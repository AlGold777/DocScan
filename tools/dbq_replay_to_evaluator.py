#!/usr/bin/env python3
"""Convert one completed AndroidTest DBQ replay run into evaluator JSONL inputs."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
import re
import sys
from typing import Any

import dbq_evaluate


ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,80}$")
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
SAFE_OUTPUT_RE = re.compile(r"^[A-Za-z0-9._-]+$")


class ReplayConversionError(ValueError):
    """Replay output is incomplete or inconsistent with its source manifest."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReplayConversionError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _bad_constant(value: str) -> None:
    raise ReplayConversionError(f"invalid JSON number: {value}")


def _load_json(path: pathlib.Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_unique_object,
                          parse_constant=_bad_constant)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ReplayConversionError(f"cannot read JSON {path}: {exc}") from exc


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReplayConversionError(message)


def _safe_id(value: Any, label: str) -> str:
    _require(isinstance(value, str) and ID_RE.fullmatch(value) is not None and value not in (".", ".."),
             f"{label} must use 1-80 ASCII letters, digits, dot, underscore, or dash")
    return value


def _frame_id(value: Any, label: str) -> int:
    _require(isinstance(value, int) and not isinstance(value, bool) and value >= 0,
             f"{label} must be the non-negative integer used by replay schema v1")
    return value


def _finite_number(value: Any) -> bool:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        return False
    try:
        return math.isfinite(float(value))
    except (OverflowError, ValueError):
        return False


def _sha(value: Any, label: str) -> str:
    _require(isinstance(value, str) and SHA_RE.fullmatch(value) is not None,
             f"{label} must be a lowercase SHA-256 hex digest")
    return value


def _point_list(value: Any, label: str, *, required: bool) -> list[list[float | int]] | None:
    if value is None:
        _require(not required, f"{label} is required for eligible ground truth")
        return None
    _require(isinstance(value, list) and len(value) == 4, f"{label} must be null or four [x,y] points")
    points: list[list[float | int]] = []
    for index, point in enumerate(value):
        _require(isinstance(point, list) and len(point) == 2, f"{label}[{index}] must be [x,y]")
        for coordinate in point:
            _require(_finite_number(coordinate), f"{label}[{index}] has a non-finite or invalid coordinate")
        points.append([point[0], point[1]])
    return points


def _manifest_quad(value: Any, *, eligible: bool, label: str) -> list[list[float | int]] | None:
    if value is None:
        _require(not eligible, f"{label} is null for eligible frame")
        return None
    _require(isinstance(value, list) and len(value) == 8, f"{label} must be null or eight flat coordinates")
    _require(all(_finite_number(v) for v in value),
             f"{label} has non-finite or invalid coordinates")
    nested = [[value[i], value[i + 1]] for i in range(0, 8, 2)]
    try:
        metrics = dbq_evaluate.geometry(nested, nested)
        _require(all(math.isfinite(metric) for metric in metrics.values()), f"{label} has non-finite geometry")
    except (ValueError, OverflowError) as exc:
        raise ReplayConversionError(f"{label} is invalid ground truth: {exc}") from exc
    return nested


