package com.tsuty.sandboxagent.settings

/**
 * The enabled agents as the settings dialog edits them, until they are applied (see
 * [AgentListPanel]). Kept apart from the Swing side so that the rules of the list can be tested
 * on their own:
 *
 * - no registry id is enabled twice, since the id is what tells the sessions of the agents apart;
 * - there is always at least one agent, and exactly one of them is the default;
 * - removing the default makes the first agent left the default;
 * - every agent has a color of its own ([colorOf]); one equal to the agent's default is not
 *   stored, and the color of a removed agent is kept, so it is back when the agent is added again.
 */
class AgentListEdits(
    entries: List<AgentEntry>,
    defaultId: String,
    colors: Map<String, String> = emptyMap(),
) {

    private val _colors: MutableMap<String, String> = LinkedHashMap(colors)

    /** The chosen colors by agent id, the removed agents' included (see [AgentColors]). */
    val colors: Map<String, String> get() = _colors

    private val _entries: MutableList<AgentEntry> = entries.distinctBy { it.id }.map { it.copy() }.toMutableList()

    /** The agents, in the order they are listed; copies, so editing them never touches the settings. */
    val entries: List<AgentEntry> get() = _entries

    var defaultId: String = resolveDefault(defaultId)
        private set

    val size: Int get() = _entries.size

    /** `false` for the last agent: a tab always needs one to run. */
    val canRemove: Boolean get() = _entries.size > 1

    fun isDefault(index: Int): Boolean = _entries.getOrNull(index)?.id == defaultId

    fun contains(agentId: String): Boolean = _entries.any { it.id == agentId }

    /** The registry entries of [available] that can still be added, in their registry order. */
    fun addable(available: List<AcpAgent>): List<AcpAgent> = available.filterNot { contains(it.id) }

    /** Appends [agent] and returns its index; `-1` when it is already enabled. */
    fun add(agent: AcpAgent): Int {
        if (contains(agent.id)) return -1
        _entries += AgentEntry.of(agent)
        return _entries.lastIndex
    }

    /** Removes the agent at [index]; refused for the last one (see [canRemove]). */
    fun remove(index: Int): Boolean {
        if (!canRemove || index !in _entries.indices) return false
        _entries.removeAt(index)
        defaultId = resolveDefault(defaultId)
        return true
    }

    fun setDefault(index: Int) {
        _entries.getOrNull(index)?.let { defaultId = it.id }
    }

    /**
     * Takes what the selector holds for the agent at [index] (a newer version, a typed command).
     * The drawing stays unless the registry entry now points at another file, which then has to
     * be downloaded again.
     */
    fun update(index: Int, agent: AcpAgent) {
        val entry = _entries.getOrNull(index) ?: return
        if (entry.id != agent.id) return
        val iconSvg = if (agent.iconUrl.orEmpty() == entry.iconUrl) entry.iconSvg else ""
        _entries[index] = AgentEntry.of(agent, iconSvg)
    }

    /** The color [agentId] is drawn in with these edits; `null` leaves its drawing as it is. */
    fun colorOf(agentId: String): String? = AgentColors.resolve(agentId, _colors)

    /** Gives [agentId] the color [hex]; `null`, or its default color, resets it to the default. */
    fun setColor(agentId: String, hex: String?) {
        val normalized = hex?.let { AgentColors.normalize(it) }
        if (normalized == null || normalized == AgentColors.defaultOf(agentId)) _colors.remove(agentId) else _colors[agentId] = normalized
    }

    /** Whether applying these edits would change [settings]. */
    fun differsFrom(settings: SandboxAgentSettingsState): Boolean {
        if (_colors != settings.agentColors) return true
        val stored = settings.enabledAgents()
        if (defaultId != settings.defaultAgent().id) return true
        if (stored.size != _entries.size) return true
        return stored.zip(_entries).any { (a, b) -> !a.agent().isSameSelection(b.agent()) }
    }

    /** The reason the agents cannot be applied, or `null` when they can (a binary agent without its command). */
    fun commandError(): String? = _entries.firstNotNullOfOrNull { entry ->
        val agent = entry.agent()
        if (!agent.launcher.isCustom) return@firstNotNullOfOrNull null
        AgentCommandValidator.validate(agent.customCommand)?.let { "${entry.nameOrDefault()}: $it" }
    }

    /**
     * Stores the edits. The drawings already in the settings are kept for the agents whose icon
     * file did not change; the others are downloaded by the dialog afterwards.
     */
    fun applyTo(settings: SandboxAgentSettingsState) {
        val stored = settings.enabledAgents().associateBy { it.id }
        val merged = _entries.map { entry ->
            val known = stored[entry.id]
            if (entry.iconSvg.isBlank() && known != null && known.iconUrl == entry.iconUrl) {
                entry.copy(iconSvg = known.iconSvg)
            } else {
                entry.copy()
            }
        }
        settings.updateAgents(merged, defaultId)
        settings.agentColors = LinkedHashMap(_colors)
    }

    private fun resolveDefault(id: String): String =
        id.takeIf { contains(it) } ?: _entries.firstOrNull()?.id.orEmpty()
}
