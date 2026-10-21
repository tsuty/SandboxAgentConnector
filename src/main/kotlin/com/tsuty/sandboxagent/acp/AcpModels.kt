package com.tsuty.sandboxagent.acp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime

/** ACP protocol version implemented by this plugin (integer `1`, see the ACP v1 spec). */
const val ACP_PROTOCOL_VERSION: Int = 1

/**
 * Shared JSON codec for the ACP (Agent Client Protocol) layer.
 *
 * `ignoreUnknownKeys` keeps forward compatibility with agents that send newer fields, and
 * `explicitNulls = false` omits optional fields instead of sending `null`, as ACP expects.
 */
val AcpJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}

// ---------------------------------------------------------------------------------------------
// JSON-RPC 2.0 envelope
// ---------------------------------------------------------------------------------------------

/**
 * JSON-RPC ids are either a string or a number. They are kept as raw [JsonPrimitive] so a
 * response echoes back exactly the same shape the agent used in its request.
 */
typealias JsonRpcId = JsonPrimitive

fun rpcId(value: Long): JsonRpcId = JsonPrimitive(value)

fun rpcId(value: String): JsonRpcId = JsonPrimitive(value)

/** Stable map key for a JSON-RPC id, independent of the string/number representation. */
val JsonRpcId.key: String get() = content

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonRpcId,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonRpcId,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        fun methodNotFound(method: String) = JsonRpcError(METHOD_NOT_FOUND, "Method not found: $method")
    }
}

/**
 * The `data.details` string agents attach to an error. `message` is often a generic label
 * ("Internal error") and only `details` names the actual cause, so it must not be dropped.
 * A non string payload degrades to its JSON text, a blank or absent one to `null`.
 */
val JsonRpcError.details: String?
    get() = when (val detail = (data as? JsonObject)?.get("details")) {
        null, is JsonNull -> null
        is JsonPrimitive -> detail.content.ifBlank { null }
        else -> detail.toString()
    }

/** [JsonRpcError.message] followed by [JsonRpcError.details] whenever the agent sent one. */
val JsonRpcError.fullMessage: String
    get() = details?.let { "$message: $it" } ?: message

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

/** Method names of ACP v1. Client → Agent unless stated otherwise. */
object AcpMethods {
    const val INITIALIZE = "initialize"
    const val AUTHENTICATE = "authenticate"
    const val SESSION_NEW = "session/new"
    const val SESSION_LOAD = "session/load"
    const val SESSION_LIST = "session/list"
    const val SESSION_RESUME = "session/resume"
    const val SESSION_DELETE = "session/delete"
    const val SESSION_PROMPT = "session/prompt"
    const val SESSION_CANCEL = "session/cancel"
    const val SESSION_SET_MODE = "session/set_mode"
    const val SESSION_SET_MODEL = "session/set_model"
    const val SESSION_SET_CONFIG_OPTION = "session/set_config_option"

    /** Agent → Client notification carrying [SessionNotification]. */
    const val SESSION_UPDATE = "session/update"

    /** Agent → Client request answered with [RequestPermissionResult] in the response `result`. */
    const val SESSION_REQUEST_PERMISSION = "session/request_permission"
}

/**
 * A tolerant boolean serializer that handles both JSON booleans (`true`/`false`)
 * and JSON objects (`{}`) or other representations used by ACP / MCP agents
 * for capability flags.
 *
 * An object (`JsonObject`) indicates capability presence/support (`true`).
 * Booleans/strings decode according to their boolean value.
 */
object FlexibleBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.tsuty.sandboxagent.acp.FlexibleBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (element is JsonNull) {
                    false
                } else {
                    element.booleanOrNull
                        ?: element.content.toBooleanStrictOrNull()
                        ?: false
                }
            }
            is JsonObject -> true
            else -> false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) {
        encoder.encodeBoolean(value)
    }
}

/**
 * A tolerant timestamp serializer: agents report the last update of a session either as epoch
 * milliseconds, as epoch seconds or as an ISO-8601 instant. Everything is normalized to epoch
 * milliseconds, and an unparsable value degrades to [UNKNOWN] instead of failing the message.
 */
object FlexibleEpochMillisSerializer : KSerializer<Long> {
    /** Timestamp of a value that could not be parsed; treated as "the agent did not report one". */
    const val UNKNOWN: Long = 0L

    /** Values below this bound are treated as epoch seconds (year 33658 in milliseconds). */
    private const val SECONDS_UPPER_BOUND = 100_000_000_000L

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.tsuty.sandboxagent.acp.FlexibleEpochMillis", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return UNKNOWN
        val content = primitive.contentOrNull ?: return UNKNOWN
        content.toLongOrNull()?.let { return normalize(it) }
        content.toDoubleOrNull()?.let { return normalize(it.toLong()) }
        return runCatching { Instant.parse(content).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(content).toInstant().toEpochMilli() }
            .getOrDefault(UNKNOWN)
    }

    private fun normalize(value: Long): Long = if (value in 1 until SECONDS_UPPER_BOUND) value * 1000 else value

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

// ---------------------------------------------------------------------------------------------
// initialize
// ---------------------------------------------------------------------------------------------

