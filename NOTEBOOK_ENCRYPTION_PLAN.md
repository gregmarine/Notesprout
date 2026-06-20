# Notebook Encryption Plan — SQLCipher

> Status board for a multi-session feature. Each session is a self-contained, ordered chunk of
> work sized for **Sonnet at medium effort**. Do sessions **one at a time, in order**. At the end
> of each session: clean build + install **debug** on **G10** (`34E517F9`) for testing. The user
> reports issues → fix → clean rebuild → reinstall. When the user says **all tests pass**: set that
> session's status to ✅ DONE, commit (🌱 prefix), **do not push**.

## Status Legend

`⬜ NOT STARTED` · `🔨 IN PROGRESS` · `🧪 AWAITING TEST` (built+installed, awaiting user verdict) · `✅ DONE`

## Testing Protocol

Each session's **Build / install** block is split into two tracks:

- **Claude verifies** — data/file checks Claude runs via adb/shell (no device interaction needed). Claude runs these automatically after build+install.
- **User tests** — on-device manual steps that require tapping the UI, observing visual output, or testing prompt flows.

When the user reports all manual tests pass, Claude marks the session ✅ DONE and commits.

## Session Status Board

| # | Session | Status |
|---|---|---|
| S1 | Crypto foundation (deps, native lib, PassphraseStore, SoilCrypto) | ✅ DONE |
| S2 | Index metadata + key-resolution service + passphrase dialog | ✅ DONE |
| S3 | Open path — opening an encrypted notebook | ✅ DONE |
| S4 | Create an encrypted notebook (create dialog + global bootstrap) | ✅ DONE |
| S5 | Convert: encrypt ⇄ decrypt from MainActivity context menu | ⬜ NOT STARTED |
| S6 | Toolbar lock / lock-off buttons (close → encrypt → reopen) | ⬜ NOT STARTED |
| S7 | Lock indicator in notebook lists + cover/snapshot read guards | ⬜ NOT STARTED |
| S8 | Operational read/write sites (export, page copy, link/page pickers) | ⬜ NOT STARTED |
| S9 | Wrap-up (docs, edge cases, P2P + G10 test, Phase 2) | ⬜ NOT STARTED |

---

## Architecture & Key Decisions (read before S1)

### What SQLCipher encrypts
SQLCipher transparently encrypts the **entire `.soil` SQLite file** (pages, WAL, everything). An
encrypted `.soil` is opened by supplying a key via `PRAGMA key`. Wrong key ⇒ the first query throws.
A file browser sees opaque ciphertext instead of a readable SQLite header.

