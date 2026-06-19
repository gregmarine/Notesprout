# Template System — Global Index Migration Plan

> **Growth / New Branch:** Move the template *library* out of flat PNG files and into the global
> index (`notesprout.db`) as first-class objects, organized by **Template Folders**, with full
> search / sort / rename / copy / move / delete — mirroring the existing notebook & folder UX. Add a
> full-screen template browser, a full-screen New Notebook flow that can seed the first page with a
> template, and a standalone template-management entry point in the MainActivity toolbar.

---

## 0. How We Run This Growth (workflow — read first)

- The plan is executed **one session at a time**, in order.
- For each session, **a Sonnet subagent performs the implementation** per that session's spec. The
  spec must be precise enough that the subagent makes **no design decisions of its own** — every
  decision is locked in this document.
- After the subagent finishes a session, **the lead (Opus) does a clean build and installs the debug
  APK on G10** (`34E517F9`) for the user to test.
- **If issues are found, the lead (Opus) makes the fixes** — not a subagent.
- When the user confirms tests pass, the lead **updates this file's status table + session checklist**,
  then **commits all changes for that session with a descriptive message — but does NOT push.**
- A **Wrap-Up session** at the end captures anything discovered along the way.

### Build & install (debug, G10)

```sh
cd apps/notesprout_android && ./gradlew assembleDebug
adb -s 34E517F9 install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 1. Status

| # | Session | Status |
|---|---|---|
| 1 | Index data model & repository foundation | ✅ Done |
| 2 | TemplateBrowserActivity — browse, navigate, import, new folder + toolbar launch | ✅ Done |
| 3 | Management actions — preview, action sheet, rename, copy, move, delete | ✅ Done |
| 4 | Search & sort in the template browser | ✅ Done |
| 5 | In-notebook integration — slim picker + library browse + apply-into-`.soil` | ✅ Done |
| 6 | Full-screen New Notebook flow + first-page template seeding | ✅ Done |
| 7 | Wrap-up — docs, dead-code removal, cross-device QA, migration task | ✅ Done |
| | **— Phase 2: Pinned · Recent · Toolbar · Save-as-Template (see §6) —** | |
| 8 | Pinned templates — repository + MANAGE pin/unpin + pinned view | ✅ Done |
| 9 | Recent templates — device-local store + use-tracking | ✅ Done |
| 10 | Pinned & recent in the PICK selectors (new-notebook + in-notebook) | ⬜ Not started |
| 11 | Toolbar relocation — search/sort/pinned/recent → top bar | ⬜ Not started |
| 12 | "Save as Template" from the page index | ⬜ Not started |
| 13 | Phase 2 wrap-up — docs, dead-code, cross-device QA | ⬜ Not started |

Status legend: ⬜ Not started · 🚧 In progress · ✅ Done (committed, not pushed)

> **Phase 1 (Sessions 1–7)** delivered the global-index template library, browser, in-notebook
> integration, and New Notebook seeding — all committed **and pushed**. **Phase 2 (Sessions 8–13,
> §6)** layers pinning, recents, a toolbar reorg, and page→template export on top. Phase 2 follows
> the same workflow as §0 (one session at a time, Sonnet subagent implements, lead builds/installs
> on G10, commit-no-push per session).

---

## 2. Locked Architecture Decisions

These are **final**. Subagents must not deviate.

1. **Two new object types** in the global index (`data/index/ObjectType.kt`):
   - `TEMPLATE = "template"`
   - `TEMPLATE_FOLDER = "template_folder"`
   - No collision with the `.soil` `type="template"` rows — those live in a *different* database
     (`notebook` table inside each `.soil`), not the global `objects` table.

2. **Template object payload** — new serializable in `data/index/`:
   ```kotlin
   @Serializable
   data class TemplateObject(
       val width: Int = 0,
       val height: Int = 0,
       val image: String = "",   // full-resolution PNG, base64 (NO_WRAP)
   )
   ```
   - The template **name lives in `ObjectEntity.name`** (the top-level column), exactly like
     notebooks and folders. It does **NOT** live inside the JSON. (This differs from the `.soil`
     `TemplateData`, which keeps name in JSON — that class is unchanged and stays in use for `.soil`.)
   - The full base64 image is stored in `ObjectEntity.data`. This is consistent with the existing
     pattern of storing base64 cover snapshots in `NotebookObject.snapshot` in the same column.

3. **Template Folders** behave like notebook folders: nestable, `parentId = null` at root,
   soft-deleted recursively, movable, copyable, renamable. They are a **separate** type — never
   reuse `FOLDER`. A template folder can contain template folders and templates only.

4. **Thumbnails:** decode-sampled from the full base64 on `Dispatchers.IO`, cached in an in-memory
   map keyed by `"${id}:${updatedAt}"`. Mirror the sampling logic already in `TemplateDialog`
   (`computeInSampleSize`, cap `inSampleSize` at 4). No separate thumbnail field is stored.
   (Expected template counts are tens, not thousands; revisit only if it becomes a perf issue.)

5. **One full-screen Activity — `TemplateBrowserActivity`** — drives all three contexts via a mode:
   - `MODE_MANAGE` — launched from the MainActivity toolbar. Full management. No selection result.
   - `MODE_PICK` — selection. Returns a chosen library template id (or blank). Used by:
     - the in-notebook template button (PICK for the current page), and
     - the New Notebook flow (PICK **plus** a name field + CREATE button, gated by an extra).
   - "Full screen" = a **normal Activity that fills the screen** (Android status bar + nav bar remain
     visible). Use `Theme.Notesprout` (`Theme.AppCompat.Light.NoActionBar`). **No immersive mode.**

6. **Templates within notebooks are unchanged.** The `.soil` `type="template"` rows + page
   `data.template` references stay exactly as they are. When a user selects a **library** template,
   it is **copied into the notebook's `.soil`** as a `type="template"` row at apply/create time
   (same mechanism as today's `insertTemplateFromFile`, but sourced from a `TemplateObject`'s base64
   instead of a PNG file). Deleting a library template never affects notebooks that already used it.

7. **`.soil` access stays inside `NotebookActivity`.** The browser Activity never opens a `.soil`
   file. The "templates already used in this notebook" quick-pick is owned by `NotebookActivity`
   (which already holds the live `.soil` Room connection) — see Session 5. This avoids any
   cross-Activity WAL/sidecar risk.

8. **Blank is always available** as a virtual entry in PICK contexts (clears the page template).
   It is **not** a stored object.

9. **All index writes go through `IndexRepository`.** No direct DAO mutations outside it.

10. **Design system:** e-ink rules from `CLAUDE.md` apply everywhere — no color, no elevation, no
    ripple, no animation; 1dp inkBlack borders; `shape_bordered` dialog backgrounds applied after
    `show()`; `Slog.d` not `Log.d`; `kotlinx.serialization` only; no Material Components; no new
    Gradle dependencies.

---

## 3. Reference Map (mirror these existing patterns)

The template browser is a near-clone of MainActivity's browse system. Subagents should **read and
mirror** these:

- **Repository CRUD/move:** `data/index/IndexRepository.kt` — `createFolder`, `createNotebook`,
  `renameFolder`, `softDeleteFolder`, `moveObject`, `getChildren`, `getFolders`, `getNotebooks`,
  `getAllNotebooks`, `getAllFolders`.
- **DAO:** `data/index/ObjectDao.kt` — `getChildren(parentId, type)`, `getById`, `insert`, `update`,
  `softDelete`, `getAllNotDeleted`. Likely **no DAO changes needed**.
- **Directory stack / breadcrumb:** `MainActivity` — `directoryStack`, `currentParentId`,
  `navigateIntoFolder`, breadcrumb builder (~`MainActivity:745`).
- **Card grid render + pagination:** `MainActivity.scanAndRender` (~666) and `sortItems` (~720).
- **Action sheets:** `ActionSheetDialog` (builder: `.title().addAction(icon,label){}.show()`); usage
  at `MainActivity.showNotebookContextMenu` (~1473) / `showFolderContextMenu` (~1501).
- **Picker (move/copy destination):** `MainActivity.enterPickerMode` /
  `DestinationPickerState` (~1513), `isSelfOrDescendant`, conflict-check dialog.
- **Recursive copy / delete:** `MainActivity.copyFolderRecursively` (~1740),
  `deleteFolderRecursively` (~1724).
- **Name validation:** `MainActivity.validateNotebookName` (~1315), `validateFolderName` (~1324),
  `validateFolderRename` (~1341). Whitelist `[^a-zA-Z0-9_\-. ]`, reject `.`/`..`, dup-check in
  current/parent folder.
- **Sort:** `com.notesprout.android.sort` — `SortPreferences`, `SortField` (NAME / DATE_MODIFIED),
  `SortOrder`, `FolderSort` (FOLDERS_FIRST / NOTEBOOKS_FIRST / MIXED), `SortPreferencesManager`,
  `SortDialog`.
- **Search:** `search/SearchEngine.kt` — fuzzy `score(query, name)`; substring(3) > all-words(2) >
  prefix/initials(1).
- **Current template dialog (to slim down in S5):** `TemplateDialog.kt`; in-notebook apply path
  `NotebookActivity.applyTemplateToCurrentPage` (~3481), `persistPageTemplate` (~3520),
  `insertTemplateFromFile` (~`TemplateDialog:381`).
- **New notebook + first page creation:** `MainActivity.showNewNotebookDialog` (~1115),
  `createNotebook` (~1356) — note where the first page row is inserted into the new `.soil`.
- **Toolbar:** `res/layout/activity_main.xml` `actionButtonsGroup` (~320) holds `btnNewNotebook` +
  `btnNewFolder`; visibility toggles at `MainActivity` lines ~410, ~498, ~1539.
- **PNG import sanitization:** `NotebookActivity.sanitizeTemplateFileName` (~8286) — reuse this
  defense when importing into the index.

---

## 4. Sessions

### Session 1 — Index data model & repository foundation (no visible UI)

**Objective:** Add the data types and repository methods for template folders & templates. App still
builds and runs; nothing user-visible yet.

**Files:**
- `data/index/ObjectType.kt` — add `TEMPLATE` and `TEMPLATE_FOLDER` constants.
- `data/index/TemplateObject.kt` *(new)* — the `@Serializable data class TemplateObject` from §2.2,
  with `toJson()` / `fromJson()` using the project's shared `Json` config (mirror `ObjectData.kt`
  codec: `encodeDefaults=true, explicitNulls=false, ignoreUnknownKeys=true`).
- `data/index/IndexRepository.kt` — add a `// region Template operations` block:
  - `suspend fun createTemplateFolder(name: String, parentId: String?): ObjectEntity`
  - `suspend fun createTemplate(name: String, parentId: String?, width: Int, height: Int, imageBase64: String): ObjectEntity`
  - `suspend fun renameTemplate(id: String, newName: String)` (also covers folders; or add
    `renameTemplateFolder` — keep symmetric with notebook/folder rename, two methods)
  - `suspend fun renameTemplateFolder(id: String, newName: String)`
  - `suspend fun softDeleteTemplate(id: String)` / `suspend fun softDeleteTemplateFolder(id: String)`
  - `suspend fun getTemplate(id: String): ObjectEntity?`
  - `suspend fun getTemplates(parentId: String?): List<ObjectEntity>` → `dao.getChildren(parentId, TEMPLATE)`
  - `suspend fun getTemplateFolders(parentId: String?): List<ObjectEntity>` → `dao.getChildren(parentId, TEMPLATE_FOLDER)`
  - `suspend fun getAllTemplates(): List<ObjectEntity>` → `getAllNotDeleted().filter { it.type == TEMPLATE }`
  - `suspend fun copyTemplate(sourceId: String, destParentId: String?, newName: String? = null): ObjectEntity?`
    — new UUID, copy `data` verbatim (base64 included), name = `newName ?: source.name`.
  - `suspend fun copyTemplateFolderRecursively(sourceFolderId: String, destParentId: String?)`
    — mirror `MainActivity.copyFolderRecursively`: create new folder, recurse subfolders, copy
    templates. (Repository-level recursion; `MainActivity` version mixes `.soil` copies — here it's
    pure index, simpler.)
  - `suspend fun deleteTemplateFolderRecursively(folderId: String)` — soft-delete all descendant
    template folders + templates, then the folder. Pure index; **no `.soil`/file cleanup** (templates
    are index-only now).
  - Reuse `moveObject(id, newParentId)` for moves (already generic).

