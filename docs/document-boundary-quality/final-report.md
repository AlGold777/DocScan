# DBQ delivery report — software work, 2026-10-20

**Full DBQ qualification is not complete.** The common live chain, classical
experiment, tracker/refiner/filter components and test-only ML references are
implemented. The original engine remains production default. Selecting and
qualifying a replacement requires the real corpus and final phone stage, deferred
by the owner's explicit instruction. This report does not claim AC01–AC19 PASS.

## What changed

CameraX, OpenCV, the original detector, manual shutter, JPEG publication, storage,
OCR/PDF engines and version-bound edits are preserved. Live publication now has
200 ms expiry checked at UI apply and draw, a timer without new frames, latest-only
UI dispatch, generation/layout invalidation and sequential native ownership.
Measured association is separate from filtered output. Experimental optical flow
checks feature coverage, forward/backward, RANSAC inliers and reprojection; it
cannot confirm an initial/new target or renew semantic detection time. Tracking
is HELD, limited by the last real detection +500 ms. Prediction is disabled.

Detector A retains bounded alternatives from contours and rotation-independent
line pairs, validates each side and uses normal-gradient/Huber refinement with
rollback. It is an explicit debug option. The three filter modes are available
for comparison; no claim of better physical jitter/lag has been made.

The JPEG supplies the crop. It is saved unchanged first, then coarse geometry can
be refined on an oriented ≤2 MP/1600 px image under a separate 32 MiB working-set
admission. Pixel-centre normalization matches warp. Failed refinement/processing
preserves the original through the existing capture flow. An unknown JPEG exposure
or merely matching aspect ratio cannot authorize live veto.

Debug trace and optional bounded image recording are local, explicit opt-in,
disabled in production. The geometric evaluator counts missing/invalid predictions
as failures and refuses to assign acceptance from absent physical/statistical data.

## Executed evidence

All test execution and analysis were performed by **GPT-6 Luna, high**.

| Suite / variant | Executed result | Scope |
| --- | --- | --- |
| Original e62a5b5 / 0.4.279 | 184 core +48 app unit tests; 3 native methods containing historical 11 fixtures | JVM + API36 emulator, not phone quality |
| Common safety / 0.4.280 | 184+59 unit tests, AndroidTest compilation, signed debug/release APK checks | Old overlay runner INCONCLUSIVE; superseded by final observable tests |
| Candidate/common / 0.4.281 | 184+75 unit tests, zero failures/errors/skips; AndroidTest compilation | Pure geometry, states, timing, publication queue, filters |
| Python evaluator | 9/9 | Identity/overlap/cyclic/winding/invalid/missing/denominator logic |
| Final filtered native suite | 19/19, zero skipped | Refinement, synthetic flow and target handover, bounded recorders, EXIF/read-only still, overlay lease, original fixtures, five ORT reference tests |
| LC100/T8 host reference | Postprocess parity to Capybara ≤1e−9 px on eight fixtures | Generated images and upstream card; not real corpus |
| LC100/T8 isolated Android | 5/5; repeated full success after an initial runner failure | FP32 CPU on one emulator; final tree rechecked within 19-method suite |

Failed attempts and corrected test assertions remain in the raw run folders.
Historical fixtures keep their original thresholds: passing their tests does not
establish the new IoU 0.97 goal (including the historical ~0.9687 receipt result).

## A/B comparison and decision

See [decision record](decision-record.md). There is no comparable real-camera A/B
quality table yet; those cells remain NOT_RUN. Both heatmap-only checkpoints
produced a full candidate on a non-document graphic panel. The reference adapter
therefore returns AMBIGUOUS; four points alone never confirm a document. These
legacy files also lack an explicit link between exact hashes and weight-license
scope. Weights stay ignored/local and absent from production; ORT is AndroidTest-only.

Jitter, lag, actual preview alignment, sensor-to-present, crop required-content
loss, process PSS, cold start, thermal and energy remain **NOT_RUN** for a physical
tuple. The 32 MiB admission is a working-set estimate, not measured process PSS.
No corpus/split/annotation hash or qualified Xiaomi tuple is invented. Selection
and resource budgets remain OPEN; no thresholds were relaxed.

## Reproduction and remaining work

* Source baseline: [manifest](baseline-manifest.json); original commit e62a5b5.
* Commands, rollback and debug flags: [runbook](software-runbook.md).
* Targets: [acceptance config](acceptance-config.json); [traceability](requirements-traceability.md).
* Evidence: [original](runs/baseline-0.4.279/), [common 0.4.280](runs/common-0.4.280/),
  [candidates 0.4.281](runs/candidates-0.4.281/), [model references](runs/models-reference/).
* Code/model attribution: [provenance](THIRD_PARTY_MODELS.md).

G0 is preserved and recoverable. G1's software baseline is measured; its real data,
resource budgets and final freeze remain open. G2 has executed development tests;
physical temporal/projection evidence remains open. G3 prototypes are runnable;
model presence policy and artifact-license qualification remain unresolved.
G4 has no qualified selection. G5 includes common UI/still integration; switching
the production engine is gated on G4. G6 requires the final physical suite and
statistical evidence. These are explicit remaining requirements, not software PASS.

## APK artifacts

Package `app.docscan.scanner`, version 0.4.281, versionCode 400, signatures verified.
Release ABI arm64-v8a; debug includes all four supported ABIs. Aliases are byte-identical.

* `dist/DocScan-release-0.4.281.apk` — SHA-256 `e0ef9dae1639435ec26ad719a295efbbf822ddc08271a5a1cea35b925ead06b2`
* `dist/DocScan-debug-0.4.281.apk` — SHA-256 `ca89ffc7d9910e5922014479eed7b07cc70f91b2af980e2c186773e440937ce4`

The previous source and common-safety states remain committed. Final source-tag
restore/build evidence is recorded separately. No push or physical-device operation
was performed.
