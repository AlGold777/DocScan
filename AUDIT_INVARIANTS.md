# Методология системного аудита валидатора

Версия документа: 1.1. Основание: инструкция пользователя от 2026-09-20.
Это проектное приложение к [общему регламенту взаимодействия](docs/AGENT_COLLABORATION_PROTOCOL.md),
а не универсальная инструкция для других задач. Формы передачи:
[AGENT_COLLABORATION_TEMPLATES.md](docs/AGENT_COLLABORATION_TEMPLATES.md).
Исходный код для начальной инвентаризации: `e5eede6`.
Статус: **IN_PROGRESS — полнота инвентаризации и покрытия ещё не подтверждена**.

## 1. Обязательный порядок ревью

1. До ревью нового runner обновить этот документ по frozen-протоколу, схемам,
   producer-коду Android, host-коду и реальным пакетам. Diff — только указатель изменений.
2. Для каждого поля, вложенного поля, файла и внешней операции проверить:
   **P** Presence, **F** Format, **C** Cross-check, **E** Failure propagation.
3. Найденный дефект классифицировать по P/F/C/E. Выполнить поиск того же паттерна
   по ВСЕМУ `host/runner.py`, затем по связанным validator/producer-контрактам.
   В одном отчёте перечислить все найденные места, включая защищённые аналоги.
4. До реализации фикса передать Gravity полный набор негативных случаев для
   затронутого класса инвариантов. Фикс должен покрывать класс, а не одну строку.
5. Принимать по исходникам, исполненным регрессионным тестам и новому DEV-циклу.
   Не считать число зелёных тестов доказательством полноты.

`if x:`, `.get(..., 0)`, `except`, `check=False` сами по себе не дефекты.
Для каждого места необходимо доказать обязательность значения, разрешённый fallback
или обязательную последующую обработку ошибки. Поиск — инвентаризация рисков,
не автоматическое доказательство обхода.

## 2. Формат полного реестра

Каждая строка итогового реестра соответствует ОДНОМУ конкретному JSON path,
файловому контракту либо внешней операции. Группы ниже — начальная карта scope,
НЕ замена поэлементной инвентаризации и НЕ основание заявить 100% покрытия.

Обязательные колонки для развёрнутых строк:

`ID | source/section | artifact + JSON path | spike/mode/condition | P | F | C | E | code commit:path:line/symbol | test ID for each property | execution/evidence | reviewer verdict`

Для P/F/C/E допустимы `TODO`, `PASS`, `FAIL`, `N/A:обоснование`.
PASS требует проверенного кода и исполненного теста. Для условного поля явно
описать условие обязательности и протестировать обе ветви. Nullable telemetry
требует причины unavailable/censored и не может подменять наблюдение нулём.
Копии одного утверждения в двух JSON не являются независимым измерением.

## 3. Начальная карта инвариантов и угроз

Все строки ниже имеют P/F/C/E = TODO; ссылки — места проверки, не вердикт.
Списки полей в строках обязательно развернуть перед закрытием соответствующего gate.

