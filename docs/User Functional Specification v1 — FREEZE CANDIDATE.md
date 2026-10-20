# Document Scanner — User Functional Specification v1

**Status:** `FREEZE CANDIDATE — PRODUCT DECISIONS PENDING`

**Authoritative product source:** `Document Scanner — Functional Map — FINAL VERIFIED v1`

**Decision authority:** `01-decision-policy`

## Normative rules

1. Functional Map определяет существующие capability и пользовательскую семантику.
2. Эта спецификация не расширяет scope Functional Map.
3. Поведение, отсутствующее в карте и не являющееся логически неизбежным следствием её требований, не считается решённым.
4. Запись в `Open Questions / Decision Register` не является требованием и не определяет default.
5. Внутренние библиотеки, worker model, storage implementation, database schema, retry implementation и аналогичные технические механизмы не являются частью этой спецификации, если они не изменяют observable product behaviour.
6. Термины `Page`, `WorkspaceSession`, `imageVersionId`, `EditorSession`, `Active`, `Selected`, `Recent index` используются в смысле Functional Map.
7. Для каждого Business Rule указан authoritative source.
8. Acceptance Criterion проверяет только существующий BR, сценарный шаг или явно определённый state transition.

---

# CAP-001 — Камера и серийное сканирование

## 1. Capability

**Цель:** быстро добавить бумажную страницу в workspace и сразу иметь возможность снять следующую.

**Входит:** shutter, создание Page, немедленное добавление Page в workspace, haptic feedback, серийная съёмка без ожидания обработки предыдущей Page, camera permission flow.

**Не входит:** image processing/quality — CAP-003; Retake — CAP-004; OCR — CAP-005; selection — CAP-006.

## 2. Входы / выходы

**Вход:** shutter при доступной camera permission либо запуск предусмотренного permission flow.

**Выход:** новая Page существует в workspace; камера доступна для следующего кадра.

## 3. Основной сценарий

1. Пользователь нажимает shutter.
2. Новая Page немедленно создаётся и добавляется в workspace.
3. Выполняется лёгкий haptic feedback.
4. Page передаётся в асинхронную обработку.
5. Камера сразу доступна для следующего кадра.
6. Следующий shutter не ждёт processing/crop/OCR предыдущей Page.

## 4. Business Rules

`BR-001-01 | Shutter немедленно создаёт Page и добавляет её в workspace. | Source: functional-map §4`

`BR-001-02 | Следующий кадр не ждёт crop, image processing или OCR предыдущей Page. | Source: functional-map §§4, 24`

`BR-001-03 | Несколько Page имеют независимый processing state и могут обрабатываться асинхронно. | Source: functional-map §4`

`BR-001-04 | Shutter сопровождается лёгким haptic feedback. | Source: functional-map §4`

`BR-001-05 | Camera permission, отказ и Don't ask again обрабатываются; для Don't ask again предусмотрен Open Settings. | Source: functional-map §22`

## 5. Состояния и переходы

`shutter → Page создана → дальнейший processing CAP-003`

Permission flow применяется независимо до возможности capture.

## 6. Edge cases и ошибки

Processing любой ранее созданной Page не блокирует следующий shutter.

Отказ camera permission не должен обходиться скрытым альтернативным capture flow.

## 7. Acceptance Criteria

`AC-001-01 | После shutter Page существует в workspace до завершения её processing/OCR. | проверяет: BR-001-01, BR-001-02`

`AC-001-02 | Пока Page A находится в Processing, пользователь может выполнить shutter и создать Page B. | проверяет: BR-001-02, BR-001-03`

`AC-001-03 | Shutter вызывает haptic feedback. | проверяет: BR-001-04`

`AC-001-04 | При Don't ask again доступен предусмотренный переход Open Settings. | проверяет: BR-001-05`

## 8. Open Questions

`DR-01` — позиция новой camera Page в workspace order.

---

# CAP-002 — Импорт изображений и PDF

## 1. Capability

**Цель:** добавить существующие изображения и PDF в workspace как равноправные Page.

**Входит:** «+», Android File Picker, один/несколько изображений и PDF, materialization, progressive import больших PDF, crash-safe continuation.

**Не входит:** processing/quality semantics — CAP-003; OCR semantics — CAP-005.

## 2. Входы / выходы

**Вход:** выбранные через File Picker изображения/PDF.

**Выход:** изображения и страницы PDF представлены отдельными workspace Page.

## 3. Основной сценарий

1. Пользователь нажимает «+».
2. Открывается Android File Picker.
3. Пользователь выбирает поддерживаемые изображения/PDF.
4. Каждое изображение materialize как Page.
5. Каждая страница PDF materialize как Page.
6. Images проходят тот же primary processing/OCR pipeline, что camera capture.
7. Для PDF existing usable text layer переиспользуется; при его отсутствии применяется OCR.
8. Большой PDF materialize прогрессивно; готовые Page появляются до окончания полного импорта.
9. UI при этом не блокируется.
10. После process death большой import продолжается без потери готовых Page и без дублей.

## 4. Business Rules

`BR-002-01 | «+» открывает Android File Picker; поддерживается импорт одного/нескольких изображений и PDF. | Source: functional-map §10`

`BR-002-02 | Каждое изображение и каждая страница PDF являются отдельной Page. | Source: functional-map §§3, 10`

`BR-002-03 | Импортированные изображения проходят тот же primary processing/OCR pipeline, что camera capture. | Source: functional-map §10`

`BR-002-04 | PDF без usable text layer OCR-ится; usable existing layer переиспользуется насколько технически возможно. | Source: functional-map §10`

`BR-002-05 | Camera/image/OCR-less PDF/searchable PDF Page могут совместно использоваться по source-neutral workspace rules. | Source: functional-map §§10, 24`

`BR-002-06 | Большой PDF разбирается в фоне; progress видим; UI не блокируется; готовые Page появляются прогрессивно. | Source: functional-map §10`

`BR-002-07 | После crash/process death сохраняются source URI, уже materialized Page и progress marker; продолжение начинается с первой ещё не materialized страницы без дублей. | Source: functional-map §10`

`BR-002-08 | После успешной подготовки imported Page auto-select как часть imported working set. | Source: functional-map §11`

`BR-002-09 | Перед тяжёлым импортом проверяется свободное место. | Source: functional-map §15`

## 5. Состояния и переходы

`source selected → materialization → Page available`

Для большого PDF materialization является прогрессивной.

После process death:

`incomplete import → recovery → continuation without duplicates`

## 6. Edge cases и ошибки

Уже materialized Page не должны теряться при process death.

Повторный continuation не должен повторно materialize завершённые страницы.

## 7. Acceptance Criteria

`AC-002-01 | Каждое импортированное изображение и каждая страница PDF представлены отдельными Page. | проверяет: BR-002-02`

`AC-002-02 | При большом PDF хотя бы одна готовая Page может стать доступна до завершения materialization всего PDF. | проверяет: BR-002-06`

`AC-002-03 | Пока большой PDF materialize, пользовательский интерфейс остаётся доступным для остальных предусмотренных действий. | проверяет: BR-002-06`

`AC-002-04 | После recovery ранее materialized Page присутствуют ровно по одному экземпляру. | проверяет: BR-002-07`

