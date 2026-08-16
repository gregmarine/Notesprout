# Library — Paper subsystem doc

The library is Paper's home screen — a paginated card grid of folders and notebooks, never scrolling.

## Screens

| Screen | Class | Purpose |
|---|---|---|
| Library | `library/LibraryActivity` | Browse folders, create/rename/move/delete notebooks and folders, sort, paginated grid |
| New notebook | `library/NewNotebookActivity` | Name + template radio + CREATE |
| Folder picker | `library/FolderPickerActivity` | Move-destination picker (same grid, restricted navigation) |
| Notebook (stub) | `notebook/NotebookActivity` | Shows notebook name; Phase 3 fills with g-paper |

## LibraryActivity chrome

- **Top bar:** breadcrumb (root → folder chain, each crumb tappable); back arrow when not at root.
- **Bottom bar (constant):** `[Pinned] [Recents]  |< < n/n > >|  [Sort] [+Folder] [+Notebook]`.
  Pinned/Recents wired in Phase 5; until then they toast "Later".
- **Pagination:** cards-per-page measured from the real grid container after layout (`LibraryGrid.measure`).
  Page controls hidden (`INVISIBLE`, not `GONE`) when there is one page.
- **Back press:** navigates up one folder; exits at root.
- **Cold launch:** restores the last browse folder; if it no longer exists → root. If a notebook was
  open when the app was killed (`BrowseState.lastOpenNotebookId`), reopens it automatically.

## Card grid

`LibraryGrid` measures the container and computes `columns × rows = cardsPerPage`. Card min-width is
140dp; aspect ratio 1:1.4. Folders always sort before notebooks. Each card:

- **Folder card:** folder icon + name (centred).
- **Notebook card:** cover image (or blank placeholder) + name + last-modified date/time
  (`DateFormat.getMediumDateFormat/getTimeFormat`).

Tap opens; long-press shows an `ActionSheetDialog`:
- Notebook: Rename · Move · Delete.
- Folder: Rename · Move · Delete.
(Pin arrives in Phase 5.)

## Sort

`SortPrefs` (`paper_sort`): field = `NAME | MODIFIED`, order = `ASC | DESC`. Default: Name ↑.
Folders always listed before notebooks. Sort button opens an action sheet with four options; the
current one shows a check icon.

## New folder

Alert dialog: name field, validation (same rules as notebook names — `[a-zA-Z0-9_\-. ]`, not `.`/`..`,
non-empty), duplicate check against the current folder.

## Rename

Alert dialog: name field prefilled with the current name (selected), same validation, duplicate check
excluding self.

## Delete

- **Notebook:** confirm dialog → soft-delete index row, scrub pinned edges, remove `.soil` +
  sidecars, remove recent entry, invalidate raw-key cache.
- **Folder:** confirm dialog (warns about all contents) → recursive soft-delete (all sub-folders +
  notebooks), same cleanup per notebook.

## Move

`FolderPickerActivity`: same grid showing folders only; the item being moved and its own subtree are
not enterable. Bottom bar: pagination + "Move here". Collision check (name already exists in target
folder) → toast, stay in picker. Self-into-self guard for folders.

## New notebook

`NewNotebookActivity`: name field (pre-filled `YYYYMMDD_HHmmss`), template radio (Blank / Lined /
Dotted / Grid, Blank default), CREATE button.

Creation (on IO): mint UUID → `SoilDatabase.create` → notebook row → template row (if non-blank:
`BuiltInTemplates.render` → WEBP q100 blob) → page 1 (full portrait screen px, `refId` = template
row id or `""` for blank) → `notebook_meta` → seal → index row → open notebook.

## Templates

`BuiltInTemplates` in `data/template/`:
- Blank: no template row; page `refId = ""`.
- Lined: 1px horizontal rules every 8 mm (at device DPI), starting after a one-spacing top margin.
- Dotted: 1.5px dots on an 8 mm grid.
- Grid: 1px lines both axes every 8 mm.

Geometry functions (`linePositions`, `dotPositions`, `gridPositionsX`) are pure and JVM-testable.

## Browse state & recents

- `BrowseState` (`paper_view_state`): `folderId`, `mode`, `lastOpenNotebookId`.
- `RecentsPrefs` (`paper_recents`): JSON list of `RecentEntry(notebookId, timestamp)`, max 20,
  most-recent first; pruned on read.
- `SortPrefs` (`paper_sort`): `field`, `order`.

All prefs store ids and enum names only — never display names.
