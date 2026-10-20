# Boundary pipeline lifecycle regression

`ProductActivity` owns `cameraAnalysisExecutor` and a synchronized lazy
`documentBoundaryPipelineDelegate`. The pipeline may own background writers
through `DocumentBoundaryDiagnostics` and `DocumentFrameArchive`; `close()` on
the pipeline closes both of those owners.

## Race and consequence

The previous `onDestroy()` sequence tested
`documentBoundaryPipelineDelegate.isInitialized()` on the main thread before
submitting a close task. Kotlin's synchronized lazy initializer can be running
on `cameraAnalysisExecutor` while the delegate still reports uninitialized.
If `onDestroy()` observes that state, it skips the close task and then shuts
down the executor. The already-running initializer is allowed to finish, but
its returned pipeline has no remaining lifecycle owner. Its diagnostics and
frame-archive writers can continue running and hold their resources beyond
Activity destruction.

The fix enqueues an unconditional
cleanup task before shutting down the analysis executor, and performs the
`isInitialized()` check inside that queued task. A synchronized-lazy
initializer already ahead of that task must finish first; the cleanup task can
then observe the initialized delegate and close the resulting pipeline. This
ordering is now implemented in production; runtime test evidence is recorded
separately under `runs/validation-0.4.282/`.

## Instrumented regression test

`DocumentBoundaryLifecycleInstrumentedTest`
`destroyQueuesPipelineCloseBehindAnInFlightLazyInitialization` replaces only
the test Activity's private lazy delegate with a latch-controlled initializer.
It invokes that delegate on the real `cameraAnalysisExecutor`, waits until the
initializer is blocked, then closes `ActivityScenario` to execute the real
`ProductActivity.onDestroy()` path. After releasing initialization, it waits
for the actual executor queue to drain and checks observable resource cleanup:
the diagnostics `end` marker, the frame archive final summary, and termination
of both writer executors. The archive is enabled but receives no frames, so
the test exercises lifecycle ownership without opening or depending on a
camera. Its `finally` cleanup releases latches and closes any constructed
writers even when the regression is present and the assertions fail.

The validation agent executed the stabilized post-fix test on emulator-5554:
1/1 PASS, no skips, observing both writer terminations and final records.
Initial pre-fix harness attempts did not establish the desired runtime assertion;
their compile, latch and separate startup-race failures remain in the logs. It is a deterministic lifecycle regression check,
not a camera, quality, or performance test. The reflection is limited to
private fields on the app's own Activity and writer classes; it uses no hidden
OS APIs.
