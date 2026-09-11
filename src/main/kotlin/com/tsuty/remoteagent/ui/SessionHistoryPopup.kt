package com.tsuty.remoteagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStepEx
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.util.ui.StatusText
import com.tsuty.remoteagent.acp.SessionListEntry
import com.tsuty.remoteagent.service.RemoteAgentService
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * "How long ago" indicator of the session history: `now`, `5m`, `3h`, `7d`, `6mo`, `1y`.
 *
 * Sessions are listed by their last update, and the compact form keeps the dropdown narrow.
 */
object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val MONTH = 30 * DAY
    private const val YEAR = 365 * DAY

    /** `null` when the agent does not report a last update time for the session. */
    fun format(timestamp: Long?, now: Long = System.currentTimeMillis()): String? {
        if (timestamp == null || timestamp <= 0) return null
        val elapsed = (now - timestamp).coerceAtLeast(0)
        return when {
            elapsed < MINUTE -> "now"
            elapsed < HOUR -> "${elapsed / MINUTE}m"
            elapsed < DAY -> "${elapsed / HOUR}h"
            elapsed < MONTH -> "${elapsed / DAY}d"
            elapsed < YEAR -> "${elapsed / MONTH}mo"
            else -> "${elapsed / YEAR}y"
        }
    }
}

/** Title shown for a session: the local rename, the agent title, or the raw id as a last resort. */
fun SessionListEntry.presentableTitle(): String = title?.takeIf { it.isNotBlank() } ?: sessionId

class SessionHistoryPopupStep(
    private val entries: List<SessionListEntry>,
    private val onSelect: (SessionListEntry) -> Unit,
    private val now: Long = System.currentTimeMillis(),
) : BaseListPopupStep<SessionListEntry>("Sessions", entries), ListPopupStepEx<SessionListEntry> {

    override fun getTextFor(value: SessionListEntry): String = value.presentableTitle()

    override fun getSecondaryTextFor(value: SessionListEntry): String? =
        RelativeTime.format(value.lastUpdatedMillis, now)

    override fun getIconFor(value: SessionListEntry): Icon = AllIcons.Vcs.History

    override fun getTooltipTextFor(value: SessionListEntry): String = value.sessionId

    override fun setEmptyText(emptyText: StatusText) {
        emptyText.text = "No sessions reported by the agent"
    }

    override fun isSelectable(value: SessionListEntry): Boolean = true

    override fun onChosen(selectedValue: SessionListEntry, finalChoice: Boolean): PopupStep<*>? {
        if (finalChoice) onSelect(selectedValue)
        return FINAL_CHOICE
    }
}

/**
 * History dropdown of a tab's session title (`RemoteAgentTabsPanel.TabChip`), following the same
 * "click the label, get a list popup" pattern as [AttachFilePopup].
 *
 * The picked session is restored into the very tab the dropdown was opened from: the service it is
 * shown for is the one of that tab, not a shared one (Ph2 §2 — one service, and one sandbox
 * process, per tab).
 *
 * `Rename` / `Delete` live on the right click of a row instead of a management screen of their
 * own: both act on a single session, so the list the user already picks from is the place to act.
 */
object SessionHistoryPopup {

    /**
     * Rows the dropdown is at most tall; everything beyond is scrolled to.
     *
     * The history only ever grows, so an unbounded list ends up spanning the whole screen and
     * turns the recent sessions — the ones actually picked — into a needle in a haystack. No entry
     * is dropped from the list: it is the height that is capped, not the number of sessions.
     */
    private const val MAX_VISIBLE_ROWS = 20

    /**
     * Width the dropdown never goes below, even in a tool window narrower than this.
     *
     * The plugin panel can be dragged down to a few dozen pixels, and clamping the list to that
     * would leave the titles unreadable, so the panel is the cap only while it is wide enough to
     * read a row in.
     */
    private const val MIN_WIDTH = 160

    fun show(anchor: JComponent, project: Project, service: RemoteAgentService) {
        // The popup shows the list the service already holds and refreshes it in the background,
        // so opening the dropdown never blocks on a `session/list` round trip.
        service.refreshSessions()
        val entries = service.sessions.sortedByDescending { it.lastUpdatedMillis ?: 0 }
        val step = SessionHistoryPopupStep(entries, onSelect = { service.selectSession(it) })
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        (popup as? ListPopupImpl)?.let { listPopup ->
            listPopup.setMaxRowCount(MAX_VISIBLE_ROWS)
            listPopup.list?.let { list ->
                clampWidth(list, pluginPanelWidth(anchor))
                installRowActions(list, popup, anchor, project, service, entries)
            }
        }
        popup.showUnderneathOf(anchor)
    }

    /**
     * Caps the list at the width of the plugin panel: a single long session title would otherwise
     * blow the dropdown up across the editor, far past the panel it belongs to.
     *
     * The cap is applied as the fixed cell width, which is what a [JList] derives its preferred
     * width from; titles that do not fit are elided by the row renderer, and the full one stays
     * available as the row tooltip ([SessionHistoryPopupStep.getTooltipTextFor]).
     */
    private fun clampWidth(list: JList<*>, panelWidth: Int) {
        val width = cellWidthFor(list.preferredSize.width, panelWidth) ?: return
        list.fixedCellWidth = width
    }

