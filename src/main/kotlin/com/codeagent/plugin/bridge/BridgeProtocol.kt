package com.codeagent.plugin.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val BRIDGE_PROTOCOL_VERSION = 1

@Serializable
data class CommandEnvelope(
    val version: Int,
    val id: String,
    val type: String,
    val payload: JsonElement? = null,
)

@Serializable
data class EventEnvelope(
    val version: Int = BRIDGE_PROTOCOL_VERSION,
    val type: String,
    val payload: JsonElement? = null,
)

@Serializable
data class ChatMessageDto(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Serializable
data class MessageDeltaDto(
    val id: String,
    val delta: String,
)

@Serializable
data class ToolRunDto(
    val id: String,
    val name: String,
    val summary: String,
    val status: String,
    val detail: String? = null,
    val changePath: String? = null,
    val canRevert: Boolean = false,
)

@Serializable
data class ThreadSummaryDto(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val active: Boolean,
    val mode: String = "agent",
)

@Serializable
data class TaskDto(
    val id: String,
    val name: String,
    val state: String,
)

@Serializable
data class ContextItemDto(
    val id: String,
    val label: String,
    val path: String,
)

@Serializable
data class GitFileDto(
    val path: String,
    val status: String,
)

@Serializable
data class GitSnapshotDto(
    val available: Boolean = false,
    val branch: String = "",
    val repository: String = "",
    val unstaged: List<GitFileDto> = emptyList(),
    val staged: List<GitFileDto> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ImageItemDto(
    val id: String,
    val name: String,
    val path: String,
    val dataUrl: String,
    val sizeBytes: Long,
)

@Serializable
data class ImageCanvasSnapshotDto(
    val directory: String = "",
    val images: List<ImageItemDto> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class SettingsSnapshotDto(
    val backendUrl: String = "http://127.0.0.1:8787",
    val nodePath: String = "node",
    val backendTokenConfigured: Boolean = false,
    val autoApproveReadOnly: Boolean = true,
)

@Serializable
data class ContextSnapshotDto(
    val state: String = "unavailable",
    val label: String = "Context unavailable",
    val files: Int? = null,
    val chunks: Int? = null,
)

@Serializable
data class WorkspaceRuleDto(
    val id: String,
    val name: String,
    val path: String,
    val content: String,
    val trigger: String,
    val selected: Boolean,
)

@Serializable
data class WorkspaceSkillDto(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val selected: Boolean,
)

@Serializable
data class WorkspaceCustomizationDto(
    val rules: List<WorkspaceRuleDto> = emptyList(),
    val skills: List<WorkspaceSkillDto> = emptyList(),
    val maxSelectedSkills: Int = 8,
)

@Serializable
data class AppSnapshotDto(
    val projectName: String,
    val mode: String = "agent",
    val runState: String = "idle",
    val messages: List<ChatMessageDto> = emptyList(),
    val tools: List<ToolRunDto> = emptyList(),
    val threads: List<ThreadSummaryDto>,
    val tasks: List<TaskDto> = emptyList(),
    val attachments: List<ContextItemDto> = emptyList(),
    val settings: SettingsSnapshotDto = SettingsSnapshotDto(),
    val context: ContextSnapshotDto = ContextSnapshotDto(),
    val customization: WorkspaceCustomizationDto = WorkspaceCustomizationDto(),
)
