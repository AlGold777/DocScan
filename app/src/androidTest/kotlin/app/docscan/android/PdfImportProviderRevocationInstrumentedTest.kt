package app.docscan.android

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.model.ImportSessionId
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfImportProviderRevocationInstrumentedTest {
    @Test
    fun providerFailureAfterPrefixLeavesNoPrivateOrRegisteredPartialSource() {
        repeat(2) { repetition ->
            withFixture("provider-failure-$repetition") { fixture ->
                val source = createPdfBytes(pageCount = 2)
                configureProvider(fixture, source, PdfSourceFaultTestProvider.MODE_FAIL_AFTER_PREFIX, split = 64)
                fixture.grant()
                try {
                    val result = java.util.concurrent.CompletableFuture<ImportResult>()
                    val input = fixture.openSource()
                    Thread({
                        try {
                            result.complete(ImportResult.Success(fixture.storage.importPdf(
                                input,
                                "provider-failure.pdf",
                                fixture.sessionId,
                                fixture.application.heavyWorkScheduler,
                            )))
                        } catch (failure: Exception) {
                            result.complete(ImportResult.Failure(failure))
                        } finally {
                            runCatching { input.close() }
                        }
                    }, "pdf-import-provider-failure-$repetition").start()
                    awaitProviderState(fixture) { it.getBoolean(KEY_PREFIX_WRITTEN) }
                    assertEquals(64, providerState(fixture).getInt(KEY_BYTES_WRITTEN))
                    releaseProvider(fixture)
                    val importResult = result.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    assertTrue("A reliable-pipe provider failure during source copy must fail import",
                        importResult is ImportResult.Failure)
                    awaitProviderState(fixture) { it.getString(KEY_OUTCOME) == "FAILED_AFTER_PREFIX" }
                    assertNoOwnedSourceState(fixture)
                    android.util.Log.i(TAG, "SOURCE_FAILURE_PASS fixture=${fixture.uri.lastPathSegment} session=${fixture.sessionId.value} " +
                        "bytesWritten=${providerState(fixture).getInt(KEY_BYTES_WRITTEN)} " +
                        "providerOutcome=${providerState(fixture).getString(KEY_OUTCOME)} sourceSha256=${sha256(source)} phase=pre-startup")

                    fixture.storage.close()
                    fixture.storage = ProductStorage(fixture.targetContext, fixture.databaseName, fixture.artifactDirectoryName)
                    assertFalse("A fresh startup must not be integrity-blocked", fixture.storage.startupIntegrityBlocked)
                    assertNoOwnedSourceState(fixture)
                    android.util.Log.i(TAG, "SOURCE_FAILURE_RECOVERY_PASS fixture=${fixture.uri.lastPathSegment} " +
                        "session=${fixture.sessionId.value} startupIntegrityBlocked=false phase=post-startup")
                    assertTrue(fixture.sentinel.readBytes().contentEquals(fixture.sentinelBefore))
                    assertEquals("Provider must serve exactly one source stream", 1, providerState(fixture).getInt(KEY_OPENS))
                } finally {
                    fixture.revoke()
                    clearProvider(fixture)
                }
            }
        }
    }

    @Test
    fun revokingGrantDuringActiveCopyCannotProducePartialPrivateSource() {
        repeat(2) { repetition ->
            withFixture("provider-revoke-copy-$repetition") { fixture ->
                val source = createPdfBytes(pageCount = 2)
                configureProvider(fixture, source, PdfSourceFaultTestProvider.MODE_BLOCK_THEN_COMPLETE, split = 96)
                fixture.grant()
                val result = java.util.concurrent.CompletableFuture<ImportResult>()
                val input = fixture.openSource()
                Thread({
                    try {
                        result.complete(ImportResult.Success(fixture.storage.importPdf(
                            input,
                            "revoked-during-copy.pdf",
                            fixture.sessionId,
                            fixture.application.heavyWorkScheduler,
                        )))
                    } catch (failure: Exception) {
                        result.complete(ImportResult.Failure(failure))
                    } finally {
                        runCatching { input.close() }
                    }
                }, "pdf-import-provider-revoke-$repetition").start()

                try {
                    awaitProviderState(fixture) { it.getBoolean(KEY_PREFIX_WRITTEN) }
                    assertEquals(96, providerState(fixture).getInt(KEY_BYTES_WRITTEN))
                    fixture.revoke()
                    assertFreshOpenDenied(fixture)
                    assertEquals("Revocation must not open the source a second time", 1, providerState(fixture).getInt(KEY_OPENS))
                    releaseProvider(fixture)

                    val importResult = result.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    when (importResult) {
                        is ImportResult.Success -> {
                            assertEquals(source.size, providerState(fixture).getInt(KEY_BYTES_WRITTEN))
                            assertEquals(sha256(source), sha256(fixture.sourceFile().readBytes()))
                            assertEquals(2, importResult.pageIds.size)
                            assertEquals(importResult.pageIds, fixture.storage.importedPdfPageIds(fixture.sessionId))
                        }
                        is ImportResult.Failure -> {
                            assertNoOwnedSourceState(fixture)
                            fixture.storage.close()
                            fixture.storage = ProductStorage(fixture.targetContext, fixture.databaseName, fixture.artifactDirectoryName)
                            assertFalse("Fresh startup after revoked-copy failure must remain available", fixture.storage.startupIntegrityBlocked)
                            assertNoOwnedSourceState(fixture)
                        }
                    }
                    assertEquals("The importer may use only the single stream opened before revocation", 1,
                        providerState(fixture).getInt(KEY_OPENS))
                    assertTrue(fixture.sentinel.readBytes().contentEquals(fixture.sentinelBefore))
                    android.util.Log.i(TAG, "ACTIVE_REVOKE_RESULT repetition=$repetition result=${importResult.javaClass.simpleName} " +
                        "providerOutcome=${providerState(fixture).getString(KEY_OUTCOME)} sourceSha256=${
                            if (importResult is ImportResult.Success) sha256(fixture.sourceFile().readBytes()) else "none"
                        }")
                } finally {
                    releaseProvider(fixture)
                    fixture.revoke()
                    clearProvider(fixture)
                }
            }
        }
    }

    @Test
    fun revokingGrantAfterPrivatePublicationDoesNotAffectImport() {
        repeat(2) { repetition ->
            withFixture("provider-revoke-published-$repetition") { fixture ->
                val source = createPdfBytes(pageCount = 2)
                configureProvider(fixture, source, PdfSourceFaultTestProvider.MODE_COMPLETE)
                fixture.grant()
                var revokedAtPublication = false
                fixture.storage.pdfImportSourcePublicationBoundaryObserverForTest = { _, artifact ->
                    val privateFile = fixture.targetContext.noBackupFilesDir.resolve(
                        "${fixture.artifactDirectoryName}/${artifact.relativePath}",
                    )
                    assertTrue("Private PDF must be published before the revoke checkpoint", privateFile.isFile)
                    assertEquals(sha256(source), sha256(privateFile.readBytes()))
                    fixture.revoke()
                    assertFreshOpenDenied(fixture)
                    assertEquals("Provider must not be reopened after private publication", 1,
                        providerState(fixture).getInt(KEY_OPENS))
                    revokedAtPublication = true
                }

                try {
                    val pages = fixture.storage.importPdf(
                        fixture.openSource(),
                        "revoked-after-publication.pdf",
                        fixture.sessionId,
                        fixture.application.heavyWorkScheduler,
                    )
                    assertTrue("Test must revoke the URI at the durable private publication boundary", revokedAtPublication)
                    assertEquals(sha256(source), sha256(fixture.sourceFile().readBytes()))
                    assertEquals(2, pages.size)
                    assertEquals(pages, fixture.storage.importedPdfPageIds(fixture.sessionId))
                    assertEquals(1, providerState(fixture).getInt(KEY_OPENS))
                    assertEquals(source.size, providerState(fixture).getInt(KEY_BYTES_WRITTEN))
                    assertTrue(fixture.sentinel.readBytes().contentEquals(fixture.sentinelBefore))
                    android.util.Log.i(TAG, "POST_PUBLICATION_REVOKE_PASS repetition=$repetition sourceSha256=${sha256(source)} pages=${pages.size}")
                } finally {
                    fixture.revoke()
                    clearProvider(fixture)
                }
            }
        }
    }

    @Test
    fun completeProviderSourcePositiveControlImportsExactSnapshot() {
        repeat(2) { repetition ->
            withFixture("provider-source-positive-$repetition") { fixture ->
                val source = createPdfBytes(pageCount = 3)
                configureProvider(fixture, source, PdfSourceFaultTestProvider.MODE_COMPLETE)
                fixture.grant()
                try {
                    val pages = fixture.storage.importPdf(
                        fixture.openSource(),
                        "positive-provider.pdf",
                        fixture.sessionId,
                        fixture.application.heavyWorkScheduler,
                    )
                    assertEquals(sha256(source), sha256(fixture.sourceFile().readBytes()))
                    assertEquals(3, pages.size)
                    assertEquals(pages, fixture.storage.importedPdfPageIds(fixture.sessionId))
                    assertEquals(1, providerState(fixture).getInt(KEY_OPENS))
                    assertTrue(fixture.sentinel.readBytes().contentEquals(fixture.sentinelBefore))
                    android.util.Log.i(TAG, "SOURCE_POSITIVE_PASS fixture=${fixture.uri.lastPathSegment} " +
                        "session=${fixture.sessionId.value} sourceSha256=${sha256(source)} pages=${pages.size} providerOpens=1")
                } finally {
                    fixture.revoke()
                    clearProvider(fixture)
                }
            }
        }
    }

    private fun withFixture(label: String, block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val providerContext = instrumentation.context
        val suffix = UUID.randomUUID().toString()
        val databaseName = "${label.take(24)}-$suffix.db"
        val artifactDirectoryName = "${label.take(20)}-$suffix"
        val root = File(targetContext.noBackupFilesDir, artifactDirectoryName).apply { mkdirs() }
        val sentinel = File(root, "unrelated-sentinel.bin").apply { writeBytes(byteArrayOf(0x51, 0x22, 0x73)) }
        val fixture = Fixture(
            targetContext = targetContext,
            providerContext = providerContext,
            application = targetContext.applicationContext as DocScanApplication,
            databaseName = databaseName,
            artifactDirectoryName = artifactDirectoryName,
            uri = Uri.parse("content://${PdfSourceFaultTestProvider.AUTHORITY}/pdf/$suffix"),
            sessionId = ImportSessionId.new(),
            sentinel = sentinel,
            sentinelBefore = sentinel.readBytes(),
            storage = ProductStorage(targetContext, databaseName, artifactDirectoryName),
        )
        try {
            block(fixture)
        } finally {
            fixture.revoke()
            runCatching { fixture.storage.close() }
            targetContext.deleteDatabase(databaseName)
            root.deleteRecursively()
            runCatching { fixture.providerContext.deleteSharedPreferences("pdf_source_fault_provider") }
        }
    }

    private fun configureProvider(fixture: Fixture, bytes: ByteArray, mode: String, split: Int = 64) {
        val extras = Bundle().apply {
            putByteArray(KEY_BYTES, bytes)
            putString(KEY_MODE, mode)
            putInt(KEY_SPLIT, split)
        }
        val response = fixture.providerContext.contentResolver.call(
            PdfSourceFaultTestProvider.AUTHORITY,
            "configure",
            fixture.uri.lastPathSegment,
            extras,
        )
        assertTrue("Provider fixture must configure", response?.getBoolean(KEY_OK) == true)
    }

    private fun providerState(fixture: Fixture): Bundle = requireNotNull(
        fixture.providerContext.contentResolver.call(
            PdfSourceFaultTestProvider.AUTHORITY, "state", fixture.uri.lastPathSegment, null,
        ),
    )

    private fun awaitProviderState(fixture: Fixture, predicate: (Bundle) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            val state = providerState(fixture)
            if (predicate(state)) return
            Thread.sleep(10)
        }
        throw AssertionError("Provider did not reach expected state; observed=${providerState(fixture).getString(KEY_OUTCOME)}")
    }

    private fun releaseProvider(fixture: Fixture) {
        fixture.providerContext.contentResolver.call(
            PdfSourceFaultTestProvider.AUTHORITY, "release", fixture.uri.lastPathSegment, null,
        )
    }

    private fun clearProvider(fixture: Fixture) {
        fixture.providerContext.contentResolver.call(
            PdfSourceFaultTestProvider.AUTHORITY, "clear", fixture.uri.lastPathSegment, null,
        )
    }

    private fun assertFreshOpenDenied(fixture: Fixture) {
        var denied = false
        try {
            fixture.targetContext.contentResolver.openInputStream(fixture.uri)?.use { it.read() }
        } catch (_: SecurityException) {
            denied = true
        } catch (_: java.io.FileNotFoundException) {
            denied = true
        }
        assertTrue("Revoked target URI grant must deny a new provider open", denied)
    }

    private fun assertNoOwnedSourceState(fixture: Fixture) {
        SQLiteDatabase.openDatabase(
            fixture.targetContext.getDatabasePath(fixture.databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM PdfImportPublicationIntent WHERE import_session_id = ?", fixture.sessionId.value))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", fixture.sessionId.value))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", fixture.sessionId.value))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM Page"))
            android.util.Log.i(TAG, "NO_OWNED_SOURCE_STATE session=${fixture.sessionId.value} intents=0 artifacts=0 sessions=0 pages=0 " +
                "finalExists=${fixture.sourceFile().exists()} stagingOwned=${File(fixture.targetContext.noBackupFilesDir, "${fixture.artifactDirectoryName}/staging")
                    .listFiles().orEmpty().count { it.name.startsWith(fixture.sessionId.value) }}")
        }
        assertFalse("No final source object may remain", fixture.sourceFile().exists())
        val staging = File(fixture.targetContext.noBackupFilesDir, "${fixture.artifactDirectoryName}/staging")
        assertTrue("No ID-prefixed partial staging object may remain", staging.listFiles().orEmpty().none {
            it.name.startsWith(fixture.sessionId.value)
        })
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String, vararg args: String): Long =
        db.rawQuery(sql, args).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun createPdfBytes(pageCount: Int): ByteArray = ByteArrayOutputStream().use { output ->
        PDDocument().use { document ->
            repeat(pageCount) { document.addPage(PDPage()) }
            document.save(output)
        }
        output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Fixture(
        val targetContext: Context,
        val providerContext: Context,
        val application: DocScanApplication,
        val databaseName: String,
        val artifactDirectoryName: String,
        val uri: Uri,
        val sessionId: ImportSessionId,
        val sentinel: File,
        val sentinelBefore: ByteArray,
        var storage: ProductStorage,
    ) {
        fun grant() {
            val targets = Bundle().apply {
                putStringArray("target_packages", arrayOf(targetContext.packageName, providerContext.packageName))
            }
            val grant = providerContext.contentResolver.call(
                PdfSourceFaultTestProvider.AUTHORITY, "grant", uri.lastPathSegment, targets,
            )
            assertTrue("Provider must grant this randomized URI to the app/test package", grant?.getBoolean(KEY_OK) == true)
            assertEquals(PackageManager.PERMISSION_GRANTED,
                targetContext.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }

        fun revoke() {
            providerContext.contentResolver.call(
                PdfSourceFaultTestProvider.AUTHORITY, "revoke", uri.lastPathSegment, null,
            )
        }

        fun openSource() = targetContext.contentResolver.openInputStream(uri)
            ?: throw java.io.FileNotFoundException("Test provider returned no source stream")

        fun sourceFile() = File(targetContext.noBackupFilesDir,
            "$artifactDirectoryName/objects/${sessionId.value.take(2)}/${sessionId.value}.pdf")
    }

    private sealed interface ImportResult {
        data class Success(val pageIds: List<app.docscan.core.model.PageId>) : ImportResult
        data class Failure(val cause: Exception) : ImportResult
    }

    companion object {
        private const val TAG = "PdfImportProviderRevoke"
        private const val KEY_BYTES = "bytes"
        private const val KEY_MODE = "mode"
        private const val KEY_SPLIT = "split"
        private const val KEY_OK = "ok"
        private const val KEY_OPENS = "opens"
        private const val KEY_BYTES_WRITTEN = "bytes_written"
        private const val KEY_PREFIX_WRITTEN = "prefix_written"
        private const val KEY_OUTCOME = "outcome"
    }
}
