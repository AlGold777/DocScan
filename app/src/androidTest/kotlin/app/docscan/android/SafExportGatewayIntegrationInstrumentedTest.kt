package app.docscan.android

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.ImportSessionId
import app.docscan.core.store.BeginExportAttemptCommand
import app.docscan.core.store.ExternalExportPackagePlan
import app.docscan.core.store.ProviderOperationObservationCommand
import app.docscan.core.store.ProviderQualificationCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class SafExportGatewayIntegrationInstrumentedTest {
    @get:Rule val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun saveReadBackProcessDeathProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> saveReadBackDeathCheckpoint(context, app, fixtureId)
            "resume" -> resumeSaveReadBackAfterProcessDeath(context, app, fixtureId)
            "cleanup" -> cleanupSaveReadBackFixture(context, app, fixtureId)
            else -> throw AssertionError("Unknown Save/read-back process-death phase: $phase")
        }
    }

    @Test
    fun saveCreatedBeforeStreamProcessDeathProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> saveCreatedBeforeStreamDeathCheckpoint(context, app, fixtureId)
            "resume" -> resumeSaveCreatedBeforeStreamAfterProcessDeath(context, app, fixtureId)
            "cleanup" -> cleanupSaveReadBackFixture(context, app, fixtureId)
            else -> throw AssertionError("Unknown Save/create process-death phase: $phase")
        }
    }

    @Test
    fun savePartialProviderWriteProcessDeathProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> savePartialProviderWriteDeathCheckpoint(context, app, fixtureId)
            "resume" -> resumePartialProviderWriteAfterProcessDeath(context, app, fixtureId)
            "cleanup" -> cleanupSaveReadBackFixture(context, app, fixtureId)
            else -> throw AssertionError("Unknown Save/partial-write process-death phase: $phase")
        }
    }

    @Test
    fun saveStreamClosedBeforeReadBackProcessDeathProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> saveStreamClosedBeforeReadBackDeathCheckpoint(context, app, fixtureId)
            "resume" -> resumeStreamClosedBeforeReadBackAfterProcessDeath(context, app, fixtureId)
            "cleanup" -> cleanupSaveReadBackFixture(context, app, fixtureId)
            else -> throw AssertionError("Unknown Save/closed-stream process-death phase: $phase")
        }
    }

    private fun savePartialProviderWriteDeathCheckpoint(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val fixture = newRecoveryFixture(fixtureId)
        val fixtureRoot = recoveryDeathFixtureRoot(context, fixtureId).apply { mkdirs() }
        val setupFile = File(fixtureRoot, "setup.properties")
        writeRecoveryFixtureProperties(fixture, setupFile)
        val checkpointFile = File(fixtureRoot, "checkpoint.properties")
        val checkpointReached = CountDownLatch(1)
        val hostKillBarrier = CountDownLatch(1)
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            fixture.storage.exportFirstProviderWriteBoundaryObserverForTest = { attemptId, exportId, outputId, documentUri, firstChunkBytes, expectedSize, expectedSha ->
                val attempt = requireNotNull(fixture.storage.exportAttempt(ExportAttemptId.parse(attemptId)))
                assertEquals("Provider stream has received a prefix but app outcome is uncommitted", "WRITING_EXTERNAL", attempt.state)
                assertEquals("Partial output URI is not durably acknowledged yet", null, attempt.externalIdentity)
                assertEquals("Package remains in its in-flight state", "WRITING_EXTERNAL",
                    fixture.storage.externalExportPackage(ExportArtifactId.parse(exportId))?.state)
                assertTrue("The first provider write must be non-empty", firstChunkBytes > 0)
                assertTrue("Checkpoint must precede completion of the committed artifact", firstChunkBytes.toLong() < expectedSize)
                assertTrue("Frozen source Page remains selected and present", fixture.storage.workspaceProjection().pages.single().selected)
                val checkpoint = Properties().apply {
                    load(setupFile.inputStream())
                    setProperty("attemptId", attemptId)
                    setProperty("exportArtifactId", exportId)
                    setProperty("outputArtifactId", outputId)
                    setProperty("providerUri", documentUri)
                    setProperty("firstChunkBytes", firstChunkBytes.toString())
                    setProperty("expectedSizeBytes", expectedSize.toString())
                    setProperty("expectedSha256", expectedSha)
                }
                FileOutputStream(checkpointFile).use { stream ->
                    checkpoint.store(stream, "SAF provider stream after first flushed output prefix")
                    stream.fd.sync()
                }
                android.util.Log.i(TAG,
                    "SAVE_PARTIAL_WRITE_CHECKPOINT fixture=$fixtureId attempt=$attemptId export=$exportId output=$outputId provider_uri=$documentUri first_chunk_bytes=$firstChunkBytes expected_size=$expectedSize expected_sha256=$expectedSha external_identity=null")
                checkpointReached.countDown()
                check(hostKillBarrier.await(10, TimeUnit.MINUTES)) {
                    "Host did not kill app after first provider output block"
                }
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("ProductActivity Save becomes available") {
                var enabled = false
                scenario!!.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("ProductActivity Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000L))
            device.findObject(By.clazz("android.widget.EditText")).text = fixture.basename
            val saveAction = By.text("Save selected pages")
            assertTrue("ProductActivity Save confirmation is available", device.wait(Until.hasObject(saveAction), 10_000L))
            device.findObject(saveAction).click()
            assertTrue("First provider write checkpoint was not reached", checkpointReached.await(120, TimeUnit.SECONDS))
            assertTrue("Host must terminate the app while the provider stream remains open", hostKillBarrier.await(10, TimeUnit.MINUTES))
            throw AssertionError("First provider-write checkpoint returned without host process death")
        } finally {
            scenario?.close()
            if (!checkpointFile.exists()) cleanupRecoveryFixture(fixture, emptyList())
        }
    }

    private fun resumePartialProviderWriteAfterProcessDeath(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val root = recoveryDeathFixtureRoot(context, fixtureId)
        val setup = Properties().apply { File(root, "checkpoint.properties").inputStream().use(::load) }
        val fixture = recoveryFixtureFromProperties(context, app, setup, fixtureId)
        val attemptId = ExportAttemptId.parse(setup.getProperty("attemptId"))
        val exportId = ExportArtifactId.parse(setup.getProperty("exportArtifactId"))
        val providerUri = Uri.parse(setup.getProperty("providerUri"))
        val expectedSize = setup.getProperty("expectedSizeBytes").toLong()
        val expectedSha = setup.getProperty("expectedSha256")
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            assertFalse("Workspace integrity gate must allow the isolated valid fixture", fixture.storage.startupIntegrityBlocked)
            val recoveredAttempt = requireNotNull(fixture.storage.exportAttempt(attemptId))
            assertEquals("Process death during provider write resolves to UnknownOutcome", "UNKNOWN_OUTCOME", recoveredAttempt.state)
            assertEquals("Partial output URI is not durably recorded", null, recoveredAttempt.externalIdentity)
            assertEquals("Partially written package remains unresolved", "UNKNOWN_OUTCOME",
                fixture.storage.externalExportPackage(exportId)?.state)
            assertTrue("No Recent row is published for unresolved output", fixture.storage.recentDocuments().isEmpty())
            assertTrue("Frozen source Page remains selected and present", fixture.storage.workspaceProjection().pages.single().selected)
            val providerBeforeRecovery = childUris(context.contentResolver, fixture.tree)
            val ownedBefore = childDocuments(context.contentResolver, fixture.tree)
                .filter { it.name.startsWith(fixture.basename) }
            assertEquals("One provider document exists for this fixture", 1, ownedBefore.size)
            assertEquals(providerUri, ownedBefore.single().uri)
            val bytesBeforeRecovery = requireNotNull(context.contentResolver.openInputStream(providerUri)).use { it.readBytes() }
            assertTrue("Provider retained a non-empty prefix", bytesBeforeRecovery.isNotEmpty())
            assertTrue("Provider output is a strict prefix of the committed artifact", bytesBeforeRecovery.size.toLong() < expectedSize)
            val prefixSha = sha256(bytesBeforeRecovery)
            assertNotEquals("Prefix cannot validate as the complete committed output", expectedSha, prefixSha)

            val launchedScenario = ActivityScenario.launch(ProductActivity::class.java)
            scenario = launchedScenario
            await("Recovery action is exposed for the partial unknown output") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")?.isEnabled == true
                }
                enabled
            }
            launchedScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")).performClick()
            }
            awaitLong("Read-only recovery completes and leaves the partial output unresolved") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")?.isEnabled == true
                }
                enabled && fixture.storage.exportAttempt(attemptId)?.state == "UNKNOWN_OUTCOME"
            }
            assertEquals("Recovery never creates another provider document", providerBeforeRecovery,
                childUris(context.contentResolver, fixture.tree))
            val bytesAfterRecovery = requireNotNull(context.contentResolver.openInputStream(providerUri)).use { it.readBytes() }
            assertTrue("Recovery leaves the exact partial bytes unchanged", bytesBeforeRecovery.contentEquals(bytesAfterRecovery))
            assertEquals("Recovery preserves the prefix hash", prefixSha, sha256(bytesAfterRecovery))
            assertEquals("Package remains unresolved", "UNKNOWN_OUTCOME", fixture.storage.externalExportPackage(exportId)?.state)
            assertEquals("No external URI was committed", null, fixture.storage.exportAttempt(attemptId)?.externalIdentity)
            var resumeEnabled = true
            launchedScenario.onActivity {
                resumeEnabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-resume-exports-button")?.isEnabled == true
            }
            assertFalse("Unknown partial output cannot resume or blindly retry", resumeEnabled)
            assertTrue("Frozen Page remains selected", fixture.storage.workspaceProjection().pages.single().selected)
            assertTrue("Recent remains empty", fixture.storage.recentDocuments().isEmpty())
            android.util.Log.i(TAG,
                "SAVE_PARTIAL_WRITE_RESUME_PASS fixture=$fixtureId export=$exportId attempt=$attemptId provider_uri=$providerUri prefix_bytes=${bytesAfterRecovery.size} prefix_sha256=$prefixSha expected_size=$expectedSize expected_sha256=$expectedSha external_identity=null package=UNKNOWN_OUTCOME page_retained=true recent_absent=true provider_create_or_write_after_restart=0")
        } finally {
            scenario?.close()
            cleanupRecoveryFixture(fixture, childDocuments(context.contentResolver, fixture.tree)
                .filter { it.name.startsWith(fixture.basename) }.map { it.uri })
        }
    }

    private fun saveStreamClosedBeforeReadBackDeathCheckpoint(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val fixture = newRecoveryFixture(fixtureId)
        val fixtureRoot = recoveryDeathFixtureRoot(context, fixtureId).apply { mkdirs() }
        val setupFile = File(fixtureRoot, "setup.properties")
        writeRecoveryFixtureProperties(fixture, setupFile)
        val checkpointFile = File(fixtureRoot, "checkpoint.properties")
        val checkpointReached = CountDownLatch(1)
        val hostKillBarrier = CountDownLatch(1)
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            fixture.storage.exportStreamClosedBeforeReadBackObserverForTest = { attemptId, exportId, outputId, documentUri, expectedSize, expectedSha ->
                val attempt = requireNotNull(fixture.storage.exportAttempt(ExportAttemptId.parse(attemptId)))
                assertEquals("Provider output stream closed before read-back; attempt is unacknowledged", "WRITING_EXTERNAL", attempt.state)
                assertEquals("Closed output URI is not committed before successful read-back", null, attempt.externalIdentity)
                assertEquals("Package remains in its in-flight state", "WRITING_EXTERNAL",
                    fixture.storage.externalExportPackage(ExportArtifactId.parse(exportId))?.state)
                assertTrue("Frozen source Page remains selected and present", fixture.storage.workspaceProjection().pages.single().selected)
                val uri = Uri.parse(documentUri)
                val providerBytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                assertEquals("Closed provider output has the exact expected size", expectedSize, providerBytes.size.toLong())
                assertEquals("Closed provider output matches the frozen artifact SHA-256", expectedSha, sha256(providerBytes))
                val checkpoint = Properties().apply {
                    load(setupFile.inputStream())
                    setProperty("attemptId", attemptId)
                    setProperty("exportArtifactId", exportId)
                    setProperty("outputArtifactId", outputId)
                    setProperty("providerUri", documentUri)
                    setProperty("providerSizeBytes", providerBytes.size.toString())
                    setProperty("providerSha256", sha256(providerBytes))
                    setProperty("expectedSizeBytes", expectedSize.toString())
                    setProperty("expectedSha256", expectedSha)
                }
                FileOutputStream(checkpointFile).use { stream ->
                    checkpoint.store(stream, "SAF output stream closed before gateway read-back")
                    stream.fd.sync()
                }
                android.util.Log.i(TAG,
                    "SAVE_STREAM_CLOSED_CHECKPOINT fixture=$fixtureId attempt=$attemptId export=$exportId output=$outputId provider_uri=$documentUri provider_size=$expectedSize provider_sha256=$expectedSha external_identity=null")
                checkpointReached.countDown()
                check(hostKillBarrier.await(10, TimeUnit.MINUTES)) {
                    "Host did not kill app after provider stream close and before gateway read-back"
                }
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("ProductActivity Save becomes available") {
                var enabled = false
                scenario!!.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("ProductActivity Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000L))
            device.findObject(By.clazz("android.widget.EditText")).text = fixture.basename
            val saveAction = By.text("Save selected pages")
            assertTrue("ProductActivity Save confirmation is available", device.wait(Until.hasObject(saveAction), 10_000L))
            device.findObject(saveAction).click()
            assertTrue("Closed-stream checkpoint was not reached", checkpointReached.await(120, TimeUnit.SECONDS))
            assertTrue("Host must terminate the app after stream close", hostKillBarrier.await(10, TimeUnit.MINUTES))
            throw AssertionError("Provider stream-close checkpoint returned without host process death")
        } finally {
            scenario?.close()
            if (!checkpointFile.exists()) cleanupRecoveryFixture(fixture, emptyList())
        }
    }

    private fun resumeStreamClosedBeforeReadBackAfterProcessDeath(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val root = recoveryDeathFixtureRoot(context, fixtureId)
        val setup = Properties().apply { File(root, "checkpoint.properties").inputStream().use(::load) }
        val fixture = recoveryFixtureFromProperties(context, app, setup, fixtureId)
        val attemptId = ExportAttemptId.parse(setup.getProperty("attemptId"))
        val exportId = ExportArtifactId.parse(setup.getProperty("exportArtifactId"))
        val closedUri = Uri.parse(setup.getProperty("providerUri"))
        val expectedSize = setup.getProperty("expectedSizeBytes").toLong()
        val expectedSha = setup.getProperty("expectedSha256")
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            assertFalse("Workspace integrity gate must allow the isolated valid fixture", fixture.storage.startupIntegrityBlocked)
            assertEquals("Process death before read-back resolves the attempt to UnknownOutcome", "UNKNOWN_OUTCOME",
                fixture.storage.exportAttempt(attemptId)?.state)
            assertEquals("Unacknowledged package starts unresolved", "UNKNOWN_OUTCOME",
                fixture.storage.externalExportPackage(exportId)?.state)
            assertTrue("Recent is absent before whole-package confirmation", fixture.storage.recentDocuments().isEmpty())
            assertEquals("Frozen source Page remains protected", 1, fixture.storage.workspaceProjection().pages.size)
            val bytesBeforeRecovery = requireNotNull(context.contentResolver.openInputStream(closedUri)).use { it.readBytes() }
            assertEquals("Closed provider output still has its exact size", expectedSize, bytesBeforeRecovery.size.toLong())
            assertEquals("Closed provider output retains the frozen artifact digest", expectedSha, sha256(bytesBeforeRecovery))
            val providerBeforeRecovery = childUris(context.contentResolver, fixture.tree)
            assertEquals("Exactly one provider output exists before recovery", 1,
                childDocuments(context.contentResolver, fixture.tree).count { it.name.startsWith(fixture.basename) })
            assertTrue(closedUri in providerBeforeRecovery)

            val launchedScenario = ActivityScenario.launch(ProductActivity::class.java)
            scenario = launchedScenario
            await("Recovery action is exposed for the unknown closed output") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")?.isEnabled == true
                }
                enabled
            }
            launchedScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")).performClick()
            }
            awaitLong("Read-only recovery confirms the closed provider output") {
                fixture.storage.exportAttempt(attemptId)?.state == "SUCCEEDED"
            }
            assertEquals("Recovery performs no provider create/write", providerBeforeRecovery,
                childUris(context.contentResolver, fixture.tree))
            val bytesAfterRecovery = requireNotNull(context.contentResolver.openInputStream(closedUri)).use { it.readBytes() }
            assertEquals("Recovery preserves exact provider bytes", bytesBeforeRecovery.toList(), bytesAfterRecovery.toList())
            assertEquals("The attempt is associated with the exact existing provider URI", closedUri.toString(),
                fixture.storage.exportAttempt(attemptId)?.externalIdentity)
            assertEquals("Confirmed output leaves the companion package resumable", "PARTIAL",
                fixture.storage.externalExportPackage(exportId)?.state)
            assertEquals("Frozen Page remains protected until all outputs succeed", 1, fixture.storage.workspaceProjection().pages.size)
            assertTrue("Recent is still absent until package completion", fixture.storage.recentDocuments().isEmpty())

            awaitLong("Resume becomes available after positive full-size/hash confirmation") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-resume-exports-button")?.isEnabled == true
                }
                enabled
            }
            launchedScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-resume-exports-button")).performClick()
            }
            awaitLong("Remaining output completes the package and consumes its frozen Page") {
                fixture.storage.externalExportPackage(exportId)?.state == "SUCCEEDED"
                    && fixture.storage.workspaceProjection().pages.isEmpty()
            }
            val outputs = fixture.storage.committedExportOutputs(exportId)
            val attempts = fixture.storage.exportAttempts(exportId)
            assertEquals("Exactly one attempt exists for each frozen output", outputs.size, attempts.size)
            assertTrue(attempts.all { it.state == "SUCCEEDED" })
            assertEquals(closedUri.toString(), fixture.storage.exportAttempt(attemptId)?.externalIdentity)
            assertEquals("Each output appears exactly once", outputs.size,
                childDocuments(context.contentResolver, fixture.tree).count { it.name.startsWith(fixture.basename) })
            assertEquals("Recent is published only after package success", 1,
                fixture.storage.recentDocuments().count { it.exportArtifactId == exportId })
            android.util.Log.i(TAG,
                "SAVE_STREAM_CLOSED_RESUME_PASS fixture=$fixtureId export=$exportId attempt=$attemptId provider_uri=$closedUri provider_size=$expectedSize provider_sha256=$expectedSha outputs=${outputs.size} page_consumed_after_package_success=true")
        } finally {
            scenario?.close()
            cleanupRecoveryFixture(fixture, childDocuments(context.contentResolver, fixture.tree)
                .filter { it.name.startsWith(fixture.basename) }.map { it.uri })
        }
    }

    private fun saveCreatedBeforeStreamDeathCheckpoint(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val fixture = newRecoveryFixture(fixtureId)
        val fixtureRoot = recoveryDeathFixtureRoot(context, fixtureId).apply { mkdirs() }
        val setupFile = File(fixtureRoot, "setup.properties")
        writeRecoveryFixtureProperties(fixture, setupFile)
        val checkpointFile = File(fixtureRoot, "checkpoint.properties")
        val checkpointReached = CountDownLatch(1)
        val hostKillBarrier = CountDownLatch(1)
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            fixture.storage.exportCreatedBeforeStreamObserverForTest = { attemptId, exportId, outputId, documentUri ->
                val attempt = requireNotNull(fixture.storage.exportAttempt(ExportAttemptId.parse(attemptId)))
                assertEquals("Provider create returned but acknowledgement is pending", "WRITING_EXTERNAL", attempt.state)
                assertEquals("Created URI is not durably acknowledged yet", null, attempt.externalIdentity)
                assertEquals("Output package remains in its in-flight state", "WRITING_EXTERNAL",
                    fixture.storage.externalExportPackage(ExportArtifactId.parse(exportId))?.state)
                val uri = Uri.parse(documentUri)
                val children = childDocuments(context.contentResolver, fixture.tree)
                    .filter { it.name.startsWith(fixture.basename) }
                assertEquals("Exactly one test-owned provider document exists after create", 1, children.size)
                assertEquals(uri, children.single().uri)
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                assertEquals("Host checkpoint is before the first provider output byte", 0, bytes.size)
                val checkpoint = Properties().apply {
                    load(setupFile.inputStream())
                    setProperty("attemptId", attemptId)
                    setProperty("exportArtifactId", exportId)
                    setProperty("outputArtifactId", outputId)
                    setProperty("providerUri", documentUri)
                }
                FileOutputStream(checkpointFile).use { stream ->
                    checkpoint.store(stream, "SAF document created before provider stream opens")
                    stream.fd.sync()
                }
                android.util.Log.i(TAG,
                    "SAVE_CREATE_CHECKPOINT fixture=$fixtureId attempt=$attemptId export=$exportId output=$outputId provider_uri=$documentUri provider_bytes=0 external_identity=null")
                checkpointReached.countDown()
                check(hostKillBarrier.await(10, TimeUnit.MINUTES)) {
                    "Host did not kill app after createDocument and before openOutputStream"
                }
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("ProductActivity Save becomes available") {
                var enabled = false
                scenario!!.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("ProductActivity Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000L))
            device.findObject(By.clazz("android.widget.EditText")).text = fixture.basename
            val saveAction = By.text("Save selected pages")
            assertTrue("ProductActivity Save confirmation is available", device.wait(Until.hasObject(saveAction), 10_000L))
            device.findObject(saveAction).click()
            assertTrue("Real provider create checkpoint was not reached", checkpointReached.await(120, TimeUnit.SECONDS))
            assertTrue("Host must terminate the app while provider bytes are still empty", hostKillBarrier.await(10, TimeUnit.MINUTES))
            throw AssertionError("Provider create checkpoint returned without host process death")
        } finally {
            scenario?.close()
            if (!checkpointFile.exists()) cleanupRecoveryFixture(fixture, emptyList())
        }
    }

    private fun resumeSaveCreatedBeforeStreamAfterProcessDeath(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val root = recoveryDeathFixtureRoot(context, fixtureId)
        val setup = Properties().apply { File(root, "checkpoint.properties").inputStream().use(::load) }
        val fixture = recoveryFixtureFromProperties(context, app, setup, fixtureId)
        val attemptId = ExportAttemptId.parse(setup.getProperty("attemptId"))
        val exportId = ExportArtifactId.parse(setup.getProperty("exportArtifactId"))
        val createdUri = Uri.parse(setup.getProperty("providerUri"))
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            assertFalse("Workspace integrity gate must allow the isolated valid fixture", fixture.storage.startupIntegrityBlocked)
            val recoveredAttempt = requireNotNull(fixture.storage.exportAttempt(attemptId))
            assertEquals("Process death after provider create resolves to UnknownOutcome", "UNKNOWN_OUTCOME", recoveredAttempt.state)
            assertEquals("URI was never committed to the attempt before process death", null, recoveredAttempt.externalIdentity)
            assertEquals("Unacknowledged package remains unresolved", "UNKNOWN_OUTCOME",
                fixture.storage.externalExportPackage(exportId)?.state)
            assertTrue("Recent is not published for an unresolved package", fixture.storage.recentDocuments().isEmpty())
            val page = fixture.storage.workspaceProjection().pages.single()
            assertTrue("Frozen source Page remains selected and present", page.selected)
            val providerBeforeRecovery = childUris(context.contentResolver, fixture.tree)
            val ownedBefore = childDocuments(context.contentResolver, fixture.tree)
                .filter { it.name.startsWith(fixture.basename) }
            assertEquals("Exactly one empty provider document survives process death", 1, ownedBefore.size)
            assertEquals(createdUri, ownedBefore.single().uri)
            val bytesBeforeRecovery = requireNotNull(context.contentResolver.openInputStream(createdUri)).use { it.readBytes() }
            assertEquals(0, bytesBeforeRecovery.size)

            val launchedScenario = ActivityScenario.launch(ProductActivity::class.java)
            scenario = launchedScenario
            await("Recovery action is exposed for the unresolved output") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")?.isEnabled == true
                }
                enabled
            }
            launchedScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")).performClick()
            }
            awaitLong("Read-only revalidation completes and leaves the empty mismatching output unresolved") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")?.isEnabled == true
                }
                enabled && fixture.storage.exportAttempt(attemptId)?.state == "UNKNOWN_OUTCOME"
            }
            assertEquals("Recovery never writes or creates another provider document", providerBeforeRecovery,
                childUris(context.contentResolver, fixture.tree))
            val bytesAfterRecovery = requireNotNull(context.contentResolver.openInputStream(createdUri)).use { it.readBytes() }
            assertTrue("The existing incomplete provider bytes stay unchanged and empty",
                bytesBeforeRecovery.contentEquals(bytesAfterRecovery))
            assertEquals("Package stays unresolved", "UNKNOWN_OUTCOME", fixture.storage.externalExportPackage(exportId)?.state)
            var resumeEnabled = true
            launchedScenario.onActivity {
                resumeEnabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-resume-exports-button")?.isEnabled == true
            }
            assertFalse("Unknown output cannot resume or blindly retry", resumeEnabled)
            assertTrue("Frozen Page remains present after unresolved recovery", fixture.storage.workspaceProjection().pages.single().selected)
            assertTrue("No Recent row is published", fixture.storage.recentDocuments().isEmpty())
            android.util.Log.i(TAG,
                "SAVE_CREATE_RESUME_PASS fixture=$fixtureId export=$exportId attempt=$attemptId provider_uri=$createdUri provider_bytes=0 external_identity=null package=UNKNOWN_OUTCOME page_retained=true recent_absent=true provider_create_or_write_after_restart=0")
        } finally {
            scenario?.close()
            cleanupRecoveryFixture(fixture, childDocuments(context.contentResolver, fixture.tree)
                .filter { it.name.startsWith(fixture.basename) }.map { it.uri })
        }
    }

    private fun saveReadBackDeathCheckpoint(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val fixture = newRecoveryFixture(fixtureId)
        val fixtureRoot = recoveryDeathFixtureRoot(context, fixtureId).apply { mkdirs() }
        val setupFile = File(fixtureRoot, "setup.properties")
        writeRecoveryFixtureProperties(fixture, setupFile)
        val checkpointFile = File(fixtureRoot, "checkpoint.properties")
        val checkpointReached = CountDownLatch(1)
        val hostKillBarrier = CountDownLatch(1)
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            fixture.storage.exportReadBackBoundaryObserverForTest = { attemptId, exportId, outputId, documentUri, observedSha ->
                val attempt = requireNotNull(fixture.storage.exportAttempt(ExportAttemptId.parse(attemptId)))
                assertEquals("Provider bytes and read-back are complete, but DB outcome is still pending", "WRITING_EXTERNAL", attempt.state)
                assertEquals("Output package remains in its in-flight state before acknowledgement", "WRITING_EXTERNAL",
                    fixture.storage.externalExportPackage(ExportArtifactId.parse(exportId))?.state)
                assertEquals("Frozen source Page remains visible before full package success", 1,
                    fixture.storage.workspaceProjection().pages.size)
                val uri = Uri.parse(documentUri)
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                assertEquals("Provider read-back hash equals committed output", observedSha, sha256(bytes))
                val checkpoint = Properties().apply {
                    load(setupFile.inputStream())
                    setProperty("attemptId", attemptId)
                    setProperty("exportArtifactId", exportId)
                    setProperty("outputArtifactId", outputId)
                    setProperty("providerUri", documentUri)
                    setProperty("providerSha256", observedSha)
                }
                FileOutputStream(checkpointFile).use { stream ->
                    checkpoint.store(stream, "SAF provider read-back before app acknowledgement")
                    stream.fd.sync()
                }
                android.util.Log.i(TAG,
                    "SAVE_READBACK_CHECKPOINT fixture=$fixtureId attempt=$attemptId export=$exportId output=$outputId provider_sha256=$observedSha")
                checkpointReached.countDown()
                check(hostKillBarrier.await(10, TimeUnit.MINUTES)) {
                    "Host did not kill app after provider read-back and before database acknowledgement"
                }
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("ProductActivity Save becomes available") {
                var enabled = false
                scenario!!.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            assertTrue("ProductActivity Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000L))
            device.findObject(By.clazz("android.widget.EditText")).text = fixture.basename
            val saveAction = By.text("Save selected pages")
            assertTrue("ProductActivity Save confirmation is available", device.wait(Until.hasObject(saveAction), 10_000L))
            device.findObject(saveAction).click()
            assertTrue("Real provider read-back checkpoint was not reached", checkpointReached.await(120, TimeUnit.SECONDS))
            assertTrue("Host must terminate the app while the outcome is uncommitted", hostKillBarrier.await(10, TimeUnit.MINUTES))
            throw AssertionError("Provider acknowledgement checkpoint returned without host process death")
        } finally {
            scenario?.close()
            if (!checkpointFile.exists()) cleanupRecoveryFixture(fixture, emptyList())
        }
    }

    private fun resumeSaveReadBackAfterProcessDeath(
        context: android.content.Context,
        app: DocScanApplication,
        fixtureId: String,
    ) {
        val root = recoveryDeathFixtureRoot(context, fixtureId)
        val setup = Properties().apply { File(root, "checkpoint.properties").inputStream().use(::load) }
        val fixture = recoveryFixtureFromProperties(context, app, setup, fixtureId)
        val attemptId = ExportAttemptId.parse(setup.getProperty("attemptId"))
        val exportId = ExportArtifactId.parse(setup.getProperty("exportArtifactId"))
        val firstUri = Uri.parse(setup.getProperty("providerUri"))
        val firstSha = setup.getProperty("providerSha256")
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            assertFalse("Workspace integrity gate must allow recovery of the isolated valid fixture", fixture.storage.startupIntegrityBlocked)
            assertEquals("OS process death converts unacknowledged WRITING attempt to UnknownOutcome", "UNKNOWN_OUTCOME",
                fixture.storage.exportAttempt(attemptId)?.state)
            assertEquals("Package remains unresolved until a positive read-back recovery", "UNKNOWN_OUTCOME",
                fixture.storage.externalExportPackage(exportId)?.state)
            assertTrue("Recent row is not published before all package outputs are confirmed", fixture.storage.recentDocuments().isEmpty())
            assertEquals("Frozen source Page remains pending", 1, fixture.storage.workspaceProjection().pages.size)
            assertEquals(firstSha, requireNotNull(context.contentResolver.openInputStream(firstUri)).use { sha256(it.readBytes()) })
            val providerBeforeRecovery = childUris(context.contentResolver, fixture.tree)
            assertEquals("Exactly one provider output exists before recovery", 1,
                childDocuments(context.contentResolver, fixture.tree).count { it.name.startsWith(fixture.basename) })
            assertTrue(firstUri in providerBeforeRecovery)

            val launchedScenario = ActivityScenario.launch(ProductActivity::class.java)
            scenario = launchedScenario
            await("Recovery button is exposed for UnknownOutcome") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")?.isEnabled == true
                }
                enabled
            }
            launchedScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recover-exports-button")).performClick()
            }
            awaitLong("read-only recovery confirms the original provider output") {
                fixture.storage.exportAttempt(attemptId)?.state == "SUCCEEDED"
            }
            assertEquals("Read-only outcome recovery performs no provider create/write", providerBeforeRecovery,
                childUris(context.contentResolver, fixture.tree))
            assertEquals("Package awaits its remaining companion output", "PARTIAL", fixture.storage.externalExportPackage(exportId)?.state)
            assertEquals("Page remains frozen until every package output succeeds", 1, fixture.storage.workspaceProjection().pages.size)

            awaitLong("resume button is enabled for the confirmed prefix") {
                var enabled = false
                launchedScenario.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-resume-exports-button")?.isEnabled == true
                }
                enabled
            }
            launchedScenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-resume-exports-button")).performClick()
            }
            awaitLong("remaining output completes the package and consumes the frozen Page") {
                fixture.storage.externalExportPackage(exportId)?.state == "SUCCEEDED"
                    && fixture.storage.workspaceProjection().pages.isEmpty()
            }
            val outputs = fixture.storage.committedExportOutputs(exportId)
            val attempts = fixture.storage.exportAttempts(exportId)
            assertEquals("One successful attempt exists per output", outputs.size, attempts.size)
            assertTrue(attempts.all { it.state == "SUCCEEDED" })
            assertEquals("First URI remains the exact acknowledged provider document", firstUri.toString(),
                attempts.single { it.exportAttemptId == attemptId }.externalIdentity)
            val ownedDocuments = childDocuments(context.contentResolver, fixture.tree).filter { it.name.startsWith(fixture.basename) }
            assertEquals("Each package output is present exactly once", outputs.size, ownedDocuments.size)
            assertEquals("Recent entry is committed after complete package confirmation", 1,
                fixture.storage.recentDocuments().count { it.exportArtifactId == exportId })
            assertTrue("No unresolved/partial output package remains", fixture.storage.incompleteExternalExportPackages().none { it.exportArtifactId == exportId })
            assertEquals("Repeated recovery is idempotent and creates no provider documents", outputs.size, childDocuments(context.contentResolver, fixture.tree).count { it.name.startsWith(fixture.basename) })
            android.util.Log.i(TAG,
                "SAVE_READBACK_RESUME_PASS fixture=$fixtureId export=$exportId attempt=$attemptId outputs=${outputs.size} recovered_uri=$firstUri provider_sha256=$firstSha page_consumed_after_package_success=true")
        } finally {
            scenario?.close()
            cleanupRecoveryFixture(fixture, childDocuments(context.contentResolver, fixture.tree)
                .filter { it.name.startsWith(fixture.basename) }.map { it.uri })
        }
    }

    private fun cleanupSaveReadBackFixture(context: android.content.Context, app: DocScanApplication, fixtureId: String) {
        val setupFile = File(recoveryDeathFixtureRoot(context, fixtureId), "setup.properties")
        if (!setupFile.exists()) return
        val setup = Properties().apply { setupFile.inputStream().use(::load) }
        val fixture = recoveryFixtureFromProperties(context, app, setup, fixtureId)
        cleanupRecoveryFixture(fixture, childDocuments(context.contentResolver, fixture.tree)
            .filter { it.name.startsWith(fixture.basename) }.map { it.uri })
    }

    private fun recoveryFixtureFromProperties(
        context: android.content.Context,
        app: DocScanApplication,
        properties: Properties,
        fixtureId: String,
    ): RecoveryFixture {
        val storage = ProductStorage(
            context,
            properties.getProperty("databaseName"),
            properties.getProperty("artifactDirectory"),
            properties.getProperty("candidateDirectory"),
        )
        app.productStorageOverrideForTest = storage
        val priorPermissions = properties.getProperty("priorPermissions", "").split('\n')
            .filter(String::isNotBlank).map(Uri::parse).toSet()
        return RecoveryFixture(
            context = context,
            app = app,
            storage = storage,
            databaseName = properties.getProperty("databaseName"),
            artifactDirectory = properties.getProperty("artifactDirectory"),
            candidateDirectory = properties.getProperty("candidateDirectory"),
            priorPermissions = priorPermissions,
            tree = Uri.parse(properties.getProperty("treeUri")),
            basename = properties.getProperty("basename"),
            qualificationId = properties.getProperty("qualificationId"),
            adapterId = properties.getProperty("adapterId"),
            providerVersion = properties.getProperty("providerVersion"),
            osVersion = properties.getProperty("osVersion"),
            accessClass = properties.getProperty("accessClass"),
            fixtureSuffix = fixtureId,
        )
    }

    private fun writeRecoveryFixtureProperties(fixture: RecoveryFixture, file: java.io.File) {
        val properties = Properties().apply {
            setProperty("databaseName", fixture.databaseName)
            setProperty("artifactDirectory", fixture.artifactDirectory)
            setProperty("candidateDirectory", fixture.candidateDirectory)
            setProperty("treeUri", fixture.tree.toString())
            setProperty("basename", fixture.basename)
            setProperty("qualificationId", fixture.qualificationId)
            setProperty("adapterId", fixture.adapterId)
            setProperty("providerVersion", fixture.providerVersion)
            setProperty("osVersion", fixture.osVersion)
            setProperty("accessClass", fixture.accessClass)
            setProperty("priorPermissions", fixture.priorPermissions.joinToString("\n"))
        }
        FileOutputStream(file).use { stream -> properties.store(stream, "Test-owned provider recovery fixture") ; stream.fd.sync() }
    }

    private fun recoveryDeathFixtureRoot(context: android.content.Context, fixtureId: String) =
        File(context.filesDir, "saf-recovery-fixture-$fixtureId")

    private fun awaitLong(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 90_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            android.os.SystemClock.sleep(100L)
        }
        throw AssertionError(message)
    }

    @Test
    fun productionGatewayWritesWholePackageAndCollisionFailsBeforeCreate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "saf-gateway-$suffix.db"
        val artifactDirectory = "saf-gateway-artifacts-$suffix"
        val candidateDirectory = "saf-gateway-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorPermissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        val createdUris = mutableListOf<Uri>()
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        val basename = "docscan-gateway-probe-$suffix"
        var adapterId = ""
        var providerVersion = ""
        val osVersion = Build.FINGERPRINT
        val accessClass = ProductActivity.SAF_ACCESS_CLASS
        val qualificationId = "test-only-$suffix"

        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("ProductActivity storage ready") {
                var ready = false
                scenario!!.onActivity { ready = it.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true }
                ready
            }
            scenario.onActivity { activity ->
                val button = activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")
                assertNotNull(button)
                button!!.performClick()
            }
            selectDocuments(UiDevice.getInstance(instrumentation))
            val grant = awaitValue("real picker grant") { storage.destinationGrant() }
            tree = Uri.parse(grant.treeUri)
            scenario.close()
            scenario = null // Ensure the test-only tuple fixture can never enable the product Save UI.
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(tree))
            assertTrue(context.contentResolver.persistedUriPermissions.any {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            })

            val authority = requireNotNull(tree.authority)
            val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
            val providerPackage = provider.packageName
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageInfo(providerPackage, 0)
            providerVersion = "${packageInfo.versionName.orEmpty()}#${packageInfo.longVersionCode}"
            adapterId = "${ProductActivity.SAF_ADAPTER_ID}/$providerPackage/$authority"
            storage.recordDestinationGrant(tree.toString())
            storage.recordProviderQualification(
                ProviderQualificationCommand(
                    qualificationId, adapterId, providerVersion, osVersion, accessClass,
                    "TEST_FIXTURE_ONLY_NOT_QUALIFICATION",
                    """{"createDoesNotOverwriteExistingDocument":true,"enumerateChildren":true,"readBackSha256":true,"fixtureOnly":true}""",
                ),
            )
            android.util.Log.i(TAG, "TEST_FIXTURE_TUPLE adapter=$adapterId providerVersion=$providerVersion os=$osVersion access=$accessClass qualificationId=$qualificationId")

            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawText("SAF TEST", 24f, 90f, Paint().apply { color = Color.BLACK; textSize = 28f })
            }
            val jpeg = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                stream.toByteArray()
            }
            bitmap.recycle()
            val imported = storage.importImage(jpeg.inputStream(), "image/jpeg", "gateway-fixture.jpg")
            storage.setSelected(imported.pageId, true)
            val sourceFile = storage.artifactPath(storage.visiblePages().single().artifactRelativePath).toFile()
            val beforeSource = sourceFile.readBytes()

            val first = composeAndCommit(storage, basename, tree.toString(), qualificationId, adapterId, providerVersion, osVersion, accessClass)
            val packageSnapshot = requireNotNull(storage.externalExportPackage(first))
            val outputs = storage.committedExportOutputs(first).associateBy { it.artifactId }
            assertEquals(2, packageSnapshot.outputs.size)
            val gateway = SafExportGateway(context, storage)
            packageSnapshot.outputs.forEach { packageOutput ->
                val output = requireNotNull(outputs[packageOutput.outputArtifactId])
                val uri = gateway.export(
                    BeginExportAttemptCommand(
                        ExportAttemptId.new(), first, output.artifactId, tree.toString(),
                        packageOutput.displayName, output.mimeType, qualificationId, adapterId,
                        providerVersion, osVersion, accessClass,
                    ),
                )
                createdUris += uri
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                assertEquals(output.sizeBytes, bytes.size.toLong())
                assertEquals(output.sha256, sha256(bytes))
            }
            assertTrue(storage.exportAttempts(first).all { it.state == "SUCCEEDED" })
            assertEquals("SUCCEEDED", storage.externalExportPackage(first)?.state)
            assertEquals(beforeSource.toList(), sourceFile.readBytes().toList())

            // A second immutable output set with identical planned names must stop at the gateway's
            // read-only normalized-name check before createDocument, leaving the first set intact.
            val collisionPage = storage.importImage(beforeSource.inputStream(), "image/jpeg", "gateway-collision-fixture.jpg")
            storage.setSelected(collisionPage.pageId, true)
            val second = composeAndCommit(storage, basename, tree.toString(), qualificationId, adapterId, providerVersion, osVersion, accessClass)
            val secondPackage = requireNotNull(storage.externalExportPackage(second))
            val secondPdf = storage.committedExportOutputs(second).single { it.mimeType == "application/pdf" }
            val beforeNames = childNames(context.contentResolver, tree)
            val attemptsBeforeMismatch = storage.exportAttempts(second).size
            val mismatchedDestination = runCatching {
                gateway.export(
                    BeginExportAttemptCommand(
                        ExportAttemptId.new(), second, secondPdf.artifactId, "${tree}/forged",
                        secondPackage.outputs.first().displayName, secondPdf.mimeType, qualificationId,
                        adapterId, providerVersion, osVersion, accessClass,
                    ),
                )
            }.exceptionOrNull()
            assertNotNull("A different destination must fail before any attempt or provider call", mismatchedDestination)
            assertEquals(attemptsBeforeMismatch, storage.exportAttempts(second).size)
            assertEquals(beforeNames, childNames(context.contentResolver, tree))
            val collision = runCatching {
                gateway.export(
                    BeginExportAttemptCommand(
                        ExportAttemptId.new(), second, secondPdf.artifactId, tree.toString(),
                        secondPackage.outputs.first().displayName, secondPdf.mimeType, qualificationId,
                        adapterId, providerVersion, osVersion, accessClass,
                    ),
                )
            }.exceptionOrNull()
            assertNotNull("Existing normalized name must fail before create", collision)
            val failedAttempt = storage.exportAttempts(second).single()
            assertEquals("FAILED_KNOWN", failedAttempt.state)
            assertTrue(failedAttempt.knownError.orEmpty().contains("already"))
            val afterNames = childNames(context.contentResolver, tree)
            assertEquals("Collision must not create a second document", beforeNames, afterNames)
            assertTrue("Every originally published test output remains present", createdUris.all { it in childUris(context.contentResolver, tree) })
            android.util.Log.i(TAG, "GATEWAY_PASS package_outputs=2 attempts=SUCCEEDED collision=FAILED_KNOWN provider_children=${afterNames.size}")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            val resolver = context.contentResolver
            createdUris.distinct().forEach { uri ->
                val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
                if (!deleted) cleanupFailure = IllegalStateException("Could not remove test-owned output $uri", cleanupFailure)
            }
            tree?.let { selectedTree ->
                runCatching {
                    val remaining = childUris(resolver, selectedTree).filter { uri ->
                        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() in createdUris.mapNotNull {
                            runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
                        }
                    }
                    check(remaining.isEmpty()) { "Test-owned exported documents remain: $remaining" }
                }.onFailure { cleanupFailure = it }
                if (selectedTree !in priorPermissions) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure { cleanupFailure = it }
                }
            }
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
            android.util.Log.i(TAG, "CLEANUP created=${createdUris.size} grant_released=${tree != null && tree !in priorPermissions}")
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("SAF gateway integration or cleanup failed", it) }
    }

    @Test
    fun productActivitySaveConsumesFrozenPageAfterWholeProviderPackageSucceeds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "activity-save-$suffix.db"
        val artifactDirectory = "activity-save-artifacts-$suffix"
        val candidateDirectory = "activity-save-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorPermissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val basename = "docscan-activity-save-$suffix"
        val providerWriteReached = CountDownLatch(1)
        val continueProviderWrite = CountDownLatch(1)
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var namespaceWasAbsent = false
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("first ProductActivity ready") {
                var ready = false
                scenario!!.onActivity { ready = it.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true }
                ready
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")).performClick()
            }
            val device = UiDevice.getInstance(instrumentation)
            selectDocuments(device)
            val grant = awaitValue("actual SAF picker grant") { storage.destinationGrant() }
            tree = Uri.parse(grant.treeUri)
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(tree))
            scenario.close()
            scenario = null // Tuple fixture is added only after the Save screen is gone.

            val runtimeReady = SafRuntimeDestinationCheck(context, storage).ensureReady(tree.toString())
            val qualificationId = runtimeReady.qualificationId
            assertEquals("Runtime readiness is exact-tree scoped", tree.toString(),
                org.json.JSONObject(runtimeReady.qualifiedCapabilitiesPayload).getString("treeUri"))
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawText("ACTIVITY SAVE", 20f, 100f, Paint().apply { color = Color.BLACK; textSize = 24f })
            }
            val jpeg = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                stream.toByteArray()
            }
            bitmap.recycle()
            val imported = storage.importImage(jpeg.inputStream(), "image/jpeg", "activity-save-fixture.jpg")
            storage.setSelected(imported.pageId, true)
            val nonFrozenPages = (1..8).map { index ->
                storage.importImage(jpeg.inputStream(), "image/jpeg", "activity-save-untouched-$index.jpg").also {
                    storage.setSelected(it.pageId, false)
                }
            }
            assertFalse("Unique test basename must be absent before Save", childNames(context.contentResolver, tree).any { it.startsWith(basename) })
            namespaceWasAbsent = true

            storage.exportFirstProviderWriteBoundaryObserverForTest = { attemptId, exportId, _, _, firstChunkBytes, expectedSize, _ ->
                assertTrue("Provider has written a non-empty prefix", firstChunkBytes > 0)
                assertTrue("Checkpoint precedes completion of the output", firstChunkBytes.toLong() < expectedSize)
                assertEquals("Save owns an active provider attempt", "WRITING_EXTERNAL",
                    storage.exportAttempt(ExportAttemptId.parse(attemptId))?.state)
                assertEquals("Export package remains in progress", "WRITING_EXTERNAL",
                    storage.externalExportPackage(ExportArtifactId.parse(exportId))?.state)
                providerWriteReached.countDown()
                check(continueProviderWrite.await(120, TimeUnit.SECONDS)) {
                    "Test did not release the active Save provider checkpoint"
                }
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("qualified isolated Save button enabled") {
                var enabled = false
                scenario!!.onActivity { enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true }
                enabled
            }
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")!!.performClick()
            }
            assertTrue("Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000L))
            device.findObject(By.clazz("android.widget.EditText")).text = basename
            val positive = By.text("Save selected pages")
            assertTrue("Save confirmation is available", device.wait(Until.hasObject(positive), 5_000L))
            device.findObject(positive).click()

            assertTrue("Save reaches a held real provider-write checkpoint",
                providerWriteReached.await(120, TimeUnit.SECONDS))
            openWorkspaceMenuForTest(scenario)
            scrollHomeRailToPositionForTest(scenario, nonFrozenPages.lastIndex + 1)
            val clearBounds = android.graphics.Rect()
            scenario.onActivity { activity ->
                val save = requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button"))
                val delete = requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-delete-selected-button"))
                val clear = requireNotNull(nativeWorkspaceRoot(activity).findViewWithTag<android.view.View>("docscan-clear-workspace-button"))
                assertFalse("Save stays disabled while its provider operation is active", save.isEnabled)
                assertFalse("Delete Selected is disabled during active Save", delete.isEnabled)
                assertFalse("Clear is disabled during active Save", clear.isEnabled)
                val rail = requireNotNull(activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("docscan-home-thumbnail-scroll"))
                val retainedPageId = nonFrozenPages.last().pageId
                val retainedCheckbox = requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-home-selection:${retainedPageId.value}"))
                assertFalse("Home thumbnail rail is blocked during active Save", rail.isEnabled)
                assertFalse("A newly attached nonfrozen Page checkbox remains blocked during active Save", retainedCheckbox.isEnabled)
                assertTrue("Disabled Clear remains visible in the command menu for the negative input check", clear.getLocalVisibleRect(clearBounds))
                val position = IntArray(2); clear.getLocationOnScreen(position); clearBounds.offset(position[0], position[1])
            }
            assertTrue("Inject a real tap at the disabled Clear control", device.click(clearBounds.centerX(), clearBounds.centerY()))
            assertFalse("A real Clear tap cannot open confirmation during Save",
                device.wait(Until.hasObject(By.text(context.getString(R.string.clear_workspace_confirmation))), 1_000L))
            assertEquals("Frozen source and nonfrozen Pages remain while Save is unresolved", 9,
                storage.workspaceProjection().pages.size)
            assertEquals("Only the intended source Page is frozen for Save", listOf(imported.pageId),
                storage.workspaceProjection().pages.filter { it.selected }.map { it.pageId })
            assertTrue("Recent is not published before provider package completion", storage.recentDocuments().isEmpty())
            closeWorkspaceMenuForTest(scenario)
            continueProviderWrite.countDown()

            val recent = awaitValue("Activity Save reaches RecentDocument after full export") {
                storage.recentDocuments().singleOrNull()
            }
            val exportPackage = requireNotNull(storage.externalExportPackage(recent.exportArtifactId))
            assertEquals("SUCCEEDED", exportPackage.state)
            assertEquals("The package uses the actual runtime capability record", qualificationId,
                exportPackage.providerQualificationId)
            val outputs = storage.committedExportOutputs(recent.exportArtifactId).associateBy { it.artifactId }
            val attempts = storage.exportAttempts(recent.exportArtifactId)
            assertEquals(2, outputs.size)
            assertEquals(2, attempts.size)
            assertTrue(attempts.all { it.state == "SUCCEEDED" })
            attempts.forEach { attempt ->
                val output = requireNotNull(outputs[attempt.outputArtifactId])
                val bytes = requireNotNull(context.contentResolver.openInputStream(Uri.parse(attempt.externalIdentity))).use { it.readBytes() }
                assertEquals(output.sizeBytes, bytes.size.toLong())
                assertEquals(output.sha256, sha256(bytes))
            }
            assertEquals("Only the saved Page leaves the workspace after package success", nonFrozenPages.map { it.pageId },
                storage.workspaceProjection().pages.map { it.pageId })
            assertTrue("Every nonfrozen Page retains its selection state", storage.workspaceProjection().pages.all { it.selected.not() })
            scrollHomeRailToPositionForTest(scenario, nonFrozenPages.lastIndex)
            await("Home controls are re-enabled after external Save terminates") {
                var enabled = false
                scenario!!.onActivity { activity ->
                    val rail = activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("docscan-home-thumbnail-scroll")
                    val selection = activity.window.decorView.findViewWithTag<android.view.View>("docscan-home-selection:${nonFrozenPages.last().pageId.value}")
                    enabled = rail?.isEnabled == true && selection?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-home-selection:${nonFrozenPages.last().pageId.value}")).performClick()
            }
            await("The re-enabled Home selection control updates only the nonfrozen Page") {
                val pages = storage.workspaceProjection().pages
                pages.filter { it.selected }.map { it.pageId } == listOf(nonFrozenPages.last().pageId)
            }
            await("Activity renders the remaining post-Save workspace Page") {
                var rendered = false
                scenario!!.onActivity { activity ->
                    val rail = activity.window.decorView.findViewWithTag<androidx.recyclerview.widget.RecyclerView>("docscan-home-thumbnail-scroll")
                    val frame = activity.window.decorView.findViewWithTag<android.view.ViewGroup>("docscan-home-thumbnail-strip")
                    val emptyLabel = frame?.getChildAt(1) as? android.widget.TextView
                    rendered = rail?.adapter?.itemCount == nonFrozenPages.size && emptyLabel?.isShown == false
                }
                rendered
            }
            scenario.onActivity { activity ->
                assertTrue("Retained selected Page can enter another Save after the prior workflow ends",
                    activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled ?: false)
            }
            android.util.Log.i(TAG, "ACTIVITY_SAVE_PASS outputs=2 attempts=SUCCEEDED package=SUCCEEDED frozen_page_consumed=true nonfrozen_page_retained=true home_reenabled=true")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            continueProviderWrite.countDown()
            scenario?.close()
            app.productStorageOverrideForTest = null
            tree?.let { selectedTree ->
                val createdByNamespace = if (namespaceWasAbsent) {
                    runCatching { childDocuments(context.contentResolver, selectedTree).filter { it.name.startsWith(basename) } }
                        .onFailure { cleanupFailure = it }.getOrDefault(emptyList()).map { it.uri }
                } else emptyList()
                (createdByNamespace + childUris(context.contentResolver, selectedTree).filter { uri ->
                    runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() in createdByNamespace.mapNotNull {
                        runCatching { DocumentsContract.getDocumentId(it) }.getOrNull()
                    }
                }).distinct().forEach { uri ->
                    val deleted = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                    if (!deleted) cleanupFailure = IllegalStateException("Could not delete test-named output $uri", cleanupFailure)
                }
                if (namespaceWasAbsent) {
                    runCatching { childDocuments(context.contentResolver, selectedTree).none { it.name.startsWith(basename) } }
                        .onSuccess { clean -> if (!clean) cleanupFailure = IllegalStateException("Test output remains after cleanup", cleanupFailure) }
                        .onFailure { cleanupFailure = it }
                }
                if (selectedTree !in priorPermissions) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure { cleanupFailure = it }
                }
            }
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("ProductActivity Save flow or cleanup failed", it) }
    }

    @Test
    fun staleProductActivitySaveAfterGrantRevocationFailsBeforeFreezeOrProviderAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "activity-save-revoked-$suffix.db"
        val artifactDirectory = "activity-save-revoked-artifacts-$suffix"
        val candidateDirectory = "activity-save-revoked-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorPermissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("revoked Save fixture picker Activity ready") {
                var ready = false
                scenario!!.onActivity {
                    ready = it.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true
                }
                ready
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")).performClick()
            }
            selectDocuments(UiDevice.getInstance(instrumentation))
            val grant = awaitValue("revoked Save fixture real picker grant") { storage.destinationGrant() }
            tree = Uri.parse(grant.treeUri)
            assertFalse("Never revoke a tree permission that predated this test", tree in priorPermissions)
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(tree))
            scenario.close()
            scenario = null

            SafRuntimeDestinationCheck(context, storage).ensureReady(tree.toString())
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawText("REVOKED SAVE", 20f, 100f, Paint().apply { color = Color.BLACK; textSize = 24f })
            }
            val jpeg = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                stream.toByteArray()
            }
            bitmap.recycle()
            storage.importImage(jpeg.inputStream(), "image/jpeg", "activity-save-revoked-fixture.jpg")
            storage.visiblePages().single().let { storage.setSelected(it.pageId, true) }
            val sourcePage = storage.visiblePages().single()
            val sourceFile = storage.artifactPath(sourcePage.artifactRelativePath).toFile()
            val sourceBytesBefore = sourceFile.readBytes()
            assertEquals("Fresh isolated DB begins without SaveManifest rows", 0, countRows(context.getDatabasePath(databaseName), "SaveManifest"))
            assertEquals("Fresh isolated DB begins without ExportArtifact rows", 0, countRows(context.getDatabasePath(databaseName), "ExportArtifact"))

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("Disposable tuple makes the test Activity Save control available") {
                var enabled = false
                scenario!!.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("Save name dialog appears before grant revocation", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000L))
            val basename = "docscan-stale-save-$suffix"
            device.findObject(By.clazz("android.widget.EditText")).text = basename

            SafDestinationGrantManager(context).releasePersistedGrant(tree.toString())
            assertFalse(
                "The test's OS persisted read/write grant must be absent before confirmation",
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == tree && it.isReadPermission && it.isWritePermission
                },
            )
            device.findObject(By.text("Save selected pages")).click()

            val expectedStatus = context.getString(
                R.string.export_failed,
                context.getString(R.string.destination_revoked),
            )
            await("Stale Save surfaces the revoked destination failure") {
                var statusText = ""
                scenario!!.onActivity {
                    statusText = it.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text?.toString().orEmpty()
                }
                statusText == expectedStatus
            }
            val retainedPage = storage.workspaceProjection().pages.single()
            assertEquals("Original selected Page identity remains", sourcePage.pageId, retainedPage.pageId)
            assertTrue("Source Page remains selected", retainedPage.selected)
            assertEquals("Source artifact bytes remain unchanged", sourceBytesBefore.toList(), sourceFile.readBytes().toList())
            assertEquals("No SaveManifest was frozen after grant revocation", 0, countRows(context.getDatabasePath(databaseName), "SaveManifest"))
            assertEquals("No local output package was committed", 0, countRows(context.getDatabasePath(databaseName), "ExportArtifact"))
            assertEquals("No external attempt was journaled", 0, countRows(context.getDatabasePath(databaseName), "ExportAttempt"))
            assertTrue("No incomplete export package exists", storage.incompleteExternalExportPackages().isEmpty())
            assertFalse("No local composition candidate was created", context.noBackupFilesDir.resolve(candidateDirectory).exists())
            android.util.Log.i(TAG, "ACTIVITY_REVOKED_SAVE_PASS grant_absent=true manifest_rows=0 exports=0 attempts=0 source_retained=true source_sha256=${sha256(sourceBytesBefore)} provider_io_after_revoke=0_by_preflight_order")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            tree?.takeIf { it !in priorPermissions }?.let { selectedTree ->
                val stillPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == selectedTree }
                if (stillPersisted) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure { cleanupFailure = it }
                }
            }
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("ProductActivity stale Save grant revocation or cleanup failed", it) }
    }

    @Test
    fun savedPdfEditorAfterGrantRevocationKeepsConfirmedSourceAndDoesNotPublish() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "saved-pdf-revoked-$suffix.db"
        val artifactDirectory = "saved-pdf-revoked-artifacts-$suffix"
        val candidateDirectory = "saved-pdf-revoked-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorPermissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val basename = "docscan-saved-pdf-revoked-$suffix"
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var grantRevoked = false
        var fixtureOutputsDeleted = false
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("Saved-PDF revoke fixture picker Activity ready") {
                var ready = false
                scenario!!.onActivity {
                    ready = it.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true
                }
                ready
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")).performClick()
            }
            selectDocuments(UiDevice.getInstance(instrumentation))
            val grant = awaitValue("Saved-PDF revoke fixture real picker grant") { storage.destinationGrant() }
            tree = Uri.parse(grant.treeUri)
            assertFalse("Never revoke a tree permission that predated this test", tree in priorPermissions)
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(tree))
            scenario.close()
            scenario = null

            SafRuntimeDestinationCheck(context, storage).ensureReady(tree.toString())
            repeat(2) { pageIndex ->
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.WHITE)
                    Canvas(this).drawText("EDIT SOURCE ${pageIndex + 1}", 20f, 100f, Paint().apply { color = Color.BLACK; textSize = 24f })
                }
                val jpeg = ByteArrayOutputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream))
                    stream.toByteArray()
                }
                bitmap.recycle()
                val page = storage.importImage(jpeg.inputStream(), "image/jpeg", "saved-pdf-edit-source-${pageIndex + 1}.jpg")
                storage.setSelected(page.pageId, true)
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("Two selected source Pages enable normal Save") {
                var enabled = false
                scenario!!.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("Normal Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000L))
            device.findObject(By.clazz("android.widget.EditText")).text = basename
            val normalSaveButton = By.text("Save selected pages")
            assertTrue("Normal Save confirmation is available", device.wait(Until.hasObject(normalSaveButton), 5_000L))
            device.findObject(normalSaveButton).click()

            val recent = awaitValue("Positive normal Save creates a confirmed two-page RecentDocument") {
                storage.recentDocuments().singleOrNull()?.takeIf { it.pageCount == 2L }
            }
            val originalPackage = requireNotNull(storage.externalExportPackage(recent.exportArtifactId))
            assertEquals("SUCCEEDED", originalPackage.state)
            val originalAttempts = storage.exportAttempts(recent.exportArtifactId)
            assertEquals(3, originalAttempts.size)
            assertTrue(originalAttempts.all { it.state == "SUCCEEDED" })
            val sourcePdf = storage.committedExportOutputs(recent.exportArtifactId).single { it.mimeType == "application/pdf" }
            val sourceFile = storage.recentSavedPdfFile(recent)
            val sourceBytes = sourceFile.readBytes()
            val sourceSha = sha256(sourceBytes)
            assertEquals(sourcePdf.sha256, sourceSha)
            assertTrue(storage.verifyExportOutput(sourcePdf))

            await("Activity renders the confirmed Recent-document action") {
                var visible = false
                scenario!!.onActivity { activity ->
                    val button = activity.window.decorView.findViewWithTag<android.view.View>("docscan-recent-button")
                    visible = button?.visibility == android.view.View.VISIBLE && button.isEnabled
                }
                visible
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recent-button")).performClick()
            }
            val recentRow = By.text("${recent.displayName} · ${recent.pageCount}")
            assertTrue("Confirmed RecentDocument appears in picker", device.wait(Until.hasObject(recentRow), 5_000L))
            assertRecentDialogAccessibility(
                context,
                "Recent documents",
                listOf("${recent.displayName} · ${recent.pageCount}", context.getString(android.R.string.cancel)),
            )
            device.findObject(recentRow).click()
            assertRecentDialogAccessibility(
                context,
                recent.displayName,
                listOf(
                    context.getString(R.string.recent_action_open),
                    context.getString(R.string.recent_action_preview),
                    context.getString(R.string.recent_action_edit),
                    context.getString(R.string.recent_action_try_again),
                    context.getString(android.R.string.cancel),
                ),
            )
            val previewAction = By.text(context.getString(R.string.recent_action_preview))
            assertTrue("Recent-document preview action appears", device.wait(Until.hasObject(previewAction), 5_000L))
            device.findObject(previewAction).click()
            val firstPreviewPage = By.text(context.getString(R.string.saved_pdf_page, 1, 2))
            assertTrue("In-app preview renders the first confirmed PDF page", device.wait(Until.hasObject(firstPreviewPage), 10_000L))
            assertSavedPdfPreviewAccessibility(context)
            val previewPreviousSelector = By.text(Pattern.compile("(?i)${Pattern.quote(context.getString(R.string.viewer_previous))}"))
            val previewNextSelector = By.text(Pattern.compile("(?i)${Pattern.quote(context.getString(R.string.viewer_next))}"))
            assertTrue("Preview Previous button is visible", device.wait(Until.hasObject(previewPreviousSelector), 5_000L))
            assertTrue("Preview Next button is visible", device.wait(Until.hasObject(previewNextSelector), 5_000L))
            val previewPrevious = requireNotNull(device.findObject(previewPreviousSelector))
            val previewNext = requireNotNull(device.findObject(previewNextSelector))
            assertFalse("First preview page cannot navigate before the document", previewPrevious.isEnabled)
            await("Preview's first page is ready for forward navigation") { previewNext.isEnabled }
            previewNext.click()
            val lastPreviewPage = By.text(context.getString(R.string.saved_pdf_page, 2, 2))
            assertTrue("Preview navigates to the final confirmed PDF page", device.wait(Until.hasObject(lastPreviewPage), 10_000L))
            await("Preview's final page is rendered and navigation bounds update") {
                previewPrevious.isEnabled && !previewNext.isEnabled
            }
            previewPrevious.click()
            assertTrue("Preview navigates back to page one", device.wait(Until.hasObject(firstPreviewPage), 10_000L))
            await("Preview returns to page one without changing navigation bounds") {
                !previewPrevious.isEnabled && previewNext.isEnabled
            }
            assertEquals("Preview leaves the recent index unchanged", recent, storage.recentDocuments().single())
            assertEquals("Preview leaves the committed private PDF bytes unchanged", sourceSha, sha256(sourceFile.readBytes()))
            device.pressBack()

            scenario!!.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recent-button")).performClick()
            }
            assertTrue("Confirmed Recent entry reopens after preview closes", device.wait(Until.hasObject(recentRow), 5_000L))
            device.findObject(recentRow).click()
            val workspaceBeforeRetry = storage.workspaceProjection().pages
            val oldPageIds = workspaceBeforeRetry.map { it.pageId }.toSet()
            val oldImageVersionIds = workspaceBeforeRetry.map { it.imageVersionId }.toSet()
            fun importSessionIds(): Set<String> {
                return android.database.sqlite.SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.rawQuery("SELECT import_session_id FROM ImportSession", null).use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
                }
            }
            val importSessionIdsBeforeRetry = importSessionIds()
            val tryAgainAction = By.text(context.getString(R.string.recent_action_try_again))
            assertTrue("Recent-document menu exposes Try again", device.wait(Until.hasObject(tryAgainAction), 5_000L))
            device.findObject(tryAgainAction).click()
            val expectedRetryStatus = context.getString(R.string.try_again_complete, 2)
            await("Recent-menu Try again completes both saved-PDF pages") {
                var statusText = ""
                scenario!!.onActivity { activity ->
                    statusText = activity.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text?.toString().orEmpty()
                }
                statusText == expectedRetryStatus
            }
            val workspaceAfterRetry = storage.workspaceProjection().pages
            val retriedPages = workspaceAfterRetry.filter { it.pageId !in oldPageIds }.sortedBy { it.orderIndex }
            assertEquals("Recent-menu Try again materializes exactly two new Pages", 2, retriedPages.size)
            assertTrue("Every imported PDF Page is selected", retriedPages.all { it.selected })
            assertTrue("Every retry Page has a fresh Page identity", retriedPages.none { it.pageId in oldPageIds })
            assertTrue("Every retry Page has a fresh ImageVersion identity", retriedPages.none { it.imageVersionId in oldImageVersionIds })
            assertEquals("Retry creates one new ImportSession", 1, importSessionIds().subtract(importSessionIdsBeforeRetry).size)
            val retryImportSessionId = ImportSessionId.parse(
                importSessionIds().subtract(importSessionIdsBeforeRetry).single(),
            )
            assertEquals(
                "New ImportSession provenance maps exactly to the imported Page identities in source order",
                retriedPages.map { it.pageId },
                storage.importedPdfPageIds(retryImportSessionId),
            )
            assertEquals("Try again leaves the confirmed RecentDocument unchanged", recent, storage.recentDocuments().single())
            assertEquals("Try again leaves committed private PDF bytes unchanged", sourceSha, sha256(sourceFile.readBytes()))
            assertEquals(sourceBytes.toList(), sourceFile.readBytes().toList())
            assertTrue("The original confirmed PDF record remains valid", storage.verifyExportOutput(sourcePdf))
            val tableCountsBeforeEdit = mapOf(
                "SaveManifest" to countRows(context.getDatabasePath(databaseName), "SaveManifest"),
                "SavedPdfEditComposition" to countRows(context.getDatabasePath(databaseName), "SavedPdfEditComposition"),
                "ExportArtifact" to countRows(context.getDatabasePath(databaseName), "ExportArtifact"),
                "ExternalExportPackage" to countRows(context.getDatabasePath(databaseName), "ExternalExportPackage"),
                "ExportAttempt" to countRows(context.getDatabasePath(databaseName), "ExportAttempt"),
            )
            val candidateRoot = context.noBackupFilesDir.resolve(candidateDirectory)
            val candidateEntriesBeforeEdit = candidateRoot.list()?.toSet().orEmpty()

            scenario!!.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recent-button")).performClick()
            }
            assertTrue("Recent entry remains available after Try again", device.wait(Until.hasObject(recentRow), 5_000L))
            device.findObject(recentRow).click()
            val editAction = By.text(context.getString(R.string.recent_action_edit))
            assertTrue("Recent-document editor action appears", device.wait(Until.hasObject(editAction), 5_000L))
            device.findObject(editAction).click()
            val editTitle = By.text(context.getString(R.string.saved_pdf_edit_title))
            assertTrue("Saved-PDF editor opens", device.wait(Until.hasObject(editTitle), 5_000L))
            val firstPage = By.text(context.getString(R.string.saved_pdf_edit_page, 1, 2))
            assertTrue("Private confirmed PDF renders its first page", device.wait(Until.hasObject(firstPage), 10_000L))

            // Remove only the three files created by the positive fixture while access is still held.
            originalAttempts.forEach { attempt ->
                val uri = Uri.parse(requireNotNull(attempt.externalIdentity))
                assertTrue("Test-owned output is deleted before revocation", DocumentsContract.deleteDocument(context.contentResolver, uri))
            }
            fixtureOutputsDeleted = true
            assertTrue("Deleted output cleanup is recorded before revocation", fixtureOutputsDeleted)
            assertEquals("Private RecentDocument PDF survives removal of its external fixture outputs", sourceSha, sha256(sourceFile.readBytes()))

            val moveLater = By.text(Pattern.compile("(?i)^${Pattern.quote(context.getString(R.string.saved_pdf_edit_move_down))}$"))
            val hierarchy = context.cacheDir.resolve("saved-pdf-editor-window.xml")
            device.dumpWindowHierarchy(hierarchy)
            android.util.Log.i(TAG, "SAVED_PDF_EDITOR_WINDOW ${hierarchy.readText()}")
            assertTrue("Editor order control appears", device.wait(Until.hasObject(moveLater), 5_000L))
            device.findObject(moveLater).click()
            val secondPage = By.text(context.getString(R.string.saved_pdf_edit_page, 2, 2))
            assertTrue("Order change finishes and makes editor dirty", device.wait(Until.hasObject(secondPage), 10_000L))
            val editSave = By.text(context.getString(R.string.saved_pdf_edit_save))
            await("Dirty editor Save button becomes enabled") {
                device.wait(Until.findObject(editSave), 500L)?.isEnabled == true
            }

            SafDestinationGrantManager(context).releasePersistedGrant(tree.toString())
            assertFalse(
                "The test's OS persisted read/write grant must be absent before edited-PDF Save",
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == tree && it.isReadPermission && it.isWritePermission
                },
            )
            grantRevoked = true
            device.findObject(editSave).click()
            val expectedStatus = context.getString(
                R.string.saved_pdf_edit_save_failed,
                context.getString(R.string.destination_revoked),
            )
            await("Saved-PDF editor surfaces revoked-destination failure") {
                var text = ""
                scenario!!.onActivity {
                    text = it.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text?.toString().orEmpty()
                }
                text == expectedStatus
            }

            tableCountsBeforeEdit.forEach { (table, count) ->
                assertEquals("$table remains at its confirmed-source baseline after denied edit Save", count,
                    countRows(context.getDatabasePath(databaseName), table))
            }
            assertEquals("Recent-document metadata remains unchanged", recent, storage.recentDocuments().single())
            assertEquals("Original private PDF byte digest remains unchanged", sourceSha, sha256(sourceFile.readBytes()))
            assertEquals(sourceBytes.toList(), sourceFile.readBytes().toList())
            assertEquals("Denied editor Save creates no candidate beyond the confirmed-source baseline",
                candidateEntriesBeforeEdit, candidateRoot.list()?.toSet().orEmpty())
            assertEquals("No test provider outputs remain by the pre-revoke cleanup record", true, fixtureOutputsDeleted)
            android.util.Log.i(TAG, "SAVED_PDF_REVOKED_PASS grant_absent=true prior_outputs_deleted_before_revoke=true baseline_counts=$tableCountsBeforeEdit private_pdf_sha256=$sourceSha provider_io_after_revoke=0_by_preflight_order")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            tree?.takeIf { it !in priorPermissions }?.let { selectedTree ->
                val stillPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == selectedTree }
                if (stillPersisted && !grantRevoked) {
                    runCatching {
                        val cleanupNamespace = childDocuments(context.contentResolver, selectedTree)
                            .filter { it.name.startsWith(basename) }
                        cleanupNamespace.forEach { child ->
                            check(DocumentsContract.deleteDocument(context.contentResolver, child.uri)) {
                                "Could not delete test-owned output ${child.uri} during pre-revoke cleanup"
                            }
                        }
                        check(childDocuments(context.contentResolver, selectedTree).none { it.name.startsWith(basename) })
                    }.onFailure { cleanupFailure = it }
                }
                if (stillPersisted) {
                    runCatching { SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString()) }
                        .onFailure { if (cleanupFailure == null) cleanupFailure = it else cleanupFailure!!.addSuppressed(it) }
                }
                if (grantRevoked) {
                    check(fixtureOutputsDeleted) { "Grant was revoked before test-owned provider outputs were cleaned" }
                }
            }
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("Saved-PDF editor grant revocation or cleanup failed", it) }
    }

    @Test
    fun savedPdfEditorSavePublishesNewConfirmedRecentAndPreservesOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val suffix = UUID.randomUUID().toString()
        val databaseName = "saved-pdf-edit-success-$suffix.db"
        val artifactDirectory = "saved-pdf-edit-success-artifacts-$suffix"
        val candidateDirectory = "saved-pdf-edit-success-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorPermissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        val basename = "docscan-saved-pdf-edit-success-$suffix"
        var scenario: ActivityScenario<ProductActivity>? = null
        var tree: Uri? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        var originalRecent: app.docscan.core.store.RecentDocumentSnapshot? = null
        var originalPdf: java.io.File? = null
        var originalPdfSha: String? = null
        var editedRecent: app.docscan.core.store.RecentDocumentSnapshot? = null

        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("Editor success fixture destination picker Activity ready") {
                var ready = false
                scenario!!.onActivity {
                    ready = it.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true
                }
                ready
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")).performClick()
            }
            selectDocuments(UiDevice.getInstance(instrumentation))
            val grant = awaitValue("Editor success fixture real picker grant") { storage.destinationGrant() }
            tree = Uri.parse(grant.treeUri)
            assertFalse("Never assume ownership of a pre-existing destination grant", tree in priorPermissions)
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(tree))
            scenario.close()
            scenario = null

            SafRuntimeDestinationCheck(context, storage).ensureReady(tree.toString())
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, pageColor ->
                val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(pageColor)
                    Canvas(this).drawText("EDIT SUCCESS PAGE ${index + 1}", 16f, 38f, Paint().apply { color = Color.BLACK; textSize = 17f })
                }
                val jpeg = ByteArrayOutputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, stream))
                    stream.toByteArray()
                }
                bitmap.recycle()
                val page = storage.importImage(jpeg.inputStream(), "image/jpeg", "edit-success-source-${index + 1}.jpg")
                storage.setSelected(page.pageId, true)
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("Two selected source Pages enable the normal Save control") {
                var enabled = false
                scenario!!.onActivity {
                    enabled = it.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")?.isEnabled == true
                }
                enabled
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-save-button")).performClick()
            }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue("Normal Save name dialog appears", device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000L))
            device.findObject(By.clazz("android.widget.EditText")).text = basename
            val normalSave = By.text("Save selected pages")
            assertTrue("Normal Save confirmation is available", device.wait(Until.hasObject(normalSave), 5_000L))
            device.findObject(normalSave).click()

            val sourceRecent = awaitValue("Normal Save creates the two-page original Recent entry") {
                storage.recentDocuments().singleOrNull()?.takeIf { it.pageCount == 2L }
            }
            originalRecent = sourceRecent
            val sourcePackage = requireNotNull(storage.externalExportPackage(sourceRecent.exportArtifactId))
            assertEquals("SUCCEEDED", sourcePackage.state)
            val sourceAttempts = storage.exportAttempts(sourceRecent.exportArtifactId)
            assertEquals(3, sourceAttempts.size)
            assertTrue(sourceAttempts.all { it.state == "SUCCEEDED" })
            sourceAttempts.forEach { attempt ->
                val uri = Uri.parse(requireNotNull(attempt.externalIdentity))
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                val output = storage.committedExportOutputs(sourceRecent.exportArtifactId).single { it.artifactId == attempt.outputArtifactId }
                assertEquals(output.sizeBytes, bytes.size.toLong())
                assertEquals(output.sha256, sha256(bytes))
            }
            originalPdf = storage.recentSavedPdfFile(sourceRecent)
            val originalBytes = originalPdf!!.readBytes()
            originalPdfSha = sha256(originalBytes)
            assertTrue(storage.committedExportOutputs(sourceRecent.exportArtifactId).single { it.mimeType == "application/pdf" }
                .let(storage::verifyExportOutput))

            await("ProductActivity shows the confirmed Recent-document action") {
                var visible = false
                scenario!!.onActivity { activity ->
                    val button = activity.window.decorView.findViewWithTag<android.view.View>("docscan-recent-button")
                    visible = button?.visibility == android.view.View.VISIBLE && button.isEnabled
                }
                visible
            }
            scenario.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recent-button")).performClick()
            }
            val sourceRecentRow = By.text("${sourceRecent.displayName} · ${sourceRecent.pageCount}")
            assertTrue("Original Recent entry is available", device.wait(Until.hasObject(sourceRecentRow), 5_000L))
            device.findObject(sourceRecentRow).click()
            val editAction = By.text(context.getString(R.string.recent_action_edit))
            assertTrue("Edit PDF action appears", device.wait(Until.hasObject(editAction), 5_000L))
            device.findObject(editAction).click()
            assertTrue("Saved-PDF editor opens", device.wait(Until.hasObject(By.text(context.getString(R.string.saved_pdf_edit_title))), 5_000L))
            assertTrue("Original first page renders", device.wait(Until.hasObject(By.text(context.getString(R.string.saved_pdf_edit_page, 1, 2))), 10_000L))

            val editorTitle = By.text(context.getString(R.string.saved_pdf_edit_title))
            val firstDraftPage = By.text(context.getString(R.string.saved_pdf_edit_page, 1, 2))
            val oneDraftPage = By.text(context.getString(R.string.saved_pdf_edit_page, 1, 1))
            val deleteDraftPage = By.text(Pattern.compile("(?i)^${Pattern.quote(context.getString(R.string.saved_pdf_edit_delete))}$"))
            val undoDraftEdit = By.text(Pattern.compile("(?i)^${Pattern.quote(context.getString(R.string.saved_pdf_edit_undo))}$"))
            val editorSave = By.text(Pattern.compile("(?i)^${Pattern.quote(context.getString(R.string.saved_pdf_edit_save))}$"))
            val dirtyPromptTitle = By.text(context.getString(R.string.saved_pdf_edit_unsaved_title))
            val cancelDirtyPrompt = By.text(Pattern.compile("(?i)^${Pattern.quote(context.getString(android.R.string.cancel))}$"))
            val discardDirtyDraft = By.text(Pattern.compile("(?i)^${Pattern.quote(context.getString(R.string.saved_pdf_edit_discard))}$"))
            await("Clean editor starts without Undo") { device.wait(Until.findObject(undoDraftEdit), 500L)?.isEnabled == false }
            device.findObject(deleteDraftPage).click()
            assertTrue("Deleting a draft page leaves one page", device.wait(Until.hasObject(oneDraftPage), 10_000L))
            await("Dirty draft enables Undo and Save") {
                device.wait(Until.findObject(undoDraftEdit), 500L)?.isEnabled == true
                    && device.wait(Until.findObject(editorSave), 500L)?.isEnabled == true
            }
            device.pressBack()
            assertTrue("Back from a dirty editor opens the unsaved-changes prompt", device.wait(Until.hasObject(dirtyPromptTitle), 5_000L))
            assertTrue("Dirty prompt offers Cancel", device.wait(Until.hasObject(cancelDirtyPrompt), 5_000L))
            device.findObject(cancelDirtyPrompt).click()
            assertTrue("Cancel keeps the dirty one-page draft open", device.wait(Until.hasObject(oneDraftPage), 5_000L))
            device.findObject(undoDraftEdit).click()
            assertTrue("Undo restores the two-page source order", device.wait(Until.hasObject(firstDraftPage), 10_000L))
            await("Undo to clean disables Undo and Save") {
                device.wait(Until.findObject(undoDraftEdit), 500L)?.isEnabled == false
                    && device.wait(Until.findObject(editorSave), 500L)?.isEnabled == false
            }

            device.findObject(deleteDraftPage).click()
            assertTrue("Second dirty edit removes one draft page", device.wait(Until.hasObject(oneDraftPage), 10_000L))
            device.pressBack()
            assertTrue("Dirty exit prompt reopens for the second edit", device.wait(Until.hasObject(dirtyPromptTitle), 5_000L))
            assertTrue("Dirty prompt offers Discard", device.wait(Until.hasObject(discardDirtyDraft), 5_000L))
            device.findObject(discardDirtyDraft).click()
            assertTrue("Discard closes the saved-PDF editor", device.wait(Until.gone(editorTitle), 5_000L))
            assertEquals("Discard preserves original Recent metadata", sourceRecent, storage.recentDocuments().single())
            assertEquals("Discard preserves the original private PDF hash", originalPdfSha, sha256(requireNotNull(originalPdf).readBytes()))

            scenario!!.onActivity { activity ->
                requireNotNull(activity.window.decorView.findViewWithTag<android.view.View>("docscan-recent-button")).performClick()
            }
            assertTrue("Original Recent entry is available after Discard", device.wait(Until.hasObject(sourceRecentRow), 5_000L))
            device.findObject(sourceRecentRow).click()
            assertTrue("Edit PDF action remains available after Discard", device.wait(Until.hasObject(editAction), 5_000L))
            device.findObject(editAction).click()
            assertTrue("Reopened editor starts from the unchanged two-page source", device.wait(Until.hasObject(firstDraftPage), 10_000L))

            val moveLater = By.text(Pattern.compile("(?i)^${Pattern.quote(context.getString(R.string.saved_pdf_edit_move_down))}$"))
            assertTrue("Editor order control appears", device.wait(Until.hasObject(moveLater), 5_000L))
            device.findObject(moveLater).click()
            assertTrue("Reordered second page is displayed", device.wait(Until.hasObject(By.text(context.getString(R.string.saved_pdf_edit_page, 2, 2))), 10_000L))
            await("Dirty editor enables Save") { device.wait(Until.findObject(editorSave), 500L)?.isEnabled == true }
            device.findObject(editorSave).click()

            val editedDisplayName = "$basename (edited).pdf"
            val expectedSuccess = context.getString(R.string.saved_pdf_edit_saved, editedDisplayName)
            val failedStatusPrefix = context.getString(R.string.saved_pdf_edit_save_failed, "").trimEnd()
            val terminalEditorStatus = awaitValue("Saved-PDF editor leaves its saving state") {
                var statusText = ""
                scenario!!.onActivity {
                    statusText = it.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text?.toString().orEmpty()
                }
                statusText.takeIf { it == expectedSuccess || it.startsWith(failedStatusPrefix) }
            }
            assertEquals("Saved-PDF editor surfaces confirmed export success", expectedSuccess, terminalEditorStatus)
            val changedRecent = awaitValue("New edited PDF is projected into Recent") {
                storage.recentDocuments().singleOrNull { it.exportArtifactId != sourceRecent.exportArtifactId }
            }
            editedRecent = changedRecent
            assertNotEquals("Edited export uses a new immutable output identity", sourceRecent.exportArtifactId, changedRecent.exportArtifactId)
            assertEquals(editedDisplayName, changedRecent.displayName)
            assertEquals(2L, changedRecent.pageCount)
            val editedPackage = requireNotNull(storage.externalExportPackage(changedRecent.exportArtifactId))
            assertEquals("SUCCEEDED", editedPackage.state)
            assertEquals(1, editedPackage.outputs.size)
            val editedOutput = storage.committedExportOutputs(changedRecent.exportArtifactId).single { it.mimeType == "application/pdf" }
            assertTrue("Edited private output passes committed-file verification", storage.verifyExportOutput(editedOutput))
            val editedFile = storage.recentSavedPdfFile(changedRecent)
            val editedBytes = editedFile.readBytes()
            val editedSha = sha256(editedBytes)
            assertEquals(editedOutput.sha256, editedSha)
            assertNotEquals("Reordering produces bytes distinct from the original", originalPdfSha, editedSha)
            val editedAttempts = storage.exportAttempts(changedRecent.exportArtifactId)
            assertEquals(1, editedAttempts.size)
            assertEquals("SUCCEEDED", editedAttempts.single().state)
            val externalEditedBytes = requireNotNull(context.contentResolver.openInputStream(Uri.parse(requireNotNull(editedAttempts.single().externalIdentity))))
                .use { it.readBytes() }
            assertEquals(editedOutput.sizeBytes, externalEditedBytes.size.toLong())
            assertEquals(editedOutput.sha256, sha256(externalEditedBytes))

            val orderedIndexes = context.getDatabasePath(databaseName).let { databaseFile ->
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    databaseFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.rawQuery(
                        "SELECT c.output_page_order FROM SavedPdfEditComposition c JOIN ExportArtifact e ON e.save_manifest_id = c.save_manifest_id WHERE e.export_artifact_id = ?",
                        arrayOf(changedRecent.exportArtifactId.value),
                    ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
                }
            }
            assertEquals("[1,0]", orderedIndexes)
            val originalRowAfterEdit = storage.recentDocuments().single { it.exportArtifactId == sourceRecent.exportArtifactId }
            assertEquals("Original Recent metadata remains unchanged", sourceRecent, originalRowAfterEdit)
            assertEquals("Original private PDF remains byte-for-byte unchanged", originalBytes.toList(), originalPdf!!.readBytes().toList())
            assertEquals("Original private PDF digest remains unchanged", originalPdfSha, sha256(originalPdf!!.readBytes()))

            val originalFirst = SavedPdfPageRenderer.renderPage(originalPdf!!, 2, 0, app.heavyWorkScheduler).bitmap
            val originalSecond = SavedPdfPageRenderer.renderPage(originalPdf!!, 2, 1, app.heavyWorkScheduler).bitmap
            val editedFirst = SavedPdfPageRenderer.renderPage(editedFile, 2, 0, app.heavyWorkScheduler).bitmap
            val editedSecond = SavedPdfPageRenderer.renderPage(editedFile, 2, 1, app.heavyWorkScheduler).bitmap
            try {
                fun center(bitmap: Bitmap) = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                val sourceFirstPixel = center(originalFirst)
                val sourceSecondPixel = center(originalSecond)
                val editedFirstPixel = center(editedFirst)
                val editedSecondPixel = center(editedSecond)
                assertTrue("Original first-page fixture is red", Color.red(sourceFirstPixel) > Color.blue(sourceFirstPixel) + 50)
                assertTrue("Original second-page fixture is blue", Color.blue(sourceSecondPixel) > Color.red(sourceSecondPixel) + 50)
                assertTrue("Edited page 1 follows reordered blue source page", Color.blue(editedFirstPixel) > Color.red(editedFirstPixel) + 50)
                assertTrue("Edited page 2 follows reordered red source page", Color.red(editedSecondPixel) > Color.blue(editedSecondPixel) + 50)
            } finally {
                originalFirst.recycle(); originalSecond.recycle(); editedFirst.recycle(); editedSecond.recycle()
            }
            assertEquals("One normal and one edit manifest exist", 2, countRows(context.getDatabasePath(databaseName), "SaveManifest"))
            assertEquals("Exactly one saved-PDF edit composition committed", 1, countRows(context.getDatabasePath(databaseName), "SavedPdfEditComposition"))
            assertEquals("The source and edited outputs have distinct export artifacts", 2, countRows(context.getDatabasePath(databaseName), "ExportArtifact"))
            assertEquals("Source and edited packages are both confirmed", 2, countRows(context.getDatabasePath(databaseName), "ExternalExportPackage"))
            assertEquals("Three source and one edit provider attempts are confirmed", 4, countRows(context.getDatabasePath(databaseName), "ExportAttempt"))
            android.util.Log.i(TAG, "SAVED_PDF_EDIT_SUCCESS_PASS source_export=${sourceRecent.exportArtifactId.value} edited_export=${changedRecent.exportArtifactId.value} source_pdf_sha256=$originalPdfSha edited_pdf_sha256=$editedSha source_recent_preserved=true edit_order=$orderedIndexes attempts=4/all_succeeded cleanup_prefix=$basename")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            scenario?.close()
            app.productStorageOverrideForTest = null
            tree?.takeIf { it !in priorPermissions }?.let { selectedTree ->
                val stillPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == selectedTree }
                if (stillPersisted) {
                    runCatching {
                        val documents = childDocuments(context.contentResolver, selectedTree).filter { it.name.startsWith(basename) }
                        documents.forEach { child ->
                            check(DocumentsContract.deleteDocument(context.contentResolver, child.uri)) {
                                "Could not delete test-owned provider output ${child.uri}"
                            }
                        }
                        check(childDocuments(context.contentResolver, selectedTree).none { it.name.startsWith(basename) })
                        SafDestinationGrantManager(context).releasePersistedGrant(selectedTree.toString())
                    }.onFailure { cleanupFailure = it }
                }
            }
            runCatching { storage.close() }.onFailure { cleanupFailure = it }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
            cleanupFailure?.let { if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it) }
        }
        primaryFailure?.let { throw AssertionError("Saved-PDF editor success or cleanup failed", it) }
    }

    private data class ProviderChild(val uri: Uri, val name: String)

    private fun childDocuments(resolver: android.content.ContentResolver, tree: Uri): List<ProviderChild> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val result = mutableListOf<ProviderChild>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                result += ProviderChild(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(id)), cursor.getString(name))
            }
        } ?: error("No child query result")
        return result
    }

    @Test
    fun unknownOutputWithMatchingReadBackIsRecoveredWithoutASecondWrite() {
        val fixture = newRecoveryFixture()
        val created = mutableListOf<Uri>()
        var primaryFailure: Throwable? = null
        try {
            val exportId = composeAndCommit(
                fixture.storage, fixture.basename, fixture.tree.toString(), fixture.qualificationId,
                fixture.adapterId, fixture.providerVersion, fixture.osVersion, fixture.accessClass,
            )
            val exportPackage = requireNotNull(fixture.storage.externalExportPackage(exportId))
            val outputs = fixture.storage.committedExportOutputs(exportId).associateBy { it.artifactId }
            val firstMapping = exportPackage.outputs.first()
            val firstOutput = requireNotNull(outputs[firstMapping.outputArtifactId])
            val gateway = SafExportGateway(fixture.context, fixture.storage)
            val firstUri = gateway.export(recoveryAttempt(fixture, exportId, firstMapping, firstOutput))
            created += firstUri

            val lastMapping = exportPackage.outputs.last()
            val lastOutput = requireNotNull(outputs[lastMapping.outputArtifactId])
            val command = recoveryAttempt(fixture, exportId, lastMapping, lastOutput)
            fixture.storage.beginExportAttempt(command)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                fixture.tree, DocumentsContract.getTreeDocumentId(fixture.tree),
            )
            val sideEffect = requireNotNull(
                DocumentsContract.createDocument(fixture.context.contentResolver, parent, command.mimeType, command.plannedDisplayName),
            )
            created += sideEffect
            fixture.storage.exportOutputFile(lastOutput).inputStream().use { source ->
                requireNotNull(fixture.context.contentResolver.openOutputStream(sideEffect, "w")).use(source::copyTo)
            }
            val providerBytesBeforeRecovery = requireNotNull(fixture.context.contentResolver.openInputStream(sideEffect)).use { it.readBytes() }
            assertEquals(lastOutput.sizeBytes, providerBytesBeforeRecovery.size.toLong())
            assertEquals(lastOutput.sha256, sha256(providerBytesBeforeRecovery))
            fixture.storage.markWritingExportAttemptUnknown(
                command.exportAttemptId, "simulated lost acknowledgement after completed provider write",
                recoveryObservation("recovery-unknown-${command.exportAttemptId.value}", "provider side effect exists; app acknowledgement absent"),
            )
            assertEquals("UNKNOWN_OUTCOME", fixture.storage.exportAttempt(command.exportAttemptId)?.state)

            val beforeRecoveryUris = childUris(fixture.context.contentResolver, fixture.tree)
            val recovered = gateway.revalidateUnknown(command.exportAttemptId)

            assertEquals("SUCCEEDED", recovered.state)
            assertEquals("SUCCEEDED", fixture.storage.externalExportPackage(exportId)?.state)
            assertTrue(fixture.storage.exportAttempts(exportId).all { it.state == "SUCCEEDED" })
            assertEquals(1, fixture.storage.recentDocuments().single().pageCount)
            assertEquals("Revalidation must not create, replace, or remove a provider document", beforeRecoveryUris,
                childUris(fixture.context.contentResolver, fixture.tree))
            val afterBytes = requireNotNull(fixture.context.contentResolver.openInputStream(sideEffect)).use { it.readBytes() }
            assertEquals(sha256(providerBytesBeforeRecovery), sha256(afterBytes))
            assertTrue("Confirmed whole package consumes its frozen Page", fixture.storage.workspaceProjection().pages.isEmpty())
            android.util.Log.i(TAG, "RECOVERY_PASS outcome=SUCCEEDED outputs=2 second_write=false page_consumed=true")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            runCatching { cleanupRecoveryFixture(fixture, created) }.onFailure {
                if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it)
            }
        }
        primaryFailure?.let { throw AssertionError("Positive UnknownOutcome recovery or cleanup failed", it) }
    }

    @Test
    fun absentOrMismatchingUnknownOutputStaysUnresolvedWithoutProviderWrites() {
        val fixture = newRecoveryFixture()
        val created = mutableListOf<Uri>()
        var primaryFailure: Throwable? = null
        try {
            val exportId = composeAndCommit(
                fixture.storage, fixture.basename, fixture.tree.toString(), fixture.qualificationId,
                fixture.adapterId, fixture.providerVersion, fixture.osVersion, fixture.accessClass,
            )
            val exportPackage = requireNotNull(fixture.storage.externalExportPackage(exportId))
            val pdfMapping = exportPackage.outputs.first()
            val pdf = fixture.storage.committedExportOutputs(exportId).single { it.mimeType == "application/pdf" }
            val command = recoveryAttempt(fixture, exportId, pdfMapping, pdf)
            assertFalse(childNames(fixture.context.contentResolver, fixture.tree).any { it == command.plannedDisplayName })
            fixture.storage.beginExportAttempt(command)
            fixture.storage.markWritingExportAttemptUnknown(
                command.exportAttemptId, "simulated lost acknowledgement; output initially absent",
                recoveryObservation("recovery-absent-${command.exportAttemptId.value}", "no result observed"),
            )
            val gateway = SafExportGateway(fixture.context, fixture.storage)
            val beforeAbsentCheck = childUris(fixture.context.contentResolver, fixture.tree)
            val absent = gateway.revalidateUnknown(command.exportAttemptId)
            assertEquals("UNKNOWN_OUTCOME", absent.state)
            assertEquals("Absence must not trigger a create or retry", beforeAbsentCheck,
                childUris(fixture.context.contentResolver, fixture.tree))

            val parent = DocumentsContract.buildDocumentUriUsingTree(
                fixture.tree, DocumentsContract.getTreeDocumentId(fixture.tree),
            )
            val wrongBytes = byteArrayOf(0x13, 0x37, 0x42, 0x55)
            val wrongDocument = requireNotNull(
                DocumentsContract.createDocument(fixture.context.contentResolver, parent, command.mimeType, command.plannedDisplayName),
            )
            created += wrongDocument
            requireNotNull(fixture.context.contentResolver.openOutputStream(wrongDocument, "w")).use { it.write(wrongBytes) }
            val wrongBefore = requireNotNull(fixture.context.contentResolver.openInputStream(wrongDocument)).use { it.readBytes() }
            assertEquals(wrongBytes.toList(), wrongBefore.toList())
            val identitiesBeforeMismatch = childUris(fixture.context.contentResolver, fixture.tree)

            val mismatch = gateway.revalidateUnknown(command.exportAttemptId)

            assertEquals("UNKNOWN_OUTCOME", mismatch.state)
            assertEquals("UNKNOWN_OUTCOME", fixture.storage.externalExportPackage(exportId)?.state)
            assertTrue("Page source remains protected while output is uncertain", fixture.storage.workspaceProjection().pages.isNotEmpty())
            assertEquals("Wrong output must not be overwritten and no retry document may appear", identitiesBeforeMismatch,
                childUris(fixture.context.contentResolver, fixture.tree))
            val wrongAfter = requireNotNull(fixture.context.contentResolver.openInputStream(wrongDocument)).use { it.readBytes() }
            assertEquals(sha256(wrongBefore), sha256(wrongAfter))
            android.util.Log.i(TAG, "RECOVERY_UNRESOLVED absent_kept_unknown=true mismatching_hash_kept_unknown=true retry=false")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            runCatching { cleanupRecoveryFixture(fixture, created) }.onFailure {
                if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it)
            }
        }
        primaryFailure?.let { throw AssertionError("Negative UnknownOutcome recovery or cleanup failed", it) }
    }

    @Test
    fun revokedPersistedGrantBlocksNewExportAndKeepsUnknownSourceProtected() {
        val fixture = newRecoveryFixture()
        val created = mutableListOf<Uri>()
        var primaryFailure: Throwable? = null
        try {
            assertFalse("Never revoke a tree permission that predated this test", fixture.tree in fixture.priorPermissions)
            val exportId = composeAndCommit(
                fixture.storage, fixture.basename, fixture.tree.toString(), fixture.qualificationId,
                fixture.adapterId, fixture.providerVersion, fixture.osVersion, fixture.accessClass,
            )
            val exportPackage = requireNotNull(fixture.storage.externalExportPackage(exportId))
            val outputs = fixture.storage.committedExportOutputs(exportId).associateBy { it.artifactId }
            val pdfMapping = exportPackage.outputs.single { requireNotNull(outputs[it.outputArtifactId]).mimeType == "application/pdf" }
            val pdf = requireNotNull(outputs[pdfMapping.outputArtifactId])
            val unresolved = recoveryAttempt(fixture, exportId, pdfMapping, pdf)
            fixture.storage.beginExportAttempt(unresolved)
            fixture.storage.markWritingExportAttemptUnknown(
                unresolved.exportAttemptId, "simulated ambiguous output before grant revocation",
                recoveryObservation("revoke-unknown-${unresolved.exportAttemptId.value}", "provider outcome unresolved"),
            )
            val baselineProviderUris = childUris(fixture.context.contentResolver, fixture.tree)
            assertFalse("Random test output prefix must have no provider children", childNames(fixture.context.contentResolver, fixture.tree).any { it.startsWith(fixture.basename) })

            SafDestinationGrantManager(fixture.context).releasePersistedGrant(fixture.tree.toString())
            assertFalse(
                "OS persisted read/write grant must be absent after revocation",
                fixture.context.contentResolver.persistedUriPermissions.any { it.uri == fixture.tree && it.isReadPermission && it.isWritePermission },
            )

            val gateway = SafExportGateway(fixture.context, fixture.storage)
            val afterRevokeRecovery = gateway.revalidateUnknown(unresolved.exportAttemptId)
            assertEquals("UNKNOWN_OUTCOME", afterRevokeRecovery.state)
            assertEquals("UNKNOWN_OUTCOME", fixture.storage.externalExportPackage(exportId)?.state)
            assertTrue("Unresolved output retains its source Page", fixture.storage.workspaceProjection().pages.single().selected)

            val jpegMapping = exportPackage.outputs.single { requireNotNull(outputs[it.outputArtifactId]).mimeType != "application/pdf" }
            val jpeg = requireNotNull(outputs[jpegMapping.outputArtifactId])
            val blockedWrite = recoveryAttempt(fixture, exportId, jpegMapping, jpeg)
            val exportFailure = runCatching { gateway.export(blockedWrite) }.exceptionOrNull()
            assertNotNull("Revoked grant must stop a new export before any provider access", exportFailure)
            assertTrue(exportFailure!!.message.orEmpty().contains("readable and writable"))
            assertEquals("Only the pre-existing unknown attempt may be journaled", 1, fixture.storage.exportAttempts(exportId).size)
            assertEquals("UNKNOWN_OUTCOME", fixture.storage.exportAttempt(unresolved.exportAttemptId)?.state)
            assertEquals("No ExportAttempt may be started for the blocked new output", null, fixture.storage.exportAttempt(blockedWrite.exportAttemptId))
            assertTrue("Page remains visible and selected", fixture.storage.workspaceProjection().pages.single().selected)
            assertEquals("Package source remains unchanged while output is unresolved", "UNKNOWN_OUTCOME", fixture.storage.externalExportPackage(exportId)?.state)
            android.util.Log.i(TAG, "REVOKED_PASS persisted_grant_absent=true existing_unknown_retained=true new_attempt_journaled=false source_retained=true provider_calls_after_revoke=0 baseline_children=${baselineProviderUris.size}")
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            runCatching { cleanupRecoveryFixture(fixture, created, providerGrantAvailable = false) }.onFailure {
                if (primaryFailure == null) primaryFailure = it else primaryFailure!!.addSuppressed(it)
            }
        }
        primaryFailure?.let { throw AssertionError("Revoked persisted grant or cleanup failed", it) }
    }

    private data class RecoveryFixture(
        val context: android.content.Context,
        val app: DocScanApplication,
        val storage: ProductStorage,
        val databaseName: String,
        val artifactDirectory: String,
        val candidateDirectory: String,
        val priorPermissions: Set<Uri>,
        val tree: Uri,
        val basename: String,
        val qualificationId: String,
        val adapterId: String,
        val providerVersion: String,
        val osVersion: String,
        val accessClass: String,
        val fixtureSuffix: String,
    )

    private fun newRecoveryFixture(suffix: String = UUID.randomUUID().toString()): RecoveryFixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as DocScanApplication
        val databaseName = "saf-recovery-$suffix.db"
        val artifactDirectory = "saf-recovery-artifacts-$suffix"
        val candidateDirectory = "saf-recovery-candidates-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory, candidateDirectory)
        val priorPermissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
        var scenario: ActivityScenario<ProductActivity>? = null
        var acquiredTree: Uri? = null
        try {
            app.productStorageOverrideForTest = storage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            await("recovery fixture ProductActivity ready") {
                var ready = false
                scenario!!.onActivity { ready = it.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")?.isEnabled == true }
                ready
            }
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<android.view.View>("docscan-destination-button")!!.performClick()
            }
            selectDocuments(UiDevice.getInstance(instrumentation))
            val grant = awaitValue("recovery fixture real picker grant") { storage.destinationGrant() }
            val tree = Uri.parse(grant.treeUri)
            acquiredTree = tree
            assertEquals("primary:Documents", DocumentsContract.getTreeDocumentId(tree))
            scenario.close()
            scenario = null

            val authority = requireNotNull(tree.authority)
            val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(provider.packageName, 0)
            val providerVersion = "${info.versionName.orEmpty()}#${info.longVersionCode}"
            val adapterId = "${ProductActivity.SAF_ADAPTER_ID}/${provider.packageName}/$authority"
            val accessClass = ProductActivity.SAF_ACCESS_CLASS
            val qualificationId = "test-only-$suffix"
            storage.recordProviderQualification(
                ProviderQualificationCommand(
                    qualificationId, adapterId, providerVersion, Build.FINGERPRINT, accessClass,
                    "TEST_FIXTURE_ONLY_NOT_QUALIFICATION",
                    """{"createDoesNotOverwriteExistingDocument":true,"enumerateChildren":true,"readBackSha256":true,"fixtureOnly":true}""",
                ),
            )
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawText("RECOVERY TEST", 20f, 100f, Paint().apply { color = Color.BLACK; textSize = 24f })
            }
            val jpeg = ByteArrayOutputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)); stream.toByteArray()
            }
            bitmap.recycle()
            val imported = storage.importImage(jpeg.inputStream(), "image/jpeg", "recovery-fixture.jpg")
            storage.setSelected(imported.pageId, true)
            val basename = "docscan-recovery-$suffix"
            assertFalse("Random recovery basename must not exist before the test", childNames(context.contentResolver, tree).any { it.startsWith(basename) })
            return RecoveryFixture(
                context, app, storage, databaseName, artifactDirectory, candidateDirectory,
                priorPermissions, tree, basename, qualificationId, adapterId, providerVersion,
                Build.FINGERPRINT, accessClass, suffix,
            )
        } catch (failure: Throwable) {
            scenario?.close()
            app.productStorageOverrideForTest = null
            acquiredTree?.takeIf { it !in priorPermissions }?.let { tree ->
                runCatching { SafDestinationGrantManager(context).releasePersistedGrant(tree.toString()) }
                    .onFailure(failure::addSuppressed)
            }
            runCatching { storage.close() }
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            context.noBackupFilesDir.resolve(candidateDirectory).deleteRecursively()
            throw failure
        }
    }

    private fun recoveryAttempt(
        fixture: RecoveryFixture,
        exportId: app.docscan.core.model.ExportArtifactId,
        mapping: app.docscan.core.store.ExternalExportPackageOutputCommand,
        output: app.docscan.core.store.CommittedExportOutput,
    ) = BeginExportAttemptCommand(
        ExportAttemptId.new(), exportId, output.artifactId, fixture.tree.toString(), mapping.displayName,
        output.mimeType, fixture.qualificationId, fixture.adapterId, fixture.providerVersion,
        fixture.osVersion, fixture.accessClass,
    )

    private fun recoveryObservation(id: String, existence: String) = ProviderOperationObservationCommand(
        observationId = id,
        identityEvidence = null,
        existenceEvidence = existence,
        integrityEvidence = null,
        readabilityEvidence = null,
        nameEvidence = null,
        accessEvidence = "persisted test tree grant held",
    )

    private fun cleanupRecoveryFixture(
        fixture: RecoveryFixture,
        createdUris: List<Uri>,
        providerGrantAvailable: Boolean = true,
    ) {
        if (providerGrantAvailable) {
            val namespaceDocs = childDocuments(fixture.context.contentResolver, fixture.tree)
                .filter { it.name.startsWith(fixture.basename) }
            (createdUris + namespaceDocs.map { it.uri }).distinct().forEach { uri ->
                if (!runCatching { DocumentsContract.deleteDocument(fixture.context.contentResolver, uri) }.getOrDefault(false)) {
                    throw IllegalStateException("Could not delete test-owned recovery document $uri")
                }
            }
            check(childDocuments(fixture.context.contentResolver, fixture.tree).none { it.name.startsWith(fixture.basename) }) {
                "Recovery test documents remain after cleanup"
            }
        } else {
            check(createdUris.isEmpty()) { "Revoked-grant scenario unexpectedly created provider documents: $createdUris" }
        }
        if (fixture.tree !in fixture.priorPermissions) {
            SafDestinationGrantManager(fixture.context).releasePersistedGrant(fixture.tree.toString())
        }
        fixture.app.productStorageOverrideForTest = null
        fixture.storage.close()
        fixture.context.deleteDatabase(fixture.databaseName)
        fixture.context.noBackupFilesDir.resolve(fixture.artifactDirectory).deleteRecursively()
        fixture.context.noBackupFilesDir.resolve(fixture.candidateDirectory).deleteRecursively()
        recoveryDeathFixtureRoot(fixture.context, fixture.fixtureSuffix).deleteRecursively()
        android.util.Log.i(TAG, "RECOVERY_CLEANUP test_docs_removed=true grant_released=${fixture.tree !in fixture.priorPermissions}")
    }

    private fun composeAndCommit(
        storage: ProductStorage, basename: String, tree: String, qualificationId: String,
        adapter: String, providerVersion: String, os: String, access: String,
    ): app.docscan.core.model.ExportArtifactId {
        val frozen = storage.freezeSelectedPagesForSave()
        val output = storage.composeFrozenSaveOutputSet(frozen, PdfOutputSettings(595f, 842f, 0.8f, 150), 82)
        val names = listOf("$basename.pdf", "$basename.jpg")
        return storage.commitLocalExportOutputSet(
            frozen, output,
            ExternalExportPackagePlan(tree, basename, qualificationId, adapter, providerVersion, os, access, names, "NONE"),
        )
    }

    private fun selectDocuments(device: UiDevice) {
        val timeout = 15_000L
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), timeout))
        val useFolder = By.text(Pattern.compile("(?i)^use this folder$"))
        val allow = By.text(Pattern.compile("(?i)^allow$"))
        SafPickerTestActions.openPrimaryDirectory(device, listOf("Documents"))
        SafPickerTestActions.clickFresh(
            device,
            useFolder,
            "Use Documents folder",
            timeoutMs = timeout,
            completed = { device.hasObject(allow) || device.hasObject(By.pkg("app.docscan.scanner")) },
        )
        if (device.wait(Until.hasObject(allow), 3_000L)) {
            SafPickerTestActions.clickFresh(
                device,
                allow,
                "Allow Documents access",
                timeoutMs = 3_000L,
                completed = { device.hasObject(By.pkg("app.docscan.scanner")) },
            )
        }
        assertTrue(device.wait(Until.hasObject(By.pkg("app.docscan.scanner")), timeout))
    }

    private fun childUris(resolver: android.content.ContentResolver, tree: Uri): Set<Uri> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val result = mutableSetOf<Uri>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) result += DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(column))
        } ?: error("No child query result")
        return result
    }

    private fun childNames(resolver: android.content.ContentResolver, tree: Uri): Set<String> {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val result = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) result += cursor.getString(column)
        } ?: error("No child query result")
        return result
    }

    private fun countRows(databaseFile: java.io.File, table: String): Int =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            databaseFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { database ->
            database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    private data class PreviewActionTarget(val label: String?, val widthPx: Int, val heightPx: Int)

    private fun assertSavedPdfPreviewAccessibility(context: android.content.Context) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.uiAutomation.rootInActiveWindow
            ?: throw AssertionError("Saved-PDF preview accessibility window is unavailable")
        val targets = mutableListOf<PreviewActionTarget>()
        collectPreviewActionTargets(root, targets)
        val inventory = targets.joinToString("\n") { target ->
            "label=${target.label ?: "<missing>"} bounds=${target.widthPx}x${target.heightPx}"
        }
        val expectedLabels = listOf(
            context.getString(R.string.viewer_previous),
            context.getString(R.string.viewer_next),
            context.getString(android.R.string.cancel),
            context.getString(R.string.page_image_description, 1),
            context.getString(R.string.page_image_description, 2),
        ).map(String::trim).map(String::lowercase)
        val labels = targets.mapNotNull { it.label?.trim()?.lowercase() }
        android.util.Log.i("DocScanA11y", "Saved-PDF preview actions: density=${context.resources.displayMetrics.density} $inventory")
        assertEquals("Preview exposes Previous, Next, Cancel and both Page thumbnails exactly once:\n$inventory", 5, targets.size)
        assertEquals("Every preview action has a distinct accessible name:\n$inventory", 5, labels.toSet().size)
        expectedLabels.forEach { expected ->
            assertTrue("Preview dialog is missing accessible action '$expected':\n$inventory", expected in labels)
        }
        val minimumSizePx = kotlin.math.ceil(48f * context.resources.displayMetrics.density).toInt()
        val undersized = targets.filter { it.widthPx < minimumSizePx || it.heightPx < minimumSizePx }
        assertTrue("Every preview action must meet 48dp (${minimumSizePx}px):\n$inventory", undersized.isEmpty())

        val previewDescription = context.getString(R.string.saved_pdf_preview_description)
        assertNotNull(
            "Rendered PDF page exposes its localized image description '$previewDescription'",
            findAccessibilityNode(root, previewDescription, compareContentDescription = true),
        )
        val pageCounter = context.getString(R.string.saved_pdf_page, 1, 2)
        assertNotNull(
            "Rendered page counter is present in the accessibility tree: $pageCounter",
            findAccessibilityNode(root, pageCounter, compareContentDescription = false),
        )
    }

    private fun assertRecentDialogAccessibility(
        context: android.content.Context,
        dialogName: String,
        expectedLabels: List<String>,
    ) {
        val expected = expectedLabels.map { it.trim().lowercase() }
        var root: AccessibilityNodeInfo? = null
        await("$dialogName accessibility rows are published") {
            val candidate = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return@await false
            val visibleTargets = mutableListOf<PreviewActionTarget>()
            collectPreviewActionTargets(candidate, visibleTargets)
            val visibleLabels = visibleTargets.mapNotNull { it.label?.trim()?.lowercase() }
            if (expected.all { it in visibleLabels }) {
                root = candidate
                true
            } else false
        }
        val activeRoot = checkNotNull(root) { "$dialogName accessibility window is unavailable" }
        val targets = mutableListOf<PreviewActionTarget>()
        collectPreviewActionTargets(activeRoot, targets)
        val inventory = targets.joinToString("\n") { target ->
            "label=${target.label ?: "<missing>"} bounds=${target.widthPx}x${target.heightPx}"
        }
        val actualLabels = targets.mapNotNull { it.label?.trim()?.lowercase() }
        android.util.Log.i(
            "DocScanA11y",
            "$dialogName accessibility inventory: density=${context.resources.displayMetrics.density} $inventory",
        )
        assertEquals("$dialogName must expose the expected actionable rows:\n$inventory", expected.size, targets.size)
        assertEquals("$dialogName actions must have distinct accessible names:\n$inventory", expected.size, actualLabels.toSet().size)
        expected.forEach { label ->
            assertTrue("$dialogName is missing accessible action '$label':\n$inventory", label in actualLabels)
        }
        val minimumSizePx = kotlin.math.ceil(48f * context.resources.displayMetrics.density).toInt()
        val undersized = targets.filter { it.widthPx < minimumSizePx || it.heightPx < minimumSizePx }
        assertTrue("Every $dialogName action must meet 48dp (${minimumSizePx}px):\n$inventory", undersized.isEmpty())
    }

    private fun collectPreviewActionTargets(node: AccessibilityNodeInfo, targets: MutableList<PreviewActionTarget>) {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        val isListContainer = node.className?.toString()?.endsWith("ListView") == true
        if (node.isVisibleToUser && node.isClickable && !isListContainer && bounds.width() > 0 && bounds.height() > 0) {
            val label = node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
                ?: node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)
            targets += PreviewActionTarget(label, bounds.width(), bounds.height())
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectPreviewActionTargets(child, targets)
        }
    }

    private fun findAccessibilityNode(
        node: AccessibilityNodeInfo,
        expected: String,
        compareContentDescription: Boolean,
    ): AccessibilityNodeInfo? {
        val actual = if (compareContentDescription) node.contentDescription?.toString() else node.text?.toString()
        if (actual == expected) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findAccessibilityNode(child, expected, compareContentDescription)?.let { return it }
        }
        return null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(50) }
        throw AssertionError(message)
    }

    private fun <T> awaitValue(message: String, value: () -> T?): T {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val result = value()
            if (result != null) return result
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object { const val TAG = "SafGatewayIntegration" }
}
