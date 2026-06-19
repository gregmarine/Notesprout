# Multi-Page Selection — Page Index Enhancement Plan

> **Goal:** Upgrade the page index (`PageIndexActivity`) from single-page actions to full
> **multi-page selection**. Long-press still activates action mode; the user can then select any
> number of pages — across pagination — and apply every toolbar action to all of them at once.
> Adds a **Select All** control, a new **Set Template** toolbar action, a **before/after** toggle
> for move/paste destinations, and a richer **Export** flow (PDF combined, or PNG per-page, with an
> "export as templates" option).
>
> **Slogan check:** the index stays calm and paper-like — selection is shown with the existing card
> highlight, no color, no Material chrome (see `docs/design-system.md`).

---

## Audience & Working Agreement

- Each **implementation session** below is written to be handed to a **Sonnet subagent**. It must
  contain enough file paths, function names, and line references for that subagent to implement
  without re-discovering the architecture.
- At the **end of each session**, a **Haiku subagent** does a **clean build + install on G10**
  (serial `34E517F9`) for the user to test.
- If the user reports issues: a **Sonnet subagent** investigates + fixes, then a **Haiku subagent**
  does another clean build + install on G10. Repeat until the user confirms all tests pass.
- Then: update this file's **Status** for that session, and **commit all changes (no push)**.
- Phase-2 candidates discovered along the way are appended to **§ Phase 2 Backlog** — do not
  implement them in Phase 1 without discussion.

### Build & install (G10) — reference

```sh
cd apps/notesprout_android && ./gradlew clean assembleDebug
adb -s 34E517F9 install -r app/build/outputs/apk/debug/app-debug.apk
```

(Debug variant only, per CLAUDE.md. If the user says G10 is ready, skip `adb devices`.)

---

## Architecture Recap (read before any session)

Key files:

| File | Role |
|---|---|
| `app/src/main/kotlin/com/notesprout/android/PageIndexActivity.kt` (~841 lines) | The page index screen. Owns selection state, action mode, move mode, all toolbar actions. |
| `app/src/main/res/layout/activity_page_index.xml` | Top bar (back + title + action buttons), grid container, bottom pagination bar. |
| `app/src/main/kotlin/com/notesprout/android/data/PageCopier.kt` | Raw-SQLite page ops used by the index: `copyPageAfterRaw`, `movePageAfterRaw`, `deletePageRaw` (+ Room twins). |
| `app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt` | `export(...)` (full-notebook PDF, with cover + `onProgress`) and `exportPage(...)` (single PNG). |
| `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` | Launches the index (`pageIndexLauncher`, line ~445), consumes result extras, pushes undo actions; owns `applyTemplateToCurrentPage` / `persistPageTemplate` (line ~3562) and the template picker launch (MODE_PICK, line ~793). |
| `app/src/main/kotlin/com/notesprout/android/TemplateBrowserActivity.kt` | `MODE_PICK` returns `RESULT_TEMPLATE_ID`; `MODE_SAVE_TARGET` imports a PNG as a template via `IndexRepository.createTemplate(...)`. |
| `app/src/main/kotlin/com/notesprout/android/history/UndoRedoAction.kt` | `PagePasted`, `PageDeleted`, `PageMoved`, `TemplateChanged` (line ~65). |
| `app/src/main/kotlin/com/notesprout/android/data/ObjectData.kt` | `PageData(width,height,template,snapshot)`. Template lives in the page row's `data` JSON. |

How the index talks to NotebookActivity today (must be preserved/extended): the index records
session actions (`pastedActions`, `deletedActions`, `movedActions`) and returns them as
**comma-joined string extras** in `finishWithResult(...)` (line ~801). `NotebookActivity`'s
`pageIndexLauncher` (line ~445) splits them back out and pushes one `UndoRedoAction` per item, then
reloads. **Any new batch action must use this same list-of-extras pattern** so undo/redo keeps
working.

### Current selection model (what we are replacing)

- `actionModePageIndex: Int?` — a single 0-based index. `null` = normal mode.
- This single index doubles as both the **action subject** AND the **paste/move destination target**
  (tapping another card in action mode moves the paste target; move mode taps pick the move
  destination).
- Cards are highlighted via `bg_page_card_current` when `pageIndex == actionModePageIndex` (or the
  open page in normal mode). Border padding is 3dp when highlighted, 1dp otherwise (`buildCardGroup`,
  line ~434).

### Target selection model (what we are building)

- **`selectedPageIds: LinkedHashSet<String>`** — selection by **page UUID**, not index. IDs survive
  pagination and reorder/delete reshuffles; insertion order is preserved (matters for paste/move
  block ordering). Empty set = normal mode; non-empty = action mode.
- **Destination** for move/paste is a separate concept (a single target page ID + a before/after
  flag), handled by a dedicated "destination-picking" mode — see Session 2.
- Highlight any card whose `entry.id ∈ selectedPageIds`. In normal mode (empty selection) keep
  highlighting the open page only.

---

## Session 1 — Selection model, Select All, multi-Delete

**Status:** ✅ DONE (2026-06-19)

**Goal:** Replace the single-index action mode with a multi-page selection set, add a Select
All / Deselect All control, show a live count, and convert **Delete** to operate on every selected
page. Copy / Paste / Move / Export remain functional for the **single-selection** case and are
**disabled when more than one page is selected** (later sessions enable each for multi). No new
toolbar action yet (Template arrives in Session 3).

### 1.1 Selection state (`PageIndexActivity.kt`)

- Remove `private var actionModePageIndex: Int? = null`.
- Add `private val selectedPageIds = LinkedHashSet<String>()`.
- Add helpers:
  - `private fun inActionMode(): Boolean = selectedPageIds.isNotEmpty()`
  - `private fun selectedCount(): Int = selectedPageIds.size`
- Everywhere the old code read `actionModePageIndex != null`, use `inActionMode()`.
- `buildCardGroup` (line ~434): compute `isSelected = entry.id in selectedPageIds`. Highlight logic:
  - move/destination mode → highlight the source pages (selection) — see Session 2; for now in move
    mode highlight `selectedPageIds`.
  - action mode → highlight all `selectedPageIds`.
  - normal mode → highlight the open page (`pageIndex == currentPageIndex`).

### 1.2 Tap / long-press behavior (`buildCardGroup` click handlers)

- **Long-press** any card: if not already in action mode, enter action mode and add this page's ID to
  the selection. If already in action mode, toggle this page (same as tap). Always `renderGridPage()`
  and refresh the toolbar/title.
- **Tap** in normal mode (empty selection): navigate to that page → `finishWithResult(pageIndex)`
  (unchanged).
- **Tap** in action mode: **toggle** this page's ID in `selectedPageIds`. If the set becomes empty,
  exit action mode. Re-render + refresh toolbar/title. (Do **not** navigate on tap while in action
  mode.)
