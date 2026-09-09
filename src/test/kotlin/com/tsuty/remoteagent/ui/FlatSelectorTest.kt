package com.tsuty.remoteagent.ui

import org.junit.jupiter.api.Test
import java.awt.FlowLayout
import javax.swing.JPanel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlatSelectorTest {

    private fun selector(): FlatSelector<SelectorItem> =
        FlatSelector<SelectorItem>(tooltip = "Model", presentation = { it.label }, onSelect = {}).apply { isVisible = false }

    /** The model / mode selectors stay hidden until the agent reports the choices of the session. */
    @Test
    fun `a selector is visible exactly while it has items`() {
        val selector = selector()
        assertFalse(selector.isVisible)

        selector.setItems(listOf(SelectorItem("haiku", "Haiku")), null)
        assertTrue(selector.isVisible)
        assertEquals("haiku", selector.selected?.id)

        selector.setItems(emptyList(), null)
        assertFalse(selector.isVisible)
    }

    /**
     * Showing a control does not schedule a layout of the action bar on its own: without the
     * request the selector keeps the empty bounds it had while it was hidden, which reached the
     * prompt action bar as a missing drop down.
     */
    @Test
    fun `a selector is laid out by its parent once items arrive`() {
        val selector = selector()
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2))
        bar.add(selector)
        bar.setSize(200, 30)
        bar.doLayout()
        assertEquals(0, selector.width)

        selector.setItems(listOf(SelectorItem("sonnet", "Sonnet")), null)
        bar.doLayout()
        assertTrue(selector.width > 0, "the selector kept the empty bounds it had while it was hidden")
    }

    /**
     * The description the agent reports per model / mode is what the hovered row of the dropdown
     * shows; an item without one must not open an empty tooltip.
     */
    @Test
    fun `a dropdown row shows the description of its item as the tooltip`() {
        val items = listOf(
            SelectorItem("sonnet", "Sonnet", "Balanced model"),
            SelectorItem("haiku", "Haiku"),
            SelectorItem("opus", "Opus", "  "),
        )
        val step = FlatSelectorPopupStep(
            items = items,
            selected = items[1],
            presentation = { it.label },
            description = { it.description },
            onItemChosen = {},
        )

        assertEquals("Sonnet", step.getTextFor(items[0]))
        assertEquals("Balanced model", step.getTooltipTextFor(items[0]))
        assertEquals(null, step.getTooltipTextFor(items[1]))
        assertEquals(null, step.getTooltipTextFor(items[2]))
        assertEquals(1, step.defaultOptionIndex, "the dropdown must open on the current item")
    }
}
