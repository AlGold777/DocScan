# Statistics harness validation, 0.4.282

This run used synthetic metadata-only fixtures. It did not use user images, network access, Gradle, Android devices, or physical measurements.

- Test command: `python3 -m unittest tools.test_dbq_compare -v`
- Result: exit 0; 11 unittest cases passed.
- CLI command: see `compare.command.txt`; exit 0.
- CLI result: 24 exact paired eligible frames in one group across 12 independent sessions; selection is `INCONCLUSIVE` because corpus freeze, CI plan, hardware gates, and resource gates are absent and this harness does not evaluate all absolute gates.
- Synthetic point estimates for the ordinary group: A had 12/24 failures (50%); B had 4/24 failures (16.7%); relative failure reduction was 66.7%; absolute success difference B−A was +33.3 percentage points.
- The absolute-effect two-sided 95% bootstrap interval is +8.3 to +58.3 percentage points; its one-sided non-inferiority lower bound is +8.3 points against the −2 point margin. The relative-effect interval is `INCONCLUSIVE` because zero-A-failure resamples occurred; this is surfaced as an unresolved denominator, never filled with zero.
- Protocol: seed 282401, 10,000 session-cluster bootstrap repetitions, frames weighted within group, two-sided 95% percentile effect intervals, and one-sided 95% lower percentile bound for ordinary-group non-inferiority with margin −2 percentage points.

Evidence files preserve the exact synthetic reports, manifest, CLI stdout/stderr, command, and exit status. These results validate harness behavior only and make no product-quality or acceptance claim.
