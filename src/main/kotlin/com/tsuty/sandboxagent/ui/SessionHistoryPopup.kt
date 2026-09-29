package com.tsuty.sandboxagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStepEx
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.ColorUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import com.intellij.util.ui.UIUtil
import com.tsuty.sandboxagent.acp.SessionListEntry
import com.tsuty.sandboxagent.service.SandboxAgentService
import com.tsuty.sandboxagent.session.LocalSessionFile
import com.tsuty.sandboxagent.session.LocalSessionIndex
import com.tsuty.sandboxagent.session.SessionSnapshotStore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

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

/**
 * The session list: the sessions of `session/list` only, each marked with how much of it is kept
 * locally ([LocalSessionIndex]), and every one but [currentSessionId] carrying a submenu of its
 * own actions ([rowActions]), opened by hovering the row.
 *
 * No row reports a substep to the platform ([hasSubstep] is always `false`): a row that is both
 * final and has a substep gets a ⋮ `More` button (or a ▶) and the width kept for it at its right
 * end, depending on the platform version. The submenu is opened by the popup itself instead
 * ([SessionHistoryPopup.RowHoverSubmenu]) from [rowActionsFor], so a click still selects the
 * session and the relative time stays at the right end of the row.
 */
class SessionHistoryPopupStep(
    private val entries: List<SessionListEntry>,
    private val onSelect: (SessionListEntry) -> Unit,
    private val now: Long = System.currentTimeMillis(),
    private val index: LocalSessionIndex = LocalSessionIndex.EMPTY,
    private val currentSessionId: String? = null,
    private val rowActions: (SessionListEntry) -> PopupStep<*>? = { null },
) : BaseListPopupStep<SessionListEntry>("Sessions", entries), ListPopupStepEx<SessionListEntry> {

    override fun getTextFor(value: SessionListEntry): String = value.presentableTitle()

    override fun getSecondaryTextFor(value: SessionListEntry): String? =
        RelativeTime.format(value.lastUpdatedMillis, now)

    override fun getIconFor(value: SessionListEntry): Icon =
        if (isCurrent(value)) AllIcons.Vcs.History else LocalSessionPresentation.storageIcon(index.storageState(value.sessionId))

    /** The full title (the row elides it), then the session id in small, muted text. */
    override fun getTooltipTextFor(value: SessionListEntry): String = tooltipHtml(value)

    override fun setEmptyText(emptyText: StatusText) {
        emptyText.text = "No sessions reported by the agent"
    }

    override fun isSelectable(value: SessionListEntry): Boolean = true

    override fun hasSubstep(selectedValue: SessionListEntry): Boolean = false

    override fun isFinal(value: SessionListEntry): Boolean = true

    override fun onChosen(selectedValue: SessionListEntry, finalChoice: Boolean): PopupStep<*>? {
        if (finalChoice) onSelect(selectedValue)
        return FINAL_CHOICE
    }

    /** The hover submenu of a row; the session open in this tab has nothing to act on from here. */
    fun rowActionsFor(value: SessionListEntry): PopupStep<*>? =
        if (isCurrent(value)) null else rowActions(value)

    private fun isCurrent(value: SessionListEntry): Boolean = value.sessionId == currentSessionId

    companion object {
        fun tooltipHtml(value: SessionListEntry, muted: Color = UIUtil.getContextHelpForeground()): String =
            HtmlBuilder()
                .append(value.presentableTitle())
                .br()
                .append(HtmlChunk.text(value.sessionId).wrapWith("small").wrapWith(HtmlChunk.font(ColorUtil.toHtmlColor(muted))))
                .wrapWith("html")
                .toString()
    }
}

/** What the submenu of a session row offers; [BACKUPS] only while the session has any. */
enum class SessionRowAction(val title: String, val icon: Icon) {
    RENAME("Rename", AllIcons.Actions.Edit),
    DELETE("Delete", AllIcons.Actions.GC),
    BACKUPS("Backups", AllIcons.Nodes.CopyOfFolder),
}

/**
 * The submenu of one session row: `Rename`, `Delete` and, when there are any, `Backups ▶`.
 *
 * It has no title: it opens right next to the hovered row, which stays selected, so repeating the
 * session name would only say twice what the row already shows.
 */
