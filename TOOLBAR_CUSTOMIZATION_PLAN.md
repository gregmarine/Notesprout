# Notebook Toolbar Customization — Implementation Plan

> **Status:** Core sessions 1–7 ✅ DONE. The feature is shipped and documented in `CLAUDE.md`
> ("Toolbar Customization System"). **Session 8 (UI/UX polish backlog) is still open** — kept
> deliberately. Do not delete this file until Session 8 is closed out and the final wrap runs.

A "New Branch" that turns the fixed top toolbar in `NotebookActivity` into a fully
customizable one: reorderable buttons, show/hide, edge anchoring (top/right/bottom/left),
a draggable floating bar, a mini bar, and a finger gesture to hide/show it.

---

## Approved design decisions

These were settled before planning. Do not relitigate without discussion.

1. **Modes are orthogonal, not exclusive.**
   - `placement` ∈ `{ TOP, RIGHT, BOTTOM, LEFT, FLOAT }` — one axis.
   - `mini` is an independent on/off toggle layered on top of `placement`.
   - So every combination is reachable: *full @ top*, *mini @ left*, *full @ float*,
     *mini @ float*, etc.
2. **Customize UI = drag-reorder + per-button show/hide toggles + a mini-set picker**, in
   one "Customize Toolbar" dialog. Entry point: a new **gear button** in the toolbar
   (overflow-eligible like any other button).
3. **Float is draggable and persists** its `{x, y, axis}`. Length ≈ 75% of the matching
   screen dimension (width when horizontal, height when vertical).
4. **Scope is global** (one config for all notebooks). Every button is movable/hideable;
   **dividers are auto-managed**; **Close stays pinned** (always present, can't be hidden —
   the user must never lose their way out of a notebook).
5. **Toggle gesture = one-finger double-tap on the canvas** (finger only, never stylus).
   It's a setting, **default ON**. Tap-vs-drag keeps it clear of the existing one-finger
   page-swipe and two-finger page-insert gestures.
6. **Hidden-state safety net = a small peek tab** at the anchored edge. Tap it (or
   double-tap) to restore. Guarantees the toolbar can always come back.
7. **Vertical (left/right) overflow is generalized** — `ToolbarOverflowManager` learns to
   pack overflow beside a vertical bar, mirroring today's horizontal behavior.

### Resolved: no user-facing spacer

- **The legacy right-aligned page-control group is dropped.** The old weighted `Space`
  (an artifact from before the full toolbar existed) is removed. Buttons pack tight against
  the **leading edge** (left when horizontal, top when vertical). There is no user-facing,
  reorderable spacer — the order is a flat first-to-last list.
- **One exception — the overflow button stays pinned to the trailing edge** (right in
  horizontal, bottom in vertical). This is achieved with a **manager-owned internal weight**
  inserted just before `btnOverflow` — invisible to the user, never part of `order`, never
  reorderable. (Today's `Space` already serves exactly this purpose for the overflow button;
  we keep only that internal role.)

---

## Current architecture (what we're building on)

- **Layout:** `res/layout/activity_notebook.xml` — root `FrameLayout`; `drawingContainer`
  fills the screen; `drawingToolbar` (`LinearLayout`, horizontal, `match_parent` × 56dp,
  `layout_gravity=top`) overlays it; `overflowMenu` hangs below the toolbar.
- **Buttons:** `androidx.appcompat.widget.AppCompatImageButton`, `Widget.Notesprout.ToolbarButton`
  style (44dp / 36dp on sw360). Dividers are inline `View`s (1dp × 28dp, inkBlack). A
  weighted `Space` right-aligns the page-control group.
- **Overflow:** `notebook/ToolbarOverflowManager.kt` — **moves** (never clones) views into a
  menu when they don't fit, so `isSelected`/listeners survive. Horizontal-only today.
- **BOOX pen exclusion:** `OnyxNotebookView.applyLimitRect()` calls
  `touchHelper.setLimitRect(limitRect, [Rect(0,0,width,toolbarHeight)])`. The exclusion is a
  single top-anchored, full-width rect set via `setToolbarHeight(px)`. `GenericNotebookView`
  has the matching `setToolbarHeight` no-op/area logic.
