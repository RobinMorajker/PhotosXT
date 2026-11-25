package com.photoxt.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.database.CollageProjectEntity
import com.photoxt.core.model.CollageCell
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.LayoutNode
import com.photoxt.core.model.SplitAxis
import com.photoxt.core.model.TransferOp
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import com.photoxt.core.render.CollageJson
import com.photoxt.core.transfer.Harness.Companion.PRIMARY
import com.photoxt.core.transfer.Harness.Companion.bytes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collages through the transfer queue (spec §4.11, Phase 8 "Done when"): with *delete sources*
 * the photos reach the trash only after the collage is written and checked, and Undo brings
 * them back; a collage that fails its check bins nothing.
 */
@RunWith(AndroidJUnit4::class)
class CollageJobsTest {
    private val h = Harness()

    @After
    fun tearDown() = h.close()

    private fun projectOf(vararg ids: String): String = CollageJson.encode(
        CollageProject(
            layout = LayoutNode.split(SplitAxis.ROW, ids.indices.map { LayoutNode(cell = it) }),
            cells = ids.map { CollageCell(mediaId = it) }
        )
    )

    private suspend fun twoPhonePhotos(): Pair<String, String> {
        val a = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(1_000, 3))
        val b = h.phoneItem(PRIMARY, "DCIM/Camera/", "b.jpg", bytes(2_000, 5))
        return a to b
    }

    @Test
    fun deleteSourcesBinsThePhotosOnlyOnceTheCollageIsSaved() = runTest {
        val (a, b) = twoPhonePhotos()
        val album = h.collages.defaultAlbum(listOf(a, b))
        assertNotNull(album)
        // While the collage renders, nothing is binned and no trash job is queued yet.
        h.collageExporter.onExport = {
            assertTrue(h.device.items.values.none { it.trashed })
        }
        val batch = checkNotNull(
            h.collages.export(
                projectOf(a, b),
                CollageOutput(),
                checkNotNull(album),
                true,
                null,
                "Camera"
            )
        )
        assertEquals(listOf(TransferOp.COLLAGE), h.dao.jobs(batch).map { it.op })

        h.drain()

        val jobs = h.dao.jobs(batch)
        assertEquals(
            listOf(TransferOp.COLLAGE, TransferOp.TRASH, TransferOp.TRASH),
            jobs.map {
                it.op
            }
        )
        assertTrue(jobs.all { it.state == TransferState.DONE })
        val collage = h.device.live("DCIM/Camera/").single { it.name.startsWith("Collage_") }
        assertArrayEquals(bytes(1_000, 3) + bytes(2_000, 5), collage.bytes)
        assertEquals(2, h.device.items.values.count { it.trashed })
        assertFalse(collage.trashed)
    }

    @Test
    fun aCollageThatFailsItsCheckBinsNothing() = runTest {
        val (a, b) = twoPhonePhotos()
        h.collageExporter.failing = true
        val album = checkNotNull(h.collages.defaultAlbum(listOf(a, b)))
        val batch =
            checkNotNull(
                h.collages.export(projectOf(a, b), CollageOutput(), album, true, null, "Camera")
            )

        repeat(ATTEMPTS) {
            h.drain()
            h.clock.advance(MINUTES_15)
        }

        val jobs = h.dao.jobs(batch)
        assertEquals(listOf(TransferOp.COLLAGE), jobs.map { it.op })
        assertEquals(TransferState.FAILED, jobs.single().state)
        assertTrue(h.device.items.values.none { it.trashed })
        // Nothing half-made is left in the album either.
        assertTrue(h.device.live("DCIM/Camera/").none { it.name.startsWith("Collage_") })
    }

    @Test
    fun aCollageThatDoesntLandIntactBinsNothing() = runTest {
        val (a, b) = twoPhonePhotos()
        h.device.corruptWrites = true
        val album = checkNotNull(h.collages.defaultAlbum(listOf(a, b)))
        val batch =
            checkNotNull(
                h.collages.export(projectOf(a, b), CollageOutput(), album, true, null, "Camera")
            )

        h.drain()

        assertEquals(listOf(TransferOp.COLLAGE), h.dao.jobs(batch).map { it.op })
        assertTrue(h.device.items.values.none { it.trashed })
    }

    @Test
    fun undoRestoresTheSourcesAndKeepsTheCollage() = runTest {
        val (a, b) = twoPhonePhotos()
        val album = checkNotNull(h.collages.defaultAlbum(listOf(a, b)))
        val batch =
            checkNotNull(
                h.collages.export(projectOf(a, b), CollageOutput(), album, true, null, "Camera")
            )
        h.drain()
        assertEquals(2, h.device.items.values.count { it.trashed })

        val undo = h.queue.undo(batch)
        h.drain()

        assertNotNull(undo)
        assertTrue(h.device.items.values.none { it.trashed })
        assertEquals(1, h.device.live("DCIM/Camera/").count { it.name.startsWith("Collage_") })
        // Undo goes once.
        assertEquals(null, h.queue.undo(batch))
    }

    @Test
    fun withoutDeleteSourcesThePhotosStayAndTheDraftRecordsTheCollage() = runTest {
        val (a, b) = twoPhonePhotos()
        val drafts = h.database.collageDao()
        drafts.save(CollageProjectEntity("d1", projectOf(a, b), "$a\n$b", 1, 1))
        val album = checkNotNull(h.collages.defaultAlbum(listOf(a, b)))
        val batch =
            checkNotNull(
                h.collages.export(projectOf(a, b), CollageOutput(), album, false, "d1", "Camera")
            )

        h.drain()

        assertEquals(listOf(TransferOp.COLLAGE), h.dao.jobs(batch).map { it.op })
        assertTrue(h.device.items.values.none { it.trashed })
        assertTrue(checkNotNull(drafts.draft("d1")).exportedMediaId?.startsWith("device:") == true)
    }

    @Test
    fun binningDrivePhotosAsksForFullDriveAccessBeforeAnythingIsMade() = runTest {
        h.account("a", full = false)
        val drive = h.driveAlbum("a", "f1", "Trip")
        val x = h.driveItem("a", drive, "f1", "x1", "x.jpg", bytes(800, 9))
        val (a, _) = twoPhonePhotos()
        val album = checkNotNull(h.collages.defaultAlbum(listOf(a)))
        val batch =
            checkNotNull(
                h.collages.export(projectOf(a, x), CollageOutput(), album, true, null, "Camera")
            )

        h.drain()

        val job = h.dao.jobs(batch).single()
        assertEquals(TransferState.WAITING, job.state)
        assertEquals(WaitFor.DRIVE_SCOPE, job.waitFor)
        assertTrue(h.collageExporter.requests.isEmpty())

        // Once granted, the collage is made from the Drive photo too, then both are binned.
        h.env.noScope.clear()
        h.queue.driveAccess("a", granted = true)
        h.drain()

        val jobs = h.dao.jobs(batch)
        assertEquals(3, jobs.size)
        assertTrue(jobs.all { it.state == TransferState.DONE })
        assertTrue(h.drive.files.getValue("x1").trashed)
    }

    private companion object {
        const val ATTEMPTS = 7
        const val MINUTES_15 = 15 * 60_000L
    }
}
