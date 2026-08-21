# Library — Notesprout SN subsystem doc

Phase **R2**. The library is SN's home screen: a paginated card grid of folders and notebooks that
**never scrolls**, plus everything that creates, renames, moves and deletes what is on it.

Fresh code. Paper v0 (`git show 87277da:apps/notesprout_paper/...`) is the shape reference; the
deliberate differences are listed at the end.

---

## Screens

| Screen | Class | Purpose |
|---|---|---|
| Library | `library/LibraryActivity` | Browse, create, rename, move, delete, sort, page |
| New notebook | `library/NewNotebookActivity` | Name + template radio + Create |
| Folder picker | `library/FolderPickerActivity` | The Move destination picker |
| Notebook | `notebook/NotebookActivity` | The drawing surface (R3) — see [`notebook.md`](notebook.md) |

Every one of them opens with `IndexGuard.ready(this)` and takes `TopGuard.applyInsetPadding`.
TopGuard's *guard* is 0 on Ratta (chrome flush at the top edge); the inset pass is still applied
because it is how the bottom bar clears a navigation bar if the device has one.

Identity travels as `EXTRA_NOTEBOOK_ID` + `EXTRA_NOTEBOOK_NAME` — **never a `File`**, never a
passphrase.

---

## Chrome

**Top bar** — the breadcrumb *is* the path. `Notebooks` is the root crumb; each ancestor follows,
separated by ` / `; any crumb jumps straight there. A back arrow appears left of the crumbs once
you are below the root. The debug ⋯ is appended to this bar at runtime (`DebugMenu.install`,
no-op in release).

**Bottom bar** — constant, seven controls plus the pager:

```
[Pinned] [Recents]   |<  <  n / n  >  >|   [Sort] [+Folder] [+Notebook]
```

At the sw720dp tier (62 dp buttons) that is ~614 dp of controls in a 749 dp-wide Nomad portrait
screen — it fits, with the pager taking the slack via `layout_weight`. Every icon button carries a
`contentDescription` and a `TooltipCompat` long-press hint naming it.

**Pinned / Recents are stubs.** They toast "Later"; the modes land in R5 (`BrowseMode` and
`RecentsPrefs` already exist so the pref files do not change shape under a user then).

**Back press** goes up one folder and exits the app at the root.

---

## The grid

`LibraryGrid` renders one page into the layout's `gridContainer`; `GridMath` (pure, JVM-tested)
does the arithmetic:

```
columns      = max(1, floor(containerWidth / library_card_min_width))
cardWidth    = containerWidth / columns          (minus one gap)
cardHeight   = cardWidth × 1.4
rows         = max(1, floor(containerHeight / cardHeight))
cardsPerPage = columns × rows
pageCount    = ceil(total / cardsPerPage)        — never 0; an empty folder is page 1 of 1
```

Measured **once**, against the container's real width and height, in a global-layout listener that
waits for a non-zero size. `library_card_min_width` is a tier dimen: **140 dp base, 200 dp at
sw720dp**. On a Nomad (1404 × 1872, density 1.875) that gives 3 columns × 2 rows = 6 cards a page.

The pager is hidden with `INVISIBLE`, not `GONE`, when there is one page — its slot must not
collapse and shuffle the rest of the bar.

**Empty-state trap:** `emptyState` is a sibling of the grid inside `gridContainer`. `bind()` removes
only the `GridLayout` it added last. A `removeAllViews()` there would delete the empty message and
no folder would ever look empty again once a card had rendered.

### Cards

- **Folder** — Tabler folder icon + name, centred.
- **Notebook** — cover image, name, last-modified. The date line uses
  `android.text.format.DateFormat.getMediumDateFormat` + `getTimeFormat`, so it follows the
  device's own locale conventions rather than a hand-rolled pattern. Secondary text stays inkBlack
  and gets *smaller*; `inkLight` is reserved for text meant not to be read.
- **No cover yet** → a small render of the notebook's own template kind
  (`BuiltInTemplates.placeholder`), squeezed to a fixed 12 rows so a 3 cm card still reads as
  "lined" / "dotted" / "grid". A Blank notebook shows a blank card, which is the honest picture.

**Covers are lazy, one page at a time.** The DAO listing is blob-free (`ObjectSummary` has no
`blob` column); `bindCurrentPage()` reads `repo.cover(id)` only for the notebooks in the visible
slice, memoised in `coverCache` for the life of the listing and cleared on every `refresh()` so an
edited notebook picks up its new cover. Several binds can be in flight at once (a page tap racing
`onResume`), so blobs are fetched into a *local* map on IO and merged into `coverCache` back on
Main — the shared map is only ever written single-threaded. The page label is rendered **after**
the bind, so "n / N" can never name a page before its cards are on screen.

