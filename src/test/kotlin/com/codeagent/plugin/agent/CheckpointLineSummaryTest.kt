package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckpointLineSummaryTest {
    @Test
    fun `counts added lines for a new file`() {
        val summary = ChangeReviewService.lineChangeSummary(
            "src/New.kt",
            FileChange(path = "src/New.kt", before = null, after = "line one\nline two\nline three"),
        )
        assertEquals(3, summary.added)
        assertEquals(0, summary.removed)
    }

    @Test
    fun `counts removed lines for a deleted body`() {
        val summary = ChangeReviewService.lineChangeSummary(
            "src/Old.kt",
            FileChange(path = "src/Old.kt", before = "a\nb\nc", after = ""),
        )
        assertEquals(0, summary.added)
        assertEquals(3, summary.removed)
    }

    @Test
    fun `counts net added and removed lines for an edit`() {
        val summary = ChangeReviewService.lineChangeSummary(
            "src/Edit.kt",
            FileChange(
                path = "src/Edit.kt",
                before = "keep\nold one\nold two",
                after = "keep\nnew one\nnew two\nnew three",
            ),
        )
        // "keep" is unchanged; old one/old two removed; new one/two/three added.
        assertEquals(3, summary.added)
        assertEquals(2, summary.removed)
    }

    @Test
    fun `reports no changes when content is identical`() {
        val summary = ChangeReviewService.lineChangeSummary(
            "src/Same.kt",
            FileChange(path = "src/Same.kt", before = "x\ny", after = "x\ny"),
        )
        assertEquals(0, summary.added)
        assertEquals(0, summary.removed)
    }
}
