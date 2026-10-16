package app.docscan.android

import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.model.ImportSessionId
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageImportRecoveryInstrumentedTest {
    @Test
    fun jpegAndPngImportsRegisterExactBytesAndMimeProvenance() {
        withFixture("image-positive") { fixture ->
            listOf("image/jpeg" to "jpg", "image/png" to "png").forEach { (mime, extension) ->
                val bytes = imageBytes(if (extension == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG)
                val session = ImportSessionId.new()
                val page = fixture.storage.importImage(bytes.inputStream(), mime, "source.$extension", session)
                val storedPath = scalarString(fixture.context, fixture.database, """
                    SELECT ArtifactRecord.relative_storage_path FROM ArtifactRecord
                    JOIN ImageVersion ON ImageVersion.artifact_id = ArtifactRecord.artifact_id
                    WHERE ImageVersion.image_version_id = ?
                """.trimIndent(), page.imageVersionId.value)
                assertEquals(bytes.size.toLong(), fixture.artifactRoot.resolve(storedPath).length())
                assertEquals(sha256(bytes), sha256(fixture.artifactRoot.resolve(storedPath).readBytes()))
                assertEquals("$mime:source.$extension", scalarString(fixture.context, fixture.database,
                    "SELECT source_provenance FROM Page WHERE page_id = ?", page.pageId.value))
                assertEquals("COMPLETED", scalarString(fixture.context, fixture.database,
                    "SELECT state FROM ImportSession WHERE import_session_id = ?", session.value))
                assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", session.value))
            }
            assertEquals(2, fixture.storage.workspaceProjection().pages.size)
        }
    }

    @Test
    fun providerStreamIsOpenedOnlyAfterIntentPersistence() {
        withFixture("image-provider-open") { fixture ->
            val session = ImportSessionId.new()
            var wasOpened = false
            val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
            val imported = fixture.storage.importImageFromProvider({
                wasOpened = true
                assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", session.value))
                assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ArtifactRecord"))
                bytes.inputStream()
            }, "image/jpeg", "provider.jpg", session)
            assertTrue(wasOpened)
            assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM Page WHERE page_id = ?", imported.pageId.value))

            val failedSession = ImportSessionId.new()
            assertThrows(IllegalStateException::class.java) {
                fixture.storage.importImageFromProvider({
                    assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", failedSession.value))
                    throw IllegalStateException("provider open denied")
                }, "image/jpeg", "denied.jpg", failedSession)
            }
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", failedSession.value))
            assertEquals(1, fixture.storage.workspaceProjection().pages.size)
        }
    }

    @Test
    fun grantedProviderFailureDuringImageCopyLeavesNoOwnedPartialState() {
        repeat(2) { repetition ->
            withFixture("image-provider-failure-$repetition") { fixture ->
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val providerContext = instrumentation.context
                val providerId = UUID.randomUUID().toString()
                val uri = Uri.parse("content://${PdfSourceFaultTestProvider.AUTHORITY}/pdf/$providerId")
                val inputBytes = imageBytes(Bitmap.CompressFormat.JPEG)
                val configure = Bundle().apply {
                    putByteArray("bytes", inputBytes)
                    putString("mime_type", "image/jpeg")
                    putString("mode", PdfSourceFaultTestProvider.MODE_FAIL_AFTER_PREFIX)
                    putInt("split", 64)
                }
                val configured = providerContext.contentResolver.call(
                    PdfSourceFaultTestProvider.AUTHORITY, "configure", providerId, configure,
                )
                assertTrue(configured?.getBoolean("ok") == true)
                val grant = Bundle().apply {
                    putStringArray("target_packages", arrayOf(fixture.context.packageName, providerContext.packageName))
                }
                val granted = providerContext.contentResolver.call(
                    PdfSourceFaultTestProvider.AUTHORITY, "grant", providerId, grant,
                )
                assertTrue(granted?.getBoolean("ok") == true)
                assertEquals(PackageManager.PERMISSION_GRANTED,
                    fixture.context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))

                val session = ImportSessionId.new()
                var ownedArtifactId: String? = null
                val sentinel = File(fixture.artifactRoot, "unrelated-sentinel.bin").apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(0x42, 0x17, 0x6a))
                }
                val sentinelBytes = sentinel.readBytes()
                try {
                    val importResult = CompletableFuture<Exception?>()
                    Thread({
                        try {
                            fixture.storage.importImageFromProvider({
                            ownedArtifactId = scalarString(fixture.context, fixture.database,
                                "SELECT artifact_id FROM ImageImportPublicationIntent WHERE import_session_id = ?", session.value)
                            fixture.context.contentResolver.openInputStream(uri)
                            }, "image/jpeg", "provider-failure.jpg", session)
                            importResult.complete(null)
                        } catch (failure: Exception) {
                            importResult.complete(failure)
                        }
                    }, "image-provider-copy-failure-$repetition").start()
                    awaitProviderState(providerContext, providerId) { it.getBoolean("prefix_written") }
                    assertTrue("Intent must exist before the real provider stream is opened", ownedArtifactId != null)
                    var state = providerState(providerContext, providerId)
                    assertEquals(1, state?.getInt("opens"))
                    assertEquals(64, state?.getInt("bytes_written"))
                    assertEquals("WAITING_AFTER_PREFIX", state?.getString("outcome"))
                    providerContext.contentResolver.call(PdfSourceFaultTestProvider.AUTHORITY, "release", providerId, null)
                    assertTrue("Provider mid-copy error must fail the import",
                        importResult.get(30, TimeUnit.SECONDS) is Exception)
                    awaitProviderState(providerContext, providerId) { it.getString("outcome") == "FAILED_AFTER_PREFIX" }
                    state = providerState(providerContext, providerId)
                    assertEquals("FAILED_AFTER_PREFIX", state?.getString("outcome"))
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", session.value))
                    val sourceId = requireNotNull(ownedArtifactId)
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", sourceId))
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", session.value))
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM Page WHERE import_session_id = ?", session.value))
                    assertFalse(fixture.artifactRoot.resolve("objects/${sourceId.take(2)}/$sourceId.jpg").exists())
                    assertTrue(File(fixture.artifactRoot, "staging").listFiles().orEmpty().none { it.name.startsWith("$sourceId-") })
                    assertTrue(sentinel.readBytes().contentEquals(sentinelBytes))

                    fixture.storage.close()
                    fixture.storage = ProductStorage(fixture.context, fixture.database, fixture.artifactDirectory)
                    assertFalse(fixture.storage.startupIntegrityBlocked)
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", session.value))
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", sourceId))
                    assertTrue(sentinel.readBytes().contentEquals(sentinelBytes))
                } finally {
                    providerContext.contentResolver.call(PdfSourceFaultTestProvider.AUTHORITY, "release", providerId, null)
                    providerContext.contentResolver.call(PdfSourceFaultTestProvider.AUTHORITY, "revoke", providerId, null)
                    providerContext.contentResolver.call(PdfSourceFaultTestProvider.AUTHORITY, "clear", providerId, null)
                }
            }
        }
    }

    @Test
    fun invalidSourceAndChangedRetryPreserveOnlyOriginalCommittedImage() {
        withFixture("image-source-failure") { fixture ->
            val session = ImportSessionId.new()
            val original = imageBytes(Bitmap.CompressFormat.JPEG, 0xff335577.toInt())
            val committed = fixture.storage.importImage(original.inputStream(), "image/jpeg", "same.jpg", session)
            assertThrows(Exception::class.java) {
                fixture.storage.importImage(imageBytes(Bitmap.CompressFormat.JPEG, 0xffaa2200.toInt()).inputStream(), "image/jpeg", "same.jpg", session)
            }
            assertEquals(1, fixture.storage.workspaceProjection().pages.size)
            assertEquals(committed.pageId.value, scalarString(fixture.context, fixture.database,
                "SELECT page_id FROM Page WHERE import_session_id = ?", session.value))
            val sourcePath = scalarString(fixture.context, fixture.database, """
                SELECT ArtifactRecord.relative_storage_path FROM ArtifactRecord
                JOIN ImageVersion ON ImageVersion.artifact_id = ArtifactRecord.artifact_id
                WHERE ImageVersion.image_version_id = ?
            """.trimIndent(), committed.imageVersionId.value)
            assertEquals(sha256(original), sha256(fixture.artifactRoot.resolve(sourcePath).readBytes()))

            val invalidSession = ImportSessionId.new()
            assertThrows(IllegalArgumentException::class.java) {
                fixture.storage.importImage("not an image".toByteArray().inputStream(), "image/jpeg", "bad.jpg", invalidSession)
            }
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", invalidSession.value))
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", invalidSession.value))
            assertEquals(1, fixture.storage.workspaceProjection().pages.size)
        }
    }

    @Test
    fun oldOpenImageSessionRecoversWithDeterministicPageOnRepeatedStartup() {
        withFixture("image-legacy-recovery") { fixture ->
            val session = ImportSessionId.new()
            val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
            val captured = fixture.storage.importImage(bytes.inputStream(), "image/jpeg", "legacy.jpg", session)
            val pageId = deterministicPageId(session.value)
            val versionId = deterministicVersionId(session.value)
            val artifactPath = scalarString(fixture.context, fixture.database, """
                SELECT ArtifactRecord.relative_storage_path FROM ArtifactRecord
                JOIN ImageVersion ON ImageVersion.artifact_id = ArtifactRecord.artifact_id
                WHERE ImageVersion.image_version_id = ?
            """.trimIndent(), captured.imageVersionId.value)
            fixture.sql { db ->
                db.delete("Page", "import_session_id = ?", arrayOf(session.value))
                db.delete("ImageVersion", "image_version_id = ?", arrayOf(captured.imageVersionId.value))
                db.delete("ArtifactReference", "artifact_id = ? AND ref_kind = 'IMAGE_VERSION'", arrayOf(captured.artifactId.value))
                db.execSQL("UPDATE Workspace SET active_page_id = NULL")
                db.execSQL("UPDATE ImportSession SET state = 'OPEN' WHERE import_session_id = ?", arrayOf(session.value))
                db.execSQL("INSERT INTO ArtifactReference(artifact_id, ref_kind, ref_owner_id, protection_class) VALUES (?, 'IMPORT_SESSION', ?, 'RECOVERY')",
                    arrayOf(captured.artifactId.value, session.value))
            }
            assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ? AND state = 'OPEN'", session.value))
            fixture.storage.close()
            fixture.storage = ProductStorage(fixture.context, fixture.database, fixture.artifactDirectory)
            assertFalse(fixture.storage.startupIntegrityBlocked)
            assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM Page WHERE page_id = ?", pageId))
            assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM ImageVersion WHERE image_version_id = ?", versionId))
            assertEquals(sha256(bytes), sha256(fixture.artifactRoot.resolve(artifactPath).readBytes()))
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", session.value))
            fixture.storage.close()
            fixture.storage = ProductStorage(fixture.context, fixture.database, fixture.artifactDirectory)
            assertFalse(fixture.storage.startupIntegrityBlocked)
            assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM Page WHERE import_session_id = ?", session.value))
            assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ? AND state = 'COMPLETED'", session.value))
        }
    }

    @Test
    fun failureImmediatelyBeforeSqlCommitRollsBackWholeMetadataTransaction() {
        withFixture("image-sql-rollback") { fixture ->
            val session = ImportSessionId.new()
            fixture.storage.imageImportTransactionBoundaryObserverForTest = { throw IllegalStateException("simulated commit interruption") }
            assertThrows(IllegalStateException::class.java) {
                fixture.storage.importImage(imageBytes(Bitmap.CompressFormat.PNG).inputStream(), "image/png", "rollback.png", session)
            }
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ArtifactRecord"))
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImportSession"))
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM Page"))
            assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent"))
            assertTrue(fixture.artifactRoot.walkTopDown().none { it.isFile && it.extension == "png" })
        }
    }

    @Test
    fun invalidLegacyOpenSessionsFailClosedWithoutDeletingDiagnosticState() {
        listOf(
            "missing-file", "corrupt-file", "malformed-context", "missing-mime",
            "mismatched-display-name", "foreign-reference", "extra-reference",
        ).forEach { scenario ->
            withFixture("image-legacy-$scenario") { fixture ->
                val snapshot = seedLegacyOpenImageSession(fixture)
                val privateFile = fixture.artifactRoot.resolve(snapshot.relativePath)
                when (scenario) {
                    "missing-file" -> assertTrue(privateFile.delete())
                    "corrupt-file" -> privateFile.writeBytes("corrupt-source-preserved".toByteArray())
                    "malformed-context" -> fixture.sql { db ->
                        db.execSQL("UPDATE ImportSession SET frozen_operation_parameters = ? WHERE import_session_id = ?",
                            arrayOf("{\"displayName\":\"legacy.jpg\",\"mimeType\":\"image/jpeg\"", snapshot.sessionId.value))
                    }
                    "missing-mime" -> fixture.sql { db ->
                        db.execSQL("UPDATE ImportSession SET frozen_operation_parameters = ? WHERE import_session_id = ?",
                            arrayOf("{\"displayName\":\"legacy.jpg\"}", snapshot.sessionId.value))
                    }
                    "mismatched-display-name" -> fixture.sql { db ->
                        db.execSQL("UPDATE ImportSession SET frozen_operation_parameters = ? WHERE import_session_id = ?",
                            arrayOf("{\"displayName\":\"different.jpg\",\"mimeType\":\"image/jpeg\"}", snapshot.sessionId.value))
                    }
                    "foreign-reference" -> fixture.sql { db ->
                        db.delete("ArtifactReference", "artifact_id = ? AND ref_kind = 'IMPORT_SESSION'", arrayOf(snapshot.artifactId))
                        db.execSQL("INSERT INTO ArtifactReference(artifact_id, ref_kind, ref_owner_id, protection_class) VALUES (?, 'IMPORT_SESSION', ?, 'RECOVERY')",
                            arrayOf(snapshot.artifactId, "foreign-owner"))
                    }
                    "extra-reference" -> fixture.sql { db ->
                        db.execSQL("INSERT INTO ArtifactReference(artifact_id, ref_kind, ref_owner_id, protection_class) VALUES (?, 'UNKNOWN_CONTEXT', ?, 'RECOVERY')",
                            arrayOf(snapshot.artifactId, "extra-owner"))
                    }
                }
                val bytesBeforeRestart = if (privateFile.exists()) privateFile.readBytes() else null
                fixture.storage.close()
                val startup = runCatching { ProductStorage(fixture.context, fixture.database, fixture.artifactDirectory) }
                val blockedStorage = startup.getOrNull()
                val diagnosedByStartupGate = scenario == "foreign-reference" || scenario == "extra-reference"
                if (diagnosedByStartupGate && blockedStorage != null) {
                    val expectedFinding = if (scenario == "foreign-reference") {
                        "IMPORT_SESSION_OWNER_MISSING"
                    } else {
                        "UNKNOWN_REFERENCE_KIND"
                    }
                    assertTrue("$scenario owner-integrity finding must block recovery", blockedStorage.startupIntegrityBlocked)
                    val actualFindings = blockedStorage.artifactReferenceIntegrityFindingsAtStartup.map { it.code }
                    assertTrue(
                        "$scenario must be retained as a startup diagnostic; findings=$actualFindings",
                        expectedFinding in actualFindings,
                    )
                } else {
                    assertTrue("$scenario legacy state must block recovery", startup.isFailure)
                }
                try {
                    assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ? AND state = 'OPEN'", snapshot.sessionId.value))
                    assertEquals(1L, fixture.scalar("SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ? AND state = 'COMMITTED'", snapshot.artifactId))
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM Page WHERE import_session_id = ?", snapshot.sessionId.value))
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageVersion WHERE page_id = ?", deterministicPageId(snapshot.sessionId.value)))
                    assertEquals(0L, fixture.scalar("SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", snapshot.sessionId.value))
                    val refOwner = scalarStringOrNull(
                        fixture.context,
                        fixture.database,
                        "SELECT ref_owner_id FROM ArtifactReference WHERE artifact_id = ? AND ref_kind = 'IMPORT_SESSION'",
                        snapshot.artifactId,
                    )
                    assertEquals(if (scenario == "foreign-reference") "foreign-owner" else snapshot.sessionId.value, refOwner)
                    assertEquals(
                        if (scenario == "extra-reference") 2L else 1L,
                        fixture.scalar("SELECT COUNT(*) FROM ArtifactReference WHERE artifact_id = ?", snapshot.artifactId),
                    )
                    if (bytesBeforeRestart == null) assertFalse(privateFile.exists())
                    else assertTrue("$scenario source bytes must be preserved", bytesBeforeRestart.contentEquals(privateFile.readBytes()))
                } finally {
                    blockedStorage?.close()
                }
            }
        }
    }

    private fun seedLegacyOpenImageSession(fixture: Fixture): LegacySnapshot {
        val session = ImportSessionId.new()
        val bytes = imageBytes(Bitmap.CompressFormat.JPEG)
        val captured = fixture.storage.importImage(bytes.inputStream(), "image/jpeg", "legacy.jpg", session)
        val relativePath = scalarString(fixture.context, fixture.database, """
            SELECT ArtifactRecord.relative_storage_path FROM ArtifactRecord
            JOIN ImageVersion ON ImageVersion.artifact_id = ArtifactRecord.artifact_id
            WHERE ImageVersion.image_version_id = ?
        """.trimIndent(), captured.imageVersionId.value)
        fixture.sql { db ->
            db.delete("Page", "import_session_id = ?", arrayOf(session.value))
            db.delete("ImageVersion", "image_version_id = ?", arrayOf(captured.imageVersionId.value))
            db.delete("ArtifactReference", "artifact_id = ? AND ref_kind = 'IMAGE_VERSION'", arrayOf(captured.artifactId.value))
            db.execSQL("UPDATE Workspace SET active_page_id = NULL")
            db.execSQL("UPDATE ImportSession SET state = 'OPEN' WHERE import_session_id = ?", arrayOf(session.value))
            db.execSQL("INSERT INTO ArtifactReference(artifact_id, ref_kind, ref_owner_id, protection_class) VALUES (?, 'IMPORT_SESSION', ?, 'RECOVERY')",
                arrayOf(captured.artifactId.value, session.value))
        }
        return LegacySnapshot(session, captured.artifactId.value, relativePath)
    }

    private fun withFixture(label: String, action: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val suffix = UUID.randomUUID().toString()
        val database = "$label-$suffix.db"
        val artifactDirectory = "$label-artifacts-$suffix"
        val fixture = Fixture(context, database, artifactDirectory, File(context.noBackupFilesDir, artifactDirectory))
        fixture.storage = ProductStorage(context, database, artifactDirectory)
        try { action(fixture) } finally {
            runCatching { fixture.storage.close() }
            context.deleteDatabase(database)
            fixture.artifactRoot.deleteRecursively()
        }
    }

    private fun imageBytes(format: Bitmap.CompressFormat, color: Int = 0xff225588.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try { ByteArrayOutputStream().use { out -> check(bitmap.compress(format, 92, out)); out.toByteArray() } }
        finally { bitmap.recycle() }
    }

    private fun deterministicPageId(sessionId: String) = UUID.nameUUIDFromBytes("$sessionId:0:page".toByteArray()).toString()
    private fun deterministicVersionId(sessionId: String) = UUID.nameUUIDFromBytes("$sessionId:0:image-version".toByteArray()).toString()

    private fun scalarString(context: Context, database: String, query: String, arg: String): String =
        SQLiteDatabase.openDatabase(context.getDatabasePath(database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(query, arrayOf(arg)).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
        }

    private fun scalarStringOrNull(context: Context, database: String, query: String, arg: String): String? =
        SQLiteDatabase.openDatabase(context.getDatabasePath(database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(query, arrayOf(arg)).use { cursor -> if (!cursor.moveToFirst()) null else cursor.getString(0) }
        }

    private fun providerState(context: Context, fixtureId: String): Bundle? = context.contentResolver.call(
        PdfSourceFaultTestProvider.AUTHORITY, "state", fixtureId, null,
    )

    private fun awaitProviderState(context: Context, fixtureId: String, predicate: (Bundle) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val state = requireNotNull(providerState(context, fixtureId))
            if (predicate(state)) return
            Thread.sleep(10)
        }
        throw AssertionError("Provider did not reach expected state: ${providerState(context, fixtureId)?.getString("outcome")}")
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Fixture(val context: Context, val database: String, val artifactDirectory: String, val artifactRoot: File) {
        lateinit var storage: ProductStorage
        fun scalar(query: String, arg: String? = null): Long = sql { db ->
            db.rawQuery(query, arg?.let { arrayOf(it) }).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
        }
        fun <T> sql(action: (SQLiteDatabase) -> T): T {
            return SQLiteDatabase.openDatabase(context.getDatabasePath(database).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(action)
        }
    }

    private data class LegacySnapshot(val sessionId: ImportSessionId, val artifactId: String, val relativePath: String)
}
