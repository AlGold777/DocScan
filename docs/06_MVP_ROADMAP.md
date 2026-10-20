# DocScan — путь от Spike Harness до MVP приложения

**Версия:** 1.2
**Статус:** рабочий план реализации
**Назначение:** довести текущий D1/S1 skeleton через проверку архитектуры до пользовательского MVP DocScan. Редакция 1.1 исправляет неполный scope версии 1.0.

## 1. Что считается MVP

Конечный MVP — работающее приложение DocScan с принятым продуктовым scope и пройденной приёмкой. Полный набор CAP-001…CAP-016 служит исходным кандидатом scope; исключать функции или выбирать сокращённый MVP без решения владельца нельзя. Промежуточный результат H — executable harness:

1. один воспроизводимый host-orchestrated runner;
2. два строго разделённых режима `DEV` и `QUALIFICATION`;
3. полный набор hooks, fixtures, diagnostics и evaluators для S1–S6;
4. полный DEV-pass на `emulator-5554` с честными `PASS/FAIL/INCOMPLETE`;
5. qualification-ready build и manifest template;
6. запрет на смешивание emulator evidence с physical evidence.

После H следуют physical qualification Q, отдельный reviewed Post-Spikes переход A и реализация продукта P. Наличие дорожной карты не отменяет STOP из frozen protocol: текущий исполнитель harness заканчивает на SPIKE_RESULTS; продуктовые этапы активируются после выполнения gate и отдельной передачи работы. H нельзя объявлять готовым MVP приложения.

## 2. Нормативные источники и границы

Полномочия определяет `01-decision-policy.md`: explicit owner decision → approved product specification → approved constraints → строго выведенные следствия → architecture → implementation. Предыдущая редакция ошибочно ставила архитектуру выше продукта.

Читать Functional Map, UFS, registry, decision policy, frozen baseline/protocol, execution docs 03–05 и `platform-constraints-audit.txt`. Registry ссылается на отсутствующий `architecture-brief.md`: запросить его при наличии; отсутствие не блокирует независимую разработку harness. Platform audit — перечень гипотез от моделей, а не подтверждённые platform constraints. Gravity проверяет применимые пункты по первичным источникам/экспериментам и сохраняет источник, дату, API/SDK version, результат и влияние на CAP.

Файлы 01 и 02 frozen и immutable в текущем qualification cycle. UFS имеет статус `NOT YET FROZEN`. Конфликт продукта и frozen baseline нельзя разрешать молчаливым выбором одного: фиксировать конфликт, authority и явное supersession; при необходимости новая versioned baseline, без правки frozen in place. Перечень конфликтов и недостающих входов — `08_MVP_GATES_AND_ACCEPTANCE.md`.

## 3. Промежуточное состояние H — harness

| Область | MVP-результат | Gate |
|---|---|---|
| Build | clean app/test APK build с фиксируемыми hash | B0 |
| Runner | explicit serial, build/install/run/pull/validate/aggregate | B1 |
| Modes | `DEV` и `QUALIFICATION` записываются в каждый результат | B1 |
| S1 | D1–D8, DEV 24 runs, qualification count 800 immutable | B2 |
| S2 | C1–C7, W0–W2, reduced DEV, qualification 6,000 commands immutable | B3 |
| S3 | OCR mechanics/instrumentation; undefined envelope → `INCOMPLETE` | B4 |
| S4 | PDF mechanics/validators; undefined P_MAX → `INCOMPLETE` | B5 |
| S5 | export state machine; missing provider inventory → `INCOMPLETE` | B6 |
| S6 | R01–R41, DEV 123 runs, qualification count 4,100 immutable | B7 |
| Evidence | per-run schema, raw logs, dumps, aggregates, SHA256 manifest | B8 |
| Qualification readiness | release-equivalent build, tuple manifest, no emulator substitution | B9 |

## 4. Этапы реализации

### Phase 0 — Baseline and control plane

Сохранить baseline, проверить current package/runner/SDK/AGP/JDK, добавить host runner skeleton, versioned configuration и JSON schema validation.

Exit criteria:

- build/install/test выполняются explicit serial;
- runner fails closed при пустом/неоднозначном serial;
- есть `execution_mode`, `run_id`, `case_id`, `operation_id`;
- JSON с неизвестным outcome не может стать `PASS`.

### Phase 1 — Shared harness

Сделать reusable:

- `RunIdentity`;
- `ExecutionConfig`;
- device/app identity collector;
- evidence writer;
- read-only diagnostic snapshot;
- hook rendezvous protocol;
- process-death/restart protocol;
- host pull/validation/aggregate pipeline.

Критическая архитектурная проверка: mandatory app-process death должна завершать именно application process, а instrumentation не должна быть единственным носителем состояния run. Допустим host-side state machine и отдельный recovery invocation.

