package com.photoxt.core.ml

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The object eraser's three models on a phone (spec §4.10): each downloads, loads in Play
 * services' LiteRT (GPU first) and fills a hole over a red sign in a sky made here with the sky's
 * colour, not red, black or noise. Times go to logcat under EraserCheck, the processor used under
 * Eraser; the filled pictures stay in the test app's external files folder (`eraser-check/`).
 */
@RunWith(AndroidJUnit4::class)
class EraserModelsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = EraserModels(context)
    private val inpainter = LiteRtInpainter(context, models)
    private val side = EraserModel.SIDE

    @Test
    fun everyModelFillsTheHoleWithTheSky() = runBlocking<Unit> {
        val picture = sky()
        val hole = ByteArray(side * side) { if (inHole(it % side, it / side)) -1 else 0 }
        val around = mean(picture) { x, y -> !inHole(x, y) && inHole(x, y, grow = RING) }
        val out = File(context.getExternalFilesDir(null), "eraser-check").apply { mkdirs() }
        for (model in EraserModel.entries) {
            models.download(model)
            val start = SystemClock.elapsedRealtime()
            val first = inpainter.inpaint(model, picture, hole)
            val loaded = SystemClock.elapsedRealtime()
            inpainter.inpaint(model, picture, hole)
            val again = SystemClock.elapsedRealtime() - loaded
            File(out, "${model.name.lowercase()}.png").outputStream().use {
                first.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it)
            }
            val inside = mean(first) { x, y -> inHole(x, y) }
            val off = distance(inside, around)
            Log.i(
                TAG,
                "${model.name}: first ${loaded - start} ms with loading, then $again ms; " +
                    "hole ${inside.joinToString()} against sky ${around.joinToString()}, $off apart"
            )
            assertTrue("${model.name} filled the hole with $inside", off < LIMIT)
        }
    }

    /** A blue sky, lighter towards the horizon, with a red sign in the middle. */
    private fun sky(): Bitmap {
        val bitmap = createBitmap(side, side)
        for (y in 0 until side) {
            val t = y.toFloat() / side
            val skyColour = Color.rgb(
                (BLUE_R + (WHITE - BLUE_R) * t).toInt(),
                (BLUE_G + (WHITE - BLUE_G) * t).toInt(),
                WHITE
            )
            for (x in 0 until side) {
                val sign = inHole(x, y, grow = -SIGN_INSET)
                bitmap[x, y] = if (sign) Color.RED else skyColour
            }
        }
        return bitmap
    }

    private fun inHole(x: Int, y: Int, grow: Int = 0): Boolean {
        val range = side / 2 - HOLE - grow until side / 2 + HOLE + grow
        return x in range && y in range
    }

    private fun mean(bitmap: Bitmap, take: (Int, Int) -> Boolean): List<Int> {
        val sum = LongArray(3)
        var count = 0
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (!take(x, y)) continue
                val p = bitmap[x, y]
                sum[0] += Color.red(p).toLong()
                sum[1] += Color.green(p).toLong()
                sum[2] += Color.blue(p).toLong()
                count++
            }
        }
        return sum.map { (it / count).toInt() }
    }

    private fun distance(a: List<Int>, b: List<Int>) =
        sqrt(a.zip(b).sumOf { (p, q) -> ((p - q) * (p - q)).toDouble() }).toInt()

    private companion object {
        const val TAG = "EraserCheck"
        const val HOLE = 64
        const val RING = 24
        const val SIGN_INSET = 8
        const val LIMIT = 40
        const val BLUE_R = 90
        const val BLUE_G = 150
        const val WHITE = 255
        const val PNG_QUALITY = 100
    }
}
