# Full Notebook Import — Plan

> The reverse of **Full Notebook Export** (`FULL_NOTEBOOK_EXPORT_PLAN.md`, ✅ done). Take a `.soil`
> file the user provides — plaintext, global-encrypted, or notebook-encrypted — and bring it back
> into Notesprout: recreate its folder placement, register it in the global index, and (for encrypted
> notebooks) let the user decide how the imported copy is keyed. Export made the `.soil`
> **self-describing** via the embedded `notebook_meta` table; this plan **consumes** that metadata.
>
> Same working agreement as the export plan (which went well): each session is a self-contained,
> ordered chunk sized for **Sonnet at medium effort**. Do sessions **one at a time, in order**.

## Working Agreement (read first)

- **Effort:** Sonnet, medium. One cohesive theme per session.
- **Testing is the user's job.** There is **no "Claude verifies" adb track**. At the end of each session
  Claude does a **clean build + install debug on G10 (`34E517F9`)** and hands off. The user runs all
  on-device tests and reports the verdict.
- **Per-session loop:** implement → clean build → install debug on **G10** → user tests → user reports
  issues → fix → clean rebuild → reinstall → on **"all tests pass"**: mark the session ✅ DONE, commit
  with a `🌱` prefix, **do not push**.
- **Wrap-up (S4)** additionally installs on **P2P (`287d2364`)** for a final two-device matrix.
- **Standing constraints (CLAUDE.md) still apply:** Kotlin/Java 17 · `kotlinx.serialization` only
  (`toJson()`/`fromJson()`, never `org.json`) · no Material Components · no new Gradle dependencies ·
  never `runBlocking` on UI · `Slog.d` not `Log.d` · e-ink design system (no color/shadow/ripple, 1dp
  inkBlack borders, `shape_bordered` dialog windows) · passphrases **never** logged, never in Intent
  extras, never in the global index · every `.soil` open routes through `SoilCrypto` when keyed ·
  **every passphrase prompt goes through `KeyResolver`** — no ad-hoc prompts.
- **Reuse existing primitives** — `soilFile()`, `SoilCrypto` (`openRaw`/`openRawEncrypted`/
  `verifyPassphrase`/`SoilRawDb`), `KeyResolver` (`resolveForConvertToEncrypted`), `SoilMigrator`
  (`rekeyInPlace`/`encryptInPlace`), `PassphraseStore` (global), `IndexRepository`
  (`getFolderAncestry`, `setEncryptionState`, `getEncryptionInfo`, `updateNotebookSnapshot`/
  `updateNotebookPageCount`, `renameNotebook`), `NotebookMeta`/`FolderRef`, the **`DestinationPickerState`
  in-place folder picker** (`MainActivity.enterPickerMode`/`confirmPickerDestination`), the `ActionSheetDialog`
  pattern, and the `OpenDocument` file-picker launcher pattern (symmetric with the export
  `CreateDocument` launcher). Grow surfaces minimally; do **not** add a parallel path.

## Status Legend

`⬜ NOT STARTED` · `🔨 IN PROGRESS` · `🧪 AWAITING TEST` (built + installed, awaiting user verdict) · `✅ DONE`

---

## Locked Decisions (confirmed with the user before S1)

1. **Two entry points.**
   - **File-picker button** in MainActivity (overflow): "Import Notebook (.soil)" → `OpenDocument`
     system picker (MIME `application/octet-stream`, also accept `*/*` since some providers don't tag
     `.soil`). Primary, testable path — landed in S1.
   - **Open-with / Share intent filters** (manifest `ACTION_VIEW` + `ACTION_SEND` for `.soil`) so
     tapping a `.soil` in Files, or Sharing one to Notesprout, triggers import — landed in S4. Both
     feed the **same** import pipeline (copy the incoming `content://` URI to a cache temp first).
