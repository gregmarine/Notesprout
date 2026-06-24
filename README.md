# Notesprout

> Where thought has a place to grow 🌱

Notesprout is an open source, handwriting-first notes app built for people who think on paper. Designed around the meditative experience of writing by hand, Notesprout gives you fixed screen-size pages — no infinite scrolling, no clutter — backed by an intelligent document model that understands what you write. Built first for BOOX e-ink devices, expanding to Android tablets, Wacom devices, iPad, and web.

---

## Status

| Release | Status |
|---|---|
| **v0.0 — First Bloom** | ✅ Shipped — MVP/proof-of-concept, source preserved on the [`mvp`](../../tree/mvp) branch |
| **v1.0 — Seed** | ✅ Shipped — all major features complete, source preserved on the `seed` branch (archived) |
| **v1.1 — Sprout** | 🌱 Active development on the [`sprout`](../../tree/sprout) branch |

---

## Device Support

**Tier 1 — Primary targets**
- BOOX NoteAir5C (EMR stylus, e-ink color) — flagship
- BOOX Note Max (EMR stylus, large-format e-ink)
- BOOX Palma2 Pro (USI 2.0 stylus, Android phone form factor)
- BOOX Go Color 7 Gen II
- Wacom Movink Pad 11 & 14 (Android, active stylus)
- iPhone 14 — future
- MacBook / Web — future

**Tier 2 — QA / testing**
- BOOX NoteAir4C, Tab XC
- iPad Air + Apple Pencil — future
- Supernote Nomad & Manta — future

---

## Tech Stack

- **Kotlin** — native Android app (Java 17 target)
- **Room / SQLite** — `.soil` notebook files, single unified table, one file per notebook
- **Onyx SDK** — low-latency EMR stylus input on BOOX e-ink devices
- **ML Kit** — on-device digital ink recognition for heading text
- **kotlinx.serialization** — code-generated JSON, zero reflection
- **Kotlin coroutines** — async operations throughout

---

## Philosophy

Notesprout is built around a single idea: writing by hand should feel like writing on paper, not fighting software. Read more in [docs/THE_SOIL.md](docs/THE_SOIL.md).

---

## Built by Humans, Coded with Computers

All design, product decisions, and philosophy behind Notesprout are human. This is an app for humans, by humans — that happens to run on computers and is coded with the assistance of one.

The code for this project is written with the help of [Claude Code](https://claude.ai/code), Anthropic's AI coding tool. In the spirit of full transparency: Claude Code does the heavy lifting of implementation; the human steers everything else — what gets built, why it gets built, and what it should feel like to use.

---

## Contributing

Contributions are welcome. We call our contributors **Gardeners**. Whether you're fixing a bug (Pruning), adding a feature (New Branch), or improving docs — you're helping this thing grow.

---

## License

MIT © Notesprout contributors
