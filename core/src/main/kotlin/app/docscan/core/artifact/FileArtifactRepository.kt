package app.docscan.core.artifact

import app.docscan.core.model.ArtifactId
import app.docscan.core.store.DurableImageArtifact
import app.docscan.core.store.DurableExportFile
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

fun interface DirectorySync {
    /** Must return only after the directory entry is durable for the supported crash boundary. */
    fun sync(directory: Path)
}

fun interface AtomicArtifactPublisher {
    /** Creates the final name atomically and must fail if it already exists. */
    fun publish(temporary: Path, target: Path)
}

fun interface ArtifactStagingSync {
    /** Forces completed staging-file bytes before the repository can publish a final name. */
    fun force(channel: FileChannel)
}

fun interface ArtifactStagingWriteCheckpoint {
    /** Runs immediately before the next private staging-file write. */
    fun beforeWrite(channel: FileChannel, bytesAlreadyWritten: Long)
}

fun interface ArtifactStagingOutputFlush {
    /** Flushes completed output before the staging file is forced or published. */
    fun flush(output: java.io.OutputStream, channel: FileChannel, bytesWritten: Long)
}

object NioAtomicArtifactPublisher : AtomicArtifactPublisher {
    override fun publish(temporary: Path, target: Path) {
        Files.createLink(target, temporary)
        Files.delete(temporary)
    }
}

/** JVM implementation for host tests. Android must provide its own qualified DirectorySync. */
object NioDirectorySync : DirectorySync {
    override fun sync(directory: Path) {
        FileChannel.open(directory, READ).use { channel -> channel.force(true) }
    }
}

/**
 * Publishes image bytes into private immutable object storage.
 * Metadata registration is deliberately a separate StateStore transaction.
 */
