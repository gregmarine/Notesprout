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
    id          TEXT    PRIMARY KEY,         -- UUID
    parentId    TEXT    NOT NULL,            -- UUID of parent object
    boundingBox TEXT    NOT NULL,            -- JSON {"x":0.0,"y":0.0,"width":0.0,"height":0.0}
    "order"     INTEGER NOT NULL DEFAULT 0,  -- sort order among siblings
    createdAt   INTEGER NOT NULL,            -- Unix epoch ms
    updatedAt   INTEGER NOT NULL,            -- Unix epoch ms
    deletedAt   INTEGER,                     -- null = alive; soft delete
    data        TEXT    NOT NULL             -- type-owned JSON blob
);

CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
    ON notebook(parentId, "order", deletedAt);
```

### Room Setup Rules

- Room database class opens `.soil` files by absolute path — not from `assets/` or `getDatabasePath()`
- Use `Room.databaseBuilder(context, SoilDatabase::class.java, absolutePath)`
- Each open notebook gets its own Room database instance; close and release it when the notebook is closed
- Do not implement Room until explicitly instructed

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
- `drawing/DrawingView.kt` — interface: `asView()`, `setToolbarHeight(Int)`, `enableDrawing()`, `disableDrawing()`, `resetOverlay()`, `clearCanvas()`, `setEraserMode(Boolean)`, `releaseResources()`
- `drawing/OnyxDrawingView.kt` — BOOX path: TouchHelper, RawInputCallback, limit rect. Key pattern: `renderStroke` calls `invalidate()` on every stroke to keep the Android canvas continuously current with the hardware overlay. `clearCanvas()` owns the overlay handoff: disable render → white bitmap → `invalidate()` → `EpdController.handwritingRepaint()` (bakes white into physical EPD pixels) → re-enable. `resetOverlay()` toggles `setRawDrawingRenderEnabled` off/on (test utility). `onBeginRawDrawing` re-enables render when a new stroke starts — guarded by `!isEraserMode` to prevent rogue overlay stroke during software eraser. `EpdController.setUpdListSize(512)` called on every `openRawDrawing()` to suppress hardware auto-GC16 mid-session. Erasing: stroke store (`strokes: MutableList<List<PointF>>`), point-to-segment distance hit test, `redrawCanvas()` rebuilds bitmap on erase. `handwritingRepaint` called in `onEndRawErasing` and `onEndRawDrawing` (eraser mode) to commit clean EPD pixels after erase. Overlay NOT enabled during erasing (physical or software) so bitmap updates are immediately visible.
- `drawing/GenericDrawingView.kt` — standard Android Canvas: two-layer Bitmap approach, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture for smooth strokes. Same stroke store + eraseAtPath as OnyxDrawingView. Erasing fires on `ACTION_MOVE` for immediate feedback.
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
- Do not create multiple tables — everything is a single `notebook` table per `.soil` file
- Do not store assets as files or file references — base64 strings only

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

### New Branch: Stroke erasing (verified NA5C, P2P, GC7, NA4C, MIP11)
- **Toolbar toggle:** `DrawingActivity` owns `isEraserActive` state; toggles button label ("Erase"/"Pen") and calls `drawingView.setEraserMode(Boolean)`.
- **OnyxDrawingView:** `isEraserMode` flag gates overlay re-enable in `onBeginRawDrawing`. Software eraser points arrive via `onRawDrawingTouchPointMoveReceived` + `onRawDrawingTouchPointListReceived`; physical eraser via `onBeginRawErasing` / `onRawErasingTouchPointMoveReceived` / `onRawErasingTouchPointListReceived`. Move callbacks call `eraseAtPath` per-point for immediate feedback. `handwritingRepaint` in end callbacks commits clean EPD pixels to EPD.
- **GenericDrawingView:** `isEraserActive` flag; `TOOL_TYPE_ERASER` accepted alongside `TOOL_TYPE_STYLUS`. Erasing handled on `ACTION_MOVE` (immediate) + `ACTION_UP` (final point).
- **Stroke store:** Both views maintain `strokes: MutableList<List<PointF>>`. `eraseAtPath` uses point-to-segment distance (`ERASER_RADIUS_PX = 15f`) to find intersecting strokes, removes them, calls `redrawCanvas()` which rebuilds the bitmap and calls `invalidate()` for immediate visual update.
- **Key rule:** Never call `handwritingRepaint` in the erase move path — only on pen/eraser lift. Move-path repaint causes a full EPD quality flash on every erased stroke.

---

## Current Step

**MVP iteration — data layer foundation.**

Immediate scope (in order):
1. ~~Define the `notebook` table schema~~ — **DONE** (see Object Schema above)
2. ~~Room setup + `.soil` file creation at `/Documents/NoteSprout/`~~ — **DONE** (MainActivity "New Notebook" screen; raw SQLiteDatabase; verified NA5C Android 15)
3. ~~Notebook list screen (reads `.soil` files from that directory)~~ — **DONE** (adaptive grid, pagination, swipe, empty state, bottom bar)
4. ~~Open a notebook (instantiates a Room DB from the `.soil` file path)~~ — **DONE** (DrawingActivity DB lifecycle; verified NA5C, P2P)
5. Basic stroke data persistence (save/load strokes from the `notebook` table)

Do not get ahead of this list. Complete one step, verify, then move to the next.

---

## Pruning: AlertDialog styling + keyboard focus (verified NA5C, P2P — Android 15)
- **Shadow removal:** `AlertDialog` carries implicit elevation from AppCompat's default window background. Fix: `dialog.window?.setElevation(0f)` + `dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)` — replaces the floating card with the same flat white/1dp-black-border drawable used by buttons and inputs throughout the app.
- **Build order matters:** `setSoftInputMode` must be called before `show()`; `setElevation` and `setBackgroundDrawableResource` must be called after `show()` (window background only exists once the window is created). Pattern: `create()` → `setSoftInputMode` → `show()` → style window → focus field.
- **Keyboard auto-open:** `SOFT_INPUT_STATE_VISIBLE` (a hint) and `InputMethodManager.showSoftInput` (deprecated API 30+) both silently fail on Android 11+. The correct API is `ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())`. Must be called in `postDelayed(100)` — the dialog window needs ~100ms to become the active input connection. `InputMethodManager.showSoftInput` is kept as the API 29 fallback via the `?: run { }` branch.
- **BOOX e-ink note:** The `WindowInsetsController` path works on NA5C (Android 15) — BOOX's own soft keyboard appears. Earlier assumption that BOOX suppresses the soft keyboard was wrong; it shows BOOX's keyboard variant, not a standard QWERTY.

---

## Pruning: .soil creation — rawQuery lazy execution + journal cleanup (verified NA5C Android 15)
- **Root cause 1 — PRAGMAs not applied:** Android's `SQLiteDatabase.execSQL()` rejects any SQL that returns a result set ("Queries can be performed using SQLiteDatabase query or rawQuery methods only"). Switching to `rawQuery(...).close()` is not enough — `rawQuery` is lazy and never executes the SQL unless the cursor is consumed. Fix: `rawQuery("PRAGMA ...", null).use { it.moveToFirst() }` forces execution.
- **Affected PRAGMAs:** `journal_mode = WAL`, `wal_autocheckpoint = 100`, `auto_vacuum = INCREMENTAL`, `incremental_vacuum`, `wal_checkpoint(TRUNCATE)` — all require `rawQuery` + `moveToFirst()`.
- **Root cause 2 — journal file artifact:** Android's SQLiteDatabase creates an empty `-journal` file during database initialisation before WAL mode is set. It persists after `db.close()`. Fix: explicitly delete `<name>.soil-journal` after `db.close()`.
- **wal_autocheckpoint is connection-level only** — not stored in the database file. Must be re-applied every time the database is opened. Room's `openCallback` / `SupportSQLiteDatabase` hooks are the right place for this.
- **Storage permissions on Android 15 (NA5C):** `MANAGE_EXTERNAL_STORAGE` was already granted on the test device — no runtime prompt observed. The permission flow (Settings → All Files Access) is in place for fresh installs.

---

### New Branch: Notebook list screen (MainActivity replacement — verified NA5C, P2P)
- **Layout:** `activity_main.xml` is a vertical LinearLayout: `FrameLayout` (weight=1, grid area) + `RelativeLayout` (100dp bottom bar). Bottom bar has a 1dp inkBlack top border, pagination controls centered via an inner horizontal LinearLayout, and the + button right-aligned.
- **Column count — screen-width breakpoints (dp):**
  - `≥ 720dp` → 5 columns (large tablet, e.g. BOOX NoteAir)
  - `480–719dp` → 4 columns (mid-size tablet)
  - `< 480dp` → 3 columns (phone / small device)
- **Adaptive grid:** Computed in `computeGridSpec()` after the first `onGlobalLayout` fires. Card aspect ratio = physical screen height ÷ width (portrait). Card width fills each column cleanly; card height follows the aspect ratio. Row count = how many complete rows fit the available height after 16dp top/bottom padding.
- **Grid alignment:** Top-aligned (`Gravity.TOP or Gravity.CENTER_HORIZONTAL`), 16dp top margin via `containerLp.topMargin = spec.paddingVPx` — matches the 16dp side gutters baked into card widths.
- **Cards (programmatic, no RecyclerView):** Each card group is a vertical LinearLayout: card `FrameLayout` (`shape_bordered` background, `ic_notebook` icon centered inside) + label `TextView` below (not inside the card). Rows are horizontal LinearLayouts; `Space` views provide gutters. All added to a centered outer LinearLayout inside `gridContainer`.
- **Empty state:** When no `.soil` files exist, a centered inkLight message replaces the grid.
- **Pagination:** `navigatePage(Int)` clamps and re-renders. `updatePaginationControls()` sets enabled state and text color (inkBlack / inkLight) on all five controls. Button text 22sp, 16dp vertical padding; bar height 100dp.
- **Swipe:** `GestureDetector.SimpleOnGestureListener.onFling` — horizontal flings only when `|velocityX| > |velocityY|`. No animation.
- **Scan on resume:** `onResume` calls `scanAndRender()` directly if `gridSpec` is ready; otherwise sets `pendingScan = true` so the global layout listener triggers the scan after the first measure.
- **New notebook → page jump:** After `createNotebook`, `scanAndRender()` re-scans the sorted list, then `navigatePage(idx / itemsPerPage)` lands on the page containing the new file. (alphabetical sort)
- **`ic_notebook.xml`:** Vector drawable, 48×48 viewport. White page body (pentagon with folded top-right corner at 33,4→42,13), fold crease (two-segment path), three content lines — all inkBlack 1.5dp stroke.
- **`GestureDetector` (not `GestureDetectorCompat`):** minSdk 29 — no compat wrapper needed.

---

### New Branch: Open notebook — DrawingActivity + Room DB lifecycle (verified NA5C, P2P)
- **Intent extra:** `DrawingActivity.EXTRA_NOTEBOOK_PATH` (`"notebook_path"`) — absolute path to the `.soil` file. `MainActivity.openNotebook(file)` puts this on the intent when a card is tapped.
- **Room open:** `Room.databaseBuilder(applicationContext, SoilDatabase::class.java, absolutePath).addCallback(SoilDatabase.openCallback()).allowMainThreadQueries().build()`. Called in `onCreate` after reading the extra. `allowMainThreadQueries()` is a temporary stub — removed in step 5 when queries move off main thread.
- **`SoilDatabase.openCallback()`:** Re-applies `PRAGMA wal_autocheckpoint = 100` on every open via `SupportSQLiteDatabase.query(...).use { it.moveToFirst() }`. This pragma is connection-level only and is not stored in the file.
- **`closeNotebook()`:** Idempotent (null-guards `soilDatabase`). Calls `saveStrokes()` stub, runs `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)` on `db.openHelper.writableDatabase`, then `db.close()`, then deletes any `-journal` artifact. Called from: Close button, `OnBackPressedCallback`, and `onDestroy` safety net.
- **Toolbar:** `btnClose` on the left; `tvNotebookName` (center, shows `file.nameWithoutExtension`); `btnClear` and `btnEraser` on the right. Close and back both route through `closeNotebook()` before `finish()`.
- **Persistence stubs:** `saveStrokes()` and `loadStrokes()` are no-op private methods with TODO comments. `loadStrokes()` is called after DB open; `saveStrokes()` is called at the top of `closeNotebook()`.
- **Data layer files:** `data/NotebookObject.kt` (`@Entity`), `data/NotebookDao.kt` (empty `@Dao` stub), `data/SoilDatabase.kt` (`@Database`, one instance per notebook, no singleton).

### Pruning: Room schema mismatch — `.soil` files rejected on open (verified NA5C, P2P)
- **Symptom:** `IllegalStateException: Pre-packaged database has an invalid schema: notebook`. Crash on every notebook open. Room validates the existing file's schema against the entity definition before allowing queries.
- **Mismatch 1 — `id.notNull`:** Expected `true`, found `false`. SQLite's `TEXT PRIMARY KEY` syntax does NOT imply `NOT NULL` in column metadata — a quirk unique to SQLite. Room's `@PrimaryKey` always expects `notNull = true`. Fix: `id TEXT PRIMARY KEY` → `id TEXT NOT NULL PRIMARY KEY` in `MainActivity.createNotebook()`.
- **Mismatch 2 — `order.defaultValue`:** Expected `'undefined'`, found `'0'`. `DEFAULT 0` was in the CREATE TABLE but not declared in `@ColumnInfo`, so Room expected no default. Fix: `@ColumnInfo(name = "order", defaultValue = "0")` on `NotebookObject.sortOrder`.
- **Mismatch 3 — unexpected index:** Expected `indices = {}`, found `idx_notebook_parent_order`. The entity declared no indices, but `createNotebook()` created one. Room flags any index in the file that the entity doesn't declare. Fix: added `@Entity(indices = [Index(name = "idx_notebook_parent_order", value = ["parentId", "order", "deletedAt"])])` to `NotebookObject`.
- **General rule:** Every `DEFAULT`, `NOT NULL`, and index in the raw `CREATE TABLE` must be mirrored exactly in the Room entity annotation. When these diverge, Room refuses to open the file.
- **Cleanup required:** Existing `.soil` files created before this fix have the wrong schema and must be deleted. They cannot be migrated (no data yet). After any CREATE TABLE change, clear `/sdcard/Documents/NoteSprout/` on all devices before testing.

---

## Future Work — Wacom & Generic Android Stylus

### Wacom stylus barrel button (MIP11 and other non-BOOX devices)
- **What:** Barrel button(s) on Wacom/USI styli do not change `getToolType()` — they set `BUTTON_STYLUS_PRIMARY` / `BUTTON_STYLUS_SECONDARY` flags on the `MotionEvent`.
- **Current state:** `GenericDrawingView` only inspects tool type, so barrel buttons have no effect. The eraser toggle in the toolbar works fine. Physical eraser end (`TOOL_TYPE_ERASER`) works fine.
- **When to address:** When focusing on Wacom and generic Android device optimization. Low priority — do not let it block BOOX-first progress.
- **Fix direction:** Check `event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)` in `onTouchEvent` and treat it as an eraser mode for the duration of that stroke.

---
*Last updated: open notebook + Room DB lifecycle complete — next step: basic stroke persistence*