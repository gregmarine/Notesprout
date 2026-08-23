# Notebook — Notesprout SN subsystem doc

Phase **N2** (arc 3 "Headings," hardening at N3). The notebook is a full-bleed g-paper surface with
four chrome overlays: the toolbar, the name strip, the selection's floating bar (now a pair — the
main bar plus its H1–H6 sub-toolbar) while a lasso selection is up, and the "Recognizing…" box during
a heading convert. Everything the paper *draws* comes from g-paper 0.1.4 (`~/git/g-paper/docs/api.md`,
`host-responsibilities.md`) plus the N2 heading `ContentRenderer`; everything the paper *remembers*
comes from the `.soil` via the collaborators below. The one extension point the notebook talks to —
handwriting recognition — is documented separately: [`docs/extensions.md`](extensions.md).

Fresh code. Paper v0 (`git show 87277da:apps/notesprout_paper/...`) is the shape reference for the
arc-1/arc-2 shape; og Notesprout and Paper's own heading arc are the reference for N2. The
deliberate differences are listed at the end.

---

## Collaborators (`notebook/`)

| File | Owns |
|---|---|
| `NotebookActivity` | lifecycle, wiring, chrome, exclusion rects, immersive mode, `IndexGuard`, the close sequence; the gesture → operation wiring and the undo/redo replay |
| `NotebookSession` | the open `SoilDatabase`, `pages: List<PageRef>`, `currentIndex`, decoded template bitmap; `open()`, `goTo()`, `insertBlank()`, `deleteCurrent()`, `reconcile()`, `saveLastOpened()`, `refreshMeta()`, `seal()` — all IO |
| `PageMath` | pure page arithmetic: `indexAfterDelete`, `insertPosition`, `toRestore`, `toDelete`. JVM-tested |
| `PageGestures` | the finger vocabulary over the paper — observer only, fed from `dispatchTouchEvent`, consumes nothing |
| `UndoRedoStack` | notebook-level in-memory history; pure ordering, bounded at 100; the N2 heading actions live here too |
| `SoilWriter` | the session's **single serial write queue** (N2: extracted out of `StrokeStore` so `StrokeStore` and `HeadingStore` share it) — one IO coroutine draining a `Channel` of jobs, so a stroke soft-delete and the heading row it converted into always land in the order they were enqueued; the debounced index `updatedAt` bump; `drain()`/`flushTouch()` for the seal path |
| `StrokeStore` | g-paper callbacks → `stroke` rows through the session's `SoilWriter`; `loadPage()`, `commit()`, `erase()`/`remove()`, `revive()` (in-place un-delete — since N3 the only way strokes come back, because the page's writing order is load-bearing for recognition), `move()` |
| `StrokeRows` | pure mapper `Stroke ⇄ SoilObjectEntity` (format-B blob, `InkColorCodec`, `StrokeStyle` name; unknown → PEN). JVM-tested |
| `HeadingStore` (N2) | `heading` rows through the same `SoilWriter`: `loadPage()`, `create()`, `erase()`, `restore()` (in place — geometry, order and `createdAt` all survive), `move()`, `updateContent()` |
| `HeadingRows` (N2) | pure mapper `Heading ⇄ SoilObjectEntity` (`SoilSchema.TYPE_HEADING`); also the `Heading` data class itself. JVM-tested |
| `core/markdown/HeadingPrefix` (N2) | the heading row's `text` ↔ level contract — `headingPrefix`/`stripHeadingPrefix`/`applyLevel`; level is authoritative, the prefix is only ever *written from* it. JVM-tested |
| `HeadingRenderer` (N2) | the g-paper `ContentRenderer` that paints `liveHeadings` into the committed layer (`BELOW_STROKES`), plus the static `measure()` both the convert flow and the edit dialog size from |
| `HeadingConvert` (N2) | ink → title: discovers the recognizer, drives `RecognizerReadiness` + the "Recognizing…" box, hands back a one-line title or explains why not |
| `HeadingEditDialog` (N2) | the hash-free "fix a heading's words" dialog — prefill/strip, empty Save = delete, never hides the IME |
| `core/RecognizingOverlay` (N2) | the "Recognizing…" box during a convert — `OpeningOverlay`'s smaller, dialog-free sibling |
| `notebook/InkPayload` (N2) | `Stroke` (g-paper) → `InkStroke` (extension-api) in writing order — the one place a page's ink is reduced to bare geometry for the recognizer |
| `CoverSnapshot` | `paper.renderToBitmap()` → ≤ 512 px long edge → WEBP q100 → `IndexRepository.setCover`; headings ride along for free (`HeadingRenderer` is part of the same committed-layer render) |
| `NotebookToolbar` | `[←] [pen] [eraser] [lasso]` — arming only; owns the fixed tool values |
| `SelectionToolbar` | the floating bar over a live lasso selection: Delete (always) + H (hidden only in `MIXED` mode), plus (N2) the H1–H6 level sub-toolbar it can open |
| `SelectionAnchor` | pure placement arithmetic for the bar (centre / gap / flip / clamp) and (N2) `placeSub` — the sub-toolbar hung off the bar the same way. JVM-tested |
| `core/OpeningOverlay` | the source-side "Opening…" box and its pre-draw + post launch sequencing |
| `OutlineTree` (C1) | pure Contents tree: items → nested H1–H6 nodes (orphans attach to the nearest shallower heading or become roots — never dropped), `visible`/`all`/`highlight`/`ancestorsOf` and the paging math. JVM-tested |
| `ContentsLayout` (C1) | pure Contents layout rules: the 480 dp sidebar/full-screen branch, 60 % sidebar width, 68 dp rows, `(level−1)×16 dp` indent, `itemsPerPage`. JVM-tested |
| `ContentsSource` (C1) | the gather (IO): writer drain → `liveHeadingsAll()` → the pure `items()` pass (live-page filter, `stripHeadingPrefix` label, `flags` level, document order, the 2000 cap) → `OutlineTree.build`. No cache — rebuilt every open |
| `ContentsFlow` (C1) | what both entry points call: busy guard, the `available` gate + generation-counted `refresh()`, pen-gated `releaseRender`, gather → `ContentsDialog`, `showing` (drives the host's BLOCK_ALL), `dismissIfShowing()` for the close path. Owns `btnContents` outright |
| `ContentsDialog` (C1) | the Contents screen: one layout, two forms (sidebar/full-screen), paginated rows, collapsible tree, active-entry highlight, tap = navigate |

## Layout (`activity_notebook.xml`)

`FrameLayout` root → `paperContainer` (the `PaperView`, added in code — `GPaper.create` needs a
Context) → `topBar` overlay (flush at the top edge — the top guard is 0 on Ratta; 1dp inkBlack
bottom border) → `bottomStrip` overlay ("`<name>` `n / N`", 1dp top border) → `selectionToolbar`
(floating, `GONE`, placed by margins) → `selectionSubToolbar` (N2 — its own floating `GONE` bar,
placed by `SelectionAnchor.placeSub` off the main bar when H is tapped) → `openingOverlay` (an
`<include>` of `overlay_opening.xml`, **last child so it is topmost**, and `VISIBLE` from the first
frame). Immersive: system bars hidden, transient by swipe. Portrait-locked.

Both bars — and both selection bars while they are up — are pushed to `paper.setExclusionRects`
after every root layout pass, translated into the paper view's coordinates, so the stylus can never
ink under chrome. The push is driven by a layout-change listener on the root, which fires for any
child's `requestLayout`, so showing/moving/hiding a floating bar re-pushes by itself. A finger
`ACTION_DOWN` over chrome calls `releaseRender()` first (palm-gated on `isPenActive`) so an EPD
panel shows the tap's result — done in `dispatchTouchEvent` because the buttons consume the touch.

The "Recognizing…" box (`overlay_recognizing.xml`, N2) is **not** part of this layout — like the
library's tap-time overlay it is inflated at runtime into `android.R.id.content` (`RecognizingOverlay`,
cached per Activity), which lands it as a sibling above the whole `activity_notebook.xml` tree
without owning a spot in it.

## Toolbar — fixed tools (P1)

Paper v0's bar shape, and Paper v0's fixed tools. **There are no panels and nothing is
remembered.**

| | |
|---|---|
| Pen | `StrokeStyle.PEN` · `InkColorCodec.BLACK` · `NotebookToolbar.PEN_WIDTH_PX` = **3 px** |
| Eraser | `NotebookToolbar.ERASER_RADIUS_PX` = **15 px** |
| Smart lasso / scribble erase | hardwired **on**, set on the surface in `onCreate` |

