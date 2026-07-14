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
import com.codeagent.plugin.agent.RemoteContextUpdated
import com.codeagent.plugin.agent.RemoteJob
import com.codeagent.plugin.agent.RemoteJobInput
import com.codeagent.plugin.agent.RemoteJobRequest
import com.codeagent.plugin.agent.RemoteToolCatalogUpdated
import com.codeagent.plugin.agent.RemoteVerificationUpdated
import com.codeagent.plugin.agent.WorkspaceCustomizationService
import com.codeagent.plugin.conversation.ConversationStore
import com.codeagent.plugin.conversation.ConversationSnapshot
import com.codeagent.plugin.conversation.ConversationTool
import com.codeagent.plugin.conversation.ConversationSummaryService
import com.codeagent.plugin.conversation.CloudConversationSyncService
import com.codeagent.plugin.context.ContextEngineService
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.CodeAgentSettingsUpdate
import com.codeagent.plugin.settings.OidcLoginService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.awt.datatransfer.StringSelection
import java.util.UUID
import java.util.concurrent.CompletableFuture

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
    private val messageQueue = mutableListOf<QueuedMessageDto>()
    private val conversations = project.service<ConversationStore>()
    private val cloudSync = project.service<CloudConversationSyncService>()
    private val conversationSummaries = project.service<ConversationSummaryService>()
    private val contextEngine = project.service<ContextEngineService>()
    private val agent = project.service<AgentOrchestrator>()
    private val mcpRuntimeService = project.service<McpRuntimeService>()
    private val hookRuntimeService = project.service<HookRuntimeService>()
    private val changeReview = project.service<ChangeReviewService>()
    private val gitWorkspace = project.service<GitWorkspaceService>()
    private val imageCanvas = project.service<ImageCanvasService>()
    private val customizations = project.service<WorkspaceCustomizationService>()
    private val settingsService = service<CodeAgentSettingsService>()
    private val oidcLogin = service<OidcLoginService>()
    private val stateLock = Any()
    private val runs = RunGeneration()
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
    private var productJobs = ProductJobSnapshotDto()

    private var productJobsGeneration = 0L

    @Volatile
    private var account = AccountSnapshotDto()

    @Volatile
    private var syncedAccountUserId: String? = null

    init {
        restoreConversationPresentation()
        cloudSync.setChangeListener {
            restoreConversationPresentation()
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
        conversationSummaries.setChangeListener { snapshot ->
            syncConversation(snapshot)
            emitSnapshot()
        }
        query.addHandler { raw ->
            runCatching { handle(raw) }
                .onFailure { error ->
                    LOG.warn("Failed to handle web command", error)
                    emit("error", mapOf("message" to (error.message ?: "Bridge command failed")))
                }
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
        synchronized(stateLock) { runs.invalidate() }
        agent.cancel()
        cloudSync.setChangeListener(null)
        mcpRuntimeService.setChangeListener(null)
        hookRuntimeService.setChangeListener(null)
        conversationSummaries.setChangeListener(null)
        syncedAccountUserId = null
        cloudSync.reset()
        conversationSummaries.reset()
        query.dispose()
    }

    private fun handle(raw: String) {
        val command = json.decodeFromString<CommandEnvelope>(raw)
        require(command.version == BRIDGE_PROTOCOL_VERSION) {
            "Unsupported bridge protocol ${command.version}"
        }

        when (command.type) {
            "bootstrap" -> {
                emitSnapshot()
                refreshContextStatus()
                checkBackendHealth()
            }
            "setMode" -> {
                synchronized(stateLock) {
                    require(runState != "running" && runState != "awaiting_approval") {
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
                    require(runState != "running" && runState != "awaiting_approval") {
                        "Cannot change model while the agent is running"
                    }
                    require(modelRegistry.options.any { it.id == selection.modelId }) {
                        "Unknown backend model: ${selection.modelId}"
                    }
                    conversations.setSelectedModel(selection.modelId)
                }
                syncActiveConversation()
                emitSnapshot()
            }
            "selectAgentProfile" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<AgentProfileSelectionPayload>(it) }
                synchronized(stateLock) {
                    require(runState != "running" && runState != "awaiting_approval") {
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
            "sendMessage" -> sendMessage(requireNotNull(command.payload) { "sendMessage requires payload" })
            "queueMessage" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<SendMessagePayload>(it) }
                require(request.text.isNotBlank()) { "Queued message must not be blank" }
                require(request.mode == "agent" || request.mode == "chat" || request.mode == "ask") {
                    "Unsupported queued message mode: ${request.mode}"
                }
                synchronized(stateLock) {
                    require(runState == "running" || runState == "awaiting_approval") { "No active run to queue behind" }
                    require(messageQueue.size < MAX_QUEUED_MESSAGES) { "Queue can contain at most $MAX_QUEUED_MESSAGES messages" }
                    messageQueue += QueuedMessageDto(UUID.randomUUID().toString(), request.text.trim(), request.mode)
                }
                emitSnapshot()
            }
            "removeQueuedMessage" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<QueuedMessagePayload>(it) }
                synchronized(stateLock) {
                    require(messageQueue.removeIf { it.id == request.messageId }) { "Queued message no longer exists" }
                }
                emitSnapshot()
            }
            "getContextStatus" -> refreshContextStatus()
            "checkBackend" -> checkBackendHealth()
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
            "cancelRun" -> {
                synchronized(stateLock) {
                    runs.invalidate()
                    runState = "idle"
                }
                agent.cancel()
                summarizeConversation(conversations.active())
                emitSnapshot()
            }
            "resolveApproval" -> {
                val approval = requireNotNull(command.payload).let { json.decodeFromJsonElement<ApprovalPayload>(it) }
                require(agent.resolveApproval(approval.toolId, approval.approved)) { "Approval is no longer pending" }
                synchronized(stateLock) { runState = "running" }
                emitSnapshot()
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
                require(runState != "running" && runState != "awaiting_approval") { "Cannot enhance while the agent is running" }
                val mode = request.mode.ifBlank { conversations.active().mode }
                val model = conversations.active().selectedModelId
                agent.enhance(request.text.trim(), mode, model).whenComplete { result, error ->
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
                synchronized(stateLock) {
                    runs.invalidate()
                    conversations.select(selection.threadId)
                    restoreConversationPresentation()
                    agentRun = AgentRunTelemetryDto()
                    attachments.clear()
                    messageQueue.clear()
                    runState = "idle"
                }
                agent.cancel()
                summarizeConversation(conversations.active())
                emitSnapshot()
            }
            "toggleThreadPinned" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ThreadPayload>(it) }
                syncConversation(conversations.togglePinned(selection.threadId))
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
                val available = customizations.snapshot().skills
                require(available.any { it.id == selection.skillId }) { "Unknown workspace skill: ${selection.skillId}" }
                synchronized(stateLock) {
                    require(runState != "running" && runState != "awaiting_approval") {
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
                val rule = requireNotNull(customizations.snapshot().rules.firstOrNull { it.id == selection.ruleId }) {
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
                val customization = customizations.refresh()
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
                sendMessage(json.encodeToJsonElement(SendMessagePayload("Complete this task: ${task.name}", "agent")))
            }
            "runAllTasks" -> {
                val tasks = conversations.active().tasks.filter { it.state == "not_started" || it.state == "in_progress" }
                require(tasks.isNotEmpty()) { "No runnable tasks" }
                val prompt = buildString {
                    append("Complete these tasks in order, updating their task state as you work:\n")
                    tasks.forEachIndexed { index, task -> append(index + 1).append(". ").append(task.name).append('\n') }
                }
                sendMessage(json.encodeToJsonElement(SendMessagePayload(prompt.trim(), "agent")))
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
                    attachments[request.path] = ContextItemDto(request.path, Path.of(request.path).fileName.toString(), request.path)
                }
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
            "exportThread" -> exportThread()
            "renameThread" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<RenameThreadPayload>(it) }
                syncConversation(conversations.renameThread(request.threadId, request.title))
                emitSnapshot()
            }
            "openTerminal" -> ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
            else -> error("Unknown bridge command: ${command.type}")
        }
    }

    private fun newThread(payload: JsonElement?) {
        synchronized(stateLock) {
            runs.invalidate()
            tools.clear()
            messageTurns.clear()
            agentRun = AgentRunTelemetryDto()
            attachments.clear()
            messageQueue.clear()
            runState = "idle"
            val mode = payload?.let { json.decodeFromJsonElement<NewThreadPayload>(it).mode }
                ?: conversations.active().mode
            conversations.newThread(mode)
        }
        agent.cancel()
        syncActiveConversation()
        emitSnapshot()
    }

    private fun sendMessage(payload: JsonElement) {
        val request = prepareMessage(json.decodeFromJsonElement<SendMessagePayload>(payload))
        require(request.text.isNotBlank()) { "Message must not be blank" }
        require(request.mode == "agent" || request.mode == "chat" || request.mode == "ask") {
            "Unsupported message mode: ${request.mode}"
        }
        request.agentProfileId?.let { agentProfileId ->
            require(isKnownAgentProfile(agentProfileId)) { "Unknown Agent profile: $agentProfileId" }
        }
        val presentationRunId = UUID.randomUUID().toString()
        val runId = synchronized(stateLock) {
            require(runState != "running" && runState != "awaiting_approval") { "Agent is already running" }
            conversations.setMode(request.mode)
            conversations.addMessage("user", request.text.trim(), runId = presentationRunId)
            agentRun = AgentRunTelemetryDto()
            runState = "running"
            runs.next()
        }
        syncActiveConversation()
        emitSnapshot()

        val history = synchronized(stateLock) {
            val messages = conversations.active().messages
                .mapTo(mutableListOf()) { AgentMessage(role = it.role, content = it.content) }
            if (attachments.isNotEmpty()) {
                val lastUserMessage = messages.indexOfLast { it.role == "user" }
                if (lastUserMessage >= 0) {
                    val paths = attachments.values.joinToString("\n") { "- ${it.path.replace('\n', ' ')}" }
                    val message = messages[lastUserMessage]
                    messages[lastUserMessage] = message.copy(
                        content = "${message.content}\n\nUser-selected project file references:\n$paths",
                    )
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
            private fun emitRunSnapshot() = this@IdeBridge.emitSnapshot()

            override fun onContextUpdated(update: RemoteContextUpdated) {
                if (withCurrentRun(runId) {
                    agentRun = agentRun.copy(
                        turnIndex = update.turnIndex,
                        estimatedInputTokens = update.estimatedInputTokens,
                        targetInputTokens = update.targetInputTokens,
                        contextWindowTokens = update.contextWindowTokens,
                        reservedOutputTokens = update.reservedOutputTokens,
                        toolDefinitionTokens = update.toolDefinitionTokens,
                        compactedToolResults = update.compactedToolResults,
                        truncatedMessages = update.truncatedMessages,
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
                if (withCurrentRun(runId) {
                    val previous = tools[call.id]
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
                        createdAt = previous?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    )
                    tools[call.id] = tool
                    conversations.upsertTool(tool.toConversationTool())
                    runState = if (status == "approval") "awaiting_approval" else "running"
                }) emitRunSnapshot()
            }

            override fun onRunStateChanged(state: String) {
                var startQueued = false
                if (withCurrentRun(runId) {
                    runState = state
                    startQueued = state == "idle" && messageQueue.isNotEmpty()
                }) {
                    if (state == "idle") {
                        val snapshot = conversations.active()
                        syncConversation(snapshot)
                        summarizeConversation(snapshot)
                    }
                    emitRunSnapshot()
                }
                if (startQueued) startNextQueuedMessage()
            }

            override fun onError(message: String) {
                withCurrentRun(runId) { emit("error", mapOf("message" to message)) }
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
            configurations = configurations.items["commands"].orEmpty(),
        )
        return SendMessagePayload(
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
                backendToken = request.backendToken,
                contextMode = request.contextMode,
                contextEmbeddingBaseUrl = request.contextEmbeddingBaseUrl,
                contextEmbeddingModel = request.contextEmbeddingModel,
                contextEmbeddingApiKey = request.contextEmbeddingApiKey,
                contextNeuralRerank = request.contextNeuralRerank,
                contextRerankBaseUrl = request.contextRerankBaseUrl,
                contextRerankModel = request.contextRerankModel,
            ),
        )
        val current = settingsService.snapshot()
        if (previous.backendUrl != current.backendUrl || request.backendToken?.isNotBlank() == true) {
            resetCloudSync()
        }
        if (previous.nodePath != current.nodePath ||
            previous.contextMode != current.contextMode ||
            previous.contextEmbeddingBaseUrl != current.contextEmbeddingBaseUrl ||
            previous.contextEmbeddingModel != current.contextEmbeddingModel ||
            previous.contextEmbeddingApiKey != current.contextEmbeddingApiKey ||
            previous.contextNeuralRerank != current.contextNeuralRerank ||
            previous.contextRerankBaseUrl != current.contextRerankBaseUrl ||
            previous.contextRerankModel != current.contextRerankModel) {
            contextEngine.restart()
            refreshContextStatus()
        } else {
            emitSnapshot()
        }
        checkBackendHealth()
    }

    private fun emitSnapshot() {
        val settings = settingsService.snapshot()
        val snapshot = synchronized(stateLock) {
            val active = conversations.active()
            val customization = customizations.snapshot()
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
                    contextMode = settings.contextMode,
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
                models = modelRegistry.copy(selectedModel = active.selectedModelId ?: modelRegistry.defaultModel),
                backendTools = backendTools,
                configurations = configurations,
                mcpRuntime = mcpRuntime,
                hookRuntime = hookRuntime,
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
                        )
                    },
                    skills = customization.skills.map { skill ->
                        WorkspaceSkillDto(
                            id = skill.id,
                            name = skill.name,
                            description = skill.description,
                            path = skill.path,
                            selected = skill.id in selectedSkillIds,
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

    private fun refreshContextStatus() {
        contextEngine.status().whenComplete { status, error ->
            context = when {
                error != null -> ContextSnapshotDto(state = "error", label = error.rootMessage())
                status.indexed -> ContextSnapshotDto(
                    state = "ready",
                    label = buildString {
                        append("${status.fileCount} files indexed")
                        append(if (status.watching) " · Auto-sync on" else " · Manual refresh")
                        if (status.hasEmbeddings) append(" · Semantic search")
                        status.watchError?.let { append(" · Watch error: $it") }
                    },
                    files = status.fileCount,
                    chunks = status.chunkCount,
                    watching = status.watching,
                    hasEmbeddings = status.hasEmbeddings,
                    lastIndexedAt = status.lastIndexedAt,
                )
                else -> ContextSnapshotDto(state = "not_indexed", label = "Index project")
            }
            emitSnapshot()
        }
    }

    private fun checkBackendHealth() {
        backendHealth = BackendHealthDto(state = "checking", label = "Checking backend")
        emitSnapshot()
        agent.health().whenComplete { health, error ->
            backendHealth = when {
                error != null -> BackendHealthDto(state = "offline", label = error.rootMessage())
                !health.ok -> BackendHealthDto(state = "offline", label = "Backend reported unavailable")
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
            if (backendHealth.state != "online") {
                backendTools = emptyList()
                modelRegistry = ModelRegistryDto(state = "error", label = backendHealth.label)
                configurations = ConfigurationSnapshotDto(state = "error", label = backendHealth.label)
            }
            emitSnapshot()
            if (backendHealth.state == "online") refreshAccount()
        }
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
                refreshModels()
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
    )

    private fun updateTool(toolId: String, update: (ToolRunDto) -> ToolRunDto): Boolean = synchronized(stateLock) {
        val current = tools[toolId] ?: return@synchronized false
        val updated = update(current)
        tools[toolId] = updated
        conversations.upsertTool(updated.toConversationTool())
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

    private fun clearAuthenticatedCapabilities(label: String) {
        synchronized(stateLock) { productJobsGeneration += 1 }
        mcpRuntimeService.reconcile(emptyList()).exceptionally { null }
        hookRuntimeService.reconcile(emptyList())
        modelRegistry = ModelRegistryDto(state = "error", label = label)
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
                    emit("notice", mapOf("message" to "Deleted ${request.id}"))
                    refreshConfigurations()
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

    private fun startNextQueuedMessage() {
        val next = synchronized(stateLock) {
            if (runState != "idle") return
            messageQueue.removeFirstOrNull()
        } ?: return
        runCatching {
            sendMessage(json.encodeToJsonElement(SendMessagePayload(next.text, next.mode)))
        }.onFailure { emit("error", mapOf("message" to it.rootMessage())) }
    }

    private fun indexWorkspace() {
        context = ContextSnapshotDto(state = "indexing", label = "Starting index")
        emitSnapshot()
        contextEngine.index { progress ->
            val count = if (progress.filesTotal > 0) "${progress.filesDone}/${progress.filesTotal}" else progress.phase
            context = ContextSnapshotDto(state = "indexing", label = "Indexing $count")
            emitSnapshot()
        }.whenComplete { _, error ->
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
                attachments[relative] = ContextItemDto(relative, file.name, relative)
            }
            emitSnapshot()
        }
    }

    private fun refreshGit() {
        gitWorkspace.snapshot().whenComplete(::emitGitResult)
    }

    private fun deleteThread(threadId: String) {
        require(conversations.threads().any { it.id == threadId }) { "Unknown conversation: $threadId" }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val answer = Messages.showYesNoDialog(
                project,
                "Delete this CodeAgent thread? This cannot be undone.",
                "Delete CodeAgent Thread",
                "Delete",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return@invokeLater
            val wasActive = conversations.active().id == threadId
            synchronized(stateLock) {
                if (wasActive) {
                    runs.invalidate()
                    tools.clear()
                    messageTurns.clear()
                    agentRun = AgentRunTelemetryDto()
                    attachments.clear()
                    messageQueue.clear()
                    runState = "idle"
                }
                conversations.deleteThread(threadId)
                if (wasActive) restoreConversationPresentation()
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
        val eventJson = json.encodeToString(EventEnvelope(type = type, payload = element))
        val escaped = json.encodeToString(eventJson)
        browser.cefBrowser.executeJavaScript(
            "window.CodeAgent && window.CodeAgent.receive($escaped);",
            browser.cefBrowser.url,
            0,
        )
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
    private data class NewThreadPayload(val mode: String)

    @Serializable
    private data class SendMessagePayload(
        val text: String,
        val mode: String,
        val agentProfileId: String? = null,
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
    private data class ModelSelectionPayload(val modelId: String)

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
        val backendToken: String? = null,
        val contextMode: String = "lexical",
        val contextEmbeddingBaseUrl: String = "http://127.0.0.1:8000/v1",
        val contextEmbeddingModel: String = "Qwen/Qwen3-Embedding-0.6B",
        val contextEmbeddingApiKey: String? = null,
        val contextNeuralRerank: Boolean = false,
        val contextRerankBaseUrl: String = "",
        val contextRerankModel: String = "Qwen/Qwen3-Reranker-0.6B",
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
        private val BUILT_IN_AGENT_PROFILES = setOf("general", "search", "context", "prompt", "loop")
        private val CONFIGURATION_KINDS = listOf("mcp", "hooks", "commands", "agents", "plugins", "tool-permissions")
        private val SUBAGENT_ROLES = setOf("research", "review", "test", "security", "planner")
    }
}
