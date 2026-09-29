package com.tsuty.sandboxagent.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets

/** One file of the local session directory whose text contains the query. */
data class LocalSessionSearchResult(
    val file: LocalSessionFile,
    val sessionId: String,
    val title: String,
    /** Text around the first match, on one line, cut with `…` where the file goes on. */
    val snippet: String,
)

/**
 * The part of a matching file shown under the results: the matching element, pretty printed, and
 * where the query occurs in [text] (start inclusive, end exclusive).
 */
data class LocalSessionPreview(val text: String, val matches: List<IntRange>)

/**
 * Full text search over the local session files ([LocalSessionIndex.searchable]).
 *
 * The raw JSON text is matched, case insensitively, instead of the decoded transcript: it is what
 * is on disk, no file has to be decoded unless it matches, and the escapes of the encoding (`\n`,
 * `\"`) being matchable as well is accepted. Nothing is cached — every search reads the files
 * again, so what is found is always what is stored now.
 */
object LocalSessionSearch {

    /** Characters shown on each side of the match in [LocalSessionSearchResult.snippet]. */
    const val SNIPPET_RADIUS: Int = 40

    /** Longest line of a [preview]; a longer one keeps its key and the part around the match. */
    const val PREVIEW_LINE_LIMIT: Int = 160

    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }

    /**
     * Files of [files] containing [query], newest first. [isCancelled] is polled between files so
     * a search overtaken by the next keystroke stops early; a file that cannot be read is skipped.
     */
    fun search(
        files: List<LocalSessionFile>,
        query: String,
        isCancelled: () -> Boolean = { false },
        read: (File) -> String? = { file -> runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull() },
    ): List<LocalSessionSearchResult> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val results = mutableListOf<LocalSessionSearchResult>()
        for (file in files.sortedByDescending { it.timestamp ?: it.file.lastModified() }) {
            if (isCancelled()) return emptyList()
            val text = read(file.file) ?: continue
            val at = text.indexOf(needle, ignoreCase = true)
            if (at < 0) continue
            val (sessionId, title) = identify(text, file.sanitizedId)
            results += LocalSessionSearchResult(file, sessionId, title, snippet(text, at, needle.length))
        }
        return results
    }

    /** `sessionId` and title of a snapshot document; the title falls back to the id. */
    internal fun identify(text: String, fallbackId: String): Pair<String, String> {
        val document = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val sessionId = (document?.get("sessionId") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: fallbackId
        val title = (document?.get("title") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return sessionId to (title ?: sessionId)
    }

    /** About [radius] characters on each side of the match at [start], whitespace folded to spaces. */
    fun snippet(text: String, start: Int, length: Int, radius: Int = SNIPPET_RADIUS): String {
        val from = (start - radius).coerceAtLeast(0)
        val to = (start + length + radius).coerceAtMost(text.length)
        val body = text.substring(from, to).replace(WHITESPACE, " ")
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return prefix + body + suffix
    }

    /**
     * The part of the snapshot document [text] that contains [query], pretty printed: the first
     * element of `entries` (one transcript entry) that matches, else the first top-level field.
     *
     * The text stays JSON — strings keep their escapes, so what is shown is what was matched — and
     * a line longer than [lineLimit] is cut down to its key and the part around the match. A text
     * that is not a snapshot, or a match across fields, falls back to the one-line [snippet].
     */
    fun preview(text: String, query: String, lineLimit: Int = PREVIEW_LINE_LIMIT): LocalSessionPreview? {
        val needle = query.trim()
        if (needle.isEmpty()) return null
        val document = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val part = document?.let { matchingPart(it, needle) }
        val body = if (part != null) {
            prettyJson.encodeToString(JsonElement.serializer(), part)
                .lines()
                .joinToString("\n") { shorten(it, needle, lineLimit) }
        } else {
            val at = text.indexOf(needle, ignoreCase = true)
            if (at < 0) return null
            snippet(text, at, needle.length)
        }
        return LocalSessionPreview(body, occurrences(body, needle))
    }

    private fun matchingPart(document: JsonObject, needle: String): JsonElement? {
        (document["entries"] as? JsonArray)
            ?.firstOrNull { it.toString().contains(needle, ignoreCase = true) }
            ?.let { return it }
        return document.entries
            .map { (key, value) -> JsonObject(mapOf(key to value)) }
            .firstOrNull { it.toString().contains(needle, ignoreCase = true) }
    }

    /** [line] cut to about [limit] characters, keeping its indent, its key and the match. */
    internal fun shorten(line: String, needle: String, limit: Int): String {
        if (line.length <= limit) return line
        val indent = line.takeWhile { it == ' ' }
        val rest = line.substring(indent.length)
        val key = KEY.find(rest)?.value.orEmpty()
        val value = rest.substring(key.length)
        val budget = (limit - indent.length - key.length).coerceAtLeast(needle.length + 2 * SNIPPET_RADIUS)
        val at = value.indexOf(needle, ignoreCase = true)
        if (at < 0) return indent + key + value.take(budget) + "…"
        val radius = (budget - needle.length) / 2
        val from = (at - radius).coerceAtLeast(0)
        val to = (at + needle.length + radius).coerceAtMost(value.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < value.length) "…" else ""
        return indent + key + prefix + value.substring(from, to) + suffix
    }

    /** Every place of [needle] in [text], case insensitively. */
    internal fun occurrences(text: String, needle: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var at = text.indexOf(needle, ignoreCase = true)
        while (at >= 0) {
            ranges += at until at + needle.length
            at = text.indexOf(needle, at + needle.length, ignoreCase = true)
        }
        return ranges
    }

    private val WHITESPACE = Regex("\\s+")

    /** The `"key": ` a pretty printed line of an object starts with. */
    private val KEY = Regex("^\"(?:[^\"\\\\]|\\\\.)*\": ")
}
