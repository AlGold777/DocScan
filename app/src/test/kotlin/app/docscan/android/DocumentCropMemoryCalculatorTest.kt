package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Test

class DocumentCropMemoryCalculatorTest {

    @Test
    fun estimateWarpWorkingSetBytes_doesNotOverflow32BitInt() {
        val largePixelCount = 50_000_000L // 50 MP
        val estimatedBytes = DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(largePixelCount)
        // 50M * 16 + 4MB = 800MB + 4MB = 804MB (well above Int.MAX_VALUE if it were in bits or multi-stage arithmetic)
        assertTrue(estimatedBytes > 0L)
        assertEquals(largePixelCount * 16L + 4L * 1024L * 1024L, estimatedBytes)

        val extremePixelCount = 500_000_000L // 500 MP
        val extremeBytes = DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(extremePixelCount)
        assertTrue("Estimated bytes must be positive and > Int.MAX_VALUE", extremeBytes > Int.MAX_VALUE.toLong())
    }

    @Test
    fun calculateBoundedPixels_capsWhenSourceExceedsBudget() {
        val width = 4000
        val height = 3000 // 12 MP
        val budgetBytes = 96L * 1024L * 1024L // 96 MiB

        val bounded = DocumentCropMemoryCalculator.calculateBoundedPixels(
            sourceWidth = width,
            sourceHeight = height,
            maxBudgetBytes = budgetBytes,
            maxSourcePixelsLimit = 20_000_000L,
        )

        val expectedCap = (budgetBytes - 4L * 1024L * 1024L) / 16L
        assertEquals(expectedCap, bounded)
        assertTrue(bounded < width.toLong() * height.toLong())
    }

    @Test
    fun calculateBoundedPixels_smallBudgetNeverExceedsItsEstimate() {
        val budgetBytes = 8L * 1024L * 1024L
        val bounded = DocumentCropMemoryCalculator.calculateBoundedPixels(
            sourceWidth = 4000,
            sourceHeight = 3000,
            maxBudgetBytes = budgetBytes,
        )

        assertTrue("Bounded pixels must fit inside the 8 MiB budget", bounded <= 262_144L)
        assertTrue(
            "The working-set estimate must fit the requested budget",
            DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(bounded) <= budgetBytes,
        )
    }

    @Test
    fun calculateBoundedPixels_rejectsBudgetBelowReservePlusOnePixel() {
        val minimumPossibleBudget = 4L * 1024L * 1024L + 16L
        val insufficientBudget = minimumPossibleBudget - 1L

        val result = runCatching {
            DocumentCropMemoryCalculator.calculateBoundedPixels(
                sourceWidth = 4000,
                sourceHeight = 3000,
                maxBudgetBytes = insufficientBudget,
            )
        }

        assertTrue("Insufficient budget must be rejected before allocation", result.isFailure)
    }

    @Test
    fun planWarp_accountsForPerspectiveOutputLargerThanDecodedSource() {
        val sourceWidth = 1000
        val sourceHeight = 800
        val quad = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.0, 0.5),
            topRight = NormalizedCropPoint(1.0, 0.0),
            bottomRight = NormalizedCropPoint(0.5, 1.0),
            bottomLeft = NormalizedCropPoint(0.0, 1.0),
        )

        val plan = DocumentCropMemoryCalculator.planWarp(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            quad = quad,
        )
        val decodedPixels = plan.decodedWidth.toLong() * plan.decodedHeight
        val outputPixels = plan.outputWidth.toLong() * plan.outputHeight

        assertTrue("Fixture must exercise an output larger than its source", outputPixels > decodedPixels)
        assertTrue(plan.workingSetBytes <= DocumentCropMemoryCalculator.DEFAULT_MAX_BUDGET_BYTES)
        assertEquals(
            plan.workingSetBytes,
            DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(decodedPixels, outputPixels),
        )
    }

    @Test
    fun planWarp_keepsOrdinaryPageWithinDefaultBudget() {
        val plan = DocumentCropMemoryCalculator.planWarp(
            sourceWidth = 4000,
            sourceHeight = 3000,
            quad = CropQuadrilateral(
                topLeft = NormalizedCropPoint(0.05, 0.04),
                topRight = NormalizedCropPoint(0.95, 0.04),
                bottomRight = NormalizedCropPoint(0.95, 0.96),
                bottomLeft = NormalizedCropPoint(0.05, 0.96),
            ),
        )

        assertTrue(plan.workingSetBytes <= DocumentCropMemoryCalculator.DEFAULT_MAX_BUDGET_BYTES)
        assertTrue(plan.decodedWidth.toLong() * plan.decodedHeight <= 12_000_000L)
    }

    @Test
    fun planWarp_rejectsBudgetTooSmallForReserveAndOnePixel() {
        val result = runCatching {
            DocumentCropMemoryCalculator.planWarp(
                sourceWidth = 1000,
                sourceHeight = 800,
                quad = CropQuadrilateral(
                    topLeft = NormalizedCropPoint(0.05, 0.05),
                    topRight = NormalizedCropPoint(0.95, 0.05),
                    bottomRight = NormalizedCropPoint(0.95, 0.95),
                    bottomLeft = NormalizedCropPoint(0.05, 0.95),
                ),
                maxBudgetBytes = 4L * 1024L * 1024L + 15L,
            )
        }

        assertTrue("An impossible warp plan must be rejected before allocation", result.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun planWarp_rejectsOnePixelThinSource() {
        DocumentCropMemoryCalculator.planWarp(
            sourceWidth = 1,
            sourceHeight = 1000,
            quad = CropQuadrilateral(
                topLeft = NormalizedCropPoint(0.0, 0.0),
                topRight = NormalizedCropPoint(1.0, 0.0),
                bottomRight = NormalizedCropPoint(1.0, 1.0),
                bottomLeft = NormalizedCropPoint(0.0, 1.0),
            ),
        )
    }

    @Test
    fun estimateWarpWorkingSetBytes_rejectsArithmeticOverflow() {
        val result = runCatching {
            DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(Long.MAX_VALUE)
        }

        assertTrue(
            "Overflow must be rejected or saturated instead of wrapping to a small estimate",
            result.isFailure || result.getOrNull() == Long.MAX_VALUE,
        )
    }

    @Test
    fun calculateBoundedPixels_preservesSourceWhenWithinBudget() {
        val width = 1000
        val height = 800 // 0.8 MP
        val budgetBytes = 96L * 1024L * 1024L

        val bounded = DocumentCropMemoryCalculator.calculateBoundedPixels(
            sourceWidth = width,
            sourceHeight = height,
            maxBudgetBytes = budgetBytes,
        )

        assertEquals(800_000L, bounded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculateBoundedPixels_rejectsZeroWidth() {
        DocumentCropMemoryCalculator.calculateBoundedPixels(0, 1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculateBoundedPixels_rejectsNegativeHeight() {
        DocumentCropMemoryCalculator.calculateBoundedPixels(1000, -5)
    }
}
