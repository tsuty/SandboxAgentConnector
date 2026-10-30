package com.tsuty.sandboxagent.session

import com.intellij.util.xmlb.XmlSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/** The selector values last picked per agent, and their XML persistence. */
class LastSelectorsStoreTest {

    @Test
    fun `a value is remembered per agent and a later pick replaces it`() {
        val store = LastSelectorsStore()

        store.remember("claude-acp", "config:mode", "plan")
        store.remember("claude-acp", "config:mode", "acceptEdits")
        store.remember("gemini", "model", "gemini-2.5-pro")

        assertEquals(mapOf("config:mode" to "acceptEdits"), store.lastSelectors("claude-acp"))
        assertEquals(mapOf("model" to "gemini-2.5-pro"), store.lastSelectors("gemini"))
        assertEquals(emptyMap(), store.lastSelectors("codex"))
    }

    @Test
    fun `the remembered values survive a round trip through the XML state`() {
        val original = LastSelectorsStore()
        original.remember("claude-acp", "config:model", "opus")
        original.remember("claude-acp", "config:effort", "high")

        val element = XmlSerializer.serialize(original.getState())
        val restored = LastSelectorsStore()
        restored.loadState(XmlSerializer.deserialize(element, LastSelectorsStore.State::class.java))

        assertEquals(mapOf("config:model" to "opus", "config:effort" to "high"), restored.lastSelectors("claude-acp"))
    }
}
