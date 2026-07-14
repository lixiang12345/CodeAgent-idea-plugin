package com.codeagent.plugin.bridge

import com.codeagent.plugin.settings.CodeAgentSettingsState
import com.codeagent.plugin.settings.DEFAULT_BACKEND_URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BridgeProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes a versioned command and ignores future fields`() {
        val command = json.decodeFromString<CommandEnvelope>(
            """{"version":1,"id":"request-1","type":"bootstrap","future":true}""",
        )

        assertEquals(BRIDGE_PROTOCOL_VERSION, command.version)
        assertEquals("bootstrap", command.type)
    }

    @Test
    fun `rejects malformed command`() {
        assertFailsWith<Exception> {
            json.decodeFromString<CommandEnvelope>("""{"version":1}""")
        }
    }

    @Test
    fun `encodes turn identity on streaming bridge payloads`() {
        val delta = json.encodeToString(MessageDeltaDto("message-1", "Done", 2))
        val tool = json.encodeToString(ToolRunDto("tool-1", "read_file", "Read", "completed", turnIndex = 2))

        assertEquals("""{"id":"message-1","delta":"Done","turnIndex":2}""", delta)
        assertEquals(true, tool.contains("\"turnIndex\":2"))
    }

    @Test
    fun `fresh install targets the local Docker backend`() {
        assertEquals("http://127.0.0.1:8788", DEFAULT_BACKEND_URL)
        assertEquals(DEFAULT_BACKEND_URL, CodeAgentSettingsState().backendUrl)
        assertEquals(DEFAULT_BACKEND_URL, SettingsSnapshotDto().backendUrl)
    }

    @Test
    fun `encodes typed product configurations in the application snapshot`() {
        val snapshot = ConfigurationSnapshotDto(
            state = "ready",
            label = "1 configurations",
            items = mapOf(
                "agents" to listOf(
                    ProductConfigurationDto(
                        id = "review-agent",
                        kind = "agents",
                        value = buildJsonObject {
                            put("name", "Review Agent")
                            put("systemPrompt", "Review twice")
                        },
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(snapshot)
        assertEquals(true, encoded.contains("\"review-agent\""))
        assertEquals(true, encoded.contains("\"systemPrompt\":\"Review twice\""))
    }

}
