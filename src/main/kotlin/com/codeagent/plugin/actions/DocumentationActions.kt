package com.codeagent.plugin.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class ShowDocsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(DOCUMENTATION_URL)
}

class ShowHelpAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(HELP_URL)
}

private const val DOCUMENTATION_URL = "https://github.com/lixiang12345/CodeAgent-idea-plugin#readme"
private const val HELP_URL = "https://github.com/lixiang12345/CodeAgent-idea-plugin/issues"
