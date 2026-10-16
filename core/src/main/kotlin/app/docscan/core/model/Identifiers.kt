package app.docscan.core.model

import java.util.UUID

@JvmInline
value class WorkspaceId private constructor(val value: String) {
    companion object {
        fun new(): WorkspaceId = WorkspaceId(UUID.randomUUID().toString())
        fun parse(value: String): WorkspaceId = WorkspaceId(requireUuid(value))
    }
}

@JvmInline
value class WorkspaceSessionId private constructor(val value: String) {
    companion object {
        fun new(): WorkspaceSessionId = WorkspaceSessionId(UUID.randomUUID().toString())
        fun parse(value: String): WorkspaceSessionId = WorkspaceSessionId(requireUuid(value))
    }
}

@JvmInline
value class PageId private constructor(val value: String) {
    companion object {
        fun new(): PageId = PageId(UUID.randomUUID().toString())
        fun parse(value: String): PageId = PageId(requireUuid(value))
    }
}

@JvmInline
value class ImageVersionId private constructor(val value: String) {
    companion object {
        fun new(): ImageVersionId = ImageVersionId(UUID.randomUUID().toString())
        fun parse(value: String): ImageVersionId = ImageVersionId(requireUuid(value))
    }
}

@JvmInline
value class ArtifactId private constructor(val value: String) {
    companion object {
        fun new(): ArtifactId = ArtifactId(UUID.randomUUID().toString())
        fun parse(value: String): ArtifactId = ArtifactId(requireUuid(value))

        /** Stable private output identity for one position in a frozen normal SaveManifest. */
        fun forSaveOutput(saveManifestId: SaveManifestId, ordinal: Int): ArtifactId {
            require(ordinal >= 0)
            val bytes = "docscan-normal-save-output-v1:${saveManifestId.value}:$ordinal"
                .toByteArray(Charsets.UTF_8)
            return ArtifactId(UUID.nameUUIDFromBytes(bytes).toString())
        }
    }
}

@JvmInline
value class RecognitionId private constructor(val value: String) {
    companion object {
        fun new(): RecognitionId = RecognitionId(UUID.randomUUID().toString())
        fun parse(value: String): RecognitionId = RecognitionId(requireUuid(value))
    }
}

@JvmInline
value class ImportSessionId private constructor(val value: String) {
    companion object {
        fun new(): ImportSessionId = ImportSessionId(UUID.randomUUID().toString())
        fun parse(value: String): ImportSessionId = ImportSessionId(requireUuid(value))
    }
}

@JvmInline
value class SaveManifestId private constructor(val value: String) {
    companion object {
        fun new(): SaveManifestId = SaveManifestId(UUID.randomUUID().toString())
        fun parse(value: String): SaveManifestId = SaveManifestId(requireUuid(value))
    }
}

@JvmInline
value class ExportArtifactId private constructor(val value: String) {
    companion object {
        fun new(): ExportArtifactId = ExportArtifactId(UUID.randomUUID().toString())
        fun parse(value: String): ExportArtifactId = ExportArtifactId(requireUuid(value))
    }
}

@JvmInline
value class ExportAttemptId private constructor(val value: String) {
    companion object {
        fun new(): ExportAttemptId = ExportAttemptId(UUID.randomUUID().toString())
        fun parse(value: String): ExportAttemptId = ExportAttemptId(requireUuid(value))
    }
}

@JvmInline
value class DeleteBatchId private constructor(val value: String) {
    companion object {
        fun new(): DeleteBatchId = DeleteBatchId(UUID.randomUUID().toString())
        fun parse(value: String): DeleteBatchId = DeleteBatchId(requireUuid(value))
    }
}

private fun requireUuid(value: String): String {
    val normalized = UUID.fromString(value).toString()
    require(normalized == value) { "Identifier must be a canonical lowercase UUID" }
    return normalized
}
