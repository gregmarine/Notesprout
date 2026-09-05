# The Calendar (arc 23)

A basic writable organizer, the way a physical one is one: **Month, Week and Day pages**, each a
full writing surface whose strokes are recorded in the extension's own store. It is an extension
APK (`NSE · Calendar`, `:ext-calendar`, package `…notesproutsn.ext.calendar`) with its own process,
its own g-paper surface and its own undo stack — the pad's shape, ridden on the pad's rules, with
the placement made a real type. Arc 23 shipped in three phases plus a user-checklist follow-up:
**Y1** (commit `6a16017a`, 2026-09-02) — the seam, `:ext-ink`, `:ext-calendar` and the Month page,
the library door only; **Y2** (commit `eaf8d8ce`, 2026-09-02) — the Week and Day pages, navigation,
the day picker, double-tap; **Y3** (commit `b8ec3fbd`, 2026-09-02) — the notebook door and both
transfers; **Y4** (2026-09-02) — the code-review pass (below), then three of the user's own
checklist calls the same day: the Scratch Pad moved to the last slot on every bar with the calendar
immediately before it, the calendar grew its own Scratch Pad door, the three view latches became
Tabler icons, and the day picker's window width and title hit-box were fixed. Each phase's user
checklist passed the same day it landed.

`ACTION_CALENDAR` + `ACTION_CALENDAR_SCREEN` is SN's **SEVENTH capability point**, granted by the
user 2026-09-01 — no EIGHTH without another decision. It is the **fourth screen-owning (tier-2)
point** (after the scratch pad, the document editor and the tag manager) and the **second with
paper** (after the scratch pad). The point was born at `ExtensionContract.API_VERSION` 7, and the
host's floor is per-action: a calendar service is accepted only at
`MIN_API_VERSION_FOR_CALENDAR` (7) and above — there is no older calendar shape to accept.

This is the pad's own reference restated for a second paper surface — read `docs/scratchpad.md`
first if you have not; most of this doc is "the same, except." The seam in full (contracts, the
extension store, the boundary audit) is `docs/extensions.md`; the notebook's and library's own
chrome are `docs/notebook.md` and `docs/library.md`.

## What it is, and is not

Month, Week and Day are three magnifications of one organizer: a Sun–Sat 6×7 grid for a month, a
2×4 grid for a week, and 24 half-hour rows for one half of a day (AM or PM — a day owns two pages).
Every page is a writing surface with the notebook's own pen and eraser, fixed. Navigation, the day
picker and the three layouts are read from Notesprout's original ("og") calendar
(`docs/calendar.md` at the monorepo root, `CalendarActivity` / `CalendarTemplateRenderer`) as a
**reference for the geometry and the gestures only** — nothing is copied, and every number here was
re-derived and re-tested for this codebase.

**Not in this arc, on the user's explicit call:** events, tasks, reminders, the day window, history,
day notes, calendar export, and the Today dashboard. Each of those is og's, each is a real feature,
and each would need its own fresh user decision before it becomes an eighth extension or a growth
of this one — nothing here should be read as a step toward any of them. What Y1–Y3 built is a place
to write on a date, and nothing more.

## Why an extension, and why `:ext-ink`

The scratch pad's reasons carry over whole: a second in-process drawing surface would have been a
second `RattaNotebookView` sibling-copy trap, and the seam already proved a screen-owning point can
hold real state with no `.soil` in sight. The calendar **writes nothing to disk itself** — its
rows live in the host's encrypted per-package extension store, lent for the showing and revoked
with the unbind — and both transfers are copies that cross **only through the held bind**, never
the Intent, never a file. Its tools are the notebook's, fixed, for the pad's exact reason: a second
writing surface that felt different from the notebook one tap away would read as a bug.

What is new here is `:ext-ink` — a library module (`:extension-api` + `:sn-screen`, never `:app`)
carved out of the pad in Y1 so the calendar is not the pad's sibling copy either. Before Y1, every
piece of "ink on rows" logic — the wire↔paper mappings, the row↔stroke decode, the batch splitter,
the ranged read planner, the in-memory page with its op log, the four stroke-level undo actions —
lived inside `:ext-scratchpad` as `Scratch*`. Y1 moved the six files verbatim (tests included, no
behaviour change) under neutral names — `ScratchInk` → `InkWire`, `ScratchReadPlan` →
`StrokeReadPlan`, `ScratchBatches` → `StoreBatches`, `ScratchDocument`'s stroke half → `InkDocument`,
`ScratchUndo`'s stroke kinds → `InkAction` — and repointed the pad at them. `StrokeRows` moved as
itself. `:ext-ink` is the one module that depends on **both** `:sn-screen` and `:extension-api`
(`api` on each), which is exactly why these helpers could not live in `:sn-screen` proper: keeping
the contract out of `:sn-screen` is what keeps the host's `TransferCaps` and the extension's ink
mapping two deliberate twins rather than one shared class quietly becoming part of the wire format.
`ScratchSql` and `ScratchSqlTest` did **not** move — the pad's SQL strings stay its own, and
`CalendarSql` is the calendar's own twin of that same shape.

**`:ext-ink` grew past the store helpers at Y4.** The Y1 move carried the wire mapping, the row
codec, the batching, the read planner, the op log and the stroke-level undo out of the pad — but it
left the screen, the service and the session behind, and by Y3 `CalendarActivity` and
`ScratchPadActivity`, `CalendarService` and `ScratchPadService`, `CalendarSession` and
`ScratchSession` were a second sibling copy sitting right below the line the seam spec had drawn:
the store was shared, the thing that used it was not. The Y4 review found it and named it for what
it was — `RattaNotebookView` one layer up — and the user chose to close it rather than leave it as a
known gap: the stroke SQL/DDL (`InkSql`), the ink half of a consumer's document as a contract
(`InkPage`), the transfer session (`InkTransferSession<P, R>`) and the tier-2 screen skeleton
(`InkScreenActivity`) all moved into `:ext-ink` in the same sweep, and both consumers were
repointed at them. See § The store, § Undo, § Both transfers and § Frame silence below for what each
one replaced.

## The three pages (geometry)

`CalendarGeometry` (pure, no `android.graphics`, JVM-tested) is where every rect on a calendar page
comes from — the template painter draws what it says and the finger hit-test reads it back, so the
two can never disagree. The governing rule for Month and Week, stated in the file itself: **every
dimension is width- or dp-derived; height slack goes to the Notes band; nothing is a proportional
slice of the height.** The Day page is the one exception, by decision (Z5b Manta check, 2026-09-04):
its rows share the whole height evenly and there is no band. og's height-derived Day rows were a
ledgered bug (`BACKLOG.md`) because og's *canvas* changed height under the same page — the top
guard; here the page is the whole screen, the bars overlay it and the guard is 0 on Ratta, so the
height a page is laid out at is the height it is drawn at.

