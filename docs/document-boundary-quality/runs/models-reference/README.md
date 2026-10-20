# DocAligner FP32 host reference

Run date: 2026-10-20. This is a host reference observation on one public upstream
fixture and generated synthetic images. It is not a camera-corpus score or an
acceptance/quality gate. A separate isolated API 36 emulator reference run
passed five instrumentation methods; its raw results are preserved under
[`android/`](android/). That historical run used the then-isolated test copy,
not the now-transferred version 0.4.281 main-tree test sources. The maintained
test-only harness is documented in
[`model-reference-harness.md`](../../model-reference-harness.md).

## Inputs and source

- Upstream checkout: `/tmp/docscan-docaligner-3275b0f`, clean at
  `3275b0f07f8e99d8c01cb0774dea2549be1416b6`.
- Exact preprocessing and postprocessing source:
  [`heatmap_reg/infer.py` at the pinned revision](https://github.com/DocsaidLab/DocAligner/blob/3275b0f07f8e99d8c01cb0774dea2549be1416b6/docaligner/heatmap_reg/infer.py).
- Fixture: upstream `docs/run_test_card.jpg` from that checkout.
- Local models: `/tmp/docscan-lc100.onnx`, `/tmp/docscan-t8.onnx`; hashes and
  byte lengths are in [`reference-results.json`](reference-results.json) and
  [`models.sha256`](models.sha256). The model files were kept outside the
  repository and were not added to app assets.

## Reproduce

The working directory was Python 3.12.12 in
`/tmp/docscan-dbq-model-env`. A `uv pip freeze` snapshot is stored beside this
file. The environment was created and populated with:

```sh
uv venv --python 3.12 /tmp/docscan-dbq-model-env
uv pip install --python /tmp/docscan-dbq-model-env/bin/python onnxruntime onnx opencv-python-headless capybara-docsaid
uv pip install --python /tmp/docscan-dbq-model-env/bin/python 'capybara-docsaid<1.0.0'
cd /Users/restart/Documents/GitHub/DocScan
/tmp/docscan-dbq-model-env/bin/python docs/document-boundary-quality/runs/models-reference/run_reference.py
```

The active session provider was `CPUExecutionProvider` for both models. Installed
reference versions: NumPy 2.5.4, ONNX 1.23.2, ONNX Runtime 1.22.0, OpenCV
5.0.0.93, and capybara-docsaid 0.12.0. The exact full environment is listed in
`requirements.freeze.txt`.

## Graph and image contract

Both ONNX files are IR v8/opset 16, FP32, take `img` as NCHW `[1,3,256,256]`
and expose only `heatmap`, with runtime shape `[1,4,128,128]`. LC100 has a fixed
batch dimension; T8 declares a dynamic batch dimension in its graph. Neither
graph has a classification/presence output or graph entries named for a
classification, presence, `has_obj`, objectness, or `cls` head. The separate
upstream point-regression implementation has `has_obj`; it is not the LC100 or
T8 heatmap model tested here.

Input is a BGR HWC image, directly resized (no aspect-preserving pad or center
crop) to 256×256 with bilinear interpolation, converted to float32 CHW and
divided by 255. This follows the upstream example's RGB-to-BGR conversion and
`heatmap_reg/infer.py` preprocessing.

Postprocessing was reproduced with OpenCV and compared against capybara-docsaid
0.12.0 calls used by upstream. For each output channel: resize the heatmap to
the source dimensions with bilinear interpolation, zero values below 0.3,
convert to uint8 by truncation after multiplying by 255, use
`THRESH_BINARY | THRESH_OTSU`, retrieve external contours with
`CHAIN_APPROX_SIMPLE`, drop contours with fewer than three vertices, choose the
contour with largest `cv2.moments().m00`, and return
`m10/(m00+1e-5), m01/(m00+1e-5)`. Every channel's returned centroid matched the
capybara path within 1e-9 pixel on every fixture. This establishes host
postprocess equivalence only.

## Observations

The model output and raw channel corners are preserved in `reference-results.json`.

| Fixture | LC100 | FastViT T8 |
| --- | --- | --- |
| Upstream run-test card | 4 corners | 4 corners |
| Uniform white, black, gray; low-contrast noise; checker clutter | no centroids | no centroids |
| Generated synthetic paper on gray background | 4 corners | no centroids |
| Generated graphic panel (explicitly not a document) | 4-corner lock | 4-corner lock |

The geometric panel is a direct false-lock example: neither candidate can
distinguish a document from a similarly shaped graphic from heatmaps alone.
Featureless negatives happened to return no centroids, but the graph cannot
produce a calibrated `NO_DOCUMENT` result. A production policy would need
additional independent presence/edge evidence and its own validated corpus.
The LC100/T8 difference on the generated paper also shows why these synthetic
fixtures cannot qualify a winner.

## Isolated Android FP32 parity

A test-only adapter and five instrumentation tests were run from the separate
copy `/tmp/docscan-dbq-model-android-tests`, on explicitly selected
`emulator-5554` (DocScanArbitration_API_36_1, API 36). No Android source, runtime,
or weights were added to the production checkout. The temporary test copy pins
`com.microsoft.onnxruntime:onnxruntime-android:1.23.2`; test-only model hashes
are in `android/androidtest-assets.sha256`. The runner converts RGBA to BGR,
uses bilinear resize and CHW float32 `/255`, then runs the same heatmap
threshold, uint8 truncation, Otsu, contour and centroid steps as the host
reference. Returned raw points use pixel-index coordinates, with the viewport
origin restored; the application-owned transform remains responsible for any
pixel-center convention.

Exact command (with the local JDK 17 and Android SDK paths used for this run):

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin:$PATH \
ANDROID_HOME=/Users/restart/Library/Android/sdk \
ANDROID_SDK_ROOT=/Users/restart/Library/Android/sdk \
ANDROID_SERIAL=emulator-5554 \
./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.docscan.android.DocAlignerAndroidReferenceInstrumentedTest
```

All five instrumentation tests passed. The test card's raw centroids match the
host reference to the printed float precision for both models; featureless
negatives stay empty; the graphic panel produces heatmap corners for both
models; LC100 detects the synthetic paper while T8 does not; and cropped input
restores full-buffer coordinates. All results remain `AMBIGUOUS`: this
heatmap-only graph has no document-presence head, so parity does not authorize
`FOUND` or `NO_DOCUMENT`. This is a single emulator FP32 CPU reference run,
not Android-device equivalence, a production quality gate, or a camera-corpus
result. The first combined invocation had a transient instrumentation process
failure; a focused rerun passed, followed by the complete successful suite
captured below.

- `android/instrumentation-results.xml`: final five-test result.
- `android/android-reference-results.logcat.txt`: raw per-model/per-fixture
  corners and graph shapes captured from the test process.
- `android/android-reference-gradle.log` and `.exit`: complete final command
  output and exit code.
- `android/androidtest-assets.sha256`: temporary instrumentation model/image
  asset hashes.

## Licensing evidence and unresolved scope

The pinned source repository includes an Apache-2.0 `LICENSE`:
[LICENSE at 3275b0f](https://github.com/DocsaidLab/DocAligner/blob/3275b0f07f8e99d8c01cb0774dea2549be1416b6/LICENSE).
This establishes the repository's code license. The pinned `infer.py` maps
LC100 and T8 to separate Google Drive IDs
(`1IlbLPkCv-TdaBLOPh_4J97P1_KHYYJ7a` and
`1W88oOkHjMza6xXbDxrG8k_C-Tf2aYKJ4`), but neither the pinned source nor the
downloaded ONNX metadata attaches a weight-specific license or attribution to
those exact files.

The publisher's current official model catalog, checked 2026-10-20, states:
“已发布的开源代码与官方模型权重可免费使用，包括商业产品” (“released
open-source code and official model weights may be used free of charge,
including in commercial products”). It labels its selected current download
`docaligner-1.1.1-fastvit-sa24-7f9f5a89` Apache-2.0 and shows a SHA-256 for that
artifact: [official model catalog](https://docsaid.org/zh-hans/models/docaligner).
That selected model artifact is not either exact legacy LC100/T8 file/hash in
this run. The general publisher statement is relevant evidence, but its scope
for the two exact legacy Drive artifacts is not established here. Treat legacy
weights as a production-distribution blocker until the license/provenance for
those exact IDs or hashes is explicitly documented. No vendor contact was
made, and no commercial terms are accepted by this reference run.

## Evidence files

- `reference-results.json`: graph metadata, model hashes, runtime versions,
  fixture dimensions, full raw corners, output activation summaries and
  per-run provider names.
- `run_reference.py`: the reproducible host runner and OpenCV/capybara parity
  assertion.
- `requirements.freeze.txt`: exact resolved Python environment.
- `models.sha256`, `upstream-revision.txt`, `run.stderr.log`, `run.stdout.json`:
  file/source provenance and captured command output.
