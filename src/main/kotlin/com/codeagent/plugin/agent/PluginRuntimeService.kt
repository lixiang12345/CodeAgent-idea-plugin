package com.codeagent.plugin.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture

internal data class PluginCommandDefinition(
    val id: String,
    val pluginId: String,
    val pluginVersion: String,
    val name: String,
    val description: String? = null,
    val prompt: String,
    val argumentHint: String? = null,
    val mode: String = "inherit",
    val agentProfileId: String? = null,
)

internal data class PluginPromptDefinition(
    val id: String,
    val pluginId: String,
    val pluginVersion: String,
    val name: String,
    val description: String? = null,
    val prompt: String,
    val argumentHint: String? = null,
)

internal data class PluginAgentDefinition(
    val id: String,
    val pluginId: String,
    val pluginVersion: String,
    val name: String,
    val description: String? = null,
    val agentType: String = "general",
    val systemPrompt: String? = null,
    val model: String? = null,
    val allowedTools: List<String> = emptyList(),
    val maxTurns: Int = 12,
    val maxToolCalls: Int = 48,
    val maxSubagentCalls: Int = 4,
    val verificationPolicy: String = "after-mutation",
    val contextWindowTokens: Int = 256_000,
    val reservedOutputTokens: Int = 8_192,
)

internal data class PluginToolDefinition(
    val id: String,
    val pluginId: String,
    val pluginVersion: String,
    val name: String,
    val description: String? = null,
    val target: String,
    val defaults: JsonObject = JsonObject(emptyMap()),
)

internal data class PluginRuntimeItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val source: String,
    val state: String,
    val label: String,
    val configuredVersion: String? = null,
    val installedVersion: String? = null,
    val latestVersion: String? = null,
    val integrity: String? = null,
    val grantedCapabilities: List<String> = emptyList(),
    val declaredCapabilities: List<String> = emptyList(),
    val commandCount: Int = 0,
    val promptCount: Int = 0,
    val ruleCount: Int = 0,
    val skillCount: Int = 0,
    val agentCount: Int = 0,
    val hookCount: Int = 0,
    val mcpCount: Int = 0,
    val toolCount: Int = 0,
    val installedAt: String? = null,
    val lastCheckedAt: String? = null,
    val lastError: String? = null,
)

internal data class PluginRuntimeSnapshot(
    val state: String = "idle",
    val label: String = "No plugins configured",
    val items: List<PluginRuntimeItem> = emptyList(),
    val commands: List<PluginCommandDefinition> = emptyList(),
    val prompts: List<PluginPromptDefinition> = emptyList(),
    val rules: List<WorkspaceRule> = emptyList(),
    val skills: List<WorkspaceSkill> = emptyList(),
    val agents: List<PluginAgentDefinition> = emptyList(),
    val hooks: List<RemoteConfiguration> = emptyList(),
    val mcpServers: List<RemoteConfiguration> = emptyList(),
    val tools: List<PluginToolDefinition> = emptyList(),
)

internal data class PluginDefinition(
    val id: String,
    val name: String,
    val description: String? = null,
    val enabled: Boolean,
    val source: String,
    val version: String? = null,
    val integrity: String? = null,
    val grantedCapabilities: List<String> = emptyList(),
)

@Serializable
internal data class DeclarativePluginManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val publisher: String? = null,
    val homepage: String? = null,
    val capabilities: List<String> = emptyList(),
    val commands: List<DeclarativePluginCommand> = emptyList(),
    val prompts: List<DeclarativePluginPrompt> = emptyList(),
    val rules: List<DeclarativePluginRule> = emptyList(),
    val skills: List<DeclarativePluginSkill> = emptyList(),
    val agents: List<DeclarativePluginAgent> = emptyList(),
    val hooks: List<DeclarativePluginHook> = emptyList(),
    val mcp: List<DeclarativePluginMcpServer> = emptyList(),
    val tools: List<DeclarativePluginTool> = emptyList(),
)

@Serializable
internal data class DeclarativePluginCommand(
    val id: String,
    val name: String,
    val description: String? = null,
    val prompt: String,
    val argumentHint: String? = null,
    val mode: String = "inherit",
    val agentProfileId: String? = null,
)

@Serializable
internal data class DeclarativePluginPrompt(
    val id: String,
    val name: String,
    val description: String? = null,
    val prompt: String,
    val argumentHint: String? = null,
)

@Serializable
internal data class DeclarativePluginRule(
    val id: String,
    val name: String,
    val description: String? = null,
    val content: String,
    val trigger: String = "always",
)

@Serializable
internal data class DeclarativePluginSkill(
    val id: String,
    val name: String,
    val description: String? = null,
    val content: String,
)

@Serializable
internal data class DeclarativePluginAgent(
    val id: String,
    val name: String,
    val description: String? = null,
    val agentType: String = "general",
    val systemPrompt: String? = null,
    val model: String? = null,
    val allowedTools: List<String> = emptyList(),
    val maxTurns: Int = 12,
    val maxToolCalls: Int = 48,
    val maxSubagentCalls: Int = 4,
    val verificationPolicy: String = "after-mutation",
    val contextWindowTokens: Int = 256_000,
    val reservedOutputTokens: Int = 8_192,
)

@Serializable
internal data class DeclarativePluginHook(
    val id: String,
    val name: String,
    val event: String,
    val command: String,
    val timeoutSeconds: Int = 60,
    val runPolicy: String = "manual",
    val failurePolicy: String = "continue",
    val requiredEnvironment: List<String> = emptyList(),
)

