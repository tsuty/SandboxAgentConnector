package com.tsuty.sandboxagent.acp

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Records every JSON-RPC line exchanged with the agent, both as a debugging aid and as an audit
 * trail (todo.md item 5): each event is a single line made of a zero padded millisecond
 * timestamp, a direction marker (`OUT` for lines written to the agent, `IN` for lines received
 * from it) and the raw JSON payload.
 *
 * One log file per session keeps individual files small; the file is only created once the
 * `sessionId` is known (after `session/new` / `session/load`), so lines logged earlier
 * (`initialize`, ...) are buffered in memory and flushed once the file is opened. A single
 * connection goes through several sessions (new session button, session restore), so every new
 * `sessionId` closes the previous file and opens its own.
 */
class AcpTrafficLogger(
    private val logDirectory: File,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
) {
    private val pending = mutableListOf<String>()

    @Volatile
    private var writer: OutputStreamWriter? = null

    /** `sessionId` the currently open file belongs to; guards against re-adopting the same session. */
    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var closed = false

    fun logOutgoing(rawLine: String) = write("OUT", rawLine)

    fun logIncoming(rawLine: String) = write("IN", rawLine)

    @Synchronized
    private fun write(direction: String, rawLine: String) {
        if (closed) return
        val entry = "${TIMESTAMP_FORMAT.format(clock())} $direction $rawLine"
        val currentWriter = writer
        if (currentWriter == null) {
            pending += entry
        } else {
            appendLine(currentWriter, entry)
        }
    }

    /**
     * Opens the log file of [sessionId] and flushes the buffered lines; switching to another
     * session closes the previous file so each session keeps its own log.
     */
    @Synchronized
    fun onSessionEstablished(sessionId: String) {
        if (closed || sessionId == currentSessionId) return
        runCatching { writer?.close() }
        writer = null
        currentSessionId = null
        try {
            logDirectory.mkdirs()
            val fileName = "${SESSION_START_FORMAT.format(clock())}_${sanitize(sessionId)}.log"
            val newWriter = OutputStreamWriter(FileOutputStream(File(logDirectory, fileName), true), StandardCharsets.UTF_8)
            pending.forEach { appendLine(newWriter, it) }
            pending.clear()
            writer = newWriter
            currentSessionId = sessionId
        } catch (e: Exception) {
            log.warn("Failed to open ACP traffic log file", e)
        }
    }

    private fun appendLine(target: OutputStreamWriter, entry: String) {
        try {
            target.write(entry)
            target.write("\n")
            target.flush()
        } catch (e: Exception) {
            log.warn("Failed to write ACP traffic log entry", e)
        }
    }

    @Synchronized
    fun close() {
        closed = true
        runCatching { writer?.close() }
        writer = null
        currentSessionId = null
    }

    private fun sanitize(sessionId: String): String = sessionId.replace(ILLEGAL_FILENAME_CHARS, "_")

    companion object {
        private val log = Logger.getInstance(AcpTrafficLogger::class.java)

        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private val SESSION_START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val ILLEGAL_FILENAME_CHARS = Regex("[^A-Za-z0-9_.-]")
        private val LOG_FILE_NAME = Regex("^\\d{8}_\\d{6}_.*\\.log$")
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /**
         * Deletes the log files of [directory] last written more than [days] days ago. Only the
         * names this class writes are touched, and a failure is only logged.
         */
        fun deleteOlderThan(directory: File, days: Int, now: Long = System.currentTimeMillis()) {
            val oldest = now - days * MILLIS_PER_DAY
            directory.listFiles { file -> file.isFile && LOG_FILE_NAME.matches(file.name) }.orEmpty()
                .filter { it.lastModified() in 1 until oldest }
                .forEach { file ->
                    if (!file.delete()) log.warn("Failed to delete old ACP traffic log ${file.path}")
                }
        }
    }
}
