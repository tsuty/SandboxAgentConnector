package com.tsuty.sandboxagent.session

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacySessionMigrationTest {
    private val root = Files.createTempDirectory("legacy-session-migration").toFile()
    private val target = File(root, "target")

    private fun source(name: String): File = File(root, name).apply { mkdirs() }

    private fun File.write(name: String, text: String, modified: Long = 1_000_000L): File =
        File(this, name).apply {
            writeText(text)
            setLastModified(modified)
        }

    private fun targetNames(): Set<String> = target.list().orEmpty().toSet() - LegacySessionMigration.MARKER_NAME

    @Test
    fun `the session directory of every IDE next to the current one is a source`() {
        val jetBrains = File(root, "JetBrains")
        val goLandOld = File(jetBrains, "GoLand2025.3").apply { mkdirs() }
        val goLandNew = File(jetBrains, "GoLand2026.1").apply { mkdirs() }
        val webStorm = File(jetBrains, "WebStorm2026.1").apply { mkdirs() }
        File(jetBrains, "a-file").writeText("")

        val sources = LegacySessionMigration.sources(goLandNew, "project.1234").toSet()

        assertEquals(
            listOf(goLandOld, goLandNew, webStorm).map { File(it, "sandbox-agent-sessions/project.1234") }.toSet(),
            sources,
        )
    }

    @Test
    fun `session files are copied with their modification time and the originals stay`() {
        val source = source("old")
        val original = source.write("s1.json", "current", modified = 5_000_000L)
        source.write("s1.json.1.backup", "backup")
        source.write("s1.json.2.deleted", "deleted")
        source.write("s1.json.3.deleted-backup", "deleted backup")

        LegacySessionMigration.migrate(listOf(source), target)

        assertEquals(setOf("s1.json", "s1.json.1.backup", "s1.json.2.deleted", "s1.json.3.deleted-backup"), targetNames())
        assertEquals("current", File(target, "s1.json").readText())
        assertEquals(5_000_000L, File(target, "s1.json").lastModified())
        assertTrue(original.exists())
    }

    @Test
    fun `a file that is not a session file and a missing source are skipped`() {
        val source = source("old")
        source.write("s1.json.tmp", "interrupted")
        source.write("notes.txt", "foreign")

        LegacySessionMigration.migrate(listOf(File(root, "missing"), source), target)

        assertEquals(emptySet(), targetNames())
        assertTrue(File(target, LegacySessionMigration.MARKER_NAME).exists())
    }

    @Test
    fun `of two versions of a session the newest is current and the other a backup`() {
        val older = source("older").apply { write("s1.json", "older", modified = 2_000_000L) }
        val newer = source("newer").apply { write("s1.json", "newer", modified = 3_000_000L) }

        LegacySessionMigration.migrate(listOf(older, newer), target)

        assertEquals("newer", File(target, "s1.json").readText())
        assertEquals("older", File(target, "s1.json.2000000.backup").readText())
        assertEquals(setOf("s1.json", "s1.json.2000000.backup"), targetNames())
    }

    @Test
    fun `an older version found after the newer one becomes the backup as well`() {
        val newer = source("newer").apply { write("s1.json", "newer", modified = 3_000_000L) }
        val older = source("older").apply { write("s1.json", "older", modified = 2_000_000L) }

        LegacySessionMigration.migrate(listOf(newer, older), target)

        assertEquals("newer", File(target, "s1.json").readText())
        assertEquals("older", File(target, "s1.json.2000000.backup").readText())
    }

    @Test
    fun `the same content in two sources makes no backup`() {
        val first = source("first").apply { write("s1.json", "same", modified = 2_000_000L) }
        val second = source("second").apply { write("s1.json", "same", modified = 3_000_000L) }

        LegacySessionMigration.migrate(listOf(first, second), target)

        assertEquals(setOf("s1.json"), targetNames())
    }

    @Test
    fun `a backup of another content never takes the name of an existing one`() {
        val first = source("first").apply { write("s1.json", "first", modified = 2_000_000L) }
        val second = source("second").apply { write("s1.json", "second", modified = 2_000_000L) }
        val third = source("third").apply { write("s1.json", "third", modified = 2_000_000L) }

        LegacySessionMigration.migrate(listOf(first, second, third), target)

        assertEquals("first", File(target, "s1.json").readText())
        assertEquals("second", File(target, "s1.json.2000000.backup").readText())
        assertEquals("third", File(target, "s1.json.2000001.backup").readText())
    }

    @Test
    fun `a backup of the same name keeps what was migrated first`() {
        val first = source("first").apply { write("s1.json.1.backup", "first") }
        val second = source("second").apply { write("s1.json.1.backup", "second") }

        LegacySessionMigration.migrate(listOf(first, second), target)

        assertEquals("first", File(target, "s1.json.1.backup").readText())
    }

    @Test
    fun `a finished migration does not bring back what was deleted since`() {
        val source = source("old").apply { write("s1.json", "current") }
        LegacySessionMigration.migrate(listOf(source), target)
        assertTrue(File(target, "s1.json").delete())

        LegacySessionMigration.migrate(listOf(source), target)

        assertFalse(File(target, "s1.json").exists())
    }

    @Test
    fun `an interrupted migration run again leaves the same files`() {
        val older = source("older").apply { write("s1.json", "older", modified = 2_000_000L) }
        val newer = source("newer").apply { write("s1.json", "newer", modified = 3_000_000L) }
        LegacySessionMigration.migrate(listOf(older, newer), target)
        val before = targetNames()
        assertTrue(File(target, LegacySessionMigration.MARKER_NAME).delete())

        LegacySessionMigration.migrate(listOf(older, newer), target)

        assertEquals(before, targetNames())
        assertEquals("newer", File(target, "s1.json").readText())
    }
}
