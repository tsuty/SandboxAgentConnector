package com.tsuty.remoteagent.session

import com.intellij.openapi.project.Project
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Project scoped 1st tier of the duplicate-open lock (Ph2 §3.3).
 *
 * Ph2 introduces one [com.tsuty.remoteagent.service.RemoteAgentService] instance per tab
 * (see `RemoteAgentTabsPanel`), so the 1st tier can no longer live as an instance field of the
 * service the way it used to: two tabs of the very same project are two different instances, and
 * without a shared registry neither would notice the other holding the very same session. This
 * registry is the shared state every instance (tab) of the project consults instead, so the 1st
 * tier keeps catching a same-window duplicate open (Ph2 §3.3) even though it now spans tabs.
 *
 * A tab is identified by the `tabId` it passes in (a per-instance id it generates itself), which
 * is *not* the same as [ownerId]: [ownerId] identifies this whole IDE window/process for the 2nd
 * tier ([SessionMetadataStore], which only cares whether some *other* window holds the session),
 * while `tabId` tells two tabs of the very same window apart so the 1st tier can still reject a
 * duplicate open between them.
 */
class SessionLockRegistry {

    /**
     * Identifies this project's IDE process/window as a lock owner for the 2nd tier (Ph2 §3.3).
     * Shared by every tab of the project on purpose: from another window's point of view, all
     * tabs of this window are the same owner. Random per registry (i.e. per project): a restarted
     * IDE process must never look like the still-live owner of a lock it left behind.
     */
    val ownerId: String = UUID.randomUUID().toString()

    /** One entry per session currently locked by some tab of this project. */
    data class LocalLock(val tabId: String, val lockedAtMillis: Long)

    private val locks = ConcurrentHashMap<String, LocalLock>()

    /**
     * Claims the local tier of the lock of [sessionId] for [tabId]. Returns the granted
     * [LocalLock] on success, or `null` when a different, still live tab already holds it
     * (Ph2 §3.4 defines [ttlMillis]). Re-entry by the very same [tabId] always succeeds.
     */
    fun tryAcquire(sessionId: String, tabId: String, ttlMillis: Long, now: Long = System.currentTimeMillis()): LocalLock? {
        val local = locks.compute(sessionId) { _, existing ->
            if (existing != null && existing.tabId != tabId && now - existing.lockedAtMillis < ttlMillis) {
                existing
            } else {
                LocalLock(tabId, now)
            }
        }
        return local?.takeIf { it.tabId == tabId }
    }

    /** Rolls a granted [tryAcquire] back, e.g. when the 2nd tier (persisted) lock failed right after. */
    fun rollback(sessionId: String, local: LocalLock) {
        locks.remove(sessionId, local)
    }

    /** Releases the lock of [sessionId], but only when [tabId] is the one holding it. */
    fun release(sessionId: String, tabId: String) {
        locks.computeIfPresent(sessionId) { _, existing -> if (existing.tabId == tabId) null else existing }
    }

    /** Refreshes the timestamp of [sessionId]'s lock while [tabId] still holds it. */
    fun heartbeat(sessionId: String, tabId: String, now: Long = System.currentTimeMillis()) {
        locks.computeIfPresent(sessionId) { _, existing ->
            if (existing.tabId == tabId) existing.copy(lockedAtMillis = now) else existing
        }
    }

    /**
     * Releases the 2nd tier ([metadataStore]) of every lock this window's tabs still hold, right
     * before the project is closed and its state is saved to `workspace.xml`.
     *
     * Bug fix: [com.tsuty.remoteagent.service.RemoteAgentService.dispose] already releases both
     * tiers on its own, but on project close (and on IDE exit, which closes every open project)
     * the platform writes `workspace.xml` *before* disposing project services, so a lock released
     * only in `dispose()` is persisted as still held — the very next IDE start then sees the
     * session as locked by an owner ([ownerId]) that no longer exists. Called from
     * `RemoteAgentProjectCloseListener.projectClosingBeforeSave`, which fires early enough to beat
     * that save.
     */
    fun releaseAllBeforeSave(metadataStore: SessionMetadataStore) {
        locks.keys.toList().forEach { sessionId ->
            metadataStore.unlock(sessionId, ownerId)
        }
        locks.clear()
    }

    companion object {
        fun getInstance(project: Project): SessionLockRegistry = project.getService(SessionLockRegistry::class.java)
    }
}