- Move/destination-mode taps are handled in Session 2 — keep the existing single-move path working
  for now by treating the (single) selected page as the move source.

### 1.3 Action-mode chrome: title count + toolbar visibility

- Replace `enterActionMode(pageIndex)` / `exitActionMode()` with `refreshActionMode()` that:
  - Shows/hides the action buttons based on `inActionMode()`.
  - Sets `binding.tvTopBarTitle.text` to `"N selected"` (or `"Pages"` when not in action mode; keep
    `"Move to…"` while in move/destination mode — Session 2).
  - Enables/disables single-only buttons: when `selectedCount() > 1`, disable (greyed)
    `btnCopyPage`, `btnPastePage`, `btnMovePage`, `btnExportPage`; enable them when
    `selectedCount() == 1` (preserving today's behavior). `btnDeletePage` is always enabled in action
    mode. (Disable = `isEnabled = false`; the existing `ToolbarButton` style should dim via alpha —
    if not, set `alpha = 0.4f` when disabled, `1f` when enabled, matching `inkLight` intent.)
  - `btnPastePage` additionally requires a non-empty clipboard (`pendingCopyPageIds`, Session 2) —
    for Session 1 keep the existing single `pendingCopyPageId` gate.
- Delete the old `enterActionMode`/`exitActionMode` bodies; provide `exitActionMode()` that clears
  `selectedPageIds`, restores `"Pages"` title, hides buttons, and re-renders.

### 1.4 Select All / Deselect All control

- Add a top-bar button **`btnSelectAll`** to `activity_page_index.xml` (style
  `@style/Widget.Notesprout.ToolbarButton`), placed at the **left of the action-button cluster**
  (after the title spacer, before `btnCopyPage`). Use an existing icon if one fits (check
  `res/drawable/` for a select-all/checklist glyph); **if none exists, create a minimal e-ink vector
  `ic_select_all.xml`** (1dp strokes, inkBlack, 24dp) — a simple "stacked pages with check" or
  "select rectangle" motif. Keep it flat, no fill gradients.
- Visible only in action mode. Tapping it:
  - If not all pages are selected → add **every** `pages[i].id` to `selectedPageIds` (this is the
    "select all pages in the notebook" requirement; `pages` already holds the full notebook).
  - If all are already selected → clear back to empty? **No** — clearing would exit action mode.
    Instead toggle to "deselect all but keep action mode" is awkward; simplest correct behavior:
    when all selected, tapping **deselects all and exits action mode**. Document this in a code
    comment. (Alternative considered: keep one selected — rejected as surprising.)
  - Update content description dynamically ("Select all" / "Deselect all") — optional polish.

### 1.5 Multi-Delete

- Rewrite `executeDelete()` to operate on `selectedPageIds`:
  - **Guard:** the notebook must retain ≥1 page. If `selectedPageIds.size >= pages.size`, show toast
    `"Cannot delete all pages"` and return.
  - Confirmation dialog message: `"Delete N pages?"` (or `"Delete Page X?"` when exactly one, to
    preserve the nicer single-page wording — use the page number for the single case).
  - On confirm, iterate the selected IDs (snapshot the set first). For each, call
    `deletePageRaw(pageId, path)` on `Dispatchers.IO`; collect `(pageId, originalIndex, deletedAt)`
    into `deletedActions`. Compute `originalIndex` from the `pages` list **before** reloading.
  - **Order matters for index bookkeeping:** capture each page's pre-delete index from the current
    `pages` list up front (build a `Map<String,Int>` of id→index), so all recorded indices are
    consistent with the snapshot NotebookActivity will undo against. Delete in any order; the raw op
    is by ID.
  - Recompute `currentPageIndex` by stable `currentPageId` after reload (mirror the move path,
    line ~622): reload `pages`, then `pages.indexOfFirst { it.id == currentPageId }`; if the open
    page was itself deleted, clamp to a surviving page.
  - Clear `selectedPageIds`, clamp `currentGridPage`, `exitActionMode()`.
- The existing `deletedActions` list + `finishWithResult` extras already support multiple deletes.
  **Correction (impl):** the original loop pushed one `PageDeleted` per page = N separate undo
  steps, so a single undo restored only one page. Fixed by adding `UndoRedoAction.PagesDeleted`
  (a batch holding `List<DeletedPageRef(pageId, pageIndex, deletedAt)>`, mirroring `LassoDeleted`);
  the launcher now pushes **one** `PagesDeleted` per index-session batch, and the executor restores
  / re-deletes the whole batch as a single undo/redo step. Extras format on the index side unchanged.

### 1.5b Selection hygiene after operations

- After any operation that reloads `pages`, prune `selectedPageIds` to only IDs still present
  (`selectedPageIds.retainAll(pages.map { it.id }.toSet())`). For delete we clear it entirely, but
  add this helper (`pruneSelection()`) now since Sessions 2–4 rely on it.

### 1.6 Acceptance (Session 1)

- Long-press selects a page; tapping more cards adds/removes them; count shows in the title.
- Selection persists when paginating with swipe / first / prev / next / last.
- Select All selects every page in the notebook (verify the count equals total pages, including
  pages on other grid pages); tapping again clears + exits.
- Delete removes all selected pages in one confirm; cannot delete the last remaining page(s); undo in
  NotebookActivity restores them.
- With >1 selected, Copy/Paste/Move/Export are visibly disabled; with exactly 1 selected they behave
  exactly as before.

**→ Haiku: clean build + install on G10. Fix-loop (Sonnet fix → Haiku rebuild) until user passes.
Then update Status above and commit (no push).**

---

## Session 2 — Multi Copy / Paste & Move, with before/after toggle

**Status:** ✅ DONE (2026-06-19)

**Goal:** Enable Copy/Paste and Move for any number of selected pages, inserting them as a
contiguous block, and add a **before/after** toggle the user sets when choosing the destination.
Remove the single-only disable for these three buttons.

### 2.1 Clipboard for multiple pages

- Replace `pendingCopyPageId: String?` with `pendingCopyPageIds: List<String>` (default empty).
- `copySelectedPage()` → `copySelectedPages()`: set `pendingCopyPageIds = selectedPageIds.toList()`
  (preserves selection/insertion order). Enable `btnPastePage` when non-empty. Stay in action mode
  (matches current UX). Show a toast `"N pages copied"` for feedback (optional, keep terse).

### 2.2 Destination-picking mode (unify move + paste)

Today move has its own mode (`isMoveMode`) and paste reuses the selected card as target. Replace both
with one explicit destination-picking mode so paste and move share the before/after toggle.

- Add `private enum class DestMode { NONE, MOVE, PASTE }` and `private var destMode = DestMode.NONE`.
- Add `private var insertBefore: Boolean = false` (default = after, preserving current semantics).
- Entering **Move**: from action mode, `btnMovePage` → `destMode = MOVE`; stash the move sources =
  `selectedPageIds.toList()` into `moveSourceIds`. Title `"Move before/after…"` (or just `"Move to…"`
  with the toggle visible). Hide action buttons, show the **before/after toggle** + back/cancel.
- Entering **Paste**: `btnPastePage` → `destMode = PASTE`. Title `"Paste before/after…"`. Show the
  toggle. (Clipboard = `pendingCopyPageIds`.)
- A **tap on a card** while `destMode != NONE` is the **destination**:
  - For MOVE: destination must not be one of `moveSourceIds`. If it is, ignore the tap (optionally
    toast `"Pick a page outside the selection"`).
  - For PASTE: any card is a valid destination (copies are new rows).
  - Execute the batch op (2.4), then return to normal mode (clear selection, `destMode = NONE`).
- **Back / system-back** while in destination mode cancels back to action mode (mirror the existing
  `cancelMoveMode`). Update `onBackPressedDispatcher` + `btnBack` `when` blocks (lines ~200, ~215) to
  branch on `destMode != NONE` first, then `inActionMode()`, then finish.

### 2.3 Before/after toggle UI (`activity_page_index.xml`)

- Add a top-bar toggle button **`btnBeforeAfter`** (hidden by default, shown only in destination
  mode). Use the existing `ic_insert_page_before.xml` / `ic_insert_page_after.xml` drawables: the
  button shows `ic_insert_page_after` when `insertBefore == false` and `ic_insert_page_before` when
  `true`. Tapping flips `insertBefore` and swaps the icon + content description ("Insert after" /
  "Insert before"). Default state = after.
- Keep it to the e-ink rules: 1dp icons, no ripple. Reuse `Widget.Notesprout.ToolbarButton`.

### 2.4 Batch data-layer ops (`PageCopier.kt`)

Add two new raw functions (mirror the existing `*Raw` structure: open RW, transaction,
`checkpointAndVacuum()`, `cleanStrayJournal`, return null on failure):

1. **`movePagesRelativeRaw(pageIds: List<String>, targetPageId: String, before: Boolean, notebookPath: String): List<Triple<String,String?,String>>?`**
   - Reorder so the moved pages form a contiguous block immediately **before** or **after**
     `targetPageId`, preserving the **order of `pageIds`** (which is selection/insertion order).
   - Build the new order: take the full sorted page-id list, remove all `pageIds`, find the target's
     index in the remaining list, splice the block in before/after it, then rewrite `order` for all.
   - For **undo**, return one `Triple(pageId, previousAfterPageId, targetPageId)` per moved page,
     compatible with `UndoRedoAction.PageMoved` and the existing `movedActions` extras. Compute each
     `previousAfterPageId` from the **original** order (the page that was immediately before it, "" if
     it was first). Set `targetPageId` per entry so undo/redo can reconstruct — reuse the existing
     single-move semantics (undo moves each back after its `previousAfterPageId`). **Verify the
     existing `PageMoved` undo handles a batch correctly when replayed in reverse**; if ordering
     conflicts arise, record the block as moves relative to siblings (document the chosen scheme in a
     code comment). Keep the simplest scheme that round-trips: undo iterates the batch in reverse.
2. **`copyPagesRelativeRaw(sourcePageIds: List<String>, targetPageId: String, before: Boolean, notebookPath: String): List<Pair<String,Int>>?`**
   - Deep-copy each source page (reuse the per-page copy body from `copyPageAfterRaw`: page row +
     non-deleted layer + non-deleted children, fresh UUIDs, `data` preserved verbatim).
   - Insert the copies as a contiguous block before/after the target, in `sourcePageIds` order.
   - Return `List<Pair<newPageId, newPageIndex>>` (post-insert indices) compatible with
     `pastedActions` / `UndoRedoAction.PagePasted`.
   - Do the order-shift + inserts in a single transaction.

Keep the old `copyPageAfterRaw` / `movePageAfterRaw` for now (other callers?) — grep to confirm; if
the index was the only caller, you may route the single case through the new functions and delete the
old ones (your choice; note it in the commit).

### 2.5 Wire executeMove / executePaste

- `executeMoveAfter(destinationIndex)` → `executeMove(targetPageId)`: call
  `movePagesRelativeRaw(moveSourceIds, targetPageId, insertBefore, path)`; on success append all
  returned triples to `movedActions`, reload pages, recompute `currentPageIndex` by stable id, clear
  selection, `destMode = NONE`, `exitActionMode()`.
- `executePaste()` → use `copyPagesRelativeRaw(pendingCopyPageIds, targetPageId, insertBefore, path)`;
  append returned pairs to `pastedActions`; reload; adjust `currentPageIndex` (count inserted pages
  whose index ≤ currentPageIndex); clear selection + clipboard; `destMode = NONE`; `exitActionMode()`.
- Remove the `selectedCount() > 1` disable for Copy/Paste/Move in `refreshActionMode()`.

### 2.6 NotebookActivity

- No new extras needed — paste and move already round-trip via the existing list extras. Confirm the
  `pageIndexLauncher` loop (lines ~451–487) handles N>1 entries (it already does). Just verify undo
  of a **multi-move** and **multi-paste** restores correctly end-to-end.

### 2.7 Acceptance (Session 2)

- Select several pages (across grid pages), Copy, choose a destination, toggle before/after → all
  copies land as a block in the chosen spot and order.
- Same for Move; sources can't be their own destination; relative order preserved.
- before/after default is "after"; toggling flips placement.
- Undo/redo in NotebookActivity reverses a multi-paste and a multi-move cleanly.

**→ Haiku: clean build + install on G10. Fix-loop until pass. Update Status. Commit (no push).**

---

## Session 3 — Multi "Set Template" toolbar action

**Status:** ✅ DONE (2026-06-19)

**Goal:** Add a new toolbar action that sets the template of all selected pages via the template
picker, with full undo.

### 3.1 Toolbar button (`activity_page_index.xml`)

- Add **`btnSetTemplate`** to the action-button cluster, using `ic_template.xml` (already exists).
  Visible in action mode, enabled for any selection count (1..N). Content description "Set template".
  Wire `binding.btnSetTemplate.setOnClickListener { chooseTemplateForSelection() }`.
- Add show/hide to `refreshActionMode()` and to the destination-mode hide block.

### 3.2 Launch the picker (`PageIndexActivity.kt`)

- Register `templatePickerLauncher` (`StartActivityForResult`). `chooseTemplateForSelection()`
  snapshots `selectedPageIds.toList()` into a field (`pendingTemplateTargets`) and launches
  `TemplateBrowserActivity` in `MODE_PICK`:
  ```kotlin
  Intent(this, TemplateBrowserActivity::class.java)
    .putExtra(TemplateBrowserActivity.EXTRA_MODE, TemplateBrowserActivity.MODE_PICK)
    .putExtra(TemplateBrowserActivity.EXTRA_TITLE, "Set template")
  ```
  (Mirror NotebookActivity's MODE_PICK launch at line ~793. The picker's "Blank" tile returns
  `RESULT_TEMPLATE_ID = ""`, which clears the template — handle that as a valid choice.)

### 3.3 Apply to all selected

- On `RESULT_OK`, read `RESULT_TEMPLATE_ID` (may be `""` = blank/clear). For each id in
  `pendingTemplateTargets`, on `Dispatchers.IO` write the page's template into its `data` JSON. Add a
  raw helper in `PageCopier.kt` (no Room instance available in the index):
  - **`setPageTemplateRaw(pageId: String, templateId: String, notebookPath: String): String?`** —
    reads the page row's `data`, parses via `PageData.fromJson`, `.copy(template = templateId)`,
    writes back with `updateData`-equivalent SQL (`UPDATE notebook SET data=?, updatedAt=? WHERE
    id=?`), `checkpointAndVacuum`. **Returns the previous template id** (for undo), or null on
    failure. Do all N in one DB connection/transaction for efficiency — consider a batch variant
    `setPagesTemplateRaw(pageIds, templateId, path): List<Pair<String,String?>>?` returning
    `(pageId, previousTemplateId)` pairs.
- Record undo data: collect `(pageId, previousTemplateId, newTemplateId)` per page into a new
  `templateChanges` list (mirror `movedActions`).

### 3.4 Return + undo wiring (NotebookActivity)

- Add three extras to `PageIndexActivity.companion` + `finishWithResult`:
  - `EXTRA_TEMPLATE_PAGE_IDS`, `EXTRA_TEMPLATE_PREV_IDS`, `EXTRA_TEMPLATE_NEW_IDS` (comma-joined;
    empty string encodes a null/blank template id, same convention as `EXTRA_MOVED_PREV_AFTER_IDS`).
- In `pageIndexLauncher` (NotebookActivity ~line 487), parse them and push one
  `UndoRedoAction.TemplateChanged(pageId, previousTemplateId, newTemplateId)` per page (the type
  already exists, line ~65; `null` for empty strings). Include template changes in the
  `anySessionActions` flag so the canvas reloads (line ~489) — if one of the changed pages is the
  open page, its template must refresh on screen.
- **Important:** the open page in NotebookActivity caches `currentTemplateBitmap`. If a changed page
  is the currently-open one, the reload path (`navigateToPage(currentPageIndex)`) must re-read and
  re-apply its template. Verify `navigateToPage` reloads the template from the page row (it should,
  since it rebuilds the page); if not, force a template refresh for the open page.

### 3.5 Acceptance (Session 3)

- Select pages, tap Set Template, pick a template → all selected pages get it (verify via reopening
  each / snapshots after next edit). Picking "Blank" clears the template on all selected.
- If the open page was among them, its template updates on return to the canvas.
- Undo reverses each page's template change; redo re-applies.

**Corrections (impl):**
- **Template id indirection.** §3.3 assumed the picker's returned id could be written straight into
  `PageData.template`. In reality a page's `template` field stores a **`.soil` `type="template"`
  row id**, while the picker returns a **global-index library id** (NotebookActivity bridges this via
  `insertLibraryTemplateIntoSoil`). The index now replicates that: `PageCopier.insertSoilTemplateRaw`
  copies the library image into one shared `.soil` template row per Set-Template op, then
  `setPagesTemplateRaw` points every selected page at it. `readNotebookRowId` supplies the template
  row's parentId. Undo/redo round-trips through these soil-row ids (which `TemplateChanged` already
  expects). Blank (`""`) clears the template.
