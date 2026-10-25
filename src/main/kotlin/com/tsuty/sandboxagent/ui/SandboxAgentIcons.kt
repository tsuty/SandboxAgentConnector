package com.tsuty.sandboxagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.LayeredIcon
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.SVGLoader
import java.awt.AlphaComposite
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * What a tab's agent icon is marked with, in the one corner its drawing leaves free (see
 * [SandboxAgentIcons.agentIcon]).
 *
 * The corner holds a single mark, so the states share it in the order they are declared in: a tab
 * with no agent up cannot be waiting for an answer either, which is why [DISCONNECTED] outranks
 * [NOTIFICATION].
 */
enum class AgentMark {
    /**
     * Nothing is drawn over the agent. Being attached with nothing pending is the normal state of
     * a tab (it connects on its own when opened), and a mark that is always there marks nothing.
     */
    NONE,

    /** The agent is not (yet) attached — the one failure a chip shows on its own. */
    DISCONNECTED,

    /**
     * The agent is waiting for an answer the user has not given yet (a `session/request_permission`
     * of a tab that is not in front). Tabs stay live in the background (Ph2 §2), so without this
     * an agent could sit blocked on a tab nobody is looking at.
     */
    NOTIFICATION,
}

/**
 * Icons the plugin paints with Swing (toolbars, buttons, the tool window stripe).
 *
 * The icons of the transcript document are a different set with different rules — they are files
 * under `icons/transcript/` handed to the browser as one sprite ([TranscriptIcons]), which cannot
 * use a Swing `Icon` at all. See `docs/development/ICONS.md`.
 */
object SandboxAgentIcons {
    @JvmField
    val Send: Icon = IconLoader.getIcon("/icons/send.svg", SandboxAgentIcons::class.java)

    @JvmField
    val Stop: Icon = IconLoader.getIcon("/icons/stop.svg", SandboxAgentIcons::class.java)

    /**
     * The agent of a tab that brought no drawing of its own (see [agent]), whatever the state of
     * that tab: being connected is the normal state, so nothing is drawn over it there and only
     * the failure is marked (see [DisconnectedStatus]).
     */
    @JvmField
    val Disconnected: Icon = IconLoader.getIcon("/icons/disconnected.svg", SandboxAgentIcons::class.java)

    /**
     * The cross marking an agent that is not attached, as a layer of its own so it can go over the
     * drawing of an agent just as well as over the bundled one.
     */
    @JvmField
    val DisconnectedStatus: Icon = IconLoader.getIcon("/icons/disconnectedStatus.svg", SandboxAgentIcons::class.java)

    /**
     * The dot marking an agent that is waiting for an answer, drawn into the very same corner as
     * [DisconnectedStatus] (see [AgentMark]).
     */
    @JvmField
    val Notification: Icon = IconLoader.getIcon("/icons/notification.svg", SandboxAgentIcons::class.java)

    @JvmField
    val TokenUsage: Icon = IconLoader.getIcon("/icons/tokenUsage.svg", SandboxAgentIcons::class.java)

    /** `Open Sandbox Terminal` of the tool window options menu. */
    @JvmField
    val Console: Icon = IconLoader.getIcon("/icons/console.svg", SandboxAgentIcons::class.java)

    /** `Sandbox Agent Connector Settings` of the tool window options menu. */
    @JvmField
    val Settings: Icon = IconLoader.getIcon("/icons/settings.svg", SandboxAgentIcons::class.java)

    /**
     * The bundled drawing is preferred, but a broken or unreadable SVG must not leave the toolbar
     * with an invisible button: an icon that renders as nothing is indistinguishable from a missing
     * control, so the platform `Add` icon takes over whenever the drawing has no geometry.
     */
    @JvmField
    val NewSession: Icon = loadOrFallback("/icons/newSession.svg", AllIcons.General.Add)

