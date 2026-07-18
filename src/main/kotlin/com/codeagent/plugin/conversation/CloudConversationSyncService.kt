package com.codeagent.plugin.conversation

import com.codeagent.plugin.agent.RemoteAgentClient
import com.codeagent.plugin.agent.RemoteConversation
import com.codeagent.plugin.agent.RemoteConversationMessage
import com.codeagent.plugin.agent.RemoteConversationTask
import com.codeagent.plugin.agent.RemoteConversationTool
import com.codeagent.plugin.agent.RemoteHttpException
import com.codeagent.plugin.settings.CodeAgentSettingsService
import com.codeagent.plugin.settings.OidcLoginService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
class CloudConversationSyncService(private val project: Project) : Disposable {
    private val settings = service<CodeAgentSettingsService>()
    private val oidcLogin = service<OidcLoginService>()
    private val conversations = project.service<ConversationStore>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
    private val json = Json { encodeDefaults = true }
    private val versions = ConcurrentHashMap<String, Int>()
    private val syncedFingerprints = ConcurrentHashMap<String, String>()
    private val pending = ConcurrentHashMap<String, RemoteConversation>()
    private val inFlightGeneration = AtomicLong(NO_GENERATION)
    private val disposed = AtomicBoolean(false)
    private val lifecycleGeneration = AtomicLong()
    private val restoreGeneration = AtomicLong()
    private val scheduleLock = Any()

    @Volatile
    private var listener: (() -> Unit)? = null

    private var scheduledFlush: ScheduledFuture<*>? = null

    fun setChangeListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    fun restore(): CompletableFuture<CloudSyncResult> {
        val lifecycle = lifecycleGeneration.get()
        val restore = restoreGeneration.incrementAndGet()
        return oidcLogin.ensureFreshToken().thenCompose {
            val client = RemoteAgentClient(settings.snapshot())
            val deletions = conversations.pendingCloudDeletions()
            val deleteRequests = deletions.map { conversationId ->
                client.deleteConversation(conversationId).thenApply { conversationId }
            }
            CompletableFuture.allOf(*deleteRequests.toTypedArray()).thenCompose {
                if (isRestoreCurrent(lifecycle, restore)) {
                    deleteRequests.map(CompletableFuture<String>::join).forEach(conversations::acknowledgeCloudDeletion)
                }
                client.conversations()
            }.thenCompose { listing ->
                val reads = listing.data.map { summary -> client.conversation(summary.id) }
                CompletableFuture.allOf(*reads.toTypedArray()).thenApply {
                    reads.map(CompletableFuture<RemoteConversation>::join)
                }
            }
        }.thenApply { remote ->
            if (!isRestoreCurrent(lifecycle, restore)) return@thenApply CloudSyncResult()
            versions.clear()
            syncedFingerprints.clear()
            remote.forEach { conversation ->
                versions[conversation.id] = conversation.version
                syncedFingerprints[conversation.id] = fingerprint(conversation)
            }
            val merge = conversations.mergeCloudSnapshot(remote.map { it.toLocal() })
            merge.upload.forEach(::schedule)
            if (merge.changed) listener?.invoke()
            CloudSyncResult(restored = remote.size, queuedUploads = merge.upload.size)
        }
    }

    fun schedule(snapshot: ConversationSnapshot) {
        if (disposed.get() || snapshot.isPristine()) return
        val remote = snapshot.toRemote(versions[snapshot.id] ?: 0)
        if (syncedFingerprints[snapshot.id] == fingerprint(remote)) return
        pending[snapshot.id] = remote
        scheduleFlush(DEBOUNCE_MILLIS)
    }

    fun delete(conversationId: String): CompletableFuture<Boolean> {
        restoreGeneration.incrementAndGet()
        pending.remove(conversationId)
        versions.remove(conversationId)
        syncedFingerprints.remove(conversationId)
        return oidcLogin.ensureFreshToken().thenCompose {
            RemoteAgentClient(settings.snapshot()).deleteConversation(conversationId)
        }.thenApply { deleted ->
            conversations.acknowledgeCloudDeletion(conversationId)
            deleted
        }
    }

