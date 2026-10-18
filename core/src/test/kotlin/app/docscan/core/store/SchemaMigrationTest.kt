package app.docscan.core.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.QueryResult
import app.docscan.core.db.DocScanDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaMigrationTest {
    @Test
    fun historicalSchemaSnapshotsOneThroughFourMigrateToCurrentWithoutLosingUserPageAndArtifactGraph() {
        for (sourceVersion in 1L..4L) {
            val snapshot = Path.of("src/main/sqldelight/databases/$sourceVersion.db")
            assertTrue("Missing tracked schema snapshot $snapshot", Files.isRegularFile(snapshot))
            val temporaryDatabase = Files.createTempFile("docscan-schema-v$sourceVersion-", ".db")
            Files.copy(snapshot, temporaryDatabase, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val driver = JdbcSqliteDriver(
                "jdbc:sqlite:$temporaryDatabase",
                Properties().apply { setProperty("foreign_keys", "false") },
            )
            try {
                driver.execute(
                    null,
                    """INSERT INTO Workspace(workspace_id, order_revision, active_page_id,
                        last_user_activity_wall, last_user_activity_monotonic, monotonic_epoch_token,
                        retention_time_uncertain) VALUES ('migration-sentinel', 7, 'page-sentinel',
                        1234, 5678, 'epoch-v1', 0)""",
                    0,
                    null,
                )
                driver.execute(
                    null,
                    "INSERT INTO WorkspaceSession(workspace_session_id, workspace_id, state) VALUES ('session-sentinel', 'migration-sentinel', 'OPEN')",
                    0,
                    null,
                )
                driver.execute(
                    null,
                    """INSERT INTO ArtifactRecord(artifact_id, relative_storage_path, artifact_kind,
                        size_bytes, checksum, artifact_format_version, creation_revision, state,
                        producer_epoch, gc_claim_token) VALUES ('artifact-sentinel', 'pages/source.jpg',
                        'IMAGE', 4, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
                        1, 7, 'COMMITTED', 'epoch-v1', NULL)""",
                    0,
                    null,
                )
                driver.execute(
                    null,
                    """INSERT INTO ImageVersion(image_version_id, page_id, artifact_id, parent_version_id,
                        coordinate_space, transform_provenance, artifact_format_version)
                        VALUES ('image-version-sentinel', 'page-sentinel', 'artifact-sentinel', NULL,
                        'PIXEL', '{"operation":"capture"}', 1)""",
                    0,
                    null,
                )
                driver.execute(
                    null,
                    """INSERT INTO Recognition(recognition_id, page_id, image_version_id, payload)
                        VALUES ('recognition-sentinel', 'page-sentinel', 'image-version-sentinel',
                        '{"text":"Invoice 42"}')""",
                    0,
                    null,
                )
                driver.execute(
                    null,
                    """INSERT INTO Page(page_id, workspace_id, workspace_session_id, order_index,
                        selected, content_revision, current_image_version_id, undo_image_version_id,
                        undo_workspace_session_id, processing_desired_state, current_recognition_id,
                        tombstone, tombstone_at_wall, import_session_id, source_page_index, source_provenance)
                        VALUES ('page-sentinel', 'migration-sentinel', 'session-sentinel', 3, 1, 2,
                        'image-version-sentinel', NULL, NULL, 'OCR_V1', 'recognition-sentinel', 0,
                        NULL, NULL, NULL, 'CAMERA')""",
                    0,
                    null,
                )
                driver.execute(
                    null,
                    """INSERT INTO ArtifactReference(artifact_id, ref_kind, ref_owner_id, protection_class)
                        VALUES ('artifact-sentinel', 'IMAGE_VERSION', 'image-version-sentinel', 'AUTHORITATIVE')""",
                    0,
                    null,
                )

                DocScanDatabase.Schema.migrate(driver, sourceVersion, 13L)

                val workspace = driver.executeQuery(
                    null,
                    """SELECT order_revision, active_page_id, last_user_activity_wall,
                        last_user_activity_monotonic, monotonic_epoch_token, retention_time_uncertain
                        FROM Workspace WHERE workspace_id = 'migration-sentinel'""",
                    { cursor ->
                        if (!cursor.next().value) return@executeQuery QueryResult.Value(null)
                        QueryResult.Value(
                            listOf(
                                cursor.getLong(0),
                                cursor.getString(1),
                                cursor.getLong(2),
                                cursor.getLong(3),
                                cursor.getString(4),
                                cursor.getLong(5),
                            ),
                        )
                    },
                    0,
                    null,
                ).value
                assertEquals("Workspace state after v$sourceVersion→v12", listOf(7L, "page-sentinel", 1234L, 5678L, "epoch-v1", 0L), workspace)

                val page = driver.executeQuery(
                    null,
                    """SELECT order_index, selected, content_revision, current_image_version_id,
                        current_recognition_id, tombstone, source_provenance FROM Page WHERE page_id = 'page-sentinel'""",
                    { cursor ->
                        if (!cursor.next().value) return@executeQuery QueryResult.Value(null)
                        QueryResult.Value(
                            listOf(
                                cursor.getLong(0), cursor.getLong(1), cursor.getLong(2),
                                cursor.getString(3), cursor.getString(4), cursor.getLong(5), cursor.getString(6),
                            ),
                        )
                    },
                    0,
                    null,
                ).value
                assertEquals("Page state after v$sourceVersion→v12", listOf(3L, 1L, 2L, "image-version-sentinel", "recognition-sentinel", 0L, "CAMERA"), page)

                val recognition = driver.executeQuery(
                    null,
                    "SELECT page_id, image_version_id, payload FROM Recognition WHERE recognition_id = 'recognition-sentinel'",
                    { cursor ->
                        if (!cursor.next().value) return@executeQuery QueryResult.Value(null)
                        QueryResult.Value(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                    },
                    0,
                    null,
                ).value
                assertEquals("Recognition after v$sourceVersion→v12", listOf("page-sentinel", "image-version-sentinel", "{\"text\":\"Invoice 42\"}"), recognition)

                val imageVersion = driver.executeQuery(
                    null,
                    "SELECT page_id, artifact_id, coordinate_space, transform_provenance, artifact_format_version FROM ImageVersion WHERE image_version_id = 'image-version-sentinel'",
                    { cursor ->
                        if (!cursor.next().value) return@executeQuery QueryResult.Value(null)
                        QueryResult.Value(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getLong(4)))
                    },
                    0,
                    null,
                ).value
                assertEquals("ImageVersion after v$sourceVersion→v12", listOf("page-sentinel", "artifact-sentinel", "PIXEL", "{\"operation\":\"capture\"}", 1L), imageVersion)

                val artifact = driver.executeQuery(
                    null,
                    "SELECT relative_storage_path, artifact_kind, size_bytes, checksum, state, storage_state FROM ArtifactRecord WHERE artifact_id = 'artifact-sentinel'",
                    { cursor ->
                        if (!cursor.next().value) return@executeQuery QueryResult.Value(null)
                        QueryResult.Value(listOf(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4), cursor.getString(5)))
                    },
                    0,
                    null,
                ).value
                assertEquals("Artifact after v$sourceVersion→v12", listOf("pages/source.jpg", "IMAGE", 4L, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "COMMITTED", "PRESENT"), artifact)

                val reference = driver.executeQuery(
                    null,
                    "SELECT ref_kind, ref_owner_id, protection_class FROM ArtifactReference WHERE artifact_id = 'artifact-sentinel'",
                    { cursor ->
                        if (!cursor.next().value) return@executeQuery QueryResult.Value(null)
                        QueryResult.Value(listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                    },
                    0,
                    null,
                ).value
                assertEquals("ArtifactReference after v$sourceVersion→v12", listOf("IMAGE_VERSION", "image-version-sentinel", "AUTHORITATIVE"), reference)

                for (table in listOf(
                    "SaveOutputPublicationIntent",
                    "PdfImportPublicationIntent",
                    "ImageImportPublicationIntent",
                    "SavedPdfEditComposition",
                    "PublicExportPublication",
                )) {
                    val tableCount = driver.executeQuery(
                        null,
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='$table'",
                        { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0)) },
                        0,
                        null,
                    ).value
                    assertEquals("$table exists after v$sourceVersion→v12", 1L, tableCount)
                }
            } finally {
                driver.close()
                Files.deleteIfExists(temporaryDatabase)
            }
        }
    }

    @Test
    fun versionTwelveToThirteenCreatesPublicExportPublicationTable() {
        val driver = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { setProperty("foreign_keys", "false") },
        )
        try {
            DocScanDatabase.Schema.create(driver)
            driver.execute(null, "DROP TABLE PublicExportPublication", 0, null)
            driver.execute(null, "ALTER TABLE ExternalExportPackage DROP COLUMN include_jpg", 0, null)

            DocScanDatabase.Schema.migrate(driver, 12L, 13L)

            val tableCount = driver.executeQuery(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='PublicExportPublication'",
                { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0)) },
                0,
                null,
            ).value
            assertEquals(1L, tableCount)

            val includeJpgColumn = driver.executeQuery(
                null,
                "PRAGMA table_info(ExternalExportPackage)",
                { cursor ->
                    while (cursor.next().value) {
                        if (cursor.getString(1) == "include_jpg") {
                            return@executeQuery QueryResult.Value(
                                Triple(cursor.getLong(3), cursor.getString(4), cursor.getLong(5)),
                            )
                        }
                    }
                    QueryResult.Value(null)
                },
                0,
                null,
            ).value
            assertEquals(Triple(1L, "1", 0L), includeJpgColumn)
        } finally {
            driver.close()
        }
    }

    @Test
    fun versionTenToElevenCreatesPdfImportPublicationIntentTable() {
        val driver = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { setProperty("foreign_keys", "false") },
        )
        try {
            DocScanDatabase.Schema.create(driver)
            driver.execute(null, "DROP TABLE PdfImportPublicationIntent", 0, null)

            DocScanDatabase.Schema.migrate(driver, 10L, 11L)

            val tableCount = driver.executeQuery(
                null,
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='PdfImportPublicationIntent'",
                { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0)) },
                0,
                null,
            ).value
            assertEquals(1L, tableCount)
        } finally {
            driver.close()
        }
    }

    @Test
    fun versionEightToNinePreservesUserSelectionAndInitializesNoPendingAutoSelect() {
        val driver = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { setProperty("foreign_keys", "false") },
        )
        try {
            DocScanDatabase.Schema.create(driver)
            // Reconstruct the v8 Page shape before seeding historical selection values.
            driver.execute(null, "ALTER TABLE Page DROP COLUMN select_when_ready", 0, null)
            driver.execute(
                null,
                """INSERT INTO Page(page_id, workspace_id, workspace_session_id, order_index, selected,
                    content_revision, current_image_version_id, processing_desired_state, tombstone, source_provenance)
                    VALUES ('unselected', 'w', 's', 0, 0, 1, 'iv0', 'OCR_V1', 0, 'CAMERA')""",
                0,
                null,
            )
            driver.execute(
                null,
                """INSERT INTO Page(page_id, workspace_id, workspace_session_id, order_index, selected,
                    content_revision, current_image_version_id, processing_desired_state, tombstone, source_provenance)
                    VALUES ('selected', 'w', 's', 1, 1, 1, 'iv1', 'OCR_V1', 0, 'IMPORT')""",
                0,
                null,
            )

            DocScanDatabase.Schema.migrate(driver, 8L, 9L)

            val database = DocScanDatabase(driver)
            assertEquals(0L, database.schemaQueries.pageById("unselected").executeAsOne().selected)
            assertEquals(1L, database.schemaQueries.pageById("selected").executeAsOne().selected)
            assertEquals(0L, database.schemaQueries.pageById("unselected").executeAsOne().select_when_ready)
            assertEquals(0L, database.schemaQueries.pageById("selected").executeAsOne().select_when_ready)
        } finally {
            driver.close()
        }
    }
}
