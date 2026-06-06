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
- `closeNotebook()` must run `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)`, then `db.close()`, then delete any `-journal` artifact

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

## Toolbar System (Implemented)

- Icons: Tabler Icons, stroke-based, `@color/inkBlack`, 24dp VectorDrawables in `res/drawable/ic_*.xml`
- Custom icons follow Tabler conventions: `ic_close` (notebook silhouette + left-exit arrow, spine offset for clear separation), `ic_new_notebook` (notebook with open lower-right corner + plus in the notch)
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

## Drawing Engine Architecture (Implemented)

### Files (package: `com.notesprout.android`)
- `drawing/DrawingView.kt` — interface: `asView()`, `setToolbarHeight(Int)`, `enableDrawing()`, `disableDrawing()`, `resetOverlay()`, `clearCanvas()`, `setEraserMode(Boolean)`, `releaseResources()`, `loadStrokes(List<LiveStroke>)`, `getStrokes(): List<LiveStroke>`, `buildRenderBitmap(List<LiveStroke>, Bitmap?): Bitmap?`, `loadStrokesWithBitmap(List<LiveStroke>, Bitmap, Bitmap?)`, `captureSnapshot(): String?`, `setStrokeListSilently(List<LiveStroke>)`, `onStrokeErased: ((String) -> Unit)?`, `onPenLifted: (() -> Unit)?`, `onSnapshotReady: ((String) -> Unit)?`
- `drawing/OnyxDrawingView.kt` — BOOX path: TouchHelper, RawInputCallback. `onPenLifted` fires on `onEndRawDrawing`. `onBeginRawDrawing` re-enables render, guarded by `!isEraserMode`.
- `drawing/GenericDrawingView.kt` — standard Android Canvas: two-layer Bitmap, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture. `onPenLifted` fires on `ACTION_UP`.
- `DrawingActivity.kt` — fullscreen immersive, multi-page state, incremental save via `insertOrIgnore`, `onStrokeErased` callback for targeted soft-delete, two-finger swipe for page navigation.
- `MainActivity.kt` — notebook list screen, adaptive grid, pagination, swipe, empty state, bottom bar.

### Key Build Facts
- `minSdk = 29`
- `android.enableJetifier=true` required — Onyx SDK bundles old support classes
- `jniLibs.pickFirsts` for `libc++_shared.so`
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

### Race condition — strokes missing on notebook reopen
- `loadStrokes()` is called in `onCreate()` before view layout. Fix: `onSizeChanged()` calls `redrawCanvas()` (not just white fill) to replay all currently-loaded strokes regardless of load order. Applied to both drawing views.

---

## Page Snapshot System (Implemented)

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
- Close/back — in `closeNotebook()`, on the main thread BEFORE the `runBlocking` IO block
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

## Template System (Implemented)

- Templates are `type = "template"` rows stored in the `.soil` notebook database
- Template PNG files are scanned from `/sdcard/Documents/NoteSprout/Templates/` at dialog open time
- `data` JSON: `{ "width": Int, "height": Int, "name": String, "image": String (base64) }`
- `parseTemplateId(data)` reads `data.template` from a page row to get the active template UUID (empty = Blank)

**TemplateDialog (`TemplateDialog.kt`):**
- Two-tab dialog (All / Notebook), adaptive grid layout
- Grid columns: `if (widthPixels >= 1500) 4 else 2` — 4 on NA5C (1860px), 2 on P2P/GC7 (≤1264px)
- `thumbFrame` FrameLayout: `shape_bordered` background + **1dp padding** — padding insets the `ImageView` so it cannot render over the border stroke. Do NOT use `clipToOutline` here — it clips the border itself at rounded corners.

**Template inheritance on new page:**
- `addPage()` reads the current page **fresh from DB** via `dao.getObjectById(currentPageId)`. Do NOT read from the stale in-memory `pages` list — it is not refreshed after `applyTemplateToCurrentPage()` writes to DB.

---

## Undo/Redo System (Implemented)

