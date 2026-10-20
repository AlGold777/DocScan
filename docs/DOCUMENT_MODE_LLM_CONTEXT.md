# DocScan: текущая реализация режима «Документ» и контекст для технического исследования

Дата снимка: **20 октября 2026 года**. Язык приложения: Kotlin, платформа Android.

Документ предназначен для передачи моделям и инженерам, которым нужно исследовать качество определения границ документа («зелёные линии»), сравнить подходы и предложить развитие системы. Он содержит описание фактического кода и автономное приложение с исходниками. Доступ к репозиторию для чтения основных компонентов не обязателен.

Это описание реализации, а не отчёт о новом прогоне приложения и не исследование рынка. Сведения о лучших решениях на октябрь 2026 года здесь не утверждаются: их предстоит проверить по внешним источникам. Наблюдения из кода и гипотезы о причинах ошибок разделены.

## 1. Задание модели, получающей этот документ

Есть серьёзные вопросы к качеству режима «Документ», прежде всего к точности определения границ и поведению зелёного контура. Пользователь не предоставил в этой задаче размеченные примеры конкретных ошибок, видео или измерения задержки. Не придумывай их.

Нужно выйти за рамки локального code review:

1. Изучить лучшие доступные решения по состоянию на октябрь 2026 года: приложения как ориентиры качества, открытые реализации, научные методы, готовые SDK, нейросетевые и классические алгоритмы. Для каждого внешнего утверждения указать проверяемый источник и дату; закрытую архитектуру приложений не выдавать за известную.
2. Разобрать текущую реализацию из этого документа: детекцию, ранжирование, уточнение углов, стабильность между кадрами, геометрию камеры, решение об обрезке JPEG и перспективное преобразование.
3. Сравнить четыре пути: улучшить текущую систему; заменить отдельные алгоритмы готовыми решениями; **переписать отдельные компоненты под наши требования, сохранив удачные части**; переработать архитектуру целиком, если это оправдано.
4. Объяснить, можно ли довести текущую систему до уровня лучших решений. По каждому компоненту рекомендовать: сохранить, доработать, заменить или переписать под себя. Не объявлять ни сохранение, ни переписывание заранее правильным ответом.
5. Предложить проверяемый план: набор данных, эталоны, метрики, сравнительные эксперименты, стоимость разработки, производительность Android, память, размер приложения, лицензии, поддержка и риски внедрения. Отдельно выяснить требования к офлайн-работе, устройствам и допустимым SDK, если они влияют на выбор.

Нужен анализ и предложение решения. Этот запрос не разрешает автоматически менять код, внедрять SDK, обучать модель или переписывать приложение. Ограничения старого задания в `DOCUMENT_MODE_IMPLEMENTATION_SPEC.md` (например, исключение нейросети из того этапа) не являются запретом рассматривать новые подходы в нынешнем исследовании.

## 2. Какую версию описывает документ

- Рабочий каталог: `/Users/restart/Documents/GitHub/DocScan`.
- Ветка на момент подготовки: `codex/ocr-quality`.
- `app/build.gradle.kts`: `versionName = "0.4.279"`, `versionCode = 398`.
- Базовый HEAD: `73633cf364100b2a57a5d4941dbba9bda062b9da` (коммит с описанием выпуска 0.4.273).
- Рабочее дерево содержит многочисленные изменения, включая `ProductActivity.kt`, `ProductStorage`-зависимые сценарии интерфейса, PDF, StateStore, документацию и артефакты сборки. Документ читает **фактические файлы рабочего дерева**, а не только HEAD. Один SHA коммита не восстанавливает этот снимок.
- Ниже приведены SHA-256 включённых файлов. Отдельные отрывки Activity/Storage сопровождаются диапазонами строк; SHA относится к полному файлу. Номера строк действуют только для этого снимка.
- Код при подготовке документа не меняется; версия приложения и APK не обновляются из-за создания документа.

## 3. Технологии и границы системы

| Область | Фактическая реализация |
| --- | --- |
| Android | minSdk 29, targetSdk/compileSdk 35; JVM target 17 |
| Камера | CameraX core/camera2/lifecycle/view 1.3.4; задняя камера |
| Компьютерное зрение | `org.opencv:opencv:4.14.0`, загружается через `OpenCVLoader.initLocal()` с fallback `System.loadLibrary` |
| UI | Android Views, `PreviewView`, собственные `DocumentOverlayView` и `CropSelectionView` |
| Декодирование | Android `ImageDecoder`, программный mutable Bitmap, sRGB; EXIF применяется декодером |
| Геометрическая модель | Один выпуклый четырёхугольник; четыре угла в порядке TL/TR/BR/BL |
| Хранилище/версии | `ProductStorage`, `StateStore`, артефакты изображений и `ImageVersionId` |
| OCR | Tesseract4Android 4.9.0; последующий потребитель изображения, не источник live-границ |
| PDF | pdfbox-android 2.0.27.0; не участвует в поиске зелёного контура |
| Release | `isMinifyEnabled = false`, ABI release ограничен arm64-v8a |

В цепочке определения границ используются классические OpenCV-операции. В приведённой реализации нет обучаемого детектора, сегментационной сети, optical flow или модели формы изогнутого листа. Категории PAGE/RECEIPT определяются геометрией, а не распознаванием содержимого. Один вызов детектора возвращает максимум один кандидат.

## 4. Карта компонентов

Все Android-файлы ниже находятся в `app/src/main/kotlin/app/docscan/android/`, если не указано другое.

| Компонент | Ответственность |
| --- | --- |
| `ProductActivity.kt` | Настройка CameraX, анализ кадров, перенос на превью, затвор, обработка JPEG, окна редактирования |
| `DocumentCropProcessor.kt` | Карты признаков, кандидаты, эвристический score, Hough fallback, fitLine, warp |
| `DocumentLiveDetectionCoordinator.kt` | Поколения, актуальность, подтверждение наблюдений, EMA, HELD и snapshot |
| `CameraGeometryTransform.kt` | cropRect, поворот, нормализация ViewPort, проверка aspect JPEG |
| `QuadGeometryUtils.kt` | Канонизация углов, пересечение полигонов, IoU, согласование |
| `DocumentOverlayView.kt` | Только рисование уже подготовленных экранных точек |
| `DocumentCapturePolicy.kt` | Допустить/отклонить still-контур; live не заменяет still |
| `DocumentCropMemoryCalculator.kt` | Подбор разрешения warp и оценка рабочего набора |
| `ProductImageDecoder.kt` | Ограниченное декодирование и EXIF |
| `DocumentCropPlan.kt` | Неизменяемый план автообрезки редактора |
| `ProductStorage.kt` | Превью плана, проверка актуальности, применение и новая версия изображения |
| `CropSelectionView.kt` | Интерактивные углы/рёбра ручного кадрирования |
| `QuadTemporalFilter.kt` | Старый отдельный фильтр; текущая live-цепочка Activity использует координатор |
| `core/.../edit/CropQuadrilateral.kt` | Нормализованные точки, выпуклость, перемещение рёбер, поворот |
| `core/.../schedule/HeavyWorkScheduler.kt` | Допуск тяжёлых фоновых операций по памяти и числу заданий |

## 5. Live-цепочка: от камеры до зелёной рамки

```text
CameraX ImageAnalysis (RGBA, полное изображение)
  → ограничение частоты по времени начала анализа
  → AnalysisBufferSpec(width, height, cropRect, rotation)
  → DocumentCropProcessor.detectCornersFromRgbaBuffer
  → Detection(points в пикселях полного буфера, score)
  → DocumentLiveDetectionCoordinator
       нормализованный ViewPort, подтверждение, EMA, TTL, generation
  → подтверждённые/удержанные точки в пикселях полного буфера
  → CoordinateTransform(source OutputTransform → PreviewView.outputTransform)
  → DocumentOverlayView.setPolygonPixels
```

### 5.1. Камера и потоки

`startCamera()` создаёт Preview, ImageCapture в режиме `CAPTURE_MODE_MINIMIZE_LATENCY` и ImageAnalysis. Для анализа запрашивается 1280×960 с `FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER`: фактическое разрешение не гарантировано. Формат `OUTPUT_IMAGE_FORMAT_RGBA_8888`; берётся plane 0, его ByteBuffer и rowStride. OpenCV Mat оборачивает буфер с этим шагом строки, анализ завершается до `imageProxy.close()`.

Анализатор работает в отдельном single-thread executor, стратегия backpressure — `KEEP_ONLY_LATEST`. Кадры, пришедшие раньше чем через 100 мс после предыдущего начала анализа, закрываются без детекции. Это верхний ориентир около 10 запусков в секунду, а не измеренные 10 FPS: сам алгоритм может работать дольше.

PreviewView использует `FILL_CENTER`. Если `previewView.viewPort` доступен, три use case объединяются через `UseCaseGroup.setViewPort`. Если он null, привязка выполняется без общего ViewPort. Фабрика source-transform настроена с `isUsingRotationDegrees = false`, `isUsingCropRect = false`, поскольку передаются точки полного неповёрнутого analysis-буфера.

Обновление UI выполняется через `runOnUiThread`; перед рисованием проверяются режим, поколение и жизненный цикл. Изменение размеров PreviewView, остановка Activity и переключение режима инвалидируют состояние. Изменение buffer/cropRect/rotation учитывает координатор.

### 5.2. Временная метка и актуальность

Activity передаёт как `frameTimestampMonotonicMs` значение `SystemClock.elapsedRealtime()`, прочитанное **перед обработкой**, а не `imageInfo.timestamp` сенсора. `processedTimestampMonotonicMs` координатор получает своим clock после обработки. Это важно для исследования задержки: возраст в координаторе не включает неизвестное время между экспозицией и входом в анализатор.

TTL по умолчанию 300 мс. Слишком долгая обработка приводит к missing. Истечение отображения обрабатывается событиями анализа; отдельного таймера удаления нарисованного контура в Overlay нет. `captureSnapshot()` независимо проверяет свежесть на затворе.

### 5.3. Автомат состояний

| Состояние | Смысл | Видимость | Пригодность для JPEG agreement |
| --- | --- | --- | --- |
| IDLE | После сброса/нового поколения | Нет | Нет |
| PROVISIONAL | Один новый кандидат | Нет | Нет |
| CONFIRMED | Согласованный кандидат подтверждён предыдущим свежим наблюдением | Да | Да, при свежести и совместимости |
| HELD | Сохраняется прежний подтверждённый контур при пропуске/скачке | Да, полупрозрачно | Нет |
| EXPIRED | Состояние утратило актуальность | Нет после обработки UI-события | Нет |

Для сравнения последовательных наблюдений используются default-пороги `checkAgreement`: IoU ≥ 0.80 и максимальная ошибка угла ≤ 0.06 диагонали. Диагональ и расстояния вычисляются в нормализованном пространстве x/y, а не в изотропных пикселях.

Два новых согласованных наблюдения B могут заменить прежний контур A. Missing разрывает provisional-последовательность. Для принятого кандидата EMA сопоставляет циклические индексы углов и использует alpha 0.18 при displacement < 0.015, 0.30 при < 0.04, иначе 0.50. Это относительное смещение из agreement, не скорость в пикселях/секунду.

Важно: `QuadTemporalFilter` имеет другие параметры (включая удержание на три пропуска), но **не является фильтром текущей live-цепочки**. Нельзя переносить его параметры на описываемую камеру.

### 5.4. Рисование

Overlay рисует замкнутый Path, зелёную заливку, контур 3 dp и угловые круги радиусом 5 dp. Цвет линии `#22C55E`. Для HELD alpha линии 110 вместо 255, заливки 15 вместо 36. Overlay не ищет границы, не выбирает кандидата и не сглаживает точки.

## 6. Детектор OpenCV: фактический алгоритм

### 6.1. Размер и подготовка

Детектор уменьшает изображение: `ds = min(1, 720 / max(width, height))`; resize INTER_AREA, затем RGB(A)→GRAY и GaussianBlur 5×5. Детекция live и JPEG использует один алгоритм, но получает разные изображения.

### 6.2. Три карты кандидатов

1. **boundaryEdges**: исходный Canny по blur. Otsu даёт уровень яркости; low = max(20, Otsu×0.45), high = max(low+20, Otsu×1.20), aperture 3, L2gradient true. Карта сохраняется до морфологического объединения.
2. **edgeMask**: Canny OR морфологический градиент adaptiveThreshold. Adaptive Gaussian, THRESH_BINARY, C=7, нечётный блок около 10% короткой стороны с ограничением 31–101. Градиент ядром 5×5; CLOSE 5×5 дважды, затем dilate 3×3.
3. **paperMask**: RGB→HSV. Канал V сглаживается 9×9 и бинаризуется Otsu; S ≤ 70; маски объединяются AND. CLOSE 15×15, OPEN 7×7, заполнение внешних контуров. Это эвристика «светлое и малонасыщенное», а не семантическая маска бумаги.

### 6.3. Кандидаты

Для каждой карты: `findContours(RETR_LIST, CHAIN_APPROX_SIMPLE)`, площадь строго между 1.5% и 99% кадра, сортировка по площади, первые 15. Затем convex hull; perimeter не меньше 0.45 короткой стороны кадра. `approxPolyDP` ищет четыре вершины 12 итерациями в диапазоне epsilon 0.005–0.08 периметра. При четырёх вершинах поиск продолжается: это не ранний выход на первом успехе.

Если аппроксимация не дала quad, используется minAreaRect только при `hullArea / rectArea ≥ 0.85`. Упорядочивание углов сначала по суммам/разностям, при совпадениях — по углам вокруг центра с началом у минимального x+y.

### 6.4. Геометрические отсечки и категории

- Четыре различные вершины, выпуклость; площадь quad от 1.5% до 99% кадра.
- Каждая сторона ≥20 px на уменьшенной копии; углы 35–145°.
- Aspect = отношение средних длин противоположных сторон; aspect >13 отклоняется.
- PAGE при aspect ≤2.8; дополнительно площадь ≥5% кадра, среднее отношение длин противоположных сторон ≥0.50.
- RECEIPT при aspect >2.8; отношение длин длинных противоположных сторон ≥0.65, длинная сторона ≥35% длинной стороны кадра.

Числа в пикселях относятся к копии до 720 px, поэтому минимальный физический размер документа зависит от масштаба кадра.

### 6.5. Скоринг

| Признак | PAGE | RECEIPT |
| --- | --- | --- |
| Площадь | 0.15 | 0.10 |
| Углы около 90° | 0.22 | 0.18 |
| Параллельность/отношения противоположных сторон | 0.14 | 0.20 |
| Предпочтительный aspect | 0.12 | 0.14 |
| Поддержка Canny вдоль сторон | 0.15 | 0.20 |
| Контраст с внешним кольцом | 0.12 | 0.12 |
| Близость к центру кадра | 0.10 | 0.06 |
| Вычитаемый штраф границы кадра | 0.20 | 0.15 |

Из baseScore дополнительно вычитается `0.30 × (1 − paperCoverage)`. Paper coverage — средняя бинарная маска внутри quad.

Угловой score = exp(−среднее |angle−90| / 24). Edge support проверяет 41 позицию на каждой стороне и наличие Canny в окрестности ±2 px, пропуская точки около края изображения. Контраст — абсолютная разница средней яркости внутри и в кольце dilation 21×21, делённая на 50 и ограниченная [0,1]. Border penalty — доля углов в пределах примерно 1 px от границы.

PAGE: areaScore достигает 1 от площади 15% до 88%, затем плавно уменьшается; aspect до 1.8 имеет максимальный score. RECEIPT: areaScore ограничен снизу 0.5, аспект до 8 даёт 0.90, до 12 снижается до 0.70, после 12 даёт 0.5. Все точные формулы сохранены в приложении.

Из всех карт выбирается единственный максимальный score. Итоговый порог `MIN_SCORE = 0.45`. Score — сумма эвристик, не калиброванная вероятность правильной границы.

### 6.6. Hough fallback

Запускается только если лучший контур отсутствует или score <0.45. `HoughLinesP(boundaryEdges, rho=1, theta=π/180, threshold=35, minLineLength=30, maxLineGap=10)`.

Рассматриваются первые 80 сегментов; длина ≥25. Почти горизонтальные: <25° или >155°; почти вертикальные: 65–115°. Выбираются крайние top/bottom/left/right, пересечения дают quad. Проверяются выпуклость и общий score. Локальный порог fallback 0.40 не отменяет общий порог возврата 0.45.

### 6.7. fitLine и возврат

После выбора кандидата стороны уточняются по boundaryEdges: точки в полосе ±5 px, вдоль 5–95% стороны с шагом 2 px; при ≥15 точках `fitLine(DIST_L2)`. Пересечения соседних прямых дают новые углы.

При плохих пересечениях, чрезмерном смещении, невыпуклости, сторонах <15 px, изменении площади за пределы 70–130% исходной возвращаются исходные точки. Есть дополнительные ограничения смещения относительно стороны и диагонали. Новый score после fitLine не рассчитывается: возвращается score исходного кандидата.

Координаты умножаются на 1/ds и ограничиваются [0,width−1]/[0,height−1]. `Detection.toNormalized()` делит x на width и y на height. Warp затем умножает нормализованные точки на width−1/height−1. Этот контракт следует проверить при оценке субпиксельной точности; он не является тождественным обратным преобразованием.

## 7. Пространства координат и соглашения

Различаются полный analysis-буфер, его cropRect, ориентированный нормализованный ViewPort, пиксели PreviewView, ориентированный JPEG, уменьшенная копия JPEG и результат warp.

В `CameraGeometryTransform`:

```text
u = (px − cropRect.left) / cropRect.width
v = (py − cropRect.top) / cropRect.height
rotation 0:   (u, v)
rotation 90:  (1−v, u)
rotation 180: (1−u, 1−v)
rotation 270: (v, 1−u)
```

Пример: буфер 1280×960, cropRect=(160,0,1120,960), точка (320,240), rotation=0 → (1/6,1/4), а не (1/4,1/4).

`NormalizedCropPoint` допускает только конечные координаты [0,1]; quad требует невырожденную выпуклость. Кандидат за пределами cropRect может вызвать отказ построения нормализованной рамки; координатор преобразует IllegalArgumentException в missing.

Совместимость live/JPEG проверяется относительным расхождением aspect ≤3%. Это проверка размеров, а не полная калибровка поля зрения. Совпадение aspect само по себе не доказывает равенство физической области, особенно при ветке bind без общего ViewPort. Фактическое совмещение на устройстве нужно измерять отдельно.

`checkAgreement` вычисляет точный polygon IoU через Sutherland–Hodgman, подбирает циклическое соответствие углов с минимальной суммой расстояний, затем использует максимальное расстояние отдельного угла. Расстояния в независимо нормализованных x/y могут иначе взвешивать направления, чем пиксельная евклидова метрика; особенно важно для чеков и разных aspect.

## 8. Съёмка и решение об обрезке JPEG

```text
Затвор → фиксируются режимы, timestamp, generation и captureSnapshot
  → CameraX сохраняет JPEG
  → неизменённый JPEG публикуется как captureSource
  → EXIF/ориентированные размеры + thumbnail ≤720 px
  → отдельная детекция stillQuad
  → DocumentCapturePolicy
       нет still → полный кадр
       нет пригодного live → разрешён still
       live пригоден и согласен → разрешён still
       live пригоден, но конфликтует → полный кадр
  → planWarp → decodePrecisely → rectifyManual → JPEG 95
  → производный артефакт/исходник → commitCapture или replacePageImage
  → OCR, если autoOcrEnabled
```

Live пригоден для сравнения только при совпадении поколения на затворе, статусе CONFIRMED, свежести ≤300 мс и совместимой геометрии. Для live/still строже пороги: IoU ≥0.85 и максимальное смещение ≤5% диагонали. HELD не может отклонять still.

**Зелёная рамка не является непосредственным заданием crop для JPEG.** Фото анализируется отдельно, его контур задаёт warp. Отсутствие live не запрещает хорошую still-детекцию. Отсутствие still запрещает warp даже при зелёной рамке.

При Exception/OOM в обработке документного кадра сохраняется исходник. При включённом отдельном чёрно-белом режиме возможна последующая BW-производная; это не означает перезапись исходного JPEG. Кнопка затвора для обычной съёмки включается после callback сохранения камерой, до окончания обработки, поэтому возможны очереди фоновой работы.

## 9. Перспективное преобразование и память

`rectifyManual` строит homography по четырём углам и прямоугольнику результата. Ширина — максимум верхнего и нижнего ребра, высота — максимум левого и правого. Используются `INTER_CUBIC`, `BORDER_REPLICATE`. Это плоское перспективное выравнивание; восстановление изогнутой страницы и физического формата бумаги не выполняется.

Ограничения `MAX_CAPTURE_SOURCE_PIXELS` и `MAX_MANUAL_SOURCE_PIXELS`: 12×1024×1024 пикселей. Старый `MAX_SOURCE_PIXELS=4×1024×1024` остаётся объявленным; активную цепочку съёмки нельзя описывать как фиксированный лимит 4 Мп.

`HeavyWorkScheduler`: по умолчанию 1 тяжёлая задача, 96 МиБ общего допуска. Детекция thumbnail резервирует 16 МиБ. Warp оценивается как `8×sourcePixels + 8×outputPixels + 4 МиБ` (Bitmap+Mat для источника и результата). `planWarp` подбирает декодируемое разрешение бинарным поиском с учётом размеров результата и переполнений Long.

Допуск удерживается до JPEG-кодирования и освобождения рабочих Bitmap/Mat. Это расчётная оценка, не измерение полного RSS/PSS. Live-анализ, камера, UI и возвращённое UI-превью имеют дополнительный расход. Отдельный BW-шаг съёмки также требует проверки фактического пика памяти.

## 10. Два сценария редактирования, использующих тот же детектор

### 10.1. Подтверждение автообрезки

`showDocumentCropEditor` → `createDocumentCropPreview`: один вызов detector на thumbnail, `DocumentCropPlan`, ограниченный warp-превью (источник с ориентиром длинной стороны 1280). Пользователь подтверждает результат.

План хранит pageId, imageVersionId, contentRevision, путь артефакта, ориентированные размеры, quad и algorithmVersion `auto-perspective-crop-v2`. `applyDocumentCrop` проверяет актуальность и применяет тот же quad **без повторной детекции**; JPEG quality здесь 92, а при автоматической съёмке 95. Создаётся новая версия изображения, сохраняется связь с cameraSource. OCR планируется Activity при autoOcrEnabled. Ошибки и OOM обрабатываются отдельно.

### 10.2. Ручное кадрирование в просмотрщике

`showCropDialogForViewer` загружает редактируемый Bitmap (до 12×1024×1024 пикселей и длинной стороны 3000), запускает тот же detector на thumbnail ≤720; при невозможности получить thumbnail пытается детектировать decoded Bitmap. Найденный quad ставится как начальная рамка `CropSelectionView`, затем пользователь двигает углы/рёбра. При отсутствии результата остаётся рамка по умолчанию.

Это отдельная цепочка от подтверждаемого `DocumentCropPlan`; гарантии отсутствия повторного detect и расчёта допуска первого сценария нельзя автоматически приписывать ей. Полный метод включён в приложение для анализа.

## 11. Что важно сохранить при любой замене алгоритма

- Исходные байты JPEG и связь captureSource не заменяются обрезанной производной.
- Работа с версиями страниц, stale-проверки, Undo/пересъёмка и GC-защита должны сохраняться.
- Геометрия OCR относится к конкретной версии изображения; старое распознавание не переиспользуется для нового warp.
- Не переносить рамку между поколениями камеры или разными пространствами координат без преобразования.
- Ошибка детекции должна иметь явный путь к сохранению кадра/ручной коррекции.
- Новая модель или SDK должны иметь понятные требования к аппаратуре, памяти, потоку, формату и геометрии результата.