`AC-002-05 | Успешно подготовленные imported Page auto-select. | проверяет: BR-002-08`

`AC-002-06 | Перед тяжёлым импортом выполняется проверка свободного места. | проверяет: BR-002-09`

## 8. Open Questions

`DR-02` — положение и взаимный order нового imported block в существующем workspace.

`DR-03` — пользовательское поведение при отрицательном результате free-space check.

`DR-04` — критерий usable existing PDF text layer.

---

# CAP-003 — Автоматическая подготовка и quality check Page

## 1. Capability

**Цель:** получить автоматически подготовленную Page без обязательного подтверждения нормального кадра.

**Входит:** primary image pipeline, quality check, Processing / Ready / Low quality / Failed.

**Не входит:** Retake semantics — CAP-004; OCR semantics — CAP-005; manual graphical edit — CAP-012.

## 2. Входы / выходы

**Вход:** новая capture/import Page либо новая фотография после Retake.

**Выход:** `Ready`, `Low quality` или `Failed`; до окончания обработки — `Processing`.

## 3. Основной сценарий

1. Page находится в Processing.
2. Выполняется edge detection.
3. Crop.
4. Perspective correction.
5. Rotation detection.
6. Image enhancement.
7. Quality check.
8. Нормальный результат → Ready без подтверждения.
9. Недостаточное качество → Low quality.
10. Failure → Failed с Retry.

## 4. Business Rules

`BR-003-01 | Primary pipeline: edge detection → crop → perspective correction → rotation detection → image enhancement → quality check → OCR. | Source: functional-map §5`

`BR-003-02 | Perspective/orientation/image автоматически корректируются для читаемости/OCR без недопустимого искажения документа. | Source: functional-map §5`

`BR-003-03 | Нормальный кадр не требует ручного подтверждения. | Source: functional-map §5`

`BR-003-04 | QC минимум оценивает blur/резкость, motion blur, glare, exposure, contrast, crop completeness, perspective и resolution. | Source: functional-map §6`

`BR-003-05 | Low quality Page остаётся в workspace и не auto-select для Save. | Source: functional-map §§6, 11`

`BR-003-06 | Для Low quality доступны Retake/Rotate/Crop. | Source: functional-map §7`

`BR-003-07 | Failed показывает Retry вместо бесконечного spinner. | Source: functional-map §7`

`BR-003-08 | После process death незавершённая job восстанавливается, перезапускается либо получает recoverable failure; фиктивный вечный Processing запрещён. | Source: functional-map §7`

`BR-003-09 | Ручная graphical edit сама по себе не запускает primary processing/OCR pipeline повторно. | Source: functional-map §5`

## 5. Состояния и переходы

`Processing → Ready`

`Processing → Low quality`

`Processing → Failed`

`Failed → Retry → processing/recovery flow`

`Low quality → Retake → Processing` через CAP-004.

## 6. Edge cases и ошибки

Low quality не удаляет Page.

Low quality/Failed не auto-select.

Process death не может оставить Page навсегда в фиктивном Processing.

## 7. Acceptance Criteria

`AC-003-01 | Нормально обработанная Page переходит в Ready без ручного confirmation step. | проверяет: BR-003-03`

`AC-003-02 | Page, classified как Low quality, остаётся в workspace и не auto-select. | проверяет: BR-003-05`

`AC-003-03 | Для Low quality доступны Retake, Rotate и Crop. | проверяет: BR-003-06`

`AC-003-04 | Failure отображает Retry вместо бесконечного Processing. | проверяет: BR-003-07`

`AC-003-05 | После process death незавершённая Page либо продолжает/restarts обработку, либо получает recoverable failure. | проверяет: BR-003-08`

## 8. Open Questions

`DR-05` — decision thresholds / calibration rule для Ready vs Low quality.

`DR-06` — должен ли quality status автоматически переоцениваться после сохранённого manual Rotate/Crop.

---

# CAP-004 — Retake проблемной Page

## 1. Capability

**Цель:** переснять содержимое существующей Page без потери её identity, позиции и selection.

## 2. Входы / выходы

**Вход:** Retake существующей Page.

**Выход:** та же Page с новой фотографией и новым processing/quality/OCR cycle.

## 3. Основной сценарий

1. Пользователь запускает Retake.
2. Создаётся новая фотография.
3. Она заменяет image content той же Page.
4. Page ID, position и selection сохраняются.
5. Старые processed image, OCR-result и quality-result инвалидируются.
6. Image-independent metadata сохраняются.
7. Новая фотография проходит processing → quality check → OCR.

## 4. Business Rules

`BR-004-01 | Retake заменяет content той же Page; новую Page не создаёт. | Source: functional-map §6`

`BR-004-02 | Stable Page ID, position и selection сохраняются. | Source: functional-map §6`

`BR-004-03 | Старые processed image, OCR-result и quality-result инвалидируются. | Source: functional-map §6`

`BR-004-04 | Image-independent metadata сохраняются. | Source: functional-map §6`

`BR-004-05 | Новая фотография заново проходит processing → quality check → OCR. | Source: functional-map §6`

## 5. Состояния и переходы

Для явно определённого случая:

`Low quality → Retake → Processing → Ready / Low quality / Failed`

## 6. Edge cases и ошибки

Если Selected Page подвергнута Retake, её existing selection сохраняется даже если новый результат впоследствии окажется Low quality/Failed; правило «не auto-select» не является правилом автоматического deselect.

## 7. Acceptance Criteria

`AC-004-01 | После Retake количество Page не увеличивается. | проверяет: BR-004-01`

`AC-004-02 | Page ID, workspace position и Selected state совпадают со значениями перед Retake. | проверяет: BR-004-02`

`AC-004-03 | Старые processed image/OCR/quality не используются для новой фотографии. | проверяет: BR-004-03`

`AC-004-04 | Новая фотография проходит новый processing/quality/OCR cycle. | проверяет: BR-004-05`

## 8. Open Questions

`DR-07` — доступность Retake вне состояния Low quality.

---

# CAP-005 — OCR и Re-OCR

## 1. Capability

**Цель:** получить OCR, соответствующий конкретной current saved image version Page.

## 2. Входы / выходы

**Вход:** primary capture/import pipeline либо explicit Re-OCR current saved image version.

**Выход:** OCR-result, связанный с конкретной imageVersionId, либо предусмотренный failure/fallback.

## 3. Основной сценарий

### Auto OCR
1. Primary capture/import pipeline доходит до OCR.
2. OCR запускается автоматически.
3. OCR не блокирует capture/UI.
4. Success → result связывается с imageVersionId.
5. Failure → Failed + Retry.

### Re-OCR
1. Пользователь явно запускает Re-OCR.
2. Распознаётся current saved image version.
3. Existing OCR той же version сохраняется до success нового распознавания.
4. Success → новый result заменяет previous.
5. Failure → previous OCR той же version остаётся доступен.
6. Page ID, position и selection не меняются.

## 4. Business Rules

`BR-005-01 | OCR primary capture/import запускается автоматически и не блокирует capture/UI. | Source: functional-map §8`

`BR-005-02 | OCR-result всегда привязан к imageVersionId. | Source: functional-map §§8, 24`

`BR-005-03 | OCR другой imageVersionId невалиден для current image version. | Source: functional-map §§8, 9, 24`

