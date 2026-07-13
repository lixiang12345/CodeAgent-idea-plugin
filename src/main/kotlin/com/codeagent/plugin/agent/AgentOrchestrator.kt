package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) : Disposable {
    private val settingsService = service<CodeAgentSettingsService>()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val activeRun = AtomicReference<RunContext?>()
    private val promptComposer = AgentPromptComposer(WorkspaceGuidanceLoader(project.basePath?.let(Path::of)))

    fun start(
        history: List<AgentMessage>,
        mode: String,
        listener: AgentRunListener,
    ) {
        cancel()
        val settings = settingsService.snapshot()
        val context = RunContext()
        activeRun.set(context)

        executor.execute {
            listener.onRunStateChanged("running")
            try {
                val prompt = promptComposer.compose(mode)
                LOG.debug(
                    "Starting agent with prompt ${prompt.version}; workspace guidance=${prompt.includesWorkspaceGuidance}",
                )
                AgentLoop(
                    gateway = OpenAIModelGateway(settings),
                    tools = AgentToolExecutor(project),
                    systemPrompt = prompt.message,
                    autoApproveReadOnly = settings.autoApproveReadOnly,
                    callbacks = object : AgentLoopCallbacks {
                        override fun onAssistantDelta(delta: String) = listener.onAssistantDelta(delta)

                        override fun onAssistantMessage(content: String) = listener.onAssistantMessage(content)

                        override fun onFileChanged(call: AgentToolCall, change: FileChange) =
                            listener.onFileChanged(call, change)

                        override fun requestApproval(call: AgentToolCall, risk: ToolRisk): Boolean {
                            val approval = CompletableFuture<Boolean>()
                            context.approvals[call.id] = approval
                            listener.onToolChanged(call, "Approval required", "approval", call.arguments)
                            return try {
                                approval.join()
                            } finally {
                                context.approvals.remove(call.id)
                            }
                        }

                        override fun onToolChanged(
                            call: AgentToolCall,
                            summary: String,
                            status: String,
                            detail: String?,
                        ) = listener.onToolChanged(call, summary, status, detail)
                    },
                    isCancelled = context.cancelled::get,
                    trackFuture = context.activeFuture::set,
                ).run(history, mode)
                if (!context.cancelled.get()) listener.onRunStateChanged("idle")
            } catch (_: AgentCancelledException) {
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

    fun resolveApproval(toolId: String, approved: Boolean): Boolean =
        activeRun.get()?.approvals?.remove(toolId)?.complete(approved) ?: false

    fun cancel() {
        activeRun.getAndSet(null)?.let { context ->
            context.cancelled.set(true)
            context.activeFuture.getAndSet(null)?.cancel(true)
            context.approvals.values.forEach { it.complete(false) }
            context.approvals.clear()
        }
    }

    override fun dispose() = cancel()

    private data class RunContext(
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val activeFuture: AtomicReference<CompletableFuture<*>?> = AtomicReference(null),
        val approvals: ConcurrentHashMap<String, CompletableFuture<Boolean>> = ConcurrentHashMap(),
    )

    private fun Throwable.rootMessage(): String {
        var current = this
        while (current.cause != null) current = current.cause!!
        return current.message ?: "Agent run failed"
    }

    companion object {
        private val LOG = logger<AgentOrchestrator>()
    }
}

interface AgentRunListener {
    fun onAssistantDelta(delta: String) = Unit
    fun onAssistantMessage(content: String)
    fun onFileChanged(call: AgentToolCall, change: FileChange) = Unit
    fun onToolChanged(call: AgentToolCall, summary: String, status: String, detail: String?)
    fun onRunStateChanged(state: String)
    fun onError(message: String)
}
