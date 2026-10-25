package com.tsuty.sandboxagent.settings

import com.intellij.util.xmlb.annotations.Tag

/**
 * One enabled agent of the settings (see [SandboxAgentSettingsState.agents]): the ACP registry
 * entry it was picked from, kept field by field instead of as a command line ([AcpAgent]), next to
 * the drawing that entry ships.
 *
 * The command is derived from the entry, and the version it holds is what makes a newer registry
 * entry an update the settings dialog offers rather than a silent change. [command] is the one
 * exception: it is typed for an agent that ships as a pre-built `binary` (see [AgentLauncher.BINARY])
 * and empty for every other launcher, which never reads it.
 *
 * [iconSvg] is the markup itself, and [iconUrl] only says which file it was downloaded from, so a
 * changed entry is noticed and downloaded again (see [AgentIcon]). The drawing is empty until it
 * was downloaded once, and the UI falls back to its own then.
 *
 * A data class with `var`s and defaults only, so the XML serializer of the platform can build it.
 */
@Tag("agent")
data class AgentEntry(
    var id: String = "",
    var packageName: String = "",
    var launcher: String = AgentLauncher.NPX.id,
    /** Arguments of the distribution, separated by spaces; the registry has none with a space. */
    var args: String = "",
    var version: String = "",
    var description: String = "",
    var command: String = "",
    var name: String = "",
    var iconUrl: String = "",
    var iconSvg: String = "",
) {
    /** Name shown for the agent, which is the one of the registry entry until there is none. */
    fun nameOrDefault(): String = name.ifBlank { id.ifBlank { DEFAULT_AGENT_NAME } }

    /** The stored drawing; `null` whenever the bundled one is to be used instead. */
    fun iconSvgOrNull(): String? = iconSvg.takeIf { it.isNotBlank() }

    /**
     * The agent as the settings dialog and the sandbox layer read it. The environment variables
     * of the registry entry are not part of it: they are a hint of the settings dialog and are
     * never persisted (see [AcpAgent.env]).
     */
    fun agent(): AcpAgent {
        val launcher = AgentLauncher.of(launcher)
        return AcpAgent(
            id = id,
            name = nameOrDefault(),
            packageName = packageName,
            launcher = launcher,
            args = args.split(' ').filter { it.isNotBlank() },
            version = version,
            description = description,
            iconUrl = iconUrl.takeIf { it.isNotBlank() },
            customCommand = command,
        )
    }

    companion object {
        /** Shown when an entry holds no name, which nothing but a hand edited file produces. */
        const val DEFAULT_AGENT_NAME: String = "Agent"

        /** An entry for [agent], with the drawing given separately because it is downloaded. */
        fun of(agent: AcpAgent, iconSvg: String = ""): AgentEntry = AgentEntry(
            id = agent.id,
            packageName = agent.packageName,
            launcher = agent.launcher.id,
            args = agent.args.joinToString(" "),
            version = agent.version,
            description = agent.description,
            command = agent.customCommand,
            name = agent.name,
            iconUrl = agent.iconUrl.orEmpty(),
            iconSvg = iconSvg,
        )
    }
}
