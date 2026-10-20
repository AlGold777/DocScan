# DocScan — v1.2 FROZEN

> **FROZEN — qualification-scope amendment from v1.1, 19.09.2026**
>
> **Parent baseline:** `DocScan v1.1 FROZEN`.  
> **Consistency check for v1.2 amendments:** 0 open scope contradictions.  
> **Status:** immutable normative baseline.  
> **Mutation rule:** этот артефакт не редактируется in place. Любое семантическое изменение требует нового versioned baseline и нового freeze stamp.

## Freeze scope

В этот baseline входит полный нормативный content `DocScan v1.1 FROZEN` плюс явно перечисленные v1.2 amendments и их зависимые consistency updates. Никакие иные Core contracts не изменены.

## v1.2 Amendment Record

| ID | Semantic change | Disposition |
|---|---|---|
| **AMEND-QS-01** | Удалена неподтверждаемая в доступной test environment гарантия `weakest supported tier = 4 GB RAM`. Initial empirical qualification scope заменён на один конкретный physical Xiaomi 14 Ultra tuple `QP-X14U-01`; evidence не переносится на другие device/OS/SKU tuples. | **ACCEPTED / FROZEN** |
| **AMEND-DUR-01** | Artifact publication guarantee для первого qualification cycle сужена до **application-process crash persistence** на `QP-X14U-01`. Sudden hardware power-loss durability не является production guarantee этого baseline без отдельной hard-power qualification. | **ACCEPTED / FROZEN** |

---

# Шаг 1 — Product Contract v1.1 carried into `v1.2 FROZEN`

Product Contract является нормативным и прямо требует, чтобы implementation architecture ему соответствовала. Он закрывает Retake Identity, Retention, Concurrency и Compatibility Horizon.

## FROZEN §F-01 — System boundary

DocScan v1.2 — **single-device local document scanner**.

Core гарантирует crash-consistency собственного локального состояния.

Core **не является**:

- distributed database;
- multi-device sync engine;
- transactional wrapper над arbitrary external filesystem;
- generic durable-job orchestration platform.

External filesystem, SAF и cloud-backed providers находятся **за export boundary**.

`[UNRESOLVED BY CONTRACT UC-01]`  
Эта граница перенесена из Conceptual Architecture без отдельного подтверждения Product Contract.

---

## FROZEN §F-02 — Authoritative truth и conditional commit

Единственная authoritative local truth — **transactional metadata state**.

Она определяет:

`Workspace / Page identity / current ImageVersion / order / selection / tombstone / desired processing / committed Recognition / active critical workflow state`.

UI, workers и external resources не являются authority и не изменяют truth напрямую.

Любой async candidate может стать committed result только если **в одной metadata transaction** одновременно доказано:

1. `Page` существует;
2. ожидаемый `contentRevision` всё ещё текущий;
3. ожидаемый `ImageVersionId` всё ещё текущий;
4. Page не tombstoned;
5. соответствующее вычисление/result всё ещё требуется.

Иначе candidate уничтожается логически как stale и не становится authoritative.

`[UNRESOLVED BY CONTRACT UC-02]`

---

## FROZEN §F-03 — Persistent logical model

Минимальная логическая модель содержит:

**Workspace**
- `WorkspaceId`
- `orderRevision`
- page order
- selection
- active page
- retention state

**Page**
- `PageId`
- `contentRevision`
- `currentImageVersionId`
- `processingDesiredState`
- `currentRecognitionId`
- `tombstone`
- revision

**ImageVersion**
- `ImageVersionId`
- `PageId`
- `ArtifactId`
- coordinate space
- optional parent version
- transform provenance
- artifact format version

`PageId` стабилен; committed ImageVersion после commit immutable.

`[UNRESOLVED BY CONTRACT UC-03]`

---

## FROZEN §F-04 — Artifact persistence boundary

Artifact identity — opaque ID/UUID, **не content hash**.

Artifact может иметь size, checksum, format и creation revision.

Ни одна authoritative metadata reference не может указывать на bytes, qualified persistence которых ещё не доказана.

Для baseline v1.2 термин `qualified persistence` означает: после успешного platform publication boundary exact bytes обязаны переживать abrupt **application-process death** и последующий app restart на qualification tuple `QP-X14U-01`.

Sudden hardware power loss / forced kernel reset **не входят в production guarantee v1.2**, пока отдельная hard-power qualification не выполнена и не заморожена новой версией evidence/baseline. Отсутствие такой qualification не разрешает называть power-loss durability `PROVEN`.

Базовый порядок:

`temporary write → encoding complete → platform persistence primitive → artifact identity → metadata transaction referencing artifact`.

Имя persisted state `STAGED_DURABLE` сохраняется для schema compatibility; в v1.2 его нормативный guarantee scope определяется этим F-04.

`[UNRESOLVED BY CONTRACT UC-04]`

`[AMEND-DUR-01]`

---

## FROZEN §F-05 — Capture

Capture:

`camera frame → private temporary artifact → encode/write → qualified persistence primitive → one metadata TX creating Page + ImageVersion V1 → Page visible → desired processing`.

До metadata commit UI может показывать transient preview/shutter animation, но persistent Page ещё не существует.

`PageShell` и `CaptureIncomplete` не входят в normal persistent model.

`[UNRESOLVED BY CONTRACT UC-05]`

---

## FROZEN §F-06 — Recomputable work vs durable workflows

OCR, enhancement, quality analysis, thumbnails и аналогичный recomputable compute:

- не имеют durable execution attempt;
- сохраняют desired operation;
- сохраняют input identity;
- сохраняют только successfully committed result;
- runtime attempt disposable.

После process death новый attempt создаётся из unsatisfied desired state.

Core не содержит generic durable `Running`, leases, checkpoints или recovery FSM для OCR/processing.

Durable lifecycle допустим только там, где существует irreversible boundary:

- Import materialization;
- external Export;
- destructive Cleanup;
- schema migration при необходимости.

Universal Operation FSM отсутствует.

`[UNRESOLVED BY CONTRACT UC-06]`

---

## FROZEN §F-07 — Content fencing

Любая authoritative mutation, инвалидирующая image-dependent work, должна атомарно менять `contentRevision`.

Workers знают только expected revision/version.

Изменение current image обязано проходить через общий semantic mutation/fencing path.

`[UNRESOLVED BY CONTRACT UC-07]`

---

## FROZEN §F-08 — Resource/concurrency and qualification-scope contract

Product implementation target remains:

- Android 10+;
- ARM64.

This target is a design/compatibility scope, **not** an empirical claim that every Android 10+/ARM64 device has been production-qualified.

Initial empirical qualification profile is **`QP-X14U-01`**:

- physical **Xiaomi 14 Ultra**;
- exact manufacturer/model/SKU, installed RAM as observed, Android version/API level, build fingerprint, security patch, kernel, app-private filesystem type, ABI and `/proc/meminfo MemTotal` are recorded before execution;
- all mandatory S1–S6 evidence for one qualification cycle must come from the **same physical unit**, same OS build fingerprint and same app build hash;
- OS update, device replacement, different Xiaomi 14 Ultra SKU or different app build creates a different qualification tuple and cannot silently reuse prior empirical results;
- evidence from `QP-X14U-01` **MUST NOT** be generalized to 4 GB devices, Android 10 devices, other Xiaomi 14 Ultra builds/SKUs or any other hardware without separate qualification.

No `weakest supported tier` RAM claim exists in v1.2. Production support outside the exact qualified tuple requires separate executable qualification evidence.

Resource contract remains:

- dedicated NPU/GPU is not a correctness dependency;
- `maxConcurrentHeavyJobs = 1`;
- decoded-image working-set budget = **96 MiB**;
- exceeding work waits;
- Capture/interactive authoritative metadata work does not wait for heavy processing.

Correctness не зависит от одного worker: revision/version fencing обязательно всегда.

Переход к двум heavy workers разрешён только после benchmark criteria из Product Contract: нужный p95 trigger, ≥25% improvement, Capture regression ≤10%, ≤128 MiB decoded peak, no OOM/ANR и no sustained thermal throttling. Любой такой PASS относится только к явно протестированному qualification tuple, пока отдельная matrix qualification не расширит scope.

Scheduler остаётся bounded executor; более сложные fairness/thermal/resource-governor механизмы не вводятся без измерений.

`[AMEND-QS-01]`

---

## FROZEN §F-09 — Import

До создания `ImportSession` external input обязан:

- либо стать durable app-private source snapshot;
- либо получить доказанно persistent access.

Idempotency domain:

`ImportSessionId + sourcePageIndex`.

Recovery одной ImportSession не создаёт duplicate Page.

Повторный пользовательский import того же исходного PDF — новая ImportSession и новые Pages.

Cross-import content dedup не гарантируется.

`[UNRESOLVED BY CONTRACT UC-08]`

---

## FROZEN §F-10 — Retake / Edit / OCR

### Retake

Retake:

- сохраняет существующий `PageId`;
- создаёт новую immutable `ImageVersion`;
- выполняет `contentRevision++`;
- меняет `currentImageVersionId`;
- сохраняет order и selection.

Предыдущая ImageVersion остаётся **одним Undo target** до более раннего события из:

- следующей content-changing mutation данной Page;
- closure/expiry WorkspaceSession.

После этого она GC-eligible, но только если её не защищает `SaveManifest`, active durable workflow или другая authoritative reference. Permanent version history не является requirement.

Если durable creation/metadata commit новой версии не завершился, current version, PageId, order и selection остаются прежними. Старый OCR/processing candidate не может commit после изменения `contentRevision`.

### Graphical edit

Draft может быть volatile.

Commit edit:

`render candidate → durable artifact → conditional metadata TX → new current ImageVersion`.

### OCR

Recognition принадлежит конкретной ImageVersion.

Неуспешный re-OCR не уничтожает существующий committed Recognition до успешного conditional replacement.

`[UNRESOLVED BY CONTRACT UC-09]` — Edit/OCR semantics. Retake identity уже закрыта Product Contract.

---

## FROZEN §F-11 — Save snapshot

Consistency point = **Save request**.

При Save фиксируются:

- ordered PageIds;
- content revisions;
- ImageVersionIds;
- relevant RecognitionIds / required processing state;
- render parameters.

После freeze `SaveManifest` immutable.

Если frozen version требует OCR/processing, Save ждёт именно эту version.

Retake/Edit после freeze не попадает в данный Save.

Renderer **никогда не читает live Workspace после freeze**.

`[UNRESOLVED BY CONTRACT UC-10]`

---