- **Month** — a day-of-week header band (`DOW_HEADER_DP` 40), then a 6×7 grid of **square** cells
  sized from the content width (seven cells and six hairlines fit the width; only on a page too
  short for six rows plus the header does the square shrink to fit the height instead — never on a
  Nomad, but a store can travel), then a Notes band taking whatever height is left. Out-of-month
  cells are grey and fully writable — there is no "outside" a pen cannot touch.
- **Week** — a 2×4 grid (Sun–Sat plus one spare eighth cell) sized over **Month's own grid area**:
  the same header height, hairline and six-row band that Month's grid occupies, halved into two
  rows, so the Notes band below it is Month's band to within the integer rounding of that halving
  (pinned as a range, not an exact match). The spare cell is blank paper, unlabeled, and hit-tests to
  null exactly like a hairline or a margin does — it is nobody's day.
- **Day** — `DAY_ROWS` (24) half-hour rows for one half sharing the height between the bars
  **evenly** (the height less 23 dividers, integer-divided by 24, never below 1 px) and a left
  gutter (`DAY_GUTTER_DP` 80) holding the time labels. **No Notes band and no closing hairline**:
  the remainder of the integer division — at most 23 px — goes to the last row (`rowHeight(i)`), so
  `rowsBottom` is the bottom bar's top exactly and the bar's own 1 dp border closes the ledger. A
  taller page is taller rows (the Manta's rows are taller than the Nomad's); a shorter page is
  shorter rows, so the whole twelve hours always show above the bar. Until Z5b the rows were a
  fixed 34 dp with a labeled "Notes" slack band below — the Manta's taller page turned that band
  into a section, and the decision was that a day is a ledger, not a ledger over a note.

**Today** is a ring around the day's number, drawn once by `CalendarTemplate.dayCell` (shared by
Month and Week so the ring arithmetic exists exactly once) — **nothing selects**. There is no
selected-day border anywhere on Month or Week; the Day page's own title already names the date it
is showing. Titles come from `CalendarDates`' hand lists, never a `java.time` formatter — arc 5's
rule, because CLDR data drifts between devices and a page title is chrome, not locale data:
`monthTitle` → "September 2026", `weekTitle` → "Aug 30 – Sep 5, 2026" (the year repeats on both
sides only when the week straddles one, the month only when it changes), `dayTitle` → "Tue, Sep 1,
2026 · AM". `dayRowLabel(half, slot)` is built the same way, from ints: "12:00 AM" … "11:30 PM",
AM/PM out of `CalendarDates.HALF_NAMES`.

**Hairlines are `round(density)` px on integer edges** — a 1 dp line at the Nomad's 1.875 density is
a coin flip drawn any other way, the standing trap this family has hit before. Every edge
`CalendarGeometry` names is an `Int`.

