package com.tsuty.sandboxagent.ui

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
import com.tsuty.sandboxagent.actions.SendPromptAction
import com.tsuty.sandboxagent.acp.PermissionOption
import com.tsuty.sandboxagent.acp.SessionConfigCategory
import com.tsuty.sandboxagent.acp.SessionConfigOption
import com.tsuty.sandboxagent.acp.SessionModeState
import com.tsuty.sandboxagent.acp.SessionModelState
import com.tsuty.sandboxagent.acp.StopReason
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.service.PermissionRequest
import com.tsuty.sandboxagent.service.SandboxAgentEvent
import com.tsuty.sandboxagent.service.SandboxAgentEventListener
import com.tsuty.sandboxagent.service.SandboxAgentService
import com.tsuty.sandboxagent.service.SessionState
import com.tsuty.sandboxagent.session.AgentMessageEntry
import com.tsuty.sandboxagent.session.NoticeEntry
import com.tsuty.sandboxagent.session.PlanSnapshotEntry
import com.tsuty.sandboxagent.session.SessionSnapshot
import com.tsuty.sandboxagent.session.ThoughtEntry
import com.tsuty.sandboxagent.session.ToolCallEntry
import com.tsuty.sandboxagent.session.TurnEndEntry
import com.tsuty.sandboxagent.session.UserMessageEntry
import com.tsuty.sandboxagent.ui.transcript.TranscriptPanel
import com.tsuty.sandboxagent.vfs.AgentWorkspaceGuard
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel

/**
 * Root tool window component: transcript on top, context chips and prompt editor at the bottom,
 * and a flat action bar under the editor holding attach / mode / model on the left and send on
 * the right.
 *
 * Service events arrive on background threads and are marshalled onto the EDT before any
 * Swing state is touched.
 */
