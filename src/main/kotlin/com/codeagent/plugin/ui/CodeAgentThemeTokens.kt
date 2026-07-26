package com.codeagent.plugin.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import javax.swing.UIManager

/**
 * Derives the webview's CSS custom properties from the current IDE theme, so
 * the panel follows Light, Dark, and custom Look and Feels instead of shipping
 * hardcoded dark tokens. Mirrors the original plugin's UIDefaults-to-CSS bridge.
 */
/** Resolved IDE colors and fonts, separated from lookup so mapping stays testable. */
data class ThemeSource(
    val dark: Boolean,
    val panelBackground: Color,
    val editorBackground: Color,
    val foreground: Color,
    val disabled: Color,
    val border: Color,
    val separator: Color,
    val accent: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
    val editorFontName: String?,
    val uiFontSize: Int,
)

object CodeAgentThemeTokens {
    fun current(): Map<String, String> {
        val dark = !JBColor.isBright()
        val scheme = EditorColorsManager.getInstance().globalScheme
        return build(
            ThemeSource(
                dark = dark,
                panelBackground = color("Panel.background", if (dark) 0x2B2D30 else 0xF7F8FA),
                editorBackground = scheme.defaultBackground,
                foreground = color("Label.foreground", if (dark) 0xDFE1E5 else 0x1E1F22),
                disabled = color("Label.disabledForeground", if (dark) 0x868A91 else 0x818594),
                border = color("Component.borderColor", if (dark) 0x393B40 else 0xD3D5DB),
                separator = UIManager.getColor("Separator.separatorColor")
                    ?: color("Component.borderColor", if (dark) 0x393B40 else 0xD3D5DB),
                accent = color("Component.focusColor", 0x3574F0),
                error = color("Label.errorForeground", if (dark) 0xDB5C5C else 0xE55765),
                success = color("Label.successForeground", if (dark) 0x499C54 else 0x2E9B4F),
                warning = color("Label.warningForeground", if (dark) 0xD19A66 else 0xB07B26),
                editorFontName = scheme.editorFontName,
                uiFontSize = UIUtil.getLabelFont().size,
            ),
        )
    }

    /** Pure mapping from resolved IDE colors to webview CSS variables. */
    fun build(source: ThemeSource): Map<String, String> {
        val dark = source.dark
        val panelBackground = source.panelBackground
        val editorBackground = source.editorBackground
        val foreground = source.foreground
        val disabled = source.disabled
        val border = source.border
        val separator = source.separator
        val accent = source.accent
        val error = source.error
        val success = source.success
        val warning = source.warning
        val editorFont = sanitizeFontName(source.editorFontName)
        val uiFontSize = source.uiFontSize.coerceIn(8, 48)

        return buildMap {
            put("colorScheme", if (dark) "dark" else "light")
            put("--bg", hex(editorBackground))
            put("--panel", hex(panelBackground))
            put("--panel-2", hex(shift(panelBackground, if (dark) 0.04 else -0.02)))
            put("--panel-3", hex(shift(panelBackground, if (dark) 0.08 else -0.04)))
            put("--chrome", hex(shift(panelBackground, if (dark) 0.12 else -0.07)))
            put("--line", hex(separator))
            put("--line-strong", hex(shift(border, if (dark) 0.10 else -0.10)))
            put("--border", hex(border))
            put("--border2", hex(shift(border, if (dark) 0.10 else -0.10)))
            put("--text", hex(foreground))
            put("--muted", hex(disabled))
            put("--dim", hex(disabled))
            put("--bright", hex(shift(foreground, if (dark) 0.20 else -0.20)))
            put("--blue", hex(accent))
            put("--blue-soft", hex(blend(accent, panelBackground, 0.75)))
            put("--green", hex(success))
            put("--green-soft", hex(blend(success, panelBackground, 0.80)))
            put("--red", hex(error))
            put("--red-soft", hex(blend(error, panelBackground, 0.80)))
            put("--amber", hex(warning))
            put("--accent", hex(accent))
            put("--focus-ring", hex(shift(accent, 0.25)))
            put("--mono", "\"$editorFont\", \"JetBrains Mono\", Menlo, monospace")
            put("--ide-font-size", "${uiFontSize}px")
        }
    }

    /**
     * Font names reach CSS unquoted-adjacent, so drop characters that could
     * terminate the declaration or introduce another one.
     */
    private fun sanitizeFontName(raw: String?): String {
        val cleaned = raw.orEmpty().filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim()
        return cleaned.take(48).ifBlank { "JetBrains Mono" }
    }

    private fun color(key: String, fallbackRgb: Int): Color =
        UIManager.getColor(key) ?: Color(fallbackRgb)

    private fun hex(color: Color): String = String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    /** Lightens on a positive amount and darkens on a negative one. */
    private fun shift(color: Color, amount: Double): Color {
        val factor = amount.coerceIn(-1.0, 1.0)
        fun channel(value: Int): Int =
            if (factor >= 0) (value + (255 - value) * factor).toInt() else (value * (1 + factor)).toInt()
        return Color(
            channel(color.red).coerceIn(0, 255),
            channel(color.green).coerceIn(0, 255),
            channel(color.blue).coerceIn(0, 255),
        )
    }

    /** Mixes [color] toward [into]; ratio 0 keeps the color, 1 returns [into]. */
    private fun blend(color: Color, into: Color, ratio: Double): Color {
        val weight = ratio.coerceIn(0.0, 1.0)
        fun channel(from: Int, to: Int): Int = (from * (1 - weight) + to * weight).toInt().coerceIn(0, 255)
        return Color(
            channel(color.red, into.red),
            channel(color.green, into.green),
            channel(color.blue, into.blue),
        )
    }
}
