package app.docscan.android

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.database.sqlite.SQLiteDatabase
import android.content.ContentUris
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.os.Environment
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.lifecycle.Lifecycle
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SaveSetupInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun firstSavePublishesVerifiedPublicPdfAndCompanionImageWithoutSafSetup() {
        val fixture = fixture("first-save")
        var scenario: ActivityScenario<ProductActivity>? = null
        var recentPdfUri: Uri? = null
        var savedPdfName: String? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        val basename = "docscan-first-save-${fixture.suffix}"
        try {
            assertTrue("The isolated workspace starts with one real source Page", fixture.storage.workspaceProjection().pages.isEmpty())
            val source = jpegFixture()
            val page = fixture.storage.importImage(source.inputStream(), "image/jpeg", "first-save-source.jpg")
            fixture.storage.setSelected(page.pageId, true)
            val sourceRecord = fixture.storage.workspaceProjection().pages.single()
            val sourcePath = fixture.storage.artifactPath(sourceRecord.artifactRelativePath).toFile()
            val sourceSha = sha256(sourcePath.readBytes())
            assertEquals("Only the source Page is selected", 1, fixture.storage.workspaceProjection().pages.count { it.selected })
            assertNull("A fresh Save path has no destination grant", fixture.storage.destinationGrant())
            assertEquals("The fresh isolated database has no provider qualification rows", 0,
                countProviderQualifications(fixture.context.getDatabasePath(fixture.databaseName)))

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            await("fresh Activity and selected Page are ready") {
                var ready = false
                activeScenario.onActivity { activity ->
                    ready = activity.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true
                }
                ready
            }
            clickSave(activeScenario)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("First Save opens the public save-name dialog without launching the SAF picker",
                device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MILLIS))
            assertFalse("A fresh default-folder Save does not launch DocumentsUI", device.hasObject(By.pkg(DOCUMENTS_UI)))
            activeScenario.onActivity { activity ->
                assertFalse("Public Save does not leave a pending SAF picker", activityBoolean(activity, "destinationPickerPending"))
                assertFalse("Public Save does not wait for folder setup", activityBoolean(activity, "pendingMainSave"))
            }
            val prompt = device.findObject(By.clazz("android.widget.EditText"))
            prompt.text = basename
            val ocrCheckbox = device.findObject(By.text("OCR"))
            assertTrue("The save dialog exposes the OCR choice", ocrCheckbox != null)
            if (ocrCheckbox.isChecked) ocrCheckbox.click()
            assertFalse("The test disables OCR to avoid native recognition work", ocrCheckbox.isChecked)
            val saveAction = By.res("android", "button1")
            assertTrue("The Android positive action is Save", device.wait(Until.hasObject(saveAction), TIMEOUT_MILLIS))
            assertEquals("Save", device.findObject(saveAction).text)
            device.findObject(saveAction).click()

            val recent = awaitValue("The public MediaStore Save is confirmed in Recent") {
                fixture.storage.recentDocuments().singleOrNull()
            }
            recentPdfUri = Uri.parse(recent.documentUri)
            savedPdfName = recent.displayName
            assertTrue("Recent points to the actual public MediaStore Download", recent.documentUri.startsWith("content://media/"))
            assertEquals("The unique requested basename is retained", "$basename.pdf", recent.displayName)
            assertNull("Public Save does not create a SAF ExternalExportPackage",
                fixture.storage.externalExportPackage(recent.exportArtifactId))
            assertNull("Public Save does not persist a SAF destination grant", fixture.storage.destinationGrant())
            assertEquals("Public Save does not create provider qualification rows", 0,
                countProviderQualifications(fixture.context.getDatabasePath(fixture.databaseName)))
            val committed = fixture.storage.committedExportOutputs(recent.exportArtifactId).associateBy { it.artifactId }
            assertEquals(2, committed.size)
            val pdfOutput = committed.values.single { it.mimeType == "application/pdf" }
            val imageOutput = committed.values.single { it.mimeType == "image/jpeg" }
            assertTrue("The local PDF is committed and checksum-valid", fixture.storage.verifyExportOutput(pdfOutput))
            assertTrue("The local companion JPG is committed and checksum-valid", fixture.storage.verifyExportOutput(imageOutput))
            PDFBoxResourceLoader.init(fixture.context)
            PDDocument.load(fixture.storage.exportOutputFile(pdfOutput)).use { document ->
                assertEquals("Image-only input remains a one-page PDF", 1, document.numberOfPages)
            }
            val downloadedPdfBytes = requireNotNull(fixture.context.contentResolver.openInputStream(recentPdfUri!!)).use { it.readBytes() }
            assertEquals("Public PDF bytes match the verified local artifact", pdfOutput.sha256, sha256(downloadedPdfBytes))
            val jpgBytes = fixture.storage.exportOutputFile(imageOutput).readBytes()
            val decoded = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size)
            assertNotNull("The local companion JPG is a valid image", decoded)
            decoded?.recycle()
            val expectedJpgName = "${recent.displayName.removeSuffix(".pdf")}.jpg"
            val downloadNames = listOf(recent.displayName, expectedJpgName)
            val downloadUris = listDownloadUris(fixture.context, downloadNames)
            assertEquals("PDF and companion JPG are present in public Downloads", 2, downloadUris.size)
            val downloadedJpg = downloadUris.single { it.second == expectedJpgName }.first
            val downloadedJpgBytes = requireNotNull(fixture.context.contentResolver.openInputStream(downloadedJpg)).use { it.readBytes() }
            assertEquals("Public companion JPG matches the verified local artifact", imageOutput.sha256, sha256(downloadedJpgBytes))
            assertEquals("Successful Save consumes its selected source Page", 0, fixture.storage.workspaceProjection().pages.size)
            assertTrue("The source artifact remains unchanged in the isolated workspace evidence",
                sourcePath.isFile && sha256(sourcePath.readBytes()) == sourceSha)
            assertEquals("Recent contains the one successful PDF result", 1, fixture.storage.recentDocuments().size)
            activeScenario.onActivity { activity ->
                assertFalse("Save disables when the only Page has been consumed",
                    activity.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled ?: true)
                assertTrue("Home rail exits the external-workflow busy state after Save",
                    activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("docscan-home-thumbnail-scroll")?.isEnabled == true)
            }
            activeScenario.close()
            scenario = null
            fixture.storage.close()
            val reopened = ProductStorage(fixture.context, fixture.databaseName, fixture.artifactDirectory, fixture.candidateDirectory)
            try {
                assertFalse("Reopening public Recent does not trigger startup integrity blocking", reopened.startupIntegrityBlocked)
                assertEquals("Public Recent survives a fresh ProductStorage open", recent,
                    reopened.recentDocuments().single())
            } finally {
                reopened.close()
            }
            android.util.Log.i(TAG, "FIRST_PUBLIC_SAVE_PASS uri=${recent.documentUri} outputs=2 page_consumed=true")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            val names = savedPdfName?.let { listOf(it, "${it.removeSuffix(".pdf")}.jpg") }
                ?: listOf("$basename.pdf", "$basename.jpg")
            val discoveredUris = runCatching { listDownloadUris(fixture.context, names).map { it.first } }
                .onFailure { cleanupFailure = it }
                .getOrDefault(emptyList())
            val exactUris = (listOfNotNull(recentPdfUri) + discoveredUris).distinct()
            exactUris.forEach { uri ->
                runCatching {
                    check(fixture.context.contentResolver.delete(uri, null, null) == 1) {
                        "MediaStore did not remove test output $uri"
                    }
                }
                    .onFailure { cleanupFailure = it }
            }
            fixture.close()
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("First Save setup or cleanup failed", it) }
    }

    @Test
    fun saveAvailabilityIsDisabledForZeroAndMoreThanOneHundredSelectedPages() {
        val fixture = fixture("save-limits")
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            await("empty Activity is initialized") {
                var ready = false
                activeScenario.onActivity { ready = it.window.decorView.findViewWithTag<View>("docscan-save-button") != null }
                ready
            }
            assertSaveDisabled(activeScenario, "Zero Pages cannot enter Save setup")
            assertEquals("The empty fixture starts with no output or readiness", 0,
                countProviderQualifications(fixture.context.getDatabasePath(fixture.databaseName)))

            val jpeg = jpegFixture()
            repeat(101) { index ->
                val imported = fixture.storage.importImage(jpeg.inputStream(), "image/jpeg", "limit-${index + 1}.jpg")
                fixture.storage.setSelected(imported.pageId, true)
            }
            assertEquals("The fixture reaches the >100 selected boundary", 101,
                fixture.storage.workspaceProjection().pages.count { it.selected })
            // Refresh through the production Activity lifecycle so its Save availability reflects all Pages.
            activeScenario.recreate()
            await("The 101 selected Pages keep Save disabled after Activity refresh") {
                var disabled = false
                activeScenario.onActivity { activity ->
                    disabled = activity.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == false
                }
                disabled
            }
            assertSaveDisabled(activeScenario, "More than 100 selected Pages cannot enter Save setup")
            assertNull("Availability checks do not launch the picker or create a grant", fixture.storage.destinationGrant())
            assertEquals("Availability checks do not create provider readiness", 0,
                countProviderQualifications(fixture.context.getDatabasePath(fixture.databaseName)))
        } finally {
            scenario?.close()
            fixture.close()
        }
    }

    @Test
    fun manuallyChosenFolderCanSaveAfterReadinessCheck() {
        val fixture = fixture("manual-folder-save")
        val madeFolder = SafPickerTestActions.createDefaultFolderFixture()
        var scenario: ActivityScenario<ProductActivity>? = null
        var selectedTreeUri: String? = null
        var outputNames: List<String> = emptyList()
        val basename = "docscan-manual-save-${fixture.suffix}"
        try {
            val page = fixture.storage.importImage(jpegFixture().inputStream(), "image/jpeg", "manual-save-source.jpg")
            fixture.storage.setSelected(page.pageId, true)
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            await("manual-save Activity is initialized") {
                var enabled = false
                activeScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }

            activeScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-workspace-menu-button")).performClick()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val chooseFolder = By.desc(Pattern.compile("(?i)^choose save folder$"))
            assertTrue("Workspace menu exposes the accessible folder chooser",
                device.wait(Until.hasObject(chooseFolder), TIMEOUT_MILLIS))
            device.findObject(chooseFolder).click()
            assertTrue("Manual folder chooser launches DocumentsUI",
                device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            SafPickerTestActions.chooseTreeDirectory(device, activeScenario, listOf("Docscan"))

            await("manual folder grant passes its readiness check") {
                var enabled = false
                activeScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true
                }
                enabled && fixture.storage.destinationGrant() != null
            }
            val grant = requireNotNull(fixture.storage.destinationGrant())
            selectedTreeUri = grant.treeUri
            assertEquals(
                DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Docscan").toString(),
                grant.treeUri,
            )

            clickSave(activeScenario)
            val editText = By.clazz("android.widget.EditText")
            assertTrue("Ready manual destination opens the Save dialog", device.wait(Until.hasObject(editText), TIMEOUT_MILLIS))
            device.findObject(editText).text = basename
            val ocrCheckbox = device.findObject(By.text("OCR"))
            if (ocrCheckbox.isChecked) ocrCheckbox.click()
            device.findObject(By.res("android", "button1")).click()

            val recent = awaitValue("Manual folder Save reaches Recent") {
                fixture.storage.recentDocuments().singleOrNull()
            }
            val packageRecord = awaitValue("Manual folder package becomes terminal") {
                fixture.storage.externalExportPackage(recent.exportArtifactId)?.takeIf { it.state == "SUCCEEDED" }
            }
            assertEquals("SUCCEEDED", packageRecord.state)
            assertEquals("The selected basename is saved as PDF", "$basename.pdf", recent.displayName)
            assertTrue("Recent links to the selected SAF tree", recent.documentUri.startsWith("content://"))
            outputNames = packageRecord.outputs.map { it.displayName }
            assertEquals("PDF and JPG are committed to the selected folder", 2, outputNames.size)
            assertTrue("Every package output is verified locally", fixture.storage.committedExportOutputs(recent.exportArtifactId)
                .all(fixture.storage::verifyExportOutput))
        } finally {
            scenario?.close()
            outputNames.forEach { name ->
                runCatching {
                    InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommand("rm -f '/sdcard/Docscan/$name'").let { descriptor ->
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                        }
                }
            }
            selectedTreeUri?.let { runCatching { SafDestinationGrantManager(fixture.context).releasePersistedGrant(it) } }
            fixture.close()
            SafPickerTestActions.removeDefaultFolderFixture(madeFolder)
        }
    }

    @Test
    fun cancelingFolderSelectionPickerPreservesPageAndCreatesNoReadinessOrOutput() {
        val fixture = fixture("cancel-save-picker")
        var scenario: ActivityScenario<ProductActivity>? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        try {
            val image = jpegFixture()
            val imported = fixture.storage.importImage(image.inputStream(), "image/jpeg", "cancel-save-source.jpg")
            fixture.storage.setSelected(imported.pageId, true)
            val page = fixture.storage.workspaceProjection().pages.single()
            val sourceSha = sha256(fixture.storage.artifactPath(page.artifactRelativePath).toFile().readBytes())
            val noOutputNames = listOf("${fixture.suffix}.pdf", "${fixture.suffix} — 01.jpg")
            assertNull("Cancellation test begins without saved destination metadata", fixture.storage.destinationGrant())
            assertEquals(0, countProviderQualifications(fixture.context.getDatabasePath(fixture.databaseName)))

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            await("Cancel fixture Activity is ready") {
                var enabled = false
                activeScenario.onActivity { enabled = it.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true }
                enabled
            }
            activeScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-workspace-menu-button")).performClick()
            }
            val chooseFolder = By.text("CHOOSE SAVE FOLDER")
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("The real workspace menu exposes Choose save folder",
                device.wait(Until.hasObject(chooseFolder), TIMEOUT_MILLIS))
            device.findObject(chooseFolder).click()
            assertTrue("Choose save folder launches the real picker", device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            activeScenario.onActivity { activity ->
                assertTrue("Folder selection marks only the picker as pending", activityBoolean(activity, "destinationPickerPending"))
                assertFalse("Folder selection does not create a pending Save", activityBoolean(activity, "pendingMainSave"))
            }
            cancelPickerWithBoundedBack(device, activeScenario)
            activeScenario.onActivity { activity ->
                assertFalse("Cancellation clears the picker-pending state", activityBoolean(activity, "destinationPickerPending"))
                assertFalse("Folder cancellation leaves no pending main Save", activityBoolean(activity, "pendingMainSave"))
            }
            await("Cancelled folder selection leaves the workspace interactive") {
                var enabled = false
                activeScenario.onActivity { enabled = it.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true }
                enabled
            }
            assertNull("Cancelling folder selection stores no destination", fixture.storage.destinationGrant())
            assertEquals("Cancelling folder selection stores no runtime readiness", 0,
                countProviderQualifications(fixture.context.getDatabasePath(fixture.databaseName)))
            assertEquals("Cancellation retains the selected Page", 1, fixture.storage.workspaceProjection().pages.count { it.selected })
            val retained = fixture.storage.workspaceProjection().pages.single()
            assertEquals("Cancellation retains identical source bytes", sourceSha,
                sha256(fixture.storage.artifactPath(retained.artifactRelativePath).toFile().readBytes()))
            assertEquals("Cancellation publishes no Recent result", 0, fixture.storage.recentDocuments().size)
            assertEquals("Cancellation creates no external package", 0,
                countTableRows(fixture.context.getDatabasePath(fixture.databaseName), "ExternalExportPackage"))
            assertEquals("Cancellation creates no export artifact", 0,
                countTableRows(fixture.context.getDatabasePath(fixture.databaseName), "ExportArtifact"))
            assertTrue("Cancelled folder selection publishes no Download outputs",
                listDownloadUris(fixture.context, noOutputNames).isEmpty())
            activeScenario.onActivity { activity ->
                assertTrue("After cancellation the selected Page can enter setup again",
                    activity.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true)
                val rail = activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("docscan-home-thumbnail-scroll")
                val checkbox = activity.window.decorView.findViewWithTag<View>("docscan-home-selection:${page.pageId.value}")
                assertTrue("Home rail is enabled after picker cancellation", rail?.isEnabled == true)
                assertTrue("Retained Page selection remains enabled after picker cancellation", checkbox?.isEnabled == true)
            }
            android.util.Log.i(TAG, "SAVE_FOLDER_PICKER_CANCEL_PASS page_retained=true readiness=0 exports=0")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            fixture.close()
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("Pending Save picker cancel or cleanup failed", it) }
    }

    private fun clickSave(scenario: ActivityScenario<ProductActivity>) {
        scenario.onActivity { activity ->
            val save = requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button"))
            assertTrue("A single selected Page enables the Save setup entry", save.isEnabled)
            save.performClick()
        }
    }

    private fun assertSaveDisabled(scenario: ActivityScenario<ProductActivity>, message: String) {
        scenario.onActivity { activity ->
            assertFalse(message, requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button")).isEnabled)
        }
    }

    private fun activityBoolean(activity: ProductActivity, fieldName: String): Boolean =
        ProductActivity::class.java.getDeclaredField(fieldName).run {
            isAccessible = true
            getBoolean(activity)
        }

    private fun fixture(label: String): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "save-setup-$label-$suffix.db"
        val artifactDirectory = "save-setup-$label-artifacts-$suffix"
        val candidateDirectory = "save-setup-$label-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorOverride = app.productStorageOverrideForTest
        val priorProvider = app.productStorageProviderForTest
        app.productStorageProviderForTest = null
        app.productStorageOverrideForTest = storage
        return Fixture(context, app, storage, databaseName, artifactDirectory, candidateDirectory,
            priorOverride, priorProvider, suffix)
    }

    private fun jpegFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff3979a5.toInt()) }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun listDownloadUris(context: Context, displayNames: List<String>): List<Pair<Uri, String>> {
        require(displayNames.isNotEmpty())
        val result = mutableListOf<Pair<Uri, String>>()
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/DocScan/"
        displayNames.forEach { displayName ->
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(relativePath, displayName),
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    result += ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn),
                    ) to cursor.getString(nameColumn)
                }
            } ?: throw AssertionError("MediaStore returned no Downloads listing for $displayName")
        }
        return result
    }

    private fun <T> awaitValue(message: String, value: () -> T?): T {
        val deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            value()?.let { return it }
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $message")
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for $message")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun countProviderQualifications(databaseFile: File): Int =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM ProviderQualification", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    private fun countTableRows(databaseFile: File, table: String): Int =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    private fun cancelPickerWithBoundedBack(device: UiDevice, scenario: ActivityScenario<ProductActivity>) {
        repeat(MAX_PICKER_BACK_PRESSES) {
            if (device.hasObject(By.pkg(DOCUMENTS_UI))) device.pressBack()
            val stepDeadline = android.os.SystemClock.uptimeMillis() + 1_000L
            while (android.os.SystemClock.uptimeMillis() < stepDeadline) {
                val appForeground = device.currentPackageName == APP_PACKAGE
                var resumed = false
                scenario.onActivity { resumed = it.lifecycle.currentState == Lifecycle.State.RESUMED }
                if (appForeground && resumed) return
                if (device.hasObject(By.pkg(DOCUMENTS_UI))) break
                android.os.SystemClock.sleep(50)
            }
        }
        assertTrue("Bounded Back cancellation returns to ProductActivity, not Home",
            device.wait(Until.hasObject(By.pkg(APP_PACKAGE)), TIMEOUT_MILLIS))
        val deadline = android.os.SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var resumed = false
            scenario.onActivity { resumed = it.lifecycle.currentState == Lifecycle.State.RESUMED }
            if (resumed) return
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("ProductActivity did not resume after bounded real-picker Back cancellation")
    }

    private class Fixture(
        val context: Context,
        private val app: DocScanApplication,
        val storage: ProductStorage,
        val databaseName: String,
        val artifactDirectory: String,
        val candidateDirectory: String,
        private val priorOverride: ProductStorage?,
        private val priorProvider: (() -> ProductStorage)?,
        val suffix: String,
    ) {
        fun close() {
            app.productStorageOverrideForTest = priorOverride
            app.productStorageProviderForTest = priorProvider
            runCatching { storage.close() }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
        }
    }

    companion object {
        private const val DOCUMENTS_UI = "com.google.android.documentsui"
        private const val APP_PACKAGE = "app.docscan.scanner"
        private const val MAX_PICKER_BACK_PRESSES = 8
        private const val TIMEOUT_MILLIS = 30_000L
        private const val TAG = "SaveSetupInstrumentedTest"
    }
}
