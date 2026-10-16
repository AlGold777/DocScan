package app.docscan.android

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** A suggested location is not a grant: only the successful system picker result authorizes writes. */
internal object DestinationPickerIntent {
    fun create(selectedTreeUri: String?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        val initialUri = selectedTreeUri?.let(Uri::parse)
            ?: DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Docscan")
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
    }
}
