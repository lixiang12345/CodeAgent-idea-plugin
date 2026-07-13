package com.codeagent.plugin.conversation

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import java.util.UUID

@Service(Service.Level.PROJECT)
@State(name = "CodeAgentConversations", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ConversationStore : PersistentStateComponent<ConversationStoreState> {
    private var data = ConversationStoreState()

    override fun getState(): ConversationStoreState = data

    override fun loadState(state: ConversationStoreState) {
        data = state
        ensureActive()
    }

    @Synchronized
    fun active(): ConversationSnapshot {
        ensureActive()
        return requireNotNull(data.threads.firstOrNull { it.id == data.activeThreadId }).toSnapshot()
    }

    @Synchronized
    fun threads(): List<ConversationSnapshot> {
        ensureActive()
        return data.threads.sortedByDescending { it.updatedAt }.map { it.toSnapshot() }
    }

    @Synchronized
    fun newThread(mode: String = "agent"): ConversationSnapshot {
        val thread = ConversationThreadState().apply {
            id = UUID.randomUUID().toString()
            title = "New task"
            updatedAt = System.currentTimeMillis()
            this.mode = mode
        }
        data.threads.add(thread)
        data.activeThreadId = thread.id
        trimHistory()
        return thread.toSnapshot()
    }

    @Synchronized
    fun select(threadId: String): ConversationSnapshot {
        require(data.threads.any { it.id == threadId }) { "Unknown conversation: $threadId" }
        data.activeThreadId = threadId
        return active()
    }

    @Synchronized
    fun setMode(mode: String) {
        require(mode == "agent" || mode == "ask") { "Unsupported mode: $mode" }
        mutableActive().mode = mode
    }

    @Synchronized
    fun addMessage(role: String, content: String): ConversationMessage {
        val thread = mutableActive()
        val message = ConversationMessageState().apply {
            id = UUID.randomUUID().toString()
            this.role = role
            this.content = content
            createdAt = System.currentTimeMillis()
        }
        thread.messages.add(message)
        if (role == "user" && thread.messages.count { it.role == "user" } == 1) {
            thread.title = content.lineSequence().first().trim().take(48).ifEmpty { "New task" }
        }
        thread.updatedAt = message.createdAt
        if (thread.messages.size > MAX_MESSAGES_PER_THREAD) {
            thread.messages = thread.messages.takeLast(MAX_MESSAGES_PER_THREAD).toMutableList()
        }
        return message.toDomain()
    }

    private fun mutableActive(): ConversationThreadState {
        ensureActive()
        return requireNotNull(data.threads.firstOrNull { it.id == data.activeThreadId })
    }

    private fun ensureActive() {
        if (data.threads.isEmpty()) {
            newThread()
            return
        }
        if (data.threads.none { it.id == data.activeThreadId }) {
            data.activeThreadId = data.threads.maxByOrNull { it.updatedAt }?.id.orEmpty()
        }
    }

    private fun trimHistory() {
        if (data.threads.size <= MAX_THREADS) return
        val keep = data.threads.sortedByDescending { it.updatedAt }.take(MAX_THREADS).mapTo(mutableSetOf()) { it.id }
        data.threads.removeIf { it.id !in keep }
    }

    private fun ConversationThreadState.toSnapshot() = ConversationSnapshot(
        id = id,
        title = title,
        updatedAt = updatedAt,
        mode = mode,
        messages = messages.map { it.toDomain() },
        active = id == data.activeThreadId,
    )

    private fun ConversationMessageState.toDomain() = ConversationMessage(id, role, content, createdAt)

    companion object {
        private const val MAX_THREADS = 50
        private const val MAX_MESSAGES_PER_THREAD = 200
    }
}

data class ConversationSnapshot(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val mode: String,
    val messages: List<ConversationMessage>,
    val active: Boolean,
)

data class ConversationMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

class ConversationStoreState {
    var activeThreadId: String = ""
    var threads: MutableList<ConversationThreadState> = mutableListOf()
}

class ConversationThreadState {
    var id: String = ""
    var title: String = "New task"
    var updatedAt: Long = 0
    var mode: String = "agent"
    var messages: MutableList<ConversationMessageState> = mutableListOf()
}

class ConversationMessageState {
    var id: String = ""
    var role: String = "user"
    var content: String = ""
    var createdAt: Long = 0
}