2. **Consume the self-describing `notebook_meta`.** Read the embedded `NotebookMeta` (id, name,
   `createdAt`/`updatedAt`, `encrypted`, `keyScope`, `cover`, **`folderPath`**) to drive placement and
   identity. Page count is **recomputed** by counting `type="page"` rows (not read from meta).
3. **Folder placement — default = the notebook's own folders; user may override.**
   - A placement dialog always appears: **"Notebook's folders"** (default — shows the resolved path
     from `folderPath`) vs **"Choose folder…"**.
   - **Only the default choice creates missing folders**, recreating the full hierarchy with the
     **same folder ids and names** from `folderPath` (walk root→parent; `getOrCreate` by id). If the
     user picks a different folder, **no missing folders are created** — the notebook lands in the
     chosen folder (or root) as-is.
4. **Encryption — prompt to read, then choose how to key the imported copy.**
   - Detect plaintext vs encrypted by **probing** the file (open as plain SQLite; failure ⇒ encrypted).
   - Encrypted ⇒ prompt for the passphrase (via `KeyResolver`, verified against the file) **before**
     reading meta (the meta is itself encrypted). Plaintext ⇒ read directly.
   - **After the user confirms the import**, an encrypted notebook offers three keying choices:
     | Choice | Action | Resulting scope |
     |---|---|---|
     | **Keep existing passphrase** | No re-key; file stays as-is | **GLOBAL** iff the kept passphrase **equals this device's current global**, else **NOTEBOOK** |
     | **Use this device's global** | `rekeyInPlace(file, enteredPass, globalPass)` (create/cache the global via `resolveForConvertToEncrypted(GLOBAL)` if none yet) | **GLOBAL** |
     | **New notebook passphrase** | `rekeyInPlace(file, enteredPass, newPass)` (prompt-with-confirm via `resolveForConvertToEncrypted(NOTEBOOK)`) | **NOTEBOOK** |
   - The index `encrypted`/`keyScope` is set to the **resulting** scope regardless of choice (this is
     the "set the scope correctly" requirement: a GLOBAL-origin notebook imported onto a device with a
     different global, keeping its passphrase, becomes **NOTEBOOK**).
5. **Plaintext imports stay plaintext.** No encryption is offered on import for plaintext notebooks
   (the user can lock later via the existing flow). The keying chooser appears **only** for
   already-encrypted imports.
6. **ID collision ⇒ Replace / Keep both.** If `notebook_meta.notebookId` already exists in the index:
   - **Keep both** — generate a fresh UUID for the imported copy (filename + index id); full import
     honoring the placement choice.
   - **Replace** — overwrite the existing notebook's `.soil` contents and update its **name** from
     meta, but **keep the existing index row's placement** (id, folder, pin, list membership). The
     placement dialog is **skipped** for Replace. Encryption choice still applies (re-keys the
     replaced file). Page count + snapshot are refreshed.
7. **Missing/foreign meta ⇒ import with fallbacks.** A valid `.soil` lacking a `notebook_meta` row
   (pre-S1 export, or a hand-rolled SQLite) still imports: name = the picked file's display name minus
   `.soil`, `folderPath` empty (so it lands at root, or the chosen folder), encryption from the probe.
   A file that is neither openable-as-plaintext nor openable-with-the-entered-passphrase, or has no
   `notebook` table, is rejected with a toast.
8. **Self-consistency after import.** After placing the file, the embedded `notebook_meta` is
   **refreshed** to the imported identity (resolved id, new `folderPath`, resulting `encrypted`/
   `keyScope`, recomputed cover/null) so a later re-export is correct. We always hold a usable key at
   this point (null for plaintext; the final passphrase for encrypted), so this is always possible —
   best-effort, never blocks the import.

---

## Session Status Board

