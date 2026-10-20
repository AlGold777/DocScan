# PHYSICAL QUALIFICATION RUNBOOK — DUT-X14U-01

## Purpose

Execute the already-developed harness on the one physical Xiaomi 14 Ultra qualification tuple. This stage is evidence collection, not harness development.

## 1. Connect and identify the physical device

Use `adb devices -l` and explicitly select the Xiaomi serial. Never rely on the default adb target while an emulator is also connected.

Record and freeze:

- manufacturer;
- model;
- SKU / observed RAM configuration;
- Android version;
- API level;
- build fingerprint;
- security patch;
- kernel version;
- app-private filesystem type;
- CPU ABI list;
- `/proc/meminfo` MemTotal;
- app package/version/build hash.

The application process must be ARM64.

If the physical device cannot be identified unambiguously, qualification is blocked.

## 2. Qualification tuple rule

All mandatory S1–S6 evidence for one cycle must use the same:

- physical phone;
- OS build fingerprint;
- app build hash.

Any OS update, different Xiaomi unit/SKU, or changed app build starts a new qualification cycle.

## 3. Clean qualification evidence root

Before starting a new cycle, archive any prior physical evidence and create a new cycle directory. Never merge old emulator/physical results into the new cycle.

## 4. Run S1

Run exact frozen QUALIFICATION counts:

- D1–D8;
- 100 process-death repetitions per barrier;
- 800 mandatory runs total.

Hard-power extension is optional. If not executed, record:

- `hard_power_extension_executed=false`;
- `hard_power_runs_completed=0`;
- `sudden_power_loss_qualified=false`.

Do not mark S1 incomplete merely because hard-power extension was not executed.

## 5. Run S2

Run exact frozen workloads:

- C-W0 Idle;
- C-W1 OCR pressure;
- C-W2 Save pressure.

For each workload:

- 100 Capture commands per iteration;
- 20 iterations;
- 2,000 Capture commands/workload;
- 6,000 Capture commands total.

## 6. Run S3

Precondition: real finite OCR input envelope is explicitly available.

If absent: S3=`INCOMPLETE` and do not invent one.

If present, run:

- O-F1/O-F2/O-F3;
- 20 batches × 50 sequential OCR runs per fixture = 1,000/fixture;
- 3,000 fixture runs total;
- 500 contention sets.

## 7. Run S4

Precondition: real `P_MAX`, page-raster envelope and render-parameter envelope are explicitly available.

If absent: S4=`INCOMPLETE` and do not invent them.

If present, run the unique required page-count points:

- 1;
- ceil(P_MAX/4);
- ceil(P_MAX/2);
- P_MAX;

50 runs per unique point + 200 contention runs at P_MAX.

Use the recorded independent host-side PDF validator/version for the full cycle.

## 8. Run S5

Precondition: complete intended production provider tuple inventory for this exact Xiaomi OS build is explicitly available.

If absent: S5=`INCOMPLETE` and do not invent support scope.

For every real intended tuple run:

- 100 definite-success;
- 100 definite-known-failure;
- 100 E1;
- 100 E2.

If a real provider has no reproducible mandatory definite-failure scenario, that tuple is `INCOMPLETE`; do not use a mock substitute as qualification evidence.

## 9. Run S6

Run every R01–R41 hook exactly 100 times:

- 41 hooks;
- 4,100 process-death cases total.

Preserve all three required diagnostic snapshots where the protocol requires recovery/idempotency validation.

## 10. Result classification

For each S1–S6 derive only:

- PASS — every mandatory case completed and no frozen kill-condition observed;
- FAIL — at least one frozen kill-condition observed;
- INCOMPLETE — mandatory case/input/evidence missing.

Never convert INCOMPLETE into PASS based on emulator results, documentation, expectation, or estimates.

## 11. Completion gate

Physical execution is ready for handoff only when:

- each S1–S6 has a final PASS/FAIL, **or** any unavoidable INCOMPLETE is explicitly reported as blocking Post-Spikes;
- all completed physical results identify the exact same Xiaomi tuple/build;
- raw per-run evidence is retained and addressable;
- no aggregate substitutes for a missing mandatory scenario.

If any mandatory spike remains INCOMPLETE, the valid architecture pipeline remains stopped before Post-Spikes.
