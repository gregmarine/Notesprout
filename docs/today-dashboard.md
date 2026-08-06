# Today — the dashboard surface

A full-screen focus view of **today**, and the jump point for the rest of Notesprout: what today asks
of you, and a way to reach whichever surface owns it.

**It has no drawing surface and it does not edit.** Every row is a jump to the screen that owns the
thing — a task to the task list, an event to the day window, a notebook to the notebook. The single
exception is the task state box, which checks a task off and un-checks one resolved today. Everything
else this screen can do is *create*, and each create hands straight to the editor that already exists.

It owns no data. Three stores that already existed are asked three differently-shaped questions;
nothing here has a table, and the whole feature shipped without a schema change or a migration.

---

## Screen — `TodayActivity`

Two shapes, chosen by `R.bool.today_single_screen` (`values/` false, `values-sw600dp/` true):

```
SINGLE SCREEN (sw600dp+ — G102 992dp, MAX)     TABBED (below — G6 571dp, P2P 439dp)
┌──────────────────────────────────────┐        ┌──────────────────────┐
│ ←  Today      Wed, 5 Aug   📅 ☑ ✏️  │        │ ←  Today  5 Aug 📅 ☑ ✏️│
├─────────────────┬────────────────────┤        ├──────────────────────┤
│ Tasks        +  │ Events          +  │        │ Tasks│Events│Notebooks│
│ Overdue         │ All day  Bin day   │        ├──────────────────────┤
│ ☐ Change filter │ 09:00    Standup   │        │ Tasks             +  │
│ Today           │                    │        │ Overdue              │
│ ☑ Morning pages │                    │        │ ☐ Change filter      │
│     ‹ 1/4 ›     │      ‹ 1/4 ›       │        │      ‹ 1/4 ›         │
├─────────────────┴────────────────────┤        └──────────────────────┘
│ Notebooks                         +  │
│ Today                                │
│ Journal      Journal · created  8:04 │
│ Recent                               │
│ TestAlpha    Notebooks       7d ago  │
│              ‹ 1/2 ›                 │
└──────────────────────────────────────┘
```

`layout/activity_today.xml` and `layout-sw600dp/activity_today.xml` carry an **identical id set** —
the tab row exists in the wide variant too, just never made visible. That is what keeps the view
binding's fields non-null and lets `TodayActivity` run one code path for both. Any view the Kotlin
never touches carries no id at all, so it cannot become a nullable binding field by accident.

### Key files

