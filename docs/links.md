# Link Objects

> Referenced from `CLAUDE.md`. Covers the data model, chrome rendering, target picker UI,
> tap-to-follow gesture, swipe-up back-stack, lasso/eraser integration, and undo/redo.

---

## Overview

A **link** wraps a lasso selection of any mix of strokes, headings, text objects, and lines into a
single `type = "link"` row. The original objects are soft-deleted; fresh-UUID embedded copies live
inside the link's `data` column. The link draws its content through the existing per-type helpers
and overlays a visual chrome indicator — making the whole group tappable (finger only) to navigate.

---

## Data Model

### Storage (`LinkObject` / `EmbeddedLine`)

- `type = "link"` rows in `.soil`; `LinkObject` serialized to `data` column via `kotlinx.serialization`
- `LinkObject` (`data/LinkObject.kt`) holds: `target: LinkTarget`, `chrome: LinkChrome`, and four embedded-object lists
- Lines use `EmbeddedLine` — a **density-independent** carrier (stores dp values) so the link
  round-trips correctly across devices with different display densities; `LineRender` (px) is never
  persisted
- `toJson()` / `fromJson()` — always use these; never `org.json`

### In-Memory (`LinkRender`)

`LinkRender` (`data/LinkRender.kt`) is the render-time representation, built at load time from DB
rows. It carries the same four object lists converted to render types (e.g. `EmbeddedLine` →
`LineRender` via `toLineRender(density)`).

Key helpers:
- `LinkRender.translate(dx, dy, newId)` — deep-copies + offsets all geometry; used for lasso
  drag-move (same `id`) and paste (fresh `id`)
- `LinkRender.toLinkObject(density)` — converts back to serializable form for DB writes

### Target (`LinkTarget`)

Sealed class (`data/LinkTarget.kt`) with three variants:

| Variant | Behaviour on follow |
|---|---|
| `CurrentNotebookPage(pageId)` | `navigateToPage()` within the open notebook |
| `OtherNotebook(notebookId)` | Opens the target notebook to its last-opened page |
| `OtherNotebookPage(notebookId, pageId)` | Opens the target notebook to a specific page |

File and website targets are intentionally out of scope for Phase 1.

### Chrome (`LinkChrome`)

Plain enum; three values:

| Value | Appearance |
|---|---|
| `NONE` | No decoration |
| `UNDERLINE` | 1dp inkBlack line under the union bbox |
| `DOTTED_CHEVRON` | Dashed-border box around the union bbox + link icon |

`DOTTED_CHEVRON` icon placement:
- **Stroke-only / line-only links** — icon tucked inside the box at bottom-right (3dp padding)
- **Text / heading links** — icon sits **6dp to the right** of the content bbox; the dashed box
  extends rightward to encompass it (prevents overlap with rendered text)

---

## Creation Flow

1. Lasso-select any mix of strokes, headings, text, lines → **Link** toolbar button appears
2. `btnLink.setOnClickListener` stashes the selection in `pendingLink*` fields, launches
   `LinkTargetPickerActivity` via `linkPickerLauncher`
3. On OK, `createLinkFromSelection()` runs (`NotebookActivity`):
   - Computes the union bbox of all selected objects, inset by −6dp (`LINK_CONTENT_PAD`) on all
     sides for breathing room; adds 17dp to the right for `DOTTED_CHEVRON` icon clearance
   - Deep-copies selected objects with fresh UUIDs as embedded content
   - Soft-deletes the original objects
   - Inserts one `NotebookObject` row (`type = "link"`) in a single Room transaction
   - Pushes `UndoRedoAction.LinkCreated`

---

## Editing a Link

- Lasso-select a single link → **Edit Link** button (`btnLinkEdit`) appears
- Relaunches `LinkTargetPickerActivity` pre-populated with the link's current chrome and target
- On OK, `updateLink()` patches only `chrome` and `target` in the DB (embedded content unchanged);
  pushes `UndoRedoAction.LinkEdited`

---

## Removing a Link (`btnUnlink`)

`removeLink(link)`:
- Soft-deletes the link row
- Re-inserts all embedded objects as new top-level rows (fresh UUIDs each time)
- Pushes `UndoRedoAction.LinkRemoved` (undo restores the link, redo re-removes it)

---

## Target Picker UI (`LinkTargetPickerActivity`)

A full-screen picker sharing the same notebook-list / page-grid pattern as the notebook browser.

