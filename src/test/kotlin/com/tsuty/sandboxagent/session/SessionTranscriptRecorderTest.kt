package com.tsuty.sandboxagent.session

import com.tsuty.sandboxagent.acp.PlanEntry
import com.tsuty.sandboxagent.acp.PromptAttachment
import com.tsuty.sandboxagent.acp.StopReason
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.ToolCallStatus
import com.tsuty.sandboxagent.service.SandboxAgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Folding of the event stream into the transcript a snapshot stores. */
class SessionTranscriptRecorderTest {

    @Test
    fun `streamed chunks are merged into whole messages`() {
        val recorder = SessionTranscriptRecorder()

        recorder.record(SandboxAgentEvent.UserPrompt("fix the parser"))
        recorder.record(SandboxAgentEvent.AgentMessageChunk("Sure, "))
        recorder.record(SandboxAgentEvent.AgentMessageChunk("done."))
        recorder.record(SandboxAgentEvent.AgentThoughtChunk("the last token is dropped"))
        recorder.record(SandboxAgentEvent.TurnFinished(StopReason.END_TURN))

        val entries = recorder.entries()
        assertEquals("fix the parser", assertIs<UserMessageEntry>(entries[0]).text)
        assertEquals("Sure, done.", assertIs<AgentMessageEntry>(entries[1]).text)
        assertEquals("the last token is dropped", assertIs<ThoughtEntry>(entries[2]).text)
        assertEquals("Turn finished", assertIs<NoticeEntry>(entries[3]).text)
        assertEquals(4, entries.size)
    }

    @Test
    fun `a replayed history is closed without a stop reason notice, but still gets a turn end marker`() {
        val recorder = SessionTranscriptRecorder()

        recorder.record(SandboxAgentEvent.UserPrompt("fix the parser"))
        recorder.record(SandboxAgentEvent.AgentThoughtChunk("the last token is dropped"))
        recorder.record(SandboxAgentEvent.AgentMessageChunk("Done."))
        recorder.record(SandboxAgentEvent.ReplayFinished)

        val entries = recorder.entries()
        assertEquals("Done.", assertIs<AgentMessageEntry>(entries[1]).text)
        assertEquals("the last token is dropped", assertIs<ThoughtEntry>(entries[2]).text)
        // No `NoticeEntry` (no stop reason to show), but the turn boundary must still be recorded,
        // otherwise a snapshot saved after a replay loses where this turn ended (see `TurnEndEntry`).
        assertIs<TurnEndEntry>(entries[3])
        assertEquals(4, entries.size)
    }

    @Test
    fun `the attachments of a prompt are recorded with it`() {
        val recorder = SessionTranscriptRecorder()
        val attachment = PromptAttachment(path = "/workspace/sample.json")

        recorder.record(SandboxAgentEvent.UserPrompt("fix the parser", listOf(attachment)))

        assertEquals(listOf(attachment), assertIs<UserMessageEntry>(recorder.entries().single()).attachments)
    }

    /** A replay reports the typed text and each attachment as a chunk of its own. */
    @Test
    fun `replayed attachments join the prompt they were sent with`() {
        val recorder = SessionTranscriptRecorder()
        val file = PromptAttachment(path = "/workspace/sample.json")
        val selection = PromptAttachment(path = "/workspace/sample.json", startLine = 5, endLine = 5)

        recorder.record(SandboxAgentEvent.UserPrompt("fix the parser"))
        recorder.record(SandboxAgentEvent.UserPromptAttachments(listOf(file)))
        // The same attachment reported twice must not double its chip.
        recorder.record(SandboxAgentEvent.UserPromptAttachments(listOf(file, selection)))

        val entries = recorder.entries()
        assertEquals(listOf(file, selection), assertIs<UserMessageEntry>(entries.single()).attachments)
    }

