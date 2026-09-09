package com.tsuty.remoteagent.ui.transcript

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire format between the host and the transcript document, and the document itself.
 *
 * The events are dispatched by their `type` in `transcript.js`, so the discriminator and the
 * names of the events are part of the contract and not an implementation detail.
 */
class TranscriptProtocolTest {

    @Test
    fun `events are dispatched by a type discriminator`() {
        val json = TranscriptProtocol.encode(
            listOf(
                ResetEvent(sessionId = "s1"),
                BlockEvent(id = "user-1", role = TranscriptRole.USER, html = "<p>hi</p>"),
                StreamEvent(id = "agent-2", text = "chunk"),
                GroupEvent(id = "group-3", closed = true, elapsedMs = 12_000),
                TurnEndEvent(id = "agent-2", targetId = "user-1"),
                ToastEvent("Copied to clipboard"),
            ),
        )

        assertTrue(json.contains("""{"type":"reset","sessionId":"s1"}"""), json)
        assertTrue(json.contains(""""type":"block","id":"user-1","role":"user""""), json)
        assertTrue(json.contains(""""type":"stream","id":"agent-2","text":"chunk""""), json)
        assertTrue(json.contains(""""type":"group","id":"group-3""""), json)
        assertTrue(json.contains(""""type":"turnEnd""""), json)
        assertTrue(json.contains("""{"type":"toast","text":"Copied to clipboard"}"""), json)
    }

    @Test
    fun `the actions of the document are parsed back`() {
        assertEquals(ReadyAction, TranscriptProtocol.decodeAction("""{"type":"ready"}"""))
        assertEquals(
            OpenFileAction("/w/Foo.kt", 12),
            TranscriptProtocol.decodeAction("""{"type":"openFile","path":"/w/Foo.kt","line":12}"""),
        )
        assertEquals(
            OpenDiffAction("toolu_1", 0),
            TranscriptProtocol.decodeAction("""{"type":"openDiff","toolCallId":"toolu_1","diffIndex":0}"""),
        )
        assertEquals(
            SubmitChoicesAction("agent-1", "- [x] A"),
            TranscriptProtocol.decodeAction("""{"type":"submitChoices","id":"agent-1","markdown":"- [x] A"}"""),
        )
        assertEquals(
            PermissionAction("req-1", "allow_once"),
            TranscriptProtocol.decodeAction("""{"type":"permission","id":"req-1","optionId":"allow_once"}"""),
        )
        assertEquals(
            CopyTextAction("println()"),
            TranscriptProtocol.decodeAction("""{"type":"copyText","text":"println()"}"""),
        )
        assertEquals(
            CopyBlockAction("agent-1"),
            TranscriptProtocol.decodeAction("""{"type":"copyBlock","id":"agent-1"}"""),
        )
    }

    /** A document of another version must never break the host. */
    @Test
    fun `an unknown message is ignored`() {
        assertNull(TranscriptProtocol.decodeAction("""{"type":"teleport"}"""))
        assertNull(TranscriptProtocol.decodeAction("not json"))
    }

    @Test
    fun `the document inlines the style sheet and the script under one nonce`() {
        val html = TranscriptDocument.html()

        assertTrue(html.contains("Content-Security-Policy"), "the CSP meta tag is missing")
        assertTrue(html.contains("default-src 'none'"), "the CSP does not deny by default")
        val nonce = Regex("""nonce-([A-Za-z0-9_-]+)""").find(html)?.groupValues?.get(1)
        requireNotNull(nonce) { "the CSP carries no nonce" }
        assertTrue(html.contains("""<style nonce="$nonce">"""), "the style sheet is not allowed to run")
        assertTrue(html.contains("""<script nonce="$nonce">"""), "the script is not allowed to run")
        assertTrue(html.contains("window.transcript"), "the script was not inlined")
        assertTrue(html.contains("--ra-code-bg"), "the style sheet was not inlined")
        // Nothing may be pulled in over the network or from a resource the document cannot reach.
        assertTrue(html.contains("{{").not(), "a placeholder was left in the document")
    }

    /**
     * The drawings are inlined as one sprite, which is what the script refers to by name: a
     * document without it draws no icon at all (see [TranscriptIconsTest]).
     */
    @Test
    fun `the document inlines the icon sprite`() {
        val html = TranscriptDocument.html()

        assertTrue(html.contains("""class="icon-sprite""""), "the sprite is missing")
        TranscriptIcons.NAMES.forEach { name ->
            assertTrue(html.contains("""id="${TranscriptIcons.PREFIX}$name""""), "no icon for $name")
        }
    }

    /**
     * A copy is confirmed by a [ToastEvent], which the document shows in a node of its own: without
     * it every copy button would answer with nothing at all.
     */
    @Test
    fun `the document carries the node a toast is shown in`() {
        val html = TranscriptDocument.html()

        assertTrue(html.contains("""id="toast""""), "the toast node is missing")
        assertTrue(html.contains("toast: showToast"), "the script does not handle a toast")
        assertTrue(html.contains(".toast--shown"), "the style sheet does not show a toast")
    }
}
