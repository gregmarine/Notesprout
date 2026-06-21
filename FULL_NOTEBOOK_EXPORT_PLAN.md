# Full Notebook Export — Plan

> Make a notebook **transportable** — copy the notebook *itself* (its `.soil` file) to the device or
> into the platform share sheet, for moving to another device or for backup. This is **not** a format
> conversion (PDF/PNG already exist); it is the notebook file, self-described so it can be **imported**
> back into Notesprout later (import is a **separate, future effort** — this plan only makes the export
> self-describing enough that import becomes possible).
>
> Same working agreement as `NOTEBOOK_ENCRYPTION_PHASE2_PLAN.md` (which went well): each session is a
> self-contained, ordered chunk sized for **Sonnet at medium effort**. Do sessions **one at a time, in
> order**.

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
  extras, never in the global index · every `.soil` open routes through `SoilCrypto` when keyed.
- **Reuse existing primitives** — `soilFile()`, `SoilDatabase` + its migrations, `SoilCrypto`,
  `KeyResolver`, `IndexRepository` (`getEncryptionInfo`, `getNotebook`, `getNotebooks`), `ActionSheetDialog`,
  the existing FileProvider share pattern (`ClipData` + `FLAG_GRANT_READ_URI_PERMISSION`), and the
  `CreateDocument` "Save to device" launcher pattern. Grow surfaces minimally; do **not** add a parallel path.

## Status Legend

`⬜ NOT STARTED` · `🔨 IN PROGRESS` · `🧪 AWAITING TEST` (built + installed, awaiting user verdict) · `✅ DONE`

---

## Locked Decisions (confirmed with the user before S1)

1. **Container format = raw `.soil`.** The export *is* the notebook's SQLite file, copied to a friendly
   filename. No zip, no wrapper, no separate manifest. Filename = the notebook's **name from the global
   index**, sanitized, with the `.soil` extension (`<NotebookName>.soil`).
2. **Self-describing `.soil`.** Import metadata lives **inside** the `.soil` in a new single-row
   `notebook_meta` table. For encrypted notebooks this metadata is therefore **encrypted too** (opaque
   until unlocked on import) — that is the accepted trade-off; an encrypted notebook's encrypted status
   travels with the file, so **no "exported file is unencrypted" warning** is shown for full-notebook
   export (unlike PDF export, which stays plaintext and keeps its warning).
3. **Metadata upkeep = continuous.** Written at notebook **creation**, refreshed whenever the notebook is
   **opened/closed** (NotebookActivity already holds the key). Full-notebook export is a **pure file copy
   with NO passphrase prompt**, even for encrypted notebooks. Best-effort refresh at export time only when
   the file is openable without a prompt (plaintext always; encrypted-GLOBAL when the global key is
   cached); encrypted-NOTEBOOK falls back to the last-edit metadata — never prompt.
4. **What travels in `notebook_meta`:**
   - **Essentials:** format version, notebook **id**, **name**, `createdAt`, `updatedAt`, `encrypted`
     flag, `keyScope` (`GLOBAL`/`NOTEBOOK`/null).
   - **Cover snapshot** — base64 PNG; **plaintext notebooks only** (encrypted notebooks store no snapshot
     by design — it stays null).
   - **Folder path** — the **full ancestor chain** root→immediate-parent, each as `{id, name, parentId}`,
     so an importing device that lacks those folders can recreate the hierarchy with the **same ids and
     names**. Empty list = the notebook lived at root.
   - **Provenance:** `exportedAt` (set at export), `appVersionCode`.
   - **Excluded (device-local):** pin state, list membership, recents, page count (page count is trivially
     derivable on import by counting `type="page"` rows — not embedded).
5. **Entry points:** the existing **Export** action in MainActivity's notebook long-press context menu,
   and the **Export** toolbar button inside an open notebook (NotebookActivity). Both currently go straight
   to PDF; both gain a small **format chooser** (PDF vs Full Notebook) as the first step.
