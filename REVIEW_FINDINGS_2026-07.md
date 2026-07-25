# Notesprout — Codebase Review Findings & Remediation Tracker

**Review date:** 2026-07-13
**Reviewer:** Claude Code (Fable 5) — full read-through of security, code health, and performance
**Scope:** `apps/notesprout_android` — 219 Kotlin files (~59k lines), manifest, Gradle, crypto/backup/import subsystems
**Branch at review time:** `sprout`

> **How to use this file.** Each finding is self-contained: severity, a live **Status**, the problem,
> the evidence, and a step-by-step fix plan written for **Claude Code Opus 4.8 (High Effort)** to
> execute with as few new decisions as possible. Work them one at a time. After each, update the
> **Status** field and append a dated line to that finding's **Status log**.

### Status legend
- 🔲 **Not started** — no work done yet
- 🟦 **In progress** — actively being worked
- 🧪 **In review / testing** — code written, awaiting build/device verification
- ✅ **Done** — fixed, verified, committed
- ⏸️ **Blocked / needs decision** — waiting on a product/owner call
- ❌ **Won't fix** — consciously declined (record why)

### Summary table

| ID | Title | Severity | Status |
|----|-------|----------|--------|
| C1 | Path traversal + index poisoning via imported `.soil` | 🔴 Critical | 🔲 Not started |
| M1 | Google OAuth client secret compiled into the APK | 🟠 Moderate | 🔲 Not started |
| M2 | Global index `notesprout.db` is unencrypted | 🟠 Moderate | ⏸️ Needs decision |
| M3 | `runBlocking` on main thread in `closeNotebook` | 🟠 Moderate | 🔲 Not started |
| M4 | `ATTACH DATABASE` paths not quote-escaped in `SoilMigrator` | 🟠 Moderate | 🔲 Not started |
| M5 | Release build ships without R8/shrinking | 🟠 Moderate | 🔲 Not started |
| L1 | Broad `catch (Exception)` swallows on critical paths | 🟡 Low | 🔲 Not started |
| L2 | `!!` assertions on storage-root access | 🟡 Low | 🔲 Not started |
| L3 | Duplicated filename-sanitization regex (drifting) | 🟡 Low | 🔲 Not started |
| L4 | Triplicated `EncryptedSharedPreferences` builder | 🟡 Low | 🔲 Not started |
| L5 | Import boilerplate duplicated 4× | 🟡 Low | 🔲 Not started |
| L6 | Two "God" classes (NotebookActivity / MainActivity) | 🟡 Low | 🔲 Not started |
| L7 | Fixed import temp filename (not concurrency-safe) | 🟡 Low | 🔲 Not started |
| L8 | Drive upload has no retry/resume | 🟡 Low | 🔲 Not started |
| L9 | Two unimplemented TODOs | 🟡 Low | 🔲 Not started |

---

## C1 — Path traversal + index poisoning via imported `.soil` files

| | |
|---|---|
| **Severity** | 🔴 Critical |
| **Status** | 🔲 Not started |
| **Effort** | Small–Medium |
| **Primary files** | `NotebookImporter.kt`, `MainActivity.kt`, `data/SoilFile.kt`, `data/NotebookMeta.kt` |

### Problem
On import, the `notebookId` and every folder `FolderRef.id` are read from the **untrusted incoming
file's** `notebook_meta` and used verbatim as filesystem path components and global-index primary
keys. There is no validation that they are well-formed UUIDs.

- `MainActivity.kt:2857` — `val resolvedId = manifest.meta?.notebookId ?: UUID.randomUUID().toString()`
- `data/SoilFile.kt` — `soilFile()` builds `File(garden, "$notebookId.soil")` with no sanitization
- `NotebookImporter.importPlaintext/importEncrypted/replace*` — `file.copyTo(gardenFile, overwrite = true)`
- `MainActivity.kt:2905` — `repository.ensureFolderWithId(ref.id, ref.name, pid)`

A crafted meta with `notebookId = "../../evil"` makes `copyTo` (which creates parent dirs) write
**outside** `Garden/`, silently overwriting an existing notebook or index-adjacent files. The
collision dialog only matches exact ids, so a traversal id sails past it. Untrusted `ref.id` values
can also inject or resurrect global-index rows.

**Blast radius is confined to the app's own sandbox** (Android prevents escaping to other apps), so
this is a data-integrity / silent-overwrite / index-poisoning bug rather than device compromise —
but it is the clearest exploitable input-validation gap in the app, reachable by any app firing an
`ACTION_VIEW`/`ACTION_SEND` intent or by the user opening a downloaded `.soil`.

