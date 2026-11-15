package com.photoxt.core.transfer

import androidx.room.withTransaction
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.media.ThumbnailStore
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import com.photoxt.core.model.TransferOp
import com.photoxt.core.model.WaitFor
import com.photoxt.core.vault.Vault
import com.photoxt.core.vault.VaultMeta
import java.io.IOException
import java.util.UUID

/**
 * Moves into and out of the vault (spec §4.8). In: the item is encrypted into the vault and read
 * back, and only then is the original deleted for good (Robin's choice, 2026-11-12: no plain copy
 * in a bin), on the phone after MediaStore's confirmation, in Drive with the `drive` scope. Out:
 * the item is written back to the phone through a checked pending item, then leaves the vault.
 * Both resume where they stopped, like every transfer (spec §0 rule 4).
 */
internal class VaultMoves(
    private val database: PxtDatabase,
    private val copier: Copier,
    private val vault: Vault,
    private val thumbnails: ThumbnailStore,
    private val clock: java.time.Clock
) {
    private val device = copier.device
    private val drive = copier.drive
    private val env = copier.env

    /** Into the vault, or out of it. */
    suspend fun move(run: JobRun): Outcome =
        if (run.job.op == TransferOp.VAULT) moveIn(run) else moveOut(run)

    private suspend fun moveIn(run: JobRun): Outcome {
        val src = run.src
        val account = src.account
        if (src.isDrive) env.requireDriveWrite(checkNotNull(account))
        val copy = run.progress.copy ?: encrypt(run).also { run.save(run.progress.copy(copy = it)) }
        if (src.isDrive) {
            deleteInDrive(run, checkNotNull(account), checkNotNull(src.fileId))
        } else {
            val uri = checkNotNull(src.uri)
            run.deviceWrite(WaitFor.DEVICE_DELETE, uri) { device.delete(uri) }
        }
        src.mediaId?.let { drop(it) }
        return Outcome.Done(copy)
    }

    private suspend fun moveOut(run: JobRun): Outcome {
        val id = vaultId(run.src)
        val copy =
            run.progress.copy ?: run.restore(id).also { run.save(run.progress.copy(copy = it)) }
        vault.delete(id)
        return Outcome.Done(copy)
    }

    /** The item into the vault under the id made for this job, checked as it is read back. */
    private suspend fun encrypt(run: JobRun): FileRef {
        val src = run.src
        val id = run.driveId { UUID.randomUUID().toString() }
        val row = src.mediaId?.let { database.fileOpsDao().media(listOf(it)).firstOrNull() }
        if (!vault.has(id)) {
            val input = if (src.isDrive) {
                (src.mediaId?.let { drive.cachedOriginal(it) } ?: copier.downloaded(run))
                    .inputStream()
            } else {
                device.open(checkNotNull(src.uri))
            }
            val meta = metaOf(src, row)
            val stored = input.use { vault.put(id, meta, it, thumbnailOf(src, row?.remoteVersion)) }
            if (src.md5 != null && !src.md5.equals(stored.md5, ignoreCase = true)) {
                vault.delete(id)
                throw VerificationException("The vault copy doesn't match the original")
            }
        }
        return FileRef(SourceType.DEVICE, src.name, src.mime, src.bytes, uri = VAULT_SCHEME + id)
    }

    /** What the vault keeps about [src]: its name, type, size and dates, and where it was. */
    private fun metaOf(src: FileRef, row: MediaEntity?) = VaultMeta(
        name = src.name,
        mime = src.mime,
        kind = if (src.mime.startsWith("video/")) MediaKind.VIDEO else MediaKind.IMAGE,
        width = row?.width ?: 0,
        height = row?.height ?: 0,
        rotation = row?.rotation ?: 0,
        durationMs = row?.durationMs,
        takenAt = src.takenAt ?: clock.millis(),
        addedAt = clock.millis(),
        origin = if (src.isDrive) null else "${src.volume}:${src.path}"
    )

    /** Writes vault item [id] back to the phone: its own folder, or Pictures/PhotoXT. */
    private suspend fun JobRun.restore(id: String): FileRef {
        val entry = vault.entry(id) ?: throw PermanentException("It's no longer in the vault")
        val meta = entry.meta
        val origin = meta.origin?.takeIf { ':' in it }
        val folder = Destination(
            SourceType.DEVICE,
            meta.name,
            volume = origin?.substringBefore(':') ?: NewAlbumPlace.PRIMARY,
            path = origin?.substringAfter(':')?.ifEmpty { null } ?: RESTORED_PATH
        )
        val name = copier.nameIn(this, folder, meta.name)
        val input = Input(
            size = { meta.bytes },
            md5 = { meta.md5 },
            open = { offset -> vault.open(id).apply { skipExactly(offset) } }
        )
        val ref = FileRef(
            SourceType.DEVICE,
            meta.name,
            meta.mime,
            meta.bytes,
            md5 = meta.md5,
            takenAt = meta.takenAt
        )
        return copier.writeToPhone(this, folder, name, ref, input)
    }

    /** Deletes the Drive original for good; one the user can't delete waits for their answer. */
    private suspend fun deleteInDrive(run: JobRun, account: String, fileId: String) {
        if (run.progress.removeInstead) {
            drive.removeFrom(account, fileId, checkNotNull(run.src.parentId))
            return
        }
        runCatching { drive.delete(account, fileId) }.onFailure { error ->
            throw if (error.refusal ==
                Refusal.FILE
            ) {
                WaitException(WaitFor.NOT_OWNED, error)
            } else {
                error
            }
        }
    }

    /** A small thumbnail for the vault's grid: MediaStore's, or the Drive one PhotoXT made. */
    private fun thumbnailOf(src: FileRef, version: Long?): ByteArray? = if (src.isDrive) {
        val file = thumbnails.grid(checkNotNull(src.fileId), version ?: 0)
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    } else {
        env.thumbnailJpeg(checkNotNull(src.uri))
    }

    /**
     * The original is gone: what PhotoXT kept about it goes too, history, video position, pins,
     * its place in virtual albums and Custom order (spec §4.8: vault items are excluded from
     * history, search, backup and casting). Views logged while the move waited go as well.
     */
    private suspend fun drop(mediaId: String) = database.withTransaction {
        val ids = listOf(mediaId)
        database.historyDao().forget(ids)
        database.playbackDao().forget(ids)
        database.hiddenDao().unhide(ids)
        database.pinDao().unpin(TargetType.ITEM, ids)
        database.fileOpsDao().forgetOwnMemberships(mediaId)
        database.mediaIndexDao().deleteMedia(ids)
    }

    companion object {
        /** A vault item as a [FileRef] uri: `vault:<id>`. */
        const val VAULT_SCHEME = "vault:"
        const val RESTORED_PATH = "Pictures/PhotoXT/"

        fun vaultId(ref: FileRef): String =
            ref.uri?.removePrefix(VAULT_SCHEME) ?: throw IOException("Not a vault item")
    }
}
