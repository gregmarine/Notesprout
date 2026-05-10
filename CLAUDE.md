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
- Drawing engine: abstracted — OnyxDrawingEngine (BOOX) and GenericDrawingEngine (all others) — **IMPLEMENTED**
- Onyx SDK: onyxsdk-device:1.3.3 + onyxsdk-pen:1.5.4 via Flutter platform channels — **IMPLEMENTED**
- Onyx SDK repo: `http://repo.boox.com/repository/maven-public/` (insecure protocol — required, do not change)
- hiddenapibypass:4.3 from JitPack — required for Android 14+ BOOX devices (applied in NoteSproutApplication.onCreate)

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

## Design System — E-Ink First (Never Violate These)

NoteSprout's visual language is designed for e-ink displays first. All other platforms inherit this aesthetic — we do not diverge for web or standard Android.

**Palette (UI Chrome Only):**
- `inkBlack` = `#000000`
- `paperWhite` = `#FFFFFF`
- `inkLight` = `#888888` — disabled / secondary text only
- `borderGray` = `#CCCCCC` — subtle dividers only
- No color in UI chrome — ever. Color belongs to content only.

**Visual Rules:**
- No shadows, no elevation, no gradients, no blur
- No Material splash or ripple effects (`NoSplash.splashFactory` everywhere)
- Animations: `Duration.zero` or minimum required — never decorative
- Borders: 1px solid inkBlack
- Corner radius: 4.0 — slightly rounded, not pill, not sharp
- Typography: clear, high-contrast, black on white

**Source of Truth:**
- All theme values live in `lib/theme/app_theme.dart`
- Do not hardcode colors or styles on widgets — always reference `AppTheme`

**What NOT To Do (Design):**
- No color in any UI chrome element
- No shadows or elevation on any widget
- No decorative animations
- No pill-shaped buttons or fully sharp corners
- Do not use Material 3 defaults without explicit override

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

## Drawing Engine Architecture (Implemented)

### Dart Layer
- `lib/drawing/drawing_engine.dart` — `DrawingEngine` abstract interface (`buildCanvas`, `clear`, `dispose`)
- `lib/drawing/generic_drawing_engine.dart` — Flutter-native fallback: `Listener` + two-layer `CustomPainter` (committed strokes as cached `ui.Image`, active stroke repaints per point)
- `lib/drawing/onyx_drawing_engine.dart` — BOOX path: embeds native `SurfaceView` via `AndroidView`, speaks over `MethodChannel("com.notesprout/onyx_canvas")`
- `lib/drawing/drawing_engine_factory.dart` — Pings native channel; uses `OnyxDrawingEngine` if response is `"ok"` (BOOX only), otherwise `GenericDrawingEngine`
- `lib/screens/drawing_screen.dart` — Drawing screen with e-ink toolbar (Clear button) above canvas

### Native Android Layer (package: `com.notesprout.app`)
- `OnyxDrawingView.kt` — Ported DIRECTLY from BOOXDemo `DrawingView.kt`: TouchHelper, RawInputCallback, limit rect, bitmap commit. Do not refactor — matches proven BOOXDemo logic exactly.
- `OnyxCanvasView.kt` — PlatformView wrapper returning `OnyxDrawingView`
- `OnyxCanvasViewFactory.kt` — PlatformViewFactory, notifies `OnyxCanvasMethodChannel` on view creation
- `OnyxCanvasMethodChannel.kt` — MethodChannel handler; `ping` checks `Build.MANUFACTURER` for "onyx" before returning `"ok"`

### Key Build Facts
- `minSdk = 29` (BOOX devices are Android 10+; Onyx SDK requires it)
- `android.enableJetifier=true` required — Onyx SDK bundles old `com.android.support` classes
- `tools:replace="android:label"` on `<application>` — Onyx SDK manifest conflicts on label
- `FlutterEngine.platformViewsController.registry` — correct API for registering PlatformViewFactory (NOT `platformViewRegistry` — that method does not exist in Flutter 3.41.x)
- `NoteSproutApplication` replaces `${applicationName}` in manifest to call `HiddenApiBypass.addHiddenApiExemptions("")` before SDK init

---

## What NOT To Do

- Do not use infinite scroll anywhere — ever
- Do not default to Material Design conventions that make the app feel like a generic Android app
- Do not add dependencies without discussion
- Do not restructure the monorepo layout without discussion
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

Drawing engine tested and verified on NoteAir5C (EMR), Palma2 Pro (USI 2.0), and Go Color 7. Next: page/notebook data model and SQLite persistence.

### Known Build Notes (from device testing)
- `setStrokeColor(Color.BLACK)` required on TouchHelper init — NoteAir5C color e-ink panel defaults to non-black
- Toolbar must be in a `Stack` above the canvas, not a `Column` sibling — native `SurfaceView` can occlude Flutter siblings below it in z-order

---
*Last updated: Drawing engine — device testing complete (seed branch)*
