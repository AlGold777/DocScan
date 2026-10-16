package app.docscan.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RightAngleRotationDraftTest {
    @Test
    fun undoReturnsToPreviousDirtyStateAndThenCleanState() {
        val draft = RightAngleRotationDraft()

        draft.rotateClockwise()
        draft.rotateClockwise()
        assertEquals(2, draft.clockwiseTurns)
        assertTrue(draft.isDirty)

        draft.undo()
        assertEquals(1, draft.clockwiseTurns)
        assertTrue(draft.isDirty)
        draft.undo()
        assertEquals(0, draft.clockwiseTurns)
        assertFalse(draft.isDirty)
        assertFalse(draft.canUndo)
    }

    @Test
    fun fullRotationReturnsToCleanStateWithoutUndoHistory() {
        val draft = RightAngleRotationDraft()
        repeat(4) { draft.rotateClockwise() }

        assertEquals(0, draft.clockwiseTurns)
        assertFalse(draft.isDirty)
        assertFalse(draft.canUndo)
    }
}
