package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Debug
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class OcrFixture(val id: String, val truth: String, val draw: (Canvas, Paint) -> Unit)

internal data class OcrMeasurement(val elapsedMs: Long, val peakPssKb: Long)

internal object OcrQualityTestSupport {
    const val WIDTH = 1600
    const val HEIGHT = 2100

    val fixtures = listOf(
        OcrFixture("ru", "Документ № 8472\nПроверка качества текста\nСумма 1250,50 руб.") { c, p ->
            lines(c, p, listOf("Документ № 8472", "Проверка качества текста", "Сумма 1250,50 руб."), 105)
        },
        OcrFixture("en", "Invoice 504821\nCustomer: Anna Smith\nTotal: 127.45 USD") { c, p ->
            lines(c, p, listOf("Invoice 504821", "Customer: Anna Smith", "Total: 127.45 USD"), 105)
        },
        OcrFixture("es", "Mañana acción\nNiña café pingüino\nNúmero 4821 ¿Sí? ¡Claro!") { c, p ->
            lines(c, p, listOf("Mañana acción", "Niña café pingüino", "Número 4821 ¿Sí? ¡Claro!"), 105)
        },
        OcrFixture("numbers", "INV-2026/10\n+34 612 345 678\n€ 1.234,56  98.7%") { c, p ->
            lines(c, p, listOf("INV-2026/10", "+34 612 345 678", "€ 1.234,56  98.7%"), 105)
        },
        OcrFixture("shadow", "RECEIPT 8831\nCoffee 2.50\nTOTAL 2.50 EUR") { c, p ->
            c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, WIDTH.toFloat(), 0f, Color.rgb(122, 122, 122), Color.WHITE, Shader.TileMode.CLAMP)
            })
            lines(c, p, listOf("RECEIPT 8831", "Coffee 2.50", "TOTAL 2.50 EUR"), 100)
        },
        OcrFixture("small", "Small print 2026\nKeep this receipt\nReference 008412") { c, p ->
            lines(c, p, listOf("Small print 2026", "Keep this receipt", "Reference 008412"), 28)
        },
        OcrFixture("skew", "DOCUMENT ANGLE\nRotate five degrees\nNumber 7319") { c, p ->
            c.save(); c.rotate(-5f, WIDTH / 2f, HEIGHT / 2f)
            lines(c, p, listOf("DOCUMENT ANGLE", "Rotate five degrees", "Number 7319"), 100)
            c.restore()
        },
        OcrFixture("columns", "LEFT ALPHA 101\nLEFT BETA 202\nRIGHT GAMMA 303\nRIGHT DELTA 404") { c, p ->
            p.textSize = 54f
            listOf("LEFT ALPHA 101", "LEFT BETA 202", "RIGHT GAMMA 303", "RIGHT DELTA 404").forEachIndexed { i, s ->
                c.drawText(s, if (i < 2) 80f else 850f, if (i % 2 == 0) 420f else 540f, p)
            }
        },
        OcrFixture("receipt", "STORE MARKET\nMilk 2.49\nBread 1.25\nTOTAL 3.74\nTHANK YOU") { c, p ->
            lines(c, p, listOf("STORE MARKET", "Milk 2.49", "Bread 1.25", "TOTAL 3.74", "THANK YOU"), 82)
        },
        OcrFixture("blank", "") { _, _ -> },
        OcrFixture("dense", (0 until 50).joinToString("\n") { "Row $it INV ${10000 + it} TOTAL 98.76" }) { c, p ->
            p.textSize = 24f
            (0 until 50).forEach { row -> c.drawText("Row $row INV ${10000 + row} TOTAL 98.76", 70f, 300f + row * 34f, p) }
        },
        OcrFixture("mixed", "Документ Invoice 2718\nEspaña: acción, pingüino\nИтого / Total 99,95") { c, p ->
            lines(c, p, listOf("Документ Invoice 2718", "España: acción, pingüino", "Итого / Total 99,95"), 90)
        },
    )

    fun bitmap(fixture: OcrFixture): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 92f
        }
        fixture.draw(canvas, paint)
        return bitmap
    }

    fun savePng(context: Context, fixture: OcrFixture): File {
        val bitmap = bitmap(fixture)
        val file = File(context.cacheDir, "ocr-quality-${fixture.id}-${System.nanoTime()}.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
        } finally { bitmap.recycle() }
        return file
    }

    fun measure(block: () -> Unit): OcrMeasurement {
        val running = AtomicBoolean(true)
        val peak = AtomicLong(Debug.getPss().toLong())
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
                try { Thread.sleep(40) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { name = "ocr-pss-sampler"; isDaemon = true; start() }
        val started = System.nanoTime()
        try { block() } finally {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            running.set(false); sampler.join(1_000)
            peak.accumulateAndGet(Debug.getPss().toLong(), ::maxOf)
            lastMeasurement = OcrMeasurement(elapsed, peak.get())
        }
        return lastMeasurement
    }

    @Volatile private var lastMeasurement = OcrMeasurement(0, 0)

    fun cer(expected: String, actual: String): Double {
        val a = expected.filterNot(Char::isWhitespace).lowercase().toList()
        val b = actual.filterNot(Char::isWhitespace).lowercase().toList()
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1); current[0] = i + 1
            for (j in b.indices) current[j + 1] = minOf(
                current[j] + 1, previous[j + 1] + 1,
                previous[j] + if (a[i] == b[j]) 0 else 1,
            )
            previous = current
        }
        return previous.last().toDouble() / a.size
    }

    fun installBestModels(context: Context): File {
        val root = File(context.cacheDir, "tessdata-best-validation")
        val tessdata = File(root, "tessdata")
        check(tessdata.isDirectory || tessdata.mkdirs())
        val testAssets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        listOf("eng", "rus", "spa").forEach { lang ->
            val output = File(tessdata, "$lang.traineddata")
            testAssets.open("tessdata-best/$lang.traineddata").use { input ->
                FileOutputStream(output).use { it.write(input.readBytes()); it.fd.sync() }
            }
        }
        return root
    }

    private fun lines(canvas: Canvas, paint: Paint, values: List<String>, size: Int) {
        paint.textSize = size.toFloat()
        values.forEachIndexed { index, line -> canvas.drawText(line, 100f, 360f + index * (size + 80f), paint) }
    }
}
