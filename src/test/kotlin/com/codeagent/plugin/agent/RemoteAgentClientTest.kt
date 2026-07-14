package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteAgentClientTest {
    @Test
    fun `streams backend events and posts tool results with backend authentication`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var runAuthorization: String? = null
        var modelsAuthorization: String? = null
        var toolsAuthorization: String? = null
        var accountAuthorization: String? = null
        var jobsAuthorization: String? = null
        var configurationAuthorization: String? = null
        var runBody = ""
        var toolResultBody = ""
        var backendToolBody = ""
        var jobBody = ""
        var configurationBody = ""
        var configurationDeleted = false
        server.createContext("/health") { exchange ->
            val body = """{"ok":true,"service":"codeagent-backend","protocolVersion":1}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/me") { exchange ->
            accountAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"user":{"id":"user-1","email":"developer@example.com","displayName":"Developer"},"usage":[{"kind":"agent-run","units":7}],"session":{"mode":"oidc","id":"session-1"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/runs") { exchange ->
            runAuthorization = exchange.requestHeaders.getFirst("Authorization")
            runBody = exchange.requestBody.bufferedReader().readText()
            val events = """
                event: run.started
                data: {"runId":"run-1"}

                event: message.delta
                data: {"delta":"Working","turnIndex":0}

                event: assistant.completed
                data: {"content":"Working","turnIndex":0}

            """.trimIndent().replace("\n", "\r\n")
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { it.write(events) }
        }
        server.createContext("/v1/models") { exchange ->
            modelsAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"provider":"unified-native","defaultModel":"gpt-5.6-sol","data":[{"id":"gpt-5.6-sol","ownedBy":"openai"},{"id":"claude-fable-5","ownedBy":"anthropic"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/tools/web_search") { exchange ->
            backendToolBody = exchange.requestBody.bufferedReader().readText()
            val body = """{"output":"Search result","summary":"Found 1 web result","detail":"Search result"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/tools") { exchange ->
            toolsAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val body = """
                {"data":[
                  {
                    "name":"web_search",
                    "catalogId":"web",
                    "description":"Search the web",
                    "parameters":{"type":"object"},
                    "available":true,
                    "requiredEnvironment":["WEB_SEARCH_ENDPOINT"]
                  }
                ]}
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/runs/run-1/tool-results") { exchange ->
            toolResultBody = exchange.requestBody.bufferedReader().readText()
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.createContext("/v1/jobs/job-1") { exchange ->
            jobsAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"id":"job-1","type":"history-summary","status":"completed","output":{"content":"Conversation summary","model":"gpt-5.6-sol"}}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/jobs") { exchange ->
            jobsAuthorization = exchange.requestHeaders.getFirst("Authorization")
            jobBody = exchange.requestBody.bufferedReader().readText()
            val body = """{"id":"job-1","type":"history-summary","status":"queued"}""".toByteArray()
            exchange.sendResponseHeaders(202, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/configurations/agents/review-agent") { exchange ->
            configurationAuthorization = exchange.requestHeaders.getFirst("Authorization")
            when (exchange.requestMethod) {
                "PUT" -> {
                    configurationBody = exchange.requestBody.bufferedReader().readText()
                    val body = """{"id":"review-agent","kind":"agents","value":{"name":"Review Agent","enabled":true,"agentType":"loop","systemPrompt":"Review twice","allowedTools":["read_file"],"maxTurns":8},"createdAt":"2026-07-14T00:00:00Z","updatedAt":"2026-07-14T00:00:00Z"}""".toByteArray()
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                "DELETE" -> {
                    configurationDeleted = true
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
            }
        }
        server.createContext("/v1/configurations/agents") { exchange ->
            configurationAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val body = """{"data":[{"id":"review-agent","kind":"agents","value":{"name":"Review Agent","enabled":true,"agentType":"loop","systemPrompt":"Review twice","allowedTools":["read_file"],"maxTurns":8}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
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
            val eventPayloads = mutableListOf<String>()
            client.stream(
                request = RemoteRunRequest(
                    mode = "agent",
                    model = "claude-fable-5",
                    messages = listOf(RemoteMessage("user", "Inspect the project")),
                    tools = listOf(RemoteToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") })),
                    workspace = RemoteWorkspace(
                        guidance = "Use repository conventions.",
                        rules = listOf(RemoteWorkspaceEntry("Tests", ".codeagent/rules/tests.md", "Add tests.")),
                    ),
                ),
                onStreamChanged = {},
                onEvent = { type, payload ->
                    eventTypes += type
                    eventPayloads += payload.toString()
                },
            )
            client.submitToolResult("run-1", RemoteToolResult("call-1", "completed", output = "README"))
            val health = client.health().join()
            val account = client.account().join()
            val models = client.models().join()
            val tools = client.tools().join()
            val backendTool = client.executeTool("web_search", buildJsonObject { put("query", "CodeAgent") }).join()
            val createdJob = client.createJob(
                RemoteJobRequest("history-summary", RemoteJobInput("Summarize the conversation")),
            ).join()
            val completedJob = client.job(createdJob.id).join()
            val configurations = client.configurations("agents").join()
            val savedConfiguration = client.putConfiguration(
                "agents",
                "review-agent",
                buildJsonObject {
                    put("name", "Review Agent")
                    put("systemPrompt", "Review twice")
                },
            ).join()
            val deletedConfiguration = client.deleteConfiguration("agents", "review-agent").join()

            assertEquals("Bearer backend-secret", runAuthorization)
            assertEquals("Bearer backend-secret", modelsAuthorization)
            assertEquals("Bearer backend-secret", toolsAuthorization)
            assertEquals("Bearer backend-secret", accountAuthorization)
            assertEquals("Bearer backend-secret", jobsAuthorization)
            assertEquals("Bearer backend-secret", configurationAuthorization)
            assertEquals(listOf("run.started", "message.delta", "assistant.completed"), eventTypes)
            assertTrue(eventPayloads[1].contains("\"turnIndex\":0"))
            assertTrue(eventPayloads[2].contains("\"turnIndex\":0"))
            assertTrue(runBody.contains("\"model\":\"claude-fable-5\""))
            assertTrue(runBody.contains("Use repository conventions."))
            assertTrue(runBody.contains("\"read_file\""))
            assertTrue(toolResultBody.contains("\"toolCallId\":\"call-1\""))
            assertTrue(health.ok)
            assertEquals("user-1", account.user.id)
            assertEquals("Developer", account.user.displayName)
            assertEquals("oidc", account.session.mode)
            assertEquals(7L, account.usage.single().units)
            assertEquals(1, health.protocolVersion)
            assertEquals("unified-native", models.provider)
            assertEquals("gpt-5.6-sol", models.defaultModel)
            assertEquals(listOf("gpt-5.6-sol", "claude-fable-5"), models.data.map { it.id })
            assertEquals(listOf("web_search"), tools.data.map { it.name })
            assertEquals("Search result", backendTool.output)
            assertTrue(backendToolBody.contains("\"query\":\"CodeAgent\""))
            assertTrue(jobBody.contains("\"type\":\"history-summary\""))
            assertEquals("Conversation summary", completedJob.output?.content)
            assertEquals(listOf("review-agent"), configurations.data.map { it.id })
            assertEquals("loop", savedConfiguration.value["agentType"].toString().trim('"'))
            assertTrue(configurationBody.contains("\"systemPrompt\":\"Review twice\""))
            assertTrue(deletedConfiguration)
            assertTrue(configurationDeleted)
        } finally {
            server.stop(0)
        }
    }
    @Test
    fun `surfaces structured backend error messages`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/runs") { exchange ->
            val body = """{"error":"Unsupported run mode"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val client = RemoteAgentClient(
                settings = CodeAgentSettings(
                    backendUrl = "http://127.0.0.1:${server.address.port}",
                    nodePath = "node",
                    autoApproveReadOnly = true,
                    backendToken = null,
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            )
            val error = assertFailsWith<IllegalStateException> {
                client.stream(
                    request = RemoteRunRequest("invalid", messages = emptyList(), tools = emptyList(), workspace = RemoteWorkspace()),
                    onStreamChanged = {},
                    onEvent = { _, _ -> },
                )
            }

            assertTrue(error.message.orEmpty().endsWith("Unsupported run mode"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `supports backends without optional tool discovery`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/tools") { exchange ->
            val body = """{"code":"RESOURCE_NOT_FOUND","message":"The requested API resource was not found","retryable":false}""".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/models") { exchange ->
            val body = """{"code":"RESOURCE_NOT_FOUND","message":"The requested API resource was not found","retryable":false}""".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val client = RemoteAgentClient(
                settings = CodeAgentSettings(
                    backendUrl = "http://127.0.0.1:${server.address.port}",
                    nodePath = "node",
                    autoApproveReadOnly = true,
                    backendToken = null,
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            )

            assertTrue(client.tools().join().data.isEmpty())
            val error = assertFailsWith<CompletionException> { client.models().join() }
            assertTrue(error.cause?.message.orEmpty().endsWith("The requested API resource was not found"))
        } finally {
            server.stop(0)
        }
    }

}
