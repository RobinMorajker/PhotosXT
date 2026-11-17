package com.photoxt.core.transfer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.datastore.PxtSettings
import com.photoxt.core.datastore.SettingsStore
import com.photoxt.core.model.EditRecipe
import com.photoxt.core.model.Ellipse01
import com.photoxt.core.model.ExportOptions
import com.photoxt.core.model.Geometry
import com.photoxt.core.model.Light
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.Retouch
import com.photoxt.core.model.TransferState
import com.photoxt.core.model.WaitFor
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.EditJson
import com.photoxt.core.transfer.Harness.Companion.PRIMARY
import com.photoxt.core.transfer.Harness.Companion.bytes
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Saving and reverting edits (spec §4.10, Phase 7 Done when): the original is kept before the
 * item changes, every save renders from it, and Revert puts back the same bytes, on the phone
 * and in Drive, where the edit is a new revision and the original's revision is kept forever.
 */
@RunWith(AndroidJUnit4::class)
class EditJobsTest {
    private val h = Harness()
    private val clipboard = EditClipboard(ApplicationProvider.getApplicationContext())
    private val ops get() = EditOps(h.database, h.queue, clipboard)
    private val brighter = EditJson.encode(EditRecipe(light = Light(exposure = 1f)))
    private val darker = EditJson.encode(EditRecipe(light = Light(exposure = -1f)))

    @After
    fun tearDown() = h.close()

    /** What the fake exporter makes of [original] under [recipe]. */
    private fun rendered(original: ByteArray, recipe: String) =
        original.reversedArray() + EditJson.encode(EditJson.decode(recipe)).toByteArray()

    private suspend fun md5(bytes: ByteArray) = FakeDrive.md5(bytes)

    @Test
    fun aPhonePhotoKeepsItsOriginalAndRevertsBitForBit() = runTest {
        val original = bytes(3_000)
        val id = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", original, granted = false)
        val uri = h.device.items.keys.single()

        val batch = checkNotNull(ops.save(id, brighter, ExportOptions(), "a.jpg"))
        h.drain()
        // MediaStore asks before PhotoXT may change another app's photo; nothing changed yet.
        val job = h.dao.jobs(batch).single()
        assertEquals("${job.state} ${job.error}", WaitFor.DEVICE_WRITE, job.waitFor)
        assertArrayEquals(original, h.device.items.getValue(uri).bytes)
        h.queue.deviceConsent(WaitFor.DEVICE_WRITE, listOf(job.id), granted = true)
        h.device.granted += uri
        h.drain()

        assertArrayEquals(rendered(original, brighter), h.device.items.getValue(uri).bytes)
        val edit = checkNotNull(h.database.editDao().edit(id))
        assertEquals(md5(original), edit.originalMd5)
        assertArrayEquals(original, File(checkNotNull(edit.originalPath)).readBytes())

        // A second save renders from the original, not from the first edit.
        ops.save(id, darker, ExportOptions(), "a.jpg")
        h.drain()
        assertArrayEquals(rendered(original, darker), h.device.items.getValue(uri).bytes)
        assertEquals(darker, checkNotNull(h.database.editDao().edit(id)).opsJson)

        ops.revert(listOf(id), "a.jpg")
        h.drain()
        assertArrayEquals(original, h.device.items.getValue(uri).bytes)
        assertNull(h.database.editDao().edit(id))
        assertFalse(File(checkNotNull(edit.originalPath)).exists())
    }

    @Test
    fun aDrivePhotoGetsANewRevisionAndItsOriginalIsKeptForever() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val original = bytes(5_000)
        val id = h.driveItem("a", album, "f1", "x1", "x.jpg", original)

        ops.save(id, brighter, ExportOptions(), "x.jpg")
        h.drain()
        val file = checkNotNull(h.drive.files["x1"])
        assertEquals(2, file.revisions.size)
        assertTrue(file.revisions.first().keepForever)
        assertArrayEquals(rendered(original, brighter), file.content)
        assertEquals("r1", checkNotNull(h.database.editDao().edit(id)).baseRevisionId)
        assertTrue("a" in h.env.written)

