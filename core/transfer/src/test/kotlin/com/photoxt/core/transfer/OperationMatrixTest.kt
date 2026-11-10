package com.photoxt.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.model.ClipboardMode
import com.photoxt.core.model.TransferState
import com.photoxt.core.transfer.Harness.Companion.PRIMARY
import com.photoxt.core.transfer.Harness.Companion.SD
import com.photoxt.core.transfer.Harness.Companion.bytes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every cell of the operation matrix (spec §4.7) against the fake Drive and a fake MediaStore,
 * through the clipboard as a user would: cut or copy, paste into an album, run the queue.
 */
@RunWith(AndroidJUnit4::class)
class OperationMatrixTest {
    private val h = Harness()
    private val photo = bytes(300_000)

    @After
    fun tearDown() = h.close()

    private suspend fun paste(mode: ClipboardMode, item: String, album: String) {
        h.transfers.clip(mode, listOf(item))
        assertTrue(h.transfers.paste(album).isSuccess)
        h.drain()
        assertEquals(listOf(TransferState.DONE), h.dao.jobs(1).map { it.state })
    }

    @Test
    fun phoneToPhoneCopyStreamsThroughMediaStore() = runTest {
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")

        paste(ClipboardMode.COPY, item, trip)

        assertArrayEquals(photo, h.device.live("Pictures/Trip/").single().bytes)
        assertEquals(1, h.device.live("DCIM/Camera/").size)
    }

    @Test
    fun phoneToPhoneCutOnOneVolumeMovesTheFile() = runTest {
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")

        paste(ClipboardMode.CUT, item, trip)

        // The same MediaStore item, now in the other folder: nothing copied.
        assertEquals(1, h.device.items.size)
        assertEquals("Pictures/Trip/", h.device.items.values.single().path)
    }

    @Test
    fun phoneToPhoneCutAcrossVolumesCopiesVerifiesThenTrashes() = runTest {
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        val sd = h.phoneAlbum(SD, "Pictures/")

        paste(ClipboardMode.CUT, item, sd)

        assertArrayEquals(photo, h.device.live("Pictures/").single().bytes)
        assertEquals(SD, h.device.live("Pictures/").single().volume)
        assertTrue(h.device.items.values.single { it.volume == PRIMARY }.trashed)
    }

    @Test
    fun phoneToDriveCopyUploads() = runTest {
        h.account("a")
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        val album = h.driveAlbum("a", "f1", "Holidays")

        paste(ClipboardMode.COPY, item, album)

        val uploaded = h.drive.inFolder("a", "f1").single()
        assertArrayEquals(photo, uploaded.content)
        assertEquals("a.jpg", uploaded.name)
        assertFalse(h.device.items.values.single().trashed)
        assertTrue("/upload/drive/v3/files" in h.drive.requests.map { it.substringAfter(' ') })
    }

    @Test
    fun phoneToDriveCutUploadsVerifiesThenTrashesTheOriginal() = runTest {
        h.account("a")
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        val album = h.driveAlbum("a", "f1", "Holidays")

        paste(ClipboardMode.CUT, item, album)

        assertArrayEquals(photo, h.drive.inFolder("a", "f1").single().content)
        assertTrue(h.device.items.values.single().trashed)
    }

    @Test
    fun driveToPhoneCopyDownloadsIntoMediaStore() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")

        paste(ClipboardMode.COPY, item, trip)