- A tool tap **arms**; a second tap on the armed tool is a **no-op** (Paper style — og
  Notesprout's eraser-toggles-back-to-pen was declined). Nothing to configure means nothing for a
  second tap to open, and a button that disarmed itself would leave the pen doing something the
  bar is not showing.
- R3's rich panels (five widths, five styles, sixteen greys, four eraser radii) and R5's lasso
  panel are **gone**, with `ToolPrefs` (`SharedPreferences("sn_tool")`) and the whole page-tap /
  stylus-pen-up panel-dismiss machinery in `dispatchTouchEvent`. Handwriting is the app; a bar that
  only arms is one less thing between the pen and the paper, and a chrome surface that could sit
  open over the page is one less thing to dismiss. `SnApplication` deletes the stale `sn_tool`
  prefs file once at start, on a background thread (harmless when it is already absent).
- **Existing strokes are untouched** — width, style and grey travel in the row, so everything
  written under R3–R6 still renders exactly as authored. There is no migration and no format
  change; only *new* strokes take the fixed values.
- The two recogniser flags are armed **before** `setPaperListener` (the engine reads them as it
  wires itself up) — the order is load-bearing.
- Every handler calls `releaseRenderIfIdle()` first — `releaseRender()` **gated on
  `!paper.isPenActive`**, the API contract: an ungated release inside the pen-active window can
  cost a live stroke. Selected = the `state_selected` bordered look of `bg_toolbar_button`. Button
  state is driven from `PaperListener.onToolChanged` (`toolbar.sync`) — the component
  arms/restores tools itself. **This is not optional now that smart lasso is on:** the engine
  switches to `Tool.LASSO` on trigger and restores `Tool.PEN` when the selection lifecycle ends,
  and **the PEN restore can land *after* `onSelectionDismissed`** (a pen tap-away dismisses at
  pen-down but restores at pen-up). Never sync the toolbar by re-reading `paper.tool` inside a
  selection callback — it will be a tool behind.

### Known issues (R3 eye check)

- **MARKER live ≠ baked** — documented g-paper behaviour (Ratta has no semi-transparent live
  style; live draws `NEEDLE`, the bake is core's true semi-transparent rendering). Deferred out of
  the ratta arc — see the monorepo `BACKLOG.md`.
- **One unreproduced lost stroke** (single occurrence): ink that showed live, vanished on close,
  absent after reopen — i.e. it never reached the engine's model (`onStrokeCommitted` fires
  synchronously at pen-up and the seal path drains the writer, so a *committed* stroke cannot be
  lost this way; overlay-only ink can). Leading suspects were a stale exclusion-rect window around
  a panel toggle filtering captured points, or a raw-delivery drop in the ink daemon (the
  4th-overlay-law family). The R3 hardening (pen-gated releases) narrowed one path and **P1 removed
  the panels entirely**, which removes the first suspect's mechanism altogether; the exclusion rects
  now only change when the selection toolbar appears or moves. Never reproduced through R4–R6 — if
  it returns, instrument `onStrokeCommitted` vs. the overlay and fix in g-paper.

## Open

`IndexGuard.ready` → extras (`EXTRA_NOTEBOOK_ID`, `EXTRA_NOTEBOOK_NAME`) →
`BrowseState.lastOpenNotebookId = id`, `RecentsPrefs.record(id)` → `repo.alive(id)` (else problem
dialog + finish) → `session.open()`: `KeySession` passphrase → file must exist and be non-empty
(**never created here**) → `SoilDatabase.open` (raw-key fast path via `KeyOpener` when cached) →
page rows (none → fail) → last-open page from the notebook row's `refId` → template decoded with
`Bitmaps.decodeBounded` (≤ 4096 px). Then on Main: `setPageSize(w,h)` (the page's authored px
rect, so ink registration survives a different screen), `setTemplate`, headings loaded
(`session.headings.loadPage`, handed to `headingRenderer` **before** `loadStrokes` so the load's
re-record already paints them), `loadStrokes(store.loadPage(id))`, page indicator.

A failed open is a **problem dialog** (SN's toast-confirms / dialog-explains rule), OK → finish —
including a crash *mid*-open (the R6 hardening: `openSession`'s catch turns any non-cancellation
throw into the same dialog instead of an uncaught-in-scope crash). `failOpen` takes the "Opening…"
box down **first**: it shields every touch under it, and an OK button that cannot be tapped is a
dead screen.

### Recognizer warm-up (N2)

Once the page has landed (after the "Opening…" box comes down, never in its critical path),
`warmUpRecognizer()` fires a single fire-and-forget `status()` bind: it starts the recognizer
extension's process if it isn't already running, whose own `onCreate` builds its ML Kit client from
an **already-present** model and primes the engine off the Binder thread — so the session's first
real heading conversion doesn't pay the model's lazy first-inference load. It can never trigger a
download (only `prepare()` may, and that lives behind the consent dialog `RecognizerReadiness` owns
— see [`docs/extensions.md`](extensions.md)), so **opening a notebook never shows the user
anything**: no recognizer installed, or one that doesn't answer, is a silent non-event either way.

### The "Opening…" overlay (P1)

Opening a notebook is the one slow navigation in the app, and on e-ink a tap that produces no frame
for hundreds of ms reads as a tap that missed. The box is shown across the whole gap, by both ends:

- **Source side** — `core/OpeningOverlay.showThen(activity) { startActivity(…) }`, wrapped around
  the library's single `openNotebook` door. It inflates `overlay_opening.xml` into
  `android.R.id.content` (cached per activity), makes it `VISIBLE`, `bringToFront()`s it, and then
  **waits for `onPreDraw` and `post`s the launch**. That sequencing is the whole point:
  `Dispatchers.Main` is an *async* Handler, so a coroutine — or a bare `startActivity` in the tap
  handler — jumps the view traversal's sync barrier, the source pauses, and the overlay never draws
  at all. It auto-hides on the first `ON_RESUME` after an `ON_PAUSE`, so the library is clean when
  the user comes back; a source that finishes itself simply dies with its overlay.
- **Destination side** — `activity_notebook.xml`'s `openingOverlay` include is the last child and
  starts `VISIBLE`, so the notebook covers its own first frame and there is no gap after the
  source's box. It goes `GONE` at exactly two places: right after `opened = true` + the real
  `pushExclusions()`, and at the top of `failOpen`.

The root is transparent but `clickable`, so it swallows the second tap a slow refresh invites while
leaving the screen underneath visible — only the box's region repaints.

**The hide is deliberately not pen-idle-gated.** `isPenActive` counts *hover*, and the user's pen is
already over the glass on the way to writing, so the gate would hold the box up over the page they
asked for. It is a boundary frame, not a frame during writing — nothing has been drawn yet.

**The surface accepts no ink until the page is loaded (R6).** The toolbar arms the pen from the
first frame, but a stroke committed before `opened` would hit the listener's guard, never reach the
store, and be silently wiped by `loadStrokes`. So `pushExclusions` pushes one **block-all rect**
while `!opened` (set up in `onCreate`, before the first layout pass) and swaps to the real chrome
rects the moment the page lands.

**An abandoned open still seals (R6).** Back during the open window (a cold raw-key miss is ~1 s of
KDF) cancels `lifecycleScope` while `SoilDatabase.open` may already have completed — and `close()`
early-exited on `session.isOpen == false`, so nothing else would ever close that handle.
Both layers clean up: `NotebookSession.open` seals on any throw after the handle opened
(`NonCancellable` — the scope *is* being cancelled), and `openSession`'s catch seals a session that
opened but never reached `opened = true`, on `appScope` (`sealAbandonedOpen`).

## Persistence

| g-paper callback | Row effect (serial IO) |
|---|---|
| `onStrokeCommitted(s)` | insert `stroke` row, `"order"` = `MAX("order")+1` among the page's strokes (live **and** deleted — order stays monotonic) |
| `onStrokesErased(ids)` | soft delete (`deletedAt`) — **also the scribble-erase path**: the engine reports a consumed scribble through this same callback, so persistence and undo needed no change for it |
| `onSelectionMoved(m)` | read rows → decode → `Stroke.translated(dx,dy)` → re-encode → upsert (`createdAt` kept); headings among `move.contentIds` (N2) get the same delta through `session.headings.move` and the working copy is patched too; `currentSelection`'s bounds shift by the same delta, and the selection toolbar re-anchors there |
| `onSelectionCreated/Dismissed` | `selectionActive` flag + the `currentSelection` copy + show/hide the selection toolbar (N2: `onSelectionDismissed` also consumes `pendingSelection` — see below) |
| `onSelectionTapped(x, y)` (N2) | hit-tests `currentSelection`'s heading ids against `liveHeadings`; a hit opens `HeadingEditDialog`. A tap over ink only, or outside any heading's bounds, still does nothing |
| `onContentErased(ids)` (N2) | the eraser tool swept a heading whole: `session.headings.erase` + drop from `liveHeadings` + re-record, recorded as `Action.HeadingDeleted` |
| `onSelectionDragStarted()` | hide the selection toolbar (the mirror is **not** cleared) |
| `onToolChanged` | toolbar sync only |

**`onSelectionTapped` is overridden again as of N2** — P1's "nothing left to open, the bar is
already showing" held until headings gave a tap inside the box something new to do: open the one
heading it landed on for editing. A tap that hits only ink, or a selection with no heading in it at
all, still falls through to nothing — the bar covers everything else.

These also maintain `liveStrokes` and (N2) `liveHeadings` — the Activity's working copies of what
is on the visible page. `liveStrokes` is the only place an erased stroke's geometry still exists
once the engine drops it (a delete/undo needs it); `liveHeadings` is what `HeadingRenderer` actually
paints from, kept in step with every row write so a re-record never shows a stale position or size.
Each callback records the matching `UndoRedoStack.Action`.

**Page attribution: `displayedPageId`, never `session.currentPage` (R6).** The callbacks stamp
their rows with the Activity's `displayedPageId` — written on Main only, at the two places
`loadStrokes` runs (`openSession`, `navigateTo`). The session's `pages`/`currentIndex` mutate on IO
mid-flip (`goTo` advances the index *before* the swap reaches the paper), so a pen-up racing a flip
would otherwise persist ink to the destination page — and a torn read of the pair could crash. What
the user inked is the page they were looking at.

Every write schedules a trailing-debounced (2 s) `IndexRepository.touch(notebookId)` — the
`updatedAt` discipline: the card's "last modified" follows ink, one UPDATE per burst, flushed on
close. Ink is durable the moment the row lands (WAL); a process kill loses at most the strokes
still queued in the channel.

## Selection (R5, context toolbar P1, headings N2)

**The engine owns every mechanic.** Outline capture, the hit test, the static dashed selection box
(the tight bounds inflated 12 px so a thin selection stays grabbable), the drag preview, the
in-memory translate, dismissal, and the Ratta firmware dash trail all live in g-paper. The host's
whole job is to mirror the result into rows and history. Lasso in arc 1 is **move + delete** (the
R5 phase decision); N2 adds headings to what a selection can hold (`Selection.contentIds`, not just
`strokeIds`) without changing that decision — a heading moves and deletes exactly like ink does.

`currentSelection: Selection?` is the host's copy of what is selected — set in
`onSelectionCreated`, shifted in place on `onSelectionMoved` (the engine keeps the selection alive
at its new position), nulled in `onSelectionDismissed` and in `navigateTo`'s `clearSelection`. It
exists for one reason: a delete (or a tap-to-edit hit test) needs the selected ids *after* the tap
that asked for it. It is never read as "is anything selected" — `selectionActive` is that flag, and
it is what the gesture detector stands down on. Selections also arrive **host-initiated**: N2's
`selectAsHeading` calls `paper.setSelection` directly after a create/edit/level-change lands the
selection on the heading's new box, with no `onSelectionCreated` echo — so the flags and the
toolbar are set there by hand rather than waiting on the callback.

| Act | What happens |
|---|---|
| Draw a lasso outline (or a smart-lasso loop) | engine draws the box; host shows the **selection toolbar** anchored to it, in one of three modes (below) |
| Drag inside the box | `onSelectionDragStarted` hides the bar(s); the engine translates + re-renders; `onSelectionMoved` → `store.move`/`headings.move` + working-copy patch + `Action.Moved`, then the bar re-anchors at the new bounds |
| Tap the bar's **Delete strokes** | `releaseRender()` then `deleteSelection` (order below) |
| Tap the bar's **H**, then a level (N2) | `onLevelPicked` — CONVERT on a pure-stroke selection, CHANGE on a lone heading (below) |
| Tap inside the box, over a heading (N2) | `onSelectionTapped` hit-tests `liveHeadings` and opens `HeadingEditDialog` |
| Tap inside the box, over ink only | nothing |
| Tap outside / tool change / any data-in call / page swap | `onSelectionDismissed` → bar(s) hidden, mirror cleared (unless a converted heading's selection is waiting to take its place — see Headings below) |

### The selection toolbar

A bordered row floating over the paper: **Delete** always, plus (N2) an **H** button that opens a
second floating bar of its own, the H1–H6 level sub-toolbar (`SelectionMode` and the convert/change
flows are covered under Headings below). It is a *bar*, not a button, because it is the shape the
selection's actions live in from here on.

It replaces R5's tap-inside-the-box action sheet. The sheet asked for a second deliberate act on top
of the lasso the user had just drawn, and on e-ink a dialog is a full-screen repaint; the bar is
already there when the selection appears.

**Placement** (`SelectionAnchor`, pure and JVM-tested, all values px in the root's space):

```
x = centre of the selection − w/2,  clamped to [0, rootWidth − w]
y = selBottom + gap                              (gap = 8 dp)
    → if y + h > bandBottom:  y = selTop − gap − h        (flip above)
    → clamped to [bandTop, bandBottom − h]
```

`bandTop` / `bandBottom` are the top bar's bottom edge and the bottom strip's top edge, so the bar
can never land under chrome where its own taps would be eaten. Below-then-flip is the order because
the hand that just drew the lasso is below the selection. `Selection.bounds` arrive in **paper**
coordinates and are inflated by 12 px first — `CanvasPaperView.SELECTION_BOX_INFLATE_PX`, the box
g-paper actually draws — then shifted by the paper view's offset inside the root; the gap is
measured from visible chrome, not the tight rect. `SelectionToolbar` measures the bar
(`MeasureSpec.UNSPECIFIED`) before asking, because the anchor centres and flips on its real size,
and places it with `FrameLayout.LayoutParams` margins.

**Lifecycle.** Shown at `onSelectionCreated`, hidden at `onSelectionDragStarted` (a bar dragged
along with the box would have to follow live ink), re-shown and re-anchored after `onSelectionMoved`
(which fires at lift), hidden at `onSelectionDismissed` and in `navigateTo` (idempotent —
`clearSelection` fires the dismissal too). `hide()` never clears `currentSelection`; only the
dismissal does. Every `show()` also closes the sub-toolbar if it is open (N2) — a fresh selection,
or a re-anchor after a move, is a new decision and should not inherit the last one's open drawer.

**Chrome, like any other.** Both bars' rects join `pushExclusions` (so the pen cannot ink through
them) and `overChrome` (so the finger paths treat them as chrome). Both come free of scheduling: the
root's layout-change listener fires for any child `requestLayout`, which showing, moving or hiding
either bar always triggers.

**Delete order matters.** Capture the stroke geometry from `liveStrokes` *first* — it is the only
place it still exists once the engine drops the strokes — patch the heading working copy (N2)
*before* `removeStrokes` (its re-record is the frame that drops both strokes and headings), then
`paper.removeStrokes(ids)`, then `store.erase(ids)`/`session.headings.erase(headingIds)`, then drop
the ids from the working copies, then record one `Action.Deleted` carrying both. `removeStrokes`
dismisses the selection itself (every data-in call does), so there is no explicit `clearSelection`
in the stroke-delete path — a heading-only delete (no strokes to drop) does call it, since nothing
else would; the resulting `onSelectionDismissed` clears the host's copy either way. Nothing captured
(which should not happen) still removes and erases, but records no history — better no undo entry
than one that restores nothing. The Delete button calls `paper.releaseRender()` before the row
runs — the same tap-escrow safety the R5 sheet had: the tap must show its result, and the delete
repaints the page underneath.

**No confirm dialog.** The button is on a bar the user summoned by drawing a lasso, it says exactly
what it does, and the delete comes straight back with undo — the same reasoning that stripped the
page-delete confirm's warning body in R4 (eye-check #2). A dialog would be ceremony.

**Frame silence.** Showing the bar at `onSelectionCreated` is an app frame, and it is deliberately
**not** pen-idle-gated: a lasso ends with the pen still hovering over the glass (`isPenActive`
counts proximity + a 350 ms tail), so an idle gate would deliver the bar long after the selection it
belongs to — the R3 panel lesson. It is safe because the engine has *already* presented the
selection box on this same boundary: this frame is part of that presentation, not a repaint during
writing. See the frame-silence section for the full list.

## Headings (N2)

Headings are baked into the core — an additive row type on the family's universal `notebook`
table, not an extension of any kind. og Notesprout's model, ported fresh: a heading is recognized
handwriting turned into a title, rendered as real (markdown) text, first-class in lasso move/delete
and undo, editable by a tap. The recognizer that turns ink into words is SN's one extension point
(`docs/extensions.md`); everything downstream of the recognized string — storage, rendering, the
toolbar, undo — is core.

### Data model

`SoilSchema.TYPE_HEADING = "heading"` on the same universal row shape every other object uses:

| Column | Holds |
|---|---|
| `parentId` | the page id |
| `text` | **hash-prefixed markdown**, e.g. `"## Meeting notes"` — **always non-null**. A heading with no recognized text never exists in SN (the og null-text stroke-fallback state is deliberately absent — recognition either produces a title or nothing is created) |
| `flags` | the level, 1–6, **authoritative**. The prefix is only ever *written from* the level via `HeadingPrefix.applyLevel`/`headingPrefix` — **never hardcode `"# "`**, and the level is never derived by parsing the prefix back out |
| `x` / `y` / `width` / `height` | the box in page px |
| `"order"` | z-order among the page's headings (not the page's strokes — a separate counter, `HeadingStore.create` takes `MAX("order")` **among heading rows only**) |

No `SOIL_VERSION` bump, no migration: this is the same additive pattern R6 already proved safe in
reverse (SN ignoring Paper's `object`/`link` rows) — Paper opens a file with `heading` rows and
simply never queries for that type, so the rows sit inert rather than corrupting anything.

`HeadingRows` is the pure `Heading ⇄ SoilObjectEntity` mapper (a malformed row — missing `text` —
decodes to `null` and is dropped rather than crashing the page); `Heading` itself carries a derived
`bounds: Bounds` and a `translated(dx, dy)` used by the move path.

### Rendering

`HeadingRenderer` is a g-paper `ContentRenderer`, registered on the paper before any page load so
the very first re-record already knows about headings. Its `layer` is `ContentLayer.BELOW_STROKES`
— og parity: ink written over a heading stays visible on top of it, the reverse of how the selection
box itself is drawn. Text goes through the N1 markdown engine (`core/markdown/MarkdownDraw`,
`MarkdownParser`, `MarkdownRenderer`): the stored `"## Title"` is parsed into a heading block and
rendered over one base `TextPaint` (`HeadingTypography.BASE_SP` = 24sp, always bold) scaled per
level — ×2.0 / 1.75 / 1.5 / 1.25 / 1.1 / 1.0 for levels 1–6, h6 sitting at body size and
distinguished by weight alone. Every heading draws **single-line, END-ellipsized**, with
`HeadingTypography.PADDING_DP` (8dp) on every side — a title that wrapped would push whatever comes
after it out of place every time the text grew.

**Free growth** (the wizard's answer, not a clamp): `HeadingRenderer.measure` lays the text out
against an effectively unbounded width (1,000,000 px), so the box takes the text's true measured
width even past the page edge — the overhang is simply not visible, never wrapped or shrunk to fit.
The same static function sizes a heading at creation, at a level change, and after an edit, so the
stored box and the drawn box can never disagree. And because the measure depends on the **writing
device's** text metrics (`scaledDensity`) while the box is stored in page px, every page load also
re-measures its headings for the current device (`remeasureForDevice`, in memory only — position is
authored and kept, size is derived and recomputed): a font-scale change, or the portable `.soil`
opening on a different-density Supernote, would otherwise ellipsize every existing heading and
leave stale hit/selection bounds (N3 review finding).

`HeadingRenderer` implements the **live-drag pair** the `ContentRenderer` contract asks for:
`draw(canvas, excludedContentIds)` skips whatever id the drag is currently ghosting, and
`drawObject(canvas, contentId)` paints that one heading at its live position — so a dragged heading
rides under the pen as its real self (real text, not a dashed placeholder) instead of freezing at
its origin while a ghost box follows the finger. `hitTargets()` reports one `HitTarget` per heading
(its bounds), which is what makes a heading lassoable, tappable, and sweepable by the eraser at all.
The renderer only re-records on `notifyContentChanged()` or the engine's own data-in calls — never
per frame — so `headings` (the screen's working copy, `liveHeadings.values.toList()`) is safe to
mutate freely between those points.

Covers pick up headings for free: `CoverSnapshot.capture` calls `paper.renderToBitmap()`, which
walks the same committed-layer render path `HeadingRenderer` is registered into — no separate
heading-aware cover code exists or is needed.

### The toolbar: H button and the H1–H6 sub-toolbar

`SelectionToolbar.show` classifies every selection into a `SelectionMode` and shows the bar
accordingly:

| Mode | When | H button |
|---|---|---|
| `STROKES` | ink only, no content ids | shown — tapping it opens the level picker in **CONVERT** |
| `HEADING` | `contentIds` is exactly one heading and `strokeIds` is empty | shown, and the sub-toolbar (once opened) highlights the heading's **current level** with a 1dp inkBlack border — this is **CHANGE** |
| `MIXED` | anything else (ink plus a heading, or more than one heading) | hidden — Delete only, because there is no single sensible level to write |

Tapping **H** doesn't grow the bar or swap its buttons — it opens a **second floating bar**, the
H1–H6 sub-toolbar, hung off the main bar by `SelectionAnchor.placeSub` (eye-check #5 round 1: the
first cut grew a second row under the main bar, which the user rejected in favour of the og/Paper
floating-popup shape). `placeSub` mirrors `place`'s below-then-flip-then-clamp logic, but anchored
to the *bar's own placement* rather than the selection: below the bar normally, above it when the
bar itself already flipped (and the reverse if that would leave the band) — so opening the level
picker never moves the Delete/H bar the user just aimed at. Every `show()` closes the sub-toolbar
first: a fresh selection should never inherit the last one's open drawer.

### The convert flow (CONVERT)

A level tap on a pure-stroke selection calls `startConvert`, which captures **everything the
creation will need right now** — the selection's `strokeIds` filtered out of `liveStrokes` (a
`LinkedHashMap`, so this preserves **writing order**; never iterate `Selection.strokeIds` itself,
which is a `Set`) and the selection's bounds — because the recognition call is async and the
selection can die (a tap-away, a page flip) before it answers. `HeadingConvert.run` then:

1. re-discovers the recognizer extension (never a cached reference from a previous tap);
2. runs `RecognizerReadiness.ensureReady` — consent dialog → download progress → ready, or a
   problem dialog and give-up (see `docs/extensions.md`);
3. shows the **`RecognizingOverlay`** ("Recognizing…") box — `OpeningOverlay`'s smaller, dialog-free
   sibling, up for the width of the recognize call and down before anything else goes on screen;
4. calls `recognizeInk` with the **selection's bounds**, not the page, as the writing area.

That last point is the N2 eye-check root cause, worth stating exactly: SN's page pipeline
recognizes per line using the line's own box, and Paper's H action passes the selection bounds for
the same reason — ML Kit reads the writing area as the *scale* of the ink. Passing the whole page
under a single lassoed title made the model guess at the wrong scale and return fragments
("Heading" → "o"/"Go"). Passing `sel.bounds.width`/`height` fixed it outright.

A non-blank result (whitespace collapsed to single spaces, trimmed) reaches
`createHeadingFromConversion`: `HeadingPrefix.applyLevel(title, level)` builds the stored text,
`HeadingRenderer.measure` sizes the box anchored at the ink's top-left, `session.store.erase` soft-
deletes the consumed strokes and `session.headings.create` inserts the row — **recorded as one
`Action.HeadingCreated`** (the heading plus the stroke ids it consumed), not two undo steps, because
to the user it was one act. A blank result, a too-dense selection, a not-ready extension, or any
call failure is the locked failure path: a problem dialog, and **the lassoed ink is left exactly as
it was** — a heading is never half-created, and there is no null-text placeholder state to clean up
(that state doesn't exist in SN at all).

**Selection handoff, the eye-check #5 round-2 fix.** The new heading must land selected — the user
just made a title and the natural next act is to look at it, tap it, or pick a different level — but
naively calling `setSelection` after `removeStrokes` raced the engine's own smart-lasso bookkeeping:
`removeStrokes` dismisses the *old* selection, and g-paper's `maybeEndSmartLassoSession` restores
`Tool.PEN` right there when it sees no successor — landing PEN armed while a heading sat selected
underneath it, which a PEN tool can neither drag nor tap. The fix is a `pendingSelection: Heading?`
field: set immediately before `removeStrokes` fires, and **consumed inside
`onSelectionDismissed`** — because the engine checks for a successor selection *after* that
callback runs, so injecting the new selection from inside it keeps the smart-lasso session alive
across the conversion. The engine then treats the heading's own selection exactly like any
smart-lasso session, restoring PEN only when *that* selection is eventually dismissed. (A defensive
drain right after `removeStrokes` also consumes `pendingSelection` directly, for the rare case the
selection had already died mid-recognize and no dismissal callback ever fired.)

**Doodles always convert to something.** ML Kit is a forced-choice recognizer — it essentially
never returns a truly blank result, and candidate confidence scores aren't comparable across
different input, so no score threshold could reject "junk" without also false-rejecting real
writing some of the time. A lassoed doodle becomes a heading reading "o" or similar, landing
selected — the same one-gesture Delete or an undo removes it. This was raised at eye-check #5 round
3 and **accepted as designed**; og and Paper behave identically. Do not re-raise a doodle-rejection
heuristic.

### Tap-to-edit, change level, and eraser

**Tap-to-edit** (`onSelectionTapped`): a sub-threshold tap inside a selection box that lands inside
one of the selection's headings (`liveHeadings[id].bounds.contains(x, y)`) opens `HeadingEditDialog`
— a single-field dialog in the `NameDialog` shape. The field is **hash-free**:
`HeadingPrefix.stripHeadingPrefix` shows the bare title, and the level lives entirely outside the
field (it is authoritative in `flags`, never re-derived from typed `#` characters). Save re-applies
`HeadingPrefix.applyLevel(raw, before.level)` and re-measures; **an empty Save is not a validation
failure, it is a delete** — clearing the words is how a heading is taken back off the page, recorded
as `Action.HeadingDeleted`. A Save that produced the same text as before is a no-op (no row write,
no undo entry). **Ratta IME rule**: the dialog never hides the soft keyboard — the same rule as
`UnlockActivity` — because a hardware keyboard on Supernote only delivers keystrokes while the IME
panel is shown; the one soft-input call here (`SOFT_INPUT_STATE_VISIBLE`) only ever asks for it.

**Change level** (`onLevelPicked` in `HEADING` mode → `changeHeadingLevel`): re-prefixes at the new
level, re-measures, and **keeps the top-left corner** — a heading grows or shrinks from its anchor
rather than wandering the page. Recorded as `Action.HeadingLevelChanged` (before/after `Heading`
snapshots, not just the level number, so replay can restore the exact prior box). Both this and a
text edit re-select the heading afterward (`selectAsHeading`) — its box just moved or resized, so
the stale selection frame has to be replaced with a fresh one at the new bounds.

**Eraser sweep** (`onContentErased`): the eraser tool (0.1.4) can report that it swept a heading's
hit target whole, in the same batched callback per gesture that scribble-erase never populates (a
scribble consumes strokes only). The host deletes on the engine's word — nothing vanishes by
itself — recorded as `Action.HeadingDeleted`.

There is **no un-heading / revert-to-ink command** (og parity) — a heading, once created, is either
edited, re-leveled, moved, or deleted; going back to raw strokes is not a supported operation.

### Undo actions

Five heading-specific `UndoRedoStack.Action` kinds, all replayed the same way as everything else —
mutate the store, `drain()`, then `refreshToPage` (the `.soil` is the source of truth, so what the
paper shows after a replay is what a reopen would show):

| Action | Reverts to | Reapplies to |
|---|---|---|
| `HeadingCreated(heading, strokeIds)` | delete the heading row, **revive the strokes in place** (`StrokeStore.revive`, not `restore` — writing order must survive a later re-recognize) | restore the heading row, remove the strokes again |
| `HeadingDeleted(headingIds)` | restore the rows in place (position/size/level/order untouched) | erase them again |
| `HeadingTextEdited(before, after)` | write `before`'s full content back | write `after`'s |
| `HeadingLevelChanged(before, after)` | write `before`'s content back | write `after`'s |
| — folded into `Moved(ids, dx, dy, headingIds)` | shift heading rows by `-dx,-dy` alongside any strokes | shift by `dx,dy` |
| — folded into `Deleted(strokes, headingIds)` | restore both strokes and heading rows | remove both again |

`HeadingMoved` and a heading-specific `Deleted` variant are **deliberately not separate kinds** —
one lasso drag or one Delete tap is one gesture to the user even when it touches both ink and
headings, so it stays one undo entry (`Moved`/`Deleted` simply carry an extra `headingIds` list
alongside their stroke ids).

### Persistence and page structure carry heading rows too

`HeadingStore` shares `StrokeStore`'s `SoilWriter` (extracted out of `StrokeStore` in N2 for exactly
this reason) — every heading write and every stroke write for the same page land through the one
serial queue, so a stroke soft-delete and the heading row it converted into can never be observed
out of order. `NotebookSession.deleteCurrent`'s `liveContentIds(pageId)` and `Structural.objectIds`
are type-agnostic ("every live child object of this page," strokes and headings alike), which is
why page delete/undo, `reconcile`, and the format's soft-delete/restore machinery needed no
heading-specific branch at all — a heading row restores exactly like a stroke row does.

### JVM tests specific to headings

`HeadingPrefixTest` (prefix/strip/apply round-trips, clamping, idempotent re-apply),
`HeadingRowsTest` (`Heading ⇄ SoilObjectEntity`, malformed-row → null), `HeadingStoreTest`
(create/erase/restore/move/updateContent ordering against a fake DAO), `StrokeStoreTest`'s `revive`
case (in-place un-delete preserves `"order"` — since N3 the only restore path), `HeadingTypographyTest`
(the six-level scale table, padding/text-size px conversions), and `SelectionAnchorTest`'s `placeSub`
cases (hangs below the bar, flips above when the bar itself flipped, clamps inside the band, and the
reverse-flip-back case when flipping would leave the band). A shared `FakeSoilDao` was extracted so
`HeadingStoreTest` and `StrokeStoreTest` exercise the same in-memory fake.

## Contents (arc 4)

A table of contents over the notebook's `heading` rows — Paper's arc-5 design (the improved og)
**baked into core**: SN headings are core rows, so Paper's whole extension layer (`describeOutline`
AIDL, capability probe, `OutlineCaps` sanitize, provider-failure dialog) does not exist here. The
feature is **read-only over existing rows**: no schema change, no `user_version` bump, format
compat with Paper untouched, and the recognizer point stays SN's only extension surface.

**Entry points, both gated the same way:** the top-bar `btnContents` (Tabler `list`, between Back
and the pen) and a one-finger swipe-down on the paper. Both exist only while the notebook holds
≥ 1 live heading on a live page (`ContentsSource.available` — exact: one id-only EXISTS query
(`SoilDao.anyLiveHeadingOnLivePage`) after a writer drain, because it runs at the tail of every
`navigateTo` and a full-entity scan would tax every flip — a C2 review fix). No headings → the
button is `GONE` and the swipe is silent (no toast — an
unavailable gesture is a non-event). `ContentsFlow.refresh()` re-asks after the open, at the end of
every `navigateTo` (which covers every flip, insert, delete and every undo/redo replay — they all
end in `refreshToPage → navigateTo`), and after each heading mutation that doesn't navigate: a
convert, a selection delete, an eraser sweep (`onContentErased`), and the edit dialog's empty-Save
delete. The button's visibility flips through `whenPenIdle`, and the flip triggers the root's
layout listener, which re-pushes the exclusion rects.

**Opening:** `ContentsFlow.open()` = busy guard → pen-gated `releaseRender` → gather on IO →
`ContentsDialog`. An empty gather (the last heading died between the gate and the read) opens
nothing and re-refreshes. The gather is rebuilt from scratch on every open — no cache, nothing to
invalidate; the dialog is a modal snapshot. Entries are the live headings in **document order**
(`pageIndex`, `y`, `x`), label = `stripHeadingPrefix(text)`, level = the row's authoritative
`flags`; blank-stripping or malformed rows are dropped, never crashed on; a cap of
`ContentsSource.MAX_ENTRIES` (2000) bounds a pathological imported file, with the honest
"Showing the first N headings" footer.

**The tree** (`OutlineTree`, pure): H1–H6 by a per-level "last open node" stack — parents persist
across page boundaries, and an **orphan attaches to the nearest shallower heading before it or
becomes a root, never dropped** (nothing the user wrote is hidden). Opens collapsed to the roots
except the highlighted entry's ancestors; the highlight is the last entry with
`pageIndex ≤ currentPageIndex` (its nearest *visible* ancestor when collapsed away), where the
current page derives from `displayedPageId` — the R6 torn-read rule applied to the highlight.

**The screen** (`ContentsDialog`): one layout, two forms by `ContentsLayout.fullScreen` at 480 dp —
both real devices take the **60 % left sidebar** (Nomad 749 dp / Manta 1024 dp at density 1.875)
over a transparent scrim (tap dismisses; the panel eats its own taps); the full-screen white form
with a back arrow is the below-480 dp branch (JVM-tested; provable via `wm size 800x1600`). Rows
are `[+/− toggle | page number 52 dp 20 sp bold | 1 dp divider | label 20 sp ellipsized]`, the
whole row indented `(level−1)×16 dp`, 68 dp min height; the toggle is `INVISIBLE` on leaves so the
columns align; the highlight row takes the 5 dp inkBlack right-edge bar. Pagination, not scrolling:
`itemsPerPage` measured once from the real body height, the library-shape pager footer `INVISIBLE`
at one page with bound taps as no-ops (never a disabled look). Expansion state is in-memory only —
every open starts collapsed again. A row tap dismisses and navigates **by the entry's page id**,
resolved at tap time under the page-op lock (`refreshToPage` — gone → no-op, current → no reload):
a snapshot *index* would go stale under a page op that committed mid-gather (an escrowed undo, a
queued insert — C2 review fix). The displayed page *numbers* are still the gather snapshot's — a
razor-thin-window skew the modal-snapshot design accepts (display-only; navigation is immune).
**Nothing is selected on arrival** (og/Paper parity — navigate + select stays deferred).

**BLOCK_ALL while showing:** the Ratta ink daemon draws firmware ink beneath any Android window, so
while `ContentsFlow.showing` the host's `pushExclusions()` pushes the whole-paper rect (the
`!opened` shield's trick) — up **before** the dialog's first frame (`onShowingChanged` fires before
`show()`), back to the chrome rects on dismiss. Exclusion rects fence only the ink path, not touch
dispatch, so finger taps on the dialog still land. **Deliberate asymmetry:** the small transient
dialogs (`HeadingEditDialog`, the delete sheet/confirm, problem dialogs) do *not* block-all — they
are brief, user-summoned, and mid-interaction; the Contents is a persistent full-height panel a pen
plausibly lands on. Don't "fix" the small dialogs to match.

The dialog's show/hide is **frame-silence exception 6** (see the ledger below); repaints *inside*
the open dialog (toggle, pager) need no exception of their own — BLOCK_ALL means no live ink can be
under them. `close()` **and the `onDestroy` fallback** (a config-change recreate, "don't keep
activities" — destroys that bypass `close()`) both call `ContentsFlow.dismissIfShowing()` — a
Dialog outliving its finishing Activity is a window leak (C2 review fix for the fallback path).

**C2 hardening (the `/code-review high` fixes, beyond the ones above):** `ContentsFlow`'s
`refresh()`/`open()` **degrade with a `Log.w`** on any gather/availability failure instead of
rethrowing into `lifecycleScope` — a transient SQLite read fault on a routine flip must never be a
process crash when every neighbouring DB path (`runPageOp`, the writer, the seal) logs and
survives; `refresh()` keeps the last answer, `open()` opens nothing and the next tap retries.
`NotebookSession.pages` is `@Volatile` — the gather reads it on IO outside the page-op mutex, and
without the fence the swap is unsafe publication under the JMM, not just staleness. A fired
**long-press stands the whole touch sequence down** (`PageGestures` sets `ignoreSequence` +
`cancelAll` before `onDeleteRequested`) — the finger is still on the glass, and its continued drag
would otherwise be judged at UP as a flip or swipe-down *under the delete sheet*, making the
pending confirm delete the wrong page. The POINTER_DOWN late-arrival commit got its **vertical
twin**: a second finger landing on an already-qualifying swipe-down commits it the way a
qualifying flip is committed (a trailing palm is likeliest on exactly the downward drag). The
dialog's pager reuses `R.string.page_indicator` + the library's `GridMath.pageCount`/`clampPage`
(one copy of the ≥ 1-page/clamp contract; `GridMath` deliberately stays in `library/` — an
in-module import, not worth a package move), the opening highlight is one `lastOrNull` over the
expanded tree (`OutlineTree.find` deleted with its only caller), and both windows' immersive
recipe is the one `core/Immersive.apply`.

### JVM tests specific to the Contents

`OutlineTreeTest` (document-order build, both orphan rules, deeper-slot clearing, cross-page
parents, level clamp, `visible`/`all`/`highlight`/`ancestorsOf`, paging edges, the carried page id),
`ContentsLayoutTest` (the 480 dp branch, sidebar width rounding, `itemsPerPage` floor + ≥ 1,
indent math), and `ContentsSourceTest` (the pure `items()` pass: prefix strip + `flags` level via
`HeadingRows`, dead-page and malformed and blank-label drops, document order, the 2000 cap + bit).

## Pages

A notebook is an ordered list of `page` rows under the notebook row; `"order"` is kept **dense,
0..N-1**, and only the rows whose number actually changed are written. Every structural edit does
its row work inside one `db.withTransaction` and then mirrors the result into the index
(`setPageCount` + `touch`) — the library card can never disagree with the file.

| Operation | What happens |
|---|---|
| `insertBlank(after)` | new `page` row (fresh UUID, parent = notebook, `order` = `PageMath.insertPosition`), inheriting the **current page's template and authored size**, then a renumber; lands on the new page |
| `deleteCurrent()` | soft-delete the page **and its live content** (`liveContentIds` — strokes **and**, N2, headings; the DAO call is type-agnostic), renumber the remainder, land on `PageMath.indexAfterDelete` (the previous page, or the new first) |
| `deleteCurrent()` on the **only** page | the page and its content are soft-deleted and a **fresh blank replacement** is created in the same transaction, same template and size — a notebook always has ≥ 1 page, and an empty one would have nothing to open |
| `reconcile(targetAlive, restoreObjectIds, deleteObjectIds, currentId)` | make the live page set exactly `targetAlive`, in that order, restoring/soft-deleting the given **objects** (strokes and headings alike — "object" here is deliberately type-agnostic) with it, and land on `currentId` |

Pages are **soft-deleted** like everything else in the family. That is what makes undo a
`reconcile` rather than a re-creation: the ids on both sides of a `Structural` snapshot still exist
as rows, so either direction is the same diff (`PageMath.toRestore` / `toDelete`) with the two
sides swapped. `Structural.objectIds` (N2: renamed from a strokes-only list) is exactly that
type-agnostic set — nothing about page structure needed a heading-specific branch.

Every page swap goes through `navigateTo(index)` in that one order, which is the host-responsibilities
page-swap law and a single EPD refresh:

```
goTo → loadPage → clearSelection → clearForContentSwap → setPageSize → setTemplate
     → hand headings to HeadingRenderer → loadStrokes → refresh liveStrokes/liveHeadings
     → page indicator → saveLastOpened
```

`clear()` + `loadStrokes()` would flash blank in between; `clearForContentSwap()` holds the pixels.
Headings are handed to the renderer **before** `loadStrokes` runs, because that call's re-record is
the frame that actually paints the new page — handing them over after would leave one frame where
the new page's strokes are up but its headings are still the old page's.

## Gestures

`PageGestures` is the notebook's whole finger vocabulary — there are no page buttons, because the
paper is full-bleed and the chrome is two thin bars.

| Gesture | Action |
|---|---|
| 1-finger horizontal swipe ← | flip next — **past the last page, insert one** (the notebook grows where you write) |
| 1-finger horizontal swipe → | flip previous (no-op on the first page) |
| 1-finger vertical swipe ↓ | open the Contents (C1 — silent while the notebook has no heading; an up-swipe is nothing) |
| 2-finger horizontal swipe ← / → | insert a page after / before this one |
| 2-finger stationary double-tap | undo |
| 3-finger stationary double-tap | redo |
| 1-finger long-press | delete sheet → confirm dialog |

Thresholds (Paper-v0 parity — the numbers are the feel):

| Constant | Value | Meaning |
|---|---|---|
| `PAGE_SWIPE_MIN_DISTANCE_FRAC` | `0.30` × screen width | minimum horizontal travel before anything counts |
| `PAGE_SWIPE_LONG_DISTANCE_FRAC` | `0.50` × screen width | travel that qualifies on its own, however slow |
| `PAGE_SWIPE_MIN_VELOCITY_MULT` | `1.0` × `scaledMinimumFlingVelocity` | the fling alternative |

A swipe must be **horizontal-dominant** (`|dx| > |dy|`) and qualify on *velocity or* length;
**direction comes from the sign of `dx`, never velocity**, because a decelerating finger can flip
the velocity sign at the end of the drag. The Contents swipe (C1) is the same rule rotated 90° —
vertical-dominant, the same three constants against the screen *height*, judged at the same
`ACTION_UP` right after the flip evaluation (the two dominance tests are mutually exclusive), and
only `dy > 0` acts. The two-finger swipe measures the two-finger centroid and
commits at `POINTER_UP` back to 2→1 fingers; a third finger landing mid-swipe commits a qualifying
insert before it dies, and a second finger landing on an already-qualifying one-finger swipe
commits the flip for the same reason.

**Pen-gating.** A resting palm produces MotionEvents a writing stylus does not, so no recogniser
arms while `isPenActive`, every one re-checks the gate before it fires, and the two double-taps are
put in **escrow for `PaperView.PEN_ACTIVE_TAIL_MS`** and re-check the gate when it expires. A
sequence whose DOWN lands on chrome or comes from a stylus is ignored whole.

**Stand-down.** `standDown()` is `{ selectionActive }` — the detector refuses to arm, and cancels
mid-sequence, while a lasso selection is up (g-paper claims finger input then). It was
`selectionActive || toolbar.panelOpen` in R4–R6; P1 removed the panels and the clause with them,
which puts it back on Paper v0's rule.

**Deliberate delta from Paper v0:** no BOOX `ACTION_CANCEL` special case. On BOOX the Onyx SDK
intercepts 3-finger touches and cancels the sequence, so the reference counted an armed, stationary
3-finger cancel as a tap. Ratta delivers the real `ACTION_UP` for every finger count, so here a
cancel is only a cancel: reset the recognisers, forget the half-tap.

## Undo / redo

g-paper keeps no history by design — the host records what happened and replays it
(`host-responsibilities.md`). `UndoRedoStack` is that record: plain LIFO deques, redo cleared the
moment a new edit is recorded, bounded at **100** entries (oldest dropped, because an `Erased`
holds the full geometry of every stroke it must put back).

**Notebook-level, not page-level.** Every entry carries the page it happened on, so history
survives a page turn — and an insert or a delete *is* a page turn, which undoing has to reverse.
The stack is cleared **only when the screen closes** — in-memory history dies with the screen.

| Action | Recorded by | Revert | Reapply |
|---|---|---|---|
| `Drew` | `onStrokeCommitted` | `store.remove([id])` | `store.revive` |
| `Erased` | `onStrokesErased` (eraser tool **and** scribble erase) | `store.revive` | `store.remove` |
| `Deleted` | the selection toolbar's Delete (strokes **and**, N2, `headingIds`) | `store.revive` + `headings.restore` | `store.remove` + `headings.erase` |
| `Moved` | `onSelectionMoved` (strokes **and**, N2, `headingIds` riding the same drag) | `store.move(-dx,-dy)` + `headings.move(-dx,-dy)` | `store.move(dx,dy)` + `headings.move(dx,dy)` |
| `HeadingCreated` (N2) | a successful convert | `headings.erase` + `store.revive` (in place — order matters) | `headings.restore` + `store.remove` |
| `HeadingDeleted` (N2) | edit-dialog empty Save, or the eraser sweeping a heading whole | `headings.restore` (in place) | `headings.erase` |
| `HeadingTextEdited` (N2) | the edit dialog's Save | write `before`'s content | write `after`'s content |
| `HeadingLevelChanged` (N2) | a level pick on an existing heading | write `before`'s content | write `after`'s content |
| `Page` | insert / delete (`Structural` snapshot, whose `objectIds` are type-agnostic — strokes and headings both) | `reconcile(before)` | `reconcile(after)` |

`Deleted` replays exactly like `Erased` (and its N2 heading half like `HeadingDeleted`) and is
deliberately kept as its own kind: to the user a sweep of the eraser and "delete these" are
different acts, and a future undo *label* has to be able to say which — the same reasoning that
keeps `HeadingCreated`/`HeadingTextEdited`/`HeadingLevelChanged` as their own kinds even though two
of them replay identically. `HeadingMoved` and a heading-only `Deleted` are **not** separate kinds
at all — folded into `Moved`/`Deleted` via their `headingIds` field, because one lasso drag or one
Delete tap touching both ink and headings is one gesture to the user, and must stay one undo step
(see Headings above). Undo of a delete does **not** put the selection back — Paper-v0 parity, and
`refreshToPage`'s `navigateTo` clears the selection anyway.

**The DB is the source of truth.** Every replay mutates the store first, `drain()`s it, and *then*
reloads the affected page through `refreshToPage` — so what the paper shows after an undo is
exactly what a reopen would show. `doUndo`/`doRedo` drain before reverting too: the writes still
queued are part of the state being reversed. Strokes come back via `store.revive` — an **in-place**
un-delete (`"order"`, geometry and `createdAt` all survive), never a tail-append: the page must
return to exactly what it was, and the page's writing order is load-bearing for a later
lasso-convert (N3 review finding — the R3-era tail-append `restore` is gone). `doDelete` drains
too (N3): a stroke commit still queued would otherwise land *after* the page delete's snapshot and
transaction — a permanently live orphan row under a soft-deleted page. And `navigateTo` buffers
commits that land during its suspending loads (`loadingCommits`) and merges them into the rebuild —
a pen-up racing an undo replay's refresh is persisted *and* stays on the glass, instead of
vanishing until the next flip (N3).

**History integrity (R6).** A replay that throws (or is cancelled) pushes the popped entry back on
its own side — the history never silently loses a step, and because the store ops are per-row and
`reconcile` is idempotent, retrying converges. And `doUndo` snapshots `undo.generation` (bumped by
every `record`) before reverting: if a pen-up lands mid-replay and records a fresh edit — which
clears redo — the undone entry is *not* pushed onto redo afterwards, so record-clears-redo holds.

Every gesture-driven operation runs through `runPageOp` — a `Mutex` on `lifecycleScope`, a no-op
while not open or once closing, `runCatching` + `Log.w` on failure — so two overlapping gestures
can never tangle the page list.

The delete long-press **asks**; it never deletes. Sheet (`ActionSheetDialog`, one "Delete page"
row) → confirm dialog → the op. The confirm dialog is the bare question "Delete this page?" with
**no warning body** — a deleted page and its ink come straight back via undo (soft delete +
`reconcile`), so "cannot be recovered" would be false (eye-check #2 finding, 2026-08-22).
`showDeleteSheet` calls `paper.releaseRender()` **ungated**, which
is safe here only because the long-press fired through the gesture gate: it never arms while the
pen is active and re-checks at fire, so we are outside the pen-active window the R3 rule protects.

## Close & lifecycle

- `onResume` → `paper.resumeDrawing()`.
- `onStop` (not closing) → app-scoped: `CoverSnapshot` + `saveLastOpened` (cheap durability point).
- Toolbar back / system back → `close()`: `lastOpenNotebookId = null` → app-scoped
  `NonCancellable`: cover → `saveLastOpened` → `refreshMeta` (name + folder path from the index)
  → `seal()` (`flushTouch` → `drain` → `wal_checkpoint(TRUNCATE)` → close) → `finish()`. Each
  step guarded; idempotent (`closing` flag; `onStop` stands down once closing).
- **Every seal/persist path holds `pageOps` (R6)** — `close()`, `onStop`'s persist, and the
  `onDestroy` fallback all take the same mutex the gesture ops run under. An insert/delete that
  passed the `closing` check before the flag flipped may still be inside its transaction; sealing
  under it would fail the transaction silently (`runPageOp` swallows) or split the `.soil` from its
  index mirror. New ops can't start once `closing` is set, so the lock only ever waits.
- **Template bitmaps are never `recycle()`d (R6)** — `loadTemplateFor` and `seal` drop the
  reference only. The engine keeps painting the old template into committed-layer repaints until
  the activity's `setTemplate` lands on Main; a recycle in that window is a
  "trying to use a recycled bitmap" crash (reachable via format-compatible imports whose pages
  carry different templates). minSdk 29: bitmaps live on the Java heap — dropping the reference is
  the release.
- `onDestroy` → `IndexGuard.bounced` first, then `paper.release()`; if the session is still open
  and no close ran (e.g. finish from a failed open), seal it.
- **Cold-launch restore:** the library's `reopenLastNotebookIfNeeded()` (cold launch only) reads
  `BrowseState.lastOpenNotebookId` — set on notebook open, cleared on close — and puts the
  notebook back on top of the library, but only when its index row is alive **and** its `.soil`
  exists. Read once, cleared regardless of outcome.

## Frame-silence rule

No app frame is presented while `paper.isPenActive` — the strip text only changes through
`whenPenIdle {}` (re-polls every `PEN_ACTIVE_TAIL_MS`). Nothing else on the screen repaints
during writing.

Six recorded exceptions, all the same shape — **one chrome frame at a deliberate act or a
boundary**, never under live ink:

1. the **delete-page sheet at long-press** (R4 — safe because `PageGestures` never arms while the
   pen is active and re-checks the gate at fire, so the sheet lands outside the pen-active window);
2. the **selection toolbar's show at lasso completion** (P1 — deliberately *not* idle-gated: a
   lasso ends with the pen hovering, and `isPenActive` counts hover, so the gate would deliver the
   bar long after its selection. Safe because the engine has already presented the selection box on
   this same boundary — the bar is part of that presentation);
3. the **"Opening…" overlay's hide when the page lands** (P1 — also not idle-gated, and for the
   same hover reason: the pen is on its way to the paper. Nothing has been drawn yet, so this is a
   screen boundary rather than a repaint during writing);
4. the **"Recognizing…" overlay's show/hide around a heading convert** (N2 — the show follows a
   level tap on chrome, the hide is the call's boundary; the box repaints only its own region and
   the pen has just left a chrome button, not the paper);
5. the **selection toolbar's re-show / sub-row toggle on its own taps** (N2 — the H toggle, a level
   pick and the post-edit re-anchor are all responses to a deliberate chrome tap, the same
   justification as its original show; each tap goes through `releaseRender()` first);
6. the **Contents dialog's show/hide** (C1 — the show follows a deliberate act that already passed
   a pen gate: a chrome tap on `btnContents`, or a swipe committed through `PageGestures`'
   `gateOpen()`; the hide is a deliberate row / scrim / back tap. Both are screen boundaries, never
   a repaint under live ink, and `ContentsFlow` pen-gates its `releaseRender` first. Deliberately
   *not* idle-gated — `isPenActive` counts hover, and a hovering pen would hold the screen hostage,
   the same reason as exceptions 2 and 3).

