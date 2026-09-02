# The Scratch Pad (arc 11)

A second sheet of paper, always the same one, one tap away from the library or from a notebook —
and **the first thing SN does not own**. The pad is an extension APK (`NSE · Scratch Pad`,
`:ext-scratchpad`) with its own process, its own g-paper surface and its own undo stack. The core
grows no second drawing surface; what it grows is a button and a contract.

This is the pad's own reference. The seam it rides on — the AIDL, the held bind, the extension
store, trust — is [`docs/extensions.md`](extensions.md); the parts of the screen it borrows are
[`docs/sn-screen.md`](sn-screen.md); the notebook it talks to is [`docs/notebook.md`](notebook.md).

**Status: arc 11 complete** — J1 `:sn-screen` · J2 the store · J3 the point · J4 the screen +
both entry buttons · J5 the two transfers · J6 review, docs, freeze. **Grown by arc 22 / X2**
(2026-09-01): the store moved from a per-package key/value blob to real SQLite tables — the pad's
pages have no size ceiling any more. See § Pages and the store. **Grown again by arc 23 / Y1**
(2026-09-02): the pad's ink/store helpers moved into the new shared library `:ext-ink`, so the
calendar (`docs/calendar.md`) is not a sibling copy of the pad. See § Shared with the calendar
below and the "Where the code is" table. **Grown once more by arc 23 / Y4** (2026-09-02, the
code-review fix): the pad's screen, service and session moved into `:ext-ink` too —
`InkScreenActivity`, `InkTransferSession` and `InkSql` — closing the second sibling-copy the Y1
move had left below the store line. See § The screen, § Pages and the store, § The transfers and
§ Frame silence below.

## Why an extension at all

The pad *could* have been a second Activity in `:app`. It is not, on the user's explicit call — and
that call is the "fresh user decision" the arc-3 extension rule demands before SN may hold a second
capability point. What it bought:

- The core keeps **one** drawing surface. A pad inside `:app` would have been the `RattaNotebookView`
  sibling-copy trap in a new costume: two screens drifting apart on lasso, erase and gesture logic.
- The seam is **proven under load**. A screen-owning point is the hardest thing the extension system
  can be asked to do — an exported Activity, a bind held across a whole showing, ink crossing in
  both directions, and two EPD pipelines swapping hands. It works, and everything after it is easier.
- The pad **writes nothing to disk**. Its pages live in the host's encrypted per-package store,
  lent for the showing and revoked with the unbind.

Two structural moves rode along: `:sn-screen` (J1), the shared paper-screen library both surfaces
build from; and the full extension store (J2) — encrypted per-package SQLite behind gated
parameterized SQL since arc 22 / X1, with ashmem still carrying a chunk over the inline cap.

### Shared with the calendar since arc 23 / Y1 (`:ext-ink`)

Arc 23's calendar (`docs/calendar.md`) needed the same thing the pad already had: a page of ink
that is rows in the host's extension store, an op log that flushes to it, a planned stroke read
that never meets `STORE_RESULT_LARGE`, and a stroke-level undo. Building that twice would have
been the `RattaNotebookView` sibling-copy trap in a new costume — one drawing surface's fix
forgotten in the other's copy — so Y1 pulled the guts of `ScratchInk` / `StrokeRows` /
`ScratchBatches` / `ScratchReadPlan` / `ScratchDocument` / the stroke-level half of `ScratchUndo`
out into a new library module, `:ext-ink` (`:extension-api` + `:sn-screen`, never `:app`; no
manifest components), under neutral names, and repointed the pad onto it. One copy, no drift: a
fix to the batch split, the read plan or the re-flush rule now lands for both extensions at once.

The move changed constructor names and nothing else. The pad's JVM tests pass unchanged except for
those names (`ScratchAction.Drew` → `InkAction.Drew`, and so on), and the pad's Nomad walk was
re-run whole at Y1 (open, ink, flip, send both ways) rather than assumed safe from the JVM tests
alone — it came back clean, including the pad's page-full ceiling having stayed gone.

## The screen