| ID | Поля/объекты и scope | Требуемый контракт P/F/C/E | Негативные тесты до фикса |
|---|---|---|---|
| I01 | Все JSON, корень, schema/manifest version, классификация файла | Обязательный файл; допустимый тип корня и версия; однозначная роль; parse/read failure исключает PASS | absent, empty, truncated, list вместо object, unsupported version, unknown role |
| I02 | `spike_id`, `case_id`, `injection_hook`, QA linkage | Непустые enum; точное соответствие нормативному registry и сценарию | missing/null/blank/wrong type, unknown spike/case/hook, допустимый префикс с выдуманным суффиксом |
| I03 | `run_id`, `operation_id`, cycle/attempt identity | Валидные уникальные IDs, связь с fixture и командой; дубликаты не увеличивают completeness | duplicate, ID чужого run, повтор файла под другим именем, wrong operation |
| I04 | `execution_mode`, `dut_id`, `device_serial`/`hardware_serial`, fingerprint, `app_build_hash` | Явные типы/форматы; согласованная identity всех артефактов и измеренного устройства/APK; отсутствие источника — отказ | null/пустой/string-null/hash wrong format, mixed modes/APKs/serials, emulator QUAL, перестановка порядка файлов |
| I05 | Device manufacturer/model/sku/OS/API/security patch/kernel/filesystem/ABI/MemTotal | Условная обязательность по schema; без выдуманных defaults; независимые измерения | getprop/df/hash command error, empty output, malformed number, wrong path filesystem, heap вместо RAM |
| I06 | `start_monotonic_ns`, `end_monotonic_ns`, dump timestamps, latency fields | Конечные числа, не bool/NaN/Inf; порядок, единицы и происхождение; unavailable явно | zero, reversed, wrong units, fabricated fallback, timestamp чужого запуска |
| I07 | `raw_log_location`, raw evidence paths, logs/transcripts | Непустой файл внутри package; связь run/operation/hook/PID; host-log не перезаписывается device-log | null, empty log, directory instead of file, absolute/traversal/escaping symlink, foreign log, overwritten transcript |
| I08 | Dump identity, `dump_type`, mode/time/device/hash | Оба требуемых dump; полный типизированный объект; filename/run/type/identity связаны с per-run | missing directory/file, swapped pre/post, foreign run, missing comparison operand |
| I09 | Dump published/temp exists/length/hash, ArtifactRecord, references, owners, workflow state | Полная схема снимка и идентичность объектов; external и embedded snapshots согласованы; read error не равен пустому состоянию | отсутствующее поле с обеих сторон, corrupt bytes, foreign owner/ref, diagnostic exception, partial transaction |
| I10 | `.bin` и ожидаемые/наблюдаемые length/SHA-256 | Нормативная условная обязательность по D-hook; тип/формат; host bytes против независимого fixture oracle | missing/zero/truncated/modified bin, null expected/observed hash, wrong size/type, запрещённая publication |
| I11 | `outcome`, `kill_condition_triggered`, `artifact_checks[*].check/passed`, diagnostic flags | Закрытая схема; все обязательные проверки присутствуют; нарушения распространяются в aggregate и exit status | absent outcome/check, bool/string confusion, unknown violation representation, per-run FAIL + aggregate PASS |
| I12 | Все aggregate counters, findings, totals, quantiles | Обязательные неотрицательные integer counters (не bool); пересчёт из attempts/findings/raw events; missing не равен 0 | omit/null/string/negative/fractional counter, mismatch totals, erased findings, fabricated percentiles |
| I13 | Expected/observed case matrices, repeats, workload/fixture/point registry | Точная матрица из зафиксированного mode/profile; каждый слот уникален, неполнота запрещает gate PASS | aggregate-only, one run per category, extra/duplicate/missing slot, changed profile, subset relabelled PASS |
| I14 | S2 C1–C7, visible Page, timestamps, dimensions/JPEG bytes, StateStore queue | Реальные события и данные; Page commit/display коррелируют; метрики измеряют заявленное | fake C7, no draw/hidden/wrong Page, queue=in-flight, missing JPEG, false committed count |
| I15 | S2 W0/W1/W2 permit owner/history, background results, burst interval, capture wait | Реальная нагрузка/admission до burst и на нужном интервале; нет подавленных ошибок | signal before admission, gaps, adapter denied/failed, fake timestamps, no counter producer |
| I16 | S3 envelope/profile, languages/formats, tokens/admission, PSS/RSS, candidate commits | DEV input не product approval; actual live buffers и limits; persistent conditional commit и реальные измерения | undefined inputs marked defined, RSS=privateDirty, stale local constants, timeout releases live OCR, unaccounted allocation |
| I17 | S4 `P_MAX`, render envelope, `pdf_artifact_path`, SaveManifest ID/page_count/page_ids/page_hashes/payload hash | Полный manifest и frozen order; нормативные points; каждый ожидаемый PDF существует и связан с run | missing PDF/manifest field/hash, duplicate page ID, changed manifest, reorder/substitute PDF |
| I18 | PDF pages, image bytes, rendering/content evidence, validator version/results | Структура + реальные pixels против эталона; нельзя принять лишь неиспользуемый ресурс/надпись | blank PDF, unused/off-page/covered raster, background-colour cover, missing details, malformed stream, renderer failure |
| I19 | Health ANR/OOM/observation_failed/error и memory counters S2–S4 | Scope по процессу/времени; дедупликация; unknown не значит healthy; failure не подавляется | stale/unrelated event, low-memory вместо OOM, permission/API error, null observer, missing flag |
| I20 | S5/S6 все поля, hooks, providers, recovery dumps | Развернуть из frozen-протокола до реализации/приёмки; real provider и отдельный oracle каждого R-case | missing E/R case, provider mock passed as real, missing dump, non-idempotent recovery, R31 misclassification |
| I21 | Commands/ADB start, kill, snapshot, pull, hash, instrumentation, aggregate | Ограниченный timeout; typed expected process death; проверка returncode и результата; ошибка не заменяет kill | hung command, nonzero with OK text, marker without death, wrong PID, failed/empty pull, stdout/stderr loss |
| I22 | Cleanup, failure capture, raw log harvest | Уже полученные evidence сохраняются до cleanup; ошибки не превращаются в успешную чистую среду | disconnect, failed cleanup, interrupted loop, log read failure, aggregate failure, lost prior attempts |
| I23 | `resume_attempts[*]`: timestamp/spike/mode/serial/outcome/executed_runs/error | Валидный журнал; все identity источники проверены до действий; attempts сохраняются; SUCCESS после validation | missing identity, mixed tuple, malformed attempts, corrupt JSON deletion, resume twice duplicates, unsupported spike |
| I24 | Package manifest, checksums, links, relocated raw artifacts | Самодостаточный package; повторная проверка после копирования; не зависит от source dir | relocate/remove source, broken refs, corrupt packaged byte, omitted attempts, wrong manifest hash |
| I25 | `tasks.json`, deps, source IDs, commit/evidence, gate verdict | DONE требует покрытия и принятого evidence; status string не доказательство | DONE without proof, unresolved dependency, stale hash, missing test, IN_PROGRESS treated complete |