    /**
     * The drawing the selected agent brought along (`AgentEntry.iconSvg`), as a
     * Swing icon; `null` whenever there is none — or whenever it does not render, which is what
     * makes the bundled icons the default.
     *
     * The markup is a file of the ACP registry, kept in the settings and already cut down to its
     * shapes (`AgentIcon`), so it keeps the colors it was drawn with; what it leaves to the text
     * around it is resolved against the theme (see [themed]) — or painted in [color], the one the
     * settings give the agent (`AgentColors`), whenever there is one.
     *
     * The icon measures [SIZE] like every bundled one, but the drawing itself is scaled into
     * [AGENT_SIZE] and sits in the lower left corner of that box: the bundled pair paints its own
     * frame with a margin of the same kind, and the corner it leaves free is where the mark of the
     * agent goes (see [agentIcon]).
     */
    fun agent(svg: String?, color: String? = null): Icon? {
        val markup = svg?.takeIf { it.isNotBlank() } ?: return null
        // Rendered here and not only while painting: a markup the loader cannot read has to leave
        // the bundled pair in place instead of turning the button into an empty one.
        raster(markup, color) ?: return null
        return AgentImageIcon(markup, color)
    }

    /**
     * Icon of a tab's agent: the drawing it brought along — the bundled [Disconnected] without one
     * — carrying [mark] in the corner that drawing leaves free.
     *
     * The agent drawing carries the [SIZE] box of the bundled icon whatever the mark is (see
     * [agent]), so the chip neither resizes itself when another agent is picked nor moves the
     * drawing when a mark comes and goes: a mark only ever occupies that free corner.
     */
    fun agentIcon(agentSvg: String?, mark: AgentMark, color: String? = null): Icon {
        val agent = agent(agentSvg, color) ?: Disconnected
        val overlay = when (mark) {
            AgentMark.NONE -> return agent
            AgentMark.DISCONNECTED -> DisconnectedStatus
            AgentMark.NOTIFICATION -> Notification
        }
        return LayeredIcon(2).apply {
            setIcon(agent, 0)
            setIcon(overlay, 1)
        }
    }

    /** The mark of a Read Only tab, drawn over [Disconnected] (see [readOnlyTabIcon]). */
    @JvmField
    val Readonly: Icon = IconLoader.getIcon("/icons/readonly.svg", SandboxAgentIcons::class.java)

    /**
     * Icon of a Read Only tab: the bundled agent with the read-only mark over it. It never pulses
     * nor carries another mark — such a tab has no agent behind it at all.
     */
    fun readOnlyTabIcon(): Icon = LayeredIcon(2).apply {
        setIcon(Disconnected, 0)
        setIcon(Readonly, 1)
    }

    /**
     * [icon] painted at [alpha] of its opacity, which is how a tab pulses its agent while a turn
     * runs (see `SandboxAgentTabsPanel`). `1f` hands the icon back untouched, so a chip that is not
     * pulsing paints exactly what it used to.
     */
    fun translucent(icon: Icon, alpha: Float): Icon =
        if (alpha >= 1f) icon else TranslucentIcon(icon, alpha)

    private fun loadOrFallback(path: String, fallback: Icon): Icon {
        val icon = IconLoader.findIcon(path, SandboxAgentIcons::class.java) ?: return fallback
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
private class AgentImageIcon(private val svg: String, private val color: String?) : Icon {

    override fun getIconWidth(): Int = JBUIScale.scale(SIZE)

    override fun getIconHeight(): Int = JBUIScale.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val image = raster(svg, color) ?: return
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
 * [delegate] painted at [alpha] of its opacity, which is how a chip pulses its agent while a turn
 * runs (see [SandboxAgentIcons.translucent]).
 *
 * The composite is set on a copy of the graphics rather than on the one handed in: an icon paints
 * into the graphics of whatever component asked for it, and leaving a transparent composite behind
 * would fade everything drawn after it.
 */
private class TranslucentIcon(private val delegate: Icon, private val alpha: Float) : Icon {

    override fun getIconWidth(): Int = delegate.iconWidth

    override fun getIconHeight(): Int = delegate.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
            delegate.paintIcon(c, graphics, x, y)
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
 * made for the other one. A [chosen] color replaces the one of the theme (see `AgentColors`).
 */
private fun raster(svg: String, chosen: String? = null): Image? {
    val drawn = JBUIScale.scale(AGENT_SIZE)
    val color = chosen ?: agentColor()
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

private val log = Logger.getInstance(SandboxAgentIcons::class.java)

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
