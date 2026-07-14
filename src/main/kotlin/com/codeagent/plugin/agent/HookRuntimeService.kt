package com.codeagent.plugin.agent

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal data class HookExecutionContext(
    val runId: String,
    val event: String,
    val toolId: String? = null,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val error: String? = null,
)

internal data class HookExecution(
    val id: String,
    val hookId: String,
    val hookName: String,
    val event: String,
    val status: String,
    val exitCode: Int? = null,
    val startedAt: String,
    val durationMs: Long,
    val summary: String,
    val detail: String? = null,
)

internal data class HookRuntimeSnapshot(
    val configured: Int = 0,
    val automatic: Int = 0,
    val recent: List<HookExecution> = emptyList(),
)

internal data class HookDefinition(
    val id: String,
    val name: String,
    val event: String,
    val command: String,
    val timeoutSeconds: Int,
    val runPolicy: String,
    val failurePolicy: String,
    val requiredEnvironment: List<String>,
)

internal data class HookProcessOutput(
    val exitCode: Int,
    val timedOut: Boolean = false,
    val output: String = "",
)

internal fun interface HookCommandExecutor {
    fun execute(definition: HookDefinition, context: HookExecutionContext): HookProcessOutput
}

internal class HookRuntime(
    private val commandExecutor: HookCommandExecutor,
    private val onChanged: () -> Unit = {},
) {
    private val lock = Any()
    private var definitions = emptyList<HookDefinition>()
    private val recent = ArrayDeque<HookExecution>()

    fun reconcile(configurations: List<RemoteConfiguration>) {
        synchronized(lock) {
            definitions = configurations
                .filter { it.kind == "hooks" }
                .mapNotNull(::hookDefinition)
                .sortedWith(compareBy(HookDefinition::event, HookDefinition::id))
        }
        onChanged()
    }

    fun runLifecycle(event: String, context: HookExecutionContext) {
        require(event in SUPPORTED_EVENTS) { "Unsupported hook event: $event" }
        val selected = synchronized(lock) {
            definitions.filter { it.event == event && it.runPolicy == "automatic" }
        }
        selected.forEach { definition ->
            val execution = execute(definition, context.copy(event = event))
            if (execution.status != "completed" && definition.failurePolicy == "fail-run") {
                throw HookExecutionException("${definition.name} blocked $event: ${execution.summary}")
            }
        }
    }

    fun test(hookId: String): HookExecution {
        val definition = synchronized(lock) {
            definitions.firstOrNull { it.id == hookId }
        } ?: error("Unknown or disabled hook: $hookId")
        return execute(
            definition,
            HookExecutionContext(
                runId = "test-${UUID.randomUUID()}",
                event = definition.event,
            ),
        )
    }

    fun snapshot(): HookRuntimeSnapshot = synchronized(lock) {
        HookRuntimeSnapshot(
            configured = definitions.size,
            automatic = definitions.count { it.runPolicy == "automatic" },
            recent = recent.toList(),
        )
    }

    private fun execute(definition: HookDefinition, context: HookExecutionContext): HookExecution {
        val startedAt = Instant.now()
        val execution = runCatching {
            val output = commandExecutor.execute(definition, context)
            val status = when {
                output.timedOut -> "timeout"
                output.exitCode == 0 -> "completed"
                else -> "failed"
            }
            HookExecution(
                id = UUID.randomUUID().toString(),
                hookId = definition.id,
                hookName = definition.name,
                event = definition.event,
                status = status,
                exitCode = output.exitCode,
                startedAt = startedAt.toString(),
                durationMs = java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                summary = when (status) {
                    "completed" -> "Completed"
                    "timeout" -> "Timed out after ${definition.timeoutSeconds}s"
                    else -> "Exited with ${output.exitCode}"
                },
                detail = output.output.takeLast(MAX_OUTPUT_CHARS).takeIf(String::isNotBlank),
            )
        }.getOrElse { error ->
            HookExecution(
                id = UUID.randomUUID().toString(),
                hookId = definition.id,
                hookName = definition.name,
                event = definition.event,
                status = "failed",
                startedAt = startedAt.toString(),
                durationMs = java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                summary = error.message ?: "Hook execution failed",
            )
        }
        synchronized(lock) {
            recent.addFirst(execution)
            while (recent.size > MAX_RECENT_EXECUTIONS) recent.removeLast()
        }
        onChanged()
        return execution
    }

    companion object {
        private val SUPPORTED_EVENTS = setOf("before-run", "after-run", "before-tool", "after-tool", "on-error")
        private const val MAX_RECENT_EXECUTIONS = 50
        private const val MAX_OUTPUT_CHARS = 20_000
    }
}

