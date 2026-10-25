package com.tsuty.sandboxagent.ui

import com.intellij.openapi.ui.Divider
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Graphics
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Vertical splitter between the transcript and the prompt area whose divider can be found
 * without stumbling on it.
 *
 * The platform divider paints nothing but the panel background, so the only hint that the prompt
 * area can be resized is the cursor changing over a 7px strip. This one draws a faint hairline
 * across the whole width, thickens it to 2px in the full accent color while the mouse is over it
 * or dragging it, and puts the divider back at [defaultProportion] on a double click.
 */
class PromptSplitter(
    private val defaultProportion: Float,
) : JBSplitter(true, PROPORTION_KEY, defaultProportion) {

    init {
        SandboxAgentColors.paintPanelBackground(this)
    }

    /** Puts the divider back where a fresh tool window has it; the new value is persisted too. */
    fun resetProportion() {
        proportion = defaultProportion
    }

    // Called from the Splitter constructor, before the properties of this class are initialized:
    // the divider must not read any of them until it is actually used.
    override fun createDivider(): Divider = GripDivider()

    private inner class GripDivider : DividerImpl() {

        private var hovered = false
        private var dragging = false

        init {
            toolTipText = "Drag to resize, double-click to reset"
        }

        override fun processMouseEvent(e: MouseEvent) {
            super.processMouseEvent(e)
            when (e.id) {
                MouseEvent.MOUSE_ENTERED -> setActive(hovered = true)
                MouseEvent.MOUSE_EXITED -> setActive(hovered = false)
                MouseEvent.MOUSE_PRESSED -> if (SwingUtilities.isLeftMouseButton(e)) setActive(dragging = true)
                MouseEvent.MOUSE_RELEASED -> if (SwingUtilities.isLeftMouseButton(e)) setActive(dragging = false)
                MouseEvent.MOUSE_CLICKED ->
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) resetProportion()
            }
        }

        private fun setActive(hovered: Boolean = this.hovered, dragging: Boolean = this.dragging) {
            if (hovered == this.hovered && dragging == this.dragging) return
            this.hovered = hovered
            this.dragging = dragging
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            g.color = SandboxAgentColors.PANEL_BACKGROUND
            g.fillRect(0, 0, width, height)

            val active = hovered || dragging
            val thickness = JBUIScale.scale(if (active) LINE_ACTIVE_THICKNESS else LINE_IDLE_THICKNESS)
            g.color = if (active) LINE_ACTIVE else LINE_IDLE
            g.fillRect(0, (height - thickness) / 2, width, thickness)
        }
    }

    private companion object {
        const val PROPORTION_KEY = "SandboxAgentConnector.splitter"

        /** Thickness, in unscaled pixels, of the line across the divider. */
        const val LINE_IDLE_THICKNESS = 1
        const val LINE_ACTIVE_THICKNESS = 2

        val LINE_ACTIVE: Color = JBColor.namedColor("Component.focusedBorderColor", JBColor(0x3574F0, 0x3574F0))
        val LINE_IDLE: Color = JBColor.lazy { LINE_ACTIVE.withAlpha(0x66) }

        private fun Color.withAlpha(alpha: Int) = Color(red, green, blue, alpha)
    }
}
