package com.louisgeek.drawpad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Looper
import android.util.Base64
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.ink.brush.Brush
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.louisgeek.drawpad.model.AabbGrid
import com.louisgeek.drawpad.model.BrushConfig
import com.louisgeek.drawpad.model.BrushStyle
import com.louisgeek.drawpad.model.DocumentEdit
import com.louisgeek.drawpad.model.DocumentSnapshot
import com.louisgeek.drawpad.model.DrawPadUiState
import com.louisgeek.drawpad.model.DrawTool
import com.louisgeek.drawpad.model.ERASER_ACCENT
import com.louisgeek.drawpad.model.ERASER_SIZE_MULTIPLIER
import com.louisgeek.drawpad.model.EraseGeometry
import com.louisgeek.drawpad.model.EraseOperation
import com.louisgeek.drawpad.model.InkBrushFactory
import com.louisgeek.drawpad.model.InkStrokeRecord
import com.louisgeek.drawpad.storage.AppPrivateDocumentStore
import com.louisgeek.drawpad.storage.DocumentCodec
import com.louisgeek.drawpad.storage.DocumentStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Holds the drawing document and its edit history.
 *
 * All mutating APIs are confined to the **main thread**: the stroke/edit collections and the
 * revision/saved-revision protocol are not thread-safe, so callers must not invoke them from other
 * dispatchers (the suspend APIs internally hop to IO/Default for heavy work and return to Main).
 */
