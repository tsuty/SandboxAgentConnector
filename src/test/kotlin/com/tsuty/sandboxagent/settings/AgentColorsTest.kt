package com.tsuty.sandboxagent.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color

class AgentColorsTest {

    @Test
    fun `the known agents carry a default color`() {
        assertEquals("#CC7C5E", AgentColors.resolve("claude-acp", emptyMap()))
        assertEquals("#74D664", AgentColors.resolve("junie", emptyMap()))
        assertEquals("#4245DB", AgentColors.resolve("codex-acp", emptyMap()))
        assertEquals("#4A83F7", AgentColors.resolve("gemini", emptyMap()))
    }

    @Test
    fun `an agent without a default keeps the existing drawing`() {
        assertNull(AgentColors.resolve("some-agent", emptyMap()))
    }

    @Test
    fun `a chosen color wins over the default`() {
        assertEquals("#112233", AgentColors.resolve("junie", mapOf("junie" to "#112233")))
        assertEquals("#ABCDEF", AgentColors.resolve("some-agent", mapOf("some-agent" to "abcdef")))
    }

    @Test
    fun `a stored value that is not a color falls back to the default`() {
        assertEquals("#74D664", AgentColors.resolve("junie", mapOf("junie" to "green")))
        assertNull(AgentColors.resolve("some-agent", mapOf("some-agent" to "#12345")))
    }

    @Test
    fun `colors convert both ways`() {
        assertEquals("#CC7C5E", AgentColors.toHex(Color(0xCC, 0x7C, 0x5E)))
        assertEquals(Color(0x4A83F7), AgentColors.toColor("#4a83f7"))
        assertNull(AgentColors.toColor(null))
    }

    @Test
    fun `the color of an agent of the settings is resolved`() {
        val settings = SandboxAgentSettingsState()
        assertEquals("#4A83F7", settings.agentColorOrNull("gemini"))
        settings.agentColors["gemini"] = "#000000"
        assertEquals("#000000", settings.agentColorOrNull("gemini"))
        assertNull(settings.agentColorOrNull("some-agent"))
    }
}
