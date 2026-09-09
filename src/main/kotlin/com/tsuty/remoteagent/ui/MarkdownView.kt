package com.tsuty.remoteagent.ui

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Reads the Markdown the agent (and the user) writes.
 *
 * The parser is the one bundled with the IDE (`org.intellij.markdown`, shipped with the Markdown
 * plugin) with the GFM flavour, so the transcript understands the same documents the IDE Markdown
 * preview does — tables, task lists, strikethrough.
 *
 * Two things come out of a document: [toHtmlBody], the HTML the transcript renders (sanitized and
 * styled by `TranscriptHtml` / `transcript.css`), and [segments], which is what lifts the task
 * lists out of it — agents use them to offer choices, and a choice has to become a check box
 * instead of a rendered list.
 *
 * Nothing here draws anything: the transcript is rendered in the embedded browser (see
 * `docs/development/TRANSCRIPT_WEBVIEW.md`).
 */
object MarkdownView {

    /** One renderable piece of a Markdown document. */
    sealed interface Segment {
        /** Anything but a code block, kept as Markdown source. */
        data class Prose(val markdown: String) : Segment

        /** A fenced or indented code block, already stripped of its fences. */
        data class Code(val code: String, val language: String?) : Segment

        /**
         * A task list (`- [ ] …`), which agents use to offer choices. [markdown] keeps the
         * original source so blocks that must stay read only (plan, ...) can render it as prose.
         */
        data class Choices(val items: List<Choice>, val markdown: String) : Segment
    }

    /** One entry of a [Segment.Choices] list. */
    data class Choice(val label: String, val checked: Boolean)

    /** Splits [markdown] into prose, top level code blocks and task lists, in document order. */
    fun segments(markdown: String): List<Segment> {
        if (markdown.isBlank()) return emptyList()
        val tree = MarkdownParser(FLAVOUR).buildMarkdownTreeFromString(markdown)
        val segments = mutableListOf<Segment>()
        var proseStart = 0

        fun flushProse(end: Int) {
            val text = markdown.substring(proseStart, end)
            if (text.isNotBlank()) segments += Segment.Prose(text.trim('\n'))
        }

        tree.children.forEach { child ->
            when (child.type) {
                MarkdownElementTypes.CODE_FENCE -> {
                    flushProse(child.startOffset)
                    segments += Segment.Code(fenceBody(child, markdown), fenceLanguage(child, markdown))
                    proseStart = child.endOffset
                }

                MarkdownElementTypes.CODE_BLOCK -> {
                    flushProse(child.startOffset)
                    segments += Segment.Code(indentedBody(child, markdown), null)
                    proseStart = child.endOffset
                }

                MarkdownElementTypes.UNORDERED_LIST -> {
                    val source = child.getTextInNode(markdown).toString().trim('\n')
                    val items = taskListItems(source) ?: return@forEach
                    flushProse(child.startOffset)
                    segments += Segment.Choices(items, source)
                    proseStart = child.endOffset
                }

                else -> Unit
            }
        }
        flushProse(markdown.length)
        return segments
    }

    /**
     * Parses [listMarkdown] as a flat GFM task list; `null` when at least one line is not a task
     * item, which keeps ordinary bullet lists (and wrapped / nested items) rendered as prose.
     */
    fun taskListItems(listMarkdown: String): List<Choice>? {
        val items = listMarkdown.lines()
            .filter { it.isNotBlank() }
            .map { line -> TASK_ITEM.matchEntire(line) ?: return null }
            .map { match ->
                Choice(
                    label = match.groupValues[2].trim(),
                    checked = !match.groupValues[1].equals(" ", ignoreCase = true),
                )
            }
        return items.takeIf { it.isNotEmpty() && it.none { item -> item.label.isEmpty() } }
    }

    /** Rebuilds the task list of [items] with the check marks of the current selection. */
    fun choicesMarkdown(items: List<Choice>): String =
        items.joinToString("\n") { "- [${if (it.checked) "x" else " "}] ${it.label}" }

    /** Converts [markdown] to an HTML body; the caller sanitizes it (see `TranscriptHtml`). */
    fun toHtmlBody(markdown: String): String {
        val tree = MarkdownParser(FLAVOUR).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, FLAVOUR).generateHtml()
    }

    private fun fenceBody(node: ASTNode, source: String): String = buildString {
        var started = false
        node.children.forEach { child ->
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                    append(child.getTextInNode(source))
                    started = true
                }

                MarkdownTokenTypes.EOL -> if (started) append('\n')
                else -> Unit
            }
        }
    }.trim('\n')

    private fun fenceLanguage(node: ASTNode, source: String): String? = node.children
        .firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.getTextInNode(source)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun indentedBody(node: ASTNode, source: String): String =
        node.getTextInNode(source).toString()
            .lines()
            .joinToString("\n") { it.removePrefix("    ") }
            .trim('\n')

    private val FLAVOUR = GFMFlavourDescriptor()

    private val TASK_ITEM = Regex("""\s*[-*+]\s+\[([ xX])]\s*(.*)""")
}
