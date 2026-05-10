# NoteSprout — Claude Code Project Intelligence

## What is NoteSprout?
A handwriting-first, meditative notes app. Think paper, but smarter underneath. Built for e-ink devices first (BOOX), expanding to iPad, Android tablets, phones, and web.

**Slogan:** "Where thoughts have a place to grow 🌱"
**License:** MIT
**Monorepo root:** ~/git/NoteSprout

---

## Monorepo Structure

- apps/notesprout_flutter — Flutter app (primary active codebase, all platforms)
- apps/notesprout_android — Native Android app (Kotlin, v1.0 / First Bloom — shipped, reference only)

---

## Core Philosophy — Never Violate These

- Human-first: fixed screen-size pages, never infinite scroll
- Meditative, paper-like writing experience
- A coexistence of human and machine — intelligent underneath, calm on the surface
- Everything is an object (universal BaseObject model — relational, compositional)
- Pages feel like physical pages. The app should never feel like a web app.

---

## Tech Decisions — Already Made, Do Not Revisit Without Discussion

- Flutter latest stable — no pinned version, always latest stable
- State management: Riverpod (flutter_riverpod, latest stable)
- Database: sqflite (SQLite) — not yet implemented, coming soon
- Package name: com.notesprout.app
- Primary test device: BOOX e-ink Android devices
- Drawing engine: abstracted — OnyxDrawingEngine (BOOX) and GenericDrawingEngine (all others) — not yet implemented
- Onyx SDK integration: via Flutter platform channels — not yet implemented, reference ~/git/BOOXDemo for lessons learned

---

## Architecture — Foundational Decisions

- Notebook = a folder containing a SQLite file
- Hierarchy: Notebook → Pages → Layers → Content Objects
- Layers: base layer (template, locked) and content layers
- Every object carries: id, parentId, type, subtype, position, boundingBox, link, createdAt, updatedAt, deletedAt, data
- Stroke data: proprietary point arrays (x, y, pressure, tilt, timestamp), stored as JSON in TEXT column
- Soft deletes with cleanup process
- Stable UUIDs everywhere
- Delta sync via syncVersion counter; SyncProvider abstraction (Supabase first)

---

## Device Target Tiers

**Tier 1 — Daily drivers:**
- BOOX NoteAir5C (EMR stylus, e-ink color) — flagship
- BOOX Palma2 Pro (USI 2.0 stylus, Android phone form factor)
- BOOX Go Color 7 Gen II
- Wacom Movink Pad 11 & 14 (Android, GenericDrawingEngine)
- iPhone 14 (touch-only)
- MacBook (Flutter desktop / web)
- Web (mobile and desktop)

**Tier 2 — Testing/QA:**
- BOOX NoteAir4C
- BOOX Tab XC
- iPad Air + Apple Pencil
- Supernote Nomad & Manta (GenericDrawingEngine fallback)

---

## Branch Strategy

- main — stable releases only
- germination — previous post-MVP feature branch (reference, not active)
- seed — current active development (clean restart, lessons learned)

---

## Community Nomenclature — Use These Consistently

- Release notes → Growth Logs
- Bug fixes → Pruning
- New features → New Branches
- Contributors → Gardeners
- v1.0 → First Bloom
- README → The Soil
- CLAUDE.md → The Soil for Claude Code

---

## What NOT To Do

- Do not use infinite scroll anywhere — ever
- Do not default to Material Design conventions that make the app feel like a generic Android app
- Do not add dependencies without discussion
- Do not restructure the monorepo layout without discussion
- Do not implement drawing, canvas, or Onyx SDK until explicitly instructed
- Do not implement SQLite/sqflite until explicitly instructed
- Do not guess at architectural decisions — ask first

---

## Build & Run

```bash
cd ~/git/NoteSprout/apps/notesprout_flutter
flutter run                        # run on connected device
flutter run -d chrome              # run in browser
flutter build apk --debug          # build Android debug APK
```

*This section will be updated as we make discoveries.*

---

## Current Step

Seed branch is live. Hello World scaffold complete. Next: [TBD after Hello World verified]

---
*Last updated: Seed branch initialization*
