package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.OidcLoginService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) : Disposable {
    private val settingsService = service<CodeAgentSettingsService>()
    private val oidcLogin = service<OidcLoginService>()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val activeRun = AtomicReference<RunContext?>()
    private val customizations = project.service<WorkspaceCustomizationService>()
    private val guidanceLoader = WorkspaceGuidanceLoader(project.basePath?.let(Path::of))
    private val json = Json { ignoreUnknownKeys = true }

    fun start(
        history: List<AgentMessage>,
        mode: String,
        agentProfileId: String,
        model: String?,
        enabledSkillIds: Set<String>,
        enabledRuleIds: Set<String>,
        listener: AgentRunListener,
    ) {
        cancel()
        val context = RunContext()
        activeRun.set(context)

        executor.execute {
            listener.onRunStateChanged("running")
            try {
                require(mode in setOf("agent", "chat", "ask")) { "Unsupported mode: $mode" }
                require(history.none { it.role == "system" }) { "Conversation history cannot contain system messages" }
                oidcLogin.ensureFreshToken().join()
                val settings = settingsService.snapshot()
                val customization = customizations.refresh()
                val client = RemoteAgentClient(settings)
                context.client.set(client)
                val remoteTools = client.tools().join().data.filter(RemoteToolCapability::available)
                val toolRunner = AgentToolExecutor(project, client, remoteTools)
                val definitions = toolRunner.definitions(mode)
                val request = RemoteRunRequest(
                    mode = mode,
                    agentProfileId = agentProfileId,
                    model = model,
                    messages = history.map { RemoteMessage(it.role, it.content) },
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
                        rules = customization.rules
                            .filter { rule ->
                                rule.trigger == "always" ||
                                    (rule.trigger == "agent" && mode == "agent") ||
                                    (rule.trigger == "manual" && rule.id in enabledRuleIds)
                            }
                            .map { RemoteWorkspaceEntry(it.name, it.path, it.content) },
                        skills = customization.skills
                            .filter { it.id in enabledSkillIds }
                            .take(WorkspaceCustomizationLoader.MAX_SELECTED_SKILLS)
                            .map { RemoteWorkspaceEntry(it.name, it.path, it.content) },
                    ),
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
                if (!context.cancelled.get()) listener.onRunStateChanged("idle")
            } catch (_: RemoteRunCancelledException) {
                listener.onRunStateChanged("idle")
            } catch (error: Throwable) {
                if (!context.cancelled.get()) {
                    listener.onError(error.rootMessage())
                    listener.onRunStateChanged("failed")
                }
            } finally {
                activeRun.compareAndSet(context, null)
            }
        }
    }

    internal fun health(): CompletableFuture<RemoteBackendHealth> = RemoteAgentClient(settingsService.snapshot()).health()

    internal fun account(): CompletableFuture<RemoteAccountResponse> = withClient(RemoteAgentClient::account)

    internal fun models(): CompletableFuture<RemoteModelsResponse> = withClient(RemoteAgentClient::models)

    internal fun tools(): CompletableFuture<RemoteToolsResponse> = withClient(RemoteAgentClient::tools)

    internal fun configurations(kind: String): CompletableFuture<RemoteConfigurationList> =
        withClient { it.configurations(kind) }

    internal fun putConfiguration(kind: String, id: String, value: kotlinx.serialization.json.JsonObject): CompletableFuture<RemoteConfiguration> =
        withClient { it.putConfiguration(kind, id, value) }

    internal fun deleteConfiguration(kind: String, id: String): CompletableFuture<Boolean> =
        withClient { it.deleteConfiguration(kind, id) }

    internal fun enhance(text: String, mode: String, model: String?): CompletableFuture<RemoteEnhanceResponse> =
        withClient { it.enhance(text, mode, model) }

    private fun <T> withClient(request: (RemoteAgentClient) -> CompletableFuture<T>): CompletableFuture<T> =
        oidcLogin.ensureFreshToken().thenCompose { request(RemoteAgentClient(settingsService.snapshot())) }

    fun resolveApproval(toolId: String, approved: Boolean): Boolean =
        activeRun.get()?.approvals?.remove(toolId)?.complete(approved) ?: false

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
            "run.started" -> context.remoteRunId.set(json.decodeFromJsonElement<RemoteRunStarted>(payload).runId)
            "message.delta" -> json.decodeFromJsonElement<RemoteMessageDelta>(payload).let {
                listener.onAssistantDelta(it.delta, it.turnIndex)
            }
            "assistant.completed" -> json.decodeFromJsonElement<RemoteAssistantCompleted>(payload).let {
                listener.onAssistantMessage(it.content, it.turnIndex)
            }
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
        val needsApproval = risk == ToolRisk.MUTATING || (risk == ToolRisk.READ_ONLY && !autoApproveReadOnly)
        if (needsApproval && !requestApproval(context, call, request.turnIndex, listener)) {
            listener.onToolChanged(call, "Rejected by user", "rejected", call.arguments, request.turnIndex)
            client.submitToolResult(runId, RemoteToolResult(call.id, "rejected", output = "Rejected by user"))
            return
        }

        listener.onToolChanged(call, "Running", "running", call.arguments, request.turnIndex)
        val future = toolRunner.execute(call)
        context.activeFuture.set(future)
        try {
            val result = future.join()
            ensureActive(context)
            result.fileChange?.let { listener.onFileChanged(call, it) }
            listener.onToolChanged(call, result.summary, "completed", result.detail, request.turnIndex)
            client.submitToolResult(
                runId,
                RemoteToolResult(call.id, "completed", output = result.output, summary = result.summary),
            )
        } catch (error: Throwable) {
            if (context.cancelled.get()) throw RemoteRunCancelledException()
            val message = error.rootMessage()
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
    fun onAssistantMessage(content: String?, turnIndex: Int)
    fun onFileChanged(call: AgentToolCall, change: FileChange) = Unit
    fun onToolChanged(call: AgentToolCall, summary: String, status: String, detail: String?, turnIndex: Int)
    fun onRunStateChanged(state: String)
    fun onError(message: String)
}
