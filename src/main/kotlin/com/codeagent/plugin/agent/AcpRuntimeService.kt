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
class AcpRuntimeService(project: Project) : Disposable {
    private val contextEngine = project.service<ContextEngineService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val refreshInFlight = AtomicBoolean(false)

    @Volatile
    private var current = AcpRuntimeSnapshot()

    @Volatile
    private var configuredAgentIds = emptySet<String>()

    @Volatile
    private var changeListener: (() -> Unit)? = null

    private val poller = scheduler.scheduleWithFixedDelay(
        {
            if (configuredAgentIds.isNotEmpty() && refreshInFlight.compareAndSet(false, true)) {
                refresh().whenComplete { _, _ -> refreshInFlight.set(false) }
            }
        },
        10,
        10,
        TimeUnit.SECONDS,
    )

    fun snapshot(): AcpRuntimeSnapshot = current

    fun setChangeListener(listener: (() -> Unit)?) {
        changeListener = listener
    }

    internal fun reconcile(configurations: List<RemoteConfiguration>): CompletableFuture<AcpRuntimeSnapshot> {
        val parsed = configurations.filter { it.kind == "acp" }.map(::acpAgentConfiguration)
        configuredAgentIds = parsed.mapTo(mutableSetOf(), AcpAgentConfiguration::id)
        return requestSnapshot(
            type = "acp.reconcile",
            payload = buildJsonObject { put("configurations", json.encodeToJsonElement(parsed)) },
            timeout = Duration.ofMinutes(2),
        )
    }

    internal fun prepareForRun(): CompletableFuture<AcpRuntimeSnapshot> =
        if (configuredAgentIds.isEmpty()) CompletableFuture.completedFuture(current)
        else refresh().exceptionally { current }

    internal fun refresh(): CompletableFuture<AcpRuntimeSnapshot> = requestSnapshot("acp.status")

    internal fun start(agentId: String): CompletableFuture<AcpRuntimeSnapshot> = command("acp.start", agentId)

    internal fun stop(agentId: String): CompletableFuture<AcpRuntimeSnapshot> = command("acp.stop", agentId)

    internal fun restart(agentId: String): CompletableFuture<AcpRuntimeSnapshot> = command("acp.restart", agentId)

    internal fun definitions(): List<AgentToolDefinition> = current.agents
        .filter { it.enabled && it.state == "ready" }
        .map { agent ->
            AgentToolDefinition(
                name = toolName(agent.id),
                description = "Delegate a bounded prompt to the ACP v1 agent '${agent.name}'. Reuse session_id only for deliberate multi-turn continuation.",
                parameters = buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", buildJsonObject {
                        put("prompt", buildJsonObject {
                            put("type", "string")
                            put("description", "Complete prompt for the external ACP agent")
                            put("minLength", 1)
                        })
                        put("session_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional ACP session ID returned by a previous call")
                            put("minLength", 1)
                        })
                    })
                    put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("prompt"))))
                },
                risk = ToolRisk.MUTATING,
            )
        }

    internal fun hasTool(name: String): Boolean = current.agents.any { toolName(it.id) == name }

    internal fun execute(name: String, arguments: JsonObject): CompletableFuture<ToolExecutionResult> {
        val agent = current.agents.firstOrNull { toolName(it.id) == name }
            ?: error("Unknown ACP tool: $name")
        val prompt = arguments["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(prompt.isNotEmpty()) { "ACP prompt is required" }
        val sessionId = arguments["session_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        return contextEngine.sidecarRequest(
            type = "acp.prompt",
            payload = buildJsonObject {
                put("agentId", agent.id)
                put("prompt", prompt)
                sessionId?.let { put("sessionId", it) }
            },
            timeout = Duration.ofMinutes(31),
        ).thenApply { payload ->
            val result = json.decodeFromJsonElement<AcpPromptResult>(payload)
            val output = result.text.ifBlank { "ACP agent stopped with ${result.stopReason} without a text response" }
            ToolExecutionResult(
                output = output,
                summary = "${agent.name} · ${result.stopReason} · session ${result.sessionId}",
                detail = output.take(8_000),
            )
        }
    }

    override fun dispose() {
        poller.cancel(false)
        changeListener = null
    }

    private fun command(type: String, agentId: String): CompletableFuture<AcpRuntimeSnapshot> {
        require(agentId.isNotBlank()) { "ACP agent ID is required" }
        return requestSnapshot(
            type,
            buildJsonObject { put("agentId", agentId) },
            Duration.ofMinutes(2),
        )
    }

    private fun requestSnapshot(
        type: String,
        payload: JsonObject? = null,
        timeout: Duration = Duration.ofSeconds(30),
    ): CompletableFuture<AcpRuntimeSnapshot> = contextEngine.sidecarRequest(type, payload, timeout)
        .thenApply { json.decodeFromJsonElement<AcpRuntimeSnapshot>(it) }
        .whenComplete { snapshot, error ->
            if (snapshot != null) current = snapshot
            else if (error != null) current = current.copy(state = "degraded", label = error.rootMessage())
            changeListener?.invoke()
        }

    private fun toolName(agentId: String): String = "acp__${agentId.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(72)}__prompt"
}

internal fun acpAgentConfiguration(configuration: RemoteConfiguration): AcpAgentConfiguration {
    require(configuration.kind == "acp") { "Expected ACP configuration, got ${configuration.kind}" }
    val value = configuration.value
    return AcpAgentConfiguration(
        id = configuration.id,
        name = value.acpText("name") ?: configuration.id,
        description = value.acpText("description"),
        enabled = value["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
        command = requireNotNull(value.acpText("command")) { "ACP command is required" },
        args = value.acpStrings("args"),
        cwd = value.acpText("cwd"),
        requiredEnvironment = value.acpStrings("requiredEnvironment"),
        authMethodId = value.acpText("authMethodId"),
        timeoutSeconds = value["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: 300,
    )
}

private fun JsonObject.acpText(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.acpStrings(name: String): List<String> =
    (get(name) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }?.distinct().orEmpty()

private fun Throwable.rootMessage(): String {
    var current = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: "ACP runtime failed"
}

@Serializable
internal data class AcpAgentConfiguration(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean = true,
    val command: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val requiredEnvironment: List<String> = emptyList(),
    val authMethodId: String? = null,
    val timeoutSeconds: Int = 300,
)

@Serializable
data class AcpSessionSnapshot(val sessionId: String, val updatedAt: String)

@Serializable
data class AcpAgentSnapshot(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val state: String,
    val label: String,
    val protocolVersion: Int? = null,
    val agentName: String? = null,
    val agentVersion: String? = null,
    val loadSession: Boolean = false,
    val authMethods: List<AcpAuthMethodSnapshot> = emptyList(),
    val sessions: List<AcpSessionSnapshot> = emptyList(),
    val pid: Int? = null,
    val lastError: String? = null,
)

@Serializable
data class AcpAuthMethodSnapshot(val id: String, val name: String)

@Serializable
data class AcpRuntimeSnapshot(
    val state: String = "idle",
    val label: String = "No ACP agents configured",
    val agents: List<AcpAgentSnapshot> = emptyList(),
)

@Serializable
private data class AcpPromptResult(
    val agentId: String,
    val sessionId: String,
    val stopReason: String,
    val text: String,
)
