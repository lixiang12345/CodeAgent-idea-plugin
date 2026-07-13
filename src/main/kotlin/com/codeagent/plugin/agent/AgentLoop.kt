package com.codeagent.plugin.agent

import java.util.concurrent.CompletableFuture

class AgentLoop(
    private val gateway: ModelGateway,
    private val tools: AgentToolRunner,
    private val systemPrompt: AgentMessage,
    private val autoApproveReadOnly: Boolean,
    private val callbacks: AgentLoopCallbacks,
    private val isCancelled: () -> Boolean,
    private val trackFuture: (CompletableFuture<*>?) -> Unit,
) {
    init {
        require(systemPrompt.role == "system" && !systemPrompt.content.isNullOrBlank()) {
            "AgentLoop requires one backend-owned system prompt"
        }
    }

    fun run(history: List<AgentMessage>, mode: String) {
        require(history.none { it.role == "system" }) { "Conversation history cannot contain system messages" }
        val definitions = tools.definitions(mode)
        val allowedNames = definitions.mapTo(mutableSetOf(), AgentToolDefinition::name)
        val messages = mutableListOf(systemPrompt)
        messages += history

        repeat(MAX_ITERATIONS) {
            ensureActive()
            val modelFuture = gateway.stream(messages, definitions, callbacks::onAssistantDelta)
            trackFuture(modelFuture)
            val turn = modelFuture.join()
            trackFuture(null)

            messages += AgentMessage(
                role = "assistant",
                content = turn.content,
                toolCalls = turn.toolCalls.takeIf { it.isNotEmpty() },
            )
            turn.content?.takeIf { it.isNotBlank() }?.let(callbacks::onAssistantMessage)

            if (turn.toolCalls.isEmpty()) return

            for (call in turn.toolCalls) {
                ensureActive()
                if (call.name !in allowedNames) {
                    messages += toolMessage(call.id, "Tool '${call.name}' is not available in $mode mode")
                    continue
                }

                val risk = tools.risk(call.name)
                val needsApproval = risk == ToolRisk.MUTATING || !autoApproveReadOnly
                if (needsApproval && !callbacks.requestApproval(call, risk)) {
                    callbacks.onToolChanged(call, "Rejected by user", "rejected", call.arguments)
                    messages += toolMessage(call.id, "Rejected by user")
                    continue
                }

                callbacks.onToolChanged(call, "Running", "running", call.arguments)
                val toolFuture = tools.execute(call)
                trackFuture(toolFuture)
                try {
                    val result = toolFuture.join()
                    result.fileChange?.let { callbacks.onFileChanged(call, it) }
                    callbacks.onToolChanged(call, result.summary, "completed", result.detail)
                    messages += toolMessage(call.id, result.output)
                } catch (error: Throwable) {
                    val message = error.rootMessage()
                    callbacks.onToolChanged(call, message, "failed", message)
                    messages += toolMessage(call.id, "Tool error: $message")
                } finally {
                    trackFuture(null)
                }
            }
        }
        error("Agent exceeded $MAX_ITERATIONS model turns")
    }

    private fun ensureActive() {
        if (isCancelled()) throw AgentCancelledException()
    }

    private fun toolMessage(callId: String, content: String) = AgentMessage(
        role = "tool",
        content = content,
        toolCallId = callId,
    )

    private fun Throwable.rootMessage(): String {
        var current = this
        while (current.cause != null) current = current.cause!!
        return current.message ?: current::class.simpleName ?: "Agent operation failed"
    }

    companion object {
        private const val MAX_ITERATIONS = 12
    }
}

interface AgentLoopCallbacks {
    fun onAssistantDelta(delta: String) = Unit
    fun onAssistantMessage(content: String)
    fun onFileChanged(call: AgentToolCall, change: FileChange) = Unit
    fun requestApproval(call: AgentToolCall, risk: ToolRisk): Boolean
    fun onToolChanged(call: AgentToolCall, summary: String, status: String, detail: String?)
}

class AgentCancelledException : RuntimeException("Agent run cancelled")
