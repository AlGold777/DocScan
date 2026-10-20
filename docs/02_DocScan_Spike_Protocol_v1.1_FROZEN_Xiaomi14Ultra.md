# DocScan — Spike Protocol v1.1 FROZEN — Xiaomi 14 Ultra

> **SPIKE PROTOCOL v1.1 FROZEN — Xiaomi 14 Ultra physical qualification specification, 19.09.2026**  
> **Normative input:** `DocScan v1.2 FROZEN — Xiaomi 14 Ultra qualification baseline`.  
> **Parent protocol:** `Spike Protocol v1.0 FROZEN`.  
> **Status:** immutable normative test baseline.  
> **Evidence state at freeze:** no empirical spike results are included.  
> **Freeze rule:** DUT identity rules, procedures, metrics, kill-conditions, mandatory hooks/tuples, result schemas and completion gate are frozen before empirical execution.  
> **Mutation rule:** any semantic change requires a new version (`v1.2+`). Results collected under v1.1 remain attributable to v1.1 and must not be silently reinterpreted under a changed protocol.  
> **Pipeline state:** `DocScan v1.2 FROZEN → Spike Protocol v1.1 FROZEN → PHYSICAL EXECUTION → STOP`.

---

# DocScan — SPIKE PROTOCOL: FINAL SPECIFICATION FOR PHYSICAL EXECUTION

## Status and scope

**Normative input:** DocScan v1.2 FROZEN.  
**Current pipeline state:** `v1.2 FROZEN → Spike Protocol v1.1 FROZEN → PHYSICAL EXECUTION → STOP`.  
**Purpose:** generate empirical evidence on the one physically available qualification device `QP-X14U-01` / `DUT-X14U-01`. This document contains **no measured result** and makes no weakest-tier inference.

`QA-01…QA-07` are risk-area labels carried from the rejected provisional Post-Spikes/Implementation-Architecture attempt. They are **not themselves amendments to FROZEN**. The pre-execution baseline changes are explicitly limited to `AMEND-QS-01` and `AMEND-DUR-01` in DocScan v1.2 FROZEN. Any further architecture correction based on empirical behavior must occur only in Post-Spikes and must cite completed results from this protocol.

All execution counts, hook numbers, page counts, timeouts, and FROZEN limits in this document are **test parameters or normative thresholds**, not measurement results.

---

# 1. QA → Spike coverage

| QA label | Risk area | Required spike(s) | Status |
|---|---|---|---|
| QA-01 | crash-consistency of local authoritative state | **S1-DURABILITY + S6-PROCESS-DEATH** | protocol ready |
| QA-02 | qualified artifact publication — application-process crash scope | **S1-DURABILITY** | protocol ready |
| QA-03 | camera / CameraX burst and Capture isolation | **S2-CAMERA-BURST** | protocol ready |
| QA-04 | OCR / ML Kit memory and heavy-admission behavior | **S3-OCR-MEMORY** | protocol ready |
| QA-05 | PDF composition, structural validation, bounded memory | **S4-PDF-COMPOSITION** | protocol ready |
| QA-06 | SAF/provider export semantics and qualification | **S5-SAF-EXPORT** | protocol ready |
| QA-07 | process-death recovery | **S6-PROCESS-DEATH** | protocol ready |

No `[MISSING PROTOCOL — QA-0X]` remains.

---

# 2. Common execution contract

## 2.1 Device-under-test profile — DUT-X14U-01

Every mandatory spike must run on the **same physical Xiaomi 14 Ultra** designated `DUT-X14U-01`.

Before the first spike, record and freeze the execution tuple:

- manufacturer;
- exact model;
- exact SKU / installed RAM configuration as observed;
- Android version;
- API level;
- build fingerprint;
- security-patch level;
- kernel version;
- filesystem type for app-private storage;
- app package/version/build hash;
- `ro.product.cpu.abilist`;
- `/proc/meminfo` `MemTotal` as observed on the device.

Qualification preconditions:

1. The application process must run as ARM64 (`arm64-v8a`) because the product design target remains ARM64.
2. S1–S6 for one qualification cycle must use the **same physical phone**, same OS build fingerprint and same app build hash.
3. An OS update, factory reset that changes the build fingerprint, device replacement, different SKU or different app build creates a different qualification tuple and requires a new complete qualification cycle unless a later protocol explicitly defines narrower rerun rules.
4. Results from this device **must not** be interpreted as evidence for Android 10, 4 GB RAM, another Xiaomi 14 Ultra SKU/build, or any other device.
5. An emulator cannot fill the normative result templates.

If the actual application process is not ARM64, the suite is `BLOCKED` before S1.

## 2.2 Test build

Use one fixed build hash for all spikes unless a spike explicitly qualifies a replacement adapter. The build must be release-equivalent except for test-only instrumentation hooks and read-only diagnostics.

Required test instrumentation:

1. unique `run_id` and `operation_id` on every exercised operation;
2. monotonic timestamp for every named test hook;
3. host-side log capture that survives application process death;
4. read-only post-restart diagnostic export of:
   - authoritative metadata rows relevant to the run;
   - ArtifactRecord / ArtifactReference state;
   - file existence, length and checksum where applicable;
   - workflow state relevant to the run;
5. HeavyWorkScheduler diagnostics:
   - current admitted heavy-job count;
   - current decoded-image token bytes;
   - peak values during the run;
6. a provider-call counter and StateStore-write-TX-depth counter around every external provider call;
7. a deterministic crash hook capable of killing the application process at a named barrier without executing cleanup code.

The diagnostic interface must not repair, migrate, retry, delete or otherwise mutate the state being inspected.

## 2.3 Process-death injection

For mandatory app-process death, the harness must terminate the process at the named hook by an abrupt process-kill mechanism. A normal Activity finish, back navigation, graceful service stop, or app-requested clean shutdown is invalid.

## 2.4 Optional hard-power qualification extension

Sudden hardware power-loss / forced-kernel-reset durability is **outside the mandatory v1.1 completion gate**, matching `AMEND-DUR-01` in DocScan v1.2 FROZEN.

If — and only if — the exact Xiaomi 14 Ultra test unit exposes a safe, reproducible mechanism that bypasses normal application and OS shutdown, an additional hard-power extension may be executed. Acceptable mechanisms are:

- genuine hardware power interruption on the same physical DUT; or
- a forced kernel panic/reset on the same device/storage/filesystem stack.

`adb reboot`, normal power-off, app force-stop or Activity termination are not hard-power evidence.

Not executing this extension **does not make S1 INCOMPLETE**. It means only that `sudden_power_loss_qualified = false`. No artifact or later Post-Spikes document may claim sudden-power-loss durability from the mandatory v1.1 suite alone.