@Serializable
data class ClientInfo(
    val name: String,
    val version: String,
)

/**
 * Client side file system capabilities. Sandbox Agent Connector runs the agent inside a sandbox,
 * so the IDE never serves file reads/writes: both flags stay `false`.
 */
@Serializable
data class FileSystemCapability(
    @Serializable(with = FlexibleBooleanSerializer::class) val readTextFile: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val writeTextFile: Boolean = false,
)

@Serializable
data class ClientCapabilities(
    val fs: FileSystemCapability = FileSystemCapability(),
    @Serializable(with = FlexibleBooleanSerializer::class) val terminal: Boolean = false,
)

@Serializable
data class InitializeParams(
    val protocolVersion: Int = ACP_PROTOCOL_VERSION,
    val clientInfo: ClientInfo = ClientInfo(CLIENT_NAME, CLIENT_VERSION),
    val clientCapabilities: ClientCapabilities = ClientCapabilities(),
) {
    companion object {
        const val CLIENT_NAME = "SandboxAgentConnector"
        const val CLIENT_VERSION = "1.0.0"
    }
}

@Serializable
data class AgentInfo(
    val name: String? = null,
    val version: String? = null,
)

@Serializable
data class PromptCapabilities(
    @Serializable(with = FlexibleBooleanSerializer::class) val image: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val audio: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val embeddedContext: Boolean = false,
)

@Serializable
data class SessionCapabilities(
    @Serializable(with = FlexibleBooleanSerializer::class) val additionalDirectories: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val close: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val list: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val resume: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val delete: Boolean = false,
)

@Serializable
data class McpCapabilities(
    @Serializable(with = FlexibleBooleanSerializer::class) val http: Boolean = false,
    @Serializable(with = FlexibleBooleanSerializer::class) val sse: Boolean = false,
)

@Serializable
data class AgentCapabilities(
    @Serializable(with = FlexibleBooleanSerializer::class) val loadSession: Boolean = false,
    val promptCapabilities: PromptCapabilities? = null,
    val sessionCapabilities: SessionCapabilities? = null,
    val mcpCapabilities: McpCapabilities? = null,
)