| File | What it is |
|---|---|
| `TodayActivity.kt` | The screen: chrome, tabs, row rendering, the check-off, the create actions |
| `TodaySection.kt` | One section — its title, `+`, empty state, group headers, and **the pagination** |
| `data/TodayRepository.kt` | The three reads, and the dashboard's own definition of "today" |
| `data/events/EventRowFormat.kt` | Event row wording, shared with the day window |
| `layout/view_today_section.xml` | One section frame, `<include>`d three times |
| `layout/item_today_notebook.xml` | The notebook row (tasks and events reuse their own screens' rows) |
| `layout{,-sw600dp}/activity_today.xml` | The two shapes |
| `values{,-sw600dp}/bools.xml` | `today_single_screen` |
| `drawable/ic_layout_dashboard.xml` | Tabler `layout-dashboard`, the only new icon |

### Chrome

Back · divider · **Today** · the date · then jumps to **Calendar**, **Tasks**, **Scratch Pad**. There
is no Library button: the library is the only place this screen launches from, so Back already goes
there. The date is `FormatStyle.FULL` on the single screen and `MEDIUM` where the bar is tight.

The system bars stay visible, so the top guard is the live inset (`TopGuard.applyInsetPadding`) rather
than the fixed reservation the drawing screens use — there is no canvas here that wants to be
full-bleed. See [`design-system.md`](design-system.md).

### Entry points

- **The library bottom bar.** `btnToday` leads `surfaceButtonsGroup` on `layout/` and
  `layout-sw480dp/`. On `layout-sw360dp` it lives in `overflowToolbar` instead, alongside `btnTasks`:
  the bar is already at 428dp of P2P's 439dp, and a fourth surface button would overlap the centred
  pagination. Adding Today is what moved Tasks off the bar in **all three** variants — see
  [`mainactivity-and-recents.md`](mainactivity-and-recents.md).
- **Launch restore.** `AppSurface.TODAY` on the `SurfaceStack`, so a cold launch reopens the dashboard
  along with whatever was above it. The entry carries **no payload** — the screen reads everything
  from "now" on every resume, so a restored instance is indistinguishable from the original.

### Staying on today

`onResume` re-reads the date, and an `ACTION_DATE_CHANGED` / `TIME_CHANGED` / `TIMEZONE_CHANGED`
receiver catches the case `onResume` cannot: the screen left open *across* midnight, which is ordinary
on a device that is never really switched off. Same reasoning and the same receiver as
[`TasksActivity`](tasks.md).

### The index guard

`onResume` calls `indexReady()` **before** `SurfaceStack.markTop`. If the global index is closed it
bounces through `BootstrapActivity` — the single owner of opening and unlocking — and returns `false`,
which also skips `markTop` so the surface stack survives intact for Bootstrap's restore to read.

This is not a dashboard-specific concern. Every repository in the app throws when constructed against
a closed index, and Android rebuilding a task after a background process kill is a route that never
touches Bootstrap. `TodayActivity` is currently the **only** guarded surface; the other six are
recorded in `BACKLOG.md`.

---

## Sections and pagination — `TodaySection<T>`

The dashboard's premise is that everything fits on screen at once, which only holds if a section that
*doesn't* fit turns into pages rather than a scroll — a scrollbar would make it a web page.

`TodaySection` is generic over its row type and shared by all three sections. Its inputs are a title,
an add hint, an empty message, an `onAdd`, and a `makeRow` lambda; its input data is
`List<TodayGroup<T>>`, a labelled run of rows.

### How a page is packed

1. **Build every cell** — one View per group header and per row, via `makeRow`.
2. **Measure each** against the real inner width and `UNSPECIFIED` height. Rows are genuinely
   different sizes (a two-line title, a visible meta line), so a uniform row height would be wrong.
3. **Pack** into pages that fit the band.

Three rules that were each paid for in a bug:

- **The last cell on a page does not have to fit its own bottom margin.** That margin separates it
  from a row that isn't there. Requiring it cost a whole row whenever the remainder landed within one
  margin's width of the band — which is exactly what happened on G102 (`pages=[6,6,6,6,3]` where
  `[7,7,7,6]` fit).
- **Headers are emitted lazily, with the first row that follows them**, so a header can never be
  stranded at the foot of a page; when a group spills, it is re-emitted at the top of the next one. A
  page of rows under no heading would leave overdue work indistinguishable from today's.
- **`used > 0` guarantees progress** — a single row taller than the band is placed anyway rather than
  looping forever on a page it can never fit.

The debug log (`Slog.d("TodaySection")`) reports the band and cell heights **in pixels, not dp**: the
packing is integer-pixel arithmetic, and rounding to dp once hid a one-pixel overflow that cost a row.

### Measurement

The host is unmeasured on first entry, and on a tabbed device a section that isn't the open tab has
**no size at all** until its tab is chosen. `whenMeasured` therefore waits on a self-removing
`OnGlobalLayoutListener` rather than guessing, guarded by a `token` so a superseded `submit` cannot
paint over a newer one. The height comes from the list's **parent frame** — the list itself is
`wrap_content` and would report the height of whatever is already in it.

### The pager

**Prev / next only, never first / last.** "Today" is a small set by construction and jumping to the end
of a two-page list is chrome that earns nothing.

The arrows are **always drawn and never disabled**; they simply stop at the ends. A disabled control
is visually silent on e-ink, so a greyed-out arrow reads as a live one that has broken.

The pager row is `INVISIBLE`, never `GONE`, when a section fits on one page. Its 44dp has to stay
reserved either way: hiding it outright would give the list more room than it was measured against,
and revealing it on the next refresh would clip the very rows that had been made to fit.

### Group headers

A lone group is **unlabelled** by default — "Events" over a single run of rows already says what they
are. `alwaysLabelGroups` overrides that, and **Notebooks sets it**: that section's title names a
*category*, not a time, so an unlabelled lone **Recent** group would read as work done today.

### Refresh

