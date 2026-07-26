package com.codeagent.plugin.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolPermissionRulesTest {
    @Test
    fun `falls back to risk defaults without rules`() {
        assertEquals(
            ToolPermissionDecision.ASK,
            ToolPermissionRules.decide("write_file", ToolRisk.MUTATING, null, emptyList(), autoApproveReadOnly = true),
        )
        assertEquals(
            ToolPermissionDecision.ALLOW,
            ToolPermissionRules.decide("read_file", ToolRisk.READ_ONLY, null, emptyList(), autoApproveReadOnly = true),
        )
        assertEquals(
            ToolPermissionDecision.ASK,
            ToolPermissionRules.decide("read_file", ToolRisk.READ_ONLY, null, emptyList(), autoApproveReadOnly = false),
        )
        assertEquals(
            ToolPermissionDecision.ALLOW,
            ToolPermissionRules.decide("open_file", ToolRisk.LOCAL_STATE, null, emptyList(), autoApproveReadOnly = false),
        )
    }

    @Test
    fun `an explicit rule overrides the risk default`() {
        val rules = listOf(ToolPermissionRule("write_file", ToolPermissionDecision.ALLOW))
        assertEquals(
            ToolPermissionDecision.ALLOW,
            ToolPermissionRules.decide("write_file", ToolRisk.MUTATING, null, rules, autoApproveReadOnly = false),
        )
    }

    @Test
    fun `a shell input regex narrows a rule`() {
        val rules = ToolPermissionRules.parse(
            """
            run_terminal=allow;^git (status|diff|log)\b
            run_terminal=deny;^git push\b
            """.trimIndent(),
        )
        assertEquals(
            ToolPermissionDecision.ALLOW,
            ToolPermissionRules.decide("run_terminal", ToolRisk.MUTATING, "git status", rules, false),
        )
        assertEquals(
            ToolPermissionDecision.DENY,
            ToolPermissionRules.decide("run_terminal", ToolRisk.MUTATING, "git push origin main", rules, false),
        )
        // A command that matches no rule keeps the mutating default.
        assertEquals(
            ToolPermissionDecision.ASK,
            ToolPermissionRules.decide("run_terminal", ToolRisk.MUTATING, "rm -rf build", rules, false),
        )
    }

    @Test
    fun `a more specific rule wins over a wildcard`() {
        val rules = ToolPermissionRules.parse(
            """
            *=allow
            remove_files=ask
            """.trimIndent(),
        )
        assertEquals(
            ToolPermissionDecision.ALLOW,
            ToolPermissionRules.decide("write_file", ToolRisk.MUTATING, null, rules, false),
        )
        assertEquals(
            ToolPermissionDecision.ASK,
            ToolPermissionRules.decide("remove_files", ToolRisk.MUTATING, null, rules, false),
        )
    }

    @Test
    fun `prefix rules match by namespace`() {
        val rules = ToolPermissionRules.parse("mcp.notion.*=ask")
        assertEquals(
            ToolPermissionDecision.ASK,
            ToolPermissionRules.decide("mcp.notion.search", ToolRisk.READ_ONLY, null, rules, autoApproveReadOnly = true),
        )
        assertEquals(
            ToolPermissionDecision.ALLOW,
            ToolPermissionRules.decide("mcp.linear.search", ToolRisk.READ_ONLY, null, rules, autoApproveReadOnly = true),
        )
    }

    @Test
    fun `deny beats allow at equal specificity`() {
        val rules = listOf(
            ToolPermissionRule("run_terminal", ToolPermissionDecision.ALLOW),
            ToolPermissionRule("run_terminal", ToolPermissionDecision.DENY),
        )
        assertEquals(
            ToolPermissionDecision.DENY,
            ToolPermissionRules.decide("run_terminal", ToolRisk.MUTATING, null, rules, false),
        )
    }

    @Test
    fun `parsing skips malformed and invalid-regex lines`() {
        val rules = ToolPermissionRules.parse(
            """
            # comment
            no_equals_sign
            missing_decision=
            bad_decision=maybe
            broken_regex=allow;([unclosed
            write_file=allow
            """.trimIndent(),
        )
        assertEquals(1, rules.size)
        assertEquals("write_file", rules.single().toolName)
    }

    @Test
    fun `extracts the shell input from command-like arguments`() {
        assertEquals(
            "npm test",
            ToolPermissionRules.shellInputOf(buildJsonObject { put("command", "npm test") }),
        )
        assertEquals(
            "y\n",
            ToolPermissionRules.shellInputOf(buildJsonObject { put("input_text", "y\n") }),
        )
        assertEquals(
            null,
            ToolPermissionRules.shellInputOf(buildJsonObject { put("path", "src/App.kt") }),
        )
        assertEquals(null, ToolPermissionRules.shellInputOf(null))
    }

    @Test
    fun `an invalid regex rule never matches`() {
        val rule = ToolPermissionRule("run_terminal", ToolPermissionDecision.ALLOW, "([unclosed")
        assertTrue(rule.hasInvalidRegex)
        assertEquals(
            ToolPermissionDecision.ASK,
            ToolPermissionRules.decide("run_terminal", ToolRisk.MUTATING, "anything", listOf(rule), false),
        )
    }

    @Test
    fun `a name rule without regex does not match when a regex is required`() {
        val rules = listOf(ToolPermissionRule("run_terminal", ToolPermissionDecision.ALLOW, "^ls\\b"))
        assertEquals(
            ToolPermissionDecision.ASK,
            ToolPermissionRules.decide("run_terminal", ToolRisk.MUTATING, null, rules, false),
        )
        assertEquals(
            ToolPermissionDecision.ALLOW,
            ToolPermissionRules.decide("run_terminal", ToolRisk.MUTATING, "ls -la", rules, false),
        )
    }

    @Test
    fun `json null shell input is ignored`() {
        val arguments = buildJsonObject { put("command", JsonPrimitive(null as String?)) }
        assertEquals(null, ToolPermissionRules.shellInputOf(arguments))
    }
}
