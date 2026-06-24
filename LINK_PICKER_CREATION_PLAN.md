# Link Picker — In-Place Creation Plan

> Add the ability to **create new pages, folders, and notebooks** directly from the link-target
> picker (`LinkTargetPickerActivity`) while building or editing a link object.
>
> This is a standing, session-tracked plan. Work **one session at a time, at the user's request**.
> Each feature session ends with a clean build + install on **G102 only** for the user to test.
> Fix any reported issues, then mark the session ✅ DONE and commit (**do not push**).

---

## Goal (from the user)

While creating (or editing) a link object, the user should be able to create new targets without
leaving the picker:

1. **This Notebook mode** — a top-right toolbar button (before **OK**) creates a **new page** in the
   current notebook.
   - If a page is currently selected in the picker → ask **insert before / after** the selected page.
   - If no page is selected → insert at the **end** of the notebook.
   - Either way, the new page becomes the **selected** page in the picker.

2. **Other Notebook mode (browsing folders/notebooks)** — two top-right toolbar buttons (before
   **OK**): **new folder** and **new notebook**, both created in the currently-browsed folder.

3. **Other Notebook mode, Page kind, viewing a notebook's pages** — a top-right toolbar button
   (before **OK**) creates a **new page** in the target notebook.
   - If a page is selected in that notebook → ask **insert before / after** the selected page.
   - If no page is selected → insert at the **end** of the target notebook.
   - Either way, the new page becomes the **selected** page in the picker.

---

## Key files (read before editing)

| File | Role |
|---|---|
| `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/LinkTargetPickerActivity.kt` | The picker. All UI/state lives here. |
| `apps/notesprout_android/app/src/main/res/layout/activity_link_target_picker.xml` | Picker layout (top bar holds OK; we add buttons before it). |
| `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/PageCopier.kt` | Raw `.soil` page write helpers (precedent: `copyPagesRelativeRaw`, `insertSoilTemplateRaw`). New blank-page helper goes here. |
| `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/index/IndexRepository.kt` | `createFolder`, `createNotebook` (index rows), `getFolderAncestry`. |
| `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/MainActivity.kt` | `createNotebook()` (~L1439) — canonical `.soil` bootstrap to replicate; `showNewFolderDialog()` (~L1241) — name-dialog pattern. |
| `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` | `launchLinkPicker()` (~L638), `linkPickerLauncher` (~L595), `addPage`/`addPageBefore` (~L4199), `openPageIndex()` (~L3765). |
| `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/ActionSheetDialog.kt` | `ActionSheetDialog(ctx).title(..).addAction(icon,label){..}.show()` — before/after chooser. |
| `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/crypto/SoilCrypto.kt` | `SoilCrypto.openRaw(file, passphrase?)` → `SoilRawDb` (insert/update/rawQuery/transactions). |
| `docs/links.md` | Link subsystem doc — update in the wrap-up session. |

### Icons (already present in `res/drawable/`)
- New page → `ic_page_add`
- New folder → `ic_folder_plus`
- New notebook → `ic_new_notebook`
- Before/after chooser → `ic_insert_page_before`, `ic_insert_page_after`

### Established patterns to follow (do not deviate)
- **Concurrent writes are safe.** `PageIndexActivity` already writes pages to the current
  notebook's `.soil` via raw `SoilCrypto` **while `NotebookActivity` keeps its Room connection
  open** (`openPageIndex()` saves strokes but does NOT close the DB). The picker writes the same
  way. This is the precedent — do not try to close/reopen the Room DB.
- **Key resolution.** The picker already resolves keys for other notebooks in
  `loadOtherPagesAsync()` via `KeySession.getFor(id)` then `KeyResolver.resolveForOpen(...)`. The
  current notebook's key is always available via `KeySession.getFor(notebookId)` (it's open).
- **E-ink design system.** No color, no elevation, 1dp inkBlack borders, `stateListAnimator=null`,
  `bg_toolbar_button` / `Widget.Notesprout.ToolbarButton` styles. Toolbar image buttons use
  `style="@style/Widget.Notesprout.ToolbarButton"` (see existing `btnBack`).
