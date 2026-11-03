package com.louisgeek.drawpad

import android.graphics.BitmapFactory
import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.louisgeek.drawpad.model.DrawTool
import com.louisgeek.drawpad.model.ERASER_ACCENT

@Composable
public fun DrawPad(
    modifier: Modifier = Modifier,
    state: DrawPadState = rememberDrawPadState(),
    stylusOnly: Boolean = false,
    showToolbar: Boolean = true,
) {
    val uiState by state.uiState
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (showToolbar) {
            Toolbar(
                state = state,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val savedStrokes = state.completedStrokes
        val backgroundBytes = state.backgroundBytes
        // Rebuilding the brush allocates a native object; cache it so recompositions don't churn
        // InProgressStrokes' AndroidView every frame.
        val brush = remember(uiState.tool, uiState.brush) { state.currentBrush }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CanvasSurface(
                strokes = savedStrokes,
                backgroundPng = backgroundBytes,
                brush = brush,
                isEraser = uiState.tool == DrawTool.ERASER,
                // Disable inking while a document is loading so a stroke made during restore()
                // cannot race the restored snapshot (id collisions / overwritten save).
                enabled = !uiState.isLoading,
                onStrokesFinished = state::acceptFinishedStrokes,
                onEraserStart = state::beginEraserTrail,
                onEraserChanged = state::updateEraserTrail,
                onEraserFinished = state::finishEraserTrail,
                onEraserCancelled = state::cancelEraserTrail,
                onCanvasSize = state::onCanvasSizeChanged,
                stylusOnly = stylusOnly,
                modifier = Modifier.fillMaxSize(),
            )
            // Loading and error UI are overlaid on the canvas instead of laid out below it, so
            // showing/hiding them never resizes CanvasSurface (Ink warns against resizing the
            // in-progress surface mid-session).
            if (uiState.isLoading) {
                LinearProgressIndicator(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                )
            }
            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { state.clearError() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Toolbar(state: DrawPadState, modifier: Modifier = Modifier) {
    val uiState by state.uiState
    Surface(modifier, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = state::undo,
                enabled = !uiState.isLoading && uiState.canUndo,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Undo") }
            TextButton(
                onClick = state::redo,
                enabled = !uiState.isLoading && uiState.canRedo,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Redo") }
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = uiState.tool == DrawTool.PEN,
                onClick = state::toggleTool,
                label = { Text(if (uiState.tool == DrawTool.PEN) "Pen selected" else "Eraser selected") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            TextButton(
                onClick = state::clear,
                enabled = !uiState.isLoading && !uiState.isEmpty,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Clear") }
        }
    }
}

@Composable
private fun CanvasSurface(
    strokes: List<Stroke>,
    backgroundPng: ByteArray?,
    brush: Brush,
    isEraser: Boolean,
    enabled: Boolean,
    onStrokesFinished: (List<Stroke>) -> Unit,
    onEraserStart: () -> Float?,
    onEraserChanged: (StrokeInputBatch) -> Unit,
    onEraserFinished: (StrokeInputBatch) -> Unit,
    onEraserCancelled: () -> Unit,
    onCanvasSize: (Int, Int) -> Unit,
    stylusOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { CanvasStrokeRenderer.create() }
    val backgroundImage = remember(backgroundPng) {
        backgroundPng?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }
    val identity = remember { AffineTransform.IDENTITY }
    // Eraser cursor: a circle outline whose diameter equals the actual erase width (brush.size
    // already includes the ERASER_SIZE_MULTIPLIER), so what the user sees is the footprint the
    // gesture removes. The same observer samples the pointer path into the erase mask, because the
    // eraser gets no in-progress layer of its own (see EraserTrail).
    val eraserCursor = remember { mutableStateOf<Offset?>(null) }
    val eraserDiameter = remember { mutableStateOf(brush.size) }
    Box(
        modifier = modifier
            .padding(12.dp)
            .onSizeChanged { onCanvasSize(it.width, it.height) }
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .then(
                if (stylusOnly) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { change ->
                                if (change.type != PointerType.Stylus && change.pressed) change.consume()
                            }
                        }
                    }
                } else Modifier,
            )
            .then(
                if (isEraser && enabled) Modifier.pointerInput(Unit) {
                    // Reset on relaunch so switching tools never leaves a frozen outline.
                    eraserCursor.value = null
                    var trail: EraserTrail? = null
                    var blockedPointerId: PointerId? = null
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val active = trail
                                if (active == null) {
                                    val blocked = blockedPointerId
                                    if (blocked != null) {
                                        val change = event.changes.firstOrNull { it.id == blocked }
                                        if (change == null || !change.pressed) blockedPointerId = null
                                        eraserCursor.value = null
                                        continue
                                    }
                                    val down = event.changes.firstOrNull {
                                        it.pressed && (!stylusOnly || it.type == PointerType.Stylus)
                                    }
                                    val eraseDiameter = down?.let { onEraserStart() }
                                    if (down != null && eraseDiameter != null) {
                                        val started = EraserTrail(
                                            pointerId = down.id,
                                            inputToolType = down.type.toInputToolType(),
                                            startUptimeMillis = down.uptimeMillis,
                                        ).apply { add(down) }
                                        trail = started
                                        eraserDiameter.value = eraseDiameter
                                        eraserCursor.value = down.position
                                        down.consume()
                                        // Preview the initial contact immediately. The final
                                        // callback still records the complete gesture as one edit.
                                        onEraserChanged(started.toInputs())
                                    } else {
                                        if (down != null) {
                                            // A previous erase is still being finalized off the UI
                                            // thread. Ignore this pointer until it is released rather
                                            // than treating a later MOVE as a new DOWN.
                                            blockedPointerId = down.id
                                            down.consume()
                                        }
                                        // Unpressed moves and enters only exist for hover-capable
                                        // pointers (mouse); show the outline where they hover.
                                        eraserCursor.value = when (event.type) {
                                            PointerEventType.Move, PointerEventType.Enter ->
                                                event.changes.firstOrNull {
                                                    !stylusOnly || it.type == PointerType.Stylus
                                                }?.position
                                            else -> null // Release, Exit
                                        }
                                    }
                                } else {
                                    val change = event.changes.firstOrNull { it.id == active.pointerId }
                                    trail = if (change == null || event.isCanceled()) {
                                        onEraserCancelled()
                                        null
                                    } else {
                                        active.add(change)
                                        eraserCursor.value = change.position.takeIf { change.pressed }
                                        change.consume()
                                        if (change.pressed) {
                                            onEraserChanged(active.toInputs())
                                            active
                                        } else {
                                            // The live preview is updated during the drag, while
                                            // this callback commits the whole gesture once so undo
                                            // and persistence remain one operation.
                                            onEraserFinished(active.toInputs())
                                            null
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        // Switching tools or leaving the composition cancels the pointer coroutine.
                        // Restore the pre-gesture document instead of leaving a preview behind.
                        if (trail != null) onEraserCancelled()
                        eraserCursor.value = null
                        eraserDiameter.value = brush.size
                    }
                } else Modifier,
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            backgroundImage?.let { image ->
                // Letterbox the background to preserve its aspect ratio instead of stretching it.
                val imageWidth = image.width.toFloat()
                val imageHeight = image.height.toFloat()
                if (imageWidth > 0f && imageHeight > 0f) {
                    val fit = minOf(size.width / imageWidth, size.height / imageHeight)
                    val drawnWidth = (imageWidth * fit).roundToInt()
                    val drawnHeight = (imageHeight * fit).roundToInt()
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(
                            ((size.width - drawnWidth) / 2f).roundToInt(),
                            ((size.height - drawnHeight) / 2f).roundToInt(),
                        ),
                        dstSize = IntSize(drawnWidth, drawnHeight),
                    )
                }
            }
            drawIntoCanvas { canvas ->
                strokes.forEach { renderer.draw(canvas.nativeCanvas, it, identity) }
            }
        }
        // The eraser cursor lives in its own layer: pointer-move updates fire every frame while
        // erasing, and keeping them out of the saved-stroke Canvas means those frames redraw only
        // this thin outline instead of re-drawing every completed stroke.
        if (isEraser && enabled) {
            Canvas(Modifier.fillMaxSize()) {
                eraserCursor.value?.let { center ->
                    // Eraser affordance: a semi-transparent pink disc + ring (brush.size already
                    // includes the size multiplier, so the radius is the true erase footprint).
                    // This is the whole eraser visual while the finger slides: the artwork stays
                    // visible through the translucent disc and nothing trails the cursor.
                    val radius = eraserDiameter.value / 2f
                    drawCircle(center = center, radius = radius, color = ERASER_ACCENT.copy(alpha = 0.25f))
                    drawCircle(
                        center = center,
                        radius = radius,
                        color = ERASER_ACCENT.copy(alpha = 0.7f),
                        style = DrawStroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
        if (!isEraser) {
            InProgressStrokes(
                defaultBrush = brush,
                // Do not start a wet stroke while the document is restoring. The eraser is not
                // represented by Ink at all; its pointer observer above owns that gesture.
                nextBrush = { if (enabled) brush else null },
                onStrokesFinished = { finished ->
                    if (enabled) onStrokesFinished(finished)
                },
            )
        }
    }
}

/**
 * One eraser gesture's path, sampled by [CanvasSurface]'s pointer observer.
 *
 * The eraser deliberately does not use Ink's in-progress layer: below API 33 the procedural stock
 * brushes can fall back to a plain hairline path that ignores the brush colour and width. The
 * eraser gesture is sampled by the pointer observer above instead, and becomes an erase mask.
 */
private fun PointerEvent.isCanceled(): Boolean {
    val event = motionEvent ?: return false
    if (event.actionMasked == MotionEvent.ACTION_CANCEL) return true
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val isPointerUp = event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
        event.actionMasked == MotionEvent.ACTION_UP
    return isPointerUp && (event.flags and MotionEvent.FLAG_CANCELED) != 0
}

private fun PointerType.toInputToolType(): InputToolType = when (this) {
    PointerType.Stylus -> InputToolType.STYLUS
    PointerType.Mouse -> InputToolType.MOUSE
    PointerType.Touch -> InputToolType.TOUCH
    else -> InputToolType.TOUCH
}

/**
 * One eraser gesture's path, sampled by [CanvasSurface]'s pointer observer.
 *
 * The trail keeps historical pointer samples and the actual input tool type. Compose can receive
 * more samples from the device than one frame can dispatch, and preserving those samples avoids
 * gaps when a fast stylus swipe crosses a stroke.
 */
private class EraserTrail(
    val pointerId: PointerId,
    private val inputToolType: InputToolType,
    private val startUptimeMillis: Long,
) {
    private val inputs = MutableStrokeInputBatch()
    private var lastPosition: Offset? = null
    private var lastElapsedMillis = -1L

    fun add(change: PointerInputChange) {
        change.historical.forEach { historical ->
            addSample(historical.position, historical.uptimeMillis)
        }
        addSample(change.position, change.uptimeMillis)
    }

    private fun addSample(position: Offset, uptimeMillis: Long) {
        if (lastPosition == position) return
        val elapsedMillis = (uptimeMillis - startUptimeMillis).coerceAtLeast(0L)
        if (elapsedMillis < lastElapsedMillis) return
        try {
            inputs.add(inputToolType, position.x, position.y, elapsedMillis)
            lastPosition = position
            lastElapsedMillis = elapsedMillis
        } catch (_: IllegalArgumentException) {
            // Real devices can deliver duplicate or out-of-order samples. Ink documents that
            // these should be sanitized rather than allowed to invalidate the whole gesture.
        }
    }

    fun toInputs(): StrokeInputBatch = inputs.toImmutable()
}
