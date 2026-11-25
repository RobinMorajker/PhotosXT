package com.photoxt.feature.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.photoxt.core.common.PxtLog
import com.photoxt.core.database.AlbumDao
import com.photoxt.core.database.AlbumSummary
import com.photoxt.core.database.CollageDao
import com.photoxt.core.database.CollageProjectEntity
import com.photoxt.core.database.MediaDao
import com.photoxt.core.database.MediaListQuery
import com.photoxt.core.database.ViewerItem
import com.photoxt.core.database.viewerItems
import com.photoxt.core.media.DriveOriginals
import com.photoxt.core.media.Original
import com.photoxt.core.ml.PhotoDetectors
import com.photoxt.core.model.COLLAGE_MAX_PHOTOS
import com.photoxt.core.model.CollageOutput
import com.photoxt.core.model.CollageProject
import com.photoxt.core.model.MediaFilter
import com.photoxt.core.model.MediaKind
import com.photoxt.core.model.Rect01
import com.photoxt.core.model.SourceType
import com.photoxt.core.render.Box
import com.photoxt.core.render.CellPixels
import com.photoxt.core.render.CollageGeometry
import com.photoxt.core.render.CollageJson
import com.photoxt.core.render.CollagePhotos
import com.photoxt.core.render.CollageRenderer
import com.photoxt.core.render.CollageSizing
import com.photoxt.core.render.EditAssets
import com.photoxt.core.render.LookSession
import com.photoxt.core.render.Size
import com.photoxt.core.transfer.CollageOps
import com.photoxt.core.transfer.SourceCounts
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * What every part of the studio shares (spec §4.11): the project and its history, the screen's
 * state and its messages. Edits made while a finger moves change the project only; letting go
 * records a step, of the last 100.
 */
class CollageCore(
    val scope: CoroutineScope,
    private val events: Channel<CollageEvent>,
    private val changed: (CollageProject, Boolean) -> Unit
) {
    val state = MutableStateFlow(CollageUi())

    val project: CollageProject get() = state.value.project

    /** Changes the project; with a [label], records a step of the history. */
    fun edit(label: Int?, change: (CollageProject) -> CollageProject) {
        state.update { s ->
            val project = change(s.project)
            if (label == null) {
                s.copy(project = project)
            } else {
                val kept = s.steps.take(s.stepIndex + 1)
                if (kept.lastOrNull()?.project == project) {
                    s.copy(project = project)
                } else {
                    val steps = (kept + CollageStep(label, project)).takeLast(MAX_STEPS)
                    s.copy(project = project, steps = steps, stepIndex = steps.lastIndex)
                }
            }
        }
        changed(project, label != null)
    }

    /** Records the project as it is now, after a gesture. */
    fun commit(label: Int) = edit(label) { it }

    fun undo() = jumpTo(state.value.stepIndex - 1)

    fun redo() = jumpTo(state.value.stepIndex + 1)

    private fun jumpTo(index: Int) {
        state.update { s ->
            if (index !in
                s.steps.indices
            ) {
                s
            } else {
                s.copy(project = s.steps[index].project, stepIndex = index)
            }
        }
        changed(project, true)
    }

    fun update(change: (CollageUi) -> CollageUi) = state.update(change)

    fun say(text: Int) {
        scope.launch { events.send(CollageEvent.Message(text)) }
    }

    fun send(event: CollageEvent) {
        scope.launch { events.send(event) }
    }

    private companion object {
        const val MAX_STEPS = 100
    }
}

/** What the studio's screen asks of its view model, so a test can draw it with a stand-in. */
interface CollageStudio {
    val edits: CollageEdits
    val assets: EditAssets

    /** The albums a collage can be saved into, for the export dialog. */
    val albums: Flow<List<AlbumSummary>>

    /** Every photo of Pictures, for replacing a cell's photo. */
    val library: Flow<PagingData<ViewerItem>>

    fun undo()

    fun redo()

    fun setTool(tool: CollageTool?)

