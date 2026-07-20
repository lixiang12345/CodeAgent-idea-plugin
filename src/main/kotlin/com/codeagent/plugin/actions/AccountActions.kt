package com.codeagent.plugin.actions

import com.codeagent.plugin.ui.CodeAgentUiRequest
import com.codeagent.plugin.ui.CodeAgentUiService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

abstract class CodeAgentUiAction(private val request: CodeAgentUiRequest) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<CodeAgentUiService>()?.request(request)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}

class SignInAction : CodeAgentUiAction(CodeAgentUiRequest("signIn"))

class SignOutAction : CodeAgentUiAction(CodeAgentUiRequest("signOut"))

class RecoverCloudConversationsAction : CodeAgentUiAction(CodeAgentUiRequest("recoverConversations"))

class ManageAccountAction : CodeAgentUiAction(CodeAgentUiRequest("openSettings", "Account"))
