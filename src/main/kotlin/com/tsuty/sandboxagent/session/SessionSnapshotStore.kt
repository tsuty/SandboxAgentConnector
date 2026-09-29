package com.tsuty.sandboxagent.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCachePath
import com.tsuty.sandboxagent.acp.PlanEntry
import com.tsuty.sandboxagent.acp.PromptAttachment
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.promptTextWithoutAttachments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Schema version written by this plugin version; see [SessionSnapshotMigrators]. */
const val SESSION_SNAPSHOT_SCHEMA_VERSION: Int = 1

/**
 * One rendered block of a transcript. This is the display state, not the ACP wire format: chunks
 * are already merged into whole messages, and a tool call is stored in its final state only.
 */
@Serializable
sealed interface TranscriptEntry

/**
 * A prompt as it was sent, including the chips of the files / selections attached to it: the
 * attachment is part of what the user asked, so it is stored and redrawn with the prompt (see
 * [PromptAttachment], which is why only the reference is kept, never the attached content).
 */
@Serializable
@SerialName("user_message")
data class UserMessageEntry(
    val text: String,
    val attachments: List<PromptAttachment> = emptyList(),
) : TranscriptEntry

@Serializable
@SerialName("agent_message")
data class AgentMessageEntry(val text: String) : TranscriptEntry

@Serializable
@SerialName("thought")
data class ThoughtEntry(val text: String) : TranscriptEntry

@Serializable
@SerialName("tool_call")
data class ToolCallEntry(val toolCall: ToolCall) : TranscriptEntry

@Serializable
@SerialName("plan")
data class PlanSnapshotEntry(val entries: List<PlanEntry> = emptyList()) : TranscriptEntry

/** Side information of the transcript: `Turn finished`, errors, session notices, ... */
@Serializable
@SerialName("notice")
data class NoticeEntry(val text: String, val error: Boolean = false) : TranscriptEntry

/**
 * Marks the end of a turn that has no [NoticeEntry] of its own (a replayed turn has no stop
 * reason to show, see [SessionTranscriptRecorder.record]'s `ReplayFinished` branch). Without this
 * marker a restored snapshot has no record at all of where a replayed turn ended, and every
 * `You`/`Agent` block it contains renders back to back as if it were a single turn.
 */
@Serializable
@SerialName("turn_end")
data object TurnEndEntry : TranscriptEntry

/**
 * Locally stored display state of one session.
 *
 * [savedAt] is when the IDE wrote the file, [agentUpdatedAt] is the last update the agent reported
 * for the session at that moment. Neither decides whether the snapshot is shown (see
 * [isRestorable]): the agent moves its update time on a mere `session/load` / `session/resume`,
 * so comparing it would replay sessions that did not change at all.
 */
@Serializable
data class SessionSnapshot(
    val schemaVersion: Int = SESSION_SNAPSHOT_SCHEMA_VERSION,
    val sessionId: String,
    val savedAt: Long = 0,
    val agentUpdatedAt: Long? = null,
    val title: String? = null,
    val entries: List<TranscriptEntry> = emptyList(),
) {
    /**
     * `true` when the snapshot is shown instead of replaying the history: whenever it holds
     * anything. The local record always wins over the agent's, which only a replay of a session
     * without local history ever uses.
     */
    val isRestorable: Boolean get() = entries.isNotEmpty()
}

/**
 * Per version upgrade of a raw snapshot document.
 *
 * A version without a migrator is treated as "no local history" (see [SessionSnapshotStore.load]),
 * so a schema change only has to add the entry that upgrades the previous version.
 */
object SessionSnapshotMigrators {
    private val migrators: Map<Int, (JsonObject) -> JsonObject> = emptyMap()

    /** Upgrades [document] to [SESSION_SNAPSHOT_SCHEMA_VERSION], or `null` when impossible. */
    fun migrate(document: JsonObject, fromVersion: Int): JsonObject? {
        var current = document
        var version = fromVersion
        while (version != SESSION_SNAPSHOT_SCHEMA_VERSION) {
            val migrator = migrators[version] ?: return null
            current = migrator(current)
            version = current[SCHEMA_VERSION_FIELD]?.jsonPrimitive?.int ?: return null
        }
        return current
    }

