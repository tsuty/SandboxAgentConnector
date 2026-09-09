package com.tsuty.remoteagent.ui.transcript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What the transcript document asks the host to do: everything that needs the IDE (opening a file,
 * the diff viewer, the clipboard) or the agent (answering a permission, sending a prompt).
 *
 * Like [TranscriptEvent] this is plain Kotlin: a different host only has to map the same JSON.
 */
@Serializable
sealed interface TranscriptAction

/** The document finished loading and is ready to receive events. */
@Serializable
@SerialName("ready")
data object ReadyAction : TranscriptAction

@Serializable
@SerialName("openFile")
data class OpenFileAction(val path: String, val line: Int? = null) : TranscriptAction

/** Opens `ToolCall.diffs[diffIndex]` of the tool call in the platform diff viewer. */
@Serializable
@SerialName("openDiff")
data class OpenDiffAction(val toolCallId: String, val diffIndex: Int) : TranscriptAction

@Serializable
@SerialName("openUrl")
data class OpenUrlAction(val url: String) : TranscriptAction

/** Follows an attachment chip of a `You` block, addressed by `PromptAttachment.key`. */
@Serializable
@SerialName("openAttachment")
data class OpenAttachmentAction(val key: String) : TranscriptAction

/** Copies the Markdown source of a block; the document only knows its id, the host keeps the text. */
@Serializable
@SerialName("copyBlock")
data class CopyBlockAction(val id: String) : TranscriptAction

/** Copies a piece of text the document has itself, e.g. one code block of an answer. */
@Serializable
@SerialName("copyText")
data class CopyTextAction(val text: String) : TranscriptAction

/** Sends the task list of a block back as a prompt, with the check marks of the selection. */
@Serializable
@SerialName("submitChoices")
data class SubmitChoicesAction(val id: String, val markdown: String) : TranscriptAction

/** Answers the pending `session/request_permission` of a card. */
@Serializable
@SerialName("permission")
data class PermissionAction(val id: String, val optionId: String) : TranscriptAction

/** Parsing of the actions, so a document that speaks an older protocol cannot break the host. */
object TranscriptProtocol {

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(events: List<TranscriptEvent>): String =
        json.encodeToString(ListSerializer(TranscriptEvent.serializer()), events)

    /** `null` for a message this host does not understand. */
    fun decodeAction(payload: String): TranscriptAction? =
        runCatching { json.decodeFromString<TranscriptAction>(payload) }.getOrNull()
}
