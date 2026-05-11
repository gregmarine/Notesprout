# NoteSprout — Claude Code Project Intelligence

## What is NoteSprout?
A handwriting-first, meditative notes app. Think paper, but smarter underneath. Built for e-ink devices first (BOOX), expanding to iPad, Android tablets, phones, and web.

**Slogan:** "Where thoughts have a place to grow 🌱"
**License:** MIT
**Monorepo root:** ~/git/NoteSprout

---

## Monorepo Structure

- apps/notesprout_android — Native Android app (primary active codebase)
- apps/notesprout_flutter — Flutter app (reference only — do not add features here)

---

## Core Philosophy — Never Violate These

- Human-first: fixed screen-size pages, never infinite scroll
- Meditative, paper-like writing experience
- A coexistence of human and machine — intelligent underneath, calm on the surface
- Everything is an object (universal BaseObject model — relational, compositional)
- Pages feel like physical pages. The app should never feel like a web app.

---

## Tech Decisions — Already Made, Do Not Revisit Without Discussion

- Language: Kotlin (Java 17 target — use Temurin-17 JDK)
- Package name: com.notesprout.android
- Primary test device: BOOX e-ink Android devices
- Drawing engine: abstracted — OnyxDrawingEngine (BOOX) and GenericDrawingEngine (all others) — **IMPLEMENTED**
- Onyx SDK: onyxsdk-device:1.3.3 + onyxsdk-pen:1.5.4 — **IMPLEMENTED**
- Onyx SDK repo: `http://repo.boox.com/repository/maven-public/` (insecure protocol — required, do not change)
- hiddenapibypass:4.3 from JitPack — required for Android 14+ BOOX devices (applied in NoteSproutApplication.onCreate)
- Database: Room/SQLite — not yet implemented, coming soon
- AGP 8.11.1 + Kotlin 2.2.20 + Gradle 8.14

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

NoteSprout's visual language is designed for e-ink displays first. All other platforms inherit this aesthetic.

**Palette (UI Chrome Only):**
- `inkBlack` = `#000000`
- `paperWhite` = `#FFFFFF`
- `inkLight` = `#888888` — disabled / secondary text only
- `borderGray` = `#CCCCCC` — subtle dividers only (invisible on e-ink — use inkBlack for any visible border)
- No color in UI chrome — ever. Color belongs to content only.

**Visual Rules:**
- No shadows, no elevation, no gradients, no blur
- No Material splash or ripple effects (`rippleColor=transparent`, `stateListAnimator=null`)
- Animations: none or minimum required — never decorative
- Borders: 1dp solid inkBlack
- Corner radius: 4dp — slightly rounded, not pill, not sharp
- Typography: clear, high-contrast, black on white

**Source of Truth:**
- Colors: `app/src/main/res/values/colors.xml`
- Styles/typography: `app/src/main/res/values/styles.xml`
- Theme: `app/src/main/res/values/themes.xml`
- Do not hardcode colors or styles on views — always reference named resources

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
- iPhone 14 (touch-only) — future
- MacBook / Web — future

**Tier 2 — Testing/QA:**
- BOOX NoteAir4C
- BOOX Tab XC
- iPad Air + Apple Pencil — future
- Supernote Nomad & Manta (GenericDrawingEngine fallback) — future

## ADB Device Serials

| Device | ADB Serial |
|---|---|
| BOOX NoteAir5C (NA5C) | `92c16533` |
| BOOX Palma2 Pro (P2P) | `287d2364` |
| BOOX Go Color 7 (GC7) | `98d56306` |
| BOOX NoteAir4C (NA4C) | `1d36f870` |
| Wacom Movink Pad 11 (MIP11) | `5HL21V5007384` |

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
- README → The Soil
- CLAUDE.md → The Soil for Claude Code

---

## Drawing Engine Architecture (Implemented)

### Native Android Layer (package: `com.notesprout.android`)
- `drawing/DrawingView.kt` — interface: `asView()`, `setToolbarHeight(Int)`, `enableDrawing()`, `disableDrawing()`, `clearCanvas()`, `releaseResources()`
- `drawing/OnyxDrawingView.kt` — BOOX path: TouchHelper, RawInputCallback, limit rect, bitmap commit, 800ms idle flush via `setRawDrawingRenderEnabled`. Do not refactor — matches proven BOOXDemo logic exactly.
- `drawing/GenericDrawingView.kt` — Flutter Canvas fallback: two-layer Bitmap approach, stylus-only (`TOOL_TYPE_STYLUS`), historical point capture for smooth strokes
- `DrawingActivity.kt` — fullscreen immersive (`WindowInsetsControllerCompat`), detects BOOX via `Build.MANUFACTURER`, `doOnLayout` for precise toolbar height
- `MainActivity.kt` — theme test screen + entry point to DrawingActivity

### Key Build Facts
- `minSdk = 29` (BOOX devices are Android 10+; Onyx SDK requires it)
- `android.enableJetifier=true` required — Onyx SDK bundles old `com.android.support` classes
- `jniLibs.pickFirsts` for `libc++_shared.so` — resolves native lib conflict with Onyx SDK
- `org.gradle.java.home` in `gradle.properties` pins Temurin-17 — system Java 26 is incompatible with current AGP/Gradle
- `NoteSproutApplication.onCreate` calls `HiddenApiBypass.addHiddenApiExemptions("")` before any SDK init
- `setStrokeColor(Color.BLACK)` required on TouchHelper init — NoteAir5C color panel defaults to non-black
- Toolbar z-order: toolbar must overlay the drawing container in a `FrameLayout`, not sit as a sibling — native SurfaceView occludes Flutter/View siblings below it

---

## What NOT To Do

- Do not use infinite scroll anywhere — ever
- Do not default to Material Design conventions that make the app feel like a generic Android app
- Do not add dependencies without discussion
- Do not restructure the monorepo layout without discussion
- Do not implement Room/SQLite until explicitly instructed
- Do not guess at architectural decisions — ask first
- Do not add new features to apps/notesprout_flutter — it is reference only

---

## Build & Run

```bash
cd ~/git/NoteSprout/apps/notesprout_android

./gradlew assembleDebug                        # build debug APK
./gradlew installDebug                         # build + install on connected device

# Install on a specific device by serial
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

*See ADB Device Serials table above for device serials.*

---

## Current Step

Native Android drawing engine verified on NA5C (EMR), P2P (USI 2.0), GC7, NA4C, and Movink Pad 11. Next: Optimize NA5C canvas flicker. On deck: page/notebook data model and SQLite persistence.

---
*Last updated: Native Android port complete — moving forward with native Kotlin (seed branch)*