### The key **is** the passphrase (portability requirement)
The user's requirement: an encrypted notebook must open on **another device/platform** with the same
passphrase. Therefore the SQLCipher key is **derived solely from the user-supplied passphrase**
(SQLCipher's built-in PBKDF2 over the raw passphrase string via `PRAGMA key = 'passphrase'`). We do
**not** wrap the key in a device-bound Keystore key — that would break portability. The Android
Keystore is used **only** to encrypt the *local cache* of the global passphrase (see below), never as
the notebook key itself.

> **Decision:** pass the passphrase to SQLCipher as a **string key** (`PRAGMA key = '...'`), letting
> SQLCipher do KDF. Do **not** use a raw-hex key. This is what makes a notebook portable: the same
> passphrase string reproduces the same key on any SQLCipher build with matching default KDF params.
> Pin SQLCipher's default cipher parameters (do not customize `kdf_iter`/page size) so other
> platforms using stock SQLCipher interoperate.

### Two passphrase scopes
- **Global passphrase** — one per device-user. **Cached** in Keystore-backed `EncryptedSharedPreferences`
  so the user is **not** prompted on subsequent opens. If the global passphrase isn't cached yet
  (e.g. first use, or notebook authored on another device), prompt once and store it.
- **Notebook-specific passphrase** — **never cached**. The user is prompted **every** open. This is the
  "extra level of security" the user wants.

### What the global index records (never the passphrase)
The global index (`notesprout.db`) `NotebookObject` (the `data` JSON of the `type="notebook"` index
row) gains two fields: `encrypted: Boolean` and `keyScope: KeyScope` (`GLOBAL` | `NOTEBOOK`). This lets
every list/picker know a notebook is encrypted **without opening the file**, and lets the open path
decide whether to consult the cache or always prompt. **The passphrase is never written to the index.**

### Plaintext-leak hygiene (critical)
Encrypting the `.soil` is not enough — several plaintext side-channels leak page content and must be
handled whenever a notebook is encrypted (and never created while encrypted):
- **Index `snapshot`** — `NotebookObject.snapshot` caches a base64 PNG of the cover page in
  `notesprout.db` (plaintext). For encrypted notebooks this must be **cleared and never written**
  (lists show the lock icon instead).
- **Undo/redo sidecar** — `undoRedoPersistenceFile(path)` writes the undo stack to a plaintext
  `*.soil.undoredo` sidecar on `onStop`. For encrypted notebooks, **skip writing it** (Phase 2 may
  encrypt it). Existing sidecars for a notebook being encrypted must be deleted.
- **WAL/SHM/journal sidecars** — SQLCipher encrypts the WAL too, so these are fine, but the
  encrypt-in-place migration must still leave the folder showing only the `.soil` (existing cleanup
  rules apply).
- **Original plaintext file** — after converting to encrypted, the original plaintext `.soil` and all
  its sidecars must be deleted so no trace remains (this is why convert-from-open closes first).

### "Close → encrypt → reopen" — yes, necessary
The user asked whether closing is necessary when encrypting an already-open notebook. **Yes, do it.**
Rationale: (1) the live Room connection holds an open handle to the plaintext file and in-memory WAL;
(2) `sqlcipher_export()` should run against a sealed, checkpointed DB; (3) it guarantees no plaintext
residue. Flow: capture nothing extra → `sealNotebook()` (existing close path) → run the
plaintext→encrypted migration → delete plaintext original + sidecars → relaunch `NotebookActivity`
with the resolved key available. Same reasoning (reversed) for decrypt-from-open if ever offered from
the toolbar (the toolbar offers encrypt via `lock`; decrypt-from-open via `lock-off` follows the same
close→convert→reopen shape).

### Library / dependency choices
- **SQLCipher for Android (Room-compatible):** `net.zetetic:sqlcipher-android:4.6.1` (the modern
  `net.zetetic.database.sqlcipher` package that integrates with `androidx.sqlite`). Provides
  `SupportOpenHelperFactory(passphraseBytes)` for Room and
  `net.zetetic.database.sqlcipher.SQLiteDatabase` for raw opens. Requires
  `System.loadLibrary("sqlcipher")` once at process start.
  - Also add `implementation("androidx.sqlite:sqlite:2.4.0")` if not transitively present (it is via
    Room, but pin it explicitly to be safe).
- **Keystore-backed prefs:** `androidx.security:security-crypto:1.1.0-alpha06` for
  `EncryptedSharedPreferences` + `MasterKey`. (This is the only Keystore usage; it protects the cached
  global passphrase at rest, not the notebook key.)
- These are **approved new dependencies** for this feature (CLAUDE.md requires discussion — this plan
  is that discussion).
- **No Material Components**, **no `org.json`**, **no `Log.d`** — use `Slog.d` and `kotlinx.serialization`
  per CLAUDE.md.

### Single-factory principle (mirror `soilFile()`)
Today every `.soil` open is ad hoc (Room in 4 files, raw `SQLiteDatabase` in 6+ files). To keep
encryption correct everywhere, introduce **one** crypto helper (`SoilCrypto`) that is the *only* place
that knows how to open a `.soil` with-or-without a key — both the Room `openHelperFactory` and the raw
open. Every existing open site is migrated to route through it. This is the encryption analog of the
"`soilFile()` is the single canonical path" rule.

---

## S1 — Crypto Foundation

**Status:** ✅ DONE

**Goal:** Add dependencies, load the native lib, and create the crypto primitives (`PassphraseStore`,
`SoilCrypto`, `KeyScope`). No behavior change to any notebook yet — purely additive plumbing that
compiles and links the native library.

### Files
- `app/build.gradle.kts` — add dependencies.
- `app/src/main/kotlin/com/notesprout/android/NotesproutApplication.kt` — load native lib in `onCreate`.
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/KeyScope.kt`
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/PassphraseStore.kt`
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/SoilCrypto.kt`

### Steps
1. **build.gradle.kts** — in `dependencies { }` add:
   ```kotlin
   // SQLCipher — full-file encryption for .soil notebooks. Key = user passphrase (portable).
   implementation("net.zetetic:sqlcipher-android:4.6.1")
   implementation("androidx.sqlite:sqlite:2.4.0")
   // Keystore-backed cache for the GLOBAL passphrase only (never the notebook key itself).
   implementation("androidx.security:security-crypto:1.1.0-alpha06")
   ```
   Then build to confirm resolution (`./gradlew :app:assembleDebug`). If `security-crypto:1.1.0-alpha06`
   has manifest-merger conflicts (it pulls `androidx.security` + Tink), resolve by pinning Tink or
   falling back to `1.0.0` — document whichever is used here.
2. **NotesproutApplication.onCreate** — before any DB access, load the native lib once:
   ```kotlin
   System.loadLibrary("sqlcipher")
   ```
   Place it at the very top of `onCreate` (before `NotesproutIndex.open`). Guard with try/catch +
   `Log.e` so a load failure is loud but doesn't crash before logging.
3. **KeyScope.kt** —
   ```kotlin
   package com.notesprout.android.crypto
   enum class KeyScope { GLOBAL, NOTEBOOK }
   ```
4. **PassphraseStore.kt** — `object` wrapping `EncryptedSharedPreferences` (prefs file
   `notesprout_secure`, `MasterKey` with `AES256_GCM`). API:
   - `fun hasGlobalPassphrase(context): Boolean`
   - `fun getGlobalPassphrase(context): String?`
   - `fun setGlobalPassphrase(context, passphrase: String)`
   - `fun clearGlobalPassphrase(context)` (for Phase 2 / settings; wire later)
   All synchronous reads from EncryptedSharedPreferences are fast (no disk DB), safe off-main but also
   acceptable on main for a single string; still prefer calling from `Dispatchers.IO` in callers.
   Never log the passphrase. Add a class KDoc explaining this caches only the GLOBAL passphrase and is
   device-local (does **not** sync, by design — global ≠ same across devices unless the user re-enters
   it; that's expected and the notebook still opens via prompt).
5. **SoilCrypto.kt** — the single canonical crypto-aware open helper. API (implementation grows over
   later sessions; stub what isn't needed yet but define the surface now):
   - `fun keyBytes(passphrase: String): ByteArray` — `passphrase.toByteArray(Charsets.UTF_8)`. Central
     so the encoding is identical everywhere (portability depends on byte-for-byte identical key
     material).
   - `fun roomFactory(passphrase: String): SupportSQLiteOpenHelper.Factory` — returns
     `net.zetetic.database.sqlcipher.SupportOpenHelperFactory(keyBytes(passphrase))`. Used by Room
     builders (S3/S8).
   - `fun openRawReadWrite(file: File, passphrase: String?): SQLiteDatabase` — if `passphrase == null`
     returns the **plaintext** `android.database.sqlite.SQLiteDatabase.openOrCreateDatabase`/`openDatabase`
     path (today's behavior); else returns `net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null)`.
     > NOTE the return types differ (`android.database.sqlite.SQLiteDatabase` vs
     > `net.zetetic.database.sqlcipher.SQLiteDatabase`). To keep callers uniform, **prefer**: callers
     > for encrypted files use the zetetic type directly via small helpers here; do not try to force a
     > common supertype. Provide the raw-open helpers callers actually need (see S7/S8) rather than one
     > leaky abstraction. Keep this minimal in S1 — just establish the factory + a documented opening
     > convention.
   - `fun verifyPassphrase(file: File, passphrase: String): Boolean` — opens the encrypted file with the
     key, runs `SELECT count(*) FROM sqlite_master`, returns true on success, false on the SQLCipher
     "file is not a database" exception. Always closes. Used by prompt dialogs (S2+).

### Build / install
```sh
cd apps/notesprout_android && ./gradlew assembleDebug && adb -s 34E517F9 install -r app/build/outputs/apk/debug/app-debug.apk
```

**Claude verifies:** build exits 0; APK installs without error; `adb -s 34E517F9 logcat -d | grep -i sqlcipher` shows the native lib loaded (no `UnsatisfiedLinkError`).

**User tests:** launch the app on G10; open and edit an existing unencrypted notebook; close it. Confirm no crash and no change in behavior.

**Completion criteria:** app builds, installs, launches on G10, existing (unencrypted) notebooks open
and edit exactly as before (regression check — the native lib loads and nothing is broken). No
user-visible encryption yet.

---

## S2 — Index Metadata + Key-Resolution Service + Passphrase Dialog

**Status:** ✅ DONE

**Goal:** Teach the global index to record encryption state, build the **one** place that resolves a
notebook's key (cache-or-prompt logic), and the **one** reusable e-ink passphrase dialog. Still no
notebook is actually encrypted — this is the decision/UI layer the later sessions call into.

### Files
- `app/src/main/kotlin/com/notesprout/android/data/index/NotebookObject.kt` — add fields.
- `app/src/main/kotlin/com/notesprout/android/data/index/IndexRepository.kt` — add helpers.
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/EncryptionInfo.kt`
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/PassphrasePrompt.kt`
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/KeyResolver.kt`

