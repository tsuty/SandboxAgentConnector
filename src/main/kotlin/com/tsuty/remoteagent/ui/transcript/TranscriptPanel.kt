package com.tsuty.remoteagent.ui.transcript

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefApp
import com.tsuty.remoteagent.acp.PermissionOption
import com.tsuty.remoteagent.acp.PlanEntry
import com.tsuty.remoteagent.acp.PromptAttachment
import com.tsuty.remoteagent.acp.ToolCall
import com.tsuty.remoteagent.acp.ToolKind
import com.tsuty.remoteagent.acp.key
import com.tsuty.remoteagent.service.PermissionRequest
import com.tsuty.remoteagent.settings.RemoteAgentSettingsBridge
import com.tsuty.remoteagent.settings.RemoteAgentSettingsState
import com.tsuty.remoteagent.ui.PromptAttachmentNavigation
import com.tsuty.remoteagent.ui.RemoteAgentColors
import com.tsuty.remoteagent.ui.ToolCallPresentation
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/**
 * Scrollable transcript of the session: user prompts, streamed agent text, the agent's thoughts,
 * tool call cards, inline permission cards, the current plan and completion notices.
 *
 * The panel itself draws nothing any more — it keeps the state a transcript needs (which block is
 * streaming, which group of tool calls is open, what a `Copy` has to yield) and turns the session
 * events into [TranscriptEvent]s for the document that does the drawing (see [TranscriptView]).
 *
 * Every method must be called on the EDT.
 *
 * [onSubmitPrompt] sends a follow up prompt on behalf of the transcript: agents offer choices as a
 * Markdown task list, which the document renders as check boxes with a `Send` action. It returns
 * `false` when the prompt could not be sent.
 */
