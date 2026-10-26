package com.tsuty.sandboxagent.settings

import javax.swing.SwingConstants
import javax.swing.JTable
import javax.swing.JButton
import com.intellij.util.ui.ColorIcon
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.JBLabel
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColorPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The enabled agents of the settings dialog: a table with `+` / `-`, the default agent checked in
 * its first column, and below it the [AgentSelector] of the selected row, which shows its version,
 * offers its update and takes the command of a `binary` agent, and the color of that agent.
 *
 * The color belongs to the agent and not to the dialog: every agent of the list has its own, shown
 * as a swatch next to its name, and picked for the selected row (see [AgentColors]).
 *
 * `+` lists the registry entries that are not enabled yet, so an agent can never be enabled twice;
 * `-` is disabled for the last agent left. The rules themselves live in [AgentListEdits].
 */
class AgentListPanel : JPanel(BorderLayout()) {

    private var edits = AgentListEdits(listOf(AgentEntry.of(AcpAgentRegistry.FALLBACK_AGENT)), "")

    /** What the registry ships; the fallback agent until it answered. */
    private var registry: List<AcpAgent> = AcpAgentRegistry.fallbackAgents()

    /** Set while a row is being pushed into the selector, which is not an edit of that row. */
    private var showing = false

    private val model = object : AbstractTableModel() {
        override fun getRowCount(): Int = edits.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = COLUMNS[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 0

        override fun getValueAt(row: Int, column: Int): Any {
            val entry = edits.entries[row]
            return when (column) {
                0 -> edits.isDefault(row)
                1 -> entry.nameOrDefault()
                else -> entry.version.ifBlank { "unknown" }
            }
        }

        /** Checking a row makes it the default; the default cannot be unchecked, only replaced. */
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            if (column != 0 || value != true) return
            edits.setDefault(row)
            fireTableRowsUpdated(0, rowCount - 1)
            changed()
        }
    }

