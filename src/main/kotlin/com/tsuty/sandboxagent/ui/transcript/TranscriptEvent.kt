package com.tsuty.sandboxagent.ui.transcript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    @SerialName("warning") WARNING,
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
 * starts as plain text instead, which is what [StreamEvent] appends to. While it streams,
 * [BlockHtmlEvent] replaces it with the Markdown rendering of what arrived so far now and then,
 * and the chunks after that are appended as plain text below the rendering until the next one.
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

/** Replaces the body of a block, which is how a stream gets its Markdown rendering. */
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
 * Adds images below a block of the agent answer, apart from its Markdown body: the body is
 * replaced as the answer streams on, the images stay. [sources] are `data:` URIs of a raster
 * type only (see [TranscriptImages]).
 */
@Serializable
@SerialName("images")
data class ImagesEvent(val id: String, val sources: List<String>, val alt: String = "") : TranscriptEvent

/**
 * Draws a compaction of the context, or redraws the entry of [id] in place: a compaction keeps
 * the place it was first reported at. [headline] names it and its state; the summary, when there
 * is one, is folded away behind an anchor.
 */
@Serializable
@SerialName("compaction")
data class CompactionEvent(
    val id: String,
    val headline: String,
    val running: Boolean = false,
    val failed: Boolean = false,
    val summaryHtml: String = "",
) : TranscriptEvent

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

/** One option of a [TranscriptQuestion]; [preview] is plain text, shown in a monospace box. */
@Serializable
data class TranscriptQuestionOption(
    val label: String,
    val description: String? = null,
    val preview: String? = null,
    /** The agent's Markdown, rendered like the options of a form (see [TranscriptElicitationOption]). */
    val labelHtml: String = "",
    val descriptionHtml: String = "",
    val previewHtml: String = "",
)

/** One question the agent asked, as it was shown: a short [header], the text and its options. */
@Serializable
data class TranscriptQuestion(
    val header: String? = null,
    val question: String,
    val multiSelect: Boolean = false,
    val options: List<TranscriptQuestionOption> = emptyList(),
    /** [question] as the Markdown it is, rendered by [TranscriptHtml.renderQuestion]. */
    val questionHtml: String = "",
)

/**
 * An ask-the-user tool call: the body of its folded card shows the questions that were asked
 * instead of the raw input. [headline] is the line of the card while it is folded.
 */
@Serializable
data class TranscriptQuestions(
    val headline: String,
    val questions: List<TranscriptQuestion> = emptyList(),
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
    val question: TranscriptQuestions? = null,
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
    /**
     * Tool call the request belongs to, which is what an [OpenDiffAction] of [flat] addresses:
     * [id] is the id of the request itself and names no tool call the host knows.
     */
    val toolCallId: String = "",
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

/** One choice of a select field of an elicitation card. */
@Serializable
data class TranscriptElicitationOption(
    val value: String,
    /** Plain text, for the places that take no markup (an entry of a drop down). */
    val title: String,
    val description: String? = null,
    val preview: String? = null,
    /** The agent's Markdown, rendered by [TranscriptHtml.renderQuestionInline] / [TranscriptHtml.renderQuestion]. */
    val titleHtml: String = "",
    val descriptionHtml: String = "",
    /** Shown in a monospace box while the option is selected; the whitespace of its lines is kept. */
    val previewHtml: String = "",
)

/**
 * One field of an elicitation card. [kind] is `text`, `number`, `boolean`, `choice` or `multi`;
 * the rest are the constraints of the schema that apply to that kind, which the document checks
 * before it sends anything (the host checks the answer again).
 */
@Serializable
data class TranscriptElicitationField(
    val key: String,
    val kind: String,
    /** Plain text; [titleHtml] / [descriptionHtml] are the agent's Markdown, rendered. */
    val title: String,
    val description: String? = null,
    val titleHtml: String = "",
    val descriptionHtml: String = "",
    val required: Boolean = false,
    /** Text, number, boolean or list of texts, matching [kind]; `null` for no default. */
    val default: JsonElement? = null,
    val options: List<TranscriptElicitationOption> = emptyList(),
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val format: String? = null,
    val pattern: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val integer: Boolean = false,
    val minItems: Int? = null,
    val maxItems: Int? = null,
)

/** Inline form of an `elicitation/create` (form mode): the agent asks the user for structured input. */
@Serializable
@SerialName("elicitation")
data class ElicitationEvent(
    val id: String,
    val message: String,
    val fields: List<TranscriptElicitationField> = emptyList(),
    /** [message] as the Markdown it is, rendered by [TranscriptHtml.renderQuestion]. */
    val messageHtml: String = "",
) : TranscriptEvent

/** Locks an elicitation card once it was answered (or the turn ended without an answer). */
@Serializable
@SerialName("elicitationResolved")
data class ElicitationResolvedEvent(val id: String, val status: String) : TranscriptEvent

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