The template is a transparent `ARGB_8888` bitmap **baked at the page's own size**, under the two
chrome bars' **measured** heights (`CalendarActivity.awaitLaidOut()` — never a dimen guess), and set
on the paper as g-paper's page template: grid and ink scale and register together, so a store
carried to a different screen still lines up. It is baked **once per** (page, day, page size, the two
bars' measured heights) — `CalendarActivity.applyTemplate` keeps that `BakeKey` and the bitmap it
produced, and a `showPage` whose key is unchanged (an undo or a redo on the showing page) reloads
the strokes and nothing else: no page-sized bitmap, no `setPageSize`/`setTemplate` repaints (each is
its own EPD frame), the replaced bitmap recycled (the Y4 review's finding: an undo was paying for a
full bake). A real navigation changes the key; `onResume` forces one only when the date has actually
changed (there is no date-change receiver — a planner call the user may revisit, tracked in
`BACKLOG.md`).

## The store

`CalendarSchema.V1` — declared once by the extension, applied by the host, quoted verbatim:

```sql
CREATE TABLE period (id TEXT PRIMARY KEY, kind INTEGER NOT NULL, date TEXT NOT NULL, UNIQUE(kind, date));
CREATE TABLE page   (id TEXT PRIMARY KEY, periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE,
                     half INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL,
                     createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, UNIQUE(periodId, half));
CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                     "order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL,
                     style TEXT NOT NULL, blob BLOB NOT NULL);
CREATE INDEX stroke_page_order ON stroke(pageId, "order");
CREATE TABLE state  (key TEXT PRIMARY KEY, value TEXT NOT NULL);   -- lastView · lastDate · lastHalf
```

A `period` is a month (dated by its first day), a week (dated by its Sunday, never the device
locale) or a day (dated by the day itself); the `kind` column says which, so **there is no key
prefix** the way the pad has none either. A month or a week owns one `page` (`half` 0); a day owns
two (0 = AM, 1 = PM), sharing one `periodId`. `stroke` is the pad's row exactly — `StrokeCodec`
format B in `blob`, `"order"` the writing order within the page, decoded and encoded through
`:ext-ink`'s shared `StrokeRows` / `StrokeBlob`. **Since Y4 the `stroke` table's DDL and every
statement against it are `:ext-ink`'s `InkSql` fragment** — `CalendarSchema.V1` lists
`InkSql.CREATE_STROKE_TABLE` / `CREATE_STROKE_INDEX` among its own steps, and
`CalendarSql : InkDocument.StrokeSql by InkSql` delegates the two writes and forwards the three
reads rather than spelling them out a second time — byte-identical to what `CalendarSql` used to
write itself (`InkSqlTest` pins the shared text from `:ext-ink`'s side).

**Rows are minted on the first stroke, never on open.** `CalendarStore.readPage` answers what is
there and writes nothing — a period row or not, a page row or not, its strokes — and
`CalendarDocument` mints the missing rows only inside the flush that carries the page's first `Put`.
`CalendarDocument.statementsFor` decides this with one check: `pageMinted` is false and the
statements about to be written include an `INSERT` (`puts`). If both hold, the lead is
`CalendarStore.mintRows` — `INSERT OR IGNORE INTO period` then `INSERT OR IGNORE INTO page`, one
batch ahead of the stroke statements. **Never `INSERT OR REPLACE` into `period` or `page`** —
foreign keys are ON for the store connection, so REPLACE's delete-then-insert would cascade away a
period's pages and a page's strokes (arc 22 / X2's trap, inherited whole). `CalendarSql.insertPage`
resolves `periodId` **inside the statement** by a `SELECT id FROM period WHERE kind = ? AND date =
?` subselect, so the day's other half — which may already have minted the shared period row under a
different id than this caller guessed — is joined rather than duplicated. A stroke that is drawn and
undone before the debounce fires produces a flush that is nothing but a `DELETE`, and mints nothing
at all (pinned by `CalendarDocumentTest.aStrokeDrawnAndUndoneBeforeTheDebounceMintsNothing`) —
because there is nothing in that flush worth keeping a row for.

Strokes themselves are the pad's exact write rule: `INSERT OR REPLACE INTO stroke` (a stroke has no
children, so REPLACE is safe) and `DELETE FROM stroke WHERE id = ?` (a row that is not there is not
an error) — both idempotent, both batched by `:ext-ink`'s `StoreBatches` and read back through
`StrokeReadPlan`'s planned `BETWEEN` ranges so a page of any size never meets `STORE_RESULT_LARGE`.
`touchPage` (an `UPDATE … SET updatedAt = ?`) follows every stroke write that actually changed
something. The `state` bookmark (`lastView` / `lastDate` / `lastHalf`, each `INSERT OR REPLACE`) is
written on **every** `show` — the first included — which is a different rule from period/page/stroke
and deliberately so: "never on open" is the ink-row rule, and the Y1 walk proved it directly (three
months browsed, then a fresh `begin` logged `rows: 0 period(s), 0 page(s), 0 stroke(s)`). It is
written **before** the in-memory swap (Y4 review): every store round-trip a `show` makes — the
target's read, the departing page's flush, the bookmark — comes first, so a `show` that throws leaves
the document, the paper and the organizer exactly where they were; a swap that has already happened
with a store failure behind it would have sent the next stroke to a page the paper was not showing.

**`CalendarStore.receive`** — the notebook → calendar placement, run on the Binder thread — reads
the target page's **header only** (`readHeader`: which rows exist and the page's size — one or two
small queries however much ink the page holds; the full stroke read is `readPage`'s alone, and a
placement onto a page holding megabytes must not pay it inside the host's placement budget), and
mints a missing page at **`0 × 0`**: the page learns the sending screen's own surface size only the
first time a screen actually shows it (`CalendarDocument.show`'s `sizeDirty` logic), exactly as the
pad's pages do. It numbers new strokes after `SELECT COALESCE(MAX("order"), -1)` on the target page, so a
placement onto an existing page never collides with what is already there. The whole placement is
one statement list: under the batch cap that is one transaction and "nothing was placed" is the
transaction's own promise; past it, `InkStore.compensated` runs the batches in order and, on a
failure after at least one has landed, **drops the minted strokes by id, one `DELETE` each** (never
an `IN (…)` list — the 999-argument cap) before `StoreUnavailable` is thrown. A period or page row
that the failed placement minted is **left behind** — an empty page is not a placement, and nothing
in this arc ever deletes a `period` row at all.

Every SQL string is `CalendarSql`'s (the stroke pair among them `InkSql`'s by delegation since Y4),
pinned to exact text and bound arguments by `CalendarSqlTest` via the real host validator
(`StoreSql`). `"order"` is quoted because it is a real keyword; `key` and
`value` are SQLite fallback keywords and pass unquoted on both the JVM and the Nomad (the same rule
`docs/document.md`'s `EditorSql` already established). `CalendarStore` extends `:ext-ink`'s
`InkStore` base (`execAll` / `compensated` / `guard` / `readStrokes`), which is where the batch
split, the compensated multi-batch write, the one-rule error mapping (any exception at all becomes
`StoreUnavailable`) and the planned stroke read all actually live — `CalendarStore` supplies only
its own schema, its own SQL and its own reads that are not a stroke read.

## Navigation

`CalendarNavigation` is a pure class, JVM-tested, and the screen holds no navigation rule of its
own: it asks for a `Move`, shows it, and reports that it landed (`shown`) only once the page is
really on the paper — a `show` that throws (the store gone out from under it) leaves the organizer
exactly where it was.

**The anchor is the point of the class.** A calendar page is a *period*, but a person is looking at
a *day*, so the state carries an anchor day alongside the showing page: toggling Month → Week → Day
walks down to the same day rather than to three unrelated first-of-periods. The anchor moves in
exactly four ways (and follows a fifth, a replay — see Undo below):

- **Opening or stepping** (`opening`, `stepped`) derives the anchor from the arriving page: a Day
  page anchors on its own day; a Month or Week page that **contains today** anchors on *today* (so
  the first toggle out of this month lands on this week, and out of this week on today); any other
  period anchors on its own first day.
- **Picking a day, tapping Today, or a double-tap** (`picked`, `todayMove`, `dayAt`) moves the anchor
  to the day the user named.
- **Toggling view** (`toggled`) does not move the anchor at all — a toggle is a change of
  magnification, not of place, which is what lets Week → Day come back to the exact day (and half)
  you left it on.

`anchorHalf` travels with the anchor and is only ever the half *that anchor day* is looking at: a Day
page sets it to its own half, and any move to a **new** anchor day resets it — to the clock's half
when that day is today, otherwise to AM (`CalendarNavigation.clockHalf`). A toggle to Day simply
reuses it, which is why "Week → Day" after "Day Sep 3 PM" is Sep 3 PM again rather than Sep 3 AM.
`CalendarDates.step` is the one stepping rule for both the pager's buttons and a finger swipe alike:
a month by a month, a week by seven days, a day AM → PM → the next day's AM (and the mirror going
back). **A double-tap on a Month or Week cell always opens that day's Day page at AM** — the
wizard's call, regardless of what the clock says — and does nothing at all on a Day page itself.

`CalendarToolbar` renders the state, never decides it: the three view toggles are latched from
`setView`, called only from `showPage` after a navigation actually lands — never from the tap that
asked for it, so a navigation that failed can never leave a lie in the bar. **Since Y4 (the user's
icon calls) they are Tabler icons, not words** — `ic_calendar_month` (the three bars) /
`ic_calendar_week` (the dots) / `ic_calendar_day` (the plain `calendar` frame with two ruled lines
inside — the Day page's rows; a Tabler derivative on the `ic_notebook_plus` precedent) as
`Widget.Notesprout.ToolbarButton` image
buttons, the armed one with `isSelected` set, which reads as the button's own selected border, the
notebook's armed-tool look; Today is `ic_calendar_star`, an icon too, and the four label strings
went with the words. (The first cut had the month/week path sets assigned backwards and Day on the
bare `calendar` glyph; the second tried Tabler `calendar-user` for Day and the user declined it —
the ruled-lines derivative is the third and final.)
**The pager's title is itself a tap target** — tapping it opens `DayPickerDialog`, rebuilt in
`:ext-calendar` in og's shape rather than shared (nothing of the host's crosses into an extension):
a Sun–Sat day grid whose prev/next step months, a header tap that flips the whole dialog to a 3×4
month chooser whose prev/next step years instead, today ringed, the day you came in on filled
black. `DayPickerModel` (pure, JVM-tested) provides both grids — the day grid never grows a
trailing empty week (a 28-day February beginning on a Sunday is exactly four rows) — and the dialog
itself is only views over it. **Since Y4 the window is sized after `show()`** to `WIDTH_FRACTION`
(0.75 of the screen) — a full-width dialog had read as a page rather than a dialog, and its
bordered background sat off the glass at the edges — and the header `TextView` carries a margin the
width of a button on each side, because it is added into the `FrameLayout` after `btnPrevMonth`
and at full width sat on top of the left arrow and took its taps (the right arrow, added after the
title, was never covered). The root also no longer paints its own white: `shape_dialog_bordered`'s
2 dp stroke carries no padding, so an opaque root over the whole window had covered the border on
every side but the one the content happened to fall short of.

## Gestures

| Gesture | Action |
|---|---|
| Finger swipe (horizontal, either direction) | Steps the period, through the notebook's own `SwipeMath` guards — the same distance/velocity rule as a page flip |
| Double-tap a Month or Week cell | Opens that day's Day page at AM (nothing on a Day page itself) |
| 2-finger stationary double-tap | Undo |
| 3-finger stationary double-tap | Redo |
| Long-press | Nothing — not offered |

Double-tap detection is `PageGestures.Listener.onFingerDoubleTap` in `:sn-screen` — a **second,
independent history** over the same qualifying bare taps `onFingerTap` (link follow, on the
notebook) already fires on, added for this arc rather than folded into the single-tap path: both
callbacks fire for a qualifying second tap, so a consumer that wants only one of them is never
silently denied the other, and the notebook's own `onFingerTap` is byte-identical to what it was
before this existed. The calendar overrides only `onFlipNext`/`onFlipPrevious` (swipe → step),
`onUndo`/`onRedo` and `onFingerDoubleTap`; it does not override `onFingerTap` at all, so a single
tap on the calendar selects and does nothing — the wizard's call. Every gesture is pen-activity
gated exactly as everywhere else in SN (`PageGestures.gateOpen()`), and a sequence that starts on
chrome or from a stylus is thrown away whole.

## Undo

Undo is **calendar-level, in memory, per showing** — the pad's rule exactly, via `:sn-screen`'s
`UndoRedoStack<InkAction>`. `:ext-ink`'s four stroke-level actions are all there is: `Drew` /
`Erased` / `Moved` / `Pasted`, each naming the `pageId` it happened on (the pad additionally has a
page-level `Page` action for inserting/deleting pad pages, which the calendar has no equivalent of —
a calendar date always has a page, minted or not). Replaying an action recorded on a page other than
the one showing navigates there first (`CalendarDocument.revert` / `reapply` → `landOn`), then
replays in memory and flushes before returning — the same "the store is the source of truth" rule
the notebook's own undo obeys.

**Since Y4 the replay loop itself is `:ext-ink`'s `InkScreenActivity`'s, shared with the pad**:
`doUndo`/`doRedo`, the record-clears-redo generation check, and the put-the-entry-back-on-failure
rule all live in the base class now, not in `CalendarActivity`. What the calendar supplies is
`CalendarDocument.revert`/`reapply` (its own navigate-then-replay) and `followReplay()` — the open
hook `InkScreenActivity` calls after every replay and before the page is shown, which the pad leaves
at its default no-op (it has nothing to follow) and the calendar overrides, below.

Every page leave, `onPause`, and every exit (`exit()`, and a Send) flushes the op log **before**
anything else happens — "Back awaits the flush," the pad's rule verbatim: the host's result callback
runs `end()` → unbind → revoke the moment the screen finishes, so a save still in flight would hit a
revoked binder otherwise. `onPause`'s flush runs under `NonCancellable` on a scope that outlives the
Activity, so a screen rotation or a background kill mid-write still lands. **A leave path's flush is
unbounded** (`InkDocument.flushUntilClean`'s `UNBOUNDED` default): a hand still committing strokes
re-dirties the log each pass, and the loop simply runs until the pen pauses — there is no next
debounce after a swap to leave anything to, and the swap's `reset` forgets the log. Only the debounced
save is bounded (`MAX_FLUSH_PASSES` 8, passed by `saveRunnable` alone) and, still dirty past it,
answers `false` with the leftover kept for the next debounce (the Y4 review: before this, the leave
path shared the bound and a swap could drop strokes that were on the paper and in the undo stack).

**An undo or redo whose action lives on another page navigates the document there first** — and the
organizer follows: `CalendarNavigation.landed(target, today, nowHour)` derives the anchor from the
landed page exactly as opening or stepping would, and `CalendarActivity.followReplay()` applies it
after every replay (the Y4 review: without it the toggles, the pager, the picker and a double-tap
all acted on the page the navigation still believed was showing).

## The two doors + the held bind

`CalendarEntry` is one class serving **both** doors — the library's (Y1) and the notebook's (Y3) —
because everything about them is identical except the one line that is not: the notebook's
`beforeLaunch` hands the EPD pipeline over (`paper.releaseForHandoff()`) immediately before the
screen launches, and the library has no pipeline to hand over. **Since arc 23 / Y4 that one-class
shape is `ExtensionScreenEntry`'s, not `CalendarEntry`'s own** — `CalendarEntry` is a thin
`ExtensionScreenEntry<ICalendar, CalendarTarget>` carrying only its registry lookup
(`ExtensionRegistry.calendar`), an `EntryWording` and its result code (`RESULT_CALENDAR_SEND`); the
pad's `ScratchPadEntry` is the sibling thin point on the same class. It owns: visibility (`GONE`
unless `ExtensionRegistry.calendar` finds a trusted extension, re-discovered on every `refresh()` —
every `onResume` and every failed open), the busy guard (`opening`, latched at the tap and released
only after a drain completes), the `OpeningOverlay` wait (a cold open is seconds — SQLCipher's KDF
creating the store — and a tap with no answer that long reads as a tap that missed), and both
transfers' host half.

**Since Y4 the calendar also has its own door out, to the pad** — the user's placement call: the
Scratch Pad is the last button on every bar, so a calendar showing needs its own way to reach it
rather than making the person back all the way out first. `ExtensionScreenEntry` grew two hooks for
it, generic enough that either ink screen can use them: `decorateIntent(activity, intent)` runs
after `begin` succeeds and before launch — the calendar's fills in
`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE` from `ExtensionRegistry.scratchPad(ctx) != null`, discovery
staying the host's, never a query from one extension to another — and `onClosed(resultCode)` fires
once the showing and its bind are both finished, so the caller is free to open a second door from
there. A tap on the calendar's own Scratch Pad button runs `exit(RESULT_CALENDAR_OPEN_SCRATCH_PAD)`
(flushed first, like every exit) rather than opening anything itself — an extension screen refuses
any caller but the host, so a door from one extension to another is always the host's to walk
through. Both callers chain the same way: `onCalendarClosed(resultCode)` opens the pad and sets a
`reopenCalendarAfterPad` latch when the result was `RESULT_CALENDAR_OPEN_SCRATCH_PAD`;
`onPadClosed(resultCode)` reopens the calendar — at its bookmark, so it lands where it was — only
when the latch is set **and** the pad closed with `RESULT_CANCELED`. A pad that sent ink to the
notebook instead stays closed: the paste is what the person is looking at, and reopening the
calendar over it would bury the thing that just landed.

`CalendarClient` was `ScratchPadClient`'s shape on `ICalendar` by discipline alone through Y3; since
Y4 it is that shape **structurally** — a thin `HeldInkClient<ICalendar, CalendarTarget>` whose
companion `Point` supplies `ICalendar`'s names, extras and budgets, on the one class that now also
serves the pad. `open` pre-opens the extension store on IO (the pre-open rule — a cold KDF must
never sit inside a call timeout), mints a uid-bound `ExtensionStoreBinder`, holds the bind
(`ExtensionBinder.hold`, signature re-checked at bind time), calls `begin(store)` within
`CALL_TIMEOUT_MS` (2 000 ms), and builds the screen Intent (`ACTION_CALENDAR_SCREEN`, `setPackage`,
the two `HeldInkClient` boolean extras plus, since Y4, `decorateIntent`'s third
(`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE`) — **nothing else rides the Intent**, every byte of ink
crosses the held service). `send` / `drainOutgoing` are the transfer host halves (below) — `HeldInkClient`'s methods,
run once for both points; `finish` runs `end()` best-effort within the same timeout, then always
unbinds and revokes the store in `finally`. Before Y4 the calendar's copy carried the settle rule
(below) alone and the pad's did not — the concrete drift the unification closed. The screen's own
`HostCallerCheck.enforceActivity` is the first statement in `CalendarActivity.onCreate` — a plain
`am start` has a null `callingPackage` and is refused before anything is inflated, logged as
`refused caller (none)`. Both `ACTION_CALENDAR` and `ACTION_CALENDAR_SCREEN` are in the host's
`<queries>` block (the arc-21 / W1 trap, avoided here).

## Both transfers

Both directions are **copies**, cross **only through the held bind**, carry **no ids** (fresh ones
are minted on the receiving side) and keep **coordinates 1:1** — the sending page and the receiving
page are both this device's screen, so a cross-size page clips the ink exactly like any other.

### Calendar → notebook

1. The top bar's Send (`sendPage`) is the whole current page in writing order; the selection bar's
   Send (`sendSelection`) is the lasso's strokes, read at the tap because a selection can die between
   the show and the tap. Both are icon `ic_pencil_down`, both absent without a notebook behind the
   calendar (`sendEnabled`). An empty pick raises "Nothing to send," never silence.
2. The page is flushed under the page-op lock first (**the calendar keeps its ink** — this is a
   copy), the wire chunks (`InkWire.toWireStrokes` → `InkChunks.chunk`) are parked in
   `CalendarSession.outbound` with the page's own size, and the screen finishes with
   `RESULT_CALENDAR_SEND`.
3. `ExtensionScreenEntry.onResult` (via `CalendarEntry`, Y4) drains `takeOutgoing` — `HeldInkClient
   .drainOutgoing` — on the bind it is **still holding** (`TransferCaps.Drain`, stopping at the first
   empty bundle, the summed caps, or the chunk budget plus one probe past it) and hands the result,
   a `DrainedInk`, to `onDrained` **before** the bind is finished.
4. The host's shared `pasteTransferred` (see below) sanitizes, mints fresh ids, and pastes — landing
   **selected** with the lasso armed, the "Pasted" toast, and one `ObjectsPasted` undo step.
5. Only then is the bind finished — `end()`, unbind, revoke.

### Notebook → calendar

1. The selection toolbar's **Calendar** button — the second extension-gated button (after Pad, ink
   only, for the pad's exact reason: `WireStroke` is the whole of what the contract carries, so a
   heading or a link in the selection takes the button away).
2. `sendSelectionToCalendar` calls the one gate both lasso sends pass since Y4,
   `NotebookActivity.sendSelectionToExtension`: `TransferSelection.sendable` picks the selection's
   ink-only strokes in writing order (empty on anything mixed, content-only, or already gone),
   then `TransferCaps.withinLimits` is checked **before any bind** ("Too much to send" on a refusal,
   nothing sent). Past the gate, `sendSelectionToCalendar` raises a four-row `ActionSheetDialog` —
   **Today, morning · Today, afternoon · This week · This month** — each row's target resolved **at
   the tap** by pure `CalendarTargets.target(choice, today)` (a sheet left up across midnight, or a
   device that slept under it, sends to the day the person is tapping on — the Y4 review), routing
   every choice through `CalendarTarget.of` so the host never computes a period itself (the week's
   Sunday rule belongs to `CalendarDates`, not to a second guess in the host). The rows carry no
   icons, on `LinkPickerActivity`'s precedent.
3. `openCalendarWith` hands an `InkSend(strokes, pageWidth, pageHeight, target)` — the one outbound
   -ink class, shared with the pad, replacing what was `CalendarEntry.Send` — to `CalendarEntry
   .open`, which pre-opens the store, holds the bind, `begin`s, then (before launching) chunks and
   sends the ink via `HeldInkClient.send` (through `CalendarClient`) — every chunk carrying the same
   `CalendarTarget`, the last chunk budgeted `PLACE_TIMEOUT_MS` (10 s — a Binder call cannot be
   cancelled, so a tighter budget would report a failure for ink that lands anyway regardless).
   **A placement that outlives its budget is settled, not abandoned** (Y4 review): the host waits
   once more (`HeldBinding.settle`, the same budget again) for the orphaned call to return — a late
   return without an exception is a success and the launch goes ahead; only a call that threw, or is
   still running past the second budget, is a failure — and `finish()` settles before it `end()`s,
   so the store is never revoked under a placement's batches (where the extension's own compensation
   would be refused by the same gate).
