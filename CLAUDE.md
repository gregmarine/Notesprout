# Notesprout — Claude Code Project Intelligence

## What is Notesprout?
A handwriting-first, meditative notes app. Think paper, but smarter underneath. Built for e-ink devices first (BOOX), expanding to iPad, Android tablets, phones, and web.

**Slogan:** "Where thought has a place to grow 🌱"
**License:** MIT
**Monorepo root:** ~/git/Notesprout

- apps/notesprout_android — Native Android app (primary active codebase)

---

## Core Philosophy — Never Violate These

- Human-first: fixed screen-size pages, never infinite scroll
- Meditative, paper-like writing experience
- A coexistence of human and machine — intelligent underneath, calm on the surface
- Everything is an object (universal BaseObject model — relational, compositional)
- Pages feel like physical pages. The app should never feel like a web app.

---

## Standard Constraints

These apply everywhere — do not repeat them in feature sections.

- **Language:** Kotlin (Java 17 target — use Temurin-17 JDK; `org.gradle.java.home` in `gradle.properties` pins Temurin-17)
- **JSON serialization:** `kotlinx.serialization` only — zero reflection, code-generated. Never use `org.json`. Use `toJson()` / `fromJson()`.
- **No new Gradle dependencies** without explicit discussion.
- **No Material Components** — `com.google.android.material` is not a dependency; do not add it.
- **Never `runBlocking` on the UI thread** — ANR risk, especially on large stroke/snapshot data.
- **No `Log.d` directly** — use `Slog.d(tag) { "msg" }` (`core/Slog.kt`, `inline fun` gated on `BuildConfig.DEBUG`). Release builds pay zero cost (lambda never evaluated). `Log.e` / `Log.w` survive into release.

---

## Architecture — Foundational Decisions

- Notebook = a `.soil` file (SQLite database with `.soil` extension)
- Notebook files live in `getExternalFilesDir(null)/Garden/<uuid>.soil` — flat directory, UUID filenames, no permissions required
- Folder/notebook structure is maintained exclusively in the global index (`notesprout.db`) — never derived from the filesystem
- **`soilFile(context, notebookId)` (`data/SoilFile.kt`)** is the single canonical way to derive a `.soil` path. No other code constructs a `.soil` path.
- Hierarchy: Notebook → Pages → Layers → Content Objects
- Layers: base layer (template, locked) and content layers
- Every object carries: id, parentId, boundingBox, order, createdAt, updatedAt, deletedAt, data
- Stroke data: proprietary point arrays (x, y, pressure, tilt, timestamp), stored as JSON in the `data` TEXT column
- Soft deletes with cleanup process; stable UUIDs everywhere
- Activities receive notebook identity as `EXTRA_NOTEBOOK_ID` (entity UUID) + `EXTRA_NOTEBOOK_NAME` — never a `File` object

---

## Global Index (`notesprout.db`)

Room/SQLite at `getExternalFilesDir(null)/notesprout.db`. Owns the entire folder/notebook tree — the `Garden/` directory is flat blob storage, not a source of structure.

### Schema (`objects` table)

```sql
CREATE TABLE objects (
    id         TEXT    PRIMARY KEY NOT NULL,
    type       TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    parentId   TEXT,
    createdAt  INTEGER NOT NULL,
    updatedAt  INTEGER NOT NULL,
    deletedAt  INTEGER,
    data       TEXT    NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_objects_parent_type_deleted
    ON objects(parentId, type, deletedAt);
```

### Key Classes

- `ObjectEntity` (`data/index/ObjectEntity.kt`) — Room entity; universal index row
- `ObjectType` (`data/index/ObjectType.kt`) — `FOLDER`, `NOTEBOOK`, `LIST`
- `FolderObject`, `NotebookObject`, `ListObject` — `@Serializable` data classes in `data` column. `NotebookObject` carries `snapshot: String?` + `pageCount: Int`. `ListObject` carries `notebookIds: List<String>` (array order = display order).
- `ListIds` (`data/index/ListIds.kt`) — `PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"`
- `ObjectDao` (`data/index/ObjectDao.kt`) — Room DAO for all index queries and mutations
- `IndexRepository` (`data/index/IndexRepository.kt`) — higher-level API: create/rename/softDelete/move for folders and notebooks; list ops: `ensurePinnedListExists`, `getPinnedList`, `addNotebookToList`, `removeNotebookFromList`, `reorderList`, `getNotebooksInList`, `scrubNotebookFromAllLists`; pin helpers: `isNotebookPinned(notebookId)`, `togglePin(notebookId)`
- `NotesproutIndex` (`data/index/NotesproutIndex.kt`) — singleton managing `notesprout.db`; `open(context)` in `Application.onCreate`, `seal()` on shutdown

### Rules

- `parentId = null` means root
- Soft-deletes only — set `deletedAt`; never hard-delete without deliberate GC
- All writes go through `IndexRepository`; direct DAO use limited to reads in `MainActivity` load paths
- `NotesproutIndex` must be opened before any Activity accesses it; `NotesproutApplication.onCreate` is the correct place
- **List bootstrap:** `NotesproutApplication.onCreate` calls `repository.ensurePinnedListExists()` on `appScope` after `NotesproutIndex.open()` — idempotent, safe every launch
- **Scrub-on-delete:** `deleteNotebook()` and `deleteFolderRecursively()` call `repository.scrubNotebookFromAllLists(notebookId)` before soft-deleting, so list rows never contain dangling references
- **G10 ADB pull:** `adb -s 34E517F9 pull /sdcard/Android/data/com.notesprout.android.dev/files/notesprout.db /tmp/notesprout.db`

### WAL Maintenance

- `NotesproutDatabase.openCallback()` sets `journal_mode = WAL`, `wal_autocheckpoint = 100`, and runs a one-time `auto_vacuum = INCREMENTAL` + `VACUUM` migration on first open (skipped when already `INCREMENTAL`).
- `NotesproutIndex.checkpointAndVacuum()` — `suspend fun` on `Dispatchers.IO`: `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)` via `rawQuery(...).use { it.moveToFirst() }`, never `execSQL`. Called from `MainActivity.onStop()` on `appScope`.
- `notesprout.db` stays open the full app lifetime — its `-wal`/`-shm` sidecars remain on disk (normal WAL behaviour; checkpoint keeps them near-empty). Full cleanup only on `NotesproutIndex.seal()`. This is distinct from the "no stray files" rule for `.soil` files.

---

## Data Layer — `.soil` Files

### Core Rules — Never Violate These

