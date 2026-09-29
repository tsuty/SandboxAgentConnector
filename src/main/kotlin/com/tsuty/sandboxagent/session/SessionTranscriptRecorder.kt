package com.tsuty.sandboxagent.session

import com.tsuty.sandboxagent.acp.PromptAttachment
import com.tsuty.sandboxagent.acp.StopReason
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.service.SandboxAgentEvent

/**
 * Folds the event stream of a session into the display state a [SessionSnapshot] stores.
 *
 * The recorder mirrors what `TranscriptPanel` shows, not the ACP wire format: streamed chunks are
 * merged into whole messages, a tool call keeps its latest state only, and the chain of thought of
 * a turn is filed away when the turn ends — exactly like the transcript does.
 */
class SessionTranscriptRecorder {

    private val entries = mutableListOf<TranscriptEntry>()
    private val agentText = StringBuilder()

    /** ACP `messageId` of the streaming agent message; `null` when the agent sends none. */
    private var agentMessageId: String? = null
    private val thought = StringBuilder()

    /** Index of the [ToolCallEntry] of each tool call, so an update refreshes it in place. */
    private val toolCallIndexes = LinkedHashMap<String, Int>()

    private var planIndex: Int? = null

    /**
     * Attachments reported before the prompt they belong to. A replay sends the typed text and
     * every attachment as its own chunk, and an agent is free to send them in either order, so
     * chips arriving first wait here for the [UserMessageEntry] they belong to.
     */
    private val pendingAttachments = mutableListOf<PromptAttachment>()

    /** Display state recorded so far, including the message that is still streaming. */
    @Synchronized
    fun entries(): List<TranscriptEntry> = buildList {
        addAll(entries)
        if (agentText.isNotBlank()) add(AgentMessageEntry(agentText.toString(), agentMessageId))
    }

    @Synchronized
    fun clear() {
        entries.clear()
        agentText.setLength(0)
        agentMessageId = null
        thought.setLength(0)
        toolCallIndexes.clear()
        planIndex = null
        pendingAttachments.clear()
    }

    /** Replaces the recorded state with the entries of a restored snapshot. */
    @Synchronized
    fun restore(snapshot: SessionSnapshot) {
        clear()
        snapshot.entries.forEach { entry ->
            entries += entry
            when (entry) {
                is ToolCallEntry -> toolCallIndexes[entry.toolCall.toolCallId] = entries.lastIndex
                is PlanSnapshotEntry -> planIndex = entries.lastIndex
                else -> Unit
            }
        }
    }

    @Synchronized
    fun record(event: SandboxAgentEvent) {
        when (event) {
            is SandboxAgentEvent.UserPrompt -> {
                endAgentMessage()
                entries += UserMessageEntry(event.text, merged(pendingAttachments, event.attachments))
                pendingAttachments.clear()
            }
            is SandboxAgentEvent.UserPromptAttachments -> attachToPrompt(event.attachments)
            is SandboxAgentEvent.AgentMessageChunk -> appendAgentChunk(event)
            is SandboxAgentEvent.AgentThoughtChunk -> thought.append(event.text)
            is SandboxAgentEvent.ToolCallStarted -> upsertToolCall(event.toolCall)
            is SandboxAgentEvent.ToolCallUpdated -> upsertToolCall(event.toolCall)
            is SandboxAgentEvent.PlanUpdated -> upsertPlan(event)
            is SandboxAgentEvent.TurnFinished -> {
                endAgentMessage()
                if (thought.isNotBlank()) entries += ThoughtEntry(thought.toString().trim())
                thought.setLength(0)
                entries += NoticeEntry(StopReason.presentableText(event.stopReason))
            }
            // A replay has no stop reason to show, but its thought must be filed away all the
            // same, and the turn boundary itself still needs a marker: without one, a snapshot
            // saved after a replay has no record of where this turn ended, and a later restore
            // renders every turn it replayed as if they were all a single one (see `TurnEndEntry`).
            is SandboxAgentEvent.ReplayFinished -> {
                endAgentMessage()
                if (thought.isNotBlank()) entries += ThoughtEntry(thought.toString().trim())
                thought.setLength(0)
                entries += TurnEndEntry
            }
            is SandboxAgentEvent.Error -> {
                endAgentMessage()
                entries += NoticeEntry(event.message, error = true)
            }
            else -> Unit
        }
    }

    /**
     * Adds [attachments] to the prompt they were replayed with, which is the last entry as long as
     * the attachment chunks follow their prompt (see [pendingAttachments] for the other order).
     */
    private fun attachToPrompt(attachments: List<PromptAttachment>) {
        val last = entries.lastOrNull()
        if (agentText.isBlank() && last is UserMessageEntry) {
            entries[entries.lastIndex] = last.copy(attachments = merged(last.attachments, attachments))
        } else {
            pendingAttachments += attachments
        }
    }

    /**
     * Keeps the order the attachments were reported in, without repeating the same chip. The same
     * attachment can be reported twice, once with the snippet it was sent with and once without
     * (see [PromptAttachment.key]), and the one carrying the snippet is the one worth keeping.
     */
    private fun merged(
        first: List<PromptAttachment>,
        second: List<PromptAttachment>,
    ): List<PromptAttachment> {
        val byKey = LinkedHashMap<String, PromptAttachment>()
        (first + second).forEach { attachment ->
            val previous = byKey[attachment.key]
            if (previous == null || previous.content == null) byKey[attachment.key] = attachment
        }
        return byKey.values.toList()
    }

    private fun upsertToolCall(toolCall: ToolCall) {
        endAgentMessage()
        val index = toolCallIndexes[toolCall.toolCallId]
        if (index == null) {
            entries += ToolCallEntry(toolCall)
            toolCallIndexes[toolCall.toolCallId] = entries.lastIndex
            return
        }
        val previous = (entries[index] as ToolCallEntry).toolCall
        entries[index] = ToolCallEntry(previous.mergeWith(toolCall))
    }

    private fun upsertPlan(event: SandboxAgentEvent.PlanUpdated) {
        val index = planIndex
        if (index == null) {
            endAgentMessage()
            entries += PlanSnapshotEntry(event.entries)
            planIndex = entries.lastIndex
        } else {
            entries[index] = PlanSnapshotEntry(event.entries)
        }
    }

    /**
     * Appends a chunk to the streaming agent message; a chunk of another message (another
     * `messageId`) closes the current one first, so an entry never mixes two messages.
     */
    private fun appendAgentChunk(event: SandboxAgentEvent.AgentMessageChunk) {
        if (event.messageId != null && event.messageId != agentMessageId) endAgentMessage()
        if (event.messageId != null) agentMessageId = event.messageId
        agentText.append(event.text)
    }

    /** Closes the streaming agent message so the next chunk starts a new block. */
    private fun endAgentMessage() {
        if (agentText.isNotBlank()) entries += AgentMessageEntry(agentText.toString(), agentMessageId)
        agentText.setLength(0)
        agentMessageId = null
    }
}