## FROZEN §F-12 — Internal document commit

Output сначала строится полностью в app-private staging.

После local structural validation он становится `CommittedExportArtifact`.

Только этот локально recoverable object является source для external export.

External provider не участвует в создании документа.

`[UNRESOLVED BY CONTRACT UC-11]`

---

## FROZEN §F-13 — External export

External destination — one-way boundary.

Каждый requested output имеет отдельный `ExportAttempt`; например PDF и JPG page set не образуют единую atomic transaction.

`Exported` означает только successful return provider operation согласно qualified adapter contract.

Это **не** означает perpetual existence.

При неизвестном исходе после crash:

`ExportAttempt = UnknownOutcome`.

Blind automatic retry запрещён.

`CommittedExportArtifact` сохраняется.

Если есть stable external identity — разрешена revalidation; если outcome доказать невозможно — требуется explicit repeat export.

`[UNRESOLVED BY CONTRACT UC-12]`

---

## FROZEN §F-14 — Provider capability/evidence

Capability не является глобальным Boolean.

Qualification scope включает:

`provider adapter + provider/version + OS/version + destination/access class + validation-suite version`.

Runtime сохраняет observation конкретной операции.

Verification evidence многомерно:

`identity / existence / integrity / readability / name / access`.

Они не образуют обязательную единую ordinal шкалу.

`[UNRESOLVED BY CONTRACT UC-13]`

---

## FROZEN §F-15 — Delete / Undo / Retention / Cleanup

Logical Delete:

`metadata TX → tombstone → contentRevision change`.

Physical bytes тем же кодом не удаляются.

Единственный Cleanup Arbiter может удалить bytes только после проверки:

- no authoritative reference;
- no active durable workflow needs artifact;
- undo/grace conditions allow deletion.

Retention = **Mode A / eligibility**.

Workspace data не может быть physically deleted до истечения **24 часов с последней authoritative user activity**.

User activity включает Capture, Import, Retake, graphical Edit, Reorder, Selection change, Delete, Undo и другие authoritative user mutations.

OCR, processing, reconciliation и maintenance не продлевают retention.

Cleanup Arbiter оценивает eligible data:

- при cold start / foreground entry;
- при prolonged active use не чаще одного раза в 6 часов.

24h — не deadline:

- `<24h`: delete forbidden;
- `≥24h`: delete permitted при следующем Cleanup pass.

При ненадёжной оценке elapsed time deletion откладывается.

False retention разрешён; false early deletion запрещён.

Возраст не отменяет protection от:

- authoritative current state;
- frozen `SaveManifest`;
- active recovery state;
- другой authoritative durable reference.

---

## FROZEN §F-16 — Projection/UI

Canonical projection:

- derived;
- non-durable;
- не source of truth.

Она может содержать state, progress, reason, recoverability и available commands.

Available commands вычисляет Application Policy, не State Authority.

`[UNRESOLVED BY CONTRACT UC-14]`

---

## FROZEN §F-17 — Reconciliation

Cold startup не должен сканировать весь persistent universe.

Synchronous startup:

1. open DB;
2. validate/migrate schema;
3. validate minimum global invariants.

Дальше применяется object-local reconciliation.

GC/orphan cleanup/compaction выполняются bounded batches с cursor.

Malformed object может быть isolated без блокировки application launch.

`[UNRESOLVED BY CONTRACT UC-15]`

---

## FROZEN §F-18 — Compatibility horizon

Compatibility разделена на три durable data family:

- `MetadataSchemaVersion`: `N, N-1, N-2`;
- `ArtifactFormatVersion`: `N, N-1, N-2`;
- `DurableWorkflowPayloadVersion`: `N, N-1`.

Unsupported metadata/artifacts:

**quarantine + read-only recovery**, без destructive fallback.

Нельзя silently reinterpret, reset или delete committed user data из-за unsupported migration.

Workflow payload `<N-1`:

- не resumes;
- становится `UnsupportedLegacyWorkflow`;
- reconstructible execution state может быть discarded;
- committed metadata/artifacts сохраняются;
- operation можно начать заново из authoritative state.

---

## FROZEN §F-19 — Policy ownership

Generic runtime Policy Registry отсутствует.

Закрытые product decisions принадлежат Product Contract / build configuration.

Если policy value влияет на durable operation, значение freeze/copy в operation manifest.

Recovery не зависит от сегодняшней global policy.

`[UNRESOLVED BY CONTRACT UC-16]`

---

## FROZEN §F-20 — Verification / production gates

Architectural self-assessment не доказывает guarantee.

`PROVEN` допустим только при наличии executable tests для соответствующей implementation/platform version.

Production gates должны измерять как минимум:

- Capture p95/p99 durable commit;
- burst behaviour;
- Processing memory/throughput/UI responsiveness;
- storage/GC soak;
- metadata size/query latency;
- cold startup;
- crash injection во всех critical commit windows;
- provider semantics;
- retention/clock discontinuity. 
`[UNRESOLVED BY CONTRACT UC-17]`

---

## FROZEN §F-21 — Core exclusions

До отдельного Product Contract requirement из core исключены:

- Saved-PDF Editor;
- Try Again workflow;
- arbitrary cross-import dedup;
- multi-device sync;
- unlimited ImageVersion history;
- transactional multi-file external publication;
- runtime provider conformance probing;
- general durable background-job framework.

`[UNRESOLVED BY CONTRACT UC-18]`

---

## UNRESOLVED BY CONTRACT — итог

`UC-01…UC-18` означают **не открытый policy seam**, а архитектурное решение Conceptual Architecture, которое Product Contract отдельно не ратифицировал.

После merge четыре реальные conditional seams из Reverse Review closure set закрыты: Retake, Retention, Concurrency, Compatibility. Product Contract прямо фиксирует, что в этом closure set больше нет conditional/open core decision.

---

---

# Шаг 2 — Core Invariants v1.2

Правило статуса происхождения: если FROZEN-пункт содержит `[UNRESOLVED BY CONTRACT]`, любой downstream invariant/decision, использующий его как parent, наследует этот статус. Downstream слой не ратифицирует UC автоматически. Четыре закрытых Product Contract seam — Retake Identity, Retention, Concurrency и Compatibility Horizon — сохраняют ratified status в пределах явно закрытого scope.

| ID | Проверяемый invariant | Parent |
|---|---|---|
| **INV-01** | Authoritative Workspace/Page state **MUST** определяться только transactional metadata; UI, workers и external providers **MUST NOT** быть source of truth. | F-01, F-02 |
| **INV-02** | Page-current async result **MUST NOT** commit как current result, если в одной TX не доказаны Page existence, expected contentRevision, expected current ImageVersion, non-tombstoned state и relevance desired computation. | F-02 |
| **INV-03** | Authoritative metadata **MUST NOT** reference artifact до доказанного durable completion его bytes. | F-04 |
| **INV-04** | Capture **MUST NOT** expose persistent Page до durable source artifact + successful atomic Page/ImageVersion metadata commit. | F-05 |
| **INV-05** | Retake **MUST** preserve PageId/order/selection, create a new immutable ImageVersion, increment contentRevision and preserve exactly one Undo target within the active WorkspaceSession. Failed Retake **MUST NOT** replace current version. | F-10 |
| **INV-06** | Любая content-changing authoritative mutation **MUST** atomically advance contentRevision/current-version fencing; competing mutation from an obsolete base **MUST NOT** commit. | F-07, F-10 |
| **INV-07** | OCR/processing runtime attempts **MUST NOT** require durable execution-attempt state. Durable desired state + input identity + successfully committed result must be sufficient for recomputation. | F-06 |
| **INV-08** | Qualified resource profile `QP-X14U-01` **MUST** admit at most one heavy image job and decoded-image working set ≤96 MiB. Capture/interactive authoritative work **MUST NOT** wait for heavy work completion. Resource/performance evidence **MUST** remain scoped to the exact device/OS/app-build qualification tuple and **MUST NOT** be interpreted as weakest-tier evidence. Two-worker mode **MUST remain disabled** unless every Product Contract benchmark gate is explicitly available and passed, including the stated ≥25% improvement, Capture regression ≤10%, decoded peak ≤128 MiB, no OOM/ANR and no sustained thermal throttling, plus any Product Contract trigger/soak thresholds not reproduced in this document. | F-08 |
| **INV-09** | One `(ImportSessionId, sourcePageIndex)` **MUST NOT** materialize more than one authoritative Page while that ImportSession is live/recoverable. | F-09 |
| **INV-10** | `SaveManifest` after freeze **MUST** be immutable; Save execution **MUST** consume only frozen identities/specifications and **MUST NOT** reread live Workspace. | F-11 |
| **INV-11** | External export **MUST NOT** start from partially generated output; source **MUST** be a locally validated `CommittedExportArtifact`. | F-12 |
| **INV-12** | Each requested external output **MUST** have a separate ExportAttempt; indeterminate crash **MUST** become `UnknownOutcome`; the system **MUST NOT** blind-retry it. | F-13 |
| **INV-13** | Logical Delete **MUST** atomically tombstone Page and invalidate stale work; committed bytes **MUST NOT** be physically deleted by that mutation path. | F-15 |
| **INV-14** | Workspace-associated physical deletion **MUST NOT** occur before 24h authoritative-user inactivity; background computation/reconciliation **MUST NOT** extend retention; uncertain elapsed time **MUST** postpone deletion; during prolonged active use Cleanup **MUST NOT** run more often than once per 6 hours. | F-15 |
| **INV-15** | Artifact **MUST NOT** be physically deleted while protected by authoritative state, active Undo, active SaveManifest, active durable/recovery workflow or another durable reference. | F-15 |
| **INV-16** | Metadata/artifact compatibility **MUST** support N/N-1/N-2, durable workflows N/N-1; unsupported committed user data **MUST NOT** be silently reinterpreted, reset or deleted and **MUST** enter quarantine/read-only recovery. | F-18 |
| **INV-17** | Projection/UI model **MUST** be derived/non-authoritative and **MUST NOT** execute authoritative mutations directly. | F-16 |
| **INV-18** | Startup **MUST NOT** require full-universe reconciliation; maintenance **MUST** be object-local or bounded by a durable cursor/batch boundary where progress must survive restart. | F-17 |
| **INV-19** | Durable workflow behavior **MUST** use parameters frozen into that workflow whenever later global policy may change. | F-19 |
| **INV-20** | No external-provider I/O may execute while an authoritative local metadata transaction is open. | F-01, F-12, F-13 + **ND-02B** |
| **INV-21** | Provider capability **MUST** be qualified by the multidimensional scope defined in F-14, and each external operation **MUST** persist its own measured observation/evidence rather than a global Boolean capability. | F-14 |
| **INV-22** | A guarantee **MUST NOT** be labelled proven/production-ready without executable evidence for the corresponding implementation/platform version and the production gates required by F-20. | F-20 |
| **INV-23** | An ImportSession **MUST NOT** become durable/open until either a durable app-private source snapshot or proven persistent source access exists. | F-09 |
| **INV-24** | Frozen Save dependencies **MUST** remain satisfiable for the exact frozen ImageVersion even after Retake/Edit/Delete changes live Page state. Results computed only for Save **MUST NOT** mutate `Page.currentRecognitionId` or other live-current Page result pointers. | F-11, F-10, F-02 |
| **INV-25** | Every authoritative artifact reference **MUST** be represented transactionally by an artifact record plus a protected-reference edge; Cleanup may claim only an artifact for which the same TX proves zero protecting edges and a claimable state. | F-03, F-04, F-15; **NEW DECISION ND-02A** |