If the extension is executed and observes a corruption condition that is also an S1 kill-condition, S1 is `FAIL`; optional execution cannot be used to ignore contrary evidence.

## 2.5 Result vocabulary

Each spike receives exactly one outcome:

- `PASS` — every mandatory case executed and no kill-condition occurred;
- `FAIL` — at least one kill-condition occurred;
- `INCOMPLETE` — any mandatory case, device identity field, provider tuple, raw artifact, or result field is missing.

`PASS` means only “no kill-condition observed in this specified test set on this exact Xiaomi 14 Ultra / OS build / app build tuple”. It is not a universal platform, device-family, Android-version or RAM-tier claim.

---

# 3. S1-DURABILITY — qualified artifact publication on DUT-X14U-01

## 3.1 ID and QA linkage

**Spike ID:** `S1-DURABILITY`  
**QA coverage:** QA-01, QA-02.  
**FROZEN trace:** F-04; INV-03; INV-25; ND-08C; PCON-02; ND-12J; Step 13 staged-artifact/crash-window evidence; AMEND-DUR-01.

This mandatory spike tests the v1.2 claim that an implementation may call artifact bytes qualified for authoritative reference only after the publication boundary has survived abrupt **application-process death** on the exact `DUT-X14U-01` tuple. It does not by itself qualify sudden hardware power loss.

## 3.2 Target device

`DUT-X14U-01` only.

## 3.3 Required adapter barriers

The filesystem persistence adapter must expose the following **test-only barriers** without changing production ordering:

- `D1_ENCODER_FINISHED`
- `D2_USERSPACE_FLUSH_FINISHED`
- `D3_FILE_PERSISTENCE_PRIMITIVE_RETURNED`
- `D4_FINAL_PATH_PUBLISHED`
- `D5_PUBLICATION_BOUNDARY_RETURNED`
- `D6_STAGED_RECORD_TX_COMMITTED`
- `D7_FIRST_REFERENCE_TX_BEFORE_COMMIT`
- `D8_FIRST_REFERENCE_TX_COMMITTED`

`D5` is the earliest point at which the adapter is allowed to report the artifact as qualified for authoritative reference under the v1.2 process-crash scope.

## 3.4 Fixture

For every run create a unique artifact with:

- unique `artifact_id`;
- deterministic payload generated from `run_id`;
- host-side expected byte length;
- host-side expected SHA-256;
- no pre-existing file or metadata row using that ID.

The payload size is fixed for the full S1 run and must be recorded in the result; it is not prescribed by FROZEN.

## 3.5 Exact mandatory procedure — application-process death

For each barrier `D1…D8`:

1. Reset the app to the clean S1 seed state.
2. Start one publication operation with a new `run_id`.
3. Wait until the named barrier is emitted.
4. Immediately kill the app process with the abrupt process-death mechanism.
5. Restart the app.
6. Before any maintenance or recovery mutation is allowed, export the read-only diagnostic state.
7. Verify file existence, byte length and SHA-256.
8. Verify `ArtifactRecord` state.
9. Verify every `ArtifactReference` for the artifact.
10. Verify the owning authoritative entity, if any.
11. Store the raw host log, diagnostic dump and checksum evidence under the `run_id`.
12. Repeat **100 times for that barrier**.

Expected state oracle:

- At `D1–D4`: no authoritative reference may exist. Artifact survival is not required because publication success has not yet been established.
- At `D5`: if the adapter had already returned publication success, the exact bytes must survive application-process death + restart. No authoritative reference is yet required.
- At `D6`: exact bytes and the `STAGED_DURABLE` record must survive; no authoritative reference is required yet.
- At `D7`: the first-reference transaction must be either wholly uncommitted or, if the commit completed before death, wholly committed. Partial owning row/reference state is forbidden.
- At `D8`: exact bytes, `COMMITTED` ArtifactRecord, ArtifactReference and owning authoritative row must all survive together.

## 3.6 Optional hard-power extension

If a valid mechanism under §2.4 is available, run the same fixture at `D3`, `D5`, `D6`, `D8`:

1. Reset to clean S1 seed state.
2. Start one operation.
3. Wait until the barrier is observed by the host runner.
4. Trigger the valid hard-power/kernel-reset mechanism.
5. Boot the same physical device.
6. Start the app with maintenance/recovery mutation disabled until the initial diagnostic snapshot is exported.
7. Perform the same byte/metadata/reference oracle as §3.5.
8. Repeat **100 times for that barrier**.

If this extension is not executed, record `sudden_power_loss_qualified = false` and `hard_power_runs_completed = 0`; this does not make mandatory S1 incomplete.

## 3.7 Metrics

Measure and record:

- `process_death_runs_completed` — count;
- `hard_power_extension_executed` — Boolean;
- `hard_power_runs_completed` — count;
- `sudden_power_loss_qualified` — Boolean;
- `publication_success_then_missing_bytes` — count;
- `publication_success_then_length_mismatch` — count;
- `publication_success_then_checksum_mismatch` — count;
- `authoritative_ref_to_missing_or_corrupt_bytes` — count;
- `partial_first_reference_tx_state` — count;
- `duplicate_artifact_identity_or_publication` — count;
- `staged_record_missing_after_D6` — count;
- `post_D8_incomplete_commit_set` — count;
- per-run `D1→D5` duration — ms;
- per-run first-reference TX duration — ms.

Latency values are recorded as evidence; FROZEN supplies no absolute S1 latency kill threshold.

## 3.8 Kill-condition

S1 = `FAIL` if **any** mandatory run — or any optional hard-power run that was actually executed — produces at least one of:

- `publication_success_then_missing_bytes ≥ 1`;
- `publication_success_then_length_mismatch ≥ 1`;
- `publication_success_then_checksum_mismatch ≥ 1`;
- `authoritative_ref_to_missing_or_corrupt_bytes ≥ 1`;
- `partial_first_reference_tx_state ≥ 1`;
- `duplicate_artifact_identity_or_publication ≥ 1`;
- `staged_record_missing_after_D6 ≥ 1`;
- `post_D8_incomplete_commit_set ≥ 1`.

S1 = `INCOMPLETE` if any mandatory D1…D8 application-process-death barrier does not complete its required run count, required device/build identity is missing, or mandatory raw evidence is missing. Absence of a hard-power mechanism is **not** an INCOMPLETE condition in v1.1.

## 3.9 Result template — S1