## 4. Первый сквозной поиск по runner.py

Срез `e5eede6`; ниже все перечисленные места подлежат проверке и привязке к
строкам полного реестра. Это кандидаты на аудит, не список уже воспроизведённых багов.

| Класс паттерна | Места в runner.py (строки исходного среза) | Обязательное обобщение |
|---|---|---|
| Optional comparison operands | manifest 763–771; dumps 805–813; per-run 874–883; aggregate 1105–1114; disk aggregation 1594,1611 | Проверить обязательность обоих fingerprint/hash/expected значений во всех режимах/артефактах |
| Missing counter becomes zero | 1130–1140; 1304–1307; 1335–1347; 1359–1362; 1416–1422 | Описать каждое поле, где default допустим, и запретить default для обязательного наблюдения |
| Optional cross-check/schema | embedded dumps 1010,1013; per-run outcome/checks 1148–1164 | Обязательные поля должны проверяться даже когда удалены с обеих сторон; checks нельзя отключить пустым списком |
| Missing observation flag | 1252,1330 | Проверять наличие и строгий bool, а не только равенство True |
| Swallowed exception/fallback | 265; metadata/aggregate 1538; identity scan 1794; attempts 1859 | Различить допустимое чтение альтернативы и потерю обязательного evidence; журнал не сбрасывать молча |
| Unchecked external failures | instrumentation 208; logs 239,298,326,380,661; metadata 426–445; cleanup 471–477; final pulls 570 | Просмотреть ВСЕ check=False, включая защищённые 352–358,415–417; документировать последующую проверку каждого |
| Truthiness вместо type/format | serial aliases 772,814,884,1115; outcome 847,1075; P_MAX 1356; PDF aliases 1395 | Общие schema validators, конфликтующие aliases отклонять; zero/null отдельно от falsy |

Повторяемый поиск (после каждого исправления дополнять новыми паттернами):

```sh
rg -n 'check=False|except|\.get\(|if .*sha|if .*serial|if .*hash|if .*fp' host/runner.py
rg -n 'or 0|, 0\)|is True|if .* in .*dump|return.*PASS' host/runner.py
```

## 5. Матрица adversarial-тестов для Gravity — до реализации

Для КАЖДОГО обязательного поля из полного реестра параметризовать:

- **T-P:** удалить; null; пустая строка/whitespace; пустой контейнер; строка `null`.
- **T-F:** неправильный scalar/container, bool вместо number, NaN/Infinity,
  отрицательное/дробное значение счётчика, unknown enum, повреждённый формат hash.
- **T-C:** заменить только один источник; оба JSON согласованы, но диск/fixture
  отличается; foreign run/operation/device; duplicate ID; переставить порядок файлов.
- **T-E:** сбой чтения/hash/renderer/ADB; timeout; nonzero с правдоподобным stdout;
  отсутствие файла при returncode=0; ожидаемая остановка до зависимого шага.
- **T-ROUNDTRIP:** полный положительный fixture, мутация, package/relocate/validate,
  resume и повторный resume. Обязателен контроль отсутствия побочных действий после отказа.

Для optional/условных полей тестировать допустимое отсутствие и его границу.
Невалидный пакет может возвращать validation error, наблюдённое нарушение — FAIL,
отсутствие доказательства — INCOMPLETE; ни одно из них не даёт успешную приёмку gate.
Успешная проверка формата FAIL/INCOMPLETE-пакета не равна успешному experimental outcome.
Каждый тест должен ломать именно целевой инвариант на иначе корректном fixture;
отказ по другой причине не подтверждает защиту. Mock не должен подменять проверяемый код.

## 6. Формулировка следующего requests-to-fix

> Валидатор нигде не должен принимать PASS при отсутствии обязательного поля
> сравнения, обязательного файла или результата наблюдения. Инвентаризировать
> каждый такой operand в runner.py и связанных валидаторах. Для каждого указать
> P/F/C/E, code location и negative test. Найденный класс распространить на все
> применимые места в одном согласованном исправлении; исключения обосновать.
> Не менять frozen outcomes, не ослаблять tests, не объявлять неизвестное нулём.

Форма отчёта одной находки:
`ID → P/F/C/E → воспроизведение → ВСЕ аналогичные места → исключения → tests → fix/evidence → verdict`.

## 7. Условия закрытия B1/B2

- Полный field/file/command inventory выведен из нормативных источников И producer/schema/code.
- Включены вложенные paths и conditional поля; все группы разделены на отдельные строки.
- Нет полей без классификации и нет TODO. Для N/A есть согласованное обоснование.
- Для каждой применимой P/F/C/E проверки есть code reference и исполненный regression test.
- Проведён сквозной поиск аналогичных паттернов; ни один найденный участок не оставлен без решения.
- Новый DEV-прогон соответствует проверенному APK/code commit; raw evidence и package валидны.
- Независимый reviewer подтвердил матрицу и результат. Самооценка Gravity не заменяет review.

