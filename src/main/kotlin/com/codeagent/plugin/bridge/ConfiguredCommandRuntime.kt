package com.codeagent.plugin.bridge

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class ConfiguredCommandInvocation(
    val id: String,
    val prompt: String,
    val mode: String,
    val agentProfileId: String? = null,
)

internal object ConfiguredCommandRuntime {
    private data class Definition(
        val id: String,
        val prompt: String,
        val mode: String = "inherit",
        val agentProfileId: String? = null,
    )

    private val commandPattern = Regex("""^/([A-Za-z0-9._-]{1,120})(?:\s+([\s\S]*))?$""")
    private val supportedModes = setOf("agent", "chat", "ask")
    private val builtIns = mapOf(
        "explain" to Definition(
            id = "explain",
            mode = "ask",
            agentProfileId = "context",
            prompt = "Explain the selected, attached, or referenced code in {{project}}. Focus on behavior, control flow, dependencies, and important risks. Address this scope or question: {{arguments}}",
        ),
        "test" to Definition(
            id = "test",
            mode = "agent",
            agentProfileId = "loop",
            prompt = "Add or run focused tests in {{project}} for the requested scope. Inspect the existing test conventions, implement only justified coverage, run the relevant checks, and report remaining risk. Scope: {{arguments}}",
        ),
        "fix" to Definition(
            id = "fix",
            mode = "agent",
            agentProfileId = "loop",
            prompt = "Find and fix the reported defect in {{project}}. Reproduce or establish evidence first, keep the change scoped, add a regression test when practical, and verify the result. Defect or scope: {{arguments}}",
        ),
        "review" to Definition(
            id = "review",
            mode = "ask",
            agentProfileId = "loop",
            prompt = "Review the current change set in {{project}}. Lead with concrete bugs, regressions, security or reliability risks, and missing tests. Ground findings in file evidence and avoid unrelated refactoring. Additional scope: {{arguments}}",
        ),
    )

    fun resolve(
        commandLine: String,
        fallbackMode: String,
        projectName: String,
        configurations: List<ProductConfigurationDto>,
    ): ConfiguredCommandInvocation {
        require(fallbackMode in supportedModes) { "Unsupported command fallback mode: $fallbackMode" }
        val normalized = commandLine.trim()
        require(normalized.length <= MAX_COMMAND_LINE_CHARS) { "Command line exceeds $MAX_COMMAND_LINE_CHARS characters" }
        val match = requireNotNull(commandPattern.matchEntire(normalized)) { "Invalid slash command syntax" }
        val id = match.groupValues[1]
        val arguments = match.groupValues.getOrElse(2) { "" }.trim()
        val definition = configuredDefinition(id, configurations) ?: builtIns[id]
            ?: error("Unknown or disabled slash command: /$id")
        val mode = definition.mode.takeUnless { it == "inherit" } ?: fallbackMode
        require(mode in supportedModes) { "Unsupported command mode: ${definition.mode}" }
        val usesArguments = "{{arguments}}" in definition.prompt
        var expanded = definition.prompt
            .replace("{{arguments}}", arguments)
            .replace("{{project}}", projectName)
            .replace("{{command}}", id)
            .trim()
        if (!usesArguments && arguments.isNotBlank()) {
            val safeArguments = arguments.replace("</command_arguments>", "&lt;/command_arguments&gt;")
            expanded += "\n\nCommand arguments:\n<command_arguments>\n$safeArguments\n</command_arguments>"
        }
        require(expanded.isNotBlank()) { "Slash command /$id produced an empty prompt" }
        require(expanded.length <= MAX_EXPANDED_PROMPT_CHARS) {
            "Expanded slash command /$id exceeds $MAX_EXPANDED_PROMPT_CHARS characters"
        }
        return ConfiguredCommandInvocation(
            id = id,
            prompt = expanded,
            mode = mode,
            agentProfileId = definition.agentProfileId,
        )
    }

    private fun configuredDefinition(
        id: String,
        configurations: List<ProductConfigurationDto>,
    ): Definition? {
        val configuration = configurations.firstOrNull { it.id == id } ?: return null
        if (configuration.value["enabled"]?.jsonPrimitive?.booleanOrNull == false) return null
        val prompt = configuration.value["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (prompt.isBlank()) return null
        return Definition(
            id = id,
            prompt = prompt,
            mode = configuration.value["mode"]?.jsonPrimitive?.contentOrNull ?: "inherit",
            agentProfileId = configuration.value["agentProfileId"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    private const val MAX_COMMAND_LINE_CHARS = 100_000
    private const val MAX_EXPANDED_PROMPT_CHARS = 100_000
}
