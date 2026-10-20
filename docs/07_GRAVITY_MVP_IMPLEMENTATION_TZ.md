# Gravity — техническое задание: harness, qualification и путь до MVP DocScan

**Версия:** 1.1
**Тип:** implementation instruction
**Вход:** `docs/06_MVP_ROADMAP.md`
**Цель:** выполнить текущую harness/qualification часть и подготовить проверяемую передачу в product implementation. Продуктовые задачи и gates заданы в обязательном приложении `08_MVP_GATES_AND_ACCEPTANCE.md`; их выполнение начинается после reviewed перехода A из roadmap.

## 0. Обязательный режим работы

Работай автономно по циклу:

`inspect → implement → build → install → run → collect → diagnose → fix → rerun → validate → commit`

Не проси пользователя исправлять обычные Gradle/Java/adb/test ошибки. Эскалируй только:

- физическое подтверждение USB debugging;
- выбор реального SAF destination;
- отсутствующий product input, который frozen protocol запрещает придумывать;
- Product Preference/Authority из `DR-*`.

### Запрещено

- менять frozen docs 01 и 02;
- ослаблять kill-condition;
- объявлять emulator evidence qualification;
- придумывать OCR envelope, P_MAX, provider inventory, threshold или pass criteria;
- выполнять POST-SPIKES/production implementation в текущем harness execution до предусмотренного STOP и отдельного reviewed перехода A;
- считать успешный JUnit без raw evidence достаточным доказательством;
- использовать hardcoded device/build/hash/metric values как measured fields.

## 1. Обязательный стартовый checklist

Перед изменением кода создай/обнови рабочий execution log и отметь:

- [ ] прочитан весь working set по docs/00, включая policy, Functional Map, UFS, registry, platform audit и docs 06–08;
- [ ] `git status --short --branch` сохранён;
- [ ] baseline commit/tag существует;
- [ ] определены applicationId, test package, runner, SDK, AGP, Gradle, JDK;
- [ ] выбран explicit serial; `emulator-5554` — default только для DEV;
- [ ] frozen docs имеют SHA-256 и не изменяются;
- [ ] найдено каждое открытое решение, влияющее на текущую работу;
- [ ] пробел классифицирован по policy A–F; разрешённые A/B оформлены PD, D/E переданы владельцу, C проверяются; технические решения в пределах текущего задания принимает исполнитель. Assumptions допустимы только для анализа и не закрывают acceptance.

## 2. Контракт режимов

Реализовать enum:

```text
DEV
QUALIFICATION
```

`ExecutionConfig` обязан содержать:

- mode;
- explicit adb serial;
- spike id;
- repetitions/counts;
- fixture/config revision;
- app/test APK paths and hashes;
- output root;
- `allow_physical_only` guard;
- `hard_power_extension` flag.

Правила:

- DEV counts не могут перезаписывать qualification counts;
- `QUALIFICATION` запрещён на emulator;
- physical evidence запрещено, если device tuple не равен `DUT-X14U-01`;
- mode должен быть в каждом run JSON и aggregate;
- missing input → `INCOMPLETE`;
- неизвестный mode/outcome → runner error, не `PASS`.

## 3. Обязательная evidence-модель

Каждый run должен содержать все поля protocol §9:

```json
{
  "spike_id": "S1-DURABILITY",
  "execution_mode": "DEV",
  "dut_id": "DEV-EMULATOR-5554",
  "run_id": "...",
  "case_id": "...",
  "operation_id": "...",
  "device_build_fingerprint": "...",
  "app_build_hash": "sha256:...",
  "start_monotonic_ns": 0,
  "end_monotonic_ns": 0,
  "injection_hook": "...",
  "measured_metrics": {},
  "pre_state_dump": {},
  "post_state_dump": {},
  "artifact_checks": [],
  "raw_log_location": "...",
  "kill_condition_triggered": null,
  "notes": "..."
}
```

Пример выше — форма, не evidence; нулевые timestamps и `...` запрещены в завершённом run. `null` может быть нормативным значением: например, kill_condition_triggered=null при отсутствии нарушения, hard_power_method=null при OFF, file hash=null для отсутствующего файла. Валидатор проверяет семантику по case; missing mandatory evidence сохраняется с причиной даже при FAIL. `pre_state_dump: {}` без объяснения запрещён.

## 4. Host orchestrator — обязательные операции

Создай host runner в репозитории. Он должен:

- принимать serial явно и печатать его перед каждой операцией;
- строить app и androidTest APK;
- устанавливать APK на выбранный serial;
- reset/seed deterministic state;
- запускать одну case;
- ждать named hook с timeout;
- подтверждать, что hook достигнут именно для ожидаемого run/operation id;
- выполнять abrupt kill именно application process;
- отдельно запускать/restart recovery;
- S1: снимать snapshot до maintenance/recovery mutation; S6: сначала выполнить обязательный startup barrier (в том числе WritingExternal→UnknownOutcome), затем dump #1 до object recovery; далее ровно один recovery pass, dump #2, повтор recovery и dump #3;
- выполнять ровно предписанное recovery;
- снимать dump #2 и #3, где требует S6;
- pull raw evidence/logs/artifacts;
- валидировать JSON schema и references;
- строить aggregates;
- возвращать non-zero exit code при `FAIL`, incomplete mandatory execution или invalid evidence.

