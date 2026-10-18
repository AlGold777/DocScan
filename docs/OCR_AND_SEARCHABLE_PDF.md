# Оптическое распознавание текста (OCR) и генерация Searchable PDF 🔍📑

Документ описывает реализацию конвейера распознавания текста Tesseract и сборки двухслойных документов PDF в DocScan (актуально для версии **0.4.252**).

---

## 1. Концепция двухслойного документа (Searchable PDF)

DocScan генерирует стандартные PDF-документы формата ISO 32000-1, которые обладают свойствами:
1. **Визуальная аутентичность**: пользователь видит точный растровый снимок страницы в высоком разрешении со всеми графическими деталями, подписями и штампами.
2. **Поиск и индексация**: при выделении текста мышью/пальцем или поиске через `Ctrl+F` в любом ридере (Adobe Acrobat, Chrome, Android PDF Viewer) находится распознанный текст.

```
┌────────────────────────────────────────────────────────┐
│  Слой 2: Невидимый текстовый вектор (PDF Text Layer)    │
│  - Построчный рендеринг слов [x, y, w, h]              │
│  - Проверка глифов через кэш TrueType cmap (safeText)  │
│  - Встроенный шрифт из assets (NotoSans-Regular.ttf)   │
│  - Режим рендеринга: NEITHER_FILL_NOR_STROKE           │
├────────────────────────────────────────────────────────┤
│  Слой 1: Растровое изображение страницы (JPEG Stream)  │
│  - Исходная геометрия листа бумаги                      │
└────────────────────────────────────────────────────────┘
```

---

## 2. Конвейер распознавания текста (Tesseract 4/5)

### 2.1. Инициализация, языковые модели и автоопределение языка
* Распознавание осуществляется полностью оффлайн с использованием C++ ядра Tesseract (`tesseract4android`).
* Обученные модели (`tessdata`: `rus.traineddata`, `eng.traineddata`, `spa.traineddata`) загружаются во внутреннее закрытое хранилище приложения при первом старте. Потокобезопасная инициализация защищена мьютексом `initLock` и атомарной установкой файлов через временные файлы с thread ID.
* Языковые профили (`OcrLangProfile`):
  * `RU_EN`: `rus+eng`
  * `ES_EN`: `spa+eng`
  * `RU_ES_EN`: `rus+spa+eng`
* Режимы распознавания (`OcrLangMode`): `AUTO`, `RU_EN`, `ES_EN`, `RU_ES_EN`.
* Двухпроходное распознавание (`recognize(bitmap, mode)`):
  * В режиме `AUTO` первый проход выполняется с `rus+eng`.
  * Детектор `LangDetect.decide(text)` анализирует результат: частотность испанских стоп-слов, наличие диакритики (`ñ`, `á`, `é`, `í`, `ó`, `ú`, `ü`, `¿`, `¡`) и долю кириллицы.
  * Если обнаружен испанский язык, выполняется второй проход (`spa+eng` или `rus+spa+eng`). При сбое второго прохода или резком уменьшении числа слов автоматически сохраняется результат первого прохода.
* Параметры OCR:
  * Режим сегментации страниц (PSM): `PSM_AUTO` (автоматическое определение ориентации и разбиение на колонки).
  * DPI: `user_defined_dpi = "300"` для стабильного распознавания и выравнивания координат.
  * Межсловные пробелы: `preserve_interword_spaces = "1"`.

### 2.2. Парсер hOCR разметки (`HocrParser`) и санитайзинг (`OcrSanitize`)
Начиная с версии `0.4.251`, извлечение структуры выполняется через стандартизированный экспорт `hOCR` (`getHOCRText(0)`), обеспечивающий строго естественный порядок чтения:
* **Естественный порядок чтения**: блоки и параграфы сохраняются в порядке, возвращенном Tesseract, без искусственной сортировки по координатам.
* **Фильтрация нетекстовых блоков**: классы `ocr_photo` и `ocr_separator` автоматически отсекаются.
* **Санитайзинг строк (`sanitizeOcrText`)**: удаление невидимых управляющих символов, LTR/RTL меток, zero-width пробелов, декодирование HTML-сущностей (`&amp;`, `&quot;`, `&#39;`, `&lt;`, `&gt;`).
* **Клампинг координат (`clampBox`)**: все координаты слов и блоков ограничиваются размерами изображения `[0..width, 0..height]`. Если ширина или высота бокса становится $\le 0$, слово отбрасывается.

