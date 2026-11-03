package com.tsuty.sandboxagent.service

import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.ToolCallStatus

/**
 * The tool calls of the running turn that have not reported an end yet.
 *
 * ACP asks a client to mark them as cancelled as soon as it sends `session/cancel`, rather than
 * leaving them `pending` / `running` until the agent reports them again, which it may never do.
 * An update only carries what changed, so the status is merged: an update without one keeps the
 * call where it was.
 *
 * Fed from the transport thread and drained from a coroutine, hence the locking.
 */
internal class UnfinishedToolCalls {
    private val statuses = LinkedHashMap<String, String>()

    /** Every call of the turn, the ended ones too: a late update without a status must not revive them. */
    @Synchronized
    fun track(toolCall: ToolCall) {
        statuses[toolCall.toolCallId] = toolCall.status ?: statuses[toolCall.toolCallId] ?: ToolCallStatus.PENDING
    }

    /** The ids that have not ended, in the order they were first reported; forgets every call. */
    @Synchronized
    fun drain(): List<String> = statuses.filterValues { it !in ENDED }.keys.toList().also { statuses.clear() }

    @Synchronized
    fun clear() = statuses.clear()

    private companion object {
        val ENDED = setOf(ToolCallStatus.COMPLETED, ToolCallStatus.FAILED, ToolCallStatus.CANCELLED)
    }
}