Полные механизмы StateStore/GC/OCR не включены в приложение: это граница пакета. Для изменения этих механизмов потребуется дополнительный код ядра. Основные вызовы интеграции камеры и редактора включены.

## 12. Имеющиеся проверки и границы доказательств

Документ не содержит нового запуска тестов. Исторические результаты ниже взяты из `docs/DOCUMENT_MODE_VALIDATION.md` и CSV `docs/document-mode-results/0.4.273-native-fixtures.csv`; они относятся к указанной версии/набору, а не автоматически ко всему текущему рабочему дереву 0.4.279.

В отчёте 0.4.273 указаны 184 core-теста, 48 JVM-тестов приложения, компиляция AndroidTest и сборки debug/release. Нативные тесты детектора/планов прошли 6/6. Для 11 синтетических изображений отчёт фиксирует IoU 0.9687–0.9949 и максимальные ошибки углов 1.60–3.97 px.

Фикстуры: листы с заполнением 90/95/98% кадра; чеки 1:4/1:8/1:12 в двух ориентациях; наклонённый лист; вложенный прямоугольник. Пороги регрессионного теста значительно мягче этих фактических результатов: IoU ≥0.72 и максимальная относительная ошибка угла ≤0.10. Отдельно есть uniform image и warp без изменения источника.

Наличие теста в приложении означает наличие исходника, а не успешный новый прогон. Для текущего исследования нужны реальные фото/видеопоследовательности и отдельный holdout. Исторический отчёт прямо оставляет непроверенными физическую камеру/PreviewView, pause/resume, TalkBack, Activity recreate, PSS при совместной работе и полноценный корпус сложных сцен.

## 13. Гипотезы для проверки, а не установленные причины жалоб

| Наблюдаемая конструкция | Возможное ограничение | Как проверить |
| --- | --- | --- |
| «Светлая малонасыщенная бумага» и её вес 0.30 | Цветная/тёмная бумага, тень, белый стол могут менять ranking | Разметка разных бумаг/фонов; ablation paperCoverage |
| Top-15 контуров каждой карты по площади | Хороший маленький контур может не дойти до score на сложном фоне | Recall до/после top-K, журнал кандидатов |
| Одна выбранная гипотеза | Переключение между листом, столом и вложенными прямоугольниками | Видео с несколькими кандидатами, логи rank/score |
| Углы близкие к 90° и параллельность | Сильная перспектива может ухудшать score корректного quad | Sweep перспективы с эталонными углами |
| Hough по крайним почти горизонтальным/вертикальным линиям | Сильный поворот и фоновые линии могут нарушить fallback | Отдельный benchmark fallback по углам поворота |
| fitLine после ranking без нового score | Уточнение не обязательно повышает точность | Ошибки углов/сторон до и после fitLine |
| 720 px и фиксированные морфологические ядра | Узкие чеки/тонкие края теряют детали | Sweep разрешения, latency и recall |
| EMA + два наблюдения + ограничение частоты | Обмен стабильности на задержку, запаздывание при движении | Покадровый jitter и tracking lag |
| Timestamp перед анализом | Неизвестен полный возраст сенсорного кадра | Сопоставить временные базы CameraX/clock и end-to-end latency |
| Обработка TTL только событиями | Рамка может визуально оставаться при остановке событий | Пауза потока анализа без обычной lifecycle-инвалидации |
| Aspect как проверка live/JPEG | Совпадение пропорций не гарантирует одинаковое поле зрения | Калибровочная сцена, разные камеры/ориентации и ViewPort null |
| Независимая нормализация осей | Порог смещения неодинаково соответствует пикселям | Сравнить agreement в normalized и isotropic pixel spaces |
| width против width−1 | Систематическая малая ошибка углов на round-trip | Контрольные точки на краях разных размеров |

Не следует делать вывод «нужна нейросеть» только из этой таблицы. Классический, обучаемый и гибридный варианты нужно сравнивать на одинаковом корпусе и устройстве.

## 14. Требуемый формат результата исследования

1. Краткий диагноз с разделением: доказано кодом, подтверждено измерением, гипотеза.
2. Матрица альтернатив и источников с актуальностью на октябрь 2026 года.
3. Карта компонентов: сохранить / улучшить / заменить / переписать под себя; зависимости и цена каждого пути.
4. План сравнительного прототипа с baseline текущего дерева и фиксированной разметкой.
5. Метрики: recall документа, false positive на сценах без документа, IoU, средняя/p95/максимальная ошибка углов и сторон, потерянное содержимое после crop, jitter неподвижного листа, задержка следования, time-to-confirm, частота переключения кандидата, live/still disagreement, p50/p95 latency, память и нагрев на выбранных устройствах.
6. Сценарии: слабый контраст, светлый/тёмный/цветной фон, текстуры, тени, блики, сильная перспектива, поворот, два листа, частичный выход за кадр, движение, мятые и глянцевые чеки. Отдельно определить желаемое поведение для неполного документа.
7. Рекомендация с критериями принятия, рисками и порядком внедрения. Численные целевые пороги качества согласовать с продуктовой задачей; не выдавать существующий регрессионный порог за уровень лучших приложений.

## 15. Навигация по существующей документации

- `docs/DOCUMENT_DETECTION_AND_RECTIFICATION.md`: история алгоритмов и редактора; ранние разделы местами описывают старые параметры. В этом снимке фактический код приоритетнее текста истории.
- `docs/DOCUMENT_MODE_IMPLEMENTATION_SPEC.md`: старое задание на исправление режима, не точное описание текущего состояния.
- `docs/DOCUMENT_MODE_VALIDATION.md`: исторические прогоны, ограничения и артефакты 0.4.269–0.4.273.
- `docs/ARCHITECTURE.md`: более широкая архитектура приложения.

## 16. Приложение: исходники текущего рабочего дерева

Далее приведён буквальный код файлов/отрывков, без переписывания алгоритмов в псевдокод. Отдельные методы Activity зависят от остальных полей и UI приложения и сами по себе не являются компилируемым проектом. Полные исходники основных алгоритмов позволяют анализировать их автономно. Также включены исходники основных тестов и исторический CSV.

### 16.1. SHA-256 полных файлов

| Файл | SHA-256 |
| --- | --- |
| `app/src/main/kotlin/app/docscan/android/DocumentCropProcessor.kt` | `ecca8f6a5cc28e3875fa5377609d851024a503762328bf8ef4c815f2bff66ed1` |
| `app/src/main/kotlin/app/docscan/android/DocumentLiveDetectionCoordinator.kt` | `795c45e75626ba8593fff2a3f7146dcef07faad4f23d6ece4eb360122d15fd71` |
| `app/src/main/kotlin/app/docscan/android/CameraGeometryTransform.kt` | `79624f216fdf50fd62b2586405fb96a2eed96645ee47a55d615e6afc2ef056d2` |
| `app/src/main/kotlin/app/docscan/android/QuadGeometryUtils.kt` | `97323fd9da2270a9507fedcf1ca3ba1e6c6ccfaf090ae795750eac9ceedace98` |
| `app/src/main/kotlin/app/docscan/android/DocumentCapturePolicy.kt` | `bb2689f93eb7c8f95e3b50a90d069528258edf7529fe11068014e8a846c5ab76` |
| `app/src/main/kotlin/app/docscan/android/DocumentOverlayView.kt` | `fa508d8fd928cf179de48482b0a21176f28c281f695653d30ae6bd4cbcfc3041` |
| `app/src/main/kotlin/app/docscan/android/DocumentCropMemoryCalculator.kt` | `5f811c5dedf4eb7e91eb3f5509b651636da8bb77ee876cb1db0c95ed1450cd64` |
| `app/src/main/kotlin/app/docscan/android/DocumentCropPlan.kt` | `51130decab180bbc01f718b11fa14dd6d360aca692d0c38f2d54623540ddf21d` |
| `app/src/main/kotlin/app/docscan/android/ProductImageDecoder.kt` | `5dafc61ca91d1bc120da562c1c26c9d5315adfb2b059bedb1545c19d70cd67c2` |
| `app/src/main/kotlin/app/docscan/android/QuadTemporalFilter.kt` | `5424cace33d321727079fc9f49aa0e0ee1cb97115e7a88a2550c66ea0b039814` |
| `core/src/main/kotlin/app/docscan/core/edit/CropQuadrilateral.kt` | `bcf247140a9846aff63b137a01447a4ab55c313730ef94ac45ae82b4f674f4fa` |
| `core/src/main/kotlin/app/docscan/core/schedule/HeavyWorkScheduler.kt` | `895eca1acc91f82bf2f2de33a2581dee4758d9b5acc76cb91cfb74fd4cd0aa47` |
| `app/src/main/kotlin/app/docscan/android/ProductActivity.kt` | `ef9f8eb85360a0b480ba34dbf76ad3a7e430448bccb42ebecd0969ce5cf00668` |
| `app/src/main/kotlin/app/docscan/android/ProductStorage.kt` | `7ea349cecca5c43a205ea4ec67da3be35fa224abf29a425a706bba093ec20acb` |
| `app/src/test/kotlin/app/docscan/android/DocumentLiveDetectionCoordinatorTest.kt` | `0f08c5ab89af7708fa445887d47a95a47fea6efdd91f9edd60e7cfac1e2b4316` |
| `app/src/test/kotlin/app/docscan/android/CameraGeometryTransformTest.kt` | `d88cc33c149b9e81d12343606126433d6cc7899201c9e2e640ca9ebaa77a8f23` |
| `app/src/test/kotlin/app/docscan/android/DocumentCapturePolicyTest.kt` | `6ba6561ac7b137bb1bff14c0dcfed5f2bfdbbf85241cfecfd21205b6cebda6c2` |
| `app/src/test/kotlin/app/docscan/android/QuadGeometryUtilsTest.kt` | `9858a493146763dfb2b01d29bb90d1f8c481fe352c5c3c7522f5658e742187b3` |
| `app/src/test/kotlin/app/docscan/android/DocumentCropMemoryCalculatorTest.kt` | `46da72ab2c52147e3555489364eff1277035984988d4b820f90df7b98a84dc9e` |
| `app/src/androidTest/kotlin/app/docscan/android/DocumentCropProcessorInstrumentedTest.kt` | `da97486fbae1aa6883e4cb97b96b2bec2367b6c083f8a796f129989e60d0de8b` |
| `app/src/androidTest/kotlin/app/docscan/android/DocumentCropPlanInstrumentedTest.kt` | `922fc801508a19160be7a690cf8f197d95e9aadad8b59bfdf7a31744d0ca0ee6` |
| `app/build.gradle.kts` | `db0de37b24383dda7ae8b2d25eca411b1818c9f066396cf2d1a3a6578f00631b` |
| `docs/DOCUMENT_MODE_VALIDATION.md` | `89cca4b48bb771045c13681e322db4993408044876e2910ce03562da4679d28d` |
| `docs/document-mode-results/0.4.273-native-fixtures.csv` | `4b7b64af806dae37afbd8467f3ab70dc32d0cc1b889572265d580d04877215b0` |

### 16.2. Полные основные компоненты

#### `app/src/main/kotlin/app/docscan/android/DocumentCropProcessor.kt`

