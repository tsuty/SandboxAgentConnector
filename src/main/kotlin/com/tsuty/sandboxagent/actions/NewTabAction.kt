package com.tsuty.sandboxagent.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.tsuty.sandboxagent.ui.SandboxAgentTabsPanel

/**
 * `New Tab`: what `+` of the tab strip does — a tab with the default agent of the settings.
 * Registered without a shortcut, so it can be found with Find Action and bound in the Keymap.
 *
 * The tool window is activated first, and the panel looked up only once it is shown: it may not
 * have been created yet when the action is run from Find Action or a shortcut.
 */
class NewTabAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SandboxAgentTabsPanel.TOOL_WINDOW_ID) ?: return
        toolWindow.activate({ SandboxAgentTabsPanel.find(project)?.openNewTab() }, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
