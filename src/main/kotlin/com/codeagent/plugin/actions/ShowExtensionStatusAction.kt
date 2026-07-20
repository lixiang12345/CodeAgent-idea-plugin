package com.codeagent.plugin.actions

import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.context.ContextEngineService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import java.util.concurrent.CompletableFuture

class ShowExtensionStatusAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val backend = project.service<AgentOrchestrator>().health()
            .handle { value, error -> if (error == null && value.ok) "Backend online" else "Backend unavailable" }
        val context = project.service<ContextEngineService>().status()
            .handle { value, error ->
                if (error != null) "Context unavailable"
                else if (value.indexed) "Context indexed: ${value.fileCount} files, ${value.chunkCount} chunks"
                else "Context not indexed"
            }
        CompletableFuture.allOf(backend, context).whenComplete { _, _ ->
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeAgent")
                .createNotification(
                    "CodeAgent status",
                    "${backend.join()}<br>${context.join()}",
                    NotificationType.INFORMATION,
                )
                .notify(project)
        }
    }
}
