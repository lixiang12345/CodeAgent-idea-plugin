package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextEditPlanTest {
    private val source = "alpha\nbeta\ngamma\nbeta\ndelta"

    @Test
    fun `replaces a unique match`() {
        val result = TextEditPlan.apply("f.txt", source, listOf(TextEdit(oldText = "gamma", newText = "GAMMA")))
        assertEquals("alpha\nbeta\nGAMMA\nbeta\ndelta", result)
    }

    @Test
    fun `rejects an ambiguous match`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TextEditPlan.apply("f.txt", source, listOf(TextEdit(oldText = "beta", newText = "B")))
        }
        assertEquals(true, error.message?.contains("occurs 2 times"))
    }

    @Test
    fun `disambiguates by start line`() {
        val result = TextEditPlan.apply(
            "f.txt",
            source,
            listOf(TextEdit(oldText = "beta", newText = "BETA", oldTextStartLine = 4)),
        )
        assertEquals("alpha\nbeta\ngamma\nBETA\ndelta", result)
    }

    @Test
    fun `replace all rewrites every occurrence`() {
        val result = TextEditPlan.apply(
            "f.txt",
            source,
            listOf(TextEdit(oldText = "beta", newText = "B", replaceAll = true)),
        )
        assertEquals("alpha\nB\ngamma\nB\ndelta", result)
    }

    @Test
    fun `applies several non-overlapping edits in one pass`() {
        val result = TextEditPlan.apply(
            "f.txt",
            source,
            listOf(
                TextEdit(oldText = "alpha", newText = "ALPHA"),
                TextEdit(oldText = "delta", newText = "DELTA"),
                TextEdit(oldText = "gamma", newText = "GAMMA"),
            ),
        )
        assertEquals("ALPHA\nbeta\nGAMMA\nbeta\nDELTA", result)
    }

    @Test
    fun `rejects overlapping edits`() {
        assertFailsWith<IllegalArgumentException> {
            TextEditPlan.apply(
                "f.txt",
                source,
                listOf(
                    TextEdit(oldText = "beta\ngamma", newText = "x", oldTextStartLine = 2),
                    TextEdit(oldText = "gamma", newText = "y"),
                ),
            )
        }
    }

    @Test
    fun `inserts after a line`() {
        val result = TextEditPlan.apply(
            "f.txt",
            source,
            listOf(TextEdit(oldText = null, newText = "inserted", insertLine = 2)),
        )
        assertEquals("alpha\nbeta\ninserted\ngamma\nbeta\ndelta", result)
    }

    @Test
    fun `inserts at the top when insert line is zero`() {
        val result = TextEditPlan.apply(
            "f.txt",
            source,
            listOf(TextEdit(oldText = null, newText = "header", insertLine = 0)),
        )
        assertEquals("header\nalpha\nbeta\ngamma\nbeta\ndelta", result)
    }

    @Test
    fun `inserting past the last line appends`() {
        val result = TextEditPlan.apply(
            "f.txt",
            source,
            listOf(TextEdit(oldText = null, newText = "tail", insertLine = 99)),
        )
        assertEquals("alpha\nbeta\ngamma\nbeta\ndelta\ntail\n", result)
    }

    @Test
    fun `combines a replacement and an insertion`() {
        val result = TextEditPlan.apply(
            "f.txt",
            source,
            listOf(
                TextEdit(oldText = "alpha", newText = "ALPHA"),
                TextEdit(oldText = null, newText = "mid", insertLine = 3),
            ),
        )
        assertEquals("ALPHA\nbeta\ngamma\nmid\nbeta\ndelta", result)
    }

    @Test
    fun `fails when the anchor line does not contain the text`() {
        assertFailsWith<IllegalArgumentException> {
            TextEditPlan.apply(
                "f.txt",
                source,
                listOf(TextEdit(oldText = "beta", newText = "B", oldTextStartLine = 3)),
            )
        }
    }
}