### Phase 2 — S1 Durability

Исправить текущий isolated-service подход, реализовать D1–D8 через host orchestration, clean seed per run, exact byte/checksum checks, transactional metadata and rollback oracle.

DEV: 3 × 8 = 24. Qualification: 100 × 8 = 800.

### Phase 3 — S2 Camera burst

Добавить capture command protocol, operation correlation, C1–C7, W0/W1/W2, serial responsiveness metrics and evaluator.

DEV должен быть коротким, но проверять все hooks и все workload types. Qualification count остаётся frozen и не изменяется DEV-настройками.

### Phase 4 — S3 OCR memory

Добавить ML Kit adapter boundary, heavy-job admission, decoded token accounting, PSS/RSS, OCR write-attempt counter and contention fixtures. Product input envelope читается только из explicit config. При его отсутствии aggregate обязан быть `INCOMPLETE`.

### Phase 5 — S4 PDF composition

Добавить immutable SaveManifest, staged PDF, app validator, independent host validator, page order/count/render checks and memory accounting. `P_MAX` и raster/render envelope не придумываются.

### Phase 6 — S5 SAF export

Добавить CommittedExportArtifact, export transaction boundaries, provider observation, E1/E2 process death, UnknownOutcome and read-only revalidation. Реальные qualification tuples берутся только из explicit inventory.

### Phase 7 — S6 recovery matrix

Добавить все R01–R41 exactly by protocol, hook-specific oracle, global recovery oracle, three diagnostic snapshots and idempotency evaluation.

DEV: 3 × 41 = 123. Qualification: 100 × 41 = 4,100.

### Phase 8 — Full DEV gate

Запустить clean build и полный DEV suite, проверить все JSON, aggregates, raw evidence links, mode/device separation and fail-closed behavior.

### Phase 9 — Qualification readiness

Собрать один release-equivalent build, записать APK/test APK/runner hashes, создать qualification manifest template. Различать build-ready и qualification-ready: второе требует реальных входов, tuple и готовности сценариев. Если устройство доступно и входы позволяют, продолжить Q; иначе зафиксировать точный blocker и продолжать независимые задачи.

### Phase Q — Physical qualification и обязательный STOP

Выполнить docs 04–05: одна physical unit, fingerprint, APK hash; immutable cycle directory; exact counts; raw evidence; все top-level файлы package. Недостающие входы отражать INCOMPLETE. S1–S6 PASS/FAIL при полной evidence позволяют отдельный Post-Spikes review; FAIL не означает пригодность архитектуры или готовность продукта. При INCOMPLETE переход блокирован. После package исполнитель harness останавливается.

### Phase A — Reviewed переход к реализации продукта

Отдельный этап после Q: разобрать все FAIL, определить необходимые изменения и повторную qualification, закрыть конфликты источников, утвердить scope и применимые DR, подготовить Implementation Architecture с ownership, schema, adapter contracts и traceability. Изменение сборки не наследует qualification старого APK; объём новой проверки определяется protocol/new approved baseline. До этого продуктовые slices ниже — план, а не разрешение обходить STOP.

### Phase P — Вертикальные этапы приложения

Первый пользовательский P4-срез реализован в APK `0.1.5`: после локального
создания PDF пользователь может выбрать системное место сохранения (`Save as`)
или передать PDF через Android share sheet (`Share`). Это не закрывает весь P4:
JPG-набор, searchable OCR output, collision policy, frozen export snapshot и
partial/unknown recovery остаются обязательными.

| Этап | Зависит от | Результат и обязательная приёмка |
|---|---|---|
| P0 Контракт и фундамент | A | Утверждён scope CAP, решения DR; persistent model, fencing, projection, scheduler, migrations и recovery работают через реальные adapters |
| P1 Камера/workspace | P0 | CAP-001/006/007/011: serial capture, permission/denial, Active отдельно от Selected, единый order, haptics; процесс прерывается без потери committed Page |
| P2 Обработка/OCR/правки | P1 | CAP-003/004/005/012: quality, OCR, Retake сохраняет ID/order/selection, stale result rejected, dirty editor/Undo, graphical Save без автоматического OCR |
| P3 Импорт | P0, P2 | CAP-002: images/PDF, secured source, progressive materialization, usable text reuse, restart без duplicate tuple |
| P4 Save/export | P2, P3 | CAP-009/010: frozen snapshot, searchable PDF + JPG, naming/collision, real SAF, no overwrite, полный output set, ambiguous outcome и explicit recovery |
| P5 Delete/retention | P1, P4 | CAP-008/016: Delete/Clear/Undo, fencing late jobs, protected cleanup, clock discontinuity, startup/recovery и сохранность external files |
| P6 Recent/editor | P3, P4, P5 | CAP-013/014/015: recent index, saved PDF editor, new output, Try again with new IDs/provenance, source unchanged |
| P7 Release acceptance | P1–P6 | Все included AC проходят; accessibility, offline OCR coverage, malformed input, interrupted export, resource/compatibility tests; signed APK и воспроизводимый release manifest |

