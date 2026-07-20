package com.codeagent.plugin.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Files
import java.nio.file.Path

class OpenWorkspaceGuidelinesAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = project.basePath?.let(Path::of) ?: return
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching {
                val rules = root.resolve(".codeagent/rules")
                val guidelines = rules.resolve("workspace.md")
                Files.createDirectories(rules)
                if (Files.notExists(guidelines)) {
                    Files.writeString(
                        guidelines,
                        """# Workspace guidelines

Describe repository conventions, architecture constraints, and verification commands here.
""",
                    )
                }
                guidelines
            }.onSuccess { path ->
                ApplicationManager.getApplication().invokeLater {
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                    if (file != null && !project.isDisposed) FileEditorManager.getInstance(project).openFile(file, true)
                }
            }.onFailure { error ->
                notify(project, "Could not open workspace guidelines", error.message ?: "File creation failed")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    private fun notify(project: com.intellij.openapi.project.Project, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("CodeAgent")
            .createNotification(title, content, NotificationType.ERROR)
            .notify(project)
    }
}