- **Slog only** (`Slog.d(TAG){..}`), `kotlinx.serialization` only, no new Gradle deps, never
  `runBlocking` on UI thread, no Material Components.

---

## Cross-cutting design decisions (already made — do NOT re-decide)

These resolve the ambiguous points so each session is mechanical:

1. **"Selected page" = a page tapped in the picker**, i.e. the picker's pending selection
   (`pendingTargetKind == TARGET_CURRENT_PAGE` with `pendingPageId`, or
   `TARGET_OTHER_NOTEBOOK_PAGE` with `pendingNotebookPageId` matching the open notebook). It is NOT
   `currentPageIndex` (the page the link is being created on).

2. **New page inherits the template** of its anchor page (the page it's inserted before/after).
   When inserted at the end, inherit the template of the **last** page. When the notebook has no
   pages (only possible for a freshly created notebook), template = `""` (blank). This mirrors
   `addPage()` in `NotebookActivity`.

3. **Notebooks created in the picker are always blank and unencrypted** (no template seeding, no
   encryption scope). Keep `MainActivity.createNotebook()` untouched. Factor the *blank* `.soil`
   bootstrap into a new `NotebookFactory` helper.

4. **Creating a folder navigates into it** (push onto `directoryStack`, reload browse) — matches
   `MainActivity.navigateIntoFolder`.

5. **Creating a notebook** reloads the browse list so it appears. In **Notebook kind** it is also
   auto-selected as the target (`pendingTargetKind = TARGET_OTHER_NOTEBOOK`). In **Page kind** it is
   not auto-selected; the user taps it to drill into its pages.

6. **No undo/redo integration for picker-created pages/folders/notebooks.** Creation is an explicit
   act (matching `MainActivity`, which does not undo folder/notebook creation). The link object
   itself remains undoable as `LinkCreated`/`LinkEdited`. Document this; do not add undo entries.

7. **NotebookActivity must refresh its in-memory page list after the picker returns**, because the
   picker may have inserted a page into the current notebook's `.soil` (shifting `order` values) even
   if the user then cancels. On **any** picker result, reload `pages` from the DB and recompute
   `currentPageIndex` from the stable `currentPageId` (see Session 1).

8. **Name validation** reuses `MainActivity`'s rules (letters/numbers/spaces/`_-.`, non-blank, not
   `.`/`..`). Folder names also check for duplicate siblings. Show a `Toast` on invalid input.

9. **Before/After chooser** uses `ActionSheetDialog` titled "Insert Page" with two actions:
   `ic_insert_page_before` "Insert Before" and `ic_insert_page_after` "Insert After".

---

## Shared building block (built in Session 1, reused in Session 3)

### `insertBlankPageRaw(...)` in `data/PageCopier.kt`

A raw helper that inserts one blank page (+ its content layer) into a `.soil`, returning the new
page id. Models `copyPagesRelativeRaw` for ordering and `addPage` for row shape.

```kotlin
/**
 * Inserts a single blank page (and its content layer) into the .soil at [notebookPath].
 *
 * Placement:
 *  - [anchorPageId] != null  → insert immediately before/after that page per [before].
 *  - [anchorPageId] == null  → append to the end (a fresh "last page").
 *
 * The new page inherits the template id of its anchor page (or, for end-insert, the last page;
 * "" when the notebook has no pages). parentId is the notebook metadata row id (type="notebook"),
 * falling back to any existing page's parentId.
 *
 * Returns Pair(newPageId, newPageIndex) — 0-based index in the post-insert page order — or null
 * on failure. Must be called on Dispatchers.IO.
 */
suspend fun insertBlankPageRaw(
    notebookPath: String,
    anchorPageId: String?,   // page to insert relative to; null = append
    before: Boolean,         // ignored when anchorPageId == null
    pageWidth: Float,
    pageHeight: Float,
    passphrase: String? = null,
): Pair<String, Int>?
```

