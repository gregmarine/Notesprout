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
- Database: Room/SQLite — **IMPLEMENTED — see Data Layer section**
- KSP: 2.2.20-2.0.4 (required for Room annotation processing with Kotlin 2.2.x)
- AGP 8.11.1 + Kotlin 2.2.20 + Gradle 8.14

---

## Architecture — Foundational Decisions

- Notebook = a `.soil` file (SQLite database with `.soil` extension)
- Notebook files live at: `/Documents/NoteSprout/<notebook-name>.soil` — no other location
- Hierarchy: Notebook → Pages → Layers → Content Objects
- Layers: base layer (template, locked) and content layers
- Every object carries: id, parentId, boundingBox, order, createdAt, updatedAt, deletedAt, data
- Stroke data: proprietary point arrays (x, y, pressure, tilt, timestamp), stored as JSON in the `data` TEXT column
- Soft deletes with cleanup process
- Stable UUIDs everywhere

---

## Data Layer — `.soil` Files

### Core Rules — Never Violate These

- **One file per notebook.** Each `.soil` file is a self-contained SQLite database.
- **Single table.** Everything — pages, layers, strokes, images, text, metadata — is a row in one `notebook` table. No exceptions without explicit discussion.
- **Everything is an object.** There is no special-casing of types at the schema level. Type behavior lives in Kotlin, not in the database schema.
- **Assets are base64 strings.** No external files, no file references. Images and other binary assets are stored inline as base64 in the `data` TEXT column.
- **SQLite must stay clean.** The folder view in a file browser should show only `.soil` files — no WAL files, no SHM files, no journals left behind.
  - `PRAGMA journal_mode = WAL` — enables WAL mode
  - `PRAGMA wal_autocheckpoint = 100` — checkpoint after 100 pages
  - `PRAGMA auto_vacuum = INCREMENTAL` — reclaims space without full vacuum
  - Run `PRAGMA incremental_vacuum` and `PRAGMA wal_checkpoint(TRUNCATE)` on clean database close to truncate WAL to zero bytes before the connection is released

### Object Schema

```sql
CREATE TABLE IF NOT EXISTS notebook (
    id          TEXT    PRIMARY KEY NOT NULL,
    parentId    TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    boundingBox TEXT    NOT NULL,
    "order"     INTEGER NOT NULL DEFAULT 0,
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    data        TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
    ON notebook(parentId, "order", deletedAt);
```

### Room Setup Rules

- Room database class opens `.soil` files by absolute path — not from `assets/` or `getDatabasePath()`
- Use `Room.databaseBuilder(context, SoilDatabase::class.java, absolutePath)`
- Each open notebook gets its own Room database instance; close and release it when the notebook is closed
- `wal_autocheckpoint` is connection-level only — must be re-applied in `SoilDatabase.openCallback()` every open via `SupportSQLiteDatabase.query(...).use { it.moveToFirst() }`
- PRAGMAs that return a result set must use `rawQuery("PRAGMA ...", null).use { it.moveToFirst() }` — never `execSQL`, never bare `rawQuery` without consuming the cursor
- Any raw SQL touching the `order` column must double-quote it: `"order"` — it is a SQLite reserved word. Room's generated DAO handles this automatically; only hand-written raw SQL is at risk
- `closeNotebook()` must run `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)`, then `db.close()`, then delete any `-journal` artifact

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
- Do not use Material Components — theme is `Theme.AppCompat.Light.NoActionBar`, buttons are `AppCompatButton` with explicit drawable backgrounds. `com.google.android.material` is not a dependency — do not add it.

