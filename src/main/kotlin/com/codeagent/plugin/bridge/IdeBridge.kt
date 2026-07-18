package com.codeagent.plugin.bridge

import com.codeagent.plugin.agent.AgentMessage
import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.AgentRunListener
import com.codeagent.plugin.agent.AgentToolCall
import com.codeagent.plugin.agent.ChangeReviewService
import com.codeagent.plugin.agent.FileChange
import com.codeagent.plugin.agent.GitWorkspaceService
import com.codeagent.plugin.agent.HookRuntimeService
import com.codeagent.plugin.agent.HookRuntimeSnapshot
import com.codeagent.plugin.agent.ImageCanvasService
import com.codeagent.plugin.agent.McpRuntimeService
import com.codeagent.plugin.agent.McpRuntimeSnapshot
import com.codeagent.plugin.agent.PluginRuntimeService
import com.codeagent.plugin.agent.PluginRuntimeSnapshot
import com.codeagent.plugin.agent.RemoteContextUpdated
import com.codeagent.plugin.agent.RemoteAgentClient
import com.codeagent.plugin.agent.RemoteBackendHealth
import com.codeagent.plugin.agent.RemoteJob
import com.codeagent.plugin.agent.RemoteJobInput
import com.codeagent.plugin.agent.RemoteJobRequest
import com.codeagent.plugin.agent.RemoteToolCatalogUpdated
import com.codeagent.plugin.agent.RemoteVerificationUpdated
import com.codeagent.plugin.agent.WorkspaceCustomization
import com.codeagent.plugin.agent.WorkspaceCustomizationService
import com.codeagent.plugin.conversation.ConversationStore
import com.codeagent.plugin.conversation.ConversationSnapshot
import com.codeagent.plugin.conversation.ConversationTool
import com.codeagent.plugin.conversation.ConversationSummaryService
import com.codeagent.plugin.conversation.CloudConversationSyncService
import com.codeagent.plugin.context.ContextEngineService
import com.codeagent.plugin.context.resolvedContextHttpApiKey
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.CodeAgentSettingsUpdate
import com.codeagent.plugin.settings.DEFAULT_CONTEXT_MODE
import com.codeagent.plugin.settings.OidcLoginService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.awt.datatransfer.StringSelection
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val BROWSER_EVENT_RETRY_MS = 750L

internal fun bridgeCommandRequiresEdt(type: String): Boolean = type in EDT_BRIDGE_COMMANDS

private val EDT_BRIDGE_COMMANDS = setOf(
    "attachActiveEditor",
    "browseImageDirectory",
    "copyText",
    "copyThread",
    "exportTasks",
    "exportThread",
    "importTasks",
    "importThread",
    "pickContext",
)

