package com.louisgeek.drawpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the production [OperationHistory] used by [DrawPadState] (undo/redo deques + trimming).
 * Previously this file tested a private copy of the class, so it could never catch a regression.
 */
class HistoryManagerTest {

    @Test
    fun executeClearsRedo() {
        val history = OperationHistory<Int>()
        history.execute(1)
        history.execute(2)
        assertEquals(2, history.undo())
        assertTrue(history.canRedo)
        history.execute(3)
        assertFalse(history.canRedo)
        assertNull(history.redo())
        assertEquals(3, history.undo())
    }

    @Test
    fun undoRedoRoundTrip() {
        val history = OperationHistory<String>()
        history.execute("a")
        history.execute("b")
        assertEquals("b", history.undo())
        assertEquals("a", history.undo())
        assertNull(history.undo())
        assertEquals("a", history.redo())
        assertEquals("b", history.redo())
        assertNull(history.redo())
    }

    @Test
    fun trimsOldestBeyondMaxSize() {
        val history = OperationHistory<Int>(maxSize = 3)
        (1..5).forEach(history::execute)
        assertTrue(history.canUndo)
        // Only the last three survive: undo yields 5, 4, 3, then nothing.
        assertEquals(5, history.undo())
        assertEquals(4, history.undo())
        assertEquals(3, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun justExecutedEntryIsNeverTrimmed() {
        val history = OperationHistory<Int>(maxSize = 1)
        history.execute(1)
        history.execute(2)
        assertEquals(2, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun clearDropsBothStacks() {
        val history = OperationHistory<Int>()
        history.execute(1)
        history.undo()
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }
}
