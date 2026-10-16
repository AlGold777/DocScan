package app.docscan.android

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SaveSetupInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun firstSaveRunsRealDestinationSetupThenReadsBackPdfAndCompanionImage() {
        val fixture = fixture("first-save")
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var createdOutputUris = emptyList<Uri>()
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        var pickerLaunchActivity: ProductActivity? = null
        val basename = "docscan-first-save-${fixture.suffix}"
        val evidenceDirectory = File(fixture.context.noBackupFilesDir, SafRuntimeDestinationCheck.CHECK_DIRECTORY)
        val evidenceBefore = evidenceDirectory.listFiles().orEmpty().map { it.name }.toSet()
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
            assertTrue("First Save opens the real system destination picker",
                device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            activeScenario.onActivity { activity ->
                pickerLaunchActivity = activity
                assertTrue("Save setup continuation remains pending across recreation",
                    activityBoolean(activity, "pendingMainSave"))
                assertTrue("The real picker is marked pending before recreation",
                    activityBoolean(activity, "destinationPickerPending"))
                val save = requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button"))
                assertFalse("Save is disabled while destination setup is pending", save.isEnabled)
                save.performClick()
            }
            // Recreate asynchronously while DocumentsUI owns the foreground. ActivityScenario.recreate()
            // waits for RESUMED, which cannot happen until the external picker returns.
            activeScenario.onActivity { it.recreate() }
            assertTrue("Activity recreation leaves the real picker and pending Save intact",
                device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            SafPickerTestActions.chooseTreeDirectory(device, activeScenario, listOf("Documents"))
            await("The picker result resumes the recreated Activity instance") {
                var resumedNewInstance = false
                activeScenario.onActivity { activity ->
                    resumedNewInstance = activity !== pickerLaunchActivity && activity.lifecycle.currentState == Lifecycle.State.RESUMED
                }
                resumedNewInstance
            }
            val selectedGrant = awaitValue("The real picker grant is persisted") { fixture.storage.destinationGrant() }
            val selectedTree = Uri.parse(selectedGrant.treeUri)
            tree = selectedTree
            assertEquals("The fixture selects the primary Documents tree", "primary:Documents", DocumentsContract.getTreeDocumentId(selectedTree))

            val runtime = awaitValue("The actual runtime destination check becomes ready") {
                val grant = fixture.storage.destinationGrant() ?: return@awaitValue null
                SafRuntimeDestinationCheck(fixture.context, fixture.storage).findReady(grant.treeUri)
            }
            assertEquals(selectedTree.toString(), org.json.JSONObject(runtime.qualifiedCapabilitiesPayload).getString("treeUri"))
            val runtimePayload = org.json.JSONObject(runtime.qualifiedCapabilitiesPayload)
            assertEquals("RUNTIME_CAPABILITY_CHECK", runtimePayload.getString("provenance"))
            assertFalse("A runtime check is not a formal qualification", runtimePayload.getBoolean("formalQualification"))
            assertTrue("The actual check retained its raw evidence", File(runtimePayload.getString("evidencePath")).isFile)
            val exactTuple = SafProviderIdentity.resolve(fixture.context, selectedTree.toString())
            val tupleRecords = fixture.storage.providerQualificationsFor(
                exactTuple.adapterId, exactTuple.providerVersion, exactTuple.osVersion, exactTuple.accessClass,
            )
            assertEquals("Only the actual runtime check qualifies this provider tuple", 1, tupleRecords.size)
            assertTrue("The Save setup has not inserted a test qualification", tupleRecords.all {
                org.json.JSONObject(it.qualifiedCapabilitiesPayload).optString("provenance") == "RUNTIME_CAPABILITY_CHECK"
            })
            val freshEvidence = evidenceDirectory.listFiles().orEmpty().filter { it.name !in evidenceBefore }
            assertEquals("Two rapid Save taps through one setup and recreation start exactly one runtime probe", 1,
                freshEvidence.count { it.name.startsWith("evidence-") && it.name.endsWith(".json") })
            assertTrue("The basename prompt appears only after destination setup completes",
                device.wait(Until.hasObject(By.clazz("android.widget.EditText")), TIMEOUT_MILLIS))

            val prompt = device.findObject(By.clazz("android.widget.EditText"))
            prompt.text = basename
            val saveAction = By.text("Save selected pages")
            assertTrue("The basename confirmation is shown", device.wait(Until.hasObject(saveAction), TIMEOUT_MILLIS))
            device.findObject(saveAction).click()

            val recent = awaitValue("The real external Save is confirmed in Recent") {
                fixture.storage.recentDocuments().singleOrNull()
            }
            val exportPackage = requireNotNull(fixture.storage.externalExportPackage(recent.exportArtifactId))
            assertEquals("The two-file output package completes", "SUCCEEDED", exportPackage.state)
            val committed = fixture.storage.committedExportOutputs(recent.exportArtifactId).associateBy { it.artifactId }
            val attempts = fixture.storage.exportAttempts(recent.exportArtifactId).associateBy { it.outputArtifactId }
            assertEquals(2, committed.size)
            assertEquals(2, attempts.size)
            assertTrue("PDF and companion image both have successful provider read-back", attempts.values.all { it.state == "SUCCEEDED" })
            val outputNames = exportPackage.outputs.associateBy { it.outputArtifactId }
            assertEquals(setOf("$basename.pdf", "$basename — 01.jpg"), outputNames.values.map { it.displayName }.toSet())
            val outputBytes = attempts.mapValues { (_, attempt) ->
                requireNotNull(fixture.context.contentResolver.openInputStream(Uri.parse(requireNotNull(attempt.externalIdentity)))).use { it.readBytes() }
            }
            outputBytes.forEach { (artifactId, bytes) ->
                val privateOutput = requireNotNull(committed[artifactId])
                assertEquals("Provider byte count matches the committed local output", privateOutput.sizeBytes, bytes.size.toLong())
                assertEquals("Provider bytes match the committed local SHA-256", privateOutput.sha256, sha256(bytes))
            }
            val pdfOutput = outputNames.values.single { it.displayName == "$basename.pdf" }
            val imageOutput = outputNames.values.single { it.displayName == "$basename — 01.jpg" }
            val pdfBytes = requireNotNull(outputBytes[pdfOutput.outputArtifactId])
            PDFBoxResourceLoader.init(fixture.context)
            PDDocument.load(ByteArrayInputStream(pdfBytes)).use { document ->
                assertEquals("Image-only input remains a one-page PDF", 1, document.numberOfPages)
            }
            val jpgBytes = requireNotNull(outputBytes[imageOutput.outputArtifactId])
            val decoded = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.size)
            assertNotNull("The companion JPG is a valid image", decoded)
            decoded?.recycle()
            val children = listChildren(fixture.context, selectedTree)
            createdOutputUris = children.filter { it.displayName in setOf("$basename.pdf", "$basename — 01.jpg") }.map { it.uri }
            assertEquals("Both expected output files are present in the selected real tree", 2, createdOutputUris.size)
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
            android.util.Log.i(TAG, "FIRST_SAVE_SETUP_PASS tree=$selectedTree runtime_formal=false outputs=2 page_consumed=true")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            val selectedTree = tree
            if (selectedTree != null) {
                val knownUris = (createdOutputUris + listChildren(fixture.context, selectedTree)
                    .filter { it.displayName == "$basename.pdf" || it.displayName == "$basename — 01.jpg" }
                    .map { it.uri }).distinct()
                knownUris.forEach { uri ->
                    runCatching { DocumentsContract.deleteDocument(fixture.context.contentResolver, uri) }
                        .onFailure { cleanupFailure = it }
                }
                if (selectedTree.toString() !in fixture.priorPermissionUris) {
                    runCatching { SafDestinationGrantManager(fixture.context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure { cleanupFailure = it }
                }
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
    fun cancelingRealPickerFromPendingSavePreservesPageAndCreatesNoReadinessOrOutput() {
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
            assertNull("Cancellation test begins without saved destination metadata", fixture.storage.destinationGrant())
            assertEquals(0, countProviderQualifications(fixture.context.getDatabasePath(fixture.databaseName)))

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = requireNotNull(scenario)
            await("Cancel fixture Activity is ready") {
                var enabled = false
                activeScenario.onActivity { enabled = it.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true }
                enabled
            }
            clickSave(activeScenario)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("Pending Save launches the real picker", device.wait(Until.hasObject(By.pkg(DOCUMENTS_UI)), TIMEOUT_MILLIS))
            cancelPickerWithBoundedBack(device, activeScenario)
            await("Cancelled destination setup re-enables the Save entry") {
                var enabled = false
                activeScenario.onActivity { enabled = it.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true }
                enabled
            }
            assertNull("Cancelling destination setup stores no destination", fixture.storage.destinationGrant())
            assertEquals("Cancelling destination setup stores no runtime readiness", 0,
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
            activeScenario.onActivity { activity ->
                assertTrue("After cancellation the selected Page can enter setup again",
                    activity.window.decorView.findViewWithTag<View>("docscan-save-button")?.isEnabled == true)
                val rail = activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("docscan-home-thumbnail-scroll")
                val checkbox = activity.window.decorView.findViewWithTag<View>("docscan-home-selection:${page.pageId.value}")
                assertTrue("Home rail is enabled after picker cancellation", rail?.isEnabled == true)
                assertTrue("Retained Page selection remains enabled after picker cancellation", checkbox?.isEnabled == true)
            }
            android.util.Log.i(TAG, "SAVE_PICKER_CANCEL_PASS page_retained=true readiness=0 exports=0")
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
        val priorPermissionUris = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        app.productStorageProviderForTest = null
        app.productStorageOverrideForTest = storage
        return Fixture(context, app, storage, databaseName, artifactDirectory, candidateDirectory,
            priorOverride, priorProvider, priorPermissionUris, suffix)
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

    private fun listChildren(context: Context, tree: Uri): List<Child> {
        val documentId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val result = mutableListOf<Child>()
        context.contentResolver.query(childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(id) ?: continue
                result += Child(DocumentsContract.buildDocumentUriUsingTree(tree, childId), cursor.getString(name).orEmpty())
            }
        } ?: throw AssertionError("The selected provider did not return a child listing")
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

    private data class Child(val uri: Uri, val displayName: String)

    private class Fixture(
        val context: Context,
        private val app: DocScanApplication,
        val storage: ProductStorage,
        val databaseName: String,
        val artifactDirectory: String,
        val candidateDirectory: String,
        private val priorOverride: ProductStorage?,
        private val priorProvider: (() -> ProductStorage)?,
        val priorPermissionUris: Set<String>,
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
