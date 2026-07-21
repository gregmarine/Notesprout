# Stability Code Review — Crashes, Corruption, and Bricking

**Date:** 2026-07-20 · **Branch:** `sprout` (clean, HEAD `fe8fb6e`) · **Scope:** entire
`apps/notesprout_android` main source (287 Kotlin files)

> **STATUS (2026-07-21): all four fix phases implemented on `pruning-stability`.**
> Phase 1 `6e2b675` (P0 destruction paths) · Phase 2 `8f592b4` (P1 crashes/crash-loops) ·
> Phase 3 `78b48fd` (P2 silent data loss) · Phase 4 (events/locale/compactor/hardening) — see
> `git log` for the per-phase breakdown. Debug + release build; unit tests pass.
> **Remaining:** the on-device validation sweep on G102 (end of Fix plan below), and two
> deliberately-skipped LOWs: explicit non-deleting handlers on zetetic opens (delete behavior
> bytecode-verified impossible while the codec is present) and cover snapshots omitting shapes
> (visual only). Stroke-attribute fidelity is backlogged per decision 4.

**Method:** ten parallel deep-read reviews (crypto, data layer + index, backup/restore,
export/import, drawing engine, activities/lifecycle, clipboard/links/transfer,
calendar/scratchpad/sticky, recognition, plus an exhaustive cross-cutting sweep that cataloged
every database-open site). Every CRITICAL claim was then independently re-verified against the
source; the shipped `sqlcipher-android-4.6.1` AAR (Java bytecode **and** native `.so`) was
disassembled to settle the corruption-handler question. Findings the reviewers raised but that an
existing defense already covers were dropped (the load-bearing ones are listed in the Appendix).

---

## Executive summary

The lessons of the July wipe-bug family were genuinely absorbed: **every** Room/raw open of a
`.soil` or the index now routes through non-destructive handlers with sane key fallback, wrong-key
SQLCipher opens throw rather than delete, and `user_version` is preserved across all three
`sqlcipher_export` round-trips. The remaining danger is concentrated in a small number of places:

1. **`SoilMigrator`'s in-place replace sequence** — one unguarded framework-SQLite open that can
   reproduce the exact historical wipe mechanism, a rename-failure branch that deletes the only
   surviving copy of a notebook (or the index), and a crash window with no orphan-tmp recovery.
2. **The restore / import "replace" flows** — both destroy the existing copy *before* the
   replacement is safely in place (RestoreEngine wipes the whole library; import Replace
   hard-deletes the victim notebook before the import is committed).
3. **The composite-object insert path** — the `UNIQUE constraint (1555)` remap fix (123acbd)
   covered only the four `replace*Subtree` helpers; all four `insert*Subtree` helpers still reuse
   source child ids, so **pasting a link or sticky note is a deterministic crash**.
4. **Two startup/page-load crash-loop vectors** — unguarded per-item clipboard decode at app
   launch, and unguarded stroke-blob decode in the heading/text readers.
5. **The third sibling of the columnar dead-write family** — shape resize/rotate in the three
   index-table hosts (scratchpad/calendar/day-note) still writes through the dead JSON path.

One widely-feared claim was **refuted during verification**: zetetic SQLCipher 4.6.1's
`DefaultDatabaseErrorHandler.onCorruption` checks `SQLiteDatabase.hasCodec()` first and **returns
without deleting** when the codec is present (bytecode-verified; the bundled `libsqlcipher.so` is a
codec build, so `hasCodec()` is always true). Encrypted connections therefore cannot self-delete on
mid-session corruption. The delete-on-corruption risk exists **only on framework
`android.database.sqlite` opens** — which is exactly where the remaining holes below are.

---

## Findings

Severity: **P0** = can destroy/brick user data · **P1** = deterministic or likely crash in normal
use · **P2** = silent data loss or edge-case crash · **P3** = hardening / polish.
Paths are relative to `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/`.
"✓ verified" = re-checked directly in source during synthesis, beyond the original reviewer.

### P0 — data loss / bricking

**P0-1 · SoilMigrator replace step can delete the only surviving copy** ✓ verified
`crypto/SoilMigrator.kt:80-84` (encrypt), `:125-129` (decrypt), `:181-185` (rekey).
All three round-trips end with `deleteSoilAndSidecars(file)` → `if (!tmp.renameTo(file)) { tmp.delete(); error(...) }`.
On a rename failure the original is already gone and the code then deletes the verified tmp — the
only remaining copy of the notebook, or, when run on `notesprout.db` (plaintext-index upgrade,
rotation final step), of the entire library index. The error message "original notebook is
unchanged" is false at that point. `docs/encryption.md`'s "the original is never touched on
failure" is violated at exactly this step.
**Fix:** never delete the tmp on rename failure (keep it + surface the path). Better: reorder to
`rename(file → file.bak)` → `rename(tmp → file)` → `delete(file.bak)`, so no window ever has zero
copies; on any failure roll `file.bak` back.