### Steps
1. **NotebookObject (index)** — extend, keeping defaults so existing rows deserialize unchanged
   (forward-compatible; `Json` already uses `ignoreUnknownKeys`):
   ```kotlin
   @Serializable
   data class NotebookObject(
       val snapshot: String? = null,
       val pageCount: Int = 0,
       val encrypted: Boolean = false,
       val keyScope: KeyScope? = null,   // non-null only when encrypted
   )
   ```
   (Import `com.notesprout.android.crypto.KeyScope`; ensure it's `@Serializable` — add
   `@Serializable` to the enum in `KeyScope.kt`.)
2. **IndexRepository** — add a `// region Encryption metadata` block:
   - `suspend fun getEncryptionInfo(notebookId): EncryptionInfo` — reads the row, parses
     `NotebookObject`, returns `EncryptionInfo(encrypted, keyScope)`.
   - `suspend fun setEncryptionState(notebookId, encrypted: Boolean, keyScope: KeyScope?)` — read-modify-write
     the `NotebookObject` JSON in the index row's `data` column (preserve `snapshot`/`pageCount` unless
     `encrypted` is being set true, in which case **also clear `snapshot`** in the same write — see
     leak hygiene). Mirror the existing `updateNotebookSnapshot` write pattern.
   - Confirm `updateNotebookSnapshot` becomes a no-op (or is never called) for encrypted notebooks —
     enforce in S7 at the call sites, but add a guard here too: if the row is encrypted, refuse to
     write a non-null snapshot.
3. **EncryptionInfo.kt** — `data class EncryptionInfo(val encrypted: Boolean, val keyScope: KeyScope?)`
   with `companion object { val NONE = EncryptionInfo(false, null) }`.
4. **PassphrasePrompt.kt** — a suspend-friendly, e-ink-styled passphrase dialog (no Material). Follow
   the existing AlertDialog styling used across MainActivity: `AlertDialog.Builder(context)`, then
   `dialog.window?.setElevation(0f)` + `setBackgroundDrawableResource(R.drawable.shape_bordered)`.
   - Layout: a vertical `LinearLayout` with a `TextView` prompt + an `AppCompatEditText`
     (`inputType = textPassword`, black-on-white, 1dp inkBlack border via a bordered background).
     Optionally a second confirm field when `confirm = true` (used by create/set-global flows).
   - API: `fun prompt(context, title: String, message: String, confirm: Boolean = false, onResult: (String?) -> Unit)`
     where `null` = user cancelled. (Use a callback, or wrap in `suspendCancellableCoroutine` for a
     `suspend fun promptForPassphrase(...): String?` — prefer the suspend form so callers in
     `lifecycleScope` read top-to-bottom.)
   - **BOOX IME dismissal:** apply the project's standard IME-dismissal pattern (see existing dialogs
     that take text input, e.g. rename dialog in MainActivity ~line 1165) so the soft keyboard hides
     correctly on BOOX. Match whatever `showSoftInput`/`clearFocus` pattern those dialogs use.
   - Never log entered text. Validate non-empty (and confirm-match when `confirm`).
5. **KeyResolver.kt** — the single decision point. `object KeyResolver` with:
   - `suspend fun resolveForOpen(activity, notebookId, info: EncryptionInfo): String?`
     - `info.encrypted == false` → return `null` (open plaintext).
     - `keyScope == GLOBAL`:
       - if `PassphraseStore.hasGlobalPassphrase` → return cached value **after**
         `SoilCrypto.verifyPassphrase` against the file; if verify fails (stale/wrong cached global,
         e.g. file from another device with a different global), fall through to prompt.
       - else prompt (single field), `verifyPassphrase`; on success
         `PassphraseStore.setGlobalPassphrase` then return it; on wrong passphrase, re-prompt (loop
         with a "Wrong passphrase" message) or return `null` on cancel.
     - `keyScope == NOTEBOOK`: **always** prompt (single field), verify, loop on wrong, return `null` on
       cancel. Never cache.
     - Returns `null` to mean "couldn't get a usable key" → caller aborts the open and finishes.
   - `suspend fun resolveForConvertToEncrypted(activity, scope: KeyScope): String?` — obtains the key to
     **write** a new encryption:
     - `GLOBAL`: if cached, return it; else prompt-with-confirm to establish the global, store, return.
     - `NOTEBOOK`: always prompt-with-confirm (set a fresh notebook passphrase), return (do not cache).
   - `suspend fun resolveForDecrypt(activity, notebookId, info): String?` — per the user's "extra are
     you sure": **always prompt** (even if global is cached), verify against the file, return on
     success. Used by S5/S6 decrypt.
