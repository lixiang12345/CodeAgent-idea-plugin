package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

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
    ): CompletableFuture<ModelTurn> = stream(messages, tools) { }

    override fun stream(
        messages: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        onTextDelta: (String) -> Unit,
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
            stream = true,
        )

        val requestBuilder = HttpRequest.newBuilder(resolveChatCompletionsUri(settings.endpoint))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(payload)))
        settings.apiKey?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }

        val activeBody = AtomicReference<java.io.InputStream?>()
        val upstream = httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val result = upstream.thenApplyAsync { response ->
            val body = response.body()
            activeBody.set(body)
            try {
                decodeResponse(response, body, onTextDelta)
            } finally {
                activeBody.compareAndSet(body, null)
                body.close()
            }
        }
        result.whenComplete { _, _ ->
            if (result.isCancelled) {
                activeBody.getAndSet(null)?.close()
                upstream.cancel(true)
            }
        }
        return result
    }

    private fun decodeResponse(
        response: HttpResponse<java.io.InputStream>,
        body: java.io.InputStream,
        onTextDelta: (String) -> Unit,
    ): ModelTurn {
        if (response.statusCode() !in 200..299) {
            val responseBody = body.readAllBytes().toString(StandardCharsets.UTF_8)
            val apiMessage = runCatching {
                json.decodeFromString<ApiErrorEnvelope>(responseBody).error?.message
            }.getOrNull()
            error(apiMessage ?: "Model request failed with HTTP ${response.statusCode()}")
        }

        val contentType = response.headers().firstValue("Content-Type").orElse("").lowercase()
        if ("text/event-stream" !in contentType) {
            return decodeCompleteResponse(body.readAllBytes().toString(StandardCharsets.UTF_8))
        }

        val accumulator = StreamAccumulator(onTextDelta)
        body.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val eventData = mutableListOf<String>()
            while (true) {
                val line = reader.readLine()
                if (line == null) {
                    consumeEvent(eventData, accumulator)
                    break
                }
                if (line.isEmpty()) {
                    consumeEvent(eventData, accumulator)
                } else if (line.startsWith("data:")) {
                    eventData += line.removePrefix("data:").trimStart()
                }
            }
        }
        return accumulator.toModelTurn()
    }

    private fun consumeEvent(eventData: MutableList<String>, accumulator: StreamAccumulator) {
        if (eventData.isEmpty()) return
        val payload = eventData.joinToString("\n")
        eventData.clear()
        if (payload == "[DONE]") return
        accumulator.accept(json.decodeFromString<ChatCompletionChunk>(payload))
    }

    private fun decodeCompleteResponse(responseBody: String): ModelTurn {
        val decoded = json.decodeFromString<ChatCompletionResponse>(responseBody)
        val message = decoded.choices.firstOrNull()?.message ?: error("Model returned no choices")
        return ModelTurn(
            content = message.content,
            toolCalls = message.toolCalls.orEmpty().map { call ->
                AgentToolCall(call.id, call.function.name, call.function.arguments)
            },
        )
    }

    private class StreamAccumulator(
        private val onTextDelta: (String) -> Unit,
    ) {
        private val content = StringBuilder()
        private val toolCalls = sortedMapOf<Int, MutableToolCall>()

        fun accept(chunk: ChatCompletionChunk) {
            chunk.choices.forEach { choice ->
                choice.delta.content?.takeIf { it.isNotEmpty() }?.let { delta ->
                    content.append(delta)
                    onTextDelta(delta)
                }
                choice.delta.toolCalls.orEmpty().forEach { delta ->
                    val call = toolCalls.getOrPut(delta.index) { MutableToolCall() }
                    delta.id?.let(call.id::append)
                    delta.function?.name?.let(call.name::append)
                    delta.function?.arguments?.let(call.arguments::append)
                }
            }
        }

        fun toModelTurn(): ModelTurn {
            val calls = toolCalls.map { (index, call) ->
                check(call.name.isNotEmpty()) { "Model streamed a tool call without a function name" }
                AgentToolCall(
                    id = call.id.toString().ifEmpty { "call-$index" },
                    name = call.name.toString(),
                    arguments = call.arguments.toString(),
                )
            }
            return ModelTurn(content.toString().takeIf { it.isNotEmpty() }, calls)
        }

        private data class MutableToolCall(
            val id: StringBuilder = StringBuilder(),
            val name: StringBuilder = StringBuilder(),
            val arguments: StringBuilder = StringBuilder(),
        )
    }

    internal fun resolveChatCompletionsUri(endpoint: String): URI {
        val normalized = endpoint.trimEnd('/')
        return URI.create(
            if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions",
        )
    }
}
