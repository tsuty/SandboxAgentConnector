package com.tsuty.sandboxagent.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic
import com.tsuty.sandboxagent.service.SandboxAgentService

/**
 * Tells the UI that the settings of a project were applied.
 *
 * Nothing is pushed into the sandbox layer: every tab runs its own [SandboxAgentService], and each
 * one reads the settings of its agent again when it connects. [TOPIC] only says that they
 * changed, and whoever renders them (the drawing of an agent, the transcript font size, ...)
 * reads them again.
 */
object SandboxAgentSettingsBridge {

    /** Fired on the EDT after the settings of a project were applied. */
    fun interface Listener {
        fun settingsApplied()
    }

    @JvmField
    val TOPIC: Topic<Listener> = Topic.create("Sandbox Agent Connector settings", Listener::class.java)

    fun apply(project: Project) {
        if (project.isDisposed) return
        project.messageBus.syncPublisher(TOPIC).settingsApplied()
    }

    fun applyToOpenProjects() {
        ProjectManager.getInstance().openProjects.forEach(::apply)
    }
}