- **One file per notebook.** Each `.soil` file is a self-contained SQLite database.
- **Single table.** Everything — pages, layers, strokes, images, text, metadata — is a row in one `notebook` table.
- **Everything is an object.** No type special-casing at the schema level — type behavior lives in Kotlin.
- **Assets are base64 strings.** No external files. Images stored inline in the `data` TEXT column.
- **Decode embedded images bounded.** Route all embedded-asset decodes through `core/BitmapDecode.decodeSampled(bytes, reqW, reqH)` — never `BitmapFactory.decodeByteArray` directly on `.soil`-sourced bytes (OOM risk on e-ink). `MAX_DIMENSION=4096` fallback when there's no natural target.
- **SQLite must stay clean.** A file browser should show only `.soil` files — no WAL/SHM/journal sidecars.
  - `PRAGMA journal_mode = WAL`; `PRAGMA wal_autocheckpoint = 100`; `PRAGMA auto_vacuum = INCREMENTAL`
  - Run `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)` on clean close

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
- Each open notebook gets its own Room instance; close and release when the notebook is closed
- `wal_autocheckpoint` is connection-level — re-apply in `SoilDatabase.openCallback()` via `SupportSQLiteDatabase.query(...).use { it.moveToFirst() }`
- PRAGMAs returning a result set: always `rawQuery("PRAGMA ...", null).use { it.moveToFirst() }` — never `execSQL`, never unconsumed cursor
- Any raw SQL touching `order` must double-quote it: `"order"` — it is a SQLite reserved word. Room-generated DAO handles this; only hand-written SQL is at risk. `ContentValues` keys use backtick quoting: `` "`order`" ``.
- `closeNotebook()` runs incremental_vacuum + wal_checkpoint(TRUNCATE), then `db.close()`, then deletes any `-journal` artifact. Lives in `suspend sealNotebook()` (`withContext(Dispatchers.IO)`). User-initiated close: capture snapshot on main thread → launch `sealNotebook()` on `NotesproutApplication.appScope` (a never-cancelled `SupervisorJob + Dispatchers.IO` scope that outlives the Activity) → `finish()` immediately. `onDestroy()` safety net calls `closeNotebook(blocking = true)` for abnormal teardown only (normal path already nulled `soilDatabase`, so it no-ops).
- **Raw `SQLiteDatabase` on `.soil` outside Room must use `OPEN_READWRITE`, not `OPEN_READONLY`.** A read-only WAL connection re-creates `-shm` and cannot unlink `-wal`/`-shm` on close — permanently stranding sidecars. Close via `SQLiteDatabase.checkpointTruncateAndClose(tag, file)` (`data/CoverLoader.kt`): checkpoint → close → delete empty `-journal`.
- Raw read-write helpers (`data/PageCopier.kt`) run `checkpointAndVacuum()` before `db.close()`, then `cleanStrayJournal()`. They must NOT delete `-wal`/`-shm` — NotebookActivity's Room connection is still open to the same file; SQLite removes those when that last connection closes. Multi-step writes must use transactions.
- Never silently swallow exceptions over raw DB ops — `Log.e` at minimum; surface a Toast for write ops.

---

## Design System — E-Ink First (Never Violate These)

**Palette (UI Chrome Only):**
- `inkBlack` = `#000000`
- `paperWhite` = `#FFFFFF`
- `inkLight` = `#888888` — disabled/secondary text only
- `borderGray` = `#CCCCCC` — subtle dividers only (**invisible on e-ink** — use inkBlack for any visible border)
- No color in UI chrome — ever.

**Visual Rules:**
- No shadows, elevation, gradients, or blur
- No Material splash or ripple (`rippleColor=transparent`, `stateListAnimator=null`)
- Animations: none or minimum — never decorative. `android:windowAnimationStyle="@null"` in `Theme.Notesprout` suppresses all system slide/fade transitions globally.
- Borders: 1dp solid inkBlack; corner radius: 4dp
- Typography: clear, high-contrast, black on white

**Source of Truth:**
- Colors: `app/src/main/res/values/colors.xml`
- Styles/typography: `app/src/main/res/values/styles.xml`
- Theme: `app/src/main/res/values/themes.xml`
- Do not hardcode colors or styles — always reference named resources

**What NOT To Do:**
- No color in UI chrome; no shadows/elevation; no decorative animations; no pill-shaped or fully sharp buttons
- Do not use Material Components — theme is `Theme.AppCompat.Light.NoActionBar`; buttons are `AppCompatButton` with explicit drawable backgrounds

**AlertDialog styling pattern:**
- `dialog.window?.setSoftInputMode(...)` before `show()`
- `dialog.window?.setElevation(0f)` and `setBackgroundDrawableResource(R.drawable.shape_bordered)` after `show()` — window only exists once shown

**Keyboard (IME) dismissal in dialogs:**
- On some BOOX devices the IME does not auto-dismiss on dialog close. Always explicitly hide in button click handlers — **not** `setOnDismissListener`.
- Use `imm.hideSoftInputFromWindow(editText.windowToken, 0)` while the dialog is still alive. `setNegativeButton("Cancel", null)` must become a real listener that also hides the IME.
- Never use the activity's `window.decorView.windowToken` — the IME is bound to the dialog's window and ignores hide requests from the wrong token.

---

## Toolbar System

- Icons: Tabler Icons, stroke-based, `@color/inkBlack`, 24dp VectorDrawables in `res/drawable/ic_*.xml`. New icons must come from Tabler or match the Tabler stroke style — no filled/solid icon sets.
- `bg_toolbar_button` StateListDrawable: default = white fill, no border; selected/activated/pressed = white fill + 1.5dp black border
- `Widget.Notesprout.ToolbarButton` style: 44dp, `bg_toolbar_button`, 10dp padding; overridden to 36dp/7dp in `res/values-sw360dp/` for Palma2 Pro
- Pen/eraser buttons: `isSelected = true` for persistent active-tool state
- Dividers: `@color/inkBlack`, 1dp × 28dp
- Undo/Redo: statically always-enabled — empty stack silently does nothing (matches native BOOX behavior)

### Toolbar Overflow System (`notebook/ToolbarOverflowManager.kt`)

- If all buttons + dividers fit, `btnOverflow`/`dividerOverflow` stay `GONE`. Otherwise `btnOverflow` (Tabler "dots") appears at the far right; overflowed buttons move into `overflowMenu` — a vertical `LinearLayout` below the toolbar with `shape_bordered` background.
- **Move-not-clone:** actual `View` instances are moved (no cloning) — `isSelected` state, icon state, and click listeners are preserved with zero extra wiring.
- **Cut-point:** sums natural widths left-to-right; finds the largest prefix fitting in `availableWidth - overflow controls`; if the last visible item is a divider, steps back one to prevent a double-divider. Greedy row packing in the overflow menu.
- **Recalc triggers:** `doOnLayout` (first layout) + `addOnLayoutChangeListener` on the toolbar (fires on rotation, closes menu first).
- **Dismiss rules (in `dispatchTouchEvent`):** touch on `btnOverflow` → toggle; inside overflow menu → close, do NOT consume; inside toolbar → close, do NOT consume; anywhere else → close AND consume (must not start a stroke).
- `releaseRender()` called on any finger `ACTION_DOWN` in the toolbar or the open overflow menu.

