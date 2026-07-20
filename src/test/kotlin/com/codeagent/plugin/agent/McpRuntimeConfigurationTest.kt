package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class McpRuntimeConfigurationTest {
    @Test
    fun `extracts OAuth configuration without product credentials`() {
        val remote = RemoteConfiguration(
            id = "remote-oauth",
            kind = "mcp",
            value = buildJsonObject {
                put("authMode", "oauth")
                put("authorizationEndpoint", "https://identity.example.test/authorize")
                put("tokenEndpoint", "https://identity.example.test/token")
                put("clientId", "codeagent-desktop")
                putJsonArray("scopes") {
                    add(kotlinx.serialization.json.JsonPrimitive("tools.read"))
                    add(kotlinx.serialization.json.JsonPrimitive("tools.execute"))
                }
                put("audience", "https://mcp.example.test")
            },
        )

        val oauth = requireNotNull(mcpOAuthConfiguration(remote))
        assertEquals("remote-oauth", oauth.id)
        assertEquals(listOf("tools.read", "tools.execute"), oauth.scopes)
        assertEquals("https://mcp.example.test", oauth.audience)
        assertNull(mcpOAuthConfiguration(remote.copy(value = buildJsonObject { put("authMode", "none") })))
    }

    @Test
    fun `requires complete OAuth metadata`() {
        val remote = RemoteConfiguration(
            id = "incomplete",
            kind = "mcp",
            value = buildJsonObject { put("authMode", "oauth") },
        )
        assertFailsWith<IllegalArgumentException> { mcpOAuthConfiguration(remote) }
    }
}
