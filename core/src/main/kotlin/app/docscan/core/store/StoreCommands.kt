package app.docscan.core.store

import app.docscan.core.model.ArtifactId
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.model.ExportArtifactId
import app.docscan.core.model.DeleteBatchId
import app.docscan.core.model.ImportSessionId
import app.docscan.core.model.PageId
import app.docscan.core.model.SaveManifestId
import app.docscan.core.model.WorkspaceId
import app.docscan.core.model.WorkspaceSessionId

@ConsistentCopyVisibility
data class DurableImageArtifact internal constructor(
    val artifactId: ArtifactId,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val formatVersion: Long,
    val producerEpoch: String,
) {
    init {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/'))
        require(relativePath.split('/').none { it == ".." || it.isBlank() })
        require(sizeBytes > 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(formatVersion > 0L)
        require(producerEpoch.isNotBlank())
    }
}

/** Durable immutable output bytes, published in app-private object storage before DB commit. */
@ConsistentCopyVisibility
data class DurableExportFile internal constructor(
    val artifactId: ArtifactId,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val formatVersion: Long,
    val producerEpoch: String,
) {
    init {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/'))
        require(relativePath.split('/').none { it == ".." || it.isBlank() })
        require(sizeBytes > 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(formatVersion > 0L)
        require(producerEpoch.isNotBlank())
    }
}

data class LocalExportFileCommand(
    val file: DurableExportFile,
    val fileKind: String,
    val sourceImageVersionId: app.docscan.core.model.ImageVersionId?,
) {
    init {
        require(fileKind == "PDF" || fileKind == "JPG_PAGE")
        require((fileKind == "PDF") == (sourceImageVersionId == null))
        require(file.relativePath.endsWith(if (fileKind == "PDF") ".pdf" else ".jpg"))
    }
}

data class CommitLocalExportCommand(
    val exportArtifactId: app.docscan.core.model.ExportArtifactId,
    val saveManifestId: SaveManifestId,
    val files: List<LocalExportFileCommand>,
    val externalPackage: ExternalExportPackageCommand? = null,
    val artifactFormatVersion: Long = 1L,
    val structuralValidationVersion: Long = 1L,
    val outputKind: String = "PDF_WITH_JPG_PAGE_SET",
    val expectedPageCount: Long? = null,
) {
    init {
        require(outputKind in setOf("PDF_WITH_JPG_PAGE_SET", "EDITED_SAVED_PDF"))
        if (outputKind == "PDF_WITH_JPG_PAGE_SET") {
            require(files.isNotEmpty() && files.count { it.fileKind == "PDF" } == 1)
            require(files.first().fileKind == "PDF" && files.drop(1).all { it.fileKind == "JPG_PAGE" })
            require(expectedPageCount == null)
        } else {
            require(files.size == 1 && files.single().fileKind == "PDF")
            require(expectedPageCount != null && expectedPageCount > 0L)
        }
        require(files.all { it.file.sizeBytes > 0L })
        require(files.map { it.file.artifactId }.distinct().size == files.size)
        require(artifactFormatVersion > 0L && structuralValidationVersion > 0L)
    }
}

data class ExternalExportPackageOutputCommand(
    val outputArtifactId: ArtifactId,
    val displayName: String,
    val ordinal: Long,
) {
    init {
        require(displayName.isNotBlank() && !displayName.contains('/') && !displayName.contains('\\'))
        require(ordinal >= 0L)
    }
}

data class ExternalExportPackagePlan(
    val destinationTreeUri: String,
    val commonBasename: String,
    val providerQualificationId: String,
    val adapterId: String,
    val providerVersion: String,
    val osVersion: String,
    val destinationAccessClass: String,
    val outputDisplayNames: List<String>,
    val ocrStatus: String = "NONE",
) {
    init {
        require(destinationTreeUri.startsWith("content://"))
        require(listOf(commonBasename, providerQualificationId, adapterId, providerVersion, osVersion,
            destinationAccessClass).all { it.isNotBlank() })
        require(outputDisplayNames.isNotEmpty() && outputDisplayNames.all { it.isNotBlank() })
        require(outputDisplayNames.distinct().size == outputDisplayNames.size)
        require(ocrStatus in setOf("FULL", "PARTIAL", "NONE"))
    }
}

data class ExternalExportPackageCommand(
    val destinationTreeUri: String,
    val commonBasename: String,
    val providerQualificationId: String,
    val adapterId: String,
    val providerVersion: String,
    val osVersion: String,
    val destinationAccessClass: String,
    val outputs: List<ExternalExportPackageOutputCommand>,
    val ocrStatus: String = "NONE",
) {
    init {
        require(destinationTreeUri.startsWith("content://"))
        require(listOf(commonBasename, providerQualificationId, adapterId, providerVersion, osVersion,
            destinationAccessClass).all { it.isNotBlank() })
        require(outputs.isNotEmpty() && outputs.map { it.outputArtifactId }.distinct().size == outputs.size)
        require(outputs.map { it.ordinal } == outputs.indices.map(Int::toLong))
        require(outputs.map { it.displayName }.distinct().size == outputs.size)
        require(ocrStatus in setOf("FULL", "PARTIAL", "NONE"))
    }
}

data class DestinationGrantSnapshot(
    val treeUri: String,
    val grantedWallMillis: Long,
    val lastVerifiedWallMillis: Long,
) {
    init {
        require(treeUri.startsWith("content://"))
        require(grantedWallMillis >= 0L && lastVerifiedWallMillis >= grantedWallMillis)
    }
}

data class ProviderQualificationCommand(
    val qualificationId: String,
    val adapterId: String,
    val providerVersion: String,
    val osVersion: String,
    val destinationAccessClass: String,
    val validationSuiteVersion: String,
    val qualifiedCapabilitiesPayload: String,
) {
    init {
        require(listOf(qualificationId, adapterId, providerVersion, osVersion, destinationAccessClass,
            validationSuiteVersion, qualifiedCapabilitiesPayload).all { it.isNotBlank() })
    }
}

data class ProviderQualificationSnapshot(
    val qualificationId: String,
    val adapterId: String,
    val providerVersion: String,
    val osVersion: String,
    val destinationAccessClass: String,
    val validationSuiteVersion: String,
    val qualifiedCapabilitiesPayload: String,
)

data class BeginExportAttemptCommand(
    val exportAttemptId: ExportAttemptId,
    val exportArtifactId: ExportArtifactId,
    val outputArtifactId: ArtifactId,
    val destinationTreeUri: String,
    val plannedDisplayName: String,
    val mimeType: String,
    val providerQualificationId: String,
    val adapterId: String,
    val providerVersion: String,
    val osVersion: String,
    val destinationAccessClass: String,
    val durableWorkflowPayloadVersion: Long = 1L,
) {
    init {
        require(destinationTreeUri.startsWith("content://"))
        require(plannedDisplayName.isNotBlank() && !plannedDisplayName.contains('/') && !plannedDisplayName.contains('\\'))
        require(mimeType in setOf("application/pdf", "image/jpeg"))
        require(providerQualificationId.isNotBlank() && adapterId.isNotBlank() && providerVersion.isNotBlank())
        require(osVersion.isNotBlank() && destinationAccessClass.isNotBlank() && durableWorkflowPayloadVersion > 0L)
    }
}

data class ExportAttemptSnapshot(
    val exportAttemptId: ExportAttemptId,
    val exportArtifactId: ExportArtifactId,
    val outputArtifactId: ArtifactId?,
    val destinationDescriptor: String,
    val frozenProviderParameters: String,
    val state: String,
    val externalIdentity: String?,
    val operationObservationId: String?,
    val knownError: String?,
)

data class ExternalExportPackageSnapshot(
    val exportArtifactId: ExportArtifactId,
    val destinationTreeUri: String,
    val commonBasename: String,
    val providerQualificationId: String,
    val adapterId: String,
    val providerVersion: String,
    val osVersion: String,
    val destinationAccessClass: String,
    val state: String,
    val lastError: String?,
    val ocrStatus: String,
    val outputs: List<ExternalExportPackageOutputCommand>,
)

data class SavedPdfEditManifestCommand(
    val saveManifestId: SaveManifestId,
    val workspaceId: app.docscan.core.model.WorkspaceId,
    val sourceExportArtifactId: app.docscan.core.model.ExportArtifactId,
    val sourcePdfArtifactId: app.docscan.core.model.ArtifactId,
    val sourcePdfSha256: String,
    val sourcePageCount: Long,
    val orderedSourcePageIndexes: List<Int>,
) {
    init {
        require(sourcePdfSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourcePageCount > 0L && orderedSourcePageIndexes.isNotEmpty())
        require(orderedSourcePageIndexes.distinct().size == orderedSourcePageIndexes.size)
        require(orderedSourcePageIndexes.all { it >= 0 && it.toLong() < sourcePageCount })
    }
}

data class RecentDocumentSnapshot(
    val recentId: String,
    val exportArtifactId: ExportArtifactId,
    val documentUri: String,
    val displayName: String,
    val pageCount: Long,
    val savedWallMillis: Long,
    val ocrStatus: String,
)

data class CommittedExportOutput(
    val artifactId: ArtifactId,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val formatVersion: Long,
) {
    val mimeType: String
        get() = when {
            relativePath.endsWith(".pdf") -> "application/pdf"
            relativePath.endsWith(".jpg") -> "image/jpeg"
            else -> throw StoreInvariantViolation("Committed export output has an unsupported extension")
        }
}

data class ProviderOperationObservationCommand(
    val observationId: String,
    val identityEvidence: String?,
    val existenceEvidence: String?,
    val integrityEvidence: String?,
    val readabilityEvidence: String?,
    val nameEvidence: String?,
    val accessEvidence: String?,
) {
    init {
        require(observationId.isNotBlank())
        require(listOf(identityEvidence, existenceEvidence, integrityEvidence, readabilityEvidence,
            nameEvidence, accessEvidence).any { !it.isNullOrBlank() })
    }
}

enum class ExportAttemptOutcome { SUCCEEDED, FAILED_KNOWN, UNKNOWN_OUTCOME }

data class CaptureCommand(
    val workspaceId: WorkspaceId,
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
    val artifact: DurableImageArtifact,
    val selected: Boolean,
    val makeActive: Boolean,
    val coordinateSpace: String,
    val processingDesiredState: String,
    val sourceProvenance: String,
    val autoSelectWhenReady: Boolean = false,
) {
    init {
        require(coordinateSpace.isNotBlank())
        require(processingDesiredState.isNotBlank())
    }
}

data class CapturedPage(
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
    val artifactId: ArtifactId,
    val orderIndex: Long,
)

data class StoredPage(
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
    val artifactRelativePath: String,
    val orderIndex: Long,
    val selected: Boolean,
    val contentRevision: Long,
    val canUndoImage: Boolean,
    val recognizedText: String?,
    val artifactState: String,
)

data class WorkspaceProjection(
    val orderRevision: Long,
    val activePageId: PageId?,
    val pages: List<StoredPage>,
)

data class WorkspaceIntegrityFinding(
    val code: String,
    val pageId: String,
)

enum class WorkspaceBootstrapState { EMPTY, EXISTING, ORPHANED }

data class ArtifactReferenceIntegrityFinding(
    val code: String,
    val artifactId: ArtifactId,
    val refKind: String,
    val refOwnerId: String,
)

data class FrozenSavePage(
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
    val artifactId: ArtifactId,
    val artifactRelativePath: String,
    val artifactSizeBytes: Long,
    val artifactSha256: String,
    val artifactFormatVersion: Long,
    val contentRevision: Long,
    val orderIndex: Long,
    val recognizedText: String?,
    val recognizedGeometry: RecognitionGeometry?,
    val recognizedLayout: RecognitionLayout? = null,
)

@ConsistentCopyVisibility
data class SaveManifestSnapshot internal constructor(
    val saveManifestId: SaveManifestId,
    val workspaceId: WorkspaceId,
    val frozenPayload: String,
    val pages: List<FrozenSavePage>,
)

data class DeleteReceipt(
    val pageId: PageId,
    val undoUntilWallMillis: Long,
    val wasActive: Boolean,
)

data class DeleteBatchReceipt(
    val batchId: DeleteBatchId,
    val pageIds: List<PageId>,
    val undoUntilWallMillis: Long,
) {
    init {
        require(pageIds.isNotEmpty() && pageIds.distinct().size == pageIds.size)
    }
}

data class DeletePageExpectation(
    val pageId: PageId,
    val contentRevision: Long,
) {
    init { require(contentRevision > 0L) }
}

data class RecognitionCandidate(
    val recognitionId: app.docscan.core.model.RecognitionId,
    val workspaceId: WorkspaceId,
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
    val contentRevision: Long,
    val desiredOperationKey: String,
    val recognizedText: String,
    val geometry: RecognitionGeometry? = null,
    val layout: RecognitionLayout? = null,
)

data class RecognitionGeometry(
    val sourceWidthPx: Long,
    val sourceHeightPx: Long,
    val coordinateSpace: String,
    val words: List<RecognizedWord>,
    val formatVersion: Long = 1L,
)

data class RecognizedWord(
    val text: String,
    val leftPx: Long,
    val topPx: Long,
    val rightPx: Long,
    val bottomPx: Long,
    val confidence: Double,
)

const val OCR_WORD_COORDINATE_SPACE = "ORIENTED_DECODED_PIXELS_TOP_LEFT"

data class ImportImageCommand(
    val importSessionId: ImportSessionId,
    val workspaceId: WorkspaceId,
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
    val sourceArtifact: DurableImageArtifact,
    val mimeType: String,
    val displayName: String,
    val desiredOperationKey: String,
) {
    init {
        require(mimeType.startsWith("image/"))
        require(displayName.isNotBlank())
        require(desiredOperationKey.isNotBlank())
    }
}

data class ImageImportPublicationIntent(
    val importSessionId: ImportSessionId,
    val workspaceId: WorkspaceId,
    val displayName: String,
    val mimeType: String,
    val artifactId: ArtifactId,
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
) {
    init {
        require(displayName.isNotBlank())
        require(mimeType.startsWith("image/"))
    }
}

data class PreparedImageImportPublication(
    val intent: ImageImportPublicationIntent,
    /** True only when this unfinished intent may be cleaned after a synchronous copy failure. */
    val cleanupOnFailure: Boolean,
)

data class BeginPdfImportCommand(
    val importSessionId: ImportSessionId,
    val workspaceId: WorkspaceId,
    val sourceArtifact: DurableExportFile,
    val sourcePageCount: Long,
    val displayName: String,
    val documentTitle: String?,
) {
    init {
        require(sourceArtifact.relativePath.endsWith(".pdf"))
        require(sourcePageCount in 1L..500L)
        require(displayName.isNotBlank())
    }
}

data class PdfImportPublicationIntent(
    val importSessionId: ImportSessionId,
    val workspaceId: WorkspaceId,
    val displayName: String,
)

data class PdfImportSourceSnapshot(
    val importSessionId: ImportSessionId,
    val workspaceId: WorkspaceId,
    val sourceArtifactId: ArtifactId,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val formatVersion: Long,
    val producerEpoch: String,
    val sourcePageCount: Long,
    val displayName: String,
    val lastError: String?,
    val importedPageIndices: Set<Long>,
)

data class CommitPdfImportPageCommand(
    val importSessionId: ImportSessionId,
    val workspaceId: WorkspaceId,
    val sourcePageIndex: Long,
    val pageId: PageId,
    val imageVersionId: ImageVersionId,
    val imageArtifact: DurableImageArtifact,
    val desiredOperationKey: String,
    val sourceDisplayName: String,
    val recognizedText: String?,
    val recognitionId: app.docscan.core.model.RecognitionId?,
) {
    init {
        require(sourcePageIndex >= 0L && desiredOperationKey.isNotBlank() && sourceDisplayName.isNotBlank())
        require((recognizedText == null) == (recognitionId == null))
        require(recognizedText == null || recognizedText.isNotBlank())
    }
}

data class RecoveryArtifact(
    val recoverySequence: Long,
    val artifactId: ArtifactId,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String?,
    val state: String,
)

data class ExportOutputPublicationIntentSnapshot(
    val artifactId: ArtifactId,
    val saveManifestId: SaveManifestId,
    val outputOrdinal: Int,
    val extension: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val formatVersion: Long,
    val producerEpoch: String,
)

data class ArtifactDeletionClaim(
    val artifactId: ArtifactId,
    val claimToken: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String?,
)

data class ReplacePageImageCommand(
    val workspaceId: WorkspaceId,
    val pageId: PageId,
    val expectedImageVersionId: ImageVersionId,
    val expectedContentRevision: Long,
    val newImageVersionId: ImageVersionId,
    val artifact: DurableImageArtifact,
    val coordinateSpace: String,
    val transformProvenance: String,
    val processingDesiredState: String,
) {
    init {
        require(expectedContentRevision > 0L)
        require(coordinateSpace.isNotBlank() && transformProvenance.isNotBlank())
        require(processingDesiredState.isNotBlank())
    }
}

class StoreInvariantViolation(message: String) : IllegalStateException(message)

class StoreConflict(message: String) : IllegalStateException(message)
