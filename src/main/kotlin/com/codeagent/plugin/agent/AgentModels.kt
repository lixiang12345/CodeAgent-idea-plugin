package com.codeagent.plugin.agent

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

data class FileChange(
    val path: String,
    val before: String?,
    val after: String,
)
