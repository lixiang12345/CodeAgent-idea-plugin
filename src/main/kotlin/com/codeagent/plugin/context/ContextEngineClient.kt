package com.codeagent.plugin.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ContextEngineClient(
    private val root: Path,
    private val configuredNodePath: () -> String? = { null },
) : Disposable {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val processLock = Any()

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var extractedServer: Path? = null

    fun request(
        type: String,
        payload: JsonObject? = null,
        timeout: Duration = Duration.ofSeconds(30),
        onProgress: (JsonElement) -> Unit = {},
    ): CompletableFuture<JsonElement> {
        val result = CompletableFuture<JsonElement>()
        executor.execute {
            runCatching {
                ensureStarted()
                val id = UUID.randomUUID().toString()
                val timeoutTask = scheduler.schedule({
                    pending.remove(id)?.future?.completeExceptionally(
                        IllegalStateException("ContextEngine request '$type' timed out after ${timeout.seconds}s"),
                    )
                }, timeout.toMillis(), TimeUnit.MILLISECONDS)
                val requestFuture = CompletableFuture<JsonElement>()
                pending[id] = PendingRequest(requestFuture, onProgress) { timeoutTask.cancel(false) }
                requestFuture.whenComplete { value, error ->
                    if (error != null) result.completeExceptionally(error) else result.complete(value)
                }
                val request = SidecarRequest(id = id, type = type, root = root.toString(), payload = payload)
                synchronized(processLock) {
                    writer?.apply {
                        write(json.encodeToString(request))
                        newLine()
                        flush()
                    } ?: error("ContextEngine process is not writable")
                }
            }.onFailure(result::completeExceptionally)
        }
        return result
    }

    override fun dispose() {
        stopProcess("ContextEngine client disposed")
        extractedServer?.let { runCatching { Files.deleteIfExists(it) } }
        extractedServer = null
    }

    fun restart() {
        stopProcess("ContextEngine runtime settings changed")
    }

    private fun stopProcess(reason: String) {
        val active = synchronized(processLock) {
            val current = process
            process = null
            writer = null
            current
        }
        active?.destroy()
        if (active?.waitFor(2, TimeUnit.SECONDS) == false) active.destroyForcibly()
        failPending(IllegalStateException(reason))
    }

    private fun ensureStarted() {
        synchronized(processLock) {
            if (process?.isAlive == true) return

            process?.destroyForcibly()
            failPending(IllegalStateException("ContextEngine process restarted"))

            val node = NodeRuntimeLocator.find(configuredNodePath())
            val server = extractedServer ?: extractServer().also { extractedServer = it }
            val started = ProcessBuilder(node, server.toString())
                .directory(root.toFile())
                .start()
            process = started
            writer = started.outputWriter()
            executor.execute { readStdout(started) }
            executor.execute { readStderr(started) }
        }
    }

    private fun extractServer(): Path {
        val target = Files.createTempFile("codeagent-context-", ".mjs")
        val source = requireNotNull(javaClass.getResourceAsStream("/sidecar/server.mjs")) {
            "Missing bundled ContextEngine sidecar"
        }
        source.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        return target
    }

    private fun readStdout(owner: Process) {
        owner.inputReader().useLines { lines ->
            lines.forEach { line ->
                runCatching { json.decodeFromString<SidecarResponse>(line) }
                    .onSuccess(::handleResponse)
                    .onFailure { LOG.warn("Invalid ContextEngine response: $line", it) }
            }
        }
        if (process === owner) {
            failPending(IllegalStateException("ContextEngine process exited with code ${owner.exitValue()}"))
        }
    }

    private fun readStderr(owner: Process) {
        owner.errorReader().useLines { lines ->
            lines.forEach { line -> LOG.info("ContextEngine: $line") }
        }
    }

    private fun handleResponse(response: SidecarResponse) {
        val request = pending[response.id] ?: return
        when (response.type) {
            "progress" -> response.payload?.let(request.onProgress)
            "result" -> {
                pending.remove(response.id)
                request.cancelTimeout()
                request.future.complete(response.payload ?: JsonObject(emptyMap()))
            }
            "error" -> {
                pending.remove(response.id)
                request.cancelTimeout()
                val message = response.payload
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonPrimitive
                    ?.content
                    ?: "ContextEngine request failed"
                request.future.completeExceptionally(IllegalStateException(message))
            }
        }
    }

    private fun failPending(error: Throwable) {
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach {
            it.cancelTimeout()
            it.future.completeExceptionally(error)
        }
    }

    private data class PendingRequest(
        val future: CompletableFuture<JsonElement>,
        val onProgress: (JsonElement) -> Unit,
        val cancelTimeout: () -> Unit,
    )

    @Serializable
    private data class SidecarRequest(
        val id: String,
        val type: String,
        val root: String,
        val payload: JsonObject? = null,
    )

    @Serializable
    private data class SidecarResponse(
        val id: String,
        val type: String,
        val payload: JsonElement? = null,
    )

    companion object {
        private val LOG = logger<ContextEngineClient>()
    }
}
