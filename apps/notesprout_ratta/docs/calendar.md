# The Calendar (arcs 23–24)

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

**Arc 24 "Events"** (2026-09-02 to 2026-09-04) added og's calendar events inside the same
extension, on the same store, with no change to the seam: two more in-process Activities
(`EventsActivity`, the day's list, and `EventEditorActivity`, one event full screen), both
`exported="false"` and launched only by `CalendarActivity` with an `ActivityResultLauncher` —
**not a point, not an `API_VERSION` bump**; the calendar still declares 7. It shipped in six phases:
**Z1** (`893b20e1`, 2026-09-02) — the store (`CalendarSchema.V2`) and the pure recurrence engine;
**Z2** (`2846d770`, 2026-09-02, reshaped `148ac60d` the same night, then rebuilt to the user's own
sketch at `5a08f150`) — the events screen, the editor's fields, and the calendar's door to them;
**Z3** (`1404fea6`, 2026-09-03) — the note section, a page of handwriting plus a text field behind
one toggle; **Z4** (`7c54c5a8`, 2026-09-03) — the grid glyphs and Day-row labels; **Z5** UI cleanup,
split into **Z5a** (`5b17333a`, 2026-09-03) and **Z5b** (`e77cc160` + a Day-view follow-up
`ed6b7b54`, 2026-09-04); **Z6** — this docs commit. JVM tests grew from 1857 to **2087 per
variant** over the arc. Version stayed `0.1.0-ratta` throughout, by the user's call at planning —
the whole arc lives inside `:ext-calendar`, and the module count (twelve) and the point count
(seven) are both unchanged. See § Events (arc 24) below for what it built, and
`apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 24 \"Events\"" for the phase-by-phase record.

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

**Events are in now** (arc 24, granted by the user 2026-09-02): og's events, reminders and the paper
look-ahead all live inside this same extension — see § Events (arc 24) below. **Still not in this
arc, on the user's explicit call:** tasks, the day window, history, day notes, calendar export, and
the Today dashboard. Each of those is og's, each is a real feature, and each would need its own
fresh user decision before it becomes an eighth extension or a growth of this one — arc 24 is not a
step toward any of them, and events themselves are reachable only through the calendar's own
Events button, never from the library or the notebook directly. Within events themselves, two more
things are planner calls rather than gaps: there is **no search over event titles** (the library's
and the tag screen's search do not reach into the calendar's store), and **no notifications of any
kind** — a reminder surfaces only as an Upcoming row the person has to open the calendar to see.

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

## Events (arc 24)

**Not a point, and not a bump.** `EventsActivity` (the day's list) and `EventEditorActivity` (one
event, full screen) are two more Activities inside `:ext-calendar`, both `exported="false"` in
`AndroidManifest.xml`, and each launched with its own `ActivityResultLauncher`, in-process, by
whichever screen opens it — `CalendarActivity.openEvents()` launches `EventsActivity` on its own
`eventsLauncher`, and `EventsActivity` in turn launches `EventEditorActivity` on its own
`editorLauncher` (for Add, and for a tap on any row) — never the host, and never one screen reaching
past the next to launch the one after it. Both children carry `HostCallerCheck` nowhere in their
source: "nothing outside this APK can start it" is a stronger guarantee than a caller test, so there
is no check to make. `ICalendar`, `CalendarTarget` and `ExtensionContract` are untouched, the
calendar still declares **7**, and every event row lives in the same `Garden/…ext.calendar.db` the
store rows already lived in — the arc-21 backup copies it whole, unconditionally, exactly as before.
The two screens carry no paper of their own (Z2's list) or a bounded paper *view* rather than a
paper *screen* (Z3's editor) — see § The note below for what that distinction costs.

### The store (Z1)

`CalendarSchema.V2` is `V1`'s step, byte-for-byte unchanged, plus one new step — the events step,
quoted verbatim from `CalendarSchema.kt`:

```sql
CREATE TABLE event (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL,
    title TEXT NOT NULL,
    startDate TEXT NOT NULL,
    endDate TEXT NOT NULL,
    allDay INTEGER NOT NULL,
    startMinute INTEGER,
    endMinute INTEGER,
    recurring INTEGER NOT NULL,
    freq TEXT,
    interval INTEGER NOT NULL,
    monthlyMode TEXT NOT NULL,
    endMode TEXT NOT NULL,
    untilDate TEXT,
    endCount INTEGER,
    noteText TEXT NOT NULL,
    noteWidth REAL NOT NULL,
    noteHeight REAL NOT NULL,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL);
CREATE INDEX event_span ON event(startDate, endDate);
CREATE INDEX event_recurring ON event(recurring);
CREATE TABLE event_weekday (
    eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    weekday INTEGER NOT NULL,
    PRIMARY KEY(eventId, weekday));
CREATE TABLE event_exception (
    eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    date TEXT NOT NULL,
    PRIMARY KEY(eventId, date));
CREATE TABLE event_reminder (
    eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    amount INTEGER NOT NULL,
    unit TEXT NOT NULL,
    PRIMARY KEY(eventId, amount, unit));
CREATE TABLE note_stroke (
    id TEXT PRIMARY KEY,
    eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    "order" INTEGER NOT NULL,
    color INTEGER NOT NULL,
    width REAL NOT NULL,
    style TEXT NOT NULL,
    blob BLOB NOT NULL);
CREATE INDEX note_stroke_event_order ON note_stroke(eventId, "order");
```

Nine tables in the store now (five events tables over `V1`'s four), and `CalendarStore.open()` logs
`schema v1 → v2` on the first open after the upgrade, `schema v2` on every one after — the same
silent-catch-up rule the host applies to any missing step. `noteText` / `noteWidth` / `noteHeight`
ride on `event` itself rather than a side table: a note's text is small and every event has at most
one. `recurring` is a **stored mirror** of `freq IS NOT NULL` (og's own DAO shape) purely so the
expansion read (`event_recurring`) is an index hit rather than a `freq IS NOT NULL` scan;
`EventRows` drops a row where the two disagree rather than trusting either one alone (a dropped
event, never a lost day).

`note_stroke` is the pad's stroke row under its own name and parent: `NoteSql : InkDocument.StrokeSql`
supplies the six statements against `note_stroke` / `eventId` in exactly the shape `InkSql` and
`ScratchSql` already established (`INSERT OR REPLACE` — a stroke has no children — and
`DELETE … WHERE id = ?`, both idempotent; `clearStrokes(eventId)`; the length-only lens
`selectStrokeLens`; the ranged `selectStrokes`; `selectMaxOrder`). `InkSqlTest`'s discipline is
repeated for it in `NoteSqlTest`.

**`event` has children, so it is never `INSERT OR REPLACE`d** — the same rule arc 22 pinned for
`period`/`page`: REPLACE deletes the conflicting row first, and with foreign keys ON that delete
would cascade away the note along with the three child sets. `EventSql`'s upsert is therefore two
idempotent statements, `insertEvent` (`INSERT OR IGNORE`) then `updateEvent` (`UPDATE … WHERE id =
?`, every column but `id` and `createdAt`). The three child sets are each a `DELETE … WHERE eventId
= ?` followed by `INSERT OR IGNORE` rows, in the same batch as the event row — `clearWeekdays` /
`insertWeekday`, `clearExceptions` / `insertException`, `clearReminders` / `insertReminder`. **No
read carries an `IN (…)` list** (the 999-argument bind cap): a day's one-off events are read by span
overlap (`startDate <= ? AND endDate >= ?`, which ISO text orders correctly) via
`selectOneOffsOverlapping`, every `recurring = 1` row is read whole via `selectRecurring` and
expanded in Kotlin, and each set's child rows come by a `JOIN … WHERE recurring = 1` that names its
own parent on every row (`selectRecurringWeekdays` etc.) rather than a keyed list — a one-off's
child read is `eventId = ?` per row, which a day holds few of.

**`EventStore.save`/`edit`/`delete` are one transaction under the batch cap** — the event upsert,
its three child sets, and the note's stroke statements (built by `NoteSurface.write`, see § The
note) ride one `execAll`. Past the cap, `InkStore.compensated` runs the batches in order and, on a
failure after at least one has landed, compensates by what this write actually was:

- a **new** event's failed multi-batch save deletes the one row it minted (`EventSql.deleteEvent`) —
  the cascade takes whatever child rows and note strokes had already landed with it;
- an **existing** event's failed save drops exactly the stroke ids this save minted, one
  `NoteSql.dropStroke` each — the event row and its fields, having already existed, are left as they
  were before the write began.

`EventStore.edit(scope, original, edited, viewedDay, newId, note)` resolves **which id the edited
fields land under** before deciding anything else (`EventWrites.editLandsUnder`) — the original's
own id for an in-place edit, `newId` for a THIS-scope override or a FOLLOWING-scope split — and asks
its `note: (landedUnder) -> NoteWrite` lookup for that id only, so the store's compensation and its
return value can never name two different rows.

**Caps, pure and pinned (`EventRules`):** `TITLE_MAX` 200 (trimmed, tabs and newlines dropped — the
tag rule, not escaped to spaces), `NOTE_TEXT_MAX` 10 000, `REMINDERS_MAX` 3 (deduped by amount+unit,
sorted by lead), `INTERVAL_RANGE` 1..99, `END_COUNT_RANGE` 1..999, `MINUTE_RANGE` 0..1439, every date
normalized through `CalendarDates`, `endDate ≥ startDate`, `endMinute` cleared when it is before
`startMinute` or the event is all-day. `EventRules.normalize` is total — it fixes everything it can
without asking; `EventRules.Problem { EMPTY_TITLE, UNTIL_BEFORE_START }` names what it cannot, on the
**normalized** event, and both are `IllegalArgumentException` at the store, not `StoreUnavailable` —
a refused row is not a failed store. Events per day are unbounded.

**Event text is user content**: title and note text are never logged (`EventStore`'s log lines carry
counts and durations only), never ride an Intent beyond `EventsActivity`'s and
`EventEditorActivity`'s own extras (an event id and an ISO date, nothing else), and are never written
to prefs.

### The recurrence engine (Z1)

`Recurrence` is a fresh pure port of og's semantics — `occursOn`, `occurrenceStartCovering`,
`nextOccurrenceStart`, `generateStarts` — re-derived and JVM-tested against og's own cases (daily
every-N, a weekly weekday set, monthly on-the-day and ordinal-weekday including "the last Tuesday",
yearly Feb 29 landing only in leap years, `COUNT` enumeration, `UNTIL` inclusivity, an excluded start
taking its whole span with it) **with one deliberate divergence, pinned by name so no reviewer
"fixes" it: WEEKLY interval weeks are counted from Sundays, not ISO Mondays.** og counts an
interval's weeks from the Monday that opens an ISO week; this calendar's weeks start on Sunday
everywhere else — the grid's columns, `CalendarDates.weekStart` — so `Recurrence` counts weeks from
`CalendarDates.weekStart` instead, and a listed ISO weekday sits `d % 7` days after its own week's
Sunday. `RecurrenceTest.weeksAreCountedFromSundays_notIsoMondays` pins the concrete case the plan
recorded: an anchor of Saturday 2026-09-05 with `{Sun}` at interval 2 lands on Sunday 2026-09-13, not
2026-09-06.

`Upcoming` is og's `upcomingForDay` rule, ported the same way: a non-recurring event surfaces on
every day `D` where `occurrence − lead ≤ D < occurrence`, a recurring one via
`nextOccurrenceStart` bounded by its own largest reminder lead, an event with no reminders never
surfaces at all, and the result is one `UpcomingEvent(event, occurrenceStart, daysUntil)` per event —
its soonest qualifying occurrence — ordered nearest-first, then all-day, then title
(`EventOrder`). `EventWording` is pure and shared by every screen and the grid alike: every string —
the time badge ("All day" / "9:00 AM"), the meta line (type, span, the recurrence summary, the end
clause), the Upcoming badge ("Tomorrow" / "In 6 days"), the Day-row label ("N events") — is built
from ints through `CalendarDates`' hand lists, **never a formatter** (arc 5's rule, restated here for
a second reason to need it). `EventWrites` is the pure statement-list layer under `EventStore`:
`save`, `delete`, `deleteWithScope`, `editWithScope`, `editSeries` (og's three scopes, below) and
`editLandsUnder`, the one function both the store's compensation and its return value read so they
cannot disagree about which id an edit landed under.

**og's three recurring scopes**, implemented as statement lists rather than as store calls, so a
scope operation is JVM-testable without a database:

- **This occurrence** — `deleteWithScope` adds an exception date to the series; `editWithScope`
  adds the same exception **and** writes a standalone one-off event carrying the edited fields, its
  own reminders, and its own note (a copy, see § The note).
- **This and following** — `deleteWithScope` truncates the series to `UNTIL occurrence − 1`
  (`EventSql.truncateEvent`, which also clears `endCount`); `editWithScope` truncates the same way
  and starts a **fresh series** at the occurrence, carrying the reminders and a copy of the note. A
  split landing at the series' **first** occurrence collapses to the whole-series operation instead
  — there is nothing before it left to truncate.
- **All** — in place, on the original row; `editSeries` is what preserves the series' stored anchor
  when the edited dates come back exactly as `occurrenceStartCovering(original, viewedDay)` plus the
  span predicted — a birthday re-saved unchanged must not silently re-anchor itself to today. A date
  deliberately changed re-anchors the series, as it should.

An "ends on" date before the start is refused at `Save`, before any of this runs — see § The editor.

### The events screen (Z2)

`EventsActivity` lists one day: **Today**, then **Upcoming**, the tags idiom — section labels
`inkBlack`, "Today" appearing only when Upcoming follows (a label exists to tell two lists apart).
Rows are `EventsPaging.rows` over the store's two reads, paged **greedily by measured height**
against the band `EventsActivity` actually has (`EventsPaging.pageCount` / `pageOf` / `clampPage`,
two row heights — a header and an event card — so a page never ends on a header and never half-draws
a row); the in-band `‹ 1/2 ›` pager is `INVISIBLE`, never `GONE`, when there is only one page, and
the arrows never disable — a disabled control is invisible on e-ink. The bottom bar is the
calendar's own pager verbatim: `[‹] [day name, itself a tap target] [›]`, opening the shared
`DayPickerDialog` on a tap and stepping by finger swipe over the band (`ListSwipe`) — the day is
what this screen is about, not the in-band page.

Each row carries its own trash icon (`EventRowView.buildEvent`'s `onDelete`) — **delete lives on the
list, not the editor**, the user's call at the Z2 rebuild, because the person deleting a thing is
already looking at the thing they mean. `EventsActivity.confirmDelete` asks the scope sheet (This
occurrence / This and following / All events, `ActionSheetDialog`) only for a recurring event, then
raises a named confirm — `"Delete %1$s?"` with a body that says "This event will be removed" for one
occurrence or "Delete every occurrence of %1$s?" for a whole series, because those are not the same
act and must not read the same.

**`EXTRA_ENDED_ON`** (ISO text) rides back on every leave — an arrow, the picker, a swipe, Back, or
a failure — because the calendar follows the day the events screen ended on (the locked "Return"
decision): `CalendarActivity`'s `eventsLauncher` result callback reads it and calls
`showMove(nav.picked(ended, today, nowHour), forceBake = true)`, forcing a re-bake because an add or
a delete changes the grid's marks. **A read failure sets the day result before the dialog, not
after**: `EventsActivity.failAndClose()` calls `setDayResult()` first and only then raises
`Dialogs.confirm` on the dismiss of which it finishes — so a screen that could not read the store
still tells the calendar which day it was asked for, rather than leaving the calendar to be moved
later by a failure that told it nothing.

### The editor (Z2, rebuilt to the user's own design; Z5a/Z5b)

**The shape shipped is not the shape planned.** The wizard's original spec called for ten stacked
fields with a header row per group; the user found that layout "surprisingly bad" beside og's own
editor and, after a first reshape the same night, brought back a sketch that Z2 rebuilt to
(`5a08f150`). What shipped, and what `activity_event_editor.xml` and `EventEditorActivity.kt` still
say today, is **three rows over the note area**:

1. the title `EditText` with the event's **type** named on the button beside it (tapping it opens an
   `ActionSheetDialog` of the six types; choosing one on a *new*, untouched draft offers that type's
   usual recurrence — `EventType.defaultFreq`, yearly for Birthday and Anniversary, none otherwise);
2. the start and end date buttons (`DayPickerDialog`), the **All day** pill toggle (`swAllDay`, Z5b —
   below), and, only while all-day is off, the two time buttons (`TimePickerDialog`);
3. two **glance** buttons, Repeat and Remind me, each reading the bare word when the thing is unset
   and a concise value when it is set ("Weekly", "Every 2 weeks", "1 week before" —
   `EventWording.repeatGlance` / `reminderLabel`), and — at the right end of the same row, a `Space`
   weight 1 apart — the note's `[Handwriting] [Text]` latches (Z3's header row was dropped for this
   at Z5, see § The note).

The top bar is `[Cancel] … title … [Save]`: Cancel at the left (before the action, the standing
rule), the title ("New Event" / "Edit Event") centred **on the screen** rather than on the leftover
space between the buttons — `activity_event_editor.xml`'s `FrameLayout` idiom, the events screen's
own bar-centring trap avoided by construction — and no back arrow. System Back and Cancel are the
same door (`OnBackPressedCallback` → the same handler as `btnCancel`), behind a "Discard changes?"
confirm (`R.string.editor_discard_title` / `_body`) raised only when `draft.changedFrom(initialDraft)
|| note?.hasUnsavedChanges == true` — an untouched screen closes without asking. **Delete is not
here at all** — see § The events screen.

`RepeatDialog` and `RemindDialog` hold their own working copy of the `EventDraft` and apply it only
on their own Save, discarding on Cancel — including, for Repeat, the frequency choice itself, so
opening the dialog and backing out leaves the event exactly as found. `RepeatDialog.show` is two
steps: a frequency sheet first (Never / Daily / Weekly / Monthly / Yearly — Never saves at once,
there being nothing left to ask), then, for anything else, a details dialog (`dialog_repeat.xml`)
whose rows show and hide from the frequency and the end mode: an "every N" sentence line
(`tvEvery`) over a `CountLatches` row, the seven weekday latches Sun…Sat for Weekly, two monthly
radios worded from the start date ("On day 17" / "On the 2nd Tuesday", `EventDraft.ordinalOf`), and
**Ends as one row of three one-armed latches** (`[Never] [On a date] [After]`, `LatchGroup` the
exclusivity) with its dependent control — the date button or a second `CountLatches` row — shown
only under the picked latch. `RemindDialog` is the same shape for the editor's **one** reminder: a
`CountLatches` amount over Days/Weeks latches, a live preview line, and three buttons — None, Cancel,
Save — because "no reminder" is a real answer distinct from either saving or discarding.

**`EventDraft` is the editor's whole state, flat and pure** (`EventDraft.kt`, roughly two dozen
fields — `id`, `type`, `title`, the two dates, `allDay`, the two minutes, the repeat's six fields,
`reminders`, `repeatTouched`, `exceptions`, the note's three) — every field *rule* a small pure
function tested on its own: `withStartDate` moves an end date that sat on the start, turning all-day
on clears both minutes and coming back seeds 9:00 AM, `applyFreq` seeds a weekly rule's weekday from
the start date's own when none is chosen yet, `repeatTouched` gates whether a type change is still
allowed to offer its default recurrence. **`reminders` is a `List<Reminder>`, but `withReminder(r:
Reminder?)` only ever replaces it wholesale with `listOfNotNull(r)`** — the editor offers one
reminder, the user's call, while `EventRules.REMINDERS_MAX` stays 3 at the store: an event saved
before this editor existed may still carry three, and saving it from here is what reduces the list
to one. `toEvent` is the one place a draft becomes an `Event`, through `EventRules.normalize` — the
same normalization the store re-runs — so the screen and the row can never silently drift apart.

**Save routes on whether the event is new.** A brand-new event is `EventStore.save(edited, isNew =
true, note)`; anything else is `EventStore.edit(scope, existing, edited, viewedDay, newId, note)` —
a one-off original always at `Scope.ALL`, which `EventWrites.editWithScope` routes to the same
in-place `editSeries` a whole-series edit takes, and a recurring original at whichever scope the
scope sheet answered. **The recurring prefill is a plain copy**: opening an occurrence of a series
sets `covering = Recurrence.occurrenceStartCovering(e, viewedDay)` and prefills the draft's start
and end from exactly that pair — nothing rounds or re-derives it — because `EventWrites.editSeries`'s
anchor-preservation check compares the edited dates against that same `occurrenceStartCovering` call
plus the span, and a prefill built any other way would make an unchanged Save read as a deliberate
re-anchor. `Save` refuses at `EventRules.problem()` before any of this: `EMPTY_TITLE` and
`UNTIL_BEFORE_START` each raise `Dialogs.problem` and return, never reaching the store.

**Z5a/Z5b replaced four controls without touching the shape above:**

- The events list became og's bordered cards — see § The events screen and `EventRowView` in § Where
  the code is.
- The editor's top bar gained its centred-title FrameLayout idiom (described above).
- The note area gained its "Notes" label on both halves (§ The note).
- Ends became the one-latch row described above, in place of a `RadioGroup`.
- **All day** became the pill toggle: `:sn-screen`'s `Widget.Notesprout.Toggle` style on an
  `AppCompatCheckBox` (`button=@null`, background `toggle_pill`, a `state_checked` selector of two
  fixed-size layer-lists) — OFF an outlined white pill with the knob at the left, ON a black-filled
  pill with the knob at the right, no ON/OFF caption because the label beside it and the fill say
  everything, `SwitchCompat` gone. **The style's 56 dp width is the drawable's own geometry** — the
  knob's 9 dp insets are computed from that width, so changing one without the other breaks the
  drawable. `swAllDay` keeps its id and its plain `CompoundButton` listener.
- **Time became a static clock face** in place of the stepper dialog: `[9]:[00]` hour/minute
  **latches** (not steppers) above a drawn `ClockFaceView`, plus AM/PM latches. `ClockFaceModel` is
  pure and JVM-tested: both faces hold exactly **twelve positions, clockwise from the top** — hours
  12, 1…11, minutes 0, 5…55 at `TimeMath.MINUTE_STEP` apart — `hit(face, dx, dy, radius)` inverts the
  drawing angle with `atan2(dx, -dy)`, answering null inside 0.30 of the radius (too close to the
  centre to mean one position over another) and outside 1.15 of it (off the dial). Picking an hour
  swaps the dial to the minute face by itself — the one automatic step, and the reason it is two taps
  rather than three — while picking a minute stays; the hour/minute latches above the dial are the
  way back to a face already left. `ACTION_UP` is what picks; there is no animation anywhere in it.
  `TimeMath.stepHour` and `stepMinute` are deleted — the rest of `TimeMath` (the 12-hour split, the
  round trip) stays the time truth.
- **Small counts became preset latches.** `CountPresets` (pure): six presets 1–6 each get their own
  latch, `pressed(value)` puts exactly one latch down always (a value below the floor still arms 1,
  because a row with nothing down reads as broken on e-ink), and the seventh latch, More, reads the
  number itself once the value has left the presets and the word "More" otherwise
  (`moreLabel`). `CountLatches` is the seven-button wrapper (`view_count_latches.xml` `<include>`d
  three times: Repeat's interval, "after N times", and Remind's amount) — it reports a tap through
  its `onValue` callback and remembers nothing itself; the caller writes the new value into whatever
  it holds and calls `render` back. More opens `KeypadDialog` (`dialog_keypad.xml`: a preview line
  over `1 2 3 / 4 5 6 / 7 8 9 / ⌫ 0 ✓`) — **✓ is a grid key, not a dialog button**: the dialog's own
  bar carries Cancel alone, because a positive button beside a ✓ key would be two doors to one
  answer. `KeypadModel(range, current)` is pure: typing tracks a digit **string**, not an int (`"0"`
  and `""` are different states, the same number), a leading zero is replaced rather than built on, a
  digit that would exceed the range's own widest number is refused outright (not silently clamped —
  clamping 999 into 1..99 would read as the keypad eating two digits), and `value()` still coerces
  into range on the way out for the one case width alone cannot catch (a two-digit number below a
  two-digit floor). The ± steppers, `Editor.StepperValue`, `RepeatDialog.unitLabel`, and their eight
  `editor_unit_*` strings are gone.

`LatchGroup<T>` is the small pure one-armed-latch helper both the Ends row and the two-state
patterns like it use: `pressed(selected)` marks exactly one option down, an unrecognized selection
leaving the **first** option down rather than none; `resolve(current, tapped)` answers the tapped
option when it is a real one, `current` unchanged otherwise — tapping the already-down latch is a
no-op, there being no "off" in a one-armed row. It does not govern the weekday row, which is
multi-select.

### The note (Z3)

**One page of handwriting on a bounded g-paper surface, plus a multi-line text field, behind one
`[Handwriting] [Text]` toggle** — new versus og entirely, and the first time this codebase has put
two paper surfaces in one process (the calendar behind the events list, and now the editor's note).
`NoteSurface` owns everything a tier-2 screen's skeleton would otherwise supply — the `PaperView`,
an `InkDocument` over `NoteSql`, an in-memory `UndoRedoStack<InkAction>`, `PageGestures` (undo/redo
only — no flips, there being only one page), and an `InkSelectionBar` with **Delete alone** (there is
nothing to Send to) — while deliberately leaving out what the skeleton exists for: **no debounce and
no leave flush**. The note rides in memory until the event's own Save, where its statements join the
event's one transaction; Cancel discards ink exactly as it discards a typed title.

**The page size is minted once, never re-measured.** A note that already has a size
(`event.noteWidth × noteHeight`, written with its first stroke) keeps it wherever it is shown,
anchored top-left, 1:1 — the pad's rule exactly. A note with no size yet takes the note area's size
at its **first** layout and holds it, because the soft keyboard shrinks the area under
`adjustResize` and the page must not shrink with it: on the Nomad, with the note's two latches on
row 3, that first-layout size measured **1404 × 1277 px**. `mintedSize()` is what Save actually
writes: the page's current size once there is ink on it, the stored size (possibly still 0 × 0) while
there is not. **An existing event's `show()` lands before the first layout pass**, so the page size
for a reopened note in practice comes from the layout listener rather than from `show()` itself — a
stored size greater than zero wins over either.

**"Blocked" is how the surface disappears without losing the pen.** While the keyboard is up, or the
Text half is showing, or before the page has loaded, `NoteSurface`'s view is `INVISIBLE` **and** a
whole-view exclusion rect is set over it — because an attached Ratta paper view keeps the firmware
pen claimed whatever its own visibility says, and the firmware paints wherever it is not told not
to; a merely-hidden view without the exclusion rect would still ink under the keyboard. **Nothing in
this screen ever hides the IME itself** — the Ratta rule, restated here for a form field rather than
a page of text — so a Handwriting-latch tap while the keyboard is up sets the kind immediately and
shows the surface only once the person dismisses the keyboard with its own key. `applyKind()` is the
one function every such change routes through, because "blocked" has two independent inputs (the
kind, and whether the IME is up) and only one of them is ever a tap.

`NoteKind.defaultFor` decides where a showing starts: **Handwriting** whenever the event has stroke
rows (ink always wins), **Text** when it has text but no strokes, **Handwriting** again when it has
neither (an empty note is an invitation to write, not to type). Text and Handwriting **both keep
their contents** at all times — the toggle only ever chooses which is shown, never clears the other.

**"Notes" labels both halves** (Z5a): the paper half bakes the label into the note's own template —
`CalendarTemplate.note()` builds a page-sized transparent bitmap carrying only `bandLabel` at its
top-left, exactly the geometry `CalendarTemplate.bandLabel` already draws on Month and Week (14 sp,
`palette.light`, 8 dp in, 4 dp down) — rebaked only when the page's own size changes; the text half
draws the same label as a plain `TextView` (`calendar_notes_label`, one string) above the `EditText`,
not a hint, so it reads the same on both halves whichever is showing.

**`write(landedUnder)` is the whole of what a note contributes to a save**, and it is not always the
same shape. When `landedUnder` is the id the note was loaded for, it is the pending op log alone
(`InkDocument.pendingStatements()` — read-only, so a failed Save leaves the exact same log for the
next tap). When it is not — a THIS-scope override or a FOLLOWING-scope new series, both of which mint
a fresh event id — it is `NoteWrite.copy`: every stroke re-encoded under a **fresh stroke id**,
because `note_stroke.id` is the table's primary key and re-parenting the existing rows would steal
the original series' note rather than copy it. `EventStore.edit`'s own `editLandsUnder` call is what
decides which of the two the editor is even allowed to ask for — a copy is built only when the
original was recurring, never for an in-place edit or a plain one-off save.

### The handoff finding (Z3)

**The calendar needs no `releaseForHandoff` before the events screen — only the editor's own surface releases, and it releases before every `finish()`.** The plan's default going in was that
`CalendarActivity` would hand the EPD pipeline over before launching the events screen, exactly as
the notebook does before opening the calendar; Z3's on-device probe (a throwaway second `GPaper`
surface in the editor, run before any of the shipped code existed) found the calendar's own paper
tears down on focus loss the moment the events screen comes to the front, and the engine's
process-local `inkOwner` guard covers the whole chain by itself — the calendar reclaims in its own
`onResume` once the list (and, behind it, the editor) is gone. So what shipped is simpler than the
plan: the calendar touches nothing before opening the events screen, the events screen touches
nothing (no paper of its own to hand off), and only the editor's `NoteSurface` calls `resume()` (`resumeDrawing`) in
`onResume` and `handoff()` (its own `releaseForHandoff`) before **every** `finish()` — never only on the happy path, because Cancel
and a store failure both leave through `finish()` too. g-paper's pin stayed at 0.1.23; nothing about
this finding needed an engine change. One edge was recorded and accepted rather than fixed: a stroke
that starts inside the note area and is dragged out of it keeps its out-of-area points in the model
(one measured at `top=-95`) — the firmware paints nothing outside the bounded view and the committed
render clips to it, so what the person sees stays honest even though the stored geometry carries a
few points past the page edge.

### The grid (Z4)

`DayMark(title, allDay, startMinute, glyph)` is deliberately **neutral of the store** — no id, no
recurrence, no reminders, no note — because the grid's bake key (`CalendarActivity.BakeKey.marks`)
compares marks **structurally, as the map itself, not as a hash**: a mark must carry exactly what is
drawn and nothing that only moves underneath it, or a hash collision could leave a deleted event's
glyph on the page. `Glyph` is og's six per-type icons (`Glyph.of(type)` — cake, heart, suitcase,
people, clock, dot) drawn as Canvas primitives so the template painter stays Context-free — it holds
no resources and no drawables, only arithmetic on a box. `MarkSource` is a `fun interface`, the
document's one read seam onto marks; `EventStore : InkStore, MarkSource` answers it with `marksFor`,
the same six-query `eventsInRange` mapped to `DayMark`s rather than a separate read.

`GridMarks` is pure: `distinct` keeps first-seen order so two birthdays on one day still show one
cake; `layout` is og's slot arithmetic — right-packing from the row's right edge, a lone `+` when a
narrow cell has room for one slot but more than one type, the first `max − 1` glyphs kept and a `+`
closing the row on overflow; `rangeOf(target)` answers Month's 42 cells (out-of-month included),
Week's seven, or the Day's own one day, whichever half is showing. `DayRows` is pure too: all-day
**and** timeless marks (a non-all-day event with no start minute at all — `EventRules.normalize`
allows both shapes) take rows from the **top of both halves**, a timed mark sits at its own 30-minute
row **only on the half its minute falls in**, one entry in a row is its title and two or more become
`EventWording.dayRowLabel`'s "N events" (no new string — Z1's wording function, reused as-is), and
`labelMaxWidth` caps the label at half the row, right of the gutter, ellipsized via `breakText`.

`CalendarDocument.show` loads a page's marks in the **same IO hop** as its strokes and its flush —
one round trip, not two — and `show(target, refreshMarks = true)` on the page already showing is a
**marks-only hop**: no page read, no flush, no bookmark write, just a fresh `marksFor` call. The
events screen's `EXTRA_ENDED_ON` return (§ The events screen) is the one caller of that shape, since
an event add or delete changes only the marks, never the page's own ink. `CalendarTemplate.dayCell`
draws the glyph row **ring-aware on today's cell** — og let a glyph touch the today ring; here the
row leaves it room — and `CalendarTemplate.day` draws its row labels in a **second pass, after every
divider**, so a label is never drawn under a line it should sit beside. The `+` overflow case is
unprovable on the Nomad (a Month cell there is roughly 198 px, wide enough for all six glyphs at
once) and is JVM-only, pinned by `GridMarksTest`.

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

Mirrors the pad's, row for row. Arc 24's events rows follow below the arc-23 transfer rows.

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
| The events list could not read the store | events list | "Events unavailable" / "Notesprout SN could not read the calendar's storage. Nothing was lost — try again." | the day result is set **before** the dialog, so the calendar still follows the day this screen was asked for; screen closes on dismiss |
| The editor could not read an event (or its note) | editor | "Cannot save" title / "Notesprout SN could not read the calendar's storage. Nothing was lost — try again." | screen closes on dismiss; a note read failure is treated as a full store failure, never as an empty note — an empty page on the glass would let the next Save write emptiness over ink |
| Save refused: no title | editor | "Cannot save" / "Give the event a title." | nothing written, editor stays open |
| Save refused: "ends on" before the start | editor | "Cannot save" / "The \"ends on\" date is before the start." | nothing written, editor stays open |
| A new event's Save fails part-way | editor → store | "Cannot save" / "Notesprout SN could not save the event. Nothing was changed." | **compensated**: the half-landed row is deleted by id, the cascade takes whatever children and note strokes landed |
| An existing event's Save fails part-way | editor → store | same "Cannot save" dialog | **compensated**: the row and its children are kept; only the strokes *this* save minted are dropped, one `DELETE` each |
| A recurring edit's day maps to no occurrence (raced by another writer) | editor → store | same "Cannot save" dialog | nothing written — never a silent no-op mistaken for success |
| Delete fails | events list | "Cannot delete" / "Notesprout SN could not delete the event. Nothing was changed." | nothing removed |
| A recurring delete's day maps to no occurrence (raced by another writer) | events list → store | `EventStore.delete` answers `false`, which `EventsActivity.delete` cannot tell apart from any other failure — same "Cannot delete" dialog | nothing removed; never a whole-series delete by accident |
| An event row will not decode | store, on read | nothing — no dialog | that event is **dropped, never surfaced**; counted and logged once per read, the rest of the day still lists |

## Entry points

| Where | Behaviour |
|---|---|
| Library top bar, before the pad (Y4 — the pad is always the last button) | opens the calendar with **no** Send buttons — there is no notebook to send to |
| Notebook top bar, right cluster, before the pad (Y4 — same placement call) | hands the EPD pipeline over first (`releaseForHandoff()`); the calendar gets both Send buttons |
| Notebook selection toolbar, 8th button (ink-only, second extension-gated slot, between Pad and Tag) | the outbound (notebook → calendar) transfer above |
| Calendar top bar's own Scratch Pad button, last on the bar (Y4) | shown only when the host found a trusted pad; `exit(RESULT_CALENDAR_OPEN_SCRATCH_PAD)` hands the door to the host, which opens the pad and brings the calendar back at its bookmark on a plain close — see § The two doors + the held bind |
| Calendar top bar's own Events button, between Send and Scratch Pad (arc 24 / Z2) | opens `EventsActivity` on the **first day of the showing period** — Month → the 1st, Week → its Sunday, Day → that day (`EventsLaunch.launchDay`, the anchor is ignored); the calendar follows the day the events screen ends on and force-rebakes on return, since events may have changed |

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
- **Events, reminders and their handwritten/text notes are in now (arc 24) — reachable only through
  the calendar's own Events button.** It still has no tasks, day window, history, day notes,
  calendar export, or Today-dashboard presence, no notification of any kind, and no search over
  event titles. Every one of those is a later, separate decision — see "What it is, and is not."
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
| `:ext-ink` `InkDocument.pendingStatements()` | arc 24 / Z3 — the op log as statements **without clearing it**, for a consumer (the note) whose whole page rides one outer transaction rather than a flush of its own |
| `:ext-calendar` `CalendarApplication` | registers `RattaEngine` — the extension's own process hosts paper |
| `:ext-calendar` `CalendarService` / `CalendarSession` | thin on `:ext-ink`'s `InkTransferSession` since Y4 — `CalendarService` supplies the target's own null check and the log wording |
| `:ext-calendar` `CalendarSchema` / `CalendarSql` | the calendar's own tables and SQL (pinned by `CalendarSqlTest`) — since Y4 the `stroke` table and its six statements are `:ext-ink`'s `InkSql` (`CalendarSql : InkDocument.StrokeSql by InkSql`) |
| `:ext-calendar` `CalendarStore` | the store calls, on `:ext-ink`'s `InkStore` |
| `:ext-calendar` `CalendarDocument` | the showing page in memory: target, mint/size bookkeeping, delegates ink to `InkDocument`; implements `:ext-ink`'s `InkPage` since Y4 |
| `:ext-calendar` `CalendarGeometry` / `CalendarTemplate` | the three layouts' rects and hit-tests; the template painter |
| `:ext-calendar` `CalendarNavigation` | the pure anchor rule and every `Move` |
| `:ext-calendar` `DayPickerModel` / `DayPickerDialog` | the picker's grids (pure) and its views |
| `:ext-calendar` `CalendarToolbar` | the chrome, the fixed tools, the pager, both Send buttons, and (Y4) the three Tabler view latches and the calendar's own Scratch Pad button |
| `:ext-calendar` `CalendarActivity` | thin on `:ext-ink`'s `InkScreenActivity` since Y4 — navigation, template bake, the picker, double-tap, `followReplay()`; (Z2) `openEvents()`/`eventsLauncher`, `btnEvents` |
| `:ext-calendar` `Event` / `EventType` / `Freq` / `MonthlyMode` / `EndMode` / `ReminderUnit` / `Reminder` / `RecurrenceRule` / `UpcomingEvent` / `Scope` | arc 24 / Z1 — the event model and its small pure types |
| `:ext-calendar` `EventRules` | arc 24 / Z1 — the caps, `normalize`, `problem` (`Problem.EMPTY_TITLE` / `UNTIL_BEFORE_START`) |
| `:ext-calendar` `Recurrence` | arc 24 / Z1 — the recurrence engine: `occursOn` / `occurrenceStartCovering` / `nextOccurrenceStart` / `generateStarts`; the Sunday-weeks divergence is pinned here |
| `:ext-calendar` `Upcoming` / `EventOrder` | arc 24 / Z1 — the look-ahead rule and the two shared comparators |
| `:ext-calendar` `EventWording` | arc 24 / Z1 — every event string, built from ints and `CalendarDates`' hand lists |
| `:ext-calendar` `EventSql` | arc 24 / Z1 — every statement against `event` and its three child tables, pinned by `EventSqlTest` |
| `:ext-calendar` `NoteSql` | arc 24 / Z1 — `InkDocument.StrokeSql` against `note_stroke`/`eventId`, written out rather than delegated (the table/column names differ from `InkSql`'s) |
| `:ext-calendar` `EventRows` | arc 24 / Z1 — row → `Event` decode; a bad row is dropped and counted, never folded to `OTHER` |
| `:ext-calendar` `EventWrites` | arc 24 / Z1 — `save` / `delete` / `deleteWithScope` / `editWithScope` / `editSeries` / `editLandsUnder` as pure statement lists |
| `:ext-calendar` `EventStore` | arc 24 / Z1 — the events half of the store, on `:ext-ink`'s `InkStore`; also implements `MarkSource` (Z4) |
| `:ext-calendar` `CalendarSchema` (V2 step) | arc 24 / Z1 — the events tables, appended to `V1`'s landed step |
| `:ext-calendar` `EventDraft` | arc 24 / Z2 — the editor's flat pure state and every field rule; `toEvent` / `problem` / `changedFrom` |
| `:ext-calendar` `EventsLaunch` | arc 24 / Z2 — the locked launch-day rule (Month → 1st, Week → Sunday, Day → that day) |
| `:ext-calendar` `EventsPaging` | arc 24 / Z2 — `EventsRow`, the Today/Upcoming assembly and the greedy height-paged walk |
| `:ext-calendar` `EventRowView` | arc 24 / Z2, the card rebuilt at Z5a — the events band's two row shapes, built in code (`TagRowView`'s idiom) |
| `:ext-calendar` `EventsActivity` | arc 24 / Z2 — the day's list: paging, the bottom pager/picker/swipe, delete, `EXTRA_ENDED_ON`, and its own `editorLauncher` (Add and every row tap open the editor from here, not from `CalendarActivity`) |
| `:ext-calendar` `EventEditorActivity` | arc 24 / Z2, rebuilt to the user's design at Z2/Z5, the note wired at Z3 — one event, full screen |
| `:ext-calendar` `TimeMath` | arc 24 / Z2, steppers removed at Z5b — the time truth: `minuteOfDay` / `hour12` / `isPm` / `snap` |
| `:ext-calendar` `TimePickerDialog` | arc 24 / Z2, rebuilt on `ClockFaceView` at Z5b — `[9]:[00]` latches, the dial, AM/PM |
| `:ext-calendar` `RepeatDialog` / `RemindDialog` | arc 24 / Z2, cut back to `EventDraft`-only state at Z2's rebuild — the two glance-button dialogs, apply-on-Save/discard-on-Cancel |
| `:ext-calendar` `NoteKind` | arc 24 / Z3 — `defaultFor(hasStrokes, hasText)` |
| `:ext-calendar` `NoteWrite` | arc 24 / Z3 — a save's note contribution: `statements` + `mintedStrokeIds`; `NONE` / `copy` |
| `:ext-calendar` `NoteSurface` | arc 24 / Z3 — the bounded g-paper surface: in-memory `InkDocument`, minted page size, `blocked`, the EPD handoff hooks (`resume` / `handoff` / `release`) |
| `:ext-calendar` `DayMark` / `Glyph` / `MarkSource` | arc 24 / Z4 — the grid's neutral read of an event, the six glyphs, the document's read seam |
| `:ext-calendar` `GridMarks` | arc 24 / Z4 — pure glyph-row placement (`distinct` / `layout`) and `rangeOf` |
| `:ext-calendar` `DayRows` | arc 24 / Z4 — pure Day-row bucketing (`slotOf` / `bucket` / `label` / `labelMaxWidth`) |
| `:ext-calendar` `ClockFaceModel` / `ClockFaceView` | arc 24 / Z5b — the dial's pure hit-testing and the Canvas view |
| `:ext-calendar` `CountPresets` / `CountLatches` | arc 24 / Z5b — the six-preset-plus-More row (pure model / the seven-button view over `view_count_latches.xml`) |
| `:ext-calendar` `KeypadModel` / `KeypadDialog` | arc 24 / Z5b — the digit pad behind More (pure typing rule / the `1…9 · ⌫ 0 ✓` grid view) |
| `:ext-calendar` `LatchGroup<T>` | arc 24 / Z5a — the one-armed-latch helper the Ends row and the count row share |
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
| `:sn-screen` `ic_calendar_event` | arc 24 / Z2 — Tabler `calendar-event`, the calendar's own Events door icon |
| `:sn-screen` `ic_backspace` | arc 24 / Z5b — Tabler `backspace`, the keypad's ⌫ key |
| `:sn-screen` `Widget.Notesprout.Toggle` / `toggle_pill` | arc 24 / Z5b — the pill toggle: an `AppCompatCheckBox` with no button/text over a two-state selector drawable (44 × 24 dp pill in a 56 dp × `toolbar_button_size` view; the view width is part of the drawable's own geometry, the 9 dp knob insets computed from it); the editor's `swAllDay` is its first use |
| `:sn-screen` `Widget.Notesprout.DialogButton` | arc 24 / Z3 — gained `layout_marginStart` 16 dp + 16 dp side padding, for every two-button dialog in the app (the discard dialog's buttons sat 8 dp apart before this) |

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
| `ext-calendar/RecurrenceTest` | every frequency (daily interval-N, weekly with a chosen weekday set and with none, monthly day-of-month across short months, monthly ordinal incl. "the 5th means last", yearly incl. Feb 29 only in leap years), the Sunday-weeks divergence pinned by name, COUNT enumeration and its stop, UNTIL inclusivity, an excluded start taking its whole span with it, a one-off answering its own span through the `Event` overloads, `nextOccurrenceStart`'s strict-after/bounded/exception-skipping rules, a non-positive horizon and an empty `generateStarts` |
| `ext-calendar/UpcomingTest` | a lead that reaches the day surfaces and one that doesn't does not, the day-before/day-of boundary, a span already under way is not upcoming, no reminders never surfaces, a recurring event bounded by its largest lead, one row per event at its soonest occurrence, an excluded occurrence skipped, the nearest-first/all-day/title order, the year-long horizon |
| `ext-calendar/EventRulesTest` | title trimmed/tab-and-newline-dropped/cut, the note text cut, reminders filtered/deduped/sorted/capped (a tie between a week and seven days breaks by unit), an inverted span straightened, all-day clearing both minutes, an end minute before the start cleared, the interval and weekday/mode/date/count field clearing per `endMode`, `normalize` idempotent, the two `Problem`s |
| `ext-calendar/EventSqlTest` | the events step's shape, every statement through the real host validator, `event` never `REPLACE`d, no read ever carries an `IN (…)`, the one `COLUMNS` constant, `insertEvent`/`updateEvent`'s exact columns, the NOT NULL defaults on a one-off, the two stamps, the delete, child sets cleared then `OR IGNORE`d, the event/child/set reads including a one-off's reminders JOIN |
| `ext-calendar/EventStoreTest` | a range as six queries expanding the recurring set in Kotlin, a saved event reading back with its children, the day order, marks narrowed to what the grid draws (and empty for an empty range), Upcoming as its own six queries, `get` reading the row and its three child sets, the note read through the lens then the planned ranges, a bad row dropped while the day still lists, `delete` answering false for no occurrence, a delete taking its children and note, `edit` answering the id the fields landed under, an override/an in-place edit each asking for the note under the right id, a failed override/in-place-edit/new-event save each compensating correctly, `save` refusing a `Problem` before any store call, the caps applied on the way in, every store failure reading as `StoreUnavailable` |
| `ext-calendar/EventWritesTest` | the note's puts, a save as row-then-children-then-note, a one-off still clearing every child set, a non-recurring or whole-series delete as one statement, THIS as an exception-plus-stamp, FOLLOWING as a truncate, a split at the first occurrence collapsing to a whole delete or whole edit, a day mapping to no occurrence answering nothing-to-do, THIS/FOLLOWING edits exceptioning or truncating and starting a fresh series, ALL keeping the anchor when the dates come back as the prefill and re-anchoring on a deliberate change, a new or non-recurring save/edit taking the one plain road |
| `ext-calendar/EventRowsTest` | a good row round-trips, a one-off carries its defaults and ignores stray child rows, an unknown enum name / an unparseable date / an end before the start each drop the row, the `recurring` mirror is load-bearing, a wrong storage-class cell drops the row, the child-row helpers, an unknown type is never folded to `OTHER` |
| `ext-calendar/EventWordingTest` | 12-hour minutes, the date builders, the events screen naming the whole day (not a half), the time badge, the meta line growing with the event, a span carrying the year on both sides only when they differ, recurrence summaries, weekdays listed Sun-first, the ending clause, the Upcoming row, the repeat glance saying only the value, reminder labels, the Day-row label, every type's label and default |
| `ext-calendar/EventDraftTest` | a blank draft asks the fewest questions, an existing event comes back as itself, a one-off gets the blank repeat defaults, a type offers its default repeat only on an untouched new draft, a one-day event stays one day when its start moves, a span pushed only when the start passes it, an end date never precedes the start, an "ends on" before the start is left for Save to refuse, all-day clearing both times and seeding a start coming back, an end time before the start cleared, the weekly weekday seed and the last-weekday-cleared case, turning the repeat off keeping the controls, the interval/count clamps, "ends on" seeding its date, the draft's rule matching what it renders, one reminder set/replaced/cleared, the note text edit and its cap, the note size riding through, `changedFrom` ignoring `repeatTouched`, a blank title as the one thing normalization can't fix, the draft round-tripping through `Event`, the ordinal-slot helper matching the engine |
| `ext-calendar/EventsLaunchTest` | a Month opens the 1st, a Week its Sunday, a Day that day, an unknown kind answering the day handed over |
| `ext-calendar/EventsPagingTest` | both sections empty is no rows, a day alone carries no "Today" label and Upcoming alone still carries its own, a header is shorter than a row, an empty list is one empty page, pages fill greedily and never half-draw a row, a shorter header lets one more row on, a page never ends on a header, a band too short for even one row still shows it, `clampPage` keeps the page inside the list (never throwing past the end), every row lands on exactly one page |
| `ext-calendar/TimeMathTest` | the 12-hour split puts midnight and noon at 12, the minute part snaps to the face's grain, the three parts round-trip for every minute of the day, the default is 9 AM |
| `ext-calendar/ClockFaceModelTest` | every drawn position reads back as the number drawn there, the top of the dial is 12/0, the seam between two positions falls exactly halfway, the dead centre and the far outside pick nothing, only the minute face zero-pads its labels, both faces hold twelve positions clockwise from the top |
| `ext-calendar/CountPresetsTest` | each preset arms its own latch, anything past the presets arms More, a value below the floor still leaves one latch down, More shows the number only once it is carrying one |
| `ext-calendar/KeypadModelTest` | nothing typed is not a number yet, a leading zero is replaced rather than built on, the range's widest number is the typing cap, backspace past empty is a no-op, the value comes back clamped inside the range, clear resets to nothing typed |
| `ext-calendar/LatchGroupTest` | exactly one true for each option, an unknown selection leaves the first down, `resolve` picks the tapped option and ignores an unknown tap, tapping the down latch keeps it down, empty or duplicate options throw |
| `ext-calendar/NoteKindTest` | ink always wins a tie, text-only is the one exception |
| `ext-calendar/NoteSqlTest` | `putStroke` is idempotent and carries format-B geometry, both deletes, the three reads keep `"order"` quoted, the table is the pad's row under its own name and parent |
| `ext-calendar/NoteWriteTest` | the minter, `copy` re-mints every stroke id, a copy targets the new event and keeps orders/content, a copy mints nothing to give back (its compensation is the row's own delete), `NONE` is empty |
| `ext-calendar/DayMarkTest` | every type has its glyph, a mark carries its title/time/glyph, an all-day mark carries no minute, two independently built equal mark maps are equal, a changed title/type/day (or time) breaks equality |
| `ext-calendar/GridMarksTest` | `distinct` is first-seen order (two birthdays are one cake) and empty of nothing, glyphs pack against the right edge for one/two/three, overflow keeps the first `max − 1` and ends with `+`, exactly `max` slots does not overflow, one slot with two types is a lone `+`, one slot with one type is that glyph, no room draws nothing, a zero-sized slot is refused rather than divided by, Month asks all 42 cells (out-of-month included), Week its seven days, Day its one day whichever half |
| `ext-calendar/DayRowsTest` | the AM/PM half windows, all-day marks from the top on both halves, a timed mark at its own row and only its own half, an all-day-plus-midnight-timed pair sharing row zero and being counted, a timeless mark taking an all-day row, more all-day marks than rows dropping the rest, keys ascending whatever arrival order, nothing buckets to nothing, one entry is its title and more is a count, the label may take half the row right of the gutter |

`FakeCalendarStore` (`ext-calendar`) is the test double behind `CalendarStoreTest` and
`CalendarDocumentTest` — it applies the four write shapes literally (not merely recording them),
the same discipline `docs/tags.md`'s `FakeTagStore` follows for the same reason: a fake that only
recorded statements could never exercise a real compensation or a real re-read. **`FakeEventStore`**
(arc 24 / Z1) follows the same discipline for the events half — it applies `EventWrites`' statement
lists literally rather than recording them, which is what lets `EventStoreTest` exercise a real
compensation and a real re-read rather than asserting on the statements alone. `TestEvent.kt`'s `testEvent(…)` is the
builder every events test constructs its fixtures from.

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
- **(Historical, Z2) A conditional row's divider must flip WITH the row or hidden rows stack
  dividers into a thick line** — three sibling `View`s under the original og-form editor's Repeats
  group stayed visible when their rows went `GONE`, until they were given ids (`dividerWeekdays` /
  `Monthly` / `Ends`) that flip with their rows. The og-form editor that trap applied to is gone
  (rebuilt to the user's own design the same night); the **current** editor and its two dialogs
  carry no per-row dividers at all (`dialog_repeat.xml`'s own comment says so). Recorded here as a
  standing trap for any *future* text-styled row this family adds, not as a description of what
  ships today.
- **The calendar's top bar carries TWO calendar-with-mark icons** — `btnToday` (a star, x≈705 on the
  Nomad) and `btnEvents` (x≈1207) — so a walk script aimed at the old Today coordinate opens nothing
  once Events is added next to it. `CalendarActivity` also refuses a direct `am start` (`refused
  caller (none)`); the calendar can only be reached by tapping through the host library's own
  Calendar button.
- **A Haiku walk agent wanders OUT of the app on the Supernote** — a stray Back at the library lands
  in the Supernote launcher, and the agent then explores the device's own apps rather than the
  screen it was sent to check. Drive anything past about five taps by hand (Fable, in this arc);
  give a scripted walk no Back at the library at all.
- **An existing event's note `show()` lands before the note area's first layout** — the page size
  then comes from the deferred layout listener rather than from `show()` itself, and a **stored**
  size greater than zero always wins over the area's own measurement whenever one exists
  (`NoteSurface.pageSize()`).
- **The pill toggle's 56 dp view width IS the drawable's geometry** — `toggle_pill.xml`'s 9 dp knob
  insets are computed from that width (`(56 − 44) / 2 = 6 dp` of pill edge, plus 3 dp of pill kept
  visible around the knob). Changing the style's width without changing the drawable's insets (or
  the reverse) misaligns the knob inside the pill.
- **The auto-mode permission classifier can refuse a plain `adb install`** — hit three times in one
  Z5b session (plain, via the device-build-install skill, and by absolute path); the fix was the
  user running the install themselves with `!`, not a change to the tree.

## Related

- `docs/scratchpad.md` — the model this doc mirrors; read it first for anything that reads here as
  "the pad's rule."
- `docs/extensions.md` — the seam in full: the calendar's boundary-audit rows, `IExtensionStore`,
  the tier-2 recipe every screen-owning point follows.
- `docs/sn-screen.md` — `:ext-ink`'s dependency shape and the `FloatingSelectionBar`
  generalization; also the icon count (arc 24 added `ic_calendar_event` and `ic_backspace`) and the
  pill toggle / count-latch widgets the editor's row 2 and its dialogs are built from.
- `docs/notebook.md` / `docs/library.md` — `btnCalendar` and the selection toolbar's Calendar button
  in their place among the rest of that screen's chrome.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 23 \"Calendar\"" — the wizard's locked
  decisions, the seam spec, and the Y1–Y4 Outcome records this doc draws its facts from.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 24 \"Events\"" — the events wizard's locked
  decisions, the store/screen/grid specs, and the Z1–Z5b Outcome records § Events (arc 24) above
  draws its facts from.
