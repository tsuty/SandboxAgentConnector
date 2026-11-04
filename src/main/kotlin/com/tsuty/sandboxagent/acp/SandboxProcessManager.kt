package com.tsuty.sandboxagent.acp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Values used to attach to an already running sandbox.
 * They mirror SPEC §5 defaults; the persisted settings state overrides them at runtime.
 */
data class SandboxConfig(
    /** CLI that fronts the sandbox: `sbx`, `docker`, `container` or `wslc` (see `SandboxTypes`). */
    val sandboxCommand: String = "sbx",
    /** Sandbox to attach to. Empty falls back to the project name. */
    val sandboxName: String = "",
    val agentCommand: String = "npx -y @agentclientprotocol/claude-agent-acp",
    /** ACP `cwd`: the working directory **inside** the sandbox. Empty falls back to the project root. */
    val sandboxWorkspacePath: String = "",
    /**
     * Silence, in seconds, that ends the wait for `initialize`. The first run downloads the agent
     * through `npx`, which reports its progress on `stderr`, so only a complete lack of output
     * counts as a failure (see [AcpClient.initialize]).
     */
    val initializeIdleTimeoutSeconds: Int = 180,
)

/** Values the attach command is built from that do not come from the settings. */
data class SandboxVariables(
    val projectName: String,
    val projectRoot: String,
    val agentCommand: String,
)

/**
 * Builds the attach command line, whose shape is fixed: `<cli> exec -i <name> <agent command>`.
 *
 * Only sandbox CLIs that spell `exec` that way are offered by the settings, so nothing about the
 * command line is written by hand any more — the sandbox name is the one free value, and it is
 * passed as a single argument, never through a shell.
 *
 * A terminal login is the very same command with a TTY and a login shell instead of the agent
 * (see [terminalArguments]).
 */
object SandboxCommand {
    private const val EXEC = "exec"

    /** The agent talks ACP over stdio, so the sandbox has to keep stdin open. */
    private const val INTERACTIVE = "-i"

    /** A shell driven by a human needs a TTY, which a stdio pipe of the agent must not get. */
    private const val TTY = "-t"

    fun arguments(sandboxCommand: String, sandboxName: String, agentCommand: String): List<String> =
        exec(sandboxCommand, sandboxName, agentCommand, tty = false)

    /**
     * Argv of a terminal login: the very same attach shape, with a TTY added and the configured
     * login shell in place of the agent (see `LoginShells`).
     */
    fun terminalArguments(sandboxCommand: String, sandboxName: String, shellCommand: String): List<String> =
        exec(sandboxCommand, sandboxName, shellCommand, tty = true)

    /**
     * [terminalArguments] as one command line. A terminal tab is a shell that is typed into, not
     * an argv the plugin hands to the OS, so the arguments are quoted the way a shell reads them
     * back — the sandbox name is the one free value and may hold spaces.
     */
    fun terminalCommandLine(sandboxCommand: String, sandboxName: String, shellCommand: String): String =
        ParametersListUtil.join(terminalArguments(sandboxCommand, sandboxName, shellCommand))

    /** Splits a command line into an argv list, honouring quotes. */
    fun toArguments(command: String): List<String> =
        ParametersListUtil.parse(command, false, true).filter { it.isNotEmpty() }

    /**
     * The command line spelled out for the IDE log: the name is quoted, it may hold spaces. It is
     * never shown in the settings, where it would only be a long line nobody has to act on.
     */
    fun display(sandboxCommand: String, sandboxName: String, agentCommand: String): String =
        "${sandboxCommand.trim()} $EXEC $INTERACTIVE \"${sandboxName.trim()}\" ${agentCommand.trim()}"

    /** `<cli> exec -i [-t] <name> <command>`, the one shape every offered sandbox CLI shares. */
    private fun exec(sandboxCommand: String, sandboxName: String, command: String, tty: Boolean): List<String> {
        val cli = sandboxCommand.trim()
        require(cli.isNotEmpty()) { "Sandbox type has no command" }
        val name = sandboxName.trim()
        require(name.isNotEmpty()) { "Sandbox name is empty" }
        val arguments = toArguments(command)
        require(arguments.isNotEmpty()) { "Command to run in the sandbox is empty" }
        val flags = if (tty) listOf(INTERACTIVE, TTY) else listOf(INTERACTIVE)
        return listOf(cli, EXEC) + flags + name + arguments
    }
}

/** Callbacks for the separated process streams. */
interface SandboxProcessListener {
    /** Only `ProcessOutputType.STDOUT` content reaches this callback (ACP payload). */
    fun onStdout(chunk: String)

    fun onStderr(chunk: String) {}

    fun onTerminated(exitCode: Int) {}
}

/**
 * Attaches to an already running `sbx` sandbox and drives the ACP agent process inside it.
 * The sandbox lifecycle itself is not managed by the plugin.
 *
 * `stdout` is routed to the ACP parser only; `stderr` (e.g. `npx` progress) goes to the IDE log.
 */