- **Batch undo.** Pushing one `TemplateChanged` per page made a single undo revert only the last page
  (same defect Session 1 hit with delete). Fixed by adding `UndoRedoAction.TemplatesChanged`
  (a batch of `TemplateChangeRef`, mirroring `PagesDeleted`/`PagesMoved`); the launcher pushes **one**
  per index session and the handler reverts/re-applies the whole group atomically. Extras format on
  the index side unchanged.

**→ Haiku: clean build + install on G10. Fix-loop until pass. Update Status. Commit (no push).**

---

## Session 4 — Multi Export (PDF combined / PNG per-page / PNG as templates)

**Status:** ✅ DONE (2026-06-19)

**Goal:** Export every selected page. User chooses **PDF** (all selected pages in one PDF) or **PNG**
(one file per page). For PNG, also offer **export as templates**. Never prompt for individual
filenames when exporting multiple PNGs — auto-name from notebook + page name/number.

### 4.1 Export choice dialog (`PageIndexActivity.kt`)

- `executeExport()` now applies to `selectedPageIds` (ordered by page order, not selection order — sort
  the selected ids by their index in `pages`). Show an AlertDialog (e-ink styled: `setElevation(0f)`,
  `shape_bordered` background, as existing dialogs do) titled **"Export N pages"** with choices:
  - **PDF** → 4.2
  - **PNG** → 4.3 (which then asks: Save images / Save as templates)
  - Cancel
