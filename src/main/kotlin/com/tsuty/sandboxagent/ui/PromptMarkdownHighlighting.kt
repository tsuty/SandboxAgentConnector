package com.tsuty.sandboxagent.ui

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * The inline Markdown styles of the prompt editor, computed without the daemon.
 *
 * The Markdown plugin paints these through `MarkdownHighlightingAnnotator`, i.e. only when the
 * daemon code analyzer runs on the editor, and it does not reliably run on the prompt field (it
 * comes back after the focus leaves and returns). Its lexer still paints the fences, `#`, `>`,
 * list markers, tables and auto links on every keystroke, so only what the annotator adds on
 * top of that is computed here: code spans (and the `$` of inline math), emphasis,
 * strikethrough, header text, links and block quote bodies.
 *
 * Nothing here touches the editor: [spans] is a pure function of the text, and the prompt field
 * maps each [Style] to the color scheme key of the Markdown plugin (see `PromptInputPanel`).
 */
object PromptMarkdownHighlighting {

    /** What a [Span] looks like; one entry per `MarkdownHighlighterColors` key it maps to. */
    enum class Style {
        CODE_SPAN, CODE_SPAN_MARKER,
        BOLD, BOLD_MARKER,
        ITALIC, ITALIC_MARKER,
        STRIKE_THROUGH,
        HEADER_LEVEL_1, HEADER_LEVEL_2, HEADER_LEVEL_3, HEADER_LEVEL_4, HEADER_LEVEL_5, HEADER_LEVEL_6,
        EXPLICIT_LINK, REFERENCE_LINK, IMAGE, LINK_TEXT, LINK_LABEL, LINK_DESTINATION, LINK_TITLE,
        BLOCK_QUOTE,
    }

    /**
     * One styled range. [depth] is how deep the element sits among the styled ones (markers one
     * deeper than their element), so the editor can stack inner styles over outer ones.
     */
    data class Span(val start: Int, val end: Int, val style: Style, val depth: Int)

    /** Every styled range of [text], outer elements before the ones they contain. */
    fun spans(text: String): List<Span> {
        if (text.isBlank()) return emptyList()
        val tree = MarkdownParser(FLAVOUR).buildMarkdownTreeFromString(text)
        val spans = mutableListOf<Span>()
        collect(tree, 0, spans)
        return spans
    }

    private fun collect(node: ASTNode, depth: Int, out: MutableList<Span>) {
        val style = styleOf(node.type)
        // Inline math (`$x$`) has no style of its own, only its `$` markers, like the annotator.
        val markerStyle = markerStyleOf(node.type)
        var childDepth = depth
        if ((style != null || markerStyle != null) && node.endOffset > node.startOffset) {
            style?.let { out += Span(node.startOffset, node.endOffset, it, depth) }
            markerStyle?.let { markers(node, it, depth + 1, out) }
            childDepth = depth + 1
        }
        node.children.forEach { collect(it, childDepth, out) }
    }

    /** The delimiters at both ends of a code span or an emphasis: `` ` ``, `**`, `_`, ... */
    private fun markers(node: ASTNode, style: Style, depth: Int, out: MutableList<Span>) {
        val markerType = markerTokenOf(node.type) ?: return
        val children = node.children
        val leading = children.takeWhile { it.type == markerType }
        val trailing = children.drop(leading.size).takeLastWhile { it.type == markerType }
        listOf(leading, trailing).filter { it.isNotEmpty() }.forEach { run ->
            out += Span(run.first().startOffset, run.last().endOffset, style, depth)
        }
    }

    private fun styleOf(type: IElementType): Style? = when (type) {
        MarkdownElementTypes.CODE_SPAN -> Style.CODE_SPAN
        MarkdownElementTypes.STRONG -> Style.BOLD
        MarkdownElementTypes.EMPH -> Style.ITALIC
        GFMElementTypes.STRIKETHROUGH -> Style.STRIKE_THROUGH
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> Style.HEADER_LEVEL_1
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> Style.HEADER_LEVEL_2
        MarkdownElementTypes.ATX_3 -> Style.HEADER_LEVEL_3
        MarkdownElementTypes.ATX_4 -> Style.HEADER_LEVEL_4
        MarkdownElementTypes.ATX_5 -> Style.HEADER_LEVEL_5
        MarkdownElementTypes.ATX_6 -> Style.HEADER_LEVEL_6
        MarkdownElementTypes.INLINE_LINK -> Style.EXPLICIT_LINK
        MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> Style.REFERENCE_LINK
        MarkdownElementTypes.IMAGE -> Style.IMAGE
        MarkdownElementTypes.LINK_TEXT -> Style.LINK_TEXT
        MarkdownElementTypes.LINK_LABEL -> Style.LINK_LABEL
        MarkdownElementTypes.LINK_DESTINATION -> Style.LINK_DESTINATION
        MarkdownElementTypes.LINK_TITLE -> Style.LINK_TITLE
        MarkdownElementTypes.BLOCK_QUOTE -> Style.BLOCK_QUOTE
        else -> null
    }

    private fun markerStyleOf(type: IElementType): Style? = when (type) {
        MarkdownElementTypes.CODE_SPAN, GFMElementTypes.INLINE_MATH -> Style.CODE_SPAN_MARKER
        MarkdownElementTypes.STRONG -> Style.BOLD_MARKER
        MarkdownElementTypes.EMPH -> Style.ITALIC_MARKER
        else -> null
    }

    private fun markerTokenOf(type: IElementType): IElementType? = when (type) {
        MarkdownElementTypes.CODE_SPAN -> MarkdownTokenTypes.BACKTICK
        GFMElementTypes.INLINE_MATH -> GFMTokenTypes.DOLLAR
        MarkdownElementTypes.STRONG, MarkdownElementTypes.EMPH -> MarkdownTokenTypes.EMPH
        else -> null
    }

    private val FLAVOUR = GFMFlavourDescriptor()
}
