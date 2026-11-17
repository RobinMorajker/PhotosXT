package com.photoxt.core.render

import com.photoxt.core.model.Curves
import com.photoxt.core.model.Point01
import java.io.Reader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Curves (spec §4.10) as one 256-entry table per channel: the master curve, then the channel's
 * own, through monotone cubic splines (Fritsch–Carlson), so a curve never overshoots its points.
 */
object ToneCurves {
    const val ENTRIES = 256

    fun isIdentity(curves: Curves) = listOf(curves.rgb, curves.red, curves.green, curves.blue)
        .all { it.isEmpty() || it.isStraight() }

    /** RGBA bytes of a 256×1 texture: red, green and blue tables, alpha 255. */
    fun texture(curves: Curves): ByteArray {
        val master = table(curves.rgb)
        val channels = listOf(table(curves.red), table(curves.green), table(curves.blue))
        val bytes = ByteArray(ENTRIES * CHANNELS)
        for (i in 0 until ENTRIES) {
            for (c in 0 until RGB) {
                val through = channels[c][(master[i] * (ENTRIES - 1)).roundToInt()]
                bytes[i * CHANNELS + c] = (through * MAX_BYTE).roundToInt().toByte()
            }
            // Alpha, after the three colours.
            bytes[i * CHANNELS + RGB] = MAX_BYTE.toInt().toByte()
        }
        return bytes
    }

    /** The curve through [points] sampled at 256 inputs, each 0 to 1. */
    fun table(points: List<Point01>): FloatArray {
        val p = sorted(points)
        if (p.size < 2) return FloatArray(ENTRIES) { it / (ENTRIES - 1f) }
        val n = p.size
        val xs = DoubleArray(n) { p[it].x.toDouble() }
        val ys = DoubleArray(n) { p[it].y.toDouble() }
        val slopes = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = DoubleArray(n)
        m[0] = slopes[0]
        m[n - 1] = slopes[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (slopes[i - 1] * slopes[i] <= 0) 0.0 else (slopes[i - 1] + slopes[i]) / 2
        }
        for (i in 0 until n - 1) {
            if (slopes[i] == 0.0) {
                m[i] = 0.0
                m[i + 1] = 0.0
            } else {
                val a = m[i] / slopes[i]
                val b = m[i + 1] / slopes[i]
                val s = a * a + b * b
                if (s > LIMIT) {
                    val t = LIMIT_ROOT / sqrt(s)
                    m[i] = t * a * slopes[i]
                    m[i + 1] = t * b * slopes[i]
                }
            }
        }
        return FloatArray(ENTRIES) { k ->
            val x = k / (ENTRIES - 1.0)
            val y = when {
                x <= xs[0] -> ys[0]

                x >= xs[n - 1] -> ys[n - 1]

                else -> {
                    var i = 0
                    while (x > xs[i + 1]) i++
                    hermite(x, xs[i], xs[i + 1], ys[i], ys[i + 1], m[i], m[i + 1])
                }
            }
            y.coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** The cubic Hermite curve from ([x0], [y0]) to ([x1], [y1]) with slopes [m0] and [m1]. */
    @Suppress("LongParameterList", "MagicNumber")
    private fun hermite(
        x: Double,
        x0: Double,
        x1: Double,
        y0: Double,
        y1: Double,
        m0: Double,
        m1: Double
    ): Double {
        val h = x1 - x0
        val t = (x - x0) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * h * m0 +
            (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * h * m1
    }

    private fun sorted(points: List<Point01>): List<Point01> {
        val byX = points.map { Point01(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.x }
        // Two points at the same x would divide by zero: keep the later one.
        return byX.filterIndexed { i, point ->
            i == byX.lastIndex || byX[i + 1].x - point.x > MIN_GAP
        }
    }

    private fun List<Point01>.isStraight() = all { abs(it.x - it.y) < MIN_GAP }

    private const val CHANNELS = 4
    private const val LIMIT = 9.0
    private const val LIMIT_ROOT = 3.0
    private const val MIN_GAP = 1e-3f
}

/** A 3D colour lookup table: [size]³ RGB entries, red fastest, each 0 to 1. */
class Lut3d(val size: Int, val data: FloatArray) {
    init {
        require(size in 2..MAX_SIZE && data.size == size * size * size * RGB) { "A malformed LUT" }
    }

    /** The table as RGB bytes, for a `GL_RGB8` 3D texture. */
    fun bytes(): ByteArray = ByteArray(data.size) {
        (data[it].coerceIn(0f, 1f) * MAX_BYTE).roundToInt().toByte()
    }

    /** The colour [r], [g], [b] (0 to 1) maps to, trilinearly, as the GPU reads it. */
    fun apply(r: Float, g: Float, b: Float): FloatArray {
        val n = size - 1
        val fr = r.coerceIn(0f, 1f) * n
        val fg = g.coerceIn(0f, 1f) * n
        val fb = b.coerceIn(0f, 1f) * n
        val r0 = min(fr.toInt(), n - 1)
        val g0 = min(fg.toInt(), n - 1)
        val b0 = min(fb.toInt(), n - 1)
        val dr = fr - r0
        val dg = fg - g0
        val db = fb - b0
        val out = FloatArray(RGB)
        for (c in 0 until RGB) {
            fun at(i: Int, j: Int, k: Int) = data[((k * size + j) * size + i) * RGB + c]
            val c00 = at(r0, g0, b0) * (1 - dr) + at(r0 + 1, g0, b0) * dr
            val c10 = at(r0, g0 + 1, b0) * (1 - dr) + at(r0 + 1, g0 + 1, b0) * dr
            val c01 = at(r0, g0, b0 + 1) * (1 - dr) + at(r0 + 1, g0, b0 + 1) * dr
            val c11 = at(r0, g0 + 1, b0 + 1) * (1 - dr) + at(r0 + 1, g0 + 1, b0 + 1) * dr
            out[c] = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
        }
        return out
    }

    companion object {
        const val MAX_SIZE = 128

        /** A table filled from [map], which takes and returns r, g, b from 0 to 1. */
        fun of(size: Int, map: (Float, Float, Float) -> FloatArray): Lut3d {
            val data = FloatArray(size * size * size * RGB)
            var i = 0
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val out = map(r / (size - 1f), g / (size - 1f), b / (size - 1f))
                        data[i++] = out[0].coerceIn(0f, 1f)
                        data[i++] = out[1].coerceIn(0f, 1f)
                        data[i++] = out[2].coerceIn(0f, 1f)
                    }
                }
            }
            return Lut3d(size, data)
        }
    }
}

/** A `.cube` file that isn't a 3D LUT PhotoXT can read. */
class CubeFormatException(message: String) : Exception(message)

/**
 * Adobe/Resolve `.cube` 3D LUTs (spec §4.10, Filters): `LUT_3D_SIZE`, optional `DOMAIN_MIN` and
 * `DOMAIN_MAX`, then size³ lines of three numbers, red changing fastest. 1D LUTs are refused.
 */
object CubeLut {
    fun parse(reader: Reader): Lut3d {
        val cube = Cube()
        reader.buffered().lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .forEach { cube.line(it.split(WHITESPACE)) }
        return cube.finished()
    }