class SessionRowActionsStep(
    private val backups: List<LocalSessionFile>,
    private val onAction: (SessionRowAction) -> Unit,
    private val onOpenBackup: (LocalSessionFile) -> Unit,
) : BaseListPopupStep<SessionRowAction>(
    null,
    SessionRowAction.entries.filter { it != SessionRowAction.BACKUPS || backups.isNotEmpty() },
) {
    override fun getTextFor(value: SessionRowAction): String = value.title

    override fun getIconFor(value: SessionRowAction): Icon = value.icon

    override fun hasSubstep(selectedValue: SessionRowAction): Boolean = selectedValue == SessionRowAction.BACKUPS

    override fun isFinal(value: SessionRowAction): Boolean = value != SessionRowAction.BACKUPS

    override fun onChosen(selectedValue: SessionRowAction, finalChoice: Boolean): PopupStep<*>? {
        if (selectedValue == SessionRowAction.BACKUPS) return BackupsStep(backups, onOpenBackup)
        // The dialogs of both actions are modal, so they are opened once the popups are gone.
        if (finalChoice) doFinalStep { onAction(selectedValue) }
        return FINAL_CHOICE
    }
}

/**
 * The backups of one session, newest first, as `{date} {n} KB`; picking one opens it read only.
 *
 * Like the row submenu it has no title: it opens next to the `Backups` item, which already says
 * what is listed.
 */
class BackupsStep(
    backups: List<LocalSessionFile>,
    private val onOpen: (LocalSessionFile) -> Unit,
) : BaseListPopupStep<LocalSessionFile>(
    null,
    backups.sortedByDescending { it.timestamp ?: 0 }.take(MAX_LISTED_BACKUPS),
) {

    companion object {
        /**
         * Backups listed at most, the newest ones. Only the list is capped: no backup file is ever
         * deleted, and the older ones stay reachable through the local session search.
         */
        const val MAX_LISTED_BACKUPS: Int = 20
    }

    override fun getTextFor(value: LocalSessionFile): String = LocalSessionPresentation.backupRow(value)

    override fun getIconFor(value: LocalSessionFile): Icon = AllIcons.Nodes.CopyOfFolder

    override fun onChosen(selectedValue: LocalSessionFile, finalChoice: Boolean): PopupStep<*>? {
        if (finalChoice) doFinalStep { onOpen(selectedValue) }
        return FINAL_CHOICE
    }
}