- Unlimited undo/redo stack, scoped to a single `DrawingActivity` session (not persisted across process death).
- `history/UndoRedoAction.kt` — sealed class: `StrokeAdded(strokeId, pageId)`, `StrokeErased(strokeId, pageId)`, `PageAdded(pageId)`
- `history/UndoRedoManager.kt` — `undoStack` / `redoStack` as `ArrayDeque`. Redo stack cleared on any new user action.

**`executeAction(action, isUndo)` — three paths:**

**Cross-page stroke** (`action.pageId != currentPageId`):
- Do NOT call `saveAndSwitchPage()` — it calls `clearCanvas()` which wipes in-memory strokes, causing a blank page.
- Phase 1: inline save/snapshot of the leaving page → navigate to target page → load from DB → display with stroke in pre-undo state.
- Phase 2: apply DB soft-delete or restore → rebuild bitmap → display. User sees the stroke appear/disappear in real time.

**Same-page stroke** (`action.pageId == currentPageId`):
- Never calls `clearCanvas()`. Updates in-memory stroke list directly (filter to remove, append from DB to restore).
- `buildRenderBitmap` off-thread with `currentTemplateBitmap` → `loadStrokesWithBitmap` — one EPD handoff.
- Keep `persistedStrokeIds` in sync: `remove(strokeId)` on soft-delete, `add(strokeId)` on restore.

**Page actions (`PageAdded`) and all others:**
- Full reload: invalidate snapshot → `clearCanvas()` → `loadCurrentPage()` → `displayPage()` → `postDisplayWork()`.

**`currentTemplateBitmap` field:**
- `DrawingActivity` holds `private var currentTemplateBitmap: Bitmap?` set in `displayPage()`. Used by the same-page stroke path to avoid re-reading the DB.

---

## Future Work — Wacom & Generic Android Stylus

**Wacom barrel button (MIP11 and other non-BOOX devices):**
- Barrel buttons set `BUTTON_STYLUS_PRIMARY` / `BUTTON_STYLUS_SECONDARY` flags on `MotionEvent` — they do not change `getToolType()`.
- Fix direction: check `event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)` in `onTouchEvent` and treat as eraser mode for the duration of that stroke.
- Low priority — do not let it block BOOX-first progress.

---

## Current Step

**MVP iteration — multi-page drawing with persistence.**

