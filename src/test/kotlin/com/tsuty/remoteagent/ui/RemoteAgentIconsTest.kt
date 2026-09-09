package com.tsuty.remoteagent.ui

import com.intellij.util.SVGLoader
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.Icon
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteAgentIconsTest {

    @Test
    fun testIconsAreLoaded() {
        assertNotNull(RemoteAgentIcons.Send)
        assertNotNull(RemoteAgentIcons.Stop)
        assertNotNull(RemoteAgentIcons.Connected)
        assertNotNull(RemoteAgentIcons.Disconnected)
        assertNotNull(RemoteAgentIcons.TokenUsage)
        assertNotNull(RemoteAgentIcons.NewSession)
        assertNotNull(RemoteAgentIcons.Console)
        assertNotNull(RemoteAgentIcons.Settings)
    }

    /**
     * The icons are exported from a drawing tool, which likes to embed a huge base64 `c2pa`
     * provenance blob into `<metadata>`. The IDE SVG loader chokes on it and the icon silently
     * renders as nothing, so every icon has to stay a plain drawing with a geometry of its own.
     */
    @Test
    fun `every icon is a plain sized svg`() {
        val icons = swingIcons()
        assertTrue(icons.isNotEmpty(), "no icons found")
        icons.forEach { icon ->
            val text = icon.readText()
            assertTrue("c2pa" !in text, "${icon.name} still carries the exported c2pa metadata")
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(icon).documentElement
            val sized = root.getAttribute("viewBox").isNotBlank() ||
                (root.getAttribute("width").isNotBlank() && root.getAttribute("height").isNotBlank())
            assertTrue(sized, "${icon.name} has no geometry")
        }
    }

    /**
     * A geometry alone is not enough: a drawing the loader cannot read still yields an empty image,
     * which reaches the toolbar as a button with nothing on it. Every icon therefore has to paint
     * at least something once it went through the very loader the IDE uses.
     */
    @Test
    fun `every icon paints something`() {
        val icons = swingIcons()
        assertTrue(icons.isNotEmpty(), "no icons found")
        icons.forEach { icon ->
            val image = icon.inputStream().use { SVGLoader.load(it, 1.0f) } as BufferedImage
            val painted = (0 until image.width).any { x ->
                (0 until image.height).any { y -> image.getRGB(x, y) ushr 24 != 0 }
            }
            assertTrue(painted, "${icon.name} renders as an empty image")
        }
    }

    /**
     * The drawing an agent brings along must measure like a bundled icon: the connect / disconnect
     * button sits in a toolbar of 16x16 icons, and a raster reported at the size of the device
     * pixels used to paint it twice as large on a HiDPI screen.
     */
    @Test
    fun `the agent icon measures like a bundled one`() {
        val expected = RemoteAgentIcons.Disconnected.iconWidth
        val agent = assertNotNull(RemoteAgentIcons.agent(AGENT_SVG), "the agent drawing did not render")
        assertEquals(expected, agent.iconWidth)
        assertEquals(expected, agent.iconHeight)
    }

    /** Connecting adds the dot into the free corner; it must not resize the button. */
    @Test
    fun `the connection icon keeps its size in both states`() {
        val disconnected = RemoteAgentIcons.connection(AGENT_SVG, connected = false)
        val connected = RemoteAgentIcons.connection(AGENT_SVG, connected = true)
        assertEquals(RemoteAgentIcons.Disconnected.iconWidth, disconnected.iconWidth)
        assertEquals(disconnected.iconWidth, connected.iconWidth)
        assertEquals(disconnected.iconHeight, connected.iconHeight)
    }

    /** Without a drawing of its own the bundled pair stays what the button shows. */
    @Test
    fun `no agent drawing keeps the bundled pair`() {
        assertSame(RemoteAgentIcons.Disconnected, RemoteAgentIcons.connection(null, connected = false))
        assertSame(RemoteAgentIcons.Connected, RemoteAgentIcons.connection(" ", connected = true))
    }

    /**
     * Every drawing of the ACP registry is monochrome and asks for `currentColor`, which the SVG
     * loader of the platform resolves to black: the connect / disconnect button showed nothing but
     * its status dot on a dark theme, so the color has to be resolved before the drawing is drawn.
     */
    @Test
    fun `a drawing without a color of its own is not painted black`() {
        val icon = assertNotNull(RemoteAgentIcons.agent(CURRENT_COLOR_SVG), "the agent drawing did not render")
        val painted = opaqueColors(icon)
        assertTrue(painted.isNotEmpty(), "the drawing renders as an empty image")
        assertTrue(painted.none { it == BLACK }, "the drawing is painted black: $painted")
    }

    /** A shape with no `fill` at all inherits black just the same, root element included. */
    @Test
    fun `an uncolored shape is not painted black either`() {
        val icon = assertNotNull(RemoteAgentIcons.agent(UNCOLORED_SVG), "the agent drawing did not render")
        val painted = opaqueColors(icon)
        assertTrue(painted.isNotEmpty(), "the drawing renders as an empty image")
        assertTrue(painted.none { it == BLACK }, "the drawing is painted black: $painted")
    }

    /** What the drawing colored itself is left alone: a brand icon is recognized by its colors. */
    @Test
    fun `a drawing keeps the colors it brought along`() {
        val icon = assertNotNull(RemoteAgentIcons.agent(AGENT_SVG), "the agent drawing did not render")
        assertEquals(setOf(0xd97757), opaqueColors(icon))
    }

    /** The colors of the pixels the [icon] paints without any transparency. */
    private fun opaqueColors(icon: Icon): Set<Int> {
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        return (0 until image.width).flatMap { x -> (0 until image.height).map { y -> image.getRGB(x, y) } }
            .filter { it ushr 24 == 0xFF }
            .map { it and 0xFFFFFF }
            .toSet()
    }

    /**
     * Only the Swing set: the icons of the transcript live in `icons/transcript/` and are checked
     * by `TranscriptIconsTest` against rules of their own (square, one color, no `_dark` twin).
     */
    private fun swingIcons(): List<File> =
        File("src/main/resources/icons").listFiles { file -> file.extension == "svg" }.orEmpty().toList()

    private companion object {
        /** Stands for what a registry entry ships: a colored drawing filling its whole view box. */
        const val AGENT_SVG =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" height="24" fill="#d97757"/></svg>"""

        /** What every entry of the registry actually ships: one shape, and no color to paint it. */
        const val CURRENT_COLOR_SVG =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none"><rect width="24" height="24" fill="currentColor"/></svg>"""

        /** The same drawing leaving the color to whatever it is painted in. */
        const val UNCOLORED_SVG =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" height="24"/></svg>"""

        const val BLACK = 0x000000
    }
}
