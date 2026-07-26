package com.codeagent.plugin.settings

import com.codeagent.plugin.agent.InlineCompletionSettingsService
import com.codeagent.plugin.agent.ToolPermissionRules
import com.codeagent.plugin.context.NodeRuntimeLocator
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel

class CodeAgentConfigurable : SearchableConfigurable {
    private val backendUrl = JBTextField()
    private val nodePath = JBTextField()
    private val contextMode = ComboBox(arrayOf("remote-http", "lexical", "private-semantic"))
    private val contextHttpUrl = JBTextField()
    private val autoApproveReadOnly = JBCheckBox("Auto-approve read-only tools")
    private val desktopNotifications = JBCheckBox("Show desktop notifications")
    private val inlineCompletions = JBCheckBox("Enable inline completions")
    private val disabledCompletionLanguages = JBTextField()
    private val showTimestamps = JBCheckBox("Show message timestamps")
    private val showRunTelemetry = JBCheckBox("Show run telemetry")
    private val detectNodeButton = JButton("Auto-detect")
    private val toolPermissionRules = JBTextArea(5, 40)
    private val configureCompletionShortcut = JButton()
    private val signInButton = JButton("Sign In")
    private val signOutButton = JButton("Sign Out")

    private var panel: JPanel? = null

    override fun getId(): String = "com.codeagent.plugin.settings"

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
        addRow(root, 6, "Disable completion by language", disabledCompletionLanguages)
        addFullRow(root, 7, JBLabel("Comma separated file extensions, for example *.js, *.ts").apply {
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        })
        addFullRow(root, 8, JPanel(BorderLayout(6, 0)).apply {
            add(configureCompletionShortcut, BorderLayout.WEST)
        })
        addFullRow(root, 9, desktopNotifications)
        addFullRow(root, 10, showTimestamps)
        addFullRow(root, 11, showRunTelemetry)
        addRow(root, 12, "Tool permission rules", JBScrollPane(toolPermissionRules).apply {
            preferredSize = Dimension(420, 96)
        })
        addFullRow(root, 13, JBLabel("One rule per line: toolName=allow|deny|ask[;shellInputRegex]. " +
            "Use * to match every tool and a trailing * for a prefix.").apply {
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        })
        addFullRow(root, 14, JPanel(BorderLayout(6, 0)).apply {
            add(JPanel().apply {
                add(signInButton)
                add(signOutButton)
            }, BorderLayout.WEST)
        })
        configureCompletionShortcut.addActionListener { openKeymapForCompletionAction() }
        signInButton.addActionListener { performAction("CodeAgent.SignIn") }
        signOutButton.addActionListener { performAction("CodeAgent.SignOut") }
        panel = root
        reset()
        return root
    }

    /** Mirrors the original plugin's explicit-completion shortcut affordance. */
    private fun openKeymapForCompletionAction() {
        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
            .showSettingsDialog(null, com.intellij.openapi.keymap.impl.ui.KeymapPanel::class.java)
    }

    private fun performAction(actionId: String) {
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        val event = AnActionEvent.createFromDataContext(ActionPlaces.UNKNOWN, null, DataContext.EMPTY_CONTEXT)
        action.actionPerformed(event)
    }

    private fun completionShortcutLabel(): String {
        val shortcut = ActionManager.getInstance().getAction(EXPLICIT_COMPLETION_ACTION_ID)
            ?.shortcutSet
            ?.shortcuts
            ?.firstOrNull()
        val rendered = shortcut?.let { KeymapUtil.getShortcutText(it) }
        return if (rendered.isNullOrBlank()) {
            "Configure Explicit Completion Shortcut"
        } else {
            "Configure Explicit Completion Shortcut ($rendered)"
        }
    }

    override fun isModified(): Boolean {
        val current = ApplicationManager.getApplication().getService(CodeAgentSettingsService::class.java).snapshot()
        val completionService = ApplicationManager.getApplication()
            .getService(InlineCompletionSettingsService::class.java)
        val completionEnabled = completionService.isEnabled()
        val disabledLanguagesChanged = InlineCompletionSettingsService
            .normalizeDisabledLanguages(disabledCompletionLanguages.text) != completionService.disabledLanguages()
        return backendUrl.text.trim() != current.backendUrl
            || nodePath.text.trim() != current.nodePath
            || contextMode.selectedItem != current.contextMode
            || contextHttpUrl.text.trim() != current.contextHttpBaseUrl
            || autoApproveReadOnly.isSelected != current.autoApproveReadOnly
            || desktopNotifications.isSelected != current.desktopNotifications
            || showTimestamps.isSelected != current.showTimestamps
            || showRunTelemetry.isSelected != current.showRunTelemetry
            || inlineCompletions.isSelected != completionEnabled
            || disabledLanguagesChanged
            || normalizedRuleText() != current.toolPermissionRulesText
    }

    private fun normalizedRuleText(): String =
        toolPermissionRules.text.lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n")

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
        val completionService = ApplicationManager.getApplication()
            .getService(InlineCompletionSettingsService::class.java)
        completionService.setEnabled(inlineCompletions.isSelected)
        completionService.setDisabledLanguages(disabledCompletionLanguages.text)
        disabledCompletionLanguages.text = completionService.disabledLanguages()
        settingsService.setToolPermissionRulesText(toolPermissionRules.text)
        toolPermissionRules.text = settingsService.toolPermissionRulesText()
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
        val completionService = ApplicationManager.getApplication()
            .getService(InlineCompletionSettingsService::class.java)
        inlineCompletions.isSelected = completionService.isEnabled()
        disabledCompletionLanguages.text = completionService.disabledLanguages()
        detectNodeButton.text = "Auto-detect"
        detectNodeButton.isEnabled = true
        val settingsService = ApplicationManager.getApplication().getService(CodeAgentSettingsService::class.java)
        toolPermissionRules.text = settingsService.toolPermissionRulesText()
        configureCompletionShortcut.text = completionShortcutLabel()
        val signedIn = settingsService.isSignedIn()
        signInButton.isVisible = !signedIn
        signOutButton.isVisible = signedIn
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

    private companion object {
        const val EXPLICIT_COMPLETION_ACTION_ID = "CallInlineCompletionAction"
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
