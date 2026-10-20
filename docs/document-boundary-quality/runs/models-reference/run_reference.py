#!/usr/bin/env python3
"""Host-side reference run of the pinned DocAligner ONNX heatmap models.

This intentionally mirrors docaligner/heatmap_reg/infer.py at upstream
3275b0f07f8e99d8c01cb0774dea2549be1416b6. It is evidence tooling only.
"""
from __future__ import annotations

import hashlib
import json
import platform
from pathlib import Path

import cv2
import numpy as np
import onnx
import onnxruntime as ort
import capybara as cb

UPSTREAM = Path('/tmp/docscan-docaligner-3275b0f')
MODELS = {
    'lc100': Path('/tmp/docscan-lc100.onnx'),
    't8': Path('/tmp/docscan-t8.onnx'),
}
OUT = Path(__file__).resolve().parent


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def array_sha256(array: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(array).tobytes()).hexdigest()


def model_info(path: Path) -> dict:
    model = onnx.load(str(path), load_external_data=False)
    def value_info(v):
        tensor = v.type.tensor_type
        return {
            'name': v.name,
            'dtype': onnx.TensorProto.DataType.Name(tensor.elem_type),
            'shape': [d.dim_value if d.dim_value else (d.dim_param or None)
                      for d in tensor.shape.dim],
        }
    return {
        'file': str(path), 'bytes': path.stat().st_size, 'sha256': sha256(path),
        'ir_version': model.ir_version,
        'opsets': [{'domain': x.domain or 'ai.onnx', 'version': x.version}
                   for x in model.opset_import],
        'inputs': [value_info(x) for x in model.graph.input],
        'outputs': [value_info(x) for x in model.graph.output],
        'node_count': len(model.graph.node),
        'operators': sorted({n.op_type for n in model.graph.node}),
        'initializers': len(model.graph.initializer),
        'producer_name': model.producer_name,
        'producer_version': model.producer_version,
        'metadata_props': {x.key: x.value for x in model.metadata_props},
        'classification_named_graph_entries': sorted({
            name for name in (
                [x.name for x in model.graph.node]
                + [name for node in model.graph.node for name in node.output]
                + [x.name for x in model.graph.input]
                + [x.name for x in model.graph.output]
                + [x.name for x in model.graph.initializer]
            ) if any(token in name.lower() for token in ('class', 'presence', 'has_obj', 'objectness', 'cls'))
        }),
    }


