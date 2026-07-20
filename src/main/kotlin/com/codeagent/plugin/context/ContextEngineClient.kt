package com.codeagent.plugin.context

import com.codeagent.plugin.context.rpc.ContextEngineRpcGrpc
import com.codeagent.plugin.context.rpc.ContextEvent
import com.codeagent.plugin.context.rpc.ContextRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.google.protobuf.ByteString
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
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
    private val extraRoots: List<ContextIndexRoot> = emptyList(),
    private val runtimeSettings: () -> ContextEngineRuntimeSettings = {
        ContextEngineRuntimeSettings(NodeRuntimeLocator.find(null))
    },
) : Disposable {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val processLock = Any()

    @Volatile
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var grpcChannel: ManagedChannel? = null
    private var grpcStub: ContextEngineRpcGrpc.ContextEngineRpcStub? = null
    private var extractedRuntimeDirectory: Path? = null
    private var activeRuntime: ContextEngineRuntimeSettings? = null

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
                val runtime = activeRuntime ?: error("ContextEngine runtime is not active")
                val id = UUID.randomUUID().toString()
                val timeoutTask = scheduler.schedule({
                    pending.remove(id)?.future?.completeExceptionally(
                        IllegalStateException("ContextEngine request '$type' timed out after ${timeout.seconds}s"),
                    )
                }, timeout.toMillis(), TimeUnit.MILLISECONDS)
                val requestFuture = CompletableFuture<JsonElement>()
                pending[id] = PendingRequest(requestFuture, onProgress, { timeoutTask.cancel(false) })
                requestFuture.whenComplete { value, error ->
                    if (error != null) result.completeExceptionally(error) else result.complete(value)
                }
                if (runtime.rpcMode == "jsonl") {
                    val request = SidecarRequest(
                        id = id,
                        type = type,
                        root = root.toString(),
                        extraRoots = extraRoots,
                        payload = payload,
                    )
                    synchronized(processLock) {
                        writer?.apply {
                            write(json.encodeToString(request))
                            newLine()
                            flush()
                        } ?: error("ContextEngine process is not writable")
                    }
                } else {
                    sendGrpcRequest(id, type, payload, timeout)
                }
            }.onFailure(result::completeExceptionally)
        }
        return result
    }

    override fun dispose() {
        stopProcess("ContextEngine client disposed")
        extractedRuntimeDirectory?.let { runCatching { it.toFile().deleteRecursively() } }
        extractedRuntimeDirectory = null
    }

    fun restart() {
        stopProcess("ContextEngine runtime settings changed")
    }

    private fun stopProcess(reason: String) {
        val active = synchronized(processLock) {
            val current = process
            process = null
            writer = null
            grpcStub = null
            grpcChannel?.shutdownNow()
            grpcChannel = null
            activeRuntime = null
            current
        }
        active?.destroy()
        if (active?.waitFor(2, TimeUnit.SECONDS) == false) active.destroyForcibly()
        failPending(IllegalStateException(reason))
    }

    private fun ensureStarted() {
        synchronized(processLock) {
            val runtime = runtimeSettings()
            if (process?.isAlive == true && activeRuntime == runtime) return

            process?.destroyForcibly()
            failPending(IllegalStateException("ContextEngine process restarted"))

            val runtimeDirectory = extractedRuntimeDirectory ?: extractRuntimeDirectory().also {
                extractedRuntimeDirectory = it
            }
            val server = if (runtime.rpcMode == "jsonl") {
                runtimeDirectory.resolve("server.mjs")
            } else {
                runtimeDirectory.resolve("grpc-server.mjs")
            }
            val builder = ProcessBuilder(runtime.nodePath, server.toString())
                .directory(root.toFile())
            val environment = builder.environment()
            CONTEXT_ENVIRONMENT_KEYS.forEach(environment::remove)
            environment.putAll(runtime.environment)
            val started = builder.start()
            process = started
            activeRuntime = runtime
            if (runtime.rpcMode == "jsonl") {
                writer = started.outputWriter()
                executor.execute { readStdout(started) }
            } else {
                try {
                    startGrpcTransport(started)
                } catch (error: Throwable) {
                    process = null
                    activeRuntime = null
                    started.destroyForcibly()
                    throw error
                }
            }
            executor.execute { readStderr(started) }
        }
    }

    private fun extractRuntimeDirectory(): Path {
        val directory = Files.createTempDirectory("codeagent-context-")
        listOf("server.mjs", "grpc-server.mjs", "context-engine.proto").forEach { name ->
            val source = requireNotNull(javaClass.getResourceAsStream("/sidecar/$name")) {
                "Missing bundled ContextEngine sidecar resource: $name"
            }
            source.use {
                Files.copy(it, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return directory
    }

    private fun startGrpcTransport(started: Process) {
        val readyLine = CompletableFuture.supplyAsync({
            started.inputReader().use { it.readLine() }
        }, executor).get(GRPC_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val ready = parseGrpcReady(readyLine)
        val channel = ManagedChannelBuilder
            .forAddress("127.0.0.1", ready.port)
            .usePlaintext()
            .build()
        val metadata = Metadata().apply {
            put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer ${ready.token}")
        }
        grpcChannel = channel
        grpcStub = ContextEngineRpcGrpc.newStub(
            ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata)),
        )
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

    private fun sendGrpcRequest(
        id: String,
        type: String,
        payload: JsonObject?,
        timeout: Duration,
    ) {
        val stub = grpcStub ?: error("ContextEngine gRPC transport is not ready")
        val request = ContextRequest.newBuilder()
            .setId(id)
            .setType(type)
            .setRoot(root.toString())
            .addAllExtraRoots(extraRoots.map { entry ->
                com.codeagent.plugin.context.rpc.IndexRoot.newBuilder()
                    .setName(entry.name)
                    .setPath(entry.path)
                    .setKind(entry.kind)
                    .build()
            })
            .setPayloadJson(ByteString.copyFrom(json.encodeToString(payload ?: JsonObject(emptyMap())).toByteArray()))
            .build()
        stub.withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(
            request,
            object : StreamObserver<ContextEvent> {
                override fun onNext(event: ContextEvent) {
                    val requestState = pending[event.id] ?: return
                    when (event.type) {
                        ContextEvent.Type.PROGRESS -> requestState.onProgress(parsePayload(event.payloadJson))
                        ContextEvent.Type.RESULT -> {
                            pending.remove(event.id)
                            requestState.cancelTimeout()
                            requestState.future.complete(parsePayload(event.payloadJson))
                        }
                        ContextEvent.Type.ERROR -> {
                            pending.remove(event.id)
                            requestState.cancelTimeout()
                            requestState.future.completeExceptionally(
                                IllegalStateException(event.errorMessage.ifBlank { "ContextEngine request failed" }),
                            )
                        }
                        else -> Unit
                    }
                }

                override fun onError(error: Throwable) {
                    pending.remove(id)?.let { requestState ->
                        requestState.cancelTimeout()
                        requestState.future.completeExceptionally(error)
                    }
                }

                override fun onCompleted() = Unit
            },
        )
    }

    private fun parsePayload(payload: ByteString): JsonElement =
        if (payload.isEmpty) JsonObject(emptyMap()) else json.parseToJsonElement(payload.toStringUtf8())

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
        val extraRoots: List<ContextIndexRoot> = emptyList(),
        val payload: JsonObject? = null,
    )

    @Serializable
    private data class SidecarResponse(
        val id: String,
        val type: String,
        val payload: JsonElement? = null,
    )

    private data class GrpcReady(val port: Int, val token: String, val protocolVersion: Int)

    private fun parseGrpcReady(line: String?): GrpcReady {
        require(line?.startsWith("CODEAGENT_GRPC_READY ") == true) {
            "ContextEngine gRPC sidecar did not become ready: ${line ?: "process exited"}"
        }
        val payload = json.parseToJsonElement(line.removePrefix("CODEAGENT_GRPC_READY ")).jsonObject
        return GrpcReady(
            port = payload.getValue("port").jsonPrimitive.content.toInt(),
            token = payload.getValue("token").jsonPrimitive.content,
            protocolVersion = payload.getValue("protocolVersion").jsonPrimitive.content.toInt(),
        ).also {
            require(it.protocolVersion == GRPC_PROTOCOL_VERSION) {
                "Unsupported ContextEngine gRPC protocol ${it.protocolVersion}; expected $GRPC_PROTOCOL_VERSION"
            }
        }
    }

    companion object {
        private val LOG = logger<ContextEngineClient>()
        private const val GRPC_PROTOCOL_VERSION = 1
        private const val GRPC_STARTUP_TIMEOUT_SECONDS = 10L
        private val CONTEXT_ENVIRONMENT_KEYS = setOf(
            "CONTEXTENGINE_HTTP_URL",
            "CONTEXTENGINE_HTTP_API_KEY",
            "CONTEXTENGINE_EMBEDDING_API_KEY",
            "CONTEXTENGINE_EMBEDDING_BASE_URL",
            "CONTEXTENGINE_EMBEDDING_MODEL",
            "CONTEXTENGINE_NEURAL_RERANK",
            "CONTEXTENGINE_RERANK_API_KEY",
            "CONTEXTENGINE_RERANK_BASE_URL",
            "CONTEXTENGINE_RERANK_MODEL",
            "CONTEXTENGINE_EXTRA_ROOTS",
            "OPENAI_API_KEY",
            "OPENAI_BASE_URL",
            "OPENAI_EMBEDDING_MODEL",
            "OPENAI_RERANK_MODEL",
            "EMBEDDING_API_KEY",
        )
    }
}

@Serializable
data class ContextIndexRoot(
    val name: String,
    val path: String,
    val kind: String = "code",
)

data class ContextEngineRuntimeSettings(
    val nodePath: String,
    val environment: Map<String, String> = emptyMap(),
    val rpcMode: String = "grpc",
) {
    init {
        require(rpcMode == "grpc" || rpcMode == "jsonl") { "Unsupported ContextEngine RPC mode: $rpcMode" }
    }
}
