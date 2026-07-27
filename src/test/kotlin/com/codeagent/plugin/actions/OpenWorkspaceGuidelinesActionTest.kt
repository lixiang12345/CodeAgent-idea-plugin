package com.codeagent.plugin.actions

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenWorkspaceGuidelinesActionTest {
    @Test
    fun `creates the runtime workspace guidelines path without adding prompt content`() {
        val project = Files.createTempDirectory("codeagent-open-guidelines")
        try {
            val file = ensureWorkspaceGuidelinesFile(project)

            assertEquals(project.resolve(".codeagent/guidelines.md"), file)
            assertTrue(Files.isRegularFile(file))
            assertEquals("", file.readText())
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preserves existing workspace guidelines`() {
        val project = Files.createTempDirectory("codeagent-existing-guidelines")
        try {
            val expected = project.resolve(".codeagent/guidelines.md")
            Files.createDirectories(expected.parent)
            expected.writeText("# Existing guidance\n\nKeep this content.")

            val file = ensureWorkspaceGuidelinesFile(project)

            assertEquals(expected, file)
            assertEquals("# Existing guidance\n\nKeep this content.", file.readText())
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
