package com.tsuty.sandboxagent.acp

/**
 * Keeps the last lines the agent process wrote to `stderr`.
 *
 * `stderr` is the only place the first run reports progress: `npx` downloads and unpacks the
 * agent there while `initialize` waits. The lines are cleaned up for display — ANSI escapes are
 * dropped and `\r` (used by progress bars to rewrite the current line) separates lines like `\n`
 * does — so the newest one can be shown as the connection message, and the last few can be
 * appended to an error.
 */
class AgentStderrTail(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<String>()
    private val buffer = StringBuilder()

    /** Newest non-blank line, or `null` while nothing readable has been written yet. */
    val lastLine: String?
        get() = synchronized(lines) { lines.lastOrNull() }

    /** Appends a raw chunk; partial lines are held back until their terminator arrives. */
    fun append(chunk: String) {
        synchronized(lines) {
            buffer.append(chunk)
            var start = 0
            for (i in buffer.indices) {
                if (buffer[i] != '\n' && buffer[i] != '\r') continue
                add(buffer.substring(start, i))
                start = i + 1
            }
            buffer.delete(0, start)
        }
    }

    /** The [count] newest lines, oldest first. */
    fun tail(count: Int): List<String> = synchronized(lines) { lines.takeLast(count) }

    private fun add(line: String) {
        val cleaned = strip(line)
        if (cleaned.isEmpty()) return
        lines.addLast(cleaned)
        while (lines.size > capacity) lines.removeFirst()
    }

    companion object {
        /** Enough to quote a meaningful excerpt in an error without turning it into a log dump. */
        const val DEFAULT_CAPACITY: Int = 20

        /** CSI / OSC escapes, which `npx` and npm use for colours and for the progress bar. */
        private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*(\u0007|\u001B\\\\)")

        /** [line] without ANSI escapes and surrounding whitespace. */
        fun strip(line: String): String = ANSI.replace(line, "").trim()
    }
}
