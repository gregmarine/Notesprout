# Notesprout

> Where thought has a place to grow 🌱

Notesprout is an open source, handwriting-first notes app built for people who process thought through writing. Designed around the meditative experience of writing by hand, Notesprout gives you the no clutter feel of a real notebook. Built first for Android e-ink devices, expanding to most stylus input capable Android tablets and beyond.

---

## Status

| Release | Status |
|---|---|
| **v0.0 — First Bloom** | ✅ Shipped — MVP/proof-of-concept, source preserved on the [`mvp`](../../tree/mvp) branch |
| **v1.0 — Seed** | ✅ Shipped — all major features complete, source preserved on the `seed` branch (archived) |
| **v1.1 — Sprout** | ✅ Shipped — merged to `main`, source preserved on the `sprout` branch (archived) |
| **v1.2 — Sapling** | ✅ Shipped — merged to `main`, source preserved on the `sapling` branch (archived) |

---

## Device Support

**Tier 1 — Primary targets**
- BOOX Go 10.3 Gen II (large-format e-ink) — flagship
- BOOX Go 6 Gen II (compact e-ink)
- BOOX Note Max (extra large-format e-ink)
- BOOX Palma2 Pro (Android phone form factor color e-ink)
- BOOX NoteAir5C (large-format color e-ink)

**Tier 2 — QA / testing**
- BOOX NoteAir4C (large-format color e-ink)
- BOOX Tab XC (extra large-format color e-ink)
- BOOX Go Color 7 Gen II (compact color e-ink)
- Wacom Movink Pad 11 (Android art tablet)
- Wacom Movink Pad 14 Pro (Android art tablet)

**Future**
- Desktop / Web
- Supernote Nomad & Manta
- Android Phone
- Android Tablet

---

## Tech Stack

- **Kotlin** — native Android app (Java 17 target)
- **Room / SQLite** — `.soil` notebook files, single unified table, one file per notebook
- **Onyx SDK** — low-latency stylus input on BOOX e-ink devices, for both EMR and USI pens
- **SQLCipher** — full-database encryption for `.soil` notebooks and the global index; passphrase-derived keys, per-notebook or global scope, with password-protected PDF export via PDFBox
- **Handwriting recognition** — on-device, two engines: **ML Kit** digital ink (default, always available) and an optional **TrOCR** personal engine running on **ONNX Runtime Mobile**, fine-tuned to your own handwriting; feeds heading text, full-page text extraction, and text/markdown export (Experimental)
- **kotlinx.serialization** — code-generated JSON, zero reflection
- **Kotlin coroutines** — async operations throughout

---

## Philosophy

Notesprout is built around a single idea: writing by hand should feel like writing on paper, not fighting software. Read more in [docs/THE_SOIL.md](docs/THE_SOIL.md).

---

## Built by a Human, Augmented by Claude Code

All design, product decisions, and philosophy behind Notesprout are human. This is an app for humans, by humans — that happens to run on computers and is coded with the assistance of AI.

The code for this project is written with the help of [Claude Code](https://claude.ai/code), Anthropic's AI coding tool. In the spirit of full transparency: Claude Code does the heavy lifting of code implementation; the human steers everything else — what gets built, why it gets built, and what it should feel like to use.

---

## Contributing

At this time, this project is solo. Someday, it will be open to contributions.

---

## License

MIT © Notesprout contributors
