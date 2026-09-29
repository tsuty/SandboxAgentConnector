package com.tsuty.sandboxagent.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.tsuty.sandboxagent.ui.SandboxAgentTabsPanel

/**
 * `Search Local Sessions` of the options menu of the tool window: full text search over the
 * sessions stored locally (see `LocalSessionSearchPopup`).
 *
 * The tool window is activated first, and the panel looked up only once it is shown: it may not
 * have been created yet when the action is run from Find Action or a shortcut.
 */
class SearchLocalSessionsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SandboxAgentTabsPanel.TOOL_WINDOW_ID) ?: return
        toolWindow.activate({
            SandboxAgentTabsPanel.find(project)?.showLocalSessionSearch(toolWindow.component)
        }, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        const val ID = "com.tsuty.sandboxagent.actions.SearchLocalSessions"
    }
}
