package app.docscan.android

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafDestinationGrantManagerInstrumentedTest {
    @Test
    fun rejectsNonTreeUrisBeforeTakingAnyProviderPermission() {
        val manager = SafDestinationGrantManager(ApplicationProvider.getApplicationContext())
        assertThrows(IllegalArgumentException::class.java) {
            manager.persistTreeGrant(Uri.parse("file:///private/documents"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    @Test
    fun rejectsNonPersistableOrIncompleteGrantBeforeCallingContentResolver() {
        val manager = SafDestinationGrantManager(ApplicationProvider.getApplicationContext())
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        assertThrows(IllegalArgumentException::class.java) {
            manager.persistTreeGrant(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.persistTreeGrant(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
