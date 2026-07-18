package com.codeagent.plugin.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