**P0-2 · Crash window between delete-original and rename-tmp has no recovery** (same sites as P0-1)
Process death / power loss between `deleteSoilAndSidecars` and `renameTo` leaves no original and an
orphan `*.enc.tmp` / `*.dec.tmp` / `*.rekey.tmp`. Only `GlobalRotation.kt:127-135` recovers
orphans, and only notebook `.rekey.tmp`. Consequences:
- Bulk conversion resume (`GlobalConversion.kt:106-109,144`): missing file is treated as "deleted
  since sweep started" and dropped — the notebook's full contents sit unrecovered in `.enc.tmp`.
- **Worst case — the index:** if the one-time plaintext-index upgrade (`NotesproutIndex.kt:74`) or
  the rotation-final index rekey (`:206`) dies in the window, next launch probes the missing
  `notesprout.db` as `Invalid` and silently creates a **fresh empty encrypted index** — the whole
  library structure (folders, notebook rows, calendar, events, scratchpad, clipboard) vanishes
  while the real index sits stranded in an orphan tmp forever.
No `fsync` of file or directory anywhere in the migrator widens the power-loss window.
**Fix:** the P0-1 `.bak` ordering closes most of this; additionally add a universal orphan-tmp
recovery pass (at bootstrap and before any notebook open: if `X` missing and a verified `X.*.tmp`
exists, rename it back), and fsync file + parent dir around the swap.

**P0-3 · `encryptInPlace` WAL checkpoint opens the live file with the framework default (deleting) error handler** ✓ verified
`crypto/SoilMigrator.kt:44-48`: `android.database.sqlite.SQLiteDatabase.openDatabase(path, null, OPEN_READWRITE)`
— no `NonDeletingErrorHandler`. A corruption report during this open/checkpoint **deletes the
user's file**; the surrounding catch logs "checkpoint skipped" and proceeds. Nastiest scenario
(trigger `MainActivity.kt:1985`): `MainActivity.encryptNotebook` calls `encryptInPlace` with **no
probe**, trusting the index row. If a prior run crashed after the file was encrypted but before
`setEncryptionState`, retrying "Encrypt" opens the already-encrypted `.soil` with plaintext
framework SQLite → looks corrupt → deleted; the subsequent `ATTACH ... KEY ''` **recreates an empty
file**, `sqlcipher_export` copies nothing, verification passes on the empty DB, and an empty
encrypted notebook is renamed into place. Silent total wipe. (All other `encryptInPlace` callers
probe `Plaintext` first and are safe.)
**Fix:** open the checkpoint connection with `NonDeletingErrorHandler` (4-arg `openDatabase`
overload) — or route through `SoilCrypto.openRawPlaintext`, which also probe-guards; and add a
`probe() == Plaintext` check in `MainActivity.encryptNotebook` before migrating.

**P0-4 · Plaintext-index upgrade opens `notesprout.db` with Room's default (deleting) corruption handler** ✓ verified pattern
`data/index/NotesproutIndex.kt:70-71` + `buildRoom` `:127-147`: the `Plaintext` branch calls
`buildRoom(app, dbFile, null)`, and with `factory == null` no `openHelperFactory` is set — Room's
default framework helper deletes the DB on corruption. `probe()` only reads `sqlite_master`, so a
torn-WAL / partially corrupt legacy index passes probe and then reports corruption mid-upgrade →
entire library structure deleted and recreated empty. Every pre-encryption user hits this branch
exactly once on first launch of the new build — the highest-traffic moment for it to matter.
**Fix:** one line — wrap the null-factory branch in
`NonDestructiveOpenHelperFactory(FrameworkSQLiteOpenHelperFactory())`, mirroring `SoilDatabase.builder`.

**P0-5 · Restore can wipe the live library and install an incomplete/torn copy** ✓ verified
`data/backup/RestoreEngine.kt:53-66` + `data/backup/RestoreSource.kt` (fetch loops).
Two compounding defects:
- **Fetch failures ignored:** per-notebook results of `SafBackupReader.copyTo(...)` and
  `client.downloadTo(...)` are discarded (only the index fetch aborts). Wi-Fi drop or Drive token
  expiry mid-fetch → staging silently incomplete, and a stream that dies mid-copy leaves a
  **partially written** `.soil` in staging. RestoreEngine then treats staging as good.
- **Wipe-before-copy, staging destroyed on failure:** the live index + Garden are deleted
  (`:54-56`) *before* staged files are copied in (`:59-60`); any exception in the copy (most
  plausibly `ENOSPC` — the library transiently exists twice) is caught → `Failed`, and the
  `finally` deletes staging: live copy gone, staged copy gone. Staging also lives in OS-clearable
  `cacheDir`, and the staged index is validated only by `exists() && length() > 0` (`:43`).
**Fix:** (a) fail the fetch on any single file failure (write each to `.part`, rename on success);
(b) integrity-probe the staged index (and ideally SQLite-header-check each `.soil`) before touching
the live library; (c) swap, don't wipe: move live `notesprout.db*` + `Garden` aside (rename), copy
staged in, delete the aside copy only on success, roll back on failure; (d) free-space pre-check;
(e) keep staging on failure.

