# Calendar — Subsystem Reference

A handwriting-first calendar. Every view (Month / Week / Day) is a real drawable page using the same
universal object model as notebooks, the scratch pad, sticky notes, and shapes. The grid / timeline is
a baked template behind the ink (exactly like a notebook ruling template); the whole surface — grid,
notes band, and timeline alike — is writable. Content persists in `notesprout.db` (plaintext) and is
fully interoperable with every other canvas via the shared clipboard.

---

## Data model — `calendar` table in `notesprout.db`

The global index holds a `calendar` table added in Room migration 2 → 3. The schema is **identical to
`scratchpad`** (which mirrors the `.soil` `notebook` table) so every existing object serializer and
render model works unchanged.

```sql
CREATE TABLE calendar (
    id          TEXT    NOT NULL PRIMARY KEY,
    parentId    TEXT    NOT NULL,
    boundingBox TEXT    NOT NULL,
    "order"     INTEGER NOT NULL DEFAULT 0,
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    type        TEXT    NOT NULL,
    data        TEXT    NOT NULL
);
CREATE INDEX idx_calendar_parent_order ON calendar(parentId, "order", deletedAt);
```

### Row hierarchy

```
calendar_root  (type="calendar_root", parentId="", fixed id CALENDAR_ROOT_ID)
  └── page       (type="page",  parentId=CALENDAR_ROOT_ID, id=pageKey, data=PageData{width,height,template=""})
        └── layer (type="layer", parentId=pageId, data={label,isLocked,isVisible})
              └── stroke / heading / text / line / link / sticky_note / shape  (parentId=layerId)
```

- **Root id:** `CALENDAR_ROOT_ID = "00000000-0000-0000-0000-63616c6e6472"` ("calndr" in hex), defined
  in `data/index/ListIds.kt`. Created once by `CalendarRepository.ensureBootstrap()`.
- **Pages are keyed, not indexed.** The page row's `id` *is* the deterministic page key (below). Pages
  + layers are created lazily on first open of any month/week/day-half (`getOrCreatePageLayer`).
- `PageData.template` is always `""` — the grid/timeline is rendered live, not stored (see below).
- Soft deletes for content (`deletedAt`); stable UUIDs throughout. **Content is always plaintext** —
  `notesprout.db` is never encrypted.

### Page keys (`CalendarActivity.pageKey()`)

| View | Key format | Pages per period |
|---|---|---|
| Month | `cal-month-YYYY-MM` | one per month |
| Week  | `cal-week-YYYY-MM-DD` (Sunday of the week) | one per week |
| Day   | `cal-day-YYYY-MM-DD-AM` / `-PM` | **two** per day (AM = 00–12, PM = 12–24) |

Future day-notes (tap a day cell) is out of scope but the model leaves room: day-notes would become
their own page keys (e.g. `cal-daynote-YYYY-MM-DD`) under the same `calendar` table/root.

### Key files

| File | Role |
|---|---|
| `data/index/CalendarEntity.kt` | Room `@Entity` for the `calendar` table (schema = `ScratchpadEntity`) |
| `data/index/CalendarDao.kt` | CRUD queries (mirrors `ScratchpadDao`; + `getAllChildrenForLayer` / `deleteChildren` for undo snapshots) |
| `data/CalendarRepository.kt` | Higher-level API (`ensureBootstrap`, `getOrCreatePageLayer`, `loadPage`, `saveStrokes`, `insertObjects`, `softDeleteObjects`, `setPageSize`, `snapshotLayer`/`restoreLayer`) |
| `data/index/ListIds.kt` | `CALENDAR_ROOT_ID` constant |
| `data/index/NotesproutDatabase.kt` | `version=3`; `CalendarEntity` in `@Database entities`; `MIGRATION_2_3` |
| `data/index/NotesproutIndex.kt` | registers `MIGRATION_2_3`; `calendarDao()` accessor |
| `notebook/CalendarTemplateRenderer.kt` | bakes the grid/timeline bitmap + finger hit-test geometry |
| `CalendarActivity.kt` | the host screen (canvas, tools, navigation, gestures, undo/redo, transfer) |
| `CalendarTransfer.kt` | one-field in-memory singleton for Calendar → Notebook hand-off |

`CalendarRepository.loadPage` returns the shared `ScratchpadPageContent` (reused to avoid churn).

---

## Template renderer — grid/timeline as a baked bitmap

**`notebook/CalendarTemplateRenderer.kt`** is a stateless `object`. `render(spec, widthPx, heightPx,
density)` returns a transparent `ARGB_8888` bitmap with just the grid lines / labels — the drawing
surface provides the white page background. It is passed to the drawing view as the **template behind
the ink** via the standard `buildRenderBitmap(..., template)` / `loadStrokesWithBitmap(strokes, bmp,
template)` path (same pattern as notebook ruling templates).

- **Month:** Sun–Sat header strip + 6×7 grid of **square** day cells (cell width = full width / 7),
  day numbers (gray when out-of-month), today drawn as a filled black circle with white number,
  selection drawn as a 3dp stroked border, 1px dividers, then a **"Notes" band** filling the leftover
  height below the grid.