    /** The agent's name with a swatch of its color; no swatch for an agent drawn as before. */
    private val nameRenderer = object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val entry = edits.entries.getOrNull(row) ?: return
            icon = AgentColors.toColor(edits.colorOf(entry.id))?.let { ColorIcon(JBUI.scale(SWATCH_SIZE), it) }
            iconTextGap = JBUI.scale(SWATCH_GAP)
            append(entry.nameOrDefault())
        }
    }

    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        columnModel.getColumn(0).apply {
            maxWidth = JBUI.scale(DEFAULT_COLUMN_WIDTH)
            preferredWidth = JBUI.scale(DEFAULT_COLUMN_WIDTH)
        }
        columnModel.getColumn(1).cellRenderer = nameRenderer
        visibleRowCount = VISIBLE_ROWS
        selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
    }

    /**
     * Both of its callbacks report an edit: an accepted update says so through [AgentSelector.onAgentChanged],
     * a typed command only through [AgentSelector.onValidationChanged].
     */
    private val selector = AgentSelector(selectable = false).apply {
        onAgentChanged = { takeSelectorEdit() }
        onValidationChanged = { takeSelectorEdit() }
    }

    /** Set while the color of the selected row is being shown, which is not a choice. */
    private var showingColor = false

    private val colorPanel = ColorPanel().apply {
        addActionListener {
            if (showingColor) return@addActionListener
            val color = selectedColor ?: return@addActionListener
            setSelectedColor(AgentColors.toHex(color))
        }
    }

    private val resetColorButton = JButton("Reset to Default").apply {
        addActionListener { setSelectedColor(null) }
    }

    /** `Color: [swatch] [Reset to Default]`, for the agent of the selected row. */
    private val colorRow = JPanel(HorizontalLayout(SELECTOR_GAP, SwingConstants.CENTER)).apply {
        isOpaque = false
        add(JBLabel("Color:"))
        add(colorPanel)
        add(resetColorButton)
    }

    /** The decorated table, with its `+` / `-` toolbar. */
    private val decorated = ToolbarDecorator.createDecorator(table)
        .setAddAction { button -> showAddPopup(button.preferredPopupPoint) }
        .setAddActionName("Add Agent")
        .setRemoveAction { removeSelected() }
        .setRemoveActionName("Remove Agent")
        .setRemoveActionUpdater { edits.canRemove && table.selectedRow >= 0 }
        .disableUpDownActions()
        .createPanel()

    /** Told whenever the edits change, so UI DSL validation installed on this component re-runs. */
    var onValidationChanged: (() -> Unit)? = null

    init {
        isOpaque = false
        add(decorated, BorderLayout.CENTER)
        val details = JPanel(VerticalLayout(SELECTOR_GAP)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(SELECTOR_GAP)
            add(selector)
            add(colorRow)
        }
        add(details, BorderLayout.SOUTH)
    }

    /** The agent of the selected row; the default one when nothing is selected. */
    val selectedAgent: AcpAgent
        get() = edits.entries.getOrElse(table.selectedRow) { edits.entries.first() }.agent()

    /** Pushes the persisted agents in. */
    fun reset(settings: SandboxAgentSettingsState) {
        edits = AgentListEdits(settings.enabledAgents(), settings.defaultAgent().id, settings.agentColors)
        model.fireTableDataChanged()
        select(edits.entries.indexOfFirst { it.id == edits.defaultId }.coerceAtLeast(0))
    }

    /** Replaces what the registry ships, once it answered. */
    fun setRegistry(agents: List<AcpAgent>) {
        registry = agents
        showSelected()
    }

    fun isModified(settings: SandboxAgentSettingsState): Boolean = edits.differsFrom(settings)

    fun commandError(): String? = edits.commandError()

    fun apply(settings: SandboxAgentSettingsState) = edits.applyTo(settings)

    private fun showAddPopup(point: RelativePoint?) {
        val addable = edits.addable(registry)
        if (addable.isEmpty() || point == null) return
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(addable)
            .setRenderer(SimpleListCellRenderer.create("") { it.name })
            .setTitle("Add Agent")
            .setNamerForFiltering { it.name }
            .setItemChosenCallback { agent ->
                val index = edits.add(agent)
                if (index >= 0) {
                    model.fireTableRowsInserted(index, index)
                    select(index)
                    changed()
                }
            }
            .createPopup()
            .show(point)
    }

    private fun removeSelected() {
        val row = table.selectedRow
        if (!edits.remove(row)) return
        model.fireTableDataChanged()
        select(row.coerceAtMost(edits.size - 1))
        changed()
    }

    private fun select(row: Int) {
        if (row in 0 until edits.size) table.selectionModel.setSelectionInterval(row, row)
        showSelected()
    }

    private fun showSelected() {
        val entry = edits.entries.getOrNull(table.selectedRow) ?: return
        showing = true
        try {
            selector.setState(entry.agent())
            selector.setAgents(registry)
        } finally {
            showing = false
        }
        showColor(entry.id)
        onValidationChanged?.invoke()
    }

    /** Stores [hex] as the color of the selected agent; `null` resets it to the agent's default. */
    private fun setSelectedColor(hex: String?) {
        val row = table.selectedRow
        val entry = edits.entries.getOrNull(row) ?: return
        edits.setColor(entry.id, hex)
        model.fireTableRowsUpdated(row, row)
        showColor(entry.id)
        changed()
    }

    /** Shows the color [agentId] would be drawn in, empty for one drawn as before. */
    private fun showColor(agentId: String) {
        showingColor = true
        try {
            colorPanel.selectedColor = AgentColors.toColor(edits.colorOf(agentId))
        } finally {
            showingColor = false
        }
    }

    private fun takeSelectorEdit() {
        if (showing) return
        val row = table.selectedRow
        if (row >= 0) {
            edits.update(row, selector.agent)
            model.fireTableRowsUpdated(row, row)
        }
        changed()
    }

    private fun changed() {
        onValidationChanged?.invoke()
    }

    private companion object {
        val COLUMNS = arrayOf("Default", "Agent", "Version")
        const val DEFAULT_COLUMN_WIDTH = 64
        const val VISIBLE_ROWS = 5
        const val SELECTOR_GAP = 6
        const val SWATCH_SIZE = 10
        const val SWATCH_GAP = 6
    }
}
