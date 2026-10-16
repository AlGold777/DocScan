package app.docscan.core.store

import java.nio.charset.StandardCharsets
import java.util.Base64

data class DecodedRecognitionPayload(
    val text: String,
    val geometry: RecognitionGeometry?,
)

/** Backward-compatible envelope stored in the existing Recognition.payload column. */
object RecognitionPayloadCodec {
    private const val MAGIC = "DOCSCAN-OCR-GEOMETRY/1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(text: String, geometry: RecognitionGeometry): String {
        validate(geometry)
        val lines = mutableListOf(
            MAGIC,
            text.toByteArray(StandardCharsets.UTF_8).encodeBase64(),
            listOf(
                geometry.sourceWidthPx,
                geometry.sourceHeightPx,
                geometry.coordinateSpace,
                geometry.formatVersion,
                geometry.words.size,
            ).joinToString("|"),
        )
        geometry.words.forEach { word ->
            lines += listOf(
                word.text.toByteArray(StandardCharsets.UTF_8).encodeBase64(),
                word.leftPx,
                word.topPx,
                word.rightPx,
                word.bottomPx,
                word.confidence,
            ).joinToString("|")
        }
        return lines.joinToString("\n")
    }

    /** Legacy recognition rows contain plain UTF-8 text and remain readable. */
    fun decode(payload: String): DecodedRecognitionPayload {
        if (!payload.startsWith("$MAGIC\n")) return DecodedRecognitionPayload(payload, null)
        val lines = payload.split('\n')
        require(lines.size >= 3 && lines[0] == MAGIC) { "OCR geometry envelope is truncated" }
        val header = lines[2].split('|')
        require(header.size == 5) { "OCR geometry header is malformed" }
        val width = header[0].toLong()
        val height = header[1].toLong()
        val coordinateSpace = header[2]
        val version = header[3].toLong()
        val wordCount = header[4].toInt()
        require(wordCount >= 0 && lines.size == 3 + wordCount) { "OCR geometry word count is malformed" }
        val words = lines.drop(3).map { line ->
            val fields = line.split('|')
            require(fields.size == 6) { "OCR word record is malformed" }
            RecognizedWord(
                text = fields[0].decodeBase64().toString(StandardCharsets.UTF_8),
                leftPx = fields[1].toLong(),
                topPx = fields[2].toLong(),
                rightPx = fields[3].toLong(),
                bottomPx = fields[4].toLong(),
                confidence = fields[5].toDouble(),
            )
        }
        val geometry = RecognitionGeometry(width, height, coordinateSpace, words, version)
        validate(geometry)
        return DecodedRecognitionPayload(lines[1].decodeBase64().toString(StandardCharsets.UTF_8), geometry)
    }

    private fun validate(geometry: RecognitionGeometry) {
        require(geometry.sourceWidthPx > 0L && geometry.sourceHeightPx > 0L)
        require(geometry.coordinateSpace == OCR_WORD_COORDINATE_SPACE && geometry.formatVersion == 1L)
        geometry.words.forEach { word ->
            require(word.text.isNotBlank())
            require(word.leftPx >= 0L && word.topPx >= 0L)
            require(word.rightPx > word.leftPx && word.bottomPx > word.topPx)
            require(word.rightPx <= geometry.sourceWidthPx && word.bottomPx <= geometry.sourceHeightPx)
            require(word.confidence.isFinite() && word.confidence in 0.0..100.0)
        }
    }

    private fun ByteArray.encodeBase64(): String = encoder.encodeToString(this)

    private fun String.decodeBase64(): ByteArray = decoder.decode(this)
}
