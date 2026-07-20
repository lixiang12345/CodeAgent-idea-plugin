package com.codeagent.plugin.actions

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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportLogsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Export CodeAgent Logs")
            .withDescription("Choose a folder for the local diagnostic archive")
        FileChooser.chooseFile(descriptor, project, null) { directory ->
            AppExecutorUtil.getAppExecutorService().execute {
                runCatching { exportLogs(directory.toNioPath()) }
                    .onSuccess { path -> notify(project, "CodeAgent logs exported", path.toString(), NotificationType.INFORMATION) }
                    .onFailure { error ->
                        notify(project, "CodeAgent log export failed", error.message ?: "Archive creation failed", NotificationType.ERROR)
                    }
            }
        }
    }

    private fun exportLogs(destination: Path): Path {
        val timestamp = FILE_TIMESTAMP.format(Instant.now())
        val output = destination.resolve("codeagent-logs-$timestamp.zip")
        val logRoot = Path.of(PathManager.getLogPath()).toAbsolutePath().normalize()
        var totalBytes = 0L
        var filesWritten = 0
        val skipped = mutableListOf<String>()

        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            val metadata = buildString {
                appendLine("CodeAgent diagnostic archive")
                appendLine("Generated: ${Instant.now()}")
                appendLine("Plugin: ${pluginVersion()}")
                appendLine("IDE: ${ApplicationInfo.getInstance().fullApplicationName}")
                appendLine("Build: ${ApplicationInfo.getInstance().build}")
                appendLine("Log root: $logRoot")
            }
            zip.putNextEntry(ZipEntry("codeagent-environment.txt"))
            zip.write(metadata.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            if (Files.isDirectory(logRoot)) {
                Files.walk(logRoot).use { stream ->
                    stream.filter(Files::isRegularFile).sorted().forEach { file ->
                        val size = runCatching { Files.size(file) }.getOrDefault(0L)
                        val relative = logRoot.relativize(file).toString().replace('\\', '/')
                        if (size > MAX_FILE_BYTES || totalBytes + size > MAX_ARCHIVE_INPUT_BYTES) {
                            skipped += "$relative ($size bytes)"
                            return@forEach
                        }
                        zip.putNextEntry(ZipEntry("logs/$relative"))
                        Files.newInputStream(file).use { it.copyTo(zip) }
                        zip.closeEntry()
                        totalBytes += size
                        filesWritten += 1
                    }
                }
            }

            val summary = buildString {
                appendLine("Files included: $filesWritten")
                appendLine("Uncompressed bytes included: $totalBytes")
                if (skipped.isNotEmpty()) {
                    appendLine("Skipped by size limit:")
                    skipped.forEach { appendLine("- $it") }
                }
            }
            zip.putNextEntry(ZipEntry("export-summary.txt"))
            zip.write(summary.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return output
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
        const val MAX_FILE_BYTES = 25L * 1024 * 1024
        const val MAX_ARCHIVE_INPUT_BYTES = 200L * 1024 * 1024
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}