@Serializable
data class AuthMethod(
    val id: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class InitializeResult(
    val protocolVersion: Int = ACP_PROTOCOL_VERSION,
    val agentInfo: AgentInfo? = null,
    val agentCapabilities: AgentCapabilities = AgentCapabilities(),
    val authMethods: List<AuthMethod> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// Content blocks
// ---------------------------------------------------------------------------------------------

/**
 * Prompt / message content element. Serialized with the `type` discriminator.
 *
 * Decode through [ContentBlockSerializer] (or [AcpCodec.decodeContentBlock]) so unknown block
 * types coming from a newer agent degrade to [UnknownContentBlock] instead of failing the message.
 */
@Serializable
sealed interface ContentBlock

@Serializable
@SerialName("text")
data class TextContentBlock(
    val text: String,
) : ContentBlock

@Serializable
@SerialName("image")
data class ImageContentBlock(
    val data: String,
    val mimeType: String,
    val uri: String? = null,
) : ContentBlock

@Serializable
@SerialName("audio")
data class AudioContentBlock(
    val data: String,
    val mimeType: String,
) : ContentBlock

@Serializable
@SerialName("resource_link")
data class ResourceLinkContentBlock(
    val uri: String,
    val name: String? = null,
    val title: String? = null,
    val mimeType: String? = null,
    val text: String? = null,
) : ContentBlock

/** Text contents of an embedded resource (`resource` content block). */
@Serializable
data class EmbeddedResource(
    val uri: String,
    val text: String,
    val mimeType: String? = null,
)

@Serializable
@SerialName("resource")
data class EmbeddedResourceContentBlock(
    val resource: EmbeddedResource,
) : ContentBlock

/** Fallback for content block types this plugin version does not know yet. */
@Serializable
@SerialName("_unknown")
data class UnknownContentBlock(
    val rawType: String? = null,
    val raw: JsonElement? = null,
) : ContentBlock

/** Best effort plain text of a content block, used by the transcript. */
val ContentBlock.plainText: String
    get() = when (this) {
        is TextContentBlock -> text
        is ResourceLinkContentBlock -> text ?: title ?: name ?: uri
        is EmbeddedResourceContentBlock -> resource.text
        is ImageContentBlock -> "[image $mimeType]"
        is AudioContentBlock -> "[audio $mimeType]"
        is UnknownContentBlock -> raw?.toString().orEmpty()
    }

/**
 * `<context ref="file://...">…</context>` wrapper an agent replays an attached file in. The body
 * is captured so a replayed selection can keep the snippet it was sent with (see
 * [promptAttachments]); an extra group changes nothing for the callers that only strip the markup.
 */
private val EMBEDDED_CONTEXT = Regex("""<context\b[^>]*>([\s\S]*?)</context\s*>""")

/** Markdown mention (`[@sample.json](file:///…)`) an agent replays an attachment as. */
private val ATTACHMENT_MENTION = Regex("""\[@[^]]*]\(\S*?file:[^)]*\)""")

/**
 * Text of a replayed `user_message_chunk` as it belongs in the transcript, or `null` when the
 * chunk carries nothing but a prompt attachment.
 *
 * A prompt is sent as text plus one embedded resource per attached file (see [AcpContextItem]),
 * and `session/load` replays each of those as its own chunk: the file mention and the whole file
 * body would each become a separate "You" block, which is neither what the user typed nor useful
 * (the file has likely changed or is gone by then). Only the typed text is kept, which is exactly
 * what the live prompt shows.
 */
val ContentBlock.userPromptText: String?
    // Attachments are the only reason a prompt carries a non text block, so they are dropped as a
    // whole instead of being reduced to a placeholder.
    get() = (this as? TextContentBlock)?.text?.let { promptTextWithoutAttachments(it) }

/** Strips the replayed attachment markup out of [text]; `null` when nothing else is left. */
fun promptTextWithoutAttachments(text: String): String? = text
    .replace(EMBEDDED_CONTEXT, "")
    .replace(ATTACHMENT_MENTION, "")
    .trim()
    .ifEmpty { null }

/** `file://` URI of an attachment, either a content block's own or one found in replayed markup. */
private val ATTACHMENT_URI = Regex("""file:/[^\s"'()<>\]]*""")

/** Line range an attached selection carries in the fragment of its URI (`#L5-5`, `#L5-L9`). */
private val URI_LINE_RANGE = Regex("""^L(\d+)(?:-L?(\d+))?$""")

/**
 * Attachments a `user_message_chunk` carries, so a replayed prompt can show the same chips as the
 * live one (see [PromptAttachment]): the file body itself is dropped by [userPromptText], but
 * which file — and which lines of it — the user attached is part of what they asked and is kept.
 *
 * An attachment reaches the transcript as a whole chunk (an embedded resource or a resource link)
 * or inlined into the prompt text as the markup [promptTextWithoutAttachments] strips, depending
 * on the agent, so both are read here.
 */
val ContentBlock.promptAttachments: List<PromptAttachment>
    get() = when (this) {
        is EmbeddedResourceContentBlock -> listOfNotNull(promptAttachmentOf(resource.uri, resource.text))
        is ResourceLinkContentBlock -> listOfNotNull(promptAttachmentOf(uri))
        is TextContentBlock -> attachmentsInMarkup(text)
        else -> emptyList()
    }

/**
 * The embedded blocks come first, so when the same attachment is replayed both as a `<context>`
 * body and as a bare mention, the chip that kept the snippet is the one that survives.
 */
private fun attachmentsInMarkup(text: String): List<PromptAttachment> {
    val embedded = EMBEDDED_CONTEXT.findAll(text).mapNotNull { match ->
        attachmentUri(match.value)?.let { promptAttachmentOf(it, match.groupValues.getOrNull(1)) }
    }
    val mentioned = ATTACHMENT_MENTION.findAll(text).mapNotNull { match ->
        attachmentUri(match.value)?.let { promptAttachmentOf(it) }
    }
    return (embedded + mentioned).distinctBy { it.key }.toList()
}

private fun attachmentUri(markup: String): String? = ATTACHMENT_URI.find(markup)?.value

/**
 * `file:///path/sample.json#L5-5` → its chip; `null` for anything that is not a file URI.
 * [content] is only kept for a selection, which is the one attachment the file itself cannot
 * stand in for (see [PromptAttachment]).
 */
fun promptAttachmentOf(uri: String, content: String? = null): PromptAttachment? {
    if (!uri.startsWith("file:")) return null
    val located = uri.removePrefix("file://").removePrefix("file:")
    val fragmentAt = located.indexOf('#')
    val path = decodePath(if (fragmentAt >= 0) located.take(fragmentAt) else located).ifEmpty { return null }
    val range = URI_LINE_RANGE.find(if (fragmentAt >= 0) located.substring(fragmentAt + 1) else "")
    val startLine = range?.groupValues?.get(1)?.toIntOrNull()
    val endLine = range?.groupValues?.get(2)?.toIntOrNull() ?: startLine
    return PromptAttachment(
        path = path,
        startLine = startLine,
        endLine = endLine,
        content = content?.trim()?.takeIf { startLine != null && it.isNotEmpty() },
    )
}

/** Percent decoding is only attempted when there is something to decode, so a `+` in a path survives. */
private fun decodePath(path: String): String =
    if ('%' !in path) path else runCatching { URLDecoder.decode(path, StandardCharsets.UTF_8) }.getOrDefault(path)

// ---------------------------------------------------------------------------------------------
// Tool calls
// ---------------------------------------------------------------------------------------------

/** `pending` → `in_progress` → `completed` / `failed`. */
object ToolCallStatus {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

/** ACP `ToolKind` values; drives the icon/label and the auto-allow mapping. */
object ToolKind {
    const val READ = "read"
    const val EDIT = "edit"
    const val DELETE = "delete"
    const val MOVE = "move"
    const val SEARCH = "search"
    const val EXECUTE = "execute"
    const val THINK = "think"
    const val FETCH = "fetch"
    const val SWITCH_MODE = "switch_mode"
    const val OTHER = "other"
}

@Serializable
data class ToolCallLocation(
    val path: String,
    val line: Int? = null,
)

/** Content produced by a tool call: plain content, a file diff or a terminal handle. */
@Serializable
sealed interface ToolCallContent

@Serializable
@SerialName("content")
data class ToolCallContentBlock(
    @Serializable(with = ContentBlockSerializer::class) val content: ContentBlock,
) : ToolCallContent

@Serializable
@SerialName("diff")
data class ToolCallDiff(
    val path: String,
    val oldText: String? = null,
    val newText: String = "",
) : ToolCallContent

@Serializable
@SerialName("terminal")
data class ToolCallTerminal(
    val terminalId: String,
) : ToolCallContent

@Serializable
@SerialName("_unknown")
data class UnknownToolCallContent(
    val rawType: String? = null,
    val raw: JsonElement? = null,
) : ToolCallContent

/**
 * Snapshot of a tool call as reported by `tool_call` / `tool_call_update` and by
 * `session/request_permission`.
 */
@Serializable
data class ToolCall(
    val toolCallId: String,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val content: List<@Serializable(with = ToolCallContentSerializer::class) ToolCallContent> = emptyList(),
    val locations: List<ToolCallLocation> = emptyList(),
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
) {
    val diffs: List<ToolCallDiff> get() = content.filterIsInstance<ToolCallDiff>()

    /** Merges an incremental `tool_call_update` into this snapshot, keeping known values. */
    fun mergeWith(update: ToolCall): ToolCall = ToolCall(
        toolCallId = toolCallId,
        title = update.title ?: title,
        kind = update.kind ?: kind,
        status = update.status ?: status,
        content = update.content.ifEmpty { content },
        locations = update.locations.ifEmpty { locations },
        rawInput = update.rawInput ?: rawInput,
        rawOutput = update.rawOutput ?: rawOutput,
    )
}

// ---------------------------------------------------------------------------------------------
// session/new, session/load
// ---------------------------------------------------------------------------------------------

/**
 * MCP server declaration. Sandbox Agent Connector always sends an empty list: MCP is configured
 * by the agent inside the sandbox.
 */
@Serializable
data class McpServer(
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: List<McpEnvVariable> = emptyList(),
)

@Serializable
data class McpEnvVariable(
    val name: String,
    val value: String,
)

@Serializable
data class SessionNewParams(
    val cwd: String,
    val mcpServers: List<McpServer> = emptyList(),
)

@Serializable
data class SessionLoadParams(
    val sessionId: String,
    val cwd: String,
    val mcpServers: List<McpServer> = emptyList(),
)

@Serializable
data class ModelInfo(
    val modelId: String,
    val name: String? = null,
    val description: String? = null,
) {
    val presentableName: String get() = name ?: modelId
}

@Serializable
data class SessionModelState(
    val currentModelId: String? = null,
    val availableModels: List<ModelInfo> = emptyList(),
)

@Serializable
data class SessionMode(
    val id: String,
    val name: String? = null,
    val description: String? = null,
) {
    val presentableName: String get() = name ?: id
}

@Serializable
data class SessionModeState(
    val currentModeId: String? = null,
    val availableModes: List<SessionMode> = emptyList(),
)

/**
 * A single choice of a select style [SessionConfigOption]. Agents may group choices, in which
 * case the entry carries nested [options] instead of a [value].
 */
@Serializable
data class SessionConfigChoice(
    val value: String? = null,
    val name: String? = null,
    val description: String? = null,
    val options: List<SessionConfigChoice> = emptyList(),
) {
    val presentableName: String get() = name ?: value.orEmpty()

    /** Flattens the (optionally grouped) choices into the selectable leaves. */
    fun leaves(): List<SessionConfigChoice> =
        if (options.isEmpty()) listOfNotNull(takeIf { value != null }) else options.flatMap { it.leaves() }
}

/**
 * Generic session configuration selector (`configOptions` of `session/new`). Newer agents report
 * the model and the permission mode this way instead of the dedicated `models` / `modes` state.
 */
@Serializable
data class SessionConfigOption(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val type: String? = null,
    val currentValue: String? = null,
    val options: List<SessionConfigChoice> = emptyList(),
) {
    val presentableName: String get() = name ?: id

    val choices: List<SessionConfigChoice> get() = options.flatMap { it.leaves() }

    /**
     * `category` is advisory, so the well known ids are accepted as a fallback. Several spellings
     * are accepted for one selector because the category and the id disagree on some of them:
     * the reasoning budget is reported as `category = thought_level` with `id = effort`.
     */
    fun isCategory(vararg expected: String): Boolean =
        category in expected || (category == null && id in expected)
}

object SessionConfigCategory {
    const val MODE = "mode"
    const val MODEL = "model"

    /** Reasoning budget of the agent; `id = effort` is the fallback spelling (see [SessionConfigOption.isCategory]). */
    const val THOUGHT_LEVEL = "thought_level"
    const val EFFORT = "effort"
}

@Serializable
data class SessionNewResult(
    val sessionId: String,
    val models: SessionModelState? = null,
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
)

/** `session/load` returns the same optional state, but the id is already known. */
@Serializable
data class SessionLoadResult(
    val sessionId: String? = null,
    val models: SessionModelState? = null,
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// session/list, session/resume, session/delete
// ---------------------------------------------------------------------------------------------

/**
 * One entry of the `session/list` response. Only [sessionId] is guaranteed: agents may omit the
 * title, and [updatedAt] is missing entirely on agents that do not track it (see
 * [FlexibleEpochMillisSerializer] for the accepted shapes).
 */
@Serializable
data class SessionListEntry(
    val sessionId: String,
    val title: String? = null,
    @Serializable(with = FlexibleEpochMillisSerializer::class) val updatedAt: Long? = null,
    val cwd: String? = null,
    val status: String? = null,
) {
    /** Last update reported by the agent, or `null` when it does not track one. */
    val lastUpdatedMillis: Long? get() = updatedAt?.takeIf { it > FlexibleEpochMillisSerializer.UNKNOWN }
}

/** `cwd` scopes the listing to the sandbox workspace; agents that ignore it list everything. */
@Serializable
data class SessionListParams(
    val cwd: String? = null,
    val cursor: String? = null,
)

@Serializable
data class SessionListResult(
    val sessions: List<SessionListEntry> = emptyList(),
    val nextCursor: String? = null,
)

/**
 * `session/resume` reconnects the agent to an existing session **without** replaying its history,
 * which is what makes an instant local snapshot restore possible.
 */
@Serializable
data class SessionResumeParams(
    val sessionId: String,
    val cwd: String,
    val mcpServers: List<McpServer> = emptyList(),
)

@Serializable
data class SessionResumeResult(
    val sessionId: String? = null,
    val models: SessionModelState? = null,
    val modes: SessionModeState? = null,
    val configOptions: List<SessionConfigOption> = emptyList(),
)

@Serializable
data class SessionDeleteParams(
    val sessionId: String,
)

/** `session/delete` carries no payload; the empty result only tells the deletion succeeded. */
@Serializable
class SessionDeleteResult

@Serializable
data class SessionSetModeParams(
    val sessionId: String,
    val modeId: String,
)

@Serializable
data class SessionSetModelParams(
    val sessionId: String,
    val modelId: String,
)

@Serializable
data class SessionSetConfigOptionParams(
    val sessionId: String,
    val configId: String,
    val value: String,
)

/**
 * `session/set_mode` answers with the mode state that actually took effect; agents that report
 * nothing at all answer with an empty result.
 */
@Serializable
data class SessionSetModeResult(
    val modes: SessionModeState? = null,
)

/** `session/set_model` answers with the model state that actually took effect, if with anything. */
@Serializable
data class SessionSetModelResult(
    val models: SessionModelState? = null,
)

/**
 * `session/set_config_option` answers with the **full** selector state after the change: the
 * agent may reject or coerce the requested value (e.g. `auto` → `acceptEdits`), so this — not the
 * value that was sent — is the state the UI must show.
 */
@Serializable
data class SessionSetConfigOptionResult(
    val configOptions: List<SessionConfigOption> = emptyList(),
)

// ---------------------------------------------------------------------------------------------
// session/prompt, session/cancel
// ---------------------------------------------------------------------------------------------

@Serializable
data class SessionPromptParams(
    val sessionId: String,
    val prompt: List<ContentBlock>,
)

/** Reason the agent ended the turn. */
object StopReason {
    const val END_TURN = "end_turn"
    const val MAX_TOKENS = "max_tokens"
    const val MAX_TURN_REQUESTS = "max_turn_requests"
    const val REFUSAL = "refusal"
    const val CANCELLED = "cancelled"

    fun presentableText(stopReason: String?): String = when (stopReason) {
        END_TURN -> "Turn finished"
        MAX_TOKENS -> "Stopped: token limit reached"
        MAX_TURN_REQUESTS -> "Stopped: request limit reached"
        REFUSAL -> "Stopped: the model refused to continue"
        CANCELLED -> "Cancelled"
        null -> "Turn finished"
        else -> "Stopped: $stopReason"
    }
}

@Serializable
data class SessionPromptResult(
    val stopReason: String? = null,
)

@Serializable
data class SessionCancelParams(
    val sessionId: String,
)

// ---------------------------------------------------------------------------------------------
// session/update
// ---------------------------------------------------------------------------------------------

@Serializable
data class SessionNotification(
    val sessionId: String? = null,
    @Serializable(with = SessionUpdateSerializer::class) val update: SessionUpdate,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("sessionUpdate")
sealed interface SessionUpdate

@Serializable
@SerialName("user_message_chunk")
data class UserMessageChunkUpdate(
    @Serializable(with = ContentBlockSerializer::class) val content: ContentBlock,
) : SessionUpdate

@Serializable
@SerialName("agent_message_chunk")
data class AgentMessageChunkUpdate(
    @Serializable(with = ContentBlockSerializer::class) val content: ContentBlock,
    /** Id of the message the chunk belongs to; optional in ACP, so an agent may leave it out. */
    val messageId: String? = null,
) : SessionUpdate

@Serializable
@SerialName("agent_thought_chunk")
data class ThoughtChunkUpdate(
    @Serializable(with = ContentBlockSerializer::class) val content: ContentBlock,
) : SessionUpdate

/** `tool_call`: a tool call has been started. */
@Serializable
@SerialName("tool_call")
data class ToolCallUpdateEvent(
    val toolCallId: String,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val content: List<@Serializable(with = ToolCallContentSerializer::class) ToolCallContent> = emptyList(),
    val locations: List<ToolCallLocation> = emptyList(),
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
) : SessionUpdate {
    val toolCall: ToolCall
        get() = ToolCall(toolCallId, title, kind, status ?: ToolCallStatus.PENDING, content, locations, rawInput, rawOutput)
}

/** `tool_call_update`: progress / output of a running tool call. */
@Serializable
@SerialName("tool_call_update")
data class ToolCallProgressUpdate(
    val toolCallId: String,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val content: List<@Serializable(with = ToolCallContentSerializer::class) ToolCallContent> = emptyList(),
    val locations: List<ToolCallLocation> = emptyList(),
    val rawInput: JsonElement? = null,
    val rawOutput: JsonElement? = null,
) : SessionUpdate {
    val toolCall: ToolCall
        get() = ToolCall(toolCallId, title, kind, status, content, locations, rawInput, rawOutput)
}

@Serializable
data class PlanEntry(
    val content: String,
    val priority: String? = null,
    val status: String? = null,
)

@Serializable
@SerialName("plan")
data class PlanUpdate(
    val entries: List<PlanEntry> = emptyList(),
) : SessionUpdate

/** `AvailableCommandInput`: the unstructured text a slash command takes after its name. */
@Serializable
data class AvailableCommandInput(
    val hint: String? = null,
)

@Serializable
data class AvailableCommand(
    val name: String,
    val description: String? = null,
    val input: AvailableCommandInput? = null,
)

@Serializable
@SerialName("available_commands_update")
data class AvailableCommandsUpdate(
    val availableCommands: List<AvailableCommand> = emptyList(),
) : SessionUpdate

/**
 * `session_info_update`: the agent renamed the session (usually right after the first prompt,
 * when it derives a title from the conversation).
 */
@Serializable
@SerialName("session_info_update")
data class SessionInfoUpdate(
    val title: String? = null,
    val sessionId: String? = null,
) : SessionUpdate

/**
 * `current_mode_update`: the agent switched the session mode (usually right after a
 * `switch_mode` tool call was allowed).
 *
 * The schema names the field `currentModeId`, the session modes documentation spells the very
 * same notification with a `modeId`, and agents follow one or the other: both are accepted and
 * [modeId] is the one the UI reads.
 */
@Serializable
@SerialName("current_mode_update")
data class CurrentModeUpdate(
    val currentModeId: String? = null,
    @SerialName("modeId") val reportedModeId: String? = null,
) : SessionUpdate {
    val modeId: String? get() = currentModeId?.takeIf { it.isNotBlank() } ?: reportedModeId?.takeIf { it.isNotBlank() }
}

@Serializable
@SerialName("current_model_update")
data class CurrentModelUpdate(
    val currentModelId: String,
) : SessionUpdate

/** Full refresh of the session configuration selectors (model, mode, ...). */
@Serializable
@SerialName("config_option_update")
data class ConfigOptionUpdate(
    val configOptions: List<SessionConfigOption> = emptyList(),
) : SessionUpdate

/** Cost amount and currency reported in a [UsageUpdate]. */
@Serializable
data class UsageCost(
    val amount: Double? = null,
    val currency: String? = null,
)

/** Token usage report sent by the agent as a `usage_update` session notification. */
@Serializable
@SerialName("usage_update")
data class UsageUpdate(
    val used: Long? = null,
    val size: Long? = null,
    val cost: UsageCost? = null,
    val raw: JsonElement? = null,
) : SessionUpdate

/** Fallback for `sessionUpdate` variants this plugin version does not know yet. */
@Serializable
@SerialName("_unknown")
data class UnknownSessionUpdate(
    val rawType: String? = null,
    val raw: JsonElement? = null,
) : SessionUpdate

// ---------------------------------------------------------------------------------------------
// session/request_permission
// ---------------------------------------------------------------------------------------------

/** ACP `PermissionOptionKind`. */
object PermissionOptionKind {
    const val ALLOW_ONCE = "allow_once"
    const val ALLOW_ALWAYS = "allow_always"
    const val REJECT_ONCE = "reject_once"
    const val REJECT_ALWAYS = "reject_always"
}

@Serializable
data class PermissionOption(
    val optionId: String,
    val name: String? = null,
    val title: String? = null,
    val kind: String? = null,
    @Serializable(with = FlexibleBooleanSerializer::class) val isDefault: Boolean = false,
) {
    val presentableName: String get() = name ?: title ?: optionId

    /** `true` for options that let the tool call proceed. */
    val isAllow: Boolean
        get() = when (kind) {
            PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.ALLOW_ALWAYS -> true
            PermissionOptionKind.REJECT_ONCE, PermissionOptionKind.REJECT_ALWAYS -> false
            else -> optionId.contains("allow", ignoreCase = true) || optionId.contains("accept", ignoreCase = true)
        }
}

@Serializable
data class RequestPermissionParams(
    val sessionId: String? = null,
    val toolCall: ToolCall,
    val options: List<PermissionOption> = emptyList(),
)

/**
 * Outcome of a permission request.
 *
 * `outcome` follows the ACP v1 schema (`selected` / `cancelled`); `decision` mirrors the same
 * decision in the `allow` / `deny` / `cancel` wording used by agents that follow the
 * design document, so both flavours are accepted by the agent side.
 */
@Serializable
data class PermissionOutcome(
    val outcome: String,
    val optionId: String? = null,
    val decision: String? = null,
) {
    val isSelected: Boolean get() = outcome == SELECTED

    companion object {
        const val SELECTED = "selected"
        const val CANCELLED = "cancelled"
        const val DECISION_ALLOW = "allow"
        const val DECISION_DENY = "deny"
        const val DECISION_CANCEL = "cancel"

        fun selected(option: PermissionOption): PermissionOutcome = PermissionOutcome(
            outcome = SELECTED,
            optionId = option.optionId,
            decision = if (option.isAllow) DECISION_ALLOW else DECISION_DENY,
        )

        fun cancelled(): PermissionOutcome = PermissionOutcome(outcome = CANCELLED, decision = DECISION_CANCEL)
    }
}

@Serializable
data class RequestPermissionResult(
    val outcome: PermissionOutcome,
)

// ---------------------------------------------------------------------------------------------
// Tolerant polymorphic codecs
// ---------------------------------------------------------------------------------------------

/**
 * Discriminator driven codecs that never fail on unknown variants.
 *
 * Encoding always goes through the generated sealed serializers, so the wire format stays
 * exactly the ACP one; decoding dispatches manually and falls back to the `_unknown` variant.
 */
object AcpCodec {

    fun decodeContentBlock(element: JsonElement): ContentBlock {
        val obj = element as? JsonObject ?: return UnknownContentBlock(raw = element)
        val type = obj.discriminator("type")
        val serializer = when (type) {
            "text" -> TextContentBlock.serializer()
            "image" -> ImageContentBlock.serializer()
            "audio" -> AudioContentBlock.serializer()
            "resource_link" -> ResourceLinkContentBlock.serializer()
            "resource" -> EmbeddedResourceContentBlock.serializer()
            else -> null
        }
        return decodeOrFallback(serializer, obj) { UnknownContentBlock(rawType = type, raw = obj) }
    }

    fun decodeToolCallContent(element: JsonElement): ToolCallContent {
        val obj = element as? JsonObject ?: return UnknownToolCallContent(raw = element)
        val type = obj.discriminator("type")
        val serializer = when (type) {
            "content" -> ToolCallContentBlock.serializer()
            "diff" -> ToolCallDiff.serializer()
            "terminal" -> ToolCallTerminal.serializer()
            else -> null
        }
        // A bare content block (`{"type":"text", ...}`) instead of the `content` wrapper: the
        // session modes examples of the spec write a `switch_mode` plan that way, and dumping it
        // as an unknown blob would show the raw JSON where the plan text belongs.
        if (serializer == null && type in CONTENT_BLOCK_TYPES) {
            return ToolCallContentBlock(decodeContentBlock(obj))
        }
        return decodeOrFallback(serializer, obj) { UnknownToolCallContent(rawType = type, raw = obj) }
    }

    private val CONTENT_BLOCK_TYPES = setOf("text", "image", "audio", "resource_link", "resource")

    fun decodeSessionUpdate(element: JsonElement): SessionUpdate {
        val obj = element as? JsonObject ?: return UnknownSessionUpdate(raw = element)
        val type = obj.discriminator("sessionUpdate")
        val serializer = when (type) {
            "user_message_chunk" -> UserMessageChunkUpdate.serializer()
            "agent_message_chunk" -> AgentMessageChunkUpdate.serializer()
            // `thought_chunk` is accepted as an alias of the spec name `agent_thought_chunk`.
            "agent_thought_chunk", "thought_chunk" -> ThoughtChunkUpdate.serializer()
            "tool_call" -> ToolCallUpdateEvent.serializer()
            "tool_call_update" -> ToolCallProgressUpdate.serializer()
            "plan" -> PlanUpdate.serializer()
            "available_commands_update" -> AvailableCommandsUpdate.serializer()
            // `session_info` is accepted as an alias of the spec name `session_info_update`.
            "session_info_update", "session_info" -> SessionInfoUpdate.serializer()
            "current_mode_update" -> CurrentModeUpdate.serializer()
            "current_model_update" -> CurrentModelUpdate.serializer()
            "config_option_update" -> ConfigOptionUpdate.serializer()
            "usage_update" -> UsageUpdate.serializer()
            else -> null
        }
        return decodeOrFallback(serializer, obj) { UnknownSessionUpdate(rawType = type, raw = obj) }
    }

    private fun JsonObject.discriminator(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun <T> decodeOrFallback(serializer: KSerializer<out T>?, obj: JsonObject, fallback: () -> T): T {
        if (serializer == null) return fallback()
        return runCatching { AcpJson.decodeFromJsonElement(serializer, obj) }.getOrElse { fallback() }
    }
}

/** Property level codec that keeps ACP encoding and tolerates unknown `type` values. */
object ContentBlockSerializer : KSerializer<ContentBlock> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.tsuty.sandboxagent.acp.ContentBlock")

    override fun deserialize(decoder: Decoder): ContentBlock =
        AcpCodec.decodeContentBlock((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: ContentBlock) {
        (encoder as JsonEncoder).encodeJsonElement(AcpJson.encodeToJsonElement(ContentBlock.serializer(), value))
    }
}

/** Property level codec that keeps ACP encoding and tolerates unknown `type` values. */
object ToolCallContentSerializer : KSerializer<ToolCallContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.tsuty.sandboxagent.acp.ToolCallContent")

    override fun deserialize(decoder: Decoder): ToolCallContent =
        AcpCodec.decodeToolCallContent((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: ToolCallContent) {
        (encoder as JsonEncoder).encodeJsonElement(AcpJson.encodeToJsonElement(ToolCallContent.serializer(), value))
    }
}

/** Property level codec that keeps ACP encoding and tolerates unknown `sessionUpdate` values. */
object SessionUpdateSerializer : KSerializer<SessionUpdate> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.tsuty.sandboxagent.acp.SessionUpdate")

    override fun deserialize(decoder: Decoder): SessionUpdate =
        AcpCodec.decodeSessionUpdate((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(encoder: Encoder, value: SessionUpdate) {
        (encoder as JsonEncoder).encodeJsonElement(AcpJson.encodeToJsonElement(SessionUpdate.serializer(), value))
    }
}

// ---------------------------------------------------------------------------------------------
// Prompt context (IDE side helper, not part of the ACP wire format)
// ---------------------------------------------------------------------------------------------

/**
 * What the user attached to a prompt, as a chip shows it: which file (or directory), and which
 * lines of it for a selection.
 *
 * This is display state kept for the whole life of the session (it is stored in the transcript
 * snapshot and redrawn in the `You` block of every restore), so a whole attached file is never
 * copied into it: a file chip navigates to the file itself, which the IDE has anyway. A selection
 * is the exception — the lines it pointed at have no meaning once the file moved on, so the
 * snippet as it was sent is kept in [content] and shown from there.
 */
@Serializable
data class PromptAttachment(
    val path: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val content: String? = null,
) {
    val presentableRange: String?
        get() = if (startLine != null && endLine != null) "$startLine-$endLine" else null

    /** `true` for an attached selection, as opposed to a whole file or a directory. */
    val isSelection: Boolean
        get() = presentableRange != null

    /**
     * What makes two chips the same attachment: the file and the lines of it. The snippet is left
     * out on purpose, so the same selection replayed with and without its body is one chip.
     */
    val key: String
        get() = presentableRange?.let { "$path:$it" } ?: path

    val presentableName: String
        get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    /** Chip label, matching the strip above the prompt editor: `sample.json`, `sample.json:5-5`. */
    val label: String
        get() = presentableRange?.let { "$presentableName:$it" } ?: presentableName
}

/**
 * A file or selection the user attached to the next prompt. Converted into ACP content blocks
 * by [toContentBlock]: the sandbox agent receives the snippet as an embedded resource so it
 * never has to read the file through the (disabled) client file system capability.
 */
@Serializable
data class AcpContextItem(
    val path: String,
    val content: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
) {
    val presentableRange: String?
        get() = if (startLine != null && endLine != null) "$startLine-$endLine" else null

    fun toContentBlock(): ContentBlock = EmbeddedResourceContentBlock(
        EmbeddedResource(uri = uri(), text = content),
    )

    /**
     * The chip of this attachment: the reference alone for a file, the snippet as well for a
     * selection (see [PromptAttachment]).
     */
    fun toAttachment(): PromptAttachment = PromptAttachment(
        path = path,
        startLine = startLine,
        endLine = endLine,
        content = content.takeIf { presentableRange != null && it.isNotEmpty() },
    )

    /**
     * Percent-encoded, so a replayed mention of a path with spaces, parentheses or non-ASCII
     * characters is still read back as one URI (see [promptAttachments]).
     */
    private fun uri(): String {
        val encoded = encodeUriPath(path)
        val base = if (path.startsWith("/")) "file://$encoded" else "file:///$encoded"
        return presentableRange?.let { "$base#L$it" } ?: base
    }
}

/** Characters a file URI path keeps as they are; everything else is percent-encoded as UTF-8. */
private const val URI_PATH_UNRESERVED = "-._~/:"

/**
 * Encodes [path] for a `file://` URI. `+` is encoded as well: [decodePath] uses [URLDecoder],
 * which would read a bare `+` as a space.
 */
fun encodeUriPath(path: String): String = buildString {
    path.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        val char = (byte.toInt() and 0xFF).toChar()
        if (char.isAsciiLetterOrDigit() || char in URI_PATH_UNRESERVED) {
            append(char)
        } else {
            append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/** `true` when the JSON-RPC id is present and not `null`. */
fun JsonElement?.asRpcId(): JsonRpcId? = (this as? JsonPrimitive)?.takeIf { it !== JsonNull }

/** Convenience accessor used when a raw JSON element is known to be an object. */
fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it is JsonObject }?.jsonObject