- **Touch routing:** `NotebookActivity.dispatchTouchEvent` hit-tests the toolbar with
  `event.y < binding.drawingToolbar.bottom` (assumes top), releases the EPD overlay on
  finger touches in the bar, manages overflow dismiss, page-swipe, text-placement cancel,
  lasso-popup dismiss.
- **Position-dependent extras that assume a top bar:** `tvPageIndicator` (bottom-end),
  `computeLassoToolbarAnchor()`, `updateFloatingSelectionToolbar()` (uses
  `drawingToolbar.height` as `minY`), lasso popup anchor.
- **Prefs precedent:** `data/recents/RecentsManager.kt` — `object` over a single
  `SharedPreferences` key holding `kotlinx.serialization` JSON. We mirror this exactly.

---

## Target architecture

### New package: `data/toolbar/`

```kotlin
@Serializable enum class ToolbarPlacement { TOP, RIGHT, BOTTOM, LEFT, FLOAT }
@Serializable enum class ToolbarAxis      { HORIZONTAL, VERTICAL }   // for FLOAT

@Serializable
data class ToolbarConfig(
    val placement: ToolbarPlacement = TOP,
    val order: List<String>  = ToolbarButtonRegistry.DEFAULT_ORDER,  // stable button keys
    val hidden: Set<String>  = emptySet(),                            // hidden keys
    val miniSet: List<String> = ToolbarButtonRegistry.DEFAULT_MINI,   // 3–5 keys
    val miniEnabled: Boolean = false,
    val floatX: Float = -1f,                  // -1 ⇒ uninitialised → center on first show
    val floatY: Float = -1f,
    val floatAxis: ToolbarAxis = ToolbarAxis.HORIZONTAL,
    val toggleGestureEnabled: Boolean = true,
    val collapsed: Boolean = false,           // toolbar currently hidden (peek tab showing)
)
```

- **`ToolbarPreferencesManager`** — `object`, `SharedPreferences("notesprout_toolbar_prefs")`,
  single key `config`, JSON via `kotlinx.serialization`. `load(context): ToolbarConfig`
  (tolerant of malformed/absent → defaults), `save(context, config)`. Same shape as
  `RecentsManager`.
- **`ToolbarButtonRegistry`** — the single source of truth mapping a **stable string key**
  (`"pen"`, `"eraser"`, `"close"`, `"undo"`, …) to: the `R.id` of its `AppCompatImageButton`,
  its icon `R.drawable` + content-description/label (for the customize dialog), its **group**
  (for auto-divider insertion), and whether it is **pinned** (`close`). Holds `DEFAULT_ORDER`
  (current XML button order, no spacer) and `DEFAULT_MINI`
  (e.g. `pen, eraser, undo, lasso, pageIndex`). Keys are append-only and **must never change**
  once shipped — they're persisted.

### New class: `notebook/ToolbarLayoutManager.kt`

Owns arranging the *existing* button Views (declared once in XML, listeners wired in
`NotebookActivity` as today) into the toolbar container per `ToolbarConfig`. Move-not-clone,
exactly like the overflow manager, so all state/listeners survive.

Responsibilities:
- Resolve the **active visible key list** = (mini ? `miniSet` : `order` minus `hidden`),
  filtered to keys whose views exist, Close always included.
- Set container **orientation** + **size/gravity** per `placement`.
- Insert **auto-dividers** between consecutive visible buttons whose `group` differs
  (orientation-aware: 1dp × 28dp horizontal, 28dp × 1dp vertical).
- Hand the resulting children off to `ToolbarOverflowManager` for fit/overflow. The overflow
  manager keeps a **manager-owned internal weight** before `btnOverflow` so the overflow
  button stays pinned to the trailing edge (right when horizontal, bottom when vertical). This
  is the only "spacer" in the system — never user-facing, never in `order`.
