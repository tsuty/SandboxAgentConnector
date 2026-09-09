package com.tsuty.remoteagent.session

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectCachePath
import com.tsuty.remoteagent.acp.PlanEntry
import com.tsuty.remoteagent.acp.PromptAttachment
import com.tsuty.remoteagent.acp.ToolCall
import com.tsuty.remoteagent.acp.promptTextWithoutAttachments
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
 * for the session at that moment — the pair is what [isFreshFor] compares to decide whether the
 * snapshot may be shown instead of replaying the whole history.
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
     * `true` when the snapshot still covers everything the agent knows about the session.
     *
     * An agent that does not report a last update time ([agentLastUpdated] `null`) cannot be
     * compared against, and the snapshot is then accepted: showing a possibly slightly stale
     * transcript is preferable to replaying every session on every switch.
     */
    fun isFreshFor(agentLastUpdated: Long?): Boolean {
        if (entries.isEmpty()) return false
        if (agentLastUpdated == null) return true
        val known = agentUpdatedAt ?: return false
        return known >= agentLastUpdated
    }
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
            directory.mkdirs()
            val target = fileOf(sessionId).toPath()
            val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
            Files.write(
                temporary,
                json.encodeToString(SessionSnapshot.serializer(), snapshot).toByteArray(StandardCharsets.UTF_8),
            )
            moveAtomically(temporary, target)
        } catch (e: Exception) {
            log.warn("Failed to save the session snapshot of $sessionId", e)
        }
    }

    /**
     * Reads the snapshot of [sessionId], or `null` when there is none, when the file is corrupt or
     * when its schema version cannot be migrated to [SESSION_SNAPSHOT_SCHEMA_VERSION].
     */
    @Synchronized
    fun load(sessionId: String): SessionSnapshot? {
        val file = fileOf(sessionId)
        if (!file.isFile) return null
        return try {
            val document = json.parseToJsonElement(file.readText(StandardCharsets.UTF_8)) as? JsonObject ?: return null
            val version = document[SessionSnapshotMigrators.SCHEMA_VERSION_FIELD]?.jsonPrimitive?.int ?: return null
            val migrated = SessionSnapshotMigrators.migrate(document, version)
            if (migrated == null) {
                log.info("Dropping the snapshot of $sessionId: schema version $version cannot be migrated")
                return null
            }
            val snapshot = json.decodeFromJsonElement(SessionSnapshot.serializer(), migrated)
            snapshot.copy(entries = withoutPromptAttachments(snapshot.entries))
        } catch (e: Exception) {
            log.warn("Failed to read the session snapshot of $sessionId", e)
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

    @Synchronized
    fun delete(sessionId: String) {
        val file = fileOf(sessionId)
        if (file.exists() && !file.delete()) {
            log.warn("Failed to delete the session snapshot of $sessionId")
        }
    }

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

    private fun sanitize(sessionId: String): String = sessionId.replace(ILLEGAL_FILENAME_CHARS, "_")

    companion object {
        const val SESSIONS_SUBDIRECTORY: String = "remote-agent-sessions"

        private val ILLEGAL_FILENAME_CHARS = Regex("[^A-Za-z0-9_.-]")

        /**
         * Cache directory of [project] holding its snapshots. `getProjectCachePath` keys the
         * directory by the project path under the `system` directory of the IDE, which keeps the
         * transcripts out of the project tree and therefore out of the VCS.
         */
        fun directoryFor(project: Project): File =
            project.getProjectCachePath(SESSIONS_SUBDIRECTORY).toFile()
    }
}
