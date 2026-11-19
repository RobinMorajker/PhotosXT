package com.photoxt.core.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The eraser models' pins, and the planes the models read and write. */
class EraserTest {
    @Test
    fun everyModelIsPinnedToOneFile() {
        for (model in EraserModel.entries) {
            assertTrue(model.name, COMMIT.containsMatchIn(model.url))
            assertTrue(model.name, SHA256.matches(model.sha256))
            assertTrue(model.name, model.bytes > 0)
        }
    }

    @Test
    fun planesCarryTheColoursAndTheHole() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        // LaMa and DeepFill: RGB planes in [0, 1] with the erased pixel blank, a mask of 1 there.
        val (image, mask) = Planes.imageAndMask(intArrayOf(red, green), byteArrayOf(0, -1))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f), FloatArray(6) { image.get() }, 0f)
        assertArrayEquals(floatArrayOf(0f, 1f), FloatArray(2) { mask.get() }, 0f)
        image.rewind()
        val black = 0xFF000000.toInt()
        assertArrayEquals(intArrayOf(red, black), Planes.pixels(image, 2, signed = false))
        // MI-GAN: what to keep − 0.5, then RGB in [−1, 1]; its output is in [−1, 1] too.
        val migan = Planes.migan(intArrayOf(red), byteArrayOf(0))
        assertArrayEquals(floatArrayOf(0.5f, 1f, -1f, -1f), FloatArray(4) { migan.get() }, 1e-6f)
        val out = Planes.floats(3).apply { put(floatArrayOf(1f, -1f, -1f)).rewind() }
        assertArrayEquals(intArrayOf(red), Planes.pixels(out, 1, signed = true))
    }

    private companion object {
        /** An immutable revision in the URL, never a branch. */
        val COMMIT = Regex("/[0-9a-f]{40}/")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
