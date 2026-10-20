package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.RecognitionCandidate
import app.docscan.core.schedule.HeavyWorkScheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class DocumentCropPlanInstrumentedTest {
    private data class StorageFixture(val suffix: String, val storage: ProductStorage)

    @Test
    fun pixelCentreLastCornerNormalizesToOneAndWarpSamplesLastSourcePixel() {
        DocumentCropProcessor.ensureOpenCvLoaded()
        val width = 101
        val height = 81
        val right = (width - 1).toFloat()
        val bottom = (height - 1).toFloat()
        val detection = DocumentCropProcessor.Detection(
            bufferPoints = floatArrayOf(0f, 0f, right, 0f, right, bottom, 0f, bottom),
            imageWidth = width,
            imageHeight = height,
            score = 0.9,
        )
        val quad = detection.toNormalized()
        assertEquals(1.0, quad.topRight.x, 0.0)
        assertEquals(1.0, quad.bottomRight.x, 0.0)
        assertEquals(1.0, quad.bottomRight.y, 0.0)
        assertEquals(1.0, quad.bottomLeft.y, 0.0)

        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            setPixel(width - 1, height - 1, Color.MAGENTA)
        }
        try {
            val warped = DocumentCropProcessor.rectifyManual(source, quad)
            try {
                assertEquals(width - 1, warped.width)
                assertEquals(height - 1, warped.height)
                assertEquals("Normalized 1.0 must warp from the final source pixel centre",
                    Color.MAGENTA, warped.getPixel(width - 2, height - 2))
            } finally {
                warped.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun previewThenApplyUsesOneDetectionAndInvalidatesRecognitionForNewImageVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = mutableListOf<Pair<Int, Int>>()
        val fixture = newStorage(context, detector = { bitmap ->
            calls += bitmap.width to bitmap.height
            detection(bitmap.width, bitmap.height)
        })
        val storage = fixture.storage
        val scheduler = HeavyWorkScheduler()
        try {
            val page = capture(storage)
            storage.commitRecognition(
                RecognitionCandidate(
                    recognitionId = RecognitionId.new(),
                    workspaceId = storage.workspaceId,
                    pageId = page.pageId,
                    imageVersionId = page.imageVersionId,
                    contentRevision = page.contentRevision,
                    desiredOperationKey = "TEST_OCR_V1",
                    recognizedText = "recognized before crop",
                ),
            )
            val recognized = storage.workspaceProjection().pages.single()
            assertEquals("recognized before crop", recognized.recognizedText)
            val originalVersion = recognized.imageVersionId
            val originalRevision = recognized.contentRevision

            val preview = requireNotNull(storage.createDocumentCropPreview(recognized, scheduler))
            try {
                assertEquals("Preview must run corner detection once", 1, calls.size)
                assertTrue(preview.previewBitmap.width > 0 && preview.previewBitmap.height > 0)
                assertThrows(IllegalStateException::class.java) {
                    storage.applyDocumentCrop(preview.plan.copy(artifactRelativePath = "other-page.jpg"), scheduler)
                }
                assertEquals("A plan cannot be redirected to another artifact", recognized,
                    storage.workspaceProjection().pages.single())
                val cropped = storage.applyDocumentCrop(preview.plan, scheduler).pages.single()

                assertEquals("Apply must reuse the preview plan", 1, calls.size)
                assertEquals(recognized.pageId, cropped.pageId)
                assertNotEquals(originalVersion, cropped.imageVersionId)
                assertTrue(cropped.contentRevision > originalRevision)
                assertNull("New pixels cannot retain text from the prior image version", cropped.recognizedText)
                assertTrue(cropped.canUndoImage)
            } finally {
                preview.previewBitmap.recycle()
            }
        } finally {
            closeStorage(context, fixture)
        }
    }

    @Test
    fun stalePlanAfterEditAndDeleteIsRejectedWithoutChangingCurrentProjection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = { bitmap: Bitmap -> detection(bitmap.width, bitmap.height) }
        val fixture = newStorage(context, detector)
        val storage = fixture.storage
        val scheduler = HeavyWorkScheduler()
        try {
            val editedPage = capture(storage)
            val editPlan = requireNotNull(storage.createDocumentCropPreview(editedPage, scheduler)).also {
                it.previewBitmap.recycle()
            }.plan

            val rotated = storage.rotatePageImage(editedPage, 1, scheduler).pages.single()
            val beforeRejectedEditPlan = storage.workspaceProjection().pages.single()
            assertThrows(IllegalStateException::class.java) {
                storage.applyDocumentCrop(editPlan, scheduler)
            }
            assertEquals("A stale plan must not publish or revise the edited page", beforeRejectedEditPlan,
                storage.workspaceProjection().pages.single())

            val deletedPage = capture(storage)
            val deletePlanResult = requireNotNull(storage.createDocumentCropPreview(deletedPage, scheduler))
            val deletePlan = deletePlanResult.plan
            deletePlanResult.previewBitmap.recycle()
            val unaffectedBeforeDelete = storage.workspaceProjection().pages.single { it.pageId == rotated.pageId }
            storage.deletePage(deletedPage)
            val projectionAfterDelete = storage.workspaceProjection()
            val unaffectedAfterDelete = projectionAfterDelete.pages.single()
            assertEquals(unaffectedBeforeDelete, unaffectedAfterDelete)

            assertThrows(IllegalStateException::class.java) {
                storage.applyDocumentCrop(deletePlan, scheduler)
            }
            val projectionAfterRejectedDeletePlan = storage.workspaceProjection()
            assertNull(projectionAfterRejectedDeletePlan.pages.find { it.pageId == deletedPage.pageId })
            assertEquals("Rejecting a deleted-page plan must leave other pages untouched", unaffectedAfterDelete,
                projectionAfterRejectedDeletePlan.pages.single())
            assertEquals("No page rows remain after both checks except the unchanged page", 1,
                projectionAfterRejectedDeletePlan.pages.size)
        } finally {
            closeStorage(context, fixture)
        }
    }

    @Test
    fun previewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previewInput = arrayOfNulls<Bitmap>(1)
        val previewFixture = newStorage(
            context,
            detector = { bitmap -> detection(bitmap.width, bitmap.height) },
            rectifier = { bitmap, _ ->
                previewInput[0] = bitmap
                throw OutOfMemoryError("injected preview allocation failure")
            },
        )
        try {
            val storage = previewFixture.storage
            val scheduler = HeavyWorkScheduler()
            val page = capture(storage)
            commitRecognition(storage, page)
            val before = storage.workspaceProjection().pages.single()
            assertThrows(OutOfMemoryError::class.java) {
                storage.createDocumentCropPreview(before, scheduler)
            }
            assertTrue("Preview source is always recycled", requireNotNull(previewInput[0]).isRecycled)
            assertEquals("Preview OOM cannot mutate page state", before, storage.workspaceProjection().pages.single())
            assertTrue("Scheduler admission is released after preview OOM", scheduler.withAdmission(1L) { true })
        } finally {
            closeStorage(context, previewFixture)
        }

        var rectifierCalls = 0
        val applyInput = arrayOfNulls<Bitmap>(1)
        val applyFixture = newStorage(
            context,
            detector = { bitmap -> detection(bitmap.width, bitmap.height) },
            rectifier = { bitmap, quad ->
                rectifierCalls++
                if (rectifierCalls == 1) {
                    DocumentCropProcessor.rectifyManual(bitmap, quad)
                } else {
                    applyInput[0] = bitmap
                    throw OutOfMemoryError("injected apply allocation failure")
                }
            },
        )
        try {
            val storage = applyFixture.storage
            val scheduler = HeavyWorkScheduler()
            val page = capture(storage)
            commitRecognition(storage, page)
            val before = storage.workspaceProjection().pages.single()
            val preview = requireNotNull(storage.createDocumentCropPreview(before, scheduler))
            try {
                assertThrows(OutOfMemoryError::class.java) {
                    storage.applyDocumentCrop(preview.plan, scheduler)
                }
            } finally {
                preview.previewBitmap.recycle()
            }
            assertTrue("Apply source is always recycled", requireNotNull(applyInput[0]).isRecycled)
            assertEquals("Apply OOM cannot publish or revise the page", before, storage.workspaceProjection().pages.single())
            assertTrue("Scheduler admission is released after apply OOM", scheduler.withAdmission(1L) { true })
        } finally {
            closeStorage(context, applyFixture)
        }
    }

    private fun newStorage(
        context: Context,
        detector: (Bitmap) -> DocumentCropProcessor.Detection?,
        rectifier: (Bitmap, app.docscan.core.edit.CropQuadrilateral) -> Bitmap = DocumentCropProcessor::rectifyManual,
    ): StorageFixture {
        val suffix = UUID.randomUUID().toString()
        return StorageFixture(
            suffix,
            ProductStorage(
                context = context,
                databaseName = "document-crop-$suffix.db",
                artifactDirectoryName = "document-crop-artifacts-$suffix",
                documentCornerDetector = detector,
                documentRectifier = rectifier,
            ),
        )
    }

    private fun capture(storage: ProductStorage): app.docscan.core.store.StoredPage {
        val bitmap = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(48, 52, 56))
            drawRect(45f, 40f, 595f, 865f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            stream.toByteArray()
        }
        bitmap.recycle()
        val pageId = PageId.new()
        storage.commitCapture(
            CaptureCommand(
                workspaceId = storage.workspaceId,
                pageId = pageId,
                imageVersionId = ImageVersionId.new(),
                artifact = storage.stageCapturedJpeg(ByteArrayInputStream(bytes)),
                selected = true,
                makeActive = true,
                coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                processingDesiredState = "TEST_OCR_V1",
                sourceProvenance = "DOCUMENT_CROP_PLAN_TEST",
            ),
        )
        return storage.workspaceProjection().pages.single { it.pageId == pageId }
    }

    private fun commitRecognition(storage: ProductStorage, page: app.docscan.core.store.StoredPage) {
        storage.commitRecognition(
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = storage.workspaceId,
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                contentRevision = page.contentRevision,
                desiredOperationKey = "TEST_OCR_V1",
                recognizedText = "recognized before crop",
            ),
        )
    }

    private fun detection(width: Int, height: Int): DocumentCropProcessor.Detection {
        require(width > 1 && height > 1)
        val right = (width - 1).toFloat()
        val bottom = (height - 1).toFloat()
        return DocumentCropProcessor.Detection(
        // Keep the old perspective/skew intent, expressed in the detector's current pixel-centre space.
        bufferPoints = floatArrayOf(
            right * 0.07f, bottom * 0.045f,
            right * 0.93f, bottom * 0.06f,
            right * 0.95f, bottom * 0.945f,
            right * 0.045f, bottom * 0.96f,
        ),
        imageWidth = width,
        imageHeight = height,
        score = 0.90,
        )
    }

    private fun closeStorage(context: Context, fixture: StorageFixture) {
        fixture.storage.close()
        context.deleteDatabase("document-crop-${fixture.suffix}.db")
        context.noBackupFilesDir.resolve("document-crop-artifacts-${fixture.suffix}").deleteRecursively()
    }
}
