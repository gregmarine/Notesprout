# NoteSprout — Project Context for Claude Code

> "Where thoughts have a place to grow 🌱"

## What is NoteSprout?
Open source handwriting-first notes app. Built initially for BOOX e-ink devices, expanding to iPad, Android, Supernote, phones, and web. MIT license.

**Core philosophy:** Meditative, paper-like writing experience with an intelligent document model underneath. Fixed screen-size pages — no infinite scrolling. Human-first.

---

## Monorepo Structure

```
apps/
  notesprout_android/   # Native Android app (Kotlin) — ONLY active codebase
docs/
templates/
  NoteAir5C/            # Page templates (e.g. Lined.png)
```

The Flutter mobile app and shared Dart packages from the MVP have been removed. The MVP is preserved in full on the `mvp` branch. All active development targets `apps/notesprout_android`.

---

## Architecture

- **Everything is a BaseObject** — a single `objects` table in SQLite stores all content. Every page, layer, stroke, text block, heading, and plugin is a row with a shared schema.
- **BaseObject fields:** `id` (UUID), `parentId`, `type`, `subtype`, `createdAt`, `updatedAt`, `deletedAt`, plus a flexible `data` JSON column for type-specific fields.
- **Notebook** = folder on disk containing a SQLite `.soil` file
- **Hierarchy:** Notebook → Pages → Layers → Content Objects
- **Layers:** base layer (template, locked) + content layer(s)
- **Stroke data:** point arrays `(x, y, pressure, tilt, timestamp)` stored as JSON in the `data` column
- **Soft deletes** with a periodic cleanup process
- **Stable UUIDs** everywhere — pages, layers, objects
- **Plugin system** — all object interactions are implemented as plugins. Two plugin types:
  - **Tool plugins** — handle input (stylus, touch, gesture) and produce objects
  - **Container plugins** — render and manage objects of a given type
  - Plugins run in a QuickJS JavaScript sandbox embedded in the Android app
- **Delta sync** via `syncVersion` counter on the notebook metadata object; `SyncProvider` abstraction planned (Supabase first, BYO cloud later) — not yet implemented

---

## Tech Stack

| Layer | Tech |
|---|---|
| Native Android | Kotlin, SQLite, Onyx SDK |
| Plugin runtime | QuickJS (embedded JS engine) |
| Async | Kotlin coroutines |
| Sync (future) | Supabase |
| Tools | VS Code, Claude Code |
| License | MIT |

---

## Release Status

- **v1.0 — First Bloom** ✅ SHIPPED. Native Android MVP complete. Full source preserved on the `mvp` branch.
- **Next — Germination** 🌿 First post-MVP release. Active development on the `germination` branch.

### Germination Release Scope
- Page Navigator (tap indicator → jump to page)
- Headings (H1–H3) with semantic ToC tree
- Dynamic page links via stable IDs
- Gesture-based heading marking with OCR conversion to styled text
- Selection tool
- Text objects (typed text as first-class content objects)
- Layer visibility and opacity controls
- Plugin system — interaction with objects via a plugin layer (everything is a plugin)
- Supabase sync via SyncProvider abstraction
- Open file format spec (.soil) — published and documented
- Folder-per-notebook → single .soil file format revisit

---

## Device Support

### Tier 1 — Primary targets
- BOOX NoteAir5C (EMR stylus, e-ink color) — flagship
- BOOX Palma2 Pro (USI 2.0 stylus, Android phone form factor)
- BOOX Go Color 7 Gen II (smaller e-ink color)
- Wacom Movink Pad 11 & 14 (Android, active stylus)
- iPhone 14 (touch-only)
- MacBook / Web

### Tier 2 — QA / testing
- BOOX NoteAir4C, Tab XC
- iPad Air + Apple Pencil
- Supernote Nomad & Manta (laggy; future consideration)

### Stylus protocols
- BOOX NoteAir5C — EMR
- BOOX Palma2 Pro — USI 2.0 (different initialization path)
- Wacom Movink — active stylus via Android `MotionEvent.TOOL_TYPE_STYLUS`

---

## Community Nomenclature

| Term | Meaning |
|---|---|
| Growth Logs | Release notes |
| Pruning | Bug fixes |
| New Branches | New features |
| Gardeners | Contributors |
| First Bloom | v1.0 |
| Germination | v1.1 (next release) |
| The Soil | README / design philosophy doc |