Instrumentation не должна быть единственным orchestrator для process-death case. Если instrumentation process может умереть, host state machine должна продолжать run независимо.

## 5. Read-only diagnostics

Diagnostic API обязан извлекать без repair/retry/migrate/delete:

- authoritative metadata;
- ArtifactRecord;
- ArtifactReference;
- file existence, length, SHA-256;
- workflow state;
- HeavyWorkScheduler state;
- provider-call counter;
- StateStore write-TX depth;
- device/build identity.

Любая ошибка чтения должна быть явно записана как diagnostic error и влиять на outcome согласно protocol. Пустой массив после DB exception нельзя трактовать как «объект отсутствует».

## 6. Реализация S1

### Hooks

- [ ] D1_ENCODER_FINISHED
- [ ] D2_USERSPACE_FLUSH_FINISHED
- [ ] D3_FILE_PERSISTENCE_PRIMITIVE_RETURNED
- [ ] D4_FINAL_PATH_PUBLISHED
- [ ] D5_PUBLICATION_BOUNDARY_RETURNED
- [ ] D6_STAGED_RECORD_TX_COMMITTED
- [ ] D7_FIRST_REFERENCE_TX_BEFORE_COMMIT
- [ ] D8_FIRST_REFERENCE_TX_COMMITTED

### Oracle

- [ ] D1–D4: no authoritative reference; survival is not required.
- [ ] D5: exact bytes survive process death; no reference required.
- [ ] D6: exact bytes + `STAGED_DURABLE` survive.
- [ ] D7: transaction is wholly uncommitted or wholly committed; no partial state.
- [ ] D8: bytes + COMMITTED record + reference + owner row survive together.
- [ ] no duplicate artifact identity/publication.

### Counts

- [ ] DEV = 3 per barrier = 24.
- [ ] QUALIFICATION = 100 per barrier = 800.
- [ ] hard-power optional and OFF by default.
- [ ] absence of hard-power does not cause S1 INCOMPLETE.

## 7. Реализация S2

### Hooks

- [ ] C1_SHUTTER_REQUEST_ACCEPTED
- [ ] C2_FRAME_ACQUIRED
- [ ] C3_ENCODE_FINISHED
- [ ] C4_DURABLE_PUBLICATION_RETURNED
- [ ] C5_TX_CAPTURE_BEGIN
- [ ] C6_TX_CAPTURE_COMMITTED
- [ ] C7_PAGE_VISIBLE

### Workloads

- [ ] C-W0 Idle.
- [ ] C-W1 OCR pressure with sole heavy permit.
- [ ] C-W2 Save pressure with sole heavy permit.
- [ ] operation_id correlation is checked for every command.
- [ ] serial capture never waits for prior OCR/save completion.
- [ ] kill conditions and metrics follow protocol §4 exactly.
- [ ] DEV reduced profile is explicitly labelled DEV.
- [ ] qualification supports 100 × 20 per workload, 6,000 total.

## 8. Реализация S3

- [ ] actual OCR adapter boundary exists;
- [ ] active heavy jobs, owner/kind and decoded token bytes recorded;
- [ ] PSS/RSS captured;
- [ ] OCR attempted StateStore writes counted;
- [ ] admission enforces peak active heavy jobs ≤ 1;
- [ ] decoded token limit is checked at 96 MiB;
- [ ] O-F1/O-F2/O-F3 fixtures exist only when envelope is explicit;
- [ ] absent envelope produces `input_envelope_defined=false` and `INCOMPLETE`;
- [ ] synthetic DEV fixtures cannot fill qualification fields;
- [ ] qualification supports 3,000 fixture runs + 500 contention sets.

## 9. Реализация S4

- [ ] immutable SaveManifest and hash check;
- [ ] staged PDF is fully written before local commit;
- [ ] app-side structural validator;
- [ ] independent host-side validator;
- [ ] page count/order/first-last render verification;
- [ ] decoded memory accounting;
- [ ] P_MAX/raster/render envelope read from explicit config only;
- [ ] absent envelope produces `INCOMPLETE`;
- [ ] qualification supports 1, ceil(P_MAX/4), ceil(P_MAX/2), P_MAX;
- [ ] 50 runs per point + 200 P_MAX contention runs.

## 10. Реализация S5

- [ ] CommittedExportArtifact fixture;
- [ ] TX-EXPORT-BEGIN / WritingExternal / TX-EXPORT-END;
- [ ] provider call occurs outside authoritative write TX;
- [ ] ProviderOperationObservation stores dimensions separately;
- [ ] E1 and E2 hooks;
- [ ] startup WritingExternal → UnknownOutcome;
- [ ] revalidation is read-only;
- [ ] provider-write counter is measured;
- [ ] missing tuple inventory produces `INCOMPLETE`;
- [ ] no mock substitutes for physical qualification;
- [ ] each real tuple supports 100 success + 100 known-failure + 100 E1 + 100 E2.