Completed:
- `.soil` schema + Room setup
- Notebook list screen (MainActivity) with adaptive grid, pagination, swipe
- Open notebook → DrawingActivity + Room DB lifecycle
- Multi-page support: LiveStroke, incremental save, add/delete/swipe pages
- Swipe-left on last page inserts a new page; two-finger fling required (palm rejection)
- Clear page confirmation dialog
- Notebook metadata row — title, cover, last_opened_page; restored on every open
- Auto-open new notebook immediately after creation
- Template system: filesystem scan, store in `.soil`, apply to page, inherit on new page
- EPD overlay lifetime fix: overlay stays active during writing; handoff only at non-writing transitions
- Page Snapshot System: transparent-background PNG in page `data` JSON; two-phase load; stale detection; persists across process death
- Undo/Redo system: session-scoped, three action types, optimized same-page path, two-phase cross-page path
- Toolbar icon system: Tabler Icons, custom state drawables, responsive sizing for Palma2 Pro
- Pruning: clear-page now tracked as `PageCleared` undo/redo action (timestamp-anchored restore via `restoreChildrenDeletedSince`)
- Pruning: suppress all activity transition animations via `android:windowAnimationStyle="@null"` in `Theme.NoteSprout`
- 🌱 New Branch: Notebook deletion via long-press context menu in MainActivity — AlertDialog mini-menu (elevation=0, shape_bordered) → confirmation dialog naming the notebook → IO-dispatched file + sibling artefact deletion → grid refresh; `ic_delete_notebook` Tabler-style VectorDrawable (notebook with minus badge)
- 🌱 New Branch: Cover image display in notebook grid — `NotebookMetadata` migrated to `kotlinx.serialization`; `CoverObject` data class; `getCoverForNotebook` + `getLastOpenedPageSnapshot` DAO methods; `loadNotebookCoverBitmap` suspend fun opens `.soil` read-only (plain `SQLiteDatabase`), resolves cover in order: explicit cover object → last-opened page snapshot → null; `buildCardGroup` shows cover via `centerCrop` `ImageView` (paperWhite FrameLayout, 1dp inset from border) with `ic_notebook` fallback; per-card coroutine jobs tracked in `coverLoadJobs` and cancelled on each re-render
- 🌱 New Branch: Set Cover — `CoverDialog` (two-card layout: "Last Opened Page" / "Select Image"; pending-selection state with `bg_toolbar_button` selected visual; image file picker with screen-height downsampling to PNG base64; Apply/Cancel buttons; IO-dispatched cover upsert: soft-delete old cover row → insert new `type="cover"` row → update `NotebookMetadata.cover`); long-press context menu in MainActivity replaced with `PopupMenu` anchored to card (group 1: Set Cover; group 2: Delete Notebook); `ic_polaroid` Tabler-style VectorDrawable (frame + bottom strip + photo rect); `btnCover` toolbar button in DrawingActivity next to close; cover-reload callback triggers `scanAndRender()` in MainActivity; `ActivityResultContracts.GetContent` launcher pre-registered in both activities
- 🌱 New Branch: Insert Page Before — `btnInsertPageBefore` / `btnInsertPageAfter` replace the old `btnAddPage` in the toolbar; `ic_insert_page_before` and `ic_insert_page_after` Tabler-style VectorDrawables (file-plus with 2dp vertical bar on left/right respectively, gap-separated, aligned to document body height); `addPageBefore()` mirrors `addPage()` with `insertionIndex = currentPageIndex`; `UndoRedoAction.PageAdded` gains `insertedBefore: Boolean = false` so undo lands on the correct page (original page returns to `pageIndex` after removal, not `pageIndex - 1`)
- 🌱 New Branch: Page Index — `PageIndexActivity` full-screen grid of page snapshot cards; `ic_files` Tabler-style VectorDrawable (two overlapping document outlines); `btnPageIndex` toolbar button grouped with template (no separator); `tvPageIndicator` tap also launches index; `bg_page_card_current` 3dp-border drawable highlights the currently-open page; same adaptive GridSpec / pagination controls (`|< < n/n > >|`) as MainActivity; cards show page snapshot (transparent PNG composited on white) + "Page N" label; tapping any card calls `setResult` with selected index and finishes; `openPageIndex()` in DrawingActivity captures + persists current snapshot before launch so index always shows fresh state; on result, `navigateToPage(selected)` if index changed

- ✂️ Pruning: Unified grid column counts — both `MainActivity` and `PageIndexActivity` now use `if (screenWidthDp >= 480f) 3 else 2`; large e-ink tablets (NA5C, ~988dp) get 3 columns, phone-form-factor devices (P2P, ~439dp portrait) get 2 columns

- 🌱 New Branch: Copy/Paste Page — `ic_copy_page` (single file + solid L on left/bottom + plus inside body) and `ic_paste_page` (single file + dashed L on left/bottom, 2-on/4-off hand-drawn segments — Android VectorDrawable has no `strokeDashArray`); both icons shift the file body up 2 units to give 2dp visual gap from the L-shape; copy/paste buttons in DrawingActivity toolbar grouped with page management controls (after delete); `pendingCopyPageId: String?` per-activity clipboard; copy toggles clipboard on/off; paste calls `copyPageAfter()` (Room-based, for DrawingActivity) or `copyPageAfterRaw()` (raw `SQLiteDatabase` writable, for PageIndexActivity — safe in WAL mode while DrawingActivity is paused); `data/PageCopier.kt` holds both implementations; `order` column must be backtick-quoted in `ContentValues` keys (`"\`order\`"`) — `ContentValues` embeds column names verbatim and `order` is a SQLite reserved word; long-press on a card in PageIndexActivity enters action mode (Copy + Paste buttons appear, single selection highlight, normal-mode current-page highlight suppressed — only one card highlighted at a time); Copy stays in action mode with paste immediately enabled; Paste refreshes the grid in place and accumulates `pastedActions`; `finishWithResult()` encodes all session pastes as comma-separated extras on any exit path; DrawingActivity pushes a `UndoRedoAction.PagePasted` for each on return; `UndoRedoAction.PagePasted(pageId, pageIndex, undoDeletedAt)` — undo soft-deletes page + layer + strokes at a recorded timestamp; `undoRedoManager.amendLastRedo()` stores that timestamp back into the redo entry so redo can use `restoreChildrenDeletedSince` to restore exactly those rows

