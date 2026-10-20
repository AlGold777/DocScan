## Active package continuation — plan revision 5.454 (2026-09-29)

- Attempt 68 S6 startup-barrier availability consistency is committed in `4d6db9e9` (runner 1.22); the S1 schema test fixture correction is separately committed in `c0dc8fac`. Configured host pytest subset passes 309/309. Static checks pass. Evidence/logs/checksums: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-68-s6-startup-measurement-consistency/`.
- Existing S1 frozen-schema test fixture was corrected after its initial 308/1 run failed early on omitted expected/observed SHA-256 values; the focused target and full subset now pass. History is in attempt `ATTEMPT_HISTORY.md`.
- Current working tree includes attempt 68 source, tests, configured test-list update, evidence and plan/handoff records. No APK, Android runtime, full configured check, or physical-device qualification was run.
- Attempt 69 runner 1.23 implementation is committed as `45b91b96`; three focused unittest methods pass across all five targets and both call routes. Evidence is at `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-69-s2-s6-instrumentation-result/`. No Android/APK/full configured run.
- The full orchestrator module remains blocked from acceptance by pre-existing I23 `test_resume_end_to_end_flow`: resume records SUCCESS but the rebuilt S1 aggregate is INCOMPLETE. The fixture/source-log mismatch is retained and mapped to the existing I23/I12 residual.
- Attempt 70 is complete: implementation `d69e8d10`, tests `155887e3`; runner 1.24, app harness `.1.22`/23, product `.219`/338 unchanged. Host orchestrator/validator/state-oracle tests pass 152 plus 80 subtests; Android debug main/androidTest compile and assemble pass; API36.1 emulator D8 rep3 passed process death, snapshot, mandatory log pull and per-run validation. Synthetic 23+1 resume reaches source-valid PASS and package round-trip; resumed device-log pull failure records terminal FAILED while preserving prior evidence/history.
- Attempt history, raw outputs and checksums: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-70-s1-resume-timing-source/`. Initial cold-emulator missing-DB setup failure is retained separately from the successful D8 rerun. No full check/release APK or physical Xiaomi qualification was run.
- Next: continue mapped I14–I20 per-run evidence, starting with S2 C1–C7 capture/workload runtime residual from §11.9.2 and plan 5.437; keep the existing matrix and thresholds. Current emulator is DEV evidence. Reserve one full integrated `tools/check_all.sh --rerun` and release APK for the consolidated parent candidate.
- Attempt 71 is preregistered in plan 5.455 / audit §11.19: one complete S2 app-produced 30-command DEV suite and host validation on API36.1, with unchanged C1–C7/W0–W2 contract. No source edits; no retries absent diagnosed cause.
- Attempt71 failed its aggregate PASS assertion after all 30 C7 presentations were typed failures. Retained evidence identifies hidden outer display host (GONE, nested view 0×0) in the instrumented setup. Plan amendment 5.456 / audit §11.19 now authorizes explicit temporary visibility, measured-layout wait, and restoration, plus consistent C7 positive-test setup. The pre-fix evidence is preserved; retry only after this correction.
- After that visibility correction, all 30 captures committed with valid C7 and zero typed failures, but attempt71 still failed aggregate PASS: C-W1 permit interval ended ~8s before its capture burst, so W1 correctly stayed incomplete. Plan amendment 5.457 traces this to asynchronous nested OCR release being treated as top-level. Preserve both device cycles; next fix/test nested permit ownership across all relevant scheduler paths before one final S2 runtime attempt.
- Physical Xiaomi 14 Ultra qualification, independent review and owner acceptance remain open; MVP `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Latest continuation snapshot — plan revision 5.446

- Attempt 67 runner source committed `def60de9` (runner 1.21; app harness `.1.21`/22, product `.219`/338 unchanged). S6 derives pass-1 recovery count from Dump 1 and binds process-death duration to run-specific monotonic transcript markers; transcript is validated in exact ordered form.
- Committed-source S6 validator + all-hook reproduction tests: **157/157 passed**, including 41 hook mutations and valid 41-hook DEV positive; py_compile/diff check passed. Initial path-resolution failures and correction retained. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-67-s6-recovery-metrics-transcript/RESULT.md`.
- No APK or Android/device run; synthetic host evidence only. Continue finite I14–I20/I12/I21–I25 inventory. Physical Xiaomi qualification, independent review and owner acceptance remain open; MVP `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Latest continuation snapshot — plan revision 5.444

- Attempt 66 committed: source `d2f11170`, S5 mutation-matrix correction `6ce35558`; runner 1.20, harness `.1.21`/code22, product `.219`/338.
- S5 per-run provider read-back source reconciliation: focused pytest 20/20 plus 18 subtests; py_compile/diff check passed; Android main/androidTest compilation rerun passed (29 tasks). Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-66-s5-provider-readback-source/RESULT.md` with raw logs and checksums.
- No APK or Android runtime/instrumentation was run; the slice is synthetic host + compile only and does not qualify production SAF. Continue the finite parent I14–I20/I12/I21–I25 batch, reserving the single full check/APK for the integrated candidate. Physical Xiaomi qualification, independent review and owner acceptance remain outstanding. MVP `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

# DocScan Harness Execution Handoff & Ledger

## Active child slice — plan revision 5.443

- Attempt 65 source/evidence is committed through `654ed219`; runner 1.19, harness `.1.20`/21, product `.219`/338. Worktree was clean at continuation start.
- Attempt 66 preregisters S5 per-run source reconciliation in plan revision 5.443 and `AUDIT_INVARIANTS.md` §11.20, within I14–I20 of `ENG-B1B2-INVENTORY-01`. Finite matrix covers SUCCESS/KNOWN_FAILURE/E1/E2, fixture-to-read-back evidence, measurement/PID, structured log, and recovery semantics.
- No code changes or attempt checks have run yet. Next: commit this preregistration, implement the S5 producer/consumer changes with collected negative controls, compile Android sources, then preserve host evidence. Do not build APK or run full configured/device cycle until parent inventory is integrated.
- Physical Xiaomi qualification, independent review and owner acceptance remain open. MVP stays `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Active package continuation — plan revision 5.437 (2026-09-29)

- Active package remains `ENG-B1B2-INVENTORY-01`; attempt 60 source/contract result is recorded in plan revision 5.431. S1 complete FAIL semantics and source-derived eight-counter validation are implemented in runner 1.13, commit `22d59879`; docs/prereg commit `c0f38468`.
- Attempt 60 host pytest **163/163**; all eight source-backed kill classes, overlaps, aggregate drift, typed-only PASS and incomplete-source INCOMPLETE controls passed. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-60-s1-kill-source-reconciliation/RESULT.md`; raw logs and SHA-256 list alongside it. Product `.219`/code338 and app harness `.1.18`/code19 unchanged.
- Exact remaining gates: integrated configured check/APK, current fresh Android DEV/runtime evidence where available, physical Xiaomi 14 Ultra qualification, independent review, owner acceptance and unresolved required product inputs. No APK/full check was run in attempt 60; no gate or MVP status changed. Continue remaining I12/I14–I25 finite inventory and reserve one full check/APK for the integrated candidate.
- Attempt 61 completed on runner 1.15: focused host pytest 163/163; run counts, six nearest-rank percentiles, per-run/raw-log durations, payload/hash evidence, diagnostic arrays and no-hard-power tuple reconcile. Synthetic host only; no APK, Android runtime, physical qualification, independent review or owner acceptance. Full details and failed/superseded attempts: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-61-s1-completion-metric-reconciliation/RESULT.md` and `ATTEMPT_HISTORY.md`.

## Latest continuation snapshot — plan revision 5.429 (2026-09-28)

