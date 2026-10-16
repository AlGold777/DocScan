package app.docscan.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductTransientScratchCleanupInstrumentedTest {
    @Test
    fun startupRemovesOnlyKnownInterruptedImageScratchFilesWithoutFollowingLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = context.cacheDir
        val id = UUID.randomUUID().toString()
        val uuid = UUID.randomUUID().toString()
        val staleFiles = listOf(
            File(cache, "capture-${System.nanoTime()}.jpg"),
            File(cache, "rotate-$uuid.jpg"),
            File(cache, "page-edit-$uuid.jpg"),
            File(cache, "crop-$uuid.jpg"),
            File(cache, "pdf-page-2-${UUID.randomUUID()}.png"),
        )
        val unrelated = File(cache, "unrelated-processing-$id.bin")
        val directory = File(cache, "crop-$id.jpg")
        val linkTarget = File(cache, "protected-target-$id.bin")
        val symlink = File(cache, "capture-${System.nanoTime()}.jpg")
        val savedPdfEdit = File(cache, "saved-pdf-edit-$id.pdf")
        var storage: ProductStorage? = null
        try {
            staleFiles.forEachIndexed { index, file -> file.writeBytes(byteArrayOf(index.toByte(), 7, 9)) }
            unrelated.writeBytes(byteArrayOf(11, 12, 13))
            assertTrue(directory.mkdir())
            linkTarget.writeBytes(byteArrayOf(21, 22, 23))
            Files.createSymbolicLink(symlink.toPath(), linkTarget.toPath())
            savedPdfEdit.writeBytes(byteArrayOf(31, 32, 33))

            storage = ProductStorage(
                context = context,
                databaseName = "scratch-recovery-$id.db",
                artifactDirectoryName = "scratch-recovery-artifacts-$id",
                cleanupSavedPdfEditCacheOnStartup = false,
            )

            assertEquals("Five interrupted producer files were reclaimed", 5, storage.abandonedProcessingScratchAtStartup)
            staleFiles.forEach { assertFalse("Stale scratch must be absent: ${it.name}", Files.exists(it.toPath())) }
            assertArrayEquals(byteArrayOf(11, 12, 13), unrelated.readBytes())
            assertTrue("A directory with a scratch-like basename is not a scratch file", directory.isDirectory)
            assertTrue("A matching symlink is retained, not followed", Files.isSymbolicLink(symlink.toPath()))
            assertArrayEquals(byteArrayOf(21, 22, 23), linkTarget.readBytes())
            assertArrayEquals(byteArrayOf(31, 32, 33), savedPdfEdit.readBytes())
            android.util.Log.i(TAG, "SCRATCH_RECOVERY_PASS removed=5 unrelated_retained=true directory_retained=true symlink_not_followed=true pdf_edit_output_retained=true")
        } finally {
            storage?.close()
            staleFiles.forEach { it.delete() }
            unrelated.delete()
            directory.delete()
            if (Files.isSymbolicLink(symlink.toPath())) Files.delete(symlink.toPath()) else symlink.delete()
            linkTarget.delete()
            savedPdfEdit.delete()
            File(context.getDatabasePath("scratch-recovery-$id.db").path + "-wal").delete()
            File(context.getDatabasePath("scratch-recovery-$id.db").path + "-shm").delete()
            context.getDatabasePath("scratch-recovery-$id.db").delete()
        }
    }

    companion object {
        private const val TAG = "DocScanScratchCleanup"
    }
}
