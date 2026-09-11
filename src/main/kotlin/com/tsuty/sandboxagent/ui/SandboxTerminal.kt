package com.tsuty.sandboxagent.ui

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.tsuty.sandboxagent.acp.SandboxCommand
import com.tsuty.sandboxagent.settings.SandboxAgentSettingsState
import com.tsuty.sandboxagent.settings.SandboxTypes
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * Logs into the running sandbox in a terminal tab of the IDE (SPEC §3.3).
 *
 * The command is the attach command with a TTY and the configured login shell instead of the
 * agent (see [SandboxCommand.terminalArguments]), and it is *typed into* a local terminal tab
 * rather than started as a process of its own: that is what gives the shell a real terminal, and
 * it leaves the tab usable after the shell exits.
 *
 * The Terminal plugin is an optional dependency — it is bundled with every IDE but can be
 * switched off — so its absence has to end in a message instead of a stack trace.
 */
object SandboxTerminal {
    private val log = Logger.getInstance(SandboxTerminal::class.java)

    /** Whether a terminal can be opened at all, which is what the menu entry is enabled on. */
    fun isAvailable(): Boolean =
        PluginManagerCore.getPlugin(PluginId.getId(TERMINAL_PLUGIN_ID))?.isEnabled == true

    fun open(project: Project) {
        val settings = SandboxAgentSettingsState.getInstance(project)
        val command = try {
            SandboxCommand.terminalCommandLine(
                sandboxCommand = SandboxTypes.byId(settings.sandboxTypeId).command,
                sandboxName = settings.sandboxName.ifBlank { project.name },
                shellCommand = settings.loginShell().command,
            )
        } catch (e: IllegalArgumentException) {
            report(project, e.message ?: "The sandbox settings are incomplete")
            return
        }
        log.info("[sbx terminal] $command")
        if (!isAvailable()) {
            report(project, "The bundled Terminal plugin is disabled, so no terminal can be opened.")
            return
        }
        // Catches a `Throwable`: a Terminal plugin that changed its API surface must not take the
        // tool window down with it, and the command is in the log to run by hand.
        try {
            val widget = TerminalToolWindowManager.getInstance(project).createShellWidget(
                project.basePath,
                settings.sandboxName.ifBlank { project.name },
                true,
                true,
            )
            widget.sendCommandToExecute(command)
        } catch (e: Throwable) {
            log.warn("Cannot open a sandbox terminal", e)
            report(project, "Cannot open a terminal: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun report(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    /** Registered as an optional dependency in `plugin.xml`. */
    private const val TERMINAL_PLUGIN_ID = "org.jetbrains.plugins.terminal"

    const val NOTIFICATION_GROUP: String = "Sandbox Agent Connector"
}
