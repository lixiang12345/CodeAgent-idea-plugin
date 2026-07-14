package com.codeagent.plugin.bridge

import com.codeagent.plugin.settings.DEFAULT_BACKEND_URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    val turnIndex: Int? = null,
    val runId: String? = null,
)

@Serializable
data class MessageDeltaDto(
    val id: String,
    val delta: String,
    val turnIndex: Int,
)

@Serializable
data class AgentRunTelemetryDto(
    val turnIndex: Int = 0,
    val estimatedInputTokens: Int = 0,
    val targetInputTokens: Int = 0,
    val contextWindowTokens: Int = 0,
    val reservedOutputTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val compactedToolResults: Int = 0,
    val truncatedMessages: Int = 0,
    val overBudget: Boolean = false,
    val activeToolNames: List<String> = emptyList(),
    val activeToolCount: Int = 0,
    val catalogToolCount: Int = 0,
    val discoverableToolCount: Int = 0,
    val activatedToolNames: List<String> = emptyList(),
    val verificationState: String = "idle",
    val verificationMessage: String? = null,
    val verificationToolName: String? = null,
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
    val turnIndex: Int? = null,
    val runId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = createdAt,
)

@Serializable
data class ThreadSummaryDto(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val active: Boolean,
    val mode: String = "agent",
    val pinned: Boolean = false,
)

@Serializable
data class TaskDto(
    val id: String,
    val name: String,
    val state: String,
)