Implementation notes:
- Open with `SoilCrypto.openRaw(File(notebookPath), passphrase)`; always `close()` +
  `cleanStrayJournal(notebookPath)` in `finally` (copy the private helper usage already in the file).
- Read page order: `SELECT id, \`order\` FROM notebook WHERE type='page' AND deletedAt IS NULL ORDER BY \`order\` ASC`.
- Compute `insertionIndex`: if anchor found → `if (before) idx else idx+1`; else `allPages.size`.
- Determine inherited template: read the anchor page's `data` (or the last page's `data` for
  end-insert) and `PageData.fromJson(data).template`; `""` if no pages.
- Bounding box: `BoundingBox(0f, 0f, pageWidth, pageHeight).toJson()`.
- In one transaction: shift `order` of pages at/after `insertionIndex` up by 1 (iterate reversed,
  like `copyPagesRelativeRaw`); insert the `page` row
  (`PageData(width=pageWidth, height=pageHeight, template=inherited).toJson()`); insert the `layer`
  row (`{"label":"Content","isLocked":false,"isVisible":true}`, `order` 0, parentId = newPageId).
- `db.checkpointAndVacuum()` after the transaction.
- Return `newPageId to insertionIndex`.

The caller supplies `pageWidth`/`pageHeight` from the picker's window metrics (full-screen, same
device): use `resources.displayMetrics.widthPixels.toFloat()` / `heightPixels.toFloat()`.

---

## Sessions

> **Build & install command (run at the end of every session):**
> ```sh
> cd apps/notesprout_android && ./gradlew assembleDebug && \
>   adb -s b7a46e13 install -r app/build/outputs/apk/debug/app-debug.apk
> ```
> Device: **BOOX Go 10.3 Gen 2 (G102)**, serial `b7a46e13`. **All testing this plan = G102 only.**

---

### Session 1 — "This Notebook": create a new page  ·  Status: ☐ NOT STARTED

**Scope:** the new-page button for the current notebook, plus the shared `insertBlankPageRaw` helper
and the NotebookActivity refresh-on-return.

**Steps:**

1. **`data/PageCopier.kt`** — add `insertBlankPageRaw(...)` exactly as specified in *Shared building
   block* above.

2. **`res/layout/activity_link_target_picker.xml`** — in `topBar`, insert an
   `AppCompatImageButton` **before** `btnConfirm`:
   ```xml
   <androidx.appcompat.widget.AppCompatImageButton
       android:id="@+id/btnNewPage"
       style="@style/Widget.Notesprout.ToolbarButton"
       android:src="@drawable/ic_page_add"
       android:contentDescription="New page"
       android:visibility="gone" />
   ```
   (It sits between the weighted `Space` and `btnConfirm`.)

3. **`LinkTargetPickerActivity.kt`:**
   - Add a field `private var currentNotebookPagesChanged = false` (used in step 5 plumbing; for now
     just track it).
   - In `onCreate`, wire `binding.btnNewPage.setOnClickListener { onNewPageTapped() }`.
   - In `updateHeaderState()`, set visibility:
     `binding.btnNewPage.visibility = if (targetTab == TargetTab.CURRENT) View.VISIBLE else View.GONE`
     (Session 3 extends this condition; for now CURRENT only).
   - Add:
     ```kotlin
     private fun onNewPageTapped() {
         // Session 1 handles CURRENT only; Session 3 adds the OTHER/PAGES branch.
         if (targetTab != TargetTab.CURRENT) return
         val selectedId = if (pendingTargetKind == TARGET_CURRENT_PAGE) pendingPageId else null
         if (selectedId != null) {
             ActionSheetDialog(this)
                 .title("Insert Page")
                 .addAction(R.drawable.ic_insert_page_before, "Insert Before") {
                     createCurrentPage(anchorPageId = selectedId, before = true)
                 }
                 .addAction(R.drawable.ic_insert_page_after, "Insert After") {
                     createCurrentPage(anchorPageId = selectedId, before = false)
                 }
                 .show()
         } else {
             createCurrentPage(anchorPageId = null, before = false) // append at end
         }
     }

     private fun createCurrentPage(anchorPageId: String?, before: Boolean) {
         val path = notebookSoilPath ?: return
         val w = resources.displayMetrics.widthPixels.toFloat()
         val h = resources.displayMetrics.heightPixels.toFloat()
         lifecycleScope.launch {
             val result = withContext(Dispatchers.IO) {
                 insertBlankPageRaw(path, anchorPageId, before, w, h, KeySession.getFor(notebookId))
             } ?: run {
                 android.widget.Toast.makeText(this@LinkTargetPickerActivity,
                     "Could not create page", android.widget.Toast.LENGTH_SHORT).show()
                 return@launch
             }
             currentNotebookPagesChanged = true
             // Reload pages from soil, then select + reveal the new page.
             pages = withContext(Dispatchers.IO) {
                 loadPagesFromSoil(path, KeySession.getFor(notebookId))
             }
             selectCurrentPage(result.first)              // sets pending target + renders
             val spec = gridSpec
             if (spec != null && spec.itemsPerPage > 0) {
                 currentGridPage = result.second / spec.itemsPerPage
             }
             render()
         }
     }
     ```
   - Import `ActionSheetDialog` and the `insertBlankPageRaw` symbol.