internal class HookExecutionException(message: String) : IllegalStateException(message)

@Service(Service.Level.PROJECT)
class HookRuntimeService(project: Project) {
    private val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    private val executor = AppExecutorUtil.getAppExecutorService()
    @Volatile
    private var listener: (() -> Unit)? = null
    private val runtime = HookRuntime(ShellHookCommandExecutor(root)) { listener?.invoke() }

    internal fun reconcile(configurations: List<RemoteConfiguration>) = runtime.reconcile(configurations)

    internal fun runLifecycle(event: String, context: HookExecutionContext) = runtime.runLifecycle(event, context)

    internal fun test(hookId: String): CompletableFuture<HookExecution> =
        CompletableFuture.supplyAsync({ runtime.test(hookId) }, executor)

    internal fun snapshot(): HookRuntimeSnapshot = runtime.snapshot()

    fun setChangeListener(listener: (() -> Unit)?) {
        this.listener = listener
    }
}

private class ShellHookCommandExecutor(
    private val root: Path,
) : HookCommandExecutor {
    override fun execute(definition: HookDefinition, context: HookExecutionContext): HookProcessOutput {
        val shellCommand = if (SystemInfo.isWindows) {
            listOf(System.getenv("ComSpec") ?: "cmd.exe", "/c", definition.command)
        } else {
            listOf("/bin/zsh", "-lc", definition.command)
        }
        val environment = linkedMapOf<String, String>()
        DEFAULT_ENVIRONMENT.forEach { name ->
            System.getenv(name)?.let { value -> environment[name] = value }
        }
        definition.requiredEnvironment.forEach { name ->
            System.getenv(name)?.let { value -> environment[name] = value }
        }
        environment += mapOf(
            "CODEAGENT_HOOK_EVENT" to context.event,
            "CODEAGENT_RUN_ID" to context.runId,
            "CODEAGENT_PROJECT_ROOT" to root.toString(),
        )
        context.toolId?.let { environment["CODEAGENT_TOOL_ID"] = it }
        context.toolName?.let { environment["CODEAGENT_TOOL_NAME"] = it }
        context.toolStatus?.let { environment["CODEAGENT_TOOL_STATUS"] = it }
        context.error?.take(MAX_ERROR_CHARS)?.let { environment["CODEAGENT_ERROR"] = it }
        val output = CapturingProcessHandler(
            GeneralCommandLine(shellCommand)
                .withWorkDirectory(root.toFile())
                .withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
                .withEnvironment(environment),
        ).runProcess(definition.timeoutSeconds * 1_000)
        val combined = buildString {
            append(output.stdout)
            if (output.stderr.isNotBlank()) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append(output.stderr)
            }
        }.takeLast(MAX_PROCESS_OUTPUT_CHARS)
        return HookProcessOutput(
            exitCode = output.exitCode,
            timedOut = output.isTimeout,
            output = combined,
        )
    }

    companion object {
        private val DEFAULT_ENVIRONMENT = setOf(
            "PATH",
            "HOME",
            "USER",
            "SHELL",
            "LANG",
            "LC_ALL",
            "TMPDIR",
            "SystemRoot",
            "ComSpec",
            "PATHEXT",
            "TEMP",
            "TMP",
        )
        private const val MAX_ERROR_CHARS = 4_000
        private const val MAX_PROCESS_OUTPUT_CHARS = 40_000
    }
}

private fun hookDefinition(configuration: RemoteConfiguration): HookDefinition? {
    val value = configuration.value
    if (value["enabled"]?.jsonPrimitive?.booleanOrNull == false) return null
    val name = value["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val event = value["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val command = value["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val timeoutSeconds = value["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: 60
    val runPolicy = value["runPolicy"]?.jsonPrimitive?.contentOrNull ?: "manual"
    val failurePolicy = value["failurePolicy"]?.jsonPrimitive?.contentOrNull ?: "continue"
    val requiredEnvironment = value["requiredEnvironment"]
        ?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        .orEmpty()
    if (name.isBlank() || event !in setOf("before-run", "after-run", "before-tool", "after-tool", "on-error")) return null
    if (command.isBlank() || timeoutSeconds !in 1..600) return null
    if (runPolicy !in setOf("manual", "automatic") || failurePolicy !in setOf("continue", "fail-run")) return null
    return HookDefinition(
        id = configuration.id,
        name = name,
        event = event,
        command = command,
        timeoutSeconds = timeoutSeconds,
        runPolicy = runPolicy,
        failurePolicy = failurePolicy,
        requiredEnvironment = requiredEnvironment,
    )
}
