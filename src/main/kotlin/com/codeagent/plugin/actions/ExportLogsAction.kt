package com.codeagent.plugin.actions

import com.codeagent.plugin.diagnostics.ExtensionStatusReport
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ExportLogsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Export CodeAgent Logs")
            .withDescription("Choose a folder for the local diagnostic archive")
        FileChooser.chooseFile(descriptor, project, null) { directory ->
            AppExecutorUtil.getAppExecutorService().execute {
                val statusReport = runCatching {
                    ExtensionStatusReport.collect(project).get(STATUS_REPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.getOrElse { error ->
                    "CodeAgent extension status unavailable (${error.message ?: error.javaClass.simpleName})"
                }
                runCatching {
                    SupportBundleExporter.export(
                        destination = directory.toNioPath(),
                        logRoot = Path.of(PathManager.getLogPath()),
                        statusReport = statusReport,
                        pluginVersion = pluginVersion(),
                        ideName = ApplicationInfo.getInstance().fullApplicationName,
                        ideBuild = ApplicationInfo.getInstance().build.toString(),
                    )
                }
                    .onSuccess { path -> notify(project, "CodeAgent logs exported", path.toString(), NotificationType.INFORMATION) }
                    .onFailure { error ->
                        notify(project, "CodeAgent log export failed", error.message ?: "Archive creation failed", NotificationType.ERROR)
                    }
            }
        }
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"

    private fun notify(
        project: com.intellij.openapi.project.Project,
        title: String,
        content: String,
        type: NotificationType,
    ) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("CodeAgent")
                .createNotification(title, content, type)
                .notify(project)
        }
    }

    private companion object {
        const val PLUGIN_ID = "com.codeagent.workspace.idea"
        const val STATUS_REPORT_TIMEOUT_SECONDS = 20L
    }
}
