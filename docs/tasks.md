# Tasks — Subsystem Reference

The task manager: "the list of things I need to do." One-time and recurring tasks, stored in a
`tasks` table in the global index and surfaced by a dedicated full-screen `TasksActivity`.

**It is independent of the calendar and the notebook.** No task is drawn on a calendar grid, no task
section appears in the day window, and nothing here reads calendar or notebook content. The three
toolbar entry points are convenience jumps between sibling surfaces, not a coupling. Wiring the
features together (tasks that reference a notebook, tasks on a calendar day) is a later effort — the
schema leaves room for it but nothing depends on it.

---

## Data model — `tasks` table in `notesprout.db`

Room migration 8 → 9 (`MIGRATION_8_9`), plus 9 → 10 (`MIGRATION_9_10`) for the reminder columns.
Unlike [`events`](global-index-format.md#events--a-bespoke-query-optimized-table) this table is
**fully columnar**: the recurrence rule lives in typed columns and there is **no `data` payload**, so
nothing in it is ever JSON.

```sql
CREATE TABLE tasks (
    id               TEXT    NOT NULL PRIMARY KEY,
    parentId         TEXT,              -- routine id; NULL for every row today (reserved)
    type             TEXT    NOT NULL,  -- TASK | ROUTINE (only TASK is written today)
    title            TEXT    NOT NULL,
    state            TEXT    NOT NULL,  -- NOT_DONE | DONE | SKIPPED
    dueEpochDay      INTEGER,           -- local epoch-day; NULL = undated
    "order"          INTEGER NOT NULL DEFAULT 0,   -- reserved: step order inside a routine
    seriesId         TEXT,              -- shared by every row generated from one rule
    seriesIndex      INTEGER,           -- 0-based position in the series (drives COUNT)
    seriesAnchorDay  INTEGER,           -- the series' ORIGINAL first due day
    recurFreq        TEXT,              -- NULL = one-time. DAILY | WEEKLY | MONTHLY | YEARLY
    recurInterval    INTEGER,
    recurWeekdays    INTEGER,           -- WEEKLY: ISO bitmask, Mon = bit 0 … Sun = bit 6
    recurMonthlyMode TEXT,              -- DAY_OF_MONTH | ORDINAL_WEEKDAY
    recurEndMode     TEXT,              -- NEVER | UNTIL | COUNT
    recurEndEpochDay INTEGER,
    recurEndCount    INTEGER,
    remindAmount     INTEGER,           -- look-ahead lead; NULL = no reminder (added v10)
    remindUnit       TEXT,              -- DAYS | WEEKS                        (added v10)
    resolvedAt       INTEGER,           -- ms the row went DONE or SKIPPED; NULL while NOT_DONE
    createdAt        INTEGER NOT NULL,
    updatedAt        INTEGER NOT NULL,
    deletedAt        INTEGER            -- soft delete
);
CREATE INDEX index_tasks_state_dueEpochDay ON tasks(state, dueEpochDay);
CREATE INDEX index_tasks_seriesId          ON tasks(seriesId);
CREATE INDEX index_tasks_parentId          ON tasks(parentId);
CREATE INDEX index_tasks_deletedAt         ON tasks(deletedAt);
```

> **Every query filters `type = 'TASK'`.** Routine rows will share this table, and without the filter
> they would surface as ordinary list items the moment that feature lands. This is the single most
> important invariant for the deferred routines work.

### Key files

| File | Role |
|---|---|
| `data/index/TaskEntity.kt` | Room `@Entity` for the `tasks` table |
| `data/index/TaskDao.kt` | CRUD + `openTasks` / `resolvedTasks` / `openInSeries` / `maxSeriesIndex`; soft **and** hard delete |
| `data/tasks/TaskModels.kt` | `TaskState` / `TaskRowType` enums (plain, **not** `@Serializable`) + `TaskWeekdays` bitmask pack/unpack |
| `data/tasks/TaskRecurrence.kt` | Bridges the columnar rule columns to [`EventRecurrence`](calendar.md#recurrence-engine-dataeventseventrecurrencekt) + `nextDue` + end conditions |
| `data/TasksRepository.kt` | CRUD, section/group queries, `complete` / `skip` / `reopen` + successor generation |
| `TasksActivity.kt` | The screen |
| `TaskEditorDialog.kt` | Add / edit dialog incl. the recurrence builder |
| `res/layout/activity_tasks.xml`, `item_task.xml`, `dialog_task_editor.xml` | Layouts |
| `res/drawable/ic_tasks.xml`, `ic_checkbox_{empty,checked,skipped}.xml` | Tabler-style icons |
| `app/src/test/…/data/tasks/TaskRecurrenceTest.kt` | The next-due matrix, as JVM tests |

---

## Recurrence — materialized rows, not in-memory expansion

The one place task behaviour genuinely diverges from calendar events, and the reason the table can be
JSON-free.

An **event** stores one anchor row and *expands* its occurrences in memory on every read — which is
why it needs `exceptionDates`, `occurrenceStartCovering`, and three edit scopes (this occurrence /
this and following / all). A **task series** instead keeps **exactly one open row at a time**:

- Resolving the open row (DONE or SKIPPED) writes `state` + `resolvedAt`, then **inserts a successor**
  with the same `seriesId` / `seriesAnchorDay` / rule and `seriesIndex + 1`.
- Resolved rows stay in the table forever as the series' history (soft-deleted only on an explicit
  delete).

Consequences, all simplifications: no exception dates, no occurrence-scoped edit or delete prompts,
no "this and following" split. Editing a task edits that row; editing its rule affects only rows
generated from then on. And the only set-shaped field left — the weekly weekday set — collapses to an
integer bitmask, which is what makes the fully columnar schema affordable.

### The next-due rule

The first valid occurrence in the series, anchored at `seriesAnchorDay`, **strictly after
`max(dueEpochDay, actionDay)`** — where `actionDay` is the local day the task was completed or
skipped. Anchoring on the original series start (not the current row's due day) is what keeps the
phase grid intact.

| Series | Due | Resolved | Next due |
|---|---|---|---|
| Daily | Mon | Wed | Thu |
| Daily | Fri | Wed (early) | **Sat** — not Thu |
| Every 3 days from Jan 1 | Jan 4 | Jan 6 | Jan 7 (stays on the anchor's grid) |
| Monthly, day 15 | Jan 15 | Feb 3 | Feb 15 |
| Monthly, day 31 | Jan 31 | Feb 1 | **Mar 31** (February is too short) |
| Yearly, Jul 4 | Jul 4 | Jul 20 | Jul 4 next year |
| Yearly, Feb 29 | Feb 29 | Mar 1 | **Feb 29 four years on** |
| Weekly Mon/Wed/Fri | Wed | Thu | Fri |

**A skip advances the series exactly as a completion does.** It is a decision about the occurrence,
not a deletion of it, so both share one code path (`TasksRepository.resolve`).

### End conditions — and why COUNT is counted differently

- **UNTIL** — the engine's own end-date check terminates the walk; `nextDue` returns null past it.
- **COUNT** — enforced by `seriesIndex`, **not** by enumerating dates, and the date walk deliberately
  runs with COUNT stripped to `NEVER`.

  That second half matters. The events engine resolves COUNT by enumerating the first *N* valid
  calendar positions. Applied to tasks, a daily "3 times" series started Jan 1 but not finished until
  Jan 5 would find no enumerated start after Jan 5 and silently end after a single occurrence — the
  user did one of three and the series vanished. For a task a count is a count of **rows**, because
  rows are what the user actually works through.

### Reusing the engine

`TaskRecurrence` does **not** reimplement recurrence. It builds an in-memory
[`RecurrenceRule`](calendar.md#data-model) from the columnar fields and calls
`EventRecurrence.nextOccurrenceStart`, which already handles DAILY interval-mod, WEEKLY weekday-set +
ISO week-index mod, MONTHLY month-index mod with short-month skipping and ordinal weekdays
("5th" → "last"), and YEARLY with leap-day handling. That rule object is a value carrier only — it is
never serialized, so no JSON reaches the table. `RecurrenceSummary.of` renders the row's meta line.

The engine's scan is bounded by `maxAheadDays`, so `TaskRecurrence.lookaheadDays` sizes the bound per
frequency and interval. It is deliberately generous: a bound that is too tight does not error, it
silently ends a series. Monthly needs headroom past a 59-day Jan-31 → Mar-31 gap; yearly-on-Feb-29
needs to reach eight years to clear a skipped century.

Intervals are coerced to 1–99 both in the editor and in `ruleOf`, which keeps the day-by-day scan
bounded no matter what is in the column.

---

## Screen — `TasksActivity`

Full-screen, `exported="false"`, `configChanges` set. Registered on the
[`SurfaceStack`](mainactivity-and-recents.md#surface-stack--launch-restore) as `AppSurface.TASKS`, so
a cold launch reopens it; the entry carries **no payload** because the screen reads the table fresh
every time.

**Unlike every other surface it keeps the system bars visible** — there is no canvas that wants to be
full-bleed and no drawing engine at all — so the top guard comes from the live inset
(`TopGuard.applyInsetPadding`) rather than the fixed reservation the drawing screens use.

**Everything on the screen is relative to *today*, so the day rolling over invalidates all of it**:
yesterday's tasks become overdue, tomorrow's become today's, and reminder windows open. `onResume`
recomputes `LocalDate.now()`, which covers closing the app one day and opening it the next. A
`BroadcastReceiver` on `ACTION_DATE_CHANGED` / `ACTION_TIME_CHANGED` / `ACTION_TIMEZONE_CHANGED`
(registered in `onResume`, released in `onPause`) covers the case that would otherwise go stale
indefinitely — the screen left open *across* midnight, easy to do on a device that is never really
switched off.

Chrome deliberately mirrors the day window's **Events** view — a nav row, then a titled header row
carrying the one create action:

```
┌──────────────────────────────────────────────┐
│ ← │ Today │ All │ Done │                  ✎  │  56dp nav row
├──────────────────────────────────────────────┤
│ Tasks                                     +  │  56dp title row
├──────────────────────────────────────────────┤
│ Overdue                                       │
│ ☐  Change furnace filter            2d ago    │
│ Today                                         │
│ ☐  Pay water bill                             │
│ ☐  Water the ferns    Every day               │
│ Upcoming                                      │
│ ☐  Renew passport                Fri 31 Jul   │
│ No date                                       │
│ ☐  Read Gödel Escher Bach                     │
└──────────────────────────────────────────────┘
```

- **There is no date control**, and deliberately so. A tappable reference-day picker was designed and
  dropped: projecting the list onto a future date would have had to show tasks as *Overdue* that were
  not yet late — manufactured stress rather than information. A display-only date label was then
  dropped too, as chrome that did nothing. The list is always relative to the real today.
- **The scratch pad launcher** (`btnTasksScratchpad`) opens the global scratch pad plain, exactly as
  the calendar's does.

### Three views

Widening in scope, left to right:

| View | Contents |
|---|---|
| **Today** *(default)* | every open task **that matters today**, grouped **Overdue → Today → Upcoming → No date** |
| **All** | every open task, **ungated** — including ones the reminder window is still hiding |
| **Done** | completed + skipped tasks, grouped by the day they were resolved (Today / Yesterday / date), newest first |

"Matters today" is doing real work in that first row — see [Reminders](#reminders--what-gates-upcoming).
It still carries Overdue and the reminder-window slice of Upcoming: both are things today asks of you.
**All** is the escape hatch that makes the gate safe: without it a hidden task could not be opened,
edited, or deleted either. Both open views share `renderOpen` and differ only in the `gated` flag
passed to `TasksRepository.openSections`.

Empty sections are omitted entirely, so checking off the last task of a section makes the header
disappear with it. Sort within a section is due day ascending then
title, case-insensitive; **No date** sorts by `createdAt`. Section headers reuse the Events list's
treatment (bold `inkBlack` 13sp) so the two lists read as one family.

The trailing date label is relative and deliberately quiet: nothing at all inside **Today** (the
header already says it) or for an undated task, "Yesterday" / "*N*d ago" when overdue, "Tomorrow"
then a formatted date when upcoming.

### Reminders — what gates *Upcoming*

A **reminder** is a per-task lead time (`remindAmount` + `remindUnit`) that decides **how far ahead of
its due date a task becomes visible**. It is **not** a notification: no `AlarmManager`, no
`POST_NOTIFICATIONS`, no receivers, nothing that interrupts. It is purely a query, exactly like the
calendar's [Events reminders](calendar.md#reminders--paper-like-look-ahead) — the user only ever sees
it by *looking*.

The whole surfacing rule is `sectionFor(task, todayEpochDay)` in `TasksRepository.kt`, a top-level
function so it can be exercised directly (`TaskSectioningTest`):

| Task | Shown? |
|---|---|
| Undated | always, in **No date** |
| Due today | always, in **Today** |
| Overdue | always, in **Overdue** |
| Due later, `due − lead ≤ today` | in **Upcoming** |
| Due later, window not open yet | **not shown** |
| Due later, **no reminder at all** | **never shown until it is due** |

Once the window opens the task stays visible every day until its due date, when it graduates to
**Today**.

> **The last row of that table is the one to understand.** A dated task with no reminder is in *no
> section at all* on the main list until its due date arrives. This is the deliberate, chosen
> behaviour — strict parity with how events treat the look-ahead. The editor says so inline ("Leave
> blank and this task stays out of the list until the day it is due") because it is the single most
> surprising thing the screen does.
>
> **The [All view](#three-views) is what keeps that safe.** An event that fails the look-ahead gate is
> still drawn on the calendar grid; a task has no second view, so without All a hidden task could not
> be opened, edited, or deleted either — it would be unreachable until its due date. All drops the
> gate entirely, and marks each held-back row `hidden until <date>` (`visibleFrom`, rendered only in
> that view) so it reads as deliberately withheld rather than misfiled.

**One lead time, not a list.** An event carries several reminders; a task carries one. That loses
nothing: the rule is "visible on every day from `due − lead` onwards", so N reminders behave exactly
like their maximum and only the largest could ever have an effect. Storing one keeps the table
columnar with two columns instead of needing a child-row table for a set.

The reminder rides on the row, so a recurring task's successor inherits it through the same
`task.copy(…)` that carries the rule. Clearing the due date clears the reminder with it — a lead time
is measured back from a due date, so an undated task cannot carry one.

### Interaction

| Gesture | Open task | Resolved task |
|---|---|---|
| Tap the state box | mark **Done** (+ generate the successor) | **un-complete** back to Not done |
| Tap the row | open the editor | open the editor |
| Long-press the row | `ActionSheetDialog`: Edit · **Skip** · Delete | Edit · **Mark not done** · Delete |

Skip lives in the long-press menu rather than on the row: it is the rarer of the two choices, and a
second always-visible control on every row would clutter a list whose whole point is calm. Its glyph
is a **square-minus, not an ×** — a cross reads as "deleted", and a skip is the opposite.

Completing a recurring task Toasts the successor's due date. That is the non-obvious part of the
interaction: the row just checked off vanishes and a new one appears somewhere else in the list.

### Un-complete

`TasksRepository.reopen` returns the task to `NOT_DONE` and **withdraws the successor its resolution
generated** — hard-deleted, not tombstoned, because that row was machine-generated and never user
content; a tombstone would leave an invisible duplicate in the series forever.

The successor is only withdrawn when it is the **direct, untouched next row**. `maxSeriesIndex`
distinguishes "my successor is still sitting there" from "the series has already moved past me",
including the case where the successor was itself resolved and the series then ended. If the user has
already acted on later occurrences, nothing changes and the caller Toasts
`SERIES_MOVED_ON` — rewinding past the user's own work would be worse than refusing.

### Editor — `TaskEditorDialog`

Title, an optional due date via the shared [`DayPickerDialog`](calendar.md#date-picker), the
[reminder](#reminders--what-gates-upcoming) (a blank amount means none), and the
recurrence builder ported from [`EventEditorDialog`](calendar.md#repository--ui) (frequency, "every
N", weekly weekday toggles, monthly day-vs-ordinal, ends Never / on a date / after N). Standard e-ink
dialog treatment: `shape_dialog_bordered`, `setElevation(0f)`. Delete appears only when editing.

The dialog never touches `seriesId` / `seriesIndex` / `seriesAnchorDay` — `TasksRepository.save` owns
that bookkeeping (`withSeriesFields`): a one-time task has all three cleared; a new recurring task
gets a fresh series anchored on its due day; and **moving an existing recurring task's due date
re-anchors it**, so a rescheduled "every 3 days" continues from the new date rather than snapping
back to the old phase grid.

**A recurring task must be anchored to a day** — and so is a reminder, which is a lead time measured
back from one — so both blocks are gated on the due date: the recurrence builder is replaced by a
one-line explanation and the reminder row is hidden entirely until a due date is set — better than letting the user assemble a rule that silently
cannot be saved. Clearing the due date drops the rule rather than keeping a hidden one that would
spring back. A weekday set equal to just the anchor's own day is stored as **mask 0**, so moving the
due date carries the rule with it.

Save-time validation: a blank title is rejected, and so is an "ends on" date earlier than the due
date — which would store a series whose first occurrence is already past its end (the same class of
bug the events editor [paid for on-device](global-index-format.md#events--the-one-table-that-is-not-universal-row-shaped)).

---

## Entry points

| Surface | Control | Notes |
|---|---|---|
| Library bottom bar | `btnTasks` in `surfaceButtonsGroup` | Third sibling-surface button, after Calendar and Scratch Pad |
| Notebook toolbar | `btnTasks`, registry key `"tasks"` | Plain launch, not for-result — nothing comes back into the page |
| Calendar toolbar | `btnCalTasks` in `calLeftBar` | Overflows via the shared `ToolbarOverflowManager` like every other tool |

**The narrow width bucket demotes it to the overflow row.** On `layout-sw360dp/activity_main.xml` the
bottom bar is already at 428dp of the Palma2 Pro's 439dp and cannot absorb another 48dp, so `btnTasks`
lives in `overflowToolbar` there instead of `surfaceButtonsGroup`. The **id exists in all three
variants** — a missing id makes the view-binding field nullable, the same reason the narrow variant
keeps its pagination buttons in the tree — so `MainActivity` wires it once with no per-variant branch.
Visibility falls out for free: every mode that hides `surfaceButtonsGroup` already calls
`closeOverflowToolbar()`.

The notebook button is a normal `ToolbarButtonRegistry` entry and obeys the **KEY STABILITY RULE**
(append only). Users with an existing persisted toolbar config pick it up automatically —
`ToolbarPreferencesManager.load` appends registry keys missing from a saved order, and
`ToolbarLayoutManager.resolveVisibleKeys` appends them defensively too. Verified on a G102 whose
toolbar was already customized to a floating vertical bar.

---

## Encryption & backup

Tasks live in `notesprout.db`, which is [SQLCipher-encrypted at rest](encryption.md#the-global-index-is-encrypted)
under the **global** passphrase. Like the calendar and events there is no per-feature encryption gate:
task content is always global-scoped, so nothing here can downgrade a notebook's secret. Backup
coverage is automatic — the whole index is backed up index-last (see [`backup.md`](backup.md)).

---

## Routines — reserved, not built

A **routine** is a named set of tasks. It is not implemented; the schema only leaves the door open:

- `type` discriminates `TASK` from `ROUTINE` rows, and **every existing query filters `type = 'TASK'`**.
- `parentId` is the member → routine edge (always `null` today).
- `"order"` is the step order within a routine (always `0` today).

So the routines effort is purely additive: a `ROUTINE` row, member rows pointing at it, and UI. No
existing query has to change to stay correct.

## Deferred

- No import / export of tasks.
- No notifications or alarms of any kind — the paper-planner model the
  [Events reminders](calendar.md#reminders--paper-like-look-ahead) established holds here too.
- No notes, time-of-day, or priority field (deliberate v1 scope).
- No cross-links to notebooks or calendar days.
