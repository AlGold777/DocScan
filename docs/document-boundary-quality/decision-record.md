# DBQ engine decision — provisional, 2026-10-20

**Selection verdict: INCONCLUSIVE. No qualified winner.** This record preserves
§22, including its prohibition on declaring a winner without comparable evidence.
Physical checks are scheduled after implementation by the owner's direction.

| Variant | Implemented / executed evidence | Qualification |
| --- | --- | --- |
| Original 0.4.279 | Forced 184+48 unit tests; 3 native fixture tests on API36 emulator; fixed source commit e62a5b5 | Real camera quality and resources NOT_RUN |
| Original + common chain 0.4.280 | Forced 184+59 unit tests, signed APKs; deadline/callback/capture-policy regressions | Overlay instrumentation INCONCLUSIVE; hardware NOT_RUN |
| A + common chain | Bounded contour/rotation-independent line hypotheses, per-side gradient evidence and Huber refinement; runnable debug backend | Software native/refiner/tracker regressions PASS within 19-method emulator suite; real-corpus comparison NOT_RUN |
| B-LC100 FP32 CPU | Upstream host postprocess parity and Android test-only reference | Heatmap-only; observed non-document panel false lock; not eligible for production |
| B-T8 FP32 CPU | Same reference harness, exact pinned artifact | Same false lock; misses one generated paper found by LC100; not eligible for production |
| Tracker / filters / refinement ablations | Independently runnable components, prediction disabled | Camera jitter/lag and sufficient final corpus NOT_RUN |

Reference model evidence is in `runs/models-reference/`. Both legacy checkpoint
files have only four heatmap channels, without an object-presence head. Reference
adapters deliberately return AMBIGUOUS and expose raw points for comparison. Four
corners alone cannot authorize a confirmed outline or automatic crop.

The upstream code is Apache-2.0. The official catalog's broad weight-use wording
and the exact legacy Drive artifact hashes have not been explicitly linked by an
artifact license. Weights remain local optional AndroidTest fixtures, excluded
from Git and production APKs; ONNX Runtime 1.23.2 is AndroidTest-only. No vendor
messages, commercial agreements or network/GMS requirement were introduced.

Production uses the original detector behind the common timing/viewport/ownership
contract. A and tracker are explicit debug experiments, not an automatic fallback
whose scores are compared across engines. Prediction stays disabled because
preview presentation time has not been measured. JPEG still refinement is bounded
and preserves the original; live disagreement only vetoes with proven FOV/exposure
alignment, which the current capture path does not claim.

Before a production engine switch: collect/split/annotate the real corpus, freeze
resource budgets and configs, run the common evaluator and paired clip/session
statistics, and apply the unchanged §22 rule. Neither reference parity nor a
synthetic fixture percentage substitutes for those steps. Final qualification,
thermal/energy, PSS and physical overlay alignment remain open.
