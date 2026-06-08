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

## Active Backlog

- **Pruning backlog:** `apps/notesprout_android/PRUNING_BACKLOG.md` — hardening items from the
  2026-06-07 vulnerability/quality scan, with stable IDs (C-1, M-3, …). Reference an item by ID
  to work it; flip its Status checkbox and fill Notes/commit when done.

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
- Drawing engine: abstracted — OnyxDrawingEngine (BOOX) and GenericDrawingEngine (all others)
- Onyx SDK: onyxsdk-device:1.3.3 + onyxsdk-pen:1.5.4
- Onyx SDK repo: `http://repo.boox.com/repository/maven-public/` (insecure protocol — required, do not change)
- hiddenapibypass:4.3 from JitPack — required for Android 14+ BOOX devices (applied in `NoteSproutApplication.onCreate`)
- Database: Room/SQLite (`.soil` files)
- KSP: 2.2.20-2.0.4 (required for Room annotation processing with Kotlin 2.2.x)
- AGP 8.11.1 + Kotlin 2.2.20 + Gradle 8.14
- JSON serialization: `kotlinx.serialization` (code-generated, zero reflection — do not use `org.json`)
- Icons: **Tabler Icons (MIT)** — stroke-based SVGs converted to Android VectorDrawables, `@color/inkBlack`, 24dp. New icons must come from Tabler or be hand-crafted to match the Tabler stroke style. Do not use filled/solid icon sets.

---

## Architecture — Foundational Decisions

