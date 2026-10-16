package app.docscan.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OutputNameResolverTest {
    @Test
    fun suggestsFirstSafeMeaningfulOcrLineWithLocalTimestamp() {
        val result = OutputBasenameSuggester.suggest(
            listOf(null, "  Invoice / № 42  \nIgnored second line", "later page"),
            LocalDateTime.of(2026, 9, 24, 16, 4),
        )

        assertEquals("Invoice No 42 2026-09-24 16-04", result)
    }

    @Test
    fun fallsBackToScanWhenNoSafeOcrLineExistsAndNeverUsesPathSeparators() {
        val result = OutputBasenameSuggester.suggest(
            listOf("///", "\\\\", " ### ", "   "),
            LocalDateTime.of(2026, 9, 24, 9, 7),
        )

        assertEquals("Scan 2026-09-24 09-07", result)
        assertTrue('/' !in result && '\\' !in result && ':' !in result)
    }

    @Test
    fun semanticPrefixIsLimitedToFortyUnicodeCodePoints() {
        val result = OutputBasenameSuggester.suggest(
            listOf("😀A".repeat(24)),
            LocalDateTime.of(2026, 9, 24, 9, 7),
        )

        assertEquals("😀A".repeat(20) + " 2026-09-24 09-07", result)
    }

    @Test
    fun resolvesOneCommonPdfBasenameAndStableOrderedJpegNames() {
        val result = OutputNameResolver.resolve("  Invoice № 8.pdf  ", 12, emptyList())

        assertTrue(result is OutputNameResolution.Resolved)
        val names = (result as OutputNameResolution.Resolved).names
        assertEquals("Invoice № 8", names.basename)
        assertEquals("Invoice № 8.pdf", names.pdfName)
        assertEquals(listOf("Invoice № 8 — 01.jpg", "Invoice № 8 — 02.jpg", "Invoice № 8 — 12.jpg"),
            listOf(names.pageJpegNames.first(), names.pageJpegNames[1], names.pageJpegNames.last()))
    }

    @Test
    fun nameCollisionComparisonUsesWhitespaceDotAndUnicodeRulesConsistently() {
        val result = OutputNameResolver.resolve("Scan", 1, listOf(" scan.pdf. "))

        assertEquals("scan.pdf", OutputNameResolver.collisionKey(" scan.pdf. "))
        assertEquals("scan.pdf", OutputNameResolver.collisionKey("Scan.pdf"))
        assertEquals("Scan (1)", (result as OutputNameResolution.Resolved).names.basename)
    }

    @Test
    fun singlePageUsesTheCommonBasenameWithoutANumberedSuffix() {
        val result = OutputNameResolver.resolve("Scan", 1, emptyList())

        assertEquals(
            OutputNameResolution.Resolved(ExportOutputNames("Scan", "Scan.pdf", listOf("Scan.jpg"))),
            result,
        )
    }

    @Test
    fun rejectsEmptyPathLikeAndControlCharacterNames() {
        listOf("", "   ", ".", "..", "../private", "folder\\scan", "scan\nnow", "scan\u202Efdp").forEach { value ->
            assertTrue("Expected invalid basename: $value", OutputNameResolver.resolve(value, 1, emptyList())
                is OutputNameResolution.Invalid)
        }
        assertTrue(OutputNameResolver.resolve("scan", 0, emptyList()) is OutputNameResolution.Invalid)
    }

    @Test
    fun anyCaseOrCompatibilityEquivalentCollisionResolvesTheWholeSetWithOneSuffix() {
        val result = OutputNameResolver.resolve("Scan", 2, listOf("scan — 02.JPG", "other.pdf"))

        assertEquals(
            OutputNameResolution.Resolved(
                ExportOutputNames(
                    "Scan (1)",
                    "Scan (1).pdf",
                    listOf("Scan (1) — 01.jpg", "Scan (1) — 02.jpg"),
                ),
            ),
            result,
        )
    }

    @Test
    fun skipsEverySuffixWhosePdfOrAnyCompanionJpegAlreadyExists() {
        val result = OutputNameResolver.resolve(
            "Scan",
            2,
            listOf(
                "scan.pdf",
                "SCAN (1) — 02.JPG",
                "Scan (2).pdf",
                "Scan (3) — 01.jpg",
            ),
        )

        assertEquals(
            OutputNameResolution.Resolved(
                ExportOutputNames(
                    "Scan (4)",
                    "Scan (4).pdf",
                    listOf("Scan (4) — 01.jpg", "Scan (4) — 02.jpg"),
                ),
            ),
            result,
        )
    }

    @Test
    fun choosesSuffixPastOneThousandWithoutFixedSuffixLimit() {
        val existing = listOf("Scan.pdf") + (1..1_001).map { "Scan ($it).pdf" }

        val result = OutputNameResolver.resolve("Scan", 1, existing)

        assertEquals(
            OutputNameResolution.Resolved(
                ExportOutputNames("Scan (1002)", "Scan (1002).pdf", listOf("Scan (1002).jpg")),
            ),
            result,
        )
    }

    @Test
    fun editedPdfUsesTheFirstFreeDeterministicSuffixAndOnlyChecksPdfNames() {
        val result = OutputNameResolver.resolveSinglePdfWithSuffix(
            "Scan (edited)",
            listOf("scan (edited).PDF", "Scan (edited) (1).pdf", "Scan (edited)-001.jpg"),
        )

        assertEquals(
            OutputNameResolution.Resolved(
                ExportOutputNames("Scan (edited) (2)", "Scan (edited) (2).pdf", listOf("Scan (edited) (2).jpg")),
            ),
            result,
        )
    }

    @Test
    fun singlePdfSuffixSearchIsBoundedByExistingNamesRatherThanAnArbitraryLimit() {
        val existing = listOf("Scan (edited).pdf") + (1..1_001).map { "Scan (edited) ($it).pdf" }

        val result = OutputNameResolver.resolveSinglePdfWithSuffix("Scan (edited)", existing)

        assertEquals(
            OutputNameResolution.Resolved(
                ExportOutputNames(
                    "Scan (edited) (1002)",
                    "Scan (edited) (1002).pdf",
                    listOf("Scan (edited) (1002).jpg"),
                ),
            ),
            result,
        )
    }
}