`refresh()` re-reads all three sections; `refreshTasks()` re-reads only the tasks section and is what
the check-off calls. Ticking a task cannot change an event or a notebook, and the notebooks read is
not cheap (see below) — paying it on every checkbox tap is work the user waits for on a slow-repaint
device. The current page number **survives a submit**, so a refresh doesn't throw the reader back to
page 1.

---

## Tasks

Everything **overdue or due today** that is still open, plus everything **resolved today**, grouped
`Overdue` then `Today`. Rows reuse `item_task.xml`, so they read exactly like the Tasks screen's.

Two things make this different from the Tasks screen's own Today view:

**Routine steps surface individually, and routines do not.** A step is real work due today whether or
not its routine is open, so it appears on its own, carrying its routine's name in the meta line. The
parent routine row is deliberately absent — it would be the same work counted twice, on a screen with
no room to spare. This is the one place in the app that steps outside
[`TaskDao.MAIN_LIST`](tasks.md): `openDueBy` filters `type = 'TASK'`, which catches standalone tasks
**and** steps while excluding routines.

**Resolved rows stay exactly where they were.** `ROW_ORDER` is due day, then title —
**state is not a sort key**. Sorting ticked rows to the bottom of their group reads well on paper and
is wrong here: the list is paginated, so a row that sinks on being ticked can sink onto *another page*,
and undoing a mis-tap where it happened is the entire reason resolved rows stay on this screen. Paper
doesn't reflow when you tick it either. They are gone tomorrow.

Undated and future-dated tasks are not here at all. The Tasks screen is one tap away and holds the
whole picture.

### The one edit

The state box checks a task off, and un-checks one resolved today.

A routine step goes through `TasksRepository.resolveMember`, so ticking the last open step completes
its routine and rolls it forward exactly as it would inside `RoutineActivity` — a step is a step
wherever it is ticked. Refusing here would make one checkbox behave differently for a reason invisible
in advance. Unlike that screen this one stays put and refreshes; there is nothing to step out of.

A toast names what isn't self-evident: a recurring task's next due date, a routine's completion and
next period, and `ReopenOutcome.LOCKED` when a finished routine's step can't be reopened. **That
irreversibility is a known sharp edge** with the context this screen removes — see `BACKLOG.md`.

The rest of the row jumps to where the task lives: a step to its `RoutineActivity`, everything else to
`TasksActivity`.

### The queries

Three, added to `TaskDao` with no schema change:

| Query | For |
|---|---|
| `openDueBy(day)` | Open rows due on or before today. **The one place outside `MAIN_LIST`** |
| `resolvedOnDay(start, end, day)` | Resolved today, `dueEpochDay <= today` so a task completed early elsewhere doesn't appear on a screen it was never on |
| `routineTitles()` | Routine id → title, once, for the step meta lines |

---

## Events

The day's events, direct and recurring alike, from `EventsRepository.eventsForDay` — all-day first,
then by start time, the ordering that repository already applies. One group, so no header.

**No look-ahead.** The day window's Events view also carries reminder-gated *Upcoming* rows; the
dashboard deliberately does not. "Today" here means today, and an event you asked to be warned about a
week early is exactly what would crowd out what is actually happening now. The look-ahead stays where
it lives, one tap away. See [`calendar.md`](calendar.md).