```kotlin
package app.docscan.android

import android.graphics.Bitmap
import android.util.Log
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Finds a strong four-edge page candidate and returns a rectified preview; it never edits input. */
object DocumentCropProcessor {
    const val MAX_SOURCE_PIXELS = 4L * 1024L * 1024L
    const val MAX_CAPTURE_SOURCE_PIXELS = 12L * 1024L * 1024L
    const val MAX_MANUAL_SOURCE_PIXELS = 12L * 1024L * 1024L
    private const val TAG = "DocScan"
    private const val MIN_SCORE = 0.45 // Lower than this — "document not found"

    data class Detection(
        val bufferPoints: FloatArray, // TL, TR, BR, BL in buffer pixels (8 floats: x0, y0, x1, y1, x2, y2, x3, y3)
        val imageWidth: Int,
        val imageHeight: Int,
        val score: Double,
    ) {
        fun toNormalized(): CropQuadrilateral {
            fun p(i: Int) = NormalizedCropPoint(
                (bufferPoints[i] / imageWidth).toDouble().coerceIn(0.0, 1.0),
                (bufferPoints[i + 1] / imageHeight).toDouble().coerceIn(0.0, 1.0),
            )
            return CropQuadrilateral(
                topLeft = p(0),
                topRight = p(2),
                bottomRight = p(4),
                bottomLeft = p(6),
            )
        }
    }

    fun rectifyManual(source: Bitmap, corners: CropQuadrilateral): Bitmap {
        require(!source.isRecycled)
        require(source.width.toLong() * source.height in 1..MAX_MANUAL_SOURCE_PIXELS)
        ensureOpenCvLoaded()
        val rgba = Mat()
        var warped: Mat? = null
        try {
            Utils.bitmapToMat(source, rgba)
            val sourcePoints = corners.asList().map { point ->
                Point(point.x * (rgba.cols() - 1), point.y * (rgba.rows() - 1))
            }
            val width = max(
                distance(sourcePoints[0], sourcePoints[1]),
                distance(sourcePoints[3], sourcePoints[2]),
            ).roundToInt().coerceAtLeast(2)
            val height = max(
                distance(sourcePoints[0], sourcePoints[3]),
                distance(sourcePoints[1], sourcePoints[2]),
            ).roundToInt().coerceAtLeast(2)
            require(width.toLong() * height <= MAX_MANUAL_SOURCE_PIXELS) {
                "Manual crop output exceeds the bounded pixel area"
            }
            val from = MatOfPoint2f(*sourcePoints.toTypedArray())
            val to = MatOfPoint2f(
                Point(0.0, 0.0), Point(width - 1.0, 0.0),
                Point(width - 1.0, height - 1.0), Point(0.0, height - 1.0),
            )
            val transform = Imgproc.getPerspectiveTransform(from, to)
            val output = Mat()
            warped = output
            try {
                Imgproc.warpPerspective(
                    rgba,
                    output,
                    transform,
                    Size(width.toDouble(), height.toDouble()),
                    Imgproc.INTER_CUBIC,
                    Core.BORDER_REPLICATE,
                )
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { result ->
                    try {
                        Utils.matToBitmap(output, result)
                    } catch (failure: Throwable) {
                        result.recycle()
                        throw failure
                    }
                }
            } finally {
                from.release()
                to.release()
                transform.release()
            }
        } finally {
            rgba.release()
            warped?.release()
        }
    }

    fun detectCorners(source: Bitmap): Detection? {
        ensureOpenCvLoaded()
        val rgba = Mat()
        return try {
            Utils.bitmapToMat(source, rgba)
            detectCornersFromRgba(rgba)
        } finally {
            rgba.release()
        }
    }

    fun detectCornersFromRgbaBuffer(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): Detection? {
        ensureOpenCvLoaded()
        val rgba = Mat(height, width, CvType.CV_8UC4, buffer, rowStride.toLong())
        return try {
            detectCornersFromRgba(rgba)
        } finally {
            rgba.release()
        }
    }

    fun rotateNormalizedQuad(quad: CropQuadrilateral, rotationDegrees: Int): CropQuadrilateral {
        val normRot = (rotationDegrees % 360 + 360) % 360
        if (normRot == 0) return quad

        fun rot(p: NormalizedCropPoint): NormalizedCropPoint {
            return when (normRot) {
                90 -> NormalizedCropPoint(1.0 - p.y, p.x)
                180 -> NormalizedCropPoint(1.0 - p.x, 1.0 - p.y)
                270 -> NormalizedCropPoint(p.y, 1.0 - p.x)
                else -> p
            }
        }

        val rawPoints = quad.asList().map(::rot)
        val cx = rawPoints.sumOf { it.x } / 4.0
        val cy = rawPoints.sumOf { it.y } / 4.0
        val sorted = rawPoints.sortedBy { atan2(it.y - cy, it.x - cx) }
        val tlIndex = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        val p = (0 until 4).map { sorted[(tlIndex + it) % 4] }
        return CropQuadrilateral(
            topLeft = p[0],
            topRight = p[1],
            bottomRight = p[2],
            bottomLeft = p[3],
        )
    }

    fun detectCornersFromRgba(input: Mat): Detection? {
        ensureOpenCvLoaded()
        if (input.empty()) return null
        val ow = input.cols()
        val oh = input.rows()
        val ds = min(1.0, 720.0 / max(ow, oh).toDouble())
        val work = Mat()
        val gray = Mat()
        val blur = Mat()
        val edgeMask = Mat()
        val boundaryEdges = Mat()
        val paperMask = Mat()
        val tmp = Mat()
        try {
            Imgproc.resize(input, work, Size(ow * ds, oh * ds), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(work, gray, if (input.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

            // ---- Карта A: Canny OR grad(Adaptive) ----
            val otsuLevel = Imgproc.threshold(blur, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            val canny = Mat()
            val low = max(20.0, otsuLevel * 0.45)
            val high = max(low + 20.0, otsuLevel * 1.20)
            Imgproc.Canny(blur, canny, low, high, 3, true)
            // Closing can merge a page's thin outer margin with the image border.
            // Keep the measured boundary as an independent candidate map and fit reference.
            canny.copyTo(boundaryEdges)

            // Adaptive threshold большим блоком переживает неравномерное освещение
            val adapt = Mat()
            var block = (min(gray.cols(), gray.rows()) * 0.10).roundToInt()
            if (block % 2 == 0) block++
            Imgproc.adaptiveThreshold(blur, adapt, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block.coerceIn(31, 101), 7.0)
            val gradMask = Mat()
            val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(adapt, gradMask, Imgproc.MORPH_GRADIENT, k5)
            adapt.release()

            Core.bitwise_or(canny, gradMask, edgeMask)
            canny.release()
            gradMask.release()

            Imgproc.morphologyEx(edgeMask, edgeMask, Imgproc.MORPH_CLOSE, k5, Point(-1.0, -1.0), 2)
            k5.release()
            val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(edgeMask, edgeMask, k3)
            k3.release()

            // ---- Карта B: "бумага" = светлое + малонасыщенное пятно ----
            buildPaperMask(work, paperMask)

            // ---- Сбор и скоринг кандидатов с обеих карт ----
            val frameArea = work.cols().toDouble() * work.rows()
            var best: Candidate? = null
            var bestMap = ""
            for ((mask, name) in listOf(boundaryEdges to "boundary", edgeMask to "edges", paperMask to "paper")) {
                for (cand in extractCandidates(mask, gray, boundaryEdges, paperMask, frameArea)) {
                    if (best == null || cand.score > best.score) {
                        best = cand
                        bestMap = name
                    }
                }
            }

            // ---- Фоллбек HoughLinesP при слабом скоре контуров ----
            if (best == null || best.score < MIN_SCORE) {
                val hough = houghLinesFallback(boundaryEdges, gray, paperMask, frameArea)
                if (hough != null && (best == null || hough.score > best.score)) {
                    best = hough
                    bestMap = "hough"
                }
            }

            if (best != null) {
                Log.d(TAG, "Document detected: score=%.3f map=%s".format(best.score, bestMap))
            }
            if (best == null || best.score < MIN_SCORE) return null

            // ---- Субпиксельное уточнение граней через fitLine ----
            val refined = refineQuadWithFitLine(best.pts, boundaryEdges)

            val inv = 1.0f / ds.toFloat()
            val pts = FloatArray(8)
            refined.forEachIndexed { i, p ->
                pts[i * 2] = (p.x * inv).toFloat().coerceIn(0f, ow.toFloat() - 1f)
                pts[i * 2 + 1] = (p.y * inv).toFloat().coerceIn(0f, oh.toFloat() - 1f)
            }
            return Detection(pts, ow, oh, best.score)
        } finally {
            work.release()
            gray.release()
            blur.release()
            edgeMask.release()
            boundaryEdges.release()
            paperMask.release()
            tmp.release()
        }
    }

    fun rectify(source: Bitmap): Bitmap? {
        val detection = detectCorners(source) ?: return null
        return runCatching { rectifyManual(source, detection.toNormalized()) }.getOrNull()
    }

    private fun ensureOpenCvLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                if (!OpenCVLoader.initLocal()) {
                    System.loadLibrary("opencv_java4")
                }
                loaded = true
            }
        }
    }

    // ---------------- Карта "бумаги" ----------------
    private fun buildPaperMask(src: Mat, out: Mat) {
        val rgb = Mat()
        val hsv = Mat()
        val ch = ArrayList<Mat>()
        val vMask = Mat()
        val sMask = Mat()
        val vBlur = Mat()
        try {
            if (src.channels() == 4) {
                Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB)
            } else {
                src.copyTo(rgb)
            }
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.split(hsv, ch)
            Imgproc.GaussianBlur(ch[2], vBlur, Size(9.0, 9.0), 0.0)
            Imgproc.threshold(vBlur, vMask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.threshold(ch[1], sMask, 70.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(vMask, sMask, out)
            val k15 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            val k7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            Imgproc.morphologyEx(out, out, Imgproc.MORPH_CLOSE, k15)
            Imgproc.morphologyEx(out, out, Imgproc.MORPH_OPEN, k7)
            fillHoles(out)
            k15.release()
            k7.release()
        } finally {
            rgb.release()
            hsv.release()
            vMask.release()
            sMask.release()
            vBlur.release()
            ch.forEach { it.release() }
        }
    }

    private fun fillHoles(mask: Mat) {
        val contours = ArrayList<MatOfPoint>()
        val work = mask.clone()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Imgproc.drawContours(mask, contours, -1, Scalar(255.0), Imgproc.FILLED)
        } finally {
            work.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    // ---------------- Кандидаты ----------------
    private data class Candidate(val pts: List<Point>, val score: Double)

    private fun extractCandidates(
        mask: Mat,
        gray: Mat,
        edgeMapRef: Mat,
        paperMask: Mat,
        frameArea: Double,
    ): List<Candidate> {
        val work = mask.clone()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val result = ArrayList<Candidate>()
        try {
            // RETR_LIST обязателен: лист может лежать ВНУТРИ внешнего контура стола
            Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val top = contours
                .map { it to Imgproc.contourArea(it) }
                .filter { it.second > frameArea * 0.015 && it.second < frameArea * 0.99 }
                .sortedByDescending { it.second }
                .take(15)

            for ((cnt, _) in top) {
                val hull = hullOf(cnt) ?: continue
                val peri = Imgproc.arcLength(hull, true)
                if (peri < min(gray.cols(), gray.rows()) * 0.45) {
                    hull.release()
                    continue
                }
                val quad = approxQuad(hull, peri) ?: minAreaRectFallback(hull)
                hull.release()
                if (quad == null) continue
                val ordered = orderCorners(quad) ?: continue
                val score = scoreQuad(ordered, gray, edgeMapRef, paperMask, frameArea) ?: continue
                result += Candidate(ordered, score)
            }
        } finally {
            work.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
        return result
    }

    /** Бинарный поиск epsilon: быстрее и стабильнее, чем фиксированный список */
    private fun approxQuad(hull: MatOfPoint2f, peri: Double): Array<Point>? {
        var lo = 0.005 * peri
        var hi = 0.08 * peri
        var found: Array<Point>? = null
        repeat(12) {
            val eps = (lo + hi) / 2.0
            val approx = MatOfPoint2f()
            try {
                Imgproc.approxPolyDP(hull, approx, eps, true)
                val n = approx.total().toInt()
                if (n == 4) {
                    found = approx.toArray()
                    lo = eps
                } else if (n > 4) {
                    lo = eps
                } else {
                    hi = eps
                }
            } finally {
                approx.release()
            }
        }
        return found
    }

    private fun minAreaRectFallback(hull: MatOfPoint2f): Array<Point>? {
        val rr = Imgproc.minAreaRect(hull)
        val rectArea = rr.size.width * rr.size.height
        if (rectArea <= 0) return null
        if (Imgproc.contourArea(hull) / rectArea < 0.85) return null
        val pts = arrayOfNulls<Point>(4)
        rr.points(pts)
        return pts.filterNotNull().toTypedArray().takeIf { it.size == 4 }
    }

    private fun hullOf(c: MatOfPoint): MatOfPoint2f? {
        val idx = MatOfInt()
        Imgproc.convexHull(c, idx)
        val src = c.toArray()
        val idxArr = idx.toArray()
        idx.release()
        if (idxArr.size < 4) return null
        return MatOfPoint2f(*Array(idxArr.size) { src[idxArr[it]] })
    }

    /** TL, TR, BR, BL — геометрический расчет, устойчивый к углам наклона */
    private fun orderCorners(p: Array<Point>): List<Point>? {
        if (p.size != 4) return null
        val tl = p.minByOrNull { it.x + it.y } ?: return null
        val br = p.maxByOrNull { it.x + it.y } ?: return null
        val tr = p.minByOrNull { it.y - it.x } ?: return null
        val bl = p.maxByOrNull { it.y - it.x } ?: return null
        val candidate = listOf(tl, tr, br, bl)
        if (candidate.distinctBy { it.x.roundToInt() to it.y.roundToInt() }.size == 4) {
            return candidate
        }
        // Fallback: Clockwise around centroid
        val cx = p.sumOf { it.x } / 4.0
        val cy = p.sumOf { it.y } / 4.0
        val clockwise = p.sortedBy { atan2(it.y - cy, it.x - cx) }
        val tlIdx = clockwise.indices.minByOrNull { clockwise[it].x + clockwise[it].y } ?: return null
        val ordered = (0 until 4).map { clockwise[(tlIdx + it) % 4] }
        if (ordered.distinctBy { it.x.roundToInt() to it.y.roundToInt() }.size != 4) return null
        return ordered
    }

    // ---------------- Скоринг ----------------
    enum class DocumentCategory {
        PAGE,
        RECEIPT,
    }

    private fun scoreQuad(
        p: List<Point>,
        gray: Mat,
        edgeMap: Mat,
        paperMask: Mat,
        frameArea: Double,
    ): Double? {
        if (p.distinctBy { it.x.roundToInt() to it.y.roundToInt() }.size < 4) return null
        if (!isConvex(p)) return null
        val w = gray.cols()
        val h = gray.rows()
        val area = polygonArea(p)
        val areaRatio = area / frameArea
        if (areaRatio < 0.015 || areaRatio > 0.99) return null

        val sides = doubleArrayOf(
            dist(p[0], p[1]),
            dist(p[1], p[2]),
            dist(p[2], p[3]),
            dist(p[3], p[0]),
        )
        // Minimum short side must be at least 20px on the detector copy
        if (sides.any { it < 20.0 }) return null

        val angles = DoubleArray(4) { cornerAngle(p[(it + 3) % 4], p[it], p[(it + 1) % 4]) }
        // Cutoff angles for perspective sanity
        if (angles.any { it < 35.0 || it > 145.0 }) return null

        val width = (sides[0] + sides[2]) / 2.0
        val height = (sides[1] + sides[3]) / 2.0
        val maxSide = max(width, height)
        val minSide = max(1.0, min(width, height))
        val aspect = maxSide / minSide
        // A nominal 1:12 receipt becomes slightly narrower under perspective and
        // integer contour sampling. This is a tolerance, not an extra user mode.
        if (aspect > 13.0) return null

        val category = if (aspect <= 2.8) DocumentCategory.PAGE else DocumentCategory.RECEIPT

        val parDir = (parallelDir(p[0], p[1], p[3], p[2]) + parallelDir(p[0], p[3], p[1], p[2])) / 2.0
        val parLenOpp1 = min(sides[0], sides[2]) / max(sides[0], sides[2])
        val parLenOpp2 = min(sides[1], sides[3]) / max(sides[1], sides[3])
        val parLen = (parLenOpp1 + parLenOpp2) / 2.0

        if (category == DocumentCategory.PAGE) {
            if (parLen < 0.50) return null
            if (areaRatio < 0.05) return null
        } else {
            val longOppRatio = if (width > height) parLenOpp1 else parLenOpp2
            if (longOppRatio < 0.65) return null
            if (maxSide < max(w, h) * 0.35) return null
        }

        // Weighted scores
        val areaScore = if (category == DocumentCategory.PAGE) {
            when {
                areaRatio < 0.15 -> (areaRatio - 0.05) / 0.10
                areaRatio <= 0.88 -> 1.0
                else -> 1.0 - (areaRatio - 0.88) * 1.5
            }.coerceIn(0.0, 1.0)
        } else {
            (areaRatio / 0.15).coerceIn(0.5, 1.0)
        }

        val angleScore = exp(-angles.map { abs(it - 90.0) }.average() / 24.0)
        val parallelScore = (parDir + parLen) / 2.0

        val aspectScore = if (category == DocumentCategory.PAGE) {
            when {
                aspect <= 1.8 -> 1.0
                aspect <= 2.4 -> 1.0 - (aspect - 1.8) / 1.2
                else -> (0.5 - (aspect - 2.4) / 0.8).coerceAtLeast(0.1)
            }
        } else {
            when {
                aspect <= 8.0 -> 0.90
                aspect <= 12.0 -> 0.90 - (aspect - 8.0) * 0.05
                else -> 0.5
            }
        }

        val edgeSupport = edgeSupportScore(p, edgeMap)
        val contrast = ringContrastScore(p, gray)
        val centerScore = centerScore(p, w, h)
        val borderPenalty = borderPenalty(p, w, h)

        val baseScore = if (category == DocumentCategory.PAGE) {
            0.15 * areaScore +
            0.22 * angleScore +
            0.14 * parallelScore +
            0.12 * aspectScore +
            0.15 * edgeSupport +
            0.12 * contrast +
            0.10 * centerScore -
            0.20 * borderPenalty
        } else {
            0.10 * areaScore +
            0.18 * angleScore +
            0.20 * parallelScore +
            0.14 * aspectScore +
            0.20 * edgeSupport +
            0.12 * contrast +
            0.06 * centerScore -
            0.15 * borderPenalty
        }

        // A colored table around a white page has strong geometry/contrast too.
        // Penalize the non-paper area enclosed by that outer rectangle.
        return baseScore - 0.30 * (1.0 - paperCoverage(p, paperMask))
    }

    // --- Геометрия ---
    private fun isConvex(p: List<Point>): Boolean {
        var sign = 0
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cr > 0) 1 else if (cr < 0) -1 else 0
            if (s != 0) {
                if (sign == 0) sign = s else if (sign != s) return false
            }
        }
        return true
    }

    private fun polygonArea(p: List<Point>): Double {
        var s = 0.0
        for (i in 0..3) {
            val j = (i + 1) % 4
            s += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return abs(s) / 2.0
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    private fun distance(first: Point, second: Point) = hypot(first.x - second.x, first.y - second.y)

    private fun cornerAngle(prev: Point, cur: Point, next: Point): Double {
        val ax = prev.x - cur.x
        val ay = prev.y - cur.y
        val bx = next.x - cur.x
        val by = next.y - cur.y
        val m = hypot(ax, ay) * hypot(bx, by)
        if (m < 1e-9) return 0.0
        return Math.toDegrees(acos(((ax * bx + ay * by) / m).coerceIn(-1.0, 1.0)))
    }

    private fun parallelDir(a1: Point, a2: Point, b1: Point, b2: Point): Double {
        var ax = a2.x - a1.x
        var ay = a2.y - a1.y
        var bx = b2.x - b1.x
        var by = b2.y - b1.y
        val am = hypot(ax, ay)
        val bm = hypot(bx, by)
        if (am < 1e-9 || bm < 1e-9) return 0.0
        ax /= am
        ay /= am
        bx /= bm
        by /= bm
        return abs(ax * bx + ay * by).coerceIn(0.0, 1.0)
    }

    // --- Фотометрические проверки ---
    /** Сэмплируем 40 точек на каждой стороне: есть ли реальный edge-пиксель в окрестности ±2px */
    private fun edgeSupportScore(p: List<Point>, edgeMap: Mat): Double {
        var good = 0
        var total = 0
        val cols = edgeMap.cols()
        val rows = edgeMap.rows()
        for (i in 0..3) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            for (j in 0..40) {
                val t = j / 40.0
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                if (x !in 2 until cols - 2 || y !in 2 until rows - 2) continue
                total++
                var found = false
                loop@ for (dy in -2..2) {
                    for (dx in -2..2) {
                        val pixel = edgeMap.get(y + dy, x + dx)
                        if (pixel != null && pixel[0] > 0) {
                            found = true
                            break@loop
                        }
                    }
                }
                if (found) good++
            }
        }
        return if (total == 0) 0.0 else good.toDouble() / total
    }

    /** Разница яркости внутри листа и в кольце вокруг него — сильнейший сигнал против стола */
    private fun ringContrastScore(p: List<Point>, gray: Mat): Double {
        val inner = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val outer = Mat()
        val ring = Mat()
        val poly = MatOfPoint(*p.toTypedArray())
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 21.0))
        return try {
            Imgproc.fillConvexPoly(inner, poly, Scalar(255.0))
            Imgproc.dilate(inner, outer, kernel)
            Core.subtract(outer, inner, ring)
            val meanInner = Core.mean(gray, inner).`val`[0]
            val meanRing = Core.mean(gray, ring).`val`[0]
            val diff = abs(meanInner - meanRing)
            (diff / 50.0).coerceIn(0.0, 1.0)
        } finally {
            inner.release()
            outer.release()
            ring.release()
            poly.release()
            kernel.release()
        }
    }

    private fun centerScore(p: List<Point>, w: Int, h: Int): Double {
        val cx = p.sumOf { it.x } / 4.0
        val cy = p.sumOf { it.y } / 4.0
        val dx = (cx - w / 2.0) / (w / 2.0)
        val dy = (cy - h / 2.0) / (h / 2.0)
        return (1.0 - sqrt(dx * dx + dy * dy) / 1.414).coerceIn(0.0, 1.0)
    }

    private fun paperCoverage(p: List<Point>, paperMask: Mat): Double {
        val interior = Mat.zeros(paperMask.size(), CvType.CV_8UC1)
        val polygon = MatOfPoint(*p.toTypedArray())
        return try {
            Imgproc.fillConvexPoly(interior, polygon, Scalar(255.0))
            (Core.mean(paperMask, interior).`val`[0] / 255.0).coerceIn(0.0, 1.0)
        } finally {
            polygon.release()
            interior.release()
        }
    }

    private fun borderPenalty(p: List<Point>, w: Int, h: Int): Double {
        // Proximity alone is not evidence of a clipped page: 95–98% occupancy is valid.
        val m = 1.0
        return p.count { it.x < m || it.y < m || it.x > w - m || it.y > h - m } / 4.0
    }

    private fun refineQuadWithFitLine(pts: List<Point>, edgeMask: Mat): List<Point> {
        if (pts.size != 4) return pts
        val cols = edgeMask.cols()
        val rows = edgeMask.rows()

        data class LineEq(val a: Double, val b: Double, val c: Double)

        val lines = mutableListOf<LineEq>()
        val byteData = ByteArray(cols * rows)
        edgeMask.get(0, 0, byteData)

        for (i in 0 until 4) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 4]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val len = hypot(dx, dy)
            if (len < 10.0) {
                val a = -dy
                val b = dx
                val c = -(a * p1.x + b * p1.y)
                lines.add(LineEq(a, b, c))
                continue
            }
            val tx = dx / len
            val ty = dy / len
            val nx = -ty
            val ny = tx

            val edgePoints = mutableListOf<Point>()
            var s = 0.05 * len
            while (s <= 0.95 * len) {
                val mx = p1.x + tx * s
                val my = p1.y + ty * s
                for (k in -5..5) {
                    val qx = (mx + nx * k).roundToInt()
                    val qy = (my + ny * k).roundToInt()
                    if (qx in 0 until cols && qy in 0 until rows) {
                        if (byteData[qy * cols + qx].toInt() != 0) {
                            edgePoints.add(Point(qx.toDouble(), qy.toDouble()))
                        }
                    }
                }
                s += 2.0
            }

            if (edgePoints.size >= 15) {
                val ptsMat = MatOfPoint2f(*edgePoints.toTypedArray())
                val lineMat = Mat()
                try {
                    Imgproc.fitLine(ptsMat, lineMat, Imgproc.DIST_L2, 0.0, 0.01, 0.01)
                    val vx = lineMat.get(0, 0)[0]
                    val vy = lineMat.get(1, 0)[0]
                    val x0 = lineMat.get(2, 0)[0]
                    val y0 = lineMat.get(3, 0)[0]
                    val a = -vy
                    val b = vx
                    val c = vy * x0 - vx * y0
                    lines.add(LineEq(a, b, c))
                } finally {
                    ptsMat.release()
                    lineMat.release()
                }
            } else {
                val a = -dy
                val b = dx
                val c = -(a * p1.x + b * p1.y)
                lines.add(LineEq(a, b, c))
            }
        }

        fun intersect(l1: LineEq, l2: LineEq): Point? {
            val det = l1.a * l2.b - l2.a * l1.b
            if (abs(det) < 1e-5) return null
            val x = (l1.b * l2.c - l2.b * l1.c) / det
            val y = (l2.a * l1.c - l1.a * l2.c) / det
            return Point(x, y)
        }

        val refined = mutableListOf<Point>()
        for (i in 0 until 4) {
            val lPrev = lines[(i + 3) % 4]
            val lCur = lines[i]
            val pt = intersect(lPrev, lCur)
            if (pt == null || !pt.x.isFinite() || !pt.y.isFinite() ||
                dist(pt, pts[i]) > max(20.0, 0.15 * dist(pts[i], pts[(i + 1) % 4]))) {
                return pts
            }
            refined.add(pt)
        }

        if (!isConvex(refined)) return pts
        val ordered = orderCorners(refined.toTypedArray()) ?: return pts

        // Strict post-fitLine geometric sanity checks:
        if (ordered.any { !it.x.isFinite() || !it.y.isFinite() }) return pts

        for (i in 0 until 4) {
            if (dist(ordered[i], ordered[(i + 1) % 4]) < 15.0) return pts
        }

        val origArea = polygonArea(pts)
        val refArea = polygonArea(ordered)
        if (origArea > 0 && (refArea < 0.70 * origArea || refArea > 1.30 * origArea)) return pts

        val diag = hypot(pts[2].x - pts[0].x, pts[2].y - pts[0].y)
        for (i in 0 until 4) {
            if (dist(ordered[i], pts[i]) > max(20.0, 0.12 * diag)) return pts
        }

        return ordered
    }

    private fun houghLinesFallback(edgeMask: Mat, gray: Mat, paperMask: Mat, frameArea: Double): Candidate? {
        val linesMat = Mat()
        return try {
            Imgproc.HoughLinesP(edgeMask, linesMat, 1.0, Math.PI / 180.0, 35, 30.0, 10.0)
            if (linesMat.rows() < 4) return null
            val w = edgeMask.cols()
            val h = edgeMask.rows()

            data class Segment(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val len: Double, val angle: Double)

            val hLines = mutableListOf<Segment>()
            val vLines = mutableListOf<Segment>()

            for (i in 0 until min(linesMat.rows(), 80)) {
                val vec = linesMat.get(i, 0) ?: continue
                val x1 = vec[0]
                val y1 = vec[1]
                val x2 = vec[2]
                val y2 = vec[3]
                val dx = x2 - x1
                val dy = y2 - y1
                val len = hypot(dx, dy)
                if (len < 25.0) continue
                val angle = abs(atan2(dy, dx) * 180.0 / Math.PI)
                if (angle < 25.0 || angle > 155.0) {
                    hLines.add(Segment(x1, y1, x2, y2, len, angle))
                } else if (angle in 65.0..115.0) {
                    vLines.add(Segment(x1, y1, x2, y2, len, angle))
                }
            }

            if (hLines.size < 2 || vLines.size < 2) return null

            val top = hLines.minByOrNull { min(it.y1, it.y2) } ?: return null
            val bottom = hLines.maxByOrNull { max(it.y1, it.y2) } ?: return null
            val left = vLines.minByOrNull { min(it.x1, it.x2) } ?: return null
            val right = vLines.maxByOrNull { max(it.x1, it.x2) } ?: return null

            fun lineIntersect(s1: Segment, s2: Segment): Point? {
                val a1 = -(s1.y2 - s1.y1)
                val b1 = s1.x2 - s1.x1
                val c1 = -(a1 * s1.x1 + b1 * s1.y1)
                val a2 = -(s2.y2 - s2.y1)
                val b2 = s2.x2 - s2.x1
                val c2 = -(a2 * s2.x1 + b2 * s2.y1)
                val det = a1 * b2 - a2 * b1
                if (abs(det) < 1e-4) return null
                val x = (b1 * c2 - b2 * c1) / det
                val y = (a2 * c1 - a1 * c2) / det
                return Point(x, y)
            }

            val pTL = lineIntersect(top, left) ?: return null
            val pTR = lineIntersect(top, right) ?: return null
            val pBR = lineIntersect(bottom, right) ?: return null
            val pBL = lineIntersect(bottom, left) ?: return null

            val rawPts = arrayOf(pTL, pTR, pBR, pBL)
            val ordered = orderCorners(rawPts) ?: return null
            if (!isConvex(ordered)) return null

            val sc = scoreQuad(ordered, gray, edgeMask, paperMask, frameArea)
            if (sc != null && sc >= 0.40) Candidate(ordered, sc) else null
        } catch (e: Throwable) {
            null
        } finally {
            linesMat.release()
        }
    }

    @Volatile private var loaded = false
}
```

#### `app/src/main/kotlin/app/docscan/android/DocumentLiveDetectionCoordinator.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot

enum class LiveDetectionStatus {
    IDLE,
    PROVISIONAL,
    CONFIRMED,
    HELD,
    EXPIRED,
}

data class LiveDetectionState(
    val generationId: Long,
    val status: LiveDetectionStatus,
    val analysisSpec: AnalysisBufferSpec?,
    val quadInBufferPixels: FloatArray?,
    val quadInNormalizedViewPort: CropQuadrilateral?,
    val rawScore: Double,
    val frameTimestampMonotonicMs: Long,
    val processedTimestampMonotonicMs: Long,
) {
    val isUsableForAgreement: Boolean
        get() = status == LiveDetectionStatus.CONFIRMED && quadInNormalizedViewPort != null

    fun isFresh(nowMonotonicMs: Long, maxTtlMs: Long = DEFAULT_MAX_TTL_MS): Boolean {
        return (nowMonotonicMs - frameTimestampMonotonicMs) in 0..maxTtlMs
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LiveDetectionState
        if (generationId != other.generationId) return false
        if (status != other.status) return false
        if (analysisSpec != other.analysisSpec) return false
        if (quadInBufferPixels != null) {
            if (other.quadInBufferPixels == null) return false
            if (!quadInBufferPixels.contentEquals(other.quadInBufferPixels)) return false
        } else if (other.quadInBufferPixels != null) return false
        if (quadInNormalizedViewPort != other.quadInNormalizedViewPort) return false
        if (rawScore != other.rawScore) return false
        if (frameTimestampMonotonicMs != other.frameTimestampMonotonicMs) return false
        if (processedTimestampMonotonicMs != other.processedTimestampMonotonicMs) return false
        return true
    }

    override fun hashCode(): Int {
        var result = generationId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (analysisSpec?.hashCode() ?: 0)
        result = 31 * result + (quadInBufferPixels?.contentHashCode() ?: 0)
        result = 31 * result + (quadInNormalizedViewPort?.hashCode() ?: 0)
        result = 31 * result + rawScore.hashCode()
        result = 31 * result + frameTimestampMonotonicMs.hashCode()
        result = 31 * result + processedTimestampMonotonicMs.hashCode()
        return result
    }

    companion object {
        const val DEFAULT_MAX_TTL_MS = 300L

        fun empty(generationId: Long = 0L) = LiveDetectionState(
            generationId = generationId,
            status = LiveDetectionStatus.IDLE,
            analysisSpec = null,
            quadInBufferPixels = null,
            quadInNormalizedViewPort = null,
            rawScore = 0.0,
            frameTimestampMonotonicMs = 0L,
            processedTimestampMonotonicMs = 0L,
        )
    }
}

