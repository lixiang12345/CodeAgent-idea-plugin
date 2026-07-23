package com.codeagent.plugin.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineCompletionSettingsServiceTest {
    private fun service(disabled: String): InlineCompletionSettingsService =
        InlineCompletionSettingsService().apply { setDisabledLanguages(disabled) }

    @Test
    fun `no disabled languages allows every file`() {
        val service = service("")
        assertFalse(service.isCompletionDisabledForFile("main.kt"))
        assertFalse(service.isCompletionDisabledForFile("script.js"))
    }

    @Test
    fun `disables matching extension with star prefix`() {
        val service = service("*.js, *.ts")
        assertTrue(service.isCompletionDisabledForFile("app.js"))
        assertTrue(service.isCompletionDisabledForFile("module.ts"))
        assertFalse(service.isCompletionDisabledForFile("main.kt"))
    }

    @Test
    fun `matching is case insensitive and tolerates bare extensions`() {
        val service = service("JS, .py")
        assertTrue(service.isCompletionDisabledForFile("Widget.JS"))
        assertTrue(service.isCompletionDisabledForFile("run.py"))
        assertFalse(service.isCompletionDisabledForFile("run.pyc"))
    }

    @Test
    fun `normalizes stored value to trimmed comma separated list`() {
        val service = service("  *.js ,, *.ts  ,")
        assertEquals("*.js, *.ts", service.disabledLanguages())
    }

    @Test
    fun `handles null and blank file names`() {
        val service = service("*.js")
        assertFalse(service.isCompletionDisabledForFile(null))
        assertFalse(service.isCompletionDisabledForFile("   "))
    }
}