    /** A cube file as its lines come: the header's size and domain, then the table. */
    private class Cube {
        private var size = 0
        private var min = floatArrayOf(0f, 0f, 0f)
        private var max = floatArrayOf(1f, 1f, 1f)
        private var data: FloatArray? = null
        private var count = 0

        fun line(parts: List<String>) {
            when (parts[0].uppercase()) {
                "LUT_1D_SIZE" -> fail("1D LUTs aren't supported")

                "LUT_3D_SIZE" -> sized(parts.getOrNull(1)?.toIntOrNull())

                "DOMAIN_MIN" -> min = floats(parts)

                "DOMAIN_MAX" -> max = floats(parts)

                // TITLE and other keywords say nothing PhotoXT needs; the rest is the table.
                else -> if (!parts[0].first().isLetter()) entry(floats(parts))
            }
        }

        private fun sized(n: Int?) {
            if (n == null || n !in 2..Lut3d.MAX_SIZE) fail("Bad LUT_3D_SIZE")
            size = n
            data = FloatArray(n * n * n * RGB)
        }

        private fun entry(values: FloatArray) {
            val table = data ?: fail("Data before LUT_3D_SIZE")
            if (count >= size * size * size) fail("Too many entries")
            for (c in 0 until RGB) {
                val span = max(max[c] - min[c], MIN_DOMAIN)
                table[count * RGB + c] = (values[c] - min[c]) / span
            }
            count++
        }

