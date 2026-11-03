package com.louisgeek.drawpad

/**
 * Undo/redo stack with a hard cap on the number of retained entries.
 *
 * Extracted from [DrawPadState] so the trimming behavior is unit-testable without native Ink (which
 * the state otherwise depends on). [execute] drops the redo stack (a new edit invalidates redo) and
 * trims the oldest entries beyond [maxSize].
 */
internal class OperationHistory<T>(private val maxSize: Int = Int.MAX_VALUE) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun execute(value: T) {
        undoStack.addLast(value)
        redoStack.clear()
        while (undoStack.size > maxSize) {
            undoStack.removeFirst()
        }
    }

    fun undo(): T? = undoStack.removeLastOrNull()?.also(redoStack::addLast)

    fun redo(): T? = redoStack.removeLastOrNull()?.also(undoStack::addLast)

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}
