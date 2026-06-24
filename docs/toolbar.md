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
  **KEY STABILITY RULE:** keys are persisted → append-only, never rename/reorder. `DEFAULT_ORDER` =
  XML order (no spacer); `DEFAULT_MINI` = compact everyday subset.
  **Encryption buttons:** `"lock"` (`btnLock`, `ic_lock`, group `GROUP_NOTEBOOK`) and `"lockOff"`
  (`btnLockOff`, `ic_lock_off`, group `GROUP_NOTEBOOK`) were appended in S6. They are runtime-hidden
  based on encryption state — `btnLock` visible only on unencrypted notebooks, `btnLockOff` only on
  encrypted. Existing users' persisted `order` configs that pre-date S6 may not include these keys;
  a one-time migration in `ToolbarPreferencesManager` appends any registry keys missing from the
  persisted list (new keys appear at the end rather than being hidden until a manual reset).
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
- **Float:** a detached bar at `shape_bordered`, length = `FLOAT_LENGTH_FRACTION` (0.75) × the
  matching screen dimension (or `WRAP_CONTENT` in mini), positioned by `floatX/floatY` margins. A
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
