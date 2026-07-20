package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InlineCompletionCacheTest {
    @Test
    fun `expires cached completions`() {
        var now = 1_000L
        val cache = InlineCompletionCache(maxEntries = 2, ttlMillis = 100) { now }
        cache.put("key", "completion")
        assertEquals("completion", cache.get("key"))
        now += 101
        assertNull(cache.get("key"))
    }

    @Test
    fun `evicts least recently used completion`() {
        val cache = InlineCompletionCache(maxEntries = 2, ttlMillis = 1_000)
        cache.put("one", "1")
        cache.put("two", "2")
        assertEquals("1", cache.get("one"))
        cache.put("three", "3")
        assertNull(cache.get("two"))
    }

    @Test
    fun `cache key includes editor and backend context`() {
        val first = InlineCompletionCache.key("Main.kt", "Kotlin", "fun ", "{}", "http://localhost:8788")
        val second = InlineCompletionCache.key("Main.kt", "Kotlin", "class ", "{}", "http://localhost:8788")
        assertNotEquals(first, second)
        assertEquals(64, first.length)
    }
}
