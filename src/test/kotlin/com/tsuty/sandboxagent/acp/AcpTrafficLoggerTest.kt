package com.tsuty.sandboxagent.acp

import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** File naming, buffering and line format checks for the request/response audit log. */
class AcpTrafficLoggerTest {

    @Test
    fun `lines logged before the session id is known are buffered until the file is opened`() {
        val dir = Files.createTempDirectory("acp-log-test").toFile()
        val start = LocalDateTime.of(2026, 8, 27, 12, 13, 14, 0)
        val logger = AcpTrafficLogger(logDirectory = dir, clock = { start })

        logger.logOutgoing("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        assertTrue(dir.listFiles().isNullOrEmpty())

        logger.onSessionEstablished("sess_1")
        logger.logIncoming("""{"jsonrpc":"2.0","id":1,"result":{}}""")

        val files = dir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertEquals("20260827_121314_sess_1.log", files.single().name)

        val lines = files.single().toPath().readLines()
        assertEquals(2, lines.size)
        assertEquals(
            """2026-08-27 12:13:14.000 OUT {"jsonrpc":"2.0","id":1,"method":"initialize"}""",
            lines[0],
        )
        assertEquals(
            """2026-08-27 12:13:14.000 IN {"jsonrpc":"2.0","id":1,"result":{}}""",
            lines[1],
        )
    }

    @Test
    fun `session id is sanitized when used in the file name`() {
        val dir = Files.createTempDirectory("acp-log-test").toFile()
        val logger = AcpTrafficLogger(logDirectory = dir, clock = { LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0) })

        logger.onSessionEstablished("sess/weird:id")

        val files = dir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertEquals("20260102_030405_sess_weird_id.log", files.single().name)
    }

    @Test
    fun `entering another session switches to a new log file`() {
        val dir = Files.createTempDirectory("acp-log-test").toFile()
        var now = LocalDateTime.of(2026, 8, 27, 12, 13, 14, 0)
        val logger = AcpTrafficLogger(logDirectory = dir, clock = { now })

        logger.onSessionEstablished("sess_1")
        logger.logOutgoing("""{"id":1,"method":"session/prompt"}""")

        now = LocalDateTime.of(2026, 8, 27, 12, 20, 0, 0)
        logger.onSessionEstablished("sess_2")
        logger.logOutgoing("""{"id":2,"method":"session/prompt"}""")

        val files = dir.listFiles().orEmpty().sortedBy { it.name }
        assertEquals(2, files.size)
        assertEquals("20260827_121314_sess_1.log", files[0].name)
        assertEquals("20260827_122000_sess_2.log", files[1].name)
        assertTrue(files[0].toPath().readLines().single().contains(""""id":1"""))
        assertTrue(files[1].toPath().readLines().single().contains(""""id":2"""))
    }

    @Test
    fun `re-adopting the same session keeps writing to the same file`() {
        val dir = Files.createTempDirectory("acp-log-test").toFile()
        val logger = AcpTrafficLogger(logDirectory = dir, clock = { LocalDateTime.of(2026, 8, 27, 12, 13, 14, 0) })

        logger.onSessionEstablished("sess_1")
        logger.logOutgoing("""{"id":1}""")
        logger.onSessionEstablished("sess_1")
        logger.logOutgoing("""{"id":2}""")

        val files = dir.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertEquals(2, files.single().toPath().readLines().size)
    }

    @Test
    fun `nothing is written once the logger is closed`() {
        val dir = Files.createTempDirectory("acp-log-test").toFile()
        val logger = AcpTrafficLogger(logDirectory = dir, clock = { LocalDateTime.now() })

        logger.onSessionEstablished("sess_1")
        logger.close()
        logger.logOutgoing("""{"jsonrpc":"2.0","id":2,"method":"session/cancel"}""")

        val lines = dir.listFiles()!!.single().toPath().readLines()
        assertFalse(lines.any { it.contains("session/cancel") })
    }

    @Test
    fun `only the logs written before the retention period are deleted`() {
        val dir = Files.createTempDirectory("acp-log-test").toFile()
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        fun log(name: String, age: Long) = java.io.File(dir, name).apply {
            writeText("")
            setLastModified(now - age)
        }
        val expired = log("20260101_000000_old.log", 60 * day + 1)
        val onTheLimit = log("20260101_000000_limit.log", 60 * day)
        val recent = log("20260101_000000_new.log", day)
        val foreign = log("notes.log", 90 * day)

        AcpTrafficLogger.deleteOlderThan(dir, days = 60, now = now)

        assertFalse(expired.exists())
        assertTrue(onTheLimit.exists())
        assertTrue(recent.exists())
        assertTrue(foreign.exists())
    }

    @Test
    fun `a missing log directory is nothing to delete`() {
        val dir = java.io.File(Files.createTempDirectory("acp-log-test").toFile(), "missing")

        AcpTrafficLogger.deleteOlderThan(dir, days = 60)

        assertFalse(dir.exists())
    }

    @Test
    fun `the data of an image is logged as its length`() {
        val data = "A".repeat(8_000)
        val line = """{"jsonrpc":"2.0","method":"session/update","params":{"update":""" +
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"image","mimeType":"image/png","data":"$data"}}}}"""

        val logged = AcpTrafficLogger.withoutMediaData(line)

        assertTrue("<8000 base64 chars omitted>" in logged, logged)
        assertTrue("image/png" in logged, logged)
        // A text block keeps its `data`-free line as it was, and a short line is not even parsed.
        val text = """{"type":"text","text":"${"x".repeat(8_000)}","data":"keep"}"""
        assertEquals(text, AcpTrafficLogger.withoutMediaData(text))
        assertEquals("not json", AcpTrafficLogger.withoutMediaData("not json"))
    }
}
