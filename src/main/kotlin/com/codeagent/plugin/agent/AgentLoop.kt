package com.codeagent.plugin.agent

import java.util.concurrent.CompletableFuture

class AgentLoop(
    private val gateway: ModelGateway,
    private val tools: AgentToolRunner,
    private val autoApproveReadOnly: Boolean,
    private val callbacks: AgentLoopCallbacks,
    private val isCancelled: () -> Boolean,
    private val trackFuture: (CompletableFuture<*>?) -> Unit,
) {
    fun run(history: List<AgentMessage>, mode: String) {
        val definitions = tools.definitions(mode)
        val allowedNames = definitions.mapTo(mutableSetOf(), AgentToolDefinition::name)
        val messages = mutableListOf(systemMessage(mode))
        messages += history

        repeat(MAX_ITERATIONS) {
            ensureActive()
            val modelFuture = gateway.complete(messages, definitions)
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

    private fun systemMessage(mode: String): AgentMessage = AgentMessage(
        role = "system",
        content = buildString {
            append("You are CodeAgent, an IDE-native software engineering agent. ")
            append("Use codebase_retrieval before broad exploration. Read existing code before editing. ")
            append("Keep changes scoped, run relevant checks, and report concrete results. ")
            if (mode == "ask") {
                append("You are in Ask mode: explain and investigate, but do not modify files or run shell commands.")
            } else {
                append("You are in Agent mode: use tools to complete the task. Mutating tools require user approval.")
            }
        },
    )

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
    fun onAssistantMessage(content: String)
    fun requestApproval(call: AgentToolCall, risk: ToolRisk): Boolean
    fun onToolChanged(call: AgentToolCall, summary: String, status: String, detail: String?)
}

class AgentCancelledException : RuntimeException("Agent run cancelled")
