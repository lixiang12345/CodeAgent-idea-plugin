package com.codeagent.plugin.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationStoreTest {
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
}
