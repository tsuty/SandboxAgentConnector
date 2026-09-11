package com.tsuty.sandboxagent.ui

/** Added / removed line counters of a unified diff hunk set. */
data class DiffStats(val added: Int, val removed: Int) {
    val presentableText: String get() = "+$added / -$removed"
}

/** A file name header plus the unified diff hunks shown inline in the transcript. */
data class SimpleDiff(val path: String, val text: String, val stats: DiffStats) {
    val presentableText: String get() = "$path  ${stats.presentableText}"

    /** Name of the changed file, which is what the cards show instead of the whole path. */
    val fileName: String get() = path.substringAfterLast('/')

    /** Lines of [text] without the trailing empty one, ready to be rendered row by row. */
    val lines: List<String> get() = text.trimEnd('\n').lines()
}

/**
 * Minimal unified diff reader / writer.
 *
 * ACP tool calls describe file edits either as a unified diff blob or as the `oldText` /
 * `newText` pair of a `diff` tool call content, so both directions are needed: a line summary
 * (`+`/`-` counters), the simple inline diff text and the two sides for the platform diff viewer.
 */
object UnifiedDiffSupport {

    /** Above this size the line-by-line diff is skipped and the change is reported as a rewrite. */
    private const val MAX_DIFF_LINES = 2_000

    /** Unchanged lines kept around a change, exactly like `diff -U3` does. */
    private const val CONTEXT_LINES = 3

    fun stats(diff: String?): DiffStats {
        if (diff.isNullOrEmpty()) return DiffStats(0, 0)
        var added = 0
        var removed = 0
        diff.lineSequence().forEach { line ->
            when {
                isHeader(line) -> Unit
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        return DiffStats(added, removed)
    }

    /** Rebuilds the before/after content of a unified diff, ignoring headers and hunk markers. */
    fun sides(diff: String?): Pair<String, String> {
        if (diff.isNullOrEmpty()) return "" to ""
        val before = StringBuilder()
        val after = StringBuilder()
        diff.lineSequence().forEach { line ->
            when {
                isHeader(line) -> Unit
                line.startsWith("+") -> after.appendLine(line.drop(1))
                line.startsWith("-") -> before.appendLine(line.drop(1))
                else -> {
                    val content = line.removePrefix(" ")
                    before.appendLine(content)
                    after.appendLine(content)
                }
            }
        }
        return before.toString() to after.toString()
    }

    /**
     * Builds the inline diff of an ACP `diff` tool call content: a file header followed by real
     * unified diff hunks (`@@ -l,c +l,c @@` plus [CONTEXT_LINES] unchanged lines around every
     * change).
     *
     * The context is what makes the change readable: a bare list of `+`/`-` lines says nothing
     * about where in the file the edit happened, and an edit that only moves lines around then
     * looks like an unrelated pile of additions and removals.
     */
    fun fromTexts(path: String, oldText: String?, newText: String): SimpleDiff {
        val oldLines = linesOf(oldText)
        val newLines = linesOf(newText)

        val header = buildString {
            appendLine("--- $path")
            appendLine("+++ $path")
        }

        if (oldLines.size > MAX_DIFF_LINES || newLines.size > MAX_DIFF_LINES) {
            return SimpleDiff(
                path = path,
                text = header + "@@ file rewritten @@\n",
                stats = DiffStats(added = newLines.size, removed = oldLines.size),
            )
        }

        val changes = diff(oldLines, newLines)
        val added = changes.count { it is Change.Added }
        val removed = changes.count { it is Change.Removed }
        return SimpleDiff(path, header + hunks(changes), DiffStats(added, removed))
    }

    private sealed interface Change {
        val line: String

        data class Kept(override val line: String) : Change
        data class Added(override val line: String) : Change
        data class Removed(override val line: String) : Change
    }

    /**
     * Groups [changes] into unified diff hunks: every run of changed lines is emitted with up to
     * [CONTEXT_LINES] kept lines before and after it, and the runs that overlap are merged.
     */
    private fun hunks(changes: List<Change>): String {
        val changed = changes.indices.filter { changes[it] !is Change.Kept }
        if (changed.isEmpty()) return ""

        val body = StringBuilder()
        var oldLine = 1
        var newLine = 1
        // Line numbers of the file sides at every position of `changes`, so a hunk header can be
        // written without walking the list again.
        val oldStarts = IntArray(changes.size)
        val newStarts = IntArray(changes.size)
        changes.forEachIndexed { index, change ->
            oldStarts[index] = oldLine
            newStarts[index] = newLine
            when (change) {
                is Change.Kept -> {
                    oldLine++
                    newLine++
                }
                is Change.Removed -> oldLine++
                is Change.Added -> newLine++
            }
        }

        var cursor = 0
        while (cursor < changed.size) {
            val from = (changed[cursor] - CONTEXT_LINES).coerceAtLeast(0)
            var last = changed[cursor]
            // Absorb the next change while its leading context touches the current hunk.
            while (cursor + 1 < changed.size && changed[cursor + 1] - last <= CONTEXT_LINES * 2) {
                cursor++
                last = changed[cursor]
            }
            val to = (last + CONTEXT_LINES).coerceAtMost(changes.size - 1)
            val slice = changes.subList(from, to + 1)
            val oldCount = slice.count { it !is Change.Added }
            val newCount = slice.count { it !is Change.Removed }
            body.appendLine(
                "@@ -${range(oldStarts[from], oldCount)} +${range(newStarts[from], newCount)} @@",
            )
            slice.forEach { change ->
                when (change) {
                    is Change.Kept -> body.appendLine(" ${change.line}")
                    is Change.Added -> body.appendLine("+${change.line}")
                    is Change.Removed -> body.appendLine("-${change.line}")
                }
            }
            cursor++
        }
        return body.toString()
    }

    /** Lines of one file side; the trailing newline of a text file does not start a new line. */
    private fun linesOf(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.removeSuffix("\n").lines()
    }

    /** `start,count` of a hunk header; an empty side is reported as `0,0`, as unified diff does. */
    private fun range(start: Int, count: Int): String = if (count == 0) "0,0" else "$start,$count"

    /** Classic LCS table walk reporting the kept lines as well, so hunks can carry context. */
    private fun diff(oldLines: List<String>, newLines: List<String>): List<Change> {
        val lengths = Array(oldLines.size + 1) { IntArray(newLines.size + 1) }
        for (i in oldLines.indices.reversed()) {
            for (j in newLines.indices.reversed()) {
                lengths[i][j] = if (oldLines[i] == newLines[j]) {
                    lengths[i + 1][j + 1] + 1
                } else {
                    maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
            }
        }
        val changes = mutableListOf<Change>()
        var i = 0
        var j = 0
        while (i < oldLines.size && j < newLines.size) {
            when {
                oldLines[i] == newLines[j] -> {
                    changes += Change.Kept(oldLines[i])
                    i++
                    j++
                }
                lengths[i + 1][j] >= lengths[i][j + 1] -> changes += Change.Removed(oldLines[i++])
                else -> changes += Change.Added(newLines[j++])
            }
        }
        while (i < oldLines.size) changes += Change.Removed(oldLines[i++])
        while (j < newLines.size) changes += Change.Added(newLines[j++])
        return changes
    }

    private fun isHeader(line: String): Boolean =
        line.startsWith("@@") ||
            line.startsWith("+++") ||
            line.startsWith("---") ||
            line.startsWith("diff ") ||
            line.startsWith("index ")
}
