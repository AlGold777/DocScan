package app.docscan.android

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.docscan.core.db.DocScanDatabase
import app.docscan.core.model.PageId
import app.docscan.core.model.ImageVersionId
import app.docscan.core.store.StoredPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSchemaMigrationInstrumentedTest {
    @Test
    fun trackedSchemaSnapshotOneMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(1)

    @Test
    fun trackedSchemaSnapshotTwoMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(2)

    @Test
    fun trackedSchemaSnapshotThreeMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(3)

    @Test
    fun trackedSchemaSnapshotFourMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(4)

    @Test
    fun intermediateSchemaSnapshotFiveMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(5)

    @Test
    fun intermediateSchemaSnapshotSixMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(6)

    @Test
    fun intermediateSchemaSnapshotSevenMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(7)

    @Test
    fun intermediateSchemaSnapshotNineMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(9)

    @Test
    fun intermediateSchemaSnapshotTenMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(10)

    @Test
    fun intermediateSchemaSnapshotElevenMigratesThroughProductionAndroidDriver() = migrateTrackedSnapshot(11)

    private fun migrateTrackedSnapshot(sourceVersion: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "schema-snapshot-v$sourceVersion-$suffix.db"
        val artifactDirectory = "schema-snapshot-artifacts-$suffix"
        val databaseFile = context.getDatabasePath(databaseName).apply { parentFile?.mkdirs() }
        val sourceImageBytes = legacyFixtureImageBytes()
        val sourceArtifact = File(context.noBackupFilesDir, "$artifactDirectory/pages/source.jpg")
        sourceArtifact.parentFile?.mkdirs()
        sourceArtifact.writeBytes(sourceImageBytes)
        val sourceChecksum = MessageDigest.getInstance("SHA-256").digest(sourceImageBytes)
            .joinToString("") { "%02x".format(it) }
        var storage: ProductStorage? = null

        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("$sourceVersion.db").use { input ->
                databaseFile.outputStream().use(input::copyTo)
            }
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.execSQL(
                    """INSERT INTO Workspace(workspace_id, order_revision, active_page_id,
                            last_user_activity_wall, last_user_activity_monotonic, monotonic_epoch_token,
                            retention_time_uncertain) VALUES ('00000000-0000-0000-0000-000000000001', 17,
                            '00000000-0000-0000-0000-000000000002',
                            123456, 987654, 'android-epoch-v1', 0)""",
                    )
                    database.execSQL(
                        "INSERT INTO WorkspaceSession(workspace_session_id, workspace_id, state) VALUES (?, ?, 'OPEN')",
                        arrayOf("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000001"),
                    )
                    database.execSQL(
                        """INSERT INTO ArtifactRecord(artifact_id, relative_storage_path, artifact_kind,
                            size_bytes, checksum, artifact_format_version, creation_revision, state,
                            producer_epoch, gc_claim_token) VALUES (?, 'pages/source.jpg', 'IMAGE', ?, ?, 1,
                            17, 'COMMITTED', 'android-epoch-v1', NULL)""",
                        arrayOf<Any>("00000000-0000-0000-0000-000000000004", sourceImageBytes.size, sourceChecksum),
                    )
                    database.execSQL(
                        """INSERT INTO ImageVersion(image_version_id, page_id, artifact_id, parent_version_id,
                            coordinate_space, transform_provenance, artifact_format_version)
                            VALUES ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000004', NULL, 'PIXEL', '{"operation":"capture"}', 1)""",
                    )
                    database.execSQL(
                        """INSERT INTO Page(page_id, workspace_id, workspace_session_id, order_index,
                            selected, content_revision, current_image_version_id, undo_image_version_id,
                            undo_workspace_session_id, processing_desired_state, current_recognition_id,
                            tombstone, tombstone_at_wall, import_session_id, source_page_index, source_provenance)
                            VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
                            '00000000-0000-0000-0000-000000000003', 3, 1, 2,
                            '00000000-0000-0000-0000-000000000005', NULL, NULL, 'OCR_V1',
                            '00000000-0000-0000-0000-000000000006', 0, NULL, NULL, NULL, 'CAMERA')""",
                    )
                    database.execSQL(
                        """INSERT INTO Recognition(recognition_id, page_id, image_version_id, payload)
                            VALUES ('00000000-0000-0000-0000-000000000006',
                            '00000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000005', '{"text":"Legacy invoice 42"}')""",
                    )
                    database.execSQL(
                        """INSERT INTO ArtifactReference(artifact_id, ref_kind, ref_owner_id, protection_class)
                            VALUES ('00000000-0000-0000-0000-000000000004', 'IMAGE_VERSION',
                            '00000000-0000-0000-0000-000000000005', 'AUTHORITATIVE')""",
                    )
                database.version = sourceVersion
                assertEquals("Fixture v$sourceVersion source version", sourceVersion, database.version)
            }

            val openedStorage = ProductStorage(context, databaseName, artifactDirectory)
            storage = openedStorage
            assertFalse("v$sourceVersion must open without startup integrity lock", openedStorage.startupIntegrityBlocked)
            assertTrue("v$sourceVersion has no integrity findings", openedStorage.workspaceIntegrityFindingsAtStartup.isEmpty())
            assertEquals("00000000-0000-0000-0000-000000000001", openedStorage.workspaceId.value)
            val projection = openedStorage.workspaceProjection()
            assertEquals("v$sourceVersion active Page", "00000000-0000-0000-0000-000000000002", projection.activePageId?.value)
            assertEquals(1, projection.pages.size)
            val page = projection.pages.single()
            assertEquals("00000000-0000-0000-0000-000000000002", page.pageId.value)
            assertEquals("00000000-0000-0000-0000-000000000005", page.imageVersionId.value)
            assertEquals(3L, page.orderIndex)
            assertTrue(page.selected)
            assertEquals(2L, page.contentRevision)
            assertEquals("pages/source.jpg", page.artifactRelativePath)
            assertEquals("COMMITTED", page.artifactState)
            assertEquals("{\"text\":\"Legacy invoice 42\"}", page.recognizedText)
            assertEquals(sourceImageBytes.toList(), openedStorage.artifactPath(page.artifactRelativePath).toFile().readBytes().toList())

            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                assertEquals(
                    "ProductStorage upgrades v$sourceVersion to the current schema",
                    DocScanDatabase.Schema.version.toInt(),
                    database.version,
                )
                assertColumns(database, "ExportAttempt", "output_artifact_id")
                assertTable(database, "DestinationGrant")
                assertTable(database, "ExternalExportPackage")
                assertTable(database, "ExternalExportPackageOutput")
                assertColumns(database, "DeleteUndo", "undo_batch_id")
                assertColumns(database, "ImportSession", "source_display_name", "last_error")
                assertColumns(database, "ExternalExportPackage", "ocr_status")
                assertColumns(database, "ArtifactRecord", "storage_state")
                assertColumns(database, "Page", "select_when_ready")
                assertTable(database, "SavedPdfEditComposition")
                assertTable(database, "SaveOutputPublicationIntent")
                assertTable(database, "PdfImportPublicationIntent")
                assertTable(database, "ImageImportPublicationIntent")
                assertColumnDefault(database, "ImportSession", "source_display_name", "''")
                assertColumnDefault(database, "ExternalExportPackage", "ocr_status", "'NONE'")
                assertColumnDefault(database, "ArtifactRecord", "storage_state", "'PRESENT'")
                assertColumnDefault(database, "Page", "select_when_ready", "0")
                val workspace = database.rawQuery(
                    """SELECT order_revision, active_page_id, last_user_activity_wall,
                        last_user_activity_monotonic, monotonic_epoch_token, retention_time_uncertain
                        FROM Workspace WHERE workspace_id='00000000-0000-0000-0000-000000000001'""",
                    null,
                ).use { cursor ->
                    assertTrue("v$sourceVersion Workspace sentinel exists", cursor.moveToFirst())
                    listOf(cursor.getLong(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4), cursor.getLong(5))
                }
                assertEquals(
                    "v$sourceVersion Workspace stable values",
                    listOf(17L, "00000000-0000-0000-0000-000000000002", 123456L, 987654L, "android-epoch-v1", 0L),
                    workspace,
                )
                val artifact = database.rawQuery(
                    """SELECT relative_storage_path, size_bytes, checksum, state, storage_state
                        FROM ArtifactRecord WHERE artifact_id='00000000-0000-0000-0000-000000000004'""",
                    null,
                ).use { cursor ->
                    assertTrue("v$sourceVersion artifact row exists", cursor.moveToFirst())
                    listOf(cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))
                }
                assertEquals(
                    "v$sourceVersion artifact identity and migration default",
                    listOf("pages/source.jpg", sourceImageBytes.size.toLong(), sourceChecksum, "COMMITTED", "PRESENT"),
                    artifact,
                )
                val reference = database.rawQuery(
                    """SELECT ref_kind, ref_owner_id, protection_class FROM ArtifactReference
                        WHERE artifact_id='00000000-0000-0000-0000-000000000004'""",
                    null,
                ).use { cursor ->
                    assertTrue("v$sourceVersion authoritative reference exists", cursor.moveToFirst())
                    listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                }
                assertEquals(
                    listOf("IMAGE_VERSION", "00000000-0000-0000-0000-000000000005", "AUTHORITATIVE"),
                    reference,
                )
            }
        } finally {
            storage?.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun androidDriverMigratesVersionEightWithoutChangingWorkspaceOrPageState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "schema-migration-$suffix.db"
        val artifactDirectory = "schema-migration-artifacts-$suffix"
        val storage = ProductStorage(context, databaseName, artifactDirectory)
        var migratedStorage: ProductStorage? = null

        try {
            createPage(storage, "legacy-unselected.jpg", Color.rgb(192, 32, 32))
            val selectedPage = createPage(storage, "legacy-selected.jpg", Color.rgb(32, 32, 192))
            storage.setSelected(selectedPage.pageId, true)
            val unselectedPage = storage.visiblePages().first { it.pageId != selectedPage.pageId }
            storage.setSelected(unselectedPage.pageId, false)
            storage.setActivePage(unselectedPage.pageId)

            val beforeProjection = storage.workspaceProjection()
            val beforePages = beforeProjection.pages.map(::snapshot)
            val beforeBytes = beforeProjection.pages.associate { page ->
                page.pageId to storage.artifactPath(page.artifactRelativePath).toFile().readBytes()
            }
            val workspaceId = storage.workspaceId
            storage.close()

            val databaseFile = context.getDatabasePath(databaseName)
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.execSQL("PRAGMA foreign_keys=OFF")
                database.execSQL("DROP TABLE SaveOutputPublicationIntent")
                database.execSQL("DROP TABLE PdfImportPublicationIntent")
                database.execSQL("DROP TABLE ImageImportPublicationIntent")
                database.execSQL("DROP TABLE PublicExportPublication")
                database.execSQL("DROP TABLE ExternalExportPackageOutput")
                database.execSQL("DROP TABLE ExternalExportPackage")
                database.execSQL(
                    """CREATE TABLE ExternalExportPackage (
                        export_artifact_id TEXT NOT NULL PRIMARY KEY REFERENCES ExportArtifact(export_artifact_id),
                        destination_tree_uri TEXT NOT NULL,
                        common_basename TEXT NOT NULL,
                        provider_qualification_id TEXT NOT NULL REFERENCES ProviderQualification(qualification_id),
                        adapter_id TEXT NOT NULL,
                        provider_version TEXT NOT NULL,
                        os_version TEXT NOT NULL,
                        destination_access_class TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (state IN ('READY', 'WRITING_EXTERNAL', 'UNKNOWN_OUTCOME', 'PARTIAL', 'SUCCEEDED', 'FAILED_KNOWN')),
                        last_error TEXT,
                        ocr_status TEXT NOT NULL DEFAULT 'NONE'
                    )""",
                )
                database.execSQL(
                    """CREATE TABLE ExternalExportPackageOutput (
                        export_artifact_id TEXT NOT NULL REFERENCES ExternalExportPackage(export_artifact_id),
                        output_artifact_id TEXT NOT NULL REFERENCES ArtifactRecord(artifact_id),
                        display_name TEXT NOT NULL,
                        ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
                        PRIMARY KEY (export_artifact_id, output_artifact_id),
                        UNIQUE (export_artifact_id, display_name),
                        UNIQUE (export_artifact_id, ordinal)
                    )""",
                )
                database.execSQL("ALTER TABLE Page DROP COLUMN select_when_ready")
                database.version = 8
                assertEquals("Fixture must represent schema v8", 8, database.version)
                val columns = database.rawQuery("PRAGMA table_info(Page)", null).use { cursor ->
                    buildSet {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
                assertFalse("v8 Page must not contain the v9 selection-intent column", "select_when_ready" in columns)
                val laterMigrationTableCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='SaveOutputPublicationIntent'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v8 fixture must omit the v10 publication-intent table", 0, laterMigrationTableCount)
                val pdfIntentCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='PdfImportPublicationIntent'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v8 fixture must omit the v11 PDF publication-intent table", 0, pdfIntentCount)
                val imageIntentCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='ImageImportPublicationIntent'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v8 fixture must omit the v12 image publication-intent table", 0, imageIntentCount)
                val publicExportPublicationCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='PublicExportPublication'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v8 fixture must omit the v13 public export publication table", 0, publicExportPublicationCount)
                val externalPackageColumns = database.rawQuery("PRAGMA table_info(ExternalExportPackage)", null).use { cursor ->
                    buildSet {
                        val nameIndex = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
                assertFalse("v8 fixture must omit the v13 JPG inclusion column", "include_jpg" in externalPackageColumns)
            }

            migratedStorage = ProductStorage(context, databaseName, artifactDirectory)
            assertFalse("A valid v8 database must open without the startup integrity lock", migratedStorage.startupIntegrityBlocked)
            assertTrue(migratedStorage.workspaceIntegrityFindingsAtStartup.isEmpty())
            assertEquals(workspaceId, migratedStorage.workspaceId)

            val afterProjection = migratedStorage.workspaceProjection()
            assertEquals(beforeProjection.activePageId, afterProjection.activePageId)
            assertEquals(beforePages, afterProjection.pages.map(::snapshot))
            afterProjection.pages.forEach { page ->
                assertEquals(beforeBytes.getValue(page.pageId).toList(), migratedStorage.artifactPath(page.artifactRelativePath).toFile().readBytes().toList())
            }

            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                assertEquals(
                    "ProductStorage must migrate Android user_version to the current schema",
                    DocScanDatabase.Schema.version.toInt(),
                    database.version,
                )
                val publicationIntentTableCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='SaveOutputPublicationIntent'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v9→v10 migration must create the publication-intent table once", 1, publicationIntentTableCount)
                val pdfIntentCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='PdfImportPublicationIntent'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v10→v11 migration must create the PDF publication-intent table once", 1, pdfIntentCount)
                val imageIntentCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='ImageImportPublicationIntent'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v11→v12 migration must create the image publication-intent table once", 1, imageIntentCount)
                val publicExportPublicationCount = database.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='PublicExportPublication'",
                    null,
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                assertEquals("v12→v13 migration must create the public export publication table once", 1, publicExportPublicationCount)
                database.rawQuery("SELECT page_id, select_when_ready FROM Page ORDER BY order_index", null).use { cursor ->
                    var rows = 0
                    while (cursor.moveToNext()) {
                        assertEquals("Legacy selection intent must default off", 0, cursor.getInt(1))
                        rows++
                    }
                    assertEquals(2, rows)
                }
            }
        } finally {
            migratedStorage?.close()
            runCatching { storage.close() }
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    private fun createPage(storage: ProductStorage, displayName: String, color: Int): StoredPage {
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val imported = storage.importImage(bytes.inputStream(), "image/jpeg", displayName)
        return storage.workspaceProjection().pages.single { it.pageId == imported.pageId }
    }

    private fun legacyFixtureImageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(37, 121, 203)) }
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertTable(database: SQLiteDatabase, table: String) {
        val count = database.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        assertEquals("$table exists after migration", 1, count)
    }

    private fun assertColumns(database: SQLiteDatabase, table: String, vararg expected: String) {
        val columns = database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        expected.forEach { column -> assertTrue("$table.$column exists after migration", column in columns) }
    }

    private fun assertColumnDefault(database: SQLiteDatabase, table: String, column: String, expected: String) {
        val defaultValue = database.rawQuery("PRAGMA table_info(`$table`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            var value: String? = null
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) value = cursor.getString(defaultIndex)
            value
        }
        assertNotNull("$table.$column has a migrated default", defaultValue)
        assertEquals("$table.$column default", expected, defaultValue)
    }

    private fun snapshot(page: StoredPage): PageState = PageState(
        pageId = page.pageId,
        imageVersionId = page.imageVersionId,
        orderIndex = page.orderIndex,
        selected = page.selected,
        contentRevision = page.contentRevision,
        artifactRelativePath = page.artifactRelativePath,
        artifactState = page.artifactState,
    )

    private data class PageState(
        val pageId: PageId,
        val imageVersionId: ImageVersionId,
        val orderIndex: Long,
        val selected: Boolean,
        val contentRevision: Long,
        val artifactRelativePath: String,
        val artifactState: String,
    )
}
