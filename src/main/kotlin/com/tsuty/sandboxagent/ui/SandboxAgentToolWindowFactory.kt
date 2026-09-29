package com.tsuty.sandboxagent.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.tsuty.sandboxagent.actions.OpenCacheDirectoryAction
import com.tsuty.sandboxagent.actions.OpenSandboxAgentSettingsAction
import com.tsuty.sandboxagent.actions.OpenSandboxTerminalAction
import com.tsuty.sandboxagent.actions.SearchLocalSessionsAction

class SandboxAgentToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Ph2 §2/§4: the tool window content is the tabs container, not a single main panel any
        // more — each tab keeps its own sandbox process and ACP session alive independently.
        val panel = SandboxAgentTabsPanel(project)
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
     *
     * Order: `Search Local Sessions`, `Open Sandbox Terminal`, a separator, then `Settings` and
     * `Open Cache Directory` — the most used entries first, the settings/maintenance ones last.
     * The search lives here only (not on the tab bar, which the up to 10 tabs need the width of).
     */
    private fun installOptions(toolWindow: ToolWindow) {
        val working = listOfNotNull(
            action(SearchLocalSessionsAction.ID),
            action(OpenSandboxTerminalAction.ID),
        )
        val settings = listOfNotNull(
            action(OpenSandboxAgentSettingsAction.ID),
            action(OpenCacheDirectoryAction.ID),
        )
        val actions = if (working.isNotEmpty() && settings.isNotEmpty()) {
            working + Separator.getInstance() + settings
        } else {
            working + settings
        }
        if (actions.isEmpty()) return
        (toolWindow as? ToolWindowEx)?.setAdditionalGearActions(DefaultActionGroup(actions))
    }

    private fun action(id: String): AnAction? = ActionManager.getInstance().getAction(id)
}
