package com.photoxt.core.render

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min

/**
 * Takes an image a strip of rows at a time, top to bottom: premultiplied RGBA bytes, [width]
 * pixels a row. Exports stream through one, so no full-size bitmap is ever held (spec §5.8:
 * no OOM on 200 MP images).
 */
interface RowSink : AutoCloseable {
    val width: Int
    val height: Int

    /** [rows] rows of [rgba] from its start. */
    fun write(rgba: ByteArray, rows: Int)

    /** Writes the rest of the file; call once every row is in. */
    fun finish()
}

/**
 * A baseline JPEG encoder that takes rows as they come (ITU T.81, with the standard Huffman
 * tables of its Annex K and IJG quality scaling). 4:2:0 chroma below quality 95, 4:4:4 from
 * there. Transparent pixels are laid over white.
 */
class JpegWriter(
    output: OutputStream,
    override val width: Int,
    override val height: Int,
    quality: Int
) : RowSink {
    private val out = BufferedOutputStream(output, BUFFER)
    private val subsample = quality < FULL_CHROMA_QUALITY
    private val mcu = if (subsample) MCU_420 else BLOCK
    private val lines = ByteArray(mcu * width * PIXEL)
    private var linesFilled = 0
    private var rowsDone = 0
    private val yTable = IntArray(BLOCK_SIZE)
    private val uvTable = IntArray(BLOCK_SIZE)
    private val fdtblY = FloatArray(BLOCK_SIZE)
    private val fdtblUv = FloatArray(BLOCK_SIZE)
    private val block = FloatArray(BLOCK_SIZE)
    private val coefficients = IntArray(BLOCK_SIZE)
    private val zigzagged = IntArray(BLOCK_SIZE)
    private val yBlocks = Array(QUAD) { FloatArray(BLOCK_SIZE) }
    private val cbBlock = FloatArray(BLOCK_SIZE)
    private val crBlock = FloatArray(BLOCK_SIZE)
    private var dcY = 0
    private var dcCb = 0
    private var dcCr = 0
    private var bits = 0L
    private var bitCount = 0

    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) {
            "JPEG holds at most 65,535 px a side"
        }
        scaleTables(quality.coerceIn(1, MAX_QUALITY))
        writeHeaders()
    }

    override fun write(rgba: ByteArray, rows: Int) {
        var row = 0
        while (row < rows) {
            val take = min(rows - row, mcu - linesFilled)
            val stride = width * PIXEL
            System.arraycopy(rgba, row * stride, lines, linesFilled * stride, take * stride)
            linesFilled += take
            row += take
            if (linesFilled == mcu) encodeLines()
        }
    }

    override fun finish() {
        if (linesFilled > 0) {
            // Repeat the last row down to a whole MCU.
            val stride = width * PIXEL
            val last = (linesFilled - 1) * stride
            for (r in linesFilled until mcu) {
                System.arraycopy(lines, last, lines, r * stride, stride)
            }
            linesFilled = mcu
            encodeLines()
        }
        check(rowsDone >= height) { "Rows missing" }
        if (bitCount > 0) writeBits((1L shl (BYTE - bitCount)) - 1, BYTE - bitCount)
        out.write(MARKER)
        out.write(EOI)
        out.flush()
    }

    override fun close() = out.close()

    private fun encodeLines() {
        var x = 0
        while (x < width) {
            if (subsample) {
                // 4:2:0: four luma blocks, then the 16×16 pixels' chroma averaged into one each.
                for (i in 0 until QUAD) readBlock(x + i % 2 * BLOCK, i / 2 * BLOCK, yBlocks[i])
                readChroma420(x)
                for (i in 0 until QUAD) dcY = encodeBlock(yBlocks[i], fdtblY, dcY, DC_Y, AC_Y)
            } else {
                readBlock(x, 0, yBlocks[0])
                dcY = encodeBlock(yBlocks[0], fdtblY, dcY, DC_Y, AC_Y)
            }
            dcCb = encodeBlock(cbBlock, fdtblUv, dcCb, DC_UV, AC_UV)
            dcCr = encodeBlock(crBlock, fdtblUv, dcCr, DC_UV, AC_UV)
            x += mcu
        }
        rowsDone += mcu
        linesFilled = 0
    }

    /** One 8×8 luma block at [x0], [y0] of the line buffer; for 4:4:4, its chroma too. */
    private fun readBlock(x0: Int, y0: Int, y: FloatArray) {
        for (j in 0 until BLOCK) {
            for (i in 0 until BLOCK) {
                val p = pixel(x0 + i, y0 + j)
                y[j * BLOCK + i] = luma(p) - CENTRE
                if (!subsample) {
                    cbBlock[j * BLOCK + i] = cb(p)
                    crBlock[j * BLOCK + i] = cr(p)
                }
            }
        }
    }

    private fun readChroma420(x0: Int) {
        for (j in 0 until BLOCK) {
            for (i in 0 until BLOCK) averageChroma(x0 + i * 2, j * 2, j * BLOCK + i)
        }
    }

    /** The chroma of the 2×2 pixels at [x], [y], averaged into cell [at] of the chroma blocks. */
    private fun averageChroma(x: Int, y: Int, at: Int) {
        var blue = 0f
        var red = 0f
        for (k in 0 until QUAD) {
            val p = pixel(x + k % 2, y + k / 2)
            blue += cb(p)
            red += cr(p)
        }
        cbBlock[at] = blue / QUAD
        crBlock[at] = red / QUAD
    }

    private val rgb = FloatArray(ALPHA)

    /** The pixel at [x], [y] of the line buffer, edges repeated, laid over white. */
    private fun pixel(x: Int, y: Int): FloatArray {
        val o = (y * width + min(x, width - 1)) * PIXEL
        val white = WHITE - (lines[o + ALPHA].toInt() and MASK)
        for (c in 0 until ALPHA) rgb[c] = ((lines[o + c].toInt() and MASK) + white).toFloat()
        return rgb
    }

    private fun encodeBlock(
        data: FloatArray,
        fdtbl: FloatArray,
        dc: Int,
        dcCode: Array<IntArray>,
        acCode: Array<IntArray>
    ): Int {
        System.arraycopy(data, 0, block, 0, BLOCK_SIZE)
        fdct(block)
        for (i in 0 until BLOCK_SIZE) {
            val v = block[i] * fdtbl[i]
            coefficients[i] = if (v > 0) (v + HALF).toInt() else (v - HALF).toInt()
        }
        for (i in 0 until BLOCK_SIZE) zigzagged[ZIGZAG[i]] = coefficients[i]
        val diff = zigzagged[0] - dc
        if (diff == 0) {
            code(dcCode[0])
        } else {
            val cat = category(diff)
            code(dcCode[cat])
            writeBits(bitcode(diff, cat).toLong(), cat)
        }
        var end = BLOCK_SIZE - 1
        while (end > 0 && zigzagged[end] == 0) end--
        if (end == 0) {
            code(acCode[0])
            return zigzagged[0]
        }
        var i = 1
        while (i <= end) {
            val start = i
            while (zigzagged[i] == 0 && i <= end) i++
            var zeroes = i - start
            while (zeroes >= RUN) {
                code(acCode[ZRL])
                zeroes -= RUN
            }
            val v = zigzagged[i]
            val cat = category(v)
            code(acCode[(zeroes shl RUN_BITS) + cat])
            writeBits(bitcode(v, cat).toLong(), cat)
            i++
        }
        if (end != BLOCK_SIZE - 1) code(acCode[0])
        return zigzagged[0]
    }

    private fun code(c: IntArray) = writeBits(c[0].toLong(), c[1])

    private fun writeBits(value: Long, length: Int) {
        bits = (bits shl length) or (value and ((1L shl length) - 1))
        bitCount += length
        while (bitCount >= BYTE) {
            val byte = ((bits shr (bitCount - BYTE)) and MASK.toLong()).toInt()
            out.write(byte)
            if (byte == MASK) out.write(0)
            bitCount -= BYTE
        }
        bits = bits and ((1L shl bitCount) - 1)
    }

    /** IJG's quality scaling (libjpeg's jcparam.c), then the divisors of the AAN DCT. */
    @Suppress("MagicNumber")
    private fun scaleTables(quality: Int) {
        val scale = if (quality < 50) 5000 / quality else 200 - quality * 2
        for (i in 0 until BLOCK_SIZE) {
            yTable[ZIGZAG[i]] = ((Q_LUMA[i] * scale + 50) / 100).coerceIn(1, MASK)
            uvTable[ZIGZAG[i]] = ((Q_CHROMA[i] * scale + 50) / 100).coerceIn(1, MASK)
        }
        var k = 0
        for (row in 0 until BLOCK) {
            for (col in 0 until BLOCK) {
                fdtblY[k] = 1f / (yTable[ZIGZAG[k]] * AAN[row] * AAN[col] * BLOCK)
                fdtblUv[k] = 1f / (uvTable[ZIGZAG[k]] * AAN[row] * AAN[col] * BLOCK)
                k++
            }
        }
    }

    /** The JFIF header, tables, frame and scan markers, as ITU T.81 Annex B lays them out. */
    @Suppress("MagicNumber")
    private fun writeHeaders() {
        val d = DataOutputStream(out)
        d.write(byteArrayOf(-1, SOI.toByte()))
        // JFIF APP0.
        d.write(byteArrayOf(-1, APP0.toByte()))
        d.writeShort(JFIF_LENGTH)
        d.write("JFIF".toByteArray())
        d.write(byteArrayOf(0, 1, 1, 0))
        d.writeShort(1)
        d.writeShort(1)
        d.write(byteArrayOf(0, 0))
        // Quantization tables, in zigzag order.
        d.write(byteArrayOf(-1, DQT.toByte()))
        d.writeShort(DQT_LENGTH)
        d.write(0)
        yTable.forEach { d.write(it) }
        d.write(1)
        uvTable.forEach { d.write(it) }
        // Frame.
        d.write(byteArrayOf(-1, SOF0.toByte()))
        d.writeShort(SOF_LENGTH)
        d.write(BYTE)
        d.writeShort(height)
        d.writeShort(width)
        d.write(3)
        d.write(byteArrayOf(1, if (subsample) SAMPLING_420.toByte() else SAMPLING_444.toByte(), 0))
        d.write(byteArrayOf(2, SAMPLING_444.toByte(), 1))
        d.write(byteArrayOf(3, SAMPLING_444.toByte(), 1))
        // Huffman tables.
        d.write(byteArrayOf(-1, DHT.toByte()))
        d.writeShort(DHT_LENGTH)
        for ((id, counts, values) in TABLES) {
            d.write(id)
            for (k in 1..MAX_CODE_LENGTH) d.write(counts[k])
            values.forEach { d.write(it) }
        }
        // Scan.
        d.write(byteArrayOf(-1, SOS.toByte()))
        d.writeShort(SOS_LENGTH)
        d.write(3)
        d.write(byteArrayOf(1, 0, 2, SAMPLING_444.toByte(), 3, SAMPLING_444.toByte()))
        d.write(byteArrayOf(0, (BLOCK_SIZE - 1).toByte(), 0))
        d.flush()
    }

    @Suppress("MagicNumber")
    private companion object {
        const val BUFFER = 1 shl 16
        const val BLOCK = 8
        const val BLOCK_SIZE = 64
        const val MCU_420 = 16
        const val MAX_SIDE = 65_535
        const val MAX_QUALITY = 100
        const val FULL_CHROMA_QUALITY = 95
        const val CENTRE = 128f
        const val WHITE = 255
        const val MASK = 0xFF
        const val BYTE = 8
        const val HALF = 0.5f
        const val RUN = 16
        const val RUN_BITS = 4
        const val ZRL = 0xF0
        const val MAX_CODE_LENGTH = 16
        const val MARKER = 0xFF
        const val SOI = 0xD8
        const val EOI = 0xD9
        const val APP0 = 0xE0
        const val DQT = 0xDB
        const val SOF0 = 0xC0
        const val DHT = 0xC4
        const val SOS = 0xDA
        const val JFIF_LENGTH = 16
        const val DQT_LENGTH = 132
        const val SOF_LENGTH = 17
        const val DHT_LENGTH = 418
        const val SOS_LENGTH = 12
        const val SAMPLING_420 = 0x22
        const val SAMPLING_444 = 0x11

        val ZIGZAG = intArrayOf(
            0, 1, 5, 6, 14, 15, 27, 28, 2, 4, 7, 13, 16, 26, 29, 42,
            3, 8, 12, 17, 25, 30, 41, 43, 9, 11, 18, 24, 31, 40, 44, 53,
            10, 19, 23, 32, 39, 45, 52, 54, 20, 22, 33, 38, 46, 51, 55, 60,
            21, 34, 37, 47, 50, 56, 59, 61, 35, 36, 48, 49, 57, 58, 62, 63
        )

        val Q_LUMA = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99
        )

        val Q_CHROMA = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99
        )

        val AAN =
            floatArrayOf(
                1.0f,
                1.387039845f,
                1.306562965f,
                1.175875602f,
                1.0f,
                0.785694958f,
                0.5411961f,
                0.275899379f
            )

        val DC_COUNTS_Y = intArrayOf(0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        val DC_COUNTS_UV = intArrayOf(0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        val DC_VALUES = IntArray(12) { it }
        val AC_COUNTS_Y = intArrayOf(0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        val AC_COUNTS_UV = intArrayOf(0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        val AC_VALUES_Y = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51,
            0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1,
            0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18,
            0x19, 0x1a, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39,
            0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57,
            0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75,
            0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92,
            0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8,
            0xd9, 0xda, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2,
            0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa
        )
        val AC_VALUES_UV = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07,
            0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09,
            0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25,
            0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38,
            0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56,
            0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74,
            0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba,
            0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6,
            0xd7, 0xd8, 0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2,
            0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa
        )

        val TABLES = listOf(
            Triple(0x00, DC_COUNTS_Y, DC_VALUES),
            Triple(0x10, AC_COUNTS_Y, AC_VALUES_Y),
            Triple(0x01, DC_COUNTS_UV, DC_VALUES),
            Triple(0x11, AC_COUNTS_UV, AC_VALUES_UV)
        )

        val DC_Y = huffman(DC_COUNTS_Y, DC_VALUES)
        val AC_Y = huffman(AC_COUNTS_Y, AC_VALUES_Y)
        val DC_UV = huffman(DC_COUNTS_UV, DC_VALUES)
        val AC_UV = huffman(AC_COUNTS_UV, AC_VALUES_UV)

        /** Code and length for each symbol of a table given by its counts per length. */
        fun huffman(counts: IntArray, values: IntArray): Array<IntArray> {
            val table = Array(256) { intArrayOf(0, 0) }
            var code = 0
            var pos = 0
            for (length in 1..MAX_CODE_LENGTH) {
                repeat(counts[length]) {
                    table[values[pos++]] = intArrayOf(code, length)
                    code++
                }
                code *= 2
            }
            return table
        }

        // JFIF's RGB to YCbCr (ITU-R BT.601, full range), without the +128 the DCT takes off.
        fun luma(p: FloatArray) = 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]

        fun cb(p: FloatArray) = -0.168736f * p[0] - 0.331264f * p[1] + 0.5f * p[2]

        fun cr(p: FloatArray) = 0.5f * p[0] - 0.418688f * p[1] - 0.081312f * p[2]

        /** How many bits the magnitude of [v] takes (its JPEG category). */
        fun category(v: Int): Int {
            var a = if (v < 0) -v else v
            var n = 0
            while (a > 0) {
                a = a shr 1
                n++
            }
            return n
        }

        /** The bits that follow [v]'s category: [v] itself, or for negatives v + 2^cat − 1. */
        fun bitcode(v: Int, cat: Int): Int = if (v >= 0) v else v + (1 shl cat) - 1

        /** The forward DCT of one block in place (Arai, Agui and Nakajima, float). */
        fun fdct(d: FloatArray) {
            for (pass in 0 until 2) {
                val step = if (pass == 0) 1 else BLOCK
                val stride = if (pass == 0) BLOCK else 1
                for (line in 0 until BLOCK) {
                    val o = line * stride
                    val d0 = d[o]
                    val d1 = d[o + step]
                    val d2 = d[o + 2 * step]
                    val d3 = d[o + 3 * step]
                    val d4 = d[o + 4 * step]
                    val d5 = d[o + 5 * step]
                    val d6 = d[o + 6 * step]
                    val d7 = d[o + 7 * step]
                    val tmp0 = d0 + d7
                    val tmp7 = d0 - d7
                    val tmp1 = d1 + d6
                    val tmp6 = d1 - d6
                    val tmp2 = d2 + d5
                    val tmp5 = d2 - d5
                    val tmp3 = d3 + d4
                    val tmp4 = d3 - d4
                    var tmp10 = tmp0 + tmp3
                    val tmp13 = tmp0 - tmp3
                    var tmp11 = tmp1 + tmp2
                    var tmp12 = tmp1 - tmp2
                    d[o] = tmp10 + tmp11
                    d[o + 4 * step] = tmp10 - tmp11
                    val z1 = (tmp12 + tmp13) * 0.707106781f
                    d[o + 2 * step] = tmp13 + z1
                    d[o + 6 * step] = tmp13 - z1
                    tmp10 = tmp4 + tmp5
                    tmp11 = tmp5 + tmp6
                    tmp12 = tmp6 + tmp7
                    val z5 = (tmp10 - tmp12) * 0.382683433f
                    val z2 = 0.5411961f * tmp10 + z5
                    val z4 = 1.306562965f * tmp12 + z5
                    val z3 = tmp11 * 0.707106781f
                    val z11 = tmp7 + z3
                    val z13 = tmp7 - z3
                    d[o + 5 * step] = z13 + z2
                    d[o + 3 * step] = z13 - z2
                    d[o + step] = z11 + z4
                    d[o + 7 * step] = z11 - z4
                }
            }
        }
    }
}

