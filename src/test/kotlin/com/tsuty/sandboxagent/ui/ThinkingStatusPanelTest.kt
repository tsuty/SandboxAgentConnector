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

    /**
     * Once the agent moves on (answers, calls a tool) the last thought no longer describes what it
     * is doing: the strip is hidden until the next thought, the whole thought is still kept.
     */
    @Test
    fun `ending a thought hides the strip but keeps the whole thought`() {
        val panel = ThinkingStatusPanel()
        panel.append("最初の考えです。")
        assertTrue(panel.isVisible)
        panel.endThought()
        assertEquals("", panel.displayedText)
        assertFalse(panel.isVisible)

        panel.append("次の考えです。\n\n")
        assertTrue(panel.isVisible)
        assertEquals("次の考えです。", panel.displayedText)
        panel.endThought()
        assertEquals("最初の考えです。\n\n次の考えです。\n\n", panel.thought)

        panel.clear()
        assertEquals("", panel.thought)
        assertFalse(panel.isVisible)
    }

    @Test
    fun `ending a thought before any thought keeps the strip hidden`() {
        val panel = ThinkingStatusPanel()
        panel.endThought()
        assertFalse(panel.isVisible)
        assertEquals("", panel.thought)
    }
}
