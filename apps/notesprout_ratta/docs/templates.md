# Templates — the paper library (arc 13)

Paper stopped being four radio buttons and became a **library**: folders, previews, import, export,
and three flat shelves across the top of it. One browser serves every place in the app where paper
is chosen.

The join at the heart of the arc is between two things the family had always kept apart — a
reusable, foldered, importable template library (og's), and built-in paper rendered from arithmetic
and never stored twice (SN's). The result is a rule with no exceptions:

> **A built-in is paper the app draws. A static template is pixels the library keeps. There is no
> third kind.**

**Status: arc 13 complete and frozen.** G1 the store + the Templates screen · **G2 abandoned**
(adjustable generators — see [*The idea that was tried and dropped*](#the-idea-that-was-tried-and-dropped)) ·
G3 the shared browser and picking · G4 SAF import and export · G5 Pinned / Recents / Search ·
G6 review, docs, freeze. No new Gradle dependency, no schema change, no migration, no format break
with Paper — every `.soil` this family has written still reads, and still writes, the same tokens.

---

## The two kinds

| | **Built-in** | **Static template** |
|---|---|---|
| What it is | Lined · Dotted · Grid, drawn from `TemplateGeometry` at the page's exact size | an imported PNG / JPEG / WEBP, stored as lossless WEBP in an index row |
| Where it lives | nowhere — a hardcoded sentinel id and a `when` branch | an `objects` row of type `template`, blob = the **original** picture |
| Where it appears | inside the reserved **Default** folder, in one order, forever | wherever the user put it |
| Long-press | **one row: Pin / Unpin** (G5) — and nothing else, ever | the full management sheet |
| `.soil` token | `LINED` / `DOTTED` / `GRID` — byte-identical to every file the family ever wrote | `IMG#<8 hex>` |

Two more cards exist and are neither kind: **Blank** (the absence of paper — always card #1 at the
root) and **Default** (the reserved folder). Both are sentinels, and **neither long-presses at all**.

---

## Where it lives

| | |
|---|---|
| `data/index/ObjectType.TEMPLATE` / `.TEMPLATE_FOLDER` | the two additive row types, `"template"` / `"template_folder"` |
| `data/index/ListIds` | the five card sentinels + `TEMPLATE_PINNED_LIST_ID` |
| `data/index/IndexRepository` § *Template library* | list · create · duplicate · fit · soft-delete · recursive folder delete · the pin shelf · search |
| `data/index/ObjectDao.searchOfType` / `.aliveOfType` | the two reads a shelf needs — `LIKE … ESCAPE` with no `parentId`, and one blob-free batch |
| `data/template/TemplateToken` | **pure** — the `.soil` token vocabulary and the image digest |
| `data/template/TemplateFit` | **pure** — Fit / Stretch / Fill as one source rect and one destination rect |
| `data/template/TemplateImport` | **pure** — sample size, downscale, the blob cap, the offered name |
| `data/template/TemplateSearch` | **pure** — the `LIKE` pattern and the label matcher, one rule for both halves |
| `data/template/PagePaper` + `PaperSource` | the one place a pick becomes page-sized pixels |
| `data/template/BuiltInTemplates` / `TemplateGeometry` | the three built-ins' arithmetic and their WEBP encode |
| `notebook/PageTemplate` | **pure** — reuse-before-mint (`reusableId`) and the tick (`tokenOf`) |
| `templates/TemplateLibrary` | **pure** — root composition, the reserved name, duplicate suffixing |
| `templates/TemplateShelves` | **pure** — what is pinnable, and the three shelves' contents |
| `templates/TemplateCard` | the card model — five cases, two of them rows |
| `templates/TemplateCardGrid` | the paginated non-scrolling grid, over `library/GridMath` |
| `templates/TemplateThumbnails` | the true-miniature renderer + its `LruCache` |
| `templates/TemplateBrowser` | **the component** — breadcrumbs, grid, sort, folders, both long-press sheets, the Move picker |
| `templates/TemplateShelfView` | Pinned / Recents / Search, beside the browser |
| `templates/TemplateTransfer` | SAF import and export, the three sheets, the decoder and the encoder |
| `templates/TemplateRecents` | the one place that decides what counts as a use |
| `templates/TemplatePick` / `TemplatePicks` | the result contract, and turning it into `PaperSource` |
| `templates/TemplatesActivity` | the host with two modes: browse, and pick-for-a-result |
| `data/prefs/RecentsPrefs.templates` / `SortPrefs.templates` | the two device-local stores, generalised rather than copied |

**No new index table.** `notesprout.db` is Room-validated and byte-compatible with Paper: a new
`@Entity` changes the identity hash and a Paper index would fail validation. The library is two
**additive row types** — the arc-5 `naming` and arc-7 `clipboard` precedent — and nothing about
templates touches the filesystem.

### The static row's shape

| Column | Holds |
|---|---|
| `type` | `"template"` |
| `name` | what the user called it |
| `parentId` | its folder, `null` = the templates root |
| `templateKind` | the base kind's name, or `"IMAGE"` (`TemplateLibrary.KIND_IMAGE`) |
| `flags` | the `TemplateFit` mode |
| `blob` | the **original** image bytes, lossless WEBP |

`refId` / `sortOrder` / `pageCount` / `keyScope` are unused. **No new columns.**

The row stores the original and the page-sized render happens **on use** — so one row lands
correctly on a Nomad page and a Manta page, and **Fit…** can be changed later without re-importing.

---

## The sentinels are not rows

Blank, Default and the three built-in papers are hardcoded ids in the `ListIds` hex-ASCII style,
composed into every listing on the fly:

| Card | Id ends | Spells |
|---|---|---|
| Blank | `…5f626c616e6b` | `_blank` |
| Default (the folder) | `…6465666c745f` | `deflt_` |
| Lined | `…6c696e65645f` | `lined_` |
| Dotted | `…646f74746564` | `dotted` |
| Grid | `…5f677269645f` | `_grid_` |
| the pinned list | `…7470696e6e64` | `tpinnd` |

Nothing is seeded at bootstrap, nothing can be deleted, renamed or moved, an index restored from a
backup needs no repair, and there is **no migration**. The database is asked only about what the
user actually made.

The consequence that catches people: **a sentinel has no row**, so anything that prunes a list
against "rows that are still alive" would sweep the built-ins out permanently. `TemplateShelves`
carries that asymmetry explicitly — `recentIds` resolves a sentinel as always-alive, and `pruneable`
is what goes back to the prefs and always contains them.

## The Default folder

Always present, always the same three papers, in one order, forever. It is the **floor** of the
library: however empty the rest of it is, every notebook can still reach Lined, Dotted and Grid.

- **"Default" is a reserved name at the templates root** — no folder *and no template* of the
  user's may take it, and no import may land in it. Compared case-insensitively: two cards a user
  cannot tell apart are not two names, whatever SQLite thinks of the bytes. Deeper in the tree the
  name is perfectly ordinary.
- Inside it, **Sort, New folder and Import are GONE** — never `isEnabled = false`, which is
  invisible on e-ink. A button that could only refuse itself is not a button. (Sort comes *back* on
  a shelf raised from inside Default: opening a shelf does not clear the folder you opened it from,
  and a shelf **is** ordered by the sort prefs.)
- It cannot be renamed, moved or deleted, and it does not long-press.

*(It was called **"Generated"** through G1–G2 and renamed on 2026-08-26 when the generator idea was
dropped — nothing about it is generated *by the user* any more.)*

---

## The browser — one component, three hosts

`TemplateBrowser` is the whole screen minus its frame: breadcrumbs, the paginated non-scrolling card
grid, the sort sheet, New folder, Import, the two long-press sheets, the Move picker and the three
shelves. A second copy of it — for the New Notebook screen, say — would be the `RattaNotebookView`
sibling-copy trap in a new place, so there is one.

A host supplies exactly two things:

| | |
|---|---|
| **`onPick`** | what a tap on a paper card *means*. Folders are never picks — a folder is entered. |
| **`selection`** | which card is the paper in force, said either way round: by **card id** (New Notebook holds a `TemplatePick` and nothing is rendered yet) or by **token** (the notebook holds a page whose `.soil` row carries one). Null on both ticks nothing. |

| Host | What a tap means | Chrome around it |
|---|---|---|
| `TemplatesActivity` (browse) | nothing — it is a library, not a picker | the Templates button at the library's bottom-right |
| `NewNotebookActivity` | tick it and wait for **Create** | a one-row header carrying the name field and Create; the four radios are gone |
| `TemplatesActivity` (pick) | apply it and leave | launched full-screen from the notebook's page sheet **Page template** row, `LinkPickerActivity` shape — an `ActivityResultLauncher`, chrome only, **no `releaseForHandoff`** (it is not a paper surface) |

Two lifecycle rules the component imposes on its hosts:

1. **Construct it in `onCreate`.** It registers `ActivityResultLauncher`s of its own (the Move
   picker, and `TemplateTransfer`'s two).
2. **Pass `saveState` / `restoreState` through.** `pendingExportId` is the one piece of state that
   outlives a call — DocumentsUI is another process on a memory-tight device.

`NewNotebookActivity` is **`adjustNothing`**, not `adjustResize`: it is a screen with a page on it,
and resizing for the keyboard would squash the grid it measured itself against. The name field lives
in the top row where the IME cannot reach it — which is also why the header is one row and not a
title plus a field.

### Thumbnails

**True miniature** — the card is the page, scaled honestly. Density is what tells two papers apart,
so the card must show it; a dense grid reading as a grey wash is what a dense grid looks like.
Rendered on IO, cached in memory keyed `id:updatedAt` (a sentinel's stamp is `0` — it never changes).
The 1 px page edge is drawn **on the bitmap**, never as an `ImageView` background, or the fit-centred
bitmap paints over it.

A miniature renders through `TemplateFit` for the same reason: a card that showed a picture *fitted*
while the page *stretched* it is the one thing a true miniature must never do.

---

## Import and export

`TemplateTransfer` is the library's two doors to the rest of the device, and **the only place in
Notesprout SN that has ever opened a system file picker**. It lives beside the browser rather than
inside it: the browser is about what is *in* the library, this is about what crosses its edge.

**SAF works on the Supernote.** Probed before a line of G4 was written: both `ACTION_OPEN_DOCUMENT`
and `ACTION_CREATE_DOCUMENT` resolve to `com.android.documentsui/.picker.PickActivity` and both
render on the Nomad. No fallback was needed.

### Import, in order — and the order is the point

1. `ACTION_OPEN_DOCUMENT`, limited to **PNG / JPEG / WEBP** (`TemplateImport.MIME_TYPES`). No PDF —
   that is a new Gradle dependency and its own decision.
2. Decode **bounds-first**, sample down by powers of two, resize exactly, re-encode **lossless
   WEBP** — all on IO.
3. Over the cap → a **problem dialog**, and nothing else happens.
4. **Then** the fit sheet, then the name.

Asking for two decisions and *then* refusing the file would waste the only two decisions the user
makes. The landing folder is read at the **tap**, not at the result.

### The two numbers, and where they came from

| | |
|---|---|
| **Downscale bound** | the page's own **long edge, ×1** (1872 px on a Nomad). **Never upscales** — a 400 px sketch stays 400 px. |
| **Blob cap** | **6 MiB** on the *encoded* bytes, after the downscale. `TemplateImport.MAX_BLOB_BYTES`. |

Measured at 2× the page (2106 × 2808), lossless comes to 0.004 MB for a clean vector-ish template,
**3.56 MB** for a mild greyscale scan, **12.51 MB** for a grainy one and **12.86 MB** for a photo —
so the spec's own encoding choice walked straight into the B3 trap and would write blobs the 8 MiB
`CursorWindow` cannot read back. Put to the user with the numbers: **lossless kept, bound dropped to
1×, cap 6 MiB** — two thirds of the read ceiling, above every measured case, with the rest of the
window spare for the row's other columns. **The refusal dialog is a live path, by design.**

### Export

`ACTION_CREATE_DOCUMENT`, a **PNG rendered at this device's page size** — the same `PagePaper` render
the page itself gets, so what lands in the file is what the paper looks like. No share sheet: the
Supernote suppresses it.

**Only imported static templates export.** G4's spec said "for built-ins and static cards alike",
which collided head-on with G1's frozen "the sentinels do not long-press at all"; **G1 won**. Import
stands down inside Default for the same reason.

A card exported and re-imported comes back **byte-for-byte the size of the original blob** — the
round trip is stable.

---

## Chrome

Everything that acts on the folder you are standing in sits on the **top bar**, in the library's
order, so a template bar and a library bar read as the same bar:

```
[←]  Templates / Folder        [Import] [+Folder] [Search] [Recents] [Pinned] [Sort]  [✕]

[←] = one layer back (shelf, then folder) · [✕] = leave the screen, from anywhere
```

A **one-finger horizontal swipe over the grid** flips the page, the same rule the library and the
notebook use (`core/ListSwipe` over `core/SwipeMath` — `docs/library.md` § The flip). The host
forwards its `dispatchTouchEvent` to `TemplateBrowser.onDispatchTouchEvent`; the browser arms on
`gridContainer` alone, which is what lets it sit inside **New Notebook** beside a name field without
a drag across that field turning the page.

The **bottom bar is the pager and nothing else** — weighted, centred, `INVISIBLE` rather than `GONE`
so the bar never changes height under the grid.

`[←]` steps back **one** layer and is the only arrow on the row: `btnCloseShelf` out of a shelf,
`btnUp` up a folder — never both, since a shelf has no path to go up out of. The `[✕]` in the right
corner leaves the **screen**, and it is offered from everywhere: root, folder, shelf alike. One
arrow, one ✕, and they never mean the same thing. In a shelf the breadcrumbs give way to the shelf's
title; inside **Default** Sort, New folder and Import stand down (the folder's contents are fixed),
and in a shelf New folder and Import go too.

---

## The three shelves

Pinned · Recents · Search, on the browser's **top bar**, in all three hosts. They are
flat, paginated, and **mutually exclusive** (one field holds the mode, so exclusivity is structural).
While a shelf is up the top bar swaps its breadcrumbs for the shelf's title and puts a back arrow at
its head — the library's shape for its own shelves — and New folder, Import and **Duplicate** stand down.

| | |
|---|---|
| **Pinned** | a sentinel `LIST` row + `list_item` edges. **Static templates and the three built-ins**; never a folder, never Blank (it is already card #1 at the root, forever). The built-ins lead, in their fixed order; the rows follow in the screen's sort. Scrubbed on delete. |
| **Recents** | device-local prefs, **stored order, never re-sorted** — a history that obeyed Name ↑ would stop being a history. Self-healing: dead rows are pruned, sentinels never are. |
| **Search** | names anywhere in the tree, flattened, **plus Blank and the three built-ins by their labels** — typing "grid" and not finding Grid would read as a bug. **Folders never appear**: a place is not paper, and a flat shelf whose taps mean "pick this" must not have taps that mean two things. |

**A shelf is a glance, not a place.** Nothing persists — not the mode, not the query. The browser
opens in the tree, at the root, every time and in every host. A picker that opened onto a shelf would
have no visible way back to the paper the page is actually using; and persisting a search would mean
writing a **name** into device-local plaintext prefs, which the family's prefs rule forbids.

**The way out of a shelf is the head arrow, not the ✕.** `showCloseButton`'s ✕ means one thing that
never changes — leave the screen — so it stays put in the corner from root, folder and shelf alike;
stepping back one layer is the arrow's job. The Back key still peels the arrow's order:
shelf → folders → out.

### How a query is typed

A **dialog**, then a flat shelf whose title is the query; re-tapping Search re-opens the dialog with
the last query in it. An inline field was rejected: the New Notebook host is `adjustNothing`, so a
field in the browser's own chrome would sit under the IME with no way to reach it — and live
filtering is a repaint per keystroke on e-ink.

`TemplateSearch` is the one rule both halves use. SQLite's `LIKE` is ASCII-case-insensitive and
substring-anywhere; a Kotlin-side `contains` that was case-*sensitive* would make "Grid" findable and
"grid" not, **for the built-in only** — precisely the split a user reads as the search being broken.
`%`, `_` and the escape character are neutralised, because a query of `_` silently matching every
name is worse than an error.

### What counts as a use

`TemplateRecents.record` is the **only** place that answers this, and it is called from exactly two:
the New Notebook screen baking page 1, and the notebook re-papering a page.

**Not** a use: creating a folder, importing, renaming, moving, duplicating, changing a fit,
exporting, or ticking a card the user may still back out of. Those are things done *to* the library;
the shelf answers "what paper have I been writing on", and an import that was never applied has not
been written on. **Blank records nothing** — a Recents shelf whose top entry is "no paper" has
learned nothing. A pick whose row vanished (the problem dialog) records nothing.

A re-pick of the **ticked** card *does* record: the user did choose it. The prefs write is not a page
change and raises no undo step, so the no-op stays a no-op.

---

## Paper on a page

### The token is the identity

The `.soil` `template` row's `text` column is the **token**, and it is the whole of what "this is the
same paper" means inside a notebook file.

| Token | Means |
|---|---|
| `""` | **blank** — not a token at all: a blank page has *no* template row and its `refId` is `""` |
| `LINED` / `DOTTED` / `GRID` | the built-ins, spelled exactly as before — every existing file and every Paper build still reads them, and **nothing about them changes** |
| `IMG#<8 hex>` | an imported picture, identified by a digest of what it draws |
| anything else | paper authored by a later version of the family — returned verbatim, matches no card, **ticks nothing** |

Arc 12 matched a page's paper by `TemplateKind`; that only worked while the four built-ins were the
only paper there was. `TemplateToken` is the same rule widened into one vocabulary — `kindOf` became
`tokenOf`, and unknown still stays unknown.

**The image digest covers the fit mode as well as the bytes.** The arc's locked wording said "8 hex
of the image bytes", and that is wrong by exactly one input: fit is what turns stored bytes into page
pixels, so the same picture Fitted and Stretched are **two papers**. Digesting the bytes alone would
let a page that asked for the stretched one be silently re-pointed at the fitted row already in the
file. The fit byte goes in **first** so it can never be read as image data.

### Reuse before mint

A `.soil` `template` row is **shared paper**, not a page's property: every page created with the
notebook points at the same row. `PageTemplate.reusableId` looks for a row this file already holds
that *is* the wanted paper at the page's exact size, and only then renders another megabyte of WEBP.

- **Identity is `token` + page size**, never the pixels. A byte-identical row arriving from another
  notebook was already content-deduped on the way in (arc 7's `PageClip.matchTemplate`).
- **`prefer` — the page's current id — wins among equal matches.** That is what makes re-picking the
  ticked card a **true no-op**: `changeTemplate` returns null, no undo step is pushed.
- A row with **no pixels** is refused: it names paper it cannot draw, so re-pointing at it would
  blank the page while claiming otherwise.
- **Nothing ever soft-deletes a template row**, so the there-and-back — Lined → Grid → Lined — is
  free.
- **Render at the page's own size, never the screen's.** A page pasted from a larger device keeps its
  authored size.

### The apply path

`PaperSource` is what a built-in and a picture have in common, and `PagePaper.render` / `.token` are
the one answer each to "what does this paper look like at this size" and "is this the same paper as
that". Both hosts that create pixels go through them.

**`applyTemplate` must not decode.** The id changing is exactly what makes the following `navigateTo`
reload the bitmap — one decode, on the swap that paints it, in one EPD refresh. Decoding here as well
costs a read the page swap throws away.

Scope is **this page only** — the same scope Copy, Cut and Delete have — and it is one undoable step
(`Action.TemplateChanged`, unchanged from arc 12; both directions are `applyTemplate` with the
change's two ids swapped).

**The browser never opens a `.soil`.** It returns a `TemplatePick` — a short string naming a *card*,
never a blob in an Intent extra — and the notebook's own session does the read and the write.

---

## Failure table

| What happened | What the user gets | Where |
|---|---|---|
| A picked static row vanished between the tap and the apply | **problem dialog**; the page is left exactly as it was | `NotebookActivity.doChangeTemplate`, `NewNotebookActivity` |
| A picked row resolved but **will not draw** (bytes that no longer decode, an allocation refused) | **problem dialog**; nothing is written, no undo step, nothing recorded as recent | `NotebookSession.PaperRenderFailed` (G6) |
| The same, while **creating** a notebook | the create's own failure dialog; the render happens **before** the `.soil` exists, so there is not even an orphan file | `NewNotebookActivity.createNotebook` (G6) |
| A pick this build cannot decode | treated as a **cancel**, never Blank — the two are indistinguishable from the caller's side and only one of them is safe | `TemplatePick.decode` |
| A page's template row has gone, or carries no token | **nothing ticks** — never a guessed "Blank" | `PageTemplate.tokenOf` |
| The chosen file will not decode as PNG / JPEG / WEBP | problem dialog, nothing added | `TemplateTransfer.ingest` |
| The stored bytes would exceed 6 MiB | problem dialog naming both sizes | `TemplateImport.overCap` |
| No DocumentsUI on the device | problem dialog; import and export are simply unavailable | `TemplateTransfer` |
| An export could not be written | problem dialog, nothing changed | `TemplateTransfer.write` |
| A duplicate's source went away | problem dialog, the list refreshes | `IndexRepository.duplicateTemplate` → null |
| "Default" typed as a name at the root | problem dialog naming the reason; **the dialog stays open with the typing intact** | `TemplateLibrary.isReservedName` |
| "Default" **moved** to the root from deeper in the tree | the same words (*"That name won't work"*), and the move does not happen | `FolderPickerActivity.moveHere` (G6) |
| A duplicate name among siblings | problem dialog | `IndexRepository` sibling check |
| An empty search | its **own** two strings — *"Nothing to search for"* — never `name_problem_title` | `TemplateSearch.isRunnable` |
| An import succeeded · an export succeeded | **toast** | `TemplateTransfer` |

The rule behind the column: **a toast only confirms something that already happened; anything
explaining why a tap didn't work is a dialog.** On e-ink a missed toast reads as "broken".

---

## The idea that was tried and dropped

**Adjustable built-in generators (G2) were built, shown to the user, and reverted** — 2026-08-26.
Its one surviving product is the rename of "Generated" to **Default**.

**Do not rebuild this. Do not re-raise it without a fresh user decision.**

It was a `TemplateSpec` (kind, per-axis density in spacing *or* count, four insets in mm, margin
rule, rule thickness, dot size, ruling shade), a `LINED#<hex>` token, and a full-screen options
screen with a live page preview and Cancel · Use once · Save as template…. Two rounds of layout work
went in first; the verdict after both was that the whole idea, not the arrangement, was wrong. **A
generator with nine knobs is a settings screen wearing a page's clothes, and this app is meant to
feel like paper.**

What replaced it: nothing. The three built-ins are fixed paper in a fixed folder, and **import is how
a user gets different paper.** That is one idea instead of two.

Three findings from it worth keeping, all cheap to lose and expensive to rediscover:

1. **Stock output has to stay bit-identical.** The rule thickness and dot radius are authored in
   *mdpi pixels*, not millimetres (`px × dpi / 160`).
2. **A count is not a spacing.** 10.0 mm → 14 lines → 9.9 mm, because a count spreads evenly over
   the page — and the exact derivation `extent / (count + leading)` puts the phantom next feature
   *precisely* on the far edge, which float accumulation then includes about half the time.
3. **`adjustResize` is wrong for a screen with a page on it.** (Applied in G3; see the browser
   section.)

---

## When you change something here

- **Read kinds through blob-free digests** (`SoilDao.templateDigests`, `ObjectDao.aliveOfType`),
  never `byId` — the pixels are the one thing the decision never needs. `templateRow` /
  `templateImage` are the two calls that cost bytes, and they are never in a listing.
- **A sentinel has no row.** Any query, prune or `IN (…)` over "alive templates" must exclude them
  deliberately — `TemplateShelves.rowIdsAmong` and `.pruneable` are the two places that already do.
- **An elvis after `?.use { }` answers for the lambda, not the receiver.** G4's import refused every
  valid image for exactly this reason: `BitmapFactory.decodeStream` with `inJustDecodeBounds = true`
  returns null **by contract**, and `open(uri)?.use { decode(...) } ?: return Failed` bound the elvis
  to the whole expression. The JVM tests were green throughout — the Android half never got as far as
  calling them.
- **A SAF pick cannot be driven by adb.** DocumentsUI's *chrome* answers injected taps perfectly —
  the roots drawer, the grid toggle, the breadcrumb, Cancel — but its **file items are inert to every
  injectable input**: `input tap`, `touchscreen tap`, 60/150/900 ms presses, `stylus`/`mouse`/
  `trackball`, and TAB focus stops at the first folder card with DPAD refusing to enter the grid. So
  **every path that begins with choosing a file is a user checklist item** — and that checklist is
  what caught the elvis bug.
- **Supernote swallows `adb shell input text`.** Naming a template or typing a query in a device walk
  means tapping the on-screen keyboard. The IME window is invisible to `uiautomator dump`, so its key
  centres come off a `screencap` read as an **image** (the capture is 1:1 with device pixels); the
  dialog's own 350 px shift under the IME is the tell that the keyboard is actually up.
- **A query is not a name.** `NameDialog` is the family's one "type a short string" dialog, and
  reusing it drags `name_problem_title` / `name_empty` along by habit. A dialog reused for something
  that is not a name needs its own words.
- **Verify reuse-before-mint through the log, not by eye.** `re-paper reuses template <id>` with **no
  `re-papered` line at all** is the true no-op; `re-paper minted template` is a fresh megabyte.
- **Frame silence:** the notebook's launch of the browser rides the page sheet's existing exception
  (arc 12) and adds none of its own.
- **A failed *render* is not a missing row, and neither of them is blank paper.** Arc 12's only null
  render was "a page with no size" (impossible), so `mintOrReuse` fell through to `""` — which arc
  13's imported pixels turned into a live path that would **wipe the paper the user can see**.
  `PaperRenderFailed` keeps the two apart: nothing is written, no undo step is pushed, and nothing is
  recorded as recent. Any new paper source has to answer this question too.
- **A rule enforced on create and rename is not enforced on *move*.** The reserved root name was
  checked in three places and missed in the fourth, and `nameTaken` cannot cover for it — **Default
  is not a row**, so the database has nothing to collide with. Every guard that protects a *place*
  needs walking against every way something can arrive there.
- **`toWebp` is lossy q100, and that was measured, not assumed (F5).** It was `WEBP_LOSSLESS` until
  2026-08-27 on the reasoning that a template is line art. On both Supernotes Skia's lossless
  encoder came out ~10x PNG on the page bakes and took **103 seconds** on an imported picture to
  produce a *larger* file than q100 made in 3.7 — and since the encode happens **before** the
  `MAX_BLOB_BYTES` check, it was stalling every import and inflating good pictures into a refusal.
  og Notesprout's `core/ImageCodec` had reached the same conclusion years earlier. **Grid is a real
  exception** — the one case lossless wins, reproducibly on both devices, for reasons nobody has
  explained — but the three built-ins together are 782K lossless against 95K lossy. No migration:
  `BitmapFactory` sniffs the header, so old lossless blobs keep decoding. Re-run `DebugMenu`'s
  **WEBP encoder measurement** before revisiting; **host libwebp cannot stand in for Skia** and says
  the opposite.
- **Card art is `RGB_565`, page bakes are `ARGB_8888` (F5).** A card is erased to white and drawn
  over, so it has no alpha to lose and costs half the memory; a bake is stored, so it keeps full
  depth. `renderWith` takes a `config` defaulting to `ARGB_8888` so the bake cannot be switched by
  accident. **`LinkComposite` is not in this family** — it is drawn *over* the live page and must
  keep its alpha, or a link's whole bounding box paints black.
- **An `LruCache` bound is a count until you give it a `sizeOf`.** The default is 1 per entry, so a
  32-entry bound on page-sized bitmaps was ~30 MB held for the life of the process — while the
  notebook behind the picker still holds the EPD pipeline. It is bounded in bytes now (16 MB), and
  **the bound has to be re-checked whenever the card gets wider**: F4's 3-column Manta grid put a
  card at ~2.1 MB, so the old 8 MB held only three of a six-card page and every flip back
  re-rendered the lot. The bound's job is to hold one whole grid page at the widest card the app
  draws.
- **A soft delete keeps the row; it should not keep the pixels.** A deleted template's blob is up to
  6 MiB nothing can read again. The order is the atomicity: `softDelete` **before** `clearBlob`, so
  no interruption can leave an alive row with no pixels.
- **A pick is state the host owns, so the host has to save it.** `TemplateBrowser.saveState` carries
  only the transfer's `pendingExportId`; New Notebook's chosen card is its own, and it is saved as
  `TemplatePick.encode()`.

---

## One recorded non-fix

**A notebook born from an imported template shows an empty cover card until its first snapshot.**
`TemplatePicks.birthKind` records `"IMAGE"` in the index's `templateKind`; `LibraryGrid` reads that
back as a `TemplateKind`, fails, falls to `BLANK`, and `BuiltInTemplates.placeholder(BLANK, …)`
draws nothing — the same picture a genuinely blank notebook gets.

Left as it is, deliberately. There is nothing honest to draw instead: the *only* thing that knows
what that paper looks like is a blob, and reading one per card is precisely what the blob-free
listing rule exists to prevent. The window is also nearly closed by construction — creating a
notebook **opens** it, and its first `onStop` writes a real cover snapshot that supersedes the
placeholder — so the empty card is reachable only if the process dies before that. Recorded here so
the next reader knows it was seen and weighed, not missed.

---

## Related

`docs/library.md` (the Templates entry point and the sort/name rules the browser reuses) ·
`docs/notebook.md` (the page sheet's **Page template** row, and re-papering as an undo step) ·
`docs/clipboard.md` (the content dedupe a pasted page's template already went through).