4. **`NotebookActivity.launchLinkPicker(...)`** — before launching, persist the current page so the
   picker reads fresh data and the open page isn't lost when the picker writes to the same `.soil`.
   Mirror `openPageIndex()`: capture + persist snapshot and `saveStrokes(db)` inside
   `withContext(Dispatchers.IO)`, then launch. (Convert `launchLinkPicker` to do its launch inside a
   `lifecycleScope.launch { ... }` like `openPageIndex`. The pending-link fields are already stashed
   by the caller before this runs, so they remain valid.)

5. **`NotebookActivity.linkPickerLauncher`** — refresh the in-memory page list on **every** return
   (OK or cancel), because the picker may have inserted a current-notebook page. Add, at the very
   start of the result callback (before the `resultCode` check):
   ```kotlin
   reloadPagesPreservingCurrent()
   ```
   Add the helper:
   ```kotlin
   /** Re-read the page list after an external picker may have inserted current-notebook pages. */
   private fun reloadPagesPreservingCurrent() {
       val db = soilDatabase ?: return
       lifecycleScope.launch {
           val refreshed = withContext(Dispatchers.IO) { db.notebookDao().getPagesSorted() }
           pages = refreshed.toMutableList()
           val idx = pages.indexOfFirst { it.id == currentPageId }
           if (idx >= 0) currentPageIndex = idx
           updatePageIndicator()
       }
   }
   ```
   (Use the exact name of the page-count UI updater already in the file — it is `updatePageIndicator()`.)

**Acceptance criteria (test on G102):**
- In a link's picker, **This Notebook** tab shows a new-page button left of OK.
- No page selected → tapping it appends a blank page; it becomes selected (highlighted) and the grid
  scrolls to it.
- With a page selected → tapping it asks Before/After; the page lands in the right spot and becomes
  selected.
- Tap OK → the link points at the new page; following the link opens it.
- Back out of the picker after creating a page, then add/delete pages in the notebook normally →
  ordering stays correct (no duplicate/!shifted pages), confirming the refresh works.
- Works for an **encrypted** current notebook too.

**On pass:** mark this session ✅ DONE below and `git commit` (no push).

---

### Session 2 — "Other Notebook": create folder + notebook  ·  Status: ☐ NOT STARTED

**Scope:** new-folder and new-notebook buttons while browsing the Other-notebook folder tree, plus
the `NotebookFactory` blank-notebook bootstrap.

**Steps:**

