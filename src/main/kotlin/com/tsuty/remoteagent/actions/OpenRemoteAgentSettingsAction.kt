package com.tsuty.remoteagent.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.tsuty.remoteagent.settings.RemoteAgentSettingsConfigurable

/**
 * `Settings` of the options menu of the tool window: opens the settings page of the plugin
 * (`Settings > Tools > Remote Agent Connector`) directly, instead of leaving it to be found.
 */
class OpenRemoteAgentSettingsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, RemoteAgentSettingsConfigurable::class.java)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        const val ID = "com.tsuty.remoteagent.actions.OpenRemoteAgentSettings"
    }
}