`BR-005-04 | После graphical Save новая current image version не имеет valid OCR до explicit Re-OCR; старый OCR остаётся связан с previous version. | Source: functional-map §§8, 26`

`BR-005-05 | Re-OCR не создаёт новую Page и сохраняет Page ID, position и selection. | Source: functional-map §8`

`BR-005-06 | Existing OCR той же version заменяется только после successful Re-OCR; при failure он сохраняется. | Source: functional-map §8`

`BR-005-07 | OCR failure → Failed + Retry. | Source: functional-map §8`

`BR-005-08 | После final OCR failure допускается explicit image-only PDF fallback; такая Page не маркируется searchable. | Source: functional-map §8`

`BR-005-09 | Searchable representation содержит visual Page + OCR text layer той же imageVersionId. | Source: functional-map §9`

`BR-005-10 | OCR text доступен для search, selection, copy и machine analysis. | Source: functional-map §9`

`BR-005-11 | Multilingual OCR использует automatic language detection. | Source: functional-map §8`

`BR-005-12 | Re-OCR и Try again saved PDF являются разными действиями с разной семантикой. | Source: functional-map §27`

## 5. Состояния и переходы

`current version без OCR → OCR success → valid OCR`

`OCR → failure → Failed / Retry`

`version с valid OCR → Re-OCR → success replaces / failure preserves old result`

`graphical Save → новая current version без valid OCR`

## 6. Edge cases и ошибки

Failure нового Re-OCR не уничтожает previous OCR той же version.

OCR previous image version никогда не используется как valid OCR новой version.

## 7. Acceptance Criteria

`AC-005-01 | Каждый valid OCR-result однозначно связан с imageVersionId. | проверяет: BR-005-02`

`AC-005-02 | После graphical Save previous OCR не считается valid для новой imageVersionId. | проверяет: BR-005-03, BR-005-04`

`AC-005-03 | Failed Re-OCR не удаляет existing OCR той же imageVersionId. | проверяет: BR-005-06`

`AC-005-04 | Re-OCR не меняет Page ID, position или selection. | проверяет: BR-005-05`

`AC-005-05 | Image-only output после final OCR failure возможен только через explicit fallback и не маркируется searchable. | проверяет: BR-005-08`

`AC-005-06 | OCR content searchable PDF поддерживает search/select/copy/machine analysis. | проверяет: BR-005-10`

## 8. Open Questions

`DR-08` — критерий final OCR failure.

`DR-04` — критерий usable existing PDF text layer.

---

# CAP-006 — Выбор Page в workspace

## 1. Capability

**Цель:** определить состав workspace PDF.

## 2. Входы / выходы

**Вход:** checkbox Page, Select all, предусмотренные auto-select events.

**Выход:** explicit workspace selection.

## 3. Основной сценарий

1. Пользователь меняет checkbox одной/нескольких Page либо использует Select all.
2. Selected-state изменяется.
3. State синхронно отображается во всех workspace views.
4. Active state остаётся независимым.
5. При workspace PDF Save selection определяет состав output.

## 4. Business Rules

`BR-006-01 | Active и Selected — независимые состояния. | Source: functional-map §11`

`BR-006-02 | Tap thumbnail → Active; tap checkbox → Selected. | Source: functional-map §11`

`BR-006-03 | Selection допускает одну, несколько последовательных/несмежных Page и Select all. | Source: functional-map §11`

`BR-006-04 | Новая Page текущей session auto-select только после успешного Ready; Low quality/Failed не auto-select. | Source: functional-map §11`

`BR-006-05 | Imported Page после successful preparation auto-select. | Source: functional-map §11`

`BR-006-06 | Main screen и Workspace Viewer используют один selection state. | Source: functional-map §§11, 18`

`BR-006-07 | Drag & Drop не меняет selection. | Source: functional-map §§11, 24`

`BR-006-08 | Page, materialized через Try again, добавляются с Selected=true. | Source: functional-map §29`

`BR-006-09 | Workspace selection определяет состав workspace PDF. | Source: functional-map §§3, 24`

## 5. Состояния и переходы

`Unselected ↔ Selected`

Active существует параллельно.

## 6. Edge cases и ошибки

Tap thumbnail не является selection command.

Retake сохраняет existing selection.

Reorder не сбрасывает selection.

## 7. Acceptance Criteria

`AC-006-01 | Tap checkbox меняет Selected-state независимо от Active. | проверяет: BR-006-01, BR-006-02`

`AC-006-02 | Tap thumbnail меняет Active без автоматического изменения Selected. | проверяет: BR-006-01, BR-006-02`

`AC-006-03 | Selection одинакова во всех workspace views. | проверяет: BR-006-06`

`AC-006-04 | New Ready current-session Page auto-select; Low quality/Failed не auto-select. | проверяет: BR-006-04`

`AC-006-05 | Imported successfully prepared Page auto-select. | проверяет: BR-006-05`

`AC-006-06 | Try-again materialized Page имеют Selected=true. | проверяет: BR-006-08`

## 8. Open Questions

`DR-09` — точный scope Select all, включая Processing / Low quality / Failed и Page предыдущих неистёкших WorkspaceSession.

---

# CAP-007 — Изменение порядка Page в workspace

## 1. Capability

**Цель:** определить workspace order, используемый для итогового PDF.

## 2. Входы / выходы

**Вход:** Drag & Drop Page.

**Выход:** изменённый workspace order.

## 3. Основной сценарий

1. Пользователь начинает Drag & Drop.
2. Отображаются перемещаемая Page и insertion position.
3. Пользователь завершает drag.
4. Workspace order обновляется.
5. Новый order синхронно отображается во всех workspace views.
6. Выполняется лёгкий haptic feedback.
7. Selection не меняется.

## 4. Business Rules

`BR-007-01 | Workspace Page можно reorder через Drag & Drop; основной gesture расположен в Documents. | Source: functional-map §12`

`BR-007-02 | Во время drag видны moved Page и insertion position; завершение сопровождается лёгким haptic feedback. | Source: functional-map §12`

`BR-007-03 | Order синхронно отражается во всех workspace views. | Source: functional-map §12`

`BR-007-04 | Reorder не запускает OCR повторно. | Source: functional-map §12`

`BR-007-05 | Reorder не меняет selection. | Source: functional-map §§11, 24`

`BR-007-06 | Итоговый workspace PDF содержит Selected Page в их current relative workspace order. | Source: functional-map §12`

## 5. Состояния и переходы

`Order A → Drag & Drop → Order B`

## 6. Edge cases и ошибки

Reorder Unselected Page изменяет order, но не делает Page Selected.

## 7. Acceptance Criteria

`AC-007-01 | После completed Drag & Drop Page занимает выбранную новую позицию. | проверяет: BR-007-01`

`AC-007-02 | Новый order одинаков во всех workspace views. | проверяет: BR-007-03`

`AC-007-03 | Reorder не запускает OCR и не изменяет selection. | проверяет: BR-007-04, BR-007-05`

`AC-007-04 | PDF Save использует current relative workspace order Selected Page. | проверяет: BR-007-06`

## 8. Open Questions

`DR-10` — является ли reorder через upper thumbnail-strip обязательным v1 behavior или только допустимой будущей/контекстной реализацией.