`ScratchPadActivity` (`:ext-scratchpad`) is the notebook's shape, built from `:sn-screen`:
full-bleed g-paper, two thin chrome bars, `PageGestures` for the finger vocabulary, `PaperChrome`
for the exclusion rects, `UndoRedoStack` for the history, `SelectionAnchor` for the floating bar.
**Since arc 23 / Y4 the shape itself is `:ext-ink`'s `InkScreenActivity`** — the page-op lock, the
undo/redo replay, the bounded-debounce-vs-unbounded-leave flush, the EPD handoff order and the
chrome-tap `releaseRender`, one abstract class rather than a recipe the pad's and the calendar's
screens each re-typed. `ScratchPadActivity` keeps only what is its own: the page list and the
pager, the inserts and the delete confirm, and the head of `consumeReceived`.

| | |
|---|---|
| Top bar | Back · Pen · Eraser · Lasso · "Scratch Pad" (centred on the screen) · **Send** (only with a notebook behind it) |
| Bottom bar | ← · page indicator · → , centred on the screen |
| Tools | **Fixed and they are the notebook's**: PEN, black, the notebook's pen width; the notebook's eraser radius. No panels, no colour, nothing remembered — a pad that lassoed differently one tap from the notebook would read as a bug. |
| Gestures | The notebook's, minus what the pad has no use for: 1-finger horizontal swipe = flip (past the last page, insert one) · 2-finger horizontal swipe = insert before / after · 2-finger stationary double-tap = undo · 3-finger = redo · 1-finger long-press = ask to delete this page. No link follow, no trail walk-back, no Contents, no Recents. |
| Selection | Smart lasso + scribble erase, armed before the listener attaches. The floating bar is Send selection (with a notebook behind) then Delete — Delete last, as on the notebook's bar. |
| Undo | Pad-level and **in memory**: it survives page turns and dies with the screen. |

**The caller check is the first statement in `onCreate`**, before anything is inflated. The screen is
exported (the host launches it by action) and only a `startActivityForResult` from the host package
with a matching signature gets in — a plain `am start` has a null `callingPackage` and is refused.
Verified on the Nomad every phase: `refused caller (none)`.

### The EPD handoff — the arc's headline risk

Two paper surfaces in two processes, one firmware ink pipeline. The ordering is load-bearing:

```
notebook: releaseForHandoff()  →  launch
pad:      onResume → resumeDrawing()
pad:      every exit → finishWithHandoff() = releaseForHandoff() then finish()
notebook: onResume → resumeDrawing()          ← lands BEFORE the pad's window closes
```

The departing window's close must land *after* the caller's reclaim; g-paper's ownership guards are
process-local statics, so the departing side's release has to be its full teardown. It worked both
ways on the Nomad first try, on Back and on a Send exit alike, and needed **no g-paper change** —
the pin stayed 0.1.6. A failure here goes to g-paper, never to a host workaround.

**Back awaits the flush.** The host's result callback runs `end()` → unbind → revoke the moment the
pad finishes, so a save still in flight would hit a revoked binder. The exit flushes under the
page-op lock first, and only then hands off and finishes.

## Pages and the store

