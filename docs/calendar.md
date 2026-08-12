# Calendar — Subsystem Reference

A handwriting-first calendar. Every view (Month / Week / Day) is a real drawable page using the same
universal object model as notebooks, the scratch pad, sticky notes, and shapes. The grid / timeline is
a baked template behind the ink (exactly like a notebook ruling template); the whole surface — grid,
notes band, and timeline alike — is writable. Content persists in `notesprout.db` (encrypted at rest) and is
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
- Soft deletes for content (`deletedAt`); stable UUIDs throughout. Content lives in `notesprout.db`,
  which is [encrypted at rest](encryption.md#the-global-index-is-encrypted) under the **global**
  passphrase (never a per-notebook one).

### Page keys (`CalendarActivity.pageKey()`)

| View | Key format | Pages per period |
|---|---|---|
| Month | `cal-month-YYYY-MM` | one per month |
| Week  | `cal-week-YYYY-MM-DD` (Sunday of the week) | one per week |
| Day   | `cal-day-YYYY-MM-DD-AM` / `-PM` | **two** per day (AM = 00–12, PM = 12–24) |
| Day detail | `cal-daynote-YYYY-MM-DD` | **one** per day (see [Day detail](#note-view-cal-daynote-)) |

Pages are shared across the `calendar` table/root regardless of view, so a single day can carry
month-cell writing, week-cell writing, both AM/PM timeline halves, **and** its day-detail page —
all independent, all keyed.

> The numeric month key is formatted with **`Locale.ROOT`** (`"cal-month-%04d-%02d".format(Locale.ROOT, …)`),
> and the History day-note `LIKE` pattern likewise. On non-Western-digit locales (ar/fa/bn…) the
> default locale would render Eastern-Arabic digits, orphaning every previously-written month page
> after a device-language switch (week/day keys use ISO `LocalDate.toString()` and were never affected).

### Key files

| File | Role |
|---|---|
| `data/index/CalendarEntity.kt` | Room `@Entity` for the `calendar` table (schema = `ScratchpadEntity`) |
| `data/index/CalendarDao.kt` | CRUD queries (mirrors `ScratchpadDao`; + `getAllChildrenForLayer` / `deleteChildren` for undo snapshots; + `getTemplatesSorted` / `getTemplateById` for day-detail templates) |
| `data/CalendarRepository.kt` | Higher-level API (`ensureBootstrap`, `getOrCreatePageLayer`, `loadPage`, `saveStrokes`, `insertObjects`, `serializeForExport`, `softDeleteObjects`, `setPageSize`, `snapshotLayer`/`restoreLayer`, `insertTemplateRow`/`getTemplateById`/`setPageTemplate`) |
| `data/PageCopier.kt` | `insertCalendarPagesIntoNotebook` / `loadNotebookPageIds` + `CalendarExportPage`/`CalendarExportChild` carriers — the engine for the full-view export (below) |
| `data/index/ListIds.kt` | `CALENDAR_ROOT_ID` constant |
| `data/index/NotesproutDatabase.kt` | `CalendarEntity` + `NotebookActivityEntity` in `@Database entities`; `MIGRATION_2_3`, `MIGRATION_3_4` (DB is now at `version=8` — see [`global-index-format.md`](global-index-format.md)) |
| `data/index/NotesproutIndex.kt` | registers `MIGRATION_2_3` + `MIGRATION_3_4`; `calendarDao()` / `notebookActivityDao()` accessors |
| `data/index/NotebookActivityEntity.kt` / `NotebookActivityDao.kt` | `notebook_activity` log (OPENED/EDITED) — see [Day window](#day-detail--the-day-window) |
| `data/DayHistoryRepository.kt` | query + logging layer for the Notebooks / History views + the long-press day list (`notebooksForDay`) |
| `DayNotebooksDialog.kt` | long-press popup: merged list of notebooks created/opened/edited on a day |
| `notebook/CalendarTemplateRenderer.kt` | bakes the grid/timeline bitmap + finger hit-test geometry |
| `CalendarActivity.kt` | the host screen (canvas, tools, navigation, gestures, undo/redo, transfer); launches `DayDetailActivity` on double-tap |
| `DayDetailActivity.kt` | full-screen **three-view day window** (Note / Notebooks / History — see [Day window](#day-detail--the-day-window)) |
| `DayTemplateDialog.kt` | day-detail template quick-picker (reads `type="template"` rows from the `calendar` table) |
| `CalendarTransfer.kt` | one-field in-memory singleton for Calendar / Day-detail → Notebook hand-off |
| `data/index/EventEntity.kt` / `EventDao.kt` | Room `@Entity` + DAO for the `events` table (see [Events](#events--the-events-table)) |
| `data/events/EventModels.kt` / `RecurrenceSummary.kt` / `EventRecurrence.kt` | `@Serializable` event models (incl. `Reminder`/`ReminderUnit`) + summary + recurrence-expansion engine (`occursOn` / `occurrenceStartCovering` / `nextOccurrenceStart`) |
| `data/EventsRepository.kt` | events CRUD + `eventsForDay` (sorted all-day-first) + `upcomingForDay` (reminder look-ahead → `UpcomingEvent`) |
| `EventsController.kt` / `EventEditorDialog.kt` | Events-view list controller (Today + Upcoming sections) + add/edit editor dialog (incl. the "Remind me" builder) |

`CalendarRepository.loadPage` returns the shared `ScratchpadPageContent` (reused to avoid churn).

---

## Template renderer — grid/timeline as a baked bitmap

**`notebook/CalendarTemplateRenderer.kt`** is a stateless `object`. `render(spec, widthPx, heightPx,
density)` returns a transparent `ARGB_8888` bitmap with just the grid lines / labels — the drawing
surface provides the white page background. It is passed to the drawing view as the **template behind
the ink** via the standard `buildRenderBitmap(..., template)` / `loadStrokesWithBitmap(strokes, bmp,
template)` path (same pattern as notebook ruling templates).

- **Month:** Sun–Sat header strip + 6×7 grid of **square** day cells (cell width = full width / 7),
  day numbers (gray when out-of-month), today drawn as a 2dp stroked **ring** around the (black) number,
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
  ├── calendarToolbar (@dimen/toolbar_bar_thickness)
  │     calLeftBar (weight=1, managed by ToolbarOverflowManager):
  │       btnBack · btnCalHome · btnCalNewNotebook │ btnToday │ btnMonthView · btnWeekView · btnDayView │
  │       btnCalPen · btnCalEraser · btnCalStickyNote · btnCalLassoEraser · btnCalLasso ·
  │       btnCalErasePage · btnCalUndo · btnCalRedo · btnCalScratchpad · btnCalTasks · btnCalSendPage
  │       ─ spacer ─ dividerCalOverflow · btnCalOverflow (both gone until overflow)
  │     btnPrev · tvMonthYear (tap → day picker) · btnNext   ← always-visible period nav, outside calLeftBar
  ├── 1dp divider
  └── calendarContent (FrameLayout — drawing view added programmatically)
        ├── calOverflowMenu (gone; below-toolbar overflow rows; bringToFront() in onCreate)
        └── floatingSelectionToolbar (gone by default; bringToFront() in onCreate)
              btnLassoCopy · btnLassoCut · btnLassoDelete · [convert-shape divider + btnConvertShape] ·
              btnLassoSendToNotebook · [btnShapeAspectLock · btnShapeTransformDone]
```

**Toolbar overflow.** The left cluster lives in a weighted `calLeftBar` driven by the notebook's
[`ToolbarOverflowManager`](toolbar.md); the period-nav cluster (`btnPrev` · `tvMonthYear` · `btnNext`)
sits **outside** it, always visible and right-aligned. When the tools don't fit, `btnCalOverflow` (⋯)
appears and trailing tools drop into `calOverflowMenu` below the bar (tools overflow first; back/today/
view-toggles last). Tapping a menu item runs it and closes the menu; tapping elsewhere / another toolbar
button / leaving the screen (`onPause`) closes it. On EPD a pen-exclusion (`setToolbarExclusion`) covers
the open menu so the stylus can't draw under it. The wrap-content text toggles (Today/Month/Week/Day)
have their measured widths pinned once (`pinToggleWidths`) so the manager — which sizes by LayoutParams
px — counts them. `tvMonthYear` opens the shared [`DayPickerDialog`](#date-picker) (picks a specific day).

`btnCalTasks` launches the [task manager](tasks.md) (`TasksActivity`). It is a convenience jump to a
sibling surface, **not** a coupling of the two features: no task is ever drawn on a calendar grid and
the calendar reads nothing from the `tasks` table.

`btnCalScratchpad` launches the global scratch pad (`ScratchpadActivity`, plain — no from-notebook
extras). Tool state (pen/eraser) is restored from and persisted to the shared `ToolPreferencesManager`.

### Library shortcuts (`btnCalHome` / `btnCalNewNotebook`)

Two toolbar buttons (immediately after `btnBack`) jump to the notebook/folder library. Both route
through `goToLibrary(newNotebook)`, which launches `MainActivity` with
`FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP` (MainActivity is always the task root, so this
brings the existing instance to front via `onNewIntent` — clearing the calendar, and any notebook the
calendar was opened from, off the stack) then `finish()`.

- **`btnCalHome`** (`ic_notebook`) — straight to the library list.
- **`btnCalNewNotebook`** (`ic_new_notebook`) — passes `MainActivity.EXTRA_START_NEW_NOTEBOOK=true`.
  MainActivity's `handleNewNotebookIntent` consumes the extra once (then `removeExtra`) and enters
  **`DestinationPickerState.NewNotebook`** — the existing folder-navigation picker (same chrome as
  Move/Copy/Import), titled *"New notebook here"* / *"Create here"*. The user navigates to (or creates)
  the destination folder; **"Create here"** exits the picker and calls the normal `showNewNotebookDialog()`
  (template browser + name), which creates the notebook in the navigated-to folder and opens it. A
  `pendingNewNotebookPicker` flag defers picker entry to the cold-launch case where the grid isn't laid
  out / browse state isn't restored yet (`tryStartPendingNewNotebookPicker`, retried from the layout
  listener and the state-restore tail).

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
- **Day picker** (`showMonthYearPicker` → the shared [`DayPickerDialog`](#date-picker), tap `tvMonthYear`) —
  picks a specific day: sets `selectedDate` + `calYear/calMonth` and navigates. This is the **only** way
  to change the selected day by pointing (see the tap note below).
- **Finger swipe** (canvas) — a deliberate horizontal swipe steps the period. Uses the *same three
  guards as `NotebookActivity`'s page turn* (`pageSwipeQualifies`, constants copied verbatim):
  horizontal dominance (|dx| > |dy|), distance ≥ 30% of screen width, and either velocity ≥
  `scaledMinimumFlingVelocity` or distance ≥ 50% of screen width. Direction comes from `dx`, never
  from velocity. A `VelocityTracker` is armed on ACTION_DOWN and recycled on UP/CANCEL/pen-gate close.
  (Before 2026-07-20 this was a bare `|dx| ≥ 60dp && |dx| > 1.5·|dy|` with no velocity term, which
  made the calendar far twitchier than the notebook.)
- **Single-finger tap does *not* select a day** (`handleDayTap`). It once selected/navigated on a single
  tap, but that caused accidental day switches while writing — day selection is the picker's job now. The
  tap still records `lastDayTapDate`/`lastDayTapTime` purely to detect a double-tap.
- **Single-finger double-tap** → opens the day's full-page canvas ([`DayDetailActivity`](#note-view-cal-daynote-)).
  Month/Week: double-tap a day **cell** (`hitTest` resolves the date). Day: double-tap **anywhere** opens
  `selectedDate` (no cell hit-test). Second tap within `getDoubleTapTimeout()` on the same day opens.
- **Single-finger long-press** → that day's [notebook list](#long-press--the-day-notebook-list)
  (`DayNotebooksDialog`). Same date resolution as the double-tap: Month/Week hit-test the cell, Day
  uses `selectedDate` from anywhere on either half.
- **Two-finger swipe down** → the [Today dashboard](today-dashboard.md#the-two-finger-swipe-down),
  from all three views. `core/TwoFingerSwipeDown.kt`, shared verbatim with `NotebookActivity` — the
  same detector object, not another port. The dashboard is pushed on top, so Back returns to the
  calendar exactly as it was.

### Last-position persistence

`SharedPreferences("calendar_state")` stores `last_view` / `last_date` / `last_cal_year` /
`last_cal_month` / `last_day_half`, written in `onPause` (`saveCalendarPosition`) and restored in
`onCreate` (falls back to today's month view, AM/PM by clock, on a fresh install). Reopening the
calendar lands on exactly the view + date the user left.

### Date picker

`DayPickerDialog` (`DayPickerDialog.kt` + `res/layout/dialog_day_picker.xml`) is a clean, monochrome,
e-ink-styled calendar-grid picker shared by the calendar (`tvMonthYear`), the day window
(`tvDayDate` / see [day window](#day-detail--the-day-window)), and **every date field in the event
editor** (start/end date + recurrence "Ends on a date", via `EventEditorDialog.pickDate`). It replaces
the native `DatePickerDialog` (whose coloured header reads wrong on e-ink) and the old month/year-only
dialog.

- **Day-grid mode:** a Sun–Sat month; `‹ ›` step months; the initially-passed day is a **filled black
  circle** (a true circle via a centred fixed-size square slot, so it never stretches to an ellipse),
  today a **stroked ring**. Tapping a day picks it and dismisses.
- **Month/year mode:** tap the header title to flip in; `‹ ›` then step **years** and a 3×4 month grid
  is shown (selected month filled). Picking a month returns to that month's day grid.
- Bordered dialog (`shape_bordered`) + `setElevation(0f)`, the standard e-ink dialog treatment.
  Drawables: `bg_day_selected` (oval) / `bg_day_today` (ring) / `bg_month_selected` (filled chip).

### Long-press — the day notebook list

A **finger** long-press on any of the three views opens `DayNotebooksDialog` — a plain list of every
notebook created / opened / edited on that day. The day window's Notebooks view is the full browser
(paginated card grid, three separate sub-lists); this is the shortcut, reachable without leaving the
calendar.

- **Merged, one row per notebook.** `DayHistoryRepository.notebooksForDay(date)` folds all three
  `Kind`s into `DayNotebook` rows carrying `created` / `opened` / `edited` flags, so a notebook
  created *and* edited that day is one row tagged `created · edited` (`activityLabel`). Newest-first.
- **Simple rows, not cards** — notebook name over `folder breadcrumb · what happened`. No covers, no
  paging (the list scrolls), so nothing has to resolve a snapshot or an encryption state.
- **Tapping a row** dismisses and opens the notebook via the normal `NotebookActivity` intent —
  encrypted notebooks route through their own unlock path.
- **A day with no activity still opens the dialog**, showing "No notebooks on this day". The gesture
  always does the same thing, so a quiet day never reads as a missed press.
- **Finger only.** Stylus events never reach `dispatchTouchEvent`'s gesture branch, and a stylus
  long-press would deposit an ink dwell — the same reason the [shape dwell trigger](shape-objects.md)
  is disabled.
- **Fires while the finger is down** (`armDayNotebooksLongPress` posts to `binding.calendarContent` at
  `getLongPressTimeout()`), not on release. Cancelled by movement past `scaledTouchSlop`, a second
  pointer, UP/CANCEL, or `cancelFingerGestures` (the pen-activity gate). Once fired, `calLongPressFired`
  swallows the following ACTION_UP so the release can't also register as a swipe or day tap.
- **Month/Week presses off the grid never arm** — `hitTest` returns null in the notes band, so writing
  there is undisturbed.
- **A press on a sticky-note icon never arms** — that touch belongs to the note. Step 1 of the touch
  routing below consumes the UP of a sticky tap, so step 3 never sees it and the cancel that lives in
  its UP branch never runs: the runnable stayed posted and fired behind the just-opened sticky editor,
  leaving the day list waiting there when the editor closed. Day view made that certain (no cell
  hit-test → every press arms). `armDayNotebooksLongPress` hit-tests the sticky icons and returns; the
  sticky tap also cancels on its way out, and the runnable itself does nothing unless the calendar is
  still RESUMED — so no other path can strand a dialog behind a screen either.

---

## Touch routing (`dispatchTouchEvent`)

Only **finger** events (`TOOL_TYPE_FINGER`) are intercepted; stylus and lasso events fall through to
the drawing view untouched. Order for a finger touch outside the toolbar/floating toolbar:

1. `handleStickyNoteTapGesture` — tap an on-canvas sticky-note icon → open its editor. **Consumes the
   UP**, so steps 2–3 never see it; anything they armed on DOWN has to be cancelled here or skipped
   there (see the sticky-icon rule under the long-press above).
2. `handleMultiFingerDoubleTap` — **2-finger** stationary double-tap = undo, **3-finger** = redo.
3. `todaySwipe` (`TwoFingerSwipeDown`) — **2-finger** swipe down → the Today dashboard. Does **not**
   consume: step 4 disarmed itself at the second pointer-down (`calMultiTouch`), so there is nothing
   left for the rest of the sequence to trip.
4. `handleCalendarFingerGesture` — single-finger horizontal swipe = step period; quick tap = select day;
   long-press = day notebook list.

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

## Day detail — the day window

A full-screen "window into one day," opened by single-finger double-tap (Month/Week day cell, or
anywhere in Day view — see [Touch routing](#touch-routing-dispatchtouchevent)). Hosted by
**`DayDetailActivity`** (registered full-screen in the manifest, `exported="false"`, `configChanges`
set so the canvas survives rotation). Launched via `DayDetailActivity.intent(ctx, date, fromNotebook*)`
from `CalendarActivity.openDayDetail`; the source-notebook identity is carried through so Send-to-Notebook
can offer "this notebook".

The window has **four views**, chosen by a toggle group in the top toolbar placed **after the Back
arrow and before the drawing tools, separated by dividers** (mirrors the Month/Week/Day toggles). The
toolbar order is **Events · Note · Notebooks · History**:

| View | What it shows | Tools toolbar |
|---|---|---|
| **Events** *(default)* | birthdays / anniversaries / appointments **attached to this day** (incl. recurring occurrences) + a **look-ahead** of upcoming events (see [Reminders](#reminders--paper-like-look-ahead)) — list + add/edit/delete. See [Events](#events--the-events-table). | hidden |
| **Note** | the editable day-note canvas | visible |
| **Notebooks** | notebooks **Opened / Edited / Created** on this calendar day (paginated card grid) | hidden |
| **History** | this month/day **in a chosen past year** — a leading **Notes** (read-only day-note bitmap) then **Opened / Edited / Created** | hidden |

State: `ViewMode {NOTE, NOTEBOOKS, HISTORY, EVENTS}` (initial value `EVENTS`) + sub-toggles
`NbSub {OPENED, EDITED, CREATED}` / `HistSub {NOTES, OPENED, EDITED, CREATED}`. **Reopening a day
always lands on Events** — the active view is not persisted. The one exception is **launch restore**:
if the app was killed with the day window open, MainActivity reopens it on the date + view the user
left it on, passed through the optional `EXTRA_VIEW` intent extra, with the calendar (and the notebook
it was opened from, if any) stacked underneath so Back still steps out where it did (see
[`docs/mainactivity-and-recents.md`](mainactivity-and-recents.md) → Surface Stack).
`daySubNotebooks` / `daySubHistory`
sub-bars are visible only in their mode; `dayToolsGroup` is visible only in Note mode (`applyViewMode`
drives all show/hide + `isSelected`).

- **Back exits straight to the calendar**, on the exact view/date it came from (`handleBackNavigation`,
  shared by the toolbar arrow and the system/predictive gesture via `onBackPressedDispatcher` +
  `OnBackPressedCallback`): it closes any open shape-transform / shape-insert overlay (Note mode only)
  first, then `finish()` — it does **not** step between day-window views.
- **Move between days without leaving** (`switchToDate`): the toolbar **Today** button (`btnDayToday`)
  and the tappable date label (`tvDayDate` → shared [`DayPickerDialog`](#date-picker)) reload the whole
  window **in place** — flush the note page, swap the date, reset the History-year state, reload the
  canvas, and re-apply the active view (current view mode preserved).
- **Two-finger swipe down → the [Today dashboard](today-dashboard.md#the-two-finger-swipe-down)**,
  from **all four views** — the one finger gesture here that belongs to the window rather than to the
  Note canvas, so it sits outside the `viewMode == NOTE` branch in `dispatchTouchEvent` (and *after*
  it, so `handleMultiFingerDoubleTap` still sees the sequence — a two-finger stationary double-tap is
  undo). Not consumed: the Events `ScrollView` does slide a little under the swipe, and the swallow
  that once prevented it threw away Android's per-pointer split dispatch along with it. The linked
  section is the one place that records why — and the G102 touch-panel behaviour found chasing it.
- **The pen layer follows the mode** (`applyViewMode` tail): `drawingView.resumeDrawing()` in Note,
  `disableDrawing()` in every other view (so a stray pen touch can't be captured *invisibly* under the
  hidden card grid / read-only note / events list), then `releaseRender()` for a clean EPD repaint.

### Note view (`cal-daynote-…`)

The original editable canvas — behaves like the scratch pad: one page, freely writable, with an
optional ruling template.

- **Page:** key `cal-daynote-YYYY-MM-DD`, one page in the `calendar` table under `CALENDAR_ROOT_ID`,
  created lazily by the shared `getOrCreatePageLayer`. All canvas machinery (drawing callbacks, save,
  per-page undo/redo snapshots, lasso/clipboard, sticky notes, shape convert/transform, snapshot
  persistence, multi-finger undo/redo, sticky-tap routing) is ported from `CalendarActivity`; period
  navigation is dropped.
- **Toolbar** (`res/layout/activity_day_detail.xml`): `btnDayBack` │ `btnDayToday` │ view toggles │
  the `dayToolsGroup` — pen · eraser · lasso-eraser · lasso · undo · redo · sticky · template ·
  insert-shape — then `tvDayDate` (full date), and trailing at the right edge the sibling-surface
  pair `btnDayTasks` · `btnDayScratchpad`. Both sit outside `dayToolsGroup` so they stay on the bar
  in all four views and never overflow, and outside the insertion point
  `collapseToolbarToSingleRow` uses so they stay trailing when the bar folds to one row. The
  [Tasks screen](tasks.md#screen--tasksactivity) carries the mirrored pair (calendar · scratch pad)
  at the same end of its own bar — one tap either way, same place both times.
  Keeping them pinned right needs `daySurfaceSpacer`: stacked, `tvDayDate` is weighted and absorbs
  the bar's slack, but inline that weight moves to `dayToolsGroup`, which is hidden outside Note —
  so the spacer takes the weight for exactly that case (`applyViewMode`), and is `gone` (and
  therefore weightless) otherwise. The **tools group is weighted and
  overflows** exactly like the calendar: its trailing tools drop into `dayOverflowMenu` (below the bar)
  behind `btnDayOverflow` (⋯) when the bar is narrow, via the shared
  [`ToolbarOverflowManager`](toolbar.md). Overflow is **Note-mode-only** — outside Note the group is
  hidden and the fallback `daySpacerB` keeps the date right-aligned. Same dismissal + EPD
  `setToolbarExclusion` handling as the calendar; `onPause`/leaving Note closes the menu.
  Floating selection toolbar = copy/cut/delete/convert-to-shape/send-to-notebook + shape-transform.
  `dayShapeInsertToolbar` is the shape-insert secondary popup (ported from `NotebookActivity`).
- **Template (copy-into-table model).** Day pages store a real ruling template. The picker
  (`DayTemplateDialog`, a calendar-table variant of `TemplateDialog`) lists **Blank + `type="template"`
  rows already in the `calendar` table + "Browse Templates…"**. Browsing launches `TemplateBrowserActivity`
  MODE_PICK; the chosen library template is **copied into the calendar table** as a `type="template"`
  row via `CalendarRepository.insertTemplateRow` (exactly how notebooks copy into their `.soil` — so
  deleting the library template never blanks an existing day page). `PageData.template` holds the
  calendar-table row id (`""` = Blank); `applyTemplate` writes it via `setPageTemplate` (preserving
  `width/height/snapshot`), then `loadTemplateBitmap` decodes the row's base64 PNG (bounded to the view
  size via `BitmapDecode.decodeSampled`) and `rebuildCanvas` repaints. The drawing view scales any-size
  template to fill the canvas (`GenericNotebookView` draws it into the full `0,0–w,h` rect).
- **Send to Notebook** uses the always-launch-fresh path (`openNotebookWithPaste` → launches
  `NotebookActivity` with `EXTRA_PASTE_PENDING`, `CalendarTransfer.pending`, then `finish()`); "this
  notebook" routes to `fromNotebookId`, otherwise the `NotebookPickerActivity`. Day detail does **not**
  use the for-result hand-off (its immediate parent is the calendar, not the notebook).

### Notebooks view (Opened / Edited / Created)

A paginated card grid (`setupNotebooksList` → `renderList`/`renderGridPage`, first/prev/next/last
paging) reusing the recents-card look: notebook name, folder breadcrumb, that day's activity time, and
snapshot cover. Cards resolve through `DayHistoryRepository`:

- **Opened / Edited** read the `notebook_activity` log, grouped by notebook within `[dayStart, dayEnd)`
  (device-default zone), newest kept per notebook — **one card per notebook per list**. Missing /
  soft-deleted / non-notebook rows are pruned.
- **Created** is derived from the index (`ObjectEntity.createdAt` inside the day) — no rows are logged,
  so it is inherently retroactive.
- **Encrypted notebooks never expose a snapshot** (`coverFor` returns `snapshotB64 = null` + a lock,
  matching MainActivity's list — plaintext-leak guard).
- Tapping a card opens the notebook through the normal `NotebookActivity` flow (encrypted → its unlock
  path); on return the view/sub-toggle/page are unchanged.

### History view (past-year year picker)

Same month/day, a **chosen past year**. The year control is a **stepper** (`btnDayYearPrev`/`Next` →
`stepHistoryYear`) that walks **only years-with-data** (`yearsWithData`: any activity row, notebook
created that day, or day-note-with-content for that month/day, descending). `tvDayYear` is a
display-only label. Default year = **current − 1** if it has data, else the newest year-with-data ≤
current−1, else the newest (`pickDefaultHistoryYear`); loaded once per open (`loadHistoryYearsThenApply`,
`historyYearsLoaded`).

- **Notes** (`renderHistoryNote` → `renderDayNoteBitmap`): the chosen year's `cal-daynote-…` page
  composed to a **static bitmap** (template + content via `NotebookExporter.renderContentBitmap`) in
  `historyNoteImage` — no live drawing engine, inherently read-only. A `historyNoteToken` discards a
  slow render if the year/sub-toggle moved on; empty state (`emptyNoteMessage`) when the year has none.
- **Opened / Edited / Created** reuse the Notebooks card grid, keyed to the chosen year's month/day.
  Opened/Edited are **forward-only** — empty for years before the log existed; **Notes** and **Created**
  still populate retroactively.

### Activity log (`notebook_activity`)

Plaintext table in the global index (`notesprout.db`, Room **v3 → v4**, `MIGRATION_3_4`), columns
`id` / `notebookId` / `activityType` (`OPENED`|`EDITED`) / `timestamp`, indexed on
`(activityType, timestamp)` and `notebookId`. *Not* named "events" — that word is reserved for the
calendar-**Events** system (birthdays / anniversaries / appointments), now built on the separate
`events` table — see [Events](#events--the-events-table).

- **OPENED** — written in `NotebookActivity.onCreate` (next to `RecentsManager.recordOpen`) via
  `DayHistoryRepository.logOpened`.
- **EDITED** — written at seal (`sealNotebook`) only when `countContentModifiedSince(sessionStart) > 0`,
  i.e. a **content** row (stroke/heading/text/line/link/sticky/shape) changed this session. `updatedAt`
  is bumped on *every* close, so it can't define an edit — hence the session-scoped content check.

---

## Events — the `events` table

Calendar **Events** are structured, recurrable day-anchored entries — birthdays, anniversaries,
vacations, meetings, appointments, and one-off items. They are **not** handwriting; they live in the
global index (`notesprout.db`, encrypted at rest), never in a `.soil` file, and surface as the **Events** view
of the [day window](#day-detail--the-day-window). (Distinct from the `notebook_activity` telemetry log,
which deliberately avoided the word "events" so this system could claim it.)

### Data model

Room `@Entity` **`events`** (`data/index/EventEntity.kt`), added in **`MIGRATION_4_5`**
(`NotesproutDatabase` **v4 → v5**; `EventDao` registered in `NotesproutIndex.eventDao()`):

```
id · type · title
startEpochDay · endEpochDay   (local epochDay; end == start for single-day, else inclusive multi-day span)
allDay · startMinute · endMinute   (minute-of-day 0–1439; null when all-day / no time)
recurring   (mirror of data.recurrence != null — lets the DAO pull only rows needing expansion)
data   (EventPayload JSON: recurrence rule + notes)
createdAt · updatedAt · deletedAt   (soft delete)
```

Indexed on `(startEpochDay, endEpochDay)`, `recurring`, `deletedAt`. Queryable fields are promoted to
columns so a day's direct (non-recurring) events are found by SQL range-overlap; recurring events are
fetched by the `recurring` flag and expanded in Kotlin.

**Serializable models** (`data/events/EventModels.kt`, `kotlinx.serialization`):

- `EventType` — fixed presets **Birthday · Anniversary · Vacation · Meeting · Appointment · Other**,
  each carrying a `defaultFreq` (Birthday/Anniversary → `YEARLY`, rest → none) offered when creating.
- `RecurrenceRule` — full RRULE-like: `freq {DAILY,WEEKLY,MONTHLY,YEARLY}` + `interval` (bi-weekly =
  WEEKLY interval 2) + `weekdays` (ISO 1=Mon…7=Sun, WEEKLY only; empty = anchor's own weekday) +
  `monthlyMode {DAY_OF_MONTH, ORDINAL_WEEKDAY}` ("day 14" vs "3rd Tuesday") + a flattened end
  (`endMode {NEVER,UNTIL,COUNT}` + `endEpochDay` / `endCount`). `RecurrenceSummary` renders the list-row
  description.
- `Reminder` / `ReminderUnit {DAYS, WEEKS}` — a paper-like look-ahead lead-time (see
  [Reminders](#reminders--paper-like-look-ahead)). **Not** a notification: `amount` + `unit`, with
  `leadDays` (weeks × 7) for window math and `label()` ("1 week before"). An event may carry several.
- `EventPayload` — the `data`-column JSON (`recurrence` + `notes` + `reminders`). `reminders` is a new
  field with an empty default, so pre-existing rows deserialize unchanged — **no DB migration**.

### Recurrence engine (`data/events/EventRecurrence.kt`)

`occursOn(rule, anchorStart, anchorEnd, dayEpochDay)` decides whether an event covers a day. Each
occurrence preserves the anchor's **span length**, so an occurrence starting on `O` covers
`[O, O + spanDays]` inclusive.

- **NEVER / UNTIL** rules test only the few candidate starts in `[day − spanDays, day]` via
  `isValidStart` — O(spanDays), so a birthday anchored decades ago is effectively O(1).
- **COUNT** rules enumerate the first *N* occurrences (`generateStarts`, N is user-small) and test
  coverage directly.
- Per-freq validity: DAILY interval-mod; WEEKLY weekday-set + ISO-Monday week-index mod; MONTHLY
  month-index mod + (day-of-month, skipping short months / ORDINAL weekday, "5th"→"last"); YEARLY
  year mod + same month+day (Feb 29 → leap years only).
- `nextOccurrenceStart(rule, anchorStart, anchorEnd, afterDay, maxAheadDays)` — the START epoch-day of
  the first occurrence **strictly after** `afterDay`, no later than `afterDay + maxAheadDays`, skipping
  exceptions (COUNT reads the enumerated starts; NEVER/UNTIL forward-scans). Powers the Reminders
  look-ahead.

### Repository + UI

- **`data/EventsRepository.kt`** — CRUD + `eventsForDay(date)`: direct rows (SQL overlap) ∪ recurring
  rows the engine lands on the day, sorted **all-day first, then timed by start minute**, title
  tiebreak (the ordering the user asked for). Plus `upcomingForDay(date)` — the reminder look-ahead
  (see [Reminders](#reminders--paper-like-look-ahead)). No encryption gate — plaintext-on-device like
  the scratch pad.
- **`data/events/EventRowFormat.kt`** — the row *wording*: the leading time badge and the meta line
  (type · end-time · multi-day span · recurrence summary). Extracted here because the
  [Today dashboard](today-dashboard.md) renders the same events in the same `item_event.xml`; kept
  inside `EventsController`, the two surfaces would eventually describe one event two different ways.
  Formatting only — edit/delete scoping stays with the surface that offers it, and the dashboard
  offers none (it hides the delete button and taps through to this screen).
- **`EventsController.kt`** — drives the Events view: `refresh()` loads today's events **and**
  `upcomingForDay`, rendering **two sections** — **Today** then **Upcoming** (black bold labels; the
  "Today" label appears only when an Upcoming section follows it). Today rows (`item_event.xml`:
  time/all-day badge · title · meta = type · end-time · multi-day span · recurrence summary) are
  tap-to-edit with per-row delete; Upcoming rows show a countdown badge ("Tomorrow" / "In N days") +
  `type · occurrence-date · time` meta and key their edit/delete scope to the **occurrence** day, not
  the viewed day. Add button opens the editor. Bound in `DayDetailActivity` (`ViewMode.EVENTS`, toolbar
  toggle `btnDayViewEvents`, `dayEventsContainer` in `activity_day_detail.xml`; `applyViewMode`
  shows/refreshes it, `onResume` re-refreshes).
- **`EventEditorDialog.kt`** (+ `dialog_event_editor.xml`) — add/edit: type spinner (default recurrence
  on type change for new events), title, start/end date, all-day switch + start/end time (long-press
  end-time clears), the full recurrence builder (Repeats spinner → `Every N units` + weekly weekday
  toggles / monthly day-vs-ordinal / Ends Never·On date·After N), and the **"Remind me"** builder
  (`etRemindAmount` + `spRemindUnit` days/weeks + Add → removable bordered rows in `llReminders`,
  deduped, sorted by lead). e-ink styled (bordered dialog, no elevation); Delete shown only when
  editing. All date fields open the shared [`DayPickerDialog`](#date-picker) (not the native spinner).
  For a recurring event the editor pre-fills dates from the **tapped occurrence** (`occurrenceStart`),
  not the series anchor — see [occurrence day-move](#per-occurrence-edit--delete-recurring-events).

### Grid rendering (Month / Week / Day canvas)

Events are **baked into the grid template bitmap** behind the ink (they need no live layer — same
model as the ruling lines). `CalendarTemplateRenderer.render` takes an
`eventsByDay: Map<LocalDate, List<DayEvent>>` (a neutral `DayEvent(label, allDay, startMinute, icon)`
— the caller maps `EventEntity` rows, incl. `EventType → EventIcon` via `CalendarActivity.iconFor`);
`highlights = false` export renders omit events (a notebook page shouldn't freeze them in).

- **Month / Week cells** — small monochrome **type glyphs** on the day-number row, right-aligned
  (numbers stay left-aligned). `EventIcon {CAKE, HEART, SUITCASE, PEOPLE, CLOCK, DOT}` (birthday /
  anniversary / vacation / meeting / appointment / other), drawn programmatically with Canvas
  primitives (no drawables → the renderer stays Context-free). **Distinct types only** (a day with two
  birthdays shows one cake); a `+` caps overflow when the row is full. Deliberately *not* text — keeps
  cells calm on e-ink.
- **Day timeline** — all-day items stacked at the top of the gutter; timed items as a filled dot +
  `time label` at the y matching their start minute, shown only for the half (AM/PM) on screen.

`CalendarActivity.loadEventsForView()` (suspend) loads the visible period's range via
`EventsRepository.eventsForRange` (one recurring-set + one range query, expanded per day) into
`eventsByDay`, called before `renderTemplateBitmap()` in `navigateCanvas` and again in `onResume`
(returning from the day window may have changed events → reload + `refreshTemplate`). Selection-only
`refreshTemplate` reuses the cache (same period ⇒ same events).

### Per-occurrence edit / delete (recurring events)

Editing or deleting a **recurring** event prompts a scope (`EventsController.promptEditScope` /
`confirmDelete`): **this occurrence / this and following / all events**. Mechanics live in
`EventsRepository`, keyed off the occurrence covering the viewed day
(`EventRecurrence.occurrenceStartCovering`):

- **This occurrence** — the occurrence's start epoch-day is added to `RecurrenceRule.exceptionDates`
  (a new field with an empty default → **no DB migration**; rides in the existing `data` JSON). The
  engine skips excluded starts. An *edit* additionally drops a standalone one-off override on that
  occurrence's dates carrying the edited fields — **including its reminders** (the override is built
  from the edited payload, not a fresh one, so reminders are never dropped).
- **This and following** — the series is truncated (`endMode = UNTIL`, `endEpochDay = occStart − 1`);
  an *edit* also starts a fresh series at the occurrence (re-anchored, no inherited exceptions) that
  **carries the reminders**. Splitting at the first occurrence collapses to a whole-series op.
- **All events** — whole-series update / soft-delete; edits carry forward existing `exceptionDates`
  and **preserve the original series anchor** when the editor's dates come back unchanged from the
  tapped-occurrence prefill (`editSeries` compares against `occurrenceStartCovering` for the viewed
  day). A deliberately changed date still re-anchors; upserting the prefill verbatim used to silently
  erase the original anchor (e.g. a birthday's birth year) and all past occurrences.

**Editor validation:** an "Ends on a date" before the event's start is **rejected at Save** (it would
otherwise store a `recurring=1` row that occurs on no day — invisible to every query and impossible to
edit or delete from the UI).

**Moving an occurrence's day:** all edit scopes honour a date change. The editor pre-fills a recurring
event's dates from the **tapped occurrence** (not the series' parent anchor — `EventsController.openEditor`
passes `occurrenceStart` via `EventRecurrence.occurrenceStartCovering`), so an untouched date stays put
and a changed date moves the instance. *This occurrence* re-homes the one-off override onto the edited
dates (the exception stays pinned to the original occurrence start); *this and following* anchors the new
series on the edited dates (the original is still truncated at the original occurrence start — moving the
anchor *earlier* than the split can overlap the truncated tail, a rare edge; use "all events" for a clean
whole-series shift). Span (multi-day) changes carry through the same way.

### Reminders — paper-like look-ahead

A **reminder** is a per-event **lead-time** (`Reminder` = `amount` + `ReminderUnit {DAYS, WEEKS}`) that
makes an upcoming event **surface in the Events screen ahead of its date**. It is **not** a
notification/alarm — there are no `AlarmManager`, no `POST_NOTIFICATIONS`, no receivers, nothing that
interrupts. It is purely a query + list render: the user only ever sees it by *looking* at the Events
screen (the calm/meditative philosophy — a paper planner you flip open, not a phone that buzzes). An
event may carry several reminders.

- **Surfacing rule.** For the Events screen of day *D*, an event surfaces under **Upcoming** when its
  next occurrence `O` satisfies `O − lead ≤ D < O` for one of its reminders — i.e. it shows on **every
  day in the lead window**, then drops into **Today** on `O`. One row per event (its soonest qualifying
  occurrence), sorted nearest-first → all-day → title.
- **Storage.** `EventPayload.reminders` (rides in the `data` JSON, empty default → **no DB migration**).
  Weeks are stored distinct from days only for display; `Reminder.leadDays` (× 7) drives the math.
- **Query** (`EventsRepository.upcomingForDay`). Non-recurring: events starting in `(D, D + 366]`
  (`MAX_LOOKAHEAD_DAYS`), kept when a reminder's lead reaches back to *D*. Recurring: each row's next
  start via `EventRecurrence.nextOccurrenceStart` (bounded by that event's largest lead), same lead
  test. Returns `UpcomingEvent(event, occurrenceStart, daysUntil)`. Events without reminders never
  surface. Recurrence-aware (honours `exceptionDates`).
- **Editing an Upcoming row** keys its recurring scope to the **occurrence day** (`openEditor` /
  `confirmDelete` take an explicit context day) — otherwise "this occurrence" would resolve against a
  lead-up day with no occurrence and silently no-op.
- **Grid canvas is unchanged** — reminders touch the Events *list* only; Month/Week/Day glyphs still
  mark the actual event day, not the lead-up days.

### v1 scope / deferred

- No import/export of events yet.
- Grid markers are **not** carried into full-view notebook export (grid export stays event-free).

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

### Send page to notebook — full-view export (`btnCalSendPage` → `sendPageToNotebook`)

Turns the **whole current view** into new notebook page(s): the grid/timeline becomes the page's
**template**, and (optionally) the view's writing is copied as page content. Neither mode touches the
template **library** — the template is written as a plain `type="template"` row inside the destination
`.soil`.

**Flow**

1. **Mode** (AlertDialog) — `With writing` (grid template + all content objects) · `Template only`
   (grid template, blank layer) · Cancel.
2. **Destination** — opened from a notebook (`fromNotebookId != null`): "‹This notebook›" / "Other
   notebook…" / Cancel. From MainActivity: straight to the notebook picker
   (`exportNotebookPickerLauncher`, separate from the Send-to-Notebook picker).
3. **Position** — "Insert into ‹Notebook›" list: **End of notebook**, or tap any **Page N** →
   **Insert before / after**. Empty notebook appends automatically. Page order is read live via
   `loadNotebookPageIds`.
4. **After insert** — "Added N page(s) to ‹Notebook›" → **Open ‹Notebook›** / **Stay**. Open returns to
   the source notebook (`EXTRA_RESULT_GOTO_PAGE_ID`) or launches the dest at the new page
   (`EXTRA_INITIAL_PAGE_ID`); either way the source notebook reloads its page list on return
   (`reloadPagesPreservingCurrent`) so new pages appear even on "Stay".

**Day view = two pages.** `exportSources()` always emits both AM (`dayHalf=0`) and PM (`dayHalf=1`)
regardless of the half on screen; the off-screen half's content is read straight from the DB.

**Native canvas size + toolbar top-margin.** New pages are the calendar canvas's exact pixel size, so
content copies 1:1 with zero scaling. **But** the calendar canvas sits *below* its toolbar, while a
notebook page's drawing area is full-screen with the toolbar **overlaid** on top. So the export reserves
a blank top strip of height `root.height − calendarContent.height` — the top guard band + 1dp rule +
toolbar (`@dimen/toolbar_bar_thickness`) + 1dp rule, i.e. exactly the chrome height the destination notebook reserves on the same
device (on Ratta the guard term is 0, so the strip is just the toolbar + rules; do **not** "fix" the
formula to exclude the guard — it tracks the device's chrome by construction):

- page height grows to `canvasH + topOffset`;
- the grid is composited into the taller bitmap at `y = topOffset` (blank white above);
- **content is translated down by `topOffset`** so it clears the notebook's floating toolbar instead of
  landing under it.

Content translation goes through the existing `regenIds(clip, 0f, topOffset)` path (fresh UUIDs +
correct per-type geometry shift — strokes, heading strokes, line/shape centers, **and nested link/sticky
embedded geometry**, not just bounding boxes; a bbox-only shift would orphan link contents). The page is
loaded as typed objects via `loadPage`, run through `regenIds`, then serialized by
`CalendarRepository.serializeForExport` (mirrors `insertObjects`) into `CalendarExportChild` rows.

**Clean grid.** The baked template renders with `highlights = false` (`CalendarTemplateRenderer.render`
gains a `highlights` flag), omitting the today-circle and selection border.

**Encryption.** The destination key is resolved once via `KeySession.getFor` ?: `KeyResolver`
(prompting if locked) and held in memory through the whole flow — a passphrase never crosses an Intent.
`insertCalendarPagesIntoNotebook` opens the dest `.soil` raw, computes the insertion index, shifts page
orders, and writes template + page + layer + children in one transaction, then checkpoint-vacuums.

### Launch surfaces

The library entry point is a **first-class bottom-bar button**, not an overflow item: `btnCalendar`
sits at the start of `surfaceButtonsGroup` beside the scratch pad, in the one bar that search /
pinned / recents mode never swap out. See
[mainactivity-and-recents.md](mainactivity-and-recents.md) for the zone split and width buckets.

| Surface | How | Extras |
|---|---|---|
| `MainActivity` | `CalendarActivity.launch(this)` from bottom-bar `btnCalendar` | none — Send-to-Notebook uses the picker |
| `NotebookActivity` | `calendarLauncher.launch(CalendarActivity.intentFromNotebook(...))` | `EXTRA_FROM_NOTEBOOK_ID/NAME/ENCRYPTED` |

`NotebookActivity` consumes `CalendarTransfer.pending` in two places: the `calendarLauncher` result
callback (calendar opened from *this* notebook) and after the initial page load when
`EXTRA_PASTE_PENDING` is set (calendar opened from main → picker). Both clear the singleton after
pasting via `performScratchpadTransfer`.

---

## Encryption note

The calendar stores all content in `notesprout.db`, which is
[SQLCipher-encrypted at rest](encryption.md#the-global-index-is-encrypted) under the **global**
passphrase. There is no encryption gate on calendar copy/paste or "Send to Notebook".

That remains correct, but for a sharper reason than "it's all plaintext anyway": calendar content is
always global-scoped, so moving content *into* the calendar never downgrades it — except from a
NOTEBOOK-scoped notebook, where it widens the secret from a per-notebook passphrase to the
device-global one. Same residual consideration as the scratch pad.
