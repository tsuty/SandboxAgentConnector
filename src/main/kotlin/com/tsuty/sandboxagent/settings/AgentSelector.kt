package com.tsuty.sandboxagent.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.UIUtil
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

/**
 * Picks the agent started inside the sandbox out of the ACP registry (see [AcpAgentRegistry]).
 *
 * The command itself is neither typed nor shown: it is derived from the distribution of the picked
 * entry, it can grow long (environment variables included) and knowing it is of no use here — the
 * IDE log spells out what was started. Since a registry package pins the version of the agent, the
 * entry that was picked keeps running that version until the update offered next to it is accepted
 * — a new registry never changes what starts behind the user's back.
 *
 * An entry that ships as a pre-built `binary` is the one exception to all of it, and the field
 * below the drop down appears only for those: nothing is installed into the sandbox, so the
 * executable is put there by hand and the command that starts it is the only thing that can be
 * known about it. It is therefore typed, checked ([AgentCommandValidator]) and stored as text. No
 * update is offered for it either: the typed command pins no version, so the version shown for it
 * is simply the one the registry ships today — what the command actually starts is not something
 * the plugin can read, let alone rewrite.
 *
 * The environment variables of a registry entry are shown as a hint and nothing more: the plugin
 * hands no environment to the sandbox, so they have to be set there beforehand (see [AcpAgent.env]).
 *
 * The drop down, the version it pins and the update offered for it read as one line, and the drop
 * down starts at x = 0 of the panel so that it lines up with every other editor of the dialog.
 *
 * With [selectable] `= false` the drop down is replaced by the name of the agent: the agent list of
 * the settings edits one enabled agent with it (see [AgentListPanel]), and swapping that agent for
 * another one would be a remove and an add, which the list does itself.
 */
class AgentSelector(private val selectable: Boolean = true) : JPanel(VerticalLayout(VERTICAL_GAP)) {

    /** Guards the listeners while the model is repopulated or the state is pushed in. */
    private var updating = false

    /** What the settings hold for the selected agent; the value that gets persisted. */
    private var current: AcpAgent = AcpAgentRegistry.FALLBACK_AGENT

    /** What the registry currently ships for the selected agent, when it ships it at all. */
    private var latest: AcpAgent? = null

    private val comboModel = DefaultComboBoxModel<AcpAgent>()

    private val combo = ComboBox(comboModel).apply {
        renderer = object : SimpleListCellRenderer<AcpAgent>() {
            override fun customize(
                list: JList<out AcpAgent>,
                value: AcpAgent?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.name.orEmpty()
                val description = value?.description?.takeIf { it.isNotBlank() }
                toolTipText = description
                // The popup asks the renderer for the text to show, but only a list that has a
                // tooltip of its own is watched by the tooltip manager in the first place.
                if (index >= 0) list.toolTipText = description
            }
        }
        addActionListener {
            if (updating) return@addActionListener
            (selectedItem as? AcpAgent)?.let { pick(it) }
        }
    }

    /** Stands in for the drop down when the agent cannot be swapped (see [selectable]). */
    private val nameLabel = JBLabel()

