package com.codeagent.plugin.settings

import com.codeagent.plugin.agent.InlineCompletionSettingsService
import com.codeagent.plugin.context.NodeRuntimeLocator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel

class CodeAgentConfigurable : Configurable {
    private val backendUrl = JBTextField()
    private val nodePath = JBTextField()
    private val contextMode = ComboBox(arrayOf("remote-http", "lexical", "private-semantic"))
    private val contextHttpUrl = JBTextField()
    private val autoApproveReadOnly = JBCheckBox("Auto-approve read-only tools")
    private val desktopNotifications = JBCheckBox("Show desktop notifications")
    private val inlineCompletions = JBCheckBox("Enable inline completions")
    private val showTimestamps = JBCheckBox("Show message timestamps")
    private val showRunTelemetry = JBCheckBox("Show run telemetry")
    private val detectNodeButton = JButton("Auto-detect")

    private var panel: JPanel? = null

    override fun getDisplayName(): String = "CodeAgent"

    override fun createComponent(): JComponent {
        val root = JPanel(GridBagLayout())
        root.border = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8)
        detectNodeButton.addActionListener { detectNodeRuntime() }
        addRow(root, 0, "Backend URL", backendUrl)
        addRow(root, 1, "Node.js executable", JPanel(BorderLayout(6, 0)).apply {
            add(nodePath, BorderLayout.CENTER)
            add(detectNodeButton, BorderLayout.EAST)
        })
        addRow(root, 2, "Context retrieval", contextMode)
        addRow(root, 3, "ContextEngine URL", contextHttpUrl)
        addFullRow(root, 4, autoApproveReadOnly)
        addFullRow(root, 5, inlineCompletions)
        addFullRow(root, 6, desktopNotifications)
        addFullRow(root, 7, showTimestamps)
        addFullRow(root, 8, showRunTelemetry)
        panel = root
        reset()
        return root
    }

    override fun isModified(): Boolean {
        val current = ApplicationManager.getApplication().getService(CodeAgentSettingsService::class.java).snapshot()
        val completionEnabled = ApplicationManager.getApplication()
            .getService(InlineCompletionSettingsService::class.java)
            .isEnabled()
        return backendUrl.text.trim() != current.backendUrl
            || nodePath.text.trim() != current.nodePath
            || contextMode.selectedItem != current.contextMode
            || contextHttpUrl.text.trim() != current.contextHttpBaseUrl
            || autoApproveReadOnly.isSelected != current.autoApproveReadOnly
            || desktopNotifications.isSelected != current.desktopNotifications
            || showTimestamps.isSelected != current.showTimestamps
            || showRunTelemetry.isSelected != current.showRunTelemetry
            || inlineCompletions.isSelected != completionEnabled
    }

    override fun apply() {
        val settingsService = ApplicationManager.getApplication().getService(CodeAgentSettingsService::class.java)
        val current = settingsService.snapshot()
        settingsService.update(
            CodeAgentSettingsUpdate(
                backendUrl = backendUrl.text,
                nodePath = nodePath.text,
                autoApproveReadOnly = autoApproveReadOnly.isSelected,
                chatZoom = current.chatZoom,
                showTimestamps = showTimestamps.isSelected,
                showRunTelemetry = showRunTelemetry.isSelected,
                desktopNotifications = desktopNotifications.isSelected,
                autoDismissNotifications = current.autoDismissNotifications,
                backendToken = null,
                contextMode = contextMode.selectedItem as? String ?: current.contextMode,
                contextHttpBaseUrl = contextHttpUrl.text,
                contextHttpApiKey = null,
                contextEmbeddingBaseUrl = current.contextEmbeddingBaseUrl,
                contextEmbeddingModel = current.contextEmbeddingModel,
                contextEmbeddingApiKey = null,
                contextNeuralRerank = current.contextNeuralRerank,
                contextRerankBaseUrl = current.contextRerankBaseUrl,
                contextRerankModel = current.contextRerankModel,
            ),
        )
        ApplicationManager.getApplication().getService(InlineCompletionSettingsService::class.java)
            .setEnabled(inlineCompletions.isSelected)
    }

    override fun reset() {
        val current = ApplicationManager.getApplication().getService(CodeAgentSettingsService::class.java).snapshot()
        backendUrl.text = current.backendUrl
        nodePath.text = current.nodePath
        contextMode.selectedItem = current.contextMode
        contextHttpUrl.text = current.contextHttpBaseUrl
        autoApproveReadOnly.isSelected = current.autoApproveReadOnly
        desktopNotifications.isSelected = current.desktopNotifications
        showTimestamps.isSelected = current.showTimestamps
        showRunTelemetry.isSelected = current.showRunTelemetry
        inlineCompletions.isSelected = ApplicationManager.getApplication()
            .getService(InlineCompletionSettingsService::class.java)
            .isEnabled()
        detectNodeButton.text = "Auto-detect"
        detectNodeButton.isEnabled = true
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun detectNodeRuntime() {
        detectNodeButton.isEnabled = false
        detectNodeButton.text = "Detecting..."
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { NodeRuntimeLocator.find(null) }
            ApplicationManager.getApplication().invokeLater {
                if (panel == null) return@invokeLater
                result.onSuccess {
                    nodePath.text = it
                    detectNodeButton.text = "Detected"
                }.onFailure {
                    detectNodeButton.text = "Not found"
                }
                detectNodeButton.isEnabled = true
            }
        }
    }

    private fun addRow(root: JPanel, row: Int, label: String, component: JComponent) {
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 10)
        }
        root.add(JBLabel(label), labelConstraints)
        val fieldConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 4, 4, 4)
        }
        root.add(component, fieldConstraints)
    }

    private fun addFullRow(root: JPanel, row: Int, component: JComponent) {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 4, 4, 4)
        }
        root.add(component, constraints)
    }
}
