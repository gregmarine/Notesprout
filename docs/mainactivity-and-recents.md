# MainActivity Feature Systems & Recents

> Referenced from `CLAUDE.md`. Covers the library chrome, notebook/folder management, browse state,
> search/sort, exports, ML Kit, and the recents system.

## Library Chrome — Zones & Width Buckets (`activity_main.xml`)

The library screen has a **top bar** that is swapped out per browse mode and a **bottom bar** that
never is. Which bar a control lives in follows from that:

```
┌──────────────────────────────────────────────────────────────┐
│ breadcrumb / search / pinned / recents / picker  (swapped)   │  ← mode-specific top bar
├──────────────────────────────────────────────────────────────┤
│                        gridContainer                          │
├──────────────────────────────────────────────────────────────┤
│  📅 ✏️        |< < n/n > >|            📓+ 📁+ ⋯             │  ← bottom bar (always present)
│  surface       pagination               actions               │
└──────────────────────────────────────────────────────────────┘
```

- **`surfaceButtonsGroup` (start)** — [`btnToday`](today-dashboard.md) + `btnCalendar` +
  `btnScratchpad`. These are *sibling surfaces* (places to go), not library actions, so they get their
  own zone opposite the create/overflow group. Because the bottom bar is never replaced by search /
  pinned / recents mode, one button each is reachable everywhere and needs **no per-mode duplicate** —
  the group is hidden only in destination-picker mode (`applyPickerModeUI`), where launching another
  surface mid-flow would be disruptive. Prior to this, `btnScratchpad` was mirrored four times
  (breadcrumb + pinned + recents + search toolbars) and `btnCalendar` was buried in the overflow row;
  both are now single buttons.

  **The bar seats three surfaces, and there are four.** Adding the dashboard is what moved `btnTasks`
  into `overflowToolbar` — in **all three** variants, so the bar reads the same everywhere rather than
  shuffling its contents by device. Today leads the group because it is the surface that leads to the
  others. In the narrow bucket **both** live in the overflow: `layout-sw360dp`'s bar is already at
  428dp of the Palma2 Pro's 439dp and cannot absorb another 48dp for either of them (see the width
  table below), while the overflow row has room at 396dp of 439dp.

  Both ids are declared in **all three** variants either way — a missing id makes the view-binding
  field nullable — so `MainActivity` wires them once with no per-variant branch, and hiding falls out
  for free because every mode that hides the surface group already calls `closeOverflowToolbar()`.
- **`paginationGroup` (centre)** — driven only by `isEnabled`, never `visibility`, from
  `updatePaginationControls`. That matters: layout variants can `gone` individual page buttons without
  any Kotlin change and without making the view-binding fields nullable.
- **`actionButtonsGroup` (end)** — `btnNewNotebook` · `btnNewFolder` · `btnMore` (overflow row:
  Import · Templates · Encryption · HWR · Backup · Compact).

**Width buckets.** The full bar needs **520dp**. Three variants of `activity_main.xml` exist, and a
device picks the highest matching qualifier:

| Bucket | Range | Devices | Bottom bar |
|---|---|---|---|
| `layout/` | < 360dp | — | full bar, pagination centred |
| `layout-sw360dp/` | 360–479dp | Palma2 Pro (439dp) | first/last-page `gone`, pagination anchored `toEndOf` the surface group — 428dp of 439dp |
| `layout-sw480dp/` | ≥ 480dp | Go 6 Gen II (571dp), Go 10.3 Gen 2 (992dp), all larger tablets | full bar, pagination centred |

Palma2 Pro is the only device genuinely too narrow for the full bar. The `sw480dp` bucket exists so
the Go 6 Gen II does **not** fall back to the narrow variant and lose its first/last-page buttons
despite having ~140dp spare. There is deliberately **no** `layout-sw600dp/activity_main.xml` — it
would be a byte-identical fourth copy of a ~500-line file, and `sw480dp` already covers everything
above 600dp. (`layout-sw600dp/` still holds `activity_template_browser.xml`.)

Device dp = `px × 160 ÷ density`; all current BOOX devices report density 300, so
`dp = px × 0.533`. Verify a bar change against the *narrowest* bucket member, not the flagship.

## Notebook & Folder Management

- **New Notebook** is a full-screen flow, not an AlertDialog: `btnNewNotebook` launches
  `TemplateBrowserActivity` in `MODE_PICK` + `EXTRA_COLLECT_NAME=true` + `EXTRA_TITLE="New"` +
  `EXTRA_TARGET_PARENT_ID=currentParentId`. The browser shows a name field (pre-filled with a
  `YYYYMMDD_HHmmss` timestamp, editable), a **type radio (Notebook / Text document)**, the encryption
  scope radios, the template grid (Blank default-selected), and a **CREATE** button. Name validation:
  whitelist `[^a-zA-Z0-9_\-. ]`, reject `.`/`..`, non-empty — *format* checked
  in the browser, *duplicate-in-target-folder* checked inside `confirmCreate` (suspend, on IO, via
  `EXTRA_TARGET_PARENT_ID`); a collision Toasts and keeps the user on the screen. `MainActivity` retains
  a post-result dup check as a harmless safety net. On result, `createNotebook(name, libraryTemplateId,
  scope, textDocument)` seeds the first page's template (see Templates below). A **Text document** is
  the same bootstrap flagged to open into the document editor — the chosen template still applies to
  the pages underneath; its card shows the document's opening lines over an `ic_file_text` glyph
  (see [`documents.md`](documents.md) § Text documents).
