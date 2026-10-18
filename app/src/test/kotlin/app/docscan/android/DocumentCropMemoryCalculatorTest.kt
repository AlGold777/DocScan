package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
