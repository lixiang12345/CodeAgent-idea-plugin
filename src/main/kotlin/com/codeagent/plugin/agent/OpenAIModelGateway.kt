package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

class OpenAIModelGateway(
    private val settings: CodeAgentSettings,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : ModelGateway {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    override fun complete(
        messages: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
    ): CompletableFuture<ModelTurn> {
        val payload = ChatCompletionRequest(
            model = settings.model,
            messages = messages.map { message ->
                ApiMessage(
                    role = message.role,
                    content = message.content,
                    toolCalls = message.toolCalls?.map { call ->
                        ApiToolCall(
                            id = call.id,
                            function = ApiFunctionCall(call.name, call.arguments),
                        )
                    },
                    toolCallId = message.toolCallId,
                )
            },
            tools = tools.takeIf { it.isNotEmpty() }?.map { tool ->
                ApiToolDefinition(
                    function = ApiFunctionDefinition(tool.name, tool.description, tool.parameters),
                )
            },
        )

        val requestBuilder = HttpRequest.newBuilder(resolveChatCompletionsUri(settings.endpoint))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
        settings.apiKey?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }

        val upstream = httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        val result = upstream.thenApply { response ->
            if (response.statusCode() !in 200..299) {
                val apiMessage = runCatching {
                    json.decodeFromString<ApiErrorEnvelope>(response.body()).error?.message
                }.getOrNull()
                error(apiMessage ?: "Model request failed with HTTP ${response.statusCode()}")
            }
            val decoded = json.decodeFromString<ChatCompletionResponse>(response.body())
            val message = decoded.choices.firstOrNull()?.message ?: error("Model returned no choices")
            ModelTurn(
                content = message.content,
                toolCalls = message.toolCalls.orEmpty().map { call ->
                    AgentToolCall(call.id, call.function.name, call.function.arguments)
                },
            )
        }
        result.whenComplete { _, _ ->
            if (result.isCancelled) upstream.cancel(true)
        }
        return result
    }

    internal fun resolveChatCompletionsUri(endpoint: String): URI {
        val normalized = endpoint.trimEnd('/')
        return URI.create(
            if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions",
        )
    }
}
