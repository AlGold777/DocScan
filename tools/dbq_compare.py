#!/usr/bin/env python3
"""Paired, session-clustered comparison of DBQ evaluator reports.

This tool compares only eligible-frame geometric success from dbq_evaluate.py.
It is not an acceptance runner and never chooses a production winner.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import random
import re
import sys
from collections import Counter, defaultdict
from typing import Any


SCHEMA_VERSION = 1
MIN_INDEPENDENT_SESSIONS = 10
NONINFERIORITY_MARGIN = -0.02
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class ComparisonError(ValueError):
    """Input does not support a fail-closed paired comparison."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, value in pairs:
        if key in out:
            raise ComparisonError(f"duplicate JSON object key: {key}")
        out[key] = value
    return out


def read_json(path: str | pathlib.Path) -> Any:
    try:
        return json.loads(pathlib.Path(path).read_text(encoding="utf-8"), object_pairs_hook=_unique_object,
                          parse_constant=lambda value: (_ for _ in ()).throw(ComparisonError(f"invalid JSON number: {value}")))
    except (OSError, json.JSONDecodeError) as exc:
        raise ComparisonError(f"cannot read JSON {path}: {exc}") from exc


def sha256_file(path: str | pathlib.Path) -> str:
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def canonical_sha256(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ComparisonError(message)


def _integer(value: Any, label: str) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool) and value >= 0,
             f"{label} must be a non-negative integer")
    return value


def load_manifest(data: Any) -> dict[str, Any]:
    _require(isinstance(data, dict), "cluster manifest must be a JSON object")
    _require(data.get("schemaVersion") == SCHEMA_VERSION, "unsupported manifest schemaVersion")
    gt_hash = data.get("sourceGtSha256")
    _require(isinstance(gt_hash, str) and SHA256_RE.fullmatch(gt_hash) is not None,
             "manifest sourceGtSha256 must be a lowercase SHA-256 hex digest")
    raw_clips = data.get("clips")
    _require(isinstance(raw_clips, dict) and bool(raw_clips), "manifest clips must be a non-empty object")
    clips: dict[str, dict[str, str]] = {}
    for clip_id, identity in raw_clips.items():
        _require(isinstance(clip_id, str) and clip_id.strip(), "clip identity must be a non-empty string")
        _require(isinstance(identity, dict), f"manifest identity for clip {clip_id!r} must be an object")
        session_id, group = identity.get("sessionId"), identity.get("group")
        _require(isinstance(session_id, str) and session_id.strip(), f"missing sessionId for clip {clip_id!r}")
        _require(isinstance(group, str) and group.strip(), f"missing group for clip {clip_id!r}")
        clips[clip_id] = {"sessionId": session_id, "group": group}
    ordinary = data.get("ordinaryGroups", [])
    _require(isinstance(ordinary, list) and all(isinstance(x, str) and x for x in ordinary),
             "ordinaryGroups must be a list of non-empty group names")
    _require(len(set(ordinary)) == len(ordinary), "ordinaryGroups contains duplicates")
    _require(set(ordinary) <= {identity["group"] for identity in clips.values()},
             "ordinaryGroups references a group absent from manifest clips")
    return {"sourceGtSha256": gt_hash, "clips": clips, "ordinaryGroups": ordinary,
            "freeze": data.get("freeze", {})}


