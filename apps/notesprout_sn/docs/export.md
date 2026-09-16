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
copied. Arc 19 grew a third: **`NSE · Document`**'s document exporter produces the notebook's
Markdown or plain-text document, streamed verbatim like the soil exporter but assembled from
`.soil` document rows rather than the file itself — plus a Document mode for the PDF exporter
that renders the editor's own preview instead of the page canvas, with no change to `:ext-pdf`
at all. See [The document exporter](#the-document-exporter-arc-19) below. Arc 31 / HV1 grew a
fourth: **`NSE · Image Export`** produces one PNG per page — a page-bundle exporter exactly like
PDF, but declaring **per-page delivery** rather than one file, so a whole-notebook export becomes
one bind per export and one call per page into a folder instead of one call into a single
document (`ExporterClient.hold` — arc 34 / M9a; before it the loop bound and unbound per page). See
[Images](#images-arc-31--hv1) below. The four exporters
split into two behavioral shapes — a verbatim byte stream (`.soil`, and now the document text) or
a host-rendered page bundle the extension only assembles (PDF, in either of its two modes, and
now PNG) — which
is why the seam itself grew a second shape to carry both; see
[The source-kind seam](#the-source-kind-seam-arc-18) below. Arc 25 / V3 grew the screen a second
kind of *destination*, orthogonal to all four exporters: the panel's **Destination** row can send
the finished file to the one installed cloud provider instead of a SAF document — see
[Cloud destination](#cloud-destination-arc-25--v3) below and [`docs/cloud.md`](cloud.md) for the
seam it rides on. Arc 31 also grew the screen a third *scope*, calendar pages rather than a
notebook's — see [Calendar mode](#calendar-mode-arc-31--hv4) below.

This is the feature doc. The seam it rides on — the point, the AIDL, the fd handshake, the
source-kind tail, the export-secret carrier, trust — is [`docs/extensions.md`](extensions.md)
§§ "The exporter point (arc 15)," "The source-kind tail," and "The export secret"; the sheet row
it hangs off is [`docs/library.md`](library.md); the document/editor feature the document exporter
reads from is [`docs/document.md`](document.md).

**Status: arc 15 complete** — E1 the point + `NSE · Soil Export` + the screen (Keep path, commit
`c5fb23b`) · E2 the keying transforms (New passphrase · Remove encryption, commit `1860da3`) · E3
review, boundary audit, docs, freeze. **Arc 18 "PDF" complete** — D1 the source-kind seam + host
render pipeline + `NSE · PDF Export` end to end (plain PDF, commit `1844446`) · post-D1 the chooser
defaults to the last-used exporter (`1a18036`) · D2 the two options — page-template toggle +
password protection (`ff71644`) + post-D2 the progress dialog (`57e8413`) · D3 this documentation
pass. **Arc 19 "Document" M9 complete** — the document exporter (`SOURCE_DOCUMENT`, `NSE ·
Document`) + the host-side Source row + Document-mode PDF-of-preview, all end to end on the Nomad
(commit `62964e6`). **Arc 25 "Drive" V3 complete** — the Destination row + the host-drawn cloud
browser + the upload leg, all three exporters unaware, walked on the Nomad (2302 JVM tests, user
checklist passed 2026-09-04). **Arc 30 "Page" PE2 complete** — page scope: the notebook's page-sheet
door (`EXTRA_PAGE_ID` + `EXTRA_RETURN_TO_NOTEBOOK`), the host-owned Scope row, `ExportScope`'s
host-side page filter ahead of every render, all three exporters unaware, walked on the Nomad
(1487 `:app` / 2847 JVM tests, 2026-09-08). See [Scope](#scope-arc-30--pe2) below. **Arc 31
"Harvest" HV1 complete** — a fourth exporter, `NSE · Image Export` (`:ext-image`, PNG,
per-page delivery at whole scope), `ExtensionContract.API_VERSION` 8 → 9 with `ExporterInfo
.delivery` as the compatible tail (1501 `:app` / 2877 tests, 2026-09-08). See
[Images](#images-arc-31--hv1) below. **HV3 complete** — export presets, an additive
`export_preset` index row (1538 `:app` / 2914 tests, 2026-09-09). See
[Presets](#presets-arc-31--hv3) below. **HV4 complete** — `ICalendar.render` at `API_VERSION` 9
and the Export screen's calendar mode, a third `ExportScope` alongside Whole and Page (1560
`:app` / 2941 tests, 2026-09-09). See [Calendar mode](#calendar-mode-arc-31--hv4) below.

---

## The screen

`ExportActivity` (`IndexGuard`, portrait, e-ink chrome, `TopGuard` 0) receives `EXTRA_NOTEBOOK_ID`
/ `EXTRA_NOTEBOOK_NAME` — never a `File` — and, from the notebook's page-sheet door only (arc 30 /
PE2), `EXTRA_PAGE_ID` + `EXTRA_RETURN_TO_NOTEBOOK`; see [Scope](#scope-arc-30--pe2) below. Chrome follows F2: the action button lives at the
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
- **The Source row (arc 19).** When the notebook has a document and the chosen exporter is
  `SOURCE_PAGES` (today, `NSE · PDF Export`), the panel grows a host-owned Notebook pages / Document
  choice deciding what gets rendered into the page bundle. It is `GONE`, not disabled, the moment
  either condition stops holding. See [The document exporter](#the-document-exporter-arc-19) below.
- **The Destination row (arc 25 / V3).** After the exporter's own options and before the passphrase
  block, a second host-owned choice — *File on this device* / the cloud provider's own name — asks
  where the finished file goes, independent of which exporter made it. `GONE`, not disabled, while
  no trusted cloud provider is installed, and its own standing answer is forced back to *local* the
  moment the row leaves, exactly as the Source row's is. See
  [Cloud destination](#cloud-destination-arc-25--v3) below.
- **The Scope row (arc 30 / PE2).** The **first** row of the options panel, above Format — *This
  page* / *Whole notebook* — because it decides which formats are listed at all. Present only when
  the screen was entered from the notebook's page sheet **and** some installed exporter serves page
  scope (`ExportScope.offerable`); `GONE` from the library door, which stays whole-notebook with no
  control, as it always was. See [Scope](#scope-arc-30--pe2) below.

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

### PDF endnotes — `PageBundle` v2 and `bundleVersion` (arc 28 / H6)

Sticky notes (arc 28) had to reach the PDF exporter without ever crossing the seam themselves —
the D7 answer is the same host-renders/extension-assembles split above, grown one trailer deeper.
A live sticky note's ink goes out as an **endnote**: one page appended after the notebook's own
pages, and two links tying it to the page it came from. Full reference for the objects this reads:
[`objects.md`](objects.md).

**`PageBundle.VERSION = 2`, backward-readable to `VERSION_1 = 1`.** `extension-api`'s
`PageBundle` (`docs/extensions.md` § "The source-kind tail") gained a trailer behind the pages: `int
linkCount` then, per link, `int fromPage · float l t r b · int toPage` — a `Link` whose constructor
`require`s 1-based page numbers and a non-empty finite rect. `MAX_LINKS = 65536` caps the trailer
before allocation, the same rule as the page caps above it. `Writer(out, pageCount, links =
emptyList())` writes **version 1 byte-for-byte when `links` is empty** — `writer.version` reports
which — so a sticky-free notebook still produces the identical arc-18 stream, and the trailer itself
is written in `close()`, after every declared page has actually landed. Every link is checked
against `pageCount` at construction, so a reader can never meet a page number the writer could not
have meant. `Reader` accepts version 1 or 2; `readLinks()` may only be called after the last page
has been read (before that, `IOException` — the trailer sits behind the pages on the wire) and
answers an empty list for a version-1 stream; the link count is capped before any `ArrayList` is
sized, and a truncated trailer is named in the exception rather than swallowed as EOF. 11
`PageBundleTest`s pin all of this, including a v1 stream read through a v2 reader and four
malformed-trailer shapes.

**`ExporterInfo.bundleVersion: Int = 1`** is the second compatible parcel tail, right after
`sourceKind` (same `dataAvail()`-gated read, same "an old-shape descriptor simply runs out and
means the old value" rule) — **no `API_VERSION` bump**, exactly as `sourceKind` needed none at arc
18. It says the highest `PageBundle` version that exporter's `export()` actually reads. `:ext-pdf`'s
`PdfDescriptor.info()` declares `bundleVersion = PageBundle.VERSION`; an exporter that never heard
of endnotes keeps declaring 1 and the host writes it a version-1 bundle regardless of what the
notebook holds — the notes simply go out as icons only, with no links to follow them, and the
Export screen says so in one line (`export_endnotes_unavailable`, below the options) whenever
`ExportDocumentRules.endnotesUnavailable(sourceKind, bundleVersion, hasStickyContent,
documentSource)` is true: a `SOURCE_PAGES` exporter below `PageBundle.VERSION`, a notebook that
actually has a note with content, and the pages — not the document — about to be drawn.
`ExportActivity` reads `hasStickyContent` off `SoilDao.stickyIdsWithContent()` on the **same**
`readOnce` that already answers `hasLiveDocument()`, and passes `c.info.bundleVersion` straight
into `ExportRender.render(...)`.

**`PdfLinks.annotations(links, pageHeights)`** (`:ext-pdf`, pure) is the one piece of arithmetic
between a bundle link and a PDF annotation: the bundle speaks top-left-origin pixels on a 1-based
page, PDF wants bottom-left-origin on a 0-based page, and the only conversion once the pages are
1:1 in size is the vertical flip — `lly = pageH − b`, `ury = pageH − t`. A link naming a page
outside `pageHeights` is refused, never dropped. `PdfAssembly` reads the trailer only after every
page has been added to the document, and — only when it is non-empty — adds one borderless
`PDAnnotationLink` with a `PDActionGoTo`/`PDPageFitDestination` per entry, in a `"linking the
endnotes"` stage that runs **before** `protect()`, so a password-protected export encrypts the
links along with everything else. A version-1 bundle carries no trailer, the annotation pass never
runs, and a sticky-free notebook's PDF is byte-identical to the one arc 18 produced
(`PdfLinksTest.noLinksMeansNoAnnotations` + the writer's v1-bytes test pin it both ends).

**Host side.** `SoilDao.stickyIdsWithContent()` is one notebook-wide `JOIN` naming every live
sticky note that owns at least one live stroke — a note with nothing drawn in it gets no endnote
page at all. Pure `export/Endnotes.plan(sources, pageCount)` decides everything `ExportRender`
then only draws: numbering is page order, then z-order on the page (loose objects before
link-wrapped ones); a note's page is `pageCount + N`; its content size is the row's own carried
size, falling back to the source page's own size when the row carries none (an old or foreign
file), clamped to `PageBundle.MAX_DIMENSION_PX` with `Endnotes.CAPTION_PX` (60 px) reserved for the
caption strip underneath; two links per note — the icon on the source page jumps to the note, the
caption strip jumps home — except an icon with no area, which gets no icon link (`PageBundle.Link`
refuses an empty rect). Content taller than one page is not split (og's own deferred item, kept
deferred). `ExportRender.render(..., bundleVersion)` plans the endnotes **before** the first page
is drawn, since the `PageBundle.Writer` declares its page count and its whole link trailer up
front; it then walks the notebook's pages as before, recycling the template bitmap, and finally
bakes each note — its strokes clipped to the content area (local coordinates, `(0,0)` at the
content's own top-left), a 1 px rule under it, `Endnotes.caption(number, fromPageLabel)` ("Note N —
from page P", og's wording verbatim, the notebook never named) in 32 px sans at a 16 px inset,
encoded WEBP q100 exactly like a page, one bitmap alive at a time. **`fromPage` and `fromPageLabel`
answer different questions (arc 34 / L15):** `fromPage` stays bundle-relative (1-based within the
exported pages) because that is what the link annotation must address; `fromPageLabel` is the
**notebook-relative** page number, so a page-scope export whose bundle holds only the exported
pages still captions "from page 7" rather than "from page 1" when page 7 is the only page in the
bundle. `ExportScope.pagesInScope` answers a `ScopedPage(row, number)` carrying that
notebook-relative number, and `ExportRender.PageBake` carries it through to the bake. Progress
counts the notes as well as the pages. `DocumentPdfRender` is untouched — a document export never
carries links, so it is always a version-1 bundle by construction.

Failure-table and traps additions, below, are this phase's; tests: `PageBundleTest` (11, in
`extension-api`), `PdfLinksTest` (`:ext-pdf`), `EndnotesTest` (9 — arc 34 / L15 added the
notebook-relative-caption-vs-bundle-relative-link case), `ExportRenderEndnotesTest` (3,
over the fake DAO — order, skip-empty, wrapped, size fallback, and the sketch-shifted link),
`ExportDocumentRulesTest` (+1).

### Sketch pages (arc 43 / K7)

**A page that carries a raster sketch exports as two pages: its ink, then its sketch, immediately
after it** (arc 43, decision 5). A drawing the person deliberately kept beside the writing stays
beside it — never flattened under it — and the sketch page is **plain white paper**, never the
page's template (decision 10), because white is what the sketch face draws on.

`ExportRender` plans it before the first page is drawn: one blob-free query
(`SketchDao.pagesWithSketch`, ids only) marks each `PageBake.hasSketch`, and two pure functions do
the rest — `bundlePages(pages)` is the interleaved list the bundle actually holds (`(pageIndex,
sketch)` entries) and `bundlePositions(pages)` is where each page's **ink** lands, 1-based. Every
count downstream is the bundle's: the header's declared page count, both `PageBundle.MAX_PAGES`
refusals, the progress line, and the endnotes — whose `fromPage` link is `bundlePositions[index]`
so a note on page 3 addresses the right page when page 1 has a sketch above it (`fromPageLabel`,
what the caption *says*, is still the notebook's own number). `SketchRaster.toWebp` draws the
page: the stored PNG decoded ARGB_8888, composited over an opaque white `RGB_565` bitmap at the
page's own size, WEBP q100, both bitmaps recycled before the next page starts. **The row is read
through `SketchDao.sketchFor` + `SketchRows.fitsPage`, not `SketchRepository.get`** — the
repository's read soft-deletes a row that fails the header guard, and a render must not mutate what
it renders (rule 1 of the bake); the guard is applied all the same, it simply refuses instead of
dating the row out.

**A sketch that has gone missing between the plan and the bake writes a blank page of the page's
size** (`SketchRaster.blank`, one `Log.w` naming the page id — never pixels). The bundle declared
its page count in its header before the first page was written, so skipping the page would close
short and the whole export would be refused as truncated; reading every sketch's bytes up front to
make the count exact would cost the notebook's pixels in memory at once, which the render will not
do.

**Naming.** The per-page delivery's answer is `ExportRender.Outcome.Ready.pageNames` — one
`ExportNaming.PageName(number, title, sketch)` per **bundle** page (never per notebook page: with a
sketch interleaved the two are different things), the endnote pages still getting none.
`ExportNaming.pageStem(…, sketch = true)` is the ink page's own stem with **` sketch`** appended —
`Meeting notes - Agenda` / `Meeting notes - Agenda sketch`, `Meeting notes - page 3 sketch`, and
`Meeting notes sketch` for a page with neither a heading nor a place. The `MAX_TITLE_CHARS` cap
applies to the heading, **before** the suffix: the suffix is the app's word and truncating it would
make a filename lie about what is in it.

**Page scope carries its sketch too**: `ExportScope.Page` filters the page rows before the plan, so
one page with a sketch is a two-page bundle and nothing else changes — a per-page exporter at page
scope still delivers one file per bundle page, and the single-file export (a PDF holding both) is
named for the **page**, the ink page's stem. The document source is untouched (decision 5): a
document export is the authored text, and there is no page under it to have a sketch.

---

## The document exporter (arc 19)

`:ext-document` (**`NSE · Document`**) grew a third exporter on the same point:
`DocumentExporterService`, declaring `ExporterContract.SOURCE_DOCUMENT = 2` and one format option
**Markdown (.md) / Plain text (.txt)** (`OPTION_TEXT_FORMAT`, `"textFormat"`, choices
`ExporterContract.TEXT_FORMAT_PLAIN`/its Markdown counterpart). Its service manifest declares
`API_VERSION` **3** — a version-2 host reading its `sourceKind` tail as absent would run this
exporter the way it always ran `SOURCE_SOIL`, which is why the number moved rather than the
descriptor's shape (the D3 skew-guard recipe, repeated; `:ext-document`'s editor service keeps
declaring 2, the D3 lesson that API version is per-service metadata, not per-package).

**The host assembles the final bytes; the extension only streams them.** M9's own phase-start
contradiction — the plan's "extension strips via `:markdown`" could not coexist with the pinned
"a text stream is verbatim" verification — resolved in the verification's favour: the `.txt` strip
runs **host-side**, through `MarkdownText.toPlainText` (og's function, ported into `:markdown`
whole and pinned by og's tests), inside `ExportText.assemble`/`write`
(`ExportDocumentRules.finalText`). `DocumentExporterService.export()` is therefore a pure
byte-for-byte copier, the same shape `SoilExporterService` already is, and `ExportVerification`
holds `SOURCE_DOCUMENT` to the identical `bytesWritten == streamBytes` equality `SOURCE_SOIL` gets
— a text export really is a copy of what the host handed it, unlike a `SOURCE_PAGES` render.

`OPTION_TEXT_FORMAT` is **host-executed twice over**: once at assembly (which strip runs, if any)
and again at destination naming (`ExportDocumentRules.fileExtension`/`mimeType` pick the `.md`/
`text/markdown` or `.txt`/`text/plain` pair the SAF picker and the suggested filename see —
every other source kind keeps its descriptor's own answers). `ExportOptions.isRenderable` gates the
option to `SOURCE_DOCUMENT` only and to its two known choice ids, and keying can never ride the
document kind — the same "one secret's lifecycle at a time" rule `OPTION_PROTECT` and the rekey
trio already share.

**`ExportText.markdownOf`** is the one read both document exports share with `DocumentPdfRender`
below — the notebook document if it is non-blank, else the per-page documents joined in page order
(M7's merge join, verbatim: `"\n\n"` between parts, parts untrimmed, the whole trimmed;
undocumented pages skipped). A text document's row is notebook-parented, so it rides the same read
for free. **Export never recognizes** — a text export is documents only, and a notebook with
nothing written in it at all is `Problem.NO_DOCUMENT`, an honest refusal rather than an empty file
(unreachable through the UI in the ordinary case, because the chooser already gates it away —
see below).

**Chooser gating.** The document exporter is listed only when the notebook actually has a document:
`hasDocument` (`SoilDatabase.readOnce` + `SoilDao.hasLiveDocument()`, blank-means-absent in SQL) is
read once at the top of `loadCandidates()` and feeds both `ExportDocumentRules.listed()` (whether
the exporter appears in the chooser at all) and `sourceRowVisible()` (the Source row, below).

### The Source row (arc 19)

PDF-of-preview needed **no `:ext-pdf` change at all**: the host paginates and renders the editor's
Markdown *preview* into the existing `SOURCE_PAGES` `PageBundle`, so `:ext-pdf` receives ordinary
page pixels and assembles a PDF with no idea it was never a notebook's ink. What the Export screen
grows instead is the host-owned **Source** row from [The screen](#the-screen) above — Notebook
pages / Document — visible only when `hasDocument` is true **and** the selected exporter is
`SOURCE_PAGES` (`ExportDocumentRules.sourceRowVisible`); `documentSource` is forced `false`
whenever the row is off-screen, so switching to `SOURCE_DOCUMENT`, or to a notebook with no
document, can never leave a stale "render the document" choice armed.

Choosing Document renders `DocumentPdfRender` into the standard `PageBundle` at the notebook's own
page size: `ExportText.markdownOf`'s same read, laid out through `:markdown`'s `MarkdownParser` +
`MarkdownRenderer` and sliced page-by-page by `MarkdownPaginator` (M1), one `StaticLayout` per
page, RGB_565/WEBP-encoded the same way `ExportRender` encodes ink. **Document mode is plain
white, always** — the template-toggle row goes `GONE` in Document mode (paper under prose was
never built, and the phase-start answer declined it). Metrics are the editor Preview's own,
exactly: `DocumentPdfMetrics` mirrors `EditorPrefs`'s 16/16/16/32 dp padding, 1.15 line spacing,
8 dp block gap, and the saved text-size preference **plus `PREVIEW_BUMP`** (Preview's own step up)
— read from the editor extension's store **only if that store file already exists** (an export
must never mint one; an unopened editor's default applies otherwise). A notebook with nothing
written renders `Problem.NO_DOCUMENT` the same as the text exporter; a page row with no usable
size, more pages than the bundle carries, or a page that will not render still answer
`ExportRender`'s own `DAMAGED`/`TOO_LONG`/`RENDER_FAILED` sentences — `DocumentPdfRender` is a
second preparer with its own `Problem` enum, but `ExportMessages` maps both onto the same strings
(one table read four ways — see the [failure table](#failure-table)).

---

## The source-kind seam (arc 18)

The exporters differ in one structural way — some stream a file verbatim, one renders and
assembles a new one — and that difference had to become part of the contract, not just this
screen's private branching. `ExporterInfo` grew a compatible parcel tail, `sourceKind`:
`ExporterContract.SOURCE_SOIL` (absent on an old-shape descriptor means this, so every exporter
built before arc 18 kept its meaning on real wire), `SOURCE_PAGES`, or — arc 19 / M9 —
`SOURCE_DOCUMENT = 2`, declared only by a service whose manifest raises `API_VERSION` to 3, so a
pre-arc-19 host (still reading version 2) never even sees a descriptor shaped this way.
`ExportActivity` reads it once, right after `describe()` answers, and everything downstream —
which preparation runs, what verification means, whether keying chrome can even appear — follows
from that one value; the flow never asks a second time which kind it is dealing with. The full
write-up of the tail, the `PageBundle` container format, and the caps that bound it (`MAX_PAGES`
4096, `MAX_DIMENSION_PX` 32768, `MAX_PAGE_BYTES` 32 MiB) lives in [`docs/extensions.md`](extensions.md)
§ "The source-kind tail" — this doc only needs the consequence: `ExportRender` produces the bundle
for `SOURCE_PAGES` where `ExportArtifact.prepare` + `ExportKeying` produce the artifact for
`SOURCE_SOIL` and `ExportText.assemble` produces the file for `SOURCE_DOCUMENT` (or
`DocumentPdfRender` produces a `SOURCE_PAGES` bundle when the Source row picks Document — see
[The document exporter](#the-document-exporter-arc-19) above), and `ExportActivity`'s private
`StreamSource` sealed class (`Ready`/`Failed`) is what lets everything from the two fds onward stop
caring which one it got.

**Verification is per source kind** too (`ExportVerification`, pure — pinned by test). The
`bytesWritten == streamBytes` equality the arc-15 review built is a *verbatim-streaming contract*:
it holds for `SOURCE_SOIL` and, since arc 19, for `SOURCE_DOCUMENT` too (the host already
assembled the final bytes, so the document exporter is a byte-for-byte copier exactly like soil's),
and it would fail every honest `SOURCE_PAGES` export, because a PDF's size is never the bundle's.
So a `SOURCE_PAGES` verdict drops the source-length equality and keeps only the
destination-corroboration half: zero bytes reported is never a document (`Verdict.SHORT`
immediately), and otherwise the extension's own reported count is checked against **every answer
the destination provider will give** — the same "corroboration, not authority" rule step 8 above
already uses for soil, unchanged. `SHORT` (a failed export — the flow may delete wreckage under
its usual rules) stays distinct from `UNCONFIRMED` (the stream completed; only the provider's
metadata disagrees — a check-the-file dialog, never a delete) for every source kind alike. An
unknown `sourceKind` value is unreachable in practice (`ExporterInfo`'s unmarshal already rejects
it, dropping that exporter at discovery like any other bad descriptor) but still resolves to
`SHORT` rather than `OK` — verification never defaults to trust.

---

## Cloud destination (arc 25 / V3)

The Destination row on the Export screen is a second, orthogonal choice on top of everything
above: whichever exporter and options were picked, the finished bytes can go to a SAF document (as
every export before this arc did) or to the one installed cloud provider's own tree, through
`:ext-cloud` (**NSE · Cloud Storage**) on the eighth extension point, `ACTION_CLOUD_STORAGE`. No
exporter learns the difference — it still receives a write fd on a plain file and streams into it
exactly as before; only *where the host's fd points afterward* changes. The full seam — the
`ICloudStorage` interface, the provider's tree, the Backup screen's Connect door, the measured
`CloudTimeouts` — is [`docs/cloud.md`](cloud.md); this section is the export screen's own use of it.

**The row and the tap.** `ExportDestination` (`export/ExportDestination.kt`, pure, JVM-tested) is
the whole decision core: `rowVisible` (a trusted provider installed — GONE otherwise, never
disabled), `settled` (a standing *cloud* answer forced back to *local* the moment the row leaves,
the Source row's rule), and `onCloudTap` — what tapping the cloud radio does, given the last
`CloudStatus` the provider gave (null when it did not answer): a live connection selects it
outright; a build with no credentials (`!status.configured`) gets the Backup screen's own *not set
up* problem dialog; anything else — no account, or no answer at all — gets the **inline Connect
offer**, a two-button *Connect to `<provider>`?* dialog. Connect opens through
`extension/CloudConnectEntry` (registered in `onCreate`, since a launcher may not register later;
closed in `onDestroy`), and its callback re-runs discovery and selects the cloud radio when the
fresh status says connected. `status()` is re-asked at every discovery, never remembered across
resumes — the connect door changes it under a standing screen, and a stale "connected" would send
an export at a provider that has since been disconnected.

**Export, cloud branch.** The secret checks (empty/mismatched passphrase or password, the length
cap) run exactly as they do for a SAF export — they are about what is in the file, not where it
goes. Then, in place of `ACTION_CREATE_DOCUMENT`, `cloud/CloudBrowserDialog` opens in
`Mode.PICK_FOLDER` over `Exports/` (`CLOUD_EXPORTS_FOLDER`, `ExportActivity`'s own constant) — the
Contents dialog's shape: Up arrow, a `<provider> › Exports › …` breadcrumb, Cancel and a **Save
here** action button (top bar, after Cancel), paged rows with a pager-only bottom bar, and a first
*New folder…* row (`NameDialog` + `CloudArgs.requireName`) that either enters an already-listed
folder of that name or calls `ensureFolder` then enters. **The browser only ever `list`s** —
`Exports/` itself is created by the upload on its way through, never by browsing. The filename is
[`ExportNaming`](#the-flow-step-by-step)'s own, fixed rather than editable (there is no field to
type it into); if the chosen folder's last-drawn listing already holds a file of that name, a
*Replace `<name>`?* dialog stands in for SAF's overwrite confirmation, because an upload is
replace-by-name and a silent replace is not the family's way. `NOT_CONNECTED` closes the browser
straight into the Connect offer; a network failure or no answer leaves the browser exactly where it
was, over a problem dialog. Purely pure browser rules — crumb text, paging, the same-named lookups,
the New-folder outcome — live in `cloud/CloudBrowserRules.kt`.

**Prepare, key, upload, verify.** Steps 4–5 of [the flow](#the-flow-step-by-step) run completely
unchanged — the artifact is sealed, copied and keyed exactly as for a SAF export — except the
destination fd now opens `cacheDir/export/out.<ext>` instead of the SAF document (the exporter
never learns which). Verification against that cache file's own length is the same corroboration
step 8 already does. Only once that passes does the flow send the bytes:
`CloudClient.upload(path, name, mime, pfd(out), out.length())`, under `uploadBudgetMs(length)`
([`docs/cloud.md`](cloud.md) § timeouts), staged in the progress dialog as *Uploading to
`<provider>`…*. The provider's own reported size then corroborates against what was actually sent —
`ExportVerification.cloudVerdict(reportedBytes, uploadedBytes)` — agreeing is `Verdict.OK` and the
confirm dialog names the provider; disagreeing is `Verdict.UNCONFIRMED`, the arc-15 *check the
file* dialog, and **never a delete** — the same corroboration-not-authority rule the SAF
destination's own metadata check already lives by. **No remote delete anywhere in this phase**: an
upload is replace-by-name, so a retry after any cloud failure is always safe, and a failure before
the upload leaves the cloud completely untouched (the cache file is wiped in the flow's usual
`finally`, same as always).

**Failure rows (cloud).** Before the upload every existing failure message stands and nothing is
in the cloud, so each one's body says *nothing was uploaded*. After the tap: `CloudNotConnectedException`
→ *no account is connected to `<provider>`; connect and export again* (`export_cloud_not_connected_body`,
with a Connect offer from the dialog); `CloudNetworkException` → *`<provider>` could not be reached;
nothing was uploaded; try again* (`export_cloud_network_body`); a plain `ExtensionCallException` (no
answer, or a timeout) → *`<provider>` didn't answer; the file may or may not have arrived — check
`<provider>` before exporting again* (`export_cloud_unanswered_body`, no delete, because a retry
over replace-by-name is safe either way); the provider gone between the tap and discovery →
`export_cloud_gone_body`.

**Measured (Nomad, `DRIVE_PLAN.md` § V3 ledger).** `status` selecting the radio at 109 ms warm; a
folder listing over `Exports/` walked cleanly; a New folder at depth 2 (`ensureFolder`) took
1 790 ms; a 283 443-byte PDF uploaded and corroborated (`agrees=true`) in 2 775 ms, on both the
first upload and a same-named replace. `cache/export` was gone in every case checked afterward, and
the local (SAF) destination was unaffected — it still opens the ordinary picker.

**Design calls recorded at V3 (binding unless the user says otherwise).** Destination-row name
matches are exact; a New folder past `CloudContract.MAX_PATH_DEPTH` is refused in the pure rules
before any bind; a same-named *file* never blocks a New folder of the same name; the Up button is
`INVISIBLE`, not disabled, at the browser's base folder, and its no-op there is silent;
`Mode.PICK_FOLDER`'s `Pick.Folder` carries the listing it was drawn from, so the replace question
costs no second `list`; a provider gone between the tap and the upload gets its own
`export_cloud_gone_body` rather than the generic failed-export sentence; and — the one thing
recorded as **not yet done** — the Destination answer is **not remembered across screens**: a fresh
Export screen always defaults back to *File on this device*, even right after a cloud export. A
remembered destination is a future call, not made here.

**Trap met.** Opus's first pass named the cache file with a literal, unescaped `${…}` in its
extension (`"out.${'$'}{…}"`) — harmless to the upload itself (the cloud name is `ExportNaming`'s,
never the cache filename) but wrong; caught on the read-through and fixed before the phase's
install.

---

## Scope (arc 30 / PE2)

og's canvas "Page" menu exports one page; SN's Export was whole-notebook only, and its one door was
the library's long-press sheet. Arc 30 opened a second door and gave the screen a scope — and did
it **without touching the seam**: `ExportSpec` has no scope field and gains none, no exporter
descriptor changed, no `API_VERSION` bump. Scope is a **host-side page-id filter and nothing more**,
so every exporter — the three installed today and item 6's future image exporter alike — inherits
page scope without knowing it exists.

**The door.** The notebook's page sheet gained an **Export page** row (`ic_download`, present
only while a trusted exporter is installed — [`docs/notebook.md`](notebook.md) § Export page; since
2026-09-10 an **Export notebook** row, last on the sheet, walks the same door with no page id, so
this screen opens at whole scope with no Scope row, as from the library). It
is **close, export, reopen** (decision 5): the Export screen reads a *cold* `.soil` (`ExportOpen`
guard 2 refuses a held file — the cold-file invariant is untouched), so the notebook closes exactly
as for a Recents switch — drain, cover, bookmark, seal — and launches this screen from
`close(andThen)` with `EXTRA_PAGE_ID` (the displayed page) and `EXTRA_RETURN_TO_NOTEBOOK = true`.
The screen's leave paths were scattered (Back, the done dialog's dismiss, `problemAndClose`, the
cancelled passphrase prompt), so **`finish()` is overridden once**: with the return extra set and
not yet relaunched it starts `NotebookActivity.intent(id, name)` first, whatever the outcome —
exported, cancelled, refused, Back — and the notebook opens at its bookmark, which the close wrote
for the page the sheet was on (no `EXTRA_PAGE` on the notebook intent). A guard bounce finishes
before the flag is read. The library's intent carries neither extra and behaves exactly as before.

| Extra | Set by | Meaning |
|---|---|---|
| `EXTRA_NOTEBOOK_ID` / `EXTRA_NOTEBOOK_NAME` | both notebook doors | the notebook — never a `File` |
| `EXTRA_PAGE_ID` | the page-sheet door only | seeds `ExportScope.Page(pageId)`; absent or empty = `Whole` |
| `EXTRA_RETURN_TO_NOTEBOOK` | the page-sheet door only | `finish()` relaunches the notebook first |
| `EXTRA_CALENDAR_TARGET` (arc 31 / HV4) | the calendar door only, never beside the notebook extras | seeds `ExportScope.Calendar(target)`; a bad value is *no door at all*, never a guessed page |

These are all this screen's own extras. The calendar seam itself carries two more that decide
whether the door is even offered — `ExtensionContract.EXTRA_CALENDAR_EXPORT_ENABLED` (the calendar
Intent's fourth boolean, set by `CalendarEntry`) and `ExtensionContract.RESULT_CALENDAR_EXPORT = 3`
(the calendar's exit code when its own Export button is tapped) — both documented alongside
`ACTION_CALENDAR` in [`docs/extensions.md`](extensions.md), not here.

**`ExportScope`** (`export/ExportScope.kt`, pure, sealed `Whole` · `Page(pageId)`) owns the rules;
the screen owns the value (saved / restored, `KEY_SCOPE_WHOLE` — Whole is restorable only where the
row that offers it is, Page is the seed):

- `pageIds` — null at `Whole` (every render reads "no filter" as "all rows"), the one id at `Page`.
- `pagesInScope(rows, pageIds)` — the filter, applied to the DAO's already-ordered `TYPE_PAGE` rows
  so display order is untouched. `ExportRender.render`, `DocumentPdfRender.render` and
  `ExportText.assemble` (+ `markdownOf`) each take `pageIds: Set<String>? = null` and run it
  **before** their existing plan; the pure `plan()` seams are untouched. A page id the rows no
  longer carry (the page vanished between the sheet and the run) yields an empty list, which each
  render already refuses as its own `Problem.EMPTY`.
- `lists(sourceKind, scope)` — the one exporter rule scope adds: a `SOURCE_SOIL` exporter streams
  the whole `.soil`, and a one-page `.soil` would be a new copy-with-filter engine og does not offer
  either (decision 4), so at page scope **Soil is hidden, never disabled**. Page-bundle and document
  exporters serve both scopes. Flipping back to Whole notebook re-lists it.
- `offerable(sourceKinds)` — page scope is offered only when at least one installed exporter lists
  at it; a page door over a Soil-only install falls back to Whole with **no row** (a door with
  nothing behind it is GONE, not a latch to an empty chooser).

**On the screen.** The Scope control is the panel's own captioned radio row (`ExportPanel.choice`,
the Source / Destination idiom — not an `Editor.Latch`: one screen, one vocabulary, no XML button).
`described` (every renderable exporter) is kept apart from `candidates` (`listedNow()` = the
document gate against the **scope's** document answer + the Soil rule) so a flip re-cuts the list
without re-describing; `reselect()` = standing ?: remembered ?: first, so a remembered Soil at page
scope falls to the first shown. `hasDocument` is **derived from scope** — at `Page` it is that
page's own `document` row, so the Source row and the document exporter's gate answer for the page,
not the notebook. The one `readOnce` also answers `PageFacts(number, title, hasDocument)` for the
door's page. `exportPrefs.lastExporter` is still written on a page export (a format choice is a
format choice). The done dialog says "This page was exported." at page scope.

**What a page export holds.** A page-bundle PDF carries that page and **its endnotes only** —
`Endnotes`' sources are collected from the *baked* pages (`endnoteSources(dao, pages)`), so the
filter covers them with nothing more to do. A Document export at page scope is **that page's
document or nothing** — the notebook document (the merged draft, [`docs/document.md`](document.md))
is not read at page scope; the page-template toggle, password protection and the destination all
work as at whole scope.

**The filename** (`ExportNaming.pageStem` / `fileName` / `specNameOf` — one stem feeds both the
file on disk and `ExportSpec.notebookName`, so the two always agree): `<notebook> - <heading>.<ext>`
when the page has a heading by the Contents / link-picker rule (`PageLabels.titleOf` — topmost by
`(y, x)`, prefix stripped, loose or link-wrapped), sanitized by the same rule as the notebook name
and capped at 80 characters (`MAX_TITLE_CHARS`); else `<notebook> - page N.<ext>` with the page's
1-based position; a page that cannot be placed names the notebook alone (a filename must never say
"page 0"). A plain ASCII hyphen throughout, because the sanitize strips every other dash. At Whole
the name is the notebook's, suffix-free, as before. Pure, tested (`ExportScopeTest` 12 — arc 34 /
L15 added the notebook-relative `ScopedPage.number` assertions, `ExportNamingTest` +6).

**What did not change.** `ExportVerification` (bytes are bytes), every exporter, `ExportSpec`, the
seam, the keying flow, the cloud leg. The library door: no Scope row, Soil listed, whole notebook.

## Images (arc 31 / HV1)

**`NSE · Image Export`** (`:ext-image`, the fourteenth module, `:extension-api` only) is a fourth
exporter on the same point: `formatLabel` "PNG image", `fileExtension` "png", `mimeType`
"image/png", one option (`OPTION_PAGE_TEMPLATE`, host-executed exactly as PDF's — the bundle
carries finished pixels, so there is nothing left to add or strip afterwards), `sourceKind =
SOURCE_PAGES` (a host-rendered page bundle, PDF's own shape), and `bundleVersion =
PageBundle.VERSION_1` **on purpose** — a PNG is one picture with nowhere to put an endnote link, so
declaring 1 tells `ExportRender` to plan none. There is no password option and no keying option: a
PNG has neither.

**The one new thing a raster exporter needs is `ExporterInfo.delivery`** — the third compatible
parcel tail, right after `bundleVersion` (same `dataAvail()`-gated read as `sourceKind` and
`bundleVersion` before it): `ExporterContract.DELIVERY_ONE_FILE` (absent = this, so every exporter
built before this arc keeps its meaning on real wire) or `DELIVERY_PER_PAGE`. It says *how many
files* one export produces, never *what* it produces — the seam's `export(source, destination,
spec)` call is still one call, one file, whatever the tail says; what changes is what the **host**
does around those calls. `ExportDelivery` (pure, JVM-tested) is the whole of the host's reading of
it: `delivery(apiVersion, info)` reads the tail **only from a service declaring at least
`ExporterContract.MIN_API_VERSION_FOR_DELIVERY` (9)** — below that it is always `DELIVERY_ONE_FILE`
whatever the parcel carried, so the declaration and the tail can never disagree about what the host
will do (the D3 skew-guard recipe, read from the host's side). `perPage(delivery, scope)` is per-page
**at more than one page**: at `ExportScope.Page` there is exactly one page, so a per-page exporter is
a single file through the ordinary picker and nothing about the flow changes; at `ExportScope.Whole`
it is always the folder case, **deliberately including a one-page notebook** — Whole = folder is a
rule the person can hold, and a scope that sometimes made a folder and sometimes a file would not
be. `ExportScope.Calendar` counts rather than assumes: a Day target draws two pages and goes to a
folder, a Month or a Week draws one and goes through the ordinary picker (see
[Calendar mode](#calendar-mode-arc-31--hv4) below).

**`BundleSplit`** (pure, over streams, JVM-tested) is the host's splitter: the render bakes
**once** (`ExportRender`/`DocumentPdfRender`, unchanged) and `BundleSplit.split` reads that one
bundle back a page at a time, writing each as its own **version-1, one-page** bundle into
`cacheDir/export/` (`page-1.pages` … `page-N.pages`) — so the exporter still receives exactly what
its contract says it may: a bundle of one page. Any version in, version 1 out (a v2 bundle's link
trailer is dropped — links jump between pages of one document, and a one-page file has nowhere to
jump to); one page alive in memory at a time on both sides; the pixels themselves are never
re-encoded, only re-framed into a new container. `ExportRender.Outcome.Ready.pageNames`
(`PageLabels.titleOf`, read beside the page's content in the same bake, not a second `readOnce`)
carries one `ExportNaming.PageName` per bundle page — the page's notebook number, its heading if it
has one (else null, which per-page naming falls back from), and since arc 43 / K7 whether the page
is that page's sketch.

**On the screen.** `Destination` grows two cases beside the SAF document: `SafTree(uri)` (a second
`treeLauncher` over `ACTION_OPEN_DOCUMENT_TREE`, **no persistable grant** — the tree is used once,
for this export, and never held past it) and `CloudFolder(path)` (the same folder pick the ordinary
cloud export uses). `exportPerPage` is the per-page flow: split the bake into parts, **hold one
bind** to the exporter (`ExporterClient.hold`, arc 34 / M9a — a bind that fails is the export
failing before any page, nothing created; the bind is closed in `finally` whatever the loop did),
then for each page (`exportPerPageHeld`) — build the stem (`ExportNaming.pageStem`, per-page
delivery's own filename per file) and spec, `DocumentsContract.createDocument` on the tree or a
cache file for the cloud leg, call `Held.export()` (the same per-call `EXPORT_TIMEOUT_MS` and the
same fd rule as the one-file `export()`), verify the result, upload if cloud, move to the next. `SHORT` on any page deletes that
one document and stops the whole run; `UNCONFIRMED` stops with the check-the-file dialog; every
early stop's dialog leads with **"N of M images were exported."** (`export_done_images_partial`,
a plural). `exportPrefs.lastExporter` is written only after **every** file finished — a run that
stopped partway did not "use" the format the way a completed export did. The progress dialog counts
pages (`export_exporting_image`, "Exporting image %1$d of %2$d…").

**The cloud-folder confirmation is the one deviation from the design (D1).** The plan called for a
count in the confirmation ("N files will be uploaded…"), but the page names are not known until the
bundle has been baked and split, and asking after the bake would interrupt the progress dialog with
a question. So the confirmation is asked **once, always, before any work runs**, and names no
count: *"Each page will be uploaded as its own image. Files with the same names will be replaced."*
(`export_cloud_folder_title`/`_body`, `export_upload_confirm` "Upload") — the same shape as every
other cloud confirmation on this screen, just earlier in the flow than the plan assumed.

**What this needed elsewhere.** `ExporterInfo`'s own constructor refuses `DELIVERY_PER_PAGE`
declared on any `sourceKind` but `SOURCE_PAGES` — an unmarshal refusal, never a second check the
screen has to run at describe time — and `ImageExportSpec` (`:ext-image`) refuses any unknown
option id and any secret, the same rule `PdfExportSpec` already enforces. `ImageAssembly` is the
extension's own read of the bundle: `requireOnePage` throws if the host ever hands it more than
one page (a host that cannot split reaching this service at all, since `MIN_API_VERSION_FOR_DELIVERY`
is meant to keep that from happening), the page decodes RGB_565 (matching the host's own bake) and
is dimension-checked against the bundle's own declaration before a byte is trusted, `compress(PNG,
100)` runs through the same counting-stream-plus-`S_ISREG`-fsync delivery `PdfAssembly` and
`SoilStreams` already use, and every `IOException` surfaces as an `IllegalStateException` naming the
stage ("reading the page bundle" / "writing the PNG"). Full boundary detail —
the delivery tail's wire shape, the caps, the trust rules a new exporter must meet — is
[`docs/extensions.md`](extensions.md).

**Nomad walk (Sonnet over adb, 2026-09-08) passed:** the library door exported five PNGs named by
heading or `page N`, a repeat run appended ` (1)` from the provider; the page-sheet door's
single-page export used the ordinary picker (`Objects - page 5.png`, ` (2)` on a third collision);
the template-off toggle exported; PDF was unaffected; a cloud folder export ran the
once-always confirmation then five uploads. `:app` 1501, `:ext-image` 15, 2877 tests total.

## Presets (arc 31 / HV3)

A **preset** is a named, saved answer to every question the Export screen asks *except the scope*:
the exporter, its option values, the host's Source answer, the Destination, and — for the cloud —
the folder. It is the first thing in the panel, above Scope and Format, because one tap on it
answers everything below.

**The row.** `@id/presets` holds a caption *Preset*, a radio *None*, one radio per listable preset,
and a code-built *Save preset…* text button (`Widget.Notesprout.TextButton`'s look set field by
field — the style sets no `layout_width`, so the panel builds it, as it builds every row). The
caption and radios are **absent with nothing to list** (GONE, never disabled); the Save button is
present whenever a format has been described. Tapping the checked radio is a no-op (the chooser's
rule); tapping *None* moves the tick and nothing else — *None* means "no preset is armed", never
"reset the form". A long press on a preset radio (`ExportPanel.choice`'s `onLongPress`, set only
where given) opens an `ActionSheetDialog` titled with its name: *Rename…* / *Delete…*. Names follow
`NameRules`; a duplicate among alive presets is refused with *Name already used* and the name
dialog stays up; delete confirms first. Every view and dialog lives in `export/ExportPresetRow`
(the activity is over the ~800-line rule with a reason that is entirely about the flow) and the
activity keeps only its side of `ExportPresetRow.Host`: `currentState` / `listedPackages` /
`cloudAvailable` / `applyPreset` / `presetsChanged`.

**The row in the index.** `ObjectType.EXPORT_PRESET = "export_preset"` — the additive-row pattern
of `naming` / `clipboard` / `backup`, so the Room identity hash (the format contract with Paper) is
untouched and a preset rides backup and restore with everything else. `id` = a fresh UUID · `name`
= the preset's name · `parentId` null · `flags` = grammar version 1 · `blob` = the kotlinx JSON of
`data/export/ExportPreset` (`version`, `exporter` = the **package name**, `values`,
`documentSource`, `destination` = `"LOCAL"` | `"CLOUD"` as a string, `cloudPath` = names under the
provider's root or null). Soft-deleted on delete; `updatedAt` bumped by rename only (the sacred
rule). `IndexRepository.exportPresets` reads `allAliveRowsOfType` (the one blob-carrying listing —
the preset *is* its blob, a few hundred bytes) in name order and **skips** any row `ExportPreset.decode`
cannot vouch for: null bytes, malformed JSON, a version above 1, an unknown destination string, a
blank exporter. One preset missing from a radio list is a small loss; a screen that crashes on a
bad blob is not.

**Never the secret, never the scope.** A rekey passphrase or an export password is never in
`values`: applying a preset merges its values through `ExportOptions.specValues`, which keeps only
*declared choices* of the live descriptor, and a secret is never a declared choice — the filter is
structural, not a blacklist. A preset for a protected export applies with the password fields
**empty**, for the user to type. The scope is the door's question (`EXTRA_PAGE_ID`), and a saved
answer could only contradict it.

**Listing, applying, dropping** (`export/ExportPresets`, pure, JVM-tested). `listable` keeps a
preset only when its exporter is among the screen's *candidates* — already cut by installation
**and** by scope, so a preset whose exporter is disabled is hidden until it is re-enabled, and a
Soil preset is hidden at page scope. Re-read at every discovery (`reload`) and re-cut without a
read when the Scope row flips (`recut`). `apply` answers the state to adopt and the one thing it may
overrule: a preset naming the cloud with no connected provider applies as **Local** with a toast
(`cloudFallback`), the folder kept for the account's return. The activity writes the state under
an `applyingPreset` latch — every write goes through the same fields a tap would use, and the
latch is what tells `handChanged()` this hand is the screen's own. **Any hand change** — a format,
Source, option, Destination or folder tap — drops the tick to *None*. A Scope flip is **not** a hand
change: the armed preset stays armed unless the new scope hides its exporter. The pick survives a
rebuild behind the picker with the format pick (`KEY_PRESET`).

**The cloud folder became screen state.** Before this phase the folder was chosen in the browser at
the Export tap; a preset that "includes the folder" needs the screen to know it before the tap.
`cloudPath` (null = *chosen at export*, every cloud export's behaviour before this arc and still the
default) is shown under the checked cloud radio as a value row *Folder: Exports › …* /
*Folder: Chosen at export* (`ExportDestination.folderLabel`) whose tap opens the same
`CloudBrowserDialog` in `PICK_FOLDER` (the export tap's `browse(onFolder)` helper, shared) and only
records the answer — no busy latch, no export. Saved with the pick (`KEY_CLOUD_PATH`), kept across a
flip to Local for the flip back, forced to null only when the Destination row itself leaves. At the
Export tap with a folder remembered the browser is **skipped**: `listThenExport` reads one
`CloudClient.list` behind *Checking the folder…* so the *Replace <name>?* question can still be
asked, then continues exactly as the browser's pick would. Not-connected and network are the
browser's own two answers (the Connect offer; *nothing was uploaded*). **Any other refusal does not
stop the export** (the phase-start call): the folder may have been moved or removed since the
preset was saved, and `upload` creates its folders on the way past and replaces by name — so the
path is applied and the upload's own failure, if any, explains. Only the collision question goes
unanswered then, and its worst case is the replace that would have happened anyway.

**What did not change.** `ExportSpec`, every exporter, the seam, `ExportPrefs.lastExporter` (still
written on every finished export, preset-driven or not), the keying flow, the verification.

## Calendar mode (arc 31 / HV4)

The calendar (`docs/calendar.md`) is the third door onto this screen, and the first that opens no
notebook at all. Every calendar view's bar grew **one out-door button** — `CalendarToolbar` picks
its face by what is open: Send alone, Export alone (`ic_download`), or (both installed) an
`ActionSheetDialog` offering *Send page* / *Export…* — because a twelfth 62 dp top-bar button does
not fit the Nomad (11 × 62 dp + margins already comes to 726 of 749 dp with Send and the pad
showing; see [Traps recorded](#traps-recorded) below). Export calls `exportPage()`, which parks the
current view's target (a Day parks itself; both halves are drawn regardless — see below) and exits
with `ExtensionContract.RESULT_CALENDAR_EXPORT` (= 3). `ExtensionScreenEntry.onExport` reads the
parked target off the held bind **before** `finish()` (the drain's own shape) and, on the host side,
`CalendarEntry` sets `ExtensionContract.EXTRA_CALENDAR_EXPORT_ENABLED` — the Intent's fourth boolean
— only when the installed calendar declares `API_VERSION` ≥
`ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_RENDER` (9, a **method** floor — `MIN_API_VERSIONS`
itself is untouched, since `ACTION_CALENDAR`'s own floor was already 7) **and** some exporter is
installed at all — the arc-30 door's own rule, so the screen never offers a button that opens onto
nothing.

**The door.** Both callers (`LibraryActivity`, `NotebookActivity`) answer `onExport` the same way:
start `ExportActivity.intent(context, calendarTarget)` — a second, calendar-only overload beside the
notebook one, carrying **no** `EXTRA_NOTEBOOK_ID`/`EXTRA_NOTEBOOK_NAME` — and latch
`reopenCalendarAfterExport = true`, consumed in `onResume` on whichever screen opened the calendar
(the calendar itself is closed by the time the Export screen shows; a process death between the two
loses the latch, and the calendar simply stays closed rather than reopening to a stale state).
`EXTRA_CALENDAR_TARGET` — `ExportActivity`'s own host-internal constant, wire form
`"<kind>/<date>/<half>"` (three numbers and an ISO day, never content, never a secret) — is present
instead of the notebook extras, never beside them; `parseCalendarTarget` treats a bad extra as *no
door at all* rather than guessing at a page, the same honesty the Scope door already shows a bad
`EXTRA_PAGE_ID`.

**Calendar mode is a different screen shape, not a different flow.** `calendarMode` (`calendarTarget
!= null`) gates every notebook-only piece off: **no `.soil` is opened** (this is the `ExportOpen`
guard-2 rule's other side — there is no file to hold cold in the first place), no Scope row (the
door's target *is* the scope, never the person's choice — see `ExportScope.Calendar` below), no
Source row (a calendar page is ink, not text; nothing to choose between), no keying chrome (no file
means no key). The header names the period instead of a notebook: `export_calendar_line`,
*"Calendar · %1$s"*, filled from `CalendarRenderPlan.of(target, false).label` — the month, the week,
or the day **without** the ` · AM`/` · PM` tail `CalendarDates.dayTitle` normally carries (an export
of a day is the whole day, so the header naming one half of it would contradict the two files the
run is about to write).

**`ExportScope.Calendar(target)`** is the third `ExportScope` case, sealed beside `Whole` and
`Page` — and the odd one out: its `pageIds` is null **and unused**, because nothing here reads a
`.soil`'s rows at all. `ExportScope.lists(sourceKind, Calendar)` is `sourceKind ==
ExporterContract.SOURCE_PAGES` only — a `.soil` exporter streams a notebook file and there is no
notebook here, a document exporter assembles what was *written* and a calendar page is ink, so both
are hidden, never disabled, exactly like Soil at page scope.

**`CalendarRenderPlan`** (pure, JVM-tested) is the whole of the arithmetic on the host's side, built
from the one `CalendarTarget` the door carried:

- **A Day is two pages, AM then PM** (`CalendarRenderPlan.pages` = 2 for `KIND_DAY`, else 1) —
  a day *page* on the calendar is one half, but a day *exported* is the day; nobody asks for half a
  Tuesday, so both are drawn in reading order regardless of which half the door came through, and a
  per-page exporter writes two files.
- **The flags are settled, not asked** (the phase-start call, which overrode the planner's
  ring-off default): ink, the today ring, and the day marks are **always** drawn on a file export;
  only the grid rides the exporter's own page-template toggle — the same question the notebook's
  pages answer with the same control, so the screen keeps one row and one label for both purposes.
- **`stems`** names one file per page (`ExportNaming.calendarStem(target)` plus ` AM`/` PM` for a
  Day's two) and **`singleStem`** names the one file a one-file exporter writes. `ExportNaming
  .calendarStem`: `Calendar - September 2026` for a month, `Calendar - Week of 2026-09-06` for a
  week (the target's own date, already that week's Sunday), `Calendar - 2026-09-08` for a day — a
  plain ASCII hyphen and letters/digits/spaces throughout, so the string is legal by construction
  rather than by the sanitize trimming it. There is no notebook and no user-typed word in a
  calendar filename at all.

**`export/CalendarRender`** (host) is the **fourth bundle producer**, beside `ExportRender`,
`DocumentPdfRender` and `ExportText` — and the only one that does not draw pixels itself. It *asks*:
`CalendarClient.render` binds per call (the store lent for the call, the tag manager's second call
shape) and writes into a file in `ExportArtifact.freshDir` — the one export cache directory every
producer shares and `runExport`'s `finally` wipes. Bytes back from an extension are **untrusted**
regardless of the door they came through: the bundle is opened with `PageBundle.Reader` and read to
its end before a byte is handed on, which is what bounds-checks the page count, every page's
dimensions and its image length against the container's own caps; `CalendarRender.verify` adds the
one thing the container cannot know on its own — the bundle must carry **exactly** the plan's own
page count and, being version 1, **no** link trailer. A failure names nothing more than one sentence
from the resources (`export_calendar_failed_body`, "The calendar could not draw the page.") — no
path, no exception text; the class name goes to the log alone. `Outcome.Ready(file, bytes,
pageTitles)` carries the plan's own stems as page titles, since a calendar page has no heading to
fall back on the way a notebook page's `PageLabels.titleOf` does. `renderedCalendarPages` is
`runExport`'s **first** branch — it answers before every other question, because in calendar mode
it is the only question. `ExportDelivery.perPage` is widened to cover it: a Day (two pages) goes
to a folder, a Month or a Week (one page) goes through the ordinary picker — "a Day is two files" is
a fact about the day, not a scope the person had to choose, unlike `ExportScope.Whole`'s folder
rule for a notebook.

**On the seam.** `ICalendar` grows two appended methods (existing transaction codes unchanged):
`render(store, targets[], widthPx, heightPx, flags, destination)` (`destination`, not `out` — an
AIDL keyword) and `outgoingTarget()` (the parked whole-page-send's or export request's page; null
after a selection send — see `docs/calendar.md` § Notebook → calendar for the send side).
`ExtensionContract` gains `RENDER_GRID`/`RENDER_INK`/`RENDER_RING`/`RENDER_MARKS` + `RENDER_ALL`,
`RENDER_MAX_TARGETS = 8`, and `CALENDAR_RENDER_TIMEOUT_MS` — see [Timeouts](#timeouts) below for
the measured number. `:ext-calendar`'s manifest moved 7 → 9 for this; the render itself draws
through g-paper's public `StrokeRasterizer.draw` (the same door `ExportRender.bakeEndnote` already
uses — no separate stroke painter was needed) at the **full page** (arc 33 / F4 — the screen's own
grid is full page under floating bars, so the render agrees with it).

**What a page export holds, and what it never opens.** A calendar file export never touches a
`.soil`, never asks for a passphrase, and never appears in a notebook's own export history — it is
purely the calendar extension's own pages, rendered fresh on every request. `PageFacts` is absent
in calendar mode; `hasDocument` is false; the done dialog says **"The calendar was exported."**

**Nomad walk (Sonnet over adb, 2026-09-09) passed 9/9**, plus a second pass after the inset fix:
the library door's Export button opens calendar mode with PNG and PDF only, no Scope/Source rows,
the template toggle present; a Month PDF via SAF produced one page with the grid, the ring on
today, glyphs on the marked days, and the ink, then the calendar reopened at its bookmark; a Day
as PNG produced a folder pick and two files (`Calendar - 2026-09-09 AM.png` / ` PM.png`); the
template toggle off produced one PDF on a white ground; Back without exporting returned cleanly;
the notebook door's sheet offered *Send page* / *Export…* and Export opened the same screen over
the notebook. Not walked: the cloud leg for a calendar export (the Destination row is unchanged,
and HV1 already walked the N-file upload). `:app` 1560, `:ext-calendar` 295, `:extension-api` 231,
2941 tests total.

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

**`CALENDAR_RENDER_TIMEOUT_MS` (arc 31 / HV4) is its own value, not the export timeout reused.**
`ICalendar.render` is a Binder call the seam's own `ACTION_CALENDAR` rules govern, not
`ExporterContract`'s — sized against the Nomad's own measurements rather than assumed to fit inside
`EXPORT_TIMEOUT_MS`: a Month with 46 strokes plus the ring and marks rendered in **1012 ms** (client
side 1065 ms, a 41 KB bundle); a Day pair with no ink rendered in **1600 ms** (1724 ms, 60 KB). Set
to **30 s** — under `RENDER_MAX_TARGETS` (8) at ten seconds even on a cold store, roughly triple the
slowest measured case — well short of the soil/PDF timeout's 120 s, because a calendar render is one
page's worth of ink and template, never a whole notebook's.

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

## Encryption (arc 26)

Every export needs its **source** notebook's own key before it needs anything else, and that
happens once, up front: `ExportActivity.resolveSourceKey` runs at the head of `loadCandidates`,
prompting (`NotebookPassphrasePrompt`) if the source is `NOTEBOOK`-scope, before a single exporter
is even asked to `describe()`. The resolved key (`sourceKey`) then threads through every read path
downstream instead of being re-resolved: `ExportOpen.readOnly(…, resolved)` is the shared door
`ExportArtifact.prepare`, `ExportRender.render`, `ExportText.assemble` and `DocumentPdfRender.render`
all stand on, and each declares its own `Guard.LOCKED` → `Problem.LOCKED` for the case a caller
skipped the prompt. A new export path that opens the notebook itself must thread `resolved` the
same way, or it reintroduces a silent-lock read this arc closed.

`keyedArtifact` (the soil-path exporter's own read) hands `ExportKeying` the **source** file's
passphrase — the typed value for a `NOTEBOOK` source, the session key for `GLOBAL` — because the
keying trio above (Keep / New passphrase / Remove encryption) reads the sealed `.soil` under
whatever key it is actually under, not the device default. The Keep row's label is
host-substituted for a `NOTEBOOK` source: "Keep encrypted (this notebook's passphrase)" in place of
the `GLOBAL` wording, via `optionLabel` — `:ext-soil` itself is untouched, since the exporter only
ever sees a choice id. `notebook_meta`'s restamp (arc 15's own rule, above) now sources its scope
from the index row rather than assuming `GLOBAL`, so a `NOTEBOOK` source's Keep copy still
describes itself honestly. Full model, the resolver, every open site and the failure table:
[`docs/encryption.md`](encryption.md).

## Failure table

| What happened | What the user gets | Where |
|---|---|---|
| No trusted exporter installed | problem dialog, "Nothing to export with" — the screen closes | `ExportActivity.discover` → `problemAndClose` |
| A `describe()` call fails, or the descriptor is over the `ExporterContract` caps | that exporter dropped with a log line — never shown, never a crash | `ExportActivity.describe` / `loadCandidates` |
| A descriptor declares an option kind this build cannot draw (a free-standing passphrase kind), or a reserved keying option with a choice id the host has no transform for | that exporter dropped the same way — an unexecutable keying surfacing at export time would be explained as the wrong failure | `ExportOptions.isRenderable` |
| No app on the device can create a document | problem dialog, "No file picker" | `onExportTap` |
| A preset name is empty / reserved / off-charset, or already used by an alive preset (HV3) | problem dialog over the name dialog, which stays up | `ExportPresetRow.askSaveName` / `askRename` → `NameRules`, `nameTaken` |
| A preset row's blob will not decode (HV3) | that preset is skipped from the row — never shown, never a crash | `IndexRepository.exportPresets` → `ExportPreset.decode` |
| A preset names the cloud but no provider is connected (HV3) | applied as Local; toast "No cloud account is connected — exporting to this device instead" | `ExportPresets.apply` → `cloudFallback` |
| The remembered cloud folder will not list — not connected (HV3) | the Connect offer; nothing uploaded | `listThenExport` → `CloudNotConnectedException` |
| The remembered cloud folder will not list — no network (HV3) | problem dialog naming the provider; nothing uploaded | `listThenExport` → `CloudNetworkException` |
| The remembered cloud folder will not list — any other refusal (HV3) | the export proceeds with no collision check; the upload's own failure explains | `listThenExport` → `ExtensionCallException` → empty listing |
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
| The page-sheet door's page is gone by the time the export runs (arc 30 / PE2) | the same "no pages" refusal — the scope filter yields nothing; the notebook still reopens after | `ExportScope.pagesInScope` → `Problem.EMPTY` |
| A page row carries no usable size — a damaged or foreign-written file (`SOURCE_PAGES`, D3; also `DocumentPdfRender.Problem.DAMAGED` in Document mode, arc 19) | problem dialog, "could not be read at its own size" — its own sentence, **never** the memory-or-space one: a data problem blamed on storage would be retried forever | `ExportRender.Problem.DAMAGED` → `export_damaged_body` |
| More pages than the bundle carries (> 4096, `SOURCE_PAGES`, D3; also `DocumentPdfRender.Problem.TOO_LONG` in Document mode, arc 19) | problem dialog, "more pages than this format can carry" | `ExportRender.Problem.TOO_LONG` → `export_too_long_body` |
| A page will not allocate, draw or encode — low memory or space (`SOURCE_PAGES`, arc 18; also `DocumentPdfRender.Problem.RENDER_FAILED` in Document mode, arc 19) | problem dialog, "could not be prepared for this format"; the notebook itself is untouched | `ExportRender.Problem.RENDER_FAILED` → `export_render_failed_body` |
| Nothing has been written in this notebook at all — no notebook document and no page document (`SOURCE_DOCUMENT`, or a Source-row Document pick under `SOURCE_PAGES`; arc 19 / M9) | problem dialog, "no document to export"; an honest refusal, never an empty file — unreachable through the chooser in the ordinary case, since a document-less notebook never lists the document exporter | `ExportText.Problem.NO_DOCUMENT` / `DocumentPdfRender.Problem.NO_DOCUMENT` → `export_no_document_body` |
| The screen was rebuilt behind the picker and the chosen exporter is gone | problem dialog naming the format unavailable; destination per the deletion rule (nothing was written — a pre-existing overwrite target is left untouched) | `reselectAfterRestore` → `export_gone_body` |
| The `ExportSpec` itself is rejected (a value out of bounds) | problem dialog; destination per the deletion rule | `runExport` catch on `ExportSpec` construction |
| Rekey was armed but the typed passphrase was lost (screen rebuilt behind the picker — `saveEnabled=false` wiped the fields) | **honest "passphrase was lost" dialog — never a silent Keep**; destination per the deletion rule | `ExportKeying.plan` throws `IllegalArgumentException` → `export_passphrase_lost_body` |
| Protect was armed but the typed password was lost the same way (arc 18 / D2) | **honest "password was lost" dialog — never a silent unprotected export**; destination per the deletion rule. Fails **closed** (D3): the guard consults the raw tap-time `protect = 1` as well as the re-described descriptor, so an exporter upgraded in place behind the picker that dropped the protect toggle still refuses rather than silently exporting unprotected | `runExport` (`armedAtTap`) → `export_password_lost_body` |
| The keying transform itself fails (plain or rekey, `SOURCE_SOIL`) | problem dialog, "could not be converted for this export"; destination per the deletion rule | `ExportKeying.apply` throws → `export_transform_body` |
| The `export()` call fails, times out, or the exporter dies mid-stream — including a PDF page that won't decode, decodes at the wrong size, an inconsistent spec (`PdfExportSpec.require`), or any assembly `IOException`, each an `IllegalStateException`/`IllegalArgumentException` naming the stage | problem dialog, "didn't finish writing the file"; the truncating open already ran, so the wreckage is removed (best-effort, reported honestly) | `ExporterClient.export` throws → `export_failed_body` |
| The byte count the extension reports doesn't match what was streamed (`SOURCE_SOIL`/`SOURCE_DOCUMENT`), or is zero or disagrees with the destination (`SOURCE_PAGES`) | problem dialog, "Only part of the notebook reached that file"; wreckage removed (best-effort, reported honestly) | `ExportVerification.verdict` → `SHORT` → `export_short_body` |
| The stream completed but every answer the destination provider gives disagrees with it (either source kind) | *check-the-file* dialog, **no delete** — metadata can lag a write it just took, and a fully-written export is never destroyed over a stale answer | `ExportVerification.verdict` → `UNCONFIRMED` → `export_verify_body` |
| Back / the back arrow tapped while an export runs | "Export in progress" dialog; the flow continues untouched | `showBusyGuard` (`export_busy_body`) |
| A `SOURCE_PAGES` exporter's `bundleVersion` is below `PageBundle.VERSION` and the notebook holds a note with content (arc 28 / H6) | no dialog — an honest one-line caption under the options, `export_endnotes_unavailable`; the export still runs, notes go out as icons only | `ExportDocumentRules.endnotesUnavailable` → `ExportActivity` |
| A live sticky note has no strokes (arc 28 / H6) | not an error — `SoilDao.stickyIdsWithContent` never names it, so `Endnotes.plan` never sees it and no page is spent on an empty note | `SoilDao.stickyIdsWithContent`, `ExportRender.endnoteSources` |
| The endnote pages push the total past `PageBundle.MAX_PAGES` (arc 28 / H6) | same as any other over-long bundle: problem dialog, "more pages than this format can carry" | `ExportRender.Problem.TOO_LONG` → `export_too_long_body` |
| A link in the trailer names a page outside the bundle's own declared count or height list (arc 28 / H6 — a foreign or damaged bundle) | refused rather than silently dropped: `IllegalArgumentException`/`IOException` naming the pages, surfacing as the ordinary assembly failure dialog | `PageBundle.Reader.readLinks`, `PdfLinks.annotations` → `export_failed_body` |
| A per-page image export's cache split itself fails (nothing created anywhere) (HV1) | problem dialog, "didn't finish writing the file" | `exportPerPage` → `BundleSplit.split` failure |
| A page in a per-page image run reports short, times out, or fails to verify (HV1) | problem dialog leading with *"N of M images were exported."*, then the page's own failure sentence; that one page's document is deleted (SHORT), everything before it stands | `exportPerPage` → `stopPerPage` → `ExportVerification.verdict` → `SHORT` |
| The destination provider's own count disagrees after a per-page page streamed cleanly (HV1) | *check-the-file* dialog leading with the same *N of M* prefix, **no delete** | `exportPerPage` → `ExportVerification.verdict` → `UNCONFIRMED` → `export_verify_body` |
| The cloud folder's once-always upload confirmation (HV1) is dismissed or Cancelled | nothing happens — the same as cancelling the SAF picker; nothing was created, nothing uploaded | `confirmFolderThenExport` → `cancelledAtThePicker` |
| The calendar's `render()` call fails, times out, or the bundle it returns is the wrong page count or carries a link trailer (arc 31 / HV4) | problem dialog, "The calendar could not draw the page." | `CalendarRender.render`/`verify` → `export_calendar_failed_body` |
| The calendar is no longer installed by the time the render runs (HV4) | problem dialog, "The calendar is no longer available on this device, so there is nothing to draw."; the screen closes | `renderedCalendarPages` → `export_calendar_gone_body` |
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
- **A plan sentence and a pinned verification requirement contradicted each other at M9** — the
  arc-19 plan's "the extension strips via `:markdown`" could not coexist with "the text stream is
  verbatim," which the seam had already committed to (`ExportVerification` needed
  `SOURCE_DOCUMENT` to hold the soil equality). Resolved in the verification's favour: the strip
  moved host-side, into `ExportText`/`ExportDocumentRules`, before the extension ever sees the
  bytes. Worth remembering the next time a plan blurb and an already-pinned contract disagree —
  the contract wins.
- **Known cosmetic, not fixed (arc 19 / M9):** with the document exporter selected, the chooser's
  own format caption and the document exporter's `OPTION_TEXT_FORMAT` radio are both labelled
  "Format" — two "Format" captions stack in the options panel. Recorded at M9's checklist, left as
  is.
- **A `PageBundle.Link` must be built only after the endnote's size clamp** (arc 28 / H6). Building
  it from the row's raw carried size and clamping the note's page afterwards produces a caption-strip
  link rectangle that no longer matches the page that was actually written, which the writer's own
  `pageCount` bounds check will not catch (the rect, not the page number, is wrong) — the trap
  surfaced while writing `Endnotes.plan`, which is why `contentSize` runs first and every `Link` in
  the plan is built from its already-clamped `w`/`h`.
- **HISTORY, superseded by arc 33 / F4 — a calendar render's insets are the screen's own bar
  insets, not zero** (arc 31 / HV4). The design's phase-start plan called for rendering at insets 0
  — a full-page grid, ink drawn edge to edge. The first Nomad walk showed the ink landing one
  bar-height low against that full-page grid: the ink was written against the grid the calendar
  **screen** actually drew, which sat under the top bar, not against a grid starting at the page's
  own top edge. HV4's fix rendered at **the screen's bar insets** (`CalendarBars`:
  `toolbar_bar_thickness` plus a `calendar_bar_rule` hairline dimen, which the layout's own two
  hairlines also referenced, so the screen and the render could never drift apart) — the exported
  page carried blank bands where the bars were, matching what a person actually wrote on. **Arc 33
  / F4 removed the insets from `CalendarGeometry` on both sides and deleted `CalendarBars`** — the
  screen's own grid is full page now, under floating bars that come and go on a double-tap, so
  there is no longer a screen-drawn inset for the render to match: a calendar render is the
  full-page grid edge to edge, on screen and in every export alike. The `calendar_bar_rule` dimen
  stays for the layout's bar hairlines. The trap as HV4 found it is kept here because the lesson
  survives the fix it produced: a render seam built from "the page is the whole bitmap" without
  re-checking what coordinate space the ink itself was captured in will reproduce this the next
  time a screen-owning extension grows a render call against chrome that is not always there.
- **A twelfth top-bar button does not fit the Nomad** (arc 31 / HV4, the arc-29 lesson re-applied).
  With Send and the pad's own button both showing, the calendar bar was already at eleven 62 dp
  buttons plus margins — 726 of the Nomad's 749 dp — so growing a Send/Export pair as two separate
  buttons was never on the table. The fix folds them into **one out-door button** whose face depends
  on what is installed: Send alone, Export alone, or an `ActionSheetDialog` offering both — the same
  shape arc 29's eraser sub-bar chose for the same reason. Any future calendar-bar addition has to
  clear this measurement before it is drawn, not after a walk finds it clipped.

---

## Related

- [`docs/extensions.md`](extensions.md) — the seam: `INotebookExporter`, `ExporterContract`, the
  two-fd handshake, the source-kind tail, the delivery tail (arc 31 / HV1), the export secret, the
  boundary audit, `ACTION_CALENDAR` and `ICalendar.render` (arc 31 / HV4), `:ext-soil`'s,
  `:ext-pdf`'s, `:ext-document`'s and `:ext-image`'s identities.
- [`docs/document.md`](document.md) — the feature the document exporter reads from: the data
  model, the notebook document's merge join `ExportText.markdownOf` reuses, the editor Preview
  metrics `DocumentPdfMetrics` mirrors, text documents.
- [`docs/library.md`](library.md) — the notebook long-press sheet, where the library's **Export…** row sits.
- [`docs/notebook.md`](notebook.md) § Export page / § Erase page — the second door (arc 30): the page sheet's **Export page** row, the close-export-reopen handoff, and the erase that shares the arc.
- [`docs/cloud.md`](cloud.md) — the eighth extension point this arc's Destination row rides on:
  `ACTION_CLOUD_STORAGE`, `:ext-cloud`, the provider's tree, the Connect door, `CloudTimeouts`; also
  what the per-page image leg and the calendar's Destination row both ride unchanged (arc 31).
- [`docs/objects.md`](objects.md) — the feature the PDF endnotes read from: sticky notes' data
  model, the transform mode, the sticky editor, and the `PageBundle` v2 / `bundleVersion` design
  in full (arc 28).
- [`docs/templates.md`](templates.md) § Save as template (arc 31 / HV2) — the page-sheet neighbor
  to this arc's Images and Presets sections: the same page-sized raster (`PageRaster`) this file's
  `ExportRender` bake already produces, landed in the template library instead of an exported file.
- [`docs/calendar.md`](calendar.md) — the feature this arc's calendar mode and per-page image
  delivery read from: the three pages and their geometry, the store, both transfers (the
  whole-page send this screen's Export button sits beside), the out-door button, and the calendar's
  own Nomad numbers.
- `apps/notesprout_sn/HARVEST_PLAN.md` — arc 31 "Harvest"'s standalone plan and ledger: the D1/D4
  design calls, the HV1/HV3/HV4 phase outcomes and every measured Nomad number in this section, in
  full. (Not `RATTA_PLAN.md` — this arc is documented there instead.)
- `apps/notesprout_sn/RATTA_PLAN.md` §§ "Phases — Arc 15 \"Export\"," "Phases — Arc 18 \"PDF\","
  and "Phases — Arc 19 \"Document\"" (phase M9) — the wizard's locked decisions and each phase's
  outcome, in full.
- og's `docs/full-notebook-export.md` (monorepo root) — the reading reference for the filename
  sanitize rule and the keying shapes; SN's export screen implements a fresh, format-compatible
  version, and imports nothing from it (Paper never built export). og's `NotebookExporter` (raster
  PDF + pdfbox password) is the equivalent reading reference for arc 18's PDF exporter — again no
  code copied.
