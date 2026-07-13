package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitStatusParserTest {
    @Test
    fun `parses staged unstaged and untracked files`() {
        val output = "## feature/login...origin/feature/login\u0000" +
            " M src/Auth.kt\u0000" +
            "M  src/Token.kt\u0000" +
            "MM src/Both.kt\u0000" +
            "?? docs/notes.md\u0000"

        val snapshot = GitStatusParser.parse(output, "sample")

        assertEquals("feature/login", snapshot.branch)
        assertEquals(listOf("src/Both.kt", "src/Token.kt"), snapshot.staged.map { it.path })
        assertEquals(listOf("docs/notes.md", "src/Auth.kt", "src/Both.kt"), snapshot.unstaged.map { it.path })
        assertTrue(snapshot.available)
    }

    @Test
    fun `consumes the extra source path in porcelain rename records`() {
        val output = "## main\u0000R  src/New.kt\u0000src/Old.kt\u0000 M README.md\u0000"

        val snapshot = GitStatusParser.parse(output, "sample")

        assertEquals(listOf("src/New.kt"), snapshot.staged.map { it.path })
        assertEquals(listOf("README.md"), snapshot.unstaged.map { it.path })
        assertEquals("renamed", snapshot.staged.single().status)
    }

    @Test
    fun `reads branch name for a repository without commits`() {
        val snapshot = GitStatusParser.parse("## No commits yet on main\u0000?? README.md\u0000", "sample")

        assertEquals("main", snapshot.branch)
        assertEquals("README.md", snapshot.unstaged.single().path)
    }
}