def _read_manifest(path: pathlib.Path) -> tuple[dict[str, Any], dict[tuple[str, int], dict[str, Any]], list[str], list[str], str]:
    _require(path.is_file(), f"real source manifest is missing: {path}")
    manifest_bytes = path.read_bytes()
    manifest_sha = _sha256_bytes(manifest_bytes)
    manifest = _load_json(path)
    _require(isinstance(manifest, dict) and manifest.get("schemaVersion") == 1,
             "source manifest must be an object with schemaVersion 1")
    variants_raw, clips_raw = manifest.get("variants"), manifest.get("clips")
    _require(isinstance(variants_raw, list) and bool(variants_raw), "source manifest variants must be a non-empty list")
    _require(isinstance(clips_raw, list) and bool(clips_raw), "source manifest clips must be a non-empty list")
    variant_ids: list[str] = []
    for row in variants_raw:
        _require(isinstance(row, dict), "source manifest variant must be an object")
        variant_id = _safe_id(row.get("id"), "variant id")
        _require(variant_id not in variant_ids, f"duplicate variant id: {variant_id}")
        variant_ids.append(variant_id)
    clips: list[str] = []
    frames: dict[tuple[str, int], dict[str, Any]] = {}
    for clip in clips_raw:
        _require(isinstance(clip, dict), "source manifest clip must be an object")
        clip_id = _safe_id(clip.get("clipId"), "clipId")
        group = clip.get("group")
        _require(isinstance(group, str) and group.strip(), f"clip {clip_id!r} has invalid group")
        _require(clip_id not in clips, f"duplicate clipId in source manifest: {clip_id}")
        clips.append(clip_id)
        frame_rows = clip.get("frames")
        _require(isinstance(frame_rows, list) and bool(frame_rows), f"clip {clip_id!r} frames must be non-empty")
        for frame in frame_rows:
            _require(isinstance(frame, dict), f"clip {clip_id!r} frame must be an object")
            frame_id = _frame_id(frame.get("frameId"), f"clip {clip_id!r} frameId")
            key = (clip_id, frame_id)
            _require(key not in frames, f"duplicate source manifest frame identity: {key!r}")
            eligible = frame.get("eligible")
            _require(isinstance(eligible, bool), f"source manifest frame {key!r} eligible must be boolean")
            quad = _manifest_quad(frame.get("quad"), eligible=eligible, label=f"source manifest frame {key!r} quad")
            frames[key] = {"clipId": clip_id, "frameId": frame_id, "group": group,
                           "eligible": eligible, "quad": quad}
    return manifest, frames, variant_ids, clips, manifest_sha


def _read_jsonl(path: pathlib.Path) -> tuple[list[dict[str, Any]], str]:
    _require(path.is_file() and not path.is_symlink(), f"missing or unsafe replay JSONL file: {path.name}")
    content = path.read_bytes()
    try:
        lines = content.decode("utf-8").splitlines()
    except UnicodeDecodeError as exc:
        raise ReplayConversionError(f"replay JSONL is not UTF-8: {path.name}") from exc
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line, object_pairs_hook=_unique_object, parse_constant=_bad_constant)
        except (json.JSONDecodeError, ReplayConversionError) as exc:
            raise ReplayConversionError(f"invalid JSONL {path.name}:{line_number}: {exc}") from exc
        _require(isinstance(row, dict), f"JSONL {path.name}:{line_number} must contain an object")
        rows.append(row)
    return rows, _sha256_bytes(content)


