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
