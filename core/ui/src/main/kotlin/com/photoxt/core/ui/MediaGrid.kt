package com.photoxt.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.model.DateGrouping
import java.time.ZoneId
import kotlinx.coroutines.flow.collectLatest

/** One row of a media grid: a date header, or a photo or video. */
@Immutable
sealed interface GridCell {
    val key: Any

    data class Header(val groupKey: Int) : GridCell {
        override val key: Any get() = "header:$groupKey"
    }

    data class Item(
        val id: String,
        /** Coil model of the thumbnail. */
        val thumbnail: Any?,
        val isVideo: Boolean,
        val durationMs: Long?,
        val favorite: Boolean,
        val takenAt: Long
    ) : GridCell {
        override val key: Any get() = id
    }
}

/** Pinch levels, densest first, Samsung style: years, months, then days in 7 down to 1 columns. */
enum class GridDensity(val grouping: DateGrouping, val columns: Int) {
    YEAR(DateGrouping.YEAR, 12),
    MONTH(DateGrouping.MONTH, 9),
    DAY_7(DateGrouping.DAY, 7),
    DAY_6(DateGrouping.DAY, 6),
    DAY_5(DateGrouping.DAY, 5),
    DAY_4(DateGrouping.DAY, 4),
    DAY_3(DateGrouping.DAY, 3),
    DAY_2(DateGrouping.DAY, 2),
    DAY_1(DateGrouping.DAY, 1);

    /** Year and month tiles are too small for badges. */
    val compact: Boolean get() = grouping != DateGrouping.DAY

    fun denser(): GridDensity = entries.getOrElse(ordinal - 1) { this }

    fun larger(): GridDensity = entries.getOrElse(ordinal + 1) { this }

    companion object {
        val Default = DAY_4
    }
}

/**
 * The paged photo grid of Pictures and of every album (spec §4.2): exact date headers, pinch to
 * change density, long-press and drag to select with auto-scroll, and a select-all control on
 * every date header while selecting.
 *
 * [rows] come from a timeline query (each header followed by its items) and [layout] from the
 * same query's group counts. [selection] maps selected ids to their capture times. Taps open an
 * item through [onOpen], or toggle it while selecting.
 */