**Modes (toggled by the sub-toggle):**

| Mode | Tap behaviour |
|---|---|
| Current notebook pages | Shows a page grid; tap → `CurrentNotebookPage` |
| Other Notebook | Shows notebooks; tap → `OtherNotebook` |
| Other Notebook Page | Shows notebooks; tap drills into that notebook's page grid → `OtherNotebookPage` |

- Chrome selection (UNDERLINE / DOTTED_CHEVRON / NONE) lives above the grid; **UNDERLINE is the default** for new links
- Search filters notebooks when in the "other notebook" view
- Used for both **create** (no pre-selection) and **edit** (pre-selected via `EXTRA_INITIAL_*` extras)
- Page cards are labelled by the **top-heading-as-page-name rule** (`data/PageHeadingNames.kt`), the same
  rule the TOC uses — see `content-objects.md`. Note: headings nested **inside link objects** are skipped
  by this rule (intentional, known gap).

### Creating targets in the picker

New targets can be created without leaving the picker via toolbar buttons that appear in the top bar
(left of OK) depending on the current mode:

| Mode | Buttons shown |
|---|---|
| This Notebook (current tab) | **New page** (`ic_page_add`) |
| Other Notebook — browsing folders/notebooks | **New folder** (`ic_folder_plus`) + **New notebook** (`ic_new_notebook`) |
| Other Notebook — Page kind — viewing a notebook's pages | **New page** (`ic_page_add`) |

**New page (This Notebook):** `insertBlankPageRaw` (`data/PageCopier.kt`) writes directly to the
current notebook's `.soil` via `SoilCrypto.openRaw`. If a page is selected in the picker, an
`ActionSheetDialog` asks **Insert Before** / **Insert After**; otherwise the page is appended at
the end. The new page inherits the template of its anchor (or last page; blank if the notebook is
empty). It immediately becomes the selected target. Works for encrypted notebooks (key comes from
`KeySession.getFor(notebookId)`).

**New page (Other Notebook / Page kind):** same `insertBlankPageRaw` helper, but the key is
resolved via `KeySession.getFor` + `KeyResolver.resolveForOpen` (same path as `loadOtherPagesAsync`).
Before/after behaviour and selection are identical.

**New folder:** prompts for a name, validates it (non-blank, no reserved names, letters/numbers/spaces/`_-.`
only, duplicate-sibling check), calls `IndexRepository.createFolder`, then navigates into the new
folder (`directoryStack` push + reload).

**New notebook:** prompts for a name (same validation, no duplicate check), calls
`createBlankNotebook` (`data/NotebookFactory.kt`) to create a blank, unencrypted `.soil` with one
blank page and a `notebook_meta` row, then reloads the browse list. In **Notebook kind** the new
notebook is auto-selected as the link target; in **Page kind** the user taps it to drill into its
pages.

**No undo/redo:** picker-created pages, folders, and notebooks are not added to the undo stack.
Creation is an explicit act (matching `MainActivity`, which does not undo folder/notebook creation).
The link object itself remains undoable as `LinkCreated`/`LinkEdited`.

**NotebookActivity refresh on return:** on *any* picker result (OK or cancel),
`NotebookActivity.reloadPagesPreservingCurrent()` re-reads the page list from the DB and recomputes
`currentPageIndex` from the stable `currentPageId`. This ensures ordering stays correct even when
the picker inserted a page into the current notebook's `.soil` but the user cancelled the link.

---

## Tap-to-Follow Gesture

`handleLinkFollowGesture()` — finger-only, never stylus:

- Active in all toolbar modes **except** text-placement
- DOWN: records position + time, hits `linkAt(x, y)` to find a candidate
- MOVE > touch-slop → cancels
- UP: if candidate still contains the UP point and elapsed ≤ long-press timeout → `followLink()`
- Multi-touch DOWN always cancels (not a tap)

`isLinkFollowEnabled()` gates the gesture (text-placement mode is the only exclusion).

---

## Back-Stack (`LinkBackStack`)

`LinkBackStack` (`data/links/LinkBackStack.kt`) — app-level, SharedPreferences-backed, capped at
50 entries.

- **push** — called on every successful link follow; records `{notebookId, pageId}` of the origin
- **pop** — called on swipe-up back; returns newest entry and shrinks the stack
- **clear** — called in `NotebookActivity.onCreate` whenever `EXTRA_VIA_LINK` is absent (fresh open
  from MainActivity/Recents resets the trail)

