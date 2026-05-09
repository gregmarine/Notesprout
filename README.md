# NoteSprout

> Where thoughts have a place to grow 🌱

NoteSprout is an open source, handwriting-first notes app built for people who think on paper. Designed around the meditative experience of writing by hand, NoteSprout gives you fixed screen-size pages — no infinite scrolling, no clutter — backed by an intelligent document model that understands what you write. Built first for BOOX e-ink devices, expanding to Android tablets, Wacom devices, iPad, and web.

---

## Status

| Release | Status |
|---|---|
| **v1.0 — First Bloom** | ✅ Shipped — source preserved on the [`mvp`](../../tree/mvp) branch |
| **v1.1 — Germination** | 🌿 In development on the [`germination`](../../tree/germination) branch |

---

## Device Support

**Tier 1 — Primary targets**
- BOOX NoteAir5C (EMR stylus, e-ink color) — flagship
- BOOX Palma2 Pro (USI 2.0, Android phone form factor)
- BOOX Go Color 7 Gen II
- Wacom Movink Pad 11 & 14 (Android, active stylus)

**Tier 2 — QA / testing**
- BOOX NoteAir4C, Tab XC
- iPad Air + Apple Pencil
- Supernote Nomad & Manta

---

## Tech Stack

- **Kotlin** — native Android app
- **SQLite** — `.soil` notebook files, single unified `objects` table
- **Onyx SDK** — low-latency stylus input on BOOX e-ink devices
- **QuickJS** — embedded JavaScript engine powering the plugin system
- **Kotlin coroutines** — async operations throughout
- **Supabase** — cloud sync (planned, not yet implemented)

---

## Philosophy

NoteSprout is built around a single idea: writing by hand should feel like writing on paper, not fighting software. Read more in [docs/THE_SOIL.md](docs/THE_SOIL.md).

---

## Contributing

Contributions are welcome. We call our contributors **Gardeners**. Whether you're fixing a bug (Pruning), adding a feature (New Branch), or improving docs — you're helping this thing grow.

---

## License

MIT © NoteSprout contributors
