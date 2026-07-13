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
        require(mode == "agent" || mode == "chat" || mode == "ask") { "Unsupported mode: $mode" }
        mutableActive().mode = mode
    }

    @Synchronized
    fun setSelectedSkills(skillIds: Collection<String>) {
        val selected = skillIds.distinct()
        require(selected.size <= MAX_SELECTED_SKILLS) { "Select at most $MAX_SELECTED_SKILLS skills" }
        mutableActive().selectedSkillIds = selected.toMutableList()
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

    @Synchronized
    fun appendMessage(messageId: String, delta: String): ConversationMessage {
        val thread = mutableActive()
        val message = requireNotNull(thread.messages.firstOrNull { it.id == messageId }) {
            "Unknown message: $messageId"
        }
        message.content += delta
        thread.updatedAt = System.currentTimeMillis()
        return message.toDomain()
    }

    @Synchronized
    fun replaceMessage(messageId: String, content: String): ConversationMessage {
        val thread = mutableActive()
        val message = requireNotNull(thread.messages.firstOrNull { it.id == messageId }) {
            "Unknown message: $messageId"
        }
        message.content = content
        thread.updatedAt = System.currentTimeMillis()
        return message.toDomain()
    }

    @Synchronized
    fun addTasks(names: List<String>): List<ConversationTask> {
        require(names.isNotEmpty()) { "At least one task is required" }
        require(names.size <= MAX_TASKS_PER_OPERATION) { "Add at most $MAX_TASKS_PER_OPERATION tasks at once" }
        val thread = mutableActive()
        val available = MAX_TASKS_PER_THREAD - thread.tasks.size
        require(available >= names.size) { "A thread can contain at most $MAX_TASKS_PER_THREAD tasks" }
        val added = names.map { name ->
            require(name.isNotBlank()) { "Task names must not be blank" }
            ConversationTaskState().apply {
                id = UUID.randomUUID().toString()
                this.name = name.trim().take(MAX_TASK_NAME_CHARS)
                state = "not_started"
            }.also(thread.tasks::add)
        }
        thread.updatedAt = System.currentTimeMillis()
        return added.map { it.toDomain() }
    }

    @Synchronized
    fun updateTask(taskId: String, state: String?, name: String?): ConversationTask {
        val task = requireNotNull(mutableActive().tasks.firstOrNull { it.id == taskId }) { "Unknown task: $taskId" }
        state?.let {
            require(it in TASK_STATES) { "Unsupported task state: $it" }
            task.state = it
        }
        name?.let {
            require(it.isNotBlank()) { "Task name must not be blank" }
            task.name = it.trim().take(MAX_TASK_NAME_CHARS)
        }
        mutableActive().updatedAt = System.currentTimeMillis()
        return task.toDomain()
    }

    @Synchronized
    fun reorderTasks(taskIds: List<String>): List<ConversationTask> {
        val thread = mutableActive()
        require(taskIds.size == taskIds.distinct().size) { "Task IDs must be unique" }
        require(taskIds.toSet() == thread.tasks.mapTo(mutableSetOf()) { it.id }) {
            "Reordering requires every current task ID exactly once"
        }
        val byId = thread.tasks.associateBy { it.id }
        thread.tasks = taskIds.mapTo(mutableListOf()) { requireNotNull(byId[it]) }
        thread.updatedAt = System.currentTimeMillis()
        return thread.tasks.map { it.toDomain() }
    }

    @Synchronized
    fun clearCompletedTasks() {
        val thread = mutableActive()
        thread.tasks.removeIf { it.state == "completed" || it.state == "cancelled" }
        thread.updatedAt = System.currentTimeMillis()
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
        selectedSkillIds = selectedSkillIds.toList(),
        messages = messages.map { it.toDomain() },
        tasks = tasks.map { it.toDomain() },
        active = id == data.activeThreadId,
    )

    private fun ConversationMessageState.toDomain() = ConversationMessage(id, role, content, createdAt)

    private fun ConversationTaskState.toDomain() = ConversationTask(id, name, state)

    companion object {
        private const val MAX_THREADS = 50
        private const val MAX_MESSAGES_PER_THREAD = 200
        private const val MAX_TASKS_PER_THREAD = 100
        private const val MAX_TASKS_PER_OPERATION = 20
        private const val MAX_TASK_NAME_CHARS = 240
        private val TASK_STATES = setOf("not_started", "in_progress", "completed", "cancelled")
        const val MAX_SELECTED_SKILLS = 8
    }
}

data class ConversationSnapshot(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val mode: String,
    val selectedSkillIds: List<String>,
    val messages: List<ConversationMessage>,
    val tasks: List<ConversationTask>,
    val active: Boolean,
)

data class ConversationMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
)

data class ConversationTask(
    val id: String,
    val name: String,
    val state: String,
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
    var selectedSkillIds: MutableList<String> = mutableListOf()
    var messages: MutableList<ConversationMessageState> = mutableListOf()
    var tasks: MutableList<ConversationTaskState> = mutableListOf()
}

class ConversationMessageState {
    var id: String = ""
    var role: String = "user"
    var content: String = ""
    var createdAt: Long = 0
}

class ConversationTaskState {
    var id: String = ""
    var name: String = ""
    var state: String = "not_started"
}
