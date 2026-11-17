package com.photoxt.core.render

import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The streaming encoders write files other decoders read back as the same picture (spec §4.10). */
class StripWritersTest {
    private val width = 333
    private val height = 251

    /** Gradients, an edge, a little noise; premultiplied RGBA, with see-through pixels if [alpha]. */
    private fun picture(alpha: Boolean): ByteArray {
        val random = Random(7)
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = (y * width + x) * 4
                val a = if (alpha && x < width / 3) (y * 255 / height) else 255
                val r = (x * 255 / width + random.nextInt(-6, 7)).coerceIn(0, 255)
                val g = (y * 255 / height).coerceIn(0, 255)
                val b = if (x > width / 2) 230 else 40
                bytes[o] = (r * a / 255).toByte()
                bytes[o + 1] = (g * a / 255).toByte()
                bytes[o + 2] = (b * a / 255).toByte()
                bytes[o + 3] = a.toByte()
            }
        }
        return bytes
    }

    /** Feeds [rgba] to [sink] in strips of uneven heights, as tiles end. */
    private fun feed(sink: RowSink, rgba: ByteArray) {
        var y = 0
        val heights = listOf(1, 16, 7, 64, 33)
        var i = 0
        while (y < height) {
            val rows = minOf(heights[i++ % heights.size], height - y)
            sink.write(rgba.copyOfRange(y * width * 4, (y + rows) * width * 4), rows)
            y += rows
        }
        sink.finish()
    }

    private fun psnr(rgba: ByteArray, decoded: java.awt.image.BufferedImage): Double {
        var sum = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val o = (y * width + x) * 4
                val p = decoded.getRGB(x, y)
                val got = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                for (c in 0 until 3) {
                    val d = (rgba[o + c].toInt() and 0xFF) - got[c]
                    sum += d * d
                }
            }
        }
        val mse = sum / (width * height * 3)
        return 10 * log10(255.0 * 255.0 / mse)
    }

    @Test
    fun jpegDecodesToTheSamePicture() {
        for ((quality, floor) in listOf(80 to 30.0, 95 to 36.0)) {
            val rgba = picture(alpha = false)
            val out = ByteArrayOutputStream()
            JpegWriter(out, width, height, quality).use { feed(it, rgba) }
            val decoded =
                checkNotNull(ImageIO.read(out.toByteArray().inputStream())) { "Not a JPEG" }
            assertEquals(width, decoded.width)
            assertEquals(height, decoded.height)
            val db = psnr(rgba, decoded)
            assertTrue("PSNR $db dB at quality $quality", db > floor)
        }
    }

    @Test
    fun pngIsLossless() {
        for (alpha in listOf(false, true)) {
            val rgba = picture(alpha)
            val out = ByteArrayOutputStream()
            PngWriter(out, width, height, alpha).use { feed(it, rgba) }
            val decoded = checkNotNull(ImageIO.read(out.toByteArray().inputStream()))
            val worst = (0 until width * height).maxOf { i ->
                difference(rgba, i * 4, decoded.getRGB(i % width, i / width), alpha)
            }
            assertTrue("worst $worst", worst <= 1)
        }
    }

    /**
     * How far decoded pixel [p] is from the premultiplied one at [o] of [rgba], on its worst
     * channel. Straight alpha back to premultiplied rounds by at most one step; a pixel that
     * is all transparent has no colour to compare.
     */
    private fun difference(rgba: ByteArray, o: Int, p: Int, alpha: Boolean): Int {
        val a = rgba[o + 3].toInt() and 0xFF
        if (alpha) assertEquals(a, (p ushr 24) and 0xFF)
        if (a == 0) return 0
        val got = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
        return (0 until 3).maxOf { c ->
            val want = rgba[o + c].toInt() and 0xFF
            val back = if (alpha) got[c] * a / 255 else got[c]
            kotlin.math.abs(want - back)
        }
    }

    @Test
    fun jpegLaysTransparencyOverWhite() {
        val rgba = ByteArray(16 * 16 * 4)
        val out = ByteArrayOutputStream()
        JpegWriter(out, 16, 16, 90).use {
            it.write(rgba, 16)
            it.finish()
        }
        val decoded = checkNotNull(ImageIO.read(out.toByteArray().inputStream()))
        val p = decoded.getRGB(8, 8)
        assertTrue(sqrt((((p shr 16) and 0xFF) - 255.0) * (((p shr 16) and 0xFF) - 255.0)) < 4)
    }
}
