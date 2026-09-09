package com.tsuty.remoteagent.ui

import com.tsuty.remoteagent.acp.SessionListEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        assertEquals("sess_1", step.getTooltipTextFor(entry))
        // The list holds sessions only: renaming and deleting live on the right click of a row.
        assertEquals(listOf(entry), step.values)
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

    @Test
    fun `the action step falls back to the session id when there is no title`() {
        val step = SessionActions.SessionActionsStep(SessionListEntry("sess_1")) {}

        assertEquals("sess_1", step.title)
    }
}
