package app.docscan.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SafProviderCapabilityProbeInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun primaryDocumentsProviderEnumeratesWritesReadsBackAndDoesNotOverwriteOnDuplicateCreate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "saf-probe-$suffix.db"
        val artifactDirectory = "saf-probe-artifacts-$suffix"
        val preexistingPermissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var firstDocument: Uri? = null
        var duplicateDocument: Uri? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        val requestedName = "docscan-provider-probe-$suffix.txt"
        val payload = ByteArray(256 * 1024) { index -> ((index * 31 + index / 251 + 17) and 0xff).toByte() }
        val expectedHash = sha256(payload)

        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitActivityReady(scenario)
            clickDestinationButton(scenario)
            selectDocumentsFolder(UiDevice.getInstance(instrumentation))

            val grant = awaitDestinationGrant(storage)
            tree = Uri.parse(grant.treeUri)
            assertEquals("The selected SAF tree must be the delegated existing primary Documents folder",
                "primary:Documents", DocumentsContract.getTreeDocumentId(tree))
            assertTrue("The real picker must leave persistent read/write permission",
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == tree && it.isReadPermission && it.isWritePermission
                })
            assertTrue("Production grant manager must revalidate the selected tree",
                SafDestinationGrantManager(context).revalidate(grant, storage))
            scenario.onActivity { activity ->
                assertFalse(
                    "Save must stay disabled while this probe has not recorded a qualification",
                    activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled ?: true,
                )
            }

            val authority = requireNotNull(tree.authority)
            val provider = context.packageManager.resolveContentProvider(authority, 0)
            assertNotNull("Selected tree authority must resolve to a provider", provider)
            val providerPackage = requireNotNull(provider).packageName
            @Suppress("DEPRECATION")
            val providerInfo = context.packageManager.getPackageInfo(providerPackage, 0)
            val providerVersion = "${providerInfo.versionName.orEmpty()}#${providerInfo.longVersionCode}"
            val adapterId = "${ProductActivity.SAF_ADAPTER_ID}/$providerPackage/$authority"
            val accessClass = ProductActivity.SAF_ACCESS_CLASS
            assertNull("This probe must not provision a provider qualification", storage.providerQualificationFor(
                adapterId, providerVersion, Build.FINGERPRINT, accessClass,
            ))
            android.util.Log.i(
                LOG_TAG,
                "PROVIDER_TUPLE authority=$authority package=$providerPackage version=$providerVersion " +
                    "os=${Build.FINGERPRINT} access=$accessClass tree=$tree",
            )

            val resolver = context.contentResolver
            val treeId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
            val before = listChildren(resolver, tree, treeId)
            assertFalse("Random probe name must not exist before this run", before.any { it.name == requestedName })
            android.util.Log.i(LOG_TAG, "CHILD_ENUMERATION before_count=${before.size} requested_absent=true")

            firstDocument = DocumentsContract.createDocument(resolver, parent, "text/plain", requestedName)
            assertNotNull("Positive control: provider must create a unique new document", firstDocument)
            writeFully(resolver, requireNotNull(firstDocument), payload)
            val firstReadBack = readFully(resolver, firstDocument!!)
            assertEquals("Provider must return every byte written", payload.size, firstReadBack.size)
            assertEquals("Full read-back SHA-256 must match", expectedHash, sha256(firstReadBack))

            var duplicateException: Exception? = null
            try {
                duplicateDocument = DocumentsContract.createDocument(resolver, parent, "text/plain", requestedName)
            } catch (failure: Exception) {
                duplicateException = failure
            }
            if (duplicateDocument == null) {
                assertNotNull("Duplicate rejection must be explicit; unrelated create errors do not qualify", duplicateException)
                val errorText = "${duplicateException!!::class.java.simpleName} ${duplicateException.message.orEmpty()}"
                    .lowercase()
                assertTrue("Duplicate rejection must identify an existing/conflicting name: $errorText",
                    listOf("exist", "duplicate", "already", "conflict").any(errorText::contains))
                android.util.Log.i(LOG_TAG, "DUPLICATE_CREATE outcome=rejected error=$errorText")
            } else {
                assertNotEquals("Duplicate create must return a distinct document identity", firstDocument, duplicateDocument)
                assertTrue("Provider enumeration must expose the newly created second identity",
                    listChildren(resolver, tree, treeId).any { it.id == DocumentsContract.getDocumentId(duplicateDocument) })
                android.util.Log.i(LOG_TAG, "DUPLICATE_CREATE outcome=separate_uri uri=$duplicateDocument")
            }

            val preserved = readFully(resolver, firstDocument!!)
            assertEquals("The original document must retain its full byte count", payload.size, preserved.size)
            assertEquals("The original document must not be overwritten by a same-name create", expectedHash, sha256(preserved))
            val after = listChildren(resolver, tree, treeId)
            assertTrue("Child enumeration must retain the original provider document ID",
                after.any { it.id == DocumentsContract.getDocumentId(firstDocument) })
            assertNull("No qualification row may be written by this probe", storage.providerQualificationFor(
                adapterId, providerVersion, Build.FINGERPRINT, accessClass,
            ))
            android.util.Log.i(
                LOG_TAG,
                "PROBE_PASS bytes=${payload.size} sha256=$expectedHash children_before=${before.size} " +
                    "children_after=${after.size} original_id=${DocumentsContract.getDocumentId(firstDocument)}",
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            val resolver = context.contentResolver
            listOfNotNull(duplicateDocument, firstDocument).distinct().forEach { uri ->
                if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false).not()) {
                    cleanupFailure = IllegalStateException("Provider did not delete test-owned document $uri", cleanupFailure)
                }
            }
            tree?.let { selectedTree ->
                var grantReleasedByTest = false
                runCatching {
                    val id = DocumentsContract.getTreeDocumentId(selectedTree)
                    val ownedIds = listOfNotNull(firstDocument, duplicateDocument).map(DocumentsContract::getDocumentId).toSet()
                    val leftovers = listChildren(resolver, selectedTree, id).filter { it.id in ownedIds }
                    if (leftovers.isNotEmpty()) throw IllegalStateException("Test-owned provider children remain after cleanup: $leftovers")
                }.onFailure { cleanupFailure = it }
                if (selectedTree !in preexistingPermissions) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onSuccess { grantReleasedByTest = true }
                        .onFailure { cleanupFailure = it }
                }
                if (cleanupFailure == null) {
                    android.util.Log.i(
                        LOG_TAG,
                        "CLEANUP_PASS created_ids_removed=true grant_released_by_test=$grantReleasedByTest",
                    )
                }
            }
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("AOSP provider probe failed or cleanup was incomplete", it) }
    }

    private fun selectDocumentsFolder(device: UiDevice) {
        val timeout = 15_000L
        assertTrue("System DocumentsUI must launch", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), timeout))
        val useFolder = By.text(Pattern.compile("(?i)^use this folder$"))
        val allow = By.text(Pattern.compile("(?i)^allow$"))
        SafPickerTestActions.openPrimaryDirectory(device, listOf("Documents"))
        SafPickerTestActions.clickFresh(
            device,
            useFolder,
            "Use shared-storage Documents folder",
            timeoutMs = timeout,
            completed = { device.hasObject(allow) || device.hasObject(By.pkg("app.docscan.scanner")) },
        )
        if (device.wait(Until.hasObject(allow), 3_000L)) {
            SafPickerTestActions.clickFresh(
                device,
                allow,
                "Allow shared-storage Documents access",
                timeoutMs = 3_000L,
                completed = { device.hasObject(By.pkg("app.docscan.scanner")) },
            )
        }
        assertTrue("DocumentsUI must return to the app", device.wait(Until.hasObject(By.pkg("app.docscan.scanner")), timeout))
    }

    private fun listChildren(
        resolver: android.content.ContentResolver,
        tree: Uri,
        treeId: String,
    ): List<Child> {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val children = mutableListOf<Child>()
        resolver.query(
            childUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) children += Child(cursor.getString(idColumn), cursor.getString(nameColumn))
        } ?: throw IllegalStateException("Provider returned no child listing")
        return children
    }

    private fun writeFully(resolver: android.content.ContentResolver, uri: Uri, bytes: ByteArray) {
        val output = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Provider returned no output stream")
        output.use { stream -> stream.write(bytes); stream.flush() }
    }

    private fun readFully(resolver: android.content.ContentResolver, uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Provider returned no read-back stream")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun clickDestinationButton(scenario: ActivityScenario<ProductActivity>) {
        scenario.onActivity { activity ->
            val button = activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")
            assertNotNull("Destination picker button must exist", button)
            assertTrue("Destination picker must be enabled after startup", button.isEnabled)
            button.performClick()
        }
    }

    private fun awaitActivityReady(scenario: ActivityScenario<ProductActivity>) {
        awaitCondition("ProductActivity must initialize storage") {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true
            }
            ready
        }
    }

    private fun awaitDestinationGrant(storage: ProductStorage): app.docscan.core.store.DestinationGrantSnapshot {
        var result: app.docscan.core.store.DestinationGrantSnapshot? = null
        awaitCondition("Picker result must create a destination grant") {
            result = storage.destinationGrant()
            result != null
        }
        return requireNotNull(result)
    }

    private fun awaitCondition(message: String, timeoutMillis: Long = 15_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private data class Child(val id: String, val name: String)

    private companion object {
        const val LOG_TAG = "SafProviderProbe"
    }
}