---

# CAP-008 — Удаление Page, Clear и Undo

## 1. Capability

**Цель:** удалить ненужные unsaved Page либо очистить workspace с предусмотренным Undo.

## 2. Входы / выходы

**Вход:** swipe Delete, Toolbar Delete, Clear, Undo.

**Выход:** Page удалены либо восстановлены; saved outputs не изменены.

## 3. Основной сценарий

### Direct Delete
1. Swipe конкретной карточки → Delete.
2. Page удаляется.
3. Доступен короткий Undo.

### Batch Delete
1. Explicit selection > 0.
2. Toolbar Delete.
3. Selected Page удаляются.
4. Доступен Undo.

### Clear
1. Пользователь выбирает Clear.
2. Требуется confirmation.
3. Все unsaved Page текущего workspace удаляются.
4. Доступен короткий Undo.

## 4. Business Rules

`BR-008-01 | Swipe Delete удаляет конкретную Page. | Source: functional-map §13`

`BR-008-02 | Toolbar Delete является batch-operation и disabled при explicit selection=0; Active Page не является implicit target. | Source: functional-map §13`

`BR-008-03 | Undo workspace Delete восстанавливает Page, position, selection, OCR и metadata. | Source: functional-map §13`

`BR-008-04 | Clear после confirmation удаляет все unsaved Page current workspace и предоставляет короткий Undo. | Source: functional-map §13`

`BR-008-05 | Clear disabled во время active PDF Save. | Source: functional-map §13`

`BR-008-06 | Clear разрешён во время обычного Processing; Page/session помечаются deleted, jobs отменяются, late callbacks игнорируются, затем удаляются temp-data. | Source: functional-map §13`

`BR-008-07 | Workspace Delete/Clear не изменяют saved PDF/JPG и recent saved metadata. | Source: functional-map §13`

`BR-008-08 | Unsaved Page не должны silently теряться вне retention либо explicit confirmed deletion. | Source: functional-map §24`

## 5. Состояния и переходы

`Present → Deleted → Undo → Restored`

`Workspace populated → Confirmed Clear → Cleared → Undo → Restored`

## 6. Edge cases и ошибки

Toolbar Delete с selection=0 ничего не удаляет.

Late callback удалённой Page не может вернуть Page либо изменить её state.

## 7. Acceptance Criteria

`AC-008-01 | Swipe Delete удаляет именно target Page. | проверяет: BR-008-01`

`AC-008-02 | Toolbar Delete disabled при explicit selection=0. | проверяет: BR-008-02`

`AC-008-03 | Undo direct/batch Delete восстанавливает position, selection, OCR и metadata удалённой Page. | проверяет: BR-008-03`

`AC-008-04 | Clear не выполняется без confirmation. | проверяет: BR-008-04`

`AC-008-05 | Clear disabled во время active PDF Save. | проверяет: BR-008-05`

`AC-008-06 | Delete/Clear не изменяют saved PDF/JPG/recent metadata. | проверяет: BR-008-07`

## 8. Open Questions

`DR-11` — точная duration «короткого Undo».

`DR-12` — Active Page после удаления текущей Active Page.

---

# CAP-009 — Сохранение workspace как PDF + JPG

## 1. Capability

**Цель:** сохранить Selected Page как полный PDF+JPG output set без partial commit и hidden data loss.

## 2. Входы / выходы

**Вход:** Save при selection ≥ 1, workspace order, current image versions, OCR state/fallback decision, destination/name из CAP-010.

**Success output:** один PDF + соответствующий комплект JPG.

**Failure output:** workspace остаётся неизменным; предусмотрен Retry.

## 3. Основной сценарий

1. Save enabled при selection ≥ 1.
2. Пользователь нажимает Save.
3. Save button блокируется до завершения.
4. Processing Selected Page ожидаются с user-visible preparation status.
5. Low quality / Failed / no-valid-OCR Page требуют correction/Re-OCR либо explicit image-only fallback.
6. Files сначала создаются во временной области/именах.
7. После успешной записи всего set публикуются final names.
8. Только после successful commit из workspace удаляются Page, вошедшие в saved output.
9. При failure temporary artifacts удаляются best-effort; workspace не меняется; Retry доступен.

## 4. Business Rules

`BR-009-01 | Save active при selection минимум одной Page. | Source: functional-map §14`

`BR-009-02 | Одна Selected Page → one-page PDF; несколько → один multipage PDF. | Source: functional-map §14`

`BR-009-03 | Selected Processing Page заставляет Save ждать завершения и показывать preparation status. | Source: functional-map §14`

`BR-009-04 | Selected Low quality/Failed/no-valid-OCR Page требуют correction/Re-OCR либо explicit image-only fallback. | Source: functional-map §14`

`BR-009-05 | Searchable PDF не может использовать OCR другой imageVersionId. | Source: functional-map §§9, 24`

`BR-009-06 | Повторный Save tap во время операции не создаёт duplicate output. | Source: functional-map §14`

`BR-009-07 | PDF + corresponding JPG являются одной user transaction. | Source: functional-map §14`

`BR-009-08 | Workspace удаляет только успешно сохранённые Page и только после commit полного output set. | Source: functional-map §§14, 24`

`BR-009-09 | До commit используются temporary names/area; при failure workspace остаётся неизменным и предоставляется Retry. | Source: functional-map §14`

`BR-009-10 | JPG берётся из current image version Page. | Source: functional-map §15`

`BR-009-11 | JPG numbering соответствует final PDF page order. | Source: functional-map §15`

`BR-009-12 | Участвующая в PDF transaction Page имеет user-visible state Saving. | Source: functional-map §7`

`BR-009-13 | Перед тяжёлым Save проверяется свободное место. | Source: functional-map §15`

`BR-009-14 | JPEG compression должна сохранять заявленный баланс читаемости, размера и скорости. | Source: functional-map §9`

## 5. Состояния и переходы

`Save disabled(selection=0) → enabled(selection≥1)`

`Save requested → Preparing при необходимости → Saving → committed`

или

`Saving → recoverable failure → workspace unchanged → Retry`

## 6. Edge cases и ошибки

Double tap не создаёт duplicate set.

Failure после partial temporary write не удаляет workspace Page.

Unselected Page не удаляются только потому, что другой selection был успешно сохранён.

## 7. Acceptance Criteria

`AC-009-01 | selection=0 не позволяет запустить workspace Save. | проверяет: BR-009-01`

`AC-009-02 | 1 Selected Page создаёт 1-page PDF и 1 JPG; N Selected Page создают 1 N-page PDF и N JPG. | проверяет: BR-009-02, BR-009-07`

`AC-009-03 | При Selected Processing Page final output не публикуется до завершения применимой подготовки. | проверяет: BR-009-03`

`AC-009-04 | Повторный tap активного Save не создаёт второй output set. | проверяет: BR-009-06`

`AC-009-05 | При failure до commit ни одна Page saved selection не удаляется из workspace. | проверяет: BR-009-08, BR-009-09`

`AC-009-06 | После success удаляются Page saved selection; Unselected Page сохраняются. | проверяет: BR-009-08`

`AC-009-07 | JPG order соответствует final PDF order. | проверяет: BR-009-11`

