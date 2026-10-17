package app.docscan.android

import app.docscan.core.store.RecognitionLayout
import java.io.File

data class RecognitionOptions(
    val languageHints: Set<String> = emptySet(),
    val isCancelled: () -> Boolean = { false },
)

interface DocumentRecognizer : AutoCloseable {
    fun recognize(file: File, options: RecognitionOptions = RecognitionOptions()): RecognitionLayout
    fun cancelCurrentRecognition()
}
