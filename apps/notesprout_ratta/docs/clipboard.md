# The clipboard (arcs 7 and 8)

**Copy / cut / paste** on a global clipboard that lives in the index — so a copy survives a
force-stop and travels between notebooks. Two things ride it, one slot at a time:

| Kind | What it is | Entry point | Arc |
|---|---|---|---|
| `"page"` | a whole page and everything on it | the notebook's one-finger long-press sheet | 7 |
| `"objects"` | what a lasso caught — strokes, headings, links, with the links' wrapped children | the selection toolbar's Copy / Cut; a pen tap on bare paper places it | 8 |

Everything from *Where it lives* down to *Reading it* is shared by both. The page half is then
`PageClip` + the sheet; the object half is `ObjectClip` + [*The object clipboard*](#the-object-clipboard-arc-8)
at the bottom.

**Status: arc 7 complete — B1 (core + same-notebook), B2 (cross-notebook), B3 (review + freeze).**
B2 added the two rules a page needs once it lands in a *different* file: the template is deduped
**by content** as well as by id, and an own-notebook link is rewritten to name the notebook it was
copied from. B3's review hardened the edges: the payload cap now respects the cursor window it is
read back through, an unreadable clipboard retires its own row, and every failure path in copy and
paste ends in a dialog rather than a silent no-op.

**Arc 8 status: O1 complete** (the engine tap callback, object copy/cut/paste within a notebook, the
lasso popup, Clear, the icon). O2 adds the cross-notebook link rewrite, the review and the freeze.
Arc 7's `kind` discriminator is what made this cheap: **no format change and no migration** — the
promise, kept.

## Where it lives

| | |
|---|---|
| `data/index/ObjectType.CLIPBOARD` | the row type, `"clipboard"` |
| `data/index/ListIds.CLIPBOARD_ID` | the sentinel `00000000-0000-0000-0000-636c69706264` ("clipbd") |
| `data/clip/ClipEnvelope.kt` | `ClipEnvelope` + `ClipRow` + `ClipHeader` — the payload grammar and its codec |
| `data/clip/ClipStore.kt` | the one index row, read and written |
| `core/SnClipboard.kt` | the process-wide in-memory **header** mirror |
| `notebook/PageClip.kt` | pure capture → envelope, envelope → the rows a paste writes, the cross-notebook link rewrite, and the template content-match rule (`kind = "page"`) |
| `notebook/ObjectClip.kt` (O1) | the same, for a lasso selection (`kind = "objects"`): fresh ids, parent rewiring, the per-type `"order"` rebase, geometry translation |
| `notebook/ObjectPlacement.kt` (O1) | pure placement: payload box + tap (or source origin) + page size → the clamped `dx/dy` |
| `notebook/LassoPopup.kt` (O1) | Paste + Clear under the armed lasso button |
| `NotebookSession.capturePage()` / `pasteAt()` / `resolveTemplate()` | the `.soil` side, pages |
| `NotebookSession.captureObjects()` / `pasteObjects()` (O1) | the `.soil` side, objects |
| `SoilDao.templateDigests` | the blob-free shortlist behind the template dedupe |
| `NotebookActivity` | the sheet, the popup, the flows, the toasts, `Action.PagePasted` / `Action.ObjectsPasted` |

**No new index table.** `notesprout.db` is Room-validated and format-compatible with Paper: a new
`@Entity` changes the identity hash and a Paper index would fail validation (and vice versa). The
clipboard is an **additive row type** at a sentinel id — the arc-5 `naming` precedent. It is
invisible to the library because every listing query is type-filtered. There is no Clear in the UI,
and the row is soft-deleted in exactly one case: `ClipStore.clear()`, the recovery for a payload
that turned out to be unreadable (see *Lifetime*).

**No encryption gate.** og warns that a clipboard "drops protection"; in SN that is vacuous — one
global key, every `.soil` under it, and the index itself encrypted at rest. Recorded, not built.

## The row

| Column | Holds |
|---|---|
| `id` | `ListIds.CLIPBOARD_ID` — one slot, every copy/cut is an upsert over it |
| `type` | `"clipboard"` |
| `name` | the payload kind (`"page"`) |
| `refId` | the source notebook id |
| `flags` | the envelope version |
| `createdAt` / `updatedAt` | when it was copied |
| `blob` | the envelope JSON, UTF-8 |

## The payload

`ClipEnvelope(version, kind, sourceNotebookId, copiedAt, rows)`, kotlinx-serialization JSON.
`rows` are neutral `.soil` rows — the universal row shape, with the blob as **Base64**.

- **`java.util.Base64`, never `android.util.Base64`.** The android class is a stub under
  `unitTests.isReturnDefaultValues`, which would make every JVM codec test lie (the N1
  `StaticLayout` lesson, applied before it could cost anything).
- `createdAt`/`updatedAt`/`deletedAt` do **not** travel — a paste is a new row and stamps its own
  clock, and only live rows are ever captured.
- `kind` is a discriminator, and `rows` is already a set — which is how arc 8 put strokes / headings
  / links on the same clipboard as `kind = "objects"` with **no format change and no migration**.
  The two kinds differ in what a paste *means*, not in what the envelope holds.
- **`decode` never throws** (the `LinkPayload` discipline). Absent, empty, malformed, truncated, or
  written by a *newer* build → the clipboard reads as empty rather than half-applying.
- The byte cap (`MAX_BYTES`, **6 MB**) is enforced on **write and read**. Over-cap on copy is a
  problem dialog and nothing is written, so the previous clipboard stands — never a truncated
  payload.
- **The cap's ceiling is the cursor window, not taste** (B3 review). SQLCipher hands a row back
  through an `android.database.CursorWindow` sized `SQLiteCursor.DEFAULT_CURSOR_WINDOW_SIZE` = **8
  MiB**, so a blob above that can be written and then never read: `SQLiteBlobTooBigException` at
  every paste, on a clipboard the sheet is still advertising, and `runPageOp` would swallow it into a
  tap that does nothing. The cap sits under the window with room for the row around it, pinned by a
  JVM guard test against `ClipEnvelope.CURSOR_WINDOW_BYTES`. `readEnvelope` also guards the read
  itself, so a row a laxer build wrote reads as unusable rather than throwing.

## Reading it

The sheet has to decide **synchronously** whether a Paste row exists, and the payload is megabytes.
So `ObjectDao.clipHeader` is a blob-free projection (`kind`, `sourceNotebookId`, `copiedAt`,
`version`) mirrored in `SnClipboard`, and `clipBlob` is read only when a paste actually happens.

`SnClipboard` is **rehydrated at notebook open, not at process start.** og warms its clipboard in
`Application`; SN cannot, because the index is encrypted and only `BootstrapActivity` opens it — at
`Application.onCreate` there is nothing to read. The notebook screen is the only consumer and always
runs after Bootstrap, so `SnClipboard.ensureLoaded()` in `openSession` covers every route in,
including the unlock route (which never passes through a warm Bootstrap).

**A failed header read does not latch** (B3 review): only a read that answered sets `loaded`, so one
transient index error costs that open rather than hiding a perfectly good clipboard for the whole
process life.

## Capture

`session.capturePage()` reads the page row, its template row, and `liveDescendantIds` — **two levels
deep since arc 6**, so a link's wrapped children ride along — and hands them to `PageClip.capture`.

**Drain the writer first.** A stroke commit still queued on the shared `SoilWriter` would land after
the capture's row read and be silently missing from the copy. `doCopy` calls `store.drain()` before
anything else, exactly as `doDelete` does.

A page with **no content** copies fine — a blank page is a legitimate thing to duplicate, and in B2
it is how a template gets stamped into another notebook. No special case: capture simply yields a
page row, a template row, and zero content rows.

## Apply

`PageClip.plan` is pure (JVM-tested) and turns an envelope into the rows a paste writes.

- **Every pasted row gets a fresh id**, wired through one old→new map, so a link's wrapped children
  re-parent onto the *copied* link and not the original.
- **`"order"` is preserved verbatim** on content. Writing order is load-bearing — recognition reads
  it as a sequence, the composite raster paints in it (the M-arc / N3 lesson). Only the page row's
  own order is rewritten, to the slot it is inserted at.
- Page `width`/`height` come across **verbatim** — ink is never resampled, so a Manta-authored page
  stays its own size inside a Nomad notebook (og's rule).
- A content row whose parent did not travel is **dropped**, not re-parented onto the page: the
  payload is untrusted input like any file, and an orphaned link child re-appearing loose on the page
  would be a silent corruption rather than a visible absence.
- The payload is deliberately **row-level, not object-level**: `PageClip` understands only the page
  row (where the template reference lives) and, across notebooks, a link row's payload. Anything a
  later arc adds to the family table copies without this file learning a single content type.

### Templates

The caller decides, because only it can see what the destination `.soil` already holds
(`NotebookSession.resolveTemplate`), in three tries:

| Choice | When | Effect |
|---|---|---|
| `Reuse(id)` | a row with that id is already in this file — **always** for a same-notebook paste, and for a repeat paste of the same source page | point at it, insert nothing |
| `Reuse(other)` | **B2**: a row here is the same paper under a different id (`PageClip.matchTemplate`) | point at that one, insert nothing |
| `Insert(id)` | the payload carries the template and nothing here matches | bring the row in **under its source id** |
| `None` | the page had no template, or the payload names one it doesn't carry | `refId = ""` |

Inserting under the *source* id is what makes the first dedupe fall out for free: a second paste of
the same source page finds the row and reuses it.

**The same paper (B2)** is the kind label, the page size it was rendered for, and byte-identical
pixels — the same renderer from the same inputs. Anything looser would silently re-paper a pasted
page; anything tighter than identity is guesswork. It matters because two notebooks created with the
same built-in template hold the *same WEBP under different UUIDs*: without the content rule every
notebook pair would stack its own copy. Proven on the Nomad — three pastes of a lined page into a
lined notebook left the `.soil` at exactly its original size.

The read is blob-free first: `SoilDao.templateDigests` projects `id / text / width / height /
length(blob)` so SQLite never materialises a WEBP, and only the rows that could match at all are
loaded whole for the byte compare (the `ClipHeader` discipline, one level down).

### Links across notebooks (B2)

`LinkPayload.KIND_PAGE` carries no notebook id — it means "a page of my own notebook", which is a
*different* page once the row has moved. So on a cross-notebook paste it is re-pointed explicitly:

| Payload | Cross-notebook paste | Why |
|---|---|---|
| `KIND_PAGE` → some other page | → `KIND_NOTEBOOK_PAGE` naming the **source** notebook | the link keeps working *and* keeps meaning what it meant |
| `KIND_PAGE` → **the page being pasted** | stays `KIND_PAGE`, re-pointed at the **new copy** | a page that links to itself still does after the trip |
| `KIND_NOTEBOOK` / `KIND_NOTEBOOK_PAGE` | unchanged | they already name their notebook — including one naming the source page: it meant *that* page in *that* notebook, and the original is still there |
| anything that does not decode | **verbatim** | rewriting what we cannot read would be inventing a target; a follow already lands in K4's dead-target dialog |

A **same-notebook** paste is verbatim throughout — including a self-link, which keeps pointing at the
original page because the original is still right there. (The asymmetry is deliberate: the rewrite
only fires where leaving the payload alone would change what it resolves to.)

An envelope with a **blank** `sourceNotebookId` also leaves own-notebook links alone — there is no
notebook id to name.

The source notebook being **deleted or renamed** between copy and paste changes nothing: the payload
is self-contained, and only a rewritten link target resolves dead — into K4's dialog.

## Undo

`Action.PagePasted` carries the same `NotebookSession.Structural` snapshot as `Action.Page` and
replays through the same `reconcile` — but it is **its own kind because `objectIds` runs the
opposite direction**: a delete's are rows to put *back* on undo, a paste's are rows to take *away*.
Folding the two into one arm would restore what the paste created.

A template row the paste inserted is **left in place** on undo — harmless, and the next paste's
dedupe reuses it. It is deliberately not in the snapshot.

**Cut** is a copy followed by the ordinary `deleteCurrent()`, recorded as a plain `Action.Page`, so
undo puts the page *and* its ink back exactly as Delete page would. Cutting the only page leaves the
fresh blank replacement behind. Cross-notebook *move* falls out of cut + paste with no move engine
at all.

The undo stack is **per-notebook and cleared on close**: a cut in A pasted into B is two independent
stacks. Recorded, not fixed — the sticky clipboard is the recovery for a cut whose source notebook
has since been closed. Paste again.

## The sheet

`showPageSheet` (the one-finger long-press): **Copy page · Cut page · Paste page · Delete page**.

- Paste is **absent, never disabled**, when the clipboard holds no page — a greyed control is
  invisible on e-ink (the standing rule), and a sheet whose row count *is* its content can simply be
  one row shorter.
- Paste opens a second sheet: **Paste before this page / Paste after this page**. It rides the page
  sheet's frame-silence exception rather than opening a new one — it is raised by a tap on a row of a
  dialog that is already up, so the pen is demonstrably idle.
- Toast-confirms / dialog-explains: Copy → "Page copied", Cut → "Page cut", Paste → "Pasted after
  page 3" (the placement is what you might have mis-tapped, so the toast names it). The toast is the
  same across notebooks — the source notebook is something you already know, and its name would put
  an unbounded string in an e-ink toast. Anything that *didn't* work — an unreadable page, an
  over-cap payload, a failed write, an unusable clipboard — is a problem dialog.
- **The toast's number is the anchor's *post*-paste number** (`PageMath.anchorNumberAfterPaste`,
  JVM-tested; B3 review fixed an off-by-one). The toast is read against the page indicator, which by
  then shows the new numbering: pasting before page 3 makes the *pasted* page 3 and the anchor 4, so
  the toast says "Pasted before page 4". Naming the pre-paste number pointed the sentence at the page
  it had just created.
- `doPaste` rejects a foreign payload itself — no envelope, a kind that isn't `page`, or one
  *claiming* a page it does not carry — and both clears the header **and retires the index row**
  (B3 review: clearing only the in-memory mirror let `ensureLoaded` read the still-valid header back
  at the next notebook open and fail again, forever). `pasteAt`'s throw for a page-less payload is a
  caller-bug assertion, and `runPageOp`'s `runCatching` would turn it into a **silent** no-op, which
  is the one thing the sheet must never do.
- `doCopy` wraps the capture and the write for the same reason: a full disk or an index IO error
  would otherwise be a tap that did nothing, while a *stale* clipboard stood ready to paste the wrong
  page. The message distinguishes over-cap from a failed write.
- Icons are Tabler `copy` / `cut` / `clipboard`.
- A paste lands you **on** the pasted page.

## Lifetime

**Sticky, single slot.** A paste leaves the clipboard loaded (paste the same page into several
notebooks); it is replaced only by the next Copy/Cut, and it survives a force-stop because it lives
in the index. **No Clear UI** this arc.

The one thing that empties it is `ClipStore.clear()`, called only when a paste discovers the payload
is unusable: the row is soft-deleted **and its blob nulled**, so a dead payload neither advertises a
Paste that can only fail nor keeps costing megabytes in the index. The next copy upserts the row back
whole.

The clipboard is a **snapshot taken at copy time**: editing or deleting the source page — or its
whole notebook — afterwards changes nothing about what pastes.

## The object clipboard (arc 8)

What a lasso caught, on the same row, the same envelope and the same 6 MB cap. **One slot, kind
wins:** a copy of either kind replaces the other, which is why each surface offers only its own —
Paste leaves the page sheet while objects are held, and the popup is absent entirely while a page
is. No surface can ever advertise a Paste for a payload that is no longer there.

### Copy and Cut

The selection toolbar grows **Copy** and **Cut**, offered in **every** mode — ink, a lone heading, a
lone link, mixed, mixed-with-link. A link copies **whole**, wrapped children included: nothing ever
reaches inside one (the K1 model).

Three orderings carry the flow, and each is a bug that was designed out rather than found:

1. **Drain first** — arc 7's trap, unchanged: a stroke commit still queued on the shared writer
   would land after the capture's row read and be silently missing from the copy.
2. **Write, then delete.** A cut whose clipboard write failed must not delete, or the user is left
   with neither the ink nor a clipboard holding it. Cut is a copy followed by the ordinary
   `deleteSelection`, so it records the same single `Action.Deleted` the bar's own Delete does.
3. **Re-arm `Tool.LASSO` afterwards.** Dismissing a selection ends the smart-lasso session and
   restores `Tool.PEN` (g-paper's documented behaviour), so without this the placement tap that
   follows a copy would **ink the page**. A host-initiated tool change ends the session cleanly and
   is never echoed as `onToolChanged`, which is why the button is synced by hand.

### Paste: a pen tap on bare paper

The placement gesture is og's, and it needed **g-paper 0.1.5**:
`PaperListener.onPaperTapped(x, y)` — a sub-threshold **stylus** tap on bare paper while
`tool == LASSO` and **nothing is selected**. Never a finger, never a palm, never a tap inside a
selection box (that is `onSelectionTapped`), and never the tap that *dismissed* a selection — a
contact spent on a dismissal is spent, and the user taps again.

The host applies the same rule one level up: `tapDismissedPopup`, rewritten at **every pointer
going down** in `dispatchTouchEvent`, keeps the contact that closed the popup from also pasting.
Rewritten every time rather than latched once, because a latch set at down goes stale the moment the
contact turns out to be a stroke rather than a tap.

**Every pointer, not just the first** (O2 review). `ACTION_DOWN` alone is the *first* contact of a
gesture; with a hand resting on the glass — the normal writing posture — the palm lands first and
the pen arrives as `ACTION_POINTER_DOWN`. A latch written only at `ACTION_DOWN` would still be
carrying the palm's answer, so if the palm had dismissed the popup, **every** pen tap until the hand
lifted would silently decline to paste, reading as tap-to-place being broken outright. The dismissal
now reads `ev.actionIndex`, so it always answers for the pointer that is actually going down.

| Where it lands | |
|---|---|
| Pen tap | **centred on the tap** — a tap is an aim, not a corner |
| The popup's Paste | **source coordinates** — no tap to aim at, so pasting into the same (or a same-size) page reproduces the original layout exactly |

Both then **clamp** onto the page, silently: no toast for a placement the user is about to watch
land. Content larger than the page on an axis pastes from that edge rather than being centred into
equal overflow on both sides. The box the clamp works from is the **ink extent** — a g-paper
`Stroke.bounds` is point-tight (the K2 trap), so it is grown by half the stroke width first, or a
clamped paste shears half a nib off the page edge.

The pasted set lands **selected**, bar up, so the pen can drag it straight into place.

### What `ObjectClip` does differently from `PageClip`

Both share the two rules everything rests on — every pasted row gets a fresh id through one old→new
map, and a row whose parent did not travel is **dropped**, never re-parented onto the page. Three
things differ, each because a page paste owns a whole self-contained row set while an object paste
lands *among* rows that are already there:

1. **`"order"` is rebased, not verbatim.** Pasted rows go after the destination page's current
   `MAX("order")` **for their own type** (the family numbers per parent *and* type), keeping their
   relative sequence — writing order is load-bearing, so the sequence survives even though the
   numbers do not. A link's wrapped children keep their orders verbatim: their parent is a
   brand-new row with nothing to collide with. The bases are read **inside** the paste transaction,
   or two pastes racing would both read the same max.
2. **Geometry is object-level.** A `stroke` row carries no `x/y/width/height` — its geometry is
   entirely inside the format-B blob — so translating one is decode → `Stroke.translated` →
   re-encode. A `heading`/`link` row is the opposite: bounds *are* columns. A link's wrapped
   children are page-absolute and translate with it.
3. **The source page id is inferred, not carried** (`ObjectClip.sourcePageOf`). `PageClip` tells a
   top-level row from a link's orphan by the page row it carries; an objects payload has none, and
   arc 7's promise was *no format change*. One selection lives on one page, so every top-level row
   shares one parent that is not itself in the payload — and a row parented to some *other* absent
   id is an orphan and is dropped. A link parented to a link is dropped too: no-nesting is a locked
   rule this build will not reproduce, even from a foreign payload.

   Two signals name that parent, in order, and both are things the format guarantees: **a `link`
   row's parent** (a link is top-level by definition, so it names the page outright), else **the
   first row parented outside the payload** (`capture` writes `top + children`). It was a
   **majority vote** until the O2 review, and a vote is a rule that can invert itself: rows
   `[stroke → page, childA → lnk-1, childB → lnk-1]` with the link row missing put the *orphans* in
   the majority, so they would have been written loose onto the page — the untrusted-payload rule
   exactly backwards — while the one genuine top-level row was dropped. **Standing trap: when a rule
   exists to reject the malformed case, never let the malformed case outvote it.**

### Links across notebooks, for objects (O2)

The rewrite above, minus its one exception. A link copied out of notebook A and pasted into B is the
same problem the page half solved: `LinkPayload.KIND_PAGE` carries no notebook id, so left alone it
would mean *a page of B* — almost always no page at all.

| Payload | Cross-notebook paste | Why |
|---|---|---|
| `KIND_PAGE` | → `KIND_NOTEBOOK_PAGE` naming the **source** notebook | the link keeps working and keeps meaning what it meant |
| `KIND_NOTEBOOK` / `KIND_NOTEBOOK_PAGE` | unchanged | already named their notebook |
| anything that does not decode | **verbatim** | rewriting what we cannot read is inventing a target |
| same-notebook paste, any payload | **verbatim** | nothing resolves differently |
| blank `sourceNotebookId` | **verbatim** | no notebook id to name |

**There is no self-page exception here.** `PageClip` has one — a link whose target *is* the page
being pasted re-points at the new copy — but **no page travels in an objects payload**, so there is
nothing for such a link to re-point at. A link to its own source page crosses like any other, back
to that page in A, which is exactly where the page it named still is. That is the whole diff between
the two rewrites, and it is why they are two functions rather than one shared with a nullable
`newPageId`.

The rewrite fires on the **top-level** link rows only. A link's wrapped children are strokes and
headings — a link inside a link is refused outright — so there is no second level to walk.

The source notebook being **deleted** between copy and paste changes nothing: the payload is
self-contained and the source `.soil` is never reopened. The rewritten target simply resolves dead,
into the same K4 dialog a link to a deleted notebook has always landed in. Same for a cut — the ink
is on the clipboard, and pasting it into B is the whole of a cross-notebook move.

JVM-tested as a table (`ObjectClipTest` § links across notebooks), because **adb cannot lasso**: the
device half of this is eye-check only.

### The lasso popup, Clear, and the icon

Tap-to-place is invisible, so the button that means "the clipboard is in play" carries both the hint
and the two acts that have no gesture:

- **The icon.** While objects are held, the armed lasso button wears `ic_lasso_clipboard` — og's own
  icon, a plus crosshair in the middle of the loop. It is the **only** standing hint that a pen tap
  will paste, so it is a state of the button, not a transient toast. (A clipboard badge scaled into
  the corner was tried first and read as a blob at 24 dp on the panel. **Check og's `drawable/`
  before drawing a "fresh Tabler-style" icon** — the vocabulary is largely already there.)
- **The popup.** A second tap on the **already-armed** lasso opens a small bordered bar hung under
  it (`SelectionAnchor.placeUnder` — no flip; the anchor is in the top bar, so below is always the
  free side): **Paste** and **Clear**, icon-only with long-press hints. It opens **only while the
  clipboard holds objects** — with a page loaded, or nothing, a second tap stays P1's silent no-op.
  That is the same rule as the page sheet's absent Paste row: absent beats open-and-half-empty, and
  a greyed control is invisible on e-ink anyway. Its rect joins `pushExclusions` and `overChrome`;
  the screen owns every dismissal (tool switch, page swap, any outside contact, paste, clear).
- **Clear** empties the clipboard in memory **and** retires the index row — clearing only the mirror
  lasts until the next `ensureLoaded` (the B3 lesson). Toast-confirms; the popup goes and the icon
  reverts.

### Toasts, dialogs, undo

Toast-confirms "Copied" · "Cut" · "Pasted" · "Clipboard cleared". Dialog-explains an over-cap
payload, a failed clipboard write (copy **and** cut), an unreadable or foreign payload at paste, and
a cut whose page moved under the capture. **Never a silent no-op** — with one deliberate exception:
a pen tap on bare paper while the clipboard holds a *page* does nothing at all, because neither the
icon nor the popup was offering a paste for that tap to fail at.

`Action.ObjectsPasted` is `Action.Deleted` run in reverse: undo soft-deletes the pasted rows (a link
whole, wrapped children and all), redo restores them in place — geometry and rebased `"order"`
intact, because a soft-deleted row keeps everything.

One asymmetry worth knowing: a paste that **decoded but carried nothing placeable** retires the
clipboard row, because it can only ever fail again — but a paste whose *write threw* (a full disk,
an IO error) does not. That is this attempt failing, and throwing the clipboard away over it would
turn a retry into a loss.

## Standing traps

- Drain the writer before capture.
- `java.util.Base64`, never `android.util.Base64`.
- Preserve `"order"`; never re-sequence content.
- `ObjectEntity.name` is non-null — the kind label fills it.
- Content is **two levels deep** since arc 6: `liveDescendantIds`, not `liveContentIds`.
- One `.soil` never has two connections: a paste writes through the **open session**, never a second
  open of the destination file. Everything a cross-notebook paste needs is in the payload — the
  source file is never reopened, which is also why deleting it changes nothing.
- Match a template on **content**, never on the kind label alone: two notebooks can carry the same
  label at different page sizes.
- **A blob you can write is not a blob you can read.** Anything stored in the index whole is read
  back through an 8 MiB cursor window; size the cap against the window, not against what SQLite will
  accept on the way in.
- **Clearing the in-memory mirror is not clearing the clipboard.** `SnClipboard.set(null)` lasts
  until the next `ensureLoaded`; a payload that is permanently unusable has to lose its row too.
- adb can drive the whole sheet (it is finger-injectable) but **not** undo/redo — those are
  multi-finger stationary double-taps, which `input` cannot inject. Paste/cut undo is eye-check only.
  Nor can it lasso, so **every link case is eye-check only** — the rewrite table is JVM-tested
  instead.
- A page sheet that is up has `releaseRender()`'d the surface, so a screencap taken while it is
  showing can be missing committed ink that is plainly there once the sheet closes. Dismiss before
  judging a page's content from a screenshot.
- **A dismissal is a spent contact.** Both g-paper (`outlineDismissedSelection`) and the host
  (`tapDismissedPopup`) refuse to let the tap that closed something also do something. Rewrite such
  a latch at every down — one set once goes stale the moment the contact becomes a stroke.
- **`Stroke.bounds` is point-tight.** Any box used to place or clamp ink must be grown by half the
  stroke width first.
- **`"order"` is per parent AND type.** An object paste rebases three maxes, not one, and reads them
  inside its own transaction.
- **The lasso button must be excluded from its own popup's outside-tap dismissal**, or its re-tap
  closes the popup in `dispatchTouchEvent` and immediately reopens it in `NotebookToolbar`. The same
  trap has a second mouth inside the toolbar: `onToolTap` fires `onToolTapped` — which is what takes
  the popup down when *another* tool is armed — **only on an actual tool change**. Firing it before
  the already-armed check hid the popup a moment before `onLassoReTap` asked whether it was showing,
  so the toggle reopened what it meant to close, every time (O2 review). Two handlers reading one
  piece of state: order the write after the read.
- **A latch keyed on `ACTION_DOWN` answers for the first contact only.** With a hand resting, the
  pen is `ACTION_POINTER_DOWN` — read `ev.actionIndex`.
- adb can neither lasso nor ink, so **copy, cut, the placement tap, the popup and the icon swap are
  all eye-check only**. The pure halves (`ObjectClip`, `ObjectPlacement`, the anchor) are JVM-tested
  instead; the one thing adb *can* reach is a second tap on the armed lasso with an empty clipboard,
  which must leave the lasso armed and put no popup node in the dump.