```json
{
  "spike_id": "S1-DURABILITY",
  "qa": ["QA-01", "QA-02"],
  "dut_id": "DUT-X14U-01",
  "device": {
    "manufacturer": null,
    "model": null,
    "sku": null,
    "android_version": null,
    "api_level": null,
    "build_fingerprint": null,
    "security_patch": null,
    "kernel_version": null,
    "filesystem": null,
    "abi": null,
    "memtotal_kib": null
  },
  "app_build_hash": null,
  "payload_bytes": null,
  "process_death_runs_completed": null,
  "hard_power_extension_executed": null,
  "hard_power_method": null,
  "hard_power_runs_completed": null,
  "sudden_power_loss_qualified": null,
  "publication_success_then_missing_bytes": null,
  "publication_success_then_length_mismatch": null,
  "publication_success_then_checksum_mismatch": null,
  "authoritative_ref_to_missing_or_corrupt_bytes": null,
  "partial_first_reference_tx_state": null,
  "duplicate_artifact_identity_or_publication": null,
  "staged_record_missing_after_D6": null,
  "post_D8_incomplete_commit_set": null,
  "publish_latency_ms_p50": null,
  "publish_latency_ms_p95": null,
  "publish_latency_ms_p99": null,
  "first_reference_tx_ms_p50": null,
  "first_reference_tx_ms_p95": null,
  "first_reference_tx_ms_p99": null,
  "raw_evidence_location": null,
  "outcome": null
}
```

---

# 4. S2-CAMERA-BURST — CameraX / Capture isolation and burst evidence

## 4.1 ID and QA linkage

**Spike ID:** `S2-CAMERA-BURST`  
**QA coverage:** QA-03.  
**FROZEN trace:** F-05; F-08; INV-04; INV-08; ND-12F; Step 13 Capture p95/p99 + burst behavior.

## 4.2 Target device

`DUT-X14U-01` only. Capture latency/throughput evidence is valid only for the recorded Xiaomi 14 Ultra / OS build / app build tuple and is not weakest-tier evidence.

## 4.3 Required hooks

- `C1_SHUTTER_REQUEST_ACCEPTED`
- `C2_FRAME_ACQUIRED`
- `C3_ENCODE_FINISHED`
- `C4_DURABLE_PUBLICATION_RETURNED`
- `C5_TX_CAPTURE_BEGIN`
- `C6_TX_CAPTURE_COMMITTED`
- `C7_PAGE_VISIBLE`

The harness must also log HeavyWorkScheduler permit ownership during the entire capture.

## 4.4 Exact workload matrix

Run exactly these three workloads:

- **C-W0 Idle:** no heavy job active.
- **C-W1 OCR pressure:** one real OCR job holds the sole heavy permit for the capture interval.
- **C-W2 Save pressure:** one real Save decode/render/validation job holds the sole heavy permit for the capture interval.

For each workload:

1. Cold-start the app.
2. Confirm HeavyWorkScheduler state for the workload.
3. Execute **100 Capture commands** using the automated UI driver.
4. The next command is issued immediately after `C1_SHUTTER_REQUEST_ACCEPTED` for the prior command; the driver does not wait for durable commit or Page visibility.
5. For every accepted command, correlate one `operation_id` through `C1…C7`.
6. After the 100th command, wait until every accepted command has either committed or produced an explicit typed failure.
7. Export the authoritative Page/ImageVersion/ArtifactReference state.
8. Verify that every persistent Page corresponds to exactly one committed Capture and that no persistent Page became visible before `C6`.
9. Repeat the full 100-command workload **20 times**.

Total required Capture commands: **2,000 per workload**.

## 4.5 Metrics

Record separately for C-W0/C-W1/C-W2:

- `accepted_capture_count` — count;
- `committed_capture_count` — count;
- `typed_failure_count` — count;
- `duplicate_persistent_page_count` — count;
- `persistent_page_before_tx_commit_count` — count;
- `committed_capture_missing_page_count` — count;
- `capture_waited_for_heavy_permit_count` — count;
- `request_to_tx_commit_ms` — per operation, summarized p50/p95/p99;
- `request_to_page_visible_ms` — per operation, summarized p50/p95/p99;
- `max_state_store_write_queue_depth` — count;
- `ANR_count` — count;
- `OOM_count` — count.

FROZEN requires p95/p99 and burst evidence but supplies **no absolute Capture-latency SLA**. Therefore no new ms threshold is invented here.

## 4.6 Kill-condition

S2 = `FAIL` if any workload produces:

- `duplicate_persistent_page_count ≥ 1`;
- `persistent_page_before_tx_commit_count ≥ 1`;
- `committed_capture_missing_page_count ≥ 1`;
- `capture_waited_for_heavy_permit_count ≥ 1`;
- `ANR_count ≥ 1`;
- `OOM_count ≥ 1`.

A high p95/p99 value by itself does not fail S2 because FROZEN contains no absolute ms threshold. It becomes empirical input for the later Post-Spikes decision.

S2 = `INCOMPLETE` if any of C-W0/C-W1/C-W2 is missing or does not execute the required command count.

## 4.7 Result template — S2

```json
{
  "spike_id": "S2-CAMERA-BURST",
  "qa": ["QA-03"],
  "dut_id": "DUT-X14U-01",
  "device": {
    "manufacturer": null,
    "model": null,
    "sku": null,
    "android_version": null,
    "api_level": null,
    "build_fingerprint": null,
    "abi": null,
    "memtotal_kib": null
  },
  "app_build_hash": null,
  "workloads": {
    "C-W0": {
      "accepted_capture_count": null,
      "committed_capture_count": null,
      "typed_failure_count": null,
      "duplicate_persistent_page_count": null,
      "persistent_page_before_tx_commit_count": null,
      "committed_capture_missing_page_count": null,
      "capture_waited_for_heavy_permit_count": null,
      "request_to_tx_commit_ms_p50": null,
      "request_to_tx_commit_ms_p95": null,
      "request_to_tx_commit_ms_p99": null,
      "request_to_page_visible_ms_p50": null,
      "request_to_page_visible_ms_p95": null,
      "request_to_page_visible_ms_p99": null,
      "max_state_store_write_queue_depth": null,
      "ANR_count": null,
      "OOM_count": null
    },
    "C-W1": {
      "accepted_capture_count": null,
      "committed_capture_count": null,
      "typed_failure_count": null,
      "duplicate_persistent_page_count": null,
      "persistent_page_before_tx_commit_count": null,
      "committed_capture_missing_page_count": null,
      "capture_waited_for_heavy_permit_count": null,
      "request_to_tx_commit_ms_p50": null,
      "request_to_tx_commit_ms_p95": null,
      "request_to_tx_commit_ms_p99": null,
      "request_to_page_visible_ms_p50": null,
      "request_to_page_visible_ms_p95": null,
      "request_to_page_visible_ms_p99": null,
      "max_state_store_write_queue_depth": null,
      "ANR_count": null,
      "OOM_count": null
    },
    "C-W2": {
      "accepted_capture_count": null,
      "committed_capture_count": null,
      "typed_failure_count": null,
      "duplicate_persistent_page_count": null,
      "persistent_page_before_tx_commit_count": null,
      "committed_capture_missing_page_count": null,
      "capture_waited_for_heavy_permit_count": null,
      "request_to_tx_commit_ms_p50": null,
      "request_to_tx_commit_ms_p95": null,
      "request_to_tx_commit_ms_p99": null,
      "request_to_page_visible_ms_p50": null,
      "request_to_page_visible_ms_p95": null,
      "request_to_page_visible_ms_p99": null,
      "max_state_store_write_queue_depth": null,
      "ANR_count": null,
      "OOM_count": null
    }
  },
  "raw_evidence_location": null,
  "outcome": null
}
```