    fun pick(picked: Picked?)

    /** Opens or closes the photo picker that replaces cell [cell]'s photo. */
    fun replacing(cell: Int?)

    /** Puts photo [item] in cell [cell] (spec §4.11, "replace a photo"). */
    fun replace(cell: Int, item: ViewerItem)

    /** The album the collage goes into unless the user picks another: its first photo's. */
    suspend fun defaultAlbum(): Pair<String, String>?

    suspend fun counts(): SourceCounts

    /** Queues the export (spec §4.11) and closes; the app says when it is saved. */
    fun export(output: CollageOutput, albumId: String, albumTitle: String, deleteSources: Boolean)
}

/**
 * The collage studio (spec §4.11): a project made from 2–20 photos or reopened from its draft,
 * each photo decoded once at a screen-sized proxy (a Drive photo downloaded first), looks drawn
 * through the editor's shader, the draft saved as it changes, and the export queued.
 */
@HiltViewModel(assistedFactory = CollageViewModel.Factory::class)
class CollageViewModel @AssistedInject constructor(
    @Assisted private val openDraft: String?,
    @Assisted private val newPhotos: List<String>,
    @param:ApplicationContext private val context: Context,
    private val savedState: SavedStateHandle,
    private val mediaDao: MediaDao,
    private val drafts: CollageDao,
    private val albumDao: AlbumDao,
    private val originals: DriveOriginals,
    private val detectors: PhotoDetectors,
    private val ops: CollageOps,
    override val assets: EditAssets
) : ViewModel(),
    CollageStudio {
    @AssistedFactory
    interface Factory {
        fun create(draftId: String?, mediaIds: List<String>): CollageViewModel
    }

    private val events = Channel<CollageEvent>(Channel.BUFFERED)
    val event: Flow<CollageEvent> = events.receiveAsFlow()
    private val saves = MutableStateFlow<CollageProject?>(null)
    private val core = CollageCore(viewModelScope, events, ::changed)
    val state: StateFlow<CollageUi> = core.state.asStateFlow()

    private val _proxies = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /** Each photo decoded at a screen-sized proxy, upright. */
    val proxies: StateFlow<Map<String, Bitmap>> = _proxies.asStateFlow()

    private val _looked = MutableStateFlow<Map<String, Bitmap>>(emptyMap())

    /** Proxies with their cell's look, by [lookKey]. */
    val looked: StateFlow<Map<String, Bitmap>> = _looked.asStateFlow()
    private val looks = LookSession(assets)
    private val lookJobs = HashSet<String>()
    private val faces = HashMap<String, List<Rect01>>()

    override val edits = CollageEdits(
        core,
        ::facesOf,
        labels = BeforeAfterLabels(
            context.getString(R.string.collage_before),
            context.getString(R.string.collage_after)
        )
    )

    override val albums: Flow<List<AlbumSummary>> = albumDao.albums()

    override val library: Flow<PagingData<ViewerItem>> = Pager(
        PagingConfig(pageSize = LIBRARY_PAGE)
    ) {
        mediaDao.viewerItems(MediaListQuery(filter = MediaFilter.PHOTOS))
    }.flow.cachedIn(viewModelScope)

    init {
        viewModelScope.launch { open() }
        viewModelScope.launch { autosave() }
    }

    // --- Opening ----------------------------------------------------------------------------

    private suspend fun open() {
        val draftId = savedState.get<String>(KEY_DRAFT) ?: openDraft ?: UUID.randomUUID().toString()
        savedState[KEY_DRAFT] = draftId
        val saved = drafts.draft(draftId)?.let {
            runCatching { CollageJson.decode(it.layoutJson) }.getOrNull()
        }
        val ids = saved?.allMediaIds ?: newPhotos.take(COLLAGE_MAX_PHOTOS)
        val items = withContext(Dispatchers.IO) {
            ids.chunked(MAX_SQL).flatMap { mediaDao.viewerItems(it) }
                .filter { it.kind == MediaKind.IMAGE }
                .associateBy { it.id }
        }
        val shown = ids.filter { it in items }
        if (saved == null && shown.size < 2) {
            core.update { it.copy(phase = CollagePhase.Failed(R.string.collage_error_photos)) }
            return
        }
        val infos = items.mapValues { (_, item) -> infoOf(item) }
        val project = saved ?: Starts.grid(shown)
        core.update {
            it.copy(
                phase = CollagePhase.Ready,
                draftId = draftId,
                project = project,
                photos = infos,
                steps = listOf(CollageStep(R.string.collage_step_open, project)),
                stepIndex = 0
            )
        }
        if (saved == null) saves.value = project
        loadProxies(items.values.toList())
    }

    /** Proxies of [items], a few at a time; Drive originals download first, with progress. */
    private suspend fun loadProxies(items: List<ViewerItem>) {
        val edge = proxyEdge(items.size)
        val drive = items.filter { it.source == SourceType.DRIVE }
        val progress = HashMap<String, Float>()
        if (drive.isNotEmpty()) core.update { it.copy(downloading = 0f) }
        val gate = Semaphore(DECODERS)
        val jobs = items.map { item ->
            viewModelScope.launch {
                gate.withPermit {
                    val bitmap = runCatching {
                        proxyOf(item, edge) { f ->
                            // Only Drive photos report progress: the share of them downloaded.
                            val done = synchronized(progress) {
                                progress[item.id] = f
                                progress.values.sum()
                            }
                            core.update { it.copy(downloading = done / drive.size) }
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        PxtLog.w(TAG, "A collage photo couldn't be read", it)
                    }.getOrNull() ?: return@withPermit
                    _proxies.update { it + (item.id to bitmap) }
                    fixAspect(item.id, bitmap)
                    if (item.source ==
                        SourceType.DRIVE
                    ) {
                        synchronized(progress) { progress[item.id] = 1f }
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        core.update { it.copy(downloading = null) }
        refreshLooks(core.project)
    }

    private suspend fun proxyOf(item: ViewerItem, edge: Int, progress: (Float) -> Unit): Bitmap? {
        val source = if (item.source == SourceType.DRIVE) {
            var file: File? = null
            originals.original(item.id).first { state ->
                when (state) {
                    is Original.Loading -> {
                        progress(state.fraction)
                        false
                    }

                    is Original.Ready -> {
                        file = state.file
                        true
                    }

                    is Original.Failed -> true
                }
            }
            file?.let { ImageDecoder.createSource(it) } ?: return null
        } else {
            val uri = item.contentUri ?: return null
            ImageDecoder.createSource(context.contentResolver, uri.toUri())
        }
        return withContext(Dispatchers.IO) { decode(source, edge) }
    }

    /** The photo's upright aspect as decoded, which the layout trusts over the index's sizes. */
    private fun fixAspect(id: String, proxy: Bitmap) {
        core.update { s ->
            val info = s.photos[id] ?: return@update s
            val shown = proxy.width.toDouble() / proxy.height
            if (abs(info.aspect / shown - 1) <= ASPECT_SLACK) return@update s
            val turned = Size(info.size.height, info.size.width)
            val size = if (abs(turned.width.toDouble() / turned.height / shown - 1) <=
                ASPECT_SLACK
            ) {
                turned
            } else {
                Size(proxy.width, proxy.height)
            }
            s.copy(photos = s.photos + (id to info.copy(size = size)))
        }
    }

    // --- Pictures ----------------------------------------------------------------------------

    /** The studio's photos for [CollageRenderer]: proxies, with looks once they are drawn. */
    fun photosFor(
        project: CollageProject,
        proxies: Map<String, Bitmap>,
        looked: Map<String, Bitmap>
    ) = object : CollagePhotos {
        override fun pixels(cell: Int, frame: CollageGeometry.PhotoFrame): CellPixels? {
            val state = project.cells.getOrNull(cell) ?: return null
            val id = state.mediaId ?: return null
            val look = state.look?.takeUnless { it.isIdentity }
            val bitmap = look?.let { looked[lookKey(id, it)] } ?: proxies[id] ?: return null
            return CellPixels(bitmap, Box.WHOLE)
        }

        override fun small(cell: Int): Bitmap? =
            project.cells.getOrNull(cell)?.mediaId?.let { proxies[it] }
    }

    /** Draws each shown cell's look on its proxy, once per look; drops the ones not needed. */
    private fun refreshLooks(project: CollageProject) {
        val wanted = project.shownCells.mapNotNull { i ->
            val cell = project.cells.getOrNull(i) ?: return@mapNotNull null
            val look = cell.look?.takeUnless { it.isIdentity } ?: return@mapNotNull null
            val id = cell.mediaId ?: return@mapNotNull null
            Triple(lookKey(id, look), id, look)
        }
        val keys = wanted.map { it.first }.toSet()
        _looked.update { it.filterKeys(keys::contains) }
        wanted.filter { (key) ->
            key !in _looked.value && lookJobs.add(key)
        }.forEach { (key, id, look) ->
            val proxy = _proxies.value[id] ?: return@forEach lookJobs.remove(key).let {}
            viewModelScope.launch {
                runCatching { looks.looked(proxy, look) }
                    .onSuccess { bitmap -> _looked.update { it + (key to bitmap) } }
                    .onFailure { PxtLog.w(TAG, "A look couldn't be drawn", it) }
                lookJobs.remove(key)
            }
        }
    }

    /** Faces in each of [ids]' proxies, as upright fractions; found once per photo. */
    private suspend fun facesOf(ids: List<String>): Map<String, List<Rect01>> {
        ids.filter { it !in faces }.forEach { id ->
            val proxy = _proxies.value[id] ?: return@forEach
            faces[id] = runCatching { detectors.faces(proxy) }.getOrElse {
                if (it is CancellationException) throw it
                PxtLog.w(TAG, "Faces couldn't be found", it)
                emptyList()
            }.map { f ->
                Rect01(
                    f.box.left.toFloat() / proxy.width,
                    f.box.top.toFloat() / proxy.height,
                    f.box.right.toFloat() / proxy.width,
                    f.box.bottom.toFloat() / proxy.height
                )
            }
        }
        return ids.associateWith { faces[it].orEmpty() }
    }

    // --- Drafts --------------------------------------------------------------------------------

    private fun changed(project: CollageProject, committed: Boolean) {
        refreshLooks(project)
        if (committed && state.value.phase == CollagePhase.Ready) saves.value = project
    }

    @OptIn(FlowPreview::class)
    private suspend fun autosave() {
        saves.filterNotNull().debounce(SAVE_DELAY_MS).collect { project -> saveDraft(project) }
    }

    private suspend fun saveDraft(project: CollageProject) {
        val id = state.value.draftId.ifEmpty { return }
        val now = System.currentTimeMillis()
        val old = drafts.draft(id)
        drafts.save(
            CollageProjectEntity(
                id = id,
                layoutJson = CollageJson.encode(project),
                itemRefs = project.allMediaIds.joinToString("\n"),
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
                exportedMediaId = old?.exportedMediaId
            )
        )
        runCatching { thumbnail(project, id) }.onFailure { PxtLog.w(TAG, "No draft picture", it) }
    }

    /** The drafts list's picture of [project], next to the drafts' other pictures. */
    private suspend fun thumbnail(project: CollageProject, id: String) =
        withContext(Dispatchers.Default) {
            val ui = state.value
            val size = CollageSizing.preview(project, THUMB_EDGE) { ui.size(it) }
            val bitmap = createBitmap(size.width, size.height)
            val geometry = CollageGeometry(project, size.width, size.height) { ui.aspect(it) }
            CollageRenderer(assets).draw(
                Canvas(bitmap),
                geometry,
                photosFor(project, _proxies.value, _looked.value),
                opaque = false
            )
            val file = draftPicture(context, id)
            file.parentFile?.mkdirs()
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, THUMB_QUALITY, it)
            }
            bitmap.recycle()
        }

    // --- Export ------------------------------------------------------------------------------

    override suspend fun defaultAlbum(): Pair<String, String>? {
        val id = ops.defaultAlbum(core.project.mediaIds) ?: return null
        val title = albumDao.albumRow(id)?.title ?: return null
        return id to title
    }

    override suspend fun counts(): SourceCounts = ops.counts(core.project.mediaIds)

    override fun export(
        output: CollageOutput,
        albumId: String,
        albumTitle: String,
        deleteSources: Boolean
    ) {
        viewModelScope.launch {
            val project = core.project
            saveDraft(project)
            val batch = ops.export(
                CollageJson.encode(project),
                output,
                albumId,
                deleteSources,
                state.value.draftId,
                albumTitle
            )
            if (batch == null) {
                core.say(R.string.collage_error_album)
            } else {
                core.send(CollageEvent.Closed(R.string.collage_saving))
            }
        }
    }

    override fun undo() = core.undo()

    override fun redo() = core.redo()

    override fun setTool(tool: CollageTool?) = core.update { it.copy(tool = tool) }

    override fun pick(picked: Picked?) = core.update {
        val tool = when (picked) {
            is Picked.Cell -> CollageTool.CELL

            is Picked.Layer -> it.tool.takeIf { t ->
                t == CollageTool.TEXT ||
                    t == CollageTool.STICKERS
            }
                ?: CollageTool.TEXT

            null -> it.tool
        }
        it.copy(picked = picked, tool = tool)
    }

    override fun replacing(cell: Int?) = core.update { it.copy(replacing = cell) }

    override fun replace(cell: Int, item: ViewerItem) {
        core.update { s -> s.copy(photos = s.photos + (item.id to infoOf(item)), replacing = null) }
        edits.cells.replacePhoto(cell, item.id)
        if (item.id !in _proxies.value) viewModelScope.launch { loadProxies(listOf(item)) }
    }

    override fun onCleared() {
        looks.close()
    }

    private fun infoOf(item: ViewerItem): PhotoInfo {
        val turned = item.rotation % HALF_TURN == QUARTER_TURN
        val w = max(1, if (turned) item.height else item.width)
        val h = max(1, if (turned) item.width else item.height)
        return PhotoInfo(Size(w, h), item.source == SourceType.DRIVE)
    }

    companion object {
        private const val TAG = "Collage"
        private const val LIBRARY_PAGE = 90
        private const val KEY_DRAFT = "draft"
        private const val MAX_SQL = 900
        private const val DECODERS = 3
        private const val SAVE_DELAY_MS = 800L
        private const val THUMB_EDGE = 480
        private const val THUMB_QUALITY = 82
        private const val ASPECT_SLACK = 0.02
        private const val HALF_TURN = 180
        private const val QUARTER_TURN = 90
        private const val MIN_PROXY = 768
        private const val MAX_PROXY = 2048
        private const val PROXY_SPREAD = 1.5

        /** Smaller proxies for more photos: about the same memory for 2 or 20. */
        fun proxyEdge(count: Int): Int = (
            MAX_PROXY / sqrt(
                max(1, count).toDouble()
            ) * PROXY_SPREAD
            ).roundToInt().coerceIn(MIN_PROXY, MAX_PROXY)

        fun lookKey(mediaId: String, look: com.photoxt.core.model.EditRecipe) =
            "$mediaId|${look.hashCode()}"

        private fun decode(source: ImageDecoder.Source, edge: Int): Bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = min(1.0, edge.toDouble() / max(w, h))
                if (scale < 1) {
                    decoder.setTargetSize(
                        max(1, (w * scale).roundToInt()),
                        max(1, (h * scale).roundToInt())
                    )
                }
            }.also { it.setHasMipMap(true) }
    }
}

/** A draft's picture for the drafts list. */
fun draftPicture(context: Context, draftId: String): File =
    File(context.filesDir, "collages/$draftId.webp")
