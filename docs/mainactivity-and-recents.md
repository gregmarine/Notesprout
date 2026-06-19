# MainActivity Feature Systems & Recents

> Referenced from `CLAUDE.md`. Covers notebook/folder management, browse state, search/sort, exports,
> ML Kit, and the recents system.

## Notebook & Folder Management

- **New Notebook** is a full-screen flow, not an AlertDialog: `btnNewNotebook` launches
  `TemplateBrowserActivity` in `MODE_PICK` + `EXTRA_COLLECT_NAME=true` + `EXTRA_TITLE="New Notebook"` +
  `EXTRA_TARGET_PARENT_ID=currentParentId`. The browser shows a name field (pre-filled with a
  `YYYYMMDD_HHmmss` timestamp, editable), the template grid (Blank default-selected), and a **CREATE**
  button. Name validation: whitelist `[^a-zA-Z0-9_\-. ]`, reject `.`/`..`, non-empty — *format* checked
  in the browser, *duplicate-in-target-folder* checked inside `confirmCreate` (suspend, on IO, via
  `EXTRA_TARGET_PARENT_ID`); a collision Toasts and keeps the user on the screen. `MainActivity` retains
  a post-result dup check as a harmless safety net. On result, `createNotebook(name, libraryTemplateId)`
  seeds the first page's template (see Templates below).
- **Move:** index update only — `.soil` file stays at `Garden/<id>.soil` (UUID unchanged).
- **Rename:** index update only via `repository.renameNotebook` / `renameFolder` (`.soil` file/UUID untouched, same as Move). Context-menu actions use `ic_edit` (Tabler `edit`): "Rename Notebook" between Move Notebook and Set Cover; "Rename Folder" between Move Folder and Delete. Dialog reuses `DialogNewNotebookBinding`, pre-filled with the current name (cursor at end). Notebook rename runs `validateNotebookName`; folder rename runs `validateFolderRename` — same whitelist + `.`/`..` reject, but duplicate check is against the folder's own `parentId` (not the current browse folder) and excludes itself. No-op when name is unchanged. After rename, `refreshActiveView()` re-renders the active mode (normal/search via `scanAndRender`, pinned, or recents).
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

`data class AppViewState(val folderId: String?, val pinnedMode: Boolean)` persisted in `SharedPreferences("notesprout_view_state")`. Saved at every browse-context change. Search mode is never persisted.

**Restore on launch:** `onCreate` loads state synchronously. Non-default state: set `isStateRestored = false`, launch coroutine to `navigateStackToFolder(folderId)` then optionally `enterPinnedMode()`, set `isStateRestored = true`, trigger first render. Layout listener and `onResume` check `isStateRestored` — if false, defer scan to the restore coroutine. **Stale folder:** if `navigateStackToFolder` resolves to root (folder deleted), clear via `AppStateManager.save(context, AppViewState(null, false))`.

## Pinned Browse View

- Back press priority: `isPinnedMode` is checked first (before picker mode, search mode, directory stack)
- `directoryStack` is NOT touched when entering/exiting pinned mode — folder position is preserved underneath
- `onResume()` calls `renderPinnedList()` when `isPinnedMode` — re-fetches in case notebook was unpinned while open
- Pinned mode, search mode, and picker mode are mutually exclusive; each hides the other's toolbar controls
- Card labels in pinned and search modes: immediate parent folder only — `folderLabel.substringAfterLast(" › ")`; root-level notebooks show "Notebooks › Name"

## Search (`search/SearchEngine.kt`)

Fuzzy match against all notebooks: substring (3) > all words present (2) > prefix/initials (1). Opening a notebook from search results rebuilds `directoryStack` by walking the `parentId` chain (`navigateStackToDirectory`) so returning lands in the correct folder.

## Sorting (`sort/`)

`SortPreferences`: `SortField` (NAME / DATE_MODIFIED), `SortOrder` (ASC / DESC), `FolderSort` (FOLDERS_FIRST / NOTEBOOKS_FIRST / MIXED). Persisted in `SharedPreferences("notesprout_sort_prefs")`. Card labels (normal mode): `"$displayName ($dateStr, $timeStr)"` via `DateFormat.getMediumDateFormat` + `DateFormat.getTimeFormat`.

