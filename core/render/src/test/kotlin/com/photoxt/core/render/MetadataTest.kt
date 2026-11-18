package com.photoxt.core.render

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The EXIF block HEIC and AVIF copies carry says their own size, not its 1×1 carrier's. */
@RunWith(RobolectricTestRunner::class)
class MetadataTest {
    @Test
    fun theBlockCarriesTheExportsSize() {
        val dir = Files.createTempDirectory("exif").toFile()
        val photo = dir.resolve("photo.jpg")
        photo.writeBytes(
            ByteArrayOutputStream().also { out ->
                JpegWriter(out, 2, 2, 90).use {
                    it.write(ByteArray(2 * 2 * 4) { -1 }, 2)
                    it.finish()
                }
            }.toByteArray()
        )
        ExifInterface(photo).apply {
            setAttribute(ExifInterface.TAG_MAKE, "PhotoXT")
            saveAttributes()
        }

        val block = checkNotNull(Metadata.block(photo, Size(1536, 1024), dir))

        val tags = ifd0(block)
        assertEquals(1536, tags[ExifTag.WIDTH])
        assertEquals(1024, tags[ExifTag.LENGTH])
        dir.deleteRecursively()
    }

    /** IFD0's numeric tags of an "Exif\0\0"-prefixed TIFF block. */
    private fun ifd0(block: ByteArray): Map<Int, Int> {
        val tiff = ByteBuffer.wrap(block, ExifTag.PREFIX, block.size - ExifTag.PREFIX).slice()
        tiff.order(
            if (tiff.get(0).toInt() ==
                'I'.code
            ) {
                ByteOrder.LITTLE_ENDIAN
            } else {
                ByteOrder.BIG_ENDIAN
            }
        )
        val start = tiff.getInt(ExifTag.IFD_OFFSET)
        val count = tiff.getShort(start).toInt()
        return (0 until count).associate { n ->
            val entry = start + 2 + n * ExifTag.ENTRY
            val type = tiff.getShort(entry + 2).toInt()
            val value = if (type == ExifTag.SHORT) {
                tiff.getShort(entry + ExifTag.VALUE).toInt() and 0xFFFF
            } else {
                tiff.getInt(entry + ExifTag.VALUE)
            }
            (tiff.getShort(entry).toInt() and 0xFFFF) to value
        }
    }

    private object ExifTag {
        const val PREFIX = 6
        const val IFD_OFFSET = 4
        const val ENTRY = 12
        const val VALUE = 8
        const val SHORT = 3
        const val WIDTH = 0x0100
        const val LENGTH = 0x0101
    }
}
