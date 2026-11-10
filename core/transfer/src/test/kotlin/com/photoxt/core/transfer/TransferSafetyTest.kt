package com.photoxt.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.drive.DriveUploads
import com.photoxt.core.model.ClipboardMode
import com.photoxt.core.model.ConflictPolicy
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import com.photoxt.core.transfer.Harness.Companion.PRIMARY
import com.photoxt.core.transfer.Harness.Companion.bytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transfer engine's promises (spec §0 rule 4, §4.7): a killed transfer resumes where it
 * stopped, a cut never deletes its source when the copy fails its check, conflicts wait for an
 * answer, Undo takes moves and deletes back, and MediaStore's consent is asked for, not assumed.
 */
@RunWith(AndroidJUnit4::class)
class TransferSafetyTest {
    private val h = Harness()

    @After
    fun tearDown() = h.close()

    @Test
    fun killingTheAppMidUploadResumesFromWhatDriveHolds() = runTest {
        h.account("a")
        val big = bytes(2 * DriveUploads.CHUNK + 1_000_000)
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "clip.mp4", big)
        val album = h.driveAlbum("a", "f1", "Holidays")
        h.drive.holdChunk = 2
        h.transfers.clip(ClipboardMode.CUT, listOf(item))
        h.transfers.paste(album)

        // The process dies while the second chunk is on its way.
        val running = launch(Dispatchers.IO) { h.drain() }
        withContext(Dispatchers.IO) { withTimeout(TIMEOUT_MS) { h.drive.held.await() } }
        running.cancelAndJoin()
        h.drive.releaseHeldChunk()
        assertEquals(TransferState.RUNNING, h.dao.jobs(1).single().state)
        assertFalse(h.device.items.values.single().trashed)

        // The next start runs a new engine, which asks Drive where the upload stands.
        h.engine = h.newEngine()
        h.drain()

