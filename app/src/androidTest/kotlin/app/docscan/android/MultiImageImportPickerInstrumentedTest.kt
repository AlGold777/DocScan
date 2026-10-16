package app.docscan.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.docscan.core.model.PageId
import app.docscan.core.store.StoredPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultiImageImportPickerInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun realPdfImportCanBeSavedAsVerifiedSafPackageInSameActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanImportSave-$suffix"
        val displayName = "ordered-source-$suffix.pdf"
        val databaseName = "import-save-$suffix.db"
        val artifactDirectory = "import-save-artifacts-$suffix"
        val candidateDirectory = "import-save-candidates-$suffix"
        val basename = "docscan-import-save-$suffix"
        val expectedOutputNames = setOf("$basename.pdf", "$basename — 01.jpg", "$basename — 02.jpg")
        val permissionsBefore = persistedPermissionUris(context)
        val source = createFixturePdf(context, fixtureFolder, displayName, EXPECTED_PDF_PAGE_COLORS.take(2))
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorOverride = app.productStorageOverrideForTest
        val priorProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var namespaceWasAbsent = false
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        val activityIdentity = java.util.concurrent.atomic.AtomicInteger()

        try {
            assertTrue("The isolated workspace must start empty", storage.workspaceProjection().pages.isEmpty())
            assertSourceBytesUnchanged(context, source)
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity -> activityIdentity.set(System.identityHashCode(activity)) }
            val device = UiDevice.getInstance(instrumentation)

            scenario.onActivity { activity ->
                val chooseDestination = activity.window.decorView.findViewWithTag<View>("docscan-destination-button")
                assertNotNull("ProductActivity destination chooser must be visible", chooseDestination)
                assertTrue("Destination chooser must be enabled", requireNotNull(chooseDestination).isEnabled)
                chooseDestination.performClick()
            }
            selectDocumentsDestination(device)
            awaitActivityResumed(scenario)
            val grant = awaitValue("real SAF destination grant") { storage.destinationGrant() }
            val selectedTree = Uri.parse(grant.treeUri)
            tree = selectedTree
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(selectedTree))
            scenario.onActivity { activity ->
                assertEquals("The same live ProductActivity returns from destination selection",
                    activityIdentity.get(), System.identityHashCode(activity))
            }

            val runtimeReady = SafRuntimeDestinationCheck(context, storage).ensureReady(selectedTree.toString())
            val qualificationId = runtimeReady.qualificationId
            assertEquals("Runtime readiness is tied to this exact destination", selectedTree.toString(),
                org.json.JSONObject(runtimeReady.qualifiedCapabilitiesPayload).getString("treeUri"))
            assertTrue("No pre-existing output uses this unique basename",
                providerChildren(context, selectedTree).none { it.second in expectedOutputNames })
            namespaceWasAbsent = true

            clickImport(scenario)
            chooseFixtureDocuments(device, fixtureFolder, listOf(displayName))
            awaitActivityResumed(scenario)
            awaitCondition("Both source PDF pages must import into the isolated workspace") {
                storage.workspaceProjection().pages.size == 2
            }
            val importedPages = storage.workspaceProjection().pages
            assertTrue("Real picker import selects both new Pages", importedPages.all { it.selected })
            val pageRecords = pageRecords(context.getDatabasePath(databaseName), importedPages)
            assertEquals(listOf(0L, 1L), pageRecords.map { it.sourcePageIndex })
            assertEquals("PDF pages share one source session", 1, pageRecords.map { it.importSessionId }.distinct().size)
            assertEquals(listOf("application/pdf:$displayName"), pageRecords.map { it.sourceProvenance }.distinct())
            val importSession = importSessionRecords(context.getDatabasePath(databaseName)).single()
            assertEquals("COMPLETED", importSession.state)
            assertEquals(displayName, importSession.displayName)
            assertEquals(sha256(source.bytes), privateSourceSha256(
                context.getDatabasePath(databaseName), artifactDirectory, importSession.sourceSnapshotArtifactId,
            ))
            importedPages.zip(EXPECTED_PDF_PAGE_COLORS.take(2)).forEachIndexed { index, (page, expectedColor) ->
                val image = BitmapFactory.decodeFile(storage.artifactPath(page.artifactRelativePath).toFile().absolutePath)
                    ?: throw AssertionError("Imported Page $index must decode")
                try { assertColorNear("Imported Page $index", image.getPixel(image.width / 2, image.height / 2), expectedColor) }
                finally { image.recycle() }
            }
            assertSourceBytesUnchanged(context, source)
            scenario.onActivity { activity ->
                assertEquals("The same live ProductActivity must continue through both system pickers",
                    activityIdentity.get(), System.identityHashCode(activity))
            }

            awaitCondition("Imported selected Pages must enable Activity Save") {
                var enabled = false
                scenario.onActivity { activity ->
                    enabled = activity.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button")).performClick()
            }
            assertTrue("Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MILLIS))
            device.findObject(By.clazz("android.widget.EditText")).text = basename
            val saveAction = By.text("Save selected pages")
            assertTrue("Save confirmation is available", device.wait(Until.hasObject(saveAction), TIMEOUT_MILLIS))
            device.findObject(saveAction).click()

            val recent = awaitValue("Complete SAF package is confirmed") { storage.recentDocuments().singleOrNull() }
            val exportPackage = requireNotNull(storage.externalExportPackage(recent.exportArtifactId))
            assertEquals("SUCCEEDED", exportPackage.state)
            assertEquals("The exported package must reference the actual runtime readiness record",
                qualificationId, exportPackage.providerQualificationId)
            val packageOutputs = exportPackage.outputs.sortedBy { it.ordinal }
            assertEquals(3, packageOutputs.size)
            assertEquals(listOf(0L, 1L, 2L), packageOutputs.map { it.ordinal })
            assertEquals(listOf("$basename.pdf", "$basename — 01.jpg", "$basename — 02.jpg"),
                packageOutputs.map { it.displayName })
            val committedOutputs = storage.committedExportOutputs(recent.exportArtifactId).associateBy { it.artifactId }
            val attempts = storage.exportAttempts(recent.exportArtifactId).associateBy { it.outputArtifactId }
            assertEquals(3, committedOutputs.size)
            assertEquals(3, attempts.size)
            assertTrue("Every package output is confirmed", attempts.values.all { it.state == "SUCCEEDED" })
            val providerBytesByName = packageOutputs.associate { packageOutput ->
                val attempt = requireNotNull(attempts[packageOutput.outputArtifactId])
                val output = requireNotNull(committedOutputs[packageOutput.outputArtifactId])
                val bytes = requireNotNull(context.contentResolver.openInputStream(Uri.parse(attempt.externalIdentity))).use { it.readBytes() }
                assertEquals("Read-back size matches private committed artifact", output.sizeBytes, bytes.size.toLong())
                assertEquals("Read-back checksum matches private committed artifact", output.sha256, sha256(bytes))
                packageOutput.displayName to bytes
            }
            val pdfBytes = requireNotNull(providerBytesByName[packageOutputs.first().displayName])
            PDFBoxResourceLoader.init(context)
            PDDocument.load(ByteArrayInputStream(pdfBytes)).use { document ->
                assertEquals("Saved PDF retains the two imported source pages", 2, document.numberOfPages)
                val renderer = PDFRenderer(document)
                EXPECTED_PDF_PAGE_COLORS.take(2).forEachIndexed { index, expectedColor ->
                    val image = renderer.renderImage(index, 0.25f)
                    try { assertColorNear("Saved PDF page $index", image.getPixel(image.width / 2, image.height / 2), expectedColor) }
                    finally { image.recycle() }
                }
            }
            packageOutputs.drop(1).zip(EXPECTED_PDF_PAGE_COLORS.take(2)).forEach { (packageOutput, expectedColor) ->
                val bytes = requireNotNull(providerBytesByName[packageOutput.displayName])
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw AssertionError("Saved companion JPG ${packageOutput.displayName} must decode")
                try { assertColorNear("Saved JPG ${packageOutput.displayName}", image.getPixel(image.width / 2, image.height / 2), expectedColor) }
                finally { image.recycle() }
            }
            assertTrue("Workspace Pages are consumed only after all provider outputs succeed",
                storage.workspaceProjection().pages.isEmpty())
            assertSourceBytesUnchanged(context, source)
            scenario.onActivity { activity ->
                assertEquals("Same Activity remains after the completed save", activityIdentity.get(), System.identityHashCode(activity))
            }
            android.util.Log.i(TAG,
                "IMPORT_TO_SAF_SAVE_PASS fixture=$suffix pages=2 outputs=${packageOutputs.joinToString(",") { it.displayName }} " +
                    "sourceSha256=${sha256(source.bytes)} outputHashes=${committedOutputs.values.joinToString(",") { it.sha256 }}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            fun retainCleanupFailure(failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
            }
            runCatching { scenario?.close() }.onFailure(::retainCleanupFailure)
            app.productStorageProviderForTest = priorProvider
            app.productStorageOverrideForTest = priorOverride
            runCatching { storage.close() }.onFailure(::retainCleanupFailure)
            runCatching { cleanupFixtureDocuments(context, fixtureFolder, listOf(source)) }.onFailure(::retainCleanupFailure)
            tree?.let { selectedTree ->
                if (namespaceWasAbsent) {
                    runCatching {
                        providerChildren(context, selectedTree).filter { it.second in expectedOutputNames }.forEach { (uri, _) ->
                            check(DocumentsContract.deleteDocument(context.contentResolver, uri)) { "Could not remove test output $uri" }
                        }
                    }.onFailure(::retainCleanupFailure)
                    runCatching {
                        check(providerChildren(context, selectedTree).none { it.second in expectedOutputNames }) {
                            "Test outputs remain in the provider after cleanup"
                        }
                    }.onFailure(::retainCleanupFailure)
                }
                if (selectedTree.toString() !in permissionsBefore) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure(::retainCleanupFailure)
                }
            }
            runCatching { check(File(context.noBackupFilesDir, artifactDirectory).deleteRecursively() || !File(context.noBackupFilesDir, artifactDirectory).exists()) }
                .onFailure(::retainCleanupFailure)
            runCatching { check(File(context.noBackupFilesDir, candidateDirectory).deleteRecursively() || !File(context.noBackupFilesDir, candidateDirectory).exists()) }
                .onFailure(::retainCleanupFailure)
            runCatching { check(context.deleteDatabase(databaseName)) }.onFailure(::retainCleanupFailure)
            runCatching { check(listFixtureDocuments(context, fixtureFolder).isEmpty()) }.onFailure(::retainCleanupFailure)
            runCatching { assertEquals("Only grants present before the test may remain", permissionsBefore, persistedPermissionUris(context)) }
                .onFailure(::retainCleanupFailure)
            cleanupFailure?.let { cleanup ->
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup)
                else throw AssertionError("Import-to-SAF-save cleanup failed", cleanup)
            }
        }
    }

    @Test
    fun realMixedImageAndPdfImportCanBeSavedAndOpenedFromRecent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMixedImportSave-$suffix"
        val imageName = "01-invoice-$suffix.jpg"
        val pdfName = "02-pages-$suffix.pdf"
        val databaseName = "mixed-import-save-$suffix.db"
        val artifactDirectory = "mixed-import-save-artifacts-$suffix"
        val candidateDirectory = "mixed-import-save-candidates-$suffix"
        val basename = "docscan-mixed-import-$suffix"
        val expectedOutputNames = setOf(
            "$basename.pdf",
            "$basename — 01.jpg",
            "$basename — 02.jpg",
            "$basename — 03.jpg",
        )
        val permissionsBefore = persistedPermissionUris(context)
        val pdfLabels = listOf("SOURCE PAGE ONE 1142", "SOURCE PAGE TWO 8472")
        val pdfColors = listOf(0xe53935, 0x43a047)
        val sources = mutableListOf<SourceDocument>()
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val previousOverride = app.productStorageOverrideForTest
        val previousProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var outputNamespaceWasAbsent = false
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        val activityIdentity = java.util.concurrent.atomic.AtomicInteger()

        try {
            val image = publishFixtureDocument(
                context,
                fixtureFolder,
                imageName,
                "image/jpeg",
                ocrFixtureImageBytes(),
            )
            sources += image
            val pdf = createFixturePdf(
                context,
                fixtureFolder,
                pdfName,
                pageColors = pdfColors,
                pageTexts = pdfLabels,
            )
            sources += pdf
            assertTrue("The isolated mixed-source workspace starts empty", storage.workspaceProjection().pages.isEmpty())
            assertEquals("The folder contains one image and one PDF", 2, listFixtureDocuments(context, fixtureFolder).size)
            sources.forEach { assertSourceBytesUnchanged(context, it) }
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity -> activityIdentity.set(System.identityHashCode(activity)) }
            val device = UiDevice.getInstance(instrumentation)

            scenario.onActivity { activity ->
                val chooseDestination = activity.window.decorView.findViewWithTag<View>("docscan-destination-button")
                assertNotNull("ProductActivity destination chooser must be visible", chooseDestination)
                assertTrue("Destination chooser must be enabled", requireNotNull(chooseDestination).isEnabled)
                chooseDestination.performClick()
            }
            selectDocumentsDestination(device)
            awaitActivityResumed(scenario)
            val grant = awaitValue("mixed-source macro SAF destination grant") { storage.destinationGrant() }
            val selectedTree = Uri.parse(grant.treeUri)
            tree = selectedTree
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(selectedTree))
            val runtimeReady = SafRuntimeDestinationCheck(context, storage).ensureReady(selectedTree.toString())
            val qualificationId = runtimeReady.qualificationId
            assertEquals("Runtime readiness is tied to this exact destination", selectedTree.toString(),
                org.json.JSONObject(runtimeReady.qualifiedCapabilitiesPayload).getString("treeUri"))
            assertTrue("This unique output namespace must start absent",
                providerChildren(context, selectedTree).none { it.second in expectedOutputNames })
            outputNamespaceWasAbsent = true
            scenario.onActivity { activity ->
                assertEquals("SAF picker returns to the same ProductActivity", activityIdentity.get(), System.identityHashCode(activity))
            }

            clickImport(scenario)
            chooseFixtureDocuments(device, fixtureFolder, sources.map { it.displayName })
            awaitActivityResumed(scenario)
            awaitMixedImportReady(
                context = context,
                storage = storage,
                databaseFile = context.getDatabasePath(databaseName),
                scenario = scenario,
                device = device,
                timeoutMs = 180_000L,
            )

            val importedPages = storage.workspaceProjection().pages
            assertEquals("The image and two PDF pages must import and bind current text", 3, importedPages.size)
            assertTrue("All imported Pages must have nonblank current text", importedPages.all { !it.recognizedText.isNullOrBlank() })
            assertEquals("Every mixed-source Page is selected after successful preparation",
                listOf(true, true, true), importedPages.map { it.selected })
            assertEquals("Mixed-source Page order follows the DocumentsUI selection and PDF order",
                listOf(0L, 1L, 2L), importedPages.map { it.orderIndex })
            val records = pageRecords(context.getDatabasePath(databaseName), importedPages)
            val sessions = importSessionRecords(context.getDatabasePath(databaseName))
            assertEquals("One JPEG Page and two PDF Pages are committed", 3, records.size)
            assertEquals("JPEG then PDF are distinct completed ImportSessions", 2, sessions.size)
            assertTrue("Both mixed-source ImportSessions are complete", sessions.all { it.state == "COMPLETED" })
            assertEquals("ImportSession order matches source selection", sources.map { it.displayName }, sessions.map { it.displayName })
            assertEquals(
                "Each Page preserves source MIME and order",
                listOf("image/jpeg:$imageName", "application/pdf:$pdfName", "application/pdf:$pdfName"),
                records.map { it.sourceProvenance },
            )
            val sessionByName = sessions.associateBy { it.displayName }
            val imageSession = requireNotNull(sessionByName[imageName])
            val pdfSession = requireNotNull(sessionByName[pdfName])
            assertEquals("The source PDF retains its private snapshot checksum", sha256(pdf.bytes), pdfSession.sourceSnapshotSha256)
            assertEquals("Private PDF snapshot bytes match the picker source", sha256(pdf.bytes),
                privateSourceSha256(context.getDatabasePath(databaseName), artifactDirectory, pdfSession.sourceSnapshotArtifactId))
            assertEquals("Page-to-session membership follows JPEG then PDF page order",
                listOf(imageSession.importSessionId, pdfSession.importSessionId, pdfSession.importSessionId),
                records.map { it.importSessionId })
            assertEquals("JPEG has one image-source page index and PDF retains its two source page indexes",
                listOf(0L, 0L, 1L), records.map { it.sourcePageIndex })

            val normalization: (String) -> String = { value -> value.lowercase().filter { it.isLetterOrDigit() } }
            val recognitionText = importedPages.map { requireNotNull(it.recognizedText) }
            assertTrue("Imported image OCR contains the fixture's expected invoice token: ${recognitionText[0]}",
                normalization(recognitionText[0]).contains("invoice8472"))
            assertTrue("First imported PDF page retains its text-layer label: ${recognitionText[1]}",
                normalization(recognitionText[1]).contains(normalization(pdfLabels[0])))
            assertTrue("Second imported PDF page retains its text-layer label: ${recognitionText[2]}",
                normalization(recognitionText[2]).contains(normalization(pdfLabels[1])))

            val database = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null,
                SQLiteDatabase.OPEN_READONLY)
            try {
                importedPages.forEach { page ->
                    database.rawQuery(
                        "SELECT r.image_version_id FROM Page p JOIN Recognition r ON r.recognition_id = p.current_recognition_id WHERE p.page_id = ?",
                        arrayOf(page.pageId.value),
                    ).use { cursor ->
                        assertTrue("Page ${page.pageId.value} has a current Recognition row", cursor.moveToFirst())
                        assertEquals("Recognition belongs to current ImageVersion for Page ${page.pageId.value}",
                            page.imageVersionId.value, cursor.getString(0))
                    }
                }
            } finally {
                database.close()
            }

            importedPages.zip(sources.flatMap { source -> if (source == pdf) listOf(pdf, pdf) else listOf(image) })
                .forEachIndexed { index, (page, source) ->
                    val pageFile = storage.artifactPath(page.artifactRelativePath).toFile()
                    if (source == image) {
                        assertEquals("Imported JPEG retains its exact source byte count", image.bytes.size.toLong(), pageFile.length())
                        assertEquals("Imported JPEG artifact matches exact source bytes", sha256(image.bytes), sha256(pageFile.readBytes()))
                    } else {
                        val expectedColor = pdfColors[index - 1]
                        val bitmap = BitmapFactory.decodeFile(pageFile.absolutePath)
                            ?: throw AssertionError("Imported PDF page $index must decode")
                        try {
                            assertColorNear("Imported PDF page $index visual marker", bitmap.getPixel(bitmap.width / 2, bitmap.height / 2), expectedColor)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            sources.forEach { assertSourceBytesUnchanged(context, it) }
            assertEquals(
                "Import source selection adds no grants beyond the selected Save destination",
                permissionsBefore + selectedTree.toString(),
                persistedPermissionUris(context),
            )
            scenario.onActivity { activity ->
                assertEquals("Both real picker transitions return to the same ProductActivity",
                    activityIdentity.get(), System.identityHashCode(activity))
                val save = requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button"))
                assertTrue("Three successfully prepared imported Pages enable Save", save.isEnabled)
                save.performClick()
            }
            assertTrue("Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MILLIS))
            device.findObject(By.clazz("android.widget.EditText")).text = basename
            val saveAction = By.text("Save selected pages")
            assertTrue("Save confirmation is available", device.wait(Until.hasObject(saveAction), TIMEOUT_MILLIS))
            device.findObject(saveAction).click()

            val recent = awaitValue("Complete mixed-source SAF package is confirmed") {
                storage.recentDocuments().singleOrNull()?.takeIf { it.pageCount == 3L }
            }
            val exportPackage = requireNotNull(storage.externalExportPackage(recent.exportArtifactId))
            assertEquals("Complete output package is confirmed", "SUCCEEDED", exportPackage.state)
            assertEquals("Output records the actual runtime readiness tuple", qualificationId,
                exportPackage.providerQualificationId)
            val packageOutputs = exportPackage.outputs.sortedBy { it.ordinal }
            assertEquals("One three-page PDF and three companion JPGs are emitted", 4, packageOutputs.size)
            assertEquals(listOf(0L, 1L, 2L, 3L), packageOutputs.map { it.ordinal })
            assertEquals(listOf("$basename.pdf", "$basename — 01.jpg", "$basename — 02.jpg", "$basename — 03.jpg"),
                packageOutputs.map { it.displayName })
            val committedOutputs = storage.committedExportOutputs(recent.exportArtifactId).associateBy { it.artifactId }
            val attempts = storage.exportAttempts(recent.exportArtifactId).associateBy { it.outputArtifactId }
            assertEquals("Every PDF/JPG output has a committed private artifact", 4, committedOutputs.size)
            assertEquals("Every PDF/JPG output has a completed provider attempt", 4, attempts.size)
            assertTrue("All four provider attempts succeeded", attempts.values.all { it.state == "SUCCEEDED" })
            val providerBytes = packageOutputs.associate { packageOutput ->
                val output = requireNotNull(committedOutputs[packageOutput.outputArtifactId])
                val attempt = requireNotNull(attempts[packageOutput.outputArtifactId])
                val bytes = requireNotNull(context.contentResolver.openInputStream(Uri.parse(attempt.externalIdentity))).use { it.readBytes() }
                assertEquals("Provider read-back byte count matches private output ${packageOutput.displayName}",
                    output.sizeBytes, bytes.size.toLong())
                assertEquals("Provider read-back SHA matches private output ${packageOutput.displayName}",
                    output.sha256, sha256(bytes))
                assertEquals("Private output bytes match committed SHA ${packageOutput.displayName}", output.sha256,
                    sha256(storage.artifactPath(output.relativePath).toFile().readBytes()))
                packageOutput.displayName to bytes
            }
            val pdfBytes = requireNotNull(providerBytes[packageOutputs.first().displayName])
            val savedPdfSha = sha256(pdfBytes)
            PDFBoxResourceLoader.init(context)
            PDDocument.load(ByteArrayInputStream(pdfBytes)).use { document ->
                assertEquals("Saved PDF follows JPEG then both PDF Pages", 3, document.numberOfPages)
                val outputText = normalization(PDFTextStripper().getText(document))
                listOf("Invoice 8472", pdfLabels[0], pdfLabels[1]).forEach { expected ->
                    assertTrue("Saved PDF retains searchable imported text '$expected': $outputText",
                        outputText.contains(normalization(expected)))
                }
                val renderer = PDFRenderer(document)
                importedPages.forEachIndexed { index, sourcePage ->
                    val raster = renderer.renderImage(index, 0.25f)
                    try {
                        assertSampledPdfPageMatches("Saved PDF page ${index + 1} follows imported Page ${index + 1}",
                            storage.artifactPath(sourcePage.artifactRelativePath).toFile(), raster)
                    } finally {
                        raster.recycle()
                    }
                }
            }
            packageOutputs.drop(1).zip(importedPages).forEach { (packageOutput, sourcePage) ->
                val bytes = requireNotNull(providerBytes[packageOutput.displayName])
                val imageBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw AssertionError("Saved companion JPG ${packageOutput.displayName} must decode")
                try {
                    assertSampledImageMatches("${packageOutput.displayName} follows imported Page ${sourcePage.orderIndex + 1}",
                        storage.artifactPath(sourcePage.artifactRelativePath).toFile(), imageBitmap)
                } finally {
                    imageBitmap.recycle()
                }
            }
            assertTrue("Workspace Pages are consumed only after every provider output succeeds",
                storage.workspaceProjection().pages.isEmpty())
            assertEquals("A successful mixed Save creates exactly one RecentDocument", 1, storage.recentDocuments().size)

            awaitCondition("ProductActivity exposes the confirmed Recent action") {
                var visible = false
                scenario.onActivity { activity ->
                    val button = activity.window.decorView.findViewWithTag<View>("docscan-recent-button")
                    visible = button?.visibility == View.VISIBLE && button.isEnabled
                }
                visible
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-recent-button")).performClick()
            }
            val recentRow = By.text("${recent.displayName} · ${recent.pageCount}")
            assertTrue("Confirmed three-page Recent document appears", device.wait(Until.hasObject(recentRow), TIMEOUT_MILLIS))
            device.findObject(recentRow).click()
            val previewAction = By.text(context.getString(R.string.recent_action_preview))
            assertTrue("Recent actions expose Preview", device.wait(Until.hasObject(previewAction), TIMEOUT_MILLIS))
            device.findObject(previewAction).click()
            for (pageNumber in 1..3) {
                val pageLabel = By.text(context.getString(R.string.saved_pdf_page, pageNumber, 3))
                assertTrue("In-app preview opens saved PDF page $pageNumber of 3", device.wait(Until.hasObject(pageLabel), 10_000L))
                if (pageNumber < 3) {
                    val nextSelector = By.text(
                        Pattern.compile("(?i)^${Pattern.quote(context.getString(R.string.viewer_next))}$"),
                    )
                    val next = device.wait(Until.findObject(nextSelector), TIMEOUT_MILLIS)
                        ?: throw AssertionError(
                            "Preview Next action must exist for page $pageNumber; " +
                                activeUiHierarchyDiagnostic(context, device),
                        )
                    awaitCondition("Preview page $pageNumber must enable Next") { next.isEnabled }
                    next.click()
                }
            }
            assertEquals("Preview does not change the Recent index", recent, storage.recentDocuments().single())
            assertEquals("Preview does not change confirmed PDF bytes", savedPdfSha,
                sha256(storage.recentSavedPdfFile(recent).readBytes()))
            device.pressBack()
            awaitActivityResumed(scenario)
            scenario.onActivity { activity ->
                assertEquals("Preview returns to the same ProductActivity",
                    activityIdentity.get(), System.identityHashCode(activity))
            }
            android.util.Log.i(TAG,
                "MIXED_IMAGE_PDF_TO_SAF_SAVE_OPEN_PASS fixture=$suffix sources=${sources.joinToString(",") { it.displayName }} " +
                    "sourceSha256=${sources.joinToString(",") { sha256(it.bytes) }} pages=${importedPages.size} " +
                    "recognitionText=${recognitionText.joinToString("|")} outputs=${packageOutputs.size} " +
                    "outputSha256=${committedOutputs.values.joinToString(",") { it.sha256 }} " +
                    "previewPages=3 providerQualification=TEST_FIXTURE_ONLY_NOT_QUALIFICATION")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            fun retainCleanupFailure(failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
            }
            runCatching { scenario?.close() }.onFailure(::retainCleanupFailure)
            app.productStorageProviderForTest = previousProvider
            app.productStorageOverrideForTest = previousOverride
            runCatching { storage.close() }.onFailure(::retainCleanupFailure)
            runCatching { cleanupFixtureDocuments(context, fixtureFolder, sources) }.onFailure(::retainCleanupFailure)
            tree?.let { selectedTree ->
                if (outputNamespaceWasAbsent) {
                    runCatching {
                        providerChildren(context, selectedTree).filter { it.second in expectedOutputNames }.forEach { (uri, _) ->
                            check(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                                "Could not remove test-owned output $uri"
                            }
                        }
                    }.onFailure(::retainCleanupFailure)
                    runCatching {
                        check(providerChildren(context, selectedTree).none { it.second in expectedOutputNames }) {
                            "Test-owned outputs remain in the provider after cleanup"
                        }
                    }.onFailure(::retainCleanupFailure)
                }
                if (selectedTree.toString() !in permissionsBefore) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure(::retainCleanupFailure)
                }
            }
            runCatching {
                check(File(context.noBackupFilesDir, artifactDirectory).deleteRecursively() ||
                    !File(context.noBackupFilesDir, artifactDirectory).exists())
            }.onFailure(::retainCleanupFailure)
            runCatching {
                check(File(context.noBackupFilesDir, candidateDirectory).deleteRecursively() ||
                    !File(context.noBackupFilesDir, candidateDirectory).exists())
            }.onFailure(::retainCleanupFailure)
            runCatching { check(context.deleteDatabase(databaseName)) }.onFailure(::retainCleanupFailure)
            runCatching { check(listFixtureDocuments(context, fixtureFolder).isEmpty()) }.onFailure(::retainCleanupFailure)
            runCatching {
                assertEquals("Only pre-test grants may remain", permissionsBefore, persistedPermissionUris(context))
            }.onFailure(::retainCleanupFailure)
            cleanupFailure?.let { cleanup ->
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup)
                else throw AssertionError("Mixed image/PDF import-to-Save cleanup failed", cleanup)
            }
        }
    }

    @Test
    fun realCameraCaptureOcrCanBeSavedAsVerifiedSafPackageInSameActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "camera-save-$suffix.db"
        val artifactDirectory = "camera-save-artifacts-$suffix"
        val candidateDirectory = "camera-save-candidates-$suffix"
        val basename = "docscan-camera-save-$suffix"
        val expectedOutputNames = setOf("$basename.pdf", "$basename — 01.jpg", "$basename — 02.jpg")
        val permissionsBefore = persistedPermissionUris(context)
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val previousOverride = app.productStorageOverrideForTest
        val previousProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var outputNamespaceWasAbsent = false
        var defaultFolderCreated = false
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        val activityIdentity = java.util.concurrent.atomic.AtomicInteger()

        try {
            defaultFolderCreated = SafPickerTestActions.createDefaultFolderFixture()
            assertTrue("The isolated camera workspace starts empty", storage.workspaceProjection().pages.isEmpty())
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity -> activityIdentity.set(System.identityHashCode(activity)) }
            val device = UiDevice.getInstance(instrumentation)
            scenario.onActivity { activity ->
                val save = requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button"))
                assertTrue("Save is disabled before there are captured Pages", !save.isEnabled)
                requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-destination-button")).performClick()
            }
            SafPickerTestActions.chooseTreeDirectory(device, requireNotNull(scenario), listOf("Docscan"))
            awaitActivityResumed(scenario)
            val grant = awaitValue("camera macro SAF destination grant") { storage.destinationGrant() }
            val selectedTree = Uri.parse(grant.treeUri)
            tree = selectedTree
            assertEquals("primary:Docscan", DocumentsContract.getTreeDocumentId(selectedTree))
            val runtimeReady = SafRuntimeDestinationCheck(context, storage).ensureReady(selectedTree.toString())
            val qualificationId = runtimeReady.qualificationId
            assertEquals("Runtime readiness is tied to this exact destination", selectedTree.toString(),
                org.json.JSONObject(runtimeReady.qualifiedCapabilitiesPayload).getString("treeUri"))
            assertTrue("This unique output namespace must start absent",
                providerChildren(context, selectedTree).none { it.second in expectedOutputNames })
            outputNamespaceWasAbsent = true
            scenario.onActivity { activity ->
                assertEquals("SAF picker returns to the same ProductActivity", activityIdentity.get(), System.identityHashCode(activity))
            }

            repeat(2) { captureIndex ->
                val beforeIds = storage.workspaceProjection().pages.map { it.pageId }.toSet()
                awaitCondition("Camera shutter is enabled for capture ${captureIndex + 1}") {
                    var enabled = false
                    scenario.onActivity { activity ->
                        enabled = activity.window.decorView.findViewWithTag<View>("docscan-capture-button")?.isEnabled == true
                    }
                    enabled
                }
                scenario.onActivity { activity ->
                    requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-capture-button")).performClick()
                }
                awaitCondition("CameraX capture ${captureIndex + 1} must durably commit one Page") {
                    storage.workspaceProjection().pages.count { it.pageId !in beforeIds } == 1
                }
            }
            awaitCondition("OCR must commit a current ImageVersion Recognition for both actual captures", 180_000L) {
                val pages = storage.workspaceProjection().pages
                pages.size == 2 && pages.all { it.recognizedText != null }
            }
            var capturedPages = storage.workspaceProjection().pages
            assertEquals(listOf(0L, 1L), capturedPages.map { it.orderIndex })
            assertEquals("Camera captures auto-select only when their current OCR commits", listOf(true, true), capturedPages.map { it.selected })
            val provenance = pageRecords(context.getDatabasePath(databaseName), capturedPages)
            assertEquals(listOf("CAMERA", "CAMERA"), provenance.map { it.sourceProvenance })
            assertEquals("Camera Pages do not belong to an import session", listOf(null, null), provenance.map { it.importSessionId })
            assertTrue("Two distinct durable Page identities are committed", capturedPages.map { it.pageId }.toSet().size == 2)
            val sourceHashesBeforeEdit = capturedPages.mapIndexed { index, page ->
                val bytes = storage.artifactPath(page.artifactRelativePath).toFile().readBytes()
                assertTrue("Captured Page ${index + 1} source artifact is nonempty", bytes.isNotEmpty())
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw AssertionError("Captured Page ${index + 1} source JPEG must decode")
                try {
                    assertTrue("Captured Page ${index + 1} has positive image dimensions", decoded.width > 0 && decoded.height > 0)
                } finally {
                    decoded.recycle()
                }
                sha256(bytes)
            }
            awaitActivityResumed(scenario)
            assertTrue("The two OCR-selected Pages enable Save",
                storage.workspaceProjection().pages.all { it.selected } && storage.workspaceProjection().pages.size == 2)

            val pageToCrop = capturedPages.first()
            val oldImageFile = storage.artifactPath(pageToCrop.artifactRelativePath).toFile()
            val oldImageBytes = oldImageFile.readBytes()
            val manualCropAction = context.getString(R.string.edit_crop_four_points).uppercase()
            openWorkspaceMenuForTest(scenario)
            scrollWorkspaceListToPositionForTest(scenario, storage.workspaceProjection().pages.indexOfFirst { it.pageId == pageToCrop.pageId })
            scenario.onActivity { activity ->
                val pageRow = requireNotNull(nativeWorkspaceRoot(activity).findViewWithTag<View>(
                    ProductActivity.PAGE_ROW_TAG_PREFIX + pageToCrop.pageId.value,
                ))
                check(pageRow.isLaidOut) { "Workspace Page row must be attached after RecyclerView scrolling" }
                requireNotNull(nativeWorkspaceRoot(activity).findViewWithTag<View>(
                    ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + pageToCrop.pageId.value,
                )).also { editButton ->
                    var railAncestor = editButton.parent
                    var actionRail: android.widget.HorizontalScrollView? = null
                    while (railAncestor is View) {
                        if (railAncestor is android.widget.HorizontalScrollView) {
                            actionRail = railAncestor
                            break
                        }
                        railAncestor = railAncestor.parent
                    }
                    requireNotNull(actionRail) { "Workspace page actions have no horizontal scroll rail" }
                        .scrollTo(editButton.left, 0)
                    editButton.performClick()
                }
            }
            assertTrue("Image editor opens for camera Page 1", device.wait(
                Until.hasObject(By.text(context.getString(R.string.edit_image_title))), TIMEOUT_MILLIS,
            ))
            assertTrue("Image editor exposes manual crop", device.wait(Until.hasObject(By.text(manualCropAction)), TIMEOUT_MILLIS))
            device.findObject(By.text(manualCropAction)).click()
            assertTrue("Manual 4-point crop preview opens", device.wait(
                Until.hasObject(By.text(context.getString(R.string.manual_crop_instruction))), TIMEOUT_MILLIS,
            ))
            val cropCorners = listOf(
                R.string.manual_crop_corner_top_left,
                R.string.manual_crop_corner_top_right,
                R.string.manual_crop_corner_bottom_right,
                R.string.manual_crop_corner_bottom_left,
            ).map(context::getString)
            cropCorners.forEach { description ->
                assertTrue("Manual crop exposes accessible handle '$description'", device.wait(Until.hasObject(By.desc(description)), 3_000L))
            }
            repeat(10) {
                listOf(
                    cropCorners[0] to listOf(CropSelectionView.ACTION_MOVE_RIGHT, CropSelectionView.ACTION_MOVE_DOWN),
                    cropCorners[1] to listOf(CropSelectionView.ACTION_MOVE_LEFT, CropSelectionView.ACTION_MOVE_DOWN),
                    cropCorners[2] to listOf(CropSelectionView.ACTION_MOVE_LEFT, CropSelectionView.ACTION_MOVE_UP),
                    cropCorners[3] to listOf(CropSelectionView.ACTION_MOVE_RIGHT, CropSelectionView.ACTION_MOVE_UP),
                ).forEach { (description, actions) ->
                    actions.forEach { action -> assertTrue("Crop handle '$description' accepts inward action $action", performCropCornerAction(instrumentation, description, action)) }
                }
            }
            device.findObject(By.text(context.getString(R.string.manual_crop_apply))).click()
            val saveCrop = By.text(context.getString(R.string.edit_save))
            assertTrue("Crop returns to the image editor before commit", device.wait(Until.hasObject(saveCrop), TIMEOUT_MILLIS))
            device.findObject(saveCrop).click()
            awaitCondition("Manual crop commits a new ImageVersion without scheduling OCR", 30_000L) {
                val current = storage.workspaceProjection().pages.singleOrNull { it.pageId == pageToCrop.pageId }
                current != null && current.imageVersionId != pageToCrop.imageVersionId && current.recognizedText == null
            }
            SystemClock.sleep(1_500L)
            val editedPages = storage.workspaceProjection().pages
            val editedPage = editedPages.first()
            assertEquals("Crop preserves Page identity", pageToCrop.pageId, editedPage.pageId)
            assertNotEquals("Crop creates a new immutable image version", pageToCrop.imageVersionId, editedPage.imageVersionId)
            assertTrue("Crop Save leaves Undo available", editedPage.canUndoImage)
            assertTrue("Crop preserves Selected state", editedPage.selected)
            assertTrue("Crop preserves Workspace order", editedPages.map { it.pageId to it.orderIndex } == capturedPages.map { it.pageId to it.orderIndex })
            assertNull("Changing ImageVersion clears the old OCR until explicit Re-OCR", editedPage.recognizedText)
            assertNull("Page current_recognition_id is cleared while OCR is pending", currentRecognitionVersion(context.getDatabasePath(databaseName), editedPage.pageId.value))
            assertEquals("Crop leaves the previous immutable camera bytes unchanged", sourceHashesBeforeEdit.first(), sha256(oldImageFile.readBytes()))
            val editedImageFile = storage.artifactPath(editedPage.artifactRelativePath).toFile()
            assertNotEquals("Crop publishes distinct edited bytes", sourceHashesBeforeEdit.first(), sha256(editedImageFile.readBytes()))

            scrollWorkspaceListToPositionForTest(scenario, storage.workspaceProjection().pages.indexOfFirst { it.pageId == editedPage.pageId })
            scenario.onActivity { activity ->
                val pageRow = requireNotNull(nativeWorkspaceRoot(activity).findViewWithTag<View>(
                    ProductActivity.PAGE_ROW_TAG_PREFIX + editedPage.pageId.value,
                ))
                check(pageRow.isLaidOut) { "Workspace Page row must remain attached after RecyclerView scrolling" }
                var railAncestor = requireNotNull(nativeWorkspaceRoot(activity).findViewWithTag<View>(
                    ProductActivity.PAGE_IMAGE_EDIT_BUTTON_TAG_PREFIX + editedPage.pageId.value,
                )).parent
                while (railAncestor is View) {
                    if (railAncestor is android.widget.HorizontalScrollView) {
                        railAncestor.scrollTo(0, 0)
                        break
                    }
                    railAncestor = railAncestor.parent
                }
            }
            val explicitReOcr = By.text(context.getString(R.string.recognize_text).uppercase())
            assertTrue("Changed Page exposes explicit Re-OCR action", device.wait(Until.hasObject(explicitReOcr), TIMEOUT_MILLIS))
            device.findObject(explicitReOcr).click()
            awaitCondition("Explicit Re-OCR commits Recognition for the edited current ImageVersion", 180_000L) {
                val current = storage.workspaceProjection().pages.singleOrNull { it.pageId == editedPage.pageId }
                current?.imageVersionId == editedPage.imageVersionId && current.recognizedText != null &&
                    currentRecognitionVersion(context.getDatabasePath(databaseName), current.pageId.value) == current.imageVersionId.value
            }
            capturedPages = storage.workspaceProjection().pages
            assertEquals(listOf(true, true), capturedPages.map { it.selected })
            val sourceBytes = capturedPages.mapIndexed { index, page ->
                val bytes = storage.artifactPath(page.artifactRelativePath).toFile().readBytes()
                val decoded = ProductImageDecoder.decode(storage.artifactPath(page.artifactRelativePath).toFile(), 12L * 1024L * 1024L)
                    ?: throw AssertionError("Final frozen camera Page ${index + 1} must decode with EXIF orientation")
                try { assertTrue("Final camera Page ${index + 1} has positive oriented dimensions", decoded.width > 0 && decoded.height > 0) }
                finally { decoded.recycle() }
                sha256(bytes)
            }

            closeWorkspaceMenuForTest(scenario)
            scenario.onActivity { activity -> requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button")).performClick() }
            assertTrue("Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MILLIS))
            val cancelSave = device.wait(Until.findObject(By.res("android", "button2")), 5_000L)
                ?: throw AssertionError("Save name dialog must expose Cancel")
            cancelSave.click()
            assertTrue("Cancel returns to ProductActivity", device.wait(Until.hasObject(By.pkg("app.docscan.scanner")), TIMEOUT_MILLIS))
            assertEquals("Cancel preserves captured Page and ImageVersion identities",
                capturedPages.map { it.pageId to it.imageVersionId },
                storage.workspaceProjection().pages.map { it.pageId to it.imageVersionId })
            assertEquals("Cancel preserves OCR text and Selected state", capturedPages.map { it.recognizedText to it.selected },
                storage.workspaceProjection().pages.map { it.recognizedText to it.selected })
            assertEquals("Cancel preserves source artifact bytes", sourceBytes,
                storage.workspaceProjection().pages.map { page -> sha256(storage.artifactPath(page.artifactRelativePath).toFile().readBytes()) })
            assertTrue("Cancelled Save creates no external package output",
                providerChildren(context, selectedTree).none { it.second in expectedOutputNames })

            scenario.onActivity { activity -> requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button")).performClick() }
            assertTrue("Second Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MILLIS))
            device.findObject(By.clazz("android.widget.EditText")).text = basename
            val saveAction = By.text("Save selected pages")
            assertTrue("Save confirmation is available", device.wait(Until.hasObject(saveAction), TIMEOUT_MILLIS))
            device.findObject(saveAction).click()

            val recent = try {
                awaitValue("Camera output package is completely confirmed") { storage.recentDocuments().singleOrNull() }
            } catch (failure: Throwable) {
                // Preserve the failed 20s observation before fixture cleanup destroys its state.
                // Diagnostics never extend the deadline or turn a late result into a pass.
                runCatching {
                    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                        ?.let(::File) ?: File(context.getExternalFilesDir(null), "camera-save-evidence")
                    check(output.exists() || output.mkdirs())
                    val report = File(output, "camera-save-failure-$suffix.txt")
                    val stacks = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, stack) ->
                        "${thread.name} [${thread.state}]\n${stack.joinToString("\n")}"
                    }
                    val statusAtFailure = statusText(scenario)
                    val rows = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath,
                        null, SQLiteDatabase.OPEN_READONLY).use { database ->
                        listOf("SaveManifest", "SaveDependencyResult", "ExportArtifact", "ExternalExportPackage",
                            "ExportAttempt", "RecentDocument").joinToString("\n") { table ->
                            database.rawQuery("SELECT * FROM $table", null).use { cursor ->
                                buildString {
                                    append("$table:\n")
                                    while (cursor.moveToNext()) append(cursor.columnNames.mapIndexed { index, name ->
                                        "$name=${cursor.getString(index)}"
                                    }.joinToString(" | ")).append('\n')
                                }
                            }
                        }
                    }
                    report.writeText("version=${context.packageManager.getPackageInfo(context.packageName, 0).versionName}\n" +
                        "failure=$failure\nstatus=$statusAtFailure\n$rows\nTHREADS:\n$stacks")
                    device.takeScreenshot(File(output, "camera-save-failure-$suffix.png"))
                    device.dumpWindowHierarchy(File(output, "camera-save-failure-$suffix.xml"))
                    android.util.Log.e(TAG, "CAMERA_SAVE_OBSERVATION_FAILURE status=$statusAtFailure rows=$rows")
                }.onFailure { failure.addSuppressed(it) }
                throw failure
            }
            val exportPackage = requireNotNull(storage.externalExportPackage(recent.exportArtifactId))
            assertEquals("SUCCEEDED", exportPackage.state)
            assertEquals(qualificationId, exportPackage.providerQualificationId)
            val packageOutputs = exportPackage.outputs.sortedBy { it.ordinal }
            assertEquals(3, packageOutputs.size)
            assertEquals(listOf(0L, 1L, 2L), packageOutputs.map { it.ordinal })
            assertEquals(listOf("$basename.pdf", "$basename — 01.jpg", "$basename — 02.jpg"), packageOutputs.map { it.displayName })
            val committedOutputs = storage.committedExportOutputs(recent.exportArtifactId).associateBy { it.artifactId }
            val attempts = storage.exportAttempts(recent.exportArtifactId).associateBy { it.outputArtifactId }
            assertEquals(3, attempts.size)
            assertTrue("Every SAF attempt is confirmed", attempts.values.all { it.state == "SUCCEEDED" })
            val providerBytes = packageOutputs.associate { packageOutput ->
                val output = requireNotNull(committedOutputs[packageOutput.outputArtifactId])
                val attempt = requireNotNull(attempts[packageOutput.outputArtifactId])
                val bytes = requireNotNull(context.contentResolver.openInputStream(Uri.parse(attempt.externalIdentity))).use { it.readBytes() }
                assertEquals("Provider read-back size matches committed private output", output.sizeBytes, bytes.size.toLong())
                assertEquals("Provider read-back SHA matches committed private output", output.sha256, sha256(bytes))
                assertEquals("Private committed output bytes match their declared SHA", output.sha256,
                    sha256(storage.artifactPath(output.relativePath).toFile().readBytes()))
                packageOutput.displayName to bytes
            }
            val pdfBytes = requireNotNull(providerBytes[packageOutputs.first().displayName])
            PDFBoxResourceLoader.init(context)
            PDDocument.load(ByteArrayInputStream(pdfBytes)).use { document ->
                assertEquals("Camera Save PDF has one page per captured Page", 2, document.numberOfPages)
                val renderer = PDFRenderer(document)
                repeat(2) { pageIndex ->
                    val raster = renderer.renderImage(pageIndex, 0.25f)
                    try {
                        assertTrue("Saved camera PDF page ${pageIndex + 1} renders nonempty pixels",
                            raster.width > 0 && raster.height > 0 && raster.getPixel(raster.width / 2, raster.height / 2) != android.graphics.Color.TRANSPARENT)
                        val sourcePage = capturedPages[pageIndex]
                        assertSampledPdfPageMatches(
                            "PDF page ${pageIndex + 1} maps to frozen camera Page ${sourcePage.orderIndex + 1}",
                            storage.artifactPath(sourcePage.artifactRelativePath).toFile(),
                            raster,
                        )
                    } finally {
                        raster.recycle()
                    }
                }
            }
            packageOutputs.drop(1).zip(capturedPages).forEach { (packageOutput, sourcePage) ->
                val bytes = requireNotNull(providerBytes[packageOutput.displayName])
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw AssertionError("Saved companion JPEG ${packageOutput.displayName} must decode")
                try {
                    assertTrue("Saved companion JPEG has valid dimensions", image.width > 0 && image.height > 0)
                    assertSampledImageMatches("${packageOutput.displayName} matches frozen camera Page ${sourcePage.orderIndex + 1}",
                        storage.artifactPath(sourcePage.artifactRelativePath).toFile(), image)
                }
                finally { image.recycle() }
            }
            assertTrue("Workspace Pages are consumed only after every external output succeeds",
                storage.workspaceProjection().pages.isEmpty())
            scenario.onActivity { activity ->
                assertEquals("The same ProductActivity remains after all SAF flows", activityIdentity.get(), System.identityHashCode(activity))
            }
            android.util.Log.i(TAG,
                "CAMERA_CROP_REOCR_TO_SAF_SAVE_PASS fixture=$suffix pages=2 oldPage1Sha256=${sourceHashesBeforeEdit.first()} " +
                    "editedPage1ImageVersion=${capturedPages.first().imageVersionId.value} sourceSha256=${sourceBytes.joinToString(",")} " +
                    "ocrChars=${capturedPages.map { it.recognizedText.orEmpty().length }} outputs=${packageOutputs.size} " +
                    "outputSha256=${committedOutputs.values.joinToString(",") { it.sha256 }}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            fun retainCleanupFailure(failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
            }
            runCatching { scenario?.close() }.onFailure(::retainCleanupFailure)
            app.productStorageProviderForTest = previousProvider
            app.productStorageOverrideForTest = previousOverride
            runCatching { storage.close() }.onFailure(::retainCleanupFailure)
            tree?.let { selectedTree ->
                if (outputNamespaceWasAbsent) {
                    runCatching {
                        providerChildren(context, selectedTree).filter { it.second in expectedOutputNames }.forEach { (uri, _) ->
                            check(DocumentsContract.deleteDocument(context.contentResolver, uri)) { "Could not remove test output $uri" }
                        }
                    }.onFailure(::retainCleanupFailure)
                    runCatching { check(providerChildren(context, selectedTree).none { it.second in expectedOutputNames }) }
                        .onFailure(::retainCleanupFailure)
                }
                if (selectedTree.toString() !in permissionsBefore) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure(::retainCleanupFailure)
                }
            }
            runCatching { check(File(context.noBackupFilesDir, artifactDirectory).deleteRecursively() || !File(context.noBackupFilesDir, artifactDirectory).exists()) }
                .onFailure(::retainCleanupFailure)
            runCatching { check(File(context.noBackupFilesDir, candidateDirectory).deleteRecursively() || !File(context.noBackupFilesDir, candidateDirectory).exists()) }
                .onFailure(::retainCleanupFailure)
            runCatching { check(context.deleteDatabase(databaseName)) }.onFailure(::retainCleanupFailure)
            runCatching { assertEquals("Only permissions present before the macro may remain", permissionsBefore, persistedPermissionUris(context)) }
                .onFailure(::retainCleanupFailure)
            runCatching { SafPickerTestActions.removeDefaultFolderFixture(defaultFolderCreated) }.onFailure(::retainCleanupFailure)
            cleanupFailure?.let { cleanup ->
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup)
                else throw AssertionError("Camera macro cleanup failed", cleanup)
            }
        }
    }

    @Test
    fun realProductPickerImportsEveryPdfPageInSourceOrderWithVerifiedSnapshotAndPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val fixtureId = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMvpPdfImport-$fixtureId"
        val displayName = "ordered-pages-$fixtureId.pdf"
        val databaseName = "pdf-picker-$fixtureId.db"
        val artifactDirectory = "pdf-picker-artifacts-$fixtureId"
        val permissionsBefore = persistedPermissionUris(context)
        val observedStatuses = CopyOnWriteArrayList<String>()
        val source = createFixturePdf(context, fixtureFolder, displayName)
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            assertTrue("The isolated workspace must start empty", storage.workspaceProjection().pages.isEmpty())
            assertSourceBytesUnchanged(context, source)
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity ->
                val status = requireNotNull(activity.window.decorView.findViewWithTag<TextView>("docscan-status"))
                status.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        observedStatuses += s?.toString().orEmpty()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            clickImport(scenario)

            val device = UiDevice.getInstance(instrumentation)
            assertTrue("The real DocumentsUI picker must launch",
                device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            navigateToDownloadsRoot(device)
            clickFresh(device, By.res(DOCUMENTS_UI, "item_root").hasDescendant(By.text(fixtureFolder)), "Unique PDF fixture folder")
            assertTrue("DocumentsUI must show the unique PDF fixture folder",
                device.wait(Until.hasObject(By.res(DOCUMENTS_UI, "breadcrumb_text").text(fixtureFolder)), TIMEOUT_MILLIS))
            clickFresh(device, By.res(DOCUMENTS_UI, "item_root").hasDescendant(By.text(displayName)), "Three-page PDF source")
            val explicitConfirmation = device.wait(
                Until.hasObject(By.text(Pattern.compile("(?i)^(open|select)$"))),
                1_500,
            )
            if (explicitConfirmation) {
                clickFresh(device, By.text(Pattern.compile("(?i)^(open|select)$")), "DocumentsUI PDF confirmation")
            } else {
                assertTrue("DocumentsUI must either expose its confirmation action or return the selected PDF",
                    device.wait(Until.gone(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            }
            awaitActivityResumed(scenario)

            awaitCondition("All three selected PDF pages must commit") {
                storage.workspaceProjection().pages.size == EXPECTED_PDF_PAGE_COLORS.size
            }
            val pages = storage.workspaceProjection().pages
            val records = pageRecords(context.getDatabasePath(databaseName), pages)
            assertEquals(listOf(0L, 1L, 2L), records.map { it.sourcePageIndex })
            assertEquals("Every rendered page must belong to one PDF import session", 1,
                records.map { it.importSessionId }.distinct().size)
            assertEquals("PDF source provenance must name the selected source", listOf("application/pdf:$displayName"),
                records.map { it.sourceProvenance }.distinct())
            assertEquals("The fixture must create exactly one completed ImportSession", 1,
                importSessionRecords(context.getDatabasePath(databaseName)).size)
            val session = importSessionRecords(context.getDatabasePath(databaseName)).single()
            assertEquals("COMPLETED", session.state)
            assertEquals(displayName, session.displayName)
            assertEquals("Private source snapshot checksum must match the exact selected bytes", sha256(source.bytes),
                privateSourceSha256(context.getDatabasePath(databaseName), artifactDirectory, session.sourceSnapshotArtifactId))

            pages.zip(EXPECTED_PDF_PAGE_COLORS).forEachIndexed { index, (page, expectedColor) ->
                val artifact = storage.artifactPath(page.artifactRelativePath).toFile()
                val bitmap = BitmapFactory.decodeFile(artifact.absolutePath)
                    ?: throw AssertionError("Private rendered PDF page $index is not a decodable image")
                try {
                    val centerColor = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff
                    assertEquals("Rendered private artifact at Page order $index must match source PDF page $index",
                        expectedColor, centerColor)
                } finally {
                    bitmap.recycle()
                }
            }
            assertSourceBytesUnchanged(context, source)
            assertTrue("ProductActivity must emit the three-pages-from-one-document completion status; observed=$observedStatuses",
                context.getString(R.string.import_documents_complete, 3, 1) in observedStatuses)
            assertEquals("PDF import must not retain a temporary picker grant", permissionsBefore,
                persistedPermissionUris(context))
            android.util.Log.i(TAG,
                "PDF_PICKER_ORDER_PASS fixture=$fixtureId displayName=$displayName pages=${pages.size} " +
                    "pageIndices=${records.joinToString(",") { it.sourcePageIndex.toString() }} " +
                    "sourceSha256=${sha256(source.bytes)} privateSha256=${session.sourceSnapshotSha256} " +
                    "pageColors=${EXPECTED_PDF_PAGE_COLORS.joinToString(",") { "%06x".format(it) }}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            runCatching { scenario?.close() }.onFailure { cleanupFailure = it }
            app.productStorageProviderForTest = oldProvider
            app.productStorageOverrideForTest = oldOverride
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            runCatching {
                cleanupFixtureDocuments(context, fixtureFolder, listOf(source))
                val artifactRoot = File(context.noBackupFilesDir, artifactDirectory)
                assertTrue("Test artifact directory must be removed", artifactRoot.deleteRecursively() || !artifactRoot.exists())
                assertTrue("Test database must be removed", context.deleteDatabase(databaseName))
                assertTrue("No test PDF may remain", listFixtureDocuments(context, fixtureFolder).isEmpty())
                assertEquals("No app persisted grant may be added or removed", permissionsBefore,
                    persistedPermissionUris(context))
            }.onFailure { cleanupFailure = it }
            val cleanupProblem = cleanupFailure
            if (cleanupProblem != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupProblem)
                else throw AssertionError("PDF picker fixture cleanup failed", cleanupProblem)
            }
        }
    }

    @Test
    fun realPickerKeepsValidPdfPagesWhenFollowingMalformedPdfFails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val fixtureId = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMvpMixedPdf-$fixtureId"
        val validName = "01-valid-$fixtureId.pdf"
        val malformedName = "02-malformed-$fixtureId.pdf"
        val databaseName = "mixed-pdf-picker-$fixtureId.db"
        val artifactDirectory = "mixed-pdf-picker-artifacts-$fixtureId"
        val permissionsBefore = persistedPermissionUris(context)
        val observedStatuses = CopyOnWriteArrayList<String>()
        val valid = createFixturePdf(context, fixtureFolder, validName, listOf(0xe53935, 0x43a047))
        val malformed = publishFixtureDocument(
            context,
            fixtureFolder,
            malformedName,
            "application/pdf",
            "%PDF-1.7\nmalformed test-owned source\n".toByteArray(),
        )
        val sources = listOf(valid, malformed)
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            assertTrue("The isolated workspace must start empty", storage.workspaceProjection().pages.isEmpty())
            assertEquals(2, listFixtureDocuments(context, fixtureFolder).size)
            sources.forEach { assertSourceBytesUnchanged(context, it) }

            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity ->
                val status = requireNotNull(activity.window.decorView.findViewWithTag<TextView>("docscan-status"))
                status.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        observedStatuses += s?.toString().orEmpty()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            clickImport(scenario)

            val device = UiDevice.getInstance(instrumentation)
            assertTrue("The real DocumentsUI picker must launch",
                device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            navigateToDownloadsRoot(device)
            clickFresh(device, By.res(DOCUMENTS_UI, "item_root").hasDescendant(By.text(fixtureFolder)), "Unique mixed-PDF fixture folder")
            assertTrue("DocumentsUI must show the unique mixed-PDF fixture folder",
                device.wait(Until.hasObject(By.res(DOCUMENTS_UI, "breadcrumb_text").text(fixtureFolder)), TIMEOUT_MILLIS))
            val validCard = By.res(DOCUMENTS_UI, "item_root").hasDescendant(By.text(validName))
            val malformedCard = By.res(DOCUMENTS_UI, "item_root").hasDescendant(By.text(malformedName))
            clickFresh(device, validCard, "Valid PDF source", longClick = true)
            SystemClock.sleep(200)
            clickFresh(device, malformedCard, "Malformed PDF source")
            clickFresh(device, By.text(Pattern.compile("(?i)^(open|select)$")), "DocumentsUI mixed-source confirmation")
            awaitActivityResumed(scenario)

            awaitCondition("The valid PDF's two Pages must commit despite the following malformed source") {
                storage.workspaceProjection().pages.size == 2
            }
            val partialPrefix = context.getString(R.string.import_documents_partial, 2, 2, 1, "")
                .substringBefore("First error:").trim()
            awaitCondition("ProductActivity must finish reporting partial success for 2 pages/2 sources/1 failure") {
                observedStatuses.any { it.startsWith(partialPrefix) }
            }
            val pages = storage.workspaceProjection().pages
            val records = pageRecords(context.getDatabasePath(databaseName), pages)
            assertEquals(listOf(0L, 1L), records.map { it.sourcePageIndex })
            assertEquals(listOf("application/pdf:$validName"), records.map { it.sourceProvenance }.distinct())
            assertEquals("Only the valid source may own an ImportSession", 1,
                importSessionRecords(context.getDatabasePath(databaseName)).size)
            val session = importSessionRecords(context.getDatabasePath(databaseName)).single()
            assertEquals("COMPLETED", session.state)
            assertEquals(validName, session.displayName)
            assertEquals(sha256(valid.bytes), session.sourceSnapshotSha256)
            assertEquals(sha256(valid.bytes), privateSourceSha256(
                context.getDatabasePath(databaseName), artifactDirectory, session.sourceSnapshotArtifactId,
            ))
            assertEquals("Malformed input must leave no publication intent",
                0L, databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM PdfImportPublicationIntent"))
            assertEquals("Only source snapshot and the two committed page images may have artifacts",
                3L, databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM ArtifactRecord"))

            pages.zip(listOf(0xe53935, 0x43a047)).forEachIndexed { index, (page, expectedColor) ->
                val artifact = storage.artifactPath(page.artifactRelativePath).toFile()
                val bitmap = BitmapFactory.decodeFile(artifact.absolutePath)
                    ?: throw AssertionError("Valid source page $index did not produce a decodable private image")
                try {
                    val centerColor = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff
                    assertEquals("Partial success must preserve valid PDF source-page order at $index", expectedColor, centerColor)
                } finally {
                    bitmap.recycle()
                }
            }

            sources.forEach { assertSourceBytesUnchanged(context, it) }
            val partialStatus = observedStatuses.firstOrNull { it.startsWith(partialPrefix) }
            assertNotNull("ProductActivity must emit partial success for 2 pages/2 sources/1 failure; observed=$observedStatuses",
                partialStatus)
            assertTrue("Partial status must include a visible failure reason",
                requireNotNull(partialStatus).substringAfter("First error:").isNotBlank())
            assertEquals("Picker import must not persist temporary document grants", permissionsBefore,
                persistedPermissionUris(context))
            android.util.Log.i(TAG,
                "MIXED_PDF_PICKER_PASS fixture=$fixtureId valid=$validName malformed=$malformedName " +
                    "pages=${pages.size} pageIndices=${records.joinToString(",") { it.sourcePageIndex.toString() }} " +
                    "validSha256=${sha256(valid.bytes)} malformedSha256=${sha256(malformed.bytes)} " +
                    "sessions=1 artifacts=3 intents=0 partialStatus=${requireNotNull(partialStatus)}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            runCatching { scenario?.close() }.onFailure { cleanupFailure = it }
            app.productStorageProviderForTest = oldProvider
            app.productStorageOverrideForTest = oldOverride
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            runCatching {
                cleanupFixtureDocuments(context, fixtureFolder, sources)
                val artifactRoot = File(context.noBackupFilesDir, artifactDirectory)
                assertTrue("Test artifact directory must be removed", artifactRoot.deleteRecursively() || !artifactRoot.exists())
                assertTrue("Test database must be removed", context.deleteDatabase(databaseName))
                assertTrue("No mixed-PDF fixture may remain", listFixtureDocuments(context, fixtureFolder).isEmpty())
                assertEquals("No app persisted grant may be added or removed", permissionsBefore,
                    persistedPermissionUris(context))
            }.onFailure { cleanupFailure = it }
            val cleanupProblem = cleanupFailure
            if (cleanupProblem != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupProblem)
                else throw AssertionError("Mixed-PDF picker fixture cleanup failed", cleanupProblem)
            }
        }
    }

    @Test
    fun realProductPickerImportsPdfPagesBeforeFollowingJpeg() {
        assertMixedPdfImagePickerOrder(pdfFirst = true)
    }

    @Test
    fun realProductPickerImportsJpegBeforeFollowingPdfPages() {
        assertMixedPdfImagePickerOrder(pdfFirst = false)
    }

    private fun assertMixedPdfImagePickerOrder(pdfFirst: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val fixtureId = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMvpPdfImage-$fixtureId"
        val pdfName = "01-two-pages-$fixtureId.pdf"
        val imageName = "02-image-$fixtureId.jpg"
        val databaseName = "pdf-image-picker-$fixtureId.db"
        val artifactDirectory = "pdf-image-picker-artifacts-$fixtureId"
        val permissionsBefore = persistedPermissionUris(context)
        val observedStatuses = CopyOnWriteArrayList<String>()
        val pdf = createFixturePdf(context, fixtureFolder, pdfName, listOf(0xe53935, 0x43a047))
        val image = publishFixtureImage(context, fixtureFolder, imageName, "image/jpeg", 0xff244a82.toInt())
        val pickerSources = if (pdfFirst) listOf(pdf, image) else listOf(image, pdf)
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            assertTrue("The isolated workspace must start empty", storage.workspaceProjection().pages.isEmpty())
            assertEquals("Only the two test-owned mixed-type sources may exist", 2,
                listFixtureDocuments(context, fixtureFolder).size)
            pickerSources.forEach { assertSourceBytesUnchanged(context, it) }

            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity ->
                val status = requireNotNull(activity.window.decorView.findViewWithTag<TextView>("docscan-status"))
                status.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        observedStatuses += s?.toString().orEmpty()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            clickImport(scenario)

            val device = UiDevice.getInstance(instrumentation)
            chooseFixtureDocuments(device, fixtureFolder, pickerSources.map { it.displayName })
            awaitActivityResumed(scenario)

            val completeStatus = context.getString(R.string.import_documents_complete, 3, 2)
            awaitCondition("ProductActivity must complete the 2-source/3-page mixed import") {
                observedStatuses.contains(completeStatus)
            }
            val pages = storage.workspaceProjection().pages
            val records = pageRecords(context.getDatabasePath(databaseName), pages)
            val sessions = importSessionRecords(context.getDatabasePath(databaseName))
            assertEquals("An image and two PDF pages must each materialize as a Page", 3, pages.size)
            assertEquals("Each picked source must own one completed ImportSession", 2, sessions.size)
            assertTrue("Every mixed-source ImportSession must complete", sessions.all { it.state == "COMPLETED" })
            assertEquals("Completed sessions must retain picker order", pickerSources.map { it.displayName },
                sessions.map { it.displayName })

            val sessionByName = sessions.associateBy { it.displayName }
            val pdfSession = requireNotNull(sessionByName[pdf.displayName])
            val imageSession = requireNotNull(sessionByName[image.displayName])
            val expectedPageSources = pickerSources.flatMap { source ->
                if (source.displayName == pdf.displayName) listOf(pdf, pdf) else listOf(image)
            }
            val expectedPageSessionIds = pickerSources.flatMap { source ->
                val sessionId = requireNotNull(sessionByName[source.displayName]).importSessionId
                if (source.displayName == pdf.displayName) listOf(sessionId, sessionId) else listOf(sessionId)
            }
            val expectedSourcePageIndices = pickerSources.flatMap { source ->
                if (source.displayName == pdf.displayName) listOf(0L, 1L) else listOf(0L)
            }
            assertEquals("One Page provenance entry must exist for each PDF page and picked image",
                expectedPageSources.map { "${it.mimeType}:${it.displayName}" }, records.map { it.sourceProvenance })
            assertEquals("Page-to-session membership must follow the selected source order",
                expectedPageSessionIds, records.map { it.importSessionId })
            assertEquals("PDF page tuples and the image source tuple must remain identifiable",
                expectedSourcePageIndices, records.map { it.sourcePageIndex })
            assertEquals("Workspace Page order must remain the picker source order and PDF page order",
                pages.map { it.orderIndex }.sorted(), pages.map { it.orderIndex })
            assertTrue("Every successfully imported Page must be selected", pages.all { it.selected })

            val sourceByName = pickerSources.associateBy { it.displayName }
            sessions.forEach { session ->
                val source = requireNotNull(sourceByName[session.displayName])
                assertEquals("ImportSession source checksum must match provider bytes: ${source.displayName}",
                    sha256(source.bytes), session.sourceSnapshotSha256)
                assertEquals("Private source snapshot must match provider bytes: ${source.displayName}",
                    sha256(source.bytes), privateSourceSha256(
                        context.getDatabasePath(databaseName), artifactDirectory, session.sourceSnapshotArtifactId,
                    ))
            }

            val pdfPages = records.zip(pages).filter { (record, _) -> record.importSessionId == pdfSession.importSessionId }
            assertEquals("The selected PDF must produce exactly two Pages", 2, pdfPages.size)
            assertEquals(listOf(0L, 1L), pdfPages.map { it.first.sourcePageIndex })
            pdfPages.zip(listOf(0xe53935, 0x43a047)).forEachIndexed { index, (recordAndPage, expectedColor) ->
                assertEquals("Rendered PDF page source index must remain ordered", index.toLong(),
                    recordAndPage.first.sourcePageIndex)
                val artifact = storage.artifactPath(recordAndPage.second.artifactRelativePath).toFile()
                val bitmap = BitmapFactory.decodeFile(artifact.absolutePath)
                    ?: throw AssertionError("Mixed-source PDF page $index is not a decodable private image")
                try {
                    val centerColor = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff
                    assertEquals("Rendered PDF page $index must match its source visual marker", expectedColor, centerColor)
                } finally {
                    bitmap.recycle()
                }
            }
            val imagePage = records.zip(pages).single { (record, _) -> record.importSessionId == imageSession.importSessionId }.second
            val privateImageBytes = storage.artifactPath(imagePage.artifactRelativePath).toFile().readBytes()
            assertEquals("Imported JPEG byte count must match its provider source", image.bytes.size, privateImageBytes.size)
            assertEquals("Imported JPEG bytes must exactly match the provider source", sha256(image.bytes), sha256(privateImageBytes))

            assertEquals("One private source plus two PDF page images and one JPEG page artifact are expected", 4L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM ArtifactRecord"))
            assertEquals("No PDF publication intent may remain after completion", 0L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM PdfImportPublicationIntent"))
            assertEquals("No image publication intent may remain after completion", 0L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM ImageImportPublicationIntent"))
            pickerSources.forEach { assertSourceBytesUnchanged(context, it) }
            assertEquals("Picker import must not persist temporary document grants", permissionsBefore,
                persistedPermissionUris(context))

            val hierarchyFile = File(context.cacheDir, "pdf-image-picker-$fixtureId.xml")
            val hierarchy = try {
                device.dumpWindowHierarchy(hierarchyFile)
                hierarchyFile.readText()
            } finally {
                hierarchyFile.delete()
            }
            val orderLabel = if (pdfFirst) "PDF,JPEG" else "JPEG,PDF"
            hierarchy.chunked(2_800).forEachIndexed { index, chunk ->
                android.util.Log.i(TAG, "MIXED_PDF_IMAGE_UI order=$orderLabel part=$index xml=$chunk")
            }
            android.util.Log.i(TAG,
                "MIXED_PDF_IMAGE_ORDER_PASS order=$orderLabel fixture=$fixtureId pageProvenance=" +
                    records.joinToString(",") { it.sourceProvenance } +
                    " sourcePageIndices=${records.joinToString(",") { it.sourcePageIndex.toString() }}" +
                    " pdfSha256=${sha256(pdf.bytes)} imageSha256=${sha256(image.bytes)}" +
                    " sessions=${sessions.size} artifacts=4 pdfIntents=0 imageIntents=0 " +
                    "selectedPages=${pages.count { it.selected }} completion=$completeStatus")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            runCatching { scenario?.close() }.onFailure { cleanupFailure = it }
            app.productStorageProviderForTest = oldProvider
            app.productStorageOverrideForTest = oldOverride
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            runCatching {
                cleanupFixtureDocuments(context, fixtureFolder, pickerSources)
                val artifactRoot = File(context.noBackupFilesDir, artifactDirectory)
                assertTrue("Mixed PDF/image artifact directory must be removed",
                    artifactRoot.deleteRecursively() || !artifactRoot.exists())
                assertTrue("Mixed PDF/image test database must be removed", context.deleteDatabase(databaseName))
                assertTrue("No mixed PDF/image fixture may remain", listFixtureDocuments(context, fixtureFolder).isEmpty())
                assertEquals("No app persisted grant may be added or removed", permissionsBefore,
                    persistedPermissionUris(context))
            }.onFailure { cleanupFailure = it }
            val cleanupProblem = cleanupFailure
            if (cleanupProblem != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupProblem)
                else throw AssertionError("Mixed PDF/image picker fixture cleanup failed", cleanupProblem)
            }
        }
    }

    @Test
    fun realProductPickerKeepsValidPdfWhenFollowingMalformedJpegFails() {
        assertCrossTypePickerFailureKeepsValidSource(pdfFirst = true)
    }

    @Test
    fun realProductPickerKeepsValidJpegWhenFollowingMalformedPdfFails() {
        assertCrossTypePickerFailureKeepsValidSource(pdfFirst = false)
    }

    @Test
    fun realProductPickerContinuesWithValidJpegAfterMalformedPdf() {
        assertCrossTypePickerFailureKeepsValidSource(pdfFirst = true, validFirst = false)
    }

    @Test
    fun realProductPickerContinuesWithValidPdfAfterMalformedJpeg() {
        assertCrossTypePickerFailureKeepsValidSource(pdfFirst = false, validFirst = false)
    }

    private fun assertCrossTypePickerFailureKeepsValidSource(pdfFirst: Boolean, validFirst: Boolean = true) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val fixtureId = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMvpCrossTypeFailure-$fixtureId"
        val pdfName = if (pdfFirst) "01-$fixtureId.pdf" else "02-$fixtureId.pdf"
        val imageName = if (pdfFirst) "02-$fixtureId.jpg" else "01-$fixtureId.jpg"
        val databaseName = "cross-type-failure-$fixtureId.db"
        val artifactDirectory = "cross-type-failure-artifacts-$fixtureId"
        val permissionsBefore = persistedPermissionUris(context)
        val observedStatuses = CopyOnWriteArrayList<String>()
        val pdfIsValid = pdfFirst == validFirst
        val imageIsValid = !pdfIsValid
        val pdf = if (pdfIsValid) {
            createFixturePdf(context, fixtureFolder, pdfName, listOf(0xe53935, 0x43a047))
        } else {
            publishFixtureDocument(
                context,
                fixtureFolder,
                pdfName,
                "application/pdf",
                "%PDF-1.7\nmalformed test-owned source\n".toByteArray(),
            )
        }
        val image = if (imageIsValid) {
            publishFixtureImage(context, fixtureFolder, imageName, "image/jpeg", 0xff244a82.toInt())
        } else {
            publishFixtureDocument(
                context,
                fixtureFolder,
                imageName,
                "image/jpeg",
                byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05),
            )
        }
        val pickerSources = if (pdfFirst) listOf(pdf, image) else listOf(image, pdf)
        val validSource = if (pdfIsValid) pdf else image
        val malformedSource = if (pdfIsValid) image else pdf
        val expectedPages = if (pdfIsValid) 2 else 1
        val expectedArtifacts = if (pdfIsValid) 3L else 1L
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            assertTrue("The isolated workspace must start empty", storage.workspaceProjection().pages.isEmpty())
            assertEquals("Only the two test-owned cross-type documents may exist", 2,
                listFixtureDocuments(context, fixtureFolder).size)
            pickerSources.forEach { assertSourceBytesUnchanged(context, it) }

            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity ->
                val status = requireNotNull(activity.window.decorView.findViewWithTag<TextView>("docscan-status"))
                status.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        observedStatuses += s?.toString().orEmpty()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            clickImport(scenario)

            val device = UiDevice.getInstance(instrumentation)
            chooseFixtureDocuments(device, fixtureFolder, pickerSources.map { it.displayName })
            awaitActivityResumed(scenario)

            awaitCondition("The valid source must commit even when the other selected source fails") {
                storage.workspaceProjection().pages.size == expectedPages
            }
            val partialPrefix = context.getString(
                R.string.import_documents_partial, expectedPages, 2, 1, "",
            ).substringBefore("First error:").trim()
            awaitCondition("ProductActivity must report the successful prefix and failed cross-type source") {
                observedStatuses.any { it.startsWith(partialPrefix) }
            }

            val pages = storage.workspaceProjection().pages
            val records = pageRecords(context.getDatabasePath(databaseName), pages)
            val sessions = importSessionRecords(context.getDatabasePath(databaseName))
            val expectedMime = if (pdfIsValid) "application/pdf" else "image/jpeg"
            assertEquals("The valid source must retain its exact Page count", expectedPages, pages.size)
            assertEquals("The malformed source must not create a Page", 1,
                records.map { it.sourceProvenance }.distinct().size)
            assertEquals(listOf("$expectedMime:${validSource.displayName}"), records.map { it.sourceProvenance }.distinct())
            assertEquals("Only the successful source may own a completed ImportSession", 1, sessions.size)
            val session = sessions.single()
            assertEquals("COMPLETED", session.state)
            assertEquals(validSource.displayName, session.displayName)
            assertEquals(sha256(validSource.bytes), session.sourceSnapshotSha256)
            assertEquals(sha256(validSource.bytes), privateSourceSha256(
                context.getDatabasePath(databaseName), artifactDirectory, session.sourceSnapshotArtifactId,
            ))
            assertEquals("The committed valid source tuples must preserve their order",
                if (pdfIsValid) listOf(0L, 1L) else listOf(0L), records.map { it.sourcePageIndex })
            assertTrue("Every successfully imported Page must remain selected", pages.all { it.selected })

            if (pdfIsValid) {
                pages.zip(listOf(0xe53935, 0x43a047)).forEachIndexed { index, (page, expectedColor) ->
                    val artifact = storage.artifactPath(page.artifactRelativePath).toFile()
                    val bitmap = BitmapFactory.decodeFile(artifact.absolutePath)
                        ?: throw AssertionError("Valid PDF page $index did not produce a decodable private image")
                    try {
                        val centerColor = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff
                        assertEquals("Committed valid PDF page $index must retain its visual marker", expectedColor, centerColor)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } else {
                val imagePage = pages.single()
                val privateImageBytes = storage.artifactPath(imagePage.artifactRelativePath).toFile().readBytes()
                assertEquals("Valid JPEG byte count must match its provider source", image.bytes.size, privateImageBytes.size)
                assertEquals("Valid JPEG private bytes must exactly match its provider source",
                    sha256(image.bytes), sha256(privateImageBytes))
            }

            assertEquals("Only the valid-source snapshot and page artifacts may remain", expectedArtifacts,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM ArtifactRecord"))
            assertEquals("No PDF publication intent may remain", 0L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM PdfImportPublicationIntent"))
            assertEquals("No image publication intent may remain", 0L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM ImageImportPublicationIntent"))
            pickerSources.forEach { assertSourceBytesUnchanged(context, it) }
            assertEquals("Picker import must not persist temporary document grants", permissionsBefore,
                persistedPermissionUris(context))

            val partialStatus = observedStatuses.firstOrNull { it.startsWith(partialPrefix) }
            assertNotNull("The UI must report partial success; observed=$observedStatuses", partialStatus)
            assertTrue("Partial status must include a visible failure reason",
                requireNotNull(partialStatus).substringAfter("First error:").isNotBlank())
            val hierarchyFile = File(context.cacheDir, "cross-type-failure-$fixtureId.xml")
            val hierarchy = try {
                device.dumpWindowHierarchy(hierarchyFile)
                hierarchyFile.readText()
            } finally {
                hierarchyFile.delete()
            }
            val orderLabel = if (pdfFirst) "PDF,JPEG" else "JPEG,PDF"
            val validPositionLabel = if (validFirst) "valid-first" else "malformed-first"
            hierarchy.chunked(2_800).forEachIndexed { index, chunk ->
                android.util.Log.i(TAG, "CROSS_TYPE_FAILURE_UI order=$orderLabel part=$index xml=$chunk")
            }
            android.util.Log.i(TAG,
                "CROSS_TYPE_FAILURE_PASS order=$orderLabel position=$validPositionLabel fixture=$fixtureId valid=${validSource.displayName} " +
                    "malformed=${malformedSource.displayName} pages=${pages.size} " +
                    "provenance=${records.joinToString(",") { it.sourceProvenance }} " +
                    "sourcePageIndices=${records.joinToString(",") { it.sourcePageIndex.toString() }} " +
                    "validSha256=${sha256(validSource.bytes)} malformedSha256=${sha256(malformedSource.bytes)} " +
                    "sessions=1 artifacts=$expectedArtifacts pdfIntents=0 imageIntents=0 " +
                    "selectedPages=${pages.count { it.selected }} partialStatus=$partialStatus")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            runCatching { scenario?.close() }.onFailure { cleanupFailure = it }
            app.productStorageProviderForTest = oldProvider
            app.productStorageOverrideForTest = oldOverride
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            runCatching {
                cleanupFixtureDocuments(context, fixtureFolder, pickerSources)
                val artifactRoot = File(context.noBackupFilesDir, artifactDirectory)
                assertTrue("Cross-type failure artifact directory must be removed",
                    artifactRoot.deleteRecursively() || !artifactRoot.exists())
                assertTrue("Cross-type failure test database must be removed", context.deleteDatabase(databaseName))
                assertTrue("No cross-type failure fixture may remain", listFixtureDocuments(context, fixtureFolder).isEmpty())
                assertEquals("No app persisted grant may be added or removed", permissionsBefore,
                    persistedPermissionUris(context))
            }.onFailure { cleanupFailure = it }
            val cleanupProblem = cleanupFailure
            if (cleanupProblem != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupProblem)
                else throw AssertionError("Cross-type failure fixture cleanup failed", cleanupProblem)
            }
        }
    }

    @Test
    fun realImagePickerKeepsValidPngWhenFollowingMalformedJpegFails() {
        assertImagePickerFailureContinues("image/png", "image/jpeg", validFirst = true)
    }

    @Test
    fun realImagePickerContinuesWithValidPngAfterMalformedJpeg() {
        assertImagePickerFailureContinues("image/png", "image/jpeg", validFirst = false)
    }

    @Test
    fun realImagePickerKeepsValidPngWhenFollowingRecognizedMalformedJpegFails() {
        assertImagePickerFailureContinues("image/png", "image/jpeg", validFirst = true, malformedJpegTruncatedHeader = true)
    }

    @Test
    fun realImagePickerContinuesWithValidPngAfterRecognizedMalformedJpeg() {
        assertImagePickerFailureContinues("image/png", "image/jpeg", validFirst = false, malformedJpegTruncatedHeader = true)
    }

    @Test
    fun realImagePickerKeepsValidJpegWhenFollowingMalformedPngFails() {
        assertImagePickerFailureContinues("image/jpeg", "image/png", validFirst = true)
    }

    @Test
    fun realImagePickerContinuesWithValidJpegAfterMalformedPng() {
        assertImagePickerFailureContinues("image/jpeg", "image/png", validFirst = false)
    }

    private fun assertImagePickerFailureContinues(
        validMimeType: String,
        malformedMimeType: String,
        validFirst: Boolean,
        malformedJpegTruncatedHeader: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val fixtureId = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMvpImageFailure-$fixtureId"
        val malformedExtension = if (malformedMimeType == "image/png") "png" else "jpg"
        val validExtension = if (validMimeType == "image/png") "png" else "jpg"
        val malformedName = if (validFirst) "02-malformed-$fixtureId.$malformedExtension" else "01-malformed-$fixtureId.$malformedExtension"
        val validName = if (validFirst) "01-valid-$fixtureId.$validExtension" else "02-valid-$fixtureId.$validExtension"
        val databaseName = "image-failure-$fixtureId.db"
        val artifactDirectory = "image-failure-artifacts-$fixtureId"
        val permissionsBefore = persistedPermissionUris(context)
        val observedStatuses = CopyOnWriteArrayList<String>()
        val malformedBytes = when {
            malformedMimeType == "image/png" -> malformedPngWithoutImageData(0xffb75027.toInt())
            malformedMimeType == "image/jpeg" && malformedJpegTruncatedHeader -> malformedJpegWithTruncatedApp0()
            else -> byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        }
        if (malformedMimeType == "image/png") {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(malformedBytes, 0, malformedBytes.size, bounds)
            assertEquals("Malformed PNG fixture must be recognized", malformedMimeType, bounds.outMimeType)
            assertTrue("Malformed PNG bounds must remain readable", bounds.outWidth > 0 && bounds.outHeight > 0)
            assertNull("Malformed image data must fail full decoding",
                BitmapFactory.decodeByteArray(malformedBytes, 0, malformedBytes.size))
        }
        if (malformedJpegTruncatedHeader) {
            assertTrue("Malformed JPEG fixture must retain SOI and JFIF APP0 markers",
                malformedBytes.size == 12 && malformedBytes[0] == 0xff.toByte() && malformedBytes[1] == 0xd8.toByte() &&
                    malformedBytes[2] == 0xff.toByte() && malformedBytes[3] == 0xe0.toByte())
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(malformedBytes, 0, malformedBytes.size, bounds)
            assertTrue("Truncated APP0 JPEG must not yield usable bounds or MIME",
                bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType.isNullOrBlank())
            assertNull("Truncated JPEG metadata must not decode", BitmapFactory.decodeByteArray(malformedBytes, 0, malformedBytes.size))
        }
        val malformed = publishFixtureDocument(
            context,
            fixtureFolder,
            malformedName,
            malformedMimeType,
            malformedBytes,
        )
        val valid = publishFixtureImage(context, fixtureFolder, validName, validMimeType, 0xffb75027.toInt())
        val sources = if (validFirst) listOf(valid, malformed) else listOf(malformed, valid)
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            assertTrue("The isolated image-import workspace must start empty", storage.workspaceProjection().pages.isEmpty())
            assertEquals("Only the two test-owned image sources may exist", 2, listFixtureDocuments(context, fixtureFolder).size)
            sources.forEach { assertSourceBytesUnchanged(context, it) }
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity ->
                val status = requireNotNull(activity.window.decorView.findViewWithTag<TextView>("docscan-status"))
                status.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        observedStatuses += s?.toString().orEmpty()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            clickImport(scenario)

            val device = UiDevice.getInstance(instrumentation)
            chooseFixtureDocuments(device, fixtureFolder, sources.map { it.displayName })
            awaitActivityResumed(scenario)

            awaitCondition("The valid image must import even when the other selected image fails") {
                storage.workspaceProjection().pages.size == 1
            }
            val partialPrefix = context.getString(
                R.string.import_documents_partial, 1, 2, 1, "",
            ).substringBefore("First error:").trim()
            awaitCondition("The image-only picker must report one success and one failed source") {
                observedStatuses.any { it.startsWith(partialPrefix) }
            }

            val pages = storage.workspaceProjection().pages
            val page = pages.single()
            val records = pageRecords(context.getDatabasePath(databaseName), pages)
            val sessions = importSessionRecords(context.getDatabasePath(databaseName))
            val session = sessions.single()
            assertTrue("The valid PNG Page must remain selected", page.selected)
            assertEquals(listOf("$validMimeType:${valid.displayName}"), records.map { it.sourceProvenance })
            assertEquals("The valid source must own exactly one completed session", 1, sessions.size)
            assertEquals("COMPLETED", session.state)
            assertEquals(valid.displayName, session.displayName)
            assertEquals(sha256(valid.bytes), session.sourceSnapshotSha256)
            assertEquals(sha256(valid.bytes), privateSourceSha256(
                context.getDatabasePath(databaseName), artifactDirectory, session.sourceSnapshotArtifactId,
            ))
            val privateBytes = storage.artifactPath(page.artifactRelativePath).toFile().readBytes()
            assertEquals(valid.bytes.size, privateBytes.size)
            assertEquals(sha256(valid.bytes), sha256(privateBytes))
            val sourceBitmap = BitmapFactory.decodeByteArray(valid.bytes, 0, valid.bytes.size)
                ?: throw AssertionError("The valid source image must decode")
            val bitmap = BitmapFactory.decodeByteArray(privateBytes, 0, privateBytes.size)
                ?: throw AssertionError("The committed image Page must decode")
            try {
                val sourceCenterColor = sourceBitmap.getPixel(sourceBitmap.width / 2, sourceBitmap.height / 2) and 0x00ffffff
                val centerColor = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) and 0x00ffffff
                assertEquals("Private Page pixels must match the decoded valid source", sourceCenterColor, centerColor)
            } finally {
                sourceBitmap.recycle()
                bitmap.recycle()
            }
            assertEquals("Only the valid PNG image artifact may be owned", 1L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM ArtifactRecord"))
            assertEquals("No failed image publication intent may remain", 0L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM ImageImportPublicationIntent"))
            assertEquals("No PDF publication intent may remain", 0L,
                databaseScalar(context.getDatabasePath(databaseName), "SELECT COUNT(*) FROM PdfImportPublicationIntent"))
            sources.forEach { assertSourceBytesUnchanged(context, it) }
            assertEquals(permissionsBefore, persistedPermissionUris(context))

            val partialStatus = observedStatuses.firstOrNull { it.startsWith(partialPrefix) }
            assertNotNull("Partial status must be visible; observed=$observedStatuses", partialStatus)
            assertTrue("Partial status must include a nonempty failure reason",
                requireNotNull(partialStatus).substringAfter("First error:").isNotBlank())
            if (malformedMimeType == "image/png") {
                assertTrue("A recognized malformed PNG must fail in the image decoder: $partialStatus",
                    requireNotNull(partialStatus).contains("Imported image cannot be decoded"))
            }
            if (malformedJpegTruncatedHeader) {
                assertTrue("A truncated JPEG header must be rejected during image bounds validation: $partialStatus",
                    requireNotNull(partialStatus).contains("Imported file is not a supported image"))
            }
            val hierarchyFile = File(context.cacheDir, "image-picker-failure-$fixtureId.xml")
            val hierarchy = try {
                device.dumpWindowHierarchy(hierarchyFile)
                hierarchyFile.readText()
            } finally {
                hierarchyFile.delete()
            }
            hierarchy.chunked(2_800).forEachIndexed { index, chunk ->
                android.util.Log.i(TAG, "IMAGE_PICKER_FAILURE_UI validFirst=$validFirst part=$index xml=$chunk")
            }
            android.util.Log.i(TAG,
                "IMAGE_PICKER_FAILURE_PASS validMime=$validMimeType malformedMime=$malformedMimeType validFirst=$validFirst valid=${valid.displayName} malformed=${malformed.displayName} " +
                    "page=${page.pageId.value} provenance=${records.single().sourceProvenance} sourceSha256=${sha256(valid.bytes)} " +
                    "privateSha256=${sha256(privateBytes)} sessions=1 artifacts=1 imageIntents=0 pdfIntents=0 " +
                    "selectedPages=1 partialStatus=$partialStatus")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            runCatching { scenario?.close() }.onFailure { cleanupFailure = it }
            app.productStorageProviderForTest = oldProvider
            app.productStorageOverrideForTest = oldOverride
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            runCatching {
                cleanupFixtureDocuments(context, fixtureFolder, sources)
                val artifactRoot = File(context.noBackupFilesDir, artifactDirectory)
                assertTrue("Image failure artifact directory must be removed",
                    artifactRoot.deleteRecursively() || !artifactRoot.exists())
                assertTrue("Image failure database must be removed", context.deleteDatabase(databaseName))
                assertTrue("No image failure fixture may remain", listFixtureDocuments(context, fixtureFolder).isEmpty())
                assertEquals("No app persisted grant may be added or removed", permissionsBefore,
                    persistedPermissionUris(context))
            }.onFailure { cleanupFailure = it }
            val cleanupProblem = cleanupFailure
            if (cleanupProblem != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupProblem)
                else throw AssertionError("Image picker failure fixture cleanup failed", cleanupProblem)
            }
        }
    }

    @Test
    fun realProductPickerImportsJpegAndPngInPickerOrderWithExactProvenanceAndBytes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val fixtureId = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMvpImport-$fixtureId"
        val databaseName = "multi-image-picker-$fixtureId.db"
        val artifactDirectory = "multi-image-picker-artifacts-$fixtureId"
        val permissionsBefore = persistedPermissionUris(context)
        val ocrStarted = CountDownLatch(1)
        val allowOcrToFinish = CountDownLatch(1)
        val gatedOcrAdapter = TesseractOcrAdapter(context) {
            if (ocrStarted.count > 0L) {
                ocrStarted.countDown()
                check(allowOcrToFinish.await(60, TimeUnit.SECONDS)) {
                    "Test did not release OCR progress after checking the import status"
                }
            }
        }
        val sources = mutableListOf<SourceDocument>()
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        val oldOcrAdapter = app.ocrAdapterOverrideForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            assertTrue("The isolated workspace must start empty", storage.workspaceProjection().pages.isEmpty())
            sources += publishFixtureImage(context, fixtureFolder, "01-docscan-$fixtureId.jpg", "image/jpeg", 0xff244a82.toInt())
            sources += publishFixtureImage(context, fixtureFolder, "02-docscan-$fixtureId.png", "image/png", 0xffb75027.toInt())
            assertEquals(2, listFixtureDocuments(context, fixtureFolder).size)
            sources.forEach { assertSourceBytesUnchanged(context, it) }

            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            app.ocrAdapterOverrideForTest = gatedOcrAdapter
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            clickImport(scenario)

            val device = UiDevice.getInstance(instrumentation)
            chooseFixtureDocuments(device, fixtureFolder, sources.map { it.displayName })
            awaitActivityResumed(scenario)

            awaitCondition("Exactly two selected images must commit as Pages") {
                storage.workspaceProjection().pages.size == sources.size
            }
            val pages = storage.workspaceProjection().pages
            assertEquals("Image imports must append in the order returned by the real picker", sources.size, pages.size)
            assertEquals("Page IDs must remain independent", pages.map { it.pageId }.distinct().size, pages.size)
            assertEquals("ImageVersion IDs must remain independent", pages.map { it.imageVersionId }.distinct().size, pages.size)

            val pageRecords = pageRecords(context.getDatabasePath(databaseName), pages)
            val sessions = importSessionRecords(context.getDatabasePath(databaseName))
            assertEquals("Each picked image must retain its matching source name and MIME in picker order",
                sources.map { "${it.mimeType}:${it.displayName}" }, pageRecords.map { it.sourceProvenance })
            assertEquals("Each source must have one independent completed ImportSession", sources.size,
                sessions.size)
            assertTrue("Every per-image ImportSession must be COMPLETED",
                sessions.all { it.state == "COMPLETED" })
            assertEquals("ImportSession names must retain the picker order", sources.map { it.displayName },
                sessions.map { it.displayName })
            assertEquals("Page order must correspond to the sequential ImportSessions",
                sessions.map { it.importSessionId }, pageRecords.map { it.importSessionId })
            assertEquals("Visible Page order indices must remain strictly increasing",
                pages.map { it.orderIndex }.sorted(), pages.map { it.orderIndex })

            pages.zip(sources).forEach { (page, source) ->
                val privateBytes = storage.artifactPath(page.artifactRelativePath).toFile().readBytes()
                assertEquals("Private import must retain source byte count for ${source.displayName}", source.bytes.size, privateBytes.size)
                assertEquals("Private import must retain source SHA-256 for ${source.displayName}", sha256(source.bytes), sha256(privateBytes))
                assertSourceBytesUnchanged(context, source)
            }
            assertTrue(
                "Production OCR must reach native progress before import-completion is asserted",
                ocrStarted.await(30, TimeUnit.SECONDS),
            )
            awaitCondition("ProductActivity must report successful two-image import") {
                statusText(scenario) == context.getString(R.string.import_documents_complete, 2, 2)
            }
            android.util.Log.i(TAG, "IMPORT_STATUS_BEFORE_OCR_RELEASE pages=${pages.size} status=${statusText(scenario)}")
            assertEquals("Picker import must not persist temporary document grants", permissionsBefore, persistedPermissionUris(context))
            allowOcrToFinish.countDown()
            awaitCondition("Both imported images must receive committed current Recognition") {
                storage.workspaceProjection().pages.all { !it.recognizedText.isNullOrBlank() }
            }
            android.util.Log.i(
                TAG,
                "IMPORT_PICKER_PASS fixture=$fixtureId names=${sources.joinToString(",") { it.displayName }} " +
                    "mimes=${sources.joinToString(",") { it.mimeType }} sha256=${sources.joinToString(",") { sha256(it.bytes) }} " +
                    "page_ids=${pages.joinToString(",") { it.pageId.value }} image_versions=${pages.joinToString(",") { it.imageVersionId.value }}",
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            allowOcrToFinish.countDown()
            runCatching {
                app.cancelOcrForPages(storage.workspaceProjection().pages.map { it.pageId })
                val ocrIdle = CountDownLatch(1)
                app.enqueueOcr(PageId.new()) { _, _ -> ocrIdle.countDown() }
                check(ocrIdle.await(60, TimeUnit.SECONDS)) { "Test OCR executor did not drain during teardown" }
            }.onFailure { cleanupFailure = it }
            runCatching { scenario?.close() }.onFailure { cleanupFailure = it }
            app.productStorageProviderForTest = oldProvider
            app.productStorageOverrideForTest = oldOverride
            app.ocrAdapterOverrideForTest = oldOcrAdapter
            runCatching { gatedOcrAdapter.close() }.onFailure { cleanupFailure = it }
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            runCatching {
                cleanupFixtureDocuments(context, fixtureFolder, sources)
                assertTrue("Test artifact directory must be removed", File(context.noBackupFilesDir, artifactDirectory).deleteRecursively() || !File(context.noBackupFilesDir, artifactDirectory).exists())
                assertTrue("Test database must be removed", context.deleteDatabase(databaseName))
                assertTrue("No fixture document may remain", listFixtureDocuments(context, fixtureFolder).isEmpty())
                assertEquals("No app persisted grant may be added or removed", permissionsBefore, persistedPermissionUris(context))
            }.onFailure { cleanupFailure = it }
            val cleanupProblem = cleanupFailure
            if (cleanupProblem != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupProblem)
                else throw AssertionError("Multi-image picker fixture cleanup failed", cleanupProblem)
            }
        }
    }

    @Test
    fun cancellingRealProductPickerLeavesWorkspacePermissionsAndFixtureBytesUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val fixtureId = UUID.randomUUID().toString()
        val fixtureFolder = "DocScanMvpImportCancel-$fixtureId"
        val databaseName = "multi-image-picker-cancel-$fixtureId.db"
        val artifactDirectory = "multi-image-picker-cancel-artifacts-$fixtureId"
        val sources = mutableListOf<SourceDocument>()
        val permissionsBefore = persistedPermissionUris(context)
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val oldOverride = app.productStorageOverrideForTest
        val oldProvider = app.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            sources += publishFixtureImage(context, fixtureFolder, "01-cancel-$fixtureId.jpg", "image/jpeg", 0xff3c7b46.toInt())
            sources += publishFixtureImage(context, fixtureFolder, "02-cancel-$fixtureId.png", "image/png", 0xff70409a.toInt())
            val before = storage.workspaceProjection()
            val beforePages = before.pages.map { it.pageId to it.imageVersionId }
            sources.forEach { assertSourceBytesUnchanged(context, it) }
            app.productStorageProviderForTest = null
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            clickImport(scenario)

            val device = UiDevice.getInstance(instrumentation)
            assertTrue("The real DocumentsUI picker must launch",
                device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            cancelDocumentsPicker(device)
            awaitActivityResumed(scenario)

            val after = storage.workspaceProjection()
            assertEquals("Cancel must preserve order revision", before.orderRevision, after.orderRevision)
            assertEquals("Cancel must preserve Page/ImageVersion identities", beforePages,
                after.pages.map { it.pageId to it.imageVersionId })
            assertEquals("Cancel must not add Page rows", 0, pageRecords(context.getDatabasePath(databaseName), after.pages).size)
            assertEquals("Cancel must not change app persisted URI grants", permissionsBefore, persistedPermissionUris(context))
            sources.forEach { assertSourceBytesUnchanged(context, it) }
            val artifactEntries = File(context.noBackupFilesDir, artifactDirectory).listFiles().orEmpty().map { it.name }
            assertTrue("Cancel must not materialize private Page artifacts; entries=$artifactEntries",
                artifactEntries.all { it == ".nomedia" || it == ".cleanup-arbiter.lock" })
            android.util.Log.i(TAG, "IMPORT_PICKER_CANCEL_PASS fixture=$fixtureId pages=0 grants_unchanged=true source_bytes_unchanged=true")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            runCatching { scenario?.close() }.onFailure { cleanupFailure = it }
            app.productStorageProviderForTest = oldProvider
            app.productStorageOverrideForTest = oldOverride
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            runCatching {
                cleanupFixtureDocuments(context, fixtureFolder, sources)
                assertTrue("Test artifact directory must be removed", File(context.noBackupFilesDir, artifactDirectory).deleteRecursively() || !File(context.noBackupFilesDir, artifactDirectory).exists())
                assertTrue("Test database must be removed", context.deleteDatabase(databaseName))
                assertTrue("No fixture document may remain", listFixtureDocuments(context, fixtureFolder).isEmpty())
                assertEquals("No app persisted grant may be added or removed", permissionsBefore, persistedPermissionUris(context))
            }.onFailure { cleanupFailure = it }
            val cleanupProblem = cleanupFailure
            if (cleanupProblem != null) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupProblem)
                else throw AssertionError("Cancelled picker fixture cleanup failed", cleanupProblem)
            }
        }
    }

    private fun selectDocumentsDestination(device: UiDevice) {
        assertTrue("The real SAF destination picker must launch",
            device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
        val useFolder = By.text(Pattern.compile("(?i)^use this folder$"))
        val allow = By.text(Pattern.compile("(?i)^allow$"))
        SafPickerTestActions.openPrimaryDirectory(device, listOf("Documents"))
        SafPickerTestActions.clickFresh(
            device,
            useFolder,
            "Use Documents destination",
            completed = { device.hasObject(allow) || device.hasObject(By.pkg("app.docscan.scanner")) },
        )
        if (device.wait(Until.hasObject(allow), 3_000L)) {
            SafPickerTestActions.clickFresh(
                device,
                allow,
                "Allow Documents destination access",
                timeoutMs = 3_000L,
                completed = { device.hasObject(By.pkg("app.docscan.scanner")) },
            )
        }
        assertTrue("The destination picker must return to ProductActivity",
            device.wait(Until.hasObject(By.pkg("app.docscan.scanner")), TIMEOUT_MILLIS))
    }

    private fun providerChildren(context: Context, tree: Uri): List<Pair<Uri, String>> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        return context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            buildList {
                while (cursor.moveToNext()) {
                    add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn)) to cursor.getString(nameColumn))
                }
            }
        } ?: throw AssertionError("Could not enumerate the test destination provider")
    }

    private fun assertColorNear(label: String, actualColor: Int, expectedRgb: Int) {
        val actual = actualColor and 0x00ffffff
        val channels = listOf(16, 8, 0)
        channels.forEach { shift ->
            val actualChannel = (actual shr shift) and 0xff
            val expectedChannel = (expectedRgb shr shift) and 0xff
            assertTrue("$label pixel #${actual.toString(16)} should match #${expectedRgb.toString(16)} within JPEG/render tolerance",
                kotlin.math.abs(actualChannel - expectedChannel) <= 35)
        }
    }

    private fun assertSampledImageMatches(label: String, sourceFile: File, output: Bitmap) {
        val source = ProductImageDecoder.decode(sourceFile, 12L * 1024L * 1024L)
            ?: throw AssertionError("$label source image must decode")
        try {
            val fractions = listOf(0.15f, 0.5f, 0.85f)
            fractions.forEach { yFraction ->
                fractions.forEach { xFraction ->
                    val sourceColor = source.getPixel(
                        (source.width * xFraction).toInt().coerceIn(0, source.width - 1),
                        (source.height * yFraction).toInt().coerceIn(0, source.height - 1),
                    )
                    val outputColor = output.getPixel(
                        (output.width * xFraction).toInt().coerceIn(0, output.width - 1),
                        (output.height * yFraction).toInt().coerceIn(0, output.height - 1),
                    )
                    listOf(16, 8, 0).forEach { shift ->
                        val sourceChannel = (sourceColor shr shift) and 0xff
                        val outputChannel = (outputColor shr shift) and 0xff
                        assertTrue("$label pixel differs more than lossy-JPEG tolerance", kotlin.math.abs(sourceChannel - outputChannel) <= 90)
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun assertSampledPdfPageMatches(label: String, sourceFile: File, pageRaster: Bitmap) {
        val source = ProductImageDecoder.decode(sourceFile, 12L * 1024L * 1024L)
            ?: throw AssertionError("$label source image must decode")
        try {
            val sourceAspect = source.width.toFloat() / source.height
            val pageAspect = pageRaster.width.toFloat() / pageRaster.height
            val imageWidthFraction = if (sourceAspect < pageAspect) sourceAspect / pageAspect else 1f
            val imageHeightFraction = if (sourceAspect > pageAspect) pageAspect / sourceAspect else 1f
            val offsetX = (1f - imageWidthFraction) / 2f
            val offsetY = (1f - imageHeightFraction) / 2f
            listOf(0.15f, 0.5f, 0.85f).forEach { yFraction ->
                listOf(0.15f, 0.5f, 0.85f).forEach { xFraction ->
                    val sourceColor = source.getPixel(
                        (source.width * xFraction).toInt().coerceIn(0, source.width - 1),
                        (source.height * yFraction).toInt().coerceIn(0, source.height - 1),
                    )
                    val pageColor = pageRaster.getPixel(
                        (pageRaster.width * (offsetX + imageWidthFraction * xFraction)).toInt().coerceIn(0, pageRaster.width - 1),
                        (pageRaster.height * (offsetY + imageHeightFraction * yFraction)).toInt().coerceIn(0, pageRaster.height - 1),
                    )
                    listOf(16, 8, 0).forEach { shift ->
                        val sourceChannel = (sourceColor shr shift) and 0xff
                        val pageChannel = (pageColor shr shift) and 0xff
                        assertTrue(
                            "$label sample ($xFraction,$yFraction shift=$shift source=$sourceChannel page=$pageChannel " +
                                "sourceSize=${source.width}x${source.height} pageSize=${pageRaster.width}x${pageRaster.height} " +
                                "content=${imageWidthFraction}x${imageHeightFraction} offset=${offsetX},${offsetY}) " +
                                "differs more than PDF/JPEG raster tolerance",
                            kotlin.math.abs(sourceChannel - pageChannel) <= 130,
                        )
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun <T : Any> awaitValue(message: String, timeoutMs: Long = TIMEOUT_MILLIS, value: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            value()?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out: $message")
    }

    private fun clickImport(scenario: ActivityScenario<ProductActivity>) {
        scenario.onActivity { activity ->
            val button = activity.window.decorView.findViewWithTag<View>("docscan-import-button")
            assertNotNull("ProductActivity import action must be present", button)
            assertTrue("ProductActivity import action must be enabled", requireNotNull(button).isEnabled)
            button.performClick()
        }
    }

    private fun chooseFixtureDocuments(device: UiDevice, folder: String, displayNames: List<String>) {
        assertTrue("The real DocumentsUI picker must launch",
            device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
        navigateToDownloadsRoot(device)
        val fixtureCard = By.res(DOCUMENTS_UI, "item_root").hasDescendant(By.text(folder))
        clickFresh(device, fixtureCard, "Unique MediaStore fixture folder")
        assertTrue("DocumentsUI must show the unique fixture folder breadcrumb",
            device.wait(Until.hasObject(By.res(DOCUMENTS_UI, "breadcrumb_text").text(folder)), TIMEOUT_MILLIS))
        displayNames.forEachIndexed { index, name ->
            val sourceCard = By.res(DOCUMENTS_UI, "item_root").hasDescendant(By.text(name))
            clickFresh(device, sourceCard, "Picker item '$name'", longClick = index == 0)
            SystemClock.sleep(200)
        }
        clickFresh(device, By.text(Pattern.compile("(?i)^(open|select)$")), "DocumentsUI multi-select confirmation")
    }

    private fun ensureDownloadsRoot(device: UiDevice) {
        var breadcrumbs = currentPickerBreadcrumbs(device)
        var unchangedBackPresses = 0
        assertTrue("DocumentsUI must identify the Downloads location", breadcrumbs.firstOrNull() == "Downloads")
        while (breadcrumbs.size > 1) {
            val previousDepth = breadcrumbs.size
            device.pressBack()
            val previousBreadcrumbs = breadcrumbs
            val nextBreadcrumbs = awaitValue("DocumentsUI returns to the Downloads root or closes its roots drawer") {
                currentPickerBreadcrumbs(device).takeIf {
                    it.firstOrNull() == "Downloads" &&
                        (it.size < previousDepth || (it == previousBreadcrumbs && !device.hasObject(downloadsRootSelector())))
                }
            }
            if (nextBreadcrumbs == previousBreadcrumbs) {
                unchangedBackPresses += 1
                assertTrue("Back must close at most one roots drawer before navigating up", unchangedBackPresses <= 1)
            } else {
                unchangedBackPresses = 0
            }
            breadcrumbs = nextBreadcrumbs
        }
        assertEquals("Picker fixture selection starts at the Downloads root", listOf("Downloads"), breadcrumbs)
    }

    private fun navigateToDownloadsRoot(device: UiDevice) {
        val downloadsRoot = downloadsRootSelector()
        if (currentPickerBreadcrumbs(device).firstOrNull() != "Downloads") {
            SafPickerTestActions.clickFresh(
                device,
                By.desc("Show roots"),
                "DocumentsUI roots control",
                completed = { device.hasObject(downloadsRoot) },
            )
            SafPickerTestActions.clickFresh(
                device,
                downloadsRoot,
                "Downloads root",
                completed = { currentPickerBreadcrumbs(device).firstOrNull() == "Downloads" },
            )
        }
        ensureDownloadsRoot(device)
    }

    private fun currentPickerBreadcrumbs(device: UiDevice): List<String> {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var lastStale: StaleObjectException? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                return device.findObjects(By.res(DOCUMENTS_UI, "breadcrumb_text"))
                    .mapNotNull { it.text?.toString() }
            } catch (stale: StaleObjectException) {
                lastStale = stale
                SystemClock.sleep(50)
            }
        }
        throw AssertionError("DocumentsUI breadcrumb snapshot remained stale", lastStale)
    }

    private fun downloadsRootSelector(): BySelector = By.res("android", "title").text("Downloads")

    private fun clickFresh(device: UiDevice, selector: BySelector, label: String, longClick: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        var lastStale: StaleObjectException? = null
        var foundCount = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val item = device.findObject(selector)
            if (item != null) {
                foundCount += 1
                try {
                    if (longClick) item.longClick() else item.click()
                    return
                } catch (stale: StaleObjectException) {
                    lastStale = stale
                }
            }
            SystemClock.sleep(250)
        }
        val hierarchyFile = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "multi-image-picker-ui-failure.xml")
        runCatching {
            device.dumpWindowHierarchy(hierarchyFile)
            android.util.Log.e(TAG, "PICKER_UI_FAILURE label=$label package=${device.currentPackageName} found=$foundCount " +
                "stale=${lastStale != null} hierarchy=${hierarchyFile.readText()}")
        }.onFailure { android.util.Log.e(TAG, "Could not record picker UI failure hierarchy", it) }
        throw AssertionError("$label did not remain available long enough to click (found=$foundCount, stale=${lastStale != null})", lastStale)
    }

    private fun cancelDocumentsPicker(device: UiDevice) {
        repeat(3) {
            if (device.wait(Until.gone(By.pkg(DOCUMENTS_UI)), 750)) return
            device.pressBack()
            if (device.wait(Until.gone(By.pkg(DOCUMENTS_UI)), 2_000)) return
        }
        throw AssertionError("DocumentsUI must close after Back dismisses any open drawer or location")
    }

    private fun awaitActivityReady(scenario: ActivityScenario<ProductActivity>) {
        awaitCondition("ProductActivity import action must initialize") {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.window.decorView.findViewWithTag<View>("docscan-import-button")?.isEnabled == true
            }
            ready
        }
    }

    private fun awaitActivityResumed(scenario: ActivityScenario<ProductActivity>) {
        awaitCondition("The same ProductActivity must return RESUMED from DocumentsUI") {
            var resumed = false
            scenario.onActivity { activity ->
                resumed = activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            resumed
        }
    }

    private fun statusText(scenario: ActivityScenario<ProductActivity>): String {
        var text = ""
        scenario.onActivity { activity ->
            text = activity.window.decorView.findViewWithTag<TextView>("docscan-status")?.text?.toString().orEmpty()
        }
        return text
    }

    private fun publishFixtureImage(
        context: Context,
        fixtureFolder: String,
        displayName: String,
        mimeType: String,
        color: Int,
    ): SourceDocument {
        val bytes = fixtureImageBytes(mimeType, color)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$fixtureFolder/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Could not create test-owned MediaStore source $displayName"
        }
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not write test-owned MediaStore source $displayName")
            val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            assertEquals(1, context.contentResolver.update(uri, published, null, null))
        } catch (failure: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw failure
        }
        return SourceDocument(uri, displayName, mimeType, bytes)
    }

    private fun fixtureImageBytes(mimeType: String, color: Int): ByteArray {
        val format = if (mimeType == "image/jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        val bitmap = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, if ((x / 12 + y / 16) % 2 == 0) color else (color xor 0x00202020))
            }
        }
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(format, 91, output))
            output.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }

    private fun malformedPngWithoutImageData(color: Int): ByteArray {
        val valid = fixtureImageBytes("image/png", color)
        val output = ByteArrayOutputStream()
        output.write(valid, 0, 33) // PNG signature and complete IHDR chunk.
        output.write(byteArrayOf(0, 0, 0, 0)) // Empty IDAT payload.
        val idatType = "IDAT".toByteArray(Charsets.US_ASCII)
        output.write(idatType)
        val crc = CRC32().apply { update(idatType) }.value
        output.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        output.write(valid, valid.size - 12, 12) // Preserve the valid IEND chunk.
        return output.toByteArray()
    }

    private fun malformedJpegWithTruncatedApp0(): ByteArray {
        val valid = fixtureImageBytes("image/jpeg", 0xffb75027.toInt())
        check(valid.size > 12 && valid[0] == 0xff.toByte() && valid[1] == 0xd8.toByte() &&
            valid[2] == 0xff.toByte() && valid[3] == 0xe0.toByte()) {
            "JPEG fixture must start with the SOI marker"
        }
        return valid.copyOf(12) // SOI and APP0 marker/length are present; APP0 payload is truncated.
    }

    private fun listFixtureDocuments(context: Context, fixtureFolder: String): List<Uri> {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$fixtureFolder/"
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            buildList {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) add(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn).toString()))
            }
        } ?: emptyList()
    }

    private fun cleanupFixtureDocuments(context: Context, fixtureFolder: String, sources: List<SourceDocument>) {
        sources.forEach { source ->
            val deleted = context.contentResolver.delete(source.uri, null, null)
            check(deleted == 1 || listFixtureDocuments(context, fixtureFolder).none { it == source.uri }) {
                "Could not remove only test-owned source ${source.displayName}"
            }
        }
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fixtureFolder,
        )
        check(!directory.exists() || directory.delete()) { "Could not remove only test-owned fixture directory $fixtureFolder" }
        check(!directory.exists()) { "Test-owned fixture directory remains after cleanup: $fixtureFolder" }
    }

    private fun assertSourceBytesUnchanged(context: Context, source: SourceDocument) {
        val bytes = context.contentResolver.openInputStream(source.uri)?.use { it.readBytes() }
            ?: throw AssertionError("Test source disappeared: ${source.displayName}")
        assertEquals("Source byte count changed: ${source.displayName}", source.bytes.size, bytes.size)
        assertEquals("Source SHA-256 changed: ${source.displayName}", sha256(source.bytes), sha256(bytes))
        assertEquals(source.mimeType, context.contentResolver.getType(source.uri))
    }

    private fun persistedPermissionUris(context: Context): Set<String> =
        context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSortedSet()

    private fun pageRecords(databaseFile: File, pages: List<StoredPage>): List<PageRecord> {
        if (!databaseFile.exists()) return emptyList()
        val pageIds = pages.map { it.pageId.value }.toSet()
        if (pageIds.isEmpty()) return emptyList()
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(
                "SELECT page_id, import_session_id, source_provenance, order_index, source_page_index FROM Page WHERE tombstone = 0 ORDER BY order_index",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val pageId = cursor.getString(0)
                        if (pageId in pageIds) add(PageRecord(pageId, if (cursor.isNull(1)) null else cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                            if (cursor.isNull(4)) null else cursor.getLong(4)))
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    private fun currentRecognitionVersion(databaseFile: File, pageId: String): String? {
        if (!databaseFile.exists()) return null
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(
                "SELECT r.image_version_id FROM Page p JOIN Recognition r ON r.recognition_id = p.current_recognition_id " +
                    "WHERE p.page_id = ? AND p.tombstone = 0",
                arrayOf(pageId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } finally {
            db.close()
        }
    }

    private fun performCropCornerAction(
        instrumentation: android.app.Instrumentation,
        description: String,
        action: Int,
    ): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val node = findAccessibilityNodeByDescription(root, description) ?: return false
        return node.performAction(action)
    }

    private fun findAccessibilityNodeByDescription(
        node: android.view.accessibility.AccessibilityNodeInfo,
        description: String,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.contentDescription?.toString() == description) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findAccessibilityNodeByDescription(child, description)?.let { return it }
        }
        return null
    }

    private fun importSessionRecords(databaseFile: File): List<ImportSessionRecord> {
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(
                "SELECT i.import_session_id, i.state, i.source_display_name, i.source_snapshot_artifact_id, a.checksum " +
                    "FROM ImportSession i LEFT JOIN ArtifactRecord a ON a.artifact_id = i.source_snapshot_artifact_id ORDER BY i.rowid",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(ImportSessionRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getString(3), cursor.getString(4)))
                }
            }
        } finally {
            db.close()
        }
    }

    private fun createFixturePdf(
        context: Context,
        folder: String,
        displayName: String,
        pageColors: List<Int> = EXPECTED_PDF_PAGE_COLORS,
        pageTexts: List<String> = emptyList(),
    ): SourceDocument {
        require(pageTexts.isEmpty() || pageTexts.size == pageColors.size) {
            "PDF text fixture labels must be absent or align one-to-one with page colors"
        }
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream().use { output ->
            PDDocument().use { document ->
                pageColors.forEachIndexed { index, rgb ->
                    val page = PDPage(PDRectangle(300f, 400f))
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.setNonStrokingColor((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)
                        content.addRect(0f, 0f, 300f, 400f)
                        content.fill()
                        pageTexts.getOrNull(index)?.let { label ->
                            content.setNonStrokingColor(0, 0, 0)
                            content.beginText()
                            content.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                            content.newLineAtOffset(18f, 370f)
                            content.showText(label)
                            content.endText()
                        }
                    }
                }
                document.save(output)
            }
            output.toByteArray()
        }
        return publishFixtureDocument(context, folder, displayName, "application/pdf", bytes)
    }

    private fun ocrFixtureImageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(1800, 520, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = 104f
            }
            canvas.drawText("Документ № 8472", 70f, 190f, paint)
            canvas.drawText("Invoice 8472", 70f, 390f, paint)
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun publishFixtureDocument(
        context: Context,
        folder: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): SourceDocument {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
            "Could not create test-owned document source $displayName"
        }
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IllegalStateException("Could not write test-owned PDF source $displayName")
            assertEquals(1, context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
        } catch (failure: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw failure
        }
        return SourceDocument(uri, displayName, mimeType, bytes)
    }

    private fun databaseScalar(databaseFile: File, sql: String): Long {
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery(sql, null).use { cursor ->
                assertTrue("SQLite count query must return a row: $sql", cursor.moveToFirst())
                cursor.getLong(0)
            }
        } finally {
            db.close()
        }
    }

    private fun privateSourceSha256(databaseFile: File, artifactDirectory: String, artifactId: String?): String {
        requireNotNull(artifactId) { "Completed PDF ImportSession must retain its source snapshot artifact identity" }
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery("SELECT relative_storage_path, size_bytes, checksum FROM ArtifactRecord WHERE artifact_id = ?",
                arrayOf(artifactId)).use { cursor ->
                assertTrue("Private source snapshot ArtifactRecord must exist", cursor.moveToFirst())
                val relativePath = cursor.getString(0)
                val expectedSize = cursor.getLong(1)
                val expectedChecksum = cursor.getString(2)
                val artifact = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                    "$artifactDirectory/$relativePath")
                assertTrue("Private source snapshot must remain present after completion", artifact.isFile)
                assertEquals(expectedSize, artifact.length())
                val actual = sha256(artifact.readBytes())
                assertEquals(expectedChecksum, actual)
                actual
            }
        } finally {
            db.close()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun awaitCondition(message: String, timeoutMs: Long = TIMEOUT_MILLIS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun awaitMixedImportReady(
        context: Context,
        storage: ProductStorage,
        databaseFile: File,
        scenario: ActivityScenario<ProductActivity>,
        device: UiDevice,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val pages = try {
                storage.workspaceProjection().pages
            } catch (failure: Throwable) {
                val diagnostic = mixedImportTimeoutDiagnostics(context, storage, databaseFile, scenario, device)
                android.util.Log.e(TAG, "MIXED_IMPORT_OBSERVATION_FAILURE ${failure.javaClass.simpleName}:${failure.message} $diagnostic")
                throw AssertionError(
                    "The image and two PDF pages must import and bind current text; " +
                        "workspace observation failed: ${failure.javaClass.simpleName}:${failure.message}; $diagnostic",
                    failure,
                )
            }
            if (pages.size == 3 && pages.all { !it.recognizedText.isNullOrBlank() }) return
            SystemClock.sleep(50)
        }
        val diagnostic = mixedImportTimeoutDiagnostics(context, storage, databaseFile, scenario, device)
        android.util.Log.e(TAG, diagnostic)
        throw AssertionError("The image and two PDF pages must import and bind current text; $diagnostic")
    }

    private fun mixedImportTimeoutDiagnostics(
        context: Context,
        storage: ProductStorage,
        databaseFile: File,
        scenario: ActivityScenario<ProductActivity>,
        device: UiDevice,
    ): String {
        val projectionResult = runCatching { storage.workspaceProjection().pages }
        val pages = projectionResult.getOrElse { emptyList() }
        val records = runCatching { pageRecords(databaseFile, pages) }.getOrElse { emptyList() }
        val pageRows = runCatching {
            if (!databaseFile.isFile) return@runCatching "database=absent"
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery(
                    "SELECT p.page_id, p.order_index, p.selected, p.import_session_id, p.source_provenance, " +
                        "p.source_page_index, p.current_image_version_id, p.current_recognition_id, r.image_version_id " +
                        "FROM Page p LEFT JOIN Recognition r ON r.recognition_id = p.current_recognition_id " +
                        "WHERE p.tombstone = 0 ORDER BY p.order_index",
                    null,
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                "page=${cursor.getString(0)} order=${cursor.getLong(1)} selected=${cursor.getInt(2)} " +
                                    "session=${cursor.getString(3)} provenance=${cursor.getString(4)} " +
                                    "sourcePageIndex=${if (cursor.isNull(5)) null else cursor.getLong(5)} " +
                                    "currentImageVersion=${cursor.getString(6)} currentRecognition=${cursor.getString(7)} " +
                                    "recognitionImageVersion=${cursor.getString(8)}",
                            )
                        }
                    }.joinToString("; ")
                }
            }
        }.getOrElse { "databaseQueryFailure=${it.javaClass.simpleName}:${it.message}" }
        val pageText = pages.mapIndexed { index, page ->
            "projection[$index]{id=${page.pageId.value},order=${page.orderIndex},selected=${page.selected}," +
                "imageVersion=${page.imageVersionId.value},textLength=${page.recognizedText?.length ?: 0}," +
                "text=${page.recognizedText?.take(160)}}"
        }.joinToString("; ")
        val sessions = runCatching { importSessionRecords(databaseFile) }
            .map { rows -> rows.joinToString("; ") { it.toString() } }
            .getOrElse { "sessionQueryFailure=${it.javaClass.simpleName}:${it.message}" }
        val status = runCatching { statusText(scenario) }
            .getOrElse { "statusReadFailure=${it.javaClass.simpleName}:${it.message}" }
        val hierarchy = runCatching {
            val file = File(context.cacheDir, "mixed-import-timeout-hierarchy-${System.currentTimeMillis()}.xml")
            device.dumpWindowHierarchy(file)
            file.readText().also { file.delete() }.take(12_000)
        }.getOrElse { "hierarchyReadFailure=${it.javaClass.simpleName}:${it.message}" }
        val projectionFailure = projectionResult.exceptionOrNull()?.let {
            "${it.javaClass.simpleName}:${it.message}"
        } ?: "none"
        return "MIXED_IMPORT_TIMEOUT projectionFailure=$projectionFailure visiblePages=${pages.size} projection=[$pageText] " +
            "pageRecords=$records databasePages=[$pageRows] importSessions=[$sessions] " +
            "status=${status.take(500)} uiHierarchy=${hierarchy.replace('\n', ' ').take(12_000)}"
    }

    private fun activeUiHierarchyDiagnostic(context: Context, device: UiDevice): String = runCatching {
        val file = File(context.cacheDir, "mixed-import-ui-${UUID.randomUUID()}.xml")
        device.dumpWindowHierarchy(file)
        file.readText().also { file.delete() }.replace('\n', ' ').take(8_000)
    }.getOrElse { "hierarchyReadFailure=${it.javaClass.simpleName}:${it.message}" }

    private data class SourceDocument(val uri: Uri, val displayName: String, val mimeType: String, val bytes: ByteArray)
    private data class PageRecord(val pageId: String, val importSessionId: String?, val sourceProvenance: String, val orderIndex: Long, val sourcePageIndex: Long?)
    private data class ImportSessionRecord(
        val importSessionId: String,
        val state: String,
        val displayName: String,
        val sourceSnapshotArtifactId: String?,
        val sourceSnapshotSha256: String?,
    )

    private companion object {
        const val DOCUMENTS_UI = "com.google.android.documentsui"
        const val TIMEOUT_MILLIS = 20_000L
        const val TAG = "MultiImageImportPicker"
        val EXPECTED_PDF_PAGE_COLORS = listOf(0xe53935, 0x43a047, 0x1e88e5)
    }
}