class IdeBridge(
    private val project: Project,
    private val browser: JBCefBrowser,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val tools = linkedMapOf<String, ToolRunDto>()
    private val messageTurns = mutableMapOf<String, Int>()
    private val attachments = linkedMapOf<String, ContextItemDto>()
    private val attachmentResolver = AttachmentContextResolver(project)
    private val messageQueue = mutableListOf<QueuedMessageDto>()
    private val conversations = project.service<ConversationStore>()
    private val cloudSync = project.service<CloudConversationSyncService>()
    private val conversationSummaries = project.service<ConversationSummaryService>()
    private val contextEngine = project.service<ContextEngineService>()
    private val agent = project.service<AgentOrchestrator>()
    private val mcpRuntimeService = project.service<McpRuntimeService>()
    private val hookRuntimeService = project.service<HookRuntimeService>()
    private val pluginRuntimeService = project.service<PluginRuntimeService>()
    private val changeReview = project.service<ChangeReviewService>()
    private val gitWorkspace = project.service<GitWorkspaceService>()
    private val imageCanvas = project.service<ImageCanvasService>()
    private val customizations = project.service<WorkspaceCustomizationService>()
    private val settingsService = service<CodeAgentSettingsService>()
    private val oidcLogin = service<OidcLoginService>()
    private val stateLock = Any()
    private val runs = RunGeneration()
    private val contextIndexRuns = RunGeneration()
    private val browserEvents = BrowserEventQueue()
    private val browserEventSequence = AtomicLong()
    private val browserDispatchScheduled = AtomicBoolean()
    private val contextIndexing = AtomicBoolean()
    private val disposed = AtomicBoolean()
    private var runState = "idle"
    private var agentRun = AgentRunTelemetryDto()

    @Volatile
    private var context = ContextSnapshotDto(state = "not_indexed", label = "Checking context")

    @Volatile
    private var backendHealth = BackendHealthDto(state = "checking", label = "Checking backend")

    @Volatile
    private var modelRegistry = ModelRegistryDto(state = "loading", label = "Loading models")

    @Volatile
    private var backendTools = emptyList<BackendToolDto>()

    @Volatile
    private var configurations = ConfigurationSnapshotDto()

    @Volatile
    private var mcpRuntime = mcpRuntimeService.snapshot().toDto()

    @Volatile
    private var hookRuntime = hookRuntimeService.snapshot().toDto()

    @Volatile
    private var pluginRuntime = pluginRuntimeService.snapshot().toDto()

    @Volatile
    private var productJobs = ProductJobSnapshotDto()

    private var productJobsGeneration = 0L

    private fun isRunBusy(): Boolean =
        runState == "starting" || runState == "running" || runState == "awaiting_approval"

    @Volatile
    private var account = AccountSnapshotDto()

    @Volatile
    private var syncedAccountUserId: String? = null

    init {
        restoreConversationPresentation()
        cloudSync.setChangeListener {
            synchronized(stateLock) {
                if (!isRunBusy()) {
                    restoreConversationPresentation()
                }
            }
            emitSnapshot()
        }
        mcpRuntimeService.setChangeListener {
            mcpRuntime = mcpRuntimeService.snapshot().toDto()
            emitSnapshot()
        }
        hookRuntimeService.setChangeListener {
            hookRuntime = hookRuntimeService.snapshot().toDto()
            emitSnapshot()
        }
        pluginRuntimeService.setChangeListener {
            pluginRuntime = pluginRuntimeService.snapshot().toDto()
            emitSnapshot()
        }
        conversationSummaries.setChangeListener { snapshot ->
            syncConversation(snapshot)
            emitSnapshot()
        }
        query.addHandler { raw ->
            handleSafely(raw)
            null
        }
    }

    fun injectBridgeJavaScript(): String = """
          window.codeAgentPost = function(payload) {
            ${query.inject("payload")}
          };
    """.trimIndent()

    fun injectBridgeScript(): String = """
        <script>
          ${injectBridgeJavaScript()}
        </script>
    """.trimIndent()

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        synchronized(stateLock) {
            runs.invalidate()
            interruptActiveToolsLocked("IDE view closed")
        }
        contextIndexRuns.invalidate()
        contextIndexing.set(false)
        agent.cancel()
        cloudSync.setChangeListener(null)
        mcpRuntimeService.setChangeListener(null)
        hookRuntimeService.setChangeListener(null)
        pluginRuntimeService.setChangeListener(null)
        conversationSummaries.setChangeListener(null)
        syncedAccountUserId = null
        cloudSync.reset()
        conversationSummaries.reset()
        browserEvents.reset()
        query.dispose()
    }

    private fun handleSafely(raw: String) {
        runCatching { handle(raw) }
            .onFailure { error ->
                LOG.warn("Failed to handle web command", error)
                emit("error", mapOf("message" to (error.message ?: "Bridge command failed")))
            }
    }

    private fun handle(raw: String) {
        val command = json.decodeFromString<CommandEnvelope>(raw)
        require(command.version == BRIDGE_PROTOCOL_VERSION) {
            "Unsupported bridge protocol ${command.version}"
        }
        if (bridgeCommandRequiresEdt(command.type) && !ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater {
                if (!disposed.get() && !project.isDisposed) handleSafely(raw)
            }
            return
        }

        when (command.type) {
            "ackEvent" -> {
                val acknowledgment = requireNotNull(command.payload).let {
                    json.decodeFromJsonElement<EventAcknowledgmentPayload>(it)
                }
                acknowledgeBrowserEvent(acknowledgment)
            }
            "bootstrap" -> {
                resetBrowserEventTransport()
                emitSnapshot()
                refreshContextStatus()
                checkBackendHealth()
            }
            "setMode" -> {
                synchronized(stateLock) {
                    require(!isRunBusy()) {
                        "Cannot change mode while the agent is running"
                    }
                    conversations.setMode(
                        command.payload?.let { json.decodeFromJsonElement<ModePayload>(it).mode }
                            ?: conversations.active().mode,
                    )
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "selectModel" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ModelSelectionPayload>(it) }
                synchronized(stateLock) {
                    selection.modelId?.let { modelId ->
                        require(modelRegistry.options.any { it.id == modelId }) {
                            "Unknown backend model: $modelId"
                        }
                    }
                    conversations.setSelectedModel(selection.modelId)
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "selectAgentProfile" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<AgentProfileSelectionPayload>(it) }
                synchronized(stateLock) {
                    require(!isRunBusy()) {
                        "Cannot change Agent profile while the agent is running"
                    }
                    require(isKnownAgentProfile(selection.agentProfileId)) {
                        "Unknown Agent profile: ${selection.agentProfileId}"
                    }
                    conversations.setSelectedAgentProfile(selection.agentProfileId)
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "newThread" -> newThread(command.payload)
            "sendMessage", "queueMessage" -> submitMessage(requireNotNull(command.payload) { "${command.type} requires payload" })
            "removeQueuedMessage" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<QueuedMessagePayload>(it) }
                synchronized(stateLock) {
                    require(messageQueue.removeIf { it.id == request.messageId }) { "Queued message no longer exists" }
                }
                emitSnapshot()
            }
            "getContextStatus" -> refreshContextStatus()
            "refreshContextIndex" -> refreshContextStatus(manual = true)
            "checkBackend" -> checkBackendHealth(command.payload)
            "checkContextEngine" -> checkContextEngine(command.payload)
            "indexWorkspace" -> indexWorkspace()
            "saveSettings" -> saveSettings(requireNotNull(command.payload) { "saveSettings requires payload" })
            "signIn" -> signIn()
            "signOut" -> signOut()
            "refreshJobs" -> refreshJobs()
            "createJob" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<CreateJobPayload>(it) }
                createProductJob(request)
            }
            "cancelJob" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<JobPayload>(it) }
                cancelProductJob(request.jobId)
            }
            "retryJob" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<JobPayload>(it) }
                retryProductJob(request.jobId)
            }
            "openJobResult" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<JobPayload>(it) }
                openProductJobResult(request.jobId)
            }
            "refreshConfigurations" -> refreshConfigurations()
            "refreshMcpRuntime" -> refreshMcpRuntime()
            "startMcpServer" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<McpServerPayload>(it) }
                controlMcpServer("start", request.serverId)
            }
            "stopMcpServer" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<McpServerPayload>(it) }
                controlMcpServer("stop", request.serverId)
            }
            "restartMcpServer" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<McpServerPayload>(it) }
                controlMcpServer("restart", request.serverId)
            }
            "testMcpServer" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<McpServerPayload>(it) }
                controlMcpServer("test", request.serverId)
            }
            "saveConfiguration" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<SaveConfigurationPayload>(it) }
                saveConfiguration(request)
            }
            "deleteConfiguration" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<DeleteConfigurationPayload>(it) }
                deleteConfiguration(request)
            }
            "testHook" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<HookPayload>(it) }
                testHook(request.hookId)
            }
            "installPlugin" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<PluginPayload>(it) }
                controlPlugin("install", request.pluginId)
            }
            "updatePlugin" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<PluginPayload>(it) }
                controlPlugin("update", request.pluginId)
            }
            "testPlugin" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<PluginPayload>(it) }
                controlPlugin("test", request.pluginId)
            }
            "uninstallPlugin" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<PluginPayload>(it) }
                controlPlugin("uninstall", request.pluginId)
            }
            "cancelRun" -> {
                val cancellation = synchronized(stateLock) {
                    runs.invalidate()
                    interruptActiveToolsLocked("cancelled by user")
                    val queuedCount = messageQueue.size
                    messageQueue.clear()
                    runState = "idle"
                    conversations.active() to queuedCount
                }
                agent.cancel()
                syncConversation(cancellation.first)
                summarizeConversation(cancellation.first)
                emitSnapshot()
                if (cancellation.second > 0) {
                    emit("notice", mapOf("message" to "Cancelled run and cleared ${cancellation.second} queued message(s)"))
                }
            }
            "resolveApproval" -> {
                val approval = requireNotNull(command.payload).let { json.decodeFromJsonElement<ApprovalPayload>(it) }
                if (agent.resolveApproval(approval.toolId, approval.approved)) {
                    updateTool(approval.toolId) { tool ->
                        if (tool.status != "approval") {
                            tool
                        } else if (approval.approved) {
                            tool.copy(status = "running", summary = "Approved; starting", canRevert = false)
                        } else {
                            tool.copy(status = "rejected", summary = "Skipped by user", canRevert = false)
                        }
                    }
                    synchronized(stateLock) { runState = "running" }
                    emitSnapshot()
                } else {
                    updateTool(approval.toolId) { tool ->
                        if (tool.status == "approval") {
                            tool.copy(status = "rejected", summary = "Approval expired after the run ended", canRevert = false)
                        } else tool
                    }
                    synchronized(stateLock) {
                        if (runState == "awaiting_approval") runState = "idle"
                    }
                    emitSnapshot()
                    emit("notice", mapOf("message" to "This approval expired because the run already ended"))
                }
            }
            "openDiff" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ChangePayload>(it) }
                changeReview.openDiff(selection.toolId)
            }
            "revertChange" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ChangePayload>(it) }
                changeReview.revert(selection.toolId).whenComplete { reverted, error ->
                    when {
                        error != null -> emit("error", mapOf("message" to error.rootMessage()))
                        reverted -> {
                            if (updateTool(selection.toolId) { tool ->
                                tool.copy(
                                    summary = "Reverted ${tool.changePath}",
                                    canRevert = false,
                                )
                            }) emitSnapshot()
                        }
                    }
                }
            }
            "reviewChanges" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ChangesPayload>(it) }
                changeReview.openDiff(requireNotNull(selection.toolIds.firstOrNull()) { "No changes to review" })
            }
            "keepChanges" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ChangesPayload>(it) }
                val kept = changeReview.keep(selection.toolIds)
                kept.forEach { toolId -> updateTool(toolId) { it.copy(canRevert = false) } }
                emitSnapshot()
            }
            "discardChanges" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ChangesPayload>(it) }
                changeReview.revertAll(selection.toolIds).whenComplete { reverted, error ->
                    when {
                        error != null -> emit("error", mapOf("message" to error.rootMessage()))
                        reverted.isNotEmpty() -> {
                            reverted.forEach { toolId ->
                                updateTool(toolId) { tool ->
                                    tool.copy(summary = "Reverted ${tool.changePath}", canRevert = false)
                                }
                            }
                            emitSnapshot()
                        }
                    }
                }
            }
            "enhancePrompt" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<EnhancePromptPayload>(it) }
                require(request.text.isNotBlank()) { "Prompt text is required" }
                require(!isRunBusy()) { "Cannot enhance while the agent is running" }
                val active = synchronized(stateLock) { conversations.active() }
                val mode = request.mode.ifBlank { active.mode }
                val conversationContext = enhancementConversationContext(active)
                contextEngine.retrievePlanned(
                    informationRequest = request.text.trim(),
                    strategy = "balanced",
                    maxTokens = 2_000,
                ).handle { packed, _ -> packed?.packedText.orEmpty() }
                    .thenCompose { repositoryContext ->
                        agent.enhance(
                            text = request.text.trim(),
                            mode = mode,
                            model = active.selectedModelId,
                            agentProfileId = active.selectedAgentProfileId,
                            repositoryContext = repositoryContext,
                            conversationContext = conversationContext,
                        )
                    }.whenComplete { result, error ->
                    when {
                        error != null -> emit("error", mapOf("message" to error.rootMessage()))
                        result == null || result.text.isBlank() -> emit("error", mapOf("message" to "Enhancer returned empty text"))
                        else -> emit("promptEnhanced", mapOf("text" to result.text))
                    }
                }
            }
            "createCheckpoint" -> {
                val request = command.payload?.let { json.decodeFromJsonElement<CheckpointLabelPayload>(it) }
                val summary = changeReview.createCheckpoint(request?.label ?: "Agent checkpoint")
                emit("checkpoints", json.encodeToJsonElement(listOf(summary.toDto()) + changeReview.listCheckpoints().filter { it.id != summary.id }.map { it.toDto() }))
                emit("notice", mapOf("message" to "Checkpoint saved (${summary.changeCount} files)"))
            }
            "listCheckpoints" -> {
                emit("checkpoints", json.encodeToJsonElement(changeReview.listCheckpoints().map { it.toDto() }))
            }
            "restoreCheckpoint" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<CheckpointPayload>(it) }
                changeReview.restoreCheckpoint(request.checkpointId).whenComplete { reverted, error ->
                    when {
                        error != null -> emit("error", mapOf("message" to error.rootMessage()))
                        else -> {
                            reverted.forEach { toolId ->
                                updateTool(toolId) { tool ->
                                    tool.copy(summary = "Restored ${tool.changePath}", canRevert = false)
                                }
                            }
                            emitSnapshot()
                            emit("notice", mapOf("message" to "Restored checkpoint (${reverted.size} files)"))
                            emit("checkpoints", json.encodeToJsonElement(changeReview.listCheckpoints().map { it.toDto() }))
                        }
                    }
                }
            }
            "selectThread" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ThreadPayload>(it) }
                val currentThreadId = synchronized(stateLock) { conversations.active().id }
                if (currentThreadId == selection.threadId) {
                    emitSnapshot()
                } else {
                    val interruptedConversation = synchronized(stateLock) {
                        runs.invalidate()
                        interruptActiveToolsLocked("thread switched")
                        val previous = conversations.active()
                        conversations.select(selection.threadId)
                        restoreConversationPresentation()
                        agentRun = AgentRunTelemetryDto()
                        attachments.clear()
                        messageQueue.clear()
                        runState = "idle"
                        previous
                    }
                    agent.cancel()
                    syncConversation(interruptedConversation)
                    summarizeConversation(interruptedConversation)
                    summarizeConversation(conversations.active())
                    emitSnapshot()
                }
            }
            "toggleThreadPinned" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ThreadPinPayload>(it) }
                val updated = selection.pinned?.let { conversations.setPinnedIfPresent(selection.threadId, it) }
                    ?: conversations.togglePinnedIfPresent(selection.threadId)
                updated?.let(::syncConversation)
                emitSnapshot()
            }
            "deleteThread" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ThreadPayload>(it) }
                deleteThread(selection.threadId)
            }
            "importThread" -> importThread()
            "pickContext" -> pickContext()
            "removeContext" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ContextPayload>(it) }
                synchronized(stateLock) { attachments.remove(selection.id) }
                emitSnapshot()
            }
            "toggleSkill" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<SkillSelectionPayload>(it) }
                val available = availableCustomization().skills
                require(available.any { it.id == selection.skillId }) { "Unknown workspace skill: ${selection.skillId}" }
                synchronized(stateLock) {
                    require(!isRunBusy()) {
                        "Cannot change skills while the agent is running"
                    }
                    val selected = conversations.active().selectedSkillIds.toMutableSet()
                    if (selection.selected) selected += selection.skillId else selected -= selection.skillId
                    conversations.setSelectedSkills(selected)
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "toggleRule" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<RuleSelectionPayload>(it) }
                val rule = requireNotNull(availableCustomization().rules.firstOrNull { it.id == selection.ruleId }) {
                    "Unknown workspace rule: ${selection.ruleId}"
                }
                require(rule.trigger == "manual") { "Only manual rules can be selected per thread" }
                synchronized(stateLock) {
                    val selected = conversations.active().selectedRuleIds.toMutableSet()
                    if (selection.selected) selected += selection.ruleId else selected -= selection.ruleId
                    conversations.setSelectedRules(selected)
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "saveRule" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<SaveRulePayload>(it) }
                val rule = customizations.saveRule(request.fileName, request.content, request.trigger, request.description)
                if (rule.trigger != "manual") {
                    synchronized(stateLock) {
                        conversations.setSelectedRules(conversations.active().selectedRuleIds - rule.id)
                    }
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "refreshCustomization" -> {
                val customization = availableCustomization(refreshWorkspace = true)
                val availableIds = customization.skills.mapTo(mutableSetOf()) { it.id }
                val manualRuleIds = customization.rules.filter { it.trigger == "manual" }.mapTo(mutableSetOf()) { it.id }
                synchronized(stateLock) {
                    conversations.setSelectedSkills(conversations.active().selectedSkillIds.filter { it in availableIds })
                    conversations.setSelectedRules(conversations.active().selectedRuleIds.filter { it in manualRuleIds })
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "setTaskState" -> {
                val update = requireNotNull(command.payload).let { json.decodeFromJsonElement<TaskStatePayload>(it) }
                conversations.updateTask(update.taskId, update.state, null)
                syncActiveConversation()
                emitSnapshot()
            }
            "clearCompletedTasks" -> {
                conversations.clearCompletedTasks()
                syncActiveConversation()
                emitSnapshot()
            }
            "addTask" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<TaskNamePayload>(it) }
                conversations.addTasks(listOf(request.name))
                syncActiveConversation()
                emitSnapshot()
            }
            "deleteTask" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<TaskPayload>(it) }
                conversations.deleteTask(request.taskId)
                syncActiveConversation()
                emitSnapshot()
            }
            "clearTasks" -> {
                conversations.clearTasks()
                syncActiveConversation()
                emitSnapshot()
            }
            "runTask" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<TaskPayload>(it) }
                val task = requireNotNull(conversations.active().tasks.firstOrNull { it.id == request.taskId }) {
                    "Unknown task: ${request.taskId}"
                }
                conversations.updateTask(task.id, "in_progress", null)
                submitMessage(json.encodeToJsonElement(SendMessagePayload("Complete this task: ${task.name}", "agent")))
            }
            "runAllTasks" -> {
                val tasks = conversations.active().tasks.filter { it.state == "not_started" || it.state == "in_progress" }
                require(tasks.isNotEmpty()) { "No runnable tasks" }
                val prompt = buildString {
                    append("Complete these tasks in order, updating their task state as you work:\n")
                    tasks.forEachIndexed { index, task -> append(index + 1).append(". ").append(task.name).append('\n') }
                }
                submitMessage(json.encodeToJsonElement(SendMessagePayload(prompt.trim(), "agent")))
            }
            "exportTasks" -> exportTasks()
            "importTasks" -> importTasks()
            "refreshGit" -> refreshGit()
            "stageGit" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<GitPathsPayload>(it) }
                gitWorkspace.stage(request.paths).whenComplete(::emitGitResult)
            }
            "unstageGit" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<GitPathsPayload>(it) }
                gitWorkspace.unstage(request.paths).whenComplete(::emitGitResult)
            }
            "openGitDiff" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<GitDiffPayload>(it) }
                gitWorkspace.openDiff(request.path, request.staged).whenComplete { _, error ->
                    if (error != null) emit("error", mapOf("message" to error.rootMessage()))
                }
            }
            "suggestCommitMessage" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<GitFilesPayload>(it) }
                val message = gitWorkspace.suggestCommitMessage(request.files)
                emit("gitCommitSuggested", mapOf("message" to message))
            }
            "commitGit" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<GitCommitPayload>(it) }
                gitWorkspace.commit(request.message).whenComplete(::emitGitResult)
            }
            "refreshImageCanvas" -> emitImageCanvas(imageCanvas.scan())
            "browseImageDirectory" -> browseImageDirectory()
            "openImage" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<ImagePayload>(it) }
                val file = imageCanvas.projectPath(request.path)
                val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
                    ?: error("Cannot resolve ${request.path} in the IDE")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                }
            }
            "attachImage" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<ImagePayload>(it) }
                imageCanvas.projectPath(request.path)
                synchronized(stateLock) {
                    attachments[request.path] = ContextItemDto(
                        id = request.path,
                        label = Path.of(request.path).fileName.toString(),
                        path = request.path,
                        kind = "image",
                    )
                }
                emitSnapshot()
            }
            "attachActiveEditor" -> {
                val attachment = attachmentResolver.activeEditorItem()
                synchronized(stateLock) { attachments[attachment.id] = attachment }
                emitSnapshot()
            }
            "openMermaidEditor" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<MermaidEditorPayload>(it) }
                require(request.code.isNotBlank()) { "Mermaid source must not be blank" }
                require(request.code.length <= 8_000) { "Mermaid source exceeds 8000 characters" }
                val name = request.title.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(80)
                    .ifBlank { "codeagent-diagram" }
                val file = LightVirtualFile("$name.mmd", PlainTextFileType.INSTANCE, request.code)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
            "copyThread" -> {
                CopyPasteManager.getInstance().setContents(StringSelection(threadMarkdown(conversations.active())))
                emit("notice", mapOf("message" to "Thread copied as Markdown"))
            }
            "copyText" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<CopyTextPayload>(it) }
                require(request.text.isNotBlank()) { "Clipboard text must not be blank" }
                require(request.text.length <= MAX_CLIPBOARD_TEXT_LENGTH) {
                    "Clipboard text exceeds $MAX_CLIPBOARD_TEXT_LENGTH characters"
                }
                CopyPasteManager.getInstance().setContents(StringSelection(request.text))
                emit("notice", mapOf("message" to "Copied to clipboard"))
            }
            "exportThread" -> exportThread()
            "renameThread" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<RenameThreadPayload>(it) }
                syncConversation(conversations.renameThread(request.threadId, request.title))
                emitSnapshot()
            }
            "openTerminal" -> ApplicationManager.getApplication().invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
            }
            else -> error("Unknown bridge command: ${command.type}")
        }
    }

    private fun newThread(payload: JsonElement?) {
        val interruptedConversation = synchronized(stateLock) {
            runs.invalidate()
            interruptActiveToolsLocked("new thread started")
            val previous = conversations.active()
            tools.clear()
            messageTurns.clear()
            agentRun = AgentRunTelemetryDto()
            attachments.clear()
            messageQueue.clear()
            runState = "idle"
            val mode = payload?.let { json.decodeFromJsonElement<NewThreadPayload>(it).mode }
                ?: conversations.active().mode
            conversations.newThread(mode)
            previous
        }
        agent.cancel()
        syncConversation(interruptedConversation)
        summarizeConversation(interruptedConversation)
        syncActiveConversation()
        emitSnapshot()
    }

    private fun submitMessage(payload: JsonElement) {
        val request = json.decodeFromJsonElement<SendMessagePayload>(payload)
        require(request.text.isNotBlank()) { "Message must not be blank" }
        require(request.mode == "agent" || request.mode == "chat" || request.mode == "ask") {
            "Unsupported message mode: ${request.mode}"
        }
        var startImmediately = false
        var drainQueue = false
        synchronized(stateLock) {
            if (isRunBusy()) {
                require(messageQueue.size < MAX_QUEUED_MESSAGES) { "Queue can contain at most $MAX_QUEUED_MESSAGES messages" }
                messageQueue += QueuedMessageDto(request.clientMessageId ?: UUID.randomUUID().toString(), request.text.trim(), request.mode)
            } else if (messageQueue.isEmpty()) {
                startImmediately = true
            } else {
                require(messageQueue.size < MAX_QUEUED_MESSAGES) { "Queue can contain at most $MAX_QUEUED_MESSAGES messages" }
                messageQueue += QueuedMessageDto(request.clientMessageId ?: UUID.randomUUID().toString(), request.text.trim(), request.mode)
                drainQueue = true
            }
        }
        if (startImmediately) {
            startMessage(json.encodeToJsonElement(request))
        } else {
            emitSnapshot()
            if (drainQueue) startNextQueuedMessage()
        }
    }

    private fun startMessage(payload: JsonElement, fromQueue: Boolean = false) {
        val request = prepareMessage(json.decodeFromJsonElement<SendMessagePayload>(payload))
        require(request.text.isNotBlank()) { "Message must not be blank" }
        require(request.mode == "agent" || request.mode == "chat" || request.mode == "ask") {
            "Unsupported message mode: ${request.mode}"
        }
        request.agentProfileId?.let { agentProfileId ->
            require(isKnownAgentProfile(agentProfileId)) { "Unknown Agent profile: $agentProfileId" }
        }
        val resolvedAttachments = attachmentResolver.resolve(synchronized(stateLock) { attachments.values.toList() })
        val presentationRunId = UUID.randomUUID().toString()
        val runId = synchronized(stateLock) {
            require(if (fromQueue) runState == "starting" else !isRunBusy()) { "Agent is already running" }
            conversations.setMode(request.mode)
            conversations.addMessage(
                role = "user",
                content = request.text.trim(),
                runId = presentationRunId,
                messageId = request.clientMessageId,
            )
            agentRun = AgentRunTelemetryDto()
            runState = "running"
            runs.next()
        }
        syncActiveConversation()
        emitSnapshot()

        val history = synchronized(stateLock) {
            val messages = conversations.active().messages
                .mapTo(mutableListOf()) { AgentMessage(role = it.role, content = it.content) }
            if (resolvedAttachments.isNotEmpty()) {
                val lastUserMessage = messages.indexOfLast { it.role == "user" }
                if (lastUserMessage >= 0) {
                    val message = messages[lastUserMessage]
                    messages[lastUserMessage] = message.copy(attachments = resolvedAttachments)
                }
            }
            messages
        }
        val enabledSkillIds = synchronized(stateLock) { conversations.active().selectedSkillIds.toSet() }
        val enabledRuleIds = synchronized(stateLock) { conversations.active().selectedRuleIds.toSet() }
        val selectedAgentProfileId = request.agentProfileId
            ?: synchronized(stateLock) { conversations.active().selectedAgentProfileId }
        val selectedModel = synchronized(stateLock) { conversations.active().selectedModelId }
        val historySummary = synchronized(stateLock) { conversations.active().summary }
        agent.start(history, historySummary, request.mode, selectedAgentProfileId, selectedModel, enabledSkillIds, enabledRuleIds, object : AgentRunListener {
            private var streamingMessageId: String? = null
            private var streamingTurnIndex: Int? = null
            private var runFailed = false
            private fun emitRunSnapshot() = this@IdeBridge.emitSnapshot()

            override fun onContextUpdated(update: RemoteContextUpdated) {
                if (withCurrentRun(runId) {
                    agentRun = agentRun.copy(
                        turnIndex = update.turnIndex,
                        estimatedInputTokens = update.estimatedInputTokens,
                        targetInputTokens = update.targetInputTokens,
                        contextWindowTokens = update.contextWindowTokens,
                        reservedOutputTokens = update.reservedOutputTokens,
                        retrievalBudgetTokens = update.retrievalBudgetTokens,
                        toolDefinitionTokens = update.toolDefinitionTokens,
                        compactedToolResults = update.compactedToolResults,
                        truncatedMessages = update.truncatedMessages,
                        compactionApplied = update.compactionApplied,
                        overBudget = update.overBudget,
                    )
                }) emitRunSnapshot()
            }

            override fun onToolCatalogUpdated(update: RemoteToolCatalogUpdated) {
                if (withCurrentRun(runId) {
                    agentRun = agentRun.copy(
                        turnIndex = update.turnIndex,
                        activeToolNames = update.activeToolNames,
                        activeToolCount = update.activeToolCount,
                        catalogToolCount = update.catalogToolCount,
                        discoverableToolCount = update.discoverableToolCount,
                        activatedToolNames = update.activated,
                    )
                }) emitRunSnapshot()
            }

            override fun onVerificationUpdated(update: RemoteVerificationUpdated) {
                if (withCurrentRun(runId) {
                    agentRun = agentRun.copy(
                        turnIndex = update.turnIndex,
                        verificationState = update.status,
                        verificationMessage = update.message,
                        verificationToolName = update.toolName,
                    )
                }) emitRunSnapshot()
            }

            override fun onAssistantDelta(delta: String, turnIndex: Int) {
                var messageId = ""
                var firstDelta = false
                if (withCurrentRun(runId) {
                    if (streamingTurnIndex != turnIndex) streamingMessageId = null
                    messageId = streamingMessageId ?: conversations.addMessage(
                        role = "assistant",
                        content = "",
                        runId = presentationRunId,
                        turnIndex = turnIndex,
                    ).id.also {
                        streamingMessageId = it
                        streamingTurnIndex = turnIndex
                        messageTurns[it] = turnIndex
                        firstDelta = true
                    }
                    conversations.appendMessage(messageId, delta)
                }) {
                    if (firstDelta) emitRunSnapshot() else emit("messageDelta", json.encodeToJsonElement(MessageDeltaDto(messageId, delta, turnIndex)))
                }
            }

            override fun onAssistantMessage(content: String?, turnIndex: Int) {
                if (withCurrentRun(runId) {
                    val messageId = streamingMessageId.takeIf { streamingTurnIndex == turnIndex }
                    if (!content.isNullOrBlank()) {
                        if (messageId == null) {
                            conversations.addMessage(
                                role = "assistant",
                                content = content,
                                runId = presentationRunId,
                                turnIndex = turnIndex,
                            ).also { messageTurns[it.id] = turnIndex }
                        } else {
                            conversations.replaceMessage(messageId, content)
                        }
                    }
                    streamingMessageId = null
                    streamingTurnIndex = null
                }) {
                    syncActiveConversation()
                    emitRunSnapshot()
                }
            }

            override fun onFileChanged(call: AgentToolCall, change: FileChange) {
                withCurrentRun(runId) { changeReview.register(call.id, change) }
            }

            override fun onToolChanged(
                call: AgentToolCall,
                summary: String,
                status: String,
                detail: String?,
                turnIndex: Int,
            ) {
                var approvalRequired = false
                if (withCurrentRun(runId) {
                    val previous = tools[call.id]
                    val now = System.currentTimeMillis()
                    val tool = ToolRunDto(
                        id = call.id,
                        name = call.name,
                        summary = summary,
                        status = status,
                        detail = detail,
                        changePath = changeReview.path(call.id),
                        canRevert = changeReview.canRevert(call.id),
                        turnIndex = turnIndex,
                        runId = presentationRunId,
                        createdAt = previous?.createdAt?.takeIf { it > 0 } ?: now,
                        updatedAt = now,
                        timelineSequence = previous?.timelineSequence,
                    )
                    val persisted = conversations.upsertTool(tool.toConversationTool())
                    tools[call.id] = tool.copy(timelineSequence = persisted.timelineSequence.takeIf { it > 0 })
                    runState = if (status == "approval") "awaiting_approval" else "running"
                    approvalRequired = status == "approval" && previous?.status != "approval"
                }) {
                    if (approvalRequired) {
                        notifyUser(
                            title = "CodeAgent approval required",
                            content = summary.ifBlank { call.name },
                            type = NotificationType.WARNING,
                        )
                    }
                    emitRunSnapshot()
                }
            }

            override fun onRunStateChanged(state: String) {
                var startQueued = false
                if (withCurrentRun(runId) {
                    if (state == "idle" || state == "failed") {
                        interruptActiveToolsLocked(
                            if (state == "failed") "Agent run failed before the tool finished"
                            else "Agent run ended before the tool finished",
                        )
                    }
                    runState = state
                    startQueued = (state == "idle" || state == "failed") && messageQueue.isNotEmpty()
                    if (startQueued) runState = "idle"
                }) {
                    if (state == "idle" || state == "failed") {
                        val snapshot = conversations.active()
                        syncConversation(snapshot)
                        summarizeConversation(snapshot)
                        if (!runFailed) {
                            notifyUser(
                                title = "CodeAgent run completed",
                                content = snapshot.title,
                                type = NotificationType.INFORMATION,
                            )
                        }
                    }
                    emitRunSnapshot()
                }
                if (startQueued) startNextQueuedMessage()
            }

            override fun onError(message: String) {
                runFailed = true
                withCurrentRun(runId) {
                    emit("error", mapOf("message" to message))
                    notifyUser(
                        title = "CodeAgent run failed",
                        content = message,
                        type = NotificationType.ERROR,
                    )
                }
            }
        })
    }

    private fun prepareMessage(request: SendMessagePayload): SendMessagePayload {
        val text = request.text.trim()
        if (!text.startsWith('/')) return request.copy(text = text)
        val invocation = ConfiguredCommandRuntime.resolve(
            commandLine = text,
            fallbackMode = request.mode,
            projectName = project.name,
            configurations = configurations.items["commands"].orEmpty() + pluginCommandConfigurations(),
        )
        return request.copy(
            text = invocation.prompt,
            mode = invocation.mode,
            agentProfileId = invocation.agentProfileId,
        )
    }

    private fun withCurrentRun(generation: Long, action: () -> Unit): Boolean = synchronized(stateLock) {
        if (!runs.isCurrent(generation)) return@synchronized false
        action()
        true
    }

    private fun saveSettings(payload: JsonElement) {
        val request = json.decodeFromJsonElement<SettingsPayload>(payload)
        val previous = settingsService.snapshot()
        settingsService.update(
            CodeAgentSettingsUpdate(
                backendUrl = request.backendUrl,
                nodePath = request.nodePath,
                autoApproveReadOnly = request.autoApproveReadOnly,
                chatZoom = request.chatZoom,
                showTimestamps = request.showTimestamps,
                showRunTelemetry = request.showRunTelemetry,
                desktopNotifications = request.desktopNotifications,
                autoDismissNotifications = request.autoDismissNotifications,
                backendToken = request.backendToken,
                contextMode = request.contextMode,
                contextHttpBaseUrl = request.contextHttpBaseUrl,
                contextHttpApiKey = request.contextHttpApiKey,
                contextEmbeddingBaseUrl = request.contextEmbeddingBaseUrl,
                contextEmbeddingModel = request.contextEmbeddingModel,
                contextEmbeddingApiKey = request.contextEmbeddingApiKey,
                contextNeuralRerank = request.contextNeuralRerank,
                contextRerankBaseUrl = request.contextRerankBaseUrl,
                contextRerankModel = request.contextRerankModel,
            ),
        )
        val current = settingsService.snapshot()
        request.requestId?.trim()?.takeIf { it.isNotEmpty() }?.let { requestId ->
            emit(
                "settingsSaved",
                json.encodeToJsonElement(
                    SettingsSavedDto(
                        requestId = requestId,
                        backendTokenConfigured = !current.backendToken.isNullOrBlank(),
                        contextHttpTokenConfigured = resolvedContextHttpApiKey(current) != null,
                        contextEmbeddingTokenConfigured = !current.contextEmbeddingApiKey.isNullOrBlank(),
                    ),
                ),
            )
        }
        if (previous.backendUrl != current.backendUrl || request.backendToken?.isNotBlank() == true) {
            resetCloudSync()
        }
        if (previous.nodePath != current.nodePath ||
            previous.contextMode != current.contextMode ||
            previous.contextHttpBaseUrl != current.contextHttpBaseUrl ||
            previous.contextHttpApiKey != current.contextHttpApiKey ||
            previous.contextEmbeddingBaseUrl != current.contextEmbeddingBaseUrl ||
            previous.contextEmbeddingModel != current.contextEmbeddingModel ||
            previous.contextEmbeddingApiKey != current.contextEmbeddingApiKey ||
            previous.contextNeuralRerank != current.contextNeuralRerank ||
            previous.contextRerankBaseUrl != current.contextRerankBaseUrl ||
            previous.contextRerankModel != current.contextRerankModel) {
            contextIndexRuns.invalidate()
            contextIndexing.set(false)
            contextEngine.restart()
            refreshContextStatus()
        } else {
            emitSnapshot()
        }
        checkBackendHealth()
    }

    private fun availableCustomization(refreshWorkspace: Boolean = false): WorkspaceCustomization {
        val workspace = if (refreshWorkspace) customizations.refresh() else customizations.snapshot()
        val pluginContributions = pluginRuntimeService.snapshot()
        return WorkspaceCustomization(
            rules = workspace.rules + pluginContributions.rules,
            skills = workspace.skills + pluginContributions.skills,
        )
    }

    private fun emitSnapshot() {
        val settings = settingsService.snapshot()
        val snapshot = synchronized(stateLock) {
            val active = conversations.active()
            val customization = availableCustomization()
            val selectedSkillIds = active.selectedSkillIds.toSet()
            val selectedRuleIds = active.selectedRuleIds.toSet()
            AppSnapshotDto(
                projectName = project.name,
                mode = active.mode,
                selectedAgentProfileId = active.selectedAgentProfileId,
                runState = runState,
                agentRun = agentRun,
                messages = active.messages.map { message ->
                    ChatMessageDto(
                        id = message.id,
                        role = message.role,
                        content = message.content,
                        createdAt = message.createdAt,
                        turnIndex = message.turnIndex ?: messageTurns[message.id],
                        runId = message.runId,
                        timelineSequence = message.timelineSequence.takeIf { it > 0 },
                    )
                },
                tools = tools.values.toList(),
                threads = conversations.threads().map { thread ->
                    ThreadSummaryDto(thread.id, thread.title, thread.updatedAt, thread.active, thread.mode, thread.pinned)
                },
                tasks = active.tasks.map { task -> TaskDto(task.id, task.name, task.state) },
                messageQueue = messageQueue.toList(),
                attachments = attachments.values.toList(),
                settings = SettingsSnapshotDto(
                    backendUrl = settings.backendUrl,
                    nodePath = settings.nodePath,
                    backendTokenConfigured = !settings.backendToken.isNullOrBlank(),
                    autoApproveReadOnly = settings.autoApproveReadOnly,
                    chatZoom = settings.chatZoom,
                    showTimestamps = settings.showTimestamps,
                    showRunTelemetry = settings.showRunTelemetry,
                    desktopNotifications = settings.desktopNotifications,
                    autoDismissNotifications = settings.autoDismissNotifications,
                    contextMode = settings.contextMode,
                    contextHttpBaseUrl = settings.contextHttpBaseUrl,
                    contextHttpTokenConfigured = resolvedContextHttpApiKey(settings) != null,
                    contextEmbeddingBaseUrl = settings.contextEmbeddingBaseUrl,
                    contextEmbeddingModel = settings.contextEmbeddingModel,
                    contextEmbeddingTokenConfigured = !settings.contextEmbeddingApiKey.isNullOrBlank(),
                    contextNeuralRerank = settings.contextNeuralRerank,
                    contextRerankBaseUrl = settings.contextRerankBaseUrl,
                    contextRerankModel = settings.contextRerankModel,
                ),
                account = account,
                context = context,
                backendHealth = backendHealth,
                models = modelRegistry.copy(selectedModel = active.selectedModelId),
                backendTools = backendTools,
                configurations = configurations,
                mcpRuntime = mcpRuntime,
                hookRuntime = hookRuntime,
                pluginRuntime = pluginRuntime,
                jobs = productJobs,
                customization = WorkspaceCustomizationDto(
                    rules = customization.rules.map { rule ->
                        WorkspaceRuleDto(
                            id = rule.id,
                            name = rule.name,
                            path = rule.path,
                            content = rule.content,
                            trigger = rule.trigger,
                            selected = rule.id in selectedRuleIds,
                            description = rule.description,
                            source = rule.source,
                        )
                    },
                    skills = customization.skills.map { skill ->
                        WorkspaceSkillDto(
                            id = skill.id,
                            name = skill.name,
                            description = skill.description,
                            path = skill.path,
                            selected = skill.id in selectedSkillIds,
                            source = skill.source,
                        )
                    },
                    maxSelectedSkills = ConversationStore.MAX_SELECTED_SKILLS,
                ),
            )
        }
        emit("snapshot", json.encodeToJsonElement(snapshot))
    }

    private fun isKnownAgentProfile(agentProfileId: String): Boolean =
        agentProfileId in BUILT_IN_AGENT_PROFILES || configurations.items["agents"].orEmpty().any { configuration ->
            configuration.id == agentProfileId && configuration.value["enabled"]?.toString() != "false"
        }

    private fun refreshContextStatus(manual: Boolean = false) {
        if (manual) {
            context = context.copy(
                state = "checking",
                label = "Checking automatic sync",
                watchError = null,
            )
            emitSnapshot()
        }
        contextEngine.status().whenComplete { status, error ->
            if (error != null) {
                context = ContextSnapshotDto(state = "error", label = error.rootMessage())
                emitSnapshot()
                return@whenComplete
            }
            if (!status.indexed) {
                startContextIndex(automatic = true)
                return@whenComplete
            }
            context = ContextSnapshotDto(
                state = "ready",
                label = buildString {
                    append("${status.fileCount} files indexed")
                    if (status.roots.size > 1) append(" across ${status.roots.size} roots")
                    append(if (status.watching) " · Auto-sync on" else " · Manual refresh")
                    if (status.hasEmbeddings) append(" · Semantic search")
                    status.watchError?.let { append(" · Watch error: $it") }
                },
                files = status.fileCount,
                chunks = status.chunkCount,
                roots = status.roots.size.coerceAtLeast(1),
                watchedRoots = status.watchedRootCount,
                watching = status.watching,
                hasEmbeddings = status.hasEmbeddings,
                lastIndexedAt = status.lastIndexedAt,
                pendingChanges = status.pendingChanges,
                automaticIndexRuns = status.automaticIndexRuns,
                lastAutomaticIndexAt = status.lastAutomaticIndexAt,
                watchError = status.watchError,
            )
            emitSnapshot()
            if (manual) {
                val message = when {
                    status.pendingChanges > 0 -> "${status.pendingChanges} context changes are synchronizing"
                    status.watching -> "Automatic sync is active; the index is current"
                    else -> "Index status refreshed; automatic sync is unavailable"
                }
                emit("notice", mapOf("message" to message))
            }
        }
    }

    private fun checkContextEngine(payload: JsonElement?) {
        val request = payload?.let { json.decodeFromJsonElement<CheckContextEnginePayload>(it) }
            ?: CheckContextEnginePayload()
        val current = settingsService.snapshot()
        val mode = request.contextMode?.trim()?.takeIf { it.isNotEmpty() } ?: current.contextMode
        if (mode != "remote-http") {
            contextEngine.status().whenComplete { status, error ->
                emit(
                    "contextConnectionChecked",
                    json.encodeToJsonElement(
                        ContextConnectionCheckedDto(
                            requestId = request.requestId,
                            ok = error == null,
                            label = when {
                                error != null -> error.rootMessage()
                                status?.indexed == true -> "ContextEngine runtime verified"
                                else -> "ContextEngine runtime verified. Project is not indexed."
                            },
                        ),
                    ),
                )
            }
            return
        }

        val baseUrl = request.contextHttpBaseUrl
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: current.contextHttpBaseUrl
        val inputToken = request.contextHttpApiKey?.trim()?.takeIf { it.isNotEmpty() }
        val apiKey = inputToken ?: resolvedContextHttpApiKey(current)
        contextEngine.checkRemoteConnection(baseUrl, apiKey).whenComplete { result, error ->
            emit(
                "contextConnectionChecked",
                json.encodeToJsonElement(
                    ContextConnectionCheckedDto(
                        requestId = request.requestId,
                        ok = error == null && result?.ok == true,
                        label = error?.rootMessage() ?: result?.label ?: "ContextEngine connection check failed",
                    ),
                ),
            )
        }
    }

    private fun checkBackendHealth(payload: JsonElement? = null) {
        if (payload != null) {
            checkBackendConnection(payload)
            return
        }
        backendHealth = BackendHealthDto(state = "checking", label = "Checking backend")
        emitSnapshot()
        agent.health().whenComplete { health, error ->
            backendHealth = backendHealthResult(health, error)
            if (backendHealth.state != "online") {
                backendTools = emptyList()
                modelRegistry = ModelRegistryDto(state = "error", label = backendHealth.label)
                configurations = ConfigurationSnapshotDto(state = "error", label = backendHealth.label)
            }
            emitSnapshot()
            if (backendHealth.state == "online") {
                refreshModels()
                refreshAccount()
            }
        }
    }

    private fun checkBackendConnection(payload: JsonElement) {
        val request = json.decodeFromJsonElement<CheckBackendPayload>(payload)
        val current = settingsService.snapshot()
        val candidate = current.copy(
            backendUrl = request.backendUrl.trim().ifEmpty { current.backendUrl },
            backendToken = request.backendToken?.trim()?.takeIf { it.isNotEmpty() } ?: current.backendToken,
        )
        val healthCheck = runCatching { RemoteAgentClient(candidate).health() }.getOrElse { error ->
            emit(
                "backendConnectionChecked",
                json.encodeToJsonElement(
                    BackendConnectionCheckedDto(
                        requestId = request.requestId,
                        ok = false,
                        label = error.rootMessage(),
                    ),
                ),
            )
            return
        }
        healthCheck.whenComplete { health, error ->
            val result = backendHealthResult(health, error)
            emit(
                "backendConnectionChecked",
                json.encodeToJsonElement(
                    BackendConnectionCheckedDto(
                        requestId = request.requestId,
                        ok = result.state == "online",
                        label = if (result.state == "online") {
                            "Connection verified: ${result.label.removePrefix("Connected to ")}"
                        } else {
                            result.label
                        },
                    ),
                ),
            )
        }
    }

    private fun backendHealthResult(health: RemoteBackendHealth?, error: Throwable?): BackendHealthDto = when {
        error != null -> BackendHealthDto(state = "offline", label = error.rootMessage())
        health == null || !health.ok -> BackendHealthDto(state = "offline", label = "Backend reported unavailable")
        health.protocolVersion != BRIDGE_PROTOCOL_VERSION -> BackendHealthDto(
            state = "incompatible",
            label = "Protocol ${health.protocolVersion} is incompatible",
            protocolVersion = health.protocolVersion,
        )
        else -> BackendHealthDto(
            state = "online",
            label = "Connected to ${health.service}",
            protocolVersion = health.protocolVersion,
            provider = health.provider,
            defaultModel = health.defaultModel,
        )
    }

    private fun signIn() {
        account = account.copy(state = "signing_in", mode = "oidc", label = "Complete sign-in in your browser")
        emitSnapshot()
        oidcLogin.signIn().whenComplete { _, error ->
            if (error != null) {
                account = AccountSnapshotDto(state = "error", mode = "oidc", label = error.rootMessage())
                emitSnapshot()
            } else {
                emit("notice", mapOf("message" to "Signed in"))
                refreshAccount()
            }
        }
    }

    private fun signOut() {
        account = account.copy(state = "signing_out", label = "Signing out")
        resetCloudSync()
        emitSnapshot()
        oidcLogin.signOut().whenComplete { _, error ->
            account = AccountSnapshotDto(state = "signed_out", mode = "oidc", label = "Sign in to sync Agent sessions")
            clearAuthenticatedCapabilities("Sign in to load models")
            emitSnapshot()
            if (error != null) {
                emit("error", mapOf("message" to error.rootMessage()))
            } else {
                emit("notice", mapOf("message" to "Signed out"))
            }
        }
    }

    private fun refreshAccount() {
        account = account.copy(state = "checking", label = "Checking account")
        emitSnapshot()
        oidcLogin.authConfig().whenComplete { config, configError ->
            if (configError != null || config == null) {
                account = AccountSnapshotDto(state = "error", label = configError?.rootMessage() ?: "Authentication unavailable")
                resetCloudSync()
                clearAuthenticatedCapabilities("Authentication unavailable")
                emitSnapshot()
                return@whenComplete
            }

            val tokenConfigured = !settingsService.snapshot().backendToken.isNullOrBlank()
            if ((config.mode == "oidc" || config.mode == "shared-token") && !tokenConfigured) {
                val label = if (config.mode == "oidc") "Sign in to sync Agent sessions" else "Configure the backend token"
                account = AccountSnapshotDto(state = "signed_out", mode = config.mode, label = label)
                resetCloudSync()
                clearAuthenticatedCapabilities(label)
                emitSnapshot()
                return@whenComplete
            }

            agent.account().whenComplete accountRequest@{ response, accountError ->
                if (accountError != null || response == null) {
                    val message = accountError?.rootMessage() ?: "Account unavailable"
                    if (config.mode == "oidc" && message.contains("HTTP 401")) {
                        settingsService.clearAuthTokens()
                        account = AccountSnapshotDto(state = "signed_out", mode = config.mode, label = "Session expired; sign in again")
                    } else {
                        account = AccountSnapshotDto(state = "error", mode = config.mode, label = message)
                    }
                    resetCloudSync()
                    clearAuthenticatedCapabilities(account.label)
                    emitSnapshot()
                    return@accountRequest
                }

                account = AccountSnapshotDto(
                    state = "signed_in",
                    mode = response.session.mode.ifBlank { config.mode },
                    userId = response.user.id,
                    displayName = response.user.displayName,
                    email = response.user.email,
                    usage = response.usage.map { AccountUsageDto(it.kind, it.units) },
                    label = "Signed in as ${response.user.displayName}",
                )
                emitSnapshot()
                refreshBackendTools()
                refreshConfigurations()
                refreshJobs()
                restoreCloudConversations(response.user.id)
            }
        }
    }

    private fun restoreCloudConversations(userId: String) {
        val shouldRestore = synchronized(stateLock) {
            if (syncedAccountUserId == userId) {
                false
            } else {
                syncedAccountUserId = userId
                true
            }
        }
        if (!shouldRestore) return
        cloudSync.reset()
        conversationSummaries.reset()
        cloudSync.restore().whenComplete { result, error ->
            val current = synchronized(stateLock) { syncedAccountUserId == userId && account.userId == userId }
            if (!current) return@whenComplete
            if (error != null) {
                synchronized(stateLock) { if (syncedAccountUserId == userId) syncedAccountUserId = null }
                emit("error", mapOf("message" to "Cloud conversation restore failed: ${error.rootMessage()}"))
            } else if (result.restored > 0 || result.queuedUploads > 0) {
                emit(
                    "notice",
                    mapOf("message" to "Restored ${result.restored} cloud threads; queued ${result.queuedUploads} local updates"),
                )
            }
            if (error == null) summarizeConversation(conversations.active())
            emitSnapshot()
        }
    }

    private fun resetCloudSync() {
        synchronized(stateLock) { syncedAccountUserId = null }
        cloudSync.reset()
        conversationSummaries.reset()
    }

    private fun syncActiveConversation() = syncConversation(conversations.active())

    private fun enhancementConversationContext(snapshot: ConversationSnapshot): String = buildString {
        snapshot.summary?.takeIf { it.isNotBlank() }?.let {
            appendLine("Conversation summary:")
            appendLine(it.take(4_000))
        }
        val recent = snapshot.messages.takeLast(8)
        if (recent.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("Recent conversation:")
            recent.forEach { message ->
                append(message.role)
                append(": ")
                appendLine(message.content.replace(Regex("\\s+"), " ").trim().take(1_200))
            }
        }
    }.trim().take(12_000)

    private fun restoreConversationPresentation(snapshot: ConversationSnapshot = conversations.active()) {
        synchronized(stateLock) {
            tools.clear()
            snapshot.tools.forEach { persisted ->
                val tool = persisted.toDto().let { restored ->
                    if (restored.status == "running" || restored.status == "approval") {
                        restored.copy(
                            status = "failed",
                            summary = "Interrupted before completion",
                            canRevert = false,
                            updatedAt = System.currentTimeMillis(),
                        )
                    } else restored
                }
                tools[tool.id] = tool
                if (tool.status != persisted.status) conversations.upsertTool(tool.toConversationTool())
            }
            messageTurns.clear()
            snapshot.messages.forEach { message ->
                message.turnIndex?.let { messageTurns[message.id] = it }
            }
        }
    }

    private fun interruptActiveToolsLocked(reason: String) {
        conversations.interruptTools(reason).forEach { interrupted ->
            tools[interrupted.id] = interrupted.toDto()
        }
    }

    private fun ConversationTool.toDto() = ToolRunDto(
        id = id,
        name = name,
        summary = summary,
        status = status,
        detail = detail,
        changePath = changePath,
        canRevert = canRevert,
        turnIndex = turnIndex,
        runId = runId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        timelineSequence = timelineSequence.takeIf { it > 0 },
    )

    private fun ToolRunDto.toConversationTool() = ConversationTool(
        id = id,
        name = name,
        summary = summary,
        status = status,
        detail = detail,
        changePath = changePath,
        canRevert = canRevert,
        turnIndex = turnIndex,
        runId = runId,
        createdAt = createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        updatedAt = updatedAt.takeIf { it > 0 } ?: createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        timelineSequence = timelineSequence ?: 0,
    )

    private fun updateTool(toolId: String, update: (ToolRunDto) -> ToolRunDto): Boolean = synchronized(stateLock) {
        val current = tools[toolId] ?: return@synchronized false
        val updated = update(current).copy(updatedAt = System.currentTimeMillis())
        val persisted = conversations.upsertTool(updated.toConversationTool())
        tools[toolId] = updated.copy(timelineSequence = persisted.timelineSequence.takeIf { it > 0 })
        true
    }

    private fun syncConversation(snapshot: ConversationSnapshot) {
        if (cloudServicesActive()) cloudSync.schedule(snapshot)
    }

    private fun summarizeConversation(snapshot: ConversationSnapshot) {
        if (cloudServicesActive()) conversationSummaries.schedule(snapshot)
    }

    private fun cloudServicesActive(): Boolean =
        syncedAccountUserId != null && account.userId == syncedAccountUserId

    private fun notifyUser(title: String, content: String, type: NotificationType) {
        if (!settingsService.snapshot().desktopNotifications) return
        val safeContent = StringUtil.escapeXmlEntities(content.take(500))
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeAgent")
                .createNotification(title, safeContent, type)
                .notify(project)
        }
    }

    private fun clearAuthenticatedCapabilities(label: String) {
        synchronized(stateLock) { productJobsGeneration += 1 }
        mcpRuntimeService.reconcile(emptyList()).exceptionally { null }
        hookRuntimeService.reconcile(emptyList())
        pluginRuntimeService.reconcile(emptyList())
        backendTools = emptyList()
        configurations = ConfigurationSnapshotDto(state = "unavailable", label = label)
        productJobs = ProductJobSnapshotDto(state = "unavailable", label = label)
    }

    private fun refreshModels() {
        modelRegistry = modelRegistry.copy(state = "loading", label = "Loading models")
        emitSnapshot()
        agent.models().whenComplete { response, error ->
            modelRegistry = if (error != null) {
                ModelRegistryDto(state = "error", label = error.rootMessage())
            } else {
                val options = response.data.distinctBy { it.id }.map { ModelOptionDto(it.id, it.ownedBy) }
                ModelRegistryDto(
                    state = "ready",
                    provider = response.provider,
                    defaultModel = response.defaultModel,
                    options = options,
                    label = if (options.isEmpty()) "No models reported" else "${options.size} models",
                )
            }
            val selected = synchronized(stateLock) { conversations.active().selectedModelId }
            if (selected != null && modelRegistry.options.none { it.id == selected }) {
                synchronized(stateLock) { conversations.setSelectedModel(null) }
                syncActiveConversation()
            }
            emitSnapshot()
        }
    }

    private fun refreshBackendTools() {
        agent.tools().whenComplete { response, error ->
            backendTools = if (error != null) {
                emptyList()
            } else {
                response.data.map { tool ->
                    BackendToolDto(
                        name = tool.name,
                        catalogId = tool.catalogId,
                        available = tool.available,
                        unavailableReason = tool.unavailableReason,
                        requiredEnvironment = tool.requiredEnvironment,
                    )
                }
            }
            emitSnapshot()
        }
    }

    private fun refreshConfigurations() {
        configurations = configurations.copy(state = "loading", label = "Loading product configurations")
        emitSnapshot()
        val requests = CONFIGURATION_KINDS.associateWith(agent::configurations)
        CompletableFuture.allOf(*requests.values.toTypedArray()).whenComplete { _, error ->
            configurations = if (error != null) {
                ConfigurationSnapshotDto(state = "error", label = error.rootMessage())
            } else {
                val items = requests.mapValues { (_, request) ->
                    request.join().data.map { item ->
                        ProductConfigurationDto(
                            id = item.id,
                            kind = item.kind,
                            value = item.value,
                            createdAt = item.createdAt,
                            updatedAt = item.updatedAt,
                        )
                    }
                }
                ConfigurationSnapshotDto(
                    state = "ready",
                    label = "${items.values.sumOf(List<ProductConfigurationDto>::size)} configurations",
                    items = items,
                )
            }
            if (error == null) {
                mcpRuntimeService.reconcile(requests.getValue("mcp").join().data).whenComplete { _, mcpError ->
                    if (mcpError != null) emit("error", mapOf("message" to "MCP activation failed: ${mcpError.rootMessage()}"))
                }
                hookRuntimeService.reconcile(requests.getValue("hooks").join().data)
                pluginRuntimeService.reconcile(requests.getValue("plugins").join().data)
                val selected = synchronized(stateLock) { conversations.active().selectedAgentProfileId }
                if (!isKnownAgentProfile(selected)) {
                    synchronized(stateLock) { conversations.setSelectedAgentProfile("general") }
                    syncActiveConversation()
                }
            }
            emitSnapshot()
        }
    }

    private fun refreshMcpRuntime() {
        mcpRuntimeService.refresh().whenComplete { _, error ->
            if (error != null) emit("error", mapOf("message" to error.rootMessage()))
        }
    }

    private fun controlMcpServer(action: String, serverId: String) {
        require(serverId.isNotBlank()) { "MCP server ID is required" }
        val progressLabel = when (action) {
            "start" -> "Starting MCP server"
            "stop" -> "Stopping MCP server"
            "restart" -> "Restarting MCP server"
            "test" -> "Testing MCP server"
            else -> error("Unsupported MCP action: $action")
        }
        mcpRuntime = mcpRuntime.copy(state = "degraded", label = progressLabel)
        emitSnapshot()
        val request = when (action) {
            "start" -> mcpRuntimeService.start(serverId)
            "stop" -> mcpRuntimeService.stop(serverId)
            "restart" -> mcpRuntimeService.restart(serverId)
            "test" -> mcpRuntimeService.test(serverId)
            else -> error("Unsupported MCP action: $action")
        }
        request.whenComplete { _, error ->
            if (error != null) {
                emit("error", mapOf("message" to error.rootMessage()))
            } else {
                val verb = when (action) {
                    "start" -> "Started"
                    "stop" -> "Stopped"
                    "restart" -> "Restarted"
                    "test" -> "Verified"
                    else -> action
                }
                emit("notice", mapOf("message" to "$verb MCP server"))
            }
        }
    }

    private fun McpRuntimeSnapshot.toDto() = McpRuntimeSnapshotDto(
        state = state,
        label = label,
        servers = servers.map { server ->
            McpServerRuntimeDto(
                id = server.id,
                name = server.name,
                enabled = server.enabled,
                transport = server.transport,
                state = server.state,
                label = server.label,
                serverName = server.serverName,
                serverVersion = server.serverVersion,
                protocolVersion = server.protocolVersion,
                capabilities = server.capabilities,
                tools = server.tools.map { tool ->
                    McpToolDto(
                        id = tool.id,
                        serverId = tool.serverId,
                        name = tool.name,
                        title = tool.title,
                        description = tool.description,
                        parameters = tool.parameters,
                        risk = tool.risk,
                    )
                },
                pid = server.pid,
                latencyMs = server.latencyMs,
                restartCount = server.restartCount,
                lastConnectedAt = server.lastConnectedAt,
                lastHealthyAt = server.lastHealthyAt,
                lastError = server.lastError,
            )
        },
        tools = tools.map { tool ->
            McpToolDto(
                id = tool.id,
                serverId = tool.serverId,
                name = tool.name,
                title = tool.title,
                description = tool.description,
                parameters = tool.parameters,
                risk = tool.risk,
            )
        },
    )

    private fun refreshJobs() {
        val generation = synchronized(stateLock) {
            productJobsGeneration += 1
            productJobsGeneration
        }
        productJobs = productJobs.copy(state = "loading", label = "Loading durable jobs")
        emitSnapshot()
        agent.jobs().whenComplete { response, error ->
            val current = synchronized(stateLock) { generation == productJobsGeneration }
            if (!current) return@whenComplete
            productJobs = if (error != null) {
                ProductJobSnapshotDto(
                    state = "error",
                    label = error.rootMessage(),
                    items = productJobs.items,
                )
            } else {
                val items = response.data.map { it.toDto() }
                ProductJobSnapshotDto(
                    state = "ready",
                    label = if (items.isEmpty()) "No durable jobs" else "${items.size} durable jobs",
                    items = items,
                )
            }
            emitSnapshot()
        }
    }

    private fun createProductJob(request: CreateJobPayload) {
        require(request.prompt.isNotBlank()) { "Job task is required" }
        require(request.role in SUBAGENT_ROLES) { "Unsupported subagent role: ${request.role}" }
        productJobs = productJobs.copy(state = "loading", label = "Creating ${request.role} job")
        emitSnapshot()
        agent.createJob(
            RemoteJobRequest(
                type = "subagent",
                input = RemoteJobInput(
                    prompt = request.prompt.trim(),
                    model = request.model?.trim()?.takeIf(String::isNotEmpty),
                    role = request.role,
                    context = request.context?.trim()?.takeIf(String::isNotEmpty),
                    expectedOutput = request.expectedOutput?.trim()?.takeIf(String::isNotEmpty),
                    maxOutputTokens = request.maxOutputTokens,
                ),
            ),
        ).whenComplete { job, error ->
            if (error != null) {
                productJobs = ProductJobSnapshotDto(
                    state = "error",
                    label = error.rootMessage(),
                    items = productJobs.items,
                )
                emitSnapshot()
                emit("error", mapOf("message" to error.rootMessage()))
            } else {
                emit("notice", mapOf("message" to "Started ${job.roleLabel()} job"))
                refreshJobs()
            }
        }
    }

    private fun cancelProductJob(jobId: String) {
        require(jobId.isNotBlank()) { "Job ID is required" }
        agent.cancelJob(jobId).whenComplete { _, error ->
            if (error != null) {
                emit("error", mapOf("message" to error.rootMessage()))
            } else {
                emit("notice", mapOf("message" to "Cancelled durable job"))
                refreshJobs()
            }
        }
    }

    private fun retryProductJob(jobId: String) {
        val job = productJobs.items.firstOrNull { it.id == jobId }
            ?: error("Job no longer exists in the current snapshot")
        createProductJob(
            CreateJobPayload(
                prompt = job.prompt,
                role = job.role ?: "research",
                context = job.context,
                expectedOutput = job.expectedOutput,
                maxOutputTokens = job.maxOutputTokens,
                model = job.model,
            ),
        )
    }

    private fun openProductJobResult(jobId: String) {
        val job = productJobs.items.firstOrNull { it.id == jobId }
            ?: error("Job no longer exists in the current snapshot")
        val output = requireNotNull(job.output?.takeIf { it.isNotBlank() }) { "Job result is not available yet" }
        val type = (job.role ?: job.type).replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "result" }
        val file = LightVirtualFile(
            "codeagent-$type-${job.id.take(8)}.md",
            PlainTextFileType.INSTANCE,
            buildString {
                appendLine("# CodeAgent ${(job.role ?: job.type).replace('-', ' ')} result")
                appendLine()
                appendLine("## Task")
                appendLine(job.prompt)
                job.context?.takeIf { it.isNotBlank() }?.let {
                    appendLine()
                    appendLine("## Delegated context")
                    appendLine(it)
                }
                appendLine()
                appendLine("## Output")
                appendLine(output)
            },
        )
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private fun RemoteJob.toDto() = ProductJobDto(
        id = id,
        type = type,
        status = status,
        prompt = input.prompt,
        role = input.role ?: output?.role,
        context = input.context,
        expectedOutput = input.expectedOutput,
        maxOutputTokens = input.maxOutputTokens,
        model = input.model ?: output?.model,
        output = output?.content,
        outputPartial = output?.partial == true,
        error = error,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun RemoteJob.roleLabel(): String =
        (input.role ?: output?.role ?: type).replace('-', ' ')

    private fun HookRuntimeSnapshot.toDto() = HookRuntimeSnapshotDto(
        state = when {
            configured == 0 -> "idle"
            recent.firstOrNull()?.status in setOf("failed", "timeout") -> "degraded"
            else -> "ready"
        },
        label = when {
            configured == 0 -> "No hooks configured"
            automatic == 0 -> "$configured hooks · manual execution"
            else -> "$configured hooks · $automatic automatic"
        },
        configured = configured,
        automatic = automatic,
        recent = recent.map { execution ->
            HookExecutionDto(
                id = execution.id,
                hookId = execution.hookId,
                hookName = execution.hookName,
                event = execution.event,
                status = execution.status,
                exitCode = execution.exitCode,
                startedAt = execution.startedAt,
                durationMs = execution.durationMs,
                summary = execution.summary,
                detail = execution.detail,
            )
        },
    )

    private fun PluginRuntimeSnapshot.toDto() = PluginRuntimeSnapshotDto(
        state = state,
        label = label,
        items = items.map { item ->
            PluginRuntimeItemDto(
                id = item.id,
                name = item.name,
                description = item.description,
                source = item.source,
                state = item.state,
                label = item.label,
                configuredVersion = item.configuredVersion,
                installedVersion = item.installedVersion,
                latestVersion = item.latestVersion,
                integrity = item.integrity,
                grantedCapabilities = item.grantedCapabilities,
                declaredCapabilities = item.declaredCapabilities,
                commandCount = item.commandCount,
                promptCount = item.promptCount,
                ruleCount = item.ruleCount,
                skillCount = item.skillCount,
                installedAt = item.installedAt,
                lastCheckedAt = item.lastCheckedAt,
                lastError = item.lastError,
            )
        },
        commands = commands.map { command ->
            PluginCommandDto(
                id = command.id,
                pluginId = command.pluginId,
                pluginVersion = command.pluginVersion,
                name = command.name,
                description = command.description,
                argumentHint = command.argumentHint,
                mode = command.mode,
                agentProfileId = command.agentProfileId,
            )
        },
        prompts = prompts.map { prompt ->
            PluginPromptDto(
                id = prompt.id,
                pluginId = prompt.pluginId,
                pluginVersion = prompt.pluginVersion,
                name = prompt.name,
                description = prompt.description,
                argumentHint = prompt.argumentHint,
            )
        },
    )

    private fun pluginCommandConfigurations(): List<ProductConfigurationDto> =
        pluginRuntimeService.snapshot().let { runtime ->
            runtime.commands.map { command ->
                ProductConfigurationDto(
                    id = command.id,
                    kind = "commands",
                    value = buildJsonObject {
                        put("name", command.name)
                        command.description?.let { put("description", it) }
                        put("enabled", true)
                        put("prompt", command.prompt)
                        command.argumentHint?.let { put("argumentHint", it) }
                        put("mode", command.mode)
                        command.agentProfileId?.let { put("agentProfileId", it) }
                        put("pluginId", command.pluginId)
                        put("pluginVersion", command.pluginVersion)
                        put("pluginCapability", "commands")
                    },
                )
            } + runtime.prompts.map { prompt ->
                ProductConfigurationDto(
                    id = prompt.id,
                    kind = "commands",
                    value = buildJsonObject {
                        put("name", prompt.name)
                        prompt.description?.let { put("description", it) }
                        put("enabled", true)
                        put("prompt", prompt.prompt)
                        prompt.argumentHint?.let { put("argumentHint", it) }
                        put("mode", "inherit")
                        put("pluginId", prompt.pluginId)
                        put("pluginVersion", prompt.pluginVersion)
                        put("pluginCapability", "prompts")
                    },
                )
            }
        }

    private fun saveConfiguration(request: SaveConfigurationPayload) {
        configurations = configurations.copy(state = "loading", label = "Saving ${request.id}")
        emitSnapshot()
        agent.putConfiguration(request.kind, request.id, request.value).whenComplete { _, error ->
            if (error != null) {
                configurations = configurations.copy(state = "error", label = error.rootMessage())
                emitSnapshot()
                emit("error", mapOf("message" to error.rootMessage()))
            } else {
                emit("notice", mapOf("message" to "Saved ${request.id}"))
                refreshConfigurations()
            }
        }
    }

    private fun deleteConfiguration(request: DeleteConfigurationPayload) {
        configurations = configurations.copy(state = "loading", label = "Deleting ${request.id}")
        emitSnapshot()
        agent.deleteConfiguration(request.kind, request.id).whenComplete { deleted, error ->
            when {
                error != null -> {
                    configurations = configurations.copy(state = "error", label = error.rootMessage())
                    emitSnapshot()
                    emit("error", mapOf("message" to error.rootMessage()))
                }
                deleted != true -> {
                    emit("error", mapOf("message" to "Configuration no longer exists"))
                    refreshConfigurations()
                }
                else -> {
                    if (request.kind == "plugins") {
                        pluginRuntimeService.uninstall(request.id).whenComplete { _, uninstallError ->
                            if (uninstallError != null) {
                                emit("error", mapOf("message" to "Plugin cache cleanup failed: ${uninstallError.rootMessage()}"))
                            }
                            emit("notice", mapOf("message" to "Deleted ${request.id}"))
                            refreshConfigurations()
                        }
                    } else {
                        emit("notice", mapOf("message" to "Deleted ${request.id}"))
                        refreshConfigurations()
                    }
                }
            }
        }
    }

    private fun testHook(hookId: String) {
        require(hookId.isNotBlank()) { "Hook ID is required" }
        hookRuntimeService.test(hookId).whenComplete { execution, error ->
            if (error != null) {
                emit("error", mapOf("message" to error.rootMessage()))
            } else {
                emit("notice", mapOf("message" to "${execution.hookName}: ${execution.summary}"))
                emitSnapshot()
            }
        }
    }

    private fun controlPlugin(action: String, pluginId: String) {
        require(pluginId.isNotBlank()) { "Plugin ID is required" }
        val progress = when (action) {
            "install" -> "Installing plugin"
            "update" -> "Updating plugin"
            "test" -> "Checking plugin manifest"
            "uninstall" -> "Uninstalling plugin"
            else -> error("Unsupported plugin action: $action")
        }
        pluginRuntime = pluginRuntime.copy(state = "degraded", label = "$progress · $pluginId")
        emitSnapshot()
        val request = when (action) {
            "install" -> pluginRuntimeService.install(pluginId)
            "update" -> pluginRuntimeService.update(pluginId)
            "test" -> pluginRuntimeService.test(pluginId)
            "uninstall" -> pluginRuntimeService.uninstall(pluginId)
            else -> error("Unsupported plugin action: $action")
        }
        request.whenComplete { snapshot, error ->
            if (error != null) {
                emit("error", mapOf("message" to error.rootMessage()))
            } else {
                pluginRuntime = snapshot.toDto()
                val completed = when (action) {
                    "install" -> "Installed"
                    "update" -> "Updated"
                    "test" -> "Validated"
                    "uninstall" -> "Uninstalled"
                    else -> "Updated"
                }
                emit("notice", mapOf("message" to "$completed $pluginId"))
                emitSnapshot()
            }
        }
    }

    private fun startNextQueuedMessage() {
        val next = synchronized(stateLock) {
            if (runState != "idle") return
            messageQueue.removeFirstOrNull()?.also { runState = "starting" }
        } ?: return
        emitSnapshot()
        runCatching {
            startMessage(
                json.encodeToJsonElement(SendMessagePayload(next.text, next.mode, clientMessageId = next.id)),
                fromQueue = true,
            )
        }.onFailure { error ->
            val continueQueue = synchronized(stateLock) {
                if (runState == "starting") runState = "idle"
                messageQueue.isNotEmpty()
            }
            emit("error", mapOf("message" to error.rootMessage()))
            emitSnapshot()
            if (continueQueue) startNextQueuedMessage()
        }
    }

    private fun indexWorkspace() = startContextIndex(automatic = false)

    private fun startContextIndex(automatic: Boolean) {
        if (!contextIndexing.compareAndSet(false, true)) return
        val generation = contextIndexRuns.next()
        context = ContextSnapshotDto(
            state = "indexing",
            label = if (automatic) "Preparing automatic project index" else "Starting project index",
        )
        emitSnapshot()
        contextEngine.index { progress ->
            if (!contextIndexRuns.isCurrent(generation)) return@index
            val count = if (progress.filesTotal > 0) "${progress.filesDone}/${progress.filesTotal}" else progress.phase
            context = ContextSnapshotDto(state = "indexing", label = "Indexing $count")
            emitSnapshot()
        }.whenComplete { _, error ->
            if (!contextIndexRuns.isCurrent(generation)) return@whenComplete
            contextIndexing.set(false)
            if (error != null) {
                context = ContextSnapshotDto(state = "error", label = error.rootMessage())
                emitSnapshot()
            } else {
                refreshContextStatus()
            }
        }
    }

    private fun pickContext() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Attach Project File")
            .withDescription("Select a file to add to the current CodeAgent task")
        FileChooser.chooseFile(descriptor, project, null) { file ->
            val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
            val selected = file.toNioPath().toAbsolutePath().normalize()
            if (!selected.startsWith(root)) {
                emit("error", mapOf("message" to "Only files inside the current project can be attached"))
                return@chooseFile
            }
            val relative = root.relativize(selected).toString()
            synchronized(stateLock) {
                attachments[relative] = ContextItemDto(
                    id = relative,
                    label = file.name,
                    path = relative,
                    kind = attachmentResolver.kindFor(relative),
                )
            }
            emitSnapshot()
        }
    }

    private fun refreshGit() {
        gitWorkspace.snapshot().whenComplete(::emitGitResult)
    }

    private fun deleteThread(threadId: String) {
        if (conversations.threads().none { it.id == threadId }) {
            emitSnapshot()
            return
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete this CodeAgent thread? This cannot be undone.",
                "Delete CodeAgent Thread",
                "Delete",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) {
                emitSnapshot()
                return@invokeLater
            }
            val wasActive = synchronized(stateLock) {
                if (conversations.threads().none { it.id == threadId }) return@synchronized null
                val active = conversations.active().id == threadId
                if (active) {
                    runs.invalidate()
                    tools.clear()
                    messageTurns.clear()
                    agentRun = AgentRunTelemetryDto()
                    attachments.clear()
                    messageQueue.clear()
                    runState = "idle"
                }
                conversations.deleteThreadIfPresent(threadId)
                if (active) restoreConversationPresentation()
                active
            }
            if (wasActive == null) {
                emitSnapshot()
                return@invokeLater
            }
            if (wasActive) agent.cancel()
            conversationSummaries.forget(threadId)
            if (cloudServicesActive()) {
                cloudSync.delete(threadId).whenComplete { _, error ->
                    if (error != null) emit("error", mapOf("message" to "Cloud thread deletion failed: ${error.rootMessage()}"))
                }
            }
            emitSnapshot()
        }
    }

    private fun threadMarkdown(active: com.codeagent.plugin.conversation.ConversationSnapshot): String = buildString {
        append("# ").append(active.title).append("\n\n")
        append("<!-- codeagent-thread mode:").append(active.mode).append(" -->\n\n")
        active.messages.forEach { message ->
            append("## ").append(if (message.role == "user") "User" else "CodeAgent").append("\n\n")
            append(message.content).append("\n\n")
        }
    }.trim()

    private fun exportThread() {
        val active = conversations.active()
        val markdown = threadMarkdown(active)
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Export CodeAgent Thread")
            .withDescription("Choose a folder for the Markdown export")
        FileChooser.chooseFile(descriptor, project, null) { folder ->
            runCatching {
                val safe = active.title.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "thread" }
                val target = Path.of(folder.path, "codeagent-$safe.md")
                Files.writeString(target, markdown, StandardCharsets.UTF_8)
                emit("notice", mapOf("message" to "Exported ${target.fileName}"))
            }.onFailure { error ->
                emit("error", mapOf("message" to (error.message ?: "Export failed")))
            }
        }
    }

    private fun importThread() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Import CodeAgent Thread")
            .withDescription("Select a CodeAgent Markdown thread export")
        FileChooser.chooseFile(descriptor, project, null) { file ->
            runCatching {
                require(file.length <= MAX_THREAD_IMPORT_BYTES) { "Thread export exceeds 2 MB" }
                val content = Files.readString(file.toNioPath(), StandardCharsets.UTF_8)
                val title = content.lineSequence().firstOrNull { it.startsWith("# ") }
                    ?.removePrefix("# ")?.trim().orEmpty().ifBlank { file.nameWithoutExtension }
                val mode = Regex("""<!--\s*codeagent-thread\s+mode:(agent|chat|ask)\s*-->""")
                    .find(content)?.groupValues?.get(1) ?: "agent"
                conversations.importThread(title, mode, parseThreadMessages(content))
            }.onSuccess {
                synchronized(stateLock) {
                    runs.invalidate()
                    tools.clear()
                    messageTurns.clear()
                    agentRun = AgentRunTelemetryDto()
                    attachments.clear()
                    messageQueue.clear()
                    runState = "idle"
                }
                agent.cancel()
                val snapshot = conversations.active()
                syncConversation(snapshot)
                summarizeConversation(snapshot)
                emitSnapshot()
            }.onFailure { emit("error", mapOf("message" to it.rootMessage())) }
        }
    }

    private fun parseThreadMessages(content: String): List<Pair<String, String>> {
        val messages = mutableListOf<Pair<String, String>>()
        var role: String? = null
        val body = StringBuilder()
        fun flush() {
            val currentRole = role ?: return
            body.toString().trim().takeIf(String::isNotEmpty)?.let { messages += currentRole to it }
            body.setLength(0)
        }
        content.lineSequence().forEach { line ->
            when (line.trim()) {
                "## User" -> {
                    flush()
                    role = "user"
                }
                "## CodeAgent", "## Assistant" -> {
                    flush()
                    role = "assistant"
                }
                else -> if (role != null) body.append(line).append('\n')
            }
        }
        flush()
        return messages
    }

    private fun emitGitResult(snapshot: GitSnapshotDto?, error: Throwable?) {
        if (error != null) {
            emit("error", mapOf("message" to error.rootMessage()))
        } else if (snapshot != null) {
            emit("gitSnapshot", json.encodeToJsonElement(snapshot))
        }
    }

    private fun browseImageDirectory() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Choose Image Canvas Directory")
            .withDescription("Choose a directory inside the current project")
        FileChooser.chooseFile(descriptor, project, null) { folder ->
            runCatching { imageCanvas.selectDirectory(folder.toNioPath()) }
                .onSuccess(::emitImageCanvas)
                .onFailure { emit("error", mapOf("message" to it.rootMessage())) }
        }
    }

    private fun emitImageCanvas(snapshot: ImageCanvasSnapshotDto) {
        emit("imageCanvas", json.encodeToJsonElement(snapshot))
    }

    private fun exportTasks() {
        val tasks = conversations.active().tasks
        require(tasks.isNotEmpty()) { "No tasks to export" }
        val markdown = tasks.joinToString("\n") { task ->
            val marker = if (task.state == "completed") "x" else " "
            "- [$marker] ${task.name} <!-- codeagent:${task.state} -->"
        }
        CopyPasteManager.getInstance().setContents(StringSelection(markdown))
        emit("notice", mapOf("message" to "Tasks copied as Markdown"))
    }

    private fun importTasks() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Import CodeAgent Tasks")
            .withDescription("Select a UTF-8 Markdown task list")
        FileChooser.chooseFile(descriptor, project, null) { file ->
            runCatching {
                val content = Files.readString(file.toNioPath(), StandardCharsets.UTF_8)
                val taskPattern = Regex("""^\s*[-*]\s+\[([ xX])]\s+(.+?)(?:\s+<!--\s*codeagent:([a-z_]+)\s*-->)?\s*$""")
                val tasks = content.lineSequence().mapNotNull { line ->
                    val match = taskPattern.matchEntire(line) ?: return@mapNotNull null
                    val name = match.groupValues[2].trim()
                    val metadata = match.groupValues[3]
                    val state = metadata.ifBlank { if (match.groupValues[1].equals("x", true)) "completed" else "not_started" }
                    name to state
                }.take(ConversationStore.MAX_IMPORT_TASKS).toList()
                conversations.importTasks(tasks)
            }.onSuccess {
                syncActiveConversation()
                emitSnapshot()
            }
                .onFailure { emit("error", mapOf("message" to it.rootMessage())) }
        }
    }

    private fun Throwable.rootMessage(): String {
        var current: Throwable = this
        while (current.cause != null) current = current.cause!!
        return current.message ?: "ContextEngine unavailable"
    }

    private fun emit(type: String, payload: Any?) {
        val element = when (payload) {
            null -> null
            is JsonElement -> payload
            is Map<*, *> -> json.encodeToJsonElement(payload.entries.associate { it.key.toString() to it.value.toString() })
            else -> error("Unsupported event payload: ${payload::class.simpleName}")
        }
        val sequence = browserEventSequence.incrementAndGet()
        val eventJson = json.encodeToString(EventEnvelope(sequence = sequence, type = type, payload = element))
        val escaped = json.encodeToString(eventJson)
        browserEvents.enqueue(BrowserEvent(sequence, "window.CodeAgent && window.CodeAgent.receive($escaped);"))
        scheduleBrowserDispatch()
    }

    private fun scheduleBrowserDispatch() {
        if (disposed.get()) {
            browserEvents.reset()
            return
        }
        if (!browserDispatchScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            try {
                if (disposed.get()) {
                    browserEvents.reset()
                    return@invokeLater
                }
                browserEvents.nextForDispatch()?.let(::dispatchBrowserEvent)
            } finally {
                browserDispatchScheduled.set(false)
                if (!disposed.get() && browserEvents.hasDispatchableEvent()) scheduleBrowserDispatch()
            }
        }
    }

    private fun dispatchBrowserEvent(event: BrowserEvent) {
        if (disposed.get() || !browserEvents.isCurrent(event.sequence)) return
        runCatching {
            browser.cefBrowser.executeJavaScript(event.script, browser.cefBrowser.url, 0)
        }.onFailure { error ->
            LOG.warn("Failed to dispatch browser event ${event.sequence}", error)
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                val retry = if (disposed.get()) null else browserEvents.retry(event.sequence)
                if (retry != null) {
                    if (retry.attempts == 1 || retry.attempts % 10 == 0) {
                        LOG.warn("Retrying unacknowledged browser event ${retry.sequence} (attempt ${retry.attempts})")
                    }
                    ApplicationManager.getApplication().invokeLater { dispatchBrowserEvent(retry) }
                }
            },
            BROWSER_EVENT_RETRY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun acknowledgeBrowserEvent(acknowledgment: EventAcknowledgmentPayload) {
        if (!browserEvents.acknowledge(acknowledgment.sequence)) return
        if (acknowledgment.eventType == "snapshot") {
            val currentMessageCount = synchronized(stateLock) { conversations.active().messages.size }
            LOG.info(
                "Browser acknowledged snapshot event ${acknowledgment.sequence}: " +
                    "receivedMessages=${acknowledgment.snapshotMessageCount} " +
                    "currentMessages=$currentMessageCount lastMessageId=${acknowledgment.lastMessageId}",
            )
        }
        scheduleBrowserDispatch()
    }

    private fun resetBrowserEventTransport() {
        browserEvents.reset()
        browserDispatchScheduled.set(false)
    }

    private fun com.codeagent.plugin.agent.CheckpointSummary.toDto() = CheckpointSummaryDto(
        id = id,
        label = label,
        createdAt = createdAt,
        changeCount = changeCount,
        paths = paths,
    )

    @Serializable
    private data class ModePayload(val mode: String)

    @Serializable
    private data class EventAcknowledgmentPayload(
        val sequence: Long,
        val eventType: String? = null,
        val snapshotMessageCount: Int? = null,
        val lastMessageId: String? = null,
    )

    @Serializable
    private data class NewThreadPayload(val mode: String)

    @Serializable
    private data class SendMessagePayload(
        val text: String,
        val mode: String,
        val agentProfileId: String? = null,
        val clientMessageId: String? = null,
    )

    @Serializable
    private data class ApprovalPayload(val toolId: String, val approved: Boolean)

    @Serializable
    private data class ChangePayload(val toolId: String)

    @Serializable
    private data class ChangesPayload(val toolIds: List<String>)

    @Serializable
    private data class ThreadPayload(val threadId: String)

    @Serializable
    private data class ThreadPinPayload(val threadId: String, val pinned: Boolean? = null)

    @Serializable
    private data class RenameThreadPayload(val threadId: String, val title: String)

    @Serializable
    private data class ContextPayload(val id: String)

    @Serializable
    private data class SkillSelectionPayload(val skillId: String, val selected: Boolean)

    @Serializable
    private data class RuleSelectionPayload(val ruleId: String, val selected: Boolean)

    @Serializable
    private data class SaveRulePayload(
        val fileName: String,
        val content: String,
        val trigger: String,
        val description: String = "",
    )

    @Serializable
    private data class TaskStatePayload(val taskId: String, val state: String)

    @Serializable
    private data class TaskPayload(val taskId: String)

    @Serializable
    private data class TaskNamePayload(val name: String)

    @Serializable
    private data class QueuedMessagePayload(val messageId: String)

    @Serializable
    private data class GitPathsPayload(val paths: List<String>)

    @Serializable
    private data class GitDiffPayload(val path: String, val staged: Boolean)

    @Serializable
    private data class GitFilesPayload(val files: List<GitFileDto>)

    @Serializable
    private data class GitCommitPayload(val message: String)

    @Serializable
    private data class ImagePayload(val path: String)

    @Serializable
    private data class MermaidEditorPayload(val title: String, val code: String)

    @Serializable
    private data class CopyTextPayload(val text: String)

    @Serializable
    private data class ModelSelectionPayload(val modelId: String? = null)

    @Serializable
    private data class AgentProfileSelectionPayload(val agentProfileId: String)

    @Serializable
    private data class EnhancePromptPayload(val text: String, val mode: String = "agent")

    @Serializable
    private data class CheckpointLabelPayload(val label: String = "Agent checkpoint")

    @Serializable
    private data class CheckpointPayload(val checkpointId: String)

    @Serializable
    private data class SettingsPayload(
        val backendUrl: String,
        val nodePath: String,
        val autoApproveReadOnly: Boolean = true,
        val chatZoom: Int = 100,
        val showTimestamps: Boolean = true,
        val showRunTelemetry: Boolean = true,
        val desktopNotifications: Boolean = false,
        val autoDismissNotifications: Boolean = true,
        val backendToken: String? = null,
        val contextMode: String = DEFAULT_CONTEXT_MODE,
        val contextHttpBaseUrl: String = "http://127.0.0.1:8790",
        val contextHttpApiKey: String? = null,
        val contextEmbeddingBaseUrl: String = "http://127.0.0.1:8000/v1",
        val contextEmbeddingModel: String = "Qwen/Qwen3-Embedding-0.6B",
        val contextEmbeddingApiKey: String? = null,
        val contextNeuralRerank: Boolean = false,
        val contextRerankBaseUrl: String = "",
        val contextRerankModel: String = "Qwen/Qwen3-Reranker-0.6B",
        val requestId: String? = null,
    )

    @Serializable
    private data class CheckContextEnginePayload(
        val requestId: String = "",
        val contextMode: String? = null,
        val contextHttpBaseUrl: String? = null,
        val contextHttpApiKey: String? = null,
    )

    @Serializable
    private data class CheckBackendPayload(
        val requestId: String = "",
        val backendUrl: String,
        val backendToken: String? = null,
    )

    @Serializable
    private data class SaveConfigurationPayload(
        val kind: String,
        val id: String,
        val value: JsonObject,
    )

    @Serializable
    private data class DeleteConfigurationPayload(
        val kind: String,
        val id: String,
    )

    @Serializable
    private data class McpServerPayload(val serverId: String)

    @Serializable
    private data class HookPayload(val hookId: String)

    @Serializable
    private data class PluginPayload(val pluginId: String)

    @Serializable
    private data class CreateJobPayload(
        val prompt: String,
        val role: String = "research",
        val context: String? = null,
        val expectedOutput: String? = null,
        val maxOutputTokens: Int? = null,
        val model: String? = null,
    )

    @Serializable
    private data class JobPayload(val jobId: String)

    companion object {
        private val LOG = logger<IdeBridge>()
        private const val MAX_THREAD_IMPORT_BYTES = 2_000_000L
        private const val MAX_QUEUED_MESSAGES = 10
        private const val MAX_CLIPBOARD_TEXT_LENGTH = 512_000
        private val BUILT_IN_AGENT_PROFILES = setOf("general", "search", "context", "prompt", "loop")
        private val CONFIGURATION_KINDS = listOf("mcp", "hooks", "commands", "agents", "plugins", "tool-permissions")
        private val SUBAGENT_ROLES = setOf("research", "review", "test", "security", "planner")
    }
}
