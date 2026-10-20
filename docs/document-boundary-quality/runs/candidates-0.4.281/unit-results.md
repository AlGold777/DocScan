# Candidate v0.4.281 unit and compile run

Commands executed:

- `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --rerun-tasks` (first attempt failed only on two test-source nullable reflection casts; see `unit-and-androidtest-compile.log`)
- Same command after fixing those test-source casts; result `BUILD SUCCESSFUL` (see `unit-and-androidtest-compile-retry.log`).
- `python3 -m unittest tools.test_dbq_evaluate -v`; result `Ran 9 tests ... OK` (see `python-evaluator.log`).

JUnit XML totals from the successful Gradle run: core 184 tests / 0 failures / 0 errors / 0 skipped across 19 suites; app 75 tests / 0 failures / 0 errors / 0 skipped across 13 suites. AndroidTest Kotlin compilation succeeded. The subsequent filtered emulator instrumentation run is recorded in `final-results.md` and `instrumentation-emulator-retry.log`.

New app unit suite counts: `DocumentBoundaryCoordinatorV281Test` 4, `DocumentBoundaryGeometryTest` 6, `DocumentQuadFilterTest` 2, `LatestDocumentPublicationTest` 4.

Environment snapshot: OpenJDK 17; Android SDK `/Users/restart/Library/Android/sdk`; attached test target is AVD `DocScanArbitration_API_36_1`, `sdk_gphone64_arm64`, API 36, ABI `arm64-v8a`. `adb devices -l` listed only `emulator-5554`; physical qualification was deferred and no physical device was touched.