6. **Destinations:** reuse the existing two-way choice — **Save to device** (`CreateDocument`) and
   **Share** (platform share sheet via FileProvider). Same as PDF export.

---

## Session Status Board

| # | Session | Status |
|---|---|---|
| S1 | Self-describing `.soil`: `notebook_meta` table, model, continuous upkeep, migration-set fix | ✅ DONE |
| S2 | Export-format chooser + full-notebook copy engine + MainActivity (context-menu) path | ✅ DONE |
| S3 | NotebookActivity (open-notebook) export path + encrypted (no-prompt) + open-file flush/checkpoint | ⬜ NOT STARTED |
| S4 | Wrap-up: hygiene, edge cases, docs, MEMORY, two-device matrix (G10 + P2P) | ⬜ NOT STARTED |

---

## S1 — Self-Describing `.soil`: `notebook_meta` Table + Continuous Upkeep

**Status:** ✅ DONE

**Goal:** Give every `.soil` a single-row `notebook_meta` table holding the import metadata, and keep it
current at creation / open / close. **No export UI yet** — this is the foundation S2/S3 copy out. Mirrors
the P2.S3 `undo_redo_state` pattern exactly (single row, `CHECK (id = 0)`, encrypted-at-rest for free).

### Files
- `app/src/main/kotlin/com/notesprout/android/data/SoilDatabase.kt` — bump `@Database version = 3`; add
  `MIGRATION_2_3` creating `notebook_meta`.
- **New** `app/src/main/kotlin/com/notesprout/android/data/NotebookMeta.kt` — the `@Serializable` model +
  `FolderRef` + `toJson()`/`fromJson()`.
- **New** `app/src/main/kotlin/com/notesprout/android/data/NotebookMetaStore.kt` — read/write/upsert the
  single row, and `buildFromIndex(...)` to assemble a `NotebookMeta` from the global index.
- `app/src/main/kotlin/com/notesprout/android/data/index/IndexRepository.kt` — a small read helper to walk
  the folder ancestor chain (e.g. `getFolderAncestry(parentId): List<ObjectEntity>` root→parent) if not
  already expressible from `getNotebook`/folder reads.
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — `createNotebook` (~1389): create the
  `notebook_meta` table in the raw-SQL bootstrap (alongside `undo_redo_state` ~1474) **and** write the
  initial meta row.
- `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` — refresh meta on **open** (after the
  Room build, ~1850) and on **seal** (`sealNotebook` ~3168, after `saveStrokes`, before checkpoint/close).
- **Every** `SoilDatabase` Room builder site must register the full migration set (see step 4).

### Steps
1. **Migration + schema.** In `SoilDatabase.kt` add:
   ```kotlin
   val MIGRATION_2_3 = object : Migration(2, 3) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL(
               "CREATE TABLE IF NOT EXISTS notebook_meta " +
               "(id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)"
           )
       }
   }
   ```
   Bump `version = 3`. Keep `exportSchema = false` (matches current).
2. **Model** (`NotebookMeta.kt`):
   ```kotlin
   @Serializable
   data class NotebookMeta(
       val formatVersion: Int = 1,
       val notebookId: String,
       val name: String,
       val createdAt: Long,
       val updatedAt: Long,
       val encrypted: Boolean,
       val keyScope: KeyScope? = null,     // null unless encrypted
       val cover: String? = null,          // base64 PNG; plaintext notebooks only
       val folderPath: List<FolderRef> = emptyList(), // root → immediate parent
       val exportedAt: Long? = null,
       val appVersionCode: Int? = null,
   ) { fun toJson(): String; companion object { fun fromJson(s: String): NotebookMeta } }

   @Serializable
   data class FolderRef(val id: String, val name: String, val parentId: String?)
   ```
   Use the project's existing `kotlinx.serialization` Json instance convention (match `NotebookObject` /
   `TemplateObject` `toJson`/`fromJson`). `KeyScope` is already `@Serializable` (used in `NotebookObject`).
