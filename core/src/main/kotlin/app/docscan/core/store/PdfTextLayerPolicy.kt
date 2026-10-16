package app.docscan.core.store

/** Reversible text-layer reuse heuristic from ENG-PDF-IMPORT-01; FACT-07 still gates acceptance. */
object PdfTextLayerPolicy {
    const val MIN_NON_WHITESPACE_CHARACTERS = 20
    const val MIN_LATIN_CYRILLIC_ALNUM_PERCENT = 70

    fun isReusable(text: String): Boolean {
        val visible = text.filterNot(Char::isWhitespace)
        if (visible.length < MIN_NON_WHITESPACE_CHARACTERS) return false
        val supported = visible.count(::isLatinCyrillicLetterOrDigit)
        return supported * 100 >= visible.length * MIN_LATIN_CYRILLIC_ALNUM_PERCENT
    }

    private fun isLatinCyrillicLetterOrDigit(char: Char): Boolean =
        char in '0'..'9'
            || char in 'A'..'Z'
            || char in 'a'..'z'
            || char in '\u0400'..'\u052F'
            || char in '\u1C80'..'\u1C8A'
            || char in '\uA640'..'\uA69F'
}
