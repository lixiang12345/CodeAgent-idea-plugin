package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.service
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

object McpOAuthMetadataDiscovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun discover(
        resourceUrl: String,
        configuredTokenEndpoint: String?,
    ): CompletableFuture<DiscoveredMetadata> = CompletableFuture.supplyAsync {
        val resource = secureEndpoint(resourceUrl, "MCP resource URL")
        val candidates = metadataCandidates(resource)
        var discoveredAuthz: String? = null
        var discoveredToken: String? = null
        var discoveredRegistration: String? = null
        var discoveredScopes = emptyList<String>()
        var discoveredCodeChallengeMethods = emptyList<String>()

        for (candidate in candidates) {
            val metadata = runCatching { fetch(candidate) }.getOrNull() ?: continue
            discoveredAuthz = discoveredAuthz ?: metadata.authorization_endpoint
            discoveredToken = discoveredToken ?: metadata.token_endpoint
            discoveredRegistration = discoveredRegistration ?: metadata.registration_endpoint
            if (discoveredScopes.isEmpty()) discoveredScopes = metadata.scopes_supported.orEmpty()
            if (discoveredCodeChallengeMethods.isEmpty()) {
                discoveredCodeChallengeMethods = metadata.code_challenge_methods_supported.orEmpty()
            }
            if (discoveredAuthz != null && discoveredToken != null) break
        }

        DiscoveredMetadata(
            authorizationEndpoint = discoveredAuthz,
            tokenEndpoint = discoveredToken ?: configuredTokenEndpoint,
            registrationEndpoint = discoveredRegistration,
            supportedScopes = discoveredScopes,
            supportedCodeChallengeMethods = discoveredCodeChallengeMethods,
        )
    }

    internal fun metadataCandidates(resource: URI): List<String> {
        val scheme = resource.scheme ?: "https"
        val host = resource.host
        val path = resource.path?.trimStart('/') ?: ""
        return listOfNotNull(
            if (path.isNotEmpty()) "$scheme://$host/.well-known/oauth-protected-resource/$path" else null,
            if (path.isNotEmpty()) "$scheme://$host/$path/.well-known/oauth-protected-resource" else null,
            if (path.isNotEmpty()) "$scheme://$host/.well-known/oauth-authorization-server/$path" else null,
            if (path.isNotEmpty()) "$scheme://$host/$path/.well-known/oauth-authorization-server" else null,
            "$scheme://$host/.well-known/oauth-authorization-server",
            if (path.isNotEmpty()) "$scheme://$host/.well-known/openid-configuration/$path" else null,
            if (path.isNotEmpty()) "$scheme://$host/$path/.well-known/openid-configuration" else null,
            "$scheme://$host/.well-known/openid-configuration",
        ).distinct()
    }

    private fun fetch(url: String): OAuthMetadata {
        val uri = secureEndpoint(url, "OAuth metadata URL")
        val response = http.send(
            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        require(response.statusCode() in 200..299) { "OAuth metadata returned HTTP ${response.statusCode()}" }
        require(response.body().size <= MAX_METADATA_BYTES) { "OAuth metadata is too large" }
        return json.decodeFromString(response.body().decodeToString())
    }

    private fun secureEndpoint(value: String, label: String): URI {
        val uri = URI.create(value.trim())
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "$label must use HTTPS unless it targets the local machine"
        }
        require(uri.userInfo == null && uri.host != null) { "$label is invalid" }
        return uri
    }

    private const val MAX_METADATA_BYTES = 500_000

    @Serializable
    private data class OAuthMetadata(
        val authorization_endpoint: String? = null,
        val token_endpoint: String? = null,
        val registration_endpoint: String? = null,
        val scopes_supported: List<String>? = null,
        val code_challenge_methods_supported: List<String>? = null,
    )

    data class DiscoveredMetadata(
        val authorizationEndpoint: String?,
        val tokenEndpoint: String?,
        val registrationEndpoint: String?,
        val supportedScopes: List<String>,
        val supportedCodeChallengeMethods: List<String>,
    )
}

@Serializable
internal data class DynamicClientRegistrationRequest(
    @SerialName("client_name") val clientName: String,
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
)

@Serializable
internal data class DynamicClientRegistrationResponse(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String? = null,
)

object McpDynamicClientRegistration {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun register(
        serverId: String,
        registrationEndpoint: String,
        redirectUri: String,
    ): CompletableFuture<Unit> = CompletableFuture.supplyAsync {
        val uri = secureEndpoint(registrationEndpoint, "Dynamic client registration endpoint")
        val request = DynamicClientRegistrationRequest(
            clientName = "CodeAgent MCP client",
            redirectUris = listOf(redirectUri),
        )
        val body = json.encodeToString(DynamicClientRegistrationRequest.serializer(), request)
        val response = http.send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        require(response.statusCode() in 200..299) {
            "Dynamic client registration returned HTTP ${response.statusCode()}"
        }
        val registration = json.decodeFromString<DynamicClientRegistrationResponse>(response.body())
        require(registration.clientId.isNotBlank()) { "Dynamic registration returned an empty client_id" }
        service<CodeAgentSettingsService>().setMcpDynamicClientId(serverId, registration.clientId)
        registration.clientSecret?.takeIf(String::isNotBlank)?.let { secret ->
            PasswordSafe.instance.setPassword(dynamicClientSecretAttributes(serverId), secret)
        }
    }

    private fun secureEndpoint(value: String, label: String): URI {
        val uri = URI.create(value.trim())
        val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
            "$label must use HTTPS unless it targets the local machine"
        }
        require(uri.userInfo == null && uri.host != null) { "$label is invalid" }
        return uri
    }

    internal fun dynamicClientSecretAttributes(serverId: String) =
        CredentialAttributes("CodeAgent MCP dynamic client secret", serverId)
}
