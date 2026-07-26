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
    private val inFlight = AtomicInteger()

    @Volatile
    private var lastRequestProducedSuggestion: Boolean? = null

    private val activityListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Registers a listener notified whenever completion activity changes, so
     * status presentations can be event-driven instead of polling.
     */
    fun addActivityListener(listener: () -> Unit): () -> Unit {
        activityListeners += listener
        return { activityListeners -= listener }
    }

    /** True while at least one completion request is outstanding. */
    fun isGenerating(): Boolean = inFlight.get() > 0

    /** Null until a request completes; false when the last completed request returned nothing. */
    fun lastRequestSuggested(): Boolean? = lastRequestProducedSuggestion

    fun editorInstalled() = installedEditors.incrementAndGet()

    fun editorUninstalled() = installedEditors.updateAndGet { (it - 1).coerceAtLeast(0) }

    fun requestStarted(): Long {
        inFlight.incrementAndGet()
        val value = requests.incrementAndGet()
        notifyActivity()
        return value
    }

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
        if (!cancelled) lastRequestProducedSuggestion = suggested
        inFlight.updateAndGet { (it - 1).coerceAtLeast(0) }
        notifyActivity()
    }

    /** Clears the transient "no completions" signal, for example when the setting is toggled. */
    fun resetLastRequestOutcome() {
        lastRequestProducedSuggestion = null
        notifyActivity()
    }

    fun notifyActivity() {
        activityListeners.forEach { listener -> runCatching { listener() } }
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
