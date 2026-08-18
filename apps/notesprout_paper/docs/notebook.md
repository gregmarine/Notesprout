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
| `SoilWriter` | the notebook's **one** serial IO writer (a `Channel` of jobs, arc 4 / H1 — lifted out of `StrokeStore`): both stores enqueue here; the debounced index `updatedAt` bump; `drain()` before seal / before an undo replay reads rows back |
| `StrokeStore` | g-paper callbacks → `stroke` rows through the writer; `loadPage()` |
| `ObjectStore` | `object` rows (arc 4): `loadPage()`, `create`, `updatePayloadAndBounds`, `move`, `remove` / `restore` — same writer, so objects and strokes never race |
| `PageObject` / `ObjectRows` | the in-memory object + the pure `PageObject ⇄ SoilObjectEntity` mapper (JVM-tested) |
| `ObjectRenderer` / `ObjectRenderCache` | the g-paper `ContentRenderer` bridge (placeholder or cached bitmap; live-drag pair; `hitTargets`) + the session-only bitmap cache |
| `StrokeRows` | pure mapper `Stroke ⇄ SoilObjectEntity` (format-B blob, `InkColorCodec`, `StrokeStyle` name; unknown → PEN). JVM-tested |
| `CoverSnapshot` | `paper.renderToBitmap()` → ≤ 512 px long edge → WEBP q100 → `IndexRepository.setCover` |
| `NotebookToolbar` | `[←] [pen] [eraser] [lasso]`; selected = `state_selected` bordered look; `sync(tool)` from `onToolChanged`; `releaseRender()` on every tap |
| `SelectionToolbar` / `ToolbarAnchor` | the floating selection toolbar + sub-toolbar (arc 4 / H2): core-drawn buttons from `ToolbarItem`s, show/hide/anchor rules, exclusion rects; the pure placement math (JVM-tested) |
| `SelectionActions` | `ToolbarAction` / `Contribution` / `ToolbarItem` + the pure `shapeOf` / `merge` (Delete first, provider order, INK / OBJECT / mixed filtering; JVM-tested) |
| `ObjectEditDialog` | the `EditSpec` → `AlertDialog` shell (Save / Cancel, IME rules) |
| `ObjectProviders` | the loaded provider set for one open (arc 4 / H4): refs by package, recognizer / Markdown refs, `contributions`, the resume `signature`; `load` (IO binds) |
| `ObjectActions` | the provider-facing flows (H4): `requires` guards, `RecognizerReadiness`, "Recognizing…" popup, `createFromInk` / `applyAction` / `describeEdit` / `applyEdit`, every failure dialog; hands results to the screen's `objectListener` |
| `ObjectRenderPass` | the cache fill (H4): objects grouped by provider → one `renderAll` bind per provider → decode → `Result`s the screen applies |

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
| `onStrokeCommitted(s)` | insert `stroke` row, `"order"` = `MAX("order")+1` among the page's strokes — live **and** soft-deleted (H5: monotonic across erase → restore, so a stroke un-deleted in place never ties) |
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

## Content objects (arc 4 / H1)

