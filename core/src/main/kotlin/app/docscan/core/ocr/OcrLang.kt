package app.docscan.core.ocr

enum class OcrLangProfile(val tess: String) {
    RU_EN("rus+eng"),
    ES_EN("spa+eng"),
    RU_ES_EN("rus+spa+eng"),
}

enum class OcrLangMode(val profile: OcrLangProfile?) {
    AUTO(null),
    RU_EN(OcrLangProfile.RU_EN),
    ES_EN(OcrLangProfile.ES_EN),
    RU_ES_EN(OcrLangProfile.RU_ES_EN),
}