The pad's pages are rows in the host's store for `com.…notesproutsn.ext.scratchpad` (arc 22 / X2 —
arc 11's flat `pages` / `current` / `page/<id>` key-value layout is gone entirely). `ScratchStore`
extends `:ext-ink`'s abstract `InkStore` base (arc 23 / Y1) for the shape common to any ink-on-rows
store — `run` / `compensated` / `guard` / the planned stroke read — and keeps its own `ScratchSchema`
and `ScratchSql`, pinned by `ScratchSqlTest`, since a consumer's table shapes stay its own. **Since
arc 23 / Y4 the `stroke` table and its six statements are `:ext-ink`'s `InkSql`** —
`ScratchSchema.V1` lists `InkSql.CREATE_STROKE_TABLE` / `CREATE_STROKE_INDEX` among its own steps,
and `ScratchSql : InkDocument.StrokeSql by InkSql` delegates the two writes and forwards the three
reads — one declaration for both consumers, byte-identical to what this file used to spell out
itself (`InkSqlTest` pins it from the shared side; `ScratchSqlTest` still pins it from the pad's).
`ScratchSchema.V1`:

```sql
CREATE TABLE page   (id TEXT PRIMARY KEY, position INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL,
                     createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL);
CREATE INDEX page_position ON page(position);            -- non-unique: renumbering shifts in place
CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                     "order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL,
                     blob BLOB NOT NULL);                 -- blob = StrokeCodec format B, unchanged
CREATE INDEX stroke_page_order ON stroke(pageId, "order");
CREATE TABLE state  (key TEXT PRIMARY KEY, value TEXT NOT NULL);   -- 'current' → page id
```

`stroke.blob` is exactly the `.soil`'s own stroke geometry (`StrokeCodec` format B) — arc 11's
whole-page blob is gone, but the bytes inside one stroke never changed. `stroke."order"` is the
writing order within its page, and it is what an undo/redo cycle restores a stroke to. First run
applies `ScratchSchema.V1` (idempotent — a no-op is one host-side `SELECT`) and, finding no page
rows at all, mints one blank page and names it current, in one batch.

**Two write ops, both idempotent**, because a batch that fails part-way is retried by whatever
caller owns it and the retry has to converge:

- a stroke row is `INSERT OR REPLACE` (a stroke has no children, so REPLACE is safe) and removed
  with `DELETE … WHERE id = ?` (a row that is not there is not an error);
- a page row is `INSERT OR IGNORE` then `UPDATE`d, and its position is renumbered per id (page
  counts are tens, so every position is rewritten rather than shifted in place). **Never
  `INSERT OR REPLACE INTO page`** — REPLACE deletes the conflicting row first, and with
  `foreign_keys` ON that delete CASCADEs, taking the page's strokes with it;
- `state('current')` is `INSERT OR REPLACE`.

Every one of these strings lives in `ScratchSql`, pinned by `ScratchSqlTest` (exact SQL text and
bound args).

**The op log.** `:ext-ink`'s `InkDocument` (the pad's `ScratchDocument` before Y1) keeps the current
page as a `TreeMap<order, Stroke>` plus a `LinkedHashMap<id, Op>` op log — one entry per touched
stroke id, `Put(stroke, order)` or `Drop`. Coalescing is the map itself: a second edit to the same
stroke overwrites the first entry rather than queuing a second one, and a flush is one statement per
touched stroke rather than a re-encode of the page. An erase is always a `Drop`, never "forget the
`Put`" — a `Put` may be a move of a row already stored, and dropping the entry outright would leave
that row behind. `flushUntilClean` snapshots the log and clears it **before** the IO hop (so a
stroke committed mid-write re-dirties the page and takes another pass, rather than being silently
lost) and, on a failed write, merges the snapshot back **under** anything recorded since — a newer
entry for the same id wins because it already describes the row's latest state — then rethrows.
The loop is **bounded only for the debounced save** (`MAX_FLUSH_PASSES` 8, passed by `saveRunnable`
alone — what it leaves behind, the next debounce picks up, and it answers `false` to say so); every
**leave** path — a page flip, a structural edit, `onPause`, the exit — flushes `UNBOUNDED` (the
default) until the page is clean, because after the swap's `reset` there is no next debounce to
leave anything to (arc 23 / Y4's review: a shared bound could drop strokes on a flip under a hand
that kept writing). The loop cannot spin: the only writer is the pen, and it ends when the pen pauses.

`ScratchDocument` itself is now thin over `InkDocument`: it owns *which* page is showing, the page
list and its structural edits, and the page's size, and delegates what is on the page to
`InkDocument`. The page size is the pad's own unwritten extra — `InkDocument.flushUntilClean
(extraDirty, exec)` takes a `sizeDirty` flag as `extraDirty` and lets the consumer prepend its own
`sizePage` statement inside `exec`, restoring its flag if that write throws. **Since Y4
`ScratchDocument` also implements `:ext-ink`'s `InkPage` contract** (`pageId` / `strokes` /
`pageWidth` / `pageHeight` / `addStroke` / `erase` / `move` / `flushUntilClean`) — the one interface
`InkScreenActivity` needs of a consumer's document — and `currentPageId` was renamed to `pageId` to
match it.

Orders are a **high-water mark** (`highWater`), never the map's last key — a Fable-review finding:
erasing the tail stroke lowers the last key, so handing out `lastKey + 1` again would give an
erased stroke's order to the next one drawn, and restoring the erased stroke via undo would then
collide with it. `highWater` only ever rises while the page is loaded.

**Batching.** A write is split into ≤ `STORE_MAX_VALUE_BYTES` (4 MiB) / ≤
`STORE_MAX_BATCH_STATEMENTS` (10 000)-statement `exec` batches (`:ext-ink`'s `StoreBatches`,
the pad's `ScratchBatches` before Y1, measuring each
statement with `StoreCodec.statementBytes` so what is measured is exactly what the payload will
weigh). One batch is one transaction and therefore atomic — every ordinary flush, page operation
and placement under the cap lands as one. Past it the write is several transactions run in order,
and the caller's retry is what closes the gap — which is exactly why every statement above is
idempotent. `receive` (the notebook → pad placement, § The transfers) **compensates** a multi-batch
failure before throwing `StoreUnavailable`: a new page's statements are undone with
`DELETE FROM page` (the declared cascade) plus the old positions restored; a placement onto the
current page is undone with one `dropStroke` per minted id — never an `IN (…)` list, because of the
999-argument cap (`STORE_MAX_ARGS`).

**Reads are planned, never refused.** `readPage` first reads the small index — `SELECT "order",
LENGTH(blob)` for every stroke on the page — then `:ext-ink`'s `StrokeReadPlan.ranges` (the pad's
`ScratchReadPlan` before Y1) packs consecutive strokes into ranges that fit under the 4 MiB budget
(`ROW_OVERHEAD` 128, an over-estimate for the id/order/colour/width/style cells and their tags),
and each range is one `BETWEEN` query run through `StoreReads.all` — both from `InkStore.readStrokes`,
the abstract base both the pad's and the calendar's stores extend for the run/compensated/guard/
planned-stroke-read shape. A page of any size comes back this way and never meets
`STORE_RESULT_LARGE`. `StrokeRows.decode` (also moved to `:ext-ink`, same name): a malformed row —
bad geometry, a cell of the wrong storage class, a stroke with no points — is a **dropped stroke,
never a lost page**: it is skipped and counted (`Log.w`), and the rest of the page loads.

**The ceiling is gone.** Arc 11's `PageFullException`, `SCRATCH_PAGE_FULL`, the "Page is full" /
"Page unreadable" / "Scratch page is full" strings, `Add`, `refuse()` and `ScratchPageCodec` are all
deleted — a page is rows now, and the only failure left is the store being unreachable at all (any
exception at all becomes `:ext-ink`'s `StoreUnavailable`, which is what the screen and the service
both answer to; `PageInk` — a page's size and strokes as stored — moved to `:ext-ink` alongside it,
since the calendar's store hands the same shape back). The proof is structural (no byte cap
anywhere in the write path, keyset/ranged reads) plus the JVM split/plan tests; the phase's user
checklist included an on-device single-page stress well past the old 4 MiB mark, and it was
**skipped by the user** — said here honestly rather than claimed as verified on the Nomad.

**The legacy wipe.** A store still shaped like arc 11's (a `kv` table, `PRAGMA user_version` at 1)
is reset the first time this host opens it — `kv` and `room_master_table` dropped, no migration,
logged once as `wiped legacy store for <pkg> (format 1, N kv row(s) dropped)`, never a key. The
file keeps its on-disk size afterwards (freed pages, no `VACUUM` anywhere in this ladder) — a
future compaction question, not this one.

`PLACE_TIMEOUT_MS` stays 10 s: a placement is now inserts, cheaper than the whole-page re-encode it
was originally sized for. Never Main, unchanged — every store call still hops to `Dispatchers.IO`,
except `begin` / `receiveInk`, which run on the Binder thread exactly as before.

Deleting the last page empties it rather than removing it: the pad always has at least one page.

## The transfers

Both directions are **copies**. Both cross **only through the held service** — never the Intent,
never a file. Both carry **no ids** (fresh ones are minted on the receiving side) and keep
**coordinates 1:1**: the pad page and the notebook page are both this device's screen, so a cross-size
page is clipped exactly like any other ink.

### Notebook → pad

1. The selection toolbar's 7th button, **Send to Scratch Pad**, shown only for an ink-only selection.
2. The placement sheet: **New page** / **Current page**.
3. `TransferCaps.withinLimits` — **checked before any bind**, behind the one gate both lasso sends
   pass since arc 23 / Y4, `NotebookActivity.sendSelectionToExtension`: it asks `TransferSelection
   .sendable` for the ink-only, writing-order strokes first, then the caps (over → "Too much to
   send").
4. `open` → `begin(store)` → the chunks over `receiveInk(bundle, placement, last)`, `placement` and
   `last` on every one; the last call carries the whole placement and gets a 10 s budget. These are
   `HeldInkClient`'s calls since Y4 (`ScratchPadClient` is a thin point on it), and the pad gained
   its own `SETTLE_TIMEOUT_MS` with that unification: a timed-out last chunk is settled rather than
   believed a failure, since a Binder call cannot be cancelled and the ink may land anyway (the full
   rule is in [`docs/calendar.md`](calendar.md), where it was first built).
5. **Since Y4 the accumulate-and-place body is `:ext-ink`'s `InkTransferSession.receiveChunk`**,
   shared with the calendar: the running-totals re-check (the untrusted-input half of step 3), the
   one monitor, and — new for the pad — refusing a placement that changes mid-transfer
   (`"placement changed mid-transfer"`, the same text the calendar already threw for its target) are
   all there. `recordInboundPageSize = true` is `ScratchSession`'s one constructor difference from
   the calendar's `false`: the sender's page size is recorded off the first chunk, because a
   placement onto a new page mints that page at the size the ink was authored in. `ScratchPadService
   .receiveInk` supplies only what is left — the placement int's own validity check — and, once
   `receiveChunk` returns non-null, mints fresh ids and places through `ScratchStore.receive` **on
   the Binder thread**: New page inserts after the current one at the recorded size, Current page
   appends keeping its own. The target becomes `current`, so the screen opens on it. The whole
   placement is one statement list: under the batch cap (§ Pages and the store) that is one
   transaction, and the promise "nothing was placed" is the transaction's; past it the batches run
   in order and a failure part-way is **compensated** before `StoreUnavailable` surfaces — the
   arc-11 hand-rolled ink-first/compensating-delete choreography this replaced is gone, because the
   transaction (or the compensation) is now the guarantee.
6. The screen is launched with `EXTRA_SCRATCH_OPEN_RECEIVED` and consumes the record **once**: it
   switches to the **lasso before `setSelection`**, selects what arrived, and records **one** undo
   step. The tool the user had comes back pen-idle at dismissal — unless they picked another one
   meanwhile.

### Pad → notebook

1. The top bar's **Send** is the whole current page; the selection bar's **Send** is the lasso's
   strokes. Both `ic_pencil_down`; both **absent** without a notebook behind the pad. An empty pick
   raises "Nothing to send" — never silence.
2. The page is flushed under the page-op lock first (**the pad keeps its ink** — this is a copy), the
   chunks are parked in `ScratchSession`, and the screen finishes with `RESULT_SCRATCH_SEND`.
3. The host drains `takeOutgoing(i)` on the bind it is **still holding** — `HeldInkClient
   .drainOutgoing`, via `ScratchPadClient`, since Y4 — stopping at the first empty bundle, at the
   summed caps, or at the chunk budget plus one probe past it, so it learns whether anything was
   left behind. The bind is finished **after** the paste, not before it.
4. Every chunk is `requireValid` at unmarshal and then sanitized: unknown style → PEN, width clamped,
   **colour forced opaque black** (SN's ink is fixed black — no colour crosses in).
5. Fresh ids are minted host-side and the strokes are written in one transaction, appended after the
   destination page's current max `"order"` with relative order preserved (the arc-8 rebase rule —
   writing order is load-bearing). One `Action.ObjectsPasted` step, landing **selected** with the
   lasso armed, and the arc-8 "Pasted" toast.
6. Only then `finish()` — `end()`, unbind, revoke.

A paste from the pad lands *selected*, which is exactly the selection the notebook's Pad button
needs — so the whole round trip is finger-drivable.

**The paste-back is one body, shared with the calendar since arc 23 / Y3.** What was `pasteFromPad`
alone is now `NotebookActivity.pasteTransferred(wire, truncated, TransferWording, source)`;
`pasteFromPad` is a one-liner over it with `PAD_WORDING`, and the calendar's `pasteFromCalendar`
is the same one-liner with `CALENDAR_WORDING` — the three strings (the failed-paste dialog body,
the truncated-paste title and body) are the whole of what the two sends differ by. The single
in-flight field that used to be `toolBeforePadPaste` is now `toolBeforeTransferPaste`: only one
transfer can have just landed, so one field does for both. See [`docs/notebook.md`](notebook.md)
§ Send to Calendar and the paste-back for the calendar's half.

### What one placement records on the pad's undo stack

| Placement | Action | Undo | Redo |
|---|---|---|---|
| Current page | `Pasted` | removes exactly what arrived | puts exactly it back |
| New page | `Page` (with `afterInk`, a `PageInk`) | removes the page **with its cargo** | brings the page back **with its ink** |

`ScratchAction.Page` carries the affected page's ink on *each* side of the move, which is what lets
one shape cover three acts — insert (blank both sides), delete (ink on the `before` side) and a
received new page (ink on the `after` side) — without the arc growing a fifth kind.

## Failure table

Every failure is a dialog that says what happened and what is still true. Toasts only confirm.

| What went wrong | Where | What the user sees | State |
|---|---|---|---|
| No trusted pad installed | host | the button is **GONE** (never disabled — invisible on e-ink) | — |
| Open failed (disabled, replaced, store unreadable) | host | "Scratch pad unavailable" | nothing sent, discovery re-runs |
| Selection over the transfer caps | host, **before any bind** | "Too much to send" | nothing sent |
| A placement's batches fail part-way (arc 22 / X2) | pad's store call → host | "Scratch pad unavailable" — the same text as any open failure | **compensated first** (the new page or the minted strokes are undone), then nothing placed; the pad is not opened |
| The drain hit a cap or the chunk budget | host | "Not everything came back" + the pasted count | what came is pasted; the rest is **still on the pad** |
| The drain failed outright, or brought back nothing | host | "Nothing came back" | nothing pasted; the ink is **still on the pad** |
| The paste could not be written | host | "…could not be written. Nothing was changed" | nothing pasted; the ink is still on the pad |
| Send with no ink picked | pad | "Nothing to send" | the pad stays up |
| A stroke row will not decode (arc 22 / X2) | pad, on read | nothing — no dialog | that stroke is **dropped, never surfaced**; counted and logged, the rest of the page loads |
| The store still carries arc 11's key/value shape | host, on first open | nothing — the pad opens as if fresh | **wiped** (`kv` + `room_master_table` dropped, logged as a row count); no migration |
| The store's format is newer than this host writes | host, on open | "Scratch pad unavailable" | **left exactly as found** — never-delete-on-corruption |
| The store binder is gone | pad | "Scratch pad unavailable" | the pad finishes |

## Entry points

| Where | Behaviour |
|---|---|
| Library top bar, last button (arc 23 / Y4 — always the last button on the bar) | opens the pad with **no** Send buttons — there is no notebook to send to |
| Notebook top bar, last button (arc 23 / Y4 — same placement call) | hands the EPD pipeline over first; the pad gets both Send buttons |
| Notebook selection toolbar, 7th button (ink-only) | the outbound transfer above |
| The calendar's own Scratch Pad button (arc 23 / Y4) | a third door the pad never sees as such — the host walks it and walks back: the calendar exits with `RESULT_CALENDAR_OPEN_SCRATCH_PAD`, the host opens the pad exactly as it would from its own button, and reopens the calendar at its bookmark once the pad closes with `RESULT_CANCELED`. See [`docs/calendar.md`](calendar.md) § The two doors + the held bind |

`ScratchPadEntry` serves **both** of its own doors — one class, because everything about them is the same except
the one line that is not (the notebook's `releaseForHandoff()`); since arc 23 / Y4 that shape is
`ExtensionScreenEntry`'s, shared with the calendar, and `ScratchPadEntry` is thin on it. Two
near-identical files would have been the sibling-copy trap `:sn-screen` exists to keep out of this
app — and, before Y4, `ScratchPadEntry` and `CalendarEntry` were exactly that pair, grown apart by
the settle rule until the unification closed them. Whether the pad shows its
Send buttons is a property of the **caller**, not of whether ink was handed over: the notebook's
plain top-bar tap must still come back able to send. The calendar's own door opens the pad through
this same `ScratchPadEntry` too — from the host's side it is an ordinary tap on `btnScratchPad`, the
only thing new is who decided to make it (`onCalendarClosed`, in `LibraryActivity` and
`NotebookActivity` alike) and what happens when the pad closes (`onPadClosed` reopening the
calendar rather than nothing at all).

The button is `GONE` unless a trusted pad is installed, discovery re-runs on every `onResume` **and
after a failed open**, and a busy guard allows one showing at a time. An `OpeningOverlay` goes up at
the tap and the open runs only once its frame is on the glass: a **cold** open measured 3 123 ms on
the Nomad (SQLCipher's KDF creating the store) against 114 ms warm, and a tap with no answer for
three seconds reads as a tap that missed.

## What the pad is not

- **It opens no `.soil`.** It has no notebook, no page rows, no index. Its ink is its own.
- **The notebook is not sealed behind it** — the one way this hop differs from arc 10's notebook
  switch. What the notebook gives up is the *pipeline*, not its data: its session, its undo stack and
  its unsaved page are all still there when the result comes back, which is exactly what the paste
  lands on.
- **It has no clipboard, no headings, no links.** Five undo kinds against the notebook's fourteen.
- **It never writes to disk itself** — no file, no prefs, no second store.

## Frame silence

The pad carries the SN-wide rule (never present an app frame while `paper.isPenActive`) and adds no
new exception: its frames are the notebook's recorded exceptions in scratch-pad form — the delete
confirm at a long-press, the selection bar's show at lasso completion (and its re-anchor after a
move, and its show over a received placement), the "Opening…" box's hide when the page lands, and a
problem dialog at a pen-up or a chrome tap. **Since arc 23 / Y4 the gate itself is `:sn-screen`'s
`PenIdle.whenIdle`** (`InkScreenActivity.whenPenIdle` is the one-line wrapper both screens call) —
one frame-silence gate written once rather than the four copies that had grown across the pad's and
the calendar's toolbars and screens. Host-side, the pad button's overlay rides the C1 exception: the
same act as the Contents and Recents buttons.

## Where the code is

| | |
|---|---|
| `:ext-scratchpad` `ScratchPadActivity` | thin on `:ext-ink`'s `InkScreenActivity` since Y4 — the page list, the pager, inserts, delete confirm, its own `consumeReceived` head |
| `ScratchToolbar` | the chrome, the fixed tools, both Send buttons |
| `ScratchDocument` | which page is showing, the page list and its structural edits, the page's size — thin over `:ext-ink`'s `InkDocument` for what is on the page, and implements `:ext-ink`'s `InkPage` contract since Y4 |
| `ScratchSchema` / `ScratchSql` | the pad's own tables and SQL, pinned by `ScratchSqlTest` — since Y4 the `stroke` table and its six statements are `:ext-ink`'s `InkSql` (`ScratchSql : InkDocument.StrokeSql by InkSql`), byte-identical to what this file used to spell out |
| `ScratchStore` | the pad's table calls (page list, receive/compensate) — extends `:ext-ink`'s `InkStore` base |
| `ScratchPages` | the pure page-list position arithmetic (no longer a storage role) |
| `ScratchPadService` / `ScratchSession` | thin on `:ext-ink`'s `InkTransferSession` since Y4 — `ScratchSession` is an `InkTransferSession<Int, ScratchStore.Received>(recordInboundPageSize = true)`; `ScratchPadService` supplies the placement int's own check, the page-list read at `begin`, and its own log wording |
| `ScratchUndo` (`ScratchAction`) | the pad's own sealed wrapper — `Ink(InkAction)` for the four stroke-level kinds, plus the page-level `Page` action, which stays the pad's |
| `:ext-ink` `InkWire` | wire ⇄ paper on the extension side (arc 11's `ScratchInk`, shared since arc 23 / Y1) |
| `:ext-ink` `InkStore` / `StoreBatches` / `StrokeReadPlan` / `StrokeRows` | the shared store base (run/compensated/guard/planned-stroke-read), batch splitting, ranged-read planning, row → stroke decode |
| `:ext-ink` `InkDocument` / `InkAction` / `StoreUnavailable` / `PageInk` | the shared page-in-memory + op log + `flushUntilClean`, the four stroke-level undo actions, the one store-failure type, the stored-page shape |
| `:ext-ink` `InkSql` / `InkPage` / `InkTransferSession` / `InkScreenActivity` | arc 23 / Y4 — the shared stroke SQL/DDL, the ink-page contract a consumer's document implements, the shared transfer-session base, and the shared tier-2 screen skeleton — one copy for the pad and the calendar |
| `:sn-screen` `FloatingSelectionBar` | the row-of-buttons primitive `InkSelectionBar` places |
| `:sn-screen` `InkSelectionBar` | the ONE Send-then-Delete floating bar (arc 23 / Y4, replacing `ScratchSelectionToolbar` and the calendar's `CalendarSelectionToolbar`), built on `FloatingSelectionBar` |
| `:sn-screen` `PenIdle` | the frame-silence gate (arc 23 / Y4) — `whenIdle` / `releaseRenderIfIdle`, shared by both toolbars and both activities |
| `:extension-api` `IScratchPad.aidl` | `begin` · `receiveInk` · `takeOutgoing` · `end` |
| `WireStroke` / `InkBundle` / `InkChunks` / `ExtensionContract` | the wire types, the chunker, the caps |
| `:app` `HeldInkClient` | the held bind, `open` / `send` / `drainOutgoing` / `finish`, once, shared with the calendar since arc 23 / Y4 — `HeldInkPoint` is the per-point names/budgets interface, `DrainedInk` the one drained-result class |
| `:app` `ScratchPadClient` | thin on `HeldInkClient` since Y4 — its companion `Point` is a `HeldInkPoint<IScratchPad, Int>` naming the pad's two actions, two extras and three budgets (`SETTLE_TIMEOUT_MS` new with the unification) |
| `:app` `TransferCaps` | the host's caps, chunking, sanitize, and its own wire ⇄ paper twin |
| `:app` `ExtensionScreenEntry` | both entry doors, the busy guard, the overlay, both transfers' host half, once, shared with the calendar since Y4 — `InkSend` is the one outbound-ink class, `EntryWording` the four strings |
| `:app` `ScratchPadEntry` | thin on `ExtensionScreenEntry` since Y4 — its registry lookup, its `EntryWording` and its send result code |
| `:app` `TransferSelection` | the pure ink-only, writing-order rule both lasso sends obey (arc 23 / Y4) — `sendable(selection, live)` |
| `:app` `NotebookActivity.sendSelectionToExtension` | the one gate both lasso sends pass (arc 23 / Y4) — `TransferSelection.sendable` then `TransferCaps.withinLimits`, before any bind |
| `:app` `NotebookActivity.pasteTransferred` / `NotebookSession.pasteStrokes` | the placement sheet, the caps gate, the paste-back body shared with the calendar |

### Tests

Arc 23 / Y1 split the JVM tests along the same line as the code: the shared shapes' tests moved to
`:ext-ink/src/test` under their new names — `InkWireTest`, `StoreBatchesTest`, `StrokeReadPlanTest`,
`StrokeRowsTest`, `InkDocumentTest` — and everything that is still the pad's own stayed in
`:ext-scratchpad/src/test`: `ScratchDocumentTest` (the thin wrapper's page-list/size behaviour over
a fake `InkStore`), `ScratchPagesTest`, `ScratchSqlTest` (the pad's SQL strings, untouched), and
`ScratchStoreTest` (`ScratchStore`'s own page-list and receive/compensate behaviour on top of the
`InkStore` base), plus the `FakeScratchStore` test double they share. **Arc 23 / Y4** added two more
to `:ext-ink/src/test` for the second wave of shared code — `InkSqlTest` (the stroke DDL and every
statement, pinned through the host's own validator) and `InkTransferSessionTest` (the
accumulate-and-place body's refusals: over the caps, a placement changed mid-transfer, the store
gone, and the one documented difference, `recordInboundPageSize`) — while `ScratchSqlTest` stays
exactly as it was, now pinning the pad's own delegation rather than its own stroke strings.