### Fix plan (Opus 4.8 / High)
1. **Add a UUID validator.** In `data/NotebookMeta.kt` (same file as `NotebookMeta`), add a top-level
   internal helper object or function:
   ```kotlin
   /** Strict RFC-4122 canonical UUID check — used to gate untrusted ids from imported files. */
   fun isCanonicalUuid(s: String?): Boolean {
       if (s == null) return false
       return try { java.util.UUID.fromString(s).toString().equals(s, ignoreCase = true) }
       catch (_: IllegalArgumentException) { false }
   }
   ```
   (The `.toString().equals(...)` round-trip rejects `UUID.fromString`'s lenient inputs like short
   hex groups. Keep `ignoreCase = true` because `UUID.toString()` lowercases.)
2. **Reject bad ids at the manifest gate**, so nothing invalid ever reaches a path or the index.
   In `NotebookImporter.readManifest` (`NotebookImporter.kt:38`), after `meta` is read in **both**
   the `Encrypted` and `Plaintext` branches (lines ~55 and ~70), add — before constructing
   `ImportManifest`:
   ```kotlin
   val m = meta
   if (m != null) {
       if (!isCanonicalUuid(m.notebookId)) throw ImportException("Not a valid notebook file")
       if (m.folderPath.any { !isCanonicalUuid(it.id) }) throw ImportException("Not a valid notebook file")
   }
   ```
   Import `com.notesprout.android.data.isCanonicalUuid` (or reference via the `NotebookMeta.kt`
   package). Do **not** validate `parentId` of `FolderRef` here — it is allowed to be `null` and its
   non-null values are themselves folder ids covered by the `folderPath` loop.
3. **Defense in depth at the path builder.** In `data/SoilFile.kt`, harden `soilFile()` so a bad id
   can never produce a traversal path even if a future caller forgets validation:
   ```kotlin
   fun soilFile(context: Context, notebookId: String): File {
       require(isCanonicalUuid(notebookId)) { "Refusing to build a .soil path for a non-UUID id" }
       val garden = File(context.getExternalFilesDir(null)!!, "Garden")
       garden.mkdirs()
       return File(garden, "$notebookId.soil")
   }
   ```
   **Before adding this**, confirm every non-import caller of `soilFile()` already passes a
   UUID (they create ids via `UUID.randomUUID()`), by running:
   `grep -rn "soilFile(" app/src/main/kotlin`. If any caller could pass a non-UUID (e.g. a legacy
   id), convert the `require` to a logged early-return of a sentinel and handle null at the call
   site instead — but only if such a caller actually exists. Expectation: none does.
4. **Keep the "Keep both" path safe.** `MainActivity.kt:2876` already assigns a fresh
   `UUID.randomUUID()` — no change needed there; just verify it still routes through the validated
   `soilFile()`.
5. **Do not weaken the collision check.** Leave `MainActivity.kt:2848` as-is; with validation in
   place, only well-formed ids reach it.

