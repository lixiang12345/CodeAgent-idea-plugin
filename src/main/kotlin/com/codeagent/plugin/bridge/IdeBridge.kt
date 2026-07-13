package com.codeagent.plugin.bridge

import com.codeagent.plugin.agent.AgentMessage
import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.AgentRunListener
import com.codeagent.plugin.agent.AgentToolCall
import com.codeagent.plugin.context.ContextEngineService
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.CodeAgentSettingsUpdate
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
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
    private val messages = mutableListOf<ChatMessageDto>()
    private val tools = linkedMapOf<String, ToolRunDto>()
    private val contextEngine = project.service<ContextEngineService>()
    private val agent = project.service<AgentOrchestrator>()
    private val settingsService = service<CodeAgentSettingsService>()
    private val stateLock = Any()
    private var mode = "agent"
    private var runState = "idle"
    private var thread = createThread()

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
                    mode = command.payload?.let { json.decodeFromJsonElement<ModePayload>(it).mode } ?: mode
                }
                emitSnapshot()
            }
            "newThread" -> newThread()
            "sendMessage" -> sendMessage(requireNotNull(command.payload) { "sendMessage requires payload" })
            "getContextStatus" -> refreshContextStatus()
            "indexWorkspace" -> indexWorkspace()
            "saveSettings" -> saveSettings(requireNotNull(command.payload) { "saveSettings requires payload" })
            "cancelRun" -> {
                agent.cancel()
                synchronized(stateLock) { runState = "idle" }
                emitSnapshot()
            }
            "resolveApproval" -> {
                val approval = requireNotNull(command.payload).let { json.decodeFromJsonElement<ApprovalPayload>(it) }
                require(agent.resolveApproval(approval.toolId, approval.approved)) { "Approval is no longer pending" }
                synchronized(stateLock) { runState = "running" }
                emitSnapshot()
            }
            "selectThread", "pickContext" -> Unit
            else -> error("Unknown bridge command: ${command.type}")
        }
    }

    private fun newThread() {
        agent.cancel()
        synchronized(stateLock) {
            messages.clear()
            tools.clear()
            runState = "idle"
            thread = createThread()
        }
        emitSnapshot()
    }

    private fun sendMessage(payload: JsonElement) {
        val request = json.decodeFromJsonElement<SendMessagePayload>(payload)
        require(request.text.isNotBlank()) { "Message must not be blank" }
        synchronized(stateLock) {
            require(runState != "running" && runState != "awaiting_approval") { "Agent is already running" }
            mode = request.mode
            messages += ChatMessageDto(
                id = UUID.randomUUID().toString(),
                role = "user",
                content = request.text.trim(),
                createdAt = System.currentTimeMillis(),
            )
            thread = thread.copy(
                title = request.text.lineSequence().first().take(48),
                updatedAt = System.currentTimeMillis(),
            )
            tools.clear()
            runState = "running"
        }
        emitSnapshot()

        val history = synchronized(stateLock) {
            messages.map { AgentMessage(role = it.role, content = it.content) }
        }
        agent.start(history, request.mode, object : AgentRunListener {
            override fun onAssistantMessage(content: String) {
                synchronized(stateLock) {
                    messages += ChatMessageDto(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        content = content,
                        createdAt = System.currentTimeMillis(),
                    )
                    thread = thread.copy(updatedAt = System.currentTimeMillis())
                }
                emitSnapshot()
            }

            override fun onToolChanged(
                call: AgentToolCall,
                summary: String,
                status: String,
                detail: String?,
            ) {
                synchronized(stateLock) {
                    tools[call.id] = ToolRunDto(
                        id = call.id,
                        name = call.name,
                        summary = summary,
                        status = status,
                        detail = detail,
                    )
                    runState = if (status == "approval") "awaiting_approval" else "running"
                }
                emitSnapshot()
            }

            override fun onRunStateChanged(state: String) {
                synchronized(stateLock) { runState = state }
                emitSnapshot()
            }

            override fun onError(message: String) {
                emit("error", mapOf("message" to message))
            }
        })
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
            AppSnapshotDto(
                projectName = project.name,
                mode = mode,
                runState = runState,
                messages = messages.toList(),
                tools = tools.values.toList(),
                threads = listOf(thread),
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

    private fun createThread() = ThreadSummaryDto(
        id = UUID.randomUUID().toString(),
        title = "New task",
        updatedAt = System.currentTimeMillis(),
        active = true,
    )

    @Serializable
    private data class ModePayload(val mode: String)

    @Serializable
    private data class SendMessagePayload(val text: String, val mode: String)

    @Serializable
    private data class ApprovalPayload(val toolId: String, val approved: Boolean)

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
