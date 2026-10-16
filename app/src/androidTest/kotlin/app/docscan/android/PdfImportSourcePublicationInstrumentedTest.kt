package app.docscan.android

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.model.ImportSessionId
import app.docscan.core.artifact.AtomicArtifactPublisher
import app.docscan.core.artifact.ArtifactStagingSync
import app.docscan.core.artifact.ArtifactStagingWriteCheckpoint
import app.docscan.core.artifact.ArtifactStagingOutputFlush
import app.docscan.core.artifact.DirectorySync
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImportSourcePublicationInstrumentedTest {
    @Test
    fun synchronousInvalidPdfRemovesOnlyItsPrivateSnapshotAndIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-invalid-$suffix.db"
        val artifactDirectoryName = "pdf-source-invalid-artifacts-$suffix"
        val artifactRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        artifactRoot.mkdirs()
        val sentinel = File(artifactRoot, "unrelated-sentinel.bin").apply { writeBytes(byteArrayOf(3, 1, 4)) }
        val sentinelBefore = sentinel.readBytes()
        val application = context.applicationContext as DocScanApplication
        val priorStorage = application.productStorageOverrideForTest
        val storage = ProductStorage(context, databaseName, artifactDirectoryName)
        application.productStorageOverrideForTest = storage
        val sessionId = ImportSessionId.new()
        try {
            var failure: Exception? = null
            try {
                storage.importPdf(
                    ByteArrayInputStream("not a PDF".toByteArray()),
                    "invalid.pdf",
                    sessionId,
                    application.heavyWorkScheduler,
                )
            } catch (caught: Exception) {
                failure = caught
            }
            assertTrue("Invalid PDF must fail inspection", failure != null)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM PdfImportPublicationIntent WHERE import_session_id = ?", sessionId.value))
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", sessionId.value))
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", sessionId.value))
            }
            assertFalse(File(artifactRoot, "objects/${sessionId.value.take(2)}/${sessionId.value}.pdf").exists())
            assertTrue(sentinel.readBytes().contentEquals(sentinelBefore))
        } finally {
            application.productStorageOverrideForTest = priorStorage
            storage.close()
            context.deleteDatabase(databaseName)
            artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun enospcAtPdfSourceAtomicPublicationLeavesNoPrivateOrDatabaseOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-enospc-$suffix.db"
        val artifactDirectoryName = "pdf-source-enospc-artifacts-$suffix"
        val artifactRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        val application = context.applicationContext as DocScanApplication
        val priorStorage = application.productStorageOverrideForTest
        val publisherCalls = java.util.concurrent.atomic.AtomicInteger()
        val injectedPublisher = AtomicArtifactPublisher { temporary, target ->
            publisherCalls.incrementAndGet()
            assertTrue("PDF bytes must reach the private staging file before destination ENOSPC", Files.isRegularFile(temporary))
            assertTrue("Staged PDF must contain the valid source", Files.size(temporary) > 0)
            throw FileSystemException(temporary.toString(), target.toString(), "No space left on device")
        }
        val storage = ProductStorage(
            context,
            databaseName,
            artifactDirectoryName,
            artifactAtomicPublisher = injectedPublisher,
        )
        application.productStorageOverrideForTest = storage
        val sessionId = ImportSessionId.new()
        val sourceBytes = createPdfBytes(pageCount = 2)
        val sourceHash = sha256(sourceBytes)
        val projectionBefore = storage.workspaceProjection()
        var storageClosed = false
        try {
            var failure: Exception? = null
            try {
                storage.importPdf(
                    ByteArrayInputStream(sourceBytes),
                    "low-space.pdf",
                    sessionId,
                    application.heavyWorkScheduler,
                )
            } catch (caught: Exception) {
                failure = caught
            }
            assertTrue("ENOSPC must propagate to the import caller", failure is FileSystemException)
            assertEquals("No space left on device", (failure as FileSystemException).reason)
            assertEquals(1, publisherCalls.get())
            assertEquals(sourceHash, sha256(sourceBytes))
            assertEquals("A failed import must not change workspace", projectionBefore, storage.workspaceProjection())
            val countsAfterFailure = readPublicationCounts(context, databaseName, sessionId)
            assertEquals(mapOf(
                "PdfImportPublicationIntent" to 0L,
                "ImportSession" to 0L,
                "Page" to 0L,
                "ImageVersion" to 0L,
                "ArtifactRecord" to 0L,
                "ArtifactReference" to 0L,
            ), countsAfterFailure)
            assertNoPrivatePdfOrStagingFiles(artifactRoot)
            Log.i("PdfImportNoSpace", "phase=atomic-publish-enospc sourceSize=${sourceBytes.size} sourceSha256=$sourceHash publisherCalls=${publisherCalls.get()} counts=$countsAfterFailure workspaceUnchanged=true privateFiles=0 exception=${failure.javaClass.simpleName}:${failure.message}")

            application.productStorageOverrideForTest = priorStorage
            storage.close()
            storageClosed = true
            val reopened = ProductStorage(context, databaseName, artifactDirectoryName)
            try {
                assertFalse("Restart must not report startup integrity failure", reopened.startupIntegrityBlocked)
                assertTrue("Failed source publication must not create a resumable session",
                    reopened.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
                assertEquals(projectionBefore, reopened.workspaceProjection())
                assertEquals(countsAfterFailure, readPublicationCounts(context, databaseName, sessionId))
                assertNoPrivatePdfOrStagingFiles(artifactRoot)
                Log.i("PdfImportNoSpace", "phase=restart-reconciled counts=${readPublicationCounts(context, databaseName, sessionId)} workspaceUnchanged=true privateFiles=0")
            } finally {
                reopened.close()
            }
        } finally {
            application.productStorageOverrideForTest = priorStorage
            if (!storageClosed) storage.close()
            context.deleteDatabase(databaseName)
            artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun enospcAtPdfSourceStagingForceLeavesNoPrivateOrDatabaseOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-fsync-enospc-$suffix.db"
        val artifactDirectoryName = "pdf-source-fsync-enospc-artifacts-$suffix"
        val artifactRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        val application = context.applicationContext as DocScanApplication
        val priorStorage = application.productStorageOverrideForTest
        val sourceBytes = createPdfBytes(pageCount = 2)
        val sourceHash = sha256(sourceBytes)
        val forceCalls = java.util.concurrent.atomic.AtomicInteger()
        val publisherCalls = java.util.concurrent.atomic.AtomicInteger()
        val injectedSync = ArtifactStagingSync { channel ->
            forceCalls.incrementAndGet()
            assertEquals("Complete PDF bytes must be staged before force", sourceBytes.size.toLong(), channel.size())
            throw FileSystemException("private-pdf-staging", null, "No space left on device")
        }
        val injectedPublisher = AtomicArtifactPublisher { _, _ ->
            publisherCalls.incrementAndGet()
            throw AssertionError("Final publication must not run after staging force fails")
        }
        val storage = ProductStorage(
            context,
            databaseName,
            artifactDirectoryName,
            artifactAtomicPublisher = injectedPublisher,
            artifactStagingSync = injectedSync,
        )
        application.productStorageOverrideForTest = storage
        val sessionId = ImportSessionId.new()
        val projectionBefore = storage.workspaceProjection()
        var storageClosed = false
        try {
            var failure: Exception? = null
            try {
                storage.importPdf(
                    ByteArrayInputStream(sourceBytes),
                    "staging-sync-enospc.pdf",
                    sessionId,
                    application.heavyWorkScheduler,
                )
            } catch (caught: Exception) {
                failure = caught
            }
            assertTrue("Staging force ENOSPC must propagate to caller", failure is FileSystemException)
            assertEquals("No space left on device", (failure as FileSystemException).reason)
            assertEquals(1, forceCalls.get())
            assertEquals("Final publication must remain unreachable", 0, publisherCalls.get())
            assertEquals(sourceHash, sha256(sourceBytes))
            assertEquals("A failed staging force must not change workspace", projectionBefore, storage.workspaceProjection())
            val countsAfterFailure = readPublicationCounts(context, databaseName, sessionId)
            assertEquals(mapOf(
                "PdfImportPublicationIntent" to 0L,
                "ImportSession" to 0L,
                "Page" to 0L,
                "ImageVersion" to 0L,
                "ArtifactRecord" to 0L,
                "ArtifactReference" to 0L,
            ), countsAfterFailure)
            assertNoPrivatePdfOrStagingFiles(artifactRoot)
            Log.i("PdfImportNoSpace", "phase=staging-force-enospc sourceSize=${sourceBytes.size} sourceSha256=$sourceHash forceCalls=${forceCalls.get()} publisherCalls=${publisherCalls.get()} counts=$countsAfterFailure workspaceUnchanged=true privateFiles=0 exception=${failure.javaClass.simpleName}:${failure.message}")

            application.productStorageOverrideForTest = priorStorage
            storage.close()
            storageClosed = true
            val reopened = ProductStorage(context, databaseName, artifactDirectoryName)
            try {
                assertFalse("Restart must not report startup integrity failure", reopened.startupIntegrityBlocked)
                assertTrue("Failed staging force must not create a resumable session",
                    reopened.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
                assertEquals(projectionBefore, reopened.workspaceProjection())
                assertEquals(countsAfterFailure, readPublicationCounts(context, databaseName, sessionId))
                assertNoPrivatePdfOrStagingFiles(artifactRoot)
                Log.i("PdfImportNoSpace", "phase=staging-force-restart-reconciled counts=${readPublicationCounts(context, databaseName, sessionId)} workspaceUnchanged=true privateFiles=0")
            } finally {
                reopened.close()
            }
        } finally {
            application.productStorageOverrideForTest = priorStorage
            if (!storageClosed) storage.close()
            context.deleteDatabase(databaseName)
            artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun enospcBeforeSecondPdfSourceStagingWriteCleansPartialPrefixAndOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-write-enospc-$suffix.db"
        val artifactDirectoryName = "pdf-source-write-enospc-artifacts-$suffix"
        val artifactRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        val application = context.applicationContext as DocScanApplication
        val priorStorage = application.productStorageOverrideForTest
        val sourceBytes = createPdfBytes(pageCount = 2)
        val sourceHash = sha256(sourceBytes)
        val writeCheckpoints = java.util.concurrent.atomic.AtomicInteger()
        val forceCalls = java.util.concurrent.atomic.AtomicInteger()
        val publisherCalls = java.util.concurrent.atomic.AtomicInteger()
        val injectedCheckpoint = ArtifactStagingWriteCheckpoint { channel, bytesAlreadyWritten ->
            val call = writeCheckpoints.incrementAndGet()
            if (call == 2) {
                assertEquals("Exactly one 64-byte prefix must have reached staging", 64L, bytesAlreadyWritten)
                assertEquals("The staging file must contain only the committed prefix", 64L, channel.size())
                throw FileSystemException("private-pdf-staging", null, "No space left on device")
            }
            assertEquals("First staging write must start at offset zero", 0L, bytesAlreadyWritten)
            assertEquals("Staging file must initially be empty", 0L, channel.size())
        }
        val injectedSync = ArtifactStagingSync {
            forceCalls.incrementAndGet()
            it.force(true)
        }
        val injectedPublisher = AtomicArtifactPublisher { _, _ ->
            publisherCalls.incrementAndGet()
            throw AssertionError("Sync and publish must not run after staging write ENOSPC")
        }
        val storage = ProductStorage(
            context,
            databaseName,
            artifactDirectoryName,
            artifactAtomicPublisher = injectedPublisher,
            artifactStagingSync = injectedSync,
            artifactStagingWriteCheckpoint = injectedCheckpoint,
        )
        application.productStorageOverrideForTest = storage
        val sessionId = ImportSessionId.new()
        val projectionBefore = storage.workspaceProjection()
        var storageClosed = false
        try {
            val shortReadSource = object : FilterInputStream(ByteArrayInputStream(sourceBytes)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, minOf(length, 64))
            }
            var failure: Exception? = null
            try {
                storage.importPdf(
                    shortReadSource,
                    "staging-write-enospc.pdf",
                    sessionId,
                    application.heavyWorkScheduler,
                )
            } catch (caught: Exception) {
                failure = caught
            }
            assertTrue("ENOSPC before second staging write must propagate", failure is FileSystemException)
            assertEquals("No space left on device", (failure as FileSystemException).reason)
            assertEquals(2, writeCheckpoints.get())
            assertEquals(0, forceCalls.get())
            assertEquals(0, publisherCalls.get())
            assertEquals(sourceHash, sha256(sourceBytes))
            assertEquals("A partial staging write must not change workspace", projectionBefore, storage.workspaceProjection())
            val countsAfterFailure = readPublicationCounts(context, databaseName, sessionId)
            assertEquals(mapOf(
                "PdfImportPublicationIntent" to 0L,
                "ImportSession" to 0L,
                "Page" to 0L,
                "ImageVersion" to 0L,
                "ArtifactRecord" to 0L,
                "ArtifactReference" to 0L,
            ), countsAfterFailure)
            assertNoPrivatePdfOrStagingFiles(artifactRoot)
            Log.i("PdfImportNoSpace", "phase=staging-write-enospc sourceSize=${sourceBytes.size} sourceSha256=$sourceHash stagedPrefixBytes=64 writeCheckpoints=${writeCheckpoints.get()} forceCalls=${forceCalls.get()} publisherCalls=${publisherCalls.get()} counts=$countsAfterFailure workspaceUnchanged=true privateFiles=0 exception=${failure.javaClass.simpleName}:${failure.message}")

            application.productStorageOverrideForTest = priorStorage
            storage.close()
            storageClosed = true
            val reopened = ProductStorage(context, databaseName, artifactDirectoryName)
            try {
                assertFalse("Restart must not report startup integrity failure", reopened.startupIntegrityBlocked)
                assertTrue("Failed staging write must not create a resumable session",
                    reopened.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
                assertEquals(projectionBefore, reopened.workspaceProjection())
                assertEquals(countsAfterFailure, readPublicationCounts(context, databaseName, sessionId))
                assertNoPrivatePdfOrStagingFiles(artifactRoot)
                Log.i("PdfImportNoSpace", "phase=staging-write-restart-reconciled counts=${readPublicationCounts(context, databaseName, sessionId)} workspaceUnchanged=true privateFiles=0")
            } finally {
                reopened.close()
            }
        } finally {
            application.productStorageOverrideForTest = priorStorage
            if (!storageClosed) storage.close()
            context.deleteDatabase(databaseName)
            artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun enospcAtPdfSourceStagingOutputFlushLeavesNoPrivateOrDatabaseOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-flush-enospc-$suffix.db"
        val artifactDirectoryName = "pdf-source-flush-enospc-artifacts-$suffix"
        val artifactRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        val application = context.applicationContext as DocScanApplication
        val priorStorage = application.productStorageOverrideForTest
        val sourceBytes = createPdfBytes(pageCount = 2)
        val sourceHash = sha256(sourceBytes)
        val flushCalls = java.util.concurrent.atomic.AtomicInteger()
        val forceCalls = java.util.concurrent.atomic.AtomicInteger()
        val publisherCalls = java.util.concurrent.atomic.AtomicInteger()
        val injectedFlush = ArtifactStagingOutputFlush { _, channel, bytesWritten ->
            flushCalls.incrementAndGet()
            assertEquals("All PDF bytes must be written before flush", sourceBytes.size.toLong(), bytesWritten)
            assertEquals("Staging file must contain the complete PDF at flush", sourceBytes.size.toLong(), channel.size())
            throw FileSystemException("private-pdf-staging", null, "No space left on device")
        }
        val injectedSync = ArtifactStagingSync { channel ->
            forceCalls.incrementAndGet()
            channel.force(true)
        }
        val injectedPublisher = AtomicArtifactPublisher { _, _ ->
            publisherCalls.incrementAndGet()
            throw AssertionError("Force and publish must not run after staging flush ENOSPC")
        }
        val storage = ProductStorage(
            context,
            databaseName,
            artifactDirectoryName,
            artifactAtomicPublisher = injectedPublisher,
            artifactStagingSync = injectedSync,
            artifactStagingOutputFlush = injectedFlush,
        )
        application.productStorageOverrideForTest = storage
        val sessionId = ImportSessionId.new()
        val projectionBefore = storage.workspaceProjection()
        var storageClosed = false
        try {
            var failure: Exception? = null
            try {
                storage.importPdf(
                    ByteArrayInputStream(sourceBytes),
                    "staging-flush-enospc.pdf",
                    sessionId,
                    application.heavyWorkScheduler,
                )
            } catch (caught: Exception) {
                failure = caught
            }
            assertTrue("Staging output flush ENOSPC must propagate", failure is FileSystemException)
            assertEquals("No space left on device", (failure as FileSystemException).reason)
            assertEquals(1, flushCalls.get())
            assertEquals(0, forceCalls.get())
            assertEquals(0, publisherCalls.get())
            assertEquals(sourceHash, sha256(sourceBytes))
            assertEquals("Staging flush failure must not change workspace", projectionBefore, storage.workspaceProjection())
            val countsAfterFailure = readPublicationCounts(context, databaseName, sessionId)
            assertEquals(mapOf(
                "PdfImportPublicationIntent" to 0L,
                "ImportSession" to 0L,
                "Page" to 0L,
                "ImageVersion" to 0L,
                "ArtifactRecord" to 0L,
                "ArtifactReference" to 0L,
            ), countsAfterFailure)
            assertNoPrivatePdfOrStagingFiles(artifactRoot)
            Log.i("PdfImportNoSpace", "phase=staging-flush-enospc sourceSize=${sourceBytes.size} sourceSha256=$sourceHash flushCalls=${flushCalls.get()} forceCalls=${forceCalls.get()} publisherCalls=${publisherCalls.get()} counts=$countsAfterFailure workspaceUnchanged=true privateFiles=0 exception=${failure.javaClass.simpleName}:${failure.message}")

            application.productStorageOverrideForTest = priorStorage
            storage.close()
            storageClosed = true
            val reopened = ProductStorage(context, databaseName, artifactDirectoryName)
            try {
                assertFalse("Restart must not report startup integrity failure", reopened.startupIntegrityBlocked)
                assertTrue("Failed flush must not create a resumable session",
                    reopened.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
                assertEquals(projectionBefore, reopened.workspaceProjection())
                assertEquals(countsAfterFailure, readPublicationCounts(context, databaseName, sessionId))
                assertNoPrivatePdfOrStagingFiles(artifactRoot)
                Log.i("PdfImportNoSpace", "phase=staging-flush-restart-reconciled counts=${readPublicationCounts(context, databaseName, sessionId)} workspaceUnchanged=true privateFiles=0")
            } finally {
                reopened.close()
            }
        } finally {
            application.productStorageOverrideForTest = priorStorage
            if (!storageClosed) storage.close()
            context.deleteDatabase(databaseName)
            artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun enospcAtPdfSourceStagingDirectorySyncCleansAtomicallyPublishedTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-dirsync-enospc-$suffix.db"
        val artifactDirectoryName = "pdf-source-dirsync-enospc-artifacts-$suffix"
        val artifactRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        val application = context.applicationContext as DocScanApplication
        val priorStorage = application.productStorageOverrideForTest
        val sourceBytes = createPdfBytes(pageCount = 2)
        val sourceHash = sha256(sourceBytes)
        val sessionId = ImportSessionId.new()
        val expectedTarget = File(artifactRoot, "objects/${sessionId.value.take(2)}/${sessionId.value}.pdf")
        val syncFailures = java.util.concurrent.atomic.AtomicInteger()
        val targetPresentAtInjectedFailure = java.util.concurrent.atomic.AtomicBoolean()
        val syncDirectories = java.util.concurrent.CopyOnWriteArrayList<String>()
        val injectedDirectorySync = DirectorySync { directory ->
            syncDirectories += directory.fileName.toString()
            if (directory.fileName.toString() == "staging" && syncFailures.compareAndSet(0, 1)) {
                targetPresentAtInjectedFailure.set(expectedTarget.isFile && expectedTarget.length() == sourceBytes.size.toLong())
                throw FileSystemException(directory.toString(), null, "No space left on device")
            }
            AndroidDirectorySync().sync(directory)
        }
        val storage = ProductStorage(
            context,
            databaseName,
            artifactDirectoryName,
            directorySyncFactory = { injectedDirectorySync },
        )
        application.productStorageOverrideForTest = storage
        val projectionBefore = storage.workspaceProjection()
        var storageClosed = false
        try {
            var failure: Exception? = null
            try {
                storage.importPdf(
                    ByteArrayInputStream(sourceBytes),
                    "directory-sync-enospc.pdf",
                    sessionId,
                    application.heavyWorkScheduler,
                )
            } catch (caught: Exception) {
                failure = caught
            }
            assertTrue("Directory sync ENOSPC must propagate to the import caller", failure is FileSystemException)
            assertEquals("No space left on device", (failure as FileSystemException).reason)
            assertEquals(1, syncFailures.get())
            assertTrue("Atomic target must exist with complete bytes when staging directory sync fails", targetPresentAtInjectedFailure.get())
            assertEquals("Cleanup must continue with later directory syncs", true, syncDirectories.size > 1)
            assertEquals(sourceHash, sha256(sourceBytes))
            assertEquals("Failed publication must not change workspace", projectionBefore, storage.workspaceProjection())
            val countsAfterFailure = readPublicationCounts(context, databaseName, sessionId)
            assertEquals(mapOf(
                "PdfImportPublicationIntent" to 0L,
                "ImportSession" to 0L,
                "Page" to 0L,
                "ImageVersion" to 0L,
                "ArtifactRecord" to 0L,
                "ArtifactReference" to 0L,
            ), countsAfterFailure)
            assertNoPrivatePdfOrStagingFiles(artifactRoot)
            assertTrue("Only the first staging sync may be injected", syncDirectories.first() == "staging")
            Log.i("PdfImportNoSpace", "phase=directory-sync-enospc sourceSize=${sourceBytes.size} sourceSha256=$sourceHash targetPresentAtFailure=${targetPresentAtInjectedFailure.get()} syncCalls=${syncDirectories.size} syncFailures=${syncFailures.get()} counts=$countsAfterFailure workspaceUnchanged=true privateFiles=0 exception=${failure.javaClass.simpleName}:${failure.message}")

            application.productStorageOverrideForTest = priorStorage
            storage.close()
            storageClosed = true
            val reopened = ProductStorage(context, databaseName, artifactDirectoryName)
            try {
                assertFalse("Restart must not report startup integrity failure", reopened.startupIntegrityBlocked)
                assertTrue("Failed directory sync must not create a resumable session",
                    reopened.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
                assertEquals(projectionBefore, reopened.workspaceProjection())
                assertEquals(countsAfterFailure, readPublicationCounts(context, databaseName, sessionId))
                assertNoPrivatePdfOrStagingFiles(artifactRoot)
                Log.i("PdfImportNoSpace", "phase=directory-sync-restart-reconciled counts=${readPublicationCounts(context, databaseName, sessionId)} workspaceUnchanged=true privateFiles=0")
            } finally {
                reopened.close()
            }
        } finally {
            application.productStorageOverrideForTest = priorStorage
            if (!storageClosed) storage.close()
            context.deleteDatabase(databaseName)
            artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun startupAbandonsOnlyAnAbsentSourceIntentAndItsIdPrefixedPartialFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-partial-$suffix.db"
        val artifactDirectoryName = "pdf-source-partial-artifacts-$suffix"
        val artifactRoot = File(context.noBackupFilesDir, artifactDirectoryName)
        val application = context.applicationContext as DocScanApplication
        val first = ProductStorage(context, databaseName, artifactDirectoryName)
        val workspaceId = first.workspaceId.value
        val sessionId = ImportSessionId.new()
        first.close()
        val staging = File(artifactRoot, "staging").apply { mkdirs() }
        val ownedTemp = File(staging, "${sessionId.value}-00000000-0000-0000-0000-000000000001.tmp").apply {
            writeBytes(byteArrayOf(8, 6, 7, 5))
        }
        val unrelated = File(staging, "${UUID.randomUUID()}-00000000-0000-0000-0000-000000000002.tmp").apply {
            writeBytes(byteArrayOf(9, 7, 9, 3))
        }
        val unrelatedBefore = unrelated.readBytes()
        val sentinel = File(artifactRoot, "keep.bin").apply { writeBytes(byteArrayOf(2, 7, 1, 8)) }
        val sentinelBefore = sentinel.readBytes()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL(
                "INSERT INTO PdfImportPublicationIntent(import_session_id, workspace_id, source_display_name) VALUES (?, ?, ?)",
                arrayOf(sessionId.value, workspaceId, "interrupted.pdf"),
            )
        }

        val recovered = ProductStorage(context, databaseName, artifactDirectoryName)
        try {
            assertFalse(recovered.startupIntegrityBlocked)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM PdfImportPublicationIntent WHERE import_session_id = ?", sessionId.value))
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", sessionId.value))
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", sessionId.value))
            }
            assertFalse(ownedTemp.exists())
            assertTrue(unrelated.exists())
            assertTrue(unrelated.readBytes().contentEquals(unrelatedBefore))
            assertTrue(sentinel.readBytes().contentEquals(sentinelBefore))
        } finally {
            recovered.close()
            context.deleteDatabase(databaseName)
            artifactRoot.deleteRecursively()
        }
    }

    @Test
    fun changedBytesForRegisteredSessionCannotDeleteOrReplaceThePrivateSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "pdf-source-mismatch-$suffix.db"
        val artifactDirectoryName = "pdf-source-mismatch-artifacts-$suffix"
        val application = context.applicationContext as DocScanApplication
        val priorStorage = application.productStorageOverrideForTest
        val storage = ProductStorage(context, databaseName, artifactDirectoryName)
        application.productStorageOverrideForTest = storage
        val sessionId = ImportSessionId.new()
        val originalBytes = createPdfBytes()
        val changedBytes = createPdfBytes(pageCount = 2)
        try {
            storage.importPdf(ByteArrayInputStream(originalBytes), "same.pdf", sessionId, application.heavyWorkScheduler)
            val sourceFile = File(context.noBackupFilesDir, "$artifactDirectoryName/objects/${sessionId.value.take(2)}/${sessionId.value}.pdf")
            val originalHash = sha256(originalBytes)
            assertEquals(originalHash, sha256(sourceFile.readBytes()))
            val originalPages = storage.importedPdfPageIds(sessionId)
            var failure: Exception? = null
            try {
                storage.importPdf(ByteArrayInputStream(changedBytes), "same.pdf", sessionId, application.heavyWorkScheduler)
            } catch (caught: Exception) {
                failure = caught
            }
            assertTrue("Different bytes under the same ImportSessionId must fail", failure != null)
            assertEquals(originalHash, sha256(sourceFile.readBytes()))
            assertEquals(originalPages, storage.importedPdfPageIds(sessionId))
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals("COMPLETED", db.rawQuery(
                    "SELECT state FROM ImportSession WHERE import_session_id = ?",
                    arrayOf(sessionId.value),
                ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) })
            }
            assertFalse(File(context.noBackupFilesDir, "$artifactDirectoryName/objects/${sessionId.value.take(2)}/${sessionId.value}.pdf.tmp").exists())
        } finally {
            application.productStorageOverrideForTest = priorStorage
            storage.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectoryName).deleteRecursively()
        }
    }

    private fun readPublicationCounts(context: android.content.Context, databaseName: String, sessionId: ImportSessionId): Map<String, Long> =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            linkedMapOf(
                "PdfImportPublicationIntent" to scalarLong(db, "SELECT COUNT(*) FROM PdfImportPublicationIntent WHERE import_session_id = ?", sessionId.value),
                "ImportSession" to scalarLong(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", sessionId.value),
                "Page" to scalarLong(db, "SELECT COUNT(*) FROM Page"),
                "ImageVersion" to scalarLong(db, "SELECT COUNT(*) FROM ImageVersion"),
                "ArtifactRecord" to scalarLong(db, "SELECT COUNT(*) FROM ArtifactRecord"),
                "ArtifactReference" to scalarLong(db, "SELECT COUNT(*) FROM ArtifactReference"),
            )
        }

    private fun assertNoPrivatePdfOrStagingFiles(artifactRoot: File) {
        val objects = File(artifactRoot, "objects")
        val staging = File(artifactRoot, "staging")
        assertTrue("No private artifact object may survive ENOSPC", objects.walkTopDown().none { it.isFile })
        assertTrue("No partial staging file may survive ENOSPC", staging.walkTopDown().none { it.isFile })
    }

    private fun createPdfBytes(pageCount: Int = 1): ByteArray = ByteArrayOutputStream().use { output ->
        PDDocument().use { document ->
            repeat(pageCount) { document.addPage(PDPage()) }
            document.save(output)
        }
        output.toByteArray()
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String, vararg args: String): Long =
        db.rawQuery(sql, args).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

}