- Expose the toolbar's current **exclusion rect** (in `drawingContainer` coords) so the
  Activity can push it to the drawing view.

### Generalized exclusion API (both drawing views)

Replace `setToolbarHeight(px: Int)` with **`setToolbarExclusion(rect: Rect?)`** (null/empty ⇒
no exclusion). `OnyxNotebookView.applyLimitRect()` uses the stored rect directly instead of
`Rect(0,0,width,toolbarHeight)`. Because `drawingToolbar` and the drawing view share the same
origin/size inside the root `FrameLayout`, the toolbar's bounds *are* the exclusion rect.
Overflow-open / float / peek-tab all just pass a different rect.

---

## Sessions

Each session ends with: **build clean → install on G10 → user verifies → fix/rebuild/reinstall
on report → on user "tests pass", commit (no push) with a message naming the work and
`Session x/N`.** No pushing until the final session.

`N = 7` core coding sessions, then **Session 8** (UI/UX polish — an idea backlog opened once the
feature is usable), then a wrap step. Sessions 1–7 keep their original `x/7` labels; Session 8 is an
appended exploration session.

---

### Session 1/7 — Foundations: prefs store, button registry, layout manager (parity) ✅ DONE

**Goal:** Introduce the data model and the layout manager driving the *existing* top bar.
Default config reproduces today's toolbar exactly. No new UI yet.

**Steps**
1. Create `data/toolbar/ToolbarPlacement.kt`, `ToolbarAxis.kt`, `ToolbarConfig.kt` (@Serializable).
2. Create `data/toolbar/ToolbarPreferencesManager.kt` (mirror `RecentsManager`).
3. Create `notebook/ToolbarButtonRegistry.kt` — keys, R.id map, labels/icons, groups, pinned
   flag, `DEFAULT_ORDER` (current XML button order, **no spacer**), `DEFAULT_MINI`.
4. Create `notebook/ToolbarLayoutManager.kt` — arrange-by-config (order + hidden + auto-dividers +
   orientation), then delegate to `ToolbarOverflowManager` (which owns the internal trailing
   weight for the overflow button). **This session: horizontal top only** — feed it the default
   config; output equals today's bar **except** the page controls now pack left with the other
   buttons (the legacy right-edge gap is gone). Overflow button stays right-aligned.
5. Wire into `NotebookActivity.onCreate`: load config, build `ToolbarLayoutManager`, apply.
   Keep the existing `doOnLayout`/overflow wiring, routed through the new manager.
6. Remove the hardcoded inline dividers from XML (now generated) — or keep them as the default
   group markers; decide during implementation to keep the diff minimal.

**Test on G10:** Toolbar behaves as today **except** the page controls (insert/delete/copy/paste
page) now sit inline with the other buttons instead of floating to the right edge; the overflow
button is still right-aligned. Overflow works on rotation; writing/erase/lasso unaffected.

**Commit:** `🌱 Customizable toolbar: prefs store + button registry + layout manager (Session 1/7)`

---

### Session 2/7 — Customize Toolbar dialog: reorder + show/hide ✅ DONE

**Goal:** Make order + visibility user-editable and persistent.

**Steps**
1. New gear button `btnToolbarSettings` (Tabler `adjustments` / `settings`) added to XML +
   registry; wire a click listener in `NotebookActivity`.
2. `notebook/CustomizeToolbarDialog.kt` — `AlertDialog` (per CLAUDE.md dialog rules:
   `shape_bordered`, `setElevation(0f)`, IME handling). A vertical reorderable list:
   each row = drag handle (☰) + label + a show/hidden toggle. Close row's toggle is disabled.
   Drag-reorder via a simple long-press/handle drag (no Material; custom touch or a minimal
   `ItemTouchHelper` on a plain `RecyclerView` — confirm RecyclerView is already a dep).
3. A "Mini set" section stub (full picker lands in Session 6) — or defer entirely to S6.
4. On Save: build new `ToolbarConfig`, `ToolbarPreferencesManager.save`, re-apply live via
   `ToolbarLayoutManager`. "Reset to defaults" action.
