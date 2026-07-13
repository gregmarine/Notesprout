#!/usr/bin/env python3
"""LoRA fine-tune of trocr-small-handwritten on a Notesprout training bundle.

Input: the zip exported from the app (Settings → Handwriting Recognition →
Export training data…). Runs on Apple Silicon (MPS) or CPU. Nothing uploads
anywhere — the bundle and the model stay on this machine.

Augmentation re-rasterizes each pair from its RAW STROKES (mirroring the app's
LineRasterizer: uniform scale into a 128-px-high band, 8% padding, stroke width
clamped to [1.5, 4.5]px, then a non-aspect resize to 384x384 at model input),
jittering thickness/slant/position per epoch. The shipped PNGs are used for the
held-out evaluation split so eval matches on-device rendering exactly.

Output: a merged Hugging Face model directory. Then produce the app bundle:

    python export_model.py --model build/personal-merged --out build/personal
    python make_bundle.py --export build/personal --name "Personal v1" --personalized --out build/

Usage:
    python finetune.py --bundle notesprout-hwr-train-*.zip --out build/personal-merged \
        [--epochs 12] [--rank 8] [--holdout 0.15]
"""

import argparse
import io
import json
import math
import random
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

BASE_MODEL = "microsoft/trocr-small-handwritten"
EXPECTED_RASTERIZER_VERSION = 1

# Mirror of LineRasterGeometry (keep in sync with the Kotlin side)
RENDER_HEIGHT = 128
PAD_FRAC = 0.08
MAX_RENDER_WIDTH = 3072
MIN_STROKE_PX, MAX_STROKE_PX = 1.5, 4.5


def load_bundle(path: Path):
    with zipfile.ZipFile(path) as z:
        meta = json.loads(z.read("meta.json"))
        if meta.get("rasterizerVersion") != EXPECTED_RASTERIZER_VERSION:
            sys.exit(f"bundle rasterizerVersion {meta.get('rasterizerVersion')} != "
                     f"{EXPECTED_RASTERIZER_VERSION} — update finetune.py's mirror first")
        rows = [json.loads(line) for line in z.read("labels.jsonl").decode().splitlines() if line.strip()]
        pairs = []
        for row in rows:
            pid = row["id"]
            strokes = json.loads(z.read(f"strokes/{pid}.json"))
            png = Image.open(io.BytesIO(z.read(f"pairs/{pid}.png"))).convert("RGB")
            pairs.append({"id": pid, "label": row["label"], "strokes": strokes, "png": png})
        return meta, pairs