`AC-009-08 | Output не маркируется searchable, если включённая Page не имеет valid OCR current version и использован image-only fallback. | проверяет: BR-009-04, BR-009-05`

## 8. Open Questions

`DR-03` — UX при недостатке свободного места.

`DR-13` — granularity image-only fallback и semantics mixed OCR/image-only PDF.

`DR-14` — quantitative acceptance target для JPEG compression/readability/size/speed balance.

---

# CAP-010 — Именование и папка назначения

## 1. Capability

**Цель:** определить доступную destination и непротиворечивые filenames без overwrite.

## 2. Входы / выходы

**Вход:** Save, требующий destination и basename.

**Выход:** доступная destination + resolved basename + final PDF/JPG names.

## 3. Основной сценарий

1. Проверяется existing destination access.
2. Если доступа нет/он утрачен — пользователь выбирает destination через SAF.
3. Persistable URI permission сохраняется.
4. Automatic basename формируется по смыслу и дате.
5. При невозможности применяется `Scan <date time>`.
6. Name collision разрешается suffix `(1)`, `(2)…`.
7. PDF/JPG получают связанные names одного resolved basename.

## 4. Business Rules

`BR-010-01 | Destination выбирается через Storage Access Framework; permission сохраняется persistently. | Source: functional-map §15`

`BR-010-02 | При утрате доступа destination запрашивается повторно вместо crash. | Source: functional-map §15`

`BR-010-03 | Имя автоматически формируется по смыслу и дате; fallback — Scan <date time>. | Source: functional-map §15`

`BR-010-04 | Existing files не overwrite; при collision применяется suffix (1), (2)…; связанные JPG используют тот же resolved basename. | Source: functional-map §15`

`BR-010-05 | Single-page: <name>.pdf + <name>.jpg. | Source: functional-map §15`

`BR-010-06 | Multi-page: <name>.pdf + <name> — 01.jpg, 02.jpg… | Source: functional-map §15`

`BR-010-07 | JPG numbering соответствует final PDF order, а не source indexes. | Source: functional-map §15`

`BR-010-08 | Перед тяжёлым Save проверяется свободное место. | Source: functional-map §15`

## 5. Состояния и переходы

`destination unavailable → SAF → destination available`

`basename candidate → collision → suffix-resolved basename`

## 6. Edge cases и ошибки

Потеря destination permission не вызывает crash.

Existing output не overwrite.

## 7. Acceptance Criteria

`AC-010-01 | При утрате saved permission приложение требует новый destination вместо продолжения записи в недоступное место. | проверяет: BR-010-02`

`AC-010-02 | Existing target name не overwrite; выбирается свободный suffix. | проверяет: BR-010-04`

`AC-010-03 | Single-page и multi-page outputs соответствуют установленным naming patterns. | проверяет: BR-010-05, BR-010-06`

`AC-010-04 | JPG numbering совпадает с order final PDF. | проверяет: BR-010-07`

## 8. Open Questions

`DR-15` — deterministic specification semantic auto-naming и exact `<date time>` format.

`DR-03` — UX при недостатке свободного места.

---

# CAP-011 — Workspace Viewer и навигация по Page

## 1. Capability

**Цель:** просматривать workspace Page и изменять Active Page независимо от selection.

## 2. Входы / выходы

**Вход:** tap thumbnail или horizontal swipe current Page.

**Выход:** соответствующая Page становится Active.

## 3. Основной сценарий

1. Tap thumbnail открывает Viewer и делает Page Active.
2. Active thumbnail получает white frame.
3. Horizontal swipe изменяет Active Page.
4. Thumbnail-strip синхронизируется.
5. Selected-state остаётся отдельным.
6. Viewer без protected dirty state закрывается Android Back/Cancel либо tap вне окна.

## 4. Business Rules

`BR-011-01 | Tap thumbnail всегда делает Page Active. | Source: functional-map §11`

`BR-011-02 | White thumbnail frame означает Active; checkbox означает Selected. | Source: functional-map §§11, 17`

`BR-011-03 | Horizontal swipe main Page переключает Active и synchronizes thumbnail-strip. | Source: functional-map §17`

`BR-011-04 | Workspace Viewer и main screen используют общий selection state. | Source: functional-map §18`

`BR-011-05 | Viewer доступен с Delete, Re-OCR и PDF Save по правилам соответствующих capability. | Source: functional-map §18`

`BR-011-06 | Viewer без protected dirty state закрывается Android Back/Cancel либо tap по empty area вне окна. | Source: functional-map §17`

`BR-011-07 | Dirty graphical state использует protection flow CAP-012, а не silently closes/discards. | Source: functional-map §§17, 26`

## 5. Состояния и переходы

`Viewer closed → tap thumbnail → Viewer open / Page Active`

`Active A ↔ Active B`

## 6. Edge cases и ошибки

Navigation не является selection operation.

Dirty Page navigation/closing делегируется CAP-012.

## 7. Acceptance Criteria

`AC-011-01 | Tap thumbnail делает target Page Active без изменения Selected только вследствие tap. | проверяет: BR-011-01, BR-011-02`

`AC-011-02 | Horizontal swipe меняет Active Page и synchronizes strip. | проверяет: BR-011-03`

`AC-011-03 | Main screen и Viewer отражают один Selected-state. | проверяет: BR-011-04`

`AC-011-04 | Без dirty protection Viewer закрывается каждым из предусмотренных способов. | проверяет: BR-011-06`

## 8. Open Questions

`DR-16` — boundary behaviour horizontal swipe на первой/последней workspace Page.

---

# CAP-012 — Графическое редактирование Page

## 1. Capability

**Цель:** вручную изменить изображение Page и сохранить новую current image version той же Page.

## 2. Входы / выходы

**Вход:** Rotate, Crop, Brightness, Contrast, Sharpness, Undo, graphical Save.

**Выход:** dirty changes отменены либо сохранена новая image version той же Page.

## 3. Основной сценарий

1. Пользователь выполняет graphical change.
2. Preview изменяется сразу.
3. Editing state становится dirty; Save enabled.
4. Undo отменяет changes multi-step в reverse order.
5. Undo всех dirty changes возвращает clean state; Save disabled.
6. Save создаёт новую internal image version той же Page и делает её current.
7. Page ID, order и selection не меняются.
8. Undo stack очищается.
9. Новая image version не имеет valid OCR до explicit Re-OCR.

## 4. Business Rules

`BR-012-01 | Доступны Rotate 90°, four-point Crop, Brightness, Contrast, Sharpness; Resize отсутствует. | Source: functional-map §§6, 26`

`BR-012-02 | Changes сразу видны в preview, но до Save существуют только как dirty editing state. | Source: functional-map §26`

`BR-012-03 | Graphical Save создаёт новую internal image version той же Page, не новую Page. | Source: functional-map §§23, 26`

`BR-012-04 | Page ID, workspace position и selection не меняются. | Source: functional-map §§23, 26`

`BR-012-05 | Новая current version не имеет valid OCR до explicit Re-OCR; previous OCR остаётся связан со старой version. | Source: functional-map §26`

`BR-012-06 | Save disabled в clean state, enabled при dirty; после success снова disabled. | Source: functional-map §26`

`BR-012-07 | Undo multi-step действует только до состояния последнего Save; после Save stack очищается. | Source: functional-map §26`

