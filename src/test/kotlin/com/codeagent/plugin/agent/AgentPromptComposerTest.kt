package com.codeagent.plugin.agent

import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentPromptComposerTest {
    @Test
    fun `composes backend policy before workspace guidance`() {
        val project = createTempDirectory("codeagent-prompt")
        project.resolve("AGENTS.md").writeText("Use the repository test conventions.")

        try {
            val prompt = AgentPromptComposer(WorkspaceGuidanceLoader(project)).compose("agent")

            assertEquals(AgentPromptComposer.PROMPT_VERSION, prompt.version)
            assertEquals("system", prompt.message.role)
            assertTrue(prompt.includesWorkspaceGuidance)
            assertContains(prompt.message.content.orEmpty(), "File mutations and terminal commands require explicit user approval")
            assertContains(prompt.message.content.orEmpty(), "Use the repository test conventions.")
            assertTrue(
                prompt.message.content.orEmpty().indexOf("## Safety and authority") <
                    prompt.message.content.orEmpty().indexOf("## Workspace guidance"),
            )
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `uses a read-only prompt for ask mode`() {
        val prompt = AgentPromptComposer().compose("ask")

        assertContains(prompt.message.content.orEmpty(), "Do not modify files or run shell commands")
        assertTrue(!prompt.includesWorkspaceGuidance)
    }

    @Test
    fun `loads repository rules and only enabled skills`() {
        val project = createTempDirectory("codeagent-customization")
        project.resolve(".codeagent/rules").createDirectories()
        project.resolve(".codeagent/rules/testing.md").writeText("# Testing\n\nAlways add a regression test.")
        project.resolve(".codeagent/skills/release").createDirectories()
        project.resolve(".codeagent/skills/release/SKILL.md").writeText(
            "# Release workflow\n\nVerify the distribution before tagging.",
        )
        project.resolve(".agents/skills/review").createDirectories()
        project.resolve(".agents/skills/review/SKILL.md").writeText(
            "---\nname: Review\n---\n# Review changes\n\nInspect the diff for regressions.",
        )

        try {
            val customization = WorkspaceCustomizationLoader(project).load()
            val releaseSkill = customization.skills.single { it.name == "Release workflow" }
            val prompt = AgentPromptComposer().compose(
                mode = "agent",
                customization = customization,
                enabledSkillIds = setOf(releaseSkill.id),
            )

            assertEquals(1, prompt.ruleCount)
            assertEquals(1, prompt.skillCount)
            assertContains(prompt.message.content.orEmpty(), "Always add a regression test.")
            assertContains(prompt.message.content.orEmpty(), "Verify the distribution before tagging.")
            assertTrue("Inspect the diff for regressions." !in prompt.message.content.orEmpty())
            assertEquals("Inspect the diff for regressions.", customization.skills.single { it.name == "Review changes" }.description)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects an unknown mode`() {
        assertFailsWith<IllegalArgumentException> {
            AgentPromptComposer().compose("unrestricted")
        }
    }
}
