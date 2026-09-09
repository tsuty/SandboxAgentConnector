package com.tsuty.remoteagent.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownViewTest {

    @Test
    fun `text without code is a single prose segment`() {
        val segments = MarkdownView.segments("# Title\n\nSome **bold** text.")
        assertEquals(1, segments.size)
        assertEquals(MarkdownView.Segment.Prose("# Title\n\nSome **bold** text."), segments.first())
    }

    @Test
    fun `a fenced block is split off with its language`() {
        val segments = MarkdownView.segments(
            """
                Before

                ```kotlin
                fun main() {
                    println("hi")
                }
                ```

                After
            """.trimIndent(),
        )

        assertEquals(3, segments.size)
        assertEquals(MarkdownView.Segment.Prose("Before"), segments[0])
        assertEquals(
            MarkdownView.Segment.Code("fun main() {\n    println(\"hi\")\n}", "kotlin"),
            segments[1],
        )
        assertEquals(MarkdownView.Segment.Prose("After"), segments[2])
    }

    @Test
    fun `a fence without a language keeps its body`() {
        val segments = MarkdownView.segments("```\nls -la\n```")
        assertEquals(listOf(MarkdownView.Segment.Code("ls -la", null)), segments)
    }

    @Test
    fun `blank text renders nothing`() {
        assertTrue(MarkdownView.segments("   \n  ").isEmpty())
    }

    @Test
    fun `a task list becomes a choices segment`() {
        val segments = MarkdownView.segments("Select an option:\n\n- [ ] Option A\n- [x] Option B\n\nPlease choose.")

        assertEquals(3, segments.size)
        assertEquals(
            MarkdownView.Segment.Choices(
                listOf(MarkdownView.Choice("Option A", false), MarkdownView.Choice("Option B", true)),
                "- [ ] Option A\n- [x] Option B",
            ),
            segments[1],
        )
    }

    @Test
    fun `a plain bullet list stays prose`() {
        val segments = MarkdownView.segments("- first\n- second")
        assertEquals(listOf(MarkdownView.Segment.Prose("- first\n- second")), segments)
    }

    @Test
    fun `a partially checkable list stays prose`() {
        val segments = MarkdownView.segments("- [ ] first\n- second")
        assertEquals(listOf(MarkdownView.Segment.Prose("- [ ] first\n- second")), segments)
    }

    @Test
    fun `the selection is rendered back as a task list`() {
        val markdown = MarkdownView.choicesMarkdown(
            listOf(MarkdownView.Choice("Option A", true), MarkdownView.Choice("Option B", false)),
        )
        assertEquals("- [x] Option A\n- [ ] Option B", markdown)
    }

    @Test
    fun `prose is converted to html`() {
        val html = MarkdownView.toHtmlBody("A [link](https://example.com) and `code`.")
        assertTrue(html.contains("<a href=\"https://example.com\">link</a>"), html)
        assertTrue(html.contains("<code>code</code>"), html)
    }

    @Test
    fun `a gfm table is converted to html`() {
        val html = MarkdownView.toHtmlBody("| a | b |\n| - | - |\n| 1 | 2 |")
        assertTrue(html.contains("<table>"), html)
        assertTrue(html.contains("<th"), html)
    }
}