**P0-6 · Import "Replace" destroys the victim notebook before the import is committed** ✓ verified
`MainActivity.kt:2824-2831`: the Replace button immediately `softDeleteNotebook(conflictId)` +
`soilFile(conflictId).delete()`, then calls `executeImport` — which only *shows the keying
chooser*. Cancel there (`:2912`, `:2979`), cancel of the passphrase prompt (`:2969`), or process
death → original gone, nothing imported. The hard delete also violates the soft-delete rule (the
trash row can never restore), and the sibling delete at `:2323` (`executePickerOperation`) can
delete a `.soil` that an open `NotebookActivity` is writing beneath (deep-link stacks MainActivity
`singleTop` over it) — the open Room connection keeps writing to the unlinked inode and every
subsequent edit is silently lost.
**Fix:** don't touch the victim until the import has fully committed; then rename it aside
(`.replaced.bak`) or leave the file and only soft-delete the row, deleting the file later via the
normal trash sweep. Guard both delete sites against a currently-open notebook id.

**P0-7 · Untrusted `notebook_meta` ids flow into filesystem paths and ATTACH SQL (path traversal)**
`data/SoilFile.kt:13` builds `File(garden, "$notebookId.soil")` unvalidated; the id comes from the
imported file's own `notebook_meta` (`MainActivity.kt:2738`, `NotebookImporter` `resolvedId`), as
do folder ids fed to `ensureFolderWithId`. A crafted `.soil` with
`notebookId = "../../databases/notesprout"` overwrites files outside `Garden/`; the sidecar deletes
at `NotebookImporter.kt:110-112` follow the same path. Related: `SoilMigrator` interpolates
`file.absolutePath` unescaped into `ATTACH DATABASE '...'` (`SoilMigrator.kt:57,107,163`) — with a
crafted id that becomes SQL injection into the ATTACH statement.
**Fix:** strict UUID-shape validation (regex) on every `notebookId` and folder id read from a
manifest before it touches a path, a query, or `ensureFolderWithId`; escape (or reject quotes in)
paths interpolated into ATTACH.

### P1 — deterministic / likely crashes

**P1-1 · Pasting a link, sticky note, or fallback-text object crashes: insert-side child-id remap is missing** ✓ verified
`data/ObjectColumns.kt:439` (`insertStickyNoteSubtree`), `:486` (`insertHeadingSubtree`), `:489`
(`insertTextSubtree`), `:533` (`insertLinkSubtree`) — all insert `toRows()` output via plain
`@Insert` (ABORT) **without** `remapDescendantIds`; the 123acbd fix covered only the four
`replace*Subtree` helpers (`:449`, `:499`, `:506`, `:543`). `LinkRender.translate` /
`StickyNoteRender.translate` re-id only the parent, and lasso-copy snapshots live child-row ids.
Three reviewers independently confirmed; one agent's claim that the sticky insert helper had the
remap was a misread of line 449 (which is inside `replaceStickyNoteSubtree`).
Deterministic failures: copy a link/sticky → paste on the same page (child rows already live);
paste the same clipboard **twice** anywhere; cut → paste (cut soft-deletes only the parent);
scratch-pad/calendar "Send to Notebook" of the same selection twice; `createLinkFromSelection` /
`removeLink` on composites containing fallback heading/text (`NotebookActivity.kt:6582-6602`,
`:6673-6688`). The `SQLiteConstraintException` escapes `lifecycleScope.launch` → app crash
(transaction rolls back, so no corruption).
**Fix:** apply `remapDescendantIds` inside all four `insert*Subtree` helpers (descendant ids are
private to the composite — the doc comment on `remapDescendantIds` itself says reuse is unsafe).
That single change also covers the link-create/unlink and transfer paths. Remove the now-redundant
caller-side heading deep-freshen or leave it (harmless).

**P1-2 · Startup crash-loop: persisted-clipboard per-item decode unguarded**
`data/ClipboardMappers.kt:74-80` (`decodeFromString` per `ClipItem`, no catch) invoked from
`NotesproutApplication.kt:64` on `appScope` (no `CoroutineExceptionHandler`). One malformed or
version-skewed item — e.g. a `LinkTarget` whose FQCN sealed-class discriminator no longer resolves
(the documented landmine), or a required-field change — throws on **every** launch; the user can
never reach the UI to clear the clipboard.
**Fix:** per-item `runCatching` (skip bad items, log count), plus P1-4's appScope handler as the
backstop.

**P1-3 · Page-load crash-loop: heading/text stroke-blob decode unguarded**
`data/ObjectColumns.kt:159` (`toTextRender`), `:186` (`toHeadingStroke`): `blob?.let { unpackStrokes(it) }`
with no `runCatching` — the sibling readers (`toLinkRender` `:222`, `toStickyNoteRender` `:259`)
and `LiveStroke.fromRow` all guard the identical decode. One truncated/bit-rotted blob → crash on
every load of that page; launch-restore replays the surface stack → crash loop, notebook
effectively unopenable (data intact).
**Fix:** wrap both in `runCatching { ... }.getOrNull()`, matching the siblings.