4. **Since Y4 this accumulate-and-place body is `:ext-ink`'s `InkTransferSession.receiveChunk`**,
   shared with the pad: `CalendarSession` is an `InkTransferSession<CalendarTarget,
   CalendarStore.Received>(recordInboundPageSize = false)`, and the running-totals re-check (the
   untrusted-input half of the same rule the host already checked), the one monitor, and refusing a
   placement that changes mid-transfer are all the shared session's rather than `CalendarService`'s
   own — the refusal text is now the generic `"placement changed mid-transfer"`, which the pad
   throws too (it never checked for this before Y4). `CalendarService.receiveInk` supplies only what
   is left: the target's own null check (already through `requireValid` at unmarshal) and the log
   wording. On the last chunk the session mints fresh ids (`InkWire.toStrokes` — nothing from the
   wire is trusted beyond its geometry) and places through `CalendarStore.receive`, leaving
   `CalendarSession.received` for the screen to consume once.
5. The screen is launched with `EXTRA_CALENDAR_OPEN_RECEIVED = true`. `openDocument` opens on
   `CalendarSession.received.target` **ahead of the bookmark** — the placement is the reason the
   screen is up — and `consumeReceived()` (after the "Opening…" overlay hides) applies the one-shot
   handover: the record is cleared before anything can fail, dropped if its target is not the page
   actually showing (only reachable through a host restart mid-showing), the **lasso armed before
   `setSelection`** (a selection under the pen can neither be dragged nor dismissed), and exactly one
   `InkAction.Pasted` undo step recorded. The tool the user had comes back pen-idle once that
   selection is dismissed.

