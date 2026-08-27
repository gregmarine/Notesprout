# Library — Notesprout SN subsystem doc

Phase **R5**. The library is SN's home screen: a paginated card grid of folders and notebooks that
**never scrolls**, plus everything that creates, renames, moves and deletes what is on it — and the
two flat shelves (Pinned, Recent) that cut across the folder tree.

Fresh code. Paper v0 (`git show 87277da:apps/notesprout_paper/...`) is the shape reference; the
deliberate differences are listed at the end.

---

## Screens

| Screen | Class | Purpose |
|---|---|---|
| Library | `library/LibraryActivity` | Browse, create, rename, move, delete, sort, page |
| New notebook | `library/NewNotebookActivity` | Name + the template browser + Create |
| Templates | `templates/TemplatesActivity` | The paper library — browse, or pick for a result ([`templates.md`](templates.md)) |
| Folder picker | `library/FolderPickerActivity` | The Move destination picker — **either hierarchy** (`browseFolderType` + `rootLabel`), notebooks or templates |
| Notebook | `notebook/NotebookActivity` | The drawing surface (R3) — see [`notebook.md`](notebook.md) |

Every one of them opens with `IndexGuard.ready(this)` and takes `TopGuard.applyInsetPadding`.
TopGuard's *guard* is 0 on Ratta (chrome flush at the top edge); the inset pass is still applied
because it is how the bottom bar clears a navigation bar if the device has one.

Identity travels as `EXTRA_NOTEBOOK_ID` + `EXTRA_NOTEBOOK_NAME` — **never a `File`**, never a
passphrase.

---

## Chrome

**Top bar** — the breadcrumb *is* the path, and everything you do *to* the library sits at the
row's right edge:

```
[←]  Notebooks / … / Folder        [+Notebook] [+Folder] [Recents] [Pinned] [Sort] [Scratch pad]
```

`+Notebook` is `ic_notebook_plus` — Tabler `address-book` with the person taken out and a plus cut
into the bottom-right corner, on `folder-plus`/`photo-plus`'s exact geometry, so the two create
buttons read as a pair. (The plain `ic_plus` it replaced named nothing, and on the link picker it
sat beside a second identical plus meaning "new page".)

