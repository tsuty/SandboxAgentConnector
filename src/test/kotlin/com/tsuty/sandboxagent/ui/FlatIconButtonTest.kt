package com.tsuty.sandboxagent.ui

import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlatIconButtonTest {

    /**
     * A toolbar control starts its life disabled (there is no session before the connection), and
     * a [javax.swing.JLabel] that is disabled without a disabled icon reports a zero sized icon:
     * the control collapsed to its border and never grew back, which reached the toolbar as a
     * missing button. Both states have to paint the icon.
     */
    @Test
    fun `an icon button paints its icon in both states`() {
        listOf(true, false).forEach { enabled ->
            val button = FlatIconButton(SandboxAgentIcons.NewSession, "New Session") {}
            button.isEnabled = enabled
            assertTrue(paintedPixels(button) > 0, "the icon is not painted when enabled=$enabled")
        }
    }

    /** Disabling and enabling again has to restore the original size of the control. */
    @Test
    fun `an icon button keeps its size across a disable and enable cycle`() {
        val button = FlatIconButton(SandboxAgentIcons.NewSession, "New Session") {}
        val initial = button.preferredSize
        assertTrue(initial.width > 0 && initial.height > 0)

        button.isEnabled = false
        assertEquals(initial, button.preferredSize)
        button.isEnabled = true
        assertEquals(initial, button.preferredSize)
    }

    private fun paintedPixels(button: FlatIconButton): Int {
        val size = button.preferredSize
        button.setBounds(0, 0, size.width, size.height)
        button.doLayout()
        val image = BufferedImage(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            button.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return (0 until image.width).sumOf { x ->
            (0 until image.height).count { y -> image.getRGB(x, y) ushr 24 != 0 }
        }
    }
}
