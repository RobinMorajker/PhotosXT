package com.photoxt.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoxt.core.model.AlbumSort
import com.photoxt.core.model.AlbumSortField
import com.photoxt.core.model.ItemSort
import com.photoxt.core.model.ItemSortField

/**
 * The Sort sheet of an item list (spec §4.2): its sorts, the direction, and for an album *Apply
 * to all albums* ([onApplyToAll], null elsewhere). A newly picked sort starts in its natural
 * direction; the toggle reverses it. Custom order has none.
 */
@Composable
fun ItemSortSheet(
    sort: ItemSort,
    fields: List<ItemSortField>,
    onSort: (ItemSort) -> Unit,
    onDismiss: () -> Unit,
    onApplyToAll: ((ItemSort) -> Unit)? = null
) {
    SortSheet(
        options = fields.map { it.label },
        selected = fields.indexOf(sort.field),
        directions = if (sort.field == ItemSortField.CUSTOM) null else sort.field.directions,
        descending = sort.descending,
        onPick = { onSort(ItemSort.of(fields[it])) },
        onDirection = { onSort(sort.copy(descending = it)) },
        applyToAll = onApplyToAll?.let { apply -> { apply(sort) } },
        onDismiss = onDismiss
    )
}

/** The Albums tab's Sort sheet (spec §4.2). */
@Composable
fun AlbumSortSheet(sort: AlbumSort, onSort: (AlbumSort) -> Unit, onDismiss: () -> Unit) {
    val fields = AlbumSortField.entries
    SortSheet(
        options = fields.map { it.label },
        selected = fields.indexOf(sort.field),
        directions = if (sort.field == AlbumSortField.CUSTOM) null else sort.field.directions,
        descending = sort.descending,
        onPick = { onSort(AlbumSort.of(fields[it])) },
        onDirection = { onSort(sort.copy(descending = it)) },
        applyToAll = null,
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    options: List<Int>,
    selected: Int,
    /** The labels of descending and ascending, or null when the sort has no direction. */
    directions: Pair<Int, Int>?,
    descending: Boolean,
    onPick: (Int) -> Unit,
    onDirection: (descending: Boolean) -> Unit,
    applyToAll: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.sort_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            options.forEachIndexed { index, label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = index == selected,
                            role = Role.RadioButton,
                            onClick = { onPick(index) }
                        )
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    RadioButton(selected = index == selected, onClick = null)
                    Text(stringResource(label), Modifier.padding(start = 16.dp))
                }
            }
            if (directions != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    listOf(true to directions.first, false to directions.second)
                        .forEachIndexed { index, (down, label) ->
                            SegmentedButton(
                                selected = descending == down,
                                onClick = { onDirection(down) },
                                shape = SegmentedButtonDefaults.itemShape(index, 2)
                            ) { Text(stringResource(label)) }
                        }
                }
            }
            if (applyToAll != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = {
                        applyToAll()
                        onDismiss()
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text(stringResource(R.string.sort_apply_to_all)) }
            }
        }
    }
}

@get:StringRes
private val ItemSortField.label: Int
    get() = when (this) {
        ItemSortField.DATE_TAKEN -> R.string.sort_date_taken
        ItemSortField.DATE_ADDED -> R.string.sort_date_added
        ItemSortField.DATE_MODIFIED -> R.string.sort_date_modified
        ItemSortField.NAME -> R.string.sort_name
        ItemSortField.SIZE -> R.string.sort_size
        ItemSortField.CUSTOM -> R.string.sort_custom
    }

private val ItemSortField.directions: Pair<Int, Int>
    get() = when (this) {
        ItemSortField.NAME -> R.string.sort_z_to_a to R.string.sort_a_to_z
        ItemSortField.SIZE -> R.string.sort_largest to R.string.sort_smallest
        else -> R.string.sort_newest to R.string.sort_oldest
    }

@get:StringRes
private val AlbumSortField.label: Int
    get() = when (this) {
        AlbumSortField.CUSTOM -> R.string.sort_custom
        AlbumSortField.NAME -> R.string.sort_name
        AlbumSortField.NEWEST_ITEM -> R.string.sort_newest_item
        AlbumSortField.DATE_CREATED -> R.string.sort_date_created
        AlbumSortField.ITEM_COUNT -> R.string.sort_item_count
        AlbumSortField.TOTAL_SIZE -> R.string.sort_total_size
    }

private val AlbumSortField.directions: Pair<Int, Int>
    get() = when (this) {
        AlbumSortField.NAME -> R.string.sort_z_to_a to R.string.sort_a_to_z

        AlbumSortField.ITEM_COUNT, AlbumSortField.TOTAL_SIZE ->
            R.string.sort_largest to R.string.sort_smallest

        else -> R.string.sort_newest to R.string.sort_oldest
    }

/** The sorts of every item list (spec §4.2); Pictures offers [PICTURES_SORTS] only. */
val ITEM_SORTS: List<ItemSortField> = ItemSortField.entries
val PICTURES_SORTS: List<ItemSortField> = listOf(ItemSortField.DATE_TAKEN, ItemSortField.DATE_ADDED)

/** The first letter of a name, for the fast scroller of a Name sort. */
fun initialOf(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#"
