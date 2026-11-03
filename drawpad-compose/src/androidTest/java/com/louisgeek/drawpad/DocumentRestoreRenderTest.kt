package com.louisgeek.drawpad

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.louisgeek.drawpad.model.DocumentEdit
import com.louisgeek.drawpad.model.InkStrokeRecord
import com.louisgeek.drawpad.model.hasVisibleGeometry
import com.louisgeek.drawpad.storage.DocumentCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restoring a captured real-world document (one pen stroke plus four erases, taken from a device
 * where the canvas came back blank after a restart) must still put ink on screen.
 */
@RunWith(AndroidJUnit4::class)
class DocumentRestoreRenderTest {

    private val width = 1080
    private val height = 2400

    private fun document(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets
        .open("document_repro.json")
        .use { it.readBytes() }

    private fun inkPixels(records: List<InkStrokeRecord>): Int {
        val renderer = CanvasStrokeRenderer.create()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        records.forEach { renderer.draw(canvas, it.toStroke(), AffineTransform.IDENTITY) }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return pixels.count { it != 0 }
    }

    @Test
    fun replayKeepsRecordsAndInk() {
        val decoded = DocumentCodec.decode(document())

        assertTrue("replay must keep at least one record", decoded.visibleStrokes.isNotEmpty())
        decoded.visibleStrokes.forEachIndexed { index, record ->
            assertTrue(
                "record $index must have geometry to draw",
                record.toStroke().shape.hasVisibleGeometry(),
            )
        }
        val ink = inkPixels(decoded.visibleStrokes)
        assertTrue("restored document must render ink, drew $ink pixels", ink > 0)
    }

    @Test
    fun erasesOnlyRemoveInk() {
        val decoded = DocumentCodec.decode(document())
        val adds = decoded.edits.filterIsInstance<DocumentEdit.AddStroke>().map { it.stroke }
        val uncut = inkPixels(adds)
        val cut = inkPixels(decoded.visibleStrokes)

        assertTrue("the stored pen stroke alone must render ink", uncut > 0)
        assertTrue("erasing must remove ink ($uncut -> $cut)", cut < uncut)
    }

    @Test
    fun replayIsStableAcrossRepeatedDecode() {
        val first = inkPixels(DocumentCodec.decode(document()).visibleStrokes)
        val second = inkPixels(DocumentCodec.decode(document()).visibleStrokes)

        assertEquals("decoding the same document twice must draw the same ink", first, second)
    }
}
