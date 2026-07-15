package com.codeagent.plugin.bridge

import com.codeagent.plugin.agent.AgentAttachment
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

internal class AttachmentContextResolver(private val project: Project) {
    private val root = Path.of(requireNotNull(project.basePath) { "Project base path is unavailable" })
        .toAbsolutePath()
        .normalize()
    private val realRoot = root.toRealPath()

    fun activeEditorItem(): ContextItemDto = ApplicationManager.getApplication().runReadAction<ContextItemDto> {
        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor) {
            "Open a project text editor before attaching editor context"
        }
        val file = requireNotNull(FileDocumentManager.getInstance().getFile(editor.document)) {
            "The active editor is not backed by a project file"
        }
        val relative = requireNotNull(projectRelative(file.toNioPath())) {
            "Only active editors inside the current project can be attached"
        }
        ContextItemDto(
            id = "editor:$relative",
            label = "Active editor: ${file.name}",
            path = relative,
            kind = "ide_state",
        )
    }

    fun kindFor(path: String): String =
        if (imageMimeType(projectFile(path)) != null) "image" else "file"

    fun resolve(items: List<ContextItemDto>): List<AgentAttachment> {
        require(items.size <= MAX_ATTACHMENTS) { "Attach at most $MAX_ATTACHMENTS context items" }
        var totalBytes = 0L
        var imageCount = 0
        return items.map { item ->
            val attachment = when (item.kind) {
                "image" -> imageAttachment(item)
                "ide_state" -> editorAttachment(item)
                "file" -> {
                    val file = projectFile(item.path)
                    if (imageMimeType(file) != null) imageAttachment(item.copy(kind = "image")) else fileAttachment(item, file)
                }
                else -> error("Unsupported attachment type: ${item.kind}")
            }
            if (attachment.type == "image") {
                imageCount += 1
                require(imageCount <= MAX_IMAGES) { "Attach at most $MAX_IMAGES images per run" }
            }
            totalBytes += attachment.sizeBytes
            require(totalBytes <= MAX_TOTAL_BYTES) {
                "Selected attachments exceed the ${MAX_TOTAL_BYTES / 1_000} KB per-run limit"
            }
            attachment
        }
    }

    private fun fileAttachment(item: ContextItemDto, file: Path): AgentAttachment {
        val bytes = readBoundedBytes(file, MAX_FILE_BYTES, "File")
        val excerpt = textExcerpt(bytes)
        return AgentAttachment(
            type = "file",
            id = item.id,
            label = item.label,
            path = item.path,
            mimeType = mimeType(file),
            data = Base64.getEncoder().encodeToString(bytes),
            textExcerpt = excerpt.text,
            sizeBytes = bytes.size.toLong(),
            metadata = mapOf(
                "source" to "user_selected_file",
                "content_truncated" to excerpt.truncated.toString(),
            ),
        )
    }

    private fun imageAttachment(item: ContextItemDto): AgentAttachment {
        val file = projectFile(item.path)
        val mimeType = requireNotNull(imageMimeType(file)) {
            "Only PNG, JPEG, GIF, and WebP images can be attached as visual context"
        }
        val bytes = readBoundedBytes(file, MAX_IMAGE_BYTES, "Image")
        return AgentAttachment(
            type = "image",
            id = item.id,
            label = item.label,
            path = item.path,
            mimeType = mimeType,
            data = Base64.getEncoder().encodeToString(bytes),
            sizeBytes = bytes.size.toLong(),
            metadata = mapOf("source" to "user_selected_image"),
        )
    }

    private fun editorAttachment(item: ContextItemDto): AgentAttachment {
        val selected = ApplicationManager.getApplication().runReadAction<AgentAttachment?> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runReadAction null
            val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return@runReadAction null
            if (projectRelative(virtualFile.toNioPath()) != item.path.replace('\\', '/')) return@runReadAction null
            editorStateAttachment(item, virtualFile, editor)
        }
        return selected ?: diskEditorAttachment(item)
    }

    private fun editorStateAttachment(item: ContextItemDto, file: VirtualFile, editor: Editor): AgentAttachment {
        val document = editor.document
        val caretOffset = editor.caretModel.offset.coerceIn(0, document.textLength)
        val caretLine = document.getLineNumber(caretOffset)
        val lineCount = document.lineCount.coerceAtLeast(1)
        val selection = editor.selectionModel
        val hasSelection = selection.hasSelection()
        val startLine = if (hasSelection) {
            document.getLineNumber(selection.selectionStart.coerceIn(0, document.textLength))
        } else {
            (caretLine - EDITOR_CONTEXT_BEFORE_LINES).coerceAtLeast(0)
        }
        val endLine = if (hasSelection) {
            document.getLineNumber(selection.selectionEnd.coerceIn(0, document.textLength))
        } else {
            (caretLine + EDITOR_CONTEXT_AFTER_LINES).coerceAtMost(lineCount - 1)
        }
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        val source = if (hasSelection) {
            selection.selectedText.orEmpty()
        } else {
            document.charsSequence.subSequence(startOffset, endOffset).toString()
        }
        val excerpt = truncateText(source, MAX_EDITOR_TEXT_CHARS)
        val excerptText = excerpt.text.orEmpty()
        val caretColumn = caretOffset - document.getLineStartOffset(caretLine)
        val diagnostics = if (WolfTheProblemSolver.getInstance(project).isProblemFile(file)) "problems_reported" else "none_reported"
        return AgentAttachment(
            type = "ide_state",
            id = item.id,
            label = item.label,
            path = item.path,
            mimeType = mimeType(file.toNioPath()),
            textExcerpt = excerptText,
            sizeBytes = excerptText.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            metadata = mapOf(
                "source" to "active_editor",
                "scope" to if (hasSelection) "selection" else "caret_window",
                "line_start" to (startLine + 1).toString(),
                "line_end" to (endLine + 1).toString(),
                "caret_line" to (caretLine + 1).toString(),
                "caret_column" to (caretColumn + 1).toString(),
                "language" to file.fileType.name,
                "diagnostics" to diagnostics,
                "content_truncated" to excerpt.truncated.toString(),
            ),
        )
    }

    private fun diskEditorAttachment(item: ContextItemDto): AgentAttachment {
        val file = projectFile(item.path)
        val bytes = readBoundedBytes(file, MAX_FILE_BYTES, "Editor file")
        val excerpt = textExcerpt(bytes)
        return AgentAttachment(
            type = "ide_state",
            id = item.id,
            label = item.label,
            path = item.path,
            mimeType = mimeType(file),
            textExcerpt = excerpt.text ?: "The selected editor is no longer open and the file is not UTF-8 text.",
            sizeBytes = bytes.size.toLong(),
            metadata = mapOf(
                "source" to "editor_file_fallback",
                "scope" to "file_excerpt",
                "content_truncated" to excerpt.truncated.toString(),
            ),
        )
    }

    private fun projectFile(relative: String): Path {
        require(relative.isNotBlank()) { "Attachment path must not be blank" }
        val candidate = root.resolve(relative).normalize()
        require(candidate.startsWith(root)) { "Attachment path escapes the current project" }
        require(Files.isRegularFile(candidate)) { "Attachment is not a regular file: $relative" }
        val real = candidate.toRealPath()
        require(real.startsWith(realRoot)) { "Attachment resolves outside the current project" }
        return real
    }

    private fun projectRelative(path: Path): String? = runCatching {
        val real = path.toAbsolutePath().normalize().toRealPath()
        if (!real.startsWith(realRoot)) return null
        realRoot.relativize(real).toString().replace('\\', '/')
    }.getOrNull()

    private fun readBoundedBytes(file: Path, maxBytes: Long, label: String): ByteArray {
        val size = Files.size(file)
        require(size <= maxBytes) { "$label exceeds the ${maxBytes / 1_000} KB attachment limit: ${file.fileName}" }
        return Files.readAllBytes(file)
    }

    private fun textExcerpt(bytes: ByteArray): TextExcerpt {
        if (bytes.any { it == 0.toByte() }) return TextExcerpt(null, false)
        val text = String(bytes, StandardCharsets.UTF_8)
        if ('\uFFFD' in text) return TextExcerpt(null, false)
        return truncateText(text, MAX_FILE_TEXT_CHARS)
    }

    private fun truncateText(text: String, maxChars: Int): TextExcerpt {
        if (text.length <= maxChars) return TextExcerpt(text, false)
        val marker = "\n...[truncated user-selected context]...\n"
        val prefixLength = ((maxChars - marker.length) * 2 / 3).coerceAtLeast(0)
        val suffixLength = (maxChars - marker.length - prefixLength).coerceAtLeast(0)
        return TextExcerpt(text.take(prefixLength) + marker + text.takeLast(suffixLength), true)
    }

    private fun mimeType(file: Path): String =
        imageMimeType(file)
            ?: TEXT_MIME_TYPES[file.fileName.toString().substringAfterLast('.', "").lowercase()]
            ?: Files.probeContentType(file)
            ?: "application/octet-stream"

    private fun imageMimeType(file: Path): String? =
        IMAGE_MIME_TYPES[file.fileName.toString().substringAfterLast('.', "").lowercase()]

    private data class TextExcerpt(val text: String?, val truncated: Boolean)

    private companion object {
        const val MAX_ATTACHMENTS = 8
        const val MAX_IMAGES = 2
        const val MAX_FILE_BYTES = 128_000L
        const val MAX_IMAGE_BYTES = 512_000L
        const val MAX_TOTAL_BYTES = 1_000_000L
        const val MAX_FILE_TEXT_CHARS = 24_000
        const val MAX_EDITOR_TEXT_CHARS = 12_000
        const val EDITOR_CONTEXT_BEFORE_LINES = 36
        const val EDITOR_CONTEXT_AFTER_LINES = 72

        val IMAGE_MIME_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
        )
        val TEXT_MIME_TYPES = mapOf(
            "c" to "text/plain",
            "cc" to "text/plain",
            "cpp" to "text/plain",
            "css" to "text/css",
            "go" to "text/plain",
            "gradle" to "text/plain",
            "html" to "text/html",
            "java" to "text/plain",
            "js" to "text/javascript",
            "json" to "application/json",
            "kt" to "text/plain",
            "kts" to "text/plain",
            "md" to "text/markdown",
            "mjs" to "text/javascript",
            "py" to "text/plain",
            "rs" to "text/plain",
            "sh" to "text/plain",
            "sql" to "text/plain",
            "svelte" to "text/plain",
            "toml" to "text/plain",
            "ts" to "text/typescript",
            "tsx" to "text/typescript",
            "txt" to "text/plain",
            "xml" to "application/xml",
            "yaml" to "text/yaml",
            "yml" to "text/yaml",
        )
    }
}