1. **New file `data/NotebookFactory.kt`** — extract the *blank, unencrypted* `.soil` bootstrap from
   `MainActivity.createNotebook()` (lines ~1478–1603, the `withContext(Dispatchers.IO)` block — the
   plaintext path only; omit the encryption branch and template seeding). Signature:
   ```kotlin
   /**
    * Creates a new blank, unencrypted notebook: the global-index row plus a fully initialized
    * .soil file (schema, undo_redo_state + notebook_meta tables, one blank page, one content layer,
    * notebook_meta with folder ancestry). Returns the new index ObjectEntity.
    * Must be called off the main thread.
    */
   suspend fun createBlankNotebook(
       context: Context,
       name: String,
       parentId: String?,
       repository: IndexRepository,
       pageWidthPx: Int,
       pageHeightPx: Int,
   ): ObjectEntity
   ```
   - Step 1: `val entity = repository.createNotebook(name, parentId)`.
   - Step 2: `val soilPath = soilFile(context, entity.id)`; open with
     `SQLiteDatabase.openOrCreateDatabase(soilPath, null)` (plaintext path).
   - Replicate the table creation, WAL/auto_vacuum PRAGMAs, the notebook/page/layer inserts, and the
     `notebook_meta` row **verbatim** from `MainActivity.createNotebook` — but with `encrypted=false`,
     `keyScope=null`, no template seed (`firstPageTemplate = ""`). Use
     `repository.getFolderAncestry(parentId)` for `NotebookMeta.folderPath`.
   - Delete the stray `-journal` file at the end (as MainActivity does).
   - Return `entity`.
   - Imports: `NotebookMetadata`, `NotebookMeta`, `PageData`, `BoundingBox`, `BuildConfig`,
     `soilFile`, `UUID`. Match the exact JSON shapes used in MainActivity.

   > Do **not** modify `MainActivity.createNotebook` in this session (keeps the encryption/template
   > path untouched). Some bootstrap SQL is intentionally duplicated; note it in a code comment.

2. **`res/layout/activity_link_target_picker.xml`** — add two image buttons in `topBar` **before**
   `btnNewPage` (order: … Space, btnNewFolder, btnNewNotebook, btnNewPage, btnConfirm):
   ```xml
   <androidx.appcompat.widget.AppCompatImageButton
       android:id="@+id/btnNewFolder"
       style="@style/Widget.Notesprout.ToolbarButton"
       android:src="@drawable/ic_folder_plus"
       android:contentDescription="New folder"
       android:visibility="gone" />

   <androidx.appcompat.widget.AppCompatImageButton
       android:id="@+id/btnNewNotebook"
       style="@style/Widget.Notesprout.ToolbarButton"
       android:src="@drawable/ic_new_notebook"
       android:contentDescription="New notebook"
       android:visibility="gone" />
   ```

3. **`LinkTargetPickerActivity.kt`:**
   - Wire `btnNewFolder` → `onNewFolderTapped()`, `btnNewNotebook` → `onNewNotebookTapped()`.
   - In `updateHeaderState()`, set both visible only while browsing the Other tree:
     ```kotlin
     val showOtherCreate = targetTab == TargetTab.OTHER && otherView == OtherView.BROWSE
     binding.btnNewFolder.visibility   = if (showOtherCreate) View.VISIBLE else View.GONE
     binding.btnNewNotebook.visibility = if (showOtherCreate) View.VISIBLE else View.GONE
     ```
   - Add a private name-input dialog helper modeled on `MainActivity.showNewFolderDialog`
     (reuse `DialogNewNotebookBinding`; same IME show/hide + `shape_bordered` background +
     `setElevation(0f)` styling):
     ```kotlin
     private fun promptForName(title: String, hint: String, onName: (String) -> Unit) { ... }
     ```
   - Add validation mirroring MainActivity:
     ```kotlin
     private fun nameError(name: String): String? {
         if (name.isBlank()) return "Name cannot be empty"
         if (name == "." || name == "..") return "Invalid name"
         if (name.contains(Regex("[^a-zA-Z0-9_\\-. ]")))
             return "Name may only contain letters, numbers, spaces, and _ - ."
         return null
     }
     ```
   - New folder:
     ```kotlin
     private fun onNewFolderTapped() = promptForName("New Folder", "Folder name") { raw ->
         val name = raw.trim()
         lifecycleScope.launch {
             nameError(name)?.let { toast(it); return@launch }
             val parentId = directoryStack.last()?.id
             val siblings = withContext(Dispatchers.IO) { indexRepo.getFolders(parentId) }
             if (siblings.any { it.name.equals(name, true) }) { toast("A folder named \"$name\" already exists"); return@launch }
             val entity = withContext(Dispatchers.IO) { indexRepo.createFolder(name, parentId) }
             navigateIntoFolder(entity)   // existing helper: pushes stack + reloads browse
         }
     }
     ```
   - New notebook:
     ```kotlin
     private fun onNewNotebookTapped() = promptForName("New Notebook", "Notebook name") { raw ->
         val name = raw.trim()
         lifecycleScope.launch {
             nameError(name)?.let { toast(it); return@launch }
             val parentId = directoryStack.last()?.id
             val w = resources.displayMetrics.widthPixels
             val h = resources.displayMetrics.heightPixels
             val entity = withContext(Dispatchers.IO) {
                 createBlankNotebook(this@LinkTargetPickerActivity, name, parentId, indexRepo, w, h)
             }
             loadBrowseAsync()                       // refresh list so it appears
             if (otherKind == OtherKind.NOTEBOOK) {  // auto-select as target in Notebook kind
                 pendingTargetKind = TARGET_OTHER_NOTEBOOK
                 pendingNotebookId = entity.id
                 pendingPageId = null; pendingNotebookPageId = null
             }
         }
     }
     ```
   - Add a small `private fun toast(msg: String)` wrapper if convenient.

