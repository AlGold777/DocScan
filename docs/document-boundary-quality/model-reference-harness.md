# DocAligner Android reference harness

This is a test-only FP32 CPU reference harness for the pinned LC100 and FastViT
T8 ONNX heatmap models. The Android adapter and instrumentation tests live in
`app/src/androidTest/kotlin/app/docscan/android/`. ONNX Runtime Android 1.23.2
is declared only as `androidTestImplementation`, so the production APK does not
include its runtime or candidate model files.

The adapter accepts the boundary contract's borrowed full-buffer RGBA
`CV_8UC4` Mat, crops with `input.spec.cropRect`, converts RGBA to BGR, performs
bilinear 256×256 resize and NCHW FP32 `/255`, then reproduces upstream heatmap
postprocessing. It SHA-checks each model, releases all temporary Mats before
returning, and preserves nullable per-channel raw centroid slots. The returned
pixel-index centroid coordinates include the crop origin. The application
boundary transform owns any pixel-center convention.

Both tested ONNX graphs expose only `img` and `heatmap`, with no
document-presence output. The adapter therefore never returns `FOUND` or
`NO_DOCUMENT`; results remain `AMBIGUOUS`, even when all four heatmap centroids
exist. The fixture suite includes a graphic panel that is not a document but
produces a four-corner lock. A future production detector would need a separately
validated presence/side-evidence policy and representative real capture corpus.

The instrumentation methods cover: the upstream run-test card and raw-corner
parity, featureless negatives, the graphic-panel false-lock, LC100/T8 behavior
on generated paper, and viewport crop offsets. Pixel parity tolerances are
3–4 px across host and Android OpenCV builds. These tests are reference checks,
not quality gates and not Android-device equivalence evidence. The API 36
emulator result recorded in `runs/models-reference/android/` is a historical
run of the same reference method from the isolated test copy before transfer to
the version 0.4.281 main tree; it is not a run of the final main-tree source.

## Optional local assets

Model and image assets are deliberately ignored by Git. If any are absent, all
five instrumentation tests use JUnit `Assume` and report as **skipped**; a
skip is not a pass. To fetch the two pinned upstream legacy model files and the
test-card JPEG, then generate the deterministic PNG fixtures, run the explicit
commands below from the repository root:

```sh
/tmp/docscan-dbq-model-env/bin/python tools/fetch_docaligner_reference_assets.py --fetch --generate-fixtures
shasum -a 256 app/src/androidTest/assets/models/lc100.onnx app/src/androidTest/assets/models/t8.onnx app/src/androidTest/assets/docaligner_run_test_card.jpg app/src/androidTest/assets/docaligner_run_test_card.png
```

`--fetch` is explicit and downloads only the Drive IDs embedded in the pinned
upstream `infer.py` and the test card from the pinned upstream Git revision.
SHA-256 values are verified before files are written. `--generate-fixtures`
requires NumPy/OpenCV; use the pinned reference venv shown above. The asset
directory patterns in `.gitignore` cover these exact files. No fetch is wired
into Gradle, a build, or app runtime.

## Run

After populating the optional assets, target one selected emulator/device and
run this focused instrumentation class:

```sh
./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.docscan.android.DocAlignerAndroidReferenceInstrumentedTest
```

The `androidTestImplementation` dependency is pinned to
`com.microsoft.onnxruntime:onnxruntime-android:1.23.2`. Test-only model
hashes and the earlier isolated run's raw results are retained under
`runs/models-reference/`. Exact legacy model-weight license scope remains
unresolved; these files are not committed and no commercial terms are accepted
by this harness.
