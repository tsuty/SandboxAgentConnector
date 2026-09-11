package com.tsuty.sandboxagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Chip of one attached file / selection shown in the strip above the prompt editor (see
 * [ContextChipsPanel]).
 *
 * The `You` block of the transcript shows the same information, but it is rendered in the
 * embedded browser now (`transcript.css`, `.chip`), so the two presentations only share the rules,
 * not the components.
 */
object PromptAttachmentChips {

    /**
     * When [onClick] is given, the chip follows the attachment on a left click; when [onRemove]
     * is, a `✖` button drops the attachment from the next prompt.
     *
     * [tooltip] is the full path of the attachment and nothing else: the chip is small and the
     * label only shows the file name, so the one thing it hides is where that file is.
     */
    fun chip(
        label: String,
        icon: Icon,
        tooltip: String?,
        onClick: (() -> Unit)? = null,
        onRemove: (() -> Unit)? = null,
    ): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(4), 0))
        panel.isOpaque = false
        panel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(1, 5),
        )
        val text = JBLabel(label, icon, JBLabel.LEFT).apply {
            font = JBUI.Fonts.smallFont()
            toolTipText = tooltip
        }
        panel.add(text, BorderLayout.CENTER)
        if (onClick != null) {
            // Both the label and the panel around it, because the label is what the pointer is
            // actually over for most of the chip and it swallows the events of its container.
            listOf<JComponent>(panel, text).forEach { it.onLeftClick(onClick) }
        }
        if (onRemove != null) {
            panel.add(
                InplaceButton("Remove from context", AllIcons.Actions.Close) { onRemove() },
                BorderLayout.EAST,
            )
        }
        return panel
    }

    private fun JComponent.onLeftClick(action: () -> Unit) {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1) action()
            }
        })
    }

    /**
     * The icon the file itself has in the project view, so a chip is recognizable at a glance. A
     * file that is gone (a restored transcript may well outlive it) falls back to the plain text
     * icon rather than dropping the chip.
     */
    fun iconFor(path: String, selection: Boolean): Icon {
        if (selection) return AllIcons.Actions.Highlighting
        val file = LocalFileSystem.getInstance().findFileByPath(path)
        return when {
            file == null -> AllIcons.FileTypes.Text
            file.isDirectory -> AllIcons.Nodes.Folder
            else -> file.fileType.icon
        }
    }
}
