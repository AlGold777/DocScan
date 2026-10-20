# Replay conversion validation, 0.4.282

This evidence uses synthetic metadata-only replay JSONL. It contains no source images and makes no hardware, latency, UI, or product-quality claim. The explicit cluster manifest is test input only; the converter itself does not infer sessions.

- Converter tests: `python3 -m unittest tools.test_dbq_replay_to_evaluator -v` — exit 0, 10 tests passed. Coverage includes exact output schema, published-null misses, invalid/missing GT, duplicate rows, labels and frame keys, source and input hashes, absent real manifest / `NOT_RUN`, and unsafe variant IDs.
- Replay conversion CLI, evaluator CLI for A and B, and paired comparison CLI each exited 0; exact commands and outputs are preserved in this directory.
- End-to-end synthetic run: 24 replay rows across 12 frame identities and two variants converted to one shared GT file and two published-only prediction files. Both evaluator reports consumed numeric replay `frameId` values directly.
- Paired comparison: 12 eligible ordinary-group frames across 12 explicitly supplied session clusters; A had 11 failures, B had 5, relative failure reduction was 54.5%, and absolute success difference B−A was +50 percentage points. The two-sided 95% percentile intervals were 25% to 83.3% for relative failure reduction and +25 to +75 percentage points for absolute success difference.
- Statistical non-inferiority estimate is `PASS` for this synthetic group against −2 percentage points; overall selection remains `INCONCLUSIVE` because corpus freeze, CI plan, hardware/resource gates, and all absolute acceptance checks are absent. No hardware or AC result is inferred.
- SHA-256 values for replay metadata, source manifest, every per-variant JSONL, per-frame input identities, canonical GT/prediction files, and cluster assignments are recorded in `evaluator-inputs/conversion-report.json` and `paired-comparison.json`.

The input shape and exact CLI are documented in [`replay-harness.md`](../../replay-harness.md#convert-completed-runs-for-geometric-evaluation).
