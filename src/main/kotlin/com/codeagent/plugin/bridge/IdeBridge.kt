package com.codeagent.plugin.bridge

import com.codeagent.plugin.agent.AgentMessage
import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.AgentRunListener
import com.codeagent.plugin.agent.AgentToolCall
import com.codeagent.plugin.agent.ChangeReviewService
import com.codeagent.plugin.agent.FileChange
import com.codeagent.plugin.agent.GitWorkspaceService
import com.codeagent.plugin.agent.ImageCanvasService
import com.codeagent.plugin.agent.WorkspaceCustomizationService
import com.codeagent.plugin.conversation.ConversationStore
import com.codeagent.plugin.context.ContextEngineService
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.CodeAgentSettingsUpdate
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.awt.datatransfer.StringSelection
import java.util.UUID

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
    private val attachments = linkedMapOf<String, ContextItemDto>()
    private val messageQueue = mutableListOf<QueuedMessageDto>()
    private val conversations = project.service<ConversationStore>()
    private val contextEngine = project.service<ContextEngineService>()
    private val agent = project.service<AgentOrchestrator>()
    private val changeReview = project.service<ChangeReviewService>()
    private val gitWorkspace = project.service<GitWorkspaceService>()
    private val imageCanvas = project.service<ImageCanvasService>()
    private val customizations = project.service<WorkspaceCustomizationService>()
    private val settingsService = service<CodeAgentSettingsService>()
    private val stateLock = Any()
    private val runs = RunGeneration()
    private var runState = "idle"

    @Volatile
    private var context = ContextSnapshotDto(state = "not_indexed", label = "Checking context")

    @Volatile
    private var backendHealth = BackendHealthDto(state = "checking", label = "Checking backend")

    @Volatile
    private var modelRegistry = ModelRegistryDto(state = "loading", label = "Loading models")

    init {
        query.addHandler { raw ->
            runCatching { handle(raw) }
                .onFailure { error ->
                    LOG.warn("Failed to handle web command", error)
                    emit("error", mapOf("message" to (error.message ?: "Bridge command failed")))
                }
            null
        }
    }

    fun injectBridgeScript(): String = """
        <script>
          window.codeAgentPost = function(payload) {
            ${query.inject("payload")}
          };
        </script>
    """.trimIndent()

    fun dispose() {
        synchronized(stateLock) { runs.invalidate() }
        agent.cancel()
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
            "cancelRun" -> {
                synchronized(stateLock) {
                    runs.invalidate()
                    runState = "idle"
                }
                agent.cancel()
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
                            if (synchronized(stateLock) {
                                val tool = tools[selection.toolId] ?: return@synchronized false
                                tools[selection.toolId] = tool.copy(
                                    summary = "Reverted ${tool.changePath}",
                                    canRevert = false,
                                )
                                true
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
                synchronized(stateLock) {
                    kept.forEach { toolId -> tools[toolId]?.let { tools[toolId] = it.copy(canRevert = false) } }
                }
                emitSnapshot()
            }
            "discardChanges" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ChangesPayload>(it) }
                changeReview.revertAll(selection.toolIds).whenComplete { reverted, error ->
                    when {
                        error != null -> emit("error", mapOf("message" to error.rootMessage()))
                        reverted.isNotEmpty() -> {
                            synchronized(stateLock) {
                                reverted.forEach { toolId ->
                                    tools[toolId]?.let { tool ->
                                        tools[toolId] = tool.copy(summary = "Reverted ${tool.changePath}", canRevert = false)
                                    }
                                }
                            }
                            emitSnapshot()
                        }
                    }
                }
            }
            "selectThread" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ThreadPayload>(it) }
                synchronized(stateLock) {
                    runs.invalidate()
                    conversations.select(selection.threadId)
                    tools.clear()
                    attachments.clear()
                    messageQueue.clear()
                    runState = "idle"
                }
                agent.cancel()
                emitSnapshot()
            }
            "toggleThreadPinned" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ThreadPayload>(it) }
                conversations.togglePinned(selection.threadId)
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
                emitSnapshot()
            }
            "setTaskState" -> {
                val update = requireNotNull(command.payload).let { json.decodeFromJsonElement<TaskStatePayload>(it) }
                conversations.updateTask(update.taskId, update.state, null)
                emitSnapshot()
            }
            "clearCompletedTasks" -> {
                conversations.clearCompletedTasks()
                emitSnapshot()
            }
            "addTask" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<TaskNamePayload>(it) }
                conversations.addTasks(listOf(request.name))
                emitSnapshot()
            }
            "deleteTask" -> {
                val request = requireNotNull(command.payload).let { json.decodeFromJsonElement<TaskPayload>(it) }
                conversations.deleteTask(request.taskId)
                emitSnapshot()
            }
            "clearTasks" -> {
                conversations.clearTasks()
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
                val active = conversations.active()
                val markdown = buildString {
                    append("# ").append(active.title).append("\n\n")
                    append("<!-- codeagent-thread mode:").append(active.mode).append(" -->\n\n")
                    active.messages.forEach { message ->
                        append("## ").append(if (message.role == "user") "User" else "CodeAgent").append("\n\n")
                        append(message.content).append("\n\n")
                    }
                }
                CopyPasteManager.getInstance().setContents(StringSelection(markdown.trim()))
            }
            "openTerminal" -> ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
            else -> error("Unknown bridge command: ${command.type}")
        }
    }

    private fun newThread(payload: JsonElement?) {
        synchronized(stateLock) {
            runs.invalidate()
            tools.clear()
            attachments.clear()
            messageQueue.clear()
            runState = "idle"
            val mode = payload?.let { json.decodeFromJsonElement<NewThreadPayload>(it).mode }
                ?: conversations.active().mode
            conversations.newThread(mode)
        }
        agent.cancel()
        emitSnapshot()
    }

    private fun sendMessage(payload: JsonElement) {
        val request = json.decodeFromJsonElement<SendMessagePayload>(payload)
        require(request.text.isNotBlank()) { "Message must not be blank" }
        val runId = synchronized(stateLock) {
            require(runState != "running" && runState != "awaiting_approval") { "Agent is already running" }
            conversations.setMode(request.mode)
            conversations.addMessage("user", request.text.trim())
            tools.clear()
            runState = "running"
            runs.next()
        }
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
        val selectedModel = synchronized(stateLock) { conversations.active().selectedModelId }
        agent.start(history, request.mode, selectedModel, enabledSkillIds, enabledRuleIds, object : AgentRunListener {
            private var streamingMessageId: String? = null

            override fun onAssistantDelta(delta: String) {
                var messageId = ""
                var firstDelta = false
                if (withCurrentRun(runId) {
                    messageId = streamingMessageId ?: conversations.addMessage("assistant", "").id.also {
                        streamingMessageId = it
                        firstDelta = true
                    }
                    conversations.appendMessage(messageId, delta)
                }) {
                    if (firstDelta) emitSnapshot() else emit("messageDelta", MessageDeltaDto(messageId, delta))
                }
            }

            override fun onAssistantMessage(content: String) {
                if (withCurrentRun(runId) {
                    val messageId = streamingMessageId
                    if (messageId == null) {
                        conversations.addMessage("assistant", content)
                    } else {
                        conversations.replaceMessage(messageId, content)
                        streamingMessageId = null
                    }
                }) emitSnapshot()
            }

            override fun onFileChanged(call: AgentToolCall, change: FileChange) {
                withCurrentRun(runId) { changeReview.register(call.id, change) }
            }

            override fun onToolChanged(
                call: AgentToolCall,
                summary: String,
                status: String,
                detail: String?,
            ) {
                if (withCurrentRun(runId) {
                    tools[call.id] = ToolRunDto(
                        id = call.id,
                        name = call.name,
                        summary = summary,
                        status = status,
                        detail = detail,
                        changePath = changeReview.path(call.id),
                        canRevert = changeReview.canRevert(call.id),
                    )
                    runState = if (status == "approval") "awaiting_approval" else "running"
                }) emitSnapshot()
            }

            override fun onRunStateChanged(state: String) {
                var startQueued = false
                if (withCurrentRun(runId) {
                    runState = state
                    startQueued = state == "idle" && messageQueue.isNotEmpty()
                }) emitSnapshot()
                if (startQueued) startNextQueuedMessage()
            }

            override fun onError(message: String) {
                withCurrentRun(runId) { emit("error", mapOf("message" to message)) }
            }
        })
    }

    private fun withCurrentRun(generation: Long, action: () -> Unit): Boolean = synchronized(stateLock) {
        if (!runs.isCurrent(generation)) return@synchronized false
        action()
        true
    }

    private fun saveSettings(payload: JsonElement) {
        val request = json.decodeFromJsonElement<SettingsPayload>(payload)
        val previousNodePath = settingsService.snapshot().nodePath
        settingsService.update(
            CodeAgentSettingsUpdate(
                backendUrl = request.backendUrl,
                nodePath = request.nodePath,
                autoApproveReadOnly = request.autoApproveReadOnly,
                backendToken = request.backendToken,
            ),
        )
        if (previousNodePath != settingsService.snapshot().nodePath) {
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
                runState = runState,
                messages = active.messages.map { message ->
                    ChatMessageDto(message.id, message.role, message.content, message.createdAt)
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
                ),
                context = context,
                backendHealth = backendHealth,
                models = modelRegistry.copy(selectedModel = active.selectedModelId ?: modelRegistry.defaultModel),
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

    private fun refreshContextStatus() {
        contextEngine.status().whenComplete { status, error ->
            context = when {
                error != null -> ContextSnapshotDto(state = "error", label = error.rootMessage())
                status.indexed -> ContextSnapshotDto(
                    state = "ready",
                    label = "${status.fileCount} files indexed",
                    files = status.fileCount,
                    chunks = status.chunkCount,
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
            emitSnapshot()
            if (backendHealth.state == "online") refreshModels()
        }
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
            }
            emitSnapshot()
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
                    attachments.clear()
                    messageQueue.clear()
                    runState = "idle"
                }
                conversations.deleteThread(threadId)
            }
            if (wasActive) agent.cancel()
            emitSnapshot()
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
                    attachments.clear()
                    messageQueue.clear()
                    runState = "idle"
                }
                agent.cancel()
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
            }.onSuccess { emitSnapshot() }
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

    @Serializable
    private data class ModePayload(val mode: String)

    @Serializable
    private data class NewThreadPayload(val mode: String)

    @Serializable
    private data class SendMessagePayload(val text: String, val mode: String)

    @Serializable
    private data class ApprovalPayload(val toolId: String, val approved: Boolean)

    @Serializable
    private data class ChangePayload(val toolId: String)

    @Serializable
    private data class ChangesPayload(val toolIds: List<String>)

    @Serializable
    private data class ThreadPayload(val threadId: String)

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
    private data class SettingsPayload(
        val backendUrl: String,
        val nodePath: String,
        val autoApproveReadOnly: Boolean = true,
        val backendToken: String? = null,
    )

    companion object {
        private val LOG = logger<IdeBridge>()
        private const val MAX_THREAD_IMPORT_BYTES = 2_000_000L
        private const val MAX_QUEUED_MESSAGES = 10
    }
}
