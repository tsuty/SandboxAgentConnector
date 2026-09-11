package com.tsuty.remoteagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tsuty.remoteagent.acp.SessionListEntry
import com.tsuty.remoteagent.service.PermissionRequest
import com.tsuty.remoteagent.service.RemoteAgentEvent
import com.tsuty.remoteagent.service.RemoteAgentEventListener
import com.tsuty.remoteagent.service.RemoteAgentService
import com.tsuty.remoteagent.service.SessionState
import com.tsuty.remoteagent.settings.RemoteAgentSettingsBridge
import com.tsuty.remoteagent.settings.RemoteAgentSettingsState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/** One active session tab, backed by its own always-live service/panel (see [TabsController]). */
private typealias Tab = RemoteAgentTab<RemoteAgentService, RemoteAgentMainPanel>

/**
 * `true` while [point], given in the coordinates of [host], lands on one of the visible [targets]
 * laid out inside [container] (itself a child of [host]).
 *
 * A tab chip handles the mouse for its whole area (so it keeps seeing the drags that reorder the
 * strip), which means a click can only be told apart by where it landed — hence this lives next to
 * the panel instead of on the labels themselves.
 */
internal fun hitsAnyOf(point: Point, host: JComponent, container: JComponent, targets: List<JComponent>): Boolean {
    val local = SwingUtilities.convertPoint(host, point, container)
    return targets.any { it.isVisible && it.bounds.contains(local) }
}

/**
 * Root tool window component (Ph2 §4): a row of tabs, each backed by its own always-live
 * [RemoteAgentService] / [RemoteAgentMainPanel] pair (Ph2 §2 — one sandbox process per session,
 * no shared pool), and a card area showing the one that is selected.
 *
 * Switching tabs never goes through `session/update` / `session/resume` again: every tab keeps
 * its own connection running in the background the whole time, and selecting another tab is only
 * a display change ([CardLayout.show]), which is the goal Ph2 §1.2 sets for the switch.
 */
class RemoteAgentTabsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val controller = TabsController<RemoteAgentService, RemoteAgentMainPanel>(
        serviceFactory = { RemoteAgentService(project) },
        panelFactory = { service -> RemoteAgentMainPanel(project, service) },
    )

    private val cardLayout = CardLayout()
    private val cards = JPanel(cardLayout)
    private val tabStrip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply { isOpaque = false }
    private val addButton = FlatIconButton(AllIcons.General.Add, "New Tab") { addTab() }

    /** [Tab.id] → the chip rendered for it; rebuilt on every structural change ([renderTabStrip]). */
    private val chips = LinkedHashMap<Int, TabChip>()

    private var selectedTab: Tab? = null

    init {
        val bar = JPanel(BorderLayout())
        bar.isOpaque = false
        bar.border = JBUI.Borders.empty(2, 4)
        bar.add(tabStrip, BorderLayout.WEST)
        val addWrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(addButton) }
        bar.add(addWrapper, BorderLayout.CENTER)

        add(bar, BorderLayout.NORTH)
        add(cards, BorderLayout.CENTER)

        installShortcuts()
        // Picking another agent in the settings changes the drawing every chip's connection
        // toggle shows; the drawing itself may also arrive later than the dialog, since it is
        // downloaded (see `AgentIcon`).
        project.messageBus.connect(this).subscribe(
            RemoteAgentSettingsBridge.TOPIC,
            RemoteAgentSettingsBridge.Listener { onEdt { chips.values.forEach { it.updateConnection() } } },
        )
        // A tool window never opens empty: the first tab is created right away, exactly like the
        // single panel this replaces used to connect eagerly on its own.
        addTab()
    }

    /**
     * `Alt+0`..`Alt+9` select the tab currently showing that number (Ph2 §4). Bound with
     * [JComponent.WHEN_IN_FOCUSED_WINDOW] rather than application wide, so a shortcut only fires
     * while this tool window's window has the focus and a plain digit typed elsewhere in the IDE
     * is left alone.
     */
    private fun installShortcuts() {
        (0..9).forEach { number ->
            val keyStroke = KeyStroke.getKeyStroke('0' + number, InputEvent.ALT_DOWN_MASK)
            registerKeyboardAction(
                { selectTab(controller.tabAt(number)) },
                "RemoteAgentConnector.SelectTab$number",
                keyStroke,
                JComponent.WHEN_IN_FOCUSED_WINDOW,
            )
        }
    }

    /** Creates a fresh tab and connects it; a no-op once [TabsController.canAddTab] is `false`. */
    private fun addTab() {
        val tab = controller.addTab() ?: return
        cards.add(tab.panel, tab.id.toString())
        tab.service.connect()
        tab.service.addListener(RemoteAgentEventListener { event -> onEdt { onTabEvent(tab, event) } })
        renderTabStrip()
        selectTab(tab)
    }

    /**
     * Closes [tab]: the sandbox process behind it is torn down and both lock tiers are released
     * right away (Ph2 §4 / §3), the session itself keeps its history (see [TabsController.closeTab]).
     *
     * The order below is what makes this safe, and both halves of it are bug fixes:
     *
     * The panel is taken out of `cards` *before* [TabsController.closeTab] disposes it, because it
     * embeds a JCEF (off screen rendered) browser (see [JcefTranscriptView]) whose native peer
     * must be detached from the visible hierarchy first; disposing it while `cards` could still
     * paint it is a use-after-free that takes the whole IDE process down.
     *
     * Selection is moved to [nextAfterClosing] and the panel hidden *before* that removal, because
     * `Container.remove` runs `removeNotify()` (which disposes the browser component's resize
     * `Alarm`) and only then calls [CardLayout.removeLayoutComponent] — while the component is
     * still in the container's child list. For a *visible* card that method flips the container to
     * its first card and validates it, so the just-detached browser gets laid out once more and
     * its `reshape` hits the disposed `Alarm`: an assertion is thrown out of `cards.remove`, the
     * close never reaches [renderTabStrip], and what the user sees is the tab surviving while the
     * content jumps to tab 0. Removing an already hidden card skips that path entirely.
     */
    private fun closeTab(tab: Tab) {
        if (selectedTab == tab) selectTab(nextAfterClosing(tab))
        tab.panel.isVisible = false
        cards.remove(tab.panel)
        // A chip that is gone must not keep a `Timer` alive that repaints it every tick.
        chips.remove(tab.id)?.stopPulse()
        // Even if disposing the panel/service fails, the strip must not keep a chip for a tab the
        // controller no longer holds (that stale chip was the visible half of the bug above).
        try {
            controller.closeTab(tab)
        } finally {
            renderTabStrip()
        }
    }

    /**
     * The tab that takes over when [tab] is closed while active: its right neighbour (the one
     * sliding into its spot), else its left one — not always the rightmost tab, which felt like
     * the selection teleporting away. `null` when [tab] is the only one left.
     */
    private fun nextAfterClosing(tab: Tab): Tab? {
        val tabs = controller.tabs
        val index = tabs.indexOf(tab)
        if (index < 0) return tabs.lastOrNull()
        return tabs.getOrNull(index + 1) ?: tabs.getOrNull(index - 1)
    }

    /**
     * Shows [tab]'s panel; the panels of every other tab stay connected in the background.
     *
     * [CardLayout.show] only flips visibility — it neither forces the newly shown card (and the
     * strip's own highlight) to actually repaint, nor moves the keyboard focus that is still sat
     * on whatever was focused inside the card that just got hidden. Left alone, that reads exactly
     * like the tab you switched to is unresponsive: its background/session controls do not repaint
     * or accept input from the fresh position, until something unrelated finally forces a layout.
     */
    private fun selectTab(tab: Tab?) {
        selectedTab = tab
        if (tab != null) cardLayout.show(cards, tab.id.toString())
        cards.revalidate()
        cards.repaint()
        chips.values.forEach { it.updateSelection() }
        tabStrip.revalidate()
        tabStrip.repaint()
        tab?.panel?.focusPrompt()
    }

    private fun onTabEvent(tab: Tab, event: RemoteAgentEvent) {
        when (event) {
            // `StateChanged` is included too: the chip's title now falls back to a state-driven
            // placeholder ("Connecting…" / "No session", see `updateTitle`), not just the session
            // id, so a plain connect/disconnect/error with no title event of its own must still
            // repaint it.
            is RemoteAgentEvent.StateChanged,
            is RemoteAgentEvent.SessionTitleChanged,
            is RemoteAgentEvent.SessionEstablished,
            is RemoteAgentEvent.SessionRestored,
            RemoteAgentEvent.SessionCleared,
            -> chips[tab.id]?.let { chip ->
                chip.updateTitle()
                chip.updateConnection()
            }
            // The agent pulses while its turn runs, so both ends of a turn redraw the icon.
            RemoteAgentEvent.TurnStarted,
            is RemoteAgentEvent.TurnFinished,
            -> chips[tab.id]?.updateConnection()
            // Marked on the chip so a tab that is not in front can say the agent is waiting for an
            // answer of its own (Ph2 §2: every tab stays live in the background).
            is RemoteAgentEvent.PermissionRequested -> chips[tab.id]?.permissionRequested(event.request)
            else -> Unit
        }
    }

    /** Rebuilds the tab strip from [TabsController.tabs]; renumbering happens here too. */
    private fun renderTabStrip() {
        tabStrip.removeAll()
        controller.tabs.forEach { tab ->
            val chip = chips.getOrPut(tab.id) { TabChip(tab) }
            chip.tab = tab
            chip.updateTitle()
            chip.updateConnection()
            chip.updateSelection()
            tabStrip.add(chip)
        }
        addButton.isEnabled = controller.canAddTab
        addButton.toolTipText = if (controller.canAddTab) "New Tab" else "Tab limit reached (10)"
        tabStrip.revalidate()
        tabStrip.repaint()
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater({ action() }, project.disposed)
    }

    override fun dispose() {
        chips.values.forEach { it.stopPulse() }
        Disposer.dispose(controller)
    }

    /**
     * One tab's chip: this tab's agent, its number (0-9, always the left-to-right position,
     * doubling as the `Alt+<n>` shortcut), an elided title with the full one as the tooltip, and a
     * close button. Its width is clamped between [MIN_WIDTH] (just the number stays legible) and
     * [MAX_WIDTH].
     *
     * The agent used to sit on the panel's own toolbar (`RemoteAgentMainPanel`), where it could
     * only ever show the state of whichever tab happened to be selected. It belongs on the chip
     * because the connection is per tab (one sandbox process per session, Ph2 §2), so every tab's
     * state is readable at a glance. It stays outside the session dropdown on purpose — with
     * multiple agents (Ph3) a session cannot be shared across them, so the agent is a property of
     * the tab, not an entry of its session list.
     *
     * The title is also this tab's session dropdown: clicking it opens [SessionHistoryPopup] for
     * *this* tab's service, so a past session is restored into the tab it was picked from, and a
     * right click opens the `Rename` / `Delete` menu of the session currently shown here. Both
     * used to sit on the panel's own toolbar (`RemoteAgentMainPanel`), which named the session a
     * second time right below the tab already naming it.
     *
     * Everything but the close button acts on the *active* tab only (see [actsOnClick]): on any
     * other chip a click is nothing but the switch to it.
     */
    private inner class TabChip(@Volatile var tab: Tab) : JPanel(BorderLayout()) {

        /**
         * The agent of this tab, carrying whatever its state asks to be marked (see [mark] and
         * [RemoteAgentIcons.agentIcon]), pulsing while its turn runs (see [pulse]), and the way to
         * attach it again (see [attach]). The drawing is the one the selected ACP registry entry
         * ships (`RemoteAgentSettingsState.agentIconSvg`) and the bundled one when there is none.
         *
         * Unlike the title, this one carries its own mouse listener: a tooltip alone already stops
         * the events from reaching the chip (the tooltip manager listens on the component it is
         * set on), so it is handled where it is shown instead of by hit testing.
         */
        private val agentLabel = JLabel(agentIcon(AgentMark.DISCONNECTED)).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyRight(4)
        }

        /**
         * `session/request_permission`s of this tab that nobody has answered yet, counted rather
         * than flagged: an agent may ask again while an earlier request is still open, and each
         * one takes itself off this count when it is resolved (see [permissionRequested]).
         */
        private var pendingPermissions = 0

        /**
         * Pulses [agentLabel] while a turn of this tab runs; see [pulseAlpha] for the wave and
         * [stopPulse] for why it must not outlive the chip.
         */
        private val pulse = Timer(PULSE_TICK_MILLIS) {
            pulseStep = (pulseStep + 1) % PULSE_STEPS
            paintAgent()
        }

        /** Position in the pulse wave, `0` whenever [pulse] is not running (full opacity). */
        private var pulseStep = 0

        /** What [agentLabel] shows before [pulse] fades it (see [paintAgent]). */
        private var agentBase: Icon = agentIcon(AgentMark.DISCONNECTED)

        private val numberLabel = JLabel().apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyRight(4)
        }
        private val titleLabel = JLabel("", SwingConstants.LEFT).apply { font = JBUI.Fonts.smallFont() }

        /** The dropdown affordance of [titleLabel], same caret the flat selectors use. */
        private val caretLabel = JLabel(AllIcons.General.ArrowDown).apply { border = JBUI.Borders.emptyLeft(2) }
        private val closeLabel = JLabel(AllIcons.Actions.Close).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.emptyLeft(4)
        }

        /**
         * Number, title and caret. Kept as a field so a click can be told apart by where it
         * landed: [titleLabel] / [caretLabel] open the dropdown, the rest of the chip only
         * selects the tab (see [isOnTitle]).
         */
        private val titleRow = JPanel(BorderLayout())

        /** `x` of the last press, used to tell a click from the start of a drag (see [reorder]). */
        private var dragStartX = 0

        /** `true` once a press turned into a reorder, so releasing it is not taken for a click. */
        private var dragged = false

        /**
         * Guards [showSessionContextMenu] against a popup trigger that is reported on both the
         * press and the release, which is what macOS does.
         */
        private var contextMenuShown = false

        init {
            isOpaque = true
            border = JBUI.Borders.empty(3, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // `BorderLayout`, not `FlowLayout`: once the chip's preferred width is clamped to
            // `MAX_WIDTH` (see below), the row's natural width (number + full title) can exceed
            // the space actually granted to it. A `FlowLayout` would then wrap `titleLabel` onto
            // a second line — which this panel is never sized to show — making the label appear
            // shifted downward/clipped for long titles. `BorderLayout`'s CENTER slot instead just
            // clips the overflow horizontally, keeping the label's vertical position correct.
            titleRow.isOpaque = false
            titleRow.add(numberLabel, BorderLayout.WEST)
            titleRow.add(titleLabel, BorderLayout.CENTER)
            titleRow.add(caretLabel, BorderLayout.EAST)
            add(agentLabel, BorderLayout.WEST)
            add(titleRow, BorderLayout.CENTER)
            add(closeLabel, BorderLayout.EAST)

            // Everything is handled on the chip itself rather than on the labels: a `JLabel`
            // without listeners of its own lets the event through to its parent, so one listener
            // can tell the title (dropdown) from the rest of the chip (select) while still seeing
            // the drags that reorder the strip — which a listener sat on a label would not.
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragStartX = e.xOnScreen
                    dragged = false
                    contextMenuShown = false
                    if (isPopupTrigger(e)) showSessionContextMenu(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    // macOS only reports the popup trigger on the release.
                    if (isPopupTrigger(e)) {
                        showSessionContextMenu(e)
                        return
                    }
                    // A press that never moved far enough to reorder (see [reorder]) is a plain
                    // click: select the tab, unless it landed on the close button.
                    if (closeLabel.bounds.contains(e.point)) return
                    // Only the tab that is already active acts on a click: on any other one the
                    // click is the switch itself (see [actsOnClick]).
                    val openDropdown = !dragged && actsOnClick() && isOnTitle(e.point)
                    selectTab(tab)
                    if (openDropdown) showSessionHistory()
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) = reorder(e)
            })
            closeLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) = closeTab(tab)
            })
            agentLabel.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (!actsOnClick()) {
                        selectTab(tab)
                        return
                    }
                    attach()
                }
            })
        }

        /**
         * Whether a click on this chip does what the thing under the pointer says, rather than
         * just bringing the tab up.
         *
         * A chip is 44-180px wide and most of it — the title (the session dropdown) and the agent
         * (the connection) — acts on a click, so wanting nothing but the tab in front used to be
         * one mis-click away from a popup or a detach. Every tab UI resolves this the same way:
         * the first click selects, and only the selected one is operated on. The close button is
         * the exception (see the listener above): closing a tab one is not looking at is the point
         * of the button, and it can hardly be mistaken for anything else.
         */
        private fun actsOnClick(): Boolean = isActive()

        /** `true` while this is the tab whose panel is shown in the card area. */
        private fun isActive(): Boolean = tab == selectedTab

        /**
         * Attaches this tab when its agent is not up. There is deliberately no way to detach one:
         * a tab attaches itself the moment it is opened (Ph2 §2), so detaching is not a step of
         * any normal flow, while it is a costly mis-click — the sandbox process goes away. Closing
         * the tab remains the way to end a session's process (Ph2 §4.5).
         */
        private fun attach() {
            when (tab.service.state) {
                SessionState.DISCONNECTED, SessionState.ERROR -> tab.service.connect()
                SessionState.CONNECTING, SessionState.READY -> Unit
            }
        }

        /**
         * Swaps this chip with its left/right neighbour once the drag crosses the midpoint
         * between them, then renumbers through [TabsController.moveTab]. Deliberately coarse (no
         * live drag ghost): Ph2 only asks for the reordering itself to work and to renumber.
         */
        private fun reorder(e: MouseEvent) {
            val dx = e.xOnScreen - dragStartX
            val from = controller.tabs.indexOf(tab)
            if (from < 0) return
            val to = when {
                dx > width / 2 && from < controller.tabs.lastIndex -> from + 1
                dx < -width / 2 && from > 0 -> from - 1
                else -> return
            }
            controller.moveTab(from, to)
            dragStartX = e.xOnScreen
            dragged = true
            renderTabStrip()
        }

        private fun isPopupTrigger(e: MouseEvent): Boolean = e.isPopupTrigger || e.button == MouseEvent.BUTTON3

        /** `true` while [point] (in this chip's coordinates) is over the title or its caret. */
        private fun isOnTitle(point: Point): Boolean =
            hitsAnyOf(point, this, titleRow, listOf(titleLabel, caretLabel))

        /**
         * Opens the session history of this tab, anchored under the chip. Only while the tab is
         * connected: restoring a session needs a live agent, which is also why the caret is hidden
         * in every other state (see [updateCaret]).
         */
        private fun showSessionHistory() {
            if (tab.service.state != SessionState.READY) return
            SessionHistoryPopup.show(this, project, tab.service)
        }

        /** `Rename` / `Delete` of the session currently open in this tab. */
        private fun showSessionContextMenu(e: MouseEvent) {
            if (contextMenuShown) return
            val service = tab.service
            val sessionId = service.sessionId ?: return
            val entry = service.sessions.firstOrNull { it.sessionId == sessionId }
                ?: SessionListEntry(sessionId = sessionId, title = service.sessionTitle)
            contextMenuShown = true
            SessionActions.showContextMenu(project, service, entry, this, e.point)
        }

        fun updateTitle() {
            val number = controller.numberOf(tab)
            numberLabel.text = number?.toString().orEmpty()
            // Driven by connection state, not just whether a title happens to be set yet — see
            // `RemoteAgentService.presentableSessionTitle`, so a tab reads "New session" the
            // moment its auto-connect succeeds instead of staying blank until the agent names it.
            val title = tab.service.presentableSessionTitle()
            titleLabel.text = elide(title)
            toolTipText = title
            updateCaret()
        }

        /**
         * Shows the dropdown affordance exactly where clicking the title opens one: on the active
         * tab (see [actsOnClick]) whose agent is up — a session cannot be restored without one,
         * and an inactive tab takes the click as the switch. It doubles as the mark of which chip
         * the pointer currently operates on.
         */
        private fun updateCaret() {
            caretLabel.isVisible = actsOnClick() && tab.service.state == SessionState.READY
        }

        /**
         * Marks a `session/request_permission` of this tab on [agentLabel] until it is answered.
         *
         * Only the request itself is marked (Ph2: a turn that merely ends is not news — every tab
         * ends its turns, so marking that would light the whole strip up). A request the settings
         * auto-allowed is already resolved when it arrives here and is never marked, which is what
         * keeps the mark from flickering on every allowed tool call.
         */
        fun permissionRequested(request: PermissionRequest) {
            if (request.isResolved) return
            pendingPermissions++
            updateConnection()
            // There is no event for a request going away, and it may go away without anyone
            // answering it (the turn ends, the session closes), so the mark is taken down from the
            // request itself rather than from an event of the service.
            request.onResolved {
                onEdt {
                    pendingPermissions = (pendingPermissions - 1).coerceAtLeast(0)
                    updateConnection()
                }
            }
        }

        /**
         * What [agentLabel] marks, sharing the one free corner of the drawing between the states
         * in the order [AgentMark] declares them.
         *
         * A pending request is only marked while the tab is *not* in front: on the active tab the
         * permission card is on screen already, and reaching it is what takes the mark down again
         * (the mark exists for the tabs the user cannot see).
         */
        private fun mark(): AgentMark = when {
            tab.service.state != SessionState.READY -> AgentMark.DISCONNECTED
            pendingPermissions > 0 && !isActive() -> AgentMark.NOTIFICATION
            else -> AgentMark.NONE
        }

        /** Connection state of this tab, drawn on [agentLabel] with the selected agent's icon. */
        fun updateConnection() {
            val state = tab.service.state
            val mark = mark()
            agentBase = agentIcon(mark)
            // A turn only runs on a session that is up, so the pulse follows both.
            if (state == SessionState.READY && tab.service.isTurnRunning) startPulse() else stopPulse()
            paintAgent()
            agentLabel.toolTipText = when {
                mark == AgentMark.NOTIFICATION -> "Waiting for your answer"
                // Nothing to act on in these two: only attaching is offered (see [attach]), so
                // they read as the state they are.
                state == SessionState.READY -> "Connected"
                state == SessionState.CONNECTING -> "Connecting…"
                // Attaching again is the first thing to try after a failed start (a partly
                // downloaded agent is cached), so this says so instead of "Disconnected".
                state == SessionState.ERROR -> "Connection failed — click to connect"
                else -> "Connect"
            }
        }

        private fun startPulse() {
            if (pulse.isRunning) return
            pulseStep = 0
            pulse.start()
        }

        /**
         * Stops the pulse, also called from the outside when the chip goes away ([closeTab],
         * [dispose]): a `Timer` holds its listener, and a chip nobody shows any more would keep
         * being repainted forever.
         */
        fun stopPulse() {
            if (!pulse.isRunning) return
            pulse.stop()
            pulseStep = 0
            paintAgent()
        }

        private fun paintAgent() {
            agentLabel.icon = RemoteAgentIcons.translucent(agentBase, pulseAlpha())
        }

        /**
         * Opacity of the current [pulseStep]: a triangle wave from full down to [PULSE_MIN_ALPHA]
         * and back over [PULSE_STEPS] ticks, so a running turn reads as breathing rather than as
         * a blinking warning. `1f` while the pulse is off, which hands the icon back untouched.
         */
        private fun pulseAlpha(): Float {
            if (!pulse.isRunning) return 1f
            val half = PULSE_STEPS / 2
            val phase = if (pulseStep <= half) pulseStep else PULSE_STEPS - pulseStep
            return 1f - (1f - PULSE_MIN_ALPHA) * phase / half
        }

        fun updateSelection() {
            val isSelected = selectedTab == tab
            background = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getPanelBackground()
            titleLabel.foreground = if (isSelected) UIUtil.getListSelectionForeground(true) else UIUtil.getLabelForeground()
            // Both follow the selection, not only the connection state: the caret marks where a
            // click acts, and a pending request is only marked while the tab is not in front.
            updateCaret()
            updateConnection()
            repaint()
        }

        /** The agent icon carrying [mark], drawn with whatever the selected agent brought along. */
        private fun agentIcon(mark: AgentMark): Icon =
            RemoteAgentIcons.agentIcon(RemoteAgentSettingsState.getInstance(project).agentIconSvgOrNull(), mark)

        private fun elide(text: String): String =
            if (text.length <= MAX_TITLE_CHARS) text else text.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"

        override fun getPreferredSize(): Dimension {
            val natural = super.getPreferredSize()
            return Dimension(natural.width.coerceAtMost(JBUI.scale(MAX_WIDTH)), natural.height)
        }

        override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(MIN_WIDTH), preferredSize.height)
    }

    private companion object {
        /** Ph2 §4: the title is elided to this many characters, the tooltip always shows it in full. */
        private const val MAX_TITLE_CHARS = 16

        /** Widest a tab chip is allowed to grow to (Ph2 §4: "Max several characters"). */
        private const val MAX_WIDTH = 180

        /**
         * Narrowest a tab chip may shrink to: its connection toggle and its number stay legible
         * (Ph2 §4), which is also all a chip needs to stay operable — the toggle is clickable and
         * the tab is reachable by its `Alt+<n>`.
         */
        private const val MIN_WIDTH = 44

        /**
         * The pulse of a running turn: [PULSE_STEPS] ticks of [PULSE_TICK_MILLIS] make one cycle
         * (a second), slow enough to read as breathing and cheap enough for a 16x16 label.
         */
        private const val PULSE_TICK_MILLIS = 100
        private const val PULSE_STEPS = 10

        /** Faintest the agent gets while pulsing; still clearly the same drawing. */
        private const val PULSE_MIN_ALPHA = 0.35f
    }
}