**The paste back is one body for both extensions.** `NotebookActivity.pasteTransferred(wire,
truncated, wording, source)` is the whole of the host-side paste; `pasteFromPad` and
`pasteFromCalendar` are one-line callers over it differing only in a `TransferWording` (three string
resources) and the `source` name used in the log line. `toolBeforeTransferPaste` is **one field**
shared by both transfers — deliberately, because only one transfer can have just landed —
restored by `restoreToolAfterTransferPaste`, the pad's own restore logic renamed rather than
duplicated. The paste lands appended after the destination page's current max order (writing order
preserved, the arc-8 rebase rule), selected, with the lasso armed, as one `ObjectsPasted` step.

## Nomad numbers

| Measurement | Cold | Warm |
|---|---|---|
| Store open (`CalendarClient.open` → `begin`) — Y1 | 2 726 ms (store creation) / 161 ms `begin` | 56 ms open / 34 ms `begin` |
| `begin` — Y2 | — | 19–21 ms |
| `begin` — Y3 | 818 ms (cold-in-process) | 28 ms |
| `receiveInk` (notebook → calendar, 19 strokes) — Y3 | 119 ms | — |
| `drainOutgoing` (calendar → notebook, 19 strokes, 1 chunk) — Y3 | 106 ms | — |

`PLACE_TIMEOUT_MS` (10 s, the pad's number) stays as-is — 119 ms for 19 strokes leaves it generous
by two orders of magnitude, and it has never been tightened. The pad's own warm `begin` (23 ms,
measured in the same Y1 walk) is the closest same-device comparison: the two stores are shaped
identically, and the numbers track. **Not measured: a transfer at the caps** (`MAX_TRANSFER_STROKES`
— which, with the mint lead and `touchPage`, is two `exec` batches). The settle rule above is what
keeps a placement that slow from leaving half its rows behind; how long it actually takes on the
Nomad is still an open measurement, recorded in `BACKLOG.md`.