- 🌱 New Branch: Delete Page from Page Index — `btnDeletePage` (using existing `ic_page_delete`) appears in PageIndexActivity action mode alongside Copy and Paste; confirmation dialog matches DrawingActivity style (`"Delete Page N?"`, elevation=0, `shape_bordered`); last-page guard shows a toast and blocks deletion if only one page remains; `deletePageRaw()` added to `PageCopier.kt` — same cascade soft-delete pattern as `DrawingActivity.deletePage()` with a single shared timestamp so `restoreChildrenDeletedSince` can restore exactly those rows on undo; deleted actions accumulated in session list and returned to DrawingActivity via result extras (`EXTRA_DELETED_PAGE_IDS/INDICES/TIMESTAMPS`); DrawingActivity pushes `UndoRedoAction.PageDeleted` for each on return; `pageIndexLauncher` now calls `navigateToPage(currentPageIndex)` whenever any session actions occurred (paste or delete) even if no card was tapped — forces a full pages-list reload and canvas refresh so deletions are immediately visible on return without requiring a swipe

- 🌱 New Branch: Move Page from Page Index — `ic_move_page` Tabler-style VectorDrawable (file body + right-pointing arrow inside); `btnMovePage` appears in PageIndexActivity action mode alongside Copy/Paste/Delete; tapping Move enters **move mode** (`isMoveMode` flag, `moveModeSourcePageId`): toolbar hides all action buttons, title changes to `"Move to…"`, source card stays highlighted; tapping any other card calls `executeMoveAfter()` which calls `movePageAfterRaw()` (new in `PageCopier.kt`) — reorders page `order` values in one raw SQLite transaction, returns the previous-after page ID for undo; tapping the source card or the back button calls `cancelMoveMode()` which restores action mode; after a successful move, `completeMoveMode()` exits to normal mode; `currentPageId` field tracks the stable ID of the currently-open page so `currentPageIndex` can be recomputed correctly after reordering; move actions returned to DrawingActivity via `EXTRA_MOVED_PAGE_IDS/PREV_AFTER_IDS/TARGET_IDS`; DrawingActivity pushes `UndoRedoAction.PageMoved(pageId, previousAfterPageId, targetPageId)` for each; `movePageToAfter(db, pageId, afterPageId)` Room-based helper reassigns all page orders in a transaction; undo/redo does a full page reload (no snapshot invalidation — content unchanged, only order)

- 🌱 New Branch: Lasso Selection Tool (visual only, no actions yet) — `ic_lasso` Tabler-style VectorDrawable with hand-crafted dashes (4 arc segments ~70° each forming a broken oval loop + rope tail, since VectorDrawable has no strokeDashArray); `btnLasso` added to toolbar in pen/eraser group; `DrawingView` interface gains `setLassoMode`, `setLassoOverlay`, `onLassoComplete`, `onLassoTapToDismiss`; both drawing views implement lasso: stylus-only gesture capture with 60ms throttled `invalidate()` during drawing, dashed `DashPathEffect(12f, 8f)` overlay paint drawn on top of strokes bitmap in `onDraw`; `OnyxDrawingView` disables `TouchHelper` raw drawing entirely on lasso entry (`setRawDrawingEnabled(false)` + `setRawDrawingRenderEnabled(false)`) so stylus events arrive via normal `onTouchEvent` and raw callbacks don't fire; lasso exit restores raw drawing via `handwritingRepaint` + `setRawDrawingEnabled(true)` in `post {}` chain; `setLassoOverlay` triggers `handwritingRepaint` for clean e-ink render of selection box; `DrawingActivity` adds `enterLassoMode` / `exitLassoMode(restorePreviousTool)` — enter captures `lastNonLassoTool` (pen or eraser); tap-to-dismiss restores that tool; `onLassoComplete`: auto-closes drawn path (lineTo start + close), runs two-phase hit test off main thread (`Dispatchers.Default`) — Phase 1: AABB filter via `LiveStroke.boundingBox`, Phase 2: `Region.setPath()` + `Region.contains()` point-in-polygon test; result is dashed selection bounding box padded 8dp; zero-result gesture silently clears and stays in lasso mode; `selectedObjectIds: MutableSet<String>` holds selected stroke IDs for future actions

