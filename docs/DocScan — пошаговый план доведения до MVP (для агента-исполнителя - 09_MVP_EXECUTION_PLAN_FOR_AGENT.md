# DocScan — пошаговый план доведения до MVP (для агента-исполнителя)

**Версия:** 1.1 · 2026-09-23
**Автор плана:** Claude (ревьюер), по поручению владельца.
**Исполнитель:** агент-разработчик (далее «ты»).
**Статус:** `ACTIVE` для планирования и реализации по прямому поручению владельца от 2026-09-23. Продуктовые решения, не подтверждённые в листе владельца, остаются открытыми и блокируют только зависящие от них задачи и приёмку.
**Отношение к другим документам:** это исполнительная инструкция уровня 03–08. Она **не меняет** `01_…FROZEN`, `02_…Spike_Protocol` и не заменяет UFS. При противоречии с frozen-документом останови задачу и зарегистрируй конфликт (§0.5).

---

## Содержание

- §0. Правила работы — **читать целиком в начале каждой сессии**
- §1. Что такое MVP в этом плане
- §2. Целевая архитектура продукта
- §3. Известные дефекты текущего кода (KD)
- §4. Этапы и карточки задач (Этап 0 … Этап 10)
- §5. Проверка на телефоне без режима разработчика (DEC-Q)
- §6. Формат отчёта «Самопроверки»
- §7. Карта AC → этап
- §8. Риски и что делать
- §9. Шаблоны сообщений и документов
- §10. Шпаргалка команд

---

## §0. Правила работы

### 0.1. Кто ты и что делаешь

Ты реализуешь приложение DocScan по карточкам задач из §4, строго по одной карточке за раз, в указанном порядке зависимостей. Решений о продукте ты не принимаешь. Всё, что не записано в карточке, в UFS, в утверждённом листе решений или во FROZEN, считается **не решённым**. В этом случае не выдумывай поведение, а остановись (§0.5).

### 0.2. Источники и их сила

От сильного к слабому (по `docs/01-decision-policy.md`):

1. Отмеченные решения владельца в `docs/10_OWNER_DECISION_SHEET.md` и их копия в `execution/decisions.md`.
2. `docs/User Functional Specification v1 — FREEZE CANDIDATE.md` (UFS) + `docs/11_UFS_DECISIONS_ADDENDUM.md` (создаётся в T1.2) + `docs/Document_Scanner_Functional_Map_v1_FINAL_VERIFIED.txt` (FM).
3. `docs/01_DocScan_v1.2_FROZEN_Xiaomi14Ultra.md` (FROZEN): инварианты INV-01…25 (Шаг 2), компоненты (Шаг 4), модель данных (Шаг 7), транзакции (Шаг 9), восстановление (Шаг 10), стек (Шаг 12).
4. Этот план.
5. Существующий код. Он **не является** образцом: большая часть текущего продуктового кода нарушает инварианты (§3).

Где что лежит:

| Что | Путь |
|---|---|
| Ledger задач продукта (создаётся в T1.3) | `execution/product_tasks.json` |
| Решения и конфликты | `execution/decisions.md` |
| Журнал передачи между сессиями | `execution/handoff.md` (новые записи **сверху** раздела «Product MVP») |
| Короткие доказательства (в git) | `execution/evidence/<TASK-ID>.md` |
| Тяжёлые артефакты (не в git) | `results/PRODUCT/<TASK-ID>/…` (каталог `results/` уже в `.gitignore`) |
| Чек-листы для телефона | `execution/device_tests/<версия>/CHECKLIST.md` |
| Пакеты ревью этапов | `execution/reviews/<этап>-packet.md` |
| Трассировка AC | `execution/traceability_product.csv` |

### 0.3. Жёсткие запреты

1. Не редактировать `docs/01_…FROZEN…md`, `docs/02_…Spike_Protocol…md`, UFS-кандидат и FM. Хэши frozen-файлов проверяет `python3 host/runner.py verify-gates`.
2. Не принимать продуктовые решения: пороги, тексты поведения, лимиты, порядок. Бери их только из листа решений или UFS.
3. Не ослаблять и не удалять существующие тесты, чтобы «позеленело». Не менять ожидаемые значения теста под фактический результат без записи в `decisions.md` и одобрения ревьюера.
4. Не писать «PASS», «готово», «исправлено» без команды и её вывода в `execution/evidence/<TASK-ID>.md`.
5. Не выдумывать значения: хэши, модели устройств, метрики. Если значения нет — `null` + причина.
6. Не глотать исключения (`catch (e: Exception) {}`) в коде, влияющем на данные пользователя. Ошибка должна дойти до состояния, видимого пользователю или тесту.
7. Не выполнять I/O провайдера (SAF/ContentResolver) внутри открытой транзакции БД (INV-20).
8. Не удалять файлы пользователя и артефакты в обход `CleanupArbiter` (FROZEN Шаг 4, правило 2).
9. Не добавлять зависимости, не перечисленные в §2.9 или в карточке, без записи `IMPL-DEC` в `decisions.md`.
10. Не коммитить чужие изменения вместе со своими. Перед каждым коммитом — `git status --short` и `git diff --stat`.
11. Не коммитить ключи, пароли, `local.properties`, keystore.
12. Не переходить к следующему этапу, если ревьюер вернул дефекты по предыдущему: сначала исправления.

### 0.4. Цикл одной задачи (выполнять каждый раз)

1. `git status --short`. Если есть чужие незакоммиченные изменения — не трогай их; если они мешают, остановись (§0.5).
2. Прочитай карточку целиком и только файлы и разделы из её поля «Читать».
3. Запиши мини-контракт в `execution/handoff.md`: «Задача X принята, если приложено Y, подтверждающее Z для коммита/версии W». Условие берётся из поля «DONE, если» и не меняется по ходу работы.
4. Реализуй **только** то, что указано в «Сделать». Если по ходу нужен шаг, которого нет в карточке, — остановись (§0.5).
5. Собери: `tools/check_all.sh` (появится в T0.5; до этого — команды из карточки).
6. Запусти тесты из «Проверка». Упавший тест чини кодом, а не тестом.
7. Сохрани доказательства: `execution/evidence/<TASK-ID>.md` по шаблону §9.4.
8. Обнови `execution/product_tasks.json` (статус, коммит, путь к evidence) и `execution/traceability_product.csv` (закрытые AC → тест → результат).
9. Коммит только своих файлов. Сообщение: `<TASK-ID>: <что сделано>`.
10. Отчёт в чат по шаблону §0.8.

### 0.5. Когда остановиться и спросить (STOP)

Остановись, поставь задаче `BLOCKED`, опиши причину в `handoff.md` и в отчёте, если:

- нужно решение, которого нет в листе решений или UFS;
- требование карточки противоречит UFS/FROZEN/листу решений;
- тест падает после 3 честных попыток исправления (опиши гипотезы и что пробовал);
- нужно изменить файл вне списка карточки больше чем на 20 строк;
- нужна новая библиотека или разрешение Android;
- окружение не позволяет выполнить проверку: нет adb или эмулятора, `.git` только для чтения, Gradle не стартует. Статус `BLOCKED-ENV`, **не** `DONE`;
- изменение превышает ~400 строк: предложи разбиение на подзадачи и дождись подтверждения;
- ты не уверен в поведении Android API. Найди официальную документацию (developer.android.com, страница библиотеки) и процитируй в evidence; если найти не удалось — остановись.

Независимые задачи из других веток зависимостей можно продолжать, пока одна задача заблокирована.

### 0.6. Статусы

- Задачи: `TODO` → `IN_PROGRESS` → `DONE` | `BLOCKED` | `BLOCKED-ENV` | `REVIEW_REQUIRED`.
- Этапы: `TODO` → `IN_PROGRESS` → `REVIEW_REQUIRED` → `DONE` (только с вердиктом ревьюера по DEC-REVIEW) | `REWORK`.
- Результаты проверок: `PASS` | `FAIL` | `INCOMPLETE` (не хватает данных) | `NOT RUN` (не запускалось, с причиной).

`DONE` задачи разрешён только если есть: коммит, существующий файл evidence, выполненные команды из «Проверка» с кодом выхода и закрытые AC в трассировке.

### 0.7. Доказательства

- В `execution/evidence/<TASK-ID>.md`: коммит, окружение (ОС, JDK, эмулятор или устройство), команды, код выхода, краткий вывод (последние 20–40 строк или сводка тестов), пути и SHA-256 тяжёлых артефактов.
- Тяжёлые артефакты (APK, PDF, логи, отчёты самопроверки) — в `results/PRODUCT/<TASK-ID>/`, в evidence только путь и SHA-256.
- Вывод «0 ошибок» не является доказательством, если команда не запускала нужные тесты: указывай число выполненных тестов.

### 0.8. Отчёт после задачи (в чат)

```text
TASK: <ID> — <название>
STATUS: DONE | BLOCKED | BLOCKED-ENV | REVIEW_REQUIRED
COMMIT: <hash>
CHANGED: <пути>
CHECKS: <команда → итог, число тестов>
AC_CLOSED: <AC-… или NONE>
EVIDENCE: execution/evidence/<ID>.md
NOT_VERIFIED: <что не проверено и почему, или NONE>
BLOCKERS: <ID или NONE>
NEXT: <следующая задача>
```

### 0.9. Версии и сборки для телефона

- Продуктовая сборка (flavor `product`): `versionCode` начинается со 100 и увеличивается на 1 для **каждого** APK, переданного владельцу. `versionName` = `0.<номер этапа>.<n>`; релиз — `1.0.0`.
- Сборка harness (flavor `harness`) сохраняет свою нумерацию (сейчас 6 / 0.1.5).
- Каждый APK для владельца: `dist/DocScan-<versionName>.apk` + `dist/DocScan-<versionName>.apk.sha256` + чек-лист `execution/device_tests/<versionName>/CHECKLIST.md` (§5). `dist/` добавить в `.gitignore`.

### 0.10. Размер изменений

Одна задача — один логический шаг, обычно до 400 изменённых строк без учёта тестов. Больше — разбей (§0.5).

### 0.11. Ревью этапа

В конце каждого этапа:

1. Собери `execution/reviews/<этап>-packet.md` по шаблону §9.3.
2. Поставь этапу `REVIEW_REQUIRED`.
3. Можешь начать задачи следующего этапа, но этап не становится `DONE` без вердикта. Если ревьюер нашёл дефекты — сначала исправь их (запрет 12).

---

## §1. Что такое MVP в этом плане

**MVP** — устанавливаемый подписанный APK DocScan 1.0.0, в котором:

1. реализованы **все 16 capability CAP-001…CAP-016** (DEC-SCOPE) с решениями DR-01…DR-26 из листа владельца;
2. каждый AC из UFS и addendum имеет тест или ручную проверку, результат которой записан для релизной сборки;
3. соблюдены инварианты FROZEN INV-01…INV-25 в объёме, покрытом тестами и самопроверкой;
4. проверка на Xiaomi 14 Ultra выполнена по DEC-Q: ручной чек-лист + отчёт «Самопроверки» для релизной сборки;
5. переданы: APK + SHA-256, инструкция по сборке, руководство пользователя (RU), список известных ограничений, пакет ревью с вердиктом.

Не входит: автоматическая квалификация по протоколу v1.1 на физическом устройстве (заменена DEC-Q), hard-power, публикация в Google Play, iOS.

### Дорожная карта

```text
Этап 0  Подготовка репозитория и окружения ─┐
Этап 1  Факты (FACT) + перенос решений ─────┴─► Gate A (ревью архитектуры и решений)
                                                    │
Этап 2  P0 Фундамент (core, БД, транзакции, восстановление, самопроверка)  → APK 0.2.x
Этап 3  P1 Камера, workspace, выбор, порядок, просмотрщик                  → APK 0.3.x
Этап 4  P2 Обработка изображения, качество, OCR, Retake, редактор          → APK 0.4.x
Этап 5  P3 Импорт изображений и PDF                                        → APK 0.5.x
Этап 6  P4 Сохранение PDF+JPG, имена, папка, экспорт                       → APK 0.6.x
Этап 7  P5 Удаление, Clear, Undo, хранение 24 ч                            → APK 0.7.x
Этап 8  P6 Недавние, редактор сохранённого PDF, «Попробовать ещё раз»      → APK 0.8.x
Этап 9  Крэш-матрица продукта, регресс, самопроверка полная                → APK 0.9.x (RC)
Этап 10 P7 Релизная приёмка                                                → APK 1.0.0
```

Этапы 3–8 строго последовательны: каждый опирается на предыдущий. Внутри этапа порядок задач — по полю «Зависит от».

---

## §2. Целевая архитектура продукта

Архитектура следует FROZEN (Шаги 2–12) и не переносит учебную схему S1 в продукт (документ 08 §6).

### 2.1. Модули и варианты сборки

```text
settings.gradle.kts: include(":app", ":core")

:core   — Kotlin/JVM, без Android. Доменная модель, схема БД (SQLDelight),
          StateStore и все транзакции, политики, проекция, планировщик,
          очистка, восстановление, сценарии самопроверки.
          Тестируется быстрыми JVM-тестами (JdbcSqliteDriver).

:app    — Android. Два productFlavors (dimension "variant"):
          harness — ВЕСЬ текущий Java-код (S1–S6, MainActivity 0.1.5,
                    TestHooks, PipelineService, MockDocumentsProvider).
                    applicationId com.example.docscanspike, как сейчас.
                    Нужен только для исторического харнеса и host runner.
          product — новый продукт на Kotlin. applicationId по DEC-APPID.
                    Адаптеры Android + UI + обвязка :core.
```

Исходники:

```text
app/src/main/                 общий минимум (разрешение CAMERA в манифесте, тема)
app/src/harness/java/...      перенесённый текущий код (git mv, без правок логики)
app/src/harness/res/...       его ресурсы
app/src/harness/AndroidManifest.xml
app/src/androidTestHarness/   перенесённые инструментальные тесты харнеса
app/src/product/kotlin/app/docscan/android/...   новый код
app/src/product/res/...       ресурсы продукта (values/ = en, values-ru/ = ru)
app/src/product/AndroidManifest.xml
app/src/androidTestProduct/kotlin/...            инструментальные тесты продукта
core/src/main/kotlin/app/docscan/core/...
core/src/main/sqldelight/app/docscan/core/db/*.sq
core/src/test/kotlin/app/docscan/core/...
```

`namespace` модуля `:app` остаётся `com.example.docscanspike`, чтобы не править харнес. Продуктовый код импортирует `com.example.docscanspike.R`.

### 2.2. Пакеты `:core`

| Пакет | Содержимое |
|---|---|
| `app.docscan.core.model` | ID-типы (value class над UUID-строкой), перечисления, доменные записи |
| `app.docscan.core.db` | сгенерированный SQLDelight-код (`DocScanDatabase`) |
| `app.docscan.core.store` | `StateStore`, классы приоритета записи, все `tx*`-методы |
| `app.docscan.core.artifact` | интерфейс `ArtifactRepository`, JVM-реализация для тестов |
| `app.docscan.core.time` | интерфейс `Clock` (wall + monotonic + epoch token), `FakeClock` |
| `app.docscan.core.schedule` | `HeavyWorkScheduler` (1 тяжёлая задача, 96 МиБ токенов) |
| `app.docscan.core.processing` | контракт кандидата обработки и качества, desired state |
| `app.docscan.core.ocr` | контракт кандидата OCR (`OcrEngine` — интерфейс) |
| `app.docscan.core.importing` | `ImportCoordinator` |
| `app.docscan.core.save` | `SaveComposer` (freeze, зависимости, проверка готовности), интерфейс `PdfWriter` |
| `app.docscan.core.export` | `ExportGateway`, интерфейс `ProviderAdapter`, `ExportAttempt` FSM |
| `app.docscan.core.cleanup` | `CleanupArbiter`, retention |
| `app.docscan.core.recovery` | `RecoveryCoordinator` (startup barrier), version gate |
| `app.docscan.core.projection` | `ProjectionBuilder` (read model для UI) |
| `app.docscan.core.policy` | `ApplicationPolicy` (какие команды доступны) |
| `app.docscan.core.naming` | автоимя (DR-15), разрешение конфликтов имён |
| `app.docscan.core.recent` | индекс недавних (CAP-013) |
| `app.docscan.core.selftest` | сценарии самопроверки: общий код для JVM-тестов и приложения |

### 2.3. Компоненты FROZEN → классы

| FROZEN компонент | Класс | Где |
|---|---|---|
| StateStore | `store.StateStore` | :core |
| ArtifactRepository | `artifact.ArtifactRepository` + `android.storage.AndroidArtifactRepository` | :core + :app |
| CaptureService | `android.capture.CaptureService` | :app |
| ImportCoordinator | `importing.ImportCoordinator` + `android.importing.SafImportSource` | :core + :app |
| ProcessingExecutor | `processing.ProcessingRunner` + `android.image.OpenCvImagePipeline` | :core + :app |
| OCRService | `ocr.OcrRunner` + `android.ocr.<Engine>OcrEngine` | :core + :app |
| SaveComposer | `save.SaveComposer` + `android.pdf.<Engine>PdfWriter` | :core + :app |
| ExportGateway | `export.ExportGateway` + `android.saf.SafProviderAdapter` | :core + :app |
| CleanupArbiter | `cleanup.CleanupArbiter` | :core |
| ProjectionBuilder | `projection.ProjectionBuilder` | :core |
| ApplicationPolicy | `policy.ApplicationPolicy` | :core |
| RecoveryCoordinator | `recovery.RecoveryCoordinator` | :core |
| HeavyWorkScheduler | `schedule.HeavyWorkScheduler` | :core |

Правила зависимостей (FROZEN Шаг 4): авторитетные изменения — только через `StateStore`; физическое удаление — только через `CleanupArbiter`; тяжёлая работа — только через `HeavyWorkScheduler`; UI и `ProjectionBuilder` не имеют методов записи.

### 2.4. Схема БД v1

Основа — FROZEN Шаг 7. Поля бери **из FROZEN §7.x** и дополняй только перечисленным здесь. Имя файла БД продукта — `docscan.db` (не `docscan_s1.db`). PRAGMA: `journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`.

| Таблица | Источник | Дополнения продукта |
|---|---|---|
| `Workspace` | §7.1 | — |
| `WorkspaceSession` | §7.2 | `created_wall`, `last_user_activity_wall`, `last_user_activity_monotonic`, `monotonic_epoch_token` (DR-23/25) |
| `Page` | §7.3 | `workspace_session_id`, `source_kind` (CAMERA/IMAGE/PDF/SAVED_PDF), `provenance` (JSON), `tombstone_at_wall`, `saved_manifest_id` (страница ушла в сохранение) |
| `ImageVersion` | §7.4 | `width_px`, `height_px` |
| `Recognition` | §7.5 | `engine`, `language_hint`, `text` (TEXT), `words_json` (слова + рамки для текстового слоя), `source` (OCR / PDF_TEXT_LAYER) |
| `ProcessingResult` | §7.6 | `result_kind` ∈ {PREPARED_IMAGE, QUALITY, FAILED}, `payload` JSON метрик |
| `OcrFailure` | новое | `page_id`, `image_version_id`, `attempt_no`, `error`; нужно для DR-08 (счётчик попыток — это не durable attempt, а зафиксированный результат неудачи) |
| `SaveManifest` | §7.7 | — |
| `SaveDependencyResult` | §7.8 | — |
| `ExportArtifact` | §7.9 | — |
| `ExportAttempt` | §7.10 | `planned_display_name`, `observed_display_name`, `document_uri` |
| `ImportSession` | §7.11 | `source_uri`, `source_display_name` |
| `ImportSourceItem` | новое | один выбранный файл внутри ImportSession: `item_index`, `mime`, `page_count?` |
| `ArtifactRecord` / `ArtifactReference` | §7.13 | — |
| `MaintenanceCursor` | §7.14 | — |
| `RecentDocument` | новое (CAP-013) | `recent_id`, `pdf_uri`, `display_name`, `page_count`, `saved_wall`, `ocr_status` (FULL/PARTIAL/NONE), `thumbnail_artifact_id?` |
| `DestinationGrant` | новое (CAP-010) | `tree_uri`, `granted_wall`, `last_verified_wall` |
| `SchemaInfo` | §6.8 | `schema_version` |

### 2.5. Транзакции → методы `StateStore`

Каждый метод реализует одноимённый раздел FROZEN Шаг 9. **Перед реализацией метода прочитай его раздел целиком.**

| FROZEN | Метод | Этап |
|---|---|---|
| TX-CAPTURE | `txCapture` | 2 |
| TX-RETAKE | `txRetake` | 2 |
| TX-EDIT | `txEdit` | 2 |
| TX-UNDO | `txUndo` | 2 |
| TX-DELETE | `txDelete` (+ `txUndoDelete`) | 2 |
| TX-REORDER | `txReorder` | 2 |
| TX-SELECTION-ACTIVE | `txSetSelected`, `txSetActive`, `txSelectAll` | 2 |
| TX-WORKSPACE-SESSION-CLOSE | `txCloseSession`, `txClear` | 2 |
| TX-IMPORT-BEGIN / MATERIALIZE-PAGE / END | `txImportBegin`, `txImportMaterializePage`, `txImportEnd` | 5 |
| TX-OCR-COMMIT / TX-PROCESSING-COMMIT | `txCommitRecognition`, `txCommitProcessing` | 2 |
| TX-SAVE-FREEZE / DEPENDENCY-COMMIT / OUTPUT-COMMIT | `txSaveFreeze`, `txSaveDependencyCommit`, `txSaveOutputCommit` | 6 |
| TX-EXPORT-BEGIN / END / AMBIGUOUS / REVALIDATE | `txExportBegin`, `txExportEnd`, `txExportAmbiguous`, `txExportRevalidate` | 6 |
| TX-GC-CLAIM / TX-GC-FINALIZE | `txGcClaim`, `txGcFinalize` | 2 |
| TX-METADATA-PURGE-BATCH / TX-MAINTENANCE-CURSOR | `txPurgeBatch`, `txAdvanceCursor` | 7 |
| TX-SCHEMA-MIGRATE | `migrate()` в `RecoveryCoordinator` | 2 |

### 2.6. Видимые состояния страницы (выводятся, а не хранятся)

`ProjectionBuilder` вычисляет статус из авторитетных данных для **текущей** `ImageVersion`:

| Статус UI | Условие |
|---|---|
| Saving | страница входит в активный `SaveManifest`, у которого ещё нет `OutputCommitted` и нет итога экспорта |
| Processing | нет `ProcessingResult(QUALITY)` и нет `FAILED` для текущей версии |
| Failed | есть `ProcessingResult(FAILED)` для текущей версии **или** OCR окончательно не удался (DR-08) |
| Low quality | есть `QUALITY` с вердиктом LOW |
| Ready | есть `QUALITY` с вердиктом OK |

Отдельный атрибут «текст»: `OCR_VALID` (есть `Recognition` текущей версии) / `OCR_PENDING` / `OCR_NONE_AFTER_EDIT` (после графического Save до Re-OCR) / `OCR_FAILED_FINAL`. Статус **никогда** не передаётся только цветом: иконка + подпись.

### 2.7. Основные потоки

**Съёмка (FROZEN §6.1, INV-04, CONFLICT-02).** Затвор → транзиентная карточка «Сохраняется…» в памяти UI → JPEG во временный файл → `ArtifactRepository.publishDurable` (fsync файла, атомарный rename, fsync каталога) → `ArtifactRecord(STAGED_DURABLE)` → `txCapture` (Page + ImageVersion + ArtifactRecord → COMMITTED + ArtifactReference, в конец порядка по DR-01, в текущую сессию по DR-25) → карточка становится страницей → в очередь обработки. Затвор никогда не ждёт обработки (INV-08).

**Обработка и OCR (FROZEN §6.6, INV-02, INV-07).** Для страницы с неудовлетворённым desired state `ProcessingRunner` берёт разрешение `HeavyWorkScheduler`, строит кандидата (подготовленное изображение, качество) и коммитит через `txCommitProcessing` **только если** в той же транзакции подтверждены: страница существует, не tombstone, `content_revision` и `current_image_version_id` те же, что при старте. Иначе кандидат выбрасывается. OCR — аналогично через `txCommitRecognition`. Попытки не хранятся; после перезапуска всё неудовлетворённое ставится в очередь заново.

**Сохранение (FROZEN §6.3–6.4, INV-10…12, CONFLICT-03).** `txSaveFreeze` фиксирует выбранные страницы, их версии и порядок в неизменяемый `SaveManifest` → ожидание зависимостей → сборка PDF и JPG в приватной папке → проверка обратным чтением → `txSaveOutputCommit` (`ExportArtifact`) → для каждого файла свой `ExportAttempt` (`txExportBegin` → запись вне транзакции → `txExportEnd` / `txExportAmbiguous`) → если все `Succeeded` — страницы уходят из workspace, запись в `RecentDocument`.

**Удаление и очистка (INV-13…15, INV-25).** Delete/Clear = tombstone + Undo. Физически файлы удаляет только `CleanupArbiter`: после 24 ч бездействия (DR-23) с двойным доказательством времени, не чаще раза в 6 ч, только незащищённые артефакты, по протоколу claim-token.

**Старт (FROZEN §10.6).** Открыть БД → проверка версии и миграция → карантин неподдерживаемого → `WritingExternal` → `UnknownOutcome` → довершить `GC_CLAIMED` → показать UI. Затем по объекту: доимпорт, повторная постановка OCR/обработки, активные SaveManifest, пакеты очистки по курсору. Полного сканирования нет.

### 2.8. Экраны

| Экран | Источник | Этап |
|---|---|---|
| Главный: лента миниатюр (чёрный фон, разделитель старых сессий), Documents (карточки со статусом, превью, чекбоксом, действиями; заголовок «+», «Выбрать все», «Сохранить», «Очистить»), снизу кнопка камеры и слева превью последнего PDF | FM §2, §25 | 3, 7, 8 |
| Камера (полноэкранный видоискатель, затвор, лента только что снятых) | DR-24 | 3 |
| Универсальный просмотрщик, режим workspace/graphical (лента, Delete / Re-OCR / Save, Undo, страница, инструменты) | FM §17, §18, §26 | 3, 4 |
| Просмотрщик/редактор сохранённого PDF (лента с перетаскиванием, Delete / «Попробовать ещё раз» / Save, ←/→ страницы, ↑/↓ документы) | FM §19, §20, §25 | 8 |
| «О приложении» (версия; 7 нажатий по версии → «Самопроверка») | DEC-Q | 2 |

UI: Android Views + RecyclerView + `ItemTouchHelper` (drag & drop, swipe) + `ViewModel` + `StateFlow`. Compose не используется, чтобы не добавлять библиотеку перетаскивания. Внедрение зависимостей — вручную (`AppContainer`), без Hilt.

### 2.9. Библиотеки (единственный разрешённый список)

| Назначение | Библиотека | Основание |
|---|---|---|
| Язык | Kotlin 2.x (стабильная, совместимая с AGP 8.13.2) | ND-12A |
| Корутины | kotlinx-coroutines-core / -android | ND-12C |
| БД | SQLDelight 2.x: плагин, `android-driver`, `sqlite-driver` (JVM-тесты), `coroutines-extensions` | ND-12B |
| Камера | CameraX (уже в проекте; можно обновить до актуальной стабильной) | ND-12F |
| Обработка изображений | OpenCV для Android (Maven-артефакт по итогам FACT-03) | ND-12D |
| OCR | ML Kit или Tesseract4Android по DEC-OCR-ENGINE / FACT-01 | ND-12E |
| PDF | Android `PdfDocument`/`PdfRenderer` или PdfBox-Android по DEC-PDF-ENGINE / FACT-02 | ND-12H |
| UI | AndroidX AppCompat, Material, RecyclerView, ConstraintLayout, Lifecycle (ViewModel, runtime), ViewPager2, ExifInterface | — |
| Тесты JVM | JUnit 4 или 5, kotlinx-coroutines-test | — |
| Тесты Android | AndroidX Test (runner, rules, ext-junit), Espresso, UiAutomator | — |

Точные версии фиксируются в `decisions.md` (IMPL-01) после проверки, что версия существует в Maven Central / Google Maven. Не бери версию «по памяти» без проверки сборкой.

### 2.10. Как тестируем

| Уровень | Что | Команда | Где работает |
|---|---|---|---|
| L1 | Логика :core, все транзакции и политики, большинство AC | `./gradlew :core:test` | любой компьютер, без эмулятора |
| L2 | Адаптеры Android: файлы, SQLite, камера, OCR, PDF, SAF (через тестовый DocumentsProvider в androidTest) | `./gradlew :app:connectedProductDebugAndroidTest` | эмулятор |
| L3 | Самопроверка в приложении (песочница, крэш процесса, восстановление) | вручную в приложении или `python3 host/runner.py selftest` (T9.3) | эмулятор и телефон |
| L4 | Ручной чек-лист по AC | §5 | телефон владельца |
| Host | Валидаторы отчётов и PDF | `python3 -m unittest discover tests` | компьютер |

Правило: каждый AC закрывается тестом самого низкого возможного уровня. Ручная проверка (L4) — только для того, что нельзя автоматизировать: тактильный отклик, реальная камера, настоящий системный выбор папки, визуальное качество.

---

## §3. Известные дефекты текущего кода (KD)

Найдены ревьюером при чтении кода версии 0.1.5. В задаче T0.4 ты подтверждаешь каждый (файл:строка) или помечаешь `NOT_CONFIRMED` с объяснением. Новый продукт пишется заново, поэтому эти дефекты не «чинятся», а **не должны повториться**: каждый KD превращается в негативный тест соответствующего этапа.

| ID | Дефект | Где | Нарушает | Не повторить в |
|---|---|---|---|---|
| KD-01 | Страница PDF рисуется в битмап 595×842 px (≈72 dpi), текст на снимке плохо читается | `PdfComposer.composeStreaming`, `DocumentRepository.exportToPdf` | FM §9, INPUT-04 | T6.4 |
| KD-02 | На каждой странице пользовательского PDF печатается служебный текст «DocScan Marker Page … RUN …» | `PdfComposer` | FM §9 | T6.4 |
| KD-03 | Изображение растягивается на весь A4 без сохранения пропорций; при повороте 90/270 искажается | `DocumentRepository.exportToPdf.renderPage` | BR-003-02 | T6.4 |
| KD-04 | Save экспортирует все страницы, игнорируя выбор; выбор хранится только в памяти (долгое нажатие) | `MainActivity`, `PageItem.isSelected` | BR-006-09, BR-009-01 | T3.5, T6.3 |
| KD-05 | Retake перезаписывает тот же JPEG на месте: нет новой версии, нет durable-публикации, нет fencing | `DocumentRepository.retakePage` | INV-03, INV-05 | T2.7, T4.8 |
| KD-06 | OCR старого изображения может записаться после Retake (нет проверки версии) | `DocumentRepository.scheduleOcrWithRetry` | INV-02 | T2.9, T4.9 |
| KD-07 | ID страниц из `System.currentTimeMillis()`; при быстрой съёмке возможна коллизия, а `CONFLICT_REPLACE` тихо перезапишет запись | `addPageFromBitmapOnDiskIoThread` | INV-01 | T2.1 |
| KD-08 | При ошибке хэширования возвращается 64 нуля (выдуманная контрольная сумма) | `DocumentRepository.calculateSha256` | запрет 5 | T2.5 |
| KD-09 | Удаление сразу стирает файл | `DocumentRepository.deletePage` | INV-13, CONFLICT-05 | T2.8, T7.1 |
| KD-10 | FileProvider открывает весь `filesDir` (`files-path path="."`) | `res/xml/file_paths.xml` | приватность | T6.1 |
| KD-11 | В пользовательском APK есть `MockDocumentsProvider` с `exported=true`, `PipelineService` и `TestHooks.killProcess` | `AndroidManifest.xml` | безопасность | T0.6 |
| KD-12 | При «Больше не спрашивать» для камеры нет перехода в настройки (только Toast) | `MainActivity.cameraPermissionLauncher` | AC-001-04 | T3.3 |
| KD-13 | Строки захардкожены на английском, нет contentDescription у части кнопок | `activity_main.xml`, `MainActivity` | FM §22, DEC-LANG-UI | T3.2 |
| KD-14 | Запись в файл и в БД при съёмке без fsync и без temp+rename | `addPageFromBitmapOnDiskIoThread` | INV-03, INV-04 | T2.5, T3.4 |
| KD-15 | Страницы без файла молча пропускаются при загрузке | `loadPagesFromDatabase` | INV-16 (нет карантина) | T2.13 |
| KD-16 | Поля `PageItem` меняются из нескольких потоков без синхронизации | `DocumentRepository` | INV-17 | T2.11 (UI получает неизменяемую проекцию) |

<!-- PART-2 -->

## §4. Оценка предложений и исходного кода

### 4.1. Контракт этого цикла и приоритет источников

Владелец поручил довести продукт до MVP. Это разрешает подготовку продукта и реализацию его этапов. Оно не подтверждает автоматически каждую рекомендацию в листе решений и не меняет frozen-инварианты. В частности, нельзя объявлять физический Gate Q пройденным по эмулятору, менять требования к данным или подставлять отсутствующие продуктовые числа.

Источники применяются в порядке из `docs/01-decision-policy.md`: решение владельца → утверждённая спецификация → подтверждённое ограничение платформы → строгое следствие → архитектурное решение → деталь реализации. Конфликты и их authority ведутся только в `execution/decisions.md`. Полная функциональная приёмка берётся из всех BR/AC UFS, а не из кратких примеров `docs/08_MVP_GATES_AND_ACCEPTANCE.md`.

Граница этапа: пользовательское Android-приложение CAP-001…CAP-016, установочный продуктовый APK и evidence его проверок. S1–S6 harness — инфраструктура разработки; результат harness не считается результатом продукта. Frozen-файлы `01_…FROZEN…` и `02_…FROZEN…` не менять.

### 4.2. Проверка предложенной архитектуры и рекомендаций

| Предложение | Оценка по источникам и коду | Плановое решение / статус |
|---|---|---|
| Новое ядро `:core`, SQLite + SQLDelight, `StateStore` и транзакционные команды | Совпадает с FROZEN v1.2 (ND-12B/12L) и INV-01…25. Текущий `S1Database` — harness schema; прямой перенос не доказывает совместимость с FROZEN §7/§9. | Использовать как продуктовую архитектуру; проверить схему и каждую транзакцию по FROZEN. Записать ADR до первой реализации БД. |
| Продуктовый код отдельным flavor внутри существующего `:app` | Сохраняет один Gradle-модуль, но требует перемещать нынешние `src/main`, manifests, androidTest и ресурсы; рабочая копия уже содержит несохранённые изменения 0.1.5. | Предпочесть отдельный Android-модуль `:product` и отдельный `:core`, оставив `:app` harness на месте. Это локальное архитектурное решение, не меняющее наблюдаемое поведение. Проверить host runner и сборки harness после подключения модулей. |
| Kotlin, CameraX, OpenCV, ML Kit, Android PDF APIs/PdfBox | SQLite/SQLDelight, CameraX, OpenCV и ML Kit перечислены в FROZEN §12. PDF API оставлен там открытым для выбора по факту. Установленные версии должны пройти сборку и продуктовые тесты; список библиотеки сам по себе не подтверждает качество. | Сохранить frozen-компоненты. PDF writer выбрать после FACT-02 с round-trip извлечением текста, проверкой растров и реального размера/памяти на согласованном профиле. |
| UI на Android Views/RecyclerView, ручное внедрение зависимостей | Текущий harness уже использует Views/RecyclerView; продуктовые BR не требуют Compose. | Принять как низкорисковую реализационную деталь. Не считать UI harness контрактом или повторно использовать его Activity. |
| Все 16 CAP входят в MVP | Лист решений описывает это как уже принятое в чате, но checkbox пуст; текущий прямой запрос владельца требует именно «продукт уровня MVP», не ограниченную демонстрацию. | В план включены все CAP-001…016. Не откладывать CAP без отдельного решения владельца. Зафиксировать scope в `execution/decisions.md` со ссылкой на текущий запрос. |
| DEC-Q: APK install + скрытая самопроверка + ручной checklist вместо квалификационного Gate Q | Это заметно меняет критерий из frozen protocol и документов 04–06. Текст решения в листе есть, но последствия не подтверждены checkbox. | Нерешено до явного подтверждения владельца. Разработку и DEV-проверки продолжать; physical qualification не подменять и финальный статус не ослаблять. |
| DEC-00: одним коммитом сохранить рабочее состояние 0.1.5 | В плане ошибочно не учтён новый пользовательский `photo_2026-09-23_17-09-05.jpg`; рабочее дерево содержит 14 изменённых tracked-файлов, `app/src/main/res/xml/` и этот отдельный файл. Корневой `AGENTS.md` запрещает смешивать существующие пользовательские изменения. | Не выполнять `git add -A` и не включать эти изменения в коммит продукта. Считать текущий HEAD (`ad9983b`) исходной Git-точкой; при коммитах выбирать только собственные пути. Исходные изменения оставить нетронутыми. |
| DEC-APPID/DEC-SIGN/DEC-LEGACY | Application ID, подпись и миграция данных — решения с последствиями для установки и доступности пользовательских файлов. Ни одна рекомендация не отмечена. | Не менять пакет/ключ и не удалять/мигрировать данные до решения владельца. Для раннего DEV APK использовать отдельную debug-конфигурацию без обещания upgrade-совместимости. Release P7 заблокирован DEC-APPID/SIGN/LEGACY до решения. |
| DEC-LANG-UI / Android 10+, target 35, ABI arm64 | FROZEN фиксирует Android 10+ и ARM64 как design scope, но отдельно запрещает обобщать эмпирическую квалификацию на другие устройства. Языки UI — предпочтение владельца; target SDK/ABI требуют совместимости и сборки. | Min SDK/ABI сверить с FROZEN. Не заявлять поддержку непроверенных устройств. Русский/английский UI и целевой API включить в лист открытых решений/фактов, пока не утверждены или не подтверждены источником. |
| OCR: русский + английский на ML Kit; при отсутствии кириллицы перейти на Tesseract | Официальная таблица ML Kit Text Recognition v2 указывает Latin, Chinese, Devanagari, Japanese, Korean; русского языка в её списке нет. Это опровергает обещание русского OCR на базе только текущего ML Kit, но не измеряет качество альтернативного движка. | Русский OCR не объявлять работающим на ML Kit. Оставить OcrEngine-интерфейс; выполнить FACT-01 на Russian/English corpus, offline и на целевом Android. Tesseract — кандидат из owner sheet, а не утверждённый выбор; движок/языки требуют решения владельца и фактического прогона. |
| INPUT-03: 4000×4000/12 MP, JPEG/PNG/WEBP, EXIF normalize, 96 MiB | 96 MiB и один тяжёлый job — frozen-инвариант. Размеры/форматы и orientation policy — продуктовый контракт, не выводятся из memory budget. | Сохранить 96 MiB/1 job. Остальные пределы не считать утверждёнными; принимать их из утверждённого профиля. Проверить отрицательные входы и измерить реальную память. |
| INPUT-04: 100 страниц, A4, 3000 px, JPEG quality 85; PDF import ≤500 страниц, 200 dpi | FROZEN требует границы входа/вывода, но sheet оставляет указанные значения рекомендацией. Существующий harness рендерит максимум 2048 px и растягивает изображение; это не доказательство нужного качества. | Не зашивать рекомендацию до решения/FACT-08. Реализовать параметры через профиль; до их утверждения можно проверять механику на DEV fixtures, но нельзя заявлять качество/лимит MVP. |
| INPUT-05: официально поддерживать внутреннее хранилище через SAF; cloud outcome Unknown | Поддержка конкретных provider tuples — продуктовое обязательство. Capability для сохранения и восстановления результата не равна совместимости со всеми облаками. | Не обещать cloud поддержку без tuple evidence. Owner должен утвердить intended support scope; обеспечить неизвестный исход без потери workspace по FROZEN. |
| DR-01…26 | Рекомендации покрывают существенные UX-правила. DR-24…26 закрывают пробелы функциональной карты, но также являются рекомендациями. DR-23 конфликтует с CONFLICT-01 (UFS BR-016-04 vs FROZEN F-15). | Перенести каждый пункт в единый ledger с `PENDING OWNER`, `NEEDS FACT`, `DECIDED` или `CONFLICT`; зависимые CAP ждут соответствующего решения. CONFLICT-01 разрешается только владельцем. Не маркировать рекомендованный текст как принятый. |
| CONFLICT-02 transient capture card | Может согласовать мгновенную визуальную обратную связь с FROZEN: временное состояние UI не является authoritative Page до durable artifact + metadata transaction. | Принять как derived implementation pattern; отрицательный сценарий: сбой публикации/БД убирает transient card, persistent Page отсутствует. |
| CONFLICT-03 полный PDF+JPG и UnknownOutcome | Требование полного набора outputs сопоставимо с FROZEN no-blind-retry только при сохранении workspace после partial/unknown. SAF не обещает атомарную многозаписную транзакцию. | Сохранить fail-closed основу FROZEN; конкретный UX retry, новые имена/имена файлов и критерий reverse-read утвердить по CAP-009/010 и provider FACT. Не удалять уже созданные пользовательские файлы как компенсацию. |
| CONFLICT-04 draft при убийстве процесса | FROZEN отличает volatile graphical/editor draft и долговечные committed pages; продуктовая гарантия draft recovery относится к отдельному открытому GAP-G01. | Считать drafts временными только если это подтверждено Product Contract/владельцем; до решения не рекламировать сохранение/потерю черновика. Тесты отдельно проверяют сохранённую страницу и несохранённый draft. |
| CONFLICT-05 немедленное удаление файла | Рекомендация идти по FROZEN согласуется с INV-13…15: tombstone/Undo и отдельный CleanupArbiter с защитой артефактов. Текущий harness удаляет страницу и сразу пытается удалить файл. | В продукте соблюдать FROZEN: немедленное физическое удаление запрещено. CAP-008/016 используют tombstone, Undo и защищённую очистку. |
| CONFLICT-06 лимит 2048 px | Текущий код использует этот cap, но INPUT-04 и качество PDF не утверждены. | Только legacy/DEV behavior. Не использовать его как продуктовое требование; отразить в FACT-08 и PDF quality checks. |

### 4.3. Результат чтения фактической рабочей копии

Проверенная версия исходников — текущая рабочая копия поверх `ad9983b`, содержащая незакоммиченные пользовательские изменения (см. `git status --short`). Это статический аудит кода; функциональное поведение в этом разделе не считается независимо проверенным запуском.

| CAP | Что обнаружено чтением исходников | Оценка относительно UFS |
|---|---|---|
| 001 | CameraX preview/shutter и callback в `MainActivity`; runtime permission есть. | Частичная harness-функция; нет продукта с frozen durable capture/session lifecycle, а состояние permission denial/settings неполно. |
| 002 | `GetContent("image/*")` передаёт один URI в `addPageFromUri`. | PDF, multi-select, persistent SAF grant, import session, progressive recovery отсутствуют. |
| 003 | Есть JPEG capture/decode и OCR adapter; отдельного production pipeline edge/crop/perspective/enhancement/quality projection не видно. | Не закрыт. |
| 004 | `retakePage` записывает в тот же `.jpg`, затем меняет revision/metadata. | Нарушает immutable ImageVersion/durable transaction/fencing класс; продуктовый путь переписать, не переносить. |
| 005 | OCR выполняется через ML Kit adapter и пишет поле текста; текущий callback не привязан к `ImageVersion`/expected revision в момент записи. | Stale OCR может стать текущим; text-layer PDF отсутствует. Русский scope не подтверждён движком. |
| 006 | Selection переключается long-press в памяти `PageItem.isSelected`. | Нет устойчивого selection/Active separation и полного select-all поведения. |
| 007 | В текущем UI найден горизонтальный RecyclerView, но reorder controller/транзакции не обнаружены. | Не закрыт. |
| 008 | `deletePage` удаляет DB Page, создаёт cleanup claim и пытается удалить файл в том же пользовательском flow. Не обнаружены tombstone, Undo UI или Clear. | Не соответствует frozen retention/undo и зависит от решения о точном UX. |
| 009 | `exportToPdf` строит PDF из `pages` всего workspace. Нет frozen manifest, JPG set, searchable text layer, choice/fallback flow. | Текущий export не закрывает CAP-009. |
| 010 | `CreateDocument` сохраняет только готовый PDF; имя строится из timestamp. | Нет общего имени PDF/JPG, collision policy, tracked provider state/no-overwrite guarantee для комплекта. |
| 011 | Можно открыть активную Page в `ImageView`; обнаружена базовая навигация по миниатюрам. | Полный viewer navigation, persisted Active и deletion boundary behavior не закрыты. |
| 012 | Есть поворот на 90°, записывается как rotation metadata; нет crop/brightness/contrast/sharpness, preview/Undo/editor guard. | Частично; графический редактор не реализован. |
| 013 | Нет `RecentDocument` индекса/списка; `lastExportedPdf` хранится в памяти Activity. | Не закрыт и не переживает recreation/restart как recent. |
| 014 | Сохранённый PDF открывается только для Share/Save as как единый готовый файл. | Saved PDF editor с delete/reorder/Undo/new output отсутствует. |
| 015 | Нет Try-again flow/provenance. | Не закрыт. |
| 016 | Есть harness recovery/cleanup tables, но workspace session inactivity/24h retention product path не найден. | Не закрыт; политика срока остаётся конфликтной. |

Дополнительные общие находки по коду (класс ошибки, не приемочный полный аудит): `calculateSha256` при ошибке возвращает 64 нуля; `loadPagesFromDatabase` пропускает запись без файла; `PageItem` имеет изменяемые поля; общий manifest включает exported `MockDocumentsProvider`; FileProvider paths нужно ограничить минимально нужной папкой. PDF renderer применяет 2048 px cap и рисует изображение в полный прямоугольник A4 без сохранения пропорций. Эти места относятся к harness и служат негативными тестами/ограничениями на перенос. Новые product adapters проверять от требований целиком, а не исправлять только указанные строки.

Непроверено: запуск APK 0.1.5 на устройстве/эмуляторе, статус instrumentation, полный Gate B0–B9, physical Q, качество OCR и PDF. Нет отчёта о сборке/тестах этой ревизии в рамках настоящего анализа.

---

## §5. План работ и зависимости

### Этап 0 — защищённая исходная точка и среда

| ID | Результат | DONE, если приложено | Negative/guard |
|---|---|---|---|
| T0.1 | Зафиксированы Git baseline и чужие изменения | `git status --short`, HEAD, список владельческих путей в `execution/handoff.md` | Любой commit исключает исходные unstaged/unknown файлы; нет `git add -A` |
| T0.2 | Проверены JDK/Gradle/SDK, app version и конфигурация устройства | Фактические версии + clean assemble каждого действующего variant с exit code | Отсутствующий SDK/эмулятор даёт BLOCKED-ENV, не DONE |
| T0.3 | Установлен проверяемый вызов host/JVM/Android suites | Команда и результат каждого реально существующего набора | Ошибка запуска не называется «0 failed» |
| T0.4 | Полный static inventory исходных дефектов KD-01…16 и аналогов | Таблица KD → места → invariant → будущий тест; ручной поиск подтверждён | Search hit без проверки кода не считается подтверждённой находкой |
| T0.5 | Единая локальная проверка build + host suite + gate/evidence validators | Скрипт с non-zero при любом провале, версии и журналом команд | Нет поглощения stderr/exit codes и пропуска отсутствующего набора |
| T0.6 | Изоляция продукта от harness hooks/providers/test-only surfaces | Product manifest проверен на installed merged manifest; test classes не входят в APK | Отрицательная проверка: `MockDocumentsProvider`, `TestHooks`, S1–S6 service отсутствуют в product APK |

Использовать текущий `:app` как legacy harness. Предлагаемая реализация создаёт отдельный `:core` (Kotlin/JVM) и `:product` (Android application), чтобы не перемещать uncommitted harness файлы и не менять его host runner. На первом переходе собрать оба приложения и сохранить текущий package `:app`; migration data и application ID продукта остаются отдельными решениями владельца.

### Этап 1 — факты, решения, контракты

| ID | Результат | DONE, если приложено | Блокирует |
|---|---|---|---|
| T1.1 | Решения DR-01…26 и INPUT-01…07 классифицированы | Каждый ID имеет источник, authority, статус, затрагиваемые CAP/этап и evidence | Никакой unchecked recommendation не считается owner decision |
| T1.2 | Versioned Product Contract / addendum | Согласованный UFS scope и разрешённые конфликты добавлены с источником, датой, заменённым решением | Нерешённый conflict блокирует только зависимые требования и gate |
| T1.3 | Полный product task ledger + traceability | Все BR/AC UFS имеют task, code symbol, positive/negative test, evidence field и status | Нет потерянных/дублирующих AC; отслеживание по ID |
| T1.4 | Implementation Architecture review | Component ownership, authoritative schema, transaction APIs, error/fallback contracts, adapter boundaries, migration strategy и ADR утверждены | Product persistence write path не начинается до принятия P0 architecture; можно делать scaffolding и independent facts |
| FACT-01 | OCR supported-script/offline/corpus spike | Official docs + build/dependency inspection + corpus result per requested script/device | Русский не заявляется без результата и owner choice |
| FACT-02 | PDF writer spike | Файл на всех fixtures структурно валиден; text extraction/search работает где требуется; сохранены raster render, byte size и memory observations | Числовые цели берутся только из approved INPUT-04 |
| FACT-03 | Image-processing library/runtime spike | Supported ABI/minSdk, license/source, APK size, orientation, memory and reproducible image fixtures | Непроверенная native dependency не добавляется |
| FACT-04 | SAF provider tuple inventory | Provider/package version/OS/destination/access mode и success/failure/unknown test evidence | Непроверенный provider не маркируется supported |
| FACT-05 | Device and signing inputs | supported tuple, app identity, key ownership/signing process recorded | Release signature/upgrade compatibility pending owner input |

Параметры DEV fixtures хранить отдельно от Product Config. Удалять/переименовывать frozen inputs запрещено. Где владелец не ответил: в ledger записать `NEEDS PREFERENCE`/`NEEDS AUTHORITY`, выполнять независимые tasks и не создавать observable behavior вместо него.

### Этап 2 — P0 persistent foundation

| ID | Scope | Обязательные проверки |
|---|---|---|
| T2.1 | Domain IDs, Workspace/Session/Page/ImageVersion/Artifact/SavedDocument модели и schema v1 из FROZEN §7 | Null/empty/duplicate IDs, invalid enum/schema version; constraints reject invalid rows |
| T2.2 | SQLDelight StateStore, только transactional authority для write | Один atomic transaction на каждую TX-…; forced SQL error rollback; diagnostic reads read-only |
| T2.3 | ArtifactRepository: temp write, fsync/atomic publish, checksum, refs | kill/error до и после publish; DB никогда не ссылается на неполный artifact; hash failure propagates, не placeholder |
| T2.4 | Content revision, image-version fencing и conditional candidate commit | поздний OCR/processing после Retake/Edit/Delete не меняет current projection |
| T2.5 | HeavyWorkScheduler, один тяжёлый job и общий 96 MiB token budget | N concurrent callers; >budget отказ; двойной release/exception; capture не ждёт permit |
| T2.6 | ProjectionBuilder + ApplicationPolicy immutable read model | UI не пишет; Active/Selected/order независимы; null/missing artifact виден как recoverable error |
| T2.7 | RecoveryCoordinator, schema gate, bounded startup work, quarantine | supported migration сохраняет records/files; unsupported schema не очищает БД; repeated recovery идемпотентна |
| T2.8 | Self-test sandbox protocol и persisted report (если выбран после DEC-Q) | sandbox не трогает real workspace; failure сохраняет diagnostics; DEV и device report modes не смешиваются |
| T2.9 | Kotlin/JVM transaction and invariant suite | Положительный сценарий и отдельный отказ для P/F/C/E каждого TX/инварианта; команда/версия/evidence |

Имена методов/таблиц сверить с FROZEN до генерации схемы. Android provider I/O запрещён внутри открытой DB transaction. Реальная `:product` сборка использует другой DB-файл от harness.

### Этап 3 — P1 Camera/workspace/viewer core

T3.1 Workspace projection + camera screen; T3.2 localized resources/accessibility; T3.3 permission denied/rationale/settings; T3.4 durable CameraX capture; T3.5 selection; T3.6 reorder; T3.7 viewer/current-page navigation.

Закрыть CAP-001/006/007/011 согласно решениям, все UFS AC в traceability. Проверить burst без ожидания OCR; повторный shutter при pending write; camera permission denied/never-ask-again; камера отсутствует/отключена; activity/process recreation; отказ fsync/metadata commit; page видна только после commit; Active не меняет Selected; reorder selection не меняет содержимое; boundary navigation не зациклена. На ошибке транзиентный preview удаляется, страница не появляется в authoritative state.

### Этап 4 — P2 image pipeline, OCR, Retake, editor

T4.1 decode/orientation/memory-bound pipeline; T4.2 document edge/crop/perspective/rotation/enhancement; T4.3 quality policy/threshold calibration; T4.4 current-version OCR; T4.5 retry and Re-OCR; T4.6 manual editor preview/dirty guard/Undo; T4.7 immutable image edit commit; T4.8 Retake same Page ID/new ImageVersion; T4.9 stale OCR/processing conditional commits.

Закрыть CAP-003/004/005/012. Не принимать качество только по наличию результата: FACT-03/approved DR-05 corpus должен включать хорошие и плохие позитив/негатив снимки; дать confusion table и используемые пороги. Проверки: rotated EXIF, huge/corrupt image, blur/glare/partial edge, decode OOM, OCR empty vs failure, unsupported script/offline/model unavailable, 3 final errors только если DR-08 утверждён, поздний old-version candidate, Retake failure сохраняет предыдущую версию, edit Undo до clean, Save/Back/close/active navigation при dirty draft, saved edit не запускает OCR без разрешённого правила. Артефакты corpus и отчёты не коммитить; записать SHA-256.

### Этап 5 — P3 image/PDF import

T5.1 SAF picker for multiple sources; T5.2 persist/revalidate grant; T5.3 image materialization; T5.4 PDF metadata/page count/text-layer inspection; T5.5 bounded page rendering; T5.6 ImportSession progressive state/recovery/idempotency.

Закрыть CAP-002. Проверить multi-image + multi-page PDF order; unknown/malformed/encrypted/empty PDF; PDF с usable/unusable text; revoke URI access during import; process kill after each materialized page; duplicate picker callback; storage below requirement; full/corrupt final page; source remains byte-identical. Импорт не держит DB transaction во время ContentResolver/PDF I/O; no duplicate `(ImportSessionId,sourcePageIndex)` после resume.

### Этап 6 — P4 frozen Save and external export

T6.1 SAF destination grants/security paths; T6.2 deterministic name/collision resolver; T6.3 immutable SaveManifest freeze; T6.4 PDF with images + searchable text layer + JPG set; T6.5 read-back validators/local output commit; T6.6 provider exports and UnknownOutcome recovery.

Закрыть CAP-009/010. Проверки: zero/one/max selected; deselected page excluded; selected order frozen; edit/retake/order mutation after freeze не меняет состав; PDF parser page count/order, text extraction/select/copy and independent raster render; aspect ratio/orientation/no blank page; every companion JPG corresponds to frozen version; duplicate/invalid filename; provider conflict/revoked permission; each file boundary kill; partial/ambiguous result keeps workspace and evidence, does not overwrite/delete existing file; retry never blindly repeats same name/attempt. Provider calls remain outside DB TX. Product limits only from owner-approved INPUT-04/05. DEV profile does not close FACT.

### Этап 7 — P5 Delete/Undo/retention

T7.1 tombstone/delete and active-page policy; T7.2 batch Delete/Clear + Undo; T7.3 reference protection/CleanupArbiter; T7.4 durable time evidence/clock discontinuity; T7.5 startup cleanup/recovery.

Закрыть CAP-008/016 по FROZEN INV-13…15/25 и разрешённому DR-11/12/23/26. Проверить page/file/reference matrix with active Save, Undo, pending OCR, shared image; late result cannot resurrect; undo exact order/selection/current image; empty clear, repeated delete, process death after tombstone/GC claim/file deletion/finalize; wall-clock rollback, reboot/monotonic epoch change, active user activity vs background processing, min cleanup cadence. Пока CONFLICT-01/DR-23 не подтверждён, destructive expiry path и Product acceptance blocked; simulator tests may run against both candidate policies without declaring one product default.

### Этап 8 — P6 Recent, saved editor, Try again

T8.1 recent index/expiry/preview; T8.2 saved PDF viewer/navigation; T8.3 isolated saved-PDF editor and output; T8.4 Try-again provenance/materialization.

Закрыть CAP-013/014/015. Проверить пустой/expired recent без filesystem scan; missing/revoked PDF URI; active doc/page boundaries; edit with no pages; close clean/dirty; Save creates a new file and original hash stays identical; duplicate Try-again tap suppressed; intentional second action creates distinct Page IDs; preserved source PDF/page, order and usable text metadata; all-or-nothing or approved partial behavior per DR-22; process death leaves original unchanged.

### Этап 9 — full product regression, crash matrix, self-test

T9.1 map every AC to executable/integration/manual test; T9.2 product crash matrix over irreversible boundaries; T9.3 isolated self-test report/validator; T9.4 end-to-end regression on release candidate.

Run clean assembly, all JVM tests, product instrumented tests, host validators and the full approved E2E scenarios against exact product build hash. Positive and negative cases include SQLite/I/O failure, cancellation/timeouts, permission/provider changes, process death at every durable boundary, missing/corrupt evidence, duplicate input and recovery idempotency. A subset or killed command is INCOMPLETE. Save logs, APK/test APK hashes, result JSON, device/API identity, exit codes and traces under `results/PRODUCT/<version>/` and references under `execution/evidence/`.

### Этап 10 — P7 release acceptance

T10.1 support/compatibility statement; T10.2 accessibility and localization; T10.3 signed reproducible APK and SHA-256; T10.4 fresh install and supported upgrade; T10.5 Xiaomi/manual checklist + approved Q/self-test route; T10.6 independent review packet.

Release acceptance inherits exact criteria from `docs/06_MVP_ROADMAP.md` §8 and `docs/08_MVP_GATES_AND_ACCEPTANCE.md` §7. No undocumented numeric performance or image-quality goal. APK, signing identity, supported provider/device tuples, source traceability, known limitations, user instructions and reviewer verdict must be linked. Any mandatory owner decision, external fact, device check or independent review still missing means `REVIEW_REQUIRED`/`BLOCKED`, never `DONE`.

---

## §6. Command/evidence contract

After T0, define and pin exact tasks from actual Gradle module names. Until then, commands in this table are acceptance targets, not claims that tasks already exist:

| Level | Target command / method | Required recorded result |
|---|---|---|
| Host | `python3 -m unittest discover tests` | executed test count, exit code, environment |
| Gradle | `./gradlew clean :app:assembleDebug :product:assembleDebug` (adjusted after module split) | build variant, version, APK path/hash, exit code |
| Core | `./gradlew :core:test` | tests by class, positive/negative outcomes, exit code |
| Product Android | `./gradlew :product:connectedProductDebugAndroidTest` | emulator serial/API, APK hash, test count, logs; DEV only |
| Install smoke | explicit serial install + launch + scripted scenario | no implicit serial; full command/output and device tuple |
| Physical | docs 04 or explicitly owner-approved replacement | exact device, OS build, app SHA, completed checklist/selftest files |

For every task store `execution/evidence/<TASK-ID>.md`: source IDs; commit/build hash; OS/JDK/Gradle/SDK and device; command; exit code; test/run counts; concise output; raw artifact links and SHA-256; NOT RUN and reason; reviewer verification separate from implementer claims. Heavy artifacts under `results/PRODUCT/<TASK-ID>/` (ignored by Git). No success report without the corresponding file.

## §7. Ledger, versions, and Git boundaries

Use existing `execution/tasks.json`, `execution/decisions.md`, `execution/handoff.md`; create one product traceability CSV and one evidence/review per task. Do not create a second decision ledger. A requirement with no test/evidence is not closed. A new finding gets a stable ID and class-wide inventory before coding.

Product APK uses a distinct version track from harness 0.1.5. Proposed mapping: `versionName=0.<stage>.<patch>`, `versionCode` begins at 100 and increases for each owner-delivered APK; verify all actual version files and release version with owner before first handoff. Every source-code change updates related project version metadata and docs in the same bounded task.

Before edits, record `git status --short`, current branch and `HEAD=ad9983b`; never overwrite, stage, commit or reformat the pre-existing 14 tracked diffs, `app/src/main/res/xml/`, or `photo_2026-09-23_17-09-05.jpg`. Stage our own paths selectively. One independent task = one commit. Add a tag immediately before a risky experiment only after confirming it will not be confused with the user's dirty state. If a user-owned modified file is required, stop using that path and implement through a new module or request a merge boundary.

## §8. Current blockers and continuation rules

Open owner/product inputs at plan revision 1.1: INPUT-01/02, DEC-Q, DEC-APPID, DEC-SIGN, DEC-LEGACY, DEC-LANG-UI, DEC-MINSDK details, DEC-OCR-LANG/ENGINE, DEC-PDF-ENGINE, INPUT-03/04/05, DR-01…26 and CONFLICT-01/03/04. Their recommendations remain unconfirmed unless the owner marks them or explicitly confirms them in chat. Recheck conversation for explicit answer before marking a row resolved.

Already available without lowering product scope: product module scaffolding in new files, frozen schema/API review, harness isolation, source traceability, Android official fact research, evaluator and core transaction implementation whose observable rules are fully defined by FROZEN. Tasks depending on missing choices can be prepared with interfaces/configuration but cannot assert product behavior.

External gates: physical Xiaomi USB/access and owner SAF interaction if required; signing secret/key must be supplied or safely generated under explicit ownership; independent reviewer for final P-gate. Continue unrelated work while an external gate is blocked. Never fabricate device evidence, approval, credentials, provider compatibility or sample-corpus labels.

## §9. Progress and handoff format

Use `docs/AGENT_COLLABORATION_TEMPLATES.md` §5 for task reports and §6 for review. Short interim update:

```text
TASK / CONTRACT: <ID, version>
STATUS: IN_PROGRESS | BLOCKED | BLOCKED-ENV | REVIEW_REQUIRED
FACTS VERIFIED: <code/docs/commands actually checked>
CHANGES: <owned paths only>
EVIDENCE: <file/hash or NOT RUN + reason>
OPEN INPUTS: <IDs and affected tasks>
NEXT: <independent next task>
```

At handoff name exact source revision/dirty state, accepted task commits, first incomplete dependency, current device/environment, owner decisions needed and how to resume. Keep claims labelled VERIFIED BY CODEX / REPORTED BY IMPLEMENTER / NOT VERIFIED.

## §10. Definition of completion and exclusions

Product MVP is complete only when:

1. all 16 CAP or explicitly owner-approved scope dispositions are recorded;
2. every included BR/AC is traceable to production code, a test/manual check and release-build result;
3. all frozen invariants and applicable P/F/C/E checks pass, including negative/failure propagation scenarios;
4. all P0–P7 acceptance gates in docs 06/08 have evidence for one identified release build;
5. no unresolved scope, product behavior, platform FACT, provider tuple, data safety, blocking defect, missing required physical check, or independent review remains;
6. APK/signing/install/upgrade/support documentation, release hash and review verdict are delivered.

This plan does not authorize changing FROZEN/Protocol, changing CAP scope, product thresholds, language/provider support or retention behavior silently. A build, working screen, emulator-only cycle, harness PASS, implementer self-review, or partial CAP subset is not Product MVP. A terminal blocker must be reported with exact missing decision/action, affected gate, work already completed, evidence path and the next independent step.