class SandboxAgentMainPanel(
    private val project: Project,
    /**
     * The service instance this panel drives. Defaults to the project's shared instance for
     * backward compatibility (a single tool window content, e.g. tests instantiating the panel
     * directly); `SandboxAgentTabsPanel` passes a dedicated instance per tab instead, so each tab
     * keeps its own sandbox process and ACP session alive independently (Ph2 §2/§4).
     */
    private val service: SandboxAgentService = SandboxAgentService.getInstance(project),
) : JPanel(BorderLayout()), Disposable {
    /**
     * A Read Only tab (a backup or a deleted session, see [SandboxAgentService.readOnlySource]):
     * only the transcript and the status are shown — no prompt, no attachments, no new session.
     */
    private val readOnly = service.isReadOnly

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

    /** Toolbar, left: the connection state itself (`Disconnected` / `Ready` / …). */
    private val statusLabel = JBLabel("Disconnected").apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    /** Toolbar, right of [statusLabel]: transient messages ("Creating session…", errors). */
    private val messageLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    /** Previous connection state, so a fresh connection can start from an empty transcript. */
    private var lastState: SessionState = SessionState.DISCONNECTED

    /** Starts a fresh session: the transcript is cleared and the next prompt runs in a new session. */
    private val newSessionButton = FlatIconButton(SandboxAgentIcons.NewSession, "New Session") { startNewSession() }

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
     * Reasoning budget of the agent (`effort`), which only exists as a generic `configOptions`
     * selector — there is no dedicated ACP method for it, so agents that do not report it simply
     * leave this picker hidden.
     */
    private val effortSelector = FlatSelector<SelectorItem>(
        tooltip = "Effort",
        presentation = { it.label },
        onSelect = { applyEffort(it.id) },
        description = { it.description },
    ).apply { isVisible = false }

    /**
     * `configId` of the `configOptions` selector backing the model / mode picker. Agents that
     * report these through `configOptions` do not implement `session/set_model` / `session/set_mode`.
     */
    private var modelConfigId: String? = null
    private var modeConfigId: String? = null

    /** `configId` of the effort selector; `null` while the session reports no such option. */
    private var effortConfigId: String? = null

    /**
     * Left half of the prompt action bar. It is a field because the selectors of the
     * `configOptions` this panel knows nothing about are added to it while a session runs (see
     * [populateExtraConfigOptions]).
     */
    private val actionBarLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(ACTION_BAR_GAP), JBUI.scale(2))).apply {
        isOpaque = false
    }

    /**
     * Selectors drawn for the remaining `configOptions`, by `configId`. Agents report options this
     * panel has no fixed slot for (`fast`, ...), and reporting them is model dependent, so they
     * come and go with the state of the session instead of existing from the start.
     */
    private val extraSelectors = linkedMapOf<String, FlatSelector<SelectorItem>>()

    private val sendStopButton = FlatIconButton(SandboxAgentIcons.Send, "Send prompt") { onSendStop() }
    // Display only: kept enabled so Swing keeps delivering mouse events and shows the tooltip
    // (disabled components never get one), the click itself does nothing.
    private val tokenUsageButton = FlatIconButton(SandboxAgentIcons.TokenUsage, "") {}.apply {
        cursor = Cursor.getDefaultCursor()
        isVisible = false
    }

    private val listener = SandboxAgentEventListener { event -> onEdt { handleEvent(event) } }

    init {
        border = JBUI.Borders.empty()
        isOpaque = true
        // The new UI recolors the whole content subtree with `ToolWindow.background()` right after
        // the content is added; opt out so the white background below survives.
        SandboxAgentColors.preventBackgroundOverride(this)
        // The chips strip listens to editor selection changes for its auto attach preview.
        Disposer.register(this, chips)
        // The transcript owns the embedded browser it renders in.
        Disposer.register(this, chatLog)

        add(toolbar(), BorderLayout.NORTH)
        if (readOnly) {
            add(chatLog, BorderLayout.CENTER)
        } else {
            val splitter = JBSplitter(true, "SandboxAgentConnector.splitter", 0.7f)
            SandboxAgentColors.paintPanelBackground(splitter)
            splitter.firstComponent = chatLog
            splitter.secondComponent = bottomPanel()
            add(splitter, BorderLayout.CENTER)
        }

        service.addListener(listener)
        updateSessionState(service.state, null)
        // `available_commands_update` is only sent once per session, so a tool window opened after
        // the session was established picks the commands up from the service instead.
        promptInput.setAvailableCommands(service.availableCommands)

        updateSendButtonTooltip()
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

    override fun getBackground(): Color = SandboxAgentColors.PANEL_BACKGROUND

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
     * Toolbar, a single row: `{Status} {Message}` left aligned with `{New Session}` pushed to the
     * right.
     *
     * It used to be two rows, with the session title and the connection toggle on the first one.
     * Both moved to the tab this panel is shown in (`SandboxAgentTabsPanel.TabChip`): the session
     * is named right next to the tab it is open in instead of twice on screen, and the connection
     * belongs to the tab too (one sandbox process per session, Ph2 §2), so every tab shows its own
     * state rather than only the selected one showing it. What is left here is the state of *this*
     * tab spelled out, which is also where its errors are shown — hence one row, not none.
     *
     * There is no `Clear` button: the transcript belongs to a session, so it is cleared when the
     * session changes (see [startNewSession] and [onSessionRestored]).
     */
    private fun toolbar(): JPanel {
        // `BorderLayout.WEST` stretches this side to the height of the row, which the taller
        // button on the other side sets. A `FlowLayout` would leave its labels sitting at the top
        // edge of that space, above the button they read next to; `GridBagLayout` centres its grid
        // in the leftover height instead, so the text lines up with the button.
        val left = JPanel(GridBagLayout())
        left.isOpaque = false
        val labels = GridBagConstraints().apply { anchor = GridBagConstraints.WEST }
        left.add(statusLabel, labels)
        left.add(messageLabel, labels.apply { insets = JBUI.insetsLeft(ACTION_BAR_GAP * 2) })

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ACTION_BAR_GAP), 0))
        right.isOpaque = false
        if (!readOnly) right.add(newSessionButton)

        // The right side's FlowLayout already contributes its gap on the outer side, so only the
        // remainder is added there to reach CONTENT_MARGIN from the tool window edge; the left
        // side's GridBagLayout adds none. The vertical insets live on this border too, and are
        // symmetric: the row is ROW_TOP_MARGIN away from both the top edge and the transcript.
        val panel = BackgroundPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(
            ROW_TOP_MARGIN,
            CONTENT_MARGIN,
            ROW_TOP_MARGIN,
            CONTENT_MARGIN - ACTION_BAR_GAP,
        )
        panel.add(left, BorderLayout.WEST)
        panel.add(right, BorderLayout.EAST)
        return panel
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
     * Prompt action bar: `{Attach File} {Attach Selection} {Mode} {Model} {Effort}` left aligned and
     * `{Send}` pushed to the right. Every control is flat and icon driven; the labels of the
     * icon buttons are only exposed as tooltips.
     *
     * The slots above are the fixed ones; every other selector the agent reports is appended
     * after them while a session runs (see [populateExtraConfigOptions]).
     */
    private fun actionBar(): JPanel {
        lateinit var attachFile: FlatIconButton
        attachFile = FlatIconButton(AllIcons.Actions.AddFile, "Attach File") {
            AttachFilePopup.show(attachFile, project) { file -> chips.attachVirtualFile(file) }
        }
        val attachSelection = FlatIconButton(AllIcons.Actions.AddList, "Attach Selection") {
            if (!chips.attachSelection()) chatLog.appendSystemMessage("Nothing is selected in the editor")
        }

        actionBarLeft.add(attachFile)
        actionBarLeft.add(attachSelection)
        actionBarLeft.add(modeSelector)
        actionBarLeft.add(modelSelector)
        actionBarLeft.add(effortSelector)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(ACTION_BAR_GAP), JBUI.scale(2)))
        right.isOpaque = false
        right.add(tokenUsageButton)
        right.add(sendStopButton)

        val bar = JPanel(BorderLayout())
        bar.isOpaque = false
        // FlowLayout already contributes ACTION_BAR_GAP on the outer sides, so only the remainder
        // is added here to reach CONTENT_MARGIN from the tool window edges.
        bar.border = JBUI.Borders.empty(0, CONTENT_MARGIN - ACTION_BAR_GAP)
        bar.add(actionBarLeft, BorderLayout.WEST)
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
            chatLog.appendSystemMessage("The sandbox agent session is not ready yet — press Connect first.")
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

    private fun handleEvent(event: SandboxAgentEvent) {
        when (event) {
            is SandboxAgentEvent.StateChanged -> updateSessionState(event.state, event.message)
            is SandboxAgentEvent.SessionEstablished -> onSessionEstablished(event)
            is SandboxAgentEvent.UserPrompt -> chatLog.appendUserPrompt(event.text, event.attachments)
            is SandboxAgentEvent.UserPromptAttachments -> chatLog.appendUserPromptAttachments(event.attachments)
            is SandboxAgentEvent.TurnStarted -> {
                updateTurnState(running = true)
                thinking.clear()
            }
            is SandboxAgentEvent.TurnFinished -> {
                updateTurnState(running = false)
                // The thought is only live while the turn runs: fold it into the transcript and
                // give the strip above the prompt editor back to the next turn.
                chatLog.appendThought(thinking.thought)
                thinking.clear()
                chatLog.appendCompletion(StopReason.presentableText(event.stopReason))
                // The requests of a finished turn can no longer be answered.
                chatLog.cancelPermissions()
            }
            SandboxAgentEvent.ReplayStarted -> replayingHistory = true
            // A replayed history ends without a stop reason: only fold the thought away, so the
            // strip above the prompt editor does not keep showing the replayed `Thinking`.
            SandboxAgentEvent.ReplayFinished -> {
                replayingHistory = false
                updateTurnState(running = false)
                chatLog.appendThought(thinking.thought)
                thinking.clear()
                // No stop reason to show for a replayed turn, but it still gets a `moveUp` icon so
                // it can be scrolled back to, same as a live turn's completion notice.
                chatLog.markTurnEnd()
            }
            // An answer, a tool call, a plan or a permission request means the agent has moved on
            // from what it was thinking: the strip is hidden until the next thought arrives.
            is SandboxAgentEvent.AgentMessageChunk -> {
                thinking.endThought()
                chatLog.appendAgentChunk(event.text)
            }
            is SandboxAgentEvent.AgentThoughtChunk -> thinking.append(event.text)
            is SandboxAgentEvent.ToolCallStarted -> {
                thinking.endThought()
                showToolCall(event.toolCall, restored = replayingHistory)
            }
            is SandboxAgentEvent.ToolCallUpdated -> updateToolCall(event.toolCall)
            is SandboxAgentEvent.PlanUpdated -> {
                thinking.endThought()
                chatLog.showPlan(event.entries)
            }
            is SandboxAgentEvent.ModeChanged -> modeSelector.selectMatching { it.id == event.modeId }
            is SandboxAgentEvent.ModelChanged -> modelSelector.selectMatching { it.id == event.modelId }
            is SandboxAgentEvent.ConfigOptionsChanged -> populateConfigOptions(event.configOptions)
            is SandboxAgentEvent.SessionSelectorsUpdated ->
                populateSelectors(event.models, event.modes, event.configOptions)
            is SandboxAgentEvent.CommandsAvailable -> promptInput.setAvailableCommands(event.commands)
            is SandboxAgentEvent.PermissionRequested -> {
                thinking.endThought()
                showPermissionCard(event.request)
            }
            is SandboxAgentEvent.UsageUpdated -> updateTokenUsageButton(event)
            is SandboxAgentEvent.Error -> chatLog.appendError(event.message)
            // The list itself is read from the service when the dropdown is opened.
            is SandboxAgentEvent.SessionsListed -> Unit
            is SandboxAgentEvent.SessionListLoading -> updateSessionsLoading(event.loading)
            is SandboxAgentEvent.SessionRestored -> onSessionRestored(event)
            is SandboxAgentEvent.SessionRestoreRejected -> chatLog.appendError(event.message)
            SandboxAgentEvent.SessionCleared -> {
                clearTranscript()
                // The selectors belong to the session that was just left; the service publishes
                // the ones of the fresh session (reused or newly created) right after.
                populateSelectors(models = null, modes = null, configOptions = emptyList())
            }
            // The title is rendered by the tab of this panel (`SandboxAgentTabsPanel.TabChip`),
            // which listens to the very same event on the very same service.
            is SandboxAgentEvent.SessionTitleChanged -> Unit
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
    private fun onSessionRestored(event: SandboxAgentEvent.SessionRestored) {
        clearTranscript()
        val snapshot = event.snapshot
        if (snapshot != null) {
            renderSnapshot(snapshot)
        }
        if (!event.contextRestored && !readOnly) {
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

    private fun onSessionEstablished(event: SandboxAgentEvent.SessionEstablished) {
        chatLog.appendSystemMessage(
            (if (event.restored) "Session restored: " else "Session started: ") + event.sessionId,
        )
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
     * the Claude ACP agent) report the model, the permission mode and the reasoning budget.
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
        // Unlike the two above, the effort selector has no dedicated state to fall back on, so an
        // option that is not reported (any more) empties it instead of keeping stale choices —
        // which is also how it is reset between sessions (see [populateSelectors]).
        val effort = configOptions.firstOrNull {
            it.isCategory(SessionConfigCategory.THOUGHT_LEVEL, SessionConfigCategory.EFFORT)
        }
        effortConfigId = effort?.id
        if (effort != null) fill(effortSelector, effort) else effortSelector.setItems(emptyList(), null)
        val known = setOfNotNull(modelConfigId, modeConfigId, effortConfigId)
        populateExtraConfigOptions(configOptions.filterNot { it.id in known })
    }

    /**
     * Draws a selector for every reported option that has no fixed slot, so an option this panel
     * knows nothing about is still offered instead of being silently dropped — and a future one
     * needs no code at all. Whether an option exists depends on the model (`fast` is only reported
     * by the models supporting it), so an option that is no longer reported loses its selector.
     */
    private fun populateExtraConfigOptions(options: List<SessionConfigOption>) {
        // An option without choices has nothing to pick from: its selector would only ever be
        // hidden (see [FlatSelector.setItems]).
        val selectable = options.filter { it.choices.isNotEmpty() }
        val reported = selectable.map { it.id }.toSet()
        val stale = extraSelectors.keys - reported
        stale.forEach { configId -> extraSelectors.remove(configId)?.let { actionBarLeft.remove(it) } }
        selectable.forEach { option ->
            val selector = extraSelectors.getOrPut(option.id) {
                FlatSelector<SelectorItem>(
                    tooltip = option.presentableName,
                    presentation = { it.label },
                    onSelect = { service.setConfigOption(option.id, it.id) },
                    description = { it.description },
                ).also { actionBarLeft.add(it) }
            }
            fill(selector, option)
        }
        // Removing a component schedules no layout on its own, unlike `FlatSelector.setItems`.
        if (stale.isNotEmpty()) {
            actionBarLeft.revalidate()
            actionBarLeft.repaint()
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

    /** The effort is a `configOptions` selector only, so without its `configId` there is nothing to send. */
    private fun applyEffort(value: String) {
        val configId = effortConfigId ?: return
        service.setConfigOption(configId, value)
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
     * [SandboxAgentEvent.StateChanged] is applied before the service exposes it as its own.
     */
    private fun updateTurnState(running: Boolean, connected: Boolean = service.state == SessionState.READY) {
        if (running) {
            sendStopButton.icon = SandboxAgentIcons.Stop
            sendStopButton.toolTipText = "Stop"
            sendStopButton.isEnabled = true
        } else {
            sendStopButton.icon = SandboxAgentIcons.Send
            updateSendButtonTooltip()
            sendStopButton.isEnabled = connected
        }
    }

    private fun updateSessionState(state: SessionState, message: String?) {
        val source = service.readOnlySource
        if (source != null) {
            // Nothing ever connects here: the status names the file the transcript comes from.
            statusLabel.icon = null
            statusLabel.text = LocalSessionPresentation.readOnlyStatus(source.file)
            messageLabel.icon = null
            messageLabel.text = ""
            return
        }
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
        updateTurnState(service.isTurnRunning, connected)
        // The session controls only make sense while a session can exist.
        newSessionButton.isEnabled = connected
        if (!connected) {
            modelSelector.isVisible = false
            modeSelector.isVisible = false
            effortSelector.isVisible = false
            extraSelectors.values.forEach { it.isVisible = false }
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

    private fun updateTokenUsageButton(event: SandboxAgentEvent.UsageUpdated) {
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

    /**
     * Moves the keyboard focus to the prompt editor of this tab.
     *
     * Called by [SandboxAgentTabsPanel] right after a `CardLayout` switch shows this panel: hiding
     * the previously active tab does not move the focus that was still sitting on one of its
     * components, so without this the newly shown tab can look unresponsive — its own controls
     * are visible but the keyboard focus is still, invisibly, on the tab just left.
     */
    fun focusPrompt() {
        if (readOnly) return
        promptInput.requestFocusInWindow()
    }

    /**
     * Called by [SandboxAgentTabsPanel] once a `CardLayout` switch showed this panel: the
     * transcript browser was not laid out while hidden and has to catch up (see
     * [TranscriptPanel.onShown]).
     */
    fun onShown() {
        chatLog.onShown()
    }

    /**
     * Called by [SandboxAgentTabsPanel] when another tab took this panel's place: the transcript
     * holds its events back until the panel is shown again (see [TranscriptPanel.onHidden]).
     */
    fun onHidden() {
        chatLog.onHidden()
    }

    override fun dispose() {
        service.removeListener(listener)
    }

    private companion object {

        /** Distance, in pixels, from the tool window edge to the bordered content (see TranscriptPanel). */
        private const val CONTENT_MARGIN = SandboxAgentLayout.CONTENT_MARGIN

        /** Distance, in pixels, from the top edge of the tool window to the first toolbar row. */
        private const val ROW_TOP_MARGIN = SandboxAgentLayout.ROW_TOP_MARGIN

        /** Horizontal gap between the action bar buttons, also applied on its outer sides. */
        private const val ACTION_BAR_GAP = 2
    }
}
