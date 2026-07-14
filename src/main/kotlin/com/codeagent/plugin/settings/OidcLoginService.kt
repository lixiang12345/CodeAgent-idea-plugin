package com.codeagent.plugin.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
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
import java.util.concurrent.TimeUnit

@Service(Service.Level.APP)
class OidcLoginService {
    private val settings = service<CodeAgentSettingsService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val executor = AppExecutorUtil.getAppExecutorService()

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
        val callback = LoopbackCallback.start(state)
        try {
            val query = linkedMapOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to callback.redirectUri,
                "scope" to config.scopes.joinToString(" "),
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
            )
            config.audience?.takeIf(String::isNotBlank)?.let { query["audience"] = it }
            BrowserUtil.browse(withQuery(authorizationEndpoint, query))
            val code = callback.code.orTimeout(5, TimeUnit.MINUTES).join()
            val tokens = exchangeToken(
                tokenEndpoint = tokenEndpoint,
                fields = linkedMapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to callback.redirectUri,
                    "code_verifier" to verifier,
                ),
            )
            persist(tokens)
        } finally {
            callback.close()
        }
    }, executor)

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

    private data class LoopbackCallback(
        val server: HttpServer,
        val state: String,
        val code: CompletableFuture<String>,
    ) : AutoCloseable {
        val redirectUri: String = "http://127.0.0.1:${server.address.port}/callback"

        override fun close() {
            server.stop(0)
        }

        companion object {
            fun start(state: String): LoopbackCallback {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val code = CompletableFuture<String>()
                val callback = LoopbackCallback(server, state, code)
                server.createContext("/callback") { exchange -> callback.handle(exchange) }
                server.executor = AppExecutorUtil.getAppExecutorService()
                server.start()
                return callback
            }
        }

        private fun handle(exchange: HttpExchange) {
            val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
            val error = params["error"]
            val returnedState = params["state"]
            val authorizationCode = params["code"]
            val success = error == null && returnedState == state && !authorizationCode.isNullOrBlank()
            when {
                error != null -> code.completeExceptionally(IllegalStateException("OIDC authorization failed: $error"))
                returnedState != state -> code.completeExceptionally(IllegalStateException("OIDC state validation failed"))
                authorizationCode.isNullOrBlank() -> code.completeExceptionally(IllegalStateException("OIDC callback contains no authorization code"))
                else -> code.complete(authorizationCode)
            }
            val html = if (success) {
                "<html><body><h2>CodeAgent sign-in complete</h2><p>You can return to the IDE.</p></body></html>"
            } else {
                "<html><body><h2>CodeAgent sign-in failed</h2><p>Return to the IDE for details.</p></body></html>"
            }
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (success) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
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

        private fun parseQuery(value: String): Map<String, String> = value.split('&').filter(String::isNotBlank).associate { pair ->
            val parts = pair.split('=', limit = 2)
            java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
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
