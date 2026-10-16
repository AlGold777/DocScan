package app.docscan.android

import android.Manifest
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.rule.GrantPermissionRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StartupWorkspaceIntegrityGateInstrumentedTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test
    fun corruptWorkspaceBlocksStartupActionsAndPreservesRowsAndArtifactBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "integrity-gate-$suffix.db"
        val artifactDirectory = "integrity-gate-artifacts-$suffix"
        val seedStorage = ProductStorage(context, databaseName, artifactDirectory)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff336699.toInt())
        }
        val imageBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val captured = seedStorage.importImage(imageBytes.inputStream(), "image/jpeg", "gate.jpg")
        val page = seedStorage.workspaceProjection().pages.single()
        val imageFile = seedStorage.artifactPath(page.artifactRelativePath).toFile()
        val originalBytes = imageFile.readBytes()
        seedStorage.close()

        val missingImageVersionId = "missing-${UUID.randomUUID()}"
        val databaseFile = context.getDatabasePath(databaseName)
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            val values = ContentValues().apply { put("current_image_version_id", missingImageVersionId) }
            assertEquals(1, db.update("Page", values, "page_id = ?", arrayOf(captured.pageId.value)))
        }

        var blockedStorage: ProductStorage? = null
        var scenario: ActivityScenario<ProductActivity>? = null
        val application = context.applicationContext as DocScanApplication
        try {
            blockedStorage = ProductStorage(context, databaseName, artifactDirectory)
            assertTrue(blockedStorage.startupIntegrityBlocked)
            assertTrue(blockedStorage.workspaceIntegrityFindingsAtStartup.any {
                it.code == "CURRENT_IMAGE_VERSION_MISSING" && it.pageId == captured.pageId.value
            })
            assertEquals("COMMITTED", readArtifactState(databaseFile, captured.artifactId.value))
            assertEquals("Blocked startup must not advance the existing recovery cursor", "", readRecoveryCursor(databaseFile))
            assertTrue(imageFile.readBytes().contentEquals(originalBytes))

            application.productStorageOverrideForTest = blockedStorage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = scenario
            awaitCondition {
                var blocked = false
                activeScenario.onActivity { activity ->
                    blocked = activity.window.decorView.findViewWithTag<android.view.View>("docscan-import-button")?.isEnabled == false
                }
                blocked
            }
            activeScenario.onActivity { activity ->
                listOf(
                    "docscan-capture-button",
                    "docscan-import-button",
                    "docscan-destination-button",
                    "docscan-recent-button",
                    "docscan-save-button",
                    "docscan-selection-button",
                    "docscan-recover-exports-button",
                    "docscan-resume-exports-button",
                    "docscan-delete-selected-button",
                    "docscan-clear-workspace-button",
                ).forEach { tag ->
                    assertFalse(
                        "$tag must remain disabled while startup integrity is blocked",
                        activity.window.decorView.findViewWithTag<android.view.View>(tag)?.isEnabled ?: true,
                    )
                }
                assertEquals(
                    context.getString(R.string.workspace_integrity_blocked, 1),
                    activity.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text,
                )
            }

            assertEquals(missingImageVersionId, readCurrentImageVersion(databaseFile, captured.pageId.value))
            assertEquals("COMMITTED", readArtifactState(databaseFile, captured.artifactId.value))
            assertEquals("Blocked Activity must not advance the recovery cursor", "", readRecoveryCursor(databaseFile))
            assertTrue("Startup gate must leave image bytes unchanged", imageFile.readBytes().contentEquals(originalBytes))
        } finally {
            scenario?.close()
            application.productStorageOverrideForTest = null
            blockedStorage?.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun persistedRowsWithoutWorkspaceAreNotSilentlyRepaired() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "orphan-workspace-$suffix.db"
        val artifactDirectory = "orphan-workspace-artifacts-$suffix"
        val seedStorage = ProductStorage(context, databaseName, artifactDirectory)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff884422.toInt()) }
        val imageBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val captured = seedStorage.importImage(imageBytes.inputStream(), "image/jpeg", "orphan.jpg")
        val page = seedStorage.workspaceProjection().pages.single()
        val imageFile = seedStorage.artifactPath(page.artifactRelativePath).toFile()
        val originalBytes = imageFile.readBytes()
        seedStorage.close()

        val databaseFile = context.getDatabasePath(databaseName)
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            assertEquals(1, db.delete("Workspace", null, null))
        }

        var blockedStorage: ProductStorage? = null
        var scenario: ActivityScenario<ProductActivity>? = null
        val application = context.applicationContext as DocScanApplication
        try {
            blockedStorage = ProductStorage(context, databaseName, artifactDirectory)
            assertTrue(blockedStorage.startupIntegrityBlocked)
            assertTrue(blockedStorage.workspaceIntegrityFindingsAtStartup.any {
                it.code == "WORKSPACE_MISSING_WITH_PERSISTED_STATE"
            })
            assertEquals("No Workspace row may be synthesized", 0, readWorkspaceCount(databaseFile))
            assertEquals(1, readPageCount(databaseFile))
            assertEquals("COMMITTED", readArtifactState(databaseFile, captured.artifactId.value))
            assertEquals("", readRecoveryCursor(databaseFile))
            assertTrue(imageFile.readBytes().contentEquals(originalBytes))

            application.productStorageOverrideForTest = blockedStorage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = scenario
            awaitCondition {
                var locked = false
                activeScenario.onActivity { activity ->
                    locked = activity.window.decorView.findViewWithTag<android.view.View>("docscan-import-button")?.isEnabled == false
                }
                locked
            }
            activeScenario.onActivity { activity ->
                listOf(
                    "docscan-capture-button",
                    "docscan-import-button",
                    "docscan-destination-button",
                    "docscan-recent-button",
                    "docscan-save-button",
                    "docscan-selection-button",
                    "docscan-recover-exports-button",
                    "docscan-resume-exports-button",
                    "docscan-delete-selected-button",
                    "docscan-clear-workspace-button",
                ).forEach { tag ->
                    assertFalse("$tag must be disabled when persisted rows have no Workspace", activity.window.decorView
                        .findViewWithTag<android.view.View>(tag)?.isEnabled ?: true)
                }
            }
            assertEquals(0, readWorkspaceCount(databaseFile))
            assertEquals(1, readPageCount(databaseFile))
            assertEquals("COMMITTED", readArtifactState(databaseFile, captured.artifactId.value))
            assertTrue(imageFile.readBytes().contentEquals(originalBytes))
        } finally {
            scenario?.close()
            application.productStorageOverrideForTest = null
            blockedStorage?.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun aSaturatedIntegrityInventoryFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "integrity-saturation-$suffix.db"
        val artifactDirectory = "integrity-saturation-artifacts-$suffix"
        val seedStorage = ProductStorage(context, databaseName, artifactDirectory)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff112233.toInt()) }
        val imageBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val source = seedStorage.importImage(imageBytes.inputStream(), "image/jpeg", "saturation.jpg")
        val databaseFile = context.getDatabasePath(databaseName)
        seedStorage.close()

        var blockedStorage: ProductStorage? = null
        try {
            SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA foreign_keys=ON")
                val workspaceId = db.rawQuery("SELECT workspace_id FROM Workspace LIMIT 1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
                val sessionId = db.rawQuery(
                    "SELECT workspace_session_id FROM WorkspaceSession WHERE workspace_id = ? AND state = 'OPEN'",
                    arrayOf(workspaceId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
                val imageVersionId = db.rawQuery(
                    "SELECT current_image_version_id FROM Page WHERE page_id = ?",
                    arrayOf(source.pageId.value),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(0)
                }
                db.beginTransaction()
                try {
                    repeat(501) { index ->
                        val values = ContentValues().apply {
                            put("page_id", "saturated-${UUID.randomUUID()}")
                            put("workspace_id", workspaceId)
                            put("workspace_session_id", sessionId)
                            put("order_index", index + 1L)
                            put("selected", 0)
                            put("content_revision", 1L)
                            put("current_image_version_id", imageVersionId)
                            put("processing_desired_state", "NONE")
                            put("tombstone", 0)
                            put("source_provenance", "INTEGRITY_GATE_TEST")
                            put("select_when_ready", 0)
                        }
                        db.insertOrThrow("Page", null, values)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            blockedStorage = ProductStorage(context, databaseName, artifactDirectory)
            assertEquals(500, blockedStorage.workspaceIntegrityFindingsAtStartup.size)
            assertTrue(blockedStorage.startupIntegrityBlocked)
            assertTrue(blockedStorage.workspaceIntegrityFindingsAtStartup.all {
                it.code == "CURRENT_IMAGE_VERSION_PAGE_MISMATCH"
            })
            assertEquals("Blocked startup must not advance the recovery cursor", "", readRecoveryCursor(databaseFile))
            assertEquals("COMMITTED", readArtifactState(databaseFile, source.artifactId.value))
        } finally {
            blockedStorage?.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun storageInitializationFailureDisablesDocumentActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as DocScanApplication
        application.productStorageProviderForTest = { throw IllegalStateException("injected schema-open failure") }
        var scenario: ActivityScenario<ProductActivity>? = null
        try {
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = scenario
            awaitCondition {
                var locked = false
                activeScenario.onActivity { activity ->
                    locked = activity.window.decorView.findViewWithTag<android.view.View>("docscan-import-button")?.isEnabled == false
                }
                locked
            }
            activeScenario.onActivity { activity ->
                listOf(
                    "docscan-capture-button",
                    "docscan-import-button",
                    "docscan-destination-button",
                    "docscan-recent-button",
                    "docscan-save-button",
                    "docscan-selection-button",
                    "docscan-recover-exports-button",
                    "docscan-resume-exports-button",
                    "docscan-delete-selected-button",
                    "docscan-clear-workspace-button",
                ).forEach { tag ->
                    assertFalse("$tag must stay disabled after storage open failure", activity.window.decorView
                        .findViewWithTag<android.view.View>(tag)?.isEnabled ?: true)
                }
                assertEquals(
                    context.getString(R.string.storage_error, "injected schema-open failure"),
                    activity.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text,
                )
            }
        } finally {
            scenario?.close()
            application.productStorageProviderForTest = null
        }
    }

    @Test
    fun malformedNonPageReferenceOwnerBlocksStartupAndPreservesState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "reference-owner-gate-$suffix.db"
        val artifactDirectory = "reference-owner-gate-artifacts-$suffix"
        val seedStorage = ProductStorage(context, databaseName, artifactDirectory)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff234567.toInt()) }
        val imageBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val imported = seedStorage.importImage(imageBytes.inputStream(), "image/jpeg", "owner-gate.jpg")
        val page = seedStorage.workspaceProjection().pages.single()
        val imageFile = seedStorage.artifactPath(page.artifactRelativePath).toFile()
        val originalBytes = imageFile.readBytes()
        seedStorage.close()

        val missingSaveManifestId = "missing-${UUID.randomUUID()}"
        val databaseFile = context.getDatabasePath(databaseName)
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            val values = ContentValues().apply {
                put("artifact_id", imported.artifactId.value)
                put("ref_kind", "SAVE_MANIFEST")
                put("ref_owner_id", missingSaveManifestId)
                put("protection_class", "SAVE")
            }
            db.insertOrThrow("ArtifactReference", null, values)
        }

        var blockedStorage: ProductStorage? = null
        var scenario: ActivityScenario<ProductActivity>? = null
        val application = context.applicationContext as DocScanApplication
        try {
            blockedStorage = ProductStorage(context, databaseName, artifactDirectory)
            assertTrue(blockedStorage.startupIntegrityBlocked)
            assertTrue(blockedStorage.workspaceIntegrityFindingsAtStartup.isEmpty())
            assertEquals(1, blockedStorage.artifactReferenceIntegrityFindingsAtStartup.size)
            assertEquals(
                "SAVE_MANIFEST_OWNER_MISSING",
                blockedStorage.artifactReferenceIntegrityFindingsAtStartup.single().code,
            )
            assertEquals(1, blockedStorage.startupIntegrityFindingCount)
            assertEquals(2, readReferenceCount(databaseFile, imported.artifactId.value))
            assertEquals(1, readPageCount(databaseFile))
            assertEquals("COMMITTED", readArtifactState(databaseFile, imported.artifactId.value))
            assertEquals("Blocked startup must not advance the existing recovery cursor", "", readRecoveryCursor(databaseFile))
            assertTrue(imageFile.readBytes().contentEquals(originalBytes))

            application.productStorageOverrideForTest = blockedStorage
            scenario = ActivityScenario.launch(ProductActivity::class.java)
            val activeScenario = scenario
            awaitCondition {
                var blocked = false
                activeScenario.onActivity { activity ->
                    blocked = activity.window.decorView.findViewWithTag<android.view.View>("docscan-import-button")?.isEnabled == false
                }
                blocked
            }
            activeScenario.onActivity { activity ->
                listOf(
                    "docscan-capture-button",
                    "docscan-import-button",
                    "docscan-destination-button",
                    "docscan-recent-button",
                    "docscan-save-button",
                    "docscan-selection-button",
                    "docscan-recover-exports-button",
                    "docscan-resume-exports-button",
                    "docscan-delete-selected-button",
                    "docscan-clear-workspace-button",
                ).forEach { tag ->
                    assertFalse(
                        "$tag must remain disabled while global reference integrity is blocked",
                        activity.window.decorView.findViewWithTag<android.view.View>(tag)?.isEnabled ?: true,
                    )
                }
                assertEquals(
                    context.getString(R.string.workspace_integrity_blocked, 1),
                    activity.window.decorView.findViewWithTag<android.widget.TextView>("docscan-status")?.text,
                )
            }
            assertEquals(2, readReferenceCount(databaseFile, imported.artifactId.value))
            assertEquals("COMMITTED", readArtifactState(databaseFile, imported.artifactId.value))
            assertEquals("Blocked Activity must not advance the recovery cursor", "", readRecoveryCursor(databaseFile))
            assertTrue(imageFile.readBytes().contentEquals(originalBytes))
        } finally {
            scenario?.close()
            application.productStorageOverrideForTest = null
            blockedStorage?.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    @Test
    fun saturatedGlobalReferenceOwnerInventoryFailsClosedBeforeRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val databaseName = "reference-owner-saturation-$suffix.db"
        val artifactDirectory = "reference-owner-saturation-artifacts-$suffix"
        val seedStorage = ProductStorage(context, databaseName, artifactDirectory)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff345678.toInt()) }
        val imageBytes = ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }
        bitmap.recycle()
        val imported = seedStorage.importImage(imageBytes.inputStream(), "image/jpeg", "owner-saturation.jpg")
        val page = seedStorage.workspaceProjection().pages.single()
        val imageFile = seedStorage.artifactPath(page.artifactRelativePath).toFile()
        val originalBytes = imageFile.readBytes()
        seedStorage.close()

        val databaseFile = context.getDatabasePath(databaseName)
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA foreign_keys=OFF")
            db.beginTransaction()
            try {
                repeat(501) {
                    val values = ContentValues().apply {
                        put("artifact_id", imported.artifactId.value)
                        put("ref_kind", "SAVE_MANIFEST")
                        put("ref_owner_id", "missing-${UUID.randomUUID()}")
                        put("protection_class", "SAVE")
                    }
                    db.insertOrThrow("ArtifactReference", null, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        var blockedStorage: ProductStorage? = null
        try {
            blockedStorage = ProductStorage(context, databaseName, artifactDirectory)
            assertEquals(500, blockedStorage.artifactReferenceIntegrityFindingsAtStartup.size)
            assertTrue(blockedStorage.artifactReferenceIntegrityFindingsAtStartup.all {
                it.code == "SAVE_MANIFEST_OWNER_MISSING"
            })
            assertEquals(500, blockedStorage.startupIntegrityFindingCount)
            assertTrue(blockedStorage.startupIntegrityBlocked)
            assertEquals(502, readReferenceCount(databaseFile, imported.artifactId.value))
            assertEquals("Blocked startup must not advance the existing recovery cursor", "", readRecoveryCursor(databaseFile))
            assertEquals("COMMITTED", readArtifactState(databaseFile, imported.artifactId.value))
            assertTrue(imageFile.readBytes().contentEquals(originalBytes))
        } finally {
            blockedStorage?.close()
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, artifactDirectory).deleteRecursively()
        }
    }

    private fun readCurrentImageVersion(databaseFile: File, pageId: String): String =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT current_image_version_id FROM Page WHERE page_id = ?", arrayOf(pageId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    private fun readArtifactState(databaseFile: File, artifactId: String): String =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT state FROM ArtifactRecord WHERE artifact_id = ?", arrayOf(artifactId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    private fun readReferenceCount(databaseFile: File, artifactId: String): Int =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM ArtifactReference WHERE artifact_id = ?", arrayOf(artifactId)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    private fun readRecoveryCursor(databaseFile: File): String =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT cursor_payload FROM MaintenanceCursor WHERE domain = ?", arrayOf("artifact-integrity-v1")).use { cursor ->
                assertTrue("A fresh workspace initializes an empty bounded-recovery cursor", cursor.moveToFirst())
                cursor.getString(0)
            }
        }

    private fun readWorkspaceCount(databaseFile: File): Int = readCount(databaseFile, "Workspace")

    private fun readPageCount(databaseFile: File): Int = readCount(databaseFile, "Page")

    private fun readCount(databaseFile: File, table: String): Int =
        SQLiteDatabase.openDatabase(databaseFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
        }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            android.os.SystemClock.sleep(50L)
        }
        assertTrue("Activity did not apply startup integrity lock", condition())
    }
}
