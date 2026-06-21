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
| `openRawPlaintext(file)` | `android.database.sqlite.SQLiteDatabase` open — no zetetic involvement |
| `openRawEncrypted(file, passphrase)` | `net.zetetic.database.sqlcipher.SQLiteDatabase` open |
| `openRaw(file, passphrase?)` | Dispatches to `SoilRawDb.Plaintext` or `SoilRawDb.Encrypted` |
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

`encryptInPlace` and `decryptInPlace` — both `suspend` on `Dispatchers.IO`, using a temp file then
atomic rename, with full sidecar cleanup. Uses `sqlcipher_export()` via an ATTACH/export/DETACH
sequence on a zetetic connection. Exception-safe: on any failure before the rename, the temp is
deleted and the original is intact.

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

`SoilMigrator.rekeyInPlace(file, oldPassphrase, newPassphrase)` re-encrypts a `.soil` in place
using SQLCipher's `PRAGMA rekey`. It opens the file with the old passphrase, runs
`PRAGMA rekey = '<newPassphrase>'`, checkpoints, closes, then calls
`SoilCrypto.verifyPassphrase(file, newPassphrase)` to confirm success. No temp file is needed —
`PRAGMA rekey` modifies the file in place. WAL/SHM sidecars are cleaned up afterward.

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

If any future feature proposes writing page content to the global index, it must go through Phase 3
design with an encrypted-at-rest index.

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
