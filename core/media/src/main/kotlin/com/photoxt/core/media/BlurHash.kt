// The BlurHash format's own constants (component limits, base 83, sRGB curve) are its spec.
@file:Suppress("MagicNumber")

package com.photoxt.core.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sign

/**
 * BlurHash (blurha.sh): a photo as a short string that decodes to a soft placeholder (spec §4.3,
 * §4.4). Stored per Drive item, so the viewer has something to show before any file loads.
 */
object BlurHash {
    private const val CHARS =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private const val BASE = 83
    private const val MAX_AC = 82.0
    private const val QUANT = 166.0
    private const val AC_STEPS = 19
    private const val AC_MAX = 18.0
    private const val AC_MID = 9.0

    /** Encodes ARGB [pixels] of a [width] × [height] image (keep it small, e.g. 32 px). */
    fun encode(
        pixels: IntArray,
        width: Int,
        height: Int,
        componentsX: Int = 4,
        componentsY: Int = 3
    ): String {
        require(componentsX in 1..9 && componentsY in 1..9 && pixels.size >= width * height)
        val factors = Array(componentsX * componentsY) { index ->
            val i = index % componentsX
            val j = index / componentsX
            val normalisation = if (index == 0) 1.0 else 2.0
            val sum = DoubleArray(3)
            for (y in 0 until height) {
                val cy = cos(PI * j * y / height)
                for (x in 0 until width) {
                    val basis = normalisation * cos(PI * i * x / width) * cy
                    val p = pixels[y * width + x]
                    sum[0] += basis * toLinear(p shr 16 and 0xFF)
                    sum[1] += basis * toLinear(p shr 8 and 0xFF)
                    sum[2] += basis * toLinear(p and 0xFF)
                }
            }
            DoubleArray(3) { sum[it] / (width * height) }
        }
        val hash = StringBuilder()
        hash.append(base83((componentsX - 1) + (componentsY - 1) * 9, 1))
        val ac = factors.drop(1)
        val maximum = if (ac.isEmpty()) {
            hash.append(base83(0, 1))
            1.0
        } else {
            val actual = ac.maxOf { factor -> factor.maxOf { abs(it) } }
            val quantised = floor(actual * QUANT - 0.5).coerceIn(0.0, MAX_AC).toInt()
            hash.append(base83(quantised, 1))
            (quantised + 1) / QUANT
        }
        val dc = factors[0]
        hash.append(base83((toSrgb(dc[0]) shl 16) + (toSrgb(dc[1]) shl 8) + toSrgb(dc[2]), 4))
        ac.forEach { factor ->
            fun q(v: Double) = floor(signPow(v / maximum, 0.5) * AC_MID + AC_MID + 0.5)
                .coerceIn(0.0, AC_MAX).toInt()
            hash.append(
                base83(
                    q(factor[0]) * AC_STEPS * AC_STEPS + q(factor[1]) * AC_STEPS + q(factor[2]),
                    2
                )
            )
        }
        return hash.toString()
    }

    /** Decodes [hash] to ARGB pixels of a [width] × [height] image, or null if it is invalid. */
    fun decode(hash: String, width: Int, height: Int): IntArray? {
        val digits = IntArray(hash.length) { CHARS.indexOf(hash[it]) }
        val size = digits.firstOrNull() ?: -1
        val componentsX = size % 9 + 1
        val componentsY = size / 9 + 1
        val valid = size >= 0 &&
            digits.none { it < 0 } &&
            hash.length == 4 + 2 * componentsX * componentsY
        return if (valid) render(digits, componentsX, componentsY, width, height) else null
    }

    private fun render(
        digits: IntArray,
        componentsX: Int,
        componentsY: Int,
        width: Int,
        height: Int
    ): IntArray {
        val maximum = (digits.number(1, 2) + 1) / QUANT
        val colors = Array(componentsX * componentsY) { index ->
            if (index == 0) {
                val dc = digits.number(2, 6)
                doubleArrayOf(
                    toLinear(dc shr 16),
                    toLinear(dc shr 8 and 0xFF),
                    toLinear(dc and 0xFF)
                )
            } else {
                val v = digits.number(4 + index * 2, 6 + index * 2)
                val quantised = intArrayOf(
                    v / (AC_STEPS * AC_STEPS),
                    v / AC_STEPS % AC_STEPS,
                    v % AC_STEPS
                )
                DoubleArray(3) { c -> signPow((quantised[c] - AC_MID) / AC_MID, 2.0) * maximum }
            }
        }
        return IntArray(width * height) { p ->
            val x = p % width
            val y = p / width
            val sum = DoubleArray(3)
            for (j in 0 until componentsY) {
                val cy = cos(PI * y * j / height)
                for (i in 0 until componentsX) {
                    val basis = cos(PI * x * i / width) * cy
                    val color = colors[j * componentsX + i]
                    for (c in 0..2) sum[c] += color[c] * basis
                }
            }
            OPAQUE or (toSrgb(sum[0]) shl 16) or (toSrgb(sum[1]) shl 8) or toSrgb(sum[2])
        }
    }

    /** The base-83 number written by `digits[from until to]`. */
    private fun IntArray.number(from: Int, to: Int): Int =
        (from until to).fold(0) { value, i -> value * BASE + this[i] }

    private fun base83(value: Int, length: Int): String {
        val out = CharArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            out[i] = CHARS[v % BASE]
            v /= BASE
        }
        return String(out)
    }

    private fun toLinear(value: Int): Double {
        val v = (value and 0xFF) / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        val s = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun signPow(value: Double, exponent: Double) = sign(value) * abs(value).pow(exponent)

    private const val OPAQUE = 0xFF shl 24
}
