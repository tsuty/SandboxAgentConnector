package com.tsuty.sandboxagent.session

import com.tsuty.sandboxagent.acp.PlanEntry
import com.tsuty.sandboxagent.acp.PromptAttachment
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.ToolCallDiff
import com.tsuty.sandboxagent.acp.ToolCallStatus
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Save / load, schema fallback and freshness checks of the local transcript snapshots. */
class SessionSnapshotStoreTest {

    private fun tempStore(now: Long = 1_000L): Pair<SessionSnapshotStore, File> {
        val directory = Files.createTempDirectory("session-snapshot-test").toFile()
        return SessionSnapshotStore(directory = directory, clock = { now }) to directory
    }

    @Test
    fun `a saved snapshot is loaded back with every entry kind`() {
        val (store, _) = tempStore(now = 5_000L)
        val entries = listOf(
            UserMessageEntry("fix the parser"),
            AgentMessageEntry("Done, see the diff."),
            ThoughtEntry("the parser drops the last token"),
            ToolCallEntry(
                ToolCall(
                    toolCallId = "call_1",
                    title = "Edit Parser.kt",
                    status = ToolCallStatus.COMPLETED,
                    content = listOf(ToolCallDiff(path = "/workspace/Parser.kt", newText = "fixed")),
                ),
            ),
            PlanSnapshotEntry(listOf(PlanEntry(content = "fix", status = "completed"))),
            NoticeEntry("Turn finished"),
        )

        store.save("sess_1", entries, agentUpdatedAt = 4_000L, title = "Fix the parser")

        val snapshot = assertNotNull(store.load("sess_1"))
        assertEquals(SESSION_SNAPSHOT_SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals("sess_1", snapshot.sessionId)
        assertEquals(5_000L, snapshot.savedAt)
        assertEquals(4_000L, snapshot.agentUpdatedAt)
        assertEquals("Fix the parser", snapshot.title)
        assertEquals(entries.size, snapshot.entries.size)
        assertEquals("fix the parser", assertIs<UserMessageEntry>(snapshot.entries[0]).text)
        val toolCall = assertIs<ToolCallEntry>(snapshot.entries[3]).toolCall
        assertEquals("/workspace/Parser.kt", toolCall.diffs.single().path)
        assertEquals("completed", assertIs<PlanSnapshotEntry>(snapshot.entries[4]).entries.single().status)
    }

    @Test
    fun `saving twice leaves no temporary file behind`() {
        val (store, directory) = tempStore()

        store.save("sess_1", listOf(UserMessageEntry("first")))
        store.save("sess_1", listOf(UserMessageEntry("second")))

        assertEquals(listOf("sess_1.json"), directory.listFiles().orEmpty().map { it.name })
        assertEquals("second", assertIs<UserMessageEntry>(assertNotNull(store.load("sess_1")).entries.single()).text)
    }

    @Test
    fun `the session id is sanitized when used in the file name`() {
        val (store, directory) = tempStore()

        store.save("sess/weird:id", listOf(UserMessageEntry("hi")))

        assertEquals("sess_weird_id.json", directory.listFiles().orEmpty().single().name)
        assertNotNull(store.load("sess/weird:id"))
    }

    @Test
    fun `an unknown schema version and a corrupt file are treated as no local history`() {
        val (store, directory) = tempStore()
        directory.mkdirs()
        File(directory, "sess_future.json").writeText(
            """{"schemaVersion":99,"sessionId":"sess_future","entries":[]}""",
        )
        File(directory, "sess_broken.json").writeText("{not json")

        assertNull(store.load("sess_future"))
        assertNull(store.load("sess_broken"))
        assertNull(store.load("sess_missing"))
    }

    @Test
    fun `delete removes the snapshot file`() {
        val (store, directory) = tempStore()
        store.save("sess_1", listOf(UserMessageEntry("hi")))

        store.delete("sess_1")

        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNull(store.load("sess_1"))
        // Deleting twice is a no-op, not a failure.
        store.delete("sess_1")
    }

    @Test
    fun `freshness compares the snapshot against the agent side update time`() {
        val entries = listOf(UserMessageEntry("hi"))
        val snapshot = SessionSnapshot(sessionId = "sess_1", savedAt = 10, agentUpdatedAt = 100, entries = entries)

        assertTrue(snapshot.isFreshFor(100))
        assertTrue(snapshot.isFreshFor(50))
        assertFalse(snapshot.isFreshFor(200))
        // An agent that does not report a time cannot be compared against: the snapshot is used.
        assertTrue(snapshot.isFreshFor(null))
        // A snapshot without a known agent time is stale as soon as the agent reports one.
        assertFalse(SessionSnapshot(sessionId = "sess_1", entries = entries).isFreshFor(100))
        // An empty snapshot is never worth showing.
        assertFalse(SessionSnapshot(sessionId = "sess_1", agentUpdatedAt = 100).isFreshFor(100))
    }

    /**
     * Snapshots written before the prompt attachments were filtered out hold one entry per
     * attachment; reading them must not bring those blocks back on screen.
     */
    @Test
    fun `loading drops the attachment entries of an older snapshot`() {
        val (store, _) = tempStore()
        store.save(
            "sess_1",
            listOf(
                UserMessageEntry("sort the array"),
                UserMessageEntry("[@sample.json](file:///workspace/tmp/sample.json)"),
                UserMessageEntry("<context ref=\"file:///workspace/tmp/sample.json\">[]</context>"),
                AgentMessageEntry("Done."),
            ),
        )

        val entries = assertNotNull(store.load("sess_1")).entries

        assertEquals(2, entries.size)
        assertEquals("sort the array", assertIs<UserMessageEntry>(entries[0]).text)
        assertEquals("Done.", assertIs<AgentMessageEntry>(entries[1]).text)
    }

    /** What was attached to a prompt is part of it, so a restore has to show the same chips. */
    @Test
    fun `the attachments of a prompt survive a save and load`() {
        val (store, _) = tempStore()
        val attachments = listOf(
            PromptAttachment(path = "/workspace/tmp/sample.json"),
            PromptAttachment(path = "/workspace/tmp/sample.json", startLine = 5, endLine = 5),
        )
        store.save("sess_1", listOf(UserMessageEntry("sort the array", attachments)))

        val entry = assertIs<UserMessageEntry>(assertNotNull(store.load("sess_1")).entries.single())

        assertEquals(attachments, entry.attachments)
        assertEquals(listOf("sample.json", "sample.json:5-5"), entry.attachments.map { it.label })
    }

    /** A snapshot written before the chips existed simply has none, and stays readable. */
    @Test
    fun `a prompt saved without attachments loads with an empty list`() {
        val (store, directory) = tempStore()
        directory.mkdirs()
        File(directory, "sess_old.json").writeText(
            """{"schemaVersion":1,"sessionId":"sess_old","entries":[{"entry":"user_message","text":"hi"}]}""",
        )

        val entry = assertIs<UserMessageEntry>(assertNotNull(store.load("sess_old")).entries.single())

        assertEquals("hi", entry.text)
        assertTrue(entry.attachments.isEmpty())
    }

    @Test
    fun `migration of the current version is a no-op and unknown versions are rejected`() {
        val (store, _) = tempStore()
        store.save("sess_1", listOf(UserMessageEntry("hi")))

        assertEquals(SESSION_SNAPSHOT_SCHEMA_VERSION, assertNotNull(store.load("sess_1")).schemaVersion)
        assertNull(SessionSnapshotMigrators.migrate(JsonObject(emptyMap()), 0))
    }
}
