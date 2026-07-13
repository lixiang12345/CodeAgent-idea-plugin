package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentLoopTest {
    @Test
    fun `executes a tool and returns the final answer`() {
        val turns = ArrayDeque(
            listOf(
                ModelTurn(null, listOf(AgentToolCall("call-1", "read_file", "{\"path\":\"README.md\"}"))),
                ModelTurn("The project is a plugin.", emptyList()),
            ),
        )
        val assistantMessages = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val loop = AgentLoop(
            gateway = object : ModelGateway {
                override fun complete(messages: List<AgentMessage>, tools: List<AgentToolDefinition>) =
                    CompletableFuture.completedFuture(turns.removeFirst())
            },
            tools = object : AgentToolRunner {
                override fun definitions(mode: String) = listOf(
                    AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") }),
                )
                override fun risk(toolName: String) = ToolRisk.READ_ONLY
                override fun execute(call: AgentToolCall) =
                    CompletableFuture.completedFuture(ToolExecutionResult("README contents", "Read README.md"))
            },
            autoApproveReadOnly = true,
            callbacks = object : AgentLoopCallbacks {
                override fun onAssistantMessage(content: String) { assistantMessages += content }
                override fun requestApproval(call: AgentToolCall, risk: ToolRisk) = true
                override fun onToolChanged(call: AgentToolCall, summary: String, status: String, detail: String?) {
                    statuses += status
                }
            },
            isCancelled = { false },
            trackFuture = {},
        )

        loop.run(listOf(AgentMessage("user", "What is this project?")), "ask")

        assertEquals(listOf("running", "completed"), statuses)
        assertEquals(listOf("The project is a plugin."), assistantMessages)
    }
}