`[NEW DECISION ND-02A — relational artifact-reference closure.]`

`ArtifactRecord + ArtifactReference` become the only admissible authoritative reference surface for committed app-private bytes. This is the concrete mechanism used to make INV-15 atomically testable.

`[NEW DECISION ND-02B — provider I/O is forbidden while an authoritative local TX is open.]`

FROZEN establishes the external-provider boundary; this concrete scheduling rule is the mechanism used to ensure the provider cannot become part of local transaction duration or outcome.

---

# Шаг 3 — System Boundaries

| Boundary | Responsibility | Relevant invariants |
|---|---|---|
| **Capture** | Camera acquisition, encoding, durable candidate preparation, Page creation trigger | INV-01, 03, 04, 08, 16 |
| **Workspace** | Page identity/order/selection/active page, Retake/Edit/Undo/Delete, derived projection and available-command policy | INV-01, 05, 06, 13, 17 |
| **Import** | Source securing, ImportSession lifecycle, page materialization and import recovery | INV-03, 09, 16, 19, 23 |
| **Processing** | Desired enhancement/quality/thumbnail computation and candidate production | INV-02, 06, 07, 08 |
| **OCR** | Recognition candidate production | INV-02, 06, 07, 08 |
| **Save** | Request-time freeze, frozen dependency resolution, composition, structural validation, local output commit | INV-03, 10, 11, 15, 16, 19, 24 |
| **Export** | Provider qualification, ExportAttempt lifecycle, external side effect, evidence, UnknownOutcome/revalidation | INV-11, 12, 16, 19, 20, 21 |
| **Cleanup** | Retention eligibility, protected-reference proof, GC claim, physical deletion, metadata purge and bounded orphan maintenance | INV-13, 14, 15, 16, 18, 25 |
| **Persistence** | Transactional authoritative state, schema/version gate, durable workflow records and recovery | INV-01, 02, 03, 16, 18, 19, 25 |
| **Resource Admission** | System-wide admission for non-interactive heavy image/decode/render/OCR work and decoded-memory budget | INV-08 |

`[NEW DECISION ND-03A — Import is an explicit core boundary.]`

`[NEW DECISION ND-03B — Resource Admission is an explicit core boundary.]`

These boundaries are introduced because Import has its own durable lifecycle and INV-08 is system-wide rather than Processing-only.

---

# Шаг 4 — Runtime Component Architecture

| Component | Boundary | Responsibility |
|---|---|---|
| **StateStore** | Persistence | Sole API for authoritative metadata transactions and schema/version gate |
| **ArtifactRepository** | Persistence | App-private bytes, durable candidate publication and ArtifactRecord staging state |
| **CaptureService** | Capture | Runtime Capture/Retake acquisition orchestration |
| **ImportCoordinator** | Import | Source securing, ImportSession lifecycle and page materialization |
| **ProcessingExecutor** | Processing | Processing candidates only; no authoritative writes |
| **OCRService** | OCR | Recognition candidates only; no authoritative writes |
| **SaveComposer** | Save | Save freeze request, frozen dependency resolution, composition/validation and local output commit |
| **ExportGateway** | Export | Provider qualification/adapter and ExportAttempt lifecycle |
| **CleanupArbiter** | Cleanup | Sole destructive authority for committed artifact deletion and retention purge |
| **ProjectionBuilder** | Workspace | Read-only derived projection |
| **ApplicationPolicy** | Workspace | Derived available-command calculation; no authoritative mutation API |
| **RecoveryCoordinator** | Persistence | Startup barrier and object-local recovery dispatch; no full-universe scan |
| **HeavyWorkScheduler** | Resource Admission | Single owner of heavy-job admission, memory tokens and interactive priority separation |

Subordinate platform adapters used by these components — camera, image pipeline, PDF composer/validator, provider adapter, clock and filesystem durability adapter — are allocated in Step 11/12 and are not additional authoritative owners.

### Dependency rules

1. Authoritative metadata mutation crosses `StateStore`.
2. Physical committed-artifact delete is reachable only through `CleanupArbiter`.
3. All non-interactive heavy OCR/processing/import-render/save-render work must acquire `HeavyWorkScheduler` admission.
4. `ProjectionBuilder` and `ApplicationPolicy` receive no authoritative mutation API.
5. `RecoveryCoordinator` may invoke only the explicit recovery transactions defined in Step 9.
6. Runtime Capture FSM belongs to `CaptureService`; authoritative Page visibility belongs to `StateStore`. Runtime orchestration ownership and authoritative state ownership are distinct.

`[NEW DECISION ND-04A — component allocation v1.1.]`

---

# Шаг 5 — Ownership and Serialization

## 5.1 Transition ownership

Ownership is defined per authoritative transition, not by blanket ownership of an entire table.

| Transition/domain | Semantic owner | SQL executor |
|---|---|---|
| Workspace/Page/ImageVersion/order/selection/active/tombstone/current pointers | StateStore | StateStore |
| ImportSession lifecycle | ImportCoordinator | StateStore |
| SaveManifest / SaveDependencyResult / CommittedExportArtifact | SaveComposer | StateStore |
| ExportAttempt / provider qualification / operation observation | ExportGateway | StateStore |
| Artifact bytes `temporary → durable staged` | ArtifactRepository | ArtifactRepository + StateStore narrow registry TX |
| Artifact `STAGED_DURABLE → COMMITTED` with first authoritative reference | Owner of referencing operation | StateStore |
| Artifact `COMMITTED → GC_CLAIMED → removed` | CleanupArbiter | StateStore |
| Projection | ProjectionBuilder | none |
| Available commands | ApplicationPolicy | none |
| Heavy admission | HeavyWorkScheduler | none |

No transition has two semantic owners.

## 5.2 Artifact lifecycle ownership

`[NEW DECISION ND-05A — staged artifact registry.]`

```text
temporary bytes
→ durable bytes
→ ArtifactRecord(STAGED_DURABLE, producer_epoch)
→ first authoritative reference TX:
     ArtifactRecord = COMMITTED
     + ArtifactReference inserted
→ later reference edges may be added/removed transactionally
→ if zero protected refs and cleanup-eligible:
     COMMITTED → GC_CLAIMED(claim_token)
→ physical delete
→ finalize removes ArtifactRecord
```

`STAGED_DURABLE` is artifact lifecycle metadata, not a durable OCR/processing execution attempt. It is never interpreted as resumable compute.

## 5.3 Write serialization and priority

SQLite remains a single-writer correctness boundary, but no heavy work/provider I/O/filesystem delete executes inside a write TX.

`[NEW DECISION ND-05B — prioritized short-write admission.]`

StateStore write admission classes:

1. interactive authoritative mutations: Capture, Retake, Edit, Undo, Delete, Reorder, Selection/ActivePage, Save freeze;
2. normal workflow commits: Import page, OCR/Processing result, Save output, Export state;
3. maintenance: GC claim/finalize, purge, maintenance cursor.

A running SQLite TX is never preempted. Priority applies between transactions. Background loops must yield after each bounded TX and cannot reacquire indefinitely ahead of queued interactive work.

No fixed latency guarantee is asserted here; F-20 production gates must measure it.

## 5.4 Resource admission

`[NEW DECISION ND-05C — one system-wide heavy admission owner.]`

`HeavyWorkScheduler` owns:

- `maxConcurrentHeavyJobs = 1` in the initial profile;
- decoded-image memory tokens, total ≤96 MiB;
- heavy consumers: OCR, Processing, Import render/decode, Save image decode/render/validation where applicable;
- cancellation as optimization only;
- Capture/interactive path does not wait for those jobs to finish.

Two-worker mode is unavailable unless INV-08 benchmark gates are fully specified and passed.

`[NEW DECISION ND-05D — atomic GC claim-token protocol.]`

`GC_CLAIMED + gc_claim_token` is the concrete arbitration mechanism between reference creation and physical deletion. Finalize may act only on the matching token.

---

# Шаг 6 — Typed State Machines

## 6.1 Capture — runtime only

```text
Idle → Acquiring → EncodingTemp → MakingDurable → MetadataCommit → Visible → Done
                               ↘ FailedBeforeCommit
```

Runtime owner: CaptureService.  
Authoritative `MetadataCommit → Visible`: StateStore.

No persistent CaptureIncomplete state.

## 6.2 Import — durable workflow

Durable state starts only after INV-23 is satisfied.

```text
Open → Completed
Open → Cancelled
Open → SourceUnavailable
Open → UnsupportedLegacyWorkflow
```

Page materialization inside `Open` is idempotent by `(ImportSessionId, sourcePageIndex)`.

Import recovery does not constitute user activity and does not update retention time.

## 6.3 Save — typed frozen-snapshot lifecycle

Durable milestones are derived from persisted entities, not a generic job row:

```text
NoManifest
→ ManifestFrozen
→ OutputCommitted
```

Runtime phases while `ManifestFrozen` may be `WaitingDependencies` and `Rendering`, but those phases are not durable worker states.

`[NEW DECISION ND-06C — a persisted SaveManifest is durable recomputable Save intent, not a durable worker attempt.]`

A frozen dependency for an ImageVersion that is no longer current commits only as `SaveDependencyResult` bound to the SaveManifest; it does not become a live-current Page result.

## 6.4 Export

There is no unreachable persistent `Prepared` state.

```text
WritingExternal → Succeeded
WritingExternal → FailedKnown
WritingExternal → UnknownOutcome

UnknownOutcome → Succeeded        only after positive revalidation proof
UnknownOutcome → FailedKnown      only if adapter can prove no side effect occurred
UnknownOutcome → UnknownOutcome   if evidence remains indeterminate
```