- For a **single** selected page, you may keep the richer existing single-page flow
  (`showExportChoice`: Save to device / Save as Template / Share) — but it's cleaner to route single
  and multi through the same dialog. Decide and note it; the requirement only constrains the
  *multiple* case. Recommended: if `selectedCount() == 1`, keep today's `showExportChoice`; if >1, use
  the new multi dialog.

### 4.2 PDF — all selected pages in one file (`NotebookExporter.kt`)

- Add **`exportPagesPdf(context, soilPath, pageIds: List<String>, notebookTitle, onProgress): File`**.
  - Refactor: extract the per-page render block shared by `export` and `exportPage` (the
    headings/text/lines/links/strokes load + `renderPage`) into a private
    `renderPageBitmap(dao, pageRow, context): Bitmap` to avoid a third copy of that logic.
  - Build a `PdfDocument`, one page per id in the given order (no cover for a selection export), using
    each page's own `boundingBox` dimensions. Write to `cacheDir/exported_pdfs/<safeTitle>.pdf`
    (reuse the dir-clean pattern, line ~164).
  - Opens a transient Room instance for `soilPath` (mirror `exportPage`, line ~381; do **not**
    checkpoint on close since NotebookActivity holds the canonical connection).
- In the index, after building the PDF, offer **Save to device** (use a
  `CreateDocument("application/pdf")` launcher, mirror `savePngLauncher`) and **Share** (FileProvider,
  `application/pdf`). Show the "Exporting…" progress dialog while rendering (reuse existing pattern,
  line ~722).

### 4.3 PNG — one file per page (`NotebookExporter.kt` + index)

