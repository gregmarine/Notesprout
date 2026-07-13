#!/usr/bin/env python3
"""Local sanity checks for an export_model.py output directory.

Checks:
  1. ONNX (fp32) vs PyTorch transcription parity on sample images.
  2. int8 vs fp32 CER delta (needs labels for a true CER; without labels it
     reports transcription-mismatch rate between the two, which still catches
     gross quantization damage).
  3. Tokenizer spot checks relevant to the Kotlin port (round-trip, specials).

Images: --images <dir of line PNGs> [--labels <labels.jsonl with {"file":..,"label":..}>].
Without --images, renders a few synthetic text lines with PIL's default font —
good enough for parity smoke, meaningless as real handwriting accuracy.
"""

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def load_images(args):
    if args.images:
        img_dir = Path(args.images)
        labels = {}
        if args.labels:
            for line in Path(args.labels).read_text().splitlines():
                if line.strip():
                    row = json.loads(line)
                    labels[row["file"]] = row["label"]
        files = sorted(p for p in img_dir.glob("*.png"))
        if not files:
            sys.exit(f"no PNGs in {img_dir}")
        return [(Image.open(p).convert("RGB"), labels.get(p.name)) for p in files]

    print("no --images given: using synthetic rendered lines (parity smoke only)")
    from PIL import ImageFont
    font = None
    for path in ["/System/Library/Fonts/Supplemental/Bradley Hand Bold.ttf",
                 "/System/Library/Fonts/Supplemental/Noteworthy.ttc",
                 "/System/Library/Fonts/Supplemental/Comic Sans MS.ttf",
                 "/System/Library/Fonts/Supplemental/Arial.ttf"]:
        try:
            font = ImageFont.truetype(path, 56)
            print(f"synthetic font: {path}")
            break
        except OSError:
            continue
    samples = []
    for text in ["hello world", "The quick brown fox", "Meeting at 6:30 tomorrow",
                 "grocery list: milk, eggs", "42 is the answer"]:
        img = Image.new("RGB", (1024, 112), "white")
        ImageDraw.Draw(img).text((16, 20), text, fill="black", font=font)
        samples.append((img, text))
    return samples


def preprocess(img, config):
    size = config["imageSize"]
    mean = np.array(config["imageMean"], dtype=np.float32).reshape(3, 1, 1)
    std = np.array(config["imageStd"], dtype=np.float32).reshape(3, 1, 1)
    arr = np.asarray(img.resize((size, size)), dtype=np.float32) / 255.0
    arr = arr.transpose(2, 0, 1)
    return ((arr - mean) / std)[None]


def onnx_generate(sess_enc, sess_dec_init, sess_dec_past, pixel_values, config, tokenizer):
    """Greedy decode mirroring what the Kotlin TrOcrDecoder does.

    Step 1 runs decoder_model (computes the fixed cross-attention KV); steps 2+
    run decoder_with_past_model feeding only the last token plus past tensors.
    """
    enc_out = sess_enc.run(None, {"pixel_values": pixel_values})[0]

    ids = [config["decoderStartTokenId"]]
    past = {}  # past_key_values.* feed for the with-past decoder
    for step in range(config["maxLength"]):
        if step == 0:
            feed = {"input_ids": np.array([ids], dtype=np.int64),
                    "encoder_hidden_states": enc_out}
            sess = sess_dec_init
        else:
            feed = {"input_ids": np.array([[ids[-1]]], dtype=np.int64), **past}
            sess = sess_dec_past
        outs = sess.run(None, feed)
        names = [o.name for o in sess.get_outputs()]
        logits = outs[names.index("logits")]
        next_id = int(np.argmax(logits[0, -1]))
        ids.append(next_id)
        if next_id == config["eosTokenId"]:
            break
        # present.i.decoder.* updates every step; present.i.encoder.* (cross KV)
        # only exists in step-1 outputs and is re-fed unchanged afterwards.
        for n in names:
            if n.startswith("present"):
                past[n.replace("present", "past_key_values")] = outs[names.index(n)]
    return tokenizer.decode(ids, skip_special_tokens=True).strip()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True)
    ap.add_argument("--images")
    ap.add_argument("--labels")
    args = ap.parse_args()

    import onnxruntime as ort
    from jiwer import cer
    from transformers import AutoTokenizer, VisionEncoderDecoderModel

    export = Path(args.export)
    config = json.loads((export / "hwr_config.json").read_text())
    tokenizer = AutoTokenizer.from_pretrained(config["baseModel"], use_fast=True)
    samples = load_images(args)

    # --- tokenizer spot checks (Kotlin port contract) -----------------------
    for text in ["hello world", "6:30, okay?", "naïve café"]:
        rt = tokenizer.decode(tokenizer.encode(text), skip_special_tokens=True).strip()
        status = "ok" if rt == text else f"MISMATCH -> {rt!r}"
        print(f"tokenizer round-trip {text!r}: {status}")
    print(f"specials: bos={tokenizer.bos_token_id} eos={tokenizer.eos_token_id} "
          f"pad={tokenizer.pad_token_id} vocab={tokenizer.vocab_size} "
          f"(manifest: start={config['decoderStartTokenId']} eos={config['eosTokenId']} "
          f"pad={config['padTokenId']} vocab={config['vocabSize']})")

    # --- PyTorch reference ---------------------------------------------------
    model = VisionEncoderDecoderModel.from_pretrained(config["baseModel"]).eval()
    import torch
    pt_texts = []
    for img, _ in samples:
        pixels = torch.from_numpy(preprocess(img, config))
        out = model.generate(pixels, max_length=config["maxLength"])
        pt_texts.append(tokenizer.decode(out[0], skip_special_tokens=True).strip())

    # --- ONNX fp32 + int8 ----------------------------------------------------
    def session(name_candidates):
        for n in name_candidates:
            p = export / n
            if p.exists():
                return ort.InferenceSession(str(p)), n
        sys.exit(f"none of {name_candidates} in {export}")

    results = {}
    for tag, suffix in [("fp32", ""), ("int8", "_int8")]:
        try:
            enc, _ = session([f"encoder_model{suffix}.onnx"])
            dec_init, _ = session([f"decoder_model{suffix}.onnx"])
            dec_past, _ = session([f"decoder_with_past_model{suffix}.onnx"])
        except SystemExit:
            print(f"({tag} models not present, skipping)")
            continue
        results[tag] = [onnx_generate(enc, dec_init, dec_past, preprocess(img, config),
                                      config, tokenizer)
                        for img, _ in samples]

    # --- report ---------------------------------------------------------------
    labels = [lbl for _, lbl in samples]
    have_labels = all(l is not None for l in labels)
    print("\nidx | pytorch | " + " | ".join(results) + (" | label" if have_labels else ""))
    for i, pt in enumerate(pt_texts):
        row = [pt] + [results[t][i] for t in results] + ([labels[i]] if have_labels else [])
        print(f"{i:3d} | " + " | ".join(repr(x) for x in row))

    if "fp32" in results:
        match = sum(a == b for a, b in zip(pt_texts, results["fp32"]))
        print(f"\nfp32 ONNX == PyTorch on {match}/{len(samples)} samples")
    if have_labels:
        for tag, texts in results.items():
            print(f"CER {tag}: {cer(labels, texts):.4f}")
        print(f"CER pytorch: {cer(labels, pt_texts):.4f}")
    elif "fp32" in results and "int8" in results:
        diff = sum(a != b for a, b in zip(results["fp32"], results["int8"]))
        print(f"int8 vs fp32 transcription mismatches: {diff}/{len(samples)}")


if __name__ == "__main__":
    main()
