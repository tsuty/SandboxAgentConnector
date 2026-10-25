package com.tsuty.sandboxagent.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.tsuty.sandboxagent.ui.SandboxAgentTabsPanel

/**
 * `New Tab with Agent…`: what the caret next to `+` does — the enabled agents in a popup that
 * can be driven with the keyboard, and a tab with the one picked. Registered without a shortcut,
 * so it can be found with Find Action and bound in the Keymap.
 */
class NewTabWithAgentAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SandboxAgentTabsPanel.TOOL_WINDOW_ID) ?: return
        toolWindow.activate({ SandboxAgentTabsPanel.find(project)?.showAgentChooser() }, true)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