def load_report(data: Any, label: str) -> dict[str, Any]:
    _require(isinstance(data, dict), f"report {label} must be a JSON object")
    _require(data.get("schemaVersion") == 1, f"report {label} has unsupported schemaVersion")
    hashes = data.get("inputSha256")
    _require(isinstance(hashes, dict), f"report {label} is missing inputSha256")
    gt_hash = hashes.get("gt")
    _require(isinstance(gt_hash, str) and SHA256_RE.fullmatch(gt_hash) is not None,
             f"report {label} inputSha256.gt is missing or invalid")
    frames = data.get("frames")
    _require(isinstance(frames, list), f"report {label} frames must be a list")
    by_key: dict[tuple[str, Any], dict[str, Any]] = {}
    for index, frame in enumerate(frames):
        _require(isinstance(frame, dict), f"report {label} frame {index} must be an object")
        clip_id, frame_id = frame.get("clipId"), frame.get("frameId")
        _require(isinstance(clip_id, str) and clip_id.strip(), f"report {label} frame {index} has invalid clipId")
        _require((isinstance(frame_id, str) and frame_id.strip()) or
                 (isinstance(frame_id, int) and not isinstance(frame_id, bool) and frame_id >= 0),
                 f"report {label} frame {index} has invalid frameId")
        key = (clip_id, frame_id)
        _require(key not in by_key, f"report {label} has duplicate eligible frame key {key!r}")
        success = frame.get("success")
        _require(isinstance(success, bool), f"report {label} frame {key!r} success must be explicit boolean")
        # A failed evaluator row includes both absent predictions and invalid geometry. reason is retained,
        # but success is the evaluator's complete decision bit and is what comparison consumes.
        _require(frame.get("reason") in (None, "missing", "invalid"),
                 f"report {label} frame {key!r} has unexpected reason")
        _require(not success or frame.get("reason") is None,
                 f"report {label} frame {key!r} cannot be successful with a failure reason")
        by_key[key] = {"clipId": clip_id, "frameId": frame_id, "success": success,
                       "reason": frame.get("reason")}
    groups = data.get("groups")
    _require(isinstance(groups, dict), f"report {label} groups must be an object")
    normalized_groups: dict[str, dict[str, int]] = {}
    for group_name, summary in groups.items():
        _require(isinstance(group_name, str) and group_name, f"report {label} has invalid group name")
        _require(isinstance(summary, dict), f"report {label} group {group_name!r} must be an object")
        normalized_groups[group_name] = {name: _integer(summary.get(name), f"report {label} {group_name}.{name}")
                                         for name in ("eligible", "success", "missing", "invalid", "clips")}
    return {"gtHash": gt_hash, "frames": by_key, "groups": normalized_groups}


def _percentile(values: list[float], probability: float) -> float:
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def _metric_counts(rows: list[tuple[str, bool, bool]]) -> dict[str, float | int | None]:
    n = len(rows)
    if not n:
        return {"eligibleFrames": 0, "failureA": 0, "failureB": 0, "failureRateA": None,
                "failureRateB": None, "successRateA": None, "successRateB": None,
                "pairedRelativeFailureReduction": None, "absoluteSuccessDifferenceBMinusA": None}
    fail_a = sum(not a for _, a, _ in rows)
    fail_b = sum(not b for _, _, b in rows)
    success_a = n - fail_a
    success_b = n - fail_b
    return {"eligibleFrames": n, "failureA": fail_a, "failureB": fail_b,
            "failureRateA": fail_a / n, "failureRateB": fail_b / n,
            "successRateA": success_a / n, "successRateB": success_b / n,
            "pairedRelativeFailureReduction": ((fail_a - fail_b) / fail_a) if fail_a else None,
            "absoluteSuccessDifferenceBMinusA": (success_b - success_a) / n}


