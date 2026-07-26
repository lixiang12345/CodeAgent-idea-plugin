package com.codeagent.plugin.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CodeAgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CodeAgentPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setAdditionalGearActions(gearActions())
    }

    override fun shouldBeAvailable(project: Project): Boolean = !project.isDefault

    private fun gearActions(): DefaultActionGroup {
        val actionManager = ActionManager.getInstance()
        val group = DefaultActionGroup()
        GEAR_ACTION_IDS.forEach { id ->
            if (id == SEPARATOR) group.add(Separator.getInstance())
            else actionManager.getAction(id)?.let(group::add)
        }
        return group
    }

    private companion object {
        const val SEPARATOR = "-"
        val GEAR_ACTION_IDS = listOf(
            "CodeAgent.OpenSettingsWebview",
            "CodeAgent.ShowExtensionStatus",
            "CodeAgent.ReindexContext",
            "CodeAgent.GenerateSyncReport",
            "CodeAgent.ExportLogs",
            SEPARATOR,
            "CodeAgent.ToggleInlineCompletions",
            SEPARATOR,
            "CodeAgent.ShowDocs",
            "CodeAgent.ShowHelp",
            SEPARATOR,
            "CodeAgent.ManageAccount",
            "CodeAgent.SignIn",
            "CodeAgent.SignOut",
        )
    }
}
