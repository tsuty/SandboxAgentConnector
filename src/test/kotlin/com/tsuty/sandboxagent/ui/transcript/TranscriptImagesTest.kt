package com.tsuty.sandboxagent.ui.transcript

import com.tsuty.sandboxagent.acp.ImageContentBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class TranscriptImagesTest {

    private fun base64(vararg bytes: Int, padTo: Int = 24): String =
        Base64.getEncoder().encodeToString(ByteArray(padTo) { bytes.getOrElse(it) { 0 }.toByte() })

    private val png = base64(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `a raster image is drawn as the type its bytes say`() {
        // The declared type is not what decides: the bytes are a PNG.
        assertEquals("data:image/png;base64,$png", TranscriptImages.dataUri(ImageContentBlock(png, "image/jpeg")))
        val gif = base64(*"GIF89a".map { it.code }.toIntArray())
        assertEquals("data:image/gif;base64,$gif", TranscriptImages.dataUri(ImageContentBlock(gif, "image/gif")))
        val webp = base64(*"RIFF\u0000\u0000\u0000\u0000WEBP".map { it.code }.toIntArray())
        assertEquals("data:image/webp;base64,$webp", TranscriptImages.dataUri(ImageContentBlock(webp, "image/webp")))
    }

    @Test
    fun `an svg, a broken payload or a too large image stays a placeholder`() {
        val svg = Base64.getEncoder().encodeToString("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray())
        assertNull(TranscriptImages.dataUri(ImageContentBlock(svg, "image/png")))
        assertNull(TranscriptImages.dataUri(ImageContentBlock("$png\" onerror=\"x", "image/png")))
        assertNull(TranscriptImages.dataUri(ImageContentBlock("", "image/png")))
        val large = png + "A".repeat(TranscriptImages.MAX_BASE64_CHARS)
        assertNull(TranscriptImages.dataUri(ImageContentBlock(large, "image/png")))
    }

    @Test
    fun `only raster data uris count as images of agent markdown`() {
        assertTrue(TranscriptImages.isRasterDataUri("data:image/png;base64,AAAA"))
        assertTrue(TranscriptImages.isRasterDataUri("data:IMAGE/JPEG;base64,AAAA"))
        assertFalse(TranscriptImages.isRasterDataUri("data:image/svg+xml;base64,AAAA"))
        assertFalse(TranscriptImages.isRasterDataUri("data:text/html,<b>x</b>"))
    }
}
