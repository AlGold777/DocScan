# RESULTS PACKAGE CONTRACT — Handoff after Spike Harness Execution

## Required directory

Create one final package, for example:

`/Users/restart/Downloads/DocScanSpikeHarness/qualification-results/`

## Required top-level files

1. `qualification-manifest.json`
2. `S1-DURABILITY.json`
3. `S2-CAMERA-BURST.json`
4. `S3-OCR-MEMORY.json`
5. `S4-PDF-COMPOSITION.json`
6. `S5-SAF-EXPORT.json`
7. `S6-PROCESS-DEATH.json`
8. `SHA256SUMS.txt`
9. `FINAL_EXECUTION_REPORT.md`

## Required evidence directories

Preserve raw evidence by spike:

```text
raw/
  S1/
  S2/
  S3/
  S4/
  S5/
  S6/
logs/
dumps/
artifacts/
```

Each mandatory run must remain traceable from aggregate result → per-run record → raw log/dump/artifact checks.

## qualification-manifest.json must identify

- `dut_id = DUT-X14U-01`;
- manufacturer/model/SKU;
- Android/API/build fingerprint;
- security patch;
- kernel;
- filesystem;
- ABI;
- MemTotal;
- app package/version/build hash;
- host runner revision/hash;
- execution start/end times;
- whether hard-power extension was executed.

## Aggregate result integrity

Every S1–S6 aggregate must:

- contain only actual measured/observed fields;
- identify execution mode;
- identify exact device/build tuple;
- link/reference raw evidence locations;
- contain final PASS/FAIL/INCOMPLETE;
- preserve frozen kill-condition semantics.

Do not fill missing physical fields with emulator data.

## FINAL_EXECUTION_REPORT.md

Keep it short and factual:

1. Build hash / APK path.
2. Physical DUT identity.
3. S1 outcome.
4. S2 outcome.
5. S3 outcome.
6. S4 outcome.
7. S5 outcome.
8. S6 outcome.
9. Any INCOMPLETE blocker and exact missing prerequisite.
10. Evidence root path.
11. Explicit statement on hard-power scope.
12. Explicit statement that no Post-Spikes work was performed.

## Final stop condition

After packaging, STOP.

The next external step is to provide:

- `01_DocScan_v1.2_FROZEN_Xiaomi14Ultra.md`;
- `02_DocScan_Spike_Protocol_v1.1_FROZEN_Xiaomi14Ultra.md`;
- this complete physical `SPIKE_RESULTS` package

to the separate POST-SPIKES review.