| # | Session | Status |
|---|---|---|
| S1 | Import engine + file-picker entry (plaintext, default folder, silent fresh-UUID) | ⬜ NOT STARTED |
| S2 | Placement & conflicts: folder-choice dialog, missing-folder creation, ID-collision Replace/Keep-both, name conflicts | ⬜ NOT STARTED |
| S3 | Encrypted import: probe, passphrase-to-read, keying chooser (keep/global/new), re-key, scope, meta refresh | ⬜ NOT STARTED |
| S4 | Intent filters (open-with / share-to) + wrap-up: hygiene, edge cases, docs, MEMORY, two-device matrix | ⬜ NOT STARTED |

---

## S1 — Import Engine + File-Picker Entry (Plaintext, Default Folder)

**Status:** ⬜ NOT STARTED

**Goal:** Stand up the import pipeline end-to-end for the simple case: a **plaintext** `.soil` picked
via the system file picker, imported into its **own folder hierarchy** (recreated from `folderPath`),
registered in the index, with the embedded meta refreshed. **No conflict UI yet** — an id collision
silently takes a fresh UUID (Keep-both behavior); placement is always the notebook's own folders.
Encrypted files are detected and politely declined this session ("Encrypted import coming soon").

### Files
- **New** `app/src/main/kotlin/com/notesprout/android/NotebookImporter.kt` — the read/copy/register engine.
- `app/src/main/kotlin/com/notesprout/android/crypto/SoilCrypto.kt` — add `probe(file): SoilFileKind`
  (`Plaintext` / `Encrypted` / `Invalid`) reusing the plaintext-open check (mirror `SoilMigrator`'s
  private `verifyPlaintext`); add a small `SoilFileKind` enum.
- `app/src/main/kotlin/com/notesprout/android/data/NotebookMetaStore.kt` — add
  `readRaw(rawDb: SoilCrypto.SoilRawDb): NotebookMeta?` (SELECT json FROM notebook_meta WHERE id = 0;
  tolerant of absent table → null) and `countPages(rawDb): Int` (SELECT count(*) FROM notebook WHERE
  type = 'page' AND deletedAt IS NULL), so import reads a **cold raw file** without building Room.
- `app/src/main/kotlin/com/notesprout/android/data/index/IndexRepository.kt` — add
  `ensureFolderWithId(id, name, parentId): ObjectEntity` (insert FOLDER with the **given** id if
  absent; un-delete if soft-deleted; reuse if present) and `importNotebookRow(id, name, parentId, obj:
  NotebookObject, createdAt, updatedAt): ObjectEntity` (insert a notebook row with an **explicit** id).
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — `importSoilLauncher`
  (`OpenDocument`), an "Import Notebook (.soil)" overflow action, the "Importing…" modal, and the
  call into `NotebookImporter`.