Retry/repeat export always creates a new ExportAttempt and requires explicit user intent.

## 6.5 Cleanup

Only the claim is the durable destructive boundary:

```text
Candidate(derived)
→ GC_CLAIMED(durable)
→ physical delete(runtime)
→ finalize(remove record)
```

If crash occurs after claim:
- existing file: retry physical delete;
- file already absent: finalize is permitted **only** for the same persisted claim token.

`[NEW DECISION ND-06A — missing-file-after-matching-claim is idempotent physical-delete success.]`

## 6.6 OCR / Processing

No durable attempt FSM:

```text
desired state
→ runtime candidate
→ conditional Page-current commit
   ├─ valid → committed current result
   └─ stale → discard
```

Save-scoped dependency computation uses the separate Save dependency contract from 6.3.

## 6.7 WorkspaceSession / Undo

`[NEW DECISION ND-06B — durable WorkspaceSession boundary for one-step Undo.]`

```text
OPEN → CLOSED
```

Process death does not itself close the session. Explicit workspace closure or retention purge closes it.

An Undo target is valid only while it belongs to the current OPEN WorkspaceSession and until the next content-changing mutation of that Page.

## 6.8 Schema migration

Metadata schema migration is a typed startup operation, not a generic job framework.

For supported `N-2/N-1 → N` metadata migration, each migration step is an explicit SQLite transaction. Unsupported metadata/artifact/workflow versions follow INV-16; no destructive fallback is allowed.

If a future migration requires irreversible artifact rewrite across crashes, that future migration must define its own typed durable workflow before shipping.

---

# Шаг 7 — Persistence Model

All concrete field layouts in this step are implementation decisions and are therefore explicitly scoped by the ND markers below.

## 7.1 Workspace

```text
Workspace
  workspace_id PK
  order_revision
  active_page_id?
  last_user_activity_wall
  last_user_activity_monotonic
  monotonic_epoch_token
  retention_time_uncertain
```

`[NEW DECISION ND-07A — dual retention-time evidence.]`

If elapsed ≥24h cannot be proved, cleanup receives `UNCERTAIN` and cannot delete.

## 7.2 WorkspaceSession

```text
WorkspaceSession
  workspace_session_id PK
  workspace_id
  state {OPEN, CLOSED}
```

One Workspace has at most one OPEN session.

## 7.3 Page

```text
Page
  page_id PK
  workspace_id
  order_index
  selected
  content_revision
  current_image_version_id
  undo_image_version_id?
  undo_workspace_session_id?
  processing_desired_state
  current_recognition_id?
  tombstone
  import_session_id?
  source_page_index?
```

No independent `row_revision` exists.

`UNIQUE(import_session_id, source_page_index)` applies while ImportSession evidence exists.

`[NEW DECISION ND-07B — physical order/selection/import-idempotency representation.]`

## 7.4 ImageVersion

```text
ImageVersion
  image_version_id PK
  page_id
  artifact_id
  parent_version_id?
  coordinate_space
  transform_provenance
  artifact_format_version
```

Row immutable after insert.

## 7.5 Recognition

```text
Recognition
  recognition_id PK
  page_id
  image_version_id
  payload
```

Recognition belongs to a specific immutable ImageVersion. It has no separate compatibility family; payload schema is governed by `MetadataSchemaVersion`.

`[NEW DECISION ND-07C — physical Recognition schema.]`

## 7.6 ProcessingResult

`[NEW DECISION ND-07D — generic committed processing result.]`

```text
ProcessingResult
  processing_result_id PK
  page_id
  image_version_id
  operation_key
  result_kind
  payload?
  artifact_id?
```

`operation_key` identifies the durable desired-operation specification that the candidate satisfies. The exact operation-key encoding is versioned as metadata schema, not as a new global compatibility family.

## 7.7 SaveManifest

```text
SaveManifest
  save_manifest_id PK
  workspace_id
  manifest_format_version
  frozen_payload
```

No `manifest_checksum` is required.

`frozen_payload` contains ordered PageIds, contentRevisions, ImageVersionIds, already-committed Recognition/Processing result identities when applicable, unresolved exact dependency specs and render parameters.

Row is immutable after insert.

`[NEW DECISION ND-07E — canonical serialized SaveManifest representation.]`

## 7.8 SaveDependencyResult

`[NEW DECISION ND-07F — Save-scoped frozen dependency result.]`

```text
SaveDependencyResult
  save_dependency_result_id PK
  save_manifest_id
  image_version_id
  dependency_kind
  dependency_spec
  payload?
  artifact_id?
```

It can satisfy only the referencing SaveManifest and never updates live-current Page result pointers.

## 7.9 CommittedExportArtifact

`[NEW DECISION ND-07G — concrete local-output schema.]`

```text
ExportArtifact
  export_artifact_id PK
  save_manifest_id
  output_kind
  artifact_set_payload
  artifact_format_version
  structural_validation_version
```

Presence means local bytes are durable and structural validation succeeded.

`output_kind`, ordered set representation and validation-version field are concrete schema choices, not implied enums from FROZEN.

## 7.10 ExportAttempt

`[NEW DECISION ND-07H — concrete ExportAttempt schema.]`

```text
ExportAttempt
  export_attempt_id PK
  export_artifact_id
  durable_workflow_payload_version
  destination_descriptor
  frozen_provider_parameters
  state
  external_identity?
  operation_observation_id?
  known_error?
```

At most one non-terminal attempt may exist for the same `(ExportArtifact, normalized destination intent)`.

## 7.11 ImportSession

`[NEW DECISION ND-07I — concrete ImportSession schema.]`

```text
ImportSession
  import_session_id PK
  workspace_id
  durable_workflow_payload_version
  frozen_operation_parameters
  source_snapshot_artifact_id?
  persistent_access_descriptor?
  state
  source_page_count?
  order_policy = APPEND_ON_COMMIT
```

Policy: imported pages preserve source order relative to each other; each committed page appends to the current Workspace order. Concurrent Capture/Reorder may therefore alter interleaving, but never source-index identity. Save freezes whatever authoritative order exists at its own consistency point.

## 7.12 Provider qualification and operation evidence

`[NEW DECISION ND-07J — provider qualification/evidence persistence.]`

```text
ProviderQualification
  qualification_id PK
  adapter_id
  provider_version
  os_version
  destination_access_class
  validation_suite_version
  qualified_capabilities_payload

ProviderOperationObservation
  observation_id PK
  export_attempt_id
  identity_evidence?
  existence_evidence?
  integrity_evidence?
  readability_evidence?
  name_evidence?
  access_evidence?
```

No global Boolean capability field exists.

## 7.13 ArtifactRecord / ArtifactReference

`[NEW DECISION ND-07K — artifact registry and reference schema.]`

```text
ArtifactRecord
  artifact_id PK
  relative_storage_path
  artifact_kind
  size_bytes
  checksum?
  artifact_format_version
  creation_revision?
  state {STAGED_DURABLE, COMMITTED, GC_CLAIMED, QUARANTINED}
  producer_epoch?
  gc_claim_token?

ArtifactReference
  artifact_id
  ref_kind
  ref_owner_id
  protection_class
  PRIMARY KEY(artifact_id, ref_kind, ref_owner_id)
```

Rules:

1. authoritative entities may reference only `COMMITTED` or `QUARANTINED` artifacts according to recovery mode;
2. first authoritative reference atomically transitions `STAGED_DURABLE → COMMITTED` and inserts the first ArtifactReference;
3. every additional authoritative/protective reference inserts/deletes its edge in the same metadata TX as the owning state change;
4. `GC_CLAIMED` cannot acquire a new reference;
5. `QUARANTINED` cannot be GC-claimed by normal retention cleanup.

## 7.14 MaintenanceCursor

`[NEW DECISION ND-07L — durable bounded-maintenance cursor.]`

```text
MaintenanceCursor
  domain PK
  cursor_payload
  cursor_format_version
```

Used only where restart-safe bounded progress is needed; it is not a generic job checkpoint.

## 7.15 Referential lifecycle

- Recognition/ProcessingResult rows tied to an ImageVersion are purged only when no current/Undo/Save/durable-workflow reference requires them.
- Open/recoverable ImportSession keeps its Page tuple evidence and source reference protected.
- Active SaveManifest protects frozen dependencies.
- WritingExternal/UnknownOutcome/revalidation ExportAttempt protects its ExportArtifact and provider-recovery evidence.
- Completed/FailedKnown ExportAttempt is not an active recovery reference.
- Workspace metadata purge is allowed only after retention eligibility and after all blocking references/workflows are resolved or explicitly retired.

`[NEW DECISION ND-07M — Save/Export protection lifecycle.]`

A SaveManifest is active protection while no CommittedExportArtifact exists. `TX-SAVE-OUTPUT-COMMIT` atomically creates the ExportArtifact/reference and retires the manifest as an active protection source; the immutable manifest row may remain as lineage until workspace metadata purge.

An ExportArtifact is protected while a non-terminal ExportAttempt exists. Once no active attempt exists, it is ordinary workspace-associated retained data and may be purged only under the same ≥24h retention rules.

---

# Шаг 8 — Artifact Storage and Cleanup

## 8.1 Identity and layout

`[NEW DECISION ND-08A — UUIDv4 opaque IDs and sharded app-private layout.]`

```text
<AppPrivateRoot>/
  objects/<format-generation>/<uuid-prefix>/<ArtifactId>.<ext>
  staging/<producer-epoch>/<operation>/<ArtifactId>.tmp
  quarantine/
```

Opaque identity is not a content hash.

## 8.2 Integrity

`[NEW DECISION ND-08B — SHA-256 as optional stored integrity checksum.]`

Checksum is integrity evidence only, never identity/dedup key.

## 8.3 Durable candidate publication

`[NEW DECISION ND-08C — qualified durable publication protocol.]`

```text
write temp
→ finish encoder
→ flush userspace buffers
→ qualified platform persistence primitive
→ atomically publish to final app-private object path
→ establish the F-04 qualified application-process-crash persistence boundary
→ register ArtifactRecord(STAGED_DURABLE)
```

There is no “where supported” weakening inside the v1.2 guarantee scope: if a platform path cannot provide the qualified application-process-crash persistence boundary required by F-04, that path is not an admissible implementation for authoritative artifacts. Sudden hardware power-loss durability is outside the v1.2 guarantee unless separately qualified.

A first-reference metadata transaction then atomically:

```text
checks STAGED_DURABLE
→ sets COMMITTED
→ inserts ArtifactReference
→ inserts/updates owning authoritative entity
```

## 8.4 Orphan staging

`[NEW DECISION ND-08D — bounded staged-artifact reclamation.]`

`STAGED_DURABLE` from a dead producer epoch is never resumed as compute. `CleanupArbiter` may delete it in bounded DB-selected batches after proving it has zero ArtifactReference edges. This is separate from retention GC of committed workspace data.

Current-process staged artifacts are not reclaimed by maintenance while their producer epoch is live.

## 8.5 Retention and committed GC

Committed workspace-associated data may be claimed only when:

```text
workspace inactivity >= 24h
AND elapsed-time evidence reliable
AND no current authoritative reference edge
AND no valid Undo edge
AND no active SaveManifest protection
AND no active durable/recovery workflow protection
AND artifact state = COMMITTED
AND artifact not QUARANTINED
```

Cleanup triggers:

- cold start / foreground entry;
- prolonged active use no more than once every 6 hours.

The 6h gate is enforced by CleanupArbiter runtime scheduling inside one foreground process. A new process start is a cold-start trigger and may run a new bounded pass.

## 8.6 Metadata purge

`[NEW DECISION ND-08E — retention metadata purge.]`

After ≥24h eligibility, CleanupArbiter may purge workspace metadata only in bounded transactions and only after:

1. active Undo, Save, Import, Export/revalidation and migration protections are absent;
2. required ArtifactReference edges are removed in the same TX;
3. dependent Recognition/ProcessingResult/Page/ImageVersion rows are removed in referentially safe order;
4. ImportSession tuple evidence is not removed while that session remains recoverable.

Physical artifact deletion remains a separate GC-claim path.

## 8.7 Backup domain

`[NEW DECISION ND-08F — transient core persistence domain excluded from OS automatic backup.]`

Authoritative SQLite metadata and app-private artifact repository are treated as one transient persistence domain for backup policy and are excluded together. A restore containing DB without matching bytes, or bytes without matching DB, is not an admissible core restore mode.

User-selected external exports are outside this policy.

---

# Шаг 9 — Transaction Boundaries

Every authoritative mutation is expressed as:

`outside TX → TX begins → conditions → mutations/reference-edge changes → commit`

## TX-CAPTURE

Outside TX: CaptureService acquires/encodes; ArtifactRepository returns `STAGED_DURABLE ArtifactId`.

TX:
- Workspace supported/writable;
- staged artifact exists, not quarantined/claimed;
- create Page + ImageVersion;
- transition ArtifactRecord to COMMITTED;
- add ImageVersion ArtifactReference;
- initialize contentRevision/order/selection/desired work;
- update authoritative user-activity time;
- commit.

## TX-RETAKE

Outside TX: produce new staged durable image.

TX guards:
- Page exists/non-tombstoned;
- expected current ImageVersion/contentRevision still current;
- current WorkspaceSession OPEN;
- staged artifact valid.

Mutations:
- create immutable new ImageVersion + reference;
- set old current ImageVersion as sole Undo target with current WorkspaceSession;
- set current ImageVersion to new;
- `contentRevision++`;
- clear live-current Recognition applicability;
- refresh desired processing;
- update user activity.

A competing Retake/Edit/Undo from the old base fails the expected-current guard.

## TX-EDIT

Outside TX: render edited candidate from explicit base ImageVersion; make staged artifact durable.

TX uses the same expected-current guard as Retake.

Mutations:
- new immutable ImageVersion + reference;
- previous current becomes sole Undo target;
- current image switches;
- `contentRevision++`;
- current Recognition applicability clears;
- desired processing recomputed;
- user activity updated.

## TX-UNDO

TX guards:
- Page exists/non-tombstoned;
- Undo target exists;
- `undo_workspace_session_id` equals current OPEN WorkspaceSession;
- expected current ImageVersion/contentRevision still current.

Mutations:
- current ImageVersion becomes the Undo target;
- `contentRevision++`;
- Undo slot clears;
- currentRecognitionId becomes the latest still-committed Recognition for the restored ImageVersion if one exists, otherwise null;
- desired processing is recalculated for restored ImageVersion;
- user activity updated.

`[NEW DECISION ND-09A — Recognition restoration on Undo uses an existing committed Recognition bound to the restored immutable ImageVersion.]`

## TX-DELETE

TX:
- Page exists/non-tombstoned;
- tombstone=true;
- `contentRevision++`;
- remove from visible order/selection;
- adjust active page deterministically;
- `orderRevision++`;
- clear Undo slot/protection;
- update user activity;
- no physical delete.

`[NEW DECISION ND-09B — Delete/order/selection update is atomic.]`

## TX-REORDER

TX:
- Workspace supported;
- input Page set exactly matches currently visible intended set or explicit expected `orderRevision`;
- write new order;
- `orderRevision++`;
- update user activity.

## TX-SELECTION-ACTIVE

TX:
- referenced Pages exist and are visible;
- update selection and/or active Page;
- update user activity.

## TX-WORKSPACE-SESSION-CLOSE

TX:
- current WorkspaceSession OPEN;
- set CLOSED;
- clear Undo slots belonging to that session;
- remove corresponding Undo protection edges;
- update user activity only when closure is an explicit user action.

## TX-IMPORT-BEGIN

Outside TX:
- durable source snapshot or proven persistent access established.

TX:
- INV-23;
- create `ImportSession(Open)` with frozen workflow parameters;
- if source snapshot is an Artifact, transition to COMMITTED and add active ImportSession reference;
- update user activity **once** for the user import command.

## TX-IMPORT-MATERIALIZE-PAGE

`[NEW DECISION ND-09D — Import materializes one authoritative Page per short transaction; imported pages append on commit.]`

Outside TX:
- HeavyWorkScheduler admission;
- decode/render source page N;
- staged durable image artifact.

TX:
- ImportSession Open;
- `(ImportSessionId,N)` absent;
- staged artifact valid;
- create Page + ImageVersion;
- commit artifact + reference;
- bind tuple;
- append to current order;
- set desired processing;
- **do not update user-activity time** when this is background/recovery materialization.

Unique collision means already materialized; no second Page.

## TX-IMPORT-END

TX transitions Open → Completed/Cancelled/SourceUnavailable/UnsupportedLegacyWorkflow as applicable and releases only references no longer needed for recovery. Automatic recovery transitions do not extend user-activity time.

## TX-OCR-COMMIT

Current-page OCR commit:
- Page/current revision/current ImageVersion/non-tombstone/relevance checks per INV-02;
- insert Recognition;
- switch currentRecognitionId;
- satisfy current desired OCR.

## TX-PROCESSING-COMMIT

Current-page generic Processing commit:
- same INV-02/06 current-page checks;
- insert ProcessingResult and any ArtifactReference;
- satisfy matching `operation_key`.

## TX-SAVE-FREEZE

The authoritative **Save request consistency point** is the instant this transaction acquires the Workspace snapshot, not the earlier UI gesture.

TX:
- snapshot ordered visible PageIds, contentRevisions, ImageVersionIds and exact required dependency/render specs;
- verify referenced artifacts are COMMITTED/not claimed/quarantined;
- insert immutable SaveManifest;
- insert protection edges for every frozen dependency;
- commit.

After commit, Save never rereads live Workspace.

`[NEW DECISION ND-09F — Save during an Open ImportSession freezes only Pages already authoritative at the Save consistency point; later imported Pages are excluded from that Save.]`

`[NEW DECISION ND-09C — authoritative Save request is StateStore acceptance/snapshot, and the UI command is not itself a consistency point until that transaction begins.]`

## TX-SAVE-DEPENDENCY-COMMIT

For dependency computed specifically for a frozen SaveManifest:

TX guards:
- SaveManifest still active;
- exact frozen ImageVersion/dependency spec matches manifest;
- source artifact still protected by manifest;
- candidate belongs to exact frozen input.

Mutations:
- insert SaveDependencyResult;
- add result ArtifactReference if any;
- do **not** mutate Page.currentRecognitionId/current Processing pointers.

This transaction may succeed even if that Page has since been Retaken/Edited/Deleted, because it serves the already-frozen Save snapshot.

## TX-SAVE-OUTPUT-COMMIT

Outside TX:
- all frozen dependencies resolved;
- render only from SaveManifest + SaveDependencyResult/current frozen results;
- HeavyWorkScheduler admission as required;
- staged output made durable;
- structural validation passes.

TX:
- SaveManifest active;
- output staged artifact valid;
- create ExportArtifact;
- commit output ArtifactRecord/reference;
- retire SaveManifest as active protection and replace required protection with ExportArtifact/reference;
- commit.

## TX-EXPORT-BEGIN

`[NEW DECISION ND-09E — WritingExternal is durably committed before provider I/O so crash ambiguity is representable.]`

Outside TX:
- select existing CommittedExportArtifact;
- normalize destination intent;
- freeze provider parameters.

TX:
- no non-terminal duplicate attempt exists for same `(ExportArtifact, normalized destination intent)`;
- provider qualification available for the attempted adapter/scope;
- workflow version supported;
- create ExportAttempt `WritingExternal`;
- add active attempt protection;
- commit.

Only after commit may provider I/O start.

## TX-EXPORT-END

Outside TX: provider returns definite success/failure.

TX:
- attempt still WritingExternal;
- persist ProviderOperationObservation;
- success → Succeeded + identity/evidence;
- known failure → FailedKnown + error/evidence;
- release active-attempt protection if terminal.

## TX-EXPORT-AMBIGUOUS

Startup barrier only:

TX:
- attempt is persisted WritingExternal from a process that cannot still be executing;
- transition to UnknownOutcome;
- retain active recovery protection.

No foreground reconciliation may rewrite a live current-process attempt.

## TX-EXPORT-REVALIDATE

Outside TX: qualified provider adapter performs read-only revalidation using stable identity.

TX:
- attempt is UnknownOutcome;
- persist observation;
- positive proof → Succeeded;
- proof of non-occurrence, if adapter contract can establish it → FailedKnown;
- otherwise remain UnknownOutcome.

No write retry occurs.

## TX-GC-CLAIM

TX:
- retention eligible and time reliable;
- ArtifactRecord = COMMITTED;
- not QUARANTINED;
- zero protecting ArtifactReference edges;
- transition to GC_CLAIMED with new claim token.

Physical delete occurs outside TX.

## TX-GC-FINALIZE

TX:
- same claim token still current;
- physical object deleted or already absent;
- remove ArtifactRecord and claim metadata.

