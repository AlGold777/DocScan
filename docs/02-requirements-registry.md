Version: 1.1

# Document Scanner — Requirements Registry

Механическая атомизация финализированного `architecture-brief.md`. Формулировки AB-пунктов перенесены без добавления новых требований, интерпретаций или уточнений.

## 1. Goal / User outcomes

- **AB-1-01** — Пользователь может получать рабочие Page из camera capture, выбранных изображений и страниц PDF; после попадания в workspace эти источники подчиняются одинаковым применимым пользовательским правилам. (Source: Functional Map §§1, 3, 10, 24.)
- **AB-1-02** — После shutter новая Page сразу появляется в workspace, а пользователь может сделать следующий снимок, не ожидая crop, image processing или OCR предыдущей Page. (Source: Functional Map §4; UFS BR-001-01…03.)
- **AB-1-03** — Shutter и завершение Drag & Drop reorder сопровождаются лёгким haptic feedback. (Source: Functional Map §§4, 12; UFS BR-001-04, BR-007-02.)
- **AB-1-04** — Большой импортированный PDF становится доступен прогрессивно: готовые Page появляются до завершения всего импорта, progress видим, а UI остаётся доступным. (Source: Functional Map §10; UFS BR-002-06.)
- **AB-1-05** — Page имеет пользовательски наблюдаемые состояния Processing, Ready, Low quality, Failed и Saving в соответствующих сценариях; нормальный результат автоматической подготовки не требует ручного подтверждения, а Failed показывает Retry вместо бесконечного ожидания. (Source: Functional Map §§5–7; UFS BR-003-03, BR-003-07, BR-009-12.)
- **AB-1-06** — OCR первичного capture/import запускается автоматически, не блокирует capture/UI, выполняется локально для основного сценария, поддерживает multilingual recognition с automatic language detection и связывается с конкретной сохранённой image version. (Source: Functional Map §§1, 8; UFS BR-005-01, BR-005-02, BR-005-11.)
- **AB-1-07** — Searchable PDF сохраняет визуальную структуру Page и предоставляет текст для search, selection, copy и machine analysis; OCR другой image version не может считаться валидным для current version. (Source: Functional Map §9; UFS BR-005-09…10.)
- **AB-1-08** — Пользователь может независимо управлять Active Page, explicit selection и workspace order, удалять/восстанавливать unsaved Page, переснимать Page и выполнять предусмотренные graphical edits без скрытого изменения остальных этих состояний. (Source: Functional Map §§6, 11–13, 17–18, 26.)
- **AB-1-09** — Новая Page текущей session auto-select только после успешного Ready; Low quality и Failed не auto-select; успешно подготовленные imported Page auto-select; Page, возвращённые через Try again, создаются Selected=true; Retake сохраняет existing selection. (Source: Functional Map §§6, 11, 29; UFS BR-006-04…05, BR-006-08, BR-004-02.)
- **AB-1-10** — Workspace Save при selection ≥ 1 создаёт один PDF и соответствующий комплект JPG; Page saved selection удаляются из workspace только после успешного завершения полного output set, а при failure workspace остаётся доступным для Retry. (Source: Functional Map §§14–15, 24; UFS BR-009-01…09.)
- **AB-1-11** — Пользователь может открыть recent saved PDF, перемещаться по recent documents и страницам, временно удалить/переставить Page в Saved PDF Editor и сохранить новый PDF без automatic overwrite original. (Source: Functional Map §§19–21, 28; UFS CAP-013, CAP-014.)
- **AB-1-12** — Try again возвращает selected Page либо, при отсутствии explicit editor selection, все remaining Page текущего EditorSession как новые workspace Page для обычной дальнейшей работы, не изменяя source saved PDF. (Source: Functional Map §§20, 27, 29; UFS BR-015-01, BR-015-11.)
- **AB-1-13** — Unsaved workspace действует в рамках 24-hour inactivity-based retention и восстанавливается после restart/process death по зафиксированным правилам; saved PDF/JPG этим cleanup не затрагиваются. (Source: Functional Map §§16, 24; UFS CAP-016.)
- **AB-1-14** — Основные touch targets имеют размер не менее 48dp; предусмотрены корректные contentDescription/TalkBack, а пользовательское состояние не передаётся только цветом. Опциональная защита чувствительного содержимого от screenshots/Recents остаётся optional capability. (Source: Functional Map §22.)

