package app.docscan.core.ocr

object LangDetect {
    private const val MIN_LETTERS = 40          // подобрать по тестам
    private const val MIN_ES_HITS = 5.0         // подобрать по тестам
    private const val ES_OVER_EN = 1.5          // подобрать по тестам
    private const val CYR_SHARE_MIXED = 0.25    // подобрать по тестам
    private const val DIACRITICS = "ñáéíóúü¿¡"

    private val ES = setOf(
        "el", "la", "los", "las", "de", "del", "que", "y", "en", "un", "una",
        "por", "para", "con", "es", "se", "su", "sus", "al", "lo", "como", "más", "pero", "ya", "este",
        "esta", "entre", "cuando", "muy", "sin", "sobre", "también", "hasta", "hay", "donde", "desde",
        "todo", "nos", "durante", "sí", "porque", "son", "fue", "ser"
    )
    private val EN = setOf(
        "the", "and", "of", "to", "in", "is", "that", "for", "it", "with", "as",
        "was", "on", "are", "be", "by", "this", "at", "from", "or", "an", "have", "not", "but", "which",
        "you", "all", "we", "will", "can", "has", "their", "been"
    )
    private val WORD = Regex("\\p{L}+")

    fun decide(text: String): OcrLangProfile? {
        val lower = text.lowercase()
        var letters = 0
        var cyr = 0
        var dia = 0
        for (ch in lower) {
            if (ch.isLetter()) {
                letters++
                if (ch in '\u0400'..'\u04FF') cyr++
            }
            if (ch in DIACRITICS) dia++
        }
        if (letters < MIN_LETTERS) {
            // Short receipts/titles do not provide enough stop words. ñ and inverted
            // punctuation are stronger evidence among the supported RU/EN/ES languages.
            if (letters < 3 || lower.none { it == 'ñ' || it == '¿' || it == '¡' }) return null
            return if (cyr.toDouble() / letters >= CYR_SHARE_MIXED)
                OcrLangProfile.RU_ES_EN else OcrLangProfile.ES_EN
        }
        var es = 0.5 * dia
        var en = 0.0
        for (m in WORD.findAll(lower)) {
            if (m.value in ES) es += 1.0
            if (m.value in EN) en += 1.0
        }
        if (es < MIN_ES_HITS || es < ES_OVER_EN * (en + 1)) return null   // не испанский
        return if (cyr.toDouble() / letters >= CYR_SHARE_MIXED) {
            OcrLangProfile.RU_ES_EN
        } else {
            OcrLangProfile.ES_EN
        }
    }
}
