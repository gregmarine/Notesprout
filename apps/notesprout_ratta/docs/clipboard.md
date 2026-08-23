# The page clipboard (arc 7)

Whole-page **copy / cut / paste**, on a global clipboard that lives in the index — so a copy
survives a force-stop and travels between notebooks. Entry point is the notebook's one-finger
long-press sheet.

**Status: B1 (core + same-notebook) and B2 (cross-notebook) landed.** B2 added the two rules a page
needs once it lands in a *different* file: the template is deduped **by content** as well as by id,
and an own-notebook link is rewritten to name the notebook it was copied from.

## Where it lives

| | |
|---|---|
| `data/index/ObjectType.CLIPBOARD` | the row type, `"clipboard"` |
| `data/index/ListIds.CLIPBOARD_ID` | the sentinel `00000000-0000-0000-0000-636c69706264` ("clipbd") |
| `data/clip/ClipEnvelope.kt` | `ClipEnvelope` + `ClipRow` + `ClipHeader` — the payload grammar and its codec |
| `data/clip/ClipStore.kt` | the one index row, read and written |
| `core/SnClipboard.kt` | the process-wide in-memory **header** mirror |
| `notebook/PageClip.kt` | pure capture → envelope, envelope → the rows a paste writes, the cross-notebook link rewrite, and the template content-match rule |
| `NotebookSession.capturePage()` / `pasteAt()` / `resolveTemplate()` | the `.soil` side |
| `SoilDao.templateDigests` | the blob-free shortlist behind the template dedupe |
| `NotebookActivity` | the sheet, the flows, the toasts, `Action.PagePasted` |

**No new index table.** `notesprout.db` is Room-validated and format-compatible with Paper: a new
`@Entity` changes the identity hash and a Paper index would fail validation (and vice versa). The
clipboard is an **additive row type** at a sentinel id — the arc-5 `naming` precedent. It is
invisible to the library because every listing query is type-filtered, and it is never soft-deleted.

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
- `kind` is a discriminator, and `rows` is already a set: a later arc can put strokes / headings /
  links on the same clipboard as `kind = "objects"` with **no format change and no migration**.
- **`decode` never throws** (the `LinkPayload` discipline). Absent, empty, malformed, truncated, or
  written by a *newer* build → the clipboard reads as empty rather than half-applying.
- The byte cap (`MAX_BYTES`, 12 MB) is enforced on **write and read**. Over-cap on copy is a problem
  dialog and nothing is written, so the previous clipboard stands — never a truncated payload.

## Reading it

The sheet has to decide **synchronously** whether a Paste row exists, and the payload is megabytes.
So `ObjectDao.clipHeader` is a blob-free projection (`kind`, `sourceNotebookId`, `copiedAt`,
`version`) mirrored in `SnClipboard`, and `clipBlob` is read only when a paste actually happens.

`SnClipboard` is **rehydrated at notebook open, not at process start.** og warms its clipboard in
`Application`; SN cannot, because the index is encrypted and only `BootstrapActivity` opens it — at
`Application.onCreate` there is nothing to read. The notebook screen is the only consumer and always
runs after Bootstrap, so `SnClipboard.ensureLoaded()` in `openSession` covers every route in,
including the unlock route (which never passes through a warm Bootstrap).

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
  over-cap payload, an unusable clipboard — is a problem dialog.
- `doPaste` rejects a foreign payload itself — no envelope, a kind that isn't `page`, or one
  *claiming* a page it does not carry — and clears the header so the row stops being advertised.
  `pasteAt`'s throw for a page-less payload is a caller-bug assertion, and `runPageOp`'s
  `runCatching` would turn it into a **silent** no-op, which is the one thing the sheet must never
  do.
- Icons are Tabler `copy` / `cut` / `clipboard`.
- A paste lands you **on** the pasted page.

## Lifetime

**Sticky, single slot.** A paste leaves the clipboard loaded (paste the same page into several
notebooks); it is replaced only by the next Copy/Cut, and it survives a force-stop because it lives
in the index. **No Clear UI** this arc.

The clipboard is a **snapshot taken at copy time**: editing or deleting the source page — or its
whole notebook — afterwards changes nothing about what pastes.

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
- adb can drive the whole sheet (it is finger-injectable) but **not** undo/redo — those are
  multi-finger stationary double-taps, which `input` cannot inject. Paste/cut undo is eye-check only.
  Nor can it lasso, so **every link case is eye-check only** — the rewrite table is JVM-tested
  instead.
- A page sheet that is up has `releaseRender()`'d the surface, so a screencap taken while it is
  showing can be missing committed ink that is plainly there once the sheet closes. Dismiss before
  judging a page's content from a screenshot.
