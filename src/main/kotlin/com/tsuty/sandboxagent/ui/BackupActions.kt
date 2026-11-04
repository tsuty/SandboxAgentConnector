package com.tsuty.sandboxagent.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.tsuty.sandboxagent.session.LocalSessionFile
import com.tsuty.sandboxagent.session.LocalSessionFileEvents
import com.tsuty.sandboxagent.session.LocalSessionKind
import com.tsuty.sandboxagent.session.SessionSnapshotStore

/**
 * Deleting backups, which only the user ever does: one backup is moved aside from its Read Only
 * tab ([deleteBackup]), and the backups of a session are erased for good from its row submenu
 * ([deleteBackupsPermanently]). Both ask first, touch the disk off the EDT and then close every
 * Read Only tab showing a file that went away (see [LocalSessionFileEvents]).
 */
object BackupActions {

    /** Asks, then moves [backup] aside as a deleted backup; `true` when the user confirmed. */
    fun deleteBackup(project: Project, backup: LocalSessionFile): Boolean {
        val answer = Messages.showYesNoDialog(
            project,
            "Delete this backup?\nIt stays readable through Search Local Sessions with deleted sessions included.",
            "Delete Backup",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return false
        ApplicationManager.getApplication().executeOnPooledThread {
            val moved = SessionSnapshotStore.forProject(project).moveBackupToDeleted(backup.file)
            onEdt(project) {
                if (moved != null) {
                    LocalSessionFileEvents.filesRemoved(project, listOf(backup.file))
                } else {
                    notifyError(project, "Cannot delete the backup ${backup.file.name}.")
                }
            }
        }
        return true
    }

    /**
     * Asks, then erases the backups and deleted backups of [sessionId] (titled [title]); `true`
     * when the user confirmed. The directory is read again right before the confirmation, and
     * exactly the files it counts are erased: a backup made while it is shown is kept. `Cancel` is
     * the default, since nothing brings the files back.
     */
    fun deleteBackupsPermanently(project: Project, sessionId: String, title: String): Boolean {
        val store = SessionSnapshotStore.forProject(project)
        val files = store.index().permanentlyDeletableBackups(sessionId)
        if (files.isEmpty()) return false
        val answer = Messages.showDialog(
            project,
            permanentDeletionMessage(title, files),
            "Delete Backups Permanently",
            arrayOf("Delete Permanently", Messages.getCancelButton()),
            1,
            Messages.getWarningIcon(),
        )
        if (answer != 0) return false
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = store.deleteBackupsPermanently(sessionId, files.map { it.file })
            onEdt(project) {
                LocalSessionFileEvents.filesRemoved(project, result.deleted)
                if (result.failed.isNotEmpty()) {
                    notifyError(project, "Cannot delete ${countOf(result.failed.size, "backup")} of \"$title\"; see the IDE log.")
                }
            }
        }
        return true
    }

    /** `Permanently delete 3 backups of "title" (1 of them already deleted)? …` */
    internal fun permanentDeletionMessage(title: String, files: List<LocalSessionFile>): String {
        val alreadyDeleted = files.count { it.kind == LocalSessionKind.DELETED_BACKUP }
        val aside = if (alreadyDeleted > 0) " ($alreadyDeleted of them already deleted)" else ""
        return "Permanently delete ${countOf(files.size, "backup")} of \"$title\"$aside?\nThis cannot be undone."
    }

    private fun countOf(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    private fun notifyError(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(SandboxTerminal.NOTIFICATION_GROUP)
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    private fun onEdt(project: Project, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) action() }
    }
}
