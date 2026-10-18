package app.docscan.core.ocr

/** Chooses the OCR pass with the best character coverage while guarding text already recognized. */
object OcrQualitySelector {
    fun choose(first: OcrLayout, second: OcrLayout): OcrLayout {
        val firstText = first.toPlainText()
        val secondText = second.toPlainText()
        val firstChars = firstText.count(Char::isLetterOrDigit)
        val secondChars = secondText.count(Char::isLetterOrDigit)
        if (firstChars == 0 && secondChars == 0) return first
        if (firstChars > 0 && confidence(second) + 0.10f < confidence(first)) return first
        // Keep already credible identifiers/amounts unless the replacement also contains them.
        // Low-confidence numbers may be corrected, so this is not a blanket digit lock.
        val credibleNumbers = first.words.filter { it.conf >= 70f }
            .flatMap { NUMBER.findAll(it.text).map { match -> match.value }.toList() }
        val secondNumbers = NUMBER.findAll(secondText).map { it.value }.toList()
        if (credibleNumbers.any { number -> secondNumbers.none { it.contains(number) } }) return first

        val maxChars = maxOf(firstChars, secondChars).coerceAtLeast(1)
        fun contentScore(text: String, count: Int): Float {
            val coverage = count.toFloat() / maxChars
            val retained = if (firstChars == 0) 1f else characterOverlap(firstText, text).toFloat() / firstChars
            return 0.65f * coverage + 0.35f * retained.coerceIn(0f, 1f)
        }

        val firstContent = contentScore(firstText, firstChars)
        val secondContent = contentScore(secondText, secondChars)
        if (secondContent > firstContent + CONTENT_TIE_EPSILON) return second
        if (firstContent > secondContent + CONTENT_TIE_EPSILON) return first

        val firstSecondary = 0.8f * confidence(first) + 0.2f * languageAgreement(first, second)
        val secondSecondary = 0.8f * confidence(second) + 0.2f * languageAgreement(second, first)
        return if (secondSecondary > firstSecondary + SCORE_TIE_EPSILON) second else first
    }

    private fun characterOverlap(first: String, second: String): Int {
        val firstCounts = characterCounts(first)
        val secondCounts = characterCounts(second)
        return firstCounts.entries.sumOf { (character, count) -> minOf(count, secondCounts[character] ?: 0) }
    }

    private fun characterCounts(text: String): Map<Char, Int> = buildMap {
        // Diacritic corrections must not look like dropped content (nina -> niña).
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).forEach { character ->
            if (character.isLetterOrDigit()) {
                val normalized = character.lowercaseChar()
                put(normalized, (get(normalized) ?: 0) + 1)
            }
        }
    }

    private fun confidence(layout: OcrLayout): Float {
        val confidences = layout.words.map { it.conf }.filter { it.isFinite() }
        val value = if (confidences.isNotEmpty()) confidences.average().toFloat()
        else layout.quality?.meanConfidence ?: 0f
        return value.coerceIn(0f, 100f) / 100f
    }

    private fun languageAgreement(candidate: OcrLayout, other: OcrLayout): Float {
        val supported = languageTags(candidate.lang, candidate.quality?.language)
        val expected = LangDetect.decide(other.toPlainText())?.tess?.let { languageTags(it) }
        if (!expected.isNullOrEmpty()) return if (supported.containsAll(expected)) 1f else 0f
        val hints = (candidate.languageHints + other.languageHints).flatMap { languageTags(it) }.toSet()
        if (supported.isEmpty() || hints.isEmpty()) return 0.5f
        return if (supported.any { it in hints }) 1f else 0f
    }

    private fun languageTags(vararg values: String?): Set<String> = values
        .filterNotNull()
        .flatMap { it.split('+', ',', ';') }
        .map { it.trim().lowercase().substringBefore('-') }
        .filter(String::isNotBlank)
        .toSet()

    private val NUMBER = Regex("\\d+(?:[.,:/-]\\d+)*")
    private const val CONTENT_TIE_EPSILON = 0.015f
    private const val SCORE_TIE_EPSILON = 0.01f
}
