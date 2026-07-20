package com.codeagent.plugin.actions

import com.codeagent.plugin.settings.CodeAgentConfigurable
import com.codeagent.plugin.ui.CodeAgentUiRequest
import com.codeagent.plugin.ui.CodeAgentUiService
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction

class OpenNativeSettingsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, CodeAgentConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class OpenSettingsWebviewAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<CodeAgentUiService>()?.request(CodeAgentUiRequest("openSettings", "Home"))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class OpenSettingsSectionAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val actionId = ActionManager.getInstance().getId(this)
        val section = SETTINGS_SECTIONS[actionId] ?: error("Unknown CodeAgent settings action: $actionId")
        project.service<CodeAgentUiService>().request(CodeAgentUiRequest("openSettings", section))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    private companion object {
        val SETTINGS_SECTIONS = mapOf(
            "CodeAgent.OpenSettingsHome" to "Home",
            "CodeAgent.OpenSettingsServices" to "Services",
            "CodeAgent.OpenSettingsMcpServers" to "MCP Servers",
            "CodeAgent.OpenSettingsGuidelines" to "Rules & Guidelines",
            "CodeAgent.OpenSettingsMemories" to "Memories",
            "CodeAgent.OpenSettingsCommands" to "Commands",
            "CodeAgent.OpenSettingsSkills" to "Skills",
            "CodeAgent.OpenSettingsHooks" to "Hooks",
            "CodeAgent.OpenSettingsAgents" to "Agents",
            "CodeAgent.OpenSettingsPlugins" to "Plugins",
            "CodeAgent.OpenSettingsUserExperience" to "User Experience",
            "CodeAgent.OpenSettingsFeatureFlags" to "Feature Flags",
            "CodeAgent.OpenSettingsBeta" to "Beta",
            "CodeAgent.OpenSettingsAccount" to "Account",
            "CodeAgent.OpenSettingsSubscription" to "Subscription",
        )
    }
}
