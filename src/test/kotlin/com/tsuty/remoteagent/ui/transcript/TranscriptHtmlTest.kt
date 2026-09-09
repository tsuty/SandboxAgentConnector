package com.tsuty.remoteagent.ui.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptHtmlTest {

    @Test
    fun `a gfm table keeps the markup the style sheet needs`() {
        val html = TranscriptHtml.render("| a | b |\n| - | - |\n| 1 | 2 |")

        assertTrue(html.contains("<table>"), html)
        assertTrue(html.contains("<th"), html)
        assertTrue(html.contains("<td"), html)
    }

    @Test
    fun `a code fence keeps its language class`() {
        val html = TranscriptHtml.render("```kotlin\nval a = 1\n```")

        assertTrue(html.contains("<pre>"), html)
        assertTrue(html.contains("language-kotlin"), html)
    }

    /**
     * `HtmlGenerator` passes inline HTML of the Markdown source straight through. The Swing HTML
     * kit ignored a `<script>`; Chromium runs it, so an agent answer (or a tool output quoted by
     * it) must never reach the document with one.
     */
    @Test
    fun `a script an agent wrote is dropped with its content`() {
        val html = TranscriptHtml.render("Before\n\n<script>alert(1)</script>\n\nAfter")

        assertFalse(html.contains("script", ignoreCase = true), html)
        assertFalse(html.contains("alert(1)"), html)
        assertTrue(html.contains("Before"), html)
        assertTrue(html.contains("After"), html)
    }

    @Test
    fun `event handlers, inline styles and javascript urls are dropped`() {
        val html = TranscriptHtml.sanitize(
            """<p onclick="steal()" style="position:fixed">
               <a href="javascript:alert(1)">x</a>
               <a href="https://example.com" title="ok">y</a></p>""",
        )

        assertFalse(html.contains("onclick"), html)
        assertFalse(html.contains("style="), html)
        assertFalse(html.contains("javascript:"), html)
        assertTrue(html.contains("""<a href="https://example.com" title="ok">"""), html)
    }

    /**
     * A path is not a link the document can follow: it is handed to the browser as a string, so
     * it lives under an internal `file:///jbcefbrowser/…` URL and a path resolves against that.
     * Following one replaced the whole transcript with the error page of Chromium.
     */
    @Test
    fun `a link of a path loses its href and keeps its text`() {
        val html = TranscriptHtml.render("See [ToDo](docs/ToDo.md) and [home](/Users/me/notes.md).")

        assertFalse(html.contains("href"), html)
        assertTrue(html.contains("ToDo"), html)
        assertTrue(html.contains("home"), html)
    }

    /** A fragment never leaves the document, so it stays the one relative link there is. */
    @Test
    fun `a fragment link is kept`() {
        assertEquals("""<a href="#section">x</a>""", TranscriptHtml.sanitize("""<a href="#section">x</a>"""))
    }

    @Test
    fun `an unknown tag loses its markup but keeps its text`() {
        val html = TranscriptHtml.sanitize("<marquee><b>text</b></marquee>")

        assertEquals("<b>text</b>", html)
    }

    @Test
    fun `an iframe is dropped with everything inside it`() {
        assertEquals("", TranscriptHtml.sanitize("<iframe src=\"http://evil\">fallback</iframe>"))
    }

    /** A `<` that is not a tag is text: an agent writing `a < b` must not lose the rest of it. */
    @Test
    fun `a stray angle bracket stays text`() {
        assertEquals("a &lt; b", TranscriptHtml.sanitize("a < b"))
    }

    @Test
    fun `a task list is lifted out of the block as choices`() {
        val body = TranscriptHtml.renderInteractive("Pick one:\n\n- [ ] Option A\n- [x] **Option B**\n\nThanks.")

        assertTrue(body.html.contains("""<div class="choices" data-choices="0"></div>"""), body.html)
        assertEquals(
            listOf(TranscriptChoice("Option A", false), TranscriptChoice("Option B", true)),
            body.choices.single().items,
        )
        // The prose around the list stays where the agent wrote it.
        assertTrue(body.html.indexOf("Pick one") < body.html.indexOf("data-choices"), body.html)
        assertTrue(body.html.indexOf("data-choices") < body.html.indexOf("Thanks"), body.html)
    }

    @Test
    fun `a block without a task list is rendered as one fragment`() {
        val body = TranscriptHtml.renderInteractive("Just **prose**.")

        assertTrue(body.choices.isEmpty())
        assertTrue(body.html.contains("<strong>prose</strong>"), body.html)
    }

    /**
     * The tokens of a fence are colored by the lexer of the IDE ([CodeHighlighter]); without an
     * IDE to ask, the fence keeps the escaped text it had instead of losing its content.
     */
    @Test
    fun `a code fence stays plain text when it cannot be colored`() {
        assertNull(CodeHighlighter.highlight("val a = 1", "kotlin"))

        val html = TranscriptHtml.render("```kotlin\nval a = \"<b>\"\n```")

        assertFalse(html.contains("hl-"), html)
        assertTrue(html.contains("&lt;b&gt;"), html)
    }

    /** A read only block (the plan) keeps its task list as the check boxes GFM draws. */
    @Test
    fun `a rendered task list keeps its check boxes disabled`() {
        val html = TranscriptHtml.render("- [x] done\n- [ ] open")

        assertTrue(html.contains("""type="checkbox""""), html)
        assertTrue(html.contains("disabled"), html)
    }
}
