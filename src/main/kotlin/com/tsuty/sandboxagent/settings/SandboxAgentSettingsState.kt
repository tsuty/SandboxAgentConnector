package com.tsuty.sandboxagent.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
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
     * The ACP registry entry of the selected agent, kept field by field instead of as a command
     * line ([AcpAgent]): the command is derived from it, and the version it holds is what makes a
     * newer registry entry an update the settings dialog offers rather than a silent change.
     */
    var agentId: String = AcpAgentRegistry.FALLBACK_AGENT.id
    var agentPackage: String = AcpAgentRegistry.FALLBACK_AGENT.packageName
    var agentLauncher: String = AcpAgentRegistry.FALLBACK_AGENT.launcher.id

    /** Arguments of the distribution, separated by spaces; the registry has none with a space. */
    var agentArgs: String = ""
    var agentVersion: String = ""
    var agentDescription: String = ""

    /**
     * The command typed for an agent that ships as a pre-built `binary`, and the one exception to
     * everything said above: there is no package to derive a command from, the executable was
     * installed into the sandbox by hand, and only its owner knows where. Empty for every other
     * launcher, which never reads it (see [AgentLauncher.BINARY]).
     */
    var agentCommand: String = ""

    /**
     * How the selected agent presents itself: the `name` and the `icon` of its registry entry,
     * kept here so the UI never has to reach the registry again (see [AgentIcon]). The icon is
     * stored as the markup itself, and [agentIconUrl] only says which file it was downloaded from,
     * so a changed entry is noticed and downloaded again.
     *
     * The drawing is empty until an agent was picked once, and the UI falls back to its own then.
     */
    var agentName: String = AcpAgentRegistry.FALLBACK_AGENT.name
    var agentIconUrl: String = ""
    var agentIconSvg: String = ""

    /** ACP `cwd` sent with `session/new`; empty means "use the local project root". */
    var remoteWorkspacePath: String = ""
    var restoreLastSession: Boolean = false
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

    /** Name shown for the agent, which is the one of the registry entry until there is none. */
    fun agentNameOrDefault(): String = agentName.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT_NAME

    /**
     * The persisted agent, as the settings dialog and the sandbox layer read it. The environment
     * variables of the registry entry are not part of it: they are a hint of the settings dialog
     * and are never persisted (see [AcpAgent.env]).
     */
    fun agent(): AcpAgent {
        val launcher = AgentLauncher.of(agentLauncher)
        return AcpAgent(
            id = agentId.ifBlank { AcpAgentRegistry.FALLBACK_AGENT.id },
            name = agentNameOrDefault(),
            // A typed command has no package, so falling back to the one of the default agent
            // would invent a package the selected entry never had.
            packageName = agentPackage.ifBlank {
                if (launcher.isCustom) "" else AcpAgentRegistry.FALLBACK_AGENT.packageName
            },
            launcher = launcher,
            args = agentArgs.split(' ').filter { it.isNotBlank() },
            version = agentVersion,
            description = agentDescription,
            iconUrl = agentIconUrl.takeIf { it.isNotBlank() },
            customCommand = agentCommand,
        )
    }

    /** Stores a picked agent. The drawing is not touched: it is downloaded separately. */
    fun setAgent(agent: AcpAgent) {
        agentId = agent.id
        agentPackage = agent.packageName
        agentLauncher = agent.launcher.id
        agentArgs = agent.args.joinToString(" ")
        agentVersion = agent.version
        agentDescription = agent.description
        agentName = agent.name
        agentCommand = agent.customCommand
    }

    /** The stored drawing of the agent; `null` whenever the bundled one is to be used instead. */
    fun agentIconSvgOrNull(): String? = agentIconSvg.takeIf { it.isNotBlank() }

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
    }

    /** Maps the persisted values onto the sandbox process layer configuration. */
    fun toSandboxConfig(): SandboxConfig = SandboxConfig(
        sandboxCommand = SandboxTypes.byId(sandboxTypeId).command,
        sandboxName = sandboxName,
        agentCommand = agent().command,
        remoteWorkspacePath = remoteWorkspacePath,
        restoreLastSession = restoreLastSession,
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

        /** Shown when the settings hold no name yet, which is what an old installation has. */
        const val DEFAULT_AGENT_NAME: String = "Agent"

        const val DEFAULT_TRANSCRIPT_FONT_SIZE: Int = 13
        const val MIN_TRANSCRIPT_FONT_SIZE: Int = 8
        const val MAX_TRANSCRIPT_FONT_SIZE: Int = 32

        fun getInstance(project: Project): SandboxAgentSettingsState =
            project.getService(SandboxAgentSettingsState::class.java)
    }
}