---

## Drawing Engine Architecture

### Files

- `notebook/NotebookView.kt` — interface for both engines; all drawing, lasso, heading, snapshot ops
- `notebook/OnyxNotebookView.kt` — BOOX: TouchHelper, RawInputCallback. `onPenLifted` fires on `onEndRawDrawing`. `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.
- `notebook/GenericNotebookView.kt` — standard Canvas: two-layer Bitmap, stylus-only (`TOOL_TYPE_STYLUS` + `TOOL_TYPE_ERASER`), historical point capture. `onPenLifted` fires on `ACTION_UP`.
- `NotebookActivity.kt` — fullscreen immersive, multi-page state, incremental save via `insertOrIgnore`. One-finger deliberate swipe for page navigation (three guards: distance ≥50% screen width, velocity ≥1.5× fling threshold, horizontal dominance). Two-finger swipe left/right inserts a page after/before current and navigates to it (same guards).
- `MainActivity.kt` — notebook list, adaptive grid (3/2 cols at 480dp), pagination, empty state, bottom bar.

### Key Build Facts

- `minSdk = 29`; `android.enableJetifier=true` (Onyx SDK bundles old support classes)
- `jniLibs.pickFirsts` for `libc++_shared.so`
- `defaultConfig.ndk { abiFilters += "arm64-v8a" }` — all target devices are 64-bit ARM. Do NOT `exclude com.tencent:mmkv:1.0.19` — `onyxsdk-base` references it; removing it risks `NoClassDefFoundError`. ML Kit `libdigitalink.so` is 16 KB-aligned at `digital-ink-recognition:19.0.0`.
- `NotesproutApplication.onCreate` calls `HiddenApiBypass.addHiddenApiExemptions("")` before any SDK init
- `setStrokeColor(Color.BLACK)` required on TouchHelper init — NoteAir5C color panel defaults to non-black
- Toolbar z-order: toolbar must overlay the drawing container in a `FrameLayout` — native SurfaceView occludes siblings below it
- `onSizeChanged()` calls `redrawCanvas()` (not just white fill) in both drawing views — handles the case where `loadStrokes()` runs before view layout

### EPD Rules — Never Violate These

**Overlay lifetime:**
- The overlay ("writing mode") stays active indefinitely while the user writes. No idle-release timer.
- Legitimate handoff points: `setEraserMode(true)`, `eraseAll()`, `setTemplate()`, `loadStrokesWithBitmap()`, `onWindowFocusChanged(false)`, toolbar finger touch.
- `onPenLifted` is a DB-save trigger only — does NOT touch the overlay.

**Toolbar touch → overlay release:**
- Any finger `ACTION_DOWN` within `drawingToolbar.bottom` (intercepted in `NotebookActivity.dispatchTouchEvent`) calls `drawingView.releaseRender()` before the child button handles the event.
- `releaseRender()`: `setRawDrawingRenderEnabled(false)` → `invalidate()`. No `handwritingRepaint` needed.
- Overlay re-enables automatically via `onBeginRawDrawing` on the next pen stroke.
- Must use `dispatchTouchEvent` (not `setOnTouchListener`) — button children always consume touches, so `setOnTouchListener` on the ViewGroup never fires.

**Overlay handoff (`eraseAll()`):**
- `setRawDrawingRenderEnabled(false)` → white bitmap → `invalidate()` → `EpdController.handwritingRepaint(view, Rect(0,0,w,h))` → re-enable
- **`handwritingRepaint` is required.** `setRawDrawingRenderEnabled` is a lightweight toggle; it does NOT clear the hardware buffer. Without it: gray residue + black flash.
- `EpdController.setUpdListSize(512)` in `openRawDrawing()` suppresses mid-session GC16 refresh — do not remove.
- `renderStroke` calls `invalidate()` on every stroke so the Android canvas stays continuously current with the overlay.

**Eraser overlay:**
- On eraser start: `setRawDrawingRenderEnabled(false)` + `invalidate()` — immediately, before any erase logic. If not released first, the overlay hides the bitmap erase result (phantom strokes remain visible).
- `handwritingRepaint` after erase gesture ends only — NEVER during move events (causes full EPD flash per stroke).
- `onBeginRawDrawing` re-enables render guarded by `!isEraserMode`.

**setTemplate() EPD handoff:**
- `setRawDrawingRenderEnabled(false)` → `redrawCanvas()` → `EpdController.handwritingRepaint()` → `setRawDrawingEnabled(true)`. Without `handwritingRepaint`, the template change is invisible on e-ink.

### Tool-State Invariants (OnyxNotebookView)

When a Dialog is shown over NotebookActivity, focus changes trigger `onWindowFocusChanged(false)` → `setRawDrawingEnabled(false)`. On return: `onWindowFocusChanged(true)` → `openRawDrawing()`. Also triggered by `onResume()` → `enableDrawing()`.

| Active tool | `setRawDrawingEnabled` | `setRawDrawingRenderEnabled` |
|---|---|---|
| Pen | `true` | `true` (SDK manages) |
| Eraser | `true` | `false` (prevents phantom pen strokes on overlay) |
| Lasso / Lasso Eraser | `false` | n/a |
| Text placement | `false` | n/a |

`openRawDrawing()` and `enableDrawing()` must guard `setRawDrawingEnabled(true)` with `!isLassoMode && !isLassoEraserMode && !isTextPlacementMode`. If the guard passes and `isEraserMode` is true, immediately follow with `setRawDrawingRenderEnabled(false)`. Failing this causes phantom pen strokes on the EPD overlay — they look real but vanish on the next EPD refresh.

### Performance Rules (Do Not Regress)

**Save path:** Wrap INSERT OR IGNORE loops in `db.withTransaction {}`; track `persistedStrokeIds` set and skip `toJson()` for already-persisted strokes.

**Load path:** `buildRenderBitmap()` on `Dispatchers.IO` — pre-builds white → template → strokes off the main thread; `loadStrokesWithBitmap()` on main thread swaps the pre-built bitmap (~12ms cost).

**Erase path:** `LiveStroke.boundingBox: RectF` pre-computed at creation; `eraseAtPath` builds an AABB and rejects non-intersecting strokes in O(4 floats). `throttledEraseRedraw()` redraws at most once per 60ms; `finalizeEraseRedraw()` forces one clean redraw on gesture end before `handwritingRepaint`.

---

## Page Snapshot System

Each page row's `data` JSON carries an optional `"snapshot"` field — a base64-encoded transparent-background PNG of all content (no schema change).

Rendering order: white → template → snapshot PNG → new content drawn this session.

**Snapshot rules:**
- Transparent background only — do NOT fill white or draw the template.
- `captureSnapshot()` returns `null` if strokes/headings/textObjects are all empty, or view isn't laid out (w=0/h=0).

**When snapshots are captured:**
- `setEraserMode(true)` — BEFORE `isEraserActive = true`
- `setTemplate(bitmap)` — BEFORE `templateBitmap = bitmap`
- `onWindowFocusChanged(false)` — backgrounded or dialog overlay
- Page navigation — BEFORE `eraseAll()`
- Close/back — synchronously in `closeNotebook()` on the main thread BEFORE `sealNotebook()` is dispatched to IO

**Critical:** `onWindowFocusChanged(false)` fires AFTER `finish()` — `soilDatabase` is already null. Any path that calls `closeNotebook()` must capture the snapshot itself. Never rely on `onWindowFocusChanged` as the close-path snapshot trigger.

**NOT on:** user-initiated `eraseAll()` or page delete — content is being discarded.

**Stale detection:** `NotebookDao.getMaxStrokeUpdatedAt(layerId)` — `SELECT MAX(updatedAt)` with **no** `deletedAt IS NULL` filter. Soft-deleted strokes have `updatedAt = deletedAt`, so erasures are detected as changes. If `maxStroke > page.updatedAt`, snapshot is stale. `persistSnapshot()` bumps `page.updatedAt`.

**Two-phase page load (`NotebookActivity.loadCurrentPage`):**
1. `setupPageIds(db)` — resolves `currentPageId` / `currentLayerId`
2. `loadPageTemplateFromDb(db)` — decodes template bitmap (or null for blank)
3. `tryLoadSnapshotBitmap(db, templateBitmap)` — staleness check + composite. Returns null on miss.
4. **Fast path** (hit): display composite immediately; deserialize strokes in background via `setStrokeListSilently()`.
5. **Full path** (miss): deserialize + `buildRenderBitmap` off-thread; capture and persist snapshot for next load.

---

## Template System

- Templates are `type = "template"` rows stored in the `.soil` notebook database
- Template PNG files stored in `getExternalFilesDir("Templates")` — imported via `ACTION_OPEN_DOCUMENT`
- `data` JSON: `{ "width": Int, "height": Int, "name": String, "image": String (base64) }`
- `parseTemplateId(data)` reads `data.template` from a page row to get the active template UUID (empty = Blank)

**TemplateDialog:** Two-tab (All / Notebook), adaptive grid — 4 columns on NA5C (≥1500px), 2 on P2P/GC7. `thumbFrame` uses `shape_bordered` + **1dp padding** to inset the `ImageView` so it cannot render over the border stroke. Do NOT use `clipToOutline` — it clips the border itself at rounded corners.

**Template inheritance on new page:** `addPage()` reads the current page **fresh from DB** via `dao.getObjectById(currentPageId)`. Do NOT read from the stale in-memory `pages` list — it is not refreshed after `applyTemplateToCurrentPage()` writes to DB.

---

## Scribble to Erase

Always-active in pen mode (no toggle). A rapid scribble across existing content erases it silently — no confirmation.

### Detection heuristic (both drawing engines)

A completed stroke is a **scribble candidate** when BOTH hold:
1. **High ink density:** `pathLength / boundingBoxDiagonal ≥ SCRIBBLE_DENSITY_RATIO` (4.0). Both values are computed from the stroke's point array.
2. **Zigzag tightness:** at least `SCRIBBLE_MIN_DIRECTION_REVERSALS` (3) direction reversals on the noise-filtered path (consecutive movement vectors whose dot product is negative). Points < 2 px apart are collapsed before counting.

Detection runs at **pen lift** (`onEndRawDrawing` on Onyx, `ACTION_UP` on Generic), after the full stroke is captured. On Onyx, all points from the gesture are accumulated in `currentGesturePoints / currentGestureStrokeIds` (cleared on `onBeginRawDrawing`). On Generic, `commitActiveStroke()` supplies the last stroke.

### Hit-testing

For a scribble candidate, a background thread runs `scribbleHitTest` against all non-deleted content on the layer:

- **Strokes:** AABB pre-filter (`SCRIBBLE_STROKE_TOUCH_RADIUS_DP` expansion), then per-stroke-point to nearest scribble segment segment distance ≤ `SCRIBBLE_STROKE_TOUCH_RADIUS_DP` px. Whole-stroke erase on any touch (matches eraser tool behavior).
- **Headings / text objects:** `scribblePathPenetration` sums the length of all scribble segments with at least one endpoint inside the object's bounding box. The object is hit only when this sum ≥ `SCRIBBLE_BBOX_PENETRATION_DP` px, preventing corner-grazes from triggering an erase.

If the hit test returns **empty** the scribble is treated as a normal stroke and `onPenLifted` fires. If any objects are hit, `onScribbleEraseComplete` fires instead.

### Constants (`notebook/NotebookConstants.kt`)

| Constant | Default | Purpose |
|---|---|---|
| `SCRIBBLE_DENSITY_RATIO` | `4.0f` | Minimum pathLength / diagonal ratio |
| `SCRIBBLE_MIN_DIRECTION_REVERSALS` | `3` | Minimum zigzag reversals |
| `SCRIBBLE_BBOX_PENETRATION_DP` | `14f` | Minimum travel inside heading/text bbox (dp) |
| `SCRIBBLE_STROKE_TOUCH_RADIUS_DP` | `8f` | Proximity radius for stroke-to-stroke touch (dp) |

### New undo action: `ScribbleErased`

`history/UndoRedoAction.ScribbleErased(scribbleStrokeIds, erasedObjectIds, pageId, layerId, deletedAt, headingIds, headings, textIds, textObjects)`.

- `scribbleStrokeIds` — IDs of the gesture stroke row(s); saved to DB then immediately soft-deleted.
- `erasedObjectIds` — IDs of all erased content (strokes, headings, text objects); does NOT include scribble strokes.
- `headings / textObjects` — full render data for in-memory restoration on undo without a DB round-trip.
- **Undo:** restore all `scribbleStrokeIds` + `erasedObjectIds` (clear `deletedAt`); rebuild canvas. Scribble stroke reappears along with erased content.
- **Redo:** re-soft-delete all `scribbleStrokeIds` + `erasedObjectIds`.

Cross-page undo/redo uses the same two-phase approach as `LassoErased`.

### EPD / overlay handoff (OnyxNotebookView)

When hit test confirms a scribble erase (fires on main thread from `post {}`):
1. `touchHelper.setRawDrawingRenderEnabled(false)` + `invalidate()` — releases hardware buffer before DB/bitmap work.
2. Activity (`onScribbleEraseComplete`): calls `saveStrokes(db)` to insert scribble stroke rows, then soft-deletes scribble + erased objects in a transaction, invalidates page snapshot.
3. Rebuilds render bitmap off-thread (`buildRenderBitmap`) without scribble or erased objects.
4. `loadStrokesWithBitmap` swaps bitmap, runs `handwritingRepaint`, re-enables raw drawing — identical to the `eraseAll` / `setTemplate` handoff sequence.

---

## Smart Lasso

Always-active in pen mode (no toggle). A quick, closed pen gesture (circle or loop) around existing content enters lasso selection mode with all enclosed objects selected — exactly as if the user had switched to the lasso tool and drawn that path manually.

### Detection gate order at pen lift

All three checks run in the same background thread, in priority order:
1. **Smart lasso** — fast + closed + encloses ≥1 object → enters lasso mode with selection.
2. **Scribble-to-erase** — dense + zigzag + crosses ≥1 object → erases hit objects.
3. **Normal stroke** — fire `onPenLifted`; activity saves to DB.

### Detection heuristics (both drawing engines)

A completed stroke is a **smart-lasso candidate** when ALL THREE hold:
1. **Velocity:** `pathLength / durationMs ≥ SMART_LASSO_MIN_VELOCITY` (0.5 px/ms). `durationMs` is from `beginRawDrawingTimeMs` on Onyx; from `strokeStartTimeMs` (set on `ACTION_DOWN`) on Generic.
2. **Closure:** distance from first to last point of the gesture ≤ `SMART_LASSO_CLOSURE_DISTANCE_DP` (50 dp).
3. **Winding:** the path accumulates ≥ `SMART_LASSO_MIN_WINDING_DEGREES` (270°) of signed angular change around the gesture's centroid. This is the circularity gate — letters and open arcs never wind 270°+ around a central point; loops always do. Computed by summing per-step `atan2` deltas (unwrapped to [−π, π]) over all gesture points.

If all three conditions hold, `runLassoHitTest` is called against all non-deleted content on the layer (strokes, headings, text objects — same function used by the lasso eraser). If ≥1 object is hit, smart lasso triggers. If 0 objects are hit (e.g. writing the letter "o" or "0" over empty space), falls through to the scribble check as a normal stroke.

### Constants (`notebook/NotebookConstants.kt`)

| Constant | Default | Purpose |
|---|---|---|
| `SMART_LASSO_MIN_VELOCITY` | `0.5f` | Minimum pathLength / durationMs (px/ms) |
| `SMART_LASSO_CLOSURE_DISTANCE_DP` | `50f` | Maximum first-to-last distance for "closed" path (dp) |
| `SMART_LASSO_MIN_WINDING_DEGREES` | `270f` | Minimum angular sweep around centroid for circularity (degrees) |

### On trigger

1. **Discard gesture stroke:** removed from in-memory stroke list before the callback fires. Never saved to DB, never added to `persistedStrokeIds`.
2. **EPD overlay released** (Onyx only): `setRawDrawingRenderEnabled(false)` + `invalidate()` before the callback.
3. **Activity enters lasso mode:** `enterLassoMode()` is called first, making the mode switch persistent. `drawingView.isLassoMode = true`, `btnLasso.isSelected = true`, `btnPen.isSelected = false`.
4. **Selection state set:** `selectedObjectIds` and `drawingView.lassoSelectedIds` populated with hit IDs.
5. **Bitmap rebuilt off-thread** via `buildRenderBitmap` + `loadStrokesWithBitmap` to drop the gesture circle from the render bitmap before the EPD repaint.
6. **Selection overlay + floating toolbar shown** via `setLassoOverlay(null, paddedBounds)` + `updateFloatingSelectionToolbar(paddedBounds)`.

### Persistent mode switch

The user remains in lasso mode after any lasso action (move, copy, delete, etc.) until the pen tool is explicitly tapped — the same behavior as a toolbar-initiated lasso selection. Smart lasso does NOT auto-return to pen mode.

### Implementation files

- `notebook/NotebookConstants.kt` — constants
- `notebook/OnyxNotebookView.kt` — `checkAndDispatchGesture()`, `isSmartLassoCandidate()`; replaces the former `checkAndRunScribble()`
- `notebook/GenericNotebookView.kt` — same pattern; `strokeStartTimeMs` tracked on `ACTION_DOWN`
- `notebook/NotebookView.kt` — `onSmartLassoComplete` callback declaration
- `NotebookActivity.kt` — `drawingView.onSmartLassoComplete` wiring

---

## Snap-to-Guide System

Always-active during lasso drag. When a selected object is dragged close to a snap region, a dashed guideline appears and the object snaps to it. Dragging past the threshold releases the snap — no hard clamping.

### Snap Regions

Two guide types — vertical (dashed vertical line, snaps X) and horizontal (dashed horizontal line, snaps Y):

| Guide | Position |
|---|---|
| Left / Right edge | x = 0 / x = pageWidth |
| Left / Right margin | x = SNAP_MARGIN_DP / x = pageWidth − SNAP_MARGIN_DP |
| Vertical center | x = pageWidth / 2 |
| Top / Bottom edge | y = 0 / y = pageHeight |
| Top / Bottom margin | y = SNAP_MARGIN_DP / y = pageHeight − SNAP_MARGIN_DP |
| Horizontal center | y = pageHeight / 2 |

`SNAP_MARGIN_DP = 44f` — matches the standard toolbar button size.

### Snap Logic

Each selection bbox has 3 anchors per axis (left/center-x/right for X; top/center-y/bottom for Y). During drag, the nearest (anchor, guide) pair within `SNAP_THRESHOLD_DP` (20dp) wins per axis. X and Y snap independently. The raw drag offset is adjusted by `guide − anchor` to pull the anchor flush to the guide.

### Constants (`notebook/NotebookConstants.kt`)

| Constant | Default | Purpose |
|---|---|---|
| `SNAP_MARGIN_DP` | `44f` | Margin guide inset from each page edge (dp) |
| `SNAP_THRESHOLD_DP` | `20f` | Max distance for snap to engage (dp) |

### Implementation Files

- `notebook/SnapGuide.kt` — `sealed class SnapGuide { Vertical(x), Horizontal(y) }` + `SnapResult(snappedDx, snappedDy, activeGuides)`
- `notebook/SnapEngine.kt` — `computeSnap(originalBox, rawDx, rawDy, pageWidth, pageHeight, marginPx, thresholdPx): SnapResult`
- Both drawing views — `activeSnapGuides: List<SnapGuide>` field; `snapGuidePaint` (1dp, black, `DashPathEffect([12dp, 6dp])`); `drawSnapGuides(canvas)` called in the drag layer of `onDraw` after the selection box, before `return`; cleared on all drag commit/cancel/mode-exit paths alongside `dragDx = 0f`

---

## Undo/Redo System

- Session-scoped (not persisted across process death)
- `history/UndoRedoAction.kt` — sealed class: stroke add/erase, page add/delete/clear/copy/paste/move, lasso erase/cut/delete/paste/move, heading create/remove/text-edit, text insert/edit/remove/convert, **scribble erase**
- `history/UndoRedoManager.kt` — `undoStack` / `redoStack` as `ArrayDeque`. Redo stack cleared on any new user action.

**Cross-page actions:** Never call `saveAndSwitchPage()` — it calls `eraseAll()` which wipes in-memory strokes. Use the two-phase approach: save/snapshot the leaving page inline → navigate → load from DB → apply the action → rebuild bitmap.

**Same-page stroke path:** Never calls `eraseAll()`. Updates in-memory stroke list directly, rebuilds bitmap off-thread with `currentTemplateBitmap` (`NotebookActivity` field set in `displayPage()`), swaps via `loadStrokesWithBitmap`. Keep `persistedStrokeIds` in sync.

---

## Heading Objects

- `type = "heading"` rows in `.soil`; `HeadingObject` serialized to `data`; `HeadingStroke` is the in-memory representation
- Headings render as grey-fill backgrounds with embedded strokes, or 20sp inkBlack canvas text when `recognizedText` is non-null
- `recognizedText: String?` — null = render strokes; non-null = canvas text (populated by ML Kit at creation)
- All lasso actions (move, copy, cut, paste, delete, eraser) treat headings as first-class participants
- `copyPageAfter()` and `copyPageAfterRaw()` copy all object types, not just strokes

---

## Text Object System

### Data Model

- `type = "text"` rows in `.soil`; `TextObject` (`data/TextObject.kt`) serialized to `data`; `TextRender` (`data/TextRender.kt`) is the in-memory representation
- `@Serializable data class TextObject(val text: String = "", val strokes: List<LiveStroke>? = null)` — raw Markdown source + optional embedded strokes (for lasso-converted objects where ML Kit failed or recognition was not run)
- `data class TextRender(val id: String, val boundingBox: RectF, val text: String, val strokes: List<LiveStroke>? = null)` — built at page load from `type = "text"` rows

**Bounding box width rule:** width = natural content width (max line width from `StaticLayout`), capped at page width only when content genuinely needs it. Never set `boundingBox.width = pageWidth` unconditionally. `TextObjectRenderer.measure()` returns this natural width; `availableWidthPx` is the layout constraint (wrapping ceiling), not the assigned width. Applies to all write paths: insert, edit, and lasso conversion. For unrecognized objects (blank text + strokes), bbox derives from the stroke union bounding box.

**`strokes` preservation rule:** All write paths (`updateTextObject`, `TextEdited` DB handler, `StrokesMoved` DB handler) preserve `strokes` in the serialized JSON — `TextObject(text = ..., strokes = target.strokes).toJson()`. Never construct `TextObject(text = ...)` alone for existing objects that may carry strokes.

### Render Dispatch

Text objects render after headings, before strokes — transparent background (no white fill, no template draw).

Dispatch is centralized in `drawTextObject(canvas, textRender, widthPx)` in each drawing view. All render sites (redrawCanvas, buildRenderBitmap, compositeTextObjects, drag layer) call this helper — never `TextObjectRenderer.draw()` directly:
- `text.isNotBlank()` → `TextObjectRenderer.draw()` (markdown engine)
- `text.isBlank()` AND `strokes` non-empty → render embedded strokes (unrecognized state)
- `text.isBlank()` AND `strokes` null/empty → render nothing

Rendering: `StaticLayout` + `TextPaint` at 16sp `Color.BLACK`. Entry point: `TextObjectRenderer.draw(canvas, textRender, widthPx, paint, density)`.

### Markdown Engine (`core/markdown/`)

- `MarkdownParser` — hand-rolled, no dependencies. Block types: `Heading(1–6)`, `Paragraph`, `ListItem`, `Blockquote`, `HorizontalRule`. Inline types: `Text`, `Bold`, `Italic`, `Strikethrough`, `Link(displayText, url)`.
- `MarkdownRenderer` — `List<Block>` → `SpannableStringBuilder` using Android text spans.
- `TextObjectRenderer` — wraps parser + renderer + `StaticLayout` for canvas drawing and measurement.

**Supported subset:**
- Headers h1–h6 (`#` … `######`)
- Bold (`**text**` / `__text__`), italic (`*text*` / `_text_`), strikethrough (`~~text~~`)
- Links (`[text](url)`) — underlined, not clickable
- Unordered lists, 3-level nesting, bullet glyphs: `• ◦ ▪`
- Ordered lists with auto-renumbering; nesting supported
- Task checkboxes (`- [ ]` → `☐`, `- [x]` → `☑`)
- Blockquotes (`>`) — left bar via `QuoteSpan`
- Horizontal rules (`---` / `***` / `___`) via `HorizontalRuleSpan : ReplacementSpan`

