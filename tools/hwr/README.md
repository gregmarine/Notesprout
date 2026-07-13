# Notesprout HWR Tooling (Mac side)

Python tooling for the TrOCR-based on-device handwriting engine (branch `hwr-trocr`).
Runs on Apple Silicon; nothing here touches user ink except the Phase-3 fine-tune, which
consumes a training bundle exported from the device and never leaves this machine.

## Setup

```sh
cd tools/hwr
python3.11 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Pipeline

1. **`export_model.py`** — pulls `microsoft/trocr-small-handwritten` from Hugging Face,
   exports ONNX (encoder + merged decoder-with-past) via Optimum, applies dynamic int8
   weight quantization, and writes `hwr_config.json` + `tokenizer.json`. All token ids /
   image-normalization values are read from the HF configs, never hardcoded.

   ```sh
   python export_model.py --out build/base            # fp32 + int8 variants
   ```

2. **`make_bundle.py`** — packages an export directory into a model-bundle zip the app
   imports via SAF (Settings → Handwriting Engine → Import model…). The manifest carries
   per-file SHA-256 hashes; the app verifies them plus a smoke decode before activating.

   ```sh
   python make_bundle.py --export build/base --name "TrOCR small handwritten" --out build/
   ```

3. **`eval_local.py`** — sanity checks: ONNX-vs-PyTorch transcription parity, int8-vs-fp32
   CER delta, and Kotlin-relevant tokenizer spot checks. Point it at a directory of line
   PNGs (e.g. extracted from a device training bundle) with a `labels.jsonl`; without
   images it falls back to synthetic rendered text (parity smoke only — not a real CER).

   ```sh
   python eval_local.py --export build/base [--images lines/ --labels lines/labels.jsonl]
   ```

4. **`finetune.py`** — LoRA fine-tune on a device training bundle (Settings → Handwriting
   Recognition → Export training data…). Runs on MPS; re-rasterizes from raw strokes with
   augmentation; prints before/after held-out CER; saves a merged HF model. Then:

   ```sh
   python finetune.py --bundle ~/Downloads/notesprout-hwr-train-*.zip --out build/personal-merged
   python export_model.py --model build/personal-merged --out build/personal
   python make_bundle.py --export build/personal --name "Personal v1" --personalized --out build/
   ```

## Getting the bundle onto a device

Any file transfer works; the app imports the zip through the system file picker. For dev:

```sh
adb -s <serial> push build/trocr-small-hw-*.zip /sdcard/Download/
```

## Notes

- `trocr-small-handwritten` uses **XLMRobertaTokenizer** (SentencePiece unigram) — the
  Kotlin `SentencePieceTokenizer` parses the exported `tokenizer.json`. Do not swap in a
  byte-BPE vocab; the app reads special-token ids from the bundle manifest.
- Quantization is weights-only dynamic int8 (MatMul, per-channel). If eval shows > 2 CER
  points of int8 regression, ship the fp32 decoder + int8 encoder (`make_bundle.py
  --decoder-precision fp32`).