3. **Store** (`NotebookMetaStore.kt`, all `suspend` / `Dispatchers.IO`):
   - `suspend fun write(db: SoilDatabase, meta: NotebookMeta)` — upsert row id=0 via
     `db.openHelper.writableDatabase` (`INSERT OR REPLACE INTO notebook_meta(id, json) VALUES (0, ?)`,
     parameterized). Match how P2.S3 writes `undo_redo_state` (same connection, same off-UI rule).
   - `suspend fun read(db: SoilDatabase): NotebookMeta?` — `SELECT json FROM notebook_meta WHERE id = 0`,
     `fromJson` or null. Tolerant of absent row/table (older file mid-migration) → null.
   - `suspend fun buildFromIndex(repo: IndexRepository, notebookId: String): NotebookMeta?` — load the
     `ObjectEntity` (`getNotebook`) + parse its `NotebookObject` data; resolve `encrypted`/`keyScope` via
     `getEncryptionInfo`; walk the `parentId` chain into `List<FolderRef>` (root→parent); set
     `cover = NotebookObject.snapshot` **only when not encrypted** (it is already null when encrypted);
     stamp `appVersionCode` from `BuildConfig`/`PackageInfo`. Returns null if the notebook row is gone.
   - A convenience `suspend fun refresh(db, repo, notebookId)` = `buildFromIndex` then `write`.
4. **Migration-set consistency (latent-bug fix).** Bumping to v3 means any Room builder that opens an
   **older** `.soil` must run the migrations or it throws. Today only `NotebookActivity` adds
   `MIGRATION_1_2`; the transient builders do **not**. Add **`.addMigrations(SoilDatabase.MIGRATION_1_2,
   SoilDatabase.MIGRATION_2_3)` to every builder site:**
   `NotebookActivity` (~1848, extend the existing call), `NotebookExporter` (~157, ~220, ~556),
   `CoverDialog` (~480), and the MainActivity PDF-export builder (~2340). **Prefer a single factory** —
   add `SoilDatabase.builder(context, path): RoomDatabase.Builder<SoilDatabase>` that applies
   `openCallback()` + both migrations, and route all sites through it (keeps future bumps one-line). Keep
   each site's `.openHelperFactory(SoilCrypto.roomFactory(key))` where it already exists.
5. **Write at creation.** In `createNotebook`'s raw bootstrap (~1428+), `CREATE TABLE IF NOT EXISTS
   notebook_meta (...)` right after the `undo_redo_state` create, then `INSERT` the initial row built from
   the just-created index entity (id, name, createdAt/updatedAt, encrypted/keyScope, empty cover, folder
   ancestry of `currentParentId`). This uses the same `exec(...)` shim that already handles both the
   SQLCipher and plaintext connections — no key handling beyond what's there.
6. **Refresh on open + close (NotebookActivity).**
   - **Open:** after `soilDatabase = builder.build()` (~1850), launch an IO task to
     `NotebookMetaStore.refresh(soilDatabase, indexRepo, nbId)`. Off the UI thread; non-blocking — a failed
     refresh must never block notebook open (wrap in `runCatching`, `Slog.d` on failure).
   - **Close:** in `sealNotebook` (~3168), after `saveStrokes(db)` and **before** the
     `incremental_vacuum` / `wal_checkpoint(TRUNCATE)`, call the refresh against `indexRepo`/`nbId`. This
     guarantees the freshest name/cover/folder path is baked in before the file goes cold — which is what
     makes the later pure-copy export correct without a prompt.

### Build / install
```sh
cd apps/notesprout_android && ./gradlew assembleDebug \
  && adb -s 34E517F9 install -r app/build/outputs/apk/debug/app-debug.apk
