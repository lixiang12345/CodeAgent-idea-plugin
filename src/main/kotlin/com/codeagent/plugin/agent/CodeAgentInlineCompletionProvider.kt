package com.codeagent.plugin.agent

import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionVariant
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.UserDataHolderBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class CodeAgentInlineCompletionProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID = InlineCompletionProviderID("codeagent.inline-completion")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!settings().isEnabled()) return false
        return runCatching { event.toRequest()?.editor?.isDisposed == false }.getOrDefault(false)
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val prefix = request.document.text.substring(0, request.startOffset).takeLast(MAX_PREFIX_CHARS)
        val suffix = request.document.text.substring(request.endOffset).take(MAX_SUFFIX_CHARS)
        if (prefix.isBlank() || request.editor.isDisposed) return emptySuggestion()

        val completion = runCatching {
            withContext(Dispatchers.IO) {
                RemoteAgentClient(settingsService().snapshot()).completion(
                    prefix = prefix,
                    suffix = suffix,
                    path = request.file.virtualFile?.path ?: request.file.name,
                    language = request.file.language.id,
                ).get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS).completion
            }
        }.getOrNull()
            ?.replace("\u0000", "")
            ?.take(MAX_COMPLETION_CHARS)
            ?.takeIf { it.isNotBlank() }

        if (completion == null) return emptySuggestion()
        return object : InlineCompletionSuggestion {
            override suspend fun getVariants(): List<InlineCompletionVariant> = listOf(
                InlineCompletionVariant.build(
                    UserDataHolderBase(),
                    flowOf(InlineCompletionTextElement(completion, TextAttributes())),
                ),
            )
        }
    }

    private fun settings(): InlineCompletionSettingsService =
        ApplicationManager.getApplication().getService(InlineCompletionSettingsService::class.java)

    private fun settingsService(): CodeAgentSettingsService =
        ApplicationManager.getApplication().getService(CodeAgentSettingsService::class.java)

    private fun emptySuggestion(): InlineCompletionSuggestion = object : InlineCompletionSuggestion {
        override suspend fun getVariants(): List<InlineCompletionVariant> = emptyList()
    }

    companion object {
        private const val MAX_PREFIX_CHARS = 80_000
        private const val MAX_SUFFIX_CHARS = 20_000
        private const val MAX_COMPLETION_CHARS = 8_000
        private const val COMPLETION_TIMEOUT_SECONDS = 25L
    }
}
