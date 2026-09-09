package com.tsuty.remoteagent.settings

/**
 * One shell the sandbox terminal can be logged into, reduced to the command that starts it.
 *
 * The command is not editable as text for the same reason the attach command is not (see
 * [SandboxTypes]): a terminal is opened by running it inside an already running sandbox, and only
 * shells every sandbox image is likely to ship are offered.
 */
data class LoginShell(val id: String, val label: String, val command: String)

object LoginShells {
    val BASH = LoginShell("bash", "bash (/bin/bash)", "/bin/bash -l")

    val ALL: List<LoginShell> = listOf(
        BASH,
        LoginShell("zsh", "zsh (/bin/zsh)", "/bin/zsh -l"),
        LoginShell("ash", "ash (/bin/ash)", "/bin/ash -l"),
        // The POSIX shell of a minimal image has no `-l`, so it is started as it is.
        LoginShell("sh", "sh (/bin/sh)", "/bin/sh"),
    )

    /** Default shell (SPEC §5): a login `bash`. */
    val DEFAULT: LoginShell get() = BASH

    /** The persisted id, degrading to [DEFAULT] when it named a shell that is no longer offered. */
    fun byId(id: String): LoginShell = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