---

# 5. S3-OCR-MEMORY — ML Kit / OCR resource contract

## 5.1 ID and QA linkage

**Spike ID:** `S3-OCR-MEMORY`  
**QA coverage:** QA-04.  
**FROZEN trace:** F-06; F-08; INV-02; INV-06; INV-07; INV-08; PCON-07; PCON-09; ND-12E; Step 13 one-heavy-worker memory/throughput/UI responsiveness.

## 5.2 Target device

`DUT-X14U-01` only. OCR memory/latency evidence is tuple-scoped; the 96 MiB decoded-image token budget and one-heavy-job limit remain normative architecture limits.

## 5.3 Mandatory input-envelope precondition

Before execution, identify from the actual implementation the **largest image envelope the product accepts for OCR**:

- maximum accepted pixel width;
- maximum accepted pixel height;
- maximum accepted source format(s);
- orientation/rotation policy;
- whether imported images can exceed camera-native dimensions.

If the implementation has no finite accepted input envelope, record `input_envelope_defined = false`; S3 becomes `INCOMPLETE`. The protocol does not invent a product limit.

Construct three deterministic fixtures:

- `O-F1`: maximum accepted camera-produced page image;
- `O-F2`: maximum accepted imported image;
- `O-F3`: maximum accepted post-processing image passed to OCR.

If two fixtures are identical by contract, record that fact and reuse the bytes; do not silently omit the fixture ID.

## 5.4 Required instrumentation

For every heavy-work allocation/release event record:

- active heavy-job count;
- decoded-image token bytes held system-wide;
- owner/job kind;
- operation ID.

Also record process PSS/RSS as diagnostic evidence. PSS/RSS is **not** substituted for the FROZEN 96 MiB decoded-image budget.

The OCR adapter must have no StateStore mutation interface. A test proxy must count any attempted StateStore call originating from OCR adapter code.

## 5.5 Exact procedure

For each fixture O-F1/O-F2/O-F3:

1. Cold-start the app.
2. Verify HeavyWorkScheduler reports no admitted heavy job.
3. Start one OCR request for the fixture.
4. Verify exactly one heavy permit is held while OCR is admitted.
5. Record every decoded-token change until the permit is released.
6. Record OCR start-to-candidate duration.
7. Allow the candidate to proceed through normal conditional commit.
8. Verify the OCR engine itself performed no authoritative write.
9. Repeat **50 times sequentially**.
10. Restart the app and repeat the 50-run batch.
11. Perform **20 batches** total per fixture.

Then execute the contention phase:

1. Queue one OCR request, one Processing request and one Save-render request concurrently.
2. Repeat the contention set **500 times**.
3. Confirm system-wide admitted heavy-job count never exceeds the FROZEN initial limit.
4. Confirm jobs wait for admission rather than executing outside accounting.

## 5.6 Metrics

- `peak_decoded_image_tokens_bytes` — bytes;
- `peak_active_heavy_jobs` — count;
- `heavy_job_unaccounted_execution_count` — count;
- `ocr_direct_authoritative_write_attempt_count` — count;
- `OOM_count` — count;
- `ANR_count` — count;
- `ocr_latency_ms` — p50/p95/p99 per fixture;
- `process_pss_kib_peak` — KiB, diagnostic;
- `process_rss_kib_peak` — KiB, diagnostic;
- `candidate_commit_rejected_stale_count` — count, diagnostic correctness evidence;
- `candidate_commit_success_count` — count.

## 5.7 Kill-condition

S3 = `FAIL` if any of the following occurs:

- `peak_decoded_image_tokens_bytes > 100663296` bytes (**96 MiB**);
- `peak_active_heavy_jobs > 1`;
- `heavy_job_unaccounted_execution_count ≥ 1`;
- `ocr_direct_authoritative_write_attempt_count ≥ 1`;
- `OOM_count ≥ 1`;
- `ANR_count ≥ 1`.

PSS/RSS and OCR latency have no FROZEN numeric threshold in this profile and therefore are recorded but not converted into an invented kill value.

S3 = `INCOMPLETE` if the accepted OCR input envelope is undefined or any required fixture/batch is missing.

## 5.8 Result template — S3

```json
{
  "spike_id": "S3-OCR-MEMORY",
  "qa": ["QA-04"],
  "dut_id": "DUT-X14U-01",
  "device": {
    "manufacturer": null,
    "model": null,
    "sku": null,
    "android_version": null,
    "api_level": null,
    "build_fingerprint": null,
    "abi": null,
    "memtotal_kib": null
  },
  "app_build_hash": null,
  "ocr_adapter_version": null,
  "input_envelope_defined": null,
  "max_input_width_px": null,
  "max_input_height_px": null,
  "accepted_formats": null,
  "peak_decoded_image_tokens_bytes": null,
  "peak_active_heavy_jobs": null,
  "heavy_job_unaccounted_execution_count": null,
  "ocr_direct_authoritative_write_attempt_count": null,
  "OOM_count": null,
  "ANR_count": null,
  "O-F1_latency_ms_p50": null,
  "O-F1_latency_ms_p95": null,
  "O-F1_latency_ms_p99": null,
  "O-F2_latency_ms_p50": null,
  "O-F2_latency_ms_p95": null,
  "O-F2_latency_ms_p99": null,
  "O-F3_latency_ms_p50": null,
  "O-F3_latency_ms_p95": null,
  "O-F3_latency_ms_p99": null,
  "process_pss_kib_peak": null,
  "process_rss_kib_peak": null,
  "candidate_commit_rejected_stale_count": null,
  "candidate_commit_success_count": null,
  "raw_evidence_location": null,
  "outcome": null
}
```

---

# 6. S4-PDF-COMPOSITION — bounded composition and structural validation

