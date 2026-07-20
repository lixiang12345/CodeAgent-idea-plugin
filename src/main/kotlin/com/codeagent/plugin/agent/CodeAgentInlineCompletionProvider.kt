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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

        val path = request.file.virtualFile?.path ?: request.file.name
        val language = request.file.language.id
        val currentSettings = settingsService().snapshot()
        val cacheKey = InlineCompletionCache.key(path, language, prefix, suffix, currentSettings.backendUrl)
        val telemetry = telemetry()
        telemetry.requestStarted()
        CACHE.get(cacheKey)?.let { cached ->
            telemetry.cacheHit()
            telemetry.requestFinished(0, suggested = true, failed = false)
            return suggestion(cached)
        }

        val started = System.nanoTime()
        var failed = false
        var cancelled = false
        val completion = try {
            RemoteAgentClient(currentSettings, byokCredentials = byokService().requestCredentials()).completion(
                prefix = prefix,
                suffix = suffix,
                path = path,
                language = language,
            ).orTimeout(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS).awaitCancellable().completion
        } catch (error: CancellationException) {
            cancelled = true
            null
        } catch (_: Throwable) {
            failed = true
            null
        }
            ?.replace("\u0000", "")
            ?.take(MAX_COMPLETION_CHARS)
            ?.takeIf { it.isNotBlank() }

        val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        telemetry.requestFinished(latencyMs, completion != null, failed, cancelled)
        if (completion == null) return emptySuggestion()
        CACHE.put(cacheKey, completion)
        return suggestion(completion)
    }

    private fun suggestion(completion: String): InlineCompletionSuggestion = object : InlineCompletionSuggestion {
            override suspend fun getVariants(): List<InlineCompletionVariant> = listOf(
                InlineCompletionVariant.build(
                    UserDataHolderBase(),
                    flowOf(InlineCompletionTextElement(completion, TextAttributes())),
                ),
            )
        }

    private fun settings(): InlineCompletionSettingsService =
        ApplicationManager.getApplication().getService(InlineCompletionSettingsService::class.java)

    private fun settingsService(): CodeAgentSettingsService =
        ApplicationManager.getApplication().getService(CodeAgentSettingsService::class.java)

    private fun telemetry(): InlineCompletionTelemetryService =
        ApplicationManager.getApplication().getService(InlineCompletionTelemetryService::class.java)

    private fun byokService(): ByokService =
        ApplicationManager.getApplication().getService(ByokService::class.java)

    private fun emptySuggestion(): InlineCompletionSuggestion = object : InlineCompletionSuggestion {
        override suspend fun getVariants(): List<InlineCompletionVariant> = emptyList()
    }

    companion object {
        private const val MAX_PREFIX_CHARS = 80_000
        private const val MAX_SUFFIX_CHARS = 20_000
        private const val MAX_COMPLETION_CHARS = 8_000
        private const val COMPLETION_TIMEOUT_SECONDS = 25L
        private val CACHE = InlineCompletionCache()
    }
}

private suspend fun <T> java.util.concurrent.CompletableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (error != null) continuation.resumeWithException(error) else continuation.resume(value)
        }
        continuation.invokeOnCancellation { cancel(true) }
    }
