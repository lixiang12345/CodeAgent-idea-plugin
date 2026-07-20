package com.codeagent.plugin.actions

import com.codeagent.plugin.agent.ByokService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class SetOpenAiKeyAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        if (configureOpenAiByok(e.project)) notify(e, "OpenAI BYOK is active; model discovery and Agent requests now use your credential")
    }
}

class ClearOpenAiKeyAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        service<ByokService>().clearOpenAi()
        notify(e, "OpenAI BYOK credentials removed")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = service<ByokService>().snapshot().openAiConfigured
    }
}

class SetAnthropicKeyAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        if (configureAnthropicByok(e.project)) notify(e, "Anthropic BYOK is active; model discovery and Agent requests now use your credential")
    }
}

class ClearAnthropicKeyAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        service<ByokService>().clearAnthropic()
        notify(e, "Anthropic BYOK credentials removed")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = service<ByokService>().snapshot().anthropicConfigured
    }
}

class SetBedrockKeyAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        if (configureBedrockByok(e.project)) notify(e, "AWS Bedrock BYOK is active; requests use SigV4 signing")
    }
}

class ClearBedrockKeyAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        service<ByokService>().clearBedrock()
        notify(e, "AWS Bedrock credentials removed")
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = service<ByokService>().snapshot().bedrockConfigured
    }
}

private class ProviderKeyDialog(
    project: com.intellij.openapi.project.Project?,
    title: String,
    keyLabel: String,
    endpoint: String,
) : DialogWrapper(project, true) {
    private val keyField = JBPasswordField()
    private val endpointField = JBTextField(endpoint)
    private val panel = FormBuilder.createFormBuilder()
        .addLabeledComponent(keyLabel, keyField)
        .addLabeledComponent("Base URL", endpointField)
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    init {
        setTitle(title)
        setOKButtonText("Save securely")
        init()
    }

    override fun createCenterPanel(): JComponent = panel

    override fun doValidate(): ValidationInfo? = when {
        keyField.password.isEmpty() -> ValidationInfo("API key is required", keyField)
        endpointField.text.isBlank() -> ValidationInfo("Base URL is required", endpointField)
        else -> null
    }

    fun apiKey(): String = String(keyField.password)
    fun endpoint(): String = endpointField.text
}

private class BedrockCredentialsDialog(project: com.intellij.openapi.project.Project?) : DialogWrapper(project, true) {
    private val accessKeyField = JBTextField()
    private val secretKeyField = JBPasswordField()
    private val sessionTokenField = JBPasswordField()
    private val regionField = JBTextField("us-east-1")
    private val modelField = JBTextField()
    private val panel = FormBuilder.createFormBuilder()
        .addLabeledComponent("AWS access key ID", accessKeyField)
        .addLabeledComponent("AWS secret access key", secretKeyField)
        .addLabeledComponent("AWS session token (optional)", sessionTokenField)
        .addLabeledComponent("Region", regionField)
        .addLabeledComponent("Bedrock model ID", modelField)
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    init {
        title = "AWS Bedrock BYOK"
        setOKButtonText("Save securely")
        init()
    }

    override fun createCenterPanel(): JComponent = panel

    override fun doValidate(): ValidationInfo? = when {
        accessKeyField.text.isBlank() -> ValidationInfo("AWS access key ID is required", accessKeyField)
        secretKeyField.password.isEmpty() -> ValidationInfo("AWS secret access key is required", secretKeyField)
        !regionField.text.trim().matches(Regex("^[a-z]{2}(?:-gov)?-[a-z]+-\\d$")) -> ValidationInfo("AWS region is invalid", regionField)
        modelField.text.isBlank() -> ValidationInfo("Bedrock model ID is required", modelField)
        else -> null
    }

    fun accessKeyId(): String = accessKeyField.text
    fun secretAccessKey(): String = String(secretKeyField.password)
    fun sessionToken(): String? = String(sessionTokenField.password).trim().takeIf(String::isNotEmpty)
    fun region(): String = regionField.text
    fun model(): String = modelField.text
}

internal fun configureOpenAiByok(project: com.intellij.openapi.project.Project?): Boolean {
    val dialog = ProviderKeyDialog(project, "OpenAI BYOK", "OpenAI API key", ByokService.DEFAULT_OPENAI_BASE_URL)
    if (!dialog.showAndGet()) return false
    return save(project, "OpenAI") { service<ByokService>().setOpenAi(dialog.apiKey(), dialog.endpoint()) }
}

internal fun configureAnthropicByok(project: com.intellij.openapi.project.Project?): Boolean {
    val dialog = ProviderKeyDialog(project, "Anthropic BYOK", "Anthropic API key", ByokService.DEFAULT_ANTHROPIC_BASE_URL)
    if (!dialog.showAndGet()) return false
    return save(project, "Anthropic") { service<ByokService>().setAnthropic(dialog.apiKey(), dialog.endpoint()) }
}

internal fun configureBedrockByok(project: com.intellij.openapi.project.Project?): Boolean {
    val dialog = BedrockCredentialsDialog(project)
    if (!dialog.showAndGet()) return false
    return save(project, "AWS Bedrock") {
        service<ByokService>().setBedrock(
            accessKeyId = dialog.accessKeyId(),
            secretAccessKey = dialog.secretAccessKey(),
            sessionToken = dialog.sessionToken(),
            region = dialog.region(),
            model = dialog.model(),
        )
    }
}

private fun save(project: com.intellij.openapi.project.Project?, provider: String, operation: () -> Unit): Boolean =
    runCatching(operation).fold(
        onSuccess = { true },
        onFailure = { error ->
            Messages.showErrorDialog(project, error.message ?: "Unable to save BYOK credentials", "$provider BYOK")
            false
        },
    )

private fun notify(event: AnActionEvent, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("CodeAgent")
        .createNotification(message, NotificationType.INFORMATION)
        .notify(event.project)
}
