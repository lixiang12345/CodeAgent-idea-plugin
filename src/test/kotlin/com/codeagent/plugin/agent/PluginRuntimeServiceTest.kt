package com.codeagent.plugin.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginRuntimeServiceTest {
    @Test
    fun `installs cached manifests and activates granted command contributions`() {
        val source = "https://plugins.example.test/review.json"
        val store = MemoryPluginStore()
        val fetcher = MutablePluginFetcher(source, manifest(version = "1.0.0"))
        val runtime = PluginRuntime(fetcher, store)
        runtime.reconcile(listOf(configuration(source, capabilities = listOf("commands"))))

        assertEquals("available", runtime.snapshot().items.single().state)

        val installed = runtime.install("review-pack")

        assertEquals("ready", installed.items.single().state)
        assertEquals("1.0.0", installed.items.single().installedVersion)
        assertEquals(listOf("review-pack.review"), installed.commands.map(PluginCommandDefinition::id))
        assertTrue(store.read("review-pack") != null)

        val restored = PluginRuntime(fetcher, store)
        restored.reconcile(listOf(configuration(source, capabilities = listOf("commands"))))
        assertEquals("ready", restored.snapshot().items.single().state)
        assertEquals("review-pack.review", restored.snapshot().commands.single().id)
    }

    @Test
    fun `keeps ungranted plugin contributions inactive`() {
        val source = "https://plugins.example.test/review.json"
        val runtime = PluginRuntime(
            MutablePluginFetcher(source, manifest(version = "1.0.0")),
            MemoryPluginStore(),
        )
        runtime.reconcile(listOf(configuration(source, capabilities = emptyList())))

        val installed = runtime.install("review-pack")

        assertEquals("ready", installed.items.single().state)
        assertTrue(installed.commands.isEmpty())
        assertEquals(listOf("commands"), installed.items.single().declaredCapabilities)
    }

    @Test
    fun `detects an available update without replacing the installed manifest`() {
        val source = "https://plugins.example.test/review.json"
        val fetcher = MutablePluginFetcher(source, manifest(version = "1.0.0"))
        val runtime = PluginRuntime(fetcher, MemoryPluginStore())
        runtime.reconcile(listOf(configuration(source, capabilities = listOf("commands"))))
        runtime.install("review-pack")
        fetcher.bytes = manifest(version = "1.1.0")

        val checked = runtime.test("review-pack")

        assertEquals("update-available", checked.items.single().state)
        assertEquals("1.0.0", checked.items.single().installedVersion)
        assertEquals("1.1.0", checked.items.single().latestVersion)
    }

    @Test
    fun `rejects mismatched identities and integrity pins`() {
        val source = "https://plugins.example.test/review.json"
        val mismatched = manifest(version = "1.0.0", id = "other")
        val identityRuntime = PluginRuntime(
            MutablePluginFetcher(source, mismatched),
            MemoryPluginStore(),
        )
        identityRuntime.reconcile(listOf(configuration(source, capabilities = listOf("commands"))))

        assertFailsWith<IllegalArgumentException> {
            identityRuntime.install("review-pack")
        }
        assertEquals("error", identityRuntime.snapshot().items.single().state)

        val integrityRuntime = PluginRuntime(
            MutablePluginFetcher(source, manifest(version = "1.0.0")),
            MemoryPluginStore(),
        )
        integrityRuntime.reconcile(
            listOf(
                configuration(
                    source,
                    capabilities = listOf("commands"),
                    integrity = "sha256:${"0".repeat(64)}",
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            integrityRuntime.install("review-pack")
        }
        assertTrue(integrityRuntime.snapshot().items.single().lastError.orEmpty().contains("integrity"))
    }

    @Test
    fun `clears a stale operation error when no cached manifest exists`() {
        val source = "https://plugins.example.test/review.json"
        val fetcher = MutablePluginFetcher(source, manifest(version = "1.0.0", id = "other"))
        val runtime = PluginRuntime(fetcher, MemoryPluginStore())
        val configuration = configuration(source, capabilities = listOf("commands"))
        runtime.reconcile(listOf(configuration))

        assertFailsWith<IllegalArgumentException> {
            runtime.install("review-pack")
        }
        assertEquals("error", runtime.snapshot().items.single().state)

        fetcher.bytes = manifest(version = "1.0.0")
        runtime.reconcile(listOf(configuration))

        assertEquals("available", runtime.snapshot().items.single().state)
        assertEquals(null, runtime.snapshot().items.single().lastError)
    }

    private fun configuration(
        source: String,
        capabilities: List<String>,
        integrity: String? = null,
    ) = RemoteConfiguration(
        id = "review-pack",
        kind = "plugins",
        value = buildJsonObject {
            put("name", "Review pack")
            put("description", "Shared review workflows")
            put("enabled", true)
            put("source", source)
            put("capabilities", buildJsonArray {
                capabilities.forEach { add(JsonPrimitive(it)) }
            })
            integrity?.let { put("integrity", it) }
        },
    )

    private fun manifest(
        version: String,
        id: String = "review-pack",
    ): ByteArray = """
        {
          "schemaVersion": 1,
          "id": "$id",
          "name": "Review pack",
          "version": "$version",
          "description": "Shared review workflows",
          "publisher": "CodeAgent",
          "homepage": "https://plugins.example.test/review-pack",
          "capabilities": ["commands"],
          "commands": [
            {
              "id": "review",
              "name": "Plugin review",
              "description": "Review the requested scope",
              "prompt": "Review {{arguments}} in {{project}}.",
              "argumentHint": "[scope]",
              "mode": "ask",
              "agentProfileId": "loop"
            }
          ]
        }
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private class MutablePluginFetcher(
        private val source: String,
        var bytes: ByteArray,
    ) : PluginManifestFetcher {
        override fun fetch(source: String): ByteArray {
            require(source == this.source)
            return bytes
        }
    }

    private class MemoryPluginStore : PluginManifestStore {
        private val values = mutableMapOf<String, StoredPluginManifest>()

        override fun read(pluginId: String): StoredPluginManifest? = values[pluginId]

        override fun write(pluginId: String, bytes: ByteArray): StoredPluginManifest =
            StoredPluginManifest(bytes.copyOf(), "2026-07-14T00:00:00Z").also {
                values[pluginId] = it
            }

        override fun delete(pluginId: String) {
            values.remove(pluginId)
        }
    }
}