- **Move:** index update only — `.soil` file stays at `Garden/<id>.soil` (UUID unchanged).
- **Rename:** index update only via `repository.renameNotebook` / `renameFolder` (`.soil` file/UUID untouched, same as Move). Context-menu actions use `ic_edit` (Tabler `edit`): "Rename Notebook" after Move Notebook; "Rename Folder" between Move Folder and Delete. Dialog reuses `DialogNewNotebookBinding`, pre-filled with the current name (cursor at end). Notebook rename runs `validateNotebookName`; folder rename runs `validateFolderRename` — same whitelist + `.`/`..` reject, but duplicate check is against the folder's own `parentId` (not the current browse folder) and excludes itself. No-op when name is unchanged. After rename, `refreshActiveView()` re-renders the active mode (normal/search via `scanAndRender`, pinned, or recents).
- **Copy notebook:** new `ObjectEntity` + copy `.soil` to new UUID path via `soilFile()`.
- **Copy folder:** recursively create new index entries and copy all descendant `.soil` files.
- **Conflict check:** if a sibling with the same name exists at the destination, show AlertDialog "A [notebook/folder] named '[name]' already exists here. Replace it?" Replace proceeds; Cancel stays in picker mode.
- **Folder delete:** recursively soft-deletes all descendants in the index; deletes `.soil` files via `soilFile()`; cleans up WAL sidecars. Confirmation dialog message: `Delete "[name]"? This will permanently remove all notebooks and subfolders inside it. This cannot be undone.`

## Templates

- **Toolbar entry:** `btnTemplates` (`ic_template`) sits in `actionButtonsGroup` after `btnNewFolder`,
  in all three `activity_main.xml` variants (`layout/`, `layout-sw360dp/`, `layout-sw600dp/`). It
  launches `TemplateBrowserActivity` in `MODE_MANAGE`. Visibility is toggled alongside `btnNewFolder`
  (hidden in picker, pinned, recents, and search modes).
- **`createNotebook(name, libraryTemplateId = "")`:** when `libraryTemplateId` is non-empty, it loads
  the library `TemplateObject` (suspend, before opening the `.soil`), inserts a `type="template"` row
  into the new `.soil`, and points the first page's `data.template` at that row — all inside the
  existing creation coroutine on IO.
- The template **library model**, `TemplateBrowserActivity` modes, and the in-notebook
  `TemplateDialog` are documented in the Template System section of
  [`drawing-engine.md`](drawing-engine.md) and the Templates subsection of
  [`data-architecture.md`](data-architecture.md).

## ActionSheetDialog (`ActionSheetDialog.kt`)

Reusable flat action sheet. Builder: `.title(String)` (optional) → `.addAction(iconRes?, label, onClick)` → `.show()`. `shape_bordered` window background after `show()`. 1dp inkBlack dividers between rows. Optional title row has an `ic_x` close button. No bottom Cancel row. Icon slot is a `Space` when `iconRes` is null, keeping labels aligned.

## Browse State Persistence (`state/AppStateManager.kt`)

```kotlin
data class AppViewState(
    val folderId: String?,
    val pinnedMode: Boolean,
    val recentsMode: Boolean = false,
    val searchMode: Boolean = false,
    val searchQuery: String = "",
)
```

Persisted in `SharedPreferences("notesprout_view_state")`. Saved at every browse-context change — including entering/exiting search and recents modes.

**Restore on launch:** `onCreate` loads state synchronously. Non-default state (any non-root folder, pinned/recents/search mode active, or a non-LIBRARY surface on cold launch): set `isStateRestored = false`, launch coroutine `restoreSavedBrowseState(state, surface, coldLaunch)`, set `isStateRestored = true`, trigger first render. Layout listener and `onResume` check `isStateRestored` — if false, defer scan to the restore coroutine.

- **Mode restore:** after navigating to the saved folder, `restoreSavedBrowseState` applies the active mode: pinned → `applyPinnedModeUI()`; recents → `applyRecentsModeUI()`; search (query non-empty) → `applySearchModeUI()` + restores `currentSearchQuery`.
- **Stale folder:** if `navigateStackToFolder` resolves to root (folder deleted), clear via `AppStateManager.save(context, AppViewState(null, false))`.

## Surface Stack — Launch Restore (`state/SurfaceStack.kt`)

A cold launch reopens **what the user was doing**, not just the library: the whole chain of open screens, so a scratch pad opened from a notebook comes back over *that notebook*, and stepping out of it lands where it did before the app died.

```kotlin
enum class AppSurface { NOTEBOOK, CALENDAR, DAY_WINDOW, SCRATCHPAD, TASKS }  // library = implicit bottom

data class SurfaceEntry(
    val token: String,               // the Activity *instance* — survives onSaveInstanceState
    val surface: AppSurface,
    val notebookId: String? = null,  // NOTEBOOK
    val dayDate: String? = null,     // DAY_WINDOW (ISO-8601)
    val dayView: String? = null,     // DAY_WINDOW (DayDetailActivity.ViewMode name)
)
```

