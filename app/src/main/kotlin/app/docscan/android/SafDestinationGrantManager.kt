package app.docscan.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.docscan.core.store.DestinationGrantSnapshot

/** Owns Android URI permission I/O; StateStore calls happen only after these checks complete. */
class SafDestinationGrantManager(context: Context) {
    private val appContext = context.applicationContext

    fun persistTreeGrant(uri: Uri?, returnedFlags: Int): String {
        require(uri != null && uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) {
            "Picker did not return a valid SAF tree URI"
        }
        val accessFlags = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(accessFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            && accessFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
            && returnedFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        ) { "SAF provider did not return persistable read/write access" }

        appContext.contentResolver.takePersistableUriPermission(uri, accessFlags)
        check(hasPersistedReadWriteGrant(uri)) { "SAF read/write permission did not persist" }
        return uri.toString()
    }

    /** Revalidate OS-owned authority outside SQLite; updates the verification timestamp after success. */
    fun revalidate(grant: DestinationGrantSnapshot, storage: ProductStorage): Boolean {
        val uri = runCatching { Uri.parse(grant.treeUri) }.getOrNull() ?: return false
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) || !hasPersistedReadWriteGrant(uri)) {
            return false
        }
        storage.markDestinationGrantVerified(grant.treeUri, System.currentTimeMillis())
        return true
    }

    fun releasePersistedGrant(uriString: String) {
        val uri = Uri.parse(uriString)
        val existing = appContext.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (existing.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (existing.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) appContext.contentResolver.releasePersistableUriPermission(uri, flags)
    }

    private fun hasPersistedReadWriteGrant(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
}
