package com.codeagent.plugin.agent

import com.codeagent.plugin.context.ContextEngineService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class McpRuntimeService(project: Project) : Disposable {
    private val contextEngine = project.service<ContextEngineService>()
    private val oauth = service<McpOAuthService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var current = McpRuntimeSnapshot()

    @Volatile
    private var configuredServerIds = emptySet<String>()

    @Volatile
    private var configurationsById = emptyMap<String, RemoteConfiguration>()

    @Volatile
    private var changeListener: (() -> Unit)? = null

    private val poller = scheduler.scheduleWithFixedDelay(
        {
            if (configuredServerIds.isNotEmpty() && refreshInFlight.compareAndSet(false, true)) {
                refresh().whenComplete { _, _ -> refreshInFlight.set(false) }
            }
        },
        10,
        10,
        TimeUnit.SECONDS,
    )

    fun snapshot(): McpRuntimeSnapshot = current

    fun setChangeListener(listener: (() -> Unit)?) {
        changeListener = listener
    }

    internal fun reconcile(configurations: List<RemoteConfiguration>): CompletableFuture<McpRuntimeSnapshot> {
        configurationsById = configurations.associateBy(RemoteConfiguration::id)
        configuredServerIds = configurationsById.keys
        val prepared = configurations.map { configuration ->
            val oauthConfiguration = mcpOAuthConfiguration(configuration)
            if (oauthConfiguration == null) {
                CompletableFuture.completedFuture(mcpServerConfiguration(configuration))
            } else {
                oauth.ensureAccessToken(oauthConfiguration).thenApply { token ->
                    mcpServerConfiguration(configuration, token)
                }
            }
        }
        return CompletableFuture.allOf(*prepared.toTypedArray()).thenCompose {
            val parsed = prepared.map(CompletableFuture<McpServerConfiguration>::join)
            requestSnapshot(
                type = "mcp.reconcile",
                payload = buildJsonObject {
                    put("configurations", json.encodeToJsonElement(parsed))
                },
                timeout = Duration.ofMinutes(2),
            )
        }
    }

    internal fun authorize(serverId: String): CompletableFuture<McpRuntimeSnapshot> {
        val configuration = requireNotNull(configurationsById[serverId]) { "Unknown MCP server: $serverId" }
        val oauthConfiguration = requireNotNull(mcpOAuthConfiguration(configuration)) {
            "MCP server '$serverId' is not configured for OAuth"
        }
        return oauth.authorize(oauthConfiguration).thenCompose { reconcile(configurationsById.values.toList()) }
    }

    internal fun disconnectOAuth(serverId: String): CompletableFuture<McpRuntimeSnapshot> {
        require(configurationsById.containsKey(serverId)) { "Unknown MCP server: $serverId" }
        oauth.clear(serverId)
        return reconcile(configurationsById.values.toList())
    }

    internal fun refresh(): CompletableFuture<McpRuntimeSnapshot> =
        requestSnapshot("mcp.status")

    internal fun prepareForRun(): CompletableFuture<McpRuntimeSnapshot> =
        if (configuredServerIds.isEmpty()) {
            CompletableFuture.completedFuture(current)
        } else {
            refresh().exceptionally { current }
        }

    internal fun start(serverId: String): CompletableFuture<McpRuntimeSnapshot> =
        serverCommand("mcp.start", serverId, Duration.ofMinutes(2))

    internal fun stop(serverId: String): CompletableFuture<McpRuntimeSnapshot> =
        serverCommand("mcp.stop", serverId)

    internal fun restart(serverId: String): CompletableFuture<McpRuntimeSnapshot> =
        serverCommand("mcp.restart", serverId, Duration.ofMinutes(2))

    internal fun test(serverId: String): CompletableFuture<McpRuntimeSnapshot> =
        serverCommand("mcp.test", serverId, Duration.ofMinutes(2))

    internal fun definitions(): List<AgentToolDefinition> {
        val serverNames = current.servers.associate { it.id to it.name }
        return current.tools.map { tool ->
            AgentToolDefinition(
                name = tool.id,
                description = "MCP ${serverNames[tool.serverId] ?: tool.serverId}: ${tool.description}",
                parameters = tool.parameters,
                risk = tool.risk.toToolRisk(),
            )
        }
    }

    internal fun hasTool(name: String): Boolean = current.tools.any { it.id == name }

    internal fun risk(name: String): ToolRisk =
        current.tools.firstOrNull { it.id == name }?.risk?.toToolRisk()
            ?: error("Unknown MCP tool: $name")

    internal fun execute(name: String, arguments: JsonObject): CompletableFuture<ToolExecutionResult> =
        contextEngine.sidecarRequest(
            type = "mcp.call",
            payload = buildJsonObject {
                put("toolId", name)
                put("arguments", arguments)
            },
            timeout = Duration.ofMinutes(10),
        ).thenApply { payload ->
            val result = json.decodeFromJsonElement<McpToolCallResult>(payload)
            ToolExecutionResult(
                output = result.output,
                summary = result.summary,
                detail = result.detail,
            )
        }

    override fun dispose() {
        poller.cancel(false)
        changeListener = null
    }

    private fun serverCommand(
        type: String,
        serverId: String,
        timeout: Duration = Duration.ofSeconds(30),
    ): CompletableFuture<McpRuntimeSnapshot> {
        require(serverId.isNotBlank()) { "MCP server ID is required" }
        return requestSnapshot(
            type = type,
            payload = buildJsonObject { put("serverId", serverId) },
            timeout = timeout,
        )
    }

    private fun requestSnapshot(
        type: String,
        payload: JsonObject? = null,
        timeout: Duration = Duration.ofSeconds(30),
    ): CompletableFuture<McpRuntimeSnapshot> =
        contextEngine.sidecarRequest(type, payload, timeout).thenApply {
            json.decodeFromJsonElement<McpRuntimeSnapshot>(it)
        }.whenComplete { snapshot, error ->
            if (snapshot != null) {
                current = snapshot
            } else if (error != null) {
                current = current.copy(
                    state = "degraded",
                    label = error.rootMessage(),
                )
            }
            changeListener?.invoke()
        }
}

