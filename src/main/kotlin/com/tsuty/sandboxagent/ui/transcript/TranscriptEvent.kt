package com.tsuty.sandboxagent.ui.transcript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the host tells the transcript document to do (see `docs/development/TRANSCRIPT_WEBVIEW.md`).
 *
 * The events are a patch protocol, not a rendering of the whole log: every one of them addresses
 * the entry it belongs to by [id], so a streamed answer, a refreshed plan or a finished tool call
 * only touches its own node and the document keeps its scroll position and its folding state.
 *
 * The definitions are deliberately free of any IDE type: they are the contract a host adapter has
 * to speak, so the very same document can later be hosted by something else than JCEF.
 */
@Serializable
sealed interface TranscriptEvent

/** Drops every entry: a session switch, a new session or a fresh connection. */
@Serializable
@SerialName("reset")
data class ResetEvent(val sessionId: String? = null) : TranscriptEvent

/**
 * Theme colors and fonts as CSS custom properties (see [ThemeTokens]).
 *
 * Applying them does not rebuild the DOM, so a light / dark switch keeps the transcript exactly
 * where the reader left it.
 */
@Serializable
@SerialName("theme")
data class ThemeEvent(val tokens: Map<String, String>, val dark: Boolean) : TranscriptEvent

/**
 * How the agent presents itself in the transcript: the [name] its blocks are headed with, and the
 * drawing shown next to it.
 *
 * Both come from the ACP registry entry of the selected agent, kept in the settings (see
 * `AgentIcon`), and both are optional — the document keeps its own `Agent` title and its bundled
 * drawing for whatever it is not told.
 *
 * [iconSymbol] is a whole `<symbol>` (see [TranscriptIcons.agentSymbol]), not a file: it replaces
 * the one of the sprite, so the blocks already on screen follow it as well.
 */
@Serializable
@SerialName("agent")
data class AgentEvent(val name: String? = null, val iconSymbol: String? = null) : TranscriptEvent

/** Who wrote a block, which is what decides its frame, its icon and its text style. */
@Serializable
enum class TranscriptRole {
    @SerialName("user") USER,
    @SerialName("agent") AGENT,
    @SerialName("thought") THOUGHT,
    @SerialName("plan") PLAN,
    @SerialName("system") SYSTEM,
    @SerialName("completion") COMPLETION,
    @SerialName("error") ERROR,
}

/** One entry of a task list the agent offered as choices. */
@Serializable
data class TranscriptChoice(val label: String, val checked: Boolean = false)

/**
 * A task list found in a Markdown block, kept out of [BlockEvent.html] so it can be rendered as
 * real check boxes. [slot] is the index of the `data-choices` placeholder the HTML left for it.
 */
@Serializable
data class TranscriptChoices(val slot: Int, val items: List<TranscriptChoice>)

/**
 * Attachment chip of a `You` block, matching the strip above the prompt editor.
 *
 * [key] is what the host looks the attachment up by when the chip is followed: a selection shows
 * the text that was sent, a file opens in the editor (see `PromptAttachmentNavigation`).
 */
@Serializable
data class TranscriptChip(
    val key: String,
    val label: String,
    val tooltip: String? = null,
)

/**
 * Adds a block of the conversation.
 *
 * [html] is already sanitized (see [TranscriptHtml]). A block created with [streaming] `true`
 * starts as plain text instead, which is what [StreamEvent] appends to until the answer is
 * complete and [BlockHtmlEvent] replaces it with its Markdown rendering.
 */
@Serializable
@SerialName("block")
data class BlockEvent(
    val id: String,
    val role: TranscriptRole,
    val html: String = "",
    val streaming: Boolean = false,
    val choices: List<TranscriptChoices> = emptyList(),
) : TranscriptEvent

/** Appends raw text to a streaming block; the text is not Markdown yet, so it is shown as is. */
@Serializable
@SerialName("stream")
data class StreamEvent(val id: String, val text: String) : TranscriptEvent

