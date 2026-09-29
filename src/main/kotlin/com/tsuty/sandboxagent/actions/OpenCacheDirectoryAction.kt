package com.tsuty.sandboxagent.actions

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.tsuty.sandboxagent.session.SessionSnapshotStore

/**
 * `Open cache directory` of the options menu of the tool window: reveals the directory the
 * session snapshots of this project are cached in (see [SessionSnapshotStore.directoryFor]) in
 * the OS file manager.
 */
class OpenCacheDirectoryAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RevealFileAction.openDirectory(SessionSnapshotStore.directoryFor(project))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        const val ID = "com.tsuty.sandboxagent.actions.OpenCacheDirectory"
    }
}