`BR-012-08 | Если Undo отменил все dirty changes, Save снова disabled. | Source: functional-map §26`

`BR-012-09 | Close/Page navigation/Android Back при dirty вызывает только Save changes? → Cancel | Save. | Source: functional-map §26`

`BR-012-10 | Cancel сохраняет dirty edit session и отменяет requested transition; Save commits version и затем выполняет transition. | Source: functional-map §26`

`BR-012-11 | Отдельного Discard нет; intentional cancel changes выполняется Undo. | Source: functional-map §26`

`BR-012-12 | Manual graphical edit сама по себе не запускает OCR. | Source: functional-map §§5, 26`

## 5. Состояния и переходы

`Clean → edit → Dirty`

`Dirty → Undo all → Clean`

`Dirty → Save → Clean/new current version`

`Dirty → navigation attempt → Save changes? → Cancel | Save`

## 6. Edge cases и ошибки

Undo после successful Save не возвращает previous saved version.

Old OCR не считается OCR новой image version.

## 7. Acceptance Criteria

`AC-012-01 | Первый edit делает Save enabled; Undo всех unsaved edits делает его disabled. | проверяет: BR-012-06, BR-012-08`

`AC-012-02 | Graphical Save изменяет imageVersionId, но не Page ID/order/selection. | проверяет: BR-012-03, BR-012-04`

`AC-012-03 | После Save Undo stack не позволяет вернуться через обычный Undo к previous saved version. | проверяет: BR-012-07`

`AC-012-04 | Новая version не использует old OCR как valid OCR. | проверяет: BR-012-05`

`AC-012-05 | Dirty navigation/close показывает только Cancel | Save; silent discard отсутствует. | проверяет: BR-012-09, BR-012-11`

## 8. Open Questions

None.

---

# CAP-013 — Recent navigation сохранённых документов

## 1. Capability

**Цель:** открыть recent saved PDF и перемещаться по recent documents и их страницам.

## 2. Входы / выходы

**Вход:** recent PDF preview, vertical/horizontal swipe.

**Выход:** соответствующий recent PDF/Page отображён в Saved PDF Viewer.

## 3. Основной сценарий

1. При non-empty 24h recent index отображается preview последнего recent document.
2. Tap preview открывает этот document.
3. Horizontal swipe меняет Page текущего PDF.
4. Vertical swipe меняет recent document.
5. При смене document thumbnail-strip полностью обновляется.

## 4. Business Rules

`BR-013-01 | Recent index содержит успешно сохранённые приложением documents последних 24 часов. | Source: functional-map §21`

`BR-013-02 | Recent index — navigation mechanism, не permanent library/document manager. | Source: functional-map §§19, 21`

`BR-013-03 | Preview представляет последний recent document. | Source: functional-map §19`

`BR-013-04 | App не ищет более старые PDF по filesystem при empty recent index. | Source: functional-map §19`

`BR-013-05 | Tap preview открывает Saved PDF Viewer на последнем recent document. | Source: functional-map §19`

`BR-013-06 | Vertical swipe меняет recent document; horizontal swipe — Page current PDF. | Source: functional-map §19`

`BR-013-07 | При смене document thumbnail-strip полностью заменяется страницами нового document. | Source: functional-map §19`

`BR-013-08 | Recent index содержит URI/metadata, достаточные для открытия и subsequent Try again materialization. | Source: functional-map §21`

## 5. Состояния и переходы

`recent index non-empty → preview → Viewer`

`Document A ↔ Document B`

`Page N ↔ Page N±1`

## 6. Edge cases и ошибки

Empty recent index не инициирует filesystem discovery старых документов.

## 7. Acceptance Criteria

`AC-013-01 | Tap recent preview открывает последний document recent index. | проверяет: BR-013-03, BR-013-05`

`AC-013-02 | Horizontal swipe изменяет Page текущего PDF, vertical swipe изменяет recent document. | проверяет: BR-013-06`

`AC-013-03 | После смены document thumbnail-strip содержит Page нового current PDF. | проверяет: BR-013-07`

`AC-013-04 | Empty recent index не вызывает поиск старых PDF в filesystem. | проверяет: BR-013-04`

## 8. Open Questions

`DR-17` — empty recent UI: preview отсутствует или используется neutral placeholder.

`DR-18` — chronological direction vertical swipe и boundary behaviour на крайних document/Page.

---

# CAP-014 — Редактирование сохранённого PDF

## 1. Capability

**Цель:** неразрушающе изменить composition/order saved PDF и сохранить новый PDF.

## 2. Входы / выходы

**Вход:** saved PDF → temporary EditorSession.

**Выход:** original unchanged; либо создан новый PDF из current remaining EditorSession pages.

## 3. Основной сценарий

1. Saved PDF открывается как EditorSession.
2. Initial order совпадает с source PDF.
3. Tap thumbnail меняет Active.
4. Checkbox формирует explicit editor selection.
5. Delete удаляет только explicitly selected pages из EditorSession.
6. Undo может восстановить deleted Page.
7. D&D изменяет temporary EditorSession order.
8. Save создаёт новый PDF из всех remaining Page current EditorSession в current order.
9. Original PDF не overwrite.

Optional `implicit all` marker до первого explicit selection допустим, но не является обязательным UI requirement.

## 4. Business Rules

`BR-014-01 | Saved PDF Editor использует temporary EditorSession; original saved PDF не изменяется действиями editor до/после Save. | Source: functional-map §§20, 23, 24`

`BR-014-02 | Initial EditorSession order = original PDF order. | Source: functional-map §20`

`BR-014-03 | Tap thumbnail = Active; explicit selection выполняется checkbox. | Source: functional-map §20`

`BR-014-04 | До explicit selection UI может показывать implicit-all marker; это не explicit selection и не разрешает Delete. | Source: functional-map §20`

`BR-014-05 | Delete применяется только к explicitly selected pages и disabled при selection=0. | Source: functional-map §§20, 28`

`BR-014-06 | Undo Delete восстанавливает Page в previous position и с previous selection. | Source: functional-map §20`

`BR-014-07 | Если deleted Page была Active и пользователь не перешёл на другую Page, Undo восстанавливает Active; иначе current Active не меняется. | Source: functional-map §20`

`BR-014-08 | D&D thumbnails меняет temporary EditorSession order. | Source: functional-map §§12, 20, 28`

`BR-014-09 | Save создаёт новый PDF из всех remaining Page current EditorSession в current order; editor selection не является Save-filter. | Source: functional-map §§20, 28`

`BR-014-10 | Automatic overwrite original PDF запрещён. | Source: functional-map §§24, 28`

`BR-014-11 | Existing usable text layer соответствующего image content переиспользуется без повторного OCR. | Source: functional-map §20`

`BR-014-12 | Editor selection локален current saved PDF и не переносится между recent documents. | Source: functional-map §20`

## 5. Состояния и переходы

`Editor initial → Delete/Reorder → Editor modified`

`Delete → Undo → restored editor state`

`Editor → Save → new PDF`

`Editor close/cancel → original unchanged`

## 6. Edge cases и ошибки

Explicit selection без Delete не ограничивает Save output.

Original PDF остаётся неизменным после Delete, reorder и Save.

## 7. Acceptance Criteria

