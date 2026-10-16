package app.docscan.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.docscan.core.model.ExportAttemptId
import app.docscan.core.export.OutputNameResolver
import app.docscan.core.export.OutputNameResolution
import app.docscan.core.store.BeginExportAttemptCommand
import app.docscan.core.store.CommittedExportOutput
import app.docscan.core.store.ExportAttemptOutcome
import app.docscan.core.store.ExportAttemptSnapshot
import app.docscan.core.store.ProviderOperationObservationCommand
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Performs one qualified SAF document write; every attempt is journaled before provider I/O. */
class SafExportGateway(context: Context, private val storage: ProductStorage) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val grants = SafDestinationGrantManager(appContext)

    fun resolveOutputNames(treeUri: String, basename: String, pageCount: Int): OutputNameResolution =
        OutputNameResolver.resolve(basename, pageCount, listChildren(Uri.parse(treeUri)).map { it.displayName })

    /** Resolves a unique PDF-only name, following the editor's deterministic (1), (2) suffix rule. */
    fun resolveSinglePdfName(treeUri: String, basename: String): OutputNameResolution {
        return OutputNameResolver.resolveSinglePdfWithSuffix(
            basename,
            listChildren(Uri.parse(treeUri)).map { it.displayName },
        )
    }

    /**
     * Writes a committed output under the caller's frozen display name. Provider tuple data must
     * come from a persisted qualification record; this adapter does not qualify providers itself.
     */
    fun export(command: BeginExportAttemptCommand): Uri {
        val grant = storage.destinationGrant() ?: error("No SAF destination grant is selected")
        check(grant.treeUri == command.destinationTreeUri && grants.revalidate(grant, storage)) {
            "SAF destination grant is no longer readable and writable"
        }
        val output = storage.committedExportOutputs(command.exportArtifactId)
            .singleOrNull { it.artifactId == command.outputArtifactId }
            ?: error("Requested output is not a committed member of this export set")
        require(output.mimeType == command.mimeType) { "MIME type differs from committed output" }
        check(storage.verifyExportOutput(output)) { "Committed output failed private-file checksum verification" }
        val source = storage.exportOutputFile(output)
        check(source.isFile && source.length() == output.sizeBytes) { "Committed output is unavailable" }
        val tree = Uri.parse(command.destinationTreeUri)

        storage.beginExportAttempt(command)

        // A collision check is read-only, so a definite collision can close without side effects.
        val children = try {
            listChildren(tree)
        } catch (failure: Exception) {
            storage.finishWritingExportAttempt(
                command.exportAttemptId,
                ExportAttemptOutcome.FAILED_KNOWN,
                null,
                "Destination enumeration failed before createDocument: ${failure.javaClass.simpleName}",
                true,
                observation(access = "read-only destination enumeration failed before any create/write call"),
            )
            throw failure
        }
        val nameKey = normalizedName(command.plannedDisplayName)
        val collisions = children.filter { normalizedName(it.displayName) == nameKey }
        if (collisions.isNotEmpty()) {
            storage.finishWritingExportAttempt(
                command.exportAttemptId,
                ExportAttemptOutcome.FAILED_KNOWN,
                null,
                "A destination document already has this normalized name",
                true,
                observation(
                    existence = "pre-create collision count=${collisions.size}",
                    name = "requested=${command.plannedDisplayName}; matched=${collisions.joinToString { it.displayName }}",
                    access = "tree query completed",
                ),
            )
            error("Destination already contains ${command.plannedDisplayName}")
        }

        // Once createDocument is invoked, exceptions are ambiguous: leave WritingExternal so
        // startup recovery marks UnknownOutcome and never retries the side effect blindly.
        try {
            val created = DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                command.mimeType,
                command.plannedDisplayName,
            ) ?: error("Provider returned no document URI after createDocument")
            storage.exportCreatedBeforeStreamObserverForTest?.invoke(
                command.exportAttemptId.value,
                command.exportArtifactId.value,
                command.outputArtifactId.value,
                created.toString(),
            )
            resolver.openOutputStream(created, "w")?.use { destination ->
                val firstWriteObserver = storage.exportFirstProviderWriteBoundaryObserverForTest
                val buffer = ByteArray(if (firstWriteObserver == null) 8_192 else 256)
                source.inputStream().use { input ->
                    var firstWriteObserved = false
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        destination.write(buffer, 0, count)
                        if (!firstWriteObserved && firstWriteObserver != null) {
                            destination.flush()
                            firstWriteObserver.invoke(
                                command.exportAttemptId.value,
                                command.exportArtifactId.value,
                                command.outputArtifactId.value,
                                created.toString(),
                                count,
                                output.sizeBytes,
                                output.sha256,
                            )
                            firstWriteObserved = true
                        }
                    }
                }
            } ?: error("Provider returned no output stream")

            storage.exportStreamClosedBeforeReadBackObserverForTest?.invoke(
                command.exportAttemptId.value,
                command.exportArtifactId.value,
                command.outputArtifactId.value,
                created.toString(),
                output.sizeBytes,
                output.sha256,
            )

            val readBack = resolver.openInputStream(created)?.use(::digest)
                ?: error("Provider returned no read-back stream")
            check(readBack.sizeBytes == output.sizeBytes && readBack.sha256 == output.sha256) {
                "Provider read-back does not match committed output bytes"
            }
            val observedName = queryDisplayName(created) ?: command.plannedDisplayName
            if (normalizedName(observedName) != nameKey) {
                android.util.Log.w("SafExportGateway", "Provider assigned alternate name '$observedName' for requested '$nameKey'")
            }

            storage.exportReadBackBoundaryObserverForTest?.invoke(
                command.exportAttemptId.value,
                command.exportArtifactId.value,
                command.outputArtifactId.value,
                created.toString(),
                readBack.sha256,
            )

            storage.finishWritingExportAttempt(
                command.exportAttemptId,
                ExportAttemptOutcome.SUCCEEDED,
                created.toString(),
                null,
                false,
                observation(
                    identity = "documentUri=${created}",
                    existence = "document opened for read-back",
                    integrity = "size=${readBack.sizeBytes};sha256=${readBack.sha256}",
                    readability = "full stream read completed",
                    name = "displayName=$observedName",
                    access = "persisted tree read/write grant revalidated",
                ),
            )
            return created
        } catch (failure: Exception) {
            val current = runCatching { storage.exportAttempt(command.exportAttemptId) }.getOrNull()
            if (current?.state == "WRITING_EXTERNAL") {
                runCatching {
                    storage.markWritingExportAttemptUnknown(
                        command.exportAttemptId,
                        "Provider operation became ambiguous after createDocument was invoked: ${failure.javaClass.simpleName}",
                        observation(
                            existence = "create/write may have had a side effect",
                            integrity = "read-back did not complete with matching size and SHA-256",
                            access = "outcome is unresolved; no retry performed",
                        ),
                    )
                }
            }
            throw failure
        }
    }

    /** Resolves an interrupted write only from positive read-back evidence; otherwise it stays unknown. */
    fun revalidateUnknown(exportAttemptId: ExportAttemptId): ExportAttemptSnapshot {
        val attempt = storage.exportAttempt(exportAttemptId) ?: error("Export attempt does not exist")
        check(attempt.state == "UNKNOWN_OUTCOME") { "Only UnknownOutcome attempts can be revalidated" }
        val destination = JSONObject(attempt.destinationDescriptor)
        val parameters = JSONObject(attempt.frozenProviderParameters)
        val treeUri = destination.getString("treeUri")
        val plannedName = parameters.getString("plannedDisplayName")
        val expectedSize = parameters.getLong("sizeBytes")
        val expectedHash = parameters.getString("sha256")
        val tree = Uri.parse(treeUri)
        val grant = storage.destinationGrant()
        if (grant == null || grant.treeUri != treeUri || !grants.revalidate(grant, storage)) {
            storage.revalidateUnknownExportAttempt(
                exportAttemptId, ExportAttemptOutcome.UNKNOWN_OUTCOME, null, null, false,
                observation(access = "persisted destination grant unavailable during recovery"),
            )
            return storage.exportAttempt(exportAttemptId)!!
        }

        val matching = runCatching { listChildren(tree).filter { normalizedName(it.displayName) == normalizedName(plannedName) } }
            .getOrElse {
                storage.revalidateUnknownExportAttempt(
                    exportAttemptId, ExportAttemptOutcome.UNKNOWN_OUTCOME, null, null, false,
                    observation(access = "provider enumeration inconclusive: ${it.javaClass.simpleName}"),
                )
                return storage.exportAttempt(exportAttemptId)!!
            }
        if (matching.size == 1) {
            val child = matching.single()
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId)
            val readback = runCatching { resolver.openInputStream(uri)?.use(::digest) }.getOrNull()
            if (readback != null && readback.sizeBytes == expectedSize && readback.sha256 == expectedHash) {
                storage.revalidateUnknownExportAttempt(
                    exportAttemptId, ExportAttemptOutcome.SUCCEEDED, uri.toString(), null, false,
                    observation(
                        identity = "documentUri=$uri",
                        existence = "exactly one matching named document enumerated",
                        integrity = "size=${readback.sizeBytes};sha256=${readback.sha256}",
                        readability = "full stream read completed during recovery",
                        name = "displayName=${child.displayName}",
                        access = "persisted tree read/write grant revalidated",
                    ),
                )
            } else {
                storage.revalidateUnknownExportAttempt(
                    exportAttemptId, ExportAttemptOutcome.UNKNOWN_OUTCOME, null, null, false,
                    observation(
                        existence = "one matching name found",
                        integrity = "read-back missing or did not match expected size/hash",
                        name = "displayName=${child.displayName}",
                        access = "grant available; document integrity unresolved",
                    ),
                )
            }
        } else {
            storage.revalidateUnknownExportAttempt(
                exportAttemptId, ExportAttemptOutcome.UNKNOWN_OUTCOME, null, null, false,
                observation(
                    existence = "matching name count=${matching.size}; absence does not prove no prior side effect",
                    name = "requested=$plannedName",
                    access = "persisted tree read/write grant revalidated",
                ),
            )
        }
        return storage.exportAttempt(exportAttemptId)!!
    }

    private fun listChildren(tree: Uri): List<Child> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val result = mutableListOf<Child>()
        resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn) ?: continue
                result += Child(id, name)
            }
        } ?: error("Provider returned no child listing")
        return result
    }

    private fun queryDisplayName(uri: Uri): String? = resolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    }

    private fun normalizedName(value: String): String = OutputNameResolver.collisionKey(value)

    private fun digest(input: java.io.InputStream): Digest {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            md.update(buffer, 0, count)
        }
        return Digest(size, md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun observation(
        identity: String? = null,
        existence: String? = null,
        integrity: String? = null,
        readability: String? = null,
        name: String? = null,
        access: String? = null,
    ) = ProviderOperationObservationCommand(
        UUID.randomUUID().toString(), identity, existence, integrity, readability, name, access,
    )

    private data class Child(val documentId: String, val displayName: String)
    private data class Digest(val sizeBytes: Long, val sha256: String)
}
