package com.codeagent.plugin.agent

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

internal data class WorkspaceCustomization(
    val rules: List<WorkspaceRule>,
    val skills: List<WorkspaceSkill>,
) {
    companion object {
        val EMPTY = WorkspaceCustomization(emptyList(), emptyList())
    }
}

internal data class WorkspaceRule(
    val id: String,
    val name: String,
    val path: String,
    val content: String,
    val trigger: String,
    val description: String,
    val source: String = "workspace",
)

internal data class WorkspaceSkill(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val content: String,
    val source: String = "workspace",
)

@Service(Service.Level.PROJECT)
internal class WorkspaceCustomizationService(project: Project) {
    private val projectRoot = project.basePath?.let(Path::of)
    private val loader = WorkspaceCustomizationLoader(projectRoot)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var cached: WorkspaceCustomization? = null

    @Synchronized
    fun snapshot(): WorkspaceCustomization = cached ?: loader.load().also { cached = it }

    @Synchronized
    fun refresh(): WorkspaceCustomization = loader.load().also { cached = it }

    @Synchronized
    fun saveRule(fileName: String, content: String, trigger: String, description: String): WorkspaceRule {
        require(trigger in RULE_TRIGGERS) { "Unsupported rule trigger: $trigger" }
        require(content.isNotBlank()) { "Rule content must not be blank" }
        require(content.length <= MAX_RULE_FILE_CHARS) { "Rule content exceeds $MAX_RULE_FILE_CHARS characters" }
        require(description.length <= MAX_RULE_DESCRIPTION_CHARS) { "Rule description exceeds $MAX_RULE_DESCRIPTION_CHARS characters" }
        val normalizedName = fileName.trim().removePrefix(".codeagent/rules/")
        require(RULE_FILE_NAME.matches(normalizedName)) { "Rule file name may contain letters, digits, '.', '_' and '-' and must end in .md" }
        val root = projectRoot?.toRealPath() ?: error("Project root is unavailable")
        val directory = root.resolve(".codeagent/rules")
        Files.createDirectories(directory)
        val file = directory.resolve(normalizedName).normalize()
        require(file.parent == directory) { "Nested rule paths are not supported" }
        Files.writeString(file, content.trim() + "\n")

        val relative = root.relativize(file).toString().replace('\\', '/')
        val metadata = loadRuleMetadata(root)
        metadata.triggers[relative] = trigger
        metadata.descriptions[relative] = description.trim()
        Files.writeString(root.resolve(RULE_METADATA_PATH), json.encodeToString(metadata) + "\n")
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)?.let {
            VfsUtil.markDirtyAndRefresh(false, false, false, it)
        }
        cached = loader.load()
        return requireNotNull(cached?.rules?.firstOrNull { it.path == relative })
    }

    private fun loadRuleMetadata(root: Path): RuleMetadata = runCatching {
        json.decodeFromString<RuleMetadata>(Files.readString(root.resolve(RULE_METADATA_PATH)))
    }.getOrDefault(RuleMetadata())

    companion object {
        private const val RULE_METADATA_PATH = ".codeagent/rules.json"
        private const val MAX_RULE_FILE_CHARS = 16_000
        private const val MAX_RULE_DESCRIPTION_CHARS = 240
        private val RULE_FILE_NAME = Regex("[A-Za-z0-9._-]+\\.md")
        private val RULE_TRIGGERS = setOf("always", "manual", "agent")
    }
}

internal class WorkspaceCustomizationLoader(private val projectRoot: Path?) {
    fun load(): WorkspaceCustomization = runCatching {
        val root = projectRoot?.toRealPath() ?: return WorkspaceCustomization.EMPTY
        WorkspaceCustomization(
            rules = loadRules(root),
            skills = loadSkills(root),
        )
    }.getOrDefault(WorkspaceCustomization.EMPTY)

    private fun loadRules(root: Path): List<WorkspaceRule> {
        val metadata = loadRuleMetadata(root)
        val directory = root.resolve(".codeagent/rules")
        if (!Files.isDirectory(directory)) return emptyList()
        return listChildren(directory)
            .filter { it.fileName.toString().endsWith(".md", ignoreCase = true) }
            .mapNotNull { file ->
                val content = readProjectFile(root, file) ?: return@mapNotNull null
                val relative = relativePath(root, file)
                WorkspaceRule(
                    id = relative,
                    name = markdownTitle(content) ?: file.fileName.toString().substringBeforeLast('.').humanize(),
                    path = relative,
                    content = content,
                    trigger = metadata.triggers[relative].takeIf { it in RULE_TRIGGERS } ?: "always",
                    description = metadata.descriptions[relative]?.take(MAX_DESCRIPTION_CHARS)
                        ?: markdownDescription(content, "Repository rule"),
                )
            }
            .take(MAX_RULES)
    }

