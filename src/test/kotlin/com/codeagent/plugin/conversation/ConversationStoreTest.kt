package com.codeagent.plugin.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationStoreTest {
    @Test
    fun `uses caller-provided message identity for optimistic bridge updates`() {
        val store = ConversationStore()
        val message = store.addMessage(
            role = "user",
            content = "Inspect the terminal failure",
            messageId = "client-message-1",
        )

        assertEquals("client-message-1", message.id)
        assertEquals("client-message-1", store.active().messages.single().id)
        assertFailsWith<IllegalArgumentException> {
            store.addMessage("user", "Duplicate", messageId = "client-message-1")
        }
    }

    @Test
    fun `appends and finalizes a streaming message`() {
        val store = ConversationStore()
        val message = store.addMessage("assistant", "")

        store.appendMessage(message.id, "Hello")
        store.appendMessage(message.id, " world")
        assertEquals("Hello world", store.active().messages.single().content)

        store.replaceMessage(message.id, "Hello, world.")
        assertEquals("Hello, world.", store.active().messages.single().content)
    }

    @Test
    fun `rewinds messages tools summary and read cursor from a user request`() {
        val store = ConversationStore()
        val firstUser = store.addMessage("user", "First request", messageId = "user-1")
        val firstReply = store.addMessage("assistant", "First reply")
        val secondUser = store.addMessage("user", "Second request", messageId = "user-2")
        store.upsertTool(
            ConversationTool(
                id = "later-tool",
                name = "read_file",
                summary = "Read later file",
                status = "completed",
                canRevert = false,
                createdAt = 1_000,
                updatedAt = 1_100,
            ),
        )
        store.addMessage("assistant", "Second reply")
        store.setSummary(store.active().id, "Summary that includes both requests")
        store.markReadIfPresent(store.active().id, store.active().messages.last().timelineSequence)

        val rewound = store.rewindFromUserMessage(secondUser.id)

        assertEquals(secondUser, rewound)
        assertEquals(listOf(firstUser.id, firstReply.id), store.active().messages.map { it.id })
        assertTrue(store.active().tools.isEmpty())
        assertEquals(null, store.active().summary)
        assertEquals(firstReply.timelineSequence, store.state.threads.single().lastReadTimelineSequence)
    }

    @Test
    fun `refuses rewind atomically when later file changes remain revertible`() {
        val store = ConversationStore()
        val target = store.addMessage("user", "Change auth", messageId = "user-change")
        store.upsertTool(
            ConversationTool(
                id = "change-tool",
                name = "replace_text",
                summary = "Changed Auth.kt",
                status = "completed",
                changePath = "src/Auth.kt",
                canRevert = true,
                createdAt = 1_000,
                updatedAt = 1_100,
            ),
        )
        store.addMessage("assistant", "Change complete")
        store.setSummary(store.active().id, "Auth was changed")
        val before = store.active()

        val failure = assertFailsWith<IllegalArgumentException> {
            store.rewindFromUserMessage(target.id)
        }

        assertTrue(failure.message.orEmpty().contains("Keep or discard"))
        assertEquals(before, store.active())
    }

    @Test
    fun `creates titles and switches persisted threads`() {
        val store = ConversationStore()
        val first = store.active()
        store.addMessage("user", "Implement project login\nwith tests")
        assertEquals("Implement project login", store.active().title)

        val second = store.newThread("ask")
        store.addMessage("user", "Explain the data model")
        assertEquals("ask", store.active().mode)

        store.select(first.id)
        assertEquals("Implement project login", store.active().title)
        assertTrue(store.threads().any { it.id == second.id })
    }

    @Test
    fun `continues task list in a new thread without copying conversation messages`() {
        val source = ConversationStore()
        source.setSelectedModel("claude-sonnet-5")
        source.setSelectedAgentProfile("loop")
        source.setSelectedSkills(listOf("skill-a"))
        source.setSelectedRules(listOf("rule-a"))
        source.addMessage("user", "Plan the migration")
        val tasks = source.addTasks(listOf("Update schema", "Run migration tests"))
        source.updateTask(tasks.first().id, "completed", null)

        val fork = source.continueTasksInNewThread()

        assertEquals(source.active().mode, fork.mode)
        assertEquals("claude-sonnet-5", fork.selectedModelId)
        assertEquals("loop", fork.selectedAgentProfileId)
        assertEquals(listOf("skill-a"), fork.selectedSkillIds)
        assertEquals(listOf("rule-a"), fork.selectedRuleIds)
        assertTrue(fork.messages.isEmpty())
        assertTrue(fork.tools.isEmpty())
        assertEquals(listOf("completed", "not_started"), fork.tasks.map { it.state })
        assertTrue(fork.tasks.zip(tasks).all { (copy, original) -> copy.id != original.id })
        assertEquals(fork.id, source.active().id)
    }

    @Test
    fun `tracks and persists unread assistant messages by timeline sequence`() {
        val store = ConversationStore()
        val threadId = store.active().id
        store.addMessage("user", "Inspect the conversation")
        val firstReply = store.addMessage("assistant", "Initial result")

        assertEquals(1, store.unreadCount(threadId))
        assertTrue(store.markReadIfPresent(threadId, firstReply.timelineSequence))
        assertEquals(0, store.unreadCount(threadId))
        assertTrue(!store.markReadIfPresent(threadId, firstReply.timelineSequence))

        store.addMessage("assistant", "Follow-up result")
        assertEquals(1, store.unreadCount(threadId))

        val restored = ConversationStore()
        restored.loadState(store.state)
        assertEquals(1, restored.unreadCount(threadId))
        assertTrue(restored.state.threads.single().lastReadTimelineSequence > 0)
    }

    @Test
    fun `preserves the local read cursor when newer cloud history is merged`() {
        val store = ConversationStore()
        val threadId = store.active().id
        store.addMessage("user", "Inspect cloud recovery")
        val firstReply = store.addMessage("assistant", "Initial result")
        store.markReadIfPresent(threadId, firstReply.timelineSequence)
        val local = store.active()
        val remote = local.copy(
            updatedAt = local.updatedAt + 1,
            active = false,
            messages = local.messages + ConversationMessage(
                id = "cloud-follow-up",
                role = "assistant",
                content = "New result from another device",
                createdAt = local.updatedAt + 1,
                timelineSequence = firstReply.timelineSequence + 1,
            ),
        )

        store.mergeCloudConversation(remote)

        assertEquals(1, store.unreadCount(threadId))
        assertEquals(firstReply.timelineSequence, store.state.threads.single().lastReadTimelineSequence)
    }

    @Test
    fun `persists bounded skill selections per thread`() {
        val store = ConversationStore()
        store.setSelectedSkills(listOf("skill-a", "skill-b", "skill-a"))
        assertEquals(listOf("skill-a", "skill-b"), store.active().selectedSkillIds)

        store.newThread()
        assertTrue(store.active().selectedSkillIds.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            store.setSelectedSkills((1..9).map { "skill-$it" })
        }
    }

    @Test
    fun `persists model selection per thread`() {
        val store = ConversationStore()
        val first = store.active()
        store.setSelectedModel("claude-sonnet-5")
        assertEquals("claude-sonnet-5", store.active().selectedModelId)
        store.setSelectedModel(null)
        assertEquals(null, store.active().selectedModelId)
        store.setSelectedModel("claude-sonnet-5")

        val second = store.newThread()
        assertEquals(null, second.selectedModelId)
        store.setSelectedModel("grok-4.5")

        store.select(first.id)
        assertEquals("claude-sonnet-5", store.active().selectedModelId)
        store.select(second.id)
        assertEquals("grok-4.5", store.active().selectedModelId)
    }

    @Test
    fun `persists Agent profile selection per thread`() {
        val store = ConversationStore()
        val first = store.active()
        store.setSelectedAgentProfile("context")
        assertEquals("context", store.active().selectedAgentProfileId)

        val second = store.newThread()
        assertEquals("general", second.selectedAgentProfileId)
        store.setSelectedAgentProfile("review-agent")

        store.select(first.id)
        assertEquals("context", store.active().selectedAgentProfileId)
        store.select(second.id)
        assertEquals("review-agent", store.active().selectedAgentProfileId)
        assertFailsWith<IllegalArgumentException> { store.setSelectedAgentProfile("not valid") }
    }

    @Test
    fun `persists and reorders tasks per thread`() {
        val store = ConversationStore()
        val tasks = store.addTasks(listOf("Inspect auth flow", "Add regression tests"))

        store.updateTask(tasks.first().id, "completed", null)
        store.reorderTasks(tasks.reversed().map { it.id })

        assertEquals(listOf("Add regression tests", "Inspect auth flow"), store.active().tasks.map { it.name })
        assertEquals("completed", store.active().tasks.last().state)

        store.newThread()
        assertTrue(store.active().tasks.isEmpty())
    }

    @Test
    fun `normalizes legacy persisted task lists to cloud limits`() {
        val store = ConversationStore()
        val state = ConversationStoreState().apply {
            activeThreadId = "legacy-thread"
            threads = mutableListOf(
                ConversationThreadState().apply {
                    id = "legacy-thread"
                    title = "Legacy task history"
                    updatedAt = 1_000
                    tasks = (1..102).mapTo(mutableListOf()) { index ->
                        ConversationTaskState().apply {
                            id = if (index == 102) "task-1" else "task-$index"
                            name = if (index == 101) "" else "Task $index"
                            state = if (index == 100) "unknown" else "not_started"
                        }
                    }
                },
            )
        }
        store.loadState(state)

        val tasks = store.active().tasks
        assertEquals(99, tasks.size)
        assertEquals((1..99).map { "task-$it" }, tasks.map { it.id })
    }

    @Test
    fun `persists tool cards and message turn identity per thread`() {
        val store = ConversationStore()
        val first = store.active()
        val user = store.addMessage("user", "Inspect auth", runId = "run-1")
        val assistant = store.addMessage("assistant", "Done", runId = "run-1", turnIndex = 2)
        val tool = store.upsertTool(
            ConversationTool(
                id = "tool-1",
                name = "read_file",
                summary = "Read auth service",
                status = "completed",
                detail = "src/Auth.kt",
                runId = "run-1",
                turnIndex = 1,
                createdAt = 1_000,
                updatedAt = 1_250,
            ),
        )

        assertEquals("run-1", store.active().messages.last().runId)
        assertEquals(2, store.active().messages.last().turnIndex)
        assertTrue(user.timelineSequence < assistant.timelineSequence)
        assertTrue(assistant.timelineSequence < tool.timelineSequence)
        assertEquals(listOf("tool-1"), store.active().tools.map { it.id })
        assertEquals(1_250, store.active().tools.single().updatedAt)

        store.newThread()
        assertTrue(store.active().tools.isEmpty())
        store.select(first.id)
        assertEquals("Read auth service", store.active().tools.single().summary)
    }

    @Test
    fun `migrates legacy message and tool timestamps into one timeline`() {
        val state = ConversationStoreState().apply {
            val thread = ConversationThreadState().apply {
                id = "legacy"
                title = "Legacy"
                updatedAt = 2_000
                messages = mutableListOf(
                    ConversationMessageState().apply {
                        id = "user"
                        role = "user"
                        content = "Inspect"
                        createdAt = 1_000
                    },
                    ConversationMessageState().apply {
                        id = "assistant-1"
                        role = "assistant"
                        content = "I will inspect it"
                        createdAt = 1_100
                        runId = "run-1"
                        turnIndex = 0
                    },
                    ConversationMessageState().apply {
                        id = "assistant-2"
                        role = "assistant"
                        content = "Done"
                        createdAt = 1_300
                        runId = "run-1"
                        turnIndex = 1
                    },
                )
                tools = mutableListOf(
                    ConversationToolState().apply {
                        id = "tool"
                        name = "read_file"
                        summary = "Read file"
                        status = "completed"
                        runId = "run-1"
                        turnIndex = 0
                        createdAt = 1_200
                        updatedAt = 1_250
                    },
                )
            }
            activeThreadId = thread.id
            threads = mutableListOf(thread)
        }
        val store = ConversationStore()
        store.loadState(state)

        val timeline = buildList {
            store.active().messages.mapTo(this) { it.timelineSequence to it.id }
            store.active().tools.mapTo(this) { it.timelineSequence to it.id }
        }.sortedBy { it.first }.map { it.second }
        assertEquals(listOf("user", "assistant-1", "tool", "assistant-2"), timeline)
        assertEquals(0, store.unreadCount("legacy"))
    }

    @Test
    fun `interrupts pending tools without dropping completed history`() {
        val store = ConversationStore()
        val thread = store.active()
        store.upsertTool(
            ConversationTool(
                id = "completed-tool",
                name = "view",
                summary = "Listed files",
                status = "completed",
                createdAt = 1_000,
                updatedAt = 1_100,
            ),
        )
        store.upsertTool(
            ConversationTool(
                id = "approval-tool",
                name = "run_terminal",
                summary = "Approval required",
                status = "approval",
                createdAt = 1_200,
                updatedAt = 1_200,
            ),
        )
        store.upsertTool(
            ConversationTool(
                id = "running-tool",
                name = "read_file",
                summary = "Running",
                status = "running",
                createdAt = 1_300,
                updatedAt = 1_300,
            ),
        )

        val interrupted = store.interruptTools("thread switched")

        assertEquals(setOf("approval-tool", "running-tool"), interrupted.mapTo(mutableSetOf()) { it.id })
        assertEquals("completed", store.active().tools.first { it.id == "completed-tool" }.status)
        assertEquals("rejected", store.active().tools.first { it.id == "approval-tool" }.status)
        assertTrue(store.active().tools.first { it.id == "approval-tool" }.summary.contains("thread switched"))
        assertEquals("failed", store.active().tools.first { it.id == "running-tool" }.status)

        store.newThread()
        store.select(thread.id)
        assertEquals(3, store.active().tools.size)
        assertEquals("rejected", store.active().tools.first { it.id == "approval-tool" }.status)
    }

    @Test
    fun `persists manual rule selection per thread`() {
        val store = ConversationStore()
        store.setSelectedRules(listOf(".codeagent/rules/review.md"))
        assertEquals(listOf(".codeagent/rules/review.md"), store.active().selectedRuleIds)

        store.newThread()
        assertTrue(store.active().selectedRuleIds.isEmpty())
    }

    @Test
    fun `imports deletes and clears persistent tasks`() {
        val store = ConversationStore()
        val imported = store.importTasks(
            listOf(
                "Inspect implementation" to "not_started",
                "Run regression tests" to "completed",
            ),
        )

        assertEquals(listOf("not_started", "completed"), store.active().tasks.map { it.state })
        store.deleteTask(imported.first().id)
        assertEquals(listOf("Run regression tests"), store.active().tasks.map { it.name })

        store.clearTasks()
        assertTrue(store.active().tasks.isEmpty())
    }

    @Test
    fun `pins imports and deletes threads`() {
        val store = ConversationStore()
        val original = store.active()
        val imported = store.importThread(
            title = "Imported review",
            mode = "ask",
            messages = listOf("user" to "Review this change", "assistant" to "The change is focused."),
        )

        store.togglePinned(original.id)
        assertEquals(original.id, store.threads().first().id)
        assertTrue(store.threads().first().pinned)
        store.setPinnedIfPresent(original.id, true)
        assertTrue(store.threads().first().pinned)
        assertEquals("ask", imported.mode)
        assertEquals(2, imported.messages.size)

        store.deleteThread(imported.id)
        assertEquals(original.id, store.active().id)
    }

    @Test
    fun `ignores repeated deletion of an already removed thread`() {
        val store = ConversationStore()
        val deleted = store.active()

        assertNotNull(store.deleteThreadIfPresent(deleted.id))
        assertEquals(null, store.deleteThreadIfPresent(deleted.id))
        assertEquals(null, store.togglePinnedIfPresent(deleted.id))
        assertEquals(listOf(deleted.id), store.pendingCloudDeletions())
    }

    @Test
    fun `restores cloud history and removes the pristine local thread`() {
        val store = ConversationStore()
        val cloud = cloudConversation(
            id = "cloud-thread",
            updatedAt = 2_000,
            messages = listOf(ConversationMessage("message-1", "user", "Cloud message", 1_900)),
            tools = listOf(
                ConversationTool(
                    id = "tool-cloud",
                    name = "read_file",
                    summary = "Read cloud file",
                    status = "completed",
                    runId = "run-cloud",
                    turnIndex = 1,
                    createdAt = 1_950,
                ),
            ),
        )

        val result = store.mergeCloudSnapshot(listOf(cloud))

        assertTrue(result.changed)
        assertTrue(result.upload.isEmpty())
        assertEquals(listOf("cloud-thread"), store.threads().map { it.id })
        assertEquals("cloud-thread", store.active().id)
        assertEquals("Cloud message", store.active().messages.single().content)
        assertEquals("tool-cloud", store.active().tools.single().id)
        assertEquals("context", store.active().selectedAgentProfileId)
    }

    @Test
    fun `keeps newer local history queued for upload`() {
        val store = ConversationStore()
        store.addMessage("user", "Local message")
        val local = store.active()
        val staleCloud = cloudConversation(
            id = local.id,
            updatedAt = local.updatedAt - 1,
            messages = listOf(ConversationMessage("remote-message", "user", "Stale cloud message", local.updatedAt - 2)),
        )

        val result = store.mergeCloudSnapshot(listOf(staleCloud))

        assertEquals("Local message", store.active().messages.single().content)
        assertEquals(listOf(local.id), result.upload.map { it.id })
    }

    @Test
    fun `stores generated summaries locally and restores cloud summaries`() {
        val store = ConversationStore()
        val localId = store.active().id

        store.setSummary(localId, "Decisions, changed files, and remaining work.")

        assertEquals("Decisions, changed files, and remaining work.", store.active().summary)

        val cloud = cloudConversation(
            id = "cloud-summary",
            updatedAt = store.active().updatedAt + 1,
            messages = listOf(ConversationMessage("message-1", "assistant", "Completed", 1_000)),
            summary = "Cloud history summary",
        )
        store.mergeCloudSnapshot(listOf(cloud))

        assertEquals("Cloud history summary", store.threads().first { it.id == cloud.id }.summary)
    }

    @Test
    fun `clears one persisted summary without deleting its transcript`() {
        val store = ConversationStore()
        val threadId = store.active().id
        store.addMessage("user", "Inspect memory behavior")
        store.addMessage("assistant", "Memory behavior inspected")
        store.setSummary(threadId, "The memory behavior was inspected.")

        val cleared = store.clearSummary(threadId)

        assertEquals(null, cleared.summary)
        assertEquals(2, cleared.messages.size)
        assertEquals(listOf("Inspect memory behavior", "Memory behavior inspected"), cleared.messages.map { it.content })
    }

    @Test
    fun `keeps deleted cloud threads tombstoned until remote deletion succeeds`() {
        val store = ConversationStore()
        store.addMessage("user", "Delete this synced thread")
        val deleted = store.active()

        store.deleteThread(deleted.id)
        val merge = store.mergeCloudSnapshot(listOf(deleted.copy(active = false)))

        assertTrue(merge.changed.not())
        assertTrue(store.threads().none { it.id == deleted.id })
        assertEquals(listOf(deleted.id), store.pendingCloudDeletions())

        store.acknowledgeCloudDeletion(deleted.id)
        store.mergeCloudSnapshot(listOf(deleted.copy(active = false, updatedAt = deleted.updatedAt + 1)))

        assertTrue(store.threads().any { it.id == deleted.id })
        assertTrue(store.pendingCloudDeletions().isEmpty())
    }

    private fun cloudConversation(
        id: String,
        updatedAt: Long,
        messages: List<ConversationMessage>,
        summary: String? = null,
        tools: List<ConversationTool> = emptyList(),
    ) = ConversationSnapshot(
        id = id,
        title = "Cloud thread",
        updatedAt = updatedAt,
        mode = "agent",
        selectedAgentProfileId = "context",
        selectedModelId = "model-a",
        selectedSkillIds = emptyList(),
        selectedRuleIds = emptyList(),
        messages = messages,
        tasks = emptyList(),
        active = false,
        pinned = false,
        summary = summary,
        tools = tools,
    )
}
