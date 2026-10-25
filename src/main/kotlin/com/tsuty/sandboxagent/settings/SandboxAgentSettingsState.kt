package com.tsuty.sandboxagent.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import com.tsuty.sandboxagent.acp.SandboxConfig

/**
 * Persisted plugin settings (SPEC §5). Stored per project so each project can have its own
 * sandbox configuration, agent command, and workspace path.
 */
@State(name = "SandboxAgentSettings", storages = [Storage("sandboxAgentConnector.xml")])
class SandboxAgentSettingsState : PersistentStateComponent<SandboxAgentSettingsState> {
    /** Id of the selected sandbox CLI ([SandboxTypes]); only its `exec` shape is ever used. */
    var sandboxTypeId: String = SandboxTypes.DEFAULT.id

    /** Sandbox to attach to; empty means "use the project name". */
    var sandboxName: String = ""

    /**
     * Id of the shell a sandbox terminal is logged into ([LoginShells]). Only the shell is picked:
     * the command around it is `<cli> exec -i -t <name>`, the very same attach shape the agent is
     * started with (see `SandboxCommand.terminalArguments`).
     */
    var loginShellId: String = LoginShells.DEFAULT.id

    /**
     * The enabled agents, in the order the settings dialog lists them; no registry id twice. A tab
     * runs one of them, and only theirs can be started (see [enabledAgents]).
     */
    @get:XCollection(style = XCollection.Style.v2)
    var agents: MutableList<AgentEntry> = mutableListOf()

    /** Registry id of the agent `+` opens a tab with (see [defaultAgent]). */
    var defaultAgentId: String = ""

    /*
     * The one agent of a version that ran a single agent, field by field. Only read to move it
     * into [agents] (see [loadState]); emptied right after, which keeps them out of the file.
     * Their defaults are empty instead of the fallback agent's for that very reason, and an empty
     * field stands for the fallback agent's value, which is what such a version stored as nothing.
     */
    var agentId: String = ""
    var agentPackage: String = ""
    var agentLauncher: String = ""
    var agentArgs: String = ""
    var agentVersion: String = ""
    var agentDescription: String = ""
    var agentCommand: String = ""
    var agentName: String = ""
    var agentIconUrl: String = ""
    var agentIconSvg: String = ""

    /**
     * Colors chosen for agents, `#RRGGBB` by the `id` of their registry entry (see [AgentColors]).
     * An agent without an entry is drawn in its default color, and one without a default the way
     * it always was, so a color that is reset is removed rather than stored.
     */
    var agentColors: MutableMap<String, String> = LinkedHashMap()

    /**
     * The agent the session snapshots written before they named their agent belong to (see
     * `SessionSnapshot.ownerAgentId`): the one agent this project ran when the plugin started
     * keeping it. Recorded once, by [loadState], and never changed afterwards.
     */
    var legacyAgentId: String = ""

    /** [legacyAgentId], or the default agent for a project whose settings were never stored. */
    fun legacyAgentIdOrDefault(): String = legacyAgentId.ifBlank { AcpAgentRegistry.FALLBACK_AGENT.id }

    /** ACP `cwd` sent with `session/new`; empty means "use the local project root". */
    var sandboxWorkspacePath: String = ""
    var autoAttachContext: Boolean = false

    /**
     * Silence, in seconds, that gives up on a starting agent. It is *not* a deadline: the wait
     * only ends when the agent produced no output at all for that long, so a slow `npx` download
     * stretches the wait instead of failing it.
     */
    var initializeIdleTimeoutSeconds: Int = DEFAULT_INITIALIZE_IDLE_TIMEOUT_SECONDS

    /**
     * Make the workspace read-only in the IDE while the agent runs a turn, so a local edit cannot
     * race the agent. Only the IDE is locked: the sandbox keeps writing to the files.
     */
    var lockEditorsDuringTurn: Boolean = true

    /**
     * Base font size of the transcript, in pixels. Every other size of the web view is derived
     * from it (see `ThemeTokens`), so this is the single knob the look of the transcript has.
     */
    var transcriptFontSize: Int = DEFAULT_TRANSCRIPT_FONT_SIZE

    /** The persisted size, kept inside the range the settings dialog offers. */
    fun transcriptFontSizeOrDefault(): Int =
        if (transcriptFontSize <= 0) DEFAULT_TRANSCRIPT_FONT_SIZE
        else transcriptFontSize.coerceIn(MIN_TRANSCRIPT_FONT_SIZE, MAX_TRANSCRIPT_FONT_SIZE)

    /** The persisted login shell, degrading to the default when it named an unknown one. */
    fun loginShell(): LoginShell = LoginShells.byId(loginShellId)

    /**
     * The enabled agents. Never empty: a project whose settings were never stored runs the
     * fallback agent, exactly like a version that ran a single agent did.
     */
    fun enabledAgents(): List<AgentEntry> = agents.ifEmpty { listOf(AgentEntry.of(AcpAgentRegistry.FALLBACK_AGENT)) }

    /** The agent `+` opens a tab with: the checked one, or the first when that is gone. */
    fun defaultAgent(): AgentEntry = enabledAgents().let { enabled -> enabled.firstOrNull { it.id == defaultAgentId } ?: enabled.first() }

