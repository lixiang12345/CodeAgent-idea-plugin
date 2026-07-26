package com.codeagent.plugin.diagnostics

import com.codeagent.plugin.agent.McpOAuthMetadataDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.URI

class McpOAuthMetadataDiscoveryTest {
    @Test
    fun `generates ordered metadata candidates for resource with path`() {
        val resource = URI.create("https://mcp.example.test/api/resources")
        val candidates = McpOAuthMetadataDiscovery.metadataCandidates(resource)
        assertEquals(8, candidates.size)
        assertEquals("https://mcp.example.test/.well-known/oauth-protected-resource/api/resources", candidates[0])
        assertEquals("https://mcp.example.test/api/resources/.well-known/oauth-protected-resource", candidates[1])
        assertEquals("https://mcp.example.test/.well-known/oauth-authorization-server/api/resources", candidates[2])
        assertEquals("https://mcp.example.test/api/resources/.well-known/oauth-authorization-server", candidates[3])
        assertEquals("https://mcp.example.test/.well-known/oauth-authorization-server", candidates[4])
        assertEquals("https://mcp.example.test/.well-known/openid-configuration/api/resources", candidates[5])
        assertEquals("https://mcp.example.test/api/resources/.well-known/openid-configuration", candidates[6])
        assertEquals("https://mcp.example.test/.well-known/openid-configuration", candidates[7])
    }

    @Test
    fun `generates root-only metadata candidates for resource without path`() {
        val resource = URI.create("https://identity.example.test")
        val candidates = McpOAuthMetadataDiscovery.metadataCandidates(resource)
        assertTrue(candidates.size >= 2)
        assertTrue(candidates.contains("https://identity.example.test/.well-known/oauth-authorization-server"))
        assertTrue(candidates.contains("https://identity.example.test/.well-known/openid-configuration"))
    }

    @Test
    fun `handles trailing slash in resource URL`() {
        val resource = URI.create("https://mcp.example.test/api/")
        val candidates = McpOAuthMetadataDiscovery.metadataCandidates(resource)
        assertTrue(candidates.any { it.contains("/api") && it.contains("oauth-protected-resource") })
    }
}