- Add **`exportPagesPng(context, soilPath, pages: List<Pair<String,String>> /* (pageId, filenameBase) */, notebookTitle): List<File>`**
  rendering each to its own PNG in `cacheDir/exported_pngs/` (reuse `exportPage`'s body via the shared
  `renderPageBitmap`). Filenames: `"<safeNotebook>_<pageLabel>.png"` where `pageLabel` is the page's
  **heading name if present, else "PageN"** (the index already has `headingName`/`pageNumber` in
  `PageEntry`). Sanitize with the existing regex; de-duplicate collisions by appending `_2`, `_3`, …
- **Destination:** since multiple files must be written without per-file prompts, pick a folder once
  with `ActivityResultContracts.OpenDocumentTree`. Then write each cached PNG into the tree via
  `DocumentFile.fromTreeUri(...).createFile("image/png", name)` + copy stream (background IO). Show a
  brief progress + final toast `"Exported N images"`. (If a tree picker proves heavy on BOOX, the
  fallback is sequential `CreateDocument` calls — but that prompts per file; avoid. Tree picker is the
  intended path.)

### 4.4 PNG as templates

- In the PNG branch, second-level choice: **Save images** (4.3) vs **Save as templates**.
- Save-as-templates: render each selected page to a PNG (cache), then import each into the template
  library directly via `IndexRepository.createTemplate(name, parentId=null, width, height, base64)`
  (signature at `IndexRepository.kt:111`; see `TemplateBrowserActivity.importSaveTarget` body around
  line ~648 for the decode-bounds + base64 pattern). Use the page label as the template name
  (sanitized, de-duped). `parentId = null` imports into the template root — or, optionally, prompt
  once for a destination folder by launching `TemplateBrowserActivity` in `MODE_SAVE_TARGET` per file
  (heavy for N files; prefer root import for batch, note as a Phase-2 polish to choose a folder).
- Confirm `IndexRepository` is reachable from `PageIndexActivity` (it operates on the **global index**
  `notesprout.db`, not the `.soil` — instantiate the same way MainActivity/TemplateBrowser do; grep
  for `IndexRepository(` construction). Do this work on `Dispatchers.IO`.

### 4.5 Acceptance (Session 4)

- Multi-select → Export → PDF: one PDF containing exactly the selected pages, in page order; save +
  share both work.
- Export → PNG → Save images: N PNGs written to the chosen folder, auto-named from notebook + heading
  or PageN, no per-file prompts, names de-duplicated.
- Export → PNG → Save as templates: N templates appear in the template library with sensible names.
- Single-selection export still offers the existing Save/Template/Share flow (if that route was
  kept).

**Corrections (impl):**
- **`DocumentsContract` instead of `DocumentFile`.** §4.3 specified
  `DocumentFile.fromTreeUri(...).createFile(...)` for writing PNGs into the chosen tree.
  `androidx.documentfile` is not an explicit Gradle dependency (only transitive via appcompat), so to
  honor CLAUDE.md's "no new Gradle dependencies" the PNG batch writer uses
  `DocumentsContract.createDocument(...)` directly (platform API, minSdk 29) — same result, zero new
  deps. The folder is still picked once via `OpenDocumentTree`.
- **Single vs. multi routing.** Per §4.1's recommendation, single-selection keeps the richer existing
  `showExportChoice` (Save / Template / Share); only `selectedCount() > 1` uses the new multi dialog
  (PDF / PNG / Cancel → PNG sub-choice: Save images / Save as templates).
- **Shared render path.** The per-page load+render block in `export` and `exportPage` was extracted
  into a private `renderPageBitmap(...)` (per §4.2) so the new `exportPagesPdf` / `exportPagesPng`
  reuse it rather than adding a third copy.

**→ Haiku: clean build + install on G10. Fix-loop until pass. Update Status. Commit (no push).**

---

## Session 5 — Wrap-up: docs, polish, edge cases

**Status:** ✅ DONE (2026-06-19)

**Wrap-up notes (impl):**
- **Docs.** Replaced the stale single-page "Page Export (PNG)" section in
  `docs/mainactivity-and-recents.md` with a full **Page Index — Multi-Page Selection** section
  (selection model, action-mode toolbar, Copy/Paste & Move with Before/After, Set Template,
  single-vs-multi Export, and the undo/redo extras round-trip). Added the batch raw-op names to the
  `PageCopier.kt` line in `docs/data-architecture.md`. No `CLAUDE.md` guardrail changed.
- **Dead-code prune.** `copyPageAfterRaw` / `movePageAfterRaw` in `data/PageCopier.kt` had no callers
  left (the index routes everything through `copyPagesRelativeRaw` / `movePagesRelativeRaw`); removed
  both (~190 lines), as §2.4 anticipated.
- **Hygiene pass.** Confirmed no `Log.d` (only `Log.e`/`Log.w` + `Slog.d`), no `runBlocking`, all
  heavy work on `Dispatchers.IO` behind a progress dialog, and per-page bitmap `recycle()` in every
  `NotebookExporter` path. Disabled buttons dim via `alpha 0.4f` (visible on e-ink).
- **Behavior note.** The Before/After control shipped (Session 2) as two explicit selectable buttons
  with **Before** selected by default — superseding the original plan's single "after" toggle. Docs
  reflect the as-built behavior.

**Goal:** Tighten the feature, document it, and confirm the whole flow.

### 5.1 Docs

- Update **`docs/mainactivity-and-recents.md`** (or whichever doc owns the page index — confirm; the
  index UI may warrant a short section there or in `docs/toolbar.md`). Document: selection model,
  Select All, before/after toggle, Set Template, multi-export (PDF/PNG/templates).
- Update **`CLAUDE.md`** only if a guardrail changed (likely not). Add a one-line growth-log entry
  style note if the project keeps one.
- If `docs/data-architecture.md` describes the page ops, add the new batch raw functions.

### 5.2 Polish / edge cases (review + fix any that fail)

- Empty selection edge: every action no-ops gracefully; toolbar hidden.
- Select All when notebook has 1 page; delete guard with all-but-one selected.
- Selection pruning after each op (`pruneSelection()`); selection cleared after destructive/move/paste
  ops; preserved after a no-op cancel.
- Pagination math (`totalGridPages`, `currentGridPage` clamp) after multi-delete that empties the last
  grid page.
- before/after toggle resets to default ("after") each time destination mode is entered.
- Disabled-button visuals are visible on e-ink (alpha/inkLight), not relying on color.
- Large selection (e.g. 50+ pages) export doesn't ANR — all heavy work on `Dispatchers.IO`, progress
  dialog shown; PDF of many pages stays within memory (recycle bitmaps per page — `export` already
  does, keep that in the shared `renderPageBitmap` callers).
- No `Log.d` (use `Slog.d`); no `runBlocking` on UI; `kotlinx.serialization` only.

### 5.3 Final pass

- Run `/code-review` (or a Sonnet review subagent) over the full diff for the feature branch; fix
  high-confidence findings.