## TX-METADATA-PURGE-BATCH

TX:
- workspace retention eligible;
- no active blocking workflows/protections;
- bounded batch selected;
- remove referential rows and their ArtifactReference edges;
- advance MaintenanceCursor;
- commit.

## TX-MAINTENANCE-CURSOR

Short TX updating one domain cursor after a successful bounded batch.

## TX-SCHEMA-MIGRATE

For each supported metadata migration step:
- migration executes inside explicit SQLite transaction;
- version advances only on successful commit;
- unsupported version does not enter this path and is quarantined/read-only.

---

# Шаг 10 — Recovery and Concurrency Sequences

## 10.1 Retake/Edit vs OCR/Processing

Long compute is outside TX. Retake/Edit increments contentRevision/current ImageVersion. Any old Page-current candidate fails TX-OCR-COMMIT/TX-PROCESSING-COMMIT.

Competing Retake/Edit from the same base also cannot both commit: the loser fails expected-current guard. Its staged artifact becomes reclaimable by the staged-artifact cleanup path.

## 10.2 Save freeze vs later mutation

`TX-SAVE-FREEZE` and a content mutation serialize as short StateStore writes.

- mutation commits first → Save freezes the new state;
- Save freezes first → later mutation changes live state but cannot alter manifest.

If frozen old ImageVersion still needs OCR/processing, the result commits via TX-SAVE-DEPENDENCY-COMMIT, not the Page-current commit path.

## 10.3 Save vs Cleanup

Save freeze can reference only COMMITTED, unclaimed artifacts and inserts protection edges in its TX.

GC claim proves zero protection edges and changes COMMITTED→GC_CLAIMED in its TX.

Therefore exactly one side can win for the same artifact; no filesystem delete holds the DB writer.

## 10.4 Import vs retention

User activity is recorded by TX-IMPORT-BEGIN. Background page materialization/recovery does not refresh retention. Open/recoverable ImportSession itself is a protection reference, so retention purge cannot erase its Page tuple evidence or source while recovery remains active.

## 10.5 Duplicate export

Only one non-terminal attempt per normalized destination intent is admitted. A terminal or UnknownOutcome attempt is not silently retried. An explicit new user repeat produces a new attempt after the previous attempt is terminal or the UI explicitly chooses repeat from unresolved state according to product policy.

## 10.6 Startup barrier

Before external ExportGateway accepts new provider writes:

1. open DB;
2. version gate/migrate supported metadata;
3. quarantine unsupported committed data;
4. convert persisted pre-startup WritingExternal attempts to UnknownOutcome;
5. resume/finalize GC_CLAIMED objects;
6. expose app.

Object-local/lazy recovery then:
- resumes missing Import pages when that ImportSession is visited/reconciled;
- re-enqueues unsatisfied desired OCR/Processing through HeavyWorkScheduler;
- re-enqueues active SaveManifest work using only frozen dependencies;
- performs bounded cleanup/orphan batches via MaintenanceCursor.

No full filesystem/persistent-universe scan is required.

## 10.7 Heavy admission

OCR, Processing, Import rendering and Save image decode/render all acquire HeavyWorkScheduler admission and decoded-memory tokens. Capture remains outside that queue for its interactive path; its authoritative metadata TX enters the highest StateStore write class.

---

# Шаг 11 — Platform Contracts

## PCON-01 — Database transaction semantics

Must provide atomic commit/rollback, conditional check+mutation isolation, crash recovery and short single-writer transactions sufficient for Steps 5/9.

## PCON-02 — Durable artifact publication

Platform adapter must expose a qualified application-process-crash persistence boundary for complete write + file publication on the active qualification tuple. It must not return success if the platform-specific sequence cannot satisfy F-04. Sudden hardware power-loss durability is not implied by this contract in v1.2 and requires separate qualification before it may be claimed.

## PCON-03 — Core storage/backup domain

SQLite authoritative metadata and app-private object repository must be protected from ordinary external mutation and excluded together from automatic OS backup according to ND-08F.

## PCON-04 — Import source access

Import adapter must produce either proven persistent access or a durable app-private snapshot before TX-IMPORT-BEGIN.

Concrete Android URI/permission mechanisms are implementation choices and require platform verification; F-09 does not itself prove any particular API behavior.

## PCON-05 — Provider adapter and qualification

Adapter must define successful return semantics, stable identity availability, revalidation capability and evidence dimensions for a specific F-14 qualification scope.

## PCON-06 — No correctness dependency on background execution

Arbitrary suspension/process death may lose runtime attempts; correctness recovers from durable desired state/workflow state only.

## PCON-07 — System-wide heavy admission

All non-interactive heavy consumers listed in Step 5.4 must share HeavyWorkScheduler admission and decoded-memory accounting.

## PCON-08 — Time evidence

Expose wall clock, monotonic elapsed time and evidence that elapsed-time observations are comparable. Uncertainty produces `UNCERTAIN`, never estimated deletion permission.

## PCON-09 — OCR/Processing candidate contract

Compute adapters consume explicit immutable input identity and return candidates only. They cannot mutate StateStore directly.

## PCON-10 — Bounded composition

Save/import/image pipelines must support bounded decode/render such that system-wide INV-08 can be enforced. No claim is made that a chosen PDF API provides this property without executable measurement.

## PCON-11 — Version gate before interpretation

Identify durable family/version before interpretation; supported versions migrate/read, unsupported committed data quarantine/read-only, old workflow payloads become UnsupportedLegacyWorkflow as specified by F-18.

## PCON-12 — External I/O transaction separation

Provider I/O starts only after TX-EXPORT-BEGIN commits and no authoritative metadata TX remains open.

This is the concrete enforcement of INV-20.

## PCON-13 — Verification evidence

Platform/technology capability claims used for production must be backed by the executable F-20 gate set for the exact implementation/platform version. Documentation or architectural assertion alone does not produce `PROVEN`.

---

# Шаг 12 — Technology Stack v1.2

Product implementation target for this Core remains **Android 10+, ARM64**. Production empirical qualification in v1.2 is intentionally narrower: `QP-X14U-01` from F-08. Passing qualification on that tuple does not establish support evidence for untested Android versions, RAM tiers, SKUs or devices. iOS/Kotlin Multiplatform scope is not part of this Core because no upstream requirement supplied here establishes it.

Every technology choice below is a separate implementation decision and remains subject to INV-22/PCON-13.

## 12.1 Language/runtime

`[NEW DECISION ND-12A — Android Kotlin.]`

No cross-platform runtime is required by this Core.

## 12.2 Database

`[NEW DECISION ND-12B — SQLite + SQLDelight.]`

Configuration target:

```text
journal_mode = WAL
synchronous = FULL
```

The initial production gate must validate transaction and persistence behavior on the exact `QP-X14U-01` tuple. No validation claim for another Android version/device/SKU exists without separate qualification evidence.

## 12.3 Concurrency

`[NEW DECISION ND-12C — kotlinx.coroutines + explicit HeavyWorkScheduler admission.]`

The semaphore/admission primitive is an implementation of INV-08, not its source.

Initial heavy concurrency = 1.

## 12.4 Image processing

`[NEW DECISION ND-12D — OpenCV behind ImagePipeline.]`

Selection is implementation-specific; no cross-platform determinism requirement is asserted.

All heavy calls are wrapped by HeavyWorkScheduler and decoded-memory accounting.

## 12.5 OCR

`[NEW DECISION ND-12E — Android on-device OCR adapter using ML Kit Text Recognition with bundled model.]`

The adapter must satisfy PCON-09 and production evidence requirements. Engine selection does not change OCR correctness invariants.

## 12.6 Camera

`[NEW DECISION ND-12F — Android CameraX adapter.]`

Camera API choice is outside authoritative-state semantics.

## 12.7 Import/export provider

`[NEW DECISION ND-12G — Android Storage Access Framework / ContentResolver adapter.]`

Actual persistent-access and provider-return semantics are not assumed from the API name; they must be qualified under PCON-04/05/13.

## 12.8 PDF generation and validation

`[NEW DECISION ND-12H — Android native PDF generation behind PdfComposer plus explicit read-back structural validator.]`

The selected implementation is acceptable only if PCON-10 memory and F-12 structural-validation gates pass. No undocumented bounded-memory property is assumed.

## 12.9 Serialization

`[NEW DECISION ND-12I — canonical JSON for small durable workflow/manifest payloads.]`

Canonicalization rules are part of the payload format version. JSON is not used for image bytes.

## 12.10 Filesystem durability

`[NEW DECISION ND-12J — Android filesystem durability adapter implementing PCON-02.]`

Exact platform primitives are encapsulated and must pass the v1.2 mandatory application-process crash-injection suite before the adapter is qualified for `QP-X14U-01`. Hard-power qualification is a separate evidence extension and is not implied by that PASS.

## 12.11 Clock

`[NEW DECISION ND-12K — Android wall-clock + monotonic elapsed-time adapter.]`

The adapter must implement PCON-08 and explicit discontinuity detection.

## 12.12 Schema migration

`[NEW DECISION ND-12L — SQLDelight migration scripts executed under TX-SCHEMA-MIGRATE.]`

Koin and logging frameworks are not part of Core Architecture; they are ordinary application implementation details unless a future requirement gives them architectural significance.



## 12.13 Platform-contract implementation mapping

| Platform contract | Primary implementation surface |
|---|---|
| PCON-01 | SQLite/SQLDelight + StateStore |
| PCON-02 | Android filesystem durability adapter + ArtifactRepository |
| PCON-03 | Core storage placement/backup rules |
| PCON-04 | SAF/ContentResolver import adapter + ImportCoordinator |
| PCON-05 | SAF/provider adapter + ExportGateway + ProviderQualification |
| PCON-06 | RecoveryCoordinator + disposable OCR/Processing attempts |
| PCON-07 | HeavyWorkScheduler |
| PCON-08 | Android wall/monotonic clock adapter |
| PCON-09 | OCRService / ProcessingExecutor candidate contracts |
| PCON-10 | ImagePipeline + PdfComposer + HeavyWorkScheduler |
| PCON-11 | StateStore version gate + migration/quarantine path |
| PCON-12 | ExportGateway transaction/provider sequencing |
| PCON-13 | Step 13 executable production gates |

---

# Step 13 — Production / Verification Gates

The architecture is not `PROVEN` by this document.

Before production, executable evidence must cover at least:

- exact qualification identity for `QP-X14U-01`: physical device identity, SKU/RAM observation, OS/API/build fingerprint, kernel/filesystem/ABI and app build hash;
- proof that all mandatory S1–S6 runs in a qualification cycle use the same physical unit, OS build fingerprint and app build hash;
- Capture p95/p99 durable commit and burst behavior;
- StateStore write-queue latency under Import/OCR/Save/Cleanup pressure;
- one-heavy-worker memory/throughput/UI responsiveness;
- any attempted two-worker upgrade against every Product Contract gate;
- Save frozen-dependency path after Retake/Edit/Delete;
- concurrent Retake/Edit expected-current loser behavior and staged-artifact reclamation;
- GC claim vs Save/reference creation;
- staged-artifact crash windows;
- retention/clock discontinuity and 6h cadence;
- metadata purge and referential closure;
- startup barrier for WritingExternal→UnknownOutcome;
- provider qualification/revalidation evidence;
- import persistent-access loss;
- compatibility N-2/N-1 and quarantine/read-only recovery;
- schema migration crash injection;
- PDF structural validation and bounded composition memory.

No implementation/platform version may be labelled proven for a guarantee without the corresponding executable evidence. Evidence collected on `QP-X14U-01` is tuple-scoped and does not prove a weakest-tier or device-family guarantee. Mandatory v1.2 durability qualification covers application-process crash persistence; sudden hardware power-loss durability remains unqualified unless the separate hard-power extension is executed and passes.

---

# Architecture Closure

The core is organized around five hard boundaries:

```text
transactional metadata authority
+ immutable/versioned app-private artifacts
+ explicit artifact-reference graph
+ frozen Save snapshots with save-scoped dependency results
+ typed irreversible workflows (Import / Export / Cleanup / migration)
```

Recomputable OCR/Processing remains disposable. Correctness comes from input identity and conditional commit, not cancellation or durable worker attempts.

The principal write serialization point is short StateStore transactions. Heavy compute, decode/render, provider I/O and physical deletion remain outside those transactions and are constrained by one system-wide HeavyWorkScheduler.

Cleanup correctness is based on relational reference closure plus atomic `GC_CLAIMED`, not on a best-effort filesystem scan.

Save correctness is independent of later live-Workspace mutations because frozen non-current dependencies have their own Save-scoped commit path.

This closes the previously missing mutation, persistence, recovery, resource-admission and frozen-dependency contracts without introducing a generic durable job framework.


---

# Appendix A — Final Traceability Matrix

Матрица является freeze-record трассировки, а не заменой содержательных contracts в Steps 1–13. Пустых ячеек нет; `N/A` означает сознательно неприменимый слой, а не отсутствие решения.

| INV | Parent / provenance status | Boundary | Owner / component | Persistent realization | TX / FSM realization | Platform / verification |
|---|---|---|---|---|---|---|
| INV-01 | F-01 (UC-01), F-02 (UC-02) | Capture / Workspace / Persistence | StateStore; CaptureService for runtime acquisition | Workspace / Page / ImageVersion authoritative metadata | All authoritative Workspace/Page mutation TX; especially TX-CAPTURE | PCON-01; StateStore transaction tests |
| INV-02 | F-02 (UC-02) | Processing / OCR / Persistence | StateStore + OCRService / ProcessingExecutor candidate producers | Recognition; ProcessingResult; Page current pointers | TX-OCR-COMMIT; TX-PROCESSING-COMMIT | PCON-01, PCON-09; stale-candidate crash/race tests |
| INV-03 | F-04 (UC-04) | Capture / Import / Save / Persistence | ArtifactRepository + StateStore + referencing operation owner | ArtifactRecord / ArtifactReference | TX-CAPTURE; TX-IMPORT-MATERIALIZE-PAGE; TX-SAVE-OUTPUT-COMMIT | PCON-02; durability crash injection |
| INV-04 | F-05 (UC-05) | Capture | CaptureService runtime; StateStore authoritative commit | Page + ImageVersion + committed ArtifactReference | TX-CAPTURE; Capture runtime FSM | PCON-01, PCON-02; capture durable-commit p95/p99 + crash windows |
| INV-05 | F-10 — Retake identity ratified; concrete Undo/session via ND-06B | Workspace | StateStore; CaptureService acquisition | Page current_image_version; WorkspaceSession; Undo target/protection | TX-RETAKE; TX-UNDO; WorkspaceSession FSM | PCON-01; Retake/Undo race and crash tests |
| INV-06 | F-07 (UC-07), F-10 (UC-09 where Edit semantics apply) | Workspace / Processing / OCR | StateStore; OCRService / ProcessingExecutor return candidates only | Page.contentRevision + current ImageVersion identity | TX-RETAKE; TX-EDIT; TX-UNDO; TX-DELETE; TX-OCR-COMMIT; TX-PROCESSING-COMMIT | PCON-01, PCON-09; expected-current loser tests |
| INV-07 | F-06 (UC-06) | Processing / OCR | OCRService; ProcessingExecutor; RecoveryCoordinator | Durable desired/input identity; Recognition / ProcessingResult only after successful commit | No durable OCR/Processing FSM; TX-OCR-COMMIT / TX-PROCESSING-COMMIT only | PCON-06, PCON-09; process-death recomputation tests |
| INV-08 | F-08 — ratified Concurrency seam + AMEND-QS-01 qualification scope | Capture / Processing / OCR / Resource Admission; Save/Import heavy paths via scheduler | HeavyWorkScheduler | N/A — runtime admission/memory-token state; no durable worker attempt by design | Heavy-work admission around Import/OCR/Processing/Save render; no metadata TX held | PCON-07, PCON-10; `QP-X14U-01` one-worker memory/throughput/UI gate; tuple-scoped two-worker benchmark gate |
| INV-09 | F-09 (UC-08) | Import | ImportCoordinator + StateStore | ImportSession; Page(import_session_id, source_page_index) uniqueness | TX-IMPORT-MATERIALIZE-PAGE | PCON-01, PCON-04; recovery/idempotency tests |
| INV-10 | F-11 (UC-10) | Save | SaveComposer + StateStore snapshot | Immutable SaveManifest | TX-SAVE-FREEZE; Save typed lifecycle | PCON-01, PCON-10; no-live-reread and crash recovery tests |
| INV-11 | F-12 (UC-11) | Save / Export | SaveComposer; ExportGateway | CommittedExportArtifact + committed output ArtifactRecord/Reference | TX-SAVE-OUTPUT-COMMIT → TX-EXPORT-BEGIN | PCON-02, PCON-05, PCON-12; structural validation/provider tests |
| INV-12 | F-13 (UC-12) | Export | ExportGateway | ExportAttempt + ProviderOperationObservation | Export FSM; TX-EXPORT-BEGIN/END/AMBIGUOUS/REVALIDATE | PCON-05, PCON-12; crash ambiguity/revalidation tests |
| INV-13 | F-15 — FROZEN, no UC marker | Workspace / Cleanup | StateStore for logical delete; CleanupArbiter for physical delete | Page.tombstone/contentRevision; artifact refs remain until cleanup eligibility | TX-DELETE; later TX-GC-CLAIM only when unprotected | PCON-01; delete-vs-processing and retention tests |
| INV-14 | F-15 — FROZEN, no UC marker | Cleanup | CleanupArbiter + StateStore time evidence | Workspace retention timestamps/uncertainty + MaintenanceCursor | Cleanup scheduling; TX-GC-CLAIM / TX-METADATA-PURGE-BATCH | PCON-08; 24h/clock-discontinuity/6h-cadence tests |
| INV-15 | F-15 — FROZEN, no UC marker | Save / Cleanup | Referencing operation owners + CleanupArbiter | ArtifactReference protection graph | Reference-creating/removing TX; TX-GC-CLAIM zero-edge proof | PCON-01, PCON-02; Save/GC and workflow-protection race tests |
| INV-16 | F-18 — ratified Compatibility seam | Import / Save / Export / Cleanup / Persistence | StateStore version gate; RecoveryCoordinator; CleanupArbiter respects quarantine | Versioned metadata/artifacts/workflow payloads; QUARANTINED state | TX-SCHEMA-MIGRATE; UnsupportedLegacyWorkflow transitions; GC claim excludes quarantine | PCON-11; N-2/N-1 + quarantine/read-only recovery tests |
| INV-17 | F-16 (UC-14) | Workspace | ProjectionBuilder + ApplicationPolicy | N/A — projection deliberately derived/non-durable | N/A — no authoritative mutation TX exposed to projection/policy | API-boundary tests; projection mutation prohibition |
| INV-18 | F-17 (UC-15) | Cleanup / Persistence | RecoveryCoordinator + CleanupArbiter | MaintenanceCursor where restart-surviving bounded progress is required | TX-METADATA-PURGE-BATCH; TX-MAINTENANCE-CURSOR; object-local recovery | PCON-06; cold-start/bounded-maintenance tests |
| INV-19 | F-19 (UC-16) | Import / Save / Export / Persistence | ImportCoordinator; SaveComposer; ExportGateway | Frozen parameters in ImportSession / SaveManifest / ExportAttempt | TX-IMPORT-BEGIN; TX-SAVE-FREEZE; TX-EXPORT-BEGIN | PCON-01; recovery under changed global policy |
| INV-20 | F-01 (UC-01), F-12 (UC-11), F-13 (UC-12) + ND-02B | Export | ExportGateway + StateStore | ExportAttempt.WritingExternal is the durable barrier before provider I/O | TX-EXPORT-BEGIN commits before provider I/O; completion uses later TX | PCON-12; provider-latency/transaction-separation tests |
| INV-21 | F-14 (UC-13) | Export | ExportGateway | ProviderQualification + ProviderOperationObservation | TX-EXPORT-BEGIN qualification check; TX-EXPORT-END / REVALIDATE persist observations | PCON-05, PCON-13; multidimensional provider qualification suite |
| INV-22 | F-20 (UC-17) | Architecture-wide | All implementation owners; release gate owned by verification process | N/A — executable evidence is a release artifact, not authoritative user-state DB | N/A — no domain mutation; gates Step 13 | PCON-13 + every Step 13 executable production gate |
| INV-23 | F-09 (UC-08) | Import | ImportCoordinator | ImportSession source_snapshot / persistent_access evidence | TX-IMPORT-BEGIN; Import FSM begins Open only after source is secured | PCON-04; access-loss/recovery tests |
| INV-24 | F-11 (UC-10), F-10 (UC-09 where applicable), F-02 (UC-02) | Save | SaveComposer | SaveManifest + SaveDependencyResult + manifest protection refs | TX-SAVE-DEPENDENCY-COMMIT; Save runtime WaitingDependencies/Rendering | PCON-09, PCON-10; frozen-old-version after Retake/Edit/Delete tests |
| INV-25 | F-03 (UC-03), F-04 (UC-04), F-15 + ND-02A | Persistence / Cleanup; all artifact-producing boundaries | StateStore + ArtifactRepository + referencing owners + CleanupArbiter | ArtifactRecord + ArtifactReference | Every first-reference/ref-removal TX; TX-GC-CLAIM atomic zero-edge proof | PCON-01, PCON-02; reference-closure/GC race/crash tests |

