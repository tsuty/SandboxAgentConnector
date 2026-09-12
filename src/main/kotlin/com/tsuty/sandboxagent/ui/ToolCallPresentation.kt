package com.tsuty.sandboxagent.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.ToolCallContentBlock
import com.tsuty.sandboxagent.acp.ToolCallDiff
import com.tsuty.sandboxagent.acp.ToolCallStatus
import com.tsuty.sandboxagent.acp.ToolCallTerminal
import com.tsuty.sandboxagent.acp.ToolKind
import com.tsuty.sandboxagent.acp.UnknownToolCallContent
import com.tsuty.sandboxagent.acp.plainText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a [ToolCall] shows in the transcript: its title, the text of its anchor, the `+n / -m`
 * summary of a change, the flat one line entries and the plan of a `switch_mode`.
 *
 * The rules live here and not in the transcript document, so both the tool call card and the
 * inline permission card of `TranscriptToolCall` say the same thing about a call — and so they
 * stay testable without a browser. Only [openFile] and [openFullDiff] touch the IDE, which is
 * what the document asks the host to do on a click.
 */
object ToolCallPresentation {

    fun title(toolCall: ToolCall): String =
        toolCall.title?.takeIf { it.isNotBlank() } ?: kindLabel(toolCall.kind)

    fun kindLabel(kind: String?): String = when (kind) {
        ToolKind.READ -> "Read file"
        ToolKind.EDIT -> "Edit file"
        ToolKind.DELETE -> "Delete file"
        ToolKind.MOVE -> "Move file"
        ToolKind.SEARCH -> "Search"
        ToolKind.EXECUTE -> "Execute command"
        ToolKind.THINK -> "Think"
        ToolKind.FETCH -> "Fetch"
        ToolKind.SWITCH_MODE -> "Switch mode"
        null, ToolKind.OTHER -> "Tool call"
        else -> kind
    }

    fun statusLabel(status: String?): String = when (status) {
        ToolCallStatus.PENDING -> "pending"
        ToolCallStatus.IN_PROGRESS -> "running"
        ToolCallStatus.COMPLETED -> "completed"
        ToolCallStatus.FAILED -> "failed"
        null -> ""
        else -> status
    }

    /** `+n / -m` over every `diff` content of the tool call, plus the touched file names. */
    fun summary(toolCall: ToolCall): String? {
        val diffs = toolCall.diffs
        if (diffs.isNotEmpty()) {
            val added = diffs.sumOf { simpleDiff(it).stats.added }
            val removed = diffs.sumOf { simpleDiff(it).stats.removed }
            val files = unnamedFiles(diffs.map { it.path }, toolCall)
            return listOfNotNull(files, "+$added / -$removed").joinToString("  ")
        }
        command(toolCall.rawInput)?.let { return it }
        return unnamedFiles(toolCall.locations.map { it.path }, toolCall)
    }

    fun simpleDiff(diff: ToolCallDiff): SimpleDiff =
        UnifiedDiffSupport.fromTexts(diff.path, diff.oldText, diff.newText)

    /**
     * Pulls the command out of the raw tool input for `execute` style tool calls.
     *
     * Only the keys that really carry a command are looked at: a `file_path` of an `edit` call is
     * not a command, and taking it for one used to replace the whole anchor of the card with a
     * bare absolute path — hiding both the title and the `+n / -m` summary of the change.
     */
    fun command(rawInput: JsonElement?): String? {
        val obj = rawInput as? JsonObject ?: return null
        return COMMAND_KEYS.firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
    }