- Confirm all four feature areas once more on G10.

**→ Haiku: final clean build + install on G10. Fix-loop until pass. Update all Statuses to DONE.
Commit (no push). Summarize the Phase 2 backlog for the user.**

---

## Phase 2 — Session P2.1 — Confirm step for Copy & Move destinations

**Status:** ✅ DONE (2026-06-19)

> **Goal:** Insert an explicit **OK / Cancel** confirmation between picking a move/paste destination
> and committing it. Today a destination tap commits **immediately** (`executeMove` / `executePaste`
> are called straight from the card click). After this session, a destination tap instead **selects
> and previews** the landing spot; the op only runs when the user taps **OK**. The before/after
> toggle and destination tap stay; the user can re-pick the destination or flip before/after and watch
> the preview update before committing. **Cancel** (back gesture / cancel) is already wired via
> `cancelDestMode()` and returns to action mode with the selection intact.
>
> **Slogan check:** no color, no Material chrome. The "where will this land" affordance is a single
> bold inkBlack insertion bar drawn on the leading/trailing edge of the destination card — paper-like,
> readable on e-ink (`borderGray` is invisible on BOOX → use inkBlack, per memory + design-system.md).

### Architecture recap (read before implementing)

The destination flow lives entirely in `PageIndexActivity.kt`:

- `enterDestMode(mode)` (line ~747) — enters MOVE/PASTE picking; resets `insertBefore = true`, stashes
  `moveSourceIds`, hides action buttons, shows the Before/After selectable buttons, sets the title.
- The **card click handler** (`buildCardGroup`, line ~544) is where a destination tap currently
  **commits**: `destMode == MOVE` → `executeMove(entry.id)` (unless the tap is a source → cancel);
  `destMode == PASTE` → `executePaste(entry.id)`.
- The **card highlight** (line ~577) highlights sources/selection; there is no destination highlight
  today (the op is instant, so none was needed).
- `executeMove(targetPageId)` (line ~806) / `executePaste(targetPageId)` (line ~841) do the batch op
  on `Dispatchers.IO`, record undo batches, reload, then `exitActionMode()`.
- `cancelDestMode()` (line ~791) clears `destMode` + `moveSourceIds`, hides Before/After, restores
  action chrome.
- `refreshInsertBeforeAfter()` (line ~785) reflects `insertBefore` on the two toggle buttons.
- Back handling: `onBackPressedDispatcher` (line ~318) and `btnBack` (line ~299) already branch
  `destMode != DestMode.NONE -> cancelDestMode()` first.

Layout: `res/layout/activity_page_index.xml`. The destination-mode top bar shows `btnInsertBefore` /
`btnInsertAfter` (lines 102–114, `Widget.Notesprout.ToggleTextButton`). There is **no confirm button
yet** and **no `ic_check` drawable** (confirmed: `res/drawable/` has no check/done/confirm glyph).

### P2.1.1 New state: pending destination

- Add `private var pendingDestPageId: String? = null` — the destination the user has tapped but not
  yet confirmed. `null` = no destination chosen yet (still picking).
- A "destination is chosen" sub-state of dest mode is simply `pendingDestPageId != null`. Clear it
  back to `null` in `enterDestMode()` and `cancelDestMode()`.

### P2.1.2 Confirm button (`activity_page_index.xml` + drawable)

- Create a minimal e-ink vector **`res/drawable/ic_check.xml`** (24dp, 1dp/2dp inkBlack stroke, no
  fill, no gradient) — a plain checkmark. Match the stroke weight of the existing
  `ic_insert_page_after.xml` so it sits visually with its neighbours.
- Add **`btnConfirmDest`** (`AppCompatImageButton`, `style="@style/Widget.Notesprout.ToolbarButton"`,
  `src="@drawable/ic_check"`, `contentDescription="Confirm"`, `visibility="gone"`) to the top bar,
  placed **after `btnInsertAfter`** (so the destination-mode cluster reads: `[Before] [After]
  [✓]`). Keep `layout_marginEnd="4dp"`.
- Wire in the click-listener setup block (near line ~308–312):
  `binding.btnConfirmDest.setOnClickListener { confirmDestination() }`.

### P2.1.3 Destination tap = select + preview (not commit)

Rewrite the card click handler (`buildCardGroup`, lines ~544–558) for the dest-mode branches:

- `destMode == DestMode.MOVE`:
  - Tapped card **is a source** (`entry.id in moveSourceIds`): keep current behavior — treat as
    "cancel the pick". **Refinement:** if a destination is already pending, clear only the pending
    destination (`pendingDestPageId = null; refreshDestChrome(); renderGridPage()`) rather than
    exiting dest mode; if none is pending, `cancelDestMode()` as today. (Document the choice in a
    comment.)
  - Otherwise: **set** `pendingDestPageId = entry.id` (do **not** call `executeMove`). Then
    `refreshDestChrome()` + `renderGridPage()` to show the preview + reveal OK.
- `destMode == DestMode.PASTE`: any card is valid → `pendingDestPageId = entry.id`,
  `refreshDestChrome()` + `renderGridPage()` (no immediate `executePaste`).
- Normal/action-mode branches (`!inActionMode()` / `toggleSelection`) are unchanged.

### P2.1.4 Commit on OK

- Add `private fun confirmDestination()`:
  - Guard: `val target = pendingDestPageId ?: return` (OK is only enabled when a destination exists —
    see P2.1.6, but guard anyway).
  - `when (destMode) { MOVE -> executeMove(target); PASTE -> executePaste(target); NONE -> {} }`.
  - `executeMove` / `executePaste` already clear state and `exitActionMode()` on success, so no extra
    teardown here. `pendingDestPageId` is reset by `exitActionMode()` (P2.1.5) — confirm that path
    nulls it, or null it explicitly at the top of both execute fns.
- **No change to the batch ops, undo/redo, or extras** — `executeMove`/`executePaste` and
  `PageCopier` are untouched. This session is purely the gate in front of them.

### P2.1.5 Chrome refresh (`refreshDestChrome()` + teardown)

- Add `private fun refreshDestChrome()` (called from `enterDestMode`, dest taps, and
  `refreshInsertBeforeAfter` flips):
  - `btnConfirmDest.visibility = if (destMode != NONE && pendingDestPageId != null) VISIBLE else GONE`.
  - Title: when `pendingDestPageId == null` keep the existing `"$titlePrefix before/after…"`; when a
    destination is chosen, set a confirming title, e.g. `"Move ${if (insertBefore) "before" else
    "after"} p.${destPageNumber}?"` (derive the page number from `pages.indexOfFirst { it.id ==
    pendingDestPageId } + 1`; fall back to the bare verb if not found). Keep it terse.
- `enterDestMode()` (line ~747): set `pendingDestPageId = null` and add `binding.btnConfirmDest`
  to the GONE block; call `refreshDestChrome()` after showing Before/After.
