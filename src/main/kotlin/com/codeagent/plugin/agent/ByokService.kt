package com.codeagent.plugin.agent

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.net.URI

@State(name = "CodeAgentByokSettings", storages = [Storage("CodeAgentByok.xml")])
class ByokService : PersistentStateComponent<ByokState> {
    private var state = ByokState()

    override fun getState(): ByokState = state

    override fun loadState(state: ByokState) {
        this.state = state
    }

    fun snapshot(): ByokSnapshot = ByokSnapshot(
        activeProvider = state.activeProvider.takeIf { it in PROVIDERS },
        openAiConfigured = !PasswordSafe.instance.getPassword(OPENAI_KEY).isNullOrBlank(),
        anthropicConfigured = !PasswordSafe.instance.getPassword(ANTHROPIC_KEY).isNullOrBlank(),
        bedrockConfigured = !PasswordSafe.instance.getPassword(AWS_ACCESS_KEY).isNullOrBlank() &&
            !PasswordSafe.instance.getPassword(AWS_SECRET_KEY).isNullOrBlank() &&
            state.awsRegion.isNotBlank() && state.awsModel.isNotBlank(),
    )

    fun setOpenAi(apiKey: String, baseUrl: String = DEFAULT_OPENAI_BASE_URL) {
        PasswordSafe.instance.setPassword(OPENAI_KEY, requiredSecret(apiKey, "OpenAI API key"))
        state.openAiBaseUrl = normalizeProviderUrl(baseUrl, "OpenAI Base URL")
        state.activeProvider = "openai"
    }

    fun clearOpenAi() {
        PasswordSafe.instance.setPassword(OPENAI_KEY, null)
        if (state.activeProvider == "openai") state.activeProvider = fallbackProvider()
    }

    fun setAnthropic(apiKey: String, baseUrl: String = DEFAULT_ANTHROPIC_BASE_URL) {
        PasswordSafe.instance.setPassword(ANTHROPIC_KEY, requiredSecret(apiKey, "Anthropic API key"))
        state.anthropicBaseUrl = normalizeProviderUrl(baseUrl, "Anthropic Base URL")
        state.activeProvider = "anthropic"
    }

    fun clearAnthropic() {
        PasswordSafe.instance.setPassword(ANTHROPIC_KEY, null)
        if (state.activeProvider == "anthropic") state.activeProvider = fallbackProvider()
    }

    fun setBedrock(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String?,
        region: String,
        model: String,
    ) {
        val normalizedRegion = region.trim()
        require(normalizedRegion.matches(Regex("^[a-z]{2}(?:-gov)?-[a-z]+-\\d$"))) { "AWS region is invalid" }
        val normalizedModel = model.trim()
        require(normalizedModel.isNotEmpty() && normalizedModel.length <= 1_000) { "AWS Bedrock model ID is required" }
        PasswordSafe.instance.setPassword(AWS_ACCESS_KEY, requiredSecret(accessKeyId, "AWS access key ID"))
        PasswordSafe.instance.setPassword(AWS_SECRET_KEY, requiredSecret(secretAccessKey, "AWS secret access key"))
        PasswordSafe.instance.setPassword(AWS_SESSION_TOKEN, sessionToken?.trim()?.takeIf(String::isNotEmpty))
        state.awsRegion = normalizedRegion
        state.awsModel = normalizedModel
        state.activeProvider = "aws-bedrock"
    }

    fun clearBedrock() {
        PasswordSafe.instance.setPassword(AWS_ACCESS_KEY, null)
        PasswordSafe.instance.setPassword(AWS_SECRET_KEY, null)
        PasswordSafe.instance.setPassword(AWS_SESSION_TOKEN, null)
        if (state.activeProvider == "aws-bedrock") state.activeProvider = fallbackProvider()
    }

    fun requestCredentials(): ByokRequestCredentials? = when (state.activeProvider) {
        "openai" -> PasswordSafe.instance.getPassword(OPENAI_KEY)?.takeIf(String::isNotBlank)?.let {
            ByokRequestCredentials.OpenAi(it, state.openAiBaseUrl)
        }
        "anthropic" -> PasswordSafe.instance.getPassword(ANTHROPIC_KEY)?.takeIf(String::isNotBlank)?.let {
            ByokRequestCredentials.Anthropic(it, state.anthropicBaseUrl)
        }
        "aws-bedrock" -> {
            val accessKey = PasswordSafe.instance.getPassword(AWS_ACCESS_KEY)
            val secretKey = PasswordSafe.instance.getPassword(AWS_SECRET_KEY)
            if (accessKey.isNullOrBlank() || secretKey.isNullOrBlank()) null else ByokRequestCredentials.Bedrock(
                accessKeyId = accessKey,
                secretAccessKey = secretKey,
                sessionToken = PasswordSafe.instance.getPassword(AWS_SESSION_TOKEN),
                region = state.awsRegion,
                model = state.awsModel,
            )
        }
        else -> null
    }

