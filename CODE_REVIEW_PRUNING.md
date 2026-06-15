# Notesprout — Code Review Pruning List

> Generated from a full-codebase review of `apps/notesprout_android` (2026-06-15).
> **Usage:** Say "Let's prune C1" (or M3, L2, …) and Claude will open this file, read the
> matching item, and resolve it. Each item carries enough context (files, line numbers,
> root cause, suggested fix) to start work immediately.
>
> IDs are stable. When an item is resolved, mark it `✅ DONE` rather than deleting it, so the
> numbering never shifts. Severity: **C**ritical (fix ASAP) · **M**oderate (should fix) ·
> **L**ow (nice to have).

---

## 🔴 Critical

### C1 — Path-traversal in template import (data-corruption vulnerability) ✅ DONE
- **Resolution:** Added `sanitizeTemplateFileName()` in `NotebookActivity` — strips directory
  components, applies the `[^a-zA-Z0-9_\-. ]` whitelist, rejects `.`/`..`, and forces a `.png`
  extension. `performTemplateImport()` now routes `DISPLAY_NAME` through it before building the
  destination `File`, so an imported template can only ever land inside `getExternalFilesDir("Templates")`.
- **Where:** `NotebookActivity.performTemplateImport()` → `copyUriToFile()`
  ([NotebookActivity.kt:6601-6637](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L6601-L6637)).
- **Root cause:** The destination filename comes straight from the picked document's
  `OpenableColumns.DISPLAY_NAME` and is used unsanitized:
  `val destFile = File(destDir, displayName)`. `ACTION_OPEN_DOCUMENT` allows *any* document
  provider (incl. a malicious/buggy one) to return a display name like `../notesprout.db`
  or `../Garden/<uuid>.soil`.
- **Impact:** `getExternalFilesDir("Templates")` is `…/files/Templates/`. A `../` name escapes
  to `…/files/`, where the **global index `notesprout.db`** and the **`Garden/` notebook store**
  live. An imported "template" could silently overwrite the index DB or a `.soil` notebook →
  permanent data loss / corruption.
- **Fix:** Sanitize before building the path — reuse the project's existing whitelist
  `name.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_")`, strip any path separators, reject `.`/`..`,
  and force a `.png` extension. Mirror the validation already in
  `MainActivity.validateNotebookName()`. Also consider validating the bytes are a decodable
  image before committing.
- **Effort:** Small (one helper + call-site change). No migration.

---

## 🟠 Moderate

### M1 — Stroke re-serialization discards color / width / pressure / tilt / timestamp ✅ DONE
- **Resolved:** `LiveStroke` now carries `color`, `strokeWidth`, and `srcPoints: List<StrokePoint>?`
  (the original captured samples). `LiveStroke.fromStrokeData(id, sd)` populates them on every load;
  `liveStroke.toStrokeData(fallbackTs)` re-serializes from the preserved data — x/y from (possibly
  translated) `points`, pressure/tilt/timestamp from `srcPoints` when index-aligned, fabricating only
  for freshly drawn strokes that never had a source. All six re-save sites now call `toStrokeData`;
  all load sites use `fromStrokeData`; move/copy/paste/convert/drag clone via `.copy(points = …)` so
  the preserved fields flow through. Display-only stroke reconstructions (heading/undo bitmap rebuilds)
  still drop colour — that surfaces as a render concern under M8, not a persistence loss.
- **Where:** Every stroke re-write hardcodes style and stamps a fresh `ts`:
  [NotebookActivity.kt:1033](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L1033),
  [:2096-2103](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L2096-L2103),
  [:2861](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L2861),
  [:3427](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L3427),
  [:5280](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L5280),
  [:5599](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L5599).
- **Root cause:** `LiveStroke` only carries `id` + `points` ([LiveStroke.kt](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/LiveStroke.kt)),
  and load always uses `StrokeData.fromJson(...).toPointFs()` (drops everything but x/y). On any
  re-save (lasso move/copy/cut/paste, conversions) the code reconstructs `StrokeData(color =
  "#000000", strokeWidth = 3.0f, points = …)` with `pressure = null, tilt = null, timestamp = now`.
