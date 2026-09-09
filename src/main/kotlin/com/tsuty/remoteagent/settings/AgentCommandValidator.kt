package com.tsuty.remoteagent.settings

import com.tsuty.remoteagent.acp.SandboxCommand

/**
 * Checks a command typed for a [AgentLauncher.BINARY] agent before it is stored.
 *
 * This is not a safety net — whoever types here does it on purpose, and `sh -c` would walk around
 * any rule anyway. It is about what cannot work: the attach command is built as an argument vector
 * and handed to the OS, never to a shell (see [SandboxCommand]), so a pipe, a redirection or a
 * `$VAR` reaches the agent as a literal argument instead of doing anything. Written here, they
 * would leave the user with a command that starts and then misbehaves for no visible reason, so
 * they are refused with the reason spelled out.
 *
 * Quotes are the exception and stay allowed: a path inside the sandbox may hold spaces, and quotes
 * are how the argument vector is told about them. Only an unclosed one is refused, because
 * [SandboxCommand.toArguments] would silently split the command somewhere else than intended.
 */
object AgentCommandValidator {

    /** Long enough for an absolute path with arguments, short enough to be a typed line. */
    const val MAX_LENGTH: Int = 1000

    /** Meaningless without a shell, so refused rather than passed on as literal arguments. */
    private const val SHELL_CHARACTERS: String = "|&;<>()\$`"

    /**
     * The reason [command] cannot be started, or `null` when it can be.
     *
     * @return a message written for the settings dialog, ready to be shown as is.
     */
    fun validate(command: String): String? {
        if (command.isBlank()) return "Enter the command that starts the agent inside the sandbox."
        if (command.length > MAX_LENGTH) return "The command is longer than $MAX_LENGTH characters."
        if (command.any { it.isControlCharacter() }) {
            return "The command has to be one plain line: a line break or a tab would travel into " +
                "the stdio the agent speaks ACP over."
        }
        val shellCharacter = command.firstOrNull { it in SHELL_CHARACTERS }
        if (shellCharacter != null) {
            return "'$shellCharacter' does nothing here: the agent is started as an argument " +
                "list, not through a shell, so pipes, redirections and variables are handed to " +
                "the agent as literal text."
        }
        if (!command.hasClosedQuotes()) return "A quote is left open."
        if (SandboxCommand.toArguments(command).isEmpty()) return "The command has no executable."
        return null
    }

    /** Anything below the printable range, the tab and the line break included. */
    private fun Char.isControlCharacter(): Boolean = this < ' ' || this == '\u007F'

    /** Whether every `"` and `'` that opened is closed again, as `ParametersListUtil` reads them. */
    private fun String.hasClosedQuotes(): Boolean {
        var quote = ' '
        forEach {
            when {
                quote != ' ' -> if (it == quote) quote = ' '
                it == '"' || it == '\'' -> quote = it
            }
        }
        return quote == ' '
    }
}