**Edge cases / rules:**
- `parentId = null` = root.
- Soft-delete only.
- Do not touch `ensurePinnedListExists` / lists.

**Definition of done:** Project compiles; `./gradlew assembleDebug` succeeds; app launches on G10 and
behaves exactly as before (no UI change). Lead verifies launch.

---

### Session 2 — TemplateBrowserActivity: browse, navigate, import, new folder + toolbar entry

**Objective:** A reachable full-screen browser (MANAGE mode) that lists template folders + templates
in a paginated card grid with breadcrumb navigation, imports PNGs into the current folder, and
creates new template folders. Reachable via a new MainActivity toolbar button.

**Files:**
- `TemplateBrowserActivity.kt` *(new)* + `res/layout/activity_template_browser.xml` *(new)*.
- `res/layout/activity_main.xml` — add `btnTemplates` (see below).
- `MainActivity.kt` — wire `btnTemplates` + visibility handling.
- `AndroidManifest.xml` — register `TemplateBrowserActivity` (theme `Theme.Notesprout`).
- Reuse `ic_template`, `ic_folder_plus` (new template folder), and the import icon used by the
  current dialog ("Import…"); for the toolbar button use **`ic_template`** per the user.

**Activity contract / extras (define companion constants):**
```
EXTRA_MODE            : Int   // MODE_MANAGE=0, MODE_PICK=1
EXTRA_COLLECT_NAME    : Bool  // PICK only: show name field + CREATE (New Notebook flow). default false
EXTRA_TITLE           : String? // optional screen title override
// PICK results (Session 5/6):
RESULT_TEMPLATE_ID    : String // "" = Blank, else library TemplateObject id
RESULT_NOTEBOOK_NAME  : String // only when EXTRA_COLLECT_NAME
```
This session implements **MODE_MANAGE** end-to-end; the PICK plumbing is added in S5/S6 but define
the constants now.

**Layout (`activity_template_browser.xml`) — mirror `activity_main.xml` structure:**
- Top bar: breadcrumb / back affordance (clone MainActivity breadcrumb at ~745), screen title
  "Templates". A close/back button returns to the caller (`finish()`).
- Center: a paginated card grid container (reuse the same card-grid + pagination approach as
  `MainActivity.scanAndRender`; extract shared logic only if trivial — otherwise replicate to keep
  sessions independent). Cards:
  - **Template folder card:** folder icon + name (match notebook-folder card styling).
  - **Template card:** thumbnail (decode-sampled, `shape_bordered` thumb frame with 1dp inset like
    `TemplateDialog.buildGridCell`) + name below.
- Bottom toolbar: pagination (first/prev/next/last) on the left; on the right an
  **"New Template Folder"** button (`ic_folder_plus`) and an **"Import"** button (`ic_template` or
  the existing import affordance). (Search + Sort buttons are added in Session 4 — leave space /
  add disabled placeholders is **not** required; just add them in S4.)
- Column count: 4 when `widthPixels >= 1500`, else 2 (match `TemplateDialog`).

**Behaviors:**
- **Load:** on resume, `repository.getTemplateFolders(currentParentId)` + `getTemplates(currentParentId)`,
  render folders-first by default (sort comes in S4 — for now folders-first, name ascending).
