# GRAVITY FULL EXECUTION PLAN — DocScan Spike Harness S1–S6

## 0. Mission

Take the existing Android project at:

`/Users/restart/Downloads/DocScanSpikeHarness`

and turn it into a complete executable spike harness for **S1–S6** from `02_DocScan_Spike_Protocol_v1.1_FROZEN_Xiaomi14Ultra.md`.

Develop and debug primarily on `emulator-5554`. Do not wait for Xiaomi connectivity during harness development.

The harness must have two clearly separated execution modes:

1. **DEV mode** — reduced repetitions for engineering/debugging on emulator. Never qualification evidence.
2. **QUALIFICATION mode** — exact mandatory counts/procedures from Spike Protocol v1.1, intended only for the physical `DUT-X14U-01` tuple.

No DEV count may modify the QUALIFICATION count compiled/configured from the frozen protocol.

---

# Phase 1 — Audit and stabilize the existing project

1. Inspect the full Gradle project before changing code.
2. Record:
   - package/application ID;
   - test package;
   - AndroidJUnitRunner;
   - compile/target/min SDK;
   - AGP/Gradle/JDK versions;
   - current D1 skeleton files;
   - current evidence path.
3. Build both app and androidTest APKs.
4. Run the existing D1 skeleton on `emulator-5554` and confirm the current baseline before refactoring.
5. Preserve a short baseline log under the project workspace.

Completion gate: existing skeleton builds and D1 baseline behavior is understood.

---

# Phase 2 — Shared harness infrastructure

Implement the shared infrastructure once before implementing the full spikes.

## 2.1 Stable run identity

Every operation/run must have:

- `spike_id`;
- `run_id`;
- `case_id`;
- `operation_id`;
- device build fingerprint;
- app build hash;
- monotonic start/end timestamps;
- injection hook when applicable.

## 2.2 Evidence writer

Implement machine-readable per-run JSON matching the frozen schema in §9 of the protocol.

Evidence root for device-side output:

`/sdcard/Android/data/com.example.docscanspike/files/evidence/`

Use a stable subdirectory structure by spike/case/run. Preserve raw logs and diagnostic snapshots separately from aggregates.

## 2.3 Read-only diagnostic snapshot

Provide a diagnostic export that can inspect, without repairing/mutating:

- relevant authoritative metadata;
- ArtifactRecord state;
- ArtifactReference state;
- file existence/size/SHA-256;
- workflow state;
- HeavyWorkScheduler state;
- provider-call count;
- StateStore write-TX depth.

## 2.4 Host orchestrator

Implement a host-side script/program that can:

- build/install app + test APK;
- target a chosen adb serial explicitly;
- reset deterministic test seed;
- start one case;
- wait for a named hook;
- trigger process death when required;
- restart/resume the case;
- pull evidence;
- validate JSON syntax;
- compute aggregates;
- continue through all cases unattended where Android interaction permits it.

Do not rely on a single instrumentation method surviving its own process kill. Use a host-orchestrated case protocol when process death would terminate instrumentation.

## 2.5 Execution-mode separation

Implement a configuration surface such as:

- `DEV`
- `QUALIFICATION`

The mode must be written into every result.

`QUALIFICATION` uses exact frozen counts. `DEV` may use reduced counts but must never be accepted by the qualification completion gate.

## 2.6 Result evaluator

For each spike, implement an evaluator that derives only:

- `PASS`;
- `FAIL`;
- `INCOMPLETE`

from the frozen kill/completion conditions.

Do not invent additional pass thresholds.

Completion gate: one shared runner can execute a trivial synthetic case, kill/restart where applicable, write/pull JSON, and evaluate it.

---

# Phase 3 — S1-DURABILITY implementation

Implement exactly the S1 barriers:

- D1_ENCODER_FINISHED
- D2_USERSPACE_FLUSH_FINISHED
- D3_FILE_PERSISTENCE_PRIMITIVE_RETURNED
- D4_FINAL_PATH_PUBLISHED
- D5_PUBLICATION_BOUNDARY_RETURNED
- D6_STAGED_RECORD_TX_COMMITTED
- D7_FIRST_REFERENCE_TX_BEFORE_COMMIT
- D8_FIRST_REFERENCE_TX_COMMITTED

Implement the minimal state necessary to exercise the frozen oracle:

- deterministic artifact payload;
- expected length/SHA-256;
- staged/committed ArtifactRecord;
- ArtifactReference;
- owning authoritative row;
- first-reference transaction.

Implement application-process death at each barrier and post-restart diagnostics before mutation.

### DEV validation

Run **3 repetitions per D1–D8** on emulator = 24 development cases.