`AC-014-01 | selection=0 делает Editor Delete disabled. | проверяет: BR-014-05`

`AC-014-02 | Delete изменяет EditorSession, но не source PDF. | проверяет: BR-014-01, BR-014-05`

`AC-014-03 | Undo Delete восстанавливает previous position и selection. | проверяет: BR-014-06`

`AC-014-04 | Reorder изменяет EditorSession order, не source PDF. | проверяет: BR-014-08`

`AC-014-05 | Save output содержит все remaining Page current EditorSession независимо от explicit selection. | проверяет: BR-014-09`

`AC-014-06 | Save никогда автоматически не overwrite source PDF. | проверяет: BR-014-10`

## 8. Open Questions

`DR-19` — destination/naming rules нового PDF, созданного Editor Save.

`DR-20` — Save availability/behavior при пустой EditorSession.

`DR-21` — user-visible close/back behaviour при unsaved Delete/Reorder changes EditorSession.

---

# CAP-015 — «Попробовать ещё раз» из saved PDF

## 1. Capability

**Цель:** materialize selected/all pages saved PDF обратно в workspace для новой работы.

## 2. Входы / выходы

**Вход:** Try again current EditorSession.

**Выход:** новые workspace Page.

## 3. Основной сценарий

1. При explicit editor selection target = selected remaining Page.
2. При отсутствии explicit selection target = все remaining Page EditorSession.
3. Пользователь запускает Try again.
4. Action disabled на время materialization.
5. Target pages materialize в current EditorSession order.
6. Каждая получает новый workspace Page ID и provenance.
7. Usable source text layer переносится как OCR/text current imported version.
8. Returned Page имеют Selected=true.
9. Existing workspace: block добавляется в конец.
10. Workspace absent: создаётся новая WorkspaceSession.
11. Returned Page наследуют current session deadline либо получают общий new-session TTL.
12. После добавления действуют обычные workspace rules.
13. Source saved PDF не изменяется.

## 4. Business Rules

`BR-015-01 | При explicit selection materialize только selected remaining Page; без explicit selection — все remaining Page EditorSession. | Source: functional-map §20`

`BR-015-02 | Materialization использует current EditorSession order. | Source: functional-map §29`

`BR-015-03 | Каждая returned Page получает новый workspace Page ID и source provenance. | Source: functional-map §29`

`BR-015-04 | Editor selection не переносится как самостоятельное state; returned Page создаются Selected=true. | Source: functional-map §29`

`BR-015-05 | Usable source text layer переносится как OCR/text текущей imported image version до manual image change. | Source: functional-map §29`

`BR-015-06 | Repeated Try again после завершения предыдущего разрешён и создаёт новые Page; automatic dedup отсутствует. | Source: functional-map §29`

`BR-015-07 | Action disabled на время materialization для защиты от accidental double tap. | Source: functional-map §29`

`BR-015-08 | Existing workspace получает returned Page одним последовательным block в конце; при отсутствии workspace создаётся новая WorkspaceSession. | Source: functional-map §29`

`BR-015-09 | Existing session → returned Page наследуют её retention deadline; new session → весь block получает общий новый session TTL. | Source: functional-map §§16, 29`

`BR-015-10 | Returned Page далее подчиняются обычным workspace selection/edit/Re-OCR/reorder/delete/clear/save rules. | Source: functional-map §29`

`BR-015-11 | Try again не изменяет original saved PDF и не является Re-OCR. | Source: functional-map §27`

## 5. Состояния и переходы

`Try again enabled → materialization running/action disabled → block added/action available`

## 6. Edge cases и ошибки

Intentional repeated Try again after previous completion создаёт новые Page.

Reordered EditorSession возвращается именно в current editor order.

## 7. Acceptance Criteria

`AC-015-01 | Explicit selection materialize только selected remaining Page; no explicit selection materialize все remaining Page. | проверяет: BR-015-01`

`AC-015-02 | Returned Page получают новые Page IDs и Selected=true. | проверяет: BR-015-03, BR-015-04`

`AC-015-03 | Returned block появляется в current EditorSession order и в конце existing workspace. | проверяет: BR-015-02, BR-015-08`

`AC-015-04 | Double tap во время active materialization не запускает вторую concurrent materialization. | проверяет: BR-015-07`

`AC-015-05 | Repeated Try again после завершения создаёт новые Page, а не deduplicate предыдущие. | проверяет: BR-015-06`

`AC-015-06 | Original saved PDF остаётся неизменным. | проверяет: BR-015-11`

## 8. Open Questions

`DR-22` — semantics materialization failure: atomic all-or-nothing либо допустим partial result и какой recovery видит пользователь.

---

# CAP-016 — Retention и восстановление WorkspaceSession

## 1. Capability

**Цель:** сохранить unsaved workspace в пределах 24-hour inactivity retention и восстановить его после restart/process death.

## 2. Входы / выходы

**Вход:** WorkspaceSession с unsaved Page; activity/lifecycle/process-death events.

**Выход:** session остаётся доступной/восстанавливается либо становится unavailable после expiry; saved files не затрагиваются.

## 3. Основной сценарий

1. Unsaved Page принадлежат WorkspaceSession.
2. Session имеет общий inactivity-based deadline.
3. Существенная activity обновляет session activity timestamp.
4. Restart/process death неистёкшей session запускает recovery.
5. Восстанавливаются Page и зафиксированный workspace state.
6. Unfinished jobs восстанавливаются/restart либо переводятся в recoverable failure.
7. Expired session может быть очищена только с соблюдением protection pending/running jobs.
8. Saved PDF/JPG cleanup не затрагивает.

## 4. Business Rules

`BR-016-01 | WorkspaceSession expires через 24 часа inactivity, а не по independent Page createdAt. | Source: functional-map §16`

`BR-016-02 | Все Page одной WorkspaceSession разделяют общий retention deadline. | Source: functional-map §16`

`BR-016-03 | Более поздняя Page не получает independent TTL, позволяющий ей пережить session. | Source: functional-map §16`

`BR-016-04 | Существенное user/system изменение active session обновляет activity timestamp. | Source: functional-map §16`

`BR-016-05 | Пока действуют определённые protection conditions active/edit/selection/pending-running job, retention cleanup не удаляет соответствующие Page. | Source: functional-map §16`

`BR-016-06 | Pending/running job должна быть безопасно отменена/завершена до удаления temp data. | Source: functional-map §16`

`BR-016-07 | Try-again returned Page наследуют current session deadline; новая session получает один общий new TTL. | Source: functional-map §16`

`BR-016-08 | После restart/process death восстанавливаются Page, order, selection, source type, image version, OCR/quality status, timestamps и unfinished jobs. | Source: functional-map §16`

`BR-016-09 | Saved PDF/JPG retention cleanup не затрагивает. | Source: functional-map §§16, 24`

`BR-016-10 | Save/Clear/crash не должны silently уничтожать unsaved Page до retention либо explicit confirmed deletion. | Source: functional-map §24`

`BR-016-11 | После recovery fake endless Processing запрещён: incomplete job восстанавливается, restart либо получает recoverable failure. | Source: functional-map §7`

## 5. Состояния и переходы

User-significant lifecycle:

`Session available → restart/process death → Session restored`

`Session available → retention expiry → Session expired/unavailable`

