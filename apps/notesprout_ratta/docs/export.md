# Export (arc 15)

Getting a notebook **out** of the app. The library's notebook long-press sheet grows an
**Export…** row; it opens the host's `ExportActivity`, which discovers whatever exporter
extensions are installed, shows their options with its own e-ink widgets, and hands the actual
file-writing off to the extension over two `ParcelFileDescriptor`s. The host owns everything
that touches a key; the extension only ever streams bytes.

The first — and so far only — exporter is **`NSE · Soil Export`**: the notebook's `.soil` file
itself, importable into another Ratta-based Notesprout instance. og's `docs/full-notebook-export.md`
(monorepo root) is the reading reference for the filename rule and the keying shapes; no code was
copied, and Paper never built export, so it has nothing to say here.

This is the feature doc. The seam it rides on — the point, the AIDL, the fd handshake, trust — is
[`docs/extensions.md`](extensions.md) § "The exporter point (arc 15)"; the sheet row it hangs off
is [`docs/library.md`](library.md).

**Status: arc 15 complete** — E1 the point + `NSE · Soil Export` + the screen (Keep path, commit
`c5fb23b`) · E2 the keying transforms (New passphrase · Remove encryption, commit `1860da3`) · E3
review, boundary audit, docs, freeze.

---

## The screen

`ExportActivity` (`IndexGuard`, portrait, e-ink chrome, `TopGuard` 0) receives `EXTRA_NOTEBOOK_ID`
/ `EXTRA_NOTEBOOK_NAME` only — never a `File`. Chrome follows F2: the action button lives at the
top bar's right edge, the bottom of the screen holds nothing.

- **The chooser.** Every trusted exporter is asked to `describe()` itself. With exactly one
  installed, the chooser collapses to a plain label naming it — no radio for a choice that does not
  exist. The same collapse rule applies one level down: a single-choice option with exactly one
  declared choice (`ExportOptions.isFixed`) renders as a fixed value line, not a one-item radio
  group.
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
- **Staged progress**, one line of running commentary (`status`, `GONE` until an export is
  actually in flight): *Preparing…* → *Re-keying…* / *Removing encryption…* (only for those two
  keyings) → *Exporting…*. A single unchanging "Exporting…" would read as a stall across stages
  this long on e-ink.
- **Toast + finish.** A toast only ever confirms something that already happened; every failure is
  a dialog instead, naming what went wrong — never a path, never a secret — and saying what
  happened to the destination file: removed, possibly remaining, or untouched (see the
  [failure table](#failure-table) for when each applies).
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
   that was just made (the arc-15 review). Only a clean pass fires the toast and finishes.

Every path through steps 4–8 that does not reach step 8's toast ends the same way: the cache
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

## Timeouts

A Binder call cannot be cancelled, so both of `ExporterContract`'s timeouts are measured, not
guessed (the arc-11 J5 lesson): `DESCRIBE_TIMEOUT_MS` is 3 s (a small in-memory descriptor, fast by
construction); `EXPORT_TIMEOUT_MS` is 120 s, sized against a 100 MB flash copy measured on the Nomad
at ~0.45 s (~525 MB/s `dd`, ~230 MB/s `cp`) — two minutes comfortably covers a 1 GB artifact even
through a slow DocumentsProvider at 10 MB/s.

---

## What never crosses

Passphrase, path, and SQLCipher never reach the exporter — the extension receives two fds and a
bounded id → value map (`ExportSpec`), nothing else. The full boundary audit — outward on `describe`
and `export`, inward from `ExporterInfo`/`ExportResult`, and the keying secret's whole host-side
lifecycle — is [`docs/extensions.md`](extensions.md) § "Boundary audit," rows 6–8.

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
| The notebook is open elsewhere in this process | problem dialog, "This notebook is open somewhere else" | `ExportArtifact.prepare` → `Problem.IN_USE` |
| No device key session (process killed, nothing unlocked since) | problem dialog, "The library is locked" | `Problem.NO_KEY` |
| The `.soil` file is gone or empty | problem dialog, "no longer on the device" | `Problem.MISSING` |
| The `.soil` won't open or won't read | problem dialog, "could not be read just now" | `Problem.UNREADABLE` |
| The cache copy failed, or came out short | problem dialog, "device may be out of space" | `Problem.COPY_FAILED` |
| The screen was rebuilt behind the picker and the chosen exporter is gone | problem dialog naming the format unavailable; destination per the deletion rule (nothing was written — a pre-existing overwrite target is left untouched) | `reselectAfterRestore` → `export_gone_body` |
| The `ExportSpec` itself is rejected (a value out of bounds) | problem dialog; destination per the deletion rule | `runExport` catch on `ExportSpec` construction |
| Rekey was armed but the typed passphrase was lost (screen rebuilt behind the picker — `saveEnabled=false` wiped the fields) | **honest "passphrase was lost" dialog — never a silent Keep**; destination per the deletion rule | `ExportKeying.plan` throws `IllegalArgumentException` → `export_passphrase_lost_body` |
| The keying transform itself fails (plain or rekey) | problem dialog, "could not be converted for this export"; destination per the deletion rule | `ExportKeying.apply` throws → `export_transform_body` |
| The `export()` call fails, times out, or the exporter dies mid-stream | problem dialog, "didn't finish writing the file"; the truncating open already ran, so the wreckage is removed (best-effort, reported honestly) | `ExporterClient.export` throws → `export_failed_body` |
| The byte count the extension reports doesn't match what was streamed | problem dialog, "Only part of the notebook reached that file"; wreckage removed (best-effort, reported honestly) | `runExport` byte-count check → `export_short_body` |
| The stream completed but every answer the destination provider gives disagrees with it | *check-the-file* dialog, **no delete** — metadata can lag a write it just took, and a fully-written export is never destroyed over a stale answer | `runExport` → `export_verify_body` |
| Back / the back arrow tapped while an export runs | "Export in progress" dialog; the flow continues untouched | `showBusyGuard` (`export_busy_body`) |
| Export succeeded | toast, "Exported"; screen finishes | `runExport` success path |

The rule behind the column, family-wide: **a toast only confirms something that already happened;
anything explaining why a tap didn't work is a dialog.** Every failure row after the picker also
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

---

## Related

- [`docs/extensions.md`](extensions.md) — the seam: `INotebookExporter`, `ExporterContract`, the
  two-fd handshake, the boundary audit, `:ext-soil`'s identity.
- [`docs/library.md`](library.md) — the notebook long-press sheet, where the **Export…** row sits.
- `apps/notesprout_ratta/RATTA_PLAN.md` § "Phases — Arc 15 \"Export\"" — the wizard's locked
  decisions and each phase's outcome, in full.
- og's `docs/full-notebook-export.md` (monorepo root) — the reading reference for the filename
  sanitize rule and the keying shapes; SN's export screen implements a fresh, format-compatible
  version, and imports nothing from it (Paper never built export).
