import copy
import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import dbq_compare


GT_HASH = hashlib.sha256(b"frozen synthetic GT identity").hexdigest()


def fixture(session_count=12, frames_per_session=1, group="ordinary"):
    clips = {}
    frames_a, frames_b = [], []
    for session in range(session_count):
        clip_id = f"clip-{session}"
        clips[clip_id] = {"sessionId": f"session-{session}", "group": group}
        for frame in range(frames_per_session):
            fail_a = session < session_count // 2
            fail_b = session < max(0, session_count // 5)
            identity = {"clipId": clip_id, "frameId": f"frame-{frame}"}
            frames_a.append({**identity, "success": not fail_a, "reason": "missing" if fail_a else None,
                             "geometry": None})
            frames_b.append({**identity, "success": not fail_b, "reason": "invalid" if fail_b else None,
                             "geometry": None})
    def report(frames):
        failed = sum(not row["success"] for row in frames)
        missing = sum(row["reason"] == "missing" for row in frames)
        invalid = sum(row["reason"] == "invalid" for row in frames)
        return {"schemaVersion": 1, "inputSha256": {"gt": GT_HASH}, "frames": frames,
                "groups": {group: {"eligible": len(frames), "success": len(frames) - failed,
                                   "missing": missing, "invalid": invalid, "clips": session_count}}}
    manifest = {"schemaVersion": 1, "sourceGtSha256": GT_HASH, "clips": clips,
                "ordinaryGroups": [group],
                "freeze": {"independentCorpusFrozen": False}}
    return report(frames_a), report(frames_b), manifest


class DbqCompareTests(unittest.TestCase):
    def test_known_paired_effect_and_frame_weighted_denominator(self):
        a, b, manifest = fixture(session_count=10, frames_per_session=2)
        result = dbq_compare.compare(a, b, manifest, seed=17, repetitions=1000)
        group = result["groups"]["ordinary"]
        self.assertEqual(group["eligibleFrames"], 20)
        self.assertEqual(group["failureA"], 10)
        self.assertEqual(group["failureB"], 4)
        self.assertAlmostEqual(group["pairedRelativeFailureReduction"], 0.6)
        self.assertAlmostEqual(group["absoluteSuccessDifferenceBMinusA"], 0.3)
        self.assertEqual(group["independentSessions"], 10)
        self.assertEqual(group["nonInferiorityMargin"], -0.02)

    def test_identity_effect_is_zero_and_never_selects_winner(self):
        a, _, manifest = fixture()
        result = dbq_compare.compare(a, a, manifest, seed=23, repetitions=500)
        group = result["groups"]["ordinary"]
        self.assertEqual(group["pairedRelativeFailureReduction"], 0.0)
        self.assertEqual(group["absoluteSuccessDifferenceBMinusA"], 0.0)
        self.assertEqual(result["selection"], {"status": "INCONCLUSIVE", "winner": None,
                                               "reasons": ["independent corpus freeze is not attested",
                                                           "predeclared CI plan is absent",
                                                           "hardware gates are absent",
                                                           "resource gates are absent",
                                                           "this paired geometry harness does not evaluate all absolute AC, hardware, or resource gates",
                                                           "winner selection requires a separate evidence-backed decision record"]})

    def test_failure_a_zero_has_unavailable_reduction(self):
        a, b, manifest = fixture()
        for row in a["frames"]:
            row["success"] = True
            row["reason"] = None
        a["groups"]["ordinary"].update(success=12, missing=0, invalid=0)
        result = dbq_compare.compare(a, b, manifest, seed=7, repetitions=500)
        metric = result["groups"]["ordinary"]
        self.assertIsNone(metric["pairedRelativeFailureReduction"])
        self.assertIsNone(metric["pairedRelativeFailureReductionCI95TwoSided"])
        self.assertEqual(metric["intervalStatus"], "INCONCLUSIVE")

    def test_geometric_threshold_miss_without_reason_still_counts_as_failure(self):
        a, b, manifest = fixture()
        a["frames"][0]["reason"] = None  # valid geometry can still miss IoU/Ecorner thresholds
        a["groups"]["ordinary"]["missing"] -= 1
        result = dbq_compare.compare(a, b, manifest, seed=5, repetitions=300)
        self.assertEqual(result["groups"]["ordinary"]["failureA"], 6)
        self.assertEqual(result["groups"]["ordinary"]["eligibleFrames"], 12)

    def test_mismatched_frame_keys_fail_closed(self):
        a, b, manifest = fixture()
        b["frames"].pop()
        b["groups"]["ordinary"].update(eligible=11, success=9, missing=2, clips=11)
        with self.assertRaisesRegex(dbq_compare.ComparisonError, "frame key mismatch"):
            dbq_compare.compare(a, b, manifest)

    def test_ground_truth_hash_mismatch_fails_closed(self):
        a, b, manifest = fixture()
        b["inputSha256"]["gt"] = "0" * 64
        with self.assertRaisesRegex(dbq_compare.ComparisonError, "GT hash"):
            dbq_compare.compare(a, b, manifest)

    def test_session_clusters_are_counted_instead_of_adjacent_frames(self):
        a, b, manifest = fixture(session_count=10, frames_per_session=40)
        result = dbq_compare.compare(a, b, manifest, seed=11, repetitions=400)
        group = result["groups"]["ordinary"]
        self.assertEqual(group["eligibleFrames"], 400)
        self.assertEqual(group["independentSessions"], 10)
        self.assertEqual(result["counts"]["independentSessions"], 10)

    def test_seed_and_repetitions_make_bootstrap_deterministic(self):
        a, b, manifest = fixture()
        first = dbq_compare.compare(a, b, manifest, seed=91, repetitions=600)
        second = dbq_compare.compare(a, b, manifest, seed=91, repetitions=600)
        self.assertEqual(first, second)

    def test_tiny_session_count_is_inconclusive(self):
        a, b, manifest = fixture(session_count=4)
        result = dbq_compare.compare(a, b, manifest, seed=2, repetitions=300)
        group = result["groups"]["ordinary"]
        self.assertEqual(group["intervalStatus"], "INCONCLUSIVE")
        self.assertTrue(any("only 4 independent sessions" in reason for reason in group["intervalReasons"]))

    def test_empty_and_null_success_fail_closed(self):
        a, b, manifest = fixture()
        a["frames"] = []
        b["frames"] = []
        for report in (a, b):
            report["groups"]["ordinary"].update(eligible=0, success=0, missing=0, invalid=0, clips=0)
        with self.assertRaisesRegex(dbq_compare.ComparisonError, "no eligible paired frames"):
            dbq_compare.compare(a, b, manifest)
        a, b, manifest = fixture()
        a["frames"][0]["success"] = None
        with self.assertRaisesRegex(dbq_compare.ComparisonError, "explicit boolean"):
            dbq_compare.compare(a, b, manifest)

    def test_duplicate_frame_or_manifest_identity_fails_closed(self):
        a, b, manifest = fixture()
        a["frames"].append(copy.deepcopy(a["frames"][0]))
        with self.assertRaisesRegex(dbq_compare.ComparisonError, "duplicate eligible frame key"):
            dbq_compare.compare(a, b, manifest)
        a, b, manifest = fixture()
        del manifest["clips"]["clip-0"]
        with self.assertRaisesRegex(dbq_compare.ComparisonError, "clip missing from manifest"):
            dbq_compare.compare(a, b, manifest)
        a, b, manifest = fixture()
        manifest["clips"]["clip-0"]["sessionId"] = None
        with self.assertRaisesRegex(dbq_compare.ComparisonError, "missing sessionId"):
            dbq_compare.compare(a, b, manifest)


if __name__ == "__main__":
    unittest.main()