5. Layouts: `dialog_customize_toolbar.xml`, `item_toolbar_customize_row.xml`. New icon
   `ic_adjustments.xml` (Tabler).

**Test on G10:** Reorder buttons, hide a few, save → toolbar updates immediately and persists
across notebook reopen. Close can't be hidden. Overflow still correct after reordering.

**Commit:** `🌱 Customizable toolbar: reorder + show/hide dialog (Session 2/7)`

---

### Session 3/7 — Anchoring: top & bottom ✅ DONE

**Goal:** Let the bar anchor to the bottom edge (still horizontal). Establishes the
placement plumbing without the orientation flip.

**Steps**
1. Add placement control to the customize dialog (segmented buttons; only TOP/BOTTOM live
   this session, LEFT/RIGHT/FLOAT shown but disabled or added in S4/S5).
2. `ToolbarLayoutManager` sets `layout_gravity` (top vs bottom) and selects an **edge-aware
   border background** (border on the inner edge — bottom-border for top bar, top-border for
   bottom bar). New drawables `toolbar_background_top`/`_bottom` (inkBlack 1dp).
3. Generalize the BOOX exclusion: replace `setToolbarHeight` with `setToolbarExclusion(Rect)`
   in both drawing views; Activity computes the toolbar's rect and pushes it.
4. Generalize `dispatchTouchEvent` toolbar hit-test from `event.y < bottom` to a rect contains
   check (helper `isTouchInToolbar`). Overflow menu opens **upward** when bar is at the bottom.
5. Make position-dependent UI placement-aware: `tvPageIndicator` (avoid the bar),
   `updateFloatingSelectionToolbar` `minY`/`maxY`, lasso popup + overflow anchor.

**Test on G10:** Switch top↔bottom. Pen excluded under the bar in both. Overflow opens the
right direction. Page indicator / lasso toolbar don't collide with the bar.

**Commit:** `🌱 Customizable toolbar: top & bottom anchoring (Session 3/7)`

---

### Session 4/7 — Vertical anchoring: left & right (+ vertical overflow) ✅ DONE

**Goal:** Anchor to left/right as a vertical bar; generalize overflow to vertical.

**Steps**
1. `ToolbarLayoutManager`: vertical orientation, `match_parent` height × 56dp width, start/end
   gravity, **vertical dividers** (28dp × 1dp). Edge-aware border
   (`toolbar_background_left`/`_right`).
2. `ToolbarOverflowManager`: generalize to an `axis` — the internal trailing weight becomes a
   vertical weight so `btnOverflow` pins to the **bottom**; pack overflow into a menu **beside**
   the vertical bar (rows become columns; cut-point logic uses height instead of width). Keep the
   move-not-clone contract and the double-divider guard.
3. Exclusion rect already generalized in S3 — verify it covers the vertical bar + open overflow.
4. Verify all S3 position-dependent UI also handles left/right (floating selection toolbar must
   not sit under a side bar; page indicator placement).
5. Enable LEFT/RIGHT in the customize placement control.