    const val SCHEMA_VERSION_FIELD: String = "schemaVersion"
}

/**
 * Stores the rendered transcript of each session as a JSON file, so reopening a session shows its
 * history instantly instead of waiting for (or living without) an agent side replay.
 *
 * Sessions belong to one project, so the files live in the per project cache directory of the IDE
 * ([directoryFor]), one per `sessionId`: a transcript is a generated artifact that must not end up
 * in the VCS, and it must not be shared between projects either. Writes go through a temporary
 * file and an atomic move, so a crash or a disconnect in the middle of a save never leaves a
 * truncated snapshot behind.
 */
class SessionSnapshotStore(
    private val directory: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val log = Logger.getInstance(SessionSnapshotStore::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
        classDiscriminator = "entry"
    }

    /** Writes the snapshot of [sessionId]; failures are logged and never propagated to the caller. */
    @Synchronized
    fun save(
        sessionId: String,
        entries: List<TranscriptEntry>,
        agentUpdatedAt: Long? = null,
        title: String? = null,
    ) {
        val snapshot = SessionSnapshot(
            sessionId = sessionId,
            savedAt = clock(),
            agentUpdatedAt = agentUpdatedAt,
            title = title,
            entries = entries,
        )
        try {
            write(snapshot)
        } catch (e: Exception) {
            log.warn("Failed to save the session snapshot of $sessionId", e)
        }
    }

    private fun write(snapshot: SessionSnapshot) {
        directory.mkdirs()
        val target = fileOf(snapshot.sessionId).toPath()
        val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        Files.write(
            temporary,
            json.encodeToString(SessionSnapshot.serializer(), snapshot).toByteArray(StandardCharsets.UTF_8),
        )
        moveAtomically(temporary, target)
    }

    /**
     * Reads the snapshot of [sessionId], or `null` when there is none, when the file is corrupt or
     * when its schema version cannot be migrated to [SESSION_SNAPSHOT_SCHEMA_VERSION].
     */
    @Synchronized
    fun load(sessionId: String): SessionSnapshot? = read(fileOf(sessionId))

    /**
     * Reads any snapshot file of the directory — a current snapshot, a backup or a deleted one —
     * with the same migration and clean up as [load]; `null` when it is missing or corrupt.
     */
    @Synchronized
    fun loadFile(file: File): SessionSnapshot? = read(file)

    private fun read(file: File): SessionSnapshot? {
        if (!file.isFile) return null
        return try {
            val document = json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)) as? JsonObject ?: return null
            val version = document[SessionSnapshotMigrators.SCHEMA_VERSION_FIELD]?.jsonPrimitive?.int ?: return null
            val migrated = SessionSnapshotMigrators.migrate(document, version)
            if (migrated == null) {
                log.info("Dropping the snapshot ${file.name}: schema version $version cannot be migrated")
                return null
            }
            val snapshot = json.decodeFromJsonElement(SessionSnapshot.serializer(), migrated)
            snapshot.copy(entries = withoutPromptAttachments(snapshot.entries))
        } catch (e: Exception) {
            log.warn("Failed to read the session snapshot ${file.name}", e)
            null
        }
    }

    /**
     * Drops the attachment markup out of the recorded prompts. Snapshots written before prompts
     * were filtered hold one block per attachment (the file mention and the whole file body), and
     * those are cleaned up on read instead of through a schema migration: the entries are display
     * state, so an older file stays readable by every version.
     *
     * The chips of an entry survive this: they are the deliberate record of what was attached,
     * not the replayed markup this drops.
     */
    private fun withoutPromptAttachments(entries: List<TranscriptEntry>): List<TranscriptEntry> =
        entries.mapNotNull { entry ->
            if (entry !is UserMessageEntry) return@mapNotNull entry
            promptTextWithoutAttachments(entry.text)?.let { entry.copy(text = it) }
        }

    /**
     * Raw text of a file of the directory, as the local session search matches it; `null` when it
     * is missing or unreadable. Read under the same lock as the writes, so a file being replaced or
     * moved aside is never read half way.
     */
    @Synchronized
    fun readText(file: File): String? =
        if (!file.isFile) null else runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()

    /**
     * Copies the current snapshot of [sessionId] to `{id}.json.{epochMillis}.backup` before a
     * replayed history replaces it (see [replaceIfChanged]); `null` when there is nothing to
     * protect or the copy failed.
     */
    @Synchronized
    fun backup(sessionId: String): File? {
        val source = fileOf(sessionId)
        if (!source.isFile) return null
        return try {
            var timestamp = clock()
            var target = siblingOf(sessionId, timestamp, BACKUP_SUFFIX)
            while (target.exists()) {
                timestamp += 1
                target = siblingOf(sessionId, timestamp, BACKUP_SUFFIX)
            }
            Files.copy(source.toPath(), target.toPath())
            target
        } catch (e: Exception) {
            log.warn("Failed to back up the session snapshot of $sessionId", e)
            null
        }
    }

    /**
     * Settles the history the agent replayed for [sessionId] against the local snapshot.
     *
     * The two are compared by their prompts and agent messages only ([sameTranscript]). When they
     * hold the same conversation the local record wins — it carries what a replay cannot
     * reproduce (stop reasons, error notices, attachment chips, ...) — and nothing is written:
     * `null` is returned. When they differ, the local snapshot is first copied to a backup, then
     * replaced by [replayed], and the new snapshot is returned for the caller to redraw it.
     *
     * An empty [replayed] tells nothing (an agent may reconnect without replaying anything), and a
     * failed backup must not cost the local record: both keep the local snapshot as it is.
     */
    @Synchronized
    fun replaceIfChanged(
        sessionId: String,
        replayed: List<TranscriptEntry>,
        agentUpdatedAt: Long? = null,
        title: String? = null,
    ): SessionSnapshot? {
        val local = read(fileOf(sessionId)) ?: return null
        if (replayableOutline(replayed).isEmpty()) return null
        if (sameTranscript(local.entries, replayed)) return null
        backup(sessionId) ?: return null
        val snapshot = SessionSnapshot(
            sessionId = sessionId,
            savedAt = clock(),
            agentUpdatedAt = agentUpdatedAt ?: local.agentUpdatedAt,
            title = title ?: local.title,
            entries = replayed,
        )
        return try {
            write(snapshot)
            snapshot
        } catch (e: Exception) {
            log.warn("Failed to replace the snapshot of $sessionId with the replayed history", e)
            null
        }
    }

    /**
     * Deletes a session locally without erasing any of its files: the snapshot is renamed to
     * `{id}.json.{epochMillis}.deleted` (the time of the deletion), and each of its backups to
     * `{id}.json.{backupEpochMillis}.deleted` (the time of the backup, which dates its content).
     *
     * Backups are part of the session: left as `.backup`, a deleted session would keep showing up
     * in a search that does not include deleted sessions. The snapshot goes first, and a failure
     * there leaves the backups alone, so the session is never half visible because of this call.
     */
    @Synchronized
    fun moveToDeleted(sessionId: String) {
        val source = fileOf(sessionId)
        if (source.isFile) {
            try {
                moveAtomically(source.toPath(), freeDeletedSibling(sessionId, clock()).toPath())
            } catch (e: Exception) {
                log.warn("Failed to move the session snapshot of $sessionId aside", e)
                return
            }
        }
        LocalSessionIndex.scan(directory).of(sessionId)?.backups?.forEach { backup ->
            try {
                val target = freeDeletedSibling(sessionId, backup.timestamp ?: clock())
                moveAtomically(backup.file.toPath(), target.toPath())
            } catch (e: Exception) {
                log.warn("Failed to move the backup ${backup.file.name} aside", e)
            }
        }
    }

    /** `{id}.json.{timestamp}.deleted`, moved on by a millisecond while that name is taken. */
    private fun freeDeletedSibling(sessionId: String, timestamp: Long): File {
        var current = timestamp
        var target = siblingOf(sessionId, current, DELETED_SUFFIX)
        while (target.exists()) {
            current += 1
            target = siblingOf(sessionId, current, DELETED_SUFFIX)
        }
        return target
    }

    /** Lists the directory once; see [LocalSessionIndex]. */
    @Synchronized
    fun index(): LocalSessionIndex = LocalSessionIndex.scan(directory)

    private fun siblingOf(sessionId: String, timestamp: Long, suffix: String): File =
        File(directory, "${sanitize(sessionId)}.json.$timestamp.$suffix")

    /** `ATOMIC_MOVE` is unsupported on a few file systems, where a plain replace is the best effort. */
    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            log.debug("Atomic move is unsupported here, replacing the snapshot instead", e)
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileOf(sessionId: String): File = File(directory, "${sanitize(sessionId)}.json")

    companion object {
        const val SESSIONS_SUBDIRECTORY: String = "sandbox-agent-sessions"

        private val shared = ConcurrentHashMap<String, SessionSnapshotStore>()

        /**
         * The one store of the snapshot directory of [project]. Every tab, the session list and
         * the local session search go through it: its methods are synchronized on the instance,
         * so a second store on the same directory would read and move files without that lock.
         */
        fun forProject(project: Project): SessionSnapshotStore {
            val directory = directoryFor(project).absoluteFile
            return shared.computeIfAbsent(directory.path) { SessionSnapshotStore(directory) }
        }

        private const val BACKUP_SUFFIX = "backup"
        private const val DELETED_SUFFIX = "deleted"

        private val ILLEGAL_FILENAME_CHARS = Regex("[^A-Za-z0-9_.-]")

        /** File name stem of [sessionId]; also the key of [LocalSessionIndex]. */
        internal fun sanitize(sessionId: String): String = sessionId.replace(ILLEGAL_FILENAME_CHARS, "_")

        /**
         * Same conversation as far as a replay can reproduce it: the [replayableOutline]s of both
         * transcripts are equal, entry by entry.
         */
        internal fun sameTranscript(before: List<TranscriptEntry>, after: List<TranscriptEntry>): Boolean =
            replayableOutline(before) == replayableOutline(after)

        /** One step of a [replayableOutline]. */
        internal sealed interface OutlineItem {
            data class User(val text: String) : OutlineItem
            data class Agent(val text: String) : OutlineItem
        }

        /**
         * The part of a transcript a live turn and its replay both produce: the `user_message`
         * and `agent_message` entries only, by their text. Prompts are trimmed; agent messages are
         * compared without whitespace, and consecutive ones are joined, since a thought or a tool
         * call in between splits them differently live and on replay.
         *
         * Everything else is left out. Turn boundaries never line up (a live turn ends with a
         * [NoticeEntry] carrying the stop reason, a replay records a single [TurnEndEntry] after
         * all of its turns), but every turn starts with a prompt, which keeps the number and the
         * order of the turns. Tool calls, thoughts, plans, notices and attachment chips are not
         * reported the same way by a replay, and they are exactly what the local record adds.
         */
        internal fun replayableOutline(entries: List<TranscriptEntry>): List<OutlineItem> {
            val outline = mutableListOf<OutlineItem>()
            for (entry in entries) {
                val item: OutlineItem = when (entry) {
                    is UserMessageEntry -> OutlineItem.User(entry.text.trim())
                    is AgentMessageEntry -> {
                        val text = entry.text.filterNot(Char::isWhitespace)
                        val last = outline.lastOrNull()
                        if (last is OutlineItem.Agent) {
                            outline[outline.lastIndex] = OutlineItem.Agent(last.text + text)
                            continue
                        }
                        OutlineItem.Agent(text)
                    }
                    is ToolCallEntry, is NoticeEntry, TurnEndEntry, is ThoughtEntry, is PlanSnapshotEntry -> continue
                }
                if (item is OutlineItem.Agent && item.text.isEmpty()) continue
                outline += item
            }
            return outline
        }

        /**
         * Cache directory of [project] holding its snapshots. `getProjectCachePath` keys the
         * directory by the project path under the `system` directory of the IDE, which keeps the
         * transcripts out of the project tree and therefore out of the VCS.
         */
        fun directoryFor(project: Project): File =
            project.getProjectCachePath(SESSIONS_SUBDIRECTORY).toFile()
    }
}