Rows reuse the day window's `item_event.xml` with the delete button hidden. A tap opens **today's day
window**, which owns editing and the recurring-scope prompts that go with it ("this occurrence / this
and following / all"); reproducing any of that on a focus view would be a second place to get it
wrong. No view is passed — a normal open already lands on Events, and nothing here is coupled to the
day window's own view enum, which is private to it.

`EventRowFormat` holds the leading time badge and the meta line (type · ends · multi-day span ·
recurrence), and **both** this screen and `EventsController` use it. The two surfaces show the same
events in the same layout; kept apart, they would eventually describe one event two different ways.

---

## Notebooks

Everything touched today under **Today**, then the most recent notebooks that weren't, under
**Recent** — capped at `RECENT_LIMIT` (10) and **deduped against today's rows**, so a notebook opened
this morning appears once rather than twice with contradictory labels.

The second group is the part that isn't obvious. A dashboard that listed only today's activity would
be blank every morning — precisely when a jump point is most useful.

- **Today** comes from `DayHistoryRepository.notebooksForDay`, one row per notebook carrying that
  day's merged flags (`created · opened · edited`), and shows a clock time.
- **Recent** comes from the device-local `RecentsManager` (SharedPreferences, not the index — the one
  store on this screen that isn't a database), and shows a relative day: `Yesterday`, `Nd ago`, then a
  plain date past a week, where "N days ago" stops being easier to read than the date.

Rows are `item_today_notebook.xml`: icon · name over folder + activity · trailing time. The meta line
carries only the **last** folder segment, not the full breadcrumb — it is what tells two same-named
notebooks apart, and a deep path would swallow the activity that follows it.

**No cover thumbnails, deliberately.** This is a jump list, not the library: a name and its folder
identify a notebook faster than 44dp of its first page, and decoding a snapshot per row on every
refresh is real work for no information.

The leading icon is a **lock** for NOTEBOOK-scope encryption, so a passphrase prompt is expected
before the tap rather than after it. It routes through `DayHistoryRepository.coverFor`, which is what
makes it inherit the rule that a GLOBAL-scope notebook is **not** locked — the index key already
covers it — instead of restating that rule and getting it wrong. See [`encryption.md`](encryption.md).

A tap opens the notebook through the ordinary `NotebookActivity` path, so an encrypted one meets its
usual unlock flow; the dashboard knows nothing about keys beyond drawing the icon.

---

## Creating

Each `+` hands to the editor that already owns the thing.

| Section | `+` does |
|---|---|
| Tasks | `TaskEditorDialog`, new task |
| Events | `EventEditorDialog`, defaulted to today |
| Notebooks | The library's folder-picker → name + template → create flow |

### Why the notebook flow leaves, and how it comes back

Choosing the destination folder is a **mode of the library's grid** (`enterPickerMode`), and this
screen has no browsing by design, so the flow genuinely belongs over there. It is reached with
`MainActivity.EXTRA_START_NEW_NOTEBOOK`, the same extra the calendar's New Notebook button uses.

Handing over costs this Activity: `CLEAR_TOP` onto the root library pops the dashboard, so the new
notebook would open with the library beneath it — while a notebook merely *opened* from the same
section closes back to the dashboard. The same section behaving two ways.

`MainActivity.EXTRA_RETURN_TO_TODAY` asks for it back: before opening the new notebook, the library
rebuilds the dashboard beneath it. The task ends as `MainActivity → TodayActivity → NotebookActivity`,
and closing the notebook returns here.

**Cancelling still ends in the library** — nothing was created, so there is nothing to sit under. The
flag is not saved instance state: rotating mid-flow loses it and the notebook opens over the library,
which is the behaviour before any of this existed. Keeping it on the Intent would survive rotation but
outlive the flow, and the next notebook created from the library's own button would be sent here too.

---

## Cost

Rows are inflated into `LinearLayout`s and rebuilt wholesale, matching every other list in the app —
there is no `RecyclerView` (deliberately not a dependency). What bounds the work is that "today" is
small by construction, **not** the pagination: `buildCells` inflates and measures a View for the
*whole* result set before packing, so a library with a very long overdue tail pays for every row even
though only one page is attached. There is no `LIMIT` on `openDueBy`. Recorded in `BACKLOG.md`.

The notebooks read is the expensive one — `notebooksForDay` walks the folder tree three times, lists
every notebook for the CREATED derivation, and `coverFor` adds a lookup per displayed row. It runs on
resume, not on every check-off; that is what `refreshTasks()` is for.

---

## What this screen is not

Settled during planning and worth keeping settled:

- **Not the home screen.** It is a sibling surface reached from the library, which keeps
  `BootstrapActivity`'s forwarding and the library's "implicit bottom of the stack" invariant
  untouched. An "Open on launch: Library / Today" preference is the separable change that would make
  it home — deferred, in `BACKLOG.md`.
- **No content previews.** Day note and scratch pad stay jump buttons; nothing renders their contents.
- **No weather, greeting, counts or streaks.** It is a focus view, not a metrics screen.
- **No drawing surface, and no editing beyond the state box.** Every other change happens on the
  screen that owns it.
