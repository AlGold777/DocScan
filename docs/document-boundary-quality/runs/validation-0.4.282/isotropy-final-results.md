# 0.4.282 isotropy verification

Date: 2026-10-20. No physical device used. The only connected target was `emulator-5554`, AVD `DocScanArbitration_API_36_1`, API 36, arm64.

## Unit tests and Android-test compile

Command:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --rerun-tasks --no-parallel --max-workers=1
```

Result: BUILD SUCCESSFUL. Core: 184 passed, 0 failed, 0 skipped. App JVM tests: 79 passed, 0 failed, 0 skipped. Android-test Kotlin compile succeeded. Full log: `isotropy-unit-compile-rerun.log`.

Added test-only regressions cover isotropic 5-pixel X/Y displacement in a 100x200 portrait viewport, invalid aspect ratios, and equal coordinator EMA response for 5-pixel X/Y translations. Existing square/default assertions were left unchanged. Test sources: `app/src/test/kotlin/app/docscan/android/QuadGeometryUtilsTest.kt` and `app/src/test/kotlin/app/docscan/android/DocumentLiveDetectionCoordinatorTest.kt`.

## Python evaluator/comparator

Command: `python3 -m unittest tools.test_dbq_evaluate tools.test_dbq_compare -v`

Result: 20 passed, 0 failed. Log: `python20-isotropy-final.log`.

## Native instrumentation

Targeted command:

```sh
./gradlew :app:connectedDebugAndroidTest --no-parallel --max-workers=1 \
  '-Pandroid.testInstrumentationRunnerArguments.class=app.docscan.android.DocumentBoundaryNativeInstrumentedTest,app.docscan.android.DocumentBoundaryRecorderInstrumentedTest,app.docscan.android.DocumentOverlayViewInstrumentedTest,app.docscan.android.DocumentCropProcessorInstrumentedTest,app.docscan.android.DocAlignerAndroidReferenceInstrumentedTest,app.docscan.android.DocumentCropPlanInstrumentedTest,app.docscan.android.ManualDocumentCropInstrumentedTest#perspectiveCropProducesSeparateBoundedBitmapAndLeavesSourceDimensionsUnchanged,app.docscan.android.ProductActivityCaptureAndReorderInstrumentedTest#validShutterProvidesOneLightFeedbackAndCommitsPage,app.docscan.android.DocumentBoundaryReplayInstrumentedTest'
```

Result: BUILD SUCCESSFUL; XML reports 31 tests, 30 passed, 0 failed, 1 skipped. The skipped test is `DocumentBoundaryReplayInstrumentedTest#realCorpusReplayOrExplicitNotRunSkip`: the optional real corpus is absent, so this is NOT_RUN, not PASS. Synthetic replay ran. Raw Gradle output: `native-isotropy-final.log`; XML is in Gradle connected Android-test results.

The target filter did not include two previously reported UI wiring failures. In an earlier combined run, the shutter-capture integration test timed out once; an isolated retry passed 1/1, and the post-isotropy 31-test run also passed it. The initial timeout is retained as an intermittent emulator camera/ImageReader observation; it is not erased by the passing repetitions.