**Out of scope (do not add without discussion):** inline code, fenced code blocks, tables, embedded images, raw HTML.

**WYSIWYG regex safety:** inline patterns do NOT use `RegexOption.DOT_MATCHES_ALL` — use `[^*\n]` / `[^~\n]` exclusion classes to prevent cross-line matches.

### TextEditDialog (`notebook/TextEditDialog.kt`)

Markdown WYSIWYG/source editor. Two modes: **WYSIWYG** (live formatting spans) and **Markdown** (raw source). Mode toggle is two `AppCompatButton` (weight=1 each) with `bg_toolbar_button` — switching is instant (spans only, text unchanged).

Formatting toolbar (HorizontalScrollView): B, I, S̶, H▾ (H1–H6/Normal), •, 1., ☐ (task checkbox toggle), ❝, —, [⊞] (link). `shape_bordered` background, 36dp height, 10dp H padding.

Auto-renumbering: ordered list blocks renumbered on every `afterTextChanged`. Auto-continue: Enter at end of a numbered list line inserts the next number prefix.

AlertDialog pattern: `setSoftInputMode(SOFT_INPUT_STATE_VISIBLE | SOFT_INPUT_ADJUST_RESIZE)` before `show()`; `setElevation(0f)` + `shape_bordered` after `show()`. IME hidden in both Save and Cancel handlers via `editMarkdown.windowToken` (dialog's window token, not the activity's).