Покрытие считать отдельно: inventory completeness, tests mapped, tests executed,
reviewed PASS. Знаменатель — полный подтверждённый inventory; до его завершения
процент покрытия **не определён**, не 100%. S5/S6 не исключать молча из общего B1.

На момент создания документа B1/B2 остаются **IN_PROGRESS по новой методологии**.
Исторические DEV-пакеты и локальные приёмки сохраняются; новые дефекты ими не
предполагаются. Это изменение критерия полной приёмки, а не отмена измерений.

## 8. Развёрнутый реестр инвариантов S5-SAF-EXPORT (I20-S5 / Gate B6)

| Normative Invariant | Observation Source | Process & Run_Id Binding | Executable Oracle | Negative Test (MUT-xx) | Evidence Link | Verdict |
|---|---|---|---|---|---|---|
| I20-S5-01: Симуляция ошибок SAF-провайдера (SecurityException, FileNotFoundException, QuotaExceeded) | `MockDocumentsProvider.java:43-63` | `com.example.docscanspike` / `s5-known_failure-*` | `runner.py:1599` (`export_terminal_state == 'FAILED'`) | `tests/test_s5_validator.py:176` | `dev-results/raw/S5/KNOWN_FAILURE/*.json` | DEV-PASS |
| I20-S5-02: Запрет слепых повторов внешней записи при ошибке (`write_call_count == 1`) | `MockDocumentsProvider.getWriteCallCount()` & IPC measurement file | `:pipeline` & main process / `s5-*-*` | `runner.py:1590` (`provider_write_call_count == 1`, `blind_retry_count == 0`) | `MUT-14` (`test_s5_validator_rejects_repeated_write_unknown_outcome`) | `dev-results/raw/S5/KNOWN_FAILURE/*.json` | FAIL (S5Harness fallback & PID binding gap) |
| I20-S5-03: Строгая изоляция SQLite транзакций: нулевая глубина транзакций на входе и выходе | `S5Pipeline.java:s5-measurements/{runId}.json` (`db.inTransaction()`) | `:pipeline` (PID verified != harness PID) / `s5-*-*` | `runner.py:1572` (`tx_depth_entry == 0`, `tx_depth_exit == 0` for non-E1, `exit_depth is None` for E1) | `MUT-13` (`test_s5_validator_rejects_e1_non_null_exit_depth`), `test_reproduce_s5harness_exit_depth_fallback_to_zero` | `dev-results/raw/S5/*/*.json` | FAIL (S5Harness.java:210 defaults missing exit depth to 0; no run_id verification) |
| I20-S5-04: 6-мерная строгая булева верификация наблюдения после экспорта | ContentResolver query & byte re-read SHA-256 hash match | `com.example.docscanspike` / `s5-success-*` | `runner.py:1615` (`type(v) is bool and v is True` across all 6 dimensions) | `MUT-07` (`test_s5_validator_rejects_string_boolean_observation`) | `dev-results/raw/S5/SUCCESS/*.json` | DEV-PASS |
| I20-S5-05: Инъекция падений E1/E2 при экспорте; переход в UNKNOWN_OUTCOME без повтора | `PipelineService` Binder death recipient & IPC measurement file | `:pipeline` (PID observed dead) / `s5-e1-*`, `s5-e2-*` | `runner.py:1602` (`export_terminal_state == 'UNKNOWN_OUTCOME'`, `process_death_verified is True`) | `test_reproduce_s5harness_loose_pid_check`, `test_s5_validator_rejects_unverified_process_death` | `dev-results/raw/S5/E1/*.json`, `dev-results/raw/S5/E2/*.json` | FAIL (measPid != harnessPid does not bind to expected process of this run) |
| I20-S5-06: Граница провайдеров: DEV-профиль на MockDocumentsProvider vs целевые провайдеры ОС | `S5Harness.java` aggregate manifest flags (`INPUT-05`) | `com.example.docscanspike` / `DEV-EMULATOR-5554` | `runner.py:1550` (`provider_tuples_inventory_defined is False` => `qualification_outcome == 'INCOMPLETE'`) | `test_s5_validator_rejects_qualified_outcome_when_tuples_undefined` | `dev-results/S5-SAF-EXPORT.json` | DEV-PASS |

## 9. Развёрнутый реестр инвариантов S6-PROCESS-DEATH (I20-S6 / Gate B7)

Нормативный источник: `docs/02_DocScan_Spike_Protocol_v1.1_FROZEN_Xiaomi14Ultra.md` §8.3, §8.4, §8.5.
Порядок дампов по протоколу §8.5: **Dump 1** (pre-recovery: сразу после падения, перезапуска и startup barrier) -> **Dump 2** (post-recovery pass 1) -> **Dump 3** (post-recovery pass 2: проверка идемпотентности, Dump 2 == Dump 3).

