package com.codeagent.plugin.bridge

import com.codeagent.plugin.agent.AgentMessage
import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.AgentRunListener
import com.codeagent.plugin.agent.AgentToolCall
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
            "newThread" -> newThread()
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
            else -> error("Unknown bridge command: ${command.type}")
        }
    }

    private fun newThread() {
        synchronized(stateLock) {
            runs.invalidate()
            tools.clear()
            attachments.clear()
            runState = "idle"
            conversations.newThread(conversations.active().mode)
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
            buildList {
                addAll(conversations.active().messages.map { AgentMessage(role = it.role, content = it.content) })
                if (attachments.isNotEmpty()) {
                    add(
                        AgentMessage(
                            role = "system",
                            content = "User-attached project files: ${attachments.values.joinToString { it.path }}. Read these files when relevant.",
                        ),
                    )
                }
            }
        }
        agent.start(history, request.mode, object : AgentRunListener {
            override fun onAssistantMessage(content: String) {
                if (withCurrentRun(runId) {
                    conversations.addMessage("assistant", content)
                }) emitSnapshot()
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
                endpoint = request.endpoint,
                model = request.model,
                nodePath = request.nodePath,
                autoApproveReadOnly = request.autoApproveReadOnly,
                apiKey = request.apiKey,
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
            AppSnapshotDto(
                projectName = project.name,
                mode = active.mode,
                runState = runState,
                messages = active.messages.map { message ->
                    ChatMessageDto(message.id, message.role, message.content, message.createdAt)
                },
                tools = tools.values.toList(),
                threads = conversations.threads().map { thread ->
                    ThreadSummaryDto(thread.id, thread.title, thread.updatedAt, thread.active)
                },
                attachments = attachments.values.toList(),
                settings = SettingsSnapshotDto(
                    endpoint = settings.endpoint,
                    model = settings.model,
                    nodePath = settings.nodePath,
                    apiKeyConfigured = !settings.apiKey.isNullOrBlank(),
                    autoApproveReadOnly = settings.autoApproveReadOnly,
                ),
                context = context,
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
    private data class SendMessagePayload(val text: String, val mode: String)

    @Serializable
    private data class ApprovalPayload(val toolId: String, val approved: Boolean)

    @Serializable
    private data class ThreadPayload(val threadId: String)

    @Serializable
    private data class ContextPayload(val id: String)

    @Serializable
    private data class SettingsPayload(
        val endpoint: String,
        val model: String,
        val nodePath: String,
        val autoApproveReadOnly: Boolean = true,
        val apiKey: String? = null,
    )

    companion object {
        private val LOG = logger<IdeBridge>()
    }
}
