package com.tsuty.remoteagent.ui.transcript

import com.tsuty.remoteagent.acp.ToolKind

/**
 * The icons of the transcript document, as one SVG sprite.
 *
 * The drawings are files (`src/main/resources/icons/transcript/<name>.svg`, see
 * `docs/development/ICONS.md`) rather than path data inside `transcript.js`: an icon is drawn in a
 * drawing tool and exported, and a `d` attribute in a script cannot be edited that way — nor can
 * it hold an icon made of more than one shape, which is what the exports of this project look like.
 *
 * Every file becomes a `<symbol id="ra-icon-<name>">` of one sprite that [TranscriptDocument]
 * inlines into the document, and the script only ever refers to a name (`<use href="#ra-icon-…">`).
 * The geometry therefore exists once, no matter how many hundreds of tool call rows a session
 * scrolled by, and no drawing has to survive the CSP (`default-src 'none'`) as a loaded resource.
 *
 * The export is normalized on the way in ([symbol]), so a file may be dropped in as the drawing
 * tool wrote it. The two things it has to get right are a square `viewBox` and a single color.
 */
object TranscriptIcons {

    /** Icons of the document itself: block headers, notices and icon buttons. */
    val CHROME: List<String> = listOf("user", "agent", "plan", "error", "copy", "up", "down")

    /** One icon per ACP `ToolKind`; the name of the file *is* the kind (see [TranscriptToolCall]). */
    val KINDS: List<String> = listOf(
        ToolKind.READ,
        ToolKind.EDIT,
        ToolKind.DELETE,
        ToolKind.MOVE,
        ToolKind.SEARCH,
        ToolKind.EXECUTE,
        ToolKind.THINK,
        ToolKind.FETCH,
        ToolKind.SWITCH_MODE,
        ToolKind.OTHER,
    )

    val NAMES: List<String> = CHROME + KINDS

    /** The sprite of every icon, hidden by `transcript.css` and referred to by `<use>`. */
    fun sprite(): String = buildString {
        append("""<svg class="icon-sprite" aria-hidden="true" focusable="false"><defs>""")
        NAMES.forEach { name -> append(symbol(name, read(name))) }
        append("</defs></svg>")
    }

    /**
     * The `<symbol>` an agent that brought its own drawing is shown with (see `AgentIcon`).
     *
     * It takes the place of the bundled `agent` icon, so every `<use>` of the document — the ones
     * already on screen included — follows it. Its colors are kept: a brand icon is recognized by
     * them, and unlike the bundled set it has no monochrome variant to fall back on.
     */
    fun agentSymbol(svg: String): String = symbol("agent", svg, recolor = false)

    /**
     * Turns the export [svg] into the `<symbol>` of [name].
     *
     * What is thrown away is everything a document does not want twenty times over: the prologue
     * and the notes of the drawing tool, the size (the style sheet decides it) and — the one that
     * would actually break something — **every `id`**. Illustrator writes `id="uuid-…"` on the
     * root and on whatever it grouped, and two files carrying ids in one document turn `<use>` into
     * a lottery. Internal references (a gradient, a clip path) are lost with them, which is fine
     * for a one color icon and the reason the rules ask for one.
     *
     * Colors are replaced by `currentColor` instead of being kept: the icon is painted in the color
     * the theme gave the text around it. `fill="none"` survives, so an outline stays an outline,
     * and the inherited `fill` of the `<symbol>` covers whatever the export left uncolored (a class
     * it painted with a `<style>` block, for instance, which is dropped as well).
     *
     * [recolor] turns that last part off for a drawing that is not ours ([agentSymbol]): what it
     * paints itself stays as it was drawn, and only the shapes without a color of their own follow
     * the text.
     */
    fun symbol(name: String, svg: String, recolor: Boolean = true): String {
        val cleaned = svg
            .replace(PROLOGUE, "")
            .replace(NOTES, "")
            .replace(STYLE, "")
        val root = ROOT.find(cleaned) ?: error("Not an svg icon: $name")
        val viewBox = VIEW_BOX.find(root.groupValues[1])?.groupValues?.get(1) ?: DEFAULT_VIEW_BOX
        val body = root.groupValues[2]
            .replace(DROPPED, "")
            .let { if (recolor) it.replace(COLOR) { match -> """${match.groupValues[1]}="currentColor"""" } else it }
            .trim()
        return """<symbol id="$PREFIX$name" viewBox="$viewBox" fill="currentColor">$body</symbol>"""
    }

    fun read(name: String): String =
        javaClass.getResourceAsStream("/icons/transcript/$name.svg")?.use { it.readBytes().decodeToString() }
            ?: error("Missing transcript icon: $name.svg")

    const val PREFIX = "ra-icon-"

    private const val DEFAULT_VIEW_BOX = "0 0 16 16"

    private val PROLOGUE = Regex("""<\?xml[\s\S]*?\?>|<!DOCTYPE[\s\S]*?>|<!--[\s\S]*?-->""")

    private val NOTES = Regex("""<(title|desc|metadata)\b[\s\S]*?</\1>""")

    private val STYLE = Regex("""<style\b[\s\S]*?</style>""")

    private val ROOT = Regex("""<svg\b([^>]*)>([\s\S]*)</svg>""")

    private val VIEW_BOX = Regex("""viewBox\s*=\s*"([^"]*)"""")

    /**
     * The `id`s that would collide across files, and the classes of a `<style>` block that is gone.
     *
     * Nothing else is touched: a `width` or an `x` inside the drawing belongs to a `rect`, not to
     * the size of the icon (that one sits on the root element, which is replaced anyway).
     */
    private val DROPPED = Regex("""\s+(id|class)\s*=\s*"[^"]*"""")

    /** A color literal of a shape; `none` is a statement about the shape, not a color. */
    private val COLOR = Regex("""\b(fill|stroke)\s*=\s*"(?!none")[^"]*"""")
}
