package com.tsuty.remoteagent.ui

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * Live `agent_thought_chunk` strip pinned right above the prompt editor.
 *
 * The chain of thought is not part of the transcript while it streams: it is written into this
 * small, scrolling area which only shows the last few lines and disappears when the turn ends
 * (see [RemoteAgentMainPanel]), so the transcript itself never fills up with thinking.
 */
class ThinkingStatusPanel : BackgroundPanel(BorderLayout(0, JBUI.scale(2))) {

    private val header = JBLabel("Thinking", AnimatedIcon.Default.INSTANCE, JBLabel.LEFT).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
        iconTextGap = JBUI.scale(4)
    }

    private val area = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty()
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val scrollPane = BackgroundScrollPane(area).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        // Only the tail of the thought is on screen; the rest stays reachable by scrolling.
        preferredSize = Dimension(0, area.getFontMetrics(area.font).height * VISIBLE_LINES)
    }

    /** Everything the agent has been thinking during the running turn. */
    val thought: String
        get() = area.text

    init {
        border = JBUI.Borders.empty(2, RemoteAgentLayout.CONTENT_MARGIN)
        isVisible = false
        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /** Appends a streamed chunk and keeps the tail of the thought in view. */
    fun append(text: String) {
        if (text.isEmpty()) return
        area.append(text)
        isVisible = true
        revalidate()
        repaint()
        SwingUtilities.invokeLater { area.caretPosition = area.document.length }
    }

    /** Drops the collected thought and hides the strip again. */
    fun clear() {
        area.text = ""
        isVisible = false
        revalidate()
        repaint()
    }

    private companion object {
        const val VISIBLE_LINES = 3
    }
}
