# Toolbar System

> Referenced from `CLAUDE.md`. Covers the base toolbar, the overflow system, and the full
> user-customization layer.

## Base Toolbar

- Icons: Tabler Icons, stroke-based, `@color/inkBlack`, 24dp VectorDrawables in `res/drawable/ic_*.xml`. New icons must come from Tabler or match the Tabler stroke style — no filled/solid icon sets.
- `bg_toolbar_button` StateListDrawable: default = white fill, no border; selected/activated/pressed = white fill + 1.5dp black border
- `Widget.Notesprout.ToolbarButton` style: 44dp, `bg_toolbar_button`, 10dp padding; overridden to 36dp/7dp in `res/values-sw360dp/` for Palma2 Pro
- Pen/eraser buttons: `isSelected = true` for persistent active-tool state
- Dividers: `@color/inkBlack`, 1dp × 28dp
- Undo/Redo: statically always-enabled — empty stack silently does nothing (matches native BOOX behavior)

### Active Tool Persistence (`notebook/ToolPreferencesManager.kt`)

The last-used drawing tool is persisted across notebook switches and app restarts via
`ToolPreferencesManager` (SharedPreferences `"notesprout_tool_prefs"`, key `"active_tool"`). The
stored value is the name of the `ActiveTool` enum: `PEN`, `ERASER`, `LASSO`, `LASSO_ERASER`.

- **Saved** when the user taps a tool button: pen saves `PEN`; eraser toggles between `ERASER` and
  `PEN`; lasso saves `LASSO` on enter; lasso eraser saves `LASSO_ERASER` on enter. Exiting a lasso
  mode via the pen or eraser button is covered by those buttons' own save calls.
- **Restored** in `NotebookActivity.onCreate` immediately after `drawingView` is created (so lasso
  modes can call `enterLassoMode()` / `enterLassoEraserMode()` safely). Absent or unrecognised
  values default to `PEN`.
- Mirrors the `SnapPreferences` pattern — not in `notesprout.db`, not in any `.soil`.

---

## Toolbar Overflow System (`notebook/ToolbarOverflowManager.kt`)

- If all buttons + dividers fit, `btnOverflow`/`dividerOverflow` stay `GONE`. Otherwise `btnOverflow` (Tabler "dots") appears at the far right; overflowed buttons move into `overflowMenu` — a vertical `LinearLayout` below the toolbar with `shape_bordered` background.
- **Move-not-clone:** actual `View` instances are moved (no cloning) — `isSelected` state, icon state, and click listeners are preserved with zero extra wiring.
- **Cut-point:** sums natural widths left-to-right; finds the largest prefix fitting in `availableWidth - overflow controls`; if the last visible item is a divider, steps back one to prevent a double-divider. Greedy row packing in the overflow menu.
- **Recalc triggers:** `doOnLayout` (first layout) + `addOnLayoutChangeListener` on the toolbar (fires on rotation, closes menu first).
- **Dismiss rules (in `dispatchTouchEvent`):** touch on `btnOverflow` → toggle; inside overflow menu → close, do NOT consume; inside toolbar → close, do NOT consume; anywhere else → close AND consume (must not start a stroke).
- `releaseRender()` called on any finger `ACTION_DOWN` in the toolbar or the open overflow menu.

---

## Toolbar Customization System

The notebook toolbar is fully user-customizable: button order, show/hide, edge anchoring, a draggable
floating bar, a mini bar, and a double-tap hide gesture. **Scope is global** — one config for every
notebook. The XML (`activity_notebook.xml`) still declares every button **once**; `NotebookActivity`
wires the listeners; the customization layer only **rearranges the existing views** (move-not-clone),
so `isSelected` state, icon state, and listeners always survive.

> Sessions 1–7 shipped; this section is the reference. Remaining Session-8 UI/UX polish ideas live in
> `BACKLOG.md` ("Toolbar Customization — Session 8").

### Prefs store + config (`data/toolbar/`)

- **`ToolbarPreferencesManager`** — `object` over `SharedPreferences("notesprout_toolbar_prefs")`,
  single key `config` holding `kotlinx.serialization` JSON of one `ToolbarConfig`. Mirrors
  `RecentsManager` / `SortPreferencesManager` — **not** in `notesprout.db`, **not** in any `.soil`.
  `load()` is tolerant (malformed/absent → defaults); `Json { ignoreUnknownKeys = true }` so a removed
  field never breaks an old saved config.
