package com.codeagent.plugin.context

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ManagedNodeRuntimeInstallerTest {
    @Test
    fun `selects the exact platform and architecture artifact`() {
        val manifest = RuntimeManifest(
            runtimes = listOf(
                RuntimeArtifact("linux", "x64", "22.5.0", "https://downloads.example/linux", "a".repeat(64), "bin/node"),
                RuntimeArtifact("darwin", "arm64", "22.5.0", "https://downloads.example/macos", "b".repeat(64), "bin/node"),
            ),
        )

        assertEquals("darwin", ManagedNodeRuntimeInstaller.selectRuntime(manifest, "darwin", "arm64")?.platform)
        assertEquals(null, ManagedNodeRuntimeInstaller.selectRuntime(manifest, "win32", "x64"))
    }

    @Test
    fun `computes SHA-256 checksums for downloaded artifacts`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ManagedNodeRuntimeInstaller.sha256("hello".toByteArray()),
        )
    }

    @Test
    fun `rejects unsupported archive formats before downloading`() {
        assertFailsWith<IllegalArgumentException> {
            ManagedNodeRuntimeInstaller.requireSupportedArchive("tar.gz")
        }
    }

    @Test
    fun `extracts zip entries without allowing path traversal`() {
        val root = Files.createTempDirectory("codeagent-runtime-test-")
        try {
            val archive = zipOf("bin/node" to "node-binary")
            ManagedNodeRuntimeInstaller.extractZip(archive, root)
            assertEquals("node-binary", Files.readString(root.resolve("bin/node")))

            val escape = root.parent.resolve("escape.txt")
            Files.deleteIfExists(escape)
            assertFailsWith<IllegalArgumentException> {
                ManagedNodeRuntimeInstaller.extractZip(zipOf("../escape.txt" to "blocked"), root)
            }
            assertFalse(Files.exists(escape))
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
