# Export (arc 15, grown arc 18)

Getting a notebook **out** of the app. The library's notebook long-press sheet grows an
**Export…** row; it opens the host's `ExportActivity`, which discovers whatever exporter
extensions are installed, shows their options with its own e-ink widgets, and hands the actual
file-writing off to the extension over two `ParcelFileDescriptor`s. The host owns everything
that touches a key; the extension only ever streams bytes.

Arc 15 shipped the point with one exporter. Arc 18 shipped a second, real one, and with it the
chooser the screen was always built for: **`NSE · Soil Export`** produces the notebook's `.soil`
file itself, importable into another Ratta-based Notesprout instance (og's
`docs/full-notebook-export.md`, monorepo root, is the reading reference for the filename rule and
the keying shapes; no code was copied, and Paper never built export, so it has nothing to say
here); **`NSE · PDF Export`** produces a full-fidelity PDF of every page, no `.soil` and no key
involved at all — og's `NotebookExporter` (raster: each page to a bitmap, drawn into a
`PdfDocument` page; pdfbox for the password) is the reading reference there, again with no code
copied. The two exporters differ enough — one streams a file verbatim, the other renders and
assembles one — that the seam itself grew a second shape to carry both; see
[The source-kind seam](#the-source-kind-seam-arc-18) below.

This is the feature doc. The seam it rides on — the point, the AIDL, the fd handshake, the
source-kind tail, the export-secret carrier, trust — is [`docs/extensions.md`](extensions.md)
§§ "The exporter point (arc 15)," "The source-kind tail," and "The export secret"; the sheet row
it hangs off is [`docs/library.md`](library.md).

**Status: arc 15 complete** — E1 the point + `NSE · Soil Export` + the screen (Keep path, commit
`c5fb23b`) · E2 the keying transforms (New passphrase · Remove encryption, commit `1860da3`) · E3
review, boundary audit, docs, freeze. **Arc 18 "PDF" complete** — D1 the source-kind seam + host
render pipeline + `NSE · PDF Export` end to end (plain PDF, commit `1844446`) · post-D1 the chooser
defaults to the last-used exporter (`1a18036`) · D2 the two options — page-template toggle +
password protection (`ff71644`) + post-D2 the progress dialog (`57e8413`) · D3 this documentation
pass.

---

## The screen

`ExportActivity` (`IndexGuard`, portrait, e-ink chrome, `TopGuard` 0) receives `EXTRA_NOTEBOOK_ID`
/ `EXTRA_NOTEBOOK_NAME` only — never a `File`. Chrome follows F2: the action button lives at the
top bar's right edge, the bottom of the screen holds nothing.

- **The chooser.** Every trusted exporter is asked to `describe()` itself, and with `NSE · Soil
  Export` and `NSE · PDF Export` both installed the chooser finally does the job it was built for
  (arc 18): a radio per format, in discovery order — `PackageManager`'s, which means nothing, so
  the *order* was never the thing worth fixing. With exactly one exporter installed the chooser
  still collapses to a plain label naming it — no radio for a choice that does not exist. The same
  collapse rule applies one level down: a single-choice option with exactly one declared choice
  (`ExportOptions.isFixed`) renders as a fixed value line, not a one-item radio group.
- **The default pick is the last-used exporter**, not the first one discovery happens to return
  (`data/prefs/ExportPrefs`, `sn_export`, the user's post-D1 call, `1a18036`). "Used," not merely
  tapped: `exportPrefs.lastExporter` is written only from the **OK verdict** — a pick abandoned at
  the SAF picker never becomes the default, only an export that actually finished does. It is
  re-matched against what discovery just found (a package name, never a format label), so a
  remembered exporter that has since gone falls back to the first listed; a standing in-screen pick
  made this session still wins over both. Radio *order* stays discovery order — only which one
  starts checked moved.
- **The options panel**, built entirely from the chosen exporter's `OptionDescriptor` list, in code
  (`ExportPanel`) rather than a layout file — the row *count* is the content. Single-choice renders
  as a radio group under a caption, toggle as a ticked row; nothing else reaches the panel, because
  a descriptor declaring any other kind dropped that exporter at discovery.
- **The keying trio**, declared by `NSE · Soil Export` as the reserved `ExporterContract.OPTION_KEYING`
  single-choice option and recognized by id rather than drawn like an ordinary option:
  **Keep encrypted** (this device's key) · **New passphrase…** · **Remove encryption**. Arming
  *New passphrase…* reveals two XML-static, `saveEnabled="false"` masked fields (passphrase +
  confirm) below the options list; arming *Remove encryption* reveals one inline inkBlack warning
  line, og's pattern — no popup, no extra tap: *"The exported file will be readable by anyone."*
- **Export**, top-right, opens SAF `ACTION_CREATE_DOCUMENT` with the exporter's declared MIME type
  and a suggested filename (`ExportNaming`). It is the family's explicit-Intent form rather than a
  registered `ActivityResultContracts.CreateDocument` — the MIME isn't known until `describe()` has
  answered, which is after registration would need to happen.
- **Staged progress, in a dialog** (post-D2, `57e8413` — the user's call, the Backup screen's
  pattern). The inline status `TextView` is gone from the layout; in its place a modal,
  non-cancelable `AlertDialog` walks the same stages by rewriting its own message: *Preparing…* →
  *Re-keying…* / *Removing encryption…* (soil's two keyings) or *Rendering page N of M…* (a PDF's
  render, one line per page so a long notebook doesn't read as a stall) → *Exporting…* or
  *Encrypting and exporting…* (only when a PDF password is armed — the one line the user sees
  through the call says which of the two is actually happening, since the encryption runs on the
  extension's side of the seam and the host cannot narrate it further). Every result path
  (`OK`/`SHORT`/`UNCONFIRMED`/every failure) dismisses this dialog before showing its own; the
  flow's `finally` is the net underneath all of them, and a bounced `onDestroy` dismisses it on
  teardown so a screen torn down mid-export never leaves it standing.
- **Confirm dialog, then finish** (post-arc-17 toast review, 2026-08-30 — was a toast + immediate
  `finish()`, which risked cutting the toast off under the screen closing). `Dialogs.confirm`
  ("Exported") finishes on dismiss instead. Every failure is a dialog too, naming what went wrong —
  never a path, never a secret — and saying what happened to the destination file: removed,
  possibly remaining, or untouched (see the [failure table](#failure-table) for when each applies).
- **Both doors out are latched while an export runs** (the back arrow and system back): a Binder
  call cannot be cancelled, so leaving mid-export would skip the verification and cleanup while the
  extension's stream keeps writing — an unverified file standing silently. The tap gets a dialog
  saying why it did nothing (the toast-vs-dialog rule), never a silent ignore.

Discovery re-runs on every `onResume` while not busy (a package can be disabled or replaced under a
standing screen) and again, from scratch, if the flow finds itself running with no descriptors in
hand — the DocumentsUI picker is another process on a memory-tight device, so this screen can be
rebuilt behind it. A discovery continuation that lands while an export is running stands down
entirely (the arc-15 review): on a rebuilt screen the pending SAF result arrives before onCreate's
discovery has answered, and a substitution or a "nothing to export with" close under the running
flow would swap the exporter — values reset, keying back to Keep — or close the screen mid-export. The chosen exporter and the panel's values are **state the host owns**
(`onSaveInstanceState` — the G6 lesson from arc 13), re-matched by package name on restore; a
chosen exporter that has since gone is a problem dialog, never a silent fallback to whatever else
is installed (that would export a different format into a file already named for the first).

---

## The flow, step by step

This section is `NSE · Soil Export`'s path — a `SOURCE_SOIL` exporter, streaming the artifact
verbatim. The PDF exporter parts ways at step 4 (it renders instead of preparing a cache copy, and
skips keying entirely) and rejoins at step 6; see [PDF: the second exporter](#pdf-the-second-exporter-arc-18)
below for its own steps, and [The source-kind seam](#the-source-kind-seam-arc-18) for why the split
exists and where it lives in the code.

1. **The sheet row.** `LibraryActivity.onCardLongPress` runs `ExtensionRegistry.exporters()` — an
   IO-dispatched package query — **before** raising a notebook's action sheet, so **Export…**
   is either there or it is not; a folder's sheet never asks and never shows the row at all (folders
   have no Export row by design). Discovery runs at every long-press, never cached, and the IO beat
   between the long-press and the sheet is latched (`sheetPending`) like every other e-ink feedback
   gap — a second long-press in it would stack a second sheet, and a card tap in it drops the
   pending sheet rather than popping it over a departing library. The row hands
   off with `startActivity(ExportActivity.intent(this, s.id, s.name))`, latched against a double-tap.
2. **Options.** The user picks (or accepts the collapsed default for) a format and its options,
   including the keying trio.
3. **SAF pick.** Export first validates any armed *New passphrase…* fields (empty or mismatched is
   a dialog, **before** the picker — a passphrase problem found after a file has been named is a
   dialog on top of a document that then has to be deleted), then launches
   `ACTION_CREATE_DOCUMENT`. Cancelling at the picker leaves the screen exactly as it was; nothing
   was created, nothing to explain — and the typed passphrase is wiped with the flow it was
   collected for, not held until the next tap.
4. **Prepare the artifact** (`ExportArtifact.prepare`), entirely host-side and IO-dispatched:
   - assert the source `.soil` is not held open in this process (see [SoilOpenFiles](#soilopenfiles--one-file-one-connection) below);
   - a **transient open** through the one `SoilDatabase.open` door, and a **best-effort**
     `notebook_meta` refresh stamping `exportedAt` (the same fields
     `NotebookSession.refreshMeta` writes, plus the timestamp — a meta write that fails is logged
     and the export goes ahead with whatever the file already said about itself);
   - **seal** — `PRAGMA wal_checkpoint(TRUNCATE)` then close (`SoilDatabase.seal`), after which the
     whole notebook lives in the main file — and, because `seal` swallows a failed checkpoint by
     contract, `prepare` re-checks the one thing the copy depends on: a `-wal` still holding frames
     refuses the export (`Problem.COPY_FAILED`) rather than silently copying a main file missing
     the newest writes;
   - **copy the main file only**, never `-wal`/`-shm`, into `cacheDir/export/` — wiped and
     recreated at the start of every export (og's `exported_notebooks` hygiene) — and verify the
     copy's length against the sealed source's before trusting it.
5. **Key it** (`ExportKeying`, only when the chosen keying is not Keep) — see [Keying](#keying)
   below. Keep streams the artifact as-is.
6. **Two fds, one call.** The host opens a read-only fd on whichever file is actually being
   streamed (the artifact, or the keying transform's output) and a write fd on the SAF destination,
   then calls `ExporterClient.export(source, destination, spec)`. Both descriptors are the client's
   from that point — it closes them in `finally`, success, failure, or timeout, and the extension
   closes its own dups on its side.
7. **The extension streams.** `SoilExporterService.export()` reads the whole source fd in 64 KiB
   chunks, writes each to the destination fd, `fsync`s before closing, and returns the byte count —
   a short copy that reported its own short count would otherwise read as success on both sides. The
   caller check runs first, and (the E1 trap) it runs **inside** the `try` whose `finally` closes
   both descriptors — outside it, a refused caller would leak both dups.
8. **Verify, then say so.** The host checks the returned byte count against the length of the file
   it actually streamed — a mismatch there is a failed write: the destination is deleted and the
   dialog explains. Then it asks the destination provider what it now holds, taking **every answer
   it will give** (the `SIZE` column and a reopened fd's stat): corroboration, not authority. Any
   agreeing answer passes; a unanimous disagreement after a fully-streamed, fsynced write gets a
   *check-the-file* dialog and **no delete** — a cloud provider's metadata can lag the write it
   just took, and deleting a fully-written export over a stale answer would destroy the very thing
   that was just made (the arc-15 review). Only a clean pass reaches the confirm dialog and finishes.

Every path through steps 4–8 that does not reach step 8's confirm dialog ends the same way: the cache
directory is wiped in a `finally` (`NonCancellable` — a screen destroyed mid-export must not leave
the artifact behind), the typed passphrase (held only in memory, from the Export tap to here) is
cleared, and a dialog explains what happened — including what happened to the destination file.
**Deletion is conditional** (the arc-15 review's headline finding): the picker's overwrite
confirmation hands back a *pre-existing* document's URI, and a failure that never wrote a byte must
not take the user's previous good file with it. So `fail()` deletes only when the truncating open
has already destroyed the old content, or when the document was verifiably empty at the start of
the flow — and because the delete itself is best-effort, the dialog reports what actually happened
(*"The unfinished file was removed"* / *"An unfinished file may remain…"* / *"The file you chose
was not changed."*) rather than asserting a removal that may not have run. See the
[failure table](#failure-table).

---

## SoilOpenFiles — one file, one connection

*One `.soil`, one connection* was always a family-wide rule, but until export every caller could
satisfy it by construction — a notebook is opened by its own session, a foreign read by the one
`readOnce` ritual. Export is the first operation that reads a notebook's **bytes** from outside any
of that, and its correctness depends on the file being cold: checkpointed, sealed, not about to be
written under the copy. The library context guarantees no notebook screen is on the stack holding
it, but "guaranteed by where the button is" is not something the code can check — so `SoilOpenFiles`
is the door written down instead.

It is a plain in-process counter, keyed on the file's canonical path, `claim`ed and `release`d
**inside `SoilDatabase.open` / `.create` / `.seal`** — every door in the app is covered by
construction, no call site has to remember. `ExportArtifact.prepare` checks `isOpen(source)` before
its own transient open and refuses with `Problem.IN_USE` if the file is already held — an export
never runs against a file with a live writer behind it.

---

## Keying

The reserved `OPTION_KEYING` option is declared by the exporter like any other single-choice
option, but **recognized by id and executed entirely by the host** (`ExportKeying`, beside
`SoilCrypto`): the transform runs on the cache artifact before the fds are ever opened, and a typed
passphrase never enters the `ExportSpec` the extension receives — only the chosen value id crosses.

The mechanism is **export-and-key, not `PRAGMA rekey`** — og's recorded on-device finding that
rekey is unreliable on device. Both non-Keep transforms run `sqlcipher_export` between the
attached and primary connections, always with **the destination as the primary connection** (og's
orientation: opening plaintext as the primary zetetic connection with an empty key does not
reliably expose data, so the plain transform keeps the encrypted source primary and attaches the
plaintext destination instead). Two things `sqlcipher_export` does not do are done by hand: the
`user_version` PRAGMA is copied explicitly and re-verified from the finished file (og's bricked-file
trap — a version-less export imports as garbage), and the output's single `notebook_meta` row is
restamped so the file describes its own new keying rather than the source's. Nothing is accepted
unverified — the output must probe as the kind it claims, open, answer `PRAGMA integrity_check =
ok`, and hold the source's `user_version`, or the transform throws and its own unaccepted sibling
output is deleted (never the artifact, never the Garden file).

| Choice | What it produces | What opens it |
|---|---|---|
| **Keep encrypted** | a pure byte copy of the sealed `.soil`, still under this device's key | this device's key — anything that already opens the Garden file opens this one identically |
| **New passphrase…** | a re-keyed sibling file, encrypted under the typed passphrase, `notebook_meta` restamped `keyScope: NOTEBOOK` | the typed passphrase, via the stock SQLCipher CLI or any SQLCipher-compatible reader — SN itself never opens a file under that scope |
| **Remove encryption** | a plaintext sibling file, `notebook_meta` restamped `encrypted: false` with `keyScope` absent | any stock SQLite reader — no key at all |

`Keep` needs no device key at all (a pure copy); the two transforms both need it, which is why
`ExportActivity` only asks `KeySession.get()` once it knows the plan is not `KEEP` — a locked
library refuses those two keyings with their own dialog rather than blocking Keep as well.

---

## PDF: the second exporter (arc 18)

`NSE · PDF Export` (`:ext-pdf`, the seventh module) turns a notebook into a document instead of
copying its file. That single difference is what forces everything else about it: a PDF exporter
can never receive the `.soil` at all — no key crosses an extension seam, and an encrypted artifact
without its key is noise — so **the host renders every page and the extension only assembles
them**. What crosses the read fd is not the notebook; it is a `PageBundle`, a length-prefixed
container of already-encoded page images that `ExportRender` bakes one page at a time
(`cacheDir/export/`, wiped by the same `finally` that wipes the soil artifact) at each page's
**own** pixel size, full fidelity — template layer, headings, links' wrapped children, then ink,
through the one layering function `PagePreview.drawContent` already used on screen. RGB_565,
opaque, WEBP lossy q100 (the F5 recipe, re-measured true here: Skia lossless was unusable-slow on
a whole notebook). A page with no usable size, or a notebook with no pages at all, is an honest
`Problem.EMPTY` refusal — `export_empty_body` — never a zero-page PDF.

**No keying option, so no keying chrome.** `PdfDescriptor.info()` declares no
`OPTION_KEYING`, and the screen already handles that case by construction: no keying option means
no passphrase block and no plain-encryption warning ever show for this exporter. The device key is
still used, but only host-side, to open the notebook for reading — it never leaves this process
and the extension never sees it. There is also **no `exportedAt` stamp**: the soil path's
`ExportArtifact.prepare` restamps the Garden file's `notebook_meta` because that file *is* the
thing travelling; a PDF is not the notebook, so the render opens it strictly read-only and touches
nothing.

**The two options (D2).** `PdfDescriptor` declares exactly the pair D1 deliberately shipped
without (the J4 rule — a not-yet-built control does not exist as a dead row):

- **Include page template** (`OPTION_PAGE_TEMPLATE`, default on) is **host-executed** — its value
  threads into `ExportRender.render(includeTemplate = …)` and decides, per page, whether the
  template decode even happens. Off means white ground under the ink, not a decoded-then-discarded
  template: the bundle carries finished pixels, so there is nothing left in it afterwards for an
  extension to add or strip.
- **Password-protect** (`OPTION_PROTECT`, default off) is **host-collected, extension-executed**.
  Arming it reveals the same dual masked block the rekey trio uses — re-worded "Password" /
  "Confirm password" (`export_password_caption` etc.) rather than a second field pair, because
  `ExportOptions.isRenderable` refuses any exporter that could ask for both a rekey and a protect
  at once: one block, one tenant, one secret's lifecycle at a time. The typed password rides
  `ExportSpec.exportSecret` — the one deliberate secret that ever crosses an extension seam, whose
  full lifecycle rules live in [`docs/extensions.md`](extensions.md) § "The export secret." On this
  screen `typedExportSecret` mirrors `typedPassphrase` to the letter: held only from the Export tap
  to the flow's end, cleared at the picker's cancel and in the flow's `finally`, never in instance
  state, an Intent, or a log line. Over 128 characters (`MAX_EXPORT_SECRET_CHARS`) is refused at
  the tap, where a dialog can still explain (`export_password_long_*`); empty or mismatched fields
  are refused the same way, before the picker (`export_password_missing_*` /
  `export_password_mismatch_*`); a screen rebuilt behind the picker with the fields wiped
  (`saveEnabled="false"`) refuses honestly with `export_password_lost_body` rather than silently
  exporting an unprotected file the user asked to have locked.

**The passwordless-PDF honesty line is deliberate silence** — the user's explicit D2
phase-start call. Every SN notebook is already encrypted at rest, so an inline note on every plain
PDF export ("this file will not be password-protected") would fire on the overwhelmingly common
case and read as nagging rather than information; the plain-encryption warning earns its one line
on the *soil* path because removing encryption is the unusual, one-way choice, where declining a
PDF password is simply the default.

`:ext-pdf` itself: `PdfExporterService` is a call-shaped, stateless bind exactly like `:ext-soil`'s
(`HostCallerCheck.enforce` first, inside the fd-closing `try`; only `SecurityException` /
`IllegalArgumentException` / `IllegalStateException` ever leave). `PdfAssembly.assemble` builds the
document with **pdfbox on both paths** — the D3 review's memory finding made structural: the
framework's `PdfDocument` looked lighter but holds *every page's full-size raster* until `writeTo`
(`finishPage` only records a picture that keeps referencing the bitmap, so `recycle()` freed
nothing and a long notebook accumulated hundreds of megabytes before a byte was written; the
13-page walk was far too small to show it), where pdfbox holds each page as its **compressed JPEG
stream**, so what accumulates across the loop is roughly the finished document's own size. Per
page: decode (RGB_565, matching the host's own encode), dimension-check against the bundle's own
declaration, re-encode JPEG q100 (the one extra lossy pass, priced invisible at q100 — and what
keeps a photo-templated page compressed instead of ballooning through a lossless pass), attach as
a `PDImageXObject` on a `PDPage` at the page's own size, recycle before the next page is read. A
page that will not decode, or decodes at a size the bundle didn't declare, is an
`IllegalStateException` naming the page number and both sizes — a **delivery failure**, never a
page quietly skipped; a PDF silently short of a page would otherwise read as a success on both
sides. When a password is armed, the same document is saved under
`StandardProtectionPolicy(password, password)` with `setPreferAES(true)` at 128 bits — **the
`setPreferAES` call is what makes 128 mean AES**: without it pdfbox emits the deprecated RC4
cipher at the same key length, a file every reader still opens and no checklist can tell apart
(the D3 review's cipher finding) — and the encryption runs as the save streams each object out, so
there is no second in-memory copy of the document. Delivery is one `CountingOutputStream` +
`fsync` for both paths, through a close-shield: pdfbox's save closes whatever stream it is given,
so the shield turns that close into a flush and the sync runs on a still-open fd (the D3 review's
sync finding — the old shape synced a fd the save had already closed, so no protected export was
ever actually synced). Whether the sync is owed is answered by `fstat`: a regular file must sync
and a failure there (`ENOSPC`/`EIO`) is a delivery failure, never a claimed success; a pipe from a
streaming provider has nothing to force to storage and is skipped rather than attempted-and-excused
(`SoilStreams` keeps the same rule). `pdfbox-android:2.0.27.0` is module-local — the one dependency
this arc added, approved 2026-08-30, and it never leaks past `:ext-pdf`. Every
`IOException` inside the assembly is re-thrown as an `IllegalStateException` naming the stage
("reading the page bundle" / "writing the PDF" / "protecting the PDF") — never a path, never a
page's content, never the secret.

---

## The source-kind seam (arc 18)

The two exporters differ in one structural way — one streams a file verbatim, the other renders
and assembles a new one — and that difference had to become part of the contract, not just this
screen's private branching. `ExporterInfo` grew a compatible parcel tail, `sourceKind`:
`ExporterContract.SOURCE_SOIL` (absent on an old-shape descriptor means this, so every exporter
built before arc 18 kept its meaning on real wire) or `SOURCE_PAGES`. `ExportActivity` reads it
once, right after `describe()` answers, and everything downstream — which preparation runs, what
verification means, whether keying chrome can even appear — follows from that one value; the flow
never asks a second time which kind it is dealing with. The full write-up of the tail, the
`PageBundle` container format, and the caps that bound it (`MAX_PAGES` 4096, `MAX_DIMENSION_PX`
32768, `MAX_PAGE_BYTES` 32 MiB) lives in [`docs/extensions.md`](extensions.md) § "The source-kind
tail" — this doc only needs the consequence: `ExportRender` produces the bundle for `SOURCE_PAGES`
where `ExportArtifact.prepare` + `ExportKeying` produce the artifact for `SOURCE_SOIL`, and
`ExportActivity`'s private `StreamSource` sealed class (`Ready`/`Failed`) is what lets everything
from the two fds onward stop caring which one it got.

**Verification is per source kind** too (`ExportVerification`, pure — pinned by test). The
`bytesWritten == streamBytes` equality the arc-15 review built is a *verbatim-streaming contract*:
it holds for `SOURCE_SOIL`, because the soil exporter really does copy the artifact byte-for-byte,
and it would fail every honest `SOURCE_PAGES` export, because a PDF's size is never the bundle's.
So a `SOURCE_PAGES` verdict drops the source-length equality and keeps only the
destination-corroboration half: zero bytes reported is never a document (`Verdict.SHORT`
immediately), and otherwise the extension's own reported count is checked against **every answer
the destination provider will give** — the same "corroboration, not authority" rule step 8 above
already uses for soil, unchanged. `SHORT` (a failed export — the flow may delete wreckage under
its usual rules) stays distinct from `UNCONFIRMED` (the stream completed; only the provider's
metadata disagrees — a check-the-file dialog, never a delete) for both source kinds alike. An
unknown `sourceKind` value is unreachable in practice (`ExporterInfo`'s unmarshal already rejects
it, dropping that exporter at discovery like any other bad descriptor) but still resolves to
`SHORT` rather than `OK` — verification never defaults to trust.

---

## Timeouts

A Binder call cannot be cancelled, so both of `ExporterContract`'s timeouts are measured, not
guessed (the arc-11 J5 lesson): `DESCRIBE_TIMEOUT_MS` is 3 s (a small in-memory descriptor, fast by
construction); `EXPORT_TIMEOUT_MS` is 120 s, sized against a 100 MB flash copy measured on the Nomad
at ~0.45 s (~525 MB/s `dd`, ~230 MB/s `cp`) — two minutes comfortably covers a 1 GB artifact even
through a slow DocumentsProvider at 10 MB/s.

**One value covers PDF too — measured, not guessed, twice.** Page-by-page PDF assembly
is heavier per byte than a flat copy, so it was measured on the
Nomad rather than assuming the soil number transferred: at D1, a 1-page notebook assembled in
303 ms and the 13-page notebook in 3508 ms (~270 ms/page marginal); at D3, when the assembly moved
onto pdfbox, the same 13-page bundle took 2607 ms (~200 ms/page — the J5 rule re-applied, since
the shape changed). `EXPORT_TIMEOUT_MS` at 120 s covers
about 400 pages at that rate, and the host's render runs to completion *before* the timed
`export()` call even starts (the render has no timeout of its own — it is plain suspending IO on
the host's side of the seam, not a Binder call). No PDF-specific timeout was added; the one value
serves both source kinds. The `PageBundle` container's own size was sanity-checked the same runs:
13 pages of WEBP q100 pixels came to 175 KB. The finished PDF grew with the D3 move to JPEG pages
— the 13-page notebook's PDF went from ~204 KB (Skia's deflate over mostly-white ground) to
~1.2 MB (~92 KB/page) — the deliberate price of pages that stay compressed whatever the paper is;
a photo-templated notebook pays roughly the same per page where a lossless pass would balloon.

---

## What never crosses

Passphrase, path, and SQLCipher never reach the exporter — the extension receives two fds and a
bounded id → value map (`ExportSpec`), nothing else. **The one deliberate exception** is the
arc-18 export secret (`ExportSpec.exportSecret`): a user-typed password, scoped to exactly this
export, that opens no Notesprout data — never the global passphrase, never derived from it, never
the device key. It rides its own trailing field rather than the value map precisely so it can never
be mistaken for an ordinary option, and `KIND_PASSPHRASE`'s never-crosses meaning is untouched by
its existence. The full boundary audit — outward on `describe` and `export`, inward from
`ExporterInfo`/`ExportResult`, the keying secret's whole host-side lifecycle, and the export
secret's own row — is [`docs/extensions.md`](extensions.md) § "Boundary audit," rows 6–8 and 13.

---

## Failure table

| What happened | What the user gets | Where |
|---|---|---|
| No trusted exporter installed | problem dialog, "Nothing to export with" — the screen closes | `ExportActivity.discover` → `problemAndClose` |
| A `describe()` call fails, or the descriptor is over the `ExporterContract` caps | that exporter dropped with a log line — never shown, never a crash | `ExportActivity.describe` / `loadCandidates` |
| A descriptor declares an option kind this build cannot draw (a free-standing passphrase kind), or a reserved keying option with a choice id the host has no transform for | that exporter dropped the same way — an unexecutable keying surfacing at export time would be explained as the wrong failure | `ExportOptions.isRenderable` |
| No app on the device can create a document | problem dialog, "No file picker" | `onExportTap` |
| A rekey's passphrase field(s) are empty | problem dialog, "Passphrase needed" — **before** the picker | `onExportTap` |
| A rekey's two fields don't match | problem dialog, "Passphrases don't match" — **before** the picker | `onExportTap` |
| A protect password field is empty (arc 18 / D2) | problem dialog, "Password needed" — **before** the picker | `onExportTap` → `export_password_missing_*` |
| A protect password's two fields don't match (D2) | problem dialog, "Passwords don't match" — **before** the picker | `onExportTap` → `export_password_mismatch_*` |
| A protect password is over 128 characters (D2) | problem dialog, "Password too long" — **before** the picker, where the dialog can still explain | `onExportTap` → `export_password_long_*` |
| The notebook is open elsewhere in this process | problem dialog, "This notebook is open somewhere else" | `ExportArtifact.prepare` **or** `ExportRender.render` → `Problem.IN_USE` |
| No device key session (process killed, nothing unlocked since) | problem dialog, "The library is locked" | `Problem.NO_KEY` (both prepare paths) |
| The `.soil` file is gone or empty | problem dialog, "no longer on the device" | `Problem.MISSING` (both prepare paths) |
| The `.soil` won't open or won't read | problem dialog, "could not be read just now" | `Problem.UNREADABLE` (both prepare paths) |
| The cache copy failed, or came out short (`SOURCE_SOIL`) | problem dialog, "device may be out of space" | `Problem.COPY_FAILED` |
| The notebook has no pages to render (`SOURCE_PAGES`, arc 18) | problem dialog, "This notebook has no pages" — an honest refusal, never a zero-page PDF | `ExportRender.Problem.EMPTY` → `export_empty_body` |
| A page row carries no usable size — a damaged or foreign-written file (`SOURCE_PAGES`, D3) | problem dialog, "could not be read at its own size" — its own sentence, **never** the memory-or-space one: a data problem blamed on storage would be retried forever | `ExportRender.Problem.DAMAGED` → `export_damaged_body` |
| More pages than the bundle carries (> 4096, `SOURCE_PAGES`, D3) | problem dialog, "more pages than this format can carry" | `ExportRender.Problem.TOO_LONG` → `export_too_long_body` |
| A page will not allocate, draw or encode — low memory or space (`SOURCE_PAGES`, arc 18) | problem dialog, "could not be prepared for this format"; the notebook itself is untouched | `ExportRender.Problem.RENDER_FAILED` → `export_render_failed_body` |
| The screen was rebuilt behind the picker and the chosen exporter is gone | problem dialog naming the format unavailable; destination per the deletion rule (nothing was written — a pre-existing overwrite target is left untouched) | `reselectAfterRestore` → `export_gone_body` |
| The `ExportSpec` itself is rejected (a value out of bounds) | problem dialog; destination per the deletion rule | `runExport` catch on `ExportSpec` construction |
| Rekey was armed but the typed passphrase was lost (screen rebuilt behind the picker — `saveEnabled=false` wiped the fields) | **honest "passphrase was lost" dialog — never a silent Keep**; destination per the deletion rule | `ExportKeying.plan` throws `IllegalArgumentException` → `export_passphrase_lost_body` |
| Protect was armed but the typed password was lost the same way (arc 18 / D2) | **honest "password was lost" dialog — never a silent unprotected export**; destination per the deletion rule. Fails **closed** (D3): the guard consults the raw tap-time `protect = 1` as well as the re-described descriptor, so an exporter upgraded in place behind the picker that dropped the protect toggle still refuses rather than silently exporting unprotected | `runExport` (`armedAtTap`) → `export_password_lost_body` |
| The keying transform itself fails (plain or rekey, `SOURCE_SOIL`) | problem dialog, "could not be converted for this export"; destination per the deletion rule | `ExportKeying.apply` throws → `export_transform_body` |
| The `export()` call fails, times out, or the exporter dies mid-stream — including a PDF page that won't decode, decodes at the wrong size, an inconsistent spec (`PdfExportSpec.require`), or any assembly `IOException`, each an `IllegalStateException`/`IllegalArgumentException` naming the stage | problem dialog, "didn't finish writing the file"; the truncating open already ran, so the wreckage is removed (best-effort, reported honestly) | `ExporterClient.export` throws → `export_failed_body` |
| The byte count the extension reports doesn't match what was streamed (`SOURCE_SOIL`), or is zero or disagrees with the destination (`SOURCE_PAGES`) | problem dialog, "Only part of the notebook reached that file"; wreckage removed (best-effort, reported honestly) | `ExportVerification.verdict` → `SHORT` → `export_short_body` |
| The stream completed but every answer the destination provider gives disagrees with it (either source kind) | *check-the-file* dialog, **no delete** — metadata can lag a write it just took, and a fully-written export is never destroyed over a stale answer | `ExportVerification.verdict` → `UNCONFIRMED` → `export_verify_body` |
| Back / the back arrow tapped while an export runs | "Export in progress" dialog; the flow continues untouched | `showBusyGuard` (`export_busy_body`) |
| Export succeeded | confirm dialog, "Exported"; screen finishes on dismiss | `runExport` success path |

The rule behind the column, family-wide: **a toast only confirms something that already happened;
anything explaining why a tap didn't work is a dialog.** Export's own success case moved off that
rule (post-arc-17 toast review, 2026-08-30): the screen closes right after, which a toast can't
survive to be read, and the outcome is worth a deliberate acknowledgment. Every failure row after
the picker also
appends what happened to the SAF document — removed where removing it cannot cost the user
anything (a partial export sitting in the user's files under a name that says "notebook" is worse
than none at all), left alone where it might be the user's own pre-existing file, and always
reported honestly, because the delete is best-effort.

---

## Traps recorded

- **The Keep `cmp` proof needs Keep to be the *last* export run.** Every `prepare()` re-stamps
  `exportedAt` into the Garden file's `notebook_meta` and SQLCipher re-encrypts the rewritten pages,
  so a Keep export taken earlier in a session honestly diverges from the Garden file's current
  bytes — that is not a bug, it is what re-stamping does; re-export and compare against the file as
  it now stands.
- **The family JSON codec cannot express an explicit-null `keyScope`.** `NotebookMeta` is
  `explicitNulls = false` with `keyScope` defaulting to `GLOBAL` on read, so an *absent* key decodes
  straight back to `GLOBAL` — a plain export cannot make the field itself say "no scope." The
  **governing field is `encrypted: false`**, pinned by test (`ExportKeyingTest`); a plain export's
  restamped meta carries `encrypted: false` with `keyScope` simply not written, and any reader must
  check `encrypted` first, not `keyScope`.
- **The verbatim-streaming verification does not transfer to a transforming exporter** (arc 18).
  The arc-15 review had refuted a "transforming exporter" finding on the grounds that soil streams
  verbatim — true for `SOURCE_SOIL`, and never meant to generalise. A PDF really does transform its
  input, which is exactly why `ExportVerification` had to grow a second, source-kind-aware branch
  rather than reuse the equality; running the old check against a `SOURCE_PAGES` result would fail
  every honest PDF export.
- **A PDF's timeout is not a byte-copy's timeout — measured, not assumed to transfer.** Assembly
  (decode + re-encode per page) costs real time per page in a way a flat copy does not;
  D1 measured on the Nomad rather than reusing arc-15's number on faith (see
  [Timeouts](#timeouts)), and only after measuring confirmed one shared value still covers both.
- **A raw NUL byte can land from file tools mid-edit** (fired 3× by arc 16, standing since) —
  byte-scan any file a tool just wrote before calling a phase done; it is invisible to a normal
  diff read.

---

## Related

- [`docs/extensions.md`](extensions.md) — the seam: `INotebookExporter`, `ExporterContract`, the
  two-fd handshake, the source-kind tail, the export secret, the boundary audit, `:ext-soil`'s and
  `:ext-pdf`'s identities.
- [`docs/library.md`](library.md) — the notebook long-press sheet, where the **Export…** row sits.
- `apps/notesprout_ratta/RATTA_PLAN.md` §§ "Phases — Arc 15 \"Export\"" and "Phases — Arc 18
  \"PDF\"" — the wizard's locked decisions and each phase's outcome, in full.
- og's `docs/full-notebook-export.md` (monorepo root) — the reading reference for the filename
  sanitize rule and the keying shapes; SN's export screen implements a fresh, format-compatible
  version, and imports nothing from it (Paper never built export). og's `NotebookExporter` (raster
  PDF + pdfbox password) is the equivalent reading reference for arc 18's PDF exporter — again no
  code copied.
