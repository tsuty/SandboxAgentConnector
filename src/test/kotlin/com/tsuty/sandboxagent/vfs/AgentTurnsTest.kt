package com.tsuty.sandboxagent.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTurnsTest {
    private val tabA = Any()
    private val tabB = Any()

    @Test
    fun `every tab starting a turn is reported as a new start`() {
        val turns = AgentTurns()

        assertTrue(turns.start(tabA))
        assertTrue(turns.start(tabB))
        assertFalse(turns.start(tabA))
    }

    @Test
    fun `the workspace stays locked until the last overlapping turn ends`() {
        val turns = AgentTurns()
        turns.start(tabA)
        turns.start(tabB)

        assertTrue(turns.finish(tabA))
        assertTrue(turns.isAnyRunning)
        assertTrue(turns.finish(tabB))
        assertFalse(turns.isAnyRunning)
    }

    @Test
    fun `ending a tab without a turn leaves the running turn of another tab alone`() {
        val turns = AgentTurns()
        turns.start(tabA)

        assertFalse(turns.finish(tabB))
        assertTrue(turns.isAnyRunning)
    }

    @Test
    fun `the agents running a turn are named once each, in the order they started`() {
        val turns = AgentTurns()
        turns.start(tabA, "Gemini CLI")
        turns.start(tabB, "Claude Agent")
        turns.start(Any(), "Gemini CLI")

        assertEquals(listOf("Gemini CLI", "Claude Agent"), turns.runningAgentNames)
        turns.finish(tabA)
        assertEquals(listOf("Claude Agent", "Gemini CLI"), turns.runningAgentNames)
    }

    @Test
    fun `the read-only message names the running agents`() {
        assertEquals(
            "Claude Agent is running a turn: the workspace is read-only until it finishes.",
            AgentWritingAccessProvider.readOnlyMessage(listOf("Claude Agent")),
        )
        assertEquals(
            "Claude Agent, Gemini CLI are running turns: the workspace is read-only until they finish.",
            AgentWritingAccessProvider.readOnlyMessage(listOf("Claude Agent", "Gemini CLI")),
        )
        assertTrue(AgentWritingAccessProvider.readOnlyMessage(emptyList()).startsWith("The sandbox agent"))
    }
}