/** Serializes tracking events; published arrays are defensive copies. */
class DocumentLiveDetectionCoordinator(
    private val maxTtlMs: Long = LiveDetectionState.DEFAULT_MAX_TTL_MS,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    init { require(maxTtlMs > 0) }

    private val generationSequence = AtomicLong(1L)
    @Volatile private var currentGenerationId = generationSequence.get()
    private val lock = Any()
    private var publishedState = LiveDetectionState.empty(currentGenerationId)
    private var activeSpec: AnalysisBufferSpec? = null
    private var provisionalPoints: FloatArray? = null
    private var provisionalQuad: CropQuadrilateral? = null
    private var provisionalTimestampMs: Long? = null
    private var confirmedPoints: FloatArray? = null
    private var confirmedQuad: CropQuadrilateral? = null
    private var confirmedTimestampMs: Long? = null
    private var confirmedScore = 0.0
    private var lastFrameTimestampMs: Long? = null

    fun currentGeneration(): Long = currentGenerationId

    fun newGeneration(): Long = synchronized(lock) { advanceGeneration() }

    fun invalidate() = synchronized(lock) {
        advanceGeneration()
        publishedState = publishedState.copy(status = LiveDetectionStatus.EXPIRED)
    }

    /** Binds a frame to its geometry without allowing an in-flight old session back in. */
    fun generationForFrame(spec: AnalysisBufferSpec, expectedGeneration: Long): Long? = synchronized(lock) {
        if (expectedGeneration != currentGenerationId) return@synchronized null
        if (activeSpec != null && activeSpec != spec) advanceGeneration()
        activeSpec = spec
        currentGenerationId
    }

    fun getPublishedState(): LiveDetectionState = synchronized(lock) { publishedState.detached() }

    fun captureSnapshot(): LiveDetectionState = synchronized(lock) {
        val state = publishedState.detached()
        if (state.isUsableForAgreement && state.isFresh(clock(), maxTtlMs)) state
        else state.copy(status = LiveDetectionStatus.EXPIRED)
    }

    fun onFrameDetected(
        generationId: Long,
        frameTimestampMonotonicMs: Long,
        rawBufferPoints: FloatArray,
        rawScore: Double,
        spec: AnalysisBufferSpec,
        processedTimestampMonotonicMs: Long = clock(),
    ): LiveDetectionState = synchronized(lock) {
        if (generationId != currentGenerationId) return@synchronized publishedState.detached()
        // Callers may submit geometry directly (e.g. tests). Never smooth across spaces.
        if (activeSpec != null && activeSpec != spec) {
            advanceGeneration()
            activeSpec = spec
            return@synchronized publishedState.detached()
        }
        activeSpec = spec
        if (frameTimestampMonotonicMs < 0 || !rawScore.isFinite() ||
            processedTimestampMonotonicMs - frameTimestampMonotonicMs !in 0..maxTtlMs) {
            return@synchronized onFrameMissing(currentGenerationId, processedTimestampMonotonicMs)
        }
        if (lastFrameTimestampMs?.let { frameTimestampMonotonicMs <= it } == true)
            return@synchronized publishedState.detached()
        if (lastFrameTimestampMs?.let { frameTimestampMonotonicMs - it > maxTtlMs } == true)
            resetTracking()
        lastFrameTimestampMs = frameTimestampMonotonicMs

        val candidate = try {
            CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(rawBufferPoints, spec)
        } catch (_: IllegalArgumentException) {
            return@synchronized onFrameMissing(currentGenerationId, frameTimestampMonotonicMs)
        }
        if (!isRecent(confirmedTimestampMs, frameTimestampMonotonicMs)) {
            confirmedQuad = null
            confirmedPoints = null
        }
        if (!isRecent(provisionalTimestampMs, frameTimestampMonotonicMs)) clearProvisional()

        val confirmedAgreement = confirmedQuad?.let { QuadGeometryUtils.checkAgreement(it, candidate) }
        val provisionalAgreement = provisionalQuad?.let { QuadGeometryUtils.checkAgreement(it, candidate) }
        val reference = when {
            confirmedAgreement?.agrees == true -> confirmedPoints
            provisionalAgreement?.agrees == true -> provisionalPoints
            else -> null
        }
        if (reference != null) {
            val agreement = if (confirmedAgreement?.agrees == true) confirmedAgreement else provisionalAgreement!!
            val smoothed = applyEma(reference, rawBufferPoints, agreement.relativeCornerShift.toFloat())
            confirmedPoints = smoothed
            confirmedQuad = CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(smoothed, spec)
            confirmedTimestampMs = frameTimestampMonotonicMs
            confirmedScore = rawScore
            clearProvisional()
            publishedState = state(LiveDetectionStatus.CONFIRMED, spec, smoothed, confirmedQuad,
                rawScore, frameTimestampMonotonicMs, processedTimestampMonotonicMs)
        } else {
            provisionalPoints = rawBufferPoints.clone()
            provisionalQuad = candidate
            provisionalTimestampMs = frameTimestampMonotonicMs
            publishedState = if (confirmedQuad != null) {
                state(LiveDetectionStatus.HELD, spec, confirmedPoints, confirmedQuad, confirmedScore,
                    confirmedTimestampMs!!, processedTimestampMonotonicMs)
            } else {
                state(LiveDetectionStatus.PROVISIONAL, spec, provisionalPoints, candidate, rawScore,
                    frameTimestampMonotonicMs, processedTimestampMonotonicMs)
            }
        }
        publishedState.detached()
    }

    fun onFrameMissing(generationId: Long, nowMonotonicMs: Long = clock()): LiveDetectionState = synchronized(lock) {
        if (generationId != currentGenerationId) return@synchronized publishedState.detached()
        if (lastFrameTimestampMs?.let { nowMonotonicMs < it } == true)
            return@synchronized publishedState.detached()
        clearProvisional() // A missing observation breaks consecutive confirmation.
        lastFrameTimestampMs = nowMonotonicMs
        publishedState = if (confirmedQuad != null && isRecent(confirmedTimestampMs, nowMonotonicMs)) {
            state(LiveDetectionStatus.HELD, activeSpec, confirmedPoints, confirmedQuad, confirmedScore,
                confirmedTimestampMs!!, nowMonotonicMs)
        } else {
            resetTracking()
            LiveDetectionState.empty(currentGenerationId).copy(status = LiveDetectionStatus.EXPIRED,
                processedTimestampMonotonicMs = nowMonotonicMs)
        }
        publishedState.detached()
    }

    private fun isRecent(timestamp: Long?, now: Long): Boolean =
        timestamp != null && now - timestamp in 0..maxTtlMs

    private fun advanceGeneration(): Long {
        currentGenerationId = generationSequence.incrementAndGet()
        resetTracking()
        activeSpec = null
        publishedState = LiveDetectionState.empty(currentGenerationId)
        return currentGenerationId
    }

    private fun clearProvisional() {
        provisionalPoints = null
        provisionalQuad = null
        provisionalTimestampMs = null
    }

    private fun resetTracking() {
        clearProvisional()
        confirmedPoints = null
        confirmedQuad = null
        confirmedTimestampMs = null
        confirmedScore = 0.0
        lastFrameTimestampMs = null
    }

    private fun state(status: LiveDetectionStatus, spec: AnalysisBufferSpec?, points: FloatArray?,
                      quad: CropQuadrilateral?, score: Double, timestamp: Long, processed: Long) =
        LiveDetectionState(currentGenerationId, status, spec, points?.clone(), quad, score, timestamp, processed)

    private fun LiveDetectionState.detached() = copy(quadInBufferPixels = quadInBufferPixels?.clone())

    private fun applyEma(previous: FloatArray, incoming: FloatArray, displacement: Float): FloatArray {
        // Match cyclic corner numbering before interpolating near a 45-degree rotation.
        val shift = (0 until 4).minByOrNull { offset ->
            (0 until 4).sumOf { index ->
                val other = (index + offset) % 4
                hypot((previous[index * 2] - incoming[other * 2]).toDouble(),
                    (previous[index * 2 + 1] - incoming[other * 2 + 1]).toDouble())
            }
        } ?: 0
        val alpha = when {
            displacement < 0.015f -> 0.18f
            displacement < 0.04f -> 0.30f
            else -> 0.50f
        }
        return FloatArray(8) { i ->
            val source = ((i / 2 + shift) % 4) * 2 + i % 2
            previous[i] + alpha * (incoming[source] - previous[i])
        }
    }
}
```

#### `app/src/main/kotlin/app/docscan/android/CameraGeometryTransform.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Coordinate space boundaries:
 * 1. AnalysisBufferSpace (width, height, cropRect, rotationDegrees)
 * 2. NormalizedViewPortSpace (normalized [0, 1] x [0, 1] relative to the oriented ViewPort)
 * 3. OrientedJpegSpace (width, height of JPEG after EXIF orientation)
 */
data class BufferRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right > left) { "BufferRect width must be positive: left=$left right=$right" }
        require(bottom > top) { "BufferRect height must be positive: top=$top bottom=$bottom" }
    }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class AnalysisBufferSpec(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropRect: BufferRect,
    val rotationDegrees: Int,
) {
    init {
        require(bufferWidth > 0 && bufferHeight > 0) { "Buffer dimensions must be positive" }
        require(cropRect.left >= 0 && cropRect.top >= 0) { "Crop rect origin must be non-negative" }
        require(cropRect.right <= bufferWidth && cropRect.bottom <= bufferHeight) {
            "Crop rect ($cropRect) exceeds buffer ($bufferWidth x $bufferHeight)"
        }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "Unsupported rotationDegrees: $rotationDegrees" }
    }

    val orientedViewPortWidth: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) cropRect.height else cropRect.width

    val orientedViewPortHeight: Int
        get() = if (rotationDegrees == 90 || rotationDegrees == 270) cropRect.width else cropRect.height

    val orientedAspectRatio: Double
        get() = orientedViewPortWidth.toDouble() / orientedViewPortHeight.toDouble()
}

data class JpegGeometrySpec(
    val orientedWidth: Int,
    val orientedHeight: Int,
) {
    init {
        require(orientedWidth > 0 && orientedHeight > 0) { "JPEG dimensions must be positive" }
    }
    val aspectRatio: Double
        get() = orientedWidth.toDouble() / orientedHeight.toDouble()
}

object CameraGeometryTransform {

    fun analysisBufferPointToNormalizedViewPort(
        px: Double,
        py: Double,
        spec: AnalysisBufferSpec,
    ): NormalizedCropPoint {
        val u = (px - spec.cropRect.left) / spec.cropRect.width.toDouble()
        val v = (py - spec.cropRect.top) / spec.cropRect.height.toDouble()

        val (xNorm, yNorm) = when (spec.rotationDegrees) {
            0 -> u to v
            90 -> (1.0 - v) to u
            180 -> (1.0 - u) to (1.0 - v)
            270 -> v to (1.0 - u)
            else -> error("Invalid rotation: ${spec.rotationDegrees}")
        }
        return NormalizedCropPoint(xNorm, yNorm)
    }

    fun normalizedViewPortToAnalysisBufferPoint(
        p: NormalizedCropPoint,
        spec: AnalysisBufferSpec,
    ): Pair<Double, Double> {
        val (u, v) = when (spec.rotationDegrees) {
            0 -> p.x to p.y
            90 -> p.y to (1.0 - p.x)
            180 -> (1.0 - p.x) to (1.0 - p.y)
            270 -> (1.0 - p.y) to p.x
            else -> error("Invalid rotation: ${spec.rotationDegrees}")
        }
        val px = spec.cropRect.left + u * spec.cropRect.width.toDouble()
        val py = spec.cropRect.top + v * spec.cropRect.height.toDouble()
        return px to py
    }

    fun analysisBufferQuadToNormalizedViewPort(
        bufferPoints: FloatArray,
        spec: AnalysisBufferSpec,
    ): CropQuadrilateral {
        require(bufferPoints.size == 8) { "Buffer points must have exactly 8 elements" }
        val rawList = (0 until 4).map { i ->
            analysisBufferPointToNormalizedViewPort(
                bufferPoints[i * 2].toDouble(),
                bufferPoints[i * 2 + 1].toDouble(),
                spec,
            )
        }
        val canonical = QuadGeometryUtils.canonicalizeCorners(rawList)
        return CropQuadrilateral(
            topLeft = canonical[0],
            topRight = canonical[1],
            bottomRight = canonical[2],
            bottomLeft = canonical[3],
        )
    }

    fun isGeometryCompatible(
        analysisSpec: AnalysisBufferSpec,
        jpegSpec: JpegGeometrySpec,
        aspectTolerance: Double = 0.03,
    ): Boolean {
        val aRatio = analysisSpec.orientedAspectRatio
        val jRatio = jpegSpec.aspectRatio
        val relDiff = abs(aRatio - jRatio) / max(aRatio, jRatio)
        return relDiff <= aspectTolerance
    }

    fun normalizedViewPortToJpegPixel(
        p: NormalizedCropPoint,
        jpegSpec: JpegGeometrySpec,
    ): Pair<Double, Double> {
        return (p.x * jpegSpec.orientedWidth) to (p.y * jpegSpec.orientedHeight)
    }

    fun jpegPixelToNormalizedViewPort(
        x: Double,
        y: Double,
        jpegSpec: JpegGeometrySpec,
    ): NormalizedCropPoint {
        return NormalizedCropPoint(x / jpegSpec.orientedWidth, y / jpegSpec.orientedHeight)
    }
}
```

#### `app/src/main/kotlin/app/docscan/android/QuadGeometryUtils.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

data class QuadAgreementResult(
    val agrees: Boolean,
    val iou: Double,
    val relativeCornerShift: Double,
    val maxCornerDistance: Double,
    val documentDiagonal: Double,
)

object QuadGeometryUtils {

    /**
     * Canonicalizes 4 corners into standard order:
     * [0] Top-Left (minimum x + y)
     * [1] Top-Right (clockwise)
     * [2] Bottom-Right (clockwise)
     * [3] Bottom-Left (clockwise)
     */
    fun canonicalizeCorners(points: List<NormalizedCropPoint>): List<NormalizedCropPoint> {
        require(points.size == 4) { "Must have exactly 4 points to canonicalize, got ${points.size}" }
        val cx = points.sumOf { it.x } / 4.0
        val cy = points.sumOf { it.y } / 4.0
        val clockwise = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val tlIndex = clockwise.indices.minByOrNull { clockwise[it].x + clockwise[it].y } ?: 0
        return (0 until 4).map { clockwise[(tlIndex + it) % 4] }
    }

    fun canonicalize(quad: CropQuadrilateral): CropQuadrilateral {
        val c = canonicalizeCorners(quad.asList())
        return CropQuadrilateral(
            topLeft = c[0],
            topRight = c[1],
            bottomRight = c[2],
            bottomLeft = c[3],
        )
    }

    /**
     * Polygon area via the standard Shoelace formula.
     */
    fun polygonArea(points: List<NormalizedCropPoint>): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            sum += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return abs(sum) / 2.0
    }

    /**
     * Exact convex polygon intersection via Sutherland-Hodgman clipping.
     */
    fun convexPolygonIntersection(
        subjectPolygon: List<NormalizedCropPoint>,
        clipPolygon: List<NormalizedCropPoint>,
    ): List<NormalizedCropPoint> {
        if (subjectPolygon.size < 3 || clipPolygon.size < 3) return emptyList()

        var outputList = subjectPolygon

        // Ensure clip polygon is oriented counter-clockwise
        val clip = ensureCounterClockwise(clipPolygon)

        for (i in clip.indices) {
            val edgeStart = clip[i]
            val edgeEnd = clip[(i + 1) % clip.size]
            val inputList = outputList
            outputList = mutableListOf()
            if (inputList.isEmpty()) break

            var prev = inputList.last()
            for (curr in inputList) {
                if (isInside(curr, edgeStart, edgeEnd)) {
                    if (isInside(prev, edgeStart, edgeEnd)) {
                        outputList.add(curr)
                    } else {
                        outputList.add(computeIntersection(prev, curr, edgeStart, edgeEnd))
                        outputList.add(curr)
                    }
                } else if (isInside(prev, edgeStart, edgeEnd)) {
                    outputList.add(computeIntersection(prev, curr, edgeStart, edgeEnd))
                }
                prev = curr
            }
        }
        return outputList
    }

    private fun isInside(p: NormalizedCropPoint, a: NormalizedCropPoint, b: NormalizedCropPoint): Boolean {
        // Cross product of (b - a) and (p - a) >= -1e-9 for counter-clockwise boundary
        val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        return cross >= -1e-9
    }

    private fun computeIntersection(
        p1: NormalizedCropPoint,
        p2: NormalizedCropPoint,
        cp1: NormalizedCropPoint,
        cp2: NormalizedCropPoint,
    ): NormalizedCropPoint {
        val a1 = p2.y - p1.y
        val b1 = p1.x - p2.x
        val c1 = a1 * p1.x + b1 * p1.y

        val a2 = cp2.y - cp1.y
        val b2 = cp1.x - cp2.x
        val c2 = a2 * cp1.x + b2 * cp1.y

        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-12) return p1 // Parallel fallback

        val x = (b2 * c1 - b1 * c2) / det
        val y = (a1 * c2 - a2 * c1) / det
        return NormalizedCropPoint(x, y)
    }

    private fun ensureCounterClockwise(points: List<NormalizedCropPoint>): List<NormalizedCropPoint> {
        var signedArea = 0.0
        val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            signedArea += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return if (signedArea >= 0.0) points else points.reversed()
    }

    /**
     * Polygon Intersection-over-Union (IoU) for two quadrilaterals.
     */
    fun polygonIoU(q1: CropQuadrilateral, q2: CropQuadrilateral): Double {
        val pts1 = q1.asList()
        val pts2 = q2.asList()
        val area1 = polygonArea(pts1)
        val area2 = polygonArea(pts2)
        if (area1 <= 0.0 || area2 <= 0.0) return 0.0

        val intersectionPolygon = convexPolygonIntersection(pts1, pts2)
        val intersectionArea = polygonArea(intersectionPolygon)
        val unionArea = area1 + area2 - intersectionArea
        if (unionArea <= 1e-9) return 0.0
        return (intersectionArea / unionArea).coerceIn(0.0, 1.0)
    }

    /**
     * Compares live quad vs still (JPEG) quad:
     * - Accounts for cyclic shifts to avoid false mismatches
     * - Measures maximum individual corner displacement relative to document diagonal
     * - Measures Polygon IoU
     * - Thresholds: maxCornerShift <= 6% of diagonal, IoU >= 0.80
     */
    fun checkAgreement(
        liveQuad: CropQuadrilateral,
        stillQuad: CropQuadrilateral,
        maxRelativeShiftTolerance: Double = 0.06,
        minIouTolerance: Double = 0.80,
    ): QuadAgreementResult {
        val live = canonicalizeCorners(liveQuad.asList())
        val still = canonicalizeCorners(stillQuad.asList())

        var bestShift = 0
        var minTotalDist = Double.MAX_VALUE
        var bestMaxCornerDist = Double.MAX_VALUE

        for (shift in 0 until 4) {
            var totalDist = 0.0
            var maxDist = 0.0
            for (i in 0 until 4) {
                val d = hypot(live[i].x - still[(i + shift) % 4].x, live[i].y - still[(i + shift) % 4].y)
                totalDist += d
                if (d > maxDist) maxDist = d
            }
            if (totalDist < minTotalDist) {
                minTotalDist = totalDist
                bestShift = shift
                bestMaxCornerDist = maxDist
            }
        }

        val d1 = hypot(still[2].x - still[0].x, still[2].y - still[0].y)
        val d2 = hypot(still[3].x - still[1].x, still[3].y - still[1].y)
        val docDiagonal = max(1e-4, max(d1, d2))

        val relativeCornerShift = bestMaxCornerDist / docDiagonal
        val iou = polygonIoU(liveQuad, stillQuad)

        val agrees = (relativeCornerShift <= maxRelativeShiftTolerance) && (iou >= minIouTolerance)

        return QuadAgreementResult(
            agrees = agrees,
            iou = iou,
            relativeCornerShift = relativeCornerShift,
            maxCornerDistance = bestMaxCornerDist,
            documentDiagonal = docDiagonal,
        )
    }
}
```

#### `app/src/main/kotlin/app/docscan/android/DocumentCapturePolicy.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral

enum class DocumentCaptureDecision { RECTIFY_STILL, NO_STILL_BOUNDARY, BOUNDARIES_DISAGREE }

/** The saved photograph supplies the crop; live analysis can only veto a conflicting crop. */
object DocumentCapturePolicy {
    fun decide(
        stillQuad: CropQuadrilateral?,
        live: LiveDetectionState?,
        jpeg: JpegGeometrySpec,
        generationAtShutter: Long,
        shutterTimestampMs: Long,
    ): DocumentCaptureDecision {
        if (stillQuad == null) return DocumentCaptureDecision.NO_STILL_BOUNDARY
        val canCompare = live != null && live.generationId == generationAtShutter &&
            live.isUsableForAgreement && live.isFresh(shutterTimestampMs) &&
            live.analysisSpec?.let { CameraGeometryTransform.isGeometryCompatible(it, jpeg) } == true
        if (!canCompare) return DocumentCaptureDecision.RECTIFY_STILL
        return if (QuadGeometryUtils.checkAgreement(live!!.quadInNormalizedViewPort!!, stillQuad,
                maxRelativeShiftTolerance = 0.05, minIouTolerance = 0.85).agrees)
            DocumentCaptureDecision.RECTIFY_STILL else DocumentCaptureDecision.BOUNDARIES_DISAGREE
    }
}
```

#### `app/src/main/kotlin/app/docscan/android/DocumentOverlayView.kt`

```kotlin
package app.docscan.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Overlay view that draws a green polygon boundary over detected documents in the live camera preview.
 * Points are ready PreviewView screen pixels (FloatArray of 8 elements: TL.x, TL.y, TR.x, TR.y, BR.x, BR.y, BL.x, BL.y).
 */
class DocumentOverlayView(context: Context) : View(context) {

    private var points: FloatArray? = null
    private val path = Path()

    init {
        setWillNotDraw(false)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2422C55E // Subtle emerald green transparent fill
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3.0f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = 0xFF22C55E.toInt() // Emerald Green
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.0f
        color = 0xFF22C55E.toInt()
    }

    private var isHeld: Boolean = false

    fun setPolygonPixels(newPoints: FloatArray?, isHeld: Boolean = false) {
        this.points = newPoints?.takeIf { it.size == 8 }?.clone()
        this.isHeld = isHeld
        invalidate()
    }

    fun clear() {
        points = null
        isHeld = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = points ?: return
        path.reset()
        path.moveTo(p[0], p[1])
        path.lineTo(p[2], p[3])
        path.lineTo(p[4], p[5])
        path.lineTo(p[6], p[7])
        path.close()

        val strokeAlpha = if (isHeld) 110 else 255
        val fillAlpha = if (isHeld) 15 else 36
        val cornerAlpha = if (isHeld) 120 else 255

        strokePaint.alpha = strokeAlpha
        fillPaint.alpha = fillAlpha
        cornerPaint.alpha = cornerAlpha
        cornerStrokePaint.alpha = strokeAlpha

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        val radius = resources.displayMetrics.density * 5f
        for (i in 0 until 4) {
            val px = p[i * 2]
            val py = p[i * 2 + 1]
            canvas.drawCircle(px, py, radius, cornerPaint)
            canvas.drawCircle(px, py, radius, cornerStrokePaint)
        }
    }
}
```

#### `app/src/main/kotlin/app/docscan/android/DocumentCropMemoryCalculator.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A decoder request and the maximum simultaneously live source/output buffers for its warp. */
data class DocumentCropWorkPlan(
    val sourcePixelLimit: Long,
    val decodedWidth: Int,
    val decodedHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val workingSetBytes: Long,
)

object DocumentCropMemoryCalculator {
    const val DEFAULT_MAX_BUDGET_BYTES = 96L * 1024L * 1024L
    const val DETECTION_THUMBNAIL_MAX_SIDE = 720
    const val ESTIMATED_DETECTION_OVERHEAD_BYTES = 16L * 1024L * 1024L
    const val WARP_BUFFER_MULTIPLIER = 16L
    const val WARP_RESERVE_BYTES = 4L * 1024L * 1024L

    /** Bitmap+Mat for each of the source and result; fail before an estimate can wrap. */
    fun estimateWarpWorkingSetBytes(targetPixels: Long, outputPixels: Long = targetPixels): Long {
        require(targetPixels > 0 && outputPixels > 0) { "Pixel counts must be positive" }
        return Math.addExact(WARP_RESERVE_BYTES,
            Math.multiplyExact(Math.addExact(targetPixels, outputPixels), 8L))
    }

    fun calculateBoundedPixels(
        sourceWidth: Int,
        sourceHeight: Int,
        maxBudgetBytes: Long = DEFAULT_MAX_BUDGET_BYTES,
        maxSourcePixelsLimit: Long = DocumentCropProcessor.MAX_CAPTURE_SOURCE_PIXELS,
    ): Long {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(maxSourcePixelsLimit > 0) { "Source pixel limit must be positive" }
        require(maxBudgetBytes >= WARP_RESERVE_BYTES + WARP_BUFFER_MULTIPLIER) {
            "Not enough memory for a crop working set"
        }
        val sourcePixels = Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
        val cap = (maxBudgetBytes - WARP_RESERVE_BYTES) / WARP_BUFFER_MULTIPLIER
        return min(sourcePixels, min(maxSourcePixelsLimit, cap))
    }

    /**
     * Use actual quadrilateral edge lengths, just as rectifyManual does. A trapezoid's
     * output rectangle can be larger than its source; 16*sourcePixels alone is unsafe.
     */
    fun planWarp(
        sourceWidth: Int,
        sourceHeight: Int,
        quad: CropQuadrilateral,
        maxBudgetBytes: Long = DEFAULT_MAX_BUDGET_BYTES,
        maxSourcePixelsLimit: Long = DocumentCropProcessor.MAX_CAPTURE_SOURCE_PIXELS,
    ): DocumentCropWorkPlan {
        require(sourceWidth >= 2 && sourceHeight >= 2) { "Crop source is too small" }
        val sourcePixels = Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
        var low = 1L
        var high = calculateBoundedPixels(sourceWidth, sourceHeight, maxBudgetBytes,
            min(maxSourcePixelsLimit, DocumentCropProcessor.MAX_MANUAL_SOURCE_PIXELS))
        var best: DocumentCropWorkPlan? = null
        while (low <= high) {
            val limit = low + (high - low) / 2
            val ratio = min(1.0, sqrt(limit.toDouble() / sourcePixels))
            val width = max(1, (sourceWidth * ratio).toInt())
            val height = max(1, (sourceHeight * ratio).toInt())
            if (width < 2 || height < 2) { low = limit + 1; continue }
            val points = quad.asList()
            fun edge(a: Int, b: Int) = hypot((points[a].x - points[b].x) * (width - 1),
                (points[a].y - points[b].y) * (height - 1))
            val outputWidth = max(edge(0, 1), edge(3, 2)).roundToInt().coerceAtLeast(2)
            val outputHeight = max(edge(0, 3), edge(1, 2)).roundToInt().coerceAtLeast(2)
            val outputPixels = Math.multiplyExact(outputWidth.toLong(), outputHeight.toLong())
            val bytes = estimateWarpWorkingSetBytes(width.toLong() * height, outputPixels)
            if (bytes <= maxBudgetBytes && outputPixels <= DocumentCropProcessor.MAX_MANUAL_SOURCE_PIXELS) {
                best = DocumentCropWorkPlan(limit, width, height, outputWidth, outputHeight, bytes)
                low = limit + 1
            } else high = limit - 1
        }
        return requireNotNull(best) { "Not enough memory for a usable crop" }
    }

