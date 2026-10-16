package app.docscan.core.recent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedPdfEditSessionTest {
    @Test
    fun reorderAndUndoReturnToCleanSourceOrder() {
        val session = SavedPdfEditSession(4)

        session.move(0, 2)

        assertEquals(listOf(1, 2, 0, 3), session.pageOrder)
        assertTrue(session.isDirty)
        assertTrue(session.undo())
        assertEquals(listOf(0, 1, 2, 3), session.pageOrder)
        assertFalse(session.isDirty)
        assertFalse(session.canUndo)
    }

    @Test
    fun deleteAllDisablesSaveAndUndoRestoresPagesInOrder() {
        val session = SavedPdfEditSession(2)

        assertEquals(0, session.removeAt(0))
        assertEquals(-1, session.removeAt(0))
        assertFalse(session.canSave)
        assertTrue(session.isDirty)

        assertTrue(session.undo())
        assertEquals(listOf(1), session.pageOrder)
        assertTrue(session.canSave)
        assertTrue(session.undo())
        assertEquals(listOf(0, 1), session.pageOrder)
        assertFalse(session.isDirty)
    }

    @Test
    fun invalidIndexesFailWithoutChangingTheDraft() {
        val session = SavedPdfEditSession(1)

        assertFailsWith<IllegalArgumentException> { SavedPdfEditSession(0) }
        assertFailsWith<IllegalArgumentException> { session.removeAt(1) }
        assertFailsWith<IllegalArgumentException> { session.move(0, 1) }
        assertEquals(listOf(0), session.pageOrder)
        assertFalse(session.isDirty)
    }
}
