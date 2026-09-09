package com.tsuty.remoteagent.ui

import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WrapFlowLayoutTest {

    /**
     * The permission card ("Deny", "Allow Once", "Always Allow") lives in a narrow tool window:
     * the row wraps, so the reported height has to cover every row instead of only the first one.
     */
    @Test
    fun `a narrow row reports the height of every wrapped row`() {
        val panel = panelWithButtons(rowWidth = 120)

        val preferred = panel.preferredSize

        assertTrue(
            preferred.height >= 3 * BUTTON_HEIGHT,
            "three wrapped rows need at least ${3 * BUTTON_HEIGHT}px, got ${preferred.height}px",
        )
        assertTrue(preferred.width <= 120, "the rows must not exceed the available width")
    }

    /** With enough room the layout still behaves like a single row. */
    @Test
    fun `a wide row stays a single row`() {
        val panel = panelWithButtons(rowWidth = 600)

        assertEquals(BUTTON_HEIGHT, panel.preferredSize.height)
    }

    /** Every button has to be laid out inside the panel, otherwise it cannot be clicked. */
    @Test
    fun `every button stays inside the panel`() {
        val panel = panelWithButtons(rowWidth = 120)
        panel.setBounds(0, 0, 120, panel.preferredSize.height)
        panel.doLayout()

        panel.components.forEach { component ->
            assertTrue(
                component.y + component.height <= panel.height,
                "${(component as JButton).text} is clipped: ${component.bounds} in ${panel.bounds}",
            )
        }
    }

    private fun panelWithButtons(rowWidth: Int): JPanel {
        val panel = JPanel(WrapFlowLayout(FlowLayout.LEFT, 4, 0))
        listOf("Deny", "Allow Once", "Always Allow").forEach { text ->
            panel.add(JButton(text).apply { preferredSize = Dimension(100, BUTTON_HEIGHT) })
        }
        panel.setBounds(0, 0, rowWidth, BUTTON_HEIGHT)
        return panel
    }

    private companion object {
        const val BUTTON_HEIGHT = 30
    }
}
