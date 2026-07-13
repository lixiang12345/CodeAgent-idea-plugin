package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        val assistantDeltas = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val fileChanges = mutableListOf<FileChange>()
        val requests = mutableListOf<List<AgentMessage>>()
        val systemPrompt = AgentMessage("system", "Backend-owned test policy")
        val loop = AgentLoop(
            gateway = object : ModelGateway {
                override fun complete(messages: List<AgentMessage>, tools: List<AgentToolDefinition>): CompletableFuture<ModelTurn> {
                    requests += messages.toList()
                    return CompletableFuture.completedFuture(turns.removeFirst())
                }
            },
            tools = object : AgentToolRunner {
                override fun definitions(mode: String) = listOf(
                    AgentToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") }),
                )
                override fun risk(toolName: String) = ToolRisk.READ_ONLY
                override fun execute(call: AgentToolCall) =
                    CompletableFuture.completedFuture(
                        ToolExecutionResult(
                            "README contents",
                            "Read README.md",
                            fileChange = FileChange("README.md", "old", "new"),
                        ),
                    )
            },
            systemPrompt = systemPrompt,
            autoApproveReadOnly = true,
            callbacks = object : AgentLoopCallbacks {
                override fun onAssistantDelta(delta: String) { assistantDeltas += delta }
                override fun onAssistantMessage(content: String) { assistantMessages += content }
                override fun onFileChanged(call: AgentToolCall, change: FileChange) { fileChanges += change }
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
        assertEquals(listOf("The project is a plugin."), assistantDeltas)
        assertEquals(listOf(FileChange("README.md", "old", "new")), fileChanges)
        assertEquals(systemPrompt, requests.first().first())
    }

    @Test
    fun `rejects system instructions in conversation history`() {
        val loop = AgentLoop(
            gateway = object : ModelGateway {
                override fun complete(messages: List<AgentMessage>, tools: List<AgentToolDefinition>) =
                    error("Gateway must not be called")
            },
            tools = object : AgentToolRunner {
                override fun definitions(mode: String) = emptyList<AgentToolDefinition>()
                override fun risk(toolName: String) = ToolRisk.READ_ONLY
                override fun execute(call: AgentToolCall) = error("Tool must not be called")
            },
            systemPrompt = AgentMessage("system", "Backend policy"),
            autoApproveReadOnly = true,
            callbacks = object : AgentLoopCallbacks {
                override fun onAssistantMessage(content: String) = Unit
                override fun requestApproval(call: AgentToolCall, risk: ToolRisk) = false
                override fun onToolChanged(
                    call: AgentToolCall,
                    summary: String,
                    status: String,
                    detail: String?,
                ) = Unit
            },
            isCancelled = { false },
            trackFuture = {},
        )

        assertFailsWith<IllegalArgumentException> {
            loop.run(listOf(AgentMessage("system", "Frontend override")), "agent")
        }
    }
}
