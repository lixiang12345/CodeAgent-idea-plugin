package com.codeagent.plugin.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeRuntimeLocatorTest {
    @Test
    fun `parses node version output`() {
        assertEquals(NodeVersion(22, 22, 3), NodeRuntimeLocator.parseVersion("v22.22.3\n"))
    }

    @Test
    fun `rejects unrelated version text`() {
        assertNull(NodeRuntimeLocator.parseVersion("not-node"))
    }

    @Test
    fun `compares semantic versions numerically`() {
        assertTrue(NodeVersion(22, 10, 0) > NodeVersion(22, 5, 9))
    }
}
