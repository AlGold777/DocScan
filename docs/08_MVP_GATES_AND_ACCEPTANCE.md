# DocScan — контроль исполнения и продуктовая приёмка

Версия 1.0. Обязательное приложение к docs 06–07. Это требования к будущему исполнению, не отчёт о выполненных проверках.

## 1. Исправления и границы

Предыдущая версия плана пропустила MVP приложения, физическое исполнение, продуктовую приёмку и новый platform audit. Настоящий комплект включает H → Q → STOP → reviewed A → P0–P7. Документация сама по себе не гарантирует дисциплину агента: нужны executable validators, сохранённые доказательства и review результатов. Независимый review нельзя заменять самооценкой автора.

## 2. Входы и конфликтные требования

До затронутого этапа Gravity создаёт `execution/decisions.md` с колонками ID, source/version/section, варианты, authority, решение, evidence, blocked tasks, supersedes. Не считать старые OPEN автоматически закрытыми более поздним архитектурным текстом без подтверждения полномочий.

| ID | Что требуется | Почему и какой этап блокирует |
|---|---|---|
| INPUT-01 | Approved MVP scope: INCLUDED/DEFERRED для всех 16 CAP | Нельзя самостоятельно сократить приложение до harness или убрать editor; блокирует A/P0 |
| INPUT-02 | Решения DR-01…DR-23 либо source-backed supersession | UFS — freeze candidate; блокирует только зависимые CAP, не всю разработку H |
| INPUT-03 | OCR max width/height/formats/orientation/import policy; supported languages/scripts | S3 qualification и реальная multilingual/offline приёмка |
| INPUT-04 | P_MAX, raster и render envelope | S4 qualification; synthetic fixture не заменяет продуктовые пределы |
| INPUT-05 | Intended production provider tuples, grants/access class и воспроизводимый known-failure setup | S5 qualification; доступный provider не равен полному scope |
| INPUT-06 | Architecture brief и принятые PD/утверждения Product Contract, если существуют | Registry ссылается на отсутствующий brief; помогают установить authority/supersession |
| INPUT-07 | Physical Xiaomi unit/serial, tuple, SAF destination | Q; запрашивать физическое действие только в нужный момент |
| CONFLICT-01 | Retention: UFS BR-016-04 system activity/expiry против F-15 user activity/eligibility | Влияет на DR-23, cleanup, P5; явно подтвердить продуктовую семантику |
| CONFLICT-02 | Page immediately visible после shutter против F-05 persistent Page только после metadata commit | Различить transient preview и persistent Page с одобренным UX; P1 |
| CONFLICT-03 | Full PDF+JPG output success и Retry против F-13 UnknownOutcome/no blind retry | Complete user save возможно только после доказанного успеха всех outputs; уточнить user recovery при partial/unknown; P4 |
| CONFLICT-04 | Registry PARAMETER-P01/GAP-G01 OPEN против F-08 Android 10+/ARM64 и F-10 volatile draft | Не называть оба «platform facts»: GAP-G01 — продуктовая гарантия dirty state. Подтвердить authority; A/P0/P2 |

Закрытие INPUT-02 ведётся поштучно: DR-01 camera position; 02 import position; 03 low space UX; 04 usable text; 05 quality thresholds; 06 post-edit quality; 07 retake availability; 08 final OCR failure; 09 Select all; 10 strip reorder; 11 Undo duration; 12 Active after deletion; 13 fallback granularity; 14 JPEG limits; 15 naming; 16 viewer boundaries; 17 empty recent; 18 recent swipe; 19 editor destination/name; 20 empty editor Save; 21 dirty editor exit; 22 partial materialization; 23 retention events. Не просить повторно решать пункт, для которого найдено подтверждённое owner decision.

`platform-constraints-audit.txt` содержит 35 смысловых пунктов. Сделать реестр всех 35: VERIFIED / NEEDS FACT / NOT APPLICABLE с обоснованием применимости и источниками. Для применимых требований проверять официальные Android/SDK/library docs или эксперимент на target tuple. Приоритет: permissions/lifecycle, SAF/grants/naming/free-space/multi-file outcomes, background/process death, PDF text extraction/generation, OCR script coverage, native dependency compatibility. Консенсус моделей не является доказательством.