### Verification
- Build debug: `cd apps/notesprout_android && ./gradlew assembleDebug`.
- Craft a malicious test `.soil`: copy a valid exported notebook, open it with `sqlite3`, and
  `UPDATE notebook_meta SET json = replace(json, '"notebookId":"<real-uuid>"', '"notebookId":"../../pwned"')`.
  Share it to the app (`adb shell am start -a android.intent.action.VIEW -d file://... ` or via a
  file manager) and confirm the import is rejected with "Not a valid notebook file" and **no** file
  appears outside `Garden/` (`adb shell run-as` is blocked on release; use a debug build +
  `adb shell ls` under the app's external files dir).
- Confirm a normal, valid notebook still imports end-to-end (placement + collision dialogs work).

### Status log
- _(empty)_

---

## M1 — Google OAuth client secret is compiled into the shipped APK

| | |
|---|---|
| **Severity** | 🟠 Moderate |
| **Status** | 🔲 Not started |
| **Effort** | Small (code) + owner action (Cloud console) |
| **Primary files** | `app/build.gradle.kts` (or `build.gradle`), `crypto`/`backup` OAuth code, `DriveAuth.kt` |

### Problem
`build.gradle` bakes `DRIVE_CLIENT_SECRET` into `BuildConfig`. With `isMinifyEnabled = false` the
secret (and all strings) are trivially recoverable from the release APK. PKCE already protects the
auth-code exchange, so practical risk is limited — but a distributed client secret is poor
credential hygiene and invites Google-quota abuse under your project.

### Fix plan (Opus 4.8 / High)
> This has an **owner/Cloud-console component** — Opus should implement the code side and leave a
> clearly marked checklist item for the human.

1. **Preferred: switch to a secret-less client.** In Google Cloud Console, the desktop/loopback
   OAuth client type supports PKCE **without** a client secret for installed apps. If the human
   confirms the client can be reconfigured that way:
   - Remove the `DRIVE_CLIENT_SECRET` `buildConfigField` from `build.gradle`.
   - In `DriveAuth.kt`, delete `client_secret` from both the `exchangeCodeForTokens` body
     (line ~70) and the `getAccessTokenSilent` refresh body (line ~105).
   - Remove the `DRIVE_CLIENT_SECRET.isBlank()` guard in `getAccessTokenSilent` (line ~95) — keep
     only the `DRIVE_CLIENT_ID` check.
   - Update the header comment in `build.gradle` and `docs/backup.md` to state PKCE-only, no secret.
2. **If the client type cannot change** (record the reason in the Status log): keep the secret but
   (a) ensure **M5** ships R8 so it is at least not sitting in plaintext strings, and (b) add a
   comment at the `buildConfigField` site documenting that this secret is treated as **non-confidential**
   in the threat model and that PKCE is the real protection.
3. Either way, add a one-line note to `docs/backup.md` describing the chosen posture so it is not
   re-litigated later.

### Verification
- Build debug + release. Connect Google Drive via the WebView flow and run one backup end-to-end to
  confirm token exchange + refresh still work after the change.
- For option 1: confirm the token endpoint returns tokens with **no** `client_secret` present
  (Google accepts PKCE-only for installed-app clients).

### Status log
- _(empty)_

---

## M2 — Global index `notesprout.db` is unencrypted

| | |
|---|---|
| **Severity** | 🟠 Moderate |
| **Status** | ⏸️ **Needs decision before implementation** |
| **Effort** | Large (schema/data migration + risk) |
| **Primary files** | `data/index/NotesproutIndex.kt`, `data/index/NotesproutDatabase.kt`, crypto layer |

### Problem
`NotesproutIndex.open()` (`NotesproutIndex.kt:20`) builds the global index with plain Room and no
SQLCipher factory. Even when a user encrypts their **notebooks**, the index still stores notebook
**names**, the full **folder hierarchy**, timestamps, and cover snapshots of *plaintext* notebooks
in the clear. The names of encrypted notebooks therefore leak (e.g. a title like "Divorce Notes"
remains readable). Encrypted notebooks correctly store `snapshot = null` (good leak hygiene), so
only titles/structure leak, not page content.

### ⚠️ Decision required first
This is a **design tradeoff, not a mechanical fix.** Encrypting the index raises hard questions:
- **Key source:** the index must open at cold start with no notebook context — which key protects
  it? Options: (a) a device-Keystore-generated random key (protects against off-device disk
  inspection, *not* against a compromised unlocked device); (b) the GLOBAL passphrase (means the app
  cannot show the library grid until the user authenticates at launch — a large UX change).
- **Migration:** existing users have a plaintext `notesprout.db` that must be transcoded in place
  with rollback safety.
- **Scope creep:** covers of plaintext notebooks are the largest data in the index.

**Do not implement until the owner chooses a key source and accepts the UX/migration cost.**
Suggested question to resolve: *"Encrypt the index with a Keystore-random key (transparent, protects
at-rest only) or gate the whole app behind the GLOBAL passphrase at launch (stronger, heavier UX)?"*

### Fix plan (Opus 4.8 / High) — **only after the decision above**
Assuming **Keystore-random-key** is chosen (the lower-UX-impact option):
1. Add a `IndexKeyStore` object (mirror `DriveTokenStore`) that lazily generates a 32-byte random
   key on first use, stores it in `EncryptedSharedPreferences`/Keystore, and returns it.
2. In `NotesproutIndex.open()`, add `.openHelperFactory(SoilCrypto.roomFactory(<index-key>))` — but
   note `roomFactory` currently takes a passphrase `String`; add a `roomFactoryRaw(keyBytes)` variant
   to `SoilCrypto` that passes raw key bytes to `SupportOpenHelperFactory`.
3. **Migration from plaintext:** on first launch after the update, detect a plaintext
   `notesprout.db` (reuse `SoilCrypto.probe`), and transcode it to encrypted using the same
   `sqlcipher_export` ATTACH pattern already proven in `SoilMigrator.encryptInPlace` — adapt that
   routine into an index-specific migrator with the temp-file + verify + atomic-rename safety.
4. Update `docs/data-architecture.md` and `docs/encryption.md` to document the index-at-rest model.

### Verification
- Fresh install: index is created encrypted; app works normally.
- Upgrade install (existing plaintext index with several notebooks/folders): index transcodes once,
  no data loss, subsequent launches open cleanly. Test on G102 (flagship).
- Confirm `sqlite3` can no longer read titles from the file on disk.

### Status log
- 2026-07-13: Filed as **needs decision** — key-source and UX tradeoff must be chosen by owner before any code.

---

## M3 — `runBlocking` on the main thread in `closeNotebook(blocking = true)`

