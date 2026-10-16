package app.docscan.android

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafRuntimeDestinationCheckInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun actualProviderCheckPersistsTypedTreeBoundEvidenceAndCleansItsOwnedDocuments() {
        val fixture = fixture("positive")
        try {
            val tree = chooseTree(fixture, "Documents")
            val initialChildren = listChildren(fixture.context, tree).associateBy { it.documentId }
            val evidenceDirectory = runtimeEvidenceDirectory(fixture.context)
            val beforeEvidence = evidenceDirectory.listFiles().orEmpty().map { it.name }.toSet()

            val checker = SafRuntimeDestinationCheck(fixture.context, fixture.storage)
            val result = checker.ensureReady(tree.toString())

            val payload = JSONObject(result.qualifiedCapabilitiesPayload)
            assertTrue("The record must use runtime-check provenance",
                payload.getString("provenance") == "RUNTIME_CAPABILITY_CHECK")
            assertFalse("Runtime evidence is not a formal qualification", payload.getBoolean("formalQualification"))
            assertEquals("Runtime readiness is scoped to the exact selected tree", tree.toString(), payload.getString("treeUri"))
            assertEquals(SafProviderIdentity.resolve(fixture.context, tree.toString()).adapterId, result.adapterId)
            assertEquals(Build.FINGERPRINT, result.osVersion)
            assertTrue("The typed capability set is complete", payload.getJSONObject("capabilities").let {
                it.getBoolean("createDoesNotOverwriteExistingDocument") &&
                    it.getBoolean("enumerateChildren") && it.getBoolean("readBackSha256")
            })
            val evidenceFile = File(payload.getString("evidencePath"))
            assertTrue("Raw runtime evidence is present", evidenceFile.isFile)
            assertEquals("Evidence bytes match the stored digest", payload.getString("evidenceSha256"), sha256(evidenceFile.readBytes()))
            val rawEvidence = JSONObject(evidenceFile.readText())
            assertTrue("No-overwrite is proved by a definite name conflict or distinct identity",
                rawEvidence.getString("duplicateOutcome") in setOf("DEFINITE_NAME_CONFLICT", "DISTINCT_IDENTITY"))
            assertEquals("The original probe bytes survive the duplicate-create attempt",
                rawEvidence.getString("payloadSha256"), rawEvidence.getString("originalHashAfterDuplicate"))
            assertTrue("The final provider listing is independently checked", rawEvidence.getBoolean("finalListingVerified"))
            assertTrue("Cleanup of checker-owned documents is independently checked", rawEvidence.getBoolean("ownedCleanupVerified"))
            assertEquals("Evidence sidecar repeats the evidence digest", payload.getString("evidenceSha256"),
                File(evidenceDirectory, evidenceFile.nameWithoutExtension + ".sha256").readText().trim())
            assertEquals("The capability checker leaves no owned provider documents", initialChildren.keys,
                listChildren(fixture.context, tree).map { it.documentId }.toSet())
            assertTrue("The successful check adds no pending operation journal",
                runtimeEvidenceDirectory(fixture.context).listFiles().orEmpty()
                    .filter { it.name !in beforeEvidence }.none { it.name.startsWith("pending-") })

            val events = mutableListOf<SafRuntimeCheckBoundary>()
            val reused = SafRuntimeDestinationCheck(
                fixture.context,
                fixture.storage,
                RealSafRuntimeProviderIo(fixture.context),
                SafRuntimeCheckObserver { boundary, _ -> events += boundary },
            ).ensureReady(tree.toString())
            assertEquals("An exact-tree ready record is reused", result.qualificationId, reused.qualificationId)
            assertTrue("Reusing readiness performs no provider operation", events.isEmpty())
            assertEquals(initialChildren.keys, listChildren(fixture.context, tree).map { it.documentId }.toSet())

            val otherTree = chooseAnotherTree(fixture, "Download")
            assertFalse("The second test destination must be a distinct exact tree", otherTree == tree)
            assertNull("Readiness for the first folder cannot authorize a different folder",
                checker.findReady(otherTree.toString()))
            val otherBaseline = listChildren(fixture.context, otherTree).map { it.documentId }.toSet()
            val otherReady = SafRuntimeDestinationCheck(fixture.context, fixture.storage).ensureReady(otherTree.toString())
            assertEquals(otherTree.toString(), JSONObject(otherReady.qualifiedCapabilitiesPayload).getString("treeUri"))
            assertFalse("A separate tree receives its own runtime record", result.qualificationId == otherReady.qualificationId)
            assertEquals("The second provider check also cleans its own documents", otherBaseline,
                listChildren(fixture.context, otherTree).map { it.documentId }.toSet())

            val oldDatabase = fixture.storage
            oldDatabase.close()
            fixture.storage = ProductStorage(fixture.context, fixture.databaseName, fixture.artifactDirectory)
            val reopened = SafRuntimeDestinationCheck(fixture.context, fixture.storage)
            val restored = reopened.findReady(tree.toString())
            assertNotNull("Readiness survives database reopen only with intact evidence", restored)
            assertEquals(result.qualificationId, restored?.qualificationId)
            assertEquals("The second tree's separate readiness also survives database reopen", otherReady.qualificationId,
                reopened.findReady(otherTree.toString())?.qualificationId)

            val createdFiles = runtimeEvidenceDirectory(fixture.context).listFiles().orEmpty()
                .filter { it.name !in beforeEvidence }
            assertTrue("At least one evidence packet was written", createdFiles.any { it.name.startsWith("evidence-") })
            android.util.Log.i(TAG, "RUNTIME_CHECK_PASS tree=$tree qualification=${result.qualificationId} " +
                "formal=false owned_cleanup=true evidence_sha256=${payload.getString("evidenceSha256")}")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun providerBoundaryFailuresNeverCreateReadinessAndOwnedDocumentsAreRecovered() {
        val permissionFixture = fixture("boundaries")
        var selectedTree: Uri? = null
        val createdEvidenceNames = mutableSetOf<String>()
        try {
            selectedTree = chooseTree(permissionFixture, "Documents")
            val tree = requireNotNull(selectedTree)
            val baselineIds = listChildren(permissionFixture.context, tree).map { it.documentId }.toSet()
            val evidenceDirectory = runtimeEvidenceDirectory(permissionFixture.context)
            val evidenceBefore = evidenceDirectory.listFiles().orEmpty().map { it.name }.toSet()
            val applicable = SafRuntimeCheckBoundary.entries
            val reached = mutableSetOf<SafRuntimeCheckBoundary>()

            applicable.forEach { target ->
                val suffix = UUID.randomUUID().toString().replace("-", "")
                val databaseName = "saf-runtime-failure-$suffix.db"
                val artifactDirectory = "saf-runtime-failure-artifacts-$suffix"
                val evidenceBeforeAttempt = evidenceDirectory.listFiles().orEmpty().map { it.name }.toSet()
                var storage = ProductStorage(permissionFixture.context, databaseName, artifactDirectory)
                storage.recordDestinationGrant(tree.toString())
                val events = mutableListOf<SafRuntimeCheckBoundary>()
                val checker = SafRuntimeDestinationCheck(
                    permissionFixture.context,
                    storage,
                    RealSafRuntimeProviderIo(permissionFixture.context, DuplicateCreateMode.RENAME_DISTINCT),
                    SafRuntimeCheckObserver { boundary, _ ->
                        events += boundary
                        if (boundary == target) throw IOException("injected failure at $target")
                    },
                )
                val failure = runCatching { checker.ensureReady(tree.toString()) }.exceptionOrNull()
                assertNotNull("Injected provider-boundary failure must propagate at $target", failure)
                reached += events
                assertNull("Failure at $target must not record runtime readiness", checker.findReady(tree.toString()))
                val evidenceAfterFailure = evidenceDirectory.listFiles().orEmpty().map { it.name }.toSet()
                val newFailureEvidence = evidenceAfterFailure - evidenceBeforeAttempt
                assertTrue("Failure evidence must be retained for $target",
                    newFailureEvidence.any { it.startsWith("evidence-failed-") })
                createdEvidenceNames += newFailureEvidence

                val pending = evidenceDirectory.listFiles().orEmpty().filter {
                    it.name !in evidenceBeforeAttempt && it.name.startsWith("pending-") && it.name.endsWith(".json")
                }
                if (pending.isNotEmpty()) {
                    // Reopen through the real provider path: it must resolve only the journaled owned URI,
                    // clean it, and then run a new check before it can persist readiness.
                    storage.close()
                    storage = ProductStorage(permissionFixture.context, databaseName, artifactDirectory)
                    val recovered = SafRuntimeDestinationCheck(permissionFixture.context, storage).ensureReady(tree.toString())
                    assertEquals("Recovery must remain tied to the selected tree", tree.toString(),
                        JSONObject(recovered.qualifiedCapabilitiesPayload).getString("treeUri"))
                    assertTrue("Recovery removes this failed run's pending journal",
                        evidenceDirectory.listFiles().orEmpty().none { it.name in pending.map(File::getName) })
                }
                assertEquals("Boundary failure/recovery leaves the original provider child identities unchanged at $target",
                    baselineIds, listChildren(permissionFixture.context, tree).map { it.documentId }.toSet())
                assertEquals("The isolated database has at most the recovery record created after the failed attempt",
                    if (pending.isEmpty()) 0 else 1,
                    storage.providerQualificationsFor(
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).adapterId,
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).providerVersion,
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).osVersion,
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).accessClass,
                    ).size,
                )
                storage.close()
                permissionFixture.context.deleteDatabase(databaseName)
                permissionFixture.context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            }

            assertTrue("Every applicable injected operation boundary was observed", applicable.all { it in reached })
            assertTrue("The real SAF tree decorator created and journaled a second distinct provider document",
                SafRuntimeCheckBoundary.AFTER_DUPLICATE_CREATE_JOURNALED in reached)
            evidenceDirectory.listFiles().orEmpty().filter { it.name !in evidenceBefore }
                .forEach { it.delete() }
            assertEquals("Test-only runtime evidence files are cleaned by exact owned filename", evidenceBefore,
                evidenceDirectory.listFiles().orEmpty().map { it.name }.toSet())
            android.util.Log.i(TAG, "RUNTIME_FAILURE_MATRIX_PASS boundaries=${reached.joinToString()} " +
                "duplicate=distinct-identity original_children=${baselineIds.size}")
        } finally {
            createdEvidenceNames.forEach { name -> File(runtimeEvidenceDirectory(permissionFixture.context), name).delete() }
            permissionFixture.close()
        }
    }

    @Test
    fun abruptInterruptionLeavesOwnedIdentityForNextCheckerToCleanBeforeRetry() {
        val fixture = fixture("interruption")
        try {
            val tree = chooseTree(fixture, "Documents")
            val before = listChildren(fixture.context, tree).map { it.documentId }.toSet()
            val evidenceDirectory = runtimeEvidenceDirectory(fixture.context)
            val observer = SafRuntimeCheckObserver { boundary, _ ->
                if (boundary == SafRuntimeCheckBoundary.AFTER_FIRST_CREATE_JOURNALED) {
                    throw SafRuntimeCheckInterruption("simulated process interruption after created URI was journaled")
                }
            }
            val interrupted = SafRuntimeDestinationCheck(
                fixture.context,
                fixture.storage,
                RealSafRuntimeProviderIo(fixture.context),
                observer,
            )
            val interruption = runCatching { interrupted.ensureReady(tree.toString()) }.exceptionOrNull()
            assertTrue("The interruption must escape as the designated abrupt-process surrogate",
                interruption is SafRuntimeCheckInterruption)
            val pendingBefore = evidenceDirectory.listFiles().orEmpty().filter {
                it.name !in fixture.priorEvidenceNames && it.name.startsWith("pending-") && it.name.endsWith(".json")
            }
            assertEquals("Created-URI interruption leaves exactly one pending journal", 1, pendingBefore.size)
            val journal = JSONObject(pendingBefore.single().readText())
            assertEquals(tree.toString(), journal.getString("treeUri"))
            assertEquals("PENDING_UNKNOWN", journal.getString("phase"))
            assertEquals("The owned URI is durably present before provider bytes are written", 1,
                journal.getJSONArray("ownedUris").length())
            assertNull("Interrupted checks never become ready", interrupted.findReady(tree.toString()))

            val reopenedStorage = ProductStorage(fixture.context, fixture.databaseName, fixture.artifactDirectory)
            fixture.storage = reopenedStorage
            val ready = SafRuntimeDestinationCheck(fixture.context, reopenedStorage).ensureReady(tree.toString())
            assertTrue("The next checker cleans the old owned document before recording new readiness",
                JSONObject(ready.qualifiedCapabilitiesPayload).getBoolean("formalQualification").not())
            assertTrue("The interrupted journal is resolved", evidenceDirectory.listFiles().orEmpty().none {
                it.name.startsWith("pending-")
            })
            assertEquals("No owned probe output survives recovery and the new successful check", before,
                listChildren(fixture.context, tree).map { it.documentId }.toSet())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun providerPortFailuresAtCreateStreamsReadBackAndCleanupNeverBecomeReady() {
        val permissionFixture = fixture("provider-port-faults")
        try {
            val tree = chooseTree(permissionFixture, "Documents")
            val baselineIds = listChildren(permissionFixture.context, tree).map { it.documentId }.toSet()
            val modes = listOf(
                ProviderPortFault.ENUMERATE_THROW,
                ProviderPortFault.CREATE_THROW,
                ProviderPortFault.CREATE_NULL,
                ProviderPortFault.CREATE_EFFECT_THEN_THROW,
                ProviderPortFault.WRITE_THROW,
                ProviderPortFault.FLUSH_THROW,
                ProviderPortFault.CLOSE_THROW,
                ProviderPortFault.READ_THROW,
                ProviderPortFault.READ_TRUNCATED,
                ProviderPortFault.READ_HASH_MISMATCH,
                ProviderPortFault.DELETE_FALSE,
            )
            modes.forEach { mode ->
                val suffix = UUID.randomUUID().toString().replace("-", "")
                val databaseName = "saf-runtime-port-fault-$suffix.db"
                val artifactDirectory = "saf-runtime-port-fault-artifacts-$suffix"
                var storage = ProductStorage(permissionFixture.context, databaseName, artifactDirectory)
                storage.recordDestinationGrant(tree.toString())
                val providerIo = RealSafRuntimeProviderIo(permissionFixture.context, portFault = mode)
                val checker = SafRuntimeDestinationCheck(
                    permissionFixture.context,
                    storage,
                    providerIo,
                    SafRuntimeCheckObserver.NONE,
                )
                val failure = runCatching { checker.ensureReady(tree.toString()) }.exceptionOrNull()
                assertNotNull("Provider port fault must fail closed at $mode", failure)
                assertNull("Provider port fault at $mode must not persist readiness", checker.findReady(tree.toString()))

                if (mode == ProviderPortFault.CREATE_EFFECT_THEN_THROW) {
                    // The provider created a random child but withheld its URI. The production checker must
                    // leave that unresolved identity alone; the test adapter knows this exact test child.
                    val created = requireNotNull(providerIo.lastCreatedUri)
                    assertTrue("Lost create identity leaves a durable pending journal", runtimeEvidenceDirectory(permissionFixture.context)
                        .listFiles().orEmpty().any { it.name.startsWith("pending-") && it.readText().contains(tree.toString()) })
                    assertTrue("Test removes only the URI it directly observed creating", providerIo.deleteDocument(created))
                }

                storage.close()
                storage = ProductStorage(permissionFixture.context, databaseName, artifactDirectory)
                val recovered = SafRuntimeDestinationCheck(permissionFixture.context, storage).ensureReady(tree.toString())
                assertEquals("Recovery qualification remains exact-tree scoped", tree.toString(),
                    JSONObject(recovered.qualifiedCapabilitiesPayload).getString("treeUri"))
                assertEquals("Every injected fault and recovery restores the original provider child IDs at $mode",
                    baselineIds, listChildren(permissionFixture.context, tree).map { it.documentId }.toSet())
                assertEquals("Only recovery, after the failed operation is resolved, can record readiness", 1,
                    storage.providerQualificationsFor(
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).adapterId,
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).providerVersion,
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).osVersion,
                        SafProviderIdentity.resolve(permissionFixture.context, tree.toString()).accessClass,
                    ).size)
                storage.close()
                permissionFixture.context.deleteDatabase(databaseName)
                permissionFixture.context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            }
            android.util.Log.i(TAG, "RUNTIME_PROVIDER_IO_FAILURE_MATRIX_PASS cases=${modes.joinToString()} baseline_ids=${baselineIds.size}")
        } finally {
            permissionFixture.close()
        }
    }

    @Test
    fun definiteExistingNameConflictIsProvedWithoutChangingTheOriginalProbe() {
        val fixture = fixture("definite-conflict")
        try {
            val tree = chooseTree(fixture, "Documents")
            val baselineIds = listChildren(fixture.context, tree).map { it.documentId }.toSet()
            val checker = SafRuntimeDestinationCheck(
                fixture.context,
                fixture.storage,
                RealSafRuntimeProviderIo(fixture.context, duplicateCreateMode = DuplicateCreateMode.DEFINITE_CONFLICT),
                SafRuntimeCheckObserver.NONE,
            )
            val ready = checker.ensureReady(tree.toString())
            val payload = JSONObject(ready.qualifiedCapabilitiesPayload)
            val evidence = JSONObject(File(payload.getString("evidencePath")).readText())
            assertEquals("Provider proves duplicate creation did not replace the existing document",
                "DEFINITE_NAME_CONFLICT", evidence.getString("duplicateOutcome"))
            assertEquals(evidence.getString("payloadSha256"), evidence.getString("originalHashAfterDuplicate"))
            assertEquals("A rejected duplicate does not create an additional child", baselineIds,
                listChildren(fixture.context, tree).map { it.documentId }.toSet())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun distinctDuplicateIdentityIsJournaledReadBackCheckedAndRemoved() {
        val fixture = fixture("distinct-duplicate")
        try {
            val tree = chooseTree(fixture, "Documents")
            val baselineIds = listChildren(fixture.context, tree).map { it.documentId }.toSet()
            val checker = SafRuntimeDestinationCheck(
                fixture.context,
                fixture.storage,
                RealSafRuntimeProviderIo(fixture.context, duplicateCreateMode = DuplicateCreateMode.RENAME_DISTINCT),
                SafRuntimeCheckObserver.NONE,
            )
            val ready = checker.ensureReady(tree.toString())
            val payload = JSONObject(ready.qualifiedCapabilitiesPayload)
            val evidence = JSONObject(File(payload.getString("evidencePath")).readText())
            assertEquals("A distinct returned identity is accepted only after being independently listed",
                "DISTINCT_IDENTITY", evidence.getString("duplicateOutcome"))
            assertEquals(evidence.getString("payloadSha256"), evidence.getString("originalHashAfterDuplicate"))
            assertTrue(evidence.getBoolean("finalListingVerified"))
            assertTrue(evidence.getBoolean("ownedCleanupVerified"))
            assertEquals("Both the original and auto-renamed owned URI are cleaned after evidence capture", baselineIds,
                listChildren(fixture.context, tree).map { it.documentId }.toSet())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun providerAutoRenameIsDetectedBeforeAnyWriteAndOnlyOwnedUriIsCleaned() {
        val fixture = fixture("first-name-rename")
        try {
            val tree = chooseTree(fixture, "Documents")
            val baselineIds = listChildren(fixture.context, tree).map { it.documentId }.toSet()
            val evidenceBefore = runtimeEvidenceDirectory(fixture.context).listFiles().orEmpty().map { it.name }.toSet()
            val events = mutableListOf<SafRuntimeCheckBoundary>()
            val checker = SafRuntimeDestinationCheck(
                fixture.context,
                fixture.storage,
                RealSafRuntimeProviderIo(fixture.context, firstCreateMode = FirstCreateMode.RENAME_NAME),
                SafRuntimeCheckObserver { boundary, _ -> events += boundary },
            )
            val failure = runCatching { checker.ensureReady(tree.toString()) }.exceptionOrNull()
            assertNotNull("Provider auto-rename must fail exact-name validation", failure)
            val failureEvidence = runtimeEvidenceDirectory(fixture.context).listFiles().orEmpty()
                .first { it.name !in evidenceBefore && it.name.startsWith("evidence-failed-") && it.name.endsWith(".json") }
            val failurePayload = JSONObject(failureEvidence.readText())
            val ownedUris = failurePayload.getJSONArray("ownedUris")
            assertEquals("Provider returned URI was journaled as owned before its name was rejected", 1, ownedUris.length())
            assertTrue("The returned document identity was absent from the baseline", DocumentsContract.getDocumentId(
                Uri.parse(ownedUris.getString(0))) !in baselineIds)
            assertTrue("No output stream was opened after the provider changed the requested name",
                SafRuntimeCheckBoundary.BEFORE_FIRST_WRITE !in events)
            assertNull("Renamed provider child cannot become readiness", checker.findReady(tree.toString()))
            assertEquals("Cleanup uses the returned owned URI and restores the baseline listing", baselineIds,
                listChildren(fixture.context, tree).map { it.documentId }.toSet())
        } finally {
            fixture.close()
        }
    }

    private fun chooseTree(fixture: Fixture, folder: String): Uri {
        val scenario = ActivityScenario.launch(ProductActivity::class.java)
        fixture.scenario = scenario
        awaitActivityReady(scenario)
        scenario.onActivity { activity ->
            val button = requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button"))
            check(button.isEnabled)
            button.performClick()
        }
        SafPickerTestActions.chooseTreeDirectory(
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
            scenario,
            listOf(folder),
        )
        return Uri.parse(requireNotNull(fixture.storage.destinationGrant()).treeUri).also {
            fixture.selectedTrees += it.toString()
        }
    }

    private fun chooseAnotherTree(fixture: Fixture, folder: String): Uri {
        val scenario = requireNotNull(fixture.scenario)
        scenario.onActivity { activity ->
            val button = requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button"))
            check(button.isEnabled)
            button.performClick()
        }
        SafPickerTestActions.chooseTreeDirectory(
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
            scenario,
            listOf(folder),
        )
        return Uri.parse(requireNotNull(fixture.storage.destinationGrant()).treeUri).also {
            fixture.selectedTrees += it.toString()
        }
    }

    private fun awaitActivityReady(scenario: ActivityScenario<ProductActivity>) {
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = it.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true }
            if (ready) return
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("ProductActivity destination action did not initialize")
    }

    private fun listChildren(context: Context, tree: Uri): List<SafRuntimeChild> = RealSafRuntimeProviderIo(context).listChildren(tree)

    private fun runtimeEvidenceDirectory(context: Context) = File(context.noBackupFilesDir, "saf-runtime-destination-checks")

    private fun fixture(label: String): Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "saf-runtime-$label-$suffix.db"
        val artifactDirectory = "saf-runtime-$label-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val previousOverride = app.productStorageOverrideForTest
        val priorPermissionUris = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        app.productStorageOverrideForTest = storage
        val evidenceDirectory = runtimeEvidenceDirectory(context)
        return Fixture(
            context, app, databaseName, artifactDirectory, storage, previousOverride,
            priorPermissionUris, evidenceDirectory.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private enum class DuplicateCreateMode { PROVIDER_DEFAULT, RENAME_DISTINCT, DEFINITE_CONFLICT }
    private enum class FirstCreateMode { PROVIDER_DEFAULT, RENAME_NAME }

    private enum class ProviderPortFault {
        NONE, ENUMERATE_THROW, CREATE_THROW, CREATE_NULL, CREATE_EFFECT_THEN_THROW,
        WRITE_THROW, FLUSH_THROW, CLOSE_THROW, READ_THROW, READ_TRUNCATED, READ_HASH_MISMATCH, DELETE_FALSE,
    }

    private class RealSafRuntimeProviderIo(
        context: Context,
        private val duplicateCreateMode: DuplicateCreateMode = DuplicateCreateMode.PROVIDER_DEFAULT,
        private val portFault: ProviderPortFault = ProviderPortFault.NONE,
        private val firstCreateMode: FirstCreateMode = FirstCreateMode.PROVIDER_DEFAULT,
    ) : SafRuntimeProviderIo {
        private val resolver = context.contentResolver
        private var createOrdinal = 0
        private var inputOrdinal = 0
        private var deleteOrdinal = 0
        var lastCreatedUri: Uri? = null
            private set

        override fun listChildren(treeUri: Uri): List<SafRuntimeChild> {
            if (portFault == ProviderPortFault.ENUMERATE_THROW) {
                throw IOException("injected provider child enumeration failure")
            }
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
            val children = mutableListOf<SafRuntimeChild>()
            resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(idColumn) ?: continue
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    children += SafRuntimeChild(documentUri, documentId, cursor.getString(nameColumn).orEmpty())
                }
            } ?: throw IOException("Provider returned no child listing")
            return children
        }

        override fun createDocument(treeUri: Uri, mimeType: String, displayName: String): Uri? {
            createOrdinal += 1
            if (createOrdinal == 1 && portFault == ProviderPortFault.CREATE_THROW) {
                throw IOException("injected provider create failure before side effect")
            }
            if (createOrdinal == 1 && portFault == ProviderPortFault.CREATE_NULL) return null
            if (createOrdinal == 1 && portFault == ProviderPortFault.CREATE_EFFECT_THEN_THROW) {
                lastCreatedUri = createDocumentActual(treeUri, mimeType, displayName)
                throw IOException("injected lost create response after actual provider side effect")
            }
            if (createOrdinal == 1 && firstCreateMode == FirstCreateMode.RENAME_NAME) {
                return createDocumentActual(treeUri, mimeType, "$displayName (1)").also { lastCreatedUri = it }
            }
            if (createOrdinal == 2 && duplicateCreateMode == DuplicateCreateMode.RENAME_DISTINCT) {
                return createDocumentActual(treeUri, mimeType, "$displayName (1)")
            }
            if (createOrdinal == 2 && duplicateCreateMode == DuplicateCreateMode.DEFINITE_CONFLICT) {
                throw IOException("EEXIST: file already exists")
            }
            return createDocumentActual(treeUri, mimeType, displayName).also { if (createOrdinal == 1) lastCreatedUri = it }
        }

        private fun createDocumentActual(treeUri: Uri, mimeType: String, displayName: String): Uri? {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            return DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
        }

        override fun openOutputStream(documentUri: Uri): OutputStream? {
            val delegate = resolver.openOutputStream(documentUri, "w") ?: return null
            if (portFault !in setOf(ProviderPortFault.WRITE_THROW, ProviderPortFault.FLUSH_THROW, ProviderPortFault.CLOSE_THROW)) return delegate
            return object : OutputStream() {
                override fun write(value: Int) = delegate.write(value)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (portFault == ProviderPortFault.WRITE_THROW) {
                        if (length > 1) delegate.write(bytes, offset, length / 2)
                        throw IOException("injected output write failure after partial bytes")
                    }
                    delegate.write(bytes, offset, length)
                }
                override fun flush() {
                    delegate.flush()
                    if (portFault == ProviderPortFault.FLUSH_THROW) throw IOException("injected output flush failure")
                }
                override fun close() {
                    delegate.close()
                    if (portFault == ProviderPortFault.CLOSE_THROW) throw IOException("injected output close failure")
                }
            }
        }

        override fun openInputStream(documentUri: Uri): InputStream? {
            inputOrdinal += 1
            if (inputOrdinal == 1 && portFault == ProviderPortFault.READ_THROW) throw IOException("injected provider read failure")
            val delegate = resolver.openInputStream(documentUri) ?: return null
            if (inputOrdinal != 1 || portFault !in setOf(ProviderPortFault.READ_TRUNCATED, ProviderPortFault.READ_HASH_MISMATCH)) return delegate
            return object : InputStream() {
                private val limit = 64
                private var readCount = 0
                private var changedByte = false
                override fun read(): Int {
                    if (portFault == ProviderPortFault.READ_TRUNCATED && readCount >= limit) return -1
                    val value = delegate.read()
                    if (value < 0) return -1
                    readCount += 1
                    return if (portFault == ProviderPortFault.READ_HASH_MISMATCH && !changedByte) {
                        changedByte = true
                        value xor 0x01
                    } else value
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (portFault == ProviderPortFault.READ_TRUNCATED && readCount >= limit) return -1
                    val bounded = if (portFault == ProviderPortFault.READ_TRUNCATED) minOf(length, limit - readCount) else length
                    val count = delegate.read(bytes, offset, bounded)
                    if (count > 0) {
                        if (portFault == ProviderPortFault.READ_HASH_MISMATCH && !changedByte) {
                            bytes[offset] = (bytes[offset].toInt() xor 0x01).toByte()
                            changedByte = true
                        }
                        readCount += count
                    }
                    return count
                }
                override fun close() = delegate.close()
            }
        }

        override fun deleteDocument(documentUri: Uri): Boolean {
            deleteOrdinal += 1
            if (deleteOrdinal == 1 && portFault == ProviderPortFault.DELETE_FALSE) return false
            return DocumentsContract.deleteDocument(resolver, documentUri)
        }
    }

    private class Fixture(
        val context: Context,
        private val app: DocScanApplication,
        val databaseName: String,
        val artifactDirectory: String,
        var storage: ProductStorage,
        private val previousOverride: ProductStorage?,
        private val priorPermissionUris: Set<String>,
        val priorEvidenceNames: Set<String>,
    ) {
        var scenario: ActivityScenario<ProductActivity>? = null
        val selectedTrees = mutableSetOf<String>()

        fun close() {
            scenario?.close()
            app.productStorageOverrideForTest = previousOverride
            runCatching { storage.close() }
            selectedTrees.filterNot { it in priorPermissionUris }.forEach { treeUri ->
                runCatching { SafDestinationGrantManager(context).releasePersistedGrant(treeUri) }
            }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            File(context.noBackupFilesDir, SafRuntimeDestinationCheck.CHECK_DIRECTORY).listFiles().orEmpty()
                .filter { it.name !in priorEvidenceNames }
                .forEach { it.delete() }
        }
    }

    companion object {
        private const val TAG = "SafRuntimeDestinationCheck"
    }
}
