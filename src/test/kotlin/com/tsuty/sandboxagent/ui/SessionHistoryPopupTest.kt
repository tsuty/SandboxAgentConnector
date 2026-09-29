package com.tsuty.sandboxagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.PopupStep
import com.tsuty.sandboxagent.acp.SessionListEntry
import com.tsuty.sandboxagent.session.LocalSessionFile
import com.tsuty.sandboxagent.session.LocalSessionIndex
import com.tsuty.sandboxagent.session.LocalSessionKind
import java.awt.Color
import java.io.File
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Presentation checks of the session history dropdown. */
class SessionHistoryPopupTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `relative time is rendered in the compact history form`() {
        assertEquals("now", RelativeTime.format(now - 5_000, now))
        assertEquals("5m", RelativeTime.format(now - 5 * 60_000, now))
        assertEquals("3h", RelativeTime.format(now - 3 * 3_600_000, now))
        assertEquals("7d", RelativeTime.format(now - 7 * 86_400_000L, now))
        assertEquals("14d", RelativeTime.format(now - 14 * 86_400_000L, now))
        assertEquals("6mo", RelativeTime.format(now - 180 * 86_400_000L, now))
        assertEquals("1y", RelativeTime.format(now - 400 * 86_400_000L, now))
        // A clock skew must not produce a negative age.
        assertEquals("now", RelativeTime.format(now + 60_000, now))
    }

    @Test
    fun `a session without a reported update time has no age`() {
        assertNull(RelativeTime.format(null, now))
        assertNull(RelativeTime.format(0, now))
    }

    @Test
    fun `an entry without a title falls back to its session id`() {
        assertEquals("Fix the parser", SessionListEntry("sess_1", title = "Fix the parser").presentableTitle())
        assertEquals("sess_1", SessionListEntry("sess_1", title = "  ").presentableTitle())
        assertEquals("sess_1", SessionListEntry("sess_1").presentableTitle())
    }

    @Test
    fun `the popup step shows the title with its age`() {
        val entry = SessionListEntry("sess_1", title = "Fix the parser", updatedAt = now - 3 * 3_600_000)
        val step = SessionHistoryPopupStep(listOf(entry), onSelect = {}, now = now)

        assertEquals("Fix the parser", step.getTextFor(entry))
        assertEquals("3h", step.getSecondaryTextFor(entry))
        // The list holds sessions only: renaming and deleting live on the hover submenu of a row.
        assertEquals(listOf(entry), step.values)
    }

    @Test
    fun `the tooltip shows the full title then the session id in small muted text`() {
        val entry = SessionListEntry("sess_1", title = "A very long title that the row elides")

        val html = SessionHistoryPopupStep.tooltipHtml(entry, muted = Color(0x80, 0x80, 0x80))

        assertTrue(html.startsWith("<html>"), html)
        assertTrue(html.contains("A very long title that the row elides<br"), html)
        assertTrue(html.contains("<small>sess_1</small>"), html)
        assertTrue(html.contains("#808080"), html)
    }

    private fun file(id: String, kind: LocalSessionKind, timestamp: Long?, size: Long = 10) =
        LocalSessionFile(File("$id.json"), id, kind, timestamp, size)

    @Test
    fun `the row icon tells the current session and the local storage state apart`() {
        val index = LocalSessionIndex.fromFiles(
            listOf(
                file("local", LocalSessionKind.CURRENT, null),
                file("backed", LocalSessionKind.CURRENT, null),
                file("backed", LocalSessionKind.BACKUP, 1),
            ),
        )
        val step = SessionHistoryPopupStep(emptyList(), onSelect = {}, now = now, index = index, currentSessionId = "local")

        assertSame(AllIcons.Vcs.History, step.getIconFor(SessionListEntry("local")))
        assertSame(AllIcons.Nodes.CopyOfFolder, step.getIconFor(SessionListEntry("backed")))
        assertSame(AllIcons.Nodes.ExtractedFolder, step.getIconFor(SessionListEntry("remote")))
        val other = SessionHistoryPopupStep(emptyList(), onSelect = {}, now = now, index = index)
        assertSame(AllIcons.Nodes.Folder, other.getIconFor(SessionListEntry("local")))
    }

    @Test
    fun `every row but the current session has a hover submenu the platform does not know of`() {
        val current = SessionListEntry("current")
        val other = SessionListEntry("other")
        val submenu = SessionRowActionsStep(emptyList(), onAction = {}, onOpenBackup = {})
        var selected: SessionListEntry? = null
        val step = SessionHistoryPopupStep(
            listOf(current, other),
            onSelect = { selected = it },
            now = now,
            currentSessionId = "current",
            rowActions = { submenu },
        )

        // No substep reported: the platform would put a ⋮ / ▶ (and room for it) on such a row.
        assertFalse(step.hasSubstep(current))
        assertFalse(step.hasSubstep(other))
        assertNull(step.rowActionsFor(current))
        assertSame(submenu, step.rowActionsFor(other))
        // A click is a final choice and selects the session.
        assertTrue(step.isFinal(other))
        assertSame(PopupStep.FINAL_CHOICE, step.onChosen(other, true))
        assertEquals(other, selected)
    }

    @Test
    fun `the row submenu only offers backups when there are any`() {
        val without = SessionRowActionsStep(emptyList(), onAction = {}, onOpenBackup = {})
        val with = SessionRowActionsStep(listOf(file("sess_1", LocalSessionKind.BACKUP, 1)), onAction = {}, onOpenBackup = {})

        // No title: the hovered row right next to it already names the session.
        assertNull(without.title)
        assertEquals(listOf("Rename", "Delete"), without.values.map { without.getTextFor(it) })
        assertEquals(listOf("Rename", "Delete", "Backups"), with.values.map { with.getTextFor(it) })
        assertTrue(with.hasSubstep(SessionRowAction.BACKUPS))
        assertFalse(with.hasSubstep(SessionRowAction.RENAME))
        assertIs<BackupsStep>(with.onChosen(SessionRowAction.BACKUPS, false))
    }

    @Test
    fun `backups are listed newest first with their date and size in whole kilobytes`() {
        val older = file("sess_1", LocalSessionKind.BACKUP, 1_000L, size = 1)
        val newer = file("sess_1", LocalSessionKind.BACKUP, 2_000L, size = 2_049)
        val step = BackupsStep(listOf(older, newer)) {}

        // No title: the `Backups` item it opens from already names the list.
        assertNull(step.title)
        assertEquals(listOf(newer, older), step.values)
        val row = LocalSessionPresentation.backupRow(newer, ZoneOffset.UTC, Locale.US)
        assertEquals("${LocalSessionPresentation.dateTime(2_000L, ZoneOffset.UTC, Locale.US)} 3 KB", row)
        assertTrue(LocalSessionPresentation.backupRow(older, ZoneOffset.UTC, Locale.US).endsWith(" 1 KB"))
    }

    @Test
    fun `only the newest backups are listed`() {
        val backups = (1L..25L).map { file("sess_1", LocalSessionKind.BACKUP, it * 1_000L) }
        val step = BackupsStep(backups) {}

        assertEquals(BackupsStep.MAX_LISTED_BACKUPS, step.values.size)
        assertEquals(backups.reversed().take(BackupsStep.MAX_LISTED_BACKUPS), step.values)
    }

    @Test
    fun `the read only status names the kind and the date of the file`() {
        val backup = file("sess_1", LocalSessionKind.BACKUP, 2_000L)
        val deleted = file("sess_1", LocalSessionKind.DELETED, 2_000L)
        val date = LocalSessionPresentation.dateTime(2_000L, ZoneOffset.UTC, Locale.US)

        assertEquals("Backup · $date", LocalSessionPresentation.readOnlyStatus(backup, ZoneOffset.UTC, Locale.US))
        assertEquals("Deleted · $date", LocalSessionPresentation.readOnlyStatus(deleted, ZoneOffset.UTC, Locale.US))
    }

    @Test
    fun `choosing an entry selects it`() {
        val entry = SessionListEntry("sess_1")
        var selected: SessionListEntry? = null
        val step = SessionHistoryPopupStep(listOf(entry), onSelect = { selected = it }, now = now)

        step.onChosen(entry, true)

        assertEquals(entry, selected)
    }

    /**
     * The dropdown closes when the action list opens, so the session the actions apply to has to
     * be named by the action list itself — otherwise a deletion is done blind.
     */
    @Test
    fun `the action step names the session it acts on`() {
        val entry = SessionListEntry("sess_1", title = "Fix the parser")
        val step = SessionActions.SessionActionsStep(entry) {}

        assertEquals("Fix the parser", step.title)
        assertEquals(
            listOf("Rename", "Delete"),
            step.values.map { step.getTextFor(it) },
        )
    }

    /** A long session title must not blow the dropdown up past the panel it belongs to. */
    @Test
    fun `the dropdown is capped at the width of the plugin panel`() {
        assertEquals(300, SessionHistoryPopup.cellWidthFor(naturalWidth = 900, panelWidth = 300))
        // Already narrower than the panel: nothing to force.
        assertNull(SessionHistoryPopup.cellWidthFor(naturalWidth = 200, panelWidth = 300))
        assertNull(SessionHistoryPopup.cellWidthFor(naturalWidth = 300, panelWidth = 300))
        // A panel narrower than the minimum still leaves a readable row.
        assertEquals(160, SessionHistoryPopup.cellWidthFor(naturalWidth = 900, panelWidth = 40))
        // Not shown in the panel (no width yet): the natural width stands.
        assertNull(SessionHistoryPopup.cellWidthFor(naturalWidth = 900, panelWidth = 0))
    }

    @Test
    fun `the action step falls back to the session id when there is no title`() {
        val step = SessionActions.SessionActionsStep(SessionListEntry("sess_1")) {}

        assertEquals("sess_1", step.title)
    }
}
