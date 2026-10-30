package com.tsuty.sandboxagent.session

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/** The value last picked for one selector (model, mode, a `configOptions` entry) of one agent. */
@Tag("selector")
data class LastSelector(
    var agentId: String = "",
    /** Which selector it is, as spelled by `SelectorChange.key`. */
    var key: String = "",
    var value: String = "",
)

/**
 * Project scoped memory of the selector values the user last picked, per agent. A new session of
 * that agent starts with them again (see `SandboxAgentService.applyLastSelectors`) instead of the
 * defaults the agent reports.
 *
 * Only picks of the user are recorded: a mode the agent switches to by itself (`switch_mode`) is
 * the state of that one session, not a preference. A value the agent no longer offers is kept,
 * and simply skipped while it is not offered.
 *
 * It lives in `workspace.xml` like [SessionMetadataStore]: these are habits of this machine, not
 * project settings to be committed.
 */
@State(name = "SandboxAgentLastSelectors", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class LastSelectorsStore : PersistentStateComponent<LastSelectorsStore.State> {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var selectors: MutableList<LastSelector> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** The remembered values of [agentId], by selector key. */
    @Synchronized
    fun lastSelectors(agentId: String): Map<String, String> =
        state.selectors.filter { it.agentId == agentId }.associate { it.key to it.value }

    @Synchronized
    fun remember(agentId: String, key: String, value: String) {
        val existing = state.selectors.firstOrNull { it.agentId == agentId && it.key == key }
        if (existing != null) existing.value = value else state.selectors.add(LastSelector(agentId, key, value))
    }

    companion object {
        fun getInstance(project: Project): LastSelectorsStore = project.getService(LastSelectorsStore::class.java)
    }
}
