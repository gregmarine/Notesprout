# Paper — Notebook screen (as built, Phase 3)

The notebook is a full-bleed g-paper surface with two chrome overlays. Everything the paper
draws comes from g-paper (`~/git/g-paper/docs/api.md`, `host-responsibilities.md`); everything
the paper *remembers* comes from the `.soil` via the collaborators below. Pages (flip / insert /
delete) and undo/redo arrive in Phase 4.

## Collaborators (`notebook/`)

| File | Owns |
|---|---|
| `NotebookActivity` | lifecycle, wiring, chrome, exclusion rects, immersive mode, `IndexGuard`, the close sequence |
| `NotebookSession` | the open `SoilDatabase`, `pages: List<PageRef>`, `currentIndex`, decoded template bitmap; `open()`, `saveLastOpened()`, `refreshMeta()`, `seal()` — all IO |
| `StrokeStore` | g-paper callbacks → `stroke` rows through one serial IO writer (a `Channel` of jobs); `loadPage()`; the debounced index `updatedAt` bump; `drain()` before seal |
| `StrokeRows` | pure mapper `Stroke ⇄ SoilObjectEntity` (format-B blob, `InkColorCodec`, `StrokeStyle` name; unknown → PEN). JVM-tested |
| `CoverSnapshot` | `paper.renderToBitmap()` → ≤ 512 px long edge → WEBP q100 → `IndexRepository.setCover` |
| `NotebookToolbar` | `[←] [pen] [eraser] [lasso]`; selected = `state_selected` bordered look; `sync(tool)` from `onToolChanged`; `releaseRender()` on every tap |

## Layout (`activity_notebook.xml`)