Tap a folder to enter it, a notebook to open it. Long-press either for the action sheet:
**Rename · Move · Delete**. (Pin joins it in R5.)

---

## Naming

`NameRules` (pure, JVM-tested) is the single answer to "may it be called that":

- non-empty after trim,
- not `.` and not `..`,
- characters from `[a-zA-Z0-9_\-. ]` only.

Names never touch the filesystem — files are `<uuid>.soil` and structure lives in the index — but
the whitelist stays so a name is always safe to drop into an export filename or a shell line.

Uniqueness is a *database* question and is not in `NameRules`: `IndexRepository.nameTaken(parentId,
type, name, excludeId)` counts alive siblings **of the same type under the same parent**. Rename
excludes the item itself, so re-casing its own name is a rename and not a collision. An unchanged
name is a no-op that just closes the dialog.

`NameDialog` is the one "type a name" surface (new folder + rename). Its positive button is wired
**after** `show()`: the stock `setPositiveButton` listener dismisses before anything can object,
which would throw the user's typing away on every rejected character.

A rejected name — bad characters or a duplicate — is a **problem dialog**, never a toast.

---

## Sort

`SortRules` + `SortPrefs` (`sn_sort`, enum names only, default Name ↑).

- Field `NAME | MODIFIED`, order `ASC | DESC` — four options in an action sheet, a check icon on
  the active one.
- Name compares case-insensitively (`Bravo` sits between `alpha` and `Charlie`).
- Modified compares `updatedAt`, the index's real-edit timestamp.
- **Folders always come before notebooks**, in every order. The chosen order applies inside each
  group. Reversing a sort must not scatter the containers through the cards.

---

## Delete

Confirm dialog first, with the item's name in the title.

- Notebook: *Delete "X"?* / "This cannot be undone."
- Folder: *Delete "X"?* / "This will permanently remove all notebooks and subfolders inside it.
  This cannot be undone."

Then, for each removed notebook:

1. `IndexRepository.deleteNotebook` / `deleteFolderRecursive` — **soft** delete of the index rows
   plus a hard delete of the pinned membership edges (`deleteEdgesTo`). `deleteFolderRecursive`
   returns the ids of every notebook that was inside, which is what drives the rest.
2. `RecentsPrefs.remove(id)`.
3. **Hard** delete of `soilFile(context, id)` and every sidecar from `sidecarsOf` (`-wal`, `-shm`,
   `-journal`) — on IO.
4. `KeyMaterial.invalidate(context, id)` — drops the raw key from **both** the process RAM map and
   the Keystore. Leaving it would mean a future file that happened to reuse the id gets opened with
   a key derived from a file that no longer exists.

Deleting the folder you are standing in navigates out to its parent.

---

## Move

`FolderPickerActivity` — the same grid, folders only, one verb.

- Top bar: "Move to…" + breadcrumb + Cancel. Bottom bar: pagination + **Move here**.
- The folder being moved is filtered out of every listing, so its own subtree can never be entered.
  `IndexRepository.isSelfOrDescendant(destination, movingId)` backstops that at the moment of the
  move (the ancestry walk is cycle-guarded at 50 hops — **there is no nesting depth cap**).
- Cards do not long-press here.
- A name collision in the destination is a problem dialog and the picker **stays open**, so the
  user can walk somewhere else without starting the move over.

---

## Creating a notebook

`NewNotebookActivity`: name field pre-filled with `YYYYMMDD_HHmmss` (editable, fully selected),
template radios Blank / Lined / Dotted / Grid with Blank default, Create.

The order is the format contract:

1. mint a UUID — the id is both the filename and the notebook row's primary key;
2. `SoilDatabase.create(context, id, soilFile(context, id), KeySession.get())` — encrypted from
   birth, and it refuses to write over an existing file;
3. **notebook** row: `parentId = ""`, `text` = name, `refId` = the page id (so a reopen knows where
   to land);
4. **template** row for Lined/Dotted/Grid: `text` = kind name, `width`/`height` = page px, `blob` =
   lossless WEBP q100. **Blank writes no template row at all**;
5. **page 1**: `order = 0`, `width`/`height` = the full portrait screen in pixels,
   `refId` = the template row id, or `""` for Blank;
6. `NotebookMetaStore.write` — the file's self-description, folder ancestry included, so it is
   portable on its own;