@Serializable
internal data class DeclarativePluginMcpServer(
    val id: String,
    val name: String,
    val description: String? = null,
    val transport: String = "stdio",
    val command: String? = null,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val url: String? = null,
    val authMode: String = "none",
    val tokenEnvironment: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val clientId: String? = null,
    val scopes: List<String> = emptyList(),
    val audience: String? = null,
    val requiredEnvironment: List<String> = emptyList(),
    val timeoutSeconds: Int = 60,
)

@Serializable
internal data class DeclarativePluginTool(
    val id: String,
    val name: String,
    val description: String? = null,
    val target: String,
    val defaults: JsonObject = JsonObject(emptyMap()),
)

internal data class StoredPluginManifest(
    val bytes: ByteArray,
    val installedAt: String,
)

internal fun interface PluginManifestFetcher {
    fun fetch(source: String): ByteArray
}

internal interface PluginManifestStore {
    fun read(pluginId: String): StoredPluginManifest?
    fun write(pluginId: String, bytes: ByteArray): StoredPluginManifest
    fun delete(pluginId: String)
}

internal class PluginRuntime(
    private val fetcher: PluginManifestFetcher,
    private val store: PluginManifestStore,
    private val onChanged: () -> Unit = {},
) {
    private val lock = Any()
    private var definitions = emptyMap<String, PluginDefinition>()
    private val installed = linkedMapOf<String, InstalledPlugin>()
    private val checked = linkedMapOf<String, CheckedPlugin>()
    private val errors = linkedMapOf<String, String>()

    fun reconcile(configurations: List<RemoteConfiguration>) {
        val nextDefinitions = configurations
            .filter { it.kind == "plugins" }
            .mapNotNull(::pluginDefinition)
            .associateBy(PluginDefinition::id)
        synchronized(lock) {
            definitions = nextDefinitions
            installed.keys.retainAll(nextDefinitions.keys)
            checked.keys.retainAll(nextDefinitions.keys)
            errors.keys.retainAll(nextDefinitions.keys)
            nextDefinitions.values.forEach { definition ->
                val stored = try {
                    store.read(definition.id)
                } catch (error: Throwable) {
                    installed.remove(definition.id)
                    errors[definition.id] = error.rootMessage()
                    return@forEach
                }
                if (stored == null) {
                    installed.remove(definition.id)
                    errors.remove(definition.id)
                    return@forEach
                }
                runCatching {
                    validateManifest(definition, stored.bytes)
                }.onSuccess { manifest ->
                    installed[definition.id] = InstalledPlugin(manifest, stored.installedAt)
                    errors.remove(definition.id)
                }.onFailure { error ->
                    installed.remove(definition.id)
                    errors[definition.id] = error.rootMessage()
                }
            }
        }
        onChanged()
    }

    fun install(pluginId: String): PluginRuntimeSnapshot = perform(pluginId) { definition ->
        val bytes = fetcher.fetch(definition.source)
        val manifest = validateManifest(definition, bytes)
        val stored = store.write(pluginId, bytes)
        synchronized(lock) {
            installed[pluginId] = InstalledPlugin(manifest, stored.installedAt)
            checked[pluginId] = CheckedPlugin(manifest, Instant.now().toString())
            errors.remove(pluginId)
        }
    }

    fun test(pluginId: String): PluginRuntimeSnapshot = perform(pluginId) { definition ->
        val manifest = validateManifest(definition, fetcher.fetch(definition.source))
        synchronized(lock) {
            checked[pluginId] = CheckedPlugin(manifest, Instant.now().toString())
            errors.remove(pluginId)
        }
    }

    fun uninstall(pluginId: String): PluginRuntimeSnapshot {
        require(PLUGIN_ID_PATTERN.matches(pluginId)) { "Invalid plugin ID" }
        return try {
            store.delete(pluginId)
            synchronized(lock) {
                installed.remove(pluginId)
                checked.remove(pluginId)
                errors.remove(pluginId)
            }
            onChanged()
            snapshot()
        } catch (error: Throwable) {
            recordError(pluginId, error)
            throw error
        }
    }

    fun snapshot(): PluginRuntimeSnapshot = synchronized(lock) {
        val commands = activeCommandsLocked()
        val prompts = activePromptsLocked()
        val rules = activeRulesLocked()
        val skills = activeSkillsLocked()
        val agents = activeAgentsLocked()
        val hooks = activeHooksLocked()
        val mcpServers = activeMcpServersLocked()
        val tools = activeToolsLocked()
        val items = definitions.values.sortedBy(PluginDefinition::name).map { definition ->
            val installedPlugin = installed[definition.id]
            val checkedPlugin = checked[definition.id]
            val lastError = errors[definition.id]
            val updateAvailable = installedPlugin != null &&
                checkedPlugin != null &&
                checkedPlugin.manifest.version != installedPlugin.manifest.version
            val state = when {
                lastError != null -> "error"
                installedPlugin == null -> "available"
                updateAvailable -> "update-available"
                !definition.enabled -> "disabled"
                else -> "ready"
            }
            val label = when (state) {
                "error" -> lastError.orEmpty()
                "available" -> checkedPlugin?.let { "Validated ${it.manifest.version}; not installed" } ?: "Not installed on this device"
                "update-available" -> "${installedPlugin?.manifest?.version} → ${checkedPlugin?.manifest?.version}"
                "disabled" -> "Installed locally; activation disabled"
                else -> "Installed and active"
            }
            val manifest = installedPlugin?.manifest ?: checkedPlugin?.manifest
            PluginRuntimeItem(
                id = definition.id,
                name = manifest?.name ?: definition.name,
                description = manifest?.description ?: definition.description,
                source = definition.source,
                state = state,
                label = label,
                configuredVersion = definition.version,
                installedVersion = installedPlugin?.manifest?.version,
                latestVersion = checkedPlugin?.manifest?.version,
                integrity = definition.integrity,
                grantedCapabilities = definition.grantedCapabilities,
                declaredCapabilities = manifest?.capabilities.orEmpty(),
                commandCount = commands.count { it.pluginId == definition.id },
                promptCount = prompts.count { it.pluginId == definition.id },
                ruleCount = rules.count { it.source == "plugin:${definition.id}" },
                skillCount = skills.count { it.source == "plugin:${definition.id}" },
                agentCount = agents.count { it.pluginId == definition.id },
                hookCount = hooks.count { it.value["pluginId"]?.jsonPrimitive?.contentOrNull == definition.id },
                mcpCount = mcpServers.count { it.value["pluginId"]?.jsonPrimitive?.contentOrNull == definition.id },
                toolCount = tools.count { it.pluginId == definition.id },
                installedAt = installedPlugin?.installedAt,
                lastCheckedAt = checkedPlugin?.checkedAt,
                lastError = lastError,
            )
        }
        val state = when {
            items.isEmpty() -> "idle"
            items.any { it.state == "error" } -> "degraded"
            else -> "ready"
        }
        val installedCount = items.count { it.installedVersion != null }
        val activeCount = items.count { it.state == "ready" || it.state == "update-available" }
        PluginRuntimeSnapshot(
            state = state,
            label = if (items.isEmpty()) {
                "No plugins configured"
            } else {
                "${items.size} configured · $installedCount installed · $activeCount active · " +
                    "${commands.size + prompts.size + rules.size + skills.size + agents.size + hooks.size + mcpServers.size + tools.size} contributions"
            },
            items = items,
            commands = commands,
            prompts = prompts,
            rules = rules,
            skills = skills,
            agents = agents,
            hooks = hooks,
            mcpServers = mcpServers,
            tools = tools,
        )
    }

    private fun perform(pluginId: String, operation: (PluginDefinition) -> Unit): PluginRuntimeSnapshot {
        val definition = synchronized(lock) {
            definitions[pluginId]
        } ?: error("Unknown plugin: $pluginId")
        return try {
            operation(definition)
            onChanged()
            snapshot()
        } catch (error: Throwable) {
            recordError(pluginId, error)
            throw error
        }
    }

    private fun recordError(pluginId: String, error: Throwable) {
        synchronized(lock) {
            errors[pluginId] = error.rootMessage()
        }
        onChanged()
    }

    private fun activeCommandsLocked(): List<PluginCommandDefinition> =
        activeManifestsLocked("commands")
            .flatMap { (definition, manifest) ->
                manifest.commands.asSequence().map { command ->
                    PluginCommandDefinition(
                        id = "${definition.id}.${command.id}",
                        pluginId = definition.id,
                        pluginVersion = manifest.version,
                        name = command.name,
                        description = command.description,
                        prompt = command.prompt,
                        argumentHint = command.argumentHint,
                        mode = command.mode,
                        agentProfileId = command.agentProfileId?.let { profileId ->
                            namespacedPluginReference(definition.id, profileId, manifest.agents.mapTo(mutableSetOf()) { it.id })
                        },
                    )
                }
            }
            .sortedBy(PluginCommandDefinition::id)
            .toList()

    private fun activePromptsLocked(): List<PluginPromptDefinition> =
        activeManifestsLocked("prompts")
            .flatMap { (definition, manifest) ->
                manifest.prompts.asSequence().map { prompt ->
                    PluginPromptDefinition(
                        id = "${definition.id}.${prompt.id}",
                        pluginId = definition.id,
                        pluginVersion = manifest.version,
                        name = prompt.name,
                        description = prompt.description,
                        prompt = prompt.prompt,
                        argumentHint = prompt.argumentHint,
                    )
                }
            }
            .sortedBy(PluginPromptDefinition::id)
            .toList()

    private fun activeRulesLocked(): List<WorkspaceRule> =
        activeManifestsLocked("rules")
            .flatMap { (definition, manifest) ->
                manifest.rules.asSequence().map { rule ->
                    WorkspaceRule(
                        id = "plugin:${definition.id}:rule:${rule.id}",
                        name = rule.name,
                        path = "plugin://${definition.id}/rules/${rule.id}.md",
                        content = rule.content,
                        trigger = rule.trigger,
                        description = rule.description ?: "Plugin rule from ${definition.name}",
                        source = "plugin:${definition.id}",
                    )
                }
            }
            .sortedBy(WorkspaceRule::id)
            .toList()

    private fun activeSkillsLocked(): List<WorkspaceSkill> =
        activeManifestsLocked("skills")
            .flatMap { (definition, manifest) ->
                manifest.skills.asSequence().map { skill ->
                    WorkspaceSkill(
                        id = "plugin:${definition.id}:skill:${skill.id}",
                        name = skill.name,
                        description = skill.description ?: "Plugin skill from ${definition.name}",
                        path = "plugin://${definition.id}/skills/${skill.id}/SKILL.md",
                        content = skill.content,
                        source = "plugin:${definition.id}",
                    )
                }
            }
            .sortedBy(WorkspaceSkill::id)
            .toList()

    private fun activeAgentsLocked(): List<PluginAgentDefinition> =
        activeManifestsLocked("agents")
            .flatMap { (definition, manifest) ->
                val localToolIds = manifest.tools.mapTo(mutableSetOf()) { it.id }
                manifest.agents.asSequence().map { agent ->
                    PluginAgentDefinition(
                        id = pluginContributionId(definition.id, agent.id),
                        pluginId = definition.id,
                        pluginVersion = manifest.version,
                        name = agent.name,
                        description = agent.description,
                        agentType = agent.agentType,
                        systemPrompt = agent.systemPrompt,
                        model = agent.model,
                        allowedTools = agent.allowedTools.map { toolId ->
                            namespacedPluginReference(definition.id, toolId, localToolIds)
                        },
                        maxTurns = agent.maxTurns,
                        maxToolCalls = agent.maxToolCalls,
                        maxSubagentCalls = agent.maxSubagentCalls,
                        verificationPolicy = agent.verificationPolicy,
                        contextWindowTokens = agent.contextWindowTokens,
                        reservedOutputTokens = agent.reservedOutputTokens,
                    )
                }
            }
            .sortedBy(PluginAgentDefinition::id)
            .toList()

    private fun activeHooksLocked(): List<RemoteConfiguration> =
        activeManifestsLocked("hooks")
            .flatMap { (definition, manifest) ->
                manifest.hooks.asSequence().map { hook ->
                    RemoteConfiguration(
                        id = pluginContributionId(definition.id, hook.id),
                        kind = "hooks",
                        value = buildJsonObject {
                            put("name", hook.name)
                            put("enabled", true)
                            put("event", hook.event)
                            put("command", hook.command)
                            put("timeoutSeconds", hook.timeoutSeconds)
                            put("runPolicy", hook.runPolicy)
                            put("failurePolicy", hook.failurePolicy)
                            put("requiredEnvironment", buildJsonArray { hook.requiredEnvironment.forEach { add(JsonPrimitive(it)) } })
                            put("pluginId", definition.id)
                            put("pluginVersion", manifest.version)
                        },
                    )
                }
            }
            .sortedBy(RemoteConfiguration::id)
            .toList()

    private fun activeMcpServersLocked(): List<RemoteConfiguration> =
        activeManifestsLocked("mcp")
            .flatMap { (definition, manifest) ->
                manifest.mcp.asSequence().map { server ->
                    RemoteConfiguration(
                        id = pluginContributionId(definition.id, server.id),
                        kind = "mcp",
                        value = buildJsonObject {
                            put("name", server.name)
                            server.description?.let { put("description", it) }
                            put("enabled", true)
                            put("transport", server.transport)
                            server.command?.let { put("command", it) }
                            put("args", buildJsonArray { server.args.forEach { add(JsonPrimitive(it)) } })
                            server.cwd?.let { put("cwd", it) }
                            server.url?.let { put("url", it) }
                            put("authMode", server.authMode)
                            server.tokenEnvironment?.let { put("tokenEnvironment", it) }
                            server.authorizationEndpoint?.let { put("authorizationEndpoint", it) }
                            server.tokenEndpoint?.let { put("tokenEndpoint", it) }
                            server.clientId?.let { put("clientId", it) }
                            put("scopes", buildJsonArray { server.scopes.forEach { add(JsonPrimitive(it)) } })
                            server.audience?.let { put("audience", it) }
                            put("requiredEnvironment", buildJsonArray { server.requiredEnvironment.forEach { add(JsonPrimitive(it)) } })
                            put("timeoutSeconds", server.timeoutSeconds)
                            put("pluginId", definition.id)
                            put("pluginVersion", manifest.version)
                        },
                    )
                }
            }
            .sortedBy(RemoteConfiguration::id)
            .toList()

    private fun activeToolsLocked(): List<PluginToolDefinition> =
        activeManifestsLocked("tools")
            .flatMap { (definition, manifest) ->
                manifest.tools.asSequence().map { tool ->
                    PluginToolDefinition(
                        id = pluginContributionId(definition.id, tool.id),
                        pluginId = definition.id,
                        pluginVersion = manifest.version,
                        name = tool.name,
                        description = tool.description,
                        target = tool.target,
                        defaults = tool.defaults,
                    )
                }
            }
            .sortedBy(PluginToolDefinition::id)
            .toList()

    private fun activeManifestsLocked(capability: String): Sequence<Pair<PluginDefinition, DeclarativePluginManifest>> =
        definitions.values.asSequence()
            .filter(PluginDefinition::enabled)
            .filter { capability in it.grantedCapabilities }
            .mapNotNull { definition -> installed[definition.id]?.manifest?.let { definition to it } }

    private data class InstalledPlugin(
        val manifest: DeclarativePluginManifest,
        val installedAt: String,
    )

    private data class CheckedPlugin(
        val manifest: DeclarativePluginManifest,
        val checkedAt: String,
    )
}

