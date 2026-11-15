package com.photoxt.feature.albums

import android.content.Context
import com.photoxt.core.auth.GoogleAccounts
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.database.AlbumDao
import com.photoxt.core.database.AlbumEntity
import com.photoxt.core.database.AlbumGroupEntity
import com.photoxt.core.database.AlbumPrefsEntity
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.database.ListSummary
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.MediaListQuery
import com.photoxt.core.database.PinEntity
import com.photoxt.core.database.VirtualAlbumSummary
import com.photoxt.core.database.summary
import com.photoxt.core.media.MediaRef
import com.photoxt.core.media.thumbnailOf
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.AlbumSortField
import com.photoxt.core.model.AlbumType
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TargetType
import com.photoxt.core.model.naturalKey
import com.photoxt.core.organize.AlbumOptions
import com.photoxt.core.organize.OwnAlbums
import com.photoxt.core.organize.Pins
import com.photoxt.core.sync.DriveSync
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** What an album tile stands for. */
enum class TileKind { FOLDER, VIRTUAL, SMART, GROUP }

/** Everything the Albums tab and its groups place: albums, PhotoXT's own albums, and groups. */
internal data class AlbumSources(
    val folders: List<AlbumSummary>,
    val virtual: List<VirtualAlbumSummary>,
    val smart: List<Pair<AlbumEntity, ListSummary>>,
    val prefs: Map<String, AlbumPrefsEntity>,
    val pins: List<PinEntity>,
    val groups: List<AlbumGroupEntity>,
    val sort: AlbumSort
)

/** The Albums tab's tiles (spec §4.2), in their order: pins first, then the sort. */
class AlbumTiles @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val albumDao: AlbumDao,
    private val mediaDao: MediaDao,
    private val ownAlbums: OwnAlbums,
    private val options: AlbumOptions,
    private val pins: Pins,
    private val googleAccounts: GoogleAccounts,
    private val driveSync: DriveSync
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val smartSummaries: Flow<List<Pair<AlbumEntity, ListSummary>>> =
        ownAlbums.smartAlbums.flatMapLatest { albums ->
            if (albums.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    albums.map { album ->
                        val query =
                            MediaListQuery(albumId = album.id, rule = OwnAlbums.decode(album.rule))
                        mediaDao.summary(query).map { album to it }
                    }
                ) { it.toList() }
            }
        }

    internal val sources: Flow<AlbumSources> = combine(
        combine(albumDao.albums(), ownAlbums.virtualAlbums, smartSummaries, ::Triple),
        options.allPrefs,
        pins.pinned(TargetType.ALBUM),
        options.groups,
        options.albumsSort
    ) { (folders, virtual, smart), prefs, pinned, groups, sort ->
        AlbumSources(folders, virtual, smart, prefs, pinned, groups, sort)
    }

    /** The tiles of the Albums tab, or of group [groupId]. */
    fun tiles(groupId: String?): Flow<List<AlbumTile>> = combine(
        sources,
        googleAccounts.accounts,
        driveSync.online
    ) { sources, accounts, online ->
        arrange(sources, accounts, online, groupId, ::displayName)
    }

    /** Camera and every Screenshots folder, for their first-time pins (spec §4.2). */
    fun autoPinned(folders: List<AlbumSummary>): List<String> = folders
        .filter { it.type == AlbumType.DEVICE_FOLDER }
        .map { it.id to recogniseFolder(it.sourceRef.substringAfter(':')) }
        .filter { it.second == DeviceFolder.CAMERA || it.second == DeviceFolder.SCREENSHOTS }
        .sortedBy { it.second.ordinal }
        .map { it.first }

    /** What a tile is called: a recognised folder's translated name, else its own. */
    private fun displayName(tile: AlbumTile): String = when (tile.folder) {
        DeviceFolder.CAMERA -> context.getString(R.string.album_camera)
        DeviceFolder.SCREENSHOTS -> context.getString(R.string.album_screenshots)
        DeviceFolder.DOWNLOADS -> context.getString(R.string.album_downloads)
        else -> tile.title.ifEmpty { context.getString(R.string.album_phone_storage) }
    }
}

/** One tile with what sorts it. */
private class Placed(
    val tile: AlbumTile,
    val latest: Long,
    val created: Long,
    val bytes: Long,
    val position: Double?
)

/**
 * The tiles to show, in order (spec §4.2, Sorting): pinned albums first in their drag order, then
 * the rest by [AlbumSources.sort], groups among them; albums in a group show only inside it
 * ([groupId]), and hidden albums nowhere. Ties go to the name.
 */
internal fun arrange(
    sources: AlbumSources,
    accounts: List<AccountEntity> = emptyList(),
    online: Boolean = true,
    groupId: String? = null,
    nameOf: (AlbumTile) -> String = { it.title }
): List<AlbumTile> {
    val needsConsent = accounts.filter { it.needsConsent }.mapTo(HashSet()) { it.id }
    val pinOrder = sources.pins.withIndex().associate { (i, pin) -> pin.targetId to i }
    val albums = buildList {
        sources.folders.forEach { add(it.placed(sources.prefs[it.id], needsConsent, online)) }
        sources.virtual.forEach { add(it.placed(sources.prefs[it.id])) }
        sources.smart.forEach { (album, summary) ->
            add(album.placed(summary, sources.prefs[album.id]))
        }
    }.filter { sources.prefs[it.tile.id]?.hidden != true }
        .map { it.withPin(it.tile.id in pinOrder, nameOf) }
    val inView = if (groupId != null) {
        albums.filter { it.tile.groupId == groupId }
    } else {
        albums.filter { it.tile.groupId == null || it.tile.pinned } + groupsOf(sources, albums)
    }
    val pinned = inView.filter { it.tile.pinned && it.tile.kind != TileKind.GROUP }
        .sortedBy { pinOrder[it.tile.id] }
    val rest = inView.filterNot { it.tile.pinned && it.tile.kind != TileKind.GROUP }
    return (pinned + sorted(rest, sources.sort)).map { it.tile }
}

