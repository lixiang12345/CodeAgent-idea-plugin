package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentToolArgumentsTest {
    @Test
    fun `allows omitted and empty optional string lists`() {
        assertEquals(emptyList(), buildJsonObject {}.optionalStringListArgument("focus_paths"))
        assertEquals(
            emptyList(),
            buildJsonObject { putJsonArray("focus_paths") {} }.optionalStringListArgument("focus_paths"),
        )
    }

    @Test
    fun `trims optional string lists and ignores blank placeholders`() {
        val arguments = buildJsonObject {
            putJsonArray("focus_paths") {
                add(" src/main ")
                add(" ")
                add("frontend")
                add("")
            }
        }
        assertEquals(listOf("src/main", "frontend"), arguments.optionalStringListArgument("focus_paths"))

        val blankOnly = buildJsonObject {
            putJsonArray("focus_paths") {
                add(" ")
                add("")
            }
        }
        assertEquals(emptyList(), blankOnly.optionalStringListArgument("focus_paths"))
    }

    @Test
    fun `prefers canonical process arguments and accepts legacy aliases`() {
        val canonical = buildJsonObject {
            put("terminal_id", "terminal-1")
            put("process_id", "terminal-1")
        }
        val legacy = buildJsonObject { put("process_id", "terminal-2") }

        assertEquals("terminal-1", canonical.requiredAliasedStringArgument("terminal_id", "process_id"))
        assertEquals("terminal-2", legacy.requiredAliasedStringArgument("terminal_id", "process_id"))
        assertEquals("", buildJsonObject { put("input", "") }.requiredAliasedStringArgument("input_text", "input", allowEmpty = true))
    }

    @Test
    fun `rejects conflicting canonical and legacy process arguments`() {
        val arguments = buildJsonObject {
            put("terminal_id", "terminal-1")
            put("process_id", "terminal-2")
        }

        assertFailsWith<IllegalArgumentException> {
            arguments.requiredAliasedStringArgument("terminal_id", "process_id")
        }
    }
}
