package com.tsuty.sandboxagent.service

import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.ToolCallStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a stopped turn marks as cancelled: the calls that never reported an end. */
class UnfinishedToolCallsTest {

    private fun call(id: String, status: String? = null) = ToolCall(toolCallId = id, status = status)

    @Test
    fun `only calls that have not ended are drained`() {
        val calls = UnfinishedToolCalls()
        calls.track(call("pending"))
        calls.track(call("running", ToolCallStatus.IN_PROGRESS))
        calls.track(call("done", ToolCallStatus.PENDING))
        calls.track(call("done", ToolCallStatus.COMPLETED))
        calls.track(call("broken", ToolCallStatus.FAILED))

        assertEquals(listOf("pending", "running"), calls.drain())
        // Drained once: a second Stop has nothing left to mark.
        assertEquals(emptyList(), calls.drain())
    }

    @Test
    fun `an update without a status keeps the call where it was`() {
        val calls = UnfinishedToolCalls()
        calls.track(call("a", ToolCallStatus.COMPLETED))
        // A late content update of a finished call does not bring it back.
        calls.track(call("a"))
        calls.track(call("b", ToolCallStatus.IN_PROGRESS))
        calls.track(call("b"))

        assertEquals(listOf("b"), calls.drain())
    }

    @Test
    fun `clear forgets what an earlier turn left running`() {
        val calls = UnfinishedToolCalls()
        calls.track(call("old", ToolCallStatus.IN_PROGRESS))
        calls.clear()

        assertEquals(emptyList(), calls.drain())
    }
}
