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

## Open

`IndexGuard.ready` → extras (`EXTRA_NOTEBOOK_ID`, `EXTRA_NOTEBOOK_NAME`) → `BrowseState.lastOpenNotebookId
= id`, `RecentsPrefs.record(id)` → `repo.alive(id)` (else toast + finish) → `session.open()`:
`KeySession` passphrase → file must exist and be non-empty (**never created here**) →
`SoilDatabase.open` (raw-key path via `KeyOpener` when cached) → page rows (none → fail) →
last-open page from the notebook row's `refId` → template decoded with `Bitmaps.decodeBounded`
(≤ 4096 px). Then on Main: `setPageSize(w,h)` (the page's authored px rect, so ink registration
survives a different screen), `setTemplate`, `loadStrokes(store.loadPage(id))`, page indicator.

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