    fun readImageBounds(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() == 0L) return null
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    fun readOrientedImageBounds(file: File): Pair<Int, Int>? {
        val raw = readImageBounds(file) ?: return null
        val rotation = androidx.exifinterface.media.ExifInterface(file).rotationDegrees
        return if (rotation == 90 || rotation == 270) raw.second to raw.first else raw
    }
}
```

#### `app/src/main/kotlin/app/docscan/android/DocumentCropPlan.kt`

```kotlin
package app.docscan.android

import android.graphics.Bitmap
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId

data class DocumentCropPlan(
    val pageId: PageId,
    val initialImageVersionId: ImageVersionId,
    val initialContentRevision: Long,
    val artifactRelativePath: String,
    val orientedWidth: Int,
    val orientedHeight: Int,
    val cropQuad: CropQuadrilateral,
    val algorithmVersion: String = ALGORITHM_VERSION,
) {
    init {
        require(orientedWidth > 0 && orientedHeight > 0) {
            "Dimensions must be positive: ${orientedWidth}x${orientedHeight}"
        }
        require(artifactRelativePath.isNotBlank()) {
            "artifactRelativePath must not be blank"
        }
    }

    companion object {
        const val ALGORITHM_VERSION = "auto-perspective-crop-v2"
    }
}

data class DocumentCropPreviewResult(
    val previewBitmap: Bitmap,
    val plan: DocumentCropPlan,
)
```

#### `app/src/main/kotlin/app/docscan/android/ProductImageDecoder.kt`

```kotlin
package app.docscan.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import kotlin.math.sqrt
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Decodes bounded pixels in the requested format and applies EXIF orientation in memory. Source artifacts stay immutable. */
object ProductImageDecoder {
    /** Decode directly to the requested envelope; EXIF is applied by ImageDecoder.
     * Unlike power-of-two BitmapFactory sampling, a 12MP photo stays 12MP when allowed.
     */
    fun decodePrecisely(file: File, maxPixels: Long, maxSide: Int = Int.MAX_VALUE): Bitmap {
        require(maxPixels > 0 && maxSide > 0)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val ratio = minOf(1.0, sqrt(maxPixels.toDouble() / (width.toLong() * height)),
                maxSide.toDouble() / maxOf(width, height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            decoder.isMutableRequired = true
            decoder.setTargetSize(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()))
        }
    }

    /**
     * Decodes a bounded lightweight copy (max 720px) specifically for corner detection,
     * avoiding the allocation of full-size 12MP RGBA buffers.
     */
    fun decodeThumbnailForDetection(file: File, maxSide: Int = 720): Bitmap {
        return decodePrecisely(file, maxPixels = maxSide.toLong() * maxSide.toLong(), maxSide = maxSide)
    }

    fun estimateWorkingSetBytes(file: File, maxPixels: Long): Long {
        val bounds = bounds(file)
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
        val pixels = (bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample)
        // Reserve for the decoded source, oriented copy, and a downstream RGBA working image.
        return (pixels * 8L).coerceAtLeast(1L)
    }

    fun decode(
        file: File,
        maxPixels: Long,
        preferredConfig: Bitmap.Config = Bitmap.Config.RGB_565,
        maxWidth: Int = Int.MAX_VALUE,
        maxHeight: Int = Int.MAX_VALUE,
    ): Bitmap? {
        require(maxPixels > 0L && maxWidth > 0 && maxHeight > 0)
        val imageBounds = bounds(file)
        var sample = sampleSize(imageBounds.outWidth, imageBounds.outHeight, maxPixels)
        while (imageBounds.outWidth / sample > maxWidth || imageBounds.outHeight / sample > maxHeight) {
            sample *= 2
        }
        val source = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = preferredConfig
        }) ?: return null

        return try {
            val exif = ExifInterface(file)
            val rotation = exif.rotationDegrees
            val flipped = exif.isFlipped
            if (rotation == 0 && !flipped) {
                source
            } else {
                val transform = Matrix().apply {
                    if (flipped) setScale(-1f, 1f)
                    if (rotation != 0) postRotate(rotation.toFloat())
                }
                Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
                    .also { oriented -> if (oriented !== source) source.recycle() }
            }
        } catch (failure: Exception) {
            source.recycle()
            throw failure
        }
    }

    private fun bounds(file: File): BitmapFactory.Options {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Image cannot be decoded" }
        return options
    }

    private fun sampleSize(width: Int, height: Int, maxPixels: Long): Int {
        var sample = 1
        while (width.toLong() / sample * (height.toLong() / sample) > maxPixels) {
            sample *= 2
        }
        return sample
    }
}
```

#### `app/src/main/kotlin/app/docscan/android/QuadTemporalFilter.kt`

```kotlin
package app.docscan.android

import kotlin.math.hypot
import kotlin.math.max

/**
 * Adaptive EMA with outlier gate (2-frame jump confirmation),
 * speed-dependent alpha, and hold last contour on missing frames.
 */
class QuadTemporalFilter {
    private var current: FloatArray? = null
    private var missingFrames = 0
    private var rejectedJump: FloatArray? = null
    private var rejectedJumpCount = 0

    /** incoming — ready PreviewView pixels (8 floats) */
    fun update(incoming: FloatArray): FloatArray {
        require(incoming.size == 8)
        val previous = current ?: run {
            current = incoming.clone()
            missingFrames = 0
            return incoming.clone()
        }
        val displacement = normalizedDisplacement(previous, incoming)
        if (displacement > 0.20f) {
            // Large jump: false candidate OR real object switch.
            // Accept only after confirmation by a second consecutive frame.
            if (rejectedJump != null && normalizedDisplacement(rejectedJump!!, incoming) < 0.06f) {
                rejectedJumpCount++
            } else {
                rejectedJump = incoming.clone()
                rejectedJumpCount = 1
            }
            if (rejectedJumpCount < 2) {
                missingFrames++
                return previous.clone()
            }
            current = incoming.clone()
            rejectedJump = null
            rejectedJumpCount = 0
            missingFrames = 0
            return incoming.clone()
        }
        rejectedJump = null
        rejectedJumpCount = 0
        missingFrames = 0

        val alpha = when {
            displacement < 0.015f -> 0.16f // almost still — strong smoothing
            displacement < 0.05f -> 0.25f
            displacement < 0.10f -> 0.38f
            else -> 0.55f // camera moving — fast response
        }
        val result = FloatArray(8)
        for (i in 0 until 8) {
            result[i] = previous[i] + alpha * (incoming[i] - previous[i])
        }
        current = result
        return result.clone()
    }

    fun onMissing(): FloatArray? {
        val previous = current ?: return null
        missingFrames++
        if (missingFrames <= 3) return previous.clone() // anti-flicker hold for ~375ms
        current = null
        rejectedJump = null
        rejectedJumpCount = 0
        return null
    }

    fun reset() {
        current = null
        rejectedJump = null
        rejectedJumpCount = 0
        missingFrames = 0
    }

    /** Displacement normalized by the size of the quad itself — scale-invariant */
    private fun normalizedDisplacement(a: FloatArray, b: FloatArray): Float {
        var sideSum = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sideSum += hypot(a[i * 2] - a[j * 2], a[i * 2 + 1] - a[j * 2 + 1])
        }
        val charSize = max(1f, sideSum / 4f)
        var disp = 0f
        for (i in 0 until 4) {
            disp += hypot(a[i * 2] - b[i * 2], a[i * 2 + 1] - b[i * 2 + 1])
        }
        return (disp / 4f) / charSize
    }
}
```

#### `core/src/main/kotlin/app/docscan/core/edit/CropQuadrilateral.kt`

```kotlin
package app.docscan.core.edit

/** Ordered normalized image-space corners: top-left, top-right, bottom-right, bottom-left. */
data class NormalizedCropPoint(val x: Double, val y: Double) {
    init {
        require(x.isFinite() && y.isFinite())
        require(x in 0.0..1.0 && y in 0.0..1.0)
    }
}

data class CropQuadrilateral(
    val topLeft: NormalizedCropPoint,
    val topRight: NormalizedCropPoint,
    val bottomRight: NormalizedCropPoint,
    val bottomLeft: NormalizedCropPoint,
) {
    init {
        val points = listOf(topLeft, topRight, bottomRight, bottomLeft)
        val turns = points.indices.map { index ->
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = points[(index + 2) % points.size]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        require(turns.all { it > MIN_CROSS } || turns.all { it < -MIN_CROSS }) {
            "Crop corners must form a non-degenerate convex quadrilateral"
        }
        val areaTwice = points.indices.sumOf { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            current.x * next.y - next.x * current.y
        }
        require(kotlin.math.abs(areaTwice) > MIN_AREA_TWICE) {
            "Crop area must be greater than zero"
        }
    }

    fun asList(): List<NormalizedCropPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** Moves one of the 4 edges by the given normalized delta (dx, dy), clamping to bounds and preserving convexity. */
    fun withMovedEdge(edgeIndex: Int, dx: Double, dy: Double): CropQuadrilateral {
        require(edgeIndex in 0..3) { "edgeIndex must be in 0..3, was $edgeIndex" }
        if (!dx.isFinite() || !dy.isFinite() || (dx == 0.0 && dy == 0.0)) return this

        val points = asList()
        val idx1 = edgeIndex
        val idx2 = (edgeIndex + 1) % 4
        val p1 = points[idx1]
        val p2 = points[idx2]

        val minDx = maxOf(-p1.x, -p2.x)
        val maxDx = minOf(1.0 - p1.x, 1.0 - p2.x)
        val clampedDx = dx.coerceIn(minDx, maxDx)

        val minDy = maxOf(-p1.y, -p2.y)
        val maxDy = minOf(1.0 - p1.y, 1.0 - p2.y)
        val clampedDy = dy.coerceIn(minDy, maxDy)

        if (clampedDx == 0.0 && clampedDy == 0.0) return this

        val initialTurnsPositive = run {
            val a = points[0]
            val b = points[1]
            val c = points[2]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0
        }

        fun tryConstruct(stepDx: Double, stepDy: Double): CropQuadrilateral? {
            return runCatching {
                val newP1 = NormalizedCropPoint(
                    (p1.x + stepDx).coerceIn(0.0, 1.0),
                    (p1.y + stepDy).coerceIn(0.0, 1.0),
                )
                val newP2 = NormalizedCropPoint(
                    (p2.x + stepDx).coerceIn(0.0, 1.0),
                    (p2.y + stepDy).coerceIn(0.0, 1.0),
                )
                val updated = points.toMutableList()
                updated[idx1] = newP1
                updated[idx2] = newP2
                val candTurnsPositive = run {
                    val a = updated[0]
                    val b = updated[1]
                    val c = updated[2]
                    (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x) > 0
                }
                require(candTurnsPositive == initialTurnsPositive)
                CropQuadrilateral(updated[0], updated[1], updated[2], updated[3])
            }.getOrNull()
        }

        val full = tryConstruct(clampedDx, clampedDy)
        if (full != null) return full

        var low = 0.0
        var high = 1.0
        var best: CropQuadrilateral = this
        repeat(8) {
            val mid = (low + high) / 2.0
            val candidate = tryConstruct(clampedDx * mid, clampedDy * mid)
            if (candidate != null) {
                best = candidate
                low = mid
            } else {
                high = mid
            }
        }
        return best
    }

    /** Keeps the selected source region aligned when the page is rotated clockwise. */
    fun rotatedClockwise(turns: Int): CropQuadrilateral {
        require(turns in 0..3)
        var points = asList()
        repeat(turns) {
            fun map(point: NormalizedCropPoint) = NormalizedCropPoint(1.0 - point.y, point.x)
            points = listOf(map(points[3]), map(points[0]), map(points[1]), map(points[2]))
        }
        return CropQuadrilateral(points[0], points[1], points[2], points[3])
    }

    private companion object {
        const val MIN_CROSS = 1e-6
        const val MIN_AREA_TWICE = 1e-6
    }
}
```

#### `core/src/main/kotlin/app/docscan/core/schedule/HeavyWorkScheduler.kt`

```kotlin
package app.docscan.core.schedule

/** Runtime admission for non-interactive image work. Capture must not use this gate. */
class HeavyWorkScheduler(
    private val maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS,
    val decodedByteBudget: Long = DEFAULT_DECODED_BYTE_BUDGET,
) {
    private val lock = Any()
    private var runningJobs = 0
    private var reservedBytes = 0L

    init {
        require(maxConcurrentJobs > 0)
        require(decodedByteBudget > 0L)
    }

    fun <T> withAdmission(decodedBytes: Long, work: () -> T): T {
        require(decodedBytes in 1..decodedByteBudget) {
            "Heavy job estimate $decodedBytes exceeds the decoded-image budget $decodedByteBudget"
        }
        synchronized(lock) {
            while (runningJobs >= maxConcurrentJobs || reservedBytes > decodedByteBudget - decodedBytes) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (lock as java.lang.Object).wait()
            }
            runningJobs += 1
            reservedBytes += decodedBytes
        }
        try {
            return work()
        } finally {
            synchronized(lock) {
                runningJobs -= 1
                reservedBytes -= decodedBytes
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (lock as java.lang.Object).notifyAll()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_JOBS = 1
        const val DEFAULT_DECODED_BYTE_BUDGET = 96L * 1024L * 1024L
    }
}
```

### 16.3. Интеграция камеры, JPEG и редакторов

#### Поля Activity: фабрика преобразования и координатор, строки 120–129

```kotlin
    private lateinit var documentModeButton: Button
    @Volatile private var isDocumentMode: Boolean = false
    private var imageAnalysis: ImageAnalysis? = null
    private var lastAnalyzedTimestamp: Long = 0L
    private val imageTransformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = false
        isUsingCropRect = false
    }
    private val liveDetectionCoordinator = DocumentLiveDetectionCoordinator()
    private lateinit var ocrButton: Button
```

#### `app/src/main/kotlin/app/docscan/android/ProductActivity.kt` — `startCamera`, строки 2564–2712

```kotlin
    private fun startCamera() {
        if (workspaceActionBlocked || isFinishing || cameraStartupPending || imageCapture != null || !hasCameraPermission()) return
        cameraStartupPending = true
        val statusAtStart = status.text?.toString()
        liveDetectionCoordinator.newGeneration()
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.getSurfaceProvider()) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    android.util.Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                analysis.setAnalyzer(cameraAnalysisExecutor) { imageProxy ->
                    val receivedGeneration = liveDetectionCoordinator.currentGeneration()
                    if (!isDocumentMode) {
                        liveDetectionCoordinator.onFrameMissing(receivedGeneration, SystemClock.elapsedRealtime())
                        runOnUiThread {
                            if (!isDocumentMode && receivedGeneration == liveDetectionCoordinator.currentGeneration() &&
                                ::documentOverlayView.isInitialized) documentOverlayView.clear()
                        }
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalyzedTimestamp < 100) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastAnalyzedTimestamp = now

                    try {
                        val cropRect = imageProxy.cropRect
                        val spec = AnalysisBufferSpec(
                            bufferWidth = imageProxy.width,
                            bufferHeight = imageProxy.height,
                            cropRect = BufferRect(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom),
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        )
                        val frameGeneration = liveDetectionCoordinator.generationForFrame(spec, receivedGeneration)
                            ?: return@setAnalyzer
                        val plane = imageProxy.planes[0]
                        val detection = DocumentCropProcessor.detectCornersFromRgbaBuffer(
                            plane.buffer,
                            imageProxy.width,
                            imageProxy.height,
                            plane.rowStride,
                        )

                        val liveState = if (detection == null) {
                            liveDetectionCoordinator.onFrameMissing(frameGeneration, now)
                        } else {
                            liveDetectionCoordinator.onFrameDetected(
                                generationId = frameGeneration,
                                frameTimestampMonotonicMs = now,
                                rawBufferPoints = detection.bufferPoints,
                                rawScore = detection.score,
                                spec = spec,
                            )
                        }

                        if (liveState.generationId != frameGeneration) return@setAnalyzer
                        val sourceTransform = imageTransformFactory.getOutputTransform(imageProxy)
                        val pointsToMap = liveState.quadInBufferPixels?.clone()
                        val isHeld = liveState.status == LiveDetectionStatus.HELD
                        val showOverlay = (liveState.status == LiveDetectionStatus.CONFIRMED || liveState.status == LiveDetectionStatus.HELD) && pointsToMap != null

                        runOnUiThread {
                            if (!isDocumentMode || !::documentOverlayView.isInitialized ||
                                frameGeneration != liveDetectionCoordinator.currentGeneration() ||
                                isFinishing || isDestroyed) return@runOnUiThread
                            if (!showOverlay) {
                                documentOverlayView.clear()
                            } else {
                                val targetTransform = previewView.outputTransform
                                if (targetTransform != null) {
                                    try {
                                        val coordinateTransform = CoordinateTransform(sourceTransform, targetTransform)
                                        coordinateTransform.mapPoints(pointsToMap)
                                        documentOverlayView.setPolygonPixels(pointsToMap, isHeld = isHeld)
                                    } catch (transformError: Throwable) {
                                        documentOverlayView.clear()
                                        android.util.Log.w("DocScan", "CoordinateTransform mapping failed", transformError)
                                    }
                                } else {
                                    documentOverlayView.clear()
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        // Forget a failed frame; never leave an apparently usable old contour.
                        if (receivedGeneration == liveDetectionCoordinator.currentGeneration()) {
                            liveDetectionCoordinator.invalidate()
                            val failedGeneration = liveDetectionCoordinator.currentGeneration()
                            runOnUiThread {
                                if (failedGeneration == liveDetectionCoordinator.currentGeneration() &&
                                    ::documentOverlayView.isInitialized && !isDestroyed) documentOverlayView.clear()
                            }
                        }
                        android.util.Log.e("DocScan", "Document live detection failed", failure)
                    } finally {
                        imageProxy.close()
                    }
                }
                provider.unbindAll()
                val viewPort = previewView.viewPort
                if (viewPort != null) {
                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(capture)
                        .addUseCase(analysis)
                        .build()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup)
                } else {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                }
                imageCapture = capture
                imageAnalysis = analysis
                cameraStartupPending = false
                captureButton.isEnabled = true
                updateCameraPreviewFilter()
                if (status.text?.toString() == statusAtStart) {
                    status.text = getString(R.string.camera_ready)
                }
            } catch (failure: Exception) {
                cameraStartupPending = false
                if (status.text?.toString() == statusAtStart) {
                    recordError(getString(R.string.camera_error, failure.message ?: failure.javaClass.simpleName))
                }
                captureButton.isEnabled = false
            }
        }, ContextCompat.getMainExecutor(this))
    }