## 3. Контрольный пакет, который Gravity обязан реализовать

Создать пути (допускается эквивалентная структура с documented mapping):

| Путь | Содержание |
|---|---|
| `execution/tasks.json` | Все tasks/gates, deps, source IDs, status, commit, evidence links, blocker |
| `execution/traceability.csv` | CAP/BR/AC или protocol clause → task → code symbol → test → run → outcome |
| `execution/decisions.md` | Решения/конфликты/authority; отдельно от измерений |
| `execution/handoff.md` | Current commit, active gate, команды resume, blockers, последние результаты |
| `host/` | Runner, evaluator, schemas, unit tests, CLI reference |
| `configs/` | DEV fixture profile, frozen count registry, qualification input template без fake values |
| `results/<mode>/<cycle_id>/` | Manifest, per-run JSON, logs, dumps, checksums, artifacts и aggregates |

Task statuses: TODO/IN_PROGRESS/BLOCKED/DONE. Spike outcomes: только PASS/FAIL/INCOMPLETE. Отдельно хранить `harness_validation` и `qualification_outcome`: negative test с ожидаемым FAIL может подтверждать исправность evaluator, но не qualification PASS.

DONE разрешён только при наличии source ID, реализованного symbol/path, команды проверки, exit code, timestamp, commit/build hash и существующей evidence. Evidence path должен разрешаться внутри package; проверять SHA-256 и отсутствие broken references. Unchecked/omitted task блокирует dependent gate. Само наличие файла/строки hook не доказывает реализацию.

Реализовать команды со стабильной документацией: `audit`, `build`, `run-case`, `run-suite`, `resume`, `validate`, `package`, `verify-gates`. Каждая принимает подходящие mode/cycle/serial параметры. `verify-gates` проверяет полноту registry/traceability/deps/evidence и frozen source hashes; non-zero при незакрытом required gate. Отсутствие report не эквивалентно нулю нарушений.

Хранить expected case set и observed case set отдельно. По каждому D/R hook, workload, fixture и provider scenario сравнивать уникальные IDs и точные counts. Не засчитывать повторный run дважды. Не выбрасывать неудачные attempts после успешного rerun: журнал append-only, rerun с новым ID и ссылкой на оригинал. Смена APK/fingerprint создаёт новый cycle; DEV не копируется в Q.

## 4. Обязательное закрытие дефектов исходного S1

| ID | Изменение | Проверка, предотвращающая ложный PASS |
|---|---|---|
| H-F01 | Проверять bind/start/hook/kill/restart как отдельные подтверждённые события | Bind failure, timeout и unrelated crash не считаются completed crash case |
| H-F02 | Связать barrier с run_id, operation_id, PID и process start identity; пауза до kill | Неподходящий hook/PID/старый log отвергаются; сохранены kill evidence и новый процесс после restart |
| H-F03 | Устранить «выжил основной процесс, умер только service» как единственное покрытие | Host убивает процесс под тестом со всей относящейся runtime authority; snapshot снимается после нового app invocation |
| H-F04 | Реальная telemetry вместо 1L/total run duration | D1→D5 и TX duration вычислены из stage timestamps; смерть до endpoint → unavailable/censored с причиной, не 0/1 ms |
| H-F05 | Реальные APK SHA, filesystem и MemTotal | Hash вычисляет host по installed artifact; filesystem измерен для app-private path; RAM из /proc/meminfo, не heap |
| H-F06 | Read-only diagnostics без swallowed exceptions | DB read error сохраняется явно; seed/schema подготовлены до kill; snapshot не запускает app migration/repair |
| H-F07 | Единый evaluator и агрегирование из его findings | Corrupt bytes D6, partial COMMITTED/ref/owner D7 и прочие нарушения не теряются в отдельных counter paths |
| H-F08 | Fail-closed schema upgrade | Supported migration сохраняет rows/artifacts; unsupported version не вызывает DROP/reset, доступен documented recovery |
| H-F09 | Clean seed и ресурсы | Reset только case sandbox, handles закрываются; repeated suite не накапливает старые rows/fixtures |
| H-F10 | Сохранение evidence до cleanup/uninstall | Host pull+hash validation завершены раньше очистки; raw_log_location — реальный сохранённый файл |