| | |
|---|---|
| **Severity** | 🟠 Moderate |
| **Status** | 🔲 Not started |
| **Effort** | Small–Medium |
| **Primary files** | `NotebookActivity.kt` (`closeNotebook`, ~line 3623–3660; `onDestroy`) |

### Problem
`NotebookActivity.kt:3656` seals the notebook synchronously via `runBlocking` in the `onDestroy`
safety-net path. It is deliberate and documented (guarantee the file is sealed before the process
dies), and the normal user-initiated close uses `appScope.launch` instead. But on a large notebook
during an abnormal teardown, the main-thread `runBlocking` can produce an ANR/jank. It is the last
main-thread blocking call in the app.

### Fix plan (Opus 4.8 / High)
> Goal: keep the "file is sealed before process death" guarantee while removing the unbounded
> main-thread block. Choose the **timeout-bounded** approach below (lowest behavioral change).

1. Read the full `closeNotebook` + `sealNotebook` implementation first
   (`grep -n "fun sealNotebook" NotebookActivity.kt`) to understand what the seal does and how long
   it can take.
2. Replace the raw `runBlocking { sealNotebook(...) }` at line 3656 with a **timeout-bounded**
   blocking seal so a pathological notebook cannot hang the main thread indefinitely:
   ```kotlin
   if (blocking) {
       // onDestroy safety net: seal synchronously but bounded, so a huge notebook
       // cannot ANR the main thread on abnormal teardown. Normal closes use appScope.
       runBlocking {
           withTimeoutOrNull(SEAL_BLOCKING_TIMEOUT_MS) {
               sealNotebook(db, snapshot, pageId, nbPath, nbId, sessionStart)
           }
       }
   } else {
       NotesproutApplication.appScope.launch { sealNotebook(db, snapshot, pageId, nbPath, nbId, sessionStart) }
   }
   ```
   Add `import kotlinx.coroutines.withTimeoutOrNull` and a companion constant
   `private const val SEAL_BLOCKING_TIMEOUT_MS = 2_000L` near the other `NotebookActivity` constants.
3. **Preferred alternative (if the seal is safely restartable):** before the blocking branch, also
   kick the seal onto `appScope` and only block briefly to give it a head start — but do **not**
   double-seal. Only pursue this if step 2's timeout risks truncating a legitimate large seal; the
   simpler timeout is the default choice.
4. Update the KDoc block above `closeNotebook` (lines ~3603–3622) to describe the bounded-seal
   behavior so the doc stays accurate.

### Verification
- Build debug, install on G102.
- Open a large notebook (many pages/strokes), background it, and force teardown
  (`adb shell am kill com.notesprout.android.dev` after backgrounding, or trigger a config change
  that recreates). Confirm the notebook reopens intact (strokes present) and no ANR dialog appears.
- Confirm the normal close button / back-press path is unchanged.

### Status log
- _(empty)_

---

## M4 — `ATTACH DATABASE` paths not quote-escaped in `SoilMigrator`

| | |
|---|---|
| **Severity** | 🟠 Moderate |
| **Status** | 🔲 Not started |
| **Effort** | Small |
| **Primary files** | `crypto/SoilMigrator.kt` (lines 56, 101, 155) |

### Problem
`SoilMigrator` interpolates `file.absolutePath` / `tmp.absolutePath` directly into
`ATTACH DATABASE '<path>' ...` statements without escaping single quotes. The old passphrase **is**
escaped (`.replace("'", "''")`), but the paths are not. Today those paths are app-controlled temp
files, so it is not currently exploitable — but it is exactly the sink that **C1**'s traversal ids
could reach, so the two findings compound. Fix as defense-in-depth.

### Fix plan (Opus 4.8 / High)
1. Add a private helper in `SoilMigrator`:
   ```kotlin
   /** SQL string-literal escape for ATTACH DATABASE paths (double any single quote). */
   private fun sqlQuote(s: String): String = s.replace("'", "''")
   ```
2. Wrap the three interpolated paths:
   - Line 56: `dest.execSQL("ATTACH DATABASE '${sqlQuote(file.absolutePath)}' AS plain KEY ''")`
   - Line 101: `src.execSQL("ATTACH DATABASE '${sqlQuote(tmp.absolutePath)}' AS plaintext KEY ''")`
   - Line 155: keep the already-escaped passphrase; wrap the path too:
     `dest.execSQL("ATTACH DATABASE '${sqlQuote(file.absolutePath)}' AS old_src KEY '${oldPassphrase.replace("'", "''")}'")`
3. Do **not** change the `KEY ''` / passphrase handling — only the path interpolation.

### Verification
- Build debug. Exercise all three transitions on a test notebook: encrypt (plaintext→encrypted),
  decrypt (encrypted→plaintext), and change passphrase (rekey). All must succeed and the notebook
  must open afterward. (These paths never contain quotes today, so behavior is unchanged; the change
  is purely hardening.)