## 6.1 ID and QA linkage

**Spike ID:** `S4-PDF-COMPOSITION`  
**QA coverage:** QA-05.  
**FROZEN trace:** F-08; F-11; F-12; INV-08; INV-10; INV-11; PCON-10; ND-12H; Step 13 PDF structural validation and bounded composition memory.

## 6.2 Target device

`DUT-X14U-01` only. PDF composition/memory evidence is tuple-scoped; passing it does not establish behavior on lower-memory hardware.

## 6.3 Mandatory product-envelope precondition

Before execution, extract from the implementation/product configuration:

- `P_MAX`: maximum document page count accepted by Save;
- maximum accepted page raster dimensions/format used by Save rendering;
- maximum accepted per-page render parameter envelope.

If `P_MAX` or the maximum page raster envelope is unbounded/undefined, S4 = `INCOMPLETE`. This protocol does not create a new product limit.

Create one deterministic worst-envelope page fixture `P-FMAX` and a deterministic SaveManifest generator.

Required document page-count points:

- `1`;
- `ceil(P_MAX / 4)`;
- `ceil(P_MAX / 2)`;
- `P_MAX`.

If two computed points are equal, execute the unique point once but preserve both labels in the result mapping.

## 6.4 Exact procedure

For each required page-count point:

1. Create a workspace containing exactly that many Pages, each backed by the `P-FMAX` raster envelope and deterministic content distinct by page index.
2. Execute `TX-SAVE-FREEZE`.
3. Capture the resulting SaveManifest and hash its frozen payload for later equality checks.
4. Run Save composition under normal `HeavyWorkScheduler` admission.
5. Record every decoded-memory token event.
6. Record active heavy-job count.
7. Produce staged PDF.
8. Run the app's explicit read-back structural validator.
9. If the app validator returns success, complete local durable publication and `TX-SAVE-OUTPUT-COMMIT`.
10. Copy the committed PDF to the host **without modifying the app state**.
11. Run an independent host-side PDF structural checker whose tool name/version is recorded in the result.
12. Independently verify:
    - file opens;
    - page count equals the SaveManifest page count;
    - first and last page render successfully;
    - ordered page identity markers match SaveManifest order.
13. Verify the SaveManifest hash did not change.
14. Repeat **50 times** for that page-count point.

Contention phase:

1. Queue OCR and Processing requests while Save composition is running.
2. Execute **200 Save runs at P_MAX**.
3. Verify the system-wide heavy admission limit and decoded-image budget throughout.

## 6.5 Metrics

- `peak_decoded_image_tokens_bytes` — bytes;
- `peak_active_heavy_jobs` — count;
- `app_validator_failure_count` — count;
- `committed_pdf_failed_host_validation_count` — count;
- `committed_pdf_wrong_page_count_count` — count;
- `committed_pdf_wrong_order_count` — count;
- `manifest_mutation_count` — count;
- `OOM_count` — count;
- `ANR_count` — count;
- `composition_ms` — p50/p95/p99 per page-count point;
- `process_pss_kib_peak` — KiB, diagnostic per page-count point;
- `output_bytes` — bytes, diagnostic per page-count point.

## 6.6 Kill-condition

S4 = `FAIL` if any of the following occurs:

- `peak_decoded_image_tokens_bytes > 100663296` bytes;
- `peak_active_heavy_jobs > 1`;
- `committed_pdf_failed_host_validation_count ≥ 1`;
- `committed_pdf_wrong_page_count_count ≥ 1`;
- `committed_pdf_wrong_order_count ≥ 1`;
- `manifest_mutation_count ≥ 1`;
- `OOM_count ≥ 1`;
- `ANR_count ≥ 1`.

An app-validator failure that prevents commit is a typed operation failure and is counted, but it is not itself a correctness breach. A PDF that is nevertheless committed and then fails the independent oracle is a kill-condition.

S4 = `INCOMPLETE` if `P_MAX`/maximum page-raster envelope is undefined, any required page-count point is not executed, or the independent host validation step is absent.

## 6.7 Result template — S4

```json
{
  "spike_id": "S4-PDF-COMPOSITION",
  "qa": ["QA-05"],
  "dut_id": "DUT-X14U-01",
  "device": {
    "manufacturer": null,
    "model": null,
    "sku": null,
    "android_version": null,
    "api_level": null,
    "build_fingerprint": null,
    "abi": null,
    "memtotal_kib": null
  },
  "app_build_hash": null,
  "pdf_composer_version": null,
  "pdf_validator_version": null,
  "host_validator_name_version": null,
  "P_MAX": null,
  "max_page_width_px": null,
  "max_page_height_px": null,
  "page_format": null,
  "peak_decoded_image_tokens_bytes": null,
  "peak_active_heavy_jobs": null,
  "app_validator_failure_count": null,
  "committed_pdf_failed_host_validation_count": null,
  "committed_pdf_wrong_page_count_count": null,
  "committed_pdf_wrong_order_count": null,
  "manifest_mutation_count": null,
  "OOM_count": null,
  "ANR_count": null,
  "per_page_count_metrics": null,
  "raw_evidence_location": null,
  "outcome": null
}
```

---

# 7. S5-SAF-EXPORT — provider qualification and external-export semantics

## 7.1 ID and QA linkage

**Spike ID:** `S5-SAF-EXPORT`  
**QA coverage:** QA-06.  
**FROZEN trace:** F-13; F-14; INV-12; INV-20; INV-21; PCON-05; PCON-12; ND-12G; Step 13 provider qualification/revalidation evidence.

## 7.2 Target device

`DUT-X14U-01` only.

## 7.3 Mandatory qualification-tuple inventory

Before any run, create the complete list of provider tuples that the product intends to support on the exact `DUT-X14U-01` OS build:

`adapter_id + provider package/version + OS build + destination/access class + permission mode + validation-suite version`

Every intended production tuple must be present. An unspecified provider is **not qualified by another provider's result**.

If the production provider list is not explicitly enumerable, S5 = `INCOMPLETE`.

## 7.4 Fixture

For each export operation create a unique local `CommittedExportArtifact` with:

- unique export artifact ID;
- deterministic payload;
- expected byte length;
- expected SHA-256;
- unique requested destination name derived from `run_id`.

The fixture must already be locally committed and structurally valid before S5 begins.

## 7.5 Scenario A — definite successful provider return

For each qualification tuple:

1. Start from a valid `CommittedExportArtifact`.
2. Execute `TX-EXPORT-BEGIN` and verify state=`WritingExternal` is committed.
3. Verify StateStore write-TX depth is zero.
4. Invoke exactly one provider write.
5. If the provider returns definite success, execute `TX-EXPORT-END`.
6. Persist one `ProviderOperationObservation`.
7. Independently evaluate every evidence dimension the qualification tuple claims:
   - identity;
   - existence;
   - integrity;
   - readability;
   - name;
   - access.
