package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.BrowserUtil
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.ide.BuiltInServerManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class McpOAuthService {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val pending = ConcurrentHashMap<String, PendingAuthorization>()

    fun authorize(configuration: McpOAuthConfiguration): CompletableFuture<Unit> {
        val builtInServer = BuiltInServerManager.getInstance().waitForStart()
        val redirectUri = "http://127.0.0.1:${builtInServer.port}$CALLBACK_PATH"
        val state = randomUrlToken(32)
        val verifier = randomUrlToken(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val result = CompletableFuture<Unit>()
        pending[state] = PendingAuthorization(configuration, verifier, redirectUri, result)

        val metadata = if (configuration.resourceUrl != null) {
            McpOAuthMetadataDiscovery.discover(configuration.resourceUrl, configuration.tokenEndpoint)
        } else {
            CompletableFuture.completedFuture(
                McpOAuthMetadataDiscovery.DiscoveredMetadata(
                    authorizationEndpoint = configuration.authorizationEndpoint,
                    tokenEndpoint = configuration.tokenEndpoint,
                    registrationEndpoint = null,
                    supportedScopes = emptyList(),
                    supportedCodeChallengeMethods = emptyList(),
                ),
            )
        }

        metadata.thenCompose { discovered ->
            val authzEndpoint = discovered.authorizationEndpoint
                ?: return@thenCompose CompletableFuture.failedFuture<String>(
                    IllegalStateException("MCP OAuth authorization endpoint was not discovered and is not configured"),
                )
            val tokenEndpoint = discovered.tokenEndpoint
                ?: return@thenCompose CompletableFuture.failedFuture<String>(
                    IllegalStateException("MCP OAuth token endpoint was not discovered and is not configured"),
                )
            secureEndpoint(authzEndpoint, "authorizationEndpoint")
            secureEndpoint(tokenEndpoint, "tokenEndpoint")
            service<CodeAgentSettingsService>().setMcpDiscoveredTokenEndpoint(configuration.id, tokenEndpoint)

            val clientId = resolveClientId(configuration, discovered, redirectUri)
            clientId.thenApply { cid ->
                val query = linkedMapOf(
                    "response_type" to "code",
                    "client_id" to cid,
                    "redirect_uri" to redirectUri,
                    "state" to state,
                    "code_challenge" to challenge,
                    "code_challenge_method" to "S256",
                )
                if (configuration.scopes.isNotEmpty()) query["scope"] = configuration.scopes.joinToString(" ")
                configuration.audience?.takeIf(String::isNotBlank)?.let { query["audience"] = it }
                withQuery(URI.create(authzEndpoint), query).toString()
            }
        }.whenComplete { authUrl, error ->
            if (error != null) {
                pending.remove(state)
                result.completeExceptionally(error)
            } else {
                BrowserUtil.browse(authUrl)
                scheduler.schedule({
                    pending.remove(state)?.result?.completeExceptionally(
                        IllegalStateException("MCP OAuth authorization timed out"),
                    )
                }, AUTHORIZATION_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            }
        }
        return result
    }

    private fun resolveClientId(
        configuration: McpOAuthConfiguration,
        discovered: McpOAuthMetadataDiscovery.DiscoveredMetadata,
        redirectUri: String,
    ): CompletableFuture<String> {
        if (configuration.clientId.isNotBlank()) return CompletableFuture.completedFuture(configuration.clientId)
        val existing = service<CodeAgentSettingsService>().mcpDynamicClientId(configuration.id)
        if (existing != null) return CompletableFuture.completedFuture(existing)
        val registrationEndpoint = discovered.registrationEndpoint
            ?: return CompletableFuture.failedFuture(
                IllegalStateException("MCP OAuth client ID is not configured and dynamic registration is unavailable"),
            )
        return McpDynamicClientRegistration.register(configuration.id, registrationEndpoint, redirectUri)
            .thenApply { service<CodeAgentSettingsService>().mcpDynamicClientId(configuration.id)!! }
    }

    fun completeCallback(state: String?, code: String?, error: String?): Boolean {
        val authorization = state?.let(pending::remove) ?: return false
        if (!error.isNullOrBlank()) {
            authorization.result.completeExceptionally(IllegalStateException("MCP OAuth authorization failed: $error"))
            return true
        }
        if (code.isNullOrBlank()) {
            authorization.result.completeExceptionally(IllegalStateException("MCP OAuth callback contains no code"))
            return true
        }
        val tokenEndpoint = service<CodeAgentSettingsService>().mcpDiscoveredTokenEndpoint(authorization.configuration.id)
            ?: authorization.configuration.tokenEndpoint
            ?: run {
                authorization.result.completeExceptionally(
                    IllegalStateException("MCP OAuth token endpoint was not discovered and is not configured"),
                )
                return true
            }
        val clientId = authorization.configuration.clientId.takeIf(String::isNotBlank)
            ?: service<CodeAgentSettingsService>().mcpDynamicClientId(authorization.configuration.id)
            ?: run {
                authorization.result.completeExceptionally(IllegalStateException("MCP OAuth client ID is unavailable"))
                return true
            }
        CompletableFuture.supplyAsync({
            val fields = linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "redirect_uri" to authorization.redirectUri,
                "code_verifier" to authorization.verifier,
            )
            val clientSecret = PasswordSafe.instance.getPassword(
                McpDynamicClientRegistration.dynamicClientSecretAttributes(authorization.configuration.id),
            )
            if (!clientSecret.isNullOrBlank()) fields["client_secret"] = clientSecret
            exchange(authorization.configuration.id, tokenEndpoint, fields)
        }, executor).whenComplete { tokens, exchangeError ->
            if (exchangeError != null) authorization.result.completeExceptionally(exchangeError)
            else {
                persist(authorization.configuration.id, tokens)
                authorization.result.complete(Unit)
            }
        }
        return true
    }

    fun ensureAccessToken(configuration: McpOAuthConfiguration): CompletableFuture<String?> {
        val accessToken = PasswordSafe.instance.getPassword(accessAttributes(configuration.id))
        if (!accessToken.isNullOrBlank()) return CompletableFuture.completedFuture(accessToken)
        val refreshToken = PasswordSafe.instance.getPassword(refreshAttributes(configuration.id))
            ?: return CompletableFuture.completedFuture(null)
        val tokenEndpoint = service<CodeAgentSettingsService>().mcpDiscoveredTokenEndpoint(configuration.id)
            ?: configuration.tokenEndpoint
            ?: return CompletableFuture.completedFuture(null)
        val clientId = configuration.clientId.takeIf(String::isNotBlank)
            ?: service<CodeAgentSettingsService>().mcpDynamicClientId(configuration.id)
            ?: return CompletableFuture.completedFuture(null)
        return CompletableFuture.supplyAsync({
            val fields = linkedMapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
            )
            val clientSecret = PasswordSafe.instance.getPassword(
                McpDynamicClientRegistration.dynamicClientSecretAttributes(configuration.id),
            )
            if (!clientSecret.isNullOrBlank()) fields["client_secret"] = clientSecret
            val tokens = exchange(configuration.id, tokenEndpoint, fields)
            persist(configuration.id, tokens.copy(refreshToken = tokens.refreshToken ?: refreshToken))
            tokens.accessToken
        }, executor)
    }

    fun clear(serverId: String) {
        PasswordSafe.instance.setPassword(accessAttributes(serverId), null)
        PasswordSafe.instance.setPassword(refreshAttributes(serverId), null)
    }

    private fun exchange(serverId: String, tokenEndpoint: String, fields: Map<String, String>): McpOAuthTokens {
        val uri = secureEndpoint(tokenEndpoint, "tokenEndpoint")
        val body = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val response = http.send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "MCP OAuth token exchange returned HTTP ${response.statusCode()}" }
        return json.decodeFromString<McpOAuthTokens>(response.body()).also {
            require(it.accessToken.isNotBlank()) { "MCP OAuth provider returned an empty access token" }
            require(it.tokenType.equals("Bearer", ignoreCase = true)) { "MCP OAuth provider returned an unsupported token type" }
        }
    }

    private fun persist(serverId: String, tokens: McpOAuthTokens) {
        PasswordSafe.instance.setPassword(accessAttributes(serverId), tokens.accessToken)
        tokens.refreshToken?.takeIf(String::isNotBlank)?.let {
            PasswordSafe.instance.setPassword(refreshAttributes(serverId), it)
        }
    }

    private fun secureEndpoint(value: String, field: String): URI {
        val uri = URI.create(value.trim())
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "MCP OAuth $field must use HTTPS unless it targets the local machine"
        }
        require(uri.userInfo == null && uri.host != null) { "MCP OAuth $field is invalid" }
        return uri
    }

    private fun withQuery(endpoint: URI, values: Map<String, String>): URI {
        val separator = if (endpoint.rawQuery.isNullOrEmpty()) "?" else "&"
        return URI.create(endpoint.toString() + separator + values.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" })
    }

    private data class PendingAuthorization(
        val configuration: McpOAuthConfiguration,
        val verifier: String,
        val redirectUri: String,
        val result: CompletableFuture<Unit>,
    )

    private companion object {
        const val CALLBACK_PATH = "/codeagent/mcp/oauth/callback"
        const val AUTHORIZATION_TIMEOUT_MINUTES = 5L
        val random = SecureRandom()

        fun accessAttributes(serverId: String) = CredentialAttributes("CodeAgent MCP OAuth access token", serverId)
        fun refreshAttributes(serverId: String) = CredentialAttributes("CodeAgent MCP OAuth refresh token", serverId)
        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
        fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)
        fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}

data class McpOAuthConfiguration(
    val id: String,
    val resourceUrl: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val clientId: String = "",
    val scopes: List<String> = emptyList(),
    val audience: String? = null,
)

@Serializable
private data class McpOAuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long = 3_600,
)

