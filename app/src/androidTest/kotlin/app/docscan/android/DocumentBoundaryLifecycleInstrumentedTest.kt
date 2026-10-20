package app.docscan.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Activity destroy path while its serialized pipeline lazy initializer is in flight. */
@RunWith(AndroidJUnit4::class)
class DocumentBoundaryLifecycleInstrumentedTest {
    @Test
    fun delayedStartupUiCallbackDoesNotSubmitDestinationWorkAfterDestroy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as DocScanApplication
        val hadCameraPermission = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val suffix = System.nanoTime().toString()
        val databaseName = "dbq-startup-lifecycle-$suffix.db"
        val artifactDirectory = "dbq-startup-lifecycle-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        val previousOverride = app.productStorageOverrideForTest
        val previousProvider = app.productStorageProviderForTest
        val providerEntered = CountDownLatch(1)
        val releaseProvider = CountDownLatch(1)
        val postDestroyIo = RecordingExecutorService()
        var scenario: ActivityScenario<ProductActivity>? = null
        var originalIo: ExecutorService? = null

        try {
            app.productStorageOverrideForTest = null
            app.productStorageProviderForTest = {
                providerEntered.countDown()
                check(releaseProvider.await(10, TimeUnit.SECONDS)) { "Test did not release startup storage provider" }
                storage
            }
            if (hadCameraPermission) {
                instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            }

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            assertTrue("Startup reaches the controlled storage provider", providerEntered.await(10, TimeUnit.SECONDS))
            val activeScenario = requireNotNull(scenario)
            activeScenario.onActivity { activity ->
                originalIo = reflectedField(activity, "io") as ExecutorService
                // The startup provider is holding the original executor's only task. Replace the
                // Activity's destination-work executor with a recorder before destroying it.
                ProductActivity::class.java.getDeclaredField("io").apply { isAccessible = true }
                    .set(activity, postDestroyIo)
            }

            activeScenario.close()
            scenario = null
            assertTrue("onDestroy shuts down the Activity-owned replacement executor", postDestroyIo.isShutdown)
            requireNotNull(originalIo).shutdown()
            releaseProvider.countDown()
            assertTrue("The held startup I/O task drains after provider release",
                requireNotNull(originalIo).awaitTermination(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            assertEquals("A destroyed Activity must not submit SAF grant work from delayed startup UI", 0,
                postDestroyIo.executeCount.get())
            assertTrue("Camera permission remains revoked; delayed startup did not launch the permission flow",
                context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        } finally {
            releaseProvider.countDown()
            runCatching { scenario?.close() }
            originalIo?.shutdownNow()
            app.productStorageProviderForTest = previousProvider
            app.productStorageOverrideForTest = previousOverride
            storage.close()
            context.deleteDatabase(databaseName)
            context.noBackupFilesDir.resolve(artifactDirectory).deleteRecursively()
            if (hadCameraPermission) {
                runCatching {
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
                }
            }
        }
    }

    @Test
    fun destroyQueuesPipelineCloseBehindAnInFlightLazyInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hadCameraPermission = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val suffix = System.nanoTime().toString()
        val trace = File(context.cacheDir, "dbq-lifecycle-$suffix/trace.jsonl")
        val archiveDirectory = File(context.cacheDir, "dbq-lifecycle-$suffix/frames")
        val initializerEntered = CountDownLatch(1)
        val releaseInitializer = CountDownLatch(1)
        val pipelineRef = AtomicReference<DocumentBoundaryPipeline?>()
        val diagnosticsRef = AtomicReference<DocumentBoundaryDiagnostics?>()
        val archiveRef = AtomicReference<DocumentFrameArchive?>()
        var scenario: ActivityScenario<ProductActivity>? = null
        var executor: ExecutorService? = null
        var blockedDelegateRef: Lazy<DocumentBoundaryPipeline>? = null
        var initFuture: FutureTask<DocumentBoundaryPipeline>? = null
        var closeFuture: FutureTask<Unit>? = null

        try {
            if (hadCameraPermission) {
                instrumentation.uiAutomation.revokeRuntimePermission(context.packageName, Manifest.permission.CAMERA)
            }
            val launched = ActivityScenario.launch(ProductActivity::class.java)
            scenario = launched
            launched.onActivity { activity ->
                executor = reflectedField(activity, "cameraAnalysisExecutor") as ExecutorService
                val blockedDelegate = lazy {
                    initializerEntered.countDown()
                    check(releaseInitializer.await(10, TimeUnit.SECONDS)) {
                        "Test did not release the blocked pipeline initializer"
                    }
                    val diagnostics = DocumentBoundaryDiagnostics(trace).also(diagnosticsRef::set)
                    val archive = DocumentFrameArchive(archiveDirectory).also(archiveRef::set)
                    DocumentBoundaryPipeline(
                        coordinator = DocumentLiveDetectionCoordinator(),
                        detector = ClassicalDocumentBoundaryDetector(),
                        trackingEnabled = false,
                        diagnostics = diagnostics,
                        frameArchive = archive,
                    ).also(pipelineRef::set)
                }
                blockedDelegateRef = blockedDelegate
                // The test owns this Activity instance and replaces only its private lazy delegate.
                val delegateField = ProductActivity::class.java
                    .getDeclaredField("documentBoundaryPipelineDelegate").apply { isAccessible = true }
                delegateField.set(activity, blockedDelegate)
            }

            // Let ProductActivity's startup I/O callback enqueue and finish its destination-status
            // refresh before destroy shuts down the same executor. Otherwise the lifecycle test
            // can race an unrelated onCreate callback and crash before reaching cleanup assertions.
            instrumentation.waitForIdleSync()
            awaitActivityStartup(launched)

            launched.onActivity {
                val blockedDelegate = requireNotNull(blockedDelegateRef)
                initFuture = FutureTask { blockedDelegate.value }
                requireNotNull(executor).execute(requireNotNull(initFuture))
            }

            assertTrue("Initializer should be blocked on cameraAnalysisExecutor",
                initializerEntered.await(10, TimeUnit.SECONDS))
            assertTrue("Delegate must remain uninitialized while initializer is blocked",
                scenarioDelegate(launched).isInitialized().not())

            // Run the real destroy path independently so the test can release the initializer
            // after onDestroy has made its cleanup decision and shut down the analysis executor.
            // ActivityScenario.close itself waits for lifecycle completion, so calling it inline
            // would prevent the test thread from releasing the latch.
            val closing = FutureTask { launched.close() }
            closeFuture = closing
            Thread(closing, "dbq-lifecycle-activity-close").start()
            val analysisExecutor = requireNotNull(executor)
            awaitShutdownDecision(analysisExecutor)
            releaseInitializer.countDown()
            closing.get(10, TimeUnit.SECONDS)
            scenario = null

            val pipeline = requireNotNull(initFuture).get(10, TimeUnit.SECONDS)
            assertNotNull(pipelineRef.get())
            assertTrue("Analysis executor should drain the queued cleanup after initialization",
                analysisExecutor.awaitTermination(10, TimeUnit.SECONDS))

            val diagnostics = requireNotNull(diagnosticsRef.get())
            val archive = requireNotNull(archiveRef.get())
            assertTrue("Diagnostics writer must terminate after pipeline close",
                writerOf(diagnostics).awaitTermination(10, TimeUnit.SECONDS))
            assertTrue("Frame archive writer must terminate after pipeline close",
                writerOf(archive).awaitTermination(10, TimeUnit.SECONDS))
            assertTrue("Diagnostics must emit its close/end marker", trace.isFile &&
                trace.readLines().any { it.contains("\"event\":\"end\"") })
            assertTrue("Archive must publish its final summary after close",
                File(archiveDirectory, "recording-summary.json").isFile)
            assertFalse("The test does not create a camera stream", archiveDirectory.listFiles()
                ?.any { it.extension == "jpg" } == true)
        } finally {
            releaseInitializer.countDown()
            closeFuture?.let { runCatching { it.get(10, TimeUnit.SECONDS) } }
            runCatching { scenario?.close() }
            initFuture?.let { runCatching { it.get(10, TimeUnit.SECONDS) } }
            pipelineRef.get()?.let { runCatching { it.close() } }
            diagnosticsRef.get()?.let { runCatching { it.close() } }
            archiveRef.get()?.let { runCatching { it.close() } }
            diagnosticsRef.get()?.let { runCatching { writerOf(it).awaitTermination(10, TimeUnit.SECONDS) } }
            archiveRef.get()?.let { runCatching { writerOf(it).awaitTermination(10, TimeUnit.SECONDS) } }
            executor?.let {
                it.shutdown()
                runCatching { it.awaitTermination(10, TimeUnit.SECONDS) }
            }
            if (hadCameraPermission) {
                runCatching {
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
                }
            }
            trace.parentFile?.deleteRecursively()
        }
    }

    private fun scenarioDelegate(scenario: ActivityScenario<ProductActivity>): Lazy<*> {
        var delegate: Lazy<*>? = null
        scenario.onActivity { activity -> delegate = reflectedField(activity, "documentBoundaryPipelineDelegate") as Lazy<*> }
        return requireNotNull(delegate)
    }

    private fun reflectedField(instance: Any, name: String): Any? {
        val field: Field = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(instance)
    }

    private fun writerOf(owner: Any): ExecutorService = reflectedField(owner, "writer") as ExecutorService

    private class RecordingExecutorService : AbstractExecutorService() {
        val executeCount = AtomicInteger()
        @Volatile private var stopped = false

        override fun execute(command: Runnable) {
            executeCount.incrementAndGet()
            command.run()
        }

        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = stopped
    }

    private fun awaitShutdownDecision(executor: ExecutorService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!executor.isShutdown && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Activity onDestroy should reach analysis executor shutdown", executor.isShutdown)
    }

    private fun awaitActivityStartup(scenario: ActivityScenario<ProductActivity>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var startupComplete = false
        while (!startupComplete && System.nanoTime() < deadline) {
            scenario.onActivity { activity ->
                startupComplete = reflectedField(activity, "destinationButton")
                    ?.let { (it as android.widget.Button).isEnabled } == true &&
                    !activity.isFinishing && !activity.isDestroyed
            }
            if (!startupComplete) Thread.sleep(20)
        }
        assertTrue("Activity startup destination status should finish before the lifecycle race", startupComplete)
    }
}