        assertArrayEquals(photo, h.device.live("Pictures/Trip/").single().bytes)
        assertFalse(h.drive.files.getValue("x1").trashed)
    }

    @Test
    fun driveToPhoneCutDownloadsVerifiesThenBinsInDrive() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")

        paste(ClipboardMode.CUT, item, trip)

        assertArrayEquals(photo, h.device.live("Pictures/Trip/").single().bytes)
        assertTrue(h.drive.files.getValue("x1").trashed)
    }

    @Test
    fun driveToDriveCopyInOneAccountIsServerSide() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val other = h.driveAlbum("a", "f2", "Best")

        paste(ClipboardMode.COPY, item, other)

        assertArrayEquals(photo, h.drive.inFolder("a", "f2").single().content)
        assertTrue("POST /drive/v3/files/x1/copy" in h.drive.requests)
        // Nothing went through the phone.
        assertEquals(emptyList<String?>(), h.drive.ranges.toList())
        assertEquals(0L, h.drive.uploadedBytes.get())
    }

    @Test
    fun driveToDriveCutInOneAccountUpdatesParents() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val other = h.driveAlbum("a", "f2", "Best")

        paste(ClipboardMode.CUT, item, other)

        assertEquals(listOf("f2"), h.drive.files.getValue("x1").parents)
        assertEquals(1, h.drive.files.values.count { !it.mime.contains("folder") })
        assertEquals(0L, h.drive.uploadedBytes.get())
    }

    @Test
    fun driveToDriveCopyAcrossAccountsStreamsDownloadIntoUpload() = runTest {
        h.account("a")
        h.account("b")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val other = h.driveAlbum("b", "g1", "Family")

        paste(ClipboardMode.COPY, item, other)

        val copy = h.drive.inFolder("b", "g1").single()
        assertArrayEquals(photo, copy.content)
        assertEquals("b", copy.account)
        assertFalse(h.drive.files.getValue("x1").trashed)
        assertEquals(photo.size.toLong(), h.drive.uploadedBytes.get())
    }

    @Test
    fun driveToDriveCutAcrossAccountsVerifiesThenBinsTheSource() = runTest {
        h.account("a")
        h.account("b")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val other = h.driveAlbum("b", "g1", "Family")

        paste(ClipboardMode.CUT, item, other)

        assertArrayEquals(photo, h.drive.inFolder("b", "g1").single().content)
        assertTrue(h.drive.files.getValue("x1").trashed)
    }

    @Test
    fun aDriveAlbumPastesOntoThePhoneWithItsSubfolders() = runTest {
        h.account("a")
        val root = h.driveAlbum("a", "f1", "Holidays")
        val sub = h.driveSubAlbum("a", root, "f1", "f9", "Day 1")
        h.driveItem("a", root, "f1", "x1", "top.jpg", photo)
        h.driveItem("a", sub, "f9", "x2", "inner.jpg", bytes(1000, 3))
        val pictures = h.phoneAlbum(PRIMARY, "Pictures/")

        h.transfers.clip(ClipboardMode.COPY, emptyList(), listOf(root))
        assertTrue(h.transfers.paste(pictures).isSuccess)
        h.drain()

        assertEquals("top.jpg", h.device.live("Pictures/Holidays/").single().name)
        assertEquals("inner.jpg", h.device.live("Pictures/Holidays/Day 1/").single().name)
    }

    @Test
    fun aPhoneAlbumPastesIntoDriveIntoFoldersMadeFirst() = runTest {
        h.account("b")
        h.phoneItem(PRIMARY, "Pictures/Trip/", "top.jpg", photo)
        h.phoneItem(PRIMARY, "Pictures/Trip/Day 1/", "inner.jpg", bytes(1000, 3))
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        val family = h.driveAlbum("b", "g1", "Family")

        h.transfers.clip(ClipboardMode.COPY, emptyList(), listOf(trip))
        assertTrue(h.transfers.paste(family).isSuccess)
        h.drain()

        val tripFolder = h.drive.inFolder("b", "g1").single()
        assertEquals("Trip", tripFolder.name)
        val inTrip = h.drive.inFolder("b", tripFolder.id)
        assertEquals(setOf("top.jpg", "Day 1"), inTrip.map { it.name }.toSet())
        val day = inTrip.single { it.name == "Day 1" }
        assertEquals("inner.jpg", h.drive.inFolder("b", day.id).single().name)
        assertTrue(h.dao.jobs(1).all { it.state == TransferState.DONE })
    }

    @Test
    fun anAlbumCannotBePastedIntoItself() = runTest {
        h.phoneItem(PRIMARY, "Pictures/Trip/", "top.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        val inner = h.phoneAlbum(PRIMARY, "Pictures/Trip/Day 1/")

        h.transfers.clip(ClipboardMode.CUT, emptyList(), listOf(trip))

        val problem = (h.transfers.paste(inner).exceptionOrNull() as PasteException).problem
        assertEquals(PasteProblem.INTO_ITSELF, problem)
        assertNull(h.dao.batch(1))
    }
}
