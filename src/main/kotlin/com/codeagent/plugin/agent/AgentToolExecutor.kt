package com.codeagent.plugin.agent

import com.codeagent.plugin.context.ContextEngineService
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern
import kotlin.io.path.isRegularFile

class AgentToolExecutor(private val project: Project) : AgentToolRunner {
    private val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    private val guard = ProjectPathGuard(root)
    private val contextEngine = project.service<ContextEngineService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val executor = AppExecutorUtil.getAppExecutorService()

    override fun definitions(mode: String): List<AgentToolDefinition> = buildList {
        add(tool("codebase_retrieval", "Retrieve ranked project context before reading or changing code", schema(
            properties = mapOf(
                "information_request" to stringProperty("A specific description of the code, behavior, or symbols needed"),
                "max_tokens" to integerProperty("Maximum packed context tokens", 500, 20_000),
            ),
            required = listOf("information_request"),
        )))
        add(tool("read_file", "Read a UTF-8 project file with optional line bounds", schema(
            properties = mapOf(
                "path" to stringProperty("Project-relative file path"),
                "start_line" to integerProperty("First 1-based line", 1, 1_000_000),
                "end_line" to integerProperty("Last 1-based line", 1, 1_000_000),
            ),
            required = listOf("path"),
        )))
        add(tool("list_files", "List project files under a directory", schema(
            properties = mapOf(
                "path" to stringProperty("Project-relative directory, or empty for project root"),
                "max_depth" to integerProperty("Traversal depth", 1, 8),
            ),
        )))
        add(tool("search_text", "Search text or a regular expression across project files", schema(
            properties = mapOf(
                "query" to stringProperty("Text or regular expression"),
                "path" to stringProperty("Optional project-relative directory"),
                "regex" to booleanProperty("Interpret query as a regular expression"),
            ),
            required = listOf("query"),
        )))
        if (mode == "agent") {
            add(tool("write_file", "Create or replace a UTF-8 project file", schema(
                properties = mapOf(
                    "path" to stringProperty("Project-relative file path"),
                    "content" to stringProperty("Complete new file content"),
                ),
                required = listOf("path", "content"),
            )))
            add(tool("replace_text", "Replace exact text in one project file", schema(
                properties = mapOf(
                    "path" to stringProperty("Project-relative file path"),
                    "old_text" to stringProperty("Exact existing text"),
                    "new_text" to stringProperty("Replacement text"),
                    "replace_all" to booleanProperty("Replace every occurrence instead of requiring exactly one"),
                ),
                required = listOf("path", "old_text", "new_text"),
            )))
            add(tool("run_terminal", "Run a shell command in the project root", schema(
                properties = mapOf(
                    "command" to stringProperty("Shell command to run"),
                    "timeout_seconds" to integerProperty("Timeout in seconds", 1, 120),
                ),
                required = listOf("command"),
            )))
        }
        add(tool("open_file", "Open a project file in the IDE editor", schema(
            properties = mapOf("path" to stringProperty("Project-relative file path")),
            required = listOf("path"),
        )))
    }

    override fun risk(toolName: String): ToolRisk = when (toolName) {
        "write_file", "replace_text", "run_terminal" -> ToolRisk.MUTATING
        else -> ToolRisk.READ_ONLY
    }

    override fun execute(call: AgentToolCall): CompletableFuture<ToolExecutionResult> =
        CompletableFuture.supplyAsync({ executeBlocking(call) }, executor)

