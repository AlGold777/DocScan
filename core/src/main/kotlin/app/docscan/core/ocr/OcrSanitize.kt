package app.docscan.core.ocr

private val INVISIBLE = Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD\\p{Cntrl}]")
fun sanitizeOcrText(s: String): String = s.replace(INVISIBLE, "").trim()

data class Bx(val l: Int, val t: Int, val r: Int, val b: Int)

fun clampBox(l: Int, t: Int, r: Int, b: Int, w: Int, h: Int): Bx? {
    if (w <= 1 || h <= 1) return null
    val cl = l.coerceIn(0, w - 1)
    val ct = t.coerceIn(0, h - 1)
    val cr = r.coerceIn(cl + 1, w)
    val cb = b.coerceIn(ct + 1, h)
    return Bx(cl, ct, cr, cb)
}
