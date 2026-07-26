package com.codeagent.plugin.ui

import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.InlineCompletionSettingsService
import com.codeagent.plugin.agent.InlineCompletionTelemetryService
import com.codeagent.plugin.context.ContextEngineService
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.event.MouseEvent
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

private const val CODEAGENT_STATUS_WIDGET_ID = "CodeAgentStatusBar"

class CodeAgentStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = CODEAGENT_STATUS_WIDGET_ID

    override fun getDisplayName(): String = "CodeAgent status"

    override fun isAvailable(project: Project): Boolean = !project.isDefault

    override fun createWidget(project: Project): StatusBarWidget = CodeAgentStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()

    override fun isEnabledByDefault(): Boolean = true
}

private class CodeAgentStatusBarWidget(
    private val project: Project,
) : StatusBarWidget {
    @Volatile
    private var state = CodeAgentStatusState.INITIALIZING

    @Volatile
    private var detail = "checking backend and project context"

    @Volatile
    private var backendOnline: Boolean? = null

    @Volatile
    private var statusBar: StatusBar? = null

    private val telemetry = service<InlineCompletionTelemetryService>()

    /**
     * Completion activity drives the widget directly; the scheduled task only
     * refreshes backend and context health, which have no push notification.
     */
    private val unsubscribeTelemetry: () -> Unit = telemetry.addActivityListener(::recompute)

    private val healthTask: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(::refreshHealth, 0, HEALTH_REFRESH_SECONDS, TimeUnit.SECONDS)

    override fun ID(): String = CODEAGENT_STATUS_WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = object : StatusBarWidget.IconPresentation {
        override fun getIcon(): Icon = when (state) {
            CodeAgentStatusState.INITIALIZING -> AllIcons.Process.Step_1
            CodeAgentStatusState.BACKEND_UNAVAILABLE -> AllIcons.General.Warning
            CodeAgentStatusState.GENERATING_COMPLETION -> AllIcons.Process.Step_passive
            CodeAgentStatusState.NO_COMPLETIONS -> AllIcons.General.BalloonInformation
            CodeAgentStatusState.COMPLETIONS_DISABLED -> AllIcons.Diff.GutterCheckBox
            CodeAgentStatusState.READY -> AllIcons.Actions.Lightning
        }

        override fun getTooltipText(): String = "${state.tooltip} — $detail"

        override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CodeAgent") ?: return@Consumer
            if (toolWindow.isVisible) toolWindow.hide() else toolWindow.show()
        }
    }

    override fun dispose() {
        healthTask.cancel(false)
        unsubscribeTelemetry()
        statusBar = null
    }

    private fun refreshHealth() {
        if (project.isDisposed) return
        val backend = project.service<AgentOrchestrator>().health().handle { health, error ->
            error == null && health?.ok == true
        }
        val context = project.service<ContextEngineService>().status().handle { value, error ->
            if (error == null) value else null
        }
        backend.thenCombine(context) { online, contextStatus ->
            val contextText = when {
                contextStatus == null -> "context unavailable"
                contextStatus.indexed && contextStatus.watching -> "context synced (${contextStatus.fileCount} files)"
                contextStatus.indexed -> "context indexed (${contextStatus.fileCount} files)"
                else -> "context not indexed"
            }
            backendOnline = online
            detail = "${if (online) "backend online" else "backend unavailable"}, $contextText"
            recompute()
        }
    }

    private fun recompute() {
        if (project.isDisposed) return
        val next = CodeAgentStatusState.resolve(
            initializing = false,
            backendOnline = backendOnline,
            generating = telemetry.isGenerating(),
            lastRequestSuggested = telemetry.lastRequestSuggested(),
            completionsEnabled = service<InlineCompletionSettingsService>().isEnabled(),
        )
        if (next == state) {
            statusBar?.let { bar -> ApplicationManager.getApplication().invokeLater { bar.updateWidget(ID()) } }
            return
        }
        state = next
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) statusBar?.updateWidget(ID())
        }
    }

    private companion object {
        const val HEALTH_REFRESH_SECONDS = 20L
    }
}
