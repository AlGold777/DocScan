package app.docscan.android

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocScanPublisherRollbackInstrumentedTest {
    @Test
    fun realDownloadsProviderUsesFreeSuffixAndPreservesFirstPdf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "publisher-real-source-${System.nanoTime()}.pdf")
        val basename = "docscan-publisher-${UUID.randomUUID()}"
        val createdUris = mutableListOf<Uri>()
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(source)
        }

        try {
            val first = DocScanPublisher.publish(
                context = context,
                pdfSource = source,
                pdfFileName = "$basename.pdf",
                jpegSources = emptyList(),
                jpegFileNames = emptyList(),
                saveJpg = false,
            )
            createdUris += first.pdfUri
            val firstExpectedBytes = source.readBytes()
            assertArrayEquals(firstExpectedBytes, requireNotNull(context.contentResolver.openInputStream(first.pdfUri)).use { it.readBytes() })

            val second = DocScanPublisher.publish(
                context = context,
                pdfSource = source,
                pdfFileName = "$basename.pdf",
                jpegSources = emptyList(),
                jpegFileNames = emptyList(),
                saveJpg = false,
            )
            createdUris += second.pdfUri

            assertEquals("$basename.pdf", first.pdfDisplayName)
            assertEquals("$basename (1).pdf", second.pdfDisplayName)
            assertNotEquals("Each publication must have its own MediaStore URI", first.pdfUri, second.pdfUri)
            assertArrayEquals(
                "The second publication must not replace the first file",
                firstExpectedBytes,
                requireNotNull(context.contentResolver.openInputStream(first.pdfUri)).use { it.readBytes() },
            )
            assertArrayEquals(
                "The newly published PDF must read back byte-for-byte",
                firstExpectedBytes,
                requireNotNull(context.contentResolver.openInputStream(second.pdfUri)).use { it.readBytes() },
            )
        } finally {
            createdUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            source.delete()
        }
    }

    @Test
    fun failedReadBackRemovesEveryRowCreatedForPublication() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(targetContext.cacheDir, "publisher-rollback-${System.nanoTime()}").apply { mkdirs() }
        val pdf = File(root, "source.pdf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val jpeg = File(root, "page.jpg").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        val provider = FakeMediaStoreProvider(root, failReadBackAtIndex = 1)
        provider.attachInfo(targetContext, ProviderInfo().apply {
            authority = "media"
            packageName = targetContext.packageName
            applicationInfo = targetContext.applicationInfo
            name = FakeMediaStoreProvider::class.java.name
        })
        val resolver = ContentResolver.wrap(provider)
        val context = object : ContextWrapper(targetContext) {
            override fun getContentResolver(): ContentResolver = resolver
        }

        try {
            assertThrows(FileNotFoundException::class.java) {
                DocScanPublisher.publish(
                    context = context,
                    pdfSource = pdf,
                    pdfFileName = "rollback.pdf",
                    jpegSources = listOf(jpeg),
                    jpegFileNames = listOf("rollback.jpg"),
                    saveJpg = true,
                )
            }
            assertEquals("Both inserted MediaStore rows must be rolled back", 2, provider.deletedIds.size)
            assertTrue("The fake provider must have no published rows after rollback", provider.items.isEmpty())
            assertTrue("Rollback removes each inserted row exactly once", provider.insertedIds.toSet() == provider.deletedIds.toSet())
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeMediaStoreProvider(
        private val root: File,
        private val failReadBackAtIndex: Int,
    ) : ContentProvider() {
        data class Item(val id: String, val uri: Uri, val file: File, val values: ContentValues)

        val items = linkedMapOf<String, Item>()
        val insertedIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()
        private var nextId = 1

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val columns = projection?.toList() ?: listOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val cursor = MatrixCursor(columns.toTypedArray())
            val item = uri.lastPathSegment?.let(items::get)
            val rows = if (item != null) listOf(item) else items.values.toList()
            rows.forEach { row ->
                cursor.addRow(columns.map { column -> row.values.getAsString(column) })
            }
            return cursor
        }

        override fun getType(uri: Uri): String = "application/octet-stream"

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = (nextId++).toString()
            val itemUri = Uri.withAppendedPath(uri, id)
            val itemFile = File(root, "media-$id").apply { createNewFile() }
            val item = Item(id, itemUri, itemFile, ContentValues(values ?: ContentValues()))
            items[id] = item
            insertedIds += id
            return itemUri
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = uri.lastPathSegment ?: return 0
            val item = items.remove(id) ?: return 0
            item.file.delete()
            deletedIds += id
            return 1
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            val item = uri.lastPathSegment?.let(items::get) ?: return 0
            if (values != null) item.values.putAll(values)
            return 1
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val item = uri.lastPathSegment?.let(items::get) ?: throw FileNotFoundException(uri.toString())
            if (mode.startsWith("r") && insertedIds.indexOf(item.id) == failReadBackAtIndex) {
                throw FileNotFoundException("Injected read-back failure")
            }
            val flags = if (mode.startsWith("r")) ParcelFileDescriptor.MODE_READ_ONLY else {
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            }
            return ParcelFileDescriptor.open(item.file, flags)
        }
    }
}
