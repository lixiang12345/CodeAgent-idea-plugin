package com.codeagent.plugin.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.codeagent.plugin.settings.CodeAgentSettingsService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.time.Duration
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class ContextEngineService(project: Project) : Disposable {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = requireNotNull(project.basePath) { "CodeAgent requires a project base path" }
    private val settings = service<CodeAgentSettingsService>()
    private val client = ContextEngineClient(java.nio.file.Path.of(root)) { settings.snapshot().nodePath }

    fun status(): CompletableFuture<ContextStatus> =
        client.request("status").thenCompose { payload ->
            val status = json.decodeFromJsonElement<ContextStatus>(payload)
            if (!status.indexed) {
                CompletableFuture.completedFuture(status)
            } else {
                ensureWatching().thenCompose {
                    client.request("status").thenApply { current -> json.decodeFromJsonElement(current) }
                }
            }
        }

    fun index(onProgress: (IndexProgress) -> Unit): CompletableFuture<IndexResult> =
        client.request(
            type = "index",
            timeout = Duration.ofMinutes(20),
            onProgress = { onProgress(json.decodeFromJsonElement(it)) },
        ).thenApply { json.decodeFromJsonElement<IndexResult>(it) }
            .thenCompose { result -> ensureWatching().thenApply { result } }

    fun retrieve(
        informationRequest: String,
        topK: Int = 14,
        maxTokens: Int = 8_000,
    ): CompletableFuture<PackedContext> = client.request(
        type = "retrieve",
        payload = buildJsonObject {
            put("informationRequest", informationRequest)
            put("topK", topK)
            put("maxTokens", maxTokens)
        },
        timeout = Duration.ofMinutes(2),
    ).thenApply { json.decodeFromJsonElement(it) }

    fun restart() = client.restart()

    override fun dispose() = client.dispose()

    private fun ensureWatching(): CompletableFuture<ContextStatus> =
        client.request(
            type = "watch",
            payload = buildJsonObject { put("debounceMs", 800) },
        ).thenApply { json.decodeFromJsonElement(it) }
}

@Serializable
data class ContextStatus(
    val indexed: Boolean,
    val root: String,
    val chunkCount: Int = 0,
    val fileCount: Int = 0,
    val hasEmbeddings: Boolean = false,
    val lastIndexedAt: String? = null,
    val watching: Boolean = false,
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