| ID | Группа / Хук протокола §8.4 | Нормативный Seed | Pre-Recovery Facts (Dump 1) | Post-Recovery Facts (Dump 2 & 3) | Целевая мутация (Clean vs Mutated) | Independent Oracle | Host Runner Oracle | Coverage & Test Reference |
|---|---|---|---|---|---|---|---|---|
| **R01** | Capture: `R01_CAPTURE_STAGED_BEFORE_TX` | d1-temp-artifacts/{run_id}.tmp | Temp file exists; 0 pages; 0 records | Temp file deleted; 0 pages; 0 records | Inject phantom authoritative page into Dump 1 | **PASS** | **DEV-PASS (runner.py:1753)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R01]` |
| **R02** | Capture: `R02_CAPTURE_TX_AFTER_MUTATIONS_BEFORE_COMMIT` | Uncommitted capture in TX | TX rolled back: 0 records; 0 pages | 0 records; 0 pages | Inject uncommitted STAGED_DURABLE record into Dump 1 | **PASS** | **DEV-PASS (runner.py:1767)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R02]` |
| **R03** | Capture: `R03_CAPTURE_AFTER_TX_COMMIT_BEFORE_UI_ACK` | Committed capture in TX | art-{run_id} COMMITTED; page-{run_id} rev 1; {run_id}.bin pub | Identical to Dump 1 | Delete authoritative page from Dumps 1, 2, 3 | **PASS** | **DEV-PASS (runner.py:1779)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R03]` |
| **R04** | Retake/Edit: `R04_RETAKE_TX_AFTER_MUTATIONS_BEFORE_COMMIT` | page-{run_id} rev 1; uncommitted v2 in TX | page-{run_id} rev 1 (v2 rolled back) | page-{run_id} rev 1 | Advance content_revision to 2 in Dump 1 | **PASS** | **DEV-PASS (runner.py:1798)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R04]` |
| **R05** | Retake/Edit: `R05_RETAKE_AFTER_TX_COMMIT_BEFORE_ACK` | page-{run_id} rev 1; committed v2 in TX | page-{run_id} rev 2 (v2 committed) | page-{run_id} rev 2 | Roll back content_revision to 1 in Dump 1 | **PASS** | **DEV-PASS (runner.py:1808)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R05]` |
| **R06** | Retake/Edit: `R06_EDIT_TX_AFTER_MUTATIONS_BEFORE_COMMIT` | page-{run_id} rev 1; uncommitted edit in TX | page-{run_id} rev 1 (edit rolled back) | page-{run_id} rev 1 | Advance content_revision to 2 in Dump 1 | **PASS** | **DEV-PASS (runner.py:1798)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R06]` |
| **R07** | Retake/Edit: `R07_EDIT_AFTER_TX_COMMIT_BEFORE_ACK` | page-{run_id} rev 1; committed edit in TX | page-{run_id} rev 2 (edit committed) | page-{run_id} rev 2 | Roll back content_revision to 1 in Dump 1 | **PASS** | **DEV-PASS (runner.py:1808)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R07]` |
| **R08** | OCR/Proc: `R08_OCR_CANDIDATE_READY_BEFORE_TX` | page-{run_id}; OCR candidate in RAM | ocr_text is None (RAM lost) | ocr_text is None | Inject phantom ocr_text into Dump 1 | **PASS** | **DEV-PASS (runner.py:1818)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R08]` |
| **R09** | OCR/Proc: `R09_OCR_TX_AFTER_INSERT_BEFORE_COMMIT` | page-{run_id}; uncommitted OCR in TX | ocr_text is None (TX rolled back) | ocr_text is None | Inject uncommitted ocr_text into Dump 1 | **PASS** | **DEV-PASS (runner.py:1818)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R09]` |
| **R10** | OCR/Proc: `R10_OCR_AFTER_TX_COMMIT_BEFORE_ACK` | page-{run_id}; committed OCR in TX | ocr_text == OCR-RESULT-{run_id} | ocr_text == OCR-RESULT-{run_id} | Set committed ocr_text to None in Dump 1 | **PASS** | **DEV-PASS (runner.py:1828)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R10]` |
| **R11** | OCR/Proc: `R11_PROCESSING_CANDIDATE_READY_BEFORE_TX` | page-{run_id}; proc candidate in RAM | 0 art-proc records (RAM lost) | 0 art-proc records | Inject phantom art-proc record into Dump 1 | **PASS** | **DEV-PASS (runner.py:1838)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R11]` |
| **R12** | OCR/Proc: `R12_PROCESSING_TX_AFTER_INSERT_BEFORE_COMMIT` | Uncommitted proc artifact in TX | 0 art-proc records (TX rolled back) | 0 art-proc records | Inject uncommitted STAGED_PROCESSING record into Dump 1 | **PASS** | **DEV-PASS (runner.py:1838)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R12]` |
| **R13** | OCR/Proc: `R13_PROCESSING_AFTER_TX_COMMIT_BEFORE_ACK` | Committed proc artifact in TX | art-proc COMMITTED; proc.bin pub | art-proc COMMITTED; proc.bin pub | Delete art-proc record from Dump 1 | **PASS** | **DEV-PASS (runner.py:1845)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R13]` |
| **R14** | Import: `R14_IMPORT_BEGIN_TX_BEFORE_COMMIT` | Uncommitted import session begin | 0 import sessions (TX rolled back) | 0 import sessions | Inject uncommitted import session into Dump 1 | **PASS** | **DEV-PASS (runner.py:1858)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R14]` |
| **R15** | Import: `R15_IMPORT_BEGIN_AFTER_COMMIT` | Committed import session begin | sess-{run_id} state ACTIVE | sess-{run_id} reconciled to ABORTED | Delete import session from Dump 1 | **PASS** | **DEV-PASS (runner.py:1865)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R15]` |
| **R16** | Import: `R16_IMPORT_PAGE_TX_BEFORE_COMMIT` | sess-{run_id}; uncommitted page insert | 0 import pages; session ACTIVE | 0 import pages; session ABORTED | Inject uncommitted import page into Dump 1 | **PASS** | **DEV-PASS (runner.py:1874)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R16]` |
| **R17** | Import: `R17_IMPORT_PAGE_AFTER_COMMIT_BEFORE_ACK` | sess-{run_id}; committed page insert | page-import-{run_id}-0 exists; session ACTIVE | page exists; session ABORTED | Delete committed import page from Dump 1 | **PASS** | **DEV-PASS (runner.py:1883)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R17]` |
| **R18** | Import: `R18_IMPORT_END_TX_BEFORE_COMMIT` | sess-{run_id}; uncommitted end TX | sess-{run_id} state ACTIVE (not COMPLETED) | sess-{run_id} state ABORTED | Advance uncommitted session to COMPLETED in Dump 1 | **PASS** | **DEV-PASS (runner.py:1890)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R18]` |
| **R19** | Import: `R19_IMPORT_END_AFTER_COMMIT` | sess-{run_id}; committed end TX | sess-{run_id} state COMPLETED | sess-{run_id} state COMPLETED | Roll back session state to ACTIVE in Dump 1 | **PASS** | **DEV-PASS (runner.py:1899)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R19]` |
| **R20** | Save: `R20_SAVE_FREEZE_TX_BEFORE_COMMIT` | Uncommitted SaveManifest in TX | 0 save manifests (TX rolled back) | 0 save manifests | Inject uncommitted SaveManifest into Dump 1 | **PASS** | **DEV-PASS (runner.py:1906)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R20]` |
| **R21** | Save: `R21_SAVE_FREEZE_AFTER_COMMIT` | Committed SaveManifest in TX | save-{run_id} state FROZEN | save-{run_id} state FROZEN | Delete SaveManifest from Dumps 1, 2, 3 | **PASS** | **DEV-PASS (runner.py:1913)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R21]` |
| **R22** | Save: `R22_SAVE_DEPENDENCY_TX_BEFORE_COMMIT` | save-{run_id}; uncommitted dep in TX | 0 save dependencies (TX rolled back) | 0 save dependencies | Inject uncommitted SaveDependency into Dump 1 | **PASS** | **DEV-PASS (runner.py:1920)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R22]` |
| **R23** | Save: `R23_SAVE_DEPENDENCY_AFTER_COMMIT` | save-{run_id}; committed dep in TX | dep-{run_id} COMMITTED; live ptrs unaffected | dep-{run_id} COMMITTED | Delete SaveDependency from Dump 1 | **PASS** | **DEV-PASS (runner.py:1930)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R23]` |
| **R24** | Save: `R24_SAVE_OUTPUT_TX_BEFORE_COMMIT` | save-{run_id}; uncommitted out in TX | 0 save outputs (TX rolled back) | 0 save outputs | Inject uncommitted SaveOutput into Dump 1 | **PASS** | **DEV-PASS (runner.py:1939)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R24]` |
| **R25** | Save: `R25_SAVE_OUTPUT_AFTER_COMMIT` | save-{run_id}; committed out in TX | out-{run_id} COMMITTED | out-{run_id} COMMITTED | Delete SaveOutput from Dump 1 | **PASS** | **DEV-PASS (runner.py:1949)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R25]` |
| **R26** | Export: `R26_EXPORT_BEGIN_TX_BEFORE_COMMIT` | Uncommitted ExportTask in TX | 0 export tasks (TX rolled back) | 0 export tasks | Inject uncommitted ExportTask into Dump 1 | **PASS** | **DEV-PASS (runner.py:1958)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R26]` |
| **R27** | Export: `R27_EXPORT_AFTER_BEGIN_COMMIT_BEFORE_PROVIDER` | Committed export begin before provider | export-{run_id} WRITING_EXTERNAL (bytes 0) | export-{run_id} UNKNOWN_OUTCOME | Retain WRITING_EXTERNAL in Dump 2 | **PASS** | **DEV-PASS (runner.py:1965)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R27]` |
| **R28** | Export: `R28_EXPORT_AFTER_PROVIDER_SIDE_EFFECT_BEFORE_END_TX` | Provider write done before end TX | export-{run_id} WRITING_EXTERNAL (bytes 1024) | export-{run_id} UNKNOWN_OUTCOME | Retain WRITING_EXTERNAL in Dump 2 | **PASS** | **DEV-PASS (runner.py:1965)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R28]` |
| **R29** | Export: `R29_EXPORT_END_TX_BEFORE_COMMIT` | Uncommitted export end TX | export-{run_id} WRITING_EXTERNAL in Dump 1 | export-{run_id} UNKNOWN_OUTCOME in Dump 2 | Retain WRITING_EXTERNAL in Dump 2 | **PASS** | **DEV-PASS (runner.py:1965)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R29]` |
| **R30** | Export: `R30_EXPORT_AFTER_END_COMMIT_BEFORE_ACK` | Committed export end TX | export-{run_id} COMPLETED (bytes 1024) | export-{run_id} COMPLETED | Delete export task from Dumps 1, 2, 3 | **PASS** | **DEV-PASS (runner.py:1974)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R30]` |
| **R31** | Cleanup: `R31_GC_CLAIM_TX_BEFORE_COMMIT` | art-gc; file pub; uncommitted claim | art-gc COMMITTED; token null; file pub | art-gc COMMITTED; file pub | Set state GC_CLAIMED and token in Dump 1 | **PASS** | **DEV-PASS (runner.py:1981)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R31]` |
| **R32** | Cleanup: `R32_GC_AFTER_CLAIM_COMMIT_BEFORE_DELETE` | art-gc; file pub; committed claim | art-gc GC_CLAIMED; file pub | art-gc GC_FINALIZED; file deleted | Retain file in Dump 2 published_files | **PASS** | **DEV-PASS (runner.py:2000)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R32]` |
| **R33** | Cleanup: `R33_GC_AFTER_PHYSICAL_DELETE_BEFORE_FINALIZE` | art-gc; file deleted; pre-finalize | art-gc GC_CLAIMED; file absent | art-gc GC_FINALIZED; file absent | Retain GC_CLAIMED in Dump 2 | **PASS** | **DEV-PASS (runner.py:2000)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R33]` |
| **R34** | Cleanup: `R34_GC_FINALIZE_TX_BEFORE_COMMIT` | art-gc; uncommitted finalize TX | art-gc GC_CLAIMED (finalize rolled back) | art-gc GC_FINALIZED via claim token | Retain GC_CLAIMED in Dump 2 | **PASS** | **DEV-PASS (runner.py:2000)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R34]` |
| **R35** | Cleanup: `R35_GC_AFTER_FINALIZE_COMMIT` | art-gc; committed finalize TX | art-gc GC_FINALIZED; file absent | art-gc GC_FINALIZED; file absent | Roll back state to COMMITTED in all dumps | **PASS** | **DEV-PASS (runner.py:2000)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R35]` |
| **R36** | Maint: `R36_METADATA_PURGE_TX_BEFORE_COMMIT` | art-stale; cursor 10; uncommitted purge | art-stale exists; cursor 10 | art-stale exists; cursor 10 | Delete uncommitted purge artifact in Dump 1 | **PASS** | **DEV-PASS (runner.py:2014)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R36]` |
| **R37** | Maint: `R37_METADATA_PURGE_AFTER_COMMIT_BEFORE_CURSOR_ACK` | art-stale deleted in committed TX | art-stale absent; cursor 10 | art-stale absent; cursor 10 | Inject deleted stale artifact into Dump 1 | **PASS** | **DEV-PASS (runner.py:2024)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R37]` |
| **R38** | Maint: `R38_MAINTENANCE_CURSOR_TX_BEFORE_COMMIT` | cursor 10; uncommitted update in TX | cursor remains 10 (not 20) | cursor remains 10 | Advance cursor_pos to 20 in Dump 1 | **PASS** | **DEV-PASS (runner.py:2034)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R38]` |
| **R39** | Maint: `R39_MAINTENANCE_CURSOR_AFTER_COMMIT` | cursor 10; committed update in TX | cursor updated to 20 | cursor updated to 20 | Roll back cursor_pos to 10 in all dumps | **PASS** | **DEV-PASS (runner.py:2041)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R39]` |
| **R40** | Migration: `R40_SCHEMA_MIGRATE_TX_BEFORE_COMMIT` | v1 DB; uncommitted v2 in onUpgrade | version 1; column ocr absent; legacy row OK | Identical to Dump 1 | Advance schema version to 2 | **PASS** | **DEV-PASS (runner.py:2048)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R40]` |
| **R41** | Migration: `R41_SCHEMA_MIGRATE_AFTER_COMMIT_BEFORE_NEXT_STEP` | v1 DB; committed v2 in onUpgrade | version 2; column ocr present; legacy row OK | Identical to Dump 1 | Roll back schema version to 1 | **PASS** | **DEV-PASS (runner.py:2057)** | `EXECUTED_BEHAVIORAL`<br>`test_runner_catches_mutation_for_all_41_hooks[R41]` |
| **PM01** | Protocol Metric: `save_dependency_updated_live_pointer_count` == 0 | Protocol §8.4; SQLite AuthoritativePage | Pointer comparison before vs after save | Pointer identical; live ptrs unmodified | Substring heuristic `.contains("dep-")` eliminated; full pointer diff | **PASS** | **DEV-PASS (S6Harness.java:342)** | `STATIC_CODE_EVIDENCE`<br>`test_static_evidence_s6harness_live_pointer_contains_heuristic` |
| **PM02** | Protocol Metric: `writingexternal_not_unknown_after_restart_count` == 0 | Protocol §8.4; SQLite ExportTask | Pre-startup `WRITING_EXTERNAL` converted to `UNKNOWN_OUTCOME` | `UNKNOWN_OUTCOME` preserved | Task retains `WRITING_EXTERNAL` after restart | **PASS** | **DEV-PASS** (`runner.py:1837`) | `EXECUTED_BEHAVIORAL`<br>`MUT-12` |
| **PM03** | Protocol Metric: `recovery_requires_runtime_attempt_state_count` == 0 | Protocol §8.4; Cold-restart persistence | Fresh process; in-memory attempt maps null/empty | Recovery succeeds purely from SQLite/disk | Persistence-driven assertion via `S6RecoveryEngine.requiresInMemoryAttemptState()` | **PASS** | **DEV-PASS (S6Harness.java:362)** | `STATIC_CODE_EVIDENCE`<br>`test_static_evidence_s6harness_constant_zero_protocol_metrics` |
| **PM04** | Protocol Metric: `startup_requires_full_universe_scan_count` == 0 | Protocol §8.4; Startup reconciliation scope | Startup barrier query execution plan | `EXPLAIN QUERY PLAN` uses indexes, bounded rows | Query plan scan assertion via `S6RecoveryEngine.requiresFullUniverseScan()` | **PASS** | **DEV-PASS (S6Harness.java:367)** | `STATIC_CODE_EVIDENCE`<br>`test_static_evidence_s6harness_constant_zero_protocol_metrics` |
## 10. Развёрнутый реестр инвариантов пакета поставки (I24 / Gate B8)