/**
 * History dropdown of a tab's session title (`SandboxAgentTabsPanel.TabChip`), following the same
 * "click the label, get a list popup" pattern as [AttachFilePopup].
 *
 * The picked session is restored into the very tab the dropdown was opened from: the service it is
 * shown for is the one of that tab, not a shared one (Ph2 §2 — one service, and one sandbox
 * process, per tab).
 *
 * `Rename` / `Delete` (and the backups of a session) live on the hover submenu of a row instead
 * of a management screen of their own: they act on a single session, so the list the user already
 * picks from is the place to act.
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

    fun show(
        anchor: JComponent,
        project: Project,
        service: SandboxAgentService,
        onOpenReadOnly: (LocalSessionFile) -> Unit,
    ) {
        // The popup shows the list the service already holds and refreshes it in the background,
        // so opening the dropdown never blocks on a `session/list` round trip.
        service.refreshSessions()
        val entries = service.sessions.sortedByDescending { it.lastUpdatedMillis ?: 0 }
        // One listing of the directory for the whole popup, not one lookup per row.
        val index = runCatching { LocalSessionIndex.scan(SessionSnapshotStore.directoryFor(project)) }
            .getOrDefault(LocalSessionIndex.EMPTY)
        val step = SessionHistoryPopupStep(
            entries,
            onSelect = { service.selectSession(it) },
            index = index,
            currentSessionId = service.sessionId,
            rowActions = { entry ->
                SessionRowActionsStep(
                    backups = index.of(entry.sessionId)?.backups.orEmpty(),
                    onAction = { action ->
                        when (action) {
                            SessionRowAction.RENAME -> SessionActions.rename(project, service, entry)
                            SessionRowAction.DELETE -> SessionActions.delete(project, service, entry)
                            SessionRowAction.BACKUPS -> Unit
                        }
                    },
                    onOpenBackup = onOpenReadOnly,
                )
            },
        )
        val popup = RightAlignedTimeListPopup(project, step)
        // Handed down to the submenus the popup opens, so `Backups ▶` opens on a hover too.
        popup.setShowSubmenuOnHover(true)
        popup.setMaxRowCount(MAX_VISIBLE_ROWS)
        popup.list?.let { list -> clampWidth(list, pluginPanelWidth(anchor)) }
        RowHoverSubmenu(popup, step).install()
        popup.showUnderneathOf(anchor)
    }

    /** The session list with the relative time at the right end of each row ([SessionRowRenderer]). */
    private class RightAlignedTimeListPopup(project: Project, step: SessionHistoryPopupStep) : ListPopupImpl(project, step) {
        // Called from the constructor of the superclass, before any field of this class is set:
        // the step is read back from the popup rather than kept here.
        override fun getListElementRenderer(): ListCellRenderer<*> = SessionRowRenderer(listStep as SessionHistoryPopupStep)
    }

    /**
     * A row of the session list: the icon and the title, elided when the row is too narrow, then
     * the relative time at the right end.
     *
     * It is built on plain Swing rather than on the platform `PopupListElementRenderer`: the
     * standard row puts the secondary text in the `CENTER` of its layout, drawn from the left right
     * behind the title, and the label it would have to be realigned through is an implementation
     * detail whose visibility changes between platform versions (`protected` in some, `private` in
     * others, where touching it fails with an `IllegalAccessError`).
     */
    private class SessionRowRenderer(private val step: SessionHistoryPopupStep) : ListCellRenderer<Any> {
        private val title = JLabel().apply { iconTextGap = JBUI.scale(6) }
        private val time = JLabel().apply {
            horizontalAlignment = SwingConstants.RIGHT
            border = JBUI.Borders.emptyLeft(12)
        }
        private val row = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(3, 8)
            add(title, BorderLayout.CENTER)
            add(time, BorderLayout.EAST)
        }

        override fun getListCellRendererComponent(
            list: JList<out Any>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val entry = value as? SessionListEntry
            val foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else list.foreground
            row.background = if (isSelected) UIUtil.getListSelectionBackground(true) else list.background
            title.text = entry?.let(step::getTextFor) ?: value?.toString().orEmpty()
            title.icon = entry?.let(step::getIconFor)
            title.foreground = foreground
            val secondary = entry?.let(step::getSecondaryTextFor)
            time.text = secondary.orEmpty()
            time.isVisible = secondary != null
            time.foreground = if (isSelected) foreground else UIUtil.getContextHelpForeground()
            row.toolTipText = entry?.let(step::getTooltipTextFor)
            return row
        }
    }

    /** How long the pointer rests on a row before its submenu opens, as for the platform menus. */
    private const val SUBMENU_DELAY_MS = 250

    /**
     * Opens the submenu of a row once the pointer has rested on it for [SUBMENU_DELAY_MS], and
     * closes it when the pointer moves on to another row.
     *
     * The rows do not report a substep (see [SessionHistoryPopupStep]), so the platform neither
     * opens it nor keeps room for a ⋮ / ▶. The selection is still driven by the platform, which
     * leaves it where it is while the pointer heads for an open submenu across other rows; the
     * submenu follows the selection, so that move does not close it either.
     */
    private class RowHoverSubmenu(private val popup: ListPopupImpl, private val step: SessionHistoryPopupStep) {
        private val list = popup.list
        private var hoveredIndex = -1
        private var pendingIndex = -1
        private val timer = Timer(SUBMENU_DELAY_MS) { open(pendingIndex) }.apply { isRepeats = false }

        fun install() {
            list.addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) = hover(rowAt(e.point))
            })
            list.addMouseListener(object : MouseAdapter() {
                override fun mouseExited(e: MouseEvent) {
                    timer.stop()
                    hoveredIndex = -1
                }
            })
            Disposer.register(popup) { timer.stop() }
        }

        private fun rowAt(point: Point): Int {
            val index = list.locationToIndex(point)
            return if (index >= 0 && list.getCellBounds(index, index)?.contains(point) == true) index else -1
        }

        private fun hover(index: Int) {
            if (index == hoveredIndex) return
            timer.stop()
            // The platform kept the selection (the pointer is on its way to the open submenu):
            // leave the submenu open and look again on the next move.
            if (index < 0 || list.selectedIndex != index) {
                hoveredIndex = -1
                return
            }
            hoveredIndex = index
            popup.disposeChildren()
            pendingIndex = index
            timer.restart()
        }

        private fun open(index: Int) {
            if (popup.isDisposed || index < 0 || list.selectedIndex != index) return
            val entry = list.model.getElementAt(index) as? SessionListEntry ?: return
            val submenu = step.rowActionsFor(entry) ?: return
            popup.handleNextStep(submenu, entry)
        }
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
            if (component is SandboxAgentTabsPanel) return component.width
            component = component.parent
        }
        return 0
    }
}

/**
 * `Rename` / `Delete` of one session, shared by the submenu of the session dropdown rows and the right
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
        service: SandboxAgentService,
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
        service: SandboxAgentService,
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
    fun rename(project: Project, service: SandboxAgentService, entry: SessionListEntry): Boolean {
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
    fun delete(project: Project, service: SandboxAgentService, entry: SessionListEntry): Boolean {
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
