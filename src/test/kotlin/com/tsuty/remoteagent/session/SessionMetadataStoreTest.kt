package com.tsuty.remoteagent.session

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rename / touch / delete behaviour and XML persistence of the IDE side session information. */
class SessionMetadataStoreTest {

    @Test
    fun `rename stores a local title override and blank input clears it`() {
        val store = SessionMetadataStore()

        store.rename("sess_1", "  Fix the parser  ")
        assertEquals("Fix the parser", store.renameOverride("sess_1"))

        store.rename("sess_1", "   ")
        assertNull(store.renameOverride("sess_1"))
        // The entry itself survives, so a later `touch` does not lose the rename history.
        assertEquals(listOf("sess_1"), store.all().map { it.sessionId })
    }

    @Test
    fun `touch remembers when a session was last opened`() {
        val store = SessionMetadataStore()

        store.touch("sess_1", now = 1_000L)
        store.touch("sess_1", now = 2_000L)

        assertEquals(2_000L, store.find("sess_1")?.lastOpenedAt)
    }

    @Test
    fun `delete forgets a single session only`() {
        val store = SessionMetadataStore()
        store.rename("sess_1", "First")
        store.rename("sess_2", "Second")

        store.delete("sess_1")

        assertNull(store.find("sess_1"))
        assertEquals("Second", store.renameOverride("sess_2"))
    }

    @Test
    fun `unknown sessions have no metadata`() {
        val store = SessionMetadataStore()

        assertNull(store.find("sess_unknown"))
        assertNull(store.renameOverride("sess_unknown"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `state survives a serialization round trip`() {
        val original = SessionMetadataStore()
        original.rename("sess_1", "Fix the parser")
        original.touch("sess_1", now = 1_700_000_000_000L)

        val element = XmlSerializer.serialize(original.getState())
        val restored = SessionMetadataStore()
        restored.loadState(XmlSerializer.deserialize(element, SessionMetadataStore.State::class.java))

        assertEquals("Fix the parser", restored.renameOverride("sess_1"))
        assertEquals(1_700_000_000_000L, restored.find("sess_1")?.lastOpenedAt)
    }

    @Test
    fun `tryLock grants an unlocked session and blocks a different live owner`() {
        val store = SessionMetadataStore()

        assertTrue(store.tryLock("sess_1", "owner-a", ttlMillis = 30_000, now = 1_000L))
        assertEquals("owner-a", store.find("sess_1")?.lockOwnerId)

        // A second owner cannot steal a lock that is still within its TTL.
        assertTrue(!store.tryLock("sess_1", "owner-b", ttlMillis = 30_000, now = 1_500L))
        assertEquals("owner-a", store.find("sess_1")?.lockOwnerId)
    }

    @Test
    fun `tryLock is idempotent for the owner already holding the lock`() {
        val store = SessionMetadataStore()
        store.tryLock("sess_1", "owner-a", ttlMillis = 30_000, now = 1_000L)

        assertTrue(store.tryLock("sess_1", "owner-a", ttlMillis = 30_000, now = 2_000L))
        assertEquals(2_000L, store.find("sess_1")?.lockedAtMillis)
    }

    @Test
    fun `an expired lock can be taken over by another owner`() {
        val store = SessionMetadataStore()
        store.tryLock("sess_1", "owner-a", ttlMillis = 1_000, now = 1_000L)

        assertTrue(store.tryLock("sess_1", "owner-b", ttlMillis = 1_000, now = 5_000L))
        assertEquals("owner-b", store.find("sess_1")?.lockOwnerId)
    }

    @Test
    fun `heartbeat only refreshes the current owner's lock`() {
        val store = SessionMetadataStore()
        store.tryLock("sess_1", "owner-a", ttlMillis = 30_000, now = 1_000L)

        store.heartbeat("sess_1", "owner-b", now = 2_000L)
        assertEquals(1_000L, store.find("sess_1")?.lockedAtMillis)

        store.heartbeat("sess_1", "owner-a", now = 3_000L)
        assertEquals(3_000L, store.find("sess_1")?.lockedAtMillis)
    }

    @Test
    fun `unlock clears the lock only when the caller owns it`() {
        val store = SessionMetadataStore()
        store.tryLock("sess_1", "owner-a", ttlMillis = 30_000, now = 1_000L)

        store.unlock("sess_1", "owner-b")
        assertEquals("owner-a", store.find("sess_1")?.lockOwnerId)

        store.unlock("sess_1", "owner-a")
        assertNull(store.find("sess_1")?.lockOwnerId)
        assertTrue(!store.isLockedByOther("sess_1", "owner-b", ttlMillis = 30_000, now = 1_500L))
    }

    @Test
    fun `isLockedByOther is false for the owner itself and for unknown sessions`() {
        val store = SessionMetadataStore()
        store.tryLock("sess_1", "owner-a", ttlMillis = 30_000, now = 1_000L)

        assertTrue(!store.isLockedByOther("sess_1", "owner-a", ttlMillis = 30_000, now = 1_500L))
        assertTrue(!store.isLockedByOther("sess_unknown", "owner-b", ttlMillis = 30_000, now = 1_500L))
    }

    @Test
    fun `clearAllLocks wipes every lock regardless of owner or TTL, but keeps the rest of the metadata`() {
        val store = SessionMetadataStore()
        store.rename("sess_1", "Fix the parser")
        store.tryLock("sess_1", "owner-a", ttlMillis = 30_000, now = 1_000L)
        store.tryLock("sess_2", "owner-b", ttlMillis = 30_000, now = 1_000L)

        store.clearAllLocks()

        assertNull(store.find("sess_1")?.lockOwnerId)
        assertNull(store.find("sess_2")?.lockOwnerId)
        assertEquals(0L, store.find("sess_1")?.lockedAtMillis)
        // Only the lock fields are reset; unrelated metadata such as the rename override survives.
        assertEquals("Fix the parser", store.renameOverride("sess_1"))
    }
}
