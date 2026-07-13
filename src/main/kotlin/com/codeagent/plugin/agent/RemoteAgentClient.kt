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

internal class RemoteAgentClient(
    private val settings: CodeAgentSettings,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
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

    fun enhance(text: String, mode: String, model: String?): CompletableFuture<RemoteEnhanceResponse> {
        val uri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/enhance")
        val body = json.encodeToString(
            RemoteEnhanceRequest(
                text = text,
                mode = mode,
                model = model,
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
        val response = httpClient.send(
            requestBuilder(runsUri)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(request)))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
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
        check(response.statusCode() == 202) {
            "Backend rejected tool result with HTTP " + response.statusCode() + ": " + errorMessage(response.body())
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

    private fun requestBuilder(uri: URI): HttpRequest.Builder = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json")
        .apply {
            settings.backendToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
        }

    private fun errorMessage(body: String): String = runCatching {
        json.decodeFromString<RemoteHttpError>(body).error
    }.getOrNull()?.takeIf(String::isNotBlank) ?: body.take(1_000).ifBlank { "Empty response body" }

    private fun readEvents(
        reader: java.io.BufferedReader,
        onEvent: (String, JsonElement) -> Unit,
    ) {
        var type: String? = null
        val data = StringBuilder()

        fun dispatch() {
            val eventType = type
            if (eventType != null && data.isNotEmpty()) {
                onEvent(eventType, json.parseToJsonElement(data.toString()))
            }
            type = null
            data.setLength(0)
        }

        reader.forEachLine { line ->
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("event:") -> type = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        dispatch()
    }
}

@Serializable
internal data class RemoteEnhanceRequest(
    val text: String,
    val mode: String? = null,
    val model: String? = null,
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
    val model: String? = null,
    val messages: List<RemoteMessage>,
    val tools: List<RemoteToolDefinition>,
    val workspace: RemoteWorkspace,
)

@Serializable
internal data class RemoteMessage(
    val role: String,
    val content: String? = null,
)

@Serializable
internal data class RemoteToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class RemoteWorkspace(
    val guidance: String? = null,
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
internal data class RemoteRunStarted(val runId: String)

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
internal data class RemoteHttpError(val error: String)

@Serializable
internal data class RemoteBackendHealth(
    val ok: Boolean,
    val service: String,
    val protocolVersion: Int,
    val provider: String? = null,
    val defaultModel: String? = null,
)

@Serializable
internal data class RemoteModelsResponse(
    val provider: String = "unknown",
    val defaultModel: String? = null,
    val data: List<RemoteModel> = emptyList(),
)

@Serializable
internal data class RemoteModel(
    val id: String,
    val ownedBy: String? = null,
)
