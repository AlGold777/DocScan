package app.docscan.android

import android.Manifest
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.lifecycle.Lifecycle
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host-driven proof that the Android-owned destination grant survives process death and fails closed on revocation. */
@RunWith(AndroidJUnit4::class)
class SafDestinationGrantProcessDeathInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun processDeathGrantResumeProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val fixture = fixture(InstrumentationRegistry.getInstrumentation().targetContext, fixtureId)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> checkpoint(context, application, fixture)
            "resume" -> resume(context, application, fixture)
            "cleanup" -> cleanup(context, fixture)
            else -> throw AssertionError("Unknown SAF destination process-death phase: $phase")
        }
    }

    private fun checkpoint(context: Context, application: DocScanApplication, fixture: Fixture) {
        check(!context.getDatabasePath(fixture.databaseName).exists()) { "Fixture database already exists" }
        check(!fixture.artifactRoot.exists()) { "Fixture artifact root already exists" }
        check(!fixture.root.exists()) { "Fixture root already exists" }
        fixture.root.mkdirs()

        val storage = ProductStorage(context, fixture.databaseName, fixture.artifactRoot.name)
        val oldOverride = application.productStorageOverrideForTest
        val oldProvider = application.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        var checkpointWritten = false
        try {
            application.productStorageProviderForTest = null
            application.productStorageOverrideForTest = storage
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xff245b89.toInt())
            }
            val imageBytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                output.toByteArray()
            }
            bitmap.recycle()
            val imported = storage.importImage(imageBytes.inputStream(), "image/jpeg", "saf-grant-death-fixture.jpg")
            storage.setSelected(imported.pageId, true)
            val page = storage.workspaceProjection().pages.single()
            assertTrue("The fixture page must be selected", page.selected)
            val imageFile = storage.artifactPath(page.artifactRelativePath).toFile()
            val pageSha256 = sha256(imageFile.readBytes())

            val expectedTree = expectedTreeUri(fixture)
            val oldPermissions = context.contentResolver.persistedUriPermissions
            assertFalse(
                "The unique fixture tree must not have a pre-existing persisted permission",
                oldPermissions.any { it.uri == expectedTree },
            )
            val oldPermissionUris = oldPermissions.map { it.uri.toString() }.toSortedSet()
            writeOwnershipSnapshot(fixture, oldPermissionUris)

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            clickDestinationButton(scenario)
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            SafPickerTestActions.chooseTreeDirectory(
                device,
                scenario,
                listOf("Documents", fixture.folderName),
            )

            awaitCondition("ProductActivity must persist the selected fixture tree") {
                storage.destinationGrant()?.treeUri == expectedTree.toString()
            }
            val grant = requireNotNull(storage.destinationGrant())
            assertEquals("Only the exact unique primary Documents subtree is selected", fixture.documentId, DocumentsContract.getTreeDocumentId(Uri.parse(grant.treeUri)))
            assertTrue("The fixture tree is a SAF tree URI", DocumentsContract.isTreeUri(Uri.parse(grant.treeUri)))
            assertPersistedReadWrite(context, expectedTree)
            assertEquals(
                "Selecting one new folder must not change other app grants",
                oldPermissionUris + expectedTree.toString(),
                context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSortedSet(),
            )

            val properties = Properties().apply {
                setProperty("fixtureId", fixture.id)
                setProperty("folderName", fixture.folderName)
                setProperty("treeUri", grant.treeUri)
                setProperty("priorPermissionUris", oldPermissionUris.joinToString("|"))
                setProperty("pageId", page.pageId.value)
                setProperty("imageVersionId", page.imageVersionId.value)
                setProperty("imageSha256", pageSha256)
            }
            writeCheckpoint(fixture, properties)
            checkpointWritten = true
            Log.i(
                TAG,
                "CHECKPOINT fixture=${fixture.id} tree=${grant.treeUri} page=${page.pageId.value} " +
                    "image_version=${page.imageVersionId.value} image_sha256=$pageSha256 pid=${android.os.Process.myPid()}",
            )
            assertFalse("Host must keep the app blocked until process death", CountDownLatch(1).await(10, TimeUnit.MINUTES))
            throw AssertionError("SAF destination grant checkpoint returned without host process death")
        } finally {
            scenario?.close()
            application.productStorageOverrideForTest = oldOverride
            application.productStorageProviderForTest = oldProvider
            runCatching { storage.close() }
            if (!checkpointWritten) cleanup(context, fixture)
        }
    }

    private fun resume(context: Context, application: DocScanApplication, fixture: Fixture) {
        val properties = readCheckpoint(fixture)
        val treeUri = Uri.parse(properties.getProperty("treeUri"))
        val priorPermissionUris = properties.getProperty("priorPermissionUris")
            .takeIf(String::isNotEmpty)?.split('|')?.toSortedSet() ?: sortedSetOf()
        val storage = ProductStorage(context, fixture.databaseName, fixture.artifactRoot.name)
        val oldOverride = application.productStorageOverrideForTest
        val oldProvider = application.productStorageProviderForTest
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            application.productStorageProviderForTest = null
            application.productStorageOverrideForTest = storage
            assertFalse("Fresh process must load the isolated database without integrity blockage", storage.startupIntegrityBlocked)
            val grant = requireNotNull(storage.destinationGrant())
            assertEquals("The exact test tree must remain recorded after process death", treeUri.toString(), grant.treeUri)
            assertEquals(fixture.documentId, DocumentsContract.getTreeDocumentId(treeUri))
            assertPersistedReadWrite(context, treeUri)
            assertEquals(
                "Fresh process must retain only the prior app grants plus this fixture grant",
                priorPermissionUris + treeUri.toString(),
                context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSortedSet(),
            )
            assertTrue("The production manager must revalidate the persisted grant after death", SafDestinationGrantManager(context).revalidate(grant, storage))
            assertFixturePage(properties, storage)

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            assertSaveEnabled(scenario, "A selected Page keeps Save available as the entry to runtime destination setup")
            assertNoRuntimeReadiness(storage, treeUri.toString())
            assertNoSavedOutputRecords(context, fixture.databaseName)

            // Exercise the negative post-death authority case by releasing only this unique test grant.
            SafDestinationGrantManager(context).releasePersistedGrant(treeUri.toString())
            assertFalse("A revoked persisted permission must fail production revalidation", SafDestinationGrantManager(context).revalidate(grant, storage))
            assertEquals("The user-selected tree metadata remains available for re-pick", treeUri.toString(), storage.destinationGrant()?.treeUri)
            assertEquals(
                "Revoking the fixture grant must leave all other app grants untouched",
                priorPermissionUris,
                context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSortedSet(),
            )
            val reopenedScenario = requireNotNull(scenario)
            reopenedScenario.recreate()
            awaitStatus(reopenedScenario, context.getString(R.string.destination_revoked))
            assertSaveEnabled(reopenedScenario, "A revoked grant must route Save through destination setup instead of disabling the entry")

            // Save remains an entry point. A real re-pick can be cancelled without export or Page loss.
            reopenedScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("Save must route a revoked destination into the real system tree picker",
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 15_000L))
            cancelTreePickerWithBoundedBack(device, reopenedScenario)
            assertEquals("Cancelling re-pick must retain the destination metadata for the user's next attempt",
                treeUri.toString(), storage.destinationGrant()?.treeUri)
            assertTrue("Cancelled re-pick must not recreate the revoked OS permission",
                context.contentResolver.persistedUriPermissions.none { it.uri == treeUri })
            assertNoRuntimeReadiness(storage, treeUri.toString())
            assertFixturePage(properties, storage)
            assertNoSavedOutputRecords(context, fixture.databaseName)
            Log.i(TAG, "RESUME_PASS fixture=${fixture.id} tree=${treeUri} revalidate=PASS revoked=FAIL_CLOSED " +
                "save_entry_enabled=true repick_cancelled=true exports=0 page_retained=true")
        } finally {
            scenario?.close()
            application.productStorageOverrideForTest = oldOverride
            application.productStorageProviderForTest = oldProvider
            runCatching { storage.close() }
        }
    }

    private fun cleanup(context: Context, fixture: Fixture) {
        val ownership = fixture.ownership.takeIf(File::exists)?.let { file ->
            Properties().apply { file.inputStream().use(::load) }
        }
        val checkpoint = fixture.checkpoint.takeIf(File::exists)?.let { runCatching { readCheckpoint(fixture) }.getOrNull() }
        val priorPermissionUris = checkpoint?.getProperty("priorPermissionUris")
            ?: ownership?.getProperty("priorPermissionUris")
        val recordedTree = checkpoint?.getProperty("treeUri")
        val treeUri = recordedTree?.let(Uri::parse) ?: expectedTreeUri(fixture)
        val testOwnsTree = ownership?.getProperty("fixtureId") == fixture.id &&
            ownership.getProperty("folderName") == fixture.folderName &&
            priorPermissionUris.orEmpty().split('|').none { it == treeUri.toString() }
        if (testOwnsTree) {
            runCatching { SafDestinationGrantManager(context).releasePersistedGrant(treeUri.toString()) }
                .onFailure { throw AssertionError("Could not release only this fixture's persisted grant", it) }
        }
        runCatching { ProductStorage(context, fixture.databaseName, fixture.artifactRoot.name).close() }
        check(context.deleteDatabase(fixture.databaseName)) { "Could not delete fixture database" }
        check(!fixture.artifactRoot.exists() || fixture.artifactRoot.deleteRecursively()) { "Could not delete fixture artifact root" }
        check(!fixture.root.exists() || fixture.root.deleteRecursively()) { "Could not delete fixture checkpoint files" }
        Log.i(TAG, "CLEANUP fixture=${fixture.id} tree_grant_released=$testOwnsTree")
    }

    private fun assertFixturePage(properties: Properties, storage: ProductStorage) {
        val page = storage.workspaceProjection().pages.single()
        assertEquals(properties.getProperty("pageId"), page.pageId.value)
        assertEquals(properties.getProperty("imageVersionId"), page.imageVersionId.value)
        assertTrue("Selected page state must survive process death and grant revocation", page.selected)
        val imageFile = storage.artifactPath(page.artifactRelativePath).toFile()
        assertEquals("Committed image bytes must survive process death and grant revocation", properties.getProperty("imageSha256"), sha256(imageFile.readBytes()))
    }

    private fun clickDestinationButton(scenario: ActivityScenario<ProductActivity>) {
        scenario.onActivity { activity ->
            val button = activity.window.decorView.findViewWithTag<View>("docscan-destination-button")
            assertNotNull("Destination button must be visible", button)
            assertTrue("Destination picker must be enabled", requireNotNull(button).isEnabled)
            button.performClick()
        }
    }

    private fun awaitActivityReady(scenario: ActivityScenario<ProductActivity>) {
        awaitCondition("ProductActivity destination action must initialize") {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.window.decorView.findViewWithTag<View>("docscan-destination-button")?.isEnabled == true
            }
            ready
        }
    }

    private fun awaitStatus(scenario: ActivityScenario<ProductActivity>, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 20_000L
        var actual = "<not observed>"
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                actual = activity.window.decorView.findViewWithTag<TextView>("docscan-status")?.text?.toString().orEmpty()
            }
            if (actual == expected) return
            SystemClock.sleep(50)
        }
        throw AssertionError("ProductActivity status must become '$expected'; last observed='$actual'")
    }

    private fun assertSaveEnabled(scenario: ActivityScenario<ProductActivity>, message: String) {
        scenario.onActivity { activity ->
            val save = activity.window.decorView.findViewWithTag<View>("docscan-save-button")
            assertNotNull("Save button must be present", save)
            assertTrue(message, requireNotNull(save).isEnabled)
        }
    }

    private fun cancelTreePickerWithBoundedBack(device: UiDevice, scenario: ActivityScenario<ProductActivity>) {
        val documentsUi = "com.google.android.documentsui"
        repeat(MAX_PICKER_BACK_ACTIONS) {
            var resumed = false
            scenario.onActivity { resumed = it.lifecycle.currentState == Lifecycle.State.RESUMED }
            if (resumed && device.currentPackageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName) return
            check(device.currentPackageName == documentsUi) {
                "Back is sent only while DocumentsUI is foreground; current=${device.currentPackageName}"
            }
            device.pressBack()
            SystemClock.sleep(150)
        }
        awaitCondition("Bounded Back actions must return the same ProductActivity to RESUMED") {
            var resumed = false
            scenario.onActivity { resumed = it.lifecycle.currentState == Lifecycle.State.RESUMED }
            resumed && device.currentPackageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName
        }
    }

    private fun assertNoRuntimeReadiness(storage: ProductStorage, treeUri: String) {
        val identity = SafProviderIdentity.resolve(InstrumentationRegistry.getInstrumentation().targetContext, treeUri)
        assertTrue("Setup without a successful live check must not persist readiness", storage.providerQualificationsFor(
            identity.adapterId, identity.providerVersion, identity.osVersion, identity.accessClass,
        ).isEmpty())
    }

    private fun assertNoSavedOutputRecords(context: Context, databaseName: String) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf("SaveManifest", "ExportArtifact", "ExportAttempt", "ExternalExportPackage").forEach { table ->
                val count = DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM $table", null)
                assertEquals("Cancelled/revoked Save setup must not create $table rows", 0L, count)
            }
        }
    }

    private fun awaitCondition(message: String, timeoutMillis: Long = 20_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun assertPersistedReadWrite(context: Context, uri: Uri) {
        assertTrue(
            "Android must retain read and write permission for the exact tree",
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            },
        )
    }

    private fun writeCheckpoint(fixture: Fixture, properties: Properties) {
        FileOutputStream(fixture.checkpoint).use { stream ->
            properties.store(stream, "Persisted SAF destination grant process-death checkpoint")
            stream.fd.sync()
        }
    }

    private fun writeOwnershipSnapshot(fixture: Fixture, priorPermissionUris: Set<String>) {
        val properties = Properties().apply {
            setProperty("fixtureId", fixture.id)
            setProperty("folderName", fixture.folderName)
            setProperty("priorPermissionUris", priorPermissionUris.joinToString("|"))
            setProperty("expectedTreeUri", expectedTreeUri(fixture).toString())
        }
        FileOutputStream(fixture.ownership).use { stream ->
            properties.store(stream, "Owned SAF grant fixture scope")
            stream.fd.sync()
        }
    }

    private fun readCheckpoint(fixture: Fixture): Properties = Properties().apply {
        fixture.checkpoint.inputStream().use(::load)
        assertEquals("Checkpoint must belong to this fixture", fixture.id, getProperty("fixtureId"))
        assertEquals("Checkpoint folder must match this fixture", fixture.folderName, getProperty("folderName"))
    }

    private fun fixture(context: Context, id: String): Fixture {
        require(id.matches(Regex("[a-f0-9]{32}"))) { "Invalid fixture id" }
        val folderName = "DocScanGrantDeath-$id"
        return Fixture(
            id = id,
            folderName = folderName,
            databaseName = "saf-grant-death-$id.db",
            artifactRoot = File(context.noBackupFilesDir, "saf-grant-death-artifacts-$id"),
            root = File(context.noBackupFilesDir, "saf-grant-death-fixture-$id"),
        )
    }

    private fun expectedTreeUri(fixture: Fixture): Uri = DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents",
        fixture.documentId,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val id: String,
        val folderName: String,
        val databaseName: String,
        val artifactRoot: File,
        val root: File,
    ) {
        val documentId: String get() = "primary:Documents/$folderName"
        val ownership: File get() = root.resolve("ownership.properties")
        val checkpoint: File get() = root.resolve("checkpoint.properties")
    }

    companion object {
        private const val TAG = "SafGrantProcessDeath"
        private const val MAX_PICKER_BACK_ACTIONS = 8
    }
}