def convert_run(input_dir: str | pathlib.Path, manifest_path: str | pathlib.Path) -> dict[str, Any]:
    """Validate a completed replay run and return canonical evaluator input bytes + provenance."""
    source_dir = pathlib.Path(input_dir)
    _require(source_dir.is_dir(), f"input replay run directory does not exist: {source_dir}")
    run_path = source_dir / "run.json"
    _require(run_path.is_file(), "replay run metadata run.json is required")
    run = _load_json(run_path)
    _require(isinstance(run, dict) and run.get("schemaVersion") == 1, "run.json must use schemaVersion 1")
    _require(run.get("status") == "COMPLETED", "replay run status must be COMPLETED; NOT_RUN is not a passing input")
    manifest, gt_frames, variant_ids, clip_ids, manifest_sha = _read_manifest(pathlib.Path(manifest_path))
    run_manifest_sha = _sha(run.get("sourceManifestSha256"), "run.json sourceManifestSha256")
    _require(run_manifest_sha == manifest_sha, "source manifest SHA does not match run.json")
    run_variants = run.get("variants")
    _require(isinstance(run_variants, list), "run.json variants must be a list")
    run_variant_ids = []
    for row in run_variants:
        _require(isinstance(row, dict), "run.json variant entry must be an object")
        run_variant_ids.append(_safe_id(row.get("variantId"), "run.json variantId"))
    _require(run_variant_ids == variant_ids, "run.json variant identities/order disagree with source manifest")
    run_clips = run.get("clipIds")
    _require(run_clips == clip_ids, "run.json clipIds/order disagree with source manifest")
    run_row_count = run.get("rowCount")
    _require(isinstance(run_row_count, int) and not isinstance(run_row_count, bool) and run_row_count >= 0,
             "run.json rowCount must be a non-negative integer")

    expected_names = {f"{variant_id}--{clip_id}.jsonl" for variant_id in variant_ids for clip_id in clip_ids}
    actual_paths = [p for p in source_dir.iterdir() if p.name.endswith(".jsonl")]
    actual_names = {p.name for p in actual_paths}
    _require(actual_names == expected_names,
             f"replay JSONL file set mismatch: missing={sorted(expected_names-actual_names)} extra={sorted(actual_names-expected_names)}")

    rows_by_variant: dict[str, dict[tuple[str, int], dict[str, Any]]] = {v: {} for v in variant_ids}
    jsonl_hashes: dict[str, str] = {}
    total_rows = 0
    expected_keys = set(gt_frames)
    for variant_id in variant_ids:
        for clip_id in clip_ids:
            name = f"{variant_id}--{clip_id}.jsonl"
            path = source_dir / name
            _require(not path.is_symlink(), f"replay JSONL must not be a symlink: {name}")
            rows, digest = _read_jsonl(path)
            jsonl_hashes[name] = digest
            for row in rows:
                total_rows += 1
                required_fields = {"schemaVersion", "sourceManifestSha256", "variantId", "clipId", "group",
                                   "frameId", "inputSha256", "eligible", "referenceQuadPixelCentres",
                                   "publishedQuadPixelCentres"}
                _require(required_fields <= set(row),
                         f"{name} row is missing required fields: {sorted(required_fields-set(row))}")
                _require(row.get("schemaVersion") == 1, f"{name} row has unsupported schemaVersion")
                row_variant = _safe_id(row.get("variantId"), f"{name} variantId")
                row_clip = _safe_id(row.get("clipId"), f"{name} clipId")
                row_frame = _frame_id(row.get("frameId"), f"{name} frameId")
                _require(row_variant == variant_id and row_clip == clip_id,
                         f"row identity does not match JSONL filename {name}")
                key = (row_clip, row_frame)
                _require(key in gt_frames, f"row references frame absent from source manifest: {key!r}")
                _require(key not in rows_by_variant[variant_id], f"duplicate replay frame identity for {variant_id}: {key!r}")
                source_sha = _sha(row.get("sourceManifestSha256"), f"{name} sourceManifestSha256")
                _require(source_sha == manifest_sha, f"{name} sourceManifestSha256 mismatch")
                expected = gt_frames[key]
                _require(row.get("group") == expected["group"], f"group label mismatch for {variant_id} {key!r}")
                _require(row.get("eligible") is expected["eligible"], f"eligibility label mismatch for {variant_id} {key!r}")
                reference = _point_list(row.get("referenceQuadPixelCentres"), f"{name} {key!r} referenceQuadPixelCentres",
                                        required=expected["eligible"])
                _require(reference == expected["quad"], f"ground-truth quad mismatch for {variant_id} {key!r}")
                input_sha = _sha(row.get("inputSha256"), f"{name} {key!r} inputSha256")
                published = _point_list(row.get("publishedQuadPixelCentres"),
                                        f"{name} {key!r} publishedQuadPixelCentres", required=False)
                if expected["eligible"] and published is not None:
                    try:
                        metrics = dbq_evaluate.geometry(published, expected["quad"])
                    except ValueError:
                        # Preserve invalid but structurally valid published geometry so the evaluator
                        # records it as an invalid-frame failure.
                        pass
                    except OverflowError as exc:
                        raise ReplayConversionError(f"published geometry overflows evaluator arithmetic for {key!r}") from exc
                    else:
                        _require(all(math.isfinite(metric) for metric in metrics.values()),
                                 f"published geometry has non-finite evaluator metrics for {key!r}")
                rows_by_variant[variant_id][key] = {"inputSha256": input_sha,
                                                     "publishedQuadPixelCentres": published}
            expected_clip_keys = {key for key in expected_keys if key[0] == clip_id}
            _require(expected_clip_keys <= set(rows_by_variant[variant_id]),
                     f"missing replay frame keys for {variant_id}/{clip_id}")
    _require(total_rows == run_row_count, "JSONL row count does not match run.json rowCount")
    for variant_id in variant_ids:
        _require(set(rows_by_variant[variant_id]) == expected_keys,
                 f"replay frame key set does not match manifest for variant {variant_id}")
    for key in sorted(expected_keys, key=lambda item: (item[0], type(item[1]).__name__, str(item[1]))):
        hashes = {rows_by_variant[v][key]["inputSha256"] for v in variant_ids}
        _require(len(hashes) == 1, f"inputSha256 differs across variants for frame {key!r}")

    ordered_frames = [gt_frames[key] for clip_id in clip_ids for key in gt_frames if key[0] == clip_id]
    gt_lines = [json.dumps({"clipId": frame["clipId"], "frameId": frame["frameId"],
                            "group": frame["group"], "eligible": frame["eligible"], "quad": frame["quad"]},
                           separators=(",", ":"), allow_nan=False) for frame in ordered_frames]
    outputs: dict[str, bytes] = {"gt.jsonl": ("\n".join(gt_lines) + "\n").encode("utf-8")}
    for variant_id in variant_ids:
        pred_rows = []
        for frame in ordered_frames:
            key = (frame["clipId"], frame["frameId"])
            pred_rows.append(json.dumps({"clipId": frame["clipId"], "frameId": frame["frameId"],
                                         "quad": rows_by_variant[variant_id][key]["publishedQuadPixelCentres"]},
                                        separators=(",", ":"), allow_nan=False))
        outputs[f"predictions-{variant_id}.jsonl"] = ("\n".join(pred_rows) + "\n").encode("utf-8")
    for filename in outputs:
        _require(SAFE_OUTPUT_RE.fullmatch(filename) is not None and pathlib.PurePath(filename).name == filename,
                 f"unsafe output filename derived from replay variant: {filename!r}")
    output_hashes = {name: _sha256_bytes(content) for name, content in outputs.items()}
    provenance = {
        "schemaVersion": 1,
        "status": "CONVERTED",
        "sourceManifestSha256": manifest_sha,
        "runMetadataSha256": _sha256_bytes(run_path.read_bytes()),
        "sourceManifestPath": str(pathlib.Path(manifest_path).resolve()),
        "replayRunDirectory": str(source_dir.resolve()),
        "replayJsonlSha256": dict(sorted(jsonl_hashes.items())),
        "inputSha256ByFrame": {f"{clip}/{frame}": rows_by_variant[variant_ids[0]][(clip, frame)]["inputSha256"]
                                for clip, frame in sorted(expected_keys, key=lambda item: (item[0], type(item[1]).__name__, str(item[1])))},
        "outputSha256": output_hashes,
        "variants": variant_ids,
        "clipIds": clip_ids,
        "frameCount": len(expected_keys),
        "rowCount": total_rows,
        "claims": {"geometryReplayOnly": True, "latencyOrUiQualityClaim": False,
                   "hardwareAcceptance": "NOT_RUN", "winnerSelection": "NOT_RUN"},
        "clusterManifest": "required separately; no sessionId or corpus independence inferred",
    }
    return {"outputs": outputs, "provenance": provenance}


