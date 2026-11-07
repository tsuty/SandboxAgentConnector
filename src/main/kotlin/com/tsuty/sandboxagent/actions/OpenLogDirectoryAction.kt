package com.tsuty.sandboxagent.actions

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.tsuty.sandboxagent.session.PluginDataDirectories

/**
 * `Open Log Directory` of the options menu of the tool window: reveals the directory the ACP
 * traffic logs of this project are kept in (see [PluginDataDirectories]) in the OS file manager.
 * They are not found through `Help > Show Log`, which opens the log directory of the IDE.
 */
class OpenLogDirectoryAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RevealFileAction.openDirectory(PluginDataDirectories.logs(project).apply { mkdirs() })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        const val ID = "com.tsuty.sandboxagent.actions.OpenLogDirectory"
    }
}
