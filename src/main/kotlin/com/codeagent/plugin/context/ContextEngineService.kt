package com.codeagent.plugin.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.codeagent.plugin.settings.CodeAgentSettings
import com.codeagent.plugin.settings.CodeAgentSettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class ContextEngineService(project: Project) : Disposable {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = project.basePath
        ?.let { Path.of(it).toAbsolutePath().normalize() }
    private val settings = service<CodeAgentSettingsService>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    private val client = root?.let { projectRoot ->
        ContextEngineClient(projectRoot, projectContextRoots(project, projectRoot)) {
            contextEngineRuntimeSettings(settings.snapshot())
        }
    }

    fun status(): CompletableFuture<ContextStatus> {
        val client = client ?: return unavailable()
        return client.request("status").thenCompose { payload ->
            val status = json.decodeFromJsonElement<ContextStatus>(payload)
            if (!status.indexed || status.watching) {
                CompletableFuture.completedFuture(status)
            } else {
                ensureWatching()
            }
        }
    }

    fun index(onProgress: (IndexProgress) -> Unit): CompletableFuture<IndexResult> {
        val client = client ?: return unavailable()
        return client.request(
            type = "index",
            timeout = Duration.ofMinutes(20),
            onProgress = { onProgress(json.decodeFromJsonElement(it)) },
        ).thenApply { json.decodeFromJsonElement<IndexResult>(it) }
            .thenCompose { result -> ensureWatching().thenApply { result } }
    }

    fun retrieve(
        informationRequest: String,
        topK: Int = 14,
        maxTokens: Int? = null,
    ): CompletableFuture<PackedContext> {
        val client = client ?: return unavailable()
        return client.request(
        type = "retrieve",
        payload = buildJsonObject {
            put("informationRequest", informationRequest)
            put("topK", topK)
            maxTokens?.let { put("maxTokens", it) }
        },
        timeout = Duration.ofMinutes(2),
        ).thenApply { json.decodeFromJsonElement(it) }
    }

    internal fun retrievePlanned(
        informationRequest: String,
        strategy: String = "balanced",
        focusPaths: List<String> = emptyList(),
        maxTokens: Int? = null,
    ): CompletableFuture<PlannedContextPack> {
        val plan = ContextQueryPlanner.plan(informationRequest, strategy, focusPaths)
        val topK = when (plan.strategy) {
            "fast" -> 12
            "deep" -> 16
            else -> 12
        }
        val searches = plan.queries.map { query ->
            search(query.text, topK, query.pathPrefix).thenApply { hits -> ContextQueryResult(query, hits) }
        }
        if (client == null) return unavailable()
        return CompletableFuture.allOf(*searches.toTypedArray()).thenApply {
            ContextPackBuilder.build(plan, searches.map { future -> future.join() }, maxTokens)
        }
    }

    private fun search(
        query: String,
        topK: Int,
        pathPrefix: String?,
    ): CompletableFuture<List<ContextSearchHit>> {
        val client = client ?: return unavailable()
        return client.request(
        type = "search",
        payload = buildJsonObject {
            put("query", query)
            put("topK", topK)
            put("mode", "auto")
            pathPrefix?.let { put("pathPrefix", it) }
        },
        timeout = Duration.ofMinutes(2),
        ).thenApply { json.decodeFromJsonElement(it) }
    }

    fun restart() {
        client?.restart()
    }

    internal fun checkRemoteConnection(
        baseUrl: String,
        apiKey: String?,
    ): CompletableFuture<ContextConnectionCheck> =
        validateContextEngineConnection(httpClient, baseUrl, apiKey)

    internal fun sidecarRequest(
        type: String,
        payload: JsonObject? = null,
        timeout: Duration = Duration.ofSeconds(30),
    ): CompletableFuture<JsonElement> = client?.request(type, payload, timeout) ?: unavailable()

    override fun dispose() {
        client?.dispose()
    }

    private fun ensureWatching(): CompletableFuture<ContextStatus> {
        val client = client ?: return unavailable()
        return client.request(
            type = "watch",
            payload = buildJsonObject { put("debounceMs", 800) },
        ).thenApply { json.decodeFromJsonElement(it) }
    }

    private fun <T> unavailable(): CompletableFuture<T> = CompletableFuture.failedFuture(
        IllegalStateException("ContextEngine is unavailable until a project with a base path is open"),
    )
}

internal fun contextEngineRuntimeSettings(
    current: CodeAgentSettings,
    resolvedNodePath: String? = null,
    processEnvironment: Map<String, String> = System.getenv(),
): ContextEngineRuntimeSettings {
    val nodePath = resolvedNodePath ?: ManagedNodeRuntimeInstaller.resolve(current)
    if (current.contextMode == "remote-http") {
        val apiKey = resolvedContextHttpApiKey(current, processEnvironment)
        val environment = buildMap {
            put("CONTEXTENGINE_HTTP_URL", current.contextHttpBaseUrl)
            apiKey?.let { put("CONTEXTENGINE_HTTP_API_KEY", it) }
        }
        return ContextEngineRuntimeSettings(nodePath = nodePath, environment = environment)
    }

    if (current.contextMode != "private-semantic") {
        return ContextEngineRuntimeSettings(nodePath = nodePath)
    }

    val apiKey = current.contextEmbeddingApiKey?.takeIf { it.isNotBlank() } ?: "codeagent-local-endpoint"
    val environment = buildMap {
        put("CONTEXTENGINE_EMBEDDING_API_KEY", apiKey)
        put("CONTEXTENGINE_EMBEDDING_BASE_URL", current.contextEmbeddingBaseUrl)
        put("CONTEXTENGINE_EMBEDDING_MODEL", current.contextEmbeddingModel)
        if (current.contextNeuralRerank) {
            put("CONTEXTENGINE_NEURAL_RERANK", "1")
            put("CONTEXTENGINE_RERANK_API_KEY", apiKey)
            put(
                "CONTEXTENGINE_RERANK_BASE_URL",
                current.contextRerankBaseUrl.ifBlank { current.contextEmbeddingBaseUrl },
            )
            put("CONTEXTENGINE_RERANK_MODEL", current.contextRerankModel)
        }
    }
    return ContextEngineRuntimeSettings(nodePath = nodePath, environment = environment)
}

