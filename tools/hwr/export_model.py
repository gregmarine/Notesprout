#!/usr/bin/env python3
"""Export trocr-small-handwritten to ONNX (encoder + merged decoder-with-past),
quantize to dynamic int8, and emit hwr_config.json + tokenizer.json.

Everything the Android side needs at runtime (token ids, image normalization,
vocab size) is read from the HF configs here and written to hwr_config.json —
never hardcoded on either side.

Usage:
    python export_model.py --out build/base
    python export_model.py --model <path-to-merged-finetuned-model> --out build/personal
"""

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def human(nbytes: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if nbytes < 1024 or unit == "GB":
            return f"{nbytes:.1f} {unit}" if unit != "B" else f"{nbytes} B"
        nbytes /= 1024
    return f"{nbytes:.1f} GB"


def export_onnx(model_id: str, out_dir: Path) -> None:
    """optimum-cli export with decoder KV-cache (merged decoder)."""
    cmd = [
        sys.executable, "-m", "optimum.exporters.onnx",
        "--model", model_id,
        "--task", "image-to-text-with-past",
        str(out_dir),
    ]
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)


def quantize(onnx_path: Path) -> Path:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    out = onnx_path.with_name(onnx_path.stem + "_int8.onnx")
    print(f"quantizing {onnx_path.name} -> {out.name}")
    quantize_dynamic(
        model_input=str(onnx_path),
        model_output=str(out),
        weight_type=QuantType.QInt8,
        per_channel=True,
        # MatMul = attention/FFN/lm_head weights; Gather = the token-embedding table
        # (65 MB fp32 in the decoder — the single biggest weight, and it duplicates
        # across the decoder pair). Everything else stays fp32.
        op_types_to_quantize=["MatMul", "Gather"],
    )
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="microsoft/trocr-small-handwritten",
                    help="HF model id or local path (e.g. a merged fine-tuned model)")
    ap.add_argument("--out", required=True, help="output directory")
    ap.add_argument("--skip-quant", action="store_true")
    args = ap.parse_args()

    from transformers import AutoImageProcessor, AutoTokenizer, VisionEncoderDecoderModel

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    # --- 1. ONNX export -----------------------------------------------------
    export_onnx(args.model, out_dir)

    # We ship the UNMERGED decoder pair (decoder_model.onnx for step 1, which also
    # computes the fixed cross-attention KV; decoder_with_past_model.onnx for steps 2+).
    # The merged variant wraps both branches in an ONNX `If` subgraph that
    # quantize_dynamic does not traverse — its "int8" file stays fp32-sized.
    encoder = out_dir / "encoder_model.onnx"
    decoder_init = out_dir / "decoder_model.onnx"
    decoder_past = out_dir / "decoder_with_past_model.onnx"
    for f in (encoder, decoder_init, decoder_past):
        if not f.exists():
            sys.exit(f"export incomplete: missing {f.name} in {out_dir}")

    # --- 2. int8 dynamic quantization --------------------------------------
    artifacts = {"encoder_fp32": encoder, "decoder_init_fp32": decoder_init,
                 "decoder_past_fp32": decoder_past}
    if not args.skip_quant:
        artifacts["encoder_int8"] = quantize(encoder)
        artifacts["decoder_init_int8"] = quantize(decoder_init)
        artifacts["decoder_past_int8"] = quantize(decoder_past)

    # --- 3. tokenizer.json + hwr_config.json -------------------------------
    tok = AutoTokenizer.from_pretrained(args.model, use_fast=True)
    with tempfile.TemporaryDirectory() as td:
        tok.save_pretrained(td)
        tj = Path(td) / "tokenizer.json"
        if not tj.exists():
            sys.exit("fast tokenizer did not produce tokenizer.json")
        shutil.copy(tj, out_dir / "tokenizer.json")

    model = VisionEncoderDecoderModel.from_pretrained(args.model)
    proc = AutoImageProcessor.from_pretrained(args.model)
    dec_cfg = model.config.decoder
    gen = model.generation_config

    def first(*vals):
        return next((v for v in vals if v is not None), None)

    size = proc.size
    image_size = size.get("height") or size.get("shortest_edge")
    config = {
        "baseModel": args.model,
        "imageSize": image_size,
        "imageMean": list(proc.image_mean),
        "imageStd": list(proc.image_std),
        "vocabSize": dec_cfg.vocab_size,
        "decoderStartTokenId": first(gen.decoder_start_token_id,
                                     model.config.decoder_start_token_id,
                                     dec_cfg.decoder_start_token_id),
        "bosTokenId": first(gen.bos_token_id, dec_cfg.bos_token_id),
        "eosTokenId": first(gen.eos_token_id, dec_cfg.eos_token_id),
        "padTokenId": first(gen.pad_token_id, dec_cfg.pad_token_id),
        # HF's generation config says 20 for this model — far too short for a full
        # written line. The app treats this as its decode cap, so floor it at 96.
        "maxLength": max(first(gen.max_length, 0) or 0, 96),
        "numDecoderLayers": dec_cfg.decoder_layers,
        "numDecoderHeads": dec_cfg.decoder_attention_heads,
        "decoderHiddenSize": dec_cfg.d_model,
        "tokenizerClass": type(tok).__name__,
    }
    missing = [k for k, v in config.items() if v is None]
    if missing:
        sys.exit(f"could not resolve config values: {missing}")
    (out_dir / "hwr_config.json").write_text(json.dumps(config, indent=2))

    # --- 4. report ----------------------------------------------------------
    print("\n=== export complete ===")
    print(json.dumps(config, indent=2))
    for name, path in artifacts.items():
        print(f"{name:14s} {human(path.stat().st_size):>10s}  {path.name}")
    print(f"{'tokenizer':14s} {human((out_dir / 'tokenizer.json').stat().st_size):>10s}  tokenizer.json")


if __name__ == "__main__":
    main()
