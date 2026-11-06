package com.photoxt.feature.viewer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size as CoilSize
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.designsystem.R as DesignR
import com.photoxt.core.media.DriveOriginalRef
import com.photoxt.core.ui.ImmersiveEffect
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * Compare (spec §4.5): two photos side by side, or over each other with a split slider, zoomed
 * and panned together, to pick the sharper shot of a burst.
 */
@Composable
fun CompareScreen(
    firstId: String,
    secondId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<CompareViewModel, CompareViewModel.Factory> {
        it.create(firstId, secondId)
    }
    val pair by viewModel.pair.collectAsStateWithLifecycle()
    var split by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    ImmersiveEffect(barsVisible = true)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        pair?.let { (first, second) ->
            val state = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
            LaunchedEffect(state, first.id) {
                state.setContentLocation(
                    ZoomableContentLocation.scaledInsideAndCenterAligned(first.displaySize())
                )
            }
            if (split) SplitCompare(first, second, state) else SideBySide(first, second, state)
        }
        CompareBar(split, onSplit = { split = it }, onClose = onClose)
    }
}

/** Both photos in their own half, zoomed and panned as one: a gesture on either moves both. */
@Composable
private fun SideBySide(first: ViewerItem, second: ViewerItem, state: ZoomableState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                ComparedPhoto(first, state, Modifier.weight(1f))
                ComparedPhoto(second, state, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ComparedPhoto(first, state, Modifier.weight(1f))
                ComparedPhoto(second, state, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ComparedPhoto(item: ViewerItem, state: ZoomableState, modifier: Modifier) {
    val context = LocalContext.current
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .zoomable(state)
    ) {
        AsyncImage(
            model = remember(item.id) { compareRequest(context, item) },
            contentDescription = describeItem(item),
            contentScale = ContentScale.Inside,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * One photo over the other: the second shows right of the slider. Gestures zoom the first, and the
 * second copies its transformation, so the two always line up.
 */
@Composable
private fun SplitCompare(first: ViewerItem, second: ViewerItem, state: ZoomableState) {
    val context = LocalContext.current
    var fraction by rememberSaveable { mutableFloatStateOf(HALF) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .zoomable(state)
        ) {
            AsyncImage(
                model = remember(first.id) { compareRequest(context, first) },
                contentDescription = describeItem(first),
                contentScale = ContentScale.Inside,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(left = size.width * fraction) { this@drawWithContent.drawContent() }
                }
        ) {
            AsyncImage(
                model = remember(second.id) { compareRequest(context, second) },
                contentDescription = describeItem(second),
                contentScale = ContentScale.Inside,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val transformation = state.contentTransformation
                        if (transformation.isSpecified) {
                            scaleX = transformation.scale.scaleX
                            scaleY = transformation.scale.scaleY
                            translationX = transformation.offset.x
                            translationY = transformation.offset.y
                            transformOrigin = transformation.transformOrigin
                        }
                    }
            )
        }
        SplitHandle(
            offsetPx = widthPx * fraction,
            onDrag = { dx -> fraction = (fraction + dx / widthPx).coerceIn(0f, 1f) }
        )
    }
}

@Composable
private fun SplitHandle(offsetPx: Float, onDrag: (Float) -> Unit) {
    val half = with(LocalDensity.current) { HandleWidth.toPx() / 2f }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset { IntOffset((offsetPx - half).roundToInt(), 0) }
            .width(HandleWidth)
            .fillMaxHeight()
            .pointerInput(Unit) { detectHorizontalDragGestures { _, dx -> onDrag(dx) } }
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White)
        )
        Box(
            Modifier
                .size(28.dp)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
private fun CompareBar(split: Boolean, onSplit: (Boolean) -> Unit, onClose: () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(SCRIM))
                .statusBarsPadding()
                .padding(4.dp)
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(DesignR.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back)
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.padding(start = 8.dp)) {
                SegmentedButton(
                    selected = !split,
                    onClick = { onSplit(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.compare_side_by_side)) }
                SegmentedButton(
                    selected = split,
                    onClick = { onSplit(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.compare_split)) }
            }
        }
    }
}

// ponytail: each photo decodes once at up to 4096 px (Coil's limit), sharp to about 1:1 for
// 16 MP shots; tiled decoding as in the viewer if bigger photos need to be compared up close.
private fun compareRequest(context: Context, item: ViewerItem): ImageRequest =
    ImageRequest.Builder(context)
        .data(
            item.contentUri ?: item.drive { id, version ->
                DriveOriginalRef(item.id, id, version)
            }
        )
        .size(CoilSize.ORIGINAL)
        .placeholderMemoryCacheKey(item.thumbnailKey())
        .allowHardware(true)
        .build()

/** The two items Compare shows. */
@HiltViewModel(assistedFactory = CompareViewModel.Factory::class)
class CompareViewModel @AssistedInject constructor(
    @Assisted("first") firstId: String,
    @Assisted("second") secondId: String,
    mediaDao: MediaDao
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("first") firstId: String,
            @Assisted("second") secondId: String
        ): CompareViewModel
    }

    private val loaded = MutableStateFlow<Pair<ViewerItem, ViewerItem>?>(null)
    val pair: StateFlow<Pair<ViewerItem, ViewerItem>?> = loaded.asStateFlow()

    init {
        viewModelScope.launch {
            val first = mediaDao.viewerItem(firstId)
            val second = mediaDao.viewerItem(secondId)
            if (first != null && second != null) loaded.value = first to second
        }
    }
}

private const val HALF = 0.5f
private const val SCRIM = 0x99000000
private val HandleWidth = 48.dp
