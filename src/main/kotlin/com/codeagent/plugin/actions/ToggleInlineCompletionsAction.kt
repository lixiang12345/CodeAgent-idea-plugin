package com.codeagent.plugin.actions

import com.codeagent.plugin.agent.InlineCompletionSettingsService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service

class ToggleInlineCompletionsAction : ToggleAction() {
    override fun isSelected(e: AnActionEvent): Boolean =
        service<InlineCompletionSettingsService>().isEnabled()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        service<InlineCompletionSettingsService>().setEnabled(state)
    }
}
