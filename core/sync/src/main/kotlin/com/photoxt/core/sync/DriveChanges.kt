package com.photoxt.core.sync

import androidx.room.withTransaction
import com.photoxt.core.database.DriveFolderEntity
import com.photoxt.core.database.KnownDriveItem
import com.photoxt.core.database.MAX_SQL_VARIABLES
import com.photoxt.core.database.MediaEntity
import com.photoxt.core.database.PxtDatabase
import com.photoxt.core.database.TrackedFolderEntity
import com.photoxt.core.drive.Change
import com.photoxt.core.drive.DriveAccount
import com.photoxt.core.drive.DriveFile
import com.photoxt.core.drive.DriveHttp
import com.photoxt.core.drive.driveStatus
import com.photoxt.core.model.TrackedFolderState
import java.io.IOException
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

/** What one sync of an account's Drive changes did. */
data class ChangesResult(
    val changes: Int = 0,
    val written: Int = 0,
    val removed: Int = 0,
    /** Tracked folders (album ids) whose shape changed, marked for a rescan. */
    val rescan: Set<String> = emptySet(),
    /** Tracked folders found binned, deleted or unshared. */
    val unavailable: Set<String> = emptySet()
) {
    operator fun plus(other: ChangesResult) = ChangesResult(
        changes + other.changes,
        written + other.written,
        removed + other.removed,
        rescan + other.rescan,
        unavailable + other.unavailable
    )
}

/**
 * Incremental sync of one account (spec §4.3, §5.5): Drive's Changes feed from the account's
 * saved position, a page at a time, each applied in one transaction with the position after it,
 * so a crash repeats at most one page and misses nothing. One feed covers every tracked folder of
 * the account: My Drive, shared with the user and shared drives (Phase 3 spike).
 *
 * A photo or video changed in a tracked folder is added, moved between its albums, renamed or
 * updated, and new content gets new thumbnails ([DriveItems]). Binned, deleted or unshared items
 * are removed with their files kept for the grace period. A renamed folder renames its album. A
 * change to a tree's shape (a folder added, moved or removed, a shortcut, an item moved out)
 * marks the tree for a rescan, which settles it. A tracked folder binned, deleted or unshared
 * turns unavailable and keeps its items; once it is back, it is rescanned.
 */
