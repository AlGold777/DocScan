package app.docscan.android

import android.Manifest
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.docscan.core.model.ImportSessionId
import app.docscan.core.store.DurableImageArtifact
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-driven process termination at PDF image publication boundaries. */
@RunWith(AndroidJUnit4::class)
class PdfImportPublicationDeathInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun publicationBoundaryResumeProbe() {
        val args = InstrumentationRegistry.getArguments()
        val fixtureId = args.getString("fixtureId") ?: return
        val phase = args.getString("phase") ?: return
        val boundary = args.getString("boundary") ?: "BYTES_PUBLISHED"
        require(boundary in setOf(
            "BYTES_PUBLISHED", "METADATA_STAGED", "TRANSACTION_OPEN",
            "SOURCE_BYTES_PUBLISHED", "SOURCE_INVALID_BYTES_PUBLISHED",
        ))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        when (phase) {
            "checkpoint" -> checkpoint(context, application, fixtureId, boundary)
            "resume" -> resume(context, application, fixtureId, boundary)
            else -> throw AssertionError("Unknown publication-death phase: $phase")
        }
    }

    private fun checkpoint(
        context: android.content.Context,
        application: DocScanApplication,
        fixtureId: String,
        boundary: String,
    ) {
        val names = fixtureNames(context, fixtureId)
        check(!context.getDatabasePath(names.database).exists()) { "Fixture database already exists" }
        check(!names.artifacts.exists()) { "Fixture artifact root already exists" }
        check(!names.fixture.exists()) { "Fixture directory already exists" }
        names.fixture.mkdirs()
        val sourcePdf = names.fixture.resolve("source.pdf")
        if (boundary == "SOURCE_INVALID_BYTES_PUBLISHED") {
            sourcePdf.writeBytes("not a PDF document".toByteArray())
        } else {
            createSourcePdf(sourcePdf)
        }
        val sourceHash = sha256(sourcePdf)
        val appOverride = application.productStorageOverrideForTest
        val providerOverride = application.productStorageProviderForTest
        val storage = ProductStorage(context, names.database, names.artifacts.name)
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage
        storage.pdfImportSourcePublicationBoundaryObserverForTest = { intent, artifact ->
            if (boundary == "SOURCE_BYTES_PUBLISHED" || boundary == "SOURCE_INVALID_BYTES_PUBLISHED") {
                val artifactFile = names.artifacts.resolve(artifact.relativePath)
                assertTrue("The source PDF must be durably published before the checkpoint", artifactFile.isFile)
                assertEquals(artifact.sizeBytes, artifactFile.length())
                assertEquals(artifact.sha256, sha256(artifactFile))
                assertEquals(1L, scalarLong(context, names.database,
                    "SELECT COUNT(*) FROM PdfImportPublicationIntent WHERE import_session_id = ?",
                    fixtureId,
                ))
                assertEquals(null, artifactState(context, names.database, artifact.artifactId.value))
                assertEquals(null, scalarString(context, names.database,
                    "SELECT state FROM ImportSession WHERE import_session_id = ?",
                    fixtureId,
                ))
                val checkpoint = listOf(
                    "fixture=$fixtureId",
                    "boundary=$boundary",
                    "sourceSha256=$sourceHash",
                    "artifactId=${artifact.artifactId.value}",
                    "artifactRelativePath=${artifact.relativePath}",
                    "artifactSize=${artifact.sizeBytes}",
                    "artifactSha256=${artifact.sha256}",
                    "displayName=${intent.displayName}",
                ).joinToString("\n") + "\n"
                writeCheckpoint(names.fixture, checkpoint)
                Log.i(TAG, "SOURCE_CHECKPOINT fixture=$fixtureId boundary=$boundary artifact=${artifact.artifactId.value}")
                check(CountDownLatch(1).await(10, TimeUnit.MINUTES)) { "Host did not terminate app at source-PDF publication checkpoint" }
            }
        }
        storage.pdfImportPublicationBoundaryObserverForTest = { observedBoundary, sourcePageIndex, artifact ->
            if (boundary != "TRANSACTION_OPEN"
                && observedBoundary == boundary
                && sourcePageIndex == PAUSE_BEFORE_PAGE_INDEX
            ) {
                assertEquals("Only the earlier source prefix may be committed at the checkpoint", 1,
                    storage.workspaceProjection().pages.size)
                assertDurablePageArtifact(context, names, artifact, boundary)
                val committed = storage.workspaceProjection().pages
                val checkpoint = buildList {
                    add("fixture=$fixtureId")
                    add("boundary=$boundary")
                    add("sourcePageIndex=$sourcePageIndex")
                    add("sourceSha256=$sourceHash")
                    add("artifactId=${artifact.artifactId.value}")
                    add("artifactRelativePath=${artifact.relativePath}")
                    add("artifactSize=${artifact.sizeBytes}")
                    add("artifactSha256=${artifact.sha256}")
                    add("committedPageCount=${committed.size}")
                    committed.forEach { page -> add("page=${page.pageId.value}|${page.imageVersionId.value}") }
                }.joinToString("\n") + "\n"
                writeCheckpoint(names.fixture, checkpoint)
                Log.i(TAG, "CHECKPOINT fixture=$fixtureId boundary=$boundary source_page=$sourcePageIndex artifact=${artifact.artifactId.value}")
                check(CountDownLatch(1).await(10, TimeUnit.MINUTES)) { "Host did not terminate app at the publication checkpoint" }
            }
        }
        storage.pdfImportPageTransactionBoundaryObserverForTest = { command ->
            if (boundary == "TRANSACTION_OPEN" && command.sourcePageIndex == PAUSE_BEFORE_PAGE_INDEX.toLong()) {
                val artifact = command.imageArtifact
                assertDurablePageArtifact(context, names, artifact, boundary)
                val prefix = deterministicPageIds(fixtureId, 0)
                val checkpoint = listOf(
                    "fixture=$fixtureId",
                    "boundary=$boundary",
                    "sourcePageIndex=${command.sourcePageIndex}",
                    "sourceSha256=$sourceHash",
                    "artifactId=${artifact.artifactId.value}",
                    "artifactRelativePath=${artifact.relativePath}",
                    "artifactSize=${artifact.sizeBytes}",
                    "artifactSha256=${artifact.sha256}",
                    "committedPageCount=1",
                    "page=${prefix.first}|${prefix.second}",
                    "transactionPageId=${command.pageId.value}",
                    "transactionImageVersionId=${command.imageVersionId.value}",
                ).joinToString("\n") + "\n"
                writeCheckpoint(names.fixture, checkpoint)
                Log.i(TAG, "CHECKPOINT fixture=$fixtureId boundary=$boundary source_page=${command.sourcePageIndex} artifact=${artifact.artifactId.value}")
                check(CountDownLatch(1).await(10, TimeUnit.MINUTES)) { "Host did not terminate app inside the PDF page transaction" }
            }
        }
        try {
            storage.importPdf(
                source = sourcePdf.inputStream(),
                displayName = "publication-death-fixture.pdf",
                importSessionId = ImportSessionId.parse(fixtureId),
                scheduler = application.heavyWorkScheduler,
            )
            throw AssertionError("Publication checkpoint returned without a host process kill")
        } finally {
            application.productStorageProviderForTest = providerOverride
            application.productStorageOverrideForTest = appOverride
            runCatching { storage.close() }
            context.deleteDatabase(names.database)
            names.artifacts.deleteRecursively()
            names.fixture.deleteRecursively()
        }
    }

    private fun assertDurablePageArtifact(
        context: android.content.Context,
        names: FixtureNames,
        artifact: DurableImageArtifact,
        boundary: String,
    ) {
        val file = names.artifacts.resolve(artifact.relativePath)
        assertTrue("Published page bytes must exist before the checkpoint", file.isFile)
        assertEquals(artifact.sizeBytes, file.length())
        assertEquals(artifact.sha256, sha256(file))
        artifactState(context, names.database, artifact.artifactId.value).let { state ->
            when (boundary) {
                "BYTES_PUBLISHED" -> assertNull("Metadata must not yet be registered", state)
                "METADATA_STAGED", "TRANSACTION_OPEN" -> assertEquals("STAGED_DURABLE", state)
            }
        }
    }

    private fun resume(
        context: android.content.Context,
        application: DocScanApplication,
        fixtureId: String,
        boundary: String,
    ) {
        val names = fixtureNames(context, fixtureId)
        if (boundary == "SOURCE_BYTES_PUBLISHED" || boundary == "SOURCE_INVALID_BYTES_PUBLISHED") {
            resumeSourcePublication(context, application, fixtureId, boundary, names)
            return
        }
        val checkpoint = names.fixture.resolve("checkpoint.txt").readLines()
            .map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
        assertEquals(fixtureId, checkpoint["fixture"])
        assertEquals(boundary, checkpoint["boundary"])
        assertEquals(PAUSE_BEFORE_PAGE_INDEX.toString(), checkpoint["sourcePageIndex"])
        assertEquals("1", checkpoint["committedPageCount"])
        val expectedPage = checkpoint.getValue("page").split('|')
        assertEquals(2, expectedPage.size)
        val sourceHash = checkpoint.getValue("sourceSha256")

        if (boundary == "TRANSACTION_OPEN") {
            assertTransactionRollback(context, names.database, fixtureId, checkpoint)
            assertEquals(sourceHash, sha256(names.fixture.resolve("source.pdf")))
            val stagedImage = names.artifacts.resolve(checkpoint.getValue("artifactRelativePath"))
            assertTrue("Published transaction image must survive the process kill", stagedImage.isFile)
            assertEquals(checkpoint.getValue("artifactSize").toLong(), stagedImage.length())
            assertEquals(checkpoint.getValue("artifactSha256"), sha256(stagedImage))
        }

        val appOverride = application.productStorageOverrideForTest
        val providerOverride = application.productStorageProviderForTest
        val storage = ProductStorage(context, names.database, names.artifacts.name)
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            assertFalse("Import fixture must pass startup integrity checks", storage.startupIntegrityBlocked)
            assertEquals("The previously committed prefix must survive process death", 1,
                storage.workspaceProjection().pages.size)
            val prefix = storage.workspaceProjection().pages.single()
            assertEquals(expectedPage[0], prefix.pageId.value)
            assertEquals(expectedPage[1], prefix.imageVersionId.value)
            assertEquals(sourceHash, sha256(names.fixture.resolve("source.pdf")))

            scenario = ActivityScenario.launch(ProductActivity::class.java)
            awaitPageCount(scenario, storage, SOURCE_PAGE_COUNT)

            val pages = storage.workspaceProjection().pages
            assertEquals(SOURCE_PAGE_COUNT, pages.size)
            assertEquals(SOURCE_PAGE_COUNT, pages.map { it.pageId }.distinct().size)
            assertEquals(SOURCE_PAGE_COUNT, pages.map { it.imageVersionId }.distinct().size)
            assertEquals(expectedPage[0], pages.first().pageId.value)
            assertEquals(expectedPage[1], pages.first().imageVersionId.value)
            val ids = storage.importedPdfPageIds(ImportSessionId.parse(fixtureId))
            assertEquals(pages.map { it.pageId }, ids)
            assertEquals(SOURCE_PAGE_COUNT, ids.distinct().size)
            val rows = importedArtifactRows(context, names.database, fixtureId)
            assertEquals(SOURCE_PAGE_COUNT, rows.size)
            assertTrue("Every visible imported page must point at a committed artifact", rows.all { it.second == "COMMITTED" })
            val sourceSnapshots = names.artifacts.walkTopDown().filter { it.isFile && it.extension == "pdf" }.toList()
            assertEquals("One private source PDF remains", 1, sourceSnapshots.size)
            assertEquals(sourceHash, sha256(sourceSnapshots.single()))
            assertTrue(storage.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
            assertEquals(SOURCE_PAGE_COUNT, storage.workspaceProjection().pages.size)
            assertEquals("No materialized page artifact may be quarantined", 0, storage.quarantinedArtifactsAtStartup)
            Log.i(TAG, "RESUME_PASS fixture=$fixtureId boundary=$boundary page_ids=${ids.joinToString(",")}")
        } finally {
            scenario?.close()
            application.productStorageProviderForTest = providerOverride
            application.productStorageOverrideForTest = appOverride
            storage.close()
            context.deleteDatabase(names.database)
            names.artifacts.deleteRecursively()
            names.fixture.deleteRecursively()
        }
    }

    private fun resumeSourcePublication(
        context: android.content.Context,
        application: DocScanApplication,
        fixtureId: String,
        boundary: String,
        names: FixtureNames,
    ) {
        val checkpoint = names.fixture.resolve("checkpoint.txt").readLines()
            .map { it.substringBefore('=') to it.substringAfter('=') }.toMap()
        assertEquals(fixtureId, checkpoint["fixture"])
        assertEquals(boundary, checkpoint["boundary"])
        val sourceHash = checkpoint.getValue("sourceSha256")
        assertEquals(sourceHash, sha256(names.fixture.resolve("source.pdf")))
        val published = names.artifacts.resolve(checkpoint.getValue("artifactRelativePath"))
        assertTrue("Published source bytes must survive until startup recovery", published.isFile)
        assertEquals(checkpoint.getValue("artifactSize").toLong(), published.length())
        assertEquals(checkpoint.getValue("artifactSha256"), sha256(published))
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(names.database).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM PdfImportPublicationIntent WHERE import_session_id = ?", fixtureId))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", fixtureId))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", fixtureId))
            assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM Page WHERE import_session_id = ?", fixtureId))
        }

        val appOverride = application.productStorageOverrideForTest
        val providerOverride = application.productStorageProviderForTest
        val storage = ProductStorage(context, names.database, names.artifacts.name)
        application.productStorageProviderForTest = null
        application.productStorageOverrideForTest = storage
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            assertFalse(storage.startupIntegrityBlocked)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(names.database).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM PdfImportPublicationIntent WHERE import_session_id = ?", fixtureId))
                if (boundary == "SOURCE_BYTES_PUBLISHED") {
                    assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ? AND state='COMMITTED'", fixtureId))
                    assertEquals(1L, scalarLong(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ? AND state='OPEN'", fixtureId))
                    assertEquals(sourceHash, sha256(published))
                } else {
                    assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ArtifactRecord WHERE artifact_id = ?", fixtureId))
                    assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM ImportSession WHERE import_session_id = ?", fixtureId))
                    assertEquals(0L, scalarLong(db, "SELECT COUNT(*) FROM Page WHERE import_session_id = ?", fixtureId))
                    assertFalse("Invalid unregistered source must be removed", published.exists())
                }
            }
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            if (boundary == "SOURCE_BYTES_PUBLISHED") {
                awaitPageCount(scenario, storage, SOURCE_PAGE_COUNT)
                val pages = storage.workspaceProjection().pages
                assertEquals(SOURCE_PAGE_COUNT, pages.size)
                assertEquals(SOURCE_PAGE_COUNT, pages.map { it.pageId }.distinct().size)
                assertEquals(SOURCE_PAGE_COUNT, pages.map { it.imageVersionId }.distinct().size)
                assertEquals(SOURCE_PAGE_COUNT, storage.importedPdfPageIds(ImportSessionId.parse(fixtureId)).size)
                assertTrue(storage.resumeIncompletePdfImports(application.heavyWorkScheduler).isEmpty())
                assertEquals(sourceHash, sha256(published))
            } else {
                assertTrue(storage.workspaceProjection().pages.isEmpty())
                assertFalse(published.exists())
            }
            assertEquals("No interrupted source intent may be quarantined as an artifact", 0, storage.quarantinedArtifactsAtStartup)
            Log.i(TAG, "SOURCE_RESUME_PASS fixture=$fixtureId boundary=$boundary")
        } finally {
            scenario?.close()
            application.productStorageProviderForTest = providerOverride
            application.productStorageOverrideForTest = appOverride
            storage.close()
            context.deleteDatabase(names.database)
            names.artifacts.deleteRecursively()
            names.fixture.deleteRecursively()
        }
    }

    private fun artifactState(context: android.content.Context, databaseName: String, artifactId: String): String? {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery("SELECT state FROM ArtifactRecord WHERE artifact_id = ?", arrayOf(artifactId)).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0)
            }
        }
    }

    private fun assertTransactionRollback(
        context: android.content.Context,
        databaseName: String,
        importSessionId: String,
        checkpoint: Map<String, String>,
    ) {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        db.use {
            assertEquals(
                "Only the committed prefix is visible after process death",
                1L,
                scalarLong(it, "SELECT COUNT(*) FROM Page WHERE import_session_id = ?", importSessionId),
            )
            assertEquals(
                "The uncommitted source page must roll back",
                0L,
                scalarLong(
                    it,
                    "SELECT COUNT(*) FROM Page WHERE import_session_id = ? AND source_page_index = ?",
                    importSessionId,
                    PAUSE_BEFORE_PAGE_INDEX.toString(),
                ),
            )
            assertEquals(
                0L,
                scalarLong(it, "SELECT COUNT(*) FROM ImageVersion WHERE image_version_id = ?", checkpoint.getValue("transactionImageVersionId")),
            )
            assertEquals(
                0L,
                scalarLong(it, "SELECT COUNT(*) FROM Page WHERE page_id = ?", checkpoint.getValue("transactionPageId")),
            )
            assertEquals(
                0L,
                scalarLong(it, "SELECT COUNT(*) FROM ArtifactReference WHERE artifact_id = ?", checkpoint.getValue("artifactId")),
            )
            assertEquals("STAGED_DURABLE", scalarString(it, "SELECT state FROM ArtifactRecord WHERE artifact_id = ?", checkpoint.getValue("artifactId")))
            assertEquals("OPEN", scalarString(it, "SELECT state FROM ImportSession WHERE import_session_id = ?", importSessionId))
            assertEquals(
                checkpoint.getValue("page").split('|').first(),
                scalarString(it, "SELECT page_id FROM Page WHERE import_session_id = ? ORDER BY source_page_index LIMIT 1", importSessionId),
            )
            assertEquals(
                checkpoint.getValue("page").split('|')[1],
                scalarString(it, "SELECT current_image_version_id FROM Page WHERE import_session_id = ? ORDER BY source_page_index LIMIT 1", importSessionId),
            )
        }
    }

    private fun scalarLong(db: SQLiteDatabase, sql: String, vararg args: String): Long =
        db.rawQuery(sql, args).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun scalarLong(context: android.content.Context, databaseName: String, sql: String, vararg args: String): Long =
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            scalarLong(it, sql, *args)
        }

    private fun scalarString(db: SQLiteDatabase, sql: String, vararg args: String): String? =
        db.rawQuery(sql, args).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun scalarString(context: android.content.Context, databaseName: String, sql: String, vararg args: String): String? =
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            scalarString(it, sql, *args)
        }

    private fun deterministicPageIds(importSessionId: String, sourcePageIndex: Int): Pair<String, String> {
        val pageIdentity = "$importSessionId:$sourcePageIndex"
        val pageId = java.util.UUID.nameUUIDFromBytes("$pageIdentity:page".toByteArray()).toString()
        val imageVersionId = java.util.UUID.nameUUIDFromBytes("$pageIdentity:image-version".toByteArray()).toString()
        return pageId to imageVersionId
    }

    private fun writeCheckpoint(directory: File, contents: String) {
        FileOutputStream(directory.resolve("checkpoint.txt")).use { out ->
            out.write(contents.toByteArray())
            out.fd.sync()
        }
    }

    private fun importedArtifactRows(
        context: android.content.Context,
        databaseName: String,
        importSessionId: String,
    ): List<Pair<String, String>> {
        val db = SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return db.use {
            it.rawQuery(
                "SELECT a.artifact_id, a.state FROM Page p JOIN ImageVersion v ON v.image_version_id = p.current_image_version_id " +
                    "JOIN ArtifactRecord a ON a.artifact_id = v.artifact_id WHERE p.import_session_id = ? " +
                    "ORDER BY p.source_page_index",
                arrayOf(importSessionId),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1))
                }
            }
        }
    }

    private fun awaitPageCount(scenario: ActivityScenario<ProductActivity>, storage: ProductStorage, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 60_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (storage.workspaceProjection().pages.size == expected) return
            SystemClock.sleep(50L)
        }
        throw AssertionError("ProductActivity startup did not resume the import to $expected pages")
    }

    private fun fixtureNames(context: android.content.Context, fixtureId: String): FixtureNames = FixtureNames(
        database = "pdf-publish-death-$fixtureId.db",
        artifacts = File(context.noBackupFilesDir, "pdf-publish-artifacts-$fixtureId"),
        fixture = File(context.filesDir, "pdf-publish-fixture-$fixtureId"),
    )

    private fun createSourcePdf(file: File) {
        PDDocument().use { document ->
            repeat(SOURCE_PAGE_COUNT) { document.addPage(PDPage(PDRectangle.A4)) }
            document.save(file)
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class FixtureNames(val database: String, val artifacts: File, val fixture: File)

    private companion object {
        const val TAG = "PdfImportPublicationDeath"
        const val SOURCE_PAGE_COUNT = 3
        const val PAUSE_BEFORE_PAGE_INDEX = 1
    }
}
