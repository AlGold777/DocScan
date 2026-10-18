# Протокол валидации режима «Документ» (DM-01 — DM-07)

Документ фиксирует матрицу требований, проверок и контрольных тестов режима «Документ» в DocScan (версия 0.4.268).

---

## 1. Матрица верификации требований ТЗ

| ID | Требование | Компонент реализации | Тестовое покрытие | Статус |
|:---|:---|:---|:---|:---:|
| **DM-01** | Преобразования систем координат камеры и фото ($< 10^{-5}$) | `CameraGeometryTransform`, `QuadGeometryUtils` | `CameraGeometryTransformTest`, `QuadGeometryUtilsTest` | ✅ Пройдено |
| **DM-02** | 2-кадровое подтверждение, EMA, TTL 300ms, сброс >15% | `DocumentLiveDetectionCoordinator`, `LiveDetectionState` | `DocumentLiveDetectionCoordinatorTest` | ✅ Пройдено |
| **DM-03** | Потоки, жизненный цикл камеры, атомарный captureSnapshot | `DocumentLiveDetectionCoordinator`, `ProductActivity` | `DocumentLiveDetectionCoordinatorTest` | ✅ Пройдено |
| **DM-04** | 64-битный Long-расчет памяти, 720px эскиз, HeavyWorkScheduler | `DocumentCropMemoryCalculator`, `ProductImageDecoder` | `DocumentCropMemoryCalculatorTest` | ✅ Пройдено |
| **DM-05** | DocumentCropPlan, единичный прогон детектора, re-OCR | `DocumentCropPlan`, `ProductStorage`, `ProductActivity` | `DocumentCropPlanTest` | ✅ Пройдено |
| **DM-06** | Сохранение режима в Bundle, TalkBack доступность, сообщения | `ProductActivity`, `strings.xml` | Инспекция Activity state & TalkBack | ✅ Пройдено |
| **DM-07** | Поддержка чеков до 1:12, скоринг PAGE/RECEIPT, fitLine checks | `DocumentCropProcessor`, `QuadGeometryUtils` | `DocumentCropLongReceiptTest` | ✅ Пройдено |

---

## 2. Спецификация тестовых наборов

### 2.1. DM-01: Геометрия координат
* **Контрольный пример**:
  * Буфер: $1280 \times 960$, `cropRect` = $[160, 0, 1120, 960]$ (центрированный 1:1 ViewPort $960 \times 960$).
  * Точка в буфере $(320, 240)$.
  * Относительно `cropRect`: $u = (320 - 160) / 960 = 1/6$, $v = 240 / 960 = 1/4$.
  * При поворотах 0°, 90°, 180°, 270° проверено точное совпадение с теоретическими значениями (погрешность $< 10^{-6}$).
  * Проверка обратимости преобразований (round-trip bijection: Buffer $\to$ ViewPort $\to$ Buffer).
* **Полигональное IoU (Sutherland-Hodgman)**:
  * Идентичные четырехугольники: $\text{IoU} = 1.0$.
  * Непересекающиеся четырехугольники: $\text{IoU} = 0.0$.
  * Сдвиг на 10%: $\text{IoU} \approx 0.818$.
* **Канонизация углов**:
  * Любые циклические сдвиги или перестановки вершин упорядочиваются строго: Top-Left $\to$ Top-Right $\to$ Bottom-Right $\to$ Bottom-Left.

### 2.2. DM-02 & DM-03: Координатор живой рамки
* **T04: Одиночный кадр и скачок**: одиночный кандидат переходит в `PROVISIONAL` (не публикуется как `CONFIRMED`). Скачок $>15\%$ площади кадра за 1 кадр переводит фильтр в сброс.
* **T05: 2-кадровое подтверждение**: при двух согласованных кадрах подряд статус переходит в `CONFIRMED`, координаты сглаживаются через EMA.
* **T06: Удержание TTL и пропуски**: при потере документа на 1-2 кадрах ($< 300$ мс) рамка переходит в `HELD` с сохранением последних подтвержденных координат и пониженной альфой. При превышении 300 мс статус переходит в `EXPIRED`, рамка скрывается.
* **T07: Инвалидация поколения**: вызов `newGeneration()` или `invalidate()` сбрасывает внутреннее состояние, и старые кадры анализа отвергаются.

### 2.3. DM-04: Память и HeavyWorkScheduler
* **64-битные вычисления**: `estimateWarpWorkingSetBytes` и `calculateBoundedPixels` оперируют с `Long`, предотвращая переполнение 32-битного знакового целого даже на экстремальных разрешениях ($> 50$ Мп).
* **Легковесная детекция на сохраненном JPEG**: вызов `decodeThumbnailForDetection(maxSide = 720)` требует $\approx 15$ МБ памяти вместо $48+$ МБ для 12 Мп RGBA Mat.
* **Неблокирующий затвор**: кнопка спуска затвора разблокируется мгновенно после сохранения оригинального JPEG (`stageCapturedJpeg`).
* **Асинхронная коррекция**: выравнивание перспективы выполняется под защитой семафора `HeavyWorkScheduler.withAdmission`.

### 2.4. DM-05: План кадрирования
* `DocumentCropPlan` фиксирует версию, ревизию и координаты.
* Отклонение устаревшего плана при модификации страницы в фоне (`initialContentRevision` не совпадает).
* Исключение повторного прогона детектора: `applyDocumentCrop` использует исключительно `plan.cropQuad`.
* Автоматический запуск повторного OCR с выбранным языком.

### 2.5. DM-06: Восстановление состояния и доступность
* Сохранение `isDocumentMode` в `outState` (`STATE_DOCUMENT_MODE`) и восстановление в `onCreate`.
* Разметка TalkBack: `isSelected = isDocumentMode`, `contentDescription = getString(R.string.document_mode_toggle_description)`, `ViewCompat.setStateDescription` («Включён» / «Выключен»).
* Локализованные статусные строки в `values/strings.xml` и `values-ru/strings.xml`.

### 2.6. DM-07: Поддержка длинных чеков
* Обработка чеков с соотношением сторон до 1:12.
* Проверка канонизации и полигонального IoU на вытянутых контурах (1:4, 1:8, 1:12).
* Проверка устойчивости `checkAgreement` при микровибрациях сенсора и отсечение при боковом сдвиге.
