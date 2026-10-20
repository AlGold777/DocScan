"""Unit tests for fail-closed behavior of the geometry-only DBQ evaluator."""
import json
import tempfile
import unittest
from pathlib import Path

try:
    from .dbq_evaluate import evaluate, geometry, load
except ImportError:  # Running this file directly from the repository root.
    from dbq_evaluate import evaluate, geometry, load


RECT = [[10, 20], [110, 20], [110, 220], [10, 220]]


class DbqEvaluateTest(unittest.TestCase):
    def test_identity_has_perfect_iou_corner_and_side_error(self):
        result = geometry(RECT, RECT)
        self.assertAlmostEqual(1.0, result["iou"])
        self.assertEqual(0.0, result["cornerError"])
        self.assertEqual(0.0, result["maxSideError"])

    def test_polygon_intersection_uses_area_not_bounding_boxes(self):
        shifted = [[60, 20], [160, 20], [160, 220], [60, 220]]
        result = geometry(shifted, RECT)
        self.assertAlmostEqual(1.0 / 3.0, result["iou"], places=9)
        self.assertAlmostEqual(0.5, result["maxSideError"], places=9)

    def test_cyclic_start_and_reversed_winding_are_equivalent(self):
        for candidate in (
            [RECT[2], RECT[3], RECT[0], RECT[1]],
            list(reversed(RECT)),
            [RECT[1], RECT[0], RECT[3], RECT[2]],
        ):
            with self.subTest(candidate=candidate):
                result = geometry(candidate, RECT)
                self.assertAlmostEqual(1.0, result["iou"])
                self.assertEqual(0.0, result["cornerError"])
                self.assertEqual(0.0, result["maxSideError"])

    def test_missing_and_invalid_predictions_are_counted_as_failures(self):
        ground_truth = {
            ("clip-a", 1): {"clipId": "clip-a", "frameId": 1, "group": "page", "eligible": True, "quad": RECT},
            ("clip-a", 2): {"clipId": "clip-a", "frameId": 2, "group": "page", "eligible": True, "quad": RECT},
        }
        predictions = {
            ("clip-a", 1): {"clipId": "clip-a", "frameId": 1, "quad": [[0, 0], [1, 1], [0, 1], [1, 0]]},
        }

        result = evaluate(ground_truth, predictions)

        page = result["groups"]["page"]
        self.assertEqual(2, page["eligible"])
        self.assertEqual(0, page["success"])
        self.assertEqual(1, page["invalid"])
        self.assertEqual(1, page["missing"])
        self.assertEqual(["invalid", "missing"], [frame["reason"] for frame in result["frames"]])

    def test_excluded_frames_do_not_enter_success_rate_denominator(self):
        ground_truth = {
            ("clip-a", 1): {"clipId": "clip-a", "frameId": 1, "group": "page", "eligible": True, "quad": RECT},
            ("clip-a", 2): {"clipId": "clip-a", "frameId": 2, "group": "page", "eligible": False, "quad": None},
        }
        result = evaluate(ground_truth, {("clip-a", 1): {"quad": RECT}})
        page = result["groups"]["page"]
        self.assertEqual(1, page["eligible"])
        self.assertEqual(1, page["success"])
        self.assertEqual(1, page["excluded"])
        self.assertEqual(1.0, page["successRate"])
        self.assertEqual("INCONCLUSIVE", page["acceptance"])

    def test_empty_eligible_denominator_and_acceptance_remain_inconclusive(self):
        row = {"clipId": "clip-a", "frameId": 1, "group": "excluded", "eligible": False, "quad": None}
        result = evaluate({("clip-a", 1): row}, {})
        group = result["groups"]["excluded"]
        self.assertEqual(0, group["eligible"])
        self.assertIsNone(group["successRate"])
        self.assertEqual("INCONCLUSIVE", group["acceptance"])
        self.assertEqual("NOT_RUN", result["physicalAcceptance"])
        self.assertIsNone(result["confirmedPrecision"])

    def test_invalid_ground_truth_fails_closed_instead_of_becoming_a_success(self):
        invalid = [[0, 0], [1, 1], [0, 1], [1, 0]]
        row = {"clipId": "clip-a", "frameId": 1, "group": "page", "eligible": True, "quad": invalid}
        with self.assertRaises(ValueError):
            evaluate({("clip-a", 1): row}, {("clip-a", 1): {"quad": invalid}})

    def test_prediction_for_nonexistent_frame_is_reported_as_extra(self):
        result = evaluate({}, {("clip-x", 12): {"clipId": "clip-x", "frameId": 12, "quad": RECT}})
        self.assertEqual(1, result["extraPredictions"])
        self.assertEqual({}, result["groups"])

    def test_loader_rejects_duplicate_clip_frame_keys(self):
        row = {"clipId": "clip-a", "frameId": 1, "quad": RECT}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "predictions.jsonl"
            path.write_text(json.dumps(row) + "\n" + json.dumps(row) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate frame key"):
                load(path)


if __name__ == "__main__":
    unittest.main()