Детальные задачи и negative acceptance для этих этапов обязательны по `08_MVP_GATES_AND_ACCEPTANCE.md`. Slices должны использовать проверяемые production paths; демонстрационный экран с synthetic state не закрывает CAP. Сроки оценивать после A по фактической скорости/зависимостям, не обещать календарь до устранения блокеров.

## 5. Что блокирует Product MVP

Product MVP не может быть честно закрыт только кодом harness. Перед product implementation нужно решить или явно исключить из v1:

- `DR-01…DR-03`, `DR-06…DR-13`, `DR-15…DR-22` — Product Preference;
- `DR-04`, `DR-05`, `DR-14` — внешний факт/измерение;
- `DR-23` — authority для retention policy;
- `OPEN SOURCE GAP-G01` и `PARAMETER-P01`.

Gravity не должен превращать эти вопросы в silent defaults. Для каждого нужен `PD-*`, `NEEDS FACT`, `NEEDS PREFERENCE` или `NEEDS AUTHORITY`.

## 6. Общие правила качества

- Frozen docs не редактировать.
- Emulator evidence всегда `DEV`.
- Отсутствующий mandatory input даёт `INCOMPLETE`, а не synthetic `PASS`.
- Kill-condition нельзя ослаблять после запуска.
- Любой diagnostic read должен быть read-only.
- Любая операция должна быть traceable: aggregate → run JSON → raw log/dump/artifact.
- Каждый законченный самостоятельный этап — отдельный Git commit.
- После каждой functional change обновить связанный version/documentation record.

## 7. Приёмка промежуточного H

Harness milestone принимается только если выполнены все пункты:

- [ ] B0–B9 закрыты evidence-backed проверками.
- [ ] S1–S6 представлены в DEV; отсутствующие product/qualification inputs классифицированы честно.
- [ ] Ни один DEV result не может попасть в physical package как qualification evidence.
- [ ] Все mandatory hooks существуют и enumerated.
- [ ] Нет hardcoded fake device/build/hash/metric values.
- [ ] Process death/restart проверены host-side, а не только внутренним JUnit test.
- [ ] Qualification build hash зафиксирован.
- [ ] Фактические результаты сохранены в execution ledger; ТЗ не переписано под полученные результаты.
- [ ] После подготовки package Gravity останавливается и не выполняет Post-Spikes.

## 8. Финальная приёмка MVP приложения

- [ ] Q и reviewed A пройдены; все release-blocking findings устранены с evidence.
- [ ] Scope явно утверждён; все CAP имеют INCLUDED или owner-approved DEFERRED с причиной.
- [ ] Все INCLUDED BR/AC связаны с реализацией, тестом и результатом для release build.
- [ ] P0–P7 приняты по условиям документа 08; нет blocked mandatory acceptance.
- [ ] Установочный APK, hash, version, build instructions, known limitations, support tuple и пользовательские сценарии переданы владельцу.
- [ ] Нет заявления о поддержке иных устройств на основании единственного Xiaomi tuple.

---

## 9. Актуальное состояние перехода к нативному продукту (v0.4.243)

* **Репозиторий:** Проект полностью выделен из исследовательского харнесса в независимый рабочий репозиторий: `/Users/restart/Documents/GitHub/DocScan`.
* **Архитектура:** Реализована двухуровневая модель модулей (`:app` и `:core`) со 100% нативным интерфейсом без XML.
* **Достигнутый статус (Релиз 0.4.243):**
  1. *Камера и видоискатель:* серийная съёмка CameraX, потоковая детекция контура листа в реальном времени с отображением зелёного оверлея (`DocumentOverlayView`), автоматическое выравнивание перспективы (OpenCV homography warp) в режиме «Документ».
  2. *Лента страниц:* RecyclerView с асинхронным двухуровневым кэшем эскизов, увеличенный размер эскизов (103×75 dp), Drag & Drop упорядочивание.
  3. *Интегрированный редактор:* Rotate (90°), интерактивный 4-точечный Crop, Adjust, кнопки `ReClick`, `ReOCR`, `🗑️`, динамический контейнер OCR-текста.
  4. *Распознавание и экспорт:* нативный C++ экстрактор `ResultIteratorLayoutExtractor` без утечек памяти, экспорт в двухслойный Searchable PDF через Apache PDFBox, проверенная доставка в общую память `/Docscan` через Android SAF.

