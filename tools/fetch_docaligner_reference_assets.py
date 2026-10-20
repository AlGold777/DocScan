#!/usr/bin/env python3
"""Explicitly fetch pinned DocAligner reference test assets (never used by Gradle).

The legacy weight files have unresolved file-specific license scope. This tool
only downloads them when the caller explicitly passes --fetch; it does not
accept license terms, and the assets are ignored by git.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ASSETS = ROOT / "app/src/androidTest/assets"
UPSTREAM_REVISION = "3275b0f07f8e99d8c01cb0774dea2549be1416b6"
ASSETS = {
    "models/lc100.onnx": (
        "1IlbLPkCv-TdaBLOPh_4J97P1_KHYYJ7a",
        "f4117b786e3a18470f3865c93f3c2bd69d9b998edd60f385574a5c665e79594e",
    ),
    "models/t8.onnx": (
        "1W88oOkHjMza6xXbDxrG8k_C-Tf2aYKJ4",
        "950070fd4a46e25d46db7c35d0ed77612d9063f70d4543c7c504f0da6a943a89",
    ),
    "docaligner_run_test_card.jpg": (
        f"https://raw.githubusercontent.com/DocsaidLab/DocAligner/{UPSTREAM_REVISION}/docs/run_test_card.jpg",
        "d7754aeeb8d1c079beafa41a3f5d6b0803fe41d786e9b10a425a941592c2ef59",
    ),
}
PNG_SHA256 = "d6966b60720a13a99b190dafcb8922e82133cb52f279b8f7844b391c208a7a99"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_google_drive(file_id: str) -> bytes:
    """Fetch a public Drive file, following the large-file confirmation token."""
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    base = "https://drive.google.com/uc"
    params = {"export": "download", "id": file_id}

    def get(query: dict[str, str]) -> tuple[bytes, str]:
        url = base + "?" + urllib.parse.urlencode(query)
        request = urllib.request.Request(url, headers={"User-Agent": "DocScan-reference-assets/1"})
        with opener.open(request, timeout=120) as response:
            return response.read(), response.headers.get("Content-Type", "")

    data, content_type = get(params)
    if "text/html" not in content_type.lower():
        return data

    token = next((cookie.value for cookie in jar if cookie.name.startswith("download_warning")), None)
    if token is None:
        html = data.decode("utf-8", errors="replace")
        match = re.search(r"[?&]confirm=([0-9A-Za-z_-]+)", html)
        if match:
            token = match.group(1)
    if token:
        params["confirm"] = token
        data, content_type = get(params)
        if "text/html" not in content_type.lower():
            return data
    raise RuntimeError(
        f"Google Drive returned an HTML confirmation/error page for {file_id}; "
        "check network access or fetch that public file manually."
    )


def write_verified(path: Path, data: bytes, expected_sha: str) -> None:
    actual = sha256(data)
    if actual != expected_sha:
        raise RuntimeError(f"SHA-256 mismatch for {path.name}: expected {expected_sha}, got {actual}")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        existing = path.read_bytes()
        if sha256(existing) != expected_sha:
            raise RuntimeError(f"Refusing to overwrite different local file: {path}")
        return
    path.write_bytes(data)


def fetch(assets_dir: Path) -> None:
    for relative, (source, expected_sha) in ASSETS.items():
        if relative.startswith("models/"):
            payload = read_google_drive(source)
            source_description = f"Google Drive ID {source}"
        else:
            request = urllib.request.Request(source, headers={"User-Agent": "DocScan-reference-assets/1"})
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
            source_description = source
        path = assets_dir / relative
        write_verified(path, payload, expected_sha)
        print(f"verified {relative} sha256={expected_sha} ({source_description})")


def make_fixtures(assets_dir: Path) -> None:
    try:
        import cv2
        import numpy as np
    except ImportError as exc:
        raise RuntimeError("Fixture generation requires NumPy and OpenCV; use the pinned reference venv") from exc

    card_jpg = assets_dir / "docaligner_run_test_card.jpg"
    card = cv2.imread(str(card_jpg), cv2.IMREAD_COLOR)
    if card is None:
        raise RuntimeError(f"Missing or unreadable pinned test card: {card_jpg}; run with --fetch")
    card_png = assets_dir / "docaligner_run_test_card.png"
    cv2.imwrite(str(card_png), card)
    actual_png_sha = hashlib.sha256(card_png.read_bytes()).hexdigest()
    if actual_png_sha != PNG_SHA256:
        card_png.unlink(missing_ok=True)
        raise RuntimeError(
            "PNG encoding does not match the recorded test asset hash; use OpenCV 5.0.0.93 "
            f"(expected {PNG_SHA256}, got {actual_png_sha})"
        )

    h, w = 480, 640
    fixtures: dict[str, object] = {
        "negative_white": np.full((h, w, 3), 255, np.uint8),
        "negative_black": np.zeros((h, w, 3), np.uint8),
        "negative_uniform_gray": np.full((h, w, 3), 127, np.uint8),
    }
    rng = np.random.default_rng(20261020)
    fixtures["negative_low_contrast_noise"] = np.clip(
        127 + rng.normal(0, 3, (h, w, 3)), 0, 255
    ).astype(np.uint8)

    scene = np.full((h, w, 3), 92, np.uint8)
    paper = np.array([[92, 54], [550, 78], [525, 424], [111, 406]], np.int32)
    cv2.fillConvexPoly(scene, paper, (224, 224, 224))
    cv2.polylines(scene, [paper], True, (35, 35, 35), 4)
    for y in range(120, 370, 22):
        cv2.line(scene, (155, y), (472 - ((y // 22) % 3) * 24, y), (65, 65, 65), 2)
    fixtures["synthetic_document"] = scene

    panel = np.full((h, w, 3), (135, 94, 52), np.uint8)
    panel_poly = np.array([[125, 43], [528, 91], [495, 420], [102, 383]], np.int32)
    cv2.fillConvexPoly(panel, panel_poly, (29, 150, 222))
    cv2.polylines(panel, [panel_poly], True, (250, 245, 225), 8)
    cv2.putText(panel, "GRAPHIC", (188, 210), cv2.FONT_HERSHEY_SIMPLEX,
                1.4, (245, 245, 245), 4, cv2.LINE_AA)
    cv2.rectangle(panel, (180, 250), (435, 298), (44, 60, 220), -1)
    fixtures["negative_graphic_panel"] = panel

    clutter = np.zeros((h, w, 3), np.uint8)
    for y in range(0, h, 24):
        for x in range(0, w, 24):
            if (x // 24 + y // 24) % 2:
                clutter[y:y + 24, x:x + 24] = (220, 220, 220)
    for i in range(10):
        cv2.circle(clutter, (35 + i * 60, 240 + (i % 3) * 25), 18,
                   (30 + (i * 19) % 210,) * 3, -1)
    fixtures["negative_checker_clutter"] = clutter

    for name, image in fixtures.items():
        path = assets_dir / f"{name}.png"
        if not cv2.imwrite(str(path), image):
            raise RuntimeError(f"Unable to write fixture: {path}")
    print(f"generated {len(fixtures) + 1} PNG fixtures; test card sha256={actual_png_sha}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-dir", type=Path, default=DEFAULT_ASSETS)
    parser.add_argument("--fetch", action="store_true", help="explicitly download the two pinned legacy models and pinned upstream JPEG")
    parser.add_argument("--generate-fixtures", action="store_true", help="generate ignored PNG fixtures using local OpenCV/NumPy")
    args = parser.parse_args()
    if not args.fetch and not args.generate_fixtures:
        parser.error("nothing requested; pass --fetch and/or --generate-fixtures explicitly")
    if args.fetch:
        fetch(args.assets_dir)
    if args.generate_fixtures:
        make_fixtures(args.assets_dir)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
