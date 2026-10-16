package app.docscan.core.export

import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Builds the editable default basename from current-page OCR and a stable local timestamp. */
object OutputBasenameSuggester {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm", Locale.ROOT)

    fun suggest(recognizedTextInPageOrder: Iterable<String?>, localTime: LocalDateTime): String {
        val semanticPrefix = recognizedTextInPageOrder.asSequence()
            .filterNotNull()
            .flatMap { it.lineSequence() }
            .map(::safeMeaningfulLine)
            .firstOrNull { it.isNotEmpty() }
            ?.let(::firstCodePoints40)
            ?: "Scan"
        return "$semanticPrefix ${localTime.format(timestampFormatter)}"
    }

    private fun safeMeaningfulLine(line: String): String {
        val safe = Normalizer.normalize(line, Normalizer.Form.NFKC)
            .filterNot { character ->
            val category = Character.getType(character)
            character == '/' || character == '\\' || character == ':' || character == '*' || character == '?'
                || character == '"' || character == '<' || character == '>' || character == '|'
                || Character.isISOControl(character)
                || category == Character.FORMAT.toInt()
                || category == Character.LINE_SEPARATOR.toInt()
                || category == Character.PARAGRAPH_SEPARATOR.toInt()
            }
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
        return if (safe.any(Character::isLetterOrDigit)) safe else ""
    }

    private fun firstCodePoints40(value: String): String {
        val result = StringBuilder()
        val points = value.codePoints().iterator()
        var count = 0
        while (points.hasNext() && count < MAX_SEMANTIC_CODE_POINTS) {
            result.appendCodePoint(points.nextInt())
            count++
        }
        return result.toString()
    }

    private const val MAX_SEMANTIC_CODE_POINTS = 40
}
