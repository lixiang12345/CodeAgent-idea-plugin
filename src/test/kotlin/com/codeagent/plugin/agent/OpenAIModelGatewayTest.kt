package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAIModelGatewayTest {
    @Test
    fun `maps OpenAI-compatible tool calls`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = null
        var requestBody = ""
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.bufferedReader().readText()
            val body = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}}]}}]}
            """.trimIndent()
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val gateway = OpenAIModelGateway(
                settings = CodeAgentSettings(
                    endpoint = "http://127.0.0.1:${server.address.port}/v1",
                    model = "test-model",
                    nodePath = "node",
                    autoApproveReadOnly = true,
                    apiKey = "secret",
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            )
            val result = gateway.complete(
                messages = listOf(AgentMessage("user", "Read the README")),
                tools = listOf(
                    AgentToolDefinition(
                        name = "read_file",
                        description = "Read a file",
                        parameters = buildJsonObject { put("type", "object") },
                    ),
                ),
            ).get(5, TimeUnit.SECONDS)

            assertEquals("Bearer secret", authorization)
            assertTrue(requestBody.contains("test-model"))
            assertEquals("read_file", result.toolCalls.single().name)
            assertEquals("{\"path\":\"README.md\"}", result.toolCalls.single().arguments)
        } finally {
            server.stop(0)
        }
    }
}
