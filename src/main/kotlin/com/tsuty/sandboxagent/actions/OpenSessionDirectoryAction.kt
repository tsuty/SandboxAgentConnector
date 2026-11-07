package com.tsuty.sandboxagent.actions

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.tsuty.sandboxagent.session.PluginDataDirectories

/**
 * `Open Session Directory` of the options menu of the tool window: reveals the directory the
 * session files of this project are kept in (see [PluginDataDirectories]) in the OS file manager.
 */
class OpenSessionDirectoryAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RevealFileAction.openDirectory(PluginDataDirectories.sessions(project).apply { mkdirs() })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        const val ID = "com.tsuty.sandboxagent.actions.OpenSessionDirectory"
    }
}
