package com.codeagent.plugin.actions

import com.codeagent.plugin.context.ContextEngineService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class ReindexContextAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        notify(project, "Context reindex started", "CodeAgent is rebuilding the project context index.", NotificationType.INFORMATION)
        project.service<ContextEngineService>().index { }.whenComplete { result, error ->
            if (error != null) {
                notify(project, "Context reindex failed", error.message ?: "ContextEngine failed", NotificationType.ERROR)
            } else {
                notify(
                    project,
                    "Context reindex complete",
                    "Indexed ${result.filesIndexed} files and wrote ${result.chunksWritten} chunks.",
                    NotificationType.INFORMATION,
                )
            }
        }
    }

    private fun notify(project: com.intellij.openapi.project.Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("CodeAgent")
            .createNotification(title, content, type)
            .notify(project)
    }
}