### Status log
- _(empty)_

---

## M5 — Release build ships without R8/shrinking

| | |
|---|---|
| **Severity** | 🟠 Moderate |
| **Status** | 🔲 Not started |
| **Effort** | Medium (keep-rule tuning + full release QA) |
| **Primary files** | `app/build.gradle`, `app/proguard-rules.pro` |

### Problem
`isMinifyEnabled = false` with an empty `proguard-rules.pro`. No shrinking, no obfuscation, larger
APK, all class/string names in clear (compounds **M1**). Enabling R8 also lets the `BuildConfig.DEBUG`
comment in `build.gradle` become true — currently R8 can't strip `Log` calls, so `Slog`'s
`BuildConfig.DEBUG` guard is the only stripping mechanism.

### Fix plan (Opus 4.8 / High)
> R8 with third-party native/reflection libs needs keep rules. Do this carefully and QA the
> **release** build, since this project ships real features through SQLCipher, ONNX Runtime, and the
> Onyx SDK.
1. In `build.gradle`, set the release block:
   ```kotlin
   release {
       isMinifyEnabled = true
       isShrinkResources = true
       proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
   }
   ```
2. Populate `proguard-rules.pro` with keep rules for the reflection/native/serialization surfaces:
   - **kotlinx.serialization** — keep `@Serializable` classes' generated serializers:
     ```
     -keepclassmembers,allowshrinking,allowobfuscation class **$$serializer { *; }
     -keepclasseswithmembers class ** { @kotlinx.serialization.Serializable *; }
     -keep,includedescriptorclasses class com.notesprout.android.**$$serializer { *; }
     ```
   - **Room** — generally R8-safe, but keep entities/DAO impls if issues appear:
     `-keep class com.notesprout.android.data.** { *; }` (start broad, then narrow).
   - **SQLCipher (net.zetetic)**: `-keep class net.zetetic.** { *; }` and
     `-keep class net.sqlcipher.** { *; }`.
   - **ONNX Runtime**: `-keep class ai.onnxruntime.** { *; }`.
   - **Onyx SDK**: `-keep class com.onyx.** { *; }`.
   - **hiddenapibypass**: `-keep class org.lsposed.hiddenapibypass.** { *; }`.
3. Build the **release** APK, sign it (per `CLAUDE.md` release recipe), and install on the flagship
   **G102**. Do a full smoke test of the high-risk surfaces: open/create notebook, draw + save +
   reopen (SQLCipher path), run ML Kit + TrOCR recognition (ONNX/native), Drive backup (serialization
   + network), export/import a `.soil`, calendar, scratch pad, sticky notes.
4. If any surface crashes, read the release logcat, identify the stripped/renamed symbol, and add a
   targeted keep rule (prefer narrow rules over blanket `-keep class pkg.** { *; }` once stable).
5. Compare APK size before/after and note the reduction in the Status log.

### Verification
- Release build installs and passes the smoke test above on G102 with **no** crashes.
- Confirm the OAuth client secret (M1) is no longer a plaintext string in the APK
  (`strings app-release.apk | grep <secret-prefix>` returns nothing) if M1 option 2 was taken.

### Status log
- _(empty)_

---

## L1 — Broad `catch (Exception)` swallows on critical paths

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Small–Medium |
| **Primary files** | `data/NotebookMetaStore.kt`, `core/ImageCodec.kt`, DB-open sites (152 total) |

### Problem
152 `catch (e: Exception)` / `catch (_: Exception)` sites exist. Most legitimately guard optional
work, but several on the **DB-open and meta-read** paths silently `return null`, which masks real
corruption/decode failures as "empty notebook." Examples: `NotebookMetaStore.read` (returns null on
any exception), `NotebookMetaStore.readRaw`, `ImageCodec` decode.

### Fix plan (Opus 4.8 / High)
1. Enumerate the swallow sites:
   `grep -rn "catch (_: Exception)\|catch (e: Exception)" app/src/main/kotlin` and triage into:
   (a) optional/expected — leave as-is; (b) **load-bearing** (DB open, meta read, key resolution,
   import) — these must at least log the cause.
2. For the load-bearing set, add a `Slog.d(TAG) { "<context> failed: ${e.message}" }` inside the
   catch **before** returning null/false. Do **not** change control flow or start surfacing new user
   errors — this is diagnostics only. Use the existing `Slog` (never `Log.d`) per `CLAUDE.md`.
3. Prioritize: `NotebookMetaStore.read`/`readRaw`/`countPages`, `SoilCrypto.probe`/`verifyPassphrase`,
   and the import catches in `MainActivity.startImportFromUri`.
4. Leave the numeric/parse `catch` sites in tight loops untouched (logging there would be noise).

