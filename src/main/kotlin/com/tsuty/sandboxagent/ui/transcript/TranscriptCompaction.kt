package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.acp.CompactionStatus
import com.tsuty.sandboxagent.session.CompactionEntry

/** Turns the state of a compaction of the context into the entry the transcript draws. */
object TranscriptCompaction {

    fun event(id: String, entry: CompactionEntry): CompactionEvent = CompactionEvent(
        id = id,
        headline = headline(entry),
        running = entry.status == CompactionStatus.IN_PROGRESS,
        failed = entry.status == CompactionStatus.FAILED,
        // The summary is the agent's text, so it goes through the sanitizer like an answer does.
        summaryHtml = entry.summary?.takeIf { it.isNotBlank() }?.let(TranscriptHtml::render).orEmpty(),
    )

    fun headline(entry: CompactionEntry): String {
        val state = when (entry.status) {
            CompactionStatus.IN_PROGRESS -> "Compacting the context…"
            CompactionStatus.COMPLETED -> "Context compacted"
            CompactionStatus.FAILED -> "Context compaction failed"
            CompactionStatus.CANCELLED -> "Context compaction cancelled"
            else -> "Context compaction: ${entry.status}"
        }
        val error = entry.error?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        return "$state: $error"
    }
}