**AlertDialog styling pattern:**
- `dialog.window?.setSoftInputMode(...)` before `show()`
- `dialog.window?.setElevation(0f)` and `dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)` after `show()` — window only exists once shown
- This removes the floating card shadow and replaces it with the flat white/1dp-black-border consistent with all other UI

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
| BOOX Tab XC (TXC) | `d852bed0` |
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
- `drawing/DrawingView.kt` — interface: `asView()`, `setToolbarHeight(Int)`, `enableDrawing()`, `disableDrawing()`, `resetOverlay()`, `clearCanvas()`, `setEraserMode(Boolean)`, `releaseResources()`, `loadStrokes(List<LiveStroke>)`, `getStrokes(): List<LiveStroke>`, `onStrokeErased: ((String) -> Unit)?`, `onPenLifted: (() -> Unit)?`
- `drawing/OnyxDrawingView.kt` — BOOX path: TouchHelper, RawInputCallback, limit rect. `renderStroke` calls `invalidate()` on every stroke to keep the Android canvas continuously current with the hardware overlay. The EPD overlay stays active for the entire writing session — no idle release timer. Handoff only happens at non-writing transitions: `setEraserMode(true)`, `clearCanvas()`, `setTemplate()`, `loadStrokesWithBitmap()`, `onWindowFocusChanged(false)`. `onPenLifted` fires on `onEndRawDrawing` (each pen lift) to persist new strokes to DB. `onBeginRawDrawing` re-enables render — guarded by `!isEraserMode`. `EpdController.setUpdListSize(512)` called on every `openRawDrawing()`.
- `drawing/GenericDrawingView.kt` — standard Android Canvas: two-layer Bitmap, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture. `onPenLifted` fires directly on `ACTION_UP` — no timer.
- `DrawingActivity.kt` — fullscreen immersive, multi-page state (`pages`, `currentPageIndex`, `currentPageId`, `currentLayerId`), incremental save via `insertOrIgnore`, `onStrokeErased` callback for targeted soft-delete, swipe gesture for page navigation.
- `MainActivity.kt` — notebook list screen, adaptive grid, pagination, swipe, empty state, bottom bar.

### Key Build Facts
- `minSdk = 29`
- `android.enableJetifier=true` required — Onyx SDK bundles old support classes
- `jniLibs.pickFirsts` for `libc++_shared.so`
- `org.gradle.java.home` in `gradle.properties` pins Temurin-17
- `NoteSproutApplication.onCreate` calls `HiddenApiBypass.addHiddenApiExemptions("")` before any SDK init
- `setStrokeColor(Color.BLACK)` required on TouchHelper init — NoteAir5C color panel defaults to non-black
- Toolbar z-order: toolbar must overlay the drawing container in a `FrameLayout` — native SurfaceView occludes siblings below it

---

## Pruning Log — Architectural Lessons (Non-Obvious Rules)

### EPD overlay lifetime — stays active during writing
- **The overlay is "writing mode."** It stays active indefinitely while the user is writing. There is no idle-release timer. This matches the behavior of the native Onyx notes app.
- Releasing the overlay mid-session (e.g., after 1.5 s of inactivity) triggers a full-panel GC16/REGAL quality refresh on color e-ink (NoteAir5C) when the base layer is stale — the visible flicker the idle timer was causing.
- **Non-writing transitions** are the only legitimate handoff points: `setEraserMode(true)` (tool switch), `clearCanvas()` (page clear), `setTemplate()` (template change), `loadStrokesWithBitmap()` (page flip), `onWindowFocusChanged(false)` (app moved to background). All of these already follow the full handoff sequence.
- `onPenLifted` fires on every `onEndRawDrawing` (Onyx) / `ACTION_UP` (Generic) to persist new strokes to DB. This is purely a DB save trigger — it does not touch the overlay.

### EPD handoff — flicker-free canvas transition
- `renderStroke` calls `invalidate()` on every stroke so the Android canvas stays continuously current with the hardware overlay. Overlay removal is always seamless.
- `clearCanvas()` owns the overlay handoff: disable render → white bitmap → `invalidate()` → `EpdController.handwritingRepaint(view, Rect(0,0,w,h))` → re-enable.
- **Never remove `handwritingRepaint` from the clear path.** `setRawDrawingRenderEnabled(false/true)` is a lightweight toggle — it hides/shows the overlay but does NOT clear the hardware buffer. `handwritingRepaint` is required to physically commit content to the EPD base layer. Without it: gray residue (EPD pixels not refreshed) and black flash (stale overlay buffer renders at full black on re-enable).
- `EpdController.setUpdListSize(512)` in `openRawDrawing()` suppresses the hardware's automatic mid-session GC16 refresh. Do not remove.