8. Verify the persisted observation does not claim a dimension for which the test produced no evidence.
9. Repeat **100 times** per tuple.

## 7.6 Scenario B — definite known failure

For each tuple:

1. Create a destination/access condition that the provider contract reports as a definite failure without performing the requested write.
2. Execute `TX-EXPORT-BEGIN`.
3. Perform exactly one provider call outside the metadata TX.
4. On definite failure, execute `TX-EXPORT-END` to `FailedKnown` and persist the error/evidence.
5. Verify no automatic second write occurs.
6. Repeat **100 times** per tuple.

If the real provider offers no reproducible definite-failure setup, that scenario for that tuple is `INCOMPLETE`; do not substitute a mock provider as evidence for the real tuple.

## 7.7 Scenario C — ambiguous process-death window

For each tuple, execute both hooks:

- `E1_AFTER_TX_EXPORT_BEGIN_BEFORE_PROVIDER_CALL`
- `E2_AFTER_PROVIDER_CALL_RETURN_BEFORE_TX_EXPORT_END`

For each hook:

1. Create a new ExportAttempt.
2. Reach the named hook.
3. Abruptly kill the app process.
4. Restart the app.
5. Before any new provider write is allowed, verify startup barrier changes persisted pre-startup `WritingExternal` to `UnknownOutcome`.
6. Verify provider-write count for that original attempt has not increased after restart.
7. If the tuple claims stable external identity + revalidation capability, perform **read-only** revalidation and record the evidence.
8. If the evidence proves success, allow `TX-EXPORT-REVALIDATE → Succeeded`.
9. If the adapter can prove no side effect, allow `→ FailedKnown`.
10. Otherwise verify state remains `UnknownOutcome`.
11. Repeat **100 times per hook per tuple**.

## 7.8 Provider-I/O / local-TX separation check

For **every** Scenario A/B/C provider invocation, record StateStore authoritative write-TX depth at provider-call entry and exit.

## 7.9 Metrics

Per qualification tuple:

- `definite_success_runs` — count;
- `definite_known_failure_runs` — count;
- `ambiguous_E1_runs` — count;
- `ambiguous_E2_runs` — count;
- `provider_success_but_claimed_evidence_false_count` — count;
- `provider_io_while_authoritative_tx_open_count` — count;
- `blind_retry_count` — count;
- `writingexternal_not_converted_to_unknown_on_startup_count` — count;
- `operation_observation_missing_count` — count;
- `qualification_dimension_overclaim_count` — count;
- `revalidation_write_call_count` — count;
- per-dimension evidence availability — Boolean measured result for that tuple;
- provider operation latency — ms p50/p95/p99, diagnostic only.

## 7.10 Kill-condition

A qualification tuple = `FAIL` if any is `≥ 1`:

- `provider_success_but_claimed_evidence_false_count`;
- `provider_io_while_authoritative_tx_open_count`;
- `blind_retry_count`;
- `writingexternal_not_converted_to_unknown_on_startup_count`;
- `operation_observation_missing_count`;
- `qualification_dimension_overclaim_count`;
- `revalidation_write_call_count`.

S5 overall = `FAIL` if any intended production tuple fails.

S5 = `INCOMPLETE` if the intended production tuple inventory is incomplete or any tuple lacks a mandatory scenario.

A failed tuple invalidates that tuple only; it does not empirically establish anything about a different provider/version/destination tuple.

## 7.11 Result template — S5

```json
{
  "spike_id": "S5-SAF-EXPORT",
  "qa": ["QA-06"],
  "dut_id": "DUT-X14U-01",
  "device": {
    "manufacturer": null,
    "model": null,
    "sku": null,
    "android_version": null,
    "api_level": null,
    "build_fingerprint": null,
    "abi": null,
    "memtotal_kib": null
  },
  "app_build_hash": null,
  "qualification_tuple_inventory_complete": null,
  "tuples": [
    {
      "adapter_id": null,
      "provider_package": null,
      "provider_version": null,
      "os_build": null,
      "destination_access_class": null,
      "permission_mode": null,
      "validation_suite_version": null,
      "definite_success_runs": null,
      "definite_known_failure_runs": null,
      "ambiguous_E1_runs": null,
      "ambiguous_E2_runs": null,
      "identity_evidence_available": null,
      "existence_evidence_available": null,
      "integrity_evidence_available": null,
      "readability_evidence_available": null,
      "name_evidence_available": null,
      "access_evidence_available": null,
      "provider_success_but_claimed_evidence_false_count": null,
      "provider_io_while_authoritative_tx_open_count": null,
      "blind_retry_count": null,
      "writingexternal_not_converted_to_unknown_on_startup_count": null,
      "operation_observation_missing_count": null,
      "qualification_dimension_overclaim_count": null,
      "revalidation_write_call_count": null,
      "provider_latency_ms_p50": null,
      "provider_latency_ms_p95": null,
      "provider_latency_ms_p99": null,
      "tuple_outcome": null
    }
  ],
  "raw_evidence_location": null,
  "outcome": null
}
```

---

# 8. S6-PROCESS-DEATH — recovery across critical commit windows

## 8.1 ID and QA linkage

**Spike ID:** `S6-PROCESS-DEATH`  
**QA coverage:** QA-01, QA-07.  
**FROZEN trace:** F-01; F-02; F-06; F-09; F-11; F-13; F-15; F-17; INV-01/02/03/06/07/09/10/12/13/15/18/20/24/25; PCON-01; PCON-06; Step 13 crash injection in all critical commit windows.

## 8.2 Target device

`DUT-X14U-01` only.

## 8.3 Recovery invariant oracle

After every injected process death, the first diagnostic snapshot after restart must satisfy all applicable rules:

1. no authoritative row references missing/non-durable bytes;
2. no half-committed SQLite transaction is visible;
3. no stale OCR/Processing candidate is current;
4. no duplicate `(ImportSessionId, sourcePageIndex)` Page exists;
5. SaveManifest, once committed, is immutable;
6. Save-scoped dependency results do not update live Page-current result pointers;
7. no external write is blind-retried;
8. pre-startup `WritingExternal` is converted to `UnknownOutcome` before new provider writes;
9. `GC_CLAIMED` recovery uses the persisted claim token;
10. startup does not require full-universe reconciliation for correctness.

## 8.4 Required crash-hook matrix

Every hook below is mandatory. Each hook is executed **100 times** with a fresh `run_id`.

### Capture / artifact

