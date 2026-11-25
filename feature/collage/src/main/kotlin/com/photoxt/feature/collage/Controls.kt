package com.photoxt.feature.collage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

// The panels' controls. ponytail: copies of the editor's ChipRow and SliderRow (feature/editor),
// kept here rather than reshaping the editor mid-phase; move both to core/ui if a third screen
// needs them.

/** One choice of several, as chips in a row that scrolls. */
@Composable
internal fun <T> ChipRow(
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                enabled = enabled
            )
        }
    }
}

/**
 * A labelled slider whose value shows beside it; tapping the value sets it back to [zero].
 * Moving it changes the collage; letting go records one step of the history.
 */
@Composable
internal fun SliderRow(
    label: Int,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    zero: Float = 0f,
    shown: (Float) -> String = ::signed
) {
    val name = stringResource(label)
    val reset = stringResource(R.string.collage_reset_named, name)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            shown(value),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(onClickLabel = reset, role = Role.Button) {
                    onChange(zero)
                    onDone()
                }
                .padding(horizontal = 8.dp)
        )
    }
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = range,
        modifier = Modifier.semantics { contentDescription = name }
    )
}

/** Colour swatches in a row that scrolls. */
@Composable
internal fun ColorRow(selected: Int?, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SWATCHES.forEach { (color, name) ->
            val label = stringResource(name)
            val on = color == selected
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .semantics {
                        contentDescription = label
                        role = Role.RadioButton
                        this.selected = on
                    }
                    .clickable { onSelect(color) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(SWATCH_DP.dp)
                        .background(Color(color), CircleShape)
                        .border(
                            BorderStroke(
                                if (on) 3.dp else 1.dp,
                                if (on) MaterialTheme.colorScheme.primary else Color.Gray
                            ),
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
internal fun Heading(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
internal fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/** Buttons in a row that scrolls, each a few words. */
@Composable
internal fun ButtonRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

@Composable
internal fun ActionButton(label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Text(stringResource(label), maxLines = 1)
    }
}

/** −100 to +100, the sign always shown. */
internal fun signed(value: Float): String =
    String.format(Locale.getDefault(), "%+d", (value * PERCENT).roundToInt())

/** 0 to 100, plain. */
internal fun plain(value: Float): String =
    String.format(Locale.getDefault(), "%d", (value * PERCENT).roundToInt())

/** Degrees, such as 12°. */
internal fun degrees(value: Float): String =
    String.format(Locale.getDefault(), "%d°", value.roundToInt())

private const val PERCENT = 100
private const val SWATCH_DP = 28

private val SWATCHES = listOf(
    0xFFFFFFFF to R.string.color_white,
    0xFF000000 to R.string.color_black,
    0xFF808080 to R.string.color_grey,
    0xFFE5E5E5 to R.string.color_light_grey,
    0xFFE53935 to R.string.color_red,
    0xFFFB8C00 to R.string.color_orange,
    0xFFFDD835 to R.string.color_yellow,
    0xFF43A047 to R.string.color_green,
    0xFF1E88E5 to R.string.color_blue,
    0xFF8E24AA to R.string.color_purple,
    0xFFF06292 to R.string.color_pink,
    0xFFF5E6CC to R.string.color_beige,
    0xFF1A237E to R.string.color_navy
).map { (argb, name) -> argb.toInt() to name }