## PDF Export

- `NotebookExporter` renders all pages off-screen on `Dispatchers.IO` using white→template→headings→text→strokes pipeline
- Output to `context.cacheDir/<title>.pdf`; FileProvider (`${applicationId}.fileprovider`) used for both save and share paths
- Share intent **must** include `clipData = ClipData.newRawUri("", uri)` alongside `FLAG_GRANT_READ_URI_PERMISSION` — on Android 12+, the chooser intermediary does not forward URI permissions without `ClipData` (causes silent Google Drive upload failure on NA5C)
- Progress dialog: "Exporting page X of N…" via `Handler(Looper.getMainLooper())`
- After export: `showExportChoice(file)` presents an `AlertDialog` with "Save to device" (`ACTION_CREATE_DOCUMENT` via `savePdfLauncher`) or "Share" (existing `ACTION_SEND` flow). Available from both `NotebookActivity` (toolbar export button) and `MainActivity` (long-press action sheet).

## Page Export (PNG)

- Entry point: long-press a page in `PageIndexActivity` → tap the export button (`ic_export`) in the action-mode toolbar
- `NotebookExporter.exportPage(context, soilPath, pageId, pageNumber, notebookTitle)` — opens a transient Room instance for the given `.soil` path; does NOT checkpoint on close (NotebookActivity's canonical connection is still live)
- Render pipeline: identical to PDF — white → template → headings → text objects → strokes; full-quality, no snapshot shortcut
- Filename format: `<safeTitle>_page<N>.png` where N is the 1-based page number. Same sanitization regex as PDF: `[^a-zA-Z0-9_\\-. ]` → `_`.
- Output to `context.cacheDir/exported_pngs/`; FileProvider path entry `name="exported_pngs"` in `res/xml/file_paths.xml`
- Share intent: `type = "image/png"`, same `ClipData` + `FLAG_GRANT_READ_URI_PERMISSION` pattern as PDF export
- Progress: non-cancellable `AlertDialog` ("Exporting…") matching the PDF export pattern; dismissed on success or failure
- After export: `exitActionMode()` then `showExportChoice(file)` — same "Save to device" / "Share" pattern as PDF; `savePngLauncher` uses `CreateDocument("image/png")`

## ML Kit

- `com.google.mlkit:digital-ink-recognition:19.0.0` — en-US model; `recognizedText` stored in `HeadingObject`
- Model downloads on any network (~20–30 MB, one-time). **TODO:** make this a user-facing setting (Wi-Fi only vs. any). See `MlKitHandwritingRecognizer.initModel()` → `DownloadConditions.Builder()`.

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
  `RecentsManager.resolve(context)` (`suspend`, `Dispatchers.IO`). Resolves each stored entry
  against the index; **skips and prunes** (single re-loaded write) any notebook that is missing,
  soft-deleted, or not a `NOTEBOOK` — self-healing store. `folderPath` is the full breadcrumb
  (`"Notebooks › A › B"`), matching the search/pinned convention; immediate parent is
  `folderPath.substringAfterLast(" › ")`.

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
- **Tap → open** reuses the search-mode pattern: `navigateStackToFolder(parentId)` +
  `AppStateManager.save(AppViewState(parentId, false))` + exit recents + `launchNotebookActivity` —
  so closing the notebook returns to its folder.

### NotebookActivity recents dialog (`notebook/RecentsDialog.kt`)

- `btnRecents` (`ic_clock`) after `btnClose`, before the divider preceding `btnToc`. Modeled on
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

---

## Future Work — Wacom & Generic Android Stylus

Wacom barrel buttons set `BUTTON_STYLUS_PRIMARY`/`BUTTON_STYLUS_SECONDARY` on `MotionEvent` — they do not change `getToolType()`. Fix: check `event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)` in `onTouchEvent` and treat as eraser for that stroke. Low priority — do not let it block BOOX-first progress.
