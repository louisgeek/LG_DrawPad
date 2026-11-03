package com.louisgeek.drawpad.model

import androidx.compose.ui.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.louisgeek.drawpad.model.BrushStyle.MARKER
import com.louisgeek.drawpad.model.BrushStyle.PRESSURE_PEN
import kotlin.math.abs


public enum class DrawTool {
    PEN,
    ERASER
}

public enum class BrushStyle {
    PRESSURE_PEN,
    MARKER
}

public data class BrushConfig(
    val color: Color = Color.Black,
    val size: Float = 4f,
    val epsilon: Float = 0.1f,
    val style: BrushStyle = PRESSURE_PEN,
)

/**
 * The eraser shares [BrushConfig.size] with the pen; a pen-sized eraser feels too thin to
 * actually erase with. Both the cursor radius ([com.louisgeek.drawpad.DrawPadState.brushFor]) and
 * the erase mask recorded in the [EraseOperation] must bake this multiplier in the same way,
 * otherwise what the user sees while erasing drifts from what the document actually removes —
 * including on the persisted-replay path in storage, which replays the recorded config as-is.
 */
internal const val ERASER_SIZE_MULTIPLIER: Float = 10f

/**
 * Accent used for the eraser affordance: the cursor disc and ring share it so the eraser reads as
 * one cohesive pink tool. The eraser draws nothing else while the finger slides — Ink is handed a
 * null brush in that mode, so there is no trail to colour (see CanvasSurface).
 */
internal val ERASER_ACCENT: Color = Color(0xFFEC407A)

public data class DrawPadUiState(
    val tool: DrawTool = DrawTool.PEN,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isEmpty: Boolean = true,
    val brush: BrushConfig = BrushConfig(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

internal data class InkStrokeRecord(
    val id: Long,
    val brushFamily: BrushFamily,
    val config: BrushConfig,
    val inputs: StrokeInputBatch,
    val transform: AffineTransform = AffineTransform.IDENTITY,
    val editedShape: PartitionedMesh? = null,
    /** Exact Ink colorLong captured from the source Brush when available. */
    val brushColorLong: Long? = null,
) {
    /**
     * The [Stroke] is expensive to build when [editedShape] is absent: the two-arg constructor
     * re-runs the native tessellation every call, and it also allocates a native [Brush]. The
     * `completedStrokes` list is read from the composable body on *every* recomposition, so
     * rebuilding strokes each time produced O(n^2) native churn (worst after a restore, where no
     * record carries a saved shape). Cache it per record instead; [copy] (used by the eraser)
     * yields a new record with its own cache.
     */
    private val cachedStroke: Stroke by lazy { buildStroke() }

    /**
     * Cached bounding box of the current geometry. The eraser asks for every record's bounds on each
     * pointer sample; recomputing them from the native mesh is an O(n) native call per frame, so
     * cache per record. [copy] (used by the eraser) yields a new record with its own cache, which is
     * correct because the subtracted geometry has different bounds.
     */
    private val cachedBounds: androidx.ink.geometry.Box? by lazy { toStroke().shape.computeBoundingBox() }

    fun bounds(): androidx.ink.geometry.Box? = cachedBounds

    fun toBrush(): Brush = brushColorLong?.let { colorLong ->
        Brush.createWithColorLong(
            family = brushFamily,
            colorLong = colorLong,
            size = config.size,
            epsilon = config.epsilon,
        )
    } ?: Brush.createWithComposeColor(
        family = brushFamily,
        color = config.color,
        size = config.size,
        epsilon = config.epsilon,
    )

    fun toStroke(): Stroke = cachedStroke

    private fun buildStroke(): Stroke =
        editedShape?.let { Stroke(toBrush(), inputs, it) } ?: Stroke(toBrush(), inputs)
}

internal class InkBrushFactory {
    fun family(config: BrushConfig): BrushFamily = when (config.style) {
        PRESSURE_PEN -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        MARKER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
    }
}

internal data class EraseOperation(
    val inputs: StrokeInputBatch,
    val brushConfig: BrushConfig,
    val transform: AffineTransform = AffineTransform.IDENTITY,
)

internal sealed interface DocumentEdit {
    data class AddStroke(val stroke: InkStrokeRecord) : DocumentEdit
    data class Erase(val operation: EraseOperation) : DocumentEdit
}

internal data class DocumentSnapshot(
    val edits: List<DocumentEdit>,
    val visibleStrokes: List<InkStrokeRecord>,
    val backgroundPng: ByteArray?,
    val nextStrokeId: Long,
)

@android.annotation.SuppressLint("RestrictedApi") // Deliberately reads Ink's @RestrictTo(LIBRARY_GROUP) mesh introspection (renderGroupMeshes/vertexCount); the render meshes are the only reliable visibility signal.
internal fun PartitionedMesh.hasVisibleGeometry(): Boolean {
    // Outlines are optional metadata. A mesh can still contain renderable triangles without
    // outlines, so use the actual render meshes as the visibility check.
    for (group in 0 until getRenderGroupCount()) {
        if (renderGroupMeshes(group).any { it.vertexCount > 0 }) return true
    }
    return false
}

/**
 * Compares the geometry that is exposed by the Ink mesh API rather than native object identity.
 * The outline positions make the no-op check resistant to two different meshes having the same
 * vertex counts and bounding box after a subtraction.
 */
@android.annotation.SuppressLint("RestrictedApi") // Same deliberate use of Ink's restricted mesh internals as hasVisibleGeometry.
internal fun PartitionedMesh.hasSameGeometryAs(other: PartitionedMesh): Boolean {
    if (getRenderGroupCount() != other.getRenderGroupCount()) return false
    val epsilon = 1e-3f
    for (group in 0 until getRenderGroupCount()) {
        val meshesA = renderGroupMeshes(group)
        val meshesB = other.renderGroupMeshes(group)
        if (meshesA.size != meshesB.size) return false
        if (meshesA.indices.any { meshesA[it].vertexCount != meshesB[it].vertexCount }) return false

        if (getOutlineCount(group) != other.getOutlineCount(group)) return false
        for (outline in 0 until getOutlineCount(group)) {
            val count = getOutlineVertexCount(group, outline)
            if (count != other.getOutlineVertexCount(group, outline)) return false
            val pointA = MutableVec()
            val pointB = MutableVec()
            for (vertex in 0 until count) {
                populateOutlinePosition(group, outline, vertex, pointA)
                other.populateOutlinePosition(group, outline, vertex, pointB)
                if (abs(pointA.x - pointB.x) > epsilon || abs(pointA.y - pointB.y) > epsilon) return false
            }
        }
    }
    val boxA = computeBoundingBox() ?: return other.computeBoundingBox() == null
    val boxB = other.computeBoundingBox() ?: return false
    return abs(boxA.xMin - boxB.xMin) <= epsilon &&
        abs(boxA.xMax - boxB.xMax) <= epsilon &&
        abs(boxA.yMin - boxB.yMin) <= epsilon &&
        abs(boxA.yMax - boxB.yMax) <= epsilon
}
