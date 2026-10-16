package app.docscan.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.OperationCanceledException
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPdfImportReaderInstrumentedTest {
    @Test
    fun cancellationAfterRasterizerReturnsRecyclesBitmapAndStopsBeforeOutputOrCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = threePagePdf(context, "pdf-import-cancel-after-raster.pdf")
        val scratch = File(context.cacheDir, "pdf-import-cancel-after-raster-scratch").apply { mkdirs() }
        val signal = CancellationSignal()
        val scheduler = app.docscan.core.schedule.HeavyWorkScheduler()
        val rendered = mutableListOf<Bitmap>()
        var callbacks = 0
        val outputOpens = java.util.concurrent.atomic.AtomicInteger()
        val reader = AndroidPdfImportReader.withPngOutputForTest(
            context,
            PdfPageBitmapRenderer { renderer, pageIndex, scale ->
                assertEquals("Cancellation must stop before a later page rasterizes", 0, pageIndex)
                renderer.renderImage(pageIndex, scale).also {
                    rendered += it
                    signal.cancel()
                }
            },
            PdfPagePngOutputFactory {
                outputOpens.incrementAndGet()
                throw AssertionError("Cancellation after rasterization must be checked before opening PNG output")
            },
            Bitmap::recycle,
        )
        val sourceDigest = sha256(pdf)
        try {
            val failure = org.junit.Assert.assertThrows(OperationCanceledException::class.java) {
                reader.renderPages(
                    source = pdf,
                    expectedPageCount = 3,
                    skipPageIndices = emptySet(),
                    scratchDirectory = scratch,
                    scheduler = scheduler,
                    cancellationSignal = signal,
                ) { _, _, _ -> callbacks++ }
            }
            assertTrue(failure.message.orEmpty().isNotBlank())
            assertEquals(1, rendered.size)
            assertTrue("Bitmap returned by the rasterizer is recycled exactly through the real recycler", rendered.single().isRecycled)
            assertEquals("Output stream was not opened", 0, outputOpens.get())
            assertEquals("Canceled page was not delivered", 0, callbacks)
            assertTrue("Canceled page temp output is removed", scratch.listFiles().isNullOrEmpty())
            assertEquals("Cancellation releases scheduler admission", "available",
                scheduler.withAdmission(80L * 1024L * 1024L) { "available" })
            assertEquals(sourceDigest, sha256(pdf))
        } finally {
            rendered.filterNot(Bitmap::isRecycled).forEach(Bitmap::recycle)
            pdf.delete()
            scratch.deleteRecursively()
        }
    }

    @Test
    fun rejectsEmptyMalformedAndEncryptedPdfs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reader = AndroidPdfImportReader(context)
        val fixtures = mutableListOf<File>()
        try {
            val empty = File(context.cacheDir, "pdf-import-empty.pdf").also(fixtures::add)
            PDDocument().use { it.save(empty) }
            assertRejectedWithoutMutation(reader, empty, IllegalArgumentException::class.java)

            val malformed = File(context.cacheDir, "pdf-import-malformed.pdf").also(fixtures::add)
            malformed.writeBytes("%PDF-1.7\nnot-a-pdf".toByteArray())
            assertRejectedWithoutMutation(reader, malformed, Exception::class.java)

            val truncated = File(context.cacheDir, "pdf-import-truncated.pdf").also(fixtures::add)
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(truncated)
            }
            val original = truncated.readBytes()
            val eofMarker = "%%EOF".toByteArray()
            val markerStart = original.indices.lastOrNull { start ->
                start + eofMarker.size <= original.size
                    && eofMarker.indices.all { offset -> original[start + offset] == eofMarker[offset] }
            } ?: throw AssertionError("Generated PDF fixture has no EOF marker")
            truncated.writeBytes(original.copyOf(markerStart))
            assertRejectedWithoutMutation(reader, truncated, IllegalArgumentException::class.java)

            val encrypted = File(context.cacheDir, "pdf-import-encrypted.pdf").also(fixtures::add)
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.protect(StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission()))
                document.save(encrypted)
            }
            assertRejectedWithoutMutation(reader, encrypted, Exception::class.java)
        } finally {
            fixtures.forEach(File::delete)
        }
    }

    @Test
    fun rejectsMalformedPdfCorpusWithoutSideEffectsAndReopensValidSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val reader = AndroidPdfImportReader(context)
        data class InvalidFixture(
            val name: String,
            val file: File,
            val inspectionMessage: String,
            val renderFailureType: Class<out Throwable>,
            val renderMessageFragment: String?,
        )

        val fixtureFiles = mutableListOf<File>()
        val scratchDirectories = mutableListOf<File>()
        val valid = File(context.cacheDir, "pdf-import-corpus-valid.pdf").also(fixtureFiles::add)
        try {
            PDDocument().use { document ->
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 12f)
                    stream.newLineAtOffset(72f, PDRectangle.A4.height - 72f)
                    stream.showText("Valid parser recovery control 48372615")
                    stream.endText()
                }
                document.save(valid)
            }
            val validDigest = sha256(valid)
            assertEquals(1, reader.inspect(valid).pageCount)

            val empty = File(context.cacheDir, "pdf-import-corpus-zero-page.pdf").also(fixtureFiles::add)
            PDDocument().use { it.save(empty) }

            val malformed = File(context.cacheDir, "pdf-import-corpus-malformed-bytes.pdf").also(fixtureFiles::add)
            malformed.writeBytes("%PDF-1.7\nnot-a-pdf\n%%EOF\n".toByteArray())

            val truncated = File(context.cacheDir, "pdf-import-corpus-truncated.pdf").also(fixtureFiles::add)
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(truncated)
            }
            val truncatedBytes = truncated.readBytes()
            val eofMarker = "%%EOF".toByteArray()
            val markerStart = lastIndexOf(truncatedBytes, eofMarker)
            assertTrue("Generated truncation fixture must contain terminal EOF", markerStart >= 0)
            truncated.writeBytes(truncatedBytes.copyOf(markerStart))

            val encryptedWithUserPassword = File(context.cacheDir,
                "pdf-import-corpus-encrypted-user-password.pdf").also(fixtureFiles::add)
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.protect(StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission()))
                document.save(encryptedWithUserPassword)
            }

            val encryptedWithEmptyUserPassword = File(context.cacheDir,
                "pdf-import-corpus-encrypted-empty-user-password.pdf").also(fixtureFiles::add)
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.protect(StandardProtectionPolicy("owner-secret", "", AccessPermission()))
                document.save(encryptedWithEmptyUserPassword)
            }

            val brokenRoot = File(context.cacheDir, "pdf-import-corpus-broken-root.pdf").also(fixtureFiles::add)
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(brokenRoot)
            }
            val brokenRootBytes = brokenRoot.readBytes()
            val serialized = String(brokenRootBytes, Charsets.ISO_8859_1)
            val rootReference = Regex("/Root\\s+\\d+\\s+\\d+\\s+R")
                .findAll(serialized)
                .lastOrNull()
            assertNotNull("Generated PDF fixture must expose its trailer Root reference", rootReference)
            val corruptRoot = serialized.replaceRange(rootReference!!.range, "/Root 987654 0 R")
            assertTrue("Corrupt fixture must retain its terminal EOF marker", lastIndexOf(
                corruptRoot.toByteArray(Charsets.ISO_8859_1), eofMarker,
            ) >= 0)
            brokenRoot.writeBytes(corruptRoot.toByteArray(Charsets.ISO_8859_1))

            val invalid = listOf(
                InvalidFixture(
                    "zero-page", empty,
                    "PDF must contain between 1 and 500 pages (found 0)",
                    IllegalStateException::class.java,
                    "page count changed after source snapshot",
                ),
                InvalidFixture(
                    "malformed-bytes", malformed,
                    "Private PDF source is malformed or unreadable",
                    IOException::class.java,
                    null,
                ),
                InvalidFixture(
                    "truncated-before-eof", truncated,
                    "PDF is truncated: terminal %%EOF marker is missing",
                    IllegalArgumentException::class.java,
                    "terminal %%EOF marker is missing",
                ),
                InvalidFixture(
                    "unresolvable-trailer-root", brokenRoot,
                    "Private PDF source is malformed or unreadable",
                    IOException::class.java,
                    null,
                ),
                InvalidFixture(
                    "encrypted-user-password-required", encryptedWithUserPassword,
                    "Encrypted PDFs are not supported for import",
                    PdfImportInvalidSourceException::class.java,
                    "Encrypted PDFs are not supported for import",
                ),
                InvalidFixture(
                    "encrypted-empty-user-password", encryptedWithEmptyUserPassword,
                    "Encrypted PDFs are not supported for import",
                    PdfImportInvalidSourceException::class.java,
                    "Encrypted PDFs are not supported for import",
                ),
            )

            invalid.forEachIndexed { fixtureIndex, fixture ->
                val sourceDigest = sha256(fixture.file)
                var firstRenderFailure: Pair<Class<out Throwable>, String?>? = null
                repeat(2) { attempt ->
                    val inspectionFailure = assertThrows(PdfImportInvalidSourceException::class.java) {
                        reader.inspect(fixture.file)
                    }
                    assertEquals("${fixture.name}: inspection error classification", fixture.inspectionMessage,
                        inspectionFailure.message)
                    assertEquals("${fixture.name}: inspection must preserve source bytes", sourceDigest,
                        sha256(fixture.file))

                    val scratch = File(context.cacheDir,
                        "pdf-import-corpus-scratch-$fixtureIndex-$attempt").apply { mkdirs() }
                    scratchDirectories += scratch
                    val callbacks = mutableListOf<Int>()
                    val renderFailure = assertThrows(fixture.renderFailureType) {
                        reader.renderPages(
                            source = fixture.file,
                            expectedPageCount = 1,
                            skipPageIndices = emptySet(),
                            scratchDirectory = scratch,
                            scheduler = app.docscan.core.schedule.HeavyWorkScheduler(),
                        ) { index, _, _ -> callbacks += index }
                    }
                    val actualRenderFailure = renderFailure.javaClass to renderFailure.message
                    if (firstRenderFailure == null) {
                        firstRenderFailure = actualRenderFailure
                    } else {
                        assertEquals("${fixture.name}: repeated render failure must be stable",
                            firstRenderFailure, actualRenderFailure)
                    }
                    assertTrue("${fixture.name}: render failure must be explicit",
                        !renderFailure.message.isNullOrBlank())
                    fixture.renderMessageFragment?.let { fragment ->
                        assertTrue("${fixture.name}: unexpected render diagnostic '${renderFailure.message}'",
                            renderFailure.message.orEmpty().contains(fragment))
                    }
                    assertTrue("${fixture.name}: invalid PDF must not publish a page", callbacks.isEmpty())
                    assertTrue("${fixture.name}: parser failure must leave scratch empty",
                        scratch.listFiles().isNullOrEmpty())
                    assertEquals("${fixture.name}: render failure must preserve source bytes", sourceDigest,
                        sha256(fixture.file))
                    assertEquals("${fixture.name}: a later valid parser open must succeed", 1,
                        reader.inspect(valid).pageCount)
                    assertEquals("${fixture.name}: valid control bytes must remain unchanged", validDigest,
                        sha256(valid))
                }
            }

            val rendered = mutableListOf<Int>()
            val validScratch = File(context.cacheDir, "pdf-import-corpus-valid-scratch").apply { mkdirs() }
            scratchDirectories += validScratch
            reader.renderPages(valid, 1, emptySet(), validScratch,
                app.docscan.core.schedule.HeavyWorkScheduler()) { index, png, _ ->
                assertTrue("Valid control PNG exists during publication", png.isFile)
                val bitmap = BitmapFactory.decodeFile(png.absolutePath)
                assertNotNull(bitmap)
                try {
                    assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
                    rendered += index
                } finally {
                    bitmap?.recycle()
                }
            }
            assertEquals(listOf(0), rendered)
            assertTrue("Valid control scratch output is removed after publication", validScratch.listFiles().isNullOrEmpty())
            assertEquals(validDigest, sha256(valid))
        } finally {
            scratchDirectories.forEach(File::deleteRecursively)
            fixtureFiles.forEach(File::delete)
        }
    }

    @Test
    fun terminalEofAllowsOnlyPdfWhitespaceAndPreservesSourceBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reader = AndroidPdfImportReader(context)
        val source = File(context.cacheDir, "pdf-import-eof-boundary-source.pdf")
        val fixture = File(context.cacheDir, "pdf-import-eof-boundary-fixture.pdf")
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(source)
            }
            val sourceDigest = sha256(source)
            val valid = source.readBytes()
            assertEquals(1, reader.inspect(source).pageCount)

            val eofEnd = lastIndexOf(valid, "%%EOF".toByteArray()) + "%%EOF".length
            assertTrue("Generated fixture must contain a terminal EOF marker", eofEnd >= "%%EOF".length)
            val permittedWhitespace = byteArrayOf(0, 9, 10, 12, 13, 32)
            permittedWhitespace.forEach { whitespace ->
                fixture.writeBytes(valid + byteArrayOf(whitespace))
                val digest = sha256(fixture)
                repeat(2) {
                    assertEquals("PDF whitespace 0x${whitespace.toUByte().toString(16)} is permitted after EOF",
                        1, reader.inspect(fixture).pageCount)
                }
                assertEquals(digest, sha256(fixture))
            }

            // The prior reader examined only a 64 KiB tail. Keep each PDF whitespace byte
            // valid when the suffix spans beyond that implementation window.
            permittedWhitespace.forEach { whitespace ->
                val longSuffix = ByteArray(64 * 1024 + 1) { whitespace }
                fixture.writeBytes(valid + longSuffix)
                val digest = sha256(fixture)
                repeat(2) {
                    assertEquals("Long PDF whitespace 0x${whitespace.toUByte().toString(16)} is permitted after EOF",
                        1, reader.inspect(fixture).pageCount)
                }
                assertEquals(digest, sha256(fixture))
            }

            val forbiddenTail = ByteArray(64 * 1024 + 1) { permittedWhitespace.last() }
            fixture.writeBytes(valid.copyOf(eofEnd) + forbiddenTail + byteArrayOf('X'.code.toByte()))
            assertRejectedWithoutMutation(reader, fixture, IllegalArgumentException::class.java)
            assertEquals(sourceDigest, sha256(source))
        } finally {
            source.delete()
            fixture.delete()
        }
    }

    private fun lastIndexOf(bytes: ByteArray, needle: ByteArray): Int {
        for (start in bytes.size - needle.size downTo 0) {
            if (needle.indices.all { bytes[start + it] == needle[it] }) return start
        }
        return -1
    }

    @Test
    fun inspectsAndRendersPagesInSourceOrderAndReusesUsableTextLayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = File(context.cacheDir, "pdf-import-reader-fixture.pdf")
        val singlePage = File(context.cacheDir, "pdf-import-single-page.pdf")
        try {
            PDDocument().use { document ->
                listOf(PDRectangle.A4, PDRectangle.LETTER).forEachIndexed { index, bounds ->
                    val page = PDPage(bounds)
                    if (index == 1) page.rotation = 90
                    document.addPage(page)
                    if (index == 0) {
                        PDPageContentStream(document, page).use { stream ->
                            stream.beginText()
                            stream.setFont(PDType1Font.HELVETICA, 12f)
                            stream.newLineAtOffset(72f, bounds.height - 72f)
                            stream.showText("Invoice page ${index + 1} reference 84726190")
                            stream.endText()
                        }
                    }
                }
                document.documentInformation.title = "PDF import fixture"
                document.save(pdf)
            }
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle.A4))
                document.save(singlePage)
            }

            val reader = AndroidPdfImportReader(context)
            val multiHash = sha256(pdf)
            val inspection = reader.inspect(pdf)
            assertEquals(2, inspection.pageCount)
            assertEquals("PDF import fixture", inspection.title)
            assertEquals(listOf(0, 1), inspection.pages.map { it.pageIndex })
            assertEquals(PDRectangle.A4.width, inspection.pages[0].widthPoints, 0.01f)
            assertEquals(PDRectangle.A4.height, inspection.pages[0].heightPoints, 0.01f)
            assertEquals(0, inspection.pages[0].rotationDegrees)
            assertTrue(inspection.pages[0].hasReusableTextLayer)
            assertEquals(PDRectangle.LETTER.width, inspection.pages[1].widthPoints, 0.01f)
            assertEquals(PDRectangle.LETTER.height, inspection.pages[1].heightPoints, 0.01f)
            assertEquals(90, inspection.pages[1].rotationDegrees)
            assertTrue(!inspection.pages[1].hasReusableTextLayer)
            assertEquals(multiHash, sha256(pdf))

            val singleInspection = reader.inspect(singlePage)
            assertEquals(1, singleInspection.pageCount)
            assertEquals(listOf(0), singleInspection.pages.map { it.pageIndex })
            assertTrue(!singleInspection.pages.single().hasReusableTextLayer)

            val rendered = mutableListOf<Pair<Int, String?>>()
            reader.renderPages(
                source = pdf,
                expectedPageCount = 2,
                skipPageIndices = emptySet(),
                scratchDirectory = context.cacheDir,
                scheduler = app.docscan.core.schedule.HeavyWorkScheduler(),
            ) { index, file, text ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                try {
                    assertNotNull(bitmap)
                    assertTrue(maxOf(bitmap!!.width, bitmap.height) <= 3000)
                    rendered += index to text
                } finally {
                    bitmap?.recycle()
                }
            }
            assertEquals(listOf(0, 1), rendered.map { it.first })
            assertTrue(rendered[0].second?.contains("reference 84726190") == true)
            assertTrue(rendered[1].second.isNullOrBlank())
            assertEquals(multiHash, sha256(pdf))
        } finally {
            pdf.delete()
            singlePage.delete()
        }
    }

    @Test
    fun renderPagesKeepsOnlyOneBitmapAndPreservesRotatedAndOversizedPageGeometry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = threePagePdf(context, "pdf-import-render-lifetime-positive.pdf")
        val scratch = File(context.cacheDir, "pdf-import-render-lifetime-positive-scratch").apply { mkdirs() }
        val returnedBitmaps = mutableListOf<Bitmap>()
        var peakLiveBitmaps = 0
        val reader = AndroidPdfImportReader.withRasterizerForTest(
            context,
            PdfPageBitmapRenderer { renderer, pageIndex, scale ->
                assertTrue(
                    "Previous PDF page bitmap must be recycled before rasterizing the next page",
                    returnedBitmaps.all(Bitmap::isRecycled),
                )
                val bitmap = renderer.renderImage(pageIndex, scale)
                returnedBitmaps += bitmap
                peakLiveBitmaps = maxOf(peakLiveBitmaps, returnedBitmaps.count { !it.isRecycled })
                bitmap
            },
        )
        val sourceDigest = sha256(pdf)
        val observed = mutableListOf<Triple<Int, Int, Int>>()
        try {
            reader.renderPages(pdf, 3, emptySet(), scratch, app.docscan.core.schedule.HeavyWorkScheduler()) {
                    index, pngFile, _ ->
                assertTrue("Rendered PNG exists only while the page consumer runs", pngFile.isFile)
                assertTrue("Page bitmap is recycled before consumer publication", returnedBitmaps[index].isRecycled)
                val bitmap = BitmapFactory.decodeFile(pngFile.absolutePath)
                assertNotNull(bitmap)
                try {
                    observed += Triple(index, bitmap!!.width, bitmap.height)
                } finally {
                    bitmap?.recycle()
                }
            }
            assertEquals(listOf(0, 1, 2), observed.map { it.first })
            assertEquals(1, peakLiveBitmaps)
            assertTrue(returnedBitmaps.all(Bitmap::isRecycled))
            assertTrue("90-degree PDF page renders in landscape orientation", observed[1].second > observed[1].third)
            val expectedRotatedRatio = PDRectangle.LETTER.height / PDRectangle.LETTER.width
            val actualRotatedRatio = observed[1].second.toFloat() / observed[1].third
            assertTrue("Rotated page aspect ratio is preserved", kotlin.math.abs(actualRotatedRatio - expectedRotatedRatio) < 0.02f)
            assertTrue("Oversized source page stays within the existing 3000 px render envelope",
                observed.all { maxOf(it.second, it.third) <= 3000 })
            assertTrue("Oversized source page uses the existing cap", maxOf(observed[2].second, observed[2].third) >= 2999)
            assertTrue(scratch.listFiles().isNullOrEmpty())
            assertEquals(sourceDigest, sha256(pdf))
        } finally {
            pdf.delete()
            scratch.deleteRecursively()
        }
    }

    @Test
    fun rendererFailureAtEveryPageClosesResourcesDeletesScratchAndReleasesAdmission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = threePagePdf(context, "pdf-import-render-failure.pdf")
        val sourceDigest = sha256(pdf)
        try {
            for (failedIndex in 0..2) {
                val scratch = File(context.cacheDir, "pdf-import-render-failure-$failedIndex").apply { mkdirs() }
                val scheduler = app.docscan.core.schedule.HeavyWorkScheduler()
                val returnedBitmaps = mutableListOf<Bitmap>()
                val reader = AndroidPdfImportReader.withRasterizerForTest(
                    context,
                    PdfPageBitmapRenderer { renderer, pageIndex, scale ->
                        assertTrue("Prior rendered page bitmap was not recycled", returnedBitmaps.all(Bitmap::isRecycled))
                        if (pageIndex == failedIndex) throw IOException("injected renderer failure at page $pageIndex")
                        renderer.renderImage(pageIndex, scale).also(returnedBitmaps::add)
                    },
                )
                val callbacks = mutableListOf<Int>()
                try {
                    val failure = assertThrows(IOException::class.java) {
                        reader.renderPages(pdf, 3, emptySet(), scratch, scheduler) { index, pngFile, _ ->
                            assertTrue(pngFile.isFile)
                            callbacks += index
                        }
                    }
                    assertEquals("injected renderer failure at page $failedIndex", failure.message)
                    assertEquals((0 until failedIndex).toList(), callbacks)
                    assertTrue(returnedBitmaps.all(Bitmap::isRecycled))
                    assertTrue("No page temporary survives renderer failure", scratch.listFiles().isNullOrEmpty())
                    assertEquals(sourceDigest, sha256(pdf))
                    assertEquals(3, reader.inspect(pdf).pageCount)
                    assertEquals("admission released", scheduler.withAdmission(80L * 1024L * 1024L) { "admission released" })
                } finally {
                    scratch.deleteRecursively()
                }
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun consumerFailureAtEveryPageDeletesCurrentPngClosesResourcesAndReleasesAdmission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = threePagePdf(context, "pdf-import-consumer-failure.pdf")
        val sourceDigest = sha256(pdf)
        try {
            for (failedIndex in 0..2) {
                val scratch = File(context.cacheDir, "pdf-import-consumer-failure-$failedIndex").apply { mkdirs() }
                val scheduler = app.docscan.core.schedule.HeavyWorkScheduler()
                val returnedBitmaps = mutableListOf<Bitmap>()
                val reader = AndroidPdfImportReader.withRasterizerForTest(
                    context,
                    PdfPageBitmapRenderer { renderer, pageIndex, scale ->
                        assertTrue("Prior rendered page bitmap was not recycled", returnedBitmaps.all(Bitmap::isRecycled))
                        renderer.renderImage(pageIndex, scale).also(returnedBitmaps::add)
                    },
                )
                val callbacks = mutableListOf<Int>()
                var failedFile: File? = null
                try {
                    val failure = assertThrows(IOException::class.java) {
                        reader.renderPages(pdf, 3, emptySet(), scratch, scheduler) { index, pngFile, _ ->
                            assertTrue(pngFile.isFile)
                            assertTrue(returnedBitmaps[index].isRecycled)
                            callbacks += index
                            if (index == failedIndex) {
                                failedFile = pngFile
                                throw IOException("injected consumer failure at page $index")
                            }
                        }
                    }
                    assertEquals("injected consumer failure at page $failedIndex", failure.message)
                    assertEquals((0..failedIndex).toList(), callbacks)
                    assertTrue(returnedBitmaps.all(Bitmap::isRecycled))
                    assertTrue("No page temporary survives consumer failure", scratch.listFiles().isNullOrEmpty())
                    assertTrue("Callback's temporary PNG is deleted after it fails", failedFile?.exists() == false)
                    assertEquals(sourceDigest, sha256(pdf))
                    assertEquals(3, reader.inspect(pdf).pageCount)
                    assertEquals("admission released", scheduler.withAdmission(80L * 1024L * 1024L) { "admission released" })
                } finally {
                    scratch.deleteRecursively()
                }
            }
        } finally {
            pdf.delete()
        }
    }

    @Test
    fun pngScratchOutputFailureAtEveryIoPhaseIncludingCloseCleansResourcesReleasesAdmissionAndPreservesPrefix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pdf = threePagePdf(context, "pdf-import-png-output-failure.pdf")
        val sourceDigest = sha256(pdf)
        try {
            for (phase in listOf("open", "partial-write", "sync", "close")) {
                val scratch = File(context.cacheDir, "pdf-import-png-output-$phase").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val scheduler = app.docscan.core.schedule.HeavyWorkScheduler()
                val renderedBitmaps = mutableListOf<Bitmap>()
                val renderCalls = mutableListOf<Int>()
                val recycleCalls = java.util.IdentityHashMap<Bitmap, Int>()
                val callbacks = mutableListOf<Int>()
                var outputOpenCount = 0
                var failedOutput: File? = null
                var partialBytesWritten = false
                var syncFailureInjected = false
                var delegateCloseCompleted = false
                var closeFailureInjected = false
                val outputFactory = PdfPagePngOutputFactory { output ->
                    val pageIndex = outputOpenCount++
                    if (pageIndex == 0) {
                        FilePdfPagePngOutputFactory.open(output)
                    } else {
                        assertEquals("Failure must be injected while writing page 2", 1, pageIndex)
                        failedOutput = output
                        when (phase) {
                            "open" -> throw IOException("injected PNG output open failure")
                            "partial-write" -> object : PdfPagePngOutput {
                                override fun compress(bitmap: Bitmap): Boolean {
                                    FileOutputStream(output).use { stream ->
                                        stream.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
                                        stream.fd.sync()
                                    }
                                    partialBytesWritten = output.length() > 0L
                                    throw IOException("injected partial PNG write failure")
                                }

                                override fun sync() = Unit
                                override fun close() = Unit
                            }
                            "sync" -> {
                                val delegate = FilePdfPagePngOutputFactory.open(output)
                                object : PdfPagePngOutput {
                                    override fun compress(bitmap: Bitmap): Boolean = delegate.compress(bitmap)
                                    override fun sync() {
                                        syncFailureInjected = true
                                        throw IOException("injected PNG sync failure")
                                    }

                                    override fun close() = delegate.close()
                                }
                            }
                            "close" -> {
                                val delegate = FilePdfPagePngOutputFactory.open(output)
                                object : PdfPagePngOutput {
                                    override fun compress(bitmap: Bitmap): Boolean = delegate.compress(bitmap)
                                    override fun sync() = delegate.sync()
                                    override fun close() {
                                        delegate.close()
                                        delegateCloseCompleted = true
                                        closeFailureInjected = true
                                        throw IOException("injected PNG close failure")
                                    }
                                }
                            }
                            else -> error("Unexpected output failure phase: $phase")
                        }
                    }
                }
                val faultingReader = AndroidPdfImportReader.withPngOutputForTest(
                    context,
                    PdfPageBitmapRenderer { renderer, pageIndex, scale ->
                        renderCalls += pageIndex
                        renderer.renderImage(pageIndex, scale).also(renderedBitmaps::add)
                    },
                    outputFactory,
                ) { bitmap ->
                    recycleCalls[bitmap] = (recycleCalls[bitmap] ?: 0) + 1
                    bitmap.recycle()
                }
                var pageOneBytes = 0L
                try {
                    val failure = assertThrows(IOException::class.java) {
                        faultingReader.renderPages(pdf, 3, emptySet(), scratch, scheduler) { index, pngFile, _ ->
                            assertTrue("Successful page scratch PNG exists during callback", pngFile.isFile)
                            assertTrue("Rendered bitmap is recycled before consumer callback", renderedBitmaps[index].isRecycled)
                            callbacks += index
                            if (index == 0) pageOneBytes = pngFile.length()
                        }
                    }
                    assertEquals(
                        when (phase) {
                            "open" -> "injected PNG output open failure"
                            "partial-write" -> "injected partial PNG write failure"
                            "sync" -> "injected PNG sync failure"
                            else -> "injected PNG close failure"
                        },
                        failure.message,
                    )
                    assertEquals("Only the completed page-1 prefix reaches the consumer", listOf(0), callbacks)
                    assertEquals("No page after the failed page is rasterized", listOf(0, 1), renderCalls)
                    assertEquals("Exactly two page outputs are opened", 2, outputOpenCount)
                    assertTrue("Page 1 produced complete PNG bytes", pageOneBytes > 8L)
                    assertTrue("Each rendered bitmap is recycled", renderedBitmaps.all(Bitmap::isRecycled))
                    assertTrue("Each rendered bitmap is recycled exactly once", renderedBitmaps.all { recycleCalls[it] == 1 })
                    assertTrue("Failed-page scratch output is removed", failedOutput?.exists() == false)
                    assertTrue("No scratch output survives the I/O failure", scratch.listFiles().isNullOrEmpty())
                    if (phase == "partial-write") assertTrue("Failure followed persisted partial bytes", partialBytesWritten)
                    if (phase == "sync") assertTrue("Injected failure occurred at sync", syncFailureInjected)
                    if (phase == "close") {
                        assertTrue("Underlying output was closed before the injected failure", delegateCloseCompleted)
                        assertTrue("Injected failure occurred at close", closeFailureInjected)
                    }
                    assertEquals("Source PDF bytes remain unchanged", sourceDigest, sha256(pdf))
                    assertEquals("Source PDF can be reopened after output failure", 3, faultingReader.inspect(pdf).pageCount)
                    assertEquals(
                        "Scheduler admission is released after output failure",
                        "admission released",
                        scheduler.withAdmission(80L * 1024L * 1024L) { "admission released" },
                    )
                } finally {
                    scratch.deleteRecursively()
                }
            }
        } finally {
            pdf.delete()
        }
    }

    private fun threePagePdf(context: android.content.Context, name: String): File {
        val pdf = File(context.cacheDir, name)
        PDDocument().use { document ->
            document.addPage(PDPage(PDRectangle.A4))
            document.addPage(PDPage(PDRectangle.LETTER).apply { rotation = 90 })
            document.addPage(PDPage(PDRectangle(30_000f, 21_000f)))
            document.save(pdf)
        }
        return pdf
    }

    private fun assertRejectedWithoutMutation(
        reader: AndroidPdfImportReader,
        source: File,
        expected: Class<out Throwable>,
    ) {
        val digestBefore = sha256(source)
        assertThrows(expected) { reader.inspect(source) }
        assertEquals(digestBefore, sha256(source))
        // A second open after each parser failure detects retained parser state/handles.
        assertThrows(expected) { reader.inspect(source) }
        assertEquals(digestBefore, sha256(source))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
