# Import (arc 16)

Getting a notebook **into** the app — the exporter's mirror, riding the same seam. The library's
bottom bar grows an **Import** button; it opens SAF `ACTION_OPEN_DOCUMENT`, hands the picked
document to whichever importer extension accepts it, and the host takes it from there: probing,
unlocking, re-keying to this device, asking three questions and writing the notebook into the
Garden and the index. The extension only ever streams bytes between two file descriptors — it never
learns what it just delivered.

The first importer is **`NSE · Soil Export`**, the same package (and the same label, on the user's
call — no rename) that exports: `:ext-soil` grew a second service, `SoilImporterService`, beside
`SoilExporterService`. og's `docs/full-notebook-export.md` § Import (monorepo root) was the reading
reference for the pipeline shape; no code was copied. Arc 19 grew a **second** importer on the same
point: `:ext-document`'s `TextImporterService`, which delivers a `.md`/`.txt` file's bytes exactly
the same way and forks the host into a wholly different, `.soil`-free path afterward — see
[The text importer](#the-text-importer-arc-19) below.

This is the feature doc. The seam it rides on — the point, the AIDL, the trust boundary — is
[`docs/extensions.md`](extensions.md) § "The importer point (arc 16)"; the button it hangs off is
[`docs/library.md`](library.md); the feature the text importer lands into is
[`docs/document.md`](document.md).

**Status: arc 16 complete + frozen 2026-08-28** — I1 the point + the whole pipeline, all keyings,
placement and the remap (`ecf0443`, user checklist passed) · I2 review fixes (9/10, the two
Replace data-loss paths the headline), boundary audit, this doc (`20b6306`, checklist re-run
passed against the fixed build). **Arc 19 "Document" M8 complete** — the text importer + the
result-kind tail + text documents end to end on the Nomad, user checklist passed (commits
`70e0218` + `1051cba`).

Entry stays the library's **one** Import button (arc-16's single-entry lock): the picker's MIME
union and extension-match discovery, [below](#the-button), already cover `.md`/`.txt` with no
separate import sheet needed — a second importer is just a new candidate `describe()` answers
with.

---

## The button

`ImportFlow` owns one `View` — the library's bottom-right `btnImport`, just before Templates
(the user's placement call: Import · Templates · debug ⋯). It is built in `LibraryActivity.onCreate`
for the same reason the scratch pad's button is: it registers two `ActivityResultLauncher`s (the
document picker and, for "Choose folder…," the folder picker), and a launcher may not be registered
after `STARTED`.

- **Discovery re-runs on every `onResume`** (`ExtensionRegistry.importers()`, IO-dispatched) — the
  same gating rule as the notebook sheet's Export… row: a package can be disabled or replaced under
  a standing library screen, and a button that lies about what is installed is worse than one that
  is simply gone.
- **`GONE` when no trusted importer is installed, never `isEnabled = false`.** A disabled control is
  invisible on e-ink; a lie about what a tap will do is worse.
- **Never hidden mid-import** — the button stays as it was while `isImporting` is true, because
  taking it away under a running flow would say something about a flow that is still going; it is
  unreachable behind the overlay anyway.
- Icon `ic_import`: the `ic_notebook_plus` recipe — the same notebook-with-spine-tabs glyph — with
  an **input arrow** in the corner notch where the plus sits on the create icon, pointing *into* the
  notebook. Drawn this way on the user's own call: the Tabler file-import glyph read as "a file," not
  "a notebook," and importing is a notebook arriving, the same visual family as `+Notebook`.

The Import button and the Export… row are deliberately not the same kind of control: exporting is
something you do *to* a notebook (a row on its long-press sheet), importing is something you do to
the library itself (a button on its own bar, asking nothing about what is currently selected).

---

## The flow, step by step

1. **The tap asks first, then opens the picker.** `onTap()` latches (`isBusy`) against a double-tap
   in the e-ink feedback gap, then calls `loadCandidates()` — every installed importer's `describe()`
   under `ExtensionRegistry.importers()`; a `describe()` call that throws (over the descriptor caps,
   or any other failure) drops that importer with a log line, never a crash. With no candidate left,
   a problem dialog ("Nothing to import with") closes the beat rather than opening a picker with
   nothing behind it. Otherwise `ACTION_OPEN_DOCUMENT` opens filtered on the union of every
   candidate's declared MIME types **plus the wildcard `*/*`** (`ImporterMatch.mimeFilter`) — og's
   rule: providers mislabel a `.soil` routinely, and a filter that hid the very file the user came
   for would be a dead end with no explanation. Cancelling at the picker leaves the screen exactly as
   it was; nothing has happened yet.
2. **Which importer gets it is decided by extension, not MIME** (`ImporterMatch.matching`, on the
   picked document's **display name**): the declared MIME list only ever seeded the picker's filter,
   because a MIME match would drop the one importer that can actually read a file with no registered
   type. One match is no question; several is a chooser (`ImportDialogs.pickFromList`); none is a
   problem dialog ("Can't import that file") — an importer picked at random would stream a document
   into a probe that was always going to refuse it.
3. **Deliver into the cache.** `NotebookImport.prepareCache` wipes and recreates `cacheDir/import/`
   and hands back `incoming.soil`. The host opens a read fd on the picked `Uri` and a write fd on
   that cache file, builds an `ImportSpec` (empty this arc, plus the display name —
   `ImportNames.specDisplayName`, leaf-only, `/` and NUL stripped before construction so a name the
   parcelable cannot express degrades to `""` rather than failing the import over it), and calls
   `ImporterClient.importDocument(source, destination, spec)`. Both fds are the client's from that
   point — closed in `finally`, success, failure or timeout. `SoilImporterService.streamCopy` reads
   the source to the end in 64 KiB chunks, writes every byte to the destination, `fsync`s, and
   returns the count — the exporter's stream, reversed (`SoilStreams.streamCopy`, literally the
   same code both directions share). The landed byte count must equal what was reported
   (`Problem.SHORT` otherwise) and a zero-byte delivery is `Problem.NOT_A_NOTEBOOK`. The source
   provider's own accounts of the document's size (`OpenableColumns.SIZE`, the fd's stat) are
   **corroboration, not authority**: a provider claiming *more* than landed describes bytes that
   never arrived — a truncated stream both first-hand counts agreed on — and fails
   (`Problem.SHORT`); a provider that says nothing, or claims less (streaming providers routinely
   report a stale or placeholder `SIZE`), never overrules two agreeing first-hand counts — the
   disagreement is logged, and the probe and keying acceptance downstream answer for what the
   bytes actually are.
4. **Probe, then unlock.** `SoilCrypto.probe` reads the header: `Invalid` rejects outright
   (`Problem.NOT_A_NOTEBOOK`), `Plaintext` needs no key, `Encrypted` is tried against **this
   device's own global key first** — a same-device Keep export simply opens, and asking for a
   passphrase the user never set on this file would be the wrong question. Only a genuinely foreign
   file raises the passphrase prompt (`ImportDialogs.passphrase`), which loops in place under its own
   `AttemptLimiter` bucket, `"IMPORT"` — a wrong guess at a stranger's file never counts against the
   library's own unlock. The dialog's field is `isSaveEnabled = false` (never in a saved instance
   state) and the window keeps the IME shown throughout — the Ratta rule: on Supernote a hardware
   keyboard only delivers keys while the IME is visible, so hiding it after a wrong try would strand
   a keyboard user. Backing out anywhere here ends the flow with nothing written.
5. **Re-key to this device's global key, always.** [Keying](#keying), below.
6. **Read the manifest, and trust none of it.** `NotebookImport.readManifest` opens the now-keyed
   file through one raw connection, requires a `notebook` table to exist at all (`Problem.NOT_A_NOTEBOOK`
   otherwise), reads the root notebook row's own id and the `notebook_meta` row if there is one — a
   **missing meta is not a failure**, the file imports under the picked document's display name —
   counts live pages (against the id the rows are literally parented to, not the validated one — a
   file whose row id fails validation still counts its pages), and best-effort reads the first
   page's paper token so the imported card's placeholder shows the right kind until the first open
   seeds a real cover. Every id that comes out is passed through
   [`SafeImportId`](#the-untrusted-manifest) before it is trusted for anything.
7. **The three questions**, in order — [id collision](#id-collision), then
   [placement](#placement) (skipped entirely by a Replace answer), then
   [name conflict](#name-conflict). The questions **decide and write nothing** — the recreated
   ancestry included: the planned folders are created only in the commit step, after the last
   question (the I2 review's finding — folders written while a later question could still be
   cancelled were folders the cancel left behind). Any question can be cancelled, and cancelling
   costs nothing but the cache.
8. **Commit: remap, Garden, folders, index row — in that order.** When the landing id differs from
   what the file's rows are parented to, [`NotebookRemap`](#the-remap) rewrites the cache copy
   first. Then [`NotebookImport.placeInGarden`](#the-garden-write) puts it in the Garden as a
   staged rename. Only once that has verifiably succeeded are the placement plan's folders created
   (`createFolderWithId`, create-only by construction — an id taken between the read and the write
   lands the notebook one level up, the planner's own rule) and, last of all,
   `IndexRepository.importNotebookRow` writes the notebook's row — an id-collision Replace rewrites
   that row **in place**, keeping its own placement; every other path inserts a fresh one. A
   name-conflict Replace's retirement (the library's own delete — index row, recents, file, cached
   key) runs **only now**, after the import has fully committed, so cancelling anywhere earlier
   leaves the notebook that had the name untouched.
9. **Best effort from here.** `NotebookImport.refreshMeta` rewrites the Garden file's
   `notebook_meta` — this id, this name, this ancestry — through the one `SoilDatabase.open` /
   `.seal` door; a failure is logged and the import stands, because the notebook is already in the
   library either way.
10. **Confirm dialog, and stay in the library** (post-arc-17 toast review, 2026-08-30 — was a toast).
    `Dialogs.confirm` ("Imported") names the destination folder when the notebook did not land where
    the user is standing (`confirmImported`), because a card that never appears on screen otherwise
    reads as an import that did nothing — exactly the information a missed toast would take with it.

Every path that ends before step 10 wipes the import cache in a `NonCancellable` `finally`
(`NotebookImport.clean`) — a screen destroyed mid-import must not leave the incoming copy behind —
and explains itself with a problem dialog, never a silent return.

---

## The three questions

### Id collision

Only a **live notebook** already sitting under the incoming file's own id asks anything
(`ImportFlow.resolveIdentity`): *Replace existing* keeps that row's own placement — root included —
and skips the placement question entirely, because it is the same notebook coming back; *Keep both*
mints a fresh UUID, which is what forces the remap. Every other way the id can be taken — a
soft-deleted notebook, a folder, a list sentinel — takes a fresh UUID with **no question asked**:
reviving or overwriting a row that is not a live notebook of the user's own would be a mutation
nobody asked for. Replace also refuses outright (`Problem.IN_USE`) if the target file is held open
in this process — the same `SoilOpenFiles` door export relies on.

### Placement

*"Notebook's folders"* **plans** the file's remembered ancestry **create-only**
(`AncestryPlan`, [below](#the-untrusted-manifest)) — a segment that is anything but a live folder of
the user's own stops the descent one level up, and nothing existing is ever touched, ever revived.
Nothing is written here: the plan's creates run in the commit step, after the last question, so a
cancel at the name dialog cannot leave empty folders behind. *"Choose folder…"* hands the question
to `FolderPickerActivity`'s pick mode. This question is asked only when the id-collision answer did
not already decide it (a Replace already knows where the notebook lives).

### Name conflict

A clash against a **sibling in the landing folder** offers *Replace* (retires the notebook that had
the name — after commit, refused if its file is held) or *Keep both* (the first free `… Copy N`
name, `ImportNames.keepBothName`). **Asked at most once per import**: when the id-collision dialog
was already answered *Keep both*, a name clash here is against the very notebook the user just chose
to keep, so the question is skipped and the `… Copy` name is taken silently — asking again would be
redundant, and a Replace answer there would delete the notebook the user had just decided to keep.
The name dialog stands only for the one case that has not been asked anything yet: a foreign
notebook clashing by name alone.

---

## Keying

`ImportKeying` is `ExportKeying`'s mirror, run inward, and it is why the arc-16 wizard could drop
og's import-keying chooser entirely: **SN only ever opens a file under this device's global key, so
every accepted import is re-keyed to it, unconditionally** — there is no "keep encrypted under a
different key" outcome to ask about.

| Incoming file | What happens | Mechanism |
|---|---|---|
| Plaintext | encrypted to this device's global key | `ImportKeying.transform` with the plaintext ATTACH key spelled `''` |
| Encrypted under a foreign passphrase (another device's GLOBAL export, or a NOTEBOOK-scoped rekeyed export) | re-keyed to this device's global key | `ImportKeying.transform` with the typed passphrase as the ATTACH key |
| Encrypted, and this device's global passphrase already opens it (a same-device Keep export coming home) | passed through byte-untouched — but **integrity-verified in place** | a file already under the target key needs no transform, but it earns no free acceptance either: the pass-through opens it and requires `PRAGMA integrity_check = ok` (the I2 review's finding — a corrupt same-device export answering a Replace would otherwise have overwritten a healthy notebook), and never deletes it whatever the answer |

The mechanism is `ExportKeying`'s, run in the other direction — **literally**: both transformed
cases call `ExportKeying.exportAndKeyToPrimary`, the shared destination-primary export-and-key
core the export rekey also runs (one copy of a transform family with a recorded history of
on-device traps — the I2 review's dedup finding). **Export-and-key, never `PRAGMA rekey`** (og's
on-device finding): `sqlcipher_export` between a fresh connection created under the global key
(always the **primary** connection) and the incoming file, attached under its own key or `''`. The two things
`sqlcipher_export` does not do are done by hand, exactly as they are on the way out: `PRAGMA
user_version` is copied explicitly and re-verified from the finished file (the og trap — a
version-less import reads as garbage), and the output's `notebook_meta` row is restamped
(`encrypted: true`, `keyScope: GLOBAL`) so the file describes its new keying rather than the
source's. Nothing is accepted unverified — the output must probe `Encrypted`, open under the global
passphrase, answer `PRAGMA integrity_check = ok`, and hold the source's `user_version`, or the
transform throws and deletes only its own unaccepted sibling output (`<incoming>-keyed.soil` and its
sidecars) — never the incoming copy.

Everything runs in the import cache. The incoming bytes are untrusted throughout: the acceptance
checks are what earns a keyed file the right to be read as a manifest at all.

---

## The untrusted manifest

Every id read out of an incoming `notebook_meta` — the notebook's own and every `folderPath`
segment's — is validated before it is used as a [`soilFile()`](../app/src/main/kotlin/com/symmetricalpalmtree/notesproutsn/data/SoilFile.kt)
path component or an index primary key. `SafeImportId` holds the file to the **UUID alphabet only**
(canonical `8-4-4-4-12` hex, case-insensitive) — exactly what `UUID.randomUUID().toString()` writes
and every file in the family carries. An id like `../../notesprout` would name a path outside the
Garden the moment it reached `soilFile()`; an id that is merely odd would still become a primary key
nothing else in the app can produce. Nothing unsafe is repaired or escaped — it is simply not used,
and a notebook id that fails falls back to a fresh UUID (which is exactly what forces the
[remap](#the-remap) pass).

`AncestryPlan.plan` walks the manifest's `folderPath` the same way, one id at a time, against three
slots the caller reads from the index: `MISSING` (create it), `LIVE_FOLDER` (descend through it,
change nothing), `BLOCKED` (a soft-deleted folder, a notebook, anything not a live folder — never
mutated, and the descent stops one level up). **Soft-deleted folders `BLOCK` rather than revive** —
tightened from og's own shape on the arc-16 wizard's call: reviving a folder the user threw away is
a mutation, and this pass performs none. The walk is capped at `MAX_DEPTH` (20) — an imported path
is a stranger's, so it gets a tighter cap than the library's own 50-hop ancestry walk.

A missing `notebook` table is a rejection at the manifest read (`Problem.NOT_A_NOTEBOOK`); a missing
`notebook_meta` is not — that file imports under the picked document's display name with an empty
ancestry (og's pre-meta files, and any build that never stamped one, still import).

---

## The remap

`NotebookRemap.remap` (`data/soil/NotebookRemap.kt`) exists because of the arc-15 / E3 round-trip
finding: inside a `.soil`, pages are parented to the **notebook row's own id**, and
`NotebookSession` queries pages by the **index row's** id — so an import that lands under a fresh
UUID (a *Keep both* answer, or a manifest id that failed `SafeImportId`) is a notebook that opens
**empty** unless the file itself is re-identified first.

The pass runs **in the cache, before the Garden copy**, on a connection the pipeline owns, inside
one transaction — a crash mid-remap leaves a cache temp the next import wipes, never a
half-identified Garden file. In order:

1. the notebook root row's own `id`;
2. every row whose `parentId` is the old id (pages, and by the same statement anything else the
   family ever parents to the root row) — soft-deleted rows included, because a deleted page's
   parentage is still read by anything that walks the file;
3. every `link` row whose payload targets the old notebook id by kind (`KIND_NOTEBOOK` /
   `KIND_NOTEBOOK_PAGE`) — a link "to this notebook by id" must follow the rename or it dies pointed
   at a target that no longer exists; a link's own-page `KIND_PAGE` carries no notebook id and
   survives untouched; a payload the pure `remapLinkPayload` cannot decode is left exactly as it
   came rather than risk corrupting a foreign or future grammar;
4. the `notebook_meta` row's `notebookId` — best effort, like every meta write (the pipeline's own
   post-import refresh rewrites the row properly anyway).

Child ids — pages, strokes, headings, links, templates — are never touched: they are minted UUIDs
with no meaning outside the file, and only the notebook's own identity changed. A `PRAGMA
wal_checkpoint(TRUNCATE)` right after the transaction puts the rewrite in the main file, which is
the only file the Garden copy takes.

---

## The Garden write

`NotebookImport.placeInGarden` is the last step that can fail without leaving the library changed,
and the careful one:

- a **live connection** to the target refuses outright (`Problem.IN_USE`) — `SoilOpenFiles` is the
  same one-file-one-connection door export relies on;
- a `-wal` sidecar still holding frames beside the keyed cache file refuses too (`Problem.WRITE`) —
  the copy takes the main file only, and one with un-checkpointed frames behind it would be a
  notebook missing its newest rows;
- the copy goes to a **`soilStagingFile` sibling** first and is length-verified there, so a
  half-written copy can never become the target; the swap is then **one `rename` over the live
  target** — `rename(2)` replaces atomically, so there is no instant at which the user's existing
  notebook is deleted-but-not-yet-replaced, and a rename that fails (same-directory siblings — it
  has no failure mode a copy would survive) refuses with the target exactly as it was, never a
  fallback copy over a target already torn down (the I2 review's data-loss finding). The replaced
  file's sidecars go only **after** the swap;
- the **cached raw key for this id is invalidated** afterward (`KeyMaterial.invalidate`) — the file
  behind the id now has different bytes, and therefore a different SQLCipher salt, than the one that
  key was derived from. (`KeyOpener` self-heals a stale key on its own, but a key already known wrong
  is not left lying around regardless.)

This is arc 15's rule read backward: an export refuses to copy a source with unflushed WAL frames
behind it; an import refuses to write a target with the same problem in front of it.

---

## The text importer (arc 19)

A second importer arrived on the same point: `:ext-document`'s `TextImporterService`, a third
registration inside that package (beside the editor point and the document exporter) — the
`SoilImporterService` shape line for line, its own `TextStreams.streamCopy`. It streams the picked
document's bytes to the host cache exactly as the soil importer does; the extension never opens
the delivered bytes or learns what they are — the fork into text handling happens **after**
delivery, entirely host-side.

**The seam grew a compatible result-kind tail**, `ImporterInfo.resultKind` — absent means
`RESULT_NOTEBOOK` (today's meaning, unchanged on real wire, the `sourceKind` recipe mirrored
verbatim: written unconditionally last, an unknown value refused at unmarshal so an unreachable
kind can never arrive here), `RESULT_TEXT_DOCUMENT = 1` says the delivered bytes are text, not a
`.soil`. The text importer's service manifest declares `API_VERSION` **3** for exactly the reason
the document exporter does: a version-2 host reading an absent tail would run the bytes through the
`.soil` probe, and text is never going to pass `Invalid`/`Plaintext`/`Encrypted` cleanly.

**`ImportRouting` forks right after `deliver()`** (pure, so the decision itself is provable off
device). Delivery is identical for every importer — two fds, a verbatim stream, a byte count
checked twice — and `resultKind` is the only thing that separates what happens next. A
`RESULT_TEXT_DOCUMENT` answer skips the whole `.soil` pipeline — no probe, no unlock, no keying, no
manifest, no three questions — and instead:

1. The delivered length is checked against `TextImport.MAX_TEXT_BYTES` (10 MB) first-hand, before
   the bytes are even read into memory (the arc-16 corroboration discipline, applied to the new
   cap). `TextImport.decode` then reads the cached bytes under **strict UTF-8**
   (`CodingErrorAction.REPORT`, never the stdlib's lossy decode): a malformed byte sequence, or any
   `U+0000` in the decoded text, refuses the import outright (`Problem.NOT_TEXT`) — a NUL byte is
   binary wearing a text extension. What survives is normalized, not rewritten: a leading BOM
   (`U+FEFF`) is dropped and `\r\n`/lone `\r` fold to `\n` (`:markdown`'s own line ending). The
   decoded length is then re-checked against `DocumentContract.MAX_DOCUMENT_CHARS`
   (`Problem.TEXT_TOO_LONG`) — the byte cap alone does not bound characters, and this is the cap
   the editor itself enforces, deliberately aligned with the byte cap so nothing under it can be
   unconditionally over the other.
2. **Silent name dedupe** (`ImportNames.freeName`, one sibling read in the current folder) — no
   name-conflict question, ever: the text path always creates a new notebook, og's rule.
3. `TextDocumentCreate` writes the notebook — the same create contract the library's own Text-radio
   create uses ([`docs/library.md`](library.md)) — with the document row's `srcUpdatedAt` left
   **NULL** ("authored elsewhere," never a draft an in-editor Bring-in could stamp over). **A blank
   `.txt` writes no document row at all** (blank means absent, the M2 rule): an empty text file is
   a legal import that lands as a genuinely empty text document, never a refusal — where a
   zero-byte `.soil` still refuses (`Problem.NOT_A_NOTEBOOK`); `ImportRouting.rejectsEmptyDelivery`
   is the one place that asymmetry is written down and provable.
4. Cover, then straight into the editor (`openImported`) — the same landing a create-flow text
   document gets.

**No questions anywhere on this path** — not id collision, not placement, not name conflict: a
text import always creates fresh, always in the folder the library is standing in, and is
encrypted like any other create. New refusals: `Problem.NOT_TEXT` (failed the UTF-8/NUL check) and
`Problem.TEXT_TOO_LONG` (over either cap) — see the [failure table](#failure-table).

---

## Timeouts

`ImporterContract` shares `ExporterContract`'s timeouts **by reference** rather than re-deriving
them: `DESCRIBE_TIMEOUT_MS` is 3 s, `IMPORT_TIMEOUT_MS` is `EXPORT_TIMEOUT_MS`, 120 s — the stream is
the same copy running in the other direction, so the arc-15 Nomad measurement (a 100 MB flash copy
at ~0.45 s, ~525 MB/s `dd`) transfers directly. Two minutes comfortably covers a 1 GB `.soil` even
through a slow DocumentsProvider at 10 MB/s.

---

## What never crosses

The unlock passphrase, the device's global key, every notebook id, and every file path stay entirely
on the host side of the seam — the extension's whole argument list is two fds and a bounded
id → value map plus a display-only name. The full boundary audit — outward on `importDocument`,
inward from `ImporterInfo` / `ImportResult`, and the unlock passphrase's whole host-side lifecycle —
is [`docs/extensions.md`](extensions.md) § "Boundary audit," rows 9–11.

---

## Failure table

| What happened | What the user gets | Where |
|---|---|---|
| No trusted importer installed | problem dialog, "Nothing to import with" | `ImportFlow.onTap` → `import_none_*` |
| A `describe()` call fails, or the descriptor is over `ImporterContract`'s caps | that importer dropped with a log line — never shown, never a crash | `ImportFlow.loadCandidates` |
| No app on the device can open a document | problem dialog, "No file picker" | `onTap` → `import_no_picker_*` |
| No installed importer's declared extensions match the picked document | problem dialog, "Can't import that file" | `chooseImporter` → `import_unsupported_*` |
| The delivery call fails, times out, or the extension dies mid-stream | problem dialog naming the extension's failure | `deliver` → `Problem.DELIVERY` → `import_delivery_body` |
| The landed byte count doesn't match what the importer reported, or the source claims more bytes than arrived | problem dialog, "Only part of that file arrived" | `deliver` → `Problem.SHORT` → `import_short_body` |
| The delivered file is zero bytes, or the probe/manifest finds no `notebook` table (`.soil` path; an empty `.txt` is legal, arc 19) | problem dialog, "not a Notesprout notebook" | `Problem.NOT_A_NOTEBOOK` → `import_not_a_notebook_body` |
| The delivered text fails strict UTF-8 decoding, or a decoded NUL byte proves it isn't text (arc 19 / M8, `RESULT_TEXT_DOCUMENT`) | problem dialog, "isn't valid text" | `TextImport.decode` → `Problem.NOT_TEXT` → `import_not_text_body` |
| The delivered text is over 10 MB, or over 10,000,000 characters after decode (arc 19 / M8) | problem dialog naming the 10 MB limit | `TextImport.decode` → `Problem.TEXT_TOO_LONG` → `import_text_too_long_body` |
| No device key session (process killed, nothing unlocked since) | problem dialog, "The library is locked" | `Problem.NO_KEY` → `import_locked_body` |
| Too many wrong passphrase guesses on this file | problem dialog naming the wait, "Too many tries" | `unlock` → `AttemptLimiter` → `import_locked_out_*` |
| The re-key transform fails, or its output fails acceptance | problem dialog, "could not be prepared for this device" | `Problem.KEYING` → `import_keying_body` |
| The keyed file opens but won't read (damaged, truncated) | problem dialog, "may be damaged" | `Problem.UNREADABLE` → `import_unreadable_body` |
| Replace (id or name) targets a notebook open elsewhere in this process | problem dialog naming that it's open elsewhere | `Problem.IN_USE` → `import_in_use_body` |
| The Garden write fails, or comes out short (no room, IO died mid-copy) | problem dialog, "device may be out of space" | `Problem.WRITE` → `import_write_body` |
| The screen was rebuilt behind the folder picker and nothing is waiting for the answer | problem dialog, "interrupted before that folder could be used" | `folderLauncher` result → `import_interrupted_body` |
| Back / the back arrow tapped while an import runs | "Import in progress" dialog; the flow continues untouched | `showBusyGuard` (`import_busy_body`) |
| Any other exception escapes the pipeline | problem dialog, generic "couldn't be finished"; exception logged by **class name only** | `runImport` catch-all → `import_generic_body` |
| Import succeeded | confirm dialog, "Imported" (body names the folder when it's not the current one) | `confirmImported` |

The rule behind the column is arc 15's, carried over whole: **a toast only confirms something that
already happened; anything explaining why a tap didn't work is a dialog.** Import's own success case
moved off that rule (post-arc-17 toast review, 2026-08-30): the folder name is easy to miss in a
toast, and it's the one piece of information the row exists to carry. Every failure row deletes
at most the import cache (`cacheDir/import/`, wiped in a `NonCancellable finally`) — the Garden and
the index are never touched until the commit step has fully verified, so a failure anywhere before
then leaves the library exactly as it was.

---

## Verification

- **JVM.** `ImporterMatchTest`, `ImportNamesTest`, `AncestryPlanTest`, `SafeImportIdTest`,
  `NotebookRemapTest` (the pure `remapLinkPayload` pinned against real link payload shapes),
  `ExportKeyingTest`'s shared pure pieces (`sqlLiteral`, `restampedJson` — the import transform
  rides the same core), and the parcelable round-trip / `require` rejection tests for
  `ImporterInfo`, `ImportSpec`, `ImportResult` — 890 JVM tests total across the arc-16 freeze
  point. Arc 19 / M8 added `TextImportTest` (strict UTF-8, the NUL refusal, BOM strip, `\r\n`/`\r`
  folding, both caps) and `ImportRoutingTest` (the result-kind fork and the empty-delivery rule,
  provable as pure logic rather than a device walk).
- **Device.** A SAF pick cannot be driven by `adb` (the standing G4 trap) — everything through the
  picker is a human checklist item; agents verify up to the picker's launch and inspect pulled
  results afterward. The foreign-passphrase prompt is typed by a person for the same reason
  Supernote swallows `adb shell input text`. The Haiku Nomad walk drives button gating
  (`pm disable-user` / `enable` across real pause/resume cycles — a re-`am start` onto an
  already-resumed Activity fires no `onResume` and so no re-discovery, a walk artifact rather than a
  bug), dialogs up to the picker, relaunch restore, the crash buffer, and that every bind is
  unbound.
- **User checklist**, passed 2026-08-28: all three export keyings (same-device Keep with no prompt,
  plaintext, and a foreign passphrase typed wrong-then-right) imported back; Replace and Keep-both
  both proven at the id-collision dialog; both placement answers; the folder-naming confirm dialog
  when the destination isn't the current folder. Arc 19 / M8's own checklist (passed) added a real
  SAF import of a `.md` file — the picker is not `adb`-drivable — landing straight in the editor
  with no questions asked.

---

## Related

- [`docs/extensions.md`](extensions.md) — the seam: `INotebookImporter`, `ImporterContract`, the
  two-fd handshake, the boundary audit rows 9–11, `:ext-soil`'s shared identity with the exporter,
  the result-kind tail's own write-up.
- [`docs/document.md`](document.md) — the feature the text importer lands into: text documents,
  the data model, the create contract `TextDocumentCreate` shares with the library's own
  Text-radio create.
- [`docs/export.md`](export.md) — the mirror-image feature, and `ExportKeying`, whose mechanism and
  acceptance rules `ImportKeying` reuses in the inward direction; also `SOURCE_DOCUMENT`, the
  export half's own arc-19 addition on the sibling point.
- [`docs/library.md`](library.md) — the bottom bar, where the Import button sits.
- `apps/notesprout_ratta/RATTA_PLAN.md` §§ "Phases — Arc 16 \"Import\"" and "Phases — Arc 19
  \"Document\"" (phase M8) — the wizard's locked decisions and each phase's outcome, in full.
- og's `docs/full-notebook-export.md` § Import (monorepo root) — the reading reference for the
  pipeline shape; SN's import screen implements a fresh, format-compatible version and imports
  nothing from it.
