package com.codeagent.plugin.agent

import com.codeagent.plugin.bridge.ImageCanvasSnapshotDto
import com.codeagent.plugin.bridge.ImageItemDto
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

@Service(Service.Level.PROJECT)
class ImageCanvasService(private val project: Project) {
    private val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    private val guard = ProjectPathGuard(root)
    private val properties = PropertiesComponent.getInstance(project)

    fun savedDirectory(): String = properties.getValue(DIRECTORY_KEY).orEmpty()

    fun selectDirectory(path: Path): ImageCanvasSnapshotDto {
        val absolute = path.toAbsolutePath().normalize()
        require(absolute.startsWith(root)) { "Image Canvas directories must be inside the current project" }
        require(Files.isDirectory(absolute)) { "Image Canvas directory does not exist" }
        val relative = root.relativize(absolute).toString().replace('\\', '/').ifBlank { "." }
        properties.setValue(DIRECTORY_KEY, relative)
        return scan(relative)
    }

    fun scan(relativeDirectory: String = savedDirectory()): ImageCanvasSnapshotDto {
        if (relativeDirectory.isBlank()) return ImageCanvasSnapshotDto()
        return runCatching {
            val directory = guard.existingDirectory(relativeDirectory)
            var totalBytes = 0L
            var truncated = false
            val images = mutableListOf<ImageItemDto>()
            Files.walk(directory, MAX_DEPTH).use { stream ->
                val iterator = stream.filter { it.isRegularFile() }
                    .filter { !Files.isSymbolicLink(it) }
                    .filter { it.extension.lowercase() in MIME_TYPES }
                    .sorted()
                    .iterator()
                while (iterator.hasNext()) {
                    val file = iterator.next()
                    val size = Files.size(file)
                    if (size > MAX_IMAGE_BYTES) continue
                    if (images.size >= MAX_IMAGES || totalBytes + size > MAX_TOTAL_BYTES) {
                        truncated = true
                        break
                    }
                    val relative = root.relativize(file).toString().replace('\\', '/')
                    val mime = requireNotNull(MIME_TYPES[file.extension.lowercase()])
                    val data = Base64.getEncoder().encodeToString(Files.readAllBytes(file))
                    images += ImageItemDto(relative, file.fileName.toString(), relative, "data:$mime;base64,$data", size)
                    totalBytes += size
                }
            }
            ImageCanvasSnapshotDto(relativeDirectory, images, truncated)
        }.getOrElse { error ->
            ImageCanvasSnapshotDto(relativeDirectory, error = error.message ?: "Image Canvas unavailable")
        }
    }

    fun projectPath(relative: String): Path = guard.existingFile(relative)

    companion object {
        private const val DIRECTORY_KEY = "codeagent.imageCanvas.directory"
        private const val MAX_DEPTH = 4
        private const val MAX_IMAGES = 40
        private const val MAX_IMAGE_BYTES = 2_000_000L
        private const val MAX_TOTAL_BYTES = 10_000_000L
        private val MIME_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
        )
    }
}