Process death одного вспомогательного процесса полезна как дополнительный тест; сама по себе не покрывает protocol restart приложения. DB engine rollback при открытии после crash отличается от прикладной repair/migration: документировать точку snapshot и не требовать обходить необходимую SQLite recovery.

## 5. Negative tests и процедуры spikes

Для каждого evaluator добавить fixture корректного состояния и по fixture на каждое frozen нарушение. Обязательны также missing evidence, truncated JSON, wrong hash/mode/tuple, duplicate run, wrong hook, diagnostic error. Проверять приоритет: наблюдённый kill-condition даёт FAIL даже при неполноте; missing data без наблюдённого violation даёт INCOMPLETE. Completeness хранится отдельно и всё равно блокирует переход. Не заменять production отрицательными fixtures: это unit tests evaluator.

S2 DEV должен вызывать реальный camera pipeline, S3 — реальный OCR adapter, S4 — создавать PDF с независимой проверкой, S5 — доступный real provider для integration. Test doubles используются только в явно помеченных unit tests. Нет SDK/provider/device → BLOCKED соответствующая integration task, а не DONE.

S6 registry содержит все 41 точных имени из §8.4, individual oracle и fixture, а не один no-op для группы. Seed включает настоящие persistent records/files. Порядок: seed → operation → exact hook → kill → restart → startup barrier → dump1/oracle → recovery1 → dump2 → recovery2 → dump3/idempotency. В S1 snapshot берётся до прикладной recovery. Startup barrier S6 нельзя запретить общим правилом «никаких mutations».

Квалификационные количества: S1 800; S2 6,000; S3 1,000 на fixture ×3 плюс 500 contention sets; S4 50 × число уникальных точек +200; S5 400 на intended tuple; S6 4,100. DEV профиль S2–S5 фиксируется до запуска с rationale покрытия; производственные пределы из него не выводятся.

## 6. Продуктовое ТЗ после gate A

Следующая таблица задаёт обязательные задачи и acceptance для P. Для каждой INCLUDED CAP импортировать ВСЕ BR/AC из approved UFS в traceability; примеры ниже не сокращают оригинальные AC. Технические interfaces/schema уточняет reviewed Implementation Architecture. До A не переносить учебную S1 schema автоматически в production.

| CAP / этап | Что реализовать | Обязательная проверка |
|---|---|---|
| 001 / P1 | Camera foreground/lifecycle, permission/denial/settings, serial capture, durable Page, haptic request | Следующий shutter при незавершённой обработке; permission denial/disconnect; kill до/после metadata commit |
| 002 / P3 | SAF images/PDF, source protection, progressive import, tuple idempotency, text-layer reuse | Kill после страницы и resume: нет duplicate; source grant lost; malformed/encrypted PDF; block order по DR-02 |
| 003 / P2 | Edge/crop/perspective/orientation/enhancement/quality pipeline, Ready/Low quality/Failed/Retry | Fixture corpus с approved DR-05; glare/blur/crop failures, retry, UI без endless Processing |
| 004 / P2 | Retake same Page ID, new immutable version, revision fencing | Position/selection сохраняются; старый OCR завершается поздно и не становится current |
| 005 / P2 | Auto OCR capture/import, explicit Re-OCR, local supported scripts, version-bound result | Failed re-OCR сохраняет прежний valid result; cold offline scenario; search/select/copy текста на принятом языке |
| 006 / P1 | Active отдельно от Selected, checkbox, auto-select только по rules, Select all | Processing/Low quality/Failed не получают неподтверждённый default; Retake сохраняет selection |
| 007 / P1 | Reorder с единым order для всех views и haptic request | Noncontiguous selection неизменна; OCR не перезапущен; strip scope по DR-10 |
| 008 / P5 | Direct/batch Delete, confirmed Clear, Undo, tombstone/fencing | selection=0 batch disabled; Clear disabled во время Save; поздняя обработка не воскрешает Page; Undo возвращает position/metadata |
| 009 / P4 | Save freeze, preparation wait, approved image-only fallback, PDF+JPG set | Edit/Retake после freeze не меняет output; independent PDF validation/text extraction; partial export не удаляет workspace |
| 010 / P4 | SAF destination, naming/collision, common basename, no overwrite | Реальный provider с collision/revoked grant; фактические имена проверены; несовместимый provider не объявлен поддержанным |
| 011 / P1 | Viewer/current page, navigation, cross-view Active | Границы swipe по DR-16, delete-active по DR-12, dirty navigation передаётся editor guard |
| 012 / P2 | Rotate/Crop/Brightness/Contrast/Sharpness, preview, multi-step Undo, dirty Save guard | Undo до clean отключает Save; Back/close/navigation показывают Cancel/Save; new version invalidates OCR и не auto OCR |
| 013 / P6 | 24h recent index, preview, page/document navigation | Empty index не сканирует filesystem; missing URI не выглядит успехом; empty/boundary UX по DR-17/18 |
| 014 / P6 | Temporary saved PDF editor, local selection, delete/reorder/Undo, save new PDF | Save всех remaining pages независимо от selection; hash source неизменен; empty/dirty exit по DR-19…21 |
| 015 / P6 | Try again выбранных/всех remaining pages, ordered block, new IDs/provenance, Selected=true | Double tap suppressed; intentional repeat создаёт новый block; source unchanged; partial failure по DR-22 |
| 016 / P5 | Approved session retention, protected cleanup, restart recovery | Clock rollback/reboot, active/protected data, no early deletion; saved files сохранены; semantics по resolved CONFLICT-01 |