- **`ToolbarConfig`** (`@Serializable`): `placement` (`TOP/RIGHT/BOTTOM/LEFT/FLOAT`), `order`
  (full button order as stable keys), `hidden` (Set), `miniSet` (≤5 *extra* keys), `miniEnabled`,
  `floatX`/`floatY` (-1 = uninitialised → center), `floatAxis` (`HORIZONTAL/VERTICAL`), `collapsed`.
  The default value reproduces today's full top bar.

### Registry + layout manager split (`notebook/`)

- **`ToolbarButtonRegistry`** — single source of truth: each `ButtonSpec` maps a **stable string key**
  → `R.id`, icon, label, **group** (consecutive buttons whose group differs get an auto-divider), and
  a `pinned` flag. `PINNED_KEY = "close"` (always present, never hideable); `SETTINGS_KEY =
  "toolbarSettings"` (the gear; force-included in mini so the dialog is always reachable).
  **KEY STABILITY RULE:** keys are persisted → append-only, never rename/reorder `SPECS`. `DEFAULT_ORDER`
  is an **explicit key list** (decoupled from `SPECS` declaration order) defining the *display* default —
  a **bracketed layout**: Close/Recents then Undo/Redo on the left (history parked in a fixed spot right
  after navigation), content tools centered (ink & select → insert-objects), Scratchpad/Calendar/Tasks
  on the right, with the latent Encrypt button + pinned gear at the far right. It must list every live `SPECS` key or `load()` appends the missing ones. `DEFAULT_MINI` =
  compact everyday subset. **Changing `DEFAULT_ORDER` only affects fresh installs / a toolbar reset** — an
  existing persisted `order` wins and is never reordered by `load()` (it only appends new keys).
  **Encryption buttons:** `"lock"` (`btnLock`, `ic_lock`, group `GROUP_NOTEBOOK`) and `"lockOff"`
  (`btnLockOff`, `ic_lock_off`, group `GROUP_NOTEBOOK`) were appended in S6. They are runtime-hidden
  based on encryption state — `btnLock` visible only on unencrypted notebooks, `btnLockOff` only on
  encrypted. Existing users' persisted `order` configs that pre-date S6 may not include these keys;
  a one-time migration in `ToolbarPreferencesManager` appends any registry keys missing from the
  persisted list (new keys appear at the end rather than being hidden until a manual reset).
  **Text-recognition button:** `"textRecognition"` (`btnTextRecognition`, `ic_text_recognition`,
  group `GROUP_NOTEBOOK`) was appended when export moved to its own screen. It opened a two-item
  action sheet — "View recognized text" and the per-notebook "Real-time text: On/Off" toggle. Both
  actions were **later moved into the canvas long-press "Page" menu** (just above Export — see
  `showPageMenu()`), and the toolbar button (spec + view + `showTextRecognitionMenu`) was removed;
  `openTextViewer()`/`toggleRtr()` are called directly from the Page menu now. The key retires
  harmlessly under the append-only rule.
- **`ToolbarLayoutManager`** — arranges the existing button views into `drawingToolbar` per
  `ToolbarConfig`: resolves the visible key list (`order − hidden`, Close always kept; or the mini
  set when `miniEnabled && FLOAT`), sets orientation + size + edge-aware background, inserts
  orientation-aware auto-dividers (1dp × 28dp horizontal / 28dp × 1dp vertical), then appends a
  **manager-owned weighted `Space`** + the overflow controls so `btnOverflow` stays pinned to the
  trailing edge. The weighted spacer is the **only** spacer in the system — never user-facing, never
  in `order`. Hands off to `ToolbarOverflowManager` (which detects + preserves the spacer + the FLOAT
  drag handle) for fit/overflow. Button views are captured once and held permanently — a hidden
  button is detached from the tree, so `findViewById` can't re-find it.

### Placement, float, mini

- **Anchoring:** TOP/BOTTOM are horizontal (`match_parent` × thickness); LEFT/RIGHT vertical
  (thickness × `match_parent`); each with an edge-aware 1dp inkBlack border on the inner edge
  (`toolbar_background_{top,bottom,left,right}`). `barThickness()` (56dp, captured from the inflated
  layout before any flip) drives overflow-menu / page-indicator / floating-selection positioning so
  none assume a placement.