```

#### `app/src/main/kotlin/app/docscan/android/ProductActivity.kt` — `takePicture`, строки 2714–2900

```kotlin
    private fun takePicture() {
        val capture = imageCapture ?: return
        val activeStorage = storage ?: return
        uiHapticFeedback(captureButton)
        captureButton.isEnabled = false
        val pageBeingRetaken = retakeTarget
        val captureDocumentMode = isDocumentMode
        val captureBlackAndWhiteMode = isBlackAndWhiteMode
        val shutterTimestampMs = SystemClock.elapsedRealtime()
        val generationAtShutter = liveDetectionCoordinator.currentGeneration()
        val liveSnapshot = if (captureDocumentMode) liveDetectionCoordinator.captureSnapshot() else null
        status.text = getString(R.string.capturing)
        val temp = File(cacheDir, "capture-${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(temp).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                // Instantly re-enable shutter button for non-blocking burst capture
                captureButton.isEnabled = pageBeingRetaken == null && imageCapture != null
                io.execute {
                    try {
                        // Publish the untouched camera JPEG before producing any derivative.
                        val captureSource = FileInputStream(temp).use(activeStorage::stageCapturedJpeg)
                        var wasRectified = false
                        var cropStatusRes: Int? = null
                        var prepared: File? = null
                        try {
                            if (captureDocumentMode) {
                                val jpegBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(temp.absolutePath, jpegBounds)
                                val exif = ExifInterface(temp)
                                val rotation = exif.rotationDegrees
                                val orientedW = if (rotation == 90 || rotation == 270) jpegBounds.outHeight else jpegBounds.outWidth
                                val orientedH = if (rotation == 90 || rotation == 270) jpegBounds.outWidth else jpegBounds.outHeight
                                val jpegSpec = JpegGeometrySpec(orientedW, orientedH)

                                val heavyScheduler = (application as DocScanApplication).heavyWorkScheduler
                                val stillQuad = heavyScheduler.withAdmission(
                                    DocumentCropMemoryCalculator.ESTIMATED_DETECTION_OVERHEAD_BYTES,
                                ) {
                                    val thumbnail = ProductImageDecoder.decodeThumbnailForDetection(temp)
                                    try { DocumentCropProcessor.detectCorners(thumbnail)?.toNormalized() }
                                    finally { thumbnail.recycle() }
                                }

                                val decision = DocumentCapturePolicy.decide(stillQuad, liveSnapshot, jpegSpec,
                                    generationAtShutter, shutterTimestampMs)
                                if (decision == DocumentCaptureDecision.NO_STILL_BOUNDARY) {
                                    cropStatusRes = R.string.document_not_detected_full_frame_saved
                                } else {
                                    if (decision == DocumentCaptureDecision.BOUNDARIES_DISAGREE) {
                                        cropStatusRes = R.string.document_boundaries_not_confirmed_full_frame_saved
                                    } else {
                                        val cropQuad = requireNotNull(stillQuad)
                                        val work = DocumentCropMemoryCalculator.planWarp(orientedW, orientedH, cropQuad,
                                            maxBudgetBytes = heavyScheduler.decodedByteBudget)
                                        val output = File(cacheDir, "rectified-${System.nanoTime()}.jpg")
                                        prepared = output
                                        heavyScheduler.withAdmission(work.workingSetBytes) {
                                            val oriented = ProductImageDecoder.decodePrecisely(temp, work.sourcePixelLimit)
                                            try {
                                                val rectified = DocumentCropProcessor.rectifyManual(oriented, cropQuad)
                                                try {
                                                    val finalBitmap = if (captureBlackAndWhiteMode) {
                                                        applyBlackAndWhiteFilter(rectified)
                                                    } else rectified
                                                    try {
                                                        FileOutputStream(output).use { out ->
                                                            check(finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                                                            out.fd.sync()
                                                        }
                                                    } finally {
                                                        if (finalBitmap !== rectified) finalBitmap.recycle()
                                                    }
                                                } finally { rectified.recycle() }
                                            } finally { oriented.recycle() }
                                        }
                                        wasRectified = true
                                        cropStatusRes = R.string.document_rectified_and_cropped
                                    }
                                }
                            }
                        } catch (failure: Exception) {
                            android.util.Log.w("DocScan", "Rectification unavailable; keeping camera source", failure)
                            prepared?.delete()
                            prepared = null
                            wasRectified = false
                            cropStatusRes = R.string.document_save_error_kept_original
                        } catch (memory: OutOfMemoryError) {
                            android.util.Log.w("DocScan", "Not enough memory to rectify; retaining camera source", memory)
                            prepared?.delete()
                            prepared = null
                            wasRectified = false
                            cropStatusRes = R.string.document_save_oom_kept_original
                        }
                        if (captureBlackAndWhiteMode && prepared == null) {
                            try {
                                val heavyScheduler = (application as DocScanApplication).heavyWorkScheduler
                                val output = File(cacheDir, "bw-${System.nanoTime()}.jpg")
                                val workingBytes = temp.length() * 4L
                                heavyScheduler.withAdmission(workingBytes) {
                                    val decoded = ProductImageDecoder.decodePrecisely(temp, maxPixels = 16L * 1024L * 1024L)
                                    try {
                                        val bwBitmap = applyBlackAndWhiteFilter(decoded)
                                        try {
                                            FileOutputStream(output).use { out ->
                                                check(bwBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                                                out.fd.sync()
                                            }
                                            prepared = output
                                        } finally {
                                            if (bwBitmap !== decoded) bwBitmap.recycle()
                                        }
                                    } finally {
                                        decoded.recycle()
                                    }
                                }
                            } catch (failure: Exception) {
                                android.util.Log.w("DocScan", "Black-and-white conversion failed; retaining original", failure)
                                prepared?.delete()
                                prepared = null
                            }
                        }
                        val artifact = try {
                            prepared?.let { FileInputStream(it).use(activeStorage::stageCapturedJpeg) } ?: captureSource
                        } finally { prepared?.delete() }
                        val affectedPageId: PageId
                        if (pageBeingRetaken != null) {
                            activeStorage.replacePageImage(pageBeingRetaken, artifact, captureSource)
                            affectedPageId = pageBeingRetaken.pageId
                        } else {
                            val pageId = PageId.new()
                            affectedPageId = pageId
                            val versionId = ImageVersionId.new()
                            activeStorage.commitCapture(
                                CaptureCommand(
                                    workspaceId = activeStorage.workspaceId,
                                    pageId = pageId,
                                    imageVersionId = versionId,
                                    artifact = artifact,
                                    selected = false,
                                    makeActive = true,
                                    coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                                    processingDesiredState = OCR_OPERATION_KEY,
                                    sourceProvenance = "CAMERA",
                                    autoSelectWhenReady = true,
                                    captureSource = captureSource,
                                ),
                            )
                        }
                        val projection = activeStorage.workspaceProjection()
                        if (autoOcrEnabled) {
                            projection.pages.firstOrNull { it.pageId == affectedPageId }?.let {
                                scheduleOcr(activeStorage, it)
                            }
                        }
                        runOnUiThread {
                            captureButton.isEnabled = imageCapture != null
                            retakeTarget = null
                            if (cropStatusRes == R.string.document_save_error_kept_original || cropStatusRes == R.string.document_save_oom_kept_original) {
                                recordError(getString(cropStatusRes))
                            } else if (cropStatusRes != null) {
                                status.text = getString(cropStatusRes)
                            } else {
                                status.text = getString(if (pageBeingRetaken == null) R.string.capture_saved else R.string.retake_saved)
                            }
                            renderPages(projection)
                        }
                    } catch (failure: Exception) {
                        runOnUiThread {
                            captureButton.isEnabled = imageCapture != null
                            retakeTarget = null
                            recordError(getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName))
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                temp.delete()
                captureButton.isEnabled = imageCapture != null
                retakeTarget = null
                recordError(getString(R.string.capture_error, exception.message ?: exception.imageCaptureError.toString()))
            }
        })
    }
```

#### `app/src/main/kotlin/app/docscan/android/ProductActivity.kt` — `showDocumentCropEditor`, строки 4731–4845

```kotlin
    private fun showDocumentCropEditor(page: app.docscan.core.store.StoredPage) {
        if (!canInteractWorkspace()) return
        val activeStorage = storage ?: return
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.auto_crop_preview_description)
        }
        val message = TextView(this).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.auto_crop_detecting)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(message, matchWrap())
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(dp(96), minOf(dp(440), root.height - dp(400)))))
        }
        var candidateBitmap: android.graphics.Bitmap? = null
        var candidatePlan: DocumentCropPlan? = null
        var candidateReady = false
        val dialog = dialogHost.builder(this)
            .setTitle(R.string.auto_crop_title)
            .setView(content)
            .setNegativeButton(R.string.edit_discard, null)
            .setPositiveButton(R.string.auto_crop_apply, null)
            .create()
        dialog.setOnDismissListener {
            preview.setImageDrawable(null)
            candidateBitmap?.recycle()
            candidateBitmap = null
            candidatePlan = null
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK
                || event.action != android.view.KeyEvent.ACTION_UP
            ) return@setOnKeyListener false
            if (!candidateReady) return@setOnKeyListener false
            dialogHost.builder(this)
                .setMessage(R.string.edit_discard_confirmation)
                .setNegativeButton(R.string.edit_keep_editing, null)
                .setPositiveButton(R.string.edit_discard) { _, _ -> dialog.dismiss() }
                .show()
            true
        }
        dialog.show()
        val applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply { isEnabled = false }
        applyButton.setOnClickListener {
            val approvedPlan = candidatePlan ?: return@setOnClickListener
            if (!candidateReady) return@setOnClickListener
            dialog.dismiss()
            status.text = getString(R.string.auto_crop_applying)
            io.execute {
                try {
                    val projection = activeStorage.applyDocumentCrop(
                        approvedPlan,
                        (application as DocScanApplication).heavyWorkScheduler,
                    )
                    if (autoOcrEnabled) {
                        projection.pages.firstOrNull { it.pageId == approvedPlan.pageId }?.let { updatedPage ->
                            scheduleOcr(activeStorage, updatedPage, force = true)
                        }
                    }
                    runOnUiThread {
                        status.text = getString(R.string.document_manual_crop_saved)
                        renderPages(projection)
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        val errorMsg = if (failure is IllegalStateException && failure.message?.contains("устарел") == true) {
                            getString(R.string.document_crop_plan_stale)
                        } else {
                            getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                        }
                        recordError(errorMsg)
                    }
                } catch (_: OutOfMemoryError) {
                    runOnUiThread { recordError(getString(R.string.document_crop_oom_page_unchanged)) }
                }
            }
        }
        (application as DocScanApplication).let { app ->
            io.execute {
                try {
                    val result = activeStorage.createDocumentCropPreview(page, app.heavyWorkScheduler)
                    runOnUiThread {
                        if (dialog.isShowing) {
                            if (result == null) {
                                message.text = getString(R.string.auto_crop_not_found)
                            } else {
                                candidateBitmap = result.previewBitmap
                                candidatePlan = result.plan
                                candidateReady = true
                                preview.setImageBitmap(result.previewBitmap)
                                message.text = getString(R.string.auto_crop_preview_ready)
                                applyButton.isEnabled = true
                            }
                        } else result?.previewBitmap?.recycle()
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        if (dialog.isShowing) message.text = getString(
                            R.string.auto_crop_failed,
                            failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                } catch (_: OutOfMemoryError) {
                    runOnUiThread {
                        if (dialog.isShowing) message.text = getString(R.string.document_crop_oom_page_unchanged)
                    }
                }
            }
        }
    }

```

#### `app/src/main/kotlin/app/docscan/android/ProductActivity.kt` — `showCropDialogForViewer`, строки 3562–3767

```kotlin
    private fun showCropDialogForViewer(page: app.docscan.core.store.StoredPage) {
        val activeStorage = storage ?: return
        val imageFile = activeStorage.artifactPath(page.artifactRelativePath).toFile()
        if (!imageFile.exists()) return

        val selection = CropSelectionView(this).apply {
            contentDescription = getString(R.string.manual_crop_view_description)
        }
        var sourceBitmap: android.graphics.Bitmap? = null
        var cropApplying = false
        var cropDismissed = false
        val cropViewerAtStart = pageViewer
        val cropGenerationAtStart = pageViewerGeneration

        val cropRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF111316.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF27272A.toInt())
            }
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val backBtn = Button(this).apply {
            text = "⏎"
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(radiusDp = 18)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        }

        val saveTopBtn = Button(this).apply {
            text = "Сохранить"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0xFF2563EB.toInt(), pressedBg = 0xFF1D4ED8.toInt(), radiusDp = 8)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isEnabled = false
        }

        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        topBar.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, dp(36), 1f))
        topBar.addView(saveTopBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        cropRoot.addView(topBar, matchWrap())

        val cropHeight = maxOf(dp(220), minOf(dp(440), root.height - dp(180)))
        cropRoot.addView(selection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cropHeight))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = "Отмена"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0x22FFFFFF.toInt(), pressedBg = 0x44FFFFFF.toInt(), strokeColor = 0x44FFFFFF.toInt(), radiusDp = 10)
        }

        val applyBtn = Button(this).apply {
            text = "Применить"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = modernButtonDrawable(normalBg = 0xFF2563EB.toInt(), pressedBg = 0xFF1D4ED8.toInt(), radiusDp = 10)
            isEnabled = false
        }

        bottomBar.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        bottomBar.addView(applyBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        cropRoot.addView(bottomBar, matchWrap())

        val cropDialog = dialogHost.builder(this)
            .setView(cropRoot)
            .create()
        cropDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        cropDialog.setCanceledOnTouchOutside(false)
        cropDialog.setOnDismissListener {
            cropDismissed = true
            selection.bitmap = null
            if (!cropApplying) {
                sourceBitmap?.recycle()
                sourceBitmap = null
            }
        }

        fun executeCrop() {
            if (cropApplying) return
            val source = sourceBitmap ?: return
            val quad = selection.quadrilateral
            cropApplying = true
            saveTopBtn.isEnabled = false
            applyBtn.isEnabled = false
            status.text = getString(R.string.edit_applying)
            io.execute {
                var cropped: android.graphics.Bitmap? = null
                try {
                    cropped = (application as DocScanApplication).heavyWorkScheduler.withAdmission(
                        EDIT_WORKING_SET_BYTES,
                    ) { PageImageAdjustmentProcessor.render(source, PageImageEditState(crop = quad)) }
                    val newProjection = activeStorage.applyPageImageEdit(page, cropped)
                    runOnUiThread {
                        cropApplying = false
                        cropDialog.dismiss()
                        if (cropDismissed) {
                            sourceBitmap?.recycle()
                            sourceBitmap = null
                        }
                        renderPages(newProjection)
                        if (pageViewer === cropViewerAtStart && pageViewer?.isShowing == true
                            && pageViewerGeneration == cropGenerationAtStart
                            && pageViewerPages.getOrNull(pageViewerIndex)?.pageId == page.pageId
                        ) {
                            (pageViewerImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
                            pageViewerImage?.setImageBitmap(cropped)
                            pageViewerPages = newProjection.pages.toList()
                            renderPages(newProjection)
                            refreshViewerStrip(newProjection.pages)
                            syncViewerStripActive()
                        } else {
                            cropped.recycle()
                        }
                        status.text = getString(R.string.edit_applied)
                    }
                } catch (failure: Exception) {
                    runOnUiThread {
                        cropApplying = false
                        if (cropDismissed) {
                            sourceBitmap?.recycle()
                            sourceBitmap = null
                        } else {
                            saveTopBtn.isEnabled = true
                            applyBtn.isEnabled = true
                        }
                        status.text = getString(R.string.capture_error, failure.message ?: failure.javaClass.simpleName)
                    }
                }
            }
        }

        backBtn.setOnClickListener { cropDialog.dismiss() }
        cancelBtn.setOnClickListener { cropDialog.dismiss() }
        saveTopBtn.setOnClickListener { executeCrop() }
        applyBtn.setOnClickListener { executeCrop() }

        cropDialog.show()

        io.execute {
            try {
                val decoded = decodeEditableImage(imageFile) ?: throw IllegalArgumentException("Cannot decode image")
                sourceBitmap = decoded

                // Автоматическая детекция границ документа по алгоритмам «зелёных линий» (DocumentCropProcessor)
                val detectedQuad = try {
                    val detectionThumb = try {
                        ProductImageDecoder.decodeThumbnailForDetection(imageFile, maxSide = 720)
                    } catch (_: Throwable) {
                        null
                    }
                    if (detectionThumb != null) {
                        try {
                            DocumentCropProcessor.detectCorners(detectionThumb)?.toNormalized()
                        } finally {
                            detectionThumb.recycle()
                        }
                    } else {
                        DocumentCropProcessor.detectCorners(decoded)?.toNormalized()
                    }
                } catch (_: Throwable) {
                    null
                }

                runOnUiThread {
                    if (cropDialog.isShowing) {
                        selection.bitmap = decoded
                        if (detectedQuad != null) {
                            selection.quadrilateral = detectedQuad
                        }
                        saveTopBtn.isEnabled = true
                        applyBtn.isEnabled = true
                    } else {
                        decoded.recycle()
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (cropDialog.isShowing) {
                        recordError(getString(R.string.edit_preview_failed, failure.message ?: ""))
                    }
                }
            }
        }
    }
```

#### `app/src/main/kotlin/app/docscan/android/ProductStorage.kt` — `createDocumentCropPreview`, строки 706–743

```kotlin
    fun createDocumentCropPreview(
        page: StoredPage,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
    ): DocumentCropPreviewResult? {
        val sourceFile = artifactPath(page.artifactRelativePath).toFile()
        if (!sourceFile.exists()) return null

        val detection = scheduler.withAdmission(DocumentCropMemoryCalculator.ESTIMATED_DETECTION_OVERHEAD_BYTES) {
            val thumbnail = ProductImageDecoder.decodeThumbnailForDetection(sourceFile)
            try { documentCornerDetector(thumbnail) } finally { thumbnail.recycle() }
        } ?: return null

        val cropQuad = detection.toNormalized()
        val bounds = DocumentCropMemoryCalculator.readOrientedImageBounds(sourceFile) ?: (detection.imageWidth to detection.imageHeight)

        val plan = DocumentCropPlan(
            pageId = page.pageId,
            initialImageVersionId = page.imageVersionId,
            initialContentRevision = page.contentRevision,
            artifactRelativePath = page.artifactRelativePath,
            orientedWidth = bounds.first,
            orientedHeight = bounds.second,
            cropQuad = cropQuad,
        )

        // Bound both the preview source and its output; the returned thumbnail remains UI-owned.
        val previewRatio = minOf(1.0, 1280.0 / maxOf(bounds.first, bounds.second))
        val previewLimit = maxOf(4L, (bounds.first.toLong() * bounds.second * previewRatio * previewRatio).toLong())
        val work = DocumentCropMemoryCalculator.planWarp(bounds.first, bounds.second, cropQuad,
            maxBudgetBytes = scheduler.decodedByteBudget, maxSourcePixelsLimit = previewLimit)
        val previewBitmap = scheduler.withAdmission(work.workingSetBytes) {
            val previewSource = ProductImageDecoder.decodePrecisely(sourceFile, work.sourcePixelLimit)
            try { documentRectifier(previewSource, cropQuad) }
            finally { previewSource.recycle() }
        }

        return DocumentCropPreviewResult(previewBitmap = previewBitmap, plan = plan)
    }
```

#### `app/src/main/kotlin/app/docscan/android/ProductStorage.kt` — `applyDocumentCrop`, строки 745–790

```kotlin
    fun applyDocumentCrop(
        plan: DocumentCropPlan,
        scheduler: app.docscan.core.schedule.HeavyWorkScheduler,
    ): WorkspaceProjection {
        val currentPages = store.workspaceProjection(workspaceId).pages
        val currentPage = currentPages.find { it.pageId == plan.pageId }
            ?: throw IllegalStateException("Страница была удалена из рабочего набора")
        if (currentPage.imageVersionId != plan.initialImageVersionId || currentPage.contentRevision != plan.initialContentRevision ||
            currentPage.artifactRelativePath != plan.artifactRelativePath) {
            throw IllegalStateException("План обрезки устарел: страница была изменена")
        }

        val sourceFile = artifactPath(plan.artifactRelativePath).toFile()
        if (!sourceFile.exists()) throw IllegalStateException("Исходный файл страницы не найден")

        val temporary = File(appContext.cacheDir, "crop-${UUID.randomUUID()}.jpg")
        try {
            val bounds = DocumentCropMemoryCalculator.readOrientedImageBounds(sourceFile) ?: (plan.orientedWidth to plan.orientedHeight)
            val work = DocumentCropMemoryCalculator.planWarp(bounds.first, bounds.second, plan.cropQuad,
                maxBudgetBytes = scheduler.decodedByteBudget)
            val artifact = scheduler.withAdmission(work.workingSetBytes) {
                val source = ProductImageDecoder.decodePrecisely(sourceFile, work.sourcePixelLimit)
                try {
                    val candidate = documentRectifier(source, plan.cropQuad)
                    try {
                        FileOutputStream(temporary).use { output ->
                            check(candidate.compress(Bitmap.CompressFormat.JPEG, DOCUMENT_CROP_JPEG_QUALITY, output)) {
                                "Cropped image could not be encoded"
                            }
                            output.fd.sync()
                        }
                    } finally {
                        candidate.recycle()
                    }
                } finally {
                    source.recycle()
                }
                FileInputStream(temporary).use(::stageCapturedJpeg).also(::validateImageArtifact)
            }
            val existingSource = store.cameraSource(currentPage.pageId)
            replacePageImage(currentPage, artifact, plan.algorithmVersion, existingSource)
            return workspaceProjection()
        } finally {
            temporary.delete()
        }
    }
```

### 16.4. Исходники проверок (не новый отчёт о прогоне)

#### `app/src/test/kotlin/app/docscan/android/DocumentLiveDetectionCoordinatorTest.kt`

```kotlin
package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLiveDetectionCoordinatorTest {

    private val spec = AnalysisBufferSpec(
        bufferWidth = 1280,
        bufferHeight = 960,
        cropRect = BufferRect(left = 160, top = 0, right = 1120, bottom = 960),
        rotationDegrees = 0,
    )

    private val candidatePoints1 = floatArrayOf(
        200f, 100f,  // TL
        1000f, 100f, // TR
        1000f, 900f, // BR
        200f, 900f,  // BL
    )

    private val candidatePointsClose = floatArrayOf(
        202f, 101f,
        998f, 102f,
        1001f, 898f,
        199f, 901f,
    )

    private val candidatePointsJump = floatArrayOf(
        500f, 400f,
        800f, 400f,
        800f, 700f,
        500f, 700f,
    )

    @Test
    fun `t04 - initial single frame or false jump is provisional and not confirmed`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        // First frame
        val state1 = coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        assertEquals("Single frame must be PROVISIONAL", LiveDetectionStatus.PROVISIONAL, state1.status)
        assertFalse("Single frame must not be usable for agreement", state1.isUsableForAgreement)

        // Second frame: false jump
        currentTime += 100L
        val stateJump = coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.80, spec)
        assertEquals("Jumped frame must remain PROVISIONAL", LiveDetectionStatus.PROVISIONAL, stateJump.status)
        assertFalse("Jumped frame must not be usable for agreement", stateJump.isUsableForAgreement)
    }

    @Test
    fun `t05 - two consecutive consistent frames confirm the detection`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)

        currentTime += 100L
        val state2 = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals("Two consistent frames must be CONFIRMED", LiveDetectionStatus.CONFIRMED, state2.status)
        assertTrue("Confirmed detection must be usable for agreement", state2.isUsableForAgreement)
    }

    @Test
    fun `t06 - misses within ttl become held, and after ttl become expired`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        // Establish confirmed state
        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        // Miss at +100ms (within 300ms TTL)
        currentTime += 100L
        val heldState = coordinator.onFrameMissing(gen, currentTime)
        assertEquals("Miss within TTL must be HELD", LiveDetectionStatus.HELD, heldState.status)
        assertFalse("Held contour must not be usable for agreement", heldState.isUsableForAgreement)

        // Miss at +350ms (exceeding TTL)
        currentTime += 250L
        val expiredState = coordinator.onFrameMissing(gen, currentTime)
        assertEquals("Miss beyond TTL must be EXPIRED", LiveDetectionStatus.EXPIRED, expiredState.status)
        assertFalse(expiredState.isUsableForAgreement)
    }

    @Test
    fun `t07 - mode invalidation and old generation callbacks are ignored`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val oldGen = coordinator.currentGeneration()

        coordinator.onFrameDetected(oldGen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        coordinator.onFrameDetected(oldGen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.CONFIRMED, coordinator.getPublishedState().status)

        // Mode toggled off or activity paused -> invalidate
        coordinator.invalidate()
        val newGen = coordinator.currentGeneration()
        assertNotEquals("Generation must increment on invalidate", oldGen, newGen)
        assertEquals(LiveDetectionStatus.EXPIRED, coordinator.getPublishedState().status)

        // Late callback from old generation arrives on background thread
        currentTime += 50L
        val staleFirst = coordinator.onFrameDetected(oldGen, currentTime, candidatePointsClose, 0.90, spec)
        currentTime += 100L
        val staleSecond = coordinator.onFrameDetected(oldGen, currentTime, candidatePointsClose, 0.90, spec)
        assertEquals(LiveDetectionStatus.EXPIRED, staleFirst.status)
        assertEquals(LiveDetectionStatus.EXPIRED, staleSecond.status)
        assertEquals("Late callback from old generation must be ignored", LiveDetectionStatus.EXPIRED, coordinator.getPublishedState().status)

        // The caller must use the fresh generation for frames after toggling back on.
        val firstFresh = coordinator.onFrameDetected(newGen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        val secondFresh = coordinator.onFrameDetected(newGen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstFresh.status)
        assertEquals(LiveDetectionStatus.CONFIRMED, secondFresh.status)
        assertEquals(newGen, secondFresh.generationId)
    }

    @Test
    fun `t05 - jump to a new document is confirmed by two stable frames`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        assertEquals(
            LiveDetectionStatus.CONFIRMED,
            coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec).status,
        )

        currentTime += 100L
        val firstB = coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.80, spec)
        assertEquals("A single jump may hold A during its TTL", LiveDetectionStatus.HELD, firstB.status)

        currentTime += 100L
        val confirmedB = coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.80, spec)

        assertEquals("Two stable B observations must replace A", LiveDetectionStatus.CONFIRMED, confirmedB.status)
        assertTrue(confirmedB.isUsableForAgreement)
        assertEquals(
            CameraGeometryTransform.analysisBufferQuadToNormalizedViewPort(candidatePointsJump, spec),
            confirmedB.quadInNormalizedViewPort,
        )
    }

    @Test
    fun `provisional candidate separated by more than ttl is not confirmed`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 301L
        val lateSecond = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        assertNotEquals(LiveDetectionStatus.CONFIRMED, lateSecond.status)
    }

    @Test
    fun `confirmed state expires after a gap and next frame starts a new candidate`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 100L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        currentTime += 301L
        val expired = coordinator.onFrameMissing(gen, currentTime)
        assertEquals(LiveDetectionStatus.EXPIRED, expired.status)
        assertFalse(expired.isUsableForAgreement)

        currentTime += 1L
        val firstAfterGap = coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstAfterGap.status)
        currentTime += 100L
        val secondAfterGap = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.CONFIRMED, secondAfterGap.status)
    }

    @Test
    fun `missing frame breaks provisional confirmation streak`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        val firstCandidate = coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstCandidate.status)

        currentTime += 100L
        coordinator.onFrameMissing(gen, currentTime)
        currentTime += 100L
        val afterGap = coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.87, spec)

        assertEquals("A missing observation must break the two-frame streak", LiveDetectionStatus.PROVISIONAL, afterGap.status)
    }

    @Test
    fun `geometry change starts a fresh generation and rejects old geometry callbacks`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val oldGeneration = coordinator.currentGeneration()
        val initialFrameGeneration = requireNotNull(coordinator.generationForFrame(spec, oldGeneration))
        assertEquals(oldGeneration, initialFrameGeneration)
        coordinator.onFrameDetected(initialFrameGeneration, currentTime, candidatePoints1, 0.85, spec)

        val changedSpec = AnalysisBufferSpec(
            bufferWidth = 1920,
            bufferHeight = 1080,
            cropRect = BufferRect(left = 240, top = 0, right = 1680, bottom = 1080),
            rotationDegrees = 90,
        )
        val newGeneration = requireNotNull(coordinator.generationForFrame(changedSpec, oldGeneration))
        assertNotEquals(oldGeneration, newGeneration)
        currentTime += 100L
        val stale = coordinator.onFrameDetected(oldGeneration, currentTime, candidatePointsClose, 0.87, spec)
        assertEquals(LiveDetectionStatus.IDLE, stale.status)
        assertEquals(newGeneration, stale.generationId)

        val pointsInChangedGeometry = floatArrayOf(
            400f, 100f,
            1500f, 100f,
            1500f, 900f,
            400f, 900f,
        )
        val firstNewGeometry = coordinator.onFrameDetected(newGeneration, currentTime, pointsInChangedGeometry, 0.85, changedSpec)
        currentTime += 100L
        val secondNewGeometry = coordinator.onFrameDetected(newGeneration, currentTime, pointsInChangedGeometry.clone(), 0.87, changedSpec)
        assertEquals(LiveDetectionStatus.PROVISIONAL, firstNewGeometry.status)
        assertEquals(LiveDetectionStatus.CONFIRMED, secondNewGeometry.status)
        assertEquals(changedSpec, secondNewGeometry.analysisSpec)
    }

    @Test
    fun `captureSnapshot captures frozen immutable copy and rejects expired state`() {
        var currentTime = 1000L
        val coordinator = DocumentLiveDetectionCoordinator(maxTtlMs = 300L, clock = { currentTime })
        val gen = coordinator.currentGeneration()

        coordinator.onFrameDetected(gen, currentTime, candidatePoints1, 0.85, spec)
        currentTime += 80L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsClose, 0.85, spec)

        val snapshot = coordinator.captureSnapshot()
        assertEquals(LiveDetectionStatus.CONFIRMED, snapshot.status)
        assertTrue(snapshot.isUsableForAgreement)

        val publishedFirstX = coordinator.getPublishedState().quadInBufferPixels!![0]
        snapshot.quadInBufferPixels!![0] = -100f
        assertEquals(
            "Snapshot mutation must not mutate published state",
            publishedFirstX,
            coordinator.getPublishedState().quadInBufferPixels!![0],
            0f,
        )

        // Later frames in background thread do not affect captured snapshot
        currentTime += 80L
        coordinator.onFrameDetected(gen, currentTime, candidatePointsJump, 0.70, spec)
        assertEquals(LiveDetectionStatus.CONFIRMED, snapshot.status)

        // Expired snapshot
        currentTime += 500L
        val expiredSnapshot = coordinator.captureSnapshot()
        assertEquals(LiveDetectionStatus.EXPIRED, expiredSnapshot.status)
        assertFalse(expiredSnapshot.isUsableForAgreement)
    }
}
```

#### `app/src/test/kotlin/app/docscan/android/CameraGeometryTransformTest.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraGeometryTransformTest {

    @Test
    fun `dm01 control example - buffer 1280x960 with cropRect 160,0,1120,960 maps 320,240 to 1 div 6, 1 div 4`() {
        val spec = AnalysisBufferSpec(
            bufferWidth = 1280,
            bufferHeight = 960,
            cropRect = BufferRect(left = 160, top = 0, right = 1120, bottom = 960),
            rotationDegrees = 0,
        )

        val normalized = CameraGeometryTransform.analysisBufferPointToNormalizedViewPort(320.0, 240.0, spec)

        val expectedX = 1.0 / 6.0
        val expectedY = 1.0 / 4.0
        assertEquals("Normalized X should be 1/6", expectedX, normalized.x, 1e-6)
        assertEquals("Normalized Y should be 1/4", expectedY, normalized.y, 1e-6)

        // Check inverse mapping
        val (backX, backY) = CameraGeometryTransform.normalizedViewPortToAnalysisBufferPoint(normalized, spec)
        assertEquals("Inverted buffer X should match original pixel", 320.0, backX, 1e-5)
        assertEquals("Inverted buffer Y should match original pixel", 240.0, backY, 1e-5)
    }

    @Test
    fun `rotations 0 90 180 270 bidirectional round-trip accuracy within 1e-5 normalized and 1 pixel`() {
        val bufferWidth = 1920
        val bufferHeight = 1080
        val cropRect = BufferRect(left = 240, top = 60, right = 1680, bottom = 1020)

        val testPoints = listOf(
            240.0 to 60.0,
            1680.0 to 60.0,
            1680.0 to 1020.0,
            240.0 to 1020.0,
            960.0 to 540.0,
            500.0 to 300.0,
            1200.0 to 800.0,
        )

        for (rotation in listOf(0, 90, 180, 270)) {
            val spec = AnalysisBufferSpec(
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                cropRect = cropRect,
                rotationDegrees = rotation,
            )

            for ((px, py) in testPoints) {
                val norm = CameraGeometryTransform.analysisBufferPointToNormalizedViewPort(px, py, spec)
                assertTrue("Normalized x must be in [0, 1] for rotation $rotation", norm.x in -1e-6..1.0 + 1e-6)
                assertTrue("Normalized y must be in [0, 1] for rotation $rotation", norm.y in -1e-6..1.0 + 1e-6)

                val (rx, ry) = CameraGeometryTransform.normalizedViewPortToAnalysisBufferPoint(norm, spec)
                val errX = abs(rx - px)
                val errY = abs(ry - py)
                assertTrue("Error in X for rotation $rotation must be < 1 px (was $errX)", errX <= 1.0)
                assertTrue("Error in Y for rotation $rotation must be < 1 px (was $errY)", errY <= 1.0)
                assertTrue("Error in X for rotation $rotation must be < 1e-5", errX < 1e-4)
                assertTrue("Error in Y for rotation $rotation must be < 1e-5", errY < 1e-4)
            }
        }
    }

    @Test
    fun `jpeg coordinate mapping with 90 degree portrait orientation and different resolutions`() {
        // Analysis buffer in landscape (1280x960), cropped to 720x960 (3:4 portrait), rotated 90 deg -> 960x720 (4:3)
        // or cropped to 960x720, rotated 90 deg -> 720x960 (3:4 portrait)
        val spec = AnalysisBufferSpec(
            bufferWidth = 1280,
            bufferHeight = 960,
            cropRect = BufferRect(left = 160, top = 0, right = 1120, bottom = 960), // 960x960 (1:1)
            rotationDegrees = 90,
        )
        val jpegSpec = JpegGeometrySpec(orientedWidth = 3000, orientedHeight = 3000) // 1:1

        assertTrue(CameraGeometryTransform.isGeometryCompatible(spec, jpegSpec))

        // Center of cropRect (left=160, width=960 -> center=640; top=0, height=960 -> center=480)
        val norm = CameraGeometryTransform.analysisBufferPointToNormalizedViewPort(640.0, 480.0, spec)
        assertEquals(0.5, norm.x, 1e-5)
        assertEquals(0.5, norm.y, 1e-5)

        val (jpegX, jpegY) = CameraGeometryTransform.normalizedViewPortToJpegPixel(norm, jpegSpec)
        assertEquals(1500.0, jpegX, 1.0)
        assertEquals(1500.0, jpegY, 1.0)
    }

    @Test
    fun `incompatible aspect ratio is detected to disable stale live quad fallback`() {
        // ViewPort is 16:9 portrait (oriented: 9:16 = 0.5625)
        val spec = AnalysisBufferSpec(
            bufferWidth = 1920,
            bufferHeight = 1080,
            cropRect = BufferRect(left = 0, top = 0, right = 1920, bottom = 1080),
            rotationDegrees = 90, // oriented: 1080 x 1920
        )
        // Saved JPEG is 4:3 portrait: 3000 x 4000 (aspect 0.75)
        val jpegSpec = JpegGeometrySpec(orientedWidth = 3000, orientedHeight = 4000)

        assertFalse("Aspect ratios 9:16 and 3:4 must be flagged as incompatible",
            CameraGeometryTransform.isGeometryCompatible(spec, jpegSpec))
    }
}
```

#### `app/src/test/kotlin/app/docscan/android/DocumentCapturePolicyTest.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentCapturePolicyTest {

    private val generation = 7L
    private val shutterTimeMs = 1_000L
    private val analysisSpec = AnalysisBufferSpec(
        bufferWidth = 800,
        bufferHeight = 800,
        cropRect = BufferRect(80, 80, 720, 720),
        rotationDegrees = 0,
    )
    private val compatibleJpeg = JpegGeometrySpec(orientedWidth = 3000, orientedHeight = 3000)
    private val document = quad(0.10, 0.10, 0.90, 0.90)

    @Test
    fun `good still boundary rectifies when live boundary is absent`() {
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, stillQuad = document, live = null)
    }

    @Test
    fun `good still boundary rectifies when live frame is held stale or from another generation`() {
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live(status = LiveDetectionStatus.HELD))
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live(timestampMs = shutterTimeMs - 301L))
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live(generationId = generation + 1L))
    }

    @Test
    fun `good still boundary rectifies when analysis and jpeg geometry are incompatible`() {
        val incompatibleAnalysis = AnalysisBufferSpec(
            bufferWidth = 800,
            bufferHeight = 600,
            cropRect = BufferRect(80, 60, 720, 540),
            rotationDegrees = 0,
        )
        val incompatibleLive = live(spec = incompatibleAnalysis)

        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, incompatibleLive)
    }

    @Test
    fun `missing still boundary never borrows a live boundary`() {
        assertDecision(DocumentCaptureDecision.NO_STILL_BOUNDARY, stillQuad = null, live = live())
    }

    @Test
    fun `matching live and still boundaries allow rectification`() {
        assertDecision(DocumentCaptureDecision.RECTIFY_STILL, document, live())
    }

    @Test
    fun `one bad live corner vetoes automatic rectification`() {
        val oneBadCorner = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.48, 0.12),
            topRight = NormalizedCropPoint(0.90, 0.10),
            bottomRight = NormalizedCropPoint(0.90, 0.90),
            bottomLeft = NormalizedCropPoint(0.10, 0.90),
        )

        assertDecision(
            DocumentCaptureDecision.BOUNDARIES_DISAGREE,
            document,
            live(quad = oneBadCorner),
        )
    }

    private fun assertDecision(
        expected: DocumentCaptureDecision,
        stillQuad: CropQuadrilateral?,
        live: LiveDetectionState?,
    ) {
        assertEquals(
            expected,
            DocumentCapturePolicy.decide(
                stillQuad = stillQuad,
                live = live,
                jpeg = compatibleJpeg,
                generationAtShutter = generation,
                shutterTimestampMs = shutterTimeMs,
            ),
        )
    }

    private fun live(
        status: LiveDetectionStatus = LiveDetectionStatus.CONFIRMED,
        quad: CropQuadrilateral = document,
        generationId: Long = generation,
        timestampMs: Long = shutterTimeMs - 100L,
        spec: AnalysisBufferSpec = analysisSpec,
    ) = LiveDetectionState(
        generationId = generationId,
        status = status,
        analysisSpec = spec,
        quadInBufferPixels = floatArrayOf(160f, 160f, 640f, 160f, 640f, 640f, 160f, 640f),
        quadInNormalizedViewPort = quad,
        rawScore = 0.9,
        frameTimestampMonotonicMs = timestampMs,
        processedTimestampMonotonicMs = timestampMs,
    )

    private fun quad(left: Double, top: Double, right: Double, bottom: Double) = CropQuadrilateral(
        topLeft = NormalizedCropPoint(left, top),
        topRight = NormalizedCropPoint(right, top),
        bottomRight = NormalizedCropPoint(right, bottom),
        bottomLeft = NormalizedCropPoint(left, bottom),
    )
}
```

#### `app/src/test/kotlin/app/docscan/android/QuadGeometryUtilsTest.kt`

```kotlin
package app.docscan.android