- Notebook = a `.soil` file (SQLite database with `.soil` extension)
- Notebook files live in the app's private external storage: `getExternalFilesDir(null)` → `Android/data/com.notesprout.android/files/<name>.soil` — no other location, no permissions required
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
- **Decode embedded images bounded — never at full resolution.** `.soil` files are portable user documents; a crafted/oversized embedded image can OOM the app (low threshold on e-ink). All embedded-asset decodes route through `core/BitmapDecode.decodeSampled(bytes, reqW, reqH)` (bounds-first + `inSampleSize`, capped to the target view/page dims; `MAX_DIMENSION=4096` fallback when there's no natural target, e.g. a cover sizing a PDF page). Do not call `BitmapFactory.decodeByteArray` directly on `.soil`-sourced bytes.
- **SQLite must stay clean.** The folder view in a file browser should show only `.soil` files — no WAL files, no SHM files, no journals left behind.
  - `PRAGMA journal_mode = WAL`
  - `PRAGMA wal_autocheckpoint = 100`
  - `PRAGMA auto_vacuum = INCREMENTAL`
  - Run `PRAGMA incremental_vacuum` and `PRAGMA wal_checkpoint(TRUNCATE)` on clean database close

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

- Open `.soil` files by absolute path: `Room.databaseBuilder(context, SoilDatabase::class.java, absolutePath)`
- Each open notebook gets its own Room database instance; close and release it when the notebook is closed
- `wal_autocheckpoint` is connection-level only — must be re-applied in `SoilDatabase.openCallback()` every open via `SupportSQLiteDatabase.query(...).use { it.moveToFirst() }`
- PRAGMAs that return a result set must use `rawQuery("PRAGMA ...", null).use { it.moveToFirst() }` — never `execSQL`, never bare `rawQuery` without consuming the cursor
- Any raw SQL touching the `order` column must double-quote it: `"order"` — it is a SQLite reserved word. Room's generated DAO handles this automatically; only hand-written raw SQL is at risk
- `closeNotebook()` must run `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)`, then `db.close()`, then delete any `-journal` artifact. This heavy seal lives in `suspend sealNotebook()` (`withContext(Dispatchers.IO)`) — **never `runBlocking` on the UI thread** (ANR risk scaling with stroke/snapshot size). User-initiated close (close button / back press) captures the snapshot on the main thread, launches `sealNotebook()` on `NoteSproutApplication.appScope` (a never-cancelled `SupervisorJob + Dispatchers.IO` scope that outlives the activity — `lifecycleScope` would be cancelled by `onDestroy` mid-seal), and `finish()`es immediately. The `onDestroy()` safety net calls `closeNotebook(blocking = true)` (`runBlocking`) for abnormal teardown only; the normal path already nulled `soilDatabase`, so it no-ops. Reading strokes off-thread is safe: `getStrokes()` returns a copy and `releaseResources()` never mutates the stroke list.
- **Any raw `SQLiteDatabase` opened on a `.soil` outside Room — even to only read — must open `OPEN_READWRITE`, not `OPEN_READONLY`.** A read-only WAL connection re-creates `-shm` on open and *cannot* unlink `-wal`/`-shm` on close (deletion needs write permission), so it permanently strands sidecars and violates the "folder shows only `.soil`" rule. Close such read connections via `SQLiteDatabase.checkpointTruncateAndClose(tag, file)` (`data/CoverLoader.kt`): it runs `wal_checkpoint(TRUNCATE)`, closes (so SQLite removes `-wal`/`-shm`), then deletes the empty `-journal` shell. Used by the cover/snapshot loaders (`CoverLoader.kt`, `MainActivity.loadLastPageSnapshot`).
- Raw read-*write* helpers (`data/PageCopier.kt` `copyPageAfterRaw`/`movePageAfterRaw`/`deletePageRaw`) mirror the Room close path: `checkpointAndVacuum()` (incremental_vacuum + wal_checkpoint TRUNCATE) before `db.close()`, then `cleanStrayJournal()`. They must NOT delete `-wal`/`-shm` themselves — DrawingActivity keeps its Room connection open to the same file while these run; SQLite removes those when that last connection closes. Multi-step writes (e.g. `deletePageRaw`'s three soft-deletes) must be wrapped in `beginTransaction()`/`setTransactionSuccessful()`/`endTransaction()`.
- Never silently swallow exceptions over raw DB ops — `Log.e` at minimum, and surface a user-visible failure (Toast) for write ops (see `PageIndexActivity` copy/move/delete).

---

## Design System — E-Ink First (Never Violate These)

NoteSprout's visual language is designed for e-ink displays first. All other platforms inherit this aesthetic.

**Palette (UI Chrome Only):**
- `inkBlack` = `#000000`
- `paperWhite` = `#FFFFFF`
- `inkLight` = `#888888` — disabled / secondary text only
- `borderGray` = `#CCCCCC` — subtle dividers only (**invisible on e-ink** — use inkBlack for any visible border)
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
- No decorative animations — including activity/screen transitions. `android:windowAnimationStyle="@null"` is set in `Theme.NoteSprout` to suppress all system-default slide/fade transitions globally.
- No pill-shaped buttons or fully sharp corners
- Do not use Material Components — theme is `Theme.AppCompat.Light.NoActionBar`, buttons are `AppCompatButton` with explicit drawable backgrounds. `com.google.android.material` is not a dependency — do not add it.

**AlertDialog styling pattern:**
- `dialog.window?.setSoftInputMode(...)` before `show()`
- `dialog.window?.setElevation(0f)` and `dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)` after `show()` — window only exists once shown

---

## Toolbar System

- Icons: Tabler Icons, stroke-based, `@color/inkBlack`, 24dp VectorDrawables in `res/drawable/ic_*.xml`
- `bg_toolbar_button` StateListDrawable: default = white fill, no border; selected/activated/pressed = white fill + 1.5dp black border
- `Widget.NoteSprout.ToolbarButton` style: 44dp, `bg_toolbar_button`, 10dp padding; overridden to 36dp/7dp in `res/values-sw360dp/` for Palma2 Pro
- Pen/eraser buttons: `isSelected = true` for persistent active-tool state
- Dividers: `@color/inkBlack`, 1dp × 28dp
- Undo/Redo buttons: statically always-enabled — tapping an empty stack silently does nothing (matches native BOOX behavior). Do not add alpha/tinting state.

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
| Supernote Nomad (SNN) | `SN078D10012852` |

## Installing on Devices

When asked to install on one or more devices:

1. **Trust the user.** If they say the devices are ready, skip `adb devices` — go straight to build and install.
2. **Build** the debug APK from the Android project root:
   ```
   cd apps/notesprout_android
   ./gradlew assembleDebug
   ```
3. **APK path:** `apps/notesprout_android/app/build/outputs/apk/debug/app-debug.apk`
4. **Install** on each requested device using its serial from the table above:
   ```
   adb -s <serial> install -r <apk-path>
   ```
5. Install on all requested devices in a single shell block — no need to do them one at a time.

**Do not** look for a project skill, run `adb devices`, or ask whether devices are plugged in. The user will say which devices to use by nickname (e.g. "NA5C", "P2P") — look up the serial in the table above.

---

## Build Variants

NoteSprout has two build variants:

- **Debug** (`com.notesprout.android.dev`) — active development build, installs alongside stable
- **Release** (`com.notesprout.android`) — stable build, never overwritten accidentally

### Default behavior

When asked to install without specifying a variant, **always build and install the debug APK**. Stable/release installs are always explicit.

### Building

**Debug APK:**
```
cd apps/notesprout_android
JAVA_HOME=<temurin-17-path> ./gradlew assembleDebug
```
APK output: `apps/notesprout_android/app/build/outputs/apk/debug/app-debug.apk`

**Release APK (unsigned, for local sideloading only):**
```
cd apps/notesprout_android
JAVA_HOME=<temurin-17-path> ./gradlew assembleRelease
```
APK output: `apps/notesprout_android/app/build/outputs/apk/release/app-release-unsigned.apk`

> Note: the release APK produced by `assembleRelease` is unsigned. Android rejects unsigned APKs — sign with the debug keystore before sideloading (see below). It cannot be submitted to the Play Store without a proper signing config — that is a separate future concern.

### Installing

Always build before installing — never install a stale APK.

**Install debug to a device:**
```
adb -s <serial> install -r apps/notesprout_android/app/build/outputs/apk/debug/app-debug.apk
```

**Install release to a device (sign with debug keystore first):**
```
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out apps/notesprout_android/app/build/outputs/apk/release/app-release-signed.apk \
  apps/notesprout_android/app/build/outputs/apk/release/app-release-unsigned.apk

adb -s <serial> install -r apps/notesprout_android/app/build/outputs/apk/release/app-release-signed.apk
```

Use device serials from the ADB Device Serials table above.

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

## Drawing Engine Architecture

### Files (package: `com.notesprout.android`)
- `drawing/DrawingView.kt` — interface implemented by both engines; all drawing, lasso, heading, and snapshot operations flow through it
- `drawing/OnyxDrawingView.kt` — BOOX path: TouchHelper, RawInputCallback. `onPenLifted` fires on `onEndRawDrawing`. `onBeginRawDrawing` re-enables render, guarded by `!isEraserMode`.
- `drawing/GenericDrawingView.kt` — standard Android Canvas: two-layer Bitmap, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture. `onPenLifted` fires on `ACTION_UP`.
- `DrawingActivity.kt` — fullscreen immersive, multi-page state, incremental save via `insertOrIgnore`, one-finger deliberate swipe for page navigation (three guards: distance ≥50% screen width, velocity ≥1.5× system fling threshold, horizontal dominance); two-finger swipe left/right inserts a new page after/before the current page and navigates to it (same three guards).
- `MainActivity.kt` — notebook list screen, adaptive grid (3/2 cols at 480dp), pagination, swipe, empty state, bottom bar.

### Key Build Facts
- `minSdk = 29`
- `android.enableJetifier=true` required — Onyx SDK bundles old support classes
- `jniLibs.pickFirsts` for `libc++_shared.so`
- `defaultConfig.ndk { abiFilters += "arm64-v8a" }` — every target device (BOOX, Wacom Movink, Supernote) is 64-bit ARM, so we ship only arm64-v8a. This drops the unused x86/x86_64/armeabi(-v7a) ABIs (smaller APK) and removes the only 4 KB-aligned native lib among them (mmkv's x86_64 `.so`) for Play 16 KB page-size compliance (M-5). **Do not** `exclude` the transitive `com.tencent:mmkv:1.0.19` — `onyxsdk-base` references it (`MMKVBuilder`, `DefaultSearchHistory`), so removing it risks a runtime `NoClassDefFoundError`; its arm64-v8a `libmmkv.so` is already 64 KB-aligned (16 KB-compliant). The lone remaining arm64 16 KB offender is ML Kit's `libdigitalink.so` — tracked as backlog L-10, deferred until Play distribution.
- `org.gradle.java.home` in `gradle.properties` pins Temurin-17
- `NoteSproutApplication.onCreate` calls `HiddenApiBypass.addHiddenApiExemptions("")` before any SDK init
- `setStrokeColor(Color.BLACK)` required on TouchHelper init — NoteAir5C color panel defaults to non-black
- Toolbar z-order: toolbar must overlay the drawing container in a `FrameLayout` — native SurfaceView occludes siblings below it

### EPD Rules — Never Violate These

**Overlay lifetime:**
- The overlay is "writing mode" — stays active indefinitely while the user writes. No idle-release timer.
- Legitimate handoff points ONLY: `setEraserMode(true)`, `clearCanvas()`, `setTemplate()`, `loadStrokesWithBitmap()`, `onWindowFocusChanged(false)`.
- `onPenLifted` is a DB-save trigger only — it does NOT touch the overlay.

**Overlay handoff sequence (`clearCanvas()`):**
- `setRawDrawingRenderEnabled(false)` → white bitmap → `invalidate()` → `EpdController.handwritingRepaint(view, Rect(0,0,w,h))` → re-enable
- **`handwritingRepaint` is required.** `setRawDrawingRenderEnabled` is a lightweight toggle; it does NOT clear the hardware buffer. Without `handwritingRepaint`: gray residue + black flash.
- `EpdController.setUpdListSize(512)` in `openRawDrawing()` suppresses mid-session GC16 refresh — do not remove.
- `renderStroke` calls `invalidate()` on every stroke so the Android canvas stays continuously current with the overlay, making handoff seamless.

**Eraser overlay:**
- On eraser start: `setRawDrawingRenderEnabled(false)`, `invalidate()` — immediately, before any erase logic. If not released first, the overlay hides the bitmap erase result (phantom strokes).
- `handwritingRepaint` after erase gesture ends only — NEVER during move events (causes full EPD flash per stroke).
- `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.

**setTemplate() EPD handoff (OnyxDrawingView):**
- `setRawDrawingRenderEnabled(false)` → `redrawCanvas()` → `EpdController.handwritingRepaint()` → `setRawDrawingEnabled(true)`. Without `handwritingRepaint`, the template change is invisible on e-ink until the next physical refresh.

### Performance Rules (Do Not Regress)

**Save path:**
- Wrap INSERT OR IGNORE loops in `db.withTransaction {}`
- Track `persistedStrokeIds` set; skip `toJson()` entirely for already-persisted strokes
- Use `kotlinx.serialization` — not `org.json`

**Load path:**
- `buildRenderBitmap()` on `Dispatchers.IO` — pre-builds white → template → strokes bitmap off the main thread
- `loadStrokesWithBitmap()` on main thread swaps the pre-built bitmap (~12ms main-thread cost)

**Erase path:**
- `LiveStroke.boundingBox: RectF` pre-computed at stroke creation; `eraseAtPath` builds an AABB of the eraser path and rejects non-intersecting strokes in O(4 floats) before any per-point geometry
- `throttledEraseRedraw()` — redraws at most once per 60ms during active erasing; strokes are removed from the list immediately
- `finalizeEraseRedraw()` forces one clean redraw on gesture end before `handwritingRepaint` commits pixels

**Logging (verbose/debug only):**
- Never call `Log.d` directly. Route all verbose/debug logging through `core/Slog.kt` — `Slog.d(tag) { "msg" }`. It is an `inline fun` gated on `BuildConfig.DEBUG`, so in release builds the message lambda is never evaluated (zero string-building cost on the e-ink per-stroke hot path) and nothing leaks to logcat (page UUIDs / layer IDs / sizes).
- This is the actual stripping mechanism: `isMinifyEnabled = false`, so R8 cannot strip `Log` calls and a ProGuard `assumenosideeffects` rule would not fire. `buildConfig = true` is enabled in `build.gradle.kts` to generate `BuildConfig.DEBUG`.
- `OnyxDrawingView.epd()` is `private inline fun epd(msg: () -> String)` delegating to `Slog.d` — all EPD-timing diagnostics are debug-only and free in release.
- `Log.e` / `Log.w` are kept (they must survive into release — see the raw-DB logging rule). Only verbose `Log.d` moves to `Slog`.

### Race condition — strokes missing on notebook reopen
- `loadStrokes()` is called in `onCreate()` before view layout. Fix: `onSizeChanged()` calls `redrawCanvas()` (not just white fill) to replay all currently-loaded strokes regardless of load order. Applied to both drawing views.

### Tool-state invariants across window focus changes (OnyxDrawingView)

When a Dialog is shown over DrawingActivity, the activity's window loses focus → `onWindowFocusChanged(false)` fires → `touchHelper.setRawDrawingEnabled(false)`. When the Dialog is dismissed, focus returns → `onWindowFocusChanged(true)` → `openRawDrawing()`. This is also triggered by `onResume()` → `enableDrawing()` when returning from any sub-Activity.

**The invariants that must be restored in `openRawDrawing()` and `enableDrawing()`:**

| Active tool | `setRawDrawingEnabled` | `setRawDrawingRenderEnabled` |
|---|---|---|
| Pen | `true` | `true` (default — SDK manages) |
| Eraser | `true` | `false` (render off prevents phantom pen strokes on overlay) |
| Lasso / Lasso Eraser | `false` | n/a |

- `openRawDrawing()` and `enableDrawing()` must guard `setRawDrawingEnabled(true)` with `!isLassoMode && !isLassoEraserMode`. If that guard passes and `isEraserMode` is true, immediately follow with `setRawDrawingRenderEnabled(false)`.
- Failing to restore these invariants causes phantom pen strokes to appear on the EPD overlay that are not captured by the app — they look real but are not persisted and vanish on the next EPD refresh.
- Every other `setRawDrawingEnabled(true)` call site in `OnyxDrawingView` already carries these guards; `openRawDrawing()` and `enableDrawing()` are the two centralized re-entry points and must match.

---

## Page Snapshot System

- Each page row's `data` JSON carries an optional `"snapshot"` field — a base64-encoded transparent-background PNG of all strokes.
- **No schema change** — snapshots live in the existing `data` TEXT column.
- Rendering order at load time: white → template → snapshot PNG → new strokes drawn this session.

**Snapshot content rules:**
- Transparent background only — do NOT fill with white or draw the template.
- `captureSnapshot()` returns `null` if `strokes` is empty or view isn't laid out (w=0/h=0).

**When snapshots are captured:**
- `setEraserMode(true)` — BEFORE `isEraserActive = true`
- `setTemplate(bitmap)` — BEFORE `templateBitmap = bitmap`
- `onWindowFocusChanged(false)` — app backgrounded or dialog overlay
- Page navigation — in `navigateToPage()` and add-page paths, BEFORE `clearCanvas()`
- Close/back — in `closeNotebook()`, captured on the main thread BEFORE the seal (`sealNotebook()`) is dispatched to IO
- **NOT on** user-initiated `clearCanvas()` or page delete — content is being discarded

**Critical: close path must capture snapshot synchronously.**
`onWindowFocusChanged(false)` fires AFTER `finish()` — `soilDatabase` is already null. Any path that calls `closeNotebook()` must capture the snapshot itself. Never rely on `onWindowFocusChanged` as the close-path snapshot trigger.

**Stale detection:**
- `NotebookDao.getMaxStrokeUpdatedAt(layerId)` — `SELECT MAX(updatedAt)` with **no** `deletedAt IS NULL` filter. Soft-deleted (erased) strokes have `updatedAt = deletedAt`, so erasures are detected as changes.
- If `maxStroke > page.updatedAt`, the snapshot is stale — full render runs and a fresh snapshot is captured.
- `persistSnapshot()` bumps `page.updatedAt` — this is the timestamp the stale check compares against.

**Two-phase page load (`DrawingActivity.loadCurrentPage`):**
1. `setupPageIds(db)` — resolves `currentPageId` / `currentLayerId`
2. `loadPageTemplateFromDb(db)` — decodes template bitmap (or null for blank)
3. `tryLoadSnapshotBitmap(db, templateBitmap)` — staleness check + composite build. Returns null on miss.
4. **Fast path** (snapshot hit): display composite immediately; deserialize strokes in background via `setStrokeListSilently()` — no visual redraw.
5. **Full path** (miss): `deserializeStrokesFromDb` + `buildRenderBitmap` off-thread; capture and persist snapshot for next load.

---

## Template System

- Templates are `type = "template"` rows stored in the `.soil` notebook database
- Template PNG files are stored in `getExternalFilesDir("Templates")` — imported by the user via system file picker (`ACTION_OPEN_DOCUMENT`)
- `data` JSON: `{ "width": Int, "height": Int, "name": String, "image": String (base64) }`
- `parseTemplateId(data)` reads `data.template` from a page row to get the active template UUID (empty = Blank)

**TemplateDialog (`TemplateDialog.kt`):**
- Two-tab dialog (All / Notebook), adaptive grid layout
- Grid columns: `if (widthPixels >= 1500) 4 else 2` — 4 on NA5C (1860px), 2 on P2P/GC7 (≤1264px)
- `thumbFrame` FrameLayout: `shape_bordered` background + **1dp padding** — padding insets the `ImageView` so it cannot render over the border stroke. Do NOT use `clipToOutline` here — it clips the border itself at rounded corners.

**Template inheritance on new page:**
- `addPage()` reads the current page **fresh from DB** via `dao.getObjectById(currentPageId)`. Do NOT read from the stale in-memory `pages` list — it is not refreshed after `applyTemplateToCurrentPage()` writes to DB.

---

## Undo/Redo System

- Unlimited undo/redo stack, scoped to a single `DrawingActivity` session (not persisted across process death).
- `history/UndoRedoAction.kt` — sealed class covering all action types: stroke add/erase, page add/delete/clear/copy/paste/move, lasso erase/cut/delete/paste/move, heading create/remove/text-edit.
- `history/UndoRedoManager.kt` — `undoStack` / `redoStack` as `ArrayDeque`. Redo stack cleared on any new user action.

**Key rule — cross-page actions:**
Do NOT call `saveAndSwitchPage()` for cross-page undo/redo — it calls `clearCanvas()` which wipes in-memory strokes. Use a two-phase approach: save/snapshot the leaving page inline → navigate → load from DB → apply the action → rebuild bitmap.

**Same-page stroke path:**
Never calls `clearCanvas()`. Updates the in-memory stroke list directly, rebuilds bitmap off-thread with `currentTemplateBitmap`, swaps via `loadStrokesWithBitmap`. Keep `persistedStrokeIds` in sync.

**`currentTemplateBitmap` field:**
`DrawingActivity` holds `private var currentTemplateBitmap: Bitmap?` set in `displayPage()`. Used by the same-page path to avoid re-reading the DB.

---

## Heading Objects

- `type = "heading"` rows in `.soil`; `HeadingObject` serialized to `data` column; `HeadingStroke` is the in-memory render representation.
- Headings render as grey-fill backgrounds with embedded strokes (or 20sp inkBlack canvas text when `recognizedText` is non-null).
- `recognizedText: String?` — null for legacy headings (renders strokes); non-null populated by ML Kit digital ink recognition at creation time.
- All lasso actions (move, copy, cut, paste, delete, eraser) treat headings as first-class participants alongside strokes.
- `ContentValues` keys for the `order` column in raw SQLite must use backtick quoting: `` "`order`" `` — `ContentValues` embeds column names verbatim.
- `copyPageAfter()` (Room) and `copyPageAfterRaw()` (raw SQLite) copy all layer-child object types, not just strokes — headings are included.

---

## Future Work — Wacom & Generic Android Stylus

**Wacom barrel button (MIP11 and other non-BOOX devices):**
- Barrel buttons set `BUTTON_STYLUS_PRIMARY` / `BUTTON_STYLUS_SECONDARY` flags on `MotionEvent` — they do not change `getToolType()`.
- Fix direction: check `event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)` in `onTouchEvent` and treat as eraser mode for the duration of that stroke.
- Low priority — do not let it block BOOX-first progress.

---

## Implemented Systems

**Core:**
- `.soil` schema + Room setup, SoilDatabase lifecycle
- Notebook list (MainActivity) — adaptive grid, pagination, cover images, Set Cover, Delete notebook
- New-notebook dialog pre-fills filename with `YYYYMMDD_HHmmss` timestamp (`java.time.LocalDateTime`, editable before confirm)
- New-notebook name validation (`MainActivity.validateNotebookName()`) — shared gate enforced at the dialog and re-checked defensively inside `createNotebook` before any file is opened. Whitelists `[^a-zA-Z0-9_\-. ]` (excludes `/` `\`) + explicit `.`/`..` reject to block path traversal (C-2); rejects a name whose `.soil` file already `exists()` to prevent reopening an existing notebook and inserting a duplicate `type='notebook'` row (C-1). Notebooks live in `getExternalFilesDir(null)` via `notesDir()` — no storage permissions required
- DrawingActivity — fullscreen immersive, multi-page, incremental save, one-finger deliberate swipe, two-finger swipe to insert page before/after
- Dual-install build variants — debug (`.dev` suffix) + release side-by-side

**Drawing & Tools:**
- OnyxDrawingView (BOOX/TouchHelper) + GenericDrawingView (standard Canvas)
- Template system — filesystem scan, per-notebook storage, inherit on new page
- Page Snapshot System — transparent PNG in page `data`, two-phase load, staleness detection
- Undo/Redo — session-scoped, all action types

**Page Management:**
- Add before/after, delete, clear (with confirmation + undo/redo)
- Two-finger swipe left → insert after current page; two-finger swipe right → insert before current page (same guards as 1-finger nav swipe; silent, immediate navigation)
- PageIndexActivity — snapshot grid, action mode (copy/paste/move/delete)

**Lasso Tools:**
- Lasso selection — draw to select strokes + headings, drag to move, stay-until-switched
- Lasso eraser — closed-path erase gesture with jitter overlay
- Floating selection toolbar: Copy, Cut, Delete, Create/Remove Heading
- Clipboard (`NoteSproutClipboard` singleton) — paste by stylus tap in lasso mode
- All lasso actions fully undo/redo with same-page optimized and cross-page two-phase paths

**Headings & Text:**
- HeadingObject (`type="heading"` rows) — grey-fill background + embedded strokes
- ML Kit digital ink recognition (en-US, `com.google.mlkit:digital-ink-recognition:18.1.0`) — `recognizedText` stored in `HeadingObject`
- Canvas text rendering (20sp inkBlack) when `recognizedText` non-null, else stroke render
- Text edit dialog (stylus tap on selected heading) + `HeadingTextEdited` undo/redo
- TOC (`TocDialog`) — topmost heading per page, paginated list, active entry indicator, dynamic page size

**Export to PDF:**
- `NotebookExporter` object — renders all pages off-screen on `Dispatchers.IO` using same white→template→headings→strokes pipeline as drawing views
- Cover page from `type="cover"` row becomes PDF page 1 (if present)
- Page dimensions from each page row's `boundingBox` → `PdfDocument.PageInfo`
- Output to `context.cacheDir/<title>.pdf`, exposed via `FileProvider` (`res/xml/file_paths.xml`, `${applicationId}.fileprovider`) with `Intent.ACTION_SEND` share sheet
- Share intent **must** include `clipData = ClipData.newRawUri("", uri)` alongside `FLAG_GRANT_READ_URI_PERMISSION` — on Android 12+ the chooser intermediary does not forward URI permissions to the final target app without `ClipData` (causes silent Google Drive upload failure on NA5C)
- Progress dialog (non-cancellable, `shape_bordered`, no animation): "Exporting page X of N…" via `Handler(Looper.getMainLooper())`
- Entry points: DrawingActivity toolbar (`btnExport`, after Cover) and MainActivity long-press context menu (Export as first item, opens Room DB read-only, closes after export)

---

*Last updated: Storage architecture migration — notebooks moved to `getExternalFilesDir(null)` (app-private external storage, no permissions required, Google Play compliant). Templates moved to `getExternalFilesDir("Templates")` with user-initiated import via `ACTION_OPEN_DOCUMENT` file picker; overwrite prompt if a template with the same name exists. `MANAGE_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` removed from the manifest entirely.*