R3's exception — the tool-panel close at stylus pen-up — is **retired**: P1 removed the panels.

Any new exception needs the same written justification.

## JVM tests

`StrokeRowsTest` (round-trip exactness both ways, every style, unknown-style/malformed-blob
fallbacks, format-B channel presence) and `StrokeStoreTest` (serial ordering incl. commit→erase
of the same stroke, monotonic `"order"` across erase, `drain` semantics, move keeps `createdAt`
and skips deleted, close drops writes, debounced-vs-flushed touch) — the store runs against an
in-memory `SoilDao` fake; `unitTests.isReturnDefaultValues = true` covers the `Log` calls in
production paths. Plus `PageMathTest` (delete-landing edges, insert slots, the two diffs, and that
insert/delete undo↔redo diffs are exact mirrors) and `UndoRedoStackTest` (LIFO order, redo cleared
by a new edit, the 100-entry bound dropping the *oldest*, `clear`, each action's `pageId`, and that
a `Deleted` rides the stack like any other action while staying distinguishable from an `Erased`,
and the R6 `generation` counter: moved only by `record`, and the mid-replay protocol that drops the
redo push when an edit interleaves).
`SelectionAnchorTest` (P1) drives `SelectionAnchor` against a Nomad-shaped band: fits below, flips
above when below would cross the bottom strip, clamps at both ends of the band (including a
selection taller than the band itself), x centring, both x clamps, a bar wider than the root, and a
zero-size selection; N2 extends it with `placeSub` cases (hangs below the bar, flips above when the
bar itself flipped, the reverse-flip-back case, and band-clamping).

