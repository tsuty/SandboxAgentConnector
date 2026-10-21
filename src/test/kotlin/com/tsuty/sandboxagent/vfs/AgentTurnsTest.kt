package com.tsuty.sandboxagent.vfs

import kotlin.test.Test
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
}
