package com.tsuty.sandboxagent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.ScreenUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tsuty.sandboxagent.acp.AvailableCommand
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * Text handling behind the slash command completion, kept free of Swing so it stays testable.
 *
 * Slash commands are advertised by the agent through `available_commands_update` and are sent as
 * ordinary prompt text (`/plan refactor the parser`), so completing one only rewrites the editor.
 */
object SlashCommandSupport {

    /** A prompt holding nothing but `/` and the command name being typed. */
    private val COMMAND_TOKEN = Regex("^/(\\S*)$")

    /** Descriptions are one liners in the popup: agents ship whole paragraphs (see Claude Code). */
    private const val MAX_DESCRIPTION_LENGTH = 120

    /** Drops nameless duplicates and orders the commands the way they are listed to the user. */
    fun normalize(commands: List<AvailableCommand>): List<AvailableCommand> = commands
        .filter { it.name.isNotBlank() }
        .distinctBy { it.name }
        .sortedBy { it.name }

    /**
     * The command name typed so far, or `null` when [text] is more than a lone slash command token
     * (the completion only opens on `/` typed into an otherwise empty editor).
     */
    fun queryFor(text: String): String? = COMMAND_TOKEN.matchEntire(text)?.groupValues?.get(1)

    /** Prefix matches first, then substring matches; the empty query lists every command. */
    fun filter(commands: List<AvailableCommand>, query: String): List<AvailableCommand> {
        if (query.isEmpty()) return commands
        val (prefix, rest) = commands.partition { it.name.startsWith(query, ignoreCase = true) }
        return prefix + rest.filter { it.name.contains(query, ignoreCase = true) }
    }

    /** The prompt text a completed command leaves behind, ready for its arguments. */
    fun completionText(command: AvailableCommand): String = "/${command.name} "

    fun presentableName(command: AvailableCommand): String = "/${command.name}"

    /** Input hint of the command, e.g. `<query>`, shown right after its name. */
    fun presentableHint(command: AvailableCommand): String? =
        command.input?.hint?.takeIf { it.isNotBlank() }?.let { "<${it.trim()}>" }

    /** First line of the description, collapsed and shortened to a single popup row. */
    fun presentableDescription(command: AvailableCommand): String? {
        val description = command.description?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (description.length <= MAX_DESCRIPTION_LENGTH) return description
        return description.take(MAX_DESCRIPTION_LENGTH).trimEnd() + "…"
    }
}

/**
 * Non focusable list popup pinned to the prompt input: typing keeps going into the editor while the
 * arrow keys, Enter, Tab and Escape are routed here by [PromptInputPanel].
 *
 * The command name is drawn on the left and its description, dimmed, on the right, mirroring the
 * command palettes of the agent CLIs.
 */
