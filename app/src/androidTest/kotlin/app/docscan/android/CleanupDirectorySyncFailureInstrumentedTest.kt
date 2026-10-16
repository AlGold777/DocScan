package app.docscan.android

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.artifact.FileArtifactRepository
import app.docscan.core.cleanup.CleanupArbiter
import app.docscan.core.db.DocScanDatabase
import app.docscan.core.model.WorkspaceId
import app.docscan.core.model.WorkspaceSessionId
import app.docscan.core.store.StateStore
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class CleanupDirectorySyncFailureInstrumentedTest {
    @Test
    fun postUnlinkDirectorySyncFailurePropagatesAndRecoveryConverges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "cleanup-sync-$suffix.db"
        val root = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "cleanup-sync-$suffix-")
        val armed = AtomicBoolean(false)
        val injected = AtomicBoolean(false)
        val sync = DirectorySync { directory ->
            if (armed.get() && injected.compareAndSet(false, true)) {
                throw IOException("injected Android post-unlink directory fsync failure")
            }
            AndroidDirectorySync().sync(directory)
        }
        val repository = FileArtifactRepository(
            privateRoot = root,
            directorySync = sync,
            producerEpoch = "cleanup-sync-$suffix",
            atomicPublisher = AndroidAtomicArtifactPublisher(),
        )
        val driver = AndroidSqliteDriver(
            schema = DocScanDatabase.Schema,
            context = context,
            name = databaseName,
            callback = object : AndroidSqliteDriver.Callback(DocScanDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                    check(db.enableWriteAheadLogging()) { "SQLite WAL mode is required" }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                    db.query("PRAGMA synchronous").use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 2) {
                            "SQLite synchronous=FULL is required for cleanup recovery fixture"
                        }
                    }
                }
            },
        )
        val database = DocScanDatabase(driver)
        val store = StateStore(database, AndroidClock())
        try {
            store.createInitialWorkspace(WorkspaceId.new(), WorkspaceSessionId.new())
            val failedArtifact = repository.publishJpeg(ByteArrayInputStream(byteArrayOf(11, 22, 33, 44)))
            store.recordStagedDurableArtifact(failedArtifact)
            assertEquals(1L, database.schemaQueries.commitArtifact(failedArtifact.artifactId.value).value)
            assertTrue(repository.verifyPublished(failedArtifact.relativePath, failedArtifact.sizeBytes, failedArtifact.sha256))

            armed.set(true)
            val failure = runCatching { CleanupArbiter(store, repository).runBatch(1L) }.exceptionOrNull()
            assertTrue("Injected directory sync failure must escape CleanupArbiter", failure is IOException)
            assertTrue("The adapter must record that its post-unlink failure ran", injected.get())
            assertTrue("Failure diagnostic must identify the injected sync boundary",
                failure?.message.orEmpty().contains("post-unlink directory fsync"))
            assertFalse("Unlink happened before the injected directory sync", Files.exists(root.resolve(failedArtifact.relativePath)))
            val claimedAfterFailure = database.schemaQueries.artifactById(failedArtifact.artifactId.value).executeAsOne()
            assertEquals("GC_CLAIMED", claimedAfterFailure.state)
            assertEquals("PRESENT", claimedAfterFailure.storage_state)
            assertEquals(0L, database.schemaQueries.referenceCountForArtifact(failedArtifact.artifactId.value).executeAsOne())

            armed.set(false)
            assertEquals(1, CleanupArbiter(store, repository).recoverInterruptedDeletions(1L))
            val recovered = database.schemaQueries.artifactById(failedArtifact.artifactId.value).executeAsOne()
            assertEquals("DELETED", recovered.storage_state)
            assertEquals("COMMITTED", recovered.state)
            assertFalse(Files.exists(root.resolve(failedArtifact.relativePath)))
            assertEquals(0L, database.schemaQueries.referenceCountForArtifact(failedArtifact.artifactId.value).executeAsOne())

            val positiveControl = repository.publishJpeg(ByteArrayInputStream(byteArrayOf(55, 66, 77, 88)))
            store.recordStagedDurableArtifact(positiveControl)
            assertEquals(1L, database.schemaQueries.commitArtifact(positiveControl.artifactId.value).value)
            val normalResult = CleanupArbiter(store, repository).runBatch(1L)
            assertEquals(1, normalResult.claimedArtifacts)
            assertEquals(1, normalResult.deletedArtifacts)
            assertEquals(0, normalResult.quarantinedArtifacts)
            assertEquals("DELETED", database.schemaQueries.artifactById(positiveControl.artifactId.value).executeAsOne().storage_state)
            assertFalse(Files.exists(root.resolve(positiveControl.relativePath)))
        } finally {
            driver.close()
            context.deleteDatabase(databaseName)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
