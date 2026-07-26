package com.codeagent.plugin.agent

/** What a matched permission rule does with a tool call. */
enum class ToolPermissionDecision { ALLOW, DENY, ASK }

/**
 * One per-tool permission rule, mirroring the original plugin's rules engine.
 * [toolName] matches exactly or as a `prefix*` glob. [shellInputRegex], when
 * present, narrows the rule to calls whose command-like argument matches it,
 * so a rule can allow `git status` while still asking for `git push`.
 */
data class ToolPermissionRule(
    val toolName: String,
    val decision: ToolPermissionDecision,
    val shellInputRegex: String? = null,
) {
    private val compiled: Regex? = shellInputRegex
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Regex(it) }.getOrNull() }

    /** True when [shellInputRegex] was supplied but is not a valid pattern. */
    val hasInvalidRegex: Boolean = !shellInputRegex.isNullOrBlank() && compiled == null

    fun matches(toolName: String, shellInput: String?): Boolean {
        if (!matchesName(toolName)) return false
        val pattern = compiled ?: return shellInputRegex.isNullOrBlank()
        return shellInput != null && pattern.containsMatchIn(shellInput)
    }

    private fun matchesName(candidate: String): Boolean = when {
        toolName == "*" -> true
        toolName.endsWith("*") -> candidate.startsWith(toolName.dropLast(1))
        else -> candidate == toolName
    }
}

/**
 * Resolves whether a tool call runs, is refused outright, or needs the user's
 * approval. Explicit rules win over the global auto-approve setting; the most
 * specific matching rule wins, and a deny always beats an allow at equal
 * specificity so a narrowing rule cannot be bypassed by ordering.
 */
object ToolPermissionRules {
    fun decide(
        toolName: String,
        risk: ToolRisk,
        shellInput: String?,
        rules: List<ToolPermissionRule>,
        autoApproveReadOnly: Boolean,
    ): ToolPermissionDecision {
        val matched = rules.filter { !it.hasInvalidRegex && it.matches(toolName, shellInput) }
        if (matched.isNotEmpty()) {
            val best = matched.maxWith(
                compareBy<ToolPermissionRule> { specificity(it) }
                    .thenBy { if (it.decision == ToolPermissionDecision.DENY) 1 else 0 },
            )
            return best.decision
        }
        return defaultDecision(risk, autoApproveReadOnly)
    }

    fun defaultDecision(risk: ToolRisk, autoApproveReadOnly: Boolean): ToolPermissionDecision = when (risk) {
        ToolRisk.MUTATING -> ToolPermissionDecision.ASK
        ToolRisk.LOCAL_STATE -> ToolPermissionDecision.ALLOW
        ToolRisk.READ_ONLY -> if (autoApproveReadOnly) ToolPermissionDecision.ALLOW else ToolPermissionDecision.ASK
    }

    /** A shell-input rule is more specific than a name rule; exact names beat globs. */
    private fun specificity(rule: ToolPermissionRule): Int {
        val nameScore = when {
            rule.toolName == "*" -> 0
            rule.toolName.endsWith("*") -> 1
            else -> 2
        }
        return nameScore * 2 + if (!rule.shellInputRegex.isNullOrBlank()) 1 else 0
    }

    /**
     * Parses the persisted rule text: one `toolName=allow|deny|ask[;regex]`
     * per line, `#` comments and blank lines ignored. Malformed lines are
     * skipped rather than failing the run, so a bad rule cannot block work.
     */
    fun parse(text: String): List<ToolPermissionRule> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = line.take(separator).trim()
            val rest = line.drop(separator + 1).trim()
            if (name.isEmpty() || rest.isEmpty()) return@mapNotNull null
            val decisionText = rest.substringBefore(';').trim().lowercase()
            val regex = rest.substringAfter(';', "").trim().takeIf { it.isNotEmpty() }
            val decision = when (decisionText) {
                "allow" -> ToolPermissionDecision.ALLOW
                "deny" -> ToolPermissionDecision.DENY
                "ask", "ask-user" -> ToolPermissionDecision.ASK
                else -> return@mapNotNull null
            }
            ToolPermissionRule(name, decision, regex).takeUnless { it.hasInvalidRegex }
        }
        .take(200)
        .toList()

    /** Extracts the command-like argument a shell rule should test against. */
    fun shellInputOf(arguments: kotlinx.serialization.json.JsonObject?): String? {
        if (arguments == null) return null
        for (key in SHELL_INPUT_KEYS) {
            val value = arguments[key]
            val content = runCatching {
                (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()
            }.getOrNull()
            if (!content.isNullOrBlank()) return content
        }
        return null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private val SHELL_INPUT_KEYS = listOf("command", "input_text", "input", "script")
}