internal fun mcpServerConfiguration(configuration: RemoteConfiguration, accessToken: String? = null): McpServerConfiguration {
    require(configuration.kind == "mcp") { "Expected MCP configuration, got ${configuration.kind}" }
    val value = configuration.value
    return McpServerConfiguration(
        id = configuration.id,
        name = value.text("name") ?: configuration.id,
        description = value.text("description"),
        enabled = value.boolean("enabled") ?: true,
        transport = value.text("transport") ?: "stdio",
        command = value.text("command"),
        args = value.strings("args"),
        cwd = value.text("cwd"),
        url = value.text("url"),
        authMode = value.text("authMode") ?: "none",
        accessToken = accessToken,
        tokenEnvironment = value.text("tokenEnvironment"),
        requiredEnvironment = value.strings("requiredEnvironment"),
        timeoutSeconds = value.integer("timeoutSeconds") ?: 60,
    )
}

internal fun mcpOAuthConfiguration(configuration: RemoteConfiguration): McpOAuthConfiguration? {
    if (configuration.kind != "mcp" || configuration.value.text("authMode") != "oauth") return null
    val value = configuration.value
    return McpOAuthConfiguration(
        id = configuration.id,
        authorizationEndpoint = requireNotNull(value.text("authorizationEndpoint")) { "MCP OAuth authorization endpoint is required" },
        tokenEndpoint = requireNotNull(value.text("tokenEndpoint")) { "MCP OAuth token endpoint is required" },
        clientId = requireNotNull(value.text("clientId")) { "MCP OAuth client ID is required" },
        scopes = value.strings("scopes"),
        audience = value.text("audience"),
    )
}

private fun JsonObject.text(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.integer(name: String): Int? =
    get(name)?.jsonPrimitive?.intOrNull

private fun JsonObject.strings(name: String): List<String> =
    (get(name) as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        ?.distinct()
        .orEmpty()

private fun String.toToolRisk(): ToolRisk =
    if (this == "read_only") ToolRisk.READ_ONLY else ToolRisk.MUTATING

private fun Throwable.rootMessage(): String {
    var current = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: "MCP runtime failed"
}

@Serializable
internal data class McpServerConfiguration(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val transport: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val url: String? = null,
    val authMode: String = "none",
    val accessToken: String? = null,
    val tokenEnvironment: String? = null,
    val requiredEnvironment: List<String> = emptyList(),
    val timeoutSeconds: Int = 60,
)

@Serializable
data class McpToolSnapshot(
    val id: String,
    val serverId: String,
    val name: String,
    val title: String? = null,
    val description: String,
    val parameters: JsonObject,
    val risk: String,
)

@Serializable
data class McpServerSnapshot(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val transport: String,
    val state: String,
    val label: String,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val protocolVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val tools: List<McpToolSnapshot> = emptyList(),
    val pid: Int? = null,
    val latencyMs: Long? = null,
    val restartCount: Int = 0,
    val lastConnectedAt: String? = null,
    val lastHealthyAt: String? = null,
    val lastError: String? = null,
)

@Serializable
data class McpRuntimeSnapshot(
    val state: String = "idle",
    val label: String = "No MCP servers configured",
    val servers: List<McpServerSnapshot> = emptyList(),
    val tools: List<McpToolSnapshot> = emptyList(),
)

@Serializable
private data class McpToolCallResult(
    val output: String,
    val summary: String,
    val detail: String,
)