## 11. Реализация S6

Создай единую explicit registry и проверку полноты:

- [ ] R01–R03 capture/artifact;
- [ ] R04–R07 retake/edit;
- [ ] R08–R13 OCR/processing;
- [ ] R14–R19 import;
- [ ] R20–R25 save;
- [ ] R26–R30 export;
- [ ] R31–R35 cleanup;
- [ ] R36–R39 metadata purge/maintenance;
- [ ] R40–R41 schema migration.

Для каждого hook:

- [ ] exact hook name;
- [ ] setup/seed;
- [ ] expected pre-state;
- [ ] process-death point;
- [ ] hook-specific oracle;
- [ ] global recovery oracle;
- [ ] dump #1/#2/#3 as required;
- [ ] idempotency evaluation;
- [ ] raw log and artifact references;
- [ ] DEV 3 runs;
- [ ] QUALIFICATION 100 runs.

## 12. Evaluator rules

Для каждого S1–S6 разрешены только:

- `PASS` — all mandatory cases completed and zero frozen kill-conditions;
- `FAIL` — at least one frozen kill-condition;
- `INCOMPLETE` — missing mandatory case/input/device/build/raw evidence.

Запрещены `PENDING`, `UNPROVEN`, `ASSUMED` как обход completion gate. Нельзя конвертировать `INCOMPLETE` в `PASS` через emulator data, estimates или documentation.

## 13. Mandatory test matrix

### Unit/host tests

- [ ] mode guard tests;
- [ ] count guard tests;
- [ ] serial/device guard tests;
- [ ] JSON schema tests;
- [ ] aggregate fail-closed tests;
- [ ] duplicate/missing evidence tests;
- [ ] hash/identity mismatch tests;
- [ ] unknown outcome tests.

### Instrumented/emulator tests

- [ ] every hook reachable;
- [ ] every hook produces expected operation_id;
- [ ] abrupt kill is observed;
- [ ] restart/recovery is a separate invocation;
- [ ] diagnostics не мутируют; S1 snapshot до recovery, S6 dump #1 после startup barrier и до object recovery;
- [ ] all DEV evidence contains `execution_mode=DEV`;
- [ ] no DEV file is accepted by QUALIFICATION validator.

### Full DEV run

- [ ] clean build from scratch;
- [ ] install explicit serial;
- [ ] full S1–S6 DEV suite;
- [ ] every JSON parses and validates;
- [ ] every raw reference exists;
- [ ] aggregate counts equal per-run counts;
- [ ] all incomplete prerequisites are listed;
- [ ] no frozen document changed.

## 14. Git and documentation discipline

Before each independent change:

- [ ] `git status --short` checked;
- [ ] unrelated user changes preserved;
- [ ] only relevant paths staged;
- [ ] change committed with focused message;
- [ ] tag added before risky experiment when appropriate.

After functional changes:

- [ ] version updated in all related version files;
- [ ] README/docs updated;
- [ ] traceability added from code/test to protocol section;
- [ ] test evidence attached to the commit/log.

## 15. Required progress report after every phase

Gravity must report in this exact compact structure:

```text
PHASE: <name>
STATUS: PASS | FAIL | BLOCKED | INCOMPLETE
COMMIT: <hash>
CHANGED: <paths>
TESTS: <commands and result>
EVIDENCE: <root and counts>
OPEN_BLOCKERS: <exact IDs or NONE>
NEXT_GATE: <name>
```

`STATUS=PASS` без commands/evidence запрещён.

## 16. Final handoff checklist

- [ ] B0–B9 из roadmap подтверждены.
- [ ] app APK, androidTest APK и host runner paths записаны.
- [ ] SHA-256 всех deliverables записаны.
- [ ] exact device/build tuple записан для qualification template.
- [ ] S1–S6 aggregates имеют outcome и raw references.
- [ ] DEV/QUALIFICATION разделены физически и логически.
- [ ] hard-power scope указан явно.
- [ ] отсутствующие S3/S4/S5 inputs классифицированы как `INCOMPLETE`.
- [ ] `FINAL_EXECUTION_REPORT.md` не содержит invented values.
- [ ] Post-Spikes не выполнялся.
- [ ] последний commit содержит только завершённый самостоятельный этап.
- [ ] после handoff работа остановлена.

## 17. Обязательные дополнения версии 1.1

Документ 08 является частью ТЗ: evidence-backed ledger, негативные тесты evaluator, acceptance CAP-001…016, физический этап Q, reviewed A и release gate обязательны. Чекбокс без evidence не означает DONE. Не менять само ТЗ для отражения прогресса — вести отдельный ledger. При конфликте краткого checklist с frozen protocol сохранить точную нормативную процедуру и зарегистрировать расхождение.

S4 использует 50 runs на каждую УНИКАЛЬНУЮ точку page count; при совпадении 1/ceil(P_MAX/4)/ceil(P_MAX/2)/P_MAX дубли не добавляются. Qualification counts не редактируемые defaults: запуск укороченного набора остаётся INCOMPLETE. Synthetic DEV input допустим для S3/S4 при незаданном product envelope; он не делает qualification ready.
