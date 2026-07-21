# Notebook Encryption

> Referenced from `CLAUDE.md`. Covers the SQLCipher encryption model, passphrase scopes, key
> lifecycle, plaintext-leak hygiene, migration mechanics, and component responsibilities.

---

## Model

Each `.soil` file is a SQLite database. When a notebook is encrypted, SQLCipher encrypts **the
entire file** — all pages, layers, strokes, images, WAL pages — as opaque ciphertext. A file
browser sees no readable SQLite header. The Room connection opens the file via a
`SupportOpenHelperFactory`; raw (non-Room) opens use `net.zetetic.database.sqlcipher.SQLiteDatabase`.

**The key is the passphrase (portability requirement).** SQLCipher's default PBKDF2 KDF derives the
actual cipher key from the passphrase string. The raw bytes are `passphrase.toByteArray(Charsets.UTF_8)`.
This encoding is the *only* correct way to feed a passphrase to SQLCipher here — changing the encoding
breaks cross-device portability. Do **not** customize `kdf_iter` or page size (stock defaults let a
notebook open on any stock SQLCipher build with the same passphrase).

The Android Keystore is used **only** to encrypt the local cache of the *global* passphrase (see
below). It is **never** part of the notebook key itself. Using the Keystore as the key would break
portability.

---

## The Global Index Is Encrypted

**`notesprout.db` is SQLCipher-encrypted at rest**, under the global passphrase, from first launch
(encrypt-everything-by-default). Earlier phases of this doc described the index as plaintext; that is
no longer true.

`NotesproutIndex.ensureReady(context)` owns the open and branches on `SoilCrypto.probe`:

| Probe result | Action |
|---|---|
| `Invalid` (fresh install / empty file) | Mint the global key (`GlobalKey.ensure`) and create the index **encrypted from the start** |
| `Plaintext` (existing user upgrading) | Bring the schema current **while still plaintext**, close, `SoilMigrator.encryptInPlace`, reopen keyed |
| `Encrypted` + resolvable key | Open via the raw-key cache (`KeyMaterial.rawKeyGlobal`, `INDEX_FILE_ID`) |
| `Encrypted` + no usable key | `PrepareOutcome.NEEDS_UNLOCK` — caller prompts, then `unlockAndOpen` |

Consequences:

- **Opening the index is potentially async** (a one-time migration, or an unlock prompt), so it can
  no longer complete synchronously in `Application.onCreate`. `BootstrapActivity` gates on it and
  index consumers suspend via `awaitReady`; `MainActivity` self-guards for deep-link entries.
- The index's raw key is cached under `KeyMaterial.INDEX_FILE_ID` and **invalidated on rotation** —
  a rotated index re-derives on next launch.
- Everything stored in the index — including the `scratchpad`, `calendar`, `events`,
  `notebook_activity`, and clipboard tables — is therefore **encrypted at rest**.

