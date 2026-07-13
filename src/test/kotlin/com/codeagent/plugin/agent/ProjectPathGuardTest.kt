package com.codeagent.plugin.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectPathGuardTest {
    @Test
    fun `allows files inside project and rejects traversal`() {
        val root = Files.createTempDirectory("codeagent-guard")
        val file = root.resolve("src/App.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "class App")
        val guard = ProjectPathGuard(root)

        assertEquals(file.toRealPath(), guard.existingFile("src/App.kt"))
        assertFailsWith<IllegalArgumentException> { guard.pathForWrite("../outside.txt") }
    }
}