def fixtures() -> dict[str, np.ndarray]:
    h, w = 480, 640
    out: dict[str, np.ndarray] = {}
    card = cv2.imread(str(UPSTREAM / 'docs/run_test_card.jpg'), cv2.IMREAD_COLOR)
    if card is None:
        raise FileNotFoundError(UPSTREAM / 'docs/run_test_card.jpg')
    out['upstream_run_test_card'] = card
    out['negative_white'] = np.full((h, w, 3), 255, np.uint8)
    out['negative_black'] = np.zeros((h, w, 3), np.uint8)
    out['negative_uniform_gray'] = np.full((h, w, 3), 127, np.uint8)
    rng = np.random.default_rng(20261020)
    out['negative_low_contrast_noise'] = np.clip(
        127 + rng.normal(0, 3, (h, w, 3)), 0, 255).astype(np.uint8)

    scene = np.full((h, w, 3), 92, np.uint8)
    paper = np.array([[92, 54], [550, 78], [525, 424], [111, 406]], np.int32)
    cv2.fillConvexPoly(scene, paper, (224, 224, 224))
    cv2.polylines(scene, [paper], True, (35, 35, 35), 4)
    for y in range(120, 370, 22):
        cv2.line(scene, (155, y), (472 - ((y // 22) % 3) * 24, y), (65, 65, 65), 2)
    out['synthetic_document'] = scene

    # A designed flat illustration/poster, not an intended document. This
    # probes whether the model treats a strong quadrilateral as document-like.
    panel = np.full((h, w, 3), (135, 94, 52), np.uint8)
    panel_poly = np.array([[125, 43], [528, 91], [495, 420], [102, 383]], np.int32)
    cv2.fillConvexPoly(panel, panel_poly, (29, 150, 222))
    cv2.polylines(panel, [panel_poly], True, (250, 245, 225), 8)
    cv2.putText(panel, 'GRAPHIC', (188, 210), cv2.FONT_HERSHEY_SIMPLEX,
                1.4, (245, 245, 245), 4, cv2.LINE_AA)
    cv2.rectangle(panel, (180, 250), (435, 298), (44, 60, 220), -1)
    out['negative_graphic_panel'] = panel

    clutter = np.zeros((h, w, 3), np.uint8)
    for y in range(0, h, 24):
        for x in range(0, w, 24):
            if (x // 24 + y // 24) % 2:
                clutter[y:y + 24, x:x + 24] = (220, 220, 220)
    for i in range(10):
        cv2.circle(clutter, (35 + i * 60, 240 + (i % 3) * 25), 18,
                   (30 + (i * 19) % 210,) * 3, -1)
    out['negative_checker_clutter'] = clutter
    return out


def preprocess(image: np.ndarray) -> np.ndarray:
    # capybara.imresize defaults to cv2.INTER_LINEAR; input is BGR as in the
    # upstream usage example after RGB2BGR conversion.
    resized = cv2.resize(image, (256, 256), interpolation=cv2.INTER_LINEAR)
    return np.transpose(resized, (2, 0, 1)).astype(np.float32)[None] / np.float32(255.0)


def postprocess(heatmaps: np.ndarray, size_hw: tuple[int, int]) -> dict:
    # Mirrors heatmap_reg/infer.py: resize, threshold (<0.3=>0), uint8 cast,
    # capybara.imbinarize = cv2.threshold(0, 255, THRESH_BINARY|OTSU),
    # RETR_EXTERNAL + CHAIN_APPROX_SIMPLE, drop contour with <3 vertices,
    # max cv2.moments m00, centroid m10/(m00+1e-5), m01/(m00+1e-5).
    result = []
    channel_stats = []
    h, w = size_hw
    for pred in heatmaps[0]:
        resized = cv2.resize(pred, (w, h), interpolation=cv2.INTER_LINEAR)
        resized[resized < 0.3] = 0
        u8 = np.uint8(resized * 255)
        _, mask = cv2.threshold(u8, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        contours = [c for c in contours if c.shape[0] >= 3]
        if contours:
            areas = [cv2.moments(c)['m00'] for c in contours]
            max_area = max(areas)
            max_area_indices = [i for i, area in enumerate(areas) if area == max_area]
            if len(max_area_indices) == 1:
                m = cv2.moments(contours[max_area_indices[0]])
                point = [m['m10'] / (m['m00'] + 1e-5), m['m01'] / (m['m00'] + 1e-5)]
                result.append(point)
            else:
                # Upstream flattens tied maximum-polygon centroids and accepts
                # only a single [x,y] pair, so tied channels produce no point.
                point = None
        else:
            point = None
        channel_stats.append({
            'max': float(np.max(pred)), 'min': float(np.min(pred)),
            'mean': float(np.mean(pred)), 'fraction_ge_0_3': float(np.mean(pred >= 0.3)),
            'otsu_threshold_u8': int(cv2.threshold(u8, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)[0]),
            'contours_ge_3_vertices': len(contours), 'selected_centroid_xy': point,
        })
    return {'corners_xy': result, 'channel_stats': channel_stats}


def upstream_postprocess(heatmaps: np.ndarray, size_hw: tuple[int, int]) -> list:
    """Call the actual upstream capybara operations as an independent oracle."""
    h, w = size_hw
    corners = []
    for pred in heatmaps[0]:
        pred = cb.imresize(pred, size=(h, w))
        pred[pred < 0.3] = 0
        pred = np.uint8(pred * 255)
        mask = cb.imbinarize(pred)
        polygons = cb.Polygons.from_image(mask).drop_empty()
        if len(polygons) > 0:
            polygons = polygons[polygons.area == polygons.area.max()]
        point = polygons.centroid.flatten().tolist()
        if len(point) == 2:
            corners.append(point)
    return corners


def main() -> None:
    info = {
        'source_revision': '3275b0f07f8e99d8c01cb0774dea2549be1416b6',
        'source_checkout': str(UPSTREAM),
        'runtime': {
            'python': platform.python_version(), 'platform': platform.platform(),
            'numpy': np.__version__, 'opencv': cv2.__version__,
            'onnx': onnx.__version__, 'onnxruntime': ort.__version__,
            'capybara_docsaid': __import__('importlib.metadata').metadata.version('capybara-docsaid'),
            'providers': ort.get_available_providers(),
            'requested_provider': 'CPUExecutionProvider',
        },
        'models': {name: model_info(path) for name, path in MODELS.items()},
        'fixtures': {},
    }
    for fixture_name, image in fixtures().items():
        record = {
            'image_shape_bgr': list(image.shape),
            'decoded_bgr_pixel_sha256': array_sha256(image),
            'source_image_file_sha256': sha256(UPSTREAM / 'docs/run_test_card.jpg')
                if fixture_name == 'upstream_run_test_card' else None,
            'models': {},
        }
        inp = preprocess(image)
        for model_name, path in MODELS.items():
            opts = ort.SessionOptions()
            opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            sess = ort.InferenceSession(str(path), sess_options=opts,
                                        providers=['CPUExecutionProvider'])
            inputs = {x.name: x for x in sess.get_inputs()}
            outputs = sess.run(None, {next(iter(inputs)): inp})
            names = [x.name for x in sess.get_outputs()]
            output_map = dict(zip(names, outputs))
            if 'heatmap' not in output_map:
                raise RuntimeError(f'{model_name}: expected heatmap output, found {names}')
            pred = output_map['heatmap']
            manual_postprocess = postprocess(pred, image.shape[:2])
            capybara_corners = upstream_postprocess(pred, image.shape[:2])
            if not np.allclose(np.asarray(manual_postprocess['corners_xy']),
                               np.asarray(capybara_corners), rtol=0, atol=1e-9):
                raise AssertionError(f'{fixture_name}/{model_name}: OpenCV reproduction differs from upstream capybara')
            record['models'][model_name] = {
                'output_shapes': {k: list(v.shape) for k, v in output_map.items()},
                'session_providers': sess.get_providers(),
                'postprocess': manual_postprocess,
                'capybara_centroid_parity': 'exact within 1e-9 px',
                'raw_output_stats': {k: {
                    'dtype': str(v.dtype), 'min': float(np.min(v)),
                    'max': float(np.max(v)), 'mean': float(np.mean(v)),
                } for k, v in output_map.items()},
            }
        info['fixtures'][fixture_name] = record
    (OUT / 'reference-results.json').write_text(json.dumps(info, indent=2) + '\n')
    print(json.dumps(info, indent=2))


if __name__ == '__main__':
    main()
