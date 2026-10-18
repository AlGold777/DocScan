package app.docscan.core.store

import app.docscan.core.ocr.Bx
import app.docscan.core.ocr.OcrBlock as CoreOcrBlock
import app.docscan.core.ocr.OcrLine as CoreOcrLine
import app.docscan.core.ocr.OcrPara as CoreOcrPara
import app.docscan.core.ocr.OcrWord as CoreOcrWord
import app.docscan.core.ocr.OcrLayout
import app.docscan.core.ocr.toOcrLayout
import app.docscan.core.ocr.toPlainText
import app.docscan.core.ocr.toRecognitionLayout
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

data class DecodedRecognitionPayload(
    val text: String,
    val geometry: RecognitionGeometry?,
    val layout: RecognitionLayout? = null,
    val ocrLayout: OcrLayout? = null,
)

/** Backward-compatible envelope stored in the existing Recognition.payload column. */
object RecognitionPayloadCodec {
    private const val MAGIC_V1 = "DOCSCAN-OCR-GEOMETRY/1"
    private const val MAGIC_V2 = "DOCSCAN-OCR-LAYOUT/2"
    private const val HEAD2 = "DOCSCAN-OCR-LAYOUT/2\n"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    private fun bx(b: Bx) = JSONArray().put(b.l).put(b.t).put(b.r).put(b.b)
    private fun unbx(a: JSONArray) = Bx(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))

    fun encodeLayout(l: OcrLayout): String {
        val blocks = JSONArray()
        for (b in l.blocks) {
            val paras = JSONArray()
            for (p in b.paras) {
                val lines = JSONArray()
                for (ln in p.lines) {
                    val ws = JSONArray()
                    for (w in ln.words) ws.put(JSONArray()
                        .put(w.text).put(w.box.l).put(w.box.t).put(w.box.r).put(w.box.b).put(w.conf.toDouble()))
                    lines.put(JSONObject().put("b", bx(ln.box)).put("w", ws))
                }
                paras.put(JSONObject().put("b", bx(p.box)).put("l", lines))
            }
            val blockObj = JSONObject().put("b", bx(b.box)).put("p", paras)
            if (b.type != null) blockObj.put("t", b.type)
            if (b.id != null) blockObj.put("id", b.id)
            blocks.put(blockObj)
        }
        val obj = JSONObject().put("w", l.sourceW).put("h", l.sourceH).put("blocks", blocks)
        obj.put("lang", l.lang ?: "")
        return HEAD2 + obj.toString()
    }

    fun decodeLayout(payload: String): OcrLayout {
        val o = JSONObject(payload.removePrefix(HEAD2))
        val blocks = (0 until o.getJSONArray("blocks").length()).map { i ->
            val b = o.getJSONArray("blocks").getJSONObject(i)
            val pa = b.getJSONArray("p")
            val bType = b.optString("t", "").ifEmpty { null }
            val bId = b.optString("id", "").ifEmpty { null }
            CoreOcrBlock(
                box = unbx(b.getJSONArray("b")),
                paras = (0 until pa.length()).map { j ->
                    val p = pa.getJSONObject(j); val la = p.getJSONArray("l")
                    CoreOcrPara(unbx(p.getJSONArray("b")), (0 until la.length()).map { k ->
                        val l = la.getJSONObject(k); val wa = l.getJSONArray("w")
                        CoreOcrLine(unbx(l.getJSONArray("b")), (0 until wa.length()).map { m ->
                            val w = wa.getJSONArray(m)
                            CoreOcrWord(w.getString(0), Bx(w.getInt(1), w.getInt(2), w.getInt(3), w.getInt(4)), w.getDouble(5).toFloat())
                        })
                    })
                },
                type = bType,
                id = bId,
            )
        }
        val lang = o.optString("lang", "").ifEmpty { null }
        return OcrLayout(o.getInt("w"), o.getInt("h"), blocks, lang)
    }

    fun encode(text: String, layout: RecognitionLayout): String {
        return encodeLayout(layout.toOcrLayout())
    }

    fun encode(text: String, geometry: RecognitionGeometry): String {
        validate(geometry)
        val lines = mutableListOf(
            MAGIC_V1,
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

    /** Decodes V2 layout, V1 geometry, or plain legacy UTF-8 text. */
    fun decode(payload: String): DecodedRecognitionPayload {
        return when {
            payload.startsWith(HEAD2) -> {
                val body = payload.removePrefix(HEAD2).trimStart()
                if (body.startsWith("{")) {
                    val ocrLayout = decodeLayout(payload)
                    val recLayout = ocrLayout.toRecognitionLayout()
                    DecodedRecognitionPayload(
                        text = ocrLayout.toPlainText(),
                        geometry = recLayout.toLegacyGeometry(),
                        layout = recLayout,
                        ocrLayout = ocrLayout,
                    )
                } else {
                    decodeV2(payload)
                }
            }
            payload.startsWith("$MAGIC_V1\n") -> decodeV1(payload)
            else -> DecodedRecognitionPayload(payload, null, null, null)
        }
    }

    private fun decodeV2(payload: String): DecodedRecognitionPayload {
        val lines = payload.split('\n')
        require(lines.size >= 3 && lines[0] == MAGIC_V2) { "OCR layout envelope is truncated" }
        val text = lines[1].decodeBase64().toString(StandardCharsets.UTF_8)
        val header = lines[2].split('|')

        val width: Int
        val height: Int
        val version: Int
        val blockCount: Int
        val languageHints = mutableSetOf<String>()

        if (header.isNotEmpty() && header[0] == "H") {
            require(header.size >= 5) { "OCR layout header is malformed" }
            width = header[1].toInt()
            height = header[2].toInt()
            version = header[3].toInt()
            blockCount = header[4].toInt()
            if (header.size > 5 && header[5].isNotBlank()) {
                languageHints.addAll(header[5].split(',').filter { it.isNotBlank() })
            }
        } else {
            require(header.size == 4) { "OCR layout header is malformed" }
            width = header[0].toInt()
            height = header[1].toInt()
            version = header[2].toInt()
            blockCount = header[3].toInt()
        }
        require(version == 2 && blockCount >= 0) { "OCR layout header parameters are invalid" }

        var lineIndex = 3
        var quality: LayoutQuality? = null
        if (lineIndex < lines.size && lines[lineIndex].startsWith("Q|")) {
            val qFields = lines[lineIndex++].split('|')
            if (qFields.size >= 7) {
                quality = LayoutQuality(
                    meanConfidence = qFields[1].toFloatOrNull() ?: 0f,
                    lowConfidenceWordCount = qFields[2].toIntOrNull() ?: 0,
                    structureAvailable = qFields[3] == "1" || qFields[3].equals("true", ignoreCase = true),
                    language = qFields[4].ifBlank { null },
                    preprocessingProfile = qFields[5],
                    engineVersion = qFields[6],
                )
            }
        }

        val blocks = mutableListOf<OcrBlock>()
        for (b in 0 until blockCount) {
            require(lineIndex < lines.size) { "OCR layout envelope truncated at block $b" }
            val blockFields = lines[lineIndex++].split('|')
            require(blockFields.isNotEmpty() && blockFields[0] == "B") { "OCR layout block record is malformed" }

            val blockId: String
            val blockType: BlockType
            val blockBox: NormalizedBox
            val readingOrder: Int
            val childCount: Int

            if (blockFields.size == 9) {
                blockId = blockFields[1].decodeBase64().toString(StandardCharsets.UTF_8)
                blockType = try { BlockType.valueOf(blockFields[2]) } catch (_: Exception) { BlockType.UNKNOWN }
                blockBox = safeNormalizedBox(
                    blockFields[3].toFloat(),
                    blockFields[4].toFloat(),
                    blockFields[5].toFloat(),
                    blockFields[6].toFloat(),
                )
                readingOrder = blockFields[7].toInt()
                childCount = blockFields[8].toInt()
            } else if (blockFields.size == 8) {
                // Legacy V2 block line without Base64 id
                blockId = "block_${b + 1}"
                blockType = try { BlockType.valueOf(blockFields[1]) } catch (_: Exception) { BlockType.UNKNOWN }
                val l = blockFields[2].toFloat()
                val t = blockFields[3].toFloat()
                val r = blockFields[4].toFloat()
                val btm = blockFields[5].toFloat()
                blockBox = if (l <= 1f && t <= 1f && r <= 1f && btm <= 1f) {
                    safeNormalizedBox(l, t, r, btm)
                } else {
                    normalizeBox(l.toLong(), t.toLong(), r.toLong(), btm.toLong(), width, height)
                        ?: safeNormalizedBox(0f, 0f, 1f, 1f)
                }
                readingOrder = blockFields[6].toInt()
                childCount = blockFields[7].toInt()
            } else {
                throw IllegalArgumentException("OCR layout block record is malformed")
            }

            val paragraphs = mutableListOf<OcrParagraph>()
            // Check if next lines are paragraphs (P) or lines (L)
            if (lineIndex < lines.size && lines[lineIndex].startsWith("P|")) {
                for (p in 0 until childCount) {
                    require(lineIndex < lines.size) { "OCR layout envelope truncated at paragraph $p" }
                    val pFields = lines[lineIndex++].split('|')
                    require(pFields.size == 6 && pFields[0] == "P") { "OCR layout paragraph record is malformed" }
                    val parBox = safeNormalizedBox(
                        pFields[1].toFloat(),
                        pFields[2].toFloat(),
                        pFields[3].toFloat(),
                        pFields[4].toFloat(),
                    )
                    val lineCount = pFields[5].toInt()
                    val parLines = decodeLines(lines, lineIndex, lineCount, width, height)
                    lineIndex += countLinesOffset(lines, lineIndex, lineCount)
                    paragraphs += OcrParagraph(parBox, parLines)
                }
            } else {
                // Lines directly under block
                val blockLines = decodeLines(lines, lineIndex, childCount, width, height)
                lineIndex += countLinesOffset(lines, lineIndex, childCount)
                paragraphs += OcrParagraph(blockBox, blockLines)
            }

            blocks += OcrBlock(
                id = blockId,
                type = blockType,
                box = blockBox,
                readingOrder = readingOrder,
                paragraphs = paragraphs,
            )
        }
        require(lineIndex == lines.size) { "OCR layout envelope has unexpected trailing lines" }

        val layout = RecognitionLayout(
            sourceWidthPx = width,
            sourceHeightPx = height,
            blocks = blocks,
            languageHints = languageHints,
            quality = quality,
            formatVersion = version,
        )
        validate(layout)
        return DecodedRecognitionPayload(text, layout.toLegacyGeometry(), layout)
    }

    private fun decodeLines(
        lines: List<String>,
        startIndex: Int,
        lineCount: Int,
        width: Int,
        height: Int,
    ): List<OcrLine> {
        var idx = startIndex
        val result = mutableListOf<OcrLine>()
        for (l in 0 until lineCount) {
            require(idx < lines.size) { "OCR layout envelope truncated at line $l" }
            val lineFields = lines[idx++].split('|')
            require(lineFields.isNotEmpty() && lineFields[0] == "L") { "OCR layout line record is malformed" }

            val lineBox: NormalizedBox
            val baseline: Baseline?
            val xHeight: Float?
            val wordCount: Int

            if (lineFields.size == 11) {
                lineBox = safeNormalizedBox(
                    lineFields[1].toFloat(),
                    lineFields[2].toFloat(),
                    lineFields[3].toFloat(),
                    lineFields[4].toFloat(),
                )
                val hasBaseline = lineFields[5].toInt() == 1
                baseline = if (hasBaseline) Baseline(lineFields[6].toFloat(), lineFields[7].toFloat()) else null
                val hasXHeight = lineFields[8].toInt() == 1
                xHeight = if (hasXHeight) lineFields[9].toFloat() else null
                wordCount = lineFields[10].toInt()
            } else if (lineFields.size == 9) {
                // Older line format
                val lVal = lineFields[1].toFloat()
                val tVal = lineFields[2].toFloat()
                val rVal = lineFields[3].toFloat()
                val bVal = lineFields[4].toFloat()
                lineBox = if (lVal <= 1f && tVal <= 1f && rVal <= 1f && bVal <= 1f) {
                    safeNormalizedBox(lVal, tVal, rVal, bVal)
                } else {
                    normalizeBox(lVal.toLong(), tVal.toLong(), rVal.toLong(), bVal.toLong(), width, height)
                        ?: safeNormalizedBox(0f, 0f, 1f, 1f)
                }
                val hasBaseline = lineFields[5].toInt() == 1
                baseline = if (hasBaseline) Baseline(lineFields[6].toFloat(), lineFields[7].toFloat()) else null
                xHeight = null
                wordCount = lineFields[8].toInt()
            } else {
                throw IllegalArgumentException("OCR layout line record is malformed: ${lineFields.size} fields")
            }

            val words = mutableListOf<OcrWord>()
            for (w in 0 until wordCount) {
                require(idx < lines.size) { "OCR layout envelope truncated at word $w" }
                val wordFields = lines[idx++].split('|')
                require(wordFields.size == 7 && wordFields[0] == "W") { "OCR layout word record is malformed" }
                val wordText = wordFields[1].decodeBase64().toString(StandardCharsets.UTF_8)
                val wl = wordFields[2].toFloat()
                val wt = wordFields[3].toFloat()
                val wr = wordFields[4].toFloat()
                val wb = wordFields[5].toFloat()
                val wordBox = if (wl <= 1f && wt <= 1f && wr <= 1f && wb <= 1f) {
                    safeNormalizedBox(wl, wt, wr, wb)
                } else {
                    normalizeBox(wl.toLong(), wt.toLong(), wr.toLong(), wb.toLong(), width, height)
                        ?: safeNormalizedBox(0f, 0f, 1f, 1f)
                }
                val confidence = wordFields[6].toFloat().coerceIn(0f, 100f)
                words += OcrWord(wordText, wordBox, confidence)
            }
            result += OcrLine(lineBox, baseline, xHeight, words)
        }
        return result
    }

    private fun countLinesOffset(lines: List<String>, startIndex: Int, lineCount: Int): Int {
        var idx = startIndex
        for (l in 0 until lineCount) {
            val lineFields = lines[idx++].split('|')
            val wordCount = lineFields.last().toInt()
            idx += wordCount
        }
        return idx - startIndex
    }

    private fun decodeV1(payload: String): DecodedRecognitionPayload {
        val lines = payload.split('\n')
        require(lines.size >= 3 && lines[0] == MAGIC_V1) { "OCR geometry envelope is truncated" }
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
        val text = lines[1].decodeBase64().toString(StandardCharsets.UTF_8)
        val synthesizedLayout = if (words.isNotEmpty()) {
            val ocrWords = words.mapNotNull { w ->
                val box = normalizeBox(w.leftPx, w.topPx, w.rightPx, w.bottomPx, width.toInt(), height.toInt())
                if (box != null) OcrWord(w.text, box, w.confidence.toFloat()) else null
            }
            if (ocrWords.isNotEmpty()) {
                val minLeft = ocrWords.minOf { it.box.left }
                val minTop = ocrWords.minOf { it.box.top }
                val maxRight = ocrWords.maxOf { it.box.right }
                val maxBottom = ocrWords.maxOf { it.box.bottom }
                val lineBox = NormalizedBox(minLeft, minTop, maxRight, maxBottom)
                val line = OcrLine(lineBox, baseline = null, xHeight = null, words = ocrWords)
                val paragraph = OcrParagraph(lineBox, listOf(line))
                val block = OcrBlock(
                    id = "block_1",
                    type = BlockType.PARAGRAPH,
                    box = lineBox,
                    readingOrder = 0,
                    paragraphs = listOf(paragraph),
                )
                val meanConf = ocrWords.map { it.confidence }.average().toFloat()
                val lowConfCount = ocrWords.count { it.confidence < 70f }
                RecognitionLayout(
                    sourceWidthPx = width.toInt(),
                    sourceHeightPx = height.toInt(),
                    blocks = listOf(block),
                    quality = LayoutQuality(
                        meanConfidence = meanConf,
                        lowConfidenceWordCount = lowConfCount,
                        structureAvailable = false,
                    ),
                    formatVersion = 2,
                )
            } else {
                RecognitionLayout(
                    sourceWidthPx = width.toInt(),
                    sourceHeightPx = height.toInt(),
                    blocks = emptyList(),
                    quality = LayoutQuality(0f, 0, structureAvailable = false),
                    formatVersion = 2,
                )
            }
        } else {
            RecognitionLayout(
                sourceWidthPx = width.toInt(),
                sourceHeightPx = height.toInt(),
                blocks = emptyList(),
                quality = LayoutQuality(0f, 0, structureAvailable = false),
                formatVersion = 2,
            )
        }
        return DecodedRecognitionPayload(text, geometry, null)
    }

    private fun validate(layout: RecognitionLayout) {
        require(layout.sourceWidthPx > 0 && layout.sourceHeightPx > 0) { "OCR source dimensions must be positive" }
        require(layout.formatVersion == 2) { "Unsupported layout format version: ${layout.formatVersion}" }
        layout.blocks.forEach { block ->
            require(block.readingOrder >= 0) { "Block reading order must be non-negative" }
            block.paragraphs.forEach { par ->
                par.lines.forEach { line ->
                    line.words.forEach { word ->
                        require(word.text.isNotBlank()) { "Word text must not be blank" }
                        require(word.confidence.isFinite() && word.confidence in 0f..100f) { "Word confidence invalid" }
                    }
                }
            }
        }
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

    private fun safeNormalizedBox(l: Float, t: Float, r: Float, b: Float): NormalizedBox {
        val left = l.coerceIn(0f, 1f)
        val top = t.coerceIn(0f, 1f)
        val right = r.coerceIn(left, 1f)
        val bottom = b.coerceIn(top, 1f)
        return NormalizedBox(left, top, right, bottom)
    }

    private fun ByteArray.encodeBase64(): String = encoder.encodeToString(this)

    private fun String.decodeBase64(): ByteArray = decoder.decode(this)
}