def compare(report_a: Any, report_b: Any, manifest_data: Any, *, seed: int = 282401,
            repetitions: int = 10000) -> dict[str, Any]:
    _require(isinstance(seed, int) and not isinstance(seed, bool) and seed >= 0, "seed must be non-negative")
    _require(isinstance(repetitions, int) and not isinstance(repetitions, bool) and repetitions >= 100,
             "repetitions must be an integer >= 100")
    manifest = load_manifest(manifest_data)
    a = load_report(report_a, "A")
    b = load_report(report_b, "B")
    _require(a["gtHash"] == manifest["sourceGtSha256"], "report A GT hash does not match cluster manifest")
    _require(b["gtHash"] == manifest["sourceGtSha256"], "report B GT hash does not match cluster manifest")
    keys_a, keys_b = set(a["frames"]), set(b["frames"])
    _require(keys_a == keys_b, f"eligible frame key mismatch: onlyA={len(keys_a-keys_b)} onlyB={len(keys_b-keys_a)}")
    _require(bool(keys_a), "no eligible paired frames")

    frame_groups: dict[str, list[tuple[str, bool, bool]]] = defaultdict(list)
    clip_counts: Counter[str] = Counter()
    expected_clip_frames: Counter[str] = Counter()
    for clip_id, _ in keys_a:
        _require(clip_id in manifest["clips"], f"eligible frame references clip missing from manifest: {clip_id!r}")
        expected_clip_frames[clip_id] += 1
    for key in sorted(keys_a, key=lambda item: (item[0], type(item[1]).__name__, str(item[1]))):
        clip_id, _ = key
        identity = manifest["clips"][clip_id]
        row_a, row_b = a["frames"][key], b["frames"][key]
        _require(row_a["clipId"] == row_b["clipId"] and row_a["frameId"] == row_b["frameId"],
                 f"paired identity mismatch for {key!r}")
        frame_groups[identity["group"]].append((identity["sessionId"], row_a["success"], row_b["success"]))
        clip_counts[identity["group"]] += 0  # group initialized below from unique eligible clips
    for clip_id in expected_clip_frames:
        clip_counts[manifest["clips"][clip_id]["group"]] += 1

    # Reconcile report summaries against the manifest-derived eligible frames and identities.
    manifest_groups = {identity["group"] for identity in manifest["clips"].values()}
    groups = set(frame_groups) | set(a["groups"]) | set(b["groups"]) | manifest_groups
    for label, report in (("A", a), ("B", b)):
        for group_name in groups:
            summary = report["groups"].get(group_name)
            expected_n = len(frame_groups.get(group_name, []))
            expected_success = sum(row[1 if label == "A" else 2] for row in frame_groups.get(group_name, []))
            expected_clips = clip_counts.get(group_name, 0)
            eligible_clip_ids = {clip_id for clip_id, _ in keys_a
                                 if manifest["clips"][clip_id]["group"] == group_name}
            expected_missing = sum(report["frames"][key]["reason"] == "missing" for key in keys_a
                                   if key[0] in eligible_clip_ids)
            expected_invalid = sum(report["frames"][key]["reason"] == "invalid" for key in keys_a
                                   if key[0] in eligible_clip_ids)
            _require(summary is not None, f"report {label} missing group summary {group_name!r}")
            _require(summary["eligible"] == expected_n, f"report {label} {group_name}.eligible disagrees with frames/manifest")
            _require(summary["success"] == expected_success, f"report {label} {group_name}.success disagrees with frames")
            _require(summary["clips"] == expected_clips, f"report {label} {group_name}.clips disagrees with manifest")
            _require(summary["missing"] == expected_missing, f"report {label} {group_name}.missing disagrees with frames")
            _require(summary["invalid"] == expected_invalid, f"report {label} {group_name}.invalid disagrees with frames")
            _require(summary["missing"] + summary["invalid"] <= summary["eligible"] - summary["success"],
                     f"report {label} {group_name} failure counters exceed failures")

    sessions = sorted({session_id for rows in frame_groups.values() for session_id, _, _ in rows})
    _require(bool(sessions), "no independent session clusters")
    point: dict[str, Any] = {}
    bootstrap_values: dict[str, dict[str, list[float]]] = {}
    for group_name in sorted(groups):
        rows = frame_groups.get(group_name, [])
        values = _metric_counts(rows)
        session_ids = {session_id for session_id, _, _ in rows}
        values["independentSessions"] = len(session_ids)
        values["independentClips"] = clip_counts[group_name]
        values["pairedRelativeFailureReductionCI95TwoSided"] = None
        values["absoluteSuccessDifferenceCI95TwoSided"] = None
        values["nonInferiorityLowerBound95OneSided"] = None
        values["nonInferiorityMargin"] = NONINFERIORITY_MARGIN if group_name in manifest["ordinaryGroups"] else None
        values["nonInferiorityStatus"] = "NOT_APPLICABLE" if group_name not in manifest["ordinaryGroups"] else "INCONCLUSIVE"
        values["absoluteIntervalStatus"] = "INCONCLUSIVE"
        values["relativeIntervalStatus"] = "UNAVAILABLE" if values["pairedRelativeFailureReduction"] is None else "INCONCLUSIVE"
        point[group_name] = values
        bootstrap_values[group_name] = {"relative": [], "absolute": []}

    rows_by_session: dict[str, dict[str, list[tuple[str, bool, bool]]]] = defaultdict(lambda: defaultdict(list))
    for group_name, rows in frame_groups.items():
        for row in rows:
            rows_by_session[row[0]][group_name].append(row)
    rng = random.Random(seed)
    invalid_replicates: Counter[str] = Counter()
    degenerate_groups: set[str] = set()
    all_bootstrap: dict[str, dict[str, list[float]]] = {name: {"relative": [], "absolute": []} for name in frame_groups}
    for group_name in sorted(groups):
        rows = frame_groups.get(group_name, [])
        group_sessions = sorted({session_id for session_id, _, _ in rows})
        if not group_sessions:
            invalid_replicates[group_name] = repetitions
            continue
        for _ in range(repetitions):
            # Resample only independent sessions represented in this group. Within every selected
            # session, include all its group clips and frames together.
            sampled_sessions = [group_sessions[rng.randrange(len(group_sessions))] for _ in group_sessions]
            sample_rows: list[tuple[str, bool, bool]] = []
            for session_id in sampled_sessions:
                sample_rows.extend(rows_by_session[session_id][group_name])
            sample_metrics = _metric_counts(sample_rows)
            relative = sample_metrics["pairedRelativeFailureReduction"]
            absolute = sample_metrics["absoluteSuccessDifferenceBMinusA"]
            if relative is None:
                invalid_replicates[group_name] += 1
            else:
                all_bootstrap[group_name]["relative"].append(float(relative))
            all_bootstrap[group_name]["absolute"].append(float(absolute))

    for group_name, values in point.items():
        group_sessions = values["independentSessions"]
        relative_samples = all_bootstrap[group_name]["relative"]
        absolute_samples = all_bootstrap[group_name]["absolute"]
        no_frames_in_draws = len(absolute_samples) != repetitions
        zero_a_failure_count = invalid_replicates[group_name]
        zero_a_failure_draws = zero_a_failure_count > 0
        flat_absolute = len(set(absolute_samples)) <= 1
        flat_relative = values["pairedRelativeFailureReduction"] is not None and len(set(relative_samples)) <= 1
        tiny = group_sessions < MIN_INDEPENDENT_SESSIONS
        if tiny:
            degenerate_groups.add(group_name)
        reasons = []
        if tiny:
            reasons.append(f"only {group_sessions} independent sessions; minimum is {MIN_INDEPENDENT_SESSIONS}")
        if values["eligibleFrames"] == 0:
            reasons.append("no eligible paired frames in this group")
        if no_frames_in_draws:
            reasons.append("some bootstrap samples had no group frames")
        if flat_absolute:
            reasons.append("absolute-effect bootstrap distribution is degenerate; flat bootstrap does not establish zero risk")
        if tiny or no_frames_in_draws or flat_absolute:
            values["absoluteIntervalStatus"] = "INCONCLUSIVE"
        else:
            values["absoluteIntervalStatus"] = "ESTIMATED"
        if values["pairedRelativeFailureReduction"] is None:
            values["relativeIntervalStatus"] = "UNAVAILABLE"
            reasons.append("relative failure reduction is unavailable because A has zero observed failures")
        elif tiny or zero_a_failure_draws or flat_relative:
            values["relativeIntervalStatus"] = "INCONCLUSIVE"
            if zero_a_failure_draws:
                reasons.append(f"{zero_a_failure_count} bootstrap samples had zero A failures")
            if flat_relative:
                reasons.append("relative-effect bootstrap distribution is degenerate; flat bootstrap does not establish zero risk")
        else:
            values["relativeIntervalStatus"] = "ESTIMATED"
        values["intervalStatus"] = ("ESTIMATED" if values["absoluteIntervalStatus"] == "ESTIMATED"
                                     and values["relativeIntervalStatus"] == "ESTIMATED" else "INCONCLUSIVE")
        values["intervalReasons"] = reasons
        if len(absolute_samples) == repetitions:
            values["absoluteSuccessDifferenceCI95TwoSided"] = [_percentile(absolute_samples, .025),
                                                                  _percentile(absolute_samples, .975)]
            values["nonInferiorityLowerBound95OneSided"] = _percentile(absolute_samples, .05)
        if values["pairedRelativeFailureReduction"] is not None and len(relative_samples) == repetitions:
            values["pairedRelativeFailureReductionCI95TwoSided"] = [_percentile(relative_samples, .025),
                                                                      _percentile(relative_samples, .975)]
        if group_name in manifest["ordinaryGroups"]:
            lower = values["nonInferiorityLowerBound95OneSided"]
            if values["absoluteIntervalStatus"] == "ESTIMATED" and lower is not None:
                values["nonInferiorityStatus"] = "PASS" if lower >= NONINFERIORITY_MARGIN else "FAIL"
            else:
                values["nonInferiorityStatus"] = "INCONCLUSIVE"

    freeze = manifest["freeze"]
    freeze_reasons = []
    if not isinstance(freeze, dict):
        freeze_reasons.append("manifest freeze metadata is absent or malformed")
    else:
        if freeze.get("independentCorpusFrozen") is not True:
            freeze_reasons.append("independent corpus freeze is not attested")
        if not isinstance(freeze.get("ciPlan"), dict) or not freeze["ciPlan"]:
            freeze_reasons.append("predeclared CI plan is absent")
        if not isinstance(freeze.get("hardwareGates"), dict) or not freeze["hardwareGates"]:
            freeze_reasons.append("hardware gates are absent")
        if not isinstance(freeze.get("resourceGates"), dict) or not freeze["resourceGates"]:
            freeze_reasons.append("resource gates are absent")
    selection_reasons = freeze_reasons + [
        "this paired geometry harness does not evaluate all absolute AC, hardware, or resource gates",
        "winner selection requires a separate evidence-backed decision record"
    ]
    return {
        "schemaVersion": SCHEMA_VERSION,
        "scope": "paired eligible-frame geometry comparison; original-pixel replay only",
        "selection": {"status": "INCONCLUSIVE", "winner": None, "reasons": selection_reasons},
        "protocol": {
            "independentUnit": "sessionId cluster; every clip and eligible frame in a sampled session is resampled jointly",
            "bootstrap": "ordinary percentile cluster bootstrap with replacement",
            "seed": seed,
            "repetitions": repetitions,
            "intervals": {"effect": "two-sided 95% percentile CI (2.5%, 97.5%)",
                          "ordinaryGroupNonInferiority": "one-sided 95% lower percentile bound (5%)"},
            "pointWeighting": "frame-weighted within each group; groups reported separately",
            "nonInferiorityMargin": NONINFERIORITY_MARGIN,
            "minimumIndependentSessionsPerGroup": MIN_INDEPENDENT_SESSIONS,
            "zeroAFailureRelativeReduction": "unavailable; never treated as infinite or zero",
        },
        "inputs": {"reportASha256": None, "reportBSha256": None, "manifestSha256": None,
                   "sourceGtSha256": manifest["sourceGtSha256"], "clusterManifestSha256": None,
                   "clusterAssignmentsSha256": canonical_sha256(manifest["clips"])},
        "counts": {"pairedEligibleFrames": len(keys_a), "groups": len(frame_groups),
                   "independentSessions": len(sessions), "bootstrapDegenerateGroups": sorted(degenerate_groups)},
        "groups": point,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-a", required=True)
    parser.add_argument("--report-b", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--seed", type=int, default=282401)
    parser.add_argument("--repetitions", type=int, default=10000)
    args = parser.parse_args(argv)
    try:
        report_a = read_json(args.report_a)
        report_b = read_json(args.report_b)
        manifest = read_json(args.manifest)
        result = compare(report_a, report_b, manifest, seed=args.seed, repetitions=args.repetitions)
        result["inputs"].update({"reportASha256": sha256_file(args.report_a),
                                 "reportBSha256": sha256_file(args.report_b),
                                 "manifestSha256": sha256_file(args.manifest),
                                 "clusterManifestSha256": sha256_file(args.manifest)})
        pathlib.Path(args.output).write_text(json.dumps(result, indent=2, allow_nan=False) + "\n", encoding="utf-8")
        print(json.dumps({"output": args.output, "selection": result["selection"], "counts": result["counts"]},
                         sort_keys=True))
        return 0
    except (ComparisonError, OSError, TypeError) as exc:
        print(f"dbq_compare: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