    /**
     * File names of [paths] the title does not spell out already, or `null` when it does: the
     * summary sits right behind the title, so repeating the same name there is only noise.
     */
    private fun unnamedFiles(paths: List<String>, toolCall: ToolCall): String? {
        val title = title(toolCall)
        val names = paths.map { it.substringAfterLast('/') }.distinct().filterNot { title.contains(it) }
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * Text of the anchor folding the tool call away: the executed command when the agent reported
     * one, the title plus whatever summary is available otherwise.
     */
    fun anchor(toolCall: ToolCall): String {
        searchAnchor(toolCall)?.let { return it }
        command(toolCall.rawInput)?.takeIf { it.isNotBlank() }?.let { return it }
        val title = title(toolCall)
        val summary = summary(toolCall)?.takeIf { it.isNotBlank() } ?: return title
        return "$title: $summary"
    }

    /**
     * Everything the card can show as text in its details area.
     *
     * The `diff` contents are left out on purpose: they are opened in the platform diff viewer
     * from the flat `Edit` line instead of being dumped into the transcript.
     */
    fun details(toolCall: ToolCall): String = buildString {
        if (toolCall.kind == ToolKind.EXECUTE) {
            val output = executeOutput(toolCall)
            if (output.isNotBlank()) appendLine("output: $output")
            return@buildString
        }
        // A diff is shown next to this text, so the raw output is not needed as a fallback.
        var reported = toolCall.diffs.isNotEmpty()
        toolCall.content.forEach { content ->
            when (content) {
                is ToolCallContentBlock -> content.content.plainText.takeIf { it.isNotBlank() }?.let {
                    appendLine(it)
                    reported = true
                }
                is ToolCallTerminal -> appendLine("[terminal ${content.terminalId}]")
                is UnknownToolCallContent -> appendLine(content.raw?.toString().orEmpty())
                is ToolCallDiff -> Unit
            }
        }
        // The command already is the anchor of the card, so the raw input is only interesting when
        // no command could be pulled out of it. An edit reports its `old_string` / `new_string`
        // there, which the diff rows below already show in a readable form.
        if (command(toolCall.rawInput) == null && toolCall.diffs.isEmpty()) {
            toolCall.rawInput?.let { appendLine("input: $it") }
        }
        // `rawOutput` normally repeats what the content blocks already carry (agents send the very
        // same text there), so it is a fallback for the calls that report nothing as content.
        if (!reported) toolCall.rawOutput?.let { appendLine("output: $it") }
    }.trim().ifEmpty {
        // A tool call that only reported a diff has its whole story in the diff rows below.
        if (toolCall.diffs.isEmpty()) "No details reported by the agent" else ""
    }

    /** Body of an expanded tool call: the full command, then everything the agent reported back. */
    fun expandedBody(toolCall: ToolCall): String {
        if (toolCall.kind == ToolKind.EXECUTE) return executeMarkdown(toolCall)
        val details = details(toolCall)
        val command = command(toolCall.rawInput)?.takeIf { it.isNotBlank() } ?: return details
        return (command.trim() + "\n\n" + details).trim()
    }

    /**
     * Markdown presentation of an `execute` tool call: the command wrapped in a `console` code
     * fence and the output wrapped in a `text` code fence.
     */
    fun executeMarkdown(toolCall: ToolCall): String {
        val input = executeInput(toolCall).trim()
        val output = executeOutput(toolCall).trim()
        return buildString {
            appendLine("```console")
            appendLine(input)
            append("```")
            // A permission request asks before the command has run, so there is nothing to show
            // yet, and many commands report no output at all even once they have: an empty
            // section is only noise.
            if (output.isNotEmpty()) {
                appendLine()
                appendLine("Output:")
                appendLine("```text")
                appendLine(output)
                append("```")
            }
        }
    }

    fun executeInput(toolCall: ToolCall): String {
        command(toolCall.rawInput)?.takeIf { it.isNotBlank() }?.let { return it }
        (toolCall.rawInput as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = toolCall.rawInput
        if (raw is JsonObject) {
            COMMAND_KEYS.firstNotNullOfOrNull { key ->
                (raw[key] as? JsonPrimitive)?.contentOrNull
            }?.takeIf { it.isNotBlank() }?.let { return it }
        }
        raw?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return toolCall.title.orEmpty()
    }

    fun executeOutput(toolCall: ToolCall): String {
        val raw = toolCall.rawOutput
        if (raw != null) {
            (raw as? JsonPrimitive)?.contentOrNull?.let { return it }
            if (raw is JsonObject) {
                OUTPUT_KEYS.firstNotNullOfOrNull { key ->
                    (raw[key] as? JsonPrimitive)?.contentOrNull
                }?.let { return it }
                return raw.toString()
            }
            return raw.toString()
        }
        val blocks = toolCall.content
            .filterIsInstance<ToolCallContentBlock>()
            .map { it.content.plainText }
            .filter { it.isNotBlank() }
        if (blocks.isNotEmpty()) return blocks.joinToString("\n")
        return ""
    }

    /**
     * `Search {pattern} · {n} results` for a `search` tool call, `null` for every other kind and
     * for a search that did not report what it looked for.
     *
     * The pattern alone is what [command] would return, and the raw hit list then lands in the
     * folded away details as an unstructured dump: the number of hits is the one thing worth
     * reading right away, so it is spelled out next to the pattern.
     */
    fun searchAnchor(toolCall: ToolCall): String? {
        if (toolCall.kind != ToolKind.SEARCH) return null
        val obj = toolCall.rawInput as? JsonObject ?: return null
        val pattern = SEARCH_KEYS
            .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val count = searchResultCount(toolCall) ?: return "$SEARCH_PREFIX $pattern"
        return "$SEARCH_PREFIX $pattern · ${resultsLabel(count)}"
    }

    /**
     * Number of hits a `search` reported: the locations when it named them, the count it spelled
     * out itself (`Found 12 files`) otherwise, and the number of reported lines as a last resort.
     * `null` when the search reported nothing at all — it is likely still running.
     */
    fun searchResultCount(toolCall: ToolCall): Int? {
        if (toolCall.locations.isNotEmpty()) return toolCall.locations.size
        val output = outputText(toolCall)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (NO_RESULTS.containsMatchIn(output)) return 0
        REPORTED_COUNT.find(output)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return output.lineSequence().count { it.isNotBlank() }
    }

    private fun resultsLabel(count: Int): String = when (count) {
        0 -> "no results"
        1 -> "1 result"
        else -> "$count results"
    }

    /** Everything the agent reported back as text, content blocks first, `rawOutput` after. */
    private fun outputText(toolCall: ToolCall): String? {
        val blocks = toolCall.content
            .filterIsInstance<ToolCallContentBlock>()
            .map { it.content.plainText }
            .filter { it.isNotBlank() }
        if (blocks.isNotEmpty()) return blocks.joinToString("\n")
        val rawOutput = toolCall.rawOutput ?: return null
        return (rawOutput as? JsonPrimitive)?.contentOrNull ?: rawOutput.toString()
    }

    /**
     * A `switch_mode` tool call: the mode the agent wants to move the session to and the plan it
     * wrote to justify the move.
     *
     * Every other tool call is something the user may safely miss — a file was read, a command
     * ran. A mode switch is not: it changes what the agent is allowed to do for the rest of the
     * session, and the plan behind it is the one text worth reading before it happens. The card
     * therefore shows it in full instead of folding it away.
     */
    data class SwitchModeEntry(
        val headline: String,
        val modeId: String?,
        val plan: String,
    )

    /** Presentation of a `switch_mode`, `null` for every other kind. */
    fun switchModeEntry(toolCall: ToolCall): SwitchModeEntry? {
        if (toolCall.kind != ToolKind.SWITCH_MODE) return null
        val modeId = (toolCall.rawInput as? JsonObject)?.let { path(it, MODE_KEYS) }
        val headline = toolCall.title?.takeIf { it.isNotBlank() }
            ?: modeId?.let { "Switch to $it" }
            ?: kindLabel(ToolKind.SWITCH_MODE)
        return SwitchModeEntry(headline = headline, modeId = modeId, plan = planText(toolCall))
    }

    /**
     * Markdown the agent attached to a `switch_mode`: its content blocks, which carry the plan.
     *
     * The raw input and the raw output are left out on purpose — they are the JSON plumbing of
     * the call, and a plan that reads as raw JSON is worse than no plan at all.
     */
    private fun planText(toolCall: ToolCall): String = toolCall.content
        .filterIsInstance<ToolCallContentBlock>()
        .map { it.content.plainText }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
        .trim()

    /**
     * One clickable piece of a flat tool call line: a file the IDE can open, a URL for the
     * browser or a [diff] for the platform diff viewer. A piece with none of them is shown as
     * quiet text.
     */
    data class FlatTarget(
        val text: String,
        val path: String? = null,
        val line: Int? = null,
        val url: String? = null,
        val diff: ToolCallDiff? = null,
    )

    /**
     * A tool call whose whole story fits on a single line: `Read {file}`, `Delete {file}`,
     * `Move {from} → {to}`, `Fetch {host/path}`.
     *
     * These calls produce nothing worth unfolding — the file is in the IDE, the page is in the
     * browser — so the card drops the foldable anchor and shows the [prefix] with the [targets]
     * as links. Only an [expandable] entry keeps the caret for what the agent reported back.
     */
    data class FlatEntry(
        val prefix: String,
        val targets: List<FlatTarget>,
        val separator: String = "→",
        val expandable: Boolean = false,
    )

    /** Flat presentation of [toolCall], `null` when it is rendered as a foldable anchor. */
    fun flatEntry(toolCall: ToolCall): FlatEntry? = when (toolCall.kind) {
        ToolKind.READ -> fileEntry(toolCall, "Read")
        ToolKind.EDIT -> editEntry(toolCall)
        ToolKind.DELETE -> fileEntry(toolCall, "Delete")
        ToolKind.MOVE -> moveEntry(toolCall)
        ToolKind.FETCH -> fetchEntry(toolCall)
        else -> null
    }

    /**
     * `Edit {file}  +n / -m` with the whole piece opening the platform diff viewer.
     *
     * The diff itself is never unfolded into the transcript any more: the editor shows it far
     * better than a few tinted rows in a tool window, so the entry is a single line pointing at
     * it (the same view `Open Full Diff` used to open).
     */
    fun editEntry(toolCall: ToolCall): FlatEntry? {
        val diff = toolCall.diffs.firstOrNull() ?: return fileEntry(toolCall, "Edit")
        val added = toolCall.diffs.sumOf { simpleDiff(it).stats.added }
        val removed = toolCall.diffs.sumOf { simpleDiff(it).stats.removed }
        val target = FlatTarget(
            "${fileName(diff.path)}  +$added / -$removed",
            path = diff.path,
            diff = diff,
        )
        return FlatEntry("Edit", listOf(target))
    }

    /**
     * The same `Edit {file}  +n / -m` piece for a call the kind of which is not an `edit` but
     * which still reported a change, `null` when it reported none.
     *
     * A permission request uses it: whatever the agent calls the tool, the change behind it is
     * opened in the platform diff viewer and never unfolded on the card.
     */
    fun diffEntry(toolCall: ToolCall): FlatEntry? =
        if (toolCall.diffs.isEmpty()) null else editEntry(toolCall)

    private fun fileEntry(toolCall: ToolCall, prefix: String): FlatEntry? {
        val path = firstPath(toolCall) ?: return null
        val target = FlatTarget(fileName(path), path = path, line = toolCall.locations.firstOrNull()?.line)
        return FlatEntry(prefix, listOf(target))
    }

    /**
     * `Move {from} → {to}`. The two paths come from the raw input when the agent named them and
     * from the reported locations otherwise; a move that only knows one path reads like a read.
     */
    private fun moveEntry(toolCall: ToolCall): FlatEntry? {
        val obj = toolCall.rawInput as? JsonObject
        val from = obj?.let { path(it, MOVE_FROM_KEYS) } ?: toolCall.locations.getOrNull(0)?.path
        val to = obj?.let { path(it, MOVE_TO_KEYS) } ?: toolCall.locations.getOrNull(1)?.path
        if (from == null || to == null) return fileEntry(toolCall, "Move")
        return FlatEntry(
            prefix = "Move",
            targets = listOf(
                FlatTarget(fileName(from), path = from),
                FlatTarget(fileName(to), path = to),
            ),
        )
    }

    /**
     * `Fetch {host/path}` with the URL opening in the browser. Unlike a file, the fetched page is
     * nowhere else in the IDE, so the entry keeps its caret for the text the agent got back.
     */
    private fun fetchEntry(toolCall: ToolCall): FlatEntry? {
        val obj = toolCall.rawInput as? JsonObject ?: return null
        val url = path(obj, URL_KEYS) ?: return null
        return FlatEntry(
            prefix = "Fetch",
            targets = listOf(FlatTarget(shortUrl(url), url = url)),
            expandable = true,
        )
    }

    /** The URL without its scheme: the host and the path are what tells the pages apart. */
    fun shortUrl(url: String): String = url
        .substringAfter("://")
        .trimEnd('/')
        .ifEmpty { url }

    /** Path the tool call points at: the first reported location, the raw input otherwise. */
    fun firstPath(toolCall: ToolCall): String? {
        toolCall.locations.firstOrNull()?.path?.takeIf { it.isNotBlank() }?.let { return it }
        val obj = toolCall.rawInput as? JsonObject ?: return null
        return path(obj, PATH_KEYS)
    }

    private fun path(obj: JsonObject, keys: List<String>): String? = keys
        .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotBlank() }

    fun fileName(path: String): String = path.substringAfterLast('/')

    /** Opens [path] in the editor, jumping to [line] (1 based) when the agent reported one. */
    fun openFile(project: Project, path: String, line: Int? = null) {
        val file = findFile(project, path) ?: return
        OpenFileDescriptor(project, file, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    /**
     * Paths are sandbox side, so the reported path itself may not exist locally: the trailing
     * part of it is then resolved against the project root (bind mounted workspace).
     */
    private fun findFile(project: Project, path: String): VirtualFile? {
        val fileSystem = LocalFileSystem.getInstance()
        fileSystem.findFileByPath(path)?.takeIf { !it.isDirectory }?.let { return it }
        val root = project.basePath?.let { fileSystem.findFileByPath(it) } ?: return null
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        return segments.indices.firstNotNullOfOrNull { from ->
            root.findFileByRelativePath(segments.drop(from).joinToString("/"))
        }
    }

    /**
     * Opens the platform diff viewer on [diff]. The file type is derived from the path so the
     * viewer highlights the two sides instead of showing them as plain text.
     *
     * The tab is named after the file alone: the full path made the tab so wide that the name of
     * the changed file — the one thing telling two diffs apart — was cut off. The path stays
     * reachable as the tooltip of the flat `Edit` line.
     */
    fun openFullDiff(project: Project, diff: ToolCallDiff) {
        val factory = DiffContentFactory.getInstance()
        val fileName = fileName(diff.path)
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val request = SimpleDiffRequest(
            fileName,
            factory.create(project, diff.oldText.orEmpty(), fileType),
            factory.create(project, diff.newText, fileType),
            "Current",
            "Proposed",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    private const val SEARCH_PREFIX = "Search"

    private val COMMAND_KEYS = listOf("command", "cmd", "script", "query", "pattern")

    private val OUTPUT_KEYS = listOf("output", "stdout", "result", "text", "content", "response", "data")

    private val PATH_KEYS = listOf("file_path", "path", "filePath", "notebook_path")

    private val SEARCH_KEYS = listOf("pattern", "query", "regex", "search")

    private val MOVE_FROM_KEYS = listOf("old_path", "oldPath", "source", "source_path", "from", "src")

    private val MOVE_TO_KEYS = listOf("new_path", "newPath", "destination", "target_path", "to", "dest")

    private val URL_KEYS = listOf("url", "uri", "href", "link")

    private val MODE_KEYS = listOf("mode", "mode_id", "modeId", "target_mode", "new_mode")

    /** `Found 12 files`, `found 3 matches`: the count a search spells out for itself. */
    private val REPORTED_COUNT = Regex("""(?i)found\s+(\d+)\s+(?:files?|matches?|results?|lines?)""")

    /** `No files found`, `No matches`: a search that found nothing still reports a line. */
    private val NO_RESULTS = Regex("""(?i)^no\s+(?:\w+\s+)?(?:found|match(?:es)?)""")
}
