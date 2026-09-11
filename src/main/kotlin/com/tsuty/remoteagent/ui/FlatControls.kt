package com.tsuty.remoteagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStepEx
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.UIUtil
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Flat controls of the prompt action bar.
 *
 * They are plain panels instead of buttons on purpose: a [javax.swing.JButton] is laid out by its
 * look and feel, which pads the content to the platform button width and paints the rollover on
 * that whole area. Here the content (an icon, or a label plus a caret) defines the size, and the
 * rounded hover highlight covers exactly that content.
 *
 * Labels are never rendered on the icon buttons — the text lives in the tooltip only.
 */
private val HOVER_ARC = JBUI.scale(6)

/**
 * Base of the flat controls: sizes itself after its content, paints the rounded hover highlight
 * and turns a click into [onClick].
 */
abstract class FlatControl(private val onClick: () -> Unit) : JPanel(null) {

    private var hovered = false

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setHovered(true)
            override fun mouseExited(e: MouseEvent) = setHovered(false)
            override fun mousePressed(e: MouseEvent) {
                if (isEnabled) onClick()
            }
        })
    }

    /** Content components in the order they are laid out from left to right. */
    protected abstract fun contentComponents(): List<JLabel>

    /** Horizontal gap between the content components. */
    protected open val contentGap: Int get() = JBUI.scale(2)

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        contentComponents().forEach { it.isEnabled = enabled }
        cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        if (!enabled) setHovered(false)
        // A disabled label reports a different preferred size, so the parent has to lay the control
        // out again — otherwise the control keeps the bounds it had in the previous state.
        revalidate()
        parent?.revalidate()
        repaint()
    }

    private fun setHovered(value: Boolean) {
        val effective = value && isEnabled
        if (hovered == effective) return
        hovered = effective
        repaint()
    }

    /**
     * Manual layout: [java.awt.FlowLayout] would centre the content inside whatever size the
     * parent hands out, which is exactly the padding the standard buttons suffered from.
     */
    override fun doLayout() {
        val insets = insets
        var x = insets.left
        val height = height - insets.top - insets.bottom
        contentComponents().filter { it.isVisible }.forEach { component ->
            val width = component.preferredSize.width
            component.setBounds(x, insets.top + (height - component.preferredSize.height) / 2, width, component.preferredSize.height)
            x += width + contentGap
        }
    }

    override fun getPreferredSize(): Dimension {
        val insets = insets
        var width = insets.left + insets.right
        var height = 0
        val visible = contentComponents().filter { it.isVisible }
        visible.forEachIndexed { index, component ->
            val size = component.preferredSize
            width += size.width + if (index > 0) contentGap else 0
            height = maxOf(height, size.height)
        }
        return Dimension(width, height + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        if (hovered) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBUI.CurrentTheme.ActionButton.hoverBackground()
                g2.fillRoundRect(0, 0, width, height, HOVER_ARC, HOVER_ARC)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }
}

/**
 * Icon only button: the icon itself is the button, the label is shown as a tooltip.
 */
class FlatIconButton(
    icon: Icon,
    tooltip: String,
    onClick: () -> Unit,
) : FlatControl(onClick) {

    private val iconLabel = JLabel(icon).also { it.disabledIcon = IconLoader.getDisabledIcon(icon) }

    init {
        toolTipText = tooltip
        border = JBUI.Borders.empty(3)
        add(iconLabel)
    }

    /**
     * Both states are set together: [JLabel] falls back to no icon at all when it is disabled and
     * has no disabled variant (which is the case for every icon the IDE loader returns, as it is
     * not an [javax.swing.ImageIcon]), and a control painting nothing looks like a missing one.
     */
    var icon: Icon?
        get() = iconLabel.icon
        set(value) {
            iconLabel.icon = value
            iconLabel.disabledIcon = value?.let { IconLoader.getDisabledIcon(it) }
            revalidate()
            repaint()
        }

    override fun contentComponents(): List<JLabel> = listOf(iconLabel)
}

