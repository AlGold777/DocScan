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

## Lifecycle close regression and combined Python verification

After the source-reviewed lifecycle fix, the exact filter
`DocumentBoundaryLifecycleInstrumentedTest#destroyQueuesPipelineCloseBehindAnInFlightLazyInitialization`
passed 1/1 on the same emulator. It observed the real Activity destroy path queue cleanup behind the blocked initializer, then verified the trace end marker, archive summary, and both writer executor terminations. Log: `lifecycle-post-fix-retry.log`; XML: Gradle connected Android-test results.

The pre-fix attempts did not produce a valid resource-leak assertion result. The first exposed a test harness latch-order deadlock; the later attempt hit an unrelated `RejectedExecutionException` when the Activity's startup `refreshDestinationGrantStatus` callback raced `io.shutdown()`. These runs are preserved in `lifecycle-pre-fix*.log`; they are not reported as a demonstrated pre-fix pipeline leak. The harness was stabilized to wait for the destination control to become enabled before racing destroy, and to release the initializer after the analysis executor enters shutdown.

Combined Python command:

```sh
python3 -m unittest tools.test_dbq_evaluate tools.test_dbq_compare tools.test_dbq_replay_to_evaluator -v
```

Result: 30 passed, 0 failed. Log: `python30-final.log`.

## Legacy UI failures retained

The 282 native target filter deliberately excluded two previously recorded UI workflow failures:

- `ProductActivityCaptureAndReorderInstrumentedTest#selectedDeleteAndConfirmedClearUndoRestoreWorkspaceAsWholeBatches`: the clear-workspace control is present as a View but unattached to the Activity decor, and the visible workspace menu has no corresponding Clear action. It remains a failure; the test was not changed to click the unattached View. Evidence: `../restore-0.4.281/restore-results.md` and its recorded instrumentation output.
- `ProductActivityCaptureAndReorderInstrumentedTest#deletingAndClearingCancelNativeOcrAndUndoCanRestartIt`: the first attempt searched the Activity decor for the per-page delete action although production places it in `workspaceMenuDialog`; a test-only dialog-root adaptation still did not find the tagged action. The test stopped before delete/undo/clear/OCR cancellation could be verified. Evidence and raw output: `../restore-0.4.281/restore-results.md` and `../restore-0.4.281/integration-attempt-ocr-menu-retry/`.

These failures remain visible in the report and are not included in any all-regressions-pass claim.