def rasterize(strokes, thickness_scale=1.0, slant_deg=0.0, jitter_px=0.0):
    """Python mirror of LineRasterizer.renderLineBitmap with augmentation knobs."""
    pts_all = [(p["x"], p["y"]) for s in strokes for p in s["points"]]
    if not pts_all:
        return Image.new("RGB", (64, RENDER_HEIGHT), "white")
    xs, ys = zip(*pts_all)
    left, top, right, bottom = min(xs), min(ys), max(xs), max(ys)
    ch, cw = max(bottom - top, 1.0), max(right - left, 1.0)
    pad = ch * PAD_FRAC
    scale = RENDER_HEIGHT / (ch + 2 * pad)
    width = max(1, round((cw + 2 * pad) * scale))
    if width > MAX_RENDER_WIDTH:
        scale *= MAX_RENDER_WIDTH / width
        width = MAX_RENDER_WIDTH
    scaled_h = (ch + 2 * pad) * scale
    dy = (RENDER_HEIGHT - scaled_h) / 2 - (top - pad) * scale
    dx = -(left - pad) * scale

    slant = math.tan(math.radians(slant_deg))
    img = Image.new("RGB", (width, RENDER_HEIGHT), "white")
    draw = ImageDraw.Draw(img)
    for s in strokes:
        w = float(s.get("strokeWidth", 3.0)) * scale * thickness_scale
        w = max(MIN_STROKE_PX, min(MAX_STROKE_PX, w))
        pts = []
        for p in s["points"]:
            x = p["x"] * scale + dx
            y = p["y"] * scale + dy
            x += (RENDER_HEIGHT / 2 - y) * slant  # shear around the vertical center
            if jitter_px:
                x += random.uniform(-jitter_px, jitter_px)
                y += random.uniform(-jitter_px, jitter_px)
            pts.append((x, y))
        if len(pts) == 1:
            x, y = pts[0]
            draw.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill="black")
        else:
            draw.line(pts, fill="black", width=max(1, round(w)), joint="curve")
    return img


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--out", required=True, help="merged model output dir")
    ap.add_argument("--epochs", type=int, default=12)
    ap.add_argument("--rank", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--holdout", type=float, default=0.15)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()

    import torch
    from jiwer import cer
    from peft import LoraConfig, get_peft_model
    from transformers import AutoImageProcessor, AutoTokenizer, VisionEncoderDecoderModel

    random.seed(args.seed)
    torch.manual_seed(args.seed)

    meta, pairs = load_bundle(Path(args.bundle))
    if len(pairs) < 8:
        sys.exit(f"only {len(pairs)} pairs — collect more (enrollment gives 16) before fine-tuning")
    random.shuffle(pairs)
    n_hold = max(2, int(len(pairs) * args.holdout))
    holdout, train = pairs[:n_hold], pairs[n_hold:]
    print(f"pairs: {len(train)} train / {len(holdout)} held-out (of {len(pairs)})")

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    model = VisionEncoderDecoderModel.from_pretrained(BASE_MODEL).to(device)
    processor = AutoImageProcessor.from_pretrained(BASE_MODEL)
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model.config.decoder_start_token_id = model.generation_config.decoder_start_token_id or tokenizer.cls_token_id
    model.config.pad_token_id = tokenizer.pad_token_id

    def pixel_values(img):
        return processor(images=img, return_tensors="pt").pixel_values[0]

    @torch.no_grad()
    def evaluate(tag):
        model.eval()
        hyps, refs = [], []
        for p in holdout:
            pv = pixel_values(p["png"]).unsqueeze(0).to(device)
            ids = model.generate(pv, max_length=96)
            hyps.append(tokenizer.decode(ids[0], skip_special_tokens=True).strip())
            refs.append(p["label"])
        score = cer(refs, hyps)
        print(f"CER {tag}: {score:.4f}")
        return score

    before = evaluate("before (base model, held-out)")

    lora = LoraConfig(
        r=args.rank,
        lora_alpha=args.rank * 2,
        target_modules=["q_proj", "v_proj"],  # decoder self- and cross-attention
        lora_dropout=0.05,
        bias="none",
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    optim = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr)

    for epoch in range(args.epochs):
        model.train()
        random.shuffle(train)
        total = 0.0
        for i in range(0, len(train), args.batch):
            batch = train[i : i + args.batch]
            imgs = [
                rasterize(
                    p["strokes"],
                    thickness_scale=random.uniform(0.7, 1.3),
                    slant_deg=random.uniform(-4, 4),
                    jitter_px=0.4,
                )
                for p in batch
            ]
            pv = torch.stack([pixel_values(img) for img in imgs]).to(device)
            labels = tokenizer(
                [p["label"] for p in batch], return_tensors="pt", padding=True, truncation=True, max_length=96
            ).input_ids.to(device)
            labels[labels == tokenizer.pad_token_id] = -100
            loss = model(pixel_values=pv, labels=labels).loss
            loss.backward()
            optim.step()
            optim.zero_grad()
            total += loss.item() * len(batch)
        print(f"epoch {epoch + 1}/{args.epochs}  loss {total / len(train):.4f}")

    after = evaluate("after (LoRA, held-out)")

    print("merging LoRA into the base weights…")
    merged = model.merge_and_unload()
    out = Path(args.out)
    merged.save_pretrained(out)
    processor.save_pretrained(out)
    tokenizer.save_pretrained(out)
    print(f"merged model saved to {out}")
    print(f"CER {before:.4f} -> {after:.4f} on {len(holdout)} held-out lines")
    print("next: python export_model.py --model", out, "--out build/personal && "
          "python make_bundle.py --export build/personal --name 'Personal v1' --personalized --out build/")


if __name__ == "__main__":
    main()
