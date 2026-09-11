package com.tsuty.sandboxagent.ui.transcript

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tsuty.sandboxagent.settings.SandboxAgentSettingsState
import com.tsuty.sandboxagent.ui.SandboxAgentColors
import java.awt.Color
import javax.swing.UIManager
import kotlin.math.roundToInt

/**
 * The current IDE theme as the CSS custom properties `transcript.css` is written against.
 *
 * This is the one place where the styling of the transcript still depends on the IDE: the layout
 * and the typography live in the bundled stylesheet, and only the colors and the two font families
 * are handed over. A look and feel change therefore only sends a new [ThemeEvent] — the document
 * keeps its DOM, and with it the scroll position and every folded entry.
 *
 * The sizes are not taken from the IDE: the transcript is read like a document, not like a tool
 * bar, so it carries one font size of its own ([SandboxAgentSettingsState.transcriptFontSize]) and
 * every other size is derived from that base — the quiet rows with [SMALL_RATIO], and code with
 * the base size itself, so a fence lines up with the prose around it.
 */
object ThemeTokens {

    /** Quiet rows (timestamps, tool call lines, captions) relative to the base size. */
    const val SMALL_RATIO: Double = 0.85

    fun event(baseFontSize: Int): ThemeEvent =
        ThemeEvent(tokens = tokens(baseFontSize), dark = !JBColor.isBright())

    /** The size the quiet rows are read with, derived from [baseFontSize]. */
    fun smallFontSize(baseFontSize: Int): Int =
        (baseFontSize * SMALL_RATIO).roundToInt().coerceIn(1, baseFontSize)

    fun tokens(baseFontSize: Int): Map<String, String> {
        val labelFont = UIUtil.getLabelFont()
        val editorScheme = EditorColorsManager.getInstance().globalScheme
        return buildMap {
            put("--ra-bg", css(SandboxAgentColors.PANEL_BACKGROUND))
            put("--ra-fg", css(UIUtil.getLabelForeground()))
            put("--ra-quiet", css(UIUtil.getContextHelpForeground()))
            put("--ra-link", css(JBUI.CurrentTheme.Link.Foreground.ENABLED))
            put("--ra-border", css(JBColor.border()))
            put("--ra-code-bg", css(SandboxAgentColors.CODE_BACKGROUND))
            put("--ra-error", css(JBColor.RED))
            put("--ra-accent", css(accent()))
            put("--ra-hover-bg", css(hoverBackground()))
            put("--ra-selection-bg", css(selectionBackground()))
            put("--ra-added-bg", css(ADDED_BACKGROUND))
            put("--ra-removed-bg", css(REMOVED_BACKGROUND))
            put("--ra-font", fontStack(labelFont.family, UI_FALLBACKS))
            put("--ra-font-size", "${baseFontSize}px")
            put("--ra-small-size", "${smallFontSize(baseFontSize)}px")
            put("--ra-mono-font", fontStack(editorScheme.editorFontName, MONO_FALLBACKS))
            put("--ra-mono-size", "${baseFontSize}px")
            putAll(highlightTokens(editorScheme))
        }
    }

    /**
     * A `font-family` list, not a single name.
     *
     * The browser only sees the fonts the operating system knows, while the IDE happily uses one
     * of its own: the default UI font of the platform is `.AppleSystemUIFont` on macOS (a name
     * that is not even a legal CSS identifier, so the whole declaration would be dropped) and the
     * editor font is usually JetBrains Mono, which ships inside the IDE and is registered with the
     * JVM only. Without a fallback list Chromium falls back to *its* default family, which is a
     * serif one — that is where the transcript picked up its Mincho look, code blocks included.
     */
    fun fontStack(family: String?, fallbacks: List<String>): String {
        val requested = family?.trim().orEmpty()
        // A leading dot marks the private system font aliases of macOS; `system-ui` names them.
        val head = if (requested.isEmpty() || requested.startsWith(".")) emptyList() else listOf(quote(requested))
        return (head + fallbacks).joinToString(", ")
    }

    private fun quote(family: String): String = "\"" + family.replace("\"", "") + "\""

    /**
     * Colors of the code token classes `transcript.css` styles, taken from the editor scheme, so a
     * code fence in the transcript is colored the way the same code is colored in the editor (see
     * [CodeHighlighter], which puts the classes on the tokens).
     */
    private fun highlightTokens(scheme: EditorColorsScheme): Map<String, String> =
        CodeHighlighter.CATEGORIES.mapNotNull { (category, keys) ->
            keys.firstNotNullOfOrNull { key -> scheme.getAttributes(key)?.foregroundColor }
                ?.let { color -> "--ra-hl-$category" to css(color) }
        }.toMap()

    /** Background of the default button, which is what the IDE uses as its accent color. */
    private fun accent(): Color =
        UIManager.getColor("Button.default.startBackground")
            ?: UIManager.getColor("Component.focusColor")
            ?: JBUI.CurrentTheme.Link.Foreground.ENABLED

    private fun hoverBackground(): Color =
        UIManager.getColor("ActionButton.hoverBackground")
            ?: JBUI.CurrentTheme.ActionButton.hoverBackground()

    private fun selectionBackground(): Color =
        UIManager.getColor("SearchMatch.startBackground")
            ?: ColorUtil.withAlpha(JBColor.YELLOW, 0.4)

    /**
     * `rgba(...)`, so a translucent theme color (the hover background of a toolbar button is one)
     * survives the trip: `#rrggbb` would silently turn it opaque.
     */
    private fun css(color: Color): String {
        if (color.alpha == 255) return "#" + ColorUtil.toHex(color)
        val alpha = "%.3f".format(color.alpha / 255.0).trimEnd('0').trimEnd('.')
        return "rgba(${color.red}, ${color.green}, ${color.blue}, $alpha)"
    }

    /**
     * Diff tints of the transcript. The editor scheme exposes its own diff colors, but they are
     * meant for a full width editor gutter: these are the quiet, GitHub-like tints the folded
     * away tool call bodies are read with.
     */
    private val ADDED_BACKGROUND: Color = JBColor.lazy {
        if (JBColor.isBright()) Color(0xE6, 0xFF, 0xEC) else Color(0x1F, 0x35, 0x25)
    }

    private val REMOVED_BACKGROUND: Color = JBColor.lazy {
        if (JBColor.isBright()) Color(0xFF, 0xEB, 0xE9) else Color(0x3D, 0x22, 0x22)
    }

    /** Generic UI families, in the order Chromium should try them. */
    private val UI_FALLBACKS = listOf("system-ui", "-apple-system", "\"Segoe UI\"", "Roboto", "sans-serif")

    private val MONO_FALLBACKS = listOf(
        "ui-monospace",
        "Menlo",
        "\"SF Mono\"",
        "Consolas",
        "\"DejaVu Sans Mono\"",
        "monospace",
    )
}
