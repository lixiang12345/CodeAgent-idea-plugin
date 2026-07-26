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

    fun snapshot(): ByokSnapshot {
        val accessKey = PasswordSafe.instance.getPassword(AWS_ACCESS_KEY)
        val secretKey = PasswordSafe.instance.getPassword(AWS_SECRET_KEY)
        return ByokSnapshot(
            openAiConfigured = !PasswordSafe.instance.getPassword(OPENAI_KEY).isNullOrBlank(),
            anthropicConfigured = !PasswordSafe.instance.getPassword(ANTHROPIC_KEY).isNullOrBlank(),
            bedrockConfigured = hasCompleteBedrockConfig(accessKey, secretKey, state.awsRegion, state.awsModel),
            openAiBaseUrl = state.openAiBaseUrl,
            anthropicBaseUrl = state.anthropicBaseUrl,
        )
    }

    fun setOpenAi(apiKey: String, baseUrl: String = DEFAULT_OPENAI_BASE_URL) {
        PasswordSafe.instance.setPassword(OPENAI_KEY, requiredSecret(apiKey, "OpenAI API key"))
        state.openAiBaseUrl = normalizeProviderUrl(baseUrl, "OpenAI Base URL")
    }

    fun clearOpenAi() {
        PasswordSafe.instance.setPassword(OPENAI_KEY, null)
    }

    fun setAnthropic(apiKey: String, baseUrl: String = DEFAULT_ANTHROPIC_BASE_URL) {
        PasswordSafe.instance.setPassword(ANTHROPIC_KEY, requiredSecret(apiKey, "Anthropic API key"))
        state.anthropicBaseUrl = normalizeProviderUrl(baseUrl, "Anthropic Base URL")
    }

    fun clearAnthropic() {
        PasswordSafe.instance.setPassword(ANTHROPIC_KEY, null)
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
    }

    fun clearBedrock() {
        PasswordSafe.instance.setPassword(AWS_ACCESS_KEY, null)
        PasswordSafe.instance.setPassword(AWS_SECRET_KEY, null)
        PasswordSafe.instance.setPassword(AWS_SESSION_TOKEN, null)
    }

    fun requestCredentials(): ByokRequestCredentials? {
        val providers = buildList {
            PasswordSafe.instance.getPassword(OPENAI_KEY)?.takeIf(String::isNotBlank)?.let {
                add(ByokRequestCredentials.OpenAi(it, state.openAiBaseUrl))
            }
            PasswordSafe.instance.getPassword(ANTHROPIC_KEY)?.takeIf(String::isNotBlank)?.let {
                add(ByokRequestCredentials.Anthropic(it, state.anthropicBaseUrl))
            }
            val accessKey = PasswordSafe.instance.getPassword(AWS_ACCESS_KEY)
            val secretKey = PasswordSafe.instance.getPassword(AWS_SECRET_KEY)
            if (hasCompleteBedrockConfig(accessKey, secretKey, state.awsRegion, state.awsModel)) add(ByokRequestCredentials.Bedrock(
                accessKeyId = requireNotNull(accessKey),
                secretAccessKey = requireNotNull(secretKey),
                sessionToken = PasswordSafe.instance.getPassword(AWS_SESSION_TOKEN),
                region = state.awsRegion,
                model = state.awsModel,
            ))
        }
        return when (providers.size) {
            0 -> null
            1 -> providers.single()
            else -> ByokRequestCredentials.Combined(providers)
        }
    }

    private fun requiredSecret(value: String, label: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 16_384) { "$label is required" }
    }

    companion object {
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com"
        const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
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
            is Combined -> buildMap {
                put("X-CodeAgent-BYOK-Providers", providers.joinToString(",") { it.providerId() })
                providers.forEach { provider ->
                    val prefix = "X-CodeAgent-BYOK-${provider.providerId().providerHeaderName()}"
                    when (provider) {
                        is OpenAi -> {
                            put("$prefix-API-Key", provider.apiKey)
                            put("$prefix-Base-URL", normalizeProviderUrl(provider.baseUrl, "OpenAI Base URL"))
                        }
                        is Anthropic -> {
                            put("$prefix-API-Key", provider.apiKey)
                            put("$prefix-Base-URL", normalizeProviderUrl(provider.baseUrl, "Anthropic Base URL"))
                        }
                        is Bedrock -> {
                            put("$prefix-AWS-Region", provider.region)
                            put("$prefix-AWS-Access-Key-ID", provider.accessKeyId)
                            put("$prefix-AWS-Secret-Access-Key", provider.secretAccessKey)
                            provider.sessionToken?.takeIf(String::isNotBlank)?.let { put("$prefix-AWS-Session-Token", it) }
                            put("$prefix-Model", provider.model)
                        }
                        is Combined -> error("Nested BYOK credential groups are not supported")
                    }
                }
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

    data class Combined(val providers: List<ByokRequestCredentials>) : ByokRequestCredentials {
        init {
            require(providers.size >= 2) { "Combined BYOK credentials require at least two providers" }
            require(providers.none { it is Combined }) { "Nested BYOK credential groups are not supported" }
            require(providers.map { it.providerId() }.distinct().size == providers.size) {
                "Combined BYOK credentials cannot contain duplicate providers"
            }
        }
    }
}

data class ByokSnapshot(
    val openAiConfigured: Boolean = false,
    val anthropicConfigured: Boolean = false,
    val bedrockConfigured: Boolean = false,
    val openAiBaseUrl: String = ByokService.DEFAULT_OPENAI_BASE_URL,
    val anthropicBaseUrl: String = ByokService.DEFAULT_ANTHROPIC_BASE_URL,
)

class ByokState {
    var activeProvider: String = ""
    var openAiBaseUrl: String = ByokService.DEFAULT_OPENAI_BASE_URL
    var anthropicBaseUrl: String = ByokService.DEFAULT_ANTHROPIC_BASE_URL
    var awsRegion: String = "us-east-1"
    var awsModel: String = ""
}

private fun ByokRequestCredentials.providerId(): String = when (this) {
    is ByokRequestCredentials.OpenAi -> "openai"
    is ByokRequestCredentials.Anthropic -> "anthropic"
    is ByokRequestCredentials.Bedrock -> "aws-bedrock"
    is ByokRequestCredentials.Combined -> error("Combined credentials do not have a provider ID")
}

private fun String.providerHeaderName(): String = when (this) {
    "openai" -> "OpenAI"
    "anthropic" -> "Anthropic"
    "aws-bedrock" -> "AWS-Bedrock"
    else -> error("Unsupported BYOK provider: $this")
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

internal fun hasCompleteBedrockConfig(accessKey: String?, secretKey: String?, region: String, model: String): Boolean =
    !accessKey.isNullOrBlank() && !secretKey.isNullOrBlank() && region.isNotBlank() && model.isNotBlank()
