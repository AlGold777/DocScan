package app.docscan.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.schedule.HeavyWorkScheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageRotationEditorInstrumentedTest {
    @Test
    fun applyingRotationCreatesNewVersionAndUndoRestoresOriginalArtifact() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val suffix = UUID.randomUUID().toString()
        val storage = ProductStorage(context, "rotation-$suffix.db", "rotation-artifacts-$suffix")
        val fixture = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also {
            assertTrue(fixture.compress(Bitmap.CompressFormat.JPEG, 95, it))
        }.toByteArray()
        fixture.recycle()

        val source = storage.stageCapturedJpeg(ByteArrayInputStream(bytes))
        val pageId = PageId.new()
        storage.commitCapture(
            CaptureCommand(
                workspaceId = storage.workspaceId,
                pageId = pageId,
                imageVersionId = ImageVersionId.new(),
                artifact = source,
                selected = true,
                makeActive = true,
                coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                processingDesiredState = "OCR_RUS_ENG_TESSERACT_V1",
                sourceProvenance = "TEST",
            ),
        )
        val before = storage.workspaceProjection().pages.single()
        val originalBytes = storage.artifactPath(source.relativePath).toFile().readBytes()

        val rotatedProjection = storage.rotatePageImage(before, 1, HeavyWorkScheduler())
        val rotated = rotatedProjection.pages.single()
        assertNotEquals(before.imageVersionId, rotated.imageVersionId)
        assertEquals(before.pageId, rotated.pageId)
        assertTrue(rotated.canUndoImage)
        val rotatedBitmap = BitmapFactory.decodeFile(storage.artifactPath(rotated.artifactRelativePath).toString())
        assertEquals(80, rotatedBitmap.width)
        assertEquals(120, rotatedBitmap.height)
        rotatedBitmap.recycle()
        assertTrue(storage.artifactPath(source.relativePath).toFile().readBytes().contentEquals(originalBytes))

        storage.undoPageImage(rotated)
        val restored = storage.workspaceProjection().pages.single()
        assertEquals(before.imageVersionId, restored.imageVersionId)
        assertEquals(source.relativePath, restored.artifactRelativePath)
        assertTrue(storage.artifactPath(source.relativePath).toFile().readBytes().contentEquals(originalBytes))
    }
}
