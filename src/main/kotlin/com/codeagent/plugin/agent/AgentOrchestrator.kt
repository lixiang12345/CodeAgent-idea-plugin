package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.OidcLoginService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.Closeable
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val AGENT_PREFLIGHT_TIMEOUT_SECONDS = 45L

@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) : Disposable {
    private val settingsService = service<CodeAgentSettingsService>()
    private val oidcLogin = service<OidcLoginService>()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val activeRun = AtomicReference<RunContext?>()
    private val customizations = project.service<WorkspaceCustomizationService>()
    private val pluginRuntime = project.service<PluginRuntimeService>()
    private val mcpRuntime = project.service<McpRuntimeService>()
    private val hookRuntime = project.service<HookRuntimeService>()
    private val guidanceLoader = WorkspaceGuidanceLoader(project.basePath?.let(Path::of))
    private val json = Json { ignoreUnknownKeys = true }
    private val log = Logger.getInstance(AgentOrchestrator::class.java)

    fun start(
        history: List<AgentMessage>,
        historySummary: String?,
        mode: String,
        agentProfileId: String,
        model: String?,
        enabledSkillIds: Set<String>,
        enabledRuleIds: Set<String>,
        listener: AgentRunListener,
    ) {
        cancel()
        val context = RunContext(mode = mode)
        activeRun.set(context)

        executor.execute {
            listener.onRunStateChanged("running")
            val startedAt = System.nanoTime()
            try {
                log.info("Agent run ${context.hookRunId} preflight started")
                hookRuntime.runLifecycle(
                    "before-run",
                    HookExecutionContext(context.hookRunId, "before-run"),
                )
                require(mode in setOf("agent", "chat", "ask")) { "Unsupported mode: $mode" }
                require(history.none { it.role == "system" }) { "Conversation history cannot contain system messages" }
                awaitStage("authentication", oidcLogin.ensureFreshToken())
                val settings = settingsService.snapshot()
                val customization = customizations.refresh()
                val pluginContributions = pluginRuntime.snapshot()
                val client = RemoteAgentClient(settings)
                context.client.set(client)
                val remoteTools = awaitStage("tool discovery", client.tools())
                    .data
                    .filter(RemoteToolCapability::available)
                awaitStage("MCP preparation", mcpRuntime.prepareForRun())
                val toolRunner = AgentToolExecutor(project, client, remoteTools)
                val definitions = toolRunner.definitions(mode)
                val request = RemoteRunRequest(
                    mode = mode,
                    agentProfileId = agentProfileId,
                    model = model,
                    messages = history.map { message ->
                        RemoteMessage(
                            role = message.role,
                            content = message.content,
                            attachments = message.attachments.map { attachment ->
                                RemoteAttachment(
                                    type = attachment.type,
                                    id = attachment.id,
                                    label = attachment.label,
                                    path = attachment.path,
                                    mimeType = attachment.mimeType,
                                    data = attachment.data,
                                    textExcerpt = attachment.textExcerpt,
                                    sizeBytes = attachment.sizeBytes,
                                    metadata = attachment.metadata,
                                )
                            },
                        )
                    },
                    tools = definitions.map { definition ->
                        RemoteToolDefinition(
                            definition.name,
                            definition.description,
                            definition.parameters,
                            when (definition.risk) {
                                ToolRisk.READ_ONLY -> "read_only"
                                ToolRisk.LOCAL_STATE -> "local_state"
                                ToolRisk.MUTATING -> "mutating"
                            },
                        )
                    },
                    workspace = RemoteWorkspace(
                        guidance = guidanceLoader.load(),
                        historySummary = historySummary,
                        rules = (customization.rules + pluginContributions.rules)
                            .filter { rule ->
                                rule.trigger == "always" ||
                                    (rule.trigger == "agent" && mode == "agent") ||
                                    (rule.trigger == "manual" && rule.id in enabledRuleIds)
                            }
                            .map { RemoteWorkspaceEntry(it.name, it.path, it.content) },
                        skills = (customization.skills + pluginContributions.skills)
                            .filter { it.id in enabledSkillIds }
                            .take(WorkspaceCustomizationLoader.MAX_SELECTED_SKILLS)
                            .map { RemoteWorkspaceEntry(it.name, it.path, it.content) },
                    ),
                )
                log.info(
                    "Agent run ${context.hookRunId} streaming after ${elapsedMillis(startedAt)} ms " +
                        "with ${definitions.size} tools",
                )
                client.stream(
                    request = request,
                    onStreamChanged = { stream ->
                        if (context.cancelled.get()) stream?.close() else context.activeStream.set(stream)
                    },
                    onEvent = { type, payload ->
                        handleEvent(
                            type = type,
                            payload = payload,
                            context = context,
                            client = client,
                            toolRunner = toolRunner,
                            allowedTools = definitions.mapTo(mutableSetOf(), AgentToolDefinition::name),
                            autoApproveReadOnly = settings.autoApproveReadOnly,
                            listener = listener,
                        )
                    },
                )
                if (!context.cancelled.get()) {
                    hookRuntime.runLifecycle(
                        "after-run",
                        HookExecutionContext(context.hookRunId, "after-run"),
                    )
                    listener.onRunStateChanged("idle")
                }
            } catch (_: RemoteRunCancelledException) {
                listener.onRunStateChanged("idle")
            } catch (error: Throwable) {
                if (!context.cancelled.get()) {
                    val message = error.rootMessage()
                    log.warn("Agent run ${context.hookRunId} failed after ${elapsedMillis(startedAt)} ms: $message", error)
                    runCatching {
                        hookRuntime.runLifecycle(
                            "on-error",
                            HookExecutionContext(context.hookRunId, "on-error", error = message),
                        )
                    }
                    listener.onError(message)
                    listener.onRunStateChanged("failed")
                }
            } finally {
                activeRun.compareAndSet(context, null)
            }
        }
    }

    private fun <T> awaitStage(stage: String, future: CompletableFuture<T>): T {
        val startedAt = System.nanoTime()
        return try {
            future.orTimeout(AGENT_PREFLIGHT_TIMEOUT_SECONDS, TimeUnit.SECONDS).join().also {
                log.info("Agent preflight stage '$stage' completed in ${elapsedMillis(startedAt)} ms")
            }
        } catch (error: Throwable) {
            future.cancel(true)
            throw IllegalStateException(
                "Agent startup timed out or failed during $stage: ${error.rootMessage()}",
                error,
            )
        }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    internal fun health(): CompletableFuture<RemoteBackendHealth> = RemoteAgentClient(settingsService.snapshot()).health()

    internal fun account(): CompletableFuture<RemoteAccountResponse> = withClient(RemoteAgentClient::account)

    internal fun models(): CompletableFuture<RemoteModelsResponse> = RemoteAgentClient(settingsService.snapshot()).models()

    internal fun tools(): CompletableFuture<RemoteToolsResponse> = withClient(RemoteAgentClient::tools)

    internal fun jobs(limit: Int = 50): CompletableFuture<RemoteJobList> =
        withClient { it.jobs(limit) }

    internal fun createJob(request: RemoteJobRequest): CompletableFuture<RemoteJob> =
        withClient { it.createJob(request) }

    internal fun cancelJob(id: String): CompletableFuture<RemoteJob> =
        withClient { it.cancelJob(id) }

    internal fun configurations(kind: String): CompletableFuture<RemoteConfigurationList> =
        withClient { it.configurations(kind) }

    internal fun putConfiguration(kind: String, id: String, value: kotlinx.serialization.json.JsonObject): CompletableFuture<RemoteConfiguration> =
        withClient { it.putConfiguration(kind, id, value) }

    internal fun deleteConfiguration(kind: String, id: String): CompletableFuture<Boolean> =
        withClient { it.deleteConfiguration(kind, id) }

    internal fun enhance(
        text: String,
        mode: String,
        model: String?,
        agentProfileId: String,
        repositoryContext: String,
        conversationContext: String,
    ): CompletableFuture<RemoteEnhanceResponse> =
        withClient {
            it.enhance(
                text,
                mode,
                model,
                agentProfileId,
                repositoryContext,
                conversationContext,
            )
        }

    private fun <T> withClient(request: (RemoteAgentClient) -> CompletableFuture<T>): CompletableFuture<T> =
        oidcLogin.ensureFreshToken().thenCompose { request(RemoteAgentClient(settingsService.snapshot())) }

    fun resolveApproval(toolId: String, approved: Boolean): Boolean {
        val context = activeRun.get() ?: return false
        val approval = context.approvals.remove(toolId) ?: return false
        return approval.complete(approved)
    }

    fun cancel() {
        activeRun.getAndSet(null)?.let { context ->
            context.cancelled.set(true)
            context.activeStream.getAndSet(null)?.close()
            context.activeFuture.getAndSet(null)?.cancel(true)
            context.approvals.values.forEach { it.complete(false) }
            context.approvals.clear()
            context.remoteRunId.get()?.let { runId -> context.client.get()?.cancel(runId) }
        }
    }

    override fun dispose() = cancel()

    private data class RunContext(
        val mode: String,
        val hookRunId: String = UUID.randomUUID().toString(),
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val remoteRunId: AtomicReference<String?> = AtomicReference(null),
        val client: AtomicReference<RemoteAgentClient?> = AtomicReference(null),
        val activeStream: AtomicReference<Closeable?> = AtomicReference(null),
        val activeFuture: AtomicReference<CompletableFuture<*>?> = AtomicReference(null),
        val approvals: ConcurrentHashMap<String, CompletableFuture<Boolean>> = ConcurrentHashMap(),
    )

    private fun handleEvent(
        type: String,
        payload: JsonElement,
        context: RunContext,
        client: RemoteAgentClient,
        toolRunner: AgentToolRunner,
        allowedTools: Set<String>,
        autoApproveReadOnly: Boolean,
        listener: AgentRunListener,
    ) {
        ensureActive(context)
        when (type) {
            "run.started" -> json.decodeFromJsonElement<RemoteRunStarted>(payload).let { started ->
                context.remoteRunId.set(started.runId)
                toolRunner.updateRetrievalBudget(started.retrievalBudgetTokens)
            }
            "context.updated" -> listener.onContextUpdated(json.decodeFromJsonElement(payload))
            "tool.catalog.updated" -> listener.onToolCatalogUpdated(json.decodeFromJsonElement(payload))
            "verification.updated" -> listener.onVerificationUpdated(json.decodeFromJsonElement(payload))
            "model.retrying" -> listener.onModelRetrying(json.decodeFromJsonElement(payload))
            "message.delta" -> json.decodeFromJsonElement<RemoteMessageDelta>(payload).let {
                listener.onAssistantDelta(it.delta, it.turnIndex)
            }
            "assistant.completed" -> json.decodeFromJsonElement<RemoteAssistantCompleted>(payload).let {
                listener.onAssistantMessage(it.content, it.turnIndex)
            }
            "tool.batch.started" -> listener.onToolBatchStarted(json.decodeFromJsonElement(payload))
            "tool.request" -> executeToolRequest(
                request = json.decodeFromJsonElement(payload),
                context = context,
                client = client,
                toolRunner = toolRunner,
                allowedTools = allowedTools,
                autoApproveReadOnly = autoApproveReadOnly,
                listener = listener,
            )
            "run.error" -> error(json.decodeFromJsonElement<RemoteRunError>(payload).message)
        }
    }

    private fun executeToolRequest(
        request: RemoteToolRequest,
        context: RunContext,
        client: RemoteAgentClient,
        toolRunner: AgentToolRunner,
        allowedTools: Set<String>,
        autoApproveReadOnly: Boolean,
        listener: AgentRunListener,
    ) {
        val runId = requireNotNull(context.remoteRunId.get()) { "Backend sent a tool request before run.started" }
        val call = AgentToolCall(request.call.id, request.call.name, request.call.arguments)
        if (call.name !in allowedTools) {
            client.submitToolResult(runId, RemoteToolResult(call.id, "failed", error = "Tool '${call.name}' is not available"))
            return
        }

        val risk = toolRunner.risk(call.name)
        if (!isToolAllowedInMode(context.mode, risk)) {
            val message = "Mutating tools are unavailable in ${context.mode} mode"
            listener.onToolChanged(call, message, "rejected", call.arguments, request.turnIndex)
            client.submitToolResult(runId, RemoteToolResult(call.id, "rejected", output = message))
            return
        }
        val needsApproval = risk == ToolRisk.MUTATING || (risk == ToolRisk.READ_ONLY && !autoApproveReadOnly)
        if (needsApproval && !requestApproval(context, call, request.turnIndex, listener)) {
            listener.onToolChanged(call, "Rejected by user", "rejected", call.arguments, request.turnIndex)
            client.submitToolResult(runId, RemoteToolResult(call.id, "rejected", output = "Rejected by user"))
            return
        }

        try {
            hookRuntime.runLifecycle(
                "before-tool",
                HookExecutionContext(
                    runId = context.hookRunId,
                    event = "before-tool",
                    toolId = call.id,
                    toolName = call.name,
                    toolStatus = "running",
                ),
            )
        } catch (error: Throwable) {
            val message = error.rootMessage()
            listener.onToolChanged(call, message, "failed", message, request.turnIndex)
            client.submitToolResult(runId, RemoteToolResult(call.id, "failed", error = message))
            throw error
        }

        listener.onToolChanged(call, "Running", "running", call.arguments, request.turnIndex)
        val future = toolRunner.execute(call)
        context.activeFuture.set(future)
        try {
            val result = future.join()
            ensureActive(context)
            result.trackedFileChanges().forEach { listener.onFileChanged(call, it) }
            hookRuntime.runLifecycle(
                "after-tool",
                HookExecutionContext(
                    runId = context.hookRunId,
                    event = "after-tool",
                    toolId = call.id,
                    toolName = call.name,
                    toolStatus = "completed",
                ),
            )
            listener.onToolChanged(call, result.summary, "completed", result.detail, request.turnIndex)
            client.submitToolResult(
                runId,
                RemoteToolResult(call.id, "completed", output = result.output, summary = result.summary),
            )
        } catch (error: RemoteRunExpiredException) {
            throw IllegalStateException(
                "Tool '${call.name}' completed locally, but backend run ${error.runId} expired before accepting its result",
                error,
            )
        } catch (error: Throwable) {
            if (context.cancelled.get()) throw RemoteRunCancelledException()
            val message = error.rootMessage()
            if (error is HookExecutionException) {
                listener.onToolChanged(call, message, "failed", message, request.turnIndex)
                client.submitToolResult(runId, RemoteToolResult(call.id, "failed", error = message))
                throw error
            }
            runCatching {
                hookRuntime.runLifecycle(
                    "after-tool",
                    HookExecutionContext(
                        runId = context.hookRunId,
                        event = "after-tool",
                        toolId = call.id,
                        toolName = call.name,
                        toolStatus = "failed",
                        error = message,
                    ),
                )
                hookRuntime.runLifecycle(
                    "on-error",
                    HookExecutionContext(
                        runId = context.hookRunId,
                        event = "on-error",
                        toolId = call.id,
                        toolName = call.name,
                        toolStatus = "failed",
                        error = message,
                    ),
                )
            }
            listener.onToolChanged(call, message, "failed", message, request.turnIndex)
            client.submitToolResult(runId, RemoteToolResult(call.id, "failed", error = message))
        } finally {
            context.activeFuture.compareAndSet(future, null)
        }
    }

    private fun requestApproval(
        context: RunContext,
        call: AgentToolCall,
        turnIndex: Int,
        listener: AgentRunListener,
    ): Boolean {
        val approval = CompletableFuture<Boolean>()
        context.approvals[call.id] = approval
        listener.onToolChanged(call, "Approval required", "approval", call.arguments, turnIndex)
        return try {
            approval.join()
        } finally {
            context.approvals.remove(call.id)
        }
    }

    private fun ensureActive(context: RunContext) {
        if (context.cancelled.get()) throw RemoteRunCancelledException()
    }

    private fun Throwable.rootMessage(): String {
        var current = this
        while (current.cause != null) current = current.cause!!
        return current.message ?: "Agent run failed"
    }

}

private class RemoteRunCancelledException : RuntimeException("Agent run cancelled")

interface AgentRunListener {
    fun onAssistantDelta(delta: String, turnIndex: Int) = Unit
    fun onContextUpdated(update: RemoteContextUpdated) = Unit
    fun onToolCatalogUpdated(update: RemoteToolCatalogUpdated) = Unit
    fun onVerificationUpdated(update: RemoteVerificationUpdated) = Unit
    fun onModelRetrying(update: RemoteModelRetrying) = Unit
    fun onToolBatchStarted(update: RemoteToolBatchStarted) = Unit
    fun onAssistantMessage(content: String?, turnIndex: Int)
    fun onFileChanged(call: AgentToolCall, change: FileChange) = Unit
    fun onToolChanged(call: AgentToolCall, summary: String, status: String, detail: String?, turnIndex: Int)
    fun onRunStateChanged(state: String)
    fun onError(message: String)
}