- ✂️ Pruning: Lasso gesture correctness fixes — re-lasso while selection active now clears the old selection on `ACTION_DOWN` and starts a fresh gesture instead of dismissing lasso mode; stylus tap-to-dismiss now uses an 8dp distance threshold at `ACTION_UP` (tap vs. drag decision deferred until lift, so lasso mode stays active for the full gesture and no stroke is ever drawn on tap); finger tap with active selection restores dismiss via a non-stylus early-out before the stylus guard; `selectedObjectIds` cleared at the top of `onLassoComplete` so stale IDs from a prior selection never survive into a new gesture

- 🌱 New Branch: Lasso Eraser Tool — `ic_lasso_eraser` VectorDrawable (eraser body identical to `ic_eraser`, translated +1/−3 to match `ic_paste_page` corner clearance, with dashed L indicator in bottom-left); `btnLassoEraser` placed in toolbar between eraser and page-clear (eraser group: eraser | lasso eraser | clear | lasso); `UndoRedoAction.LassoErased(strokeIds, pageId)` — atomic multi-stroke batch; `DrawingView` interface gains `setLassoEraserMode(Boolean)` and `onLassoEraseComplete: ((List<String>) -> Unit)?`; both drawing views implement: `isLassoEraserMode` flag, EPD raw-drawing disabled on entry (Onyx), `handleLassoEraserTouch` captures stylus gesture identically to lasso selection, `performLassoErase` closes path and runs two-phase hit test (AABB + `Region.setPath`/`contains`) on a background `Thread`, fires `onLassoEraseComplete` with hit IDs then clears overlay and calls `handwritingRepaint`; lasso eraser overlay uses a separate `lassoEraserDisplayPath` with ±4px jitter baked in per point at draw time (no `PathEffect`) drawn with a 5dp mid-grey (`#969696`) stroke — grain is static, not animated; `DrawingActivity` adds `isLassoEraserMode`, `enterLassoEraserMode`/`exitLassoEraserMode` (never auto-exits — user must tap another tool), `onLassoEraseComplete` callback soft-deletes hit rows on IO, updates in-memory stroke list, syncs `persistedStrokeIds`, pushes `LassoErased` action, and rebuilds canvas; `executeAction` handles `LassoErased` with same-page optimised batch path and cross-page two-phase path matching single-stroke patterns