    private fun fallbackProvider(): String = when {
        !PasswordSafe.instance.getPassword(OPENAI_KEY).isNullOrBlank() -> "openai"
        !PasswordSafe.instance.getPassword(ANTHROPIC_KEY).isNullOrBlank() -> "anthropic"
        !PasswordSafe.instance.getPassword(AWS_ACCESS_KEY).isNullOrBlank() &&
            !PasswordSafe.instance.getPassword(AWS_SECRET_KEY).isNullOrBlank() -> "aws-bedrock"
        else -> ""
    }

    private fun requiredSecret(value: String, label: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 16_384) { "$label is required" }
    }

    companion object {
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com"
        const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
        private val PROVIDERS = setOf("openai", "anthropic", "aws-bedrock")
        private val OPENAI_KEY = CredentialAttributes("CodeAgent BYOK OpenAI API key")
        private val ANTHROPIC_KEY = CredentialAttributes("CodeAgent BYOK Anthropic API key")
        private val AWS_ACCESS_KEY = CredentialAttributes("CodeAgent BYOK AWS access key ID")
        private val AWS_SECRET_KEY = CredentialAttributes("CodeAgent BYOK AWS secret access key")
        private val AWS_SESSION_TOKEN = CredentialAttributes("CodeAgent BYOK AWS session token")
    }
}

sealed interface ByokRequestCredentials {
    fun headersFor(backendUrl: String): Map<String, String> {
        requireSecureBackend(backendUrl)
        return when (this) {
            is OpenAi -> mapOf(
                "X-CodeAgent-BYOK-Provider" to "openai",
                "X-CodeAgent-BYOK-API-Key" to apiKey,
                "X-CodeAgent-BYOK-Base-URL" to normalizeProviderUrl(baseUrl, "OpenAI Base URL"),
            )
            is Anthropic -> mapOf(
                "X-CodeAgent-BYOK-Provider" to "anthropic",
                "X-CodeAgent-BYOK-API-Key" to apiKey,
                "X-CodeAgent-BYOK-Base-URL" to normalizeProviderUrl(baseUrl, "Anthropic Base URL"),
            )
            is Bedrock -> buildMap {
                put("X-CodeAgent-BYOK-Provider", "aws-bedrock")
                put("X-CodeAgent-BYOK-AWS-Region", region)
                put("X-CodeAgent-BYOK-AWS-Access-Key-ID", accessKeyId)
                put("X-CodeAgent-BYOK-AWS-Secret-Access-Key", secretAccessKey)
                sessionToken?.takeIf(String::isNotBlank)?.let { put("X-CodeAgent-BYOK-AWS-Session-Token", it) }
                put("X-CodeAgent-BYOK-Model", model)
            }
        }
    }

    data class OpenAi(val apiKey: String, val baseUrl: String = ByokService.DEFAULT_OPENAI_BASE_URL) : ByokRequestCredentials
    data class Anthropic(val apiKey: String, val baseUrl: String = ByokService.DEFAULT_ANTHROPIC_BASE_URL) : ByokRequestCredentials
    data class Bedrock(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String?,
        val region: String,
        val model: String,
    ) : ByokRequestCredentials
}

data class ByokSnapshot(
    val activeProvider: String? = null,
    val openAiConfigured: Boolean = false,
    val anthropicConfigured: Boolean = false,
    val bedrockConfigured: Boolean = false,
)

class ByokState {
    var activeProvider: String = ""
    var openAiBaseUrl: String = ByokService.DEFAULT_OPENAI_BASE_URL
    var anthropicBaseUrl: String = ByokService.DEFAULT_ANTHROPIC_BASE_URL
    var awsRegion: String = "us-east-1"
    var awsModel: String = ""
}

internal fun requireSecureBackend(value: String) {
    val uri = URI.create(value.trim())
    val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
    require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
        "BYOK credentials may be sent only to HTTPS or a loopback HTTP backend"
    }
    require(uri.userInfo == null && uri.host != null) { "Backend URL is invalid" }
}

internal fun normalizeProviderUrl(value: String, label: String): String {
    val uri = URI.create(value.trim().trimEnd('/'))
    val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
    require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) { "$label must use HTTPS unless it targets loopback" }
    require(uri.userInfo == null && uri.host != null && uri.query == null && uri.fragment == null) { "$label is invalid" }
    return uri.toString().trimEnd('/')
}
