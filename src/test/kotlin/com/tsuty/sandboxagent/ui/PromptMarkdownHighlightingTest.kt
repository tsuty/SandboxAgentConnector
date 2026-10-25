package com.tsuty.sandboxagent.ui

import com.tsuty.sandboxagent.ui.PromptMarkdownHighlighting.Style
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptMarkdownHighlightingTest {

    /** The styled ranges of [text] as `style:text` pairs, in the order [PromptMarkdownHighlighting.spans] returns them. */
    private fun styled(text: String): List<String> =
        PromptMarkdownHighlighting.spans(text).map { "${it.style}:${text.substring(it.start, it.end)}" }

    @Test
    fun `a code span and its backticks are styled`() {
        assertEquals(
            listOf("CODE_SPAN:`aa`", "CODE_SPAN_MARKER:`", "CODE_SPAN_MARKER:`"),
            styled("コードが `aa` 効いていない"),
        )
    }

    @Test
    fun `a double backtick code span keeps the inner backtick out of its markers`() {
        assertEquals(
            listOf("CODE_SPAN:``co`de``", "CODE_SPAN_MARKER:``", "CODE_SPAN_MARKER:``"),
            styled("``co`de``"),
        )
    }

    @Test
    fun `bold italic and strikethrough are styled with their markers`() {
        assertEquals(
            listOf(
                "BOLD:**b**", "BOLD_MARKER:**", "BOLD_MARKER:**",
                "ITALIC:_i_", "ITALIC_MARKER:_", "ITALIC_MARKER:_",
                "STRIKE_THROUGH:~~s~~",
            ),
            styled("**b** _i_ ~~s~~"),
        )
    }

    @Test
    fun `inner styles sit deeper than the ones containing them`() {
        val spans = PromptMarkdownHighlighting.spans("**a `c` b**")
        val bold = spans.single { it.style == Style.BOLD }
        val code = spans.single { it.style == Style.CODE_SPAN }
        assertTrue(code.depth > bold.depth)
        assertTrue(spans.filter { it.style == Style.BOLD_MARKER }.all { it.depth > bold.depth })
    }

    @Test
    fun `header text is styled by level`() {
        assertEquals(
            listOf("HEADER_LEVEL_1:# One", "HEADER_LEVEL_2:## Two", "HEADER_LEVEL_1:Setext\n======"),
            styled("# One\n## Two\n\nSetext\n======"),
        )
    }

    @Test
    fun `links images and reference links are styled with their parts`() {
        val styles = styled("[t](http://x.y \"ti\") ![i](a.png) [r][l]")
        assertTrue("EXPLICIT_LINK:[t](http://x.y \"ti\")" in styles, styles.toString())
        assertTrue("LINK_TEXT:[t]" in styles, styles.toString())
        assertTrue("LINK_DESTINATION:http://x.y" in styles, styles.toString())
        assertTrue("LINK_TITLE:\"ti\"" in styles, styles.toString())
        assertTrue("IMAGE:![i](a.png)" in styles, styles.toString())
        assertTrue("REFERENCE_LINK:[r][l]" in styles, styles.toString())
        assertTrue("LINK_LABEL:[l]" in styles, styles.toString())
    }

    @Test
    fun `a block quote body is styled`() {
        assertEquals(listOf("BLOCK_QUOTE:> quoted"), styled("> quoted"))
    }

    @Test
    fun `fenced code is left to the lexer and the injected language`() {
        assertEquals(emptyList<String>(), styled("```json\n{\"a\": \"`x` **y**\"}\n```"))
    }

    @Test
    fun `plain text has no styles`() {
        assertEquals(emptyList<String>(), styled("just text"))
        assertEquals(emptyList<String>(), styled(""))
    }
}
