package com.tsuty.remoteagent.ui

import org.junit.jupiter.api.Test
import java.awt.CardLayout
import javax.swing.JPanel
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins down the [CardLayout] behaviour `RemoteAgentTabsPanel.closeTab` has to work around.
 *
 * Removing the *visible* card makes [CardLayout] flip the container to its first card and validate
 * it right away — while the removed component is still in the container's child list, and after
 * `Container.remove` already ran its `removeNotify()`. For a tab panel that means the JCEF browser
 * inside it is laid out once more after its resize `Alarm` was disposed, which throws out of
 * `cards.remove` and leaves the close half done: the tab stays in the strip while the content
 * jumps to tab 0. Hiding the card first (which is what selecting another tab does) avoids the
 * whole path.
 *
 * A real `RemoteAgentMainPanel` needs a live `Project`, so this covers the layout contract the fix
 * relies on rather than the panel itself.
 */
class TabCardRemovalTest {

    @Test
    fun `removing the visible card flips the container to its first card`() {
        val (cards, layout, panels) = cardsWithThreePanels()
        layout.show(cards, "2")

        cards.remove(panels[2])

        assertTrue(panels[0].isVisible, "the bug: closing the shown card silently selects tab 0")
    }

    @Test
    fun `removing an already hidden card leaves the shown one alone`() {
        val (cards, layout, panels) = cardsWithThreePanels()
        // What `closeTab` now does first: move the selection off the tab that is about to go.
        layout.show(cards, "0")

        cards.remove(panels[2])

        assertTrue(panels[0].isVisible, "the tab selected before the close must stay selected")
        assertFalse(panels[1].isVisible)
    }

    private fun cardsWithThreePanels(): Triple<JPanel, CardLayout, List<JPanel>> {
        val layout = CardLayout()
        val cards = JPanel(layout)
        val panels = (0..2).map { JPanel() }
        panels.forEachIndexed { index, panel -> cards.add(panel, index.toString()) }
        return Triple(cards, layout, panels)
    }
}