    /** The enabled agent of [id]; `null` when it is not enabled (any more). */
    fun agentById(id: String): AgentEntry? = enabledAgents().firstOrNull { it.id == id }

    fun isEnabled(agentId: String): Boolean = agentById(agentId) != null

    /**
     * Replaces the enabled agents. A registry id listed twice keeps its first entry, and a
     * [defaultId] that is not among them falls back to the first agent (see [defaultAgent]).
     */
    fun updateAgents(entries: List<AgentEntry>, defaultId: String) {
        val unique = entries.distinctBy { it.id }
        agents = unique.map { it.copy() }.toMutableList()
        defaultAgentId = defaultId.takeIf { id -> unique.any { it.id == id } } ?: unique.firstOrNull()?.id.orEmpty()
    }

    /** Stores the drawing of the enabled agent [agentId]; a no-op for one that is not enabled. */
    fun updateAgentIcon(agentId: String, iconUrl: String, iconSvg: String) {
        if (agents.isEmpty()) agents = enabledAgents().toMutableList()
        agents.firstOrNull { it.id == agentId }?.let {
            it.iconUrl = iconUrl
            it.iconSvg = iconSvg
        }
    }

    /** The color [agentId] is drawn in; `null` leaves its drawing as it is. */
    fun agentColorOrNull(agentId: String): String? = AgentColors.resolve(agentId, agentColors)

    /** The persisted idle timeout, kept inside the range the settings dialog offers. */
    fun initializeIdleTimeoutSecondsOrDefault(): Int =
        if (initializeIdleTimeoutSeconds <= 0) DEFAULT_INITIALIZE_IDLE_TIMEOUT_SECONDS
        else initializeIdleTimeoutSeconds.coerceIn(
            MIN_INITIALIZE_IDLE_TIMEOUT_SECONDS,
            MAX_INITIALIZE_IDLE_TIMEOUT_SECONDS,
        )

    override fun getState(): SandboxAgentSettingsState = this

    override fun loadState(state: SandboxAgentSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
        // Stored settings without the legacy owner were written by a version that ran one agent
        // only: the snapshots of this project are that agent's.
        if (legacyAgentId.isBlank()) legacyAgentId = agentId.ifBlank { AcpAgentRegistry.FALLBACK_AGENT.id }
        if (agents.isEmpty()) {
            val legacy = legacyAgent()
            agents = mutableListOf(legacy)
            defaultAgentId = legacy.id
        }
        clearLegacyAgent()
    }

    /**
     * The agent of a version that ran a single agent. Such a version stored a value equal to its
     * default as nothing, and those defaults were the fallback agent's, which is what an empty
     * field is read as. A typed command has no package, so the fallback package is not invented
     * for it.
     */
    private fun legacyAgent(): AgentEntry {
        val fallback = AcpAgentRegistry.FALLBACK_AGENT
        val launcher = AgentLauncher.of(agentLauncher.ifBlank { fallback.launcher.id })
        return AgentEntry(
            id = agentId.ifBlank { fallback.id },
            packageName = agentPackage.ifBlank { if (launcher.isCustom) "" else fallback.packageName },
            launcher = launcher.id,
            args = agentArgs,
            version = agentVersion,
            description = agentDescription,
            command = agentCommand,
            name = agentName.ifBlank { fallback.name },
            iconUrl = agentIconUrl,
            iconSvg = agentIconSvg,
        )
    }

    private fun clearLegacyAgent() {
        agentId = ""
        agentPackage = ""
        agentLauncher = ""
        agentArgs = ""
        agentVersion = ""
        agentDescription = ""
        agentCommand = ""
        agentName = ""
        agentIconUrl = ""
        agentIconSvg = ""
    }

    /** Maps the persisted values onto the sandbox process layer configuration of [agent]. */
    fun toSandboxConfig(agent: AcpAgent = defaultAgent().agent()): SandboxConfig = SandboxConfig(
        sandboxCommand = SandboxTypes.byId(sandboxTypeId).command,
        sandboxName = sandboxName,
        agentCommand = agent.command,
        sandboxWorkspacePath = sandboxWorkspacePath,
        initializeIdleTimeoutSeconds = initializeIdleTimeoutSecondsOrDefault(),
    )

    companion object {
        /**
         * Generous on purpose: `npx` can stay silent for a while between two progress lines, and
         * the wait is idle based, so a high value costs nothing when the agent is simply slow.
         */
        const val DEFAULT_INITIALIZE_IDLE_TIMEOUT_SECONDS: Int = 180
        const val MIN_INITIALIZE_IDLE_TIMEOUT_SECONDS: Int = 10
        const val MAX_INITIALIZE_IDLE_TIMEOUT_SECONDS: Int = 1800

        const val DEFAULT_TRANSCRIPT_FONT_SIZE: Int = 13
        const val MIN_TRANSCRIPT_FONT_SIZE: Int = 8
        const val MAX_TRANSCRIPT_FONT_SIZE: Int = 32

        fun getInstance(project: Project): SandboxAgentSettingsState =
            project.getService(SandboxAgentSettingsState::class.java)
    }
}
