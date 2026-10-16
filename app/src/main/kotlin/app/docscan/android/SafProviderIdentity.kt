package app.docscan.android

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/** Exact provider/build tuple used by the product's SAF destination checks. */
internal data class SafProviderIdentity(
    val providerPackage: String,
    val adapterId: String,
    val providerVersion: String,
    val osVersion: String,
    val accessClass: String,
) {
    companion object {
        fun resolve(context: Context, treeUri: String): SafProviderIdentity {
            val tree = Uri.parse(treeUri)
            require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) {
                "Choose a valid SAF tree destination"
            }
            val documentId = DocumentsContract.getTreeDocumentId(tree)
            require(documentId.startsWith("primary:")) {
                "Only shared internal storage destinations are supported"
            }
            val authority = requireNotNull(tree.authority) { "SAF tree URI has no provider authority" }
            val provider = context.packageManager.resolveContentProvider(authority, 0)
                ?: error("SAF provider package cannot be resolved")
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageInfo(provider.packageName, 0)
            return SafProviderIdentity(
                providerPackage = provider.packageName,
                adapterId = "${ProductActivity.SAF_ADAPTER_ID}/${provider.packageName}/$authority",
                providerVersion = "${packageInfo.versionName.orEmpty()}#${packageInfo.longVersionCode}",
                osVersion = Build.FINGERPRINT,
                accessClass = ProductActivity.SAF_ACCESS_CLASS,
            )
        }
    }
}