- **Float:** a detached bar at `shape_bordered`, main-axis length = `min(natural content extent,
  FLOAT_LENGTH_FRACTION (0.75) × matching screen dimension)` — so a thinned toolbar hugs its buttons
  and 0.75 never leaves a trailing gap, while a longer one is capped at 0.75 and overflows the rest
  (mini stays `WRAP_CONTENT`). The natural extent is summed deterministically by `floatContentMainSize()`
  from the same fixed layout-param sizes `ToolbarOverflowManager` measures (drag handle + buttons +
  group dividers + padding), so sizing and overflow agree. Positioned by `floatX/floatY` margins. A
  manager-owned **grip drag handle** (`ic_grip_vertical`) leads the bar; `wireFloatDragHandle()` does
  the long-drag (clamped to screen, persists `{floatX, floatY}` on release, re-pushes exclusion +
  overflow anchor). Overflow can flip to the bar's leading side near a far screen edge
  (`floatOverflowOpensBefore()` — consulted by both the menu anchor and the exclusion rect).
- **Mini** is **float-only**: when `miniEnabled && placement == FLOAT`, the visible list is
  **Close (lead) → ≤5 chosen → gear (trail)**; the float hugs its content. The gear long-press is a
  fast Full↔Mini switch (no-op when not FLOAT). Customize-dialog mini UI only shows in Float.

### Generalized pen-exclusion contract

- Both drawing views expose **`setToolbarExclusion(rect: Rect?)`** (replaces the old
  `setToolbarHeight`). `OnyxNotebookView.applyLimitRect()` uses the stored rect directly. The toolbar
  and drawing view share the root `FrameLayout` origin, so the toolbar's bounds *are* the rect.
