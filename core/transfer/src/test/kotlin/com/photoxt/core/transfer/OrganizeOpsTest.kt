package com.photoxt.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.photoxt.core.database.HistoryEntity
import com.photoxt.core.database.PlaybackPositionEntity
import com.photoxt.core.model.TransferState
import com.photoxt.core.transfer.Harness.Companion.PRIMARY
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
 * Organising that changes files (spec §4.8): favorites reach MediaStore and Drive both ways, and
 * the vault takes an item in, leaving no plain copy and no history behind, and gives it back.
 */
@RunWith(AndroidJUnit4::class)
class OrganizeOpsTest {
    private val h = Harness()
    private val ops get() = OrganizeOps(h.database, h.queue)

    @After
    fun tearDown() = h.close()

    @Test
    fun aFavoriteReachesThePhoneAndDriveAndComesBackOff() = runTest {
        h.account("a")
        val phone = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", bytes(1_000))
        val album = h.driveAlbum("a", "f1", "Holidays")
        val drive = h.driveItem("a", album, "f1", "x1", "x.jpg", bytes(1_000))

        ops.setFavorite(listOf(phone, drive), favorite = true, title = "2 items")
        // The heart shows at once, before MediaStore or Drive have it.
        assertTrue(checkNotNull(h.database.mediaDao().viewerItem(phone)).favorite)
        h.drain()
        assertTrue(h.device.items.values.single().favorite)
        assertTrue(checkNotNull(h.drive.files["x1"]).starred)

        ops.setFavorite(listOf(phone, drive), favorite = false, title = "2 items")
        h.drain()
        assertFalse(h.device.items.values.single().favorite)
        assertFalse(checkNotNull(h.drive.files["x1"]).starred)
        assertFalse(checkNotNull(h.database.mediaDao().viewerItem(drive)).favorite)
    }

    @Test
    fun aFavoriteThatCantBeMadeTakesTheHeartBack() = runTest {
        h.account("a")
        val album = h.driveAlbum("a", "f1", "Holidays")
        val drive = h.driveItem("a", album, "f1", "x1", "x.jpg", bytes(1_000))
        h.drive.files.remove("x1")

        ops.setFavorite(listOf(drive), favorite = true, title = "x.jpg")
        retryUntilSettled()

        assertEquals(TransferState.FAILED, h.dao.jobs(1).single().state)
        assertFalse(checkNotNull(h.database.mediaDao().viewerItem(drive)).favorite)
    }

    @Test
    fun aPhoneItemGoesIntoTheVaultWithNothingLeftBehind() = runTest {
        val photo = bytes(300_000)
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        h.database.historyDao().insert(HistoryEntity(mediaId = item, albumId = null, viewedAt = 1))
        h.database.playbackDao().save(PlaybackPositionEntity(item, 1_000, 60_000, 1))

        ops.moveToVault(listOf(item), "1 item")
        h.drain()

        val entry = h.vault.entries().single()
        assertArrayEquals(photo, h.vault.open(entry.id).use { it.readBytes() })
        assertEquals("$PRIMARY:DCIM/Camera/", entry.meta.origin)
        // The original is deleted for good, not binned, and PhotoXT forgot it.
        assertTrue(h.device.items.isEmpty())
        assertNull(h.database.mediaDao().viewerItem(item))
        assertTrue(h.database.historyDao().all().isEmpty())
        assertNull(h.database.playbackDao().position(item))
        // Nothing decrypted was left in the work folder.
        assertTrue(h.env.workDir.walk().none { it.isFile })
    }

    @Test
    fun aDriveItemGoesIntoTheVaultAndIsDeletedInDrive() = runTest {
        h.account("a")
        val photo = bytes(200_000)
        val album = h.driveAlbum("a", "f1", "Holidays")
        val item = h.driveItem("a", album, "f1", "x1", "x.jpg", photo)

        ops.moveToVault(listOf(item), "1 item")
        h.drain()

        val entry = h.vault.entries().single()
        assertArrayEquals(photo, h.vault.open(entry.id).use { it.readBytes() })
        assertNull(h.drive.files["x1"])
        assertNull(h.database.mediaDao().viewerItem(item))
    }

    @Test
    fun anItemComesOutOfTheVaultWhereItWas() = runTest {
        val photo = bytes(300_000)
        val item = h.phoneItem(PRIMARY, "DCIM/Camera/", "a.jpg", photo)
        ops.moveToVault(listOf(item), "1 item")
        h.drain()

        val entry = h.vault.entries().single()

        ops.moveOutOfVault(listOf(entry), "1 item")
        h.drain()

        assertArrayEquals(photo, h.device.live("DCIM/Camera/").single().bytes)
        assertTrue(h.vault.entries().isEmpty())
        assertFalse(h.vault.has(entry.id))
    }

    /** Runs every due job, moving the clock past each backoff, until none is left to retry. */
    private suspend fun retryUntilSettled() {
        repeat(MAX_ROUNDS) {
            h.drain()
            if (h.dao.nextDueAt() == null) return
            h.clock.advance(BACKOFF_STEP_MS)
        }
    }

    private companion object {
        const val MAX_ROUNDS = 20
        const val BACKOFF_STEP_MS = 3_600_000L
    }
}