**P1-4 · `appScope` has no `CoroutineExceptionHandler`; seal steps unguarded**
`NotesproutApplication.kt:20` (`SupervisorJob() + Dispatchers.IO` only). `NotebookActivity.kt:3696`
fire-and-forgets `sealNotebook` (`:3912+`) whose unguarded steps — `saveStrokes(db)`,
`hardDeleteOldSoftDeleted`, the PRAGMA block, `db.close()` — crash the whole app seconds after the
user left the notebook on disk-full/`SQLiteFullException`, and the remaining seal steps are
skipped. Same pattern: `ScratchpadActivity.kt:2076/2083`, `CalendarActivity.kt:2237/2263`,
`DayDetailActivity.kt:2345/2352`, `NotebookActivity.kt:6019`.
**Fix:** install a logging `CoroutineExceptionHandler` on `appScope`; `runCatching` each seal step
individually so a failed step doesn't skip checkpoint/close.

**P1-5 · Bootstrap has no catch → crash loop at launch**
`BootstrapActivity.kt:44-58`: `boot()` runs `ensureReady`/`unlockAndOpen` bare in
`lifecycleScope.launch`. Any exception — index Room migration failure, one-time upgrade
`encryptInPlace` failure, or `EncryptedSharedPreferences.create` throwing
(`GeneralSecurityException` from a corrupted Tink keyset; Keystore briefly unavailable right after
boot) — crashes, and relaunch repeats it. `PassphraseStore`/`DerivedKeyStore`/`AttemptLimiter`
construct `EncryptedSharedPreferences` with zero error handling, and first-time keyset creation of
the same prefs file can race across threads (known androidx-security corruption mode).
**Fix:** try/catch in `boot()` → error screen with Retry + "restore from backup" escape hatch;
synchronize (single lazy holder) EncryptedSharedPreferences creation; catch Keystore exceptions
with one retry.

