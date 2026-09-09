package com.tsuty.remoteagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.SVGLoader
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Icons the plugin paints with Swing (toolbars, buttons, the tool window stripe).
 *
 * The icons of the transcript document are a different set with different rules — they are files
 * under `icons/transcript/` handed to the browser as one sprite ([TranscriptIcons]), which cannot
 * use a Swing `Icon` at all. See `docs/development/ICONS.md`.
 */
object RemoteAgentIcons {
    @JvmField
    val Send: Icon = IconLoader.getIcon("/icons/send.svg", RemoteAgentIcons::class.java)

    @JvmField
    val Stop: Icon = IconLoader.getIcon("/icons/stop.svg", RemoteAgentIcons::class.java)

    @JvmField
    val Connected: Icon = IconLoader.getIcon("/icons/connected.svg", RemoteAgentIcons::class.java)

    @JvmField
    val Disconnected: Icon = IconLoader.getIcon("/icons/disconnected.svg", RemoteAgentIcons::class.java)

    /**
     * The dot that turns [Disconnected] into [Connected], as a layer of its own: an agent that
     * brought its own drawing (see [agent]) is marked with the very same dot instead.
     */
    @JvmField
    val ConnectionStatus: Icon = IconLoader.getIcon("/icons/connectionStatus.svg", RemoteAgentIcons::class.java)

    @JvmField
    val TokenUsage: Icon = IconLoader.getIcon("/icons/tokenUsage.svg", RemoteAgentIcons::class.java)

    /** `Open Sandbox Terminal` of the tool window options menu. */
    @JvmField
    val Console: Icon = IconLoader.getIcon("/icons/console.svg", RemoteAgentIcons::class.java)

    /** `Remote Agent Connector Settings` of the tool window options menu. */
    @JvmField
    val Settings: Icon = IconLoader.getIcon("/icons/settings.svg", RemoteAgentIcons::class.java)

    /**
     * The bundled drawing is preferred, but a broken or unreadable SVG must not leave the toolbar
     * with an invisible button: an icon that renders as nothing is indistinguishable from a missing
     * control, so the platform `Add` icon takes over whenever the drawing has no geometry.
     */
    @JvmField
    val NewSession: Icon = loadOrFallback("/icons/newSession.svg", AllIcons.General.Add)

    /**
     * The drawing the selected agent brought along (`RemoteAgentSettingsState.agentIconSvg`), as a
     * Swing icon; `null` whenever there is none — or whenever it does not render, which is what
     * makes the bundled icons the default.
     *
     * The markup is a file of the ACP registry, kept in the settings and already cut down to its
     * shapes (`AgentIcon`), so it keeps the colors it was drawn with; what it leaves to the text
     * around it is resolved against the theme (see [themed]).
     *
     * The icon measures [SIZE] like every bundled one, but the drawing itself is scaled into
     * [AGENT_SIZE] and sits in the lower left corner of that box: the bundled pair paints its own
     * frame with a margin of the same kind, and the corner it leaves free is where the connection
     * dot goes (see [connection]).
     */
    fun agent(svg: String?): Icon? {
        val markup = svg?.takeIf { it.isNotBlank() } ?: return null
        // Rendered here and not only while painting: a markup the loader cannot read has to leave
        // the bundled pair in place instead of turning the button into an empty one.
        raster(markup) ?: return null
        return AgentImageIcon(markup)
    }

    /**
     * Icon of the connect / disconnect button: the agent, with the connection dot over it while
     * the session is up. Without a drawing of its own the bundled pair is used, which carries the
     * very same dot.
     *
     * The agent drawing carries the [SIZE] box of the bundled icons in both states (see [agent]),
     * so the button neither resizes itself when another agent is picked nor moves the drawing when
     * the session comes up: connecting only adds the dot into the corner the drawing leaves free.
     */
    fun connection(agentSvg: String?, connected: Boolean): Icon {
        val agent = agent(agentSvg) ?: return if (connected) Connected else Disconnected
        if (!connected) return agent
        return LayeredIcon(2).apply {
            setIcon(agent, 0)
            setIcon(ConnectionStatus, 1)
        }
    }

    private fun loadOrFallback(path: String, fallback: Icon): Icon {
        val icon = IconLoader.findIcon(path, RemoteAgentIcons::class.java) ?: return fallback
        return if (icon.iconWidth > 0 && icon.iconHeight > 0) icon else fallback
    }
}