## 2. Hard platform constraints

_Нет AB-пунктов в финализированном brief._

## 3. Operational scenarios

- **AB-3-01** — Serial capture: пользователь делает несколько shutter подряд; каждая Page создаётся и добавляется в workspace немедленно, предыдущие Page могут оставаться Processing/OCR, следующий capture их не ждёт. Позиция новой camera Page в workspace order остаётся открытой по DR-01. (Source: UFS CAP-001.)
- **AB-3-02** — Camera permission: camera permission, denial и Don’t ask again обрабатываются явно; при Don’t ask again доступен Open Settings. Permission denial не обходится скрытым альтернативным capture flow. (Source: Functional Map §22; UFS BR-001-05 и CAP-001 edge cases.)
- **AB-3-03** — Import: пользователь выбирает через Android File Picker одно/несколько изображений и/или PDF; каждое изображение и каждая PDF page становятся отдельной Page. Большой PDF materialize прогрессивно, UI не блокируется; перед тяжёлым импортом выполняется free-space check. Положение imported block — DR-02, UX отрицательного free-space check — DR-03, критерий usable existing PDF text layer — DR-04. (Source: Functional Map §§10, 15; UFS BR-002-01…09.)
- **AB-3-04** — Import recovery: после crash/process death уже materialized Page не теряются и не дублируются; continuation начинается с первой ещё не materialized PDF page. Для иной незавершённой Page-processing работы после process death должен наступить recovery/restart либо recoverable failure; фиктивный бесконечный Processing запрещён. (Source: Functional Map §§7, 10; UFS BR-002-07, BR-003-08.)
- **AB-3-05** — Automatic preparation / quality: primary capture/import проходит edge detection → crop → perspective correction → rotation detection → image enhancement → quality check → OCR. Quality check минимум оценивает blur/резкость, motion blur, glare, exposure, contrast, crop completeness, perspective и resolution. Нормальный результат → Ready без confirmation; Low quality остаётся в workspace и даёт Retake/Rotate/Crop; Failed → Retry. Thresholds Ready vs Low quality, re-evaluation после manual Rotate/Crop и Retake вне Low quality остаются DR-05/06/07. (Source: Functional Map §§5–7; UFS BR-003-01…08.)
- **AB-3-06** — Retake / Re-OCR: Retake заменяет image content той же Page, сохраняя Page identity, position и selection, инвалидирует старые image-dependent processing/OCR/quality results и запускает новый processing/quality/OCR cycle. Re-OCR распознаёт current saved image version, не создаёт новую Page и сохраняет Page identity/position/selection; failed Re-OCR не уничтожает previous OCR той же image version. Final OCR failure остаётся DR-08. (Source: Functional Map §§6, 8; UFS CAP-004, BR-005-05…08.)
- **AB-3-07** — Graphical edit: Rotate/Crop/Brightness/Contrast/Sharpness изменяют preview и создают dirty editing state; multi-step Undo действует до последнего graphical Save. Undo всех dirty changes возвращает clean state и отключает Save. Graphical Save создаёт новую current image version той же Page, очищает Undo stack и не запускает OCR автоматически; valid OCR для новой version появляется только после explicit Re-OCR. При close/Page navigation/Android Back с dirty changes показывается только `Save changes? → Cancel | Save`; silent discard и отдельный Discard отсутствуют. (Source: Functional Map §26; UFS BR-012-01…12.)
- **AB-3-08** — Workspace selection/order: tap thumbnail меняет Active; checkbox меняет Selected. Selection допускает non-contiguous Page и Select all. Reorder меняет workspace order, синхронно отражается во всех workspace views, не меняет selection и не запускает OCR. Scope Select all — DR-09; обязательность reorder через upper thumbnail strip — DR-10. (Source: Functional Map §§11–12; UFS CAP-006, CAP-007.)
- **AB-3-09** — Workspace Delete/Clear: direct Delete удаляет target Page; batch Delete действует только на explicit selection и disabled при selection=0, Active не является implicit target. Undo Delete восстанавливает Page, position, selection, OCR и metadata. Clear требует confirmation, удаляет все unsaved Page current workspace и предоставляет Undo; во время active PDF Save Clear disabled, во время обычного Processing разрешён. При Clear during processing связанная незавершённая работа прекращается по предусмотренным правилам, её поздние результаты не должны возвращать удалённую Page или менять её state, после чего удаляются соответствующие temporary data. Duration Undo — DR-11; Active после удаления current Active — DR-12. (Source: Functional Map §13; UFS BR-008-01…08.)
- **AB-3-10** — Workspace Save readiness: Save доступен при selection ≥ 1. Selected Processing Page заставляет Save ждать предусмотренной подготовки с user-visible status. Selected Low quality/Failed/no-valid-OCR Page требуют correction/Re-OCR либо explicit image-only fallback. Критерий final OCR failure — DR-08; fallback granularity/mixed semantics — DR-13; JPEG quantitative target — DR-14. Перед тяжёлым Save выполняется free-space check; user behavior при недостатке места остаётся DR-03. (Source: Functional Map §§14–15; UFS BR-009-01…05, BR-009-13…14.)
- **AB-3-11** — Workspace Save output: one Selected Page → `<name>.pdf` + `<name>.jpg`; multiple → `<name>.pdf` + `<name> — 01.jpg`, `02.jpg` и т.д. JPG берутся из current image versions и нумеруются по final PDF order. Existing files не overwrite; collisions используют suffix `(1)`, `(2)…`, связанные JPG используют тот же resolved basename. Semantic auto-name/exact fallback date-time остаются DR-15. При failure полный output set не считается успешно опубликованным, workspace не меняется и Retry доступен; Unselected Page остаются. Destination выбирается через SAF и при утрате доступа запрашивается повторно. (Source: Functional Map §§14–15; UFS BR-009-07…14, BR-010-01…08.)
- **AB-3-12** — Workspace retention/recovery: WorkspaceSession истекает через 24 часа inactivity; все Page session разделяют общий deadline. Существенная activity обновляет session timestamp; пока Page/session открыта, редактируется, выбрана либо участвует в pending/running work, cleanup её не удаляет. Если удаление требует прекращения незавершённой работы, работа сначала безопасно прекращается, затем удаляются temporary data. После restart/process death восстанавливаются Page, order, selection, source type, image version, OCR/quality status, timestamps и unfinished-work state. Exact exhaustive list activity events — DR-23. (Source: Functional Map §16; UFS BR-016-01…08.)
- **AB-3-13** — Recent saved navigation: 24-hour recent index содержит успешно сохранённые приложением documents и не является permanent library. Preview открывает последний recent document; horizontal swipe меняет Page current PDF, vertical swipe — recent document; при смене document thumbnail strip заменяется Page нового document. Empty index не инициирует filesystem-wide discovery старых PDF. Empty preview UI — DR-17; direction/boundaries — DR-18. (Source: Functional Map §§19, 21; UFS BR-013-01…08.)
- **AB-3-14** — Saved PDF Editor: editor selection локален current saved PDF и не переносится между recent documents. Delete применяется только к explicitly selected Page и disabled при selection=0; Undo восстанавливает Page в previous position и с previous selection, а Active — по зафиксированным условиям. Drag & Drop меняет temporary editor order. Save создаёт новый PDF из всех remaining Page current EditorSession в current order; editor selection не является Save-filter; original PDF не overwrite. Destination/naming нового PDF — DR-19; Save при empty EditorSession — DR-20; close/back при unsaved editor changes — DR-21. (Source: Functional Map §§20, 28; UFS BR-014-01…12.)
- **AB-3-15** — Try again: при explicit editor selection materialize только selected remaining Page; без explicit selection — все remaining Page. Materialization использует current EditorSession order; каждая returned Page получает новый workspace Page ID и source provenance, создаётся Selected=true, а usable source text layer переносится как text/OCR current imported version до manual image change. Во время active materialization действие disabled; после завершения повторный Try again разрешён и создаёт новые Page без automatic deduplication. Existing workspace получает весь returned block одним последовательным block в конце; если workspace отсутствует — создаётся новая WorkspaceSession. Existing session → returned Page наследуют её retention deadline; new session → весь block получает общий новый TTL. Source saved PDF остаётся неизменным, Try again не является Re-OCR. Materialization failure semantics — DR-22. (Source: Functional Map §§20, 27, 29; UFS BR-015-01…11.)

