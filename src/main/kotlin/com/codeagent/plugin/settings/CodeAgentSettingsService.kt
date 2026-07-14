package com.codeagent.plugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.net.URI

const val DEFAULT_BACKEND_URL = "http://127.0.0.1:8788"
const val DEFAULT_CONTEXT_EMBEDDING_URL = "http://127.0.0.1:8000/v1"
const val DEFAULT_CONTEXT_EMBEDDING_MODEL = "Qwen/Qwen3-Embedding-0.6B"
const val DEFAULT_CONTEXT_RERANK_MODEL = "Qwen/Qwen3-Reranker-0.6B"

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
        chatZoom = settings.chatZoom,
        showTimestamps = settings.showTimestamps,
        showRunTelemetry = settings.showRunTelemetry,
        desktopNotifications = settings.desktopNotifications,
        autoDismissNotifications = settings.autoDismissNotifications,
        backendToken = PasswordSafe.instance.getPassword(BACKEND_TOKEN_ATTRIBUTES),
        refreshToken = PasswordSafe.instance.getPassword(REFRESH_TOKEN_ATTRIBUTES),
        tokenExpiresAtEpochSeconds = settings.tokenExpiresAtEpochSeconds,
        contextMode = settings.contextMode,
        contextEmbeddingBaseUrl = settings.contextEmbeddingBaseUrl,
        contextEmbeddingModel = settings.contextEmbeddingModel,
        contextEmbeddingApiKey = PasswordSafe.instance.getPassword(CONTEXT_EMBEDDING_TOKEN_ATTRIBUTES),
        contextNeuralRerank = settings.contextNeuralRerank,
        contextRerankBaseUrl = settings.contextRerankBaseUrl,
        contextRerankModel = settings.contextRerankModel,
    )

    fun update(update: CodeAgentSettingsUpdate) {
        val backendUrl = normalizeHttpUrl(update.backendUrl, "Backend URL", allowRemoteHttp = true)
        val contextMode = update.contextMode.trim()
        require(contextMode in setOf("lexical", "private-semantic")) { "Unsupported Context Engine mode" }
        val contextEmbeddingBaseUrl = normalizeHttpUrl(
            update.contextEmbeddingBaseUrl.trim().ifEmpty { DEFAULT_CONTEXT_EMBEDDING_URL },
            "Context embedding URL",
            allowRemoteHttp = false,
        )
        val contextRerankBaseUrl = update.contextRerankBaseUrl.trim().takeIf { it.isNotEmpty() }
            ?.let { normalizeHttpUrl(it, "Context rerank URL", allowRemoteHttp = false) }
            .orEmpty()
        val contextEmbeddingModel = update.contextEmbeddingModel.trim().ifEmpty { DEFAULT_CONTEXT_EMBEDDING_MODEL }
        val contextRerankModel = update.contextRerankModel.trim().ifEmpty { DEFAULT_CONTEXT_RERANK_MODEL }
        require(contextEmbeddingModel.isNotEmpty() && contextEmbeddingModel.length <= 240) {
            "Context embedding model is required and must be at most 240 characters"
        }
        require(contextRerankModel.isNotEmpty() && contextRerankModel.length <= 240) {
            "Context rerank model is required and must be at most 240 characters"
        }
        require(update.chatZoom in 85..140) { "Chat zoom must be between 85 and 140" }

        settings.backendUrl = backendUrl
        settings.nodePath = update.nodePath.trim().ifEmpty { "node" }
        settings.autoApproveReadOnly = update.autoApproveReadOnly
        settings.chatZoom = update.chatZoom
        settings.showTimestamps = update.showTimestamps
        settings.showRunTelemetry = update.showRunTelemetry
        settings.desktopNotifications = update.desktopNotifications
        settings.autoDismissNotifications = update.autoDismissNotifications
        settings.contextMode = contextMode
        settings.contextEmbeddingBaseUrl = contextEmbeddingBaseUrl
        settings.contextEmbeddingModel = contextEmbeddingModel
        settings.contextNeuralRerank = update.contextNeuralRerank
        settings.contextRerankBaseUrl = contextRerankBaseUrl
        settings.contextRerankModel = contextRerankModel
        update.backendToken?.takeIf { it.isNotBlank() }?.let {
            PasswordSafe.instance.setPassword(BACKEND_TOKEN_ATTRIBUTES, it)
        }
        update.contextEmbeddingApiKey?.takeIf { it.isNotBlank() }?.let {
            PasswordSafe.instance.setPassword(CONTEXT_EMBEDDING_TOKEN_ATTRIBUTES, it)
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

    private fun normalizeHttpUrl(value: String, label: String, allowRemoteHttp: Boolean): String {
        val normalized = value.trim().trimEnd('/')
        val uri = URI.create(normalized)
        require(uri.scheme == "http" || uri.scheme == "https") { "$label must use http or https" }
        require(uri.host != null) { "$label must include a host" }
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
        require(uri.scheme == "https" || allowRemoteHttp || loopback) {
            "$label must use https unless it targets the local machine"
        }
        require(uri.userInfo == null) { "$label must not contain credentials" }
        return normalized
    }

    companion object {
        private val BACKEND_TOKEN_ATTRIBUTES = CredentialAttributes("CodeAgent backend authentication token")
        private val REFRESH_TOKEN_ATTRIBUTES = CredentialAttributes("CodeAgent OIDC refresh token")
        private val CONTEXT_EMBEDDING_TOKEN_ATTRIBUTES = CredentialAttributes("CodeAgent Context Engine embedding token")
    }
}

data class CodeAgentSettings(
    val backendUrl: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val chatZoom: Int = 100,
    val showTimestamps: Boolean = true,
    val showRunTelemetry: Boolean = true,
    val desktopNotifications: Boolean = false,
    val autoDismissNotifications: Boolean = true,
    val backendToken: String?,
    val refreshToken: String? = null,
    val tokenExpiresAtEpochSeconds: Long = 0,
    val contextMode: String = "lexical",
    val contextEmbeddingBaseUrl: String = DEFAULT_CONTEXT_EMBEDDING_URL,
    val contextEmbeddingModel: String = DEFAULT_CONTEXT_EMBEDDING_MODEL,
    val contextEmbeddingApiKey: String? = null,
    val contextNeuralRerank: Boolean = false,
    val contextRerankBaseUrl: String = "",
    val contextRerankModel: String = DEFAULT_CONTEXT_RERANK_MODEL,
)

data class CodeAgentSettingsUpdate(
    val backendUrl: String,
    val nodePath: String,
    val autoApproveReadOnly: Boolean,
    val chatZoom: Int = 100,
    val showTimestamps: Boolean = true,
    val showRunTelemetry: Boolean = true,
    val desktopNotifications: Boolean = false,
    val autoDismissNotifications: Boolean = true,
    val backendToken: String?,
    val contextMode: String = "lexical",
    val contextEmbeddingBaseUrl: String = DEFAULT_CONTEXT_EMBEDDING_URL,
    val contextEmbeddingModel: String = DEFAULT_CONTEXT_EMBEDDING_MODEL,
    val contextEmbeddingApiKey: String? = null,
    val contextNeuralRerank: Boolean = false,
    val contextRerankBaseUrl: String = "",
    val contextRerankModel: String = DEFAULT_CONTEXT_RERANK_MODEL,
)

class CodeAgentSettingsState {
    var backendUrl: String = DEFAULT_BACKEND_URL
    var nodePath: String = "node"
    var autoApproveReadOnly: Boolean = true
    var chatZoom: Int = 100
    var showTimestamps: Boolean = true
    var showRunTelemetry: Boolean = true
    var desktopNotifications: Boolean = false
    var autoDismissNotifications: Boolean = true
    var tokenExpiresAtEpochSeconds: Long = 0
    var contextMode: String = "lexical"
    var contextEmbeddingBaseUrl: String = DEFAULT_CONTEXT_EMBEDDING_URL
    var contextEmbeddingModel: String = DEFAULT_CONTEXT_EMBEDDING_MODEL
    var contextNeuralRerank: Boolean = false
    var contextRerankBaseUrl: String = ""
    var contextRerankModel: String = DEFAULT_CONTEXT_RERANK_MODEL
}