### Text Placement Mode

`btnInsertText` (`ic_text_recognition.xml`) — persistent toggle between EraseAll and Lasso in the NotebookActivity toolbar.

**Entering:** exits lasso/lasso-eraser; sets `isTextPlacementMode = true`; calls `drawingView.setTextPlacementMode(true)` → on Onyx: `setRawDrawingEnabled(false)`; calls `releaseRender()`. Sets `btnInsertText.isSelected = true`.

**Exiting:** sets `isTextPlacementMode = false`; calls `setTextPlacementMode(false)` + `enableDrawing()` to restore drawing state.

**Canvas tap:** `handleTextPlacementTouch` captures coordinates on `ACTION_DOWN` but does NOT fire the callback or exit until `ACTION_UP`. Exiting on DOWN would route subsequent MOVE/UP events to the normal drawing path, creating a phantom stroke persisted to DB. `onTextPlacementTap(tapX, tapY)` fires on `ACTION_UP`. The callback must NOT call `enableDrawing()` — drawing is restored by the dialog focus cycle (`onWindowFocusChanged(true)` → `openRawDrawing()`) after the stylus lifts. Calling `enableDrawing()` before the stylus lifts re-enables Onyx raw input mid-contact → `onBeginRawDrawing` → `onEndRawDrawing` → `onPenLifted` → phantom stroke persisted to DB. Stylus tool type only — finger touches ignored.

