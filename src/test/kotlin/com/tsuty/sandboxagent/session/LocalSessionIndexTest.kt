package com.tsuty.sandboxagent.session

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** File name rules, grouping and states of the local session directory index. */
class LocalSessionIndexTest {

    @Test
    fun `the three file name forms are parsed and anything else is rejected`() {
        assertEquals(Triple("sess_1", LocalSessionKind.CURRENT, null), LocalSessionIndex.parse("sess_1.json"))
        assertEquals(Triple("sess_1", LocalSessionKind.BACKUP, 123L), LocalSessionIndex.parse("sess_1.json.123.backup"))
        assertEquals(Triple("sess_1", LocalSessionKind.DELETED, 456L), LocalSessionIndex.parse("sess_1.json.456.deleted"))
        assertEquals(Triple("a.b", LocalSessionKind.CURRENT, null), LocalSessionIndex.parse("a.b.json"))

        assertNull(LocalSessionIndex.parse("sess_1.json1234567.tmp"))
        assertNull(LocalSessionIndex.parse("sess_1.json.abc.backup"))
        assertNull(LocalSessionIndex.parse("sess_1.json.123.backup.tmp"))
        assertNull(LocalSessionIndex.parse(".json"))
        assertNull(LocalSessionIndex.parse("notes.txt"))
    }

    private fun directoryWith(vararg names: String): File {
        val directory = Files.createTempDirectory("local-session-index-test").toFile()
        names.forEach { File(directory, it).writeText("x".repeat(10)) }
        return directory
    }

    @Test
    fun `scan groups the files per session with backups newest first`() {
        val directory = directoryWith(
            "sess_1.json",
            "sess_1.json.100.backup",
            "sess_1.json.300.backup",
            "sess_1.json.200.backup",
            "sess_2.json.50.deleted",
            "sess_1.json999.tmp",
        )

        val index = LocalSessionIndex.scan(directory)

        val files = assertNotNull(index.of("sess_1"))
        assertNotNull(files.current)
        assertEquals(listOf(300L, 200L, 100L), files.backups.map { it.timestamp })
        assertEquals(10L, files.current.sizeBytes)
        assertEquals(1L, files.current.sizeKb)
        assertEquals(listOf(50L), assertNotNull(index.of("sess_2")).deleted.map { it.timestamp })
        assertEquals(setOf("sess_1", "sess_2"), index.bySanitizedId.keys)
    }

    @Test
    fun `storage state distinguishes local, local with backups and remote only`() {
        val index = LocalSessionIndex.scan(
            directoryWith("a.json", "b.json", "b.json.1.backup", "c.json.1.deleted", "d.json.1.backup"),
        )

        assertEquals(StorageState.LOCAL, index.storageState("a"))
        assertEquals(StorageState.LOCAL_WITH_BACKUP, index.storageState("b"))
        assertEquals(StorageState.REMOTE_ONLY, index.storageState("c"))
        assertEquals(StorageState.REMOTE_ONLY, index.storageState("d"))
        assertEquals(StorageState.REMOTE_ONLY, index.storageState("missing"))
    }

    @Test
    fun `backups are listed only while the current snapshot exists`() {
        val index = LocalSessionIndex.scan(
            directoryWith("b.json", "b.json.1.backup", "b.json.2.backup", "d.json.1.backup"),
        )

        assertEquals(listOf(2L, 1L), index.listedBackups("b").map { it.timestamp })
        assertEquals(emptyList(), index.listedBackups("d"))
        assertEquals(emptyList(), index.listedBackups("missing"))
        // The backups of `d` stay searchable.
        assertEquals(3, index.searchable(includeDeleted = false).count { it.kind == LocalSessionKind.BACKUP })
    }

    @Test
    fun `the lookup sanitizes the session id like the store does`() {
        val index = LocalSessionIndex.scan(directoryWith("sess_weird_id.json"))

        assertNotNull(index.of("sess/weird:id"))
    }

    @Test
    fun `searchable files include deleted ones only on request`() {
        val index = LocalSessionIndex.scan(directoryWith("a.json", "a.json.1.backup", "b.json.2.deleted"))

        assertEquals(
            setOf(LocalSessionKind.CURRENT, LocalSessionKind.BACKUP),
            index.searchable(includeDeleted = false).map { it.kind }.toSet(),
        )
        assertEquals(3, index.searchable(includeDeleted = true).size)
    }

    @Test
    fun `size in kilobytes is rounded up`() {
        fun kb(bytes: Long) = LocalSessionFile(File("x"), "x", LocalSessionKind.CURRENT, null, bytes).sizeKb
        assertEquals(0L, kb(0))
        assertEquals(1L, kb(1))
        assertEquals(1L, kb(1024))
        assertEquals(2L, kb(1025))
    }
}
