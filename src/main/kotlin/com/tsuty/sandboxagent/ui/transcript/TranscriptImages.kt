package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.acp.ImageContentBlock
import java.util.Base64

/**
 * Which images the transcript draws, and as what.
 *
 * An image is drawn as a `data:` URI (the only kind of image the CSP lets in, see
 * `docs/development/TRANSCRIPT_WEBVIEW.md`), and only when it is a raster format a browser decodes
 * without running anything: PNG, JPEG, GIF or WebP. The format is read from the first bytes of the
 * data rather than from the `mimeType` the agent declared, so an SVG (a document that may carry a
 * script) or anything else labelled `image/png` stays the placeholder it was. An image above
 * [MAX_BASE64_CHARS] stays one as well: it is handed to the browser as a string, as a whole.
 */
object TranscriptImages {

    /** About 5 MiB of image data, as Base64. */
    const val MAX_BASE64_CHARS: Int = 5 * 1024 * 1024 / 3 * 4

    /** MIME types an image may be drawn as. */
    val RASTER_TYPES: Set<String> = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    /** `data:` URI of [block], or `null` when it is not drawn (see the class comment). */
    fun dataUri(block: ImageContentBlock): String? {
        val data = block.data
        if (data.isEmpty() || data.length > MAX_BASE64_CHARS || data.length % 4 != 0) return null
        // Checked as a whole: the URI ends up inside an attribute, so it may hold nothing else.
        if (!BASE64.matches(data)) return null
        val head = runCatching { Base64.getDecoder().decode(data.take(HEAD_CHARS)) }.getOrNull() ?: return null
        val type = rasterType(head) ?: return null
        return "data:$type;base64,$data"
    }

    /** `true` for a `data:` URI of a raster type, as an `<img>` of agent Markdown may carry one. */
    fun isRasterDataUri(url: String): Boolean {
        val type = url.removePrefix("data:").substringBefore(',').substringBefore(';').trim().lowercase()
        return url.startsWith("data:", ignoreCase = true) && type in RASTER_TYPES
    }

    /** `<img>` of [block] for an HTML body built on the host, or `null` when it is not drawn. */
    fun html(block: ImageContentBlock): String? {
        val src = dataUri(block) ?: return null
        val alt = TranscriptHtml.escape("[image ${block.mimeType}]")
        return """<p><img class="transcript-image" src="$src" alt="$alt"></p>"""
    }

    /** Enough Base64 for the [SIGNATURE_BYTES] the longest signature ([rasterType]) needs. */
    private const val HEAD_CHARS = 16

    /** Bytes [rasterType] reads at most. */
    const val SIGNATURE_BYTES: Int = 12

    /**
     * The raster type the first bytes of an image say it is, one of [RASTER_TYPES]; `null` for
     * anything else. The same test decides what is drawn and what a prompt may carry.
     */
    fun rasterType(head: ByteArray): String? = when {
        head.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        head.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        head.startsWith(*"GIF87a".codes()) || head.startsWith(*"GIF89a".codes()) -> "image/gif"
        head.startsWith(*"RIFF".codes()) && head.size >= 12 &&
            head.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
        else -> null
    }

    private fun String.codes(): IntArray = map { it.code }.toIntArray()

    private fun ByteArray.startsWith(vararg bytes: Int): Boolean =
        size >= bytes.size && bytes.indices.all { (this[it].toInt() and 0xFF) == bytes[it] }
}
