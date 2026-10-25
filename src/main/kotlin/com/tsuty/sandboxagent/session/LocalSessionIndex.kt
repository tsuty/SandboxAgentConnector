package com.tsuty.sandboxagent.session

import java.io.File

/** What a file of the session directory holds (see [LocalSessionIndex.parse]). */
enum class LocalSessionKind { CURRENT, BACKUP, DELETED }

/** How much of a listed session is kept locally, shown as the icon of the session list rows. */
enum class StorageState { LOCAL, LOCAL_WITH_BACKUP, REMOTE_ONLY }

/**
 * One file of the session directory. [timestamp] is the epoch millis encoded in the name of a
 * backup / deleted file, and `null` for the current snapshot.
 */
data class LocalSessionFile(
    val file: File,
    val sanitizedId: String,
    val kind: LocalSessionKind,
    val timestamp: Long?,
    val sizeBytes: Long,
) {
    /** Size rounded up to whole kilobytes, as shown in the backup list. */
    val sizeKb: Long get() = (sizeBytes + 1023) / 1024
}

/** Every local file of one session; [backups] and [deleted] are sorted newest first. */
data class LocalSessionFiles(
    val current: LocalSessionFile?,
    val backups: List<LocalSessionFile>,
    val deleted: List<LocalSessionFile>,
)

/**
 * The content of the session directory, read with a single `list()` call, so the session list can
 * tell for every row whether a snapshot / backups exist without touching the disk per row.
 *
 * Only the three names written by [SessionSnapshotStore] are recognised; everything else (the
 * `.tmp` files of an interrupted save in particular) is ignored.
 */
class LocalSessionIndex(val bySanitizedId: Map<String, LocalSessionFiles>) {

    fun of(sessionId: String): LocalSessionFiles? = bySanitizedId[SessionSnapshotStore.sanitize(sessionId)]

    /**
     * A session without its current snapshot counts as not kept locally, whatever else is left of
     * it: a `.backup` without `{id}.json` shows as [StorageState.REMOTE_ONLY] and offers no
     * backups in the session list ([listedBackups]). Those files stay reachable through the local
     * session search.
     */
    fun storageState(sessionId: String): StorageState {
        val files = of(sessionId) ?: return StorageState.REMOTE_ONLY
        return when {
            files.current == null -> StorageState.REMOTE_ONLY
            files.backups.isNotEmpty() -> StorageState.LOCAL_WITH_BACKUP
            else -> StorageState.LOCAL
        }
    }

    /**
     * Backups the session list offers for [sessionId], newest first: none unless the current
     * snapshot exists, in line with [storageState].
     */
    fun listedBackups(sessionId: String): List<LocalSessionFile> {
        val files = of(sessionId) ?: return emptyList()
        return if (files.current == null) emptyList() else files.backups
    }

    /**
     * `true` when [sessionId] was deleted in the IDE: its files were moved aside and no current
     * snapshot was written since. The session list leaves such a session out even when the agent
     * still lists it, because it lacks `session/delete` or failed to run it.
     */
    fun isDeleted(sessionId: String): Boolean {
        val files = of(sessionId) ?: return false
        return files.current == null && files.deleted.isNotEmpty()
    }

    /**
     * This index without the files of [sessionIds]: the session list of an agent shows a session
     * whose local files belong to another agent as kept nowhere (see `SessionSnapshotStore.ownerOf`).
     */
    fun without(sessionIds: Set<String>): LocalSessionIndex {
        if (sessionIds.isEmpty()) return this
        val excluded = sessionIds.map { SessionSnapshotStore.sanitize(it) }.toSet()
        return LocalSessionIndex(bySanitizedId.filterKeys { it !in excluded })
    }

    /** The files the local session search reads: current snapshots and backups, deleted ones on request. */
    fun searchable(includeDeleted: Boolean): List<LocalSessionFile> =
        bySanitizedId.values.flatMap { files ->
            listOfNotNull(files.current) + files.backups + (if (includeDeleted) files.deleted else emptyList())
        }

    companion object {
        private val CURRENT = Regex("^(.+)\\.json$")
        private val BACKUP = Regex("^(.+)\\.json\\.(\\d+)\\.backup$")
        private val DELETED = Regex("^(.+)\\.json\\.(\\d+)\\.deleted$")

        val EMPTY: LocalSessionIndex = LocalSessionIndex(emptyMap())

        /** Sanitized id, kind and timestamp encoded in [name], or `null` for a foreign file. */
        fun parse(name: String): Triple<String, LocalSessionKind, Long?>? {
            BACKUP.matchEntire(name)?.let { match ->
                val timestamp = match.groupValues[2].toLongOrNull() ?: return null
                return Triple(match.groupValues[1], LocalSessionKind.BACKUP, timestamp)
            }
            DELETED.matchEntire(name)?.let { match ->
                val timestamp = match.groupValues[2].toLongOrNull() ?: return null
                return Triple(match.groupValues[1], LocalSessionKind.DELETED, timestamp)
            }
            CURRENT.matchEntire(name)?.let { match ->
                return Triple(match.groupValues[1], LocalSessionKind.CURRENT, null)
            }
            return null
        }

        fun scan(directory: File): LocalSessionIndex {
            val names = directory.list() ?: return EMPTY
            val files = names.mapNotNull { name ->
                val (id, kind, timestamp) = parse(name) ?: return@mapNotNull null
                val file = File(directory, name)
                LocalSessionFile(file, id, kind, timestamp, file.length())
            }
            return fromFiles(files)
        }

        fun fromFiles(files: List<LocalSessionFile>): LocalSessionIndex {
            val grouped = files.groupBy { it.sanitizedId }.mapValues { (_, group) ->
                LocalSessionFiles(
                    current = group.firstOrNull { it.kind == LocalSessionKind.CURRENT },
                    backups = group.filter { it.kind == LocalSessionKind.BACKUP }.sortedByDescending { it.timestamp },
                    deleted = group.filter { it.kind == LocalSessionKind.DELETED }.sortedByDescending { it.timestamp },
                )
            }
            return LocalSessionIndex(grouped)
        }
    }
}