### Verification
- Build debug. Force a meta-read failure (corrupt a test notebook's `notebook_meta` json) and confirm
  a `Slog` line appears in logcat with the cause, and the app still degrades gracefully.

### Status log
- _(empty)_

---

## L2 — `!!` assertions on storage-root access

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Small |
| **Primary files** | `data/SoilFile.kt`, `NotebookImporter.kt` (`gardenFile.parent!!`), others (~35 sites) |

### Problem
~35 `!!` assertions. The risky ones are on storage-root access —
`context.getExternalFilesDir(null)!!` and `gardenFile.parent!!` — which become hard crashes if
external storage is unavailable (ejected/unmounted).

### Fix plan (Opus 4.8 / High)
1. List them: `grep -rn "!!" app/src/main/kotlin` and focus only on `getExternalFilesDir(null)!!`
   and `.parent!!` on `File`s derived from it.
2. For `getExternalFilesDir(null)!!`: this returns null only when external storage isn't mounted.
   Since the entire app requires it, the pragmatic fix is a single early check at app start
   (`NotesproutApplication`) that surfaces a clear "storage unavailable" state rather than crashing
   deep in a path builder. If that is out of scope, leave `soilFile()`'s `!!` (C1 already adds a
   `require` there) but wrap other ad-hoc `getExternalFilesDir(null)!!` call sites in a helper
   `externalFilesDirOrThrow(context)` that throws a descriptive exception.
3. For `gardenFile.parent!!` in `NotebookImporter` — `parent` is non-null whenever `soilFile()`
   produced the file, so these are safe in practice; convert to a local `val parent = gardenFile.parentFile ?: return@withContext` guard only if trivially done, otherwise leave with a comment.
4. Keep changes minimal — this is crash-robustness polish, not a refactor.

### Verification
- Build debug; no behavior change under normal conditions. Optionally test with external storage
  made unavailable to confirm a clean error instead of an NPE crash.

### Status log
- _(empty)_

---

## L3 — Duplicated filename-sanitization regex (already drifting)

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Small |
| **Primary files** | `MainActivity`, `TemplateBrowserActivity`, `PageIndexActivity`, `NotebookExporter`, `NotebookTextExporter`, `NotebookPackager`, `LinkTargetPickerActivity`, `BackupSettingsActivity` |

### Problem
`Regex("[^a-zA-Z0-9_\\-. ]")` is copy-pasted in ~15 places. Worse, `BackupSettingsActivity.kt:270`
already uses a **different** pattern (`[/\\\\:*?\"<>|]+`), so the rules have drifted.

### Fix plan (Opus 4.8 / High)
1. Create `core/FileNames.kt` with:
   ```kotlin
   package com.notesprout.android.core

   object FileNames {
       private val UNSAFE = Regex("[^a-zA-Z0-9_\\-. ]")
       /** Replace unsafe chars with '_' and trim padding. For export filenames. */
       fun sanitize(raw: String): String = raw.replace(UNSAFE, "_").trim('_', ' ')
       /** True if the name contains any unsafe char — for input validation before accepting. */
       fun hasUnsafe(name: String): Boolean = name.contains(UNSAFE)
   }
   ```
2. Replace the `.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_")...` sites with `FileNames.sanitize(...)`
   and the `.contains(Regex(...))` validation sites with `FileNames.hasUnsafe(...)`. Grep first:
   `grep -rn "a-zA-Z0-9_..-. " app/src/main/kotlin`.
3. **Leave `BackupSettingsActivity.kt:270` for last and decide deliberately:** its pattern targets
   Windows/Drive-illegal chars, not the same allow-list. Either (a) keep it separate with a comment
   explaining why, or (b) if its intent matches the general case, switch it too. Default: keep
   separate + comment (do not silently change backup-folder naming behavior).

### Verification
- Build debug. Export a notebook/page with a title containing spaces and punctuation; confirm the
  output filename matches the prior behavior exactly (same characters replaced).

### Status log
- _(empty)_

---

## L4 — Triplicated `EncryptedSharedPreferences` builder

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Small |
| **Primary files** | `crypto/PassphraseStore.kt`, `crypto/AttemptLimiter.kt`, `data/backup/DriveTokenStore.kt` |

### Problem
The identical `prefs(context)` factory (MasterKey + `EncryptedSharedPreferences.create` with
AES256_SIV/GCM) is copy-pasted in three objects. `PassphraseStore` and `AttemptLimiter` also share
the same `"notesprout_secure"` prefs file.