### QUALIFICATION behavior

The harness must be capable of the frozen mandatory **100 repetitions per D1–D8** on `DUT-X14U-01` = 800 mandatory process-death runs.

Optional hard-power extension must remain separate and off by default. Absence of hard-power execution must produce `sudden_power_loss_qualified=false`, not `INCOMPLETE`.

Completion gate for development: all D1–D8 exist, 24 DEV cases complete, aggregate evaluator runs, and any violations are reported truthfully.

---

# Phase 4 — S2-CAMERA-BURST implementation

Implement hooks C1–C7 and correlation by operation ID:

- C1_SHUTTER_REQUEST_ACCEPTED
- C2_FRAME_ACQUIRED
- C3_ENCODE_FINISHED
- C4_DURABLE_PUBLICATION_RETURNED
- C5_TX_CAPTURE_BEGIN
- C6_TX_CAPTURE_COMMITTED
- C7_PAGE_VISIBLE

Implement the three frozen workloads:

- C-W0 Idle
- C-W1 OCR pressure holding sole heavy permit
- C-W2 Save pressure holding sole heavy permit

Implement metrics and oracle exactly as protocol §4.

### DEV validation

Use a reduced smoke profile sufficient to validate mechanics, e.g. a small number of capture commands/workload and repetitions, but label every output `DEV` and keep qualification counts independently encoded.

### QUALIFICATION behavior

Must support exact frozen workload:

- 100 Capture commands per workload iteration;
- 20 iterations per workload;
- 2,000 Capture commands per workload;
- 6,000 total across C-W0/C-W1/C-W2.

Completion gate for development: all three workloads execute on emulator, metrics are populated, and evaluator handles all S2 kill conditions.

---

# Phase 5 — S3-OCR-MEMORY implementation

Implement actual ML Kit OCR candidate execution and HeavyWorkScheduler instrumentation required by protocol §5.

Must record:

- active heavy jobs;
- decoded-image token bytes;
- owner/job kind;
- PSS/RSS diagnostic data;
- OCR adapter attempted StateStore writes.

## Mandatory non-invention gate

Do **not** invent the product OCR input envelope.

The harness must read the following from explicit qualification configuration or actual implementation configuration:

- max accepted width;
- max accepted height;
- accepted formats;
- orientation policy;
- imported-image size policy.

If these inputs do not exist, official S3 result must remain `INCOMPLETE`.

Gravity may still build and DEV-test the mechanics with clearly labelled synthetic fixtures, but synthetic limits cannot populate qualification fields.

### QUALIFICATION behavior once envelope exists

Support O-F1/O-F2/O-F3 and exact protocol workload:

- 20 batches × 50 sequential OCR runs per fixture;
- 1,000 runs per fixture;
- 3,000 fixture runs total;
- 500 contention sets of OCR + Processing + Save-render.

Kill-condition enforcement remains exactly the frozen one, including:

- decoded-image tokens ≤ 96 MiB;
- peak active heavy jobs ≤ 1;
- no unaccounted heavy execution;
- no OCR direct authoritative write;
- no OOM/ANR.

Completion gate for development: OCR/metrics/admission/evaluator mechanics work. Qualification readiness additionally requires a real finite input envelope.

---

# Phase 6 — S4-PDF-COMPOSITION implementation

Implement Save freeze/composition/validation mechanics from protocol §6:

- SaveManifest freeze and immutable hash check;
- HeavyWorkScheduler admission;
- decoded-memory accounting;
- staged PDF;
- app-side read-back structural validator;
- local commit;
- host-side independent PDF structural validation;
- page-count/order/first-last render verification.

## Mandatory non-invention gate

Do **not** invent:

- `P_MAX`;
- max accepted page raster dimensions/format;
- max per-page render parameter envelope.

Read these from explicit product/qualification configuration. If absent, qualification S4 = `INCOMPLETE`.

DEV mode may use synthetic small fixtures only to validate mechanics and must label them as non-qualification.

### QUALIFICATION behavior once envelope exists

Support page-count points:

- 1;
- ceil(P_MAX/4);
- ceil(P_MAX/2);
- P_MAX.

Run 50 times per unique point plus 200 contention Save runs at P_MAX, exactly as frozen.

Completion gate for development: composition, validators, memory accounting, manifest immutability check and evaluator all work. Qualification readiness additionally requires real P_MAX/raster/render limits.

---

# Phase 7 — S5-SAF-EXPORT implementation

Implement the export state/evidence harness from protocol §7:

- CommittedExportArtifact fixture;
- TX-EXPORT-BEGIN / WritingExternal;
- provider call outside authoritative write TX;
- TX-EXPORT-END;
- ProviderOperationObservation;
- E1/E2 process-death hooks;
- startup WritingExternal→UnknownOutcome barrier;
- read-only revalidation;
- provider-write counter;
- evidence-dimension checks.

## Mandatory non-invention gate

Do **not** invent the intended production provider tuple inventory.

Qualification tuples must come from explicit product support scope on the exact Xiaomi OS build:

`adapter_id + provider package/version + OS build + destination/access class + permission mode + validation-suite version`

If the complete intended production tuple inventory is unavailable, qualification S5 = `INCOMPLETE`.

DEV mode may exercise mechanics against emulator-accessible providers, but those tuples are development-only.

### QUALIFICATION behavior per real tuple

Support exactly:

- 100 definite-success runs;
- 100 definite-known-failure runs;
- 100 E1 ambiguous-window runs;
- 100 E2 ambiguous-window runs.

Minimum 400 runs per qualification tuple.

A provider with no reproducible definite-known-failure scenario leaves that tuple `INCOMPLETE`; do not substitute a mock for qualification evidence.

Completion gate for development: state machine, counters, E1/E2 orchestration and evidence evaluator work. Qualification readiness additionally requires the real tuple inventory and reproducible mandatory scenarios.

---

# Phase 8 — S6-PROCESS-DEATH implementation

Implement **all R01–R41 hooks** from protocol §8.4, grouped exactly as frozen:

- R01–R03 Capture/artifact
- R04–R07 Retake/Edit
- R08–R13 OCR/Processing
- R14–R19 Import
- R20–R25 Save
- R26–R30 Export
- R31–R35 Cleanup
- R36–R39 Metadata purge/maintenance
- R40–R41 Schema migration

For every hook implement the frozen hook-specific oracle plus global recovery oracle.

The host orchestrator must perform:

`seed → operation → exact hook → process kill → restart → startup barrier → dump #1 → one recovery pass → dump #2 → recovery again → dump #3 → idempotency evaluation`

### DEV validation

Run **3 repetitions per R01–R41** on emulator = 123 development crash cases.

### QUALIFICATION behavior

Support exact frozen **100 repetitions per R01–R41** = 4,100 qualification crash cases.

Completion gate for development: all 41 hooks exist, all 123 DEV cases run, global + hook-specific evaluators execute, and violations are reported truthfully.

---

# Phase 9 — Full emulator development pass

After S1–S6 mechanics are implemented:

1. Clean build from scratch.
2. Install on `emulator-5554`.
3. Run the full DEV suite unattended as far as possible.
4. Validate every generated JSON file.
5. Verify aggregate files for S1–S6 can be generated.
6. Confirm every aggregate/result explicitly says `execution_mode=DEV` and identifies emulator device.
7. Confirm no code path can mistake DEV results for qualification results.
8. Fix all harness bugs discovered.

The goal of Phase 9 is harness correctness, **not** protocol PASS.

---

# Phase 10 — Qualification build freeze

When the harness is stable:

1. Produce one release-equivalent qualification APK plus required test/orchestrator artifacts.
2. Record SHA-256 of APK(s) and relevant host runner revision/hash.
3. Do not change the app build during a physical qualification cycle.
4. If code changes are required after physical runs begin, invalidate that cycle and start a new one with a new build hash.
5. Generate a qualification manifest template containing all required device/build identity fields.

Deliver paths for:

- app APK;
- androidTest APK if used;
- host runner;
- SHA-256 manifest.

---

# Phase 11 — Physical Xiaomi qualification

Follow `04_PHYSICAL_QUALIFICATION_RUNBOOK.md` exactly.

Do not substitute emulator results for missing physical cases.

If S3/S4/S5 mandatory non-invention inputs are still absent, record those spikes `INCOMPLETE`; do not fabricate configuration to unlock Post-Spikes.

---

# Phase 12 — Result packaging and STOP

Follow `05_RESULTS_PACKAGE_CONTRACT.md`.

After producing the complete evidence package, STOP.

Do not perform POST-SPIKES architecture correction, Implementation Architecture, or implementation slices. Those are downstream tasks for a separate reviewed step.

---

# Autonomous-working rule

Gravity should continue from one phase to the next without asking the user to press Android Studio buttons or solve ordinary build/runtime errors.

Ask the user only when a genuinely external action is required, such as:

- authorizing USB debugging on the physical Xiaomi;
- choosing/approving a real SAF destination that cannot be automated;
- supplying missing product qualification inputs that the frozen protocol explicitly forbids inventing.

When blocked by such an external dependency, report the exact blocker and continue any independent implementation work that remains possible.
