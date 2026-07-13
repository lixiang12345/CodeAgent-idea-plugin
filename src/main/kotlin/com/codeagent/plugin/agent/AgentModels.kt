package com.codeagent.plugin.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<AgentToolCall>? = null,
    val toolCallId: String? = null,
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class ModelTurn(
    val content: String?,
    val toolCalls: List<AgentToolCall>,
)

interface ModelGateway {
    fun complete(messages: List<AgentMessage>, tools: List<AgentToolDefinition>): java.util.concurrent.CompletableFuture<ModelTurn>
}

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val tools: List<ApiToolDefinition>? = null,
    val stream: Boolean = false,
)

@Serializable
internal data class ApiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class ApiToolDefinition(
    val type: String = "function",
    val function: ApiFunctionDefinition,
)

@Serializable
internal data class ApiFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class ApiToolCall(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCall,
)

@Serializable
internal data class ApiFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class ChatCompletionResponse(
    val choices: List<ApiChoice> = emptyList(),
)

@Serializable
internal data class ApiChoice(
    val message: ApiMessage,
)

@Serializable
internal data class ApiErrorEnvelope(
    val error: ApiError? = null,
)

@Serializable
internal data class ApiError(
    val message: String? = null,
)