class SandboxProcessManager(
    private val config: SandboxConfig,
    private val variables: SandboxVariables,
) {
    private val log = Logger.getInstance(SandboxProcessManager::class.java)

    @Volatile
    private var agentHandler: OSProcessHandler? = null

    val isRunning: Boolean
        get() = agentHandler?.isProcessTerminated == false

    /** The sandbox to attach to: the configured name, or the project when there is none. */
    val sandboxName: String get() = config.sandboxName.ifBlank { variables.projectName }

    /** First word of the agent command: what starts the agent, e.g. `npx` or `uvx`. */
    val launcher: String get() = SandboxCommand.toArguments(variables.agentCommand).firstOrNull().orEmpty()

    /**
     * Whether the sandbox CLI can be started at all, looked up the way [GeneralCommandLine] does:
     * a path as is, a bare name on the `PATH` — on macOS the one of the login shell, which an IDE
     * started from the Dock does not inherit.
     */
    fun sandboxCommandExists(): Boolean {
        val cli = config.sandboxCommand.trim()
        if (cli.isEmpty()) return false
        if (cli.contains('/') || cli.contains(File.separatorChar)) return File(cli).canExecute()
        if (SystemInfo.isMac) {
            val shellPath = EnvironmentUtil.getValue("PATH")
            if (shellPath != null && PathEnvironmentVariableUtil.findInPath(cli, shellPath, null) != null) return true
        }
        return PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS(cli) != null
    }

    /**
     * Runs an environment-agnostic probe through the attach command to confirm the sandbox name is
     * valid and the sandbox can be entered. It is bounded: a CLI may wait for a sign-in instead of
     * failing (`sbx` opens a browser and waits when signed out), and the output is kept, since the
     * CLI explains its own failure best.
     */
    fun healthCheck(): ProbeResult = probe(HEALTH_CHECK_COMMAND, HEALTH_CHECK_TIMEOUT_SECONDS)

    /**
     * Runs `<launcher> --help` in the sandbox, to tell a missing launcher from an agent that
     * failed on its own. Only run once the agent has already exited.
     */
    fun probeLauncher(): ProbeResult =
        probe(ParametersListUtil.join(listOf(launcher, LAUNCHER_PROBE_ARGUMENT)), LAUNCHER_PROBE_TIMEOUT_SECONDS)

    /** Starts `<cli> exec -i <name> ...` and wires the streams to [listener]. */
    fun startAgent(listener: SandboxProcessListener): OSProcessHandler {
        check(!isRunning) { "Agent process is already running" }
        val handler = OSProcessHandler(commandLine())
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                when (outputType) {
                    ProcessOutputType.STDOUT -> listener.onStdout(event.text)
                    ProcessOutputType.STDERR -> {
                        log.info("[sbx stderr] ${event.text.trimEnd()}")
                        listener.onStderr(event.text)
                    }
                    else -> Unit
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                agentHandler = null
                listener.onTerminated(event.exitCode)
            }
        })
        agentHandler = handler
        handler.startNotify()
        return handler
    }

    /** Writes a single JSON-RPC line to the agent's stdin. */
    fun writeLine(line: String) {
        val handler = agentHandler ?: throw IllegalStateException("Agent process is not running")
        val stream = handler.processInput
        stream.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
        stream.flush()
    }

    fun stopAgent() {
        agentHandler?.destroyProcess()
        agentHandler = null
    }

    fun commandLine(agentCommand: String = variables.agentCommand): GeneralCommandLine {
        val arguments = SandboxCommand.arguments(config.sandboxCommand, sandboxName, agentCommand)
        // The settings do not show it any more, so the log is where to read what was started.
        log.info("[sbx exec] " + SandboxCommand.display(config.sandboxCommand, sandboxName, agentCommand))
        return GeneralCommandLine(arguments)
            .withWorkDirectory(variables.projectRoot)
            .withCharset(StandardCharsets.UTF_8)
    }

    /**
     * Runs [command] through the attach command and waits at most [timeoutSeconds] for it. Its
     * stdin is closed right away, so a command that reads it sees the end instead of waiting.
     */
    private fun probe(command: String, timeoutSeconds: Long): ProbeResult {
        val output = AgentOutputTail()
        val handler = OSProcessHandler(commandLine(command))
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (!ProcessOutputType.isStdout(outputType) && !ProcessOutputType.isStderr(outputType)) return
                log.info("[sbx] ${event.text.trimEnd()}")
                output.append(event.text)
            }
        })
        handler.startNotify()
        runCatching { handler.processInput.close() }
        val finished = handler.waitFor(timeoutSeconds * 1000)
        if (!finished) {
            log.warn("[sbx] probe did not finish within ${timeoutSeconds}s")
            handler.destroyProcess()
        }
        output.flush()
        return ProbeResult(if (finished) handler.exitCode ?: -1 else null, output.tail(StartupDiagnosis.QUOTED_LINES))
    }

    companion object {
        /**
         * Fixed, environment-agnostic probe run in place of the agent for the health check.
         * `echo` is a shell builtin available in every sandbox, so no configuration is required.
         */
        const val HEALTH_CHECK_COMMAND: String = "echo sandbox-agent-connector"

        /** Bound of the health check; generous, since `sbx exec` first starts a stopped sandbox. */
        const val HEALTH_CHECK_TIMEOUT_SECONDS: Long = 60

        /** Argument the launcher probe passes; every launcher offered (`npx`, `uvx`, ...) knows it. */
        private const val LAUNCHER_PROBE_ARGUMENT = "--help"

        /** Bound of the launcher probe; `--help` returns at once, a hung launcher proves nothing. */
        private const val LAUNCHER_PROBE_TIMEOUT_SECONDS: Long = 15
    }
}
