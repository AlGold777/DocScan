package app.docscan.core.recent

/** Volatile page-order draft for editing a saved PDF without changing its source. */
class SavedPdfEditSession(pageCount: Int) {
    init {
        require(pageCount > 0) { "A saved PDF edit session requires at least one page" }
    }

    private val originalOrder = (0 until pageCount).toList()
    private val mutableOrder = originalOrder.toMutableList()
    private val undoStack = ArrayDeque<Change>()

    val pageCount: Int get() = mutableOrder.size
    val canSave: Boolean get() = mutableOrder.isNotEmpty()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val isDirty: Boolean get() = mutableOrder != originalOrder
    val pageOrder: List<Int> get() = mutableOrder.toList()

    /** Removes the page at [position] and returns the new selected position, or -1 if empty. */
    fun removeAt(position: Int): Int {
        require(position in mutableOrder.indices)
        val removedPage = mutableOrder.removeAt(position)
        undoStack.addLast(Change.Removed(removedPage, position))
        return if (mutableOrder.isEmpty()) -1 else minOf(position, mutableOrder.lastIndex)
    }

    /** Moves a page by current list positions; no-op moves do not create undo entries. */
    fun move(fromPosition: Int, toPosition: Int) {
        require(fromPosition in mutableOrder.indices)
        require(toPosition in mutableOrder.indices)
        if (fromPosition == toPosition) return
        val page = mutableOrder.removeAt(fromPosition)
        mutableOrder.add(toPosition, page)
        undoStack.addLast(Change.Moved(fromPosition, toPosition))
    }

    fun undo(): Boolean {
        val change = undoStack.removeLastOrNull() ?: return false
        when (change) {
            is Change.Removed -> mutableOrder.add(change.position, change.page)
            is Change.Moved -> {
                val page = mutableOrder.removeAt(change.toPosition)
                mutableOrder.add(change.fromPosition, page)
            }
        }
        return true
    }

    private sealed interface Change {
        data class Removed(val page: Int, val position: Int) : Change
        data class Moved(val fromPosition: Int, val toPosition: Int) : Change
    }
}