**Acceptance criteria (test on G102):**
- In **Other notebook** mode while browsing, New-folder and New-notebook buttons appear left of OK
  (and disappear when you drill into a notebook's pages in Page kind).
- New folder → prompt → created in the current breadcrumb folder → picker navigates into it.
  Duplicate/blank/invalid names are rejected with a toast.
- New notebook → prompt → a blank notebook is created in the current folder and appears in the grid.
  In **Notebook kind** it is auto-selected (highlighted); OK links to it.
- Open the newly created notebook later from MainActivity → it has exactly one blank page and opens
  cleanly (validates the `.soil` bootstrap).

**On pass:** mark ✅ DONE below and `git commit` (no push).

---

### Session 3 — "Other Notebook" Page kind: create a page in the target notebook  ·  Status: ☐ NOT STARTED

**Scope:** reuse the `btnNewPage` button for the target-notebook page case (Other tab, Page kind,
viewing a notebook's pages).

**Steps:**

1. **`LinkTargetPickerActivity.kt` — `updateHeaderState()`:** extend `btnNewPage` visibility:
   ```kotlin
   val showNewPage = targetTab == TargetTab.CURRENT ||
       (targetTab == TargetTab.OTHER && otherKind == OtherKind.PAGE && otherView == OtherView.PAGES)
   binding.btnNewPage.visibility = if (showNewPage) View.VISIBLE else View.GONE
   ```

2. **`onNewPageTapped()`** — add the OTHER/PAGES branch (the early `return` from Session 1 becomes a
   branch):
   ```kotlin
   private fun onNewPageTapped() {
       when {
           targetTab == TargetTab.CURRENT -> { /* Session 1 logic, unchanged */ }
           targetTab == TargetTab.OTHER && otherView == OtherView.PAGES -> {
               val nbId = otherNotebookId ?: return
               val selectedId = if (pendingTargetKind == TARGET_OTHER_NOTEBOOK_PAGE &&
                                    pendingNotebookId == nbId) pendingNotebookPageId else null
               if (selectedId != null) {
                   ActionSheetDialog(this)
                       .title("Insert Page")
                       .addAction(R.drawable.ic_insert_page_before, "Insert Before") {
                           createOtherPage(nbId, selectedId, before = true)
                       }
                       .addAction(R.drawable.ic_insert_page_after, "Insert After") {
                           createOtherPage(nbId, selectedId, before = false)
                       }
                       .show()
               } else {
                   createOtherPage(nbId, anchorPageId = null, before = false)
               }
           }
       }
   }
   ```

3. **Add `createOtherPage(...)`** — resolve the target notebook's key the same way
   `loadOtherPagesAsync` does, then insert and reload:
   ```kotlin
   private fun createOtherPage(forNotebookId: String, anchorPageId: String?, before: Boolean) {
       val path = soilFile(this, forNotebookId).absolutePath
       val w = resources.displayMetrics.widthPixels.toFloat()
       val h = resources.displayMetrics.heightPixels.toFloat()
       lifecycleScope.launch {
           val info = withContext(Dispatchers.IO) { indexRepo.getEncryptionInfo(forNotebookId) }
           val key = if (info.encrypted)
               KeySession.getFor(forNotebookId)
                   ?: KeyResolver.resolveForOpen(this@LinkTargetPickerActivity, forNotebookId, info)
           else null
           if (info.encrypted && key == null) { toast("Notebook is locked"); return@launch }
           val result = withContext(Dispatchers.IO) {
               insertBlankPageRaw(path, anchorPageId, before, w, h, key)
           } ?: run { toast("Could not create page"); return@launch }
           if (otherNotebookId != forNotebookId) return@launch   // user navigated away
           otherPages = withContext(Dispatchers.IO) { loadPagesFromSoil(path, key) }
           selectOtherNotebookPage(forNotebookId, result.first)  // sets pending target + renders
           val spec = gridSpec
           if (spec != null && spec.itemsPerPage > 0) currentGridPage = result.second / spec.itemsPerPage
           render()
       }
   }
   ```

**Acceptance criteria (test on G102):**
- Other notebook → Page kind → tap a notebook to view its pages → a new-page button appears left of
  OK (and is hidden while merely browsing notebooks in Page kind).
- No page selected → appends a blank page to the target notebook; it becomes selected.
- A page selected → Before/After prompt; page lands correctly and becomes selected.
- OK → link points at the new page in the other notebook; following it opens that page.
- Works when the target notebook is **encrypted** (key prompt appears if not cached; cancelling
  leaves the notebook untouched).
- Newly created notebook from Session 2, opened in Page kind, can have pages added this way.

**On pass:** mark ✅ DONE below and `git commit` (no push).

---

### Session 4 — Wrap-up: docs & polish  ·  Status: ☐ NOT STARTED

**No Tier 1 testing. Final build/install on G102 only.**

**Steps:**
1. **`docs/links.md`** — under *Target Picker UI*, add a subsection "Creating targets in the picker"
   documenting: the new-page button (This Notebook + Other/Page), the new-folder/new-notebook
   buttons (Other/browse), the before/after behavior, that new pages become selected, that picker
   notebooks are blank/unencrypted, the no-undo decision, and that NotebookActivity refreshes its
   page list on picker return (decision #7).
2. **`CLAUDE.md`** — if the picker's responsibilities are summarized anywhere, add a one-line note;
   otherwise no change.
3. **`data/PageCopier.kt` / `data/NotebookFactory.kt`** — confirm KDoc on the new public functions
   is complete and accurate.
4. Quick consistency pass: button content-descriptions, toast strings, and that all new buttons hide
   in every mode where they shouldn't appear (edit-link flow included).
5. Final clean build + install on G102; user does a smoke test across all three creation paths.

**On pass:** mark ✅ DONE below and `git commit` (no push).

---

## Status tracker

| Session | Title | Status |
|---|---|---|
| 1 | This Notebook — new page (+ `insertBlankPageRaw`, refresh-on-return) | ☐ NOT STARTED |
| 2 | Other Notebook — new folder + notebook (+ `NotebookFactory`) | ☐ NOT STARTED |
| 3 | Other Notebook Page kind — new page in target notebook | ☐ NOT STARTED |
| 4 | Wrap-up — docs & polish | ☐ NOT STARTED |

> Status legend: ☐ NOT STARTED · ◐ IN PROGRESS · ✅ DONE
> When a session passes G102 testing, set its row + heading to ✅ DONE, then `git commit` (no push).
