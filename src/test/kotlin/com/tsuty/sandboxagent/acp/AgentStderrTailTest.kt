package com.tsuty.sandboxagent.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cleanup of the `npx` progress output shown while the agent starts. */
class AgentStderrTailTest {

    @Test
    fun `nothing readable yet`() {
        val tail = AgentStderrTail()
        assertNull(tail.lastLine)
        assertEquals(emptyList(), tail.tail(3))

        // A line without its terminator is held back.
        tail.append("npm warn depre")
        assertNull(tail.lastLine)
    }

    @Test
    fun `chunks are joined and blank lines skipped`() {
        val tail = AgentStderrTail()
        tail.append("npm warn depre")
        tail.append("cated request@2.88.2\n\n  \n")

        assertEquals("npm warn deprecated request@2.88.2", tail.lastLine)
        assertEquals(listOf("npm warn deprecated request@2.88.2"), tail.tail(5))
    }

    @Test
    fun `a progress bar rewriting its line yields the newest state`() {
        val tail = AgentStderrTail()
        tail.append("\u001B[2Kdownloading 10%\rdownloading 70%\r")

        assertEquals("downloading 70%", tail.lastLine)
        assertEquals(listOf("downloading 10%", "downloading 70%"), tail.tail(5))
    }

    @Test
    fun `only the newest lines are kept`() {
        val tail = AgentStderrTail(capacity = 2)
        tail.append("one\ntwo\nthree\n")

        assertEquals(listOf("two", "three"), tail.tail(5))
        assertEquals(listOf("three"), tail.tail(1))
    }

    @Test
    fun `ansi escapes are dropped`() {
        assertEquals("added 1 package", AgentStderrTail.strip("\u001B[32madded 1 package\u001B[39m "))
        assertEquals("plain", AgentStderrTail.strip("plain"))
    }
}
