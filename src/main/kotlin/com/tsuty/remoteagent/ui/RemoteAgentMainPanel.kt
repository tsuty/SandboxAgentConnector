package com.tsuty.remoteagent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManagerListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.tsuty.remoteagent.actions.SendPromptAction
import com.tsuty.remoteagent.acp.PermissionOption
import com.tsuty.remoteagent.acp.SessionConfigCategory
import com.tsuty.remoteagent.acp.SessionConfigOption
import com.tsuty.remoteagent.acp.SessionListEntry
import com.tsuty.remoteagent.acp.SessionModeState
import com.tsuty.remoteagent.acp.SessionModelState
import com.tsuty.remoteagent.acp.StopReason
import com.tsuty.remoteagent.acp.ToolCall
import com.tsuty.remoteagent.service.PermissionRequest
import com.tsuty.remoteagent.service.RemoteAgentEvent
import com.tsuty.remoteagent.service.RemoteAgentEventListener
import com.tsuty.remoteagent.service.RemoteAgentService
import com.tsuty.remoteagent.service.SessionState
import com.tsuty.remoteagent.settings.RemoteAgentSettingsBridge
import com.tsuty.remoteagent.settings.RemoteAgentSettingsState
import com.tsuty.remoteagent.session.AgentMessageEntry
import com.tsuty.remoteagent.session.NoticeEntry
import com.tsuty.remoteagent.session.PlanSnapshotEntry
import com.tsuty.remoteagent.session.SessionSnapshot
import com.tsuty.remoteagent.session.ThoughtEntry
import com.tsuty.remoteagent.session.ToolCallEntry
import com.tsuty.remoteagent.session.TurnEndEntry
import com.tsuty.remoteagent.session.UserMessageEntry
import com.tsuty.remoteagent.ui.transcript.TranscriptPanel
import com.tsuty.remoteagent.vfs.AgentWorkspaceGuard
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Root tool window component: transcript on top, context chips and prompt editor at the bottom,
 * and a flat action bar under the editor holding attach / mode / model on the left and send on
 * the right.
 *
 * Service events arrive on background threads and are marshalled onto the EDT before any
 * Swing state is touched.
 */
class RemoteAgentMainPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = RemoteAgentService.getInstance(project)
    // Choices the agent offers as a Markdown task list are answered straight from the transcript.
    private val chatLog = TranscriptPanel(project) { submitPrompt(it) }
    private val chips = ContextChipsPanel(project)
    private val promptInput = PromptInputPanel(project) { submitPrompt(it) }

    /** Live chain of thought of the running turn, pinned above the prompt editor. */
    private val thinking = ThinkingStatusPanel()

    /**
     * `true` while the agent replays the history of a restored session (see `ReplayStarted`).
     *
     * The replay uses the very same streaming events a live turn does, so this flag is the only
     * thing that tells the two apart: the tool calls of a past turn arrive all at once and must
     * not be timed as work happening now.
     */
    private var replayingHistory = false

    /** Second toolbar row, left: the connection state itself (`Disconnected` / `Ready` / …). */
    private val statusLabel = JBLabel("Disconnected").apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    /** Second toolbar row, right of [statusLabel]: transient messages ("Creating session…", errors). */
    private val messageLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    /** Previous connection state, so a fresh connection can start from an empty transcript. */
    private var lastState: SessionState = SessionState.DISCONNECTED
    /**
     * Icon-only toggle showing the connection state (see [updateSessionState]): the agent, with
     * the connection dot over it while the session is up. The drawing is the one the selected ACP
     * registry entry ships (`RemoteAgentSettingsState.agentIconSvg`) and the bundled pair when
     * there is none.
     */
    private val connectionButton = FlatIconButton(connectionIcon(false), "Disconnected") { toggleConnection() }

    /** Starts a fresh session: the transcript is cleared and the next prompt runs in a new session. */
    private val newSessionButton = FlatIconButton(RemoteAgentIcons.NewSession, "New Session") { startNewSession() }

    /** Current session title; left click opens the history, right click `Rename` / `Delete`. */
    private val sessionTitleLabel = FlatCaretLabel("No session")
    /** Flat, borderless selectors living in the prompt action bar; hidden until the agent reports items. */
    private val modelSelector = FlatSelector<SelectorItem>(
        tooltip = "Model",
        presentation = { it.label },
        onSelect = { applyModel(it.id) },
        description = { it.description },
    ).apply { isVisible = false }
    private val modeSelector = FlatSelector<SelectorItem>(
        tooltip = "Mode",
        presentation = { it.label },
        onSelect = { applyMode(it.id) },
        description = { it.description },
    ).apply { isVisible = false }

    /**
     * `configId` of the `configOptions` selector backing the model / mode picker. Agents that
     * report these through `configOptions` do not implement `session/set_model` / `session/set_mode`.
     */
    private var modelConfigId: String? = null
    private var modeConfigId: String? = null
    private val sendStopButton = FlatIconButton(RemoteAgentIcons.Send, "Send prompt") { onSendStop() }
    // Display only: kept enabled so Swing keeps delivering mouse events and shows the tooltip
    // (disabled components never get one), the click itself does nothing.
    private val tokenUsageButton = FlatIconButton(RemoteAgentIcons.TokenUsage, "") {}.apply {
        cursor = Cursor.getDefaultCursor()
        isVisible = false
    }

    private val listener = RemoteAgentEventListener { event -> onEdt { handleEvent(event) } }

    init {
        border = JBUI.Borders.empty()
        isOpaque = true
        // The new UI recolors the whole content subtree with `ToolWindow.background()` right after
        // the content is added; opt out so the white background below survives.
        RemoteAgentColors.preventBackgroundOverride(this)
        // The chips strip listens to editor selection changes for its auto attach preview.
        Disposer.register(this, chips)
        // The transcript owns the embedded browser it renders in.
        Disposer.register(this, chatLog)

        val splitter = JBSplitter(true, "RemoteAgentConnector.splitter", 0.7f)
        RemoteAgentColors.paintPanelBackground(splitter)
        splitter.firstComponent = chatLog
        splitter.secondComponent = bottomPanel()
        add(toolbar(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        service.addListener(listener)
        updateSessionState(service.state, null)
        // `available_commands_update` is only sent once per session, so a tool window opened after
        // the session was established picks the commands up from the service instead.
        promptInput.setAvailableCommands(service.availableCommands)

        updateSendButtonTooltip()
        // Picking another agent in the settings changes what the connection button shows; the
        // drawing may also arrive later than the dialog, since it is downloaded (see `AgentIcon`).
        project.messageBus.connect(this).subscribe(
            RemoteAgentSettingsBridge.TOPIC,
            RemoteAgentSettingsBridge.Listener { onEdt { updateConnectionIcon() } },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            KeymapManagerListener.TOPIC,
            object : KeymapManagerListener {
                override fun activeKeymapChanged(keymap: com.intellij.openapi.keymap.Keymap?) = onEdt { updateSendButtonTooltip() }
                override fun shortcutsChanged(
                    keymap: com.intellij.openapi.keymap.Keymap,
                    actionIds: Collection<String>,
                    fromSettings: Boolean,
                ) {
                    if (SendPromptAction.ID in actionIds) onEdt { updateSendButtonTooltip() }
                }
            },
        )
    }

    override fun getBackground(): Color = RemoteAgentColors.PANEL_BACKGROUND

    /** Shows the currently bound `Settings > Keymap` shortcut for [SendPromptAction.ID] on the Send button. */
    private fun updateSendButtonTooltip() {
        if (service.isTurnRunning) {
            sendStopButton.toolTipText = "Stop"
            return
        }
        val shortcutText = KeymapUtil.getFirstKeyboardShortcutText(SendPromptAction.ID)
        sendStopButton.toolTipText = if (shortcutText.isNotEmpty()) "Send prompt ($shortcutText)" else "Send prompt"
    }

    /**
     * Toolbar, two rows: `{Connect/Disconnect} {Session title}` left aligned with `{New Session}`
     * pushed to the right on the first row, and `{Status} {Message}` left aligned on the second.
     * The split gives the session title and the new session icon a full row of their own, which
     * they were too cramped to be readable in when everything shared a single line.
     *
     * There is no `Clear` button: the transcript belongs to a session, so it is cleared when the
     * session changes (see [startNewSession] and [onSessionRestored]).
     */
    private fun toolbar(): JPanel {
        sessionTitleLabel.onLeftClick = { SessionHistoryPopup.show(sessionTitleLabel, project, service) }
        sessionTitleLabel.onRightClick = { event -> showSessionContextMenu(event) }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ACTION_BAR_GAP), 0))
        left.isOpaque = false
        left.add(connectionButton)
        left.add(sessionTitleLabel)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ACTION_BAR_GAP), 0))
        right.isOpaque = false
        right.add(newSessionButton)

        // FlowLayout already contributes ACTION_BAR_GAP on the outer sides, so only the remainder
        // is added here to reach CONTENT_MARGIN from the tool window edges; the vertical gaps of
        // the rows live on these borders too, so the icons start ROW_TOP_MARGIN below the top edge.
        val firstRow = JPanel(BorderLayout())
        firstRow.isOpaque = false
        firstRow.border =
            JBUI.Borders.empty(ROW_TOP_MARGIN, CONTENT_MARGIN - ACTION_BAR_GAP, 0, CONTENT_MARGIN - ACTION_BAR_GAP)
        firstRow.add(left, BorderLayout.WEST)
        firstRow.add(right, BorderLayout.EAST)

        // Second row: the connection state, followed by the transient message of that state.
        val secondRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ACTION_BAR_GAP * 2), 0))
        secondRow.isOpaque = false
        secondRow.border =
            JBUI.Borders.empty(2, CONTENT_MARGIN - ACTION_BAR_GAP * 2, 2, CONTENT_MARGIN - ACTION_BAR_GAP * 2)
        secondRow.add(statusLabel)
        secondRow.add(messageLabel)

        // Each row carries its own insets, so the rows can start at CONTENT_MARGIN despite the
        // different gaps their layouts contribute.
        val panel = BackgroundPanel(BorderLayout())
        panel.add(firstRow, BorderLayout.NORTH)
        panel.add(secondRow, BorderLayout.CENTER)
        return panel
    }

    private fun showSessionContextMenu(event: MouseEvent) {
        val sessionId = service.sessionId ?: return
        val entry = service.sessions.firstOrNull { it.sessionId == sessionId }
            ?: SessionListEntry(sessionId = sessionId, title = service.sessionTitle)
        SessionActions.showContextMenu(project, service, entry, sessionTitleLabel, event.point)
    }

    /** Clears the transcript and moves the service on to a fresh session. */
    private fun startNewSession() {
        service.startNewSession()
    }

    private fun clearTranscript() {
        chatLog.clear()
        thinking.clear()
        replayingHistory = false
    }

    /** Attaches when disconnected, detaches when connecting or ready. */
    private fun toggleConnection() {
        when (service.state) {
            SessionState.DISCONNECTED, SessionState.ERROR -> service.connect()
            SessionState.CONNECTING, SessionState.READY -> service.disconnect()
        }
    }

    private fun bottomPanel(): JPanel {
        val panel = BackgroundPanel(BorderLayout())

        val north = BackgroundPanel(BorderLayout())
        north.add(thinking, BorderLayout.NORTH)
        north.add(chips, BorderLayout.CENTER)

        panel.add(north, BorderLayout.NORTH)
        panel.add(promptInputWrapper(), BorderLayout.CENTER)
        panel.add(actionBar(), BorderLayout.SOUTH)
        return panel
    }

    /**
     * Keeps the prompt field inset by [CONTENT_MARGIN] so its border lines up with the borders of
     * the transcript entries above it.
     */
    private fun promptInputWrapper(): JPanel {
        val wrapper = BackgroundPanel(BorderLayout())
        wrapper.border = JBUI.Borders.empty(0, CONTENT_MARGIN)
        wrapper.add(promptInput, BorderLayout.CENTER)
        return wrapper
    }

    /**
     * Prompt action bar: `{Attach File} {Attach Selection} {Mode} {Model}` left aligned and
     * `{Send}` pushed to the right. Every control is flat and icon driven; the labels of the
     * icon buttons are only exposed as tooltips.
     */
    private fun actionBar(): JPanel {
        lateinit var attachFile: FlatIconButton
        attachFile = FlatIconButton(AllIcons.Actions.AddFile, "Attach File") {
            AttachFilePopup.show(attachFile, project) { file -> chips.attachVirtualFile(file) }
        }
        val attachSelection = FlatIconButton(AllIcons.Actions.AddList, "Attach Selection") {
            if (!chips.attachSelection()) chatLog.appendSystemMessage("Nothing is selected in the editor")
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ACTION_BAR_GAP), JBUI.scale(2)))
        left.isOpaque = false
        left.add(attachFile)
        left.add(attachSelection)
        left.add(modeSelector)
        left.add(modelSelector)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ACTION_BAR_GAP), JBUI.scale(2)))
        right.isOpaque = false
        right.add(tokenUsageButton)
        right.add(sendStopButton)

        val bar = JPanel(BorderLayout())
        bar.isOpaque = false
        // FlowLayout already contributes ACTION_BAR_GAP on the outer sides, so only the remainder
        // is added here to reach CONTENT_MARGIN from the tool window edges.
        bar.border = JBUI.Borders.empty(0, CONTENT_MARGIN - ACTION_BAR_GAP)
        bar.add(left, BorderLayout.WEST)
        bar.add(right, BorderLayout.EAST)
        return bar
    }

    private fun onSendStop() {
        if (service.isTurnRunning) {
            service.cancelPrompt()
        } else {
            submitPrompt(promptInput.text.trim())
        }
    }

    /** `false` when the prompt was not sent (empty, turn running or session not ready). */
    private fun submitPrompt(text: String): Boolean {
        if (text.isEmpty() || service.isTurnRunning) return false
        if (service.state != SessionState.READY) {
            chatLog.appendSystemMessage("The remote agent session is not ready yet — press Connect first.")
            return false
        }
        chips.applyAutoAttach()
        val context = chips.contextItems()
        promptInput.clearInput()
        chips.clearAll()
        // The prompt and its answer belong at the bottom edge: a reader who had scrolled up into
        // the history is taken back down, and the transcript follows the new turn from there on.
        chatLog.scrollToBottom()
        service.sendPrompt(text, context)
        return true
    }

    private fun handleEvent(event: RemoteAgentEvent) {
        when (event) {
            is RemoteAgentEvent.StateChanged -> updateSessionState(event.state, event.message)
            is RemoteAgentEvent.SessionEstablished -> onSessionEstablished(event)
            is RemoteAgentEvent.UserPrompt -> chatLog.appendUserPrompt(event.text, event.attachments)
            is RemoteAgentEvent.UserPromptAttachments -> chatLog.appendUserPromptAttachments(event.attachments)
            is RemoteAgentEvent.TurnStarted -> {
                updateTurnState(running = true)
                thinking.clear()
            }
            is RemoteAgentEvent.TurnFinished -> {
                updateTurnState(running = false)
                // The thought is only live while the turn runs: fold it into the transcript and
                // give the strip above the prompt editor back to the next turn.
                chatLog.appendThought(thinking.thought)
                thinking.clear()
                chatLog.appendCompletion(StopReason.presentableText(event.stopReason))
                // The requests of a finished turn can no longer be answered.
                chatLog.cancelPermissions()
            }
            RemoteAgentEvent.ReplayStarted -> replayingHistory = true
            // A replayed history ends without a stop reason: only fold the thought away, so the
            // strip above the prompt editor does not keep showing the replayed `Thinking`.
            RemoteAgentEvent.ReplayFinished -> {
                replayingHistory = false
                updateTurnState(running = false)
                chatLog.appendThought(thinking.thought)
                thinking.clear()
                // No stop reason to show for a replayed turn, but it still gets a `moveUp` icon so
                // it can be scrolled back to, same as a live turn's completion notice.
                chatLog.markTurnEnd()
            }
            is RemoteAgentEvent.AgentMessageChunk -> chatLog.appendAgentChunk(event.text)
            is RemoteAgentEvent.AgentThoughtChunk -> thinking.append(event.text)
            is RemoteAgentEvent.ToolCallStarted -> showToolCall(event.toolCall, restored = replayingHistory)
            is RemoteAgentEvent.ToolCallUpdated -> updateToolCall(event.toolCall)
            is RemoteAgentEvent.PlanUpdated -> chatLog.showPlan(event.entries)
            is RemoteAgentEvent.ModeChanged -> modeSelector.selectMatching { it.id == event.modeId }
            is RemoteAgentEvent.ModelChanged -> modelSelector.selectMatching { it.id == event.modelId }
            is RemoteAgentEvent.ConfigOptionsChanged -> populateConfigOptions(event.configOptions)
            is RemoteAgentEvent.SessionSelectorsUpdated ->
                populateSelectors(event.models, event.modes, event.configOptions)
            is RemoteAgentEvent.CommandsAvailable -> promptInput.setAvailableCommands(event.commands)
            is RemoteAgentEvent.PermissionRequested -> showPermissionCard(event.request)
            is RemoteAgentEvent.UsageUpdated -> updateTokenUsageButton(event)
            is RemoteAgentEvent.Error -> chatLog.appendError(event.message)
            // The list itself is read from the service when the dropdown is opened.
            is RemoteAgentEvent.SessionsListed -> Unit
            is RemoteAgentEvent.SessionListLoading -> updateSessionsLoading(event.loading)
            is RemoteAgentEvent.SessionRestored -> onSessionRestored(event)
            is RemoteAgentEvent.SessionRestoreRejected -> chatLog.appendError(event.message)
            RemoteAgentEvent.SessionCleared -> {
                clearTranscript()
                sessionTitleLabel.title = null
                // The selectors belong to the session that was just left; the service publishes
                // the ones of the fresh session (reused or newly created) right after.
                populateSelectors(models = null, modes = null, configOptions = emptyList())
            }
            is RemoteAgentEvent.SessionTitleChanged ->
                if (event.sessionId == service.sessionId) sessionTitleLabel.title = event.title
        }
    }

    /** `loading-sessions`: a listing or a session switch is in flight. */
    private fun updateSessionsLoading(loading: Boolean) {
        if (loading) {
            messageLabel.icon = AnimatedIcon.Default.INSTANCE
            messageLabel.text = "Loading sessions…"
        } else {
            updateSessionState(service.state, null)
        }
    }

    /**
     * Switches the panel to another session: the transcript of the previous one is dropped, and
     * the local snapshot (when there is one) is drawn immediately. Without a snapshot the history
     * is replayed by the agent and arrives through the regular streaming events.
     */
    private fun onSessionRestored(event: RemoteAgentEvent.SessionRestored) {
        clearTranscript()
        sessionTitleLabel.title = event.title
        val snapshot = event.snapshot
        if (snapshot != null) {
            renderSnapshot(snapshot)
        }
        if (!event.contextRestored) {
            chatLog.appendSystemMessage(
                "This agent cannot resume a session: the transcript below is restored from the local history only, " +
                    "so the agent has no context of it.",
            )
        }
    }

    /** Redraws a stored transcript; the entries are already the final display state. */
    private fun renderSnapshot(snapshot: SessionSnapshot) {
        snapshot.entries.forEach { entry ->
            when (entry) {
                is UserMessageEntry -> chatLog.appendUserPrompt(entry.text, entry.attachments)
                is AgentMessageEntry -> chatLog.appendAgentMessage(entry.text)
                is ThoughtEntry -> chatLog.appendThought(entry.text)
                is ToolCallEntry -> showToolCall(entry.toolCall, restored = true)
                is PlanSnapshotEntry -> chatLog.showPlan(entry.entries)
                is NoticeEntry -> if (entry.error) chatLog.appendError(entry.text) else chatLog.appendCompletion(entry.text)
                // A replayed turn has no stop reason (see `TurnEndEntry`), but it still needs its
                // `moveUp` icon anchored so the transcript keeps showing separate turns instead of
                // one continuous block once it is saved and restored again.
                is TurnEndEntry -> chatLog.markTurnEnd()
            }
        }
    }

    private fun onSessionEstablished(event: RemoteAgentEvent.SessionEstablished) {
        chatLog.appendSystemMessage(
            (if (event.restored) "Session restored: " else "Session started: ") + event.sessionId,
        )
        sessionTitleLabel.title = service.sessionTitle
        populateSelectors(event.models, event.modes, event.configOptions)
    }

    /**
     * Applies the model / mode state of the session that was just entered. The `configOptions` of
     * the new session decide again how the selectors are driven, so the remembered `configId`s of
     * the previous one are dropped first.
     */
    private fun populateSelectors(
        models: SessionModelState?,
        modes: SessionModeState?,
        configOptions: List<SessionConfigOption>,
    ) {
        modelConfigId = null
        modeConfigId = null
        // `configOptions` wins: an agent reporting them does not implement the dedicated methods.
        populateConfigOptions(configOptions)
        populateModels(models)
        populateModes(modes)
    }

    private fun populateModels(models: SessionModelState?) {
        if (modelConfigId != null) return
        val items = models?.availableModels.orEmpty().map { SelectorItem(it.modelId, it.presentableName, it.description) }
        modelSelector.setItems(items, items.firstOrNull { it.id == models?.currentModelId })
    }

    private fun populateModes(modes: SessionModeState?) {
        if (modeConfigId != null) return
        val items = modes?.availableModes.orEmpty().map { SelectorItem(it.id, it.presentableName, it.description) }
        modeSelector.setItems(items, items.firstOrNull { it.id == modes?.currentModeId })
    }

    /**
     * Fills the selectors from the generic `configOptions` state, which is how newer agents (e.g.
     * the Claude ACP agent) report the model and the permission mode.
     */
    private fun populateConfigOptions(configOptions: List<SessionConfigOption>) {
        configOptions.firstOrNull { it.isCategory(SessionConfigCategory.MODEL) }?.let { option ->
            modelConfigId = option.id
            fill(modelSelector, option)
        }
        configOptions.firstOrNull { it.isCategory(SessionConfigCategory.MODE) }?.let { option ->
            modeConfigId = option.id
            fill(modeSelector, option)
        }
    }

    private fun fill(selector: FlatSelector<SelectorItem>, option: SessionConfigOption) {
        val items = option.choices.mapNotNull { choice ->
            choice.value?.let { SelectorItem(it, choice.presentableName, choice.description) }
        }
        selector.setItems(items, items.firstOrNull { it.id == option.currentValue })
    }

    private fun applyModel(modelId: String) {
        val configId = modelConfigId
        if (configId != null) service.setConfigOption(configId, modelId) else service.selectModel(modelId)
    }

    private fun applyMode(modeId: String) {
        val configId = modeConfigId
        if (configId != null) service.setConfigOption(configId, modeId) else service.selectMode(modeId)
    }

    /**
     * Draws a `tool_call` as a card, or refreshes the card it already has.
     *
     * Every kind but `switch_mode` joins the foldable group of the current run (see
     * `TranscriptPanel.showToolCall`): a mode switch changes what the agent may do for the rest of the
     * session, so its framed block stays an entry of its own. [restored] marks a card redrawn
     * from a stored transcript or replayed by the agent (see [replayingHistory]), which its group
     * must not time as a live run.
     */
    private fun showToolCall(toolCall: ToolCall, restored: Boolean = false) {
        chatLog.showToolCall(toolCall, restored = restored)
    }

    /**
     * A `tool_call_update` only carries what changed, so the merged state the transcript keeps is
     * what the affected files are read from.
     */
    private fun updateToolCall(toolCall: ToolCall) {
        val merged = chatLog.showToolCall(toolCall, restored = replayingHistory)
        refreshAffectedFiles(merged)
    }

    private fun showPermissionCard(request: PermissionRequest) {
        chatLog.showPermission(request) { option ->
            service.respondToPermission(request, option)
            if (option.isAllow) refreshAffectedFiles(request.toolCall)
        }
    }

    /**
     * Makes edits performed by the agent visible in the IDE; the paths are sandbox side, which
     * [AgentWorkspaceGuard] resolves against the locally mounted workspace.
     */
    private fun refreshAffectedFiles(toolCall: ToolCall) {
        val paths = toolCall.diffs.map { it.path } + toolCall.locations.map { it.path }
        AgentWorkspaceGuard.getInstanceOrNull(project)?.refreshPaths(paths)
    }

    /**
     * Send / Stop state of the action bar button. [connected] is passed in because the state of a
     * [RemoteAgentEvent.StateChanged] is applied before the service exposes it as its own.
     */
    private fun updateTurnState(running: Boolean, connected: Boolean = service.state == SessionState.READY) {
        if (running) {
            sendStopButton.icon = RemoteAgentIcons.Stop
            sendStopButton.toolTipText = "Stop"
            sendStopButton.isEnabled = true
        } else {
            sendStopButton.icon = RemoteAgentIcons.Send
            updateSendButtonTooltip()
            sendStopButton.isEnabled = connected
        }
    }

    private fun updateSessionState(state: SessionState, message: String?) {
        val connecting = state == SessionState.CONNECTING
        val connected = state == SessionState.READY
        val isError = state == SessionState.ERROR
        statusLabel.icon = when {
            connecting -> AnimatedIcon.Default.INSTANCE
            isError -> AllIcons.General.Error
            else -> null
        }
        statusLabel.text = presentableState(state)
        messageLabel.icon = null
        messageLabel.text = message?.takeIf { it.isNotBlank() }.orEmpty()
        connectionButton.icon = connectionIcon(connected)
        connectionButton.toolTipText = when (state) {
            SessionState.READY -> "Connected"
            SessionState.CONNECTING -> "Connecting…"
            // Attaching again is the first thing to try after a failed start (a partly downloaded
            // agent is cached), so the button says so instead of just "Disconnected".
            SessionState.ERROR -> "Connection failed — click to retry"
            else -> "Disconnected"
        }
        updateTurnState(service.isTurnRunning, connected)
        // The session controls only make sense while a session can exist.
        sessionTitleLabel.isEnabled = connected
        newSessionButton.isEnabled = connected
        sessionTitleLabel.title = service.sessionTitle
        if (!connected) {
            modelSelector.isVisible = false
            modeSelector.isVisible = false
            // The commands belong to the session that just went away.
            promptInput.setAvailableCommands(emptyList())
        }
        if (state == SessionState.ERROR && message != null) {
            chatLog.appendError(message)
        }
        // A reconnection starts a fresh conversation, so the transcript of the previous connection
        // is dropped once the new one is up. Disconnecting leaves it on screen to read.
        if (connected && lastState != SessionState.READY) clearTranscript()
        lastState = state
    }

    private fun presentableState(state: SessionState): String = when (state) {
        SessionState.DISCONNECTED -> "Disconnected"
        SessionState.CONNECTING -> "Connecting to the sandbox…"
        SessionState.READY -> "Ready"
        SessionState.ERROR -> "Error"
    }

    private fun updateTokenUsageButton(event: RemoteAgentEvent.UsageUpdated) {
        val used = event.usedTokens
        val size = event.contextSize
        val cost = event.cost
        if (used == null && cost == null) {
            tokenUsageButton.isVisible = false
            return
        }
        val parts = buildList {
            if (used != null && size != null) add("${formatTokens(used)}/${formatTokens(size)} tokens")
            else if (used != null) add("${formatTokens(used)} tokens")
            if (cost != null && cost.amount != null) {
                val currency = cost.currency ?: "USD"
                add("%,.4f %s".format(cost.amount, currency))
            }
        }
        tokenUsageButton.toolTipText = parts.joinToString(" · ")
        tokenUsageButton.isVisible = true
        tokenUsageButton.revalidate()
        tokenUsageButton.parent?.revalidate()
        tokenUsageButton.parent?.repaint()
    }

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000L -> "%.1fM".format(n / 1_000_000.0).trimEnd('0').trimEnd('.')
        n >= 1_000L -> "%.1fK".format(n / 1_000.0).trimEnd('0').trimEnd('.')
        else -> n.toString()
    }

    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater({ action() }, project.disposed)
    }

    override fun dispose() {
        service.removeListener(listener)
    }

    private fun updateConnectionIcon() {
        connectionButton.icon = connectionIcon(service.state == SessionState.READY)
    }

    /** The button icon for [connected], drawn with whatever the selected agent brought along. */
    private fun connectionIcon(connected: Boolean): Icon =
        RemoteAgentIcons.connection(RemoteAgentSettingsState.getInstance(project).agentIconSvgOrNull(), connected)

    private companion object {

        /** Distance, in pixels, from the tool window edge to the bordered content (see TranscriptPanel). */
        private const val CONTENT_MARGIN = RemoteAgentLayout.CONTENT_MARGIN

        /** Distance, in pixels, from the top edge of the tool window to the first toolbar row. */
        private const val ROW_TOP_MARGIN = RemoteAgentLayout.ROW_TOP_MARGIN

        /** Horizontal gap between the action bar buttons, also applied on its outer sides. */
        private const val ACTION_BAR_GAP = 2
    }
}
