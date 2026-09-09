package com.tsuty.remoteagent.settings

/**
 * The ways an ACP agent is started with a single command, and how that command is spelled.
 *
 * [NPX] and [UVX] spell the command themselves out of a registry package, so it is derived and
 * never stored. [BINARY] cannot: the executable is installed into the sandbox by hand — the plugin
 * downloads and unpacks nothing — so there the command is the one value the user types in.
 */
enum class AgentLauncher(val id: String, private val prefix: List<String>) {
    /** `-y` is mandatory: without it `npx` prompts on a cache miss and the prompt blocks stdio. */
    NPX("npx", listOf("npx", "-y")),

    /** `uvx` never prompts before running a package, so it has no `-y` counterpart to add. */
    UVX("uvx", listOf("uvx")),

    /**
     * A pre-built executable already sitting inside the sandbox. Its path there is known to
     * whoever installed it and to nobody else, so the command is typed instead of derived.
     */
    BINARY("binary", emptyList());

    /** Whether the command is typed in the settings rather than derived from a package. */
    val isCustom: Boolean get() = this == BINARY

    fun commandOf(packageName: String, args: List<String>): String =
        (prefix + packageName + args).joinToString(" ")

    companion object {
        /** The persisted id, degrading to [NPX] when it named a launcher that no longer exists. */
        fun of(id: String): AgentLauncher = entries.firstOrNull { it.id == id } ?: NPX
    }
}

/**
 * One entry of the ACP registry (see [AcpAgentRegistry]), reduced to what the plugin needs.
 *
 * For an `npx` / `uvx` entry the command is never stored as a line of text but derived from the
 * distribution, so a newer registry entry is a change of [version] and of [packageName] — which
 * usually pins that very version — and the settings can offer it as an update instead of silently
 * following it. A [AgentLauncher.BINARY] entry is the one exception: there [customCommand] *is*
 * the command, it belongs to the user, and no update is ever offered for it (see [AgentSelector]).
 */
data class AcpAgent(
    val id: String,
    val name: String,
    val packageName: String,
    val launcher: AgentLauncher = AgentLauncher.NPX,
    val args: List<String> = emptyList(),
    val version: String = "",
    val description: String = "",
    val iconUrl: String? = null,
    /** Typed by the user, and only read for [AgentLauncher.BINARY]. */
    val customCommand: String = "",
    /**
     * Environment variables the registry entry asks for. Shown as a hint and nothing more: the
     * plugin passes no environment into the sandbox, so they have to be set there beforehand.
     * Being registry data of the moment, it is not persisted and stays out of [isSameSelection].
     */
    val env: Map<String, String> = emptyMap(),
) {
    /** The command started inside the sandbox. */
    val command: String get() =
        if (launcher.isCustom) customCommand.trim() else launcher.commandOf(packageName, args)

    /**
     * Whether both describe the same selection, ignoring what the registry only lends for
     * display: [env] is never persisted, so a settings dialog that compared it would report
     * itself modified the moment the registry answers.
     */
    fun isSameSelection(other: AcpAgent): Boolean = copy(env = emptyMap()) == other.copy(env = emptyMap())
}
