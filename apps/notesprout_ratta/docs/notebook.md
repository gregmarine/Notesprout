# Notebook — Notesprout SN subsystem doc

Phase **R6**. The notebook is a full-bleed g-paper surface with two chrome overlays: the toolbar
(with its three slide-down tool panels) and the name strip. Everything the paper *draws* comes from
g-paper 0.1.4 (`~/git/g-paper/docs/api.md`, `host-responsibilities.md`); everything the paper
*remembers* comes from the `.soil` via the collaborators below.

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
| `NotebookToolbar` | `[←] [pen] [eraser] [lasso]` + the pen/eraser/lasso panels; owns every tool decision incl. applying `ToolPrefs` |
| `ToolPrefs` | `SharedPreferences("sn_tool")` — armed width/style/ink/radius + the two recogniser flags, app-wide, validated on read |

## Layout (`activity_notebook.xml`)

`FrameLayout` root → `paperContainer` (the `PaperView`, added in code — `GPaper.create` needs a
Context) → `topBar` overlay (flush at the top edge — the top guard is 0 on Ratta; 1dp inkBlack
bottom border; the three panels are its children, `GONE` until opened, each with its own 1dp bottom
border) → `bottomStrip` overlay ("`<name>` `n / N`", 1dp top border). Immersive: system bars
hidden, transient by swipe. Portrait-locked.

Both bars are pushed to `paper.setExclusionRects` after every root layout pass, translated into
the paper view's coordinates, so the stylus can never ink under chrome. Because the panels are
children of `topBar`, an open panel grows the bar's rect and the same push covers it — the
toolbar only toggles visibility and the layout listener does the rest. A finger `ACTION_DOWN`
over either bar calls `releaseRender()` first (palm-gated on `isPenActive`) so an EPD panel shows
the tap's result — done in `dispatchTouchEvent` because the buttons consume the touch.

## Toolbar & panels (R3 phase decisions)

Paper v0's bar shape with **rich panels** — the R3 wizard answers:

- Tapping an **unarmed** tool arms it (and closes any panel). Tapping the **armed** button toggles
  that tool's panel; the three panels are mutually exclusive. All three tools have one since R5.
- **Pen panel:** widths **1·2·3·5·8 px** (dots sized to read the width back), all five g-paper
  styles (PEN / FOUNTAIN / MARKER / PENCIL / BRUSH as text buttons — words over glyphs on e-ink),
  and the **16-level greyscale ladder** (level i → grey `i × 17`, black → white, 2 rows of 8).
  The ladder is the one sanctioned appearance of "colour" in chrome: the grey *is* the choice.
  Every swatch carries an always-visible 1dp inkBlack ring (a white swatch is otherwise invisible
  on paper).
