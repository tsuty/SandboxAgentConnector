package com.tsuty.remoteagent.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The 1st tier of the duplicate-open lock (Ph2 §3.3), now shared by every
 * [com.tsuty.remoteagent.service.RemoteAgentService] instance (tab) of a project. `tabId` tells
 * two tabs of the very same window apart; [SessionLockRegistry.ownerId] is what the 2nd tier
 * ([SessionMetadataStore]) sees as a single owner for every tab of this window.
 */
class SessionLockRegistryTest {

    @Test
    fun `tryAcquire grants an unlocked session`() {
        val registry = SessionLockRegistry()

        val local = registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L)

        assertNotNull(local)
        assertEquals("tab-a", local.tabId)
    }

    @Test
    fun `a live lock of a different tab blocks a second tryAcquire`() {
        val registry = SessionLockRegistry()
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L)

        // Tab B of the very same window/project must be rejected: this is exactly the duplicate
        // open the 1st tier exists to catch (Ph2 §3.3), which the shared registry restores across
        // tabs since it is no longer an instance field of a single RemoteAgentService.
        val blocked = registry.tryAcquire("sess_1", tabId = "tab-b", ttlMillis = 30_000, now = 1_500L)

        assertNull(blocked)
    }

    @Test
    fun `re-entry by the same tab always succeeds`() {
        val registry = SessionLockRegistry()
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L)

        val reacquired = registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_500L)

        assertNotNull(reacquired)
        assertEquals("tab-a", reacquired.tabId)
    }

    @Test
    fun `an expired lock can be taken over by another tab`() {
        val registry = SessionLockRegistry()
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 1_000, now = 1_000L)

        val takenOver = registry.tryAcquire("sess_1", tabId = "tab-b", ttlMillis = 1_000, now = 5_000L)

        assertNotNull(takenOver)
        assertEquals("tab-b", takenOver.tabId)
    }

    @Test
    fun `rollback removes exactly the granted lock`() {
        val registry = SessionLockRegistry()
        val local = registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L)
        assertNotNull(local)

        registry.rollback("sess_1", local)

        assertNotNull(registry.tryAcquire("sess_1", tabId = "tab-b", ttlMillis = 30_000, now = 1_001L))
    }

    @Test
    fun `release only clears the lock when the caller's tab owns it`() {
        val registry = SessionLockRegistry()
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L)

        registry.release("sess_1", "tab-b")
        assertNull(registry.tryAcquire("sess_1", tabId = "tab-b", ttlMillis = 30_000, now = 1_100L))

        registry.release("sess_1", "tab-a")
        assertNotNull(registry.tryAcquire("sess_1", tabId = "tab-b", ttlMillis = 30_000, now = 1_200L))
    }

    @Test
    fun `release of an unknown session is a no-op`() {
        val registry = SessionLockRegistry()

        registry.release("sess_unknown", "tab-a")

        assertNotNull(registry.tryAcquire("sess_unknown", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L))
    }

    @Test
    fun `heartbeat only refreshes the owning tab's lock`() {
        val registry = SessionLockRegistry()
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 1_000, now = 1_000L)

        // A different tab's heartbeat must not extend a lock it does not own.
        registry.heartbeat("sess_1", "tab-b", now = 1_900L)
        assertNotNull(registry.tryAcquire("sess_1", tabId = "tab-b", ttlMillis = 1_000, now = 2_500L))
    }

    @Test
    fun `heartbeat keeps the owning tab's lock alive past its original TTL window`() {
        val registry = SessionLockRegistry()
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 1_000, now = 1_000L)

        registry.heartbeat("sess_1", "tab-a", now = 1_900L)

        // Without the heartbeat this would already have expired (1_000 + 1_000 < 2_500).
        val blocked = registry.tryAcquire("sess_1", tabId = "tab-b", ttlMillis = 1_000, now = 2_500L)
        assertNull(blocked)
    }

    @Test
    fun `heartbeat of an unknown session is a no-op`() {
        val registry = SessionLockRegistry()

        registry.heartbeat("sess_unknown", "tab-a", now = 1_000L)

        assertNotNull(registry.tryAcquire("sess_unknown", tabId = "tab-b", ttlMillis = 30_000, now = 1_000L))
    }

    @Test
    fun `each registry has its own owner id for the 2nd tier`() {
        val a = SessionLockRegistry()
        val b = SessionLockRegistry()

        assert(a.ownerId != b.ownerId)
    }

    @Test
    fun `releaseAllBeforeSave unlocks every held session in the 2nd tier and clears the 1st tier`() {
        val registry = SessionLockRegistry()
        val metadataStore = SessionMetadataStore()
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L)
        registry.tryAcquire("sess_2", tabId = "tab-b", ttlMillis = 30_000, now = 1_000L)
        metadataStore.tryLock("sess_1", registry.ownerId, ttlMillis = 30_000, now = 1_000L)
        metadataStore.tryLock("sess_2", registry.ownerId, ttlMillis = 30_000, now = 1_000L)

        registry.releaseAllBeforeSave(metadataStore)

        // The bug this guards against: without this call the 2nd tier would still show these
        // sessions as locked by this (about to disappear) owner once workspace.xml is written.
        assertNull(metadataStore.find("sess_1")?.lockOwnerId)
        assertNull(metadataStore.find("sess_2")?.lockOwnerId)
        // The 1st tier is cleared too, so a stale entry cannot block a fresh tab of a still-open
        // project (e.g. the listener firing without the project actually going away).
        assertNotNull(registry.tryAcquire("sess_1", tabId = "tab-c", ttlMillis = 30_000, now = 1_100L))
    }

    @Test
    fun `releaseAllBeforeSave does not touch a lock now held by another owner`() {
        val registry = SessionLockRegistry()
        val metadataStore = SessionMetadataStore()
        // A race the 2nd tier's own ownership check already guards against (SessionMetadataStore.unlock):
        // this window still thinks it holds the 1st tier lock, but the 2nd tier was somehow taken
        // over by another owner in the meantime.
        registry.tryAcquire("sess_1", tabId = "tab-a", ttlMillis = 30_000, now = 1_000L)
        metadataStore.tryLock("sess_1", "other-owner", ttlMillis = 30_000, now = 1_000L)

        registry.releaseAllBeforeSave(metadataStore)

        assertEquals("other-owner", metadataStore.find("sess_1")?.lockOwnerId)
    }
}
