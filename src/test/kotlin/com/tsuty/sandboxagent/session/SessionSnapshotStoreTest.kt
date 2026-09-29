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
    fun `readText returns the raw file or null`() {
        val (store, directory) = tempStore()
        store.save("sess_1", listOf(UserMessageEntry("hi")))

        val text = assertNotNull(store.readText(File(directory, "sess_1.json")))

        assertTrue(text.contains("\"text\":\"hi\""), text)
        assertNull(store.readText(File(directory, "sess_missing.json")))
    }

    @Test
    fun `a snapshot with entries is always restored, whatever the update times`() {
        val entries = listOf(UserMessageEntry("hi"))

        assertTrue(SessionSnapshot(sessionId = "sess_1", savedAt = 10, agentUpdatedAt = 100, entries = entries).isRestorable)
        assertTrue(SessionSnapshot(sessionId = "sess_1", entries = entries).isRestorable)
        // An empty snapshot is never worth showing.
        assertFalse(SessionSnapshot(sessionId = "sess_1", agentUpdatedAt = 100).isRestorable)
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

    private fun tickingStore(): Pair<SessionSnapshotStore, File> {
        val directory = Files.createTempDirectory("session-snapshot-test").toFile()
        var now = 1_000L
        return SessionSnapshotStore(directory = directory, clock = { now++ }) to directory
    }

    private fun File.names(): List<String> = listFiles().orEmpty().map { it.name }.sorted()

    @Test
    fun `backup copies the current snapshot next to it and is loadable`() {
        val (store, directory) = tempStore(now = 7_000L)
        store.save("sess_1", listOf(UserMessageEntry("hi")))

        val backup = assertNotNull(store.backup("sess_1"))

        assertEquals("sess_1.json.7000.backup", backup.name)
        assertEquals(listOf("sess_1.json", "sess_1.json.7000.backup"), directory.names())
        assertEquals("hi", assertIs<UserMessageEntry>(assertNotNull(store.loadFile(backup)).entries.single()).text)
        // Nothing to protect without a snapshot.
        assertNull(store.backup("sess_missing"))
    }

    @Test
    fun `an unchanged replay keeps the local record and makes no backup`() {
        val (store, directory) = tickingStore()
        val live = listOf(UserMessageEntry("a"), AgentMessageEntry("b"), NoticeEntry("Turn finished"))
        store.save("sess_1", live, agentUpdatedAt = 10L, title = "Old")
        val before = assertNotNull(store.load("sess_1"))

        val replayed = listOf(UserMessageEntry("a"), AgentMessageEntry("b"), TurnEndEntry)
        assertNull(store.replaceIfChanged("sess_1", replayed, agentUpdatedAt = 20L, title = "New"))

        assertEquals(listOf("sess_1.json"), directory.names())
        assertEquals(before, store.load("sess_1"))
    }

    @Test
    fun `a changed replay backs the local record up and becomes the snapshot`() {
        val (store, directory) = tickingStore()
        val live = listOf(UserMessageEntry("a"), AgentMessageEntry("b"), NoticeEntry("Turn finished"))
        store.save("sess_1", live, agentUpdatedAt = 10L, title = "Old")

        val replayed = listOf(UserMessageEntry("a"), AgentMessageEntry("c"), TurnEndEntry)
        val replaced = assertNotNull(store.replaceIfChanged("sess_1", replayed, agentUpdatedAt = 20L, title = null))

        assertEquals(replayed, replaced.entries)
        assertEquals(20L, replaced.agentUpdatedAt)
        assertEquals("Old", replaced.title)
        assertEquals(replaced, store.load("sess_1"))
        val backup = assertNotNull(store.index().of("sess_1")).backups.single()
        assertEquals(live, assertNotNull(store.loadFile(backup.file)).entries)
        assertEquals(2, directory.names().size)
    }

    @Test
    fun `a replay without messages or without a local snapshot changes nothing`() {
        val (store, directory) = tickingStore()
        store.save("sess_1", listOf(UserMessageEntry("a"), AgentMessageEntry("b")))

        // An agent that reconnects without replaying anything tells nothing about its history.
        assertNull(store.replaceIfChanged("sess_1", listOf(TurnEndEntry)))
        assertNull(store.replaceIfChanged("sess_missing", listOf(UserMessageEntry("a"))))

        assertEquals(listOf("sess_1.json"), directory.names())
    }

    @Test
    fun `the comparison ignores what only a live turn records`() {
        val call = ToolCall(toolCallId = "call_1", title = "Edit", status = ToolCallStatus.COMPLETED)
        val live = listOf(
            UserMessageEntry("fix it", listOf(PromptAttachment(path = "/workspace/A.kt"))),
            ThoughtEntry("thinking"),
            AgentMessageEntry("Looking"),
            ThoughtEntry("more"),
            AgentMessageEntry(" at it."),
            ToolCallEntry(call),
            PlanSnapshotEntry(listOf(PlanEntry(content = "fix", status = "completed"))),
            NoticeEntry("Something failed", error = true),
            NoticeEntry("Cancelled"),
            UserMessageEntry("again"),
            AgentMessageEntry("ok"),
            NoticeEntry("Turn finished"),
        )
        val replayed = listOf(
            UserMessageEntry("fix it"),
            AgentMessageEntry("Looking at it."),
            ThoughtEntry("thinking"),
            ToolCallEntry(call.copy(title = "Edit A.kt", status = ToolCallStatus.FAILED)),
            TurnEndEntry,
            UserMessageEntry("again"),
            AgentMessageEntry("ok"),
            TurnEndEntry,
        )

        assertTrue(SessionSnapshotStore.sameTranscript(live, replayed))
    }

    @Test
    fun `the comparison sees every change a replay can make`() {
        val a = UserMessageEntry("a")
        val b = AgentMessageEntry("b")
        val turn = listOf(a, b, TurnEndEntry)
        assertTrue(SessionSnapshotStore.sameTranscript(turn, turn))
        // A turn missing, a message changed in the middle, a prompt changed, an answer gone.
        assertFalse(SessionSnapshotStore.sameTranscript(turn + turn, turn))
        assertFalse(SessionSnapshotStore.sameTranscript(listOf(a, b, TurnEndEntry, a), listOf(a, AgentMessageEntry("c"), TurnEndEntry, a)))
        assertFalse(SessionSnapshotStore.sameTranscript(turn, listOf(UserMessageEntry("x"), b, TurnEndEntry)))
        assertFalse(SessionSnapshotStore.sameTranscript(turn, listOf(a, TurnEndEntry)))
        // Only prompts and agent messages count: a tool call alone is no change.
        assertTrue(
            SessionSnapshotStore.sameTranscript(
                listOf(a, ToolCallEntry(ToolCall(toolCallId = "call_1")), b),
                listOf(a, b),
            ),
        )
    }

    /**
     * A live record ends every turn with a stop-reason notice, while a replay records a single
     * `turn_end` after all of its turns: the boundaries never line up and are not compared.
     */
    @Test
    fun `the comparison ignores turn boundaries`() {
        val finished = NoticeEntry("Turn finished")
        val live = listOf(UserMessageEntry("a"), AgentMessageEntry("b"), finished, UserMessageEntry("c"), AgentMessageEntry("d"), finished)
        val replayed = listOf(UserMessageEntry("a"), AgentMessageEntry("b"), UserMessageEntry("c"), AgentMessageEntry("d"), TurnEndEntry)

        assertTrue(SessionSnapshotStore.sameTranscript(live, replayed))
        // The prompts still keep the turns apart: one turn missing is a change.
        assertFalse(SessionSnapshotStore.sameTranscript(live, replayed.take(2) + TurnEndEntry))
    }

    @Test
    fun `backups are never pruned`() {
        val (store, directory) = tickingStore()
        store.save("sess_1", listOf(UserMessageEntry("a")))
        repeat(25) { store.backup("sess_1") }
        store.replaceIfChanged("sess_1", listOf(UserMessageEntry("b")))

        assertEquals(26, directory.names().count { it.endsWith(".backup") })
    }

    @Test
    fun `moving to deleted renames the snapshot and its backups`() {
        val (store, directory) = tickingStore()
        store.save("sess_1", listOf(UserMessageEntry("hi")))
        store.save("sess_2", listOf(UserMessageEntry("other")))
        val backup = assertNotNull(store.backup("sess_1"))
        val backupTimestamp = assertNotNull(assertNotNull(store.index().of("sess_1")).backups.single().timestamp)
        store.backup("sess_2")

        store.moveToDeleted("sess_1")

        val names = directory.names()
        assertFalse("sess_1.json" in names)
        assertFalse(backup.exists())
        assertTrue(names.none { it.startsWith("sess_1.") && it.endsWith(".backup") })
        val files = assertNotNull(store.index().of("sess_1"))
        assertNull(files.current)
        assertEquals(2, files.deleted.size)
        // The former backup keeps the time it was taken at.
        assertTrue(files.deleted.any { it.timestamp == backupTimestamp })
        files.deleted.forEach { assertNotNull(store.loadFile(it.file)) }
        assertNull(store.load("sess_1"))
        // Other sessions are untouched.
        assertTrue("sess_2.json" in names)
        assertTrue(names.any { it.startsWith("sess_2.json.") && it.endsWith(".backup") })
    }

    @Test
    fun `moving a session without a snapshot to deleted still moves its backups`() {
        val (store, directory) = tempStore()
        directory.mkdirs()
        File(directory, "sess_1.json.5.backup").writeText("{}")

        store.moveToDeleted("sess_1")

        assertEquals(listOf("sess_1.json.5.deleted"), directory.names())
    }

    @Test
    fun `moving to deleted does nothing without any file`() {
        val (store, directory) = tempStore()

        store.moveToDeleted("sess_1")

        assertTrue(directory.names().isEmpty())
    }

    @Test
    fun `a corrupt file loads as null`() {
        val (store, directory) = tempStore()
        directory.mkdirs()
        val broken = File(directory, "sess_1.json.5.backup").apply { writeText("{not json") }

        assertNull(store.loadFile(broken))
    }

    @Test
    fun `migration of the current version is a no-op and unknown versions are rejected`() {
        val (store, _) = tempStore()
        store.save("sess_1", listOf(UserMessageEntry("hi")))

        assertEquals(SESSION_SNAPSHOT_SCHEMA_VERSION, assertNotNull(store.load("sess_1")).schemaVersion)
        assertNull(SessionSnapshotMigrators.migrate(JsonObject(emptyMap()), 0))
    }
}
