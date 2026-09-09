package com.tsuty.remoteagent.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic
import com.tsuty.remoteagent.service.RemoteAgentService

/**
 * Pushes the persisted settings into the sandbox layer by assigning
 * [RemoteAgentService.config]. Applied when the service starts and whenever the
 * settings dialog is applied.
 *
 * Settings the UI reads instead of the sandbox (the transcript font size is one) are not pushed
 * anywhere: [TOPIC] only says that they changed, and whoever renders them reads them again.
 */
object RemoteAgentSettingsBridge {

    /** Fired on the EDT after the settings of a project were applied. */
    fun interface Listener {
        fun settingsApplied()
    }

    @JvmField
    val TOPIC: Topic<Listener> = Topic.create("Remote Agent Connector settings", Listener::class.java)

    fun apply(project: Project) {
        if (project.isDisposed) return
        project.getServiceIfCreated(RemoteAgentService::class.java)?.let { service ->
            service.config = RemoteAgentSettingsState.getInstance(project).toSandboxConfig()
        }
        project.messageBus.syncPublisher(TOPIC).settingsApplied()
    }

    fun applyToOpenProjects() {
        ProjectManager.getInstance().openProjects.forEach(::apply)
    }
}
