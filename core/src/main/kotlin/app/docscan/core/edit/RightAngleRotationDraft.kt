package app.docscan.core.edit

/** Volatile right-angle rotation state with reverse-order Undo and a clean zero-turn state. */
class RightAngleRotationDraft {
    private val previousTurns = mutableListOf<Int>()

    var clockwiseTurns: Int = 0
        private set

    val isDirty: Boolean get() = clockwiseTurns != 0
    val canUndo: Boolean get() = previousTurns.isNotEmpty()

    fun rotateClockwise() {
        val next = (clockwiseTurns + 1) % 4
        if (next == 0) {
            clockwiseTurns = 0
            previousTurns.clear()
        } else {
            previousTurns += clockwiseTurns
            clockwiseTurns = next
        }
    }

    fun undo() {
        check(canUndo) { "There is no rotation to undo" }
        clockwiseTurns = previousTurns.removeAt(previousTurns.lastIndex)
        if (clockwiseTurns == 0) previousTurns.clear()
    }
}