```kotlin
// Парсинг hOCR в иммутабельную модель OcrLayout
val ocrLayout = HocrParser.parseHocr(
    hocr = hocrString,
    sourceW = decodedBitmap.width,
    sourceH = decodedBitmap.height
)
val plainText = ocrLayout.toPlainText()
```

### 2.3. Порядок чтения текста (`toPlainText`)
Текст формируется из иерархии:
* Между блоками: два перевода строки (`\n\n`)
* Между параграфами: два перевода строки (`\n\n`)
* Между строками в параграфе: один перевод строки (`\n`)
* Между словами в строке: один пробел (` `)

---

## 3. Генерация Searchable PDF (`SearchablePdfWriter`)

Сборка PDF-файла реализована с использованием библиотеки **Apache PDFBox for Android** (порт TomRoush):

### 3.1. Размещение растрового изображения
```kotlin
val page = PDPage(PDRectangle(pageWidthPt, pageHeightPt))
document.addPage(page)

val contentStream = PDPageContentStream(document, page, AppendMode.OVERWRITE, false)

// Вставка растрового изображения во всю страницу
val pdImage = JPEGFactory.createFromImage(document, pageBitmap, 0.85f)
contentStream.drawImage(pdImage, 0f, 0f, pageWidthPt, pageHeightPt)
```

### 3.2. Наложение невидимого текстового слоя
В спецификации PDF для скрытия текста без искажения поиска используется режим отрисовки **`RenderingMode.NEITHER_FILL_NOR_STROKE`** (код режима 3):
* **Встроенный шрифт из assets**: используется `NotoSans-Regular.ttf`, загружаемый из `assets/fonts/NotoSans-Regular.ttf` ровно один раз на документ. Все нестабильные пути к системным шрифтам (`/system/fonts/Roboto-Regular.ttf` и т.д.) полностью удалены.
* **Кэш проверки глифов (`safeText`)**: перед передачей строки в PDFBox каждый code point проверяется методом `font.hasGlyph(cp)`. Результаты кэшируются на уровне документа в `HashMap<Int, Boolean>`. Неподдерживаемые глифы или невалидные суррогатные пары отфильтровываются без падений процесса.
* **Построчный рендеринг**: текст отрисовывается построчно, слово за словом. Слова с некорректной геометрией ($w \le 0$ или $h \le 0$) или отсутствующими глифами безопасно пропускаются без вызова `require(...)` и сбоев генерации.
* **Верификация после записи**: перед закрытием документа вызывается `PDFTextStripper`. Если исходный документ содержал распознанные слова, но извлеченный текст оказался пустым, записывается предупреждение в лог.

```kotlin
// Загрузка встроенного TrueType шрифта один раз на документ
val font = PDType0Font.load(document, context.assets.open("fonts/NotoSans-Regular.ttf"))
val glyphCache = HashMap<Int, Boolean>()

contentStream.beginText()
contentStream.setRenderingMode(RenderingMode.NEITHER_FILL_NOR_STROKE)

for (line in para.lines) {
    for (word in line.words) {
        val safe = font.safeText(word.text, glyphCache) ?: continue
        val wPt = word.box.w * scaleX
        val hPt = word.box.h * scaleY
        if (wPt <= 0f || hPt <= 0f) continue
        
        val fontSize = (hPt * 0.85f).coerceAtLeast(1f)
        val xPt = word.box.l * scaleX
        val yPt = pageHeightPt - (word.box.b * scaleY)
        
        contentStream.setFont(font, fontSize)
        contentStream.setTextMatrix(Matrix.getTranslateInstance(xPt, yPt))
        contentStream.showText(safe)
    }
}

contentStream.endText()
contentStream.close()
```