### Fix plan (Opus 4.8 / High)
1. Add `crypto/SecurePrefs.kt`:
   ```kotlin
   package com.notesprout.android.crypto

   import android.content.Context
   import android.content.SharedPreferences
   import androidx.security.crypto.EncryptedSharedPreferences
   import androidx.security.crypto.MasterKey

   /** Single factory for all Keystore-backed EncryptedSharedPreferences in the app. */
   object SecurePrefs {
       fun open(context: Context, fileName: String): SharedPreferences =
           EncryptedSharedPreferences.create(
               context,
               fileName,
               MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
               EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
               EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
           )
   }
   ```
2. Replace the three private `prefs()` methods with `SecurePrefs.open(context, PREFS_FILE)`. Keep
   each object's own `PREFS_FILE` constant (do **not** merge the files — `DriveTokenStore` uses a
   separate `"drive_token_store"` file intentionally, and merging changes on-disk keys).
3. No functional change — same schemes, same file names.

### Verification
- Build debug. Confirm existing stored values still read back: set a global passphrase, trigger a
  failed-attempt lockout, and connect Drive — all three prefs paths must round-trip unchanged.

### Status log
- _(empty)_

---

## L5 — Import boilerplate duplicated 4×

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Small–Medium |
| **Primary files** | `NotebookImporter.kt` |

### Problem
`importPlaintext`, `replacePlaintext`, `importEncrypted`, `replaceEncrypted` each repeat the same
sequence: clear `.soil-wal`/`.soil-shm`/`.soil-journal` sidecars, `file.copyTo(gardenFile,
overwrite = true)`, and a meta refresh. This is also the natural home for **C1**'s validation.

### Fix plan (Opus 4.8 / High)
> Do this **after C1** so the shared helper can carry the validation.
1. Extract a private helper in `NotebookImporter`:
   ```kotlin
   private fun clearSidecarsAndCopyInto(source: File, gardenFile: File) {
       val parent = gardenFile.parentFile!!
       val base = gardenFile.name
       listOf("$base-wal", "$base-shm", "$base-journal").forEach { File(parent, it).delete() }
       source.copyTo(gardenFile, overwrite = true)
   }
   ```
   (Note current code uses `"$resolvedId.soil-wal"`; `gardenFile.name` is `"$id.soil"` so
   `"$base-wal"` yields the same string — verify this equivalence when substituting.)
2. Replace the four inlined blocks with `clearSidecarsAndCopyInto(file, gardenFile)`.
3. Keep the two meta-refresh helpers (`refreshPlaintextMeta` / `refreshEncryptedMeta`) as-is — they
   already deduplicate that half.
4. Pure refactor — no behavioral change.

### Verification
- Build debug. Import + replace, both plaintext and encrypted, all succeed and reopen intact.

### Status log
- _(empty)_

---

## L6 — Two "God" classes (NotebookActivity / MainActivity)

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Large (ongoing) |
| **Primary files** | `NotebookActivity.kt` (11,284 lines / 123 methods), `MainActivity.kt` (3,187 / 81) |

### Problem
Not a bug, but the biggest long-term maintainability drag. The import flow, export flow, and toolbar
logic are all extractable into controllers — the project already does this well elsewhere
(`EventsController`, `ShapeTransformController`).

### Fix plan (Opus 4.8 / High)
> This is a **multi-session refactor** — do not attempt in one pass. Slice conservatively, one
> cohesive flow at a time, verifying on-device between slices.
1. **Slice 1 — Import controller.** Extract `MainActivity`'s import pipeline
   (`handleIncomingIntent`, `startImportFromUri`, and the `showImport*Dialog` chain, ~lines
   2776–3120) into an `ImportController(activity, repository, lifecycleScope)` that exposes a single
   `beginImport(uri)` and calls back into the activity for dialogs. Keep the dialogs' look identical.
2. **Slice 2 — Export controller** in `MainActivity` (the `exportPassword`/export-choice flow).
3. **Slice 3 — NotebookActivity toolbar** logic into a controller (much of it already lives in
   `notebook/Toolbar*` — move the remaining wiring out of the activity).
4. Between every slice: build debug, install on G102, and exercise the moved flow end-to-end before
   the next slice. Commit each slice separately with a clear message.
5. Do **not** change behavior or UI — this is mechanical extraction only.

### Verification
- Each slice: the moved flow works identically on G102; no regression in adjacent flows.

### Status log
- _(empty)_

---

## L7 — Fixed import temp filename (not concurrency-safe)

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Small |
| **Primary files** | `MainActivity.kt` (`startImportFromUri`, ~lines 2800–2810) |