/**
 * A PNG encoder that takes rows as they come: 8-bit RGB, or RGBA when [alpha], Sub-filtered,
 * deflated into 64 KB IDAT chunks.
 */
class PngWriter(
    output: OutputStream,
    override val width: Int,
    override val height: Int,
    private val alpha: Boolean
) : RowSink {
    private val out = DataOutputStream(BufferedOutputStream(output, CHUNK))
    private val deflater = Deflater(LEVEL)
    private val channels = if (alpha) PIXEL else ALPHA
    private val row = ByteArray(1 + width * channels)
    private val buffer = ByteArray(CHUNK)
    private var rowsDone = 0

    init {
        out.write(SIGNATURE)
        val header = java.io.ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width)
            writeInt(height)
            write(DEPTH)
            write(if (alpha) RGBA else RGB)
            write(byteArrayOf(0, 0, 0))
        }
        chunk("IHDR", header.toByteArray())
    }

    override fun write(rgba: ByteArray, rows: Int) {
        for (r in 0 until rows) {
            row[0] = SUB
            val o = r * width * PIXEL
            for (x in 0 until width) straight(rgba, o + x * PIXEL, 1 + x * channels)
            // Sub filter: each byte minus the same channel of the pixel to its left.
            for (i in row.size - 1 downTo 1 + channels) {
                row[i] = (row[i] - row[i - channels]).toByte()
            }
            deflater.setInput(row)
            drain(Deflater.NO_FLUSH)
            rowsDone++
        }
    }

    /**
     * The pixel at [from] of [rgba] into the row at [to]. Rows come premultiplied; PNG stores
     * straight alpha, and without alpha the pixel is laid over white.
     */
    private fun straight(rgba: ByteArray, from: Int, to: Int) {
        val a = rgba[from + ALPHA].toInt() and MASK
        for (c in 0 until channels) {
            var v = rgba[from + c].toInt() and MASK
            if (alpha && c < ALPHA && a in 1 until MASK) v = min(MASK, v * MASK / a)
            if (!alpha && c < ALPHA && a < MASK) v += MASK - a
            row[to + c] = v.toByte()
        }
    }

    override fun finish() {
        check(rowsDone >= height) { "Rows missing" }
        deflater.finish()
        drain(Deflater.NO_FLUSH)
        chunk("IEND", ByteArray(0))
        out.flush()
    }

    override fun close() {
        deflater.end()
        out.close()
    }

    private fun drain(flush: Int) {
        while (true) {
            val n = deflater.deflate(buffer, 0, buffer.size, flush)
            if (n > 0) chunk("IDAT", buffer.copyOf(n))
            if (n == 0 && (deflater.needsInput() || deflater.finished())) return
        }
    }

    private fun chunk(type: String, data: ByteArray) {
        out.writeInt(data.size)
        val name = type.toByteArray()
        out.write(name)
        out.write(data)
        val crc = CRC32()
        crc.update(name)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    private companion object {
        const val CHUNK = 1 shl 16
        const val LEVEL = 6
        const val DEPTH = 8
        const val RGB = 2
        const val RGBA = 6
        const val SUB: Byte = 1
        const val MASK = 0xFF
        val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}

/** Bytes of an RGBA pixel as the rows come in. */
private const val PIXEL = 4

/** Where the alpha byte sits in such a pixel, after its three colours. */
private const val ALPHA = 3

/** The four luma blocks, or the four pixels behind one chroma sample, of 4:2:0. */
private const val QUAD = 4

/** The biggest square tile side under [limit] that the GPU takes. */
internal fun tileSide(maxTexture: Int, limit: Int): Int = max(1, min(maxTexture, limit))