/** Replaces the body of a block, which is how a finished stream gets its Markdown rendering. */
@Serializable
@SerialName("blockHtml")
data class BlockHtmlEvent(
    val id: String,
    val html: String,
    val choices: List<TranscriptChoices> = emptyList(),
) : TranscriptEvent

/**
 * Adds chips to a `You` block. They arrive after the prompt itself when a replayed history
 * reports the attachments one at a time.
 */
@Serializable
@SerialName("attachments")
data class AttachmentsEvent(val id: String, val chips: List<TranscriptChip>) : TranscriptEvent

/**
 * Opens or closes the foldable group collecting the tool calls of one run.
 *
 * The elapsed time is counted by the document itself: [timed] tells it whether this run is
 * happening now (a replayed history is not, so it only reports `Worked`), and [elapsedMs] is the
 * final duration once the group is closed.
 */
@Serializable
@SerialName("group")
data class GroupEvent(
    val id: String,
    val timed: Boolean = true,
    val closed: Boolean = false,
    val elapsedMs: Long? = null,
) : TranscriptEvent

/** One clickable piece of a flat tool call line (see `ToolCallPresentation.FlatTarget`). */
@Serializable
data class TranscriptTarget(
    val text: String,
    val path: String? = null,
    val line: Int? = null,
    val url: String? = null,
    /** Index into `ToolCall.diffs`, so the host can open the change in the platform diff viewer. */
    val diffIndex: Int? = null,
)

/** A tool call whose whole story fits on one line (see `ToolCallPresentation.FlatEntry`). */
@Serializable
data class TranscriptFlatEntry(
    val prefix: String,
    val targets: List<TranscriptTarget>,
    val separator: String = "→",
    val expandable: Boolean = false,
)

/** A `switch_mode` tool call, which is shown in full instead of being folded away. */
@Serializable
data class TranscriptSwitchMode(
    val headline: String,
    val modeId: String? = null,
    val planHtml: String = "",
)

/** Adds a tool call card, or refreshes the one that already carries [id]. */
@Serializable
@SerialName("toolCall")
data class ToolCallEvent(
    val id: String,
    /** Group the card belongs to; `null` for a call that stays an entry of its own. */
    val groupId: String? = null,
    val kind: String? = null,
    val anchor: String = "",
    val statusLabel: String = "",
    val failed: Boolean = false,
    val flat: TranscriptFlatEntry? = null,
    val bodyHtml: String = "",
    val switchMode: TranscriptSwitchMode? = null,
) : TranscriptEvent

/** One button of an inline permission card, built from what the agent offered. */
@Serializable
data class TranscriptPermissionOption(
    val optionId: String,
    val label: String,
    val allow: Boolean = false,
    val default: Boolean = false,
)

/** Inline approval card of a `session/request_permission`. */
@Serializable
@SerialName("permission")
data class PermissionEvent(
    val id: String,
    val headline: String,
    val bodyHtml: String = "",
    /** Shown right away instead of being folded away: the plan behind a `switch_mode`. */
    val expanded: Boolean = false,
    val flat: TranscriptFlatEntry? = null,
    val options: List<TranscriptPermissionOption> = emptyList(),
) : TranscriptEvent

/** Locks a permission card once it was answered (or the turn ended without an answer). */
@Serializable
@SerialName("permissionResolved")
data class PermissionResolvedEvent(val id: String, val status: String) : TranscriptEvent

/**
 * Ends a turn: [id] is the last message block of it, which gets the action scrolling back to the
 * block that started the turn ([targetId]).
 */
@Serializable
@SerialName("turnEnd")
data class TurnEndEvent(val id: String, val targetId: String) : TranscriptEvent

/**
 * A message shown over the bottom edge of the transcript for a moment.
 *
 * It is what tells the reader that a copy went through: the clipboard belongs to the host, so the
 * confirmation can only be given once the text really was written to it.
 */
@Serializable
@SerialName("toast")
data class ToastEvent(val text: String) : TranscriptEvent
