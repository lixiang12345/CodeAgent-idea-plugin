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
                put("url", "https://mcp.example.test/api/resources")
                putJsonArray("scopes") {
                    add(kotlinx.serialization.json.JsonPrimitive("tools.read"))
                    add(kotlinx.serialization.json.JsonPrimitive("tools.execute"))
                }
                put("audience", "https://mcp.example.test")
            },
        )

        val oauth = requireNotNull(mcpOAuthConfiguration(remote))
        assertEquals("remote-oauth", oauth.id)
        assertEquals("https://mcp.example.test/api/resources", oauth.resourceUrl)
        assertEquals(listOf("tools.read", "tools.execute"), oauth.scopes)
        assertEquals("https://mcp.example.test", oauth.audience)
        assertNull(mcpOAuthConfiguration(remote.copy(value = buildJsonObject { put("authMode", "none") })))
    }

    @Test
    fun `allows OAuth configuration with discovered endpoints`() {
        val remote = RemoteConfiguration(
            id = "discovered-oauth",
            kind = "mcp",
            value = buildJsonObject {
                put("authMode", "oauth")
                put("url", "https://identity.example.test")
            },
        )
        val oauth = requireNotNull(mcpOAuthConfiguration(remote))
        assertEquals("https://identity.example.test", oauth.resourceUrl)
        assertEquals("", oauth.clientId)
    }

    @Test
    fun `requires authMode oauth to extract OAuth configuration`() {
        val remote = RemoteConfiguration(
            id = "incomplete",
            kind = "mcp",
            value = buildJsonObject { put("authMode", "bearer") },
        )
        assertNull(mcpOAuthConfiguration(remote))
    }
}
