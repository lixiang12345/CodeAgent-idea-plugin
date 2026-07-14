package com.codeagent.plugin.conversation

import com.codeagent.plugin.agent.RemoteAgentClient
import com.codeagent.plugin.agent.RemoteJob
import com.codeagent.plugin.agent.RemoteJobInput
import com.codeagent.plugin.agent.RemoteJobRequest
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.OidcLoginService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class ConversationSummaryService(private val project: Project) : Disposable {
    private val settings = service<CodeAgentSettingsService>()
    private val oidcLogin = service<OidcLoginService>()
    private val conversations = project.service<ConversationStore>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val scheduled = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val inFlight = ConcurrentHashMap<String, SummaryRequest>()
    private val summarizedFingerprints = ConcurrentHashMap<String, String>()
    private val lifecycleGeneration = AtomicLong()
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var listener: ((ConversationSnapshot) -> Unit)? = null

    fun setChangeListener(listener: ((ConversationSnapshot) -> Unit)?) {
        this.listener = listener
    }

    fun schedule(snapshot: ConversationSnapshot) {
        if (disposed.get() || !shouldSummarize(snapshot)) return
        val fingerprint = fingerprint(snapshot)
        val generation = lifecycleGeneration.get()
        val request = SummaryRequest(fingerprint, generation)
        if (summarizedFingerprints[snapshot.id] == fingerprint || inFlight[snapshot.id] == request) return
        scheduled.remove(snapshot.id)?.cancel(false)
        scheduled[snapshot.id] = scheduler.schedule(
            {
                scheduled.remove(snapshot.id)
                generate(snapshot.id, fingerprint, generation)
            },
            SUMMARY_DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun forget(conversationId: String) {
        scheduled.remove(conversationId)?.cancel(false)
        inFlight.remove(conversationId)
        summarizedFingerprints.remove(conversationId)
    }

    fun reset() {
        lifecycleGeneration.incrementAndGet()
        scheduled.values.forEach { it.cancel(false) }
        scheduled.clear()
        inFlight.clear()
        summarizedFingerprints.clear()
    }

    override fun dispose() {
        disposed.set(true)
        reset()
        listener = null
    }

    private fun generate(conversationId: String, requestedFingerprint: String, generation: Long) {
        if (!isCurrent(generation)) return
        val snapshot = conversations.threads().firstOrNull { it.id == conversationId } ?: return
        val currentFingerprint = fingerprint(snapshot)
        if (currentFingerprint != requestedFingerprint) {
            schedule(snapshot)
            return
        }
        val request = SummaryRequest(currentFingerprint, generation)
        if (inFlight.putIfAbsent(conversationId, request) != null) return

        oidcLogin.ensureFreshToken().thenCompose {
            val client = RemoteAgentClient(settings.snapshot())
            client.createJob(
                RemoteJobRequest(
                    type = "history-summary",
                    input = RemoteJobInput(
                        prompt = buildPrompt(snapshot),
                    ),
                ),
            ).thenCompose { job -> waitForJob(client, job.id, 0) }
        }.whenComplete { job, error ->
            inFlight.remove(conversationId, request)
            if (!isCurrent(generation)) return@whenComplete
            if (error != null) {
                LOG.warn("Conversation summary generation failed", error.rootCause())
                return@whenComplete
            }
            if (job.status != "completed") {
                LOG.warn("Conversation summary job ${job.id} ended with ${job.status}: ${job.error.orEmpty()}")
                return@whenComplete
            }
            val content = job.output?.content?.trim().orEmpty()
            if (content.isEmpty()) {
                LOG.warn("Conversation summary job ${job.id} returned no content")
                return@whenComplete
            }
            val latest = conversations.threads().firstOrNull { it.id == conversationId } ?: return@whenComplete
            if (fingerprint(latest) != currentFingerprint) {
                schedule(latest)
                return@whenComplete
            }
            val updated = conversations.setSummary(conversationId, content)
            summarizedFingerprints[conversationId] = currentFingerprint
            listener?.invoke(updated)
        }
    }

    private fun waitForJob(client: RemoteAgentClient, jobId: String, attempt: Int): CompletableFuture<RemoteJob> =
        client.job(jobId).thenCompose { job ->
            when {
                job.status in TERMINAL_JOB_STATES -> CompletableFuture.completedFuture(job)
                attempt >= MAX_POLL_ATTEMPTS -> CompletableFuture.failedFuture(
                    IllegalStateException("Conversation summary job timed out"),
                )
                else -> CompletableFuture.runAsync(
                    {},
                    CompletableFuture.delayedExecutor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS),
                ).thenCompose { waitForJob(client, jobId, attempt + 1) }
            }
        }

    private fun shouldSummarize(snapshot: ConversationSnapshot): Boolean {
        if (snapshot.messages.none { it.role == "assistant" }) return false
        val contentChars = snapshot.messages.sumOf { it.content.length }
        return snapshot.messages.size >= MIN_MESSAGES || contentChars >= MIN_CONTENT_CHARS
    }

    private fun buildPrompt(snapshot: ConversationSnapshot): String {
        val header = buildString {
            append("Conversation: ").append(snapshot.title).append('\n')
            snapshot.summary?.let { append("Existing summary:\n").append(it).append('\n') }
            append("Create an updated history summary from the transcript below.")
        }
        val entries = mutableListOf<String>()
        var remaining = MAX_PROMPT_CHARS - header.length
        for (message in snapshot.messages.asReversed()) {
            val role = if (message.role == "user") "USER" else "ASSISTANT"
            val prefix = "\n\n$role:\n"
            val available = (remaining - prefix.length).coerceAtLeast(0)
            if (available == 0) break
            val content = if (message.content.length <= available) message.content else message.content.takeLast(available)
            entries.add(0, prefix + content)
            remaining -= prefix.length + content.length
            if (content.length < message.content.length) break
        }
        return header + entries.joinToString("")
    }

    private fun fingerprint(snapshot: ConversationSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        snapshot.messages.forEach { message ->
            digest.update(message.id.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(message.role.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(message.content.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isCurrent(generation: Long): Boolean =
        !disposed.get() && lifecycleGeneration.get() == generation

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null) current = current.cause!!
        return current
    }

    companion object {
        private val LOG = logger<ConversationSummaryService>()
        private val TERMINAL_JOB_STATES = setOf("completed", "failed", "cancelled")
        private const val SUMMARY_DEBOUNCE_MILLIS = 1_500L
        private const val POLL_INTERVAL_MILLIS = 500L
        private const val MAX_POLL_ATTEMPTS = 120
        private const val MIN_MESSAGES = 4
        private const val MIN_CONTENT_CHARS = 4_000
        private const val MAX_PROMPT_CHARS = 90_000
    }

    private data class SummaryRequest(
        val fingerprint: String,
        val generation: Long,
    )
}
