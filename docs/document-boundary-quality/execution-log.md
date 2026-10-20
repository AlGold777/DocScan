# DBQ execution log

## 2026-10-20 — G0

* Read the supplied specification as task material. The user's separate request
  authorized implementation; embedded historical authorization text was not used
  as an instruction on its own.
* Preserved the initial dirty source/docs snapshot outside the repository at
  `/Users/restart/Documents/GitHub/before-dbq-20261020-source`, associated with tag
  `before-dbq-20261020`. It deliberately excludes caches, APKs and local secrets.
* Created branch `codex/document-boundary-quality`.
* At the owner's explicit request an agent committed all existing changes as
  `e62a5b5`, including previously tracked generated files. The resulting tree was
  clean. Later implementation commits preserve this ownership boundary.
* Original version: 0.4.279/398. Version source: `app/build.gradle.kts`;
  README contains the current displayed version. Historical records stay intact.
* GPT-6 Luna high performs validation. Phone qualification deferred by owner until
  implementation is complete. No replacement of physical evidence by emulator.

## Evidence

See `baseline-manifest.json`, `benchmark-protocol.md`, and run folders. Hardware,
resource budgets and final corpus are not yet qualified.

## 0.4.280 — common live safety

* Added a 200 ms visual lease checked at UI submission and drawing, independent
  expiry callback and lifecycle cancellation; older publications cannot overwrite
  a newer state. Unknown sensor clocks remain unknown.
* Live/JPEG veto now requires explicit spatial and exposure-time proof. Current
  capture path has no such proof, so reliable still geometry stands independently.
* GPT-6 Luna high executed 184 core + 59 application unit tests with no failures;
  compiled AndroidTest and built signed debug/release APKs in an isolated matching
  source copy. APK metadata/signature/ABI/hash checks passed. See run `common-0.4.280`.
* Overlay instrumentation is INCONCLUSIVE: helper Activity setup/runner hung at
  0/3; stopped after bounded investigation. This is not evidence of phone or View
  presentation correctness. Original baseline native fixtures passed 3/3 on emulator.

## Owner direction and experimental provenance

* Owner explicitly moved all real-phone checks to after the full implementation.
  Emulator-only development runs were used in the meantime; no physical phone
  was connected or modified during these runs.
* LC100 and T8 FP32 references were reproduced by GPT-6 Luna high on the host and
  in an isolated API36 Android emulator harness. The upstream Capybara
  postprocess matched the independent OpenCV translation; Android reference
  tests passed 5/5. Exact pinned model hashes, commands, first failed runner
  attempt and successful repeats are preserved in `runs/models-reference/`.
* Both heatmap-only graphs produced a non-document graphic-panel lock. The
  reference adapter therefore stays AMBIGUOUS and does not authorize capture.
  Production does not contain model weights or ONNX Runtime; test artifacts are
  optional local inputs provisioned by an explicit hash-verifying script.

## 0.4.281 — implementation and development validation

* Added viewport-aware detector contracts, bounded A proposals, common Huber
  refinement, optical flow with geometric/inlier/coverage/reprojection gates,
  experimental time filters and exact resize/EXIF/letterbox transforms.
* Added a single replaceable UI publication, distinct measured association and
  semantic/visual timestamps, partial/error states, localized hints and bounded
  debug recording. Prediction is disabled; original backend remains default.
* Added bounded JPEG refinement and made still normalization match warp's pixel
  centres, preserving original publication and existing admission/storage/OCR
  invariants. The crop plan version is now v3.
* First software validation: forced core 184/184 and app 75/75 tests passed;
  AndroidTest compilation passed. Python geometric evaluator: 9/9. All were
  executed/analyzed by GPT-6 Luna high. Current native run details are recorded
  under `runs/candidates-0.4.281/`; final results are added after completion.
* First 19-case native attempt exposed two test-assertion mistakes (matrix
  coefficient count and an implementation-specific callback counter), retained
  in evidence. Test corrections preserve observable lease-expiry assertions;
  a repeated run is required before any native PASS claim.

* Corrected test-only assertions and repeated the same emulator filter: **19/19,
  zero skips**. Original crop thresholds remain unchanged. Signed debug/release
  0.4.281/code400 builds passed; signature, ABI, package and alias checks passed.
  See `runs/candidates-0.4.281/final-results.md` and `apk-artifact-checks.md`.

## 0.4.282 — replay and integration maintenance

* A restored `stage-dbq-software-0.4.281` checkout matched production fingerprints
  and rebuilt debug/release. Evidence is under `runs/restore-0.4.281/`.
* Broader existing integration tests exposed a legacy injected test contour that
  used fixed 610×865 coordinates even for a smaller thumbnail. Production detector
  output remains within W−1/H−1; no clamp was added. The dimension-relative test
  fixture and a real endpoint/warp regression passed 4/4 on emulator.
* Two older ProductActivity test paths assumed controls in the main decor although
  the current starting UI has a separate page dialog/unattached legacy clear view.
  They are retained as independently reported test/UI compatibility failures until
  verified through an actual visible path; no unrelated UI redesign is introduced.
* Added the common local-corpus replay harness missing from the first software
  stage. It cannot replace the still-absent real corpus or phone qualification.

* The 0.4.281 restored debug APK is byte-identical. Release differs only in AGP's
  `META-INF/version-control-info.textproto`; all DEX/resources/native entries and
  signing certificate match. No release bit-reproducibility claim is made.
* 0.4.282 adds explicit UI_APPLY and DRAW debug markers with copied screen quads;
  DRAW is not PRESENT. Common replay and paired session-bootstrap evaluation
  tooling fill preparation gaps before real data collection. The statistics
  harness passed 11 unit tests and a separately labelled metadata-only synthetic
  CLI run; it cannot auto-qualify a winner with missing gates.

* Corrected a concrete geometry defect found during C/R contract review: normalized
  X/Y distances were treated as a square even for a rectangular ViewPort/JPEG.
  Agreement now accepts the actual image aspect ratio, restores isotropic pixel
  distances, and keeps existing IoU/shift thresholds. Final regression runs follow
  this source change; earlier 0.4.282 logs are not relabelled as post-fix evidence.

* Lifecycle cleanup now queues the lazy-initialized ownership check behind in-flight
  analysis before executor shutdown, avoiding orphaned diagnostics/archive writers.
  The first regression harness attempts exposed harness compile/latch ordering
  issues and a separate existing startup/destroy callback rejection; those attempts
  are retained and do not count as a pre-fix demonstration.
