package com.codeagent.plugin.agent

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

@Service(Service.Level.PROJECT)
class ChangeReviewService(private val project: Project) {
    private val root = Path.of(requireNotNull(project.basePath)).toAbsolutePath().normalize()
    private val guard = ProjectPathGuard(root)
    private val changes = linkedMapOf<String, StoredChange>()

    @Synchronized
    fun register(toolId: String, change: FileChange) {
        changes[toolId] = StoredChange(change)
        while (changes.size > MAX_CHANGES) changes.remove(changes.keys.first())
    }

    @Synchronized
    fun path(toolId: String): String? = changes[toolId]?.change?.path

    @Synchronized
    fun canRevert(toolId: String): Boolean = changes[toolId]?.reverted == false

    fun openDiff(toolId: String) {
        val change = synchronized(this) { requireNotNull(changes[toolId]?.change) { "Change is no longer available" } }
        ApplicationManager.getApplication().invokeLater {
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(
                "CodeAgent change: ${change.path}",
                factory.create(change.before.orEmpty()),
                factory.create(change.after),
                if (change.before == null) "Before (new file)" else "Before",
                "After",
            )
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    fun revert(toolId: String): CompletableFuture<Boolean> {
        val stored = synchronized(this) {
            requireNotNull(changes[toolId]) { "Change is no longer available" }.also {
                require(!it.reverted) { "Change was already reverted" }
            }
        }
        val future = CompletableFuture<Boolean>()
        ApplicationManager.getApplication().invokeLater {
            val answer = Messages.showYesNoDialog(
                project,
                "Revert CodeAgent's change to ${stored.change.path}?",
                "Revert CodeAgent Change",
                "Revert",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) {
                future.complete(false)
                return@invokeLater
            }

            runCatching { revertInWriteAction(stored.change) }
                .onSuccess {
                    synchronized(this) { stored.reverted = true }
                    future.complete(true)
                }
                .onFailure(future::completeExceptionally)
        }
        return future
    }

    private fun revertInWriteAction(change: FileChange) {
        val file = guard.pathForWrite(change.path)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
            ?: error("${change.path} no longer exists")
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
        val current = document?.text ?: Files.readString(file, StandardCharsets.UTF_8)
        require(current == change.after) {
            "${change.path} changed after this agent edit; refusing to overwrite newer content"
        }

        WriteCommandAction.runWriteCommandAction(project) {
            if (change.before == null) {
                virtualFile.delete(this)
            } else if (document != null) {
                document.setText(change.before)
            } else {
                VfsUtil.saveText(virtualFile, change.before)
            }
        }
    }

    private data class StoredChange(
        val change: FileChange,
        var reverted: Boolean = false,
    )

    companion object {
        private const val MAX_CHANGES = 100
    }
}
