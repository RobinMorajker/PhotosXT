package com.photoxt.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoxt.core.model.ColorAdjust
import com.photoxt.core.model.Curves
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Effects
import com.photoxt.core.model.ExportFormat
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.Filter
import com.photoxt.core.model.Frame
import com.photoxt.core.model.FrameStyle
import com.photoxt.core.model.Geometry
import com.photoxt.core.model.HslBand
import com.photoxt.core.model.HslColor
import com.photoxt.core.model.Light
import com.photoxt.core.model.Point01
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.TextLayer
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7, Done when: a 50 MP photo exported in tiles matches the editor's preview. The photo
 * is made here, row by row, at the Galaxy A15's 8160 × 6120; the export is scaled down to the
 * preview's size and the two are compared pixel by pixel. Both pictures stay in the app's
 * external files folder (`export-check/`) to look at.
 */
@RunWith(AndroidJUnit4::class)
class ExportMatchesPreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "export-check").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun aFiftyMegapixelExportMatchesThePreview() = runBlocking<Unit> {
        val photo = File(work, "photo.jpg")
        writePhoto(photo)
        val assets = EditAssets(File(work, "assets"))
        val recipe = EditRecipe(
            geometry = Geometry(straighten = 3f, crop = Rect01(0.05f, 0.08f, 0.95f, 0.9f)),
            light = Light(exposure = 0.3f, contrast = 0.25f, highlights = -0.2f, shadows = 0.3f),
            color = ColorAdjust(
                saturation = 0.2f,
                temperature = 0.15f,
                hsl = mapOf(HslColor.BLUE to HslBand(saturation = -0.3f))
            ),
            curves = Curves(rgb = listOf(Point01(0f, 0f), Point01(0.5f, 0.55f), Point01(1f, 1f))),
            effects = Effects(clarity = 0.3f, dehaze = 0.1f, vignette = 0.2f),
            filter = Filter(look = "warm", intensity = 0.6f),
            layers = listOf(TextLayer(id = "t", text = "PhotoXT", size = 0.1f)),
            frame = Frame(style = FrameStyle.SOLID, width = 0.03f)
        )
        val preview = PreviewSession(assets).use { session ->
            session.open(photo)
            val shown = session.render(recipe, PreviewMode.EDIT, PREVIEW_EDGE)
            shown.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val exported = File(work, "export.jpg")
        val asJpeg = ExportOptions(format = ExportFormat.JPEG)
        val request = ExportRequest(photo, "image/jpeg", recipe, asJpeg, exported)
        GlPhotoExporter(context, assets).export(request)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(exported.path, bounds)
        val full = previewGeometry(Size(WIDTH, HEIGHT), 1, recipe, Int.MAX_VALUE).output
        assertEquals(full, Size(bounds.outWidth, bounds.outHeight))
        assertTrue("${bounds.outWidth} × ${bounds.outHeight}", full.pixels > MIN_EXPORT_PIXELS)

        val shrunk = shrink(exported, preview.width, preview.height)
        val diff = Diff.of(preview, shrunk)
        keep(preview, "preview.png")
        keep(shrunk, "export-scaled.png")
        Log.i(
            TAG,
            "Export ${full.width}×${full.height} against preview ${preview.width}×${preview.height}: $diff"
        )
        assertTrue("$diff", diff.mean <= MAX_MEAN)
        assertTrue("$diff", diff.p99 <= MAX_P99)
        work.deleteRecursively()
    }

    /** Sky-to-ground gradients, big discs and soft bands: detail at every scale but no noise. */
    private fun writePhoto(file: File) {
        file.outputStream().use { out ->
            val writer = JpegWriter(out, WIDTH, HEIGHT, QUALITY)
            val rows = ByteArray(WIDTH * STRIP * 4)
            var y = 0
            while (y < HEIGHT) {
                val n = minOf(STRIP, HEIGHT - y)
                for (r in 0 until n) fillRow(rows, r * WIDTH * 4, y + r)
                writer.write(rows, n)
                y += n
            }
            writer.finish()
        }
    }

    private fun fillRow(rgba: ByteArray, at: Int, y: Int) {
        val v = y / HEIGHT.toFloat()
        for (x in 0 until WIDTH) {
            val u = x / WIDTH.toFloat()
            var r = 60 + 150 * u
            var g = 90 + 120 * (1 - v)
            var b = 200 - 120 * v
            // Soft bands across the frame.
            val band = ((x + y) / BAND) % 2
            if (band == 0) {
                r += 25
                g += 15
            }
            DISCS.forEach { (cx, cy, color) ->
                if (hypot(u - cx, (v - cy) * HEIGHT / WIDTH) < DISC_RADIUS) {
                    r = ((color shr 16) and 0xFF).toFloat()
                    g = ((color shr 8) and 0xFF).toFloat()
                    b = (color and 0xFF).toFloat()
                }
            }
            val i = at + x * 4
            rgba[i] = r.coerceIn(0f, 255f).toInt().toByte()
            rgba[i + 1] = g.coerceIn(0f, 255f).toInt().toByte()
            rgba[i + 2] = b.coerceIn(0f, 255f).toInt().toByte()
            rgba[i + 3] = -1
        }
    }

    /** The export decoded small by the JPEG decoder, then scaled to [width] × [height]. */
    private fun shrink(file: File, width: Int, height: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width * 2) sample *= 2
        val smaller = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = checkNotNull(BitmapFactory.decodeFile(file.path, smaller))
        return Bitmap.createScaledBitmap(decoded, width, height, true)
    }

    private fun keep(bitmap: Bitmap, name: String) {
        val dir = File(context.getExternalFilesDir(null), "export-check").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** How far two pictures of one size are apart, per channel, 0 to 255. */
    private data class Diff(val mean: Double, val p99: Int, val pixels: Int) {
        companion object {
            fun of(a: Bitmap, b: Bitmap): Diff {
                val w = minOf(a.width, b.width)
                val h = minOf(a.height, b.height)
                val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }
                val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
                val histogram = IntArray(256)
                var sum = 0L
                for (i in pa.indices) {
                    var worst = 0
                    for (shift in intArrayOf(16, 8, 0)) {
                        val d = abs(((pa[i] shr shift) and 0xFF) - ((pb[i] shr shift) and 0xFF))
                        sum += d
                        worst = max(worst, d)
                    }
                    histogram[worst]++
                }
                var seen = 0
                val p99 = histogram.indices.first { k ->
                    seen += histogram[k]
                    seen >= pa.size * PERCENTILE
                }
                return Diff(sum / (pa.size * 3.0), p99, pa.size)
            }
        }
    }

    private companion object {
        const val TAG = "ExportCheck"
        const val WIDTH = 8160
        const val HEIGHT = 6120
        const val STRIP = 16
        const val QUALITY = 92
        const val BAND = 900
        const val DISC_RADIUS = 0.12f
        const val PREVIEW_EDGE = 1024
        const val MIN_EXPORT_PIXELS = 35_000_000L
        const val MAX_MEAN = 3.0
        const val MAX_P99 = 24
        const val PERCENTILE = 0.99
        val DISCS = listOf(
            Triple(0.25f, 0.3f, 0xE04040),
            Triple(0.7f, 0.35f, 0x40B060),
            Triple(0.5f, 0.72f, 0xF0D040)
        )
    }
}
