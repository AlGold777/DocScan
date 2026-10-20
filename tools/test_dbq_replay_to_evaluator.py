import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dbq_compare
import dbq_evaluate
import dbq_replay_to_evaluator as converter


QUAD_FLAT = [0, 0, 10, 0, 10, 10, 0, 10]
QUAD_NESTED = [[0, 0], [10, 0], [10, 10], [0, 10]]


def build_run(root: Path, *, session_count=2, variants=("A", "B")):
    run_dir = root / "replay-run"
    run_dir.mkdir()
    manifest_path = root / "manifest.json"
    if session_count == 2:
        clips = [
            {"clipId": "clip-1", "group": "ordinary", "frames": [
                {"frameId": 1, "timestampMs": 1, "file": "frame1.jpg", "rotationDegrees": 0,
                 "eligible": True, "quad": QUAD_FLAT},
                {"frameId": 2, "timestampMs": 2, "file": "frame2.jpg", "rotationDegrees": 0,
                 "eligible": False, "quad": None},
            ]},
            {"clipId": "clip-2", "group": "difficult", "frames": [
                {"frameId": 3, "timestampMs": 1, "file": "frame3.jpg", "rotationDegrees": 0,
                 "eligible": True, "quad": QUAD_FLAT},
            ]},
        ]
    else:
        clips = [{"clipId": f"clip-{i}", "group": "ordinary", "frames": [
            {"frameId": 1, "timestampMs": 1, "file": f"frame-{i}.jpg", "rotationDegrees": 0,
             "eligible": True, "quad": QUAD_FLAT},
        ]} for i in range(session_count)]
    manifest = {"schemaVersion": 1,
                "variants": [{"id": name, "backend": "A", "tracking": False} for name in variants],
                "clips": clips}
    manifest_bytes = (json.dumps(manifest, separators=(",", ":")) + "\n").encode()
    manifest_path.write_bytes(manifest_bytes)
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    all_rows = 0
    for variant in variants:
        for clip in clips:
            rows = []
            for frame in clip["frames"]:
                key = (clip["clipId"], frame["frameId"])
                if session_count > 2:
                    session = int(clip["clipId"].split("-")[-1])
                    failed = session < (11 if variant == variants[0] else 5)
                else:
                    failed = (variant == variants[0] and frame["frameId"] == 3)
                rows.append({
                    "schemaVersion": 1, "sourceManifestSha256": manifest_sha, "variantId": variant,
                    "clipId": clip["clipId"], "group": clip["group"], "frameId": frame["frameId"],
                    "inputSha256": hashlib.sha256(f"bytes:{key}".encode()).hexdigest(),
                    "eligible": frame["eligible"],
                    "referenceQuadPixelCentres": QUAD_NESTED if frame["quad"] is not None else None,
                    "publishedQuadPixelCentres": None if failed or not frame["eligible"] else QUAD_NESTED,
                })
            all_rows += len(rows)
            target = run_dir / f"{variant}--{clip['clipId']}.jsonl"
            target.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    run = {"schemaVersion": 1, "status": "COMPLETED", "sourceManifestSha256": manifest_sha,
           "variants": [{"variantId": v} for v in variants], "clipIds": [c["clipId"] for c in clips],
           "rowCount": all_rows}
    (run_dir / "run.json").write_text(json.dumps(run), encoding="utf-8")
    return run_dir, manifest_path, manifest, manifest_sha


