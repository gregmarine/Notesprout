# Backup & compaction (arc 17)

Getting a copy of the whole library **off** the device — and, first, teaching the databases to
clean up after themselves so the copy is worth taking. Two joined halves on the user's 2026-08-28
direction:

- **K1 — compaction**: every `.soil` close hard-deletes the file's soft-deleted rows and gives the
  space back (`SoilCompactor`), sidecars never linger after a clean close, and the global index
  purges too (`IndexCompactor`). Nomad-proven: one inked-page delete took an imported notebook from
  442368 to 225280 bytes (49%).
- **K2 — local backup**: a dedicated **Backup** screen off the library bottom bar — choose a SAF
  folder, **Back up now**, a last-run status line — copying every alive notebook incrementally
  (og's D8 stamp rule) and the index **last**, with an **Exclude from backup** toggle on the
  library's long-press sheet.

**Pure core — no extension involvement** (the arc wizard's first lock, true as written at K1/K2).
Everything a backup touches is host-only by the standing seam rule anyway: keys, Garden paths, the
index. **Arc 25 "Drive" / V4 grew a second destination onto the same run** — a cloud leg to
`Backups/<device folder>/`, through the eighth extension point (`ACTION_CLOUD_STORAGE`,
`:ext-cloud`) the wizard's deferral above named — without touching anything K1–K3 built: the local
leg is unchanged code, and the two legs share only the run's shape. See
[The cloud leg](#the-cloud-leg-arc-25--v4) below and [`docs/cloud.md`](cloud.md) for the seam
itself.

**Backup only — no restore.** A single notebook is already recoverable through arc 16's Import
(every backup file is a self-describing `.soil` with `notebook_meta`); whole-library restore is its
own future arc. og's `docs/backup.md` + `NotebookCompactor` were the reading references — no code
copied.

**Arc 21 / W5 grew the set: every extension store is backed up too** — see
[Extension stores](#extension-stores-arc-21--w5) below. Same rule, still no restore.

**Status: arc 17 complete** — K1 compaction (`73d6490`) · K2 backup (`7fb0aa2`, user checklist
passed) · K3 review (high, 10/10 findings fixed — the destination-integrity cluster the headline),
this doc. **Grown by arc 21 / W5** (extension stores). **Grown by arc 25 "Drive" / V4** (the cloud
leg — `Backups/<device folder>/`, its own stamp map, `SelfContainedSnapshot`; 2329 JVM tests, user
checklist passed 2026-09-04).

---

## The purge (K1) — clean at rest

**Undo is in-memory and dies with the session**, so a closed notebook's soft-deleted rows are
unreachable by construction — from the user's view nothing changes except file size. og never
purged user content; SN does, on the user's explicit decision.

`SoilCompactor` (`data/soil/`) runs at `NotebookSession.seal`, **after `writer.close()` and before
`db.seal`** — no queued write can race the deletes, and the checkpoint inside `db.seal` absorbs
the `VACUUM`. It is also run standalone by the backup engine's compact pass. Three rules, each
carrying an arc's lesson:

- **Template rows are exempt, by type, from both passes** (arc 13): nothing ever soft-deletes a
  template and reuse-before-mint depends on the rows persisting.
- **The cascade starts from what this purge deletes, never from "parent missing"** — an alive row
  whose parent simply isn't in the file is evidence of damage, and never-delete-on-corruption
  applies: sweeping it would turn one corrupt `parentId` into destroyed content.
- **`updatedAt` is untouched everywhere**: rows are deleted, never rewritten — a bump would
  re-flag the notebook as needing backup forever.

`VACUUM`, not `incremental_vacuum` (og's measured finding: freed-value fragmentation is not
returned by incremental, and an imported/re-keyed file may not have `auto_vacuum` set at all), and
only when something was actually deleted. A cheap `EXISTS` probe runs first (K3 review) — the
common close has nothing soft-deleted, K1 purged at the previous close, and must not pay a
whole-table snapshot to find that out. `compact()` **never throws** — it rides inside seal's
never-throw contract; a compaction bug must never cost a save.

**Sidecar hygiene** (`SoilCompactor.sweepSidecars`, called by `SoilDatabase.seal`): after a clean
close the Garden holds only `.soil` files — a fully-checkpointed (zero-length) `-wal` and its
`-shm` go. **A non-empty WAL is live data** (a failed checkpoint leaves real writes in it) and is
never deleted, only copied alongside at backup. The sweep runs **before the claim release** and
only while the handle's own claim is the sole one standing (K3 review) — see the reopen rule
below.

**`IndexCompactor`** is the `.soil` purge's index-side twin: hard-delete soft-deleted `objects`
rows, sweep orphan `list_item` edges, `VACUUM`. Two arc-13 traps live here: the five template
sentinels (`PROTECTED_REF_IDS`, pinned by test to `TemplateLibrary.SENTINEL_IDS`) are ids **with
no row behind them**, so the edge sweep must exempt them by name or it would silently unpin every
built-in paper; and a null `refId` is malformed in a way the pass does not understand — left
exactly as it came. Runs at bootstrap behind an `EXISTS` gate (the one moment the index has no
other reader) and from the backup run. **The bootstrap `VACUUM` also waits for a freelist floor**
(`SnIndex.BOOT_MIN_RECLAIM_BYTES`, 256 KiB — K3 review): one soft-deleted clipboard row (every
clipboard clear makes one) must not cost the next launch a whole-index rewrite; the backup run
passes a floor of 0, because a pre-copy compaction wants every byte back.

**Revived-row paths tolerate the purge** (`RevivedRowPurgeTest`): `createNotebook`, import
Replace and `setScheme` all take their fresh-create branch when the soft-deleted row they used to
revive is gone.

**The reopen rule (K3 review).** The seal that frees a notebook runs detached (`appScope`) and now
carries a purge + whole-file `VACUUM`, so a prompt reopen of a large notebook can genuinely arrive
while the old claim stands — racing it is the sticky-lock crash family. `SoilDatabase.open` now
**waits on the `SoilOpenFiles` claim** (bounded, 15 s; past the timeout it opens anyway and lets
`busy_timeout` fight). That wait is also what makes the sidecar sweep safe: openers block on the
claim, so no fresh connection's sidecars can appear under the sweep's deletes.

---

## The screen

`BackupActivity` (`backup/`), reached from the library bottom bar's far-left button (Tabler
`archive` as `ic_backup`, in `:sn-screen` — the K3 user call, replacing K2's `device-floppy`;
Import moved to sit right after it — the user's
K2 placement call, superseding the wizard's "off the top bar":
`[Backup] [Import*] … pager … [Templates] [⋯]`). Three things and nothing else: where backups go,
a button that runs one, and what the last run did. Wording is plain verbs (the user's K2 call):
"Backup folder" + "Choose…", "Back up now", "Last backup: <date time> — N copied, N skipped"
("Never backed up" before the first run).

- **Manual only.** No schedule, no watcher, no background run.
- **One run at a time** — an `AtomicBoolean`, cleared from the coroutine continuation; the modal,
  non-cancelable progress dialog covers the e-ink feedback gap.
- **Never a disabled control**: with no folder chosen the button still looks live and the tap
  explains itself in a dialog.
- **Every outcome is a dialog** (post-arc-17 toast review, 2026-08-30): a clean run confirms its
  counts in a dialog too, not a toast — this screen exists to answer "did it work," and the counts
  are the answer a missed toast would take with it. Anything the run could not do gets the same
  treatment with the honest per-count summary. "Skipped" is one number covering four honest reasons
  (up to date · excluded · open elsewhere · file missing) — the distinction matters to the engine,
  not the reader.
- The status line is read back from the stored config, not the in-memory result, so it says the
  same thing after a relaunch.
- **The Cloud section (arc 25 / V2, grown V4)**, under the local folder line: a status line naming
  the connected account, Connect/Disconnect, and an enable checkbox ("Back up to `<provider>`") —
  `GONE`, not disabled, while no trusted provider is installed. V4 adds the **Device folder** line
  and a **Rename…** button beneath it, and a second status line at the section's foot, *Last cloud
  backup: `<date>` — N copied, M skipped* (or *Never backed up to `<provider>`*). See
  [The cloud leg](#the-cloud-leg-arc-25--v4) below.

**Choosing the folder** takes the persistable read+write grant first — a tree URI without a
lasting grant is worthless next launch — and **releases the previous folder's grant** when the
destination changes (K3 review: persisted grants are a capped per-app resource; without the
release, enough re-picks make every future pick fail). **Picking a *different* folder clears the
stamp map** (flagged at K2, reviewed and kept at K3): stamps are statements about a destination,
and carrying them to a fresh folder would back up only the index there while every notebook read
"up to date". Re-picking the same folder keeps them.

**The exclude toggle** lives on the library long-press sheet (notebooks only, after Export…, the
Pin/Unpin label pattern: "Exclude from backup" / "Include in backup"), stored as notebook `flags`
bit 1 (`NotebookFlags.EXCLUDE_FROM_BACKUP = 2`; bit 0 is ENCRYPTED — format-safe, Paper ignores
it). `IndexRepository.setExcludeFromBackup` **never bumps `updatedAt`** — it is both the library
sort key and the needs-backup flag. An id-collision Replace import **preserves the bit**
(`importNotebookRow`, K3 review — a wholesale flags rewrite silently dropped the user's policy).

---

## The engine — the order is the design

`BackupEngine.run` (`data/backup/`, og D9 reshaped for SN's single LOCAL destination), headless IO
that **never throws** — every failure becomes a count or a `Problem` (`NO_FOLDER` / `FOLDER_GONE` /
`NO_KEY`), held by a top-level catch (K3 review: disk-full inside a Room write used to crash the
app under the progress dialog; the screen carries a belt catch too).

1. **Resolve the destination** — the persisted SAF tree; debug builds write into a `dev/`
   subfolder (debug and release coexist on the Nomad and must not share a root). Fail fast with a
   `Problem` the screen can name.
2. **Build the work list** (`BackupPredicates.workList`, pure + JVM-tested) over every alive
   notebook and the stamp map — og's D8: copy when not excluded and either never stamped or
   `updatedAt > stamp`. **Equal means backed up.** Excluded and up-to-date are counted, not
   visited.
3. **Per notebook**: a `SoilOpenFiles`-held file is skipped-and-counted (never copy under a live
   writer — the arc-15 lesson; structurally unreachable from the UI since Backup is only enterable
   from the library, kept as defense-in-depth); **compact** it through the one open door (the K1
   pass — best effort, a file that will not open is still copied as the bytes it is, og's rule);
   **copy** the `.soil` atomically; the **WAL rule** (below); then **stamp — per success,
   immediately**, with the `updatedAt` the work list read (never the wall clock: an edit landing
   mid-run can never be masked; a failed copy never stamps and retries next run; a stamp write
   that itself fails only re-copies next run and never aborts the run).
4. **Every extension store** (arc 21 / W5), after the notebooks and before the index — see below.
5. **The index, last, after every stamp**: purge (`SnIndex.compactIfNeeded(0)`) + checkpoint, then
   **snapshot to a local temp and probe it** (encrypted header, byte-for-byte the live length)
   before streaming — a torn copy of the live index is worse than no backup; only a failed
   snapshot falls back to streaming the live file.
6. **`lastRunAt`** — only when at least one destination write succeeded (**a store copy counts**)
   — and the stamp map pruned of notebooks the index purge removed.

**The WAL rule, every file kind (K3 review made it whole).** A non-empty `-wal` beside a source
file is live data: it is copied **alongside**, and *both* must land before the copy counts (before
the stamp, for a notebook). An absorbed WAL instead deletes the stale destination sidecar — and
that delete must be **verifiable**: a swallowed failure would pair a fresh `.soil` with an old
`-wal` forever, which corrupts on restore. The K2 build applied this to notebooks only and
discarded the delete's result; K3 fixed the delete and extended the whole rule to the **index**
copy — a busy checkpoint (one pooled Room reader under the library screen is enough) leaves
committed rows in `notesprout.db-wal`, *this run's own stamps included*, and a main-file-only copy
passes every probe while silently missing them. `SoilCompactor.sidecarsRemovable` is the one
predicate deciding a sidecar's fate everywhere.

**Filenames** (og D5): `<notebookUuid>.soil` (+ `<name>-wal` when live) and `notesprout.db`
(+ `-wal`) — UUID names give replace-in-place identity; display names travel inside each file's
`notebook_meta`. Encrypted handling is a **ciphertext byte-copy, never decrypt** (og D10); SN is
global-key-only and the raw key is cached post-unlock, so the compact pass can open every notebook
unattended — og's NOTEBOOK-scope skip has no SN equivalent.

**State** is one additive index row type `backup` (the CLIPBOARD pattern — no schema change): a
singleton row at `ListIds.BACKUP_ID`, `blob` = `BackupConfig` kotlinx JSON (`treeUri`, `lastRunAt`,
`lastCopied`/`lastSkipped` for the status line, the per-notebook stamp map). `decode` never throws
— a corrupt blob reads as a fresh config, whose worst case is re-copying everything, the safe
direction. Living in the index lets the stamp map ride the same encryption and the same backup as
the rows it describes. **An import that lands on an existing id clears that id's stamp**
(`BackupStore.clearStamp`, called from `ImportFlow` — K3 review: imported content can carry an
`updatedAt` *older* than the stamp, and D8 would read "up to date" forever).

---

## Extension stores (arc 21 / W5)

Every `Garden/<pkg>.db` is copied too — the scratch pad's pages, the proofread user dictionary and
arc 21's tag index all become durable, and the backup folder stops being a copy of *most* of what
the user has.

- **The listing is the file system, and it is the one place `Garden/` is enumerated.**
  `SoilFile.extensionStoreFiles(context)` — the one path authority grew the one listing function.
  A store has no index row to be listed from: the host mints the file the first time an extension is
  lent its store, and that file is the only record it exists. The pure half,
  `extensionStorePackage(fileName)`, is what decides — a store is the only thing in that directory
  ending in `.db`, and its stem must still pass `isValidExtensionPackage`, so a notebook, an import
  in flight and every sidecar are all named out.
- **Every pass, unconditionally — no stamps.** A store is small, and it has no `updatedAt` to
  compare a stamp against: its edits belong to an extension, not to the library. Inventing a clock
  for one would be a second answer that can disagree with the file, so the run simply copies it.
  `updatedAt` semantics elsewhere are untouched.
- **A store takes the *index's* treatment, not a notebook's** — checkpoint if this process holds it
  open (`ExtensionStores.checkpointIfOpen`, best effort; a store this process never opened is left
  alone rather than paying a cold KDF to buy what the WAL-alongside rule already buys), then
  snapshot into the cache, probe it, copy that, WAL alongside. The notebook rule — never copy under
  a live writer, skip and count it — would skip **every** store worth copying, because
  `ExtensionStores` caches each store it opens for the life of the process and closes none.
  `BackupEngine.copyDatabase` is the one body both kinds take.
- **Ordered after the notebooks, before the index.** A store is content; the index is last by rule,
  because it is the manifest of everything the run already wrote.
- **A zero-length store is skipped** (a create that never finished — copying it would replace a good
  destination copy with an empty one). An uninstalled extension's store is still copied: arc 11's
  rule is that removing an extension's data is a deliberate act, never a side effect.
- **Its own sentence in the dialog** (the user's W5 call): "N extension stores copied." Folding them
  into "N copied" would make a number the user can check against the library stop matching it.

**Getting a store back — still no restore screen.** There is no restore path in the app for a store
(as there is none for the library). A store is recovered by hand, with the app closed: copy
`<pkg>.db` from the backup folder into `Garden/`, and its `-wal` beside it **if the backup has one**
(a backup with a `-wal` is incomplete without it, and a `-wal` left over from an older copy corrupts
the newer one — take both or neither, never one). Any `-shm` is rebuilt on open and is never copied.
The file is ciphertext under the device's global key: a store from a *different* device's library
will not open, and the app reports corruption rather than deleting it (never-delete-on-corruption).
A store copied back from an old enough backup to still carry the arc-11 key/value shape is wiped on
its first open after the restore like any other legacy store (format 1 → tables, logged as a row
count), leaving the backup file itself untouched.
A restore screen for the whole library, stores included, is in the monorepo `BACKLOG.md`.

---

## The cloud leg (arc 25 / V4)

"Back up now" can run **two legs in one tap** — the SAF folder above, unchanged, and a second
destination under the one installed cloud provider's own root: `Backups/<device folder>/`, through
`:ext-cloud` on the eighth extension point. Neither leg knows the other exists beyond
`BackupEngine.run` deciding, at the top of each run, which legs exist this time — local when
`treeUri` is set, cloud when `cloudEnabled` **and** a fresh `ExtensionRegistry.cloud()` discovery
finds a provider (re-asked at every run start, never trusted stale) — and running local first, then
cloud. **Neither leg → the run does nothing and says so** (`Problem.NO_DESTINATION`, the old
`NO_FOLDER` renamed and widened); the screen pre-checks the identical rule so a tap with nowhere to
go never raises a progress dialog for nothing. The seam itself — `ICloudStorage`, the provider's
tree, `CloudTimeouts` — is [`docs/cloud.md`](cloud.md); this section is what the backup run does
with it.

**One result per leg, and a leg that did not run is `null`, never a zero result** —
`BackupEngine.Outcome(local: Result?, cloud: Result?, problem: Problem?)` — because "0 copied to the
cloud" and "there is no cloud destination" are different sentences and the report must never say the
first when it means the second. `Progress` names which leg it is in, so the dialog reads *Backing
up…* then *Uploading to `<provider>`…*, and the total is both legs' units summed before either runs.

**The cloud never holds a sidecar.** The local leg can land a `.soil` and its `-wal` as a
near-atomic pair because the SAF `.part`/`.old` swap makes them so; the cloud has no swap, so two
separate uploads can tear, and a fresh main file paired with a stale `-wal` corrupts on restore.
`data/backup/SelfContainedSnapshot.kt` absorbs the WAL first: it copies main + `-wal` into
`cacheDir/backup/cloud/`, opens the copy with the file's cached raw key (falling back to the
passphrase, and — the bug the walk found, below — verifying that cached key against the copy and
invalidating it if stale, `KeyOpener`'s own recipe), runs `PRAGMA wal_checkpoint(TRUNCATE)`, closes,
and answers the file only if the copy's `-wal` is now empty or gone and the copy probes
`Encrypted`. Anything else answers `null` and that file is **refused this run** — counted failed,
retried next run, nothing uploaded. Every cloud upload is therefore one self-contained file, never a
pair. A stale `<name>-wal` still sitting in the folder's own listing is deleted **before the
stamp**, verifiably — the arc's **one remote delete** — kept for the day something else ever leaves
one there.

**`CloudBackupLeg.run`** (`data/backup/CloudBackupLeg.kt`, beside `BackupEngine` so neither file
grows past its line budget; it **calls** the engine's shared pieces — `compactPass`, the work list,
the store checkpoint, the index purge — rather than copying them):

1. `ensureFolder(["Backups", device])` — fail fast, uploading nothing, on any of the four typed
   failures below.
2. **One `list` of the device folder at leg start**, kept current in place as the leg goes (an
   upload replaces its row, a delete removes one) — a `list` costs most of a second on this seam,
   so the leg pays it once, not once per file.
3. The work list, same source as the local leg's: per notebook, `compactPass` unless the local leg
   already compacted that id this run (a shared `compactedThisRun` set — VACUUM twice is a minute
   for nothing), snapshot, `upload` (MIME `application/octet-stream`, budget
   `CloudTimeouts.uploadBudgetMs(length)`), corroborate with `ExportVerification.cloudVerdict` — the
   same pure function the export destination uses. Agreeing stamps `BackupConfig.cloudStamps`
   **per success, immediately**, the local rule; disagreeing counts the file failed and stamps
   nothing, safe to retry because an upload is replace-by-name.
4. Every extension store — checkpoint if this process holds it open, snapshot, upload, corroborate;
   no stamps, the index's own treatment.
5. The index last: `compactIfNeeded(0)` + checkpoint, then snapshot, upload, corroborate.
6. `cloudLastRunAt`/`cloudLastCopied`/`cloudLastSkipped` written only when at least one upload
   landed, and `cloudStamps` pruned.

**Mid-leg failure ends the leg, keeping every stamp already earned.** A `CloudNotConnectedException`,
a `CloudNetworkException`, or a plain no-answer on any single upload stops the leg there and then —
continuing would pile 60–120 second upload budgets onto a dead link one file at a time. A
corroboration miss, by contrast, is per file and never ends the leg: only that one file is retried
next run.

**The device folder** (`data/backup/DeviceFolder.kt`) defaults to a sanitized `Build.MODEL` plus
eight hex from a random UUID — og's D4 shape, never the hardware serial (a durable identifier has no
business sitting in the person's cloud forever). Minted lazily: the Backup screen mints and stores
one the first time it renders the Cloud section and finds none; the engine mints one itself if a run
starts before the screen ever has. The screen offers **Rename…** (`NameDialog`, judged by
`library.NameRules` first and then `CloudArgs.requireName` for the seam's own bounds) — **a
different name resets `cloudStamps`**, because a stamp is a statement about one destination and a
folder that has never seen a file cannot claim to hold it; re-entering the same name keeps them.

**Config (`BackupConfig`, additive, `VERSION` stays 1).** `cloudEnabled` (arc 25 / V2's checkbox) ·
`cloudDeviceFolder` · `cloudStamps` (the **second** stamp map, sharing no field with the local
leg's `stamps`) · `cloudLastRunAt` / `cloudLastCopied` / `cloudLastSkipped`. A field with a default
is readable by an older build and an older blob reads fine into a newer one, which is why none of
this moved the version.

**The report.** One dialog covers both legs: both clean → *Backup complete*, the local block, a
blank line, then `<provider>: N copied, M skipped.` plus its own stores/index sentences; any problem
in either leg → *Backup didn't finish* with the same two blocks and the cloud problem as one
sentence — *not connected — connect and back up again* · *could not be reached; nothing more was
uploaded; try again* · *didn't answer; check `<provider>` before relying on it* · *no longer
available on this device*. A leg that did not run carries no block at all. "Untouched / tried again
next time" stays true for both legs alike.

**Measured (Nomad, `DRIVE_PLAN.md` § V4 ledger).** First run: `ensureFolder` at depth 2 in
3 162 ms, exactly one `list`, 42 `.soil` uploads all `agrees=true`, the index uploaded. Second run:
0 copied / 43 skipped, only the 7 store `.db` files plus the index re-uploaded — no `.soil`
re-uploads. Rename to a fresh device folder reset the stamps and re-copied all 42 files into the new
folder.

**Design calls recorded at V4 (binding unless the user says otherwise).** `Problem.NO_FOLDER` is
gone — `NO_DESTINATION` replaces it, one sentence covering both ways out · a local `FOLDER_GONE`
result is a leg outcome and does not stop the cloud leg from running · `CLOUD_GONE` is decided by
re-asking discovery after a plain, otherwise-unexplained call failure · a store whose package cannot
be derived from its own filename counts against `storesFailed` on the cloud leg · the minted device
folder's charset is narrower than the one `NameRules` allows a typed name (no dot, no space; model
capped at 48 characters) so a minted name is always legal at the seam · Rename… judges with
`NameRules` first and `CloudArgs.requireName` second · the cloud leg re-runs the index purge and
checkpoint even right after the local leg already did (a near no-op, but two legs must not disagree
about which of them owns it) · a finished run re-renders the Cloud section (one extra discovery plus
a `status()` call).

**Not walked; JVM-tested instead.** The *Nowhere to back up* dialog — the walk's device already had
a SAF folder chosen, and the screen has no affordance to clear one, so the `NO_DESTINATION` path was
never exercised live; `CloudBackupRules`' pure rule for it is pinned by test.

**Bug found on the walk and fixed.** A 7 MB extension store that this session had not opened failed
every run with "file is not a database": `KeyMaterial.peekOrLoad` can hand back a **stale** cached
key for a file re-minted since the key was derived, and only `KeyOpener` knows to verify-and-
invalidate it. `SelfContainedSnapshot.absorbWal` now takes `KeyOpener`'s own recipe instead —
verify the cached key against the copy, invalidate a stale one, fall back to the passphrase — and
the re-walk showed the invalidation firing, then a clean snapshot and a clean upload.

---

## The destination writer

`SafBackupWriter` — hand-rolled over platform `DocumentsContract` (`androidx.documentfile` is not
on the classpath and the no-new-dependencies rule stands). **`writeAtomic` is the one write path**,
og's `.part` discipline made whole:

1. **One directory listing serves the whole write** (K3 review — a listing is a whole-directory
   provider query, and the K2 shape paid up to five per file): sweep a stray `.part`, find the
   existing copy, and handle a leftover `.old`.
2. **A `.old` standing alone is the last good copy** a crash stranded mid-swap — it is renamed
   back under its real name, **never swept** (K3 review: the K2 shape deleted it up front, and a
   failed write after that left *no* copy at all — "a torn write never replaces a good backup"
   forbids deleting the last one too). A `.old` beside a complete copy is a finished swap's failed
   final delete and goes.
3. Stream to `<name>.part`, verify the landed size (a **single-document** `COLUMN_SIZE` query,
   not a re-listing).
4. The swap: rename the previous copy to `.old`, rename the part in, drop the `.old` — no window
   has neither file complete under a name a restore would read. Rename results are kept and
   reused, never re-found.

Nothing here throws; every failure logs and answers false/null, and the engine counts it. Content
URIs are never logged (a tree URI can carry the folder's display name); file *names* are UUIDs and
safe.

---

## Failure table

| Failure | What happens |
|---|---|
| No folder chosen | Dialog before anything runs; nothing copied. |
| Folder deleted / ejected / grant revoked | `FOLDER_GONE` dialog; nothing copied, notebooks untouched. |
| Notebook file missing from the Garden | Counted `missing` (skipped, not failed); run continues. |
| Notebook held open in-process | Counted `held`, never copied live; structurally unreachable from the UI. |
| Compact pass cannot open a file | Logged; the file is **still copied as the bytes it is** (og's rule). |
| Copy fails / short write | `.part` deleted, previous good copy stays under its own name; counted `failed`, no stamp, retried next run. |
| Crash mid-swap | Worst case a stale `.part`/`.old` pair — the `.part` is swept next write, a lone `.old` is renamed back (it is the last good copy); both invisible to a restore. |
| Stale destination `-wal` cannot be verifiably removed | The copy does **not** count and does not stamp — a fresh `.soil` + old `-wal` corrupts on restore. |
| Index checkpoint busy | The non-empty index `-wal` is snapshotted and copied alongside; both must land or `indexCopied` is false. |
| Index snapshot fails its probe | Falls back to streaming the live file (+ its live WAL) — last resort, logged. |
| Stamp write fails | Logged, run continues; that notebook re-copies next run (the safe direction). |
| Extension store copy fails | Counted `storesFailed`; the dialog says the run didn't finish and names how many stores did not land. Nothing else in the run is affected, and every store is retried next pass anyway. |
| Extension store is zero length | Skipped silently (a create that never finished); the destination keeps whatever good copy it has. |
| Anything else throws | The top-level catch turns it into a `failed` count and the dialog says the run didn't finish; the app never crashes under the progress dialog. |
| Corrupt `backup` config blob | Reads as a fresh config; worst case is re-copying everything. |
| Neither a SAF folder nor a connected cloud provider (arc 25 / V4) | `Problem.NO_DESTINATION` dialog before anything runs; the screen pre-checks the same rule. |
| Cloud leg: no account connected | Leg stops there; report names it *not connected — connect and back up again*; every stamp already earned this run stays. |
| Cloud leg: provider unreachable | Leg stops there; *could not be reached; nothing more was uploaded; try again*. |
| Cloud leg: provider didn't answer | Leg stops there; *didn't answer; check `<provider>` before relying on it*. |
| Cloud leg: provider uninstalled mid-run | Leg stops there; *no longer available on this device*. |
| Cloud leg: a file's upload lands but the provider's reported size disagrees | Counted failed, **not stamped**, no delete; retried next run (upload is replace-by-name). |
| Cloud leg: a stale `<name>-wal` cannot be verifiably removed | That file's stamp is withheld, exactly as the local leg's own stale-sidecar rule. |
| Cloud leg: a notebook file will not absorb its WAL into a self-contained snapshot | Refused this run — counted failed, retried next run, nothing uploaded. |

---

## What this arc deliberately did not do

- **No restore** (arc 16's Import recovers a single notebook; whole-library restore is a future arc,
  and arc 21 / W5 confirmed the same answer for extension stores — the manual copy-back is
  documented above and a restore screen is in `BACKLOG.md`). Arc 25 / V4 confirms the identical
  answer for the cloud leg: uploading is the whole of it, there is no restore-from-cloud path, and
  a backup `.soil` picked up by hand from Drive is recoverable the same way any other export is —
  through arc 16's ordinary Import. - **No automatic runs**, no scheduler.
  - **No per-device subfolder for the local (SAF) leg** (og's LOCAL shape; only debug's `dev/`
  split) — the cloud leg's own `Backups/<device folder>/` is arc 25 / V4's addition and does not
  change the local leg's flat shape. - **Drive landed** (arc 25 "Drive," V4, 2026-09-04) as the
  cloud leg documented above, through the eighth extension point rather than as backup-specific
  code — see [`docs/cloud.md`](cloud.md).