    fun reset() {
        lifecycleGeneration.incrementAndGet()
        restoreGeneration.incrementAndGet()
        inFlightGeneration.set(NO_GENERATION)
        synchronized(scheduleLock) {
            scheduledFlush?.cancel(false)
            scheduledFlush = null
        }
        pending.clear()
        versions.clear()
        syncedFingerprints.clear()
    }

    override fun dispose() {
        disposed.set(true)
        reset()
        listener = null
    }

    private fun scheduleFlush(delayMillis: Long) {
        if (disposed.get()) return
        synchronized(scheduleLock) {
            scheduledFlush?.cancel(false)
            scheduledFlush = scheduler.schedule({
                synchronized(scheduleLock) { scheduledFlush = null }
                flush()
            }, delayMillis, TimeUnit.MILLISECONDS)
        }
    }

    private fun flush() {
        if (disposed.get() || pending.isEmpty()) return
        val generation = lifecycleGeneration.get()
        if (!inFlightGeneration.compareAndSet(NO_GENERATION, generation)) {
            scheduleFlush(DEBOUNCE_MILLIS)
            return
        }
        val batch = pending.values.toList()
        oidcLogin.ensureFreshToken().thenCompose {
            val client = RemoteAgentClient(settings.snapshot())
            CompletableFuture.allOf(*batch.map { upload(client, it, generation) }.toTypedArray())
        }.whenComplete { _, error ->
            inFlightGeneration.compareAndSet(generation, NO_GENERATION)
            if (isCurrent(generation) && pending.isNotEmpty()) {
                scheduleFlush(if (error == null) DEBOUNCE_MILLIS else RETRY_MILLIS)
            }
        }
    }

    private fun upload(
        client: RemoteAgentClient,
        payload: RemoteConversation,
        generation: Long,
    ): CompletableFuture<Unit> {
        if (!isCurrent(generation)) return CompletableFuture.completedFuture(Unit)
        val write = client.putConversation(payload, versions[payload.id]).thenApply { stored ->
            completeUpload(payload, stored, generation)
            Unit
        }
        return write.handle { result, error ->
            val cause = error?.rootCause()
            when {
                error == null -> CompletableFuture.completedFuture(result)
                isCurrent(generation) && cause is RemoteHttpException && cause.statusCode == 409 ->
                    resolveConflict(client, payload, generation)
                isCurrent(generation) && cause is RemoteHttpException && cause.statusCode in NON_RETRYABLE_STATUS_CODES -> {
                    pending.remove(payload.id, payload)
                    LOG.warn(
                        "Cloud conversation ${payload.id} was rejected with HTTP ${cause.statusCode}; " +
                            "the invalid snapshot will not be retried until it changes",
                        cause,
                    )
                    CompletableFuture.completedFuture(Unit)
                }
                else -> CompletableFuture.failedFuture(cause ?: requireNotNull(error))
            }
        }.thenCompose { it }
    }

    private fun resolveConflict(
        client: RemoteAgentClient,
        attempted: RemoteConversation,
        generation: Long,
    ): CompletableFuture<Unit> = client.conversation(attempted.id).thenCompose { cloud ->
        if (!isCurrent(generation)) return@thenCompose CompletableFuture.completedFuture(Unit)
        versions[cloud.id] = cloud.version
        val local = conversations.threads().firstOrNull { it.id == cloud.id }
        when {
            local == null -> {
                pending.remove(attempted.id, attempted)
                CompletableFuture.completedFuture(Unit)
            }
            cloud.updatedAt >= local.updatedAt -> {
                syncedFingerprints[cloud.id] = fingerprint(cloud)
                pending.remove(attempted.id, attempted)
                val merge = conversations.mergeCloudConversation(cloud.toLocal())
                if (merge.changed) listener?.invoke()
                CompletableFuture.completedFuture(Unit)
            }
            else -> {
                val latest = local.toRemote(cloud.version)
                pending[local.id] = latest
                client.putConversation(latest, cloud.version).thenApply { stored ->
                    completeUpload(latest, stored, generation)
                    Unit
                }
            }
        }
    }