class SlashCommandPopup(
    private val anchor: JComponent,
    private val onApply: (AvailableCommand) -> Unit,
) {

    private val log = Logger.getInstance(SlashCommandPopup::class.java)

    private val model = DefaultListModel<AvailableCommand>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = VISIBLE_ROWS
        cellRenderer = SlashCommandCellRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val index = locationToIndex(event.point)
                if (index < 0) return
                selectedIndex = index
                applySelected()
            }
        })
    }

    /** The list never grows past the screen: long command sets scroll instead. */
    private val scrollPane = JBScrollPane(list).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    private var popup: JBPopup? = null

    val isVisible: Boolean get() = popup?.isVisible == true

    /** Opens the popup, or refreshes the items of the one already showing. */
    fun show(commands: List<AvailableCommand>) {
        if (commands.isEmpty()) return hide()
        val previous = list.selectedValue?.name
        model.clear()
        commands.forEach { model.addElement(it) }
        val restored = commands.indexOfFirst { it.name == previous }
        list.selectedIndex = if (restored >= 0) restored else 0
        list.ensureIndexIsVisible(list.selectedIndex)

        val width = popupWidth()
        // Cells stop at the viewport width so long descriptions ellipsize instead of scrolling.
        list.fixedCellWidth = width - JBUI.scale(SCROLLBAR_GUTTER)
        val size = Dimension(width, popupHeight())
        scrollPane.preferredSize = size

        val showing = popup
        if (showing != null && showing.isVisible) {
            showing.size = size
            runCatching { showing.setLocation(popupLocation(size).screenPoint) }
            return
        }
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(scrollPane, null)
            .setRequestFocus(false)
            .setFocusable(false)
            // Every way out of the popup is driven by the prompt editor (typing, Escape, focus loss),
            // so none of the automatic cancellations may fire: they close it before it is seen.
            .setCancelOnClickOutside(false)
            .setCancelOnWindowDeactivation(false)
            .setCancelKeyEnabled(false)
            .setCancelOnOtherWindowOpen(false)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        created.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (popup === event.asPopup()) popup = null
            }
        })
        popup = created
        created.show(popupLocation(size))
        log.debug("slash command popup shown with ${commands.size} commands")
    }

    /**
     * Underneath the prompt input, or above it when the screen has no room left below (the input
     * usually sits at the very bottom of the tool window).
     */
    private fun popupLocation(size: Dimension): RelativePoint {
        val below = RelativePoint(anchor, Point(0, anchor.height))
        val fitsBelow = runCatching {
            val screen = ScreenUtil.getScreenRectangle(anchor.locationOnScreen)
            below.screenPoint.y + size.height <= screen.y + screen.height
        }.getOrDefault(false)
        return if (fitsBelow) below else RelativePoint(anchor, Point(0, -size.height))
    }

    /** As wide as the prompt input, so the popup reads as its drop down. */
    private fun popupWidth(): Int = anchor.width.coerceAtLeast(JBUI.scale(MIN_POPUP_WIDTH))

    /** Follows the item count and stops at [VISIBLE_ROWS] rows. */
    private fun popupHeight(): Int {
        val items = model.size().coerceAtLeast(1)
        val rowHeight = list.getCellBounds(0, 0)?.height?.takeIf { it > 0 }
            ?: (list.preferredSize.height / items).takeIf { it > 0 }
            ?: JBUI.scale(ROW_HEIGHT)
        return items.coerceAtMost(VISIBLE_ROWS) * rowHeight + JBUI.scale(8)
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    /** `true` when the popup consumed the key, so the editor keeps its own behaviour otherwise. */
    fun moveSelection(delta: Int): Boolean {
        val size = model.size()
        if (!isVisible || size == 0) return false
        val next = ((list.selectedIndex + delta) % size + size) % size
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
        return true
    }

    fun applySelected(): Boolean {
        if (!isVisible) return false
        val selected = list.selectedValue ?: return false
        hide()
        onApply(selected)
        return true
    }

    private companion object {
        const val VISIBLE_ROWS = 10
        const val MIN_POPUP_WIDTH = 320
        const val ROW_HEIGHT = 22
        const val SCROLLBAR_GUTTER = 16
    }
}

/** `/name <hint>` on the left, the dimmed description right aligned and ellipsized on the right. */
private class SlashCommandCellRenderer : ListCellRenderer<AvailableCommand> {

    private val nameLabel = JBLabel()
    private val descriptionLabel = JBLabel().apply { horizontalAlignment = SwingConstants.RIGHT }
    private val panel = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        isOpaque = true
        border = JBUI.Borders.empty(2, 8)
        add(nameLabel, BorderLayout.WEST)
        add(descriptionLabel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out AvailableCommand>,
        value: AvailableCommand,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ): Component {
        val hint = SlashCommandSupport.presentableHint(value)
        nameLabel.text = SlashCommandSupport.presentableName(value) + if (hint == null) "" else " $hint"
        descriptionLabel.text = SlashCommandSupport.presentableDescription(value) ?: ""
        panel.background = UIUtil.getListBackground(selected, true)
        nameLabel.foreground = UIUtil.getListForeground(selected, true)
        descriptionLabel.foreground =
            if (selected) UIUtil.getListForeground(true, true) else UIUtil.getContextHelpForeground()
        return panel
    }
}