@Service(Service.Level.PROJECT)
class PluginRuntimeService : Disposable {
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val runtime = PluginRuntime(
        fetcher = HttpPluginManifestFetcher(),
        store = FilePluginManifestStore(
            Path.of(PathManager.getSystemPath(), "codeagent", "plugins"),
        ),
    ) { listener?.invoke() }

    @Volatile
    private var listener: (() -> Unit)? = null

    internal fun reconcile(configurations: List<RemoteConfiguration>) = runtime.reconcile(configurations)

    internal fun install(pluginId: String): CompletableFuture<PluginRuntimeSnapshot> =
        CompletableFuture.supplyAsync({ runtime.install(pluginId) }, executor)

    internal fun update(pluginId: String): CompletableFuture<PluginRuntimeSnapshot> = install(pluginId)

    internal fun test(pluginId: String): CompletableFuture<PluginRuntimeSnapshot> =
        CompletableFuture.supplyAsync({ runtime.test(pluginId) }, executor)

    internal fun uninstall(pluginId: String): CompletableFuture<PluginRuntimeSnapshot> =
        CompletableFuture.supplyAsync({ runtime.uninstall(pluginId) }, executor)

    internal fun snapshot(): PluginRuntimeSnapshot = runtime.snapshot()

    fun setChangeListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    override fun dispose() {
        listener = null
    }
}