    private fun completeUpload(payload: RemoteConversation, stored: RemoteConversation, generation: Long) {
        if (!isCurrent(generation)) return
        versions[payload.id] = stored.version
        syncedFingerprints[payload.id] = fingerprint(payload)
        pending.remove(payload.id, payload)
    }

    private fun isCurrent(generation: Long): Boolean =
        !disposed.get() && lifecycleGeneration.get() == generation

    private fun isRestoreCurrent(lifecycle: Long, restore: Long): Boolean =
        isCurrent(lifecycle) && restoreGeneration.get() == restore

    private fun fingerprint(conversation: RemoteConversation): String =
        json.encodeToString(conversation.copy(version = 0))

    private fun ConversationSnapshot.toRemote(version: Int) = RemoteConversation(
        id = id,
        title = title,
        mode = mode,
        updatedAt = updatedAt,
        selectedAgentProfileId = selectedAgentProfileId,
        selectedModelId = selectedModelId,
        selectedSkillIds = selectedSkillIds,
        selectedRuleIds = selectedRuleIds,
        pinned = pinned,
        summary = summary,
        messages = messages.takeLast(MAX_REMOTE_MESSAGES).map {
            RemoteConversationMessage(it.id, it.role, it.content, it.createdAt, it.runId, it.turnIndex, it.timelineSequence)
        },
        tasks = tasks.take(MAX_REMOTE_TASKS).map { RemoteConversationTask(it.id, it.name, it.state) },
        tools = tools.takeLast(MAX_REMOTE_TOOLS).map { tool ->
            RemoteConversationTool(
                id = tool.id,
                name = tool.name,
                summary = tool.summary,
                status = tool.status,
                detail = tool.detail,
                changePath = tool.changePath,
                canRevert = tool.canRevert,
                runId = tool.runId,
                turnIndex = tool.turnIndex,
                createdAt = tool.createdAt,
                updatedAt = tool.updatedAt,
                timelineSequence = tool.timelineSequence,
            )
        },
        version = version,
    )

    private fun RemoteConversation.toLocal() = ConversationSnapshot(
        id = id,
        title = title,
        updatedAt = updatedAt,
        mode = mode,
        selectedAgentProfileId = selectedAgentProfileId,
        selectedModelId = selectedModelId,
        selectedSkillIds = selectedSkillIds,
        selectedRuleIds = selectedRuleIds,
        messages = messages.map { ConversationMessage(it.id, it.role, it.content, it.createdAt, it.runId, it.turnIndex, it.timelineSequence) },
        tasks = tasks.map { ConversationTask(it.id, it.name, it.state) },
        active = false,
        pinned = pinned,
        summary = summary,
        tools = tools.map { tool ->
            ConversationTool(
                id = tool.id,
                name = tool.name,
                summary = tool.summary,
                status = tool.status,
                detail = tool.detail,
                changePath = tool.changePath,
                canRevert = tool.canRevert,
                runId = tool.runId,
                turnIndex = tool.turnIndex,
                createdAt = tool.createdAt,
                updatedAt = tool.updatedAt,
                timelineSequence = tool.timelineSequence,
            )
        },
    )

    private fun ConversationSnapshot.isPristine(): Boolean =
        title == "New task" && messages.isEmpty() && tasks.isEmpty() && tools.isEmpty() && !pinned && summary.isNullOrBlank()

    private fun Throwable.rootCause(): Throwable {
        var current = this
        while (current.cause != null) current = current.cause!!
        return current
    }

    companion object {
        private val LOG = Logger.getInstance(CloudConversationSyncService::class.java)
        private val NON_RETRYABLE_STATUS_CODES = setOf(400, 404, 413, 422)
        private const val NO_GENERATION = -1L
        private const val DEBOUNCE_MILLIS = 750L
        private const val RETRY_MILLIS = 5_000L
        private const val MAX_REMOTE_MESSAGES = 200
        private const val MAX_REMOTE_TASKS = 100
        private const val MAX_REMOTE_TOOLS = 1_000
    }
}

data class CloudSyncResult(
    val restored: Int = 0,
    val queuedUploads: Int = 0,
)
