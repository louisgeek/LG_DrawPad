package com.louisgeek.drawpad.model

import kotlin.math.floor
import kotlin.math.max

/**
 * Uniform grid over axis-aligned bounding boxes, used by the eraser to avoid scanning the whole
 * stroke list on every pointer sample.
 *
 * A [query] returns a **conservative superset** of the boxes that overlap the query rectangle (cell
 * granularity), so the caller must still run an exact overlap test. The only guarantee is that no
 * truly overlapping box is ever missed — the erase result is therefore unchanged. The class is kept
 * free of Ink types (just floats) so it can be unit-tested and benchmarked on the JVM.
 */
internal class AabbGrid private constructor(private val cellSize: Float) {

    private val cells = HashMap<Long, MutableList<Int>>()

    private fun cellKey(cx: Int, cy: Int): Long =
        (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    fun add(index: Int, xMin: Float, yMin: Float, xMax: Float, yMax: Float) {
        val cx0 = floor(xMin / cellSize).toInt()
        val cx1 = floor(xMax / cellSize).toInt()
        val cy0 = floor(yMin / cellSize).toInt()
        val cy1 = floor(yMax / cellSize).toInt()
        for (cy in cy0..cy1) {
            for (cx in cx0..cx1) {
                cells.getOrPut(cellKey(cx, cy)) { ArrayList() }.add(index)
            }
        }
    }

    /** Deduplicated candidate indices whose cells intersect the query rectangle. */
    fun query(xMin: Float, yMin: Float, xMax: Float, yMax: Float): IntArray {
        val cx0 = floor(xMin / cellSize).toInt()
        val cx1 = floor(xMax / cellSize).toInt()
        val cy0 = floor(yMin / cellSize).toInt()
        val cy1 = floor(yMax / cellSize).toInt()
        val seen = HashSet<Int>()
        for (cy in cy0..cy1) {
            for (cx in cx0..cx1) {
                cells[cellKey(cx, cy)]?.let { list -> for (index in list) seen.add(index) }
            }
        }
        val result = IntArray(seen.size)
        var i = 0
        for (index in seen) result[i++] = index
        return result
    }

    companion object {
        /**
         * Builds a grid from per-record bounds (`[xMin, yMin, xMax, yMax]`, or null when absent). The
         * cell size is derived from the average box extent so buckets stay reasonably populated for
         * both tiny and large strokes; building is O(n) and done once per eraser gesture.
         */
        fun build(bounds: List<FloatArray?>): AabbGrid {
            var sum = 0f
            var count = 0
            for (b in bounds) {
                if (b != null) {
                    sum += max(b[2] - b[0], b[3] - b[1])
                    count++
                }
            }
            val averageExtent = if (count > 0) sum / count else 1f
            val cellSize = (averageExtent * 2f).coerceAtLeast(1f)
            val grid = AabbGrid(cellSize)
            for (i in bounds.indices) {
                val b = bounds[i] ?: continue
                grid.add(i, b[0], b[1], b[2], b[3])
            }
            return grid
        }
    }
}