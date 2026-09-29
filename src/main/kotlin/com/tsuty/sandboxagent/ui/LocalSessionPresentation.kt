package com.tsuty.sandboxagent.ui

import com.intellij.icons.AllIcons
import com.tsuty.sandboxagent.session.LocalSessionFile
import com.tsuty.sandboxagent.session.LocalSessionKind
import com.tsuty.sandboxagent.session.StorageState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.swing.Icon

/**
 * How the local session files are shown, shared by the session list, the Read Only tabs and the
 * local session search so the three read the same.
 */
internal object LocalSessionPresentation {

    /** `Backup · {date}` / `Deleted · {date}`, the status of a Read Only tab. */
    fun readOnlyStatus(file: LocalSessionFile, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val date = file.timestamp?.let { dateTime(it, zone, locale) }
        return listOfNotNull(kindLabel(file.kind), date).joinToString(" · ")
    }

    fun kindLabel(kind: LocalSessionKind): String = when (kind) {
        LocalSessionKind.CURRENT -> "Local"
        LocalSessionKind.BACKUP -> "Backup"
        LocalSessionKind.DELETED -> "Deleted"
    }

    /** An epoch millis timestamp in the medium, localized form of the user's locale. */
    fun dateTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** `{date} {n} KB`, a row of the backup list; the size is rounded up to whole kilobytes. */
    fun backupRow(file: LocalSessionFile, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val date = file.timestamp?.let { dateTime(it, zone, locale) }.orEmpty()
        return "$date ${file.sizeKb} KB".trim()
    }

    fun storageIcon(state: StorageState): Icon = when (state) {
        StorageState.LOCAL -> AllIcons.Nodes.Folder
        StorageState.LOCAL_WITH_BACKUP -> AllIcons.Nodes.CopyOfFolder
        StorageState.REMOTE_ONLY -> AllIcons.Nodes.ExtractedFolder
    }

    /** Icon of a search result: the storage icons for current / backup files, a bin for deleted ones. */
    fun kindIcon(kind: LocalSessionKind): Icon = when (kind) {
        LocalSessionKind.CURRENT -> AllIcons.Nodes.Folder
        LocalSessionKind.BACKUP -> AllIcons.Nodes.CopyOfFolder
        LocalSessionKind.DELETED -> AllIcons.Actions.GC
    }
}