/**
 * Borderless drop down: the current item plus a caret, without any combo box frame. The popup is
 * a plain IDE list popup, so the control looks like flat text until the mouse hovers it.
 *
 * [description] is the explanation the agent reports for an item; it is shown as the tooltip of
 * the hovered row of the popup, which is the only place long text fits — the control itself is a
 * single flat line.
 */
class FlatSelector<T : Any>(
    private val tooltip: String,
    private val presentation: (T) -> String,
    private val onSelect: (T) -> Unit,
    private val description: (T) -> String? = { null },
) : FlatControl({}) {

    private val label = JLabel("", SwingConstants.LEFT).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getLabelForeground()
    }
    private val caret = JLabel(AllIcons.General.ArrowDown)

    private var items: List<T> = emptyList()

    /** Guards [onSelect] while the selection is applied programmatically. */
    private var silent = false

    var selected: T? = null
        private set

    init {
        toolTipText = tooltip
        border = JBUI.Borders.empty(3, 4)
        add(label)
        add(caret)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (isEnabled) showPopup()
            }
        })
    }

    override fun contentComponents(): List<JLabel> = listOf(label, caret)

    /** Replaces the items and selects [current] without firing [onSelect]. */
    fun setItems(items: List<T>, current: T?) {
        this.items = items
        withoutEvents { select(current ?: items.firstOrNull()) }
        isVisible = items.isNotEmpty()
        // Showing a control does not schedule a layout of the action bar on its own, so the
        // selector would keep the empty bounds it had while it was hidden.
        parent?.revalidate()
        parent?.repaint()
    }

    /** Selects the first item matching [predicate], if any, without firing [onSelect]. */
    fun selectMatching(predicate: (T) -> Boolean) {
        val match = items.firstOrNull(predicate) ?: return
        withoutEvents { select(match) }
    }

    private fun withoutEvents(action: () -> Unit) {
        silent = true
        try {
            action()
        } finally {
            silent = false
        }
    }

    private fun select(item: T?) {
        selected = item
        label.text = item?.let(presentation).orEmpty()
        toolTipText = item?.let { "$tooltip: ${presentation(it)}" } ?: tooltip
        revalidate()
        repaint()
        if (!silent && item != null) onSelect(item)
    }

    /**
     * The popup is a [com.intellij.openapi.ui.popup.ListPopupStepEx] rather than a chooser
     * builder: only the step is asked for a per row tooltip, which is what makes the agent side
     * description visible on hover.
     */
    private fun showPopup() {
        if (items.isEmpty()) return
        val step = FlatSelectorPopupStep(
            items = items,
            selected = selected,
            presentation = presentation,
            description = description,
            onItemChosen = { chosen -> if (chosen != selected) select(chosen) },
        )
        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(this)
    }
}

/** Rows of the [FlatSelector] dropdown: the item label, with its description as the tooltip. */
internal class FlatSelectorPopupStep<T : Any>(
    items: List<T>,
    selected: T?,
    private val presentation: (T) -> String,
    private val description: (T) -> String?,
    private val onItemChosen: (T) -> Unit,
) : BaseListPopupStep<T>(null as String?, items), ListPopupStepEx<T> {

    init {
        defaultOptionIndex = items.indexOf(selected)
    }

    override fun getTextFor(value: T): String = presentation(value)

    override fun getTooltipTextFor(value: T): String? = description(value)?.takeIf { it.isNotBlank() }

    override fun setEmptyText(emptyText: StatusText) {
        emptyText.text = ""
    }

    override fun isSelectable(value: T): Boolean = true

    override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? {
        if (finalChoice) onItemChosen(selectedValue)
        return FINAL_CHOICE
    }
}

/**
 * Generic selector entry: an agent side id, the label shown in the action bar, and the optional
 * description the agent reports, shown as the tooltip of the row in the dropdown.
 */
data class SelectorItem(val id: String, val label: String, val description: String? = null)
