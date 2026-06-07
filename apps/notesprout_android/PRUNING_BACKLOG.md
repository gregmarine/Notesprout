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

**Progress:** 0 / 9 tracked (C+M) · Low items tracked separately at the bottom.

---

## 🔴 Critical

### C-1 · Creating a notebook with an existing name corrupts the existing notebook
- **Status:** ☐ Open
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
- **Notes/commit:** —

### C-2 · Notebook name not sanitized → path traversal on write
- **Status:** ☐ Open
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
- **Notes/commit:** —

---

## 🟡 Moderate

### M-1 · Unbounded base64 image decode → OOM / DoS via crafted or large `.soil`
- **Status:** ☐ Open
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
- **Notes/commit:** —

### M-2 · Raw read-write DB helpers leave WAL/journal artifacts & aren't transactional
- **Status:** ☐ Open
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
- **Notes/commit:** —

### M-3 · `runBlocking` on the main thread in the close path (incl. `onDestroy`)
- **Status:** ☐ Open
- **Severity:** 🟡 Moderate
- **Files:** `DrawingActivity.kt:1197-1230` (`closeNotebook`), called from `onDestroy()` `921-927`
- **Problem:** `saveStrokes` + `persistSnapshot` + `incremental_vacuum` + `wal_checkpoint` run
  synchronously via `runBlocking {}` on the UI thread. Cost scales with stroke count + snapshot
  size → ANR risk on large notebooks; also fragile when invoked from `onDestroy()` on process kill.
- **Fix approach:** Move heavy save off the main thread (deferred-`finish()` write / bounded
  foreground task) while keeping the file seal guarantee; keep only the lightweight close on UI.
- **Verification:** Close a large notebook (many strokes) → no ANR/jank; reopening shows all
  strokes and a clean file.
- **Notes/commit:** —

### M-4 · Verbose logging unconditional in release builds
- **Status:** ☐ Open
- **Severity:** 🟡 Moderate
- **Files:** `drawing/OnyxDrawingView.kt:47-49` (`epd()`), 30+ `Log.d` in `DrawingActivity.kt`
  (e.g. `1282`, `1351`, `1582`); `build.gradle.kts:50-53` (`isMinifyEnabled = false`)
- **Problem:** No `Log` is `BuildConfig.DEBUG`-guarded and R8 won't strip them. `epd()` fires on
  the per-stroke render path (e-ink hot path). Page UUIDs / layer IDs / sizes leak to logcat.
- **Fix approach:** Guard with `BuildConfig.DEBUG`, or add a ProGuard `assumenosideeffects` rule
  to strip `Log` in release; silence the per-stroke `epd()` line specifically.
- **Verification:** Release build produces no NoteSprout `Log.d` output during draw/navigate.
- **Notes/commit:** —

### M-5 · `mmkv` not excluded from `onyxsdk-pen` (16 KB page-size / Play compliance)
- **Status:** ☐ Open
- **Severity:** 🟡 Moderate
- **Files:** `build.gradle.kts:62` (`onyxsdk-pen:1.5.4`)
- **Problem:** No `exclude` for transitive `mmkv` and no jniLibs handling for it beyond
  `libc++_shared.so`. An old non-16KB-aligned mmkv `.so` would block release uploads under newer
  Play 16 KB page-size requirements. (No network here to inspect the transitive tree — confirm
  manually.)
- **Fix approach:** Add the documented `exclude(group=..., module="mmkv...")` on `onyxsdk-pen`,
  or confirm 1.5.4 no longer bundles mmkv.
- **Verification:** `./gradlew :app:dependencies` shows mmkv excluded/absent; release APK aligns.
- **Notes/commit:** —

### M-6 · `MANAGE_EXTERNAL_STORAGE` is broad and Play-restricted
- **Status:** ☐ Open
- **Severity:** 🟡 Moderate
- **Files:** `AndroidManifest.xml:7`
- **Problem:** All-files access is the widest storage permission, needs special Play
  justification, and broadens the blast radius of C-1/C-2. App only needs
  `/Documents/NoteSprout/`.
- **Fix approach:** Scope to MediaStore/SAF where feasible, or document the Play justification.
  (May stay as-is for sideloaded BOOX dev; revisit before distribution.)
- **Verification:** App functions with the narrower permission, or a written justification exists.
- **Notes/commit:** —

### M-7 · Silently swallowed exceptions hide corruption
- **Status:** ☐ Open
- **Severity:** 🟡 Moderate
- **Files:** `data/PageCopier.kt:205,264,306`; `MainActivity.kt:825`;
  `data/CoverLoader.kt:56,76,81`
- **Problem:** `catch (_: Exception) { null }` over raw DB ops turns open/transaction/parse
  failures into silent no-ops returning `null` — failed page copy/move/delete is
  indistinguishable from success.
- **Fix approach:** At minimum `Log.e` the exception; surface a user-visible failure for write ops.
- **Verification:** Force a write failure (e.g. read-only file) → error is logged and the user is
  informed instead of a silent no-op.
- **Notes/commit:** —

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

---

*Source: full scan report, 2026-06-07. Last updated: 2026-06-07 (backlog created).*
