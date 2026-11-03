package com.louisgeek.drawpad

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.louisgeek.drawpad.model.BrushConfig
import com.louisgeek.drawpad.model.BrushStyle
import com.louisgeek.drawpad.model.DocumentEdit
import com.louisgeek.drawpad.model.DocumentSnapshot
import com.louisgeek.drawpad.model.EraseGeometry
import com.louisgeek.drawpad.model.EraseOperation
import com.louisgeek.drawpad.model.InkStrokeRecord
import com.louisgeek.drawpad.model.hasSameGeometryAs
import com.louisgeek.drawpad.storage.DocumentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the eraser. Uses the production [EraseGeometry] (shared with DrawPadState and
 * DocumentCodec) and verifies that many successive erases all survive.
 *
 * The previous implementation split the subtracted mesh with Stroke.split(); on-device diagnostics
 * showed split could return fragments whose mesh has zero outlines but a non-null bounding box,
 * which both blanked the stroke and let later erases regenerate the full geometry ("擦掉又回去").
 *
 * Also covers a no-op erase (bbox overlap but nothing cut) and an encode -> decode -> replay
 * round-trip that must reproduce the live geometry.
 */
@RunWith(AndroidJUnit4::class)
class EraseCompositionTest {

    private val width = 900
    private val height = 300
    private val lineY = 150f
    private val eraserSize = 36f

    private val penConfig = BrushConfig(color = Color.Black, size = 6f, epsilon = 0.5f, style = BrushStyle.PRESSURE_PEN)
    private val eraserConfig = BrushConfig(color = Color.Gray, size = eraserSize, epsilon = 0.5f, style = BrushStyle.MARKER)

    private fun horizontalInputs(x0: Float, x1: Float): StrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val steps = 180
        for (i in 0..steps) {
            val x = x0 + (x1 - x0) * i.toFloat() / steps
            batch.add(InputToolType.STYLUS, x, lineY, i.toLong() * 8L)
        }
        return batch
    }

    private fun verticalMaskInputs(x: Float): StrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val steps = 40
        for (i in 0..steps) {
            val y = lineY - 60f + 120f * i.toFloat() / steps
            batch.add(InputToolType.STYLUS, x, y, i.toLong() * 8L)
        }
        return batch
    }

    private fun original() = InkStrokeRecord(
        id = 1L,
        brushFamily = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
        config = penConfig,
        inputs = horizontalInputs(20f, 880f),
    )

    /**
     * True if any ink is present in a small window around (x, y). Avoiding a single-pixel probe
     * keeps the test robust to sub-pixel anti-aliasing differences.
     */
    private fun painted(records: List<InkStrokeRecord>, x: Int, y: Int = 150): Boolean {
        val renderer = CanvasStrokeRenderer.create()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        records.forEach { renderer.draw(canvas, it.toStroke(), AffineTransform.IDENTITY) }
        var found = false
        for (dx in -2..2) {
            for (dy in -8..8) {
                val px = x + dx
                val py = y + dy
                if (px in 0 until width && py in 0 until height && bitmap.getPixel(px, py) != 0) {
                    found = true
                }
            }
        }
        bitmap.recycle()
        return found
    }

    @Test
    fun manyErasesAllPersist() {
        var records = listOf(original())
        assertTrue(painted(records, 200))
        assertTrue(painted(records, 700))

        val eraseXs = listOf(200f, 400f, 300f, 600f, 700f, 500f)
        eraseXs.forEach { x ->
            records = EraseGeometry.apply(records, EraseOperation(verticalMaskInputs(x), eraserConfig))
            assertTrue("erase@$x must keep the record list non-empty", records.isNotEmpty())
        }

        // Every erased x stays clear; every never-erased x stays painted.
        eraseXs.forEach { x ->
            assertFalse("hole at x=${x.toInt()} must remain clear after all erases", painted(records, x.toInt()))
        }
        listOf(100, 250, 450, 650, 800).forEach { x ->
            assertTrue("non-erased sample x=$x must still be painted", painted(records, x))
        }

        // The split-era bug made the stroke vanish once it broke into disconnected pieces.
        assertTrue("far-half of the line must still have ink", painted(records, 800))
    }

    @Test
    fun reErasingSameRegionKeepsItClear() {
        var records = listOf(original())
        repeat(4) {
            records = EraseGeometry.apply(records, EraseOperation(verticalMaskInputs(450f), eraserConfig))
        }
        assertFalse("repeatedly erased x=450 must stay clear", painted(records, 450))
        assertTrue("x=200 must be untouched", painted(records, 200))
        assertTrue("x=700 must be untouched", painted(records, 700))
    }

    @Test
    fun eraseThatHitsNothingIsANoOp() {
        // A diagonal stroke has a large bounding box with empty corners. An eraser in a corner
        // overlaps the bbox but cuts nothing, which must be a no-op rather than a phantom edit.
        val diagonal = MutableStrokeInputBatch().apply {
            for (i in 0..180) add(InputToolType.STYLUS, 50f + 800f * i / 180f, 50f + 200f * i / 180f, i.toLong() * 8L)
        }
        val records = listOf(
            InkStrokeRecord(
                id = 1L,
                brushFamily = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
                config = penConfig,
                inputs = diagonal,
            ),
        )
        // Short vertical mask near the top edge, far from the diagonal at x=450 (which is at y≈150).
        val mask = MutableStrokeInputBatch().apply {
            for (i in 0..10) add(InputToolType.STYLUS, 450f, 40f + i.toFloat(), i.toLong())
        }
        val after = EraseGeometry.apply(records, EraseOperation(mask, eraserConfig))
        // No phantom edit: the list is unchanged (same instance), so callers can detect a no-op.
        assertTrue("erase that removes nothing must not change the record list", records == after)
    }

    @Test
    fun encodeDecodeReplayMatchesLiveGeometry() {
        val original = original()
        val eraseXs = listOf(200f, 400f, 300f, 600f, 500f)
        var live = listOf(original)
        val edits = mutableListOf<DocumentEdit>(DocumentEdit.AddStroke(original))
        eraseXs.forEach { x ->
            val op = EraseOperation(verticalMaskInputs(x), eraserConfig)
            live = EraseGeometry.apply(live, op)
            edits += DocumentEdit.Erase(op)
        }

        val decoded = DocumentCodec.decode(
            DocumentCodec.encode(DocumentSnapshot(edits, live, null, 2L)),
        )

        // The replay path must reproduce exactly the geometry the live edit path produced,
        // otherwise the document silently changes across a restart.
        assertEquals("record count must survive round-trip", live.size, decoded.visibleStrokes.size)
        live.zip(decoded.visibleStrokes).forEachIndexed { index, (liveRecord, decodedRecord) ->
            assertTrue(
                "record $index must have identical geometry after replay",
                liveRecord.toStroke().shape.hasSameGeometryAs(decodedRecord.toStroke().shape),
            )
        }
    }
}
