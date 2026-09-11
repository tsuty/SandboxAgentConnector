package com.tsuty.remoteagent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.tsuty.remoteagent.service.RemoteAgentService
import java.util.concurrent.atomic.AtomicInteger

/**
 * One active session tab (Ph2 §4): its own, always-live [service] instance (and thus its own
 * sandbox process, Ph2 §2) and the [panel] that renders it. [id] is a stable identity for the tab
 * itself; it is unrelated to the *displayed* number (see [TabsController.numberOf]), which is
 * always the tab's left-to-right position instead.
 */
class RemoteAgentTab<S : Disposable, P : Disposable> internal constructor(
    val id: Int,
    val service: S,
    val panel: P,
)

/**
 * Non-UI logic of the active session tabs (Ph2 §4): tab creation/close order, the 0-9 numbering
 * (always the tab's left-to-right position, reassigned both on close and on reorder — see
 * [numberOf]) and the upper bound of concurrent tabs, which doubles as the sandbox process limit
 * of Ph2 §2 since every tab keeps its own live [RemoteAgentService] and sandbox process.
 *
 * Generic in the service/panel types (rather than hard-coded to [RemoteAgentService] /
 * `RemoteAgentMainPanel`) purely so this can be unit tested with plain fake [Disposable]s instead
 * of the real, project-service-dependent classes; [RemoteAgentTabsPanel] is the only production
 * caller and always instantiates it for the real pair, closing [serviceFactory] / [panelFactory]
 * over its own project instead of this controller holding one itself.
 */
class TabsController<S : Disposable, P : Disposable>(
    private val maxTabs: Int = MAX_TABS,
    private val serviceFactory: () -> S,
    private val panelFactory: (S) -> P,
) : Disposable {

    private val idSeq = AtomicInteger(0)
    private val _tabs = mutableListOf<RemoteAgentTab<S, P>>()

    /** Tabs in display order, left to right. */
    val tabs: List<RemoteAgentTab<S, P>> get() = _tabs.toList()

    val size: Int get() = _tabs.size

    /** `false` once [maxTabs] tabs are open; the "+" button is disabled instead of it being called. */
    val canAddTab: Boolean get() = _tabs.size < maxTabs

    /**
     * Number shown on [tab] and used as its shortcut (Ph2 §4): its left-to-right position, 0-9.
     * `null` for a tab this controller does not hold, or past the 0-9 range (not expected while
     * [maxTabs] stays at 10 or less).
     */
    fun numberOf(tab: RemoteAgentTab<S, P>): Int? {
        val index = _tabs.indexOf(tab)
        return index.takeIf { it in 0..9 }
    }

    /** The tab currently showing [numberOf] `== number`, if any. Used to resolve a shortcut key. */
    fun tabAt(number: Int): RemoteAgentTab<S, P>? = _tabs.getOrNull(number)

    /**
     * Creates a fresh (pristine) tab and connects it. Returns `null` once [canAddTab] is `false`;
     * the caller is expected to have disabled the "+" button in that case already, so this is a
     * defensive fallback rather than the primary way the limit is enforced.
     */
    fun addTab(): RemoteAgentTab<S, P>? {
        if (!canAddTab) return null
        val service = serviceFactory()
        val panel = panelFactory(service)
        val tab = RemoteAgentTab(idSeq.incrementAndGet(), service, panel)
        _tabs += tab
        return tab
    }

    /**
     * Closes [tab]: its sandbox process is torn down and both lock tiers are released right away
     * (see [RemoteAgentService.dispose] → `closeSession()` → `releaseAllLocks()`), but the
     * session itself is *not* cancelled — its metadata, snapshot and transcript survive in
     * [com.tsuty.remoteagent.session.SessionMetadataStore] / `SessionSnapshotStore`, so it can be
     * reopened from `SessionHistoryPopup` in another tab. A no-op when [tab] is not held here.
     *
     * The panel is disposed first so it stops listening before the service publishes the events
     * of its own teardown (`SessionCleared`, ...), then the service is disposed to actually
     * release the sandbox process and the locks.
     */
    fun closeTab(tab: RemoteAgentTab<S, P>) {
        if (!_tabs.remove(tab)) return
        Disposer.dispose(tab.panel)
        Disposer.dispose(tab.service)
    }

    /**
     * Moves the tab at [from] to [to], renumbering every tab in between (Ph2 §4: renumbering
     * happens both on close and on reorder). A no-op for an out-of-range or identity move.
     */
    fun moveTab(from: Int, to: Int) {
        if (from !in _tabs.indices || to !in _tabs.indices || from == to) return
        val tab = _tabs.removeAt(from)
        _tabs.add(to, tab)
    }

    /** Closes every open tab, e.g. when the tool window content itself is disposed. */
    override fun dispose() {
        _tabs.toList().forEach { closeTab(it) }
    }

    companion object {
        /** Ph2 §2/§4: same limit for the concurrent sandbox processes and for the tab count. */
        const val MAX_TABS = 10
    }
}
