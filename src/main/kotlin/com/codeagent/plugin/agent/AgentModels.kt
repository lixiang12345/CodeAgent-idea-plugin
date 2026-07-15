package com.codeagent.plugin.agent

import kotlinx.serialization.json.JsonObject

data class AgentMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<AgentToolCall>? = null,
    val toolCallId: String? = null,
    val attachments: List<AgentAttachment> = emptyList(),
)

data class AgentAttachment(
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

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val risk: ToolRisk = ToolRisk.READ_ONLY,
)

data class FileChange(
    val path: String,
    val before: String?,
    val after: String,
)
