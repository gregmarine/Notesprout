# Notebook — Notesprout SN subsystem doc

Phase **P1** (arc 2). The notebook is a full-bleed g-paper surface with three chrome overlays: the
toolbar, the name strip, and the selection's floating bar while a lasso selection is up. Everything
the paper *draws* comes from g-paper 0.1.4 (`~/git/g-paper/docs/api.md`,
`host-responsibilities.md`); everything the paper *remembers* comes from the `.soil` via the
collaborators below.

Fresh code. Paper v0 (`git show 87277da:apps/notesprout_paper/...`) is the shape reference; the
deliberate differences are listed at the end.

---

## Collaborators (`notebook/`)

| File | Owns |
|---|---|
| `NotebookActivity` | lifecycle, wiring, chrome, exclusion rects, immersive mode, `IndexGuard`, the close sequence; the gesture → operation wiring and the undo/redo replay |
| `NotebookSession` | the open `SoilDatabase`, `pages: List<PageRef>`, `currentIndex`, decoded template bitmap; `open()`, `goTo()`, `insertBlank()`, `deleteCurrent()`, `reconcile()`, `saveLastOpened()`, `refreshMeta()`, `seal()` — all IO |
| `PageMath` | pure page arithmetic: `indexAfterDelete`, `insertPosition`, `toRestore`, `toDelete`. JVM-tested |
| `PageGestures` | the finger vocabulary over the paper — observer only, fed from `dispatchTouchEvent`, consumes nothing |
| `UndoRedoStack` | notebook-level in-memory history; pure ordering, bounded at 100 |
| `StrokeStore` | the session's single serial `SoilWriter`: g-paper callbacks → `stroke` rows through one serial IO writer (a `Channel` of jobs); `loadPage()`, `remove()`, `restore()`; the debounced index `updatedAt` bump; `drain()` before seal |
| `StrokeRows` | pure mapper `Stroke ⇄ SoilObjectEntity` (format-B blob, `InkColorCodec`, `StrokeStyle` name; unknown → PEN). JVM-tested |
| `CoverSnapshot` | `paper.renderToBitmap()` → ≤ 512 px long edge → WEBP q100 → `IndexRepository.setCover` |
| `NotebookToolbar` | `[←] [pen] [eraser] [lasso]` — arming only; owns the fixed tool values |
| `SelectionToolbar` | the floating Delete bar over a live lasso selection: build, anchor, show/hide, its rect |
| `SelectionAnchor` | pure placement arithmetic for that bar (centre / gap / flip / clamp). JVM-tested |
| `core/OpeningOverlay` | the source-side "Opening…" box and its pre-draw + post launch sequencing |

## Layout (`activity_notebook.xml`)

`FrameLayout` root → `paperContainer` (the `PaperView`, added in code — `GPaper.create` needs a
Context) → `topBar` overlay (flush at the top edge — the top guard is 0 on Ratta; 1dp inkBlack
bottom border) → `bottomStrip` overlay ("`<name>` `n / N`", 1dp top border) → `selectionToolbar`
(floating, `GONE`, placed by margins) → `openingOverlay` (an `<include>` of `overlay_opening.xml`,
**last child so it is topmost**, and `VISIBLE` from the first frame). Immersive: system bars
hidden, transient by swipe. Portrait-locked.

Both bars — and the selection toolbar while it is up — are pushed to `paper.setExclusionRects`
after every root layout pass, translated into the paper view's coordinates, so the stylus can never
ink under chrome. The push is driven by a layout-change listener on the root, which fires for any
child's `requestLayout`, so showing/moving/hiding the floating bar re-pushes by itself. A finger
`ACTION_DOWN` over chrome calls `releaseRender()` first (palm-gated on `isPenActive`) so an EPD
panel shows the tap's result — done in `dispatchTouchEvent` because the buttons consume the touch.

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
rect, so ink registration survives a different screen), `setTemplate`, `loadStrokes
(store.loadPage(id))`, page indicator.

