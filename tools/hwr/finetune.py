#!/usr/bin/env python3
"""LoRA fine-tune of trocr-small-handwritten on a Notesprout training bundle.

Input: the zip exported from the app (Settings → Handwriting Recognition →
Export training data…). Runs on Apple Silicon (MPS) or CPU. Nothing uploads
anywhere — the bundle and the model stay on this machine.

Training images are the APP-RENDERED PNGs (the exact rendering the device uses at
inference) with light per-epoch geometric jitter (small rotation/scale/translate via
PIL affine). The stroke re-rasterizer below is retained for experiments but is NOT
the default training source: PIL's line rendering (chunky joints, no anti-aliasing)
differs enough from the app's to hurt — training on it made held-out CER worse.
The held-out split also uses the app PNGs, so eval matches on-device rendering.

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


def augment_png(img: Image.Image) -> Image.Image:
    """Light geometric jitter on the app-rendered PNG — rendering style untouched."""
    angle = random.uniform(-1.5, 1.5)
    scale = random.uniform(0.94, 1.06)
    tx = random.uniform(-0.01, 0.01) * img.width
    ty = random.uniform(-0.03, 0.03) * img.height
    a = math.cos(math.radians(angle)) / scale
    b = math.sin(math.radians(angle)) / scale
    return img.transform(
        img.size,
        Image.Transform.AFFINE,
        (a, b, -tx, -b, a, -ty),
        resample=Image.Resampling.BILINEAR,
        fillcolor="white",
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--out", required=True, help="merged model output dir")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--rank", type=int, default=8)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=4)
    ap.add_argument("--holdout", type=float, default=0.2)
    ap.add_argument("--target", choices=["encoder", "decoder", "both"], default="encoder",
                    help="which attention projections get LoRA; encoder-only is safest on small "
                         "data (adapts letterform perception without disturbing the decoder's "
                         "language behavior — decoder tuning caused repetition artifacts)")
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

    def report_lines(tag):
        model.eval()
        print(f"-- held-out detail ({tag}):")
        with torch.no_grad():
            for p in holdout:
                pv = pixel_values(p["png"]).unsqueeze(0).to(device)
                ids = model.generate(pv, max_length=96)
                hyp = tokenizer.decode(ids[0], skip_special_tokens=True).strip()
                mark = "OK " if hyp == p["label"] else "DIFF"
                print(f"  [{mark}] ref: {p['label']}")
                if hyp != p["label"]:
                    print(f"         hyp: {hyp}")

    before = evaluate("before (base model, held-out)")

    # DeiT encoder names its attention projections query/value; the TrOCR decoder
    # uses q_proj/v_proj.
    targets = {
        "encoder": ["query", "value"],
        "decoder": ["q_proj", "v_proj"],
        "both": ["query", "value", "q_proj", "v_proj"],
    }[args.target]
    lora = LoraConfig(
        r=args.rank,
        lora_alpha=args.rank * 2,
        target_modules=targets,
        lora_dropout=0.05,
        bias="none",
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    optim = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr)

    # Early stopping: keep the adapter weights from the best held-out CER — the floor
    # is the base model itself (if nothing beats "before", we report that honestly).
    best_cer = before
    best_state = None
    trainable_names = [n for n, p in model.named_parameters() if p.requires_grad]

    def snapshot():
        return {n: model.get_parameter(n).detach().clone() for n in trainable_names}

    for epoch in range(args.epochs):
        model.train()
        random.shuffle(train)
        total = 0.0
        for i in range(0, len(train), args.batch):
            batch = train[i : i + args.batch]
            imgs = [augment_png(p["png"]) for p in batch]
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
        msg = f"epoch {epoch + 1}/{args.epochs}  loss {total / len(train):.4f}"
        if (epoch + 1) % 2 == 0 or epoch + 1 == args.epochs:
            score = evaluate(f"epoch {epoch + 1}")
            if score < best_cer:
                best_cer = score
                best_state = snapshot()
            msg += f"  (best {best_cer:.4f})"
        print(msg)

    if best_state is None:
        # Nothing beat the plain base model — refuse to produce an artifact rather
        # than merge degraded weights. More data (especially corrections from real
        # pages) is the fix; gradient fine-tuning needs a few hundred lines.
        print("NO epoch beat the base model — no model saved. Keep collecting "
              "corrections and retrain later; the on-device lexicon/correction-memory "
              "personalization keeps working from these same samples in the meantime.")
        sys.exit(1)

    with torch.no_grad():
        for n in trainable_names:
            model.get_parameter(n).copy_(best_state[n])
    print(f"restored best checkpoint (held-out CER {best_cer:.4f})")

    after = evaluate("after (best checkpoint, held-out)")
    report_lines("after")

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