### 3.3. Сохранение через Storage Access Framework (SAF)
* Документ сохраняется во внешнюю общую память устройства через `content://` URI (SAF).
* Перед записью выполняется верификация разрешений: создание временного файла со случайным именем, проверка записи/чтения и последующее удаление.
* Итоговый PDF атомарно передается в целевой файл, исключая риск создания поврежденных или усеченных файлов.
* В случае несовпадения геометрических размеров растрового изображения и результатов OCR выполняется безопасное масштабирование через `OcrLayout.scaledTo(imageWidth, imageHeight)`. Если масштабирование невозможно, текстовый слой пропускается без падения приложения (`FrozenSaveOutputComposer`).

### 3.4. Режим «Только OCR без картинки» (`includeImage = false`)
Начиная с версии `0.4.254`, приложение поддерживает экспорт PDF исключительно с распознанным текстом:
* **Исключение растрового слоя**: вызов `JPEGFactory.createFromImage` и `contentStream.drawImage` полностью пропускается при выключенном флаге `JPG` (`settings.includeImage == false`).
* **Видимый рендеринг текста**: режим рендеринга переключается с невидимого `RenderingMode.NEITHER` на стандартный заливающий `RenderingMode.FILL` с чёрным цветом символов (`setNonStrokingColor(0, 0, 0)`).
* **Естественное масштабирование**: коэффициент горизонтального сжатия/растяжения слов (`horizontalScaling`) в видимом режиме мягко ограничивается диапазоном `70%..150%`, предотвращая визуальные артефакты при искажённых координатах OCR.
* **Текстовый фолбэк**: при отсутствии точной геометрии hOCR текст отрисовывается построчно со стандартным интерлиньяжем.
* **Гарантия OCR перед сохранением**: при включенном флаге `OCR` незавершённые задачи распознавания выбранных страниц синхронно ожидаются до этапа заморозки манифеста (`awaitOcrForPages`), а `commitRecognition` выполняется синхронно в потоке задачи до завершения Future, исключая гонки состояния.
* **Отказоустойчивая валидация PDF**: валидация `validatePdf` проверяет целостность и непустоту слоя распознанного текста (`extracted.isNotBlank()`) без хрупкого пословного сравнения, предотвращая сбои сохранения из-за специфических символов, различий переносов и отсутствующих в шрифте глифов.

---

## 4. Каноническая модель данных (`OcrLayout`) и сериализация (`RecognitionPayloadCodec`)

### 4.1. Модель данных `OcrLayout`
В пакете `app.docscan.core.ocr` определена каноническая модель разметки распознавания:

```kotlin
data class Bx(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w: Int get() = r - l
    val h: Int get() = b - t
}

data class OcrWord(val text: String, val box: Bx, val conf: Float = 1.0f)
data class OcrLine(val box: Bx, val words: List<OcrWord>, val baseline: List<Float>? = null)
data class OcrPara(val box: Bx, val lines: List<OcrLine>)
data class OcrBlock(val box: Bx, val paras: List<OcrPara>, val type: String = "text", val id: String? = null)

data class OcrLayout(
    val sourceW: Int,
    val sourceH: Int,
    val blocks: List<OcrBlock>,
    val lang: String = "rus+eng"
) {
    fun toPlainText(): String = ...
    fun scaledTo(w: Int, h: Int): OcrLayout? = ...
}
```

### 4.2. Сериализация в SQLite (`DOCSCAN-OCR-LAYOUT/2`)
* Поле `Recognition.payload` в таблице SQLDelight сохраняет разметку с префиксом `DOCSCAN-OCR-LAYOUT/2\n` и телом в формате компактного JSON (`org.json`).
* Поддерживается полная обратная совместимость:
  1. `DOCSCAN-OCR-LAYOUT/2`: полный иерархический макет страницы.
  2. `DOCSCAN-OCR-GEOMETRY/1`: legacy-формат плоского списка слов (декодируется в список `geometry.words` без синтетических блоков).
  3. Обычный плоский текст: восстанавливается как текст без геометрического слоя.
* Для генерации осмысленного имени файла `OutputBasenameSuggester` извлекает первую непустую строку из первого текстового блока разметки `/2`.