A failed open is a **problem dialog** (SN's toast-confirms / dialog-explains rule), OK → finish —
including a crash *mid*-open (the R6 hardening: `openSession`'s catch turns any non-cancellation
throw into the same dialog instead of an uncaught-in-scope crash). `failOpen` takes the "Opening…"
box down **first**: it shields every touch under it, and an OK button that cannot be tapped is a
dead screen.

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
| `onSelectionMoved(m)` | read rows → decode → `Stroke.translated(dx,dy)` → re-encode → upsert (`createdAt` kept); `currentSelection`'s bounds shift by the same delta, and the selection toolbar re-anchors there |
| `onSelectionCreated/Dismissed` | `selectionActive` flag + the `currentSelection` copy + show/hide the selection toolbar |
| `onSelectionDragStarted()` | hide the selection toolbar (the mirror is **not** cleared) |
| `onToolChanged` | toolbar sync only |

`onSelectionTapped` is **not overridden** (P1) — a tap inside the box has nothing left to open now
that the actions are on a bar that is already showing.

The first three also maintain `liveStrokes` (the Activity's map of what is on the visible page —
the only place an erased stroke's geometry still exists once the engine drops it) and record the
matching `UndoRedoStack.Action`.

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

## Selection (R5, context toolbar P1)

**The engine owns every mechanic.** Outline capture, the hit test, the static dashed selection box
(the tight bounds inflated 12 px so a thin selection stays grabbable), the drag preview, the
in-memory translate, dismissal, and the Ratta firmware dash trail all live in g-paper. The host's
whole job is to mirror the result into rows and history. Lasso in arc 1 is **move + delete** (the
R5 phase decision).

`currentSelection: Selection?` is the host's copy of what is selected — set in
`onSelectionCreated`, shifted in place on `onSelectionMoved` (the engine keeps the selection alive
at its new position), nulled in `onSelectionDismissed` and in `navigateTo`'s `clearSelection`. It
exists for one reason: a delete needs the stroke ids *after* the tap that asked for it. It is never
read as "is anything selected" — `selectionActive` is that flag, and it is what the gesture
detector stands down on.

| Act | What happens |
|---|---|
| Draw a lasso outline (or a smart-lasso loop) | engine draws the box; host shows the **selection toolbar** anchored to it |
| Drag inside the box | `onSelectionDragStarted` hides the bar; the engine translates + re-renders; `onSelectionMoved` → `store.move` + `liveStrokes` patch + `Action.Moved`, then the bar re-anchors at the new bounds |
| Tap the bar's **Delete strokes** | `releaseRender()` then `deleteSelection` (order below) |
| Tap inside the box | nothing (`onSelectionTapped` is not overridden) |
| Tap outside / tool change / any data-in call / page swap | `onSelectionDismissed` → bar hidden, mirror cleared |

### The selection toolbar

A bordered row floating over the paper, one `ic_trash` button today (**Delete strokes** — the label
is its long-press hint and content description). It is a *bar*, not a button, because it is the
shape the selection's actions live in from here on.

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
dismissal does.

**Chrome, like any other.** Its rect joins `pushExclusions` (so the pen cannot ink through it) and
`overChrome` (so the finger paths treat it as chrome). Both come free of scheduling: the root's
layout-change listener fires for any child `requestLayout`, which showing, moving or hiding the bar
always triggers.

**Delete order matters.** Capture the geometry from `liveStrokes` *first* — it is the only place it
still exists once the engine drops the strokes — then `paper.removeStrokes(ids)`, then
`store.erase(ids)`, then drop the ids from `liveStrokes`, then record `Action.Deleted`.
`removeStrokes` dismisses the selection itself (every data-in call does), so there is no
`clearSelection` in the delete path; the resulting `onSelectionDismissed` clears the host's copy.
Nothing captured (which should not happen) still removes and erases, but records no history —
better no undo entry than one that restores nothing. The Delete button calls `paper.releaseRender()`
before the row runs — the same tap-escrow safety the R5 sheet had: the tap must show its result, and
the delete repaints the page underneath.

**No confirm dialog.** The button is on a bar the user summoned by drawing a lasso, it says exactly
what it does, and the delete comes straight back with undo — the same reasoning that stripped the
page-delete confirm's warning body in R4 (eye-check #2). A dialog would be ceremony.

**Frame silence.** Showing the bar at `onSelectionCreated` is an app frame, and it is deliberately
**not** pen-idle-gated: a lasso ends with the pen still hovering over the glass (`isPenActive`
counts proximity + a 350 ms tail), so an idle gate would deliver the bar long after the selection it
belongs to — the R3 panel lesson. It is safe because the engine has *already* presented the
selection box on this same boundary: this frame is part of that presentation, not a repaint during
writing. See the frame-silence section for the full list.

## Pages

A notebook is an ordered list of `page` rows under the notebook row; `"order"` is kept **dense,
0..N-1**, and only the rows whose number actually changed are written. Every structural edit does
its row work inside one `db.withTransaction` and then mirrors the result into the index
(`setPageCount` + `touch`) — the library card can never disagree with the file.

| Operation | What happens |
|---|---|
| `insertBlank(after)` | new `page` row (fresh UUID, parent = notebook, `order` = `PageMath.insertPosition`), inheriting the **current page's template and authored size**, then a renumber; lands on the new page |
| `deleteCurrent()` | soft-delete the page **and its live strokes** (`liveStrokeIds`), renumber the remainder, land on `PageMath.indexAfterDelete` (the previous page, or the new first) |
| `deleteCurrent()` on the **only** page | the page and its ink are soft-deleted and a **fresh blank replacement** is created in the same transaction, same template and size — a notebook always has ≥ 1 page, and an empty one would have nothing to open |
| `reconcile(target, restoreStrokes, deleteStrokes, currentId)` | make the live page set exactly `target`, in that order, restoring/soft-deleting the given strokes with it, and land on `currentId` |

Pages are **soft-deleted** like everything else in the family. That is what makes undo a
`reconcile` rather than a re-creation: the ids on both sides of a `Structural` snapshot still exist
as rows, so either direction is the same diff (`PageMath.toRestore` / `toDelete`) with the two
sides swapped.

Every page swap goes through `navigateTo(index)` in that one order, which is the host-responsibilities
page-swap law and a single EPD refresh:

```
goTo → loadPage → clearSelection → clearForContentSwap → setPageSize → setTemplate → loadStrokes
     → refresh liveStrokes → page indicator → saveLastOpened
```

`clear()` + `loadStrokes()` would flash blank in between; `clearForContentSwap()` holds the pixels.

## Gestures

`PageGestures` is the notebook's whole finger vocabulary — there are no page buttons, because the
paper is full-bleed and the chrome is two thin bars.

| Gesture | Action |
|---|---|
| 1-finger horizontal swipe ← | flip next — **past the last page, insert one** (the notebook grows where you write) |
| 1-finger horizontal swipe → | flip previous (no-op on the first page) |
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
the velocity sign at the end of the drag. The two-finger swipe measures the two-finger centroid and
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
| `Drew` | `onStrokeCommitted` | `store.remove([id])` | `store.restore` |
| `Erased` | `onStrokesErased` (eraser tool **and** scribble erase) | `store.restore` | `store.remove` |
| `Deleted` | the selection toolbar's Delete strokes | `store.restore` | `store.remove` |
| `Moved` | `onSelectionMoved` | `store.move(-dx,-dy)` | `store.move(dx,dy)` |
| `Page` | insert / delete (`Structural` snapshot) | `reconcile(before)` | `reconcile(after)` |

`Deleted` replays exactly like `Erased` and is deliberately kept as its own kind: to the user a
sweep of the eraser and "delete these" are different acts, and a future undo *label* has to be able
to say which. Undo of a delete does **not** put the selection back — Paper-v0 parity, and
`refreshToPage`'s `navigateTo` clears the selection anyway.

**The DB is the source of truth.** Every replay mutates the store first, `drain()`s it, and *then*
reloads the affected page through `refreshToPage` — so what the paper shows after an undo is
exactly what a reopen would show. `doUndo`/`doRedo` drain before reverting too: the writes still
queued are part of the state being reversed. `store.restore` is a REPLACE upsert, which revives the
soft-deleted row live at the tail of the z-order.

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

Five recorded exceptions, all the same shape — **one chrome frame at a deliberate act or a
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
   justification as its original show; each tap goes through `releaseRender()` first).

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
zero-size selection.

## Deliberate differences from Paper v0

- **Fixed tools like Paper v0** (3 px pen / 15 px eraser, no panels) as of P1 — R3's panels and
  `ToolPrefs` are gone. The remaining delta is that SN hardwires **smart lasso and scribble erase
  on**, where Paper v0 exposed them.
- **A selection context toolbar.** Paper v0's lasso was move-only with no menu; SN floats a
  one-button Delete bar over the selection and adds the `Deleted` undo action. (Paper's own
  selection toolbar is an arc-4 feature with a sub-toolbar and provider actions; SN's is the same
  anchor rules with a single core action.)
- Failed open shows a **problem dialog**, not Paper's toast (SN rule: a toast only confirms).
- No `TopGuard` padding on the top bar — the guard is 0 on Ratta; chrome sits flush.
- `CoverSnapshot` API-guards `WEBP_LOSSY` (API 30) with legacy `WEBP` on 29, like
  `BuiltInTemplates` does for lossless.
- **No BOOX `ACTION_CANCEL` tap case** in `PageGestures` — Ratta delivers the real `ACTION_UP` for
  3-finger gestures, so a cancel is only ever a cancel.
- **The "Opening…" overlay is og Notesprout's pattern, not Paper's** — Paper v0 had none at all.