import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadGeometryUtilsTest {

    @Test
    fun `canonicalizeCorners correctly orders cyclically shifted or shuffled corners`() {
        val pTL = NormalizedCropPoint(0.1, 0.1)
        val pTR = NormalizedCropPoint(0.9, 0.1)
        val pBR = NormalizedCropPoint(0.9, 0.9)
        val pBL = NormalizedCropPoint(0.1, 0.9)

        // Test shuffled permutations
        val permutations = listOf(
            listOf(pTL, pTR, pBR, pBL),
            listOf(pTR, pBR, pBL, pTL),
            listOf(pBR, pBL, pTL, pTR),
            listOf(pBL, pTL, pTR, pBR),
            listOf(pTL, pBL, pBR, pTR),
        )

        for (perm in permutations) {
            val ordered = QuadGeometryUtils.canonicalizeCorners(perm)
            assertEquals("TL should be pTL", pTL, ordered[0])
            assertEquals("TR should be pTR", pTR, ordered[1])
            assertEquals("BR should be pBR", pBR, ordered[2])
            assertEquals("BL should be pBL", pBL, ordered[3])
        }
    }

    @Test
    fun `polygonIoU matches expected values for identical, shifted, and disjoint quadrilaterals`() {
        val q1 = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.1, 0.1),
            topRight = NormalizedCropPoint(0.9, 0.1),
            bottomRight = NormalizedCropPoint(0.9, 0.9),
            bottomLeft = NormalizedCropPoint(0.1, 0.9),
        )

        // Identical quad -> IoU = 1.0
        val iouSame = QuadGeometryUtils.polygonIoU(q1, q1)
        assertEquals(1.0, iouSame, 1e-4)

        // Disjoint quad
        val qDisjoint = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.95, 0.95),
            topRight = NormalizedCropPoint(1.0, 0.95),
            bottomRight = NormalizedCropPoint(1.0, 1.0),
            bottomLeft = NormalizedCropPoint(0.95, 1.0),
        )
        val iouDisjoint = QuadGeometryUtils.polygonIoU(q1, qDisjoint)
        assertEquals(0.0, iouDisjoint, 1e-4)

        // Overlapping quads:
        // q1 is [0.1..0.7] x [0.1..0.7] -> width 0.6, height 0.6 -> Area1 = 0.36
        // qOverlap is [0.4..1.0] x [0.1..0.7] -> width 0.6, height 0.6 -> Area2 = 0.36
        // Overlap region: [0.4..0.7] x [0.1..0.7] -> width 0.3, height 0.6 -> InterArea = 0.18
        // UnionArea = 0.36 + 0.36 - 0.18 = 0.54
        // IoU = 0.18 / 0.54 = 1/3 ~ 0.3333
        val qBase = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.1, 0.1),
            topRight = NormalizedCropPoint(0.7, 0.1),
            bottomRight = NormalizedCropPoint(0.7, 0.7),
            bottomLeft = NormalizedCropPoint(0.1, 0.7),
        )
        val qOverlap = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.4, 0.1),
            topRight = NormalizedCropPoint(1.0, 0.1),
            bottomRight = NormalizedCropPoint(1.0, 0.7),
            bottomLeft = NormalizedCropPoint(0.4, 0.7),
        )
        val iouOverlap = QuadGeometryUtils.polygonIoU(qBase, qOverlap)
        assertEquals(1.0 / 3.0, iouOverlap, 1e-3)
    }

    @Test
    fun `checkAgreement passes for close quads and fails for substantial disagreement or single bad corner`() {
        val live = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.10, 0.10),
            topRight = NormalizedCropPoint(0.90, 0.10),
            bottomRight = NormalizedCropPoint(0.90, 0.90),
            bottomLeft = NormalizedCropPoint(0.10, 0.90),
        )

        // Very close quad (within 1% diagonal shift) -> should agree
        val close = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.105, 0.102),
            topRight = NormalizedCropPoint(0.898, 0.104),
            bottomRight = NormalizedCropPoint(0.903, 0.897),
            bottomLeft = NormalizedCropPoint(0.098, 0.902),
        )
        val resClose = QuadGeometryUtils.checkAgreement(live, close)
        assertTrue("Close quads must agree", resClose.agrees)
        assertTrue("IoU must be high (>0.90)", resClose.iou > 0.90)
        assertTrue("Relative corner shift must be <= 0.06", resClose.relativeCornerShift <= 0.06)

        // Substantial disagreement (one bad corner shifted by 15% of diagonal)
        // Diagonal is hypot(0.8, 0.8) = 1.131. 15% is ~0.17
        val oneBadCorner = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.28, 0.28), // Shifted by 0.25 (diagonal ratio ~0.22)
            topRight = NormalizedCropPoint(0.90, 0.10),
            bottomRight = NormalizedCropPoint(0.90, 0.90),
            bottomLeft = NormalizedCropPoint(0.10, 0.90),
        )
        val resBad = QuadGeometryUtils.checkAgreement(live, oneBadCorner)
        assertFalse("Single bad corner (>6% diagonal) must reject agreement", resBad.agrees)

        // Low IoU (< 0.80)
        val shifted = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.25, 0.25),
            topRight = NormalizedCropPoint(0.95, 0.25),
            bottomRight = NormalizedCropPoint(0.95, 0.95),
            bottomLeft = NormalizedCropPoint(0.25, 0.95),
        )
        val resShifted = QuadGeometryUtils.checkAgreement(live, shifted)
        assertFalse("Significantly shifted quad must reject agreement", resShifted.agrees)
    }
}
```

#### `app/src/test/kotlin/app/docscan/android/DocumentCropMemoryCalculatorTest.kt`

```kotlin
package app.docscan.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import org.junit.Test

class DocumentCropMemoryCalculatorTest {

    @Test
    fun estimateWarpWorkingSetBytes_doesNotOverflow32BitInt() {
        val largePixelCount = 50_000_000L // 50 MP
        val estimatedBytes = DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(largePixelCount)
        // 50M * 16 + 4MB = 800MB + 4MB = 804MB (well above Int.MAX_VALUE if it were in bits or multi-stage arithmetic)
        assertTrue(estimatedBytes > 0L)
        assertEquals(largePixelCount * 16L + 4L * 1024L * 1024L, estimatedBytes)

        val extremePixelCount = 500_000_000L // 500 MP
        val extremeBytes = DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(extremePixelCount)
        assertTrue("Estimated bytes must be positive and > Int.MAX_VALUE", extremeBytes > Int.MAX_VALUE.toLong())
    }

    @Test
    fun calculateBoundedPixels_capsWhenSourceExceedsBudget() {
        val width = 4000
        val height = 3000 // 12 MP
        val budgetBytes = 96L * 1024L * 1024L // 96 MiB

        val bounded = DocumentCropMemoryCalculator.calculateBoundedPixels(
            sourceWidth = width,
            sourceHeight = height,
            maxBudgetBytes = budgetBytes,
            maxSourcePixelsLimit = 20_000_000L,
        )

        val expectedCap = (budgetBytes - 4L * 1024L * 1024L) / 16L
        assertEquals(expectedCap, bounded)
        assertTrue(bounded < width.toLong() * height.toLong())
    }

    @Test
    fun calculateBoundedPixels_smallBudgetNeverExceedsItsEstimate() {
        val budgetBytes = 8L * 1024L * 1024L
        val bounded = DocumentCropMemoryCalculator.calculateBoundedPixels(
            sourceWidth = 4000,
            sourceHeight = 3000,
            maxBudgetBytes = budgetBytes,
        )

        assertTrue("Bounded pixels must fit inside the 8 MiB budget", bounded <= 262_144L)
        assertTrue(
            "The working-set estimate must fit the requested budget",
            DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(bounded) <= budgetBytes,
        )
    }

    @Test
    fun calculateBoundedPixels_rejectsBudgetBelowReservePlusOnePixel() {
        val minimumPossibleBudget = 4L * 1024L * 1024L + 16L
        val insufficientBudget = minimumPossibleBudget - 1L

        val result = runCatching {
            DocumentCropMemoryCalculator.calculateBoundedPixels(
                sourceWidth = 4000,
                sourceHeight = 3000,
                maxBudgetBytes = insufficientBudget,
            )
        }

        assertTrue("Insufficient budget must be rejected before allocation", result.isFailure)
    }

    @Test
    fun planWarp_accountsForPerspectiveOutputLargerThanDecodedSource() {
        val sourceWidth = 1000
        val sourceHeight = 800
        val quad = CropQuadrilateral(
            topLeft = NormalizedCropPoint(0.0, 0.5),
            topRight = NormalizedCropPoint(1.0, 0.0),
            bottomRight = NormalizedCropPoint(0.5, 1.0),
            bottomLeft = NormalizedCropPoint(0.0, 1.0),
        )

        val plan = DocumentCropMemoryCalculator.planWarp(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            quad = quad,
        )
        val decodedPixels = plan.decodedWidth.toLong() * plan.decodedHeight
        val outputPixels = plan.outputWidth.toLong() * plan.outputHeight

        assertTrue("Fixture must exercise an output larger than its source", outputPixels > decodedPixels)
        assertTrue(plan.workingSetBytes <= DocumentCropMemoryCalculator.DEFAULT_MAX_BUDGET_BYTES)
        assertEquals(
            plan.workingSetBytes,
            DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(decodedPixels, outputPixels),
        )
    }

    @Test
    fun planWarp_keepsOrdinaryPageWithinDefaultBudget() {
        val plan = DocumentCropMemoryCalculator.planWarp(
            sourceWidth = 4000,
            sourceHeight = 3000,
            quad = CropQuadrilateral(
                topLeft = NormalizedCropPoint(0.05, 0.04),
                topRight = NormalizedCropPoint(0.95, 0.04),
                bottomRight = NormalizedCropPoint(0.95, 0.96),
                bottomLeft = NormalizedCropPoint(0.05, 0.96),
            ),
        )

        assertTrue(plan.workingSetBytes <= DocumentCropMemoryCalculator.DEFAULT_MAX_BUDGET_BYTES)
        assertTrue(plan.decodedWidth.toLong() * plan.decodedHeight <= 12_000_000L)
    }

