package app.docscan.core.ocr

import app.docscan.core.store.RecognitionLayout

object HocrParser {
    private val extractor = HocrLayoutExtractor()

    fun parse(
        hocr: String,
        fallbackWidthPx: Int = 0,
        fallbackHeightPx: Int = 0,
        languageHints: Set<String> = emptySet(),
    ): RecognitionLayout {
        return extractor.extract(hocr, fallbackWidthPx, fallbackHeightPx, languageHints)
    }

    fun parse(
        hocr: String,
        fallbackWidthPx: Long,
        fallbackHeightPx: Long,
    ): RecognitionLayout {
        return parse(hocr, fallbackWidthPx.toInt(), fallbackHeightPx.toInt())
    }
}

