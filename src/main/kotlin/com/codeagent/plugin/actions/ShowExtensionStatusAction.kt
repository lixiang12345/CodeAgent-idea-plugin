package com.codeagent.plugin.actions

import com.codeagent.plugin.diagnostics.ExtensionStatusReport
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

class ShowExtensionStatusAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ExtensionStatusReport.collect(project).whenComplete { report, error ->
            val text = report ?: "CodeAgent extension status unavailable (${error?.message ?: "unknown failure"})"
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) ExtensionStatusDialog(project, text).show()
            }
        }
    }
}

private class ExtensionStatusDialog(project: Project, private val report: String) : DialogWrapper(project) {
    init {
        title = "CodeAgent Extension Status"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val area = JBTextArea(report)
        area.isEditable = false
        area.font = Font(Font.MONOSPACED, Font.PLAIN, area.font.size)
        area.caretPosition = 0
        return JBScrollPane(area).apply { preferredSize = JBUI.size(640, 360) }
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction("Copy") {
            override fun doAction(e: ActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(report))
            }
        },
        okAction,
    )
}
