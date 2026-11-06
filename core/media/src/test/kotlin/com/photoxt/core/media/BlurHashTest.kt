package com.photoxt.core.media

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurHashTest {
    @Test
    fun aFlatColourEncodesAndDecodesToItself() {
        val teal = 0xFF2A9D8F.toInt()
        val hash = BlurHash.encode(IntArray(16 * 12) { teal }, 16, 12)
        assertEquals(28, hash.length) // 4 × 3 components
        val pixels = assertNotNullAnd(BlurHash.decode(hash, 8, 6))
        // Quantised AC terms leave a faint ripple; the average is the colour.
        for (shift in listOf(16, 8, 0)) {
            assertClose(teal shr shift and 0xFF, pixels.map { it shr shift and 0xFF }.average())
        }
    }

    @Test
    fun aGradientKeepsItsDirection() {
        val width = 32
        val pixels = IntArray(width * 8) { index ->
            val v = index % width * 255 / (width - 1)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val decoded = assertNotNullAnd(BlurHash.decode(BlurHash.encode(pixels, width, 8), 16, 4))
        val left = decoded[16 + 1] and 0xFF
        val right = decoded[16 + 14] and 0xFF
        assertTrue("left $left should be darker than right $right", left + 100 < right)
    }

    @Test
    fun aKnownHashDecodesAndBadOnesDoNot() {
        // The example on blurha.sh.
        assertNotNull(BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 4, 3))
        assertNull(BlurHash.decode("LEHV6n", 4, 3))
        assertNull(BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdn!", 4, 3))
    }

    private fun assertNotNullAnd(pixels: IntArray?): IntArray {
        assertNotNull(pixels)
        return checkNotNull(pixels)
    }

    private fun assertClose(expected: Int, actual: Double) =
        assertTrue("$actual is not close to $expected", abs(expected - actual) <= TOLERANCE)

    private companion object {
        // A placeholder, not a copy: sRGB rounding of the quantised terms moves it a few steps.
        const val TOLERANCE = 6
    }
}