### Steps
1. **Probe helper.** `SoilCrypto.probe(file)`: if the file opens as plain SQLite and
   `SELECT count(*) FROM sqlite_master` succeeds → `Plaintext`; if a plain open fails but the file
   exists and is non-empty → `Encrypted` (could be SQLCipher); empty/absent → `Invalid`. (A definitive
   encrypted-vs-garbage distinction needs the passphrase — deferred to S3's verify step.)
2. **Copy incoming to a cache temp.** In MainActivity, `importSoilLauncher` returns a `content://`
   URI; copy it to `cacheDir/imported_notebooks/incoming.soil` (wipe+recreate the dir first, mirroring
   the export cache hygiene) on `Dispatchers.IO`. SQLite can't open a `content://` directly, so the
   temp file is the importer's input.
3. **NotebookImporter** (`object`, `suspend` / `Dispatchers.IO`). Public entry for S1:
   `suspend fun readManifest(context, file): ImportManifest` →
   - `probe(file)`; `Invalid` → throw a friendly `ImportException`.
   - **Plaintext** this session: `SoilCrypto.openRaw(file, null)`, then
     `NotebookMetaStore.readRaw(...)` (may be null → fallback name = picked display name minus `.soil`,
     empty `folderPath`), `countPages(...)`, and a quick `notebook`-table existence check (no `notebook`
     table → `Invalid`). Returns an `ImportManifest(meta?, kind, pageCount, fallbackName)`.
   - **Encrypted** this session: throw `ImportException("Encrypted import coming soon")` (real handling
     in S3). The probe already routes here.
   And the executor: `suspend fun importPlaintext(context, repo, file, manifest, displayName): String`
   (returns the resolved notebook id):
   - **Resolve placement (default only this session):** walk `manifest.meta.folderPath` root→parent;
     for each `FolderRef`, `repo.ensureFolderWithId(ref.id, ref.name, runningParentId)`; the final
     resolved `parentId` is the last folder's id (or `null` for a root-level notebook / missing meta).
   - **Resolve id (silent Keep-both this session):** `id = meta.notebookId` if not present in the
     index, else a fresh `UUID`. (The Replace/Keep-both **dialog** arrives in S2 — S1 just never
     overwrites.)
   - **Copy** the temp `.soil` to `soilFile(context, id)` (the Garden path). Delete any stale
     `id` siblings (`-wal`/`-shm`/`-journal`) first.
   - **Register** the index row: `repo.importNotebookRow(id, displayName, parentId, NotebookObject(
     snapshot = meta.cover (plaintext only), pageCount = manifest.pageCount, encrypted = false,
     keyScope = null), createdAt = meta?.createdAt ?: now, updatedAt = now)`.
   - **Refresh embedded meta** (self-consistency, best-effort `runCatching`): open
     `SoilCrypto.openRaw(gardenFile, null)`, `INSERT OR REPLACE` a `NotebookMeta` with the resolved id,
     new `folderPath` (from `repo.getFolderAncestry(parentId)`), `encrypted=false`, then
     `PRAGMA wal_checkpoint(TRUNCATE)` + close so no sidecars are stranded in Garden.
   - Return `id`.
4. **MainActivity wiring.**
   - `importSoilLauncher = registerForActivityResult(OpenDocument())` — on a non-null URI: show the
     **"Importing…"** modal (`shape_bordered`), copy URI→temp, `readManifest`, then (plaintext)
     `importPlaintext(...)` with `displayName = meta?.name ?: fallbackName`, dismiss, toast
     "Imported \"<name>\"", and refresh the list (`scanAndRender`). Encrypted/Invalid → dismiss + toast
     the `ImportException` message. All DB/file work on `Dispatchers.IO`.
   - Add an **"Import Notebook (.soil)"** row to the overflow (next to where export-related actions
     live); on tap `importSoilLauncher.launch(arrayOf("application/octet-stream", "*/*"))`.
   - The imported notebook should appear in the **current** list once `scanAndRender` runs (it may be
     in another folder if `folderPath` placed it elsewhere — that's expected; a toast names it).
5. **Temp hygiene.** Delete `imported_notebooks/incoming.soil` after a successful import; the dir is
   wiped+recreated on the next import regardless.

### Build / install
```sh
cd apps/notesprout_android && ./gradlew assembleDebug \
  && adb -s 34E517F9 install -r app/build/outputs/apk/debug/app-debug.apk
```

### User tests
1. Export a **plaintext** notebook (existing feature) to a file, then **overflow → Import Notebook**,
   pick it → "Importing…" → toast; the notebook appears.
2. Import a notebook that lived in a **nested folder** on the source → the folder hierarchy is
   recreated with the same names; the notebook lands inside it (navigate to confirm).
3. Import a **root-level** notebook (empty `folderPath`) → lands at root.
4. Import the **same** file twice → two independent copies exist (silent fresh-UUID; no overwrite).
5. Import a **pre-S1** `.soil` (no `notebook_meta`, if one is available) → imports with the file name
   as the notebook name at root. Open it → pages render.
6. Pick an **encrypted** `.soil` → graceful "Encrypted import coming soon" toast, no crash.
7. Open an imported notebook → pages/strokes render; drawing + close work (a real, openable `.soil`).

### Completion criteria
A plaintext `.soil` picked from the file picker imports into its own (recreated) folder hierarchy,
registers in the index, opens correctly, and re-exports consistently (embedded meta refreshed).
Encrypted files are detected and declined cleanly. Build clean, installed on G10.

---

## S2 — Placement & Conflicts: Folder Choice, Replace/Keep-Both, Name Conflicts

**Status:** ⬜ NOT STARTED

**Goal:** Give the user control over **where** the import lands and resolve **collisions** — the
"choose what folder" requirement and the Replace/Keep-both requirement. Still plaintext-only (S3 layers
encryption on top of this exact flow).

### Files
- `app/src/main/kotlin/com/notesprout/android/ui/DestinationPickerState.kt` — add
  `object ImportNotebook : DestinationPickerState()` (the picker target for an in-flight import; the
  payload is held in a MainActivity field, since there's no source `ObjectEntity` yet).
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — the placement dialog, the
  collision dialog, the picker branch (`confirmPickerDestination`), and a `pendingImport` field
  carrying the manifest/temp-file/resolved context across the picker round-trip.
- `app/src/main/kotlin/com/notesprout/android/NotebookImporter.kt` — split the executor so placement
  (`parentId`) and resolved id (`Keep-both` vs `Replace`) are **inputs**, plus a
  `replacePlaintext(context, repo, file, manifest, existingId)` variant for Replace.

### Steps
1. **Placement dialog** (after `readManifest`, before executing). An `AlertDialog`/`ActionSheetDialog`
   (`shape_bordered`):
   - **"Notebook's folders"** — shows the resolved path string from `folderPath`
     (e.g. "Work / Projects", or "Top level" when empty). Default. ⇒ recreate missing folders (S1's
     `ensureFolderWithId` walk).
   - **"Choose folder…"** ⇒ stash the import context in `pendingImport`, then
     `enterPickerMode(DestinationPickerState.ImportNotebook)`. The existing folder-navigation picker
     lets the user browse + **Confirm** a destination. On confirm, `currentParentId` is the target and
     **no folders are created**.
2. **Picker branch.** In `confirmPickerDestination`, handle `ImportNotebook`: validate `pendingImport`
   is set, then run the executor with `parentId = currentParentId`, `createFolders = false`. Exit
   picker mode; show the "Importing…" modal during the IO.
3. **Collision dialog.** Before placement, if `meta.notebookId` exists in the index (and refers to a
   live notebook), show **"A notebook with this id already exists"** with:
   - **Replace** → `replacePlaintext(...)`: overwrite `soilFile(context, existingId)` contents with the
     temp file (delete old sidecars first), `repo.renameNotebook(existingId, name)`,
     `repo.updateNotebookPageCount`, `repo.updateNotebookSnapshot(existingId, meta.cover)`, refresh
     embedded meta. **Keep** existing parentId/pin/list — **skip** the placement dialog.
   - **Keep both** → fresh UUID; continue to the placement dialog (step 1).
   - **Cancel** → abort, wipe temp.
   A fresh `notebookId` (no collision) skips straight to placement.
4. **Name conflict in the target folder.** When executing into the resolved `parentId`, if a notebook
   of the same name already exists there (and it isn't the Replace target), reuse the existing
   move/copy convention: prompt **Replace / Keep both** (append " Copy"/de-dupe) — match
   `confirmPickerDestination`'s existing name-conflict dialog so the UX is consistent. (Plain name
   collision is independent of the id collision in step 3.)
5. **`createFolders` flag.** The executor takes `createFolders: Boolean` — `true` for the default
   "Notebook's folders" path (walk + `ensureFolderWithId`), `false` for a user-chosen folder
   (`parentId` used directly, nothing created).

### Build / install
G10 command (as S1).

### User tests
1. Import a nested-folder notebook → placement dialog defaults to its path; accept → folders recreated.
2. Same notebook → choose **"Choose folder…"**, navigate elsewhere, Confirm → lands there; the
   notebook's original folders are **not** created.
3. Import the **same id** twice: second time → **Replace** → original is overwritten in place (same
   folder/pin), name updated; **Keep both** → a second copy in the chosen placement.
4. Import into a folder that already has a notebook of the same name → name-conflict prompt behaves
   like Move/Copy (Replace vs Keep both).
5. Cancel at the placement dialog / picker / collision dialog → no import, no stray files, list intact.
6. Regression: Move/Copy notebook & folder pickers still work (shared picker code untouched in spirit).

### Completion criteria
The user can accept the notebook's own folders (recreated) or pick any folder (nothing created);
id collisions offer Replace (in-place, placement kept) or Keep both; name conflicts reuse the existing
convention. Build clean, installed on G10.

---

## S3 — Encrypted Import: Probe → Unlock → Keying Chooser → Re-Key → Scope

**Status:** ⬜ NOT STARTED

**Goal:** Finish the encryption story. An encrypted `.soil` prompts for its passphrase (to read the
encrypted meta), flows through the **same** placement/collision UI as S2, then offers the keying
chooser (keep / device-global / new notebook passphrase), re-keys as chosen, and sets the index scope
**correctly** — including the GLOBAL→NOTEBOOK downgrade when a kept passphrase doesn't match this
device's global.

### Files
- `app/src/main/kotlin/com/notesprout/android/crypto/KeyResolver.kt` — add
  `resolveForImportRead(activity, file): String?` — prompt (via `PassphrasePrompt`) + verify against an
  **arbitrary file** (`SoilCrypto.verifyPassphrase(file, pass)`), loop on wrong, cancel → null. Uses a
  dedicated `AttemptLimiter` bucket (`"IMPORT"`) so a notebook id isn't needed. Keeps the
  "all prompts go through KeyResolver" invariant.
- `app/src/main/kotlin/com/notesprout/android/NotebookImporter.kt` — extend `readManifest` to take an
  optional `passphrase` (open via `openRaw(file, pass)`); add the encrypted executor that re-keys and
  sets scope.
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — the encrypted branch: unlock prompt,
  keying chooser dialog, and wiring into the S2 placement/collision flow.

### Steps
1. **Unlock to read meta.** When `probe == Encrypted`: `KeyResolver.resolveForImportRead(this, temp)`;
   cancel → abort + wipe temp. With the verified `enteredPass`, `readManifest(context, temp,
   enteredPass)` opens via `SoilCrypto.openRaw(temp, enteredPass)` and reads `notebook_meta` (encrypted
   at rest — now readable) + page count. Missing meta even when encrypted → fallback name, empty
   `folderPath` (rare, but handled).
2. **Same placement + collision UI as S2.** The encrypted import reuses the placement dialog, the
   `ImportNotebook` picker, and the Replace/Keep-both collision dialog unchanged.
3. **Keying chooser** (after the user confirms placement, before executing). An `ActionSheetDialog`
   (`shape_bordered`) titled "Import encrypted notebook":
   - **"Keep existing passphrase"** → `finalPass = enteredPass`, no re-key. Scope =
     `if (enteredPass == PassphraseStore.getGlobalPassphrase(this)) GLOBAL else NOTEBOOK`.
   - **"Use this device's global"** → `globalPass = KeyResolver.resolveForConvertToEncrypted(this,
     GLOBAL)` (returns the cached global, or prompts-with-confirm to create+cache one if none exists);
     cancel → abort. If `globalPass != enteredPass`, `SoilMigrator.rekeyInPlace(file, enteredPass,
     globalPass)`. `finalPass = globalPass`, scope = **GLOBAL**.
   - **"New notebook passphrase"** → `newPass = KeyResolver.resolveForConvertToEncrypted(this,
     NOTEBOOK)` (prompt-with-confirm); cancel → abort. `SoilMigrator.rekeyInPlace(file, enteredPass,
     newPass)`. `finalPass = newPass`, scope = **NOTEBOOK**.
4. **Execute (encrypted).** Re-key happens on the **temp file** (or after the copy to Garden — pick one
   and be consistent; re-keying the temp before copy keeps Garden clean on failure). Then copy to
   `soilFile(context, id)`; register/replace the index row with `NotebookObject(encrypted = true,
   keyScope = scope, snapshot = null /* leak hygiene — never cache an encrypted cover */, pageCount)`;
   for Replace use `setEncryptionState(existingId, true, scope)` + `renameNotebook` +
   `updateNotebookPageCount` (snapshot stays null). Refresh embedded `notebook_meta` **through a keyed
   raw open** (`openRaw(file, finalPass)`) with the resulting scope, then `wal_checkpoint(TRUNCATE)` +
   close. `rekeyInPlace` already cleans its own sidecars.
5. **Order of operations** mirrors S2 with the keying chooser inserted **after** placement is resolved
   and **before** the file is written into Garden, so a cancel anywhere leaves Garden + index untouched.
6. **Leak hygiene checks:** the entered/global/new passphrases are **never** logged, never put in an
   Intent, never written to the index; an encrypted import never writes a `snapshot`; no plaintext temp
   of an encrypted file is ever left behind (the temp is the still-encrypted `.soil`; re-key is in
   place; delete the temp on completion).

### Build / install
G10 command.

### User tests
1. Import a **GLOBAL**-encrypted notebook exported from **this** device (same global) →
   "Keep existing" → opens with the cached global; index shows GLOBAL (re-export → still GLOBAL).
2. Import a **GLOBAL**-encrypted notebook from a device with a **different** global, "Keep existing" →
   scope becomes **NOTEBOOK** (every open now prompts; index scope = NOTEBOOK).
3. Same file, choose **"Use this device's global"** → re-keyed; opens via the cached global with **no**
   prompt afterward; scope = GLOBAL.
4. Import a **NOTEBOOK**-encrypted notebook, "Keep existing" → opens prompting its original passphrase;
   "New notebook passphrase" → opens prompting the **new** passphrase, old one rejected.
5. Wrong passphrase at the unlock prompt → loops with "Wrong passphrase"; lockout escalation works
   (`AttemptLimiter` "IMPORT" bucket); Cancel aborts cleanly.
6. Encrypted import lands in the right folder (default recreated / chosen) and respects Replace/Keep-both.
7. After any encrypted import, the file on disk is still encrypted (raw SQLite open fails); no plaintext
   snapshot in the index; no stray `-wal`/`-shm` in Garden.

### Completion criteria
Encrypted `.soil` files import through the same placement/collision UI; the keying chooser re-keys as
chosen; the index scope is set correctly (including GLOBAL→NOTEBOOK downgrade); no plaintext leaks; all
prompts route through `KeyResolver`. Build clean, installed on G10.

---

## S4 — Intent Filters (Open-With / Share-To) + Wrap-Up

**Status:** ⬜ NOT STARTED

**Goal:** Add the second entry point (tap-a-`.soil` / share-to-Notesprout), then the hygiene /
edge-case / docs / memory / two-device-matrix wrap-up on **G10 (`34E517F9`)** and **P2P (`287d2364`)**.

### Steps
1. **Intent filters.** In `AndroidManifest.xml`, add to MainActivity (or a thin trampoline) an
   `ACTION_VIEW` filter (`scheme content`/`file`, MIME `application/octet-stream`, and a
   `pathPattern`/host-glob for `.*\\.soil`) and an `ACTION_SEND` filter (MIME `application/octet-stream`).
   In `onCreate`/`onNewIntent`, detect an incoming `.soil` URI (`intent.data` for VIEW,
   `EXTRA_STREAM` for SEND), copy it to the import cache temp, and run the **same** `NotebookImporter`
   pipeline (probe → unlock if needed → manifest → placement → collision → keying → execute). Guard
   against re-handling the same intent across config changes (consume the URI once).
2. **Hygiene sweep.** Confirm `imported_notebooks/` is wiped+recreated each import and the temp is
   deleted after success/cancel; no stray `-wal`/`-shm`/`-journal` in Garden after plaintext or
   encrypted imports; re-key cleanup verified; cancelling at any dialog leaves the index + Garden
   untouched.
3. **Edge cases (verify or note):**
   - `folderPath` referencing a folder id that exists on-device but as a **non-folder** (notebook id
     collision) → that level is created with a fresh id (hierarchy still forms); document.
   - `folderPath` referencing a **soft-deleted** folder id → `ensureFolderWithId` un-deletes/reuses it.
   - Import a notebook whose name collides at root; import into the **same** folder it claims to come
     from (default placement when those folders already exist — reuse, don't duplicate).
   - Very large notebook (many image pages) → copy + re-key run off-UI behind the modal; no ANR.
   - A truly corrupt / non-`.soil` file → rejected with a toast, no partial index row.
   - Share-to-Notesprout while the app is **cold** vs **already open** (onCreate vs onNewIntent paths).
4. **Docs:**
   - `docs/full-notebook-export.md` — replace the "Import (Future — Stub)" section with a real
     **"Import"** section: entry points, the probe, meta consumption, placement (default-recreates /
     chosen-creates-nothing), id-collision Replace/Keep-both, the encrypted keying chooser + scope
     rule, embedded-meta refresh. (Consider renaming the doc to cover both directions, or add a sibling
     `docs/full-notebook-import.md` and a CLAUDE.md row — pick one; keep the docs table in sync.)
   - `docs/encryption.md` — document the import keying chooser and the GLOBAL→NOTEBOOK scope rule on
     cross-device import, and `KeyResolver.resolveForImportRead` + the `"IMPORT"` AttemptLimiter bucket.
   - `docs/mainactivity-and-recents.md` — the Import overflow action, the placement/collision dialogs,
     the intent-filter entry, and the import cache dir.
   - `CLAUDE.md` — note import in the full-notebook-export docs row (or new row); one Architecture line
     if a new canonical class (`NotebookImporter`) warrants it.
5. **MEMORY:** add/refresh a project memory for full-notebook import (probe → unlock → consume
   `notebook_meta` → placement default/choose → Replace/Keep-both → keying chooser → scope rule →
   meta refresh) and cross-link `[[project_full_notebook_export]]` and
   `[[project_encryption_architecture]]`; update `MEMORY.md`.
6. **Two-device matrix on G10 + P2P:** plaintext import (picker + open-with + share-to); nested vs root
   placement; choose-folder; Replace vs Keep-both; encrypted GLOBAL (same global / different global /
   re-key to device global) and NOTEBOOK (keep / new passphrase); wrong-passphrase loop + lockout;
   sidecar/temp hygiene; **cross-device round-trip** (export from G10 → import on P2P and vice-versa).
7. On **all tests pass**: mark every session ✅ DONE; final wrap-up commit (docs + MEMORY, `🌱` prefix).
   **Do not push.**

---

## Out of Scope / Future

- **Bulk / folder import** — importing many `.soil` files or a whole exported folder set at once.
  Gated on the same multi-select work as bulk export / encryption Phase 3.
- **Encrypt-on-import for plaintext** — deliberately not offered (decision 5); the user can lock after
  import. Revisit only if requested.
- **Cross-notebook link auto-repair beyond folders** — recreating folders with the same ids helps link
  resolution; full link rewrite/repair across an imported set is a separate effort.
- **Conflict-aware merge** — Replace overwrites; there is no page-level merge of an imported copy into
  an existing notebook.
- **Progress for very large copies** — a determinate progress bar instead of the indeterminate
  "Importing…" modal. Not needed for typical notebook sizes.