## Failure table

Mirrors the pad's, row for row.

| What went wrong | Where | What the user sees | State |
|---|---|---|---|
| No trusted calendar installed | host | Both doors are **GONE** (never disabled) | — |
| Open failed (disabled, replaced, store unreadable) | host | "Calendar unavailable" / "The calendar could not be opened. It may have been disabled or removed." | nothing sent, discovery re-runs |
| Selection over the transfer caps | host, **before any bind** | "Too much to send" / "This selection holds more ink than the calendar can take. Send a smaller part of it." | nothing sent |
| A placement's batches fail part-way | calendar's store call → host | "Calendar unavailable" — the same text as any open failure | **compensated first** (minted strokes dropped by id); the calendar is not opened |
| The drain hit a cap or the chunk budget | host | "Not everything came back" / the pasted count | what came is pasted; the rest is **still on the calendar** |
| The drain failed outright, or brought back nothing | host | "Nothing came back" / "The ink from the calendar could not be brought back. Nothing was changed — it is still on the calendar." | nothing pasted |
| The paste could not be written | host | "…could not be written to this page. Nothing was changed — it is still on the calendar." | nothing pasted |
| Send with no ink picked | calendar | "Nothing to send" / "There is no ink here to send to the notebook." | the calendar stays up |
| A stroke row will not decode | calendar, on read | nothing — no dialog | that stroke is **dropped, never surfaced**; counted and logged, the rest of the page loads |
| The store's format is newer than this host writes | host, on open | "Calendar unavailable" / "Notesprout SN could not open the calendar's storage. Nothing was lost — try again." | **left exactly as found** — never-delete-on-corruption |
| The store binder is gone mid-showing | calendar | same "Calendar unavailable" dialog | the calendar shows the problem and stays on the page it had |

## Entry points

| Where | Behaviour |
|---|---|
| Library top bar, before the pad (Y4 — the pad is always the last button) | opens the calendar with **no** Send buttons — there is no notebook to send to |
| Notebook top bar, right cluster, before the pad (Y4 — same placement call) | hands the EPD pipeline over first (`releaseForHandoff()`); the calendar gets both Send buttons |
| Notebook selection toolbar, 8th button (ink-only, second extension-gated slot, between Pad and Tag) | the outbound (notebook → calendar) transfer above |
| Calendar top bar's own Scratch Pad button, last on the bar (Y4) | shown only when the host found a trusted pad; `exit(RESULT_CALENDAR_OPEN_SCRATCH_PAD)` hands the door to the host, which opens the pad and brings the calendar back at its bookmark on a plain close — see § The two doors + the held bind |

`CalendarEntry` serves both entry doors identically apart from `beforeLaunch` and `sendEnabled` —
two near-identical classes would have been the sibling-copy trap in miniature. Both buttons are
`GONE` unless a trusted calendar is discovered, re-run on every `onResume` and after a failed open.

## Frame silence

The calendar carries the SN-wide rule (never present an app frame while `paper.isPenActive`) and
adds no new exception — its frames are the pad's recorded exceptions in calendar form: the
selection bar's show at lasso completion (and its re-anchor after a move, and its show over a
received placement — the same kind of frame at the same kind of boundary), the "Opening…" box's
hide once the page lands, and a problem dialog at a pen-up or a chrome tap. The pager's title
(`CalendarToolbar.setTitle`) and the view latches wait for `whenPenIdle` explicitly — **since Y4**
`InkScreenActivity.whenPenIdle` is a one-line wrapper over `:sn-screen`'s `PenIdle.whenIdle`, the
same gate the pad's screen calls, rather than a copy each screen kept for itself.

## What the calendar is not

- **It opens no `.soil`.** It has no notebook, no page rows in the global index. Its pages are its
  own, in its own store.
- **The notebook is not sealed behind it** — the same shape as the pad's hop: what the notebook
  gives up is the EPD *pipeline*, not its data. Its session, undo stack and unsaved page are all
  still there when the result comes back.
- **It has no events, tasks, reminders, day window, history, day notes, export, or Today-dashboard
  presence.** Every one of those is a later, separate decision — see "What it is, and is not."
- **It has no clipboard, no headings, no links.** Four stroke-level undo kinds, and no page-level
  action at all (the pad has a fifth, `Page`, for inserting/deleting its own pages — a calendar date
  always has a page, minted or not, so there is nothing analogous to insert or delete).
- **It never writes to disk itself** — no file, no prefs, no second store outside the one the host lends.

## Where the code is