    /**
     * A replay can report the same selection twice, once with the snippet it was sent with and
     * once as a bare mention: one chip, and the one that can still show the snippet.
     */
    @Test
    fun `a selection reported twice keeps the content it was sent with`() {
        val recorder = SessionTranscriptRecorder()
        val bare = PromptAttachment(path = "/workspace/sample.json", startLine = 5, endLine = 5)
        val withContent = bare.copy(content = "[]")

        recorder.record(SandboxAgentEvent.UserPrompt("fix the parser"))
        recorder.record(SandboxAgentEvent.UserPromptAttachments(listOf(bare)))
        recorder.record(SandboxAgentEvent.UserPromptAttachments(listOf(withContent)))

        val attachments = assertIs<UserMessageEntry>(recorder.entries().single()).attachments
        assertEquals(listOf(withContent), attachments)
    }

    @Test
    fun `attachments reported before their prompt wait for it`() {
        val recorder = SessionTranscriptRecorder()
        val attachment = PromptAttachment(path = "/workspace/sample.json")

        recorder.record(SandboxAgentEvent.UserPromptAttachments(listOf(attachment)))
        recorder.record(SandboxAgentEvent.UserPrompt("fix the parser"))

        assertEquals(listOf(attachment), assertIs<UserMessageEntry>(recorder.entries().single()).attachments)
    }

    @Test
    fun `a tool call keeps its latest state in place`() {
        val recorder = SessionTranscriptRecorder()

        recorder.record(
            SandboxAgentEvent.ToolCallStarted(
                ToolCall(toolCallId = "call_1", title = "Edit Parser.kt", status = ToolCallStatus.PENDING),
            ),
        )
        recorder.record(
            SandboxAgentEvent.ToolCallUpdated(ToolCall(toolCallId = "call_1", status = ToolCallStatus.COMPLETED)),
        )

        val toolCall = assertIs<ToolCallEntry>(recorder.entries().single()).toolCall
        assertEquals("Edit Parser.kt", toolCall.title)
        assertEquals(ToolCallStatus.COMPLETED, toolCall.status)
    }

    @Test
    fun `the plan is refreshed instead of being appended again`() {
        val recorder = SessionTranscriptRecorder()

        recorder.record(SandboxAgentEvent.PlanUpdated(listOf(PlanEntry(content = "fix", status = "in_progress"))))
        recorder.record(SandboxAgentEvent.PlanUpdated(listOf(PlanEntry(content = "fix", status = "completed"))))

        val plan = assertIs<PlanSnapshotEntry>(recorder.entries().single())
        assertEquals("completed", plan.entries.single().status)
    }

    @Test
    fun `errors are recorded as error notices`() {
        val recorder = SessionTranscriptRecorder()

        recorder.record(SandboxAgentEvent.Error("session/prompt failed"))

        val notice = assertIs<NoticeEntry>(recorder.entries().single())
        assertEquals("session/prompt failed", notice.text)
        assertTrue(notice.error)
    }

    @Test
    fun `a restored snapshot keeps updating in place`() {
        val recorder = SessionTranscriptRecorder()
        val snapshot = SessionSnapshot(
            sessionId = "sess_1",
            entries = listOf(
                UserMessageEntry("fix the parser"),
                ToolCallEntry(ToolCall(toolCallId = "call_1", title = "Edit Parser.kt")),
                PlanSnapshotEntry(listOf(PlanEntry(content = "fix"))),
            ),
        )

        recorder.restore(snapshot)
        recorder.record(
            SandboxAgentEvent.ToolCallUpdated(ToolCall(toolCallId = "call_1", status = ToolCallStatus.COMPLETED)),
        )
        recorder.record(SandboxAgentEvent.PlanUpdated(listOf(PlanEntry(content = "fix", status = "completed"))))

        val entries = recorder.entries()
        assertEquals(3, entries.size)
        assertEquals(ToolCallStatus.COMPLETED, assertIs<ToolCallEntry>(entries[1]).toolCall.status)
        assertEquals("completed", assertIs<PlanSnapshotEntry>(entries[2]).entries.single().status)
    }

    @Test
    fun `clearing forgets the previous session`() {
        val recorder = SessionTranscriptRecorder()
        recorder.record(SandboxAgentEvent.UserPrompt("hi"))

        recorder.clear()

        assertTrue(recorder.isEmpty)
        assertTrue(recorder.entries().isEmpty())
    }
}
