package com.codeagent.plugin.bridge

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    private var mode = "agent"
    private var thread = newThread()

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
        query.dispose()
    }

    private fun handle(raw: String) {
        val command = json.decodeFromString<CommandEnvelope>(raw)
        require(command.version == BRIDGE_PROTOCOL_VERSION) {
            "Unsupported bridge protocol ${command.version}"
        }

        when (command.type) {
            "bootstrap" -> emitSnapshot()
            "setMode" -> {
                mode = command.payload?.let { json.decodeFromJsonElement<ModePayload>(it).mode } ?: mode
                emitSnapshot()
            }
            "newThread" -> {
                messages.clear()
                thread = newThread()
                emitSnapshot()
            }
            "sendMessage" -> {
                val request = requireNotNull(command.payload) { "sendMessage requires payload" }
                    .let { json.decodeFromJsonElement<SendMessagePayload>(it) }
                messages += ChatMessageDto(
                    id = UUID.randomUUID().toString(),
                    role = "user",
                    content = request.text,
                    createdAt = System.currentTimeMillis(),
                )
                thread = thread.copy(
                    title = request.text.lineSequence().first().take(48),
                    updatedAt = System.currentTimeMillis(),
                )
                messages += ChatMessageDto(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    content = "The IDE bridge is ready. Configure a model to start the agent runtime.",
                    createdAt = System.currentTimeMillis(),
                )
                emitSnapshot()
            }
            "getContextStatus", "indexWorkspace" -> emitSnapshot()
            "saveSettings" -> emit("error", mapOf("message" to "Settings service is not connected yet"))
            "cancelRun", "resolveApproval", "selectThread", "pickContext" -> Unit
            else -> error("Unknown bridge command: ${command.type}")
        }
    }

    private fun emitSnapshot() {
        val snapshot = AppSnapshotDto(
            projectName = project.name,
            mode = mode,
            messages = messages.toList(),
            threads = listOf(thread),
        )
        emit("snapshot", json.encodeToJsonElement(snapshot))
    }

    private fun emit(type: String, payload: Any?) {
        val element = when (payload) {
            null -> null
            is kotlinx.serialization.json.JsonElement -> payload
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

    private fun newThread() = ThreadSummaryDto(
        id = UUID.randomUUID().toString(),
        title = "New task",
        updatedAt = System.currentTimeMillis(),
        active = true,
    )

    @kotlinx.serialization.Serializable
    private data class ModePayload(val mode: String)

    @kotlinx.serialization.Serializable
    private data class SendMessagePayload(val text: String, val mode: String)

    companion object {
        private val LOG = logger<IdeBridge>()
    }
}