**`dispatchTouchEvent`** cancels placement mode on any toolbar touch, UNLESS the touch is on `btnInsertText` itself.

### Insert Flow (`insertTextObject`)

1. Measure markdown via `TextObjectRenderer.measure()` on `Dispatchers.Default`
2. Compute bounding box centered on tap, clamped to page bounds
3. Insert `type="text"` row + `invalidatePageSnapshot(db, pageId)` on `Dispatchers.IO`
4. Append `TextRender` to `drawingView.getTextObjects()` + `loadTextObjects(...)`
5. Rebuild render bitmap off-thread → swap via `loadStrokesWithBitmap`
6. Enter lasso mode, select new object, show floating toolbar
7. Push `UndoRedoAction.TextInserted(textId, pageId, layerId, textRender)`

### Tap-to-Edit

While a text object is selected (single selection in lasso mode), a stylus tap within `boundingBox` opens `TextEditDialog` pre-filled with raw Markdown. **Gated on `text.isNotBlank()`** — unrecognized objects (blank text + embedded strokes) ignore the tap.

- **Non-empty confirm → `updateTextObject`:** remeasures, keeps top-left fixed, clamps resized box to page bounds, persists, rebuilds bitmap, refreshes dashed selection overlay. Pushes `UndoRedoAction.TextEdited(textId, pageId, oldTextRender, newTextRender)`.
- **Empty confirm → `deleteTextObjectFromEdit`:** soft-deletes row, removes from in-memory list, clears selection. Pushes `UndoRedoAction.TextRemoved`. Embedded strokes are intentionally discarded — the user explicitly cleared the text.

