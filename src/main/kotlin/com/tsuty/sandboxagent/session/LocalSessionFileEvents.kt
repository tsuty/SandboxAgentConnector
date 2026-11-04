package com.tsuty.sandboxagent.session

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.io.File

/**
 * Tells the UI that local session files were moved aside or erased by the user (a backup deleted
 * from its Read Only tab, the backups of a session deleted permanently), so that every Read Only
 * tab showing one of them is closed — whichever tab or popup the deletion started from.
 */
object LocalSessionFileEvents {

    /** Fired on the EDT with the files as they were named before the deletion. */
    fun interface Listener {
        fun filesRemoved(files: Set<File>)
    }

    @JvmField
    val TOPIC: Topic<Listener> = Topic.create("Sandbox Agent Connector local session files", Listener::class.java)

    fun filesRemoved(project: Project, files: Collection<File>) {
        if (project.isDisposed || files.isEmpty()) return
        project.messageBus.syncPublisher(TOPIC).filesRemoved(files.map { it.absoluteFile }.toSet())
    }
}
