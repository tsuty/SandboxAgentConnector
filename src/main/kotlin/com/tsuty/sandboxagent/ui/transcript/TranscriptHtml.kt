package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.ui.MarkdownView

/**
 * Markdown of the agent (and of the user) turned into the HTML the transcript document shows.
 *
 * Everything here is a pure function: the transcript is rendered in a browser now, so the HTML is
 * the one artifact worth testing, and it is the same artifact a different host would use. The one
 * step that asks the IDE something is the coloring of a code fence ([CodeHighlighter]), which is
 * skipped — leaving the fence plain — wherever there is no IDE to ask.
 *
 * The parser is the one bundled with the IDE (`org.intellij.markdown`, GFM — see [MarkdownView]),
 * but its output is *not* trusted: `HtmlGenerator` passes inline HTML of the Markdown source
 * straight through, and under Chromium a `<script>` an agent (or a tool output quoted by it) wrote
 * would actually run. Every fragment therefore goes through [sanitize] before it leaves this file.
 */
object TranscriptHtml {

    /** A rendered Markdown block: its HTML plus the task lists lifted out of it as choices. */
    data class Body(val html: String, val choices: List<TranscriptChoices> = emptyList())

    /** Renders [markdown] as one read only fragment; task lists stay the check boxes GFM draws. */
    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        val html = sanitize(MarkdownView.toHtmlBody(markdown).removeSurrounding("<body>", "</body>"))
        return highlightFences(html)
    }

    /**
     * Colors the code of every fence that named a language.
     *
     * This runs after [sanitize] on purpose: the spans are put in by us, so they must not be
     * filtered again, while the code inside a fence is escaped text either way. A fence the
     * highlighter does not know (and every fence, when there is no IDE around) keeps that text.
     */
    private fun highlightFences(html: String): String = FENCE.replace(html) { match ->
        val language = match.groupValues[1]
        val spans = CodeHighlighter.highlight(unescape(match.groupValues[2]), language)
            ?: return@replace match.value
        """<pre><code class="language-$language">$spans</code></pre>"""
    }

    /**
     * Renders [markdown] with its task lists lifted out: agents offer choices as `- [ ] …`, which
     * the transcript answers with real check boxes and a `Send` action.
     *
     * Every lifted list leaves a `data-choices` placeholder behind, so the answer stays where the
     * agent wrote it instead of being collected at the end of the block.
     */
    fun renderInteractive(markdown: String): Body {
        if (markdown.isBlank()) return Body("")
        val segments = MarkdownView.segments(markdown)
        if (segments.none { it is MarkdownView.Segment.Choices }) return Body(render(markdown))
        val choices = mutableListOf<TranscriptChoices>()
        val html = buildString {
            segments.forEach { segment ->
                when (segment) {
                    is MarkdownView.Segment.Prose -> append(render(segment.markdown))
                    is MarkdownView.Segment.Code -> append(render(fence(segment)))
                    is MarkdownView.Segment.Choices -> {
                        val slot = choices.size
                        choices += TranscriptChoices(
                            slot = slot,
                            items = segment.items.map { TranscriptChoice(plainText(it.label), it.checked) },
                        )
                        append("""<div class="choices" data-choices="$slot"></div>""")
                    }
                }
            }
        }
        return Body(html, choices)
    }

    /** Renders one line of plain text (a notice, the reason a turn ended) as an escaped fragment. */
    fun text(text: String): String = escape(text)

    /** Rebuilds the fence of a code segment, so it is rendered as the code block it was. */
    private fun fence(segment: MarkdownView.Segment.Code): String =
        "```" + segment.language.orEmpty() + "\n" + segment.code + "\n```"

    /** Drops the inline emphasis markers, so a choice reads the same as its rendered prose. */
    fun plainText(markdown: String): String = markdown.replace(EMPHASIS, "").trim()

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    /**
     * Drops everything an untrusted document must not carry: the tags that execute or load
     * something ([DROP_WITH_CONTENT]), every tag that is not on the allow list (its text is kept,
     * only the markup goes), every event handler and inline `style`, and every URL that is not
     * plainly a link.
     *
     * The document also runs under a `default-src 'none'` CSP, so this is the inner of two
     * barriers, not the only one.
     */
    fun sanitize(html: String): String {
        val out = StringBuilder(html.length)
        var index = 0
        // Name of the element whose content is being dropped, `null` while output is kept.
        var dropping: String? = null
        while (index < html.length) {
            val open = html.indexOf('<', index)
            if (open < 0) {
                if (dropping == null) out.append(html, index, html.length)
                break
            }
            if (dropping == null) out.append(html, index, open)
            if (html.startsWith("<!--", open)) {
                val end = html.indexOf("-->", open)
                index = if (end < 0) html.length else end + 3
                continue
            }
            val close = tagEnd(html, open)
            if (close < 0) {
                // A stray `<` that never closes is text, not markup.
                if (dropping == null) out.append("&lt;")
                index = open + 1
                continue
            }
            val tag = parseTag(html.substring(open + 1, close))
            index = close + 1
            if (tag == null) continue
            if (dropping != null) {
                if (tag.closing && tag.name == dropping) dropping = null
                continue
            }
            if (tag.name in DROP_WITH_CONTENT) {
                if (!tag.closing && !tag.selfClosing) dropping = tag.name
                continue
            }
            if (tag.name !in ALLOWED_TAGS) continue
            out.append(render(tag))
        }
        return out.toString()
    }

    private fun render(tag: Tag): String {
        if (tag.closing) return "</${tag.name}>"
        val attributes = tag.attributes
            .filter { (name, value) -> keepAttribute(tag.name, name, value) }
            .map { (name, value) -> """ $name="${escape(value)}"""" }
            .joinToString("")
        return "<${tag.name}$attributes>"
    }

    private fun keepAttribute(tag: String, name: String, value: String): Boolean {
        if (name.startsWith("on")) return false
        if (name == "style") return false
        return when (name) {
            "href" -> tag == "a" && isSafeUrl(value)
            "src" -> tag == "img" && isSafeUrl(value)
            in GLOBAL_ATTRIBUTES -> true
            else -> ALLOWED_ATTRIBUTES[tag]?.contains(name) == true
        }
    }

    /**
     * `true` for a URL that only names something outside the document: an absolute `http(s)` /
     * `mailto` link, a `data:` image, or a fragment of this very page. `javascript:` and every
     * other scheme are dropped.
     *
     * A URL without a scheme — a relative or an absolute path — is dropped as well, which is the
     * one case that is not about safety. The document is handed to the browser as a string, so it
     * lives under an internal URL of its own (`file:///jbcefbrowser/…`): a path resolves against
     * *that* instead of the project, and following it replaced the whole transcript with the
     * error page of Chromium. A path an agent wrote therefore stays the text it is; the files of
     * the project are reached through the tool call rows, which ask the host to open them.
     */
    private fun isSafeUrl(url: String): Boolean {
        val trimmed = url.trim().replace("\u0000", "")
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("#")) return true
        val scheme = SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase() ?: return false
        return scheme in SAFE_SCHEMES
    }

    /** Index of the `>` closing the tag started at [open], skipping the quoted attribute values. */
    private fun tagEnd(html: String, open: Int): Int {
        var index = open + 1
        var quote = ' '
        while (index < html.length) {
            val char = html[index]
            when {
                quote != ' ' -> if (char == quote) quote = ' '
                char == '"' || char == '\'' -> quote = char
                char == '>' -> return index
            }
            index++
        }
        return -1
    }

    private data class Tag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val attributes: List<Pair<String, String>>,
    )

    /** `null` for something that is not a tag at all (`< 3`, a bare `</>`, a processing instruction). */
    private fun parseTag(source: String): Tag? {
        var body = source.trim()
        if (body.isEmpty() || body.startsWith("!") || body.startsWith("?")) return null
        val closing = body.startsWith("/")
        if (closing) body = body.drop(1).trim()
        val selfClosing = body.endsWith("/")
        if (selfClosing) body = body.dropLast(1).trim()
        val name = NAME.find(body)?.value?.lowercase() ?: return null
        val attributes = if (closing) emptyList() else parseAttributes(body.substring(name.length))
        return Tag(name, closing, selfClosing, attributes)
    }

    private fun parseAttributes(source: String): List<Pair<String, String>> =
        ATTRIBUTE.findAll(source).map { match ->
            val name = match.groupValues[1].lowercase()
            // Double quoted, single quoted, unquoted; a bare attribute stands for its own name.
            val value = match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: name
            name to unescape(value)
        }.toList()

    /** Attribute values are re-escaped on output, so the entities of the input are resolved first. */
    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private val EMPHASIS = Regex("""\*\*|__|`|\*|(?<!\w)_|_(?!\w)""")

    /** A fenced code block as `HtmlGenerator` writes it, with the language class GFM puts on it. */
    private val FENCE = Regex("""<pre><code class="language-([^"]*)">([\s\S]*?)</code></pre>""")

    private val NAME = Regex("""^[A-Za-z][A-Za-z0-9]*""")

    private val ATTRIBUTE = Regex(
        """([A-Za-z_:][-A-Za-z0-9_:.]*)\s*(?:=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?""",
    )

    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")

    private val SAFE_SCHEMES = setOf("http", "https", "mailto", "data")

    /** Tags whose content is markup for something else and is dropped along with them. */
    private val DROP_WITH_CONTENT = setOf(
        "script", "style", "iframe", "frame", "frameset", "object", "embed", "applet",
        "template", "textarea", "noscript", "svg", "math", "head", "title", "form", "base",
    )

    private val ALLOWED_TAGS = setOf(
        "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
        "em", "i", "strong", "b", "u", "s", "del", "ins", "mark", "small", "sup", "sub",
        "code", "pre", "kbd", "samp", "var", "blockquote", "q", "cite",
        "ul", "ol", "li", "dl", "dt", "dd",
        "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col",
        "a", "span", "div", "img", "input", "abbr", "details", "summary", "figure", "figcaption",
    )

    private val GLOBAL_ATTRIBUTES = setOf("class", "title", "lang", "dir")

    private val ALLOWED_ATTRIBUTES = mapOf(
        "img" to setOf("alt", "width", "height"),
        // The GFM task lists of a read only block: rendered, but never clickable.
        "input" to setOf("type", "checked", "disabled"),
        "th" to setOf("align", "colspan", "rowspan", "scope"),
        "td" to setOf("align", "colspan", "rowspan"),
        "col" to setOf("span", "align"),
        "colgroup" to setOf("span", "align"),
        "ol" to setOf("start", "reversed", "type"),
        "details" to setOf("open"),
        "abbr" to setOf("title"),
        "code" to setOf("data-lang"),
    )
}
