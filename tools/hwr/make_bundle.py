#!/usr/bin/env python3
"""Package an export_model.py output directory into a Notesprout model bundle.

The bundle is a zip the app imports via SAF. Layout (flat):
    manifest.json
    encoder_model.onnx
    decoder_model.onnx            # step 1: input_ids + encoder_hidden_states -> logits + all present.*
    decoder_with_past_model.onnx  # steps 2+: last token + past_key_values.* -> logits + present.*.decoder.*
    tokenizer.json

manifest.json carries per-file SHA-256; the app verifies every hash and runs a
smoke decode before activating a bundle. Field names must stay in sync with the
Kotlin TrOcrManifest (recognition/trocr/TrOcrManifest.kt).

Usage:
    python make_bundle.py --export build/base --name "TrOCR small handwritten" --out build/
    python make_bundle.py --export build/personal --name "Greg v1" --personalized --out build/
"""

import argparse
import hashlib
import json
import time
import zipfile
from pathlib import Path

MANIFEST_SCHEMA = 1


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def pick(export: Path, stem: str, precision: str) -> Path:
    """Choose the int8 or fp32 artifact for a model stem (e.g. 'encoder_model')."""
    name = f"{stem}_int8.onnx" if precision == "int8" else f"{stem}.onnx"
    p = export / name
    if not p.exists():
        raise SystemExit(f"no {precision} {stem} model found in {export}")
    return p


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True, help="export_model.py output dir")
    ap.add_argument("--name", required=True, help="human-readable bundle name")
    ap.add_argument("--out", required=True, help="output dir for the zip")
    ap.add_argument("--personalized", action="store_true")
    ap.add_argument("--encoder-precision", choices=["int8", "fp32"], default="int8")
    ap.add_argument("--decoder-precision", choices=["int8", "fp32"], default="int8")
    args = ap.parse_args()

    export = Path(args.export)
    config = json.loads((export / "hwr_config.json").read_text())

    encoder = pick(export, "encoder_model", args.encoder_precision)
    decoder_init = pick(export, "decoder_model", args.decoder_precision)
    decoder_past = pick(export, "decoder_with_past_model", args.decoder_precision)
    tokenizer = export / "tokenizer.json"
    if not tokenizer.exists():
        raise SystemExit(f"missing {tokenizer}")

    # Stable in-bundle names regardless of source precision suffixes.
    contents = {
        "encoder_model.onnx": encoder,
        "decoder_model.onnx": decoder_init,
        "decoder_with_past_model.onnx": decoder_past,
        "tokenizer.json": tokenizer,
    }

    stamp = time.strftime("%Y%m%d-%H%M%S")
    kind = "personal" if args.personalized else "base"
    version_id = f"{kind}-{stamp}"

    manifest = {
        "schema": MANIFEST_SCHEMA,
        "name": args.name,
        "versionId": version_id,
        "createdAt": int(time.time() * 1000),
        "personalized": args.personalized,
        "quantization": {
            "encoder": args.encoder_precision,
            "decoder": args.decoder_precision,
        },
        # runtime config recorded by export_model.py from the HF configs
        **config,
        "files": {name: sha256(path) for name, path in contents.items()},
    }

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    zip_path = out_dir / f"trocr-small-hw-{version_id}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        for name, path in contents.items():
            z.write(path, name)

    print(f"bundle: {zip_path}  ({zip_path.stat().st_size / (1 << 20):.1f} MB)")
    print(f"versionId: {version_id}")


if __name__ == "__main__":
    main()
