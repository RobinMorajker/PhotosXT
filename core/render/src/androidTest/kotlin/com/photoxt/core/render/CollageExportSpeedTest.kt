package com.photoxt.core.render

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Filter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8, Done when: a 9-photo collage exports at 4K in under 5 s on a mid-range phone (the
 * Galaxy A15). Nine photos at the A15 camera's 4,080 × 3,060 are written first, untimed; then a
 * nine-cell grid is exported at 3,840 × 3,840 and timed, the exporter's own decode check
 * included. A second export with a look on every photo (the editor's shader) is timed for the
 * record only. The collage stays in the app's external files folder (`collage-check/`).
 */
@RunWith(AndroidJUnit4::class)
class CollageExportSpeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "collage-check").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun nineFourKPhotosExportInUnderFiveSeconds() = runBlocking<Unit> {
        val ids = (1..PHOTOS).map { "p$it" }
        val photos = ids.mapIndexed { i, id ->
            id to File(work, "$id.jpg").also { writePhoto(it, i) }
        }.toMap()
        val template = CollageTemplates.grid(PHOTOS).first()
        val plain = CollageProject(
            layout = template.layout,
            template = template.id,
            cells = ids.map { CollageCell(mediaId = it) }
        )
        val exporter = CanvasCollageExporter(context, EditAssets(File(work, "assets")))

        val output = File(work, "collage.jpg")
        val ms = timed { exporter.export(CollageRequest(plain, photos, CollageOutput(), output)) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertEquals(CollageOutput.LONG_EDGE_4K, bounds.outWidth)
        assertEquals(CollageOutput.LONG_EDGE_4K, bounds.outHeight)

        val look = EditRecipe(filter = Filter(look = "warm", intensity = 0.8f))
        val looked = plain.copy(cells = plain.cells.map { it.copy(look = look) })
        val withLooks = File(work, "collage-looks.jpg")
        val lookMs = timed {
            exporter.export(CollageRequest(looked, photos, CollageOutput(), withLooks))
        }

        val mb = "%.1f".format(photos.values.sumOf { it.length() } / photos.size / 1e6)
        Log.i(TAG, "9 photos ($mb MB each) at 4K: $ms ms plain, $lookMs ms with a look on each")
        keep(output)
        work.deleteRecursively()
        assertTrue("$ms ms", ms < MAX_MS)
    }

    private inline fun timed(block: () -> Unit): Long {
        val started = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - started
    }

    /**
     * A gradient in its own colours for each photo, with grain like a camera's, so the file is
     * about the size of a real 12 MP photo (3–5 MB) and decodes as slowly.
     */
    private fun writePhoto(file: File, index: Int) {
        file.outputStream().use { out ->
            val writer = JpegWriter(out, WIDTH, HEIGHT, QUALITY)
            val rows = ByteArray(WIDTH * STRIP * 4)
            var y = 0
            while (y < HEIGHT) {
                val n = minOf(STRIP, HEIGHT - y)
                for (r in 0 until n) fillRow(rows, r * WIDTH * 4, y + r, index)
                writer.write(rows, n)
                y += n
            }
            writer.finish()
        }
    }

    private fun fillRow(rgba: ByteArray, at: Int, y: Int, index: Int) {
        val v = y * 255 / HEIGHT
        val tint = index * TINT_STEP
        var seed = (y * 7919 + index * 104_729) or 1
        for (x in 0 until WIDTH) {
            // xorshift: grain of ±GRAIN levels, different in every pixel and every run alike.
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            val grain = (seed and (GRAIN * 2 - 1)) - GRAIN
            val u = x * 255 / WIDTH
            val i = at + x * 4
            rgba[i] = ((u + tint) % 256 + grain).coerceIn(0, 255).toByte()
            rgba[i + 1] = ((255 - v + tint / 2) % 256 + grain).coerceIn(0, 255).toByte()
            rgba[i + 2] = ((v + u / 2) % 256 + grain).coerceIn(0, 255).toByte()
            rgba[i + 3] = -1
        }
    }

    private fun keep(file: File) {
        val dir = File(context.getExternalFilesDir(null), "collage-check").apply { mkdirs() }
        file.copyTo(File(dir, file.name), overwrite = true)
    }

    private companion object {
        const val TAG = "CollageCheck"
        const val PHOTOS = 9
        const val WIDTH = 4080
        const val HEIGHT = 3060
        const val STRIP = 16
        const val QUALITY = 92
        const val TINT_STEP = 28
        const val GRAIN = 16
        const val MAX_MS = 5_000L
    }
}