### Lasso stroke-to-text Conversion (`convertLassoToText`)

"Text" button (`btnConvertText`, `ic_text_recognition`) in the floating lasso toolbar — visible only when `selectionIsPureStrokes`.

1. Run ML Kit recognition on selected strokes
2. **Success:** measure text, resize bbox (left/top anchored); `data.text = recognizedText`
3. **Failure:** keep original bbox; `data.text = ""`
4. Both cases: `data.strokes = embeddedStrokes` (fresh-UUID copies of selected stroke data)
5. Soft-delete original stroke rows + insert `type="text"` row in one transaction; invalidate snapshot; select new text object

Undo: `UndoRedoAction.TextConverted(textId, pageId, layerId, deletedAt, originalStrokeIds, textRender)` — undo restores original strokes and soft-deletes text row; redo reverses.

### Lasso Actions (text objects are full first-class participants)

All lasso actions work for text objects alongside strokes and headings with full undo/redo support:

- **Selection:** center-point containment hit test
- **Drag to move:** translates bbox; persisted via `updateHeadingData`; `StrokesMoved` undo; `strokes` field preserved
- **Delete:** `performLassoDelete` — soft-delete + `LassoDeleted` undo
- **Copy/Cut/Paste:** `NotesproutClipboard.ClipboardContent.textObjects: List<TextRender>` carries both `text` + `strokes`. Paste: new UUID, translated bbox. `LassoCut`/`LassoPasted` undo actions carry `textIds`/`textObjects`. Cross-page paste survives the round trip via the undo action.
- **Lasso eraser:** `runLassoHitTest` — center-point containment; `LassoErased.strokeIds` contains ALL erased IDs; `textIds` is the text-object subset stored separately for in-memory partitioning on undo.

### Canvas Integration

- `NotebookDao.getTextObjectsForLayer(layerId)` — `WHERE type = 'text'`; included in `getMaxContentUpdatedAt` staleness check
- `buildRenderBitmap` default parameter `textObjects: List<TextRender>? = null` — null = use stored field; non-null overrides (page load path, undo/redo call sites pass null)
- Snapshot fast-path: `compositeTextObjects(bitmap)` paints text objects onto the snapshot bitmap after `loadTextObjects()`, before `loadStrokesWithBitmap()`
- PDF export: `NotebookExporter.renderPage()` loads text objects via `getTextObjectsForLayer()` and renders after headings, before strokes

---

## MainActivity Feature Systems

### Notebook & Folder Management

- New-notebook name validation: whitelist `[^a-zA-Z0-9_\-. ]`, reject `.`/`..`, check index for duplicate name in current folder. New-notebook dialog pre-fills with `YYYYMMDD_HHmmss` timestamp (editable before confirm).
- **Move:** index update only — `.soil` file stays at `Garden/<id>.soil` (UUID unchanged).
- **Copy notebook:** new `ObjectEntity` + copy `.soil` to new UUID path via `soilFile()`.
- **Copy folder:** recursively create new index entries and copy all descendant `.soil` files.
- **Conflict check:** if a sibling with the same name exists at the destination, show AlertDialog "A [notebook/folder] named '[name]' already exists here. Replace it?" Replace proceeds; Cancel stays in picker mode.
- **Folder delete:** recursively soft-deletes all descendants in the index; deletes `.soil` files via `soilFile()`; cleans up WAL sidecars. Confirmation dialog message: `Delete "[name]"? This will permanently remove all notebooks and subfolders inside it. This cannot be undone.`

### ActionSheetDialog (`ActionSheetDialog.kt`)

Reusable flat action sheet. Builder: `.title(String)` (optional) → `.addAction(iconRes?, label, onClick)` → `.show()`. `shape_bordered` window background after `show()`. 1dp inkBlack dividers between rows. Optional title row has an `ic_x` close button. No bottom Cancel row. Icon slot is a `Space` when `iconRes` is null, keeping labels aligned.

### Browse State Persistence (`state/AppStateManager.kt`)

`data class AppViewState(val folderId: String?, val pinnedMode: Boolean)` persisted in `SharedPreferences("notesprout_view_state")`. Saved at every browse-context change. Search mode is never persisted.

**Restore on launch:** `onCreate` loads state synchronously. Non-default state: set `isStateRestored = false`, launch coroutine to `navigateStackToFolder(folderId)` then optionally `enterPinnedMode()`, set `isStateRestored = true`, trigger first render. Layout listener and `onResume` check `isStateRestored` — if false, defer scan to the restore coroutine. **Stale folder:** if `navigateStackToFolder` resolves to root (folder deleted), clear via `AppStateManager.save(context, AppViewState(null, false))`.

