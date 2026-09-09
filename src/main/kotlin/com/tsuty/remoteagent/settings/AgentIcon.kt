package com.tsuty.remoteagent.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests

/**
 * The drawing an ACP registry entry ships for its agent (see [CommandOption.iconUrl]).
 *
 * The file is downloaded once, when the agent is picked in the settings, and the markup itself is
 * persisted next to the command ([RemoteAgentSettingsState.agentIconSvg]): the plugin draws the
 * agent on every start of the IDE, and reaching the CDN for it would make the toolbar depend on
 * the network — an agent that is only ever started inside a VM does not need one.
 *
 * What comes back is a file from the internet drawn in two places that both take markup (the Swing
 * icon of the toolbar and the sprite of the transcript document), so it is cut down to the shapes
 * before it is stored: [sanitize] keeps geometry and colors and drops everything that could act —
 * scripts, event handlers, external references and anything else that would load or run.
 */
object AgentIcon {
    private val log = Logger.getInstance(AgentIcon::class.java)

    /** An icon is a few kilobytes of paths; anything past this is not one, and is not stored. */
    const val MAX_LENGTH: Int = 64 * 1024

    /** Downloads [url] and returns the sanitized markup, or `null` when it yields no usable icon. */
    fun download(url: String): String? = try {
        sanitize(HttpRequests.request(url).productNameAsUserAgent().readString())
    } catch (e: Exception) {
        log.warn("Cannot read the agent icon from $url", e)
        null
    }

    /**
     * The `<svg>` element of [content] with everything that is not a drawing taken out.
     *
     * The result is still a whole document (`<svg …>…</svg>`), because that is what both consumers
     * need: the Swing side hands it to the SVG loader of the platform, and the transcript turns it
     * into a `<symbol>` of its sprite. Returns `null` when nothing that looks like an icon is left.
     */
    fun sanitize(content: String): String? {
        if (content.length > MAX_LENGTH) return null
        val cleaned = content
            .replace(COMMENTS, "")
            .replace(PROLOGUE, "")
            .replace(ACTIVE, "")
            .replace(HANDLERS, "")
            .replace(EXTERNAL, "")
        val root = ROOT.find(cleaned) ?: return null
        val svg = root.value.trim()
        return svg.takeIf { it.length <= MAX_LENGTH && root.groupValues[2].isNotBlank() }
    }

    private val COMMENTS = Regex("""<!--[\s\S]*?-->""")

    private val PROLOGUE = Regex("""<\?xml[\s\S]*?\?>|<!DOCTYPE[\s\S]*?>""")

    /** Elements that do something instead of drawing something. */
    private val ACTIVE = Regex(
        """<(script|foreignObject|animate\w*|set|handler)\b[\s\S]*?</\1>|<(script|foreignObject|animate\w*|set|handler)\b[^>]*/>""",
        RegexOption.IGNORE_CASE,
    )

    /** `onload`, `onclick`, … — an attribute whose value is code. */
    private val HANDLERS = Regex("""\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)

    /**
     * Anything pointing outside the file: a remote image, a `<use>` of another document, a
     * `javascript:` link. A one file drawing has no use for them, and the transcript document
     * refuses to load them anyway (its CSP is `default-src 'none'`).
     */
    private val EXTERNAL = Regex(
        """\s+(xlink:href|href|src)\s*=\s*("(?!#)[^"]*"|'(?!#)[^']*')""",
        RegexOption.IGNORE_CASE,
    )

    private val ROOT = Regex("""<svg\b([^>]*)>([\s\S]*)</svg>""", RegexOption.IGNORE_CASE)
}
