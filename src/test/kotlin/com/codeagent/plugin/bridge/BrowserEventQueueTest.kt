package com.codeagent.plugin.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserEventQueueTest {
    @Test
    fun `keeps one ordered event in flight until acknowledged`() {
        val queue = BrowserEventQueue()
        queue.enqueue(BrowserEvent(1, "first"))
        queue.enqueue(BrowserEvent(2, "second"))

        assertEquals(1, queue.nextForDispatch()?.sequence)
        assertNull(queue.nextForDispatch())
        assertFalse(queue.acknowledge(2))
        assertTrue(queue.acknowledge(1))
        assertEquals(2, queue.nextForDispatch()?.sequence)
    }

    @Test
    fun `retries only the current event and resets cleanly`() {
        val queue = BrowserEventQueue()
        queue.enqueue(BrowserEvent(7, "event"))
        queue.nextForDispatch()

        assertNull(queue.retry(6))
        assertEquals(1, queue.retry(7)?.attempts)
        assertTrue(queue.isCurrent(7))

        queue.reset()
        assertFalse(queue.isCurrent(7))
        assertFalse(queue.hasDispatchableEvent())
        assertNull(queue.nextForDispatch())
    }
}