- `computeToolbarExclusionRect()` is the single authority: per-placement bounds, extended away from
  the anchored edge when the overflow menu is open, **empty `Rect()` when collapsed** (whole canvas
  writable). `pushToolbarExclusion()` pushes it. **Highest-risk thread** — every placement / float /
  overflow / collapsed state must push the right rect or the pen draws under the bar (or is blocked
  where it shouldn't).

### Customize dialog (`notebook/CustomizeToolbarDialog.kt`)

`AlertDialog` (standard `shape_bordered` / `setElevation(0f)` rules). Fixed header = placement
segmented control + float-axis toggle (Float only) + mini Full/Mini toggle (Float only); scrolling
body = hand-rolled drag-reorder list (no RecyclerView), each row a grip handle + label + show/hide
(tap row) + per-row Mini toggle (Float only). "Reset" rebuilds defaults in place; "Save" folds the
read-back order + sets into a fresh `ToolbarConfig` and hands it to `applyToolbarConfig()`. Opened via
the gear button `btnToolbarSettings`.

### Double-tap hide gesture (always active)

- A **one-finger double-tap on the canvas** toggles `collapsed` — the *only* way to hide the bar and
  the *only* way back, so it never strands the user (no peek tab, no on/off setting — both were
  dropped as redundant). `handleToolbarToggleGesture()` in `dispatchTouchEvent`, **finger-only**
  (stylus never reaches it). A "tap" is short, near-stationary (≤ `scaledTouchSlop`), single-pointer,
  and not on toolbar chrome; two within `getDoubleTapTimeout()` + `scaledDoubleTapSlop` fire it. The
  movement + single-pointer guards keep it clear of the page-swipe and two-finger page-insert.
- `applyCollapsedState()` hides/shows `drawingToolbar` (closing any open overflow first) + releases
  the EPD overlay; `collapsed` persists and is restored on open via `root.doOnLayout` (after the
  overflow-init `doOnLayout`, so fit is computed while the bar is still visible).
- **The page indicator hides with the bar.** `applyCollapsedState()` also drives
  `tvPageIndicator.visibility`, so one double-tap clears *all* chrome off the page. Because that single
  site serves both the gesture and the restore path, a notebook opened with a persisted `collapsed`
  state starts with the indicator already hidden — no flash.

### Page indicator overlay

`tvPageIndicator` (`activity_notebook.xml`) is a bare `TextView` layered above the toolbar in the root
`FrameLayout` — it is *not* part of the bar. It reads `<notebook name> · <n> / <total>` (falling back to
the bare `n / total` when the name is blank), at 18sp `inkBlack`.

- `updatePageIndicator()` (`NotebookActivity`) sets the text and syncs the page count to the global
  index when it changes.
- `positionPageIndicator()` re-anchors it so it never collides with the bar: collapsed → `bottom|end`;
  toolbar BOTTOM → `top|end`; RIGHT → `bottom|start`; TOP/LEFT → `bottom|end`.
- The name makes this view far wider than the old `n / total`, so it is capped at `maxWidth=320dp` with
  `maxLines=1` + `ellipsize=end`. On narrow devices a long name truncates rather than running across the
  page — worth re-checking in every toolbar placement if that cap is ever changed.

---

## Page Long-Press Menu

The **page-scoped** actions are **not** toolbar buttons — they live in a canvas long-press menu, so the
bar stays focused on drawing tools. A one-finger stationary long-press anywhere on the canvas opens an
`ActionSheetDialog` titled **"Page"** with: **Template · Page Index · Insert Page Before · Insert Page
After · Copy Page · (Paste Page) · Erase Page · Delete Page**. Paste appears only when a page has been
copied (`pendingCopyPageId != null`) — an unavailable action is simply absent, never a silently-inert
row (disabled buttons are invisible on e-ink). Each row calls the same method its old button did.

- **Gesture:** `handlePageMenuLongPress()` in `NotebookActivity.dispatchTouchEvent`'s finger fan-out,
  alongside the swipe / toggle / multi-finger detectors. **Finger-only** (the stylus never reaches the
  fan-out, so writing is untouched). Arms a posted runnable on `ACTION_DOWN` and lets it fire at
  `getLongPressTimeout()` while the finger is still held; cancels on movement past `scaledTouchSlop`, a
  second finger, `ACTION_UP`/`CANCEL`, or the pen-activity gate (`cancelFingerGestures`). The runnable
  re-checks `isFinishing`/`isDestroyed`/`isPenActive` at fire time. A long-press is longer than the
  toolbar-toggle tap window, so the two never collide.
- **Suppressed** only for genuine *finger*-tap conflicts: over toolbar chrome, the shape-insert picker,
  an open overflow menu, or a followable link / sticky note. It is deliberately **not** gated on the
  active drawing tool — lasso / lasso-eraser / text-placement / shape-transform are *stylus*
  interactions, orthogonal to a finger gesture, and the other finger gestures (swipe, double-tap
  toggle) run in them freely. (An early build guarded on those modes and the menu silently did nothing
  whenever the restored tool was Lasso — a finger long-press must work regardless of the stylus tool.)
  `showPageMenu()` calls `releaseRender()` before showing so the sheet is visible on EPD.
- **Registry impact:** the `template`, `pageIndex`, `insertPageBefore`, `insertPageAfter`, `deletePage`,
  `copyPage`, `pastePage`, and `eraseAll` specs were **removed** from `ToolbarButtonRegistry` (and their
  `activity_notebook.xml` views deleted). Per the append-only key rule these keys are simply retired —
  a persisted config still listing them resolves to `null` and is skipped harmlessly, exactly like the
  retired `lockOff`.
- **Later retirements (`toc`, `export`, `insertText`, `textRecognition`, `pin`):** the `toc` spec/view
  were removed — the table of contents is reachable via the swipe-down-on-canvas gesture only
  (`evaluateSwipeDownToc` → `openToc()`, still live). `export` moved to the **bottom of the canvas
  long-press "Page" menu** (`showPageMenu()` → `openExportScreen()`); its spec/view were removed from the
  toolbar. `textRecognition` moved to the **Page menu just above Export** — its two actions ("View
  recognized text" → `openTextViewer()`, and the "Real-time text: On/Off" toggle → `toggleRtr()`) are
  added inline in `showPageMenu()`, and the button (spec + view + `showTextRecognitionMenu`) was removed.
  `pin` moved to the **top of the Page menu** — its spec/view were removed; a synchronous in-memory
  `notebookPinned` flag (loaded on open, updated by `toggleNotebookPin()`) drives the menu item's
  label/icon ("Pin Notebook"/"Unpin Notebook"), replacing the old button's live icon swap.
  `insertText` (the tap-to-place-text flow) was retired outright — strokes are converted to text instead —
  so its spec/view **and** the Activity-side placement machinery
  (`enterTextPlacementMode`/`exitTextPlacementMode`/`insertTextObject`, the `onTextPlacementTap` wiring,
  and the toolbar-touch cancel guard) were deleted; existing `type="text"` objects stay editable via
  `showTextEditDialogForTextObject` and the `TextInserted` undo path. The dormant `DrawingView` placement
  hooks (`setTextPlacementMode`/`onTextPlacementTap`) are left in place, unused. All five keys retire
  harmlessly under the append-only rule. `DEFAULT_MINI` is now `pen, eraser, undo, lasso` (its former
  `toc` entry dropped).