## 4. Failure symptoms

- **AB-4-01** — Во входных authoritative sources отсутствуют описания реально наблюдавшихся user-facing багов. Реальные failure symptoms для этого раздела не предоставлены.
- **AB-4-02** — Failed, permission denial, destination-access loss, save/import failure, process death и другие failure paths из требований являются обязательными сценариями проектирования/тестирования, но не должны выдаваться за уже наблюдавшиеся реальные баги.

## 5. Required invariants

- **AB-5-01** — No silent data loss: Save, Clear, crash/process death или retention cleanup не должны незаметно уничтожать unsaved Page до expiry применимой WorkspaceSession либо explicit confirmed deletion. (Source: Functional Map §24.)
- **AB-5-02** — Searchability consistency: output не может считаться searchable, если у включённой Page нет valid OCR для её current image version; допустим только предусмотренный explicit image-only path. (Source: Functional Map §§9, 24.)
- **AB-5-03** — Selection ≠ Order: checkbox/selection определяет состав workspace PDF, workspace order — последовательность выбранных Page. Изменение одного не должно неявно менять другое. (Source: Functional Map §§3, 11–12, 24.)
- **AB-5-04** — Source neutrality: camera/image/OCR-less PDF/searchable PDF/Try-again Page после попадания в workspace подчиняются одинаковым применимым workspace rules. (Source: Functional Map §§10, 24, 29.)
- **AB-5-05** — Save commits only on success: Page saved selection удаляются из workspace только после successful completion полного пользовательского PDF+JPG output set; failure не удаляет их и не считается успешной публикацией partial set. (Source: Functional Map §§14, 24.)
- **AB-5-06** — Cross-view consistency: пользователь не должен наблюдать разные workspace order, selection, Active/state значения в предусмотренных views одного workspace. (Source: Functional Map §§11–12, 18, 24.)
- **AB-5-07** — Serial responsiveness: создание новой Page никогда не ждёт завершения processing/OCR ранее созданной Page. (Source: Functional Map §§4, 24.)
- **AB-5-08** — Saved PDF original immutability: Saved PDF Editor и Try again не изменяют original saved PDF; Editor Save создаёт новый PDF и не выполняет automatic overwrite original. (Source: Functional Map §§24, 27–29.)
- **AB-5-09** — OCR validity: OCR-result валиден только для image version, на которой он получен; graphical Save не переносит valid OCR previous version на новую current version. (Source: Functional Map §§8–9, 24, 26.)
- **AB-5-10** — Retake identity: Retake заменяет image content той же Page, сохраняет Page ID/position/selection и делает previous image-dependent processing/OCR/quality results невалидными для нового content. (Source: Functional Map §6.)
- **AB-5-11** — Recovery correctness: restart/process death не должен приводить к потере уже materialized Page, дублированию уже materialized PDF pages или фиктивному endless Processing. (Source: Functional Map §§7, 10, 24.)
- **AB-5-12** — Session retention: все Page одной WorkspaceSession разделяют один 24-hour inactivity-based deadline; отдельная Page не получает независимый TTL, позволяющий ей пережить session. Saved PDF/JPG этим cleanup не удаляются. (Source: Functional Map §16.)
- **AB-5-13** — Active/selected/pending work protection: cleanup не удаляет Page, которая по действующим product rules защищена active/edit/selection/pending-running-work состоянием; требуемое прекращение незавершённой работы предшествует удалению соответствующих temporary data. (Source: Functional Map §16; UFS BR-016-05…06.)
- **AB-5-14** — Delete/Clear isolation: workspace Delete/Clear не изменяют saved PDF/JPG или recent saved metadata; late results ранее прекращённой работы не могут вернуть удалённую Page или изменить её state. (Source: Functional Map §13; UFS BR-008-06…08.)
- **AB-5-15** — Naming/output correspondence: existing user files не overwrite; PDF/JPG одного output используют согласованный basename, JPG numbering соответствует final PDF page order, а JPG берутся из current image versions. (Source: Functional Map §15.)
- **AB-5-16** — Repeated-action semantics: repeated Save tap во время активного Save не создаёт duplicate output; Try again не запускается concurrently повторным tap, но после завершения может быть выполнен снова и создать новые Page без automatic deduplication. (Source: Functional Map §§14, 29.)
- **AB-5-17** — Permission flow integrity: camera permission denial не обходится скрытым альтернативным capture flow; Don’t ask again имеет предусмотренный user-visible Open Settings path. (Source: Functional Map §22; UFS CAP-001 edge cases.)
- **AB-5-18** — Dirty-edit integrity: unsaved graphical changes не теряются молча при предусмотренной close/Page-navigation/Android-Back попытке; действует только зафиксированный `Cancel | Save` decision flow. (Source: Functional Map §26.)
- **AB-5-19** — Storage permission scope: файловый доступ использует предусмотренные modern Android SAF/API без избыточного broad-storage permission. (Source: Functional Map §22.)
- **AB-5-20** — Core offline property: OCR и document processing основного сценария выполняются локально; сеть не является обязательной для основного пользовательского потока. (Source: Functional Map §1.)
- **AB-5-21** — Мета-инвариант: архитектура не должна выдавать эвристику, оценку или best-effort поведение за гарантию. Если требуемую гарантию невозможно доказать в данной platform/external-provider situation, система должна явно обозначить границу и перейти в явное неопределённое/ошибочное/recoverable состояние вместо ложного success. (Directive of this architecture-brief task.)