- **Navigate into folder:** push onto a local `directoryStack` (clone MainActivity's), re-render,
  update breadcrumb. Back/breadcrumb pops.
- **Tap a template:** open a **full-screen preview** of the full-resolution bitmap (decode bounded
  via `core/BitmapDecode.decodeSampled`, `MAX_DIMENSION=4096`) with a close/back control. (Long-press
  → action sheet is **Session 3**; tap preview can be a simple full-screen `ImageView` + close for
  now.)
- **Import:** `ACTION_OPEN_DOCUMENT`, `arrayOf("image/png")`. On result: read bytes on IO, decode to
  get width/height, base64-encode (`NO_WRAP`), derive a name from the document `DISPLAY_NAME` via the
  **`sanitizeTemplateFileName` logic** (strip dirs, whitelist, drop extension for the *name*), then
  `repository.createTemplate(name, currentParentId, w, h, base64)`. Re-render. **Name uniqueness:**
  if a sibling template with that name exists, append ` (2)`, ` (3)`, … (do **not** silently
  overwrite — import has no "current page" context). Surface a Toast on decode failure.
- **New Template Folder:** dialog reusing `DialogNewNotebookBinding` with hint "Folder name",
  title "New Template Folder", validate with the folder-name rules (whitelist, `.`/`..`,
  dup-check against template folders in `currentParentId`), then
  `repository.createTemplateFolder(name, currentParentId)` and navigate into it (match
  `showNewFolderDialog`).
- **Empty state:** "No templates yet." / "Empty folder." text, matching MainActivity tone.
- Thumbnail cache per §2.4.

**MainActivity toolbar button (`btnTemplates`):**
- In `activity_main.xml` `actionButtonsGroup`, add **after** `btnNewFolder`:
  ```xml
  <androidx.appcompat.widget.AppCompatImageButton
      android:id="@+id/btnTemplates"
      style="@style/Widget.Notesprout.ToolbarButton"
      android:layout_marginStart="4dp"
      android:src="@drawable/ic_template"
      android:contentDescription="Templates" />
  ```
- `MainActivity.setupListeners`: `binding.btnTemplates.setOnClickListener { startActivity(Intent(this, TemplateBrowserActivity::class.java).putExtra(EXTRA_MODE, MODE_MANAGE)) }`.
- **Visibility:** treat exactly like `btnNewFolder` — visible in normal browse; **GONE** in picker,
  pinned, recents, and search modes (add `binding.btnTemplates.visibility = …` everywhere
  `btnNewFolder` is toggled: ~410/411, ~498/499, and the search block ~1539).

**Definition of done:** From MainActivity, tapping the new template icon opens the full-screen
browser. User can create template folders, navigate in/out, import PNGs (which appear as cards),
and tap a template to see a full preview. Build + install on G10.

---

### Session 3 — Management actions: preview, action sheet, rename, copy, move, delete

**Objective:** Long-press management for template folders & templates matching the notebook/folder UX.

**Files:** `TemplateBrowserActivity.kt` (+ any small dialog layouts; reuse `DialogNewNotebookBinding`).

**Behaviors:**
- **Long-press a template** → `ActionSheetDialog` titled with the template name:
  - `ic_copy_plus` **Copy Template** → enter **copy picker** (see below).
  - `ic_move_page` **Move Template** → enter **move picker**.
  - `ic_edit` **Rename Template** → rename dialog (validate: whitelist, `.`/`..`, dup-check templates
    in this template's own `parentId`, exclude self; no-op if unchanged) → `repository.renameTemplate`.
  - `ic_delete_notebook` (or `ic_trash`) **Delete Template** → confirm AlertDialog
    ("Delete \"<name>\"? This cannot be undone."), then `repository.softDeleteTemplate`.
- **Long-press a template folder** → `ActionSheetDialog` titled with folder name:
  - `ic_copy_plus` **Copy Folder** → copy picker.
  - `ic_move_page` **Move Folder** → move picker.
  - `ic_edit` **Rename Folder** → rename dialog (folder-rename validation against folder's own parent).
  - `ic_folder_minus` **Delete** → confirm AlertDialog
    ("Delete \"<name>\"? This will permanently remove all template folders and templates inside it.
    This cannot be undone.") → `repository.deleteTemplateFolderRecursively`.
- **Tap a template (MANAGE):** full-screen preview (already in S2; finalize chrome — close button,
  bordered frame, centered FIT_CENTER image).
- **Move / Copy picker** (mirror `MainActivity.enterPickerMode` + `DestinationPickerState`, scoped to
  **template folders only**):
  - Picker shows only template folders for navigation + a "move/copy here" confirm + cancel, with a
    breadcrumb. Hide template *cards* while picking (only folders are destinations), matching how the
    notebook picker shows folders as destinations.
  - **Self/descendant guard** for folder moves/copies (clone `isSelfOrDescendant`): cannot move a
    folder into itself or a descendant; cannot move an item into its current parent (no-op message).
  - **Conflict check** at destination: if a sibling of the same name + type exists, show
    "A [template/folder] named '<name>' already exists here. Replace it?" — Replace proceeds (soft-
    delete the existing then write), Cancel stays in picker. (Mirror MainActivity conflict dialog.)
  - **Move** = `repository.moveObject(id, destParentId)`.
  - **Copy template** = `repository.copyTemplate(id, destParentId)`.
  - **Copy folder** = `repository.copyTemplateFolderRecursively(id, destParentId)`.
- After any mutation, re-render the active view; if a name now collides on rename, block via Toast.

**Definition of done:** All six actions (rename/copy/move/delete × template/folder) work, with
conflict + self-descendant guards, matching notebook/folder behavior. Build + install on G10.

---

### Session 4 — Search & sort in the template browser

**Objective:** Add search and sort to `TemplateBrowserActivity`, mirroring MainActivity.

**Files:** `TemplateBrowserActivity.kt`, `activity_template_browser.xml`; reuse `sort/SortDialog`,
`sort/SortPreferences*`; `search/SearchEngine.kt` (extend or add a template variant).

**Sort:**
- Add a **Sort** button (`ic_sort` or whatever MainActivity uses) to the browser's top/bottom bar.
- Reuse `SortField` / `SortOrder` / `FolderSort` and `SortDialog`.
- **Persistence:** store template sort prefs under a **separate key** so they don't clobber notebook
  sort. Check `SortPreferencesManager.load/save` signatures: if it hardcodes the prefs key, add an
  overload taking a key (e.g. `"notesprout_template_sort_prefs"`); otherwise pass the key. Do **not**
  change notebook behavior.
- Apply: `FolderSort` controls folders-vs-templates ordering (FOLDERS_FIRST / TEMPLATES_FIRST(=the
  NOTEBOOKS_FIRST enum value, just relabel in the dialog if trivial) / MIXED); `SortField` +
  `SortOrder` order within. Mirror `MainActivity.scanAndRender`'s sort block.

**Search:**
- Add a **Search** button + inline search field/mode (mirror MainActivity search toolbar toggling).
- Scope: search **all templates** (`repository.getAllTemplates()`), score by name via
  `SearchEngine.score` (extract/reuse — if `SearchEngine.search` is notebook-specific, add
  `SearchEngine.scoreName(query, name)` public helper and use it here, or write a small local
  scorer with identical tiers). Show matching template cards with their folder-path label
  (`buildFolderLabel` equivalent over template folders). Tapping a result in MANAGE = preview; in
  PICK = select.
- Search mode is **not persisted**; exits on back.

**Definition of done:** Sort dialog reorders folders/templates; search finds templates by fuzzy name
across all folders; notebook sort prefs untouched. Build + install on G10.

---

### Session 5 — In-notebook integration

**Objective:** Replace the in-notebook template **library** picker with the full-screen browser while
**keeping** a quick pick of templates already used in this notebook. Selecting a library template
copies it into the `.soil` and applies it to the current page. Remove the old PNG-file library path.

**Decision recap (locked):** `NotebookActivity` owns `.soil` access. The in-notebook template button
shows a **slim chooser** (Blank + "in this notebook" thumbnails + **Browse Library…**). "Browse
Library…" launches `TemplateBrowserActivity` in `MODE_PICK`. This honors "full-screen library browse"
while keeping zero cross-Activity `.soil` connections (avoids WAL/sidecar risk).

**Files:** `TemplateDialog.kt`, `NotebookActivity.kt`, `TemplateBrowserActivity.kt`.

**`TemplateDialog` — slim down:**
- Remove the **All / PNG-file** tab and all PNG-library code paths (`scanFileItems`, `templatesDir`,
  `insertTemplateFromFile`, per-file delete, the import-from-file wiring). Keep only:
  - **Blank** entry (selected indicator from current page template id),
  - the **"in this notebook"** grid = `db.notebookDao().getTemplatesSorted()` (the existing `.soil`
    `type="template"` rows) with thumbnails + tap-to-apply (existing behavior), and
  - a **"Browse Library…"** button (in the custom title bar, replacing "Import…").
- It becomes single-view (no tabs). Keep the e-ink dialog styling.
- `onRequestImport` is removed; add a new callback `onBrowseLibrary: () -> Unit`.

**`NotebookActivity` — wiring:**
- `btnTemplate` click: build the slim `TemplateDialog`; on **Browse Library…**, launch
  `TemplateBrowserActivity` (`MODE_PICK`) via a `registerForActivityResult` launcher.
- **On PICK result** (`RESULT_TEMPLATE_ID`):
  - `""` → `applyTemplateToCurrentPage("", null)` (Blank).
  - non-empty (library `TemplateObject` id) → on IO: `repository.getTemplate(id)`, parse
    `TemplateObject`, build a `.soil` `TemplateData(width, height, name=entity.name, image)` and
    **insert a new `type="template"` row** into the `.soil` (new helper
    `insertLibraryTemplateIntoSoil(...)` — model after the removed `insertTemplateFromFile`, returning
    `Pair<soilRowId, Bitmap?>`), then `applyTemplateToCurrentPage(soilRowId, bitmap)` on Main.
  - Decode the full bitmap via `core/BitmapDecode.decodeSampled` (bounded), not raw
    `decodeByteArray`.
- Remove the now-dead `templateImportLauncher`, `performTemplateImport`, `copyUriToFile`,
  `onTemplateImportDone`, and `sanitizeTemplateFileName` **from NotebookActivity** — but first
  confirm `sanitizeTemplateFileName`'s logic has been relocated/reused by the browser's import
  (Session 2). Do not delete the only copy of that defense; the browser must have its own.

**`TemplateBrowserActivity` (MODE_PICK):**
- Tapping a template → set result `RESULT_TEMPLATE_ID = templateObjectId`, `finish()`.
- Provide a **Blank** virtual card at root that returns `RESULT_TEMPLATE_ID = ""`.
- Import / new-folder / search / sort all remain available in PICK (user may organize while picking).
- Long-press management actions: **omit in PICK mode** (management lives in MANAGE) — keep PICK focused.

**Definition of done:** Inside a notebook, the template button shows Blank + in-notebook templates +
Browse Library…; browsing and picking a library template applies it to the current page and persists
it (survives page reload), and new pages inherit it (existing inheritance path unchanged). Build +
install on G10.

---

### Session 6 — Full-screen New Notebook flow + first-page template seeding

**Objective:** Replace the New Notebook AlertDialog with a full-screen flow that collects the name
**and** lets the user pick a template (default Blank) for the first page, confirmed with **CREATE**.

**Files:** `MainActivity.kt`, `TemplateBrowserActivity.kt`, `activity_template_browser.xml`.

**Flow:**
- `MainActivity.showNewNotebookDialog` → instead launch `TemplateBrowserActivity` with
  `MODE_PICK` + `EXTRA_COLLECT_NAME=true` + `EXTRA_TITLE="New Notebook"`, via a result launcher.
- In `EXTRA_COLLECT_NAME` mode the browser shows:
  - a **name field** at the top, pre-filled with the `yyyyMMdd_HHmmss` timestamp (match current
    default; editable), and
  - the template browser below (Blank default selected), and
  - a **CREATE** button (enabled always; name validated on tap).
  - Selecting a template sets the pending selection and visibly marks it; **CREATE** confirms.
  - Name is validated by the **same rules as `validateNotebookName`** — but the browser can't see
    `MainActivity`'s folder. So: do name **format** validation in the browser (whitelist, `.`/`..`,
    non-empty); do the **duplicate-in-target-folder** check back in `MainActivity` after result
    (it knows `currentParentId`). On dup, Toast + do not create (the user is returned to MainActivity;
    acceptable — or re-open the flow. Keep simple: Toast and abort).
  - Result: `RESULT_NOTEBOOK_NAME` + `RESULT_TEMPLATE_ID` (`""` = Blank).
- `MainActivity` on result → `createNotebook(name, libraryTemplateId)`:
  - Extend `createNotebook` to accept an optional `libraryTemplateId: String = ""`.
  - During `.soil` creation, **after** creating the schema and **before/at** first-page insertion:
    if `libraryTemplateId` non-empty, load `repository.getTemplate(id)` → `TemplateObject`, insert a
    `type="template"` row into the new `.soil` (same `TemplateData` shape as Session 5's helper —
    factor a shared helper or replicate), capture its new soil row id, and set the **first page's**
    `data.template = soilTemplateRowId` (instead of `""`). All on `Dispatchers.IO`, within the
    existing creation coroutine.
  - Everything else about notebook creation is unchanged (UUID filename, index entry, open notebook).

**Definition of done:** Tapping New Notebook opens the full-screen flow; user names it, optionally
picks a template, taps CREATE; the notebook opens with the first page showing the chosen template
(or blank). Build + install on G10.

---

### Session 7 — Wrap-up

**Objective:** Documentation, dead-code removal, cross-device QA, and the separate migration task.

**Tasks:**
1. **Docs:**
   - Rewrite the **Template System** section of `docs/drawing-engine.md` (~118–131) to describe the
     new global-index model, `TemplateBrowserActivity` modes, and the `.soil` copy-on-apply flow.
   - Add a **Templates** subsection to `docs/data-architecture.md` (object types `template` /
     `template_folder`, `TemplateObject` payload, base64-in-`data`, recursion/soft-delete rules).
   - Update `docs/mainactivity-and-recents.md` for the new toolbar button + New Notebook flow.
   - Update the `CLAUDE.md` docs table if any doc scope changed.
2. **Dead-code removal:** confirm no remaining references to `getExternalFilesDir("Templates")`, the
   old PNG library, or removed `NotebookActivity` import members. Remove now-unused imports/strings.
3. **Cross-device QA (Tier 1):** build + install on Go 10.3 (G10), Note Max (MAX), Go 7 (G7),
   Palma2 Pro (P2P); smoke-test browse/import/preview/manage/pick/new-notebook. Lead runs installs in
   one shell block. _(Deferred at user request — clean `assembleDebug` verified; on-device QA skipped
   for this commit. Features were validated on G10 incrementally across S2–S6.)_
4. **Migration (separate, on user request):** The app ships **fresh** (no in-app migration). When the
   user asks, the lead runs a **Haiku subagent** to migrate the user's existing device templates: pull
   `notesprout.db` and the old `Templates/` PNGs from the device, insert `template` objects (base64)
   at root (or an "Imported" template folder), push back. This is an **operational task, not app
   code**. **The full step-by-step runbook (debug + stable builds) lives in
   [`TEMPLATE_MIGRATION_RUNBOOK.md`](TEMPLATE_MIGRATION_RUNBOOK.md)** — hand a Haiku session that file.
   Record each run (date, build, device, template count) at the bottom of this item when performed.
   - _Runs log:_ (none yet)
5. **Anything discovered** during Sessions 1–6 that needs a home.

**Definition of done:** Docs updated; no dead references; Tier-1 devices smoke-tested. Commit (no push).

---

## 5. Open Questions / Notes (fill in as we go)

- **`activity_main.xml` has three layout variants** (`layout/`, `layout-sw360dp/`, `layout-sw600dp/`)
  that must stay in sync. Any new toolbar button must be added to **all three** or the generated view
  binding field becomes nullable. (Discovered S2 — `btnTemplates` added to all three.)
- **`applyPickerModeUI` was missing `btnNewFolder` visibility handling** (pre-existing gap — it only
  toggled `btnNewNotebook`). Fixed in S2 alongside adding `btnTemplates` so picker mode hides all
  three action buttons consistently.
- Search mode (`enterSearchMode`/`exitSearchMode`) does **not** hide `btnNewFolder`/`btnNewNotebook`,
  but per the S2 spec `btnTemplates` **is** hidden in search mode. Existing button behavior left
  unchanged.
- **S3 picker is a local sealed class** (`TemplatePicker` inside `TemplateBrowserActivity`) — the
  shared `ui/DestinationPickerState` was intentionally **not** extended with template variants. A
  dedicated `pickerToolbar` + `pickerToolbarDivider` (`gone` by default) was added to
  `activity_template_browser.xml`, mirroring MainActivity's picker bar (import/new-folder buttons
  hide while picking). `isSelfOrDescendant` walks ancestors via `repository.getTemplate(id)`
  (`dao.getById` is type-agnostic). (Discovered S3.)
- **S7 cleanup nit:** `confirmPickerDestination` has two unused locals (`isCopyTemplate`,
  `isCopyFolder`) left over from the conflict branch — harmless, remove during the S7 dead-code pass.
- **S4 shared-component params (non-breaking):** `SortPreferencesManager.load/save` gained a trailing
  `prefsName` arg (default = notebook key) + a `TEMPLATE_PREFS_NAME` const, so template sort persists
  to `notesprout_template_sort_prefs` and never clobbers notebook sort. `SortDialog` gained an
  `itemNoun` param (before `onApply` so the trailing-lambda call site still compiles) that relabels
  the header → "Folders & Templates" and the radio → "Templates first" (`FolderSort.NOTEBOOKS_FIRST`
  enum value unchanged). `SearchDialog.show` gained a `hint` param. `SearchEngine` gained public
  `scoreName` + `searchTemplates` (mirrors notebook `search`, rooted at "Templates"); `scoreName` is
  currently unused (kept as a small public helper). New `IndexRepository.getAllTemplateFolders()`.
- **S4 search scope:** long-press management is disabled while in search mode (kept focused);
  move/copy picker hides Search/Sort. Picker can't be entered from search, so `btnClearSearch` is not
  re-shown on picker exit. (Discovered S4.)
- **S5 `TemplateDialog` slimmed to single-view:** the `notebookId` constructor param was removed
  (only the deleted `insertTemplateFromFile` used it); the dialog's only call site was
  `NotebookActivity.btnTemplate`. `onRequestImport` → `onBrowseLibrary: () -> Unit`. The in-notebook
  apply path (`db.notebookDao().getTemplatesSorted()` + tap-to-apply) is unchanged. (Discovered S5.)
- **S5 library apply path:** `NotebookActivity.insertLibraryTemplateIntoSoil(libraryTemplateId, db):
  Pair<String, Bitmap?>` copies a library `TemplateObject` into the open `.soil` as a new
  `type="template"` row (decodes via `core/BitmapDecode.decodeSampled`, bounded), then
  `applyTemplateToCurrentPage(soilRowId, bitmap)`. Launcher is `templatePickLauncher`
  (`StartActivityForResult`) → `onTemplatePicked()`. Removed dead PNG-import machinery from
  `NotebookActivity` (`templateImportLauncher`, `performTemplateImport`, `copyUriToFile`,
  `onTemplateImportDone`, `sanitizeTemplateFileName`) — the browser keeps its own sanitize copy
  (`TemplateBrowserActivity.sanitizeTemplateNameFromFile`). (Discovered S5.)
- **S5 PICK Blank card:** the virtual **Blank** card (`RESULT_TEMPLATE_ID = ""`) is injected as grid
  slot 0 only at root (`directoryStack.size <= 1`), and only outside copy/move picker and search.
  `totalGridPagesWithBlank()` duplicates that `showBlank` condition from `render()` for pagination —
  **keep the two in sync**; candidate to factor into a computed property in the S7 cleanup pass.
- **S5 user-facing label:** the in-notebook title-bar button reads **"Browse Templates…"** (not
  "Browse Library…") per user request. (Discovered S5.)
- **S6 new extra `EXTRA_TARGET_PARENT_ID`:** the spec put the duplicate-name check back in
  `MainActivity` after the result. In practice that dismissed the flow on a collision with no way to
  fix the name. Resolved by passing the target folder id to the browser via a new
  `EXTRA_TARGET_PARENT_ID` extra and doing the **dup check inside `confirmCreate`** (suspend, on IO)
  — a collision now Toasts and keeps the user on the screen. `MainActivity`'s post-result dup check
  is retained as a harmless safety net. (Discovered S6, per user feedback.)
- **S6 selection-marker styling:** the selected template/Blank cell mirrors `TemplateDialog`'s
  selected cell — a thin **1dp `shape_bordered`** border around the WHOLE cell (thumbnail + label),
  set on the card `group`, NOT a thick `bg_page_card_current` border on the thumbnail alone. Because
  the browser grid uses fixed-pixel cells (unlike the dialog's weighted cells), a constant 6dp gap is
  reserved in collectName mode and the inner card shrunk by `2*gap`, so the cell footprint is
  identical selected-or-not and the grid never shifts. Non-collectName modes pass `gapPx = 0` →
  unchanged. (Discovered S6, two rounds of user feedback.)
- **S6 `.soil` seeding:** `createNotebook(name, libraryTemplateId = "")` loads the library
  `TemplateObject` (suspend, before opening the `.soil`) into a method-local `SeedTemplate`, then —
  inside the existing raw-`SQLiteDatabase` block — inserts a `type="template"` row (parentId = the
  in-soil notebook row id, matching `NotebookActivity.insertLibraryTemplateIntoSoil`) and points the
  first page's `data.template` at it. All within the existing creation coroutine. (Discovered S6.)

---

## 6. Phase 2 Growth — Pinned · Recent · Toolbar · Save-as-Template

> **Growth / New Branch:** Four enhancements discovered after Phase 1 shipped:
> **(1) Pinned templates** — same concept as pinned notebooks, in the template **manager** and the
> PICK **selectors** (new-notebook + in-notebook), card grid + pagination.
> **(2) Recent templates** — same concept as recent notebooks, in the PICK **selectors** only, card
> grid + pagination.
> **(3) Toolbar relocation** — move **search, sort, pinned, recent** from the bottom toolbar to a
> **top** action bar on every template screen; **New Template Folder** and **Import (new template)**
> stay in the bottom bar.
> **(4) "Save as Template"** — export a page directly into the template library: a new option in the
> page-index export sheet, beside **Share** and **Save to device**, that opens the template browser
> to pick a destination folder.

### 6.0 Workflow (same as §0)

One session at a time, in order. A **Sonnet subagent implements** each session from its spec (no
design decisions of its own — everything is locked here). The lead (Opus) does a clean
`assembleDebug`, installs on **G10** (`34E517F9`), and on the user's confirmation updates the §1
status table + this section, then **commits per session — does NOT push**. Fixes during a session are
made by the lead, not a subagent. A **wrap-up** (Session 13) captures discoveries.

### 6.1 Locked Architecture Decisions (Phase 2) — final, do not deviate

**P1. Pinned templates use a dedicated index list — never reuse the notebook pinned list.**
- New constant in `data/index/ListIds.kt`:
  `const val PINNED_TEMPLATES_LIST_ID = "00000000-0000-0000-0000-746d706c7069"`.
- New serializable `data/index/TemplateListObject.kt`:
  ```kotlin
  @Serializable
  data class TemplateListObject(val templateIds: List<String> = emptyList())
  ```
  A parallel to `ListObject` so notebook list code is **untouched** and the member type is explicit.
  The pinned-templates list is a `type = LIST` `ObjectEntity` (name `"Pinned Templates"`,
  `parentId = null`), `data` = `TemplateListObject` JSON.
- **Only templates are pinnable — never template folders** (mirrors notebooks: folders aren't pinned).
- Pin order is the `templateIds` list order (newest pin appended last; render in list order, which
  matches MainActivity pinned ordering). No drag-reorder this phase.

**P2. Recents are device-local, library-template-only, separate from notebook recents.**
- New `data/recents/TemplateRecentsManager.kt` mirroring `RecentsManager` exactly, but: prefs name
  `notesprout_template_recents`, resolves against `ObjectType.TEMPLATE`, breadcrumb root label
  `"Templates"`. New `TemplateRecentEntry(templateId, timestamp)` and `ResolvedTemplateRecent(
  templateId, templateName, folderPath, timestamp)`. `MAX_ENTRIES = 20`. Self-healing prune on
  `resolve` (skip missing/deleted, persist the pruned list).
- **Recorded only when a *library* template is actually used on a page:** the in-notebook PICK apply
  path and the New Notebook seed path (both consume a library `TemplateObject` id). Blank (`""`)
  records nothing. The slim dialog's `.soil` "in this notebook" quick-pick uses `.soil` row ids, **not**
  library ids → never recorded. **Importing or "Save as Template" does not record** (creation ≠ use).

**P3. The pinned/recent surfaces reuse the existing browser card grid + pagination.** No new grid
engine. They are alternate "view modes" of `TemplateBrowserActivity` toggled by top-bar buttons,
exactly like MainActivity's `enterPinnedMode` / `enterRecentsMode` swap the breadcrumb area for a
pinned/recents toolbar and re-render the same grid container.
- **MANAGE:** pinned view available (recents is **not** — recents is a selector convenience only).
- **PICK** (new-notebook `EXTRA_COLLECT_NAME` + in-notebook "Browse Templates…"): **both** pinned and
  recents views available. Tapping a card in PICK selects it (returns `RESULT_TEMPLATE_ID`), same as a
  normal grid tap. The **Blank** card belongs to the root browse view only — not pinned/recents.
- Pinned/recents views show **templates only** (no folders, no breadcrumb navigation): a flat,
  paginated card grid + an empty state. Long-press management is **MANAGE-only** and applies in the
  pinned view too (pin/unpin, rename, etc., as in the browse grid); **omitted in PICK** (consistent
  with §2 / S5).

**P4. Toolbar layout (final end-state, all template screens).**
- **Top action bar** (new, mirrors MainActivity's breadcrumb-bar button cluster): **Search**,
  **Sort**, **Pinned**, **Recents**. `btnRecents` is **GONE in MANAGE** (selector-only), VISIBLE in
  PICK. `btnPinned` visible in both MANAGE and PICK.
- **Bottom bar:** pagination (unchanged) on the left; **New Template Folder** + **Import** on the
  right. Search/Sort/Pinned/Recents are **removed** from the bottom bar.
- Icons: `ic_search` / `ic_x` (clear) / `ic_filter` (sort) / `ic_pinned` (+ `ic_pinned_off` for the
  unpin action-sheet item) / `ic_clock` (recents) — reuse the MainActivity set; **add no new
  drawables**.

**P5. "Save as Template" hands the browser a file path, not a base64 blob.** A full-page PNG base64 in
an Intent risks `TransactionTooLargeException`. The page-index export already writes a PNG to disk
(`NotebookExporter.exportPage`); pass **that file's path** + a default name to the browser via a new
`MODE_SAVE_TARGET`. The browser navigates **folders only** (like the move/copy picker), and on
**"Save Here"** prompts for a name (default = page heading / `"Page N"`, sanitized), then calls
`repository.createTemplate(name, destFolderId, w, h, base64)` itself (it owns the repository). No
result is returned to the page index beyond success/cancel.

**P6. e-ink design system + standard constraints from `CLAUDE.md` apply everywhere** (no color /
elevation / ripple / animation; 1dp inkBlack borders; `Slog.d`; `kotlinx.serialization`; no Material;
no new Gradle deps). All index writes go through `IndexRepository`.

### 6.2 Reference Map additions (Phase 2)

- **Pinned notebooks (mirror for pinned templates):** `IndexRepository` — `ensurePinnedListExists`
  (240), `togglePin` (318), `isNotebookPinned` (308), `getNotebooksInList` (299),
  `scrubNotebookFromAllLists` (333); `ListObject.kt`; `ListIds.kt` (`PINNED_LIST_ID`).
- **Pinned UI (mirror for pinned/recents views):** `MainActivity` — `enterPinnedMode` (403),
  `exitPinnedMode` (417), `applyPinnedModeUI` (426), `renderPinnedList` (453); `enterRecentsMode`
  (486) + recents render; the `pinnedToolbar` / `pinnedToolbarDivider` / `recents*` views in
  `activity_main.xml`; pin/unpin in `showNotebookContextMenu`.
- **Recents (mirror for template recents):** `data/recents/RecentsManager.kt`, `RecentEntry.kt`,
  `ResolvedRecent.kt`; record sites = `MainActivity`/`NotebookActivity` open/close calls.
- **Top button cluster styling:** `activity_main.xml` breadcrumb bar (~199–229) — `btnPinned`
  (`ic_pinned`), `btnSearch` (`ic_search`), `btnClearSearch` (`ic_x`), `btnRecents` (`ic_clock`),
  `btnSort` (`ic_filter`).
- **Page-index export sheet (Save-as-Template host):** `PageIndexActivity` — `executeExport` (708),
  `showExportChoice` (752, the Save-to-device/Share `AlertDialog`); `NotebookExporter.exportPage`.
- **Template browser internals already built (Phase 1):** `TemplateBrowserActivity` modes
  (`MODE_MANAGE` / `MODE_PICK`), `showBlankCard`, `render()`, `totalGridPagesWithBlank()`, the local
  `TemplatePicker` sealed class + `pickerToolbar`, `sanitizeTemplateNameFromFile`,
  `IndexRepository.createTemplate / getTemplate / getAllTemplates / getAllTemplateFolders`.
- **Note:** `activity_template_browser.xml` has a **single** layout variant (no `sw360dp`/`sw600dp`
  copies, unlike `activity_main.xml`) — new buttons are added once.

---

### Session 8 — Pinned templates: repository + MANAGE pin/unpin + pinned view

**Objective:** Templates can be pinned/unpinned from the MANAGE action sheet, and a **Pinned** view in
`TemplateBrowserActivity` lists them as a paginated card grid. Mirrors pinned notebooks.

**Files:** `data/index/ListIds.kt`, `data/index/TemplateListObject.kt` *(new)*,
`data/index/IndexRepository.kt`, `TemplateBrowserActivity.kt`, `res/layout/activity_template_browser.xml`.

**Repository (`// region Template pin operations`):**
- `suspend fun ensurePinnedTemplatesListExists()` — bootstrap the `PINNED_TEMPLATES_LIST_ID` LIST
  object (name `"Pinned Templates"`) if absent/deleted (mirror `ensurePinnedListExists`). Call it from
  the same place `ensurePinnedListExists` is called at app/index init.
- `suspend fun isTemplatePinned(templateId: String): Boolean`
- `suspend fun toggleTemplatePin(templateId: String): Boolean` — returns new pinned state (mirror
  `togglePin`, backed by `TemplateListObject.templateIds`).
- `suspend fun getPinnedTemplates(): List<ObjectEntity>` — resolve `templateIds` → entities, skip
  null/`deletedAt`/non-`TEMPLATE` (mirror `getNotebooksInList`).
- `suspend fun scrubTemplateFromPinned(templateId: String)` — remove from the list (mirror
  `scrubNotebookFromAllLists`, single list). **Call this from `softDeleteTemplate`** so deleting a
  pinned template also unpins it.

**Action sheet (MANAGE, `TemplateBrowserActivity`):** in the **template** long-press
`ActionSheetDialog`, add a toggle item **above Delete**:
- if pinned → `ic_pinned_off` **"Unpin Template"**; else → `ic_pinned` **"Pin Template"**.
- On tap: `repository.toggleTemplatePin(id)`, Toast (`"Pinned"` / `"Unpinned"`), re-render the active
  view. (Resolve current pin state with `isTemplatePinned` when building the sheet.)
- **Template *folders* get no pin item.**

**Pinned view:**
- Add `btnPinned` (`ic_pinned`) to the bottom `actionButtonsGroup` for now — **next to `btnSort`**
  (Session 11 relocates the whole cluster to the top). Add a `pinnedToolbar` + `pinnedToolbarDivider`
  to the layout (mirror MainActivity's: a title `"Pinned Templates"` + a cancel/back button), shown
  only in pinned view.
- `enterPinnedView()` / `exitPinnedView()` (mirror `enterPinnedMode`/`exitPinnedMode`): set an
  `isPinnedView` flag, reset to page 0, swap the breadcrumb bar for the `pinnedToolbar`, hide
  New-Folder/Import/Search/Sort while in pinned view, re-render.
- Render: `repository.getPinnedTemplates()` as a flat paginated **template-card** grid (reuse the
  existing card builder + pagination; no folder cards, no breadcrumb). Empty state:
  `"No pinned templates yet."`
- **MANAGE:** tapping a pinned card = full-screen preview; long-press = the same action sheet
  (including Unpin). Pagination uses the existing controls.
- **Pinned view is available in MANAGE this session.** (PICK wiring is Session 10 — but write
  `enterPinnedView` mode-agnostic so S10 can reuse it; just keep the button hidden in PICK until S10.)

**Edge cases:** unpinning the last pinned template → empty state; deleting a pinned template (via the
sheet) re-renders without it (scrub already ran); a pinned template that becomes deleted elsewhere is
filtered by `getPinnedTemplates`.

**Definition of done:** In MANAGE, long-press a template → Pin; the Pinned button opens a paginated
grid of pinned templates; Unpin removes it; deleting a pinned template unpins it. Build + install G10.

---

### Session 9 — Recent templates: device-local store + use-tracking

**Objective:** Add a device-local "recently used library templates" store and record a use whenever a
**library** template is *selected from the library* — i.e. picked via the in-notebook "Browse
Templates…" library browser, or seeded into a new notebook. **No UI this session** (the recents view
is Session 10) — this is the data + tracking layer, kept isolated.

**"Recents" means recently *selected from the library*, NOT recently *added*.** A "use" is recorded
only at the two library-selection points below. Explicitly **excluded** (these do **not** record a
use): re-applying a template **already in the notebook** via `TemplateDialog.onConfirm` →
`applyTemplateToCurrentPage(...)` (this path never touches `insertLibraryTemplateIntoSoil`), and the
**Blank** selection (empty `RESULT_TEMPLATE_ID`).

**Files:** `data/recents/TemplateRecentEntry.kt` *(new)*, `data/recents/ResolvedTemplateRecent.kt`
*(new)*, `data/recents/TemplateRecentsManager.kt` *(new)*, `NotebookActivity.kt`, `MainActivity.kt`.

**Store (mirror `RecentsManager` / `RecentEntry` / `ResolvedRecent` per §6.1 P2):**
- `TemplateRecentEntry(templateId: String, timestamp: Long)` (`@Serializable`).
- `ResolvedTemplateRecent(templateId, templateName, folderPath, timestamp)`.
- `TemplateRecentsManager` object: `PREFS_NAME = "notesprout_template_recents"`, `MAX_ENTRIES = 20`;
  `load`, `recordUse(context, templateId)` (drop existing, prepend now, cap), `remove`, and
  `suspend fun resolve(context): List<ResolvedTemplateRecent>` resolving against `ObjectType.TEMPLATE`
  via `IndexRepository`/`getAllTemplateFolders` for the breadcrumb (root label `"Templates"`),
  self-healing prune of missing/deleted. (A single `recordUse` covers both open and re-use; no
  separate close hook — templates aren't "closed".)

**Tracking (record points — library template id only, never Blank; exactly two sites):**
- `NotebookActivity` — **only** the library-browse PICK-result apply path (`onTemplatePicked` →
  `insertLibraryTemplateIntoSoil`): after a successful apply of a **non-empty** `RESULT_TEMPLATE_ID`,
  call `TemplateRecentsManager.recordUse(this, libraryTemplateId)`. ⚠️ Do **not** add a record call to
  `applyTemplateToCurrentPage` or `TemplateDialog.onConfirm` — those cover in-notebook re-apply of an
  existing template and are deliberately excluded from recents.
- `MainActivity` — in `createNotebook(name, libraryTemplateId)`: if `libraryTemplateId` is non-empty,
  call `TemplateRecentsManager.recordUse(this, libraryTemplateId)` after the notebook is created.
- **Cleanup:** in `IndexRepository.softDeleteTemplate`, recents can't be scrubbed (no `Context` in the
  repo) — instead rely on `resolve`'s self-healing prune (deleted templates never surface). Do **not**
  add a Context to the repository.

**Definition of done:** Applying a library template in a notebook and creating a notebook from a
template both write a recents entry (verify via a temporary `Slog.d`, or defer visible verification to
S10). App builds; no behavior change visible yet. Build + install G10. (Lead verifies via S10.)

---

### Session 10 — Pinned & recent in the PICK selectors

**Objective:** Surface **Pinned** and **Recents** views inside `TemplateBrowserActivity` **PICK** mode
so both the New Notebook flow (`EXTRA_COLLECT_NAME`) and the in-notebook "Browse Templates…" selector
expose them. Tapping a card selects it. Card grid + pagination (reuses S8's pinned view + the existing
grid).

**Files:** `TemplateBrowserActivity.kt`, `res/layout/activity_template_browser.xml`.

**Behaviors:**
- Make `btnPinned` (added S8) **visible in PICK** too, and add `btnRecents` (`ic_clock`) to the bottom
  `actionButtonsGroup` (S11 relocates the cluster). `btnRecents` is **PICK-only** (GONE in MANAGE).
- **Pinned view in PICK:** reuse `enterPinnedView()` from S8. In PICK, tapping a pinned card sets
  `RESULT_TEMPLATE_ID = id` and `finish()` (same as a normal PICK grid tap); long-press is omitted
  (PICK has no management). Selection-marker styling in `EXTRA_COLLECT_NAME` mode mirrors the S6 grid
  (1dp `shape_bordered` around the whole cell, fixed footprint) — but since pinned/recents tap-selects
  **and finishes immediately** (no CREATE deferral), no persistent marker is needed; **for the
  New-Notebook flow keep the existing "tap sets pending selection + CREATE confirms" behavior** —
  i.e., tapping a pinned/recents card sets the pending `RESULT_TEMPLATE_ID`, marks it, and returns the
  user to the name+CREATE context (do **not** auto-finish in `EXTRA_COLLECT_NAME`). In the
  non-collect-name in-notebook PICK, tap = select + finish.
  > **Locked rule:** tap behavior in pinned/recents views = identical to the **root browse grid** for
  > the current mode. (collectName → set pending + mark + stay; plain PICK → select + finish.)
- **Recents view (PICK only):** `enterRecentsView()` / `exitRecentsView()` mirroring pinned;
  `TemplateRecentsManager.resolve(this)` → flat paginated card grid (decode each template's thumbnail
  from `repository.getTemplate(id)`), newest-first. Empty state `"No recent templates yet."` Add a
  `recentsToolbar` + divider (title `"Recent Templates"` + back).
- Pinned and recents are **mutually exclusive** view modes and exclusive with search (entering one
  exits the others — mirror `clearRecentsMode()` in `enterPinnedMode`).
- Import / New-Folder / Search / Sort stay available in PICK root browse; while in pinned/recents view
  those buttons hide (like MANAGE pinned view).

**Definition of done:** From the in-notebook "Browse Templates…" and the New Notebook flow, Pinned and
Recents buttons open paginated card grids; picking from either applies/seeds the template (and, being a
use, shows up in Recents next time). Build + install G10.

---

### Session 11 — Toolbar relocation: search/sort/pinned/recent → top bar

**Objective:** Move **Search, Sort, Pinned, Recents** from the bottom `actionButtonsGroup` to a new
**top action bar** in `TemplateBrowserActivity`, matching MainActivity's top button cluster. **New
Template Folder** and **Import** stay in the bottom bar with pagination. Pure layout + wiring reorg —
**no behavior change.**

**Files:** `res/layout/activity_template_browser.xml`, `TemplateBrowserActivity.kt`.

**Layout:**
- In the **top bar** (`topBar`, the row with `btnBack` + title), add a right-aligned button group
  (after the title, mirror `activity_main.xml` ~199–229): `btnSearch` (`ic_search`), `btnClearSearch`
  (`ic_x`, gone), `btnSort` (`ic_filter`), `btnPinned` (`ic_pinned`), `btnRecents` (`ic_clock`). Use
  `Widget.Notesprout.ToolbarButton`. Keep the existing ids so listeners need minimal change.
- **Remove** those five buttons from the bottom `actionButtonsGroup`; it now holds only
  `btnNewTemplateFolder` + `btnImport`.
- The breadcrumb bar, pinned/recents/picker toolbars, grid, and pagination are unchanged.

**Wiring:**
- Update every visibility toggle that referenced these buttons (browse vs pinned vs recents vs search
  vs picker vs collectName modes) to the new locations — audit `applyPinnedModeUI`-equivalent,
  `enterRecentsView`, search mode, and picker mode handlers. **Mode rules unchanged** from S8–S10:
  `btnRecents` GONE in MANAGE; pinned/recents/search hide the other action buttons while active;
  picker hides Search/Sort/Pinned/Recents.
- The `btnClearSearch` inline-search affordance moves with `btnSearch` to the top bar (mirror
  MainActivity, where both live in the breadcrumb bar).

**Definition of done:** On every template screen (MANAGE + both PICK entry points) Search/Sort/Pinned/
Recents sit in the top bar; New-Folder/Import remain bottom; all four toggles behave exactly as before
in every mode. Build + install G10.

---

### Session 12 — "Save as Template" from the page index

**Objective:** Add a **"Save as Template"** option to the page-index export sheet (beside Share / Save
to device). It exports the page PNG, then opens `TemplateBrowserActivity` in a new **`MODE_SAVE_TARGET`**
to pick a destination folder and name, and creates the library template there.

**Files:** `PageIndexActivity.kt`, `TemplateBrowserActivity.kt`,
`res/layout/activity_template_browser.xml` (a save-target confirm toolbar; or reuse `pickerToolbar`).

**Page index:**
- In `showExportChoice(file)` add a **neutral** button **"Save as Template"**. On tap: launch
  `TemplateBrowserActivity` via a `registerForActivityResult` launcher with:
  - `EXTRA_MODE = MODE_SAVE_TARGET`
  - `EXTRA_SAVE_SOURCE_PATH = file.absolutePath` (the exported PNG)
  - `EXTRA_SAVE_DEFAULT_NAME` = page heading or `"Page N"`, pre-sanitized.
  - `EXTRA_TITLE = "Save as Template"`.
- `executeExport` already produces the PNG and calls `showExportChoice`; no change to export itself.
  (The exported file lives in cache/export dir — fine as a transient source; the browser copies its
  bytes into the index, so the file isn't needed afterward.)
- On result `RESULT_OK`: Toast `"Saved to Templates"`. On cancel: no-op.

**`TemplateBrowserActivity` — `MODE_SAVE_TARGET`:**
- Add `MODE_SAVE_TARGET = 2` + the new extras to the companion.
- Behaves like the **move/copy destination picker** (folders only, breadcrumb navigation, no template
  cards as targets), but cross-launched: show a confirm toolbar (reuse/clone `pickerToolbar`) titled
  `"Choose a folder"` with **"Save Here"** + **Cancel**.
- **Save Here:** prompt for a name (rename-style dialog; default = `EXTRA_SAVE_DEFAULT_NAME`; validate
  whitelist/`.`/`..`/non-empty; dup-check templates in the chosen folder → append ` (2)`… on
  collision, same as Import). Then on IO: read the PNG at `EXTRA_SAVE_SOURCE_PATH`, decode bounded
  (`core/BitmapDecode.decodeSampled`) for width/height, base64 (`NO_WRAP`), and
  `repository.createTemplate(name, currentFolderId, w, h, base64)`. `setResult(RESULT_OK)`, `finish()`.
- **Cancel:** `finish()` with no result.
- New-Folder is **available** while choosing (user may create a destination folder); Import / Search /
  Sort / Pinned / Recents are hidden in `MODE_SAVE_TARGET`. No long-press management.

**Definition of done:** Long-press a page → Export → **Save as Template** → pick/navigate to a folder
(optionally create one) → name it → the page appears as a template in that folder (verify in MANAGE).
Build + install G10.

---

### Session 13 — Phase 2 wrap-up

**Objective:** Docs, dead-code, cross-device QA for the Phase 2 features.

**Tasks:**
1. **Docs:**
   - `docs/data-architecture.md` — document the **pinned-templates list** (`PINNED_TEMPLATES_LIST_ID`,
     `TemplateListObject`) and the **template recents** store (`notesprout_template_recents`,
     device-local, library-only, self-healing).
   - `docs/drawing-engine.md` and/or `docs/mainactivity-and-recents.md` — pinned/recents views in the
     template browser, the relocated top toolbar, and the page→template "Save as Template" flow.
   - Update the `CLAUDE.md` docs table if any scope changed.
2. **Dead-code:** confirm no leftover bottom-bar references to the relocated buttons; remove unused
   imports/strings.
3. **Cross-device QA (Tier 1):** build + install on G10 / MAX / G7 / P2P in one shell block; smoke
   pin/unpin, pinned & recents in both selectors, toolbar layout, Save-as-Template. (Defer per user
   request if instructed, as in S7.)
4. **Anything discovered** during Sessions 8–12 that needs a home (append to §6.3 below).

**Definition of done:** Docs updated; no dead references; Tier-1 smoke-tested (or deferred per user).
Commit (no push).

### 6.3 Phase 2 Open Questions / Notes (fill in as we go)

- **S8 pinned-templates store:** mirrors the notebook pinned list exactly. New
  `PINNED_TEMPLATES_LIST_ID` (`…746d706c7069` = "tmplpi") + a dedicated `TemplateListObject`
  (`templateIds`) so notebook list code is untouched. `IndexRepository` gained
  `ensurePinnedTemplatesListExists` / `isTemplatePinned` / `toggleTemplatePin` / `getPinnedTemplates`
  / `scrubTemplateFromPinned`; `softDeleteTemplate` now scrubs-then-deletes so deleting a pinned
  template unpins it. Bootstrap launched from `NotesproutApplication` alongside `ensurePinnedListExists`.
- **S8 pinned view (MANAGE-only this session):** `isPinnedView` flag swaps the breadcrumb bar for a
  `pinnedToolbar` ("Pinned Templates" + ✕), hides New-Folder/Import/Search/Sort/Pinned while active,
  and renders `getPinnedTemplates()` as a flat paginated template grid (no folders, no Blank — the
  `showBlankCard` getter gained `&& !isPinnedView`). Empty state "No pinned templates yet." Back press
  exits the view first. `btnPinned` lives in the **bottom** `actionButtonsGroup` next to Sort for now
  and is **GONE in PICK** — Session 10 enables it in PICK, Session 11 relocates the cluster to a top bar.
- **S8 action sheet:** `showTemplateContextMenu` is now `lifecycleScope.launch` (queries
  `isTemplatePinned` on IO) so it can insert a conditional **Pin / Unpin** item (`ic_pinned` /
  `ic_pinned_off`) above Delete. Template *folders* get no pin item. (Discovered S8.)
- **S9 template recents store (no UI):** new `data/recents/TemplateRecentEntry`,
  `ResolvedTemplateRecent`, and `TemplateRecentsManager` — exact mirrors of the notebook
  `RecentEntry` / `ResolvedRecent` / `RecentsManager`, but prefs `notesprout_template_recents`,
  `MAX_ENTRIES = 20`, breadcrumb root `"Templates"`, resolving against `ObjectType.TEMPLATE` via
  `getTemplate` + `getAllTemplateFolders`, self-healing prune in `resolve`. A single
  `recordUse(context, templateId)` (no open/close split — templates aren't "closed").
- **S9 use-tracking (exactly two sites, library id only, never Blank):**
  `NotebookActivity.onTemplatePicked` records after a successful library-browse apply (inside the
  `soilRowId.isNotEmpty()` block); `MainActivity.createNotebook` records when `libraryTemplateId`
  is non-empty after the notebook is created. `applyTemplateToCurrentPage` / `TemplateDialog.onConfirm`
  (in-notebook re-apply) are intentionally **not** recorded. No `Context` added to the repo — deleted
  templates never surface thanks to `resolve`'s prune. No visible behavior yet (verified via S10).
