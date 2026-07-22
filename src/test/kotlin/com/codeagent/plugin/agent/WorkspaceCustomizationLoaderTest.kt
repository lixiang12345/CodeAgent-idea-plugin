package com.codeagent.plugin.agent

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceCustomizationLoaderTest {
    @Test
    fun `loads structured rule triggers and defaults to always`() {
        val project = Files.createTempDirectory("codeagent-rules")
        try {
            project.resolve(".codeagent/rules").createDirectories()
            project.resolve(".codeagent/rules/review.md").writeText("# Review\n\nInspect the diff.")
            project.resolve(".codeagent/rules/testing.md").writeText("# Testing\n\nAdd tests.")
            project.resolve(".codeagent/rules.json").writeText(
                """{"triggers":{".codeagent/rules/review.md":"manual"},"descriptions":{".codeagent/rules/review.md":"Use before finalizing a change"}}""",
            )

            val rules = WorkspaceCustomizationLoader(project).load().rules.associateBy { it.name }

            assertEquals("manual", rules.getValue("Review").trigger)
            assertEquals("always", rules.getValue("Testing").trigger)
            assertEquals("Use before finalizing a change", rules.getValue("Review").description)
            assertEquals("Add tests.", rules.getValue("Testing").description)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loads workspace guidelines and layers them after AGENTS instructions`() {
        val project = Files.createTempDirectory("codeagent-guidelines")
        try {
            project.resolve(".codeagent").createDirectories()
            project.resolve("AGENTS.md").writeText("# Repository instructions\n\nRun focused tests.")
            project.resolve(".codeagent/guidelines.md").writeText("# Workspace guidelines\n\nPrefer small reviewable changes.")

            val customization = WorkspaceCustomizationLoader(project).load()
            val guidance = requireNotNull(WorkspaceGuidanceLoader(project).load())

            assertEquals("# Workspace guidelines\n\nPrefer small reviewable changes.", customization.guidelines)
            assertTrue(guidance.contains("AGENTS.md:\n# Repository instructions"))
            assertTrue(guidance.contains("Workspace guidelines:\n# Workspace guidelines"))
            assertTrue(guidance.indexOf("AGENTS.md:") < guidance.indexOf("Workspace guidelines:"))
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