- **Week:** 2×4 grid (7 days + 1 blank), each cell with a DOW label + number; the Notes band height
  matches the month view so both feel identical.
- **Day:** 24 half-hour rows (`startHour` 0 or 12 by `dayHalf`), time labels in an 80dp left gutter,
  horizontal rules + a vertical separator.

`hitTest(spec, x, y, w, h, density): LocalDate?` reuses the identical geometry constants to map a
finger tap back to a date (Month/Week only; returns null for Day, the notes band, or empty hits). The
`Spec` data class (view, calYear, calMonth, selectedDate, dayHalf, today) is the single input to both
`render` and `hitTest`; `CalendarActivity.currentSpec()` builds it.

The template is re-rendered (cheap) whenever the selection changes (`refreshTemplate`) so the selection
border / today circle update without touching stored content.

---

## Host screen — `CalendarActivity`

Full-screen Activity (not translucent — unlike the scratch pad / sticky editor). `exported="false"`,
`configChanges` set so the canvas/bitmap survive rotation. One persistent drawing view, created once:

```kotlin
drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
binding.calendarContent.addView(drawingView.asView(), MATCH_PARENT × MATCH_PARENT)
```

It is **never recreated** across view/period changes (EPD-safe — avoids the `setLimitRect`/restart
pitfalls). Navigation swaps the page content + template bitmap into the same view.

**Layout** (`res/layout/activity_calendar.xml`):

```
LinearLayout (vertical, paperWhite)
  ├── calendarToolbar (56dp)
  │     btnBack │ btnToday │ btnMonthView · btnWeekView · btnDayView │
  │     btnCalPen · btnCalEraser · btnCalStickyNote · btnCalLassoEraser · btnCalLasso ·
  │     btnCalErasePage · btnCalUndo · btnCalRedo · btnCalScratchpad
  │     ─ spacer ─ btnPrev · tvMonthYear (tap → month/year picker) · btnNext
  ├── 1dp divider
  └── calendarContent (FrameLayout — drawing view added programmatically)
        └── floatingSelectionToolbar (gone by default; bringToFront() in onCreate)
              btnLassoCopy · btnLassoCut · btnLassoDelete · [convert-shape divider + btnConvertShape] ·
              btnLassoSendToNotebook · [btnShapeAspectLock · btnShapeTransformDone]
```

`btnCalScratchpad` launches the global scratch pad (`ScratchpadActivity`, plain — no from-notebook
extras). Tool state (pen/eraser) is restored from and persisted to the shared `ToolPreferencesManager`.

### Page load contract (`navigateCanvas`)

Same two-phase contract as the notebook/scratch pad:

1. `saveStrokes()` on `Dispatchers.IO` for the leaving page (also in `onPause`).
2. Clear lasso selection + exit shape-transform if active.
3. `repository.getOrCreatePageLayer(pageKey())` → `currentPageId` / `currentLayerId`.
4. `eraseAll()` (skipped on first load), `renderTemplateBitmap()`, `loadCanvasContent()`,
   `initHistory()`.

`loadCanvasContent` loads all object types, builds the render bitmap on IO with the template, then
`loadStrokesWithBitmap` for a single repaint. On first layout, if `PageData.width == 0` it records the
real canvas pixel size via `setPageSize`.

### Navigation surfaces

- **View toggles** (`switchView`) — Month/Week/Day; keeps `selectedDate`, recomputes `calYear/calMonth`.
- **Prev / Next** (`stepBack` / `stepForward`) — month ±1 / week ±7 / day steps AM↔PM↔next-day.
- **Today** (`goToToday`).
- **Month/year picker** (`showMonthYearPicker`, tap `tvMonthYear`).
- **Finger swipe** (canvas) — horizontal swipe ≥ 60dp (and dx > 1.5·dy) steps the period.
- **Finger tap** (canvas, Month/Week) — `hitTest` → select that day (re-renders template); tapping an
  out-of-month day in Month view navigates to that month.

### Last-position persistence

