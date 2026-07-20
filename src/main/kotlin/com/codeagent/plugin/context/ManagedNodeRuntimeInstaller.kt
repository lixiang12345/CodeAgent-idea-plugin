package com.codeagent.plugin.context

import com.codeagent.plugin.settings.CodeAgentSettings
import com.intellij.openapi.application.PathManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

object ManagedNodeRuntimeInstaller {
    private const val MAX_MANIFEST_BYTES = 1_000_000L
    private const val MAX_ARCHIVE_BYTES = 400L * 1024L * 1024L
    private const val MAX_EXTRACTED_BYTES = 700L * 1024L * 1024L
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun resolve(settings: CodeAgentSettings): String = runCatching {
        NodeRuntimeLocator.find(settings.nodePath)
    }.getOrElse {
        install(settings)
    }

    fun install(settings: CodeAgentSettings): String {
        val manifestUri = URI.create("${settings.backendUrl.trimEnd('/')}/v1/runtime/manifest")
        requireSecureEndpoint(manifestUri, "Runtime manifest URL")
        val request = HttpRequest.newBuilder(manifestUri)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .apply { settings.backendToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") } }
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        require(response.statusCode() in 200..299) { "Runtime manifest returned HTTP ${response.statusCode()}" }
        require(response.body().size <= MAX_MANIFEST_BYTES) { "Runtime manifest is too large" }
        val manifest = json.decodeFromString<RuntimeManifest>(response.body().decodeToString())
        val runtime = manifest.runtimes.firstOrNull { it.platform == platform() && it.arch == architecture() }
            ?: error("No managed Node runtime for ${platform()}/${architecture()}")
        requireSecureDownload(URI.create(runtime.url))
        requireSupportedArchive(runtime.archive)
        require(runtime.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Managed runtime checksum is invalid" }
        val artifact = download(runtime)
        require(sha256(artifact) == runtime.sha256.lowercase()) { "Managed runtime checksum mismatch" }
        val target = Path.of(PathManager.getSystemPath(), "codeagent", "runtimes", "${runtime.version}-${runtime.platform}-${runtime.arch}")
        val temporary = target.resolveSibling(".${target.fileName}.tmp-${System.nanoTime()}")
        Files.createDirectories(temporary)
        try {
            when (runtime.archive) {
                "raw" -> writeExecutable(resolveExecutable(temporary, runtime.executable), artifact)
                "zip" -> extractZip(artifact, temporary)
            }
            val executable = resolveExecutable(temporary, runtime.executable)
            require(Files.isRegularFile(executable)) {
                "Managed runtime executable '${runtime.executable}' was not found"
            }
            Files.createDirectories(target.parent)
            if (Files.exists(target)) deleteTree(target)
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            return target.resolve(runtime.executable).toString()
        } catch (error: Throwable) {
            deleteTree(temporary)
            throw error
        }
    }

    internal fun selectRuntime(manifest: RuntimeManifest, osName: String, archName: String): RuntimeArtifact? =
        manifest.runtimes.firstOrNull { it.platform == osName && it.arch == archName }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun download(runtime: RuntimeArtifact): ByteArray {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(runtime.url)).timeout(Duration.ofMinutes(5)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        require(response.statusCode() in 200..299) { "Managed runtime download returned HTTP ${response.statusCode()}" }
        require(response.body().size <= MAX_ARCHIVE_BYTES) { "Managed runtime archive is too large" }
        return response.body()
    }

    internal fun extractZip(bytes: ByteArray, target: Path) {
        var extractedBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = target.resolve(entry.name).normalize()
                require(output.startsWith(target)) { "Managed runtime archive contains a path traversal entry" }
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                    continue
                }
                Files.createDirectories(requireNotNull(output.parent))
                Files.newOutputStream(output).use { outputStream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        extractedBytes += read
                        require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                            "Managed runtime extracted content is too large"
                        }
                        outputStream.write(buffer, 0, read)
                    }
                }
                markExecutable(output)
            }
        }
    }

    private fun writeExecutable(path: Path, bytes: ByteArray) {
        Files.createDirectories(requireNotNull(path.parent))
        Files.write(path, bytes)
        markExecutable(path)
    }

    private fun markExecutable(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(path, setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ))
        }
    }

    private fun resolveExecutable(root: Path, executable: String): Path {
        require(executable.isNotBlank()) { "Managed runtime executable is required" }
        val resolved = root.resolve(executable).normalize()
        require(resolved.startsWith(root) && !resolved.equals(root)) {
            "Managed runtime executable must stay inside the runtime directory"
        }
        return resolved
    }

    internal fun requireSupportedArchive(archive: String) {
        require(archive == "raw" || archive == "zip") {
            "Unsupported managed runtime archive '$archive'"
        }
    }

    private fun requireSecureDownload(uri: URI) {
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) {
            "Managed runtime downloads must use HTTPS without embedded credentials"
        }
    }

    private fun requireSecureEndpoint(uri: URI, label: String) {
        val loopback = uri.host in setOf("127.0.0.1", "localhost", "::1")
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) { "$label must use HTTPS unless loopback" }
        require(uri.host != null && uri.userInfo == null) { "$label is invalid" }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun platform(): String = when {
        System.getProperty("os.name").startsWith("Mac", true) -> "darwin"
        System.getProperty("os.name").startsWith("Windows", true) -> "win32"
        else -> "linux"
    }

    private fun architecture(): String = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        else -> "x64"
    }
}

@Serializable
data class RuntimeManifest(val version: Int = 1, val runtimes: List<RuntimeArtifact> = emptyList())

@Serializable
data class RuntimeArtifact(
    val platform: String,
    val arch: String,
    val version: String,
    val url: String,
    val sha256: String,
    val executable: String,
    val archive: String = "zip",
)
