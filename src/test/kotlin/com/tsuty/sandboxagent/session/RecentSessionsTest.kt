package com.tsuty.sandboxagent.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentSessionsTest {

    private fun file(id: String, kind: LocalSessionKind = LocalSessionKind.CURRENT) =
        LocalSessionFile(File("/tmp/$id.json"), id, kind, null, 10)

    private fun snapshot(id: String, savedAt: Long?, agentId: String? = null, title: String? = null) =
        SessionSnapshot(sessionId = id, title = title, entries = listOf(UserMessageEntry("hi")), agentId = agentId, savedAt = savedAt)

    @Test
    fun `the newest current snapshots are listed by the time they were saved`() {
        val files = listOf(file("a"), file("b"), file("c"), file("d", LocalSessionKind.BACKUP))
        val snapshots = mapOf(
            "a" to snapshot("a", savedAt = 300, agentId = "gemini", title = "A"),
            // Copied later than it was saved: the modification time does not date it.
            "b" to snapshot("b", savedAt = 100),
            // Written before the save time was kept: its modification time dates it.
            "c" to snapshot("c", savedAt = null),
        )
        val modified = mapOf("a" to 300L, "b" to 900L, "c" to 200L)

        val recent = RecentSessions.load(
            LocalSessionIndex.fromFiles(files),
            read = { snapshots[it.sanitizedId] },
            legacyAgentId = "claude-acp",
            renameOverride = { if (it == "c") "Renamed" else null },
            lastModified = { modified.getValue(it.sanitizedId) },
            limit = 2,
        )

        assertEquals(listOf("a", "c"), recent.map { it.sessionId })
        assertEquals(listOf("gemini", "claude-acp"), recent.map { it.agentId })
        assertEquals(listOf("A", "Renamed"), recent.map { it.title })
        assertEquals(200L, recent[1].savedAt)
    }

    @Test
    fun `empty and unreadable snapshots are left out`() {
        val recent = RecentSessions.load(
            LocalSessionIndex.fromFiles(listOf(file("a"), file("b"))),
            read = { if (it.sanitizedId == "a") SessionSnapshot(sessionId = "a") else null },
            legacyAgentId = "claude-acp",
            lastModified = { 1 },
        )

        assertEquals(emptyList(), recent)
    }
}