- `cancelDestMode()` (line ~791): set `pendingDestPageId = null`, hide `btnConfirmDest`.
- `exitActionMode()`: ensure it nulls `pendingDestPageId` and hides `btnConfirmDest` (it already hides
  the dest cluster on the normal→action restore; add the confirm button to that path).
- `refreshInsertBeforeAfter()` (line ~785): after flipping, also call `refreshDestChrome()` so the
  title text and the preview side update when the user changes before/after **after** picking a
  destination. (Flipping must re-render the grid — add `renderGridPage()` to the
  `btnInsertBefore`/`btnInsertAfter` click handlers, lines ~311–312, when `pendingDestPageId != null`.)

### P2.1.6 Preview affordance (`buildCardGroup` render, line ~577)

- The destination card (`entry.id == pendingDestPageId`) gets a distinct treatment from the source
  highlight so the two reads differently:
  - Keep sources highlighted as today (`bg_page_card_current`).
  - For the pending destination, draw a **bold insertion bar** (a `View`, `3dp` × card-height,
    `@color/inkBlack`) on the card's **leading edge if `insertBefore`** else **trailing edge**. The
    grid cards live in a wrapping row layout (`group` is a vertical `LinearLayout` inside the grid) —
    add the bar as a sibling on the correct side within the card's container, or overlay it in the
    card `FrameLayout` with `Gravity.START` / `Gravity.END` and a matching height. Prefer the overlay
    inside the existing card `FrameLayout` (line ~582) to avoid disturbing grid measurement.
  - Optionally also outline the destination card itself (e.g. reuse `bg_page_card_current`) so it's
    obvious which card the bar attaches to — but the bar's side is what conveys before/after.
- Because before/after now has a visible consequence (the bar jumps sides), the earlier "reset
  insertBefore to true each time" still holds at `enterDestMode`; the preview just makes the current
  value legible.

### P2.1.7 Edge cases

- Re-tapping the **same** destination card: no-op beyond keeping it selected (or, optional polish,
  toggle it off back to "picking"). Keep it simple: re-set to the same id, re-render.
- Tapping a **different** card after one is pending: moves the preview to the new card (just reassign
  `pendingDestPageId`).
- MOVE: tapping a **source** card while a destination is pending clears the pending destination (back
  to picking), per P2.1.3 — it does not commit and does not exit.
- Back / cancel at any point (`destMode != NONE`) → `cancelDestMode()` → action mode, selection +
  clipboard intact (paste) / selection intact (move). No op runs.
- OK with `pendingDestPageId == null` is impossible (button hidden), but `confirmDestination()` guards
  anyway.
- Pagination while a destination is pending: the preview must survive paginating to another grid page
  and back (state is by id, and `renderGridPage()` re-derives the bar from `pendingDestPageId` — verify
  the bar redraws after `first/prev/next/last`).

### P2.1.8 Docs

- Update the **Page Index — Multi-Page Selection** section of `docs/mainactivity-and-recents.md`: the
  Copy/Move flow now reads *select → pick destination → (preview, adjust before/after) → OK*, with
  Cancel via back. Note the insertion-bar preview affordance.

### P2.1.9 Acceptance

- Copy several pages → pick a destination → an insertion bar shows the landing edge; flipping
  Before/After moves the bar; tapping a different card moves the preview; **OK** commits exactly as
  before; the result is identical to today's instant paste.
- Same for Move, including: tapping a source while a destination is pending returns to picking (no
  commit); a destination can't be a source on commit.
- Back / cancel at the picking **or** the confirming step returns to action mode with selection (and
  clipboard, for paste) intact and nothing changed.
- Undo/redo of a confirmed multi-move / multi-paste behaves exactly as in Phase 1 (no regression —
  this session does not touch the ops).
- Preview survives pagination; disabled/hidden chrome correct; no `Log.d`, no `runBlocking`, e-ink
  visuals (inkBlack bar, no color).

**Corrections (impl):**
- **"OK" text button, not a ✓ icon.** §P2.1.2 specified an `ic_check` checkmark `AppCompatImageButton`.
  As built, `btnConfirmDest` is an **"OK" text button** (`Widget.Notesprout.ToggleTextButton`, shown in
  its selected/bordered state) to read more clearly as the commit action and match the Before/After
  toggle pair. Given a `layout_marginStart="16dp"` so it sits apart: `[Before] [After]   [OK]`. The
  `ic_check.xml` drawable was created then removed (unused).
- **Insertion bar got an arrowhead + white triangle.** §P2.1.6's plain inkBlack bar was upgraded to a
  code-drawn `InsertionMarkerView`: full-height inkBlack bar on the destination edge **plus** a centred
  arrowhead with an inset **white triangle**, both pointing in the insertion direction (left = Before,
  right = After). Flipping Before/After flips the arrow with the bar's edge. No color, crisp on e-ink.

**→ Haiku: clean build + install on G10. Fix-loop (Sonnet fix → Haiku rebuild) until user passes.
Then update Status above and commit (no push).**

---

## Phase 2 — Session P2.2 — Choose template folder for batch "PNG as templates"

**Status:** ✅ DONE (2026-06-19)

