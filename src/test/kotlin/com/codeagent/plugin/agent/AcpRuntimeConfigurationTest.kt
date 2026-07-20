package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AcpRuntimeConfigurationTest {
    @Test
    fun `extracts bounded ACP configuration`() {
        val remote = RemoteConfiguration(
            id = "review-agent",
            kind = "acp",
            value = buildJsonObject {
                put("name", "Review agent")
                put("command", "review-agent")
                putJsonArray("args") { add(kotlinx.serialization.json.JsonPrimitive("--acp")) }
                put("authMethodId", "agent-login")
                put("timeoutSeconds", 300)
            },
        )
        val parsed = acpAgentConfiguration(remote)
        assertEquals("review-agent", parsed.id)
        assertEquals(listOf("--acp"), parsed.args)
        assertEquals("agent-login", parsed.authMethodId)
        assertEquals(300, parsed.timeoutSeconds)
    }

    @Test
    fun `requires an ACP command`() {
        val remote = RemoteConfiguration("broken", "acp", buildJsonObject { put("name", "Broken") })
        assertFailsWith<IllegalArgumentException> { acpAgentConfiguration(remote) }
    }
}