**Test on G10:** Anchor left and right. All buttons reachable (overflow beside the bar on the
7"-class layout if needed — verify on G10 it simply fits). Writing excluded under the bar.
Lasso/floating toolbar positions sane.

**Commit:** `🌱 Customizable toolbar: left & right vertical anchoring + vertical overflow (Session 4/7)`

---

### Session 5/7 — Float mode ✅ DONE

**Goal:** A draggable floating bar at ~75% of the matching screen dimension; position persists.

**Steps**
1. Enable FLOAT in the placement control + an **axis toggle** (horizontal/vertical) shown only
   for Float.
2. `ToolbarLayoutManager` Float branch: `wrap_content` on the cross axis, fixed length =
   `0.75 × (screenW or screenH)` on the main axis per `floatAxis`; `shape_bordered` background
   (border all around, inkBlack); position from `floatX/floatY` (center on -1).
3. Drag-to-move: a drag handle region (or long-press-drag on the bar background, not on
   buttons) updates `layout_gravity=top|start` + margins; clamp to screen; on drag end persist
   `floatX/floatY`. Update the exclusion rect live during/after drag.
4. Overflow inside a floating bar: shorter length ⇒ more overflow; verify menu anchors to the
   bar (below for horizontal, beside for vertical) and the exclusion covers it.

**Test on G10:** Switch to Float, drag it around, reopen notebook → same spot. Pen excluded
under the floating bar (and its overflow). Both axes work.

**Commit:** `🌱 Customizable toolbar: draggable floating mode (Session 5/7)`

---

### Session 6/7 — Mini toolbar ✅ DONE

**Goal:** A mini toggle showing a compact button set. **Revised during the session:** mini is
**float-only** (not "any placement") and the count rule changed — **Close + the gear are always
present and don't count; the user picks up to 5 extra buttons (7 max total), no minimum.**

**What shipped**
1. Mini-set picker in the customize dialog: per-row **Mini** toggle marks membership, capped at 5
   extras (no minimum). Close/gear show no toggle (always in mini). Persists `miniSet` (extras only,
   excludes Close/gear; saved in full-list order).
2. **Mini on/off toggle** in the dialog (Full/Mini) **plus** a long-press on the gear for a fast
   switch. Both are **float-only** — the dialog section + per-row toggles only appear when Float is
   the selected placement, and the long-press is a no-op when anchored. `miniEnabled` persists.
3. `ToolbarLayoutManager.resolveVisibleKeys`: when `miniEnabled` **and** `placement == FLOAT`, the
   visible list is **Close (leading) → up to 5 chosen → gear (trailing)**. Close is force-included
   here (kept as the way out); the gear is force-included as the only path back to the dialog.
4. A mini float **hugs its content** — `WRAP_CONTENT` on the main axis instead of the 75% length, so
   no trailing blank space. The weighted overflow spacer collapses to zero in a wrap-content bar.

**Test on G10:** ✅ Float + Mini hugs its buttons and drags/persists; pick up to 5 extras (7 total),
6th blocked; deselect all → just Close + gear; anchored placements show no mini UI and ignore the
flag; long-press gear flips Full↔Mini in Float.

**Commit:** `🌱 Customizable toolbar: mini toolbar mode (Session 6/7)`

---

### Session 7/7 — Show/hide gesture ✅ DONE

**Goal:** Hide the toolbar with a one-finger double-tap; restore with the same gesture.

**Revised during the session:** the planned **peek tab and `toggleGestureEnabled` on/off setting
were both dropped.** Once the double-tap toggles *both* ways they're redundant, and the setting was
a foot-gun — it could only be reached while the bar was visible, so disabling it while hidden would
strand the user. The double-tap is now **always active**: the single, symmetric way to hide and
restore the bar, so nothing can strand you.

**What shipped**
1. `handleToolbarToggleGesture()` — manual double-tap timing in `dispatchTouchEvent`, **finger-only**
   (stylus never reaches it, so writing/erasing are untouched). A "tap" is short, near-stationary
   (≤ `scaledTouchSlop`), single-pointer, and not on any toolbar chrome (bar / open overflow menu /
   floating-selection / lasso-popup); two taps within `getDoubleTapTimeout()` and `scaledDoubleTapSlop`
   fire the toggle. The movement + single-pointer guards keep it clear of the one-finger page-swipe
   and two-finger page-insert gestures.
2. `toggleToolbarCollapsed()` flips + persists `collapsed`; `applyCollapsedState()` hides/shows the bar
   (closing any open overflow first) and releases the EPD overlay. While collapsed the toolbar
   exclusion rect is **empty** (`Rect()`), leaving the whole canvas writable.
3. `collapsed` is restored on notebook open via `root.doOnLayout` (registered after the overflow-init
   `doOnLayout` so the bar's fit is computed while still visible, before it's hidden).
4. Removed the now-unused `ToolbarConfig.toggleGestureEnabled` field — old saved configs deserialize
   fine via `ignoreUnknownKeys`.

**Test on G10:** ✅ Double-tap (finger) hides the bar; double-tap again restores it. Whole canvas
writable while hidden; pen never blocked. Never fires from the stylus; never collides with page-turn
swipes or two-finger insert. Works across all placements (top/bottom/left/right/float). Collapsed
state persists across notebook reopen.

**Commit:** `🌱 Customizable toolbar: double-tap hide/show gesture (Session 7/7)`

---

### Session 8 — UI/UX polish & refinements (idea backlog)

**Goal:** A living catch-all for UI/UX ideas that surfaced once the feature was usable in the hand.
Functionality (Sessions 1–7) is the priority; this session is where we revisit *feel*. Capture ideas
here as they come up so none are lost, then triage into concrete steps before implementing.

**Ideas backlog (to be fleshed out / discussed):**
- **Pin the gear button (always shown).** The "Customize Toolbar" gear must never be hideable — if a
  user hides it, they lose the only way back into the customize dialog. Treat it like Close: keep it
  in the bar regardless of config (and disable its show/hide toggle in the dialog, like Close's).
  Open question: should the gear *also* be reachable from a second entry point (e.g. overflow menu or
  a long-press) as a belt-and-suspenders recovery, or is pinning it sufficient? (Pinning alone is the
  simplest and probably enough.)
- **Split-panel customize dialog (grid, not list).** Redesign the dialog into two stacked panels:
  - **Top panel = "Showing"** — the currently-visible buttons, arrangeable in order.
  - **Bottom panel = "Hidden / Available"** — buttons not currently in the bar.
  - Each panel is a **grid** of icon buttons (not a vertical list). Drag a button between panels to
    show/hide it; drag within the top panel to reorder. This makes order + visibility a single direct
    manipulation instead of separate reorder + toggle actions.
  - Considerations to work through: grid drag-reorder is more involved than the current list (2-D
    target slots, reflow); still hand-rolled (no RecyclerView); how Close/gear pinning reads in a grid
    (locked cells?); how the mini-set picker (Session 6) composes with this layout; cross-panel drag
    must preserve the move-not-clone + key-stability contracts.
- **Remove the toolbar dividers.** Revisit whether the auto-managed group dividers earn their keep —
  consider dropping them entirely for a cleaner, calmer bar. Just an idea to evaluate; not yet decided.

**Process:** Each idea gets a short note now; we discuss + scope before building. May split into
multiple commits. Same session protocol (build → install on G10 → verify → commit, no push).

**Commit(s):** `🌱 Customizable toolbar: UI/UX polish — <what> (Session 8)`

---

### Wrap — documentation + final commit + push

After Session 7 passes on G10:
1. Add a **"Toolbar Customization System"** section to `CLAUDE.md` documenting: the prefs store
   (`notesprout_toolbar_prefs`), `ToolbarConfig` shape, the registry/layout-manager split, the
   generalized `setToolbarExclusion` contract, placement/float/mini/gesture behavior, and the
   key-stability rule.
2. Delete this `TOOLBAR_CUSTOMIZATION_PLAN.md`.
3. Commit (`🌱 Document toolbar customization; complete New Branch`) and **push everything**.

---

## Risks & watch-items

- **EPD exclusion correctness** is the highest-risk thread — every placement/float/overflow/peek
  state must push the right rect or the pen will draw under the bar (or be blocked where it
  shouldn't). Verify visually on G10 each session via the `applyLimitRect` Slog line.
- **Move-not-clone invariant** must hold across both managers — never recreate button Views, or
  `isSelected`/listeners break.
- **Persisted key stability** — `ToolbarButtonRegistry` keys are append-only forever.
- **Gesture interference** — double-tap must lose cleanly to page-swipe/insert; finger-only and
  tap-vs-drag separation is the guard.
- **No new deps / no Material** — if drag-reorder needs `RecyclerView.ItemTouchHelper`, confirm
  `recyclerview` is already on the classpath before relying on it; otherwise hand-roll drag.
- **sw360 (Palma2 Pro)** uses 36dp buttons — vertical/float sizing must read the actual measured
  button size, not assume 44dp.
