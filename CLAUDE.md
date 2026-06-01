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

Next up: TBD — discuss before starting.

---
*Last updated: 🌱 New Branch — Delete Page from Page Index*