        val uploaded = h.drive.inFolder("a", "f1").single()
        assertArrayEquals(big, uploaded.content)
        // Only the first chunk was sent twice at most: nothing started over.
        assertEquals(big.size.toLong(), h.drive.uploadedBytes.get())
        assertEquals(1, h.drive.requests.count { it == "POST /upload/drive/v3/files" })
        assertTrue(h.device.items.values.single().trashed)
        assertEquals(TransferState.DONE, h.dao.jobs(1).single().state)
    }

    @Test
    fun aKilledDownloadResumesWithARangeRequest() = runTest {
        h.account("a")
        val photo = bytes(500_000)
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        h.transfers.clip(ClipboardMode.COPY, listOf(item))
        h.transfers.paste(trip)
        // What a killed run left: the first 200,000 bytes, and the job still running.
        val job = h.dao.jobs(1).single()
        File(h.env.workDir, "${job.id}.part").apply {
            parentFile!!.mkdirs()
            writeBytes(photo.copyOf(200_000))
        }
        h.dao.claim(job.id, 0)

        h.drain()

        assertArrayEquals(photo, h.device.live("Pictures/Trip/").single().bytes)
        assertEquals(listOf<String?>("bytes=200000-"), h.drive.ranges.toList())
        assertEquals(TransferState.DONE, h.dao.jobs(1).single().state)
    }

    @Test
    fun aCutNeverDeletesItsSourceWhenTheUploadFailsItsCheck() = runTest {
        h.account("a")
        val photo = bytes(300_000)
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        val album = h.driveAlbum("a", "f1", "Holidays")
        h.drive.corruptUploads = true
        h.transfers.clip(ClipboardMode.CUT, listOf(item))
        h.transfers.paste(album)

        retryUntilSettled()

        val job = h.dao.jobs(1).single()
        assertEquals(TransferState.FAILED, job.state)
        assertFalse(h.device.items.values.single().trashed)
        // Every bad copy was deleted again.
        assertEquals(emptyList<FakeDrive.File>(), h.drive.inFolder("a", "f1"))
    }

    @Test
    fun aCutNeverDeletesItsSourceWhenThePhoneCopyFailsItsCheck() = runTest {
        h.account("a")
        val photo = bytes(300_000)
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        h.device.corruptWrites = true
        h.transfers.clip(ClipboardMode.CUT, listOf(item))
        h.transfers.paste(trip)

        retryUntilSettled()

        assertEquals(TransferState.FAILED, h.dao.jobs(1).single().state)
        assertFalse(h.drive.files.getValue("x1").trashed)
        assertEquals(
            emptyList<FakeDevice.Item>(),
            h.device.items.values.filter {
                it.path ==
                    "Pictures/Trip/"
            }
        )
    }

    @Test
    fun aCutNeverDeletesItsSourceWhenADriveCopyFailsItsCheck() = runTest {
        h.account("a")
        h.account("b")
        val photo = bytes(300_000)
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val family = h.driveAlbum("b", "g1", "Family")
        h.drive.corruptUploads = true
        h.transfers.clip(ClipboardMode.CUT, listOf(item))
        h.transfers.paste(family)

        retryUntilSettled()

        assertEquals(TransferState.FAILED, h.dao.jobs(1).single().state)
        assertFalse(h.drive.files.getValue("x1").trashed)
        assertEquals(emptyList<FakeDrive.File>(), h.drive.inFolder("b", "g1"))
    }

    @Test
    fun aNameConflictWaitsAndApplyToAllAnswersTheRest() = runTest {
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        h.device.add(PRIMARY, "Pictures/Trip/", "a.jpg", bytes(10))
        h.device.add(PRIMARY, "Pictures/Trip/", "b.jpg", bytes(10))
        val a = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(100))
        val b = h.phoneItem(PRIMARY, "DCIM/Camera/", "b.jpg", bytes(200))
        h.transfers.clip(ClipboardMode.COPY, listOf(a, b))
        h.transfers.paste(trip)

        h.drain()
        val waiting = h.dao.jobs(1)
        assertEquals(listOf(WaitFor.CONFLICT, WaitFor.CONFLICT), waiting.map { it.waitFor })

        h.queue.resolveConflict(waiting.first().id, ConflictPolicy.KEEP_BOTH, applyToAll = true)
        h.drain()

        assertEquals(
            setOf("a.jpg", "b.jpg", "a (1).jpg", "b (1).jpg"),
            h.device.live("Pictures/Trip/").map { it.name }.toSet()
        )
    }

    @Test
    fun replaceBinsTheOldItemAndSkipLeavesBoth() = runTest {
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        val old = h.device.add(PRIMARY, "Pictures/Trip/", "a.jpg", bytes(10)).also {
            h.device.granted +=
                it
        }
        h.device.add(PRIMARY, "Pictures/Trip/", "b.jpg", bytes(10))
        val a = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(100))
        val b = h.phoneItem(PRIMARY, "DCIM/Camera/", "b.jpg", bytes(200))
        h.transfers.clip(ClipboardMode.COPY, listOf(a, b))
        h.transfers.paste(trip)
        h.drain()
        val (first, second) = h.dao.jobs(1)

        h.queue.resolveConflict(first.id, ConflictPolicy.REPLACE, applyToAll = false)
        h.queue.resolveConflict(second.id, ConflictPolicy.SKIP, applyToAll = false)
        h.drain()

        assertTrue(h.device.items.getValue(old).trashed)
        assertArrayEquals(
            bytes(100),
            h.device.live("Pictures/Trip/").single {
                it.name == "a.jpg"
            }.bytes
        )
        assertEquals(TransferState.SKIPPED, h.dao.job(second.id)?.state)
        assertArrayEquals(
            bytes(10),
            h.device.live("Pictures/Trip/").single {
                it.name == "b.jpg"
            }.bytes
        )
    }

    @Test
    fun undoTakesAMoveAndADeleteBack() = runTest {
        h.account("a")
        val photo = bytes(1000)
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        h.transfers.clip(ClipboardMode.CUT, listOf(item))
        val moved = h.transfers.paste(trip).getOrThrow()
        h.drain()
        assertEquals("Pictures/Trip/", h.device.items.values.single().path)

        h.queue.undo(moved)
        h.drain()
        assertEquals("DCIM/Camera/", h.device.items.values.single().path)

        val album = h.driveAlbum("a", "f1", "Holidays")
        val driveItem = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)
        val binned = h.transfers.trash(listOf(driveItem), "1 item")!!
        h.drain()
        assertTrue(h.drive.files.getValue("x1").trashed)

        h.queue.undo(binned)
        h.drain()
        assertFalse(h.drive.files.getValue("x1").trashed)
        // An undone batch can't be undone twice.
        assertEquals(null, h.queue.undo(binned))
    }

    @Test
    fun undoOfACutAcrossSourcesRestoresTheSourceAndBinsTheCopy() = runTest {
        h.account("a")
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(1000))
        val album = h.driveAlbum("a", "f1", "Holidays")
        h.transfers.clip(ClipboardMode.CUT, listOf(item))
        val moved = h.transfers.paste(album).getOrThrow()
        h.drain()

        h.queue.undo(moved)
        h.drain()

        assertFalse(h.device.items.values.single().trashed)
        assertTrue(h.drive.files.values.single { it.name == "a.jpg" }.trashed)
    }

    @Test
    fun anotherAppsItemWaitsForMediaStoreConsent() = runTest {
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(1000), granted = false)
        val trip = h.phoneAlbum(PRIMARY, "Pictures/Trip/")
        h.transfers.clip(ClipboardMode.CUT, listOf(item))
        h.transfers.paste(trip)

        h.drain()
        val job = h.dao.jobs(1).single()
        assertEquals(WaitFor.DEVICE_WRITE, job.waitFor)
        assertEquals(h.device.items.keys.single(), job.consentUri)

        // The user allows it on MediaStore's screen.
        h.device.granted += h.device.items.keys
        h.queue.deviceConsent(WaitFor.DEVICE_WRITE, listOf(job.id), granted = true)
        h.drain()

        assertEquals("Pictures/Trip/", h.device.items.values.single().path)
    }

    @Test
    fun deletingAnotherAppsItemIsDoneByMediaStoresOwnConfirmation() = runTest {
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(1000), granted = false)
        val batch = h.transfers.trash(listOf(item), "1 item")!!
        h.drain()
        val job = h.dao.jobs(batch).single()
        assertEquals(WaitFor.DEVICE_TRASH, job.waitFor)

        // MediaStore's trash screen moves it itself; the job is then done.
        h.device.items.values.single().trashed = true
        h.queue.deviceConsent(WaitFor.DEVICE_TRASH, listOf(job.id), granted = true)

        assertEquals(TransferState.DONE, h.dao.job(job.id)?.state)
        assertTrue(h.dao.batch(batch)?.finishedAt != null)
    }

    @Test
    fun aDriveWriteWaitsForTheFullDriveScope() = runTest {
        h.account("a", full = false)
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(1000))
        val album = h.driveAlbum("a", "f1", "Holidays")
        h.transfers.clip(ClipboardMode.COPY, listOf(item))
        h.transfers.paste(album)

        h.drain()
        assertEquals(WaitFor.DRIVE_SCOPE, h.dao.jobs(1).single().waitFor)
        assertEquals(emptyList<String>(), h.drive.requests.filter { "upload" in it })

        h.env.noScope.clear()
        h.queue.driveAccess("a", granted = true)
        h.drain()
        assertEquals(1, h.drive.inFolder("a", "f1").size)
    }

    @Test
    fun aDriveFileTheUserDoesNotOwnLeavesTheAlbumInsteadWhenTheyChooseThat() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", bytes(100))
        h.drive.notOwned += "x1"
        val batch = h.transfers.trash(listOf(item), "1 item")!!

        h.drain()
        val job = h.dao.jobs(batch).single()
        assertEquals(WaitFor.NOT_OWNED, job.waitFor)

        h.queue.resolveNotOwned(listOf(job.id), removeFromAlbum = true)
        h.drain()
        assertFalse(h.drive.files.getValue("x1").trashed)
        assertEquals(emptyList<String>(), h.drive.files.getValue("x1").parents)
        assertEquals(TransferState.DONE, h.dao.jobs(batch).single().state)
    }

    @Test
    fun renamingInDriveKeepsTheTimeAVideoIsDatedBy() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.mp4", bytes(100))

        h.transfers.rename(listOf(item), "trip_{n}", "1 item")
        h.drain()
        val file = h.drive.files.getValue("x1")
        assertTrue(file.name.startsWith("trip_"))
        assertEquals(FakeDrive.MODIFIED_TIME, file.modifiedTime)
    }

    /** Runs the queue, moving the clock past each backoff, until nothing is left to retry. */
    private suspend fun retryUntilSettled() {
        repeat(MAX_ROUNDS) {
            h.drain()
            if (h.dao.nextDueAt() == null) return
            h.clock.advance(BACKOFF_STEP_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val MAX_ROUNDS = 20
        const val BACKOFF_STEP_MS = 16 * 60_000L
    }
}
