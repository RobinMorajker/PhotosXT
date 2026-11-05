package com.photoxt.core.media

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Motion photo, photo sphere and subtitle metadata, parsed from bytes and text. */
class ImageTraitsTest {
    @Test
    fun findsTheXmpPacketInAFileHead() {
        val head = byteArrayOf(-1, -40) + "junk<x:xmpmeta a='1'>body</x:xmpmeta>tail".toByteArray()
        assertEquals("<x:xmpmeta a='1'>body</x:xmpmeta>", findXmp(head))
        assertNull(findXmp("<x:xmpmeta never closes".toByteArray()))
    }

    @Test
    fun motionPhotoFormatPutsTheVideoAtTheEndOfTheFile() {
        val xmp = """
            <x:xmpmeta><rdf:Description Camera:MotionPhoto="1" Camera:MotionPhotoVersion="1">
            <Container:Directory><rdf:Seq>
            <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg"
                Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
            <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4"
                Item:Semantic="MotionPhoto" Item:Length="4000" Item:Padding="0"/></rdf:li>
            </rdf:Seq></Container:Directory></rdf:Description></x:xmpmeta>
        """.trimIndent()
        assertEquals(EmbeddedVideo(offset = 6_000, length = 4_000), motionVideoFromXmp(xmp, 10_000))
    }

    @Test
    fun motionPhotoVideoCanSitBeforeAGainMap() {
        val xmp = """<rdf:Description GCamera:MotionPhoto="1">
            <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
            <Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="300"/>
            <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="200"/>
            </rdf:Description>"""
        assertEquals(EmbeddedVideo(offset = 500, length = 300), motionVideoFromXmp(xmp, 1_000))
    }

    @Test
    fun olderPixelMicroVideoCountsFromTheEnd() {
        val xmp = """<rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="2500"/>"""
        assertEquals(EmbeddedVideo(offset = 7_500, length = 2_500), motionVideoFromXmp(xmp, 10_000))
        assertNull(motionVideoFromXmp("""<rdf:Description GCamera:MicroVideo="0"/>""", 10_000))
        assertNull(motionVideoFromXmp("""<rdf:Description Camera:MotionPhoto="1"/>""", 10_000))
    }

    @Test
    fun samsungTrailerPointsAtTheMotionPhotoBlock() {
        val jpeg = ByteArray(1_000) { 7 }
        val video = ByteArray(500) { 9 }
        val file = samsungFile(jpeg, video)
        val read = { position: Long, count: Int ->
            file.copyOfRange(position.toInt(), minOf(file.size, position.toInt() + count))
        }
        val found = samsungMotionVideo(file.size.toLong(), read)
        val blockHead = 8 + "MotionPhoto_Data".length
        assertEquals(EmbeddedVideo(offset = (1_000 + blockHead).toLong(), length = 500), found)
        assertNull(
            samsungMotionVideo(jpeg.size.toLong()) { p, n ->
                jpeg.copyOfRange(
                    p.toInt(),
                    p.toInt() + n
                )
            }
        )
    }

    @Test
    fun photoSphereKeepsItsCropAsFractions() {
        val full = """<rdf:Description GPano:ProjectionType="equirectangular"/>"""
        assertEquals(Panorama(), panoramaFromXmp(full))
        val cropped = """<rdf:Description><GPano:ProjectionType>equirectangular</GPano:ProjectionType>
            <GPano:FullPanoWidthPixels>8000</GPano:FullPanoWidthPixels>
            <GPano:FullPanoHeightPixels>4000</GPano:FullPanoHeightPixels>
            <GPano:CroppedAreaImageWidthPixels>8000</GPano:CroppedAreaImageWidthPixels>
            <GPano:CroppedAreaImageHeightPixels>2000</GPano:CroppedAreaImageHeightPixels>
            <GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels>
            <GPano:CroppedAreaTopPixels>1000</GPano:CroppedAreaTopPixels></rdf:Description>"""
        assertEquals(Panorama(0f, 0.25f, 1f, 0.5f), panoramaFromXmp(cropped))
        assertNull(panoramaFromXmp("""<rdf:Description GPano:ProjectionType="cylindrical"/>"""))
    }

    @Test
    fun subtitlesMatchTheVideoNameAndKeepTheirLanguage() {
        assertEquals(
            SubtitleFile("content://1", "application/x-subrip", null),
            subtitleFor("clip", "clip.srt", "content://1")
        )
        assertEquals(
            SubtitleFile("content://2", "text/vtt", "en"),
            subtitleFor("clip", "clip.en.VTT", "content://2")
        )
        assertNull(subtitleFor("clip", "clip.txt", "content://3"))
        assertNull(subtitleFor("clip", "clip2.srt", "content://4"))
    }

    @Test
    fun videoLocationsAreIso6709() {
        assertEquals(GeoPoint(15.4909, 73.8278), parseIso6709("+15.4909+073.8278/"))
        assertEquals(GeoPoint(37.422, -122.0841), parseIso6709("+37.4220-122.0841+012.000/"))
        assertNull(parseIso6709("+00.0000+000.0000/"))
        assertNull(parseIso6709("nowhere"))
    }

    /** A JPEG followed by Samsung's trailer with one MotionPhoto_Data block. */
    private fun samsungFile(jpeg: ByteArray, video: ByteArray): ByteArray {
        val name = "MotionPhoto_Data".toByteArray()
        val block = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0x30, 0x0A))
            write(le(name.size))
            write(name)
            write(video)
        }.toByteArray()
        val directory = ByteArrayOutputStream().apply {
            write("SEFH".toByteArray())
            write(le(MOTION_VERSION))
            write(le(1))
            write(byteArrayOf(0, 0, 0x30, 0x0A))
            write(le(block.size))
            write(le(block.size))
        }.toByteArray()
        return jpeg + block + directory + le(directory.size) + "SEFT".toByteArray()
    }

    private fun le(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private companion object {
        const val MOTION_VERSION = 106
    }
}