`SharedPreferences("calendar_state")` stores `last_view` / `last_date` / `last_cal_year` /
`last_cal_month` / `last_day_half`, written in `onPause` (`saveCalendarPosition`) and restored in
`onCreate` (falls back to today's month view, AM/PM by clock, on a fresh install). Reopening the
calendar lands on exactly the view + date the user left.

---

## Touch routing (`dispatchTouchEvent`)

Only **finger** events (`TOOL_TYPE_FINGER`) are intercepted; stylus and lasso events fall through to
the drawing view untouched. Order for a finger touch outside the toolbar/floating toolbar:

1. `handleStickyNoteTapGesture` — tap an on-canvas sticky-note icon → open its editor.
2. `handleMultiFingerDoubleTap` — **2-finger** stationary double-tap = undo, **3-finger** = redo.
3. `handleCalendarFingerGesture` — single-finger horizontal swipe = step period; quick tap = select day.

A `releaseRender()` is issued when a finger ACTION_DOWN lands on the toolbar/floating toolbar (EPD).

### Multi-finger double-tap (undo/redo) — ported from `NotebookActivity`

Arms on first pointer-down; evaluates on ACTION_UP (or ACTION_CANCEL for the BOOX case where the Onyx
SDK intercepts 3-finger touches and never sends UP). Movement guard uses `scaledTouchSlop`; double-tap
matching uses `scaledDoubleTapSlop` + `getDoubleTapTimeout()`; peak pointer count distinguishes 2 vs 3.
≥ 4 fingers disarm. State fields: `mfTap*`, `twoFingerTapFirst*`, `threeFingerTapFirst*`.

### Swipe vs multi-finger (regression guard)

`handleCalendarFingerGesture` tracks a dedicated **`calMultiTouch`** flag set true only on
ACTION_POINTER_DOWN. The ACTION_UP swipe branch is gated on `!calMultiTouch` so a 2-/3-finger
undo/redo double-tap never registers as a single-finger navigation swipe — while genuine single-finger
swipes (which set `calMoved` via ACTION_MOVE) still navigate. The tap branch keeps the `!calMoved`
guard.

---

## Tools, lasso & clipboard

Pen, eraser, lasso-eraser (scribble-erase), lasso-select, and sticky-note insert are ported from
`ScratchpadActivity`, with every erase/lasso/move/shape callback writing through `CalendarDao`. Lasso
reuses the notebook's `LassoGeometry` hit-test and the shared `NotesproutClipboard` / `ClipboardStore`,
so copy on the calendar can be pasted in a notebook / scratch pad / sticky note and vice-versa.

**Floating selection toolbar:** Copy · Cut · Delete · (Convert-to-Shape for a single recognizable
stroke) · Send to Notebook. Lasso-tap on empty canvas pastes the clipboard at the tap point (fresh
UUIDs, translated, left selected). Sticky notes and shapes (convert, transform, aspect-lock) have full
parity with the scratch pad.

**Erase page** (`btnCalErasePage`) soft-deletes every object on the current page's layer (AlertDialog
confirm, e-ink styling) and is undoable.

---

## Undo / redo (full-layer snapshot history)

Per-page snapshot history, identical shape to the calendar's sibling canvases:

- `undoStack` / `redoStack` of `List<CalendarEntity>` (whole-layer snapshots), `currentSnapshot`,
  `historyCap = 50`.
- `initHistory()` captures the current layer and clears both stacks — called on every page/view
  navigation, so **undo is per-page** (does not cross navigation).
- `pushHistory()` (called at every mutation site: pen-lift of new strokes, erase, scribble-erase,
  lasso cut/delete/paste, move, sticky insert, shape create/transform, erase-page) pushes the prior
  snapshot, captures the post-change state, and clears the redo stack.
- `undo()` / `redo()` swap snapshots, `repository.restoreLayer(layerId, rows)` (hard-replace the
  layer's children in a transaction — calendar is local/plaintext), then `loadCanvasContent()`.

**EPD repaint note:** undo/redo deliberately do **not** call `eraseAll()` before reloading.
`loadCanvasContent` swaps the full render bitmap in a single `handwritingRepaint`; a separate
`eraseAll()` would add a second full-screen repaint (double white-flash on EPD). This matches the
sticky-note editor's clean single-repaint feel.

---

## Cross-screen transfer

### Send to Notebook (`btnLassoSendToNotebook` → `sendToNotebook`)

Sends the current lasso selection (copy, not move) to a notebook via `CalendarTransfer.pending`
(one-field in-memory singleton).

- **Opened from a notebook** (`fromNotebookId != null`): AlertDialog offers "‹This notebook›" (set
  `CalendarTransfer.pending`, `setResult(RESULT_OK)`, `finish()` → the notebook's `calendarLauncher`
  pastes on return) or "Other notebook…" (notebook picker).
- **Opened from MainActivity:** notebook picker → `openNotebookWithPaste` launches the chosen notebook
  with `EXTRA_PASTE_PENDING`; `NotebookActivity` pastes once its initial page is laid out.

### Launch surfaces

| Surface | How | Extras |
|---|---|---|
| `MainActivity` | `CalendarActivity.launch(this)` | none — Send-to-Notebook uses the picker |
| `NotebookActivity` | `calendarLauncher.launch(CalendarActivity.intentFromNotebook(...))` | `EXTRA_FROM_NOTEBOOK_ID/NAME/ENCRYPTED` |

`NotebookActivity` consumes `CalendarTransfer.pending` in two places: the `calendarLauncher` result
callback (calendar opened from *this* notebook) and after the initial page load when
`EXTRA_PASTE_PENDING` is set (calendar opened from main → picker). Both clear the singleton after
pasting via `performScratchpadTransfer`.

---

## Encryption note

The calendar stores all content in `notesprout.db`, which is **never encrypted**. There is no
encryption gate on calendar copy/paste or "Send to Notebook" — calendar content is inherently
plaintext-on-device, the same as the scratch pad.