## 6. Conceptual information

- **AB-6-01** — Page: пользовательски значимая рабочая страница из camera capture, image, PDF page либо Try again; в пределах workspace lifecycle требуется stable identity, а для источника — provenance. (Source: Functional Map §§3, 10, 29.)
- **AB-6-02** — Workspace: временная рабочая область Page, в которой независимо существуют current order, Active Page и explicit Selected-state; workspace PDF определяется Selected Page в их current relative order. (Source: Functional Map §§1, 3, 11–12.)
- **AB-6-03** — WorkspaceSession: группа unsaved Page с общим inactivity-based retention deadline; все Page session разделяют этот deadline. (Source: Functional Map §§3, 16.)
- **AB-6-04** — Image version / dirty edit: у Page может существовать последовательность сохранённых image versions, одна из которых current; unsaved graphical dirty state отличается от последней сохранённой image version. (Source: Functional Map §§23, 26; UFS CAP-012.)
- **AB-6-05** — OCR information: OCR-result относится к конкретной image version; previous OCR может существовать как информация о previous version, но не является автоматически valid для новой current version. (Source: Functional Map §§8–9, 24.)
- **AB-6-06** — Quality/processing information: Page имеет user-visible processing/quality state и отдельно признак valid OCR current image version. (Source: Functional Map §§7–8.)
- **AB-6-07** — Active / Selected / order: Active определяет показываемую Page, Selected — участие в workspace PDF, order — последовательность; эти отношения независимы там, где требования это задают. (Source: Functional Map §§3, 11–12.)
- **AB-6-08** — Recovery information: для зафиксированного recovery нужно различать уже materialized/processed результат, незавершённую работу, timestamps/retention context и user-visible outcome; brief не определяет форму внутреннего хранения этих данных. (Source: Functional Map §§10, 16.)
- **AB-6-09** — Save relation: workspace Save использует selection, current order, current image versions, applicable OCR/fallback state, destination и resolved names; successful user output состоит из одного PDF и соответствующего набора JPG. (Source: Functional Map §§14–15.)
- **AB-6-10** — Destination/naming information: существует выбранная destination, состояние доступности этой destination и согласованный basename/filenames output; ранее доступная destination может потребовать повторного выбора. (Source: Functional Map §15.)
- **AB-6-11** — Recent saved information: существует временный 24-hour index успешно сохранённых приложением documents, достаточный для preview/navigation/Try again; это не permanent document library/manager. (Source: Functional Map §§19, 21.)
- **AB-6-12** — Saved PDF editing context: во время редактирования saved PDF существует временное состояние current composition/order/selection/Active, отличное от original saved PDF; original остаётся неизменным. (Source: Functional Map §§20, 28.)
- **AB-6-13** — Try-again provenance: returned Page является новой workspace Page с новой identity и сохраняет provenance source saved PDF/page; usable source text layer может быть перенесён по правилам CAP-015. (Source: Functional Map §29.)
- **AB-6-14** — OPEN DR-01 — позиция новой camera Page в workspace order не решена.
- **AB-6-15** — OPEN DR-02 — положение и взаимный order нового imported block относительно existing workspace Page не решены.
- **AB-6-16** — OPEN DR-03 — user-visible поведение при insufficient/negative free-space check не решено.
- **AB-6-17** — OPEN DR-04 — критерий usable existing PDF text layer не решён.
- **AB-6-18** — OPEN DR-05 — thresholds/decision rule Ready vs Low quality не заданы.
- **AB-6-19** — OPEN DR-06 — автоматическая переоценка quality status после сохранённого manual Rotate/Crop не решена.
- **AB-6-20** — OPEN DR-07 — доступность Retake вне Low quality не решена.
- **AB-6-21** — OPEN DR-08 — критерий final OCR failure не решён.
- **AB-6-22** — OPEN DR-09 — точный scope Select all по Page states/sessions не решён.
- **AB-6-23** — OPEN DR-10 — обязательность reorder через workspace Viewer/upper thumbnail-strip в v1 не решена.
- **AB-6-24** — OPEN DR-11 — точная duration «короткого Undo» не решена.
- **AB-6-25** — OPEN DR-12 — какая Page становится Active после удаления current Active Page, не решено.
- **AB-6-26** — OPEN DR-13 — image-only fallback per-Page vs per-Save и semantics mixed OCR/image-only PDF не решены.
- **AB-6-27** — OPEN DR-14 — quantitative acceptance limits JPEG readability/size/speed balance не заданы.
- **AB-6-28** — OPEN DR-15 — deterministic semantic auto-name и exact `<date time>` fallback format не решены.
- **AB-6-29** — OPEN DR-16 — boundary behaviour horizontal swipe первой/последней workspace Page не решено.
- **AB-6-30** — OPEN DR-17 — empty recent index: preview absent vs neutral placeholder не решено.
- **AB-6-31** — OPEN DR-18 — chronological direction vertical swipe и boundary behaviour recent documents/pages не решены.
- **AB-6-32** — OPEN DR-19 — destination/naming rules нового PDF из Saved PDF Editor не решены.
- **AB-6-33** — OPEN DR-20 — Save availability/behavior при EditorSession без Page не решено.
- **AB-6-34** — OPEN DR-21 — close/back behaviour Saved PDF Editor при unsaved Delete/Reorder changes не решено.
- **AB-6-35** — OPEN DR-22 — Try-again materialization failure: all-or-nothing vs partial result + recovery не решено.
- **AB-6-36** — OPEN DR-23 — exhaustive set событий, считающихся substantial activity и обновляющих session retention timestamp, не задан.
- **AB-6-37** — OPEN SOURCE GAP-G01 — Functional Map/UFS различают unsaved graphical dirty state и последнюю saved image version, но не определяют, обязано ли unsaved dirty state переживать process death. Архитектурное предложение не должно молча выбирать один исход; любое допущение должно быть явно обозначено.
- **AB-6-38** — OPEN PLATFORM PARAMETER-P01 — supported Android API range/minSdk не задан authoritative product sources. Любое допущение архитектурного предложения о диапазоне Android должно быть явно обозначено и не выдаваться за продуктовый requirement.