### Eraser overlay — release render before erasing begins
- When erasing begins (toolbar toggle or physical pen flip), release the overlay render immediately: `setRawDrawingRenderEnabled(false)`, `invalidate()`.
- If the overlay is still enabled when erasing starts, the hardware overlay hides the already-correct bitmap erase result — causing phantom strokes and delayed visual feedback.
- `handwritingRepaint` in `onEndRawErasing` / `onEndRawDrawing` (eraser mode) still commits clean EPD pixels on lift. Never call `handwritingRepaint` in the erase move path — it causes a full EPD quality flash on every erased stroke.
- `onBeginRawDrawing` re-enables render — guarded by `!isEraserMode` to prevent rogue overlay stroke during software eraser.

### Page navigation performance — dense pages (600+ strokes)
Three compounding slowdowns eliminated on heavy pages:

**Save path** (`DrawingActivity.saveStrokes`):
- Wrap INSERT OR IGNORE loop in `db.withTransaction {}` — 610-stroke save: 2500ms → 1300ms (−48%).
- Track `persistedStrokeIds` set; skip `toJson()` entirely for strokes already in DB — fully-persisted page: 1300ms → 1–5ms (−99.8%).
- Replace `org.json` with `kotlinx.serialization` (code-generated, zero reflection, same wire format, no DB migration) — warm JSON deserialize: 1325ms → 845ms (−36%).

**Load path** (`DrawingActivity.loadStrokesFromDb`):
- In-memory `strokeCache: LinkedHashMap<pageId, List<LiveStroke>>` (access-order LRU, capped at 10 pages / ~6 MB). Cache hit skips DB query + JSON deserialization entirely — 610-stroke load: ~900ms → 15–19ms (−98%).
- `snapshotCurrentPageToCache()` called before every page transition so unsaved in-memory strokes are always captured. Cache refreshed on erase, cleared on page-clear/delete.

**Render path** (both drawing views):
- `buildRenderBitmap()` on `Dispatchers.IO` pre-builds white → template → strokes bitmap off the main thread.
- `loadStrokesWithBitmap()` on main thread swaps the pre-built bitmap in 12–13ms instead of the previous 118–129ms O(N) on-thread redraw.
- Combined page-turn TOTAL on a 610-stroke page (warm cache): 1163ms → 246ms (−79%). Main thread blocked: 118ms → 12ms (−90%).

### Eraser performance — dense pages (600+ strokes)
Two compounding freezes eliminated; erasing on 600+ stroke pages went from multi-second app-unresponsive to instant.

**Hit test** (`eraseAtPath` in both drawing views):
- Old: O(S × P × E) — every stroke point tested against every eraser segment per move event. 600 strokes × 50 pts × 5 eraser pts = 150,000 float ops per touch-move.
- Fix: `LiveStroke.boundingBox: RectF` pre-computed at stroke creation. `eraseAtPath` builds an expanded AABB of the eraser path and rejects non-intersecting strokes in O(4 floats) before any per-point geometry. Eliminates ~95% of candidates instantly.
- `strokes.removeAll { it.id in removeIds }` (HashSet of String IDs) replaces `removeAll(toRemove.toSet())` which triggered expensive data-class equality on `List<PointF>`.

**Redraw throttle** (both drawing views):
- Old: `redrawCanvas()` (O(N) — redraws all strokes to bitmap) called on every single erase move event. 30–50 events × 200ms each = 6–10 s main-thread block.
- Fix: `throttledEraseRedraw()` — redraws at most once per 60ms (~16fps) during active erasing. Strokes are removed from the list immediately so data is always correct; only the visual refresh is coalesced.
- `finalizeEraseRedraw()` forces one clean redraw at gesture end (`onEndRawErasing`, `onEndRawDrawing` in eraser mode, `ACTION_UP` in GenericDrawingView) before `handwritingRepaint` commits pixels to the e-ink panel.

---

## Future Work — Wacom & Generic Android Stylus

