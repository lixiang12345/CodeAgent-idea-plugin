package com.codeagent.plugin.agent

import com.intellij.codeInsight.inline.completion.InlineCompletionHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionInstallListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
class InlineCompletionTelemetryService {
    private val installedEditors = AtomicInteger()
    private val requests = AtomicLong()
    private val cacheHits = AtomicLong()
    private val suggestions = AtomicLong()
    private val failures = AtomicLong()
    private val cancellations = AtomicLong()
    private val totalLatencyMs = AtomicLong()
    private val lastLatencyMs = AtomicLong()
    private val lastSuggestionAt = AtomicLong()

    fun editorInstalled() = installedEditors.incrementAndGet()

    fun editorUninstalled() = installedEditors.updateAndGet { (it - 1).coerceAtLeast(0) }

    fun requestStarted() = requests.incrementAndGet()

    fun cacheHit() = cacheHits.incrementAndGet()

    fun requestFinished(latencyMs: Long, suggested: Boolean, failed: Boolean, cancelled: Boolean = false) {
        val bounded = latencyMs.coerceAtLeast(0)
        totalLatencyMs.addAndGet(bounded)
        lastLatencyMs.set(bounded)
        if (suggested) {
            suggestions.incrementAndGet()
            lastSuggestionAt.set(System.currentTimeMillis())
        }
        if (failed) failures.incrementAndGet()
        if (cancelled) cancellations.incrementAndGet()
    }

    fun snapshot(): InlineCompletionTelemetry = InlineCompletionTelemetry(
        installedEditors = installedEditors.get(),
        requests = requests.get(),
        cacheHits = cacheHits.get(),
        suggestions = suggestions.get(),
        failures = failures.get(),
        cancellations = cancellations.get(),
        averageLatencyMs = requests.get().takeIf { it > 0 }?.let { totalLatencyMs.get() / it } ?: 0,
        lastLatencyMs = lastLatencyMs.get(),
        lastSuggestionAt = lastSuggestionAt.get().takeIf { it > 0 },
    )
}

data class InlineCompletionTelemetry(
    val installedEditors: Int,
    val requests: Long,
    val cacheHits: Long,
    val suggestions: Long,
    val failures: Long,
    val cancellations: Long,
    val averageLatencyMs: Long,
    val lastLatencyMs: Long,
    val lastSuggestionAt: Long?,
)

class CodeAgentInlineCompletionInstallListener : InlineCompletionInstallListener {
    override fun handlerInstalled(editor: Editor, handler: InlineCompletionHandler) {
        com.intellij.openapi.components.service<InlineCompletionTelemetryService>().editorInstalled()
    }

    override fun handlerUninstalled(editor: Editor, handler: InlineCompletionHandler) {
        com.intellij.openapi.components.service<InlineCompletionTelemetryService>().editorUninstalled()
    }
}