private fun groupsOf(sources: AlbumSources, albums: List<Placed>): List<Placed> =
    sources.groups.map { group ->
        val members = albums.filter { it.tile.groupId == group.id }
        val first = members.maxByOrNull { it.latest }
        Placed(
            tile = AlbumTile(
                id = AlbumOptions.GROUP_PREFIX + group.id,
                title = group.name,
                folder = DeviceFolder.OTHER,
                count = members.sumOf { it.tile.count },
                cover = first?.tile?.cover,
                kind = TileKind.GROUP,
                name = group.name,
                members = members.size
            ),
            latest = first?.latest ?: 0,
            created = group.createdAt,
            bytes = members.sumOf { it.bytes },
            position = group.position
        )
    }

private fun sorted(placed: List<Placed>, sort: AlbumSort): List<Placed> {
    val byName = compareBy<Placed> { naturalKey(it.tile.name) }
    val comparator: Comparator<Placed> = when (sort.field) {
        // New albums (no place yet) join at the top, newest first (spec §4.2, Custom order).
        AlbumSortField.CUSTOM -> compareBy<Placed> { it.position != null }
            .thenBy { it.position }
            .thenByDescending { it.latest }

        AlbumSortField.NAME -> byName

        // Empty albums (one just made, a Drive folder still syncing) lead (spec §4.7).
        AlbumSortField.NEWEST_ITEM -> compareBy<Placed> { it.tile.count != 0 }.thenBy { it.latest }

        AlbumSortField.DATE_CREATED -> compareBy { it.created }

        AlbumSortField.ITEM_COUNT -> compareBy { it.tile.count }

        AlbumSortField.TOTAL_SIZE -> compareBy { it.bytes }
    }
    val directed = when {
        sort.field == AlbumSortField.CUSTOM || !sort.descending -> comparator

        // Descending, but empty albums still lead in Newest item.
        sort.field == AlbumSortField.NEWEST_ITEM ->
            compareBy<Placed> { it.tile.count != 0 }.thenByDescending { it.latest }

        else -> comparator.reversed()
    }
    return placed.sortedWith(directed.then(byName))
}

private fun Placed.withPin(pinned: Boolean, nameOf: (AlbumTile) -> String) = Placed(
    tile = tile.copy(pinned = pinned, name = nameOf(tile)),
    latest = latest,
    created = created,
    bytes = bytes,
    position = position
)

private fun AlbumSummary.placed(
    prefs: AlbumPrefsEntity?,
    needsConsent: Set<String>,
    online: Boolean
): Placed {
    val drive = type == AlbumType.DRIVE_FOLDER
    return Placed(
        tile = AlbumTile(
            id = id,
            title = title,
            folder = if (drive) {
                DeviceFolder.OTHER
            } else {
                recogniseFolder(
                    sourceRef.substringAfter(':')
                )
            },
            count = count,
            cover = cover(),
            drive = if (drive) status(accountId in needsConsent, online) else null,
            hideFromPictures = prefs?.hideFromPictures == true,
            groupId = prefs?.groupId
        ),
        latest = latestTakenAt,
        created = createdAt,
        bytes = totalBytes,
        position = prefs?.position
    )
}

private fun VirtualAlbumSummary.placed(prefs: AlbumPrefsEntity?) = Placed(
    tile = AlbumTile(
        id = id,
        title = title,
        folder = DeviceFolder.OTHER,
        count = count,
        cover = coverId?.let { id ->
            thumbnailOf(
                MediaRef(
                    id,
                    coverSource ?: SourceType.DEVICE,
                    coverUri,
                    coverModifiedAt,
                    coverAccountId,
                    remoteVersion = coverVersion
                )
            )
        },
        kind = TileKind.VIRTUAL,
        groupId = prefs?.groupId
    ),
    latest = latestTakenAt,
    created = createdAt,
    bytes = totalBytes,
    position = prefs?.position
)

private fun AlbumEntity.placed(summary: ListSummary, prefs: AlbumPrefsEntity?) = Placed(
    tile = AlbumTile(
        id = id,
        title = title,
        folder = DeviceFolder.OTHER,
        count = summary.count,
        cover = summary.coverId?.let { id ->
            thumbnailOf(
                MediaRef(
                    id,
                    summary.coverSource ?: SourceType.DEVICE,
                    summary.coverUri,
                    summary.coverModifiedAt,
                    summary.coverAccountId,
                    remoteVersion = summary.coverVersion
                )
            )
        },
        kind = TileKind.SMART,
        groupId = prefs?.groupId
    ),
    latest = summary.latestTakenAt,
    created = createdAt ?: 0,
    bytes = summary.totalBytes,
    position = prefs?.position
)
