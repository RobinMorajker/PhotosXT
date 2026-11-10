package com.photoxt.core.transfer

import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.ClipboardEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.drive.FOLDER_MIME
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.ClipboardMode
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TransferOp
import java.io.OutputStream
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the clipboard holds (spec §4.7): items and albums, to copy or to move. */
data class Clip(val mode: ClipboardMode, val mediaIds: List<String>, val albumIds: List<String>) {
    val size: Int get() = mediaIds.size + albumIds.size

    /** A cut moves, a copy copies. */
    internal val op: TransferOp
        get() = if (mode == ClipboardMode.CUT) TransferOp.MOVE else TransferOp.COPY
}

/** Where *New album* makes its folder (spec §4.7, "on the device or in Drive"). */
sealed interface NewAlbumPlace {
    /** A phone folder in [path] of [volume], e.g. `Pictures/`. */
    data class Phone(val volume: String = PRIMARY, val path: String = PICTURES) : NewAlbumPlace

    /** A Drive folder in [folderId] of [account]; at the top of My Drive it becomes an album. */
    data class Drive(val account: String, val folderId: String? = null) : NewAlbumPlace

    companion object {
        const val PRIMARY = "external_primary"
        const val PICTURES = "Pictures/"
    }
}

/** Why a paste couldn't start. */
enum class PasteProblem { NOTHING_TO_PASTE, NO_TARGET, INTO_ITSELF }

/**
 * File operations for screens (spec §4.7): the clipboard and paste, delete, restore, delete
 * forever, batch rename and convert, PDF export, and new and renamed albums. Each one queues its
 * jobs ([TransferQueue]) and returns at once.
 */
