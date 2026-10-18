package app.docscan.core.ocr

import app.docscan.core.store.Baseline

data class OcrWord(val text: String, val box: Bx, val conf: Float)
data class OcrLine(val box: Bx, val words: List<OcrWord>, val baseline: Baseline? = null)
data class OcrPara(val box: Bx, val lines: List<OcrLine>)
data class OcrBlock(
    val box: Bx,
    val paras: List<OcrPara>,
    val type: String? = null,
    val id: String? = null,
)
data class OcrLayout(
    val sourceW: Int,
    val sourceH: Int,
    val blocks: List<OcrBlock>,
    val lang: String? = null,
) {
    val words: List<OcrWord>
        get() = blocks.flatMap { b -> b.paras.flatMap { p -> p.lines.flatMap { it.words } } }
}

fun OcrLayout.toPlainText(): String =
    blocks.joinToString("\n\n") { b ->
        b.paras.joinToString("\n\n") { p ->
            p.lines.joinToString("\n") { l -> l.words.joinToString(" ") { it.text } }
        }
    }

fun OcrLayout.toRecognitionLayout(): app.docscan.core.store.RecognitionLayout {
    val w = maxOf(1, sourceW)
    val h = maxOf(1, sourceH)
    val recBlocks = blocks.mapIndexed { bIdx, b ->
        val bLeft = (b.box.l.toFloat() / w).coerceIn(0f, 1f)
        val bTop = (b.box.t.toFloat() / h).coerceIn(0f, 1f)
        val bRight = (b.box.r.toFloat() / w).coerceIn(bLeft, 1f)
        val bBottom = (b.box.b.toFloat() / h).coerceIn(bTop, 1f)
        val blockType = when (b.type) {
            "HEADER" -> app.docscan.core.store.BlockType.HEADER
            "FOOTER" -> app.docscan.core.store.BlockType.FOOTER
            "CAPTION" -> app.docscan.core.store.BlockType.CAPTION
            "TITLE" -> app.docscan.core.store.BlockType.TITLE
            "PARAGRAPH" -> app.docscan.core.store.BlockType.PARAGRAPH
            else -> app.docscan.core.store.BlockType.TEXT
        }
        val paras = b.paras.map { p ->
            val pLeft = (p.box.l.toFloat() / w).coerceIn(0f, 1f)
            val pTop = (p.box.t.toFloat() / h).coerceIn(0f, 1f)
            val pRight = (p.box.r.toFloat() / w).coerceIn(pLeft, 1f)
            val pBottom = (p.box.b.toFloat() / h).coerceIn(pTop, 1f)
            val lines = p.lines.map { l ->
                val lLeft = (l.box.l.toFloat() / w).coerceIn(0f, 1f)
                val lTop = (l.box.t.toFloat() / h).coerceIn(0f, 1f)
                val lRight = (l.box.r.toFloat() / w).coerceIn(lLeft, 1f)
                val lBottom = (l.box.b.toFloat() / h).coerceIn(lTop, 1f)
                val words = l.words.map { word ->
                    val wLeft = (word.box.l.toFloat() / w).coerceIn(0f, 1f)
                    val wTop = (word.box.t.toFloat() / h).coerceIn(0f, 1f)
                    val wRight = (word.box.r.toFloat() / w).coerceIn(wLeft, 1f)
                    val wBottom = (word.box.b.toFloat() / h).coerceIn(wTop, 1f)
                    app.docscan.core.store.OcrWord(
                        text = word.text,
                        box = app.docscan.core.store.NormalizedBox(wLeft, wTop, wRight, wBottom),
                        confidence = word.conf,
                    )
                }
                app.docscan.core.store.OcrLine(
                    box = app.docscan.core.store.NormalizedBox(lLeft, lTop, lRight, lBottom),
                    baseline = l.baseline,
                    xHeight = null,
                    words = words,
                )
            }
            app.docscan.core.store.OcrParagraph(
                box = app.docscan.core.store.NormalizedBox(pLeft, pTop, pRight, pBottom),
                lines = lines,
            )
        }
        app.docscan.core.store.OcrBlock(
            id = b.id ?: "block_${bIdx + 1}",
            type = blockType,
            box = app.docscan.core.store.NormalizedBox(bLeft, bTop, bRight, bBottom),
            readingOrder = bIdx,
            paragraphs = paras,
        )
    }
    return app.docscan.core.store.RecognitionLayout(
        sourceWidthPx = w,
        sourceHeightPx = h,
        blocks = recBlocks,
        quality = app.docscan.core.store.LayoutQuality(80f, 0, structureAvailable = true, language = lang),
        formatVersion = 2,
    )
}

fun app.docscan.core.store.RecognitionLayout.toOcrLayout(): OcrLayout {
    val w = sourceWidthPx
    val h = sourceHeightPx
    fun nb(box: app.docscan.core.store.NormalizedBox): Bx {
        val l = (box.left * w).toInt().coerceIn(0, maxOf(0, w - 1))
        val t = (box.top * h).toInt().coerceIn(0, maxOf(0, h - 1))
        val r = kotlin.math.ceil(box.right * w).toInt().coerceIn(l + 1, maxOf(l + 1, w))
        val b = kotlin.math.ceil(box.bottom * h).toInt().coerceIn(t + 1, maxOf(t + 1, h))
        return Bx(l, t, r, b)
    }
    val ocrBlocks = blocks.sortedBy { it.readingOrder }.map { b ->
        val paras = b.paragraphs.map { p ->
            val lines = p.lines.map { l ->
                val words = l.words.map { w ->
                    OcrWord(w.text, nb(w.box), w.confidence)
                }
                OcrLine(nb(l.box), words, l.baseline)
            }
            OcrPara(nb(p.box), lines)
        }
        OcrBlock(nb(b.box), paras, b.type.name, b.id)
    }
    return OcrLayout(w, h, ocrBlocks, quality?.language)
}

fun OcrLayout.scaledTo(w: Int, h: Int): OcrLayout? {
    if (sourceW <= 0 || sourceH <= 0 || w <= 0 || h <= 0) return null
    val diff = kotlin.math.abs((sourceW.toDouble() / sourceH) / (w.toDouble() / h) - 1.0)
    if (diff > 0.01) return null              // другое изображение
    val fx = w.toDouble() / sourceW; val fy = h.toDouble() / sourceH
    fun s(b: Bx) = clampBox((b.l * fx).toInt(), (b.t * fy).toInt(), (b.r * fx).toInt(), (b.b * fy).toInt(), w, h)
        ?: Bx(0, 0, maxOf(1, w), maxOf(1, h))
    return OcrLayout(
        sourceW = w,
        sourceH = h,
        blocks = blocks.map { bl ->
            OcrBlock(
                box = s(bl.box),
                paras = bl.paras.map { p ->
                    OcrPara(
                        box = s(p.box),
                        lines = p.lines.map { l ->
                            OcrLine(
                                box = s(l.box),
                                words = l.words.map { it.copy(box = s(it.box)) },
                                baseline = l.baseline,
                            )
                        },
                    )
                },
                type = bl.type,
                id = bl.id,
            )
        },
        lang = lang,
    )
}