- ✂️ Pruning: Lasso Eraser correctness fixes — all `setRawDrawingEnabled(true)` re-enable calls in `OnyxDrawingView` (`loadStrokesWithBitmap`, `clearCanvas`, `setTemplate`, `setLassoMode` exit, `setLassoEraserMode` exit) now guarded by `!isLassoMode && !isLassoEraserMode` to prevent the BOOX hardware EPD stylus path from re-activating mid-lasso and rendering phantom pen strokes; fixes phantom stroke after first lasso-erase, unresponsive state on zero-hit second gesture, and broken lasso↔lasso-eraser tool switching (deferred `post {}` chain was racing with the incoming tool's synchronous disable call)

- ✂️ Pruning: Lasso Selection Tool — remain active until tool change — lasso selection now matches lasso eraser's "stay until switched" behavior; completing a gesture (with or without hits) stays in lasso mode; tap-to-dismiss clears the selection visual but stays in lasso mode; `lastNonLassoTool` tracking and `DrawingTool` enum removed; `exitLassoMode(restorePreviousTool)` parameter removed — callers are only the explicit tool-button tap handlers

- 🌱 New Branch: Lasso Move Action — pen-down inside the selection box begins a drag; pen-down outside clears the selection and starts a fresh lasso gesture; `DRAG_THRESHOLD_DP = 8f` constant in `drawing/DrawingConstants.kt` replaces all hardcoded tap-threshold values in both drawing views; **Option A rendering**: `dragBackingBitmap` (non-selected strokes + template, built once at drag-start) held in memory; `onDraw` composites backing + translated selected strokes + offset selection box during drag — no `clearCanvas()` anywhere in the move path; on lift above threshold: point arrays rewritten in memory, `lassoSelectionBox` translated to match, DB rows updated atomically via `updateStrokeData()` in a `withTransaction` block, one `UndoRedoAction.StrokesMoved(pageId, originalStrokes, movedStrokes)` pushed; below threshold: silent no-op; **BOOX**: enters `UpdateMode.GU_FAST` (A2 fast EPD refresh) at threshold crossing via `EpdController.setViewDefaultUpdateMode`, restored to `GU` + `handwritingRepaint` at lift; **GenericDrawingView**: identical logic, no EPD calls; `LiveStroke` gains `@Serializable` with `PointFListSerializer` surrogate (kotlinx.serialization, no reflection) and `@Transient` on `boundingBox` so full point data survives undo/redo JSON persistence; `DrawingView` interface gains `lassoSelectedIds: Set<String>`, `setDragMoveMode(Boolean)`, and `onStrokesMoved` callback; `DrawingActivity` syncs `lassoSelectedIds` in `onLassoComplete`/`onLassoTapToDismiss`; `executeAction` handles `StrokesMoved` with optimized same-page path (in-memory replace + bitmap rebuild, selection box updated if moved IDs match active selection) and two-phase cross-page path; selection box and `selectedObjectIds` preserved after a completed move so the user can drag again immediately

- 🌱 New Branch: Lasso Copy/Paste — `NoteSproutClipboard` app-level singleton (`ClipboardContent(strokes, boundingBox)`, not cleared after paste); `UndoRedoAction.LassoPasted(strokeIds, pageId, insertedAt)` — undo = soft-delete by ID, redo = restore by ID (inverse of `LassoErased`); three new VectorDrawables: `ic_lasso_clipboard` (lasso loop + plus crosshair in oval center), `ic_copy_plus` (Tabler copy-plus: two overlapping docs + plus in front body), `ic_clipboard_x` (Tabler clipboard-x: clipboard body + X mark); `floatingSelectionToolbar` LinearLayout (elevation=0, `shape_bordered`, `visibility=gone`) positioned programmatically below/above selection box — contains `btnLassoCopy`; `lassoPopupToolbar` LinearLayout (same style) anchored below `btnLasso` — contains `btnLassoClearClipboard`; `DrawingView` interface gains `onLassoTap`, `onDragStarted`, `onLassoSelectionCleared`, `setLassoSelectedIds(ids, box)`; both drawing views fire `onLassoTap(x, y)` on stylus tap alongside `onLassoTapToDismiss`, `onDragStarted` at drag threshold, `onLassoSelectionCleared` on ACTION_DOWN when selection was active; paste triggered by stylus tap in lasso mode with no active selection and clipboard content — offsets all strokes to tap point, inserts to DB in `withTransaction`, pushes `LassoPasted`, calls `setLassoSelectedIds` to show pasted selection box, reshows floating toolbar; `btnLasso` icon updates to `ic_lasso_clipboard` / `ic_lasso` via `updateLassoButtonIcon()` (also called in `onResume` so icon persists across notebook switches); `executeAction` handles `LassoPasted` with same-page optimised path (undo = filter out IDs, redo = fetch from DB + append) and cross-page two-phase path; `StrokesMoved` undo/redo now calls `updateFloatingSelectionToolbar(unionBounds)` so the context toolbar tracks the restored selection position

- ✂️ Pruning: Lasso context toolbar follows undo/redo — `StrokesMoved` same-page path calls `updateFloatingSelectionToolbar(unionBounds)` after restoring selection box so the floating toolbar tracks strokes back on undo; cross-page path calls `hideFloatingSelectionToolbar()` when clearing selection on page switch

- 🌱 New Branch: Lasso Cut Action — `ic_cut` Tabler cut VectorDrawable (two circles at bottom for finger-hole handles + two blade lines rising to a small open gap at top-center, matching Tabler outline/cut.svg exactly); `UndoRedoAction.LassoCut(strokeIds, pageId, deletedAt, strokes)` — undo = restore by ID (does not touch clipboard), redo = re-soft-delete by ID + repopulate `NoteSproutClipboard` from `strokes` + union bounding box; `btnLassoCut` added to `floatingSelectionToolbar` after `btnLassoCopy`; `performLassoCut()` deep-copies selected strokes, populates clipboard (identical payload to lasso copy), soft-deletes rows on IO with a shared `deletedAt` timestamp, removes from in-memory stroke list, syncs `persistedStrokeIds`, pushes `LassoCut`, rebuilds canvas, clears selection visual, stays in lasso mode, updates lasso button icon to clipboard variant; `executeAction` handles `LassoCut` with same-page optimised path (undo = fetch from DB + append, redo = filter + clipboard repopulate + icon update) and cross-page two-phase path matching `LassoErased` pattern

- 🌱 New Branch: Lasso Delete Action — `ic_lasso_delete` Tabler trash VectorDrawable (lid bar, two inner vertical lines, rounded trapezoid body, handle cap); `UndoRedoAction.LassoDeleted(strokeIds, pageId, deletedAt, strokes)` — undo = restore by ID, redo = re-soft-delete by ID; clipboard never touched; `btnLassoDelete` added to `floatingSelectionToolbar` after `btnLassoCut` (Copy → Cut → Delete order); `performLassoDelete()` mirrors `performLassoCut()` minus all clipboard interaction — soft-deletes rows on IO with shared `deletedAt`, removes from in-memory stroke list, syncs `persistedStrokeIds`, pushes `LassoDeleted`, rebuilds canvas, clears selection visual, stays in lasso mode; `executeAction` handles `LassoDeleted` with same-page optimised path and cross-page two-phase path identical to `LassoErased`

- ✂️ Pruning: Rounded corners on snapshot thumbnail cards — `MainActivity` notebook cards and `PageIndexActivity` page index cards were rendering cover/snapshot images with square-clipped corners; root cause was an intermediate `imageContainer` FrameLayout child with a flat `setBackgroundColor(paperWhite)` (rectangular, no shape) that painted over the 4dp corner arcs of the `shape_bordered` card; fix mirrors the working TemplateDialog pattern: remove `imageContainer`, add `card.setPadding(1dp)` (or 3dp for highlighted cards whose border is 3dp), add children directly to `card`; removed now-unused `paperWhiteColor` lazy field from both activities

- 🌱 New Branch: Heading Object — `HeadingStroke` (render-time in-memory: `id`, `boundingBox: RectF`, `strokes: List<LiveStroke>`) + `HeadingObject` (DB-stored, serialized to `data` column); `type = "heading"` rows in `.soil`; `HeadingStroke` is `@Serializable` with `RectFSerializer` surrogate (matches `PointFSerializer` pattern); `DrawingView` interface gains `loadHeadings`, `getHeadings`, `onHeadingErased`, `buildRenderBitmap` overloaded to accept optional `headings` param; both drawing views render heading grey-fill backgrounds + embedded strokes behind all live strokes; lasso hit test extended to include heading bounding boxes via AABB + `Region` point-in-polygon; lasso eraser also hits headings; `btnCreateHeading` in `floatingSelectionToolbar` — lasso-selected strokes are soft-deleted, a new `type="heading"` row is inserted with a `HeadingObject(strokes)` JSON payload, `UndoRedoAction.HeadingCreated` pushed; `btnRemoveHeading` appears in floating toolbar when a lasso selection contains exactly one heading (and no strokes) — heading row soft-deleted, embedded strokes re-inserted as individual live rows with fresh UUIDs, `UndoRedoAction.HeadingRemoved` pushed; all lasso actions (move, copy, cut, paste, delete, eraser) extended to treat headings as first-class participants: `StrokesMoved` gains `originalHeadings`/`movedHeadings`; `LassoErased`/`LassoCut`/`LassoDeleted` gain `headingIds`/`headings`; `LassoPasted` gains `headingIds`; `NoteSproutClipboard.ClipboardContent` gains `headings: List<HeadingStroke>`; drag backing bitmap excludes selected headings, `onDraw` translates them during drag; same-page and cross-page undo/redo paths all load/rebuild headings; `computeUnionBoundingBox(strokes, headings)` helper unifies bounding box for selection toolbar and undo actions; `updateHeadingData` DAO method updates `boundingBox` + `data` + `updatedAt` atomically; all `UndoRedoAction` new fields use `= emptyList()` defaults for backward compat with serialized stacks

- ✂️ Pruning: Stroke Eraser + Heading Undo — three fixes: (1) `onHeadingErased` callback changed to pass the full `HeadingStroke` (not just the ID) because the view removes the heading from its in-memory list before the callback fires, so ID alone was insufficient for undo; erasing a heading via stroke eraser now pushes a `LassoErased` undo action so undo/redo correctly restores the heading. (2) Phantom pen stroke after lasso exit or heading erase on BOOX: deferred `setRawDrawingEnabled(true)` calls in `OnyxDrawingView` now re-apply `setRawDrawingRenderEnabled(false)` when `isEraserMode` is true, preventing the BOOX SDK from silently re-enabling the pen render path. (3) Headings visually disappearing on stroke undo/redo: all three `buildRenderBitmap` call sites in the stroke undo/redo path (same-page and both phases of cross-page) were missing the `headings` parameter — the rebuilt bitmap painted strokes without heading backgrounds.

- ✂️ Pruning: Keep heading selected after creation with correct selection padding — after `createHeadingFromStrokes` completes the newly created heading is now kept selected with the floating toolbar visible (Un-heading, Copy, Cut, Delete ready immediately); the selection box is padded by an additional 8dp outset (matching `val pad = 8f * resources.displayMetrics.density; RectF(boundingBox).also { it.inset(-pad, -pad) }`) to match the visual treatment applied when re-selecting an existing heading via lasso.

- ✂️ Pruning: Lasso paste/dismiss and selection-on-page-turn fixes — three fixes: (1) Paste fired on tap even with an active selection: root cause was `lassoSelectionBox` already nulled at `ACTION_DOWN` (outside-box tap), so the `ACTION_UP` check saw no selection and paste fired; fix adds `lassoGestureHadSelection` flag captured at `ACTION_DOWN` and checked at `ACTION_UP` in both drawing views — `onLassoTap` is suppressed when the gesture started with an active selection. (2) Finger/palm touch cleared lasso selection: removed the non-stylus early-out block in `handleLassoTouch` from both drawing views; selection can now only be cleared by a stylus tap outside the box. (3) Lasso selection visual (dashed box + floating toolbar) persisted after page turn: `navigateToPageInternal` and the swipe-to-new-page path in `evaluatePageFling` now call `selectedObjectIds.clear()` + `drawingView.setLassoOverlay(null, null)` + `hideFloatingSelectionToolbar()` before `clearCanvas()`.

- 🌱 New Branch: Dual-install build variants — `applicationIdSuffix = ".dev"` added to debug build type so debug (`com.notesprout.android.dev`) and release (`com.notesprout.android`) install side-by-side on the same device; `app/src/debug/res/values/strings.xml` overrides `app_name` to "NoteSprout Dev" for the launcher; CLAUDE.md updated with build variant instructions and corrected release signing steps (unsigned APKs must be signed with the debug keystore via `apksigner` before sideloading — `--bypass-verification` is not supported on BOOX devices)

- 🌱 New Branch: Table of Contents — `ic_toc` VectorDrawable (Tabler list icon); `btnToc` in DrawingActivity toolbar (after close, new separator, before cover); `TocEntry` data class; `TocRepository` picks topmost heading per page (min boundingBox.top, left tiebreaker), returns sorted `List<TocEntry>`; `activity_toc.xml` responsive layout (full-screen < 480dp with btnTocClose, sidebar 60% width >= 480dp); `shape_toc_panel_border` right-border drawable; `HeadingThumbnailView` (Matrix.setRectToRect START scaling, inkBlack strokes, paperWhite background, width-clipped); `item_toc_entry.xml` row layout; `TocActivity`: paginated list (6 items/page, 52dp heading max height), swipe left/right to page, tap row navigates to page + closes, tap-outside dismisses (sidebar), empty state "No headings available", scrim background (sidebar), no animations; DrawingActivity `tocLauncher` wires result back to `navigateToPage`

Next up: TBD — discuss before starting.

---
*Last updated: 🌱 New Branch — Table of Contents*