        ops.revert(listOf(id), "x.jpg")
        h.drain()
        assertEquals(3, file.revisions.size)
        assertArrayEquals(original, file.content)
        assertEquals(md5(original), file.md5)
        // Back to the original, the kept revision may go again.
        assertFalse(file.revisions.first().keepForever)
        assertNull(h.database.editDao().edit(id))
    }

    @Test
    fun saveAsCopyLeavesTheOriginalAlone() = runTest {
        val original = bytes(2_000)
        val id = h.phoneItem(PRIMARY, "Pictures/Trip/", "b.jpg", original)
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val driveId = h.driveItem("a", album, "f1", "x1", "x.jpg", original)

        ops.saveCopy(id, brighter, ExportOptions(), name = null, title = "b.jpg")
        ops.saveCopy(driveId, brighter, ExportOptions(), name = null, title = "x.jpg")
        h.drain()

        val phone = h.device.items.values.associateBy { it.name }
        assertArrayEquals(original, phone.getValue("b.jpg").bytes)
        assertArrayEquals(rendered(original, brighter), phone.getValue("b_edited.jpg").bytes)
        assertEquals("Pictures/Trip/", phone.getValue("b_edited.jpg").path)
        val inFolder = h.drive.inFolder("a", "f1").associateBy { it.name }
        assertArrayEquals(original, inFolder.getValue("x.jpg").content)
        assertArrayEquals(rendered(original, brighter), inFolder.getValue("x_edited.jpg").content)
        assertNull(h.database.editDao().edit(id))
    }

    @Test
    fun undoPutsBackWhatTheItemWas() = runTest {
        val original = bytes(1_500)
        val id = h.phoneItem(PRIMARY, "DCIM/Camera/", "c.jpg", original)
        val uri = h.device.items.keys.single()
        val first = checkNotNull(ops.save(id, brighter, ExportOptions(), "c.jpg"))
        h.drain()
        val second = checkNotNull(ops.save(id, darker, ExportOptions(), "c.jpg"))
        h.drain()

        assertNotNull(h.queue.undo(second))
        h.drain()
        assertArrayEquals(rendered(original, brighter), h.device.items.getValue(uri).bytes)
        assertNotNull(h.queue.undo(first))
        h.drain()
        assertArrayEquals(original, h.device.items.getValue(uri).bytes)
        assertNull(h.database.editDao().edit(id))
    }

    @Test
    fun pasteEditsLaysTheCopiedLookOverEachPhotosOwnEdit() = runTest {
        val a = bytes(1_000, seed = 3)
        val b = bytes(1_200, seed = 5)
        val c = bytes(900, seed = 7)
        val first = h.phoneItem(PRIMARY, "DCIM/Camera/", "p1.jpg", a)
        val second = h.phoneItem(PRIMARY, "DCIM/Camera/", "p2.jpg", b)
        val third = h.phoneItem(PRIMARY, "DCIM/Camera/", "p3.jpg", c)
        val crop = Rect01(0.1f, 0.1f, 0.9f, 0.9f)
        val cropped = EditJson.encode(
            EditRecipe(geometry = Geometry(crop = crop), light = Light(exposure = -1f))
        )
        ops.save(first, brighter, ExportOptions(), "p1.jpg")
        ops.save(second, cropped, ExportOptions(), "p2.jpg")
        h.drain()

        assertTrue(ops.copy(first))
        assertFalse(ops.copy(third))
        val pasted = checkNotNull(ops.paste(listOf(second, third), emptySet(), "2 photos"))
        h.drain()

        h.dao.jobs(pasted).forEach { assertEquals("${it.error}", TransferState.DONE, it.state) }
        // The look comes from the copy; the second photo keeps its own crop.
        val own = EditJson.decode(checkNotNull(h.database.editDao().edit(second)).opsJson)
        assertEquals(1f, own.light.exposure)
        assertEquals(crop, own.geometry.crop)
        val byName = h.device.items.values.associateBy { it.name }
        assertArrayEquals(rendered(c, brighter), byName.getValue("p3.jpg").bytes)
        assertArrayEquals(
            b,
            java.io.File(checkNotNull(h.database.editDao().edit(second)?.originalPath)).readBytes()
        )
    }

    @Test
    fun withoutTheUnredactedOriginalNothingIsOverwritten() = runTest {
        h.env.readsOriginals = false
        val original = bytes(1_000)
        val id = h.phoneItem(PRIMARY, "DCIM/Camera/", "d.jpg", original)
        val batch = checkNotNull(ops.save(id, brighter, ExportOptions(), "d.jpg"))
        h.drain()
        assertEquals(TransferState.FAILED, h.dao.jobs(batch).single().state)
        assertArrayEquals(original, h.device.items.values.single().bytes)
        assertNull(h.database.editDao().edit(id))
    }

    @Test
    fun pastedSkinSmoothingFindsThePhotosOwnFaces() = runTest {
        val source = h.phoneItem(PRIMARY, "DCIM/Camera/", "s1.jpg", bytes(800, seed = 13))
        val target = h.phoneItem(PRIMARY, "DCIM/Camera/", "s2.jpg", bytes(700, seed = 17))
        // Faces found in the copied photo say nothing about where they are in another one.
        val elsewhere = Ellipse01(0.2f, 0.2f, 0.1f, 0.1f)
        val smooth = Retouch(skinSmoothing = 0.6f, faces = listOf(elsewhere))
        ops.save(source, EditJson.encode(EditRecipe(retouch = smooth)), ExportOptions(), "s1.jpg")
        h.drain()
        val found = listOf(Ellipse01(0.6f, 0.4f, 0.08f, 0.1f))
        h.env.faces = found

        assertTrue(ops.copy(source))
        checkNotNull(ops.paste(listOf(target), emptySet(), "s2.jpg"))
        h.drain()

        val saved = EditJson.decode(checkNotNull(h.database.editDao().edit(target)).opsJson)
        assertEquals(0.6f, saved.retouch.skinSmoothing)
        assertEquals(found, saved.retouch.faces)
        // What was rendered is what was saved, so the editor shows the same.
        assertEquals(saved, EditJson.decode(h.exporter.exported.last()))
    }

    @Test
    fun aGonePhotosOriginalGoesAfterAMonthUnlessThePhotoComesBack() = runTest {
        val first = h.phoneItem(PRIMARY, "DCIM/Camera/", "g1.jpg", bytes(800, seed = 9))
        val second = h.phoneItem(PRIMARY, "DCIM/Camera/", "g2.jpg", bytes(700, seed = 11))
        ops.save(first, brighter, ExportOptions(), "g1.jpg")
        ops.save(second, brighter, ExportOptions(), "g2.jpg")
        h.drain()
        val keptFirst = File(checkNotNull(h.database.editDao().edit(first)?.originalPath))
        val keptSecond = File(checkNotNull(h.database.editDao().edit(second)?.originalPath))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val assets = EditAssets(File(context.cacheDir, "edit-assets").apply { deleteRecursively() })
        val unused = assets.put(byteArrayOf(1, 2, 3), "png")
        val cleanup = EditCleanup(h.database, assets, SettingsStore(MemoryStore()), clipboard)
        val day = 86_400_000L
        val start = assets.file(unused).lastModified() + day

        // Both photos leave PhotoXT; the second comes back, as from the trash.
        val rows = h.database.fileOpsDao().media(listOf(second))
        h.database.mediaIndexDao().deleteMedia(listOf(first, second))
        cleanup.run(start)
        h.database.mediaIndexDao().upsertMedia(rows)
        cleanup.run(start + 32 * day)

        assertFalse(keptFirst.exists())
        assertNull(h.database.editDao().edit(first))
        assertTrue(keptSecond.exists())
        assertNotNull(h.database.editDao().edit(second))
        assertFalse(assets.file(unused).exists())
    }

    /** Settings in memory. */
    private class MemoryStore : androidx.datastore.core.DataStore<PxtSettings> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(PxtSettings())
        override val data: kotlinx.coroutines.flow.Flow<PxtSettings> = state

        override suspend fun updateData(
            transform: suspend (t: PxtSettings) -> PxtSettings
        ): PxtSettings = transform(state.value).also { state.value = it }
    }
}
