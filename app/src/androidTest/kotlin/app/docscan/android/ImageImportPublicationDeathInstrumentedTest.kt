package app.docscan.android

import android.Manifest
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.docscan.core.model.ImportSessionId
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-driven process termination after durable image bytes but before SQL registration. */
@RunWith(AndroidJUnit4::class)
class ImageImportPublicationDeathInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun sourcePublicationDeathResumeProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val boundary = args.getString("boundary") ?: "SOURCE_BYTES_PUBLISHED"
        require(boundary in setOf("SOURCE_BYTES_PUBLISHED", "SQL_TRANSACTION_OPEN"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        val names = fixture(context, fixtureId)
        when (phase) {
            "checkpoint" -> checkpoint(context, application, names, fixtureId, boundary)
            "resume" -> resume(context, application, names, fixtureId, boundary)
            else -> throw AssertionError("Unknown image-import phase: $phase")
        }
    }

    private fun checkpoint(
        context: android.content.Context,
        application: DocScanApplication,
        names: Fixture,
        fixtureId: String,
        boundary: String,
    ) {
        check(!context.getDatabasePath(names.database).exists())
        check(!names.artifacts.exists())
        names.root.mkdirs()
        val bytes = createJpeg()
        val source = names.root.resolve("source.jpg")
        source.writeBytes(bytes)
        val originalOverride = application.productStorageOverrideForTest
        val originalProvider = application.productStorageProviderForTest
        val storage = ProductStorage(context, names.database, names.artifacts.name)
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage
        storage.imageImportSourcePublicationBoundaryObserverForTest = { intent, artifact ->
            if (boundary == "SOURCE_BYTES_PUBLISHED") {
                check(intent.importSessionId.value == fixtureId)
                writeCheckpoint(context, names, fixtureId, boundary, source, artifact.artifactId.value,
                    artifact.relativePath, artifact.sizeBytes, artifact.sha256)
                awaitHostKill("image source publication")
            }
        }
        storage.imageImportTransactionBoundaryObserverForTest = { command ->
            if (boundary == "SQL_TRANSACTION_OPEN") {
                writeCheckpoint(context, names, fixtureId, boundary, source, command.sourceArtifact.artifactId.value,
                    command.sourceArtifact.relativePath, command.sourceArtifact.sizeBytes, command.sourceArtifact.sha256)
                awaitHostKill("image SQL transaction")
            }
        }
        try {
            storage.importImage(source.inputStream(), "image/jpeg", source.name, ImportSessionId.parse(fixtureId))
            throw AssertionError("Host should force-stop the process before import returns")
        } finally {
            application.productStorageProviderForTest = originalProvider
            application.productStorageOverrideForTest = originalOverride
        }
    }

    private fun resume(
        context: android.content.Context,
        application: DocScanApplication,
        names: Fixture,
        fixtureId: String,
        boundary: String,
    ) {
        val checkpoint = names.checkpoint.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(fixtureId, checkpoint["fixture"])
        assertEquals(boundary, checkpoint["boundary"])
        val published = names.artifacts.resolve(checkpoint.getValue("artifactRelativePath"))
        assertTrue(published.isFile)
        assertEquals(checkpoint.getValue("artifactSize").toLong(), published.length())
        assertEquals(checkpoint.getValue("artifactSha256"), sha256(published))
        SQLiteDatabase.openDatabase(context.getDatabasePath(names.database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", fixtureId))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", checkpoint.getValue("artifactId")))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", fixtureId))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM Page WHERE import_session_id = ?", fixtureId))
        }

        val originalOverride = application.productStorageOverrideForTest
        val originalProvider = application.productStorageProviderForTest
        val storage = ProductStorage(context, names.database, names.artifacts.name)
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            assertFalse("Source intent recovery must preserve app startup", storage.startupIntegrityBlocked)
            assertEquals(1, storage.workspaceProjection().pages.size)
            assertEquals(checkpoint.getValue("sourceSha256"), sha256(names.root.resolve("source.jpg")))
            assertEquals(checkpoint.getValue("artifactSha256"), sha256(published))
            assertEquals(1L, imageStateCount(context, names.database, fixtureId, "COMPLETED"))
            assertEquals(1L, imagePageCount(context, names.database, fixtureId))
            assertEquals(0L, imageIntentCount(context, names.database, fixtureId))

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            SystemClock.sleep(500)
            storage.close()
            val secondStartup = ProductStorage(context, names.database, names.artifacts.name)
            try {
                assertFalse(secondStartup.startupIntegrityBlocked)
                assertEquals(1, secondStartup.workspaceProjection().pages.size)
                assertEquals(1L, imagePageCount(context, names.database, fixtureId))
                assertEquals(0L, imageIntentCount(context, names.database, fixtureId))
            } finally {
                secondStartup.close()
            }
            Log.i(TAG, "SOURCE_RESUME_PASS fixture=$fixtureId boundary=$boundary pages=1 artifactSha256=${checkpoint.getValue("artifactSha256")}")
        } finally {
            scenario?.close()
            application.productStorageProviderForTest = originalProvider
            application.productStorageOverrideForTest = originalOverride
            runCatching { storage.close() }
            context.deleteDatabase(names.database)
            names.artifacts.deleteRecursively()
            names.root.deleteRecursively()
        }
    }

    private fun writeCheckpoint(
        context: android.content.Context,
        names: Fixture,
        fixtureId: String,
        boundary: String,
        source: File,
        artifactId: String,
        relativePath: String,
        size: Long,
        hash: String,
    ) {
        val privateFile = names.artifacts.resolve(relativePath)
        assertTrue(privateFile.isFile)
        assertEquals(size, privateFile.length())
        assertEquals(hash, sha256(privateFile))
        SQLiteDatabase.openDatabase(context.getDatabasePath(names.database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1L, scalar(db, "SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", fixtureId))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", artifactId))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", fixtureId))
            assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM Page WHERE import_session_id = ?", fixtureId))
        }
        names.checkpoint.writeText(listOf(
            "fixture=$fixtureId",
            "boundary=$boundary",
            "sourceSha256=${sha256(source)}",
            "artifactId=$artifactId",
            "artifactRelativePath=$relativePath",
            "artifactSize=$size",
            "artifactSha256=$hash",
        ).joinToString("\n") + "\n")
        Log.i(TAG, "SOURCE_CHECKPOINT fixture=$fixtureId boundary=$boundary artifact=$artifactId")
    }

    private fun awaitHostKill(boundary: String) {
        check(CountDownLatch(1).await(10, TimeUnit.MINUTES)) { "Host did not terminate app at $boundary checkpoint" }
    }

    private fun createJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun fixture(context: android.content.Context, id: String): Fixture {
        require(id.matches(Regex("[0-9a-fA-F-]{36}")))
        val root = File(context.noBackupFilesDir, "image-import-death-$id")
        return Fixture("image-import-$id.db", File(context.noBackupFilesDir, "image-artifacts-$id"), root, File(root, "checkpoint.txt"))
    }

    private fun scalar(db: SQLiteDatabase, sql: String, arg: String): Long =
        db.rawQuery(sql, arrayOf(arg)).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun imageStateCount(context: android.content.Context, database: String, id: String, state: String): Long =
        SQLiteDatabase.openDatabase(context.getDatabasePath(database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            scalar(it, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ? AND state = '$state'", id)
        }

    private fun imagePageCount(context: android.content.Context, database: String, id: String): Long =
        SQLiteDatabase.openDatabase(context.getDatabasePath(database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            scalar(it, "SELECT COUNT(*) FROM Page WHERE import_session_id = ?", id)
        }

    private fun imageIntentCount(context: android.content.Context, database: String, id: String): Long =
        SQLiteDatabase.openDatabase(context.getDatabasePath(database).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            scalar(it, "SELECT COUNT(*) FROM ImageImportPublicationIntent WHERE import_session_id = ?", id)
        }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private data class Fixture(val database: String, val artifacts: File, val root: File, val checkpoint: File)

    private companion object { const val TAG = "ImageImportPublicationDeath" }
}
