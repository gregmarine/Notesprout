# NoteSprout Android — Pruning Backlog

Findings from the vulnerability & code-quality scan of **2026-06-07** (branch `seed`).
This is the working backlog for hardening the active Android codebase. Each item has a
stable ID — reference it directly (e.g. *"let's do M-2"*) and Claude Code can cold-start
from this file.

## How to use this file
- Pick an item by ID. Read its **Files**, **Problem**, **Fix approach**, **Verification**.
- Implement the fix, then run the **Verification** step.
- Flip **Status** ☐ → ☑, fill in **Notes/commit** with the commit hash / what changed.
- Keep CLAUDE.md updated only if a fix changes a documented convention or invariant.

**Severity legend:** 🔴 Critical (data loss / crash / security) · 🟡 Moderate (fix before
wider release) · 🟢 Low / Informational.

**Progress:** 8 / 10 tracked (C+M) · Low items tracked separately at the bottom.

---

## 🔴 Critical

### C-1 · Creating a notebook with an existing name corrupts the existing notebook
- **Status:** ☑ Done
- **Severity:** 🔴 Critical
- **Files:** `MainActivity.kt:555-650` (`createNotebook`)
- **Problem:** `openOrCreateDatabase` + `CREATE TABLE IF NOT EXISTS` + unconditional insert of a
  fresh `notebook`/`page`/`layer`. Reusing an existing name opens the existing `.soil` and
  inserts a **second** `type='notebook'` row plus an orphan page/layer — breaking the
  one-metadata-row invariant. `getNotebookObject()` (`LIMIT 1`) and `getPagesSorted()`
  (`type='page'`) then mix rows from both logical notebooks. Silent, irreversible corruption.
- **Fix approach:** Reject creation if `soilFile.exists()` (or auto-suffix the name); show a
  clear "notebook already exists" message. Coordinate with C-2 (shared validation path).
- **Verification:** Create a notebook named `X`; create another `X` → second attempt is
  rejected (or suffixed), and opening the original `X` shows exactly one notebook row and its
  original pages.
- **Notes/commit:** Added shared `validateNotebookName()` gate in `MainActivity.kt`; rejects a
  name whose `.soil` file already `exists()`. Same helper backs C-2.

### C-2 · Notebook name not sanitized → path traversal on write
- **Status:** ☑ Done
- **Severity:** 🔴 Critical
- **Files:** `MainActivity.kt:522-563` (name capture → `File(notesDir, "$name.soil")`)
- **Problem:** Only `isBlank()` is checked. Names with `/` or `../` write outside
  `/Documents/NoteSprout/`; combined with `MANAGE_EXTERNAL_STORAGE` this can clobber arbitrary
  shared-storage files and escape the documented storage location.
- **Fix approach:** Reject names containing path separators / `..` / reserved chars; whitelist a
  safe filename charset. Reuse the exporter's sanitizer pattern (`NotebookExporter.kt:49`,
  `Regex("[^a-zA-Z0-9_\\-. ]")`).
- **Verification:** Names like `../x`, `a/b`, `..` are rejected with a message; valid names still
  create files only under `/Documents/NoteSprout/`.