private class HttpPluginManifestFetcher(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) : PluginManifestFetcher {
    override fun fetch(source: String): ByteArray {
        val sourceUri = validatePluginSource(source)
        val response = http.send(
            HttpRequest.newBuilder(sourceUri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "CodeAgent-IDE")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        require(response.statusCode() in 200..299) {
            "Plugin manifest request failed with HTTP ${response.statusCode()}"
        }
        validatePluginSource(response.uri().toString())
        return response.body().use { input ->
            val bytes = input.readNBytes(MAX_PLUGIN_MANIFEST_BYTES + 1)
            require(bytes.size <= MAX_PLUGIN_MANIFEST_BYTES) {
                "Plugin manifest exceeds $MAX_PLUGIN_MANIFEST_BYTES bytes"
            }
            bytes
        }
    }
}

private class FilePluginManifestStore(
    private val root: Path,
) : PluginManifestStore {
    override fun read(pluginId: String): StoredPluginManifest? {
        val path = path(pluginId)
        if (!Files.isRegularFile(path)) return null
        val bytes = Files.readAllBytes(path)
        require(bytes.size <= MAX_PLUGIN_MANIFEST_BYTES) { "Cached plugin manifest is too large" }
        return StoredPluginManifest(
            bytes = bytes,
            installedAt = Files.getLastModifiedTime(path).toInstant().toString(),
        )
    }

    override fun write(pluginId: String, bytes: ByteArray): StoredPluginManifest {
        require(bytes.size <= MAX_PLUGIN_MANIFEST_BYTES) { "Plugin manifest is too large" }
        Files.createDirectories(root)
        val target = path(pluginId)
        val temporary = Files.createTempFile(root, "$pluginId-", ".json.tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return StoredPluginManifest(bytes, Files.getLastModifiedTime(target).toInstant().toString())
    }

    override fun delete(pluginId: String) {
        Files.deleteIfExists(path(pluginId))
    }

    private fun path(pluginId: String): Path {
        require(PLUGIN_ID_PATTERN.matches(pluginId)) { "Invalid plugin ID" }
        return root.resolve("$pluginId.json")
    }
}

private fun pluginDefinition(configuration: RemoteConfiguration): PluginDefinition? {
    val value = configuration.value
    val name = value["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val source = value["source"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (!PLUGIN_ID_PATTERN.matches(configuration.id) || name.isBlank() || source.isBlank()) return null
    return PluginDefinition(
        id = configuration.id,
        name = name,
        description = value["description"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
        enabled = value["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
        source = source,
        version = value["version"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
        integrity = value["integrity"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
        grantedCapabilities = (value["capabilities"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            .orEmpty(),
    )
}

private fun validateManifest(
    definition: PluginDefinition,
    bytes: ByteArray,
): DeclarativePluginManifest {
    require(bytes.isNotEmpty()) { "Plugin manifest is empty" }
    require(bytes.size <= MAX_PLUGIN_MANIFEST_BYTES) { "Plugin manifest is too large" }
    definition.integrity?.let { expected ->
        require(INTEGRITY_PATTERN.matches(expected)) { "Plugin integrity must use sha256:<hex>" }
        require("sha256:${sha256(bytes)}" == expected) { "Plugin manifest integrity check failed" }
    }
    val manifest = PLUGIN_JSON.decodeFromString<DeclarativePluginManifest>(
        bytes.toString(StandardCharsets.UTF_8),
    )
    require(manifest.schemaVersion == 1) { "Unsupported plugin manifest schema ${manifest.schemaVersion}" }
    require(manifest.id == definition.id) {
        "Plugin manifest ID '${manifest.id}' does not match '${definition.id}'"
    }
    require(PLUGIN_ID_PATTERN.matches(manifest.id)) { "Invalid plugin manifest ID" }
    require(manifest.name.isNotBlank() && manifest.name.length <= 160) { "Plugin name is invalid" }
    require(manifest.version.isNotBlank() && manifest.version.length <= 240) { "Plugin version is invalid" }
    definition.version?.let { required ->
        require(manifest.version == required) {
            "Plugin version ${manifest.version} does not match configured version $required"
        }
    }
    require(manifest.description == null || manifest.description.length <= 2_000) { "Plugin description is too long" }
    require(manifest.publisher == null || manifest.publisher.length <= 240) { "Plugin publisher is too long" }
    manifest.homepage?.let(::validatePluginSource)
    val declaredCapabilities = manifest.capabilities.distinct()
    require(declaredCapabilities.size == manifest.capabilities.size) { "Plugin capabilities must be unique" }
    require(declaredCapabilities.all { it in ALLOWED_PLUGIN_CAPABILITIES }) {
        "Plugin declares an unsupported capability"
    }
    require(definition.grantedCapabilities.all { it in ALLOWED_PLUGIN_CAPABILITIES }) {
        "Plugin configuration grants an unsupported capability"
    }
    require(definition.grantedCapabilities.all { it in declaredCapabilities }) {
        "Plugin configuration grants a capability not declared by the manifest"
    }
    require(manifest.commands.isEmpty() || "commands" in declaredCapabilities) {
        "Plugin command contributions require the commands capability"
    }
    require(manifest.prompts.isEmpty() || "prompts" in declaredCapabilities) {
        "Plugin prompt contributions require the prompts capability"
    }
    require(manifest.rules.isEmpty() || "rules" in declaredCapabilities) {
        "Plugin rule contributions require the rules capability"
    }
    require(manifest.skills.isEmpty() || "skills" in declaredCapabilities) {
        "Plugin skill contributions require the skills capability"
    }
    require(manifest.agents.isEmpty() || "agents" in declaredCapabilities) {
        "Plugin Agent contributions require the agents capability"
    }
    require(manifest.hooks.isEmpty() || "hooks" in declaredCapabilities) {
        "Plugin hook contributions require the hooks capability"
    }
    require(manifest.mcp.isEmpty() || "mcp" in declaredCapabilities) {
        "Plugin MCP contributions require the mcp capability"
    }
    require(manifest.tools.isEmpty() || "tools" in declaredCapabilities) {
        "Plugin tool contributions require the tools capability"
    }
    require(manifest.commands.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many command contributions"
    }
    require(manifest.prompts.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many prompt contributions"
    }
    require(manifest.rules.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many rule contributions"
    }
    require(manifest.skills.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many skill contributions"
    }
    require(manifest.agents.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many Agent contributions"
    }
    require(manifest.hooks.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many hook contributions"
    }
    require(manifest.mcp.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many MCP contributions"
    }
    require(manifest.tools.size <= MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE) {
        "Plugin declares too many tool contributions"
    }
    require(
        manifest.commands.size + manifest.prompts.size + manifest.rules.size + manifest.skills.size +
            manifest.agents.size + manifest.hooks.size + manifest.mcp.size + manifest.tools.size <=
            MAX_PLUGIN_CONTRIBUTIONS_TOTAL,
    ) {
        "Plugin declares too many contributions"
    }
    val commandIds = mutableSetOf<String>()
    manifest.commands.forEach { command ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(command.id)) { "Invalid plugin command ID '${command.id}'" }
        require(commandIds.add(command.id)) { "Duplicate plugin command ID '${command.id}'" }
        require(PLUGIN_ID_PATTERN.matches("${manifest.id}.${command.id}")) {
            "Namespaced plugin command ID is too long"
        }
        require(command.name.isNotBlank() && command.name.length <= 160) { "Plugin command name is invalid" }
        require(command.description == null || command.description.length <= 2_000) {
            "Plugin command description is too long"
        }
        require(command.prompt.isNotBlank() && command.prompt.length <= 100_000) {
            "Plugin command prompt is invalid"
        }
        require(command.argumentHint == null || command.argumentHint.length <= 500) {
            "Plugin command argument hint is too long"
        }
        require(command.mode in setOf("inherit", "agent", "chat", "ask")) {
            "Plugin command mode is invalid"
        }
        require(
            command.agentProfileId == null ||
                command.agentProfileId in BUILT_IN_AGENT_PROFILES ||
                manifest.agents.any { it.id == command.agentProfileId },
        ) {
            "Plugin command Agent profile is invalid"
        }
    }
    val promptIds = mutableSetOf<String>()
    manifest.prompts.forEach { prompt ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(prompt.id)) { "Invalid plugin prompt ID '${prompt.id}'" }
        require(promptIds.add(prompt.id)) { "Duplicate plugin prompt ID '${prompt.id}'" }
        require(PLUGIN_ID_PATTERN.matches("${manifest.id}.${prompt.id}")) {
            "Namespaced plugin prompt ID is too long"
        }
        require(prompt.name.isNotBlank() && prompt.name.length <= 160) { "Plugin prompt name is invalid" }
        require(prompt.description == null || prompt.description.length <= 2_000) {
            "Plugin prompt description is too long"
        }
        require(prompt.prompt.isNotBlank() && prompt.prompt.length <= 100_000) {
            "Plugin prompt template is invalid"
        }
        require(prompt.argumentHint == null || prompt.argumentHint.length <= 500) {
            "Plugin prompt argument hint is too long"
        }
    }
    require(commandIds.intersect(promptIds).isEmpty()) {
        "Plugin command and prompt IDs must be unique across slash contributions"
    }
    val ruleIds = mutableSetOf<String>()
    manifest.rules.forEach { rule ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(rule.id)) { "Invalid plugin rule ID '${rule.id}'" }
        require(ruleIds.add(rule.id)) { "Duplicate plugin rule ID '${rule.id}'" }
        require(rule.name.isNotBlank() && rule.name.length <= 160) { "Plugin rule name is invalid" }
        require(rule.description == null || rule.description.length <= 2_000) {
            "Plugin rule description is too long"
        }
        require(rule.content.isNotBlank() && rule.content.length <= MAX_PLUGIN_CONTEXT_CONTENT_CHARS) {
            "Plugin rule content is invalid"
        }
        require(rule.trigger in RULE_TRIGGERS) { "Plugin rule trigger is invalid" }
    }
    val skillIds = mutableSetOf<String>()
    manifest.skills.forEach { skill ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(skill.id)) { "Invalid plugin skill ID '${skill.id}'" }
        require(skillIds.add(skill.id)) { "Duplicate plugin skill ID '${skill.id}'" }
        require(skill.name.isNotBlank() && skill.name.length <= 160) { "Plugin skill name is invalid" }
        require(skill.description == null || skill.description.length <= 2_000) {
            "Plugin skill description is too long"
        }
        require(skill.content.isNotBlank() && skill.content.length <= MAX_PLUGIN_CONTEXT_CONTENT_CHARS) {
            "Plugin skill content is invalid"
        }
    }
    val agentIds = mutableSetOf<String>()
    manifest.agents.forEach { agent ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(agent.id)) { "Invalid plugin Agent ID '${agent.id}'" }
        require(agentIds.add(agent.id)) { "Duplicate plugin Agent ID '${agent.id}'" }
        require(namespacedContributionIdWithinLimit(manifest.id, agent.id)) {
            "Namespaced plugin Agent ID is too long"
        }
        require(agent.name.isNotBlank() && agent.name.length <= 160) { "Plugin Agent name is invalid" }
        require(agent.description == null || agent.description.length <= 2_000) {
            "Plugin Agent description is too long"
        }
        require(agent.agentType in AGENT_PROFILE_TYPES) { "Plugin Agent type is invalid" }
        require(agent.systemPrompt == null || agent.systemPrompt.length <= 100_000) {
            "Plugin Agent system prompt is too long"
        }
        require(agent.model == null || agent.model.length <= 240) { "Plugin Agent model is invalid" }
        require(agent.allowedTools.distinct().size == agent.allowedTools.size) {
            "Plugin Agent allowed tools must be unique"
        }
        require(agent.allowedTools.size <= 128 && agent.allowedTools.all(::validPluginReference)) {
            "Plugin Agent allowed tools are invalid"
        }
        require(agent.maxTurns in 1..64) { "Plugin Agent maxTurns must be between 1 and 64" }
        require(agent.maxToolCalls in 1..256) { "Plugin Agent maxToolCalls must be between 1 and 256" }
        require(agent.maxSubagentCalls in 0..16) { "Plugin Agent maxSubagentCalls must be between 0 and 16" }
        require(agent.verificationPolicy in VERIFICATION_POLICIES) {
            "Plugin Agent verification policy is invalid"
        }
        require(agent.contextWindowTokens in 32_768..2_000_000) {
            "Plugin Agent context window is invalid"
        }
        require(agent.reservedOutputTokens in 1_024..65_536 && agent.reservedOutputTokens < agent.contextWindowTokens) {
            "Plugin Agent reserved output budget is invalid"
        }
    }
    val hookIds = mutableSetOf<String>()
    manifest.hooks.forEach { hook ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(hook.id)) { "Invalid plugin hook ID '${hook.id}'" }
        require(hookIds.add(hook.id)) { "Duplicate plugin hook ID '${hook.id}'" }
        require(namespacedContributionIdWithinLimit(manifest.id, hook.id)) {
            "Namespaced plugin hook ID is too long"
        }
        require(hook.name.isNotBlank() && hook.name.length <= 160) { "Plugin hook name is invalid" }
        require(hook.event in HOOK_EVENTS) { "Plugin hook event is invalid" }
        require(hook.command.isNotBlank() && hook.command.length <= 4_000) { "Plugin hook command is invalid" }
        require(hook.timeoutSeconds in 1..600) { "Plugin hook timeout is invalid" }
        require(hook.runPolicy in HOOK_RUN_POLICIES) { "Plugin hook run policy is invalid" }
        require(hook.failurePolicy in HOOK_FAILURE_POLICIES) { "Plugin hook failure policy is invalid" }
        require(hook.requiredEnvironment.size <= 32 && hook.requiredEnvironment.all(::validEnvironmentName)) {
            "Plugin hook environment requirements are invalid"
        }
    }
    val mcpIds = mutableSetOf<String>()
    manifest.mcp.forEach { server ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(server.id)) { "Invalid plugin MCP ID '${server.id}'" }
        require(mcpIds.add(server.id)) { "Duplicate plugin MCP ID '${server.id}'" }
        require(namespacedContributionIdWithinLimit(manifest.id, server.id)) {
            "Namespaced plugin MCP ID is too long"
        }
        require(server.name.isNotBlank() && server.name.length <= 160) { "Plugin MCP name is invalid" }
        require(server.description == null || server.description.length <= 2_000) {
            "Plugin MCP description is too long"
        }
        require(server.transport in MCP_TRANSPORTS) { "Plugin MCP transport is invalid" }
        if (server.transport == "stdio") {
            require(!server.command.isNullOrBlank() && server.command.length <= 4_000) {
                "stdio plugin MCP command is required"
            }
            require(server.url == null) { "stdio plugin MCP cannot declare a URL" }
            require(server.authMode == "none") { "stdio plugin MCP authentication must be none" }
        } else {
            require(server.command == null) { "HTTP plugin MCP cannot declare a command" }
            require(server.url != null) { "HTTP plugin MCP URL is required" }
            validatePluginSource(server.url)
            require(server.authMode in MCP_AUTH_MODES) { "Plugin MCP authentication is invalid" }
        }
        require(server.args.size <= 128 && server.args.all { it.length <= 2_000 }) {
            "Plugin MCP arguments are invalid"
        }
        require(server.cwd == null || (!URI.create(server.cwd).isAbsolute && server.cwd.length <= 4_000)) {
            "Plugin MCP working directory must be relative"
        }
        if (server.authMode == "bearer-environment") {
            require(validEnvironmentName(server.tokenEnvironment)) { "Plugin MCP token environment is invalid" }
        }
        if (server.authMode == "oauth") {
            require(server.authorizationEndpoint != null && server.tokenEndpoint != null) {
                "Plugin MCP OAuth endpoints are required"
            }
            validatePluginSource(server.authorizationEndpoint)
            validatePluginSource(server.tokenEndpoint)
            require(!server.clientId.isNullOrBlank() && server.clientId.length <= 500) {
                "Plugin MCP OAuth client ID is required"
            }
            require(server.scopes.size <= 32 && server.scopes.all { it.length in 1..240 }) {
                "Plugin MCP OAuth scopes are invalid"
            }
            require(server.audience == null || server.audience.length <= 1_000) {
                "Plugin MCP OAuth audience is too long"
            }
        }
        require(server.requiredEnvironment.size <= 32 && server.requiredEnvironment.all(::validEnvironmentName)) {
            "Plugin MCP environment requirements are invalid"
        }
        require(server.timeoutSeconds in 1..600) { "Plugin MCP timeout is invalid" }
    }
    val toolIds = mutableSetOf<String>()
    manifest.tools.forEach { tool ->
        require(PLUGIN_COMMAND_ID_PATTERN.matches(tool.id)) { "Invalid plugin tool ID '${tool.id}'" }
        require(toolIds.add(tool.id)) { "Duplicate plugin tool ID '${tool.id}'" }
        require(namespacedContributionIdWithinLimit(manifest.id, tool.id)) {
            "Namespaced plugin tool ID is too long"
        }
        require(tool.name.isNotBlank() && tool.name.length <= 160) { "Plugin tool name is invalid" }
        require(tool.description == null || tool.description.length <= 2_000) {
            "Plugin tool description is too long"
        }
        require(validPluginReference(tool.target) && !tool.target.startsWith("plugin.")) {
            "Plugin tool target is invalid"
        }
        require(tool.defaults.size <= 64 && tool.defaults.keys.all(::validPluginReference)) {
            "Plugin tool defaults are invalid"
        }
        require(tool.defaults.toString().length <= 16_000) { "Plugin tool defaults are too large" }
    }
    return manifest.copy(capabilities = declaredCapabilities)
}

private fun validatePluginSource(value: String): URI {
    require(value.length <= 2_000) { "Plugin URL is too long" }
    val uri = URI.create(value)
    require(uri.scheme in setOf("http", "https")) { "Plugin URL must use http or https" }
    require(uri.host != null) { "Plugin URL must include a host" }
    require(uri.userInfo == null) { "Plugin URL must not contain credentials" }
    require(uri.fragment == null) { "Plugin URL must not contain a fragment" }
    val loopback = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1")
    require(uri.scheme == "https" || loopback) {
        "Plugin URL must use HTTPS unless it targets the local machine"
    }
    return uri
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun pluginContributionId(pluginId: String, contributionId: String): String =
    "plugin.$pluginId.$contributionId"

private fun namespacedContributionIdWithinLimit(pluginId: String, contributionId: String): Boolean =
    pluginContributionId(pluginId, contributionId).length <= 120

private fun namespacedPluginReference(pluginId: String, reference: String, localIds: Set<String>): String =
    if (reference in localIds) pluginContributionId(pluginId, reference) else reference

private fun validPluginReference(value: String): Boolean =
    value.length in 1..160 && Regex("^[A-Za-z0-9._:-]+$").matches(value)

private fun validEnvironmentName(value: String?): Boolean =
    value != null && Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$").matches(value)

private fun Throwable.rootMessage(): String {
    var current = this
    while (current.cause != null) current = current.cause!!
    return current.message ?: "Plugin operation failed"
}

private val PLUGIN_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
}
private val PLUGIN_ID_PATTERN = Regex("""^[A-Za-z0-9._-]{1,120}$""")
private val PLUGIN_COMMAND_ID_PATTERN = Regex("""^[A-Za-z0-9_-]{1,80}$""")
private val INTEGRITY_PATTERN = Regex("""^sha256:[a-f0-9]{64}$""")
private val ALLOWED_PLUGIN_CAPABILITIES = setOf(
    "commands",
    "agents",
    "hooks",
    "mcp",
    "rules",
    "skills",
    "tools",
    "prompts",
)
private val BUILT_IN_AGENT_PROFILES = setOf("general", "search", "context", "prompt", "loop")
private val AGENT_PROFILE_TYPES = setOf("general", "search", "context", "prompt", "loop")
private val VERIFICATION_POLICIES = setOf("none", "after-mutation")
private val RULE_TRIGGERS = setOf("always", "manual", "agent")
private val HOOK_EVENTS = setOf("before-run", "after-run", "before-tool", "after-tool", "on-error")
private val HOOK_RUN_POLICIES = setOf("manual", "automatic")
private val HOOK_FAILURE_POLICIES = setOf("continue", "fail-run")
private val MCP_TRANSPORTS = setOf("stdio", "streamable-http", "sse")
private val MCP_AUTH_MODES = setOf("none", "bearer-environment", "oauth")
private const val MAX_PLUGIN_MANIFEST_BYTES = 1_048_576
private const val MAX_PLUGIN_CONTRIBUTIONS_PER_TYPE = 32
private const val MAX_PLUGIN_CONTRIBUTIONS_TOTAL = 64
private const val MAX_PLUGIN_CONTEXT_CONTENT_CHARS = 16_000