- **Eraser panel:** radii **8·15·30·60 px** (raw px, matching g-paper's semantics).
- **Lasso panel (R5):** the two pen-gesture recognisers as independent latches — **Smart lasso**
  and **Scribble erase**, both **on by default** (the R5 phase decision), each persisted in
  `ToolPrefs` (`smartLasso` / `scribbleErase`) and written straight through to
  `paper.smartLassoEnabled` / `paper.scribbleEraseEnabled`. Not a one-of group: each row keeps its
  own `state_selected` border (bordered = on) and its tooltip reads the state back in words
  ("Smart lasso: on"), because a border alone is a thin signal on paper.
  The flags are **global to the surface, and the engine evaluates both only while the PEN tool is
  armed** — they live under the *lasso* button because that is where selection behaviour is
  configured, and a pen panel that silently changed what the pen does would be the more surprising
  home.
- Panel choices apply immediately, persist in `ToolPrefs`, and the panel stays open (the user may
  be composing width + style + ink). First-ever defaults: **PEN · black · 3 px**, eraser **15 px**
  (Paper-v0 parity).
- **Touching the page dismisses an open panel** (eye-check #1 decisions): the activity's
  `dispatchTouchEvent` closes panels on any contact that is not over chrome — a tap on the panel
  itself is over chrome (the panel is a `topBar` child), so composing in the panel never
  dismisses it. A **finger** dismisses at `ACTION_DOWN` (palm-gated like every finger path); a
  **stylus** dismisses at its **pen-up** (posted, so the engine's synchronous commit runs first).
  Pen-up, not pen-idle: `isPenActive` counts *hover* + a 350 ms tail, so an idle-gated close
  holds the panel for as long as the pen floats near the glass (eye-check finding — it read as a
  long delay). This is the **one deliberate frame-silence exception**: a single chrome frame at a
  stroke boundary, once per dismissal; if a new contact lands before the posted close runs, it
  falls back to the `whenPenIdle` gate rather than repaint under live ink.
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
  lost this way; overlay-only ink can). Leading suspects: a stale exclusion-rect window around a
  panel toggle filtering captured points, or a raw-delivery drop in the ink daemon (the
  4th-overlay-law family). The R3 hardening (pen-gated releases) narrows one path; watch for a
  recurrence through R4–R6 regression — if it reproduces, instrument `onStrokeCommitted` vs. the
  overlay and fix in g-paper.

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
throw into the same dialog instead of an uncaught-in-scope crash).

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
| `onSelectionMoved(m)` | read rows → decode → `Stroke.translated(dx,dy)` → re-encode → upsert (`createdAt` kept); `currentSelection`'s bounds shift by the same delta |
| `onSelectionCreated/Dismissed` | `selectionActive` flag + the `currentSelection` copy |
| `onSelectionTapped(x,y)` | opens the selection sheet (see below) |
| `onToolChanged` | toolbar sync only |

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

## Selection (R5)

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
| Draw a lasso outline (or a smart-lasso loop) | engine only — `onSelectionCreated` |
| Drag inside the box | engine translates + re-renders; `onSelectionMoved` → `store.move` + `liveStrokes` patch + `Action.Moved` |
| **Tap inside the box** | `onSelectionTapped` → the selection sheet: one row, **Delete strokes** |
| Tap outside / tool change / any data-in call | `onSelectionDismissed` |

**Delete order matters.** Capture the geometry from `liveStrokes` *first* — it is the only place it
still exists once the engine drops the strokes — then `paper.removeStrokes(ids)`, then
`store.erase(ids)`, then drop the ids from `liveStrokes`, then record `Action.Deleted`.
`removeStrokes` dismisses the selection itself (every data-in call does), so there is no
`clearSelection` in the delete path; the resulting `onSelectionDismissed` clears the host's copy.
Nothing captured (which should not happen) still removes and erases, but records no history —
better no undo entry than one that restores nothing.

**No confirm dialog.** The tap landed inside the box the user had just drawn, the row says exactly
what it does, and the delete comes straight back with undo — the same reasoning that stripped the
page-delete confirm's warning body in R4 (eye-check #2). A second dialog would be ceremony.

**Frame silence.** The sheet calls `releaseRender()` ungated and puts a dialog on screen, which is
an app frame. This is an **extension of the recorded exception family** — the same shape as the
panel close at stylus pen-up: a single chrome frame at a *stroke boundary*, in direct response to a
deliberate act, never during writing. It is safe for the same structural reason the delete-page
long-press is: g-paper escrows this callback past the contact — a stylus tap fires at **pen-up**,
a finger tap only after the `PEN_ACTIVE_TAIL_MS` palm-gated escrow — so the contact that asked for
the menu is over before we paint, and a tap inside a selection box is by definition not a stroke.

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

**Stand-down.** `standDown()` is SN's wider version of Paper v0's `selectionActive`: the detector
refuses to arm, and cancels mid-sequence, while **a lasso selection is up** (g-paper claims finger
input then) **or a tool panel is open** (the panel's own dismiss owns that contact). The Activity
feeds `pageGestures.onTouchEvent` **before** its panel-dismiss block for exactly that reason — a
finger DOWN that is about to close a panel is seen while `panelOpen` is still true, so the whole
sequence is discarded rather than half-read.

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
| `Deleted` | the selection sheet's Delete strokes | `store.restore` | `store.remove` |
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

Three recorded exceptions, all the same shape — **one chrome frame at a stroke boundary, in direct
response to a deliberate act**, never under live ink:

1. the tool-panel close at stylus pen-up (R3 — an idle-gated close reads as a stuck panel, because
   `isPenActive` counts hover);
2. the delete-page sheet at long-press (R4 — safe because `PageGestures` never arms while the pen
   is active and re-checks the gate at fire);
3. the selection sheet at `onSelectionTapped` (R5 — safe because g-paper escrows the callback to
   pen-up for a stylus and past the palm-gated `PEN_ACTIVE_TAIL_MS` escrow for a finger).

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
`ToolPrefsDefaultsTest` pins the locked default values — both recognisers **on**, PEN 3 px / eraser
15 px, each on the ladder its panel offers. The `ToolPrefs` *accessors* are not JVM-tested: they
need real `SharedPreferences`, and the `isReturnDefaultValues` stub returns the type default, so
such a test would assert the stub rather than the pref.

## Deliberate differences from Paper v0

- **Rich tool panels** (Paper v0 had fixed 3 px pen / 15 px eraser and no panels) + `ToolPrefs`
  persistence — the R3 wizard decisions above; plus the R5 **lasso panel** carrying the two
  recogniser latches, both defaulting **on**.
- **A selection sheet.** Paper v0's lasso was move-only with no menu; SN's tap-inside-the-box opens
  a one-row sheet and adds the `Deleted` undo action.
- Failed open shows a **problem dialog**, not Paper's toast (SN rule: a toast only confirms).
- No `TopGuard` padding on the top bar — the guard is 0 on Ratta; chrome sits flush.
- `CoverSnapshot` API-guards `WEBP_LOSSY` (API 30) with legacy `WEBP` on 29, like
  `BuiltInTemplates` does for lossless.
- **Gesture stand-down includes an open tool panel**, not just a lasso selection — Paper v0 had no
  panels. The Activity feeds the detector before its own panel-dismiss block so the gate reads
  `panelOpen` as still true.
- **No BOOX `ACTION_CANCEL` tap case** in `PageGestures` — Ratta delivers the real `ACTION_UP` for
  3-finger gestures, so a cancel is only ever a cancel.
