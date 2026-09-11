package com.tsuty.sandboxagent.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The drawing of an agent is a file downloaded from the ACP registry and drawn in two places that
 * both take markup, so what is stored has to be shapes and nothing else.
 */
class AgentIconTest {

    private val claude = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- Generator: a drawing tool -->
        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24">
          <path d="M4 4h16v16H4z" fill="#D97757"/>
        </svg>
    """.trimIndent()

    @Test
    fun `a drawing keeps its shapes and its colors`() {
        val svg = assertNotNull(AgentIcon.sanitize(claude))

        assertTrue(svg.startsWith("<svg"), svg)
        assertTrue(svg.endsWith("</svg>"), svg)
        assertTrue(svg.contains("""viewBox="0 0 24 24""""), svg)
        // The colors are the point of showing the drawing of the agent instead of the bundled one.
        assertTrue(svg.contains("""fill="#D97757""""), svg)
        assertTrue(svg.contains("<?xml").not(), svg)
        assertTrue(svg.contains("Generator").not(), svg)
    }

    @Test
    fun `a script is not part of a drawing`() {
        val svg = assertNotNull(
            AgentIcon.sanitize(
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" onload="steal()">
                  <script>steal()</script>
                  <circle cx="8" cy="8" r="8" fill="#000" onclick="steal()"/>
                </svg>
                """.trimIndent(),
            ),
        )

        assertTrue("script" !in svg, svg)
        assertTrue("onload" !in svg, svg)
        assertTrue("onclick" !in svg, svg)
        assertTrue(svg.contains("<circle"), svg)
    }

    /** A reference to another document would load from the network, which neither side allows. */
    @Test
    fun `references leaving the file are dropped`() {
        val svg = assertNotNull(
            AgentIcon.sanitize(
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <image href="https://example.com/logo.png"/>
                  <use xlink:href="https://example.com/other.svg#icon"/>
                  <use href="#shape"/>
                </svg>
                """.trimIndent(),
            ),
        )

        assertTrue("example.com" !in svg, svg)
        // A reference inside the same drawing is how a gradient or a reused shape is drawn.
        assertTrue(svg.contains("""href="#shape""""), svg)
    }

    @Test
    fun `what is not a drawing yields nothing`() {
        assertNull(AgentIcon.sanitize("<html><body>404</body></html>"))
        assertNull(AgentIcon.sanitize(""))
        assertNull(AgentIcon.sanitize("<svg viewBox=\"0 0 16 16\"></svg>"), "an empty drawing is none")
        assertNull(AgentIcon.sanitize("<svg>" + "x".repeat(AgentIcon.MAX_LENGTH) + "</svg>"))
    }

    /** What is stored is what the transcript turns into a `<symbol>` of its sprite. */
    @Test
    fun `the sanitized markup is a single svg element`() {
        val svg = assertNotNull(AgentIcon.sanitize(claude))

        assertEquals(1, Regex("<svg\\b").findAll(svg).count(), svg)
    }
}
