package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettings
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteAgentClientTest {
    @Test
    fun `sends BYOK credentials only to model endpoints`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val modelProvider = AtomicReference<String?>(null)
        val modelKey = AtomicReference<String?>(null)
        val accountKey = AtomicReference<String?>(null)
        server.createContext("/v1/models") { exchange ->
            modelProvider.set(exchange.requestHeaders.getFirst("X-CodeAgent-BYOK-Provider"))
            modelKey.set(exchange.requestHeaders.getFirst("X-CodeAgent-BYOK-API-Key"))
            val body = """{"provider":"byok-openai","defaultModel":"gpt-byok","data":[{"id":"gpt-byok"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/me") { exchange ->
            accountKey.set(exchange.requestHeaders.getFirst("X-CodeAgent-BYOK-API-Key"))
            val body = """{"user":{"id":"local-user"},"usage":[],"session":{"mode":"local"}}""".toByteArray()
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
                    backendToken = null,
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                byokCredentials = ByokRequestCredentials.OpenAi("sk-transient"),
            )
            client.models().join()
            client.account().join()
            assertEquals("openai", modelProvider.get())
            assertEquals("sk-transient", modelKey.get())
            assertEquals(null, accountKey.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `requests inline completion with bounded editor context and authentication`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authorization = AtomicReference<String?>(null)
        val requestBody = AtomicReference("")
        server.createContext("/v1/completions") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            requestBody.set(exchange.requestBody.bufferedReader().readText())
            val body = """{"completion":"println(\"hello\")","model":"gpt-5.6-sol"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
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
                    backendToken = "completion-secret",
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            )

            val response = client.completion(
                prefix = "fun main() {\n  ",
                suffix = "\n}",
                path = "src/Main.kt",
                language = "Kotlin",
                model = "gpt-5.6-sol",
            ).join()

            assertEquals("Bearer completion-secret", authorization.get())
            assertTrue(requestBody.get().contains("\"prefix\":\"fun main() {\\n  \""))
            assertTrue(requestBody.get().contains("\"suffix\":\"\\n}\""))
            assertTrue(requestBody.get().contains("\"path\":\"src/Main.kt\""))
            assertTrue(requestBody.get().contains("\"language\":\"Kotlin\""))
            assertEquals("println(\"hello\")", response.completion)
        } finally {
            server.stop(0)
        }
    }

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
        var jobListQuery: String? = null
        var jobCancelled = false
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

                event: run.completed
                data: {"runId":"run-1"}

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
                    "risk":"read_only",
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
            val body = when (exchange.requestMethod) {
                "DELETE" -> {
                    jobCancelled = true
                    """{"id":"job-1","type":"history-summary","status":"cancelled","input":{"prompt":"Summarize the conversation"},"error":"Cancelled by user"}"""
                }
                else -> """{"id":"job-1","type":"history-summary","status":"completed","input":{"prompt":"Summarize the conversation"},"output":{"content":"Conversation summary","model":"gpt-5.6-sol"},"createdAt":"2026-07-14T00:00:00Z","updatedAt":"2026-07-14T00:00:01Z"}"""
            }.toByteArray()
            exchange.sendResponseHeaders(if (exchange.requestMethod == "DELETE") 202 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/jobs") { exchange ->
            jobsAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val body = if (exchange.requestMethod == "GET") {
                jobListQuery = exchange.requestURI.query
                """{"data":[{"id":"job-1","type":"history-summary","status":"completed","input":{"prompt":"Summarize the conversation"},"output":{"content":"Conversation summary","model":"gpt-5.6-sol"}}]}""".toByteArray()
            } else {
                jobBody = exchange.requestBody.bufferedReader().readText()
                """{"id":"job-1","type":"history-summary","status":"queued","input":{"prompt":"Summarize the conversation"}}""".toByteArray()
            }
            exchange.sendResponseHeaders(if (exchange.requestMethod == "GET") 200 else 202, body.size.toLong())
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
                    agentProfileId = "context-review",
                    model = "claude-fable-5",
                    messages = listOf(
                        RemoteMessage(
                            role = "user",
                            content = "Inspect the project",
                            attachments = listOf(
                                RemoteAttachment(
                                    type = "ide_state",
                                    id = "editor:src/Main.kt",
                                    label = "Active editor: Main.kt",
                                    path = "src/Main.kt",
                                    mimeType = "text/plain",
                                    textExcerpt = "fun main() = Unit",
                                    sizeBytes = 17,
                                    metadata = mapOf("scope" to "caret_window"),
                                ),
                            ),
                        ),
                    ),
                    tools = listOf(RemoteToolDefinition("read_file", "Read", buildJsonObject { put("type", "object") }, "read_only")),
                    workspace = RemoteWorkspace(
                        guidance = "Use repository conventions.",
                        historySummary = "Earlier work completed indexing.",
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
            val jobs = client.jobs(25).join()
            val cancelledJob = client.cancelJob(createdJob.id).join()
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
            assertEquals(listOf("run.started", "message.delta", "assistant.completed", "run.completed"), eventTypes)
            assertTrue(eventPayloads[1].contains("\"turnIndex\":0"))
            assertTrue(eventPayloads[2].contains("\"turnIndex\":0"))
            assertTrue(runBody.contains("\"model\":\"claude-fable-5\""))
            assertTrue(runBody.contains("\"agentProfileId\":\"context-review\""))
            assertTrue(runBody.contains("\"type\":\"ide_state\""))
            assertTrue(runBody.contains("\"id\":\"editor:src/Main.kt\""))
            assertTrue(runBody.contains("\"textExcerpt\":\"fun main() = Unit\""))
            assertTrue(runBody.contains("\"scope\":\"caret_window\""))
            assertTrue(runBody.contains("Use repository conventions."))
            assertTrue(runBody.contains("\"historySummary\":\"Earlier work completed indexing.\""))
            assertTrue(runBody.contains("\"read_file\""))
            assertTrue(runBody.contains("\"risk\":\"read_only\""))
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
            assertEquals("read_only", tools.data.single().risk)
            assertEquals("Search result", backendTool.output)
            assertTrue(backendToolBody.contains("\"query\":\"CodeAgent\""))
            assertTrue(jobBody.contains("\"type\":\"history-summary\""))
            assertEquals("Conversation summary", completedJob.output?.content)
            assertEquals("Summarize the conversation", jobs.data.single().input.prompt)
            assertEquals("limit=25", jobListQuery)
            assertTrue(jobCancelled)
            assertEquals("cancelled", cancelledJob.status)
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
    fun `conversation writes include default empty collections`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestBody = AtomicReference("")
        server.createContext("/v1/conversations/thread-empty") { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().readText())
            val body = """{"id":"thread-empty","title":"Empty","mode":"agent","updatedAt":1000,"selectedAgentProfileId":"general","selectedSkillIds":[],"selectedRuleIds":[],"pinned":false,"messages":[],"tasks":[],"tools":[],"version":1}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
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
                    backendToken = null,
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            )
            val saved = client.putConversation(
                RemoteConversation(id = "thread-empty", title = "Empty", mode = "agent", updatedAt = 1_000),
                expectedVersion = null,
            ).join()

            assertEquals(1, saved.version)
            assertTrue(requestBody.get().contains("\"messages\":[]"))
            assertTrue(requestBody.get().contains("\"tasks\":[]"))
            assertTrue(requestBody.get().contains("\"tools\":[]"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `surfaces structured backend error messages`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestBody = AtomicReference("")
        server.createContext("/v1/runs") { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().readText())
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
            assertFalse(requestBody.get().contains("\"model\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fails when backend stream closes without a terminal event`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/runs") { exchange ->
            exchange.requestBody.close()
            val events =
                "event: run.started\r\n" +
                    "data: {\"runId\":\"run-disconnected\"}\r\n\r\n" +
                    "event: message.delta\r\n" +
                    "data: {\"delta\":\"Partial\",\"turnIndex\":0}\r\n\r\n"
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { it.write(events) }
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

            val error = assertFailsWith<RemoteStreamDisconnectedException> {
                client.stream(
                    request = RemoteRunRequest("agent", messages = emptyList(), tools = emptyList(), workspace = RemoteWorkspace()),
                    onStreamChanged = {},
                    onEvent = { _, _ -> },
                )
            }

            assertTrue(error.message.orEmpty().contains("disconnected before completion"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `continues a streaming run after delayed approval posts a tool result`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val runRequestReceived = CountDownLatch(1)
        val approvalShown = CountDownLatch(1)
        val approvalGranted = CountDownLatch(1)
        val toolResultReceived = CountDownLatch(1)
        val streamCompleted = CountDownLatch(1)
        val allowResponseClose = CountDownLatch(1)
        val toolResultBody = AtomicReference("")
        server.executor = executor
        server.createContext("/v1/runs") { exchange ->
            exchange.requestBody.close()
            runRequestReceived.countDown()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write(
                    "event: run.started\r\n" +
                        "data: {\"runId\":\"run-approval\"}\r\n\r\n" +
                        "event: tool.request\r\n" +
                        "data: {\"turnIndex\":0,\"call\":{\"id\":\"call-approval\",\"name\":\"run_terminal\",\"arguments\":{\"command\":\"pwd\"}}}\r\n\r\n",
                )
                writer.flush()
                check(toolResultReceived.await(5, TimeUnit.SECONDS)) { "Tool result was not received" }
                writer.write(
                    "event: tool.completed\r\n" +
                        "data: {\"turnIndex\":0,\"call\":{\"id\":\"call-approval\",\"name\":\"run_terminal\",\"arguments\":{\"command\":\"pwd\"}},\"summary\":\"pwd completed\",\"detail\":\"/workspace\"}\r\n\r\n" +
                        "event: assistant.completed\r\n" +
                        "data: {\"turnIndex\":0,\"content\":\"Terminal command finished.\"}\r\n\r\n" +
                        "event: run.completed\r\n" +
                        "data: {\"runId\":\"run-approval\"}\r\n\r\n",
                )
                writer.flush()
                check(allowResponseClose.await(5, TimeUnit.SECONDS)) { "Client did not close after run.completed" }
            }
        }
        server.createContext("/v1/runs/run-approval/tool-results") { exchange ->
            toolResultBody.set(exchange.requestBody.bufferedReader().readText())
            toolResultReceived.countDown()
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()

        try {
            val client = RemoteAgentClient(
                settings = CodeAgentSettings(
                    backendUrl = "http://127.0.0.1:${server.address.port}",
                    nodePath = "node",
                    autoApproveReadOnly = false,
                    backendToken = null,
                ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            )
            val eventTypes = mutableListOf<String>()
            val stream = executor.submit {
                try {
                    client.stream(
                        request = RemoteRunRequest("agent", messages = emptyList(), tools = emptyList(), workspace = RemoteWorkspace()),
                        onStreamChanged = {},
                        onEvent = { type, _ ->
                            eventTypes += type
                            if (type == "tool.request") {
                                approvalShown.countDown()
                                check(approvalGranted.await(5, TimeUnit.SECONDS)) { "Approval was not granted" }
                                client.submitToolResult(
                                    "run-approval",
                                    RemoteToolResult("call-approval", "completed", output = "/workspace"),
                                )
                            }
                        },
                    )
                } finally {
                    streamCompleted.countDown()
                }
            }

            assertTrue(runRequestReceived.await(2, TimeUnit.SECONDS))
            assertTrue(approvalShown.await(2, TimeUnit.SECONDS))
            assertFalse(streamCompleted.await(150, TimeUnit.MILLISECONDS))
            approvalGranted.countDown()
            assertTrue(toolResultReceived.await(2, TimeUnit.SECONDS))
            assertTrue(streamCompleted.await(1, TimeUnit.SECONDS))
            allowResponseClose.countDown()
            stream.get(1, TimeUnit.SECONDS)
            assertEquals(
                listOf("run.started", "tool.request", "tool.completed", "assistant.completed", "run.completed"),
                eventTypes,
            )
            assertTrue(toolResultBody.get().contains("\"toolCallId\":\"call-approval\""))
            assertTrue(toolResultBody.get().contains("\"status\":\"completed\""))
        } finally {
            allowResponseClose.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun `distinguishes an expired run from a terminal execution failure`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/runs/expired-run/tool-results") { exchange ->
            exchange.requestBody.close()
            val body = """{"error":"Run not found"}""".toByteArray()
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

            val error = assertFailsWith<RemoteRunExpiredException> {
                client.submitToolResult(
                    "expired-run",
                    RemoteToolResult("terminal-call", "completed", output = "exit=0\n/workspace"),
                )
            }

            assertEquals("expired-run", error.runId)
            assertTrue(error.message.orEmpty().contains("Run not found"))
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