P0 обязателен для всех: единственная transactional authority; общий conditional commit с revision/version/tombstone/desired checks; immutable versions; durable workflows только у irreversible boundaries; один heavy permit и 96 MiB decoded accounting; bounded reconciliation; supported version horizons без destructive fallback. Контракты проверять на persistent state, а не boolean mocks.

P4 отделяет local document commit от external publication и user Save completion. Никакой universal multi-file atomic SAF promise. При partial/unknown workspace сохраняется; повтор записи только по approved explicit flow. Все связанные JPG используют принятый basename и frozen order, existing output не перезаписывается.

P7: installable release APK с version/hash; documented signing без ключей в Git; fresh install и supported upgrade; declared min API/ARM64 compatibility отдельно от empirical Xiaomi qualification; проверка native dependencies для выбранных targets; TalkBack/contentDescription и ≥48dp targets; сценарии малой памяти/места, process death, permission loss, недоступного provider, offline OCR. Для качества/latency без frozen numeric threshold получить approved acceptance target или оставить BLOCKED; не назначать числа постфактум.

## 7. Gates и handoff

H/B0–B9 закрываются не общим JUnit green, а выполненными tasks и evidence. Незаполненные qualification inputs допускают завершённые DEV mechanics, но исключают qualification-ready. Q выдаёт полный package docs 05, включая явные INCOMPLETE и blockers, после чего STOP. A проверяет protocol completion и рассматривает FAIL; наличие FAIL не принимается как разрешение выпускать продукт с дефектом.

Для P0–P7 gate: все INCLUDED AC этапа DONE, dependencies accepted, negative scenarios исполнены, release-blocking defects отсутствуют, independent reviewer verdict сохранён. Рецензент должен проверить исходники и raw evidence; если reviewer недоступен, статус REVIEW_REQUIRED, не вымышленное одобрение.

После каждого task: build/test при необходимости, обновление версии при изменениях кода во всех связанных файлах, documentation, selective commit. Перед рискованным экспериментом tag и branch. При возобновлении: git status, ledger/handoff, проверить commit/evidence hashes, продолжить первую незавершённую зависимость. Failed test исправлять с новым прогоном, не ослаблять assertions. Не смешивать внешние user changes с task commit.

Итоговый product handoff содержит approved scope/decisions, traceability, release APK+SHA256, build/run instructions, actual test results, unresolved limitations/support tuple, user acceptance сценарии и reviewer verdict. Пустой или демонстрационный экран не является выполнением CAP. Финальная декларация «MVP готов» допустима только после P7.
