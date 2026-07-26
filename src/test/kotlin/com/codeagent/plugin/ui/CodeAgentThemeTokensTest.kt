package com.codeagent.plugin.ui

import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the shape of the theme payload the webview consumes: the frontend
 * rejects keys that are not `--kebab-case` and values that are over 120
 * characters or contain `;`, `{`, `}`, or `url(`.
 */
class CodeAgentThemeTokensTest {
    private fun source(dark: Boolean, fontName: String? = "JetBrains Mono", uiFontSize: Int = 13) = ThemeSource(
        dark = dark,
        panelBackground = if (dark) Color(0x2B2D30) else Color(0xF7F8FA),
        editorBackground = if (dark) Color(0x1E1F22) else Color(0xFFFFFF),
        foreground = if (dark) Color(0xDFE1E5) else Color(0x1E1F22),
        disabled = Color(0x868A91),
        border = Color(0x393B40),
        separator = Color(0x393B40),
        accent = Color(0x3574F0),
        error = Color(0xDB5C5C),
        success = Color(0x499C54),
        warning = Color(0xD19A66),
        editorFontName = fontName,
        uiFontSize = uiFontSize,
    )

    private fun assertWebviewSafe(tokens: Map<String, String>) {
        val keyPattern = Regex("^--[a-z0-9-]+$")
        tokens.forEach { (key, value) ->
            if (key == "colorScheme") {
                assertTrue(value == "light" || value == "dark", "colorScheme must be light or dark, was $value")
                return@forEach
            }
            assertTrue(keyPattern.matches(key), "Token key '$key' would be rejected by the webview")
            assertTrue(value.length <= 120, "Token '$key' value is too long for the webview")
            assertTrue(
                !value.contains(";") && !value.contains("{") && !value.contains("}") &&
                    !value.lowercase().contains("url("),
                "Token '$key' value would be rejected by the webview: $value",
            )
        }
    }

    @Test
    fun `every emitted token survives the webview validator in both schemes`() {
        assertWebviewSafe(CodeAgentThemeTokens.build(source(dark = true)))
        assertWebviewSafe(CodeAgentThemeTokens.build(source(dark = false)))
    }

    @Test
    fun `covers the css variables the panel styles depend on`() {
        val tokens = CodeAgentThemeTokens.build(source(dark = true))
        val required = listOf(
            "--bg", "--panel", "--panel-2", "--panel-3", "--chrome",
            "--line", "--line-strong", "--border", "--border2", "--text",
            "--muted", "--dim", "--bright", "--blue", "--blue-soft",
            "--green", "--green-soft", "--red", "--red-soft", "--amber",
            "--accent", "--focus-ring", "--mono",
        )
        required.forEach { key -> assertTrue(tokens.containsKey(key), "Missing theme token $key") }
        assertEquals("dark", tokens["colorScheme"])
    }

    @Test
    fun `reports the light scheme for a bright theme`() {
        assertEquals("light", CodeAgentThemeTokens.build(source(dark = false))["colorScheme"])
    }

    @Test
    fun `colors are emitted as six-digit hex`() {
        val hex = Regex("^#[0-9a-f]{6}$")
        val colors = CodeAgentThemeTokens.build(source(dark = true))
            .filterKeys { it.startsWith("--") && it != "--mono" && it != "--ide-font-size" }
        assertTrue(colors.isNotEmpty())
        colors.forEach { (key, value) ->
            assertTrue(hex.matches(value), "Token '$key' is not a six-digit hex color: $value")
        }
    }

    @Test
    fun `light and dark produce different surfaces`() {
        val dark = CodeAgentThemeTokens.build(source(dark = true))
        val light = CodeAgentThemeTokens.build(source(dark = false))
        assertTrue(dark["--bg"] != light["--bg"])
        assertTrue(dark["--panel"] != light["--panel"])
        assertEquals(dark.keys, light.keys)
    }

    @Test
    fun `a hostile font name cannot inject css`() {
        val tokens = CodeAgentThemeTokens.build(
            source(dark = true, fontName = "Evil\"; background: url(http://x/y); font-family: \""),
        )
        assertWebviewSafe(tokens)
        val mono = tokens.getValue("--mono")
        listOf("\";", ";", "(", ")", ":", "/").forEach { dangerous ->
            assertTrue(!mono.substringBefore("\", \"JetBrains").contains(dangerous), "Font name kept '$dangerous': $mono")
        }
        assertTrue(mono.endsWith("\"JetBrains Mono\", Menlo, monospace"))
    }

    @Test
    fun `a blank font name falls back`() {
        val tokens = CodeAgentThemeTokens.build(source(dark = true, fontName = null))
        assertTrue(tokens.getValue("--mono").startsWith("\"JetBrains Mono\""))
    }

    @Test
    fun `ui font size is bounded`() {
        assertEquals("48px", CodeAgentThemeTokens.build(source(dark = true, uiFontSize = 400))["--ide-font-size"])
        assertEquals("8px", CodeAgentThemeTokens.build(source(dark = true, uiFontSize = 1))["--ide-font-size"])
    }
}
