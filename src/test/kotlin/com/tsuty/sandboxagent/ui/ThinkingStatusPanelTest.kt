package com.tsuty.sandboxagent.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingStatusPanelTest {

    /**
     * Every thought block ends with a paragraph break. Shown right away, it took two of the three
     * visible lines and only the last wrapped fragment of the sentence stayed in view, which read
     * as a thought missing its first characters.
     */
    @Test
    fun `the paragraph break ending a thought block waits for the next text`() {
        val panel = ThinkingStatusPanel()
        panel.append("最初の")
        panel.append("考えです。\n\n")
        assertEquals("最初の考えです。", panel.displayedText)

        panel.append("次の考えです。\n\n")
        assertEquals("最初の考えです。\n\n次の考えです。", panel.displayedText)
        assertEquals("最初の考えです。\n\n次の考えです。\n\n", panel.thought)
    }

    @Test
    fun `whitespace alone does not show the strip`() {
        val panel = ThinkingStatusPanel()
        panel.append("\n\n")
        assertFalse(panel.isVisible)
        panel.append("考え")
        assertTrue(panel.isVisible)
        assertEquals("\n\n考え", panel.displayedText)

        panel.clear()
        assertEquals("", panel.thought)
        assertFalse(panel.isVisible)
    }
}
