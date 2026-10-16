package app.docscan.android

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.docscan.core.artifact.FileArtifactRepository
import app.docscan.core.cleanup.CleanupArbiter
import app.docscan.core.db.DocScanDatabase
import app.docscan.core.model.WorkspaceId
import app.docscan.core.model.WorkspaceSessionId
import app.docscan.core.store.DurableImageArtifact
import app.docscan.core.store.StateStore
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Comparator
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CleanupCorruptionQuarantineInstrumentedTest {
    @Test
    fun quarantinesCorruptAndroidArtifactsWithoutDeletingTheirBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "cleanup-corrupt-$suffix.db"
        val root = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "cleanup-corrupt-$suffix-")
        val driver = AndroidSqliteDriver(
            schema = DocScanDatabase.Schema,
            context = context,
            name = databaseName,
            callback = object : AndroidSqliteDriver.Callback(DocScanDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                    check(db.enableWriteAheadLogging()) { "SQLite WAL mode is required for cleanup fixture" }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA synchronous=FULL")
                    db.query("PRAGMA synchronous").use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 2) {
                            "SQLite synchronous=FULL is required for cleanup fixture"
                        }
                    }
                }
            },
        )
        val database = DocScanDatabase(driver)
        val store = StateStore(database, AndroidClock())
        val repository = FileArtifactRepository(
            privateRoot = root,
            directorySync = AndroidDirectorySync(),
            producerEpoch = "cleanup-corrupt-$suffix",
            atomicPublisher = AndroidAtomicArtifactPublisher(),
        )
        val arbiter = CleanupArbiter(store, repository)

        try {
            store.createInitialWorkspace(WorkspaceId.new(), WorkspaceSessionId.new())

            val corruptBeforeClaim = publishCommittedOrphan(database, repository, store, byteArrayOf(1, 2, 3, 4))
            val firstCorruption = byteArrayOf(91, 92, 93)
            Files.write(root.resolve(corruptBeforeClaim.relativePath), firstCorruption)
            val firstResult = arbiter.runBatch(1L)
            assertEquals(1, firstResult.claimedArtifacts)
            assertEquals(0, firstResult.deletedArtifacts)
            assertEquals(1, firstResult.quarantinedArtifacts)
            assertQuarantinedBytesPreserved(database, root, corruptBeforeClaim, firstCorruption)

            val corruptDuringRecovery = publishCommittedOrphan(database, repository, store, byteArrayOf(5, 6, 7, 8))
            val activeClaim = store.claimUnreferencedArtifacts(1L).single()
            assertEquals(corruptDuringRecovery.artifactId, activeClaim.artifactId)
            val secondCorruption = byteArrayOf(81, 82)
            Files.write(root.resolve(corruptDuringRecovery.relativePath), secondCorruption)
            assertEquals(1, arbiter.recoverInterruptedDeletions(1L))
            assertQuarantinedBytesPreserved(database, root, corruptDuringRecovery, secondCorruption)

            val validOrphan = publishCommittedOrphan(database, repository, store, byteArrayOf(21, 22, 23, 24))
            val positiveResult = arbiter.runBatch(1L)
            assertEquals(1, positiveResult.claimedArtifacts)
            assertEquals(1, positiveResult.deletedArtifacts)
            assertEquals(0, positiveResult.quarantinedArtifacts)
            val deleted = database.schemaQueries.artifactById(validOrphan.artifactId.value).executeAsOne()
            assertEquals("COMMITTED", deleted.state)
            assertEquals("DELETED", deleted.storage_state)
            assertFalse(Files.exists(root.resolve(validOrphan.relativePath)))

            val noRetry = arbiter.runBatch(1L)
            assertEquals(0, noRetry.claimedArtifacts)
            assertQuarantinedBytesPreserved(database, root, corruptBeforeClaim, firstCorruption)
            assertQuarantinedBytesPreserved(database, root, corruptDuringRecovery, secondCorruption)
        } finally {
            driver.close()
            context.deleteDatabase(databaseName)
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun publishCommittedOrphan(
        database: DocScanDatabase,
        repository: FileArtifactRepository,
        store: StateStore,
        bytes: ByteArray,
    ): DurableImageArtifact {
        val artifact = repository.publishJpeg(ByteArrayInputStream(bytes))
        store.recordStagedDurableArtifact(artifact)
        assertEquals(1L, database.schemaQueries.commitArtifact(artifact.artifactId.value).value)
        assertTrue(repository.verifyPublished(artifact.relativePath, artifact.sizeBytes, artifact.sha256))
        return artifact
    }

    private fun assertQuarantinedBytesPreserved(
        database: DocScanDatabase,
        root: java.nio.file.Path,
        artifact: DurableImageArtifact,
        expectedBytes: ByteArray,
    ) {
        val row = database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne()
        assertEquals("QUARANTINED", row.state)
        assertEquals("PRESENT", row.storage_state)
        assertNull(row.gc_claim_token)
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
        val path = root.resolve(artifact.relativePath)
        assertTrue("Quarantined bytes must stay present", Files.exists(path))
        assertArrayEquals(expectedBytes, Files.readAllBytes(path))
    }
}
