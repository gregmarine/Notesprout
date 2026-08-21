# Notebook — Notesprout SN subsystem doc

Phase **R3**. The notebook is a full-bleed g-paper surface with two chrome overlays: the toolbar
(with its two slide-down tool panels) and the name strip. Everything the paper *draws* comes from
g-paper 0.1.4 (`~/git/g-paper/docs/api.md`, `host-responsibilities.md`); everything the paper
*remembers* comes from the `.soil` via the collaborators below. Pages (flip / insert / delete) and
undo/redo arrive in R4; lasso move/polish in R5.

Fresh code. Paper v0 (`git show 87277da:apps/notesprout_paper/...`) is the shape reference; the
deliberate differences are listed at the end.

---

## Collaborators (`notebook/`)

| File | Owns |
|---|---|
| `NotebookActivity` | lifecycle, wiring, chrome, exclusion rects, immersive mode, `IndexGuard`, the close sequence |
| `NotebookSession` | the open `SoilDatabase`, `pages: List<PageRef>`, `currentIndex`, decoded template bitmap; `open()`, `goTo()`, `saveLastOpened()`, `refreshMeta()`, `seal()` — all IO |
| `StrokeStore` | the session's single serial `SoilWriter`: g-paper callbacks → `stroke` rows through one serial IO writer (a `Channel` of jobs); `loadPage()`; the debounced index `updatedAt` bump; `drain()` before seal |
| `StrokeRows` | pure mapper `Stroke ⇄ SoilObjectEntity` (format-B blob, `InkColorCodec`, `StrokeStyle` name; unknown → PEN). JVM-tested |
| `CoverSnapshot` | `paper.renderToBitmap()` → ≤ 512 px long edge → WEBP q100 → `IndexRepository.setCover` |
| `NotebookToolbar` | `[←] [pen] [eraser] [lasso]` + the pen/eraser panels; owns every tool decision incl. applying `ToolPrefs` |
| `ToolPrefs` | `SharedPreferences("sn_tool")` — armed width/style/ink/radius, app-wide, validated on read |

## Layout (`activity_notebook.xml`)

`FrameLayout` root → `paperContainer` (the `PaperView`, added in code — `GPaper.create` needs a
Context) → `topBar` overlay (flush at the top edge — the top guard is 0 on Ratta; 1dp inkBlack
bottom border; the two panels are its children, `GONE` until opened, each with its own 1dp bottom
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

- Tapping an **unarmed** tool arms it (and closes any panel). Tapping the **armed** pen or eraser
  button toggles that tool's panel; the two panels are mutually exclusive. Lasso has no panel.
- **Pen panel:** widths **1·2·3·5·8 px** (dots sized to read the width back), all five g-paper
  styles (PEN / FOUNTAIN / MARKER / PENCIL / BRUSH as text buttons — words over glyphs on e-ink),
  and the **16-level greyscale ladder** (level i → grey `i × 17`, black → white, 2 rows of 8).
  The ladder is the one sanctioned appearance of "colour" in chrome: the grey *is* the choice.
  Every swatch carries an always-visible 1dp inkBlack ring (a white swatch is otherwise invisible
  on paper).
- **Eraser panel:** radii **8·15·30·60 px** (raw px, matching g-paper's semantics).
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
  arms/restores tools itself (smart lasso, off in R3).

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

A failed open is a **problem dialog** (SN's toast-confirms / dialog-explains rule), OK → finish.

## Persistence

| g-paper callback | Row effect (serial IO) |
|---|---|
| `onStrokeCommitted(s)` | insert `stroke` row, `"order"` = `MAX("order")+1` among the page's strokes (live **and** deleted — order stays monotonic) |
| `onStrokesErased(ids)` | soft delete (`deletedAt`) |
| `onSelectionMoved(m)` | read rows → decode → `Stroke.translated(dx,dy)` → re-encode → upsert (`createdAt` kept) |
| `onSelectionCreated/Dismissed` | `selectionActive` flag only (R4's gesture detector stands down on it) |
| `onToolChanged` | toolbar sync only |

Every write schedules a trailing-debounced (2 s) `IndexRepository.touch(notebookId)` — the
`updatedAt` discipline: the card's "last modified" follows ink, one UPDATE per burst, flushed on
close. Ink is durable the moment the row lands (WAL); a process kill loses at most the strokes
still queued in the channel.

## Close & lifecycle

- `onResume` → `paper.resumeDrawing()`.
- `onStop` (not closing) → app-scoped: `CoverSnapshot` + `saveLastOpened` (cheap durability point).
- Toolbar back / system back → `close()`: `lastOpenNotebookId = null` → app-scoped
  `NonCancellable`: cover → `saveLastOpened` → `refreshMeta` (name + folder path from the index)
  → `seal()` (`flushTouch` → `drain` → `wal_checkpoint(TRUNCATE)` → close) → `finish()`. Each
  step guarded; idempotent (`closing` flag; `onStop` stands down once closing).
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

## JVM tests

`StrokeRowsTest` (round-trip exactness both ways, every style, unknown-style/malformed-blob
fallbacks, format-B channel presence) and `StrokeStoreTest` (serial ordering incl. commit→erase
of the same stroke, monotonic `"order"` across erase, `drain` semantics, move keeps `createdAt`
and skips deleted, close drops writes, debounced-vs-flushed touch) — the store runs against an
in-memory `SoilDao` fake; `unitTests.isReturnDefaultValues = true` covers the `Log` calls in
production paths.

## Deliberate differences from Paper v0

- **Rich tool panels** (Paper v0 had fixed 3 px pen / 15 px eraser and no panels) + `ToolPrefs`
  persistence — the R3 wizard decisions above.
- Failed open shows a **problem dialog**, not Paper's toast (SN rule: a toast only confirms).
- No `TopGuard` padding on the top bar — the guard is 0 on Ratta; chrome sits flush.
- `CoverSnapshot` API-guards `WEBP_LOSSY` (API 30) with legacy `WEBP` on 29, like
  `BuiltInTemplates` does for lossless.
- No `liveStrokes` mirror / undo stack / page gestures yet — they land in R4 with undo-redo
  (Paper v0's file is the one-tree-later reference).
