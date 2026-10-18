package app.docscan.core.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.docscan.core.db.DocScanDatabase
import app.docscan.core.model.ArtifactId
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.ImportSessionId
import app.docscan.core.model.PageId
import app.docscan.core.model.RecognitionId
import app.docscan.core.model.SaveManifestId
import app.docscan.core.model.WorkspaceId
import app.docscan.core.model.WorkspaceSessionId
import app.docscan.core.artifact.DirectorySync
import app.docscan.core.artifact.FileArtifactRepository
import app.docscan.core.artifact.VerifiedDeleteResult
import app.docscan.core.cleanup.CleanupArbiter
import app.docscan.core.time.FakeClock
import app.docscan.core.time.TimeReading
import java.util.Properties
import java.util.UUID
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StateStoreTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: DocScanDatabase
    private var workspaceId: WorkspaceId = WorkspaceId.new()
    private lateinit var store: StateStore
    private lateinit var fakeClock: FakeClock

    @Before
    fun setUp() {
        driver = JdbcSqliteDriver(
            JdbcSqliteDriver.IN_MEMORY,
            Properties().apply { setProperty("foreign_keys", "true") },
        )
        DocScanDatabase.Schema.create(driver)
        database = DocScanDatabase(driver)
        workspaceId = WorkspaceId.new()
        fakeClock = FakeClock(TimeReading(1_000L, 2_000L, "test-epoch"))
        store = StateStore(database, fakeClock)
        store.createInitialWorkspace(workspaceId, WorkspaceSessionId.new())
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun captureAtomicallyCommitsPageImageVersionAndProtectiveReference() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val command = captureCommand(artifact)

        val captured = store.commitCapture(command)

        assertEquals(command.pageId, captured.pageId)
        assertEquals(0L, captured.orderIndex)
        assertEquals(1L, store.visiblePageCount(workspaceId))
        assertEquals(1L, database.schemaQueries.workspaceById(workspaceId.value).executeAsOne().order_revision)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne().state)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
    }

    @Test
    fun cameraOriginalSurvivesEditsAndIsReplacedOnlyByRetake() {
        val image = durableArtifact()
        val original = durableArtifact()
        listOf(image, original).forEach(store::recordStagedDurableArtifact)
        val capture = captureCommand(image).copy(captureSource = original)
        store.commitCapture(capture)
        assertEquals(original, store.cameraSource(capture.pageId))
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).isEmpty())
        assertTrue(store.claimUnreferencedArtifacts(100L).isEmpty())

        val edited = durableArtifact()
        store.recordStagedDurableArtifact(edited)
        val editedVersion = ImageVersionId.new()
        store.replacePageImage(ReplacePageImageCommand(workspaceId, capture.pageId, capture.imageVersionId,
            1L, editedVersion, edited, "pixels", "edit", "OCR_LATIN_V1"))
        assertEquals(original, store.cameraSource(capture.pageId))
        store.undoPageImage(workspaceId, capture.pageId, editedVersion, 2L)
        assertEquals(original, store.cameraSource(capture.pageId))

        val retaken = durableArtifact()
        val newOriginal = durableArtifact()
        val retakenVersion = ImageVersionId.new()
        listOf(retaken, newOriginal).forEach(store::recordStagedDurableArtifact)
        store.replacePageImage(ReplacePageImageCommand(workspaceId, capture.pageId, capture.imageVersionId,
            3L, retakenVersion, retaken, "pixels", "retake", "OCR_LATIN_V1", newOriginal))
        assertEquals(newOriginal, store.cameraSource(capture.pageId))
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(original.artifactId.value).executeAsOne())
        assertFalse(store.claimUnreferencedArtifacts(100L).any { it.artifactId == original.artifactId })
        store.undoPageImage(workspaceId, capture.pageId, retakenVersion, 4L)
        assertEquals(original, store.cameraSource(capture.pageId))
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(original.artifactId.value).executeAsOne())
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).isEmpty())
    }

    @Test
    fun invalidCameraSourceRollsBackWholeCaptureAndDoesNotCreateFakeOriginalForLegacyCapture() {
        val image = durableArtifact()
        val original = durableArtifact()
        listOf(image, original).forEach(store::recordStagedDurableArtifact)
        val capture = captureCommand(image)
        assertThrows(StoreInvariantViolation::class.java) {
            store.commitCapture(capture.copy(captureSource = original.copy(sha256 = "f".repeat(64))))
        }
        assertEquals(0L, store.visiblePageCount(workspaceId))
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(image.artifactId.value).executeAsOne().state)
        store.commitCapture(capture)
        assertEquals(null, store.cameraSource(capture.pageId))
    }

    @Test
    fun cameraOriginalIsProtectedForDeleteUndoThenReleasedWhenUndoExpires() {
        val image = durableArtifact()
        val original = durableArtifact()
        listOf(image, original).forEach(store::recordStagedDurableArtifact)
        val capture = captureCommand(image).copy(captureSource = original)
        store.commitCapture(capture)
        store.deletePage(workspaceId, capture.pageId, 1L)
        assertTrue(store.claimUnreferencedArtifacts(100L).none { it.artifactId == original.artifactId })
        fakeClock.set(TimeReading(500L, 1_000L, "test-epoch"))
        assertTrue(store.claimUnreferencedArtifacts(100L).none { it.artifactId == original.artifactId })
        fakeClock.set(TimeReading(1_000L, 2_000L, "test-epoch"))
        store.undoDelete(workspaceId, capture.pageId)
        assertEquals(original, store.cameraSource(capture.pageId))
        store.deletePage(workspaceId, capture.pageId, 3L)
        fakeClock.set(TimeReading(100_000L, 100_000_000_000L, "test-epoch"))
        assertTrue(store.claimUnreferencedArtifacts(100L).any { it.artifactId == original.artifactId })
        assertEquals(null, store.cameraSource(capture.pageId))
    }

    @Test
    fun cameraReferenceIntegrityDetectsWrongOwnerAndProtection() {
        val image = durableArtifact()
        store.recordStagedDurableArtifact(image)
        val capture = captureCommand(image).copy(captureSource = image)
        store.commitCapture(capture)
        assertEquals(image, store.cameraSource(capture.pageId))
        database.schemaQueries.addArtifactReference(image.artifactId.value, "CAPTURE_SOURCE", PageId.new().value, "UNDO")
        val codes = store.artifactReferenceOwnerIntegrityFindings(100L).map { it.code }
        assertTrue(codes.contains("CAPTURE_SOURCE_OWNER_MISSING"))
    }

    @Test
    fun successfulCurrentRecognitionAutoSelectsOnlyWhileCaptureIntentIsPending() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact).copy(autoSelectWhenReady = true)
        store.commitCapture(capture)

        assertEquals(false, store.visiblePages(workspaceId).single().selected)
        assertEquals(1L, database.schemaQueries.pageById(capture.pageId.value).executeAsOne().select_when_ready)

        store.commitRecognition(
            RecognitionCandidate(
                RecognitionId.new(), workspaceId, capture.pageId, capture.imageVersionId,
                1L, "OCR_LATIN_V1", "ready text",
            ),
        )

        assertEquals(true, store.visiblePages(workspaceId).single().selected)
        assertEquals(0L, database.schemaQueries.pageById(capture.pageId.value).executeAsOne().select_when_ready)
    }

    @Test
    fun explicitDeselectBeforeRecognitionCancelsPendingAutoSelect() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact).copy(autoSelectWhenReady = true)
        store.commitCapture(capture)

        store.setSelected(workspaceId, capture.pageId, selected = false)
        store.commitRecognition(
            RecognitionCandidate(
                RecognitionId.new(), workspaceId, capture.pageId, capture.imageVersionId,
                1L, "OCR_LATIN_V1", "ready text",
            ),
        )

        assertEquals(false, store.visiblePages(workspaceId).single().selected)
        assertEquals(0L, database.schemaQueries.pageById(capture.pageId.value).executeAsOne().select_when_ready)
    }

    @Test
    fun clearSelectionDuringRecognitionCancelsPendingAutoSelect() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact).copy(autoSelectWhenReady = true)
        store.commitCapture(capture)

        store.setAllVisiblePagesSelected(workspaceId, selected = false)
        assertEquals(false, store.visiblePages(workspaceId).single().selected)
        assertEquals(0L, database.schemaQueries.pageById(capture.pageId.value).executeAsOne().select_when_ready)
    }

    @Test
    fun staleRecognitionCannotConsumePendingSelectionIntent() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact).copy(autoSelectWhenReady = true)
        store.commitCapture(capture)

        assertThrows(StoreConflict::class.java) {
            store.commitRecognition(
                RecognitionCandidate(
                    RecognitionId.new(), workspaceId, capture.pageId, capture.imageVersionId,
                    2L, "OCR_LATIN_V1", "stale text",
                ),
            )
        }

        assertEquals(false, store.visiblePages(workspaceId).single().selected)
        assertEquals(1L, database.schemaQueries.pageById(capture.pageId.value).executeAsOne().select_when_ready)
        assertEquals(0L, database.schemaQueries.recognitionCount().executeAsOne())
    }

    @Test
    fun recognitionForDifferentDesiredOperationIsRejectedWithoutChangingPage() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact).copy(autoSelectWhenReady = true)
        store.commitCapture(capture)

        assertThrows(StoreConflict::class.java) {
            store.commitRecognition(
                RecognitionCandidate(
                    RecognitionId.new(), workspaceId, capture.pageId, capture.imageVersionId,
                    1L, "OCR_RUS_ENG_TESSERACT_V1", "wrong operation result",
                ),
            )
        }

        assertEquals(false, store.visiblePages(workspaceId).single().selected)
        assertEquals(1L, database.schemaQueries.pageById(capture.pageId.value).executeAsOne().select_when_ready)
        assertEquals(0L, database.schemaQueries.recognitionCount().executeAsOne())
    }

    @Test
    fun selectAllAndClearSelectionAffectVisiblePagesAtomicallyWithoutChangingActivePage() {
        val firstArtifact = durableArtifact()
        store.recordStagedDurableArtifact(firstArtifact)
        val first = captureCommand(firstArtifact)
        store.commitCapture(first)
        val secondArtifact = durableArtifact()
        store.recordStagedDurableArtifact(secondArtifact)
        val second = captureCommand(secondArtifact)
        store.commitCapture(second)

        store.setAllVisiblePagesSelected(workspaceId, selected = true)

        assertEquals(listOf(true, true), store.visiblePages(workspaceId).map { it.selected })
        assertEquals(second.pageId.value, database.schemaQueries.workspaceById(workspaceId.value)
            .executeAsOne().active_page_id)

        store.setAllVisiblePagesSelected(workspaceId, selected = false)

        assertEquals(listOf(false, false), store.visiblePages(workspaceId).map { it.selected })
        assertEquals(second.pageId.value, database.schemaQueries.workspaceById(workspaceId.value)
            .executeAsOne().active_page_id)
    }

    @Test
    fun artifactReferenceOwnerAuditFindsCorruptionAcrossKnownKindsWithoutRepairingRows() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact)
        store.commitCapture(capture)
        val wrongArtifact = durableArtifact()
        store.recordStagedDurableArtifact(wrongArtifact)
        database.schemaQueries.insertSaveManifest(
            "existing-save-manifest", workspaceId.value, 1L,
            "{\"pages\":[{\"artifactId\":\"${artifact.artifactId.value}\"}]}",
        )
        database.schemaQueries.insertExportArtifact(
            "existing-export-artifact", "existing-save-manifest", "PDF",
            "{\"files\":[{\"artifactId\":\"${wrongArtifact.artifactId.value}\"}]}", 1L, 1L,
        )
        database.schemaQueries.insertExportAttempt(
            "existing-export-attempt", "existing-export-artifact", wrongArtifact.artifactId.value,
            1L, "destination", "parameters",
        )
        database.schemaQueries.insertImportSession(
            "existing-import-session", workspaceId.value, "parameters", wrongArtifact.artifactId.value,
            null, "source.pdf",
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "SAVE_MANIFEST", "existing-save-manifest", "SAVE",
        )
        database.schemaQueries.addArtifactReference(
            wrongArtifact.artifactId.value, "EXPORT_ARTIFACT", "existing-export-artifact", "EXPORT",
        )
        database.schemaQueries.addArtifactReference(
            wrongArtifact.artifactId.value, "EXPORT_ATTEMPT", "existing-export-attempt", "RECOVERY",
        )
        database.schemaQueries.addArtifactReference(
            wrongArtifact.artifactId.value, "IMPORT_SESSION", "existing-import-session", "RECOVERY",
        )
        assertEquals(emptyList<ArtifactReferenceIntegrityFinding>(), store.artifactReferenceOwnerIntegrityFindings(20L))
        database.schemaQueries.insertSaveManifest(
            "missing-source-reference-manifest", workspaceId.value, 1L,
            "{\"pages\":[{\"artifactId\":\"${artifact.artifactId.value}\"}]}",
        )
        database.schemaQueries.insertExportArtifact(
            "missing-output-reference-export", "existing-save-manifest", "PDF",
            "{\"files\":[{\"artifactId\":\"${artifact.artifactId.value}\"}]}", 1L, 1L,
        )
        database.schemaQueries.addArtifactReference(
            wrongArtifact.artifactId.value, "SAVE_MANIFEST", "existing-save-manifest", "SAVE",
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "EXPORT_ARTIFACT", "existing-export-artifact", "EXPORT",
        )

        database.schemaQueries.addArtifactReference(
            wrongArtifact.artifactId.value, "IMAGE_VERSION", capture.imageVersionId.value, "AUTHORITATIVE",
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "IMAGE_VERSION", "missing-image-version", "AUTHORITATIVE",
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "SAVE_MANIFEST", "missing-save-manifest", "SAVE",
        )
        driver.execute(
            null,
            "UPDATE ArtifactReference SET protection_class = 'EXPORT' WHERE artifact_id = '${artifact.artifactId.value}' AND ref_kind = 'SAVE_MANIFEST' AND ref_owner_id = 'existing-save-manifest'",
            0,
            null,
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "EXPORT_ARTIFACT", "missing-export-artifact", "EXPORT",
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "EXPORT_ATTEMPT", "missing-export-attempt", "RECOVERY",
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "IMPORT_SESSION", "missing-import-session", "RECOVERY",
        )
        database.schemaQueries.addArtifactReference(
            artifact.artifactId.value, "FUTURE_KIND", "future-owner", "RECOVERY",
        )

        val referencesBefore = database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne()
        val allReferencesBefore = referencesBefore + database.schemaQueries
            .referenceCountForArtifact(wrongArtifact.artifactId.value).executeAsOne()
        val findings = store.artifactReferenceOwnerIntegrityFindings(20L)
        assertEquals(
            listOf(
                "EXPORT_ARTIFACT_ARTIFACT_NOT_IN_PAYLOAD",
                "EXPORT_ARTIFACT_OWNER_MISSING",
                "EXPORT_ARTIFACT_REFERENCE_MISSING",
                "EXPORT_ATTEMPT_OWNER_MISSING",
                "IMAGE_VERSION_ARTIFACT_MISMATCH",
                "IMAGE_VERSION_OWNER_MISSING",
                "IMPORT_SESSION_OWNER_MISSING",
                "SAVE_MANIFEST_ARTIFACT_NOT_IN_FROZEN_PAYLOAD",
                "SAVE_MANIFEST_OWNER_MISSING",
                "SAVE_MANIFEST_PROTECTION_MISMATCH",
                "SAVE_MANIFEST_REFERENCE_MISSING",
                "UNKNOWN_REFERENCE_KIND",
            ),
            findings.map { it.code },
        )
        assertEquals(findings, store.artifactReferenceOwnerIntegrityFindings(20L))
        assertEquals(findings.first(), store.artifactReferenceOwnerIntegrityFindings(1L).single())
        assertEquals(artifact.artifactId, findings.first().artifactId)
        assertEquals("EXPORT_ARTIFACT", findings.first().refKind)
        assertEquals("existing-export-artifact", findings.first().refOwnerId)
        assertEquals(referencesBefore, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
        assertEquals(
            allReferencesBefore,
            database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne()
                + database.schemaQueries.referenceCountForArtifact(wrongArtifact.artifactId.value).executeAsOne(),
        )
        assertEquals("COMMITTED", database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne().state)
        assertEquals(capture.imageVersionId.value, database.schemaQueries.pageById(capture.pageId.value)
            .executeAsOne().current_image_version_id)
        assertThrows(IllegalArgumentException::class.java) { store.artifactReferenceOwnerIntegrityFindings(0L) }
    }

    @Test
    fun artifactReferenceAuditChecksExternalPackageOutputMembershipBothWaysWithoutMutation() {
        val member = durableArtifact()
        val nonMember = durableArtifact()
        listOf(member, nonMember).forEach { artifact ->
            store.recordStagedDurableArtifact(artifact)
            assertEquals(1L, database.schemaQueries.commitArtifact(artifact.artifactId.value).value)
        }
        val exportId = "external-package-membership-export"
        val saveId = "external-package-membership-save"
        database.schemaQueries.insertSaveManifest(saveId, workspaceId.value, 1L, "{}")
        database.schemaQueries.insertExportArtifact(
            exportId,
            saveId,
            "PDF_AND_JPG",
            "{\"formatVersion\":1,\"files\":[{\"kind\":\"PDF\",\"artifactId\":\"${member.artifactId.value}\"}]}",
            1L,
            1L,
        )
        database.schemaQueries.addArtifactReference(member.artifactId.value, "EXPORT_ARTIFACT", exportId, "EXPORT")
        database.schemaQueries.insertProviderQualification(
            "external-package-membership-qualification", "test-adapter", "1", "test-os", "tree",
            "suite-1", "{}",
        )
        database.schemaQueries.insertExternalExportPackage(
            exportId, "content://tree", "Scan", "external-package-membership-qualification",
            "test-adapter", "1", "test-os", "tree", "NONE",
        )
        database.schemaQueries.insertExternalExportPackageOutput(
            exportId, member.artifactId.value, "Scan.pdf", 0L,
        )

        assertEquals(emptyList<ArtifactReferenceIntegrityFinding>(), store.artifactReferenceOwnerIntegrityFindings(20L))

        driver.execute(
            null,
            "DELETE FROM ExternalExportPackageOutput WHERE export_artifact_id = '$exportId'",
            0,
            null,
        )
        database.schemaQueries.insertExternalExportPackageOutput(
            exportId, nonMember.artifactId.value, "Scan.pdf", 0L,
        )
        val referencesBefore = listOf(member, nonMember).sumOf {
            database.schemaQueries.referenceCountForArtifact(it.artifactId.value).executeAsOne()
        }
        val findings = store.artifactReferenceOwnerIntegrityFindings(20L)
        assertEquals(
            listOf(
                "EXTERNAL_PACKAGE_OUTPUT_MAPPING_MISSING",
                "EXTERNAL_PACKAGE_OUTPUT_NOT_IN_EXPORT_PAYLOAD",
            ),
            findings.map { it.code },
        )
        assertEquals(member.artifactId, findings[0].artifactId)
        assertEquals(nonMember.artifactId, findings[1].artifactId)
        assertEquals(listOf(exportId, exportId), findings.map { it.refOwnerId })
        assertEquals(findings, store.artifactReferenceOwnerIntegrityFindings(20L))
        assertEquals(
            referencesBefore,
            listOf(member, nonMember).sumOf {
                database.schemaQueries.referenceCountForArtifact(it.artifactId.value).executeAsOne()
            },
        )
        assertEquals("COMMITTED", database.schemaQueries.artifactById(member.artifactId.value).executeAsOne().state)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(nonMember.artifactId.value).executeAsOne().state)
    }

    @Test
    fun savedPdfEditMetadataAuditFindsMissingMismatchAndNonMemberWithoutMutation() {
        val source = committedExportPackageFixture()
        val baseline = store.artifactReferenceOwnerIntegrityFindings(20L)
        assertTrue(baseline.none { it.code == "SAVE_MANIFEST_REFERENCE_MISSING" })
        database.schemaQueries.insertRecentDocument(
            "saved-pdf-audit-recent",
            source.export.exportArtifactId.value,
            "content://test.provider/source.pdf",
            "Source.pdf",
            1L,
            1_000L,
            "FULL",
        )
        val sourcePdf = source.export.output
        val manifestId = SaveManifestId.new()
        val edit = SavedPdfEditManifestCommand(
            saveManifestId = manifestId,
            workspaceId = workspaceId,
            sourceExportArtifactId = source.export.exportArtifactId,
            sourcePdfArtifactId = sourcePdf.artifactId,
            sourcePdfSha256 = sourcePdf.sha256,
            sourcePageCount = 1L,
            orderedSourcePageIndexes = listOf(0),
        )
        store.beginSavedPdfEditManifest(edit)
        assertEquals(
            baseline.filterNot { it.refKind == "RECENT_DOCUMENT" },
            store.artifactReferenceOwnerIntegrityFindings(20L).filterNot {
                it.refKind == "SAVED_PDF_EDIT_COMPOSITION" || it.refKind == "RECENT_DOCUMENT"
            },
        )

        val composition = database.schemaQueries.savedPdfEditCompositionByManifest(manifestId.value).executeAsOne()
        val manifestBefore = database.schemaQueries.saveManifestById(manifestId.value).executeAsOne().toString()
        val sourceReferenceCount = database.schemaQueries.referenceCountForArtifact(sourcePdf.artifactId.value).executeAsOne()

        driver.execute(
            null,
            "DELETE FROM ArtifactReference WHERE artifact_id = '${sourcePdf.artifactId.value}' " +
                "AND ref_kind = 'SAVE_MANIFEST' AND ref_owner_id = '${manifestId.value}'",
            0,
            null,
        )
        val missingSourceReference = store.artifactReferenceOwnerIntegrityFindings(20L)
        assertEquals(
            1,
            missingSourceReference.count {
                it.code == "SAVE_MANIFEST_REFERENCE_MISSING" && it.refOwnerId == manifestId.value &&
                    it.artifactId == sourcePdf.artifactId
            },
        )
        assertEquals(missingSourceReference, store.artifactReferenceOwnerIntegrityFindings(20L))
        database.schemaQueries.addArtifactReference(
            sourcePdf.artifactId.value,
            "SAVE_MANIFEST",
            manifestId.value,
            "SAVE",
        )
        assertEquals(
            baseline.filterNot { it.refKind == "RECENT_DOCUMENT" },
            store.artifactReferenceOwnerIntegrityFindings(20L).filterNot {
                it.refKind == "SAVED_PDF_EDIT_COMPOSITION" || it.refKind == "RECENT_DOCUMENT"
            },
        )

        driver.execute(
            null,
            "UPDATE SavedPdfEditComposition SET source_page_count = 2 WHERE save_manifest_id = '${manifestId.value}'",
            0,
            null,
        )
        val mismatch = store.artifactReferenceOwnerIntegrityFindings(20L).filter {
            it.refKind == "SAVED_PDF_EDIT_COMPOSITION"
        }
        assertEquals(listOf("SAVED_PDF_EDIT_COMPOSITION_PAYLOAD_MISMATCH"), mismatch.map { it.code })
        assertEquals(sourcePdf.artifactId, mismatch.single().artifactId)
        assertEquals(manifestId.value, mismatch.single().refOwnerId)
        assertEquals(
            mismatch,
            store.artifactReferenceOwnerIntegrityFindings(20L).filter {
                it.refKind == "SAVED_PDF_EDIT_COMPOSITION"
            },
        )
        driver.execute(
            null,
            "UPDATE SavedPdfEditComposition SET source_page_count = 1 WHERE save_manifest_id = '${manifestId.value}'",
            0,
            null,
        )

        driver.execute(
            null,
            "DELETE FROM SavedPdfEditComposition WHERE save_manifest_id = '${manifestId.value}'",
            0,
            null,
        )
        val missing = store.artifactReferenceOwnerIntegrityFindings(20L).filter {
            it.refKind == "SAVED_PDF_EDIT_COMPOSITION"
        }
        assertEquals(listOf("SAVED_PDF_EDIT_COMPOSITION_MISSING"), missing.map { it.code })
        assertEquals(sourcePdf.artifactId, missing.single().artifactId)
        assertEquals(
            missing,
            store.artifactReferenceOwnerIntegrityFindings(20L).filter {
                it.refKind == "SAVED_PDF_EDIT_COMPOSITION"
            },
        )
        database.schemaQueries.insertSavedPdfEditComposition(
            composition.save_manifest_id,
            composition.source_export_artifact_id,
            composition.source_pdf_artifact_id,
            composition.source_pdf_sha256,
            composition.source_page_count,
            composition.output_page_order,
        )

        val otherSaveManifestId = SaveManifestId.new()
        val otherExportArtifactId = ExportArtifactId.new()
        database.schemaQueries.insertSaveManifest(otherSaveManifestId.value, workspaceId.value, 1L, "{}")
        database.schemaQueries.insertExportArtifact(
            otherExportArtifactId.value,
            otherSaveManifestId.value,
            "PDF_WITH_JPG_PAGE_SET",
            "{\"formatVersion\":1,\"files\":[]}",
            1L,
            1L,
        )
        driver.execute(
            null,
            "UPDATE SavedPdfEditComposition SET source_export_artifact_id = '${otherExportArtifactId.value}' " +
                "WHERE save_manifest_id = '${manifestId.value}'",
            0,
            null,
        )
        val nonMember = store.artifactReferenceOwnerIntegrityFindings(20L).filter {
            it.refKind == "SAVED_PDF_EDIT_COMPOSITION"
        }
        assertEquals(
            listOf("SAVED_PDF_EDIT_COMPOSITION_PAYLOAD_MISMATCH", "SAVED_PDF_EDIT_SOURCE_NOT_IN_EXPORT"),
            nonMember.map { it.code },
        )
        assertTrue(nonMember.all { it.artifactId == sourcePdf.artifactId && it.refOwnerId == manifestId.value })
        assertEquals(
            nonMember,
            store.artifactReferenceOwnerIntegrityFindings(20L).filter {
                it.refKind == "SAVED_PDF_EDIT_COMPOSITION"
            },
        )

        val manifestAfter = database.schemaQueries.saveManifestById(manifestId.value).executeAsOne()
        assertEquals(manifestBefore, manifestAfter.toString())
        assertEquals(
            sourceReferenceCount,
            database.schemaQueries.referenceCountForArtifact(sourcePdf.artifactId.value).executeAsOne(),
        )
        assertEquals("COMMITTED", database.schemaQueries.artifactById(sourcePdf.artifactId.value).executeAsOne().state)
    }

    @Test
    fun exportAttemptObservationAuditFindsBrokenLinksWithoutMutation() {
        val fixture = committedExportFixture()
        val provider = qualifiedProvider()
        val outputs = store.committedExportOutputs(fixture.exportArtifactId)
        val firstCommand = beginAttempt(fixture, provider).copy(
            outputArtifactId = outputs[0].artifactId,
            plannedDisplayName = "audit-first.pdf",
            mimeType = "application/pdf",
        )
        val secondCommand = beginAttempt(fixture, provider).copy(
            exportAttemptId = ExportAttemptId.new(),
            outputArtifactId = outputs[1].artifactId,
            plannedDisplayName = "audit-second.jpg",
            mimeType = "image/jpeg",
        )
        listOf(
            firstCommand to observation("audit-observation-first", "first"),
            secondCommand to observation("audit-observation-second", "second"),
        ).forEach { (command, providerObservation) ->
            store.beginExportAttempt(command)
            store.finishWritingExportAttempt(
                command.exportAttemptId,
                ExportAttemptOutcome.SUCCEEDED,
                "provider:${command.exportAttemptId.value}",
                null,
                false,
                providerObservation,
            )
        }
        val relevantKinds = setOf("EXPORT_ATTEMPT_OBSERVATION", "PROVIDER_OPERATION_OBSERVATION")
        val baseline = store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind in relevantKinds }
        assertEquals(emptyList<ArtifactReferenceIntegrityFinding>(), baseline)

        driver.execute(
            null,
            "UPDATE ExportAttempt SET operation_observation_id = 'missing-observation' " +
                "WHERE export_attempt_id = '${firstCommand.exportAttemptId.value}'",
            0,
            null,
        )
        val missingAttemptBefore = database.schemaQueries.exportAttemptById(firstCommand.exportAttemptId.value)
            .executeAsOne().toString()
        val firstObservationBefore = database.schemaQueries.providerOperationObservationById("audit-observation-first")
            .executeAsOne().toString()
        val missing = store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind in relevantKinds }
        assertEquals(
            listOf("EXPORT_ATTEMPT_OBSERVATION_MISSING", "PROVIDER_OBSERVATION_BACKLINK_MISSING"),
            missing.map { it.code },
        )
        assertEquals(missing, store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind in relevantKinds })
        assertEquals(missingAttemptBefore, database.schemaQueries.exportAttemptById(firstCommand.exportAttemptId.value)
            .executeAsOne().toString())
        assertEquals(firstObservationBefore, database.schemaQueries.providerOperationObservationById("audit-observation-first")
            .executeAsOne().toString())
        driver.execute(
            null,
            "UPDATE ExportAttempt SET operation_observation_id = 'audit-observation-first' " +
                "WHERE export_attempt_id = '${firstCommand.exportAttemptId.value}'",
            0,
            null,
        )

        driver.execute(
            null,
            "UPDATE ExportAttempt SET operation_observation_id = 'audit-observation-second' " +
                "WHERE export_attempt_id = '${firstCommand.exportAttemptId.value}'",
            0,
            null,
        )
        val mismatch = store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind in relevantKinds }
        assertEquals(
            listOf("EXPORT_ATTEMPT_OBSERVATION_OWNER_MISMATCH", "PROVIDER_OBSERVATION_BACKLINK_MISSING"),
            mismatch.map { it.code },
        )
        assertEquals(mismatch, store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind in relevantKinds })
        driver.execute(
            null,
            "UPDATE ExportAttempt SET operation_observation_id = 'audit-observation-first' " +
                "WHERE export_attempt_id = '${firstCommand.exportAttemptId.value}'",
            0,
            null,
        )

        database.schemaQueries.insertProviderOperationObservation(
            "audit-observation-orphan",
            firstCommand.exportAttemptId.value,
            "orphan",
            null,
            null,
            null,
            null,
            null,
        )
        val orphanBefore = database.schemaQueries.providerOperationObservationById("audit-observation-orphan")
            .executeAsOne().toString()
        val orphan = store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind in relevantKinds }
        assertEquals(listOf("PROVIDER_OBSERVATION_BACKLINK_MISSING"), orphan.map { it.code })
        assertEquals(firstCommand.exportAttemptId.value, orphan.single().refOwnerId)
        assertEquals(orphan, store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind in relevantKinds })
        assertEquals(orphanBefore, database.schemaQueries.providerOperationObservationById("audit-observation-orphan")
            .executeAsOne().toString())
    }

    @Test
    fun externalPackageQualificationTupleAuditFindsEveryMismatchWithoutMutation() {
        val fixture = committedExportPackageFixture()
        val exportId = fixture.export.exportArtifactId.value
        val packageBefore = database.schemaQueries.externalExportPackageById(exportId).executeAsOne()
        val qualificationBefore = database.schemaQueries.providerQualificationById(
            packageBefore.provider_qualification_id,
        ).executeAsOne()
        val pdfOutput = store.committedExportOutputs(fixture.export.exportArtifactId)
            .single { it.mimeType == "application/pdf" }
        val relevantKind = "EXTERNAL_PACKAGE_QUALIFICATION"
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none { it.refKind == relevantKind })

        val mismatches = listOf(
            "adapter_id" to "different-adapter",
            "provider_version" to "different-provider-version",
            "os_version" to "different-os-version",
            "destination_access_class" to "different-access-class",
        )
        mismatches.forEachIndexed { index, (column, changedValue) ->
            driver.execute(
                null,
                "UPDATE ExternalExportPackage SET $column = '$changedValue' WHERE export_artifact_id = '$exportId'",
                0,
                null,
            )
            val corruptRow = database.schemaQueries.externalExportPackageById(exportId).executeAsOne().toString()
            val findings = store.artifactReferenceOwnerIntegrityFindings(100L).filter {
                it.refKind == relevantKind
            }
            assertEquals(listOf("EXTERNAL_PACKAGE_QUALIFICATION_TUPLE_MISMATCH"), findings.map { it.code })
            assertEquals(pdfOutput.artifactId, findings.single().artifactId)
            assertEquals(exportId, findings.single().refOwnerId)
            assertEquals(
                findings,
                store.artifactReferenceOwnerIntegrityFindings(100L).filter { it.refKind == relevantKind },
            )
            assertEquals(corruptRow, database.schemaQueries.externalExportPackageById(exportId).executeAsOne().toString())
            assertEquals(
                qualificationBefore,
                database.schemaQueries.providerQualificationById(packageBefore.provider_qualification_id).executeAsOne(),
            )
            val originalValue = when (column) {
                "adapter_id" -> packageBefore.adapter_id
                "provider_version" -> packageBefore.provider_version
                "os_version" -> packageBefore.os_version
                else -> packageBefore.destination_access_class
            }
            driver.execute(
                null,
                "UPDATE ExternalExportPackage SET $column = '$originalValue' WHERE export_artifact_id = '$exportId'",
                0,
                null,
            )
            if (index < mismatches.lastIndex) {
                assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none { it.refKind == relevantKind })
            }
        }
        assertEquals(packageBefore, database.schemaQueries.externalExportPackageById(exportId).executeAsOne())
    }

    @Test
    fun recentDocumentAuditChecksConfirmedPdfProjectionWithoutMutation() {
        val fixture = committedExportPackageFixture()
        val outputs = store.committedExportOutputs(fixture.export.exportArtifactId)
        outputs.forEachIndexed { index, output ->
            val command = beginAttempt(fixture.export, fixture.provider).copy(
                exportAttemptId = ExportAttemptId.new(),
                outputArtifactId = output.artifactId,
                plannedDisplayName = if (index == 0) "Scan.pdf" else "Scan-001.jpg",
                mimeType = if (index == 0) "application/pdf" else "image/jpeg",
            )
            store.beginExportAttempt(command)
            store.finishWritingExportAttempt(
                command.exportAttemptId,
                ExportAttemptOutcome.SUCCEEDED,
                "content://test.provider/${command.exportAttemptId.value}",
                null,
                false,
                observation("recent-audit-${command.exportAttemptId.value}", "fully read back"),
            )
        }
        val recent = store.recentDocuments(1_000L).single()
        val exportId = fixture.export.exportArtifactId.value
        val pdfOutput = outputs.first()
        val relevantKind = "RECENT_DOCUMENT"
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none { it.refKind == relevantKind })

        driver.execute(
            null,
            "UPDATE ExternalExportPackage SET state = 'PARTIAL' WHERE export_artifact_id = '$exportId'",
            0,
            null,
        )
        val packageFinding = store.artifactReferenceOwnerIntegrityFindings(100L).filter {
            it.refKind == relevantKind
        }
        assertEquals(listOf("RECENT_DOCUMENT_PACKAGE_NOT_CONFIRMED"), packageFinding.map { it.code })
        assertEquals(pdfOutput.artifactId, packageFinding.single().artifactId)
        assertEquals(packageFinding, store.artifactReferenceOwnerIntegrityFindings(100L).filter {
            it.refKind == relevantKind
        })
        driver.execute(
            null,
            "UPDATE ExternalExportPackage SET state = 'SUCCEEDED' WHERE export_artifact_id = '$exportId'",
            0,
            null,
        )

        val corruptions = listOf(
            Triple("document_uri", "content://tampered", "RECENT_DOCUMENT_URI_MISMATCH"),
            Triple("display_name", "Other.pdf", "RECENT_DOCUMENT_DISPLAY_NAME_MISMATCH"),
            Triple("page_count", "2", "RECENT_DOCUMENT_PAGE_COUNT_MISMATCH"),
            Triple("ocr_status", "NONE", "RECENT_DOCUMENT_OCR_STATUS_MISMATCH"),
        )
        corruptions.forEach { (column, changedValue, expectedCode) ->
            driver.execute(
                null,
                "UPDATE RecentDocument SET $column = '$changedValue' WHERE export_artifact_id = '$exportId'",
                0,
                null,
            )
            val recentBeforeRead = database.schemaQueries.recentDocumentByExport(exportId).executeAsOne().toString()
            val findings = store.artifactReferenceOwnerIntegrityFindings(100L).filter {
                it.refKind == relevantKind
            }
            assertEquals(listOf(expectedCode), findings.map { it.code })
            assertEquals(pdfOutput.artifactId, findings.single().artifactId)
            assertEquals(exportId, findings.single().refOwnerId)
            assertEquals(findings, store.artifactReferenceOwnerIntegrityFindings(100L).filter {
                it.refKind == relevantKind
            })
            assertEquals(
                recentBeforeRead,
                database.schemaQueries.recentDocumentByExport(exportId).executeAsOne().toString(),
            )
            val restoreValue = when (column) {
                "document_uri" -> recent.documentUri
                "display_name" -> recent.displayName
                "page_count" -> recent.pageCount.toString()
                else -> recent.ocrStatus
            }
            driver.execute(
                null,
                "UPDATE RecentDocument SET $column = '$restoreValue' WHERE export_artifact_id = '$exportId'",
                0,
                null,
            )
        }
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none { it.refKind == relevantKind })
    }

    @Test
    fun pdfOnlySafPackageRecentUsesFrozenPageCountRatherThanExternalJpgOutputs() {
        val fixture = committedExportPackageFixture(pageCount = 2, pdfOnly = true)
        val pdf = store.committedExportOutputs(fixture.export.exportArtifactId)
            .single { it.mimeType == "application/pdf" }
        val command = beginAttempt(fixture.export, fixture.provider).copy(
            outputArtifactId = pdf.artifactId,
            plannedDisplayName = "Scan.pdf",
            mimeType = "application/pdf",
        )

        store.beginExportAttempt(command)
        store.finishWritingExportAttempt(
            command.exportAttemptId,
            ExportAttemptOutcome.SUCCEEDED,
            "content://test.provider/document/pdf-only",
            null,
            false,
            observation("pdf-only-success", "PDF read-back matched"),
        )

        val recent = store.recentDocuments(1_000L).single()
        assertEquals(2L, recent.pageCount)
        assertEquals("SUCCEEDED", store.externalExportPackage(fixture.export.exportArtifactId)?.state)
        assertEquals(0L, database.schemaQueries.externalExportPackageById(fixture.export.exportArtifactId.value)
            .executeAsOne().include_jpg)
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none {
            it.refKind == "RECENT_DOCUMENT" || it.refKind == "EXTERNAL_EXPORT_PACKAGE_OUTPUT"
        })
    }

    @Test
    fun fullSafPackageStillFlagsMissingJpgOutputMapping() {
        val fixture = committedExportPackageFixture(pageCount = 2)
        val jpg = store.committedExportOutputs(fixture.export.exportArtifactId)
            .first { it.mimeType == "image/jpeg" }
        driver.execute(
            null,
            "DELETE FROM ExternalExportPackageOutput WHERE export_artifact_id = '${fixture.export.exportArtifactId.value}' AND output_artifact_id = '${jpg.artifactId.value}'",
            0,
            null,
        )

        val findings = store.artifactReferenceOwnerIntegrityFindings(100L).filter {
            it.code == "EXTERNAL_PACKAGE_OUTPUT_MAPPING_MISSING"
        }
        assertEquals(1, findings.size)
        assertEquals(jpg.artifactId, findings.single().artifactId)
        assertEquals(fixture.export.exportArtifactId.value, findings.single().refOwnerId)
        assertEquals(1L, database.schemaQueries.externalExportPackageById(fixture.export.exportArtifactId.value)
            .executeAsOne().include_jpg)
    }

    @Test
    fun artifactReferenceClosureFindsMissingCurrentImportAndActiveExportEdges() {
        val pageArtifact = durableArtifact()
        store.recordStagedDurableArtifact(pageArtifact)
        val capture = captureCommand(pageArtifact)
        store.commitCapture(capture)

        val replacementArtifact = durableArtifact()
        store.recordStagedDurableArtifact(replacementArtifact)
        val replacementVersion = ImageVersionId.new()
        assertEquals(
            2L,
            store.replacePageImage(
                ReplacePageImageCommand(
                    workspaceId = workspaceId,
                    pageId = capture.pageId,
                    expectedImageVersionId = capture.imageVersionId,
                    expectedContentRevision = 1L,
                    newImageVersionId = replacementVersion,
                    artifact = replacementArtifact,
                    coordinateSpace = "source-pixel-v1",
                    transformProvenance = "test-replacement",
                    processingDesiredState = "NONE",
                ),
            ),
        )

        val workflowArtifact = durableArtifact()
        store.recordStagedDurableArtifact(workflowArtifact)
        assertEquals(1L, database.schemaQueries.commitArtifact(workflowArtifact.artifactId.value).value)
        database.schemaQueries.insertImportSession(
            "open-import-session", workspaceId.value, "parameters", workflowArtifact.artifactId.value,
            null, "source.pdf",
        )
        database.schemaQueries.insertSaveManifest("closure-save-manifest", workspaceId.value, 1L, "{}")
        database.schemaQueries.insertExportArtifact(
            "closure-export-artifact", "closure-save-manifest", "PDF",
            "{\"files\":[{\"artifactId\":\"${workflowArtifact.artifactId.value}\"}]}", 1L, 1L,
        )
        database.schemaQueries.insertExportAttempt(
            "active-export-attempt", "closure-export-artifact", workflowArtifact.artifactId.value,
            1L, "destination", "parameters",
        )
        database.schemaQueries.addArtifactReference(
            workflowArtifact.artifactId.value, "IMPORT_SESSION", "open-import-session", "RECOVERY",
        )
        database.schemaQueries.addArtifactReference(
            workflowArtifact.artifactId.value, "EXPORT_ARTIFACT", "closure-export-artifact", "EXPORT",
        )
        database.schemaQueries.addArtifactReference(
            workflowArtifact.artifactId.value, "EXPORT_ATTEMPT", "active-export-attempt", "RECOVERY",
        )
        assertEquals(emptyList<ArtifactReferenceIntegrityFinding>(), store.artifactReferenceOwnerIntegrityFindings(20L))

        driver.execute(
            null,
            "DELETE FROM ArtifactReference WHERE ref_kind = 'IMAGE_VERSION' AND ref_owner_id IN ('${capture.imageVersionId.value}', '${replacementVersion.value}')",
            0,
            null,
        )
        driver.execute(
            null,
            "DELETE FROM ArtifactReference WHERE ref_kind = 'IMPORT_SESSION' AND ref_owner_id = 'open-import-session'",
            0,
            null,
        )
        database.schemaQueries.deleteExportAttemptArtifactReferences("active-export-attempt")

        val allReferencesBefore = listOf(pageArtifact, replacementArtifact, workflowArtifact).sumOf {
            database.schemaQueries.referenceCountForArtifact(it.artifactId.value).executeAsOne()
        }
        val findings = store.artifactReferenceOwnerIntegrityFindings(20L)
        assertEquals(
            listOf(
                "CURRENT_IMAGE_VERSION_REFERENCE_MISSING",
                "EXPORT_ATTEMPT_REFERENCE_MISSING",
                "IMPORT_SESSION_REFERENCE_MISSING",
                "UNDO_IMAGE_VERSION_REFERENCE_MISSING",
            ),
            findings.map { it.code },
        )
        assertEquals(replacementVersion.value, findings[0].refOwnerId)
        assertEquals("active-export-attempt", findings[1].refOwnerId)
        assertEquals("open-import-session", findings[2].refOwnerId)
        assertEquals(capture.imageVersionId.value, findings[3].refOwnerId)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(pageArtifact.artifactId.value).executeAsOne().state)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(workflowArtifact.artifactId.value).executeAsOne().state)
        assertEquals(
            allReferencesBefore,
            listOf(pageArtifact, replacementArtifact, workflowArtifact).sumOf {
                database.schemaQueries.referenceCountForArtifact(it.artifactId.value).executeAsOne()
            },
        )
        assertEquals(replacementVersion.value, database.schemaQueries.pageById(capture.pageId.value)
            .executeAsOne().current_image_version_id)
    }

    @Test
    fun workspaceIntegrityDiagnosticsFindCurrentOwnerAndProtectionFailuresWithoutRepairingThem() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact)
        store.commitCapture(capture)
        assertEquals(emptyList<WorkspaceIntegrityFinding>(), store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L))

        driver.execute(
            null,
            "UPDATE ImageVersion SET page_id = 'wrong-owner' WHERE image_version_id = '${capture.imageVersionId.value}'",
            0,
            null,
        )
        database.schemaQueries.deleteArtifactReference(capture.imageVersionId.value)
        database.schemaQueries.setActivePageOnly("missing-page", workspaceId.value)

        val findings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
        assertEquals(
            setOf(
                "CURRENT_IMAGE_VERSION_PAGE_MISMATCH",
                "CURRENT_IMAGE_VERSION_REFERENCE_MISSING",
                "ACTIVE_PAGE_NOT_VISIBLE",
            ),
            findings.map { it.code }.toSet(),
        )
        assertTrue(findings.filter { it.code != "ACTIVE_PAGE_NOT_VISIBLE" }
            .all { it.pageId == capture.pageId.value })
        assertEquals("missing-page", findings.single { it.code == "ACTIVE_PAGE_NOT_VISIBLE" }.pageId)
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
        assertEquals("wrong-owner", database.schemaQueries.imageVersionById(capture.imageVersionId.value).executeAsOne().page_id)
        assertEquals(capture.imageVersionId.value, database.schemaQueries.pageById(capture.pageId.value)
            .executeAsOne().current_image_version_id)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne().state)
        assertThrows(IllegalArgumentException::class.java) {
            store.workspaceImageReferenceIntegrityFindings(workspaceId, 0L)
        }
    }

    @Test
    fun workspaceIntegrityDiagnosticsReportDanglingCurrentAndUndoImageVersionIds() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact)
        store.commitCapture(capture)
        val missingCurrent = ImageVersionId.new().value
        val missingUndo = ImageVersionId.new().value

        driver.execute(null, "PRAGMA foreign_keys = OFF", 0, null)
        driver.execute(
            null,
            "UPDATE Page SET current_image_version_id = '$missingCurrent', undo_image_version_id = '$missingUndo' " +
                "WHERE page_id = '${capture.pageId.value}'",
            0,
            null,
        )
        driver.execute(null, "PRAGMA foreign_keys = ON", 0, null)

        val findings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
        assertEquals(
            listOf("CURRENT_IMAGE_VERSION_MISSING", "UNDO_IMAGE_VERSION_MISSING"),
            findings.map { it.code },
        )
        assertTrue(findings.all { it.pageId == capture.pageId.value })
        val persisted = database.schemaQueries.pageById(capture.pageId.value).executeAsOne()
        assertEquals(missingCurrent, persisted.current_image_version_id)
        assertEquals(missingUndo, persisted.undo_image_version_id)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne().state)
    }

    @Test
    fun workspaceIntegrityDiagnosticsReportExtraImageVersionProtectionReferences() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val capture = captureCommand(artifact)
        store.commitCapture(capture)
        val extraArtifact = durableArtifact()
        store.recordStagedDurableArtifact(extraArtifact)
        assertEquals(1L, database.schemaQueries.commitArtifact(extraArtifact.artifactId.value).value)
        database.schemaQueries.addArtifactReference(
            extraArtifact.artifactId.value,
            "IMAGE_VERSION",
            capture.imageVersionId.value,
            "AUTHORITATIVE",
        )

        val findings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
        assertEquals(listOf("CURRENT_IMAGE_VERSION_REFERENCE_MISMATCH"), findings.map { it.code })
        assertEquals(capture.pageId.value, findings.single().pageId)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(extraArtifact.artifactId.value).executeAsOne())
        assertEquals("COMMITTED", database.schemaQueries.artifactById(extraArtifact.artifactId.value).executeAsOne().state)

        val undoSourceArtifact = durableArtifact()
        store.recordStagedDurableArtifact(undoSourceArtifact)
        val undoCapture = captureCommand(undoSourceArtifact)
        store.commitCapture(undoCapture)
        val replacementArtifact = durableArtifact()
        store.recordStagedDurableArtifact(replacementArtifact)
        store.replacePageImage(
            ReplacePageImageCommand(
                workspaceId = workspaceId,
                pageId = undoCapture.pageId,
                expectedImageVersionId = undoCapture.imageVersionId,
                expectedContentRevision = 1L,
                newImageVersionId = ImageVersionId.new(),
                artifact = replacementArtifact,
                coordinateSpace = "source-pixel-v1",
                transformProvenance = "integrity-diagnostic-fixture",
                processingDesiredState = "NONE",
            ),
        )
        database.schemaQueries.addArtifactReference(
            extraArtifact.artifactId.value,
            "IMAGE_VERSION",
            undoCapture.imageVersionId.value,
            "UNDO",
        )
        val undoFindings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
        assertEquals(
            listOf("CURRENT_IMAGE_VERSION_REFERENCE_MISMATCH", "UNDO_IMAGE_VERSION_REFERENCE_MISMATCH"),
            undoFindings.map { it.code },
        )
        assertEquals(setOf(capture.pageId.value, undoCapture.pageId.value), undoFindings.map { it.pageId }.toSet())

        val wrongClassArtifact = durableArtifact()
        store.recordStagedDurableArtifact(wrongClassArtifact)
        val wrongClassCapture = captureCommand(wrongClassArtifact)
        store.commitCapture(wrongClassCapture)
        database.schemaQueries.deleteArtifactReference(wrongClassCapture.imageVersionId.value)
        database.schemaQueries.addArtifactReference(
            wrongClassArtifact.artifactId.value,
            "IMAGE_VERSION",
            wrongClassCapture.imageVersionId.value,
            "UNDO",
        )
        val wrongClassFindings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
            .filter { it.pageId == wrongClassCapture.pageId.value }
        assertEquals(
            listOf("CURRENT_IMAGE_VERSION_REFERENCE_MISMATCH", "CURRENT_IMAGE_VERSION_REFERENCE_MISSING"),
            wrongClassFindings.map { it.code },
        )
        assertEquals("UNDO", database.schemaQueries.artifactReferenceProtection(wrongClassCapture.imageVersionId.value).executeAsOne())
    }

    @Test
    fun workspaceIntegrityDiagnosticsReportMissingCurrentAndUnavailableUndoArtifacts() {
        val missingArtifactPageArtifact = durableArtifact()
        store.recordStagedDurableArtifact(missingArtifactPageArtifact)
        val missingArtifactPage = captureCommand(missingArtifactPageArtifact)
        store.commitCapture(missingArtifactPage)
        val missingArtifactId = ArtifactId.new().value
        driver.execute(null, "PRAGMA foreign_keys = OFF", 0, null)
        driver.execute(
            null,
            "UPDATE ImageVersion SET artifact_id = '$missingArtifactId' " +
                "WHERE image_version_id = '${missingArtifactPage.imageVersionId.value}'",
            0,
            null,
        )
        driver.execute(null, "PRAGMA foreign_keys = ON", 0, null)

        val missingFindings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
            .filter { it.pageId == missingArtifactPage.pageId.value }
        assertTrue(missingFindings.any { it.code == "CURRENT_IMAGE_ARTIFACT_MISSING" })
        assertEquals(
            missingArtifactId,
            database.schemaQueries.imageVersionById(missingArtifactPage.imageVersionId.value).executeAsOne().artifact_id,
        )

        val undoArtifact = durableArtifact()
        store.recordStagedDurableArtifact(undoArtifact)
        val undoPage = captureCommand(undoArtifact)
        store.commitCapture(undoPage)
        val replacementArtifact = durableArtifact()
        store.recordStagedDurableArtifact(replacementArtifact)
        store.replacePageImage(
            ReplacePageImageCommand(
                workspaceId = workspaceId,
                pageId = undoPage.pageId,
                expectedImageVersionId = undoPage.imageVersionId,
                expectedContentRevision = 1L,
                newImageVersionId = ImageVersionId.new(),
                artifact = replacementArtifact,
                coordinateSpace = "source-pixel-v1",
                transformProvenance = "integrity-diagnostic-fixture",
                processingDesiredState = "NONE",
            ),
        )
        driver.execute(
            null,
            "UPDATE ArtifactRecord SET storage_state = 'DELETED' WHERE artifact_id = '${undoArtifact.artifactId.value}'",
            0,
            null,
        )

        val unavailableFindings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
            .filter { it.pageId == undoPage.pageId.value }
        assertEquals(listOf("UNDO_IMAGE_ARTIFACT_UNAVAILABLE"), unavailableFindings.map { it.code })
        assertEquals("DELETED", database.schemaQueries.artifactById(undoArtifact.artifactId.value)
            .executeAsOne().storage_state)
    }

    @Test
    fun workspaceIntegrityDiagnosticsDetectMissingUndoProtectionWithoutChangingIt() {
        val originalArtifact = durableArtifact()
        store.recordStagedDurableArtifact(originalArtifact)
        val original = captureCommand(originalArtifact)
        store.commitCapture(original)
        val replacementArtifact = durableArtifact()
        store.recordStagedDurableArtifact(replacementArtifact)
        val replacementVersion = ImageVersionId.new()
        store.replacePageImage(
            ReplacePageImageCommand(
                workspaceId = workspaceId,
                pageId = original.pageId,
                expectedImageVersionId = original.imageVersionId,
                expectedContentRevision = 1L,
                newImageVersionId = replacementVersion,
                artifact = replacementArtifact,
                coordinateSpace = "source-pixel-v1",
                transformProvenance = "integrity-diagnostic-fixture",
                processingDesiredState = "NONE",
            ),
        )
        assertEquals(emptyList<WorkspaceIntegrityFinding>(), store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L))

        driver.execute(
            null,
            "UPDATE ImageVersion SET page_id = 'wrong-owner' WHERE image_version_id = '${original.imageVersionId.value}'",
            0,
            null,
        )
        database.schemaQueries.deleteArtifactReference(original.imageVersionId.value)

        val findings = store.workspaceImageReferenceIntegrityFindings(workspaceId, 20L)
        assertEquals(
            listOf("UNDO_IMAGE_VERSION_PAGE_MISMATCH", "UNDO_IMAGE_VERSION_REFERENCE_MISSING"),
            findings.map { it.code },
        )
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(originalArtifact.artifactId.value).executeAsOne())
        assertEquals("AUTHORITATIVE", database.schemaQueries.artifactReferenceProtection(replacementVersion.value).executeAsOne())
        assertEquals(original.imageVersionId.value, database.schemaQueries.pageById(original.pageId.value)
            .executeAsOne().undo_image_version_id)
    }

    @Test
    fun recognitionGeometryCommitsAtomicallyAndRejectsWordsOutsideSourceImage() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val page = captureCommand(artifact)
        store.commitCapture(page)
        val recognitionId = RecognitionId.new()
        store.commitRecognition(
            RecognitionCandidate(
                recognitionId, workspaceId, page.pageId, page.imageVersionId,
                1L, "OCR_LATIN_V1", "Invoice 8472",
                RecognitionGeometry(
                    1200L, 800L, OCR_WORD_COORDINATE_SPACE,
                    listOf(
                        RecognizedWord("Invoice", 10L, 20L, 210L, 68L, 96.5),
                        RecognizedWord("8472", 230L, 20L, 330L, 68L, 91.0),
                    ),
                ),
            ),
        )

        val storedPayload = database.schemaQueries.recognitionById(recognitionId.value).executeAsOne().payload
        val decoded = RecognitionPayloadCodec.decode(storedPayload)
        assertEquals("Invoice 8472", decoded.text)
        assertEquals(1200L, decoded.geometry?.sourceWidthPx)
        assertEquals(800L, decoded.geometry?.sourceHeightPx)
        assertEquals(OCR_WORD_COORDINATE_SPACE, decoded.geometry?.coordinateSpace)
        assertEquals(listOf("Invoice", "8472"), decoded.geometry?.words?.map { it.text })
        assertEquals(listOf(0L, 1L), decoded.geometry?.words?.indices?.map(Int::toLong))
        assertEquals("Invoice 8472", store.workspaceProjection(workspaceId).pages.single().recognizedText)

        val rejectedId = RecognitionId.new()
        assertThrows(StoreInvariantViolation::class.java) {
            store.commitRecognition(
                RecognitionCandidate(
                    rejectedId, workspaceId, page.pageId, page.imageVersionId,
                    1L, "OCR_LATIN_V1", "bad bounds",
                    RecognitionGeometry(
                        1200L, 800L, OCR_WORD_COORDINATE_SPACE,
                        listOf(RecognizedWord("outside", 1190L, 10L, 1210L, 30L, 80.0)),
                    ),
                ),
            )
        }
        assertEquals(null, database.schemaQueries.recognitionById(rejectedId.value).executeAsOneOrNull())
        assertEquals(recognitionId.value, database.schemaQueries.pageById(page.pageId.value)
            .executeAsOne().current_recognition_id)
    }

    @Test
    fun legacyPlainRecognitionPayloadRemainsReadableAndMalformedGeometryFailsClosed() {
        assertEquals("old plain OCR text", RecognitionPayloadCodec.decode("old plain OCR text").text)
        assertEquals(null, RecognitionPayloadCodec.decode("old plain OCR text").geometry)
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionPayloadCodec.decode("DOCSCAN-OCR-GEOMETRY/1\nnot-base64\nheader")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionPayloadCodec.decode("DOCSCAN-OCR-LAYOUT/2\nnot-base64\nheader")
        }
    }

    @Test
    fun recognitionLayoutRoundTripsAndFreezesInSaveManifest() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val page = captureCommand(artifact).copy(selected = true)
        store.commitCapture(page)
        val recognitionId = RecognitionId.new()

        val box = NormalizedBox(50f / 1400f, 50f / 900f, 800f / 1400f, 150f / 900f)
        val word1Box = NormalizedBox(50f / 1400f, 50f / 900f, 500f / 1400f, 150f / 900f)
        val word2Box = NormalizedBox(520f / 1400f, 50f / 900f, 800f / 1400f, 150f / 900f)
        val layout = RecognitionLayout(
            sourceWidthPx = 1400,
            sourceHeightPx = 900,
            blocks = listOf(
                OcrBlock(
                    id = "block_1",
                    type = BlockType.TITLE,
                    box = box,
                    readingOrder = 0,
                    paragraphs = listOf(
                        OcrParagraph(
                            box = box,
                            lines = listOf(
                                OcrLine(
                                    box = box,
                                    baseline = Baseline(0.0f, -5.0f),
                                    xHeight = null,
                                    words = listOf(
                                        OcrWord("Счёт-фактура", word1Box, 98.0f),
                                        OcrWord("№123", word2Box, 95.0f),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        store.commitRecognition(
            RecognitionCandidate(
                recognitionId = recognitionId,
                workspaceId = workspaceId,
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                contentRevision = 1L,
                desiredOperationKey = "OCR_LATIN_V1",
                recognizedText = layout.toPlainText(),
                layout = layout,
            ),
        )

        val storedPayload = database.schemaQueries.recognitionById(recognitionId.value).executeAsOne().payload
        assertTrue(storedPayload.startsWith("DOCSCAN-OCR-LAYOUT/2\n"))
        val decoded = RecognitionPayloadCodec.decode(storedPayload)
        assertEquals("Счёт-фактура №123", decoded.text)
        assertNotNull(decoded.layout)
        assertEquals(1400, decoded.layout?.sourceWidthPx)
        assertEquals(900, decoded.layout?.sourceHeightPx)
        assertEquals(1, decoded.layout?.blocks?.size)
        assertEquals(BlockType.TITLE, decoded.layout?.blocks?.first()?.type)
        assertEquals("Счёт-фактура", decoded.layout?.blocks?.first()?.lines?.first()?.words?.first()?.text)

        // Legacy geometry compatibility projection
        assertNotNull(decoded.geometry)
        assertEquals(1400L, decoded.geometry?.sourceWidthPx)
        assertEquals(2, decoded.geometry?.words?.size)

        // Manifest freezing
        val manifestId = SaveManifestId.new()
        val frozen = store.freezeSelectedPagesForSave(workspaceId, manifestId)
        val frozenPage = frozen.pages.single()
        assertEquals("Счёт-фактура №123", frozenPage.recognizedText)
        assertNotNull(frozenPage.recognizedLayout)
        assertEquals(1400, frozenPage.recognizedLayout?.sourceWidthPx)
        assertTrue(frozen.frozenPayload.contains("\"recognizedLayout\":{\"formatVersion\":2"))
    }

    @Test
    fun saveManifestFreezesSelectedOrderContentAndProtectsExactSourceArtifacts() {
        val firstArtifact = durableArtifact()
        store.recordStagedDurableArtifact(firstArtifact)
        val first = captureCommand(firstArtifact).copy(selected = true)
        store.commitCapture(first)
        store.commitRecognition(
            RecognitionCandidate(
                RecognitionId.new(), workspaceId, first.pageId, first.imageVersionId,
                1L, "OCR_LATIN_V1", "Invoice \"A\"\\B\nTotal 42",
                RecognitionGeometry(
                    1000L, 1400L, OCR_WORD_COORDINATE_SPACE,
                    listOf(RecognizedWord("Документ", 25L, 40L, 230L, 95L, 88.5)),
                ),
            ),
        )

        val secondArtifact = durableArtifact()
        store.recordStagedDurableArtifact(secondArtifact)
        val second = captureCommand(secondArtifact).copy(selected = true)
        store.commitCapture(second)

        val excludedArtifact = durableArtifact()
        store.recordStagedDurableArtifact(excludedArtifact)
        val excluded = captureCommand(excludedArtifact)
        store.commitCapture(excluded)
        store.reorderPages(workspaceId, 3L, listOf(excluded.pageId, second.pageId, first.pageId))

        val manifestId = SaveManifestId.new()
        val frozen = store.freezeSelectedPagesForSave(workspaceId, manifestId)

        assertEquals(listOf(second.pageId, first.pageId), frozen.pages.map { it.pageId })
        assertEquals(listOf(1L, 2L), frozen.pages.map { it.orderIndex })
        assertEquals(listOf(null, "Invoice \"A\"\\B\nTotal 42"), frozen.pages.map { it.recognizedText })
        assertEquals(true, frozen.frozenPayload.contains("\\\"A\\\"\\\\B\\nTotal 42"))
        assertEquals("Документ", frozen.pages.last().recognizedGeometry?.words?.single()?.text)
        assertEquals(true, frozen.frozenPayload.contains("\"recognizedGeometry\":{\"formatVersion\":1"))
        assertEquals(true, frozen.frozenPayload.contains("\"text\":\"Документ\""))
        assertEquals(frozen.frozenPayload, database.schemaQueries.saveManifestById(manifestId.value)
            .executeAsOne().frozen_payload)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(firstArtifact.artifactId.value).executeAsOne())
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(secondArtifact.artifactId.value).executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(excludedArtifact.artifactId.value).executeAsOne())

        store.setSelected(workspaceId, first.pageId, false)
        store.commitRecognition(
            RecognitionCandidate(
                RecognitionId.new(), workspaceId, first.pageId, first.imageVersionId,
                1L, "OCR_LATIN_V1", "new recognition",
                RecognitionGeometry(
                    1000L, 1400L, OCR_WORD_COORDINATE_SPACE,
                    listOf(RecognizedWord("new", 30L, 45L, 180L, 90L, 80.0)),
                ),
            ),
        )
        assertEquals(frozen.frozenPayload, database.schemaQueries.saveManifestById(manifestId.value)
            .executeAsOne().frozen_payload)
        assertEquals(firstArtifact.sha256, frozen.pages.last().artifactSha256)
    }

    @Test
    fun emptySaveAndInvalidSelectedArtifactFailWithoutManifestOrProtectionWrites() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val page = captureCommand(artifact)
        store.commitCapture(page)
        val manifestId = SaveManifestId.new()

        assertThrows(StoreInvariantViolation::class.java) {
            store.freezeSelectedPagesForSave(workspaceId, manifestId)
        }
        assertEquals(null, database.schemaQueries.saveManifestById(manifestId.value).executeAsOneOrNull())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())

        store.setSelected(workspaceId, page.pageId, true)
        database.schemaQueries.quarantineArtifact(artifact.artifactId.value)
        val rejectedId = SaveManifestId.new()
        assertThrows(StoreInvariantViolation::class.java) {
            store.freezeSelectedPagesForSave(workspaceId, rejectedId)
        }
        assertEquals(null, database.schemaQueries.saveManifestById(rejectedId.value).executeAsOneOrNull())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
    }

    @Test
    fun cancellingUnstartedSaveReleasesOnlyThatManifestArtifactProtection() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val page = captureCommand(artifact).copy(selected = true)
        store.commitCapture(page)
        val manifestId = SaveManifestId.new()
        store.freezeSelectedPagesForSave(workspaceId, manifestId)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())

        store.cancelUnstartedSaveManifest(workspaceId, manifestId)

        assertEquals(null, database.schemaQueries.saveManifestById(manifestId.value).executeAsOneOrNull())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
    }

    @Test
    fun localExportCommitAtomicallyCommitsCompleteOrderedFilesAndReplacesSaveProtection() {
        val firstArtifact = durableArtifact()
        store.recordStagedDurableArtifact(firstArtifact)
        val firstPage = captureCommand(firstArtifact).copy(selected = true)
        store.commitCapture(firstPage)
        val secondArtifact = durableArtifact()
        store.recordStagedDurableArtifact(secondArtifact)
        val secondPage = captureCommand(secondArtifact).copy(selected = true)
        store.commitCapture(secondPage)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)

        val pdf = durableExportFile("pdf")
        val firstJpeg = durableExportFile("jpg")
        val secondJpeg = durableExportFile("jpg")
        val files = listOf(
            LocalExportFileCommand(pdf, "PDF", null),
            LocalExportFileCommand(firstJpeg, "JPG_PAGE", manifest.pages[0].imageVersionId),
            LocalExportFileCommand(secondJpeg, "JPG_PAGE", manifest.pages[1].imageVersionId),
        )
        files.forEach { store.recordStagedExportFile(manifest.saveManifestId, it.file) }

        val exportId = ExportArtifactId.new()
        store.commitLocalExportArtifact(CommitLocalExportCommand(exportId, manifest.saveManifestId, files))

        val persisted = database.schemaQueries.exportArtifactsBySaveManifest(manifest.saveManifestId.value).executeAsList()
        assertEquals(1, persisted.size)
        assertEquals(exportId.value, persisted.single().export_artifact_id)
        assertTrue(persisted.single().artifact_set_payload.indexOf(firstJpeg.artifactId.value)
            < persisted.single().artifact_set_payload.indexOf(secondJpeg.artifactId.value))
        listOf(pdf, firstJpeg, secondJpeg).forEach { file ->
            assertEquals("COMMITTED", database.schemaQueries.artifactById(file.artifactId.value).executeAsOne().state)
            assertEquals(1L, database.schemaQueries.referenceCountForArtifact(file.artifactId.value).executeAsOne())
        }
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(firstArtifact.artifactId.value).executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(secondArtifact.artifactId.value).executeAsOne())
        assertTrue(store.incompleteLocalOutputCompositionManifests().isEmpty())
        assertTrue(store.hasCommittedNormalLocalSave(manifest.saveManifestId))
        assertThrows(StoreConflict::class.java) {
            store.abandonInterruptedLocalOutputComposition(manifest.saveManifestId)
        }
        assertEquals(1, database.schemaQueries.exportArtifactsBySaveManifest(manifest.saveManifestId.value)
            .executeAsList().size)
    }

    @Test
    fun verifiedPublicExportCreatesRecentConsumesOnlyUnchangedPagesAndIsIdempotent() {
        val pages = (0 until 2).map {
            val source = durableArtifact()
            store.recordStagedDurableArtifact(source)
            captureCommand(source).copy(selected = true).also(store::commitCapture)
        }
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val pdf = durableExportFile("pdf")
        val jpgs = pages.map { durableExportFile("jpg") }
        val files = listOf(LocalExportFileCommand(pdf, "PDF", null)) + pages.mapIndexed { index, page ->
            LocalExportFileCommand(jpgs[index], "JPG_PAGE", page.imageVersionId)
        }
        files.forEach { store.recordStagedExportFile(manifest.saveManifestId, it.file) }
        val exportId = ExportArtifactId.new()
        store.commitLocalExportArtifact(CommitLocalExportCommand(exportId, manifest.saveManifestId, files))

        driver.execute(
            null,
            "UPDATE Page SET content_revision = content_revision + 1 WHERE page_id = '${pages[1].pageId.value}'",
            0,
            null,
        )
        val uri = "content://media/external/document/scan-1"
        store.recordVerifiedPublicExport(exportId, uri, "Scan.pdf", 2L, "PARTIAL")

        assertEquals(1L, database.schemaQueries.pageById(pages[0].pageId.value).executeAsOne().tombstone)
        assertEquals(0L, database.schemaQueries.pageById(pages[1].pageId.value).executeAsOne().tombstone)
        assertEquals(1L, store.visiblePageCount(workspaceId))
        val recent = store.recentDocuments(1_000L).single()
        assertEquals(uri, recent.documentUri)
        assertEquals("Scan.pdf", recent.displayName)
        assertEquals(2L, recent.pageCount)
        assertEquals("PARTIAL", recent.ocrStatus)
        assertEquals(2L, database.schemaQueries.publicExportPublicationById(exportId.value)
            .executeAsOne().page_count)
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none {
            it.refKind == "RECENT_DOCUMENT"
        })

        store.recordVerifiedPublicExport(exportId, uri, "Scan.pdf", 2L, "PARTIAL")
        assertEquals(recent, store.recentDocuments(1_000L).single())
        assertEquals(1L, store.visiblePageCount(workspaceId))
        assertThrows(StoreInvariantViolation::class.java) {
            store.recordVerifiedPublicExport(exportId, uri, "Other.pdf", 2L, "PARTIAL")
        }

        driver.execute(
            null,
            "UPDATE RecentDocument SET page_count = 1 WHERE export_artifact_id = '${exportId.value}'",
            0,
            null,
        )
        assertEquals(
            listOf("RECENT_DOCUMENT_PAGE_COUNT_MISMATCH"),
            store.artifactReferenceOwnerIntegrityFindings(100L)
                .filter { it.refKind == "RECENT_DOCUMENT" }.map { it.code },
        )
        driver.execute(
            null,
            "UPDATE RecentDocument SET page_count = 2 WHERE export_artifact_id = '${exportId.value}'",
            0,
            null,
        )

        assertTrue(store.recentDocuments(Long.MAX_VALUE).isEmpty())
        assertEquals(exportId.value, database.schemaQueries.publicExportPublicationById(exportId.value)
            .executeAsOne().export_artifact_id)
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none {
            it.refKind == "RECENT_DOCUMENT"
        })
    }

    @Test
    fun publicExportCannotBeRecordedBeforeLocalExportCommit() {
        val exportId = ExportArtifactId.new()

        assertThrows(StoreInvariantViolation::class.java) {
            store.recordVerifiedPublicExport(
                exportId, "content://media/external/document/scan", "Scan.pdf", 1L, "NONE",
            )
        }

        assertEquals(null, database.schemaQueries.publicExportPublicationById(exportId.value).executeAsOneOrNull())
        assertTrue(store.recentDocuments(1_000L).isEmpty())
    }

    @Test
    fun invalidFrozenOutputOrderRollsBackExportRowsAndKeepsSaveProtection() {
        val sourceArtifact = durableArtifact()
        store.recordStagedDurableArtifact(sourceArtifact)
        val page = captureCommand(sourceArtifact).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val pdf = durableExportFile("pdf")
        val jpeg = durableExportFile("jpg")
        val files = listOf(
            LocalExportFileCommand(pdf, "PDF", null),
            LocalExportFileCommand(jpeg, "JPG_PAGE", ImageVersionId.new()),
        )
        files.forEach { store.recordStagedExportFile(manifest.saveManifestId, it.file) }

        assertThrows(StoreInvariantViolation::class.java) {
            store.commitLocalExportArtifact(CommitLocalExportCommand(ExportArtifactId.new(), manifest.saveManifestId, files))
        }

        assertEquals(0, database.schemaQueries.exportArtifactsBySaveManifest(manifest.saveManifestId.value)
            .executeAsList().size)
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(pdf.artifactId.value).executeAsOne().state)
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(jpeg.artifactId.value).executeAsOne().state)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(sourceArtifact.artifactId.value).executeAsOne())
    }

    @Test
    fun saveManifestCannotBeCancelledAfterAnyOutputBytesAreDurablyPublished() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        val output = durableExportFile("pdf")
        store.recordStagedExportFile(manifest.saveManifestId, output)

        assertThrows(StoreConflict::class.java) {
            store.cancelUnstartedSaveManifest(workspaceId, manifest.saveManifestId)
        }

        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(output.artifactId.value).executeAsOne().state)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(output.artifactId.value).executeAsOne())
    }

    @Test
    fun outputCompositionBarrierPreventsCancellationBeforeFirstFileExists() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)

        assertThrows(StoreConflict::class.java) {
            store.cancelUnstartedSaveManifest(workspaceId, manifest.saveManifestId)
        }

        assertEquals(0, database.schemaQueries.exportArtifactsBySaveManifest(manifest.saveManifestId.value)
            .executeAsList().size)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
    }

    @Test
    fun interruptedLocalCompositionRecoveryAbandonsOnlyItsManifestAndKeepsSourcePage() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        assertEquals(listOf(manifest.saveManifestId), store.incompleteLocalOutputCompositionManifests())

        store.abandonInterruptedLocalOutputComposition(manifest.saveManifestId)

        assertTrue(store.incompleteLocalOutputCompositionManifests().isEmpty())
        assertEquals(null, database.schemaQueries.saveManifestById(manifest.saveManifestId.value).executeAsOneOrNull())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        assertEquals(1L, store.visiblePageCount(workspaceId))
    }

    @Test
    fun interruptedCompositionRecoveryIncludesOnlySafelyOwnedStagedOutputs() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val output = durableExportFile("pdf")
        store.recordStagedExportFile(manifest.saveManifestId, output)

        assertEquals(listOf(manifest.saveManifestId), store.incompleteLocalOutputCompositionManifests())
        assertEquals(listOf(output.artifactId),
            store.stagedExportOutputsForInterruptedSave(manifest.saveManifestId).map { it.artifactId })
        assertThrows(StoreConflict::class.java) {
            store.abandonInterruptedLocalOutputComposition(manifest.saveManifestId)
        }
        assertEquals(1L, database.schemaQueries.saveOutputCompositionStarted(manifest.saveManifestId.value).executeAsOne())
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(output.artifactId.value).executeAsOne().state)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(output.artifactId.value).executeAsOne())
    }

    @Test
    fun interruptedCompositionRecoveryDeletesStagedOutputMetadataAndPreservesSource() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val output = durableExportFile("pdf")
        store.recordStagedExportFile(manifest.saveManifestId, output)
        val staged = store.stagedExportOutputsForInterruptedSave(manifest.saveManifestId)

        store.abandonInterruptedLocalOutputComposition(manifest.saveManifestId, staged.map { it.artifactId })

        val recoveredOutput = database.schemaQueries.artifactById(output.artifactId.value).executeAsOne()
        assertEquals("QUARANTINED", recoveredOutput.state)
        assertEquals("DELETED", recoveredOutput.storage_state)
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(output.artifactId.value).executeAsOne())
        assertEquals(null, database.schemaQueries.saveManifestById(manifest.saveManifestId.value).executeAsOneOrNull())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        assertEquals(1L, store.visiblePageCount(workspaceId))
    }

    @Test
    fun interruptedCompositionRecoveryRefusesForeignOutputReferenceBeforeCleanup() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val output = durableExportFile("pdf")
        store.recordStagedExportFile(manifest.saveManifestId, output)
        database.schemaQueries.addArtifactReference(
            output.artifactId.value, "TEST", "foreign-owner", "RECOVERY",
        )

        assertThrows(StoreConflict::class.java) {
            store.stagedExportOutputsForInterruptedSave(manifest.saveManifestId)
        }
        assertEquals(1L, database.schemaQueries.saveOutputCompositionStarted(manifest.saveManifestId.value).executeAsOne())
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(output.artifactId.value).executeAsOne().state)
    }

    @Test
    fun normalSaveOutputPublicationIsJournaledBeforeStagingAndCompletesAtomically() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val artifactId = ArtifactId.forSaveOutput(manifest.saveManifestId, 0)
        val descriptor = DurableExportFile(
            artifactId,
            "objects/${artifactId.value.take(2)}/${artifactId.value}.pdf",
            10L,
            "a".repeat(64),
            1L,
            "test-epoch",
        )

        store.beginSaveOutputPublication(manifest.saveManifestId, 0, descriptor)

        assertEquals(1L, database.schemaQueries.saveOutputPublicationIntentCount(manifest.saveManifestId.value)
            .executeAsOne())
        assertEquals(null, database.schemaQueries.artifactById(artifactId.value).executeAsOneOrNull())
        assertEquals(emptyList<ArtifactId>(),
            store.stagedExportOutputsForInterruptedSave(manifest.saveManifestId).map { it.artifactId })
        assertEquals(listOf(artifactId),
            store.outputPublicationIntentsForInterruptedSave(manifest.saveManifestId).map { it.artifactId })

        store.completeSaveOutputPublication(manifest.saveManifestId, descriptor)

        assertEquals(0L, database.schemaQueries.saveOutputPublicationIntentCount(manifest.saveManifestId.value)
            .executeAsOne())
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(artifactId.value).executeAsOne().state)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifactId.value).executeAsOne())
    }

    @Test
    fun normalSaveOutputPublicationRejectsNondeterministicIdentityBeforeIntent() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val descriptor = durableExportFile("pdf")

        assertThrows(StoreInvariantViolation::class.java) {
            store.beginSaveOutputPublication(manifest.saveManifestId, 0, descriptor)
        }

        assertEquals(0L, database.schemaQueries.saveOutputPublicationIntentCount(manifest.saveManifestId.value)
            .executeAsOne())
        assertEquals(null, database.schemaQueries.artifactById(descriptor.artifactId.value).executeAsOneOrNull())
    }

    @Test
    fun interruptedPublicationIntentRecoveryDeletesIntentAndPreservesPageSource() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val artifactId = ArtifactId.forSaveOutput(manifest.saveManifestId, 0)
        val intent = DurableExportFile(
            artifactId,
            "objects/${artifactId.value.take(2)}/${artifactId.value}.pdf",
            10L,
            "b".repeat(64),
            1L,
            "test-epoch",
        )
        store.beginSaveOutputPublication(manifest.saveManifestId, 0, intent)

        val recoverable = store.outputPublicationIntentsForInterruptedSave(manifest.saveManifestId)
        store.abandonInterruptedLocalOutputComposition(
            manifest.saveManifestId,
            cleanedPublicationIntentArtifactIds = recoverable.map { it.artifactId },
        )

        assertEquals(null, database.schemaQueries.saveManifestById(manifest.saveManifestId.value).executeAsOneOrNull())
        assertEquals(0L, database.schemaQueries.saveOutputPublicationIntentCount(manifest.saveManifestId.value)
            .executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        assertEquals(1L, store.visiblePageCount(workspaceId))
    }

    @Test
    fun interruptedCompositionRecoveryPreservesManifestWhenSourceProtectionDoesNotMatch() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        database.schemaQueries.deleteSaveManifestArtifactReferences(manifest.saveManifestId.value)

        assertThrows(StoreInvariantViolation::class.java) {
            store.abandonInterruptedLocalOutputComposition(manifest.saveManifestId)
        }

        assertEquals(1L, database.schemaQueries.saveOutputCompositionStarted(manifest.saveManifestId.value).executeAsOne())
        assertTrue(database.schemaQueries.saveManifestById(manifest.saveManifestId.value).executeAsOneOrNull() != null)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
    }

    @Test
    fun abortingFailedLocalCompositionKeepsManifestSourcesAndAllowsCancellation() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)

        store.abortLocalOutputComposition(manifest)

        assertEquals(0L, database.schemaQueries.saveOutputCompositionStarted(manifest.saveManifestId.value).executeAsOne())
        assertEquals(manifest.frozenPayload,
            database.schemaQueries.saveManifestById(manifest.saveManifestId.value).executeAsOne().frozen_payload)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        store.cancelUnstartedSaveManifest(workspaceId, manifest.saveManifestId)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
    }

    @Test
    fun abortingLocalCompositionRefusesOnceOutputBytesAreStaged() {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val output = durableExportFile("pdf")
        store.recordStagedExportFile(manifest.saveManifestId, output)

        assertThrows(StoreConflict::class.java) { store.abortLocalOutputComposition(manifest) }

        assertEquals(1L, database.schemaQueries.saveOutputCompositionStarted(manifest.saveManifestId.value).executeAsOne())
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(output.artifactId.value).executeAsOne().state)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(output.artifactId.value).executeAsOne())
    }

    @Test
    fun destinationGrantMetadataIsPersistedRevalidatedAndClearedConditionally() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        store.recordDestinationGrant(uri, 100L)
        assertEquals(DestinationGrantSnapshot(uri, 100L, 100L), store.destinationGrant())

        store.markDestinationGrantVerified(uri, 250L)
        assertEquals(DestinationGrantSnapshot(uri, 100L, 250L), store.destinationGrant())
        store.markDestinationGrantVerified(uri, 50L)
        assertEquals(DestinationGrantSnapshot(uri, 100L, 250L), store.destinationGrant())
        assertThrows(StoreConflict::class.java) { store.markDestinationGrantVerified("content://other/tree/x", 300L) }
        assertThrows(IllegalArgumentException::class.java) { store.recordDestinationGrant("file:///private", 400L) }
        assertEquals(DestinationGrantSnapshot(uri, 100L, 250L), store.destinationGrant())

        store.clearDestinationGrant(uri)
        assertEquals(null, store.destinationGrant())
        assertThrows(StoreConflict::class.java) { store.clearDestinationGrant(uri) }
    }

    @Test
    fun exportAttemptCommitsWritingBarrierThenSuccessReleasesRecoveryReferences() {
        val fixture = committedExportFixture()
        val provider = qualifiedProvider()
        assertEquals(provider.qualificationId, store.providerQualificationFor(
            provider.adapterId, provider.providerVersion, provider.osVersion, provider.destinationAccessClass,
        )?.qualificationId)
        assertEquals(null, store.providerQualificationFor(
            provider.adapterId, provider.providerVersion, "different-build", provider.destinationAccessClass,
        ))
        val committedOutputs = store.committedExportOutputs(fixture.exportArtifactId)
        assertEquals(2, committedOutputs.size)
        assertEquals(fixture.output.artifactId, committedOutputs.first().artifactId)
        assertEquals("application/pdf", committedOutputs.first().mimeType)
        val command = beginAttempt(fixture, provider)
        val writing = store.beginExportAttempt(command)

        assertEquals("WRITING_EXTERNAL", writing.state)
        assertEquals(true, writing.frozenProviderParameters.contains(fixture.output.sha256))
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(fixture.output.artifactId.value).executeAsOne())
        assertThrows(StoreConflict::class.java) { store.beginExportAttempt(command.copy(exportAttemptId = ExportAttemptId.new())) }

        store.finishWritingExportAttempt(
            exportAttemptId = command.exportAttemptId,
            outcome = ExportAttemptOutcome.SUCCEEDED,
            externalIdentity = "provider-document-id-1",
            knownError = null,
            provesNoSideEffect = false,
            observation = observation("success-1", "created and readable"),
        )

        val completed = store.exportAttempt(command.exportAttemptId)
        assertEquals("SUCCEEDED", completed?.state)
        assertEquals("provider-document-id-1", completed?.externalIdentity)
        assertEquals(listOf("SUCCEEDED"), store.exportAttempts(fixture.exportArtifactId).map { it.state })
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(fixture.output.artifactId.value).executeAsOne())
    }

    @Test
    fun startupTurnsWritingIntoUnknownWithoutRetryAndRevalidationCanCompleteIt() {
        val fixture = committedExportFixture()
        val provider = qualifiedProvider()
        val command = beginAttempt(fixture, provider)
        store.beginExportAttempt(command)

        assertEquals(listOf(command.exportAttemptId), store.markWritingExportAttemptsUnknownAtStartup())
        assertEquals("UNKNOWN_OUTCOME", store.exportAttempt(command.exportAttemptId)?.state)
        assertEquals(listOf(command.exportAttemptId), store.unknownExportAttempts().map { it.exportAttemptId })
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(fixture.output.artifactId.value).executeAsOne())
        assertThrows(StoreConflict::class.java) {
            store.beginExportAttempt(command.copy(exportAttemptId = ExportAttemptId.new()))
        }

        store.revalidateUnknownExportAttempt(
            exportAttemptId = command.exportAttemptId,
            outcome = ExportAttemptOutcome.SUCCEEDED,
            externalIdentity = "observed-document-id-1",
            knownError = null,
            provesNoSideEffect = false,
            observation = observation("revalidate-1", "matching bytes and provider identity"),
        )

        assertEquals("SUCCEEDED", store.exportAttempt(command.exportAttemptId)?.state)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(fixture.output.artifactId.value).executeAsOne())
    }

    @Test
    fun liveAmbiguousProviderResultBecomesUnknownAndKeepsRecoveryReference() {
        val fixture = committedExportFixture()
        val command = beginAttempt(fixture, qualifiedProvider())
        store.beginExportAttempt(command)

        store.markWritingExportAttemptUnknown(
            command.exportAttemptId,
            "Provider threw after create was invoked",
            observation("ambiguous-live-1", "side effect cannot be ruled out"),
        )

        assertEquals("UNKNOWN_OUTCOME", store.exportAttempt(command.exportAttemptId)?.state)
        assertEquals(listOf(command.exportAttemptId), store.unknownExportAttempts().map { it.exportAttemptId })
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(fixture.output.artifactId.value).executeAsOne())
    }

    @Test
    fun externalPackagePersistsOutputOrderAndResumesOnlyAfterUnknownIsConfirmed() {
        val fixture = committedExportPackageFixture()
        val exportPackage = store.externalExportPackage(fixture.export.exportArtifactId)!!
        assertEquals("READY", exportPackage.state)
        assertEquals(listOf("Scan.pdf", "Scan-001.jpg"), exportPackage.outputs.map { it.displayName })

        val first = beginAttempt(fixture.export, fixture.provider)
        assertThrows(StoreInvariantViolation::class.java) {
            store.beginExportAttempt(first.copy(plannedDisplayName = "Another.pdf"))
        }
        store.beginExportAttempt(first)
        assertEquals("WRITING_EXTERNAL", store.externalExportPackage(fixture.export.exportArtifactId)?.state)
        store.markWritingExportAttemptUnknown(
            first.exportAttemptId,
            "Provider acknowledgement was lost",
            observation("package-unknown-1", "create may have occurred"),
        )
        assertEquals("UNKNOWN_OUTCOME", store.externalExportPackage(fixture.export.exportArtifactId)?.state)
        val jpeg = store.committedExportOutputs(fixture.export.exportArtifactId).last()
        val next = first.copy(
            exportAttemptId = ExportAttemptId.new(),
            outputArtifactId = jpeg.artifactId,
            plannedDisplayName = "Scan-001.jpg",
            mimeType = "image/jpeg",
        )
        assertThrows(StoreConflict::class.java) { store.beginExportAttempt(next) }

        store.revalidateUnknownExportAttempt(
            first.exportAttemptId,
            ExportAttemptOutcome.SUCCEEDED,
            "content://test.provider/document/pdf",
            null,
            false,
            observation("package-revalidated-1", "single named object hash matched"),
        )
        assertEquals("PARTIAL", store.externalExportPackage(fixture.export.exportArtifactId)?.state)
        assertTrue(store.recentDocuments(1_000L).isEmpty())
        assertThrows(StoreConflict::class.java) {
            store.beginExportAttempt(first.copy(exportAttemptId = ExportAttemptId.new()))
        }

        store.beginExportAttempt(next)
        store.finishWritingExportAttempt(
            next.exportAttemptId,
            ExportAttemptOutcome.SUCCEEDED,
            "content://test.provider/document/jpg",
            null,
            false,
            observation("package-success-2", "full jpeg read-back matched"),
        )
        assertEquals("SUCCEEDED", store.externalExportPackage(fixture.export.exportArtifactId)?.state)
        val recent = store.recentDocuments(1_000L)
        assertEquals(1, recent.size)
        assertEquals("content://test.provider/document/pdf", recent.single().documentUri)
        assertEquals("Scan.pdf", recent.single().displayName)
        assertEquals(1L, recent.single().pageCount)
        assertEquals("FULL", recent.single().ocrStatus)
        assertTrue(store.recentDocuments(1_001L + 24L * 60L * 60L * 1_000L).isEmpty())
    }

    @Test
    fun editedSavedPdfCommitsReorderedTwoPagePdfAndIndexesItsPageCount() {
        val original = committedExportPackageFixture(pageCount = 2)
        val originalOutputs = store.committedExportOutputs(original.export.exportArtifactId)
        val originalPdf = originalOutputs.single { it.mimeType == "application/pdf" }
        val originalPackage = requireNotNull(store.externalExportPackage(original.export.exportArtifactId))
        val outputsById = originalOutputs.associateBy { it.artifactId }
        originalPackage.outputs.forEach { packageOutput ->
            val output = requireNotNull(outputsById[packageOutput.outputArtifactId])
            val attempt = beginAttempt(original.export, original.provider).copy(
                exportAttemptId = ExportAttemptId.new(),
                outputArtifactId = output.artifactId,
                plannedDisplayName = packageOutput.displayName,
                mimeType = output.mimeType,
            )
            store.beginExportAttempt(attempt)
            store.finishWritingExportAttempt(
                attempt.exportAttemptId,
                ExportAttemptOutcome.SUCCEEDED,
                "content://test.provider/document/source-${output.artifactId.value}",
                null,
                false,
                observation("source-${output.artifactId.value}", "source output fully read back"),
            )
        }
        val sourceRecent = store.recentDocuments(1_000L).single()
        assertEquals(2L, sourceRecent.pageCount)

        val manifestId = SaveManifestId.new()
        store.beginSavedPdfEditManifest(
            SavedPdfEditManifestCommand(
                manifestId,
                workspaceId,
                original.export.exportArtifactId,
                originalPdf.artifactId,
                originalPdf.sha256,
                sourceRecent.pageCount,
                listOf(1, 0),
            ),
        )
        assertEquals(
            2L,
            database.schemaQueries.referenceCountForArtifact(originalPdf.artifactId.value).executeAsOne(),
        )
        val editedPdf = durableExportFile("pdf")
        store.recordStagedExportFile(manifestId, editedPdf)
        val editedExportId = ExportArtifactId.new()
        store.commitLocalExportArtifact(
            CommitLocalExportCommand(
                exportArtifactId = editedExportId,
                saveManifestId = manifestId,
                files = listOf(LocalExportFileCommand(editedPdf, "PDF", null)),
                externalPackage = ExternalExportPackageCommand(
                    destinationTreeUri = "content://test.provider/tree/root",
                    commonBasename = "Scan-edited",
                    providerQualificationId = original.provider.qualificationId,
                    adapterId = original.provider.adapterId,
                    providerVersion = original.provider.providerVersion,
                    osVersion = original.provider.osVersion,
                    destinationAccessClass = original.provider.destinationAccessClass,
                    outputs = listOf(ExternalExportPackageOutputCommand(editedPdf.artifactId, "Scan-edited.pdf", 0L)),
                    ocrStatus = sourceRecent.ocrStatus,
                ),
                outputKind = "EDITED_SAVED_PDF",
                expectedPageCount = 2L,
            ),
        )
        assertEquals(
            1L,
            database.schemaQueries.referenceCountForArtifact(originalPdf.artifactId.value).executeAsOne(),
        )

        assertEquals("EDITED_SAVED_PDF", database.schemaQueries.exportArtifactById(editedExportId.value)
            .executeAsOne().output_kind)
        assertEquals(1, store.committedExportOutputs(editedExportId).size)
        val editedOutput = store.committedExportOutputs(editedExportId).single()
        val attempt = BeginExportAttemptCommand(
            exportAttemptId = ExportAttemptId.new(),
            exportArtifactId = editedExportId,
            outputArtifactId = editedOutput.artifactId,
            destinationTreeUri = "content://test.provider/tree/root",
            plannedDisplayName = "Scan-edited.pdf",
            mimeType = "application/pdf",
            providerQualificationId = original.provider.qualificationId,
            adapterId = original.provider.adapterId,
            providerVersion = original.provider.providerVersion,
            osVersion = original.provider.osVersion,
            destinationAccessClass = original.provider.destinationAccessClass,
        )
        store.beginExportAttempt(attempt)
        store.finishWritingExportAttempt(
            attempt.exportAttemptId,
            ExportAttemptOutcome.SUCCEEDED,
            "content://test.provider/document/edited-pdf",
            null,
            false,
            observation("edited-pdf-success", "edited PDF fully read back"),
        )

        val recent = store.recentDocuments(1_000L).single { it.exportArtifactId == editedExportId }
        assertEquals(2L, recent.pageCount)
        assertEquals("Scan-edited.pdf", recent.displayName)
        assertEquals(sourceRecent.ocrStatus, recent.ocrStatus)
        assertEquals("[1,0]", database.schemaQueries.savedPdfEditCompositionByManifest(manifestId.value)
            .executeAsOne().output_page_order)
        assertTrue(store.artifactReferenceOwnerIntegrityFindings(100L).none {
            it.refKind == "RECENT_DOCUMENT"
        })
    }

    @Test
    fun savedPdfEditCancelAndAbandonReleaseOnlyTemporarySourceProtection() {
        val source = committedExportPackageFixture()
        val sourcePdf = source.export.output
        database.schemaQueries.insertRecentDocument(
            "saved-pdf-cleanup-recent",
            source.export.exportArtifactId.value,
            "content://test.provider/source.pdf",
            "Source.pdf",
            1L,
            1_000L,
            "FULL",
        )
        val initialReferences = database.schemaQueries.referenceCountForArtifact(sourcePdf.artifactId.value)
            .executeAsOne()
        assertEquals(1L, initialReferences)

        fun beginEdit(): SaveManifestId = SaveManifestId.new().also { id ->
            store.beginSavedPdfEditManifest(
                SavedPdfEditManifestCommand(
                    saveManifestId = id,
                    workspaceId = workspaceId,
                    sourceExportArtifactId = source.export.exportArtifactId,
                    sourcePdfArtifactId = sourcePdf.artifactId,
                    sourcePdfSha256 = sourcePdf.sha256,
                    sourcePageCount = 1L,
                    orderedSourcePageIndexes = listOf(0),
                ),
            )
        }

        val cancelledManifest = beginEdit()
        assertEquals(
            initialReferences + 1L,
            database.schemaQueries.referenceCountForArtifact(sourcePdf.artifactId.value).executeAsOne(),
        )
        store.cancelUnstartedSaveManifest(workspaceId, cancelledManifest)
        assertEquals(initialReferences, database.schemaQueries.referenceCountForArtifact(sourcePdf.artifactId.value)
            .executeAsOne())
        assertEquals(null, database.schemaQueries.saveManifestById(cancelledManifest.value).executeAsOneOrNull())
        assertEquals(null, database.schemaQueries.savedPdfEditCompositionByManifest(cancelledManifest.value)
            .executeAsOneOrNull())

        val abandonedManifest = beginEdit()
        assertEquals(
            initialReferences + 1L,
            database.schemaQueries.referenceCountForArtifact(sourcePdf.artifactId.value).executeAsOne(),
        )
        assertEquals(1, store.abandonIncompleteSavedPdfEditManifests())
        assertEquals(initialReferences, database.schemaQueries.referenceCountForArtifact(sourcePdf.artifactId.value)
            .executeAsOne())
        assertEquals(null, database.schemaQueries.saveManifestById(abandonedManifest.value).executeAsOneOrNull())
        assertEquals(null, database.schemaQueries.savedPdfEditCompositionByManifest(abandonedManifest.value)
            .executeAsOneOrNull())
        val retainedSource = database.schemaQueries.artifactById(sourcePdf.artifactId.value).executeAsOne()
        assertEquals("COMMITTED", retainedSource.state)
        assertEquals("PRESENT", retainedSource.storage_state)
    }

    @Test
    fun inconclusiveRevalidationKeepsUnknownStateAndRecoveryProtection() {
        val fixture = committedExportFixture()
        val provider = qualifiedProvider()
        val command = beginAttempt(fixture, provider)
        store.beginExportAttempt(command)
        store.markWritingExportAttemptsUnknownAtStartup()

        store.revalidateUnknownExportAttempt(
            exportAttemptId = command.exportAttemptId,
            outcome = ExportAttemptOutcome.UNKNOWN_OUTCOME,
            externalIdentity = null,
            knownError = "Provider identity could not be revalidated",
            provesNoSideEffect = false,
            observation = observation("revalidate-indeterminate", "multiple or inaccessible matching entries"),
        )

        assertEquals("UNKNOWN_OUTCOME", store.exportAttempt(command.exportAttemptId)?.state)
        assertEquals(2L, database.schemaQueries.referenceCountForArtifact(fixture.output.artifactId.value).executeAsOne())
        assertThrows(StoreConflict::class.java) {
            store.beginExportAttempt(command.copy(exportAttemptId = ExportAttemptId.new()))
        }
    }

    @Test
    fun knownFailureRequiresProofOfNoSideEffectAndWrongProviderTupleCannotBegin() {
        val fixture = committedExportFixture()
        val provider = qualifiedProvider()
        val command = beginAttempt(fixture, provider)
        assertThrows(StoreInvariantViolation::class.java) {
            store.beginExportAttempt(command.copy(providerVersion = "unqualified-provider-version"))
        }
        store.beginExportAttempt(command)
        assertThrows(IllegalArgumentException::class.java) {
            store.finishWritingExportAttempt(
                command.exportAttemptId, ExportAttemptOutcome.FAILED_KNOWN, null,
                "provider rejected create", provesNoSideEffect = false,
                observation("failure-unproven", "provider call returned an ambiguous error"),
            )
        }
        store.finishWritingExportAttempt(
            command.exportAttemptId, ExportAttemptOutcome.FAILED_KNOWN, null,
            "permission denied before document creation", provesNoSideEffect = true,
            observation("failure-proven", "no document created"),
        )
        assertEquals("FAILED_KNOWN", store.exportAttempt(command.exportAttemptId)?.state)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(fixture.output.artifactId.value).executeAsOne())
    }

    @Test
    fun missingDurableArtifactCreatesNoPage() {
        assertThrows(StoreInvariantViolation::class.java) {
            store.commitCapture(captureCommand(durableArtifact()))
        }

        assertEquals(0L, store.visiblePageCount(workspaceId))
    }

    @Test
    fun artifactMismatchRollsBackImageVersionPageAndActivity() {
        val recordedArtifact = durableArtifact()
        store.recordStagedDurableArtifact(recordedArtifact)
        val differentDescriptor = recordedArtifact.copy(relativePath = "objects/other.jpg")

        assertThrows(StoreInvariantViolation::class.java) {
            store.commitCapture(captureCommand(differentDescriptor))
        }

        assertEquals(0L, store.visiblePageCount(workspaceId))
        assertEquals(
            "STAGED_DURABLE",
            database.schemaQueries.artifactById(recordedArtifact.artifactId.value).executeAsOne().state,
        )
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(recordedArtifact.artifactId.value).executeAsOne())
    }

    @Test
    fun retakePreservesPageIdentityOrderSelectionAndUndoRestoresPreviousVersion() {
        val originalArtifact = durableArtifact()
        store.recordStagedDurableArtifact(originalArtifact)
        val original = captureCommand(originalArtifact).copy(selected = true)
        store.commitCapture(original)
        store.commitRecognition(
            RecognitionCandidate(
                RecognitionId.new(), workspaceId, original.pageId, original.imageVersionId,
                1L, "OCR_LATIN_V1", "Recognized original",
            ),
        )
        val replacementArtifact = durableArtifact()
        store.recordStagedDurableArtifact(replacementArtifact)
        val replacementVersion = ImageVersionId.new()

        val nextRevision = store.replacePageImage(
            ReplacePageImageCommand(
                workspaceId = workspaceId,
                pageId = original.pageId,
                expectedImageVersionId = original.imageVersionId,
                expectedContentRevision = 1L,
                newImageVersionId = replacementVersion,
                artifact = replacementArtifact,
                coordinateSpace = "source-pixel-v1",
                transformProvenance = "retake",
                processingDesiredState = "NONE",
            ),
        )
        val retaken = database.schemaQueries.pageById(original.pageId.value).executeAsOne()

        assertEquals(2L, nextRevision)
        assertEquals(0L, retaken.order_index)
        assertEquals(1L, retaken.selected)
        assertEquals(replacementVersion.value, retaken.current_image_version_id)
        assertEquals(original.imageVersionId.value, retaken.undo_image_version_id)
        assertEquals("UNDO", database.schemaQueries.artifactReferenceProtection(original.imageVersionId.value).executeAsOne())

        assertEquals(3L, store.undoPageImage(workspaceId, original.pageId, replacementVersion, 2L))
        val restored = database.schemaQueries.pageById(original.pageId.value).executeAsOne()
        assertEquals(original.imageVersionId.value, restored.current_image_version_id)
        assertEquals(null, restored.undo_image_version_id)
        assertEquals("Recognized original", store.workspaceProjection(workspaceId).pages.single().recognizedText)
        assertEquals("AUTHORITATIVE", database.schemaQueries.artifactReferenceProtection(original.imageVersionId.value).executeAsOne())
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(replacementArtifact.artifactId.value).executeAsOne())
    }

    @Test
    fun staleRetakeAndInvalidReorderFailWithoutChangingWorkspace() {
        val firstArtifact = durableArtifact()
        store.recordStagedDurableArtifact(firstArtifact)
        val first = captureCommand(firstArtifact)
        store.commitCapture(first)
        val secondArtifact = durableArtifact()
        store.recordStagedDurableArtifact(secondArtifact)
        val second = captureCommand(secondArtifact)
        store.commitCapture(second)

        assertThrows(StoreConflict::class.java) {
            store.replacePageImage(
                ReplacePageImageCommand(
                    workspaceId,
                    first.pageId,
                    ImageVersionId.new(),
                    1L,
                    ImageVersionId.new(),
                    durableArtifact(),
                    "source-pixel-v1",
                    "retake",
                    "NONE",
                ),
            )
        }
        assertThrows(StoreInvariantViolation::class.java) {
            store.reorderPages(workspaceId, 2L, listOf(first.pageId, first.pageId))
        }
        assertThrows(StoreConflict::class.java) {
            store.reorderPages(workspaceId, 1L, listOf(second.pageId, first.pageId))
        }
        assertEquals(listOf(first.pageId.value, second.pageId.value), store.visiblePages(workspaceId).map { it.pageId.value })
        assertEquals(
            first.imageVersionId.value,
            database.schemaQueries.pageById(first.pageId.value).executeAsOne().current_image_version_id,
        )
    }

    @Test
    fun reorderAppliesExactVisiblePermutationAndAdvancesRevision() {
        val firstArtifact = durableArtifact()
        store.recordStagedDurableArtifact(firstArtifact)
        val first = captureCommand(firstArtifact)
        store.commitCapture(first)
        val secondArtifact = durableArtifact()
        store.recordStagedDurableArtifact(secondArtifact)
        val second = captureCommand(secondArtifact)
        store.commitCapture(second)

        store.reorderPages(workspaceId, 2L, listOf(second.pageId, first.pageId))

        assertEquals(listOf(second.pageId, first.pageId), store.visiblePages(workspaceId).map { it.pageId })
        assertEquals(3L, database.schemaQueries.workspaceById(workspaceId.value).executeAsOne().order_revision)
    }

    @Test
    fun staleOcrCandidateAfterRetakeIsRejectedAndDoesNotReplaceCurrentRecognition() {
        val originalArtifact = durableArtifact()
        store.recordStagedDurableArtifact(originalArtifact)
        val original = captureCommand(originalArtifact)
        store.commitCapture(original)
        val candidate = RecognitionCandidate(
            RecognitionId.new(), workspaceId, original.pageId, original.imageVersionId,
            1L, "OCR_LATIN_V1", "stale result",
        )
        val replacementArtifact = durableArtifact()
        store.recordStagedDurableArtifact(replacementArtifact)
        store.replacePageImage(
            ReplacePageImageCommand(
                workspaceId, original.pageId, original.imageVersionId, 1L, ImageVersionId.new(),
                replacementArtifact, "source-pixel-v1", "retake", "OCR_LATIN_V1",
            ),
        )

        assertThrows(StoreConflict::class.java) { store.commitRecognition(candidate) }
        val page = database.schemaQueries.pageById(original.pageId.value).executeAsOne()
        assertEquals(null, page.current_recognition_id)
        assertEquals(0L, database.schemaQueries.recognitionCount().executeAsOne())
    }

    @Test
    fun imageImportSnapshotsBeforeBeginAndAtomicallyCommitsOnePage() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val command = ImportImageCommand(
            importSessionId = ImportSessionId.new(),
            workspaceId = workspaceId,
            pageId = PageId.new(),
            imageVersionId = ImageVersionId.new(),
            sourceArtifact = artifact,
            mimeType = "image/jpeg",
            displayName = "owner's \"scan\".jpg",
            desiredOperationKey = "OCR_RUS_ENG_TESSERACT_V1",
        )

        store.beginImageImport(command)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne().state)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
        val imported = store.commitImportedImage(command)

        assertEquals(command.pageId, imported.pageId)
        assertEquals(true, store.visiblePages(workspaceId).single().selected)
        assertEquals(1L, store.visiblePageCount(workspaceId))
        assertEquals(1L, database.schemaQueries.workspaceById(workspaceId.value).executeAsOne().order_revision)
        assertEquals("COMPLETED", database.schemaQueries.importSessionById(command.importSessionId.value).executeAsOne().state)
        assertEquals(command.desiredOperationKey,
            database.schemaQueries.pageById(command.pageId.value).executeAsOne().processing_desired_state)
        val frozenParameters = database.schemaQueries.importSessionById(command.importSessionId.value)
            .executeAsOne().frozen_operation_parameters
        assertEquals(true, frozenParameters.contains("\\\"scan\\\""))
        assertEquals(false, database.schemaQueries.pageByImportTuple(command.importSessionId.value, 0L).executeAsOneOrNull() == null)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
        val importedPage = store.visiblePages(workspaceId).single()
        store.commitRecognition(
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = workspaceId,
                pageId = importedPage.pageId,
                imageVersionId = importedPage.imageVersionId,
                contentRevision = importedPage.contentRevision,
                desiredOperationKey = command.desiredOperationKey,
                recognizedText = "Invoice 8472",
            ),
        )
        assertEquals("Invoice 8472", store.visiblePages(workspaceId).single().recognizedText)
        assertThrows(StoreConflict::class.java) { store.commitImportedImage(command) }
    }

    @Test
    fun intentBackedImageImportCommitsAtomicallyAndResumesAfterRollback() {
        val importSessionId = ImportSessionId.new()
        val prepared = store.prepareImageImportPublication(
            importSessionId, workspaceId, "image/jpeg", "atomic-import.jpg",
        )
        val artifactId = prepared.intent.artifactId
        val artifact = DurableImageArtifact(
            artifactId = artifactId,
            relativePath = "objects/${artifactId.value.take(2)}/${artifactId.value}.jpg",
            sizeBytes = 128L,
            sha256 = "c".repeat(64),
            formatVersion = 1L,
            producerEpoch = "process-1",
        )
        val command = ImportImageCommand(
            importSessionId, workspaceId, prepared.intent.pageId, prepared.intent.imageVersionId,
            artifact, "image/jpeg", "atomic-import.jpg", "OCR_RUS_ENG_TESSERACT_V1",
        )

        assertThrows(IllegalStateException::class.java) {
            store.commitImageImportPublication(prepared.intent, command) {
                throw IllegalStateException("simulated process failure before SQL commit")
            }
        }
        assertEquals(1L, database.schemaQueries.pendingImageImportPublicationIntents(10L).executeAsList().size.toLong())
        assertEquals(null, database.schemaQueries.artifactById(artifactId.value).executeAsOneOrNull())
        assertEquals(null, database.schemaQueries.pageById(command.pageId.value).executeAsOneOrNull())
        assertEquals(null, database.schemaQueries.importSessionById(importSessionId.value).executeAsOneOrNull())

        val committed = store.commitImageImportPublication(prepared.intent, command)
        assertEquals(command.pageId, committed.pageId)
        assertEquals("COMPLETED", database.schemaQueries.importSessionById(importSessionId.value).executeAsOne().state)
        assertEquals(command.desiredOperationKey,
            database.schemaQueries.pageById(command.pageId.value).executeAsOne().processing_desired_state)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(artifactId.value).executeAsOne().state)
        assertEquals(1L, store.visiblePageCount(workspaceId))
        assertEquals(0L, database.schemaQueries.pendingImageImportPublicationIntents(10L).executeAsList().size.toLong())
        val committedPage = store.visiblePages(workspaceId).single()
        store.commitRecognition(
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = workspaceId,
                pageId = committedPage.pageId,
                imageVersionId = committedPage.imageVersionId,
                contentRevision = committedPage.contentRevision,
                desiredOperationKey = command.desiredOperationKey,
                recognizedText = "Invoice 8472",
            ),
        )
        assertEquals("Invoice 8472", store.visiblePages(workspaceId).single().recognizedText)
        val repeated = store.prepareImageImportPublication(
            importSessionId, workspaceId, "image/jpeg", "atomic-import.jpg",
        )
        assertEquals(false, repeated.cleanupOnFailure)
        assertEquals(command.pageId, store.commitImageImportPublication(repeated.intent, command).pageId)
        assertEquals(1L, store.visiblePageCount(workspaceId))
    }

    @Test
    fun legacyOpenImageImportSessionGetsDeterministicPageExactlyOnce() {
        val importSessionId = ImportSessionId.new()
        val artifactId = ArtifactId.new()
        val artifact = DurableImageArtifact(
            artifactId = artifactId,
            relativePath = "objects/${artifactId.value.take(2)}/${artifactId.value}.jpg",
            sizeBytes = 128L,
            sha256 = "d".repeat(64),
            formatVersion = 1L,
            producerEpoch = "process-1",
        )
        val oldCommand = ImportImageCommand(
            importSessionId, workspaceId, PageId.new(), ImageVersionId.new(),
            artifact, "image/jpeg", "legacy.jpg", "OCR_RUS_ENG_TESSERACT_V1",
        )
        store.recordStagedDurableArtifact(artifact)
        store.beginImageImport(oldCommand)
        val pageId = PageId.parse(java.util.UUID.nameUUIDFromBytes("${importSessionId.value}:0:page".toByteArray()).toString())
        val versionId = ImageVersionId.parse(java.util.UUID.nameUUIDFromBytes("${importSessionId.value}:0:image-version".toByteArray()).toString())
        val intent = store.prepareLegacyImageImportPublication(
            importSessionId, workspaceId, "legacy.jpg", "image/jpeg", artifactId, pageId, versionId,
        )
        val recoveryCommand = oldCommand.copy(pageId = pageId, imageVersionId = versionId)
        assertEquals(pageId, store.commitImageImportPublication(intent, recoveryCommand).pageId)
        assertEquals(pageId, store.commitImageImportPublication(intent, recoveryCommand).pageId)
        assertEquals("COMPLETED", store.importSessionRecord(importSessionId)?.state)
        assertEquals(1L, store.visiblePageCount(workspaceId))
    }

    @Test
    fun pdfImportResumesIdempotentlyAndIntentionalReimportCreatesNewPages() {
        val firstSession = ImportSessionId.new()
        val source = pdfSource(firstSession)
        val begin = BeginPdfImportCommand(firstSession, workspaceId, source, 2L, "two pages.pdf", "Two pages")
        assertEquals(firstSession, store.beginPdfImport(begin))
        assertEquals(firstSession, store.beginPdfImport(begin))
        assertEquals(setOf<Long>(), store.pdfImportSnapshot(firstSession)?.importedPageIndices)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())

        val firstPage = pdfPageCommand(firstSession, 0L)
        val canonicalArtifact = store.recordStagedPdfImageArtifact(firstPage.imageArtifact)
        val committedFirst = store.commitPdfImportPage(firstPage.copy(imageArtifact = canonicalArtifact))
        assertEquals(committedFirst, store.commitPdfImportPage(firstPage))
        assertEquals(setOf(0L), store.pdfImportSnapshot(firstSession)?.importedPageIndices)
        assertEquals(1L, store.visiblePageCount(workspaceId))
        assertEquals(
            canonicalArtifact.producerEpoch,
            store.recordStagedPdfImageArtifact(firstPage.imageArtifact.copy(producerEpoch = "new-process-epoch")).producerEpoch,
        )
        store.recordPdfImportFailure(firstSession, "simulated renderer interruption")
        assertEquals("simulated renderer interruption", store.pdfImportSnapshot(firstSession)?.lastError)

        val secondPage = pdfPageCommand(firstSession, 1L)
        val stagedSecondPage = secondPage.copy(imageArtifact = store.recordStagedPdfImageArtifact(secondPage.imageArtifact))
        store.commitPdfImportPage(stagedSecondPage)
        assertEquals("COMPLETED", store.pdfImportSessionState(firstSession))
        assertEquals(null, database.schemaQueries.importSessionById(firstSession.value).executeAsOne().last_error)
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
        assertEquals(2L, store.visiblePageCount(workspaceId))
        assertEquals(listOf(true, true), store.visiblePages(workspaceId).map { it.selected })

        // FROZEN §F-09: a deliberate import action always creates a new session, even for identical bytes.
        val secondSession = ImportSessionId.new()
        val secondSource = pdfSource(secondSession)
        store.beginPdfImport(
            BeginPdfImportCommand(secondSession, workspaceId, secondSource, 2L, "two pages.pdf", "Two pages"),
        )
        assertEquals("OPEN", store.pdfImportSessionState(secondSession))
        assertEquals(2L, store.visiblePageCount(workspaceId))
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(secondSource.artifactId.value).executeAsOne())
    }

    @Test
    fun importedPdfPlainTextLayoutSurvivesPersistenceAndFrozenManifestWithoutInventedCoordinates() {
        val sessionId = ImportSessionId.new()
        store.beginPdfImport(BeginPdfImportCommand(sessionId, workspaceId, pdfSource(sessionId), 1L, "plain.pdf", null))
        val text = "niña\nTotal: 123,45 €"
        val layout = RecognitionLayout(1200, 1800, emptyList(), plainText = text)
        val command = pdfPageCommand(sessionId, 0L).copy(recognizedText = text,
            recognitionId = RecognitionId.new(), recognizedLayout = layout)
        store.recordStagedDurableArtifact(command.imageArtifact)
        store.commitPdfImportPage(command)
        val frozen = store.freezeSelectedPagesForSave(workspaceId)
        assertEquals(text, frozen.pages.single().recognizedText)
        assertEquals(text, frozen.pages.single().recognizedLayout?.plainText)
        assertTrue(frozen.pages.single().recognizedLayout?.blocks?.isEmpty() == true)
        assertTrue(frozen.frozenPayload.contains("\"plainText\":\"niña\\nTotal: 123,45 €\""))
    }

    @Test
    fun pdfImportPublicationIntentIsIdempotentBoundAndAtomicallyReplacedBySession() {
        val sessionId = ImportSessionId.new()
        assertTrue(store.preparePdfImportPublication(sessionId, workspaceId, "scan.pdf"))
        assertTrue(store.preparePdfImportPublication(sessionId, workspaceId, "scan.pdf"))
        assertEquals(
            listOf(PdfImportPublicationIntent(sessionId, workspaceId, "scan.pdf")),
            store.pendingPdfImportPublicationIntents(4L),
        )
        assertThrows(StoreConflict::class.java) {
            store.preparePdfImportPublication(sessionId, workspaceId, "different-name.pdf")
        }

        val source = pdfSource(sessionId)
        store.beginPdfImport(BeginPdfImportCommand(sessionId, workspaceId, source, 2L, "scan.pdf", null))

        assertTrue(store.pendingPdfImportPublicationIntents(4L).isEmpty())
        assertEquals("OPEN", store.pdfImportSessionState(sessionId))
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(source.artifactId.value).executeAsOne())
    }

    @Test
    fun pdfImportPublicationAbandonmentRequiresUnregisteredOwnedIntent() {
        val sessionId = ImportSessionId.new()
        val intent = PdfImportPublicationIntent(sessionId, workspaceId, "abandoned.pdf")
        store.preparePdfImportPublication(sessionId, workspaceId, intent.displayName)
        store.abandonPdfImportPublication(intent)
        assertTrue(store.pendingPdfImportPublicationIntents(4L).isEmpty())

        val registeredSession = ImportSessionId.new()
        val source = pdfSource(registeredSession)
        store.beginPdfImport(BeginPdfImportCommand(registeredSession, workspaceId, source, 1L, "registered.pdf", null))
        assertEquals(false, store.preparePdfImportPublication(registeredSession, workspaceId, "registered.pdf"))
        store.abandonPdfImportPublication(PdfImportPublicationIntent(registeredSession, workspaceId, "registered.pdf"))
        assertTrue(store.pendingPdfImportPublicationIntents(4L).isEmpty())
        assertEquals("OPEN", store.pdfImportSessionState(registeredSession))
    }

    @Test
    fun pdfImportRejectsOutOfRangePageAndMismatchedRepeatWithoutVisibleMutation() {
        val sessionId = ImportSessionId.new()
        val source = pdfSource(sessionId)
        val begin = BeginPdfImportCommand(sessionId, workspaceId, source, 1L, "one.pdf", null)
        store.beginPdfImport(begin)
        val outOfRange = pdfPageCommand(sessionId, 1L)
        store.recordStagedDurableArtifact(outOfRange.imageArtifact)

        assertThrows(StoreInvariantViolation::class.java) { store.commitPdfImportPage(outOfRange) }
        assertEquals(0L, store.visiblePageCount(workspaceId))
        assertEquals("STAGED_DURABLE", database.schemaQueries.artifactById(outOfRange.imageArtifact.artifactId.value).executeAsOne().state)

        val differentSource = source.copy(sha256 = "c".repeat(64))
        assertThrows(StoreConflict::class.java) {
            store.beginPdfImport(begin.copy(sourceArtifact = differentSource))
        }
        assertEquals("OPEN", store.pdfImportSessionState(sessionId))
    }

    private fun pdfSource(sessionId: ImportSessionId): DurableExportFile = DurableExportFile(
        artifactId = ArtifactId.parse(sessionId.value),
        relativePath = "objects/${sessionId.value.take(2)}/${sessionId.value}.pdf",
        sizeBytes = 200L,
        sha256 = "d".repeat(64),
        formatVersion = 1L,
        producerEpoch = "process-pdf",
    )

    private fun pdfPageCommand(sessionId: ImportSessionId, index: Long): CommitPdfImportPageCommand {
        val artifact = durableArtifact()
        return CommitPdfImportPageCommand(
            importSessionId = sessionId,
            workspaceId = workspaceId,
            sourcePageIndex = index,
            pageId = PageId.new(),
            imageVersionId = ImageVersionId.new(),
            imageArtifact = artifact,
            desiredOperationKey = "OCR_LATIN_V1",
            sourceDisplayName = "two pages.pdf",
            recognizedText = null,
            recognitionId = null,
        )
    }

    @Test
    fun recoveryArtifactScanUsesBoundedCursorAndQuarantinesWithoutDeletingRecord() {
        val artifacts = listOf(
            "ffffffff-ffff-4fff-8fff-ffffffffffff",
            "88888888-8888-4888-8888-888888888888",
            "11111111-1111-4111-8111-111111111111",
        ).map { rawId ->
            val id = ArtifactId.parse(rawId)
            durableArtifact().copy(artifactId = id, relativePath = "objects/$rawId.jpg")
        }
        artifacts.forEach(store::recordStagedDurableArtifact)

        val batch = store.recoveryArtifactBatch(2L)
        assertEquals(2, batch.size)
        assertEquals(artifacts.take(2).map { it.artifactId }, batch.map { it.artifactId })
        batch.forEach(store::acknowledgeRecoveredArtifact)
        val last = store.recoveryArtifactBatch(2L).single()
        assertEquals(artifacts.last().artifactId, last.artifactId)
        store.quarantineRecoveredArtifact(last)

        assertEquals("QUARANTINED", database.schemaQueries.artifactById(last.artifactId.value).executeAsOne().state)
        assertEquals(0L, database.schemaQueries.referenceCountForArtifact(last.artifactId.value).executeAsOne())
        assertEquals(emptyList<RecoveryArtifact>(), store.recoveryArtifactBatch(2L))
        store.resetRecoveryCursor()
        assertEquals(2, store.recoveryArtifactBatch(10L).size)
    }

    @Test
    fun cleanupArbiterProtectsReferencesAndRecoversBothSidesOfUnlinkBoundary() {
        val root = Files.createTempDirectory("docscan-cleanup-arbiter")
        val files = FileArtifactRepository(root, DirectorySync {}, "cleanup-test")
        val protectedFile = files.publishJpeg(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        store.recordStagedDurableArtifact(protectedFile)
        store.commitCapture(captureCommand(protectedFile))

        fun committedOrphan(bytes: ByteArray): DurableImageArtifact {
            val artifact = files.publishJpeg(ByteArrayInputStream(bytes))
            store.recordStagedDurableArtifact(artifact)
            assertEquals(1L, database.schemaQueries.commitArtifact(artifact.artifactId.value).value)
            return artifact
        }

        val beforeUnlink = committedOrphan(byteArrayOf(4, 5, 6))
        val arbiter = CleanupArbiter(store, files)
        val interruptedBeforeUnlink = store.claimUnreferencedArtifacts(1L).single()
        assertEquals(beforeUnlink.artifactId, interruptedBeforeUnlink.artifactId)
        assertEquals("GC_CLAIMED", database.schemaQueries.artifactById(beforeUnlink.artifactId.value).executeAsOne().state)
        assertEquals(1, arbiter.recoverInterruptedDeletions(1L))
        assertEquals("COMMITTED", database.schemaQueries.artifactById(beforeUnlink.artifactId.value).executeAsOne().state)
        assertEquals(true, files.verifyPublished(beforeUnlink.relativePath, beforeUnlink.sizeBytes, beforeUnlink.sha256))

        val result = arbiter.runBatch(1L)
        assertEquals(1, result.deletedArtifacts)
        assertEquals("DELETED", database.schemaQueries.artifactById(beforeUnlink.artifactId.value).executeAsOne().storage_state)
        assertEquals(false, Files.exists(root.resolve(beforeUnlink.relativePath)))
        assertEquals(true, files.verifyPublished(protectedFile.relativePath, protectedFile.sizeBytes, protectedFile.sha256))
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(protectedFile.artifactId.value).executeAsOne())

        val afterUnlink = committedOrphan(byteArrayOf(7, 8, 9))
        val interruptedAfterUnlink = store.claimUnreferencedArtifacts(1L).single()
        assertEquals(VerifiedDeleteResult.DELETED, files.deleteVerified(
            interruptedAfterUnlink.relativePath,
            interruptedAfterUnlink.sizeBytes,
            interruptedAfterUnlink.sha256.orEmpty(),
        ))
        assertEquals(1, arbiter.recoverInterruptedDeletions(1L))
        assertEquals("DELETED", database.schemaQueries.artifactById(afterUnlink.artifactId.value).executeAsOne().storage_state)
        assertThrows(StoreConflict::class.java) { store.finalizeArtifactDeletion(interruptedAfterUnlink) }

        val corrupt = committedOrphan(byteArrayOf(10, 11, 12))
        store.claimUnreferencedArtifacts(1L).single()
        Files.write(root.resolve(corrupt.relativePath), byteArrayOf(99))
        assertEquals(1, arbiter.recoverInterruptedDeletions(1L))
        assertEquals("QUARANTINED", database.schemaQueries.artifactById(corrupt.artifactId.value).executeAsOne().state)
        assertTrue(Files.exists(root.resolve(corrupt.relativePath)))
    }

    @Test
    fun cleanupArbiterSerializesConcurrentWorkersAcrossUnlinkBoundary() {
        val root = Files.createTempDirectory("docscan-cleanup-workers")
        val unlinkReachedSync = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val pauseNextSync = AtomicBoolean(false)
        val files = FileArtifactRepository(root, DirectorySync {
            if (pauseNextSync.compareAndSet(true, false)) {
                unlinkReachedSync.countDown()
                check(releaseSync.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release cleanup sync" }
            }
        }, "cleanup-workers")
        val orphan = files.publishJpeg(ByteArrayInputStream(byteArrayOf(21, 22, 23)))
        store.recordStagedDurableArtifact(orphan)
        assertEquals(1L, database.schemaQueries.commitArtifact(orphan.artifactId.value).value)
        val arbiter = CleanupArbiter(store, files)
        val workers = Executors.newFixedThreadPool(2)
        try {
            pauseNextSync.set(true)
            val first = workers.submit<app.docscan.core.cleanup.CleanupBatchResult> { arbiter.runBatch(1L) }
            assertTrue("First worker did not reach post-unlink directory sync", unlinkReachedSync.await(5, TimeUnit.SECONDS))
            assertEquals("GC_CLAIMED", database.schemaQueries.artifactById(orphan.artifactId.value).executeAsOne().state)
            assertEquals(false, Files.exists(root.resolve(orphan.relativePath)))

            val secondStarted = CountDownLatch(1)
            val second = workers.submit<app.docscan.core.cleanup.CleanupBatchResult> {
                secondStarted.countDown()
                arbiter.runBatch(1L)
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertEquals("Second worker must wait while the first claim is active", false, second.isDone)
            assertEquals("GC_CLAIMED", database.schemaQueries.artifactById(orphan.artifactId.value).executeAsOne().state)

            releaseSync.countDown()
            assertEquals(1, first.get(5, TimeUnit.SECONDS).deletedArtifacts)
            val secondResult = second.get(5, TimeUnit.SECONDS)
            assertEquals(0, secondResult.deletedArtifacts)
            assertEquals(0, secondResult.recoveredClaims)
            assertEquals("DELETED", database.schemaQueries.artifactById(orphan.artifactId.value).executeAsOne().storage_state)
            assertEquals(false, Files.exists(root.resolve(orphan.relativePath)))
            assertEquals(0L, database.schemaQueries.referenceCountForArtifact(orphan.artifactId.value).executeAsOne())
        } finally {
            releaseSync.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun cleanupArbiterProtectsEveryPersistedReferenceClassAndDeletesOnlyPositiveControl() {
        val root = Files.createTempDirectory("docscan-cleanup-reference-matrix")
        val files = FileArtifactRepository(root, DirectorySync {}, "cleanup-reference-matrix")
        val referencePairs = listOf(
            "IMAGE_VERSION" to "AUTHORITATIVE",
            "IMAGE_VERSION" to "UNDO",
            "IMPORT_SESSION" to "RECOVERY",
            "SAVE_MANIFEST" to "SAVE",
            "EXPORT_ARTIFACT" to "EXPORT",
            "EXPORT_ATTEMPT" to "RECOVERY",
        )
        val protected = referencePairs.mapIndexed { index, (kind, protection) ->
            val artifact = files.publishJpeg(ByteArrayInputStream(byteArrayOf(index.toByte(), 31, 41)))
            store.recordStagedDurableArtifact(artifact)
            assertEquals(1L, database.schemaQueries.commitArtifact(artifact.artifactId.value).value)
            database.schemaQueries.addArtifactReference(
                artifact.artifactId.value,
                kind,
                "cleanup-matrix-owner-$index-${UUID.randomUUID()}",
                protection,
            )
            artifact
        }
        val orphan = files.publishJpeg(ByteArrayInputStream(byteArrayOf(99, 98, 97)))
        store.recordStagedDurableArtifact(orphan)
        assertEquals(1L, database.schemaQueries.commitArtifact(orphan.artifactId.value).value)

        val result = CleanupArbiter(store, files).runBatch(20L)

        assertEquals(1, result.claimedArtifacts)
        assertEquals(1, result.deletedArtifacts)
        assertEquals(0, result.quarantinedArtifacts)
        protected.forEachIndexed { index, artifact ->
            val row = database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne()
            assertEquals("COMMITTED", row.state)
            assertEquals("PRESENT", row.storage_state)
            assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
            assertTrue("Protected bytes changed for reference pair $index", files.verifyPublished(
                artifact.relativePath,
                artifact.sizeBytes,
                artifact.sha256,
            ))
        }
        val orphanRow = database.schemaQueries.artifactById(orphan.artifactId.value).executeAsOne()
        assertEquals("DELETED", orphanRow.storage_state)
        assertEquals(false, Files.exists(root.resolve(orphan.relativePath)))
    }

    @Test
    fun cleanupLockExcludesSeparateProcessesAndReleasesAfterException() {
        val root = Files.createTempDirectory("docscan-cleanup-process-lock")
        val files = FileArtifactRepository(root, DirectorySync {}, "cleanup-process-lock")
        val attempted = root.resolve("child-attempted")
        val acquired = root.resolve("child-acquired")
        val classpath = listOf(
            CleanupLockProcessProbe::class.java.protectionDomain.codeSource.location.toURI().path,
            FileArtifactRepository::class.java.protectionDomain.codeSource.location.toURI().path,
            kotlin.jvm.internal.Intrinsics::class.java.protectionDomain.codeSource.location.toURI().path,
        ).distinct().joinToString(java.io.File.pathSeparator)
        val java = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        var child: Process? = null
        files.withExclusiveCleanupLock {
            child = ProcessBuilder(
                java, "-cp", classpath, CleanupLockProcessProbe::class.java.name,
                root.toString(), attempted.toString(), acquired.toString(),
            ).redirectErrorStream(true).start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!Files.exists(attempted) && child!!.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
            assertTrue("Child process did not reach the lock attempt", Files.exists(attempted))
            assertEquals("Child acquired the cross-process lock while parent held it", false, Files.exists(acquired))
        }
        val process = requireNotNull(child)
        assertTrue("Child process did not finish after parent released lock", process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        assertTrue(Files.exists(acquired))

        assertThrows(IllegalStateException::class.java) {
            files.withExclusiveCleanupLock { throw IllegalStateException("injected cleanup failure") }
        }
        assertEquals("released", files.withExclusiveCleanupLock { "released" })
    }

    @Test
    fun deleteAndUndoRestoreOrderSelectionAndActivePageWithoutDeletingArtifact() {
        val firstArtifact = durableArtifact()
        store.recordStagedDurableArtifact(firstArtifact)
        val firstCommand = captureCommand(firstArtifact).copy(selected = true)
        store.commitCapture(firstCommand)
        val secondArtifact = durableArtifact()
        store.recordStagedDurableArtifact(secondArtifact)
        val secondCommand = captureCommand(secondArtifact)
        store.commitCapture(secondCommand)

        val deletedFirst = store.deletePage(workspaceId, firstCommand.pageId, 1L)
        assertEquals(true, deletedFirst.undoUntilWallMillis > 1_000L)
        assertEquals(listOf(secondCommand.pageId), store.workspaceProjection(workspaceId).pages.map { it.pageId })
        assertEquals(secondCommand.pageId, store.workspaceProjection(workspaceId).activePageId)
        store.undoDelete(workspaceId, firstCommand.pageId)

        var projection = store.workspaceProjection(workspaceId)
        assertEquals(listOf(firstCommand.pageId, secondCommand.pageId), projection.pages.map { it.pageId })
        assertEquals(true, projection.pages.first().selected)
        assertEquals(secondCommand.pageId, projection.activePageId)
        assertEquals("COMMITTED", database.schemaQueries.artifactById(firstArtifact.artifactId.value).executeAsOne().state)

        store.deletePage(workspaceId, secondCommand.pageId, 1L)
        projection = store.workspaceProjection(workspaceId)
        assertEquals(firstCommand.pageId, projection.activePageId)
        store.undoDelete(workspaceId, secondCommand.pageId)
        assertEquals(secondCommand.pageId, store.workspaceProjection(workspaceId).activePageId)
    }

    @Test
    fun supersedingSingleDeleteUndoRetainsPriorTombstoneProtectionDuringWorkspaceRetention() {
        val priorArtifact = durableArtifact()
        store.recordStagedDurableArtifact(priorArtifact)
        val priorPage = captureCommand(priorArtifact)
        store.commitCapture(priorPage)
        store.deletePage(workspaceId, priorPage.pageId, 1L)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(priorArtifact.artifactId.value).executeAsOne())

        val currentArtifact = durableArtifact()
        store.recordStagedDurableArtifact(currentArtifact)
        val currentPage = captureCommand(currentArtifact)
        store.commitCapture(currentPage)
        store.deletePage(workspaceId, currentPage.pageId, 1L)

        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(priorArtifact.artifactId.value).executeAsOne())
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(currentArtifact.artifactId.value).executeAsOne())
        assertThrows(StoreConflict::class.java) { store.undoDelete(workspaceId, priorPage.pageId) }
        assertEquals(emptyList<ArtifactId>(), store.claimUnreferencedArtifacts(100L).map { it.artifactId })
        store.undoDelete(workspaceId, currentPage.pageId)
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(currentArtifact.artifactId.value).executeAsOne())
    }

    @Test
    fun supersedingBatchDeleteUndoRetainsPriorAndWholeNewBatchProtectionDuringWorkspaceRetention() {
        val priorArtifact = durableArtifact()
        store.recordStagedDurableArtifact(priorArtifact)
        val priorPage = captureCommand(priorArtifact)
        store.commitCapture(priorPage)
        store.deletePage(workspaceId, priorPage.pageId, 1L)

        val batch = (1..2).map {
            val artifact = durableArtifact()
            store.recordStagedDurableArtifact(artifact)
            captureCommand(artifact).also(store::commitCapture)
        }
        val receipt = store.deletePages(
            workspaceId,
            batch.map { DeletePageExpectation(it.pageId, 1L) },
        )

        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(priorArtifact.artifactId.value).executeAsOne())
        batch.forEach { page ->
            assertEquals(1L, database.schemaQueries.referenceCountForArtifact(page.artifact.artifactId.value).executeAsOne())
        }
        assertEquals(emptyList<ArtifactId>(), store.claimUnreferencedArtifacts(100L).map { it.artifactId })
        store.undoDeleteBatch(workspaceId, receipt.batchId)
        batch.forEach { page ->
            assertEquals(1L, database.schemaQueries.referenceCountForArtifact(page.artifact.artifactId.value).executeAsOne())
        }
    }

    @Test
    fun supersedingPriorBatchUndoRetainsEveryDeletedPageReferenceDuringWorkspaceRetention() {
        val priorPages = (0..1).map {
            val artifact = durableArtifact()
            store.recordStagedDurableArtifact(artifact)
            captureCommand(artifact).also(store::commitCapture)
        }
        store.deletePages(workspaceId, priorPages.map { DeletePageExpectation(it.pageId, 1L) })

        val nextArtifact = durableArtifact()
        store.recordStagedDurableArtifact(nextArtifact)
        val nextPage = captureCommand(nextArtifact)
        store.commitCapture(nextPage)
        store.deletePage(workspaceId, nextPage.pageId, 1L)

        priorPages.forEach { prior ->
            assertEquals(1L, database.schemaQueries.referenceCountForArtifact(prior.artifact.artifactId.value)
                .executeAsOne())
        }
        assertEquals(1L, database.schemaQueries.referenceCountForArtifact(nextArtifact.artifactId.value).executeAsOne())
        assertEquals(emptySet<ArtifactId>(), store.claimUnreferencedArtifacts(100L).map { it.artifactId }.toSet())
        store.undoDelete(workspaceId, nextPage.pageId)
    }

    @Test
    fun batchDeleteAndUndoAreAtomicAndRestoreExactOrderSelectionAndActivePage() {
        val commands = (0..3).map { index ->
            val artifact = durableArtifact()
            store.recordStagedDurableArtifact(artifact)
            captureCommand(artifact).copy(selected = index == 1 || index == 3).also(store::commitCapture)
        }
        val before = store.workspaceProjection(workspaceId)
        val receipt = store.deletePages(
            workspaceId,
            listOf(commands[1], commands[3]).map { DeletePageExpectation(it.pageId, 1L) },
        )
        assertEquals(listOf(commands[0].pageId, commands[2].pageId), store.workspaceProjection(workspaceId).pages.map { it.pageId })
        assertEquals(commands[2].pageId, store.workspaceProjection(workspaceId).activePageId)

        store.undoDeleteBatch(workspaceId, receipt.batchId)
        val restored = store.workspaceProjection(workspaceId)
        assertEquals(before.pages.map { it.pageId }, restored.pages.map { it.pageId })
        assertEquals(before.pages.map { it.selected }, restored.pages.map { it.selected })
        assertEquals(commands[3].pageId, restored.activePageId)
        assertThrows(StoreConflict::class.java) { store.undoDeleteBatch(workspaceId, receipt.batchId) }
    }

    @Test
    fun batchDeleteRejectsStaleOrDuplicateTargetsWithoutMutatingAnyPage() {
        val commands = (0..1).map {
            val artifact = durableArtifact()
            store.recordStagedDurableArtifact(artifact)
            captureCommand(artifact).also(store::commitCapture)
        }
        val before = store.workspaceProjection(workspaceId)
        assertThrows(StoreConflict::class.java) {
            store.deletePages(
                workspaceId,
                listOf(DeletePageExpectation(commands[0].pageId, 1L), DeletePageExpectation(commands[1].pageId, 2L)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.deletePages(
                workspaceId,
                listOf(DeletePageExpectation(commands[0].pageId, 1L), DeletePageExpectation(commands[0].pageId, 1L)),
            )
        }
        val after = store.workspaceProjection(workspaceId)
        assertEquals(before.pages.map { it.pageId }, after.pages.map { it.pageId })
        assertEquals(before.orderRevision, after.orderRevision)
    }

    @Test
    fun clearDuringOcrRejectsEveryInFlightCandidateWithoutPartialStateOrArtifactLoss() {
        val artifactsAndCommands = (0..1).map {
            val artifact = durableArtifact()
            store.recordStagedDurableArtifact(artifact)
            val command = captureCommand(artifact).also(store::commitCapture)
            artifact to command
        }
        val before = store.workspaceProjection(workspaceId)
        val candidates = before.pages.map { page ->
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = workspaceId,
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                contentRevision = page.contentRevision,
                desiredOperationKey = "OCR_LATIN_V1",
                recognizedText = "late OCR for ${page.pageId.value}",
            )
        }

        val receipt = store.deletePages(
            workspaceId,
            before.pages.map { DeletePageExpectation(it.pageId, it.contentRevision) },
        )

        assertEquals(before.pages.map { it.pageId }.toSet(), receipt.pageIds.toSet())
        candidates.forEach { candidate ->
            assertThrows(StoreConflict::class.java) { store.commitRecognition(candidate) }
        }
        assertEquals(0L, store.visiblePageCount(workspaceId))
        assertEquals(null, store.workspaceProjection(workspaceId).activePageId)
        assertEquals(0L, database.schemaQueries.recognitionCount().executeAsOne())
        artifactsAndCommands.forEach { (artifact, command) ->
            val tombstoned = database.schemaQueries.pageById(command.pageId.value).executeAsOne()
            assertEquals(1L, tombstoned.tombstone)
            assertEquals(2L, tombstoned.content_revision)
            assertEquals("COMMITTED", database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne().state)
            assertEquals(1L, database.schemaQueries.referenceCountForArtifact(artifact.artifactId.value).executeAsOne())
        }
    }

    @Test
    fun staleRecognitionCannotCommitAfterDeleteAndUndoWindowExpires() {
        val artifact = durableArtifact()
        store.recordStagedDurableArtifact(artifact)
        val command = captureCommand(artifact)
        store.commitCapture(command)
        store.deletePage(workspaceId, command.pageId, 1L)

        assertThrows(StoreConflict::class.java) {
            store.commitRecognition(
                RecognitionCandidate(
                    RecognitionId.new(), workspaceId, command.pageId, command.imageVersionId,
                    1L, "OCR_LATIN_V1", "late recognition",
                ),
            )
        }
        fakeClock.set(TimeReading(9_001L, 8_000_002_001L, "test-epoch"))
        assertThrows(StoreConflict::class.java) { store.undoDelete(workspaceId, command.pageId) }
        assertEquals(0L, store.visiblePageCount(workspaceId))
        assertEquals("COMMITTED", database.schemaQueries.artifactById(artifact.artifactId.value).executeAsOne().state)
    }

    @Test
    fun successfulExternalPackageConsumesOnlyFrozenPagesAndPreservesUnselectedPages() {
        val fixture = committedExportPackageFixture()
        val frozenPage = store.workspaceProjection(workspaceId).pages.single()
        val unselectedArtifact = durableArtifact()
        store.recordStagedDurableArtifact(unselectedArtifact)
        val unselected = captureCommand(unselectedArtifact).copy(selected = false)
        store.commitCapture(unselected)

        confirmPackageOutputs(fixture)

        assertEquals("SUCCEEDED", store.externalExportPackage(fixture.export.exportArtifactId)?.state)
        val remaining = store.workspaceProjection(workspaceId).pages
        assertEquals(listOf(unselected.pageId), remaining.map { it.pageId })
        assertEquals(false, remaining.single().selected)
        assertEquals(1L, store.visiblePageCount(workspaceId))
        assertEquals(1L, database.schemaQueries.pageById(frozenPage.pageId.value).executeAsOne().tombstone)
    }

    @Test
    fun failedOrUnknownExternalPackageDoesNotConsumeFrozenPages() {
        val failed = committedExportPackageFixture()
        val failedPage = store.workspaceProjection(workspaceId).pages.single()
        val pdf = store.committedExportOutputs(failed.export.exportArtifactId).single { it.mimeType == "application/pdf" }
        val failedCommand = exportAttemptCommand(failed, pdf)
        store.beginExportAttempt(failedCommand)
        store.finishWritingExportAttempt(
            failedCommand.exportAttemptId, ExportAttemptOutcome.FAILED_KNOWN, null,
            "provider rejected before create", true, observation("known-failure-${failedCommand.exportAttemptId.value}", "no side effect"),
        )
        assertEquals("FAILED_KNOWN", store.externalExportPackage(failed.export.exportArtifactId)?.state)
        assertTrue(store.workspaceProjection(workspaceId).pages.any { it.pageId == failedPage.pageId })
        store.setSelected(workspaceId, failedPage.pageId, false)

        val unknown = committedExportPackageFixture()
        val unknownPage = store.workspaceProjection(workspaceId).pages.last()
        val unknownPdf = store.committedExportOutputs(unknown.export.exportArtifactId).single { it.mimeType == "application/pdf" }
        val unknownCommand = exportAttemptCommand(unknown, unknownPdf)
        store.beginExportAttempt(unknownCommand)
        store.markWritingExportAttemptUnknown(
            unknownCommand.exportAttemptId, "provider outcome ambiguous",
            observation("unknown-${unknownCommand.exportAttemptId.value}", "outcome not observed"),
        )
        assertEquals("UNKNOWN_OUTCOME", store.externalExportPackage(unknown.export.exportArtifactId)?.state)
        assertTrue(store.workspaceProjection(workspaceId).pages.any { it.pageId == unknownPage.pageId })
    }

    @Test
    fun packageCompletionPreservesFrozenPageWhoseImageChangedAfterFreeze() {
        val fixture = committedExportPackageFixture()
        val original = store.workspaceProjection(workspaceId).pages.single()
        val replacement = durableArtifact()
        store.recordStagedDurableArtifact(replacement)
        store.replacePageImage(
            ReplacePageImageCommand(
                workspaceId, original.pageId, original.imageVersionId, original.contentRevision,
                ImageVersionId.new(), replacement, "source-pixel-v1", "retake-after-save-freeze", "NONE",
            ),
        )

        confirmPackageOutputs(fixture)

        val current = store.workspaceProjection(workspaceId).pages.single()
        assertEquals(original.pageId, current.pageId)
        assertEquals(replacement.relativePath, current.artifactRelativePath)
        assertEquals(true, current.selected)
    }

    @Test
    fun confirmingLastUnknownOutputConsumesFrozenPagesExactlyOnce() {
        val fixture = committedExportPackageFixture()
        val outputs = store.committedExportOutputs(fixture.export.exportArtifactId)
        val pdf = outputs.single { it.mimeType == "application/pdf" }
        val jpeg = outputs.single { it.mimeType == "image/jpeg" }
        val pdfCommand = exportAttemptCommand(fixture, pdf)
        store.beginExportAttempt(pdfCommand)
        store.finishWritingExportAttempt(
            pdfCommand.exportAttemptId, ExportAttemptOutcome.SUCCEEDED,
            "content://test.provider/${pdf.artifactId.value}", null, false,
            observation("confirmed-pdf-${pdfCommand.exportAttemptId.value}", "full read-back"),
        )
        val jpegCommand = exportAttemptCommand(fixture, jpeg)
        store.beginExportAttempt(jpegCommand)
        store.markWritingExportAttemptUnknown(
            jpegCommand.exportAttemptId, "process ended before acknowledgement",
            observation("ambiguous-jpg-${jpegCommand.exportAttemptId.value}", "side effect unresolved"),
        )
        val sourcePageId = store.workspaceProjection(workspaceId).pages.single().pageId
        assertTrue(store.workspaceProjection(workspaceId).pages.any { it.pageId == sourcePageId })

        store.revalidateUnknownExportAttempt(
            jpegCommand.exportAttemptId, ExportAttemptOutcome.SUCCEEDED,
            "content://test.provider/${jpeg.artifactId.value}", null, false,
            observation("recovered-jpg-${jpegCommand.exportAttemptId.value}", "full read-back during recovery"),
        )

        assertEquals("SUCCEEDED", store.externalExportPackage(fixture.export.exportArtifactId)?.state)
        assertEquals(0L, store.visiblePageCount(workspaceId))
        assertEquals(1L, database.schemaQueries.pageById(sourcePageId.value).executeAsOne().tombstone)
    }

    private fun confirmPackageOutputs(fixture: CommittedExportPackageFixture) {
        val outputs = store.committedExportOutputs(fixture.export.exportArtifactId)
        outputs.forEach { output ->
            val command = exportAttemptCommand(fixture, output)
            store.beginExportAttempt(command)
            store.finishWritingExportAttempt(
                command.exportAttemptId, ExportAttemptOutcome.SUCCEEDED,
                "content://test.provider/${output.artifactId.value}", null, false,
                observation("success-${command.exportAttemptId.value}", "full read-back"),
            )
        }
    }

    private fun exportAttemptCommand(
        fixture: CommittedExportPackageFixture,
        output: CommittedExportOutput,
    ): BeginExportAttemptCommand {
        val packageSnapshot = requireNotNull(store.externalExportPackage(fixture.export.exportArtifactId))
        val mapping = packageSnapshot.outputs.single { it.outputArtifactId == output.artifactId }
        return BeginExportAttemptCommand(
            ExportAttemptId.new(), fixture.export.exportArtifactId, output.artifactId,
            packageSnapshot.destinationTreeUri, mapping.displayName, output.mimeType,
            fixture.provider.qualificationId, fixture.provider.adapterId, fixture.provider.providerVersion,
            fixture.provider.osVersion, fixture.provider.destinationAccessClass,
        )
    }

    private fun durableArtifact(): DurableImageArtifact {
        val id = ArtifactId.new()
        return DurableImageArtifact(
        artifactId = id,
        relativePath = "objects/${id.value}.jpg",
        sizeBytes = 128L,
        sha256 = "a".repeat(64),
        formatVersion = 1L,
        producerEpoch = "process-1",
        )
    }

    private fun durableExportFile(extension: String): DurableExportFile {
        val id = ArtifactId.new()
        return DurableExportFile(
            artifactId = id,
            relativePath = "objects/${id.value}.$extension",
            sizeBytes = 20L,
            sha256 = "b".repeat(64),
            formatVersion = 1L,
            producerEpoch = "process-1",
        )
    }

    private data class CommittedExportFixture(
        val exportArtifactId: ExportArtifactId,
        val output: DurableExportFile,
    )

    private fun committedExportFixture(): CommittedExportFixture {
        val source = durableArtifact()
        store.recordStagedDurableArtifact(source)
        val page = captureCommand(source).copy(selected = true)
        store.commitCapture(page)
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val pdf = durableExportFile("pdf")
        val jpg = durableExportFile("jpg")
        val files = listOf(
            LocalExportFileCommand(pdf, "PDF", null),
            LocalExportFileCommand(jpg, "JPG_PAGE", page.imageVersionId),
        )
        files.forEach { store.recordStagedExportFile(manifest.saveManifestId, it.file) }
        val exportId = ExportArtifactId.new()
        store.commitLocalExportArtifact(CommitLocalExportCommand(exportId, manifest.saveManifestId, files))
        store.recordDestinationGrant("content://test.provider/tree/root", 100L)
        return CommittedExportFixture(exportId, pdf)
    }

    private data class CommittedExportPackageFixture(
        val export: CommittedExportFixture,
        val provider: ProviderQualificationCommand,
    )

    private fun committedExportPackageFixture(pageCount: Int = 1, pdfOnly: Boolean = false): CommittedExportPackageFixture {
        require(pageCount > 0)
        val pages = (0 until pageCount).map {
            val source = durableArtifact()
            store.recordStagedDurableArtifact(source)
            captureCommand(source).copy(selected = true).also(store::commitCapture)
        }
        val manifest = store.freezeSelectedPagesForSave(workspaceId)
        store.beginLocalOutputComposition(manifest)
        val pdf = durableExportFile("pdf")
        val jpgs = pages.map { durableExportFile("jpg") }
        val files = listOf(LocalExportFileCommand(pdf, "PDF", null)) + pages.mapIndexed { index, page ->
            LocalExportFileCommand(jpgs[index], "JPG_PAGE", page.imageVersionId)
        }
        files.forEach { store.recordStagedExportFile(manifest.saveManifestId, it.file) }
        val provider = qualifiedProvider()
        store.recordDestinationGrant("content://test.provider/tree/root", 100L)
        val exportId = ExportArtifactId.new()
        store.commitLocalExportArtifact(
            CommitLocalExportCommand(
                exportId,
                manifest.saveManifestId,
                files,
                externalPackage = ExternalExportPackageCommand(
                    "content://test.provider/tree/root",
                    "Scan",
                    provider.qualificationId,
                    provider.adapterId,
                    provider.providerVersion,
                    provider.osVersion,
                    provider.destinationAccessClass,
                    listOf(ExternalExportPackageOutputCommand(pdf.artifactId, "Scan.pdf", 0L)) +
                        if (pdfOnly) emptyList() else
                        jpgs.mapIndexed { index, jpg ->
                            ExternalExportPackageOutputCommand(
                                jpg.artifactId,
                                "Scan-${"%03d".format(index + 1)}.jpg",
                                (index + 1).toLong(),
                            )
                        },
                    "FULL",
                ),
            ),
        )
        return CommittedExportPackageFixture(CommittedExportFixture(exportId, pdf), provider)
    }

    private fun qualifiedProvider(): ProviderQualificationCommand =
        ProviderQualificationCommand(
            qualificationId = "qualification-${UUID.randomUUID()}",
            adapterId = "test-saf-adapter-v1",
            providerVersion = "test-provider-1",
            osVersion = "android-test-api",
            destinationAccessClass = "PERSISTED_TREE_READ_WRITE",
            validationSuiteVersion = "test-suite-v1",
            qualifiedCapabilitiesPayload = "{\"create\":true,\"write\":true,\"observe\":true}",
        ).also(store::recordProviderQualification)

    private fun beginAttempt(
        fixture: CommittedExportFixture,
        provider: ProviderQualificationCommand,
    ) = BeginExportAttemptCommand(
        exportAttemptId = ExportAttemptId.new(),
        exportArtifactId = fixture.exportArtifactId,
        outputArtifactId = fixture.output.artifactId,
        destinationTreeUri = "content://test.provider/tree/root",
        plannedDisplayName = "Scan.pdf",
        mimeType = "application/pdf",
        providerQualificationId = provider.qualificationId,
        adapterId = provider.adapterId,
        providerVersion = provider.providerVersion,
        osVersion = provider.osVersion,
        destinationAccessClass = provider.destinationAccessClass,
    )

    private fun observation(id: String, evidence: String) = ProviderOperationObservationCommand(
        observationId = id,
        identityEvidence = evidence,
        existenceEvidence = null,
        integrityEvidence = null,
        readabilityEvidence = null,
        nameEvidence = null,
        accessEvidence = null,
    )

    private fun captureCommand(artifact: DurableImageArtifact) = CaptureCommand(
        workspaceId = workspaceId,
        pageId = PageId.new(),
        imageVersionId = ImageVersionId.new(),
        artifact = artifact,
        selected = false,
        makeActive = true,
        coordinateSpace = "source-pixel-v1",
        processingDesiredState = "OCR_LATIN_V1",
        sourceProvenance = "{\"kind\":\"camera\"}",
    )
}

object CleanupLockProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = java.nio.file.Path.of(args[0])
        val attempted = java.nio.file.Path.of(args[1])
        val acquired = java.nio.file.Path.of(args[2])
        Files.writeString(attempted, "attempted")
        FileArtifactRepository(root, DirectorySync {}, "cleanup-process-probe").withExclusiveCleanupLock {
            Files.writeString(acquired, "acquired")
        }
    }
}