@Singleton
class Transfers @Inject constructor(
    database: PxtDatabase,
    private val queue: TransferQueue,
    private val device: DeviceStore,
    private val pdf: PdfExport,
    private val clock: Clock
) {
    private val dao = database.transferDao()
    private val files = database.fileOpsDao()
    private val planner = PastePlanner(files)

    val clipboard: Flow<Clip?> = dao.clipboard().map { it?.toClip() }

    // --- Clipboard ---------------------------------------------------------------------------

    suspend fun clip(
        mode: ClipboardMode,
        mediaIds: List<String>,
        albumIds: List<String> = emptyList()
    ) {
        val refs = mediaIds.map { "$MEDIA$it" } + albumIds.map { "$ALBUM$it" }
        if (refs.isEmpty()) return
        dao.setClipboard(
            ClipboardEntity(
                mode = mode,
                itemRefs = refs.joinToString("\n"),
                createdAt = clock.millis()
            )
        )
    }

    suspend fun clearClipboard() = dao.clearClipboard()

    /**
     * Pastes the clipboard into album [albumId] (spec §4.7): items, and albums as subfolders with
     * everything in them. A cut moves, a copy copies; either way the clipboard empties.
     */
    suspend fun paste(albumId: String): Result<Long> {
        val clip = dao.currentClipboard()?.toClip() ?: return problem(PasteProblem.NOTHING_TO_PASTE)
        val target = planner.destinationOf(albumId) ?: return problem(PasteProblem.NO_TARGET)
        val albums = clip.albumIds.mapNotNull { files.album(it) }
        if (albums.any { planner.contains(it, target) }) return problem(PasteProblem.INTO_ITSELF)
        val items = planner.mediaRows(clip.mediaIds).map { (item, album) ->
            Planned(clip.op, item.toRef(album), target)
        }
        val folders = albums.flatMap { planner.albumPlan(clip.op, it, target) }
        return pasted(clip, target, items + folders)
    }

    /** Pastes clipped albums at the top: `Pictures/<name>/` on the phone, or My Drive. */
    suspend fun pasteAlbums(place: NewAlbumPlace): Result<Long> {
        val clip = dao.currentClipboard()?.toClip()?.takeIf { it.albumIds.isNotEmpty() }
            ?: return problem(PasteProblem.NOTHING_TO_PASTE)
        val target = when (place) {
            is NewAlbumPlace.Phone ->
                Destination(SourceType.DEVICE, place.path, volume = place.volume, path = place.path)

            is NewAlbumPlace.Drive -> Destination(
                SourceType.DRIVE,
                "My Drive",
                account = place.account,
                folderId = place.folderId ?: ROOT
            )
        }
        val top = place is NewAlbumPlace.Drive && place.folderId == null
        val plan = clip.albumIds.mapNotNull { files.album(it) }.flatMap { album ->
            planner.albumPlan(clip.op, album, target, track = top)
        }
        return pasted(clip, target, plan)
    }

    // --- Actions -----------------------------------------------------------------------------

    /** Moves items to their trash: the phone's, or Drive's bin (spec §4.7, every delete). */
    suspend fun trash(mediaIds: List<String>, title: String): Long? {
        val plan = planner.mediaRows(mediaIds).map { (item, album) ->
            Planned(TransferOp.TRASH, item.toRef(album), null)
        }
        return queued(TransferOp.TRASH, title, plan)
    }

    /** Takes [entries] out of the Trash, back where they were. */
    suspend fun restore(entries: List<FileRef>, title: String): Long? =
        queued(TransferOp.RESTORE, title, entries.map { Planned(TransferOp.RESTORE, it, null) })

    /** Deletes [entries] for good: no Undo, so screens confirm first. */
    suspend fun deleteForever(entries: List<FileRef>, title: String): Long? =
        queued(TransferOp.DELETE, title, entries.map { Planned(TransferOp.DELETE, it, null) })

    /** Renames items with [pattern] ([RenamePattern]), in their order of capture. */
    suspend fun rename(
        mediaIds: List<String>,
        pattern: String,
        title: String,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long? {
        val rows = planner.mediaRows(mediaIds).sortedBy { it.first.takenAt }
        val names = RenamePattern(pattern, zone)
        val plan = rows.mapIndexed { index, (item, album) ->
            val name = names.nameFor(item.name, item.takenAt, index, rows.size)
            val to = Destination(item.source, title, name = name)
            Planned(TransferOp.RENAME, item.toRef(album), to)
        }
        return queued(TransferOp.RENAME, title, plan)
    }

    /** Saves a JPEG copy beside each photo that isn't one (spec §4.7, convert formats). */
    suspend fun convertToJpeg(mediaIds: List<String>, title: String): Long? {
        val plan = planner.mediaRows(mediaIds)
            .filter { (item) -> item.mime.startsWith("image/") && item.mime != JPEG }
            .map { (item, album) ->
                Planned(TransferOp.CONVERT, item.toRef(album), Destination(item.source, title))
            }
        return queued(TransferOp.CONVERT, title, plan)
    }

    /** Writes the photos among [mediaIds] as a PDF to [out]; returns the number of pages. */
    suspend fun exportPdf(
        mediaIds: List<String>,
        out: OutputStream,
        onPage: (Int) -> Unit = {}
    ): Int {
        val refs = planner.mediaRows(mediaIds).sortedByDescending { it.first.takenAt }
            .map { (item, album) -> item.toRef(album) }
        return pdf.export(refs, out, onPage)
    }

    /**
     * *New album* (spec §4.7): a phone album exists at once, empty until something is pasted into
     * it (its id is returned); a Drive folder is made by the queue and returns null.
     */
    suspend fun newAlbum(place: NewAlbumPlace, name: String): String? = when (place) {
        is NewAlbumPlace.Phone -> {
            val path = place.path + name.trim() + "/"
            val bucket = device.bucketId(place.volume, path)
                ?: error("That storage isn't available")
            val album = AlbumEntity(
                id = "device:$bucket",
                type = AlbumType.DEVICE_FOLDER,
                sourceRef = "${place.volume}:$path",
                title = name.trim(),
                pending = true
            )
            files.savePendingAlbum(album)
            album.id
        }

        is NewAlbumPlace.Drive -> {
            val folder = FileRef(
                SourceType.DRIVE,
                name.trim(),
                FOLDER_MIME,
                account = place.account,
                isFolder = true
            )
            val parent = Destination(
                SourceType.DRIVE,
                name.trim(),
                account = place.account,
                folderId = place.folderId ?: ROOT,
                track = place.folderId == null
            )
            queued(TransferOp.MKDIR, name.trim(), listOf(Planned(TransferOp.MKDIR, folder, parent)))
            null
        }
    }

    /** Where a new album inside album [albumId] goes: a subfolder of its folder. */
    suspend fun placeIn(albumId: String): NewAlbumPlace? {
        val album = files.album(albumId) ?: return null
        return when (album.type) {
            AlbumType.DEVICE_FOLDER -> NewAlbumPlace.Phone(album.deviceVolume, album.devicePath)

            AlbumType.DRIVE_FOLDER ->
                NewAlbumPlace.Drive(checkNotNull(album.accountId), planner.driveFolderOf(album))
        }
    }

    /**
     * Renames album [albumId]'s folder (spec §4.7): in Drive, the folder (sync then retitles the
     * album); on the phone, every item moves into a folder of the new name.
     */
    suspend fun renameAlbum(albumId: String, name: String): Long? {
        val album = files.album(albumId) ?: return null
        val newName = name.trim().takeIf { it.isNotEmpty() && it != album.title } ?: return null
        return when (album.type) {
            AlbumType.DRIVE_FOLDER -> {
                val folder = FileRef(
                    SourceType.DRIVE,
                    album.title,
                    FOLDER_MIME,
                    account = album.accountId,
                    fileId = planner.driveFolderOf(album),
                    isFolder = true
                )
                val to = Destination(SourceType.DRIVE, newName, albumId, name = newName)
                queued(TransferOp.RENAME, newName, listOf(Planned(TransferOp.RENAME, folder, to)))
            }

            AlbumType.DEVICE_FOLDER -> queued(TransferOp.MOVE, newName, movedInto(album, newName))
        }
    }

    /** Every item of phone album [album] and its subfolders, moved into a folder [newName]. */
    private suspend fun movedInto(album: AlbumEntity, newName: String): List<Planned> {
        val base = album.devicePath
        val parent = base.trimEnd('/').substringBeforeLast('/', "")
        val renamed = if (parent.isEmpty()) "$newName/" else "$parent/$newName/"
        return files.deviceAlbumsUnder(album.sourceRef).flatMap { folder ->
            val to = Destination(
                SourceType.DEVICE,
                newName,
                volume = album.deviceVolume,
                path = renamed + folder.devicePath.removePrefix(base)
            )
            files.mediaIn(listOf(folder.id)).map { Planned(TransferOp.MOVE, it.toRef(folder), to) }
        }
    }

    /** Queues [plan] unless it is empty; returns its batch. */
    private suspend fun queued(op: TransferOp, title: String, plan: List<Planned>): Long? =
        plan.takeIf { it.isNotEmpty() }?.let { queue.enqueue(op, title, it) }

    /** Queues a paste of [clip] into [target] and empties the clipboard. */
    private suspend fun pasted(clip: Clip, target: Destination, plan: List<Planned>): Result<Long> {
        val batch = queued(clip.op, target.title, plan)
            ?: return problem(PasteProblem.NOTHING_TO_PASTE)
        dao.clearClipboard()
        return Result.success(batch)
    }

    private fun problem(problem: PasteProblem): Result<Long> =
        Result.failure(PasteException(problem))

    private fun ClipboardEntity.toClip(): Clip {
        val refs = itemRefs.lines().filter { it.isNotBlank() }
        return Clip(
            mode,
            refs.filter { it.startsWith(MEDIA) }.map { it.removePrefix(MEDIA) },
            refs.filter { it.startsWith(ALBUM) }.map { it.removePrefix(ALBUM) }
        )
    }

    private companion object {
        const val MEDIA = "media:"
        const val ALBUM = "album:"
        const val ROOT = "root"
        const val JPEG = "image/jpeg"
    }
}

class PasteException(val problem: PasteProblem) : Exception(problem.name)
