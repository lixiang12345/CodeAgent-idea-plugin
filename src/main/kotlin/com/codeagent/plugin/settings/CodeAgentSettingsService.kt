package com.codeagent.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.net.URI

@Service(Service.Level.APP)
@State(name = "CodeAgentSettings", storages = [Storage("CodeAgent.xml")])
class CodeAgentSettingsService : PersistentStateComponent<CodeAgentSettingsState> {
    private var settings = CodeAgentSettingsState()

    override fun getState(): CodeAgentSettingsState = settings

    override fun loadState(state: CodeAgentSettingsState) {
        settings = state
    }

    fun snapshot(): CodeAgentSettings = CodeAgentSettings(
        endpoint = settings.endpoint,
        model = settings.model,
        nodePath = settings.nodePath,
        autoApproveReadOnly = settings.autoApproveReadOnly,
        apiKey = PasswordSafe.instance.getPassword(API_KEY_ATTRIBUTES),
    )

    fun update(update: CodeAgentSettingsUpdate) {
        val endpoint = update.endpoint.trim().trimEnd('/')
        val uri = URI.create(endpoint)
        require(uri.scheme == "http" || uri.scheme == "https") { "API endpoint must use http or https" }
        require(uri.host != null) { "API endpoint must include a host" }
        require(update.model.isNotBlank()) { "Model is required" }

        settings.endpoint = endpoint
        settings.model = update.model.trim()
        settings.nodePath = update.nodePath.trim().ifEmpty { "node" }
        settings.autoApproveReadOnly = update.autoApproveReadOnly
        update.apiKey?.takeIf { it.isNotBlank() }?.let {
            PasswordSafe.instance.setPassword(API_KEY_ATTRIBUTES, it)
        }
    }

    companion object {
        private val API_KEY_ATTRIBUTES = CredentialAttributes("CodeAgent OpenAI-compatible API key")
    }
}

data class CodeAgentSettings(
    val endpoint: String,
    val model: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val apiKey: String?,
)

data class CodeAgentSettingsUpdate(
    val endpoint: String,
    val model: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val apiKey: String?,
)

class CodeAgentSettingsState {
    var endpoint: String = "https://api.openai.com/v1"
    var model: String = "gpt-5.2"
    var nodePath: String = "node"
    var autoApproveReadOnly: Boolean = true
}