## 7. Quality priority ordering

- **AB-7-01** — OPEN: quality priority ordering. Authoritative product sources не задают общий порядок приоритетов качества при конфликте; архитектурное предложение должно показать собственные trade-offs, но не представлять выбранное им ordering как product requirement.

## 8. Non-goals

- **AB-8-01** — Приложение не является permanent archive/document manager; permanent storage находится в выбранной пользователем файловой системе. (Source: Functional Map §1.)
- **AB-8-02** — Основной экран не включает поиск, категории, фильтры или permanent internal document library. (Source: Functional Map §1.)
- **AB-8-03** — Empty recent index не запускает filesystem-wide discovery более старых PDF. (Source: Functional Map §19.)
- **AB-8-04** — Graphical editing не включает Resize; предусмотрены только Rotate, Crop, Brightness, Contrast и Sharpness. (Source: Functional Map §26.)
- **AB-8-05** — Saved PDF Editor не выполняет automatic overwrite original saved PDF. (Source: Functional Map §28.)
- **AB-8-06** — Try again не является Re-OCR и не выполняет automatic deduplication повторно materialized Page. (Source: Functional Map §§27, 29.)
- **AB-8-07** — Brief не фиксирует конкретную OCR/PDF library, worker model, queue/concurrency mechanism, storage implementation или database schema; это архитектурные решения, если они не меняют observable product behaviour. (Source: UFS normative rule 5; Functional Map §§23, 30.)
- **AB-8-08** — OPEN: дополнительных non-goals сверх явно подтверждённых выше authoritative sources не задано.

