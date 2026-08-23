# The page clipboard (arc 7)

Whole-page **copy / cut / paste**, on a global clipboard that lives in the index — so a copy
survives a force-stop and travels between notebooks. Entry point is the notebook's one-finger
long-press sheet.

**Status: B1 (core + same-notebook) landed.** B2 adds the cross-notebook rules — template dedupe by
content and the `KIND_PAGE` → `KIND_NOTEBOOK_PAGE` link rewrite. A cross-notebook paste *works*
today (the template travels, ids remap, page size is verbatim); what B2 adds is the link rewrite,
so a copied page's own-notebook links keep pointing home.

## Where it lives

| | |
|---|---|
| `data/index/ObjectType.CLIPBOARD` | the row type, `"clipboard"` |
| `data/index/ListIds.CLIPBOARD_ID` | the sentinel `00000000-0000-0000-0000-636c69706264` ("clipbd") |
| `data/clip/ClipEnvelope.kt` | `ClipEnvelope` + `ClipRow` + `ClipHeader` — the payload grammar and its codec |
| `data/clip/ClipStore.kt` | the one index row, read and written |
| `core/SnClipboard.kt` | the process-wide in-memory **header** mirror |
| `notebook/PageClip.kt` | pure capture → envelope, and envelope → the rows a paste writes |
| `NotebookSession.capturePage()` / `pasteAt()` | the `.soil` side |
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
  row (where the template reference lives). Anything a later arc adds to the family table copies
  without this file learning a single content type.

### Templates

The caller decides, because only it can see what the destination `.soil` already holds
(`NotebookSession.resolveTemplate`):

| Choice | When | Effect |
|---|---|---|
| `Reuse(id)` | a row with that id is already in this file — **always** for a same-notebook paste | point at it, insert nothing |
| `Insert(id)` | the payload carries the template and the id is free here | bring the row in **under its own id** |
| `None` | the page had no template, or the payload names one it doesn't carry | `refId = ""` |

Inserting under the *source* id is what makes dedupe fall out for free: a second paste of the same
source page finds the row and reuses it, so repeated pastes never stack identical WEBPs. B2 adds the
content-match rule for a template that is the same paper under a different id.

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
  page 3" (the placement is what you might have mis-tapped, so the toast names it). Anything that
  *didn't* work — an unreadable page, an over-cap payload, an unusable clipboard — is a problem
  dialog.
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
  open of the destination file.
- adb can drive the whole sheet (it is finger-injectable) but **not** undo/redo — those are
  multi-finger stationary double-taps, which `input` cannot inject. Paste/cut undo is eye-check only.
