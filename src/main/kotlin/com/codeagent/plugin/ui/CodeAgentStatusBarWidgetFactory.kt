package com.codeagent.plugin.ui

import com.codeagent.plugin.agent.AgentOrchestrator
import com.codeagent.plugin.agent.InlineCompletionSettingsService
import com.codeagent.plugin.context.ContextEngineService
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
    private var label = "CodeAgent"

    @Volatile
    private var tooltip = "CodeAgent: checking backend and project context"

    @Volatile
    private var statusBar: StatusBar? = null

    private val refreshTask: ScheduledFuture<*> = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(::refresh, 0, REFRESH_SECONDS, TimeUnit.SECONDS)

    override fun ID(): String = CODEAGENT_STATUS_WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = object : StatusBarWidget.TextPresentation {
        override fun getText(): String = label

        override fun getAlignment(): Float = 0f

        override fun getTooltipText(): String = tooltip

        override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
            ToolWindowManager.getInstance(project).getToolWindow("CodeAgent")?.show()
        }

    }

    override fun dispose() {
        refreshTask.cancel(false)
        statusBar = null
    }

    private fun refresh() {
        if (project.isDisposed) return
        val backend = project.service<AgentOrchestrator>().health().handle { health, error ->
            error == null && health?.ok == true
        }
        val context = project.service<ContextEngineService>().status().handle { value, error ->
            if (error == null) value else null
        }
        backend.thenCombine(context) { backendOnline, contextStatus ->
            val contextText = when {
                contextStatus == null -> "context unavailable"
                contextStatus.indexed && contextStatus.watching -> "context synced (${contextStatus.fileCount} files)"
                contextStatus.indexed -> "context indexed (${contextStatus.fileCount} files)"
                else -> "context not indexed"
            }
            val completions = if (service<InlineCompletionSettingsService>().isEnabled()) "inline on" else "inline off"
            val nextLabel = if (backendOnline) "CodeAgent" else "CodeAgent !"
            val nextTooltip = "CodeAgent: ${if (backendOnline) "backend online" else "backend unavailable"}, $contextText, $completions"
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) update(nextLabel, nextTooltip)
            }
        }
    }

    private fun update(nextLabel: String, nextTooltip: String) {
        label = nextLabel
        tooltip = nextTooltip
        statusBar?.updateWidget(ID())
    }

    private companion object {
        const val REFRESH_SECONDS = 20L
    }
}
