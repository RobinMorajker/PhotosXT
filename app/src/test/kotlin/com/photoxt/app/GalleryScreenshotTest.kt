package com.photoxt.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import com.github.takahirom.roborazzi.captureRoboImage
import com.photoxt.core.database.AccountEntity
import com.photoxt.core.model.MediaAccess
import com.photoxt.core.ui.GridDensity
import com.photoxt.feature.albums.AlbumTile
import com.photoxt.feature.albums.AlbumsContent
import com.photoxt.feature.albums.AlbumsUiState
import com.photoxt.feature.albums.BrowserActions
import com.photoxt.feature.albums.BrowserFolder
import com.photoxt.feature.albums.DeviceFolder
import com.photoxt.feature.albums.DriveBrowserContent
import com.photoxt.feature.albums.DriveBrowserUiState
import com.photoxt.feature.albums.DriveStatus
import com.photoxt.feature.timeline.TimelineCallbacks
import com.photoxt.feature.timeline.TimelineContent
import com.photoxt.feature.timeline.TimelineUiState
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pictures and Albums with content, compared with src/test/screenshots on every run. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class GalleryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { WithFakeThumbnails { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Pictures(state: TimelineUiState) {
        val rows = remember { flowOf(PagingData.from(GalleryFakes.cells)) }
            .collectAsLazyPagingItems()
        TimelineContent(
            title = "Pictures",
            state = state,
            rows = rows,
            permissions = emptyArray(),
            callbacks = TimelineCallbacks(),
            empty = {}
        )
    }

    @Test
    fun picturesGrid() = capture("pictures_grid") {
        Pictures(TimelineUiState(MediaAccess.FULL, layout = GalleryFakes.layout))
    }

    @Test
    fun picturesSelecting() = capture("pictures_selecting") {
        Pictures(
            TimelineUiState(
                MediaAccess.FULL,
                layout = GalleryFakes.layout,
                selection = GalleryFakes.selectionOf("b1", "b2", "b3", "b4", "b5", "a3")
            )
        )
    }

    @Test
    fun picturesLimitedAccessAtThreeColumns() = capture("pictures_limited") {
        Pictures(
            TimelineUiState(
                MediaAccess.LIMITED,
                density = GridDensity.DAY_3,
                layout = GalleryFakes.layout
            )
        )
    }

    /** Spec §7: the limited-access banner, filter chips and date headers hold at 200% text. */
    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun picturesAt200PercentText() = capture("pictures_limited_200") {
        Pictures(TimelineUiState(MediaAccess.LIMITED, layout = GalleryFakes.layout))
    }

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun accessRequestAt200PercentText() = capture("pictures_denied_200") {
        Pictures(TimelineUiState(MediaAccess.DENIED))
    }

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun albumsAt200PercentText() = capture("albums_grid_200") { Albums() }

    @Test
    fun albums() = capture("albums_grid") { Albums() }

    @Composable
    private fun Albums() {
        AlbumsContent(
            title = "Albums",
            state = AlbumsUiState(
                MediaAccess.FULL,
                listOf(
                    AlbumTile("device:1", "Camera", DeviceFolder.CAMERA, 1_204, "camera"),
                    AlbumTile("device:2", "Screenshots", DeviceFolder.SCREENSHOTS, 87, "shots"),
                    AlbumTile("device:3", "WhatsApp Images", DeviceFolder.WHATSAPP, 3_311, "wa"),
                    AlbumTile("device:4", "Download", DeviceFolder.DOWNLOADS, 12, "dl"),
                    AlbumTile("device:5", "", DeviceFolder.OTHER, 1, "root")
                )
            ),
            permissions = emptyArray(),
            onAccessResult = {},
            onOpenAlbum = { _, _ -> },
            empty = {}
        )
    }

    @Test
    fun albumsWithDrive() = capture("albums_drive") { DriveAlbums() }

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun albumsWithDriveAt200PercentText() = capture("albums_drive_200") { DriveAlbums() }

    @Composable
    private fun DriveAlbums() {
        val lapsed =
            AccountEntity("a", "robin@example.com", "Robin", null, "drive", true, null, null, 1)
        AlbumsContent(
            title = "Albums",
            state = AlbumsUiState(
                MediaAccess.FULL,
                listOf(
                    AlbumTile("device:1", "Camera", DeviceFolder.CAMERA, 1_204, "camera"),
                    AlbumTile(
                        "drive:a:1",
                        "Holidays",
                        DeviceFolder.OTHER,
                        2_001,
                        "dl",
                        DriveStatus.SYNCED
                    ),
                    AlbumTile(
                        "drive:a:2",
                        "Family",
                        DeviceFolder.OTHER,
                        320,
                        "wa",
                        DriveStatus.SYNCING
                    ),
                    AlbumTile(
                        "drive:a:3",
                        "Work",
                        DeviceFolder.OTHER,
                        41,
                        "shots",
                        DriveStatus.NEEDS_ATTENTION
                    )
                ),
                listOf(lapsed)
            ),
            permissions = emptyArray(),
            onAccessResult = {},
            onOpenAlbum = { _, _ -> },
            empty = {}
        )
    }

    @Test
    fun driveBrowser() = capture("drive_browser") {
        DriveBrowserContent(
            state = DriveBrowserUiState(
                granted = true,
                folders = listOf(
                    BrowserFolder("1", "Camera uploads"),
                    BrowserFolder("2", "PhotoXT test"),
                    BrowserFolder("3", "Scans")
                )
            ),
            actions = object : BrowserActions {}
        )
    }

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun driveBrowserInAFolderAt200PercentText() = capture("drive_browser_folder_200") {
        DriveBrowserContent(
            state = DriveBrowserUiState(
                granted = true,
                path = listOf(BrowserFolder("2", "PhotoXT test")),
                folders = listOf(BrowserFolder("4", "Trip 2025")),
                merge = true
            ),
            actions = object : BrowserActions {}
        )
    }
}