    @Test
    fun planWarp_rejectsBudgetTooSmallForReserveAndOnePixel() {
        val result = runCatching {
            DocumentCropMemoryCalculator.planWarp(
                sourceWidth = 1000,
                sourceHeight = 800,
                quad = CropQuadrilateral(
                    topLeft = NormalizedCropPoint(0.05, 0.05),
                    topRight = NormalizedCropPoint(0.95, 0.05),
                    bottomRight = NormalizedCropPoint(0.95, 0.95),
                    bottomLeft = NormalizedCropPoint(0.05, 0.95),
                ),
                maxBudgetBytes = 4L * 1024L * 1024L + 15L,
            )
        }

        assertTrue("An impossible warp plan must be rejected before allocation", result.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun planWarp_rejectsOnePixelThinSource() {
        DocumentCropMemoryCalculator.planWarp(
            sourceWidth = 1,
            sourceHeight = 1000,
            quad = CropQuadrilateral(
                topLeft = NormalizedCropPoint(0.0, 0.0),
                topRight = NormalizedCropPoint(1.0, 0.0),
                bottomRight = NormalizedCropPoint(1.0, 1.0),
                bottomLeft = NormalizedCropPoint(0.0, 1.0),
            ),
        )
    }

    @Test
    fun estimateWarpWorkingSetBytes_rejectsArithmeticOverflow() {
        val result = runCatching {
            DocumentCropMemoryCalculator.estimateWarpWorkingSetBytes(Long.MAX_VALUE)
        }

        assertTrue(
            "Overflow must be rejected or saturated instead of wrapping to a small estimate",
            result.isFailure || result.getOrNull() == Long.MAX_VALUE,
        )
    }

    @Test
    fun calculateBoundedPixels_preservesSourceWhenWithinBudget() {
        val width = 1000
        val height = 800 // 0.8 MP
        val budgetBytes = 96L * 1024L * 1024L

        val bounded = DocumentCropMemoryCalculator.calculateBoundedPixels(
            sourceWidth = width,
            sourceHeight = height,
            maxBudgetBytes = budgetBytes,
        )

        assertEquals(800_000L, bounded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculateBoundedPixels_rejectsZeroWidth() {
        DocumentCropMemoryCalculator.calculateBoundedPixels(0, 1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun calculateBoundedPixels_rejectsNegativeHeight() {
        DocumentCropMemoryCalculator.calculateBoundedPixels(1000, -5)
    }
}
```

#### `app/src/androidTest/kotlin/app/docscan/android/DocumentCropProcessorInstrumentedTest.kt`

```kotlin
package app.docscan.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import app.docscan.core.edit.CropQuadrilateral
import app.docscan.core.edit.NormalizedCropPoint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class DocumentCropProcessorInstrumentedTest {
    private data class Fixture(
        val name: String,
        val width: Int,
        val height: Int,
        val corners: List<Pair<Float, Float>>,
    )

    @Test
    fun rectifiesStrongPageBoundaryWithoutChangingSource() {
        val source = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.rgb(30, 30, 30))
        val page = Path().apply {
            moveTo(130f, 100f)
            lineTo(850f, 155f)
            lineTo(790f, 1280f)
            lineTo(190f, 1320f)
            close()
        }
        canvas.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val originalCorner = source.getPixel(0, 0)

        val result = DocumentCropProcessor.rectify(source)

        assertNotNull(result)
        assertEquals(originalCorner, source.getPixel(0, 0))
        assertTrue(requireNotNull(result).width >= 500)
        assertTrue(result.height >= 900)
        result.recycle()
        source.recycle()
    }

    @Test
    fun returnsNoCandidateForUniformImage() {
        val source = Bitmap.createBitmap(800, 1100, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.GRAY)

        val result = DocumentCropProcessor.rectify(source)

        assertNull(result)
        source.recycle()
    }

    @Test
    fun opencvDetectsNearFullFramePagesAndLongReceiptsInBothOrientations() {
        val fixtures = buildList {
            listOf(0.90, 0.95, 0.98).forEach { occupancy ->
                add(pageFixture("page-${(occupancy * 100).toInt()}pct", 1000, 1400, occupancy))
            }
            listOf(4, 8, 12).forEach { ratio ->
                add(receiptFixture("receipt-1to${ratio}-portrait", ratio, portrait = true))
                add(receiptFixture("receipt-1to${ratio}-landscape", ratio, portrait = false))
            }
            add(skewedFixture())
            add(nestedFixture())
        }

        val measurements = fixtures.map(::measureWithOpenCv)
        measurements.forEach { measurement ->
            Log.i(
                "DocScanCropNative",
                "fixture=${measurement.name} detected=${measurement.detected} score=${measurement.score} " +
                    "iou=${measurement.iou} areaRatio=${measurement.actualAreaRatio} " +
                    "maxCornerErrorPx=${measurement.maxCornerErrorPixels} " +
                    "relativeCornerError=${measurement.relativeCornerError} " +
                    "output=${measurement.outputWidth}x${measurement.outputHeight}",
            )
        }

        val misses = measurements.filterNot { it.detected }
        assertTrue("OpenCV missed fixtures: ${misses.map { it.name }}", misses.isEmpty())
        measurements.forEach { measurement ->
            assertTrue("${measurement.name}: IoU=${measurement.iou}", measurement.iou >= 0.72)
            assertTrue("${measurement.name}: relative corner error=${measurement.relativeCornerError}", measurement.relativeCornerError <= 0.10)
            if (measurement.name.matches(Regex("page-(90|95|98)pct"))) {
                val requested = measurement.name.removePrefix("page-").removeSuffix("pct").toDouble() / 100.0
                assertTrue("${measurement.name}: generated page area=${measurement.actualAreaRatio}",
                    kotlin.math.abs(measurement.actualAreaRatio - requested) <= 0.002)
            }
        }
    }

    private data class Measurement(
        val name: String,
        val detected: Boolean,
        val score: Double,
        val iou: Double,
        val actualAreaRatio: Double,
        val maxCornerErrorPixels: Double,
        val relativeCornerError: Double,
        val outputWidth: Int,
        val outputHeight: Int,
    )

    private fun measureWithOpenCv(fixture: Fixture): Measurement {
        val source = Bitmap.createBitmap(fixture.width, fixture.height, Bitmap.Config.ARGB_8888)
        drawFixture(source, fixture.corners)
        try {
            val detected = DocumentCropProcessor.detectCorners(source)
            if (detected == null) {
                return Measurement(fixture.name, false, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, 0, 0)
            }
            val actual = detected.toNormalized()
            val expected = quad(fixture.corners, fixture.width, fixture.height)
            val iou = QuadGeometryUtils.polygonIoU(expected, actual)
            val areaRatio = QuadGeometryUtils.polygonArea(expected.asList())
            val expectedCorners = QuadGeometryUtils.canonicalizeCorners(expected.asList())
            val actualCorners = QuadGeometryUtils.canonicalizeCorners(actual.asList())
            var maxCornerErrorPixels = Double.POSITIVE_INFINITY
            var minCornerErrorPixels = Double.POSITIVE_INFINITY
            for (shift in 0 until 4) {
                val cornerErrors = (0 until 4).map { index ->
                    val a = expectedCorners[index]
                    val b = actualCorners[(index + shift) % 4]
                    hypot((a.x - b.x) * fixture.width, (a.y - b.y) * fixture.height)
                }
                val total = cornerErrors.sum()
                if (total < minCornerErrorPixels) {
                    minCornerErrorPixels = total
                    maxCornerErrorPixels = cornerErrors.maxOrNull() ?: Double.POSITIVE_INFINITY
                }
            }
            val docDiagonal = maxOf(
                hypot((expectedCorners[2].x - expectedCorners[0].x) * fixture.width,
                    (expectedCorners[2].y - expectedCorners[0].y) * fixture.height),
                hypot((expectedCorners[3].x - expectedCorners[1].x) * fixture.width,
                    (expectedCorners[3].y - expectedCorners[1].y) * fixture.height),
            )
            val rectified = DocumentCropProcessor.rectifyManual(source, actual)
            try {
                return Measurement(
                    name = fixture.name,
                    detected = true,
                    score = detected.score,
                    iou = iou,
                    actualAreaRatio = areaRatio,
                    maxCornerErrorPixels = maxCornerErrorPixels,
                    relativeCornerError = maxCornerErrorPixels / docDiagonal,
                    outputWidth = rectified.width,
                    outputHeight = rectified.height,
                )
            } finally {
                rectified.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    private fun pageFixture(name: String, width: Int, height: Int, occupancy: Double): Fixture {
        val scale = sqrt(occupancy)
        val centerX = width / 2.0
        val centerY = height / 2.0
        val halfWidth = width * scale / 2.0
        val halfHeight = height * scale / 2.0
        return Fixture(name, width, height, listOf(
            (centerX - halfWidth).toFloat() to (centerY - halfHeight).toFloat(),
            (centerX + halfWidth).toFloat() to (centerY - halfHeight).toFloat(),
            (centerX + halfWidth).toFloat() to (centerY + halfHeight).toFloat(),
            (centerX - halfWidth).toFloat() to (centerY + halfHeight).toFloat(),
        ))
    }

    private fun receiptFixture(name: String, ratio: Int, portrait: Boolean): Fixture {
        val width = if (portrait) 900 else 2000
        val height = if (portrait) 2000 else 900
        val shortSide = minOf(minOf(width, height) * 0.66f, maxOf(width, height) * 0.72f / ratio)
        val longSide = shortSide * ratio
        val pageWidth = if (portrait) shortSide else longSide
        val pageHeight = if (portrait) longSide else shortSide
        val left = (width - pageWidth) / 2f
        val top = (height - pageHeight) / 2f
        val perspective = minOf(pageWidth, pageHeight) * 0.035f
        return Fixture(name, width, height, listOf(
            left + perspective to top,
            left + pageWidth - perspective * 0.6f to top + perspective * 0.4f,
            left + pageWidth to top + pageHeight,
            left to top + pageHeight - perspective * 0.6f,
        ))
    }

    private fun skewedFixture(): Fixture {
        val width = 1200
        val height = 1200
        return Fixture("page-skewed-45deg", width, height, listOf(
            420f to 80f, 1120f to 780f, 780f to 1120f, 80f to 420f,
        ))
    }

    private fun nestedFixture(): Fixture = Fixture(
        "page-on-nested-background", 1200, 1600,
        listOf(270f to 180f, 960f to 230f, 900f to 1430f, 310f to 1380f),
    )

    private fun drawFixture(bitmap: Bitmap, corners: List<Pair<Float, Float>>) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(55, 63, 69))
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(92, 105, 111); strokeWidth = 3f }
        for (x in 0 until bitmap.width step 47) canvas.drawLine(x.toFloat(), 0f, (x + 120).toFloat(), bitmap.height.toFloat(), background)
        // Nested colored panels exercise cases where a strong background contour surrounds the true sheet.
        if (bitmap.width >= 1200 && bitmap.height >= 1500) {
            canvas.drawRoundRect(100f, 100f, bitmap.width - 100f, bitmap.height - 100f, 18f, 18f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(142, 106, 72) })
        }
        val page = Path().apply {
            moveTo(corners[0].first, corners[0].second)
            for (index in 1..3) lineTo(corners[index].first, corners[index].second)
            close()
        }
        canvas.drawPath(page, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 246, 238) })
        // Low-contrast print texture helps distinguish paper from a featureless white screen.
        val print = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 211, 202); strokeWidth = 2f }
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        for (line in -6..6) {
            val y = centerY + line * 28f
            canvas.drawLine(centerX - bitmap.width * 0.18f, y, centerX + bitmap.width * 0.18f, y, print)
        }
    }

    private fun quad(corners: List<Pair<Float, Float>>, width: Int, height: Int) = CropQuadrilateral(
        NormalizedCropPoint((corners[0].first / width).toDouble(), (corners[0].second / height).toDouble()),
        NormalizedCropPoint((corners[1].first / width).toDouble(), (corners[1].second / height).toDouble()),
        NormalizedCropPoint((corners[2].first / width).toDouble(), (corners[2].second / height).toDouble()),
        NormalizedCropPoint((corners[3].first / width).toDouble(), (corners[3].second / height).toDouble()),
    )
}
```

#### `app/src/androidTest/kotlin/app/docscan/android/DocumentCropPlanInstrumentedTest.kt`

```kotlin
package app.docscan.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import app.docscan.core.model.ImageVersionId
import app.docscan.core.model.PageId
import app.docscan.core.model.RecognitionId
import app.docscan.core.store.CaptureCommand
import app.docscan.core.store.RecognitionCandidate
import app.docscan.core.schedule.HeavyWorkScheduler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class DocumentCropPlanInstrumentedTest {
    private data class StorageFixture(val suffix: String, val storage: ProductStorage)

    @Test
    fun previewThenApplyUsesOneDetectionAndInvalidatesRecognitionForNewImageVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = mutableListOf<Pair<Int, Int>>()
        val fixture = newStorage(context, detector = { bitmap ->
            calls += bitmap.width to bitmap.height
            detection(bitmap.width, bitmap.height)
        })
        val storage = fixture.storage
        val scheduler = HeavyWorkScheduler()
        try {
            val page = capture(storage)
            storage.commitRecognition(
                RecognitionCandidate(
                    recognitionId = RecognitionId.new(),
                    workspaceId = storage.workspaceId,
                    pageId = page.pageId,
                    imageVersionId = page.imageVersionId,
                    contentRevision = page.contentRevision,
                    desiredOperationKey = "TEST_OCR_V1",
                    recognizedText = "recognized before crop",
                ),
            )
            val recognized = storage.workspaceProjection().pages.single()
            assertEquals("recognized before crop", recognized.recognizedText)
            val originalVersion = recognized.imageVersionId
            val originalRevision = recognized.contentRevision

            val preview = requireNotNull(storage.createDocumentCropPreview(recognized, scheduler))
            try {
                assertEquals("Preview must run corner detection once", 1, calls.size)
                assertTrue(preview.previewBitmap.width > 0 && preview.previewBitmap.height > 0)
                assertThrows(IllegalStateException::class.java) {
                    storage.applyDocumentCrop(preview.plan.copy(artifactRelativePath = "other-page.jpg"), scheduler)
                }
                assertEquals("A plan cannot be redirected to another artifact", recognized,
                    storage.workspaceProjection().pages.single())
                val cropped = storage.applyDocumentCrop(preview.plan, scheduler).pages.single()

                assertEquals("Apply must reuse the preview plan", 1, calls.size)
                assertEquals(recognized.pageId, cropped.pageId)
                assertNotEquals(originalVersion, cropped.imageVersionId)
                assertTrue(cropped.contentRevision > originalRevision)
                assertNull("New pixels cannot retain text from the prior image version", cropped.recognizedText)
                assertTrue(cropped.canUndoImage)
            } finally {
                preview.previewBitmap.recycle()
            }
        } finally {
            closeStorage(context, fixture)
        }
    }

    @Test
    fun stalePlanAfterEditAndDeleteIsRejectedWithoutChangingCurrentProjection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = { bitmap: Bitmap -> detection(bitmap.width, bitmap.height) }
        val fixture = newStorage(context, detector)
        val storage = fixture.storage
        val scheduler = HeavyWorkScheduler()
        try {
            val editedPage = capture(storage)
            val editPlan = requireNotNull(storage.createDocumentCropPreview(editedPage, scheduler)).also {
                it.previewBitmap.recycle()
            }.plan

            val rotated = storage.rotatePageImage(editedPage, 1, scheduler).pages.single()
            val beforeRejectedEditPlan = storage.workspaceProjection().pages.single()
            assertThrows(IllegalStateException::class.java) {
                storage.applyDocumentCrop(editPlan, scheduler)
            }
            assertEquals("A stale plan must not publish or revise the edited page", beforeRejectedEditPlan,
                storage.workspaceProjection().pages.single())

            val deletedPage = capture(storage)
            val deletePlanResult = requireNotNull(storage.createDocumentCropPreview(deletedPage, scheduler))
            val deletePlan = deletePlanResult.plan
            deletePlanResult.previewBitmap.recycle()
            val unaffectedBeforeDelete = storage.workspaceProjection().pages.single { it.pageId == rotated.pageId }
            storage.deletePage(deletedPage)
            val projectionAfterDelete = storage.workspaceProjection()
            val unaffectedAfterDelete = projectionAfterDelete.pages.single()
            assertEquals(unaffectedBeforeDelete, unaffectedAfterDelete)

            assertThrows(IllegalStateException::class.java) {
                storage.applyDocumentCrop(deletePlan, scheduler)
            }
            val projectionAfterRejectedDeletePlan = storage.workspaceProjection()
            assertNull(projectionAfterRejectedDeletePlan.pages.find { it.pageId == deletedPage.pageId })
            assertEquals("Rejecting a deleted-page plan must leave other pages untouched", unaffectedAfterDelete,
                projectionAfterRejectedDeletePlan.pages.single())
            assertEquals("No page rows remain after both checks except the unchanged page", 1,
                projectionAfterRejectedDeletePlan.pages.size)
        } finally {
            closeStorage(context, fixture)
        }
    }

    @Test
    fun previewAndApplyOutOfMemoryFailuresRecycleBitmapsAndPreservePageAndScheduler() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previewInput = arrayOfNulls<Bitmap>(1)
        val previewFixture = newStorage(
            context,
            detector = { bitmap -> detection(bitmap.width, bitmap.height) },
            rectifier = { bitmap, _ ->
                previewInput[0] = bitmap
                throw OutOfMemoryError("injected preview allocation failure")
            },
        )
        try {
            val storage = previewFixture.storage
            val scheduler = HeavyWorkScheduler()
            val page = capture(storage)
            commitRecognition(storage, page)
            val before = storage.workspaceProjection().pages.single()
            assertThrows(OutOfMemoryError::class.java) {
                storage.createDocumentCropPreview(before, scheduler)
            }
            assertTrue("Preview source is always recycled", requireNotNull(previewInput[0]).isRecycled)
            assertEquals("Preview OOM cannot mutate page state", before, storage.workspaceProjection().pages.single())
            assertTrue("Scheduler admission is released after preview OOM", scheduler.withAdmission(1L) { true })
        } finally {
            closeStorage(context, previewFixture)
        }

        var rectifierCalls = 0
        val applyInput = arrayOfNulls<Bitmap>(1)
        val applyFixture = newStorage(
            context,
            detector = { bitmap -> detection(bitmap.width, bitmap.height) },
            rectifier = { bitmap, quad ->
                rectifierCalls++
                if (rectifierCalls == 1) {
                    DocumentCropProcessor.rectifyManual(bitmap, quad)
                } else {
                    applyInput[0] = bitmap
                    throw OutOfMemoryError("injected apply allocation failure")
                }
            },
        )
        try {
            val storage = applyFixture.storage
            val scheduler = HeavyWorkScheduler()
            val page = capture(storage)
            commitRecognition(storage, page)
            val before = storage.workspaceProjection().pages.single()
            val preview = requireNotNull(storage.createDocumentCropPreview(before, scheduler))
            try {
                assertThrows(OutOfMemoryError::class.java) {
                    storage.applyDocumentCrop(preview.plan, scheduler)
                }
            } finally {
                preview.previewBitmap.recycle()
            }
            assertTrue("Apply source is always recycled", requireNotNull(applyInput[0]).isRecycled)
            assertEquals("Apply OOM cannot publish or revise the page", before, storage.workspaceProjection().pages.single())
            assertTrue("Scheduler admission is released after apply OOM", scheduler.withAdmission(1L) { true })
        } finally {
            closeStorage(context, applyFixture)
        }
    }

    private fun newStorage(
        context: Context,
        detector: (Bitmap) -> DocumentCropProcessor.Detection?,
        rectifier: (Bitmap, app.docscan.core.edit.CropQuadrilateral) -> Bitmap = DocumentCropProcessor::rectifyManual,
    ): StorageFixture {
        val suffix = UUID.randomUUID().toString()
        return StorageFixture(
            suffix,
            ProductStorage(
                context = context,
                databaseName = "document-crop-$suffix.db",
                artifactDirectoryName = "document-crop-artifacts-$suffix",
                documentCornerDetector = detector,
                documentRectifier = rectifier,
            ),
        )
    }

    private fun capture(storage: ProductStorage): app.docscan.core.store.StoredPage {
        val bitmap = Bitmap.createBitmap(640, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(48, 52, 56))
            drawRect(45f, 40f, 595f, 865f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            stream.toByteArray()
        }
        bitmap.recycle()
        val pageId = PageId.new()
        storage.commitCapture(
            CaptureCommand(
                workspaceId = storage.workspaceId,
                pageId = pageId,
                imageVersionId = ImageVersionId.new(),
                artifact = storage.stageCapturedJpeg(ByteArrayInputStream(bytes)),
                selected = true,
                makeActive = true,
                coordinateSpace = "JPEG_PIXEL_EDGE_ORIGIN_TOP_LEFT",
                processingDesiredState = "TEST_OCR_V1",
                sourceProvenance = "DOCUMENT_CROP_PLAN_TEST",
            ),
        )
        return storage.workspaceProjection().pages.single { it.pageId == pageId }
    }

    private fun commitRecognition(storage: ProductStorage, page: app.docscan.core.store.StoredPage) {
        storage.commitRecognition(
            RecognitionCandidate(
                recognitionId = RecognitionId.new(),
                workspaceId = storage.workspaceId,
                pageId = page.pageId,
                imageVersionId = page.imageVersionId,
                contentRevision = page.contentRevision,
                desiredOperationKey = "TEST_OCR_V1",
                recognizedText = "recognized before crop",
            ),
        )
    }

    private fun detection(width: Int, height: Int) = DocumentCropProcessor.Detection(
        bufferPoints = floatArrayOf(45f, 40f, 595f, 55f, 610f, 850f, 30f, 865f),
        imageWidth = width,
        imageHeight = height,
        score = 0.90,
    )

    private fun closeStorage(context: Context, fixture: StorageFixture) {
        fixture.storage.close()
        context.deleteDatabase("document-crop-${fixture.suffix}.db")
        context.noBackupFilesDir.resolve("document-crop-artifacts-${fixture.suffix}").deleteRecursively()
    }
}
```

### 16.5. Исторические измерения 0.4.273

Источник: `docs/document-mode-results/0.4.273-native-fixtures.csv`. Данные не являются новым измерением 0.4.279.

```csv
fixture,detected,score,iou,actualAreaRatio,maxCornerErrorPixels,relativeCornerError,outputWidth,outputHeight
page-90pct,true,0.9970412924377092,0.9944830204326414,0.9000000780208504,3.6129581164286004,0.002213582933523327,946,1325
page-95pct,true,0.9850128297908064,0.9945982650345251,0.9500000348666824,3.9622122651197897,0.0023628169184410205,973,1360
page-98pct,true,0.9780791227605281,0.9946843846998633,0.9799999714346255,3.0131212438546644,0.0017691217440619301,989,1383
receipt-1to4-portrait,true,0.9743581954207445,0.9901872767839811,0.278720087102557,2.1064627267655665,0.0014220245070947597,360,1434
receipt-1to4-landscape,true,0.9726763302938849,0.9901440634535904,0.28098808564277955,2.251310541895951,0.0015273846952712248,1439,355
receipt-1to8-portrait,true,0.967698878559803,0.9832989170626254,0.13966403647113457,2.3365352979266842,0.0016109165126534748,180,1436
receipt-1to8-landscape,true,0.9618930110052842,0.983200962873371,0.1409870310549497,3.171435944008145,0.0021922079237138422,1439,177
receipt-1to12-portrait,true,0.8667913432018952,0.9735702853655243,0.09317689121005568,2.6295173909117233,0.001820177796435522,121,1437
receipt-1to12-landscape,true,0.8771773363750134,0.9687040471568787,0.09410089664184662,3.298431337330749,0.00228718356904829,1439,120
page-skewed-45deg,true,0.9724983839346861,0.9891047220187438,0.3305555613504514,3.7268297203803136,0.0033863482735591002,986,477
page-on-nested-background,true,0.9523799154229717,0.994845595667931,0.4002604345170162,1.60046086440948,0.0011433613293845954,691,1201
```

### 16.6. Конфигурация Android-модуля

Источник: `app/build.gradle.kts`. Значения секретов подписи здесь не хранятся: показаны только имена Gradle properties.

```kotlin
plugins {
    id("com.android.application")
    kotlin("android")
}

val releaseStoreFile = providers.gradleProperty("DOCSCAN_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("DOCSCAN_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("DOCSCAN_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("DOCSCAN_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseRequested && releaseSigningValues.any { it.isNullOrBlank() }) {
    throw GradleException("Release signing is not configured. Provide DOCSCAN_RELEASE_* values in user-level Gradle properties.")
}

android {
    namespace = "app.docscan.android"
    compileSdk = 35

    sourceSets.getByName("androidTest").assets.srcDir("../core/src/main/sqldelight/databases")

    defaultConfig {
        applicationId = "app.docscan.scanner"
        minSdk = 29
        targetSdk = 35
        versionCode = 398
        versionName = "0.4.279"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("productRelease") {
            if (releaseStoreFile != null) storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("productRelease")
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                "DocScan-${variant.buildType.name}-${variant.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("app.cash.sqldelight:android-driver:2.3.2")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    implementation("org.opencv:opencv:4.14.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
```