- Active package: `ENG-B1B2-INVENTORY-01`, S6 source-derived totals are committed under the consolidated I12/I14–I25 inventory. S1 frozen kill-counter coverage is committed (contract/result 5.428/5.429); continue remaining source-bound aggregate and per-run inventory within the same ENG-B1B2 package. S4 typed-failure semantics are implemented in commit `f76abd62` on `experiment/pdf-scratch-output-close`; plan S1 contract 5.428; S6 contract/result 5.426/5.427 (S4 contract/result 5.424/5.425). Harness `.1.18`/code 19, host runner 1.11, product `.219`/code 338 unchanged.
- Attempt 57 evidence: aggregate/run-schema pytest 87/87; integrated S4 12-run host PDF control 1/1; app main/androidTest compile successful; Python compile, shell syntax and diff check pass. See `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-57-s4-typed-failure-semantics/RESULT.md`. One typed app-validation run is retained as FAIL while complete aggregate remains PASS; only committed run IDs enter PDF validation.
- S6 attempt 58: aggregate/S6 pytest 56/56 in 2.93 s; details at `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-58-s6-source-derived-totals/RESULT.md`. Scope boundary: synthetic host controls and Java compilation only. No Android runtime, APK, full `check_all.sh`, fresh DEV, physical Xiaomi Q, independent review, owner acceptance or MVP acceptance occurred. Overall remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`. Attempt 59 evidence: aggregate + S1 state pytest 88/88; integrated 24-run counter test 1/1. See `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-59-s1-kill-counter-coverage/RESULT.md`. All evidence remains synthetic host-only; reserve one final full configured check and APK for the integrated candidate.

## Current Product MVP continuation — 2026-09-28

- **Current workspace:** isolated branch `experiment/pdf-scratch-output-close`; latest source under test is harness commit `fa4d7ad2` (`host/runner.py` v1.7), with its documentation/evidence recorded in plan revision 5.361. Product baseline remains `.216` / code 335, source `b43bfb64`, delivered APK SHA-256 `4b187e4254a91edddd29efc155f3d1d79c9548bc8a809eb71c7c0ea263aa030c`. MVP stays `IN_PROGRESS_NOT_ACCEPTED`.
- **Map:** see `docs/09_MVP_EXECUTION_PLAN_FOR_AGENT.md`, revisions 5.352–5.353. The finite map was requested for owner review. Formal approval of the map does not block already-authorized independent evidence inventory and source audit; product decisions, acceptance, Gate A and physical/reviewer gates remain required where applicable.
- **Closed/reused boundaries:** `.191` renderer lifetime and `.196` malformed/encrypted PDF corpus evidence remain applicable; `.212`–`.216` injected private-PDF publication/staging ENOSPC evidence remains valid only at its recorded injection points. The `.216` full API 36.1 suite is 139/139. Do not rerun these slices absent a changed source, criterion, dependency or contrary evidence. They do not prove physical low storage or preflight.
- **DR-03 disposition:** resolved for implementation by direct user delegation on 2026-09-28; see `ENG-DR-03-01` in `execution/decisions.md` and plan revision 5.355. Adopt known-size estimate + decimal 100 MB reserve on app-managed storage; unknown SAF size/quota is reported as unknown, not sufficient, and may proceed after private-volume reserve check. Actual late ENOSPC remains possible; fail safely and preserve workspace/source. The owner-sheet DR-03 checkbox remains unchecked, so this is delegated engineering authority, not an owner-sheet sign-off.
- **Completed package:** `ENG-RUNNER-INSTRUMENTATION-EXIT-01`, plan revisions 5.359–5.361. Android `INSTRUMENTATION_CODE: -1` is `RESULT_OK`; runner v1.7 requires adb exit 0 and exactly one `OK (1 test)` for S1 snapshot/aggregate, permits the `-1` marker alongside that summary, rejects missing/wrong result codes, failure/abort markers and nonzero adb status, and preserves the intentional `start_case` kill evidence. Test commit `9d0864a6`, source fix `fa4d7ad2`, runner SHA-256 `95b75edd16df18fc7ef939a79dc8202c319ae70d84d7fbd44ccc5162f97dcbab`. Focused controls passed 3/3, orchestrator 22/22, full check_all passed (Gradle 123 tasks, host 107/107, traceability, task ledger, B0 evidence 272 JSON/575 hashes). Raw logs and checksums: `execution/device_tests/ENG-RUNNER-INSTRUMENTATION-EXIT-01/`. Local release APK `.216`/335 SHA `b934c9c43a59b652075f6c9062fa508430b285720a3e897f97b5e2d45f863512`; build-only, not installed. Package is `REVIEW_REQUIRED`; no instrumentation/DEV run or independent review has been done.
- **Authority:** user instruction on 2026-09-28 delegates engineering implementation, reversible defaults, test strategy and sequencing within the already authorized MVP; no routine engineering-choice question is pending. See `ENG-SYSTEM-DELEGATION-01` and plan revision 5.363. Scope, frozen requirements, thresholds, physical qualification, independent review and owner acceptance remain unchanged.
- **Active package:** `ENG-B1B2-INVENTORY-01`, plan revisions 5.362–5.388. Exact frozen aggregate paths, nested per-run/dump shapes and command/package/gate maps are in `AUDIT_INVARIANTS.md` §§11.4–11.16; I12 outcome/QA gaps and the S1-only oracle extension classification are in §11.11; I12 exact aggregate counter type and reconciliation mapping is in §11.15, I14–I20 S1–S4 per-run metrics/artifacts are mapped in §11.9.2, state-dump paths in §11.9.3 and S1's barrier oracle in §11.5.2, S5/S6 in §11.9.1, I23 resume, I24 package and I25 gate-ledger details in §§8–11. Continue property-level P/F/C/E, regression-ID, executed-evidence and mode-condition mapping across I01–I25. Aggregate schema/I13/I12, common per-run, I14–I20, S5/S6, I21/I22 command and file mutations, I23, I24 and I25 slices remain one consolidated candidate batch; no runner code changes until the inventory and batch contract are complete. AVDs currently listed include `DocScanMvpReleaseSmoke_API_36.1` and `DocScanMvpTest_API_36.1`; select a DEV-only profile after the inventory. `adb devices -l` was recorded empty at last check; no device execution is claimed.
- **User-requested OCR check:** completed against product `.216`/code 335 at source `6c2df181` on Android 16/API 36 AVD `emulator-5560`; the existing Cyrillic/Latin and Spanish synthetic PNG/JPEG assertions passed 2/2. Raw Spanish output loses `ñ`, `ó`, `ü`, `ú` and retains `é`; runtime loaded only `rus+eng`. This is not general Spanish support or language-detection evidence. Exact report `execution/evidence/ENG-OCR-02-current-cyrillic-spanish-0.4.216.md`; raw/checksummed artifacts `results/PRODUCT/ENG-OCR-02-CURRENT-0.4.216/focused/attempt-1/`. Do not repeat absent source change or contradictory evidence.
- **Latest inventory pass:** plan revisions 5.375–5.388 define Contract 05, task-ID reconciliation, per-run metrics/artifacts, state-dump claims, executed-target corrections, I21/I22 command/file boundaries, the I12 frozen-vs-extension correction, the exact S1–S6 aggregate-counter type/reconciliation map, and the aggregate measurement/mode matrix in `AUDIT_INVARIANTS.md` §§11.4–11.16. I14–I20 records executed v1.7 evidence for S2/S3/S4 accepting incomplete or empty frozen state/metric fixtures, plus current S1 byte/hash and PDF controls. S1 protocol mapping separates D1–D8 required semantic states from non-normative DB columns; external dumps are only partially bound to run records. I23 resume APK-hash/process-death tests, two S1 identity-tuple tests and one S1 dump-mode test exit early on missing identity/timestamp; these are false test coverage, not product failures, and need corrected fixtures. I21/I22 mapping reuses exact v1.7 evidence for timeout, required JSON pulls, prior-run preservation, final-pull layout, snapshot/aggregate parser and checksums; S2–S6 summary shape, ignored cleanup/grant/logcat outcomes, cycle-path collision and interrupted journal writes remain untested. `failed_runs_count` and `oracle_violations*` are S1-only runner extensions, so their absence from the S3 fixture is not a finding; the named S1 test still exits before its oracle branch. Frozen common `outcome=null` and non-array QA remain source-level schema gaps. I12 maps S1–S6 counter checks and typed-mode conditions in §11.15, including S4 app-validator failure semantics and S6 total reconciliation gaps. Qualification manifest/report paths have fixed names; package identity and transaction controls remain source candidates. I25 repair map is exact: add BLOCKED Q/A task records and a direct P1 trace row, with 13 reciprocal task IDs expected; preserve existing statuses and treat P1 DONE evidence as unvalidated until its chain resolves. This pass was read-only; no test/build/package/ADB/device command ran, source and product version remain unchanged, and no independent review occurred. Continue remaining I01–I25 exact path/property/mode mapping; do not start code changes until the unified regression contract is complete. MVP and all gate/task statuses remain unchanged.
- **Counter-test evidence correction:** the retained host log shows `test_s1_fails_closed_on_missing_or_nonzero_kill_counter` stops on malformed dump keys (line 247), and `test_s4_fails_closed_on_missing_or_nonzero_failure_counter` stops on zero per-run files (line 289), before either reaches its counter assertions. Both are test-target gaps, not validator behavior evidence; corrected complete fixtures are in the single I12 batch (§11.15). No new run or status change.
- **Outcome interpretation:** `ENG-AGGREGATE-OUTCOME-01` in `execution/decisions.md` applies frozen Protocol §2.5: exact per-spike kills yield FAIL; missing mandatory work/evidence yields INCOMPLETE; typed operation failure alone remains counted and does not force FAIL. Current S2/S4 producer and common validator deviations are included in the single I12/I13 batch. No tests or source changes ran for this interpretation; gates and statuses are unchanged.
- **Held product gap:** `ENG-IMPORT-SPACE-PREFLIGHT-01` is confirmed in the picker→import flow but remains held on Gate A, blocked by physical Gate Q. DR-03 implementation policy is already resolved in revision 5.355 under delegated system-engineering authority; it does not require a new user answer. Gate B1–B9 and MVP are not accepted; Q/A remain blocked and independent review is still required.

## Current audit policy override — 2026-09-20

User requires invariant-based review before every new runner review. See root
`AUDIT_INVARIANTS.md` for P/F/C/E inventory, class-wide search and adversarial test
plan. B1 and B2 are IN_PROGRESS until full inventory/test coverage, independent
review and a new DEV run. Historical local acceptance and packages remain valid
evidence; older DONE wording below is historical and does not override this policy.

- **Active Phase:** Phase 1 (B1/B2 Anchored & Orchestrator Hardened) / Moving to Phase 2 (Cross-Spike Infra) & Phase 3 (B3 / S2 CameraX)
- **Active Gates:**
  - Gate B0: **DONE**
  - Gate B1: **IN_PROGRESS** (Cross-spike general infrastructure undergoing extension; S1-specific runner/orchestrator verified)
  - Gate B2: **IN_PROGRESS** (new invariant-based acceptance required; historical DEV evidence: `edf6306`, `cycle-20260920_102945`)
  - Gates B3–B7: **NEEDS_REWORK**
  - Gates B8–B9: **IN_PROGRESS**
  - Gate Q: **BLOCKED** on INPUT-07 (physical Xiaomi 14 Ultra offline on USB)
- **Baseline Verified Commit:** `edf6306`
- **Baseline Verified Package:** `results/DEV/cycle-20260920_102945` (73/73 JSON files validated, 15 .bin files, 24 KILL_INJECT lines)
- **Target Serial:** `emulator-5554` (DEV mode validated for S1)

## Stage 1 — B1/B2 Results Anchored & Orchestrator Hardened

1. **Accepted Scope:**
   - S1 DEV cycle (8 barriers × 3 repeats = 24 runs) fully verified with host orchestration, process death verification, snapshot recovery, and bound external dumps.
   - All 4 audit #14 issues resolved: mandatory 64-char hex SHA-256 for published artifacts, exact 1:1 `S1_CASE_HOOK_MAP` lookup, host/device log separation, strict intermediate pull verification.
   - Decoupled general B1 infrastructure (for spikes S2–S6) from completed S1 durability results.

2. **Automated Orchestrator Test Suite (`tests/test_orchestrator.py` — 7/7 PASS):**
   - Mandatory `adb pull` non-zero exit code halts cycle fail-closed with code 1.
   - Mandatory `adb pull` returning 0 but local file missing halts cycle fail-closed.
   - Pulled mandatory file having 0 bytes halts cycle fail-closed.
   - Immediate halt: no subsequent case is executed after an error.
   - Diagnostics persistence: failure reason, stdout, stderr, and available logcat are persisted to `logs/S1/` and logcat log before abort.
   - Final pull targets subdirectories specifically, protecting host transcripts in `logs/S1/` from overwrite.
   - Prior runs preservation: runs completed before an error remain intact on disk.
   - Bounded command timeouts: shell/ADB commands enforce `DEFAULT_CMD_TIMEOUT_SECONDS = 60`, catching `TimeoutExpired` and exiting fail-closed (code 124) without hanging runner indefinitely.

3. **Validator Test Suite (`tests/test_validator.py` — 44/44 PASS):**
   - 44 negative and positive validation tests verifying fail-closed behavior across all manifest, per-run, dump, and aggregate integrity rules.

## Stage 2 & Stage 3 — Runner Infrastructure Extended & CameraX Burst Capture (B3 / S2) Implemented

1. **Gate B3 / S2-CAMERA-BURST Real Implementation:**
   - Implemented `app/src/main/java/com/example/docscanspike/CameraXPipeline.java`:
     - Headless `PipelineLifecycleOwner` with `ProcessCameraProvider` and `ImageCapture` minimizing latency.
     - 1080p resolution strategy (`1920x1080`), fallback to device capabilities.
     - Real frame acquisition from camera (`takePicture`), byte conversion from ImageProxy to JPEG.
     - Non-blocking shutter intake: `C1_SHUTTER_REQUEST_ACCEPTED` emitted immediately and synchronously; frame capture and durable publication executed asynchronously.
     - Atomic rename to published directory (`C4_DURABLE_PUBLICATION_RETURNED`), SQLite transactions on `TABLE_ARTIFACT_RECORD` and `TABLE_AUTHORITATIVE_PAGE` (`C5_TX_CAPTURE_BEGIN` -> `C6_TX_CAPTURE_COMMITTED`), and UI visibility (`C7_PAGE_VISIBLE`).
     - Invariant enforcement: `tPageVisibleNs >= tTxCommitNs` verified for every frame.
   - Updated `app/src/main/java/com/example/docscanspike/S2Harness.java`:
     - C-W0 Idle workload: burst commands issued immediately following C1 intake without waiting for durable commit or UI visibility.
     - C-W1 (OCR) and C-W2 (Save) kept pending without artificial delays or mock sleeps until B4 and B5 real workloads are constructed.
     - Aggregate output adheres strictly to FROZEN Protocol v1.1 Section 4.7 template with outcome `INCOMPLETE` (as required by Section 4.6 when C-W1/C-W2 are pending).
   - Validated on `emulator-5554`: 10-frame burst completes in ~2.7s; all 10 captures pass with real JPEG files (~200 KB each), zero dropped commands, zero premature page visibility, and authoritative DB records.

2. **Runner Infrastructure Extended Across Spikes (`host/runner.py`):**
   - Subcommands: `run-case`, `run-suite`, `run-all`, `resume`, `validate`, `package`, `verify-gates`.
   - Per-spike schema decoupling in `validate_dir`: S1 durability fields (`pre_state_dump`, `post_state_dump`, `expected_payload_bytes`) are no longer forced onto S2–S6. S2 per-run schema validates `shutter_latency_ms`, `encoded_jpeg_bytes`, and artifact checks.
   - S2 aggregate validation: validates `qa: ["QA-03"]`, `workloads` matrix, and enforces all 6 FROZEN kill conditions (`duplicate_persistent_page_count`, `persistent_page_before_tx_commit_count`, `committed_capture_missing_page_count`, `capture_waited_for_heavy_permit_count`, `ANR_count`, `OOM_count`).
   - Package round-trip integrity: `cmd_package` automatically runs `validate_dir` on the packaged output directory, failing closed if any file fails validation.
   - Physical evidence gate verification: `verify-gates` inspects the actual `evidence_package` on disk for any gate claiming `DONE`, verifying directory existence and running `validate_dir`.
   - Resumption support (`cmd_resume`): scans existing cycle directory, identifies missing normative cases, executes only missing runs, and re-aggregates.

3. **Validator & Orchestrator Test Suites Verified (65/65 PASS):**
   - `tests/test_validator.py`: 54/54 tests pass (added 5 tests blocking S2 aggregate false PASS: requiring per-run files, forbidding empty W1/W2 on PASS, requiring all 10 counters, per-run file reconciliation, FAIL priority over INCOMPLETE on kill conditions).
   - `tests/test_orchestrator.py`: 11/11 tests pass (added tests for process death check in run-case and resume, resume APK hash mismatch rejection, resume unsupported spike rejection, and real orchestrator execution for log preservation).
   - Android instrumentation tests: 9/9 tests pass on `emulator-5554`.

## Audit Review #16 Closure

1. **S2 Validator False PASS Prevention**:
   - Mandated non-empty per-run file existence on disk (0 per-runs rejected).
   - Rejected empty `{}` for W1/W2 when outcome is `PASS`.
   - Mandated all 10 workload counters without defaulting.
   - Reconciled on-disk per-run files against `accepted_capture_count`, `committed_capture_count`, and `typed_failure_count`.
   - Enforced strict priority of `FAIL` over `INCOMPLETE` upon any kill condition.

2. **Unified Single-Case Execution**:
   - Extracted `_execute_single_s1_case` shared identically across `run-suite`, `run-case`, and `cmd_resume`.
   - Mandatory `/proc/{pid}` process death verification with 5s timeout prior to `am force-stop`.
   - Snapshot recovery and mandatory pull verification.

3. **Harden `resume` Subcommand**:
   - APK build hash verification: compares device APK sha256 (`pm path` + `sha256sum`) against manifest/cycle hash.
### Audit #17 Hardening & Resolution (All 5 Findings Addressed)
1. **Resume Package Validation (`resume_attempts.json`)**:
   - Added schema and fail-closed validation for `resume_attempts.json` in `host/runner.py:validate_dir` (validating `attempt_timestamp`, `spike`, `mode`, `serial`, `outcome` in `SUCCESS`/`FAILED`/`IN_PROGRESS`/`VALIDATION_FAILED`, rejecting emulator serials in QUALIFICATION).
   - `cmd_package` explicitly bundles `resume_attempts.json` and `manifest.json`.
   - `cmd_resume` marks `SUCCESS` only *after* `validate_dir` succeeds. Full end-to-end resume test (`test_resume_end_to_end_flow`) passing.

2. **Fail-Closed Identity Check on Resume & Evidence Preservation**:
   - `cmd_resume` requires valid device fingerprint, APK hash, and checks `cycle_serial == dev_serial` fail-closed *before* executing cases.
   - Corrupted or invalid evidence on disk is never deleted via `unlink`; it is preserved untouched and recorded as a failed attempt.

3. **S1 Disk Aggregator Metadata & Violation Recalculation**:
   - `build_s1_aggregate_from_disk` reads real device metadata from `manifest.json` (measured via `measure_device_metadata(serial)` during cycle initialization) or existing aggregate.
   - Sets outcome `INCOMPLETE` with `device: null` if measured metadata or required runs are missing (zero fake constants).
   - Recalculates all 8 violation counters (`pub_missing`, `pub_length`, `pub_checksum`, `auth_ref_corrupt`, `partial_first_ref`, `dup_art`, `staged_missing_d6`, `post_d8_incomplete`) and oracle violations directly from on-disk per-run JSONs and post-state dumps.

4. **C7 Hook Bound to Hardware Render Commit & 4 Distinct Verification Tests (Audit #18 Resolved)**:
   - Implemented dedicated [`HarnessPageDisplayView`](file:///Users/restart/Downloads/DocScanSpikeHarness/app/src/main/java/com/example/docscanspike/HarnessPageDisplayView.java):
     - Tracks actual canvas drawing via `dispatchDraw(Canvas)` setting `drawnPageId = boundPageId` (unless `suppressDraw` is activated).
     - Provides `isDisplayFullyVisibleOnScreen()` strictly validating `isAttachedToWindow()`, full ancestor hierarchy visibility via `isShown()`, `getWindowVisibility() == View.VISIBLE`, positive layout dimensions (`width > 0 && height > 0`), and non-empty `getGlobalVisibleRect()`.
   - Updated `MainActivity.java`: embeds `HarnessPageDisplayView` inside `parentContainer`, handles activity lifecycle (`onDestroy`, `onDetachedFromWindow`), and sets `launchMode="singleTask"` in `AndroidManifest.xml`.
   - Updated `PageUiPresenter` in [`CameraXPipeline.java`](file:///Users/restart/Downloads/DocScanSpikeHarness/app/src/main/java/com/example/docscanspike/CameraXPipeline.java):
     - Strictly requires `HarnessPageDisplayView` and verifies full screen visibility.
     - Confirms post-draw rendering on screen via `ViewTreeObserver.registerFrameCommitCallback` (API 29+ hardware compositor commit).
     - At frame commit, validates that `boundPageId` and `drawnPageId` both match the requested `pageId`.
     - Fails exceptionally if unattached, hidden, mismatched, or if draw is suppressed/timed out. Emits `TestHooks.C7_PAGE_VISIBLE` only upon full confirmation.
   - Replaced placeholder test with 4 distinct negative/positive tests in [`D1EncoderFinishedInstrumentedTest.java`](file:///Users/restart/Downloads/DocScanSpikeHarness/app/src/androidTest/java/com/example/docscanspike/D1EncoderFinishedInstrumentedTest.java):
     1. `testC7_1_positiveRealPageDisplay`: calls real presenter, page bound and drawn to canvas, hardware frame committed, C7 emitted, timestamp > 0.
     2. `testC7_2_negativePageMismatchThroughRealPresenter`: calls real presenter `observePageDisplayed(...)`, alters bound page before commit, asserts presenter's future fails with mismatch exception, asserts C7 hook NOT emitted.
     3. `testC7_3_negativeHiddenParent`: hides parent container (`parentContainer.setVisibility(View.GONE)`), asserts `isShown() == false`, calls real presenter, asserts future fails with visibility error, asserts C7 hook NOT emitted.
     4. `testC7_4_negativeAbsenceOfDraw`: sets `pageDisplayView.setSuppressDraw(true)`, calls real presenter, asserts future fails with draw suppressed / timeout, asserts C7 hook NOT emitted.
     5. `testC7_0_negativeUnattachedView`: tests unattached view, asserts future fails, asserts C7 hook NOT emitted.

5. **Accurate Windowed & Filtered ANR/OOM Calculation**:
   - Filtered `ActivityManager.getProcessesInErrorState()` strictly to current app PID/UID and package name; excluded `CRASHED` and general `lowMemory` from OOM count.
   - Filtered `ApplicationExitInfo` to `timestamp >= suiteStartTimestampMs` and `packageUid == myUid`.
   - Deduplicated incidents across query APIs by PID (`Set<Integer> anrPids`, `Set<Integer> oomPids`).
   - Recorded observation failures explicitly via `system_health_observation_failed` and `system_health_observation_error`. Fail-closed validator enforces `FAIL` if observation failed.

6. **Mandatory Device Serial on Resume & S2 Resume Rejection**:
   - Made device serial strictly mandatory before running cases in `cmd_resume`: fails closed immediately if cycle lacks `device_serial` or if target `--serial` differs.
   - Added negative tests: `test_resume_fails_closed_when_cycle_serial_missing` and `test_resume_fails_closed_when_device_serial_mismatched`.
   - Explicitly rejected `resume --spike S2` fail-closed with code 1 to prevent mixing distinct burst captures into the same cycle directory, tested via `test_resume_rejects_s2_spike_fail_closed`.

- **Verified S2 Cycle**: `results/DEV/cycle-20260920_164418` (30 per-run JSONs across C-W0, C-W1, C-W2, 1 aggregate JSON, 1 manifest.json, 30 logs, all validated with 0 errors).
- **Verified S3 Cycle**: `results/DEV/cycle-20260920_164153` (15 per-run JSONs, 1 aggregate JSON, 1 manifest.json, all validated with 0 errors).
- **Verified S4 Cycle**: `results/DEV/cycle-20260920_164210` (12 per-run JSONs, 12 PDF files, 1 aggregate JSON, 1 manifest.json, all validated with 0 errors).
- **Unit Tests**: 82/82 Python unit tests passing (`python3 -m unittest discover tests`).
- **Android Instrumented Tests**: 14/14 tests pass on `emulator-5554` (all C7 tests, camera capture, S1, S2, S3, S4, S5).
- **Baseline S1 Cycle**: `results/DEV/cycle-20260920_102945` (Gate B2 DONE).

## Stage 4 & Stage 5 — Gates B4 (S3-OCR-MEMORY) & B5 (S4-PDF-COMPOSITION) Implemented & S2 Concurrent Workloads Connected

1. **Gate B4 / S3-OCR-MEMORY Real Adapter & Section 5.8 Compliance:**
   - Implemented `OcrAdapter.java` with on-device Google ML Kit `TextRecognizer` (`libmlkit_google_ocr_pipeline.so` bundled in APK).
   - Enforced finite input envelope: 4000x4000 px, 12 MP limit, formats JPEG/PNG/WEBP, EXIF-normalized.
   - Enforced heavy admission permit (max 1 active heavy job) and 96 MiB decoded token budget.
   - Strict isolation: zero StateStore mutation interfaces.
   - Updated `S3Harness.java`: runs 15 runs across 3 fixtures (O-F1 1600x2000, O-F2 2000x2500, O-F3 2400x3000), computes p50/p95/p99 latencies, tracks PSS/RSS peaks.
   - Aggregate compliant with Section 5.8 Result Template (`QA-04`, `ocr_adapter_version="1.0.0-mlkit-16.0.1"`, `input_envelope_defined=true`, `outcome="PASS"`, `qualification_outcome="INCOMPLETE"`).
   - Executed on `emulator-5554`: `results/DEV/cycle-20260920_164153` (17 JSON files validated successfully).

2. **Gate B5 / S4-PDF-COMPOSITION Bounded Engine & Host Validator:**
   - Implemented `PdfComposer.java` using `android.graphics.pdf.PdfDocument` with P_MAX=20 and A4 raster envelope (595x842 pt).
   - Generated `SaveManifest` computing per-page and manifest payload SHA-256 hashes.
   - Integrated dual validation: in-app `PdfRenderer` read-back rendering verification and independent host validator.
   - Implemented `host/pdf_validator.py` using `pypdf-6.19.0` inspecting PDF object tree, page count, and dimensions.
   - Updated `S4Harness.java`: runs 12 runs across page points P1, P5, P10, P20, exports PDFs and manifests.
   - Aggregate compliant with Section 6.7 Result Template (`QA-05`, `pdf_composer_version="1.0.0-pdfdocument"`, `host_validator_name_version="pypdf-6.19.0"`, `P_MAX=20`, `outcome="PASS"`, `qualification_outcome="INCOMPLETE"`).
   - Executed on `emulator-5554`: `results/DEV/cycle-20260920_164210` (14 JSON files, 12 PDF files, all validated).

3. **Gate B3 / S2-CAMERA-BURST Concurrent Workloads Connected:**
   - C-W1 (OCR pressure): spawned background thread executing real `OcrAdapter` text recognition, holding the heavy permit during burst captures.
   - C-W2 (Save pressure): spawned background thread executing real `PdfComposer` PDF generation, holding the heavy permit during burst captures.
   - Invariant verified: `capture_waited_for_heavy_permit_count == 0` across all 3 workloads (C-W0, C-W1, C-W2).
   - Threading fix: `PipelineLifecycleOwner` sets state synchronously on the main thread.
   - Executed on `emulator-5554`: `results/DEV/cycle-20260920_164418` (30 captures: 10 C-W0, 10 C-W1, 10 C-W2; 0 kill conditions; `c_w0_outcome="PASS"`, `outcome="PASS"`, `qualification_outcome="INCOMPLETE"`).

4. **Preserved Invariants:**
   - Hook C7 preserved without changes (`HarnessPageDisplayView`, `registerFrameCommitCallback`, 4 distinct tests).
   - Gates B1 and B3 remain `IN_PROGRESS`.
   - Gate B5 is S4/PDF; Gate B6 is S5/SAF.
   - Unit test suite expanded to 82/82 PASS.

## Next Steps
- Submit Audit Report for B4, B5, and connected S2 workloads.
- Proceed to Gate B6 (S5-SAF-EXPORT) and Gate B7 (S6-PROCESS-DEATH).

## Codex seven-cycle review — 2026-09-22

- Cycles 1–5 hardened host evidence paths/checksums, strict numeric types, disk re-aggregation, external command/log failure propagation, and device metadata provenance.
- Cycle 6 closed CameraX `ImageProxy` ownership on conversion/executor failures and made lifecycle transition interruption fail closed.
- Cycle 7 made DONE gates/tasks require on-disk evidence, commit, and resolved dependencies.
- Current acceptance remains `IN_PROGRESS`: physical qualification and product inputs `INPUT-03`, `INPUT-04`, `INPUT-05`, `INPUT-07` are not supplied/verified.

## Ledger reconciliation — 2026-09-22

- `B0` now points to the existing, independently validated `dev-results` package.
- `TASK-P0-02` is marked `DONE` at commit `c820815` after the seven-cycle host-runner review and 101-test verification.
- `TASK-P8-01` is `IN_PROGRESS` while P2–P7 dependencies remain open; its historical DEV package is retained as evidence, not treated as a dependency override.
- `Q`, `A`, and dependent qualification work remain open because the required physical DUT and product decisions are not present; their statuses were not fabricated.

### Current continuation — product 0.4.190 — 2026-09-26

- **Step contract:** BUG-PDF-EOF-WHITESPACE-LIMIT-01, plan revisions 5.291–5.292. The accepted existing PDF rule allows all six PDF whitespace bytes after terminal %%EOF without an arbitrary supported trailer limit; preserve exact marker checks and reject non-whitespace tails.
- **Result:** source `.190`/309 passed the focused parser corpus 3/3, full API 36.1 connected suite 125/125 on established `emulator-5560`, exact clean-source `tools/check_all.sh --rerun`, signed APK verification and `.188`→`.190` workspace/selection update/fresh-launch check. The installed base.apk matched the release hash; firstInstallTime remained `2026-09-26 22:47:28`. The temporary AVD full-suite run with its visible system UI-not-responding overlay remains separately recorded as a failed attempt and is not counted.
- **Commits and evidence:** test commit `4f9cede`, parser fix `c672744`; tested source `8339598` and integrated source have identical tree `24b1aacc652ae6b049f850be99d43aea8cdf6e55`. Report: `execution/evidence/ENG-PDF-EOF-BOUNDARY-0.4.190.md`; device checklist/packet: `execution/device_tests/0.4.190-current-api36/`; raw attempts and manifests: `results/PRODUCT/ENG-PDF-EOF-WHITESPACE-LIMIT-0.4.{189,190}/`. Existing unrelated dirty workspace files were preserved and not included.
- **Boundary:** this accepts only the bounded EOF whitespace parser slice. T5.4/T5.5, broader malformed/encrypted corpus, FACT-07 quality/memory, provider/process-death breadth, CAP-002, physical Xiaomi, independent review, owner acceptance and MVP remain open. Q/A, task ledger and acceptance thresholds were not promoted or changed.
- **Next:** continue the wider T5.4/T5.5 audit from the existing task contract; first close remaining malformed/encrypted parser corpus and page-render resource/failure cases with an explicit contract before code changes.

## Current continuation snapshot — 2026-09-28, plan revision 5.389

- Active package: `ENG-B1B2-INVENTORY-01`; source baseline runner v1.7 (`fa4d7ad2`), product `.216` / code 335. This handoff records inventory only; it does not change app source, version, scope or acceptance status.
- `AUDIT_INVARIANTS.md` §11.17 and §11.10 now cross-reference 16 exact aggregate paths common to S1–S6, covering 96 of 263 path rows. The other 167 rows remain open. `sku` is not promoted to a nonblank requirement because the frozen templates show `null`; non-common device children remain mapped with their own spike rows.
- Prior host evidence remains limited to its exact v1.7 run (107/107): `execution/device_tests/ENG-RUNNER-INSTRUMENTATION-EXIT-01/check-all-attempt-1/host-tests.log`. This continuation performed read-only source/template/test/log inspection and `git diff --check`; no test, build, APK, ADB or device command ran.
- Next: finish the remaining path-level I01–I25 map and one unified regression contract, then execute the addressable host batch once. Runner/product edits, signed APK and release checks remain downstream of that inventory.
- Status: B1/B2 and MVP are `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`. Physical Q, independent review and owner acceptance remain open; this handoff is not an independent review or MVP acceptance.

## Current continuation snapshot — 2026-09-28, plan revision 5.390

- Active package `ENG-B1B2-INVENTORY-01`; runner v1.7 (`fa4d7ad2`), product `.216` / code 335. The exact aggregate path inventory in `AUDIT_INVARIANTS.md` §11.10 now maps all 263 rows to a field rule group, evidence limit and unified target; counts: S1 39, S2 68, S3 40, S4 33, S5 47, S6 36.
- `AGG-ROOT-SCHEMA-01`, `AGG-COUNTER-MATRIX-01`, `AGG-METRICS-MODE-01`, and `I13-QUAL-MATRIX-01` are parameterized slices of `I12-AGGREGATE-PATH-MATRIX-01`, not separate release cycles. `§11.18` preserves unresolved evidence boundaries for S1 payload size, conditional device children and S2 workload roots.
- Source/frozen-template/test/log inspection and a read-only inventory consistency check only; `git diff --check` passed. No tests, build, APK, ADB or device command ran. The prior 107/107 host log remains evidence only for its recorded runner v1.7 run.
- Next: reconcile the rest of I01–I25 and freeze the single full positive/negative contract before running one addressable host batch. No source/version or acceptance status changed. B1/B2 and MVP remain `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`; physical Q, independent review and owner acceptance remain open.

## Current continuation snapshot — 2026-09-28, plan revision 5.391

- Active package `ENG-B1B2-INVENTORY-01`; audited baseline remains runner v1.7 (`fa4d7ad2`, SHA-256 `95b75edd16df18fc7ef939a79dc8202c319ae70d84d7fbd44ccc5162f97dcbab`) and product `.216` / code 335. The current documentation change is isolated to `AUDIT_INVARIANTS.md`, the existing plan, and this handoff; it does not alter source/version/status.
- `AUDIT_INVARIANTS.md` §11.19 now maps every I01–I25 requirement to the user result, applicable evidence and limit, exact residual, regression target, measurable closure condition, and dependency. §§11.2–11.18 contain supporting finite source/path mappings, including all 263 aggregate rows and the false-coverage corrections. The batch contract is now ready; its tests/implementation remain NOT RUN.
- Single batch targets: common per-run/state/S1 oracle; aggregate paths/counters/metrics and exact qualification matrices; S1–S6 per-run evidence; command/file boundaries; resume; package transaction; gate/task reciprocity. Reuse v1.7 107/107 only for named branches. Next: implement these together, run affected checks, and run the complete configured host/build check once on the final candidate.
- This turn performed source/document reconciliation and `git diff --check` only; no tests, build, APK, package, ADB or device command ran. The existing OCR Cyrillic/Spanish evidence remains `.216` scoped at `execution/evidence/ENG-OCR-02-current-cyrillic-spanish-0.4.216.md`.
- B1/B2 and MVP remain `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`. A connected API 36.1 device is needed for a new DEV run; Gate Q still needs the physical Xiaomi 14 Ultra, serial and qualified destination. Independent review and owner acceptance remain outstanding. No gate/task/CAP status was promoted.

## Current continuation snapshot — 2026-09-28, plan revision 5.392

- Active package `ENG-B1B2-INVENTORY-01`; baseline remains runner v1.7 (`fa4d7ad2`, SHA-256 `95b75edd16df18fc7ef939a79dc8202c319ae70d84d7fbd44ccc5162f97dcbab`), product `.216` / code 335. Documentation commit `46d9ba7b` freezes the complete I01–I25 inventory and single batch contract in `AUDIT_INVARIANTS.md` §§11.2–11.19.
- First addressable slice is I20: configure `tools/check_all.sh` to run the pytest-style `tests/test_s6_validator.py` and `tests/test_audit_reproduction.py` suites while retaining unittest discovery; reconcile stale test metadata after confirming the actual hook-to-validator mapping. Contract and measurable focused acceptance are recorded in plan revision 5.392. Do not change app, protocol, thresholds, physical tuple or statuses.
- The plan/handoff contract is committed in the preceding docs-only commit; no focused pytest run, code/test edit, build, APK, ADB or device command has run. The retained host 107/107 log remains limited to its recorded v1.7 run and does not include these pytest-style cases.
- After committing the contract, execute the focused suite and retain raw output under `execution/device_tests/ENG-B1B2-INVENTORY-01/`; diagnose test-fixture versus runner failures and continue the unified I01–I25 batch. One final full `tools/check_all.sh --rerun` is required on the final candidate.
- Gate/task/CAP/MVP statuses remain `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`. Device-dependent DEV, physical Xiaomi Q, independent review and owner acceptance remain outstanding; this step cannot accept B1/B2 or MVP.

## Current continuation snapshot — 2026-09-28, plan revision 5.393

- Active package remains `ENG-B1B2-INVENTORY-01`; product `.216` / code 335, runner v1.7 SHA-256 `95b75edd16df18fc7ef939a79dc8202c319ae70d84d7fbd44ccc5162f97dcbab`. This snapshot documents the S6 pytest collection/oracle implementation in `tools/check_all.sh` and `tests/test_audit_reproduction.py`; no Android app or `host/runner.py` source changed.
- Focused `python3 -m pytest -q tests/test_s6_validator.py tests/test_audit_reproduction.py`: **149 passed / 0 failed** in 7.29 seconds. Every R01–R41 mutation now asserts its named diagnostic; source-derived coverage confirms all 41 runner branch IDs. `bash -n tools/check_all.sh` and `git diff --check` passed. Raw command/environment/output are retained in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-1-focused-s6/`.
- This is one collected host slice only. It does not establish the rest of the frozen I01–I25 batch, final full `tools/check_all.sh --rerun`, physical/device qualification, independent review or owner acceptance. No gate/task/CAP/MVP status changed.
- Next: continue §11.19's single integrated host batch; run the complete configured check once on the final batch candidate.