    private val versionLabel = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }

    private val updateButton = JButton("Update").apply {
        isVisible = false
        addActionListener { latest?.let { adopt(it) } }
    }

    /** Only shown for a `binary` entry, where the command is the one thing to know about it. */
    private val commandField = JBTextField(COMMAND_COLUMNS).apply {
        isVisible = false
        document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (updating) return
                current = current.copy(customCommand = text)
                onValidationChanged?.invoke()
            }
        })
    }

    /** Shown for whichever entry names environment variables, whatever it is started with. */
    private val envLabel = JBLabel().apply {
        isVisible = false
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }

    /** The drop down with its version and update offer, laid out as the one line they read as. */
    private val header = JPanel(HorizontalLayout(HORIZONTAL_GAP, SwingConstants.CENTER)).apply {
        isOpaque = false
        add(if (selectable) combo else nameLabel)
        add(versionLabel)
        add(updateButton)
    }

    init {
        isOpaque = false
        add(header)
        add(commandField)
        add(envLabel)
        refresh()
    }

    /** The agent the settings should hold; the value that gets persisted. */
    val agent: AcpAgent get() = current

    /**
     * Told about the selected agent whenever it may have changed, so what the dialog shows per
     * agent (its color, see `AgentColors`) can follow the drop down before anything is applied.
     */
    var onAgentChanged: ((AcpAgent) -> Unit)? = null

    /**
     * Told whenever the selected agent or the custom command changes, so that UI DSL validation
     * installed on this component can re-evaluate.
     */
    var onValidationChanged: (() -> Unit)? = null

    /**
     * The reason the selected agent cannot be started, or `null` when it can be. Only a `binary`
     * entry ever has one: every other command is derived and cannot be wrong.
     */
    fun commandError(): String? =
        if (current.launcher.isCustom) AgentCommandValidator.validate(current.customCommand) else null

    /** Pushes a persisted agent in, which stays the selected one until the registry answers. */
    fun setState(agent: AcpAgent) {
        current = agent
        latest = null
        setAgents(emptyList())
    }

    /**
     * Replaces the drop down entries, keeping the selected agent and the version it holds. Used
     * when the registry answers after the panel is already visible. An agent the registry no
     * longer ships stays selectable: it is what the sandbox is set up for.
     */
    fun setAgents(agents: List<AcpAgent>) {
        val known = agents.firstOrNull { it.id == current.id }
        val all = if (known == null) listOf(current) + agents else agents
        updating = true
        try {
            comboModel.removeAllElements()
            all.forEach(comboModel::addElement)
            comboModel.selectedItem = all.first { it.id == current.id }
        } finally {
            updating = false
        }
        latest = known
        // The registry is what knows the arguments a typed command needs, so the hint of the
        // field is only right once it answered.
        if (known != null) current = current.copy(env = known.env)
        refresh()
    }

    /**
     * A newly picked agent is taken as the registry describes it: nothing is pinned yet. A
     * `binary` entry brings no command along — the registry names a path inside an archive nobody
     * unpacked here — so its field starts empty and the dialog refuses to apply until it is filled.
     */
    private fun pick(agent: AcpAgent) {
        // Re-picking the entry that is already selected must not throw a typed command away: the
        // drop down reports every choice made in its popup, the one already selected included.
        current = if (agent.id == current.id) agent.copy(customCommand = current.customCommand) else agent
        latest = agent
        refresh()
    }

    /** Accepts the version the registry ships for the agent that is already selected. */
    private fun adopt(agent: AcpAgent) {
        current = agent
        refresh()
    }

    private fun refresh() {
        nameLabel.text = current.name
        val custom = current.launcher.isCustom
        // What the registry says about a `binary` entry today, which is all it can say: the typed
        // command pins nothing, so there is no older version to offer an update from.
        val described = latest ?: current
        val newer = latest
            ?.takeIf { !custom && it.version.isNotBlank() && it.version != current.version }
        versionLabel.text = when {
            custom -> "Version ${described.versionOrUnknown}"
            newer != null -> "Version ${current.versionOrUnknown} · ${newer.version} available"
            else -> "Version ${current.versionOrUnknown}"
        }
        updateButton.isVisible = newer != null
        updateButton.toolTipText = newer?.let { "Start version ${it.version} from now on" }
        commandField.isVisible = custom
        commandField.emptyText.text = described.commandHint
        updating = true
        try {
            if (commandField.text != current.customCommand) commandField.text = current.customCommand
        } finally {
            updating = false
        }
        envLabel.isVisible = described.env.isNotEmpty()
        envLabel.text = described.env.entries.joinToString(", ", prefix = "Expects in the sandbox: ") {
            "${it.key}=${it.value}"
        }
        revalidate()
        onAgentChanged?.invoke(current)
        onValidationChanged?.invoke()
    }

    /** An agent picked before the registry was reachable has no version to show. */
    private val AcpAgent.versionOrUnknown: String get() = version.ifBlank { "unknown" }

    /**
     * What to type: the path of the executable inside the sandbox, followed by the arguments the
     * registry entry names. Those arguments are the part that is easy to forget and impossible to
     * guess — an agent started without its `acp` argument talks something else entirely — while
     * the path is not something the registry can tell, since it depends on where the binary was
     * installed.
     */
    private val AcpAgent.commandHint: String
        get() = (listOf(COMMAND_HINT_PATH) + args).joinToString(" ")

    private companion object {
        /** Unscaled: [HorizontalLayout] and [VerticalLayout] scale the gap themselves. */
        const val HORIZONTAL_GAP = 6
        const val VERTICAL_GAP = 4

        /** Wide enough for an absolute path with its arguments, which is what is typed here. */
        const val COMMAND_COLUMNS = 44

        const val COMMAND_HINT_PATH = "/path/to/agent"
    }
}
