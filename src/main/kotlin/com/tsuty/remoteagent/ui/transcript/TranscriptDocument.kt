package com.tsuty.remoteagent.ui.transcript

import java.security.SecureRandom
import java.util.Base64

/**
 * The document the transcript is rendered in: `transcript.html` with the bundled style sheet, the
 * bundled script and the icon sprite inlined into it.
 *
 * Inlining is not an optimization but a consequence of how the document is loaded: a page handed
 * to the browser as a string has an opaque origin, so it cannot pull `transcript.css` /
 * `transcript.js` in as resources (and ES modules are out of reach as well). Serving them would
 * mean a custom scheme handler or a local server, neither of which is needed for anything else —
 * local images are opened in the IDE instead of being shown here.
 *
 * The `nonce` of the Content Security Policy is generated per document, so the two bundled
 * fragments are the only ones allowed to run: whatever inline HTML an agent (or a tool output it
 * quoted) wrote is refused by the browser even if it survived [TranscriptHtml.sanitize].
 */
object TranscriptDocument {

    fun html(): String {
        val nonce = nonce()
        return read("transcript.html")
            .replace(NONCE, nonce)
            .replace(CSS, read("transcript.css"))
            .replace(JS, read("transcript.js"))
            .replace(ICONS, TranscriptIcons.sprite())
    }

    private fun nonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun read(name: String): String =
        javaClass.getResourceAsStream("/transcript/$name")?.use { it.readBytes().decodeToString() }
            ?: error("Missing transcript resource: $name")

    private const val NONCE = "{{NONCE}}"
    private const val CSS = "{{CSS}}"
    private const val JS = "{{JS}}"
    private const val ICONS = "{{ICONS}}"
}
