#!/usr/bin/env python3
"""Prepare checksum-pinned models for a complete, offline Android APK."""
import argparse
import hashlib
import json
import shutil
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
MODELS = ASSETS / 'offline_models'
CACHE = ROOT / '.offline-model-cache'
RELEASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/'
EXTRA = {
    'diarization/reverb/model.onnx': (9512223, '8249e2e323f8fb0566a387d94b40a73d0b75d54ee60d02dcd729a56a5ba8ecea'),
    'diarization/eres2netv2.onnx': (71441526, 'bf1a75b9930474cf3389ef415e6e5d38ca96fea4a3a00f7e301d080a58ee2239'),
    'enrollment/titanet-small.onnx': (40257283, 'ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e'),
}

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def check(path, size, sha):
    return path.is_file() and path.stat().st_size == size and digest(path) == sha

def download(url, target):
    if target.is_file():
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_suffix(target.suffix + '.part')
    try:
        with urllib.request.urlopen(url, timeout=180) as src, temp.open('wb') as out:
            shutil.copyfileobj(src, out)
        temp.replace(target)
    finally:
        temp.unlink(missing_ok=True)
    return target

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--gigaam-archive', help='Existing models ZIP path or HTTPS download URL')
    group.add_argument('--gigaam-parts', help='Directory with android_models.part01 through part07')
    args = parser.parse_args()
    CACHE.mkdir(exist_ok=True)
    if args.gigaam_parts:
        archive = CACHE / 'gigaam.zip'
        parts = [Path(args.gigaam_parts) / f'android_models.part{i:02}' for i in range(1, 8)]
        if not all(p.is_file() for p in parts):
            raise SystemExit('All seven parts are required')
        with archive.open('wb') as out:
            for part in parts:
                with part.open('rb') as src:
                    shutil.copyfileobj(src, out)
    elif args.gigaam_archive.startswith('https://'):
        archive = download(args.gigaam_archive, CACHE / 'gigaam.zip')
    else:
        archive = Path(args.gigaam_archive)
    expected = json.loads((ASSETS / 'model_manifest.json').read_text())
    with zipfile.ZipFile(archive) as z:
        for name, spec in expected.items():
            target = MODELS / name
            if not check(target, spec['size'], spec['sha256']):
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(name) as src, target.open('wb') as out:
                    shutil.copyfileobj(src, out)
            if not check(target, spec['size'], spec['sha256']):
                raise SystemExit(f'GigaAM checksum mismatch: {name}')
    reverb = download(RELEASE + 'speaker-segmentation-models/sherpa-onnx-reverb-diarization-v1.tar.bz2', CACHE / 'reverb.tar.bz2')
    if digest(reverb) != '615761e980be1688da0ef81618c056134d63aa55ea0a5f1494c47393b9398eab':
        raise SystemExit('Reverb archive checksum mismatch')
    with tarfile.open(reverb, 'r:bz2') as tar:
        for name in ('model.onnx', 'LICENSE'):
            target = MODELS / 'diarization/reverb' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile('sherpa-onnx-reverb-diarization-v1/' + name) as src, target.open('wb') as out:
                shutil.copyfileobj(src, out)
    (MODELS / 'diarization/reverb/NOTICE').write_text('Licensed by Rev under the Rev Model Non-Production License.')
    download(RELEASE + 'speaker-recongition-models/3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx', MODELS / 'diarization/eres2netv2.onnx')
    download(RELEASE + 'speaker-recongition-models/nemo_en_titanet_small.onnx', MODELS / 'enrollment/titanet-small.onnx')
    for name, (size, sha) in EXTRA.items():
        if not check(MODELS / name, size, sha):
            raise SystemExit(f'Model checksum mismatch: {name}')
    manifest = {str(p.relative_to(MODELS)): {'size': p.stat().st_size, 'sha256': digest(p)}
                for p in sorted(MODELS.rglob('*')) if p.is_file()}
    (ASSETS / 'bundled_model_manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(f'Offline assets ready: {len(manifest)} files, {sum(v["size"] for v in manifest.values())} bytes')

if __name__ == '__main__':
    main()