**N1 (`core/markdown/`, JVM-only phase — pure Kotlin, nothing against the `android.text` classes,
because `returnDefaultValues` would lie about `StaticLayout`):** `MarkdownParserBlockTest` /
`MarkdownParserInlineTest` / `MarkdownParserOrderedListTest` / `MarkdownParserImageTest` port og's
two parser test suites case-for-case (ordered-list start numbers + per-depth counters, seven-`#`s
falls to a paragraph, `-*-` is not a rule, task-before-bullet, image-before-link with
empty-alt-renders-nothing, unclosed markers stay literal, literal coalescing, blockquote space-join)
plus `HeadingTypographyTest` (the six-level scale table). `MarkdownDraw`/`MarkdownRenderer`
themselves are exercised on-device in N2, through `HeadingRenderer`.

**N2 (headings end to end):** see "JVM tests specific to headings" under Headings above —
`HeadingPrefixTest`, `HeadingRowsTest`, `HeadingStoreTest`, `StrokeStoreTest`'s `revive` case, and
the `placeSub` additions to `SelectionAnchorTest` noted above. A shared `FakeSoilDao` backs both
`HeadingStoreTest` and `StrokeStoreTest`.

## Deliberate differences from Paper v0

- **Fixed tools like Paper v0** (3 px pen / 15 px eraser, no panels) as of P1 — R3's panels and
  `ToolPrefs` are gone. The remaining delta is that SN hardwires **smart lasso and scribble erase
  on**, where Paper v0 exposed them.
