# Paired DBQ statistics harness

`tools/dbq_compare.py` compares two reports produced by `tools/dbq_evaluate.py`. It implements the paired geometric replay portion of DBQ-M01 and DBQ-SEL01; it does not evaluate the complete acceptance criteria and cannot select a production winner.

## Inputs

The evaluator report must use `schemaVersion: 1` and contain `inputSha256.gt`, `groups`, and `frames`. The evaluator emits one `frames` entry per eligible `(clipId, frameId)` with the evaluator's boolean `success`, plus `reason` (`null`, `missing`, or `invalid`). Missing predictions and invalid geometry therefore remain failures through the evaluator's success bit. Group summaries must include `eligible`, `success`, `missing`, `invalid`, and `clips`. The comparer checks those counts against frame rows and the cluster manifest.

The required cluster manifest is JSON with this shape:

```json
{
  "schemaVersion": 1,
  "sourceGtSha256": "<64 lowercase hex characters>",
  "ordinaryGroups": ["ordinary"],
  "clips": {
    "clip-001": {"sessionId": "session-01", "group": "ordinary"}
  },
  "freeze": {
    "independentCorpusFrozen": false,
    "ciPlan": {},
    "hardwareGates": {},
    "resourceGates": {}
  }
}
```

Every eligible clip must have exactly one non-empty `sessionId` and `group`. A session can contain multiple clips; those clips stay together during resampling. The same frozen GT SHA must appear in the manifest and both reports. Both reports must have exactly the same eligible frame keys. Duplicate keys, missing identities, malformed counts, mismatched hashes, and null success values stop the comparison with an error.

`ordinaryGroups` identifies groups subject to the 2 percentage-point non-inferiority check. The corresponding estimate is B minus A success rate and its margin is `-0.02`. Grouping is explicit in the manifest because the evaluator report has no per-frame group field; the comparer verifies it against aggregate report counts.

## Protocol and output

Requires Python 3.10 or newer. Run from the repository root:

```sh
python3 tools/dbq_compare.py \
  --report-a path/to/report-a.json \
  --report-b path/to/report-b.json \
  --manifest path/to/cluster-manifest.json \
  --output path/to/paired-comparison.json \
  --seed 282401 \
  --repetitions 10000
```

The recorded point estimates are frame-weighted within each group. It reports each group's eligible denominator, failure counts and rates, success rates, paired relative failure reduction `(failureA - failureB) / failureA`, and absolute success difference `B - A`. Relative reduction is `null` when A has no failures.

Uncertainty uses a percentile bootstrap over independent sessions, with replacement. For each group, a draw selects the same number of sessions represented in that group; all eligible frames and clips from every sampled session are carried together. The seed and repetition count are pinned in the output. The output specifies two-sided 95% percentile intervals for effects and a one-sided 95% lower bound for ordinary-group non-inferiority. Fewer than 10 independent sessions or a flat bootstrap distribution marks the relevant interval `INCONCLUSIVE`. If any replicate has zero A failures, the relative-reduction interval is `INCONCLUSIVE`; when A has zero observed failures, relative reduction is `null` and its interval is unavailable. A flat interval is not evidence of zero risk. The absolute-effect interval is assessed separately, so unavailable relative reduction does not invalidate an otherwise estimable non-inferiority bound.

The JSON records SHA-256 hashes for both reports and the manifest, the source GT hash, the cluster-manifest file hash, a canonical hash of the normalized clip-to-session/group assignments, and all protocol settings. `selection.status` is always `INCONCLUSIVE`: this harness does not verify the full absolute AC suite, independent corpus freeze, physical/hardware measurements, or resource gates, and it never emits an AC `PASS` or winner. Missing freeze, CI-plan, hardware, or resource-gate metadata is listed as a specific reason as well.

## Limits

This is software-only statistics over evaluator replay outputs. It makes no claim about physical camera behavior, preview presentation, latency, hardware resource budgets, or corpus independence. A valid comparison does not replace the protocol freeze, all absolute acceptance checks, or the decision record required by spec sections 18 and 22. Inputs must retain the same frozen eligible GT population; the tool does not infer exclusions or repair invalid reports.

Unit coverage and command stdout/exit evidence for version 0.4.282 are recorded in [`runs/statistics-0.4.282/validation-summary.md`](runs/statistics-0.4.282/validation-summary.md).
