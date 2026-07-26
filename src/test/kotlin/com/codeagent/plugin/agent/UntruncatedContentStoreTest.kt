package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UntruncatedContentStoreTest {
    private val content = (1..20).joinToString("\n") { "line $it" }

    @Test
    fun `stores and reads a line range`() {
        val store = UntruncatedContentStore()
        val id = store.store("search_text", content)
        assertEquals("3: line 3\n4: line 4", store.viewRange(id, 3, 4))
    }

    @Test
    fun `clamps a range beyond the stored content`() {
        val store = UntruncatedContentStore()
        val id = store.store("search_text", content)
        assertEquals("20: line 20", store.viewRange(id, 20, 999))
    }

    @Test
    fun `reports an empty range past the end`() {
        val store = UntruncatedContentStore()
        val id = store.store("search_text", content)
        assertTrue(store.viewRange(id, 50, 60).contains("has 20 lines"))
    }

    @Test
    fun `search returns matches with context`() {
        val store = UntruncatedContentStore()
        val id = store.store("run_terminal", content)
        val result = store.search(id, "line 5", contextLines = 1)
        assertEquals("4- line 4\n5: line 5\n6- line 6", result)
    }

    @Test
    fun `search reports no matches truthfully`() {
        val store = UntruncatedContentStore()
        val id = store.store("run_terminal", content)
        assertTrue(store.search(id, "absent", 0).startsWith("No matches"))
    }

    @Test
    fun `rejects an unknown reference`() {
        val store = UntruncatedContentStore()
        assertFailsWith<IllegalArgumentException> { store.viewRange("missing-1", 1, 2) }
        assertNull(store.get("missing-1"))
    }

    @Test
    fun `evicts the oldest entries beyond the bound`() {
        val store = UntruncatedContentStore()
        val ids = (1..45).map { store.store("tool", "value $it") }
        assertEquals(40, store.ids().size)
        assertNull(store.get(ids.first()))
        assertEquals("value 45", store.get(ids.last()))
    }

    @Test
    fun `reference ids are unique and tool-scoped`() {
        val store = UntruncatedContentStore()
        val first = store.store("search_text", "a")
        val second = store.store("search_text", "b")
        assertTrue(first.startsWith("search_text-"))
        assertTrue(first != second)
    }
}
