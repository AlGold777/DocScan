package app.docscan.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.docscan.core.store.ProviderQualificationCommand
import app.docscan.core.store.ProviderQualificationSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class SafRuntimeChild(val uri: Uri, val documentId: String, val displayName: String)

internal interface SafRuntimeProviderIo {
    fun listChildren(treeUri: Uri): List<SafRuntimeChild>
    fun createDocument(treeUri: Uri, mimeType: String, displayName: String): Uri?
    fun openOutputStream(documentUri: Uri): OutputStream?
    fun openInputStream(documentUri: Uri): InputStream?
    fun deleteDocument(documentUri: Uri): Boolean
}

internal enum class SafRuntimeCheckBoundary {
    BEFORE_ENUMERATE,
    AFTER_ENUMERATE,
    BEFORE_FIRST_CREATE,
    AFTER_FIRST_CREATE_JOURNALED,
    BEFORE_FIRST_WRITE,
    AFTER_FIRST_CLOSE,
    BEFORE_FIRST_READ_BACK,
    AFTER_FIRST_READ_BACK,
    BEFORE_DUPLICATE_CREATE,
    AFTER_DUPLICATE_CREATE_JOURNALED,
    BEFORE_DUPLICATE_READ_BACK,
    AFTER_DUPLICATE_READ_BACK,
    BEFORE_DELETE,
    AFTER_DELETE,
    BEFORE_SUCCESS_EVIDENCE_PUBLICATION,
    AFTER_SUCCESS_EVIDENCE_PUBLICATION,
    BEFORE_JOURNAL_UNLINK,
    AFTER_JOURNAL_UNLINK_BEFORE_DIRECTORY_SYNC,
    AFTER_JOURNAL_DELETE_SYNC,
    BEFORE_READY_RECORD,
}

internal fun interface SafRuntimeCheckObserver {
    fun onBoundary(boundary: SafRuntimeCheckBoundary, runId: String)

    companion object {
        val NONE = SafRuntimeCheckObserver { _, _ -> }
    }
}

