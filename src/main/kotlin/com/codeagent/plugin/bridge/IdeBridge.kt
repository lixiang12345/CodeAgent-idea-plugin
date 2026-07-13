package com.codeagent.plugin.bridge

import com.codeagent.plugin.agent.AgentMessage
import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.AgentRunListener
import com.codeagent.plugin.agent.AgentToolCall
import com.codeagent.plugin.agent.ChangeReviewService
import com.codeagent.plugin.agent.FileChange
import com.codeagent.plugin.agent.WorkspaceCustomizationService
import com.codeagent.plugin.conversation.ConversationStore
import com.codeagent.plugin.context.ContextEngineService
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.CodeAgentSettingsUpdate
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
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
    private val conversations = project.service<ConversationStore>()
    private val contextEngine = project.service<ContextEngineService>()
    private val agent = project.service<AgentOrchestrator>()
    private val changeReview = project.service<ChangeReviewService>()
    private val customizations = project.service<WorkspaceCustomizationService>()
    private val settingsService = service<CodeAgentSettingsService>()
    private val stateLock = Any()
    private val runs = RunGeneration()
    private var runState = "idle"

    @Volatile
    private var context = ContextSnapshotDto(state = "not_indexed", label = "Checking context")

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
            "newThread" -> newThread(command.payload)
            "sendMessage" -> sendMessage(requireNotNull(command.payload) { "sendMessage requires payload" })
            "getContextStatus" -> refreshContextStatus()
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
            "selectThread" -> {
                val selection = requireNotNull(command.payload).let { json.decodeFromJsonElement<ThreadPayload>(it) }
                synchronized(stateLock) {
                    runs.invalidate()
                    conversations.select(selection.threadId)
                    tools.clear()
                    attachments.clear()
                    runState = "idle"
                }
                agent.cancel()
                emitSnapshot()
            }
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
            "refreshCustomization" -> {
                val availableIds = customizations.refresh().skills.mapTo(mutableSetOf()) { it.id }
                synchronized(stateLock) {
                    conversations.setSelectedSkills(conversations.active().selectedSkillIds.filter { it in availableIds })
                }
                emitSnapshot()
            }
            else -> error("Unknown bridge command: ${command.type}")
        }
    }

    private fun newThread(payload: JsonElement?) {
        synchronized(stateLock) {
            runs.invalidate()
            tools.clear()
            attachments.clear()
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
        agent.start(history, request.mode, enabledSkillIds, object : AgentRunListener {
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
                if (withCurrentRun(runId) {
                    runState = state
                }) emitSnapshot()
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
    }

    private fun emitSnapshot() {
        val settings = settingsService.snapshot()
        val snapshot = synchronized(stateLock) {
            val active = conversations.active()
            val customization = customizations.snapshot()
            val selectedSkillIds = active.selectedSkillIds.toSet()
            AppSnapshotDto(
                projectName = project.name,
                mode = active.mode,
                runState = runState,
                messages = active.messages.map { message ->
                    ChatMessageDto(message.id, message.role, message.content, message.createdAt)
                },
                tools = tools.values.toList(),
                threads = conversations.threads().map { thread ->
                    ThreadSummaryDto(thread.id, thread.title, thread.updatedAt, thread.active, thread.mode)
                },
                attachments = attachments.values.toList(),
                settings = SettingsSnapshotDto(
                    backendUrl = settings.backendUrl,
                    nodePath = settings.nodePath,
                    backendTokenConfigured = !settings.backendToken.isNullOrBlank(),
                    autoApproveReadOnly = settings.autoApproveReadOnly,
                ),
                context = context,
                customization = WorkspaceCustomizationDto(
                    rules = customization.rules.map { rule ->
                        WorkspaceRuleDto(rule.id, rule.name, rule.path)
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
    private data class ThreadPayload(val threadId: String)

    @Serializable
    private data class ContextPayload(val id: String)

    @Serializable
    private data class SkillSelectionPayload(val skillId: String, val selected: Boolean)

    @Serializable
    private data class SettingsPayload(
        val backendUrl: String,
        val nodePath: String,
        val autoApproveReadOnly: Boolean = true,
        val backendToken: String? = null,
    )

    companion object {
        private val LOG = logger<IdeBridge>()
    }
}
