package app.docscan.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.FileAlreadyExistsException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAtomicArtifactPublisherInstrumentedTest {
    @Test
    fun atomicallyRenamesWithinPrivateStorageAndRefusesToReplaceExistingTarget() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "publisher-${System.nanoTime()}")
        val source = File(root, "staging/source.tmp")
        val target = File(root, "objects/result.bin")
        source.parentFile!!.mkdirs()
        target.parentFile!!.mkdirs()
        val publisher = AndroidAtomicArtifactPublisher()
        try {
            val first = byteArrayOf(1, 2, 3, 4)
            source.writeBytes(first)
            publisher.publish(source.toPath(), target.toPath())
            assertFalse(source.exists())
            assertArrayEquals(first, target.readBytes())

            val duplicate = File(source.parentFile, "duplicate.tmp")
            val originalTargetBytes = target.readBytes()
            duplicate.writeBytes(byteArrayOf(9, 8, 7))
            assertThrows(FileAlreadyExistsException::class.java) {
                publisher.publish(duplicate.toPath(), target.toPath())
            }
            assertTrue(duplicate.exists())
            assertArrayEquals(originalTargetBytes, target.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
