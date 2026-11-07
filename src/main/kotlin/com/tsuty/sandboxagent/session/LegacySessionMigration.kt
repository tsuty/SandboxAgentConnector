package com.tsuty.sandboxagent.session

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Copies the session files out of the directories they were kept in before they moved to
 * [PluginDataDirectories]: one per product and version of the IDE. Runs once per project, a marker
 * file stops it from bringing back what was deleted since. The originals are left alone, so an
 * older IDE or plugin version keeps working on its own files.
 */
internal object LegacySessionMigration {
    const val MARKER_NAME: String = ".migrated"

    /** Name of the directory under the `system` directory of the IDE that held the sessions. */
    private const val LEGACY_SUBDIRECTORY = "sandbox-agent-sessions"

    private val log = Logger.getInstance(LegacySessionMigration::class.java)

    /**
     * The session directories of [projectCacheFileName] of every IDE next to [systemDirectory],
     * whether they exist or not, and of that directory itself when it is placed elsewhere.
     */
    fun sources(systemDirectory: File, projectCacheFileName: String): List<File> {
        val siblings = systemDirectory.absoluteFile.parentFile?.listFiles { file -> file.isDirectory }.orEmpty().toList()
        return (siblings + systemDirectory.absoluteFile).distinct()
            .map { File(File(it, LEGACY_SUBDIRECTORY), projectCacheFileName) }
    }

    /**
     * Copies the session files of [sources] into [target], unless that was done before. The marker
     * is only written when every file made it, so a failure is retried on the next run.
     */
    fun migrate(sources: List<File>, target: File) {
        val marker = File(target, MARKER_NAME)
        if (marker.exists()) return
        target.mkdirs()
        var complete = true
        for (source in sources) {
            for (file in source.listFiles().orEmpty()) {
                if (!file.isFile) continue
                try {
                    copy(file, target)
                } catch (e: IOException) {
                    complete = false
                    log.warn("Failed to migrate session file ${file.path}", e)
                }
            }
        }
        if (!complete) return
        try {
            marker.createNewFile()
        } catch (e: IOException) {
            log.warn("Failed to mark the session files as migrated", e)
        }
    }

    /**
     * Only the names [LocalSessionIndex] recognises are copied. When two versions of the IDE both
     * hold a session, the most recently written current snapshot stays the current one and the
     * other becomes a backup of it, so neither is lost.
     */
    private fun copy(source: File, target: File) {
        val (_, kind, _) = LocalSessionIndex.parse(source.name) ?: return
        val destination = File(target, source.name)
        when {
            !destination.exists() -> copyAtomically(source, destination)
            sameContent(source, destination) -> Unit
            kind != LocalSessionKind.CURRENT -> log.info("Keeping the migrated ${destination.name}, its copy at ${source.path} differs")
            source.lastModified() > destination.lastModified() -> {
                freeBackupOf(destination, destination, target)?.let {
                    Files.move(destination.toPath(), it.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                copyAtomically(source, destination)
            }
            else -> freeBackupOf(source, destination, target)?.let { copyAtomically(source, it) }
        }
    }

    /**
     * The backup name of [content], a version of the current snapshot [current], dated by when it
     * was written. `null` when that very content was already backed up by an interrupted run.
     */
    private fun freeBackupOf(content: File, current: File, target: File): File? {
        var timestamp = content.lastModified()
        while (true) {
            val backup = File(target, "${current.name}.$timestamp.backup")
            if (!backup.exists()) return backup
            if (sameContent(content, backup)) return null
            timestamp++
        }
    }

    private fun sameContent(first: File, second: File): Boolean = Files.mismatch(first.toPath(), second.toPath()) == -1L

    /** Keeps the modification time, which dates a snapshot without `savedAt`. */
    private fun copyAtomically(source: File, destination: File) {
        val temporary = File(destination.parentFile, "${destination.name}.migrating.tmp")
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