## Current continuation snapshot — 2026-09-28, plan revision 5.394

- Active package remains `ENG-B1B2-INVENTORY-01`; checkpoint commit `3ab0312b` is on isolated branch `experiment/pdf-scratch-output-close`. Product is `.217` / code 336. Host runner source has frozen common per-run checks in progress from v1.7 baseline; bump its version when the final batch candidate is assembled.
- Common schema slice evidence: focused pytest **201/201**, validator/S5 unittest **84/84**, orchestrator unittest **22/22**. The first orchestrator attempt exposed a stale resume fixture missing frozen `notes`; fixture was corrected and the complete orchestrator suite then passed. Initial fixture and assertion failures remain preserved with raw logs under `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-2-common-run-schema/` through `attempt-6-common-run-orchestrator/`.
- The commit enforces the 17 frozen Protocol §9 fields, basic types and per-spike case/hook associations, JSON root roles, operation identity uniqueness and non-empty embedded S1 dumps; S2 emits `notes: null`. This does not yet close S1 D1–D8 semantic state/reference/owner/byte relationships, all identity links, remaining I01–I25 targets or final full check. No full check/build/APK/ADB/device command ran for this checkpoint.
- Next is the paired `RUN-STATE-SCHEMA-01` + `S1-STATE-ORACLE-01` matrix under `AUDIT_INVARIANTS.md` §§11.5.2 and 11.19; keep D1–D8 positive/negative semantics exact and distinguish empty state from query failure. Continue the integrated batch after that. Final full `tools/check_all.sh --rerun`, device DEV, physical Xiaomi Q, independent review and owner acceptance remain outstanding. B1/B2 and MVP stay `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation — preregistered S1 diagnostic-open correction, plan revision 5.395

- Active package remains `ENG-B1B2-INVENTORY-01`; worktree `/private/tmp/docscan-spikeharness-0.4.208-6a21267`, branch `experiment/pdf-scratch-output-close`, base `ff751985`. Candidate versions in the uncommitted worktree are product `.218` / code 337 and harness `.1.4` / code 5.
- Focused source work has implemented host and app-side D1–D8 state/reference/owner/byte checks. Attempt 22 host results: pytest 251/251; unittest 106/106; `git diff --check` and `bash -n tools/check_all.sh` pass. Raw logs are in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-22-s1-integrated-host-regressions/`. Attempt 21 compiles the actual harness androidTest source, but no device test ran.
- Source review found `S1Diagnostic.capture` still opens through `SQLiteOpenHelper.getReadableDatabase()`, which may create/upgrade a database despite neutral `onOpen`. This is being corrected under frozen Protocol §3.5 / I09, with missing-DB no-create and legacy-v1 no-upgrade instrumentation pre-registered in plan revision 5.395. No acceptance criterion changes.
- Next: perform the preregistered read-only-open fix, run its isolated Android instrumentation on API 36.1 if the configured AVD is available, re-run affected host checks, then continue the single I01–I25 batch. Do not start a release APK/full regression loop until the batch candidate is final.
- Status remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`; full batch, final full `check_all.sh --rerun`, fresh device DEV evidence, physical Xiaomi Q, independent review and owner acceptance remain open. This handoff is not a review or MVP acceptance.


## Current continuation snapshot — plan revision 5.396

- Active package ENG-B1B2-INVENTORY-01 remains on experiment/pdf-scratch-output-close. The preregistration commit is a9de692c; source candidate is product .219/code 338, harness .1.5/code 6, with runner version bump deferred until the integrated batch candidate is assembled.
- The paired common run/state/S1 slice is now implemented in the worktree. Attempt 24 passes the no-create/no-upgrade capture test 1/1; attempt 26 passes the query-error-vs-empty database test 1/1 on DocScanMvpTest_API_36.1. Attempt 25 passes 251 pytest and 106 unittest tests plus static checks. Attempts 23–26 retain raw logs/reports.
- The S1 read-only capture correction is in frozen Protocol §3.5 scope. The result remains emulator/harness evidence; the final S1 D1–D8 DEV repetitions on the integrated candidate have not run.
- Next target is the single §11.19 item-2 matrix: I12 aggregate path/root/counter/metrics plus I13 exact QUAL matrices. First reconcile all frozen paths and source/consumer dependencies before edits; no new APK/full release loop until the final candidate.
- B1/B2 and MVP remain IN_PROGRESS_NOT_ACCEPTED / REVIEW_REQUIRED. The remaining integrated I01–I25 work, final full check, fresh device DEV package, physical Xiaomi Q, independent review and owner acceptance are outstanding. No gate/task/CAP status changed.

## Current continuation snapshot — 2026-09-28, plan revision 5.397

- Active `ENG-B1B2-INVENTORY-01` remains on `experiment/pdf-scratch-output-close`; checkout HEAD is `e1d856e616e89dfe7d043b8362d50ad60927bfdd` and the working tree contains the integrated candidate diff. Product candidate `.219` / code 338; harness `.1.5` / code 6. Runner version bump remains due before final candidate checks.
- Addressable I13 validator slice now enforces exact QUAL matrices and cross-source row identities for S2–S6; existing S1 exact count is reused. Attempt 41 results: matrix pytest 20/20, validator/S5 unittest 84/84, S6/schema pytest 201/201, compile and `git diff --check` passed. Synthetic host evidence only; no APK, ADB, device or physical run. Details and SHA manifests: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-41-qualification-matrix-contract/`; checksums for attempts 37–40 also verified.
- `AUDIT_INVARIANTS.md` §11.12.1, plan 5.397 and `ENG-QUAL-MATRIX-EVIDENCE-01` record the implementation-level slot fields and limits. Next: finish I12 counter/measurement source binding and then the remaining I14–I25 slices under the single §11.19 package; reserve one full `tools/check_all.sh --rerun` for the final candidate.
- MVP, B1/B2 acceptance and statuses are unchanged. Real QUAL S2–S6 production is not yet implemented; S5 INPUT-05 remains undefined. The eventual handoff still needs current-source full validation, APK artifact, fresh API 36.1 DEV evidence, physical Xiaomi Q, independent review and owner acceptance. This is not an acceptance or self-review.

