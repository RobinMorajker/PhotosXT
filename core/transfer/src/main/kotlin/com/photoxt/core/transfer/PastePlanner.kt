package com.photoxt.core.transfer

import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.DriveFolderEntity
import com.photoxt.core.database.FileOpsDao
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.drive.FOLDER_MIME
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TransferOp

/** One job to queue; a folder made by another job of the plan is `folderJob = -(index + 1)`. */
internal data class Planned(val op: TransferOp, val src: FileRef, val dst: Destination?)

/**
 * Turns albums and items into jobs (spec §4.7): where an album is, what is below it, and the
 * folders a recursive paste makes before its items.
 */
internal class PastePlanner(private val files: FileOpsDao) {
    /**
     * An album pasted into [target] with everything below it (spec §4.7, recursive). Drive to
     * Drive in one account moves the folder itself; otherwise its folders are made first, then its
     * items copied or moved into them. Only photos and videos travel; other files stay put.
     */
    suspend fun albumPlan(
        op: TransferOp,
        album: AlbumEntity,
        target: Destination,
        track: Boolean = false
    ): List<Planned> {
        val folders = folderTree(album)
        if (op == TransferOp.MOVE && inSameDrive(album, target)) {
            val root = folders.first()
            val ref = FileRef(
                SourceType.DRIVE,
                root.name,
                FOLDER_MIME,
                account = album.accountId,
                fileId = root.driveId,
                parentId = root.driveParent,
                isFolder = true
            )
            return listOf(Planned(TransferOp.MOVE, ref, target))
        }
        // Where each folder of the tree goes: a Drive folder job, or a phone path.
        val plan = ArrayList<Planned>()
        val places = HashMap<String, Destination>()
        folders.forEach { folder ->
            val parent = folder.parentKey?.let(places::getValue) ?: target
            places[folder.key] = if (target.isDrive) {
                val ref = FileRef(
                    SourceType.DRIVE,
                    folder.name,
                    FOLDER_MIME,
                    account = target.account,
                    isFolder = true
                )
                val top = track && folder.parentKey == null
                plan += Planned(TransferOp.MKDIR, ref, parent.copy(track = top))
                Destination(
                    SourceType.DRIVE,
                    folder.name,
                    account = target.account,
                    folderJob = -plan.size.toLong()
                )
            } else {
                val path = checkNotNull(parent.path) + folder.name + "/"
                parent.copy(title = folder.name, path = path)
            }
        }
        folders.forEach { folder ->
            folder.items.forEach { (item, itemAlbum) ->
                plan += Planned(op, item.toRef(itemAlbum), places.getValue(folder.key))
            }
        }
        return plan
    }

    /** Whether [target] is [album] or inside it: pasting there would never end. */
    suspend fun contains(album: AlbumEntity, target: Destination): Boolean = when {
        album.type == AlbumType.DEVICE_FOLDER && !target.isDrive ->
            target.volume == album.deviceVolume &&
                target.path.orEmpty().startsWith(album.devicePath, ignoreCase = true)

        inSameDrive(album, target) -> folderTree(album).any { it.driveId == target.folderId }

        else -> false
    }

    suspend fun destinationOf(albumId: String): Destination? {
        val album = files.album(albumId) ?: return null
        return when (album.type) {
            AlbumType.DEVICE_FOLDER -> Destination(
                SourceType.DEVICE,
                album.title,
                album.id,
                volume = album.deviceVolume,
                path = album.devicePath
            )

            AlbumType.DRIVE_FOLDER -> Destination(
                SourceType.DRIVE,
                album.title,
                album.id,
                account = album.accountId,
                folderId = driveFolderOf(album)
            )
        }
    }

    /** [album]'s Drive folder: its tracked folder's (Relink may change it), else its id's. */
    suspend fun driveFolderOf(album: AlbumEntity): String =
        files.tracked(album.id)?.driveFolderId ?: album.id.substringAfterLast(':')