class FileArtifactRepository(
    private val privateRoot: Path,
    private val directorySync: DirectorySync,
    private val producerEpoch: String,
    private val atomicPublisher: AtomicArtifactPublisher = NioAtomicArtifactPublisher,
    private val stagingSync: ArtifactStagingSync = ArtifactStagingSync { channel -> channel.force(true) },
    private val stagingWriteCheckpoint: ArtifactStagingWriteCheckpoint = ArtifactStagingWriteCheckpoint { _, _ -> },
    private val stagingOutputFlush: ArtifactStagingOutputFlush = ArtifactStagingOutputFlush { output, _, _ -> output.flush() },
) {
    init {
        require(producerEpoch.isNotBlank())
    }

    fun publishJpeg(source: InputStream, artifactId: ArtifactId = ArtifactId.new()): DurableImageArtifact {
        return publishImage(source, "jpg", artifactId)
    }

    fun publishImage(
        source: InputStream,
        extension: String,
        artifactId: ArtifactId = ArtifactId.new(),
    ): DurableImageArtifact {
        require(extension in SUPPORTED_IMAGE_EXTENSIONS) { "Unsupported image extension" }
        val metadata = publishBytes(source, extension, artifactId)
        return DurableImageArtifact(
            artifactId = metadata.artifactId,
            relativePath = metadata.relativePath,
            sizeBytes = metadata.sizeBytes,
            sha256 = metadata.sha256,
            formatVersion = metadata.formatVersion,
            producerEpoch = metadata.producerEpoch,
        )
    }

    /** Reuses a deterministic image object after a crash only when the bytes are identical. */
    fun publishOrVerifyImage(
        source: InputStream,
        extension: String,
        artifactId: ArtifactId,
    ): DurableImageArtifact {
        require(extension in SUPPORTED_IMAGE_EXTENSIONS) { "Unsupported image extension" }
        val metadata = publishBytes(source, extension, artifactId, allowIdenticalExisting = true)
        return DurableImageArtifact(
            artifactId = metadata.artifactId,
            relativePath = metadata.relativePath,
            sizeBytes = metadata.sizeBytes,
            sha256 = metadata.sha256,
            formatVersion = metadata.formatVersion,
            producerEpoch = metadata.producerEpoch,
        )
    }

    fun publishExportFile(
        source: InputStream,
        extension: String,
        artifactId: ArtifactId = ArtifactId.new(),
    ): DurableExportFile {
        require(extension in SUPPORTED_EXPORT_EXTENSIONS) { "Unsupported export file extension" }
        val metadata = publishBytes(source, extension, artifactId)
        return DurableExportFile(
            artifactId = metadata.artifactId,
            relativePath = metadata.relativePath,
            sizeBytes = metadata.sizeBytes,
            sha256 = metadata.sha256,
            formatVersion = metadata.formatVersion,
            producerEpoch = metadata.producerEpoch,
        )
    }

    /** Describes an immutable export target before creating any file-system object. */
    fun describeExportFile(
        artifactId: ArtifactId,
        extension: String,
        sizeBytes: Long,
        sha256: String,
    ): DurableExportFile {
        require(extension in SUPPORTED_EXPORT_EXTENSIONS)
        require(sizeBytes > 0L && sha256.matches(Regex("[0-9a-f]{64}")))
        return DurableExportFile(
            artifactId = artifactId,
            relativePath = expectedExportFileRelativePath(artifactId, extension),
            sizeBytes = sizeBytes,
            sha256 = sha256,
            formatVersion = 1L,
            producerEpoch = producerEpoch,
        )
    }

    fun expectedExportFileRelativePath(artifactId: ArtifactId, extension: String): String {
        require(extension in SUPPORTED_EXPORT_EXTENSIONS)
        return "objects/${artifactId.value.take(2)}/${artifactId.value}.$extension"
    }

    fun expectedImageFileRelativePath(artifactId: ArtifactId, extension: String): String {
        require(extension in SUPPORTED_IMAGE_EXTENSIONS)
        return "objects/${artifactId.value.take(2)}/${artifactId.value}.$extension"
    }

    /** Reads metadata for the exact deterministic private PDF source path owned by an import intent. */
    fun publishedPdfForImport(artifactId: ArtifactId): DurableExportFile? {
        val relativePath = expectedExportFileRelativePath(artifactId, "pdf")
        val target = resolvePrivateObject(relativePath) ?: return null
        if (!Files.exists(target, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Private PDF source path is not a regular file" }
        val size = Files.size(target)
        check(size > 0L) { "Private PDF source is empty" }
        return DurableExportFile(
            artifactId = artifactId,
            relativePath = relativePath,
            sizeBytes = size,
            sha256 = sha256(target),
            formatVersion = 1L,
            producerEpoch = producerEpoch,
        )
    }

    /** Reads metadata only for the deterministic private image path owned by an image-import intent. */
    fun publishedImageForImport(artifactId: ArtifactId, extension: String): DurableImageArtifact? {
        val relativePath = expectedImageFileRelativePath(artifactId, extension)
        val target = resolvePrivateObject(relativePath) ?: return null
        if (!Files.exists(target, NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Private image source path is not a regular file" }
        val size = Files.size(target)
        check(size > 0L) { "Private image source is empty" }
        return DurableImageArtifact(
            artifactId = artifactId,
            relativePath = relativePath,
            sizeBytes = size,
            sha256 = sha256(target),
            formatVersion = 1L,
            producerEpoch = producerEpoch,
        )
    }

    /** Removes only an import-intent-owned final PDF and ID-prefixed staging files. */
    fun cleanupInterruptedPdfPublication(artifactId: ArtifactId) {
        val root = ensurePrivateRoot()
        val relativePath = expectedExportFileRelativePath(artifactId, "pdf")
        val target = resolvePrivateObject(relativePath)
        if (target != null && Files.exists(target, NOFOLLOW_LINKS)) {
            rejectSymbolicLinks(root, target)
            check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Interrupted PDF target is not a regular file" }
            check(Files.deleteIfExists(target)) { "Cannot remove interrupted PDF target" }
            syncDirectoryChain(root, target.parent)
        }

        val staging = root.resolve("staging").normalize()
        ensureContained(root, staging)
        if (!Files.exists(staging, NOFOLLOW_LINKS)) return
        rejectSymbolicLinks(root, staging)
        check(Files.isDirectory(staging, NOFOLLOW_LINKS)) { "Artifact staging path is not a directory" }
        val pattern = Regex("^${Regex.escape(artifactId.value)}-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.tmp$")
        val temporaries = Files.newDirectoryStream(staging).use { entries ->
            entries.filter { pattern.matches(it.fileName.toString()) }.toList()
        }
        temporaries.forEach { temporary ->
            rejectSymbolicLinks(root, temporary)
            check(Files.isRegularFile(temporary, NOFOLLOW_LINKS)) { "Artifact temporary path is not a regular file" }
            check(Files.deleteIfExists(temporary)) { "Cannot remove interrupted PDF temporary" }
        }
        if (temporaries.isNotEmpty()) directorySync.sync(staging)
    }

    /** Removes only an image-import-intent-owned final image and ID-prefixed staging files. */
    fun cleanupInterruptedImagePublication(artifactId: ArtifactId, extension: String) {
        val root = ensurePrivateRoot()
        val target = resolvePrivateObject(expectedImageFileRelativePath(artifactId, extension))
        if (target != null && Files.exists(target, NOFOLLOW_LINKS)) {
            rejectSymbolicLinks(root, target)
            check(Files.isRegularFile(target, NOFOLLOW_LINKS)) { "Interrupted image target is not a regular file" }
            check(Files.deleteIfExists(target)) { "Cannot remove interrupted image target" }
            syncDirectoryChain(root, target.parent)
        }
        val staging = root.resolve("staging").normalize()
        ensureContained(root, staging)
        if (!Files.exists(staging, NOFOLLOW_LINKS)) return
        rejectSymbolicLinks(root, staging)
        check(Files.isDirectory(staging, NOFOLLOW_LINKS)) { "Artifact staging path is not a directory" }
        val escapedId = Regex.escape(artifactId.value)
        val pattern = Regex("^$escapedId-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.tmp$")
        val temporaries = Files.newDirectoryStream(staging).use { entries ->
            entries.filter { pattern.matches(it.fileName.toString()) }.toList()
        }
        temporaries.forEach { temporary ->
            rejectSymbolicLinks(root, temporary)
            check(Files.isRegularFile(temporary, NOFOLLOW_LINKS)) { "Artifact temporary path is not a regular file" }
            check(Files.deleteIfExists(temporary)) { "Cannot remove interrupted image temporary" }
        }
        if (temporaries.isNotEmpty()) directorySync.sync(staging)
    }

    /** Removes a publication intent's exact target and ID-prefixed write temps, idempotently. */
    fun cleanupInterruptedExportPublication(
        artifactId: ArtifactId,
        extension: String,
        relativePath: String,
        sizeBytes: Long,
        sha256: String,
    ) {
        require(relativePath == expectedExportFileRelativePath(artifactId, extension)) {
            "Publication intent path does not match its deterministic ArtifactId"
        }
        when (inspect(relativePath, sizeBytes, sha256)) {
            ArtifactFileObservation.VERIFIED_PRESENT -> check(
                deleteVerified(relativePath, sizeBytes, sha256) != VerifiedDeleteResult.INVALID,
            ) { "Interrupted export object changed while deleting" }
            ArtifactFileObservation.MISSING -> Unit
            ArtifactFileObservation.INVALID -> error("Interrupted export object failed private path/content validation")
        }

        val root = ensurePrivateRoot()
        val staging = root.resolve("staging").normalize()
        ensureContained(root, staging)
        if (!Files.exists(staging, NOFOLLOW_LINKS)) return
        rejectSymbolicLinks(root, staging)
        check(Files.isDirectory(staging, NOFOLLOW_LINKS)) { "Artifact staging path is not a directory" }
        val escapedId = Regex.escape(artifactId.value)
        val temporaryPattern = Regex("^$escapedId-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.tmp$")
        val temporaries = Files.newDirectoryStream(staging).use { entries ->
            entries.filter { temporaryPattern.matches(it.fileName.toString()) }.toList()
        }
        temporaries.forEach { temporary ->
            rejectSymbolicLinks(root, temporary)
            check(Files.isRegularFile(temporary, NOFOLLOW_LINKS)) { "Artifact temporary path is not a regular file" }
            check(Files.deleteIfExists(temporary)) { "Cannot remove interrupted artifact temporary" }
        }
        if (temporaries.isNotEmpty()) directorySync.sync(staging)
    }

    /** Reuses a deterministic publication only when a repeated workflow presents byte-identical input. */
    fun publishOrVerifyExportFile(
        source: InputStream,
        extension: String,
        artifactId: ArtifactId,
    ): DurableExportFile {
        require(extension in SUPPORTED_EXPORT_EXTENSIONS) { "Unsupported export file extension" }
        val metadata = publishBytes(source, extension, artifactId, allowIdenticalExisting = true)
        return DurableExportFile(
            artifactId = metadata.artifactId,
            relativePath = metadata.relativePath,
            sizeBytes = metadata.sizeBytes,
            sha256 = metadata.sha256,
            formatVersion = metadata.formatVersion,
            producerEpoch = metadata.producerEpoch,
        )
    }

    private fun publishBytes(
        source: InputStream,
        extension: String,
        artifactId: ArtifactId,
        allowIdenticalExisting: Boolean = false,
    ): FileMetadata {
        val root = ensurePrivateRoot()
        val id = artifactId.value
        val stagingDir = root.resolve("staging").normalize()
        val objectDir = root.resolve("objects").resolve(id.take(2)).normalize()
        ensureContained(root, stagingDir)
        ensureContained(root, objectDir)
        Files.createDirectories(stagingDir)
        Files.createDirectories(objectDir)
        rejectSymbolicLinks(root, stagingDir)
        rejectSymbolicLinks(root, objectDir)

        val temporary = stagingDir.resolve("$id-${UUID.randomUUID()}.tmp")
        val target = objectDir.resolve("$id.$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L

        try {
            FileChannel.open(temporary, WRITE, CREATE_NEW).use { channel ->
                val output = java.nio.channels.Channels.newOutputStream(channel)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    stagingWriteCheckpoint.beforeWrite(channel, byteCount)
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    byteCount += count
                }
                stagingOutputFlush.flush(output, channel, byteCount)
                stagingSync.force(channel)
            }
            require(byteCount > 0L) { "Cannot publish an empty artifact" }
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                if (!allowIdenticalExisting || Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, NOFOLLOW_LINKS)
                    || Files.size(target) != byteCount
                ) throw IllegalStateException("Immutable artifact path already exists")
                val incomingSha256 = digest.digest().toHex()
                if (sha256(target) != incomingSha256) {
                    throw IllegalStateException("Repeated import request supplied different source bytes")
                }
                Files.deleteIfExists(temporary)
                directorySync.sync(stagingDir)
                directorySync.sync(objectDir)
                return FileMetadata(
                    artifactId,
                    root.relativize(target).toString().replace('\\', '/'),
                    byteCount,
                    incomingSha256,
                    1L,
                    producerEpoch,
                )
            }

            try {
                atomicPublisher.publish(temporary, target)
            } catch (failure: java.nio.file.FileAlreadyExistsException) {
                if (!allowIdenticalExisting || Files.isSymbolicLink(target)
                    || !Files.isRegularFile(target, NOFOLLOW_LINKS)
                    || Files.size(target) != byteCount
                    || sha256(target) != digest.digest().toHex()
                ) throw failure
                Files.deleteIfExists(temporary)
                syncDirectoryChain(root, stagingDir)
                syncDirectoryChain(root, objectDir)
                return FileMetadata(
                    artifactId,
                    root.relativize(target).toString().replace('\\', '/'),
                    byteCount,
                    sha256(target),
                    1L,
                    producerEpoch,
                )
            }
            syncDirectoryChain(root, stagingDir)
            syncDirectoryChain(root, objectDir)

            return FileMetadata(
                artifactId = artifactId,
                relativePath = root.relativize(target).toString().replace('\\', '/'),
                sizeBytes = byteCount,
                sha256 = digest.digest().toHex(),
                formatVersion = 1L,
                producerEpoch = producerEpoch,
            )
        } catch (failure: Throwable) {
            try {
                Files.deleteIfExists(temporary)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    /** Bounded one-object check. Caller must perform filesystem I/O outside database transactions. */
    fun verifyPublished(relativePath: String, expectedSize: Long, expectedSha256: String): Boolean {
        if (expectedSize <= 0L || !expectedSha256.matches(Regex("[0-9a-f]{64}"))) return false
        val root = ensurePrivateRoot()
        val relative = Path.of(relativePath)
        if (relative.isAbsolute || relative.any { it.toString() == ".." || it.toString().isBlank() }) return false
        val target = root.resolve(relative).normalize()
        ensureContained(root, target)
        rejectSymbolicLinks(root, target)
        if (!Files.isRegularFile(target, NOFOLLOW_LINKS) || Files.size(target) != expectedSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(target, READ).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex() == expectedSha256
    }

    /** Deletes exactly one checksum-verified private object and syncs its directory entries. */
    fun deleteVerified(relativePath: String, expectedSize: Long, expectedSha256: String): VerifiedDeleteResult {
        val target = resolvePrivateObject(relativePath) ?: return VerifiedDeleteResult.INVALID
        if (!Files.exists(target, NOFOLLOW_LINKS)) return VerifiedDeleteResult.MISSING
        if (!verifyPublished(relativePath, expectedSize, expectedSha256)) return VerifiedDeleteResult.INVALID
        // Recheck the final component immediately before unlinking; artifact storage is app-private.
        rejectSymbolicLinks(ensurePrivateRoot(), target)
        if (!Files.isRegularFile(target, NOFOLLOW_LINKS)) return VerifiedDeleteResult.INVALID
        if (!Files.deleteIfExists(target)) return VerifiedDeleteResult.MISSING
        syncDirectoryChain(ensurePrivateRoot(), target.parent)
        return VerifiedDeleteResult.DELETED
    }

    /**
     * Excludes cleanup workers sharing this private artifact root, including workers in other
     * processes. The OS releases the file lock if a process dies; the in-process lock prevents
     * overlapping Java file locks from throwing instead of waiting.
     */
    fun <T> withExclusiveCleanupLock(block: () -> T): T {
        PROCESS_CLEANUP_LOCK.lock()
        try {
            val root = ensurePrivateRoot()
            val lockPath = root.resolve(CLEANUP_LOCK_FILE)
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { return block() }
            }
        } finally {
            PROCESS_CLEANUP_LOCK.unlock()
        }
    }

    fun inspect(relativePath: String, expectedSize: Long, expectedSha256: String?): ArtifactFileObservation {
        val target = try { resolvePrivateObject(relativePath) } catch (_: SecurityException) { null }
            ?: return ArtifactFileObservation.INVALID
        if (!Files.exists(target, NOFOLLOW_LINKS)) return ArtifactFileObservation.MISSING
        if (expectedSha256 == null || !verifyPublished(relativePath, expectedSize, expectedSha256)) {
            return ArtifactFileObservation.INVALID
        }
        return ArtifactFileObservation.VERIFIED_PRESENT
    }

    private fun resolvePrivateObject(relativePath: String): Path? {
        val root = ensurePrivateRoot()
        val relative = Path.of(relativePath)
        if (relative.isAbsolute || relative.any { it.toString() == ".." || it.toString().isBlank() }) return null
        val target = root.resolve(relative).normalize()
        ensureContained(root, target)
        rejectSymbolicLinks(root, target)
        return target
    }

    private fun ensurePrivateRoot(): Path {
        Files.createDirectories(privateRoot)
        val root = privateRoot.toRealPath()
        if (Files.isSymbolicLink(privateRoot)) throw SecurityException("Artifact root cannot be a symlink")
        return root
    }

    private fun ensureContained(root: Path, candidate: Path) {
        if (!candidate.startsWith(root) || candidate == root) {
            throw SecurityException("Artifact path escapes private storage")
        }
    }

    private fun rejectSymbolicLinks(root: Path, target: Path) {
        var current = root
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw SecurityException("Artifact path contains a symlink")
        }
    }

    private fun syncDirectoryChain(root: Path, directory: Path) {
        var current: Path? = directory
        while (current != null && current.startsWith(root)) {
            directorySync.sync(current)
            if (current == root) break
            current = current.parent
        }
        directorySync.sync(root)
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, READ).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val CLEANUP_LOCK_FILE = ".cleanup-arbiter.lock"
        val PROCESS_CLEANUP_LOCK = ReentrantLock(true)
        val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic")
        val SUPPORTED_EXPORT_EXTENSIONS = setOf("pdf", "jpg")
    }

    private data class FileMetadata(
        val artifactId: ArtifactId,
        val relativePath: String,
        val sizeBytes: Long,
        val sha256: String,
        val formatVersion: Long,
        val producerEpoch: String,
    )
}

enum class VerifiedDeleteResult { DELETED, MISSING, INVALID }
enum class ArtifactFileObservation { VERIFIED_PRESENT, MISSING, INVALID }
