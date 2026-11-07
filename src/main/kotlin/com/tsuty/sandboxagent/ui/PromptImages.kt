package com.tsuty.sandboxagent.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import com.tsuty.sandboxagent.acp.PromptImage
import com.tsuty.sandboxagent.ui.transcript.TranscriptImages
import java.awt.Image
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Image files a prompt may carry: the raster types the transcript draws (see [TranscriptImages],
 * told by their bytes, not by their name) of at most [MAX_BYTES], sent as an `image` block to an
 * agent declaring `promptCapabilities.image`.
 */
object PromptImages {
    private val log = Logger.getInstance(PromptImages::class.java)

    /** 5 MiB: what the transcript draws, and what model APIs take per image at most. */
    const val MAX_BYTES: Long = 5L * 1024 * 1024

    /** What reading a binary file for a prompt came to. */
    sealed interface Read {
        /** Not one of the raster types: the file cannot be attached at all. */
        data object NotAnImage : Read

        data class TooLarge(val bytes: Long) : Read

        data class Image(val image: PromptImage, val bytes: ByteArray) : Read {
            override fun equals(other: Any?): Boolean = other is Image && other.image == image
            override fun hashCode(): Int = image.hashCode()
        }
    }

    fun read(file: VirtualFile): Read {
        if (file.length > MAX_BYTES) return Read.TooLarge(file.length)
        val bytes = runCatching { file.contentsToByteArray() }.getOrElse {
            log.info("Cannot read ${file.path}", it)
            return Read.NotAnImage
        }
        return of(bytes)
    }

    /** [bytes] as a prompt image; the file is read by [read]. */
    fun of(bytes: ByteArray): Read {
        if (bytes.size > MAX_BYTES) return Read.TooLarge(bytes.size.toLong())
        val type = TranscriptImages.rasterType(bytes.copyOf(minOf(bytes.size, TranscriptImages.SIGNATURE_BYTES)))
            ?: return Read.NotAnImage
        return Read.Image(PromptImage(type, Base64.getEncoder().encodeToString(bytes)), bytes)
    }

    /**
     * Thumbnail of the chip of an attached image, [THUMBNAIL_HEIGHT] high; `null` when the JDK
     * cannot decode the format (WebP), in which case the chip keeps the icon of the file type.
     * Shown before the prompt is sent on purpose: an image cannot be checked by its name.
     */
    fun thumbnail(bytes: ByteArray): Icon? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val boxHeight = JBUI.scale(THUMBNAIL_HEIGHT)
        val boxWidth = boxHeight * MAX_ASPECT
        // Fitted into the box, keeping the proportions: a panorama gets lower instead of wider.
        val scale = minOf(boxHeight.toDouble() / image.height, boxWidth.toDouble() / image.width)
        val width = maxOf(1, (image.width * scale).toInt())
        val height = maxOf(1, (image.height * scale).toInt())
        return ImageIcon(image.getScaledInstance(width, height, Image.SCALE_SMOOTH))
    }

    private const val THUMBNAIL_HEIGHT = 32

    /** A thumbnail is at most this many times as wide as it is high, so one chip cannot fill the strip. */
    private const val MAX_ASPECT = 4
}