class ReplayConverterTests(unittest.TestCase):
    def test_small_rows_convert_to_shared_gt_and_published_only_predictions(self):
        with tempfile.TemporaryDirectory() as temp:
            run_dir, manifest_path, _, manifest_sha = build_run(Path(temp))
            converted = converter.convert_run(run_dir, manifest_path)
            gt = [json.loads(line) for line in converted["outputs"]["gt.jsonl"].decode().splitlines()]
            pred_a = [json.loads(line) for line in converted["outputs"]["predictions-A.jsonl"].decode().splitlines()]
            self.assertEqual(len(gt), 3)
            self.assertEqual(gt[0], {"clipId": "clip-1", "frameId": 1, "group": "ordinary",
                                     "eligible": True, "quad": QUAD_NESTED})
            self.assertIsNone(gt[1]["quad"])
            self.assertIsNone(pred_a[1]["quad"])
            self.assertEqual(pred_a[0]["quad"], QUAD_NESTED)
            self.assertEqual(converted["provenance"]["sourceManifestSha256"], manifest_sha)
            self.assertEqual(set(converted["provenance"]["outputSha256"]),
                             {"gt.jsonl", "predictions-A.jsonl", "predictions-B.jsonl"})

    def test_eligible_missing_or_invalid_ground_truth_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            run_dir, manifest_path, manifest, _ = build_run(root)
            manifest["clips"][0]["frames"][0]["quad"] = None
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            manifest_sha = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            run = json.loads((run_dir / "run.json").read_text())
            run["sourceManifestSha256"] = manifest_sha
            (run_dir / "run.json").write_text(json.dumps(run), encoding="utf-8")
            for path in run_dir.glob("*.jsonl"):
                rows = [json.loads(line) for line in path.read_text().splitlines()]
                for row in rows:
                    row["sourceManifestSha256"] = manifest_sha
                path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            with self.assertRaisesRegex(converter.ReplayConversionError, "null for eligible frame"):
                converter.convert_run(run_dir, manifest_path)
            manifest["clips"][0]["frames"][0]["quad"] = [0, 0, 10, 10, 10, 0, 0, 10]
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            manifest_sha = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            run["sourceManifestSha256"] = manifest_sha
            (run_dir / "run.json").write_text(json.dumps(run), encoding="utf-8")
            for path in run_dir.glob("*.jsonl"):
                rows = [json.loads(line) for line in path.read_text().splitlines()]
                for row in rows:
                    row["sourceManifestSha256"] = manifest_sha
                    if row["clipId"] == "clip-1" and row["frameId"] == 1:
                        row["referenceQuadPixelCentres"] = [[0, 0], [10, 10], [10, 0], [0, 10]]
                path.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
            with self.assertRaisesRegex(converter.ReplayConversionError, "invalid ground truth"):
                converter.convert_run(run_dir, manifest_path)

    def test_duplicate_replay_keys_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            run_dir, manifest_path, _, _ = build_run(Path(temp))
            path = run_dir / "A--clip-1.jsonl"
            text = path.read_text()
            path.write_text(text + text.splitlines()[0] + "\n")
            with self.assertRaisesRegex(converter.ReplayConversionError, "duplicate replay frame identity"):
                converter.convert_run(run_dir, manifest_path)

    def test_mismatched_manifest_labels_fail_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            run_dir, manifest_path, _, _ = build_run(Path(temp))
            path = run_dir / "A--clip-1.jsonl"
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            rows[0]["group"] = "wrong-group"
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            with self.assertRaisesRegex(converter.ReplayConversionError, "group label mismatch"):
                converter.convert_run(run_dir, manifest_path)

    def test_missing_replay_frame_key_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            run_dir, manifest_path, _, _ = build_run(Path(temp))
            path = run_dir / "B--clip-1.jsonl"
            path.write_text(path.read_text().splitlines()[0] + "\n")
            with self.assertRaisesRegex(converter.ReplayConversionError, "missing replay frame keys"):
                converter.convert_run(run_dir, manifest_path)

    def test_input_sha_mismatch_across_variants_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            run_dir, manifest_path, _, _ = build_run(Path(temp))
            path = run_dir / "B--clip-1.jsonl"
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            rows[0]["inputSha256"] = "0" * 64
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            with self.assertRaisesRegex(converter.ReplayConversionError, "inputSha256 differs across variants"):
                converter.convert_run(run_dir, manifest_path)

    def test_source_manifest_hash_mismatch_fails_closed(self):
        with tempfile.TemporaryDirectory() as temp:
            run_dir, manifest_path, _, _ = build_run(Path(temp))
            run = json.loads((run_dir / "run.json").read_text())
            run["sourceManifestSha256"] = "0" * 64
            (run_dir / "run.json").write_text(json.dumps(run))
            with self.assertRaisesRegex(converter.ReplayConversionError, "source manifest SHA does not match"):
                converter.convert_run(run_dir, manifest_path)

    def test_missing_real_manifest_and_not_run_are_errors(self):
        with tempfile.TemporaryDirectory() as temp:
            run_dir, manifest_path, _, _ = build_run(Path(temp))
            with self.assertRaisesRegex(converter.ReplayConversionError, "real source manifest is missing"):
                converter.convert_run(run_dir, Path(temp) / "missing.json")
            run = json.loads((run_dir / "run.json").read_text())
            run["status"] = "NOT_RUN"
            (run_dir / "run.json").write_text(json.dumps(run))
            with self.assertRaisesRegex(converter.ReplayConversionError, "must be COMPLETED"):
                converter.convert_run(run_dir, manifest_path)

    def test_unsafe_variant_id_is_rejected_before_output_path_creation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            run_dir, manifest_path, manifest, _ = build_run(root)
            manifest["variants"][0]["id"] = "../escape"
            manifest_path.write_text(json.dumps(manifest))
            with self.assertRaisesRegex(converter.ReplayConversionError, "variant id must use"):
                converter.convert_run(run_dir, manifest_path)

    def test_conversion_evaluator_and_explicit_clustered_comparison_chain(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            run_dir, manifest_path, manifest, _ = build_run(root, session_count=12)
            converted = converter.convert_run(run_dir, manifest_path)
            gt_path = root / "gt.jsonl"
            gt_path.write_bytes(converted["outputs"]["gt.jsonl"])
            gt = dbq_evaluate.load(gt_path)
            reports = []
            for variant in ("A", "B"):
                pred_path = root / f"predictions-{variant}.jsonl"
                pred_path.write_bytes(converted["outputs"][pred_path.name])
                pred = dbq_evaluate.load(pred_path)
                report = dbq_evaluate.evaluate(gt, pred)
                report["inputSha256"] = {"gt": hashlib.sha256(gt_path.read_bytes()).hexdigest(),
                                          "predictions": hashlib.sha256(pred_path.read_bytes()).hexdigest()}
                reports.append(report)
            clusters = {clip["clipId"]: {"sessionId": f"session-{index}", "group": clip["group"]}
                        for index, clip in enumerate(manifest["clips"])}
            cluster_manifest = {"schemaVersion": 1, "sourceGtSha256": reports[0]["inputSha256"]["gt"],
                                "ordinaryGroups": ["ordinary"], "clips": clusters,
                                "freeze": {"independentCorpusFrozen": False}}
            comparison = dbq_compare.compare(reports[0], reports[1], cluster_manifest,
                                             seed=282401, repetitions=1000)
            ordinary = comparison["groups"]["ordinary"]
            self.assertEqual(ordinary["eligibleFrames"], 12)
            self.assertAlmostEqual(ordinary["pairedRelativeFailureReduction"], 6 / 11)
            self.assertAlmostEqual(ordinary["absoluteSuccessDifferenceBMinusA"], 0.5)
            self.assertEqual(ordinary["relativeIntervalStatus"], "ESTIMATED")
            self.assertEqual(comparison["selection"]["status"], "INCONCLUSIVE")


if __name__ == "__main__":
    unittest.main()