    /** The items [ids], each with its album, in no particular order. */
    suspend fun mediaRows(ids: List<String>): List<Pair<MediaEntity, AlbumEntity?>> {
        val albums = HashMap<String, AlbumEntity?>()
        return ids.chunked(MAX_SQL_VARIABLES).flatMap { files.media(it) }.map { item ->
            item to albums.getOrPut(item.albumId) { files.album(item.albumId) }
        }
    }

    /** Whether Drive album [album] and [target] are in one account's Drive. */
    private fun inSameDrive(album: AlbumEntity, target: Destination): Boolean =
        album.type == AlbumType.DRIVE_FOLDER && target.isDrive && target.account == album.accountId

    /** One folder of an album's tree: its key, name, parent, Drive ids and items. */
    private class Folder(
        val key: String,
        val name: String,
        val parentKey: String?,
        val driveId: String? = null,
        val driveParent: String? = null,
        val items: List<Pair<MediaEntity, AlbumEntity?>>
    )

    /** [album]'s folder and every folder below it, parents first, each with its items. */
    private suspend fun folderTree(album: AlbumEntity): List<Folder> = when (album.type) {
        AlbumType.DEVICE_FOLDER -> phoneTree(album)
        AlbumType.DRIVE_FOLDER -> driveTree(album)
    }

    private suspend fun phoneTree(album: AlbumEntity): List<Folder> {
        val base = album.devicePath
        val folders = files.deviceAlbumsUnder(album.sourceRef).map { folder ->
            val relative = folder.devicePath.removePrefix(base).trimEnd('/')
            Folder(
                key = relative,
                name = relative.substringAfterLast('/').ifEmpty { folderName(base) },
                parentKey = relative.takeIf { it.isNotEmpty() }?.substringBeforeLast('/', ""),
                items = files.mediaIn(listOf(folder.id)).map { it to folder }
            )
        }
        return withMissingParents(folders)
    }

    private suspend fun driveTree(album: AlbumEntity): List<Folder> {
        val account = checkNotNull(album.accountId)
        val rootId = driveFolderOf(album)
        val all = files.driveFolders(account).associateBy { it.folderId }
        val children = all.values.groupBy { it.parentFolderId }
        val tree = ArrayList<DriveFolderEntity>()
        val root = all[rootId]
        val queue = ArrayDeque(listOfNotNull(root))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            tree += next
            queue += children[next.folderId].orEmpty()
                .filter { it.trackedAlbumId == next.trackedAlbumId }
        }
        val items = tree.map { it.folderId }.chunked(MAX_SQL_VARIABLES)
            .flatMap { files.driveMediaIn(account, it) }
            .groupBy { it.remoteParentId }
        val rootFolder = Folder(
            key = rootId,
            name = root?.name ?: album.title,
            parentKey = null,
            driveId = rootId,
            driveParent = root?.parentFolderId,
            items = items[rootId].orEmpty().map { it to null }
        )
        return listOf(rootFolder) + tree.drop(1).map { folder ->
            Folder(
                key = folder.folderId,
                name = folder.name,
                parentKey = folder.parentFolderId,
                driveId = folder.folderId,
                driveParent = folder.parentFolderId,
                items = items[folder.folderId].orEmpty().map { it to null }
            )
        }
    }

    /** Phone folders between an album and its nested folders that hold no items themselves. */
    private fun withMissingParents(folders: List<Folder>): List<Folder> {
        val known = folders.associateBy { it.key }.toMutableMap()
        val all = ArrayList<Folder>()
        fun add(key: String) {
            if (all.any { it.key == key }) return
            val parent = if (key.isEmpty()) null else key.substringBeforeLast('/', "")
            if (parent != null) add(parent)
            all += known[key]
                ?: Folder(key, key.substringAfterLast('/'), parent, items = emptyList())
        }
        if ("" !in known) known[""] = Folder("", folders.first().name, null, items = emptyList())
        folders.forEach { add(it.key) }
        return all
    }
}