### Pinned Browse View

- Back press priority: `isPinnedMode` is checked first (before picker mode, search mode, directory stack)
- `directoryStack` is NOT touched when entering/exiting pinned mode — folder position is preserved underneath
- `onResume()` calls `renderPinnedList()` when `isPinnedMode` — re-fetches in case notebook was unpinned while open
- Pinned mode, search mode, and picker mode are mutually exclusive; each hides the other's toolbar controls
- Card labels in pinned and search modes: immediate parent folder only — `folderLabel.substringAfterLast(" › ")`; root-level notebooks show "Notebooks › Name"

### Search (`search/SearchEngine.kt`)

Fuzzy match against all notebooks: substring (3) > all words present (2) > prefix/initials (1). Opening a notebook from search results rebuilds `directoryStack` by walking the `parentId` chain (`navigateStackToDirectory`) so returning lands in the correct folder.

### Sorting (`sort/`)

`SortPreferences`: `SortField` (NAME / DATE_MODIFIED), `SortOrder` (ASC / DESC), `FolderSort` (FOLDERS_FIRST / NOTEBOOKS_FIRST / MIXED). Persisted in `SharedPreferences("notesprout_sort_prefs")`. Card labels (normal mode): `"$displayName ($dateStr, $timeStr)"` via `DateFormat.getMediumDateFormat` + `DateFormat.getTimeFormat`.

### PDF Export

- `NotebookExporter` renders all pages off-screen on `Dispatchers.IO` using white→template→headings→text→strokes pipeline
- Output to `context.cacheDir/<title>.pdf`, shared via `FileProvider` (`${applicationId}.fileprovider`) + `Intent.ACTION_SEND`
- Share intent **must** include `clipData = ClipData.newRawUri("", uri)` alongside `FLAG_GRANT_READ_URI_PERMISSION` — on Android 12+, the chooser intermediary does not forward URI permissions without `ClipData` (causes silent Google Drive upload failure on NA5C)
- Progress dialog: "Exporting page X of N…" via `Handler(Looper.getMainLooper())`

### Page Export (PNG)

- Entry point: long-press a page in `PageIndexActivity` → tap the export button (`ic_export`) in the action-mode toolbar
- `NotebookExporter.exportPage(context, soilPath, pageId, pageNumber, notebookTitle)` — opens a transient Room instance for the given `.soil` path; does NOT checkpoint on close (NotebookActivity's canonical connection is still live)
- Render pipeline: identical to PDF — white → template → headings → text objects → strokes; full-quality, no snapshot shortcut
- Filename format: `<safeTitle>_page<N>.png` where N is the 1-based page number. Same sanitization regex as PDF: `[^a-zA-Z0-9_\\-. ]` → `_`.
- Output to `context.cacheDir/exported_pngs/`; FileProvider path entry `name="exported_pngs"` in `res/xml/file_paths.xml`
- Share intent: `type = "image/png"`, same `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION` pattern as PDF export
- Progress: non-cancellable `AlertDialog` ("Exporting…") matching the PDF export pattern; dismissed on success or failure
- Selection is cleared via `exitActionMode()` after the share chooser launches

### ML Kit

- `com.google.mlkit:digital-ink-recognition:19.0.0` — en-US model; `recognizedText` stored in `HeadingObject`
- Model downloads on any network (~20–30 MB, one-time). **TODO:** make this a user-facing setting (Wi-Fi only vs. any). See `MlKitHandwritingRecognizer.initModel()` → `DownloadConditions.Builder()`.

---

## Future Work — Wacom & Generic Android Stylus

Wacom barrel buttons set `BUTTON_STYLUS_PRIMARY`/`BUTTON_STYLUS_SECONDARY` on `MotionEvent` — they do not change `getToolType()`. Fix: check `event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)` in `onTouchEvent` and treat as eraser for that stroke. Low priority — do not let it block BOOX-first progress.

---

## Device Target Tiers

**Tier 1 — Primary (always-tested, daily drivers):**
- BOOX Go 10.3 (EMR stylus, large e-ink) — **FLAGSHIP**
- BOOX Note Max (EMR stylus, large-format e-ink)
- BOOX Go 7 (EMR stylus, compact e-ink)
- BOOX Palma2 Pro (USI 2.0 stylus, phone form factor)

**Tier 2 — Testing/QA:**
- BOOX NoteAir5C, NoteAir4C, Tab XC, Go Color 7 Gen II
- Wacom Movink Pad 11 & 14 (Android, GenericDrawingEngine)
- iPad Air + Apple Pencil — future
- iPhone 14 (touch-only) — future
- MacBook / Web — future
- Supernote Nomad & Manta (GenericDrawingEngine) — future

## ADB Device Serials

| Device | ADB Serial |
|---|---|
| BOOX NoteAir5C (NA5C) | `92c16533` |
| BOOX Note Max (MAX) | `6325773d` |
| BOOX Go 10.3 (G10) | `34E517F9` |
| BOOX Go 7 (G7) | `17845014` |
| BOOX Palma2 Pro (P2P) | `287d2364` |
| BOOX Go Color 7 (GC7) | `98d56306` |
| BOOX NoteAir4C (NA4C) | `1d36f870` |
| BOOX Tab XC (TXC) | `d852bed0` |
| Wacom Movink Pad 11 (MIP11) | `5HL21V5007384` |
| Supernote Nomad (SNN) | `SN078D10012852` |

---

## Build Variants & Install

**Variants:**
- **Debug** (`com.notesprout.android.dev`) — active development; installs alongside stable
- **Release** (`com.notesprout.android`) — stable; release installs are always explicit

Default: always build and install **debug** unless explicitly told otherwise.

**Build debug:**
```
cd apps/notesprout_android
./gradlew assembleDebug
```
APK: `apps/notesprout_android/app/build/outputs/apk/debug/app-debug.apk`

**Build release (unsigned — must sign before sideloading):**
```
cd apps/notesprout_android
./gradlew assembleRelease
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out apps/notesprout_android/app/build/outputs/apk/release/app-release-signed.apk \
  apps/notesprout_android/app/build/outputs/apk/release/app-release-unsigned.apk
```

**Install:**
```
adb -s <serial> install -r <apk-path>
```

Install all requested devices in a single shell block. If the user says devices are ready, **skip `adb devices`** — go straight to build and install. User refers to devices by nickname (e.g. "G10", "P2P") — look up serial in the table above.

---

## Branch Strategy

- `main` — stable releases only
- `germination` — previous post-MVP feature branch (reference, not active)
- `seed` — current active development

---

## Community Nomenclature

- Release notes → Growth Logs
- Bug fixes → Pruning
- New features → New Branches
- Contributors → Gardeners
- README → The Soil
- CLAUDE.md → The Soil for Claude Code
