package app.docscan.core.export

import java.text.Normalizer
import java.util.Locale

data class ExportOutputNames(
    val basename: String,
    val pdfName: String,
    val pageJpegNames: List<String>,
)

sealed interface OutputNameResolution {
    data class Resolved(val names: ExportOutputNames) : OutputNameResolution
    data class Invalid(val reason: String) : OutputNameResolution
    data class Collision(val conflictingNames: List<String>) : OutputNameResolution
}

/** Resolves UFS single/multi-page output names and chooses a free basename suffix without overwriting. */
object OutputNameResolver {
    /** Used by saved-PDF editing when only a PDF name is published. */
    fun resolveSinglePdfWithSuffix(
        requestedBasename: String,
        existingNames: Collection<String>,
    ): OutputNameResolution {
        val initial = resolve(requestedBasename, 1, emptyList())
        if (initial !is OutputNameResolution.Resolved) return initial
        val keys = existingNames.mapTo(HashSet()) { collisionKey(it) }
        for (suffix in 0..keys.size) {
            val basename = if (suffix == 0) initial.names.basename else "${initial.names.basename} ($suffix)"
            val candidate = namesFor(basename, 1)
            if (collisionKey(candidate.pdfName) !in keys) return OutputNameResolution.Resolved(candidate)
        }
        return OutputNameResolution.Collision(listOf("$requestedBasename.pdf"))
    }

    fun resolve(
        requestedBasename: String,
        pageCount: Int,
        existingNames: Collection<String>,
    ): OutputNameResolution {
        if (pageCount <= 0) return OutputNameResolution.Invalid("At least one frozen page is required")
        var basename = Normalizer.normalize(requestedBasename.trim(), Normalizer.Form.NFC)
        if (basename.endsWith(".pdf", ignoreCase = true)) {
            basename = basename.dropLast(4).trim()
        }
        if (basename.isBlank() || basename == "." || basename == "..") {
            return OutputNameResolution.Invalid("Enter a non-empty base filename")
        }
        if (basename.any {
                val category = Character.getType(it)
                it == '/' || it == '\\' || Character.isISOControl(it)
                    || category == Character.FORMAT.toInt()
                    || category == Character.LINE_SEPARATOR.toInt()
                    || category == Character.PARAGRAPH_SEPARATOR.toInt()
            }
        ) {
            return OutputNameResolution.Invalid("Base filename cannot contain path separators or control characters")
        }

        val existingKeys = existingNames.mapTo(HashSet()) { collisionKey(it) }
        var lastCollisions = emptyList<String>()
        // At most |existingKeys| suffix candidates can be blocked: each blocked set
        // consumes at least one distinct existing filename. The next candidate is free.
        for (suffix in 0..existingKeys.size) {
            val candidateBase = if (suffix == 0) basename else "$basename ($suffix)"
            val candidate = namesFor(candidateBase, pageCount)
            lastCollisions = (listOf(candidate.pdfName) + candidate.pageJpegNames)
                .filter { collisionKey(it) in existingKeys }
            if (lastCollisions.isEmpty()) return OutputNameResolution.Resolved(candidate)
        }
        return OutputNameResolution.Collision(lastCollisions)
    }

    private fun namesFor(basename: String, pageCount: Int): ExportOutputNames {
        val digits = maxOf(2, pageCount.toString().length)
        return ExportOutputNames(
            basename = basename,
            pdfName = "$basename.pdf",
            pageJpegNames = if (pageCount == 1) listOf("$basename.jpg") else {
                (1..pageCount).map { index ->
                    "$basename — ${index.toString().padStart(digits, '0')}.jpg"
                }
            },
        )
    }

    /** Shared by name planning and SAF pre-create/recovery checks. */
    fun collisionKey(name: String): String =
        Normalizer.normalize(name.trim().trimEnd('.'), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
