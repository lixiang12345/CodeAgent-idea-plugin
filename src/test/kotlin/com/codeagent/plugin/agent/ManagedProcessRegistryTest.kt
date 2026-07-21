package com.codeagent.plugin.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ManagedProcessRegistryTest {
    @Test
    fun `captures bounded output and reports process completion`() {
        val root = Files.createTempDirectory("codeagent-process-")
        ManagedProcessRegistry(root, maxOutputChars = 32).use { registry ->
            val launched = registry.launch("printf '0123456789abcdefghijklmnopqrstuvwxyz\\n'", "output-test")
            val completed = registry.waitFor(launched.id, 5)
            val read = registry.read(launched.id, 0, 100)

            assertEquals("completed", completed.state)
            assertEquals(0, completed.exitCode)
            assertTrue(read.truncatedBeforeOffset)
            assertTrue(read.output.endsWith("abcdefghijklmnopqrstuvwxyz\n"))
            assertEquals(completed.outputEndOffset, read.nextOffset)
        }
        Files.deleteIfExists(root)
    }

    @Test
    fun `writes stdin and can terminate a running process`() {
        val root = Files.createTempDirectory("codeagent-process-")
        ManagedProcessRegistry(root).use { registry ->
            val interactive = registry.launch("IFS= read -r line; printf 'received:%s\\n' \"\$line\"; sleep 30", "interactive")
            registry.write(interactive.id, "hello", appendNewline = true)
            waitUntil { registry.read(interactive.id).output.contains("received:hello") }

            val read = registry.read(interactive.id)
            assertTrue(read.output.contains("received:hello"))
            assertEquals("running", read.process.state)

            val killed = registry.kill(interactive.id)
            assertEquals("killed", killed.state)
            assertFalse(registry.list().first { it.id == interactive.id }.state == "running")
        }
        Files.deleteIfExists(root)
    }

    @Test
    fun `terminates descendant processes without relabeling completed records`() {
        val root = Files.createTempDirectory("codeagent-process-")
        ManagedProcessRegistry(root).use { registry ->
            val launched = registry.launch("sleep 30 & child=\$!; printf 'child:%s\\n' \"\$child\"; wait \"\$child\"", "tree")
            waitUntil { registry.read(launched.id).output.contains("child:") }
            val childPid = registry.read(launched.id).output
                .lineSequence()
                .first { it.startsWith("child:") }
                .substringAfter(':')
                .trim()
                .toLong()
            assertTrue(ProcessHandle.of(childPid).orElse(null)?.isAlive == true)

            val killed = registry.kill(launched.id)

            assertEquals("killed", killed.state)
            val child = ProcessHandle.of(childPid).orElse(null)
            assertTrue(child == null || !child.isAlive)

            val completed = registry.launch("printf 'done\\n'", "completed")
            assertEquals("completed", registry.waitFor(completed.id, 5).state)
            val unchanged = registry.kill(completed.id)
            assertNotNull(unchanged.exitCode)
            assertEquals("completed", unchanged.state)
        }
        Files.deleteIfExists(root)
    }

    @Test
    fun `uses a project contained working directory and rejects traversal`() {
        val root = Files.createTempDirectory("codeagent-process-")
        val child = Files.createDirectory(root.resolve("child"))
        ManagedProcessRegistry(root).use { registry ->
            val launched = registry.launch("pwd", cwd = "child")
            registry.waitFor(launched.id, 5)
            val read = registry.read(launched.id)

            assertEquals(child.toRealPath().toString(), read.process.workingDirectory)
            assertTrue(read.output.contains(child.toRealPath().toString()))
            assertFailsWith<IllegalArgumentException> { registry.launch("pwd", cwd = "..") }
        }
        Files.deleteIfExists(child)
        Files.deleteIfExists(root)
    }

    @Test
    fun `wait returns early when a process is prompting for input`() {
        val root = Files.createTempDirectory("codeagent-process-")
        ManagedProcessRegistry(root, inputPromptIdleMillis = 50).use { registry ->
            val launched = registry.launch("printf 'Password:'; sleep 30", "prompt")
            val waiting = registry.waitFor(launched.id, 5)

            assertEquals("running", waiting.state)
            assertTrue(waiting.waitingForInput)
            registry.write(launched.id, "secret", appendNewline = true)
            assertFalse(registry.read(launched.id).process.waitingForInput)
            registry.kill(launched.id)
        }
        Files.deleteIfExists(root)
    }

    @Test
    fun `prompt detection excludes progress counters and urls`() {
        assertTrue(outputEndsWithPrompt("Password:"))
        assertTrue(outputEndsWithPrompt("Continue?"))
        assertFalse(outputEndsWithPrompt("Progress 80%:"))
        assertFalse(outputEndsWithPrompt("Items 2/4:"))
        assertFalse(outputEndsWithPrompt("https://example.com/path?"))
        assertFalse(outputEndsWithPrompt("Password:\n"))
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "Condition was not met before timeout")
    }
}