class DriveChanges @Inject constructor(
    private val http: DriveHttp,
    private val database: PxtDatabase,
    private val items: DriveItems,
    private val clock: Clock
) {
    private val drive = database.driveDao()
    private val cache = database.driveCacheDao()

    /** Applies [accountId]'s changes since its saved position; null when it tracks nothing. */
    suspend fun sync(accountId: String, zone: ZoneId = ZoneId.systemDefault()): ChangesResult? {
        var token = drive.account(accountId)?.changesToken ?: return null
        val account = DriveAccount(accountId)
        var total = ChangesResult()
        while (true) {
            val page = runCatching { http.api.changes(account, token) }.getOrElse { error ->
                // The saved position expired or is unknown to Drive: start over from now.
                if (error.driveStatus in TOKEN_GONE) return total + restart(accountId)
                throw error
            }
            val next = page.nextPageToken ?: page.newStartPageToken
                ?: throw IOException("Drive sent no next position")
            total += apply(accountId, page.changes, next, zone)
            if (page.nextPageToken == null) return total
            token = next
        }
    }

    /** A new position, and every tracked folder of [accountId] rescanned from there. */
    private suspend fun restart(accountId: String): ChangesResult {
        val token = http.api.startPageToken(DriveAccount(accountId)).startPageToken
        val trees = drive.trackedFoldersOf(accountId).map { it.albumId }
        database.withTransaction {
            drive.saveChangesToken(accountId, token, clock.millis())
            trees.chunked(MAX_SQL_VARIABLES).forEach {
                drive.setStates(it, TrackedFolderState.SCANNING)
            }
        }
        return ChangesResult(rescan = trees.toSet())
    }

    private suspend fun apply(
        accountId: String,
        changes: List<Change>,
        next: String,
        zone: ZoneId
    ): ChangesResult {
        val fileIds = changes.mapNotNull { it.fileId }.distinct()
        val plan = Plan(
            accountId = accountId,
            zone = zone,
            trees = drive.trackedFoldersOf(accountId).associateBy { it.albumId },
            folders = drive.foldersOfAccount(accountId).associateBy { it.folderId },
            albums = drive.albumIdsOfAccount(accountId).toHashSet(),
            known = fileIds.chunked(MAX_SQL_VARIABLES)
                .flatMap { drive.knownItems(accountId, it) }
                .associateBy { it.remoteId }
        )
        changes.forEach(plan::add)
        // An item removed under a tracked folder may mean the folder itself went (binned or
        // unshared): then the folder turns unavailable and keeps its items.
        val lost = plan.removalTrees().filterTo(HashSet()) { rootGone(accountId, plan, it) }
        val unavailable = plan.unavailable + lost
        val removals = plan.removals.filter { (_, tree) -> tree !in unavailable }.map { it.first }
        val rescan = plan.rescan - unavailable
        val now = clock.millis()
        val written = database.withTransaction {
            unavailable.chunked(MAX_SQL_VARIABLES).forEach {
                drive.setStates(it, TrackedFolderState.UNAVAILABLE)
            }
            plan.renamed.forEach { (folder, name) -> drive.renameFolder(accountId, folder, name) }
            plan.renamedTrees.mapNotNull { plan.trees[it] }.forEach { retitle(it) }
            cache.removeItems(removals, purgeAfter = now + ORPHAN_GRACE_MS)
            items.write(accountId, plan.upserts)
            rescan.chunked(MAX_SQL_VARIABLES).forEach {
                drive.setStates(it, TrackedFolderState.SCANNING)
            }
            drive.saveChangesToken(accountId, next, now)
            plan.upserts.size
        }
        return ChangesResult(changes.size, written, removals.size, rescan, unavailable)
    }

    /** Whether tracked folder [treeId] itself is gone: binned, deleted or no longer shared. */
    private suspend fun rootGone(accountId: String, plan: Plan, treeId: String): Boolean {
        val tree = plan.trees[treeId] ?: return false
        if (treeId in plan.restored) return false
        if (tree.state == TrackedFolderState.UNAVAILABLE) return true
        val root = runCatching {
            http.api.get(DriveAccount(accountId), tree.driveFolderId, "id,trashed")
        }.getOrElse { error ->
            if (error.driveStatus == HTTP_NOT_FOUND) return true
            throw error
        }
        return root.trashed
    }

    /** Album titles and paths of [tree] from its folders' names, after renames. */
    private suspend fun retitle(tree: TrackedFolderEntity) {
        val rows = drive.foldersOf(tree.albumId).associateBy { it.folderId }
        fun path(row: DriveFolderEntity): String {
            val names = generateSequence(row) { it.parentFolderId?.let(rows::get) }
                .take(MAX_DEPTH)
                .map { it.name }
                .toList()
            return names.asReversed().joinToString("") { "$it/" }
        }
        rows.values.forEach { row ->
            val albumId = when {
                row.parentFolderId == null -> tree.albumId
                tree.mergeSubfolders -> null
                else -> driveAlbumId(tree.accountId, row.folderId)
            }
            if (albumId != null) drive.retitleAlbum(albumId, row.name, "drive:${path(row)}")
        }
    }

    /** One page of changes sorted into what to write, remove, rename and rescan. */
    private class Plan(
        val accountId: String,
        val zone: ZoneId,
        val trees: Map<String, TrackedFolderEntity>,
        val folders: Map<String, DriveFolderEntity>,
        val albums: Set<String>,
        val known: Map<String, KnownDriveItem>
    ) {
        val upserts = ArrayList<MediaEntity>()

        /** Media id to the tracked folder it is under. */
        val removals = ArrayList<Pair<String, String>>()
        val rescan = HashSet<String>()
        val unavailable = HashSet<String>()

        /** Tracked folders that came back in Drive. */
        val restored = HashSet<String>()

        /** Folder id to its new name. */
        val renamed = HashMap<String, String>()
        val renamedTrees = HashSet<String>()

        fun removalTrees(): Set<String> = removals.mapTo(HashSet()) { it.second } - unavailable

        fun add(change: Change) {
            val id = change.fileId ?: return
            if (change.changeType == DRIVE_CHANGE) return
            val file = change.file?.takeUnless { change.removed || it.trashed }
            val folder = folders[id]
            when {
                folder != null -> folderChanged(folder, file)

                file == null -> removed(id)

                file.isFolder || file.isShortcut -> rescan += treesOf(file.parents)

                file.isMedia -> mediaChanged(file)

                // Documents and other files are not media; a known item keeps its type.
                else -> Unit
            }
        }

        private fun removed(fileId: String) {
            val item = known[fileId] ?: return
            item.rootId?.let { removals += item.id to it }
        }

        private fun folderChanged(row: DriveFolderEntity, file: DriveFile?) {
            val tree = trees[row.trackedAlbumId] ?: return
            val isRoot = row.parentFolderId == null
            when {
                file == null && isRoot -> unavailable += tree.albumId

                // A subfolder went: the rescan's sweep removes it and its items.
                file == null -> rescan += tree.albumId

                isRoot -> {
                    if (tree.state == TrackedFolderState.UNAVAILABLE) {
                        restored += tree.albumId
                        rescan += tree.albumId
                    }
                    if (file.name != row.name) rename(row, file.name)
                }

                row.parentFolderId !in file.parents -> {
                    rescan += tree.albumId
                    rescan += treesOf(file.parents)
                }

                file.name != row.name -> rename(row, file.name)
            }
        }

        private fun rename(row: DriveFolderEntity, name: String) {
            renamed[row.folderId] = name
            renamedTrees += row.trackedAlbumId
        }

        private fun mediaChanged(file: DriveFile) {
            val placement = file.parents.firstNotNullOfOrNull { folders[it] }
            val tree = placement?.let { trees[it.trackedAlbumId] }
            if (placement == null || tree == null) {
                // Moved out of every tracked folder, or placed there through a shortcut: the
                // rescan of its tree tells which.
                known[file.id]?.rootId?.let { rescan += it }
                return
            }
            val albumId = if (placement.parentFolderId == null || tree.mergeSubfolders) {
                tree.albumId
            } else {
                driveAlbumId(accountId, placement.folderId)
            }
            if (albumId !in albums) {
                rescan += tree.albumId
                return
            }
            file.toMedia(accountId, albumId, placement.folderId, zone)?.let { upserts += it }
        }

        private fun treesOf(parents: List<String>): Set<String> =
            parents.mapNotNullTo(HashSet()) { folders[it]?.trackedAlbumId }
    }

    private companion object {
        /** A shared drive itself changed (renamed, hidden): its files come as file changes. */
        const val DRIVE_CHANGE = "drive"
        const val HTTP_NOT_FOUND = 404
        const val MAX_DEPTH = 100

        /** Drive's answers to a position it no longer knows. */
        val TOKEN_GONE = setOf(400, 404, 410)
    }
}