The [search-leak invariant](#search-leak-invariant) still holds and is still enforced: index
encryption is defence in depth, not a licence to start writing page content there.

---

## Passphrase Scopes

| Scope | `KeyScope` value | Prompt behaviour | Cached? |
|---|---|---|---|
| Global | `GLOBAL` | Prompted once; subsequent opens use the cached value (after verify) | Yes — `PassphraseStore` (Keystore-backed `EncryptedSharedPreferences`) |
| Notebook-specific | `NOTEBOOK` | Prompted on **every** open | Never |

The global passphrase is **device-local by design.** A notebook encrypted with a global passphrase on
device A still opens on device B — the user is prompted once to supply the passphrase there; after
that it is cached on B. The same passphrase must be used on both devices.

---

## Index Fields

`NotebookObject` (the `data` JSON of the `type="notebook"` index row in `notesprout.db`) carries two
encryption fields:

```kotlin
@Serializable
data class NotebookObject(
    val snapshot: String? = null,
    val pageCount: Int = 0,
    val encrypted: Boolean = false,
    val keyScope: KeyScope? = null,   // non-null only when encrypted == true
)
```

These fields let every list, picker, and card renderer know a notebook is encrypted **without opening
the file**. The passphrase is **never** written to the index.

---

## Plaintext-Leak Hygiene (critical — never violate)

Encrypting the `.soil` is not enough. Several plaintext side-channels must also be suppressed:

| Side-channel | Rule |
|---|---|
| **Index snapshot** — `NotebookObject.snapshot` (base64 PNG cached in `notesprout.db`) | Clear on encrypt; never write for encrypted notebooks. `IndexRepository.setEncryptionState` clears it atomically. `IndexRepository` also guards `updateNotebookSnapshot` — refuses a non-null snapshot if the row is already encrypted. |
| **Undo/redo sidecar** — `*.soil.undoredo` plaintext file | Skip writing on `onStop` when encrypted. Delete any stale sidecar when opening an encrypted notebook. |
| **WAL/SHM** | SQLCipher encrypts WAL too — safe. Normal sidecar-cleanup rules still apply. |
| **Original plaintext file** | After `encryptInPlace`, the original `.soil` and all siblings are deleted atomically. The zetetic temp file (`*.enc.tmp`) is renamed over the original only after `verifyPassphrase` passes. |

---

## Component Responsibilities

### `SoilCrypto` (`crypto/SoilCrypto.kt`)

The single canonical crypto-aware open helper — the encryption analogue of `soilFile()`. Every
`.soil` open that may be encrypted routes through here. Never construct a `SupportOpenHelperFactory`
or open a zetetic `SQLiteDatabase` outside this object.

| Method | Purpose |
|---|---|
| `keyBytes(passphrase)` | `passphrase.toByteArray(Charsets.UTF_8)` — canonical encoding |
| `roomFactory(passphrase)` | Returns `SupportOpenHelperFactory(keyBytes(passphrase))` for Room builder |
| `openRawPlaintext(file)` | `android.database.sqlite.SQLiteDatabase` open — no zetetic involvement. Opened with `NonDeletingErrorHandler` so a corruption report never deletes the file (see [Never delete on corruption](#never-delete-on-corruption)) |
| `openRawEncrypted(file, passphrase)` | `net.zetetic.database.sqlcipher.SQLiteDatabase` open |
| `openRaw(file, passphrase?)` | Dispatches to `SoilRawDb.Plaintext` or `SoilRawDb.Encrypted`. A `null` key against a file that probes as `Encrypted` throws `SoilLockedException` instead of opening it as plaintext — a null key means "not resolved yet", never "plaintext" |
| `verifyPassphrase(file, passphrase)` | Opens, runs `SELECT count(*) FROM sqlite_master`, always closes; returns `false` on any error |

`SoilRawDb` is a thin sealed wrapper providing a common `rawQuery/beginTransaction/…/close` API over
both raw DB types (which share no common supertype). Only the methods actually needed by `PageCopier`
and page-list loaders are exposed — don't grow the surface.

### `KeyResolver` (`crypto/KeyResolver.kt`)

The single decision point for obtaining a passphrase. No other code decides whether to use the cache,
prompt, or verify.

| Method | Behaviour |
|---|---|
| `resolveForOpen(activity, notebookId, info)` | Plaintext → `null`. GLOBAL: cache hit + verify → return; verify fails or no cache → prompt. NOTEBOOK: always prompt. Wrong passphrase → loop with "Wrong passphrase". Cancel → `null`. |
| `resolveForConvertToEncrypted(activity, scope)` | GLOBAL: cached → return; else prompt-with-confirm + store. NOTEBOOK: always prompt-with-confirm. |
| `resolveForDecrypt(activity, notebookId, info)` | **Always** prompts (even if global is cached — the "extra are you sure"). Verify, return on success. |

`null` return always means "couldn't get a usable key" — the caller must abort and not open anything.

**Global-passphrase overwrite guard.** `resolveGlobalForOpen` adopts an entered passphrase as the
device global (`PassphraseStore.setGlobalPassphrase`) **only when no global was cached** — i.e. the
user is establishing it on a fresh device or after "Forget on this device". If a global is already
cached but this notebook *diverged* from it (its file uses a different passphrase — restored from an
older backup, keyed on another device), the entered passphrase unlocks **that file for this session
only** and the stored global is left untouched. Overwriting it would silently re-point every other
global notebook's key resolution and could strand the whole library from one outlier notebook. The
prompt says so ("This notebook's passphrase differs from your global passphrase"). Re-keying a
divergent notebook back onto the global is `NotebookRecovery`'s explicit repair, never a side effect.

**Fast-path assumption.** When a notebook's raw key is already cached, `resolveGlobalForOpen` returns
the cached global **without** re-verifying (skips the ~300 ms KDF). This is optimistic, not
guaranteed: in-app flows invalidate the cache on rotation/delete/forget, but a file swapped
out-of-band (restore from backup, re-key on another device) can leave a stale key that no longer
fits. That mismatch is caught downstream by [self-heal](#never-delete-on-corruption) — a wrong
assumption costs one self-heal, never a failed open.

### `PassphraseStore` (`crypto/PassphraseStore.kt`)

Keystore-backed `EncryptedSharedPreferences` (`notesprout_secure`, `AES256_GCM`). Stores **only** the
GLOBAL-scope passphrase. Device-local; never synced. The passphrase is **never logged**.

### `KeySession` (`crypto/KeySession.kt`)

Process-scoped in-memory entry for the single foreground notebook. `NotebookActivity` sets it on a
successful encrypted open; clears it in `sealNotebook`. Child activities (`PageIndexActivity`,
`LinkTargetPickerActivity` when targeting the current notebook) call `KeySession.getFor(notebookId)`
to skip re-prompting the user within one open/close cycle. Never written to disk or an Intent.

### `PassphraseCache` (`crypto/PassphraseCache.kt`)

Single-use in-memory cache for the create → immediate-open path. When a NOTEBOOK-encrypted notebook
is just created, the caller stores the passphrase here so the very first open can skip the prompt.
The entry is consumed (removed) on the first hit; every subsequent open prompts as normal. Never
persisted.

### `SoilMigrator` (`crypto/SoilMigrator.kt`)

`encryptInPlace`, `decryptInPlace`, and `rekeyInPlace` — all `suspend` on `Dispatchers.IO`, using a
temp file then atomic rename, with full sidecar cleanup. Uses `sqlcipher_export()` via an
ATTACH/export/DETACH sequence on a zetetic connection. Exception-safe: on any failure before the
rename, the temp is deleted and the original is intact.

**`sqlcipher_export` does not copy `PRAGMA user_version`** — every round-trip must restore it by hand
(`copyUserVersion`), or the output is version 0. This is not cosmetic: a version-0 file whose schema
is *below* the current Room version makes Room treat it as a brand-new/prepackaged database and
reject the old-schema tables instead of migrating them — see [version-loss repair](#version-loss-repair).

`repairMissingUserVersion(file, passphrase)` fixes a file already bricked that way: it opens with the
key, and **only** if the exact brick signature holds (`user_version == 0`, `room_master_table`
present, a `notebook` table present, and the v4 columnar columns absent) it restamps the version
derived from which schema-version tables exist (`notebook_meta` ⇒ 3, `undo_redo_state` ⇒ 2, else 1),
then checkpoints. Room then runs the remaining migrations normally. No-op on anything else.

---

## Data-Loss Defense (never violate)

A `.soil` opened with the wrong key — or with **no** key, i.e. as plaintext — makes SQLite read
ciphertext as a *corrupt database*. Two real incidents came out of that, and this section is the
standing defense against a third. **The invariant: a notebook the user holds a valid passphrase for
must never become permanently unopenable, and no open path may ever delete a `.soil` it failed to
read.** Four independent layers enforce it.

### Never delete on corruption

Android's default SQLite corruption handler (`DefaultDatabaseErrorHandler`) and Room's default both
**delete and recreate** a database reported corrupt. For a `.soil` opened with the wrong key that
destroys the notebook and leaves an empty stub — exactly how a notebook was lost twice (link-picker
thumbnail open; page-index raw open). Every open path must therefore use a non-deleting handler:

- **Room path** — `NonDestructiveOpenHelperFactory` (`data/`) wraps the Room open factory; its
  `onCorruption` logs and throws instead of deleting. Wired into `SoilDatabase.builder`,
  `SoilCrypto.roomFactory`, and `roomFactoryRawKey`.
- **Raw (non-Room) path** — `NonDeletingErrorHandler` (`crypto/SoilCrypto.kt`) is the framework-SQLite
  analogue, passed to every `openRawPlaintext` (and `probe`). The ~18 raw open sites (`PageCopier`,
  `ExportEngine`, `LinkTargetPickerActivity`, `PageList`, `NotebookImporter`) all inherit it.

### Never open an encrypted file as plaintext

`SoilCrypto.openRaw(file, passphrase=null)` probes the file first: if it is actually encrypted it
throws `SoilLockedException` rather than opening it as plaintext. A `null` key means "the key was not
resolved yet" (e.g. a `KeySession` miss on a non-foreground notebook after process death), **not**
"this notebook is plaintext" — conflating the two is what wiped a notebook via `PageIndexActivity`.
Callers already treat "can't read" as an empty/locked state, so this degrades safely. Screens that
open a notebook they didn't launch from (e.g. `PageIndexActivity` reached by launch restore) resolve
the key through the full `KeySession → KeyResolver/PassphraseStore` fallback, not a bare
`KeySession.getFor`.

### Self-heal a stale raw key

The raw key is a cache — PBKDF2 over the passphrase **and the file's salt**. Anything that swaps the
file behind a notebook id (restore from backup, re-key elsewhere, re-import) changes the salt, making
the cached key wrong even though the passphrase is correct. `SelfHealingKeyFactory`
(`crypto/SelfHealingKeyFactory.kt`) wraps the raw-key Room open: on the first failure it drops the
cached key (`KeyMaterial.invalidate`), reopens with the **passphrase** (re-running the KDF against the
file's real salt), and re-warms so the next open is fast again. Wired in `KeyOpener.roomFactoryFor`.
This makes the common "same passphrase, stale key" case — including a notebook restored from a
pre-conversion backup — heal automatically with no UI. Only if the passphrase open *also* fails does
the failure propagate.

### Recover with a user-supplied passphrase

When an encrypted notebook still won't open (the passphrase the app knows is genuinely wrong for the
file), `NotebookActivity.failOpen` calls `NotebookRecovery.offer` **before** falling back to the
library. It tries the cached global first, then prompts (verifying each attempt against the file,
rate-limited via `AttemptLimiter`). On success it drops the stale key and reopens via `recreate()`;
for a GLOBAL-scope notebook whose file uses a *different* passphrase it offers **"Repair and Open"**,
which `SoilMigrator.rekeyInPlace`-es the file onto the global passphrase so future opens are ordinary.
A one-shot flag on the Intent (`EXTRA_RECOVERY_ATTEMPTED`) ensures the offer is made once per launch,
never in a loop.

**`failOpen` routes by failure kind, not blanket.** It first tries the silent version-loss repair
(below) with the key it already has. Only if the failure is a genuine *key/decryption* problem
(`isKeyFailure` — `SQLiteDatabaseCorruptException`, `SoilLockedException`, "file is not a database")
does it invoke `NotebookRecovery`. A **schema/migration** failure (`IllegalStateException` with
"invalid schema"/"migration") is explicitly *not* a key failure and never triggers a passphrase
prompt — recovery can't fix schema drift and the prompt would mislead.

### Version-loss repair

A `.soil` encrypted (or rekeyed) by an older build carries `user_version = 0` because
`sqlcipher_export` dropped it. If that notebook was also below the current Room schema at the time,
the current build rejects it at Room `onCreate` with "Pre-packaged database has an invalid schema".
`failOpen` calls `SoilMigrator.repairMissingUserVersion` with the resolved key: it restamps the
correct version in place (no re-encryption, no backup restore) and retries via `recreate()`, after
which the normal additive migration runs. The fix-forward `copyUserVersion` in the migrator prevents
new occurrences; this repairs ones already on disk. Real incident: G6's v3 notebooks, encrypted while
still v3, bricked exactly this way. (G102 survived because its notebooks were already v4 when
encrypted, so their schema still matched.)

> **Debug harness.** In debug builds a notebook's context menu has **"Break Keying (debug)"**
> (`MainActivity.showBreakKeyingDialog`): it `rekeyInPlace`-es the file to a throwaway passphrase but
> deliberately leaves the index scope and the cached raw key untouched — reproducing the
> stale-key-plus-divergent-passphrase failure on demand to exercise self-heal and recovery. Never
> ship an entry point to this.

---

## Migration Mechanics (`sqlcipher_export`)

Converting a `.soil` in place:

1. Open the **source** file using the zetetic driver (plaintext = empty key `""`, encrypted = its passphrase).
2. `ATTACH DATABASE '<tmp>' AS target KEY '<dest-passphrase>'` (empty key for decrypt).
3. `SELECT sqlcipher_export('target')` — copies all pages into `<tmp>`.
4. `DETACH DATABASE 'target'`.
5. `SoilCrypto.verifyPassphrase(<tmp>, dest-passphrase)`.
6. Delete the original + WAL/SHM/journal siblings, then `tmp.renameTo(original)`.
7. Delete any `*.undoredo` sidecar.

On any failure before step 6, `tmp` is deleted and the original is never touched.

---

## Close → Encrypt → Reopen (why closing is required)

Encrypting an already-open notebook must go through a close → migrate → reopen cycle:
- The live Room connection holds an open handle to the plaintext file and in-memory WAL.
- `sqlcipher_export` must run against a sealed, checkpointed DB.
- This ensures no plaintext residue remains.

`btnLock` in `NotebookActivity` triggers `sealNotebook()`, waits for it, then runs
`SoilMigrator.encryptInPlace`, updates the index, and relaunches the activity. The same shape
(reversed) applies to `btnLockOff`.

---

## `NotebookObject` Snapshot Rule

`IndexRepository.updateNotebookSnapshot` is a no-op when the notebook row has `encrypted = true`.
`setEncryptionState(..., encrypted = true, ...)` atomically clears `snapshot` in the same write.
No snapshot is ever written during an encrypted notebook's open session (guards in both
`NotebookActivity.cacheSnapshotIfAllowed` and `CoverLoader`).

---

## Phase 2 — Re-Key, Rotation, Rate-Limiting, and More

### Re-Keying a Single Notebook (`SoilMigrator.rekeyInPlace`)

`SoilMigrator.rekeyInPlace(file, oldPassphrase, newPassphrase)` re-encrypts a `.soil` via the same
`sqlcipher_export` round-trip as `encryptInPlace` — **not** `PRAGMA rekey`, which was found unreliable
on-device. It opens a new-passphrase-keyed temp as the primary connection, ATTACHes the old-keyed
source, exports source → main, **copies `PRAGMA user_version`** (sqlcipher_export drops it — see
[SoilMigrator](#soilmigrator-cryptosoilmigratorkt)), verifies the temp with the new passphrase, then
deletes the original + sidecars and renames the temp into place.

On any failure the file remains intact and openable with the original passphrase; the caller
receives an exception.

**Change Passphrase (NOTEBOOK scope only):** resolve the old key via `resolveForDecrypt` (always
prompts), prompt-with-confirm for the new, call `rekeyInPlace`. GLOBAL notebooks must change their
passphrase via the global rotation flow in `EncryptionSettingsActivity` — attempting Change
Passphrase on a GLOBAL notebook shows a redirect note.

**Change Encryption Scope:**
- NOTEBOOK → GLOBAL: `rekeyInPlace(file, notebookKey, globalKey)` + `setEncryptionState(id, true, GLOBAL)`.
- GLOBAL → NOTEBOOK: `rekeyInPlace(file, globalKey, newNotebookKey)` + `setEncryptionState(id, true, NOTEBOOK)`.

Both scope-change paths resolve the old key first; a cancellation leaves the file and index
unchanged.

---

### Global Passphrase Rotation (`GlobalRotation`)

`GlobalRotation` (`crypto/GlobalRotation.kt`) batch re-keys every `encrypted == true && keyScope == GLOBAL`
notebook from the old global passphrase to a new one. It is **crash-resumable**: a
`RotationMarker` (stored in `PassphraseStore`'s `EncryptedSharedPreferences`) records `pendingIds`
and the new passphrase before the first file is touched. On any crash or cancel, restarting the app
and entering Encryption Settings resumes from the last completed notebook.

**Per-notebook loop (idempotent):** for each pending id, try `verifyPassphrase(file, newPassphrase)`
first — if it already opens with the new key (a prior interrupted run finished it), skip and drop it
from pending. Otherwise `rekeyInPlace(file, oldGlobal, newGlobal)`, verify, then remove the id from
the persisted marker.

**Cancel:** stops after the current notebook completes (never interrupts mid-rekey). Already-rotated
notebooks are on the new key; the marker keeps the rest pending for resume. During a partial rotation,
the cached global passphrase is still the *old* one — `KeyResolver` falls through to prompt for any
notebook already re-keyed, caches on success. `setGlobalPassphrase(new)` is called **only** after the
marker's `pendingIds` list is empty.

**Encryption Settings UI (`EncryptionSettingsActivity`):** accessible from MainActivity's overflow.
Shows global passphrase status (Set / Not set), count of GLOBAL notebooks, a "Change Global
Passphrase" button (triggers rotation), a "Forget on This Device" button (`clearGlobalPassphrase`
+ optional `KeySession` clear — no notebook is decrypted), and a "Resume rotation" banner when a
marker is present.

---

### Passphrase Attempt Rate-Limiting (`AttemptLimiter`)

`AttemptLimiter` (`crypto/AttemptLimiter.kt`, backed by `EncryptedSharedPreferences`) enforces an
escalating lockout after repeated wrong-passphrase attempts. State is keyed per notebook id plus a
separate `"GLOBAL"` bucket for global-scope prompts. State: `consecutiveFailures: Int`,
`lockoutUntil: Long` (epoch ms).

| Consecutive failures | Lockout |
|---|---|
| 1–2 | None |
| 3 | 30 s |
| 5 | 5 min |
| ≥ 10 | 1 hr (cap) |

`check(key)` returns ms remaining (0 = allowed). `recordFailure(key)` increments and sets
`lockoutUntil`. `recordSuccess(key)` resets both. Cancel does not advance the counter.

`KeyResolver` consults `AttemptLimiter` before each prompt. `PassphrasePrompt` shows a "Too many
attempts. Try again in mm:ss" message, disables the input and OK button, and ticks a countdown
(one text update per second — no animation). State survives process death; a re-launched app still
shows the remaining lockout.

No passphrase material or attempt values are ever logged.

---

### Undo/Redo Persistence for Encrypted Notebooks

Phase 1 skipped writing the `*.soil.undoredo` sidecar for encrypted notebooks to avoid a plaintext
leak. Phase 2 instead persists undo/redo state **inside the encrypted `.soil`**:

**Schema (`.soil` version 2):** `SoilDatabase.MIGRATION_1_2` adds:
```sql
CREATE TABLE IF NOT EXISTS undo_redo_state
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)
```
This single-row table lives inside the SQLCipher-encrypted file — encrypted at rest for free.

**Write (onStop):** for encrypted notebooks, `undoRedoManager.toJson()` is written to this table
via the keyed Room connection on `Dispatchers.IO`. For plaintext notebooks the existing
`*.soil.undoredo` sidecar path is unchanged.

**Read (open):** for encrypted notebooks, the meta row is read and the undo stack is rehydrated.
For plaintext, the sidecar is read as before.

**Sidecar cleanup:** any stale `*.undoredo` sidecar left by a pre-Phase-2 build is deleted when
opening an encrypted notebook.

**Decrypt transition:** the `undo_redo_state` row travels with `sqlcipher_export` into the
now-plaintext file. The plaintext open path reads the sidecar, so the in-DB row is simply ignored.
The row is intentionally left in place (no data hazard — same JSON a sidecar would hold).

---

### Full-Notebook Export — Encrypted Notebooks

Full-notebook export of an encrypted notebook is a **silent pure copy** of the `.soil` file:

- No passphrase is prompted at export time. The export file is still encrypted — SQLCipher encrypts
  the entire file, including the embedded `notebook_meta` table. A file browser sees opaque
  ciphertext.
- No "exported file is unencrypted" warning is shown (contrast: PDF export decrypts content for
  rendering and does warn). The encrypted status travels with the file; importing requires the same
  passphrase.
- For **open-notebook** export (NotebookActivity), the already-held `db` connection supplies the
  key — no second prompt. For **cold** NOTEBOOK-scoped export (MainActivity context menu), the
  file is copied without opening it; embedded `notebook_meta` reflects the last open/close state.
- `notebook_meta` is encrypted at rest inside the SQLCipher file. An encrypted notebook's cover
  snapshot is always `null` in `notebook_meta` (same rule as the index snapshot suppression).

See [`docs/full-notebook-export.md`](full-notebook-export.md) for the full format, copy engine,
and the encrypted-NOTEBOOK meta-freshness trade-off.

---

### Full-Notebook Import — Encrypted Notebooks

Encrypted `.soil` files follow a **probe → unlock → keying chooser → re-key → register** pipeline:

**Probe:** `SoilCrypto.probe(file)` attempts a plain SQLite open. Success → `Plaintext`; failure on a non-empty file → `Encrypted`. (A definitive encrypted-vs-garbage distinction happens in the verify step.)

**Unlock to read meta:** `KeyResolver.resolveForImportRead(activity, file)` prompts the user for the passphrase and verifies it against the file with `SoilCrypto.verifyPassphrase`. Uses an `AttemptLimiter` bucket keyed `"IMPORT"` (independent of any notebook id). Loops on wrong passphrase with the standard lockout escalation; cancel returns null → import aborted, temp deleted.

**Keying chooser (ActionSheetDialog)** — after the user confirms placement but before the file is written into Garden:

| Choice | Action | Resulting scope |
|---|---|---|
| **Keep existing passphrase** | No re-key | `GLOBAL` if entered pass equals this device's cached global; otherwise `NOTEBOOK` |
| **Use this device's global** | `rekeyInPlace(file, enteredPass, globalPass)` (creates/caches global via `resolveForConvertToEncrypted(GLOBAL)` if none) | **GLOBAL** |
| **New notebook passphrase** | `rekeyInPlace(file, enteredPass, newPass)` (prompt-with-confirm via `resolveForConvertToEncrypted(NOTEBOOK)`) | **NOTEBOOK** |

**GLOBAL→NOTEBOOK downgrade rule:** when importing a GLOBAL-encrypted notebook from another device and choosing "Keep existing passphrase", the kept passphrase is compared to `PassphraseStore.getGlobalPassphrase(context)`. If they differ, the scope is set to `NOTEBOOK` — the index records `NOTEBOOK`, and every open will prompt. This is correct: the imported file's passphrase is not this device's global.

**Re-key order:** `rekeyInPlace` operates on the temp file before the copy into Garden. A failure leaves Garden untouched.

**Leak hygiene:** the temp file is the still-encrypted `.soil` (never a plaintext copy); passphrases are never logged, never put in an Intent; the index never receives a `snapshot` for encrypted notebooks.

`KeyResolver.resolveForImportRead` lives in `crypto/KeyResolver.kt`. The `"IMPORT"` `AttemptLimiter` bucket lives in the same file and persists across process restarts.

See [`docs/full-notebook-export.md`](full-notebook-export.md) for the full import pipeline (placement, collision, meta refresh).

---

### Password-Protected PDF Export (PdfBox-Android)

Notebooks can be exported to a password-protected PDF. The existing bitmap → `android.graphics.pdf.PdfDocument`
rendering path produces a plaintext temp PDF; when the user requests a password, PdfBox-Android
post-processes it:

```
PDDocument.load(tempPdf)
  → StandardProtectionPolicy(ownerPwd = password, userPwd = password, AccessPermission())
  → setEncryptionKeyLength(128)   // AES-128
  → doc.protect(policy)
  → doc.save(finalFile)
  → doc.close()
  → delete tempPdf
```

The intermediate plaintext temp PDF is **always deleted** after producing the protected output. No
password is ever logged. The export runs entirely on `Dispatchers.IO` under the existing "Exporting…"
dialog.

**Dependency:** `com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0). `PDFBoxResourceLoader.init(context)`
is called once in `NotesproutApplication.onCreate`.

---

### Search-Leak Invariant

**No page content is ever written to the global index.** `SearchEngine` queries only
`entity.name` from the index (`notesprout.db`). ML Kit recognition output (`recognizedText`) is
stored as part of `HeadingObject` in the `.soil` `notebook` table — it never reaches the index.
Stroke data, images, text objects, and all other page content live exclusively in their respective
`.soil` files.

This invariant ensures that adding search over decrypted content in a future phase requires an
explicit, opt-in design decision — no page content can leak into search results by accident.

The index is now [encrypted at rest](#the-global-index-is-encrypted), but the invariant is
**unchanged**: index encryption is under the *global* key, so writing a NOTEBOOK-scoped notebook's
content there would still widen its blast radius from one passphrase to the device-global one. Any
future feature that proposes writing page content to the index must still go through an explicit
design decision.

---

### Portability / Interop

SQLCipher's default KDF (PBKDF2, default `kdf_iter`, default page size) is used without any
customization. This means any stock SQLCipher build can open a Notesprout `.soil` with the same
passphrase — no special compiler flags or PRAGMA overrides are needed.

**Verified CLI recipe (desktop interop test):**

```sh
# Pull a notebook from the device:
adb -s 34E517F9 pull \
  /sdcard/Android/data/com.notesprout.android.dev/files/Garden/<uuid>.soil \
  /tmp/test.soil

# Open with stock sqlcipher CLI and confirm it decrypts:
sqlcipher /tmp/test.soil
PRAGMA key = 'your-passphrase';
SELECT count(*) FROM sqlite_master;
-- Expected: integer row count (not an error) — confirms AES-256, PBKDF2 defaults interoperate
.quit
```

Expected result: `count(*)` returns the number of rows in `sqlite_master` (e.g. `4`). Any error
means the passphrase or encoding is wrong. The canonical encoding is
`passphrase.toByteArray(Charsets.UTF_8)` — the CLI feeds the passphrase as UTF-8 by default,
so no special flags are required.

**Note:** the desktop interop test is the user's to run on their own machine with a real notebook.
The recipe above records the expected parameters and confirms we never deviate from stock defaults.