`FrameLayout` root → `paperContainer` (the `PaperView`, added in code — `GPaper.create` needs a
Context) → `topBar` overlay (flush at the top edge, `TopGuard.applyRootPadding` so it clears the
BOOX status bar; 0 on Ratta; 1dp inkBlack bottom border) → `bottomStrip` overlay ("`<name>`
`n / N`", 1dp top border). Immersive: system bars hidden, transient by swipe. Portrait-locked.

Both bars are pushed to `paper.setExclusionRects` after every root layout pass, translated into
the paper view's coordinates, so the stylus can never ink under chrome. A finger `ACTION_DOWN`
over either bar calls `releaseRender()` first (palm-gated on `isPenActive`) so an EPD panel shows
the tap's result — done in `dispatchTouchEvent` because the buttons consume the touch.

**Debug ⋯ (debug builds only, arc 3 / M1):** `NotebookDebugMenu.install(this, binding.topBarRow) { … }`
adds a ⋯ at the far end of the top bar row (weight-1 spacer + `ToolbarButton`-sized
`AppCompatImageButton`; a no-op object in `src/release`). It is inside `topBar`, so the existing
exclusion rect and the chrome `releaseRender()` cover it — `pushExclusions()` needed no change. The
provider lambda hands it a `RecognizeContext(paper.getStrokes(), currentPage.width, currentPage.height)`
(null until `opened`) — the page's strokes and px size, nothing else. Its one action, "Recognize page
(ML Kit)", is described in `docs/extensions.md` §"HandwritingRecognizer — host behaviour".

**Toast vs. dialog (settled with the user in arc 3 / M1, applied here, on the New-notebook screen
and — M2 — across the library):** a toast only confirms something that already happened and needs no
reaction ("copied"); anything that explains why a tap did *not* do what was asked, or that the user
must act on, is an `AlertDialog` — `Dialogs.problem(activity, title, message)` (`core/Dialogs.kt`,
styled, OK only, no-op if the activity is finishing) is the one helper for it — on e-ink a toast is
easy to miss and its absence reads as "broken". `NewNotebookActivity`'s taken/invalid-name and
template-render failures, the library's New-folder / Rename name problems, the Naming extension's
validation / unavailable / save-failed messages, and `FolderPickerActivity`'s move collisions are all
`Dialogs.problem` for the same reason (the arc-2 ones were toasts until M2).

## Open

`IndexGuard.ready` → extras (`EXTRA_NOTEBOOK_ID`, `EXTRA_NOTEBOOK_NAME`) → `BrowseState.lastOpenNotebookId
= id`, `RecentsPrefs.record(id)` → `repo.alive(id)` (else toast + finish) → `session.open()`:
`KeySession` passphrase → file must exist and be non-empty (**never created here**) →
`SoilDatabase.open` (raw-key path via `KeyOpener` when cached) → page rows (none → fail) →
last-open page from the notebook row's `refId` → template decoded with `Bitmaps.decodeBounded`
(≤ 4096 px). Then on Main: `setPageSize(w,h)` (the page's authored px rect, so ink registration
survives a different screen), `setTemplate`, `loadStrokes(store.loadPage(id))`, page indicator.

**"Opening…" popup** (`openingOverlay` in the layout — a bordered, 75 %-width box, no scrim, centred
over the paper): **visible from the first frame** so the screen never looks ready before it is, and
hidden (`GONE`) at the end of `openSession`, once the page's template and strokes are on the paper and
`opened` is set. **No pen input while it is up:** until `opened`, `pushExclusions` pushes a single
whole-paper exclusion rect (applied at the first layout pass, before the posted chrome-rect push), so
the stylus cannot ink anywhere; the moment the popup hides, `pushExclusions()` swaps it for the normal
chrome rects. The hide is deliberately **not** `whenPenIdle`-gated: `isPenActive` is true while the
pen merely hovers, and a readiness popup lingering over paper that is already keeping ink would say
the opposite of the truth — one removal, one frame. Also — the layout doc above: "both bars are pushed
to `setExclusionRects` after every root layout pass" holds only once `opened`. On the Nomad the template can take a beat to appear;
the popup is the signal that ink written before then is not yet persisted (`onStrokeCommitted`
ignores strokes while `!opened`). It is not chrome: no exclusion rect, not tappable.

Tool defaults: `PEN`, black, **3 px** width, `StrokeStyle.PEN`, eraser **15 px** (raw px on every
device — same as the reference and g-paper's own defaults). Smart-lasso and scribble-erase are off.

## Persistence

| g-paper callback | Row effect (serial IO) |
|---|---|
| `onStrokeCommitted(s)` | insert `stroke` row, `"order"` = `MAX("order")+1` among the page's live strokes |
| `onStrokesErased(ids)` | soft delete (`deletedAt`) |
| `onSelectionMoved(m)` | read rows → decode → `Stroke.translated(dx,dy)` → re-encode → upsert (createdAt kept) |
| `onPenLifted` | no-op (writes are already incremental) |
| `onToolChanged` | toolbar sync only |

Every write schedules a trailing-debounced (2 s) `IndexRepository.touch(notebookId)` — the
`updatedAt` discipline: the card's "last modified" follows ink, one UPDATE per burst, flushed on
close. Ink is durable the moment the row lands (WAL); a process kill loses at most the strokes
still queued in the channel.

## Close & lifecycle

- `onResume` → `paper.resumeDrawing()`.
- `onStop` (not closing) → app-scoped: `CoverSnapshot` + `saveLastOpened` (cheap durability point).
- Back button / system back → `close()`: `lastOpenNotebookId = null` → app-scoped
  `NonCancellable`: cover → `saveLastOpened` → `refreshMeta` (name + folder path from the index)
  → `seal()` (`flushTouch` → `drain` → `wal_checkpoint(TRUNCATE)` → close) → `finish()`. Each step
  guarded; idempotent (`closing` flag; `onStop` stands down once closing).
- `onDestroy` → `paper.release()`; if the session is still open and no close ran (e.g. finish
  from a failed open), seal it.

## Frame-silence rule

No app frame is presented while `paper.isPenActive` — the strip text only changes through
`whenPenIdle {}` (re-polls every `PEN_ACTIVE_TAIL_MS`). Nothing else on the screen repaints
during writing.

## Library side

`LibraryGrid` decodes the cover with `Bitmaps.decodeBounded(bytes, 512)` into the card's
`coverImage` (`layout_weight=1` — the Phase 2 layout gave it 0 height, fixed here).

## Pages: flip, insert, delete, undo/redo (Phase 4)

The notebook is a stack of pages on one g-paper surface. `NotebookSession` owns the ordered `pages`
list + `currentIndex`; `PageGestures` turns finger input into page actions; `UndoRedoStack` is the
in-memory history.

### Gestures (`PageGestures`)

Fed from `NotebookActivity.dispatchTouchEvent` (observer only — consumes nothing, so pen ink and the
toolbar buttons still see every event). Ported thresholds:

- **Flip** (1 finger, horizontal-dominant, `|dx| ≥ 0.30×width`, and `|vx| ≥ scaledMinimumFlingVelocity`
  **or** `|dx| ≥ 0.50×width`): `dx<0` → next, `dx>0` → previous. Direction from `dx` sign, never
  velocity. **Swiping next past the last page inserts a new page after it** (phase-4 decision).
- **Insert** (2 fingers, same gates on the centroid): `dx<0` → insert **after** + navigate; `dx>0` →
  **before**.
- **Undo / redo** (multi-finger stationary double-tap; arms on `POINTER_DOWN`, ≥4 disarms; stationary
  = centroid ≤ `touchSlop`; each tap ≤ `longPressTimeout`; second tap ≤ `doubleTapTimeout` &
  ≤ `doubleTapSlop` of the first): 2 fingers = undo, 3 = redo. **BOOX sends `ACTION_CANCEL` for
  3-finger touches** → a cancel on an armed, stationary 3-finger gesture counts as the tap.
- **Delete** (1 finger long-press ≥ `longPressTimeout`, stationary ≤ `touchSlop`): `ActionSheetDialog`
  "Delete page" → `AlertDialog` "Delete this page? / Its ink cannot be recovered." [Delete] [Cancel].

**Gating (every recogniser):** refuse to start / act while `paper.isPenActive`; re-check at the gate;
tap-actions (undo/redo, long-press) commit after a `PEN_ACTIVE_TAIL_MS` escrow and drop if the gate
closed. The whole detector **stands down while a lasso selection is active** (g-paper claims finger
input then — dismiss the selection before you can flip/undo) and never arms on a stylus down or a down
over chrome. No haptic feedback (meditative).

### Page structure (`NotebookSession`)

- `goTo(i)` — navigate only (loads the page's template).
- `insertBlank(after)` — new page row copying the current page's geometry + template ref, renumber
  siblings 0..N-1 in one transaction, mirror `pageCount` + `updatedAt`, land on the new page. Returns
  a `Structural` snapshot.
- `deleteCurrent()` — soft-delete the page + its strokes, renumber, land on the previous page
  (`PageMath.indexAfterDelete`). **Deleting the only page creates a fresh blank in its place** so a
  notebook always has ≥ 1 page. Returns a `Structural` snapshot.
- `reconcile(targetAlive, restoreStrokeIds, deleteStrokeIds, currentId)` — the undo/redo primitive:
  makes the live page set exactly `targetAlive` (diff via `PageMath.toRestore` / `toDelete`, using the
  DAO's `restore` to un-soft-delete), toggles the given strokes, renumbers, lands on `currentId`.

`SoilDao` gained `restore(ids, at)` (un-soft-delete) and `liveStrokeIds(pageId)` (cheap, no blobs).
`PageMath` holds the pure index/set arithmetic (JVM-tested in `PageMathTest`).

### Undo / redo (`UndoRedoStack`) — notebook-level

**Deviation from the plan's "per-page, cleared on page turn":** because the phase-start answer put
**page insert/delete inside undo**, and undoing an insert/delete must reverse the page turn it caused,
the stack is **notebook-level and cleared only on close** (never on a turn). Each entry carries its
page id, so undo navigates back to the affected page. Bounded at 100 entries (oldest dropped;
`Erased` holds the full stroke geometry). Redo clears on any new edit.

Actions and their replay (all go **store → drain → reload the affected page**, so the DB is always the
source of truth and paper never desyncs):

| Action | undo | redo |
|---|---|---|
| `Drew(pageId, stroke)` | `store.remove` | `store.restore` |
| `Erased(pageId, strokes)` | `store.restore` | `store.remove` |
| `Moved(pageId, ids, dx, dy)` | `store.move(-dx,-dy)` | `store.move(dx,dy)` |
| `Page(Structural)` | `reconcile(before, strokeIds→restore, …, beforeCurrentId)` | `reconcile(after, …, strokeIds→delete, afterCurrentId)` |

`StrokeStore` gained `remove(ids)` (= soft delete) and `restore(pageId, strokes)` (re-add as live
rows). `NotebookActivity` keeps `liveStrokes` (a `Map<id,Stroke>` of the visible page) so an erase can
capture the full `Stroke` objects the undo needs; it is rebuilt on every page load/navigate.

All page/undo operations run under a `Mutex` (`pageOps`) so overlapping gestures can't corrupt the
page list, and are dropped once `closing`.
