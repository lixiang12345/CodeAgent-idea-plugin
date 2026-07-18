package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val SHARED_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

internal class RemoteAgentClient(
    private val settings: CodeAgentSettings,
    private val httpClient: HttpClient = SHARED_HTTP_CLIENT,
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val conversationJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val runsUri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/runs")

    fun health(): CompletableFuture<RemoteBackendHealth> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/health")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            check(response.statusCode() == 200) {
                "Backend health check returned HTTP " + response.statusCode() + ": " + errorMessage(response.body())
            }
            json.decodeFromString<RemoteBackendHealth>(response.body())
        }
    }

    fun account(): CompletableFuture<RemoteAccountResponse> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/me")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            check(response.statusCode() == 200) {
                "Backend account request returned HTTP " + response.statusCode() + ": " + errorMessage(response.body())
            }
            json.decodeFromString<RemoteAccountResponse>(response.body())
        }
    }

    fun conversations(): CompletableFuture<RemoteConversationList> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/conversations")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 200) throw remoteHttpError("Conversation discovery", response)
            json.decodeFromString<RemoteConversationList>(response.body())
        }
    }

    fun conversation(id: String): CompletableFuture<RemoteConversation> {
        val uri = conversationUri(id)
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 200) throw remoteHttpError("Conversation read", response)
            json.decodeFromString<RemoteConversation>(response.body())
        }
    }

    fun putConversation(conversation: RemoteConversation, expectedVersion: Int?): CompletableFuture<RemoteConversation> {
        val request = requestBuilder(conversationUri(conversation.id))
            .timeout(Duration.ofSeconds(30))
            .apply { expectedVersion?.let { header("If-Match", it.toString()) } }
            .PUT(HttpRequest.BodyPublishers.ofString(conversationJson.encodeToString(conversation)))
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            if (response.statusCode() != 200) throw remoteHttpError("Conversation write", response)
            json.decodeFromString<RemoteConversation>(response.body())
        }
    }

    fun deleteConversation(id: String): CompletableFuture<Boolean> {
        return httpClient.sendAsync(
            requestBuilder(conversationUri(id)).timeout(Duration.ofSeconds(30)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            when (response.statusCode()) {
                204 -> true
                404 -> false
                else -> throw remoteHttpError("Conversation delete", response)
            }
        }
    }

    fun createJob(request: RemoteJobRequest): CompletableFuture<RemoteJob> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/jobs")
        return httpClient.sendAsync(
            requestBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 202) throw remoteHttpError("Job creation", response)
            json.decodeFromString<RemoteJob>(response.body())
        }
    }

    fun jobs(limit: Int = 50): CompletableFuture<RemoteJobList> {
        require(limit in 1..100) { "Job limit must be between 1 and 100" }
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/jobs?limit=$limit")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 200) throw remoteHttpError("Job discovery", response)
            json.decodeFromString<RemoteJobList>(response.body())
        }
    }

    fun job(id: String): CompletableFuture<RemoteJob> {
        return httpClient.sendAsync(
            requestBuilder(jobUri(id)).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 200) throw remoteHttpError("Job read", response)
            json.decodeFromString<RemoteJob>(response.body())
        }
    }

    fun cancelJob(id: String): CompletableFuture<RemoteJob> {
        return httpClient.sendAsync(
            requestBuilder(jobUri(id)).timeout(Duration.ofSeconds(30)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 202) throw remoteHttpError("Job cancellation", response)
            json.decodeFromString<RemoteJob>(response.body())
        }
    }

    fun models(): CompletableFuture<RemoteModelsResponse> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/models")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            check(response.statusCode() == 200) {
                "Backend model discovery returned HTTP " + response.statusCode() + ": " + errorMessage(response.body())
            }
            json.decodeFromString<RemoteModelsResponse>(response.body())
        }
    }

    fun tools(): CompletableFuture<RemoteToolsResponse> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/tools")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() == 404) {
                return@thenApply RemoteToolsResponse()
            }
            check(response.statusCode() == 200) {
                "Backend tool discovery returned HTTP " + response.statusCode() + ": " + errorMessage(response.body())
            }
            json.decodeFromString<RemoteToolsResponse>(response.body())
        }
    }

    fun configurations(kind: String): CompletableFuture<RemoteConfigurationList> {
        val uri = configurationUri(kind)
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 200) throw remoteHttpError("Configuration discovery", response)
            json.decodeFromString<RemoteConfigurationList>(response.body())
        }
    }

    fun putConfiguration(kind: String, id: String, value: JsonObject): CompletableFuture<RemoteConfiguration> {
        val uri = configurationUri(kind, id)
        return httpClient.sendAsync(
            requestBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(json.encodeToString(value)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            if (response.statusCode() != 200) throw remoteHttpError("Configuration write", response)
            json.decodeFromString<RemoteConfiguration>(response.body())
        }
    }

    fun deleteConfiguration(kind: String, id: String): CompletableFuture<Boolean> {
        return httpClient.sendAsync(
            requestBuilder(configurationUri(kind, id)).timeout(Duration.ofSeconds(30)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            when (response.statusCode()) {
                204 -> true
                404 -> false
                else -> throw remoteHttpError("Configuration delete", response)
            }
        }
    }

    fun executeTool(name: String, arguments: JsonObject): CompletableFuture<RemoteToolExecutionResponse> {
        require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid backend tool name: $name" }
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/tools/$name")
        val body = json.encodeToString(RemoteToolExecutionRequest(arguments))
        return httpClient.sendAsync(
            requestBuilder(uri)
                .timeout(Duration.ofSeconds(130))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            check(response.statusCode() == 200) {
                "Backend tool '$name' returned HTTP " + response.statusCode() + ": " + errorMessage(response.body())
            }
            json.decodeFromString<RemoteToolExecutionResponse>(response.body())
        }
    }

    fun enhance(
        text: String,
        mode: String,
        model: String?,
        agentProfileId: String,
        repositoryContext: String,
        conversationContext: String,
    ): CompletableFuture<RemoteEnhanceResponse> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/enhance")
        val body = json.encodeToString(
            RemoteEnhanceRequest(
                text = text,
                mode = mode,
                model = model,
                agentProfileId = agentProfileId,
                repositoryContext = repositoryContext,
                conversationContext = conversationContext,
            ),
        )
        return httpClient.sendAsync(
            requestBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            check(response.statusCode() == 200) {
                "Backend enhance returned HTTP " + response.statusCode() + ": " + errorMessage(response.body())
            }
            json.decodeFromString<RemoteEnhanceResponse>(response.body())
        }
    }

    fun stream(
        request: RemoteRunRequest,
        onStreamChanged: (Closeable?) -> Unit,
        onEvent: (String, JsonElement) -> Unit,
    ) {
        val responseFuture = httpClient.sendAsync(
            requestBuilder(runsUri)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request)))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        val response = try {
            responseFuture.orTimeout(STREAM_HEADER_TIMEOUT_SECONDS, TimeUnit.SECONDS).join()
        } catch (error: Throwable) {
            responseFuture.cancel(true)
            throw error
        }
        if (response.statusCode() != 200) {
            response.body().use { body ->
                val message = errorMessage(body.bufferedReader().readText())
                error("Backend run request failed with HTTP " + response.statusCode() + ": " + message)
            }
        }

        response.body().use { body ->
            onStreamChanged(body)
            try {
                readEvents(body.bufferedReader(), onEvent)
            } finally {
                onStreamChanged(null)
            }
        }
    }

    fun submitToolResult(runId: String, result: RemoteToolResult) {
        val uri = URI.create("${runsUri}/$runId/tool-results")
        val response = httpClient.send(
            requestBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(result)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        when (response.statusCode()) {
            202 -> return
            404, 410 -> throw RemoteRunExpiredException(
                runId,
                "Backend run no longer accepts tool results: ${errorMessage(response.body())}",
            )
            else -> throw RemoteHttpException(
                response.statusCode(),
                "Backend rejected tool result with HTTP ${response.statusCode()}: ${errorMessage(response.body())}",
            )
        }
    }

    fun cancel(runId: String): CompletableFuture<Void> {
        val uri = URI.create("${runsUri}/$runId")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(10)).DELETE().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).thenApply { response ->
            check(response.statusCode() == 202) {
                "Backend cancel failed with HTTP " + response.statusCode() + ": " + errorMessage(response.body())
            }
            null
        }
    }

    private fun conversationUri(id: String): URI {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,200}"))) { "Invalid conversation ID" }
        return URI.create("${settings.backendUrl.trimEnd('/')}/v1/conversations/$id")
    }

    private fun jobUri(id: String): URI {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,200}"))) { "Invalid job ID" }
        return URI.create("${settings.backendUrl.trimEnd('/')}/v1/jobs/$id")
    }

    private fun configurationUri(kind: String, id: String? = null): URI {
        require(kind in CONFIGURATION_KINDS) { "Invalid configuration kind" }
        if (id != null) {
            require(id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid configuration ID" }
        }
        val suffix = id?.let { "/$it" }.orEmpty()
        return URI.create("${settings.backendUrl.trimEnd('/')}/v1/configurations/$kind$suffix")
    }

    private fun remoteHttpError(operation: String, response: HttpResponse<String>): RemoteHttpException =
        RemoteHttpException(response.statusCode(), "$operation returned HTTP ${response.statusCode()}: ${errorMessage(response.body())}")

    private fun requestBuilder(uri: URI): HttpRequest.Builder = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json")
        .apply {
            settings.backendToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
        }

    private fun errorMessage(body: String): String = runCatching {
        json.decodeFromString<RemoteHttpError>(body).let { it.error ?: it.message }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: body.take(1_000).ifBlank { "Empty response body" }

    private fun readEvents(
        reader: java.io.BufferedReader,
        onEvent: (String, JsonElement) -> Unit,
    ) {
        var type: String? = null
        val data = StringBuilder()

        fun dispatch(): Boolean {
            val eventType = type
            if (eventType != null && data.isNotEmpty()) {
                onEvent(eventType, json.parseToJsonElement(data.toString()))
            }
            type = null
            data.setLength(0)
            return eventType in TERMINAL_RUN_EVENTS
        }

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() && dispatch() -> return
                line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        if (dispatch()) return
        throw RemoteStreamDisconnectedException("Backend run stream disconnected before completion")
    }

    companion object {
        private val CONFIGURATION_KINDS = setOf("mcp", "hooks", "commands", "agents", "plugins", "tool-permissions")
        private val TERMINAL_RUN_EVENTS = setOf("run.completed", "run.error", "run.cancelled")
        private const val STREAM_HEADER_TIMEOUT_SECONDS = 45L
    }
}

@Serializable
internal data class RemoteEnhanceRequest(
    val text: String,
    val mode: String? = null,
    val model: String? = null,
    val agentProfileId: String = "general",
    val repositoryContext: String? = null,
    val conversationContext: String? = null,
)

@Serializable
internal data class RemoteEnhanceResponse(
    val text: String,
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
internal data class RemoteRunRequest(
    val mode: String,
    val agentProfileId: String = "general",
    val model: String? = null,
    val messages: List<RemoteMessage>,
    val tools: List<RemoteToolDefinition>,
    val workspace: RemoteWorkspace,
)

@Serializable
internal data class RemoteMessage(
    val role: String,
    val content: String? = null,
    val attachments: List<RemoteAttachment> = emptyList(),
)

@Serializable
internal data class RemoteAttachment(
    val type: String,
    val id: String,
    val label: String,
    val path: String? = null,
    val mimeType: String? = null,
    val data: String? = null,
    val textExcerpt: String? = null,
    val sizeBytes: Long = 0,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
internal data class RemoteToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val risk: String,
)

@Serializable
internal data class RemoteWorkspace(
    val guidance: String? = null,
    val historySummary: String? = null,
    val rules: List<RemoteWorkspaceEntry> = emptyList(),
    val skills: List<RemoteWorkspaceEntry> = emptyList(),
)

@Serializable
internal data class RemoteWorkspaceEntry(
    val name: String,
    val path: String,
    val content: String,
)

@Serializable
internal data class RemoteToolResult(
    val toolCallId: String,
    val status: String,
    val output: String = "",
    val error: String = "",
    val summary: String? = null,
)

@Serializable
internal data class RemoteRunStarted(
    val runId: String,
    val contextWindowTokens: Int = 256_000,
    val retrievalBudgetTokens: Int = 8_192,
)

@Serializable
data class RemoteContextUpdated(
    val turnIndex: Int,
    val estimatedInputTokens: Int,
    val targetInputTokens: Int,
    val contextWindowTokens: Int,
    val reservedOutputTokens: Int,
    val retrievalBudgetTokens: Int = 8_192,
    val toolDefinitionTokens: Int,
    val compactedToolResults: Int,
    val truncatedMessages: Int,
    val compactionApplied: Boolean = false,
    val overBudget: Boolean,
)

@Serializable
data class RemoteToolCatalogUpdated(
    val turnIndex: Int,
    val activeToolNames: List<String>,
    val activeToolCount: Int,
    val catalogToolCount: Int,
    val discoverableToolCount: Int,
    val activated: List<String> = emptyList(),
)

@Serializable
data class RemoteVerificationUpdated(
    val turnIndex: Int,
    val status: String,
    val message: String,
    val toolName: String? = null,
)

@Serializable
internal data class RemoteMessageDelta(val delta: String, val turnIndex: Int)

@Serializable
internal data class RemoteAssistantCompleted(val content: String? = null, val turnIndex: Int)

@Serializable
internal data class RemoteToolRequest(val call: RemoteToolCall, val turnIndex: Int)

@Serializable
internal data class RemoteToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
internal data class RemoteRunError(val message: String)

@Serializable
internal data class RemoteHttpError(
    val error: String? = null,
    val message: String? = null,
)

internal class RemoteHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal class RemoteRunExpiredException(
    val runId: String,
    message: String,
 ) : IllegalStateException(message)

internal class RemoteStreamDisconnectedException(message: String) : IllegalStateException(message)


@Serializable
internal data class RemoteBackendHealth(
    val ok: Boolean,
    val service: String,
    val protocolVersion: Int,
    val provider: String? = null,
    val defaultModel: String? = null,
)

@Serializable
internal data class RemoteAccountResponse(
    val user: RemoteAccountUser,
    val usage: List<RemoteUsageSummary> = emptyList(),
    val session: RemoteSession,
)

@Serializable
internal data class RemoteAccountUser(
    val id: String,
    val email: String? = null,
    val displayName: String,
)

@Serializable
internal data class RemoteUsageSummary(
    val kind: String,
    val units: Long,
)

@Serializable
internal data class RemoteSession(
    val mode: String = "unknown",
    val id: String? = null,
    val refreshExpiresAt: String? = null,
)

@Serializable
internal data class RemoteConversationList(
    val data: List<RemoteConversationSummary> = emptyList(),
)

@Serializable
internal data class RemoteConversationSummary(
    val id: String,
    val title: String,
    val mode: String,
    val version: Int,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val taskCount: Int = 0,
    val toolCount: Int = 0,
    val pinned: Boolean = false,
    val summary: String? = null,
)

@Serializable
internal data class RemoteConversation(
    val id: String,
    val title: String,
    val mode: String,
    val updatedAt: Long,
    val selectedAgentProfileId: String = "general",
    val selectedModelId: String? = null,
    val selectedSkillIds: List<String> = emptyList(),
    val selectedRuleIds: List<String> = emptyList(),
    val pinned: Boolean = false,
    val summary: String? = null,
    val messages: List<RemoteConversationMessage> = emptyList(),
    val tasks: List<RemoteConversationTask> = emptyList(),
    val tools: List<RemoteConversationTool> = emptyList(),
    val version: Int = 0,
)

@Serializable
internal data class RemoteConversationMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val runId: String? = null,
    val turnIndex: Int? = null,
    val timelineSequence: Long = 0,
)

@Serializable
internal data class RemoteConversationTool(
    val id: String,
    val name: String,
    val summary: String,
    val status: String,
    val detail: String? = null,
    val changePath: String? = null,
    val canRevert: Boolean = false,
    val runId: String? = null,
    val turnIndex: Int? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val timelineSequence: Long = 0,
)

@Serializable
internal data class RemoteConversationTask(
    val id: String,
    val name: String,
    val state: String,
)

@Serializable
internal data class RemoteJobRequest(
    val type: String,
    val input: RemoteJobInput,
)

@Serializable
internal data class RemoteJobInput(
    val prompt: String,
    val system: String? = null,
    val model: String? = null,
    val role: String? = null,
    val context: String? = null,
    val expectedOutput: String? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
internal data class RemoteJob(
    val id: String,
    val type: String,
    val status: String,
    val input: RemoteJobInput = RemoteJobInput(prompt = ""),
    val output: RemoteJobOutput? = null,
    val error: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class RemoteJobOutput(
    val content: String,
    val model: String? = null,
    val role: String? = null,
    val partial: Boolean = false,
)

@Serializable
internal data class RemoteJobList(
    val data: List<RemoteJob> = emptyList(),
)

@Serializable
internal data class RemoteModelsResponse(
    val provider: String = "unknown",
    val defaultModel: String? = null,
    val data: List<RemoteModel> = emptyList(),
)

@Serializable
internal data class RemoteToolsResponse(
    val data: List<RemoteToolCapability> = emptyList(),
)

@Serializable
internal data class RemoteConfigurationList(
    val data: List<RemoteConfiguration> = emptyList(),
)

@Serializable
internal data class RemoteConfiguration(
    val id: String,
    val kind: String,
    val value: JsonObject,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class RemoteToolCapability(
    val name: String,
    val catalogId: String,
    val description: String,
    val parameters: JsonObject,
    val risk: String = "read_only",
    val available: Boolean,
    val unavailableReason: String? = null,
    val requiredEnvironment: List<String> = emptyList(),
)

@Serializable
internal data class RemoteToolExecutionRequest(
    val arguments: JsonObject,
)

@Serializable
internal data class RemoteToolExecutionResponse(
    val output: String,
    val summary: String,
    val detail: String? = null,
)

@Serializable
internal data class RemoteModel(
    val id: String,
    val ownedBy: String? = null,
)
