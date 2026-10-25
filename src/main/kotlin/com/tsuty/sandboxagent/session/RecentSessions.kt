package com.tsuty.sandboxagent.session

/**
 * One row of the list a tool window without tabs shows: a session kept locally, of whichever
 * agent, dated by the time this plugin last wrote its snapshot.
 */
data class RecentSession(
    val file: LocalSessionFile,
    val sessionId: String,
    val title: String,
    /** The owner of the snapshot (see [SessionSnapshot.ownerAgentId]). */
    val agentId: String,
    /** The name the snapshot keeps for its agent; `null` for one written before it was kept. */
    val agentName: String?,
    /** [SessionSnapshot.savedAt], or the modification time of a file written before it was kept. */
    val savedAt: Long,
)

/**
 * The recent sessions of the local session directory, built from the local files alone: a tool
 * window without tabs has no agent connected to ask.
 *
 * Only current snapshots are listed; backups and deleted sessions stay with the local session
 * search. Ordering by [SessionSnapshot.savedAt] means reading the files, so only the
 * [CANDIDATES] most recently modified ones are read: a file is written at the time it names, so
 * its modification time is never older than that time, and the newest by it are among them.
 */
object RecentSessions {

    const val LIMIT: Int = 10

    /** Files read to find the [LIMIT] newest; the slack absorbs files copied or touched since. */
    const val CANDIDATES: Int = 2 * LIMIT

    fun load(
        index: LocalSessionIndex,
        read: (LocalSessionFile) -> SessionSnapshot?,
        legacyAgentId: String,
        renameOverride: (String) -> String? = { null },
        lastModified: (LocalSessionFile) -> Long = { it.file.lastModified() },
        limit: Int = LIMIT,
    ): List<RecentSession> =
        index.bySanitizedId.values
            .mapNotNull { it.current }
            .map { it to lastModified(it) }
            .sortedByDescending { it.second }
            .take(maxOf(limit, CANDIDATES))
            .mapNotNull { (file, modified) ->
                val snapshot = read(file)?.takeIf { it.isRestorable } ?: return@mapNotNull null
                RecentSession(
                    file = file,
                    sessionId = snapshot.sessionId,
                    title = renameOverride(snapshot.sessionId)
                        ?: snapshot.title?.takeIf { it.isNotBlank() }
                        ?: snapshot.sessionId,
                    agentId = snapshot.ownerAgentId(legacyAgentId),
                    agentName = snapshot.agentName,
                    savedAt = snapshot.savedAt ?: modified,
                )
            }
            .sortedByDescending { it.savedAt }
            .take(limit)
}
