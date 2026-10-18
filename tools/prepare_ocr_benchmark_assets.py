#!/usr/bin/env python3
"""Fetch pinned test-only models; never installs models in the production APK."""
from pathlib import Path
import argparse
import hashlib
import urllib.request
import shutil
import subprocess
import tempfile
import zipfile
import json

COMMIT = 'e12c65a915945e4c28e237a9b52bc4a8f39a0cec'
MODELS = {
    'eng': '8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba',
    'rus': 'b617eb6830ffabaaa795dd87ea7fd251adfe9cf0efe05eb9a2e8128b7728d6b6',
    'spa': 'e2c1ffdad8b30f26c45d4017a9183d3a7f9aa69e59918be4f88b126fac99ab2c',
}
def download(url, destination):
    # Use the OS certificate store through curl on macOS Python installations without CA bundles.
    if shutil.which('curl'):
        subprocess.run(['curl', '--fail', '--location', '--retry', '2', '--silent', '--show-error',
                        url, '--output', str(destination)], check=True)
    else:
        urllib.request.urlretrieve(url, destination)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project', type=Path, default=Path(__file__).resolve().parents[1])
parser.add_argument('--with-real-scans', action='store_true', help='Also prepare 10 pinned FUNSD test scans')
args = parser.parse_args()
root = args.project / 'app/src/androidTest/assets/tessdata-best'
root.mkdir(parents=True, exist_ok=True)
for language, expected in MODELS.items():
    destination = root / f'{language}.traineddata'
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() == expected:
        print(f'{language}: verified cached test model')
        continue
    temporary = destination.with_suffix('.downloading')
    try:
        url = f'https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/{COMMIT}/{language}.traineddata'
        download(url, temporary)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            raise ValueError(f'Checksum mismatch for {language}')
        temporary.replace(destination)
        print(f'{language}: verified {destination.stat().st_size} bytes')
    finally:
        temporary.unlink(missing_ok=True)

if args.with_real_scans:
    funsd_commit = '8905aca92b3181853307e2880e3d6f71dee8e9f3'
    expected_archive = 'c31735649e4f441bcbb4fd0f379574f7520b42286e80b01d80b445649d54761f'
    corpus = args.project / 'app/src/androidTest/assets/funsd'
    corpus.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='docscan-funsd-') as temporary_directory:
        archive_path = Path(temporary_directory) / 'dataset.zip'
        download(f'https://raw.githubusercontent.com/guillaumejaume/FUNSD/{funsd_commit}/dataset.zip', archive_path)
        if hashlib.sha256(archive_path.read_bytes()).hexdigest() != expected_archive:
            raise ValueError('FUNSD archive checksum mismatch')
        manifest = []
        with zipfile.ZipFile(archive_path) as archive:
            names = sorted(name for name in archive.namelist()
                           if name.startswith('dataset/testing_data/annotations/') and name.endswith('.json'))[:10]
            if len(names) != 10:
                raise ValueError('Expected ten FUNSD testing pages')
            for name in names:
                stem = Path(name).stem
                if not all(character.isalnum() or character == '_' for character in stem):
                    raise ValueError('Unexpected dataset filename')
                item = {'id': stem}
                for folder, extension in [('annotations', 'json'), ('images', 'png')]:
                    content = archive.read(f'dataset/testing_data/{folder}/{stem}.{extension}')
                    (corpus / f'{stem}.{extension}').write_bytes(content)
                    item[f'{extension}_sha256'] = hashlib.sha256(content).hexdigest()
                manifest.append(item)
        (corpus / 'manifest.json').write_text(json.dumps({
            'source': 'https://guillaumejaume.github.io/FUNSD/', 'commit': funsd_commit,
            'archive_sha256': expected_archive, 'pages': manifest,
        }, indent=2) + '\n')
        print('Prepared ten public FUNSD scans for the test APK only')
