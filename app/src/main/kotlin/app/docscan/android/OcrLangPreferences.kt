package app.docscan.android

import android.content.SharedPreferences
import app.docscan.core.ocr.OcrLangMode

const val KEY_OCR_LANG_MODE = "ocr_lang_mode"

fun readLangMode(prefs: SharedPreferences): OcrLangMode =
    runCatching { OcrLangMode.valueOf(prefs.getString(KEY_OCR_LANG_MODE, "AUTO")!!) }
        .getOrDefault(OcrLangMode.AUTO)

fun saveLangMode(prefs: SharedPreferences, mode: OcrLangMode) {
    prefs.edit().putString(KEY_OCR_LANG_MODE, mode.name).apply()
}
