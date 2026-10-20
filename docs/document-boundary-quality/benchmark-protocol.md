# DBQ-2026-10: protocol, revision 1

Owner authorized implementation on 2026-10-20. Subsequent owner direction: all
physical-phone checks happen after the complete implementation; use available
unit, replay, emulator and build checks in the meantime. This changes scheduling,
not acceptance thresholds. Questions must offer selectable answers.

## Source and variants

`BASE_ORIGINAL` is commit `e62a5b5009acc603f590b74062e4039960e381ca`,
version 0.4.279/398. It includes the owner's previously uncommitted work.
`before-dbq-20261020` points to the earlier HEAD only; its dirty source archive
is described separately in `baseline-manifest.json`. Do not confuse these bases.

Compare original, original + common pipeline, A, B-LC100, B-T8, then winner
without refinement/tracking/prediction. Keep original detector runnable. No
production engine switch until the selection rule in specification §22 passes.
Prediction starts disabled; unavailable preview presentation time stays null.

## Data and metrics

The pilot requires at least 120 independent 8–15 s clips, 15 per group in §17,
plus at least 30 minutes of negatives per qualified device tuple. Record document,
background, session, clip and primary-object IDs before split. Never split frames
of one clip. Double-annotate at least 10% of clips. Synthetic fixtures and emulator
camera scenes remain a separate regression suite, not a real-camera denominator.

GT determines eligibility, partial visibility, mandatory-content masks and
annotation uncertainty before viewing detector outputs. Until a corpus manifest
exists, corpus/split/annotation hashes are null, not an empty passing dataset.

Use §18 formulas for IoU, maximum cyclic corner error, narrow-receipt side error,
time-weighted precision/coverage, false-lock episodes, displayed jitter and lag.
Include misses/errors in success denominators. Keep raw/refined/tracked/displayed
spaces and timestamps separate. Capture-to-present cannot use receipt/draw time.

Freeze split, evaluator revision, eligibility, sample-size/stopping plan, tuning
configs and resource budgets before opening final test. Paired bootstrap resamples
independent sessions/clips, never adjacent frames. Use one-sided 95% conservative
acceptance bounds. Zero events need a conservative upper bound, never a degenerate
bootstrap PASS. Resource limits remain OPEN until physical baseline; 96 MiB crop
admission is not a process PSS limit. No final selection while these gates are open.

## Runs and reporting

Each run records source/config/model/corpus hashes, commands, executed tests,
device/build/ABI, version/signature/APK hashes, missing observations and reasons.
Allowed verdicts: PASS, FAIL, NOT_RUN, BLOCKED, INCONCLUSIVE, NOT_APPLICABLE.
`UP-TO-DATE` is not a fresh executed test. Tests and their result analysis are
delegated to GPT-6 Luna high. Do not relax assertions after failures.

Physical AC01–AC19 and HW01–HW10 remain NOT_RUN until final phone qualification.
An implementation APK may be delivered for that qualification without claiming
that these gates passed. Keep external SDKs NOT_EVALUATED unless actually run;
do not purchase licenses or make network/GMS mandatory.
