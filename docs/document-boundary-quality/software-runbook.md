# DBQ software and final qualification runbook

## Reproduce the preserved baseline

`e62a5b5` is the owner's complete starting tree. `8e27a1a` is the first common
safety implementation, version 0.4.280. Use a separate worktree, existing JDK/SDK,
and ignored `local.properties` pointing to the installed SDK. Signing values
belong only in user Gradle configuration. Never replace release signing with a
new debug key. Do not run Gradle against a tree another agent is mutating.

Tests and analysis are performed by GPT-6 Luna high. Commands:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --rerun-tasks
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.docscan.android.DocumentCropProcessorInstrumentedTest
./gradlew :app:assembleDebug :app:assembleRelease
```

Always select an emulator serial explicitly for current development checks.
Preserve XML, stdout, exit status, source manifest and APK hashes. Optional model
reference instructions are in `model-reference-harness.md`; missing local weights
must remain explicit skipped/NOT_RUN evidence, not PASS.

## Explicit local diagnostics (debug APK only)

Restart the debug Activity with `docscan.dbq.trace=true` to record JSONL in the
app's private `files/dbq/` directory. `docscan.dbq.frames=true` additionally records
at most 450 analysis samples, downscaled to 720 px, with exact resize metadata.
The frame writer holds at most two queued owned images; dropped samples are
counted. Use non-sensitive test documents. Export deliberately using local adb
`run-as`; nothing is uploaded automatically. These sampled analysis images do
not prove display presentation or full-resolution camera quality.

`docscan.dbq.backend=A` selects the bounded classical experiment only in debug;
`docscan.dbq.tracker=true` enables experimental optical flow. Release ignores
these extras. Default is original detector, no tracker or prediction. Backend
settings are captured once per Activity and do not change within a track/capture.
Force-stop/relaunch between configurations; do not mix their traces as one run.

Each trace includes generation/frame/geometry, candidates and raw/refined buffer
coordinates, status/deadline and received/detector times. Unknown sensor mapping,
UI presentation time and metrics without observations are unavailable. Full
sensor-to-present profiling and diagnostic overhead require the final physical
suite; no latency claims should be derived from detector time alone.

## Geometry evaluator

`tools/dbq_evaluate.py --gt GT.jsonl --predictions PRED.jsonl --output report.json`
accepts original-image pixel-centre quads. Each GT row contains `clipId`, `frameId`,
`group`, explicit `eligible`, and ordered four-point `quad`. Prediction keys match
GT; missing, invalid and crossed polygons count as failures. Duplicate IDs or
malformed GT stop evaluation. It reports original-pixel IoU, maximum cyclic corner
error, and endpoint normal side distances divided by GT short side. Acceptance
stays INCONCLUSIVE without the frozen independent corpus/CI/physical evidence.
Displayed jitter, confirmed precision and capture-to-present are not inferred.

## Final phone stage (after software implementation)

Keep `acceptance-config.json` targets unchanged. Assemble the pilot groups and
negative scenes from specification §17, annotate partial/occluded/out-of-frame
separately, include required-content masks and double annotation. Freeze corpus,
split, eligibility, statistical sampling/stopping plan and budgets before final
comparison. Record exact Xiaomi device/camera/Android tuple and APK signature/hash.
Then execute HW01–HW10, AC01–AC19, per-group A/B tables, ablations, cold/offline,
concurrent OCR/crop, 10-minute thermal/PSS and 30-minute negatives. Report failed
and unavailable criteria alongside successful checks; no production winner can
be qualified while selection inputs remain open.

## Rollback

Use a separate checkout of `e62a5b5` for the original product, or `8e27a1a` for the
common safety release. Preserve the active worktree and user data. A versionCode
rollback cannot be installed over a newer application with normal Android upgrade
rules; use an isolated device/profile or a higher-code rebuild. Never uninstall
and lose user data merely to exercise rollback. The initial dirty source archive
is supplementary; the complete initial state is now committed in e62a5b5.