---

# Appendix B — Accepted `UNRESOLVED BY CONTRACT` Registry

Все элементы ниже **сознательно приняты в FROZEN**. Они не являются открытыми policy seams: это provenance-status архитектурных решений Conceptual Architecture, которые Product Contract отдельно не ратифицировал. Downstream сохраняет этот статус и не превращает его в молчаливую ратификацию.

| ID | Scope | Freeze disposition |
|---|---|---|
| UC-01 | F-01 — System boundary | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-02 | F-02 — Authoritative truth and conditional commit | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-03 | F-03 — Persistent logical model | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-04 | F-04 — Artifact persistence boundary | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-05 | F-05 — Capture | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-06 | F-06 — Recomputable work vs durable workflows | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-07 | F-07 — Content fencing | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-08 | F-09 — Import | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-09 | F-10 — Edit/OCR semantics; Retake identity separately ratified | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-10 | F-11 — Save snapshot | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-11 | F-12 — Internal document commit | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-12 | F-13 — External export | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-13 | F-14 — Provider capability/evidence | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-14 | F-16 — Projection/UI | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-15 | F-17 — Reconciliation | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-16 | F-19 — Policy ownership | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-17 | F-20 — Verification/production gates | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |
| UC-18 | F-21 — Core exclusions | **ACCEPTED / FROZEN** — inherited provenance status; не open decision |

---

# Appendix C — Accepted `NEW DECISION` Registry

Все перечисленные concrete allocations/mechanisms **сознательно приняты как часть v1.2 FROZEN**. Их изменение является изменением baseline и требует новой версии/freeze.

| ID | Decision | Freeze disposition |
|---|---|---|
| ND-02A | relational artifact-reference closure | **ACCEPTED / FROZEN** |
| ND-02B | provider I/O is forbidden while an authoritative local TX is open | **ACCEPTED / FROZEN** |
| ND-03A | Import is an explicit core boundary | **ACCEPTED / FROZEN** |
| ND-03B | Resource Admission is an explicit core boundary | **ACCEPTED / FROZEN** |
| ND-04A | component allocation v1.1 | **ACCEPTED / FROZEN** |
| ND-05A | staged artifact registry | **ACCEPTED / FROZEN** |
| ND-05B | prioritized short-write admission | **ACCEPTED / FROZEN** |
| ND-05C | one system-wide heavy admission owner | **ACCEPTED / FROZEN** |
| ND-05D | atomic GC claim-token protocol | **ACCEPTED / FROZEN** |
| ND-06C | a persisted SaveManifest is durable recomputable Save intent, not a durable worker attempt | **ACCEPTED / FROZEN** |
| ND-06A | missing-file-after-matching-claim is idempotent physical-delete success | **ACCEPTED / FROZEN** |
| ND-06B | durable WorkspaceSession boundary for one-step Undo | **ACCEPTED / FROZEN** |
| ND-07A | dual retention-time evidence | **ACCEPTED / FROZEN** |
| ND-07B | physical order/selection/import-idempotency representation | **ACCEPTED / FROZEN** |
| ND-07C | physical Recognition schema | **ACCEPTED / FROZEN** |
| ND-07D | generic committed processing result | **ACCEPTED / FROZEN** |
| ND-07E | canonical serialized SaveManifest representation | **ACCEPTED / FROZEN** |
| ND-07F | Save-scoped frozen dependency result | **ACCEPTED / FROZEN** |
| ND-07G | concrete local-output schema | **ACCEPTED / FROZEN** |
| ND-07H | concrete ExportAttempt schema | **ACCEPTED / FROZEN** |
| ND-07I | concrete ImportSession schema | **ACCEPTED / FROZEN** |
| ND-07J | provider qualification/evidence persistence | **ACCEPTED / FROZEN** |
| ND-07K | artifact registry and reference schema | **ACCEPTED / FROZEN** |
| ND-07L | durable bounded-maintenance cursor | **ACCEPTED / FROZEN** |
| ND-07M | Save/Export protection lifecycle | **ACCEPTED / FROZEN** |
| ND-08A | UUIDv4 opaque IDs and sharded app-private layout | **ACCEPTED / FROZEN** |
| ND-08B | SHA-256 as optional stored integrity checksum | **ACCEPTED / FROZEN** |
| ND-08C | qualified durable publication protocol | **ACCEPTED / FROZEN** |
| ND-08D | bounded staged-artifact reclamation | **ACCEPTED / FROZEN** |
| ND-08E | retention metadata purge | **ACCEPTED / FROZEN** |
| ND-08F | transient core persistence domain excluded from OS automatic backup | **ACCEPTED / FROZEN** |
| ND-09A | Recognition restoration on Undo uses an existing committed Recognition bound to the restored immutable ImageVersion | **ACCEPTED / FROZEN** |
| ND-09B | Delete/order/selection update is atomic | **ACCEPTED / FROZEN** |
| ND-09D | Import materializes one authoritative Page per short transaction; imported pages append on commit | **ACCEPTED / FROZEN** |
| ND-09F | Save during an Open ImportSession freezes only Pages already authoritative at the Save consistency point; later imported Pages are excluded from that Save | **ACCEPTED / FROZEN** |
| ND-09C | authoritative Save request is StateStore acceptance/snapshot, and the UI command is not itself a consistency point until that transaction begins | **ACCEPTED / FROZEN** |
| ND-09E | WritingExternal is durably committed before provider I/O so crash ambiguity is representable | **ACCEPTED / FROZEN** |
| ND-12A | Android Kotlin | **ACCEPTED / FROZEN** |
| ND-12B | SQLite + SQLDelight | **ACCEPTED / FROZEN** |
| ND-12C | kotlinx.coroutines + explicit HeavyWorkScheduler admission | **ACCEPTED / FROZEN** |
| ND-12D | OpenCV behind ImagePipeline | **ACCEPTED / FROZEN** |
| ND-12E | Android on-device OCR adapter using ML Kit Text Recognition with bundled model | **ACCEPTED / FROZEN** |
| ND-12F | Android CameraX adapter | **ACCEPTED / FROZEN** |
| ND-12G | Android Storage Access Framework / ContentResolver adapter | **ACCEPTED / FROZEN** |
| ND-12H | Android native PDF generation behind PdfComposer plus explicit read-back structural validator | **ACCEPTED / FROZEN** |
| ND-12I | canonical JSON for small durable workflow/manifest payloads | **ACCEPTED / FROZEN** |
| ND-12J | Android filesystem durability adapter implementing PCON-02 | **ACCEPTED / FROZEN** |
| ND-12K | Android wall-clock + monotonic elapsed-time adapter | **ACCEPTED / FROZEN** |
| ND-12L | SQLDelight migration scripts executed under TX-SCHEMA-MIGRATE | **ACCEPTED / FROZEN** |

---

# Appendix D — Conscious Risk Register

| Risk | Decision | Known counterexample / failure mode | Freeze status |
|---|---|---|---|
| **RISK-01 — Contract provenance** | `UC-01…UC-18` принимаются как inherited Conceptual Architecture decisions без отдельной Product Contract ratification там, где это явно помечено. | Оригинальный Product Contract/Conceptual source может при внешней повторной сверке показать более узкую или иную формулировку, чем embedded FROZEN. | **решение принято, контрпример известен** |
| **RISK-02 — Two-worker upgrade evidence** | Initial profile остаётся `maxConcurrentHeavyJobs = 1`; переход к 2 workers запрещён, пока полный набор Product Contract trigger/soak thresholds не доступен и не пройден. | Частичный benchmark может показать ≥25% throughput improvement, но одновременно нарушить omitted p95/soak requirement, Capture regression, memory или thermal limit. | **решение принято, контрпример известен** |
| **RISK-03 — External provider separation** | ND-02B запрещает provider I/O во время authoritative local TX, даже если это сильнее минимальной формулировки FROZEN boundary. | Будущий provider/adapter, которому для корректности потребовалась бы синхронная transactional coupling с local metadata, не помещается в этот Core без новой версии architecture. | **решение принято, контрпример известен** |
| **RISK-04 — Platform capability qualification** | Android/SAF/filesystem/OCR/PDF technology choices считаются допустимыми только после executable qualification для конкретной implementation/platform version; documentation alone не даёт `PROVEN`. | OEM/provider/API behavior может формально вернуть success, но не обеспечить ожидаемые durability, identity, existence, integrity, readability или bounded-memory properties. | **решение принято, контрпример известен** |
| **RISK-05 — Narrow qualification scope** | Initial production evidence is limited to the exact `QP-X14U-01` Xiaomi 14 Ultra device/OS/app-build tuple; the former 4 GB weakest-tier claim is removed. | A lower-memory device, Android 10 build, different Xiaomi 14 Ultra SKU/OS build or another OEM may violate latency, memory, CameraX, filesystem or provider assumptions even when `QP-X14U-01` passes. | **решение принято, контрпример известен** |
| **RISK-06 — No sudden-power-loss guarantee** | Mandatory v1.2 artifact-publication qualification proves application-process crash persistence only. | Sudden battery/power loss or kernel reset may expose storage behavior not observed by process-death injection. | **решение принято, контрпример известен** |

---

# Freeze Seal

**FROZEN — v1.2 qualification-scope amendment from v1.1, 19.09.2026**

- Parent baseline: **DocScan v1.1 FROZEN**
- Core sections frozen: **13 / 13**
- Core invariants frozen: **25 / 25**
- Accepted `UNRESOLVED BY CONTRACT`: **18**
- Accepted inherited `NEW DECISION`: **49**
- v1.2 baseline amendments: **2** (`AMEND-QS-01`, `AMEND-DUR-01`)
- Open v1.2 scope-consistency findings: **0**
- Conscious risks accepted with known counterexample: **6**

**Qualification release rule:** empirical PASS applies only to the exact recorded `QP-X14U-01` tuple unless a later baseline explicitly widens scope.

**Mutation rule:** semantic modification invalidates this seal and requires a new versioned frozen artifact.
