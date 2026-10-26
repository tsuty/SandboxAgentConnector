package com.tsuty.sandboxagent.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentListEditsTest {

    private val claude = AcpAgentRegistry.FALLBACK_AGENT
    private val gemini = AcpAgent(id = "gemini", name = "Gemini CLI", packageName = "@google/gemini-cli@1", version = "1", iconUrl = "g.svg")
    private val goose = AcpAgent(id = "goose", name = "goose", packageName = "", launcher = AgentLauncher.BINARY)

    private fun edits(vararg agents: AcpAgent, defaultId: String = agents.first().id) =
        AgentListEdits(agents.map { AgentEntry.of(it) }, defaultId)

    @Test
    fun `an agent that is already enabled cannot be added again`() {
        val edits = edits(claude)

        assertEquals(listOf("gemini"), edits.addable(listOf(claude, gemini)).map { it.id })
        assertEquals(1, edits.add(gemini))
        assertEquals(-1, edits.add(gemini))
        assertEquals(2, edits.size)
    }

    @Test
    fun `the last agent cannot be removed`() {
        val edits = edits(claude, gemini)

        assertTrue(edits.remove(1))
        assertFalse(edits.canRemove)
        assertFalse(edits.remove(0))
        assertEquals(listOf("claude-acp"), edits.entries.map { it.id })
    }

    @Test
    fun `removing the default makes the first agent left the default`() {
        val edits = edits(claude, gemini, goose, defaultId = "claude-acp")

        edits.remove(0)

        assertEquals("gemini", edits.defaultId)
        assertTrue(edits.isDefault(0))
    }

    @Test
    fun `exactly one agent is the default`() {
        val edits = edits(claude, gemini, defaultId = "nope")
        assertEquals("claude-acp", edits.defaultId)

        edits.setDefault(1)

        assertFalse(edits.isDefault(0))
        assertTrue(edits.isDefault(1))
    }

    @Test
    fun `an update keeps the drawing unless the icon file changed`() {
        val edits = AgentListEdits(listOf(AgentEntry.of(gemini, iconSvg = "<svg/>")), "gemini")

        edits.update(0, gemini.copy(version = "2", packageName = "@google/gemini-cli@2"))
        assertEquals("<svg/>", edits.entries[0].iconSvg)
        assertEquals("2", edits.entries[0].version)

        edits.update(0, gemini.copy(iconUrl = "g2.svg"))
        assertEquals("", edits.entries[0].iconSvg)

        // Another agent is never written into the row.
        edits.update(0, claude)
        assertEquals("gemini", edits.entries[0].id)
    }

    @Test
    fun `a binary agent without its command cannot be applied`() {
        val edits = edits(claude, goose)

        assertNotNull(edits.commandError())
        edits.update(1, goose.copy(customCommand = "/opt/goose acp"))
        assertNull(edits.commandError())
    }

    @Test
    fun `applying keeps the stored drawings and reports what changed`() {
        val settings = SandboxAgentSettingsState()
        settings.updateAgents(listOf(AgentEntry.of(claude), AgentEntry.of(gemini, iconSvg = "<svg/>")), "claude-acp")
        val edits = AgentListEdits(settings.enabledAgents().map { it.copy(iconSvg = "") }, settings.defaultAgent().id)
        assertFalse(edits.differsFrom(settings))

        edits.setDefault(1)
        edits.add(goose)
        assertTrue(edits.differsFrom(settings))
        edits.applyTo(settings)

        assertEquals(listOf("claude-acp", "gemini", "goose"), settings.agents.map { it.id })
        assertEquals("gemini", settings.defaultAgent().id)
        assertEquals("<svg/>", settings.agentById("gemini")?.iconSvg)
        assertFalse(edits.differsFrom(settings))
    }

    @Test
    fun `every agent keeps a color of its own`() {
        val edits = AgentListEdits(listOf(AgentEntry.of(claude), AgentEntry.of(gemini)), "claude-acp", mapOf("gemini" to "#000000"))

        assertEquals("#CC7C5E", edits.colorOf("claude-acp"))
        assertEquals("#000000", edits.colorOf("gemini"))

        edits.setColor("claude-acp", "#123abc")
        edits.setColor("gemini", null)
        assertEquals("#123ABC", edits.colorOf("claude-acp"))
        assertEquals("#4A83F7", edits.colorOf("gemini"))
        // A color equal to the default is not stored.
        edits.setColor("claude-acp", "#cc7c5e")
        assertEquals(emptyMap(), edits.colors)
    }

    @Test
    fun `the color of a removed agent is kept and applied`() {
        val settings = SandboxAgentSettingsState()
        settings.updateAgents(listOf(AgentEntry.of(claude), AgentEntry.of(gemini)), "claude-acp")
        val edits = AgentListEdits(settings.enabledAgents(), settings.defaultAgent().id, settings.agentColors)

        edits.setColor("gemini", "#111111")
        assertTrue(edits.differsFrom(settings))
        edits.remove(1)
        edits.applyTo(settings)

        assertEquals(mapOf("gemini" to "#111111"), settings.agentColors)
        assertFalse(edits.differsFrom(settings))
        assertEquals("#111111", AgentListEdits(settings.enabledAgents(), "", settings.agentColors).colorOf("gemini"))
    }
}