/** Performs and durably records a bounded, real-provider capability check for one exact tree. */
class SafRuntimeDestinationCheck internal constructor(
    context: Context,
    private val storage: ProductStorage,
    private val providerIo: SafRuntimeProviderIo,
    private val observer: SafRuntimeCheckObserver,
) {
    private val appContext = context.applicationContext
    private val evidenceDirectory = File(appContext.noBackupFilesDir, CHECK_DIRECTORY)
    private val grants = SafDestinationGrantManager(appContext)

    internal constructor(context: Context, storage: ProductStorage) : this(
        context,
        storage,
        AndroidSafRuntimeProviderIo(context.applicationContext.contentResolver),
        SafRuntimeCheckObserver.NONE,
    )

    /** Returns a supported record for this provider tuple and, for runtime evidence, this exact tree. */
    fun findReady(treeUri: String): ProviderQualificationSnapshot? {
        val identity = SafProviderIdentity.resolve(appContext, treeUri)
        val candidates = storage.providerQualificationsFor(
            identity.adapterId,
            identity.providerVersion,
            identity.osVersion,
            identity.accessClass,
        )
        return candidates.firstOrNull { isReadyRecord(it, identity, treeUri) }
    }

    /** Blocking I/O: call from a worker thread. Never records readiness unless every check and cleanup passes. */
    fun ensureReady(treeUri: String): ProviderQualificationSnapshot = synchronized(PROCESS_CHECK_LOCK) {
        ensureReadySerialized(treeUri)
    }

    private fun ensureReadySerialized(treeUri: String): ProviderQualificationSnapshot {
        check(Thread.currentThread() !== mainThread) { "SAF capability checks must run off the main thread" }
        val identity = SafProviderIdentity.resolve(appContext, treeUri)
        val grant = storage.destinationGrant() ?: error("Choose a save folder before checking it")
        check(grant.treeUri == treeUri && grants.revalidate(grant, storage)) {
            "The selected folder no longer has persisted read/write access"
        }
        findReady(treeUri)?.let { return it }
        resolvePendingChecks(treeUri, identity)

        val runId = UUID.randomUUID().toString()
        val plannedName = "docscan-runtime-check-$runId.txt"
        var journal = CheckJournal(
            runId = runId,
            treeUri = treeUri,
            identity = identity,
            plannedName = plannedName,
            phase = "PREPARED",
            baselineIds = emptySet(),
            baselineNames = emptySet(),
            ownedUris = emptyList(),
            originalUri = null,
            duplicateUri = null,
        )
        writeJournal(journal)
        try {
            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_ENUMERATE, runId)
            journal = durablePhase(journal, "BASELINE_ENUMERATION_PENDING")
            val baseline = providerIo.listChildren(Uri.parse(treeUri))
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_ENUMERATE, runId)
            check(baseline.none { it.displayName == plannedName }) {
                "A random capability-check name already exists; choose the folder again"
            }
            journal = journal.copy(
                phase = "ENUMERATED",
                baselineIds = baseline.map { it.documentId }.toSet(),
                baselineNames = baseline.map { it.displayName }.toSet(),
            )
            writeJournal(journal)

            val payload = deterministicPayload()
            val expectedHash = sha256(payload)
            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_FIRST_CREATE, runId)
            journal = durablePhase(journal, "FIRST_CREATE_PENDING")
            val firstUri = try {
                providerIo.createDocument(Uri.parse(treeUri), MIME_TEXT, plannedName)
                    ?: error("Provider returned no URI for the capability-check document")
            } catch (interruption: SafRuntimeCheckInterruption) {
                journal = journal.copy(
                    phase = "PENDING_UNKNOWN",
                    interruptedAt = journal.phase,
                    lastError = interruption.message,
                )
                writeJournal(journal)
                throw interruption
            } catch (createFailure: Throwable) {
                resolveCreateFailure(journal, createFailure)
                throw createFailure
            }
            journal = journal.copy(phase = "FIRST_CREATE_RETURNED", originalUri = firstUri.toString())
            writeJournal(journal)

            val firstChild = identifyOwnedChild(journal, firstUri)
            if (firstChild != null) {
                journal = journal.copy(
                    phase = "FIRST_CHILD_OWNED",
                    ownedUris = journal.ownedUris + firstUri.toString(),
                )
                writeJournal(journal)
            }
            check(firstChild != null) {
                "Provider did not return a distinct child created by this check; no write was attempted"
            }
            check(firstChild.displayName == plannedName) {
                "Provider changed the requested check-document name; no bytes were written"
            }
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_FIRST_CREATE_JOURNALED, runId)

            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_FIRST_WRITE, runId)
            journal = durablePhase(journal, "FIRST_WRITE_PENDING")
            providerIo.openOutputStream(firstUri)?.use { stream ->
                stream.write(payload)
                stream.flush()
            } ?: error("Provider returned no output stream for the capability check")
            journal = journal.copy(phase = "FIRST_CLOSED")
            writeJournal(journal)
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_FIRST_CLOSE, runId)

            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_FIRST_READ_BACK, runId)
            journal = durablePhase(journal, "FIRST_READ_BACK_PENDING")
            val firstReadBack = readBounded(firstUri)
            check(firstReadBack.size == payload.size && sha256(firstReadBack) == expectedHash) {
                "Provider read-back did not match the bounded capability-check payload"
            }
            journal = journal.copy(phase = "FIRST_READ_BACK_VERIFIED", firstSha256 = expectedHash)
            writeJournal(journal)
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_FIRST_READ_BACK, runId)

            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_DUPLICATE_CREATE, runId)
            journal = durablePhase(journal, "DUPLICATE_CREATE_PENDING")
            var definiteConflict = false
            val duplicateUri = try {
                providerIo.createDocument(Uri.parse(treeUri), MIME_TEXT, plannedName)
                    ?: throw SafRuntimeCheckUnknownOutcome("Duplicate create returned no document identity")
            } catch (unknown: SafRuntimeCheckUnknownOutcome) {
                throw unknown
            } catch (duplicateFailure: Throwable) {
                journal = durablePhase(journal, "DUPLICATE_FAILURE_ENUMERATION_PENDING")
                val afterFailure = providerIo.listChildren(Uri.parse(treeUri))
                if (afterFailure.map { it.documentId }.toSet() != journal.baselineIds + firstChild.documentId) {
                    journal = journal.copy(
                        phase = "PENDING_UNKNOWN",
                        interruptedAt = "DUPLICATE_CREATE_PENDING",
                        lastError = duplicateFailure.message ?: "Duplicate create changed child identities",
                    )
                    writeJournal(journal)
                    throw SafRuntimeCheckUnknownOutcome("Duplicate create may have had an unreturned side effect", duplicateFailure)
                }
                check(isDefiniteExistingNameFailure(duplicateFailure)) {
                    "Duplicate create did not prove a no-side-effect name conflict"
                }
                definiteConflict = true
                journal = durablePhase(journal, "DUPLICATE_CONFLICT_PROVED")
                null
            }
            if (duplicateUri != null) {
                journal = journal.copy(phase = "DUPLICATE_CREATE_RETURNED", duplicateUri = duplicateUri.toString())
                writeJournal(journal)
                val duplicateChild = identifyOwnedChild(journal, duplicateUri)
                if (duplicateChild != null) {
                    journal = journal.copy(
                        phase = "DUPLICATE_CHILD_OWNED",
                        ownedUris = journal.ownedUris + duplicateUri.toString(),
                    )
                    writeJournal(journal)
                }
                check(duplicateChild != null && duplicateChild.documentId != firstChild.documentId) {
                    "Duplicate create returned an existing identity; the original was not written again"
                }
                observer.onBoundary(SafRuntimeCheckBoundary.AFTER_DUPLICATE_CREATE_JOURNALED, runId)
            }
            check(duplicateUri != null || definiteConflict) {
                "Duplicate create did not return a distinct identity or a definite no-side-effect name conflict"
            }

            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_DUPLICATE_READ_BACK, runId)
            journal = durablePhase(journal, "DUPLICATE_READ_BACK_PENDING")
            val originalAfterDuplicate = readBounded(firstUri)
            check(originalAfterDuplicate.size == payload.size && sha256(originalAfterDuplicate) == expectedHash) {
                "Duplicate create changed the original capability-check document"
            }
            journal = durablePhase(journal, "FINAL_ENUMERATION_PENDING")
            val finalChildren = providerIo.listChildren(Uri.parse(treeUri))
            check(finalChildren.any { it.documentId == firstChild.documentId && it.displayName == plannedName }) {
                "Final child listing did not contain the original capability-check document"
            }
            if (duplicateUri != null) {
                val duplicateId = DocumentsContract.getDocumentId(duplicateUri)
                check(finalChildren.any { it.documentId == duplicateId }) {
                    "Final child listing did not contain the distinct duplicate-create result"
                }
            } else {
                check(finalChildren.map { it.documentId }.toSet() == journal.baselineIds + firstChild.documentId) {
                    "Final child listing changed unexpectedly after a rejected duplicate create"
                }
            }
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_DUPLICATE_READ_BACK, runId)

            journal = journal.copy(phase = "CHECKS_PASSED")
            writeJournal(journal)
            cleanupOwned(journal)
            journal = journal.copy(phase = "CLEANED")
            writeJournal(journal)

            val evidence = JSONObject()
                .put("schema", EVIDENCE_SCHEMA)
                .put("runId", runId)
                .put("provenance", RUNTIME_PROVENANCE)
                .put("formalQualification", false)
                .put("treeUri", treeUri)
                .put("providerPackage", identity.providerPackage)
                .put("adapterId", identity.adapterId)
                .put("providerVersion", identity.providerVersion)
                .put("osVersion", identity.osVersion)
                .put("accessClass", identity.accessClass)
                .put("validationSuiteVersion", SUITE_VERSION)
                .put("plannedName", plannedName)
                .put("baselineChildIds", JSONArray(journal.baselineIds.sorted()))
                .put("payloadByteCount", payload.size)
                .put("payloadSha256", expectedHash)
                .put("firstDocumentId", firstChild.documentId)
                .put("firstDocumentName", firstChild.displayName)
                .put("duplicateOutcome", if (duplicateUri == null) "DEFINITE_NAME_CONFLICT" else "DISTINCT_IDENTITY")
                .put("originalHashAfterDuplicate", sha256(originalAfterDuplicate))
                .put("finalListingVerified", true)
                .put("ownedCleanupVerified", true)
                .put("capabilities", JSONObject()
                    .put("createDoesNotOverwriteExistingDocument", true)
                    .put("enumerateChildren", true)
                    .put("readBackSha256", true))
            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_SUCCESS_EVIDENCE_PUBLICATION, runId)
            val evidenceFile = writeEvidence(runId, evidence)
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_SUCCESS_EVIDENCE_PUBLICATION, runId)
            val evidenceHash = sha256(evidenceFile.readBytes())
            val capabilities = JSONObject()
                .put("provenance", RUNTIME_PROVENANCE)
                .put("formalQualification", false)
                .put("treeUri", treeUri)
                .put("suiteVersion", SUITE_VERSION)
                .put("evidencePath", evidenceFile.absolutePath)
                .put("evidenceSha256", evidenceHash)
                .put("capabilities", evidence.getJSONObject("capabilities"))
            // No provider or journal I/O may fail after operational readiness is committed.
            deleteJournal(runId)
            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_READY_RECORD, runId)
            val command = ProviderQualificationCommand(
                qualificationId = "runtime-$runId",
                adapterId = identity.adapterId,
                providerVersion = identity.providerVersion,
                osVersion = identity.osVersion,
                destinationAccessClass = identity.accessClass,
                validationSuiteVersion = SUITE_VERSION,
                qualifiedCapabilitiesPayload = capabilities.toString(),
            )
            return storage.recordRuntimeProviderAssessment(command)
        } catch (interruption: SafRuntimeCheckInterruption) {
            journal = journal.copy(
                phase = "PENDING_UNKNOWN",
                interruptedAt = journal.phase,
                lastError = interruption.message,
            )
            writeJournal(journal)
            throw interruption
        } catch (unknown: SafRuntimeCheckUnknownOutcome) {
            journal = journal.copy(
                phase = "PENDING_UNKNOWN",
                interruptedAt = journal.interruptedAt ?: journal.phase,
                lastError = unknown.message,
            )
            writeJournal(journal)
            writeFailureEvidence(journal, unknown)
            throw unknown
        } catch (caught: Throwable) {
            runCatching { safeResolveAndCleanCurrent(journal) }
                .onFailure { caught.addSuppressed(it) }
            writeFailureEvidence(journal, caught)
            throw caught
        }
    }

    private fun isReadyRecord(
        record: ProviderQualificationSnapshot,
        identity: SafProviderIdentity,
        treeUri: String,
    ): Boolean = runCatching {
        if (!hasThreeCapabilities(JSONObject(record.qualifiedCapabilitiesPayload))) return false
        val payload = JSONObject(record.qualifiedCapabilitiesPayload)
        if (payload.optString("provenance") != RUNTIME_PROVENANCE) {
            return !payload.has("fixtureOnly")
                && (!payload.has("formalQualification") || payload.optBoolean("formalQualification"))
        }
        if (payload.optBoolean("formalQualification", true)) return false
        if (payload.optString("treeUri") != treeUri || payload.optString("suiteVersion") != SUITE_VERSION) return false
        if (record.validationSuiteVersion != SUITE_VERSION) return false
        val evidenceFile = evidenceFileFromPayload(payload) ?: return false
        val expectedHash = payload.optString("evidenceSha256")
        if (!expectedHash.matches(Regex("[0-9a-f]{64}")) || !evidenceFile.isFile) return false
        if (sha256(evidenceFile.readBytes()) != expectedHash) return false
        val sidecar = File(evidenceDirectory, evidenceFile.nameWithoutExtension + ".sha256")
        if (!sidecar.isFile || sidecar.readText().trim() != expectedHash) return false
        val evidence = JSONObject(evidenceFile.readText())
        evidence.optString("provenance") == RUNTIME_PROVENANCE
            && !evidence.optBoolean("formalQualification", true)
            && evidence.optString("runId").isNotBlank()
            && evidence.optString("treeUri") == treeUri
            && evidence.optString("providerPackage") == identity.providerPackage
            && evidence.optString("adapterId") == identity.adapterId
            && evidence.optString("providerVersion") == identity.providerVersion
            && evidence.optString("osVersion") == identity.osVersion
            && evidence.optString("accessClass") == identity.accessClass
            && evidence.optString("validationSuiteVersion") == SUITE_VERSION
            && hasThreeCapabilities(evidence.optJSONObject("capabilities") ?: JSONObject())
            && evidence.optBoolean("ownedCleanupVerified", false)
    }.getOrDefault(false)

    private fun resolvePendingChecks(treeUri: String, identity: SafProviderIdentity) {
        if (!evidenceDirectory.exists()) return
        val pendingFiles = evidenceDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("pending-") && it.name.endsWith(".json") }
            .orEmpty()
        pendingFiles.forEach { file ->
            val journal = readJournal(file)
            if (journal.treeUri != treeUri) return@forEach
            check(journal.identity == identity) {
                "A previous capability check belongs to a different provider build; choose another folder or resolve its pending document manually"
            }
            val plannedPresent = listChildren(journal, "PENDING_RESOLUTION_ENUMERATION_PENDING").any {
                it.displayName == journal.plannedName
            }
            val duplicateCreateUnresolved = journal.phase in setOf(
                "FIRST_CREATE_PENDING",
                "DUPLICATE_CREATE_PENDING",
                "DUPLICATE_FAILURE_ENUMERATION_PENDING",
            ) || (journal.phase == "PENDING_UNKNOWN" && journal.interruptedAt in setOf(
                    "FIRST_CREATE_PENDING",
                    "DUPLICATE_CREATE_PENDING",
                    "DUPLICATE_FAILURE_ENUMERATION_PENDING",
                    "DUPLICATE_CREATE_RETURNED",
                ))
            val unownedReturnedDuplicate = journal.duplicateUri != null && journal.duplicateUri !in journal.ownedUris
            if (plannedPresent && (duplicateCreateUnresolved || unownedReturnedDuplicate)
            ) {
                error("A previous capability check has an unresolved provider document; choose another folder or remove it manually")
            }
            if (journal.phase == "CLEANUP_REQUIRED" || journal.ownedUris.isNotEmpty()) {
                cleanupOwned(journal)
                deleteJournal(journal.runId)
            } else if (plannedPresent) {
                error("A previous capability check may have created a document; choose another folder or remove it manually")
            } else {
                writeFailureEvidence(journal, IOException("Interrupted before any provider child was observed"))
                deleteJournal(journal.runId)
            }
        }
    }

    private fun resolveCreateFailure(journal: CheckJournal, failure: Throwable) {
        val children = runCatching { listChildren(journal, "CREATE_FAILURE_ENUMERATION_PENDING") }.getOrElse {
            val unknown = journal.copy(
                phase = "PENDING_UNKNOWN",
                interruptedAt = journal.phase,
                lastError = failure.message,
            )
            writeJournal(unknown)
            throw IOException("Provider create outcome is unknown; no retry was attempted", it)
        }
        if (children.any { it.displayName == journal.plannedName }) {
            writeJournal(journal.copy(phase = "PENDING_UNKNOWN", interruptedAt = journal.phase, lastError = failure.message))
            throw IOException("Provider create outcome is unknown; choose another folder or resolve the pending document manually", failure)
        }
        writeFailureEvidence(journal, failure)
        deleteJournal(journal.runId)
    }

    private fun safeResolveAndCleanCurrent(journal: CheckJournal) {
        var current = journal
        val duplicateUri = current.duplicateUri?.let(Uri::parse)
        if (duplicateUri != null && duplicateUri.toString() !in current.ownedUris) {
            val duplicateId = runCatching { DocumentsContract.getDocumentId(duplicateUri) }.getOrNull()
            val originalId = current.originalUri?.let(Uri::parse)
                ?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() }
            if (duplicateId != null && duplicateId != originalId && duplicateId !in current.baselineIds) {
                val listed = runCatching {
                    listChildren(current, "RESOLVE_DUPLICATE_RETURN_PENDING")
                        .singleOrNull { it.documentId == duplicateId }
                }.getOrElse { failure ->
                    writeJournal(current.copy(
                        phase = "PENDING_UNKNOWN",
                        interruptedAt = current.phase,
                        lastError = "Could not identify returned duplicate: ${failure.message}",
                    ))
                    throw IOException("Duplicate-create identity is unresolved; no cleanup by name was attempted", failure)
                }
                if (listed != null) {
                    current = current.copy(ownedUris = current.ownedUris + duplicateUri.toString())
                    writeJournal(current)
                } else {
                    if (current.ownedUris.isNotEmpty()) cleanupOwned(current)
                    writeJournal(current.copy(
                        phase = "PENDING_UNKNOWN",
                        interruptedAt = current.phase,
                        lastError = "Returned duplicate identity was not independently listed",
                    ))
                    throw IOException("Duplicate-create result is not independently identifiable; choose another folder or resolve it manually")
                }
            }
        }
        if (current.ownedUris.isNotEmpty()) {
            cleanupOwned(current)
            deleteJournal(current.runId)
            return
        }
        val children = listChildren(current, "FAILURE_RESOLUTION_ENUMERATION_PENDING")
        if (children.none { it.displayName == current.plannedName }) {
            val returned = current.originalUri?.let(Uri::parse)
            val returnedId = returned?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() }
            if (returnedId != null && returnedId !in current.baselineIds
                && returnedId != DocumentsContract.getTreeDocumentId(Uri.parse(current.treeUri))) {
                writeJournal(current.copy(phase = "PENDING_UNKNOWN", interruptedAt = current.phase, lastError = "Returned URI was not independently identified as an owned child"))
                throw IOException("Provider returned an unverified new identity; no cleanup or readiness was claimed")
            }
            deleteJournal(current.runId)
        } else {
            writeJournal(current.copy(phase = "PENDING_UNKNOWN", interruptedAt = current.phase, lastError = current.lastError ?: "Unowned planned name is present"))
            throw IOException("Provider outcome is unknown; no cleanup by name was attempted")
        }
    }

    private fun identifyOwnedChild(journal: CheckJournal, uri: Uri): SafRuntimeChild? {
        val tree = Uri.parse(journal.treeUri)
        if (!isDocumentInTree(uri, tree)) return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (id == DocumentsContract.getTreeDocumentId(tree) || id in journal.baselineIds) return null
        return listChildren(journal, "VERIFY_RETURNED_CHILD_PENDING").singleOrNull { it.documentId == id }
    }

    private fun cleanupOwned(initial: CheckJournal) {
        var journal = initial.copy(phase = "CLEANUP_REQUIRED")
        writeJournal(journal)
        journal.ownedUris.distinct().forEach { rawUri ->
            val uri = Uri.parse(rawUri)
            val tree = Uri.parse(journal.treeUri)
            check(isDocumentInTree(uri, tree)) {
                "Owned provider URI is outside the selected document tree; cleanup is unresolved"
            }
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: error("Owned provider URI is not a document URI; cleanup is unresolved")
            check(id != DocumentsContract.getTreeDocumentId(tree)) {
                "Refusing to delete the selected folder itself"
            }
            val listedBefore = listChildren(journal, "OWNED_CLEANUP_ENUMERATION_PENDING").any { it.documentId == id }
            if (!listedBefore) return@forEach
            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_DELETE, journal.runId)
            journal = durablePhase(journal, "OWNED_DELETE_PENDING")
            check(providerIo.deleteDocument(uri)) { "Provider refused cleanup of a document created by this check" }
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_DELETE, journal.runId)
            check(listChildren(journal, "VERIFY_OWNED_DELETE_PENDING").none { it.documentId == id }) {
                "Provider cleanup did not remove the document created by this check"
            }
            journal = journal.copy(phase = "CLEANUP_REQUIRED")
            writeJournal(journal)
        }
    }

    private fun isDocumentInTree(documentUri: Uri, treeUri: Uri): Boolean =
        documentUri.scheme == "content"
            && documentUri.authority == treeUri.authority
            && DocumentsContract.isDocumentUri(appContext, documentUri)
            && runCatching {
                DocumentsContract.getTreeDocumentId(documentUri) == DocumentsContract.getTreeDocumentId(treeUri)
            }.getOrDefault(false)

    private fun readBounded(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        providerIo.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                check(output.size() + count <= MAX_READ_BACK_BYTES) { "Provider read-back exceeded the probe bound" }
                output.write(buffer, 0, count)
            }
        } ?: error("Provider returned no read-back stream")
        return output.toByteArray()
    }

    private fun durablePhase(journal: CheckJournal, phase: String): CheckJournal =
        journal.copy(phase = phase).also(::writeJournal)

    private fun listChildren(journal: CheckJournal, phase: String): List<SafRuntimeChild> {
        durablePhase(journal, phase)
        return providerIo.listChildren(Uri.parse(journal.treeUri))
    }

    private fun writeEvidence(runId: String, evidence: JSONObject): File {
        evidenceDirectory.mkdirs()
        val evidenceFile = File(evidenceDirectory, "evidence-$runId.json")
        writeAtomic(evidenceFile, evidence.toString().toByteArray(Charsets.UTF_8))
        val hashFile = File(evidenceDirectory, "evidence-$runId.sha256")
        writeAtomic(hashFile, (sha256(evidenceFile.readBytes()) + "\n").toByteArray(Charsets.UTF_8))
        return evidenceFile
    }

    private fun writeFailureEvidence(journal: CheckJournal, failure: Throwable) {
        val evidence = JSONObject()
            .put("schema", EVIDENCE_SCHEMA)
            .put("runId", journal.runId)
            .put("provenance", RUNTIME_PROVENANCE)
            .put("formalQualification", false)
            .put("treeUri", journal.treeUri)
            .put("adapterId", journal.identity.adapterId)
            .put("providerVersion", journal.identity.providerVersion)
            .put("osVersion", journal.identity.osVersion)
            .put("accessClass", journal.identity.accessClass)
            .put("validationSuiteVersion", SUITE_VERSION)
            .put("phase", journal.phase)
            .put("plannedName", journal.plannedName)
            .put("ownedUris", JSONArray(journal.ownedUris))
            .put("failure", failure.javaClass.name + ": " + (failure.message ?: ""))
        runCatching { writeEvidence("failed-${journal.runId}", evidence) }
    }

    private fun writeJournal(journal: CheckJournal) {
        evidenceDirectory.mkdirs()
        val json = JSONObject()
            .put("schema", JOURNAL_SCHEMA)
            .put("runId", journal.runId)
            .put("treeUri", journal.treeUri)
            .put("providerPackage", journal.identity.providerPackage)
            .put("adapterId", journal.identity.adapterId)
            .put("providerVersion", journal.identity.providerVersion)
            .put("osVersion", journal.identity.osVersion)
            .put("accessClass", journal.identity.accessClass)
            .put("plannedName", journal.plannedName)
            .put("phase", journal.phase)
            .put("baselineIds", JSONArray(journal.baselineIds.sorted()))
            .put("baselineNames", JSONArray(journal.baselineNames.sorted()))
            .put("ownedUris", JSONArray(journal.ownedUris))
            .put("originalUri", journal.originalUri)
            .put("duplicateUri", journal.duplicateUri)
            .put("firstSha256", journal.firstSha256)
            .put("lastError", journal.lastError)
            .put("interruptedAt", journal.interruptedAt)
        writeAtomic(journalFile(journal.runId), json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readJournal(file: File): CheckJournal {
        val json = JSONObject(file.readText())
        check(json.optInt("schema") == JOURNAL_SCHEMA) { "Unrecognized pending runtime capability journal: ${file.name}" }
        return CheckJournal(
            runId = json.getString("runId"),
            treeUri = json.getString("treeUri"),
            identity = SafProviderIdentity(
                providerPackage = json.getString("providerPackage"),
                adapterId = json.getString("adapterId"),
                providerVersion = json.getString("providerVersion"),
                osVersion = json.getString("osVersion"),
                accessClass = json.getString("accessClass"),
            ),
            plannedName = json.getString("plannedName"),
            phase = json.getString("phase"),
            baselineIds = json.getJSONArray("baselineIds").toStringSet(),
            baselineNames = json.getJSONArray("baselineNames").toStringSet(),
            ownedUris = json.getJSONArray("ownedUris").toStringList(),
            originalUri = json.optString("originalUri").takeIf { it.isNotEmpty() },
            duplicateUri = json.optString("duplicateUri").takeIf { it.isNotEmpty() },
            firstSha256 = json.optString("firstSha256").takeIf { it.isNotEmpty() },
            lastError = json.optString("lastError").takeIf { it.isNotEmpty() },
            interruptedAt = json.optString("interruptedAt").takeIf { it.isNotEmpty() },
        )
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not atomically persist ${target.name}")
        }
        AndroidDirectorySync().sync(requireNotNull(target.parentFile).toPath())
    }

    private fun deleteJournal(runId: String) {
        val file = journalFile(runId)
        if (file.exists()) {
            observer.onBoundary(SafRuntimeCheckBoundary.BEFORE_JOURNAL_UNLINK, runId)
            if (!file.delete()) throw IOException("Could not clear the completed capability-check journal")
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_JOURNAL_UNLINK_BEFORE_DIRECTORY_SYNC, runId)
            AndroidDirectorySync().sync(evidenceDirectory.toPath())
            observer.onBoundary(SafRuntimeCheckBoundary.AFTER_JOURNAL_DELETE_SYNC, runId)
        }
    }

    private fun journalFile(runId: String) = File(evidenceDirectory, "pending-$runId.json")

    private fun evidenceFileFromPayload(payload: JSONObject): File? {
        val raw = payload.optString("evidencePath").takeIf { it.isNotEmpty() } ?: return null
        val candidate = File(raw).canonicalFile
        val root = evidenceDirectory.canonicalFile
        if (candidate.parentFile != root || !candidate.name.startsWith("evidence-")) return null
        return candidate
    }

    private fun hasThreeCapabilities(payload: JSONObject): Boolean {
        val capabilities = payload.optJSONObject("capabilities") ?: payload
        return capabilities.optBoolean("createDoesNotOverwriteExistingDocument", false)
            && capabilities.optBoolean("enumerateChildren", false)
            && capabilities.optBoolean("readBackSha256", false)
    }

    private fun isDefiniteExistingNameFailure(failure: Throwable): Boolean {
        val text = generateSequence(failure) { it.cause }
            .joinToString(" ") { "${it.javaClass.simpleName} ${it.message.orEmpty()}" }
            .lowercase()
        return listOf("filealreadyexists", "eexist", "already exists", "already exist", "duplicate", "name conflict")
            .any(text::contains)
    }

    private fun deterministicPayload(): ByteArray = ByteArray(PAYLOAD_BYTES) { index ->
        ((index * 31 + index / 251 + 17) and 0xff).toByte()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private data class CheckJournal(
        val runId: String,
        val treeUri: String,
        val identity: SafProviderIdentity,
        val plannedName: String,
        val phase: String,
        val baselineIds: Set<String>,
        val baselineNames: Set<String>,
        val ownedUris: List<String>,
        val originalUri: String?,
        val duplicateUri: String?,
        val firstSha256: String? = null,
        val lastError: String? = null,
        val interruptedAt: String? = null,
    )

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
    private fun JSONArray.toStringSet(): Set<String> = toStringList().toSet()

    private class AndroidSafRuntimeProviderIo(private val resolver: ContentResolver) : SafRuntimeProviderIo {
        override fun listChildren(treeUri: Uri): List<SafRuntimeChild> {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)
            val result = mutableListOf<SafRuntimeChild>()
            resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn) ?: continue
                    result += SafRuntimeChild(
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                        id,
                        cursor.getString(nameColumn).orEmpty(),
                    )
                }
            } ?: throw IOException("Provider did not return a child listing")
            return result
        }

        override fun createDocument(treeUri: Uri, mimeType: String, displayName: String): Uri? {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            return DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
        }

        override fun openOutputStream(documentUri: Uri): OutputStream? = resolver.openOutputStream(documentUri, "w")

        override fun openInputStream(documentUri: Uri): InputStream? = resolver.openInputStream(documentUri)

        override fun deleteDocument(documentUri: Uri): Boolean = DocumentsContract.deleteDocument(resolver, documentUri)
    }

    companion object {
        internal const val CHECK_DIRECTORY = "saf-runtime-destination-checks"
        internal const val SUITE_VERSION = "RUNTIME_CAPABILITY_CHECK_V1"
        private const val RUNTIME_PROVENANCE = "RUNTIME_CAPABILITY_CHECK"
        private const val EVIDENCE_SCHEMA = 1
        private const val JOURNAL_SCHEMA = 1
        private const val MIME_TEXT = "text/plain"
        private const val PAYLOAD_BYTES = 64 * 1024
        private const val MAX_READ_BACK_BYTES = PAYLOAD_BYTES + 1
        private val mainThread = android.os.Looper.getMainLooper().thread
        private val PROCESS_CHECK_LOCK = Any()
    }
}

/** Test-only process interruption marker; deliberately bypasses normal failure cleanup. */
internal class SafRuntimeCheckInterruption(message: String) : Error(message)

/** A provider effect may have occurred without returning enough identity evidence to clean it safely. */
internal class SafRuntimeCheckUnknownOutcome(message: String, cause: Throwable? = null) : IOException(message, cause)
