package com.tsuty.remoteagent.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.tsuty.remoteagent.actions.OpenRemoteAgentSettingsAction
import com.tsuty.remoteagent.actions.OpenSandboxTerminalAction

class RemoteAgentToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RemoteAgentMainPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        installOptions(toolWindow)
    }

    /**
     * Adds the entries of the plugin to the `Options` menu of the tool window header, above the
     * ones the platform puts there (`Show Toolbar`, the view modes, ...): the sandbox terminal and
     * the settings page are reached from the tool window they belong to instead of being hunted
     * for in the menus of the IDE.
     */
    private fun installOptions(toolWindow: ToolWindow) {
        val actions = listOfNotNull(
            action(OpenSandboxTerminalAction.ID),
            action(OpenRemoteAgentSettingsAction.ID),
        )
        if (actions.isEmpty()) return
        (toolWindow as? ToolWindowEx)?.setAdditionalGearActions(DefaultActionGroup(actions))
    }

    private fun action(id: String): AnAction? = ActionManager.getInstance().getAction(id)
}
