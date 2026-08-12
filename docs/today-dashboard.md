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
- **Two-finger swipe down** (`core/TwoFingerSwipeDown.kt`) — the shortcut, on the notebook, all three
  calendar views, all four views of the day window, and both task screens. A jump point reachable only from the library
  is not much of a jump point; this is the way back to it from wherever the user is. The dashboard is
  **pushed**, not brought forward: Back returns to the page or the day that was open, and the
  `SurfaceStack` records it above them, so a cold launch reopens the whole chain. See
  [the gesture](#the-two-finger-swipe-down) below.
- **Launch restore.** `AppSurface.TODAY` on the `SurfaceStack`, so a cold launch reopens the dashboard
  along with whatever was above it. The entry carries **no payload** — the screen reads everything
  from "now" on every resume, so a restored instance is indistinguishable from the original.

### The two-finger swipe down

`core/TwoFingerSwipeDown.kt` — a self-contained detector each screen feeds finger events to, plus a
`cancel()` its `cancelFingerGestures()` calls. It is the one gesture in the app that is **shared
rather than ported**: it means the same thing everywhere, and the copies of the multi-finger
double-tap on five screens are the argument for not making a sixth set.

**The gates** mirror `NotebookActivity.evaluateSwipeDownToc` — the one-finger down-swipe that opens
the table of contents — so the two feel like siblings. The centroid of the two fingers must travel
downward, be vertical-dominant, cover ≥ 30% of screen *height*, and either carry velocity ≥
`scaledMinimumFlingVelocity` or reach ≥ 50% of height. Direction comes from displacement, never from
velocity, which flips sign as a finger decelerates at lift-off.

- **Why two fingers, and vertical.** One finger down already opens the ToC, and two fingers sideways
  already inserts a page. Vertical *and* two-fingered is the free corner of the notebook's existing
  vocabulary; nothing had to be given up to make room. The insert swipe demands horizontal dominance
  and this one vertical, so the two can never both fire on one gesture — which is why the notebook
  runs this detector alongside its own two-finger tracker instead of inside it.
- **Armed on the second finger down, fired on the first finger up** (`ACTION_POINTER_UP`, where both
  pointers are still reported so the end centroid is measured exactly as the start one was). A third
  finger landing on a swipe that *already* qualifies commits it before disarming — the same
  early-commit the page-insert swipe makes, so a palm joining late can't swallow a real gesture.
- **Behind the pen-activity gate** on every host, like every other finger detector: a palm rolling
  across the glass mid-word is not a gesture. See [`drawing-engine.md`](drawing-engine.md).
- **Where the gesture runs among the other detectors.** Last. A two-finger stationary double-tap is
  the undo gesture and arms this detector too; anything that took the sequence before
  `handleMultiFingerDoubleTap` saw it would cost undo.
- **The task screens have neither a pen gate nor a tool-type filter.** There is no canvas to protect a
  stroke on, and needing two pointers already makes the gesture deliberate, so `TasksActivity` and
  `RoutineActivity` each override `dispatchTouchEvent` to feed the detector everything and pass it
  straight on. A **finished** routine is read-only but still swipes: `readOnly` withdraws what would
  edit the occurrence, and leaving for another screen is not that.
- **Not on the scratch pad or the sticky-note editor**, by decision rather than omission: both are
  focus surfaces — one thing, briefly, then out — and a jump to another screen has no place in them.

#### No host consumes — and why the day window tried to

Every host returns the event to normal dispatch. On the notebook and the calendar there was never a
reason not to: their content cannot scroll, and their single-finger detectors have already written
themselves off at the second pointer-down (`calMultiTouch` and the notebook's equivalent).

The day window looked like the exception. Its **Events** view — the view it opens on — is a
`ScrollView`, and a `ScrollView` re-targets to whichever pointer went down last, so it follows a
two-finger swipe as readily as a one-finger drag; the list slides a little under the gesture on the
way out, and on e-ink a scroll is a full repaint. It briefly took the whole sequence instead
(cancel the content, then swallow through to the last finger up, and only once the fingers had moved
past `scaledTouchSlop`).

**That was reverted.** Android splits pointers across children by default, so a second finger landing
on a different row is a real tap on a real view — and an Activity-level swallow throws that away for
every touch in the sequence, not just the swipe. **A list scrolling slightly on the way out is the
far cheaper problem.**

The removal came out of a false lead worth recording, because the next person will hit it too. Tapping
a row with a thumb resting on the glass does nothing on the **G102** — which looked exactly like the
swallow eating taps, and was not: it still failed with the swallow gone, and it fails the same way on
screens that have no gesture code at all. A `getevent` capture off `onyx_ts_istaric` settles where it
goes wrong: the digitizer reports **both** contacts, slot 1 with its own tracking id, position and
pressure, so the panel is fine and something above the driver drops the second tap. See
[the device note](#a-resting-contact-suppresses-taps-on-the-g102).

#### A resting contact suppresses taps on the G102

Device behaviour, not ours, and not something the app can route around — but it shapes what gestures
are worth designing. On the BOOX Go 10.3 Gen 2, a contact resting on the glass suppresses taps from a
second finger **app-wide**, including screens with no custom touch handling. The raw stream shows the
hardware reporting both contacts, so this sits above the driver.

Two things follow. First, **never diagnose a missing tap on that device from app code alone** — check
whether the same tap works with nothing resting on the glass before suspecting a detector. Second,
this gesture is unaffected: it needs two fingers that *travel*, and the capture shows both contacts
tracked continuously through a drag.

Also from the capture, for anyone tuning thresholds: the digitizer is 1:1 with the panel (X max 1859,
Y max 2479 against 1860×2480) at 300 dpi, and a resting thumb drifts on the order of 15 px — right at
`scaledTouchSlop` (8dp ≈ 15 px here). Any "has it moved?" test on a centroid that includes a resting
finger is therefore borderline by construction.

### Staying on today

`onResume` re-reads the date, and an `ACTION_DATE_CHANGED` / `TIME_CHANGED` / `TIMEZONE_CHANGED`
receiver catches the case `onResume` cannot: the screen left open *across* midnight, which is ordinary
on a device that is never really switched off. Same reasoning and the same receiver as
[`TasksActivity`](tasks.md).

### The index guard

`onCreate` calls `IndexGuard.ready(this)` before anything else and returns if it is false. The
dashboard is where this problem was found — Android rebuilding a task after a background process kill
never runs `BootstrapActivity`, so every surface reads a closed index and throws — but it was never a
dashboard-specific concern, and the guard now covers all eighteen index-backed surfaces. See
`core/IndexGuard.kt`.

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
before the tap rather than after it. It routes through `DayHistoryRepository.locksFor` — one batched,
blob-free read for every row on screen (never `coverFor`, whose full-row fetch drags each cover blob
out of the index for a boolean) — which inherits the rule that a GLOBAL-scope notebook is **not**
locked — the index key already covers it — instead of restating that rule and getting it wrong. See
[`encryption.md`](encryption.md).

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

The three sections load **concurrently** — three child coroutines in `refresh()` — so each paints as
its own data arrives rather than notebooks (the slowest) waiting behind tasks and events.

The notebooks read is still the expensive one, but it is batched: `notebooksForDay` fetches the
folder list once and shares it across the three kinds, the CREATED derivation is a date-ranged query
rather than a scan of every notebook, opened/edited ids resolve through one blob-free
`ObjectSummary` batch read, `RecentsManager.resolve` takes an exclude-set and limit so only rows that
will actually show are looked up, and the lock icons come from a single `locksFor` batch. Even so it
runs on resume, not on every check-off; that is what `refreshTasks()` is for.

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