def convert_to_directory(input_dir: str | pathlib.Path, manifest_path: str | pathlib.Path,
                         output_dir: str | pathlib.Path) -> dict[str, Any]:
    converted = convert_run(input_dir, manifest_path)
    destination = pathlib.Path(output_dir)
    _require(not destination.exists(), f"output directory already exists: {destination}")
    destination.mkdir(parents=True)
    try:
        for name, content in converted["outputs"].items():
            (destination / name).write_bytes(content)
        provenance_bytes = (json.dumps(converted["provenance"], indent=2, allow_nan=False) + "\n").encode("utf-8")
        (destination / "conversion-report.json").write_bytes(provenance_bytes)
    except OSError:
        # A partially written output directory is not a valid conversion artifact.
        for child in destination.iterdir():
            child.unlink()
        destination.rmdir()
        raise
    return converted


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True, help="completed replay run directory containing run.json and per-variant JSONL")
    parser.add_argument("--manifest", required=True, help="the exact source replay manifest used for the run")
    parser.add_argument("--output-dir", required=True, help="new directory for canonical evaluator inputs and provenance")
    args = parser.parse_args(argv)
    try:
        converted = convert_to_directory(args.input_dir, args.manifest, args.output_dir)
        print(json.dumps({"status": "CONVERTED", "outputDir": args.output_dir,
                          "frameCount": converted["provenance"]["frameCount"],
                          "rowCount": converted["provenance"]["rowCount"],
                          "variants": converted["provenance"]["variants"],
                          "outputSha256": converted["provenance"]["outputSha256"]}, sort_keys=True))
        return 0
    except (ReplayConversionError, OSError, TypeError) as exc:
        print(f"dbq_replay_to_evaluator: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