- **Impact:** Today benign (no pen color/width UI yet, so everything is already black/3.0), **but**
  original capture timestamps are destroyed on every move *now*, and the moment per-pen color/width
  ships (see `reference_onyxsdk_pen_api` memory — it's planned), moving/copying a styled stroke will
  silently reset it to black 3.0. Latent data-loss; becomes Critical when styling lands.
- **Fix:** Carry `color`, `strokeWidth`, and the original `List<StrokePoint>` (pressure/tilt/ts) on
  `LiveStroke` (or keep an immutable `StrokeData` reference). Re-serialize from the preserved data
  instead of fabricating it. Note the renderer/exporter also hardcode black — see M8.
- **Effort:** Medium (touches the in-memory model + every save site + both views' render paths).

### M2 — `org.json` + string-interpolated JSON for boundingBox / page data (rule violation + fragility)
- **Where:** `org.json.JSONObject` import/use in
  [NotebookActivity.kt:1835,1945,2135,6547,6562](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L1835),
  [NotebookExporter.kt:177,188,209](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt#L175),
  [TocRepository.kt:52](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/toc/TocRepository.kt#L51),
  [TemplateDialog.kt:107,392,421,580,619](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/TemplateDialog.kt).
  Hand-built JSON via string templates:
  [NotebookActivity.kt:2094](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L2094),
  [MainActivity.kt:1107,1129,1135](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/MainActivity.kt#L1107).
- **Root cause:** There is no `@Serializable` class for the `boundingBox` shape
  (`{x,y,width,height}`) or for page `data` (`{width,height,template,snapshot}`), so these are
  parsed/produced ad hoc. Directly violates the CLAUDE.md rule *"kotlinx.serialization only — never
  use org.json."* `parseBoundingBox` is reimplemented **3×**
  ([NotebookActivity.kt:1943](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L1943),
  [NotebookExporter.kt:186](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt#L186),
  [TocRepository.kt:51](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/toc/TocRepository.kt#L51))
  plus `parseDimensions` once.
- **Impact:** Rule drift; fragile (string interpolation breaks on NaN/Infinity and is injection-prone
  if any field ever becomes user-controlled); 4 copies of the same parser to keep in sync.
- **Fix:** Add `@Serializable data class BoundingBox(x,y,width,height)` and `@Serializable PageData(...)`
  with `toJson()/fromJson()` + a single `RectF` adapter. Replace all org.json sites and remove the
  duplicate parsers. After this, `org.json` should appear nowhere outside comments.
- **Effort:** Medium (mechanical, but spread across 5 files).

### M3 — `NotebookActivity` is a 6,638-line god class; undo/redo is ~2,300 lines of repetition
- **Where:** [NotebookActivity.kt](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt).
  The undo/redo engine alone spans `executeAction` → `handleCrossPageAction` →
  `executeActionDb` → `handleSamePageAction` ([:4240-6545](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L4240)).
- **Root cause:** All concerns (lifecycle, drawing, lasso, headings, text, lines, snapshots, export,
  templates, paging, undo/redo) live in one Activity. The undo handlers repeat the same block ~15×:
  `LiveStroke(id = obj.id, points = StrokeData.fromJson(obj.data).toPointFs())`, load
  strokes/headings/texts/lines, `buildRenderBitmap` + `loadStrokesWithBitmap`, partition restored
  rows by type.
- **Impact:** Very hard to navigate/test/modify; high regression risk; the repeated deserialize is
  also where M1's data loss is cemented.
- **Fix:** Extract: (a) a `PageContent` loader (`deserializeStrokes/Headings/Texts/Lines` already exist
  — wrap them into one `loadPageContent(db, layerId)`), (b) a `rebuildAndSwap(strokes, headings, …)`
  helper, (c) a `partitionRowsByType(rows)` helper, (d) move the undo/redo execution into a dedicated
  `UndoRedoExecutor` collaborator. Target: shrink the file below ~2k lines.
- **Effort:** Large; do incrementally, one extraction at a time, verifying on a BOOX device between steps.

### M4 — Detection / geometry / render helpers duplicated verbatim across the two drawing engines
- **Where:** Near-identical implementations in
  [OnyxNotebookView.kt](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/notebook/OnyxNotebookView.kt)
  and [GenericNotebookView.kt](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/notebook/GenericNotebookView.kt):
  `isSmartLassoCandidate`, `isScribbleCandidate`, `scribbleHitTest`, `scribblePathPenetration`,
  `runLassoHitTest`, `pointToSegmentDistSq`, `pointToPointDistSq`, plus the render helpers
  `drawHeadingText`, `drawTextObject`, `drawLineObject`, `drawSnapGuides`, and `jitter`.
- **Root cause:** The two `View` subclasses share no base/util; each engine copied the same pure
  functions. (~250+ lines duplicated.)
- **Impact:** Any bug fix or tuning (e.g. a scribble-threshold change) must be done twice and can drift
  — exactly the class of bug in recent pruning commits.
- **Fix:** Move the pure-geometry/detection functions (input = `List<PointF>` + object lists, output =
  hit-id lists) into a stateless `GestureDetection` object in `notebook/`. Move the canvas render
  helpers into a shared `ObjectRenderer` (or a `BaseNotebookView` if the engines can share a superclass).
  Both views then call the shared code.
- **Effort:** Medium; pure functions are low-risk to extract; render helpers need careful paint/field wiring.

### M5 — Auto Backup misconfiguration: notebooks may be cloud-backed-up (or silently fail)
- **Where:** `AndroidManifest.xml` (`android:allowBackup="true"`) +
  [backup_rules.xml](apps/notesprout_android/app/src/main/res/xml/backup_rules.xml) /
  [backup_descriptor.xml](apps/notesprout_android/app/src/main/res/xml/backup_descriptor.xml).
- **Root cause:** The comment claims *".soil and template files live in getExternalFilesDir — excluded
  from backup by the OS."* That is **incorrect** — Android Auto Backup *includes* `getExternalFilesDir()`
  by default. The rules only exclude `domain="cache"`.
- **Impact:** (1) User notebooks (potentially private handwriting) get uploaded to Google cloud backup
  unmanaged; (2) Auto Backup has a ~25 MB total cap — a single large notebook silently disables backup
  for the whole app. Either is undesirable for a "calm, private paper" product.
- **Fix:** Decide the intended behavior. If notebooks should NOT be cloud-backed-up, add explicit
  `<exclude domain="external" path="." />` (and the `full-backup-content` equivalent), or set
  `allowBackup="false"`. If they *should* be, document it and acknowledge the size cap / consider a
  proper export-based backup. Fix the misleading comment either way.
- **Effort:** Small (config) + a product decision.

### M6 — `runBlocking` on the main thread in the `onDestroy` seal safety-net
- **Where:** [NotebookActivity.kt:1699-1700](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookActivity.kt#L1699)
  (`closeNotebook(blocking = true)` → `runBlocking { sealNotebook(...) }`), called from `onDestroy`.
- **Root cause:** Abnormal-teardown path blocks the UI thread to guarantee the file is sealed before
  the process dies. Directly contradicts the CLAUDE.md hard rule *"Never runBlocking on the UI thread."*
- **Impact:** On abnormal teardown with a large notebook, `saveStrokes` + `hardDeleteOldSoftDeleted` +
  vacuum/checkpoint run synchronously on main → ANR risk during teardown.
- **Fix:** This is a deliberate durability trade-off, but bound it: the normal close path already seals
  on `appScope` (off-thread) and nulls `soilDatabase`, so this only fires on true abnormal teardown.
  Consider (a) moving guaranteed seal earlier (already mostly done), (b) capping the blocking work, or
  (c) using `goAsync`-style mechanisms. At minimum, document the residual ANR risk explicitly.
- **Effort:** Small-to-medium; mostly a correctness/risk review.

### M7 — Duplicate / redundant DAO methods
- **Where:** [NotebookDao.kt](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/NotebookDao.kt):
  `softDeleteById` ([:105](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/NotebookDao.kt#L105))
  and `softDelete` ([:120](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/NotebookDao.kt#L120))
  have **identical** SQL; `getPagesSorted` ([:52](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/NotebookDao.kt#L52))
  and `getAllPages` ([:259](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/data/NotebookDao.kt#L259))
  are identical (the latter's own KDoc says so).
- **Impact:** Dead surface area; "legacy, prefer the other" comments invite drift.
- **Fix:** Pick one of each, migrate call sites, delete the other. (`softDelete` is marked legacy;
  `getAllPages` is the redundant alias.)
- **Effort:** Small.

### M8 — `NotebookExporter` duplicates page-content loading and hardcodes stroke style
- **Where:** [NotebookExporter.kt](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt):
  the headings/text/line/stroke loading blocks in `export()` ([:100-139](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt#L100))
  and `exportPage()` ([:365-398](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt#L365))
  are ~identical (~40 lines each). `renderPage` paints all strokes with a fixed
  `strokeWidth = 2.5f` / `Color.BLACK` ([:241-247,318-325](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/NotebookExporter.kt#L241)).
- **Impact:** Two copies to maintain; export ignores per-stroke color/width (tied to M1 — and note the
  on-screen views also hardcode black, so exports already match the screen *today*).
- **Fix:** Extract `loadPageContent(dao, layerId, density)` returning a small holder, used by both
  export paths (and reusable by M3's loader). When M1 lands, read color/width from `StrokeData`.
- **Effort:** Small-to-medium.

---

## 🟡 Low

### L1 — Unused drawable `ic_page_add`
- **Where:** [res/drawable/ic_page_add.xml](apps/notesprout_android/app/src/main/res/drawable/ic_page_add.xml).
  Zero references in any `.kt` or layout (page add/delete now use `ic_insert_page_after/before` and
  `ic_page_delete`). Delete it.

### L2 — Source tree split between `java/` and `kotlin/`
- **Where:** Most code is under `app/src/main/kotlin/`, but `sort/` (7 files), `state/AppStateManager.kt`,
  and `notebook/SnapPreferences.kt` live under `app/src/main/java/`. Same packages, different root dirs.
- **Fix:** Move the stragglers under `kotlin/` for consistency (no package change needed).

### L3 — Eight separate `Json { ignoreUnknownKeys = true }` instances
- **Where:** `MainActivity.kt:82`, `CoverDialog.kt:488`, `PageIndexActivity.kt:86`,
  `UndoRedoManager.kt:36`, `NotebookMetadata.kt:47`, `CoverLoader.kt:129-130` (×2), `StrokeData.kt:51`.
- **Fix:** Consolidate to a single shared `NotesproutJson` codec (one `explicitNulls=false,
  ignoreUnknownKeys=true` instance) in `core/`. Minor allocation + consistency win.

### L4 — `MarkdownParser.appendChar` builds text O(n²)
- **Where:** [MarkdownParser.kt:257-264](apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/core/markdown/MarkdownParser.kt#L257).
  Each plain character does `Inline.Text(last.text + c)` (full string copy per char). Quadratic for long
  text objects.
- **Fix:** Accumulate runs of plain text in a `StringBuilder` and emit one `Inline.Text` at a
  format-boundary. Parser is otherwise clean (no ReDoS — patterns are anchored and simple).

### L5 — Release build has `isMinifyEnabled = false`
- **Where:** [build.gradle.kts:64](apps/notesprout_android/app/build.gradle.kts#L64).
- **Impact:** No R8 shrinking/optimization/obfuscation → larger APK; `Log.e/Log.w` strings (some with
  ids/paths) ship in clear. The Slog DEBUG guard handles verbose logs, but not these.
- **Fix:** Evaluate enabling R8 for release with proper keep rules for Room/kotlinx.serialization/Onyx
  SDK. Test thoroughly on a BOOX device (the SDK uses reflection/hidden APIs).

### L6 — `versionCode`/`versionName` still `1` / `"1.0"`
- **Where:** [build.gradle.kts:16-17](apps/notesprout_android/app/build.gradle.kts#L16). Bump per release
  so installs upgrade correctly and Growth Logs map to versions.

### L7 — Stale `TODO`s
- `NotebookActivity.kt:537` "implement toolbar show/hide UX"; `:2370` "apply template to all pages"
  (the "All pages" affordance silently no-ops?); `IndexRepository.kt:133` pinned-list only checks the
  single known list. Triage: implement, ticket, or remove.

### L8 — Missing `contentDescription` on image-only buttons (a11y)
- Only 4 of 10 layouts set `contentDescription`. E-ink-first, low priority, but cheap to add for the
  toolbar/action buttons (helps TalkBack and lint).

### L9 — ML Kit model downloads on any network
- **Where:** `MlKitHandwritingRecognizer.initModel()` (`DownloadConditions.Builder()` with no
  Wi-Fi constraint). Already a documented TODO in CLAUDE.md. Add a user setting (Wi-Fi-only vs any)
  before broad release — a ~20-30 MB download on cellular is surprising.

---

## Notes / Non-findings (verified OK)
- Stroke save path correctly batches in `withTransaction` and skips already-persisted ids — good.
- Data-model objects (`HeadingObject`, `TextObject`, `LineObject`, `NotebookMetadata`, `StrokeData`)
  correctly use kotlinx.serialization (the org.json drift in M2 is confined to boundingBox/page data).
- No empty `catch {}` swallows and no `printStackTrace()` found; raw-DB ops log via `Log.e`.
- FileProvider export paths (`exported_pdfs/`, `exported_pngs/`) match `file_paths.xml`; share intents
  include the required `ClipData` for Android 12+.
- `!!` usage is low and mostly on framework-guaranteed views.