```

### User tests
1. Create a new **plaintext** notebook, draw, close, reopen → no crash; everything behaves as before
   (the meta table is invisible to the user this session).
2. Create a new **encrypted** notebook (Global and Notebook scope), open/close/reopen → no crash; opening
   still prompts correctly for NOTEBOOK scope.
3. Open an **existing pre-migration** notebook (one created before this build) → it migrates v2→v3 cleanly
   and opens. Export to **PDF** still works (exercises the transient builders now carrying the migrations).
4. *(Optional verify, user's call)* `adb pull` a plaintext `.soil`, open in a SQLite browser → a
   `notebook_meta` table exists with one JSON row containing the correct id/name/folder path.

### Completion criteria
Every new `.soil` carries a `notebook_meta` row written at creation and refreshed on open/close; all
`SoilDatabase` builders run the full migration set; existing notebooks migrate to v3 and open without
regression; no UI change yet. Build clean, installed on G10.

---

## S2 — Export-Format Chooser + Full-Notebook Copy Engine (MainActivity path)

**Status:** ✅ DONE

**Goal:** From the **context menu** (long-press → Export), let the user choose **PDF** (existing) or
**Full Notebook (.soil)** (new), and implement the copy engine + Save/Share for the new option. Plaintext
focus this session; encrypted is finished in S3 (the copy itself already works for encrypted — S3 adds the
open-notebook path and confirms no-prompt behaviour).

### Files
- **New** `app/src/main/kotlin/com/notesprout/android/NotebookPackager.kt` — the copy/checkpoint engine.
- `app/src/main/res/xml/file_paths.xml` — add `<cache-path name="exported_notebooks" path="exported_notebooks/" />`.
- `app/src/main/kotlin/com/notesprout/android/MainActivity.kt` — insert the format chooser before the
  current PDF flow (`startExportFromMain` ~2284); add a `saveSoilLauncher` + the new full-notebook flow +
  a Save/Share chooser for `.soil`.

### Steps
1. **NotebookPackager** (`object`, `suspend` / `Dispatchers.IO`):
   `suspend fun packageForExport(context, repo, notebookId, openableKey: String?): File`
   - Resolve the source via `soilFile(context, notebookId)`.
   - **Best-effort meta refresh (no prompt):** if `openableKey != null` (plaintext → `null` key but
     openable; encrypted-GLOBAL → cached key passed in) open a **transient** keyed/plain connection,
     `NotebookMetaStore.refresh(...)` with `exportedAt = now`, **checkpoint** (`wal_checkpoint(TRUNCATE)`),
     close cleanly (reuse the `CoverLoader.checkpointTruncateAndClose` pattern so no `-wal`/`-shm`/`-journal`
     is stranded). If not openable without a prompt (encrypted-NOTEBOOK), **skip the refresh** and just set
     `exportedAt` is **not** possible without opening — so leave the embedded meta as last-written (document
     this; S3 covers the rationale).
   - **Copy:** copy the (now-checkpointed) `.soil` **main file only** to
     `cacheDir/exported_notebooks/<safeName>.soil`. After `wal_checkpoint(TRUNCATE)` the `-wal`/`-shm` are
     empty, so the single main file is self-contained — do **not** copy sidecars. Clear/recreate the
     `exported_notebooks/` dir first (mirror `NotebookExporter`'s `deleteRecursively()` + `mkdirs()`).
   - **Filename:** `<safeName>.soil` where `safeName` = the index **name** sanitized with the existing
     notebook-name whitelist (`[^a-zA-Z0-9_\-. ]` stripped; reject `.`/`..`; non-empty fallback to the
     notebook id). De-dupe not needed (single file in a wiped dir).
   - Return the `File`.
2. **Format chooser.** Rename/replace `startExportFromMain(entity)` so it first shows an
   `ActionSheetDialog` titled "Export" with two rows: **"Export as PDF"** → the existing PDF path (the
   current body of `startExportFromMain`, including its encrypted-unencrypted warning + optional PDF
   password), and **"Export Notebook (.soil)"** → the new full-notebook path. Use a sensible icon
   (`ic_export` for the sheet rows or reuse PDF/notebook icons if present).
3. **Full-notebook flow (from MainActivity).**
   - Read `getEncryptionInfo`. **No unencrypted-warning** for this path (decision 2).
   - Determine `openableKey`: plaintext → `null`; encrypted-GLOBAL → `KeyResolver` cached value **without
     prompting** (use the cache-only accessor; if not cached, skip refresh — do **not** prompt);
     encrypted-NOTEBOOK → skip refresh (no prompt).
   - Show the existing "Exporting…" modal (`shape_bordered`), run `NotebookPackager.packageForExport(...)`
     on IO, dismiss, then `showSoilExportChoice(file)`.
4. **Save / Share for `.soil`.**
   - `saveSoilLauncher = registerForActivityResult(CreateDocument("application/octet-stream"))` mirroring
     `savePdfLauncher` (reuse `pendingExportFile` + the same `copyTo(out)` body).
   - `showSoilExportChoice(file)`: AlertDialog (`shape_bordered`), **"Save to device"** →
     `saveSoilLauncher.launch(file.name)`; **"Share"** → `ACTION_SEND`, `type = "application/octet-stream"`,
     `EXTRA_STREAM` = FileProvider uri, **with `clipData = ClipData.newRawUri("", uri)` +
     `FLAG_GRANT_READ_URI_PERMISSION`** (the NA5C Drive-upload fix). Chooser title "Share Notebook".

### Build / install
G10 command (as S1).

### User tests
1. Long-press a **plaintext** notebook → Export → the chooser offers **PDF** and **Notebook (.soil)**.
   Pick PDF → unchanged behaviour. Pick Notebook → "Exporting…" → Save/Share sheet.
2. **Save to device** → pick a location → a `<NotebookName>.soil` file lands there; it's a single file
   (no `-wal`/`-shm`). Copy it back via a file manager and confirm size is sane.
3. **Share** → the share sheet appears; sharing to Drive/Files/email succeeds (no silent failure).
4. The exported filename matches the notebook's **index name** (rename the notebook first, re-export →
   filename reflects the new name).
5. *(Optional)* open the exported `.soil` in a SQLite browser → `notebook_meta` row present with correct
   id/name/folder ancestry and `exportedAt` set.

### Completion criteria
Context-menu Export offers PDF vs Full Notebook; full-notebook export produces a single self-contained
`<name>.soil` in cache; Save-to-device and Share both work with the correct MIME and URI-grant pattern;
PDF path unchanged. Build clean, installed on G10.

---

## S3 — Open-Notebook Export (NotebookActivity) + Encrypted, No-Prompt Pure Copy

**Status:** ⬜ NOT STARTED

**Goal:** Offer the same Full-Notebook option from **inside an open notebook**, and finish the encrypted
story: full-notebook export of an encrypted notebook is a **pure copy with no passphrase prompt and no
unencrypted warning**, and the **currently-open** notebook is flushed + checkpointed before the copy so the
exported file is complete and current.

### Files
- `app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt` — `startExport` (~3824) gains the
  format chooser; new full-notebook branch; `saveSoilLauncher` + `showSoilExportChoice` + `shareSoil`
  (mirror the PDF equivalents at ~3895/~3911).
- `app/src/main/kotlin/com/notesprout/android/NotebookPackager.kt` — add an **open-DB variant** that
  packages from the already-open keyed `SoilDatabase` (no second connection).

### Steps
1. **Format chooser in NotebookActivity.** In `startExport(db)` (~3824), before the PDF-specific logic,
   show the same two-row "Export" `ActionSheetDialog`. **"Export as PDF"** → the existing body (encrypted
   warning + PDF-password prompt + `runExport`). **"Export Notebook (.soil)"** → the new branch (step 2).
2. **Open-DB packaging (no prompt, current content).** Add
   `NotebookPackager.packageOpenForExport(context, db, repo, notebookId, soilPath): File`:
   - `saveStrokes(db)` is already callable in NotebookActivity — flush the current page first (the open
     path), then `NotebookMetaStore.refresh(db, repo, notebookId)` with `exportedAt = now` **through the
     live keyed connection** (no new prompt — the key is already held by `db`).
   - **Checkpoint the live connection** (`db.openHelper.writableDatabase` → `PRAGMA wal_checkpoint(TRUNCATE)`)
     so the main `.soil` is complete on disk, then **copy the main file** to
     `cacheDir/exported_notebooks/<safeName>.soil`. The live Room connection stays open; do **not** delete
     its `-wal`/`-shm` (SQLite owns them while the connection is open — same rule as `data/PageCopier.kt`).
     Because we just TRUNCATE-checkpointed and the user isn't drawing during the modal, the copied main
     file holds all committed content. Run on `Dispatchers.IO`.
   - This branch works identically for plaintext and encrypted open notebooks — the key question never
     arises because `db` is already unlocked. **No unencrypted warning** is shown.
3. **Wire the branch.** Show the "Exporting…" modal, run `packageOpenForExport(...)`, dismiss, then
   `showSoilExportChoice(file)`.
4. **Save / Share in NotebookActivity.** Add `saveSoilLauncher` (`CreateDocument("application/octet-stream")`,
   reuse `pendingExportFile`), `showSoilExportChoice` (Save/Share AlertDialog), and `shareSoil(file)`
   (mirror `sharePdf` ~3911 but `type = "application/octet-stream"`, chooser "Share Notebook", same
   `ClipData` + grant flags).
5. **MainActivity encrypted confirm.** Confirm S2's no-prompt path for encrypted-NOTEBOOK from the context
   menu: it copies the cold file as-is (meta = last-edit state). Document the freshness window in
   `docs/encryption.md` / `docs/mainactivity-and-recents.md`: an encrypted-NOTEBOOK notebook renamed in the
   index but **not reopened** since the rename exports with the prior embedded name (the file copy and its
   *export filename* still use the current index name; only the **embedded** `notebook_meta.name` may lag).
   This is acceptable and self-heals on the next open.

### Build / install
G10 command.

### User tests
1. Open a **plaintext** notebook → toolbar Export → chooser (PDF / Notebook). Pick Notebook → draw a fresh
   stroke first, export, Save to device → reopen the exported `.soil` (copy into Garden via import-by-hand
   later, or inspect in a SQLite browser) → the **latest** stroke is present (proves flush+checkpoint).
2. Open an **encrypted** notebook (both scopes) → Export → Notebook → **no** passphrase prompt, **no**
   "exported file is unencrypted" warning → Save/Share works. The exported file is still encrypted
   (opening it raw in a SQLite browser fails without the key).
3. From MainActivity context menu, export an **encrypted-NOTEBOOK** notebook → no prompt, no warning,
   single `.soil` produced.
4. PDF export from both MainActivity and NotebookActivity still shows its warning + optional password and
   works (regression).

### Completion criteria
Full-notebook export available from both entry points; open-notebook export flushes + checkpoints so the
copy is current; encrypted export is a silent pure copy (no prompt, no warning) and stays encrypted; PDF
paths unchanged. Build clean, installed on G10.

---

## S4 — Wrap-Up: Hygiene, Edge Cases, Docs, MEMORY, Two-Device Matrix

**Status:** ⬜ NOT STARTED

**Goal:** Hygiene sweep, edge-case verification, documentation, memory, and a two-device test matrix on
**G10 (`34E517F9`)** and **P2P (`287d2364`)**.

### Steps
1. **Sidecar / temp hygiene.** Verify exported `.soil` files leave the device free of stray `-wal`/`-shm`/
   `-journal` — both in the **source** Garden dir (open-notebook checkpoint must not strand sidecars) and
   in the export **cache** dir. Confirm `exported_notebooks/` is wiped+recreated each export so stale files
   don't accumulate. Confirm the live-connection case never deletes the open notebook's sidecars (rule
   from `PageCopier`).
2. **Edge cases (verify or note):**
   - Export a notebook whose name has spaces/`-`/`_`/`.` → sanitized filename is correct and valid; a name
     that sanitizes to empty falls back to the notebook id.
   - Export immediately after **rename** (cold notebook, never reopened) → export *filename* = new name;
     embedded `notebook_meta.name` may lag for encrypted-NOTEBOOK (documented in S3).
   - Export a notebook at **root** (empty `folderPath`) vs **nested** (full ancestor chain present and
     ordered root→parent).
   - Cancelling the Save dialog / Share chooser leaves no partial file at the destination and the cache
     file is harmless (wiped next export).
   - Large notebook (many high-res image pages) → copy completes off-UI behind the modal; no ANR.
3. **Docs:**
   - **New** `docs/full-notebook-export.md` — the format: raw `.soil` + `notebook_meta` schema, the
     `NotebookMeta`/`FolderRef` fields, continuous-upkeep rule, pure-copy/no-prompt export, the encrypted
     trade-off (no warning, meta encrypted), checkpoint-before-copy rule, and a **stub "Import (future)"**
     section describing how the embedded metadata is intended to be consumed (recreate missing folders by
     id/name from `folderPath`; keep or regenerate the notebook id; honor `encrypted`/`keyScope`).
   - `CLAUDE.md` — add a `docs/full-notebook-export.md` row to the docs table; one line under
     Architecture noting the `.soil` is self-describing via `notebook_meta`.
   - `docs/data-architecture.md` — document `.soil` **schema v3** (`notebook_meta`, single row,
     `CHECK (id = 0)`), alongside the existing v2 `undo_redo_state` note; note all `SoilDatabase` builders
     now run the full migration set.
   - `docs/mainactivity-and-recents.md` — extend the Export sections: the format chooser, the
     full-notebook destination flow, the new FileProvider cache path.
   - `docs/encryption.md` — note that full-notebook export of an encrypted notebook is a silent pure copy
     (encrypted status travels; no plaintext-leak warning, unlike PDF) and that `notebook_meta` is
     encrypted at rest with the rest of the file.
4. **MEMORY:** add a project memory for the full-notebook-export format (raw `.soil` + `notebook_meta`,
   self-describing, pure-copy export, encrypted trade-off, folder-ancestry travels) and refresh
   `MEMORY.md`. Cross-link `[[project_encryption_architecture]]`.
5. **Testing:** clean build, install on **G10** and **P2P**.
   - **Full matrix on both devices:** PDF export (regression, both entry points); full-notebook export of
     plaintext from context menu + open notebook; full-notebook export of encrypted (GLOBAL + NOTEBOOK)
     with no prompt/no warning; Save-to-device + Share for each; root vs nested folder ancestry; rename →
     re-export filename; sidecar hygiene check.
6. On **all tests pass**: mark every session ✅ DONE; final wrap-up commit (docs + MEMORY, `🌱` prefix).
   **Do not push.**

---

## Out of Scope / Future

- **Import** — the consuming side (open a `.soil`, recreate folders from `folderPath`, register in the
  index, handle id collisions, map `keyScope=GLOBAL` onto the new device's global passphrase or downgrade
  to NOTEBOOK). This plan only makes export self-describing enough to make import buildable later. The
  `docs/full-notebook-export.md` "Import (future)" stub seeds it.
- **Bulk / folder export** — exporting many notebooks or a whole folder as a set. Gated on the same
  multi-select work the encryption Phase 3 bulk items are gated on.
- **Encrypted-NOTEBOOK meta freshness on cold rename** — optionally refresh embedded meta at export by
  prompting; deliberately **not** done (decision 3 keeps export prompt-free). Revisit only if the lag
  proves confusing in practice.
- **Compressed / single-file bundle of cover+content for faster transfer** — not needed; SQLite already
  packs everything.
