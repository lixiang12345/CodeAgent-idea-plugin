package com.codeagent.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Publishes the active editor's selection to the chat panel, mirroring the
 * original plugin's live selection tracking. The panel can then offer the
 * current selection as context instead of requiring a manual attach step.
 */
@Service(Service.Level.PROJECT)
class EditorSelectionTrackingService(private val project: Project) {
    private val listeners = CopyOnWriteArrayList<(EditorSelectionSnapshot?) -> Unit>()

    @Volatile
    private var current: EditorSelectionSnapshot? = null

    fun subscribe(listener: (EditorSelectionSnapshot?) -> Unit): () -> Unit {
        listeners += listener
        listener(current)
        return { listeners -= listener }
    }

    fun snapshot(): EditorSelectionSnapshot? = current

    fun selectionChanged(editor: Editor) {
        if (project.isDisposed) return
        publish(capture(editor))
    }

    fun clear() = publish(null)

    private fun publish(snapshot: EditorSelectionSnapshot?) {
        if (snapshot == current) return
        current = snapshot
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    private fun capture(editor: Editor): EditorSelectionSnapshot? {
        if (editor.project != project || editor.isDisposed) return null
        val model = editor.selectionModel
        if (!model.hasSelection()) return null
        val document = editor.document
        val file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(document)
            ?: return null
        if (!file.isInLocalFileSystem) return null
        // Expand to line boundaries so the panel shows whole lines, like the original.
        val startLine = document.getLineNumber(model.selectionStart)
        val endLine = document.getLineNumber(model.selectionEnd)
        val start = document.getLineStartOffset(startLine)
        val end = document.getLineEndOffset(endLine)
        val text = document.getText(com.intellij.openapi.util.TextRange(start, end)).take(MAX_SELECTION_CHARS)
        return EditorSelectionSnapshot(
            path = relativePath(file),
            fileName = file.name,
            startLine = startLine + 1,
            endLine = endLine + 1,
            text = text,
        )
    }

    private fun relativePath(file: VirtualFile): String {
        val base = project.basePath ?: return file.path
        return runCatching {
            Path.of(base).relativize(Path.of(file.path)).toString().takeIf { !it.startsWith("..") }
        }.getOrNull() ?: file.path
    }

    private companion object {
        const val MAX_SELECTION_CHARS = 4_000
    }
}

data class EditorSelectionSnapshot(
    val path: String,
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

/** Attaches a per-editor selection listener; registered as an editorFactoryListener. */
class CodeAgentEditorSelectionListener : com.intellij.openapi.editor.event.EditorFactoryListener {
    override fun editorCreated(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        if (project.isDisposed) return
        val service = project.service<EditorSelectionTrackingService>()
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(event: SelectionEvent) {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !editor.isDisposed) service.selectionChanged(editor)
                }
            }
        })
    }

    override fun editorReleased(event: com.intellij.openapi.editor.event.EditorFactoryEvent) {
        val project = event.editor.project ?: return
        if (project.isDisposed) return
        val service = project.service<EditorSelectionTrackingService>()
        if (service.snapshot() != null) service.clear()
    }
}
