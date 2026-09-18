# `:sn-screen` — the shared paper-screen library

*Arc 11 / J1. Read this before adding, moving, or removing anything in the module.*

SN grew a second paper surface in arc 11: the Scratch Pad, which lives in its own extension APK,
in its own process, with its own g-paper canvas. Both surfaces need the same e-ink design
resources and the same handful of screen helpers, and the alternative to sharing them is the
sibling-copy trap og Notesprout still carries — `RattaNotebookView` is a hand-maintained copy of
`GenericNotebookView`, and every fix to shared logic has to be applied to both files or one of them
silently rots. `:sn-screen` is that trap refused up front.

## What the module is allowed to depend on

g-paper (`api`, so `PaperView` and `Stroke` reach both consumers transitively) and androidx
(`core-ktx`, `appcompat`). **Never `:app`** — the module has to build with the host absent — and
**never `:extension-api`**. That second exclusion is deliberate and load-bearing: keeping the
contract out of here is what makes the host's transfer mapping and the extension's own ink mapping
two twin translations rather than one shared class that quietly becomes part of the wire format.
No Room, no SQLCipher, no serialization: nothing here knows what a `.soil` is.

`:app` depends on `:sn-screen`; so do `:ext-scratchpad`, `:ext-document`, `:ext-tags`,
`:ext-calendar`, `:ext-sketch` (arc 43, via `:ext-ink`'s `api`) and — since arc 23 / Y1 — **`:ext-ink`**, the ink-on-rows library the pad and the
calendar share (`InkWire`, `StrokeRows`, `StoreBatches`, `StrokeReadPlan`, `InkDocument`,
`InkAction`, the `InkStore` base, and — since Y4's review — the shared stroke SQL/DDL (`InkSql`),
the `InkPage` contract, the transfer session (`InkTransferSession<P, R>`) and the abstract tier-2
ink screen (`InkScreenActivity`)). `:ext-ink` is the one module that depends on **both** this and
`:extension-api` (`api` on each), which is exactly why those helpers could not live here: they are
extension-side code over the contract's `Statement` and `WireStroke` — and, since Y4,
`InkScreenActivity` extends this module's own `AppCompatActivity`, which is why `:ext-ink` also took
on `api(appcompat)` (a version both consumers already declared, no new library on the graph). It
never depends on `:app`.
g-paper is **not** declared in any consumer — it arrives through this module's `api(...)`, and the
version pin lives here.

## The namespace and the R-class flag

The module's Android namespace is `com.symmetricalpalmtree.notesproutsn.screen` — deliberately not
the app's, because two modules sharing a namespace would collide on `R` and `BuildConfig`. The
Kotlin **packages** of everything that moved are unchanged (`…notesproutsn.core`,
`…notesproutsn.notebook`), which is why the move needed no import sweep in `:app`; the only three
lines that changed are the `R` / `BuildConfig` imports of `Dialogs`, `ActionSheetDialog` and
`Slog`, which now point at the module's own.

`gradle.properties` sets **`android.nonTransitiveRClass=false`**. AGP 8.11 defaults it to
non-transitive, and without the line every moved resource falls out of `:app`'s `R` — hundreds of
compile errors. Do not remove it.

`Slog` gates on **this module's** `BuildConfig.DEBUG` (`buildFeatures.buildConfig = true`). The
app's debug build consumes the library's debug variant, so the gate means exactly what it meant in
`:app`. Any module whose tested code reaches `Slog` also needs
`testOptions.unitTests.isReturnDefaultValues = true`.

## What lives here

| Kotlin | What it is |
|---|---|
| `core/StrokeCodec`, `core/InkColorCodec` | the format-B stroke blob and the ink-colour token — the family's byte-compatible encodings |
| `core/Slog` | the debug-gated logger |
| `core/Dialogs` | the bordered-window `AlertDialog` helpers (`problem`, and the window styling every dialog routes through) |
| `core/ActionSheetDialog` | the "what do you want to do with this?" sheet — hairline-separated rows built in code, because the row *count* is the content |
| `core/TopGuard` | the top-edge guard — **0 on Ratta**, where chrome sits flush at the top |
| `core/Immersive` | system bars hidden, transient by swipe |
| `notebook/PageMath` | page-index arithmetic |
| `notebook/SelectionAnchor` | where a floating bar may sit relative to a selection |
| `notebook/PageGestures` | the finger vocabulary — flips, inserts, the two swipes, the multi-finger undo/redo taps, the long-press, and (arc 23 / Y2) `Listener.onFingerDoubleTap` — a second, independent history over the same qualifying bare taps, so a consumer can add a double-tap without touching `onFingerTap`, which stays byte-identical. Pen-gated throughout |
| `core/SwipeMath` | the one horizontal-flip rule, in pure arithmetic — shared by `PageGestures` and `ListSwipe` so a page turn means the same travel everywhere. JVM-tested |
| `core/ListSwipe` | the one-finger flip for a **paginated list** (F3): `SwipeMath` applied to a region rather than the screen, armed only inside it, finger-only, observer-only. Optional vertical callbacks (`onSwipeDown` / `onSwipeUp`, arc 37 / B6) and an optional centroid-measured `onTwoFingerSwipeDown` (B7) — the Bible reader's Contents and Recents doors; a host that leaves them null keeps the plain flip |
| `notebook/UndoRedoStack<A>` | the generic LIFO history plus its `generation` counter. The notebook's fourteen action kinds stay in `:app` as `NotebookUndo.Action`. **Arc 43 / K2 grew it a `cost: (A) -> Long = { 0 }` constructor parameter and a `budgetBytes`** — an entry the notebook or the pad never sizes stays free (the default costs everything 0), but the sketch face's raster undo tiles are real bytes, so `evictForBudget` (the Paintsprout port, ported exactly: the **oldest** costed entry evicted first, never the newest) keeps the stack under `budgetBytes` as tiles are pushed. **`pushUndoBeneath(action, sinceGeneration)`** inserts an entry **below** the stack's live top rather than on it — K5b's redo-preserving replay: an undo of a page insert/delete must not clobber whatever pixel edits already sit above it on the redo side, so the replay puts the reversed entry back where it always was rather than pushing it fresh |
| `notebook/AnchoredBar` | arc 29 / LE2 — **moved here from `:app`** (same package, `R` repointed; the three `:app` callers — the lasso popup, the tags popup, the Insert bar — untouched): the floating-bar placement primitive, one bordered row of buttons anchored under a view and clamped to the root. **Since arc 34 / L2 its button recipe is the one recipe:** `AnchoredBar.button(ctx, iconRes, hint, onClick)` is a public companion function (dimen-driven size, no ripple, no state-list animator, tooltip == content description) because `:app`'s two other floating bars — `ShapeTransformBar` and `SelectionToolbar` — had each grown a byte-identical private copy of it; both now call it, and its own `rectOf` copy is gone in favour of `PaperToolbar.rectOf`. **Arc 36 / C1 gave `show()` an optional `anchor: View = this.anchor` parameter and C3 made its anchor guard visibility-aware (`PaperToolbar.rectOf(anchor) == null` → a loud no-op, never a bar hung under a `GONE` button's stale edges)** — the collapsed chrome hangs the Insert bar and the tags popup off its own mini-toolbar / overflow buttons rather than the top bar's, because the bar button they were built on sits inside a `GONE` bar and keeps stale edges; every existing caller that omits the argument is unchanged. **Arc 44 / T3** grew it `addRow(row: View)`, beside `addButton` — a caller whose own `bar` is a *vertical* `LinearLayout` and whose children are rows rather than icon buttons (the sketch face's `PencilBar`: shade swatches and size dots, neither of them the shared button recipe) adds each row itself and `AnchoredBar` still owns the placement, the measure-before-place rule, the rects and the hit test, knowing nothing about what is inside. |
| `notebook/EraserBar` | arc 29 / LE2–LE3 — the eraser button's **Point · Lasso** sub-bar, built on `AnchoredBar`; one implementation shared by all four paper surfaces (the notebook, the sticky editor, the scratch pad, the calendar) rather than a fourth top-bar button (an eleventh 62 dp button already fills the Nomad's bar with every extension installed; a twelfth falls off the edge) |
| `notebook/PaperToolbar` | back + the three tool buttons, **binding-free**; since arc 29 / LE2 also carries `onEraserReTap` (a second tap on the armed eraser opens `EraserBar` rather than doing nothing), `onToolTapped` (an actual tool change, so a consuming screen can close floating chrome), and public `arm(tool)` (the sub-bar's pick lands here — a host-set tool is never echoed back as `onToolChanged`, so `sync` has to be called by hand); `sync` selects the eraser button under **either** eraser kind and swaps its glyph only on a change of kind (frame silence — every `onToolChanged` lands in `sync`, and re-setting the same drawable would invalidate the button for nothing). **Its companion `rectOf(v)` gained a visibility check at arc 33 / F1** — `if (v.visibility != View.VISIBLE) return null`, ahead of the existing size check — because a `GONE` view keeps its last measured width and height (trap 1): without the check a hidden bar would keep excluding ink and swallowing gestures exactly where it used to sit. `PaperChrome.pushExclusions`/`overChrome`, `FloatingSelectionBar.rects`, the sticky editor's own `overChrome` and the notebook's own `rectOf` (now `= PaperToolbar.rectOf`) all inherit the fix from this one place; `AnchoredBar`'s private `rectOf` already carried the same check independently — the rule was **promoted** into the shared function, not copied into it. **Arc 34 / L2 finished the promotion:** `AnchoredBar`, `ShapeTransformBar` and `SelectionToolbar` dropped their own copies too, so `rectOf` now exists exactly once. **Arc 44 / T3** grew it a defaulted, trailing `btnAltPen: ImageButton?` / `altPenArmed: () -> Boolean` / `onPenKindPicked: (alt: Boolean) -> Unit` / `onPenReTap: () -> Unit` set — a second **kind** of `Tool.PEN` (the sketch face's gel pen beside its pencil, both the same tool to g-paper), armed and re-tapped through `selectPen` on the same rules `select`'s eraser re-tap already established (the kind, not the tool, decides whether a tap is a change or a re-tap), `sync` testing both pen buttons against `CollapsedTools.penButtonSelected` rather than a second `tool == PEN`; every existing caller passes none of the four and is unchanged. |
| `notebook/PaperChrome` | exclusion rects and the over-chrome hit test, with the host-specific parts as suppliers |
| `notebook/FloatingSelectionBar` | an extension screen's floating selection bar (arc 23 / Y1 — the pad's own, shared so the calendar's is not a sibling copy): a row of buttons built to the one recipe, placed by `SelectionAnchor` next to the lasso box; the consumer says which buttons. `buttonAt(index)` (arc 28 / H5) hands back one built button for a consumer whose button carries **state** the bar itself cannot know — the sticky editor's Snap latch, which wears the selected border and re-words its hint exactly as the notebook's own Snap button does |
| `notebook/PenIdle` | arc 23 / Y4 — the two pen-activity gates every paper-hosting screen writes against: `whenIdle` (the frame-silence gate, re-posting at `PaperView.PEN_ACTIVE_TAIL_MS` while the pen is active) and `releaseRenderIfIdle` (`PaperView.releaseRender`'s own pen-gated contract); one copy rather than the four that had grown across the pad's and the calendar's toolbars and screens — `PaperToolbar` is trimmed to call it too. **Arc 34 / L1** brought in the last two hold-outs: `:app`'s `NotebookActivity` (whose `releaseRenderIfIdle` keeps only its `lateinit` guard on top) and `NotebookToolbar` |
| `notebook/InkSelectionBar` | arc 23 / Y4 — the ONE Send-then-Delete floating bar an ink-on-paper extension screen puts over a lasso selection, replacing the pad's and the calendar's own `*SelectionToolbar` copies; built on `FloatingSelectionBar`, Send absent (never disabled) with no notebook behind the caller |
| `notebook/ChromeBand` | arc 33 / F1 — the pure rule for the free band between a screen's two chrome bars: `of(rootHeight, top: Bar?, bottom: Bar?): IntRange?`, `Bar(shown, edge, laidOut)`. A hidden bar contributes the root's own edge (0 / `rootHeight`) rather than withholding the band; only a **shown** bar that has not laid out yet returns null (trap 2 — the pre-arc `chromeBand()`s each returned null whenever either bar's height was 0, which is exactly what a `GONE` bar reports, so every floating bar — `AnchoredBar.show`, `FloatingSelectionBar`/`SelectionToolbar`'s `show` — would have silently refused to show while the chrome was hidden). `View.asBar(edge)` builds a `Bar` from a real view — Android-typed and untested on purpose, since the rule under it is what `ChromeBandTest` (12) covers. Replaces the three per-screen `chromeBand()` lambdas (the notebook, `InkScreenActivity`, the sticky editor's two) with one pure copy |
| `notebook/ChromeToggle` | arc 33 / F1 — hides/shows a paper screen's chrome bars in one written-once flip order: `paper.releaseRender()` (skipped when `releaseRender = false`, since `onCreate` has nothing on the glass) → hiding only: `beforeHide()` (the consumer's button-anchored popups — lasso, tags, insert, eraser — come down because their button is about to go) → every bar `GONE`/`VISIBLE` (**never `INVISIBLE`** — an attached Ratta paper view keeps the pen claimed whatever a sibling's visibility, and an `INVISIBLE` bar would keep its rect) → `root.doOnNextLayout { afterLayout() }`, the consumer's `pushExclusions()`, re-reading the band and the now-visibility-aware rects. One `Slog.d` per flip, deliberately never `whenPenIdle`-gated (`isPenActive` counts hover — the bars must answer the double-tap that asked for them, not wait for the pen to leave). One copy for all four paper screens (notebook, sticky editor, scratch pad, calendar). **Arc 34 / L3 moved the two per-screen halves in here as well:** `sync(persisted)` is the resume rule (a no-op when nothing changed, never a render release — it runs before `resumeDrawing()`), and the `onChanged: (Boolean) -> Unit` constructor hook is told every real change, so the two host screens persist through it (`ChromePrefs`) instead of each writing the flag by hand after `toggle()`; the extension screens pass nothing (an extension writes nothing to disk). `initial` became the plainer `releaseRender`. **Arc 36 / C1 added `whileHidden: List<View> = emptyList()`** — views that take the *inverse* visibility of `bars` in the same flip (the corner tool button: `VISIBLE` exactly when the bars go `GONE`, so it is never up beside a bar and never absent over bare paper) — **and `beforeShow: () -> Unit = {}`**, the hide → show counterpart of `beforeHide`: the consumer takes down the rows hung off the corner button, whose button is about to disappear |
| `notebook/CollapsedTools` | arc 36 / C1–C2 — the collapsed chrome's rules, pure and JVM-tested apart from the views: `ORDER` (the four tools, fixed: Pen · Point eraser · Lasso eraser · Lasso — the two erasers stay two buttons, not a nested sub-bar, so the lasso eraser is one tap away while collapsed); `iconFor(tool, clipboardLoaded)` (the corner button's and the mini toolbar's lasso glyph — the clipboard mark exactly as the bar's own lasso button wears it, `Tool.NONE` wearing the pen); `selectedFor(tool)` (which mini-toolbar button reads as armed); `outsideTapDismisses(showing, onChrome, keep)` (nothing showing → no-op; on the collapsed chrome itself — the corner button, whose own re-tap would otherwise close-then-reopen, or the rows — → no; inside a sub-bar hung off the rows → no; anywhere else → yes); and, since C2's user call, `overflowInline(count) = count in 1..INLINE_MAX` (`INLINE_MAX` = 2) — one or two overflow entries sit on the mini toolbar itself with no `…` at all (the sticky editor's Back, the pad's Back · Send), three or more (the notebook, the calendar) go behind it. **Arc 44 / T3** grew `iconFor` a defaulted `altPen: Boolean = false` (the gel pen's `ic_ballpen` over the pencil's `ic_pen` under `Tool.PEN`, false reading exactly as before) and added `penButtonSelected(tool, altPenArmed, isAltButton)` — one rule for which of a PEN slot's two kind-buttons reads as armed, asked by both `PaperToolbar.sync` and `CollapsedChrome.sync` rather than two spellings of it; a screen with one pen passes the defaults and gets back `tool == PEN`, its old answer. `CollapsedToolsTest` (10 → 14) |
| `notebook/CollapsedChrome` | arc 36 / C1 — the collapsed chrome's view half, one copy shared by every paper screen rather than a sibling `RattaNotebookView`-shaped copy per screen. **Arc 43 / K2 gave its constructor a `tools: List<Tool> = CollapsedTools.ORDER` parameter, last with a default** — every pre-existing caller (notebook, sticky editor, pad, calendar) keeps the four-tool order unchanged, while the sketch face's own `CollapsedChrome` passes just `[PEN, ERASER]`, since it has no lasso and no lasso eraser to collapse into. The corner tool button (`knob`, `GONE` until collapsed) plus two `AnchoredBar`s — the **mini toolbar** (the four tools, then the screen's `commands`, then `…` unless `CollapsedTools.overflowInline` folds the overflow onto it instead) and the **overflow row** hung under `…`. `Entry(iconRes, hint, mirrors, onTap)` is one mini-toolbar command or overflow entry, made by `Entry.mirroring(iconRes, button, onTap)` — the hint is the bar button's own content description; `mirrors` is re-read at every open of its row (visibility, selected look, and the glyph only when its constant state differs — no allocation in the steady state); a plain entry's tap dismisses both rows and `performClick()`s `mirrors`; an entry with `onTap` is handed its own button as the anchor (`AnchoredBar.show(anchor)`) and owns dismissal itself — the notebook's Insert and Tags. `sync()` repaints the corner glyph + mini-toolbar selection **from `paper.tool`** (nothing cached; wired once into the bar's `onSynced` funnel — `PaperToolbar` / `NotebookToolbar`), `showClipboardLoaded(loaded)`, `rects()` / `contains(x, y)` (for the screen's exclusion rects and `overChrome`; the corner button included so its own tap never dismisses), `dismissOnContact(x, y, keep): Boolean` (the outside-tap rule, `keep` naming the screen's own sub-bars; answers whether the contact was spent — the notebook's paste latch), `dismiss()` / `hideOverflow()` (idempotent; both fire `onClose` first, the screen's raw hide of the sub-bars it hung off the rows, then one `onChanged` push). Opening a row is an ungated `paper.releaseRender()` (the Insert bar's rule — a deliberate tap with the pen still hovering); a tool pick releases once, through the screen's `toolbar.arm` (`onArmed`). Strings `collapsed_tools` ("Tools", the knob's content description) / `collapsed_more` ("More", the `…` button's) / `tool_pen` / `tool_lasso` (the mini toolbar's two hints — the four screens say the same words). **Arc 44 / T3** gave the constructor one more defaulted, trailing parameter, `penKinds: PenKinds? = null`, for a screen whose PEN slot is two **kinds** rather than one tool — the sketch face's pencil and gel pen, both `Tool.PEN` to g-paper. `PenKinds` bundles the alt button's glyph and hint, `altArmed` (read live, never cached), `onPick`, an optional `onPrimaryReTap(anchor)` (a re-pick of the already-armed primary kind hands the caller this row's own button, the notebook Insert bar's precedent, so its sub-bar hangs under the button that was actually tapped rather than a `GONE` top-bar button's stale edges) and an optional `primaryIcon: () -> PenIcon` — a screen-painted glyph (the sketch face's shade-filled pencil) that replaces the plain resource one on both the row's own button and the corner knob, `PenIcon(token, newDrawable)` swapping only when its token changes, the family's frame-silence rule extended to a picture the module itself did not draw. The alt button is built immediately after the primary one, so the row reads Pencil · Pen · Eraser; `sync()`/`syncKnob()` test both against `CollapsedTools.penButtonSelected` rather than `tool == selected`. Every existing caller passes no `penKinds` and is unchanged |

Resources: `values/{colors,dimens,styles,themes}`, `values-sw720dp/dimens`,
`values-sw960dp/dimens` (the Manta's card-grid minimum only — see `docs/library.md` § The grid), 59
chrome `ic_*.xml` (grown one arc at a time since J1's move; the latest are arc 44 / T3's
`ic_ballpen.xml` (Tabler's `ballpen`, the sketch face's gel pen and the shared vocabulary's own
copy — one glyph, not a fork, the reason it lives here rather than in `:ext-sketch`) and
`ic_pen_fill.xml` (`ic_pen`'s own outline path closed to a filled body, the layer `PencilIcon`
tints with the armed shade — the Pencil button's report, the corner button's and the mini row's
too); before them arc 29 / LE2's
`ic_lasso_eraser` — copied byte-for-byte from og's `drawable/` rather than drawn fresh (the standing
"check first" trap), the eraser sub-bar's Lasso button and the eraser top-bar button's own glyph
while that eraser is armed; before it arc 28 / H4's
`ic_resize` (Tabler, the lasso bar's **Transform** button — a lone selected shape's one verb, D9);
before it arc 24 / Z5b's
`ic_backspace` — Tabler's own, the keypad's rub-out key — before it arc 24 / Z2's
`ic_calendar_event` (Tabler `calendar-event`, the calendar's own Events door), and before that arc
23 / Y4's `ic_calendar_star`, `ic_calendar_month`, `ic_calendar_week` and `ic_calendar_day` (Tabler
`calendar` with two ruled lines — a derivative, the `ic_notebook_plus` precedent), the calendar's
Today button and its three view latches — `ic_calendar` itself dates to Y1 and is the extension's
door on both host bars),
the button/border/radio drawables the moved styles reference, `Widget.Notesprout.Toggle` +
`toggle_pill` (arc 24 / Z5b — the yes/no pill; the style's 56dp width **is** the drawable's geometry,
since the knob insets are computed from it, so change both or neither), `Widget.Notesprout.DialogButton`
(arc 24 / Z3, the user's eye on the discard dialog — a 16dp `layout_marginStart` plus 16dp of side
padding, so AppCompat's own 8dp button-bar spacing no longer reads as one control; every two-button
dialog in the family inherits the air),
and a `strings.xml` holding `ok`
and `cancel` — the two strings the moved helpers reference themselves — plus, since arc 29 / LE2,
`eraser_point` / `eraser_lasso`, the `EraserBar`'s two long-press hints (here, not in `:app`,
because all four paper surfaces share the one bar), and, since arc 36 / C1, `collapsed_tools` /
`collapsed_more` / `tool_pen` / `tool_lasso` — the corner button's, the `…` button's and the mini
toolbar's Pen / Lasso hints, here for the same reason: `CollapsedChrome` is the one copy all four
screens share. Every other string stays in the consumer that owns the screen.

**`ic_launcher_foreground.xml` and every `mipmap-*` stay in `:app`.** The launcher glyph is the
host's identity, not shared chrome; the Scratch Pad extension draws its own.

## Two helpers that were written fresh, not moved

- **`PaperToolbar`** is not `NotebookToolbar` relocated. The notebook's is hard-bound to
  `ActivityNotebookBinding` and carries the clipboard-loaded icon swap and the lasso re-tap, so it
  stays in `:app`. `PaperToolbar` takes the views themselves and does only what a spartan second
  surface needs. Both obey the same two rules: release the render first but **pen-gated**, and
  `sync` is the truth rather than our taps (g-paper arms and restores tools on its own).
  **Arc 43 / K2 made its `btnLasso: ImageButton?` constructor parameter nullable, default `null`**
  — the sketch face has no lasso at all (decision 12: gestures only, no arrows, and the user's
  2026-09-15 call that the face never gets one), so `SketchToolbar` builds a `PaperToolbar` over
  Pen · Eraser alone; every existing caller that passes a real `btnLasso` is unchanged.
- **`PaperChrome`** is not `NotebookActivity.pushExclusions` relocated. The notebook's also carries
  `paper.snapMarginPx` (arc 9) and reads its Contents and Recents flows by name; it stays exactly
  where it is, and adopting the helper in the notebook was explicitly not arc 11's business. What
  the two share is the *shape*, so the host-specific parts arrive as `extraRects` /
  `extraContains` / `blockAll` suppliers.

**Test count.** `:sn-screen`'s own JVM suite sat at **69** tests as of arc 28 — the pure geometry
(`PageMathTest`, `SelectionAnchorTest`, `SwipeMathTest`) and `PageGestures`'/`ListSwipe`'s pure
rules, unchanged by the arc. Arc 28's own additions here are small and structural rather than
tested in this module: `FloatingSelectionBar.buttonAt` and the `ic_resize` glyph above — the three
new object kinds themselves, their stores and their JVM suites, are core, in `:app` — see
[`docs/objects.md`](objects.md) and [`docs/notebook.md`](notebook.md). Arc 33 / F1 raised it to
**81** — `ChromeBandTest` (12), the whole of `ChromeBand`'s rule (a hidden bar's edge, a shown-but-
unlaid bar withholding, `rootHeight` 0, an empty or inverted range, both bars at once). `ChromeToggle`
and the `rectOf` visibility check are Android-typed and have no JVM suite of their own — the pure
rule under each is what is tested (`ChromeBand`'s own trap 2, and `AnchoredBar`'s already-covered
visibility rule that `rectOf`'s check now shares). Arc 36 raised it to **91** — `CollapsedToolsTest`
(10): the tool order and glyph, which button reads as armed, the outside-tap rule, and (C2's
addition) `overflowInline`'s inline-vs-`…` boundary at `INLINE_MAX`. `CollapsedChrome` itself, like
`ChromeToggle`, is Android-typed and untested here on purpose — the pure decisions it calls are what
`CollapsedToolsTest` covers. Arc 44 / T3 raised the module to **118** — `CollapsedToolsTest` 10 →
**14**: which of the PEN slot's two kind-buttons reads as armed and only under `Tool.PEN`
(`penButtonSelected`), the alt button's `ic_ballpen` glyph and only under `Tool.PEN`, the corner
button's painted report following the *primary* pen button rather than a second "is the pencil on
the paper?", and a screen with one pen still getting the rule it always had. `AnchoredBar.addRow`,
`PaperToolbar`'s `btnAltPen` set and `CollapsedChrome.PenKinds`/`PenIcon` are Android-typed and
untested here, like `CollapsedChrome` itself — the pure rule under each is `CollapsedToolsTest`'s.

## When you change something here

A change to a shared helper reaches two screens in two processes. The notebook is the older
consumer and the one with a full test suite behind it — check it as well as the pad, and keep
anything notebook-specific in `:app` rather than growing a parameter here for it.

`ChromeBand` and `ChromeToggle` reach **five** consumers rather than two: `:app`'s
`NotebookActivity` and `StickyEditorActivity` build their own `ChromeToggle` directly, and
`:ext-ink`'s `PaperScreenActivity` — the store-agnostic chrome/handoff base `InkScreenActivity` was
split out of at arc 43 / K2 (855 → 428 lines; chrome, the collapsed bar, the eraser sub-bar, touch
dispatch, chrome-hidden state and lifecycle stayed on `PaperScreenActivity`, while store, strokes,
undo, selection and Send stayed up on `InkScreenActivity`, which now extends it with **zero**
behaviour change to the pad or the calendar) — builds one in its own base class (`initChrome()`)
that the scratch pad, the calendar **and, since K5, `:ext-sketch`'s `SketchActivity`** all inherit
unchanged — a sixth copy was exactly the sibling-copy trap this module exists to refuse, so the
toggle's one flip order is checked against all five screens, not the notebook alone, before it
changes. `CollapsedChrome` and `CollapsedTools` reach the same five screens the same way (arc 36 /
C1–C2, K2): the notebook builds its own directly, `StickyEditorActivity` the notebook's shape
without commands, and `PaperScreenActivity`'s `initCollapsed()` builds the pad's, the calendar's and
the sketch face's (the face's own `[PEN, ERASER]` `tools` override above) — check all five before
changing the corner button's or the mini toolbar's rules.