**P1-6 · Backup run crashes the app on engine-level throw**
`BackupSettingsActivity.kt:231-245`: `try { BackupEngine.run(...) } finally { ... }` — no `catch`.
Engine-internal Room/repo calls (`notebooksNeedingBackup`, `markNotebookBackedUp`,
`checkpointAndVacuum`'s `db()` which throws `IllegalStateException` if the index was sealed
underneath) propagate → uncaught crash mid-backup.
**Fix:** add `catch` → toast + log; treat as failed run (see also P2-8: don't stamp `lastRunAt`).

**P1-7 · SAF backup replace is delete-then-write; restore trusts the result**
`data/backup/SafBackupWriter.kt:32-49`: `findFile(fileName)?.delete()` destroys the previous good
backup before new bytes stream in; a mid-write failure (USB unplug, destination full) leaves a
truncated file under the final name. For `notesprout.db` that later feeds P0-5's weak validation →
restore installs a truncated index → library bricked post-restore.
**Fix:** write to `name.part`, rename over the old file on success; delete `.part` on failure.

**P1-8 · Missing-file verify fabricates an empty DB and "succeeds"**
`crypto/SoilCrypto.kt:51-52, 87-88, 94-103`: `verifyPassphrase`/`verifyRawKey`/`openRawEncrypted`
use zetetic `openOrCreateDatabase` (create-if-missing), and `openRawPlaintext` uses
`CREATE_IF_NECESSARY`. Verifying/opening a **missing** path creates an empty DB keyed to whatever
was supplied and returns success. `KeyResolver.promptAndVerify` (`KeyResolver.kt:198-199`) has no
`file.exists()` guard, so opening a notebook whose `.soil` is absent (e.g. a P0-2 orphan state)
"verifies" the first passphrase typed and shows a fabricated empty notebook — which also destroys
the evidence needed for manual orphan recovery. Same at `NotesproutIndex.rekey:192`.
**Fix:** `file.exists()` guard at the top of the verify helpers (return false / distinct error) and
in `promptAndVerify`; open existing files with `OPEN_READWRITE` (no create flag) where creation is
not intended.

**P1-9 · Text viewer crashes on any failed `.soil` open**
`PageTextViewerActivity.kt:440-479` (`loadText`) and `:318-327` (correction dialog): DB open + DAO
work inside `lifecycleScope.launch` with `finally { db.close() }` but **no catch** — a stale key,
locked, corrupt, or missing file throws `SQLiteException`/`SoilLockedException` → process crash
(file itself is safe). Not surface-restored, so no loop.
**Fix:** wrap in try/catch → "Couldn't open" message, mirroring `failOpen`'s shape.

### P2 — silent data loss / edge-case crashes

**P2-1 · Columnar dead-write, third sibling: shape resize/rotate in the three index-table hosts**
`ScratchpadActivity.kt:1922`, `CalendarActivity.kt:1684`, `DayDetailActivity.kt:1936` —
`persistShapeTransform` writes via `updateObjectData` (JSON), but `toShapeRender`
(`ObjectColumns.kt:127-138`) reads the typed columns first whenever `shapeType != null`, so for any
columnar shape row (pasted shapes insert with `data=""`; lasso-moved rows are columnarized by
`persistMovedObjects`) the transform is a **dead write** — the shape silently snaps back on next
load. `NotebookActivity.kt:6310` was correctly converted to `updateColumns`; these three sites were
missed (exact c98af2e family). Also delete the dead duplicate `onStrokesMoved` JSON block at
`ScratchpadActivity.kt:598-644` (overwritten at `:646` — one refactor away from regressing).
**Fix:** switch all three to `updateColumns` (same shape as the NotebookActivity fix).

**P2-2 · Lasso drag-move strips stroke attributes**
`OnyxNotebookView.kt:1394-1396`, `GenericNotebookView.kt:401-403` build the drag copy as
`LiveStroke(it.id, points)`, dropping `color`/`strokeWidth`/`srcPoints`; `NotebookActivity.kt:1882`
(and undo `:9779`) then persists defaults (`#000000`/3.0f) and replaces legacy JSON (pressure/tilt)
with a points-only blob. Invisible today (all in-app ink is default black) but permanently corrupts
attributes of imported `.soil` ink (Paintsprout interop) on first move; copy path (`:5148-5152`)
strips the same into the clipboard; undo cannot restore.
**Fix:** carry `color`/`strokeWidth`/`srcPoints` through the drag copy and copy/paste snapshots.

**P2-3 · Index mutators crash on a corrupt legacy row**
`data/index/IndexRepository.kt:132,144,578,589` call `entity.notebookMeta()` bare
(`IndexObjectColumns.kt:44` throws on malformed legacy `data` JSON); `getEncryptionInfo` sits on
the notebook-open path — one corrupt row makes that notebook unopenable via crash. Every other call
site guards this. **Fix:** `runCatching` with a sane default at those four sites.

**P2-4 · Replace-encrypted import: file/index inconsistency window**
`NotebookImporter.kt:154-176`: after `copyTo(gardenFile)` succeeds, a throw in
`renameNotebook`/`setEncryptionState` leaves the new file described by the old index encryption
state → stranded in NEEDS_UNLOCK (no delete risk thanks to non-destructive handlers). Also
`file.copyTo(gardenFile, overwrite=true)` is a non-atomic replace (process death → truncated
garden file). **Fix:** copy to `gardenFile.new` + rename; do the two index writes before/after in
an order that fails safe, or wrap in a retry-on-open repair.

**P2-5 · `ensureFolderWithId` trusts imported folder ids**
`IndexRepository.kt:44-65` resurrects and **renames/moves** an existing soft-deleted folder sharing
the UUID; a folder id colliding with a notebook id throws `SQLiteConstraintException`
(import aborts mid-way, earlier renames not rolled back — the doc's claimed "skip and land one
level up" guard does not exist). **Fix:** with P0-7's id validation in place, add a type check
(skip + land one level up when the id exists as a non-folder) and only resurrect folders that are
ancestors being deliberately restored; wrap placement in a transaction.

**P2-6 · Rotation resume caches an unverified "old global passphrase"**
`EncryptionSettingsActivity.kt:191-199` + `GlobalRotation.kt:172-179`: a mistyped old passphrase is
stored via `setGlobalPassphrase` unverified; resume then quarantine-downgrades every remaining
GLOBAL notebook to NOTEBOOK scope and leaves the wrong passphrase cached. No data loss, but one
typo silently mis-scopes the library. **Fix:** verify the entered passphrase (e.g. against the
index file) before caching; abort resume with a clear error on mismatch.

**P2-7 · Async seal races: `KeySession.clear()` + cross-thread stroke-list read + unserialized undo**
- `NotebookActivity.kt:3973`: seal (seconds — compaction + VACUUM) unconditionally clears the
  process-global `KeySession` after the user may have opened another encrypted notebook. Use
  MainActivity's guarded idiom (`if (KeySession.entry?.notebookId == nbId) clear()`,
  `MainActivity.kt:2042`). Related: `LinkTargetPickerActivity.kt:488/:1230/:1239` use bare
  `KeySession.getFor` with no `resolveNotebookKey` fallback — grid renders empty / "Could not
  create page" after the race or process death (probe guard prevents data loss).
- `NotebookActivity.kt:4468` + `OnyxNotebookView.kt:2367`: `saveStrokes` on IO calls
  `strokes.toList()` on a plain `ArrayList` the UI thread mutates (`renderStroke:457`,
  `eraseAtPath:567`, drag commit `:1551`) → rare CME/torn copy on the core ink-save path. Snapshot
  the list on the main thread before dispatching, or use a synchronized/CoW list.
- `NotebookActivity.kt:8026-8038`: `performUndo`/`performRedo` launch unserialized coroutines;
  interleaved executions write full stroke lists that resurrect just-undone strokes (view/DB
  divergence). Add a Mutex or in-flight guard.

**P2-8 · Backup bookkeeping/consistency gaps**
- `BackupEngine.kt:176`: `lastRunAt` stamped even when every destination failed → false "backed up
  just now". Stamp only on ≥1 success.
- `BackupEngine.kt:120-150` + `:191-196`: encrypted notebooks skipped by pre-backup compaction are
  copied without WAL absorption — if an abnormal exit left a nonempty `-wal`, the backup silently
  misses committed writes. Checkpoint via a keyed open when possible, else copy the sidecars too.
- `BackupEngine.kt:153-166`: the live, still-open index is streamed to destinations; a concurrent
  `appScope` write (`touchNotebook` from a late seal) + auto-checkpoint can tear the copy — and
  restore's weak validation (P0-5) would trust it. Copy from a sealed/temp snapshot, or verify the
  copy's header after streaming.
- `DriveApiClient.kt:92`: non-200 mid-pagination `break`s and returns a silently truncated list
  (matters at >1000 files). Throw instead.
- `DriveAuthActivity.kt:130-131` + `BackupSettingsActivity.kt:174`: transient `accountEmail()`
  failure → `driveAccountEmail = null` → Drive backup silently disabled despite a valid token.

**P2-9 · Calendar/events semantic data-loss bugs** (v1, untested on device — confirm intended behavior first)
- Series re-anchor: `EventsRepository.kt:235-242` + `EventEditorDialog.kt:64-68/261-266` — "All
  events in the series" re-anchors the series to the tapped occurrence; a title-only edit destroys
  the original anchor date (e.g. birth year) and all past occurrences. Preserve the stored anchor
  unless the user actually changed the date.
- Reminders dropped: `EventsRepository.kt:185` (occurrence override) and `:222-225`
  (this-and-following) discard `reminders`.
- `EventEditorDialog.kt:194-196,255`: UNTIL-before-start accepted → event occurs never, is
  excluded from every query, and becomes **uneditable/undeletable** from the UI. Validate.
- Locale-digit page keys: `CalendarActivity.kt:562` `"cal-month-%04d-%02d".format(...)` uses the
  default locale — Eastern-Arabic-digit locales write month keys that ISO-keyed lookups (and
  `DayHistoryRepository.kt:190`'s LIKE) never match; switching device language orphans existing
  month pages. Use `Locale.ROOT` + a one-time key-normalization sweep.

**P2-10 · Compactor fragility + strict JSON divergence**
`NotebookCompactor.kt:87` (unguarded `StrokeData.fromJson` in pass 1), `:107/229-241`
(`stripDeadStrokes` uses strict default `Json` — no `ignoreUnknownKeys`, diverging from
`HeadingObject.kt:24`/`TextObject.kt:15`/`LinkObject.kt:29`), `:128`. One malformed legacy row
silently kills compaction for that notebook forever; strict Json also means a future added field
makes older builds drop objects. **Fix:** per-row `runCatching` + the shared lenient `Json`.

**P2-11 · Ghost/empty `.soil` creation on vanished notebook id**
`NotebookActivity.kt:2247-2320` never verifies the index row exists (`getEncryptionInfo` returns
`NONE` for a missing row) → Room creates a brand-new empty DB at `Garden/<id>.soil` on a stale
recents/history tap. Orphan file, confusing "empty notebook" UX. **Fix:** existence check + toast.

**P2-12 · Sticky-note editor state lost on process death**
`pendingStickyNote` is a plain field in all hosts (e.g. `ScratchpadActivity.kt:154-166`); process
death behind the editor discards the session silently (known deferred item in
`docs/sticky-notes.md` — bundle it into this pass or leave deliberately).

### P3 — hardening / polish (grouped)

- **Dormant default-handler opens:** `MainActivity.kt:1682`, `data/NotebookFactory.kt:31`
  (fresh-UUID create paths) — route through `openRawPlaintext`/`NonDeletingErrorHandler` so no
  default-handler open survives anywhere. Also pass an explicit non-deleting
  `net.zetetic.database.DatabaseErrorHandler` on all zetetic opens to make the no-delete invariant
  structural rather than an artifact of `hasCodec()`.
- **`NotebookFactory.kt:28-29`** inserts the index row before building the `.soil`; disk-full
  strands a phantom row. Reverse the order or clean up on failure.
- **zlib inflate loops** (`ObjectColumns.kt:55`, `core/StrokeCodec.kt:95-99`) can spin forever on a
  corrupt FDICT stream → ANR. Add a `needsDictionary()`/zero-progress exit.
- **`SoilFile.kt:11`** `getExternalFilesDir(null)!!` NPEs when storage is unavailable — fail with a
  user-visible error instead. **`CalendarRepository.kt:95`** `getLayerForPage(pageKey)!!`.
  **`TextEditDialog.kt:210`** `toInt()` on a regex group overflows on absurd ordered-list numbers.
- **`GlobalKey.ensure`** (`GlobalKey.kt:26-31`) unsynchronized check-then-act — add a lock.
- **`CancellationException` swallowed** in `GlobalConversion.kt:137-141` and
  `RestoreEngine.kt:63` — rethrow it.
- **`NotesproutIndex.seal()`** (`:230-239`) bypasses `prepareMutex` — take the mutex.
- **Import temp hygiene:** `incoming.soil` leaks on several `return@launch` paths
  (`MainActivity.kt:2901, 2908, 2969, 2975`); `probe()` classifies garbage files as `Encrypted`
  (futile passphrase prompt) — add a size/format sanity gate.
- **Undo bookkeeping for <2-point strokes** (`NotebookActivity.kt:4490` vs `:4503-4504`);
  **`ShapeCreated` undo→redo→undo double-insert** (`:9884-9895`, latent — dwell disabled);
  **cover snapshots omit shapes** (both engines); **stale page_text watermark order**
  (`PageTextRepository.kt:121-123` — swap the two reads); **RtrScheduler** swallowed-cancellation
  eviction race + uncancellable backfill; **recognized text logged** in debug
  (`MlKitHandwritingRecognizer.kt:113`); **DriveAuthActivity** WebView never destroyed;
  **`DayHistoryRepository.kt:212-222`** folder-path walk needs a visited-set;
  **main-thread `contentResolver.query`** during import (`MainActivity.kt:2668`);
  **double-tap guard** on notebook cards (`MainActivity.kt:1299`).

### Refuted during verification

- **"zetetic's DefaultDatabaseErrorHandler deletes encrypted DBs mid-session"** — refuted.
  Bytecode: `onCorruption` logs, then `if (hasCodec()) return;` before any delete;
  `hasCodec()` → native `SQLITE_HAS_CODEC`, and the bundled `libsqlcipher.so` is a codec build →
  always true. Encrypted connections cannot self-delete. (The framework-SQLite opens in P0-3/P0-4
  and the P3 dormant sites are the only real members of this family.)
- **"`insertStickyNoteSubtree` already remaps"** — misread; line 449 is inside
  `replaceStickyNoteSubtree`. All four insert helpers lack the remap (P1-1).

---

## Fix plan

Suggested order. Phases 1–2 are the "stop real-world data loss and crashes" core; each phase ends
buildable + installable for on-device validation (G102 per usual flow).

### Phase 1 — close the destruction paths (P0) · est. 1–2 sessions
1. **SoilMigrator** (P0-1/2/3): `.bak` rename-ordering in all three round-trips; keep tmp on any
   failure; `NonDeletingErrorHandler` on the checkpoint open; probe-before-encrypt in
   `MainActivity.encryptNotebook`; universal orphan-tmp recovery (bootstrap + pre-open); fsync.
   *Test:* fault-inject rename failure + kill between delete/rename (encrypt, decrypt, rekey; a
   notebook and the index); confirm recovery on relaunch.
2. **NotesproutIndex plaintext-upgrade factory** (P0-4): one-line wrap.
3. **RestoreEngine/RestoreSource/SafBackupWriter** (P0-5, P1-7): `.part`+rename everywhere;
   fail-on-any-fetch-failure; staged-index probe; aside-swap commit; free-space check; keep staging
   on failure. *Test:* kill Wi-Fi mid-Drive-restore; fill disk; kill process mid-commit.
4. **Import Replace ordering + open-notebook guard** (P0-6) and **manifest id validation + ATTACH
   escaping** (P0-7). *Test:* cancel at keying chooser after Replace; import a crafted `.soil`
   with a traversal id (must be rejected).

### Phase 2 — deterministic crashes & crash-loops (P1) · est. 1–2 sessions
5. **Insert-helper remap** (P1-1) — 4 one-line changes + regression test: paste link/sticky twice,
   send-to-notebook twice, link-create/unlink on fallback composites.
6. **Clipboard per-item decode guard** (P1-2) + **appScope CoroutineExceptionHandler + seal-step
   guards** (P1-4) + **Bootstrap catch + EncryptedSharedPreferences hardening** (P1-5).
7. **Heading/text blob decode guard** (P1-3), **index-mutator meta guards** (P2-3),
   **BackupSettings catch** (P1-6), **verify exists() guards** (P1-8), **PageTextViewer catches**
   (P1-9).

### Phase 3 — silent data loss (P2 core) · est. 1 session
8. **Shape-transform `updateColumns` in 3 hosts + delete dead JSON block** (P2-1) — plus finally
   device-validate the earlier c98af2e move fix (memory notes it never was).
9. ~~Stroke-attribute preservation (P2-2)~~ — **backlogged per decision 4.**
10. **Seal/KeySession/undo/stroke-list race fixes** (P2-7).
11. **Backup bookkeeping** (P2-8, incl. sidecar-copy per decision 9) and **import consistency**
    (P2-4/5, per decision 1).
12. **Sticky-editor process-death durability** (P2-12, pulled in per decision 7).

### Phase 4 — calendar/events semantics + hardening (P2 rest, P3) · est. 1–2 sessions
12. Events fixes (P2-9) after confirming intended behavior (see Questions).
13. Compactor leniency (P2-10), ghost-file guard (P2-11), P3 grab-bag (dormant handlers, inflate
    guard, temp hygiene, `!!` guards, locks, CancellationException rethrows).

**Validation pass at the end:** full on-device sweep on G102 — encrypt/decrypt/rekey a notebook +
rotation with induced failures, backup→restore round-trip (SAF and Drive), import
replace/keep-both/cancel matrix, paste/transfer matrix, calendar shape transforms — plus a
`pm kill` process-death pass over seal, restore-commit, and migrator windows.

---

## Decisions (Greg, 2026-07-21)

1. **Import threat model:** validate ids unconditionally (P0-7). Imported folder ancestry may only
   **create** missing folders — an id that already exists (any type, live or soft-deleted) is left
   completely untouched; on a type mismatch the notebook lands one level up. No resurrect, no
   rename, no move (supersedes the P2-5 "add guards to resurrect" option).
2. **Replace semantics:** after a successful Replace, the old notebook goes to the **normal trash**
   — soft-delete the index row, keep its `.soil` until the regular trash sweep. No hard delete
   anywhere in the Replace flow.
3. **Restore free-space:** pre-check and **hard-fail** with a clear "free up ~X MB" message when
   the ~2× transient space isn't available. No fallback mode.
4. **Stroke-attribute stripping (P2-2): backlogged** (entry added to `BACKLOG.md`) — revisit when
   Paintsprout interop approaches. Removed from Phase 3.
5. **Events (P2-9): all three confirmed** — preserve the series anchor unless the date was
   explicitly changed; occurrence overrides and splits inherit reminders; editor blocks an end
   date before the event's start.
6. **Calendar locale keys:** `Locale.ROOT` fix only; no normalization sweep (no users on affected
   locales).
7. **Sticky editor process-death durability (P2-12): pulled into Phase 3** — persist
   `pendingStickyNote` in instance state and rebuild the editor transfer on restore.
8. **Branch:** all fixes land on **`pruning-stability`** (created off `sprout` 2026-07-21; sprout
   stays untouched until merge). Note: `sprout-global-encryption` is already fully merged into
   sprout, so there is no split-landing question.
9. **Backup WAL gap:** **copy `-wal`/`-shm` sidecars** into the backup for notebooks that couldn't
   be checkpointed, and restore them alongside — small documented addition to the backup layout
   (update `docs/backup.md` + `docs/soil-file-format.md` accordingly).

---

## Appendix — defenses verified sound (checked, no action)

- Every Room open of a `.soil` routes through `SoilDatabase.builder` with
  `NonDestructiveOpenHelperFactory` (both keyed factories wrapped); every raw open routes through
  `SoilCrypto.openRaw` with `NonDeletingErrorHandler` + the probe→`SoilLockedException` guard that
  refuses plaintext opens of encrypted files. Complete open-site table audited — the only unsafe
  opens are the ones listed in P0-3/P0-4/P3.
- Wrong-key/no-key SQLCipher opens throw (keying verified inside `SQLiteConnection.open`); zetetic
  corruption handler never deletes when the codec is present (bytecode + native lib verified).
- `copyUserVersion` present on all three `sqlcipher_export` round-trips + `repairMissingUserVersion`
  backstop; `failOpen` gates recovery to real key failures (`isKeyFailure`).
- No `fallbackToDestructiveMigration` anywhere; all index/notebook migrations are additive and
  match their entities (v3→v4 `notebook_activity`, v4→v5 `events` checked).
- `PageCopier` (cross-notebook copy/move): BFS fresh-UUID remap of full subtrees including
  grandchildren, single transactions, checkpoint-then-close, deletes only `-journal`. Exemplary.
- Historical eraseAll→loadStrokes ink-loss race properly fixed (`displayPage` merges unpersisted
  ink; `clearForPageLoad` on all nav paths); stroke-blob reader (`LiveStroke.fromRow`)
  catches-and-skips corrupt rows; zero/one-point strokes guarded throughout render/hit-test.
- Launch-restore chain has existence checks and an explicit crash-loop breaker in `failOpen`;
  recents/history filter dead notebooks at list build.
- Export is a cold copy after WAL TRUNCATE checkpoint; Drive uploads are atomic server-side
  (resumable session); backup runs are mutually excluded and index-last ordering holds.
- RRULE engine: interval coerced ≥1, Feb-29 handled, iteration guards, 366-day look-ahead bound,
  malformed stored recurrence degrades via `runCatching`.
- TrOCR model install: manifest schema + per-file SHA-256 + smoke decode + atomic rename; runtime
  failures degrade to ML Kit / fallback text. ML Kit not-ready paths degrade gracefully.
- Sticky editor `updateData` persist is safe (sticky reader prefers non-empty `data` — opposite
  column priority to shapes); scratchpad/calendar lasso **moves** correctly use `updateColumns`.
- Saved-instance bundles are token-only (no TransactionTooLarge); SAF/result launchers null-check;
  no `GlobalScope`, no empty catch blocks around DB writes, UUIDs for all identity.
