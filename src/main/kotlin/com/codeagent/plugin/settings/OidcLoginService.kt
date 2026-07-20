package com.codeagent.plugin.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.jetbrains.ide.BuiltInServerManager

class OidcLoginService {
    private val settings = service<CodeAgentSettingsService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val pending = ConcurrentHashMap<String, CompletableFuture<String>>()

    fun authConfig(): CompletableFuture<OidcAuthConfig> = CompletableFuture.supplyAsync(::fetchAuthConfig, executor)

    fun signIn(): CompletableFuture<Unit> = CompletableFuture.supplyAsync({
        val config = fetchAuthConfig()
        require(config.mode == "oidc") { "Backend authentication mode is ${config.mode}; OIDC sign-in is unavailable" }
        val authorizationEndpoint = secureEndpoint(requireNotNull(config.authorizationEndpoint) { "OIDC authorization endpoint is missing" })
        val tokenEndpoint = secureEndpoint(requireNotNull(config.tokenEndpoint) { "OIDC token endpoint is missing" })
        val clientId = requireNotNull(config.clientId) { "OIDC client ID is missing" }
        val verifier = randomUrlToken(48)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val state = randomUrlToken(32)
        val builtInServer = BuiltInServerManager.getInstance().waitForStart()
        val redirectUri = "http://127.0.0.1:${builtInServer.port}$CALLBACK_PATH"
        val authorizationCode = CompletableFuture<String>()
        pending[state] = authorizationCode
        try {
            val query = linkedMapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "scope" to config.scopes.joinToString(" "),
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
            )
            config.audience?.takeIf(String::isNotBlank)?.let { query["audience"] = it }
            BrowserUtil.browse(withQuery(authorizationEndpoint, query))
            val code = authorizationCode.orTimeout(5, TimeUnit.MINUTES).join()
            val tokens = exchangeToken(
                tokenEndpoint = tokenEndpoint,
                fields = linkedMapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to verifier,
                ),
            )
            persist(tokens)
        } finally {
            pending.remove(state, authorizationCode)
        }
    }, executor)

    fun completeCallback(state: String?, code: String?, error: String?): Boolean {
        val pendingCode = state?.let(pending::remove) ?: return false
        when {
            !error.isNullOrBlank() -> pendingCode.completeExceptionally(IllegalStateException("OIDC authorization failed: $error"))
            code.isNullOrBlank() -> pendingCode.completeExceptionally(IllegalStateException("OIDC callback contains no authorization code"))
            else -> pendingCode.complete(code)
        }
        return true
    }

    fun ensureFreshToken(): CompletableFuture<Unit> {
        val current = settings.snapshot()
        if (current.backendToken.isNullOrBlank()) return CompletableFuture.completedFuture(Unit)
        if (current.tokenExpiresAtEpochSeconds == 0L || current.tokenExpiresAtEpochSeconds > Instant.now().epochSecond + 60) {
            return CompletableFuture.completedFuture(Unit)
        }
        val refreshToken = current.refreshToken
            ?: return CompletableFuture.failedFuture(IllegalStateException("CodeAgent session expired; sign in again"))
        return CompletableFuture.supplyAsync({
            val config = fetchAuthConfig()
            require(config.mode == "oidc") { "Backend no longer advertises OIDC authentication" }
            val tokens = exchangeToken(
                secureEndpoint(requireNotNull(config.tokenEndpoint) { "OIDC token endpoint is missing" }),
                linkedMapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to requireNotNull(config.clientId) { "OIDC client ID is missing" },
                    "refresh_token" to refreshToken,
                ),
            )
            persist(tokens.copy(refreshToken = tokens.refreshToken ?: refreshToken))
        }, executor)
    }

    fun signOut(): CompletableFuture<Unit> = ensureFreshToken().handle { _, _ -> Unit }.thenCompose {
        CompletableFuture.supplyAsync({
            val current = settings.snapshot()
            try {
                current.backendToken?.takeIf(String::isNotBlank)?.let { token ->
                    val uri = URI.create("${current.backendUrl.trimEnd('/')}/v1/auth/logout")
                    val response = http.send(
                        HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(15))
                            .header("Authorization", "Bearer $token")
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )
                    check(response.statusCode() == 204 || response.statusCode() == 401) {
                        "Backend sign-out returned HTTP ${response.statusCode()}"
                    }
                }
            } finally {
                settings.clearAuthTokens()
            }
            Unit
        }, executor)
    }




    private fun fetchAuthConfig(): OidcAuthConfig {
        val uri = URI.create("${settings.snapshot().backendUrl.trimEnd('/')}/v1/auth/config")
        val response = http.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() == 200) { "Backend auth discovery returned HTTP ${response.statusCode()}" }
        return json.decodeFromString(response.body())
    }

    private fun exchangeToken(tokenEndpoint: URI, fields: Map<String, String>): OidcTokenResponse {
        val body = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val response = http.send(
            HttpRequest.newBuilder(tokenEndpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "OIDC token exchange returned HTTP ${response.statusCode()}" }
        return json.decodeFromString(response.body())
    }

    private fun persist(tokens: OidcTokenResponse) {
        require(tokens.accessToken.isNotBlank()) { "OIDC provider returned an empty access token" }
        val expiresAt = Instant.now().epochSecond + tokens.expiresIn.coerceAtLeast(60) - 30
        settings.updateAuthTokens(tokens.accessToken, tokens.refreshToken, expiresAt)
    }

    companion object {
        const val CALLBACK_PATH = "/codeagent/oauth/callback"
        private val random = SecureRandom()

        private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)
        private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
        private fun withQuery(endpoint: URI, values: Map<String, String>): URI = URI.create(
            endpoint.toString() + (if (endpoint.query == null) "?" else "&") + values.entries.joinToString("&") {
                "${encode(it.key)}=${encode(it.value)}"
            },
        )

        private fun secureEndpoint(value: String): URI {
            val uri = URI.create(value)
            require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost"))) {
                "OIDC endpoints must use HTTPS"
            }
            return uri
        }

    }
}

@Serializable
data class OidcAuthConfig(
    val mode: String,
    val issuer: String? = null,
    val clientId: String? = null,
    val audience: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val endSessionEndpoint: String? = null,
    val scopes: List<String> = listOf("openid", "profile", "email"),
)

@Serializable
data class OidcTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3_600,
)
