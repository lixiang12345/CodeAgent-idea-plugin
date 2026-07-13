package com.codeagent.plugin.bridge

import kotlinx.serialization.json.Json
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
}
