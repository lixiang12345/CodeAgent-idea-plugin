package com.codeagent.plugin.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Keeps the IDE-facing context status fresh while the sidecar owns actual indexing. */
@Service(Service.Level.PROJECT)
class CodeAgentContextSyncService(private val project: Project) : Disposable {
    private val context = project.service<ContextEngineService>()
    private val root = project.basePath?.let { Path.of(it).toAbsolutePath().normalize() }
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private var refreshTask: ScheduledFuture<*>? = null

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document)
                if (file != null && isProjectFile(file.path)) scheduleRefresh()
            }
        })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                if (isProjectFile(event.newFile?.path)) scheduleRefresh()
            }
        })
    }

    override fun dispose() {
        refreshTask?.cancel(false)
        refreshTask = null
    }

    fun externalFilesChanged(paths: List<String>) {
        if (paths.any(::isProjectFile)) scheduleRefresh()
    }

    fun editorStateChanged() = scheduleRefresh()

    private fun scheduleRefresh() {
        refreshTask?.cancel(false)
        refreshTask = scheduler.schedule({
            context.status().whenComplete { _, error ->
                if (error != null && !project.isDisposed) LOG.debug("Context status refresh deferred", error)
            }
        }, REFRESH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    private fun isProjectFile(path: String?): Boolean {
        if (path == null || root == null) return false
        val candidate = runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull() ?: return false
        return candidate.startsWith(root)
    }

    private companion object {
        const val REFRESH_DELAY_MS = 900L
        val LOG = logger<CodeAgentContextSyncService>()
    }
}
