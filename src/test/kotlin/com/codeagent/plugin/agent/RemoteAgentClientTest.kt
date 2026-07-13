package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteAgentClientTest {
    @Test
    fun `streams backend events and posts tool results with backend authentication`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var runAuthorization: String? = null
        var runBody = ""
        var toolResultBody = ""
        server.createContext("/v1/runs") { exchange ->
            runAuthorization = exchange.requestHeaders.getFirst("Authorization")
            runBody = exchange.requestBody.bufferedReader().readText()
            val events = """
                event: run.started
                data: {"runId":"run-1"}

                event: message.delta
                data: {"delta":"Working"}

                event: assistant.completed
                data: {"content":"Working"}

            """.trimIndent().replace("\n", "\r\n")
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { it.write(events) }
        }
        server.createContext("/v1/runs/run-1/tool-results") { exchange ->
            toolResultBody = exchange.requestBody.bufferedReader().readText()
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()

        try {
            val client = RemoteAgentClient(
                settings = CodeAgentSettings(
                    backendUrl = "http://127.0.0.1:${server.address.port}",
                    nodePath = "node",
                    autoApproveReadOnly = true,
                    backendToken = "backend-secret",
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            )
            val eventTypes = mutableListOf<String>()
            client.stream(
                request = RemoteRunRequest(
                    mode = "agent",
                    messages = listOf(RemoteMessage("user", "Inspect the project")),
                    tools = listOf(RemoteToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })),
                    workspace = RemoteWorkspace(
                        guidance = "Use repository conventions.",
                        rules = listOf(RemoteWorkspaceEntry("Tests", ".codeagent/rules/tests.md", "Add tests.")),
                    ),
                ),
                onStreamChanged = {},
                onEvent = { type, _ -> eventTypes += type },
            )
            client.submitToolResult("run-1", RemoteToolResult("call-1", "completed", output = "README"))

            assertEquals("Bearer backend-secret", runAuthorization)
            assertEquals(listOf("run.started", "message.delta", "assistant.completed"), eventTypes)
            assertTrue(runBody.contains("Use repository conventions."))
            assertTrue(runBody.contains("\"read_file\""))
            assertTrue(toolResultBody.contains("\"toolCallId\":\"call-1\""))
        } finally {
            server.stop(0)
        }
    }
}
