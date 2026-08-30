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

**Pure core — no extension involvement** (the arc wizard's first lock). Everything a backup
touches is host-only by the standing seam rule anyway: keys, Garden paths, the index. SN stays at
**four** capability points; Google Drive backup is deferred to a future arc *as an extension*, and
that will be its own capability-point user decision — nothing here scaffolds for it.

**Backup only — no restore.** A single notebook is already recoverable through arc 16's Import
(every backup file is a self-describing `.soil` with `notebook_meta`); whole-library restore is its
own future arc. og's `docs/backup.md` + `NotebookCompactor` were the reading references — no code
copied.

**Status: arc 17 complete** — K1 compaction (`73d6490`) · K2 backup (`7fb0aa2`, user checklist
passed) · K3 review (high, 10/10 findings fixed — the destination-integrity cluster the headline),
this doc.

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
- **Toast confirms, dialog explains**: a clean run gets a toast naming its counts; anything the
  run could not do gets a dialog with the honest per-count summary. "Skipped" is one number
  covering four honest reasons (up to date · excluded · open elsewhere · file missing) — the
  distinction matters to the engine, not the reader.
- The status line is read back from the stored config, not the in-memory result, so it says the
  same thing after a relaunch.

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
4. **The index, last, after every stamp**: purge (`SnIndex.compactIfNeeded(0)`) + checkpoint, then
   **snapshot to a local temp and probe it** (encrypted header, byte-for-byte the live length)
   before streaming — a torn copy of the live index is worse than no backup; only a failed
   snapshot falls back to streaming the live file.
5. **`lastRunAt`** — only when at least one destination write succeeded — and the stamp map
   pruned of notebooks the index purge removed.

**The WAL rule, both file kinds (K3 review made it whole).** A non-empty `-wal` beside a source
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
| Anything else throws | The top-level catch turns it into a `failed` count and the dialog says the run didn't finish; the app never crashes under the progress dialog. |
| Corrupt `backup` config blob | Reads as a fresh config; worst case is re-copying everything. |

---

## What this arc deliberately did not do

- **No restore** (arc 16's Import recovers a single notebook; whole-library restore is a future
  arc). - **No automatic runs**, no scheduler. - **No per-device subfolder** (og's LOCAL shape;
  only debug's `dev/` split). - **No Drive** — a future arc, as an extension, behind its own
  capability-point user decision.
