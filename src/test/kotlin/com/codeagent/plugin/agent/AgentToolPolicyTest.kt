package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentToolPolicyTest {
    @Test
    fun `agent mode keeps every definition`() {
        val definitions = definitions()

        assertEquals(definitions, filterToolDefinitionsForMode("agent", definitions))
    }

    @Test
    fun `chat and ask remove mutating definitions but keep read only and local state`() {
        val definitions = definitions()

        assertEquals(
            listOf("read_file", "view_tasks"),
            filterToolDefinitionsForMode("chat", definitions).map(AgentToolDefinition::name),
        )
        assertEquals(
            listOf("read_file", "view_tasks"),
            filterToolDefinitionsForMode("ask", definitions).map(AgentToolDefinition::name),
        )
    }

    @Test
    fun `execution policy rejects mutations outside agent mode`() {
        assertTrue(isToolAllowedInMode("agent", ToolRisk.MUTATING))
        assertTrue(isToolAllowedInMode("chat", ToolRisk.READ_ONLY))
        assertTrue(isToolAllowedInMode("ask", ToolRisk.LOCAL_STATE))
        assertFalse(isToolAllowedInMode("chat", ToolRisk.MUTATING))
        assertFalse(isToolAllowedInMode("ask", ToolRisk.MUTATING))
    }

    @Test
    fun `unknown wire risks fail closed as mutating`() {
        assertEquals(ToolRisk.READ_ONLY, toolRiskFromWire("read_only"))
        assertEquals(ToolRisk.LOCAL_STATE, toolRiskFromWire("local_state"))
        assertEquals(ToolRisk.MUTATING, toolRiskFromWire("mutating"))
        assertEquals(ToolRisk.MUTATING, toolRiskFromWire("future-risk"))
    }

    @Test
    fun `unknown modes fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            filterToolDefinitionsForMode("unknown", definitions())
        }
    }

    @Test
    fun `plugin tool defaults make only defaulted schema fields optional`() {
        val parameters = buildJsonObject {
            put("type", "object")
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("start_line"))
            })
            put("properties", buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("start_line", buildJsonObject { put("type", "integer") })
            })
        }
        val updated = parametersWithDefaults(parameters, buildJsonObject { put("path", "README.md") })

        assertEquals(listOf("start_line"), updated["required"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("README.md", updated["properties"]?.jsonObject?.get("path")?.jsonObject?.get("default")?.jsonPrimitive?.content)
    }

    private fun definitions() = listOf(
        AgentToolDefinition("read_file", "Read", buildJsonObject {}, ToolRisk.READ_ONLY),
        AgentToolDefinition("view_tasks", "Tasks", buildJsonObject {}, ToolRisk.LOCAL_STATE),
        AgentToolDefinition("apply_patch", "Edit", buildJsonObject {}, ToolRisk.MUTATING),
    )
}