## Current continuation snapshot — 2026-09-28, plan revision 5.398

- Active package remains `ENG-B1B2-INVENTORY-01`, item 2 / I12 of `AUDIT_INVARIANTS.md` §11.19. Candidate product `.219` / code 338, Android harness `.1.6` / code 7, host runner header 1.8. Work remains on isolated `experiment/pdf-scratch-output-close`; prior dirty batch work is preserved.
- Attempt 42 adds S2 aggregate source reconciliation and targeted mutations: aggregate-contract pytest 22/22; targeted S2 validator unittest 3/3; main and instrumentation Java compilation both BUILD SUCCESSFUL. Raw logs, initial failures and checksums are in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-42-s2-counter-source-reconciliation/`.
- Boundary: no APK or instrumentation/device execution in attempt 42. The heavy-permit wait false value has no runtime event recorder; health PID identifiers are not a timestamped system-event transcript. S1 and S3–S6 I12 cells and the rest of §11.19 remain open. No MVP/gate status changed.
- Next: continue S3/S4 aggregate-to-run/PDF source reconciliation, then S5/S6 and the remaining frozen batch rows. Build/package the handoff APK and run the one final `tools/check_all.sh --rerun` on the integrated candidate. Physical Xiaomi Q, independent review and owner acceptance remain mandatory open gates.

## Current continuation snapshot — 2026-09-28, plan revision 5.399

- Active package remains `ENG-B1B2-INVENTORY-01`, item 2 / I12 of §11.19. Product `.219` / code 338; Android harness `.1.8` / code 9; host runner header 1.8.
- Attempt 43 binds available S3 resource/latency/direct-write fields to per-run metrics: pytest 24/24; targeted S3 validator unittest 1/1; Python compile and diff check pass. Attempt 44 adds S4 per-run heavy-job observation and aggregate source binding: pytest 25/25; complete 12-run/PDF validator unittest 1/1; main and instrumentation source compilation both BUILD SUCCESSFUL. Raw outputs, environment and checksums are in their attempt directories.
- Remaining S3 gaps: unaccounted jobs, timestamped ANR/OOM sources, stale/candidate commits. Remaining S4 gaps: PDF/manifest kill counters and timestamped health sources. Existing S4 “missing or nonzero counter” test still exits before its named branch and is not accepted as counter evidence. No APK/device runtime was performed in these two attempts.
- Next: finish full S3/S4 exact source/counter matrix, then S5/S6 and I14–I25 within the frozen batch. Only after integration: one full `tools/check_all.sh --rerun` and handoff APK. Physical Xiaomi Q, independent review and owner acceptance remain open; MVP is not accepted.

## Current continuation snapshot — 2026-09-28, plan revision 5.401

- Active `ENG-B1B2-INVENTORY-01`, item 2 / I12, branch `experiment/pdf-scratch-output-close`. Latest committed checkpoint before this slice: `6502954b974bc23dd0c90d7a1af1e4e2d6fe94fc`, tag `before-s3-health-source-fix`. Current candidate product `.219` / code 338, harness `.1.9` / code 10, host runner 1.8.
- Attempt 45 corrects the S3 health observation window to start before OCR, retains ANR/OOM PID arrays, binds counters to those arrays and treats missing ActivityManager as observation failure. Focused pytest 25/25 + source-order regression 1/1; Java main/androidTest compilation successful. Evidence and checksums: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-45-s3-health-window-source/`.
- Boundary: no runtime ANR/OOM injection/device execution; arrays omit timestamps/reasons and historical query is capped at ten. Remaining S3/S4 I12 counters, S5/S6 and I14–I25 are active. Full final check, APK, device DEV, physical Xiaomi Q, independent review and owner acceptance remain outstanding. MVP is not accepted.
- Next: continue the complete S3/S4 source-counter matrix, then S5/S6 and remaining frozen batch. Build current APK and run full `tools/check_all.sh --rerun` on integrated transfer candidate only.