7. `db.seal(file)` — WAL checkpoint back into the file, close;
8. **then** `IndexRepository.createNotebook(...)` (pageCount 1, `templateKind` = the kind's name).

The index row is last on purpose: the index is the library's truth, so a crash anywhere earlier
leaves an orphan file in `Garden/` — never a card pointing at nothing. A failure mid-way still
seals (to close the handle), leaves the partial file on disk (**never delete data on failure**) and
reports through a problem dialog.

The whole thing runs on `Dispatchers.IO`. Create is guarded by a `creating` flag rather than
`isEnabled = false` — a disabled control is invisible on e-ink.

The passphrase comes from `KeySession` (process RAM only). If it is somehow absent the screen
bounces back through `BootstrapActivity` the way `IndexGuard` does, rather than reaching step 2 and
throwing with a half-typed name on screen.

---

## Templates

Split in two, on purpose:

- **`data/template/TemplateGeometry`** — pure arithmetic, no `android.graphics`, JVM-tested. One
  physical constant: **8 mm** between features, converted at the panel's real dpi
  (`8 × dpi / 25.4`). Paper is measured in millimetres, so a template must be the same *size* on
  any device.
- **`data/template/BuiltInTemplates`** — the thin painter, plus the WEBP encode and the card
  placeholder.

| Kind | Geometry |
|---|---|
| Blank | no template row; page `refId = ""` |
| Lined | horizontal rules from `linePositions`, first at **2 × spacing** — a writing sheet wants a top margin |
| Dotted | dots at every grid intersection (`dotPositions`), first at 1 × spacing |
| Grid | `gridPositionsX` + `gridPositionsY`, both from **1 × spacing**, symmetric |

The grid must **not** borrow `linePositions` for its horizontals: the lined top margin would leave
a double-height top row of cells. There is a test that says so.

Feature sizes are authored at mdpi and scaled by dpi, floored at 1 px: `lineWidthPx` = 1 px at
mdpi (≈ 1.9 px at 300 ppi), `dotRadiusPx` = 2 px at mdpi (≈ 3.75 px, matching Paper v0's on-device
finding — a 1.5 px-authored dot still read faint). A literal 1 px rule on a
300 ppi e-ink panel is 0.08 mm and renders as faint grey, not a line.

Everything is **baked into the file at creation**, so changing a constant here affects new
notebooks only — a page must not silently re-rule itself under old ink.

---

## Prefs

All three live in `data/prefs/` and hold **ids and enum names only — never a display name**. Prefs
are device-local plaintext; every name in this app lives in the encrypted index.

| Store | File | Holds |
|---|---|---|
| `SortPrefs` | `sn_sort` | `field`, `order` |
| `BrowseState` | `sn_view_state` | `folderId`, `mode`, `lastOpenNotebookId` (R3 slot) |
| `RecentsPrefs` | `sn_recents` | JSON `List<RecentEntry(notebookId, timestamp)>`, max 20, newest first |

`RecentsPrefs` exists but **nothing records into it yet** — opening a notebook is R3's event and
the Recents view is R5. Deletion already prunes through it, so the list cannot start life holding
dead ids the moment recording is switched on. A corrupt blob reads as an empty list rather than
throwing.

**Cold launch** restores `BrowseState.folderId`; if that folder is no longer alive in the index the
library falls back to the root. Nothing in prefs is trusted as still existing.

---

## Deliberate differences from Paper v0

- **Duplicate names and invalid names are problem dialogs, not toasts.** SN's standing rule: a
  toast only confirms something that happened; anything explaining why a tap *didn't* work is a
  dialog, because on e-ink a missed toast reads as "broken". Paper toasted these.
- **`library_card_min_width` is a tier dimen (140 dp / 200 dp)** rather than Paper's hardcoded
  "3 columns above 480 dp, else 2". Same result on a Nomad (3 columns), but the grid is now
  dimen-driven like every other sizing decision, and `GridMath` is pure and testable.
- **`NameRules` returns a `Problem` enum**, not a hardcoded English string, so the wording lives in
  `strings.xml`. Paper's `validateName` returned literals from an Activity companion.
- **`NameDialog` is shared** between new-folder and rename instead of two near-identical copies.
- **`LibraryGrid` is reused by the folder picker** instead of the picker rolling its own
  `GridLayout` (which is why SN needs no `ids.xml` entry for a picker grid).
- **No pin badge on cards and no Pin action in the sheet** — R5, per the phase plan.
- **No cold-launch notebook reopen** — that depends on `NotebookActivity` writing
  `lastOpenNotebookId`, which is R3. The pref slot is reserved.

## Tests (JVM)

| File | Covers |
|---|---|
| `library/GridMathTest` | columns/rows/cards-per-page against a real Nomad band, page count rounding, clamp after a delete, page slice ranges, degenerate inputs |
| `library/NameRulesTest` | whitelist, `.`/`..`, blank/whitespace, control characters, dots that are legal |
| `library/SortRulesTest` | all four orders, case-insensitivity, folders-first in both directions and on both fields, stability |
| `data/TemplateGeometryTest` | 8 mm spacing at dpi, density-scaled feature sizes with the 1 px floor, lined top margin, grid symmetry, grid-≠-lined, dot intersections, Nomad-page counts |
