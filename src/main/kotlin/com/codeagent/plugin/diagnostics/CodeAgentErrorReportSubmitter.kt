package com.codeagent.plugin.diagnostics

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class CodeAgentErrorReportSubmitter : ErrorReportSubmitter() {
    override fun getReportActionText(): String = "Save CodeAgent Diagnostic Report"

    override fun getPrivacyNoticeText(): String =
        "The report is written to the local IDE log directory and is never uploaded automatically."

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean = runCatching {
        val directory = Path.of(PathManager.getLogPath(), "codeagent-reports")
        Files.createDirectories(directory)
        val report = directory.resolve("codeagent-error-${System.currentTimeMillis()}.txt")
        val content = buildString {
            appendLine("CodeAgent local diagnostic report")
            appendLine("Generated: ${Instant.now()}")
            appendLine("IDE: ${ApplicationInfo.getInstance().fullApplicationName}")
            appendLine("Build: ${ApplicationInfo.getInstance().build}")
            if (!additionalInfo.isNullOrBlank()) {
                appendLine()
                appendLine("User description:")
                appendLine(additionalInfo.take(MAX_DESCRIPTION_CHARS))
            }
            events.take(MAX_EVENTS).forEachIndexed { index, event ->
                appendLine()
                appendLine("Event ${index + 1}: ${(event.message ?: "No message").take(MAX_MESSAGE_CHARS)}")
                appendLine(event.throwableText.take(MAX_STACK_CHARS))
            }
        }
        Files.writeString(report, content)
        consumer.consume(
            SubmittedReportInfo(report.toUri().toString(), report.fileName.toString(), SubmittedReportInfo.SubmissionStatus.NEW_ISSUE),
        )
        true
    }.getOrElse {
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED))
        false
    }

    private companion object {
        const val MAX_EVENTS = 20
        const val MAX_DESCRIPTION_CHARS = 8_000
        const val MAX_MESSAGE_CHARS = 4_000
        const val MAX_STACK_CHARS = 40_000
    }
}
