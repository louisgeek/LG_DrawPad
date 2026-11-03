package com.louisgeek.drawpad.model

import androidx.ink.brush.Brush
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.geometry.Box
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.Stroke

/**
 * Single source of truth for the eraser geometry.
 *
 * Both the live edit path ([com.louisgeek.drawpad.DrawPadState]) and the persisted replay path
 * ([com.louisgeek.drawpad.storage.DocumentCodec]) must produce identical results, otherwise the
 * document the user sees drifts from the one restored on the next launch. Keeping the subtract /
 * no-op / drop rules here is what prevents that silent divergence.
 */
internal object EraseGeometry {

    fun overlaps(a: Box, b: Box): Boolean =
        a.xMin <= b.xMax && a.xMax >= b.xMin && a.yMin <= b.yMax && a.yMax >= b.yMin

    /**
     * Applies [operation] to every record in [source]:
     *  - records whose bounds don't touch the mask are kept unchanged,
     *  - records the mask removes entirely are dropped,
     *  - records where subtraction changes nothing keep their original instance (so callers can
     *    detect a no-op by list equality instead of recording a phantom edit),
     *  - otherwise the record gets the subtracted mesh as [InkStrokeRecord.editedShape].
     */
    @OptIn(ExperimentalInkEraserApi::class)
    fun apply(
        source: List<InkStrokeRecord>,
        operation: EraseOperation,
        index: AabbGrid? = null,
    ): List<InkStrokeRecord> {
        val config = operation.brushConfig
        val maskBrush = Brush.createWithComposeColor(
            family = InkBrushFactory().family(config),
            color = config.color,
            size = config.size,
            epsilon = config.epsilon,
        )
        val mask = Stroke(maskBrush, operation.inputs).shape
        val maskBounds = mask.computeBoundingBox() ?: return source
        // Conservative early-out for the live eraser: when the supplied index shows that no record
        // can truly overlap the mask, the document is unchanged, so return the same list instead of
        // rebuilding an identical one on every pointer sample. The index only ever yields a superset,
        // so an overlapping record can never be skipped.
        if (index != null) {
            val candidates = index.query(maskBounds.xMin, maskBounds.yMin, maskBounds.xMax, maskBounds.yMax)
            var overlapsAny = false
            for (candidate in candidates) {
                val candidateBounds = source[candidate].bounds()
                if (candidateBounds != null && overlaps(candidateBounds, maskBounds)) {
                    overlapsAny = true
                    break
                }
            }
            if (!overlapsAny) return source
        }
        return buildList {
            source.forEach { record ->
                // Use the record's cached bounds: scanning every record on each pointer sample must not
                // re-derive the mesh bounding box from native every frame.
                val strokeBounds = record.bounds()
                if (strokeBounds == null || !overlaps(strokeBounds, maskBounds)) {
                    add(record)
                } else {
                    val original = record.toStroke()
                    val remaining = original.subtract(mask, operation.transform, record.transform)
                    when {
                        !remaining.shape.hasVisibleGeometry() -> Unit
                        remaining.shape.hasSameGeometryAs(original.shape) -> add(record)
                        else -> add(record.copy(editedShape = remaining.shape))
                    }
                }
            }
        }
    }
}
