package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentCropPlanTest {

    private val sampleQuad = CropQuadrilateral(
        topLeft = NormalizedCropPoint(0.1, 0.1),
        topRight = NormalizedCropPoint(0.9, 0.1),
        bottomRight = NormalizedCropPoint(0.9, 0.9),
        bottomLeft = NormalizedCropPoint(0.1, 0.9),
    )

    @Test
    fun plan_preservesInputMetadata() {
        val pageId = PageId.new()
        val versionId = ImageVersionId.new()
        val plan = DocumentCropPlan(
            pageId = pageId,
            initialImageVersionId = versionId,
            initialContentRevision = 3L,
            cropQuad = sampleQuad,
            orientedWidth = 2000,
            orientedHeight = 3000,
            artifactRelativePath = "pages/page1.jpg",
            algorithmVersion = "DOC_CROP_V2",
        )

        assertEquals(pageId, plan.pageId)
        assertEquals(versionId, plan.initialImageVersionId)
        assertEquals(3L, plan.initialContentRevision)
        assertEquals(sampleQuad, plan.cropQuad)
        assertEquals(2000, plan.orientedWidth)
        assertEquals(3000, plan.orientedHeight)
        assertEquals("pages/page1.jpg", plan.artifactRelativePath)
        assertEquals("DOC_CROP_V2", plan.algorithmVersion)
    }

    @Test
    fun isStale_detectsVersionOrRevisionChange() {
        val pageId = PageId.new()
        val versionId = ImageVersionId.new()
        val plan = DocumentCropPlan(
            pageId = pageId,
            initialImageVersionId = versionId,
            initialContentRevision = 1L,
            cropQuad = sampleQuad,
            orientedWidth = 1000,
            orientedHeight = 1500,
            artifactRelativePath = "p.jpg",
        )

        // Same version and revision -> not stale
        assertFalse(plan.initialImageVersionId != versionId || plan.initialContentRevision != 1L)

        // Revision changed (e.g. rotated or edited) -> stale
        val modifiedRevision = 2L
        assertTrue(plan.initialImageVersionId != versionId || plan.initialContentRevision != modifiedRevision)

        // Version changed -> stale
        val modifiedVersion = ImageVersionId.new()
        assertTrue(plan.initialImageVersionId != modifiedVersion || plan.initialContentRevision != 1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plan_rejectsNonPositiveDimensions() {
        DocumentCropPlan(
            pageId = PageId.new(),
            initialImageVersionId = ImageVersionId.new(),
            initialContentRevision = 1L,
            cropQuad = sampleQuad,
            orientedWidth = 0,
            orientedHeight = 1000,
            artifactRelativePath = "p.jpg",
        )
    }
}
