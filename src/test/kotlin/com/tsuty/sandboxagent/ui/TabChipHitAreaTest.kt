package com.tsuty.sandboxagent.ui

import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers how a tab chip tells a click on its session title (which opens the history dropdown) from
 * a click anywhere else on it (which only selects the tab), see `hitsAnyOf`.
 *
 * The chip itself is a private inner class of `SandboxAgentTabsPanel` and needs a live `Project`,
 * so the layout below is a stand-in built the same way: the agent sits at the chip's left edge,
 * the number, the title and its caret sit in a row next to it, and only the last two are the
 * dropdown.
 *
 * Whether the chip acts on a click at all is a separate question the chip answers first (only the
 * active tab does, `actsOnClick`); this covers where a click that does act has landed.
 */
class TabChipHitAreaTest {

    private val numberLabel = JLabel("2")
    private val titleLabel = JLabel("Fix the parser")
    private val caretLabel = JLabel("v")
    private val titleRow = JPanel(BorderLayout()).apply {
        add(numberLabel, BorderLayout.WEST)
        add(titleLabel, BorderLayout.CENTER)
        add(caretLabel, BorderLayout.EAST)
    }
    private val agentLabel = JLabel("a")
    private val chip = JPanel(BorderLayout()).apply {
        add(agentLabel, BorderLayout.WEST)
        add(titleRow, BorderLayout.CENTER)
        add(JLabel("x"), BorderLayout.EAST)
    }

    private val dropdownTargets = listOf(titleLabel, caretLabel)

    init {
        // Headless Swing never lays a component out on its own, so the chip is given its bounds
        // and laid out explicitly: `number | title | caret` at 0..10, 10..90, 90..100 of the row,
        // and the row itself placed after the connection toggle and inset by the chip's border —
        // the offset the hit test converts away.
        chip.setBounds(0, 0, 148, 26)
        agentLabel.setBounds(8, 3, 18, 20)
        titleRow.setBounds(26, 3, 100, 20)
        numberLabel.setBounds(0, 0, 10, 20)
        titleLabel.setBounds(10, 0, 80, 20)
        caretLabel.setBounds(90, 0, 10, 20)
    }

    @Test
    fun `a click on the title opens the dropdown`() {
        assertTrue(hitsAnyOf(Point(68, 10), chip, titleRow, dropdownTargets))
    }

    @Test
    fun `a click on the caret opens the dropdown`() {
        assertTrue(hitsAnyOf(Point(118, 10), chip, titleRow, dropdownTargets))
    }

    @Test
    fun `a click on the number only selects the tab`() {
        assertFalse(hitsAnyOf(Point(30, 10), chip, titleRow, dropdownTargets))
    }

    @Test
    fun `a click on the agent is not a dropdown target`() {
        // The agent has a listener of its own (it attaches this tab), so the chip must not read a
        // click landing on it as one on the session title.
        assertFalse(hitsAnyOf(Point(14, 10), chip, titleRow, dropdownTargets))
    }

    @Test
    fun `a click right of the title row only selects the tab`() {
        assertFalse(hitsAnyOf(Point(138, 10), chip, titleRow, dropdownTargets))
    }

    @Test
    fun `a hidden caret is not a dropdown target`() {
        // The caret is hidden while the tab has no live agent (nothing to restore), so its area
        // must fall back to plainly selecting the tab.
        caretLabel.isVisible = false

        assertFalse(hitsAnyOf(Point(118, 10), chip, titleRow, dropdownTargets))
    }
}
