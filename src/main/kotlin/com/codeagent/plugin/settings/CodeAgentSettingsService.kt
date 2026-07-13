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
        backendUrl = settings.backendUrl,
        nodePath = settings.nodePath,
        autoApproveReadOnly = settings.autoApproveReadOnly,
        backendToken = PasswordSafe.instance.getPassword(BACKEND_TOKEN_ATTRIBUTES),
    )

    fun update(update: CodeAgentSettingsUpdate) {
        val backendUrl = update.backendUrl.trim().trimEnd('/')
        val uri = URI.create(backendUrl)
        require(uri.scheme == "http" || uri.scheme == "https") { "Backend URL must use http or https" }
        require(uri.host != null) { "Backend URL must include a host" }

        settings.backendUrl = backendUrl
        settings.nodePath = update.nodePath.trim().ifEmpty { "node" }
        settings.autoApproveReadOnly = update.autoApproveReadOnly
        update.backendToken?.takeIf { it.isNotBlank() }?.let {
            PasswordSafe.instance.setPassword(BACKEND_TOKEN_ATTRIBUTES, it)
        }
    }

    companion object {
        private val BACKEND_TOKEN_ATTRIBUTES = CredentialAttributes("CodeAgent backend authentication token")
    }
}

data class CodeAgentSettings(
    val backendUrl: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val backendToken: String?,
)

data class CodeAgentSettingsUpdate(
    val backendUrl: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val backendToken: String?,
)

class CodeAgentSettingsState {
    var backendUrl: String = "http://127.0.0.1:8787"
    var nodePath: String = "node"
    var autoApproveReadOnly: Boolean = true
}