| Normative Invariant | Observation Source | Process & Run_Id Binding | Executable Oracle | Negative Test (MUT-xx) | Evidence Link | Verdict |
|---|---|---|---|---|---|---|
| I24-01: Структура каталога пакета (`raw/S1..S6`, `logs/`, `dumps/`, `artifacts/`, `device_logs/`, `host_transcripts/`) | Filesystem directory layout | Host runner / DEV cycle package | `runner.py:2210-2275` (all 6 raw subfolders and log directories exist) | `test_validate_dir_rejects_missing_sums_in_package` | `dev-results/` directory tree | DEV-PASS |
| I24-02: Наличие и валидность манифеста пакета (`dev-manifest.json`) | `dev-manifest.json` on disk | Host runner / DEV cycle package | `runner.py:2284-2298` (`manifest_version`, `execution_mode`, `spikes_included`) | `test_validate_dir_rejects_missing_sums_in_package` | `dev-results/dev-manifest.json` | DEV-PASS |
| I24-03: Итоговый отчёт о выполнении `FINAL_EXECUTION_REPORT.md` с точными статусами и сводкой | `FINAL_EXECUTION_REPORT.md` on disk | Host runner / DEV cycle package | `runner.py:2300-2329` (spike breakdown, gate blockers, verified commit) | `test_validate_dir_rejects_missing_sums_in_package` | `dev-results/FINAL_EXECUTION_REPORT.md` | DEV-PASS |
| I24-04: Принудительная проверка контрольных сумм всех файлов `SHA256SUMS.txt` | `SHA256SUMS.txt` and raw files | Host runner / DEV cycle package | `runner.py:2330-2338`, `validate_dir` (every file hash matched against SHA256SUMS.txt) | `test_validate_dir_rejects_tampered_file_hash`, `test_validate_dir_rejects_missing_sums_in_package` | `dev-results/SHA256SUMS.txt` | DEV-PASS |
| I24-05: Переносимость и самодостаточность пакета при копировании в изолированную директорию | Isolated filesystem tree | Host runner / Isolated directory | `validate_dir` on `/tmp/isolated_verification/dev-results` (zero repo dependency) | `test_validate_dir_verifies_sha256sums_pass` | Isolated directory copy | DEV-PASS |
| I24-06: Перенос всех бинарных артефактов (включая сгенерированные `.pdf` файлы S4) | `dev-results/raw/S4/*.pdf` | Host runner / DEV cycle package | `runner.py:2234`, `validate_pdf_artifact` (all 12 PDFs transferred and validated) | `test_s4_aggregate_and_host_pdf_validation` | `dev-results/raw/S4/*.pdf` | DEV-PASS |





**Attempt 67 result (plan revision 5.446).** Runner 1.21 commit `def60de9` derives S6 pass-1 recovery count from Dump 1 and reconciles monotonic death duration plus the ordered source transcript. Committed-source pytest passes 157/157, including all 41 hook oracle mutations and the 41-hook positive fixture; py_compile and diff check pass. Evidence, checksum and initial path-resolution failures/correction: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-67-s6-recovery-metrics-transcript/`. No app runtime/device evidence; parent I20/I01–I25 and acceptance remain open.