- `R01_CAPTURE_STAGED_BEFORE_TX`
- `R02_CAPTURE_TX_AFTER_MUTATIONS_BEFORE_COMMIT`
- `R03_CAPTURE_AFTER_TX_COMMIT_BEFORE_UI_ACK`

Expected:

- R01: no persistent Page required; no authoritative reference; staged orphan may remain.
- R02: either complete rollback or complete commit; never partial Page/ImageVersion/reference set.
- R03: exactly one committed Page/ImageVersion/reference set survives.

### Retake / Edit

- `R04_RETAKE_TX_AFTER_MUTATIONS_BEFORE_COMMIT`
- `R05_RETAKE_AFTER_TX_COMMIT_BEFORE_ACK`
- `R06_EDIT_TX_AFTER_MUTATIONS_BEFORE_COMMIT`
- `R07_EDIT_AFTER_TX_COMMIT_BEFORE_ACK`

Expected:

- before-commit death: old current ImageVersion/contentRevision remains authoritative;
- after-commit death: new ImageVersion/current pointer/contentRevision/Undo relationship survives atomically.

### OCR / Processing

- `R08_OCR_CANDIDATE_READY_BEFORE_TX`
- `R09_OCR_TX_AFTER_INSERT_BEFORE_COMMIT`
- `R10_OCR_AFTER_TX_COMMIT_BEFORE_ACK`
- `R11_PROCESSING_CANDIDATE_READY_BEFORE_TX`
- `R12_PROCESSING_TX_AFTER_INSERT_BEFORE_COMMIT`
- `R13_PROCESSING_AFTER_TX_COMMIT_BEFORE_ACK`

Expected:

- runtime attempt may disappear;
- pre-commit state remains unsatisfied/recomputable;
- post-commit result survives exactly once;
- no durable execution-attempt row is required for correctness.

### Import

- `R14_IMPORT_BEGIN_TX_BEFORE_COMMIT`
- `R15_IMPORT_BEGIN_AFTER_COMMIT`
- `R16_IMPORT_PAGE_TX_BEFORE_COMMIT`
- `R17_IMPORT_PAGE_AFTER_COMMIT_BEFORE_ACK`
- `R18_IMPORT_END_TX_BEFORE_COMMIT`
- `R19_IMPORT_END_AFTER_COMMIT`

Expected:

- begin pre-commit: no durable Open session;
- begin post-commit: one Open session with secured source evidence;
- materialize pre-commit: no partial Page/reference tuple;
- materialize post-commit: exactly one Page for the tuple and recovery cannot create a second;
- end transition is atomic.

### Save

- `R20_SAVE_FREEZE_TX_BEFORE_COMMIT`
- `R21_SAVE_FREEZE_AFTER_COMMIT`
- `R22_SAVE_DEPENDENCY_TX_BEFORE_COMMIT`
- `R23_SAVE_DEPENDENCY_AFTER_COMMIT`
- `R24_SAVE_OUTPUT_TX_BEFORE_COMMIT`
- `R25_SAVE_OUTPUT_AFTER_COMMIT`

Expected:

- pre-freeze commit: no SaveManifest;
- post-freeze commit: immutable exact snapshot survives;
- dependency pre-commit: no partial result/reference;
- dependency post-commit: exact SaveDependencyResult survives without changing live-current Page pointers;
- output pre-commit: no partial ExportArtifact/reference;
- output post-commit: exactly one committed local output and protection transition survives.

### Export

- `R26_EXPORT_BEGIN_TX_BEFORE_COMMIT`
- `R27_EXPORT_AFTER_BEGIN_COMMIT_BEFORE_PROVIDER`
- `R28_EXPORT_AFTER_PROVIDER_SIDE_EFFECT_BEFORE_END_TX`
- `R29_EXPORT_END_TX_BEFORE_COMMIT`
- `R30_EXPORT_AFTER_END_COMMIT_BEFORE_ACK`

Expected:

- R26: no non-terminal ExportAttempt from the uncommitted TX;
- R27: persisted pre-startup `WritingExternal` becomes `UnknownOutcome`; no provider write is auto-issued after restart;
- R28/R29: startup conservatively yields `UnknownOutcome` unless definite committed end state exists; no blind retry;
- R30: committed terminal state survives exactly once.

### Cleanup

- `R31_GC_CLAIM_TX_BEFORE_COMMIT`
- `R32_GC_AFTER_CLAIM_COMMIT_BEFORE_DELETE`
- `R33_GC_AFTER_PHYSICAL_DELETE_BEFORE_FINALIZE`
- `R34_GC_FINALIZE_TX_BEFORE_COMMIT`
- `R35_GC_AFTER_FINALIZE_COMMIT`

Expected:

- pre-claim commit: artifact remains COMMITTED and must not be physically deleted by recovery as claimed;
- post-claim: persisted token authorizes resumed physical delete;
- post-delete/pre-finalize: matching token permits idempotent finalize for absent file;
- no mismatched token may finalize another claim.

### Metadata purge / maintenance

- `R36_METADATA_PURGE_TX_BEFORE_COMMIT`
- `R37_METADATA_PURGE_AFTER_COMMIT_BEFORE_CURSOR_ACK`
- `R38_MAINTENANCE_CURSOR_TX_BEFORE_COMMIT`
- `R39_MAINTENANCE_CURSOR_AFTER_COMMIT`

Expected:

- every batch is all-or-nothing;
- cursor may lag a completed batch only where replay is idempotent; it may never skip unprocessed objects;
- startup correctness does not require a full persistent-universe scan.

### Schema migration

- `R40_SCHEMA_MIGRATE_TX_BEFORE_COMMIT`
- `R41_SCHEMA_MIGRATE_AFTER_COMMIT_BEFORE_NEXT_STEP`

Expected:

- pre-commit: prior schema version remains authoritative;
- post-commit: advanced version and its complete migration effects survive together;
- no destructive fallback.

## 8.5 Procedure for every R-hook

1. Reset to the deterministic seed required for the hook.
2. Start the operation with a unique `run_id`.
3. Reach the exact named hook.
4. Abruptly kill the app process.
5. Restart the app.
6. Prevent user actions until the startup barrier completes.
7. Export the first post-startup diagnostic state.
8. Apply the hook-specific oracle and the global invariant oracle.
9. If the object remains recoverable, allow exactly one recovery pass.
10. Export the second diagnostic state.
11. Run the same recovery entry once more to test idempotency.
12. Export the third diagnostic state and verify no duplicate side effect/row/reference was created.
13. Preserve host logs and all three diagnostic dumps.
14. Repeat 100 times for the hook.

## 8.6 Metrics

