package app.docscan.core.artifact

import app.docscan.core.model.ArtifactId
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FileArtifactRepositoryTest {
    @Test
    fun publicationReturnsChecksumOnlyAfterFileAndDirectoriesAreSynced() {
        val root = Files.createTempDirectory("docscan-artifacts")
        val payload = "jpeg-bytes".toByteArray()
        val synced = mutableListOf<java.nio.file.Path>()
        val repository = FileArtifactRepository(root, DirectorySync { synced.add(it) }, "test-epoch")

        val artifact = repository.publishJpeg(ByteArrayInputStream(payload))
        val published = root.resolve(artifact.relativePath)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

        assertTrue(Files.isRegularFile(published))
        assertEquals(payload.size.toLong(), artifact.sizeBytes)
        assertEquals(expectedHash, artifact.sha256)
        assertTrue(repository.verifyPublished(artifact.relativePath, artifact.sizeBytes, artifact.sha256))
        assertFalse(repository.verifyPublished(artifact.relativePath, artifact.sizeBytes + 1, artifact.sha256))
        Files.write(published, payload + byteArrayOf(0))
        assertFalse(repository.verifyPublished(artifact.relativePath, artifact.sizeBytes, artifact.sha256))
        assertFalse(synced.isEmpty())
        assertTrue(synced.contains(root.toRealPath()))
    }

    @Test
    fun duplicateArtifactIdentityDoesNotOverwritePublishedBytes() {
        val root = Files.createTempDirectory("docscan-duplicate-artifact")
        val repository = FileArtifactRepository(root, DirectorySync {}, "test-epoch")
        val id = ArtifactId.new()
        val first = repository.publishJpeg(ByteArrayInputStream(byteArrayOf(1, 2, 3)), id)
        val target = root.resolve(first.relativePath)

        assertThrows(IllegalStateException::class.java) {
            repository.publishJpeg(ByteArrayInputStream(byteArrayOf(9, 9, 9)), id)
        }
        assertTrue(Files.readAllBytes(target).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun concurrentDuplicateIdentityPublishesExactlyOneImmutableObject() {
        val root = Files.createTempDirectory("docscan-concurrent-duplicate")
        val repository = FileArtifactRepository(root, DirectorySync {}, "test-epoch")
        val id = ArtifactId.new()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(byteArrayOf(1, 2, 3), byteArrayOf(9, 8, 7)).map { bytes ->
                executor.submit<Boolean> {
                    start.await()
                    try {
                        repository.publishJpeg(ByteArrayInputStream(bytes), id)
                        true
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        false
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
            }
            start.countDown()
            val outcomes = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it })
            val target = root.resolve("objects/${id.value.take(2)}/${id.value}.jpg")
            val stored = Files.readAllBytes(target)
            assertTrue(stored.contentEquals(byteArrayOf(1, 2, 3)) || stored.contentEquals(byteArrayOf(9, 8, 7)))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun emptyBytesAndDirectorySyncFailureDoNotReturnDurableMetadata() {
        val emptyRoot = Files.createTempDirectory("docscan-empty")
        val emptyRepository = FileArtifactRepository(emptyRoot, DirectorySync {}, "test-epoch")
        assertThrows(IllegalArgumentException::class.java) {
            emptyRepository.publishJpeg(ByteArrayInputStream(byteArrayOf()))
        }

        val failureRoot = Files.createTempDirectory("docscan-sync-failure")
        val failureRepository = FileArtifactRepository(
            failureRoot,
            DirectorySync { throw IllegalStateException("fsync failed") },
            "test-epoch",
        )
        assertThrows(IllegalStateException::class.java) {
            failureRepository.publishJpeg(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ArtifactId.new())
        }
    }

    @Test
    fun publishImagePreservesSupportedMediaExtensionAndRejectsPathInput() {
        val root = Files.createTempDirectory("docscan-image-types")
        val repository = FileArtifactRepository(root, DirectorySync {}, "test-epoch")
        val artifact = repository.publishImage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "png")

        assertTrue(artifact.relativePath.endsWith(".png"))
        assertTrue(Files.isRegularFile(root.resolve(artifact.relativePath)))
        assertThrows(IllegalArgumentException::class.java) {
            repository.publishImage(ByteArrayInputStream(byteArrayOf(1)), "../png")
        }
    }

    @Test
    fun exportPublisherAcceptsOnlyPdfAndJpegImmutableArtifacts() {
        val root = Files.createTempDirectory("docscan-export-types")
        val repository = FileArtifactRepository(root, DirectorySync {}, "test-epoch")
        val pdf = repository.publishExportFile(ByteArrayInputStream("%PDF".toByteArray()), "pdf")
        val jpeg = repository.publishExportFile(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "jpg")

        assertTrue(pdf.relativePath.endsWith(".pdf"))
        assertTrue(jpeg.relativePath.endsWith(".jpg"))
        assertTrue(repository.verifyPublished(pdf.relativePath, pdf.sizeBytes, pdf.sha256))
        assertTrue(repository.verifyPublished(jpeg.relativePath, jpeg.sizeBytes, jpeg.sha256))
        assertThrows(IllegalArgumentException::class.java) {
            repository.publishExportFile(ByteArrayInputStream(byteArrayOf(1)), "png")
        }
    }

    @Test
    fun interruptedPdfPublicationRecoveryRemovesOnlyIntentOwnedFinalAndStagingFiles() {
        val root = Files.createTempDirectory("docscan-pdf-intent")
        val synced = mutableListOf<java.nio.file.Path>()
        val repository = FileArtifactRepository(root, DirectorySync { synced.add(it) }, "test-epoch")
        val ownedId = ArtifactId.new()
        val unrelatedId = ArtifactId.new()
        val sourceBytes = "private-pdf-source".toByteArray()
        val published = repository.publishOrVerifyExportFile(ByteArrayInputStream(sourceBytes), "pdf", ownedId)
        val recovered = repository.publishedPdfForImport(ownedId)
        assertEquals(published.copy(producerEpoch = "test-epoch"), recovered)

        val staging = root.resolve("staging")
        Files.createDirectories(staging)
        val ownedTemporary = staging.resolve("${ownedId.value}-00000000-0000-0000-0000-000000000001.tmp")
        val unrelatedTemporary = staging.resolve("${unrelatedId.value}-00000000-0000-0000-0000-000000000002.tmp")
        Files.write(ownedTemporary, byteArrayOf(4, 5, 6))
        Files.write(unrelatedTemporary, byteArrayOf(7, 8, 9))
        val unrelated = repository.publishOrVerifyExportFile(ByteArrayInputStream(byteArrayOf(10)), "pdf", unrelatedId)

        repository.cleanupInterruptedPdfPublication(ownedId)

        assertFalse(Files.exists(root.resolve(published.relativePath)))
        assertFalse(Files.exists(ownedTemporary))
        assertTrue(Files.exists(unrelatedTemporary))
        assertTrue(Files.exists(root.resolve(unrelated.relativePath)))
        assertTrue(synced.contains(staging.toRealPath()))
    }

    @Test
    fun failedPdfSourceCopyLeavesNoFinalOrStagingFile() {
        val root = Files.createTempDirectory("docscan-pdf-copy-failure")
        val repository = FileArtifactRepository(root, DirectorySync {}, "test-epoch")
        val id = ArtifactId.new()
        val failingSource = object : java.io.InputStream() {
            private var sent = false
            override fun read(): Int {
                if (!sent) { sent = true; return 42 }
                throw java.io.IOException("provider read failed")
            }
        }

        assertThrows(java.io.IOException::class.java) {
            repository.publishOrVerifyExportFile(failingSource, "pdf", id)
        }

        assertFalse(Files.exists(root.resolve("objects/${id.value.take(2)}/${id.value}.pdf")))
        val staging = root.resolve("staging")
        if (Files.exists(staging)) Files.newDirectoryStream(staging).use { assertFalse(it.iterator().hasNext()) }
    }

    @Test
    fun deterministicPdfPublicationReusesOnlyByteIdenticalSnapshot() {
        val root = Files.createTempDirectory("docscan-pdf-idempotency")
        val repository = FileArtifactRepository(root, DirectorySync {}, "test-epoch")
        val id = ArtifactId.new()
        val bytes = "%PDF-test-source".toByteArray()

        val first = repository.publishOrVerifyExportFile(ByteArrayInputStream(bytes), "pdf", id)
        val repeated = repository.publishOrVerifyExportFile(ByteArrayInputStream(bytes), "pdf", id)

        assertEquals(first, repeated)
        assertThrows(IllegalStateException::class.java) {
            repository.publishOrVerifyExportFile(ByteArrayInputStream("different".toByteArray()), "pdf", id)
        }
        assertTrue(repository.verifyPublished(first.relativePath, first.sizeBytes, first.sha256))
    }

    @Test
    fun deterministicPageImagePublicationCanResumeAfterPublishBeforeMetadataCommit() {
        val root = Files.createTempDirectory("docscan-page-image-recovery")
        val repository = FileArtifactRepository(root, DirectorySync {}, "first-process")
        val id = ArtifactId.new()
        val bytes = byteArrayOf(9, 8, 7, 6)
        val first = repository.publishOrVerifyImage(ByteArrayInputStream(bytes), "png", id)

        val afterRestart = FileArtifactRepository(root, DirectorySync {}, "second-process")
        val repeated = afterRestart.publishOrVerifyImage(ByteArrayInputStream(bytes), "png", id)

        assertEquals(first.relativePath, repeated.relativePath)
        assertEquals(first.sha256, repeated.sha256)
        assertThrows(IllegalStateException::class.java) {
            afterRestart.publishOrVerifyImage(ByteArrayInputStream(byteArrayOf(1, 2)), "png", id)
        }
    }

    @Test
    fun interruptedSavePublicationCleanupRemovesOnlyExactTargetAndTemps() {
        val root = Files.createTempDirectory("docscan-save-publication-intent")
        val synced = mutableListOf<java.nio.file.Path>()
        val repository = FileArtifactRepository(root, DirectorySync { synced.add(it) }, "test-epoch")
        val id = ArtifactId.new()
        val bytes = "%PDF-output".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val descriptor = repository.describeExportFile(id, "pdf", bytes.size.toLong(), hash)
        val published = repository.publishExportFile(ByteArrayInputStream(bytes), "pdf", id)
        assertEquals(descriptor, published)
        val staging = root.resolve("staging")
        val temporary = staging.resolve("${id.value}-12345678-1234-1234-1234-123456789abc.tmp")
        Files.write(temporary, byteArrayOf(1, 2, 3))

        repository.cleanupInterruptedExportPublication(
            id, "pdf", descriptor.relativePath, descriptor.sizeBytes, descriptor.sha256,
        )

        assertFalse(Files.exists(root.resolve(descriptor.relativePath)))
        assertFalse(Files.exists(temporary))
        assertTrue(synced.any { it.fileName.toString() == "staging" })
        repository.cleanupInterruptedExportPublication(
            id, "pdf", descriptor.relativePath, descriptor.sizeBytes, descriptor.sha256,
        )
    }

    @Test
    fun interruptedSavePublicationCleanupRejectsMismatchedObjectBytes() {
        val root = Files.createTempDirectory("docscan-save-publication-mismatch")
        val repository = FileArtifactRepository(root, DirectorySync {}, "test-epoch")
        val id = ArtifactId.new()
        val descriptor = repository.describeExportFile(id, "jpg", 3L, "0".repeat(64))
        val objectPath = root.resolve(descriptor.relativePath)
        Files.createDirectories(objectPath.parent)
        Files.write(objectPath, byteArrayOf(1, 2, 3))

        assertThrows(IllegalStateException::class.java) {
            repository.cleanupInterruptedExportPublication(
                id, "jpg", descriptor.relativePath, descriptor.sizeBytes, descriptor.sha256,
            )
        }
        assertTrue(Files.exists(objectPath))
    }
}