@Composable
fun MediaGrid(
    rows: LazyPagingItems<GridCell>,
    layout: TimelineLayout,
    density: GridDensity,
    onDensityChange: (GridDensity) -> Unit,
    selection: Map<String, Long>,
    onSelectionChange: (Map<String, Long>) -> Unit,
    onSelectGroup: (groupKey: Int, select: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(),
    onOpen: ((GridCell.Item) -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val leadingCount = if (leading != null) 1 else 0
    val onPinch = rememberDensityPinch(state, rows, layout, density, leadingCount, onDensityChange)
    val gestures =
        rememberGridGestures(state, rows, leadingCount, selection, onSelectionChange, onOpen)
    val labels = rememberDateGroupLabels(density.grouping)
    val selectedPerGroup = remember(selection, density.grouping) {
        val zone = ZoneId.systemDefault()
        selection.values.groupingBy { density.grouping.keyOf(it, zone) }.eachCount()
    }
    BoxWithConstraints(modifier) {
        // Wider windows keep a phone's tile size by multiplying the columns.
        val columns = density.columns * (maxWidth / PhoneWidth).toInt().coerceAtLeast(1)
        val gap = if (density.compact) 1.dp else 2.dp
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .fillMaxSize()
                .testTag(MEDIA_GRID_TEST_TAG)
                .pinchToZoom(onPinch)
                .pointerInput(gestures) { gestures.detect(this) }
        ) {
            if (leading != null) {
                item(key = LEADING_KEY, span = { GridItemSpan(maxLineSpan) }, contentType = 0) {
                    leading()
                }
            }
            items(
                count = rows.itemCount,
                key = rows.itemKey { it.key },
                span = { row ->
                    val cell = rows.peek(row)
                    val header = if (cell == null) layout.isHeader(row) else cell is GridCell.Header
                    GridItemSpan(if (header) maxLineSpan else 1)
                },
                contentType = rows.itemContentType { if (it is GridCell.Header) 1 else 2 }
            ) { row ->
                GridRow(
                    cell = rows[row],
                    headerRow = layout.isHeader(row),
                    label = labels,
                    density = density,
                    selection = selection,
                    groupComplete = { key ->
                        layout.countOfKey(key).let { it > 0 && selectedPerGroup[key] == it }
                    },
                    gestures = gestures,
                    onSelectGroup = onSelectGroup,
                    onOpen = onOpen
                )
            }
        }
    }
}

@Composable
private fun GridRow(
    cell: GridCell?,
    headerRow: Boolean,
    label: (Int) -> String,
    density: GridDensity,
    selection: Map<String, Long>,
    groupComplete: (Int) -> Boolean,
    gestures: GridGestures,
    onSelectGroup: (groupKey: Int, select: Boolean) -> Unit,
    onOpen: ((GridCell.Item) -> Unit)?
) {
    when (cell) {
        is GridCell.Header -> GroupHeader(
            label = label(cell.groupKey),
            selecting = selection.isNotEmpty(),
            allSelected = groupComplete(cell.groupKey),
            onSelectAll = { onSelectGroup(cell.groupKey, it) }
        )

        is GridCell.Item -> MediaTile(
            item = cell,
            compact = density.compact,
            selecting = selection.isNotEmpty(),
            selected = cell.id in selection,
            onTap = { gestures.onTap(cell) },
            onSelect = { gestures.onSelectionChange(selection.toggled(cell)) },
            open = onOpen?.let { { it(cell) } }
        )

        null -> RowPlaceholder(header = headerRow)
    }
}

/**
 * The pinch handler. Changing the grouping (day, month, year) replaces every row, so it also
 * remembers which photo was at the top and scrolls back to it once the new rows have arrived.
 */
@Composable
private fun rememberDensityPinch(
    state: LazyGridState,
    rows: LazyPagingItems<GridCell>,
    layout: TimelineLayout,
    density: GridDensity,
    leadingCount: Int,
    onDensityChange: (GridDensity) -> Unit
): (spread: Boolean) -> Unit {
    var anchor by remember { mutableStateOf<DensityAnchor?>(null) }
    LaunchedEffect(anchor, layout, rows.itemCount) {
        val pending = anchor ?: return@LaunchedEffect
        if (layout.grouping == pending.grouping && rows.itemCount == layout.rowCount) {
            state.scrollToItem(leadingCount + layout.rowOfItem(pending.ordinal))
            anchor = null
        }
    }
    val current by rememberUpdatedState(DensityRequest(density, layout, leadingCount))
    val change by rememberUpdatedState(onDensityChange)
    return remember(state) {
        { spread ->
            val (from, fromLayout, lead) = current
            val to = if (spread) from.larger() else from.denser()
            val firstRow = state.firstVisibleItemIndex - lead
            if (to.grouping != from.grouping && firstRow >= 0 && fromLayout.rowCount > 0) {
                anchor = DensityAnchor(to.grouping, fromLayout.itemOrdinalAt(firstRow))
            }
            if (to != from) change(to)
        }
    }
}

@Composable
private fun rememberGridGestures(
    state: LazyGridState,
    rows: LazyPagingItems<GridCell>,
    leadingCount: Int,
    selection: Map<String, Long>,
    onSelectionChange: (Map<String, Long>) -> Unit,
    onOpen: ((GridCell.Item) -> Unit)?
): GridGestures {
    val haptics = LocalHapticFeedback.current
    val gestures = remember(state) { GridGestures(state, haptics) }
    SideEffect {
        gestures.leadingCount = leadingCount
        gestures.rows = rows
        gestures.selection = selection
        gestures.onSelectionChange = onSelectionChange
        gestures.onTap = { item ->
            when {
                selection.isNotEmpty() -> onSelectionChange(selection.toggled(item))
                onOpen != null -> onOpen(item)
            }
        }
    }
    LaunchedEffect(gestures) {
        snapshotFlow { gestures.autoScrollSpeed }.collectLatest { speed ->
            while (speed != 0f) {
                withFrameNanos { }
                state.scrollBy(speed)
                gestures.afterAutoScroll()
            }
        }
    }
    return gestures
}

@Composable
private fun RowPlaceholder(header: Boolean) {
    if (header) {
        Spacer(Modifier.heightIn(min = HeaderHeight))
    } else {
        Box(
            Modifier
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    }
}

const val MEDIA_GRID_TEST_TAG = "media_grid"

private const val LEADING_KEY = "leading"
private const val PINCH_STEP = 1.25f
private val PhoneWidth = 360.dp
internal val HeaderHeight = 48.dp
private val AutoScrollEdge = 72.dp
private const val MAX_AUTO_SCROLL_PX = 36f

private data class DensityAnchor(val grouping: DateGrouping, val ordinal: Int)

private data class DensityRequest(
    val density: GridDensity,
    val layout: TimelineLayout,
    val leadingCount: Int
)

private fun Map<String, Long>.toggled(item: GridCell.Item): Map<String, Long> =
    if (item.id in this) this - item.id else this + (item.id to item.takenAt)

/**
 * [base] with every item between rows [from] and [to] (both included) added, or removed when
 * [adding] is false. Headers and unloaded rows are skipped.
 */
internal fun rangeSelection(
    base: Map<String, Long>,
    from: Int,
    to: Int,
    adding: Boolean,
    cellAt: (Int) -> GridCell?
): Map<String, Long> {
    val result = LinkedHashMap(base)
    for (row in minOf(from, to)..maxOf(from, to)) {
        val item = cellAt(row) as? GridCell.Item ?: continue
        if (adding) result[item.id] = item.takenAt else result.remove(item.id)
    }
    return result
}

/** One pinch changes the density by one level; spreading the fingers makes tiles larger. */
private fun Modifier.pinchToZoom(onPinch: (spread: Boolean) -> Unit) = pointerInput(onPinch) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var zoom = 1f
        var changed = false
        do {
            // The initial pass runs before the grid's own scrolling, which a pinch must stop.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } > 1) {
                zoom *= event.calculateZoom()
                if (!changed && (zoom > PINCH_STEP || zoom < 1f / PINCH_STEP)) {
                    changed = true
                    onPinch(zoom > 1f)
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Tap, long-press and drag-to-select for the whole grid, so tiles need no click handlers that
 * would fight the drag. A long-press selects the item under the finger (or, if it was already
 * selected, starts deselecting); dragging extends that action over every item between, and near
 * the top or bottom edge the grid scrolls on its own.
 */
private class GridGestures(private val state: LazyGridState, private val haptics: HapticFeedback) {
    var leadingCount = 0
    var rows: LazyPagingItems<GridCell>? = null
    var selection: Map<String, Long> = emptyMap()
    var onSelectionChange: (Map<String, Long>) -> Unit = {}
    var onTap: (GridCell.Item) -> Unit = {}

    var autoScrollSpeed by mutableFloatStateOf(0f)
        private set

    private var anchorRow = -1
    private var lastRow = -1
    private var pointer = Offset.Zero
    private var base: Map<String, Long> = emptyMap()
    private var adding = true

    suspend fun detect(scope: PointerInputScope) = with(scope) {
        val edge = AutoScrollEdge.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val wasScrolling = state.isScrollInProgress
            val longPress = awaitLongPressOrCancellation(down.id)
            if (longPress == null) {
                // Released before the long-press timeout: a tap, unless a child (a header's
                // select-all) took it, the grid scrolled, or the touch only stopped a fling.
                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                if (!wasScrolling && up.isUntakenRelease()) {
                    up?.let { itemAt(it.position) }?.let(onTap)
                }
            } else if (start(longPress.position)) {
                val completed = drag(longPress.id) { change ->
                    pointer = change.position
                    autoScrollSpeed = edgeSpeed(change.position.y, size.height.toFloat(), edge)
                    extendTo(rowAt(change.position))
                    change.consume()
                }
                if (completed) currentEvent.changes.forEach { if (!it.pressed) it.consume() }
                anchorRow = -1
                autoScrollSpeed = 0f
            }
        }
    }

    /** A still finger over a scrolling grid moves across items too. */
    fun afterAutoScroll() {
        if (anchorRow >= 0) extendTo(rowAt(pointer))
    }

    private fun start(position: Offset): Boolean {
        val row = rowAt(position)
        val item = row?.let { rows?.peek(it) } as? GridCell.Item ?: return false
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        base = selection
        adding = item.id !in base
        anchorRow = row
        lastRow = -1
        pointer = position
        extendTo(row)
        return true
    }

    private fun PointerInputChange?.isUntakenRelease(): Boolean =
        this != null && !pressed && !isConsumed

    private fun extendTo(row: Int?) {
        val items = rows ?: return
        if (row == null || row == lastRow || anchorRow < 0) return
        lastRow = row
        onSelectionChange(rangeSelection(base, anchorRow, row, adding) { items.peek(it) })
    }

    private fun itemAt(position: Offset): GridCell.Item? =
        rowAt(position)?.let { rows?.peek(it) as? GridCell.Item }

    private fun rowAt(position: Offset): Int? {
        val x = position.x.toInt()
        val y = position.y.toInt()
        val hit = state.layoutInfo.visibleItemsInfo.firstOrNull {
            x in it.offset.x until it.offset.x + it.size.width &&
                y in it.offset.y until it.offset.y + it.size.height
        } ?: return null
        return (hit.index - leadingCount).takeIf { it >= 0 }
    }

    private fun edgeSpeed(y: Float, height: Float, edge: Float): Float = when {
        y < edge -> -MAX_AUTO_SCROLL_PX * (edge - y.coerceAtLeast(0f)) / edge
        y > height - edge -> MAX_AUTO_SCROLL_PX * (y.coerceAtMost(height) - (height - edge)) / edge
        else -> 0f
    }
}