    private fun executeBlocking(call: AgentToolCall): ToolExecutionResult {
        val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }
            .getOrElse { error("Invalid arguments for ${call.name}: ${it.message}") }
        return when (call.name) {
            "codebase_retrieval" -> retrieve(args)
            "read_file" -> readFile(args)
            "list_files" -> listFiles(args)
            "search_text" -> searchText(args)
            "write_file" -> writeFile(args)
            "replace_text" -> replaceText(args)
            "run_terminal" -> runTerminal(args)
            "open_file" -> openFile(args)
            else -> error("Unknown tool: ${call.name}")
        }
    }

    private fun retrieve(args: JsonObject): ToolExecutionResult {
        val request = args.requiredString("information_request")
        val maxTokens = args["max_tokens"]?.jsonPrimitive?.intOrNull ?: 8_000
        val packed = contextEngine.retrieve(request, maxTokens = maxTokens).join()
        return ToolExecutionResult(
            output = packed.packedText,
            summary = "Retrieved ${packed.estimatedTokens} tokens",
            detail = packed.packedText.take(MAX_DETAIL_CHARS),
        )
    }

    private fun readFile(args: JsonObject): ToolExecutionResult {
        val relative = args.requiredString("path")
        val file = guard.existingFile(relative)
        val lines = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").split('\n')
        val start = (args["start_line"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
        val end = (args["end_line"]?.jsonPrimitive?.intOrNull ?: lines.size).coerceIn(start, lines.size.coerceAtLeast(start))
        val content = if (lines.isEmpty() || start > lines.size) "" else lines.subList(start - 1, end).joinToString("\n")
        val output = "$relative:$start-$end\n\n$content"
        return ToolExecutionResult(output, "Read $relative:$start-$end", output.take(MAX_DETAIL_CHARS))
    }

    private fun listFiles(args: JsonObject): ToolExecutionResult {
        val relative = args.string("path") ?: ""
        val directory = guard.existingDirectory(relative)
        val depth = (args["max_depth"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 8)
        val files = Files.walk(directory, depth).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { !isIgnored(it) }
                .limit(300)
                .map { root.relativize(it).toString() }
                .sorted()
                .toList()
        }
        val output = files.joinToString("\n")
        return ToolExecutionResult(output, "Listed ${files.size} files", output.take(MAX_DETAIL_CHARS))
    }

    private fun searchText(args: JsonObject): ToolExecutionResult {
        val query = args.requiredString("query")
        val directory = guard.existingDirectory(args.string("path") ?: "")
        val isRegex = args["regex"]?.jsonPrimitive?.booleanOrNull ?: false
        val pattern = Pattern.compile(if (isRegex) query else Pattern.quote(query))
        val matches = mutableListOf<String>()
        Files.walk(directory).use { stream ->
            val iterator = stream.filter { it.isRegularFile() }
                .filter { !isIgnored(it) }
                .limit(3_000)
                .iterator()
            while (iterator.hasNext() && matches.size < 80) {
                val file = iterator.next()
                if (Files.size(file) > 1_000_000) continue
                val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrNull() ?: continue
                lines.forEachIndexed { index, line ->
                    if (matches.size < 80 && pattern.matcher(line).find()) {
                        matches += "${root.relativize(file)}:${index + 1}: ${line.trim().take(240)}"
                    }
                }
            }
        }
        val output = matches.joinToString("\n").ifEmpty { "No matches" }
        return ToolExecutionResult(output, "Found ${matches.size} matches", output.take(MAX_DETAIL_CHARS))
    }

    private fun writeFile(args: JsonObject): ToolExecutionResult {
        val relative = args.requiredString("path")
        val content = args.requiredString("content", allowEmpty = true)
        val file = guard.pathForWrite(relative)
        Files.createDirectories(requireNotNull(file.parent))
        Files.writeString(
            file,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        refresh(file)
        return ToolExecutionResult("Wrote $relative (${content.length} chars)", "Wrote $relative", content.take(MAX_DETAIL_CHARS))
    }

    private fun replaceText(args: JsonObject): ToolExecutionResult {
        val relative = args.requiredString("path")
        val oldText = args.requiredString("old_text")
        val newText = args.requiredString("new_text", allowEmpty = true)
        val replaceAll = args["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
        val file = guard.existingFile(relative)
        val content = Files.readString(file, StandardCharsets.UTF_8)
        val occurrences = content.windowed(oldText.length, 1).count { it == oldText }
        require(occurrences > 0) { "old_text was not found in $relative" }
        require(replaceAll || occurrences == 1) {
            "old_text occurs $occurrences times in $relative; set replace_all=true or provide a more specific match"
        }
        val updated = if (replaceAll) content.replace(oldText, newText) else content.replaceFirst(oldText, newText)
        Files.writeString(file, updated, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
        refresh(file)
        return ToolExecutionResult(
            "Replaced ${if (replaceAll) occurrences else 1} occurrence(s) in $relative",
            "Edited $relative",
            "-${oldText.take(1200)}\n+${newText.take(1200)}",
        )
    }

    private fun runTerminal(args: JsonObject): ToolExecutionResult {
        val command = args.requiredString("command")
        val timeoutSeconds = (args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 60).coerceIn(1, 120)
        val shellCommand = if (SystemInfo.isWindows) listOf("cmd.exe", "/c", command) else listOf("/bin/zsh", "-lc", command)
        val output = CapturingProcessHandler(
            GeneralCommandLine(shellCommand)
                .withWorkDirectory(root.toFile())
                .withCharset(StandardCharsets.UTF_8),
        ).runProcess(timeoutSeconds * 1_000)
        val combined = buildString {
            append(output.stdout)
            if (output.stderr.isNotBlank()) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append(output.stderr)
            }
        }.takeLast(20_000)
        val result = "exit=${output.exitCode}${if (output.isTimeout) " timeout=true" else ""}\n$combined"
        return ToolExecutionResult(result, "$command (exit ${output.exitCode})", result.take(MAX_DETAIL_CHARS))
    }

    private fun openFile(args: JsonObject): ToolExecutionResult {
        val relative = args.requiredString("path")
        val file = guard.existingFile(relative)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
            ?: error("Cannot resolve $relative in the IDE")
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
        return ToolExecutionResult("Opened $relative", "Opened $relative")
    }

    private fun refresh(file: Path) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
        if (virtualFile != null) VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
    }

    private fun isIgnored(path: Path): Boolean {
        val relative = root.relativize(path).toString().replace('\\', '/')
        return IGNORED_SEGMENTS.any { relative == it || relative.startsWith("$it/") || relative.contains("/$it/") }
    }

    private fun tool(name: String, description: String, parameters: JsonObject) =
        AgentToolDefinition(name, description, parameters)

    private fun schema(properties: Map<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (name, value) -> put(name, value) } })
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    private fun stringProperty(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerProperty(description: String, minimum: Int, maximum: Int) = buildJsonObject {
        put("type", "integer")
        put("description", description)
        put("minimum", minimum)
        put("maximum", maximum)
    }

    private fun booleanProperty(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun JsonObject.requiredString(name: String, allowEmpty: Boolean = false): String {
        val value = get(name)?.jsonPrimitive?.contentOrNull ?: error("$name is required")
        require(allowEmpty || value.isNotBlank()) { "$name must not be blank" }
        return value
    }

    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull

    companion object {
        private const val MAX_DETAIL_CHARS = 8_000
        private val IGNORED_SEGMENTS = setOf(".git", ".idea", ".gradle", ".contextengine", "build", "dist", "node_modules", "out")
    }
}

enum class ToolRisk { READ_ONLY, MUTATING }

interface AgentToolRunner {
    fun definitions(mode: String): List<AgentToolDefinition>
    fun risk(toolName: String): ToolRisk
    fun execute(call: AgentToolCall): CompletableFuture<ToolExecutionResult>
}

data class ToolExecutionResult(
    val output: String,
    val summary: String,
    val detail: String? = null,
)

class ProjectPathGuard(private val root: Path) {
    private val normalizedRoot = root.toAbsolutePath().normalize()
    private val realRoot = normalizedRoot.toRealPath()

    fun existingFile(relative: String): Path = existing(relative).also {
        require(Files.isRegularFile(it)) { "$relative is not a file" }
    }

    fun existingDirectory(relative: String): Path = existing(relative.ifBlank { "." }).also {
        require(Files.isDirectory(it)) { "$relative is not a directory" }
    }

    fun pathForWrite(relative: String): Path {
        require(relative.isNotBlank()) { "path is required" }
        val candidate = normalizedRoot.resolve(relative).normalize()
        require(candidate.startsWith(normalizedRoot)) { "Path escapes the project: $relative" }
        var parent = candidate.parent ?: normalizedRoot
        while (!Files.exists(parent)) parent = requireNotNull(parent.parent) { "Cannot resolve parent for $relative" }
        require(parent.toRealPath().startsWith(realRoot)) { "Path escapes the project through a symlink: $relative" }
        return candidate
    }

    private fun existing(relative: String): Path {
        val candidate = normalizedRoot.resolve(relative).normalize()
        require(candidate.startsWith(normalizedRoot)) { "Path escapes the project: $relative" }
        val real = candidate.toRealPath()
        require(real.startsWith(realRoot)) { "Path escapes the project through a symlink: $relative" }
        return real
    }
}
