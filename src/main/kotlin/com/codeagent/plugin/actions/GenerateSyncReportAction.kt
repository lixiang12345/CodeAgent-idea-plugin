package com.codeagent.plugin.actions

import com.codeagent.plugin.context.ContextEngineService
import com.codeagent.plugin.context.ContextStatus
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.testFramework.LightVirtualFile
import java.time.Instant

class GenerateSyncReportAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<ContextEngineService>().status().whenComplete { status, error ->
            if (error != null || status == null) {
                notify(project, "CodeAgent sync report failed", error?.message ?: "Context status unavailable")
                return@whenComplete
            }
            val settings = service<CodeAgentSettingsService>().snapshot()
            val report = buildReport(project.name, settings.contextMode, status)
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    FileEditorManager.getInstance(project).openFile(
                        LightVirtualFile("CodeAgent Sync Report.md", PlainTextFileType.INSTANCE, report),
                        true,
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    private fun buildReport(projectName: String, contextMode: String, status: ContextStatus): String = """
        # CodeAgent sync report

        Generated: ${Instant.now()}
        Project: $projectName
        Context mode: $contextMode

        ## Index

        - Indexed: ${status.indexed}
        - Root: `${status.root}`
        - Roots: ${status.roots.size}
        - Files: ${status.fileCount}
        - Chunks: ${status.chunkCount}
        - Embeddings: ${status.hasEmbeddings}
        - Last indexed: ${status.lastIndexedAt ?: "never"}

        ## Incremental synchronization

        - Watching: ${status.watching}
        - Watched roots: ${status.watchedRootCount}/${status.roots.size.coerceAtLeast(1)}
        - Debounce: ${status.watchDebounceMs} ms
        - Pending changes: ${status.pendingChanges}
        - Automatic index runs: ${status.automaticIndexRuns}
        - Last automatic index: ${status.lastAutomaticIndexAt ?: "never"}
        - Last duration: ${status.lastAutomaticDurationMs?.let { "$it ms" } ?: "unknown"}
        - Last files indexed: ${status.lastAutomaticFilesIndexed}
        - Last files removed: ${status.lastAutomaticFilesRemoved}
        - Last chunks written: ${status.lastAutomaticChunksWritten}
        - Watch error: ${status.watchError ?: "none"}
    """.trimIndent()

    private fun notify(project: com.intellij.openapi.project.Project, title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("CodeAgent")
                .createNotification(title, content, NotificationType.ERROR)
                .notify(project)
        }
    }
}
