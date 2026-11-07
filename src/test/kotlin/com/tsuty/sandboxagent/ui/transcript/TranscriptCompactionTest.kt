package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.session.CompactionEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranscriptCompactionTest {

    @Test
    fun `a running compaction spins, a failed one says why`() {
        val running = TranscriptCompaction.event("compaction:c1", CompactionEntry("c1", "in_progress"))
        assertTrue(running.running)
        assertEquals("Compacting the context…", running.headline)
        assertEquals("", running.summaryHtml)

        val failed = TranscriptCompaction.event("compaction:c1", CompactionEntry("c1", "failed", error = "too large"))
        assertTrue(failed.failed)
        assertFalse(failed.running)
        assertEquals("Context compaction failed: too large", failed.headline)
    }

    @Test
    fun `the summary is the agent's markdown, sanitized`() {
        val event = TranscriptCompaction.event(
            "compaction:c1",
            CompactionEntry("c1", "completed", summary = "**Fixed** the parser<script>alert(1)</script>"),
        )

        assertEquals("Context compacted", event.headline)
        assertTrue(event.summaryHtml.contains("<strong>Fixed</strong>"), event.summaryHtml)
        assertFalse(event.summaryHtml.contains("script"), event.summaryHtml)
    }
}
