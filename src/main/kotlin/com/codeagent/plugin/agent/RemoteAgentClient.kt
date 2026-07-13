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
            check(response.statusCode() == 200) { "Backend health check returned HTTP ${response.statusCode()}" }
            json.decodeFromString<RemoteBackendHealth>(response.body())
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
                error("Backend run request failed with HTTP ${response.statusCode()}: ${body.bufferedReader().readText().take(1_000)}")
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
            "Backend rejected tool result with HTTP ${response.statusCode()}: ${response.body().take(1_000)}"
        }
    }

    fun cancel(runId: String): CompletableFuture<Void> {
        val uri = URI.create("${runsUri}/$runId")
        return httpClient.sendAsync(
            requestBuilder(uri).timeout(Duration.ofSeconds(10)).DELETE().build(),
            HttpResponse.BodyHandlers.discarding(),
        ).thenApply { null }
    }

    private fun requestBuilder(uri: URI): HttpRequest.Builder = HttpRequest.newBuilder(uri)
        .header("Content-Type", "application/json")
        .apply {
            settings.backendToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
        }

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
internal data class RemoteRunRequest(
    val mode: String,
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
internal data class RemoteMessageDelta(val delta: String)

@Serializable
internal data class RemoteAssistantCompleted(val content: String? = null)

@Serializable
internal data class RemoteToolRequest(val call: RemoteToolCall)

@Serializable
internal data class RemoteToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
internal data class RemoteRunError(val message: String)

@Serializable
internal data class RemoteBackendHealth(
    val ok: Boolean,
    val service: String,
    val protocolVersion: Int,
)
