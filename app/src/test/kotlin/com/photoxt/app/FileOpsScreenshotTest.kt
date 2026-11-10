package com.photoxt.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.photoxt.core.database.TransferBatchRow
import com.photoxt.core.model.SourceType
import com.photoxt.core.model.TransferOp
import com.photoxt.core.transfer.FileRef
import com.photoxt.core.transfer.TrashEntry
import com.photoxt.feature.library.TransferActions
import com.photoxt.feature.library.TransferRowState
import com.photoxt.feature.library.TransfersContent
import com.photoxt.feature.library.TrashContent
import com.photoxt.feature.library.TrashUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Trash and Transfers (spec §4.7) with content, at 100% and 200% text (spec §7). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE_QUALIFIERS)
class FileOpsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { WithFakeThumbnails { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun trash() = capture("trash") { Trash() }

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun trashAt200PercentText() = capture("trash_200") { Trash() }

    /** Two picked: Restore and Delete forever replace Empty. */
    @Test
    fun trashSelecting() = capture("trash_selecting") {
        Trash(selection = setOf("content://media/1", "a:f1"))
    }

    @Test
    fun transfers() = capture("transfers") { Transfers() }

    @Test
    @Config(qualifiers = PHONE_QUALIFIERS, fontScale = 2f)
    fun transfersAt200PercentText() = capture("transfers_200") { Transfers() }

    @Composable
    private fun Trash(selection: Set<String> = emptySet()) {
        // Half a day past whole days, so the count shown never changes during a run.
        val now = System.currentTimeMillis() + DAY_MS / 2
        val photo = FileRef(SourceType.DEVICE, "IMG_0412.jpg", "image/jpeg")
        val second = photo.copy(name = "IMG_0413.jpg", uri = "content://media/2")
        val inDrive = photo.copy(source = SourceType.DRIVE, account = "a")
        TrashContent(
            state = TrashUiState(
                loading = false,
                entries = listOf(
                    TrashEntry(photo.copy(uri = "content://media/1"), now + 29 * DAY_MS, "camera"),
                    TrashEntry(
                        inDrive.copy(name = "Beach.jpg", fileId = "f1"),
                        now + 12 * DAY_MS,
                        "wa",
                        account = "robin@example.com"
                    ),
                    TrashEntry(
                        inDrive.copy(name = "Shared.jpg", fileId = "f2"),
                        now + 3 * DAY_MS,
                        "dl",
                        account = "robin@example.com",
                        owned = false
                    ),
                    TrashEntry(second, now, "b")
                ),
                failedAccounts = listOf("work@example.com"),
                selection = selection
            ),
            onBack = {},
            onToggle = {},
            onClearSelection = {},
            onRestore = {},
            onDelete = {},
            onEmpty = {},
            onRefresh = {}
        )
    }

    @Composable
    private fun Transfers() {
        val running = TransferBatchRow(
            id = 1,
            op = TransferOp.COPY,
            title = "Holidays",
            paused = false,
            createdAt = 0,
            finishedAt = null,
            undoOf = null,
            undone = false,
            total = 12,
            done = 5,
            failed = 0,
            skipped = 0,
            waiting = 0,
            cancelled = 0,
            bytesDone = 180 * MB,
            bytesTotal = 420 * MB
        )
        TransfersContent(
            rows = listOf(
                running,
                running.copy(id = 2, op = TransferOp.MOVE, title = "Family", paused = true),
                running.copy(id = 3, op = TransferOp.TRASH, title = "3 items", total = 3, done = 1)
                    .copy(waiting = 1, bytesDone = 0, bytesTotal = 0),
                running.copy(id = 4, title = "Trip 2025", finishedAt = 1, done = 10, failed = 2),
                running.copy(id = 5, op = TransferOp.MOVE, title = "Camera", done = 12)
                    .copy(finishedAt = 1),
                running.copy(id = 6, op = TransferOp.TRASH, title = "2 items", total = 2, done = 2)
                    .copy(finishedAt = 1, undoOf = 7, bytesDone = 0, bytesTotal = 0)
            ).map(::TransferRowState),
            onBack = {},
            onClearFinished = {},
            actions = TransferActions()
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MB = 1024L * 1024
    }
}
