package com.tsuty.sandboxagent.session

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** File name rules, grouping and states of the local session directory index. */
class LocalSessionIndexTest {

    @Test
    fun `the four file name forms are parsed and anything else is rejected`() {
        assertEquals(Triple("sess_1", LocalSessionKind.CURRENT, null), LocalSessionIndex.parse("sess_1.json"))
        assertEquals(Triple("sess_1", LocalSessionKind.BACKUP, 123L), LocalSessionIndex.parse("sess_1.json.123.backup"))
        assertEquals(Triple("sess_1", LocalSessionKind.DELETED, 456L), LocalSessionIndex.parse("sess_1.json.456.deleted"))
        assertEquals(
            Triple("sess_1", LocalSessionKind.DELETED_BACKUP, 789L),
            LocalSessionIndex.parse("sess_1.json.789.deleted-backup"),
        )
        assertEquals(Triple("a.b", LocalSessionKind.CURRENT, null), LocalSessionIndex.parse("a.b.json"))

        assertNull(LocalSessionIndex.parse("sess_1.json1234567.tmp"))
        assertNull(LocalSessionIndex.parse("sess_1.json.abc.backup"))
        assertNull(LocalSessionIndex.parse("sess_1.json.123.backup.tmp"))
        assertNull(LocalSessionIndex.parse("sess_1.json.123.deleted-backup.tmp"))
        assertNull(LocalSessionIndex.parse("sess_1.json.abc.deleted-backup"))
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
        val index = LocalSessionIndex.scan(
            directoryWith("a.json", "a.json.1.backup", "a.json.3.deleted-backup", "b.json.2.deleted"),
        )

        assertEquals(
            setOf(LocalSessionKind.CURRENT, LocalSessionKind.BACKUP),
            index.searchable(includeDeleted = false).map { it.kind }.toSet(),
        )
        assertEquals(4, index.searchable(includeDeleted = true).size)
        assertTrue(index.searchable(includeDeleted = true).any { it.kind == LocalSessionKind.DELETED_BACKUP })
    }

    @Test
    fun `a session is deleted while only its moved aside files are left`() {
        val index = LocalSessionIndex.scan(
            directoryWith("gone.json.1.deleted", "back.json.2.deleted", "back.json", "kept.json", "kept.json.3.backup"),
        )

        assertTrue(index.isDeleted("gone"))
        assertFalse(index.isDeleted("back"))
        assertFalse(index.isDeleted("kept"))
        assertFalse(index.isDeleted("unknown"))
    }

    @Test
    fun `deleted backups are kept apart and never make the session count as deleted`() {
        val index = LocalSessionIndex.scan(
            directoryWith(
                "a.json",
                "a.json.1.backup",
                "a.json.2.deleted-backup",
                "a.json.3.deleted-backup",
                "b.json.4.deleted-backup",
            ),
        )

        val files = assertNotNull(index.of("a"))
        assertEquals(listOf(1L), files.backups.map { it.timestamp })
        assertEquals(listOf(3L, 2L), files.deletedBackups.map { it.timestamp })
        assertTrue(files.deleted.isEmpty())
        // Not listed among the backups, and the session is not deleted.
        assertEquals(listOf(1L), index.listedBackups("a").map { it.timestamp })
        assertEquals(StorageState.LOCAL_WITH_BACKUP, index.storageState("a"))
        assertFalse(index.isDeleted("a"))
        assertFalse(index.isDeleted("b"))
    }

    @Test
    fun `the permanent deletion covers every backup and deleted backup of a local session`() {
        val backups = (1..25).map { "a.json.$it.backup" }.toTypedArray()
        val index = LocalSessionIndex.scan(
            directoryWith(
                "a.json",
                *backups,
                "a.json.30.deleted-backup",
                "a.json.40.deleted",
                "only.json",
                "only.json.5.deleted-backup",
                "gone.json.6.backup",
                "gone.json.7.deleted-backup",
                "plain.json",
            ),
        )

        val a = index.permanentlyDeletableBackups("a")
        assertEquals(26, a.size)
        assertEquals(setOf(LocalSessionKind.BACKUP, LocalSessionKind.DELETED_BACKUP), a.map { it.kind }.toSet())
        // Every backup was moved aside one by one: the deleted ones are still offered.
        assertEquals(listOf(5L), index.permanentlyDeletableBackups("only").map { it.timestamp })
        // Without the current snapshot, as for the listed backups.
        assertTrue(index.permanentlyDeletableBackups("gone").isEmpty())
        assertTrue(index.permanentlyDeletableBackups("plain").isEmpty())
        assertTrue(index.permanentlyDeletableBackups("missing").isEmpty())
    }

    @Test
    fun `size in kilobytes is rounded up`() {
        fun kb(bytes: Long) = LocalSessionFile(File("x"), "x", LocalSessionKind.CURRENT, null, bytes).sizeKb
        assertEquals(0L, kb(0))
        assertEquals(1L, kb(1))
        assertEquals(1L, kb(1024))
        assertEquals(2L, kb(1025))
    }

    @Test
    fun `an index without some sessions reads them as kept nowhere`() {
        val directory = java.nio.file.Files.createTempDirectory("index-without").toFile()
        File(directory, "a.json").writeText("{}")
        File(directory, "b.json").writeText("{}")
        val index = LocalSessionIndex.scan(directory)

        val filtered = index.without(setOf("a"))

        assertEquals(StorageState.REMOTE_ONLY, filtered.storageState("a"))
        assertEquals(StorageState.LOCAL, filtered.storageState("b"))
        assertSame(index, index.without(emptySet()))
    }
}