### Wacom stylus barrel button (MIP11 and other non-BOOX devices)
- Barrel buttons set `BUTTON_STYLUS_PRIMARY` / `BUTTON_STYLUS_SECONDARY` flags on `MotionEvent` — they do not change `getToolType()`.
- `GenericDrawingView` currently only inspects tool type; barrel buttons have no effect.
- Fix direction: check `event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)` in `onTouchEvent` and treat as eraser mode for the duration of that stroke.
- Low priority — do not let it block BOOX-first progress.

---

## Template System (Implemented)

### Overview
- Templates are `type = "template"` rows stored in the `.soil` notebook database
- Template PNG files are scanned from `/sdcard/Documents/NoteSprout/Templates/` at dialog open time
- Selecting a template from the "All" tab inserts it into the notebook DB (base64-encoded PNG in `data`) and returns the full-res `Bitmap` to `DrawingActivity`
- Selecting from the "Notebook" tab decodes the already-stored base64 and returns the full-res `Bitmap`
- Template `data` JSON: `{ "width": Int, "height": Int, "name": String, "image": String (base64) }`
- `parseTemplateId(data)` reads `data.template` from a page row to get the active template UUID (empty = Blank)

### TemplateDialog
- `TemplateDialog.kt` — two-tab dialog (All / Notebook), adaptive grid layout
- Grid columns: `if (widthPixels >= 1500) 4 else 2` — 4 on NA5C (1860px), 2 on P2P/GC7 (≤1264px)
- Thumbnails: decoded at `inSampleSize ≤ 4` (target ~1300px longest side); `THUMB_HEIGHT_DP = 200`
- `thumbFrame` FrameLayout: `shape_bordered` background + **1dp padding** — the padding insets the `ImageView` so it cannot render over the border stroke. Do NOT use `clipToOutline` here — it clips the border stroke itself at the rounded corners, making the top/bottom borders appear truncated.
- Selected cell: `shape_bordered` background on outer `cell` LinearLayout

### Template inheritance on new page
- `addPage()` in `DrawingActivity` reads the current page **fresh from DB** via `dao.getObjectById(currentPageId)` before computing `inheritedTemplate`. Do NOT read from the stale in-memory `pages` list — it is not refreshed after `applyTemplateToCurrentPage()` writes to DB.

### setTemplate() EPD handoff (OnyxDrawingView)
- `setTemplate()` must follow the full EPD handoff pattern: `setRawDrawingRenderEnabled(false)` → `redrawCanvas()` → `EpdController.handwritingRepaint()` → `setRawDrawingEnabled(true)`. Without `handwritingRepaint`, the template change is invisible on e-ink until the next physical refresh.

### Race condition — strokes missing on notebook reopen
- `loadStrokes()` is called in `onCreate()` before view layout; if the DB query completes before `onSizeChanged()`, `redrawCanvas()` is a no-op (null canvas).
- Fix: `onSizeChanged()` calls `redrawCanvas()` (not just white fill) — this replays all currently-loaded strokes onto the fresh bitmap regardless of load order. Applied to both `OnyxDrawingView` and `GenericDrawingView`.

---

## Current Step

**MVP iteration — multi-page drawing with persistence.**

Completed:
- `.soil` schema + Room setup
- Notebook list screen (MainActivity)
- Open notebook → DrawingActivity + Room DB lifecycle
- Basic stroke persistence (save/load)
- Multi-page support: LiveStroke, incremental save, add/delete/swipe pages
- Swipe-left on last page inserts a new page (natural continuous writing flow)
- Clear page confirmation dialog
- Notebook metadata row (`type = "notebook"`) — title, cover, last_opened_page
- Restore last-opened page on every notebook open; persist on every page turn
- Auto-open new notebook in DrawingActivity immediately after creation (no extra tap required); list re-scans on back-press via onResume
- Template system: scan from device filesystem, store in `.soil`, apply to page, inherit on new page, adaptive grid dialog
- EPD overlay flicker fix: overlay stays active during writing; released only at non-writing transitions (tool switch, page flip, page clear, focus loss). `onIdleSave` renamed to `onPenLifted`; saves triggered per pen lift instead of idle timer.

Next up: TBD — discuss before starting.

---
*Last updated: EPD overlay flicker fix + onPenLifted*