class TranscriptPanel(
    private val project: Project,
    private val onSubmitPrompt: (String) -> Boolean = { false },
) : JPanel(BorderLayout()), Disposable {

    private val view: TranscriptView =
        if (JBCefApp.isSupported()) {
            JcefTranscriptView(
                parent = this,
                onAction = { onAction(it) },
                onPageSource = { html -> showPageSource(html) },
            )
        } else {
            PlainTextTranscriptView()
        }

    /** Ids are handed out by the host: they are what every later patch of an entry addresses. */
    private var sequence = 0

    /** Block the agent answer streams into, `null` when nothing is streaming. */
    private var streamingId: String? = null
    private val streamed = StringBuilder()

    private var planId: String? = null

    /** Block that started the current turn, which its `moveUp` action scrolls back to. */
    private var turnStartId: String? = null

    /** Last `You` / `Agent` block, which the action closing a turn is anchored to. */
    private var lastMessageId: String? = null

    /** Last `You` block, so attachments reported after their prompt still land in it. */
    private var userBlockId: String? = null

    /** Attachments drawn as chips, by key: a chip is followed through the host, not the document. */
    private val shownAttachments = LinkedHashMap<String, PromptAttachment>()

    /** Markdown source of the blocks that offer a `Copy`, which is what it puts on the clipboard. */
    private val sources = mutableMapOf<String, String>()

    /** Group collecting the tool calls of the current run; every other entry closes it. */
    private var group: OpenGroup? = null

    /** Group a tool call was filed into, so an update never opens a second one. */
    private val toolCallGroups = mutableMapOf<String, String?>()

    /** Latest state of every tool call of the session, merged from its updates. */
    private val toolCalls = mutableMapOf<String, ToolCall>()

    private val permissions = mutableMapOf<String, Pending>()

    private class OpenGroup(val id: String, val startedAt: Long?)

    private class Pending(
        val request: PermissionRequest,
        val options: List<PermissionOption>,
        val onSelect: (PermissionOption) -> Unit,
    )

    init {
        isOpaque = true
        add(view.component, BorderLayout.CENTER)
        postTheme()
        postAgent()
        // The document keeps its DOM across a theme change, so only the CSS variables are resent:
        // the scroll position and every folded entry survive a light / dark switch.
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { postTheme() })
        connection.subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { _: EditorColorsScheme? -> postTheme() },
        )
        // A new base font size is a theme change as well: the sizes of the document are derived
        // from the one the settings carry (see ThemeTokens).
        project.messageBus.connect(this).subscribe(
            RemoteAgentSettingsBridge.TOPIC,
            RemoteAgentSettingsBridge.Listener {
                postTheme()
                // Another agent was picked, or its drawing finished downloading (see `AgentIcon`).
                postAgent()
            },
        )
        object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) = view.showSearch()
        }.registerCustomShortcutSet(CommonShortcuts.getFind(), this, this)
    }

    override fun getBackground(): Color = RemoteAgentColors.PANEL_BACKGROUND

    /** Colors of the current IDE theme, sized with the base font size of the settings. */
    private fun postTheme() {
        val baseFontSize = RemoteAgentSettingsState.getInstance(project).transcriptFontSizeOrDefault()
        view.post(ThemeTokens.event(baseFontSize))
    }

    /**
     * Name and drawing of the selected agent, which is what its blocks are headed with. A drawing
     * that cannot be turned into a `<symbol>` is simply not sent, and the bundled one stays.
     */
    private fun postAgent() {
        val settings = RemoteAgentSettingsState.getInstance(project)
        val svg = settings.agentIconSvgOrNull()
        val symbol = svg?.let { runCatching { TranscriptIcons.agentSymbol(it) }.getOrNull() }
        view.post(AgentEvent(name = settings.agentNameOrDefault(), iconSymbol = symbol))
    }

    /**
     * Draws a prompt with the files / selections that were attached to it: what was attached is
     * part of what the user asked, so it stays readable in the transcript instead of vanishing
     * with the editor. The `Copy` action keeps yielding the typed text alone.
     */
    fun appendUserPrompt(text: String, attachments: List<PromptAttachment> = emptyList()) {
        val id = block(TranscriptRole.USER, text)
        turnStartId = id
        lastMessageId = id
        userBlockId = id
        shownAttachments.clear()
        appendUserPromptAttachments(attachments)
    }

    /** Adds chips to the last `You` block; ignored when no prompt is on screen to attach them to. */
    fun appendUserPromptAttachments(attachments: List<PromptAttachment>) {
        val id = userBlockId ?: return
        val added = attachments.filter { shownAttachments.put(it.key, it) == null }
        if (added.isEmpty()) return
        view.post(
            AttachmentsEvent(
                id = id,
                chips = added.map { TranscriptChip(key = it.key, label = it.label, tooltip = it.path) },
            ),
        )
    }

    fun appendSystemMessage(text: String) {
        block(TranscriptRole.SYSTEM, text)
    }

    fun appendError(text: String) {
        block(TranscriptRole.ERROR, text)
    }

    /** Appends a chunk of the agent answer, creating the streaming block on demand. */
    fun appendAgentChunk(text: String) {
        if (text.isEmpty()) return
        val id = streamingId ?: startStreaming()
        streamed.append(text)
        view.post(StreamEvent(id, text))
    }

    /**
     * Appends a whole agent answer at once, which is how a restored snapshot is drawn: the text is
     * already complete, so it goes straight to its Markdown rendering.
     */
    fun appendAgentMessage(text: String) {
        if (text.isBlank()) return
        appendAgentChunk(text)
        endStreaming()
    }

    /**
     * Files the chain of thought of a finished turn away: it streams live in the strip above the
     * prompt editor (see `ThinkingStatusPanel`) and is only kept here as a folded, quiet anchor.
     */
    fun appendThought(text: String) {
        if (text.isBlank()) return
        block(TranscriptRole.THOUGHT, text.trim())
    }

    fun appendCompletion(text: String) {
        block(TranscriptRole.COMPLETION, text)
        anchorTurnEnd()
    }

    /**
     * Marks the end of a turn that has no completion notice of its own, which is how a session
     * restored by replaying its history ends: there is no stop reason to show, but the turn still
     * deserves the action jumping back to its `You` prompt.
     */
    fun markTurnEnd() {
        endStreaming()
        closeGroup()
        anchorTurnEnd()
    }

    /** Shows or refreshes the plan reported by a `plan` session update. */
    fun showPlan(entries: List<PlanEntry>) {
        // Written as a GFM task list so the document draws real check boxes; the running task is
        // the only one emphasized. The plan is read only, so they stay check boxes and never
        // become an answerable choice.
        val text = entries.joinToString("\n") { entry ->
            when (entry.status) {
                "completed" -> "- [x] ${entry.content}"
                "in_progress" -> "- [ ] **${entry.content}**"
                else -> "- [ ] ${entry.content}"
            }
        }
        val id = planId
        if (id != null) {
            view.post(BlockHtmlEvent(id, TranscriptHtml.render(text)))
            return
        }
        planId = block(TranscriptRole.PLAN, text, interactive = false)
    }

    /**
     * Draws a `tool_call` as a card, or refreshes the card it already has, and returns the merged
     * state of the call (an update only carries what changed).
     *
     * Every kind but `switch_mode` joins the foldable group of the current run: a mode switch
     * changes what the agent may do for the rest of the session, so its framed block stays an
     * entry of its own. [restored] marks a card redrawn from a stored transcript or replayed by
     * the agent, which its group must not time as a live run.
     */
    fun showToolCall(toolCall: ToolCall, restored: Boolean = false): ToolCall {
        val id = toolCall.toolCallId
        val known = toolCalls[id]
        val merged = known?.mergeWith(toolCall) ?: toolCall
        toolCalls[id] = merged
        val groupId = when {
            // An update keeps the group its card was filed into when it was first drawn.
            toolCallGroups.containsKey(id) -> toolCallGroups[id]
            merged.kind == ToolKind.SWITCH_MODE -> {
                endStreaming()
                closeGroup()
                null
            }

            else -> {
                endStreaming()
                openGroup(timed = !restored).id
            }
        }
        toolCallGroups[id] = groupId
        view.post(TranscriptToolCall.event(merged, groupId))
        return merged
    }

    /**
     * Shows the inline approval card of a `session/request_permission`.
     *
     * The buttons are the options the agent sent (`allow_once`, `reject_always`, ...); the card
     * never invents one of its own. A request without any option can only be cancelled, which is
     * the one button it gets.
     */
    fun showPermission(request: PermissionRequest, onSelect: (PermissionOption) -> Unit) {
        endStreaming()
        closeGroup()
        val id = request.requestId.key
        permissions[id] = Pending(request, request.options, onSelect)
        val event = TranscriptToolCall.permissionEvent(id, request.toolCall, request.options)
        view.post(
            if (event.options.isEmpty()) {
                event.copy(options = listOf(TranscriptPermissionOption(CANCEL_OPTION, "Cancel")))
            } else {
                event
            },
        )
    }

    /** Locks every card that is still waiting for an answer (the turn finished, the session went). */
    fun cancelPermissions() {
        permissions.keys.toList().forEach { id ->
            permissions.remove(id)
            view.post(PermissionResolvedEvent(id, "Cancelled"))
        }
    }

    fun clear() {
        streamingId = null
        streamed.setLength(0)
        planId = null
        turnStartId = null
        lastMessageId = null
        userBlockId = null
        shownAttachments.clear()
        sources.clear()
        group = null
        toolCallGroups.clear()
        toolCalls.clear()
        permissions.clear()
        view.post(ResetEvent())
    }

    fun showSearch() = view.showSearch()

    /** Moves to the newest entry, which is where a prompt that was just sent is answered. */
    fun scrollToBottom() = view.scrollToBottom()

    // ------------------------------------------------------------------ blocks

    /**
     * Adds a block of the conversation. Anything but a tool call closes the open group, which is
     * what makes a group span exactly the calls between two entries of the conversation.
     */
    private fun block(role: TranscriptRole, text: String, interactive: Boolean = true): String {
        endStreaming()
        closeGroup()
        val id = nextId(role.name.lowercase())
        val body = if (interactive && role == TranscriptRole.AGENT) {
            TranscriptHtml.renderInteractive(text)
        } else {
            TranscriptHtml.Body(TranscriptHtml.render(text))
        }
        view.post(BlockEvent(id = id, role = role, html = body.html, choices = body.choices))
        if (role == TranscriptRole.USER || role == TranscriptRole.AGENT) {
            sources[id] = text
            lastMessageId = id
        }
        return id
    }

    private fun startStreaming(): String {
        closeGroup()
        val id = nextId("agent")
        streamingId = id
        streamed.setLength(0)
        view.post(BlockEvent(id = id, role = TranscriptRole.AGENT, streaming = true))
        lastMessageId = id
        return id
    }

    /**
     * Closes the streaming block so the next chunk starts a new one, replacing the plain text
     * shown while the answer streamed with its Markdown rendering.
     */
    private fun endStreaming() {
        val id = streamingId ?: return
        streamingId = null
        val text = streamed.toString()
        streamed.setLength(0)
        if (text.isBlank()) return
        sources[id] = text
        val body = TranscriptHtml.renderInteractive(text)
        view.post(BlockHtmlEvent(id, body.html, body.choices))
    }

    private fun anchorTurnEnd() {
        val block = lastMessageId ?: return
        val target = turnStartId ?: return
        view.post(TurnEndEvent(block, target))
    }

    private fun openGroup(timed: Boolean): OpenGroup {
        group?.let { return it }
        val opened = OpenGroup(nextId("group"), if (timed) System.currentTimeMillis() else null)
        group = opened
        view.post(GroupEvent(id = opened.id, timed = timed))
        return opened
    }

    /** Closes the open group, turning its `Working…` title into the time the run took. */
    private fun closeGroup() {
        val open = group ?: return
        group = null
        view.post(
            GroupEvent(
                id = open.id,
                timed = open.startedAt != null,
                closed = true,
                elapsedMs = open.startedAt?.let { System.currentTimeMillis() - it },
            ),
        )
    }

    private fun nextId(prefix: String): String = "$prefix-${++sequence}"

    // ------------------------------------------------------------------ actions

    /** Everything the document cannot do on its own: the IDE, the clipboard and the agent. */
    private fun onAction(action: TranscriptAction) {
        when (action) {
            ReadyAction -> postTheme()
            is OpenFileAction -> ToolCallPresentation.openFile(project, action.path, action.line)
            is OpenDiffAction -> toolCalls[action.toolCallId]?.diffs?.getOrNull(action.diffIndex)
                ?.let { ToolCallPresentation.openFullDiff(project, it) }

            is OpenUrlAction -> BrowserUtil.browse(action.url)
            is OpenAttachmentAction -> followAttachment(action.key)
            is CopyBlockAction -> copy(sources[action.id])
            is CopyTextAction -> copy(action.text)
            is SubmitChoicesAction -> onSubmitPrompt(action.markdown)
            is PermissionAction -> answerPermission(action)
        }
    }

    /**
     * A selection chip shows the text that was sent (which may have changed in the editor since),
     * a file chip opens the file — the same two behaviours the strip above the prompt editor has.
     */
    private fun followAttachment(key: String) {
        val attachment = shownAttachments[key] ?: return
        if (!PromptAttachmentNavigation.isNavigable(attachment)) return
        if (attachment.isSelection) {
            PromptAttachmentNavigation.showAttachedContent(project, attachment)
        } else {
            PromptAttachmentNavigation.openInEditor(project, attachment)
        }
    }

    private fun answerPermission(action: PermissionAction) {
        val pending = permissions.remove(action.id) ?: return
        val option = pending.options.firstOrNull { it.optionId == action.optionId }
        if (option == null) {
            pending.request.cancel()
            view.post(PermissionResolvedEvent(action.id, "Cancelled"))
            return
        }
        pending.onSelect(option)
        view.post(PermissionResolvedEvent(action.id, option.presentableName))
    }

    /**
     * Shows the markup of the transcript document in a read only editor tab, which is what the
     * `Show Page Source` entry of the internal mode context menu ends in (see [JcefTranscriptView]).
     * The tab is backed by an in memory file: nothing is written to the project.
     */
    private fun showPageSource(html: String) {
        if (html.isEmpty()) return
        val file = LightVirtualFile("transcript-page-source.html", html)
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /**
     * Writes to the clipboard and says so in the document: the button the reader pressed lives in
     * the browser, so the only way to confirm the copy is to send the message back.
     */
    private fun copy(text: String?) {
        if (text.isNullOrEmpty()) return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        view.post(ToastEvent(COPIED_MESSAGE))
    }

    override fun dispose() {
        permissions.clear()
    }

    private companion object {
        /** Option id of the `Cancel` button a request without any option of its own gets. */
        private const val CANCEL_OPTION = "__cancel"

        /** Confirmation of every copy button of the transcript (see [copy]). */
        private const val COPIED_MESSAGE = "Copied to clipboard"
    }
}
