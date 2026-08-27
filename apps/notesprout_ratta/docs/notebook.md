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
| `PageMath` (`:sn-screen`) | pure page arithmetic: `indexAfterDelete`, `insertPosition`, `toRestore`, `toDelete`. JVM-tested |
| `PageGestures` (`:sn-screen`) | the finger vocabulary over the paper — observer only, fed from `dispatchTouchEvent`, consumes nothing |
| `UndoRedoStack<A>` (`:sn-screen`) + `NotebookUndo.Action` | screen-level in-memory history; pure ordering, bounded at 100. Arc 11 / J1 split the two: the stack is generic and shared with the Scratch Pad's surface, while the notebook's fourteen action kinds — the N2 heading ones included — live in `:app` as `NotebookUndo.Action`, and the replay stays in `NotebookActivity` |
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
| `NotebookToolbar` | `[←] [contents] [pen] [eraser] [lasso] … [recents] [scratch pad]` — arming only; owns the fixed tool values (the Contents and Recents buttons belong to their flows, not to it). Back goes through `backPressed()`, never straight to `close()` (K4 — both Backs walk the link trail in a via-link notebook). O1: a second tap on the **armed lasso** calls back to the screen (the clipboard popup), and `showClipboardLoaded()` swaps that button's icon |
| `SelectionToolbar` | the floating bar over a live lasso selection: Delete (always) + H, plus (K1) **Link** / **Edit** / **Unlink** by `SelectionMode` (five modes since K1), plus (O1) **Copy** / **Cut** in every mode, plus (N2) the H1–H6 level sub-toolbar it can open |
| `LassoPopup` (O1) | the small bordered bar under the **armed** lasso button: **Paste** + **Clear** for the object clipboard. Opens only while the clipboard holds objects; the screen owns every dismissal |
| `ObjectClip` (O1) | pure selection ⇄ clipboard payload — capture, fresh ids, parent rewiring, the per-type `"order"` rebase, geometry translation (stroke = decode/translate/re-encode). JVM-tested. [`docs/clipboard.md`](clipboard.md) |
| `ObjectPlacement` (O1) | pure placement arithmetic: payload box + tap (or source origin) + page size → the clamped `dx/dy`. JVM-tested |
| **Links (arc 6)** | `LinkPayload` · `PageLink`/`LinkRows` · `LinkStore` · `LinkComposite`/`LinkRenderer` · `LinkPickerActivity`/`LinkPickerModel`/`PageCardGrid` · `LinkPickFlow` · `PickerPageSource`/`ForeignPageSource`/`PageReads`/`PagePreview`/`PreviewMath`/`PageLabels` · `LinkFollowFlow`/`LinkNav` · `data/prefs/LinkTrail` — the whole subsystem is documented in [`docs/links.md`](links.md) |
| `SelectionAnchor` (`:sn-screen`) | pure placement arithmetic for the bar (centre / gap / flip / clamp), (N2) `placeSub` — the sub-toolbar hung off the bar the same way — and (O1) `placeUnder`, a row hung under a chrome *button*, which never flips. JVM-tested |
| `core/OpeningOverlay` | the source-side "Opening…" box and its pre-draw + post launch sequencing |
| `OutlineTree` (C1) | pure Contents tree: items → nested H1–H6 nodes (orphans attach to the nearest shallower heading or become roots — never dropped), `visible`/`all`/`highlight`/`ancestorsOf` and the paging math. JVM-tested |
| `ContentsLayout` (C1) | pure Contents layout rules: the 480 dp sidebar/full-screen branch, 60 % sidebar width, 68 dp rows, `(level−1)×16 dp` indent, `itemsPerPage`. JVM-tested |
| `ContentsSource` (C1) | the gather (IO): writer drain → `liveHeadingsAll()` + `liveLinkPages()` → the pure `items()` pass (page resolution — page, else the link's page — `stripHeadingPrefix` label, `flags` level, document order, the 2000 cap) → `OutlineTree.build`. No cache — rebuilt every open |
| `ContentsFlow` (C1) | what both entry points call: busy guard, the `available` gate + generation-counted `refresh()`, pen-gated `releaseRender`, gather → `ContentsDialog`, `showing` (drives the host's BLOCK_ALL), `dismissIfShowing()` for the close path. Owns `btnContents` outright |
| `ContentsDialog` (C1) | the Contents screen: one layout, two forms (sidebar/full-screen), paginated rows, collapsible tree, active-entry highlight, tap = navigate |
| `RecentRows` (T1) | pure Recents arithmetic: stored order wins, the open notebook and dead/duplicate ids dropped, the breadcrumb join, `itemsPerPage`. JVM-tested |
| `RecentsSource` (T1) | the Recents gather (IO): `sn_recents` → one blob-free batch index read (`aliveNotebooks`) → prune → one ancestry walk per distinct parent → display rows. Touches no `.soil` |
| `RecentsFlow` (T1) | what the clock button and the two-finger swipe-down both call: busy guard, pen-gated `releaseRender`, gather → `RecentsDialog`, `showing` (drives BLOCK_ALL), `dismissIfShowing()`. Owns `btnRecents` outright |
| `RecentsDialog` (T1) | the Recents screen: `dialog_contents.xml` mirrored to the right (2 dp rule on the left edge), three-line rows, measured pagination, tap = switch notebooks |

## Layout (`activity_notebook.xml`)

`FrameLayout` root → `paperContainer` (the `PaperView`, added in code — `GPaper.create` needs a
Context) → `topBar` overlay (flush at the top edge — the top guard is 0 on Ratta; 1dp inkBlack
bottom border) → `bottomStrip` overlay ("`<name>` `n / N`", 1dp top border) → `selectionToolbar`
(floating, `GONE`, placed by margins) → `selectionSubToolbar` (N2 — its own floating `GONE` bar,
placed by `SelectionAnchor.placeSub` off the main bar when H is tapped) → `openingOverlay` (an
`<include>` of `overlay_opening.xml`, **last child so it is topmost**, and `VISIBLE` from the first
frame). Immersive: system bars hidden, transient by swipe. Portrait-locked.

The `topBarRow` is left-packed — Back, then Contents / Pen / Eraser / Lasso, all butted together
(the same spacing the scratch pad's row uses) — with a **weighted spacer** after the Lasso holding
the row's free space, so `btnRecents` (T1) and the Scratch Pad button sit flush at the right edge
and everything to their left keeps its position whatever the screen width.

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

`IndexGuard.ready` → extras (`EXTRA_NOTEBOOK_ID`, `EXTRA_NOTEBOOK_NAME`; K4 adds
`EXTRA_VIA_LINK` + `EXTRA_INITIAL_PAGE_ID` — the initial page is **consumed once**, read only when
`savedInstanceState == null`, so a recreated via-link notebook lands on its remembered page rather
than re-following the redelivered Intent; [`docs/links.md`](links.md)) →
`BrowseState.lastOpenNotebookId = id` (+ `lastOpenViaLink`, K4), `RecentsPrefs.record(id)` → `repo.alive(id)` (else problem
dialog + finish) → `session.open()`: `KeySession` passphrase → file must exist and be non-empty
(**never created here**) → `SoilDatabase.open` (raw-key fast path via `KeyOpener` when cached) →
page rows (none → fail) → last-open page from the notebook row's `refId` → template decoded with
`Bitmaps.decodeBounded` (≤ 4096 px). Then on Main: `setPageSize(w,h)` (the page's authored px
rect, so ink registration survives a different screen), `setTemplate`, headings and links loaded
(`session.headings.loadPage` / `session.links.loadPage`, handed to their renderers **before**
`loadStrokes` so the load's re-record already paints them — for links this ordering is
load-bearing, the K1 hover-repaint trap; [`docs/links.md`](links.md)),
`loadStrokes(store.loadPage(id))`, page indicator.

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
  at all. It **auto-hides on any `ON_RESUME` with no launch still pending**, so the library is clean
  when the user comes back; a source that finishes itself simply dies with its overlay.
- **The auto-hide is not pause-gated (B3), and that matters more than it looks.** The rule used to be
  "hide on the first `ON_RESUME` after an `ON_PAUSE`", which assumes every show is followed by a
  pause — true of the tap path and of nothing else. An activity that shows the box while it is *not*
  resumed (recreated in the background, or opening from `onCreate` on the launch-restore path)
  resumes with no pause on record and hides nothing. And a stranded box is not cosmetic: the root is
  full-screen and `clickable`, so it swallows **every** tap and the screen underneath is dead until
  the process is killed — seen once on the Nomad, a library that answered nothing. The `launchPending`
  flag is what still protects the restore path: the one resume that must leave the box alone is the
  one that arrives while the launch is in flight. A `WATCHDOG_MS` timer hides it anyway if the launch
  never draws at all (a source that is off-screen never gets `onPreDraw`, so `then` never runs).
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
| `onStrokesErased(ids)` | soft delete (`deletedAt`) — the **eraser tool** only, as of arc 14; a scribble reports through `onScribbleErased` instead |
| `onSelectionMoved(m)` | read rows → decode → `Stroke.translated(dx,dy)` → re-encode → upsert (`createdAt` kept); headings among `move.contentIds` (N2) get the same delta through `session.headings.move` and the working copy is patched too; `currentSelection`'s bounds shift by the same delta, and the selection toolbar re-anchors there |
| `onSelectionCreated/Dismissed` | `selectionActive` flag + the `currentSelection` copy + show/hide the selection toolbar (N2: `onSelectionDismissed` also consumes `pendingSelection` — see below) |
| `onSelectionTapped(x, y)` (N2) | hit-tests `currentSelection`'s heading ids against `liveHeadings`; a hit opens `HeadingEditDialog`. A tap over ink only, or outside any heading's bounds, still does nothing |
| `onContentErased(ids)` (N2) | the eraser tool swept a heading or a link whole → `removeContent` (rows + working copies + both renderers + `notifyContentChanged`), recorded as `Action.HeadingDeleted`, or `Action.Deleted` when a link was in it |
| `onScribbleErased(strokeIds, contentIds)` (arc 14) | a scribble crossed out ink **and** content in one gesture: `store.erase` for the strokes, the same `removeContent` for the rest, recorded as **one** `Action.ScribbleErased`. One callback because one gesture must be one undo step — see below |
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
Each callback records the matching `NotebookUndo.Action`.

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
| Draw a lasso outline (or a smart-lasso loop) | engine draws the box; host shows the **selection toolbar** anchored to it, in one of five modes (below; the two link modes are [`docs/links.md`](links.md)) |
| Drag inside the box | `onSelectionDragStarted` hides the bar(s); the engine translates + re-renders; `onSelectionMoved` → `store.move`/`headings.move` + working-copy patch + `Action.Moved`, then the bar re-anchors at the new bounds |
| Tap the bar's **Delete strokes** | `releaseRender()` then `deleteSelection` (order below) |
| Tap the bar's **H**, then a level (N2) | `onLevelPicked` — CONVERT on a pure-stroke selection, CHANGE on a lone heading (below) |
| Tap inside the box, over a heading (N2) | `onSelectionTapped` hit-tests `liveHeadings` and opens `HeadingEditDialog` |
| Tap inside the box, over ink only | nothing |
| Tap the bar's **Copy** / **Cut** (O1) | the selection goes on the global clipboard; the bar goes, and the host **re-arms `Tool.LASSO`** so the next tap places ([`docs/clipboard.md`](clipboard.md)) |
| Tap bare paper with the lasso armed and **nothing** selected (O1) | `onPaperTapped` (g-paper 0.1.5) → the clipboard's objects paste **centred on the tap**, landing selected |
| Tap the bar's **Snap** (A1) | snap-to-guide flips for every drag from now on — nothing on the page moves ([Snap to guides](#snap-to-guides-a1) below) |
| Tap outside / tool change / any data-in call / page swap | `onSelectionDismissed` → bar(s) hidden, mirror cleared (unless a converted heading's selection is waiting to take its place — see Headings below) |

### The selection toolbar

A bordered row floating over the paper: (A1) **Snap** and (O1) **Copy** / **Cut** first — all three
offered in every mode — then (N2) an **H** button that opens a second floating bar of its own, the
H1–H6 level sub-toolbar (`SelectionMode` and the convert/change flows are covered under Headings
below), then Link / Edit / Unlink, then Pad, and **Delete** last. It is a *bar*, not a button,
because it is the shape the selection's actions live in from here on.

**Delete sits on the far edge, alone**: it is the one destructive verb, and it is kept away from
the buttons the hand reaches for casually. (It led the row from P1 through arc 13; the order above
supersedes that.)

**Snap leads** because it is the one button that is not an act on this selection. Everything after
it does something and the bar goes away; Snap changes how the *next* drag behaves and the bar stays
exactly as it was.

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

## Snap to guides (A1)

A dragged selection can be pulled onto the page's own structure instead of landing wherever the pen
let go. Off by default; the toggle is the bar's last button, and the setting outlives every
selection, page, notebook and relaunch (`data/prefs/SnapPrefs.kt`, `sn_snap`/`enabled`).

**It is g-paper's, and it had to be.** `CanvasPaperView` owns `lassoTryBeginDrag` /
`lassoDragMove` / `lassoDragFinish` and the drag layer's `onDraw`; the host never sees a sample of
the drag. So **g-paper 0.1.6** grew `SnapEngine` (pure, `core/geometry`, JVM-tested there) plus two
host-facing properties, and the host's whole share is a button and a preference — the standing rule
that engine gaps are fixed in the engine, not worked around above it.

**The guides.** Per axis, the page contributes five (edge · margin · centre · margin · edge) and
every content object *not* in the selection contributes five more (`left − margin` · `left` ·
`centerX` · `right` · `right + margin`, and the same on Y). The ±margin **proximity** guides are
what make equal spacing fall out of a drag: pull one heading below another and it catches exactly
one margin from its neighbour's edge, with no measuring.

**The margin is one toolbar thick** — and *the whole toolbar*, which is why `paper.snapMarginPx` is
set from `topBar.height` in `pushExclusions()` rather than from `@dimen/toolbar_bar_thickness`. That
dimen (70 dp on the Nomad/Manta tier, 56 dp below) sizes the **button row**; `topBar` is that row
plus a 1 dp `inkBlack` border, so snapping to the dimen alone would park the top of an object two
pixels behind the black rule — breaking the exact invariant the value was chosen for. Reading the
measured height also means the margin can never drift from the chrome it names. og used 44 dp here,
which was its *small*-tier button size and lines up with nothing on a Supernote.

**Strokes are never snap targets.** Only headings and links (whatever `hitTargets()` returns) are.
On a handwriting page ink is everywhere; a guide per stroke box would be a thicket that fights the
pen instead of helping it.

**Nothing is clamped.** Anchors are the selection's leading edge, centre and trailing edge per axis,
taken from the **tight** bounds rather than the 12 px-inflated box the overlay draws — the user is
aligning content, not chrome. The nearest (anchor, guide) pair within 20 dp wins, X and Y decided
independently, and the guide holds only while the pen stays inside that threshold. Drag on and it
simply lets go. `onSelectionMoved` reports the **snapped** delta, so `store.move` and `Action.Moved`
need no knowledge of any of this.

**Where it does not apply:** O1's tap-to-place. A paste still lands exactly where the pen tapped —
it arrives selected with the bar up, so the next drag snaps it. A paste that relocated itself would
read as the app moving your content on its own.

**The toggle.** One icon (`ic_snap`, Tabler `layout-align-left` with its rule broken into long
dashes — the same stride g-paper's `snapGuidePaint` draws a caught guide with, so the button shows
what the page shows: a selection sitting against a guide. The two blocks stay solid; at 24 dp with a
2-unit stroke a dashed rectangle is all corner and no rectangle) with the selected border from
`bg_toolbar_button`, which is already how the top bar shows which tool is armed; the long-press hint
says "on"/"off" in words, because a border alone is something you have to have been told about. No
toast — the border *is* the confirmation, and the current selection does not move.
`NotebookActivity.toggleSnap()` writes both the live flag (`paper.snapToGuides`) and the durable one
in the same breath so they can never disagree.

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

The **screen** classifies every selection into a `SelectionMode` (`showSelectionToolbar`, read off
the working copies — since K1 the bar only renders the classification):

| Mode | When | H button |
|---|---|---|
| `STROKES` | ink only, no content ids | shown — tapping it opens the level picker in **CONVERT** |
| `HEADING` | `contentIds` is exactly one heading and `strokeIds` is empty | shown, and the sub-toolbar (once opened) highlights the heading's **current level** with a 1dp inkBlack border — this is **CHANGE** |
| `MIXED` | ink plus a heading, or more than one heading — no link | hidden — no single sensible level to write; Delete and (K1) **Link** remain |
| `LINK` / `MIXED_WITH_LINK` (K1) | a lone link / a link alongside anything else | hidden — the link modes' buttons (Edit/Unlink; Link withheld — the no-nesting rule) are [`docs/links.md`](links.md)'s |

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
hit target whole, in one batched callback per gesture. The host deletes on the engine's word — nothing vanishes by
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
≥ 1 live heading on a live page — **loose or wrapped in a link** (`ContentsSource.available` —
exact: one id-only EXISTS query
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
`flags`; blank-stripping or rows whose page cannot be resolved are dropped, never crashed on; a cap of
`ContentsSource.MAX_ENTRIES` (2000) bounds a pathological imported file, with the honest
"Showing the first N headings" footer.

**A wrapped heading is listed too.** A heading's `parentId` is its page while it is loose and its
**link** once a wrap re-parents it (arc 6 / K1), so the gather resolves a page in two hops: the
page map directly, else `SoilDao.liveLinkPages()` (every live link → the page it sits on) and then
the page map. Nothing else is needed — only the parentage moves in a wrap, the child keeps its
page-absolute `(x, y)` — so the entry sorts into document order exactly where it is written, and a
tap navigates by the resolved **page id** like any other. A link on a dead page, or a heading whose
link is soft-deleted (a link erases whole, children and all), resolves to nothing and is dropped by
the same rule that has always dropped an unresolvable row. `anyLiveHeadingOnLivePage` reaches
through a link the same way — the gate must reach exactly as far as the gather, or the button would
hide an outline that has entries.

This reverses K1's "a wrapped heading belongs to the link, everywhere" (2026-08-26, the user's
call): both places that answer *what is written on this page* now reach through a link — the outline
here, and the link picker's page label (`PageLabels.titleOf(PageContent)`). Ownership rules that
govern *editing* — the eraser, delete, move, the page cascade — are untouched: a link is still
whole.

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

## Recents (arc 10)

og's in-notebook **Recent Notebooks** switcher, fitted to SN and **mirrored to the right** — the ToC
seen from the other side. It lists the notebooks you have opened recently and switches to the one you
tap. Nothing about it is stored anywhere new: it reads the same device-local `sn_recents`
`SharedPreferences` the library's Recents shelf reads (`data/prefs/RecentsPrefs` — **id and timestamp
only**, so a notebook's name never reaches plaintext prefs), and resolves names and folder paths from
the global index at gather time. No schema change, nothing in any `.soil`.

**Entry points:** `btnRecents` (Tabler `clock`) **at the top bar's right edge**, with the Scratch
Pad button after it — a weighted spacer after the Lasso puts them there, because neither is a tool
and the Recents panel comes in from that side
— and a **two-finger swipe down** on the paper. Unlike the Contents, neither is gated: the button is
always visible and the swipe always acts, because "nothing recent" is a real answer the panel gives
("No recent notebooks") rather than a reason to hide a control.

**The timestamp is a close stamp.** `RecentsPrefs.record()` still runs at `onCreate` — opening is what
puts a notebook at the front of the list — and `RecentsPrefs.touch()` now re-stamps it as the screen
goes away, so a row reads *when you last put the notebook down*, not when you picked it up. `touch()`
never inserts and never reorders (the open already moved it to the front); `close()` and the
`onDestroy` fallback are mutually exclusive on `closing`, so exactly one stamp is written per screen.

**The gather** (`RecentsSource`, IO): every stored id in one **blob-free batch** index read
(`ObjectDao.aliveNotebooks` — `IndexRepository.alive()` reads whole rows, cover blob included, which
is a megabyte the panel never draws), ids that no longer resolve pruned from the store in the same
pass (self-healing, like the library shelf), then one ancestry walk per *distinct* parent folder for
the breadcrumb. The open notebook is looked up like any other — it is health-checked and only then
dropped from the display list, because excluding it earlier would make it the one id the prune could
never verify. Ordering is `RecentRows.select`: **stored order wins**, dead ids out, duplicates
collapsed, the current notebook never listed. Logs counts and durations — never a name.

**The screen** (`RecentsDialog`): `dialog_recents.xml` is `dialog_contents.xml` mirrored — panel
anchored `end`, its 2 dp inkBlack rule on the **left** edge (`shape_recents_sidebar`), header running
title-then-arrow so the dismissal sits nearest the edge the panel came from. The 480 dp full-screen
breakpoint is shared with `ContentsLayout` — "a sidebar doesn't fit here" is decided once — but the
width is its own: `RecentRows.SIDEBAR_WIDTH_FRACTION` = **50 %** (702 px on the Nomad), narrower than
the Contents' 60 %, because a row is a name, a time and a path. Rows are three lines — name 20 sp, `<medium date>, <time>` and the full
breadcrumb at 13 sp, all inkBlack (the palette rule: secondary text is *smaller*, never grey). It
paginates, never scrolls: one row is inflated and **measured** at the real panel width after the first
layout (three lines at two text sizes is not a height worth guessing) and `RecentRows.itemsPerPage`
follows, with the library-shape pager footer `INVISIBLE` at one page.

**The hop** (`NotebookActivity.switchToNotebook`): the panel is a snapshot, so the tapped notebook is
re-checked against the index first — gone → the "Can't open that notebook" problem dialog, and this
screen stays. Otherwise the link-follow's order exactly: raise the "Opening…" box, and only once its
frame is on the glass `close { startActivity(…) }` — one live session per `.soil`, family-wide.
Deliberately **not a follow**: nothing is pushed onto the link trail and the target opens without
`viaLink`, so its Back exits to the library. Being a fresh open, it takes the library's rule with it
— `onCreate` **clears** the trail on arrival — and that is deliberate, not a side effect: a trail
left standing across a switch would let a link followed later in the *new* notebook walk back into
the notebook you switched away from. A switch starts a new story.

**BLOCK_ALL while showing**, on the Contents' reasoning and through the same `pushExclusions()`
branch; `close()` and the `onDestroy` fallback both call `RecentsFlow.dismissIfShowing()`.

### JVM tests specific to the Recents

`RecentRowsTest` — stored order kept against both an alphabetical and a chronological trap, the
current notebook dropped (including a duplicate of it), dead ids dropped, duplicates collapsed,
nothing invented, the breadcrumb's root-only and nested forms, and `itemsPerPage` (whole rows, ≥ 1,
and no divide-by-zero on an unmeasured row).

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
| `pasteAt(env, before)` (B1) | write the clipboard payload's rows — a fresh page row, its content, and the template unless `resolveTemplate` finds this file already has it (by id, or B2, by content) — at `PageMath.insertPosition`, renumber, land on the pasted page. Across notebooks the page's own-notebook links are rewritten to name the source ([`docs/clipboard.md`](clipboard.md)) |
| `capturePage()` (B1) | snapshot the current page, its template row and its live descendants into a clipboard payload; the caller drains the writer first |
| `changeTemplate(paper, dpi)` (arc 12; a `PaperSource` since arc 13) | re-paper the **current page only**: find or mint the `template` row for that paper's **token** at the page's own size, point the page's `refId` at it, mirror the index clock. Null when the page already has that paper |
| `applyTemplate(pageId, templateId)` (arc 12) | point one page's `refId` at one template id (`""` = blank) — `changeTemplate`'s undo/redo primitive; the decode is left to the caller's page swap |
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
| 1-finger vertical swipe ↓ | open the Contents (C1 — silent while the notebook has no heading) |
| 1-finger vertical swipe ↑ | walk back the link trail (K4 — silent while the trail is empty; [`docs/links.md`](links.md)) |
| 1-finger tap on a link | follow it (K4 — finger only, never stylus; the escrowed inverse-recogniser tap below) |
| 2-finger horizontal swipe ← / → | insert a page after / before this one |
| 2-finger vertical swipe ↓ | open the **Recents** (T1 — its upward twin is unassigned) |
| 2-finger stationary double-tap | undo |
| 3-finger stationary double-tap | redo |
| 1-finger long-press | the **page sheet** — Copy / Cut / Paste / Page template (arc 12, the whole library since arc 13; [`docs/templates.md`](templates.md)) / Delete (B1; [`docs/clipboard.md`](clipboard.md)) |

Thresholds (Paper-v0 parity — the numbers are the feel):

| Constant | Value | Meaning |
|---|---|---|
| `PAGE_SWIPE_MIN_DISTANCE_FRAC` | `0.30` × screen width | minimum horizontal travel before anything counts |
| `PAGE_SWIPE_LONG_DISTANCE_FRAC` | `0.50` × screen width | travel that qualifies on its own, however slow |
| `PAGE_SWIPE_MIN_VELOCITY_MULT` | `1.0` × `scaledMinimumFlingVelocity` | the fling alternative |

A swipe must be **horizontal-dominant** (`|dx| > |dy|`) and qualify on *velocity or* length;
**direction comes from the sign of `dx`, never velocity**, because a decelerating finger can flip
the velocity sign at the end of the drag. The vertical swipes (C1, K4) are the same rule rotated
90° — vertical-dominant, the same three constants against the screen *height*, judged at the same
`ACTION_UP` right after the flip evaluation (the two dominance tests are mutually exclusive) — and
one sign-routed evaluation: `dy > 0` opens the Contents, `dy < 0` walks back the link trail (K4),
so the two can never both fire. The one-finger **tap** (K4) is the inverse recogniser: sub-slop
travel, under the long-press timeout, single-finger, judged at `ACTION_UP` with the down point
reported — and it rides the same escrow as the double-taps below, so a pen tail can veto it late.
The two-finger swipe measures the two-finger centroid and
commits at `POINTER_UP` back to 2→1 fingers; a third finger landing mid-swipe commits a qualifying
gesture before it dies, and a second finger landing on an already-qualifying one-finger swipe
commits the flip for the same reason.

The **two-finger vertical** swipe (T1) is the insert's rule rotated 90°, evaluated at every place the
insert is — the `POINTER_UP` back to one finger, and the 3+-finger commit — and, like the one-finger
pair, mutually exclusive with it by dominance. Only **down** is claimed: it opens the Recents panel.
A consequence of the late-arrival rule above: a two-finger swipe whose first finger has already
travelled a qualifying distance before the second lands is committed as a *one*-finger swipe, so it
opens the Contents instead — land both fingers together.

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
The stack is cleared when the screen closes — in-memory history dies with the screen — and (K3)
when the link picker created a page in **this** notebook behind the screen's back: the old
`Structural` snapshots no longer describe the page list, so the whole stack goes rather than a
replay reviving the wrong shape (the new link's own `LinkCreated` is recorded *after* the clear
and survives it — [`docs/links.md`](links.md)).

| Action | Recorded by | Revert | Reapply |
|---|---|---|---|
| `Drew` | `onStrokeCommitted` | `store.remove([id])` | `store.revive` |
| `Erased` | `onStrokesErased` (the eraser tool; a scribble records `ScribbleErased` instead) | `store.revive` | `store.remove` |
| `ScribbleErased` (arc 14) | `onScribbleErased` — one scribble, whatever mix of strokes / `headingIds` / `links` snapshots it crossed out | `store.revive` + `headings.restore` + `links.restore` | `store.remove` + `headings.erase` + `links.remove` |
| `Deleted` | the selection toolbar's Delete (strokes **and**, N2, `headingIds` — **and**, K1, `links` snapshots: a whole-link erase records here too; O1's **Cut** goes through the very same path, so undoing a cut puts the ink back exactly as undoing a Delete would) | `store.revive` + `headings.restore` + `links.restore` | `store.remove` + `headings.erase` + `links.remove` |
| `Moved` | `onSelectionMoved` (strokes **and**, N2, `headingIds` **and**, K1, `linkIds` riding the same drag) | `store.move(-dx,-dy)` + `headings.move(-dx,-dy)` + `links.move(-dx,-dy)` | `store.move(dx,dy)` + `headings.move(dx,dy)` + `links.move(dx,dy)` |
| `HeadingCreated` (N2) | a successful convert | `headings.erase` + `store.revive` (in place — order matters) | `headings.restore` + `store.remove` |
| `HeadingDeleted` (N2) | edit-dialog empty Save, or the eraser sweeping a heading whole | `headings.restore` (in place) | `headings.erase` |
| `HeadingTextEdited` (N2) | the edit dialog's Save | write `before`'s content | write `after`'s content |
| `HeadingLevelChanged` (N2) | a level pick on an existing heading | write `before`'s content | write `after`'s content |
| `LinkCreated` (K1) | the picker's OK on a create ([`docs/links.md`](links.md)) | `links.unlink` | `links.relink` |
| `LinkUnlinked` (K1) | the selection toolbar's Unlink | `links.relink` | `links.unlink` |
| `LinkEdited` (K2) | the picker's OK on an edit | write `before`'s payload | write `after`'s payload |
| `Page` | insert / delete (`Structural` snapshot, whose `objectIds` are type-agnostic — strokes, headings and links all) | `reconcile(before)`, **restoring** `objectIds` | `reconcile(after)`, deleting them |
| `PagePasted` (B1) | a paste — the same `Structural` shape, its own kind because `objectIds` runs the **opposite direction** (rows the paste *created*) | `reconcile(before)`, **deleting** `objectIds` | `reconcile(after)`, restoring them |
| `TemplateChanged` (arc 12) | a pick in the template library — the two template ids the page moved between (`""` = blank). No drain: it writes one page row and never touches the stroke writer | `applyTemplate(from)` | `applyTemplate(to)` |
| `ObjectsPasted` (O1) | an object paste — `Deleted` run in reverse, its own kind for `PagePasted`'s reason (a link travels as a `PageLink` snapshot, so undo takes its wrapped children down with it) | `store.remove` + `headings.erase` + `links.remove` | `store.revive` + `headings.restore` + `links.restore` |

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

The long-press **asks**; it never acts. `showPageSheet` opens an `ActionSheetDialog` with
**Copy page · Cut page · Paste page · Page template · Delete page** — Paste present only when the
clipboard holds a page (**absent, never disabled**: a greyed control is invisible on e-ink). Copy
and Cut confirm with a toast; Paste opens a second sheet for the placement (before/after); Page
template opens the template library (below); Delete goes to its confirm dialog. The whole clipboard
side is [`docs/clipboard.md`](clipboard.md).

The delete confirm is the bare question "Delete this page?" with **no warning body** — a deleted
page and its ink come straight back via undo (soft delete + `reconcile`), so "cannot be recovered"
would be false (eye-check #2 finding, 2026-08-22). `showPageSheet` calls `paper.releaseRender()`
**ungated**, which is safe here only because the long-press fired through the gesture gate: it
never arms while the pen is active and re-checks at fire, so we are outside the pen-active window
the R3 rule protects.

### Page template (arc 12; the whole library since arc 13)

**Page template** opens the **template library**, full-screen — the same browser the Templates
screen and the New Notebook screen host, with the same folders, the same shelves and the same
import ([`docs/templates.md`](templates.md)). Arc 12 shipped this row as a four-choice sub-sheet;
arc 13 / G3 replaced the sheet with the browser and left everything below it unchanged.

The launch is the `LinkPickerActivity` shape — `TemplatesActivity.pickIntent` through an
`ActivityResultLauncher` registered in `onCreate`, chrome only, and **no `releaseForHandoff`**: the
picker is not a paper surface. The result is a `TemplatePick` — a short string naming a *card*
(Blank, a built-in kind, or a static row's id), never pixels in an Intent extra. **The browser never
opens a `.soil`**; `doChangeTemplate` reads the pixels itself (`TemplatePicks.paper`, on IO) and the
session does the write.

Picking re-papers **this page only**, which is the same scope every other row of the sheet has:
Copy, Cut and Delete are all the page you long-pressed. Ink is untouched — a template is the paper
*under* the strokes, and re-ruling a page never moves, resamples or reflows what is written on it.

The launch is **asynchronous**: the tick needs the token the page is on, which is a `.soil` read
(`session.currentTemplateToken()` → `templateDigests`, blob-free — never `byId`, which would drag a
whole WEBP through the cursor to read one word). The sheet the user just tapped is already dismissed
by then, so no window exists where two surfaces are up. A read that **fails** still opens the
browser, with nothing ticked: every card is still a valid choice, an unknown token already ticks
nothing, and there is nothing here the user must act on.

Three states share "no tick", deliberately (`PageTemplate.tokenOf`): the row has vanished, its
`text` is a token this build cannot name (family-compatible files can carry paper we cannot draw),
or the read failed. Ticking **Blank** for any of them would claim the page is empty while a ruled
sheet is on the glass — a lie the user can see through. An empty `refId`, on the other hand, *is*
Blank: that is what blank means in the format, not a missing answer.

**A pick that will not resolve changes nothing.** A static row deleted between the tap and the
apply raises a problem dialog and leaves the page exactly as it was — a template that vanished must
never become blank paper by default. A result this build cannot decode at all is treated as a
**cancel**, for the same reason.

**Nor does a pick that resolves but will not draw.** Paper whose stored bytes no longer decode, or
whose bitmap the device refuses to allocate, throws `NotebookSession.PaperRenderFailed` out of
`changeTemplate` **before anything is written** — its own dialog, no undo step, nothing recorded as
recent. Arc 12 had no live case for this (its only null render was a page with no size, which cannot
happen), so a failure fell through to `""` and blanked the page; arc 13's imported pixels made it
reachable, and wiping paper the user can see because we could not redraw it is the one outcome the
whole vanished-template rule exists to prevent.

An apply that resolved is also the one thing that makes paper **recent** (`TemplateRecents.record`,
arc 13 / G5). It is recorded before the no-op check: re-picking the paper already in force writes no
page row and raises no undo step, but the user did choose it, and a prefs write is not a page change.

**Reuse before mint** (`PageTemplate.reusableId`, pure, JVM-tested). A `template` row is *shared
paper*: every page a notebook was created with points at one row. So a change first looks for a row
this file already holds that carries the wanted **token** — arc 13's one vocabulary for built-in
paper and imported pictures alike — **at the page's own size**, and only renders and stores a new
one when there is none. That makes Lined → Grid → Lined free — nothing ever
soft-deletes a template, so the way back finds the original row still standing. Among equal matches
the page's **current** id wins, so picking the card the browser already ticked is a true no-op rather
than a re-point onto an identical-looking twin plus a pointless undo step. (Two rows of one kind at
one size is possible: a page pasted from a notebook whose panel had a different density, so the
paste's content dedupe found no match — [`docs/clipboard.md`](clipboard.md).)

Identity is deliberately `token + page size`, not the pixels: a byte-identical row arriving from
another notebook was already deduped by content on the way in (`resolveTemplate` →
`PageClip.matchTemplate`), so the only row that could pass this test while looking different is one
authored at the same page size and a different panel dpi — a device that does not exist in the
family.

The render uses the **page's own** width/height, never the screen's. A page pasted in from a larger
device keeps its authored size (ink is never resampled), and ruling it to this screen would print a
template that stops short of its own edge.

The **old row is left exactly where it is**. Nothing may still point at it, but a template is
cheap, deleting one is not undoable, and leaving it is what makes the change back free — the same
reasoning the paste path uses for the template row it may have inserted.

The notebook's index `templateKind` is **not** touched: it is the notebook's birth record, a real
cover snapshot supersedes it on every close, and with per-page paper there is no longer one true
answer for a whole notebook ([`docs/library.md`](library.md)).

`applyTemplate` deliberately does **not** decode. `loadTemplateFor` compares against
`templateIdLoaded`, so the id changing is exactly what makes the following `navigateTo` reload the
bitmap — one decode, on the swap that paints it, in one EPD refresh. It is also safe on a page
deleted since: the write lands on a soft-deleted row (which a restore then honours), the page list
has no entry to update, and `refreshToPage` finds no index and stays put.

## Close & lifecycle

- `onResume` → `paper.resumeDrawing()`.
- `onStop` (not closing) → app-scoped: `CoverSnapshot` + `saveLastOpened` (cheap durability point).
- Toolbar back / system back → **`backPressed()`** (K4 — in a via-link notebook both Backs walk
  the link trail first, exactly like a swipe-up; only an empty trail falls through to `close()`) →
  `close()`: `lastOpenNotebookId = null` → app-scoped `NonCancellable`: cover → `saveLastOpened`
  → `refreshMeta` (name + folder path from the index) → `seal()` (`flushTouch` → `drain` →
  `wal_checkpoint(TRUNCATE)` → close) → `finish()`. Each step guarded; idempotent (`closing`
  flag; `onStop` stands down once closing). K4 adds `close(andThen)`: a cross-notebook hop
  launches the next screen **strictly after the seal completes** — the seal/reopen race is not
  survivable any other way ([`docs/links.md`](links.md)).
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
  exists. Read once, cleared regardless of outcome. K4 rides `lastOpenViaLink` along with it, so
  a via-link notebook is restored *as* via-link — without it the restore would read as a fresh
  open and clear the persisted link trail ([`docs/links.md`](links.md)).

## Frame-silence rule

No app frame is presented while `paper.isPenActive` — the strip text only changes through
`whenPenIdle {}` (re-polls every `PEN_ACTIVE_TAIL_MS`). Nothing else on the screen repaints
during writing.

Seven recorded exceptions, all the same shape — **one chrome frame at a deliberate act or a
boundary**, never under live ink:

1. the **page sheet at long-press** (R4 — safe because `PageGestures` never arms while the
   pen is active and re-checks the gate at fire, so the sheet lands outside the pen-active window.
   B1's paste-placement sub-sheet **rides this same exception** rather than opening a new one: it
   is raised by a tap on a row of a dialog that is already up, so the pen is demonstrably idle —
   and so does arc 12's page-template launch — an Activity since arc 13, and still the same act —
   whose one blob-free read between the tap and the launch is not a reason to re-gate on the pen:
   `isPenActive` counts hover, so a gate there would hold it while the pen merely floats near the
   glass, which is the R3 lesson. **Arc 13 added no new exception**);
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
6. the **Contents and Recents dialogs' show/hide** (C1, and T1 riding the same exception — the show
   follows a deliberate act that already passed a pen gate: a chrome tap on `btnContents` /
   `btnRecents`, or a swipe committed through `PageGestures`' `gateOpen()`; the hide is a deliberate
   row / scrim / back tap. Both are screen boundaries, never a repaint under live ink, and both
   flows pen-gate their `releaseRender` first. Deliberately *not* idle-gated — `isPenActive` counts
   hover, and a hovering pen would hold the screen hostage, the same reason as exceptions 2 and 3).
7. the **object paste's frame at pen-up, and the lasso popup's show/hide** (O1). The paste is the
   direct visible result of a deliberate tap: the pasted content, the selection box and the bar all
   land in one frame at the *tap's* pen-up, where nothing is being written — the same justification
   as exception 2, applied to the act that creates the selection rather than the one that follows
   it. Deliberately not idle-gated for exception 2's reason as well: the pen that just tapped is
   still hovering, so a gate would deliver the paste long after the tap that asked for it. The
   popup's show follows a chrome tap on the lasso button (`releaseRender()` first, pen-gated inside
   `NotebookToolbar`), and every one of its hides is a deliberate act — a tool switch, an outside
   contact, a page swap, a paste, a clear.

R3's exception — the tool-panel close at stylus pen-up — is **retired**: P1 removed the panels.

**Arc 10 added no new exception**: the Recents panel is folded into exception 6 above (it is the
Contents dialog's act, mirrored), and its button never changes visibility, so the chrome it owns
presents no frame of its own.

**Arc 9 added no new exception**: the Snap button re-styles itself on its own deliberate chrome tap,
through `releaseRender()` first — exception 5 exactly. The guide lines themselves are drawn by
g-paper inside the drag layer it was already repainting, so they cost no frame the drag did not
already present.

**Arc 6 added no new exception**: every link surface (the follow's navigate and "Opening…"
overlay, the dead-target dialog, the swipe-up walk-back) enters through a finger gesture behind
`PageGestures`' pen gate, and the picker and the selection toolbar's link buttons ride the
existing selection-toolbar exceptions (2 and 5).

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
`PageTemplateTest` (arc 12, widened in arc 13) covers the re-paper decision — reuse the notebook's
existing paper vs. mint another copy of it, the page's own row winning among identical twins, a
pixel-less row refused, a different page size minting, blank never doing either, and an `IMG#` token
matching only itself — and `tokenOf`'s three "no tick" states kept apart from real blank paper. `NotebookUndoTest` gains the `TemplateChanged` case
(both ids carried, blank's `""` included — an entry that dropped it could not undo a page back to
blank).

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

**Arc 6 (links):** the inventory lives in [`docs/links.md`](links.md) — `LinkPayloadTest` (incl.
the Paper-grammar byte fixtures), `LinkRowsTest`, `LinkStoreTest` (against the same
`FakeSoilDao`), `LinkCompositeTest`, `LinkPickerModelTest`, `PageLabelsTest`, `PreviewMathTest`,
`PageReadsTest`, `LinkNavTest`, `TrailCodecTest`, and `library/SchemePrefillTest`.

**Arcs 7–8 (the clipboard):** `ClipEnvelopeTest`, `ClipStoreTest` (incl. the O1 kind-swap case:
objects take the page's slot and a page copy takes it back), `PageClipTest` — and O1's
`ObjectClipTest` (fresh ids, top-level vs wrapped parenting, the dropped orphan, a refused nested
link, the per-type `"order"` rebase with children kept verbatim, the stroke decode→translate→
re-encode round trip, the ink-extent box handed to the placement, an unusable blob costing one
stroke, and a round trip through the clipboard codec) and `ObjectPlacementTest` (centre-on-tap,
all four clamps, content bigger than the page, source-coordinate paste, an unknown page size, a
non-finite box). `SelectionAnchorTest` gains the `placeUnder` cases. Full detail in
[`docs/clipboard.md`](clipboard.md).

**Arc 9 (snap) has no tests in this repo, on purpose.** The whole of the logic is
`SnapEngine.computeSnap`, which lives in g-paper — so its suite lives there too
(`gpaper-core/src/test/.../geometry/SnapEngineTest.kt`: each page guide on each axis, object edge /
centre / proximity catches, nearest-wins, the page-over-object and leading-edge tie-breaks, release
past the threshold, exact-threshold miss, independent axes, no targets, a zero page dimension, a
zero margin, and a zero-size selection). What is left on this side is a preference and a button,
which is a device eye-check, not a unit test — and one adb can only half reach, since it can neither
lasso nor drag.

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
