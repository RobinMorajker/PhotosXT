package com.photoxt.core.ui

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.photoxt.core.model.DateGrouping
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Labels for date-group keys: "Today", "Yesterday", "Wed, 4 Nov", "Wed, 4 Nov 2025",
 * "November 2026" or "2026", in the user's locale. The returned function is cheap to call from
 * outside composition too, as the fast scroller does while it is dragged.
 */
@Composable
fun rememberDateGroupLabels(grouping: DateGrouping): (key: Int) -> String {
    val today = stringResource(R.string.date_today)
    val yesterday = stringResource(R.string.date_yesterday)
    val locale = LocalConfiguration.current.locales[0]
    return remember(grouping, locale, today, yesterday) {
        val labels = DateGroupLabels(grouping, locale, LocalDate.now(), today, yesterday)
        labels::label
    }
}

internal class DateGroupLabels(
    private val grouping: DateGrouping,
    private val locale: Locale,
    private val now: LocalDate,
    private val today: String,
    private val yesterday: String
) {
    private val formatters = HashMap<String, DateTimeFormatter>()

    fun label(key: Int): String {
        val date = grouping.startOf(key)
        val day = grouping == DateGrouping.DAY
        return when {
            day && date == now -> today
            day && date == now.minusDays(1) -> yesterday
            else -> date.format(formatter(skeleton(date)))
        }
    }

    private fun skeleton(date: LocalDate): String = when (grouping) {
        DateGrouping.DAY -> if (date.year == now.year) "EEEdMMM" else "EEEdMMMy"
        DateGrouping.MONTH -> "MMMMy"
        DateGrouping.YEAR -> "y"
    }

    private fun formatter(skeleton: String): DateTimeFormatter = formatters.getOrPut(skeleton) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

// Read on the main thread only, when TalkBack asks a tile for its description.
private val durationFormats = HashMap<Locale, MeasureFormat>()

/** What TalkBack reads for a tile: kind, length of a video, and when it was taken. */
internal fun describeMedia(context: Context, item: GridCell.Item): String {
    val taken = DateUtils.formatDateTime(
        context,
        item.takenAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME
    )
    if (!item.isVideo) return context.getString(R.string.media_photo, taken)
    val seconds = (item.durationMs ?: 0L) / MILLIS_PER_SECOND
    val length = durationFormats
        .getOrPut(Locale.getDefault()) {
            MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE)
        }
        .formatMeasures(
            Measure(seconds / SECONDS_PER_MINUTE, MeasureUnit.MINUTE),
            Measure(seconds % SECONDS_PER_MINUTE, MeasureUnit.SECOND)
        )
    return context.getString(R.string.media_video, length, taken)
}

/** A video's length as a tile badge shows it: "0:42", "12:05", "1:02:03". */
internal fun formatDuration(durationMs: Long?): String {
    val total = (durationMs ?: 0L) / MILLIS_PER_SECOND
    val hours = total / SECONDS_PER_HOUR
    val minutes = total / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    val locale = Locale.getDefault()
    return if (hours > 0) {
        String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(locale, "%d:%02d", minutes, seconds)
    }
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
