package com.tsuty.sandboxagent.session

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Matching, snippets and the deleted-file filter of the local session search. */
class LocalSessionSearchTest {

    private fun directoryWith(vararg files: Pair<String, String>): File {
        val directory = Files.createTempDirectory("local-session-search-test").toFile()
        files.forEach { (name, text) -> File(directory, name).writeText(text) }
        return directory
    }

    private fun snapshot(id: String, title: String?, text: String): String {
        val titleField = title?.let { ""","title":"$it"""" }.orEmpty()
        return """{"schemaVersion":1,"sessionId":"$id"$titleField,"entries":[{"entry":"user_message","text":"$text"}]}"""
    }

    @Test
    fun `the raw text is matched case insensitively`() {
        val directory = directoryWith(
            "sess_1.json" to snapshot("sess_1", "Parser", "Fix the PARSER bug"),
            "sess_2.json" to snapshot("sess_2", "Other", "unrelated"),
        )
        val files = LocalSessionIndex.scan(directory).searchable(includeDeleted = false)

        val results = LocalSessionSearch.search(files, "parser bug")

        assertEquals(listOf("sess_1"), results.map { it.sessionId })
        assertEquals("Parser", results.single().title)
        assertTrue(results.single().snippet.contains("PARSER bug"), results.single().snippet)
    }

    @Test
    fun `deleted files are only searched on request`() {
        val directory = directoryWith(
            "sess_1.json.10.backup" to snapshot("sess_1", "Backup", "needle"),
            "sess_2.json.20.deleted" to snapshot("sess_2", "Gone", "needle"),
            "sess_3.json12345.tmp" to snapshot("sess_3", "Temp", "needle"),
        )
        val index = LocalSessionIndex.scan(directory)

        val without = LocalSessionSearch.search(index.searchable(includeDeleted = false), "needle")
        val with = LocalSessionSearch.search(index.searchable(includeDeleted = true), "needle")

        assertEquals(listOf(LocalSessionKind.BACKUP), without.map { it.file.kind })
        // Newest first; the temporary file of an interrupted save is never searched.
        assertEquals(listOf(LocalSessionKind.DELETED, LocalSessionKind.BACKUP), with.map { it.file.kind })
    }

    @Test
    fun `the title falls back to the session id`() {
        val directory = directoryWith("sess_1.json" to snapshot("sess_1", null, "needle"))

        val result = LocalSessionSearch.search(LocalSessionIndex.scan(directory).searchable(false), "needle").single()

        assertEquals("sess_1", result.title)
    }

    @Test
    fun `a blank query and a cancelled search find nothing`() {
        val directory = directoryWith("sess_1.json" to snapshot("sess_1", "T", "needle"))
        val files = LocalSessionIndex.scan(directory).searchable(false)

        assertTrue(LocalSessionSearch.search(files, "  ").isEmpty())
        assertTrue(LocalSessionSearch.search(files, "needle", isCancelled = { true }).isEmpty())
    }

    @Test
    fun `the snippet keeps about forty characters on each side on one line`() {
        val text = "a".repeat(100) + "\n  NEEDLE\n" + "b".repeat(100)
        val start = text.indexOf("NEEDLE")

        val snippet = LocalSessionSearch.snippet(text, start, "NEEDLE".length)

        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("a NEEDLE b"), snippet)
        assertEquals(1 + 40 + 6 + 40 + 1 - 2, snippet.length)
        // Near the edges nothing is cut, so there is no ellipsis either.
        assertEquals("short NEEDLE text", LocalSessionSearch.snippet("short NEEDLE text", 6, 6))
    }

    @Test
    fun `the preview pretty prints the matching entry only and marks every match`() {
        val text = """{"schemaVersion":1,"sessionId":"s","title":"T","entries":[""" +
            """{"entry":"user_message","text":"first"},""" +
            """{"entry":"agent_message","text":"a Needle and a needle\n"}]}"""

        val preview = LocalSessionSearch.preview(text, "needle")!!

        assertEquals(
            """
            {
                "entry": "agent_message",
                "text": "a Needle and a needle\n"
            }
            """.trimIndent(),
            preview.text,
        )
        assertEquals(2, preview.matches.size)
        preview.matches.forEach { assertEquals("needle", preview.text.substring(it.first, it.last + 1).lowercase()) }
    }

    @Test
    fun `the preview falls back to the matching top-level field`() {
        val preview = LocalSessionSearch.preview(snapshot("s", "Parser work", "other"), "parser")!!

        assertEquals("{\n    \"title\": \"Parser work\"\n}", preview.text)
        assertEquals(1, preview.matches.size)
    }

    @Test
    fun `a long line keeps its key and the text around the match`() {
        val line = "    \"text\": \"" + "a".repeat(300) + "NEEDLE" + "b".repeat(300) + "\""

        val short = LocalSessionSearch.shorten(line, "needle", 160)

        assertTrue(short.startsWith("    \"text\": …a"), short)
        assertTrue(short.endsWith("b…"), short)
        assertTrue(short.contains("aNEEDLEb"), short)
        assertTrue(short.length <= 162, "${short.length}")
    }

    @Test
    fun `text that is not a snapshot previews as a snippet`() {
        val preview = LocalSessionSearch.preview("not json with a needle inside", "NEEDLE")!!

        assertEquals("not json with a needle inside", preview.text)
        assertEquals(listOf(16 until 22), preview.matches)
    }
}