### Swipe-Up Back (`evaluateSwipeUpBack`)

Triggered inside the one-finger fling detector. Qualifies when:
- Direction is upward (dy < 0)
- Vertically dominant (|dy| > |dx|)
- Covers the minimum distance fraction of screen height
- Fast enough (velocity) **or** long enough (distance)

On qualify: `followBack()` → pops the stack → same-notebook goes to `navigateToPage()`;
cross-notebook goes to `openLinkedNotebook()` with `origin = null` (walking back, not pushing).

### `EXTRA_VIA_LINK`

`NotebookActivity` carries this boolean intent extra on any link-follow or back-swipe launch.
The target's `onCreate` checks it — present → preserve the back-stack; absent → clear it.
`EXTRA_INITIAL_PAGE_ID` (also on the intent) navigates to a specific page after open.

---

## Lasso & Eraser Integration

Links participate in all lasso operations as first-class objects alongside strokes/headings/text/lines:

| Operation | Behaviour |
|---|---|
| Lasso selection | Box-overlap test against `link.boundingBox` (same as headings) |
| Lasso move | `translate(dx, dy)` + DB bbox/data update; new snapshot |
| Lasso copy / cut / paste | `translate(0,0, newId)` for copy; fresh UUIDs on paste |
| Lasso delete | Soft-delete the link row |
| Hardware eraser | `onLinkErased` callback: soft-deletes the whole link row (embedded content erased with it); pushes `UndoRedoAction.EraseStrokes` with the `linkIds` set |
| Scribble erase | Same — `onScribbleEraseComplete` receives `erasedLinks` as a typed subset |

`eraseAll` (page clear) wipes in-memory links (`drawingView.loadLinks(emptyList())`) in addition
to DB rows, so stale references can't linger.

---

## Rendering

`drawLinkObject()` (both `GenericNotebookView` and `OnyxNotebookView`):
1. Draws embedded headings (grey-fill rect + strokes or recognized text)
2. Draws embedded text objects
3. Draws embedded line objects
4. Draws embedded strokes
5. Calls `drawLinkChrome()` with `iconOutside = link.headings.isNotEmpty() || link.textObjects.isNotEmpty()`

`drawLinkChrome()` handles the three `LinkChrome` variants. For `DOTTED_CHEVRON` with
`iconOutside = true`, the dashed box IS the stored bbox (which already includes the 6dp gap +
14dp icon + 3dp inner pad baked in at creation). The icon is placed inside the box at bottom-right
(`box.right - 3dp - 14dp` to `box.right - 3dp`). The stored bbox is therefore the full visual
extent, so lasso overlap tests against `link.boundingBox` cover the entire chrome.

Links are drawn after page-rule lines and before top-level strokes (see `redrawCanvas` order).

---

## Load Path

`loadLinksFromDb(db, layerId)` (`NotebookActivity`):
- Queries `getLinkObjectsForLayer(layerId)` from Room
- Parses each row's `boundingBox` JSON + `LinkObject.fromJson(data)`
- Maps `EmbeddedLine` → `LineRender` via `toLineRender(density)`
- Returns `List<LinkRender>` passed to `drawingView.loadLinks()`

On page load, if a snapshot bitmap is used, `compositeLinks(bitmap)` bakes link rendering into
the bitmap before `loadStrokesWithBitmap()`.

---

## Undo/Redo Actions

| Action | Undo | Redo |
|---|---|---|
| `LinkCreated` | Soft-delete the link row | Restore the link row |
| `LinkRemoved` | Restore link row; soft-delete the re-inserted original objects | Re-remove link; re-insert original objects |
| `LinkEdited` | Revert `chrome` + `target` | Re-apply new `chrome` + `target` |
| `EraseStrokes` (with `linkIds`) | Restore link rows via `restoreById` | Re-delete via `softDeleteById` |
| `MoveObjects` (with `originalLinks`/`movedLinks`) | Swap back to `originalLinks` geometry | Apply `movedLinks` geometry |
| `PasteObjects`, `CutObjects`, `DeleteObjects`, `CopyPageAfter` | Each carries `links: List<LinkRender>` for in-memory restore | Mirrors stroke/heading handling |

After every undo/redo that touches links, `loadLinksFromDb()` re-reads the DB and calls
`drawingView.loadLinks()` to stay in sync.
