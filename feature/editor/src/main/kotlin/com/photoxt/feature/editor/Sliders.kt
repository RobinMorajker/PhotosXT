package com.photoxt.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.photoxt.core.model.EditRecipe
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The tool panels' sliders, and how their values read.
 */

/** A labelled slider. Its value shows beside it; tapping the value sets it back to [zero]. */
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
    val reset = stringResource(R.string.editor_reset_named, name)
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

/**
 * A slider over one number of the recipe: moving it changes the recipe through [set], and
 * letting go records one history step named [step].
 */
@Composable
internal fun RecipeSlider(
    tools: EditorTools,
    label: Int,
    step: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    zero: Float = 0f,
    shown: (Float) -> String = ::signed,
    set: EditRecipe.(Float) -> EditRecipe
) {
    SliderRow(
        label = label,
        value = value,
        onChange = { v -> tools.history.edit(null) { it.set(v) } },
        onDone = { tools.history.commit(step) },
        range = range,
        zero = zero,
        shown = shown
    )
}

/** −100 to +100, the sign always shown. */
internal fun signed(value: Float): String =
    String.format(Locale.getDefault(), "%+d", (value * PERCENT).roundToInt())

/** 0 to 100, plain. */
internal fun plain(value: Float): String =
    String.format(Locale.getDefault(), "%d", (value * PERCENT).roundToInt())

/** 0% to 100%. */
internal fun percent(value: Float): String =
    String.format(Locale.getDefault(), "%d%%", (value * PERCENT).roundToInt())

/** Exposure stops, such as +0.3. */
internal fun stops(value: Float): String = String.format(Locale.getDefault(), "%+.1f", value)

/** Degrees, such as 2.5°. */
internal fun degrees(value: Float): String = String.format(Locale.getDefault(), "%.1f°", value)

private const val PERCENT = 100