    /** The width to force on a row, or `null` when the list already fits the panel. */
    internal fun cellWidthFor(naturalWidth: Int, panelWidth: Int): Int? {
        if (panelWidth <= 0) return null
        val limit = maxOf(panelWidth, MIN_WIDTH)
        return limit.takeIf { naturalWidth > it }
    }

    /**
     * Width of the tool window content the [anchor] sits in, or `0` when it is not shown in one
     * (which leaves the dropdown at its natural width).
     */
    private fun pluginPanelWidth(anchor: JComponent): Int {
        var component: Container? = anchor
        while (component != null) {
            if (component is RemoteAgentTabsPanel) return component.width
            component = component.parent
        }
        return 0
    }

    private fun installRowActions(
        list: JList<*>,
        popup: JBPopup,
        anchor: JComponent,
        project: Project,
        service: RemoteAgentService,
        entries: List<SessionListEntry>,
    ) {
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = maybeShowActions(event)

            override fun mouseReleased(event: MouseEvent) = maybeShowActions(event)

            private fun maybeShowActions(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                val row = list.locationToIndex(event.point).takeIf { it >= 0 } ?: return
                if (list.getCellBounds(row, row)?.contains(event.point) != true) return
                val entry = entries.getOrNull(row) ?: return
                event.consume()

                // The row is selected before anything else so the session the actions apply to
                // stays visible while the dropdown is still on screen.
                list.selectedIndex = row
                val location = event.locationOnScreen

                // The dropdown is a window of its own and closes as soon as the action menu takes
                // the focus, so it is dismissed on purpose. The action menu carries the session
                // title as its own title, which is what keeps the target unambiguous — a plain
                // context menu left the user guessing which session was about to be deleted.
                popup.cancel()
                SessionActions.showActions(project, service, entry, location) {
                    show(anchor, project, service)
                }
            }
        })
    }
}

/**
 * `Rename` / `Delete` of one session, shared by the rows of the session dropdown and the right
 * click of a tab. `Rename` is local only (ACP has no counterpart), `Delete` asks for a
 * confirmation and always removes the local information, even when the agent cannot delete its
 * own session.
 */
object SessionActions {

    enum class SessionAction(val title: String, val icon: Icon) {
        RENAME("Rename", AllIcons.Actions.Edit),
        DELETE("Delete", AllIcons.Actions.GC),
    }

    /**
     * Action list of a single session. The title of the popup is the session title, so the target
     * of `Rename` / `Delete` is named on screen even though the session dropdown had to close.
     */
    class SessionActionsStep(
        entry: SessionListEntry,
        private val onChosenAction: (SessionAction) -> Unit,
    ) : BaseListPopupStep<SessionAction>(entry.presentableTitle(), SessionAction.entries.toList()) {

        override fun getTextFor(value: SessionAction): String = value.title

        override fun getIconFor(value: SessionAction): Icon = value.icon

        override fun onChosen(selectedValue: SessionAction, finalChoice: Boolean): PopupStep<*>? {
            // The dialogs of both actions are modal, so they are opened once this popup is gone.
            if (finalChoice) doFinalStep { onChosenAction(selectedValue) }
            return FINAL_CHOICE
        }
    }

    /** Shows the action list at [screenLocation], which is where the user right clicked. */
    fun showActions(
        project: Project,
        service: RemoteAgentService,
        entry: SessionListEntry,
        screenLocation: Point,
        onChanged: () -> Unit = {},
    ) {
        val step = SessionActionsStep(entry) { action ->
            val changed = when (action) {
                SessionAction.RENAME -> rename(project, service, entry)
                SessionAction.DELETE -> delete(project, service, entry)
            }
            if (changed) onChanged()
        }
        JBPopupFactory.getInstance().createListPopup(step).show(RelativePoint.fromScreen(screenLocation))
    }

    /** Shows the action list next to [component], used by the right click of a tab. */
    fun showContextMenu(
        project: Project,
        service: RemoteAgentService,
        entry: SessionListEntry,
        component: JComponent,
        location: Point,
        onChanged: () -> Unit = {},
    ) {
        val screenLocation = Point(location)
        SwingUtilities.convertPointToScreen(screenLocation, component)
        showActions(project, service, entry, screenLocation, onChanged)
    }

    /** `true` when the user confirmed a new title. */
    fun rename(project: Project, service: RemoteAgentService, entry: SessionListEntry): Boolean {
        val title = Messages.showInputDialog(
            project,
            "New title of \"${entry.presentableTitle()}\" (stored locally, the agent is not notified):",
            "Rename Session",
            null,
            entry.title.orEmpty(),
            null,
        ) ?: return false
        service.renameSession(entry.sessionId, title)
        return true
    }

    /** `true` when the user confirmed the deletion. */
    fun delete(project: Project, service: RemoteAgentService, entry: SessionListEntry): Boolean {
        val agentSide = if (service.canDeleteSessions) {
            "The session is deleted on the agent side as well."
        } else {
            "The agent does not support session/delete, so only the local information is removed."
        }
        val answer = Messages.showYesNoDialog(
            project,
            "Delete \"${entry.presentableTitle()}\"?\n$agentSide",
            "Delete Session",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return false
        service.deleteSession(entry)
        return true
    }
}
