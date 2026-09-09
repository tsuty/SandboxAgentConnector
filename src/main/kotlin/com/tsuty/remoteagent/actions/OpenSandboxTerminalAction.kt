package com.tsuty.remoteagent.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.tsuty.remoteagent.ui.SandboxTerminal

/**
 * `Open Sandbox Terminal` of the options menu of the tool window: logs into the sandbox the agent
 * is attached through, with the configured login shell (see [SandboxTerminal]).
 */
class OpenSandboxTerminalAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        SandboxTerminal.open(e.project ?: return)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && SandboxTerminal.isAvailable()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        const val ID = "com.tsuty.remoteagent.actions.OpenSandboxTerminal"
    }
}
