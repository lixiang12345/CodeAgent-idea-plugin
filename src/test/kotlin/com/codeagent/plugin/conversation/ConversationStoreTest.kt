package com.codeagent.plugin.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConversationStoreTest {
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
    fun `persists tool cards and message turn identity per thread`() {
        val store = ConversationStore()
        val first = store.active()
        store.addMessage("user", "Inspect auth", runId = "run-1")
        store.addMessage("assistant", "Done", runId = "run-1", turnIndex = 2)
        store.upsertTool(
            ConversationTool(
                id = "tool-1",
                name = "read_file",
                summary = "Read auth service",
                status = "completed",
                detail = "src/Auth.kt",
                runId = "run-1",
                turnIndex = 1,
                createdAt = 1_000,
            ),
        )

        assertEquals("run-1", store.active().messages.last().runId)
        assertEquals(2, store.active().messages.last().turnIndex)
        assertEquals(listOf("tool-1"), store.active().tools.map { it.id })

        store.newThread()
        assertTrue(store.active().tools.isEmpty())
        store.select(first.id)
        assertEquals("Read auth service", store.active().tools.single().summary)
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
        assertEquals("ask", imported.mode)
        assertEquals(2, imported.messages.size)

        store.deleteThread(imported.id)
        assertEquals(original.id, store.active().id)
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
