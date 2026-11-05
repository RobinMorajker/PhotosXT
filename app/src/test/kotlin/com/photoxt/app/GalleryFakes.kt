package com.photoxt.app

import android.graphics.drawable.ColorDrawable
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.photoxt.core.designsystem.PxtTheme
import com.photoxt.core.model.DateGrouping
import com.photoxt.core.ui.GridCell
import com.photoxt.core.ui.TimelineLayout
import java.time.LocalDate
import java.time.ZoneId

/** Two days of fake photos (a past year, so labels never turn into "Today"). */
object GalleryFakes {
    private val day1 = LocalDate.of(2024, 3, 9)
    private val day2 = LocalDate.of(2024, 3, 2)

    val cells: List<GridCell> =
        listOf(GridCell.Header(day1.toEpochDay().toInt())) +
            (1..7).map { item("a$it", day1, video = it == 2, favorite = it == 4) } +
            listOf(GridCell.Header(day2.toEpochDay().toInt())) +
            (1..5).map { item("b$it", day2) }

    val layout = TimelineLayout(
        DateGrouping.DAY,
        intArrayOf(day1.toEpochDay().toInt(), day2.toEpochDay().toInt()),
        intArrayOf(7, 5)
    )

    fun item(id: String, day: LocalDate, video: Boolean = false, favorite: Boolean = false) =
        GridCell.Item(
            id = id,
            thumbnail = id,
            isVideo = video,
            durationMs = if (video) 83_000 else null,
            favorite = favorite,
            takenAt = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )

    fun selectionOf(vararg ids: String): Map<String, Long> = cells
        .filterIsInstance<GridCell.Item>()
        .filter { it.id in ids }
        .associate { it.id to it.takenAt }
}

private val swatches =
    listOf(0xFF5B8DEF, 0xFFE57373, 0xFF81C784, 0xFFFFB74D, 0xFF9575CD, 0xFF4DB6AC)

/** Renders every thumbnail as a solid colour picked from its model, the same on every run. */
@OptIn(ExperimentalCoilApi::class)
private val fakeThumbnails = AsyncImagePreviewHandler { request ->
    val index = Math.floorMod(request.data.hashCode(), swatches.size)
    ColorDrawable(swatches[index].toInt()).asImage()
}

@OptIn(ExperimentalCoilApi::class)
@Composable
fun WithFakeThumbnails(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides fakeThumbnails
    ) {
        PxtTheme(darkTheme = darkTheme) {
            Surface { content() }
        }
    }
}
