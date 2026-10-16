package app.docscan.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PageImageEditDraftTest {
    @Test
    fun cropQuadrilateralRequiresOrderedInBoundsConvexCorners() {
        val valid = CropQuadrilateral(
            NormalizedCropPoint(0.1, 0.1),
            NormalizedCropPoint(0.9, 0.1),
            NormalizedCropPoint(0.8, 0.9),
            NormalizedCropPoint(0.2, 0.9),
        )
        assertEquals(4, valid.asList().size)
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedCropPoint(-0.1, 0.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CropQuadrilateral(
                NormalizedCropPoint(0.1, 0.1),
                NormalizedCropPoint(0.9, 0.9),
                NormalizedCropPoint(0.9, 0.1),
                NormalizedCropPoint(0.1, 0.9),
            )
        }
    }

    @Test
    fun manualCropIsOneUndoableDraftOperation() {
        val draft = PageImageEditDraft()
        val crop = CropQuadrilateral(
            NormalizedCropPoint(0.1, 0.1),
            NormalizedCropPoint(0.9, 0.1),
            NormalizedCropPoint(0.9, 0.9),
            NormalizedCropPoint(0.1, 0.9),
        )

        draft.apply(draft.state.copy(crop = crop))
        assertTrue(draft.isDirty)
        draft.undo()
        assertEquals(null, draft.state.crop)
        assertFalse(draft.isDirty)
    }

    @Test
    fun cropCornersRotateWithThePageAndRemainUndoable() {
        val draft = PageImageEditDraft()
        val originalCrop = CropQuadrilateral(
            NormalizedCropPoint(0.1, 0.2),
            NormalizedCropPoint(0.9, 0.2),
            NormalizedCropPoint(0.9, 0.8),
            NormalizedCropPoint(0.1, 0.8),
        )
        draft.apply(draft.state.copy(crop = originalCrop))
        val rotated = draft.state.crop!!.rotatedClockwise(1)
        draft.apply(draft.state.copy(clockwiseTurns = 1, crop = rotated))

        assertEquals(0.2, draft.state.crop!!.topLeft.x, 1e-9)
        assertEquals(0.1, draft.state.crop!!.topLeft.y, 1e-9)
        draft.undo()
        assertEquals(originalCrop, draft.state.crop)
    }

    @Test
    fun previewsBecomeOneUndoStepAndUndoReturnsToCleanState() {
        val draft = PageImageEditDraft()
        draft.preview(PageImageEditState(brightness = 15))
        draft.preview(PageImageEditState(brightness = 30))
        assertTrue(draft.isDirty)
        assertFalse(draft.canUndo)

        draft.commitPreview()
        assertTrue(draft.canUndo)
        draft.undo()

        assertEquals(PageImageEditState(), draft.state)
        assertFalse(draft.isDirty)
        assertFalse(draft.canUndo)
    }

    @Test
    fun undoRunsOperationsInReverseOrderAndSaveClearsHistory() {
        val draft = PageImageEditDraft()
        draft.apply(PageImageEditState(clockwiseTurns = 1))
        draft.apply(PageImageEditState(clockwiseTurns = 1, contrastPercent = 125))
        draft.apply(PageImageEditState(clockwiseTurns = 1, contrastPercent = 125, sharpnessPercent = 40))

        draft.undo()
        assertEquals(125, draft.state.contrastPercent)
        assertEquals(0, draft.state.sharpnessPercent)
        draft.markSaved()

        assertFalse(draft.isDirty)
        assertFalse(draft.canUndo)
        assertEquals(125, draft.state.contrastPercent)
    }

    @Test
    fun visuallyNeutralFullRotationRemainsUndoableUntilTheUserUndoesAllSteps() {
        val draft = PageImageEditDraft()
        repeat(4) { draft.apply(draft.state.copy(clockwiseTurns = (draft.state.clockwiseTurns + 1) % 4)) }

        assertEquals(PageImageEditState(), draft.state)
        assertTrue(draft.isDirty)
        repeat(4) { draft.undo() }
        assertFalse(draft.isDirty)
        assertFalse(draft.canUndo)
    }
}