        fun finished(): Lut3d {
            val table = data ?: fail("No LUT_3D_SIZE")
            if (count != size * size * size) fail("Too few entries")
            return Lut3d(size, table)
        }
    }

    private fun fail(message: String): Nothing = throw CubeFormatException(message)

    private fun floats(parts: List<String>): FloatArray {
        if (parts.size < RGB) fail("A line needs three numbers")
        return FloatArray(RGB) {
            parts[parts.size - RGB + it].toFloatOrNull() ?: fail("Not a number")
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_DOMAIN = 1e-6f
}

/**
 * The built-in looks (spec §4.10, Filters), each a colour map baked into a 3D LUT once. Their ids
 * are stored in recipes, so they never change meaning; new looks get new ids.
 */
@Suppress("MagicNumber") // A look is its numbers.
object Looks {
    val ids = listOf(
        "vivid",
        "warm",
        "cool",
        "mono",
        "noir",
        "sepia",
        "matte",
        "vintage",
        "teal_orange",
        "pastel"
    )

    private val cache = HashMap<String, Lut3d>()

    @Synchronized
    fun lut(id: String): Lut3d? =
        cache[id] ?: map(id)?.let { Lut3d.of(SIZE, it) }?.also { cache[id] = it }

    @Suppress("CyclomaticComplexMethod") // One entry for each look.
    private fun map(id: String): ((Float, Float, Float) -> FloatArray)? = when (id) {
        "vivid" -> { r, g, b -> saturate(contrast(floatArrayOf(r, g, b), 0.15f), 1.35f) }

        "warm" -> { r, g, b -> floatArrayOf(r * 1.06f + 0.02f, g * 1.01f, b * 0.9f) }

        "cool" -> { r, g, b -> floatArrayOf(r * 0.92f, g * 1.0f, b * 1.08f + 0.02f) }

        "mono" -> { r, g, b -> grey(luma(r, g, b)) }

        "noir" -> { r, g, b -> contrast(grey(luma(r, g, b)), 0.45f) }

        "sepia" -> { r, g, b ->
            val l = luma(r, g, b)
            floatArrayOf(l * 1.07f + 0.05f, l * 0.95f + 0.02f, l * 0.75f)
        }

        "matte" -> { r, g, b -> fade(saturate(floatArrayOf(r, g, b), 0.85f), 0.12f) }

        "vintage" -> { r, g, b ->
            fade(saturate(floatArrayOf(r * 1.05f + 0.03f, g * 1.0f + 0.01f, b * 0.85f), 0.8f), 0.1f)
        }

        "teal_orange" -> { r, g, b ->
            val l = luma(r, g, b)
            val shadowTeal = (1 - l) * 0.08f
            val highlightOrange = l * 0.08f
            saturate(
                floatArrayOf(
                    r + highlightOrange - shadowTeal,
                    g + highlightOrange * 0.3f,
                    b - highlightOrange + shadowTeal
                ),
                1.1f
            )
        }

        "pastel" -> { r, g, b -> fade(saturate(floatArrayOf(r, g, b), 0.7f), 0.18f) }

        else -> null
    }

    /** Rec. 709 luma. */
    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun grey(l: Float) = floatArrayOf(l, l, l)

    private fun saturate(c: FloatArray, amount: Float): FloatArray {
        val l = luma(c[0], c[1], c[2])
        return FloatArray(RGB) { (l + (c[it] - l) * amount).coerceIn(0f, 1f) }
    }

    /** Towards a smoothstep S-curve by [amount]. */
    private fun contrast(c: FloatArray, amount: Float) = FloatArray(RGB) {
        val x = c[it].coerceIn(0f, 1f)
        val s = x * x * (3 - 2 * x)
        (x + (s - x) * amount * 2).coerceIn(0f, 1f)
    }

    /** Lifted blacks and gentler whites. */
    private fun fade(c: FloatArray, amount: Float) = FloatArray(RGB) {
        c[it] * (1 - amount * 1.5f) + amount
    }

    private const val SIZE = 33
}

/** sRGB ↔ linear light, as the shader does it (IEC 61966-2-1). */
@Suppress("MagicNumber") // The transfer function's own constants.
internal object Srgb {
    fun toLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    fun toSrgb(c: Float): Float {
        val x = max(c, 0f)
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1 / 2.4f) - 0.055f
    }
}

/** Red, green and blue. */
private const val RGB = 3
private const val MAX_BYTE = 255f
