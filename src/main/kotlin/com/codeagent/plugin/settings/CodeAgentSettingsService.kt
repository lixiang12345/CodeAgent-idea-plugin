package com.codeagent.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.net.URI

const val DEFAULT_BACKEND_URL = "http://127.0.0.1:8788"

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
        refreshToken = PasswordSafe.instance.getPassword(REFRESH_TOKEN_ATTRIBUTES),
        tokenExpiresAtEpochSeconds = settings.tokenExpiresAtEpochSeconds,
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

    fun updateAuthTokens(accessToken: String, refreshToken: String?, expiresAtEpochSeconds: Long) {
        require(accessToken.isNotBlank()) { "Access token is required" }
        PasswordSafe.instance.setPassword(BACKEND_TOKEN_ATTRIBUTES, accessToken)
        PasswordSafe.instance.setPassword(REFRESH_TOKEN_ATTRIBUTES, refreshToken)
        settings.tokenExpiresAtEpochSeconds = expiresAtEpochSeconds
    }

    fun clearAuthTokens() {
        PasswordSafe.instance.setPassword(BACKEND_TOKEN_ATTRIBUTES, null)
        PasswordSafe.instance.setPassword(REFRESH_TOKEN_ATTRIBUTES, null)
        settings.tokenExpiresAtEpochSeconds = 0
    }

    companion object {
        private val BACKEND_TOKEN_ATTRIBUTES = CredentialAttributes("CodeAgent backend authentication token")
        private val REFRESH_TOKEN_ATTRIBUTES = CredentialAttributes("CodeAgent OIDC refresh token")
    }
}

data class CodeAgentSettings(
    val backendUrl: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val backendToken: String?,
    val refreshToken: String? = null,
    val tokenExpiresAtEpochSeconds: Long = 0,
)

data class CodeAgentSettingsUpdate(
    val backendUrl: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val backendToken: String?,
)

class CodeAgentSettingsState {
    var backendUrl: String = DEFAULT_BACKEND_URL
    var nodePath: String = "node"
    var autoApproveReadOnly: Boolean = true
    var tokenExpiresAtEpochSeconds: Long = 0
}
