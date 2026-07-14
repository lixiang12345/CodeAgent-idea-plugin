package com.codeagent.plugin.bridge

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfiguredCommandRuntimeTest {
    @Test
    fun `expands configured arguments and runtime overrides`() {
        val configuration = ProductConfigurationDto(
            id = "release-check",
            kind = "commands",
            value = buildJsonObject {
                put("name", "Release check")
                put("enabled", true)
                put("prompt", "Check {{project}} release readiness for {{arguments}} using /{{command}}.")
                put("mode", "agent")
                put("agentProfileId", "loop")
            },
        )

        val invocation = ConfiguredCommandRuntime.resolve(
            commandLine = "/release-check backend and plugin",
            fallbackMode = "chat",
            projectName = "CodeAgent",
            configurations = listOf(configuration),
        )

        assertEquals("release-check", invocation.id)
        assertEquals("Check CodeAgent release readiness for backend and plugin using /release-check.", invocation.prompt)
        assertEquals("agent", invocation.mode)
        assertEquals("loop", invocation.agentProfileId)
    }

    @Test
    fun `appends arguments when the template has no placeholder`() {
        val configuration = ProductConfigurationDto(
            id = "inspect",
            kind = "commands",
            value = buildJsonObject {
                put("name", "Inspect")
                put("prompt", "Inspect the requested code carefully.")
                put("mode", "inherit")
            },
        )

        val invocation = ConfiguredCommandRuntime.resolve(
            commandLine = "/inspect src/main/App.kt",
            fallbackMode = "ask",
            projectName = "CodeAgent",
            configurations = listOf(configuration),
        )

        assertEquals("ask", invocation.mode)
        assertTrue(invocation.prompt.contains("<command_arguments>"))
        assertTrue(invocation.prompt.contains("src/main/App.kt"))
    }

    @Test
    fun `resolves built in command policy and rejects disabled commands`() {
        val disabled = ProductConfigurationDto(
            id = "private",
            kind = "commands",
            value = buildJsonObject {
                put("name", "Private")
                put("prompt", "Do private work")
                put("enabled", false)
            },
        )

        val builtIn = ConfiguredCommandRuntime.resolve(
            commandLine = "/fix authentication failure",
            fallbackMode = "chat",
            projectName = "CodeAgent",
            configurations = listOf(disabled),
        )
        assertEquals("agent", builtIn.mode)
        assertEquals("loop", builtIn.agentProfileId)
        assertTrue(builtIn.prompt.contains("authentication failure"))

        assertFailsWith<IllegalStateException> {
            ConfiguredCommandRuntime.resolve(
                commandLine = "/private",
                fallbackMode = "agent",
                projectName = "CodeAgent",
                configurations = listOf(disabled),
            )
        }
    }
}
