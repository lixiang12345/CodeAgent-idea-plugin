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
        normalizePersistedThreads()
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
        return data.threads.sortedWith(compareByDescending<ConversationThreadState> { it.pinned }.thenByDescending { it.updatedAt })
            .map { it.toSnapshot() }
    }

    @Synchronized
    fun newThread(mode: String = "agent"): ConversationSnapshot {
        pauseActiveMessageQueue()
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
    fun continueTasksInNewThread(): ConversationSnapshot {
        val source = mutableActive()
        require(source.tasks.isNotEmpty()) { "The current task list is empty" }
        pauseMessageQueue(source)
        val thread = ConversationThreadState().apply {
            id = UUID.randomUUID().toString()
            title = "${source.title} tasks".take(48)
            updatedAt = System.currentTimeMillis()
            mode = source.mode
            selectedAgentProfileId = source.selectedAgentProfileId
            selectedModelId = source.selectedModelId
            selectedSkillIds = source.selectedSkillIds.toMutableList()
            selectedRuleIds = source.selectedRuleIds.toMutableList()
            tasks = source.tasks.mapTo(mutableListOf()) { task ->
                ConversationTaskState().apply {
                    id = UUID.randomUUID().toString()
                    name = task.name
                    state = task.state
                }
            }
        }
        data.threads.add(thread)
        data.activeThreadId = thread.id
        trimHistory()
        return thread.toSnapshot()
    }

    @Synchronized
    fun select(threadId: String): ConversationSnapshot {
        require(data.threads.any { it.id == threadId }) { "Unknown conversation: $threadId" }
        if (data.activeThreadId != threadId) pauseActiveMessageQueue()
        data.activeThreadId = threadId
        return active()
    }

    @Synchronized
    fun contextUsage(threadId: String): ConversationContextUsage? {
        return data.threads.firstOrNull { it.id == threadId }?.contextUsage?.toDomain()
    }

    @Synchronized
    fun setContextUsage(usage: ConversationContextUsage) {
        mutableActive().contextUsage = usage.normalized().toState()
    }

    @Synchronized
    fun messageQueue(): ConversationMessageQueue = mutableActive().toMessageQueue()

    @Synchronized
    fun messageQueue(threadId: String): ConversationMessageQueue {
        val thread = data.threads.firstOrNull { it.id == threadId }
            ?: return ConversationMessageQueue()
        return thread.toMessageQueue()
    }

    @Synchronized
    fun enqueueMessage(message: ConversationQueuedMessage): ConversationQueuedMessage {
        val thread = mutableActive()
        val normalized = message.normalized()
        require(thread.messageQueue.size < MAX_QUEUED_MESSAGES) {
            "Queue can contain at most $MAX_QUEUED_MESSAGES messages"
        }
        require(thread.messageQueue.none { it.id == normalized.id } && thread.messages.none { it.id == normalized.id }) {
            "Queued message ID already exists"
        }
        thread.messageQueue += normalized.toState()
        return normalized
    }

    @Synchronized
    fun updateQueuedMessage(messageId: String, text: String): ConversationQueuedMessage {
        val thread = mutableActive()
        val state = thread.messageQueue.firstOrNull { it.id == messageId }
            ?: error("Queued message no longer exists")
        val updated = ConversationQueuedMessage(state.id, text, state.mode).normalized()
        state.text = updated.text
        return updated
    }

    @Synchronized
    fun removeQueuedMessage(messageId: String): ConversationQueuedMessage {
        val thread = mutableActive()
        val index = thread.messageQueue.indexOfFirst { it.id == messageId }
        require(index >= 0) { "Queued message no longer exists" }
        return thread.messageQueue.removeAt(index).toDomain().also {
            if (thread.messageQueue.isEmpty()) thread.messageQueuePaused = false
        }
    }

    @Synchronized
    fun takeNextQueuedMessage(): ConversationQueuedMessage? {
        val thread = mutableActive()
        if (thread.messageQueuePaused || thread.messageQueue.isEmpty()) return null
        return thread.messageQueue.removeAt(0).toDomain().also {
            if (thread.messageQueue.isEmpty()) thread.messageQueuePaused = false
        }
    }

    @Synchronized
    fun requeueMessageFirst(message: ConversationQueuedMessage) {
        val thread = mutableActive()
        val normalized = message.normalized()
        require(thread.messageQueue.size < MAX_QUEUED_MESSAGES) {
            "Queue can contain at most $MAX_QUEUED_MESSAGES messages"
        }
        require(thread.messageQueue.none { it.id == normalized.id }) { "Queued message ID already exists" }
        thread.messageQueue.add(0, normalized.toState())
    }

    @Synchronized
    fun setMessageQueuePaused(paused: Boolean): ConversationMessageQueue {
        val thread = mutableActive()
        thread.messageQueuePaused = paused && thread.messageQueue.isNotEmpty()
        return thread.toMessageQueue()
    }

    @Synchronized
    fun unreadCount(threadId: String): Int {
        val thread = data.threads.firstOrNull { it.id == threadId } ?: return 0
        return thread.messages.count { message ->
            message.role == "assistant" &&
                message.content.isNotBlank() &&
                message.timelineSequence > thread.lastReadTimelineSequence
        }
    }

    @Synchronized
    fun markReadIfPresent(threadId: String, throughTimelineSequence: Long): Boolean {
        val thread = data.threads.firstOrNull { it.id == threadId } ?: return false
        val latestMessageSequence = thread.messages.maxOfOrNull { it.timelineSequence } ?: 0
        val next = throughTimelineSequence.coerceIn(0, latestMessageSequence)
        if (next <= thread.lastReadTimelineSequence) return false
        thread.lastReadTimelineSequence = next
        return true
    }

    @Synchronized
    fun togglePinned(threadId: String): ConversationSnapshot {
        return requireNotNull(togglePinnedIfPresent(threadId)) { "Unknown conversation: $threadId" }
    }

    @Synchronized
    fun togglePinnedIfPresent(threadId: String): ConversationSnapshot? {
        val thread = data.threads.firstOrNull { it.id == threadId } ?: return null
        updatePinned(thread, !thread.pinned)
        return thread.toSnapshot()
    }

    @Synchronized
    fun setPinnedIfPresent(threadId: String, pinned: Boolean): ConversationSnapshot? {
        val thread = data.threads.firstOrNull { it.id == threadId } ?: return null
        updatePinned(thread, pinned)
        return thread.toSnapshot()
    }

    @Synchronized
    fun renameThread(threadId: String, title: String): ConversationSnapshot {
        val thread = requireNotNull(data.threads.firstOrNull { it.id == threadId }) { "Unknown conversation: $threadId" }
        val next = title.trim().take(48)
        require(next.isNotEmpty()) { "Thread title must not be blank" }
        thread.title = next
        thread.updatedAt = System.currentTimeMillis()
        return thread.toSnapshot()
    }

    @Synchronized
    fun setSummary(threadId: String, summary: String): ConversationSnapshot {
        val thread = requireNotNull(data.threads.firstOrNull { it.id == threadId }) { "Unknown conversation: $threadId" }
        val next = summary.trim().take(MAX_SUMMARY_CHARS)
        require(next.isNotEmpty()) { "Conversation summary must not be blank" }
        if (thread.summary != next) {
            thread.summary = next
            thread.updatedAt = System.currentTimeMillis()
        }
        return thread.toSnapshot()
    }

    @Synchronized
    fun clearSummary(threadId: String): ConversationSnapshot {
        val thread = requireNotNull(data.threads.firstOrNull { it.id == threadId }) { "Unknown conversation: $threadId" }
        if (thread.summary.isNotBlank()) {
            thread.summary = ""
            thread.updatedAt = System.currentTimeMillis()
        }
        return thread.toSnapshot()
    }

    @Synchronized
    fun deleteThread(threadId: String): ConversationSnapshot {
        return requireNotNull(deleteThreadIfPresent(threadId)) { "Unknown conversation: $threadId" }
    }

    @Synchronized
    fun deleteThreadIfPresent(threadId: String): ConversationSnapshot? {
        if (!data.threads.removeIf { it.id == threadId }) return null
        if (threadId !in data.deletedCloudThreadIds) {
            data.deletedCloudThreadIds.add(threadId)
            if (data.deletedCloudThreadIds.size > MAX_CLOUD_TOMBSTONES) {
                data.deletedCloudThreadIds = data.deletedCloudThreadIds.takeLast(MAX_CLOUD_TOMBSTONES).toMutableList()
            }
        }
        if (data.activeThreadId == threadId) data.activeThreadId = ""
        ensureActive()
        return active()
    }

    @Synchronized
    fun pendingCloudDeletions(): List<String> = data.deletedCloudThreadIds.toList()

    @Synchronized
    fun acknowledgeCloudDeletion(threadId: String) {
        data.deletedCloudThreadIds.remove(threadId)
    }

    @Synchronized
    fun importThread(title: String, mode: String, messages: List<Pair<String, String>>): ConversationSnapshot {
        require(mode == "agent" || mode == "chat" || mode == "ask") { "Unsupported mode: $mode" }
        require(messages.isNotEmpty()) { "Imported thread contains no messages" }
        require(messages.size <= MAX_MESSAGES_PER_THREAD) { "Imported thread contains too many messages" }
        val now = System.currentTimeMillis()
        val thread = ConversationThreadState().apply {
            id = UUID.randomUUID().toString()
            this.title = title.trim().take(48).ifBlank { "Imported thread" }
            updatedAt = now
            this.mode = mode
            this.messages = messages.mapIndexedTo(mutableListOf()) { index, (role, content) ->
                require(role == "user" || role == "assistant") { "Unsupported imported message role: $role" }
                require(content.isNotBlank()) { "Imported messages must not be blank" }
                ConversationMessageState().apply {
                    id = UUID.randomUUID().toString()
                    this.role = role
                    this.content = content.take(MAX_IMPORTED_MESSAGE_CHARS)
                    createdAt = now + index
                    timelineSequence = index + 1L
                }
            }
        }
        pauseActiveMessageQueue()
        data.threads.add(thread)
        data.activeThreadId = thread.id
        trimHistory()
        return thread.toSnapshot()
    }

    @Synchronized
    fun setMode(mode: String) {
        require(mode == "agent" || mode == "chat" || mode == "ask") { "Unsupported mode: $mode" }
        val thread = mutableActive()
        if (thread.mode != mode) {
            thread.mode = mode
            thread.updatedAt = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun setSelectedModel(modelId: String?) {
        val normalized = modelId?.trim()?.takeIf { it.isNotEmpty() }.orEmpty()
        require(normalized.length <= MAX_MODEL_ID_CHARS) { "Model ID is too long" }
        val thread = mutableActive()
        if (thread.selectedModelId != normalized) {
            thread.selectedModelId = normalized
            thread.updatedAt = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun setSelectedAgentProfile(agentProfileId: String) {
        val normalized = agentProfileId.trim()
        require(AGENT_PROFILE_ID.matches(normalized)) { "Agent profile ID is invalid" }
        val thread = mutableActive()
        if (thread.selectedAgentProfileId != normalized) {
            thread.selectedAgentProfileId = normalized
            thread.updatedAt = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun setSelectedSkills(skillIds: Collection<String>) {
        val selected = skillIds.distinct()
        require(selected.size <= MAX_SELECTED_SKILLS) { "Select at most $MAX_SELECTED_SKILLS skills" }
        val thread = mutableActive()
        if (thread.selectedSkillIds != selected) {
            thread.selectedSkillIds = selected.toMutableList()
            thread.updatedAt = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun setSelectedRules(ruleIds: Collection<String>) {
        val selected = ruleIds.distinct()
        require(selected.size <= MAX_SELECTED_RULES) { "Select at most $MAX_SELECTED_RULES manual rules" }
        val thread = mutableActive()
        if (thread.selectedRuleIds != selected) {
            thread.selectedRuleIds = selected.toMutableList()
            thread.updatedAt = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun addMessage(
        role: String,
        content: String,
        runId: String? = null,
        turnIndex: Int? = null,
        messageId: String? = null,
    ): ConversationMessage {
        val thread = mutableActive()
        val normalizedMessageId = messageId?.trim()?.takeIf { it.isNotEmpty() }
        require(normalizedMessageId == null || normalizedMessageId.matches(Regex("[A-Za-z0-9._-]{1,200}"))) {
            "Message ID is invalid"
        }
        require(normalizedMessageId == null || thread.messages.none { it.id == normalizedMessageId }) {
            "Message ID already exists: $normalizedMessageId"
        }
        val message = ConversationMessageState().apply {
            id = normalizedMessageId ?: UUID.randomUUID().toString()
            this.role = role
            this.content = content
            createdAt = System.currentTimeMillis()
            this.runId = runId.orEmpty()
            this.turnIndex = turnIndex ?: -1
            timelineSequence = nextTimelineSequence(thread)
        }
        if (role == "user") thread.contextUsage = ConversationContextUsageState()
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
    fun rewindFromUserMessage(messageId: String): ConversationMessage {
        val thread = mutableActive()
        val target = requireNotNull(thread.messages.firstOrNull { it.id == messageId }) {
            "Unknown message: $messageId"
        }
        require(target.role == "user") { "Only user messages can be edited or resent" }
        val targetSequence = target.timelineSequence
        require(thread.tools.none { it.timelineSequence > targetSequence && it.canRevert }) {
            "Keep or discard pending file changes before editing this message"
        }

        val original = target.toDomain()
        thread.messages.removeIf { it.timelineSequence >= targetSequence }
        thread.tools.removeIf { it.timelineSequence > targetSequence }
        thread.summary = ""
        thread.contextUsage = ConversationContextUsageState()
        val latestRemainingMessage = thread.messages.maxOfOrNull { it.timelineSequence } ?: 0
        thread.lastReadTimelineSequence = thread.lastReadTimelineSequence.coerceIn(0, latestRemainingMessage)
        thread.updatedAt = System.currentTimeMillis()
        return original
    }

    @Synchronized
    fun upsertTool(tool: ConversationTool): ConversationTool {
        require(tool.id.isNotBlank()) { "Tool ID must not be blank" }
        require(tool.name.isNotBlank()) { "Tool name must not be blank" }
        require(tool.status in TOOL_STATES) { "Unsupported tool state: ${tool.status}" }
        require(tool.summary.length <= MAX_TOOL_SUMMARY_CHARS) { "Tool summary is too long" }
        require(tool.detail.orEmpty().length <= MAX_TOOL_DETAIL_CHARS) { "Tool detail is too long" }
        val thread = mutableActive()
        val state = thread.tools.firstOrNull { it.id == tool.id } ?: ConversationToolState().also(thread.tools::add)
        if (state.timelineSequence <= 0) {
            state.timelineSequence = tool.timelineSequence.takeIf { it > 0 } ?: nextTimelineSequence(thread)
        }
        state.id = tool.id
        state.name = tool.name
        state.summary = tool.summary
        state.status = tool.status
        state.detail = tool.detail.orEmpty()
        state.changePath = tool.changePath.orEmpty()
        state.canRevert = tool.canRevert
        state.runId = tool.runId.orEmpty()
        state.turnIndex = tool.turnIndex ?: -1
        state.createdAt = if (state.createdAt > 0) state.createdAt else tool.createdAt
        state.updatedAt = maxOf(tool.updatedAt, state.createdAt)
        if (thread.tools.size > MAX_TOOLS_PER_THREAD) {
            thread.tools = thread.tools.takeLast(MAX_TOOLS_PER_THREAD).toMutableList()
        }
        thread.updatedAt = System.currentTimeMillis()
        return state.toDomain()
    }

    @Synchronized
    fun interruptTools(reason: String): List<ConversationTool> {
        val normalizedReason = reason.replace(Regex("\\s+"), " ").trim().take(240).ifBlank { "Run interrupted" }
        val thread = mutableActive()
        val interrupted = thread.tools.filter { it.status == "approval" || it.status == "running" }
        if (interrupted.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        interrupted.forEach { tool ->
            if (tool.status == "approval") {
                tool.status = "rejected"
                tool.summary = "Approval expired: $normalizedReason"
            } else {
                tool.status = "failed"
                tool.summary = "Interrupted: $normalizedReason"
            }
            tool.canRevert = false
            tool.updatedAt = now
        }
        thread.updatedAt = now
        return interrupted.map { it.toDomain() }
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

    @Synchronized
    fun deleteTask(taskId: String) {
        val thread = mutableActive()
        require(thread.tasks.removeIf { it.id == taskId }) { "Unknown task: $taskId" }
        thread.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun clearTasks() {
        val thread = mutableActive()
        thread.tasks.clear()
        thread.updatedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun importTasks(tasks: List<Pair<String, String>>): List<ConversationTask> {
        require(tasks.isNotEmpty()) { "No tasks found in the selected file" }
        val thread = mutableActive()
        require(thread.tasks.size + tasks.size <= MAX_TASKS_PER_THREAD) {
            "A thread can contain at most $MAX_TASKS_PER_THREAD tasks"
        }
        val imported = tasks.map { (name, state) ->
            require(name.isNotBlank()) { "Task names must not be blank" }
            require(state in TASK_STATES) { "Unsupported task state: $state" }
            ConversationTaskState().apply {
                id = UUID.randomUUID().toString()
                this.name = name.trim().take(MAX_TASK_NAME_CHARS)
                this.state = state
            }.also(thread.tasks::add)
        }
        thread.updatedAt = System.currentTimeMillis()
        return imported.map { it.toDomain() }
    }

    @Synchronized
    fun mergeCloudSnapshot(remote: List<ConversationSnapshot>): CloudMergeResult =
        mergeCloud(remote, completeSnapshot = true)

    @Synchronized
    fun mergeCloudConversation(remote: ConversationSnapshot): CloudMergeResult =
        mergeCloud(listOf(remote), completeSnapshot = false)

    private fun mergeCloud(remote: List<ConversationSnapshot>, completeSnapshot: Boolean): CloudMergeResult {
        require(remote.map { it.id }.distinct().size == remote.size) { "Cloud conversation IDs must be unique" }
        val acceptedRemote = remote.filter { it.id !in data.deletedCloudThreadIds }
        acceptedRemote.forEach(::validateCloudConversation)
        val remoteById = acceptedRemote.associateBy { it.id }
        var changed = false

        if (completeSnapshot && acceptedRemote.isNotEmpty()) {
            val activeState = data.threads.firstOrNull { it.id == data.activeThreadId }
            if (activeState?.isPristine() == true && activeState.id !in remoteById) {
                data.threads.remove(activeState)
                changed = true
            }
        }

        val uploadIds = linkedSetOf<String>()
        acceptedRemote.forEach { incoming ->
            val index = data.threads.indexOfFirst { it.id == incoming.id }
            if (index < 0) {
                data.threads.add(incoming.toState())
                changed = true
            } else {
                val local = data.threads[index]
                when {
                    incoming.updatedAt > local.updatedAt -> {
                        data.threads[index] = incoming.toState().also { replacement ->
                            replacement.lastReadTimelineSequence = minOf(
                                local.lastReadTimelineSequence,
                                replacement.messages.maxOfOrNull { it.timelineSequence } ?: 0,
                            )
                            replacement.restoreLocalRuntimeState(local)
                        }
                        changed = true
                    }
                    incoming.updatedAt < local.updatedAt -> uploadIds += local.id
                    local.toSnapshot().copy(active = incoming.active) != incoming -> {
                        data.threads[index] = incoming.toState().also { it.restoreLocalRuntimeState(local) }
                        changed = true
                    }
                }
            }
        }

        if (completeSnapshot) {
            for (thread in data.threads) {
                if (thread.id !in remoteById && !thread.isPristine()) uploadIds += thread.id
            }
        }
        if (data.threads.none { it.id == data.activeThreadId }) {
            data.activeThreadId = data.threads.maxByOrNull { it.updatedAt }?.id.orEmpty()
            changed = true
        }
        ensureActive()
        trimHistory()
        return CloudMergeResult(
            changed = changed,
            upload = data.threads.filter { it.id in uploadIds }.map { it.toSnapshot() },
        )
    }

    private fun validateCloudConversation(conversation: ConversationSnapshot) {
        require(conversation.id.isNotBlank() && conversation.id.length <= 200) { "Cloud conversation ID is invalid" }
        require(conversation.title.isNotBlank() && conversation.title.length <= 200) { "Cloud conversation title is invalid" }
        require(conversation.mode in setOf("agent", "chat", "ask")) { "Cloud conversation mode is invalid" }
        require(conversation.updatedAt >= 0) { "Cloud conversation timestamp is invalid" }
        require(AGENT_PROFILE_ID.matches(conversation.selectedAgentProfileId)) { "Cloud Agent profile ID is invalid" }
        require(conversation.selectedSkillIds.size <= MAX_SELECTED_SKILLS) { "Cloud conversation has too many skills" }
        require(conversation.selectedRuleIds.size <= MAX_SELECTED_RULES) { "Cloud conversation has too many rules" }
        require(conversation.messages.size <= MAX_MESSAGES_PER_THREAD) { "Cloud conversation has too many messages" }
        require(conversation.tasks.size <= MAX_TASKS_PER_THREAD) { "Cloud conversation has too many tasks" }
        require(conversation.tools.size <= MAX_TOOLS_PER_THREAD) { "Cloud conversation has too many tools" }
        require(conversation.messages.all { it.role == "user" || it.role == "assistant" }) { "Cloud message role is invalid" }
        require(conversation.tasks.all { it.state in TASK_STATES }) { "Cloud task state is invalid" }
        require(conversation.tools.all { it.status in TOOL_STATES }) { "Cloud tool state is invalid" }
        require(conversation.messages.map { it.id }.distinct().size == conversation.messages.size) { "Cloud message IDs must be unique" }
        require(conversation.tasks.map { it.id }.distinct().size == conversation.tasks.size) { "Cloud task IDs must be unique" }
        require(conversation.tools.map { it.id }.distinct().size == conversation.tools.size) { "Cloud tool IDs must be unique" }
        require(conversation.summary.orEmpty().length <= MAX_SUMMARY_CHARS) { "Cloud summary is too long" }
    }

    private fun ConversationSnapshot.toState() = ConversationThreadState().also { state ->
        state.id = id
        state.title = title
        state.updatedAt = updatedAt
        state.mode = mode
        state.selectedAgentProfileId = selectedAgentProfileId
        state.selectedModelId = selectedModelId.orEmpty()
        state.selectedSkillIds = selectedSkillIds.toMutableList()
        state.selectedRuleIds = selectedRuleIds.toMutableList()
        state.messages = messages.mapTo(mutableListOf()) { message ->
            ConversationMessageState().also {
                it.id = message.id
                it.role = message.role
                it.content = message.content
                it.createdAt = message.createdAt
                it.runId = message.runId.orEmpty()
                it.turnIndex = message.turnIndex ?: -1
                it.timelineSequence = message.timelineSequence
            }
        }
        state.tasks = tasks.mapTo(mutableListOf()) { task ->
            ConversationTaskState().also {
                it.id = task.id
                it.name = task.name
                it.state = task.state
            }
        }
        state.tools = tools.mapTo(mutableListOf()) { tool ->
            ConversationToolState().also {
                it.id = tool.id
                it.name = tool.name
                it.summary = tool.summary
                it.status = tool.status
                it.detail = tool.detail.orEmpty()
                it.changePath = tool.changePath.orEmpty()
                it.canRevert = tool.canRevert
                it.runId = tool.runId.orEmpty()
                it.turnIndex = tool.turnIndex ?: -1
                it.createdAt = tool.createdAt
                it.updatedAt = tool.updatedAt
                it.timelineSequence = tool.timelineSequence
            }
        }
        state.pinned = pinned
        state.summary = summary.orEmpty()
        normalizeTimelineSequences(state)
        state.lastReadTimelineSequence = state.messages.maxOfOrNull { it.timelineSequence } ?: 0
    }

    private fun ConversationThreadState.isPristine(): Boolean =
        title == "New task" && messages.isEmpty() && tasks.isEmpty() && tools.isEmpty() && messageQueue.isEmpty() && !pinned && summary.isBlank()

    private fun ConversationThreadState.restoreLocalRuntimeState(local: ConversationThreadState) {
        contextUsage = local.contextUsage.toDomain()?.toState() ?: ConversationContextUsageState()
        messageQueue = local.messageQueue.mapTo(mutableListOf()) { it.toDomain().toState() }
        messageQueuePaused = local.messageQueuePaused && messageQueue.isNotEmpty()
    }

    private fun mutableActive(): ConversationThreadState {
        ensureActive()
        return requireNotNull(data.threads.firstOrNull { it.id == data.activeThreadId })
    }

    private fun pauseActiveMessageQueue() {
        data.threads.firstOrNull { it.id == data.activeThreadId }?.let(::pauseMessageQueue)
    }

    private fun pauseMessageQueue(thread: ConversationThreadState) {
        if (thread.messageQueue.isNotEmpty()) thread.messageQueuePaused = true
    }

    private fun updatePinned(thread: ConversationThreadState, pinned: Boolean) {
        if (thread.pinned == pinned) return
        thread.pinned = pinned
        thread.updatedAt = System.currentTimeMillis()
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

    private fun normalizePersistedThreads() {
        data.threads.forEach { thread ->
            thread.tasks = thread.tasks.asSequence()
                .filter { task ->
                    task.id.isNotBlank() &&
                        task.name.isNotBlank() &&
                        task.state in TASK_STATES
                }
                .distinctBy { it.id }
                .take(MAX_TASKS_PER_THREAD)
                .toList()
                .toMutableList()
            thread.contextUsage = thread.contextUsage.toDomain()?.normalized()?.toState()
                ?: ConversationContextUsageState()
            thread.messageQueue = thread.messageQueue.asSequence()
                .mapNotNull { it.toDomainOrNull() }
                .distinctBy { it.id }
                .take(MAX_QUEUED_MESSAGES)
                .mapTo(mutableListOf()) { it.toState() }
            thread.messageQueuePaused = thread.messageQueue.isNotEmpty()
            normalizeTimelineSequences(thread)
            if (thread.lastReadTimelineSequence < 0) {
                thread.lastReadTimelineSequence = thread.messages.maxOfOrNull { it.timelineSequence } ?: 0
            }
        }
        trimHistory()
    }

    private fun normalizeTimelineSequences(thread: ConversationThreadState) {
        val used = buildList {
            thread.messages.mapTo(this) { it.timelineSequence }
            thread.tools.mapTo(this) { it.timelineSequence }
        }.filterTo(mutableSetOf()) { it > 0 }
        var next = used.maxOrNull() ?: 0L
        val missing = mutableListOf<TimelineSequenceCandidate>()
        thread.messages.forEachIndexed { index, message ->
            if (message.timelineSequence <= 0) {
                missing += TimelineSequenceCandidate(
                    createdAt = message.createdAt,
                    phase = when {
                        message.role == "user" -> -1
                        message.turnIndex >= 0 -> message.turnIndex * 2
                        else -> 0
                    },
                    index = index,
                    assign = { message.timelineSequence = it },
                )
            }
        }
        thread.tools.forEachIndexed { index, tool ->
            if (tool.timelineSequence <= 0) {
                missing += TimelineSequenceCandidate(
                    createdAt = tool.createdAt,
                    phase = if (tool.turnIndex >= 0) tool.turnIndex * 2 + 1 else 1,
                    index = thread.messages.size + index,
                    assign = { tool.timelineSequence = it },
                )
            }
        }
        missing.sortedWith(
            compareBy<TimelineSequenceCandidate> { it.createdAt }
                .thenBy { it.phase }
                .thenBy { it.index },
        ).forEach { candidate ->
            next += 1
            candidate.assign(next)
        }
    }

    private fun nextTimelineSequence(thread: ConversationThreadState): Long =
        maxOf(
            thread.messages.maxOfOrNull { it.timelineSequence } ?: 0,
            thread.tools.maxOfOrNull { it.timelineSequence } ?: 0,
        ) + 1

    private fun trimHistory() {
        if (data.threads.size <= MAX_THREADS) return
        val keep = data.threads.sortedWith(compareByDescending<ConversationThreadState> { it.pinned }.thenByDescending { it.updatedAt })
            .take(MAX_THREADS)
            .mapTo(mutableSetOf()) { it.id }
        data.threads.removeIf { it.id !in keep }
    }

    private fun ConversationThreadState.toSnapshot() = ConversationSnapshot(
        id = id,
        title = title,
        updatedAt = updatedAt,
        mode = mode,
        selectedAgentProfileId = selectedAgentProfileId,
        selectedModelId = selectedModelId.takeIf { it.isNotBlank() },
        selectedSkillIds = selectedSkillIds.toList(),
        selectedRuleIds = selectedRuleIds.toList(),
        messages = messages.map { it.toDomain() },
        tasks = tasks.map { it.toDomain() },
        active = id == data.activeThreadId,
        pinned = pinned,
        summary = summary.takeIf { it.isNotBlank() },
        tools = tools.map { it.toDomain() },
    )

    private fun ConversationMessageState.toDomain() = ConversationMessage(
        id = id,
        role = role,
        content = content,
        createdAt = createdAt,
        timelineSequence = timelineSequence.takeIf { it > 0 } ?: 0,
        runId = runId.takeIf(String::isNotBlank),
        turnIndex = turnIndex.takeIf { it >= 0 },
    )

    private fun ConversationTaskState.toDomain() = ConversationTask(id, name, state)

    private fun ConversationThreadState.toMessageQueue() = ConversationMessageQueue(
        messages = messageQueue.map { it.toDomain() },
        paused = messageQueuePaused && messageQueue.isNotEmpty(),
    )

    private fun ConversationQueuedMessageState.toDomain() = ConversationQueuedMessage(id, text, mode)

    private fun ConversationQueuedMessageState.toDomainOrNull(): ConversationQueuedMessage? = runCatching {
        ConversationQueuedMessage(id, text, mode).normalized()
    }.getOrNull()

    private fun ConversationQueuedMessage.toState() = ConversationQueuedMessageState().also { state ->
        state.id = id
        state.text = text
        state.mode = mode
    }

    private fun ConversationQueuedMessage.normalized(): ConversationQueuedMessage {
        val normalizedText = text.trim()
        require(id.isNotBlank() && id.length <= 200) { "Queued message ID is invalid" }
        require(normalizedText.isNotBlank()) { "Queued message must not be blank" }
        require(normalizedText.length <= MAX_QUEUE_MESSAGE_CHARS) {
            "Queued message exceeds $MAX_QUEUE_MESSAGE_CHARS characters"
        }
        require(mode in CONVERSATION_MODES) { "Queued message mode is invalid" }
        return copy(text = normalizedText)
    }

    private fun ConversationToolState.toDomain() = ConversationTool(
        id = id,
        name = name,
        summary = summary,
        status = status,
        detail = detail.takeIf(String::isNotBlank),
        changePath = changePath.takeIf(String::isNotBlank),
        canRevert = canRevert,
        runId = runId.takeIf(String::isNotBlank),
        turnIndex = turnIndex.takeIf { it >= 0 },
        createdAt = createdAt,
        updatedAt = updatedAt.takeIf { it > 0 } ?: createdAt,
        timelineSequence = timelineSequence.takeIf { it > 0 } ?: 0,
    )

    private fun ConversationContextUsageState.toDomain(): ConversationContextUsage? {
        if (contextWindowTokens <= 0) return null
        return ConversationContextUsage(
            turnIndex = turnIndex,
            estimatedInputTokens = estimatedInputTokens,
            targetInputTokens = targetInputTokens,
            contextWindowTokens = contextWindowTokens,
            reservedOutputTokens = reservedOutputTokens,
            retrievalBudgetTokens = retrievalBudgetTokens,
            toolDefinitionTokens = toolDefinitionTokens,
            assistantResponseTokens = assistantResponseTokens,
            compactedToolResults = compactedToolResults,
            truncatedMessages = truncatedMessages,
            compactionApplied = compactionApplied,
            overBudget = overBudget,
            activeToolCount = activeToolCount,
            catalogToolCount = catalogToolCount,
            discoverableToolCount = discoverableToolCount,
        )
    }

    private fun ConversationContextUsage.toState() = ConversationContextUsageState().also { state ->
        state.turnIndex = turnIndex
        state.estimatedInputTokens = estimatedInputTokens
        state.targetInputTokens = targetInputTokens
        state.contextWindowTokens = contextWindowTokens
        state.reservedOutputTokens = reservedOutputTokens
        state.retrievalBudgetTokens = retrievalBudgetTokens
        state.toolDefinitionTokens = toolDefinitionTokens
        state.assistantResponseTokens = assistantResponseTokens
        state.compactedToolResults = compactedToolResults
        state.truncatedMessages = truncatedMessages
        state.compactionApplied = compactionApplied
        state.overBudget = overBudget
        state.activeToolCount = activeToolCount
        state.catalogToolCount = catalogToolCount
        state.discoverableToolCount = discoverableToolCount
    }

    private fun ConversationContextUsage.normalized() = copy(
        turnIndex = turnIndex.coerceAtLeast(0),
        estimatedInputTokens = estimatedInputTokens.coerceIn(0, MAX_CONTEXT_USAGE_TOKENS),
        targetInputTokens = targetInputTokens.coerceIn(0, MAX_CONTEXT_USAGE_TOKENS),
        contextWindowTokens = contextWindowTokens.coerceIn(0, MAX_CONTEXT_USAGE_TOKENS),
        reservedOutputTokens = reservedOutputTokens.coerceIn(0, MAX_CONTEXT_USAGE_TOKENS),
        retrievalBudgetTokens = retrievalBudgetTokens.coerceIn(0, MAX_CONTEXT_USAGE_TOKENS),
        toolDefinitionTokens = toolDefinitionTokens.coerceIn(0, MAX_CONTEXT_USAGE_TOKENS),
        assistantResponseTokens = assistantResponseTokens.coerceIn(0, MAX_CONTEXT_USAGE_TOKENS),
        compactedToolResults = compactedToolResults.coerceIn(0, MAX_CONTEXT_USAGE_ITEMS),
        truncatedMessages = truncatedMessages.coerceIn(0, MAX_CONTEXT_USAGE_ITEMS),
        activeToolCount = activeToolCount.coerceIn(0, MAX_CONTEXT_USAGE_ITEMS),
        catalogToolCount = catalogToolCount.coerceIn(0, MAX_CONTEXT_USAGE_ITEMS),
        discoverableToolCount = discoverableToolCount.coerceIn(0, MAX_CONTEXT_USAGE_ITEMS),
    )

    companion object {
        private const val MAX_THREADS = 50
        private const val MAX_MESSAGES_PER_THREAD = 200
        private const val MAX_TASKS_PER_THREAD = 100
        private const val MAX_TOOLS_PER_THREAD = 1_000
        private const val MAX_TASKS_PER_OPERATION = 20
        private const val MAX_TASK_NAME_CHARS = 240
        private const val MAX_TOOL_SUMMARY_CHARS = 8_000
        private const val MAX_TOOL_DETAIL_CHARS = 100_000
        private const val MAX_IMPORTED_MESSAGE_CHARS = 40_000
        private const val MAX_MODEL_ID_CHARS = 240
        private val AGENT_PROFILE_ID = Regex("^[A-Za-z0-9._-]{1,120}$")
        private const val MAX_SUMMARY_CHARS = 20_000
        private const val MAX_CLOUD_TOMBSTONES = 200
        private const val MAX_CONTEXT_USAGE_TOKENS = 4_000_000
        private const val MAX_CONTEXT_USAGE_ITEMS = 100_000
        private const val MAX_QUEUED_MESSAGES = 10
        private const val MAX_QUEUE_MESSAGE_CHARS = 40_000
        private val CONVERSATION_MODES = setOf("agent", "chat", "ask")
        private val TASK_STATES = setOf("not_started", "in_progress", "completed", "cancelled")
        private val TOOL_STATES = setOf("approval", "running", "completed", "failed", "rejected")
        const val MAX_SELECTED_SKILLS = 8
        const val MAX_SELECTED_RULES = 32
        const val MAX_IMPORT_TASKS = 100
    }
}

data class ConversationSnapshot(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val mode: String,
    val selectedAgentProfileId: String = "general",
    val selectedModelId: String?,
    val selectedSkillIds: List<String>,
    val selectedRuleIds: List<String>,
    val messages: List<ConversationMessage>,
    val tasks: List<ConversationTask>,
    val active: Boolean,
    val pinned: Boolean,
    val summary: String? = null,
    val tools: List<ConversationTool> = emptyList(),
)

data class CloudMergeResult(
    val changed: Boolean,
    val upload: List<ConversationSnapshot>,
)

data class ConversationMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val runId: String? = null,
    val turnIndex: Int? = null,
    val timelineSequence: Long = 0,
)

data class ConversationTask(
    val id: String,
    val name: String,
    val state: String,
)

data class ConversationTool(
    val id: String,
    val name: String,
    val summary: String,
    val status: String,
    val detail: String? = null,
    val changePath: String? = null,
    val canRevert: Boolean = false,
    val runId: String? = null,
    val turnIndex: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val timelineSequence: Long = 0,
)

data class ConversationContextUsage(
    val turnIndex: Int = 0,
    val estimatedInputTokens: Int = 0,
    val targetInputTokens: Int = 0,
    val contextWindowTokens: Int = 0,
    val reservedOutputTokens: Int = 0,
    val retrievalBudgetTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val assistantResponseTokens: Int = 0,
    val compactedToolResults: Int = 0,
    val truncatedMessages: Int = 0,
    val compactionApplied: Boolean = false,
    val overBudget: Boolean = false,
    val activeToolCount: Int = 0,
    val catalogToolCount: Int = 0,
    val discoverableToolCount: Int = 0,
)

data class ConversationQueuedMessage(
    val id: String,
    val text: String,
    val mode: String,
)

data class ConversationMessageQueue(
    val messages: List<ConversationQueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

class ConversationStoreState {
    var activeThreadId: String = ""
    var threads: MutableList<ConversationThreadState> = mutableListOf()
    var deletedCloudThreadIds: MutableList<String> = mutableListOf()
}

class ConversationThreadState {
    var id: String = ""
    var title: String = "New task"
    var updatedAt: Long = 0
    var mode: String = "agent"
    var selectedAgentProfileId: String = "general"
    var selectedModelId: String = ""
    var selectedSkillIds: MutableList<String> = mutableListOf()
    var selectedRuleIds: MutableList<String> = mutableListOf()
    var messages: MutableList<ConversationMessageState> = mutableListOf()
    var tasks: MutableList<ConversationTaskState> = mutableListOf()
    var tools: MutableList<ConversationToolState> = mutableListOf()
    var contextUsage: ConversationContextUsageState = ConversationContextUsageState()
    var messageQueue: MutableList<ConversationQueuedMessageState> = mutableListOf()
    var messageQueuePaused: Boolean = false
    var summary: String = ""

    var pinned: Boolean = false
    var lastReadTimelineSequence: Long = -1
}

class ConversationContextUsageState {
    var turnIndex: Int = 0
    var estimatedInputTokens: Int = 0
    var targetInputTokens: Int = 0
    var contextWindowTokens: Int = 0
    var reservedOutputTokens: Int = 0
    var retrievalBudgetTokens: Int = 0
    var toolDefinitionTokens: Int = 0
    var assistantResponseTokens: Int = 0
    var compactedToolResults: Int = 0
    var truncatedMessages: Int = 0
    var compactionApplied: Boolean = false
    var overBudget: Boolean = false
    var activeToolCount: Int = 0
    var catalogToolCount: Int = 0
    var discoverableToolCount: Int = 0
}

class ConversationQueuedMessageState {
    var id: String = ""
    var text: String = ""
    var mode: String = "agent"
}

class ConversationMessageState {
    var id: String = ""
    var role: String = "user"
    var content: String = ""
    var createdAt: Long = 0
    var timelineSequence: Long = 0
    var runId: String = ""
    var turnIndex: Int = -1
}

class ConversationToolState {
    var id: String = ""
    var name: String = ""
    var summary: String = ""
    var status: String = "completed"
    var detail: String = ""
    var changePath: String = ""
    var canRevert: Boolean = false
    var runId: String = ""
    var turnIndex: Int = -1
    var createdAt: Long = 0
    var updatedAt: Long = 0
    var timelineSequence: Long = 0
}

private data class TimelineSequenceCandidate(
    val createdAt: Long,
    val phase: Int,
    val index: Int,
    val assign: (Long) -> Unit,
)

class ConversationTaskState {
    var id: String = ""
    var name: String = ""
    var state: String = "not_started"
}
