package app.docscan.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafDestinationPickerInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun realDocumentsUiGrantPersistsAndRevalidatesAfterStorageAndActivityReopen() {
        verifyRealGrantAndReopen("Documents")
    }

    @Test
    fun defaultDocscanFolderOpensAndGrantSurvivesReopen() {
        val created = SafPickerTestActions.createDefaultFolderFixture()
        var failure: Throwable? = null
        try {
            verifyRealGrantAndReopen("Docscan")
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try { SafPickerTestActions.removeDefaultFolderFixture(created) }
            catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test
    fun pickerSuggestsExactDefaultAndPreservesExplicitLocalOrCloudChoiceWithoutGrantingAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val expectedDefault = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Docscan")
        for ((selected, expected) in listOf(
            null to expectedDefault,
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments" to
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments"),
            "content://example.cloud.documents/tree/my-folder" to Uri.parse("content://example.cloud.documents/tree/my-folder"),
        )) {
            val intent = DestinationPickerIntent.create(selected)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
            @Suppress("DEPRECATION")
            val actual = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
            assertEquals(expected, actual)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            assertEquals(flags, intent.flags)
        }
        assertEquals("Hints must never create permission grants", before, context.contentResolver.persistedUriPermissions.map { it.uri }.toSet())
    }

    private fun verifyRealGrantAndReopen(folder: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "saf-picker-$suffix.db"
        val artifactDirectory = "saf-picker-artifacts-$suffix"
        val preexistingUris = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        var activeStorage = storage
        var scenario: ActivityScenario<ProductActivity>? = null
        var selectedUri: Uri? = null
        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            clickDestinationButton(scenario)

            val device = UiDevice.getInstance(instrumentation)
            if (folder == "Docscan") {
                assertTrue("System picker must initially open /Docscan", device.wait(
                    androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.res(
                        "com.google.android.documentsui", "breadcrumb_text",
                    ).text("Docscan")), 15_000L,
                ))
            }
            SafPickerTestActions.chooseTreeDirectory(device, requireNotNull(scenario), listOf(folder))

            awaitCondition("The app must record the selected primary-storage tree") {
                val grant = storage.destinationGrant()
                grant != null && grant.treeUri == DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$folder").toString()
            }
            val saved = storage.destinationGrant()
            assertNotNull("A successful picker result must create a DestinationGrant", saved)
            val grant = requireNotNull(saved)
            val chosenUri = Uri.parse(grant.treeUri)
            selectedUri = chosenUri
            assertTrue(
                "Only the delegated primary shared-storage scope is in this test",
                DocumentsContract.getTreeDocumentId(chosenUri).startsWith("primary:"),
            )
            assertTrue("Picker result must be a SAF tree", DocumentsContract.isTreeUri(chosenUri))
            assertTrue(
                "Android must retain read and write permission",
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == chosenUri && it.isReadPermission && it.isWritePermission
                },
            )
            assertTrue("Persisted grant must revalidate through the production manager", SafDestinationGrantManager(context).revalidate(grant, storage))
            scenario.onActivity { activity ->
                assertFalse(
                    "Save remains disabled until an exact provider tuple is qualified",
                    activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled ?: true,
                )
            }

            scenario.close()
            scenario = null
            storage.close()
            val reopened = ProductStorage(context, databaseName, artifactDirectory)
            activeStorage = reopened
            app.productStorageOverrideForTest = reopened
            val reopenedGrant = reopened.destinationGrant()
            assertEquals("The same tree URI must survive storage recreation", grant.treeUri, reopenedGrant?.treeUri)
            assertTrue(
                "The Android-owned URI permission must remain usable after storage recreation",
                SafDestinationGrantManager(context).revalidate(requireNotNull(reopenedGrant), reopened),
            )
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            scenario.onActivity { activity ->
                assertFalse(
                    "Reopened Activity must not enable Save without qualification",
                    activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled ?: true,
                )
            }
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            runCatching { activeStorage.close() }
            selectedUri?.takeIf { it !in preexistingUris }?.let { uri ->
                runCatching { SafDestinationGrantManager(context).releasePersistedGrant(uri.toString()) }
            }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun pickerCancelMissingAndDeniedGrantLeaveDestinationStateUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "saf-picker-cancel-$suffix.db"
        val artifactDirectory = "saf-picker-cancel-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            val before = storage.destinationGrant()
            val permissionsBefore = context.contentResolver.persistedUriPermissions.map {
                Triple(it.uri, it.isReadPermission, it.isWritePermission)
            }.toSet()
            clickDestinationButton(scenario)
            UiDevice.getInstance(instrumentation).pressBack()
            scenario.onActivity { }
            assertEquals("Cancel must not add or replace app grant metadata", before?.treeUri, storage.destinationGrant()?.treeUri)
            assertEquals(
                "Cancel must not change Android persisted URI permissions",
                permissionsBefore,
                context.contentResolver.persistedUriPermissions.map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet(),
            )

            deliverActivityResult(scenario, Intent(), Activity.RESULT_OK)
            val expectedStatus = context.getString(R.string.destination_failed, "Picker returned no folder")
            awaitCondition("Missing picker data must be visible") {
                var statusText = ""
                scenario.onActivity { activity ->
                    statusText = activity.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text?.toString().orEmpty()
                }
                statusText == expectedStatus
            }
            assertEquals("An OK result without a tree URI must not add or replace metadata", before?.treeUri, storage.destinationGrant()?.treeUri)

            val ungrantedTree = Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3Adocscan-ungranted-${UUID.randomUUID()}",
            )
            val forgedGrantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            deliverActivityResult(scenario, Intent().setData(ungrantedTree).addFlags(forgedGrantFlags), Activity.RESULT_OK)
            val failurePrefix = context.getString(R.string.destination_failed, "").substringBefore(":")
            awaitCondition("An ungranted URI must produce a visible failure") {
                var statusText = ""
                scenario.onActivity { activity ->
                    statusText = activity.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text?.toString().orEmpty()
                }
                statusText.startsWith(failurePrefix)
            }
            assertEquals("A denied OS grant must not be written to app storage", before?.treeUri, storage.destinationGrant()?.treeUri)
            assertEquals(
                "A denied OS grant must not add a persisted URI permission",
                permissionsBefore,
                context.contentResolver.persistedUriPermissions.map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet(),
            )
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            storage.close()
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
        }
    }

    private fun clickDestinationButton(scenario: ActivityScenario<ProductActivity>) {
        scenario.onActivity { activity ->
            val button = activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")
            assertNotNull("Destination button must be present", button)
            assertTrue("Destination button must be enabled", button.isEnabled)
            button.performClick()
        }
    }

    private fun deliverActivityResult(scenario: ActivityScenario<ProductActivity>, data: Intent, resultCode: Int) {
        scenario.onActivity { activity ->
            val result = androidx.activity.result.ActivityResult(resultCode, data)
            val handler = activity.javaClass.getDeclaredMethod("handleDestinationResult", result.javaClass).apply {
                isAccessible = true
            }
            handler.invoke(activity, result)
        }
    }

    private fun awaitActivityReady(scenario: ActivityScenario<ProductActivity>) {
        awaitCondition("ProductActivity storage initialization must finish") {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true
            }
            ready
        }
    }

    private fun awaitCondition(message: String, timeoutMillis: Long = 15_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }
}