## Current continuation snapshot — 2026-09-28, plan revision 5.402

- Latest source/evidence checkpoint: commit `3ac32f3ea7ac75e63d339ff7babae8443e19ef5e`; S3 health-window correction remains committed. Active item is still the single `ENG-B1B2-INVENTORY-01` I12 matrix.
- S4 false counter test was removed because it failed on an empty per-run package before reaching the named field. The aggregate outcome helper now exercises every S4 frozen kill counter using the correct threshold. Attempt 46: aggregate pytest 25/25; complete 12-run/PDF validator test 1/1; evidence and checksums at `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-46-s4-counter-coverage-correction/`.
- This is helper-level counter/outcome coverage, not source-bound PDF/manifest/health counter validation. S3 unaccounted-job/candidate commit counters, S4 PDF and health counters, S5/S6 and I14–I25 remain. No APK/device/full regression or acceptance promotion occurred.
- Continue the finite source/counter matrix in the existing batch. After integration, run one final `tools/check_all.sh --rerun` and build the transfer APK; physical Xiaomi Q, independent review and owner acceptance remain open.

## Current continuation snapshot — 2026-09-28, plan revision 5.404

- Active package `ENG-B1B2-INVENTORY-01`, item 2 / I12. Last committed checkpoint `a7888ed58374ebe51cf6cdb6642128cdddfaff4a`, tag `before-s4-pdf-counter-binding`; current candidate product `.219` / code 338, harness `.1.10` / code 11, runner 1.8.
- Attempt 47 binds S4's four PDF/manifest counters to the exact successful run IDs whose PDFs/SaveManifests pass host validation; also binds ANR/OOM counters to PID arrays. Aggregate pytest 26/26; complete 12-run S4 PDF integration 1/1; main/androidTest Java compile successful. Raw outputs, initial fixture failures and hashes in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-47-s4-pdf-counter-source-binding/`.
- Boundary: invalid PDFs still fail package validation; no APK or device run. Health IDs have no timestamps/reasons. S4 remaining exact-mode/measurement and failure semantics, S3 residual counters, S5/S6 and I14–I25 remain open. Full check/APK, physical Xiaomi Q, independent review and owner acceptance remain outstanding.
- Next: finish remaining I12 rows from §§11.15–11.18, then continue the finite I14–I25 batch. Run the single full `tools/check_all.sh --rerun` and build the current APK after integration; MVP stays `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation snapshot — 2026-09-28, plan revision 5.406

- Active batch `ENG-B1B2-INVENTORY-01`, I12. Latest checkpoint before S3 transaction evidence: commit `1745e9a1f7b0d9c859a4b309f9fa961992c40748`, tag `before-s3-candidate-commit-source-binding`. Candidate product `.219` / code 338; harness `.1.11` / code 12; host runner 1.8.
- Attempt 48 adds S3's three candidate commit observations and host checks for row/source consistency. Aggregate pytest 27/27; targeted S3 validator unittest 1/1; main/androidTest Java compile successful. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-48-s3-candidate-commit-source-binding/`.
- Boundary: test fixtures and source compile only; no APK or runtime. Harness-reported state is checked internally, not independently re-read by host. S3 heavy-job counter and richer health events remain open; other I12 and I14–I25 work remains. No gate/MVP acceptance changed.
- Next: finish remaining I12 and frozen batch items; then one final full check and build current APK. Physical Xiaomi Q, independent review and owner acceptance remain outstanding.

