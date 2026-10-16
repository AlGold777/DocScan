package app.docscan.core.cleanup

import app.docscan.core.artifact.ArtifactFileObservation
import app.docscan.core.artifact.FileArtifactRepository
import app.docscan.core.artifact.VerifiedDeleteResult
import app.docscan.core.store.ArtifactDeletionClaim
import app.docscan.core.store.StateStore

/** Serializes cleanup through durable reference checks, claim tokens and verified file deletion. */
class CleanupArbiter(
    private val store: StateStore,
    private val artifacts: FileArtifactRepository,
) {
    /** Reconciles existing claims first, then processes at most [limit] new artifacts. */
    fun runBatch(limit: Long = DEFAULT_BATCH_SIZE): CleanupBatchResult {
        require(limit in 1L..MAX_BATCH_SIZE)
        return artifacts.withExclusiveCleanupLock {
            val recovered = recoverInterruptedDeletionsLocked(limit)
            val claims = store.claimUnreferencedArtifacts(limit)
            var deleted = 0
            var quarantined = 0
            claims.forEach { claim ->
                when (artifacts.deleteVerified(claim.relativePath, claim.sizeBytes, claim.sha256.orEmpty())) {
                    VerifiedDeleteResult.DELETED, VerifiedDeleteResult.MISSING -> {
                        store.finalizeArtifactDeletion(claim)
                        deleted++
                    }
                    VerifiedDeleteResult.INVALID -> {
                        store.quarantineClaimedArtifact(claim)
                        quarantined++
                    }
                }
            }
            CleanupBatchResult(recovered, claims.size, deleted, quarantined)
        }
    }

    /**
     * A valid file means the prior worker died before unlink; retry may be scheduled later.
     * A missing file means unlink completed; corrupt/unsafe paths are quarantined without unlink.
     */
    fun recoverInterruptedDeletions(limit: Long = DEFAULT_BATCH_SIZE): Int {
        require(limit in 1L..MAX_BATCH_SIZE)
        return artifacts.withExclusiveCleanupLock { recoverInterruptedDeletionsLocked(limit) }
    }

    private fun recoverInterruptedDeletionsLocked(limit: Long): Int {
        val claims: List<ArtifactDeletionClaim> = store.claimedArtifactDeletions(limit)
        claims.forEach { claim ->
            when (artifacts.inspect(claim.relativePath, claim.sizeBytes, claim.sha256)) {
                ArtifactFileObservation.VERIFIED_PRESENT -> store.releaseArtifactDeletionClaim(claim)
                ArtifactFileObservation.MISSING -> store.finalizeArtifactDeletion(claim)
                ArtifactFileObservation.INVALID -> store.quarantineClaimedArtifact(claim)
            }
        }
        return claims.size
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 32L
        const val MAX_BATCH_SIZE = 100L
    }
}

data class CleanupBatchResult(
    val recoveredClaims: Int,
    val claimedArtifacts: Int,
    val deletedArtifacts: Int,
    val quarantinedArtifacts: Int,
)
