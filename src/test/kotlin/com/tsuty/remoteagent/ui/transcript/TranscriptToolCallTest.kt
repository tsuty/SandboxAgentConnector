package com.tsuty.remoteagent.ui.transcript

import com.tsuty.remoteagent.acp.PermissionOption
import com.tsuty.remoteagent.acp.PermissionOptionKind
import com.tsuty.remoteagent.acp.TextContentBlock
import com.tsuty.remoteagent.acp.ToolCall
import com.tsuty.remoteagent.acp.ToolCallContentBlock
import com.tsuty.remoteagent.acp.ToolCallDiff
import com.tsuty.remoteagent.acp.ToolCallLocation
import com.tsuty.remoteagent.acp.ToolCallStatus
import com.tsuty.remoteagent.acp.ToolKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptToolCallTest {

    private val edit = ToolCall(
        toolCallId = "toolu_edit",
        title = "Edit tmp/sample.json",
        kind = ToolKind.EDIT,
        status = ToolCallStatus.COMPLETED,
        content = listOf(ToolCallDiff(path = "/project/tmp/sample.json", oldText = "a\nc\n", newText = "a\nb\n")),
        locations = listOf(ToolCallLocation("/project/tmp/sample.json")),
    )

    private val execute = ToolCall(
        toolCallId = "toolu_bash",
        kind = ToolKind.EXECUTE,
        status = ToolCallStatus.IN_PROGRESS,
        rawInput = buildJsonObject { put("command", "npm test") },
    )

    /** A change is referenced by its index: the document asks the host to open the diff viewer. */
    @Test
    fun `an edit becomes a flat entry pointing at its diff`() {
        val event = TranscriptToolCall.event(edit, groupId = "group-1")

        assertEquals("group-1", event.groupId)
        assertEquals(ToolKind.EDIT, event.kind)
        val target = requireNotNull(event.flat).targets.single()
        assertEquals("sample.json  +1 / -1", target.text)
        assertEquals(0, target.diffIndex)
        assertEquals("/project/tmp/sample.json", target.path)
    }

    @Test
    fun `a completed call says nothing about its status`() {
        assertEquals("", TranscriptToolCall.event(edit, groupId = null).statusLabel)
        assertEquals("running", TranscriptToolCall.event(execute, groupId = null).statusLabel)
        assertTrue(TranscriptToolCall.event(edit.copy(status = ToolCallStatus.FAILED), null).failed)
    }

    @Test
    fun `an execute call is folded away behind its command`() {
        val event = TranscriptToolCall.event(execute, groupId = "group-1")

        assertNull(event.flat)
        assertEquals("npm test", event.anchor)
        assertTrue(event.bodyHtml.contains("npm test"), event.bodyHtml)
    }

    @Test
    fun `a switch mode keeps its plan rendered and out of the group`() {
        val switchMode = ToolCall(
            toolCallId = "toolu_mode",
            kind = ToolKind.SWITCH_MODE,
            title = "Ready to implement",
            content = listOf(ToolCallContentBlock(TextContentBlock("## Plan\n\n- step"))),
            rawInput = buildJsonObject { put("mode", "code") },
        )

        val event = TranscriptToolCall.event(switchMode, groupId = null)
        val mode = requireNotNull(event.switchMode)
        assertEquals("Ready to implement", mode.headline)
        assertEquals("code", mode.modeId)
        assertTrue(mode.planHtml.contains("<h2>Plan</h2>"), mode.planHtml)
        // The plan is the body of the framed block, so nothing is folded away next to it.
        assertEquals("", event.bodyHtml)
    }

    @Test
    fun `a permission card carries the options the agent sent`() {
        val event = TranscriptToolCall.permissionEvent(
            id = "req-1",
            toolCall = edit,
            options = listOf(
                PermissionOption("allow_once", name = "Allow once", kind = PermissionOptionKind.ALLOW_ONCE),
                PermissionOption("reject", name = "Deny", kind = PermissionOptionKind.REJECT_ONCE),
            ),
        )

        assertEquals("Permission requested: Edit tmp/sample.json", event.headline)
        assertEquals(listOf("Allow once", "Deny"), event.options.map { it.label })
        assertEquals(listOf(true, false), event.options.map { it.allow })
        // The change is a single line opening the diff viewer, never unfolded on the card.
        assertEquals(0, requireNotNull(event.flat).targets.single().diffIndex)
    }

    @Test
    fun `a mode switch asks which mode to continue in, not for a permission`() {
        val switchMode = ToolCall(
            toolCallId = "toolu_mode",
            kind = ToolKind.SWITCH_MODE,
            title = "Plan ready",
            content = listOf(ToolCallContentBlock(TextContentBlock("the plan"))),
            rawInput = buildJsonObject { put("mode", "code") },
        )

        val event = TranscriptToolCall.permissionEvent("req-2", switchMode, options = emptyList())

        assertEquals("Switch mode: Plan ready  →  code", event.headline)
        assertTrue(event.expanded)
        assertTrue(event.bodyHtml.contains("the plan"), event.bodyHtml)
    }
}
