# Library — Paper subsystem doc

The library is Paper's home screen — a paginated card grid of folders and notebooks, never scrolling.

## Screens

| Screen | Class | Purpose |
|---|---|---|
| Library | `library/LibraryActivity` | Browse folders, create/rename/move/delete notebooks and folders, sort, paginated grid |
| New notebook | `library/NewNotebookActivity` | Name + (extension-provided) template radios + CREATE |
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

**Cover loading is lazy (Phase 6 perf).** The DAO listing is blob-free (`ObjectSummary`, no `blob`
column). Covers are read one **page** at a time: `bindCurrentPage()` fetches `repo.cover(id)` only for
the visible slice's notebooks, memoised in a `coverCache` that lives for the current listing and is
cleared on every `refresh()` (so an edited notebook's new cover is picked up). A 40-notebook folder
therefore reads ~one screen of covers, not 40. `LibraryGrid.bind(items, pageIndex, covers)` takes the
cache and renders a card without an image when its id isn't present. Several `bindCurrentPage`
coroutines can be in flight at once (a page tap racing `onResume`); each fetches its page's covers into
a *local* map on IO and merges into `coverCache` back on the Main dispatcher, so the shared `HashMap` is
only ever written single-threaded. The page indicator (`renderPager`) is drawn **after** the bind, so
"n / N" can't advance before the cards it names appear.

Tap opens; long-press shows an `ActionSheetDialog`:
- Notebook: Rename · Move · Delete.
- Folder: **Default notebook name…** (only while a Naming extension is installed — see §Naming schemes)
  · Rename · Move · Delete.
(Pin arrives in Phase 5.)

## Sort

`SortPrefs` (`paper_sort`): field = `NAME | MODIFIED`, order = `ASC | DESC`. Default: Name ↑.
Folders always listed before notebooks. Sort button opens an action sheet with four options; the
current one shows a check icon.

## New folder

Alert dialog: name field, validation (same rules as notebook names — `[a-zA-Z0-9_\-. ]`, not `.`/`..`,
non-empty), duplicate check against the current folder. With a Naming extension installed the dialog
also carries the scheme field (§Naming schemes) — described by the extension **before** the dialog
shows; if that call fails the plain dialog opens.

## Naming schemes (arc 2 / N1 — needs the Naming extension)

The core draws **one text field from extension data** and never interprets a scheme; everything
below is absent when `ExtensionRegistry.notebookNamer` finds nothing (`namerRef`, refreshed on every
`onResume`; when found, the namer's store is pre-warmed on IO in the same breath so the first
+Notebook tap doesn't pay the cold open). Full contract + client: `docs/extensions.md` §"NotebookNamer — host behaviour".

- **Field** (`library/SchemeDialogs.buildField`): caption (`BodyMedium`, the extension's label) ·
  bordered `EditText` (hint from the extension) · help line (`BodySmall`). Wording ships in the
  extension: "Default notebook name" / "e.g. Meeting {date} {n:2}" / "Tokens: {date} {time} {n} {n:3}.
  Leave empty for the standard name."
- **New folder** — CREATE: name validated as before → non-blank scheme → `NamerClient.validate`
  (extension's error text, or "Naming extension didn't respond", as a toast; dialog stays) →
  `createFolder` → `save(folder.id, scheme)`; a save failure toasts "Folder created — naming scheme
  not saved" and dismisses (retry from long-press).
- **Folder long-press → "Default notebook name…"** (Tabler `cursor-text`, before Rename): the field
  description and the current scheme are fetched first (failure → "Naming extension didn't respond",
  no dialog); `SchemeDialogs.showSchemeDialog` — titled with the folder name, prefilled + select-all;
  OK → blank clears, else validate (toast + stay) → save; the extension being unreachable keeps the
  dialog up so the text isn't lost.
- **+Notebook** in a folder: the default name is resolved from the extension **before**
  `NewNotebookActivity` opens (folder UUID + the listing's notebook names cross; ≤ 2 s worst case, no
  feedback — the tap takes a beat; a second tap during that beat is dropped) and travels as
  `EXTRA_DEFAULT_NAME`. Root, no scheme, or any failure → the screen opens without the extra.
- **Store:** the extension keeps `folder:<UUID>` → scheme in its host-owned encrypted store
  (`Garden/<ext pkg>.db`); it survives disable/uninstall, moves and renames of the folder (keyed by
  UUID); a deleted folder leaves an orphan row (tolerated in v1).

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

`NewNotebookActivity`: name field (pre-filled from `EXTRA_DEFAULT_NAME` when the caller passed one that
survives `acceptDefaultName` — name rule + ≤ 100 chars — else `YYYYMMDD_HHmmss`; the screen never talks
to the Naming extension itself), an optional **Template** section, CREATE button. **The core has no template renderer** — templates come only from discovered, trusted
extension providers (`extension/ExtensionRegistry`; see `docs/extensions.md`).

- On `onCreate` a `lifecycleScope` job runs `ExtensionRegistry.templateProviders` → for each provider
  `TemplateProviderClient.list()` (a provider that throws is skipped with a `Slog.d`, never a toast).
  If at least one template exists, one `RadioButton` per template (inflated from
  `layout/item_template_radio.xml`, `tag` = `TemplateChoice`) is appended after `radioBlank` and the
  section (`templateSection`, `GONE` in XML) becomes visible; with more than one provider each group is
  preceded by a 14sp heading carrying the extension's label. **With no extension installed the section
  never appears** — nothing hints at templates; notebooks are created blank. Default = Blank. On a
  recreation the chosen template's identity is saved (`onSaveInstanceState`) and re-checked once
  discovery rebuilds the radios; Blank is re-checked immediately so the group is never left empty.
  Provider headings use `TextAppearance.Notesprout.BodyMedium` (never hardcoded size/colour).
- `attemptCreate`: name validation + duplicate check, then, if a template is chosen, **render first**
  (`client.render(id, pageW, pageH, dpi)`) before any file exists. On `ExtensionCallException` (which includes a
  payload that is undecodable or not exactly the requested size) or a null/empty result: reset the button, toast `new_notebook_template_failed` ("Template extension didn't
  respond — try again or choose Blank") and **stay on the screen** — never a silent downgrade to Blank.
- Creation (on IO): mint UUID → `SoilDatabase.create` → notebook row → template row (if chosen: `text` =
  `TemplateChoice.identity` = `"<extension package>:<template id>"`, `blob` = the WEBP the extension
  returned) → page 1 (full portrait screen px, `refId` = template row id or `""` for blank) →
  `notebook_meta` → seal → index row (`templateKind` = identity or `SoilSchema.TEMPLATE_BLANK` =
  `"BLANK"`) → open notebook.

## Templates

Rendered by the **Templates extension** (`:ext-templates`, `TemplateRenderer` — the v0 `BuiltInTemplates`
moved verbatim; geometry documented in `docs/extensions.md`). The core only stores and draws the WEBP;
a notebook opened on a device without the extension still shows its template. Identity strings in the
index / template row are informational: `BLANK` · `<pkg>:<id>` · legacy v0 `LINED`/`DOTTED`/`GRID`.

## Modes: Pinned & Recents

The library has three modes (`BrowseMode`, persisted in `BrowseState.mode`): `NORMAL` (folder
browsing), `PINNED`, `RECENTS`. The two bottom-bar buttons toggle their mode on/off; the top bar swaps
the breadcrumb for a title ("Pinned" / "Recent") + a ✕ (`btnCloseMode`); back-press exits a mode before
navigating folders. `renderChrome()` hides +Folder / +Notebook / Up while in a mode; Sort stays active.
A mode is a flat overlay — the folder stack underneath is untouched and restored on exit.

- **Pin/Unpin** lives in the notebook long-press action sheet. Pinning is an index `list_item` edge on
  `PINNED_LIST_ID` (`IndexRepository.pin/unpin`); pinned cards show a corner badge (`R.id.pinBadge`,
  `bg_pin_badge` white backing so the outline pin reads over ink). `CardItem.Notebook` carries
  `pinned` (badge) and an optional `subtitle` (Recents' parent-folder line).
- **Pinned mode** shows pinned notebooks (no folders) **in the current sort** — one sort model
  everywhere; `sortOrder` is still recorded on the edge but not used for display. Empty: "No pinned
  notebooks".
- **Recents mode** shows `RecentsPrefs` entries newest-first (recency order, *not* re-sorted), each with
  its immediate-parent-folder name as the subtitle; dead ids are pruned on read (`pruneDeleted`). Empty:
  "No recent notebooks". Opening a notebook records it (`recentsPrefs.record`) before launch, so it
  jumps to the top on return.

**Empty-state trap:** `emptyState` is a sibling of the grid inside `gridContainer`. `LibraryGrid.bind`
removes only its own last `GridLayout` (`currentGrid`), **never** `container.removeAllViews()` — the
latter deletes `emptyState` and no empty message ever shows again once a card has rendered.

## Cold-launch reopen

If a notebook was open when the app last died/closed, it reopens on top of the library on the next cold
launch. `NotebookActivity.onCreate` writes `BrowseState.lastOpenNotebookId`; a normal close clears it.
`LibraryActivity.reopenLastNotebookIfNeeded()` runs **only on cold launch** (`savedInstanceState ==
null`, captured as `coldLaunch`), reads-and-clears the id, and relaunches the notebook only if its index
row is alive **and** its `.soil` exists — never mints a ghost file. Not gating on cold launch would
re-fire on every config-change/recreate.

## Browse state & recents

- `BrowseState` (`paper_view_state`): `folderId`, `mode`, `lastOpenNotebookId`.
- `RecentsPrefs` (`paper_recents`): JSON list of `RecentEntry(notebookId, timestamp)`, max 20,
  most-recent first; pruned on read.
- `SortPrefs` (`paper_sort`): `field`, `order`.

All prefs store ids and enum names only — never display names.