| | |
|---|---|
| `:extension-api` `ICalendar.aidl` | `begin` · `receiveInk` · `takeOutgoing` · `end` |
| `:extension-api` `CalendarTarget` / `.aidl` | the wire target: `kind` / `date` / `half`, `requireValid`, `of` |
| `:extension-api` `CalendarDates` | week/month normalization, stepping, the hand-list titles |
| `:extension-api` `ExtensionContract` | `ACTION_CALENDAR[_SCREEN]`, `API_VERSION` 7, the per-action `minApiVersion` map, the extras/result |
| `:ext-ink` `InkWire` | wire ⇄ paper, the extension-side twin of the host's `TransferCaps` |
| `:ext-ink` `StrokeRows` / `StrokeBlob` | row → stroke decode (dropped-not-lost), the format-B encoder |
| `:ext-ink` `StoreBatches` | splitting a write into `exec` batches |
| `:ext-ink` `StrokeReadPlan` | planning a page's strokes into `BETWEEN` ranges |
| `:ext-ink` `InkDocument` | the `TreeMap` + op log + `flushUntilClean` + `highWater`, shared by the pad and the calendar |
| `:ext-ink` `InkAction` / `InkStore` | the four stroke-level undo kinds; the store base (`execAll` / `compensated` / `guard` / `readStrokes`) |
| `:ext-ink` `InkSql` | arc 23 / Y4 — the shared `stroke` DDL and its six statements, `CalendarSql`/`ScratchSql` delegate to it |
| `:ext-ink` `InkPage` | arc 23 / Y4 — the ink half of a consumer's document as a contract; `CalendarDocument` implements it |
| `:ext-ink` `InkTransferSession<P, R>` | arc 23 / Y4 — the shared held-showing state and the two transfer stubs' bodies (`receiveChunk` / `outgoing`); `CalendarSession` is one instance, `ScratchSession` the other |
| `:ext-ink` `InkScreenActivity<A>` | arc 23 / Y4 — the shared tier-2 screen skeleton (page-op lock, undo/redo replay, `followReplay` hook, the save debounce, the EPD handoff); `CalendarActivity` is thin over it |
| `:ext-calendar` `CalendarApplication` | registers `RattaEngine` — the extension's own process hosts paper |
| `:ext-calendar` `CalendarService` / `CalendarSession` | thin on `:ext-ink`'s `InkTransferSession` since Y4 — `CalendarService` supplies the target's own null check and the log wording |
| `:ext-calendar` `CalendarSchema` / `CalendarSql` | the calendar's own tables and SQL (pinned by `CalendarSqlTest`) — since Y4 the `stroke` table and its six statements are `:ext-ink`'s `InkSql` (`CalendarSql : InkDocument.StrokeSql by InkSql`) |
| `:ext-calendar` `CalendarStore` | the store calls, on `:ext-ink`'s `InkStore` |
| `:ext-calendar` `CalendarDocument` | the showing page in memory: target, mint/size bookkeeping, delegates ink to `InkDocument`; implements `:ext-ink`'s `InkPage` since Y4 |
| `:ext-calendar` `CalendarGeometry` / `CalendarTemplate` | the three layouts' rects and hit-tests; the template painter |
| `:ext-calendar` `CalendarNavigation` | the pure anchor rule and every `Move` |
| `:ext-calendar` `DayPickerModel` / `DayPickerDialog` | the picker's grids (pure) and its views |
| `:ext-calendar` `CalendarToolbar` | the chrome, the fixed tools, the pager, both Send buttons, and (Y4) the three Tabler view latches and the calendar's own Scratch Pad button |
| `:ext-calendar` `CalendarActivity` | thin on `:ext-ink`'s `InkScreenActivity` since Y4 — navigation, template bake, the picker, double-tap, `followReplay()` |
| `:app` `HeldInkClient` | the held bind, `open` / `send` / `drainOutgoing` / `finish`, once, shared with the pad since Y4 — `HeldInkPoint` the per-point names/budgets interface, `DrainedInk` the one drained-result class |
| `:app` `CalendarClient` | thin on `HeldInkClient` since Y4 — its companion `Point` is a `HeldInkPoint<ICalendar, CalendarTarget>` naming `ICalendar`'s two actions, two extras and three budgets |
| `:app` `ExtensionScreenEntry` | both entry doors, the busy guard, the overlay, both transfers' host half, once, shared with the pad since Y4 — `InkSend` the one outbound-ink class (replacing `CalendarEntry.Send`), `EntryWording` the four strings, and (Y4) `decorateIntent`/`onClosed(resultCode)`, the two hooks the calendar's pad door rides |
| `:app` `CalendarEntry` | thin on `ExtensionScreenEntry` since Y4 — its registry lookup, its `EntryWording`, `RESULT_CALENDAR_SEND`, and (Y4) `decorateIntent` (sets `EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE`) plus an `onClosed` passthrough |
| `:app` `CalendarTargets` | the four Send-to-Calendar rows, pure |
| `:app` `TransferSelection` | the pure ink-only, writing-order rule both lasso sends obey (Y4) — `sendable(selection, live)` |
| `:app` `NotebookActivity` | `btnCalendar`, `sendSelectionToExtension` (the one gate, Y4), `sendSelectionToCalendar`, `openCalendarWith`, `onCalendarSent`, `pasteFromCalendar`, the shared `pasteTransferred`, and (Y4) `onCalendarClosed`/`onPadClosed` — the door chain to and from the calendar's own pad door |
| `:app` `SelectionToolbar` | the Calendar button (STROKES-only, extension-gated) |
| `:app` `LibraryActivity` | `btnCalendar`, the library door, and (Y4) `onCalendarClosed`/`onPadClosed` — the same door chain as the notebook's |
| `:sn-screen` `FloatingSelectionBar` | the row-of-buttons primitive `InkSelectionBar` places |
| `:sn-screen` `InkSelectionBar` | arc 23 / Y4 — the ONE Send-then-Delete floating bar, replacing `CalendarSelectionToolbar` and the pad's `ScratchSelectionToolbar`, built on `FloatingSelectionBar` |
| `:sn-screen` `PenIdle` | arc 23 / Y4 — the frame-silence gate (`whenIdle` / `releaseRenderIfIdle`), shared by both toolbars and both activities |
| `:sn-screen` `PageGestures` | `onFingerDoubleTap` — the second tap history the calendar's cell-open rides |

## Tests (JVM)

