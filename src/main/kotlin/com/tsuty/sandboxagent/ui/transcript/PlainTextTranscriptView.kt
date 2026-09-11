package com.tsuty.sandboxagent.ui.transcript

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tsuty.sandboxagent.ui.BackgroundPanel
import com.tsuty.sandboxagent.ui.BackgroundScrollPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants

/**
 * Read only fallback for a runtime without JCEF.
 *
 * `JBCefApp.isSupported()` is `false` when the IDE runs on a JetBrains Runtime built without the
 * bundled browser, when `ide.browser.jcef.enabled` was turned off in the registry, in headless
 * mode (which is where the tests live) and in some Remote Development setups. The Swing renderer
 * of the transcript is not kept alive for those: maintaining a second, worse looking UI would
 * cancel out the point of moving the presentation into HTML. What is left instead is the
 * conversation as plain text, plus a notice naming what to turn on.
 *
 * Answering a permission request is not possible here, which the notice says as well.
 */
class PlainTextTranscriptView : TranscriptView {

    private val text = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(8)
        font = UIUtil.getLabelFont()
    }

    private val panel = BackgroundPanel(BorderLayout()).apply {
        add(notice(), BorderLayout.NORTH)
        add(
            BackgroundScrollPane(text).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER,
        )
    }

    override val component: JComponent get() = panel

    private fun notice(): JComponent = JBLabel(
        "<html>The transcript needs the embedded browser (JCEF), which this runtime does not " +
            "offer.<br>Enable <code>ide.browser.jcef.enabled</code> in the registry, or start the " +
            "IDE on a JetBrains Runtime with JCEF.<br>Only plain text is shown below, and tool " +
            "call permissions cannot be answered.</html>",
    ).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(8, 8, 0, 8)
    }

    override fun post(events: List<TranscriptEvent>) {
        events.forEach { event ->
            when (event) {
                is ResetEvent -> text.text = ""
                is BlockEvent -> if (!event.streaming) line(plain(event.html))
                is StreamEvent -> text.append(event.text)
                is BlockHtmlEvent -> Unit
                is AttachmentsEvent -> line(event.chips.joinToString(", ") { it.label })
                is ToolCallEvent -> line(event.switchMode?.headline ?: event.anchor)
                is PermissionEvent -> line(event.headline)
                is PermissionResolvedEvent -> line(event.status)
                // A toast answers a copy button, and this view has none of them; the name and the
                // drawing of the agent belong to a block header this view does not draw either.
                is GroupEvent, is TurnEndEvent, is ThemeEvent, is AgentEvent, is ToastEvent -> Unit
            }
        }
    }

    override fun scrollToBottom() {
        text.caretPosition = text.document.length
    }

    private fun line(value: String) {
        if (value.isBlank()) return
        if (text.document.length > 0) text.append("\n\n")
        text.append(value.trim())
    }

    /** Everything but the words: tags go, entities are resolved, block ends become line breaks. */
    private fun plain(html: String): String = html
        .replace(BLOCK_END, "\n")
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(BLANK_LINES, "\n\n")
        .trim()

    private companion object {
        private val BLOCK_END = Regex("""</(p|div|li|tr|h[1-6]|pre|blockquote)>|<br\s*/?>""")
        private val TAG = Regex("""<[^>]*>""")
        private val BLANK_LINES = Regex("""\n{3,}""")
    }
}
