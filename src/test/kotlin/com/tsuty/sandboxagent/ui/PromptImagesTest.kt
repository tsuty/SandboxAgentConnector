package com.tsuty.sandboxagent.ui

import com.intellij.util.ui.JBUI
import com.tsuty.sandboxagent.acp.PromptImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.assertNotNull

class PromptImagesTest {

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    @Test
    fun `a png is read as an image of the type its bytes say`() {
        val bytes = png(8, 4)

        val read = PromptImages.of(bytes)

        assertEquals(
            PromptImages.Read.Image(PromptImage("image/png", Base64.getEncoder().encodeToString(bytes)), bytes),
            read,
        )
    }

    @Test
    fun `anything else is no image, and an image above the limit is too large`() {
        assertEquals(PromptImages.Read.NotAnImage, PromptImages.of("<svg/>".toByteArray()))
        assertEquals(PromptImages.Read.NotAnImage, PromptImages.of(ByteArray(0)))
        val large = png(1, 1).copyOf((PromptImages.MAX_BYTES + 1).toInt())
        assertEquals(PromptImages.Read.TooLarge(PromptImages.MAX_BYTES + 1), PromptImages.of(large))
    }

    @Test
    fun `a thumbnail keeps the proportions of the image within its box`() {
        val box = JBUI.scale(32)

        val tall = assertNotNull(PromptImages.thumbnail(png(100, 200)))
        assertEquals(box, tall.iconHeight)
        assertEquals(box / 2, tall.iconWidth)
        // A panorama fits the width of the box, and gets lower instead of being squeezed.
        val wide = assertNotNull(PromptImages.thumbnail(png(1000, 10)))
        assertEquals(box * 4, wide.iconWidth)
        assertNull(PromptImages.thumbnail("not an image".toByteArray()))
    }
}