- **A selection context toolbar.** Paper v0's lasso was move-only with no menu; SN floats a Delete
  bar over the selection (P1) and adds the `Deleted` undo action, then (N2) an **H** button that
  opens its own H1–H6 sub-toolbar for headings. (Paper's own selection toolbar is an arc-4 feature
  with a sub-toolbar and provider actions; SN's is the same anchor rules with core actions only.)
- Failed open shows a **problem dialog**, not Paper's toast (SN rule: a toast only confirms).
- No `TopGuard` padding on the top bar — the guard is 0 on Ratta; chrome sits flush.
- `CoverSnapshot` API-guards `WEBP_LOSSY` (API 30) with legacy `WEBP` on 29, like
  `BuiltInTemplates` does for lossless.
- **No BOOX `ACTION_CANCEL` tap case** in `PageGestures` — Ratta delivers the real `ACTION_UP` for
  3-finger gestures, so a cancel is only ever a cancel.
- **The "Opening…" overlay is og Notesprout's pattern, not Paper's** — Paper v0 had none at all.
- **Headings are core, not an extension** (N2) — the only extension point SN has at all is the
  recognizer that turns lassoed ink into a title (`docs/extensions.md`); storage, rendering, the
  toolbar and undo are all in `:app`. That is og Notesprout's shape (headings are first-class
  content objects there), where Paper delivered its headings *through* its extension system — SN
  takes og's side of that split, keeping only recognition pluggable.
- **`HeadingMoved` and a heading-only delete are not their own undo kinds** — folded into
  `Moved`/`Deleted` via an extra `headingIds` field, because one lasso drag or one Delete tap
  touching both ink and headings is one gesture to the user and must replay as one undo step.
- **No null-text / stroke-fallback heading state** — og's model allows a heading whose recognition
  never resolved to text, backed by its original strokes as a placeholder. SN's `text` column is
  contractually always non-null: a conversion either produces a title or nothing is created at all,
  so that whole state (and its own edit/re-recognize affordances) simply doesn't exist here.
- **No un-heading / revert-to-ink command** — og parity kept deliberately narrow: a heading is
  edited, re-leveled, moved or deleted; there is no path back to raw strokes.