@MainThread
public class DrawPadState internal constructor(
    private val store: DocumentStore,
    private val scope: CoroutineScope,
) {
    private val brushFactory = InkBrushFactory()
    private val documentEdits = mutableListOf<DocumentEdit>()
    private val strokes = mutableStateListOf<InkStrokeRecord>()
    private val history = OperationHistory<HistoryCommand>(MAX_HISTORY)
    private val background = mutableStateOf<ByteArray?>(null)
    private val mutableUiState = mutableStateOf(DrawPadUiState())
    private val saveMutex = Mutex()
    /** Newest snapshot awaiting a save; older ones are superseded (see [persist]). */
    private var pendingSnapshot: DocumentSnapshot? = null
    private var pendingRevision = -1L

    /** Size (px) of the drawing surface the strokes' coordinates live in; set from composition. */
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var nextStrokeId = 1L
    private var revision = 0L
    private var savedRevision = -1L

    /** A drag is previewed live but committed as one undoable/persisted erase operation on lift. */
    private var activeEraser: ActiveEraser? = null

    public val uiState: State<DrawPadUiState> = mutableUiState
    public val backgroundPng: ByteArray? get() = background.value?.copyOf()

    /**
     * Non-copying view of the background bytes for internal UI use. Returning the same array keeps
     * a stable `remember` key (a fresh copy would defeat equality and re-decode the PNG on every
     * recomposition). Callers must treat it as read-only; the public [backgroundPng] still copies.
     */
    internal val backgroundBytes: ByteArray? get() = background.value

    internal fun onCanvasSizeChanged(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
    }

    internal val currentBrush: Brush get() = brushFor(mutableUiState.value.tool)
    internal val completedStrokes: List<Stroke> get() = strokes.map(InkStrokeRecord::toStroke)

    internal suspend fun restore() {
        mutableUiState.value = mutableUiState.value.copy(isLoading = true)
        val result = runCatching {
            withContext(Dispatchers.IO) { store.read()?.let(DocumentCodec::decode) }
        }
        result.onSuccess { snapshot ->
            if (snapshot != null) {
                documentEdits.clear()
                documentEdits.addAll(snapshot.edits)
                strokes.clear()
                strokes.addAll(snapshot.visibleStrokes)
                background.value = snapshot.backgroundPng
                nextStrokeId = snapshot.nextStrokeId
                // The restored document is a fresh baseline: any history from before the load is
                // stale (and its stroke ids may now collide with restored ones), so drop it.
                history.clear()
                // Bump revision so an in-flight persist() from before the load fails the
                // `revisionToSave < savedRevision` guard instead of overwriting the restored file.
                revision++
                savedRevision = revision
            }
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message ?: "文档恢复失败")
        }
        mutableUiState.value = mutableUiState.value.copy(isLoading = false)
        publish()
    }

    public fun setTool(tool: DrawTool) {
        cancelEraserTrail()
        mutableUiState.value = mutableUiState.value.copy(tool = tool)
    }

    /** Clears [DrawPadUiState.errorMessage], e.g. when the user dismisses an error banner. */
    public fun clearError() {
        if (mutableUiState.value.errorMessage != null) {
            mutableUiState.value = mutableUiState.value.copy(errorMessage = null)
        }
    }

    public fun toggleTool() = setTool(
        if (mutableUiState.value.tool == DrawTool.PEN) DrawTool.ERASER else DrawTool.PEN,
    )

    /**
     * Updates the pen/eraser brush. Returns `false` and leaves the current brush untouched when
     * [config] is invalid, instead of throwing [IllegalArgumentException] at a UI caller.
     */
    public fun setBrush(config: BrushConfig): Boolean {
        if (config.size <= 0f || config.epsilon <= 0f) return false
        cancelEraserTrail()
        mutableUiState.value = mutableUiState.value.copy(brush = config)
        return true
    }

    internal fun brushFor(tool: DrawTool): Brush {
        val config = mutableUiState.value.brush
        return if (tool == DrawTool.ERASER) {
            Brush.createWithComposeColor(
                // Never used to draw: CanvasSurface does not mount Ink's in-progress layer in eraser
                // mode, and the erase mask is built from the trail the pointer observer samples
                // (see beginEraserTrail/updateEraserTrail). This object only carries the erase footprint,
                // which the pink cursor reads as its radius.
                family = brushFactory.family(config.copy(style = BrushStyle.PRESSURE_PEN)),
                color = ERASER_ACCENT.copy(alpha = 0f),
                size = config.size * ERASER_SIZE_MULTIPLIER,
                epsilon = config.epsilon,
            )
        } else {
            Brush.createWithComposeColor(
                family = brushFactory.family(config),
                color = config.color,
                size = config.size,
                epsilon = config.epsilon,
            )
        }
    }

    public fun clear() {
        cancelEraserTrail()
        if (strokes.isEmpty() && background.value == null) return
        val before = DocumentSnapshot(
            documentEdits.toList(), strokes.toList(), background.value?.copyOf(), nextStrokeId,
        )
        execute(HistoryCommand.Clear(before)) {
            strokes.clear()
            documentEdits.clear()
            background.value = null
        }
    }

    public fun undo() {
        cancelEraserTrail()
        val command = history.undo() ?: return
        command.undo(this)
        revision++
        publish()
        persist()
    }

    public fun redo() {
        cancelEraserTrail()
        val command = history.redo() ?: return
        command.redo(this)
        revision++
        publish()
        persist()
    }

    public suspend fun importBackgroundPng(bytes: ByteArray) {
        cancelEraserTrail()
        withContext(Dispatchers.IO) {
            // Only read the header: a full decode just to validate wastes a multi-MB allocation.
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "无效的 PNG 图片" }
        }
        cancelEraserTrail()
        execute(HistoryCommand.Background(background.value?.copyOf(), bytes.copyOf())) {
            background.value = bytes.copyOf()
        }
    }

    public suspend fun importBase64Image(value: String) {
        val bytes = withContext(Dispatchers.Default) {
            Base64.decode(value, Base64.DEFAULT)
        }
        importBackgroundPng(bytes)
    }

    public suspend fun exportEditableDocument(): ByteArray {
        cancelEraserTrail()
        // Build the snapshot on the caller thread; [documentEdits] is Main-confined, so touching it
        // on a Default worker would race with edits.
        val snapshot = DocumentSnapshot(
            edits = documentEdits.toList(),
            visibleStrokes = strokes.toList(),
            backgroundPng = background.value?.copyOf(),
            nextStrokeId = nextStrokeId,
        )
        return withContext(Dispatchers.Default) { DocumentCodec.encode(snapshot) }
    }

    /**
     * Replaces the whole document with [document]. Unlike a background import this is a single
     * undoable step ([HistoryCommand.ReplaceDocument]); older history is discarded because its
     * commands refer to the previous document.
     */
    public suspend fun importEditableDocument(document: ByteArray) {
        cancelEraserTrail()
        val imported = withContext(Dispatchers.Default) { DocumentCodec.decode(document) }
        cancelEraserTrail()
        val before = DocumentSnapshot(
            edits = documentEdits.toList(),
            visibleStrokes = strokes.toList(),
            backgroundPng = background.value?.copyOf(),
            nextStrokeId = nextStrokeId,
        )
        history.clear()
        execute(HistoryCommand.ReplaceDocument(before, imported)) {
            applySnapshot(imported)
        }
    }

    /** Replaces the in-memory document with [snapshot] (used by undo/redo of a document replace). */
    private fun applySnapshot(snapshot: DocumentSnapshot) {
        documentEdits.clear(); documentEdits.addAll(snapshot.edits)
        strokes.clear(); strokes.addAll(snapshot.visibleStrokes)
        background.value = snapshot.backgroundPng?.copyOf()
        nextStrokeId = snapshot.nextStrokeId
    }

    public suspend fun exportPng(width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0)
        cancelEraserTrail()
        val strokeSnapshot = strokes.toList()
        val backgroundSnapshot = background.value?.copyOf()
        // Strokes are stored in the coordinate space of the drawing surface, so exports must map
        // that space onto the requested bitmap or ink and background end up misaligned.
        val sourceWidth = canvasWidth.takeIf { it > 0 } ?: width
        val sourceHeight = canvasHeight.takeIf { it > 0 } ?: height
        return withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
                val offsetX = (width - sourceWidth * scale) / 2f
                val offsetY = (height - sourceHeight * scale) / 2f

                if (backgroundSnapshot != null) {
                    val image = BitmapFactory.decodeByteArray(backgroundSnapshot, 0, backgroundSnapshot.size)
                    if (image != null) {
                        try {
                            // Draw the background inside the document space, preserving its aspect
                            // ratio (letterboxed), so it is neither stretched nor skewed.
                            val fit = minOf(sourceWidth.toFloat() / image.width, sourceHeight.toFloat() / image.height)
                            val w = image.width * fit
                            val h = image.height * fit
                            val left = (sourceWidth - w) / 2f
                            val top = (sourceHeight - h) / 2f
                            canvas.save()
                            canvas.translate(offsetX, offsetY)
                            canvas.scale(scale, scale)
                            canvas.drawBitmap(image, null, RectF(left, top, left + w, top + h), null)
                            canvas.restore()
                        } finally {
                            image.recycle()
                        }
                    }
                }

                val renderer = CanvasStrokeRenderer.create()
                // Map document coordinates -> export bitmap coordinates (uniform scale + centering).
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(offsetX, offsetY)
                }
                strokeSnapshot.forEach { renderer.draw(canvas, it.toStroke(), matrix) }
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    public suspend fun exportBase64(width: Int, height: Int): String {
        val png = exportPng(width, height)
        // Base64 of a multi-MB PNG must not run on the caller (typically Main) thread.
        return withContext(Dispatchers.Default) {
            Base64.encodeToString(png, Base64.NO_WRAP)
        }
    }

    internal fun acceptFinishedStrokes(finished: List<Stroke>) {
        finished.forEach { stroke ->
            val record = InkStrokeRecord(
                id = nextStrokeId++,
                brushFamily = stroke.brush.family,
                config = mutableUiState.value.brush,
                inputs = stroke.inputs,
                brushColorLong = stroke.brush.colorLong,
                // Reuse the mesh Ink already tessellated for this stroke; otherwise every
                // later recomposition would re-run native tessellation from the inputs.
                editedShape = stroke.shape,
            )
            execute(HistoryCommand.Add(record)) {
                documentEdits.add(DocumentEdit.AddStroke(record))
                strokes.add(record)
            }
        }
    }

    /**
     * Starts a live eraser preview. A false result means the previous gesture is still finishing
     * on a worker thread, so the pointer layer must not merge a new gesture into it.
     */
    internal fun beginEraserTrail(): Float? {
        if (activeEraser != null) return null
        val brushConfig = eraserBrushConfig()
        val before = strokes.toList()
        // Build the spatial index once per gesture so every later sample reuses it instead of
        // scanning the whole stroke list.
        activeEraser = ActiveEraser(
            before = before,
            brushConfig = brushConfig,
            index = AabbGrid.build(
                before.map { record ->
                    record.bounds()?.let { floatArrayOf(it.xMin, it.yMin, it.xMax, it.yMax) }
                },
            ),
        )
        return brushConfig.size
    }

    /**
     * Queues the newest complete trail for background geometry work. Requests are coalesced to one
     * worker loop, so high-frequency pointer events never create an unbounded job backlog.
     */
    internal fun updateEraserTrail(inputs: StrokeInputBatch) {
        val session = activeEraser ?: return
        requestEraserWork(session, inputs, finish = false)
    }

    /**
     * Requests the final geometry. The edit is committed only after the latest background result
     * arrives, keeping one drag as one undoable and persisted operation.
     */
    internal fun finishEraserTrail(inputs: StrokeInputBatch) {
        val session = activeEraser ?: return
        requestEraserWork(session, inputs, finish = true)
    }

    /** Cancels a live preview when the pointer is cancelled or the eraser leaves composition. */
    internal fun cancelEraserTrail() {
        val session = activeEraser ?: return
        activeEraser = null
        session.worker?.cancel()
        replaceVisibleStrokes(session.before)
    }

    private fun requestEraserWork(
        session: ActiveEraser,
        inputs: StrokeInputBatch,
        finish: Boolean,
    ) {
        if (activeEraser !== session) return
        session.latestInputs = inputs
        session.requestId++
        session.finishRequested = session.finishRequested || finish
        if (session.worker?.isActive != true) {
            session.worker = scope.launch { drainEraserWork(session) }
        }
    }

    private suspend fun drainEraserWork(session: ActiveEraser) {
        try {
            while (activeEraser === session) {
                val inputs = session.latestInputs ?: return
                val requestId = session.requestId
                val operation = EraseOperation(inputs, session.brushConfig)
                val after = withContext(Dispatchers.Default) {
                    EraseGeometry.apply(session.before, operation, session.index)
                }
                if (activeEraser !== session) return
                // A newer pointer event arrived while subtract() was running. Do not publish the
                // stale result; loop immediately and process only the newest complete trail.
                if (requestId != session.requestId) continue

                replaceVisibleStrokes(after)
                if (session.finishRequested) {
                    activeEraser = null
                    if (session.before == after) return
                    val edit = DocumentEdit.Erase(operation)
                    execute(HistoryCommand.Erase(session.before, edit, documentEdits.size)) {
                        strokes.clear()
                        strokes.addAll(after)
                        documentEdits.add(edit)
                    }
                    return
                }
                return
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (activeEraser === session) {
                activeEraser = null
                replaceVisibleStrokes(session.before)
                mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message ?: "擦除失败")
            }
        } finally {
            if (activeEraser === session && session.worker === coroutineContext[Job]) {
                session.worker = null
            }
        }
    }

    private fun eraserBrushConfig(): BrushConfig {
        val brush = mutableUiState.value.brush
        return brush.copy(
            style = BrushStyle.MARKER,
            // Bake the multiplier into the operation so persisted replay matches the cursor.
            size = brush.size * ERASER_SIZE_MULTIPLIER,
        )
    }

    private fun replaceVisibleStrokes(next: List<InkStrokeRecord>) {
        if (strokes == next) return
        strokes.clear()
        strokes.addAll(next)
        publish()
    }

    private fun execute(command: HistoryCommand, mutation: () -> Unit) {
        mutation()
        revision++
        history.execute(command)
        publish()
        persist()
    }

    private fun persist() {
        // Enforce the documented @MainThread contract: the revision/saved-revision protocol and the
        // pending-snapshot coalescing below are not thread-safe, so this must run on the main thread.
        check(Looper.getMainLooper() === Looper.myLooper()) {
            "DrawPadState.persist() must be called on the main thread"
        }
        // Build the snapshot on the caller (Main) thread without copying the background: the codec
        // only reads these bytes, and a per-edit copy of a multi-MB PNG is pure UI-thread memcpy.
        val snapshot = DocumentSnapshot(
            edits = documentEdits.toList(),
            visibleStrokes = strokes.toList(),
            backgroundPng = background.value,
            nextStrokeId = nextStrokeId,
        )
        val revisionToSave = revision
        // Coalesce bursts of edits: only the newest pending snapshot is encoded, so N rapid edits
        // no longer spawn N concurrent full-document JSON encodes.
        pendingSnapshot = snapshot
        pendingRevision = revisionToSave
        scope.launch {
            val toEncode = saveMutex.withLock {
                val latest = pendingSnapshot ?: return@withLock null
                // A newer persist was queued while this coroutine waited; let that one do the work.
                if (pendingRevision != revisionToSave) return@withLock null
                pendingSnapshot = null
                latest
            } ?: return@launch
            val bytes = runCatching {
                withContext(Dispatchers.Default) { DocumentCodec.encode(toEncode) }
            }.getOrElse { error ->
                if (revision == revisionToSave) mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message ?: "文档编码失败")
                return@launch
            }
            saveMutex.withLock {
                if (revisionToSave < savedRevision) return@withLock
                runCatching { store.write(bytes) }
                    .onSuccess { savedRevision = revisionToSave }
                    .onFailure { error ->
                        if (revision == revisionToSave) mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message ?: "自动保存失败")
                    }
            }
        }
    }

    private fun publish() {
        mutableUiState.value = mutableUiState.value.copy(
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            isEmpty = strokes.isEmpty() && background.value == null,
        )
    }

    private data class ActiveEraser(
        val before: List<InkStrokeRecord>,
        val brushConfig: BrushConfig,
        /** Spatial index over [before], built once per gesture and reused by every erase sample. */
        val index: AabbGrid? = null,
        var latestInputs: StrokeInputBatch? = null,
        var requestId: Long = 0L,
        var finishRequested: Boolean = false,
        var worker: Job? = null,
    )

    private sealed interface HistoryCommand {
        fun undo(state: DrawPadState)
        fun redo(state: DrawPadState)

        data class Add(val stroke: InkStrokeRecord) : HistoryCommand {
            override fun undo(state: DrawPadState) {
                state.strokes.removeAll { it.id == stroke.id }
                state.documentEdits.removeAll { it is DocumentEdit.AddStroke && it.stroke.id == stroke.id }
            }
            override fun redo(state: DrawPadState) {
                state.strokes.add(stroke)
                state.documentEdits.add(DocumentEdit.AddStroke(stroke))
            }
        }

        data class Erase(
            val before: List<InkStrokeRecord>,
            val edit: DocumentEdit.Erase,
            val insertIndex: Int,
        ) : HistoryCommand {
            override fun undo(state: DrawPadState) {
                state.strokes.clear(); state.strokes.addAll(before)
                val index = state.documentEdits.indexOfFirst { it === edit }
                if (index >= 0) state.documentEdits.removeAt(index) else state.documentEdits.remove(edit)
            }
            override fun redo(state: DrawPadState) {
                // Recompute the post-erase geometry from [before] instead of retaining a second full
                // record list: EraseGeometry.apply is the single, deterministic source of truth (the
                // same path the persisted replay uses), so this halves the eraser's history footprint.
                val after = EraseGeometry.apply(before, edit.operation)
                state.strokes.clear(); state.strokes.addAll(after)
                state.documentEdits.add(insertIndex.coerceIn(0, state.documentEdits.size), edit)
            }
        }

        data class Clear(val snapshot: DocumentSnapshot) : HistoryCommand {
            override fun undo(state: DrawPadState) {
                state.applySnapshot(snapshot)
            }
            override fun redo(state: DrawPadState) {
                state.documentEdits.clear(); state.strokes.clear(); state.background.value = null
            }
        }

        data class Background(val before: ByteArray?, val after: ByteArray) : HistoryCommand {
            override fun undo(state: DrawPadState) { state.background.value = before?.copyOf() }
            override fun redo(state: DrawPadState) { state.background.value = after.copyOf() }
        }

        data class ReplaceDocument(
            val before: DocumentSnapshot,
            val after: DocumentSnapshot,
        ) : HistoryCommand {
            override fun undo(state: DrawPadState) { state.applySnapshot(before) }
            override fun redo(state: DrawPadState) { state.applySnapshot(after) }
        }
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}

/**
 * Hosts [DrawPadState] for the lifetime of the enclosing [androidx.lifecycle.ViewModelStoreOwner].
 *
 * Keeping the state here (instead of a plain `remember`) means configuration changes no longer
 * recreate it: an in-flight auto-save is not cancelled mid-encode, and undo history / tool / brush
 * survive rotation. The document is restored once, when the ViewModel is created.
 */
internal class DrawPadViewModel(store: DocumentStore) : ViewModel() {
    val state: DrawPadState = DrawPadState(store, viewModelScope)

    init {
        viewModelScope.launch { state.restore() }
    }
}

@Composable
public fun rememberDrawPadState(store: DocumentStore? = null): DrawPadState {
    val context = LocalContext.current.applicationContext
    val actualStore = remember(store, context) { store ?: AppPrivateDocumentStore(context) }
    val owner = checkNotNull(LocalViewModelStoreOwner.current) {
        "rememberDrawPadState requires a ViewModelStoreOwner (e.g. an Activity or Fragment)"
    }
    val vm: DrawPadViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "DrawPadState-${System.identityHashCode(actualStore)}",
        factory = viewModelFactory { initializer { DrawPadViewModel(actualStore) } },
    )
    return vm.state
}
