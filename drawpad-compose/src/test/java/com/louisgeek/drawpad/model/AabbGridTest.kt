package com.louisgeek.drawpad.model

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Correctness + pruning properties of [AabbGrid]. Runs on the JVM (no Ink): the grid only works on
 * float bounds, so it can be verified and benchmarked without a device.
 */
class AabbGridTest {

    private fun randomBounds(n: Int, span: Float, size: Float, seed: Int): List<FloatArray> {
        val rnd = Random(seed)
        return List(n) {
            val x = rnd.nextFloat() * span
            val y = rnd.nextFloat() * span
            val s = size * (0.5f + rnd.nextFloat())
            floatArrayOf(x, y, x + s, y + s)
        }
    }

    @Test
    fun queryNeverMissesAnOverlappingBox() {
        val bounds = randomBounds(n = 5_000, span = 10_000f, size = 40f, seed = 42)
        val grid = AabbGrid.build(bounds.map { it })
        val qx0 = 1_000f
        val qy0 = 1_000f
        val qx1 = 1_200f
        val qy1 = 1_200f
        val candidates = grid.query(qx0, qy0, qx1, qy1).toHashSet()
        bounds.forEachIndexed { i, b ->
            val overlaps = b[0] <= qx1 && b[2] >= qx0 && b[1] <= qy1 && b[3] >= qy0
            if (overlaps) assertTrue("box $i overlaps but was missed", i in candidates)
        }
    }

    @Test
    fun queryPrunesMostBoxes() {
        val n = 20_000
        val bounds = randomBounds(n = n, span = 20_000f, size = 20f, seed = 7)
        val grid = AabbGrid.build(bounds.map { it })
        val candidates = grid.query(1_000f, 1_000f, 1_100f, 1_100f)
        assertTrue("expected heavy pruning, got ${candidates.size}/$n", candidates.size < n / 10)
    }

    /** Prints a rough timing comparison; no assertion (timing on shared CI is not deterministic). */
    @Test
    fun queryIsFasterThanLinearScan() {
        val n = 50_000
        val bounds = randomBounds(n = n, span = 40_000f, size = 20f, seed = 11)
        val grid = AabbGrid.build(bounds.map { it })
        val qx0 = 5_000f
        val qy0 = 5_000f
        val qx1 = 5_100f
        val qy1 = 5_100f

        val gridStart = System.nanoTime()
        var gridHits = 0
        repeat(1_000) { gridHits += grid.query(qx0, qy0, qx1, qy1).size }
        val gridNanos = System.nanoTime() - gridStart

        val linearStart = System.nanoTime()
        var linearHits = 0
        repeat(1_000) {
            for (b in bounds) {
                if (b[0] <= qx1 && b[2] >= qx0 && b[1] <= qy1 && b[3] >= qy0) linearHits++
            }
        }
        val linearNanos = System.nanoTime() - linearStart

        println(
            "AabbGrid: index=$gridNanos ns (candidates/query≈${gridHits / 1_000}), " +
                "linear=$linearNanos ns (n=$n), speedup≈${"%.1f".format(linearNanos.toDouble() / gridNanos.coerceAtLeast(1))}x",
        )
    }
}