internal fun resolvedContextHttpApiKey(
    current: CodeAgentSettings,
    processEnvironment: Map<String, String> = System.getenv(),
): String? = current.contextHttpApiKey?.trim()?.takeIf { it.isNotEmpty() }
    ?: processEnvironment["CONTEXTENGINE_HTTP_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }

@Serializable
data class ContextStatus(
    val indexed: Boolean,
    val root: String,
    val roots: List<String> = emptyList(),
    val chunkCount: Int = 0,
    val fileCount: Int = 0,
    val hasEmbeddings: Boolean = false,
    val lastIndexedAt: String? = null,
    val watching: Boolean = false,
    val watchedRootCount: Int = 0,
    val watchDebounceMs: Int = 800,
    val pendingChanges: Int = 0,
    val automaticIndexRuns: Int = 0,
    val lastAutomaticIndexAt: String? = null,
    val lastAutomaticDurationMs: Long? = null,
    val lastAutomaticFilesIndexed: Int = 0,
    val lastAutomaticFilesRemoved: Int = 0,
    val lastAutomaticChunksWritten: Int = 0,
    val watchError: String? = null,
)

internal fun projectContextRoots(project: Project, primaryRoot: Path): List<ContextIndexRoot> {
    val normalizedPrimary = primaryRoot.toAbsolutePath().normalize()
    val usedNames = mutableSetOf("main")
    return ProjectRootManager.getInstance(project).contentRoots
        .asSequence()
        .mapNotNull { virtualFile ->
            runCatching { Path.of(virtualFile.path).toAbsolutePath().normalize() }.getOrNull()
        }
        .filter { candidate -> candidate != normalizedPrimary && !candidate.startsWith(normalizedPrimary) }
        .distinct()
        .map { candidate ->
            val baseName = candidate.fileName
                ?.toString()
                .orEmpty()
                .lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-')
                .ifBlank { "root" }
                .take(48)
            var name = baseName
            var suffix = 2
            while (!usedNames.add(name)) {
                name = "${baseName.take(43)}-$suffix"
                suffix += 1
            }
            ContextIndexRoot(name = name, path = candidate.toString())
        }
        .toList()
}

@Serializable
data class IndexProgress(
    val phase: String,
    val filesTotal: Int,
    val filesDone: Int,
    val chunksTotal: Int,
    val message: String? = null,
)

@Serializable
data class IndexResult(
    val filesScanned: Int,
    val filesIndexed: Int,
    val filesRemoved: Int,
    val chunksWritten: Int,
    val embeddingsWritten: Int,
    val durationMs: Long,
    val roots: List<String>,
)

@Serializable
data class PackedContext(
    val task: String,
    val packedText: String,
    val estimatedTokens: Int,
    val truncated: Boolean,
)

internal data class ContextConnectionCheck(
    val ok: Boolean,
    val label: String,
)

internal fun validateContextEngineConnection(
    httpClient: HttpClient,
    baseUrl: String,
    apiKey: String?,
): CompletableFuture<ContextConnectionCheck> {
    val normalizedBaseUrl = normalizeContextEngineHttpUrl(baseUrl)
    val request = HttpRequest.newBuilder(URI.create("$normalizedBaseUrl/v1/workspaces"))
        .timeout(Duration.ofSeconds(15))
        .GET()
        .apply {
            apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") }
        }
        .build()
    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply { response ->
        when (response.statusCode()) {
            in 200..299 -> ContextConnectionCheck(
                ok = true,
                label = "Connection and token verified. Save settings to apply.",
            )
            401 -> ContextConnectionCheck(ok = false, label = "ContextEngine token is invalid")
            403 -> ContextConnectionCheck(ok = false, label = "ContextEngine token is not authorized")
            else -> ContextConnectionCheck(
                ok = false,
                label = "ContextEngine returned HTTP ${response.statusCode()}",
            )
        }
    }
}

private fun normalizeContextEngineHttpUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
    val uri = URI.create(normalized)
    require(uri.scheme == "http" || uri.scheme == "https") { "ContextEngine URL must use http or https" }
    require(uri.host != null) { "ContextEngine URL must include a host" }
    val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1")
    require(uri.scheme == "https" || loopback) {
        "ContextEngine URL must use https unless it targets the local machine"
    }
    require(uri.userInfo == null) { "ContextEngine URL must not contain credentials" }
    return normalized
}