A page holds **objects** next to its strokes — core-owned `object` rows (`docs/data.md` §"Object
rows"): identity, provider identity, an opaque payload, bounds in page px, z-order. The screen keeps a
`liveObjects` mirror (`LinkedHashMap`, z-ordered) beside `liveStrokes`, rebuilt on every page load /
navigate together with the strokes (`objectStore.loadPage` → `paper.notifyContentChanged()`).

**Renderer.** `ObjectRenderer` is registered once with `paper.addContentRenderer` (layer
`BELOW_STROKES`). For each live object it draws the bitmap from `ObjectRenderCache` when one is cached
for exactly (payload, render width, dpi), else a **dashed 1 px inkBlack placeholder rect** at the
object's bounds — the look of an object whose provider is absent, disabled or failing (still
selectable, movable, deletable). It implements g-paper's live-drag pair (`draw(canvas, excluded)` +
`drawObject`) so a dragged object rides under the pen instead of ghosting, and `hitTargets()` returns
every live object's bounds — that is what makes objects lasso-selectable. `ObjectRenderCache` is
in-memory for the open notebook only (**bounded to the current page** — `retain(ids)` on every page load
recycles the rest, H5; cleared in `onDestroy`); the render pass that fills it is
§"Objects — actions, edit, render pass" (H4). A cache entry is valid for exactly (payload, dpi) and for
any render width it still fits — an image that stopped **more than 64 dp short** of the width it was
given counts as unconstrained and is reused after a move that doesn't push the object against the page's
right edge; anything closer to its width (an END-ellipsized line lands a little under it — the glyph that
didn't fit) is a miss whenever the width changes, so a truncated heading dragged back to room re-renders
whole (H5; the bare `width < maxWidth` test kept the ellipsis).

**Selection.** `onSelectionCreated` / `onSelectionMoved` / `onSelectionDismissed` keep
`currentSelection` (bounds follow moves). `onSelectionMoved` gains `contentIds`: `objectStore.move` +
the `liveObjects` update + `notifyContentChanged`, one undo `Moved(pageId, strokeIds, dx, dy,
objectIds)`. **`onSelectionTapped(x, y)`** (g-paper 0.1.1: a sub-threshold stylus or single-finger tap
inside the selection box; the finger variant escrowed + palm-gated like tap-to-dismiss; drags
unchanged) opens the tapped object's edit dialog (§"Edit dialog").

(The H1 debug ⋯ "Insert test object" item and its `DebugHooks` callback were removed in H5 with the
real provider in place; H1's "Delete selection" item had already become the toolbar Delete in H2.)

## Selection toolbar (arc 4 / H2)

A bordered floating row over the paper while a lasso selection is active — `SelectionToolbar`
(`view_selection_toolbar.xml`, included twice in `activity_notebook.xml`: the toolbar and its
**sub-toolbar**, both `GONE` until needed, both FrameLayout children of the root placed by margins).
Everything on it is core-drawn from descriptions (`docs/extensions.md` §"Selection-toolbar
contributions"): the core's **Delete** (Tabler `trash`, hint "Delete selection") first, then each
provider's `ToolbarAction`s — icon buttons (`toolbar_button_size` square, `bg_toolbar_button`) or, when
the icon name is not in `IconCatalog`, the label as bold text in the same square (sized relative to the
button so it reads as big as the icons on the tablet tier); long-press hint = the action's hint. Every
tap calls `paper.releaseRender()` first (chrome release), like the top bar.

**Contents** = `SelectionActions.merge(coreActions, contributions, shape)` (pure, JVM-tested):
`shapeOf(strokeCount, objectIdentities)` → `Ink` (strokes only) / `OneObject(pkg, typeId)` (exactly one
object, no strokes) / `Mixed` (anything else). `Ink` shows every provider's `INK` actions; `OneObject`
shows the `OBJECT` actions of the provider whose key (package) and type match; `Mixed` shows Delete
only. A parent action is filtered through its leaves and dropped when none survive. Contributions are
the object providers' (`ObjectProviders`, loaded once per open, refreshed on resume when the extension set
changed — §"Objects — actions, edit, render pass"); the H2 `FakeContributions` twin is no longer wired
(kept in `src/debug` until H5 removes it). Tapping a **parent**
toggles the sub-toolbar with its leaves (a second tap closes it; another parent replaces it); leaves
whose id the provider reports as *active* for the selected object are drawn `state_selected`. Tapping a
**leaf** closes the sub-toolbar and dispatches: Delete → `deleteSelection()` (`ObjectsDeleted`, one
undo step; `clearSelection` dismisses → the toolbar hides); anything else → `Listener.onAction(providerKey,
action)` → `ObjectActions.perform` (`createFromInk` for an ink selection, `applyAction` for one object).

**When it shows (`ToolbarAnchor`, pure, JVM-tested):** `onSelectionCreated` → `showSelectionToolbar`
**at once — deliberately not `whenPenIdle`** (H5): a lasso ends with the pen up and, on EMR panels,
hovering over the page, so `isPenActive` held the toolbar back until the pen left hover range;
g-paper is already presenting the selection-box frame at that moment, so a chrome frame breaks no
frame silence. The one async part (a single object's `activeActionIds`) is dropped if the selection
changed meanwhile. Anchored **8 dp below the drawn selection
box** (g-paper inflates the tight bounds by 12 px — `SELECTION_BOX_INFLATE_PX`, kept in step), centred
on it, **flipped above** when it would cross the bottom strip, and clamped horizontally into the root
and vertically between the top bar's bottom and the bottom strip's top. The sub-toolbar hangs off the
**toolbar** (below it; above it when the toolbar flipped; the other side when that would leave the
band), centred on it. `onSelectionDragStarted` hides it; `onSelectionMoved` re-shows it (at once) at
the new place; `onSelectionDismissed`, every page navigation and Delete hide it. **Its rects join the
exclusion rects** (`pushExclusions` appends `selectionToolbar.rects()`) and `overChrome`, so the stylus
never inks under it and a finger tap on it releases the render. Note that switching to the pen tool
dismisses the selection (g-paper: tool change → `onSelectionDismissed`), so "writing across the
toolbar" is only reachable with the lasso tool — a new outline started over the toolbar is excluded.

## Edit dialog (arc 4 / H2)

`onSelectionTapped(x, y)` (g-paper 0.1.1) with **exactly one selected object, no strokes, and the tap
inside the object's bounds** → `ObjectActions.editTapped` → the provider's `describeEdit` (through
`ObjectProviderClient`, `EditCaps` applied; null = not editable → nothing) → at once (the pen hovers after a tap — same rule as the toolbar) → `paper.releaseRender()` → **`ObjectEditDialog`** (`dialog_edit_object.xml`): a styled
`AlertDialog` titled from the spec with one bordered `AppCompatEditText` prefilled with the spec's text
(hint, `LengthFilter(maxChars)`, single-line with IME *Done* = Save, or multi-line 3–8 rows), **Save /
Cancel**. IME per `docs/design-system.md`: `SOFT_INPUT_STATE_VISIBLE | ADJUST_RESIZE` before `show()`;
Save and Cancel are real click listeners that hide the keyboard through the **field's** window token
while the dialog is alive (BOOX doesn't auto-dismiss it; the decor token is the wrong one); nothing
hides it earlier, so a Ratta hardware keyboard keeps typing. Save hands the raw text to
`applyEdit` → a new payload → `objectListener.onPayloadChanged` (`ObjectEdited`, re-render, re-select);
null (blank / unchanged) → nothing. Cancel does nothing. A tap with strokes selected, or outside the
object, does nothing.

## Objects — actions, edit, render pass (arc 4 / H4)

Three collaborators sit between the toolbar and the page: **`ObjectProviders`** (what is installed),
**`ObjectActions`** (talking to a provider + every failure dialog) and **`ObjectRenderPass`** (filling
the cache). `NotebookActivity` keeps only the page mutations (`objectListener`) — rows, undo, paper,
selection.

**Providers.** `ObjectProviders.load` (IO, launched right after `opened = true` so its binds never hold
the "Opening…" popup): every trusted `OBJECT_PROVIDER` → `describeTypes` + `describeActions` (a provider
failing either — a cold process past the 3 s bind — stays **known but undescribed**: its objects still
go to the render pass, it contributes nothing to the toolbar, and the load is marked *partial*, H5) →
`Contribution(providerKey = package, label, typeIds, actions)`; plus the one recognizer and the one
Markdown renderer the proxies would lend (null when none). The toolbar shows Delete only until they
arrive; an active selection is re-shown when they do. `onResume` compares the cheap discovery
`signature` (component list of the three points; a partial load carries a marker no discovery equals) and
reloads only on a change (an extension enabled / disabled / installed while the screen was away, or a
partial load to retry) — then clears the "failed" set and re-runs the render pass.

**Stroke order is writing order — always.** `liveStrokes` is a `LinkedHashMap` (loaded in `"order"`,
commits append) and every place that hands strokes on — the INK action, Delete, the eraser's undo capture
— takes them in *that* order, never in `Selection.strokeIds`' set order. An online recognizer reads
strokes as a sequence — a hash-ordered "Meeting Notes" came back as four characters on all three devices
(H4, fixed the same day). **`StrokeStore.restore` un-deletes rows in place** (H5): a soft-deleted row keeps
its `"order"`, so an undone erase / Delete / heading create puts the strokes back at their writing
position — until H5 it re-numbered them to `MAX+1`, which persisted a scramble on every undo of a
mid-line run (only a stroke with no row at all is upserted after the last). Ink undone before these
fixes may still be scrambled in its rows (rewrite it).

**INK leaf** (`ObjectActions.perform`, strokes-only selection): guards in this order — `Requires.RECOGNIZER`
with no recognizer installed → "This action needs a handwriting recognizer extension…"; `Requires.MARKDOWN`
with no Markdown renderer → "…needs the NSE · Markdown extension"; `MAX_OBJECTS_PER_PAGE` → "page full".
Then `RecognizerReadiness.ensureReady` (READY → at once; NEEDS_DOWNLOAD → consent → download → continue
with no second tap; the same flow as the debug Recognize page, titled "Couldn't apply the action") →
the **"Recognizing…" popup** (the M1 one, no buttons, up until the object is on the page or a dialog says
why not) → `InkPayload.fromStrokes` → `ObjectProviderClient.createFromInk(leaf, ink, bounds.w, bounds.h)`
(15 s, recognizer proxy inside) → **null** → "Couldn't read the handwriting — try writing larger or clearer"
and the ink is untouched · a `CreatedObject` → `objectListener.onCreated` under `pageOps`: the strokes must
still be live (erased meanwhile, or the page turned during the consent dialog → dropped) → `PageObject`
at the lasso box's top-left with the box's size, identity `objectIdentity(pkg, typeId)`, order max+1 →
`objectStore.create` + `store.erase(strokeIds)` → **one `ObjectCreated(page, obj, strokes)`** →
`paper.removeStrokes` (dismisses the selection; the placeholder draws at the box) → **inline render
pass** for the new object (sizes it to the image; persisted) → `selectObject`: `paper.setSelection(∅,
{id}, bounds)` (host-initiated — no `onSelectionCreated` echo, so `currentSelection` / `selectionActive`
are set here) → toolbar with the level marked active.

**OBJECT leaf** (one selected object of the provider's type): guards `Requires.MARKDOWN` only (its result
must render; the recognizer is not involved) → `applyAction(leaf, typeId, payload)` (2 s) → null →
nothing · a payload → `onPayloadChanged`: `updatePayloadAndBounds` → inline render pass → **one
`ObjectEdited(page, before-re-anchored-at-the-final-x/y, after-with-final-bounds)`** (a drag of the
still-selected object during the round-trip is its own `Moved`; an edit records payload + size only — H5)
→ re-select (new bounds, active ids refreshed — `activeIdsCache` per object id + payload, filled on success
only, spares a bind per re-selection). `ObjectCreated` is likewise recorded **after** the inline render,
with the rendered object, so a redo restores the sized row and not the lasso box (H5).

**Edit** (`onSelectionTapped` on the one selected object): no guard → `describeEdit` → dialog → Save →
`applyEdit` → the same `onPayloadChanged` path (with Markdown absent the payload still changes; the box
re-sizes only when Markdown returns).

**Render pass.** `ObjectRenderPass.render(objects, providers, pageWidth, dpi)` (IO): objects grouped by
provider identity → **one `ObjectProviderClient.renderAll` per provider** (one bind, one Markdown proxy,
N renders; a single failed render is null, a `CapabilityRequiredException` ends the batch) → each verified
WEBP decoded (`Bitmaps.decodeBounded`, edge cap) → `Result(id, payload, maxWidth, dpi, bitmap?)`. The
screen applies on Main (`applyRenderResults`): skip if the object is gone or its payload moved on; null →
`renderFailed` (no retry until the next page load, a provider reload, or an edit of that object); else
`renderCache.put` and, when the image size differs, `width/height` from the image (persisted; anchored
top-left) → **one `notifyContentChanged` through `whenPenIdle`**. Two entry points: **inline** (`renderNow`,
awaited under `pageOps` by create / apply / edit — so a slow provider queues page flips / undo behind
it for at most bind 3 s + render 10 s; known, accepted in H5) and **background** (`scheduleRenderPass` — page load,
navigate, undo reload, provider reload, and after a selection move with objects (an object pushed against
the right edge re-ellipsizes); never holds `pageOps`; a trigger during a pass queues exactly one more).
Render width = `ObjectRenderer.renderWidth(pageWidth, o)` = page width − x, ≥ 1, ≤ `MAX_IMAGE_EDGE_PX` —
the one function the cache lookup and the pass both key on.

**Failure table (every one a core dialog; nothing changes on the page):**

| Situation | Dialog |
|---|---|
| INK action needs a recognizer, none installed (checked first) | "Couldn't apply the action" / `objects_needs_recognizer` |
| INK / OBJECT action needs Markdown, none installed | … / `objects_needs_markdown` |
| Page holds `MAX_OBJECTS_PER_PAGE` objects | … / `objects_page_full` |
| Recognizer flow: offline / download failed / unavailable / cancelled | the `RecognizerReadiness` dialogs; nothing created |
| `createFromInk` returned null (nothing usable recognized) | "Couldn't read the handwriting" / `objects_unreadable_body` (ink untouched) |
| Ink over the caps | … / `recognize_too_dense` |
| READY, then `RECOGNIZER_NOT_READY` from the proxied call | … / `recognize_still_downloading` |
| Provider threw `RECOGNIZER_REQUIRED` / `MARKDOWN_REQUIRED` (typed) | the matching "needs …" text |
| Any other provider failure / timeout | … / `objects_provider_failed` ("The <label> extension didn't respond") |
| Provider absent / disabled at render time | no dialog — dashed placeholder, still selectable / movable / deletable |

**Undo.** `ObjectCreated` (undo: ink back + object gone), `ObjectEdited` (payload + bounds either way),
`ObjectsDeleted`, `Moved` with objects — replay stays store → drain → reload the page, which reruns the
render pass from cache (an undone level is a re-render because the cache holds one image per object).

**Page delete / undo.** `SoilDao.liveChildIds(pageId)` (strokes **and** objects) is what
`deleteCurrent` soft-deletes and `Structural.childIds` carries; `reconcile` restores / deletes the whole
set. Objects reload with strokes on every `refreshToPage`.

## Frame-silence rule

No app frame is presented while `paper.isPenActive` — the strip text only changes through
`whenPenIdle {}` (re-polls every `PEN_ACTIVE_TAIL_MS`). Nothing else on the screen repaints
during writing. **Two deliberate exceptions (H5):** the selection toolbar on `onSelectionCreated` /
`onSelectionMoved` and the object edit dialog on `onSelectionTapped` show at once — both fire with the
pen up (and typically hovering), after g-paper has itself presented the selection frame; gating them on
`isPenActive` made them wait for the pen to leave hover range.

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
  "Delete page" → `AlertDialog` "Delete this page? / Undo (two-finger double-tap) brings it back until you close the notebook." [Delete] [Cancel] (wording fixed in arc 4 / H1 — the Phase-4 text still said the ink could not be recovered, which stopped being true the moment page delete joined undo).

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
- `deleteCurrent()` — `writer.drain()`, then soft-delete the page + its strokes and objects (`liveChildIds` — read only after every queued create / erase has landed, H5), renumber, land on the previous page
  (`PageMath.indexAfterDelete`). **Deleting the only page creates a fresh blank in its place** so a
  notebook always has ≥ 1 page. Returns a `Structural` snapshot.
- `reconcile(targetAlive, restoreChildIds, deleteChildIds, currentId)` — the undo/redo primitive:
  makes the live page set exactly `targetAlive` (diff via `PageMath.toRestore` / `toDelete`, using the
  DAO's `restore` to un-soft-delete), toggles the given page content (strokes + objects), renumbers,
  lands on `currentId`.

`SoilDao` gained `restore(ids, at)` (un-soft-delete), `liveStrokeIds(pageId)` and — arc 4 —
`liveChildIds(pageId)` (strokes + objects; cheap, no blobs).
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
| `Moved(pageId, ids, dx, dy, objectIds)` | `store.move(-dx,-dy)` + `objectStore.move(-dx,-dy)` | both `move(dx,dy)` |
| `Page(Structural)` | `reconcile(before, childIds→restore, …, beforeCurrentId)` | `reconcile(after, …, childIds→delete, afterCurrentId)` |
| `ObjectCreated(pageId, obj, removedStrokes)` (arc 4) | `objectStore.remove([obj])` + `store.restore(removedStrokes)` | `objectStore.restore([obj])` + `store.remove(removedStrokes)` |
| `ObjectsDeleted(pageId, strokes, objects)` (arc 4) | restore both | remove both |
| `ObjectEdited(pageId, before, after)` (arc 4) | `updatePayloadAndBounds(before)` | `updatePayloadAndBounds(after)` |

`StrokeStore` gained `remove(ids)` (= soft delete) and `restore(pageId, strokes)` (re-add as live
rows). `NotebookActivity` keeps `liveStrokes` (a `Map<id,Stroke>` of the visible page) so an erase can
capture the full `Stroke` objects the undo needs; it is rebuilt on every page load/navigate — as is
`liveObjects` (arc 4). Every replay drains the shared `SoilWriter` once, then reloads the page (strokes +
objects) — `revert` / `reapply` are `when` tables over the actions above.

All page/undo operations run under a `Mutex` (`pageOps`) so overlapping gestures can't corrupt the
page list, and are dropped once `closing`.
