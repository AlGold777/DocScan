# Model and runtime provenance

DocAligner source: DOCSAID Lab, repository
https://github.com/DocsaidLab/DocAligner, pinned revision
`3275b0f07f8e99d8c01cb0774dea2549be1416b6`. The independent AndroidTest
translation follows `docaligner/heatmap_reg/infer.py` preprocessing and
postprocessing. Source license: [Apache-2.0](licenses/DocAligner-Apache-2.0.txt).
The source license is not asserted to establish rights to exact legacy weights.

| Candidate artifact | Bytes | SHA-256 | Location / distribution |
| --- | --- | --- | --- |
| LC100 legacy heatmap FP32 | 4767987 | f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e | Optional local AndroidTest fixture; absent from production APK/Git |
| T8 legacy heatmap FP32 | 13167814 | 950070fd4a46e25d46db7c35d0ed77612d9063f70d4543c7c504f0da6a943a89 | Same |

Exact metadata and byte lengths: `runs/models-reference/reference-results.json`.
The explicit downloader verifies pinned SHA-256 and never runs from the app,
Gradle configuration or an ordinary build. Do not redistribute the optional test
models until the legacy artifacts' license scope is established. No vendor was
contacted and no commercial terms were accepted.

Android test runtime is `com.microsoft.onnxruntime:onnxruntime-android:1.23.2`
(MIT), AndroidTest configuration only. CPU FP32, two intra-op threads; no delegate,
quantization or claimed Android-wide numerical equivalence. Upstream host Python
versions and exact freeze are archived in `runs/models-reference/`.

Both models expose only `[1,4,128,128]` heatmaps from BGR CHW `/255` input. Their
reference adapters return AMBIGUOUS and retain raw candidates. The observed
non-document graphic-panel lock is retained as a counterexample; output shape
or reference parity does not demonstrate document presence.
