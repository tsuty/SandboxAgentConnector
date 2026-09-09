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