    private fun loadSkills(root: Path): List<WorkspaceSkill> = buildList {
        SKILL_ROOTS.forEach { relativeRoot ->
            val directory = root.resolve(relativeRoot)
            if (!Files.isDirectory(directory)) return@forEach
            listChildren(directory).filter(Files::isDirectory).forEach { skillDirectory ->
                if (size >= MAX_SKILLS) return@buildList
                val file = skillDirectory.resolve("SKILL.md")
                val content = readProjectFile(root, file) ?: return@forEach
                val relative = relativePath(root, file)
                val body = withoutFrontMatter(content)
                add(
                    WorkspaceSkill(
                        id = relative,
                        name = markdownTitle(body) ?: skillDirectory.fileName.toString().humanize(),
                        description = markdownDescription(body),
                        path = relative,
                        content = content,
                    ),
                )
            }
        }
    }.sortedBy { it.path }

    private fun listChildren(directory: Path): List<Path> = Files.list(directory).use { stream ->
        stream.sorted().toList()
    }

    private fun readProjectFile(root: Path, file: Path): String? = runCatching {
        if (!Files.isRegularFile(file)) return null
        val resolved = file.toRealPath()
        if (!resolved.startsWith(root)) return null
        Files.newBufferedReader(resolved).use { reader ->
            val content = StringBuilder()
            val buffer = CharArray(4096)
            while (content.length < MAX_FILE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_FILE_CHARS - content.length))
                if (count < 0) break
                content.append(buffer, 0, count)
            }
            content.toString().trim().takeIf(String::isNotEmpty)
        }
    }.getOrNull()

    private fun relativePath(root: Path, file: Path): String =
        root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')

    private fun markdownTitle(content: String): String? = content.lineSequence()
        .firstOrNull { it.startsWith("# ") }
        ?.removePrefix("# ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    private fun markdownDescription(content: String, fallback: String = "Repository skill"): String = content.lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
        ?.take(MAX_DESCRIPTION_CHARS)
        ?: fallback

    private fun withoutFrontMatter(content: String): String {
        val lines = content.lines()
        if (lines.firstOrNull()?.trim() != "---") return content
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end < 0) content else lines.drop(end + 2).joinToString("\n")
    }

    private fun String.humanize(): String = replace('-', ' ').replace('_', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

    companion object {
        const val MAX_SELECTED_SKILLS = 8
        private const val MAX_RULES = 32
        private const val MAX_SKILLS = 64
        private const val MAX_FILE_CHARS = 16_000
        private const val MAX_DESCRIPTION_CHARS = 180
        private val SKILL_ROOTS = listOf(".codeagent/skills", ".agents/skills")
        private const val RULE_METADATA_PATH = ".codeagent/rules.json"
        private val RULE_TRIGGERS = setOf("always", "manual", "agent")
        private val METADATA_JSON = Json { ignoreUnknownKeys = true }
    }

    private fun loadRuleMetadata(root: Path): RuleMetadata = runCatching {
        METADATA_JSON.decodeFromString<RuleMetadata>(Files.readString(root.resolve(RULE_METADATA_PATH)))
    }.getOrDefault(RuleMetadata())
}

@Serializable
private data class RuleMetadata(
    val triggers: MutableMap<String, String> = linkedMapOf(),
    val descriptions: MutableMap<String, String> = linkedMapOf(),
)

internal class WorkspaceGuidanceLoader(private val projectRoot: Path?) {
    fun load(): String? = runCatching {
        val root = projectRoot?.toRealPath() ?: return null
        val guidanceFile = root.resolve("AGENTS.md")
        if (!Files.isRegularFile(guidanceFile)) return null
        val resolvedFile = guidanceFile.toRealPath()
        if (!resolvedFile.startsWith(root)) return null

        Files.newBufferedReader(resolvedFile).use { reader ->
            val content = StringBuilder()
            val buffer = CharArray(4096)
            while (content.length < MAX_GUIDANCE_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_GUIDANCE_CHARS - content.length))
                if (count < 0) break
                content.append(buffer, 0, count)
            }
            content.toString().trim().takeIf(String::isNotEmpty)
        }
    }.getOrNull()

    companion object {
        private const val MAX_GUIDANCE_CHARS = 16_000
    }
}