## Current continuation snapshot — 2026-09-28, plan revision 5.410

- Active batch remains `ENG-B1B2-INVENTORY-01`, I12, branch `experiment/pdf-scratch-output-close`. Source checkpoint before current uncommitted slice: `dfb8e82d3d0d25be6f52f672a9d0a1eb4fa944c2`; current harness candidate `.1.12` / code 13; product `.219` / code 338; runner 1.8.
- Attempt 49 adds per-run S3 admission/start/pending/session evidence and derived `heavy_job_unaccounted_execution_count`. Permit and bitmap ownership span incomplete ML Kit tasks after timeout/await error. Focused pytest 27/27, targeted S3 validator unittest 1/1, app main/androidTest compilation successful, Python compile and diff check pass. Evidence and hashes are in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-49-s3-heavy-job-accounting-source/timeout-lifecycle/`.
- Evidence is host/source and compile only; async runtime behavior, APK, full regression and device execution were not run. S3's remaining exact mutation/health evidence, rest of I12, S5/S6 and I14–I25 remain. Full `tools/check_all.sh --rerun`, final APK, fresh API36 DEV, physical Xiaomi Q, independent review and owner acceptance are still required. MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.
- Next: continue the finite I12 inventory's remaining S3 exact-source and health-observation cells, then S4/S5/S6 and I14–I25. Integrate before one final full configured check and handoff APK.

## Current continuation snapshot — 2026-09-28, plan revision 5.412

- Active batch `ENG-B1B2-INVENTORY-01`, I12, branch `experiment/pdf-scratch-output-close`. Latest checkpoint `5951a75a3b608bd7123b5a69a2bad259d71c8364`; current uncommitted health-provenance candidate harness `.1.13` / code 14, product `.219` / code 338, runner 1.8.
- Attempt 50 adds typed S3 active ANR/historical exit observation rows and explicit suite time window, while preserving frozen counters/PID arrays. Focused pytest 28/28, targeted validator unittest 1/1, main/androidTest compilation successful, Python compile and diff check pass. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-50-s3-health-event-provenance/`.
- Host synthetic rows and Java compilation only; no Android event injection, APK, full regression or device runtime. S3/S4 exact counter/measurement cases, S5/S6 and I14–I25 remain. Final full check/APK, fresh API36 DEV, physical Xiaomi Q, independent review, owner acceptance remain required; MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.
- Next: finish remaining I12 cells in the frozen inventory; only then proceed through registered I14–I25 and run the single final full check/build on the integrated transfer candidate.

## Current continuation snapshot — 2026-09-28, plan revision 5.413

- Active `ENG-B1B2-INVENTORY-01` / I12. Latest committed checkpoint `c19136352f36971cd21f414e2412f973e9ff1ed4`; current uncommitted refinement is harness `.1.14` / code 15, product `.219` / code 338. Attempt 51 binds historical ANR and low-memory reason names to Android's documented numeric reason constants and rejects cross-kind mutations. Focused pytest 28/28; targeted S3 validator 1/1; app main/androidTest compile successful. Evidence in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-51-s3-health-reason-identity/`.
- Source/synthetic-host evidence only; no runtime health injection, full regression or APK. Remaining finite I12, I14–I25 and required physical/review/owner gates remain. MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation snapshot — 2026-09-28, plan revision 5.415

