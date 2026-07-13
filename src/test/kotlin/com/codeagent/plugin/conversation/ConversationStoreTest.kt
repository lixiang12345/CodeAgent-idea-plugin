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
}