Bottom-first list, kotlinx JSON under one key (`surface_stack`) in the same prefs file as the browse state — separate key + separate accessors, so the many `AppStateManager.save(AppViewState(...))` browse writes can't clobber it. Exact in memory for the process's life, mirrored to prefs on every mutation; the mirror is what survives the kill. Main thread only.

- **The Activities maintain it themselves**, from two hooks: `onCreate` → `attach` (append, or refresh in place if this instance is a recreation), `onResume` → `markTop` (I'm on screen — drop anything still recorded above me). `markTop` is what pops a surface the user backed out of, so there's **no `onDestroy` bookkeeping** — which would be unreliable exactly when it matters, since a killed process gets no `onDestroy`. `attach` is needed as well because a restored stack is launched with `startActivities`, and everything below the top is created without ever being resumed.
- **`MainActivity.onResume` calls `reset`** — the library is on screen, so nothing is stacked on it. Every path home therefore heals the stack; a stale entry can't outlive a visit to the library. Guarded by `isStateRestored`, since an in-flight restore is about to stack surfaces on top.
- **Ordering holds** because the leaving Activity's `onPause` always precedes the revealed Activity's `onResume`.
- **`token` identifies the instance, not the surface type** — the same notebook can legitimately appear twice in one stack (notebook → calendar → day window → same notebook again), so identity can't be surface + payload. It's stored in `onSaveInstanceState`, so an Activity Android rebuilds (config change, or a task the OS restores itself) re-attaches to its existing entry instead of duplicating it.
- **DayDetail also `attach`es in `onPause`:** it's the one surface whose payload changes without the user leaving the Activity (`switchToDate`, `switchViewMode`).
- **Restore (`MainActivity.restoreSurfaces`), cold launch only** (`savedInstanceState == null`; on a warm restart the surfaces are already on the back stack or were just closed): map the entries to intents and `startActivities` them bottom-first, then `reset` (the relaunched Activities re-record themselves).
- **Only the notebook and day window need identity passed in.** The calendar (`calendar_state` prefs) and the scratch pad (`ScratchpadPreferences`) already persist their own position and restore it on open. The day window has no such store, so its date + view ride in the entry and reach it via `DayDetailActivity.EXTRA_VIEW` — absent on a normal open, which still lands on **Events**.
- **The source notebook is passed back down:** a restored calendar / day window / scratch pad gets the `EXTRA_FROM_NOTEBOOK_*` of the notebook directly beneath it (for a day window, the one beneath its calendar), so Send-to-Notebook still targets what it did.
- **This can put an encrypted notebook back underneath.** It only unlocks when the user steps down to it, but its `.soil` is opened for a screen they aren't looking at yet — accepted, so they land where they actually were.
- **Anything unresolvable is dropped, the rest still comes back:** a deleted notebook (`getNotebook` null / `deletedAt != null`) or an unparseable date drops that one entry.
- **Migration:** installs predating the stack only stored `last_notebook_id`. Read once, as a one-entry NOTEBOOK stack, then removed on the next write.

## Pinned Browse View

- Back press priority: `isPinnedMode` is checked first (before picker mode, search mode, directory stack)
- `directoryStack` is NOT touched when entering/exiting pinned mode — folder position is preserved underneath
- `onResume()` calls `renderPinnedList()` when `isPinnedMode` — re-fetches in case notebook was unpinned while open
- Pinned mode, search mode, and picker mode are mutually exclusive; each hides the other's toolbar controls
- Card labels in pinned and search modes: immediate parent folder only — `folderLabel.substringAfterLast(" › ")`; root-level notebooks show "Notebooks › Name"

## Search (`search/SearchEngine.kt`)

Fuzzy match against all notebooks: substring (3) > all words present (2) > prefix/initials (1). Opening a notebook from search results rebuilds `directoryStack` by walking the `parentId` chain (`navigateStackToDirectory`) so returning lands in the correct folder.

**Search toolbar:** entering search mode hides the breadcrumb bar and shows a dedicated `searchToolbar` (same pattern as pinned/recents) containing a **"Search: {query}"** title, a search icon (re-opens `SearchDialog` pre-filled with the current query to modify it), and an X button (`btnClearSearch`) to exit search. `applySearchModeUI()` toggles both toolbars and also hides `btnMore` (new-notebook/folder actions suppressed during search).

**Search persistence:** `enterSearchMode` saves `searchMode=true, searchQuery=query` to `AppStateManager`; `exitSearchMode` clears both. On cold launch, a saved search query is restored and `applySearchModeUI()` is applied so the user lands directly back in their previous search.

## Sorting (`sort/`)

`SortPreferences`: `SortField` (NAME / DATE_MODIFIED), `SortOrder` (ASC / DESC), `FolderSort` (FOLDERS_FIRST / NOTEBOOKS_FIRST / MIXED). Persisted in `SharedPreferences("notesprout_sort_prefs")`. Card labels (normal mode): `"$displayName ($dateStr, $timeStr)"` via `DateFormat.getMediumDateFormat` + `DateFormat.getTimeFormat`.

## Notebook-Level Export

Both `MainActivity` (long-press → Export) and `NotebookActivity` (canvas "Page" menu → Export) first
present a **format chooser** `ActionSheetDialog` with two rows before starting any export:

- **Export as PDF** → **template sub-choice** (see below) → existing PDF path (encrypted-unencrypted warning + optional PDF password)
- **Export Notebook (.soil)** → full-notebook copy via `NotebookPackager`

### Strokes-only (no-template) export

Every **raster** export path (PDF + PNG, notebook-level and page-index) first offers an
`ActionSheetDialog` template sub-choice: **"With template"** / **"Strokes only (no template)"**. This
threads a single `includeTemplate: Boolean` flag through `NotebookExporter` (`export`,
`exportPagesPdf`, `exportPagesPng`, `exportPage` → `renderPageBitmap`); when false,
`renderPageBitmap` skips `loadTemplate` so the page renders content layers on white with **no
template image**. Only the page template (lines/grid) is suppressed — headings, text, line/shape
objects, links, sticky-note icons, and pen strokes all still render. Use case: writing on a lined
template but exporting just the handwriting (blog posts, letters). No data-model or schema change.

- Sub-choice entry points: MainActivity Export → "Export as PDF"; NotebookActivity Export → "Export
  as PDF"; PageIndex single-page Export (PNG); PageIndex multi Export → PDF; PageIndex multi Export →
  PNG → Save images. **PNG → Save as templates is excluded** (a saved template keeps its lines/grid —
  always `includeTemplate=true`). Notebook-level export is PDF-only; whole-notebook strokes-only PNG
  is reached via page index → Select All → Export → PNG → Save images → Strokes only.

### PDF Export

- `NotebookExporter` renders all pages off-screen on `Dispatchers.IO` using white→template→headings→text→strokes pipeline (template skipped for strokes-only)
- Output to `context.cacheDir/<title>.pdf`; FileProvider (`${applicationId}.fileprovider`) used for both save and share paths
- Share intent **must** include `clipData = ClipData.newRawUri("", uri)` alongside `FLAG_GRANT_READ_URI_PERMISSION` — on Android 12+, the chooser intermediary does not forward URI permissions without `ClipData` (causes silent Google Drive upload failure on NA5C)
- Progress is shown **inline on the export screen** ("Rendering page X of N…"), not in a modal dialog.
- **Encrypted-notebook warning:** the rendered PDF is plaintext, so the export screen shows a
  standing inline warning whenever the output would leave the app readable. Ticking "Protect PDF
  with a password" clears it.

### The export screen

`startExportFromMain(entity)` opens `ExportActivity` — that is the whole implementation. Format,
page scope, render options, encryption and destination are all chosen there, and the same screen
serves NotebookActivity and PageIndexActivity. See
[`docs/full-notebook-export.md` § The Export Screen](full-notebook-export.md#the-export-screen).

- Export cache dirs: `cacheDir/exported_pdfs|exported_pngs|exported_text|exported_notebooks/`, each
  wiped+recreated per export.
- FileProvider entry: `<cache-path name="exported_notebooks" path="exported_notebooks/" />` in `file_paths.xml`.
- **openableKey semantics:** `""` = plaintext; non-empty = passphrase to open with; `null` = copy
  cold without a meta refresh.

See [`docs/full-notebook-export.md`](full-notebook-export.md) for the full format, `notebook_meta`
schema, continuous upkeep, and encrypted trade-off.

## Full-Notebook Import (.soil)

Consumes a `.soil` produced by full-notebook export. Entry points:

- **Overflow Import button** — now an `ActionSheetDialog`: **Notebook (.soil)** → `importSoilLauncher` (`OpenDocument`, MIME `application/octet-stream` + `*/*`) → `startImportFromUri(uri)`; **Text or Markdown…** → `importTextLauncher` → `importTextDocumentFromUri` — a new **text document** in the current folder (name deduped, ≤10 MB, content written as the notebook document during the create bootstrap, opened into the editor). `.md`/`.markdown`/`.txt` files and shared literal text arriving via the intent filters below take the same text path — detected by extension/MIME in `startImportFromUri` before the `.soil` probe.
- **Open-with / Share-to intent filters** — `AndroidManifest.xml` registers three filters on `MainActivity` (`launchMode="singleTop"`):
  - `ACTION_VIEW` with `scheme=content`, `mimeType=application/octet-stream`
  - `ACTION_VIEW` with `scheme=file`, `mimeType=application/octet-stream` (legacy)
  - `ACTION_SEND` with `mimeType=application/octet-stream`
  - Cold launch: `onCreate` calls `handleIncomingIntent(intent)` when `savedInstanceState == null`
  - Already-open: `onNewIntent` calls `handleIncomingIntent(intent)` and `setIntent(intent)`

**Import pipeline** (both entry points feed `startImportFromUri`):
1. Copy the incoming `content://` URI to `cacheDir/imported_notebooks/incoming.soil` (dir is wiped+recreated each import).
2. Probe the temp file (`SoilCrypto.probe`). Invalid → toast + abort. Encrypted → `KeyResolver.resolveForImportRead` (prompts + verifies; `"IMPORT"` AttemptLimiter bucket; cancel → abort + wipe temp).
3. `NotebookImporter.readManifest(file, fallbackName, passphrase?)` — reads `notebook_meta` + page count; missing meta → fallback name + empty `folderPath`.
4. **ID collision check** — if `meta.notebookId` already exists in the index (live row): show **Replace / Keep both / Cancel** dialog. Replace keeps the existing row's placement; Keep both assigns a fresh UUID.
5. **Placement dialog** — skipped for Replace. Options: **"Notebook's folders"** (recreates missing folders with same UUIDs via `ensureFolderWithId`) or **"Choose folder…"** (enters `DestinationPickerState.ImportNotebook` — existing picker; no folders created).
6. **Name conflict** — if a notebook of the same name exists in the target folder: Replace (soft-delete conflict, import with same name) or Keep both (name gets " Copy" suffix).
7. **Keying chooser** (encrypted only, after placement, before writing to Garden) — Keep existing / Use device global / New notebook passphrase. See [`docs/encryption.md`](encryption.md) for the scope rule.
8. Re-key on the temp file (if needed), copy to `soilFile(context, resolvedId)`, register/update the index row, refresh `notebook_meta` inside the Garden file (`wal_checkpoint(TRUNCATE)`), delete the temp.

**Cache hygiene:** `imported_notebooks/` is wiped+recreated at the start of each import; the temp `incoming.soil` is deleted after success or cancel. Encrypted temp is never decrypted to disk — the still-encrypted `.soil` is the temp; re-key happens in place.

Key classes: `NotebookImporter.kt` (engine — `readManifest`, `importPlaintext`, `replacePlaintext`, `importEncrypted`, `replaceEncrypted`); import dialogs live entirely in `MainActivity`.

See [`docs/full-notebook-export.md`](full-notebook-export.md) for the `.soil` format, `notebook_meta` schema, and the full import spec.

## Page Index — Multi-Page Selection (`PageIndexActivity`)

The page index is a paginated grid of page thumbnails (rendered on demand from page content — there
is no stored per-page snapshot; a per-visit LRU + neighbour prefetch + raw-key fast open keep it
quick, see the thumbnail section of [`docs/drawing-engine.md`](drawing-engine.md)). Long-press
enters **action mode**; the user can
then select any number of pages — across pagination — and apply every toolbar action to all of them at
once. Calm/paper-like per `docs/design-system.md`: selection is shown with the existing card highlight
(`bg_page_card_current`, 3dp inset border), no color, no Material chrome.

### Selection model

- **`selectedPageIds: LinkedHashSet<String>`** — selection by stable page **UUID**, not index. IDs
  survive pagination and reorder/delete reshuffles; insertion order is preserved (it drives paste/move
  block ordering). Empty set = normal mode; non-empty = action mode (`inActionMode()`).
- **Tap** in normal mode navigates to that page (`finishWithResult(pageIndex)`). **Long-press** enters
  action mode with that page selected. In action mode, **tap toggles** a card; emptying the set exits
  action mode. Highlight: normal mode → the open page only; action mode → every selected card;
  destination mode → the source pages.
- `pruneSelection()` drops IDs no longer present after any reload; destructive/move/paste/template ops
  clear the selection via `exitActionMode()`.

### Action-mode toolbar (`activity_page_index.xml`)

Shown only in action mode. Title shows `"N selected"`. Buttons (all enabled for any selection size):
**Select All** (`btnSelectAll`, `ic_select_all`), **Copy** (`btnCopyPage`), **Move** (`btnMovePage`),
**Set Template** (`btnSetTemplate`, `ic_template`), **Export** (`btnExportPage`), **Delete**
(`btnDeletePage`). Disabled buttons dim to `alpha 0.4f` (visible on e-ink — never color). There is no
separate Paste button — Copy goes straight to destination-picking (see below).

- **Select All** (`toggleSelectAll`): selects every page in the notebook (across all grid pages). When
  everything is already selected, tapping it **deselects all and exits** action mode (an empty
  selection *is* normal mode; keeping one selected was rejected as surprising). Content description
  flips "Select all" / "Deselect all".
- **Delete** (`executeDelete`): guard — the notebook must retain ≥1 page (`selectedPageIds.size >=
  pages.size` → Toast "Cannot delete all pages"). Confirmation message is `"Delete Page N?"` for a
  single page, else `"Delete N pages?"`. Each page's pre-delete index is snapshotted up front
  (`indexById`) so recorded undo indices are consistent with the list NotebookActivity restores
  against. `currentPageIndex` is recomputed by stable id (clamped to a survivor if the open page was
  deleted); `currentGridPage` is re-clamped in case the last grid page emptied.

### Copy / Paste & Move — destination-picking with Before/After

Move and Paste share one **destination-picking mode** (`DestMode { NONE, MOVE, PASTE }`): hide the
action buttons, show two selectable buttons **"Move/Copy Before"** and **"Move/Copy After"**, and tap a
destination card to **select + preview** the landing spot. The flow is *select → pick destination →
(preview, adjust Before/After) → **OK***. `insertBefore` resets to **true (Before)** each time
destination mode is entered. Back / system-back cancels destination mode → action mode
(`cancelDestMode`), then action mode → normal (`exitActionMode`), then finishes.

- **Preview + confirm step** (`pendingDestPageId`): a destination tap does **not** commit — it sets
  `pendingDestPageId`, outlines the card, and draws a bold inkBlack **insertion bar** on the card's
  leading edge (Before) or trailing edge (After) so the landing spot is legible (`borderGray` is
  invisible on e-ink → inkBlack). A **Confirm (✓)** button (`btnConfirmDest`, `ic_check`) appears only
  once a destination is chosen; tapping it runs `confirmDestination()` → `executeMove`/`executePaste`.
  Flipping Before/After moves the bar and updates the confirming title ("Move before p.3?"); tapping a
  different card moves the preview. The preview survives pagination (state is by id, re-derived in
  `renderGridPage`). `refreshDestChrome()` owns the title + Confirm-button visibility. **The batch ops,
  undo/redo, and extras are untouched** — the confirm step is purely a gate in front of them.
- **Copy** (`copySelectedPages`): stashes the selection into `pendingCopyPageIds`, then immediately
  enters `PASTE` destination mode. The chosen destination deep-copies the clipboard as a contiguous
  block before/after it, in clipboard (selection) order.
- **Move** (`enterDestMode(MOVE)`): stashes `moveSourceIds`. A non-source card is a valid destination;
  tapping a source card clears the pending destination (back to picking) or, if none is pending, cancels.
  Sources can't be their own destination.
- Data layer (`data/PageCopier.kt`): `movePagesRelativeRaw(pageIds, targetPageId, before, path)` and
  `copyPagesRelativeRaw(sourcePageIds, targetPageId, before, path)`. Both rebuild the full page order in
  one transaction, ordering the block by **original document order** so undo/redo predecessor chaining
  is stable. Move returns `(pageId, prevAfterId, newAfterId)` triples (undo/redo); copy returns
  `(newPageId, newPageIndex)` pairs. (The old single-page `copyPageAfterRaw`/`movePageAfterRaw` were
  removed in Session 5 — superseded by these batch variants.)

### Set Template (multi)

- `btnSetTemplate` → `chooseTemplateForSelection()` snapshots the selection into
  `pendingTemplateTargets` and launches `TemplateBrowserActivity` in `MODE_PICK`. The "Blank" tile
  returns `RESULT_TEMPLATE_ID = ""` → clears the template.
- **Template-id indirection:** a page's `data.template` stores a `.soil` `type="template"` **row id**,
  while the picker returns a global-index **library id**. `applyTemplateToSelection` bridges this:
  `insertSoilTemplateRaw` copies the library image into **one shared `.soil` template row** per
  Set-Template op (parentId from `readNotebookRowId`), then `setPagesTemplateRaw` points every selected
  page at it and returns each page's previous template id for undo. Blank (`""`) clears.

### Export (single vs. multi)

`executeExport()` routes by selection size. Every raster sub-path first asks the **template
sub-choice** (`chooseExportTemplate(title) { includeTemplate -> … }` — "With template" / "Strokes
only (no template)") before rendering; the chosen `includeTemplate` flows to the matching
`NotebookExporter` call. See **Strokes-only export** above.

- **Single** (`selectedCount() == 1`) → template sub-choice → the richer `showExportChoice`: Save to device
  (`savePngLauncher`, `CreateDocument("image/png")`) / Save as Template (`MODE_SAVE_TARGET`) / Share.
  Render via `NotebookExporter.exportPage(...)`.
- **Multi** (`> 1`) → `showMultiExportDialog`: **PDF** / **PNG** / Cancel.
  - **PDF** — `NotebookExporter.exportPagesPdf(context, soilPath, pageIds, notebookTitle, onProgress)`
    renders all selected pages (in page order) into one `PdfDocument`, no cover; then offers Save
    (`savePdfLauncher`) / Share. Pages are sorted to page order, not selection order
    (`orderedSelectedEntries`).
  - **PNG → Save images** — `exportPagesPng(...)` renders one PNG per page named
    `<safeNotebook>_<heading|PageN>.png` (de-duplicated via `makeUniqueFilename`), then prompts **once**
    for a folder (`OpenDocumentTree`) and writes each file via `DocumentsContract.createDocument` (no
    `androidx.documentfile` dep, no per-file prompts).
  - **PNG → Save as templates** — first prompts for a **destination folder** via
    `TemplateBrowserActivity.MODE_PICK_FOLDER` (a folders-only chooser whose **Save Here** returns the
    folder id in `RESULT_TEMPLATE_FOLDER_ID`, `""` = root). `renderAndImportTemplates(parentId)` then
    renders each selected page to PNG and imports it into that folder
    (`IndexRepository.createTemplate(name, parentId, w, h, base64)`), named from the page label and
    de-duplicated against existing templates **in that folder** (`makeUniqueTemplateName`, `(2)`/`(3)`
    suffix). Cancel/back in the chooser imports nothing and leaves the selection in action mode.

All exporters share `NotebookExporter.renderPageBitmap(...)` (white → template → headings → text →
strokes, full-quality), recycle bitmaps per page, run on `Dispatchers.IO` behind a non-cancellable
"Exporting page X of N…" progress dialog, and open a transient Room instance that does **not** checkpoint
on close (NotebookActivity's canonical connection stays live). Cache dirs: `exported_pdfs/`,
`exported_pngs/` (FileProvider entries in `res/xml/file_paths.xml`). Share intents use the `ClipData`
+ `FLAG_GRANT_READ_URI_PERMISSION` pattern (see PDF Export note above).

### Undo/redo round-trip (`NotebookActivity.pageIndexLauncher`)

The index never mutates NotebookActivity's undo stack directly. Every session action is recorded
(`pastedActions`, `deletedActions`, `movedActions`, `templateChanges`) and returned as **comma-joined
string extras** in `finishWithResult(...)`; `pageIndexLauncher` splits them back out and pushes
**one batch `UndoRedoAction` per operation** so a single undo reverses the whole batch:
`PagesDeleted`, `PagesMoved` (split by `EXTRA_MOVED_OP_SIZES`), `PagePasted`, `TemplatesChanged`.
A changed open page reloads its template on return to the canvas. Empty string encodes a null/blank id
in every extras list.

## ML Kit

- `com.google.mlkit:digital-ink-recognition:19.0.0` — en-US model; `recognizedText` stored in `HeadingObject`
- Model downloads on any network (~20–30 MB, one-time). **TODO:** make this a user-facing setting (Wi-Fi only vs. any). See `MlKitHandwritingRecognizer.initModel()` → `DownloadConditions.Builder()`.

---

## Table of Contents (TOC)

The notebook TOC (`toc/TocDialog`) is a **hierarchical** H1→H2→H3 outline of the notebook's
headings, built by `toc/TocRepository.buildTocTree(): List<TocNode>`. It is opened **only** by the
swipe-down-on-canvas gesture (`NotebookActivity.evaluateSwipeDownToc` → `openToc()`) — the former
`btnToc` toolbar button was retired.

- **`TocNode`** (`toc/TocNode.kt`) — `pageNumber`, `pageIndex`, `pageId`, `level` (1–3), `title`
  (prefix-stripped; `""` for unrecognized stroke headings), `heading: HeadingStroke`, and a mutable
  `children` list. Returned list holds the H1 roots; H2/H3 hang off `children`.
- **Tree build** — all non-deleted headings are resolved to their page (layer→page map), sorted into
  **document order** (`pageIndex` → `boundingBox.top` → `left`), then walked once with running
  `currentH1`/`currentH2` pointers:
  - `level 1` → new root; resets `currentH2`.
  - `level 2` → child of `currentH1`; **orphan H2 (no preceding H1) is skipped**.
  - `level 3` → child of `currentH2`; **orphan H3 is skipped**.
  - The "most recent" parent **persists across page boundaries** — an H2 on page 3 can parent under an
    H1 from page 1.
- **`TocDialog`** is a **collapsible cascading menu**. It opens **collapsed** — only the H1 roots are
  visible. An in-memory `expanded: MutableSet<String>` (keyed on `node.heading.id`, empty by default,
  **not persisted** — reopening starts collapsed) drives a `computeVisibleNodes()` pre-order walk that
  descends into a node's `children` only when its id is in `expanded`. Each render builds the **visible
  list**; pagination (`itemsPerPage` measured from row height; first/prev/next/last + indicator) iterates
  the visible list, and `toggleExpanded()` recomputes it, clamps the current page, and re-renders without
  dismissing.
- **Row chrome** (`item_toc_entry.xml`): a leading `@+id/btnTocToggle` (`ic_plus` / `ic_minus`,
  inkBlack) sits before the page number. It is `VISIBLE` only for nodes with children (`ic_minus` when
  expanded, else `ic_plus`) and `INVISIBLE` otherwise (preserves alignment). The whole content row
  (`@+id/llTocRowContent` — toggle, page number, divider, and text) is indented `(level − 1) × 16dp`,
  so a child row shifts wholesale under its parent. Recognized rows show `node.title`; unrecognized rows
  render a `HeadingThumbnailView` from `node.heading`. The toggle button consumes its own click, so
  tapping it expands/collapses without navigating; tapping anywhere else on the row dismisses + navigates.
- **Active-page auto-expand** — when the TOC opens, if the active page maps to an H2 or H3 node its
  full ancestor chain is pre-added to `expanded` before the first render. H2 pre-expands its H1
  parent; H3 pre-expands both H2 and H1. H1 pages and pages not in the TOC open fully collapsed.
  Logic lives in `TocDialog` right after `resolveActiveNode()`, before `resolveHighlightNodeId()`.
- **Active-page highlight** — `resolveHighlightNodeId()` finds the active node (last node with
  `pageIndex ≤ currentPageIndex`); if that node is collapsed away, it walks a `parentMap` up to the
  nearest **visible ancestor** and highlights that row (`bg_toc_active_entry`). The dialog also opens on
  the page that shows the resolved row.

> The TOC is distinct from the **page-name rule** (`PageHeadingNames`, see
> [`docs/content-objects.md`](content-objects.md)) — `TocRepository` no longer produces page names.

---

## Recents System

Device-local list of the most recently opened notebooks, surfaced in two places with different UI:
a **MainActivity recents browse mode** (notebook cards) and a **NotebookActivity recents dialog**
(TOC-style paginated switch list).

### Store (`data/recents/`)

- `RecentEntry` — `@Serializable data class RecentEntry(notebookId: String, timestamp: Long)`.
- `RecentsManager` — `object` over `SharedPreferences("notesprout_recents")`, single key `entries`
  holding a JSON-serialized `List<RecentEntry>` via `kotlinx.serialization`. Mirrors
  `AppStateManager` / `SortPreferencesManager` — **not** in `notesprout.db`, **not** in any `.soil`.
  - `MAX_ENTRIES = 20`. On overflow the oldest (furthest-back timestamp) is dropped.
  - **Identity:** a notebook appears at most once. `recordOpen` drops any existing entry, prepends a
    fresh one stamped *now*, caps to 20.
  - **Timestamp:** set to *now* on open; *updated* to *now* on close. List is always ordered by
    `timestamp` descending (newest-first). `load()` is tolerant of malformed/absent JSON → empty.
  - `recordClose` is a no-op if the id is blank or not currently listed; `remove` backs the prune.
- `ResolvedRecent(notebookId, notebookName, folderPath, timestamp)` — display model produced by
  `RecentsManager.resolve(context, exclude, limit)` (`suspend`, `Dispatchers.IO`; both optional —
  the defaults resolve everything, which is what this screen uses). Resolves the stored entries
  against the index in **one blob-free `ObjectSummary` batch read** (never a full row per entry —
  that dragged each cover blob out of the index); **skips and prunes** (single re-loaded write) any
  notebook that is missing, soft-deleted, or not a `NOTEBOOK` — self-healing store. `exclude` drops
  entries before lookup and `limit` caps the *valid* results — the Today dashboard's shape; excluded
  ids are not health-checked. `folderPath` is the full breadcrumb (`"Notebooks › A › B"`), matching
  the search/pinned convention; immediate parent is `folderPath.substringAfterLast(" › ")`.

### Record points (`NotebookActivity`)

- **Open:** `RecentsManager.recordOpen(this, notebookId)` in `onCreate` right after `notebookId`
  resolves from `EXTRA_NOTEBOOK_ID`.
- **Close:** `RecentsManager.recordClose(applicationContext, nbId)` in `sealNotebook()` (the seal runs
  on `appScope` while the Activity may be finishing — use `applicationContext`).

### MainActivity recents mode

- `btnRecents` (`ic_clock`) sits after `btnSearch`, before `btnSort`; visible only when not pinned
  and not in search. `isRecentsMode` is mutually exclusive with pinned/search/picker and is **never**
  persisted to `AppStateManager` (same as search mode). Back-press handles `isRecentsMode` first.
- Chrome: title "Recent Notebooks" + X close; bottom bar shows pagination only (new-notebook /
  new-folder hidden). `renderRecentsList()` resolves off-thread, renders notebook cards (reusing the
  pinned/search card builder) with folder name + notebook name + date/time (`getMediumDateFormat` +
  `getTimeFormat`), paginated over the resolved list; empty state "No recent notebooks".
- **Tap → open** calls `launchNotebookActivity(entity)` directly (no mode-clearing). The recents mode
  stays active in both memory and prefs, so closing the notebook returns to the recents screen.
  `launchNotebookActivity` (every library open) raises the tap-time "Opening…" overlay via
  `OpeningOverlay.showThen` — see the overlay pattern in
  [`design-system.md`](design-system.md) — on top of its 800ms double-tap debounce.
- **Recents persistence:** `enterRecentsMode` saves `recentsMode=true` to `AppStateManager`;
  `exitRecentsMode` clears it. On cold launch, recents mode is restored via `applyRecentsModeUI()`.

### NotebookActivity recents dialog (`notebook/RecentsDialog.kt`)

- `btnRecents` (`ic_clock`) sits second in the default order, right after `btnClose` (see
  `ToolbarButtonRegistry.DEFAULT_ORDER`). Modeled on
  `TocDialog`: paginated (measure row height → `itemsPerPage`; first/prev/next/last + indicator).
  Each row: notebook name (TOC heading style) / date-time (smaller) / folder path (smaller).
  Layouts: `dialog_recents.xml`, `item_recent_entry.xml`. Empty state "No recent notebooks".
- Data: `RecentsManager.resolve(context)` then **exclude the currently-open notebook** (filter by id).

### Switch flow + return-to-folder

- `switchToRecentNotebook(selectedId)`: resolve the selected `ObjectEntity` (abort if pruned) →
  `AppStateManager.save(AppViewState(selected.parentId, false))` → `closeNotebook()` (seals current,
  fires `recordClose`) → launch the selected notebook directly (new `NotebookActivity` with its
  `EXTRA_NOTEBOOK_ID`/`NAME`, **not** via MainActivity) → `finish()`. The new Activity's `onCreate`
  fires `recordOpen` for the open side.
- **Return-to-folder sync** lives in `MainActivity.resumeNormalBrowse()` (called from `onResume`):
  when no special mode is active and the persisted browse folder differs from the current one,
  re-navigate the stack to the persisted folder before rendering. Narrow by design — the normal close
  path leaves persisted == current, so it no-ops and just scans.

### Template recents (separate store)

Library templates have their own device-local recents store (`TemplateRecentsManager`, prefs
`notesprout_template_recents`), surfaced as a Recents view inside `TemplateBrowserActivity` (PICK only).
It is independent of notebook recents — see `data-architecture.md` (template recents) and
`drawing-engine.md` (the browser's Pinned/Recents views).

---

## Future Work — Wacom & Generic Android Stylus

Wacom barrel buttons set `BUTTON_STYLUS_PRIMARY`/`BUTTON_STYLUS_SECONDARY` on `MotionEvent` — they do not change `getToolType()`. Fix: check `event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)` in `onTouchEvent` and treat as eraser for that stroke. Low priority — do not let it block BOOX-first progress.
