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
| `core/RecognizingOverlay` (N2) | the "Recognizing…" box during a convert — `OpeningOverlay`'s smaller, dialog-free sibling. Grew a message parameter in arc 21 / W3 (default text unchanged) so the lasso's silent heading→tag can reuse it, showing "Tagging…" instead |
| `notebook/InkPayload` (N2) | `Stroke` (g-paper) → `InkStroke` (extension-api) in writing order — the one place a page's ink is reduced to bare geometry for the recognizer |
| `CoverSnapshot` | `paper.renderToBitmap()` → ≤ 512 px long edge → WEBP q100 → `IndexRepository.setCover`; headings ride along for free (`HeadingRenderer` is part of the same committed-layer render) |
| `NotebookToolbar` | `[←] [contents] [pen] [eraser] [lasso] … [document] [recents] [scratch pad]` — arming only; owns the fixed tool values (the Contents, Document and Recents buttons belong to their own flows, not to it). Back goes through `backPressed()`, never straight to `close()` (K4 — both Backs walk the link trail in a via-link notebook). O1: a second tap on the **armed lasso** calls back to the screen (the clipboard popup), and `showClipboardLoaded()` swaps that button's icon. Arc 29 / LE2: a second tap on the **armed eraser** calls back as `onEraserReTap` (the screen opens `EraserBar`); `arm(tool)` lets the bar's pick drive the button from the host side; `sync` selects the eraser button under either eraser and swaps its glyph to `ic_lasso_eraser` only on a change of kind |
| `EraserBar` (arc 29 / LE2–LE3, `:sn-screen`) | the eraser button's own floating sub-bar — **Point** (`Tool.ERASER`) · **Lasso** (`Tool.LASSO_ERASER`) — hung under it via `AnchoredBar`. One implementation shared by all four paper surfaces (the notebook and the sticky editor at LE2, the scratch pad and the calendar at LE3 through `:ext-ink`'s `InkScreenActivity`) so a fix to the sub-bar's placement or dismissal never becomes the `RattaNotebookView` sibling-copy trap one bar at a time. Remembers nothing between openings; a pick pen-gates the release, arms the tool and calls back so the screen can hide the bar and sync its own toolbar |
| `SelectionToolbar` | the floating bar over a live lasso selection: Delete (always) + H, plus (K1) **Link** / **Edit** / **Unlink** by `SelectionMode` (five modes since K1), plus (O1) **Copy** / **Cut** in every mode, plus (N2) the H1–H6 level sub-toolbar it can open, plus (arc 21 / W3) **Tag** — see [Tag](#tag-arc-21) below |
| `LassoPopup` (O1) | the small bordered bar under the **armed** lasso button: **Paste** + **Clear** for the object clipboard. Opens only while the clipboard holds objects; the screen owns every dismissal. Placement is [`AnchoredBar`](#anchoredbar) since arc 21 / W2, shared with the tag button's own popup |
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
| `DocumentEditorEntry` / `DocumentSeedFlow` / `DocumentHostHooks` (arc 19 / M3–M8) | what `btnDocument` opens: the fifth extension point's client, the seed-before-launch flow, and the host-side callback binder every read/write from the editor comes back through. Full detail — data model, seeding, flips, the notebook document, text documents — is [`docs/document.md`](document.md); see [Document](#document-arc-19) below for this screen's own slice |
| `AnchoredBar` (arc 21 / W2, moved to `:sn-screen` arc 29 / LE2) | the placement mechanics for a small bordered bar hung under a top-bar button — measure-before-place, the rects, the button recipe — pulled out of the arc-8 `LassoPopup` so `TagsPopup` did not need a second copy of the same bar. Moved into `:sn-screen` at LE2 so `EraserBar` could share it across all four paper surfaces; the three `:app` callers (`LassoPopup`, `TagsPopup`, `InsertBar`) were repointed, untouched otherwise |
| `TagsPopup` (arc 21 / W2) | the `ic_tag` button's own bar, hung under it via `AnchoredBar`: **Tag notebook** · **Tag page** · **Manage** — the notebook's three tag doors. Gated on `canvasShown`; see [Tags](#tags-arc-21) below |
| `TagTargets` (arc 21 / W2) | pure: a page's 1-based number in the live page list (or null — a page briefly missing from it names nothing), and the pages a MANAGE showing may carry, capped at `TagShowing.MAX_PAGES`. JVM-tested |
| `TagSelection` (arc 21 / W3) | pure: which selections offer the lasso's Tag button and which of the two flows a tap takes (`TagFlow.SILENT` / `RECOGNIZE` / `NONE`), plus the prefill cut for an over-cap heading title. JVM-tested; see [Tag](#tag-arc-21) below |
| `TagManagerEntry` (arc 21 / W1) | the host's one door for every tag surface — library sheet, notebook toolbar, lasso — the `ScratchPadEntry` shape: availability, the busy latch, the "Opening…" wait, a held showing's bind life, and (W3) the silent `assign` call with its own "Tagging…" wait |
| **Text objects (arc 28)** | `TextRows`/`TextStore` (rows + store on the shared `SoilWriter`) · `TextRenderer` (draw + the one `measure()` creation/edit/`remeasureForDevice` share) · `TextEditDialog` (plain Markdown box, `HeadingEditDialog`'s shape) · `TextLines` (pure: `normalize` for recognized ink, `typed` for the dialog) · `TextPlacement` (pure centring) · `TextFlow`/`TextFlow.Host` (insert / convert / edit, out of the activity) — full detail in [`docs/objects.md`](objects.md) |
| **Shape objects (arc 28)** | `ShapeRows`/`ShapeStore` · `ShapeGeometry` (pure `outline`/`tightBounds`/`aabb`) · `ShapeRenderer` · `ShapeDefaults` (pure insert sizes) · `ShapeBox` (pure `PageShape` ⇄ `OrientedBox`) · `ShapeTransformBar` + `ShapeTransformLabels` (the lasso bar's Transform, the aspect-lock wording) · `ShapeFlow`/`ShapeFlow.Host` (insert + the whole g-paper transform lifecycle) — [`docs/objects.md`](objects.md) |
| **Sticky notes (arc 28)** | `StickyRows`/`StickyStore` (icon row; children in the note's local space) · `StickyRenderer` (`ic_sticker_2`, white-filled interior) · `StickyDefaults` · `StickyFlow`/`StickyFlow.Host` (insert → editor at once, finger-tap reopen) · `StickyEditorActivity` (the core second paper surface) · `StickyEditorTransfer` (the process-local singleton the editor writes host-side rows through) · `StickyInk`/`StickyClip` (pure editor ink + clipboard rules) — [`docs/objects.md`](objects.md) |
| `InsertBar` (arc 28 / H1, D4) | the `btnInsert` sub-bar: Sticky · Text · Rectangle · Ellipse · Triangle · Line · Arrow · Star, placed by `AnchoredBar`; a command, not a tool — lands the new object selected and remembers nothing between openings |
| `SelectionModes` (arc 28 / H2, D5) | pure `classify()` — the `when` that decides `SelectionMode.TEXT`/`SHAPE`/`STICKY`/`MIXED` from a selection's content, pulled out of the activity so the table is testable |
| `PageObjects` (arc 28 / H1) | the three renderers (`textRenderer`/`shapeRenderer`/`stickyRenderer`) + their working copies, held together as one small view-model beside the activity; draw order and the repaint are the caller's (D8) |
| `DoubleTapToggleRule` (arc 33 / F1) | pure: whether a finger double-tap toggles the chrome, from a two-deep hit history fed by `onFingerTap` (`tapped(hit)`) and consumed by `onFingerDoubleTap` (`shouldToggle()`) — see [Gestures](#gestures) below |
| `data/prefs/ChromePrefs` (arc 33 / F1) | `sn_chrome` / `hidden`, `SnapPrefs`'s shape — the one global persisted chrome-hidden flag shared by all four paper surfaces |

## Layout (`activity_notebook.xml`)

`FrameLayout` root → `paperContainer` (the `PaperView`, added in code — `GPaper.create` needs a
Context) → `topBar` overlay (flush at the top edge — the top guard is 0 on Ratta; 1dp inkBlack
bottom border) → `bottomStrip` overlay (1dp top border; **one `@dimen/toolbar_bar_thickness`
row like every other chrome bar** — the notebook's name at the left, ellipsized inside its
half, and the pager `[‹] n / N [›]` centred on the SCREEN by an equal-weight spacer, the
calendar's and the pad's bottom bar exactly; the arrows flip **within** the notebook only —
the swipe past the last page still grows it, a button never does — and are silent no-ops at
either bound, never disabled) → `selectionToolbar`
(floating, `GONE`, placed by margins) → `selectionSubToolbar` (N2 — its own floating `GONE` bar,
placed by `SelectionAnchor.placeSub` off the main bar when H is tapped) → `tagsPopup` (arc 21 / W2 —
the `ic_tag` button's own floating `GONE` bar, placed by `AnchoredBar` under `btnTags`) →
`openingOverlay` (an
`<include>` of `overlay_opening.xml`, **last child so it is topmost**, and `VISIBLE` from the first
frame). Immersive: system bars hidden, transient by swipe. Portrait-locked.

The `topBarRow` is left-packed — Back, then Contents / Pen / Eraser / Lasso, all butted together
(the same spacing the scratch pad's row uses) — with a **weighted spacer** after the Lasso holding
the row's free space, so `btnDocument` (arc 19), `btnTags` (arc 21 / W2), `btnRecents` (T1),
`btnCalendar` and `btnScratchPad` (arc 23 / Y3, reordered at Y4) sit flush at the right edge, in
that order — Document immediately before Tags, Tags immediately before Recents (the user's
placement call, arc 21 / W2: next to Document, before Recents), and Calendar immediately before the
Scratch Pad button, for the pad's own reason: the other paper surface this page's ink can be sent
to. **Since Y4 the Scratch Pad is the LAST button on every bar** — the user's placement call — with
Calendar moved to sit just before it rather than after; everything to their left keeps its position
whatever the screen width. `btnTags` is **GONE without a trusted tag manager installed**
(`TagManagerEntry.refresh()`, re-run from `onResume` like every other extension-backed control),
never disabled; `btnCalendar` is the same shape over `CalendarEntry` — GONE without a trusted
calendar, refreshed on every `onResume`, and a tap `releaseForHandoff()`s the paper immediately
before launch, the pad's own rule for handing the EPD pipeline over first.

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

**The top bar and the bottom strip are floating overlays over full-bleed paper, and a single-finger
double-tap on bare paper hides or shows both together** (arc 33 / F1). The root has always been a
`FrameLayout` with `paperContainer` first and the bars as later siblings — this arc did not
restructure the notebook's layout, only put a gesture behind the bars it already had. `ChromeToggle`
(`:sn-screen`, shared with the sticky editor, the scratch pad and the calendar) owns the one flip
order: `paper.releaseRender()` (skipped on the `onCreate` call, since nothing is on the glass yet) →
hiding only: `beforeHide()` — the lasso popup, the tags popup, the Insert bar and the eraser sub-bar
all come down, because the button they hang from is about to go — → every bar `View.GONE` /
`View.VISIBLE`, **never `View.INVISIBLE`** (an attached Ratta paper view keeps the pen claimed
whatever a sibling's visibility, so an `INVISIBLE` bar would both keep its rect *and* still block
the pen under it) → `root.doOnNextLayout { pushExclusions() }`, one binder call per flip rather than
per event. The contextual floating bars — the selection toolbar and its H1–H6 sub-row, the lasso
popup it anchors, the transform bar — **keep working while the chrome is hidden**: a lasso is a
deliberate act with nothing else to answer it, so nothing about hiding the top bar and bottom strip
touches them. The eraser sub-bar, the tags popup and the Insert bar hang from a bar button, so they
come down with the bar and cannot be opened again until the chrome is shown.

A **shown** bar covers whatever ink sits beneath it (its background is opaque `paperWhite`) and the
pen refuses to ink under it — `pushExclusions()`'s existing exclusion rect — while a page written
under where the bar sits, with the chrome hidden, shows and inks freely; nothing is redrawn, moved,
or re-templated by the flip. **Trap 1:** a `GONE` view keeps its last measured width and height, so
`PaperToolbar.rectOf` (which the notebook's own `rectOf` now delegates to) refuses any non-`VISIBLE`
view before it ever reads a size — without that check a hidden bar would keep excluding ink and
swallowing gestures exactly where it used to sit. The snap margin (`paper.snapMarginPx`, arc 9) is
**deliberately not visibility-aware** — it keeps reading `topBar.height` whether the bar is shown or
`GONE`, because a `GONE` bar's last laid-out height *is* the "one toolbar" margin an object must
clear whether the chrome is up or down when it was snapped.

`chromeBand()` is now pure: `ChromeBand.of(root.height, topBar.asBar(bottom), bottomStrip.asBar(top))`
(`:sn-screen`) — a hidden bar contributes the root's own edge instead of withholding the band, which
is what the pre-arc `chromeBand()` did whenever either bar's height read 0 (**trap 2**: exactly what a
`GONE` bar reports, so every floating bar built on the band would have silently refused to show at
all while the chrome was hidden).

**Persistence.** `ChromePrefs` (`data/prefs/`, `sn_chrome` / `hidden`, `SnapPrefs`'s shape exactly)
holds **one global boolean**, default shown, shared by all four paper surfaces — "give me the whole
page" is a way of working, not a property of a page or a notebook. It is device-local: never backed
up, never restored, and not in the index or any `.soil`.

**Arc 36 ("Corner") replaces the bare-paper state.** While the bars are `GONE` a single floating
**corner button** sits at `top|end` (8dp margin, `shape_dialog_bordered`, the toolbar-button dimen)
wearing the armed tool's glyph — `CollapsedTools.iconFor(tool, clipboardLoaded)`, the same
clipboard-loaded lasso mark the bar's own lasso button wears (arc 8), and `Tool.NONE` wearing the
pen (a surface that captures nothing still names what a tap brings back). It is declared in
`activity_notebook.xml` before the opening overlay, `GONE` while the bars show and flipped to
`VISIBLE` by `ChromeToggle`'s new `whileHidden` list in the same pass that hides the bars — never up
beside a bar, never absent over bare paper. It caches nothing: `CollapsedChrome.sync()` repaints
from `paper.tool`, and is wired once into `NotebookToolbar`'s `onSynced` — the one funnel every tool
change passes through (a bar tap, `arm`, `armLasso`, every by-hand sync, `onToolChanged`) — so no
path, echoed by g-paper or host-set, can leave it wearing a tool that is no longer armed (the C3
review's finding: two by-hand arms had been left out of a per-site fan-out).

A tap on the corner button opens a **mini toolbar** (`CollapsedChrome`, `:sn-screen`) hung under it
by `AnchoredBar`: the four tools in `CollapsedTools.ORDER` (Pen · Point eraser · Lasso eraser ·
Lasso), the armed one bordered, then the notebook's own commands — Insert — then a `…` button
opening a second **overflow row**: Back · Contents · Document · Tags · Recents · Calendar · Scratch
Pad, mirroring the bar buttons of the same name. **A small overflow is not an overflow**
(`CollapsedTools.overflowInline`, `INLINE_MAX` = 2, the user's call after the C2 walks): the sticky
editor's Back and the pad's Back · Send sit on the mini toolbar itself with no `…` at all, because
opening a row for one or two buttons is two taps for one; the notebook and the calendar have seven
and eight doors respectively and keep the `…`.

Every mirrored entry (`CollapsedChrome.Entry.mirroring(iconRes, button, onTap)` — the hint is the
bar button's own content description) copies its bar
button's state at the moment the row opens, never cached: visibility (a door `GONE` on the bar —
Tags without a trusted tag manager, Calendar/Scratch Pad without their extensions — is absent here
too), the selected look, and, for an image button, its current glyph. A tap on a plain mirrored
entry closes both rows and `performClick()`s the bar's own button — one handler, never a second copy
of it. Insert and Tags are the two entries with their own `onTap`: each hangs its sub-bar under
**its own mini-toolbar / overflow button**, via `AnchoredBar.show(anchor)`'s new optional anchor
parameter, and leaves the row open beneath it. **This is load-bearing, not a style choice**: the
bar's own `btnInsert` / `btnTags` sit inside a `GONE` top bar while the chrome is collapsed, and a
`GONE` view keeps its last measured position — hanging a sub-bar off it would land the bar exactly
where the top bar used to be, not under the corner button's row. Picking a tool instead **arms** it
(`CollapsedChrome.pick`) — the assignment is skipped when the tool is already armed, so a re-tap
changes nothing on the surface but still closes the rows, because a tap on a tool is an answer — and
calls back into `onArmed`, which is where the notebook's own `toolbar.arm(it)` runs (again because
the pick is never echoed as `onToolChanged`).

**Dismissal.** Any contact that starts outside the corner button, both rows, or a sub-bar hung off
them (`CollapsedChrome.dismissOnContact`, the `keep` lambda checking the Insert bar and the tags
popup) takes both rows down — a bare pen tap, a stroke, a finger gesture, or any bar button. The
corner button is deliberately excluded from that rule: closing it there and letting its own
`setOnClickListener` reopen it in the same contact is the lasso popup's own close-then-reopen trap.
The rows also go down at every place the notebook's other floating bars do: a page swap, the
hide → show flip (`ChromeToggle`'s new `beforeShow` hook — `dismissCollapsed()` — because a shown
bar button is about to reappear and its would-be sub-bar is stale), a tool pick, and every one of the
overflow's own actions (Insert's landed shape, a Tags pick, Contents/Document/Recents/Calendar/
Scratch Pad's own launches).

**Chrome, not paper.** The corner button and both rows join every existing list: `pushExclusions()`
(so the pen refuses to ink under them), `overChrome()` (so a finger landing on them is not a page
gesture), and the outside-contact dismissals of the tags popup and the Insert bar, which now also
leave a contact inside the collapsed chrome alone — a tap on the mini toolbar's own Insert / Tags
button toggles its sub-bar rather than being read as an outside tap by the bar-level dismissal that
would otherwise fire first.

**Render release** follows the existing split: opening a row is one chrome frame at a deliberate tap
with the pen still hovering — an ungated `paper.releaseRender()`, the Insert bar's own rule — while
a tool pick goes through the eraser sub-bar's pen-gated `PenIdle.releaseRenderIfIdle`. Nothing about
the collapsed chrome is a new frame-silence exception: it rides the chrome toggle's own exception 6
(§ Frame-silence rule) exactly, because opening or closing a row is answered by the same deliberate
act — a chrome tap or a gesture that already passed `PageGestures.gateOpen()` — that every other
floating bar answers.

**Nothing new is persisted.** The one boolean `ChromePrefs.hidden` is unchanged in meaning —
"hidden" now means "collapsed to the corner" rather than "bare paper" — and whether a row is open is
not state: it is never written, never restored, and starts closed on every open of the screen.

**Trap 3:** a sub-bar hung under a `GONE`-parented anchor lands where the bar used to be, not where
its own button now sits — the reason `AnchoredBar.show` takes an optional `anchor` and the mini
toolbar's Insert / Tags entries always pass their own button, never the top bar's.

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

### Insert (arc 28)

`btnInsert` (`ic_plus`) sits directly after the lasso in the top bar's left-packed row. A tap opens
`InsertBar`, a floating eight-button sub-bar (`AnchoredBar`'s placement — the arc-8 lasso popup's
and the arc-21 tag popup's shape) holding **Sticky · Text · Rectangle · Ellipse · Triangle · Line ·
Arrow · Star**, left to right, always — one row on the Nomad at `toolbar_button_size` (D4's two-row
wrap was measured for but never needed). **Insert is a command, not a tool**: a pick places the
object at the page centre and lands it selected under the lasso (`armLassoForLanding()` runs first —
see Selection below); the armed tool is exactly what it was before the tap, and nothing about the
bar is remembered between openings. Dismisses on a pick, another bar's button, a tool change, a page
swap, or an outside touch, and unions its bounds into the exclusion rects and `overChrome` while up,
like every other floating bar. Full data-model detail for what each button creates is
[`docs/objects.md`](objects.md).

### The lasso eraser (arc 29 / Loop)

A third erase path (after the point eraser and scribble erase), but **not a fourth toolbar button** — with every extension installed the
notebook's top bar already holds eleven 62 dp buttons on the Nomad's 749 dp (682 dp + 8 dp
padding); a twelfth falls off the edge. Instead the eraser is reached under **either** of two
kinds it can be armed to, and a **second tap on the already-armed eraser** opens `EraserBar`
(Collaborators above) — **Point** (`Tool.ERASER`, the 15 px whole-stroke eraser) · **Lasso**
(`Tool.LASSO_ERASER`, g-paper 0.1.28). Picking Lasso arms the new
tool and swaps the eraser button's own glyph to `ic_lasso_eraser` for as long as it stays armed
(the O1 `showClipboardLoaded` precedent — a standing state of the surface belongs on the button,
not a toast that is gone before the next stroke); picking Point (or a plain eraser tap from any
other tool) always arms `Tool.ERASER` — **the sub-bar remembers nothing**, and the lasso eraser is
reachable only through the re-tap.

- **The hit rule is the lasso's own, unchanged (D4).** A stroke goes if any point lies inside the
  drawn outline; a heading / link / text / shape / sticky goes **whole** if the outline touches its
  box — exactly what a lasso *selects* today, so select-then-Delete and a lasso-erase always agree
  about what a loop holds. The eraser never reaches inside a sticky from the page.
- **There is no selection in this tool.** No box, no drag, no `onSelection*` callback; the barrel
  button / eraser end still point-erases, exactly as it does under `Tool.LASSO`; the smart-lasso
  and scribble-erase recognizers stay off (they only ever evaluate under `Tool.PEN`); and a
  tap-sized contact reports nothing at all — no callback, no undo entry, not even the paste-here
  hook (`onPaperTapped`, which belongs to `Tool.LASSO` only). A loop that takes nothing is nothing.
- **One gesture, one undo entry** — `PaperListener.onLassoErased(strokeIds, contentIds)` reports
  both lists once, on the `onScribbleErased` shape (a forwarding default keeps an older host
  compiling); see the listener table and [Undo / redo](#undo--redo) below for the mirror.
- **The sub-bar's own lifecycle** is the Insert bar's: it hangs under the eraser button via
  `AnchoredBar`, and closes on a pick, any tool tap, another floating bar taking its place
  (newest-tap-wins), a page swap, or an outside contact — with the eraser button itself excluded,
  since its own re-tap is what toggles the bar and a dismissal there would reopen what it just
  closed. Its rect unions into the exclusion rects and `overChrome` while it is up, like every
  other floating bar, and its show/hide is **not** pen-idle-gated — a re-tap and a pick are both
  deliberate acts (see [Frame-silence rule](#frame-silence-rule)).
- On the Supernote the outline paints the firmware's `SupernoteInk.Pen.CROSS` x-trail (not the
  lasso's own dash trail), retracted at lift by the same trace ladder as every other hardware
  trail — a g-paper 0.1.28 detail, not a host one.

The sticky editor, the scratch pad and the calendar carry the identical re-tap and sub-bar — see
[Objects — text, shapes and stickies](#objects--text-shapes-and-stickies-arc-28) below for the
sticky editor's own mirror, and [`docs/scratchpad.md`](scratchpad.md) /
[`docs/calendar.md`](calendar.md) for the other two surfaces.

## Open

`IndexGuard.ready` → extras (`EXTRA_NOTEBOOK_ID`, `EXTRA_NOTEBOOK_NAME`; K4 adds
`EXTRA_VIA_LINK` + `EXTRA_INITIAL_PAGE_ID` — the initial page is **consumed once**, read only when
`savedInstanceState == null`, so a recreated via-link notebook lands on its remembered page rather
than re-following the redelivered Intent; [`docs/links.md`](links.md)). Arc 32 / RS2 adds a second
consume-once extra beside it, `EXTRA_RESUME_ABOVE` — an `ArrayList<String>` of surface names, also
read only on a fresh create and ignored on a task rebuild, host-internal (the library never keeps a
copy). `stack.attach(SurfaceEntry(stackToken, NOTEBOOK, id, viaLink))` replaces the old
`BrowseState.lastOpenNotebookId = id` (+ `lastOpenViaLink`, K4) write — `stackToken` is a `UUID`
minted per instance and saved under `KEY_STACK_TOKEN` in `onSaveInstanceState`, so a same-process
recreate refreshes this screen's entry in place instead of stacking a second one
([`docs/library.md`](library.md) § Launch restore has the stack itself). Then
`RecentsPrefs.record(id)` → `repo.alive(id)` (else problem
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

### Encryption (arc 26)

Every open resolves the key first: `keyFor(alive)` reads the index row's scope, and for `GLOBAL`
that's `SoilDatabase.resolve` handing back the cached device key; a `NOTEBOOK`-scope notebook
prompts **every time** it is opened, no exceptions — `NotebookPassphrasePrompt.ask` (bucket = the
notebook id), with `takeParked` tried first for the one hand-off a create or scope change just
parked (`PassphraseCache`, `TTL_MS` = 60 s; nothing else in the app is allowed to spend it, so a
picker drill or an Export prompt earlier can never make this open silently). Cancelling the prompt
leaves quietly — no dialog, the last-open pointer cleared — same as any other declined open.
`openSession()` then runs `SoilDatabase.open(…, resolved)` whole; a **RETRY** from the recovery
offer below just re-runs that same call. A key failure (never a schema error — see
`crypto/KeyFailure`) offers `NotebookRecovery` **once per launch** (`EXTRA_RECOVERY_ATTEMPTED` on
the Intent): "Can't open <name>" → Try a passphrase, silently against the cached global and a
mid-rotation marker first, then the one prompt.

Two places downstream read the scope back rather than assume `GLOBAL`: `close()`'s cover capture
(`captureCover`, both the `onStop` and the close path) is skipped outright for `NOTEBOOK` — a
locked notebook's card is a lock glyph, never a stale or missing cover — and `refreshMeta` sources
`keyScope` from the index row itself, never from the previous meta row, so a refresh can never
launder a NOTEBOOK-scope file back into describing itself as globally keyed. Full model, the
resolver, the doors and the failure table: [`docs/encryption.md`](encryption.md).

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
| `onLassoErased(strokeIds, contentIds)` (arc 29 / LE2) | `Tool.LASSO_ERASER`'s one report: a drawn loop took ink **and** content in one gesture, on the lasso's own hit rule. The body is `onScribbleErased`'s exactly — `store.erase` for the strokes, the same `removeContent` for the rest, recorded as one `Action.LassoErased` (`eraseEntry(kind = EraseKind.LASSO)` — the boolean `eraseEntry` used for "was this a scribble" became a three-way `EraseKind { ERASER, SCRIBBLE, LASSO }`). No repaint beyond what `removeContent` already does — the engine re-records the strokes itself |
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

## Selection (R5, context toolbar P1, headings N2, tag arc 21)

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
| Tap the bar's **Tag** (arc 21) | the silent flow on a lone heading, or the recognize flow on ink alone — non-destructive, offered on no other selection ([Tag](#tag-arc-21) below) |
| Tap outside / tool change / any data-in call / page swap | `onSelectionDismissed` → bar(s) hidden, mirror cleared (unless a converted heading's selection is waiting to take its place — see Headings below) |

### The selection toolbar

A bordered row floating over the paper: (A1) **Snap** and (O1) **Copy** / **Cut** first — all three
offered in every mode — then (N2) an **H** button that opens a second floating bar of its own, the
H1–H6 level sub-toolbar (`SelectionMode` and the convert/change flows are covered under Headings
below), then Link / Edit / Unlink, then **Pad** · **Calendar** (arc 23 / Y3) · **Tag** (arc 21 /
W3) — three extension-gated buttons in a row, each on an ink-only (Pad, Calendar) or ink-or-lone-
heading (Tag) selection and each GONE without its own trusted extension installed, re-read on every
`show()` — and **Delete** last. Calendar sits between Pad and Tag as the second of the three; see
[Send to Calendar](#send-to-calendar-arc-23) below and [`docs/calendar.md`](calendar.md) for the
feature. It is a *bar*, not a button, because it is the shape the selection's actions live in from
here on.

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

### Objects — text, shapes and stickies (arc 28)

`SelectionModes.classify` (pure, JVM-tested) is the `when` `showSelectionToolbar` used to run by
hand: a lone content object with no ink takes its own mode — `HEADING`, `TEXT`, `SHAPE` or
`STICKY` — ahead of the link/mixed fallbacks. Per mode, `SelectionToolbar.show` offers:

- **`TEXT`** (a lone text object) — the base row (Snap · Copy · Cut · a link-free Link · Delete)
  and nothing of its own: the object's one verb is a **stylus tap inside the box**, which
  `onSelectionTapped` hands to `TextEditDialog` after the heading lookup misses. **Text** itself
  (the convert button, directly after **H**) is offered only on `STROKES` — ink alone, never a lone
  text object, which already has words.
- **`SHAPE`** (a lone shape) — the base row plus **Transform** (`ic_resize`, directly after Text),
  the one button this mode adds: it hands the shape straight to g-paper's transform mode (D9,
  [`docs/objects.md`](objects.md)).
- **`STICKY`** (a lone sticky) — the base row and nothing else: a note's one verb is a **finger tap
  on its icon**, which opens `StickyEditorActivity` (Gestures below), not a bar button.
  `StickyEditorActivity` carries the same eraser re-tap and `EraserBar` as the notebook (arc 29 /
  LE2) — a second tap on its armed eraser button opens the sub-bar, and `onLassoErased` records
  exactly what its point eraser already does (the note holds ink only, so `contentIds` is always
  empty and is ignored). The bar is the last child of the editor's root, dismissed on every
  pointer-down outside it and hidden on `exit()` and on `reload()` after a fresh showing.
- **Pad / Calendar / Tag are gone in all three modes** — Pad and Calendar are gated on `STROKES`
  alone, and `TagSelection.offered` explicitly refuses `TEXT`/`SHAPE`/`STICKY`: a note or a shape
  has no ink to send and no words to tag.
- **Link** stays offered on every link-free selection, the new kinds included — a link may wrap a
  text, a shape or a sticky exactly as it wraps ink or a heading ([`docs/links.md`](links.md)).

**Draw order (D8):** headings · text · shapes · links · stickies · strokes, all
`ContentLayer.BELOW_STROKES` — texts and shapes sit under links so a wrap changes nothing about how
content looks, and stickies sit over links so an icon dropped on a link is what a finger tap catches
(Gestures below). `PagePreview.drawContent` and `LinkComposite.build` mirror the same order exactly.

### Send to Calendar (arc 23)

The lasso's Calendar button (`sendSelectionToCalendar`) is offered on the same selection as Pad —
**ink only, `SelectionMode.STROKES`**, because `WireStroke` is the whole of what the seventh
point's `ICalendar` contract carries, the same reason the pad is ink-only. Since arc 23 / Y4 it
calls the one gate both lasso sends pass, `sendSelectionToExtension`: `TransferSelection.sendable`
reads `currentSelection` and returns the strokes in writing order — empty on a mixed or content-only
selection, or one with nothing live — and, past that, `TransferCaps.withinLimits` is checked
**before any bind** ("Too much to send" if it does not fit). Through Y3 each send asked this in its
own words; one written rule is what closed the drift that had already grown between them — the
pad's settle rule arriving a whole phase late (see [`docs/calendar.md`](calendar.md) § Notebook →
calendar for the rule itself).

The strokes then go through the four-row `ActionSheetDialog` (`calendar_target_title`, "Send to
Calendar"): **Today, morning · Today, afternoon · This week · This month**, built by
`CalendarTargets.Choice` with each row's target resolved **at the tap** — `CalendarTargets.target(choice,
LocalDate.now())`, so a sheet left up across midnight sends to the day the person is tapping on (the
Y4 review) — a pure `Choice` → `CalendarTarget` mapping so the host never computes a period itself;
every target comes from `CalendarTarget.of`, which normalizes
through the contract's own `CalendarDates` (the week's Sunday, the month's first day). The rows
carry no icons — four identical calendar glyphs would say nothing, `LinkPickerActivity`'s new-page
sheet is the precedent — and the sheet rises from the selection-toolbar tap, the O1 pattern, so it
needs no new frame-silence exception.

Picking a row calls `openCalendarWith(strokes, page, target)` → `CalendarEntry.open(InkSend(strokes,
page.width, page.height, target))` — `InkSend` the one outbound-ink class shared with the pad since
Y4, replacing what was `CalendarEntry.Send` — which opens the store, holds the bind, sends the
chunks and launches the calendar screen on that page — the ink lands **1:1** (no cell-fitting; the
user drags it into a cell afterward) and **lasso-selected**, as one undo step there. Only once the
send is actually across does `onCalendarSent()` clear the notebook's own selection and toast "Sent
to calendar" — the standing toast-confirms rule, since a send that failed partway has changed
nothing here to confirm.

This is the lasso's send — ink only, onto the page already displayed. Since arc 31 / HV5 a
**whole-page** Send from the calendar's own bar is a different act and lands differently: a new
page after the displayed one, papered with the grid that was sent, the ink on top — see
[§ The received page](#the-received-page-arc-31--hv5) above and
[`docs/calendar.md`](calendar.md) § Calendar → notebook for the calendar's own half.

See [`docs/calendar.md`](calendar.md) for the calendar's own side of both transfers (Send to
Notebook included) and the paste-back below for the reverse direction.

### Tag (arc 21)

The lasso always tags **the page on the paper, never the notebook** (the wizard's call) — a lassoed
heading or a lassoed patch of ink turns into a page tag, non-destructively both ways: the ink, the
heading and the selection are all exactly as they were afterward, because a tag is a *snapshot* of
some text at this moment, not a second name for the thing it was taken from. Editing that heading
later never renames the tag it produced; converting the same ink again makes another one.

**The offered set is exactly one heading, or a selection with no content objects at all** —
`SelectionMode.HEADING` and `SelectionMode.STROKES`, decided by `TagSelection.flowFor`. A **mixed**
selection is not offered the button at all, and neither is a lone link (content with a payload, not
ink). The reason is that a mixed selection has two answers with no way to ask which is meant: a
heading already carries the words a tag would be made of, while the ink beside it carries different
words, and re-recognizing the heading's own strokes could come back with something other than what
is on the glass. A button that quietly picked one of those would be worse than a button that is not
there.

- **A lone heading — `TagFlow.SILENT`.** One call (`TagManagerEntry.assign`), one toast naming the
  tag's canonical display text, no screen. The heading's hash prefix is storage, not the title, and
  never reaches the tag. A title that is **not** a valid tag — over `MAX_TAG_CHARS`, or blank once
  the prefix is stripped — is not refused: it falls through to the same correction screen the ink
  flow uses, prefilled with as much of the title as fits (`TagSelection.prefill`, a character backed
  off rather than a surrogate pair split — the `TextChunks` rule), so a tap that cannot finish
  silently still lands somewhere the user can finish it in one more gesture.
- **Ink alone — `TagFlow.RECOGNIZE`.** This takes `HeadingConvert` **whole** rather than growing a
  near-copy — "read this one writing area and give me back a single line" is the same question a
  heading convert asks: same extension, same selection-bounds writing area (a page-sized area under
  one line of writing collapses recognition to fragments), same problem dialogs. Only the caller's
  use of the answer differs — it opens the tag screen in `MODE_ADD` with the recognized text as
  `prefill` instead of creating a heading — so the name `HeadingConvert` stays; nothing in it knows a
  heading is what usually follows.

**The recognizer is NOT gated on.** Tag stands or falls with the *tag* extension alone; a missing
recognizer is explained by the same problem dialog the H button beside it already gives — H and Tag
sit in the same bar and both go out through the recognizer, so one vanishing while the other stayed
would read as a bug rather than a rule. It also keeps a package query off every `show()`.

**The flow is re-read from the live selection at the tap**, not trusted from the bar that offered
it — a selection can move, change kind or die between the bar going up and a button landing. The
page id is captured **at the tap** too (`displayedPageId`, the R6 rule), so a flip mid-recognition
still lands the tag on the page the ink was on. The toast fires when the write lands, never at the
tap — the standing toast-confirms rule, since until the write lands nothing has happened yet.

`RecognizingOverlay` shows "Tagging…" for the silent flow's wait rather than a third overlay object
— a default message-resource argument, because the wait is the same shape and length as a heading
convert's (the first tag operation of a host process pays SQLCipher's KDF, seconds on a Nomad) even
though nothing is being recognized.

See [Tags](#tags-arc-21) below for the notebook's other two tag doors, and
[`docs/tags.md`](tags.md) for the tag screen and the tag model.

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
4. calls `recognizeInk` with the **selection's bounds**, not the page, as the area — and the
   extension then segments the selection into reading-order lines and recognizes each with its
   own box, exactly as it does a page (since 2026-09-13; before that the selection box itself was
   the writing area).

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
at one page with bound taps as no-ops (never a disabled look). A **one-finger horizontal swipe over
the body** flips those pages too (F3, `core/ListSwipe` — `docs/library.md` § The flip). A `Dialog`
owns its own window, so the sequence is taken from the *dialog's* `dispatchTouchEvent`, not the
Activity's: the notebook's own `PageGestures` behind the panel never sees a stroke of it, and the
page underneath cannot turn while the outline is up. Expansion state is in-memory only —
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

**Entry points:** `btnRecents` (Tabler `clock`) **at the top bar's right edge**, with Document and
Tags before it and Calendar (arc 23) then, since Y4, the Scratch Pad after it — a weighted spacer
after the Lasso puts them all there, because none of them is a tool and the Recents panel comes in
from that side
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
follows, with the library-shape pager footer `INVISIBLE` at one page — and, like the Contents panel,
a one-finger horizontal swipe over the body flips them (F3).

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

## Document (arc 19)

The notebook's own door into og's Documents feature — the fifth extension point
([`docs/extensions.md`](extensions.md); [`docs/document.md`](document.md) is the feature bible for
everything past this screen). `btnDocument` (icon `ic_file_text`) sits in the top bar's right
cluster, immediately before Recents (see [Layout](#layout-activity_notebookxml) above). A tap runs
`DocumentSeedFlow.start()` — flush this page's ink, then hand the page's stored document straight
to the editor, or, on an undocumented page, recognize it behind a "Reading this page…" popup and a
consent flow first — and only then launches the editor through `DocumentEditorEntry`, the
scratch-pad-shaped client for the fifth point. **On the surface stack since arc 32 / RS1:** the
entry pushes its `SurfaceEntry` the instant the launch has actually happened, and pops it
synchronously at the top of `onResult` and in `close()` — before this screen's own `onResume` marks
itself the top, since ActivityResult callbacks always run first.

**The notebook is STOPPED behind the editor, not sealed.** Unlike a page flip or a plain close, the
Document button calls **no `releaseForHandoff()`**: the editor draws no ink of its own, so the EPD
pipeline stays here exactly as it does for the arc-13 template picker — measured, not assumed, on
the Nomad at M3 (pen scribble under the live editor drew nothing; ink resumed cleanly, with no
ghosting, on return). Every read and write the editor makes crosses back through the held callback
binder (`DocumentHostHooks`), never a `.soil` open of its own — the notebook's own `SoilDatabase`
stays the only writer.

**Page flips made inside the editor are caught up on close.** `IDocumentHost.requestPage` lets the
editor flip without touching the canvas; the notebook only catches up once the showing ends
(`documentShowingEnded`, `DocumentEditorEntry.onClosed`), landing on the page the editor was
showing (an ordinary notebook) or routing per `TextDocRouting`'s table (a text document — see
[`docs/library.md`](library.md) § Text documents). **The flip gap is a no-save zone**, guarded on
both sides of the process boundary; full detail lives in [`docs/document.md`](document.md).

`NotebookActivity` declares `keyboard|keyboardHidden` in `configChanges` (added at M4): the editor
runs in the extension's own process on top of this screen, and a Bluetooth-keyboard attach or
detach is a config change reaching all the way down here too — a recreate behind a live editor
would tear down the host binder mid-edit, the exact failure M4 exists to close.

## Tags (arc 21)

The notebook's own doors into the sixth extension point — see [`docs/extensions.md`](extensions.md)
for the point itself and [`docs/tags.md`](tags.md) for the tag screen and the tag model; this section
is only the notebook's own slice. The lasso's door onto the same point is [Tag](#tag-arc-21) above.

`btnTags` (icon `ic_tag`) sits in the top bar's right cluster, between Document and Recents (see
[Layout](#layout-activity_notebookxml) above) — **GONE without a trusted tag manager installed**,
never disabled. A tap opens `TagsPopup`, an [`AnchoredBar`](#anchoredbar) hung under it, holding
three **icon-only buttons with long-press hints** — the house style every floating bar in this
screen follows:

| Button | Icon | Opens |
|---|---|---|
| Tag notebook | `ic_notebook` | the tag screen in `MODE_ADD` on this notebook, field focused |
| Tag page | `ic_page` (Tabler `file` — deliberately **not** `ic_file_text`, which is the Document button two taps away in the same bar) | the same, on the page whose ink is on the paper |
| Manage tags | `ic_list` | the tag screen in `MODE_MANAGE`: this notebook **and** every one of its pages, for add and remove |

**Gated on `canvasShown`, not on `opened` alone.** Two of the three doors are about the page on the
paper, and a text document that has never shown its pages has no page to tag — the bar stays absent
rather than opening with a door that would do nothing (the standing rule: a control that cannot work
is not shown greyed).

**Page numbers are the host's to resolve, at the tap, against the live page list** — the extension
is handed labels, never a way to work them out, because it has no idea what a page is
(`TagTargets.pageNumber`). A displayed page that is not in the list — briefly true during a page op —
falls back to the notebook's own name rather than naming a "Page 0". MANAGE's page id/label arrays
stop at `TagShowing.MAX_PAGES`: the parcel **refuses** rather than allocates above it, so listing
what fits is the lesser of the two failures.

**The bar's outside-tap dismissal deliberately does not write `tapDismissedPopup`.** That latch
exists so a contact spent dismissing the *clipboard* popup is not also spent pasting on the same
contact — a coincidence of geometry the lasso popup has to guard against and this bar does not, since
it has no second meaning a stray tap could trigger.

### AnchoredBar

Placement, the measure-before-place rule, the rects and the button recipe for `TagsPopup` are not a
second copy of arc 8's `LassoPopup` (the armed lasso button's own Paste/Clear bar). Both bars are the
same shape — a small bordered row of icon buttons hung under a top-bar or toolbar control — so the
placement call, the measure-before-place rule, the rects and the button recipe moved into
`notebook/AnchoredBar`, and both callers are now thin on top of it: a `TagsPopup` or `LassoPopup`
supplies only its own buttons and the rule for when it may open. A second, near-identical bar class
would have been the sibling-copy trap ([`docs/drawing-engine.md`](drawing-engine.md)'s Ratta/Generic
family) in miniature — the same lesson, at the scale of one floating bar instead of a whole notebook
view.

`AnchoredBar` itself **moved into `:sn-screen` at arc 29 / LE2** so the eraser sub-bar
([EraserBar](#the-lasso-eraser-arc-29--loop) below) could share it with the scratch pad and the
calendar as well as the notebook — the same package, the three `:app` callers (`LassoPopup`,
`TagsPopup`, `InsertBar`) repointed at the moved class and otherwise untouched.

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
| 1-finger double-tap on bare paper | **hide / show all chrome** (arc 33 / F1 — the top bar and the bottom strip go `GONE` together and come back on the next pair; a pair where either tap hit a sticky or a link is that tap's act, never a toggle — `DoubleTapToggleRule`; the flag is global and persisted, `ChromePrefs`; guarded `opened && !closing`, the same shape every other gesture handler uses. **Since arc 36 "hidden" shows a floating corner tool button and its mini toolbar / overflow row rather than bare paper** — § Layout) |
| 2-finger horizontal swipe ← / → | insert a page after / before this one |
| 2-finger vertical swipe ↓ | open the **Recents** (T1 — its upward twin is unassigned) |
| 2-finger stationary double-tap | undo |
| 3-finger stationary double-tap | redo |
| 1-finger long-press | the **page sheet** — Copy / Cut / Paste / Page template (arc 12, the whole library since arc 13; [`docs/templates.md`](templates.md)) / Erase page (arc 30 / PE1) / Delete (B1; [`docs/clipboard.md`](clipboard.md)) / Export page (arc 30 / PE2, only while an exporter is installed; [`docs/export.md`](export.md) § Scope) / Save as template (arc 31 / HV2; [`docs/templates.md`](templates.md) § Save as template) / Export notebook (2026-09-10, only while an exporter is installed; § Export page) |

Thresholds (Paper-v0 parity — the numbers are the feel):

| Constant | Value | Meaning |
|---|---|---|
| `PAGE_SWIPE_MIN_DISTANCE_FRAC` | `0.30` × screen width | minimum horizontal travel before anything counts |
| `PAGE_SWIPE_LONG_DISTANCE_FRAC` | `0.50` × screen width | travel that qualifies on its own, however slow |
| `PAGE_SWIPE_MIN_VELOCITY_MULT` | `1.0` × `scaledMinimumFlingVelocity` | the fling alternative |

A swipe must be **horizontal-dominant** (`|dx| > |dy|`) and qualify on *velocity or* length;
**direction comes from the sign of `dx`, never velocity**, because a decelerating finger can flip
the velocity sign at the end of the drag. The vertical swipes (C1, K4) are the same rule rotated
90° — `SwipeMath.vertical`, vertical-dominant, the same three constants against the screen
*height* (written once in `:sn-screen`, shared with `ListSwipe`'s optional vertical callbacks that
the Bible reader's Contents rides), judged at the same
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
which puts it back on Paper v0's rule. Widened again at arc 28 / H4 to
`{ selectionActive || paper.transformingContentId != null }` (a Nomad finding from H3's demo walk):
a live transform mode claims finger input from g-paper exactly as a lasso selection does, and the
notebook's own detectors must yield to it the same way.

**Objects (arc 28).** A one-finger tap hit-tests **stickies before links** — `StickyFlow.openAt`
runs first inside `onFingerTap`, because the icon draws above the link layer (D8): a note dropped
over a link is what the finger is on, and a hit opens `StickyEditorActivity`. Stylus taps stay ink,
the same rule a link follow has always had.

**The chrome toggle's collision rule (arc 33 / F1).** `PageGestures` posts the second tap's
`onFingerTap` escrow before it evaluates the double, so both taps of a pair have already been
answered — sticky opened, link followed, or neither — by the time `onFingerDoubleTap` fires.
`DoubleTapToggleRule` keeps that answer as a **two-deep hit history** (`tapped(hit)` from every
`onFingerTap`, `shouldToggle()` consumed by the double) and toggles **iff neither tap hit** a sticky
icon or a link: a note or a link tapped twice is what the finger meant, never a chrome flip, and a
link followed on the first tap must never toggle the page it just navigated to. The rule is
timing-free — a double that somehow arrives with fewer than two taps recorded is refused, so the
worst case is one missed toggle, never a wrong one. Like every other finger gesture the toggle rides
`PageGestures.gateOpen()` and its escrow: a stylus tap, a sequence starting on chrome, or a
stand-down drops it at `ACTION_DOWN`, and it is never `whenPenIdle`-gated on top of that — `isPenActive`
counts hover, so idle-gating would hold the flip back long after the tap that asked for it.

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
| `LassoErased` (arc 29 / LE2) | `onLassoErased` — one drawn loop, whatever mix of strokes / `headingIds` / `links` / `textIds` / `shapeIds` / stickies it took. `ScribbleErased`'s exact shape and replay, kept its own kind for the same reason `ScribbleErased` and `Deleted` are separate: a loop around something is a different act to the user than crossing it out or tapping Delete, and a future undo *label* has to say which | `store.revive` + `headings.restore` + `links.restore` + text/shape restore + `StickyStore.restore` | `store.remove` + `headings.erase` + `links.remove` + text/shape erase + `StickyStore.remove` |
| `Deleted` | the selection toolbar's Delete (strokes **and**, N2, `headingIds` — **and**, K1, `links` snapshots: a whole-link erase records here too; O1's **Cut** goes through the very same path, so undoing a cut puts the ink back exactly as undoing a Delete would) | `store.revive` + `headings.restore` + `links.restore` | `store.remove` + `headings.erase` + `links.remove` |
| `Moved` | `onSelectionMoved` (strokes **and**, N2, `headingIds` **and**, K1, `linkIds` riding the same drag) | `store.move(-dx,-dy)` + `headings.move(-dx,-dy)` + `links.move(-dx,-dy)` | `store.move(dx,dy)` + `headings.move(dx,dy)` + `links.move(dx,dy)` |
| `HeadingCreated` (N2) | a successful convert | `headings.erase` + `store.revive` (in place — order matters) | `headings.restore` + `store.remove` |
| `HeadingDeleted` (N2) | edit-dialog empty Save, or the eraser sweeping a heading whole | `headings.restore` (in place) | `headings.erase` |
| `HeadingTextEdited` (N2) | the edit dialog's Save | write `before`'s content | write `after`'s content |
| `HeadingLevelChanged` (N2) | a level pick on an existing heading | write `before`'s content | write `after`'s content |
| `LinkCreated` (K1) | the picker's OK on a create ([`docs/links.md`](links.md)) | `links.unlink` | `links.relink` |
| `LinkUnlinked` (K1) | the selection toolbar's Unlink | `links.relink` | `links.unlink` |
| `LinkEdited` (K2) | the picker's OK on an edit | write `before`'s payload | write `after`'s payload |
| `TextCreated` (arc 28 / H2) | an Insert-bar text (`strokeIds` empty) or a lasso→Text conversion (`strokeIds` = the ink it replaced) | erase the text row, revive `strokeIds` in place | restore the text row, re-delete `strokeIds` |
| `TextEdited` (arc 28 / H2) | the text dialog's Save | write `before`'s content | write `after`'s content |
| `ShapeInserted` (arc 28 / H4) | an Insert-bar shape | `store.remove` | `store.restore` |
| `ShapeTransformed` (arc 28 / H4) | one finished transform-mode drag (`onTransformEnded`'s whole before/after geometry) | write `before`'s geometry | write `after`'s geometry |
| `StickyInserted` (arc 28 / H5) | an Insert-bar sticky (the icon row only — the editor has not run yet) | `StickyStore.remove` (takes any children with it) | `StickyStore.restore` |
| `StickyContentEdited` (arc 28 / H5) | one **showing** of the sticky editor that changed the note, recorded once from the result callback | `StickyStore.setContent(stickyId, before)` | `StickyStore.setContent(stickyId, after)` |
| `Page` | insert / delete (`Structural` snapshot, whose `objectIds` are type-agnostic — strokes, headings, links and, since arc 28, texts/shapes/stickies too) | `reconcile(before)`, **restoring** `objectIds` | `reconcile(after)`, deleting them |
| `PagePasted` (B1) | a paste — the same `Structural` shape, its own kind because `objectIds` runs the **opposite direction** (rows the paste *created*) | `reconcile(before)`, **deleting** `objectIds` | `reconcile(after)`, restoring them |
| `PageErased` (arc 30 / PE1) | **Erase page** — `pageId` + the `objectIds` `eraseCurrent()` soft-deleted, ids only: `StrokeStore` keeps **no in-memory mirror** (`revive` is a bare `dao.restore` queued on the writer, and `reconcile` already restores a page delete's content ids with the same bare call), so no per-type snapshot and no stroke split is needed — nothing moves and nothing is re-minted, the rows stay where they are, dated out. Recorded only when the list is non-empty | `session.restoreIds(ids)` (one `withTransaction` + `mirror(now)`), `store.drain()`, `refreshToPage(pageId)` | `session.eraseIds(ids)`, drain, refresh — one repaint each way |
| `TemplateChanged` (arc 12) | a pick in the template library — the two template ids the page moved between (`""` = blank). No drain: it writes one page row and never touches the stroke writer | `applyTemplate(from)` | `applyTemplate(to)` |
| `ObjectsPasted` (O1) | an object paste — `Deleted` run in reverse, its own kind for `PagePasted`'s reason (a link travels as a `PageLink` snapshot, so undo takes its wrapped children down with it); a transfer paste from the Scratch Pad or (arc 23 / Y3) the Calendar is a strokes-only object paste and records here too, through the one shared `pasteTransferred` body (below) rather than a fifteenth kind | `store.remove` + `headings.erase` + `links.remove` | `store.revive` + `headings.restore` + `links.restore` |
| `PagesReceived` (arc 35 / HA1) | **several pages received from the calendar in one gesture** — a Day's AM and PM halves — one `Structural` per `receivePage`, chained (`before` = the previous `after`); reports the last page | `reconcile(first.before)`, **deleting** every snapshot's `objectIds` | `reconcile(last.after)`, restoring them |
| `PageReceived` (arc 31 / HV5) | a **whole-page send from the calendar** arriving with paper — `receivePage`'s `Structural`, `PagePasted`'s exact shape and replay, its own kind so a future undo label can say the page came from the calendar rather than the clipboard (see below) | `reconcile(before)`, **deleting** `objectIds` | `reconcile(after)`, restoring them |

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

**The three older multi-kind entries widened at arc 28 / H1.** `Deleted`, `ScribbleErased` and
`Moved` all gained `textIds`/`shapeIds` (ids only — the heading rule: a soft-deleted row keeps every
column and revives in place) and `ObjectsPasted` gained the same pair for the paste direction;
stickies ride all four as whole `PageSticky` snapshots **with their content**
(`StickyStore.withContent`), never ids, because an icon alone would come back an empty note. Both
replay `when`s stay exhaustive, so a new kind that misses one arm is a compile error, not a silent
no-op — the standing trap this arc tests against on every kind.

**`LassoErased` (arc 29 / LE2) is `ScribbleErased`'s shape, not a widened field.** Because a lasso
erase is its own gesture rather than a variant of an existing one, it earned its own kind (Undo
table above) instead of a boolean or an extra field on `ScribbleErased` — the same call as
`ScribbleErased` itself against `Deleted` at arc 14. Both exhaustive `when`s (undo and redo) gained
the arm, so the standing trap from H1 held again: a kind either replays both directions or the
build fails. `NotebookUndoTest` gained the case both ways.

**A sticky's delete snapshot suspends (arc 28 / H5; the delete made synchronous arc 34 / M6).**
The content read `Deleted`/`ScribbleErased` need before the row goes is a suspending call, and
g-paper's delete callback is not. `NotebookActivity.recordWithStickies` is the one seam: with a
sticky (loose, or wrapped in a link that holds one) in the act, it calls
`StickyStore.removeWithContent` / `LinkStore.removeWithContent` **on the spot** — each queues one
writer job, in writer order, that reads the note's content *ahead of its own soft-delete* in one
transaction and hands the snapshot back as a `Deferred` — and only the **record** waits for it:
still **one gesture, one undo entry**, just recorded a beat later. With no sticky in the act the
entry is recorded on the spot, exactly as every erase and delete was before H5. Before M6 the
delete itself sat inside a `runPageOp` behind a drain and the reads, and a page op is skipped under
`closing` — so an erase while another op held the mutex, followed by Back, never deleted the rows
and the sticky (or the sticky-wrapping link) came back on the next open. Nothing in the delete goes
through `runPageOp` now; a closed writer cancels the deferred, so a delete that will never run
records no entry.

Every gesture-driven operation runs through `runPageOp` — a `Mutex` on `lifecycleScope`, a no-op
while not open or once closing — so two overlapping gestures can never tangle the page list. **What
a throwing op comes to is the pure `PageOpFailure.classify` table (arc 34 / M7), the `:ext-ink`
screens' shape:** a `CancellationException` is rethrown (the close's own business — before M7 it
was logged as a failure on every close); a **store failure** — `android.database.SQLException`
(the framework's and SQLCipher's `SQLiteException` base) or an `IOException` anywhere in the cause
chain — shows the plain problem dialog *Couldn't change the page* / "The page could not be
changed. Nothing was saved." (`page_op_failed_title/body`; skipped once the screen is finishing),
so a full disk under Erase page or Delete page no longer reads as a confirmed tap that did nothing;
anything else is a bug and is logged as before. Shared with `:ext-ink` it is not — that would be a
new module edge for one `when`.

### The transfer paste-back (arc 11 / J5, unified arc 23 / Y3)

Ink coming back from the Scratch Pad and ink coming back from the Calendar are the same act with
two different senders, so since Y3 they run through **one body**:
`pasteTransferred(wire, truncated, wording, source)`. `pasteFromPad` and `pasteFromCalendar` each
take the `DrainedInk` their entry's `onDrained` callback hands them — the one drained-result class
(arc 23 / Y4, `extension/HeldInkClient.kt`), shared by both points — and are one-liners over
`pasteTransferred(drained.strokes, drained.truncated, wording, source)`: `PAD_WORDING` /
`CALENDAR_WORDING` supply the three strings the two sends differ by (the failed-paste dialog body,
the truncated-paste title and body), and `source` names the sender in the log line only. The single
in-flight field the tool restore needs — `toolBeforeTransferPaste` — is one field rather than two:
only one transfer can have just landed.

The body itself: drain the still-held bind — `HeldInkClient.drainOutgoing`, via `ScratchPadClient`
/ `CalendarClient`, run from `ExtensionScreenEntry.onResult` once the extension's screen returns
(the same `onResult` that pops the entry's `SurfaceEntry` off the surface stack, synchronously, at
the top of the callback — arc 32 / RS1) —
mint fresh ids (nothing from the wire is trusted beyond its geometry — `TransferCaps.toStrokes`),
write the strokes in one transaction appended after the displayed page's current max `"order"` with
relative order preserved (the arc-8 rebase rule), record one `Action.ObjectsPasted` step, then arm
the lasso **before** `setSelection` so the pen can drag the result into place at once — the tool the
user had comes back pen-idle when that selection is dismissed. A drain that came back truncated is a
problem dialog (the rest of the ink is still on the sender); otherwise the ordinary "Pasted" toast.
See [`docs/scratchpad.md`](scratchpad.md) § The transfers and [`docs/calendar.md`](calendar.md) for
each sender's own half.

The long-press **asks**; it never acts. `showPageSheet` opens an `ActionSheetDialog` with
**Copy page · Cut page · Paste page · Page template · Erase page · Delete page · Export page · Save as
template · Export notebook** (nine rows since 2026-09-10) — Paste present only when the clipboard holds a page,
Export page and Export notebook only while a trusted exporter is installed, Save as template only while the page has a
usable size (**absent, never disabled**: a greyed control is invisible on e-ink).
Copy and Cut confirm with a toast; Paste opens a second sheet for the placement (before/after); Page
template opens the template library (below); Erase page and Delete go to their confirm dialogs;
Export page closes the notebook into the Export screen (below); Save as template (arc 31 / HV2,
`notebook/SaveAsTemplateFlow`) rasters the page as the export bake would — paper + ink through the
shared `PageRaster`, after a `drain()` — and lands it in the template library with fit pinned to Fit,
no `.soil` write, no undo entry: [`docs/templates.md`](templates.md) § Save as template. Export notebook (last) is
the library's whole-notebook Export through the same close-export-reopen door as Export page (below).
The whole clipboard side is [`docs/clipboard.md`](clipboard.md).

The delete confirm is the bare question "Delete this page?" with **no warning body** — a deleted
page and its ink come straight back via undo (soft delete + `reconcile`), so "cannot be recovered"
would be false (eye-check #2 finding, 2026-08-22). `showPageSheet` calls `paper.releaseRender()`
**ungated**, which is safe here only because the long-press fired through the gesture gate: it
never arms while the pen is active and re-checks at fire, so we are outside the pen-active window
the R3 rule protects.

### The received page (arc 31 / HV5)

**Since arc 35 / HA1 the landing takes a list** (`receiveCalendarPages`): every page's paper is
checked first, then one `runPageOp` runs `session.receivePage` per page in order — each inserts
after the page the previous one landed on — recorded as `Action.PageReceived` for one page and
`Action.PagesReceived` for more; the notebook ends on the last page with its ink selected. A page
whose paper fails is dropped from the pair (its ink lands on the displayed page only when no page
at all could be papered). A "Receiving from the calendar…" box stands over the screen for the
whole of it.

A **whole-page send from the calendar** lands a *new* page rather than pasting onto the one
displayed — the one road out of the transfer paste-back above that creates a page instead of
appending to it. `NotebookActivity.pasteFromCalendar` is where the two roads fork: `drained.paper
== null` (a selection send, or a whole-page send whose paper never rendered) keeps the ink-only
road through `pasteTransferred` unchanged; a non-null `paper` goes to `receiveCalendarPage`.

`receiveCalendarPage` bounded-decodes the paper bytes off Main and checks the decoded picture
against the page it claims to be through pure `notebook/CalendarPaper.accept(byteCount, width,
height, pageWidth, pageHeight)` — the encoded size under
`TemplateImport.MAX_BLOB_BYTES` and the decoded dimensions exactly the sender's page, zero of
either refused outright. A refusal with ink behind it falls back to the ink-only paste (`"the
calendar's paper was refused"`, logged); a refusal with **no** ink lands nothing at all and says
"Couldn't add the page".

Paper that passes runs through `runPageOp`: `session.store.drain()` first (the delete/erase rule
— a queued stroke commit must never land after the page list has already moved), mint the strokes
through `TransferCaps.toStrokes`, then `NotebookSession.receivePage(width, height, paper, strokes,
dpi): Structural` — a new page row after the current one, sized to the **sender's** page (the
calendar's 1:1 rule, not this notebook's default), with the paper resolved **reuse before mint**
through `resolvePaper` (split out of `mintOrReuse` precisely so the minted template row can be
`upsert`ed **inside** the receive's own transaction, alongside the page row and its stroke rows —
one send twice over lands one template row, filed under `PagePaper.token`'s digest, the same rule
every other paper reuse in the file follows). `maxOrder`, `renumber`, `loadTemplateFor` and
`mirror` all run exactly as they do for the notebook's own page inserts.

The receive is recorded as its **own undo kind**, `Action.PageReceived(snapshot)` — not a fifth
`Action.PagePasted`, even though the two replay identically (`session.reconcile` on the
snapshot's `objectIds` in the paste direction, both ways), because a future undo *label* has to be
able to say a page arrived from the calendar rather than from the clipboard, the same reasoning
that keeps `Deleted` apart from `Erased`. What it undoes is the whole of what arrived: the new page
after the displayed one, its paper and its ink together, in one step.

`Action.PageReceived` then feeds `navigateTo(session.currentIndex)` and the same shared
`landTransferred` tail the ink-only paste uses (arc 11 / J5, unified arc 23 / Y3, above) — the
lasso armed **before** `setSelection` (the O2 rule), the truncated-drain dialog if the send was
cut short, the toast "A page from the calendar was added." on success, and nothing selected when
the page arrived with no ink at all. `pasteTransferred` (the ink-only road) now calls that same
`landTransferred` too, rather than the copy it used to end on — one tail for both roads, the same
call the arc-23 unification made for the rest of the transfer.

### Erase page (arc 30 / PE1)

**Erase page** is the content-only wipe og's canvas "Page" menu offers: every live object on the
page goes, the page stays. The confirm is the delete's shape — "Erase this page?", Cancel · Erase,
a plain `AlertDialog` through `Dialogs.style`, no warning body for the delete's reason (everything
comes straight back via undo). Erase runs `doErase()` under `runPageOp`: `session.store.drain()`
**first** (the delete's rule — a stroke commit still queued on the writer would otherwise land after
the id snapshot and survive as a live orphan), then `NotebookSession.eraseCurrent()`, then one
`Action.PageErased` if anything went, then **one** `refreshToPage(pageId)` — the scribble / eraser
rule: the host repaints once after the transaction, never per object.

`eraseCurrent()` is `deleteCurrent()`'s content half alone: `dao().liveDescendantIds(page.id)` —
the one type-agnostic query that already serves the delete (strokes, headings, links and their
wrapped children, the page's `document` row, texts, shapes, sticky notes and their children) — and
one `withTransaction { softDelete(ids, now) }`. The page row, its `order`, its size, its template
and `currentIndex` are untouched; the page count does not change. **An empty page's Erase is
silent** — the dialog is still shown (the row is always present while the page exists), but the
query comes back empty, no transaction runs, nothing is recorded and nothing repaints. The page's
document row **is** part of the erase (the user's PE1 call): the page document is content of the
page and goes with the rest, and comes back on undo like everything else.

The row sits between Page template and Delete, icon `ic_erase_page` in `:sn-screen` — og's
`ic_erase_all` byte-for-byte (Tabler `file-x`, 24 dp / stroke 2 / round; og's `drawable/` was
checked before drawing a "fresh" one, the standing rule).

### Export page (arc 30 / PE2) · Export notebook (2026-09-10)

**Export page** is the notebook's Export reachable at page scope — `ic_download`,
present only while `exportAvailable`, which is `ExtensionRegistry.exporters(this).isNotEmpty()`
re-asked on every resume and cached (the sheet is built synchronously on the long-press, and a
package rarely changes under an open notebook; a stale true costs one dialog on the Export screen,
never a crash — the cheaper of the two idioms, against the library's per-long-press IO beat).

The door is **close, export, reopen** (decision 5): `exportPage()` takes `displayedPageId` (what
is on the glass — the R6 rule, never `session.currentIndex` mid-flip), shows the "Opening…" overlay
and runs `close { startActivity(ExportActivity.intent(…, pageId, returnToNotebook = true)) }`. The
Export screen reads a **cold** `.soil` (`ExportOpen` guard 2 refuses a held file), so the notebook
closes exactly as it does for a Recents switch — drain, cover, bookmark, seal — and the launch runs
after the seal by `close(andThen)`'s ordering. **Not** through `runPageOp`: `close()` takes the
page-op lock itself, so a page op in flight finishes first anyway, and `closing` refuses everything
after. `ExportActivity` relaunches this notebook when it finishes, whatever the outcome (exported,
cancelled, refused, Back), and the reopen lands on the bookmark the close just wrote — this page.
Undo history dies with the close, as on every close. Seal-in-place was declined (a new state
machine on a ~3900-line screen). The Export screen's own half — the Scope row, the Soil rule, the
filename, the reopen — is [`docs/export.md`](export.md) § Scope.

**Export notebook** (2026-09-10 — the user found the whole-notebook export reachable only from the
library) is the sheet's last row, `ic_download`, behind the same `exportAvailable` gate. It is the
same door — `exportVia(pageId = null)`, which `exportPage()` also calls with the displayed page —
so the Export screen opens exactly as from the library: whole scope, Soil listed, no Scope row, and
the notebook reopens after on its bookmark. No new Intent extra, no Export-screen change.

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

- `onResume` → **`stack.markTop(stackToken)` first** (arc 32 / RS1 — resumed means the top of the
  surface stack, so whatever entry stood above this one has closed; guarded on `::stack.isInitialized`
  because an `IndexGuard` bounce still gets this callback with nothing attached; runs **after** every
  result callback, which is why an extension entry's own pop always precedes it), then **the chrome
  re-sync** (arc 33 / F1): `if (chromeToggle.hidden != chromePrefs.hidden) chromeToggle.apply(chromePrefs.hidden,
  initial = true)` — the pad or the calendar may have flipped the persisted flag while this screen
  was stopped, and `apply(…, initial = true)` skips the render release since nothing is on the glass
  yet. This runs **before** `paper.resumeDrawing()`. The write side of the same handoff is
  synchronous, not `onResume`-timed: `ExtensionScreenEntry.onResult` reads the result Intent's
  `EXTRA_CHROME_HIDDEN` and writes `ChromePrefs` right at the top of `onResult`, before the launched
  coroutine — ActivityResult callbacks run **before** `onResume`, so by the time this re-sync runs
  the preference already reflects whatever the extension screen reported. The sticky editor carries
  the identical re-sync before its own `resumeDrawing()`.
- `onStop` (not closing) → app-scoped: `CoverSnapshot` + `saveLastOpened` (cheap durability point).
- Toolbar back / system back → **`backPressed()`** (K4 — in a via-link notebook both Backs walk
  the link trail first, exactly like a swipe-up; only an empty trail falls through to `close()`) →
  `close()`: `stack.pop(stackToken)` (arc 32 / RS1, replacing the old `lastOpenNotebookId = null`)
  → app-scoped `NonCancellable`: cover → `saveLastOpened`
  → `refreshMeta` (name + folder path from the index) → `seal()` (`flushTouch` → `drain` →
  **purge** → `wal_checkpoint(TRUNCATE)` → close) → `finish()`. Each step guarded; idempotent
  (`closing` flag; `onStop` stands down once closing). K4 adds `close(andThen)`: a cross-notebook
  hop launches the next screen **strictly after the seal completes** — the seal/reopen race is not
  survivable any other way ([`docs/links.md`](links.md)). `stack.pop(stackToken)` runs at the same
  four sites the old id-clear did: the recovery-declined leave, the cancelled passphrase prompt,
  `failOpen`, and `close()` itself — never from `onDestroy`, which a killed process never gets.
- **Seal-time compaction (arc 17 / K1)** — the purge above is `SoilCompactor.compact`: after the
  writer is closed (no queued write can race the deletes), before `db.seal` (the checkpoint
  absorbs the `VACUUM`), every soft-deleted row is hard-deleted and the space given back. Undo is
  in-memory and dies with the session, so those rows are unreachable by construction. It never
  throws, never touches `updatedAt`, exempts template rows, and a cheap `EXISTS` probe keeps the
  common nothing-to-purge close free. `db.seal` then sweeps a fully-checkpointed `-wal`/`-shm`
  pair (never a non-empty WAL) **before releasing the `SoilOpenFiles` claim**, and
  `SoilDatabase.open` waits (bounded) on that claim — a prompt reopen of a large notebook must not
  race the seal's `VACUUM` (the sticky-lock family). Details: [`docs/backup.md`](backup.md).
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

### Cold-launch restore (arc 32 / RS1–RS2)

The library's `replayStack()` replaces the old `reopenLastNotebookIfNeeded()` — same read-once,
cleared-regardless discipline, same three validity gates (alive index row · type NOTEBOOK · `.soil`
on disk) — but reads the whole surface stack instead of one id, and can hand a **chain** down to
this screen rather than just the notebook. The stack itself, the gates, and the library-level arm
(an extension screen open with no notebook beneath it) are [`docs/library.md`](library.md) § Launch
restore; this section covers only what happens once `openNotebook(…, resumeAbove)` reaches here.

- **`EXTRA_RESUME_ABOVE` rides the launch** — the surfaces (`CALENDAR`, `SCRATCH_PAD`, or
  `CALENDAR, SCRATCH_PAD`, or `DOCUMENT_EDITOR`) that stood above this notebook, decoded once into
  `resumeAbove` and consumed at whichever of `loadCanvas` or `openIntoEditor` the open ends in.
- **`replayAbove()` is the last line of `loadCanvas`** — consume-once, after `opened = true` and the
  "Opening…" overlay is down, so it runs behind the own-key passphrase prompt **by construction**:
  nothing above this notebook can stand until the notebook itself is on the paper (decision 3 — a
  cancelled prompt pops this entry and clears everything above it along with it). Each arm **awaits
  the entry's own `discovered()`** — `ExtensionScreenEntry.discovered()` /
  `DocumentEditorEntry.discovered()`, `refresh()`'s body made awaitable — rather than reading
  `isAvailable`: the `onResume` refresh and this replay are two coroutines whose finishing order is
  a race, so a reopen that read `isAvailable` could drop a screen that is installed. After every
  suspension the arm
  re-checks `standingForReplay()` (`opened && !closing && !isFinishing && !isDestroyed`) before
  raising anything.
- **The arms** (`ReplayPlan.decodeAbove` → `ReplayPlan.legalAbove`'s two legal shapes — anything
  else is cut to its longest legal prefix, logged, because a `NOTEBOOK` first is nothing this
  replay can raise): `[CALENDAR]` / `[SCRATCH_PAD]` → the entry's `open()` with no `InkSend` (there
  is nothing to send), through the entry's `beforeLaunch` (`releaseForHandoff`) exactly as a tap;
  the pair `[CALENDAR, SCRATCH_PAD]` → `openPadOverCalendar()` — the same three lines
  `onCalendarClosed` already used for the calendar's own pad door, now shared: re-attach
  `calendar.stackEntry`, arm the `reopenCalendarAfterPad` latch, open the pad, so a plain pad close
  brings the calendar back exactly as today; calendar missing → the whole chain dropped, pad
  missing → the calendar comes back alone. `[DOCUMENT_EDITOR]` → `documentEntry.open()` only when
  `session.documents.get(displayedPageId)` answers a row — no `DocumentSeedFlow`, no recognition,
  nothing staged (decision 4); no row → dropped, the notebook comes back alone.
- **Text documents consume the whole above-list on their own launch:** `openIntoEditor(launch =
  true)` reads and empties `resumeAbove` before anything else — a bare `[DOCUMENT_EDITOR]` above a
  text-document notebook is consumed silently (the route is already launching the editor; never a
  second launch), anything else is logged and dropped, since a text document has no page for
  another screen to stand on.
- **Every drop is one `Slog.d` line naming the surface, never an id.** Per-surface position rides
  nothing on the stack: the notebook lands on its own `refId` page (`saveLastOpened`), and the
  calendar / pad / editor land on their own persisted positions, exactly as an ordinary tap would.
- **The via-link flag rides the entry** (`SurfaceEntry.viaLink`), so a restored via-link notebook is
  reopened *as* via-link and the persisted `LinkTrail` survives — K4's rule, unchanged, just moved
  off `BrowseState.lastOpenViaLink` onto the stack entry ([`docs/links.md`](links.md)).
- **The extension entries maintain their own place on the stack**, not the host's lifecycle:
  `ExtensionScreenEntry` and `DocumentEditorEntry` push their `SurfaceEntry` the instant
  `launcher.launch` has actually succeeded — never at the tap, where the open can still fail with
  nothing on the glass — and pop it synchronously at the top of `onResult` and in `close()`.
  ActivityResult callbacks run **before** `onResume`, so the pop always precedes this screen's own
  `markTop` finding the stack.
- **Nomad walk (RS2, Sonnet + Fable over adb):** the notebook, killed behind the calendar / the pad
  / the document editor (host force-stopped first, then the extension process — killing the
  extension alone hands the host a cancelled result whose `onResult` pops the entry before the walk
  ever gets to look), came back cold with the screen resumed on top and its `restore: reopening […]
  above the notebook` line, Back walked out through the notebook to the library with the stack
  shrinking to `[]`; the calendar's pad door restored pad-over-calendar, Back brought the calendar
  back; an uninstalled calendar behind a restored `NOTEBOOK CALENDAR` stack came back with the
  notebook alone and "the calendar is not installed — dropped"; a text document killed behind its
  editor came back to the editor exactly once. Own-key notebooks were not walked on the Nomad (its
  library is all GLOBAL) — by hand: prompt → cancel → library with the stack cleared; prompt → key
  → the chain above comes back.
- **`endTransformIfRunning()` (arc 28 / H4)** persists and records a running transform mode through
  `onTransformEnded` rather than dropping it on the silent `release` path — called at **eight**
  sites: `close()`, `onStop`, the top of `navigateTo` (before `drain()`, so a same-page refresh
  reads the geometry the exit just wrote), both extensions' `beforeLaunch` handoffs
  (`releaseForHandoff` is itself a silent release), and the three other floating bars' `show`s
  (`showLassoPopup`/`showTagsPopup`/`showInsertBar` — another bar taking this one's place ends the
  mode, the same rule the bars already apply to each other). Idempotent, and safe before `onCreate`
  has built the surface.
- **The sticky editor's EPD handoff (arc 28 / H5).** `StickyFlow` runs
  `dismissFloatingChrome()` → `endTransformIfRunning()` → `paper.releaseForHandoff()` → launch, all
  inside one page op after a `drain()` — the notebook itself has no chrome-side `releaseForHandoff`
  to give up, only the paper surface underneath. `StickyEditorActivity` mirrors it:
  `resumeDrawing()` in `onResume`, `releaseForHandoff()` before every `finish()`. The notebook's
  result callback runs `reclaimPipeline()` **first, before any other statement** — ActivityResult
  callbacks run **before** `onResume` — guarded on `::paper.isInitialized`, since the same callback
  also fires on a screen Android rebuilt after a process death whose `onCreate` bounced on
  `IndexGuard`.

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

**Arc 19 added no new exception**: the Document button's own "Reading this page…" dialog
(`DocumentSeedFlow.seed`, an undocumented page's open-time recognition) and the editor's launch
both follow a deliberate chrome tap on `btnDocument` — exception 1's justification exactly (the
page-template picker's own Activity launch, extended to a cross-process one at M3): the tap has
already passed the gate before either can show, and nothing here repaints while the pen writes.

**Arc 21 added no new exception**: `TagsPopup`'s show/hide follows a deliberate chrome tap on
`btnTags` — exception 1's justification again, a bar in place of a dialog or an Activity launch. The
lasso's Tag button rides exception 5, the same as every other selection-toolbar button's own tap;
and the silent flow's "Tagging…" box is exception 4's "Recognizing…" box exactly, carrying a
different word for a wait of the same shape.

**Arc 23 added no new exception**: `btnCalendar`'s "Opening…" overlay and the calendar's launch
follow a deliberate chrome tap — exception 1's justification, the same as the Document button and
the pad's own door. The lasso's Calendar button raises the four-row Send-to-Calendar sheet on its
own tap, exception 5's shape (a deliberate response to a selection-toolbar button, the H toggle's
reasoning) rather than a new one; and the transfer paste-back landing selected with the bar up is
exception 7's object-paste frame exactly — ink arriving in one frame at a boundary where nothing is
being written, whether that ink came from the clipboard or from another extension's screen.

**Arc 29 added no new exception**: the eraser sub-bar's show/hide follows a deliberate tap on the
already-armed eraser button — exception 5's justification (a chrome tap answered by a chrome
frame), extended from the lasso popup to this bar. A lasso-erase's own repaint is the engine's one
frame at the outline's completion, the same boundary exception 2 already covers for a selection;
the host never repaints from `onLassoErased` beyond what `removeContent` already asks for.

**Arc 30 added no new exception**: the Erase page confirm and the Export page row are rows of the
page sheet — exception 1's act exactly (a dialog raised from a dialog that is already up, the pen
demonstrably idle), and the Export door's "Opening…" overlay is exception 3's boundary on the way
*out* of the screen. The erase's own repaint is **one** `refreshToPage` frame after the
transaction, the same single frame a page delete or a template pick already presents at a deliberate
chrome act.

**Arc 33 added no new exception**: the chrome toggle's flip — both bars `GONE` / `VISIBLE` at a
finger double-tap (`ChromeToggle`, shared by the four paper screens) — rides exception 6's
justification exactly: the act already passed `PageGestures`' `gateOpen()` and its escrow, so the
pen is demonstrably not on the paper; `releaseRender()` precedes the flip as every chrome handler's
does; and it is deliberately *not* idle-gated, for exceptions 2, 3 and 6's reason — `isPenActive`
counts hover, and a hovering pen would hold the bars back long after the taps that asked for them.
The floating bars a lasso raises keep working over bare paper (exception 2 covers their show), and
the button-anchored popups go down at hide as a deliberate act (exception 7's hides).

**Arc 36 added no new exception**: the collapsed chrome's corner button and its two rows ride
exception 6 exactly — opening or closing a row follows a chrome tap on the corner button, a
mirrored bar button, or a tool pick, each already `releaseRender()`-ed and none idle-gated, for the
same hover reason exceptions 2, 3 and 6 already give. A tool pick's own release is the eraser
sub-bar's pen-gated `PenIdle.releaseRenderIfIdle`, not this exception's ungated one.

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

**Arc 21 (tags), the notebook's own half:** `TagTargetsTest` (1-based page numbers following the
list's order, a page not in the list having none, every page listed while under `MAX_PAGES`, the
listing stopping exactly at the bound) and `TagSelectionTest` (a lone heading is the silent flow, ink
alone is recognized, a mixed selection has no flow, a lone link is not ink, no tag manager means no
button whatever is selected, exactly the two flows offered with one, an ordinary title is a tag, a
blank or over-cap one is not, a prefill normalized the way a tag is, recognized line breaks
collapsing, nothing left being a null prefill rather than an empty one, an over-long prefill cut to
the cap, and a cut never splitting a surrogate pair). The tag screen, the codec and the seam's own
tests are [`docs/tags.md`](tags.md)'s.

**Arc 23 (calendar), the notebook's own half:** `CalendarTargetsTest` (8) — the four rows in the
wizard's order, a fixed Wednesday's day/day/week/month targets, a Sunday's week target being that
day itself, a Saturday's six days back, a month-first day, a year-end day whose week crosses into
January while the month stays in December, every row satisfying `CalendarTarget.requireValid`
(construction is the validation), and a choice resolving against the day it is asked on rather than
the day the sheet was built. The screen, the store, the geometry and the seam's own tests are
[`docs/calendar.md`](calendar.md)'s.

**Arc 23 / Y4 (the host-side client/entry sibling copy), the pure rule the unification also pulled
out:** `TransferSelectionTest` (5) — a selection sends its strokes in writing order, a mixed
selection sends nothing, a content-only selection sends nothing, an empty selection sends nothing,
and ids no longer live are dropped from what is sent. `HeldInkClient` and `ExtensionScreenEntry`
themselves are exercised through `ScratchPadClient`/`CalendarClient` and
`ScratchPadEntry`/`CalendarEntry`'s own instrumented paths, not a JVM suite of their own — the
`RattaNotebookView` sibling-copy trap this closed is documented, not separately unit-tested.

**Arc 28 (objects), the notebook's own half:** `TextRowsTest`, `TextLinesTest`,
`TextPlacementTest`, `SelectionModesTest` (+ `SelectionModesStickyTest`), `ShapeRowsTest`,
`ShapeFlagsTest`, `ShapeGeometryTest`, `ShapeDefaultsTest`, `ShapeBoxTest`,
`ShapeTransformLabelsTest`, `InsertBarKindsTest`, `StickyRowsTest`, `StickyFlagsTest`,
`StickyStoreTest` (+ `StickyStoreSetContentTest`), `StickyInkTest`, `StickyClipTest`,
`StickyDefaultsTest`, `StickyEditorTransferTest`, `TagSelectionTest`
(+ `TagSelectionStickyTest`), and `NotebookUndoTest`'s six new kinds (+
`NotebookUndoStickyTest`). `ObjectClipTest` grows the three kinds' arms (a sticky's children
travel with fresh ids, kept in local space; a shape's payload bounds go through
`ShapeGeometry.tightBounds` + `strokeWidth/2`, not the density-scaled `aabb`), and `SoilDao`'s
`liveContentIds`/`liveDescendantIds` widen against the same shared `FakeSoilDao`. `:app` went
**1194 → 1470** over the arc (1337 at H1 · 1369 at H2/H3 · 1393 at H4 · 1459 at H5 · 1470 at H6);
2830 across every module. The store/geometry/PDF-endnote/transform-mode suites this table does not
name are [`docs/objects.md`](objects.md)'s own inventory. `NotebookActivity` grew alongside them,
**3150 → 3725** lines despite the new object kinds landing almost entirely in their own files — one
`<Kind>Flow.kt` (+ a nested `Host` object the activity implements) per kind kept the growth in the
activity itself to selection wiring, the eight `endTransformIfRunning()` call sites and the
handoff chain, not the features themselves.

**Arc 29 (loop), the notebook's own half:** `NotebookUndoTest` gains the `LassoErased` case both
replay directions (revert and reapply, on `ScribbleErased`'s fixture shape). No new pure geometry
module — `EraserBar`'s placement rides `AnchoredBar`'s existing suite, and the hit rule is
g-paper's own `LassoHitTest`, already covered there. `:app` **1470 → 1472**; **2830 → 2832** across
every module; `./gradlew test` exit 0. The engine side (`Tool.LASSO_ERASER`, `onLassoErased`, the
Ratta trail, the Onyx capture widening — untested this arc, SN is Ratta-only) is
`~/git/g-paper`'s own suite, not this repo's.

**Arc 30 (page), the notebook's own half:** `NotebookUndoTest` gains the `PageErased` case (ids ride
the stack; its own kind against `Page` / `Deleted` / `LassoErased`). No `NotebookSession` test:
the session opens a real Room DB and `FakeSoilDao` cannot drive it — the erase is one existing
query and one existing soft-delete, walked on the Nomad instead (incl. `am crash` → reopen
persists the erased state). The export half's pure pieces (`ExportScopeTest`, `ExportNamingTest`)
are [`docs/export.md`](export.md)'s. `:app` **1472 → 1487**; **2832 → 2847** across every module;
`./gradlew test` exit 0.

**Arc 31 "Harvest" (HV2/HV5), the notebook's own half:** `TemplateSeedNameTest` (11 — the topmost
heading reduced to `NameRules.CHARSET`, spaces collapsed, capped, else `page N`, never a seed
`NameRules.validate` would refuse) covers **Save as template**'s name seed; `PageRaster` is
Android-bound and has no JVM test of its own (said in its KDoc — the bake it moved out of
`ExportRender` is exercised by the export suite instead). `CalendarPaperTest` covers **the
received page**'s bound check (`CalendarPaper.accept`: the byte-count ceiling, the decoded size
matching the page exactly, every non-positive argument refused) and `NotebookUndoTest` gains the
`PageReceived` case both replay directions, on `PagePasted`'s fixture shape — its own kind kept
apart from `Page` / `PagePasted` / `PageErased` the same way every other transfer-shaped kind is.
No `NotebookSession.receivePage` test, for `PageErased`'s reason above: it opens a real Room DB.
`:app` **1487 → 1564** over the arc's six phases; **2847 → 2945** across every module.

**Arc 33 "Focus", the notebook's own half:** `DoubleTapToggleRuleTest` (8) covers the collision rule
— both taps missing toggles, either tap hitting refuses it, a lone tap recorded (never two) refuses,
and the history is consumed by a decision rather than read twice. `:sn-screen` gained
`ChromeBandTest` (12, `notebook/ChromeBand` — a hidden bar's edge, a shown-but-unlaid bar
withholding the band, `rootHeight` 0, an empty or inverted range, both bars hidden at once) — the
shared piece four screens now build on, `ChromeToggle` and the `rectOf` visibility check among them.
No `NotebookActivity`-level test: the toggle wiring, the collision rule's consumption and the
`onResume` re-sync were walked on the Nomad instead, the same reason `NotebookSession`-touching
features always are. `:app` **1606 → 1614** at F1 (the arc's own notebook phase); `:sn-screen`
**69 → 81**; **3007 → 3033** across every module by the arc's close (F1's own count was 3007).

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
- **The lasso eraser is an engine tool armed from the eraser's own re-tap, not a fourth bar
  button** (arc 29 / Loop). og Notesprout gives `lassoEraser` its own toolbar slot
  (`pen · eraser · lassoEraser · lasso`); SN's bar has no room for a twelfth button once every
  extension is installed, so the same tool is reached through a **Point · Lasso** sub-bar hung
  under the eraser — the lasso re-tap popup's own precedent, one tap deeper rather than one button
  wider.
