package com.tsuty.remoteagent.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection

/**
 * IDE side information about one agent session. It is deliberately *not* part of
 * [SessionSnapshot]: a rename or a last opened time must survive a purged, corrupt or
 * unmigratable snapshot.
 */
class SessionMetadata(
    var sessionId: String = "",
    /** Title the user typed in `Rename`; wins over the title the agent reports. */
    var renameOverride: String? = null,
    /** Epoch milliseconds of the last time the session was selected in the IDE. */
    var lastOpenedAt: Long = 0,
    /**
     * Id of the IDE process/window that currently holds the duplicate-open lock of this session
     * (Ph2 §3.3, 2nd tier); `null` when the session is not locked.
     */
    var lockOwnerId: String? = null,
    /** Epoch milliseconds of the last heartbeat of [lockOwnerId]; stale past the TTL (Ph2 §3.4). */
    var lockedAtMillis: Long = 0,
)

/**
 * Project scoped store of the IDE side session information (see [SessionMetadata]).
 *
 * Sessions belong to the sandbox of one project, so the store is persisted per project instead of
 * application wide, and it is kept separate from the transcript snapshots on purpose: the two have
 * different lifecycles.
 *
 * It lives in `workspace.xml` rather than in a shared `.idea` file: renames and last opened times
 * are machine local and must not be committed together with the project settings.
 */
@State(name = "RemoteAgentSessionMetadata", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SessionMetadataStore : PersistentStateComponent<SessionMetadataStore.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var sessions: MutableList<SessionMetadata> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    @Synchronized
    fun all(): List<SessionMetadata> = state.sessions.toList()

    @Synchronized
    fun find(sessionId: String): SessionMetadata? = state.sessions.firstOrNull { it.sessionId == sessionId }

    /** Local title override of [sessionId]; blank input clears the override. */
    @Synchronized
    fun rename(sessionId: String, title: String?) {
        val normalized = title?.trim()?.ifEmpty { null }
        mutate(sessionId) { it.renameOverride = normalized }
    }

    @Synchronized
    fun renameOverride(sessionId: String): String? = find(sessionId)?.renameOverride

    /** Remembers that the session was just selected, which orders the history dropdown. */
    @Synchronized
    fun touch(sessionId: String, now: Long = System.currentTimeMillis()) {
        mutate(sessionId) { it.lastOpenedAt = now }
    }

    @Synchronized
    fun delete(sessionId: String) {
        state.sessions.removeAll { it.sessionId == sessionId }
    }

    /**
     * Claims the duplicate-open lock of [sessionId] for [ownerId]. Fails only when another owner
     * holds a lock that has not gone past [ttlMillis] yet (Ph2 §3.3/§3.4); a lock already held by
     * [ownerId] is refreshed instead of rejected, so re-entering the same session is idempotent.
     */
    @Synchronized
    fun tryLock(sessionId: String, ownerId: String, ttlMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (isLockedByOther(sessionId, ownerId, ttlMillis, now)) return false
        mutate(sessionId) {
            it.lockOwnerId = ownerId
            it.lockedAtMillis = now
        }
        return true
    }

    /** Refreshes the lock timestamp of [sessionId]; a no-op once [ownerId] no longer holds it. */
    @Synchronized
    fun heartbeat(sessionId: String, ownerId: String, now: Long = System.currentTimeMillis()) {
        val existing = find(sessionId) ?: return
        if (existing.lockOwnerId == ownerId) existing.lockedAtMillis = now
    }

    /** Releases the lock of [sessionId], but only when [ownerId] is the one holding it. */
    @Synchronized
    fun unlock(sessionId: String, ownerId: String) {
        val existing = find(sessionId) ?: return
        if (existing.lockOwnerId == ownerId) {
            existing.lockOwnerId = null
            existing.lockedAtMillis = 0
        }
    }

    /** `true` when a live (non-expired) lock of a *different* owner blocks [sessionId]. */
    @Synchronized
    fun isLockedByOther(sessionId: String, ownerId: String, ttlMillis: Long, now: Long = System.currentTimeMillis()): Boolean {
        val existing = find(sessionId) ?: return false
        val owner = existing.lockOwnerId ?: return false
        if (owner == ownerId) return false
        return now - existing.lockedAtMillis < ttlMillis
    }

    /**
     * Wipes every persisted lock, regardless of owner or TTL.
     *
     * Called once per IDE startup (see `RemoteAgentStartupActivity`): we trust that the same
     * sandbox is never driven from two different IDE processes at once (good faith, not
     * multi-process safety), so a lock that survived to the next launch can only be stale &mdash;
     * left behind by a crash or an IDE exit that raced the save (the very bug
     * [SessionLockRegistry.releaseAllBeforeSave] already guards against on a clean close). There is
     * therefore no need to inspect [SessionMetadata.lockOwnerId] or [SessionMetadata.lockedAtMillis]
     * here; every one of them is cleared unconditionally.
     */
    @Synchronized
    fun clearAllLocks() {
        state.sessions.forEach {
            it.lockOwnerId = null
            it.lockedAtMillis = 0
        }
    }

    private fun mutate(sessionId: String, action: (SessionMetadata) -> Unit) {
        val existing = state.sessions.firstOrNull { it.sessionId == sessionId }
        if (existing != null) {
            action(existing)
            return
        }
        val created = SessionMetadata(sessionId)
        action(created)
        state.sessions += created
    }

    companion object {
        fun getInstance(project: Project): SessionMetadataStore =
            project.getService(SessionMetadataStore::class.java)
    }
}
