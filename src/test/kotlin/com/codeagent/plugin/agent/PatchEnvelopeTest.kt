package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchEnvelopeTest {
    @Test
    fun `detects envelope format`() {
        assertTrue(PatchEnvelope.isEnvelope("*** Begin Patch\n*** End Patch"))
        assertTrue(!PatchEnvelope.isEnvelope("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b"))
    }

    @Test
    fun `parses add update and delete sections`() {
        val sections = PatchEnvelope.parse(
            """
            *** Begin Patch
            *** Add File: docs/new.txt
            +hello
            +world
            *** Update File: src/app.txt
            @@ header
             context
            -old line
            +new line
            *** Delete File: obsolete.txt
            *** End Patch
            """.trimIndent(),
        )
        assertEquals(3, sections.size)
        val add = sections[0] as PatchEnvelopeSection.AddFile
        assertEquals("docs/new.txt", add.path)
        assertEquals("hello\nworld", add.content)
        val update = sections[1] as PatchEnvelopeSection.UpdateFile
        assertEquals("src/app.txt", update.path)
        assertEquals(null, update.movePath)
        assertEquals(1, update.chunks.size)
        val delete = sections[2] as PatchEnvelopeSection.DeleteFile
        assertEquals("obsolete.txt", delete.path)
    }

    @Test
    fun `parses move to marker`() {
        val sections = PatchEnvelope.parse(
            """
            *** Begin Patch
            *** Update File: src/old-name.txt
            *** Move to: src/new-name.txt
             keep
            -a
            +b
            *** End Patch
            """.trimIndent(),
        )
        val update = sections.single() as PatchEnvelopeSection.UpdateFile
        assertEquals("src/new-name.txt", update.movePath)
    }

    @Test
    fun `applies chunks by context match`() {
        val original = "alpha\nbeta\ngamma\ndelta"
        val sections = PatchEnvelope.parse(
            """
            *** Begin Patch
            *** Update File: f.txt
            @@
             beta
            -gamma
            +GAMMA
            *** End Patch
            """.trimIndent(),
        )
        val update = sections.single() as PatchEnvelopeSection.UpdateFile
        assertEquals("alpha\nbeta\nGAMMA\ndelta", PatchEnvelope.applyChunks("f.txt", original, update.chunks))
    }

    @Test
    fun `applies multiple chunks in order`() {
        val original = (1..10).joinToString("\n") { "line $it" }
        val chunks = listOf(
            listOf(" line 2", "-line 3", "+edited 3"),
            listOf(" line 8", "+inserted 8.5"),
        )
        val result = PatchEnvelope.applyChunks("f.txt", original, chunks)
        assertEquals(
            listOf(
                "line 1", "line 2", "edited 3", "line 4", "line 5",
                "line 6", "line 7", "line 8", "inserted 8.5", "line 9", "line 10",
            ).joinToString("\n"),
            result,
        )
    }

    @Test
    fun `fails when context is missing`() {
        assertFailsWith<IllegalArgumentException> {
            PatchEnvelope.applyChunks("f.txt", "a\nb", listOf(listOf("-not there", "+x")))
        }
    }

    @Test
    fun `rejects envelope without terminator`() {
        assertFailsWith<IllegalArgumentException> {
            PatchEnvelope.parse("*** Begin Patch\n*** Add File: x\n+1")
        }
    }
}
