package com.tsuty.sandboxagent.ui

import com.intellij.toolWindow.InternalDecoratorImpl
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.LayoutManager
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Colors shared by the tool window components.
 *
 * Plain `background = ...` is not enough here: in the new UI `ToolWindowImpl` calls
 * `InternalDecoratorImpl.setBackgroundRecursively(content, ToolWindow.background())` when the
 * content is added and every time the content is recreated, which repaints the whole subtree with
 * the default `#F7F8FA`. The components below therefore ignore the background handed to them and
 * report [PANEL_BACKGROUND] from `getBackground()` instead, and [preventBackgroundOverride] opts
 * the tool window content out of that recursive recoloring altogether.
 */
object SandboxAgentColors {

    /**
     * Background of the whole tool window: plain white under light themes (the IDE default panel
     * color is `#F7F8FA`), the untouched panel color under dark ones.
     */
    val PANEL_BACKGROUND: Color = JBColor.lazy {
        if (JBColor.isBright()) Color.WHITE else UIUtil.getPanelBackground()
    }

    /** Quiet backdrop of the Markdown code blocks and of the inline code spans. */
    val CODE_BACKGROUND: Color = JBColor.lazy {
        if (JBColor.isBright()) Color(0xF2, 0xF3, 0xF5) else UIUtil.getPanelBackground().brighter()
    }

    /** Makes [component] paint [PANEL_BACKGROUND] instead of the default panel color. */
    fun paintPanelBackground(component: JComponent) {
        component.isOpaque = true
        component.background = PANEL_BACKGROUND
    }

    /**
     * Stops the new UI tool window decorator from overwriting the background of [component] and
     * of everything below it. The call is best effort: the helper is platform internal, so a
     * future platform must not break the tool window over it.
     */
    fun preventBackgroundOverride(component: JComponent) {
        runCatching { InternalDecoratorImpl.preventRecursiveBackgroundUpdateOnToolwindow(component) }
    }
}

/** [JPanel] that always paints [SandboxAgentColors.PANEL_BACKGROUND], whoever calls `setBackground`. */
open class BackgroundPanel(layout: LayoutManager?) : JPanel(layout) {

    init {
        isOpaque = true
    }

    override fun getBackground(): Color = SandboxAgentColors.PANEL_BACKGROUND
}

/** [JBScrollPane] with a transparent viewport over [SandboxAgentColors.PANEL_BACKGROUND]. */
class BackgroundScrollPane(view: Component) : JBScrollPane(view) {

    init {
        isOpaque = true
        viewport.isOpaque = false
    }

    override fun getBackground(): Color = SandboxAgentColors.PANEL_BACKGROUND
}