/**
 * The agent drawing as an icon of [SIZE], rasterized on demand and painted [AGENT_SIZE] wide in
 * the lower left corner of that box.
 *
 * The markup is kept instead of the pixels, and the raster is asked for while painting: it belongs
 * to the scale and the theme it was made for, and both can change while the icon sits on the
 * button (see [raster]). The size is reported in user space, so the button lays the drawing out
 * like every other 16x16 icon — handing the raster to `JBImageIcon` instead reports the device
 * pixels as the size of the icon, which paints it twice as large on a HiDPI screen.
 */
private class AgentImageIcon(private val svg: String) : Icon {

    override fun getIconWidth(): Int = JBUIScale.scale(SIZE)

    override fun getIconHeight(): Int = JBUIScale.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val image = raster(svg) ?: return
        val box = JBUIScale.scale(SIZE)
        val drawn = JBUIScale.scale(AGENT_SIZE)
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(image, x, y + (box - drawn), drawn, drawn, c)
        } finally {
            graphics.dispose()
        }
    }
}

/**
 * The pixels of [svg] for the current scale and theme, rendered once per combination.
 *
 * A rasterization is far too expensive to repeat on every repaint of the connection button, and a
 * raster is only good for the scale it was made for and the color the theme gave it, so both are
 * part of the key: a moved window or a switched theme renders again instead of painting a drawing
 * made for the other one.
 */
private fun raster(svg: String): Image? {
    val drawn = JBUIScale.scale(AGENT_SIZE)
    val color = agentColor()
    return rendered.computeIfAbsent("$drawn:$color:$svg") { render(svg, drawn, color) ?: MISSING }
        .takeIf { it !== MISSING }
}

private fun render(svg: String, drawn: Int, color: String): Image? = try {
    SVGLoader.load(
        null,
        themed(svg, color).byteInputStream(),
        ScaleContext.create(),
        drawn.toDouble(),
        drawn.toDouble(),
    )
} catch (e: Exception) {
    log.warn("Cannot render the agent icon", e)
    null
}

/**
 * [svg] with everything it left to the text around it painted in [color].
 *
 * Every drawing of the ACP registry is a monochrome one asking for `currentColor`, which is a
 * statement about the color of its surroundings rather than a color: the transcript document has
 * the browser resolve it against the text (`TranscriptIcons.agentSymbol`), while the SVG loader of
 * the platform resolves it — and an uncolored shape — to black. Black on the toolbar of a dark
 * theme is the color of nothing, which left the connection button showing its status dot only, so
 * the color is resolved here instead. A shape the drawing colored itself is left alone.
 */
private fun themed(svg: String, color: String): String {
    val painted = CURRENT_COLOR.replace(svg, color)
    val root = ROOT.find(painted) ?: return painted
    if (FILL.containsMatchIn(root.groupValues[1])) return painted
    // Without a `fill` of its own the root inherits black, and so does every shape under it.
    return painted.replaceRange(root.range, """<svg${root.groupValues[1]} fill="$color">""")
}

/**
 * The color of a drawing that has none: the grey the bundled icons are drawn in (`#6c707e` light,
 * `#ced0d6` dark), so an agent icon carries the weight of the ones next to it on the toolbar.
 */
private val AGENT_FOREGROUND = JBColor(0x6C707E, 0xCED0D6)

/** Resolved for every rasterization: the theme can change while the IDE runs. */
private fun agentColor(): String = "#%06x".format(AGENT_FOREGROUND.rgb and 0xFFFFFF)

private val log = Logger.getInstance(RemoteAgentIcons::class.java)

/** Rendered agent drawings, by markup, scale and color: the same key always yields the same image. */
private val rendered = ConcurrentHashMap<String, Image>()

/** Stands for "this markup does not render", which a map of images cannot say with `null`. */
private val MISSING: Image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

/** Size of the bundled icons, which is the box the connection button lays out. */
private const val SIZE = 16

/**
 * Size of the agent drawing inside that box: the bundled pair paints its own frame 13 wide with
 * the dot overlapping its corner, so the drawing of an agent is given the same room.
 */
private const val AGENT_SIZE = 13

private val CURRENT_COLOR = Regex("""\bcurrentColor\b""", RegexOption.IGNORE_CASE)

private val ROOT = Regex("""<svg\b([^>]*)>""", RegexOption.IGNORE_CASE)

private val FILL = Regex("""\bfill\s*=""", RegexOption.IGNORE_CASE)
