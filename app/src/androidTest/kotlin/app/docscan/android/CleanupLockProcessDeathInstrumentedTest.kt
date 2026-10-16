package app.docscan.android

import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.artifact.FileArtifactRepository
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupLockProcessDeathInstrumentedTest {
    @Test
    fun androidProcessDeathReleasesCleanupLockForWaitingWorker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext: Context = instrumentation.targetContext
        val testCache = targetContext.cacheDir.toPath()
        Files.createDirectories(testCache)
        val root = Files.createTempDirectory(testCache, "cleanup-lock-death-")
        val helperIntent = Intent(targetContext, CleanupLockHolderService::class.java)
            .putExtra(CleanupLockHolderService.EXTRA_ROOT, root.toString())
        val helperComponent = targetContext.startService(helperIntent)
        assertTrue("Test lock-holder service did not start", helperComponent != null)

        val ready = root.resolve(CleanupLockHolderService.OWNER_READY_FILE)
        val helperPidFile = root.resolve(CleanupLockHolderService.OWNER_PID_FILE)
        val error = root.resolve(CleanupLockHolderService.OWNER_ERROR_FILE)
        var helperPid = -1
        val executor = Executors.newSingleThreadExecutor()
        try {
            val deadline = SystemClock.elapsedRealtime() + 8_000L
            while (!Files.exists(ready) && !Files.exists(error) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25L)
            }
            assertFalse("Lock-holder process failed: ${if (Files.exists(error)) error.toFile().readText() else "no ready marker"}", Files.exists(error))
            assertTrue("Lock-holder process never recorded its lock", Files.exists(ready))
            helperPid = helperPidFile.toFile().readText().toInt()
            assertNotEquals("Lock-holder must run in a separate Android process", Process.myPid(), helperPid)
            Log.i(TAG, "helper_pid=$helperPid instrumentation_pid=${Process.myPid()} lock_ready=true")

            val waitingStarted = CountDownLatch(1)
            val secondRoot = FileArtifactRepository(root, DirectorySync {}, "android-cleanup-waiter")
            val waitingWorker = executor.submit {
                waitingStarted.countDown()
                secondRoot.withExclusiveCleanupLock { root.resolve("waiter.acquired").toFile().writeText("acquired") }
            }
            assertTrue("Waiting worker did not start", waitingStarted.await(3, TimeUnit.SECONDS))
            Thread.sleep(250L)
            assertFalse("Second Android process acquired the live lock", waitingWorker.isDone)
            assertFalse("Acquisition marker appeared while helper held lock", Files.exists(root.resolve("waiter.acquired")))

            Process.killProcess(helperPid)
            assertTrue(
                "Waiting worker did not acquire lock after helper process death",
                Files.exists(root.resolve("waiter.acquired")) || runCatching {
                    waitingWorker.get(8, TimeUnit.SECONDS)
                    true
                }.getOrDefault(false),
            )
            waitingWorker.get(8, TimeUnit.SECONDS)
            secondRoot.withExclusiveCleanupLock { root.resolve("reacquired").toFile().writeText("yes") }
            assertTrue("Lock could not be acquired after process death", Files.exists(root.resolve("reacquired")))
            Log.i(TAG, "helper_killed=true waiting_worker_acquired=true lock_reacquired=true")
        } finally {
            if (helperPid > 0) Process.killProcess(helperPid)
            targetContext.stopService(helperIntent)
            executor.shutdownNow()
            runCatching {
                Files.list(root).use { children -> children.forEach(Files::deleteIfExists) }
                Files.deleteIfExists(root)
            }
        }
    }

    companion object {
        private const val TAG = "CleanupLockProcessDeathTest"
    }
}