- `hooks_required` — count;
- `hooks_completed` — count;
- `runs_completed` — count;
- `partial_tx_visible_count` — count;
- `authoritative_ref_to_missing_bytes_count` — count;
- `stale_current_result_commit_count` — count;
- `duplicate_import_page_tuple_count` — count;
- `save_manifest_mutation_count` — count;
- `save_dependency_updated_live_pointer_count` — count;
- `blind_export_retry_count` — count;
- `writingexternal_not_unknown_after_restart_count` — count;
- `gc_wrong_claim_token_finalize_count` — count;
- `recovery_duplicate_side_effect_count` — count;
- `recovery_requires_runtime_attempt_state_count` — count;
- `startup_requires_full_universe_scan_count` — count;
- `committed_state_lost_after_process_death_count` — count;
- startup barrier duration — ms p50/p95/p99, diagnostic only.

## 8.7 Kill-condition

S6 = `FAIL` if **any** of the following is `≥ 1`:

- `partial_tx_visible_count`;
- `authoritative_ref_to_missing_bytes_count`;
- `stale_current_result_commit_count`;
- `duplicate_import_page_tuple_count`;
- `save_manifest_mutation_count`;
- `save_dependency_updated_live_pointer_count`;
- `blind_export_retry_count`;
- `writingexternal_not_unknown_after_restart_count`;
- `gc_wrong_claim_token_finalize_count`;
- `recovery_duplicate_side_effect_count`;
- `recovery_requires_runtime_attempt_state_count`;
- `startup_requires_full_universe_scan_count`;
- `committed_state_lost_after_process_death_count`.

S6 = `INCOMPLETE` unless **all R01…R41 hooks** complete their required run count with raw evidence retained.

## 8.8 Result template — S6

```json
{
  "spike_id": "S6-PROCESS-DEATH",
  "qa": ["QA-01", "QA-07"],
  "dut_id": "DUT-X14U-01",
  "device": {
    "manufacturer": null,
    "model": null,
    "sku": null,
    "android_version": null,
    "api_level": null,
    "build_fingerprint": null,
    "abi": null,
    "memtotal_kib": null
  },
  "app_build_hash": null,
  "hooks_required": null,
  "hooks_completed": null,
  "runs_completed": null,
  "partial_tx_visible_count": null,
  "authoritative_ref_to_missing_bytes_count": null,
  "stale_current_result_commit_count": null,
  "duplicate_import_page_tuple_count": null,
  "save_manifest_mutation_count": null,
  "save_dependency_updated_live_pointer_count": null,
  "blind_export_retry_count": null,
  "writingexternal_not_unknown_after_restart_count": null,
  "gc_wrong_claim_token_finalize_count": null,
  "recovery_duplicate_side_effect_count": null,
  "recovery_requires_runtime_attempt_state_count": null,
  "startup_requires_full_universe_scan_count": null,
  "committed_state_lost_after_process_death_count": null,
  "startup_barrier_ms_p50": null,
  "startup_barrier_ms_p95": null,
  "startup_barrier_ms_p99": null,
  "per_hook_result_table_location": null,
  "raw_evidence_location": null,
  "outcome": null
}
```

---

# 9. Mandatory per-run evidence schema

Every individual run in S1–S6 must be represented by one machine-readable record. No aggregate-only result is sufficient.

```json
{
  "spike_id": null,
  "dut_id": "DUT-X14U-01",
  "run_id": null,
  "case_id": null,
  "operation_id": null,
  "device_build_fingerprint": null,
  "app_build_hash": null,
  "start_monotonic_ns": null,
  "end_monotonic_ns": null,
  "injection_hook": null,
  "measured_metrics": {},
  "pre_state_dump": null,
  "post_state_dump": null,
  "artifact_checks": [],
  "raw_log_location": null,
  "kill_condition_triggered": null,
  "notes": null
}
```

All measured fields remain empty until a human/physical-run harness supplies them.

---

# 10. Completion gate for Post-Spikes

The Post-Spikes step is blocked unless all of the following are true:

1. S1 outcome is `PASS` or `FAIL`, not `INCOMPLETE`; optional hard-power execution is not required, but its execution status and scope field must be recorded.
2. S2 outcome is `PASS` or `FAIL`, not `INCOMPLETE`.
3. S3 outcome is `PASS` or `FAIL`, not `INCOMPLETE`.
4. S4 outcome is `PASS` or `FAIL`, not `INCOMPLETE`.
5. S5 outcome is `PASS` or `FAIL`, not `INCOMPLETE`, and every intended production provider tuple for the tested OS build has its own result.
6. S6 outcome is `PASS` or `FAIL`, not `INCOMPLETE`.
7. Every result identifies `DUT-X14U-01`, the exact physical-device build fingerprint and app build hash.
8. All six spikes in the cycle use the same physical unit, OS build fingerprint and app build hash.
9. Every mandatory measured field is filled.
10. Raw per-run evidence is retained and addressable.
11. No aggregate is substituted for a missing mandatory scenario.
12. No result is interpreted as evidence for another device, RAM tier, Android version, SKU or OS build.

Until then, the only valid chain state is:

> **`DocScan v1.2 FROZEN → Spike Protocol v1.1 FROZEN → PHYSICAL EXECUTION → STOP`**

The following are prohibited before the completion gate passes:

- Post-Spikes architecture correction;
- a new FROZEN version based on spike evidence;
- Implementation Architecture v1.0;
- vertical implementation slices;
- generated or estimated replacement values;
- `UNPROVEN`, `PENDING`, `ASSUMED` or similar categories used as a way to bypass missing empirical evidence.

The transition is unlocked only when **all six spike result templates are fully populated from physical execution on the exact `DUT-X14U-01` tuple, all mandatory provider tuples and crash hooks are complete, and every spike has a final `PASS` or `FAIL` outcome with raw evidence.**

If the optional hard-power extension is not executed, Post-Spikes may proceed, but every resulting architecture/evidence artifact must preserve the explicit limitation: **sudden hardware power-loss durability is not qualified**.

---

# Freeze Seal

**SPIKE PROTOCOL v1.1 FROZEN — Xiaomi 14 Ultra physical qualification specification, 19.09.2026**

- Normative baseline: **DocScan v1.2 FROZEN**
- Normative DUT: **DUT-X14U-01 / QP-X14U-01**
- Mandatory spikes: **S1–S6**
- Mandatory S6 crash hooks: **R01–R41**
- Hard-power extension: **optional / separately scoped**
- Empirical results included at freeze: **none**

**Release rule:** semantic modification invalidates this seal and requires `v1.2+`; Post-Spikes / Implementation Architecture remain blocked until the v1.1 completion gate is satisfied with real device evidence.
