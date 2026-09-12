package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.acp.PermissionOption
import com.tsuty.sandboxagent.acp.ToolCall
import com.tsuty.sandboxagent.acp.ToolCallStatus
import com.tsuty.sandboxagent.acp.ToolKind
import com.tsuty.sandboxagent.ui.ToolCallPresentation

/**
 * Turns a `tool_call` / `tool_call_update` and a `session/request_permission` into the events the
 * transcript document draws.
 *
 * What a card shows is decided by [ToolCallPresentation], exactly as it was for the Swing cards:
 * this file only maps its output onto the wire format, so the presentation rules (which kinds stay
 * a flat one line entry, what the anchor says, what a `switch_mode` spells out) keep living in one
 * place and stay testable without a browser.
 */
object TranscriptToolCall {

    /** [groupId] is `null` for a call that stays an entry of its own (a `switch_mode`). */
    fun event(toolCall: ToolCall, groupId: String?): ToolCallEvent {
        val switchMode = ToolCallPresentation.switchModeEntry(toolCall)
        return ToolCallEvent(
            id = toolCall.toolCallId,
            groupId = groupId,
            kind = toolCall.kind ?: ToolKind.OTHER,
            anchor = ToolCallPresentation.anchor(toolCall),
            statusLabel = statusLabel(toolCall),
            failed = toolCall.status == ToolCallStatus.FAILED,
            flat = flat(toolCall),
            bodyHtml = if (switchMode == null) TranscriptHtml.render(ToolCallPresentation.expandedBody(toolCall)) else "",
            switchMode = switchMode?.let {
                TranscriptSwitchMode(
                    headline = it.headline,
                    modeId = it.modeId,
                    planHtml = TranscriptHtml.render(it.plan),
                )
            },
        )
    }

    /**
     * The inline approval card of [toolCall].
     *
     * A `switch_mode` asks which mode to continue in, not whether a tool may run: its plan is
     * spelled out right away, while the command and the output of an ordinary call stay folded
     * away behind the same anchor the tool call card uses.
     */
    fun permissionEvent(id: String, toolCall: ToolCall, options: List<PermissionOption>): PermissionEvent {
        val switchMode = ToolCallPresentation.switchModeEntry(toolCall)
        return PermissionEvent(
            id = id,
            toolCallId = toolCall.toolCallId,
            headline = headline(toolCall, switchMode),
            bodyHtml = switchMode
                ?.let { TranscriptHtml.render(it.plan) }
                ?: TranscriptHtml.render(ToolCallPresentation.expandedBody(toolCall)),
            expanded = switchMode != null,
            // Whatever the agent calls the tool, a change behind it is opened in the diff viewer.
            flat = ToolCallPresentation.diffEntry(toolCall)?.let { flat(it, toolCall) },
            options = options.map {
                TranscriptPermissionOption(
                    optionId = it.optionId,
                    label = it.presentableName,
                    allow = it.isAllow,
                    default = it.isDefault,
                )
            },
        )
    }

    private fun headline(toolCall: ToolCall, switchMode: ToolCallPresentation.SwitchModeEntry?): String {
        if (switchMode == null) return "Permission requested: ${ToolCallPresentation.title(toolCall)}"
        return switchMode.modeId?.let { "Switch mode: ${switchMode.headline}  →  $it" }
            ?: "Switch mode: ${switchMode.headline}"
    }

    /**
     * The status is only spelled out while a call is unfinished or has failed: a completed call is
     * the normal case and does not deserve a word of its own.
     */
    private fun statusLabel(toolCall: ToolCall): String = when (toolCall.status) {
        ToolCallStatus.COMPLETED, null -> ""
        else -> ToolCallPresentation.statusLabel(toolCall.status)
    }

    private fun flat(toolCall: ToolCall): TranscriptFlatEntry? =
        ToolCallPresentation.flatEntry(toolCall)?.let { flat(it, toolCall) }

    /**
     * A diff is referenced by its index in `ToolCall.diffs` instead of being sent along: the
     * document never renders a change, it asks the host to open it in the platform diff viewer.
     */
    private fun flat(entry: ToolCallPresentation.FlatEntry, toolCall: ToolCall): TranscriptFlatEntry =
        TranscriptFlatEntry(
            prefix = entry.prefix,
            separator = entry.separator,
            expandable = entry.expandable,
            targets = entry.targets.map { target ->
                TranscriptTarget(
                    text = target.text,
                    path = target.path,
                    line = target.line,
                    url = target.url,
                    diffIndex = target.diff?.let { diff -> toolCall.diffs.indexOf(diff).takeIf { it >= 0 } },
                )
            },
        )
}