Отдельные `Persisted`, `Cleaned` и аналогичные внутренние states не вводятся как product states.

## 6. Edge cases и ошибки

Later-created Page не переживает остальных только из-за более позднего creation time.

Pending/running job не должна быть уничтожена cleanup без safe job handling.

Saved PDF/JPG находятся вне workspace retention.

## 7. Acceptance Criteria

`AC-016-01 | Все Page одной WorkspaceSession имеют общий session retention deadline, а не independent Page TTL. | проверяет: BR-016-01, BR-016-02`

`AC-016-02 | После restart/process death неистёкшая session восстанавливает Page order, selection, image version и OCR/quality state. | проверяет: BR-016-08`

`AC-016-03 | Page с pending/running job не удаляется retention cleanup до предусмотренного safe job handling. | проверяет: BR-016-05, BR-016-06`

`AC-016-04 | Try-again Page в existing session используют её deadline; new WorkspaceSession даёт всему returned block единый новый TTL. | проверяет: BR-016-07`

`AC-016-05 | Retention cleanup не удаляет saved PDF/JPG. | проверяет: BR-016-09`

`AC-016-06 | Recovery не оставляет unfinished Page навсегда в фиктивном Processing. | проверяет: BR-016-11`

## 8. Open Questions

`DR-23` — exact exhaustive set событий, которые считаются `substantial activity` и изменяют session retention timestamp.

---

# Decision Register

Ниже перечислены **только реальные незакрытые решения**. До их закрытия они не являются Business Rules.

| ID | Вопрос | Class | Status | Affects |
|---|---|---|---|---|
| DR-01 | Куда в workspace order вставляется новая camera Page? | D | NEEDS PREFERENCE | CAP-001, CAP-007 |
| DR-02 | Куда и в каком порядке вставляется imported block относительно existing workspace Page? | D | NEEDS PREFERENCE | CAP-002, CAP-007 |
| DR-03 | Какое user-visible поведение при insufficient free space перед heavy import/Save? | D | NEEDS PREFERENCE | CAP-002, CAP-009, CAP-010 |
| DR-04 | По какому критерию existing PDF text layer считается usable? | C | NEEDS FACT | CAP-002, CAP-005, CAP-014, CAP-015 |
| DR-05 | Как определяются thresholds/decision rule Ready vs Low quality? | C | NEEDS FACT | CAP-003 |
| DR-06 | Меняется ли quality status автоматически после сохранённого manual Rotate/Crop? | D | NEEDS PREFERENCE | CAP-003, CAP-012 |
| DR-07 | Доступен ли Retake вне Low quality и, если да, в каких состояниях? | D | NEEDS PREFERENCE | CAP-004 |
| DR-08 | Что именно считается final OCR failure, после которого предлагается image-only fallback? | D | NEEDS PREFERENCE | CAP-005, CAP-009 |
| DR-09 | Какие Page включает Select all: Processing / Ready / Low quality / Failed / previous non-expired sessions? | D | NEEDS PREFERENCE | CAP-006 |
| DR-10 | Должен ли workspace Viewer/upper thumbnail-strip обязательно поддерживать reorder в v1? | D | NEEDS PREFERENCE | CAP-007 |
| DR-11 | Какова точная duration «короткого Undo»? | D | NEEDS PREFERENCE | CAP-008 |
| DR-12 | Какая Page становится Active после удаления current Active Page? | D | NEEDS PREFERENCE | CAP-008, CAP-011 |
| DR-13 | Image-only fallback выбирается per-Page или для всей Save operation; как маркируется mixed OCR/image-only PDF? | D | NEEDS PREFERENCE | CAP-009 |
| DR-14 | Какие количественные acceptance limits определяют допустимый JPEG readability/size/speed balance? | C | NEEDS FACT | CAP-009 |
| DR-15 | Как формируется semantic auto-name и каков exact `<date time>` format fallback? | D | NEEDS PREFERENCE | CAP-010 |
| DR-16 | Что происходит при horizontal swipe за первой/последней workspace Page? | D | NEEDS PREFERENCE | CAP-011 |
| DR-17 | Empty recent index: preview отсутствует или отображается neutral placeholder? | D | NEEDS PREFERENCE | CAP-013 |
| DR-18 | Какое направление vertical swipe соответствует older/newer recent document и что происходит на границах списков? | D | NEEDS PREFERENCE | CAP-013 |
| DR-19 | По каким destination/naming rules сохраняется новый PDF Saved PDF Editor? | D | NEEDS PREFERENCE | CAP-014 |
| DR-20 | Что происходит при Save EditorSession, в которой не осталось Page? | D | NEEDS PREFERENCE | CAP-014 |
| DR-21 | Как пользователь закрывает EditorSession с unsaved Delete/Reorder changes? | D | NEEDS PREFERENCE | CAP-014 |
| DR-22 | Try-again materialization failure: all-or-nothing или partial; какой user recovery предоставляется? | D | NEEDS PREFERENCE | CAP-015 |
| DR-23 | Полный перечень activity events, обновляющих inactivity retention deadline. | E | NEEDS AUTHORITY | CAP-016 |

---

# Decision Summary

| Status | Count |
|---|---:|
| Logically determined / new PD-A | 0 |
| Delegated decisions / new PD-B | 0 |
| Needs fact | 3 |
| Needs preference | 19 |
| Needs authority | 1 |
| Architectural open questions in User Functional Specification | 0 |
| Unresolved source conflicts | 0 |

---

# Freeze Gate

## G1 — Hidden decisions

**PASS**

В нормативной части отсутствуют product rules без Functional Map source либо explicit decision.

## G2 — Unauthorized product choices

**PASS**

Ни один D/E gap не закрыт моделью самостоятельно.

## G3 — Architecture leakage

**PASS**

Внутренние worker/database/concurrency/API choices не превращены в Business Rules.

## G4 — Unresolved conflicts

**PASS**

Внутренних противоречий между действующими нормативными правилами не выявлено.

## G5 — Traceability

**PASS**

Каждый Business Rule имеет Functional Map source; каждый AC связан с конкретным BR/scenario/state transition.

## G6 — Scope creep

**PASS**

Новые capability, actions, user-visible states и mandatory UI elements без source не добавлены.

Optional behaviour карты сохранено как optional, а не превращено в обязательное.

## G7 — Assumptions

**PASS**

Assumption не используется как requirement.

---

# Final status

**Нормативная часть:** `READY FOR FREEZE`

**Документ в целом:** `NOT YET FROZEN`

Причина: открыты `DR-01…DR-23`, из которых 19 требуют Product Preference, 3 — внешнего/эмпирического основания, 1 затрагивает retention authority.

Статус `USER FUNCTIONAL SPECIFICATION — FROZEN v1` допустим только после:

1. закрытия либо явного исключения из v1 каждого `DR-*`;
2. оформления принятых A/B решений через `PD-*`, если такие появятся;
3. внесения owner decisions D/E в соответствующие BR/AC;
4. повторного Gate Check с:
   - Hidden decisions = NONE;
   - Unauthorized choices = NONE;
   - Architecture leakage = NONE;
   - Unresolved conflicts = NONE;
   - Traceability = PASS;
   - Scope creep = NONE;
   - Assumptions = NONE.