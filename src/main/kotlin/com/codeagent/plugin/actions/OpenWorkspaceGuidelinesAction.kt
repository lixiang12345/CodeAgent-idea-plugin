package com.codeagent.plugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal fun ensureWorkspaceGuidelinesFile(root: Path): Path {
    val directory = root.resolve(".codeagent")
    val guidelines = directory.resolve("guidelines.md")
    Files.createDirectories(directory)
    Files.newOutputStream(
        guidelines,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
    ).use { }
    require(Files.isRegularFile(guidelines)) { "Workspace guidelines path is not a regular file" }
    return guidelines
}

internal fun openWorkspaceGuidelines(project: Project) {
    val root = project.basePath?.let(Path::of) ?: return
    AppExecutorUtil.getAppExecutorService().execute {
        runCatching { ensureWorkspaceGuidelinesFile(root) }
            .onSuccess { path ->
                ApplicationManager.getApplication().invokeLater {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    if (file != null && !project.isDisposed) FileEditorManager.getInstance(project).openFile(file, true)
                }
            }.onFailure { error ->
                notify(project, "Could not open workspace guidelines", error.message ?: "File creation failed")
            }
    }
}

class OpenWorkspaceGuidelinesAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openWorkspaceGuidelines(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }
}

private fun notify(project: Project, title: String, content: String) {
    NotificationGroupManager.getInstance().getNotificationGroup("CodeAgent")
        .createNotification(title, content, NotificationType.ERROR)
        .notify(project)
}
