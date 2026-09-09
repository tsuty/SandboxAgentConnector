package com.tsuty.remoteagent.ui

import com.tsuty.remoteagent.acp.TextContentBlock
import com.tsuty.remoteagent.acp.ToolCall
import com.tsuty.remoteagent.acp.ToolCallContentBlock
import com.tsuty.remoteagent.acp.ToolCallDiff
import com.tsuty.remoteagent.acp.ToolCallLocation
import com.tsuty.remoteagent.acp.ToolCallStatus
import com.tsuty.remoteagent.acp.ToolKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolCallPresentationTest {

    private val edit = ToolCall(
        toolCallId = "toolu_edit",
        title = "Edit tmp/sample.json",
        kind = ToolKind.EDIT,
        status = ToolCallStatus.PENDING,
        content = listOf(
            ToolCallDiff(
                path = "/project/tmp/sample.json",
                oldText = "a\nc\nb\n",
                newText = "a\nb\nc\n",
            ),
        ),
        locations = listOf(ToolCallLocation("/project/tmp/sample.json")),
        rawInput = buildJsonObject {
            put("file_path", "/project/tmp/sample.json")
            put("old_string", "a\nc\nb\n")
            put("new_string", "a\nb\nc\n")
        },
    )

    @Test
    fun `the anchor of a file edit reports the changed lines`() {
        assertEquals("+1 / -1", ToolCallPresentation.summary(edit))
        assertEquals("Edit tmp/sample.json: +1 / -1", ToolCallPresentation.anchor(edit))
    }

    @Test
    fun `a file edit has no details text of its own`() {
        // The whole story of the call is the diff, which the flat line opens in the diff viewer:
        // the `old_string` / `new_string` raw input must not be dumped next to it.
        assertEquals("", ToolCallPresentation.details(edit))
        assertEquals("", ToolCallPresentation.expandedBody(edit))
    }

    @Test
    fun `a reported change is a diff entry whatever the kind of the call is`() {
        // A permission card shows the change as one line opening the diff viewer, even when the
        // agent did not label the tool call as an `edit`.
        val other = edit.copy(kind = ToolKind.OTHER)

        val entry = requireNotNull(ToolCallPresentation.diffEntry(other))
        assertEquals("Edit", entry.prefix)
        val target = entry.targets.single()
        assertEquals("sample.json  +1 / -1", target.text)
        assertEquals(other.diffs.single(), target.diff)
    }

    @Test
    fun `a call without a change has no diff entry`() {
        val execute = ToolCall(
            toolCallId = "toolu_bash",
            kind = ToolKind.EXECUTE,
            status = ToolCallStatus.PENDING,
            rawInput = buildJsonObject { put("command", "npm test") },
        )

        assertNull(ToolCallPresentation.diffEntry(execute))
    }

    @Test
    fun `an executed command stays the anchor of the card`() {
        val execute = ToolCall(
            toolCallId = "toolu_bash",
            title = "npm test",
            kind = ToolKind.EXECUTE,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject { put("command", "npm test") },
            rawOutput = buildJsonObject { put("output", "1 passed") },
        )

        assertEquals("npm test", ToolCallPresentation.anchor(execute))
        assertTrue(ToolCallPresentation.details(execute).contains("1 passed"))
        val expectedMarkdown = """
            ```console
            npm test
            ```
            Output:
            ```text
            1 passed
            ```
        """.trimIndent()
        assertEquals(expectedMarkdown, ToolCallPresentation.expandedBody(execute))
    }

    @Test
    fun `an executed command with empty output produces console markdown`() {
        val execute = ToolCall(
            toolCallId = "toolu_bash",
            kind = ToolKind.EXECUTE,
            status = ToolCallStatus.IN_PROGRESS,
            rawInput = buildJsonObject { put("command", "npm test") },
        )

        val expectedMarkdown = """
            ```console
            npm test
            ```
            Output:
            ```text
            ```
        """.trimIndent()
        assertEquals(expectedMarkdown, ToolCallPresentation.expandedBody(execute))
    }

    @Test
    fun `a read reports the file the title does not name`() {
        val read = ToolCall(
            toolCallId = "toolu_read",
            title = "Read file",
            kind = ToolKind.READ,
            status = ToolCallStatus.COMPLETED,
            locations = listOf(ToolCallLocation("/project/tmp/sample.json", line = 1)),
        )

        assertEquals("Read file: sample.json", ToolCallPresentation.anchor(read))
    }

    @Test
    fun `a read is presented as the file it opened`() {
        val read = ToolCall(
            toolCallId = "toolu_read",
            title = "Read tmp/sample.json",
            kind = ToolKind.READ,
            status = ToolCallStatus.COMPLETED,
            locations = listOf(ToolCallLocation("/project/tmp/sample.json", line = 3)),
        )

        val entry = requireNotNull(ToolCallPresentation.flatEntry(read))
        assertEquals("Read", entry.prefix)
        assertFalse(entry.expandable)
        assertEquals(
            listOf(ToolCallPresentation.FlatTarget("sample.json", path = "/project/tmp/sample.json", line = 3)),
            entry.targets,
        )
    }

    @Test
    fun `a read without a location falls back to the raw input path`() {
        val read = ToolCall(
            toolCallId = "toolu_read",
            kind = ToolKind.READ,
            status = ToolCallStatus.PENDING,
            rawInput = buildJsonObject { put("file_path", "/project/tmp/sample.json") },
        )

        val target = requireNotNull(ToolCallPresentation.flatEntry(read)).targets.single()
        assertEquals("/project/tmp/sample.json", target.path)
        assertNull(target.line)
    }

    @Test
    fun `an edit is a flat line opening the diff viewer`() {
        // The diff is never unfolded into the transcript: the line itself opens the full diff.
        val entry = requireNotNull(ToolCallPresentation.flatEntry(edit))
        assertEquals("Edit", entry.prefix)
        assertFalse(entry.expandable)
        val target = entry.targets.single()
        assertEquals("sample.json  +1 / -1", target.text)
        assertEquals("/project/tmp/sample.json", target.path)
        assertEquals(edit.diffs.single(), target.diff)
    }

    @Test
    fun `an edit without a diff still names the file it changed`() {
        val blind = ToolCall(
            toolCallId = "toolu_edit",
            kind = ToolKind.EDIT,
            status = ToolCallStatus.PENDING,
            rawInput = buildJsonObject { put("file_path", "/project/tmp/sample.json") },
        )

        val entry = requireNotNull(ToolCallPresentation.flatEntry(blind))
        assertEquals("Edit", entry.prefix)
        assertEquals(listOf("sample.json"), entry.targets.map { it.text })
        assertNull(entry.targets.single().diff)
    }

    @Test
    fun `a delete is presented as the file it removed`() {
        val delete = ToolCall(
            toolCallId = "toolu_delete",
            title = "Delete tmp/sample.json",
            kind = ToolKind.DELETE,
            status = ToolCallStatus.COMPLETED,
            locations = listOf(ToolCallLocation("/project/tmp/sample.json")),
            rawInput = buildJsonObject { put("path", "/project/tmp/sample.json") },
        )

        val entry = requireNotNull(ToolCallPresentation.flatEntry(delete))
        assertEquals("Delete", entry.prefix)
        assertFalse(entry.expandable)
        assertEquals(listOf("sample.json"), entry.targets.map { it.text })
    }

    @Test
    fun `a move names both ends of the rename`() {
        val move = ToolCall(
            toolCallId = "toolu_move",
            title = "Move tmp/sample.json",
            kind = ToolKind.MOVE,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject {
                put("old_path", "/project/tmp/sample.json")
                put("new_path", "/project/tmp/renamed.json")
            },
        )

        val entry = requireNotNull(ToolCallPresentation.flatEntry(move))
        assertEquals("Move", entry.prefix)
        assertEquals(listOf("sample.json", "renamed.json"), entry.targets.map { it.text })
        assertEquals("/project/tmp/renamed.json", entry.targets.last().path)
    }

    @Test
    fun `a move that only knows one path reads like a read`() {
        val move = ToolCall(
            toolCallId = "toolu_move",
            kind = ToolKind.MOVE,
            status = ToolCallStatus.PENDING,
            locations = listOf(ToolCallLocation("/project/tmp/sample.json")),
        )

        assertEquals(listOf("sample.json"), requireNotNull(ToolCallPresentation.flatEntry(move)).targets.map { it.text })
    }

    @Test
    fun `a fetch is presented as the URL it opened and keeps its caret`() {
        val fetch = ToolCall(
            toolCallId = "toolu_fetch",
            title = "Fetch",
            kind = ToolKind.FETCH,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject { put("url", "https://agentclientprotocol.com/protocol/v1/tool-calls/") },
            rawOutput = JsonPrimitive("Tool calls represent..."),
        )

        val entry = requireNotNull(ToolCallPresentation.flatEntry(fetch))
        assertEquals("Fetch", entry.prefix)
        assertTrue(entry.expandable)
        val target = entry.targets.single()
        assertEquals("agentclientprotocol.com/protocol/v1/tool-calls", target.text)
        assertEquals("https://agentclientprotocol.com/protocol/v1/tool-calls/", target.url)
        assertNull(target.path)
    }

    @Test
    fun `a search counts the files it reported`() {
        val search = ToolCall(
            toolCallId = "toolu_search",
            title = "Search for pattern",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject { put("pattern", "ToolKind") },
            locations = listOf(
                ToolCallLocation("/project/a.kt"),
                ToolCallLocation("/project/b.kt"),
            ),
        )

        assertEquals(2, requireNotNull(ToolCallPresentation.searchResultCount(search)))
        assertEquals("Search ToolKind · 2 results", ToolCallPresentation.anchor(search))
        // The hit list stays folded away behind the anchor.
        assertNull(ToolCallPresentation.flatEntry(search))
    }

    @Test
    fun `a search reads the count out of its own output`() {
        val search = ToolCall(
            toolCallId = "toolu_search",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject { put("pattern", "ToolKind") },
            rawOutput = JsonPrimitive("Found 12 files\n/project/a.kt\n/project/b.kt"),
        )

        assertEquals(12, requireNotNull(ToolCallPresentation.searchResultCount(search)))
        assertEquals("Search ToolKind · 12 results", ToolCallPresentation.anchor(search))
    }

    @Test
    fun `a search that found nothing says so`() {
        val search = ToolCall(
            toolCallId = "toolu_search",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject { put("pattern", "ToolKind") },
            rawOutput = JsonPrimitive("No files found"),
        )

        assertEquals(0, requireNotNull(ToolCallPresentation.searchResultCount(search)))
        assertEquals("Search ToolKind · no results", ToolCallPresentation.anchor(search))
    }

    @Test
    fun `a running search reports the pattern alone`() {
        val search = ToolCall(
            toolCallId = "toolu_search",
            kind = ToolKind.SEARCH,
            status = ToolCallStatus.PENDING,
            rawInput = buildJsonObject { put("pattern", "ToolKind") },
        )

        assertNull(ToolCallPresentation.searchResultCount(search))
        assertEquals("Search ToolKind", ToolCallPresentation.anchor(search))
    }

    @Test
    fun `a switch mode carries its plan and the mode it moves to`() {
        val switchMode = ToolCall(
            toolCallId = "call_001",
            title = "Ready for implementation",
            kind = ToolKind.SWITCH_MODE,
            status = ToolCallStatus.PENDING,
            rawInput = buildJsonObject { put("mode", "code") },
            content = listOf(ToolCallContentBlock(TextContentBlock("## Implementation Plan\n\n1. Add the card"))),
        )

        val entry = requireNotNull(ToolCallPresentation.switchModeEntry(switchMode))
        assertEquals("Ready for implementation", entry.headline)
        assertEquals("code", entry.modeId)
        assertEquals("## Implementation Plan\n\n1. Add the card", entry.plan)
        // The switch is the story of the entry, not a flat line folded in with the reads.
        assertNull(ToolCallPresentation.flatEntry(switchMode))
    }

    @Test
    fun `a switch mode without a plan names the mode it moves to`() {
        val switchMode = ToolCall(
            toolCallId = "call_002",
            kind = ToolKind.SWITCH_MODE,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject { put("mode_id", "architect") },
        )

        val entry = requireNotNull(ToolCallPresentation.switchModeEntry(switchMode))
        assertEquals("Switch to architect", entry.headline)
        assertTrue(entry.plan.isEmpty())
    }

    @Test
    fun `the raw input of a switch mode is never shown as its plan`() {
        val switchMode = ToolCall(
            toolCallId = "call_003",
            title = "Ready for implementation",
            kind = ToolKind.SWITCH_MODE,
            status = ToolCallStatus.COMPLETED,
            rawInput = buildJsonObject { put("mode", "code") },
            rawOutput = JsonPrimitive("switched"),
        )

        assertTrue(requireNotNull(ToolCallPresentation.switchModeEntry(switchMode)).plan.isEmpty())
    }

    @Test
    fun `only a switch mode gets the framed block`() {
        assertNull(ToolCallPresentation.switchModeEntry(edit))
    }
}