`Notebooks` is the root crumb; each ancestor follows, separated by ` / `; any crumb jumps straight
there. `btnUp`'s back arrow appears left of the crumbs once you are below the root. **In a mode the
breadcrumbs give way** to a title (`modeTitle`) and `btnCloseMode` — a **left arrow**, first child
of the row, before the title — see [Modes](#modes). `btnUp` and `btnCloseMode` share the same
`ic_arrow_left` and never show at once: a shelf has no path to go up out of.

**Bottom bar** — a `FrameLayout`, not a row:

```
                        |<  <  n / n  >  >|                          [Templates] [debug ⋯]
```

The pager takes `layout_gravity="center"` so it is centred on the **screen**, and the right-hand
group (`bottomRight`) takes `layout_gravity="end"`. That is the whole reason the bar is not a
`LinearLayout`: `DebugMenu.install` appends the ⋯ into `bottomRight` at runtime and is a **no-op in
release**, so a weight-centred pager would sit in a different place in the two build types. The
pager stays `INVISIBLE` rather than `GONE` so the row never reflows. Every icon button carries a
`contentDescription` and a `TooltipCompat` long-press hint naming it.

The Templates screen is reachable from here only; paper is *picked* from New Notebook and from the
notebook's page-template row, which go straight to `TemplatesActivity.pickIntent`.

**Back press** peels one layer at a time: out of a mode, then up one folder, then out of the app.

---

## Modes

`BrowseMode { NORMAL, PINNED, RECENTS }` (`data/prefs/BrowseState`). A mode is a **flat shelf of
notebooks with no path** — the folder tree is still there underneath, and closing the mode returns
to exactly the folder you were in.

`setMode(new)` is a no-op on the mode already showing; otherwise it writes `browseState.mode`,
resets `pageIndex` to 0 and refreshes. `btnPinned` toggles PINNED ↔ NORMAL, `btnRecents` toggles
RECENTS ↔ NORMAL, `btnCloseMode` (the left arrow at the row's head) and Back both go to NORMAL. The mode **persists across a
relaunch**: it is read back in `onCreate` and honoured by the first refresh.

**Chrome in a mode** (`renderChrome`): breadcrumb scroll and `btnUp` hidden, `modeTitle` ("Pinned"
/ "Recent") and `btnCloseMode` shown; `btnNewFolder` / `btnNewNotebook` hidden — a shelf is not a
place to create into. Sort stays active. The active mode's top-bar button takes
`isSelected = true`, so `bg_toolbar_button`'s border says which shelf you are on.

**What each shelf holds** — one `repo.pinnedNotebookIds()` read per refresh feeds every card's
badge *and* the long-press sheet's Pin/Unpin label, so no card ever queries the index on its own:

| Mode | Items | Order |
|---|---|---|
| NORMAL | folders + notebooks of the current folder | the current sort, folders first |
| PINNED | the pinned notebooks, alive only | **the current sort** — the pin edge's `sortOrder` is recorded but deliberately unused for display |
| RECENTS | `RecentsPrefs.entries()`, alive notebooks only | **stored order, newest first — never re-sorted** |

Pinned uses the on-screen sort rather than pin order on purpose: a second, invisible arrangement
would be one the user has no control to see. Recents refuses the sort for the opposite reason —
it is a *history*, and Name ↑ would turn "what I was just working on" into an alphabet. The
ordering/filtering rule is pure and JVM-tested in `library/RecentsAssembly` so it cannot drift into
the Activity's sorting code. Reading the Recents shelf is also when the store is swept:
`RecentsPrefs.pruneDeleted(aliveIds)` runs after the list is built, so dead ids cannot accumulate.

A Recents card's second line is the **parent folder name** (root or unknown → "Notebooks"),
memoised per refresh, instead of the last-modified stamp: on that shelf "where is it" is the useful
thing.

**Empty states** are one `TextView` whose text is set per mode before it is shown — "No notebooks
yet" / "No pinned notebooks" / "No recent notebooks".

**Pin storage is an index list edge**, not a pref: a `list_item` row under the `PINNED_LIST_ID`
sentinel (`IndexRepository.pin` / `unpin` / `pinnedNotebookIds`). It therefore lives in the
encrypted index, travels with the library, and is scrubbed by `deleteEdgesTo` on any notebook
delete. Only notebooks pin — the shelf is of things to write in, not of places.

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
  device's own locale conventions rather than a hand-rolled pattern — unless the item carries a
  `subtitle` (Recents' parent-folder line), which takes that row instead. Secondary text stays
  inkBlack and gets *smaller*; `inkLight` is reserved for text meant not to be read.
- **Pin badge** — a 24 dp `ic_pinned` in the cover's top-right corner, `GONE` unless the item is
  pinned. It sits on `bg_pin_badge`, a solid paperWhite chip with a 1 dp inkBlack outline: a bare
  glyph over a lined or dotted cover is unreadable on e-ink.
- **No cover yet** → a small render of the notebook's own template kind
  (`BuiltInTemplates.placeholder`), squeezed to a fixed 12 rows so a 3 cm card still reads as
  "lined" / "dotted" / "grid". A Blank notebook shows a blank card, which is the honest picture.
  The index's `templateKind` is the notebook's **birth record** and stays that: re-papering a page
  (arc 12, [`notebook.md`](notebook.md)) deliberately does not write it, because with per-page
  paper there is no longer one true answer for a whole notebook — and a real cover snapshot, minted
  on every close, supersedes the placeholder anyway.
- **Selection** (arc 6 / K2) — `LibraryGrid.bind` takes an optional `selectedId`; the matching
  card gets `state_selected` on its background (`bg_selectable_card`: the 1 dp border thickens to
  3 dp — never a colour, never a grey). It exists for the link picker, where browsing *is*
  choosing; the library and the move picker pass nothing, and the unselected state is
  byte-identical to `shape_bordered`, so their cards are unchanged.

**Covers are lazy, one page at a time.** The DAO listing is blob-free (`ObjectSummary` has no
`blob` column); `bindCurrentPage()` reads `repo.cover(id)` only for the notebooks in the visible
slice, memoised in `coverCache` for the life of the listing and cleared on every `refresh()` so an
edited notebook picks up its new cover. Several binds can be in flight at once (a page tap racing
`onResume`), so blobs are fetched into a *local* map on IO and merged into `coverCache` back on
Main — the shared map is only ever written single-threaded. The page label is rendered **after**
the bind, so "n / N" can never name a page before its cards are on screen.

Tap a folder to enter it, a notebook to open it. Long-press either for the action sheet:
**Pin/Unpin · Rename · Move · Delete** — the first row is notebooks-only, and its label comes from
the card's own `pinned` flag (the listing already read the pinned list) rather than a fresh query.

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

## Name schemes (arc 5)

A folder can say what the notebooks created inside it should be called. `SchemeEngine` (pure
Kotlin, JVM-tested) is the language; the index stores one row per folder; the library owns all
the UI. Paper's arc-2 Naming *extension* is the reading reference — here the whole provider
layer (AIDL, store, client, discovery) is gone and the feature is core.

### The language (v2 = Paper's v1 + date-part/name tokens)

Literal text (the core name charset) plus tokens:

| token | expands to | example |
|---|---|---|
| `{date}` | `yyyyMMdd` | `20260822` |
| `{time}` | `HHmmss` | `143005` |
| `{year}` / `{month}` / `{day}` | `yyyy` / `MM` / `dd` | `2026` / `08` / `22` |
| `{monthname}` / `{mon}` | `MMMM` / `MMM` | `August` / `Aug` |
| `{weekday}` / `{wd}` | `EEEE` / `EEE` | `Saturday` / `Sat` |
| `{n}` / `{n:K}` | next number, zero-padded to K (1–9), at most once | `07` |

Three rules hold it together:

- **Literals obey `NameRules`' charset** — validated against `NameRules.CHARSET` itself (the one
  place the charset is written; a literal-only scheme is judged by `NameRules.validate`), so a
  scheme can only produce a name the library would have accepted by hand. Belt-and-braces: the
  library still runs `NameRules.isValid` **and the 100-char cap** on the expansion and falls back
  to the timestamp if either fails — a counter that outgrows its declared width (the 100th
  notebook under `{n:2}` with 97 literal chars) is never truncated, so over-cap degrades to the
  default instead (S2).
- **`{n}` is a sibling question, not stored state**: 1 + the highest number among the creation
  folder's alive notebook names matching the scheme's anchored **skeleton** regex — every
  date/time/name position a wildcard of the right shape (fixed digit widths; non-capturing
  alternations of the 12 month / 7 weekday names, so the counter stays capture group 1). That
  is what makes the run continue across days, months, and years. Numbers wider than K are
  matched and never truncated. Nothing is persisted — a rename or delete just changes the answer.
- **100-char cap counted at the worst-case expansion**, not the source: `{monthname}` is 11
  characters of scheme but up to 9 of name.

Numeric formatting is pinned to `Locale.US` (not `Locale.ROOT` — CLDR's root locale renders
`MMMM`/`EEEE` as the abbreviated forms). The month/weekday **names** never come from a formatter
at all (S2): expansion reads the engine's own hand lists by `Calendar` index — the same alphabet
the skeleton alternates over — so neither a device-language change nor a CLDR data update
(en_GB's "Sep" → "Sept" is the precedent) can make new expansions stop matching the skeleton and
stall the counter. One authority, both uses; pinned by a 12-month + 7-weekday JVM test.
Failures are codes (`SchemeEngine.Error`); the dialog maps them to sentences — the engine has
no strings.

### Storage

Additive index row type `naming` (`ObjectType.NAMING`) in the `objects` table — **no schema
change, no Room-hash change**; Paper filters listings by type so the rows are invisible to it.
One row per folder: `parentId` = folder id (**null = the library root**), `name` = the scheme
text. Set = upsert **in place** (`namingRowAny` reads the row *including a soft-deleted one*,
so re-setting revives the same row — a folder never accumulates naming rows); clear = soft
delete. `deleteFolderRecursive` soft-deletes each folder's naming row in the same transaction —
a stranded alive row would be invisible, un-clearable, and would come back if the folder id
were ever reused.

### Resolution

`resolveScheme`: **nearest ancestor wins** — the creation folder first, then up the (already
cycle-guarded) `ancestry` chain, finally the root's `parentId = null` row; first alive scheme
is the answer, none → the core timestamp default. `{n}` always counts siblings in the
**creation folder**, never the scheme-holding ancestor's.

### Entry points — four, one dialog

1. **New-folder dialog** (`NewFolderFlow` — extracted whole in arc 6 / K3 so the link picker's
   New folder is the *same* dialog, not a second implementation that can drift; the library
   delegates with a refresh callback, the picker with navigate-in) — a second optional field: the
   flow builds it with `SchemeDialog.buildField`, hands it to `NameDialog.show` as `extraField`,
   and reads it back itself in its accept closure (rename passes nothing and knows nothing about
   schemes). Both fields come from `NameDialog.input`, the one bordered single-line recipe, so the
   two stacked inputs can never drift visibly apart. Order is deliberate: name rule → **scheme
   validation** → duplicate check → create → save scheme. The scheme is validated *before* the
   folder exists, so a mistyped token keeps the dialog; once the folder is created it stands — a
   scheme that then fails to save is explained, not rolled back. The accept path is
   re-entry-guarded (S2): it crosses a coroutine, and an e-ink double-tap on OK would otherwise
   run two creates whose duplicate checks both read before either insert — two identically named
   folders (rename carries the same guard for family consistency).
2. **Folder long-press sheet** — "Default notebook name…" (`ic_cursor_text`). Folders only:
   a scheme is a rule about what is created *inside* something.
3. **Breadcrumb long-press** — any crumb **including the root** (the root has no card, so this
   is its only way in). The long-press returns `true` so it never also navigates on release.
4. **+Notebook** — the library resolves + expands *before* launching `NewNotebookActivity` and
   hands the result in as `EXTRA_DEFAULT_NAME`; the screen stays naming-agnostic (a prefill
   like any other, fully editable, Create-time duplicate check unchanged). The scheme→prefill
   rules live in **`SchemePrefill`** (pure, extracted in arc 6 / K3, shared verbatim with the
   link picker's New notebook): siblings are fetched lazily, only when the parsed scheme
   actually holds a counter — nothing else reads them (S2) — and an expansion `NameRules` would
   refuse (or that outgrew the cap) falls back to the caller's default rather than reaching a
   screen that will reject it. The
   launch shares the library's **one** `launching` latch with the notebook-card door (S2: in the
   e-ink feedback gap the second tap is not always on the same control — two per-door flags
   would let a card tap plus a + tap stack two screens); reset in `onResume` **and at the top of
   the New-notebook result callback** — the callback runs *before* `onResume`, so without that
   release the open of the just-created notebook would hit the still-armed latch and be silently
   dropped (S2 regression, user-caught). A mid-resolve folder change drops the tap rather than
   create elsewhere.

`SchemeDialog` (the standalone editor): does its own current-scheme read before showing — a
read failure explains itself and opens nothing (an empty field would silently offer to
overwrite a scheme that is actually there). **Blank save = clear** — there is no separate
remove control. Positive button wired after `show()` (the `NameDialog` pattern), click-guarded
via `isClickable` (never `isEnabled` — invisible on e-ink). The help line is inkBlack made
smaller, never inkLight — the token list is meant to be read.

### The failure rule

Naming never blocks what the user chose. An unresolvable/unparseable stored scheme, or any
failure in the resolve path → timestamp default silently (`Log.w` — the degrade-not-throw rule:
these run in `lifecycleScope`, which has no handler). Validation/save failures → problem
dialogs that keep the user's text. Three distinct failure strings (folder-created-but-scheme-
not, standalone save, standalone read) because one wording would read wrongly in two of the
three places.

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
   returns the ids of every notebook that was inside, which is what drives the rest. The whole
   cascade runs in **one Room transaction** (R6): a process kill mid-walk must never strand an
   alive subtree under a dead parent — unreachable in browse, un-deletable again, its `.soil`
   files and cached keys never purged because the caller never learns those ids.
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

`NewNotebookActivity`: a one-row header carrying the name field (pre-filled with
`YYYYMMDD_HHmmss`, editable, fully selected) and **Create**, over the **whole template browser** —
breadcrumbs, folders, shelves, import, both long-press sheets ([`templates.md`](templates.md)). The
four radios are gone (arc 13 / G3); a tap ticks a card and the screen waits for Create.

The screen is **`adjustNothing`**, not `adjustResize`: it has a page on it, and resizing for the
keyboard would squash the grid it measured itself against. The name field sits in the top row where
the IME cannot reach it — which is also why the header is one row and not a title plus a field.

The order is the format contract:

1. mint a UUID — the id is both the filename and the notebook row's primary key;
2. `SoilDatabase.create(context, id, soilFile(context, id), KeySession.get())` — encrypted from
   birth, and it refuses to write over an existing file;
3. **notebook** row: `parentId = ""`, `text` = name, `refId` = the page id (so a reopen knows where
   to land);
4. **template** row for whatever was picked: `text` = the [token](templates.md#the-token-is-the-identity)
   (`LINED` / `DOTTED` / `GRID`, or `IMG#<8 hex>` for an imported picture), `width`/`height` = page
   px, `blob` = lossless WEBP q100 rendered through `PagePaper.render` at the **page's** size.
   **Blank writes no template row at all**;
5. **page 1**: `order = 0`, `width`/`height` = the full portrait screen in pixels,
   `refId` = the template row id, or `""` for Blank;
6. `NotebookMetaStore.write` — the file's self-description, folder ancestry included, so it is
   portable on its own;
7. `db.seal(file)` — WAL checkpoint back into the file, close;
8. **then** `IndexRepository.createNotebook(...)` (pageCount 1, `templateKind` =
   `TemplatePicks.birthKind` — the kind's name, or `IMAGE` for an imported template).

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

The **library** of them — folders, import, export, the three shelves, and the one browser all three
hosts share — is [`templates.md`](templates.md). What follows is only the three built-ins' own
arithmetic, which is where the library's floor comes from.

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
notebooks only — a page must not silently re-rule itself under old ink. **The three built-ins'
output must stay bit-identical**: their thickness and dot constants are authored in *mdpi pixels*,
not millimetres, and any arithmetic change needs that check ([`templates.md`](templates.md)).

Blank, Lined, Dotted and Grid are also the four cards a user meets first: the three built-ins live
in the reserved **Default** folder at the templates root, and Blank is card #1 there, forever.

---

## Prefs

All of these live in `data/prefs/` and hold **ids and enum names only — never a display name**.
Prefs are device-local plaintext; every name in this app lives in the encrypted index.

| Store | File | Holds |
|---|---|---|
| `SortPrefs` | `sn_sort` | `field`, `order` |
| `BrowseState` | `sn_view_state` | `folderId`, `mode`, `lastOpenNotebookId`, `lastOpenViaLink` (K4 — cold restore reopens a via-link notebook *as* via-link, so the persisted link trail survives a mid-chain process death; see [`docs/links.md`](links.md)) |
| `RecentsPrefs` | `sn_recents` | JSON `List<RecentEntry(notebookId, timestamp)>`, max 20, newest first |
| `LinkTrail` (K4) | `sn_trail` | the link-follow walk-back stack, ids only, cap 50 — owned by the notebook's follow flow; see [`docs/links.md`](links.md) |

`RecentsPrefs` is written by **`NotebookActivity.onCreate`** (`record(id)` on every open, R3) and
read by the Recents shelf (R5). Three things prune it: a notebook delete (`remove`), a folder
delete (each notebook that was inside), and `pruneDeleted(aliveIds)` every time the shelf is built.
A corrupt blob reads as an empty list rather than throwing — this is a convenience, never a source
of truth.

**Pin membership is not here.** It is an index list edge (see [Modes](#modes)); prefs hold only
device-local browsing state.

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
- **Modes toggle from their own button** and are reachable from Back, and the active one's button
  carries a selected border — Paper v0 had only the close button.
- **The pinned shelf follows the on-screen sort**, not the pin edge's `sortOrder`.
- **`openNotebook` is the one door into `NotebookActivity` (R6)** — a `launchingNotebook` latch set
  on launch and reset in `onResume`. E-ink gives a tap no feedback for hundreds of ms, so users
  double-tap; without the latch each tap would stack its own `NotebookActivity` — two concurrent
  SQLCipher writers on one `.soil` (the documented lock-crash family). All three launch sites
  (card tap — including the Pinned and Recents shelves — the new-notebook result, and the
  cold-launch reopen) route through it.
- **The "Opening…" overlay goes up at tap time (P1)** — that same door is
  `OpeningOverlay.showThen(this) { startActivity(…) }` (`core/OpeningOverlay`, detail in
  `docs/notebook.md`). Opening a notebook is the app's one slow navigation, and the destination's
  own overlay can only appear once *its* first frame is drawn, leaving a dead gap after the tap.
  The library raises the box first and the launch runs only after that frame is committed — which
  is why the helper waits for `onPreDraw` and then `post`s: `Dispatchers.Main` is an async Handler,
  so a coroutine (or a bare `startActivity` here) jumps the traversal's sync barrier and the box
  never draws at all. It hides itself on the first resume after the pause, so returning from a
  notebook finds a clean library, and it swallows touches while up — a second guard against the
  double-tap the latch already covers.
- **A damaged index file is never built over (R6)** — `SnIndex`'s probe-`Invalid` branch creates a
  fresh encrypted index only when the file is genuinely absent (or zero bytes). An existing
  non-empty file that fails the probe (an interrupted copy/restore remnant) is
  `PrepareOutcome.DAMAGED_FILE`: `BootstrapActivity` shows the Retry/Close problem dialog with an
  honest body — nothing created over it, nothing deleted (the never-delete-on-corruption family).
  Bootstrap's boot catch also rethrows `CancellationException` and guards the failure dialog on
  `isFinishing`/`isDestroyed` (Home during the first-boot KDF is not a boot failure).

## Tests (JVM)

| File | Covers |
|---|---|
| `library/GridMathTest` | columns/rows/cards-per-page against a real Nomad band, page count rounding, clamp after a delete, page slice ranges, degenerate inputs |
| `library/NameRulesTest` | whitelist, `.`/`..`, blank/whitespace, control characters, dots that are legal |
| `library/SortRulesTest` | all four orders, case-insensitivity, folders-first in both directions and on both fields, stability |
| `library/SchemeEngineTest` | every token parses (v1 + v2, exact names only), each `Error` case, expansion-counted 100 cap (shrinking tokens not charged source length), fixed-clock expansion of all tokens, `{n}` counting (starts at 1, highest + 1 with gaps ignored, continues across days / months / weekdays — every date/name position a wildcard, padded + unpadded both count, width never truncates), anchored quoted-literal skeleton, counter stays capture group 1 behind name tokens, every expansion satisfies `NameRules`, every emitted month/weekday name matches the skeleton alphabet (all 12 + all 7 — the single-authority pin) |
| `library/RecentsAssemblyTest` | stored order survives (anti-alphabetical, anti-chronological fixtures), dead ids dropped, duplicates collapsed to their newest position, empty inputs, and that an alive id never visited is not invented |
| `library/SchemePrefillTest` (K3) | no-scheme/unparseable/refused expansions all fall back to null, siblings fetched only when the scheme holds a counter, a throwing sibling fetch never escapes, valid expansions pass through |
| `data/TemplateGeometryTest` | 8 mm spacing at dpi, density-scaled feature sizes with the 1 px floor, lined top margin, grid symmetry, grid-≠-lined, dot intersections, Nomad-page counts |