## 9. Existing implementation

- **AB-9-01** — Реализация существует, но не является ограничением для проектирования и не должна наследоваться.

## 10. Expected architectural output

- **AB-10-01** — Дать архитектурную декомпозицию и объяснить ответственность выбранных частей без подмены продуктовых требований внутренними механизмами.
- **AB-10-02** — Для ключевых решений показать traceability к relevant invariants, подтверждённым constraints, operational scenarios и OPEN decisions/parameters.
- **AB-10-03** — Явно назвать главный architectural trade-off выбранного варианта и его худшие свойства/цены, а не только преимущества.
- **AB-10-04** — Рассмотреть и отклонить 2–4 содержательно отличающиеся альтернативы с указанием причин отказа.
- **AB-10-05** — Не закрывать DR-01…DR-23, OPEN SOURCE GAP-G01, OPEN PLATFORM PARAMETER-P01 или другие незакрытые product/platform вопросы молча. Если для проектирования требуется assumption, пометить его как assumption и показать, как оно влияет на архитектуру и какие варианты остаются открытыми.
- **AB-10-06** — Если решение зависит от platform fact, отсутствующего в разделе 2 как confirmed hard constraint, явно пометить этот факт как требующий platform verification; не выдавать model consensus или implementation convenience за подтверждённое ограничение платформы.
- **AB-10-07** — Для требований, зависящих от ненадёжного внешнего destination/provider или результата, требующего подтверждения соответствия, объяснить, как архитектура обеспечивает требуемую проверяемость фактического результата и как различает success, failure и unknown/indeterminate outcome, не навязывая конкретный механизм заранее.
- **AB-10-08** — Для сравнимости предоставить диаграммы/схемы, достаточные для сопоставления: system boundary и external interactions; conceptual information/lifecycle relations; ключевые потоки serial capture, large-PDF recovery и workspace Save success/failure; relevant state/lifecycle transitions. Конкретная нотация и точное количество диаграмм не предписываются.
- **AB-10-09** — Для recovery/retention объяснить, как proposal соблюдает 24-hour session semantics, recovery without duplication, protection active/edit/selected/pending-running work и границу OPEN GAP-G01.
- **AB-10-10** — Для PDF/OCR/save flows объяснить, как proposal сохраняет OCR↔image-version consistency, original-PDF immutability, full-output-set success semantics, naming/no-overwrite rules и explicit image-only fallback boundaries, не закрывая DR-04/08/13/14/15.
