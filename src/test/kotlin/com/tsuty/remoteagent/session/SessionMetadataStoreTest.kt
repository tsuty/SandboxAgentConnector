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
}