- **Notes/commit:** `validateNotebookName()` whitelists `[^a-zA-Z0-9_\-. ]` (excludes `/` `\`) plus
  an explicit `.`/`..` reject. Enforced at the dialog and re-checked defensively in
  `createNotebook` before any file is opened.

---

## 🟡 Moderate

### M-1 · Unbounded base64 image decode → OOM / DoS via crafted or large `.soil`
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `DrawingActivity.kt:1476-1485` (template), `DrawingActivity.kt:1336-1337` (snapshot),
  `NotebookExporter.kt:59-60` & `166-167` (cover/template in export)
- **Problem:** `BitmapFactory.decodeByteArray(...)` decodes at full resolution with no
  `inSampleSize` / dimension cap. `.soil` files are portable user documents, so an oversized or
  malicious embedded image OOM-crashes the app on open/export — low threshold on e-ink devices.
- **Fix approach:** Decode with `inJustDecodeBounds` first, cap to view/page dimensions, apply
  `inSampleSize`. Mirror the working sampling in `CoverDialog.kt:388-394`.
- **Verification:** Open/export a notebook with a deliberately huge embedded template/snapshot →
  no OOM; image is downsampled to fit.
- **Notes/commit:** Added shared `core/BitmapDecode.decodeSampled(bytes, reqW, reqH)` — decodes
  bounds-first (`inJustDecodeBounds`) then applies a power-of-two `inSampleSize` so decode memory
  is capped to ~target dims (mirrors `CoverDialog.encodeImageFromUri`). Routed all unbounded
  embedded-asset decodes through it: DrawingActivity snapshot (capped to view size; view-dim read
  reordered before decode) + template (`loadPageTemplateFromDb`), `NotebookExporter` cover (capped
  to `MAX_DIMENSION=4096`, since the cover sizes the PDF page) + `loadTemplate` (capped to page
  dims). Also fixed a duplicate template path the scan missed —
  `DrawingActivity.loadTemplateBitmapById` (`:4396`). Left the self-captured snapshot decode in
  `openCoverDialog` (`:1684`) untouched: it decodes a PNG the app just captured from its own view,
  so it's already view-sized and not untrusted file input. Builds clean (`assembleDebug`).

### M-2 · Raw read-write DB helpers leave WAL/journal artifacts & aren't transactional
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `data/PageCopier.kt:89-311` (`copyPageAfterRaw`, `movePageAfterRaw`, `deletePageRaw`)
- **Problem:** These open `.soil` `OPEN_READWRITE`, write, then `db.close()` without
  `wal_checkpoint(TRUNCATE)` / `incremental_vacuum` and without deleting `-journal` — leaving
  `-wal`/`-shm`/`-journal` behind (violates the "folder shows only `.soil`" rule).
  `deletePageRaw` (`295-303`) also does three `db.update()` calls **outside any transaction** →
  half-deleted page on failure.
- **Fix approach:** Wrap `deletePageRaw` in `beginTransaction()`/`setTransactionSuccessful()`;
  run the same close-time PRAGMAs as the Room path; clean stray journal files on close.
- **Verification:** After page copy/move/delete from `PageIndexActivity`, the folder shows only
  the `.soil` file (no `-wal`/`-shm`/`-journal`).
- **Notes/commit:** Added shared `checkpointAndVacuum()` (runs `PRAGMA incremental_vacuum` +
  `wal_checkpoint(TRUNCATE)` via cursor) + `cleanStrayJournal()` helpers in `PageCopier.kt`,
  mirroring the Room close path; all three raw helpers now checkpoint before `db.close()` and
  delete the stray `-journal`. `-wal`/`-shm` are intentionally NOT deleted — DrawingActivity
  keeps its own Room connection open to the same file during these calls, so SQLite removes
  those sidecars when that last connection closes via `closeNotebook()`. Wrapped
  `deletePageRaw`'s three soft-delete `update()` calls in `beginTransaction()` /
  `setTransactionSuccessful()` / `endTransaction()`.

### M-3 · `runBlocking` on the main thread in the close path (incl. `onDestroy`)
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `DrawingActivity.kt:1197-1230` (`closeNotebook`), called from `onDestroy()` `921-927`
- **Problem:** `saveStrokes` + `persistSnapshot` + `incremental_vacuum` + `wal_checkpoint` run
  synchronously via `runBlocking {}` on the UI thread. Cost scales with stroke count + snapshot
  size → ANR risk on large notebooks; also fragile when invoked from `onDestroy()` on process kill.
- **Fix approach:** Move heavy save off the main thread (deferred-`finish()` write / bounded
  foreground task) while keeping the file seal guarantee; keep only the lightweight close on UI.
- **Verification:** Close a large notebook (many strokes) → no ANR/jank; reopening shows all
  strokes and a clean file.
- **Notes/commit:** Split `closeNotebook()` into a lightweight main-thread head (snapshot capture +
  history clear) and a `suspend sealNotebook()` (`withContext(Dispatchers.IO)`: persist snapshot,
  `saveStrokes`, `incremental_vacuum` + `wal_checkpoint(TRUNCATE)`, `db.close()`, `-journal` delete).
  Added `blocking` param: user-initiated close (btnClose/back) launches the seal on a new
  application-scoped `NoteSproutApplication.appScope` (`SupervisorJob + Dispatchers.IO`, never
  cancelled) and `finish()`es immediately — no UI-thread block, and the seal outlives the activity
  (unlike `lifecycleScope`, which `onDestroy` would cancel). The `onDestroy` safety net calls
  `closeNotebook(blocking = true)` → `runBlocking` (only fires on abnormal teardown; the normal path
  already nulled `soilDatabase`, so it no-ops). Reading strokes off-thread is safe: `getStrokes()`
  returns a copy and `releaseResources()` never mutates the stroke list. Builds clean
  (`assembleDebug`).

### M-4 · Verbose logging unconditional in release builds
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `drawing/OnyxDrawingView.kt:47-49` (`epd()`), 30+ `Log.d` in `DrawingActivity.kt`
  (e.g. `1282`, `1351`, `1582`); `build.gradle.kts:50-53` (`isMinifyEnabled = false`)
- **Problem:** No `Log` is `BuildConfig.DEBUG`-guarded and R8 won't strip them. `epd()` fires on
  the per-stroke render path (e-ink hot path). Page UUIDs / layer IDs / sizes leak to logcat.
- **Fix approach:** Guard with `BuildConfig.DEBUG`, or add a ProGuard `assumenosideeffects` rule
  to strip `Log` in release; silence the per-stroke `epd()` line specifically.
- **Verification:** Release build produces no NoteSprout `Log.d` output during draw/navigate.
- **Notes/commit:** Enabled `buildConfig = true` (R8 stripping is unavailable — `isMinifyEnabled`
  is false — so `BuildConfig.DEBUG` is the actual gate). Added `core/Slog.kt`: an `object` with a
  single `inline fun d(tag) { msg() }` that no-ops in release; the lambda + `inline` means the
  message string is never built on the e-ink hot path. Converted all 32 `Log.d` call sites across
  `OnyxDrawingView`, `DrawingActivity`, `TemplateDialog`, `MlKitHandwritingRecognizer` to
  `Slog.d(tag) { … }`. `epd()` is now `private inline fun epd(msg: () -> String)` delegating to
  `Slog.d` (104 call sites converted to `epd { … }`). `Log.e`/`Log.w` (37 sites) deliberately
  kept — errors/warnings must survive into release (raw-DB logging rule). Both `assembleDebug`
  (DEBUG=true) and `assembleRelease` (DEBUG=false) build clean.

### M-5 · `mmkv` not excluded from `onyxsdk-pen` (16 KB page-size / Play compliance)
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `build.gradle.kts:62` (`onyxsdk-pen:1.5.4`)
- **Problem:** No `exclude` for transitive `mmkv` and no jniLibs handling for it beyond
  `libc++_shared.so`. An old non-16KB-aligned mmkv `.so` would block release uploads under newer
  Play 16 KB page-size requirements. (No network here to inspect the transitive tree — confirm
  manually.)
- **Fix approach:** Add the documented `exclude(group=..., module="mmkv...")` on `onyxsdk-pen`,
  or confirm 1.5.4 no longer bundles mmkv.
- **Verification:** `./gradlew :app:dependencies` shows mmkv excluded/absent; release APK aligns.
- **Notes/commit:** Manually confirmed the transitive tree (`releaseRuntimeClasspath`):
  `onyxsdk-pen:1.5.4 → onyxsdk-base:1.8.5 → com.tencent:mmkv:1.0.19`. Two findings flipped the
  premise:
  1. **mmkv's arm64-v8a `libmmkv.so` is 64 KB-aligned** (ELF LOAD `p_align = 0x10000` ≥ 0x4000),
     i.e. already 16 KB-compliant. Only its *x86_64* variant is 4 KB-aligned. So mmkv is **not** an
     arm64/Play blocker.
  2. **Excluding mmkv is unsafe** — `onyxsdk-base` references it directly (`MMKVBuilder`,
     `DefaultSearchHistory`, `BaseSearchHistoryHelper`); dropping it risks a runtime
     `NoClassDefFoundError`. So no `exclude` was added.
  Action taken instead: pinned `defaultConfig.ndk { abiFilters += "arm64-v8a" }`. Every Tier-1/2
  device is 64-bit ARM, so this drops the unused x86/x86_64/armeabi(-v7a) ABIs — including the only
  4 KB-aligned native lib among them (mmkv x86_64) — and shrinks the APK. Verified the rebuilt
  release APK ships **only** `lib/arm64-v8a/`, and every arm64 lib except one is ≥16 KB-aligned
  (`libmmkv.so` 0x10000; `libc++_shared.so`/`libonyx_pen_touch_reader.so`/`libneopen_jni.so`
  0x4000). The lone remaining arm64 16 KB offender is **`libdigitalink.so` from ML Kit** (not
  mmkv/Onyx) — tracked as **L-10**.

### M-6 · `MANAGE_EXTERNAL_STORAGE` is broad and Play-restricted
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `AndroidManifest.xml:7`
- **Problem:** All-files access is the widest storage permission, needs special Play
  justification, and broadens the blast radius of C-1/C-2. App only needs
  `/Documents/NoteSprout/`.
- **Fix approach:** Scope to MediaStore/SAF where feasible, or document the Play justification.
  (May stay as-is for sideloaded BOOX dev; revisit before distribution.)
- **Verification:** App functions with the narrower permission, or a written justification exists.
- **Notes/commit:** Architectural migration to scoped storage — notebooks moved to
  `getExternalFilesDir(null)`, templates to `getExternalFilesDir("Templates")` with
  user-initiated import via `ACTION_OPEN_DOCUMENT`. `MANAGE_EXTERNAL_STORAGE`,
  `WRITE_EXTERNAL_STORAGE`, and `requestLegacyExternalStorage` all removed from the
  manifest. Zero storage permissions required. Commit `15e7980`.

### M-7 · Silently swallowed exceptions hide corruption
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `data/PageCopier.kt:205,264,306`; `MainActivity.kt:825`;
  `data/CoverLoader.kt:56,76,81`
- **Problem:** `catch (_: Exception) { null }` over raw DB ops turns open/transaction/parse
  failures into silent no-ops returning `null` — failed page copy/move/delete is
  indistinguishable from success.
- **Fix approach:** At minimum `Log.e` the exception; surface a user-visible failure for write ops.
- **Verification:** Force a write failure (e.g. read-only file) → error is logged and the user is
  informed instead of a silent no-op.
- **Notes/commit:** Every `catch (_: Exception)` over a raw DB op now `Log.e`s the throwable —
  `PageCopier.kt` (all 3 raw helpers), `CoverLoader.kt` (cover-decode, snapshot-decode, outer),
  and `MainActivity.loadLastPageSnapshot`. Added user-visible failure for the write ops:
  `PageIndexActivity` now Toasts "Couldn't move/paste/delete page" when the corresponding raw
  helper returns `null` instead of silently no-oping.

### M-8 · Read-only cover/snapshot loaders regenerate & strand `-wal`/`-shm`
- **Status:** ☑ Done
- **Severity:** 🟡 Moderate
- **Files:** `data/CoverLoader.kt` (`loadNotebookCoverBitmap`); `MainActivity.kt`
  (`loadLastPageSnapshot`)
- **Problem:** Both open each `.soil` `OPEN_READONLY` to render the notebook-list thumbnails.
  A read-only WAL connection **re-creates `-shm` on open** and **cannot unlink `-wal`/`-shm` on
  close** (deletion needs write permission). So every return to the notebook list regenerates the
  sidecars and can never clean them — violating the "folder shows only `.soil`" rule and masking
  M-2's verification. Fingerprint on-device: all `-shm` files share the timestamp of the last
  list render, with 0-byte `-wal` shells beside them. This is a separate path from M-2 (which
  fixed the read-*write* page helpers).
- **Fix approach:** Open these read paths `OPEN_READWRITE`; on close, `wal_checkpoint(TRUNCATE)`
  then close as the last connection so SQLite removes both sidecars. Best-effort — if a notebook
  is open concurrently the TRUNCATE is skipped (no corruption).
- **Verification:** Open the notebook list (covers render), then check `/Documents/NoteSprout/` —
  no `-wal`/`-shm` left beside closed notebooks. Pre-existing stranded sidecars are cleaned the
  first time each notebook's cover is rendered by the fixed loader.
- **Notes/commit:** Added shared `internal SQLiteDatabase.checkpointTruncateAndClose(tag, file)`
  in `CoverLoader.kt`; both loaders now open `OPEN_READWRITE` and route close through it.
  `loadLastPageSnapshot` restructured from `db.use {}` to `try/finally` so the checkpoint runs
  before close. The helper also deletes the 0-byte `-journal` shell the read-write open leaves
  (caught during NA5C device testing — checkpoint cleared `-wal`/`-shm` but stranded `-journal`).
  Verified on NA5C: after a full cover scan the folder shows only `.soil` files + `Templates/`.

---

## 🟢 Low / Informational

Lightweight items; address opportunistically.

- **L-1 · `allowBackup="true"` no rules** — `AndroidManifest.xml:12`. Undo-persistence files +
  cached PDFs are backup-eligible. Add `dataExtractionRules`/`fullBackupContent` or set false.
- **L-2 · FileProvider scope is whole cache dir** — `res/xml/file_paths.xml:3` exposes
  `cache-path "."`. Not directly exploitable (per-URI grant, `exported=false`), but tighten to an
  `exported_pdfs/` subdir.
- **L-3 · Exported PDFs accumulate in cacheDir** — `NotebookExporter.kt:123-124`. Never cleaned;
  consider deleting old exports.
- **L-4 · ML Kit model downloads over any network** — `MlKitHandwritingRecognizer.kt:48-51`,
  default `DownloadConditions`. Consider `requireWifi()`.
- **L-5 · Recognizer never closed** — `MlKitHandwritingRecognizer.close()` exists (`:117`) but
  `HandwritingRecognizerProvider` has no shutdown hook.
- **L-6 · Clipboard retains content process-wide** — `NoteSproutClipboard.kt`. No `Context` held
  (no leak), just retention until overwritten; consider clearing on notebook close.
- **L-7 · `http://` BOOX Maven repo** — `settings.gradle.kts:14-17`. Build-time only, documented
  as required. Accepted; noted for completeness.
- **L-8 · Snapshot staleness ignores headings** — `data/NotebookDao.kt:260`
  (`getMaxStrokeUpdatedAt` filters `type='stroke'`). Masked today because DrawingActivity
  re-snapshots on page-leave; latent if a future path mutates headings without re-snapshotting.
  Add a comment / guard.
- **L-9 · Compaction never runs** — `data/SoilDatabase.kt:45-48` `TODO(compaction)` unimplemented;
  soft-deleted rows + per-page base64 snapshots make `.soil` grow monotonically. Expected by
  design; long-term size concern.
- **L-10 · ML Kit `libdigitalink.so` is not 16 KB-aligned** — `build.gradle.kts:88`
  (`com.google.mlkit:digital-ink-recognition:18.1.0`). Its arm64-v8a `.so` has ELF LOAD
  `p_align = 0x1000` (4 KB), the only remaining 16 KB-noncompliant native lib after M-5. Not a
  problem today — the app is sideloaded onto 4 KB-page BOOX devices, not on Play (see M-6) — but it
  would block a future Play upload targeting Android 15+. Fix when distributing: bump ML Kit to a
  16 KB-aligned release if one exists, else gate the upload on it. Surfaced during M-5.

---

*Source: full scan report, 2026-06-07. Last updated: 2026-06-07 (M-5 done — ABI filtered to
arm64-v8a; mmkv confirmed 16 KB-compliant & kept; ML Kit `libdigitalink.so` logged as L-10).*
