package com.tsuty.sandboxagent.acp

/**
 * Outcome of a short command run through the attach command in place of the agent.
 *
 * [exitCode] is `null` when the command did not finish in time and was killed; [output] holds its
 * last lines, `stdout` and `stderr` alike.
 */
data class ProbeResult(val exitCode: Int?, val output: List<String>) {
    val timedOut: Boolean get() = exitCode == null
    val succeeded: Boolean get() = exitCode == 0
}

/** A failed start whose [message] already tells what went wrong and what to do about it. */
class SandboxStartupException(message: String) : RuntimeException(message)

/**
 * Turns the checks of a failed start into a readable message. Only steps every sandbox CLI and
 * every agent share are relied on — the CLI exists, `exec` runs `echo`, the launcher of the agent
 * command runs — and what the CLI or the agent printed is quoted as is: they explain their own
 * failure far better than any guess made here.
 */
object StartupDiagnosis {
    /** Lines of output quoted in a message; enough for an npm error, short enough to read. */
    const val QUOTED_LINES: Int = 5

    /** Exit codes a shell or an OCI runtime uses for a command that cannot be found or run. */
    private val COMMAND_NOT_FOUND_EXIT_CODES = setOf(126, 127)

    /** How a missing command reads in a shell, in `execvp` and in the OCI runtimes. */
    private val COMMAND_NOT_FOUND = Regex(
        "not found|no such file or directory|executable file not found",
        RegexOption.IGNORE_CASE,
    )

    fun sandboxCommandMissing(sandboxCommand: String): String =
        "Sandbox command `$sandboxCommand` was not found on the PATH. " +
            "Install it, or choose another sandbox type in the settings."

    /** The `exec … echo` probe failed: the sandbox name is wrong, or the sandbox cannot be entered. */
    fun sandboxUnreachable(
        sandboxCommand: String,
        sandboxName: String,
        probe: ProbeResult,
        timeoutSeconds: Long,
    ): String = buildString {
        append("Cannot reach sandbox \"$sandboxName\": `$sandboxCommand exec` ")
        if (probe.timedOut) {
            append("did not finish within ${timeoutSeconds}s. ")
            append("The sandbox may still be starting, or `$sandboxCommand` may be waiting for a sign-in.")
        } else {
            append("exited with code ${probe.exitCode}. ")
            append("Check that a sandbox with this name exists and is running.")
        }
        appendOutput(probe.output, "Output of `$sandboxCommand`:")
    }

    /**
     * Whether the `<launcher> --help` probe shows the launcher is missing. A probe that succeeded,
     * timed out or failed in some other way proves nothing, and the agent output is left to speak.
     */
    fun launcherMissing(probe: ProbeResult): Boolean {
        val exitCode = probe.exitCode ?: return false
        if (exitCode == 0) return false
        return exitCode in COMMAND_NOT_FOUND_EXIT_CODES || probe.output.any { COMMAND_NOT_FOUND.containsMatchIn(it) }
    }

    /**
     * The agent process ended before the connection was up. [launcherProbe] is the `--help` probe
     * of [launcher], or `null` when it was not run.
     */
    fun agentExited(
        sandboxName: String,
        launcher: String,
        exitCode: Int,
        launcherProbe: ProbeResult?,
        output: List<String>,
    ): String = buildString {
        if (launcherProbe != null && launcherMissing(launcherProbe)) {
            append("Cannot start the agent: `$launcher` was not found in sandbox \"$sandboxName\". ")
            append("Install it in the sandbox, or change the agent command.\n")
        }
        append("The agent exited with code $exitCode before the connection was established.")
        appendOutput(output, "Last agent output:")
    }

    /** `initialize` saw no output at all for the idle timeout. */
    fun agentSilent(reason: String, output: List<String>): String = buildString {
        append(reason)
        append(". The agent may not have finished downloading: retry (the download is cached), ")
        append("pre-install the agent in the VM, or raise \"Agent startup idle timeout\" in the settings.")
        appendOutput(output, "Last agent output:")
    }

    private fun StringBuilder.appendOutput(output: List<String>, heading: String) {
        val quoted = output.takeLast(QUOTED_LINES)
        if (quoted.isNotEmpty()) append(quoted.joinToString("\n", prefix = "\n$heading\n"))
    }
}
