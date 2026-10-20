# Final v0.4.281 validation result

PASS — host tests: `:core:test` 184/184 and `:app:testDebugUnitTest` 75/75; 0 failures, 0 errors, 0 skipped.
PASS — `:app:compileDebugAndroidTestKotlin`.
PASS — Python evaluator: 9/9.
PASS — filtered emulator instrumentation: 19/19, 0 skipped, on `DocScanArbitration_API_36_1` (AVD, API 36, arm64-v8a). See `instrumentation-emulator-retry.log` and raw JUnit XML under app/build/outputs/androidTest-results/connected/debug.
PASS — existing near-full-page and long-receipt fixtures: original threshold assertions unchanged; instrumented fixture case executed on the AVD.
PASS — `:app:assembleDebug :app:assembleRelease`; versionName 0.4.281, versionCode 400; APK aliases verified byte-identical. See `apk-artifact-checks.md`.

The first filtered instrumentation attempt had two test-only assertion failures: expected 36 instead of 9 transform coefficients, and an overstrict redraw callback count. Those assertions were corrected; the second filtered run passed. No production defect or crash stack trace was observed.

No physical device qualification was performed; parent requested all physical checks deferred.