> **Goal:** When the user exports a multi-page selection as **PNG → Save as templates**, let them
> **pick the destination folder** in the template library instead of always dumping every template
> into the library **root** (`parentId = null`, as Session 4.4 shipped). The single-page "Save as
> Template" flow (which already lets the user navigate folders via `MODE_SAVE_TARGET`) is the model;
> this session brings the same folder choice to the **batch** path, **once**, with no per-file
> prompts — matching the "never prompt for individual filenames when exporting multiple" rule from
> Session 4.
>
> **Slogan check:** reuses the existing folder-chooser chrome (`applySaveTargetUI` — "Choose a
> folder" / "Save Here" + New Template Folder). No color, no Material chrome. Calm, paper-like.

### Architecture recap (read before implementing)

- **Today's batch path** (`PageIndexActivity.exportMultiAsPngTemplates()`, line ~1406): renders each
  selected page to a cached PNG (progress dialog), then on `Dispatchers.IO` imports each into the
  library via `indexRepo.createTemplate(finalName, **null**, w, h, base64)` — hard-coded root
  `parentId = null`. Names de-duped against `indexRepo.getTemplates(**null**)`.
- **Single-page "Save as Template"** (`showExportChoice`, line ~1543) launches
  `TemplateBrowserActivity` in `MODE_SAVE_TARGET` (`saveTemplateLauncher`, line ~187): the browser
  shows a **folders-only** chooser (`applySaveTargetUI`, line ~574), and **Save Here**
  (`confirmSaveTarget`, line ~593) prompts for a name and imports the one PNG into `currentParentId`.
- `TemplateBrowserActivity` modes (companion, line ~70): `MODE_MANAGE=0`, `MODE_PICK=1`,
  `MODE_SAVE_TARGET=2`. `isSaveTarget` (line ~154) drives: extras read (~275), confirm/cancel
  branch (~317/320), `applySaveTargetUI()` (~335), and folders-only enforcement in `loadAndRender`
  (~868) + empty-state text (~974/975).
- `IndexRepository.createTemplate(name, parentId, w, h, base64)` and `getTemplates(parentId)` already
  take any folder id (the browser passes `currentParentId` everywhere). No repository change needed.

### P2.2.1 New browser mode: folder-only picker that **returns** the folder

`TemplateBrowserActivity` — add a mode that reuses the Save-target chrome but, instead of importing,
**returns the chosen folder id** to the caller (the batch importer does the actual `createTemplate`).

- Add `const val MODE_PICK_FOLDER = 3` and `const val RESULT_TEMPLATE_FOLDER_ID = "result_template_folder_id"`
  (value = chosen folder id; `""` encodes root/`null`, same empty-string-for-null convention used
  elsewhere).
- Introduce `private val isFolderChooser: Boolean get() = mode == MODE_SAVE_TARGET || mode == MODE_PICK_FOLDER`.
  Replace the **UI/navigation** `isSaveTarget` uses with `isFolderChooser`: `applySaveTargetUI()`
  gate (~335), folders-only in `loadAndRender` (~868), empty-state text (~974/975), and the
  **cancel** branch (~320 → `if (isFolderChooser) finish() else exitPicker()`). Keep `isSaveTarget`
  for the **import-only** branches: the extras read (~275, source PNG + default name) and the
  **confirm** branch.
- Confirm button (`btnPickerConfirm`, ~316): branch three ways —
  `if (isSaveTarget) confirmSaveTarget() else if (mode == MODE_PICK_FOLDER) returnPickedFolder() else confirmPickerDestination()`.
- `applySaveTargetUI()` already reads well for both ("Choose a folder" / "Save Here"); leave the
  copy as-is (an `EXTRA_TITLE` override still works for the top-bar title). Call it for
  `isFolderChooser`, not just `isSaveTarget` (the ~335 gate change covers this).
- Add `private fun returnPickedFolder()`:
  `setResult(RESULT_OK, Intent().putExtra(RESULT_TEMPLATE_FOLDER_ID, currentParentId ?: "")); finish()`.

### P2.2.2 Launch the folder picker from the batch path (`PageIndexActivity`)

- Snapshot fields for the deferred import (the picker is a separate activity; capture inputs up front
  so the import doesn't depend on live selection): `private var pendingTemplateEntries: List<PageEntry> = emptyList()`.
- New launcher `pickTemplateFolderLauncher` (`StartActivityForResult`): on `RESULT_OK`, read
  `RESULT_TEMPLATE_FOLDER_ID` (`""` → `null`) and call `renderAndImportTemplates(folderId)`; on
  cancel, clear `pendingTemplateEntries` and no-op (selection stays in action mode).
- Rewrite `exportMultiAsPngTemplates()` to **pick the folder first** (cheaper: cancel before any
  rendering), then render+import:
  - `pendingTemplateEntries = orderedSelectedEntries() ?: return`.
  - Launch `TemplateBrowserActivity` with `EXTRA_MODE = MODE_PICK_FOLDER`,
    `EXTRA_TITLE = "Save templates to…"`.
- Extract the existing render + import body into `private fun renderAndImportTemplates(parentId: String?)`,
  using `pendingTemplateEntries` (cleared at the top) and replacing the two hard-coded `null`s:
  `indexRepo.getTemplates(parentId)` for de-dup and `indexRepo.createTemplate(finalName, parentId, …)`.
  The progress dialog, sanitize/de-dup helpers, `exitActionMode()`, and the final toast are unchanged.

### P2.2.3 Acceptance

- Multi-select → Export → PNG → **Save as templates** → the folder chooser opens (folders only, "Save
  Here"); navigate into a subfolder (or create one with New Template Folder) and tap **Save Here** →
  all N templates land in **that** folder (verify in the template manager), names de-duped against
  that folder's existing templates.
- Choosing the **root** (Save Here at top level) behaves exactly as today (templates in root).
- **Cancel/back** in the chooser: nothing is imported; the page index stays in action mode with the
  selection intact.
- Single-page "Save as Template" (`MODE_SAVE_TARGET`) is unchanged — still prompts for a name and
  imports into the chosen folder.
- No `Log.d` (use `Slog.d`); no `runBlocking`; all rendering + DB work on `Dispatchers.IO` behind the
  progress dialog; `kotlinx.serialization` only.

### P2.2.4 Docs

- Update the **Page Index — Multi-Page Selection** section of `docs/mainactivity-and-recents.md`: the
  "PNG → Save as templates" batch now picks a destination folder (folders-only chooser, Save Here),
  no longer always root. Remove the "deferred to Phase 2" note. Drop the matching Phase-2 backlog
  seed below once shipped.

**→ Haiku: clean build + install on G10. Fix-loop (Sonnet fix → Haiku rebuild) until user passes.
Then update Status above and commit (no push).**

---

## Phase 2 Backlog (do NOT build in Phase 1 without discussion)

Append items discovered during implementation here. Seeds:

- **Confirm (OK) step for Copy & Move destinations.** → **Planned in detail: see § Phase 2 — Session
  P2.1 above.** (Destination tap selects-and-previews; **OK** commits; Cancel via the existing
  back / `cancelDestMode()` path. Affects only the `destMode` gate in front of
  `executeMove` / `executePaste`; the batch ops and undo/redo are untouched.)
- **Range selection** (tap first, shift/long-press last to select a span) for faster large
  selections.
- **Selection count badge on each grid page** / a footer summary while paginating.
- **Drag-to-reorder** multiple pages directly (vs. the before/after destination tap).
- **Export ordering choice** (page order vs. selection order) for PDF/PNG.
- **Persisting selection** across leaving/returning to the index (currently cleared on exit).
- **"Invert selection"** control alongside Select All.

---

## Status Legend

- ☐ Not started · ◐ In progress · ✅ DONE (with date)

| Session | Status |
|---|---|
| 1 — Selection model, Select All, multi-Delete | ✅ DONE (2026-06-19) |
| 2 — Multi Copy/Paste & Move + before/after | ✅ DONE (2026-06-19) |
| 3 — Multi Set Template | ✅ DONE (2026-06-19) |
| 4 — Multi Export (PDF/PNG/templates) | ✅ DONE (2026-06-19) |
| 5 — Wrap-up (docs, polish) | ✅ DONE (2026-06-19) |
| P2.1 — Confirm step for Copy/Move destinations | ✅ DONE (2026-06-19) |
| P2.2 — Choose template folder for batch PNG-as-templates | ✅ DONE (2026-06-19) |
</content>
</invoke>
