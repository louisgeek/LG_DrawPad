package com.louisgeek.drawpad.storage

import android.content.Context
import androidx.core.util.AtomicFile
import androidx.compose.ui.graphics.Color
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.storage.StrokeInputBatchSerialization
import androidx.ink.storage.BrushFamilySerialization
import com.louisgeek.drawpad.model.BrushConfig
import com.louisgeek.drawpad.model.BrushStyle
import com.louisgeek.drawpad.model.DocumentEdit
import com.louisgeek.drawpad.model.DocumentSnapshot
import com.louisgeek.drawpad.model.EraseGeometry
import com.louisgeek.drawpad.model.EraseOperation
import com.louisgeek.drawpad.model.InkStrokeRecord
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

public interface DocumentStore {
    public suspend fun read(): ByteArray?
    public suspend fun write(document: ByteArray)
}

public class AppPrivateDocumentStore(context: Context) : DocumentStore {
    private val file = AtomicFile(File(context.filesDir, "drawpad/document.json"))

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { file.readFully() }.getOrNull()
    }

    override suspend fun write(document: ByteArray) = withContext(Dispatchers.IO) {
        var stream: java.io.FileOutputStream? = null
        try {
            val output = file.startWrite()
            stream = output
            output.write(document)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }
}

internal object DocumentCodec {
    private const val LEGACY_SCHEMA_VERSION = 1
    private const val SCHEMA_VERSION = 2

    fun encode(snapshot: DocumentSnapshot): ByteArray = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("nextStrokeId", snapshot.nextStrokeId)
        .put("background", snapshot.backgroundPng?.let(::b64))
        .put("edits", JSONArray().apply {
            snapshot.edits.forEach { edit ->
                when (edit) {
                    is DocumentEdit.AddStroke -> put(JSONObject().put("type", "add").put("stroke", encodeStroke(edit.stroke)))
                    is DocumentEdit.Erase -> put(JSONObject().put("type", "erase").put("operation", encodeErase(edit.operation)))
                }
            }
        })
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): DocumentSnapshot {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val schema = root.getInt("schema")
        require(schema in LEGACY_SCHEMA_VERSION..SCHEMA_VERSION) { "Unsupported drawing document version" }
        // Schema 1 documents written by the first implementation used zero for opaque black in
        // captured documents. Keep accepting those documents so a restart cannot turn the canvas
        // blank; newly written documents use schema 2 and preserve zero as transparent.
        val legacyZeroBlack = schema == LEGACY_SCHEMA_VERSION
        val edits = List(root.getJSONArray("edits").length()) { index ->
            val item = root.getJSONArray("edits").getJSONObject(index)
            when (item.getString("type")) {
                "add" -> DocumentEdit.AddStroke(decodeStroke(item.getJSONObject("stroke"), legacyZeroBlack))
                "erase" -> DocumentEdit.Erase(decodeErase(item.getJSONObject("operation"), legacyZeroBlack))
                else -> error("Unknown document edit type")
            }
        }
        val sourceStrokes = edits.filterIsInstance<DocumentEdit.AddStroke>().map { it.stroke }
        val nextStrokeId = root.getLong("nextStrokeId")
        require(nextStrokeId > (sourceStrokes.maxOfOrNull { it.id } ?: 0L)) { "Invalid stroke id sequence" }
        val background = root.optString("background").takeIf(String::isNotEmpty)?.let(::unb64)
        background?.let { encoded ->
            // Validate via the header only; a full decode here would allocate the whole bitmap.
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Invalid background image in document" }
        }
        return DocumentSnapshot(
            edits = edits,
            visibleStrokes = replay(edits),
            backgroundPng = background,
            nextStrokeId = nextStrokeId,
        )
    }

    private fun encodeStroke(record: InkStrokeRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("style", record.config.style.name)
        .put("color", record.config.color.value.toULong().toString(16))
        .put("colorLong", (record.brushColorLong ?: record.toBrush().colorLong).toULong().toString(16))
        .put("size", record.config.size.toDouble())
        .put("epsilon", record.config.epsilon.toDouble())
        .put("inputs", b64(StrokeInputBatchSerialization.encode(record.inputs)))
        .put("family", b64(BrushFamilySerialization.encode(record.brushFamily)))
        .put("transform", encodeTransform(record.transform))

    private fun decodeStroke(json: JSONObject, legacyZeroBlack: Boolean): InkStrokeRecord {
        val config = json.readBrushConfig(legacyZeroBlack)
        val family = BrushFamilySerialization.decode(unb64(json.getString("family")))
        return InkStrokeRecord(
            id = json.getLong("id"),
            brushFamily = family,
            config = config,
            inputs = StrokeInputBatchSerialization.decode(unb64(json.getString("inputs"))),
            transform = decodeTransform(json.getJSONArray("transform")),
            brushColorLong = json.optString("colorLong").takeIf(String::isNotEmpty)?.toULong(16)?.toLong(),
        )
    }

    private fun encodeErase(operation: EraseOperation): JSONObject = JSONObject()
        .put("color", operation.brushConfig.color.value.toULong().toString(16))
        .put("style", operation.brushConfig.style.name)
        .put("size", operation.brushConfig.size.toDouble())
        .put("epsilon", operation.brushConfig.epsilon.toDouble())
        .put("inputs", b64(StrokeInputBatchSerialization.encode(operation.inputs)))
        .put("transform", encodeTransform(operation.transform))

    private fun decodeErase(json: JSONObject, legacyZeroBlack: Boolean): EraseOperation = EraseOperation(
        inputs = StrokeInputBatchSerialization.decode(unb64(json.getString("inputs"))),
        brushConfig = json.readBrushConfig(legacyZeroBlack),
        transform = decodeTransform(json.getJSONArray("transform")),
    )

    private fun JSONObject.readBrushConfig(legacyZeroBlack: Boolean) = BrushConfig(
        color = getString("color").toULong(16).let { encoded ->
            if (legacyZeroBlack && encoded == 0UL) Color.Black else Color(encoded.toLong())
        },
        size = getDouble("size").toFloat(),
        epsilon = getDouble("epsilon").toFloat(),
        style = BrushStyle.valueOf(getString("style")),
    )

    private fun replay(edits: List<DocumentEdit>): List<InkStrokeRecord> {
        var records = emptyList<InkStrokeRecord>()
        edits.forEach { edit ->
            when (edit) {
                is DocumentEdit.AddStroke -> records = records + edit.stroke
                is DocumentEdit.Erase -> records = EraseGeometry.apply(records, edit.operation)
            }
        }
        return records
    }
    private fun encodeTransform(transform: AffineTransform): JSONArray =
        JSONArray(transform.getValues(FloatArray(6)).toList())

    private fun decodeTransform(values: JSONArray): AffineTransform =
        ImmutableAffineTransform(
            values.getDouble(0).toFloat(), values.getDouble(1).toFloat(),
            values.getDouble(2).toFloat(), values.getDouble(3).toFloat(),
            values.getDouble(4).toFloat(), values.getDouble(5).toFloat(),
        )

    // 使用 android.util.Base64（java.util.Base64 需 API 26，项目 minSdk 24）
    private fun b64(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun unb64(value: String): ByteArray = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
}