- Active batch `ENG-B1B2-INVENTORY-01` / I12, isolated branch `experiment/pdf-scratch-output-close`; latest committed source checkpoint `d4f7d2ca4bade45d270d6d4d97ad6ddb05bb092c`, harness `.1.14` / code 15, product `.219` / code 338, runner 1.8.
- Attempt 52 is a test-only addition for the complete S3 source-valid counter matrix: six frozen kill counters, two candidate diagnostics, malformed types/domains, positive kill behavior, exact resource thresholds and aggregate-to-source drift. Aggregate pytest 29/29 and targeted S3 validator 1/1; Python compile/diff check pass. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-52-s3-counter-source-matrix/`.
- Attempt 52 is uncommitted test/documentation/evidence work. No Android build/runtime, APK, or full `check_all` for this test-only slice. S4 residual health/failure and the rest of I12, I14–I25, final full check/APK, fresh API36 DEV, physical Xiaomi Q, independent review and owner acceptance remain. MVP is not accepted.
- Next: complete the finite S4 health and failure-source matrix, then remaining I12 and I14–I25; preserve one integrated full check and handoff APK.

## Current continuation snapshot — 2026-09-28, plan revision 5.417

- Active batch `ENG-B1B2-INVENTORY-01` / I12; source base is committed through `14e51cb66111e25468c1b410116da47e9610e69c`. Current S4 health-source candidate: harness `.1.15` / code 16, runner 1.9, product `.219` / code 338. Worktree has uncommitted S4 health producer/shared-host-oracle changes.
- Attempt 53 retains typed S4 health observations and tests the shared S3/S4 source oracle. Aggregate pytest 30/30; S3+S4 targeted validators 2/2 including 12-run PDF integration 25.738s; app main/androidTest compilation, Python compile and scoped diff check pass. Evidence and hashes: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-53-s4-health-event-provenance/`.
- No Android health injection, device runtime, APK or full `check_all`. Remaining S4 source/failure cells, I12, S5/S6 and I14–I25 remain. Physical Xiaomi Q, independent review and owner acceptance remain mandatory; MVP not accepted.
- Next: finish the finite S4 exact source/counter and failure-semantic cells, then remaining I12/I14–I25; run the single full configured check and package APK only on the integrated transfer candidate.

## Current continuation snapshot — 2026-09-28, plan revision 5.419

- Active batch `ENG-B1B2-INVENTORY-01` / I12; latest source commit `f50d8c0359badba4cdf599d57d6156c18454bc37`; harness `.1.15` / code 16, runner 1.9, product `.219` / code 338. Attempt 54 is test-only and currently uncommitted.
- S4 exact source/outcome matrix: pytest 31/31; S3/S4 targeted validation 2/2 including complete 12-run S4 PDF package validation; Python compile and diff check pass. Evidence under `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-54-s4-counter-source-matrix/`.
- No Android/runtime or APK in attempt 54. S4 health injection/failure semantics and remaining I12, then I14–I25, final full check/APK, fresh API36 DEV, physical Xiaomi Q, independent review and owner acceptance remain. MVP not accepted.
- Next: finish remaining finite I12 measurement/source and failure semantics; then complete the frozen remainder and run the single final full check/build on the integrated candidate.

## Current continuation snapshot — 2026-09-28, plan revision 5.421

- Active batch remains `ENG-B1B2-INVENTORY-01` / I12, branch `experiment/pdf-scratch-output-close`; latest committed source baseline `1a9162bc9cba7bfbbdc7039303e222fe4b3bb0b7`. Current S3 metric candidate harness `.1.16` / code 17; product `.219` / code 338; runner 1.9. Worktree contains uncommitted S3 metrics/config reconciliation.
- Attempt 55: aggregate pytest 33/33, targeted S3 validator 1/1, app main/androidTest compile successful, Python compile and diff check pass. Evidence/hashes: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-55-s3-measurement-source-matrix/`. Temporary DEV config now matches INPUT-03's existing temporary values; production INPUT-03 remains OPEN.
- No APK, full check, Android OCR runtime/accuracy, or physical test. Remaining S3/S4 measurements/failure semantics and I12, S5/S6, I14–I25, final full check/APK, fresh API36 DEV, physical Xiaomi Q, independent review, owner acceptance remain. MVP not accepted.
- Next: finish remaining finite I12 exact-path cells, then continue registered I14–I25; run the single full configured check/build on the integrated transfer candidate.

## Continuation snapshot — 2026-09-28, plan revision 5.423

- Active package remains `ENG-B1B2-INVENTORY-01`, I12 within the frozen §11.19 batch. Code checkpoint `1eaf4e54` on `experiment/pdf-scratch-output-close`; product `.219` / code 338 unchanged, app harness `.1.17` / code 18, host runner 1.10.
- S4 measurement source and page-grid implementation is committed: synthetic DEV profile matches S4Harness P_MAX=20 and points 1/5/10/20; PASS metrics are complete and source-derived; QUAL grid follows protocol §6.3 and INPUT-04 P_MAX=100 at points 1/25/50/100. PDF dimensions explicitly report points. Attempt 56: focused host matrix 87/87, complete 12-run S4 PDF/package integration 1/1 (16.949s), main/androidTest Java compilation successful, static checks exit 0. Raw logs, environment and SHA-256 manifest: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-56-s4-metrics-and-page-grid/`.
- Boundary: synthetic DEV and host fixtures only. No S4 product Save qualification, OCR/runtime, APK, full `check_all.sh --rerun`, ADB/device, physical Xiaomi Q, or independent review. Product quality/INPUT-04 acceptance, remaining S4 failure semantics, rest of I12/I14–I25, fresh device DEV, independent review and owner acceptance remain open. MVP stays `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`; gate/task/CAP states did not change.
- Next: continue the finite registered inventory with remaining S4 failure semantics and S5/S6 I12/per-run cells, then I14–I25; reserve one full configured check and current APK for the integrated transfer candidate.


## Current continuation snapshot — 2026-09-29, plan revision 5.433

- Active `ENG-B1B2-INVENTORY-01`, branch `experiment/pdf-scratch-output-close`; HEAD `d1c01bc34ecb1243ef0ed3b9fa27767df4998d00`. Product `.219`/code 338 and app harness `.1.18`/code 19 unchanged; host runner 1.15.
- Attempt 61 S1 aggregate and source reconciliation is complete: host pytest 163/163, static checks pass. Evidence is synthetic host only.
- Next finite package: attempt 62 S2 latency summary source reconciliation. S2Harness emits six percentiles for each C-W0/C-W1/C-W2 from successful per-run `request_to_tx_commit_ms` and `request_to_page_visible_ms`; typed failures use -1 sentinels and are excluded from lists. Host validator currently checks aggregate metric type/presence but not source-derived values. Register the complete DEV/QUAL inventory, failure semantics and mutations in plan rev5.434 before touching source. Evidence target `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-62-s2-latency-source-reconciliation/`.
- MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`; remaining I12/I14–I25, integrated full check/APK, current Android DEV, physical Xiaomi qualification, independent review and owner acceptance remain.


## Current continuation snapshot — 2026-09-29, plan revision 5.436

- Active `ENG-B1B2-INVENTORY-01`, branch `experiment/pdf-scratch-output-close`; source HEAD `a672a1e98bf5a0369e48e5f26cd1377f3f497911`; host runner 1.16. Product `.219`/338 and Android harness `.1.18`/19 unchanged.
- Attempt 61 S1 and attempt 62 S2 host source-reconciliation packages are documented and committed. Attempt 62 result: 117/117 focused host tests; strict type/source-binding for all 18 percentiles, nearest-rank, page-visible endpoint check, failure sentinel handling, and synthetic QUAL 6,000-row exact matrix. Evidence folder `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-62-s2-latency-source-reconciliation/`.
- No APK, Android runtime, complete `tools/check_all.sh --rerun`, fresh physical/device package, physical Xiaomi 14 Ultra, independent review or owner acceptance occurred. Transaction commit time in S2 per-run source is not independently reconstructible because the producer omits the commit timestamp; the exact aggregate-to-per-run boundary is recorded.
- Next: refresh the active §11.19 finite remainder and select the highest-priority unresolved user-result row in existing I12/I14–I25 scope; preregister a complete package before source edits. Save one full configured check and current APK for the integrated transfer candidate. MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.


## Active child slice — plan revision 5.437

- Attempt 63 preregisters a complete S2 per-run capture trace/workload slice under the same ENG-B1B2 I01–I25 batch: six metrics, C1–C7 monotonic source, artifact/page state, typed-failure log and sentinels, W0/W1/W2 workload overlap. Contract/inventory: plan rev5.437 and `AUDIT_INVARIANTS.md` active I14–I15 S2 entry. Evidence target: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-63-s2-per-run-capture-trace/`.
- Baseline: source commit `91fd1fa16a8d864e35d1896d14cd1f549a0236e6`; host runner 1.16; app harness `.1.18`/19; product `.219`/338. No package code edits have started.

## Current continuation snapshot — 2026-09-29, plan revision 5.438

- Active `ENG-B1B2-INVENTORY-01`, isolated branch `experiment/pdf-scratch-output-close`; attempt 63 source commit `098247aecd1e1b5587439e76dde0e388bb975a12`. App harness `.1.19`/code 20, host runner 1.17, product `.219`/338.
- Attempt 63 S2 source/consumer slice: structured C1–C7 and typed-failure logs, six metric/trace checks, page/artifact identity, and burst/permit interval reconciliation. Committed-source focused host tests 121/121; Python compile, diff check and app/androidTest Java compile succeeded. Evidence and checksum manifest: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-63-s2-per-run-capture-trace/RESULT.md`.
- Boundary: no instrumentation runtime, APK, Android DEV, full configured check, or physical-device qualification. Runtime behavior for I14/I15 remains open. Continue the remaining mapped I12/I14–I25 batch and preserve one full check/APK for its integrated candidate. Independent review, fresh DEV, Xiaomi 14 Ultra qualification and owner acceptance remain mandatory; MVP `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Active child slice — plan revision 5.439

