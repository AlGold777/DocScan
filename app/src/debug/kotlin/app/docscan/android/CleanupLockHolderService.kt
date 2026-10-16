package app.docscan.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.artifact.FileArtifactRepository
import java.nio.file.Path

/** Test-only process that holds the app-private cleanup lock until the test kills this process. */
class CleanupLockHolderService : Service() {
    @Volatile
    private var holder: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (holder?.isAlive == true) return START_NOT_STICKY
        val rootValue = intent?.getStringExtra(EXTRA_ROOT)
            ?: throw IllegalArgumentException("Missing isolated cleanup root")
        val root = Path.of(rootValue)
        holder = Thread({
            try {
                FileArtifactRepository(root, DirectorySync {}, "android-cleanup-lock-probe")
                    .withExclusiveCleanupLock {
                        root.resolve(OWNER_PID_FILE).toFile().writeText(Process.myPid().toString())
                        root.resolve(OWNER_READY_FILE).toFile().writeText("locked")
                        while (!Thread.currentThread().isInterrupted) Thread.sleep(100)
                    }
            } catch (failure: Throwable) {
                runCatching { root.resolve(OWNER_ERROR_FILE).toFile().writeText(failure.toString()) }
                Log.e(TAG, "Cleanup lock holder exited unexpectedly", failure)
            }
        }, "cleanup-lock-holder").apply {
            isDaemon = false
            start()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_ROOT = "cleanup_lock_root"
        const val OWNER_PID_FILE = "android-holder.pid"
        const val OWNER_READY_FILE = "android-holder.ready"
        const val OWNER_ERROR_FILE = "android-holder.error"
        private const val TAG = "CleanupLockHolderService"
    }
}
