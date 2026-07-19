package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolExecutionResultTest {
    @Test
    fun `tracks all file changes when multiple changes are present`() {
        val first = FileChange("src/one.kt", "old", "new")
        val second = FileChange("src/two.kt", "before", "after")
        val result = ToolExecutionResult(
            output = "patched",
            summary = "Patched 2 files",
            fileChange = first,
            fileChanges = listOf(first, second),
        )

        assertEquals(listOf(first, second), result.trackedFileChanges())
    }

    @Test
    fun `falls back to the legacy single file change`() {
        val change = FileChange("src/one.kt", "old", "new")
        val result = ToolExecutionResult(
            output = "patched",
            summary = "Patched 1 file",
            fileChange = change,
        )

        assertEquals(listOf(change), result.trackedFileChanges())
    }
}