- Attempt 63 result and commits: `098247ae` (S2 source) and `f3a64b0e` (docs/evidence), current HEAD before attempt 64 preregistration `f3a64b0e`. S2 focused host 121/121; app/androidTest Java compile passed. Full evidence in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-63-s2-per-run-capture-trace/`.
- Attempt 64 preregisters S3 per-run OCR measurement/raw-source and aggregate reconciliation within the same I01–I25 package (plan 5.439; `AUDIT_INVARIANTS.md` active section). Device inventory now shows `emulator-5554` online, but APK/instrumentation execution remains reserved for the integrated candidate per the attempt-63 boundary.
- Frozen OCR language scope is unchanged. Cyrillic/Spanish evidence remains the bounded `.216` result, including documented Spanish accent losses. MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation snapshot — 2026-09-29, plan revision 5.440

- Active `ENG-B1B2-INVENTORY-01` on `experiment/pdf-scratch-output-close`; source commits: attempt 63 S2 `098247ae`, attempt 64 S3 `0470bdf0d363767ad5701af203b845471f46dfb2`. Current harness `.1.20`/code21, host runner 1.18, product `.219`/338.
- Attempt 64 structured S3 source logs and host per-run metric/domain reconciliation: pytest 122/122, Python compile/diff check and Android main/androidTest compile passed. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-64-s3-per-run-measurement-reconciliation/RESULT.md` plus raw logs and checksum list.
- No APK, instrumentation run, full configured check or physical qualification. Emulator `emulator-5554` is online, but the integrated candidate run is reserved until the mapped package is complete. Cyrillic/Spanish evidence is still `.216`-bound and documents losses in Spanish diacritics. Continue I14–I20 and remaining I12/I21–I25. Independent review, current fresh DEV, physical Xiaomi 14 Ultra and owner acceptance remain open; MVP `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Active child slice — plan revision 5.441

- Attempts 63/64 committed and evidenced; current base commit `8a98e445`, app harness `.1.20`/code21, runner 1.18, product `.219`/338. S3 attempt64 host 122/122 plus source compile evidence is under its evidence folder.
- Attempt 65 preregisters S4 SaveManifest-to-PDF identity/payload validation, including exact fields, canonical ordered SHA-256 derivation, PDF page IDs/raster hashes and point count. Contract is plan 5.441 and the active subsection in `AUDIT_INVARIANTS.md`. No source edits have started for attempt65.
- MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`; physical qualification, integrated full check/APK, independent review and owner acceptance remain open.

## Current continuation snapshot — 2026-09-29, plan revision 5.458

- Active `ENG-B1B2-INVENTORY-01` / attempt 71 on isolated branch `experiment/pdf-scratch-output-close`; no edits to the dirty original checkout. Current in-progress app harness target `.1.24`/code25. App implementation and C7 visibility fix are uncommitted and isolated in the attempt evidence workspace.
- Scheduler source now handles an async nested OCR release without releasing the owner permit. Debug/main/androidTest compile and assemble passed; async nested release, top-level async release, nested budget enforcement, C7 visible positive and hidden-parent negative each passed 1/1 on `DocScanMvpTest_API_36.1`.
- The one post-fix S2 instrumentation invocation completed exactly one named JUnit test and all 30 S2 operations, but host metadata collection failed before pull: API36 Toybox rejects `df -T /data`. Preserved log is `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-71-s2-api36-runtime/run-suite-final.log`. Plan 5.458 / audit §11.25 preregisters exact `/data` parsing from `/proc/mounts`, fail-closed parser controls, runner 1.25, then one complete S2 rerun.
- Earlier attempt-71 partial runtime evidence, including the initial all-C7 failure and the post-display C-W1 permit result, remains preserved. S2 is not closed until final pull and host validation pass. Parent I12/I14–I25, one integrated full check/current APK, fresh physical Xiaomi 14 Ultra qualification, independent review and owner acceptance remain. MVP `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation snapshot — 2026-09-29, plan revision 5.442

- Active `ENG-B1B2-INVENTORY-01`; attempt 65 source commit `fbb81ea8`, documentation/evidence commit pending. Host runner 1.19; product `.219`/338 and harness `.1.20`/21 unchanged.
- S4 strict SaveManifest-to-operation/PDF binding passed focused committed-source host tests 122/122, including full synthetic P1/P5/P10/P20 PDF package and missing/type/count/order/hash/payload mutations. Python compile and diff check pass. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-65-s4-save-manifest-pdf-binding/RESULT.md`.
- No Android runtime, APK, integrated full check, physical Xiaomi 14 Ultra, independent review or owner acceptance. Continue finite I14–I20 then I12/I21–I25. MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation snapshot — 2026-09-29, plan revision 5.459

- Attempt 71 S2 API36.1 emulator runtime slice completed on `experiment/pdf-scratch-output-close`. Commits: preregistration `68f2aff6`, app/harness `5fefb87a` (0.1.24/code25), host runner `c1afe918` (1.25). Product remains `.219`/338.
- Final `run-suite S2` exited 0; exact instrumentation summary was `OK (1 test)`, pull succeeded, and host `validate_dir` accepted 32/32 JSON files. Aggregate PASS; all three workloads have 10/10 committed captures, zero typed failures and waits, valid overlap. C-W1 OCR permit covers full burst. Focused device controls passed 5/5; focused host controls 2/2; build/static checks passed.
- Complete final cycle, all retained failed/partial attempts and verified 205-entry SHA-256 manifest are under `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-71-s2-api36-runtime/`. Debug APK SHA-256: `4f13f5ecbcbcf139901eb604d5c3573032829619264cd92f8f28fa968af8bef0`.
- S2 emulator runtime child slice is closed only. S2/S3–S6 physical/QUAL and parent I12/I13/I14–I25 inventory, integrated `tools/check_all.sh --rerun` and release APK, physical Xiaomi 14 Ultra cycle, independent review and owner acceptance remain. MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.
- Next selected by plan 5.459: I18 `PDF-CONTENT-ORACLE-01` within the existing I14–I20 package. First map the frozen S4 fixture and renderer/readback path, then preregister exact content-presence and content-loss cases before code edits; preserve the final full check/APK for the integrated parent candidate.

## Active child slice — plan revision 5.460

- Attempt 71 S2 runtime result is preserved and committed as `5ea31e91`; the emulator runtime child slice passes, with physical/QUAL and parent package gates still open.
- Attempt 72 is preregistered as a host-test-only I18 source-raster binding slice. Source audit found existing validator tests already cover blank, text-only, empty, unused, off-page, covered, malformed/readback and manifest-hash mismatch cases; those remain reused evidence. The gap is the lack of a pinned expected S4 source-page digest independent from the PDF fixture builder.
- Registered finite work: pin deterministic page-index-specific raster hashes for indices 1–20; validate source/manifest/PDF binding at P1/P5/P10/P20; reject a different valid page raster, erased detail and reordered source identities; rerun the existing content-loss controls. Plan 5.460 and audit §11.26 define scope and evidence target. No app or runner source/version change is planned. Baseline commit `5ea31e91`, app `.1.24`/25, runner 1.25, product `.219`/338.
- Next implementation acceptance: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-72-s4-source-raster-binding/` contains exact focused commands/environment, raw result, and verified hashes; full integrated host suite/APK remains reserved for the parent candidate. MVP stays `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation snapshot — 2026-09-29, plan revision 5.461

- Attempt 72 I18 host source-raster binding passed and is committed as `7b7691be`. Twenty page hashes are pinned; P1/P5/P10/P20 source-to-PDF/SaveManifest positives and source substitution/detail-loss/order negatives passed. Existing PDF adversarial controls and 12-run synthetic S4 package integration passed; exact logs/environment/checksums are in `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-72-s4-source-raster-binding/`. No app or runner change.
- Next preregistered package is I19 exact health-event deduplication, plan 5.461 / audit §11.27. Source inspection found duplicate `ProcessErrorStateInfo` / `ApplicationExitInfo` records are currently appended without identity deduplication, and the host oracle allows repeated event identity. Exact event identity, distinct-event preservation, positive/negative controls and version/evidence scope are registered before edits.
- Baseline: `7b7691be`, branch `experiment/pdf-scratch-output-close`, harness `.1.24`/25, runner 1.25, product `.219`/338. Physical S2/S3/S4 qualification, remaining I12/I13/I14–I25, integrated final check/APK, independent review and owner acceptance remain open. MVP stays `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.

## Current continuation snapshot — 2026-09-29, plan revision 5.462

- Attempt 73 I19 exact duplicate health-event source/consumer slice committed as `9e06f7fb` on isolated branch `experiment/pdf-scratch-output-close`. App harness `.1.25`/code26; runner1.25 and product `.219`/338 unchanged.
- S3 active/historical event arrays now suppress duplicates under source-specific identities; host aggregate validation rejects repeated identities. Distinct event timestamps and active-vs-historical same-PID rows are retained as distinct observations.
- Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-73-health-event-dedup/`; 49/49 aggregate contract pytest, 2/2 S3/S4 validator tests, focused instrumentation 1/1, compilation/assembly and static checks pass. Result and raw logs/checksums are recorded there.
- This closes only the exact duplicate cell, not injected ANR/OOM lifecycle, S2–S4 full health runtime, physical/QUAL health qualification, integrated full check/current signed APK, independent review or owner acceptance. Parent I01–I25 and MVP gates remain open; status `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.
- Next: inspect the active parent inventory and select the highest-priority remaining evidence-backed gap before edits. Do not spend another full configured check/APK on a child slice; reserve that for the integrated transfer candidate.

## Active child slice — plan revision 5.463

- Attempt 73 exact duplicate health-event source/consumer result is committed (`9e06f7fb`, evidence/docs `9f3b8670`); tree was clean at baseline.
- Attempt 74 preregisters the remaining host-verifiable I19 process-name scope gap: current host accepts any nonempty process name although the producer scopes events to DocScan. Exact accepted package/child process names and foreign/malformed negative matrix are in plan 5.463 / audit §11.28.
- Baseline `9f3b8670`, harness `.1.25`/26, runner1.25, product `.219`/338. Host-only expected; no version change. UID cannot be inferred from process name and remains a separate producer-side source boundary.
- After attempt 74, re-audit I19 remaining fields and the larger parent inventory; do not claim I19 or MVP fully accepted from a bounded host slice. Physical Xiaomi, integrated full check/current APK, independent review and owner acceptance remain open.

## Current continuation snapshot — 2026-09-30, plan revision 5.464

- Attempt 74 I19 host process-name scope closed in commit `eba0d167`, evidence/docs commit pending. App harness `.1.25`/26, runner 1.25, product `.219`/338 unchanged.
- Host S3/S4 health oracle accepts only the applicationId `com.example.docscanspike` and nonempty colon child process names; rejects unrelated package, false prefix and malformed names. 50/50 aggregate-contract tests; S3/S4 validators 2/2; Python compile, shell syntax and diff checks pass. Evidence: `execution/device_tests/ENG-B1B2-INVENTORY-01/attempt-74-health-process-scope/`.
- Boundary: process name is not independent UID proof. I19 remains open for complete app identity, observation failure/error behavior and source/aggregate linkage; no injected health event, physical/QUAL qualification, integrated full check/APK, independent review or owner acceptance.
- Resume by reading this file, the collaboration protocol/templates, `docs/09_MVP_EXECUTION_PLAN_FOR_AGENT.md` (revision 5.464), `AUDIT_INVARIANTS.md` §11.19 and §11.28, decisions, product task ledger, and latest evidence. Continue the same parent inventory; full check/current APK only after its integrated candidate is complete. MVP remains `IN_PROGRESS_NOT_ACCEPTED` / `REVIEW_REQUIRED`.