6. **Debug trigger for PassphrasePrompt** — add a temporary long-press handler on the `MainActivity`
   toolbar title (or any always-visible dev hook) that calls
   `PassphrasePrompt.promptForPassphrase(this, "Test Dialog", "Enter any passphrase to test styling")
   { result -> Toast.makeText(this, "Got: ${result?.length} chars", Toast.LENGTH_SHORT).show() }`.
   This gives the user a concrete thing to tap to verify IME behavior and dialog styling on G10.
   **Remove this trigger** before marking the session done (it's debug scaffolding only).

### Build / install
Same G10 command.

**Claude verifies:** build exits 0; after creating a new notebook, pull `notesprout.db` and confirm the `data` JSON for the new notebook row contains `"encrypted":false` (Claude runs: `adb -s 34E517F9 shell "run-as com.notesprout.android.dev cat databases/notesprout.db" | sqlite3 /dev/stdin "SELECT data FROM objects WHERE type='notebook' ORDER BY created_at DESC LIMIT 1;"`).

**User tests:** (1) Launch; open and close an existing notebook — no change in behavior (regression). (2) Trigger the debug long-press on the toolbar title; confirm the passphrase dialog appears with correct e-ink styling, the soft keyboard shows/dismisses cleanly on BOOX, and entering text then cancelling returns correctly. (3) Confirm empty input is rejected with a validation message.

**Completion criteria:** builds/installs; existing notebooks unaffected; `PassphrasePrompt` looks and behaves correctly on G10; index round-trips `encrypted=false` on newly created notebooks.

---

## S3 — Open Path: Opening an Encrypted Notebook

**Status:** ✅ DONE

**Goal:** `NotebookActivity` opens an encrypted `.soil` using a key resolved via `KeyResolver`, with
the SQLCipher `SupportFactory` wired into the Room builder. After this session an encrypted notebook
(once one exists) opens and edits normally. (No notebook is encrypted *yet* — test by manually
encrypting a `.soil` via adb/sqlcipher CLI, or defer the live test until S4 creates one; note this in
the test report.)

### Files
- `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` — open path (~1773–1807).

### Steps
1. In `onCreate`, **before** building the Room DB, resolve encryption info + key. Convert the existing
   synchronous open into a small coroutine since key resolution may prompt:
   - `EXTRA_NOTEBOOK_ID` already read. Fetch `val info = indexRepo.getEncryptionInfo(notebookId)`
     (on `Dispatchers.IO`).
   - `val key = KeyResolver.resolveForOpen(this, notebookId, info)`.
   - If `info.encrypted && key == null` → user cancelled / couldn't unlock → `Toast` "Notebook locked"
     and `finish()` (do **not** open anything).
2. Build Room with the factory when keyed:
   ```kotlin
   val builder = Room.databaseBuilder(applicationContext, SoilDatabase::class.java, notebookPath)
       .addCallback(SoilDatabase.openCallback())
   if (key != null) builder.openHelperFactory(SoilCrypto.roomFactory(key))
   soilDatabase = builder.build()
   ```
   Stash `key` in a `private var soilKey: String?` field on the Activity — later raw-open sites in the
   same session (PageCopier, snapshot writes) need it. **Never** persist it; it lives only in memory.
3. **Snapshot write guard:** at the two `indexRepo.updateNotebookSnapshot(...)` sites (~2978, ~3580)
   and any cover write, **skip** when `info.encrypted` (don't cache page content in the plaintext
   index). Centralize via a `private fun cacheSnapshotIfAllowed(...)` that no-ops for encrypted.
4. **Undo/redo sidecar guard:** in `onStop` (~1826) skip `undoRedoPersistenceFile(path).writeText(...)`
   when encrypted (plaintext leak). On open, if an encrypted notebook has a stale sidecar, delete it.
5. **`openCallback()` PRAGMA note:** SQLCipher's helper applies `wal_autocheckpoint` fine through the
   same callback; confirm the `PRAGMA wal_autocheckpoint` query still runs (it does — the support DB is
   the zetetic one but the `SupportSQLiteDatabase.query` API is identical). No change expected; verify
   on device.
6. **`sealNotebook()` path:** the existing close/checkpoint path uses Room's connection — it inherits
   the key from the factory, so `incremental_vacuum` + `wal_checkpoint(TRUNCATE)` + `close()` work
   unchanged. Verify the `-journal`/`-wal` cleanup still leaves only the `.soil`.

### Build / install
Same G10 command.

**Claude verifies:** build exits 0; after opening and closing a plaintext notebook, confirm no stale `.undoredo` or `-wal`/`-shm` sidecars remain in the Garden folder (`adb -s 34E517F9 shell ls /storage/emulated/0/Android/data/com.notesprout.android.dev/files/Garden/`).

**User tests:** open an existing unencrypted notebook, make a stroke, close it — confirm it behaves exactly as before (regression). There is no encrypted notebook yet so the encrypted-open path cannot be tested live at this session. **This is expected — mark the encrypted-open test as ⏳ DEFERRED TO S4** in the test report when reporting results.

**Completion criteria:** unencrypted notebooks open/edit/close cleanly; no stale sidecars in Garden; encrypted-open path verified in S4.

---

## S4 — Create an Encrypted Notebook

**Status:** ⬜ NOT STARTED

**Goal:** The new-notebook flow offers **None / Global / Notebook-specific** encryption. Creating an
encrypted notebook writes the `.soil` with SQLCipher from the start (no plaintext ever touches disk),
sets the index flags, and bootstraps the global passphrase when needed.

### Files
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — new-notebook dialog + `createNotebook`
  (~1165 dialog area, ~1358 `createNotebook`).

### Steps
1. **New-notebook dialog:** add an encryption selector to the existing create dialog (the name-entry
   AlertDialog). Use three e-ink radio-style choices or an `ActionSheetDialog`-consistent control (no
   Material): **None** (default), **Encrypt (global passphrase)**, **Encrypt (notebook passphrase)**.
   Pass the chosen `KeyScope?` (null = none) into `createNotebook`.
2. **`createNotebook(name, libraryTemplateId, scope: KeyScope?)`:**
   - Before creating the file, if `scope != null`:
     `val key = KeyResolver.resolveForConvertToEncrypted(this, scope)` — for GLOBAL this prompts only
     if no global exists yet; for NOTEBOOK always prompts-with-confirm. If `key == null` (cancelled),
     abort creation (also roll back / skip the index entry — create the index row *after* the key is
     secured, or delete it on abort).
   - Replace the raw `SQLiteDatabase.openOrCreateDatabase(soilPath, null)` (~1386) with the crypto-aware
     open: when `scope != null` use
     `net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(soilPath, key, null, null)` via
     `SoilCrypto`. The schema-creation `execSQL` / `rawQuery` PRAGMA block is otherwise identical — the
     zetetic `SQLiteDatabase` exposes the same `execSQL` / `rawQuery`. (Confirm `PRAGMA journal_mode=WAL`
     etc. run the same; SQLCipher supports WAL.)
   - After successful creation, set index state:
     `repository.setEncryptionState(entity.id, encrypted = scope != null, keyScope = scope)`.
   - **Do not** cache any snapshot for an encrypted new notebook.
3. **Launch:** `launchNotebookActivity(entity)` already passes `EXTRA_NOTEBOOK_ID`; the S3 open path
   resolves the key (GLOBAL → cached, so no second prompt; NOTEBOOK → prompts once — acceptable, or pass
   the freshly-entered key forward via a short-lived in-memory handoff to avoid an immediate re-prompt;
   prefer the clean re-resolve unless the double-prompt feels bad on device, then add the handoff).
4. **Global bootstrap edge:** if user picks Global and none exists, the confirm-prompt sets it; if one
   exists, no prompt (per spec). If user picks Notebook-specific, always prompt regardless of global
   (per spec).

### Build / install
Same G10 command.

**Claude verifies:** build exits 0; after the user creates the three notebooks, Claude pulls each `.soil` from the Garden folder and checks the file header:
```sh
adb -s 34E517F9 pull /storage/emulated/0/Android/data/com.notesprout.android.dev/files/Garden/ /tmp/garden/
xxd /tmp/garden/<encrypted-uuid>.soil | head -1   # must NOT start with "53 51 4c 69 74 65" (SQLite format 3)
sqlite3 /tmp/garden/<encrypted-uuid>.soil ".tables" 2>&1  # must fail with "file is not a database"
sqlite3 /tmp/garden/<plaintext-uuid>.soil ".tables" 2>&1  # must succeed
```
Also verifies index JSON has `"encrypted":true` + correct `"keyScope"` for the encrypted rows.

**User tests:** (1) Create an unencrypted notebook — opens normally, no prompt. (2) Create a global-encrypted notebook — prompted once for passphrase + confirm; opens; close and reopen — **no second prompt** (cached). (3) Create a notebook-encrypted notebook — prompted for passphrase + confirm; opens; close and reopen — **always prompts**; enter wrong passphrase — rejected with "Wrong passphrase" message; cancel — returns to grid cleanly. This session also proves the S3 encrypted-open path — confirm it works and clear the S3 ⏳ DEFERRED mark.

**Completion criteria:** three notebooks created; encrypted files are opaque (Claude verified); prompt behavior matches spec per scope; wrong passphrase and cancel both handled cleanly.

---

## S5 — Convert: Encrypt ⇄ Decrypt from MainActivity Context Menu

**Status:** ⬜ NOT STARTED

**Goal:** From the notebook long-press context menu, convert an unencrypted notebook to encrypted, and
an encrypted notebook back to unencrypted, using `sqlcipher_export()`. Decrypt always re-prompts and
shows a warning.

### Files
- **New** `app/src/main/kotlin/com/notesprout/android/crypto/SoilMigrator.kt`
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — `showNotebookContextMenu` (~1498),
  new dialogs.

### Steps
1. **SoilMigrator.kt** — `object` with two `suspend` funcs on `Dispatchers.IO`, both using a temp file
   then atomic replace, and full sidecar cleanup:
   - `encryptInPlace(file: File, scope: KeyScope, passphrase: String)`:
     1. Open plaintext `file` with `android.database.sqlite.SQLiteDatabase.OPEN_READWRITE`.
     2. `val tmp = File(file.absolutePath + ".enc.tmp")` (delete if exists).
     3. `ATTACH DATABASE '<tmp>' AS encrypted KEY '<passphrase>'` (use the zetetic raw exec on the
        plaintext handle? — actually `sqlcipher_export` requires the SQLCipher-aware connection).
        **Correct approach:** open the *plaintext* file with the **zetetic** `SQLiteDatabase` using an
        **empty key** (`""` ⇒ plaintext), then `ATTACH ... KEY 'passphrase'`, then
        `SELECT sqlcipher_export('encrypted')`, then `DETACH`. The zetetic driver supports reading a
        plaintext DB with empty key and exporting into an attached encrypted DB. Document this clearly.
     4. Checkpoint + close both. Verify `tmp` opens with the passphrase (`SoilCrypto.verifyPassphrase`).
     5. **Atomic-ish replace:** delete `file` + its `-wal`/`-shm`/`-journal` siblings, then
        `tmp.renameTo(file)`. On any failure before rename, delete `tmp` and abort (original intact).
     6. Delete the undo/redo sidecar (`*.undoredo`) if present.
   - `decryptInPlace(file: File, passphrase: String)`: symmetric — open encrypted with key, attach a
     plaintext temp with empty key, `sqlcipher_export('plaintext')`, detach, replace original, clean
     sidecars.
   - Both must be exception-safe and never leave a half-written `file`; surface failures to the caller
     for a Toast (CLAUDE.md: never silently swallow raw DB exceptions).
2. **Context menu — "Encrypt Notebook"** (shown only when `getEncryptionInfo(entity).encrypted == false`):
   - Sub-choice **Global / Notebook-specific** (mirror create). Resolve key via
     `resolveForConvertToEncrypted`.
   - Run `SoilMigrator.encryptInPlace`. On success:
     `repository.setEncryptionState(id, true, scope)` (this also clears the index snapshot), then
     `scanAndRender()` so the card flips to the lock icon (S7).
   - Show a progress dialog (reuse the export "Exporting…" AlertDialog pattern, text "Encrypting…").
3. **Context menu — "Decrypt Notebook"** (shown only when `encrypted == true`):
   - **Warning dialog first:** explain the notebook will be stored unencrypted and readable by anyone
     with file access; Cancel / Continue.
   - On Continue: `resolveForDecrypt` (**always** prompts, even if global cached — the "extra are you
     sure"). Verify, then `SoilMigrator.decryptInPlace`. On success
     `repository.setEncryptionState(id, false, null)`, `scanAndRender()`.
4. Add both actions to `showNotebookContextMenu`'s `ActionSheetDialog` conditionally based on
   `getEncryptionInfo` (fetch it in the existing `lifecycleScope.launch` that already fetches `pinned`).
   Use `R.drawable.ic_lock` / `R.drawable.ic_lock_off` for the action icons (added in S6 — if S6 hasn't
   run, temporarily reuse an existing icon and swap in S6, or pull the icons in this session; prefer
   adding the two vector drawables **here** so the menu is correct immediately, and S6 only wires the
   toolbar buttons).

### Build / install
Same G10 command.

**Claude verifies:** build exits 0; after encrypt op — file header is opaque (same `xxd` check as S4); Garden folder has no `.undoredo` sidecar for that notebook; index `encrypted=true` + `keyScope` set. After decrypt op — file header is `SQLite format 3`; index `encrypted=false, keyScope=null`; snapshot is non-null (restored).

**User tests:** (1) Long-press an unencrypted notebook → "Encrypt Notebook" → choose Global → confirm dialog styling; file card flips to lock icon; reopen with no prompt (global cached). (2) Long-press that notebook → "Decrypt Notebook" → warning dialog appears → Continue → forced passphrase prompt (even though global is cached) → card shows snapshot again; reopen works normally. (3) Repeat encrypt with Notebook scope → reopen prompts every time; decrypt path same as above. (4) Confirm wrong passphrase on decrypt is rejected cleanly.

**Completion criteria:** encrypt/decrypt round-trip works for both scopes from the context menu; no stray sidecars; lock icon and snapshot appear/disappear correctly; forced re-prompt on decrypt confirmed.

---

## S6 — Toolbar Lock / Lock-Off Buttons

**Status:** ⬜ NOT STARTED

**Goal:** Add `lock` (encrypt) and `lock-off` (decrypt) buttons to the notebook toolbar. Encrypting
from the open notebook closes → encrypts → reopens so no plaintext remains.

### Files
- **New** `app/src/main/res/drawable/ic_lock.xml`, `ic_lock_off.xml` (Tabler icons — see below).
- `app/src/main/res/layout/activity_notebook.xml` — declare two `AppCompatImageButton`s.
- `app/src/main/kotlin/com/notesprout/android/notebook/ToolbarButtonRegistry.kt` — append two specs.
- `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` — wire listeners + close/encrypt/reopen.

### Steps
1. **Icons (Tabler, e-ink vector style).** Create two 24dp vector drawables matching the existing
   convention (`fillColor=transparent`, `strokeColor=@color/inkBlack`, `strokeWidth=2`, round caps).
   Use the official Tabler `lock` and `lock-off` path data:
   - `ic_lock.xml` — Tabler `lock`: rounded-rect body + shackle + keyhole dot. Paths:
     `M5 13a2 2 0 0 1 2 -2h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2z`,
     `M11 16a1 1 0 1 0 2 0a1 1 0 0 0 -2 0` (keyhole), `M8 11v-4a4 4 0 1 1 8 0v4` (shackle).
   - `ic_lock_off.xml` — Tabler `lock-off`: same body with the diagonal slash. Paths:
     `M19 19a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2h7`,
     `M11 16a1 1 0 0 0 2 0a1 1 0 0 0 -1 -1`, `M8 11v-4a4 4 0 0 1 7.5 -1.97`, `M3 3l18 18` (slash).
     (Verify exact path data against the current Tabler set when implementing; keep stroke style
     identical to `ic_pinned.xml`.)
2. **Layout** — add two buttons mirroring an existing toolbar `AppCompatImageButton` (copy the
   `btnPin` block's attributes exactly: background, padding, `ic_*` src, `contentDescription`,
   `stateListAnimator=@null`, transparent ripple). IDs `btnLock`, `btnLockOff`. They live among the
   `GROUP_NOTEBOOK` buttons.
3. **Registry** — append (append-only key rule!):
   ```kotlin
   ButtonSpec("lock", R.id.btnLock, R.drawable.ic_lock, "Encrypt", GROUP_NOTEBOOK),
   ButtonSpec("lockOff", R.id.btnLockOff, R.drawable.ic_lock_off, "Decrypt", GROUP_NOTEBOOK),
   ```
   New keys append to `DEFAULT_ORDER` automatically; existing users' persisted configs won't show them
   until reset (acceptable — note in Phase 2 a possible "merge new buttons into existing config"
   migration; for now they appear for new configs / after a toolbar reset). **Alternatively** (preferred
   for discoverability): add a one-time config migration in `ToolbarPreferencesManager` that inserts any
   registry keys missing from a persisted `order` (append to end, not hidden). Decide and document.
4. **Visibility logic** — show `btnLock` only when the open notebook is **unencrypted**, `btnLockOff`
   only when **encrypted**. Set in `onCreate` after `getEncryptionInfo`, in the same place other
   notebook-scoped buttons get their state. (They occupy registry slots but are runtime-hidden for the
   inapplicable state, like other context-sensitive buttons.)
5. **`btnLock` listener — encrypt from open:**
   - Scope sub-choice (Global / Notebook) via a small dialog.
   - `val key = KeyResolver.resolveForConvertToEncrypted(this, scope)`; if null, abort.
   - **Close → encrypt → reopen:**
     1. Run the existing user-initiated close: capture snapshot? **No** — for encryption we must not
        write a plaintext snapshot; instead seal without snapshot. Trigger `sealNotebook()` on
        `appScope` but **await** it here (we need the file closed before migrating). Simplest: make a
        `suspend` variant that seals and returns, then proceed.
     2. `SoilMigrator.encryptInPlace(file, scope, key)`.
     3. `indexRepo.setEncryptionState(id, true, scope)`.
     4. Relaunch `NotebookActivity` for the same `notebookId` (fresh intent) and `finish()` the current
        one. The reopened instance resolves the key (GLOBAL cached → no prompt; NOTEBOOK → one prompt,
        or hand off the in-memory key to skip it).
   - Progress UI: "Encrypting…" dialog; disable interaction during the op.
6. **`btnLockOff` listener — decrypt from open:**
   - Warning dialog (same copy as S5). On continue: `resolveForDecrypt` (**always** prompts). Then
     close → `SoilMigrator.decryptInPlace` → `setEncryptionState(id, false, null)` → reopen.

### Build / install
Same G10 command.

**Claude verifies:** build exits 0; after encrypt-from-toolbar — file header opaque; no `.undoredo` in Garden; index `encrypted=true`; snapshot cleared. After decrypt-from-toolbar — file header is `SQLite format 3`; index `encrypted=false`.

**User tests:** (1) Open an unencrypted notebook — confirm `lock` button visible in toolbar, `lock-off` hidden. (2) Tap `lock` → choose scope → "Encrypting…" progress dialog → notebook closes and reopens (global: no prompt on reopen; notebook-scope: one prompt). (3) Confirm the newly opened notebook edits and closes normally. (4) Open the now-encrypted notebook → confirm `lock-off` visible, `lock` hidden. (5) Tap `lock-off` → warning dialog → Continue → forced passphrase prompt → notebook closes and reopens decrypted. (6) Confirm new toolbar buttons appear in the toolbar customization list (or document if a reset is required).

**Completion criteria:** toolbar lock/lock-off buttons visible only for the applicable state; close→encrypt→reopen and close→decrypt→reopen flows both work cleanly; no plaintext residue (Claude verified).

---

## S7 — Lock Indicator in Lists + Cover/Snapshot Read Guards

**Status:** ⬜ NOT STARTED

**Goal:** Every notebook list shows the Tabler `lock` icon as the cover for encrypted notebooks, and no
code path attempts to read an encrypted `.soil` for a cover/snapshot (which would fail or, worse, cache
plaintext). Covers the main grid, recents, and the link/page pickers' notebook lists.

### Files
- **New** `app/src/main/res/drawable/ic_lock_cover.xml` — a larger/centered lock for card covers (can
  reuse `ic_lock` scaled; a dedicated drawable lets the cover read at card size).
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — grid card render (~956–992),
  `loadAndCacheSnapshot` (~1855), `reloadCoverForNotebook` (~1898).
- `app/src/main/kotlin/com/notesprout/android/data/CoverLoader.kt` — guard.
- `app/src/main/kotlin/com/notesprout/android/LinkTargetPickerActivity.kt` — notebook list render.
- `app/src/main/kotlin/com/notesprout/android/PageIndexActivity.kt` — only if it lists notebooks (it
  lists pages of the current notebook; the current notebook is already keyed — so likely no change
  here for the *list*, but see S8 for page reads).

### Steps
1. **Grid render (MainActivity ~956):** when the item is a Notebook, read `encrypted` from the index
   `NotebookObject` (already parsing it at ~972). If `encrypted`, **skip** the snapshot path entirely
   and show the lock cover: set `icon` to `ic_lock_cover` (centered, like the folder icon) and do
   **not** launch the snapshot decode job. This is the "clear indicator that it is encrypted."
2. **`loadAndCacheSnapshot` / `reloadCoverForNotebook`:** early-return for encrypted notebooks (check
   `getEncryptionInfo` or the parsed `NotebookObject.encrypted`) — never open the encrypted file with a
   plaintext `SQLiteDatabase` (it would throw) and never write a snapshot to the index.
3. **CoverLoader.loadNotebookCoverBitmap:** add an `encrypted: Boolean` (or pre-check) guard — return
   `null` immediately for encrypted notebooks so callers fall back to the lock icon. (Keep CoverLoader
   plaintext-only; encrypted covers are intentionally never rendered from page content.)
4. **LinkTargetPickerActivity notebook list:** same treatment — encrypted notebooks show the lock icon
   as their cover/snapshot in the picker grid. (Reads the same index `NotebookObject.encrypted`.)
5. **Recents:** recents render reuses the grid card path in MainActivity; confirm the encrypted branch
   also applies in recents mode (it should, since it's the same `renderCard`). Verify a recently-opened
   encrypted notebook shows the lock in recents.

### Build / install
Same G10 command.

**Claude verifies:** build exits 0; `adb -s 34E517F9 logcat -d | grep -i "notesprout"` shows no exceptions from any list trying to open an encrypted file.

**User tests:** (1) Main grid — encrypted notebooks show the lock icon, unencrypted ones show their snapshot. (2) Open an encrypted notebook then go back — confirm it appears in recents with the lock icon (not a snapshot). (3) Create a link from any notebook → open the link target picker → confirm encrypted notebooks show the lock icon in the picker list. (4) Attempt to view the cover/snapshot of an encrypted notebook via any UI path — confirm no crash, just the lock icon.

**Completion criteria:** lock icon appears in all list contexts; no crashes; index snapshot stays cleared for encrypted notebooks.

---

## S8 — Operational Read/Write Sites Needing the Key

**Status:** ⬜ NOT STARTED

**Goal:** The remaining sites that genuinely need to *read page content* of a (possibly encrypted)
notebook acquire the key: **export**, **page copy/move across notebooks**, and the **link workflow's
page selection** (and `PageIndexActivity` page grid). Each prompts/uses-cache via `KeyResolver` as
appropriate.

### Files
- `app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt` — 3 Room opens (~141, ~195, ~514).
- `app/src/main/kotlin/com/notesprout/android/data/PageCopier.kt` — 6 raw opens.
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — `startExportFromMain` (~1977), the raw
  Room open at ~1998, and cross-notebook copy/move (~1687–1788).
- `app/src/main/kotlin/com/notesprout/android/LinkTargetPickerActivity.kt` — page read (~453–471).
- `app/src/main/kotlin/com/notesprout/android/PageIndexActivity.kt` — page read (~399).

### Steps
1. **Thread the key, don't re-architect.** Each of these entry points already knows the `notebookId`.
   Add a small helper used at each site: `suspend fun keyFor(activity, notebookId): String?` that calls
   `getEncryptionInfo` then `KeyResolver.resolveForOpen`. Returns `null` for plaintext **and** for
   "user cancelled" — distinguish via `EncryptionInfo.encrypted`: if encrypted and resolve returns null,
   **abort** the operation with a Toast ("Notebook is locked").
2. **NotebookExporter:** add a `passphrase: String?` parameter to each public export entry
   (`exportPagesPdf`, `exportPagesPng`, and the third opener). When non-null, set
   `builder.openHelperFactory(SoilCrypto.roomFactory(passphrase))`. Callers (MainActivity
   `startExportFromMain`, NotebookActivity export button) resolve the key first and pass it.
   - **PageCopier raw opens:** add a `passphrase: String?` param to each `*Raw` function; route opens
     through `SoilCrypto.openRawReadWrite(file, passphrase)`. Cross-notebook copy/move (MainActivity
     ~1687–1788) needs the **source** and **destination** keys (they can differ — plaintext↔encrypted,
     or different notebook passphrases). Resolve both before the op; abort if either is locked.
     > Note: copying a page *from* an encrypted notebook *into* a plaintext one writes that content in
     > plaintext — that's the user's explicit action, acceptable, but consider a Phase 2 confirm.
3. **MainActivity copy/move (~1687–1788):** these use `soilFile(...).copyTo(...)` for whole-notebook
   copy — a **byte copy** works regardless of encryption (the encrypted file copies fine and the index
   row carries `encrypted`/`keyScope`, so the copy stays openable with the same passphrase). **Confirm**
   whole-notebook copy/move only does file copy + index row clone (no content re-read) — if so, it needs
   **no key**, just ensure the cloned index row preserves `encrypted`/`keyScope`. Page-level
   copy/move across notebooks (PageCopier) is the keyed path from step 2.
4. **LinkTargetPickerActivity page selection (~453–471):** when the user drills into an encrypted
   notebook to pick a specific page, resolve the key first (`keyFor`), then open with
   `SoilCrypto.openRawReadWrite(file, key)` (zetetic, read-write — note the existing code uses
   `OPEN_READONLY`; for encrypted use the zetetic open with key; keep plaintext on the
   `android.database.sqlite` readonly path). If locked/cancelled, show a Toast and stay on the notebook
   list (don't drill in). Per the spec: use the keystore for global-scope, prompt for notebook-scope or
   uncached global.
   - The page-name labelling (`PageHeadingNames`) reads page rows — works once the keyed connection is
     open.
5. **PageIndexActivity page grid (~399):** `PageIndexActivity` operates on the **currently open**
   notebook. Pass the resolved key forward from `NotebookActivity` (via a transient in-memory handoff,
   since putting a passphrase in an Intent extra is a leak risk — **do not** put it in the Intent).
   Options: (a) a process-scoped `object KeySession { var current: Pair<notebookId,String>? }` set by
   `NotebookActivity` when it resolves the key and read by `PageIndexActivity`/pickers launched from it;
   cleared on notebook close. Prefer this over Intent extras. Document the lifetime (cleared in
   `sealNotebook`). If the handoff is empty (process death), re-resolve via `keyFor`.

> **Design note on the in-memory key session:** introduce `crypto/KeySession.kt` —
> `object KeySession { @Volatile var entry: Entry? = null }` holding `{ notebookId, passphrase }` for
> the single foreground notebook. Set on successful open (S3), read by same-notebook child activities
> (PageIndex, and link-picker when targeting the *current* notebook), cleared on seal. This avoids both
> Intent leaks and redundant prompts. Cross-notebook targets (link picker → other notebook) always go
> through `keyFor` (cache or prompt).

### Build / install
Same G10 command.

**Claude verifies:** build exits 0; after whole-notebook copy of an encrypted notebook, Claude checks the copied `.soil` file header is still opaque and the copied index row carries `encrypted=true` + correct `keyScope`.

**User tests:** (1) Export an encrypted notebook as PDF — confirm prompt (notebook-scope) or no-prompt (global cached); exported PDF exists and is readable. (2) Export as PNG — same. (3) Copy a page from an encrypted notebook into an unencrypted one — confirm key prompt fires; page appears in destination. (4) Copy a whole notebook (duplicate) — confirm the copy is still encrypted and openable with the same passphrase. (5) Create a link in one notebook; in the link target picker, drill into an encrypted notebook — confirm passphrase prompt; pages list appears after unlock; select a page to complete the link. (6) Cancel the picker prompt — confirm it stays on the notebook list with a "Notebook is locked" toast rather than crashing.

**Completion criteria:** all operational read/write sites handle encrypted notebooks correctly; no plaintext leaks (Claude verified on whole-notebook copy); cancel/locked cases are handled gracefully.

---

## S9 — Wrap-Up

**Status:** ⬜ NOT STARTED

**Goal:** Documentation, edge-case sweep, and focused testing on **P2P** (`287d2364`) and **G10**
(`34E517F9`) only (not full Tier-1). Capture deferrals in Phase 2.

### Steps
1. **Docs:**
   - New `docs/encryption.md` — model (key = portable passphrase), scopes (global cached vs notebook
     prompt-always), index fields, leak-hygiene rules, `SoilCrypto`/`KeyResolver`/`SoilMigrator`/
     `KeySession` responsibilities, migration mechanics (`sqlcipher_export`), close→encrypt→reopen.
   - `CLAUDE.md` — add an Encryption row to the docs table; add a one-line guardrail
     ("`.soil` opens route through `SoilCrypto`; passphrases never logged, never in Intents, never in
     the index").
   - `docs/data-architecture.md` — note the new `NotebookObject` index fields and the snapshot-suppression
     rule for encrypted notebooks.
   - Update `docs/toolbar.md` for the new `lock`/`lockOff` registry keys.
2. **Edge-case sweep (verify or file to Phase 2):**
   - Wrong passphrase loop UX (clear "Wrong passphrase" message, cancel exits cleanly).
   - Deleting an encrypted notebook removes file + sidecars (existing delete already globs siblings —
     confirm it covers encrypted's identical sidecar set).
   - Cancelling an open from MainActivity returns to the grid (no half-open Activity).
   - Global passphrase cached on device A, notebook authored on device B with a *different* global →
     verify-fail falls through to prompt (S2 logic) — confirm on device.
   - Backgrounding mid-prompt / process death during convert (temp file cleanup leaves original intact).
   - Toolbar config migration shows the new buttons (or document the reset requirement).
3. **MEMORY:** add a memory file summarizing the encryption architecture + the "passphrase is the
   portable key; Keystore only caches the global" decision, and update `MEMORY.md` index.
4. **Testing:** clean build, install on **P2P** (`287d2364`) and **G10** (`34E517F9`).
   - **Claude verifies:** build exits 0; installs on both devices without error; final Garden folder on each device has no stray sidecars after the test matrix.
   - **User tests (full matrix):** create (all 3 scopes), open (cached / prompt / wrong passphrase / cancel), encrypt-from-list, encrypt-from-toolbar (close→encrypt→reopen), decrypt (both entry points, forced prompt + warning dialog), lists show lock in all views (grid, recents, link picker), export (PDF + PNG), page-copy across notebooks, link-to-specific-page of encrypted notebook. Run matrix on **both** P2P and G10. User reports → fix → clean rebuild → reinstall.
5. On **all tests pass**: set every session ✅ DONE, commit the whole feature history is already in
   place; final wrap-up commit with docs/MEMORY. **Do not push.**

---

## Phase 2 — Deferred / Found-Along-The-Way

> Populate as issues surface during S1–S9. Seed list:

- **Undo/redo sidecar encryption** — currently skipped for encrypted notebooks (no cross-session undo
  persistence). Could encrypt the sidecar with the session key, or store it inside the `.soil`.
- **Change passphrase** — re-key an encrypted notebook (`PRAGMA rekey`) or change scope (global↔notebook)
  without a full decrypt/re-encrypt round trip.
- **Global passphrase rotation** — change the global passphrase and batch re-key every notebook in the
  index whose `keyScope == GLOBAL` in a single operation. Requires iterating all global-scoped notebooks,
  opening each with the old key, running `PRAGMA rekey`, and updating `PassphraseStore`. Must be
  cancellable/resumable so a crash mid-batch doesn't leave notebooks split across two keys.
- **Global passphrase management UI** — view/clear/reset the cached global; "forget on this device".
- **Biometric gate** — optionally require fingerprint/face to release the cached global passphrase.
- **Toolbar config auto-merge** — migrate existing persisted toolbar orders to include new buttons.
- **Cross-notebook plaintext-leak confirm** — warn when copying a page from an encrypted notebook into
  a plaintext one.
- **Export destination security** — exported PDFs/PNGs are plaintext by nature; consider a warning.
- **KDF parameter pinning / interop test** — verify a notebook encrypted here opens with stock
  SQLCipher on desktop (true portability test) and document exact cipher params.
- **Bulk operations** — encrypt/decrypt multiple notebooks; encrypt a whole folder.
- **Recents/search index** — ensure no encrypted page text leaks into any future search index.
- **Encryption choice in TemplateBrowserActivity** — move the encryption scope picker (currently a separate post-picker dialog) into the "New Notebook" flow inside `TemplateBrowserActivity` so name, template, and encryption are chosen in one cohesive screen.