### Problem
Import copies the incoming URI to a fixed path `cacheDir/imported_notebooks/incoming.soil` after a
`deleteRecursively()` on the dir. Two imports fired in quick succession would race (one deletes the
other's temp).

### Fix plan (Opus 4.8 / High)
1. Replace the fixed dir + `deleteRecursively()` with a per-import unique subdir:
   ```kotlin
   val importDir = java.io.File(cacheDir, "imported_notebooks/${java.util.UUID.randomUUID()}")
       .also { it.mkdirs() }
   val tempFile = java.io.File(importDir, "incoming.soil")
   ```
2. Ensure the temp is cleaned in **all** exit paths. The importer already `file.delete()`s on
   success; add cleanup of `importDir` (the whole subdir) on the early-return failure paths in
   `startImportFromUri` (invalid kind, cancelled passphrase, `ImportException`, generic exception).
   Consider a `try/finally` or a small `cleanup()` local that deletes `importDir` recursively when
   the import does not proceed to Garden.
3. Optionally, add a best-effort sweep of stale `imported_notebooks/*` subdirs older than a day at
   app start — only if trivial; not required.

### Verification
- Build debug. Import a notebook normally (success path leaves no temp behind — check
  `adb shell ls .../cache/imported_notebooks`). Cancel an encrypted import at the passphrase prompt
  and confirm its temp subdir is removed.

### Status log
- _(empty)_

---

## L8 — Drive upload has no retry/resume

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Medium |
| **Primary files** | `data/backup/DriveApiClient.kt` (`uploadOrReplace`) |

### Problem
`uploadOrReplace` opens a **resumable** upload session but streams the whole file in one PUT with no
resume-on-failure. A dropped connection mid-backup fails the entire file even though the resumable
protocol was designed to recover.

### Fix plan (Opus 4.8 / High)
1. Keep the current single-shot path as the fast case, but add a bounded retry around the PUT: on an
   `IOException` or 5xx, re-query the session's current offset with a
   `PUT <sessionUri>` + `Content-Range: bytes */<total>` (Drive returns `308` with a `Range` header
   giving bytes received), then resume streaming from that offset. Cap at, e.g., 3 attempts with a
   short backoff.
2. If full resumable-offset handling is more than desired, a simpler acceptable improvement: wrap the
   whole `uploadOrReplace` in a 3-attempt retry that re-initiates the session on transient failure
   (idempotent because `uploadType=resumable` + replace semantics). Choose this simpler option if the
   offset-resume adds significant complexity — record which was chosen.
3. Do not change the success-path behavior or the `findChild`/replace logic.

### Verification
- Build debug. Run a Drive backup of a large notebook and confirm success. Simulate a transient
  failure if feasible (e.g. toggle airplane mode mid-upload on a large file) and confirm the retry
  recovers or fails cleanly with a clear message.

### Status log
- _(empty)_

---

## L9 — Two unimplemented TODOs

| | |
|---|---|
| **Severity** | 🟡 Low |
| **Status** | 🔲 Not started |
| **Effort** | Trivial (triage) |
| **Primary files** | `NotebookActivity.kt:1228`, `NotebookActivity.kt:4889` |

### Problem
Two dangling TODOs: `:1228` "implement toolbar show/hide UX" and `:4889` "apply template to all
pages". Either they are real backlog items or dead comments.

### Fix plan (Opus 4.8 / High)
1. Read the surrounding code for each to determine whether the feature is partially wired or fully
   absent.
2. If they represent intended future work, move them into `BACKLOG.md` (monorepo root) as tracked
   entries with a one-line description, and delete the inline `// TODO` comments.
3. If they are obsolete (the surrounding code already handles the case another way), delete the
   comments.
4. Do **not** implement the features here — this item is triage only.

### Verification
- `grep -rn "TODO\|FIXME" app/src/main/kotlin` returns zero (or only newly-justified entries).

### Status log
- _(empty)_

---

## Appendix — What was checked and found clean
- **No SQL injection** in local DB access — user data goes through parameterized queries /
  `ContentValues`; the only string-built SQL is schema migrations (app-constant column names) and the
  Drive query builder (which escapes correctly via `escapeDriveString`).
- **Crypto model is sound** — passphrase→UTF-8→SQLCipher, Keystore-backed caches, escalating attempt
  lockout (`AttemptLimiter`), single-use in-memory `PassphraseCache`, consistent "never
  log/Intent/index the passphrase" discipline.
- **Bitmap decoding is guarded** — `BitmapDecode` uses `inSampleSize`; `ImageCodec` try/catches
  base64. No obvious decode-OOM from untrusted covers.
- **Manifest exposure is minimal** — only `MainActivity` is exported (necessarily); the debug-only
  `HwrLabActivity` is confined to the `.dev` variant; everything else is `exported=false`.
  FileProvider is scoped to cache export dirs.
- **No cleartext networking** — all Drive endpoints are HTTPS; the `http://localhost` redirect is
  intercepted, never loaded.
- **Discipline markers** — 2 TODOs total, zero `GlobalScope`, no passphrase/token logging, no
  hardcoded credentials, no main-thread Room queries, consistent `Slog`/`kotlinx.serialization` use.
