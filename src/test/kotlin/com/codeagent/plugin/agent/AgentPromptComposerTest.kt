package com.codeagent.plugin.agent

import kotlin.io.path.createTempDirectory
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
    fun `rejects an unknown mode`() {
        assertFailsWith<IllegalArgumentException> {
            AgentPromptComposer().compose("unrestricted")
        }
    }
}
