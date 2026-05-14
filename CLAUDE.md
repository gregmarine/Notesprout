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
- Do not use Material Components — theme is `Theme.AppCompat.Light.NoActionBar`, buttons are `AppCompatButton` with explicit drawable backgrounds

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
- `drawing/DrawingView.kt` — interface: `asView()`, `setToolbarHeight(Int)`, `enableDrawing()`, `disableDrawing()`, `resetOverlay()`, `clearCanvas()`, `releaseResources()`
- `drawing/OnyxDrawingView.kt` — BOOX path: TouchHelper, RawInputCallback, limit rect. Key pattern: `renderStroke` calls `invalidate()` on every stroke to keep the Android canvas continuously current with the hardware overlay. `clearCanvas()` owns the overlay handoff: disable render → white bitmap → `invalidate()` → `EpdController.handwritingRepaint()` (bakes white into physical EPD pixels) → re-enable. `resetOverlay()` toggles `setRawDrawingRenderEnabled` off/on (test utility). `onBeginRawDrawing` re-enables render when a new stroke starts after a toggle. `EpdController.setUpdListSize(512)` called on every `openRawDrawing()` to suppress hardware auto-GC16 mid-session.
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
- No `com.google.android.material` dependency — removed; BOOX GC7 OEM skin intercepts Material's backgroundTint mechanism and renders all non-primary-colored buttons solid black. AppCompat with explicit drawable backgrounds is reliable across all devices.

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

## Pruning Log

### Pruning: EPD handoff — flicker-free canvas transition (verified NA5C, P2P, GC7)
- **Root cause:** While the hardware pen overlay is active, the Android canvas bitmap was not being kept current — so when the overlay was removed, the EPD base image was stale and flashed. Fix: call `invalidate()` inside `renderStroke` so the canvas is continuously updated as strokes arrive. The overlay and canvas are always in sync; removing the overlay is seamless.
- **Architecture simplified:** `commitStrokes` abstraction removed entirely. `clearCanvas()` owns the overlay handoff directly. `pendingCommit`/`commitCallback` fields removed. `onDraw` is now just a bitmap blit.
- **Focus loss:** `onWindowFocusChanged(false)` calls `invalidate()` then `setRawDrawingEnabled(false)` — canvas is painted before input is stopped. On focus regain, `restartRawDrawing()` resets overlay state.

### Pruning: Fullscreen + Button rendering (verified NA5C, GC7, P2P)
- **Fullscreen:** `MainActivity` was not fullscreen — on Android 15 devices (NA5C, P2P) the status bar overlaid the window and intercepted touches near the top. Fixed by mirroring `DrawingActivity`'s `WindowInsetsControllerCompat` setup: hide system bars, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`. Both activities are now fully immersive; swipe from edges to transiently reveal system bars.
- **Buttons on GC7:** BOOX Go Color 7 OEM skin intercepts Material Components' `backgroundTint` mechanism and renders all buttons as solid black regardless of the tint value set. Root fix: dropped `com.google.android.material` entirely. Theme moved to `Theme.AppCompat.Light.NoActionBar`; all buttons switched from `MaterialButton` to `AppCompatButton` with explicit `android:background` drawables (`btn_elevated_background.xml`, `shape_bordered.xml`). `MaterialCardView` replaced with `LinearLayout` + `shape_bordered`. `TextInputLayout` replaced with `AppCompatEditText`. Reliable rendering confirmed on all three test devices.

### Pruning: Idle overlay release — 1.5s after last stroke (verified NA5C, P2P, GC7, NA4C)
- **What:** After 1.5s of pen inactivity, `setRawDrawingRenderEnabled(false)` + `invalidate()` is called, handing the overlay back to the Android canvas. The overlay reactivates automatically on the next `onBeginRawDrawing`.
- **Implementation:** `idleReleaseRunnable` posted via `postDelayed` in `onEndRawDrawing`; cancelled via `removeCallbacks` in `onBeginRawDrawing`, on focus loss, and on detach. No explicit reactivation needed — `onBeginRawDrawing` already calls `setRawDrawingRenderEnabled(true)`.
- **Timing rationale:** 1.5s filters natural mid-thought pen lifts without feeling sluggish. Revisit if explicit page refresh controls are added.

### Pruning: clearCanvas phantom strokes + EPD ghosting (verified NA5C, P2P, GC7, NA4C, MIP11)
- **Root cause:** Two distinct problems caused by the removal of `EpdController.handwritingRepaint()` from the clear path in commit 99d7b72. (1) **Gray residue** — physical EPD pixels retained old stroke content because the display was never properly refreshed; no overlay toggle fixes this. (2) **Black flash** — the EPD overlay hardware buffer carries old strokes independently of `renderBitmap`; when render is re-enabled after a clear, the stale buffer briefly renders at full black before new strokes overwrite it.
- **Fix:** Restored `EpdController.handwritingRepaint(view, Rect(0,0,w,h))` in `clearCanvas()` after painting `renderBitmap` white. With the overlay disabled and the bitmap white, `handwritingRepaint` bakes white into the physical EPD pixels, clearing both the ghosting and the stale overlay buffer state. Restored `EpdController.setUpdListSize(512)` in `openRawDrawing()` to suppress the hardware's automatic mid-session GC16 refresh.
- **Key distinction:** `setRawDrawingRenderEnabled(false/true)` is a lightweight toggle — it hides/shows the overlay but does NOT clear the hardware buffer. `handwritingRepaint` is required to physically commit content to the EPD base layer. Never remove it from the clear path.

---

## Current Step

Next: implement stroke erasing.

---
*Last updated: clearCanvas phantom strokes + EPD ghosting — verified NA5C, P2P, GC7, NA4C, MIP11*