@Serializable
data class QueuedMessageDto(
    val id: String,
    val text: String,
    val mode: String,
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
data class CheckpointSummaryDto(
    val id: String,
    val label: String,
    val createdAt: Long,
    val changeCount: Int,
    val paths: List<String> = emptyList(),
)

@Serializable
data class SettingsSnapshotDto(
    val backendUrl: String = DEFAULT_BACKEND_URL,
    val nodePath: String = "node",
    val backendTokenConfigured: Boolean = false,
    val autoApproveReadOnly: Boolean = true,
    val chatZoom: Int = 100,
    val showTimestamps: Boolean = true,
    val showRunTelemetry: Boolean = true,
    val desktopNotifications: Boolean = false,
    val autoDismissNotifications: Boolean = true,
    val contextMode: String = "lexical",
    val contextEmbeddingBaseUrl: String = "http://127.0.0.1:8000/v1",
    val contextEmbeddingModel: String = "Qwen/Qwen3-Embedding-0.6B",
    val contextEmbeddingTokenConfigured: Boolean = false,
    val contextNeuralRerank: Boolean = false,
    val contextRerankBaseUrl: String = "",
    val contextRerankModel: String = "Qwen/Qwen3-Reranker-0.6B",
)

@Serializable
data class AccountUsageDto(
    val kind: String,
    val units: Long,
)

@Serializable
data class AccountSnapshotDto(
    val state: String = "checking",
    val mode: String = "unknown",
    val userId: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val usage: List<AccountUsageDto> = emptyList(),
    val label: String = "Checking account",
)

@Serializable
data class ContextSnapshotDto(
    val state: String = "unavailable",
    val label: String = "Context unavailable",
    val files: Int? = null,
    val chunks: Int? = null,
    val watching: Boolean = false,
    val hasEmbeddings: Boolean = false,
    val lastIndexedAt: String? = null,
)

@Serializable
data class BackendHealthDto(
    val state: String = "unknown",
    val label: String = "Not checked",
    val protocolVersion: Int? = null,
    val provider: String? = null,
    val defaultModel: String? = null,
)

@Serializable
data class ModelOptionDto(
    val id: String,
    val ownedBy: String? = null,
)

@Serializable
data class ModelRegistryDto(
    val state: String = "unknown",
    val provider: String = "unknown",
    val defaultModel: String? = null,
    val selectedModel: String? = null,
    val options: List<ModelOptionDto> = emptyList(),
    val label: String = "Models not loaded",
)

@Serializable
data class BackendToolDto(
    val name: String,
    val catalogId: String,
    val available: Boolean,
    val unavailableReason: String? = null,
    val requiredEnvironment: List<String> = emptyList(),
)

@Serializable
data class ProductConfigurationDto(
    val id: String,
    val kind: String,
    val value: JsonObject,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ConfigurationSnapshotDto(
    val state: String = "unavailable",
    val label: String = "Configurations unavailable",
    val items: Map<String, List<ProductConfigurationDto>> = emptyMap(),
)

@Serializable
data class HookExecutionDto(
    val id: String,
    val hookId: String,
    val hookName: String,
    val event: String,
    val status: String,
    val exitCode: Int? = null,
    val startedAt: String,
    val durationMs: Long,
    val summary: String,
    val detail: String? = null,
)

@Serializable
data class HookRuntimeSnapshotDto(
    val state: String = "idle",
    val label: String = "No hooks configured",
    val configured: Int = 0,
    val automatic: Int = 0,
    val recent: List<HookExecutionDto> = emptyList(),
)

@Serializable
data class PluginCommandDto(
    val id: String,
    val pluginId: String,
    val pluginVersion: String,
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
    val mode: String = "inherit",
    val agentProfileId: String? = null,
)

@Serializable
data class PluginRuntimeItemDto(
    val id: String,
    val name: String,
    val description: String? = null,
    val source: String,
    val state: String,
    val label: String,
    val configuredVersion: String? = null,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val integrity: String? = null,
    val grantedCapabilities: List<String> = emptyList(),
    val declaredCapabilities: List<String> = emptyList(),
    val commandCount: Int = 0,
    val installedAt: String? = null,
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class PluginRuntimeSnapshotDto(
    val state: String = "idle",
    val label: String = "No plugins configured",
    val items: List<PluginRuntimeItemDto> = emptyList(),
    val commands: List<PluginCommandDto> = emptyList(),
)

@Serializable
data class McpToolDto(
    val id: String,
    val serverId: String,
    val name: String,
    val title: String? = null,
    val description: String,
    val parameters: JsonObject,
    val risk: String,
)

@Serializable
data class McpServerRuntimeDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: String,
    val state: String,
    val label: String,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val tools: List<McpToolDto> = emptyList(),
    val pid: Int? = null,
    val latencyMs: Long? = null,
    val restartCount: Int = 0,
    val lastConnectedAt: String? = null,
    val lastHealthyAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class McpRuntimeSnapshotDto(
    val state: String = "idle",
    val label: String = "No MCP servers configured",
    val servers: List<McpServerRuntimeDto> = emptyList(),
    val tools: List<McpToolDto> = emptyList(),
)

@Serializable
data class ProductJobDto(
    val id: String,
    val type: String,
    val status: String,
    val prompt: String,
    val role: String? = null,
    val context: String? = null,
    val expectedOutput: String? = null,
    val maxOutputTokens: Int? = null,
    val model: String? = null,
    val output: String? = null,
    val error: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ProductJobSnapshotDto(
    val state: String = "unavailable",
    val label: String = "Durable jobs unavailable",
    val items: List<ProductJobDto> = emptyList(),
)

@Serializable
data class WorkspaceRuleDto(
    val id: String,
    val name: String,
    val path: String,
    val content: String,
    val trigger: String,
    val selected: Boolean,
    val description: String,
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
    val selectedAgentProfileId: String = "general",
    val runState: String = "idle",
    val agentRun: AgentRunTelemetryDto = AgentRunTelemetryDto(),
    val messages: List<ChatMessageDto> = emptyList(),
    val tools: List<ToolRunDto> = emptyList(),
    val threads: List<ThreadSummaryDto>,
    val tasks: List<TaskDto> = emptyList(),
    val messageQueue: List<QueuedMessageDto> = emptyList(),
    val attachments: List<ContextItemDto> = emptyList(),
    val settings: SettingsSnapshotDto = SettingsSnapshotDto(),
    val account: AccountSnapshotDto = AccountSnapshotDto(),

    val context: ContextSnapshotDto = ContextSnapshotDto(),
    val backendHealth: BackendHealthDto = BackendHealthDto(),
    val models: ModelRegistryDto = ModelRegistryDto(),
    val backendTools: List<BackendToolDto> = emptyList(),
    val configurations: ConfigurationSnapshotDto = ConfigurationSnapshotDto(),
    val mcpRuntime: McpRuntimeSnapshotDto = McpRuntimeSnapshotDto(),
    val hookRuntime: HookRuntimeSnapshotDto = HookRuntimeSnapshotDto(),
    val pluginRuntime: PluginRuntimeSnapshotDto = PluginRuntimeSnapshotDto(),
    val jobs: ProductJobSnapshotDto = ProductJobSnapshotDto(),
    val customization: WorkspaceCustomizationDto = WorkspaceCustomizationDto(),
)
