package com.codeagent.plugin.bridge

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunGenerationTest {
    @Test
    fun `invalidates callbacks from an older run`() {
        val runs = RunGeneration()
        val first = runs.next()

        assertTrue(runs.isCurrent(first))
        runs.invalidate()
        assertFalse(runs.isCurrent(first))

        val second = runs.next()
        assertTrue(runs.isCurrent(second))
        assertFalse(runs.isCurrent(first))
    }
}