| File | Covers |
|---|---|
| `extension-api/CalendarDatesTest` | Sunday-start weeks across year ends, month starts and `firstCell`, `periodDate`/`isNormalized`, stepping (month/week/day incl. Feb 29 and Dec → Jan), ISO round-trips, the hand-list titles |
| `extension-api/CalendarTargetTest` | every kind, normalized dates accepted, unnormalized ones rejected (not corrected), `half` legal only for a day, bad kinds/dates rejected, `of` normalizes, value equality |
| `extension-api/ExtensionContractTest` | the `minApiVersion` map and `accepts`, including the calendar's floor of 7 |
| `ext-ink/InkWireTest` | wire ⇄ paper both directions, fresh ids minted inward, width clamped, unknown style → PEN, a point-less stroke skipped outward |
| `ext-ink/StrokeRowsTest` | row → stroke decode, a bad row dropped and counted rather than failing the page |
| `ext-ink/StoreBatchesTest` | splitting by byte budget and by statement count, a single oversized statement getting its own batch |
| `ext-ink/StrokeReadPlanTest` | ranging a page's strokes under a byte budget, a single oversized stroke getting its own range |
| `ext-ink/InkDocumentTest` | the TreeMap + op log, `highWater` never lowered by an erase, `flushUntilClean`'s re-dirty loop and its failure-merge-under rule, a bounded flush answering `false` and keeping its leftover while an unbounded one runs until the writer pauses |
| `ext-ink/InkSqlTest` | the `stroke` table and index DDL through the host's real DDL validator, `putStroke`'s idempotent text and format-B geometry, both deletes, all three reads keeping `"order"` quoted |
| `ext-ink/InkTransferSessionTest` | chunks accumulating and only the last one placing, fresh ids minted with the wire's geometry, a placement changed mid-transfer refused and the whole inbound dropped, the stroke and point caps, a missing store and a store that fails mid-placement both answering the one `STORE_UNAVAILABLE` text, `recordInboundPageSize`'s one documented difference, parked chunks probed past the end as empty, `end`/a second `begin` clearing everything |
| `ext-calendar/CalendarSqlTest` | the schema shape, every statement passes the real host validator, period/page are `OR IGNORE` never `REPLACE`, page updates, stroke rows match the pad's, state rows, every read statement |
| `ext-calendar/CalendarGeometryTest` | hairline rounding and integer edges, Month cells square from the width with the Notes band taking the rest, dividers on integer edges, a short page shrinking cells rather than running under the bar, Month `hitTest` on and off the grid, Week cells as Month's quarter with Month's band, Week `hitTest` incl. the spare cell, Day rows sharing the height evenly with no band and the last row taking the remainder to the bar, a taller page growing the rows, a short Day page shrinking rows, `dayRowLabel`'s 12-hour text |
| `ext-calendar/CalendarNavigationTest` | first run onto today's Month, honouring any bookmark kind, the anchor landing on today/this-week on toggle, anchoring on a period's own first day when it doesn't hold today, re-anchoring on today when stepping back into a period that does, a toggle preserving the anchor's half, a toggle to the showing view doing nothing, Today and the clock's half, a double-tap opening AM and moving the anchor (and doing nothing on a Day page), a pick moving the anchor, Day stepping AM → PM → next morning; `landed` re-anchoring a replay's page (and answering null for the showing one) |
| `ext-calendar/CalendarStoreTest` | `open` declares the schema, reads the bookmark and writes nothing; a bad bookmark reads as none; reading a missing page writes nothing; reading a day's other half finds the period but no page; reading an existing page is the join then the strokes; `saveState` is one batch of three; `mintRows` is period-then-page both `OR IGNORE`; `receive` on no rows mints at zero size in one batch; `receive` on an existing page numbers after the max and mints nothing; a mid-way placement failure drops exactly what it minted; every store failure reads as `StoreUnavailable`; a placement onto an existing page reads its header and max order, never a blob |
| `ext-calendar/CalendarDocumentTest` | showing an empty month writes only the bookmark; the first stroke mints period and page ahead of itself in one batch; an existing page is never re-minted and keeps its own size; the other half of a day joins the existing period; a zero-size page learns the surface once and only once; a stroke drawn and undone before the debounce mints nothing; leaving a page flushes it after reading the next; a replay on another page navigates there first; a replay for a page never shown this showing is skipped |
| `ext-calendar/DayPickerModelTest` | a month starting Sunday has no leading blanks, one starting Saturday has six, a 28-day February from Sunday is exactly four rows, every row is seven slots with no trailing empty week, leading blanks match the first day's column, the month grid is 1–12 in four rows of three, titles from the hand lists and the year itself |
| `app/CalendarTargetsTest` | the four rows in the wizard's order, a Wednesday's four targets, a Sunday and a Saturday's week target, a month's-first target, a year-end week crossing into the new year's month, every target satisfying `requireValid`; a choice resolves against the day it is asked on, not the day the sheet was built |

`FakeCalendarStore` (`ext-calendar`) is the test double behind `CalendarStoreTest` and
`CalendarDocumentTest` — it applies the four write shapes literally (not merely recording them),
the same discipline `docs/tags.md`'s `FakeTagStore` follows for the same reason: a fake that only
recorded statements could never exercise a real compensation or a real re-read.

## Traps

- **`AppCompatButton`s with no `layout_width` inflate-crash under `Widget.Notesprout.TextButton` /
  `LatchButton`** — only `ToolbarButton` sets one. `processDebugResources` passes either way; the
  screen dies at inflate with `InflateException … You must supply a layout_width attribute`. Hit
  once, at the Y2 walk's first run, on the four new word buttons (Today, Month, Week, Day) — now a
  standing trap for any future text-styled button in this family.
- **Two sequential `adb shell input tap`s land outside the platform's 300 ms double-tap window** —
  each spawns and tears down its own `input` process. `adb shell "input tap X Y & input tap X Y;
  wait"` (both backgrounded in one shell invocation) lands inside it.
- **The day picker dialog's width is fixed since Y4 (`WIDTH_FRACTION` 0.75), but its height is
  still content-sized and narrows in month mode** — flipping to the 3×4 month chooser shrinks the
  grid from six rows to four, which moves the header row (and both arrows with it) up the window. A
  scripted tap aimed at a coordinate dumped before the flip can land outside the dialog, or on the
  wrong control, after it. Re-dump the layout before every tap rather than reusing coordinates
  across a flip. Cosmetic on a real finger, which reads the dialog before tapping; left as-is.
- **A transfer paste lands *selected*, standing in for a lasso when driving both directions by
  adb.** Neither direction of the calendar's transfer can be triggered by a scripted lasso — `adb`
  cannot draw one — but the notebook's own pasted-and-selected ink after a calendar → notebook
  transfer is a real selection the very next Send-to-Calendar can act on, which is how the Y3 walk
  proved both directions from one seed of ink without any hand-drawn lasso at all.

## Related

- `docs/scratchpad.md` — the model this doc mirrors; read it first for anything that reads here as
  "the pad's rule."
- `docs/extensions.md` — the seam in full: the calendar's boundary-audit rows, `IExtensionStore`,
  the tier-2 recipe every screen-owning point follows.
- `docs/sn-screen.md` — `:ext-ink`'s dependency shape and the `FloatingSelectionBar` generalization.
- `docs/notebook.md` / `docs/library.md` — `btnCalendar` and the selection toolbar's Calendar button
  in their place among the rest of that screen's chrome.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 23 \"Calendar\"" — the wizard's locked
  decisions, the seam spec, and the Y1–Y4 Outcome records this doc draws its facts from.
