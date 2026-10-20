# Document-boundary corpus replay

`DocumentBoundaryReplayInstrumentedTest` replays an explicitly supplied local
frame corpus through the current production `DocumentBoundaryPipeline`,
`DocumentLiveDetectionCoordinator`, and selected production detector. The
replay engine and tests are AndroidTest-only. They do not change production
selection, inference, tracking, UI, or capture behavior.

The harness runs every clip in manifest order for every configured variant,
with a new coordinator and pipeline for each variant/clip pair. It reopens the
same canonical input files for each variant, verifies input SHA-256 before and
after replay, never resizes a frame, caps decoded frames at 4,000,000 pixels,
and releases each borrowed RGBA `CV_8UC4` Mat before advancing. Image rotation
and crop metadata are passed to `AnalysisBufferSpec`; any metadata change
passes through `generationForFrame` and advances geometry/generation state.

## Local input and schema

By default, place a private `manifest.json` under
`<application filesDir>/dbq-replay/manifest.json`. An instrumentation argument
can select a different local path with
`dbq.replay.manifest=/absolute/path/to/manifest.json`. The manifest parent is
the root for frame files; every `file` must be relative and resolve canonically
inside that directory. Inputs are never copied or uploaded by this harness.

Schema version 1 has `variants` and `clips`. Frame IDs and timestamps must be
unique and strictly increasing inside each clip; timestamps are virtual
monotonic receipt times in milliseconds, not sensor, capture, or present
timestamps. `eligible` is retained as a labeling field; all frames are still
replayed in order so that temporal behavior is preserved. `quad`, when
present, is eight flat pixel-center coordinates ordered `(x1,y1,...,x4,y4)` and
is recorded as a reference label only. `cropRect` uses decoded full-buffer
pixel boundaries (`left`, `top`, `right`, `bottom`).

```json
{
  "schemaVersion": 1,
  "variants": [
    {"id": "original", "backend": "original", "tracking": false},
    {"id": "A", "backend": "A", "tracking": true},
    {"id": "B-lc100", "backend": "B_LC100", "tracking": false},
    {"id": "B-t8", "backend": "B_T8", "tracking": false}
  ],
  "clips": [{
    "clipId": "scan-001",
    "group": "real-corpus-local",
    "frames": [{
      "frameId": 1,
      "timestampMs": 0,
      "file": "scan-001/frame-0001.jpg",
      "rotationDegrees": 0,
      "cropRect": {"left": 0, "top": 0, "right": 1280, "bottom": 720},
      "eligible": true,
      "quad": [120, 80, 1150, 90, 1140, 650, 130, 640]
    }]
  }]
}
```

Supported backends are `original`, `A`, `B_LC100`, and `B_T8`. The B models
are optional local test assets. If the corpus manifest is absent, the real
corpus test writes a `NOT_RUN` report and JUnit marks that test **skipped**.
If the manifest requires a missing B model, the test is likewise skipped with
a `NOT_RUN` report. A skip is not a passing corpus result. The separate
synthetic smoke test still executes with generated five-frame inputs and
`original`/`A` variants; its assertions cover output row count, JSON parsing,
identity/order, and unchanged input hashes only. It makes no geometry-quality
assertion.

Fetch pinned LC100/T8 and the upstream smoke fixture only with the explicit
test-asset command documented in
[`model-reference-harness.md`](model-reference-harness.md). Model files remain
ignored local test assets, are SHA-checked by the test adapter, and are not
fetched by Gradle or app runtime. The exact legacy weight-license scope remains
unresolved; replay does not change that status.

## Outputs and interpretation

Reports are written only to
`<application filesDir>/dbq-replay-results/run-<id>/`. A run has a summary
`run.json` and one JSONL file per variant/clip. Summary metadata records the
source manifest SHA, APK version, Android device/API/ABI tuple, detector/config
IDs, and exact B model hashes. Per-frame rows retain source hash, frame and
geometry identities, virtual receipt time/domain, whether inference actually
ran, actual published status, confirmed/held flags, track ID, published quad,
detection/visual/deadline timestamps, and raw/refined detector candidates only
on detector calls. Published quad values are converted from buffer boundary
coordinates to original full-buffer pixel-center coordinates by subtracting
0.5. A real detector runtime in nanoseconds is included only for scheduled
detector calls; missing timings remain absent/null rather than invented zeroes.
For B variants, each scheduled call also records the four nullable raw heatmap
centroids, even when they cannot form a valid candidate quad.

`filterGeometryOnly`, when present, contains independent `LEGACY_EMA`,
`TIME_EMA`, and `ONE_EURO` outputs updated from a new `FOUND` detector
observation's raw measured quad. Each filter resets for each clip. It is omitted
on misses and ambiguous outcomes, never filled by interpolation, and never
replaces pipeline/coordinator published geometry.

The B heatmap adapter returns `AMBIGUOUS` and does not invent document presence
or `NO_DOCUMENT`. Every replay result is diagnostic data, not a winner
selection, acceptance threshold, representative-corpus quality claim, Android
equivalence claim, or physical-device result. Virtual replay timestamps drive
coordinator/pipeline state transitions; actual backend runtime uses measured
`elapsedRealtimeNanos` and is not synthesized from the replay clock.

## Run

The test class is focused and can be invoked on one explicitly selected AVD:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon --console=plain \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.docscan.android.DocumentBoundaryReplayInstrumentedTest
```

To select a manifest by instrumentation argument, add:

```sh
-Pandroid.testInstrumentationRunnerArguments.dbq.replay.manifest=/absolute/path/to/manifest.json
```

The API 36 emulator, if used, is an emulator-only execution environment. It
does not stand in for a physical camera or a real capture corpus.

## Convert completed runs for geometric evaluation

`tools/dbq_replay_to_evaluator.py` (Python 3.10+) converts a completed run directory into one
shared evaluator GT JSONL and one prediction JSONL per variant. Conversion
requires both the replay output directory and the exact source `manifest.json`;
`run.json` and every JSONL row must carry the same source manifest SHA as that
file. A `NOT_RUN` report, missing source manifest, missing variant/clip file,
duplicate frame, changed label, mismatched source-manifest SHA, or inconsistent
per-frame input SHA across variants is an error.

```sh
python3 tools/dbq_replay_to_evaluator.py \
  --input-dir /path/to/run-<id> \
  --manifest /path/to/manifest.json \
  --output-dir /path/to/evaluator-inputs

python3 tools/dbq_evaluate.py \
  --gt /path/to/evaluator-inputs/gt.jsonl \
  --predictions /path/to/evaluator-inputs/predictions-A.jsonl \
  --output /path/to/evaluator-inputs/report-A.json
```

Repeat the evaluator command for each `predictions-<variantId>.jsonl`. GT rows
use the source manifest's labels and flat reference quad converted to four
`[x,y]` points. Prediction rows use only `publishedQuadPixelCentres`; raw or
refined candidates are never substituted. A null published quad remains a
missing prediction. The conversion report records source manifest, run
metadata, per-variant JSONL, frame-input, and output hashes.

The comparer also needs a separately prepared cluster manifest mapping every
clip to an explicitly assigned `sessionId` and group. Conversion does not
create sessions, claim corpus independence, or synthesize hardware, latency,
preview, or UI-quality results. The resulting reports are geometric replay
inputs only.
