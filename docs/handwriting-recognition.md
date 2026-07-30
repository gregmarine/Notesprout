# Handwriting Recognition & Page-Text

Handwriting-to-text recognition in Notesprout. This doc covers **what ships today** (a single-shot
ML Kit recognizer used for heading / text-box conversion) and the **proposed design** for extending
it to whole-page and whole-notebook text: a segmentation layer, a persisted per-page text cache, a
real-time background recognition (RTR) mode, and an export-time recognition path.

> **Second engine (branch `hwr-trocr`, 2026-07-13):** a personalizable TrOCR-based engine now sits
> behind the same `HandwritingRecognizer` interface — see § "TrOCR engine" at the end of this doc.

> **Status.** The single-shot recognizer (§ Current State) is **shipped**. The shared core
> (`StrokeSegmenter`, context-aware `recognizeSegment`, `PageTextRecognizer`, the `page_text`
> object), **Path 2 export** (Markdown default + text-only via `MarkdownText`; whole-notebook from
> MainActivity + NotebookActivity), **Path 1 RTR** (`RtrScheduler`, `rtrEnabled` on
> `NotebookMetadata`, idle/seal-debounced + backfill-on-enable), and the **read-only viewer**
> (`PageTextViewerActivity`) are **shipped on `sprout` (2026-07-03)**. As-built notes:
> - **Headings** come only from existing `heading` objects (their `level`); no size inference.
> - **Horizontal rules** come from roughly-horizontal `ShapeType.LINE` shapes → `---`. The Lines
>   tool (`type = "line"`) is template ruling and is **not** fed to recognition.
> - **Line detection uses a vertical projection profile** (coverage histogram over Y → dense
>   writing "bands" separated by whitespace; each stroke assigned to its band). This replaced an
>   initial sort-by-centerY greedy merge that interleaved strokes across adjacent lines
>   (descenders/ascenders) and fragmented clean sentences. A guarded post-pass merges a tiny
>   fragment (≤3 strokes) into a vertically-overlapping neighbor (stray trailing marks). Verified
>   on real journal handwriting (769 strokes → 19 lines / 5 paras, near-perfect transcription).
> - Segmenter constants: `BAND_COVERAGE_FRAC = 0.15`, `MERGE_OVERLAP_FRAC = 0.4`,
>   `FRAGMENT_MAX_STROKES = 3`, `PARA_GAP_FRAC = 0.9` (the "noticeable gap = new paragraph" rule).
> - **ML Kit tuning (per Google guidance, 2026-07-03):** `recognizeSegment` sets the `WritingArea`
>   height from the page's **consistent** median line height (`PageLayout.medianLineHeight`), not
>   each line's tight ink bbox — ML Kit interprets glyph size *relative to the writing area* (o vs O,
>   comma vs slash), so a line of only short letters no longer reports a too-small area and skews
>   toward tall/capital readings. `preContext` cap lowered 40 → 20 chars (Google's stated optimum;
>   more gives no benefit and adds latency). **Measured effect on real handwriting: negligible** —
>   kept because it's correct usage and free, but it does not close the gap with cloud vision models.
> - Remaining errors are ML Kit character-level misreads (same class as the single-shot convert
>   tool), not pipeline issues. `Slog.d` traces log stroke/line **counts only — never the
>   recognized text** (privacy rule).
> - **`.md` save bug fixed:** SAF `CreateDocument` mime must match the extension, so `.md` routes
>   through a `text/markdown` launcher and `.txt` through `text/plain` (a `text/plain` launcher on a
>   `.md` name made the picker append `.txt` → `notebook.md.txt`).
> - Viewer reads via `freshOrRecognizeReadOnly` (no writes) so it can open its own connection while
>   the notebook is still open.
> - **Export surfaces:** whole-notebook (MainActivity library + NotebookActivity Export sheet),
>   **selected-page / single-page** from `PageIndexActivity` (Text option in the single- and
>   multi-select export menus, reusing `exportFromPath` with the selected page ids), **and the
>   read-only viewer itself** (`PageTextViewerActivity` header **Export** button). The viewer export
>   is instant — it writes the **already-recognized in-memory Markdown** for whatever view is showing
>   (This Page vs Whole Notebook), no re-recognition; then Markdown/Plain format choice (Plain via
>   `MarkdownText.toPlainText`) → encrypted-source warning → Save (SAF, mime-per-extension) / Share.
> - **Viewer rendering:** `MarkdownRenderer.render` takes an opt-in `blockGapPx` (a sized blank line
>   inserted between blocks). On-page text objects (`TextObjectRenderer`) pass the default `0` and
>   stay compact; the viewer passes a positive gap plus a larger body size (18sp) and 1.15× line
>   spacing so paragraphs/headings read like a document.
> - **Plain-text (`.txt`) is a faithful *structural* flatten, not a line-for-line copy** of the
>   Markdown. Because `MarkdownText.toPlainText` parses to blocks first, a run of lines separated by
>   **single** newlines is one paragraph → joined with spaces; only **blank-line**-separated lines
>   stay on separate lines (same rule as CommonMark, and as the viewer preview). Markdown export keeps
>   the raw breaks. `MarkdownTextReproTest` pins both this and full multi-block survival (a heading +
>   paragraphs + a Markdown text object with list/blockquote must never collapse to the first line).
>   (Note: BOOX **NeoReader** may *paginate* a long `.txt` — that is the reader, not a truncated file.)
>
> **Not yet built (deferred):** "copy page as text" to clipboard; multi-column, editable text,
> search index, Onyx HWR. Remaining unbuilt design below is future work.

---

## Current State (shipped)

### Engine — ML Kit Digital Ink Recognition

`com.google.mlkit:digital-ink-recognition:19.0.0` (see `app/build.gradle.kts`). It is a **stroke-based**
recognizer (it consumes the pen trajectory, not a rendered image), which makes it accurate at the
word / short-line level — often better than image OCR on cursive.

- **Fully on-device after a one-time model download.** `MlKitHandwritingRecognizer.initModel()` uses
  `RemoteModelManager` to fetch the `en-US` model once (~20 MB), checks `isModelDownloaded`, and from
  then on every `recognize(...)` call runs locally with **no network**. No ink ever leaves the device
  — consistent with the offline / encrypted-notebook philosophy.
- **Caveats:** the first-run download needs connectivity (once per language); today only `en-US` is
  loaded. On a never-connected device `isReady()` stays false and callers get `FALLBACK_TEXT`.

### Interface & wiring

| File | Role |
|---|---|
| `recognition/HandwritingRecognizer.kt` | Interface. `isReady()`, `recognize(strokes, bounds, onResult)`, `FALLBACK_TEXT = "unrecognized"`. Context-agnostic — operates on raw `LiveStroke`s. |
| `recognition/MlKitHandwritingRecognizer.kt` | ML Kit implementation. Builds one `Ink`, one `WritingArea`, takes `result.candidates.firstOrNull()`. |
| `recognition/HandwritingRecognizerProvider.kt` | App-level singleton holding the active recognizer; initialized in `NotesproutApplication`. |

The interface comment already anticipates a `OnyxHwrHandwritingRecognizer` (BOOX firmware AIDL bridge)
as a future higher-quality implementation on Tier-1 devices. The design below is engine-agnostic and
preserves that path.

### Where it's used today

Only **small, single-selection** conversions, all in `NotebookActivity`:

- **Heading conversion** — a lasso'd stroke selection → heading text (`recognizedText`), `singleLine = true`.
- **Text-object conversion** — a selection → a `text` object carrying markdown.

Both feed **all** selected strokes as one `WritingArea` and keep the top candidate. This is correct
for a phrase or a line; it does **not** scale to a page (see next).

---

## The Gap — why full-page needs a segmentation layer

ML Kit Digital Ink has **no layout analysis**. It is tuned for short inputs and returns a single best
transcription for whatever ink you hand it. Dump a whole page of strokes into one `Ink` and you get:

- a **run-on blob** — no line breaks, paragraphs, indents, bullets, or reading order;
- **degraded accuracy** — the model was not trained on page-scale input;
- **one guess** for the entire page rather than per-line results.

The fix is to do the layout work ourselves **before** calling ML Kit: group strokes into lines and
paragraphs spatially, recognize each line as its own segment (chaining context between lines), then
reassemble. ML Kit's per-line accuracy is strong, so this yields good full-page text without a new
recognition engine. The same segmentation core serves every path below.

---

## Goals & Non-Goals

**Goals**
- A reusable core that turns a page's `LiveStroke`s into reading-order text.
- A persisted, per-page text cache stored inside the `.soil` (travels on export/import, encrypted at rest).
- Two entry points sharing that core: background **RTR** and **export-time** recognition.
- Engine-agnostic: ML Kit today, Onyx HWR later, without touching callers.

**Non-Goals (v1)**
- Editing recognized text and reconciling edits back onto the ink (hard; deferred — see § Deferred).
- Multi-column / table layout reconstruction (single-column v1; the hook is left in place).
- Full-text search over recognized content (a natural *future consumer* of the cache, not built here).
- Any cloud recognition (rejected on privacy grounds).

---

## Architecture — the shared core

Three new pieces sit **on top of** the existing `HandwritingRecognizer`. Both the RTR and export paths
call the same core; they differ only in *scheduling*.

### 1. `StrokeSegmenter` — pure geometry (`recognition/StrokeSegmenter.kt`)

No ML or Android-UI dependencies beyond `RectF`/`PointF`; unit-testable in isolation. It converts a
flat `List<LiveStroke>` into reading-order lines/paragraphs using the AABB already precomputed on
every `LiveStroke` (`LiveStroke.boundingBox`).

```kotlin
data class Segment(val strokes: List<LiveStroke>, val bounds: RectF)   // one text line
data class Paragraph(val lines: List<Segment>)
data class PageLayout(val paragraphs: List<Paragraph>)                 // top → bottom

object StrokeSegmenter {
    fun segment(strokes: List<LiveStroke>): PageLayout {
        // 1. Reference metric: median stroke height → thresholds scale to hand size.
        // 2. Line grouping: sort by boundingBox.centerY(); greedily merge strokes whose
        //    vertical bands overlap / centers fall within lineH * SAME_LINE_FRAC.
        //    (Absorbs ascenders/descenders, dotted i/j, slight baseline drift.)
        // 3. Intra-line: sort left→right by box.left; flag gaps > lineH * WORD_GAP_FRAC
        //    as word/space boundaries (also the seed for future column detection).
        // 4. Paragraph break: inter-line vertical gap > lineH * PARA_FRAC → blank line.
    }
}
```

All thresholds are **ratios of the median line height**, so the segmenter self-scales to large or
small handwriting. Single-column in v1; the horizontal-gap data from step 3 is the hook for
multi-column later.

**What is fed in:** only `stroke` rows from the page's one content layer. `shape` / `line` / `link`
are separate object types and are never fed. Converted headings / text-boxes are no longer `stroke`
rows — they already carry `recognizedText`, so the assembler **merges them in by vertical position**
rather than re-recognizing.

### 2. Interface change — context-aware, suspendable recognition

`MlKitHandwritingRecognizer` currently hardcodes `setPreContext("")`. Per-line context chaining is
the single biggest free accuracy win, so add a suspend, context-aware method to the interface (keep
the existing callback `recognize` for the single-shot heading / text path):

```kotlin
suspend fun recognizeSegment(strokes: List<LiveStroke>, bounds: RectF, preContext: String): String
```

Implemented by wrapping ML Kit's callback API in `suspendCancellableCoroutine` and passing
`preContext` into `RecognitionContext.setPreContext(...)` instead of `""`.

### 3. `PageTextRecognizer` — orchestrator (`recognition/PageTextRecognizer.kt`)

```kotlin
class PageTextRecognizer(private val hwr: HandwritingRecognizer) {
    suspend fun recognizePage(strokes: List<LiveStroke>): PageText {   // Dispatchers.IO
        val layout = StrokeSegmenter.segment(strokes)
        val sb = StringBuilder(); var pre = ""
        for (para in layout.paragraphs) {
            for (line in para.lines) {
                val t = hwr.recognizeSegment(line.strokes, line.bounds, preContext = pre)
                sb.append(t).append('\n'); pre = t          // feed line N into line N+1
            }
            sb.append('\n')                                  // paragraph = blank line
        }
        return PageText(text = sb.toString().trim(), engine = "mlkit", /* … */)
    }
}
```

---

## Storage — a `page_text` object (no schema migration)

Because `type` on the `notebook` table is a plain string discriminator (see
`data/NotebookObject.kt`), page text is added as a **new object type with zero schema migration** —
the same mechanism that lets headings / shapes coexist.

```kotlin
@Serializable
data class PageText(
    val text: String,                 // assembled, reading-order text (plain or markdown)
    val engine: String,               // "mlkit" | "onyx" — lets us upgrade text per-engine later
    val recognizedAt: Long,
    val sourceMaxUpdatedAt: Long,     // == getMaxContentUpdatedAt(layerId) at recognition time
    val schema: Int = 1,
)
// Persisted as NotebookObject(type = "page_text", parentId = pageId, data = toJson()); upsert; one per page.
// Add TYPE_PAGE_TEXT = "page_text" alongside the existing TYPE_* constants.
```

- **Staleness** reuses the existing snapshot-staleness mechanism verbatim: `NotebookDao`
  already exposes `getMaxContentUpdatedAt(layerId)` (which counts soft-deletes, since deleted rows
  carry `updatedAt = deletedAt`). If the layer's current max exceeds the stored `sourceMaxUpdatedAt`,
  the cached text is stale → re-recognize (RTR) or badge it "updating…" (viewer).
- **Never user-editable.** `page_text` is a *cache*: RTR, export, and the viewer's recognize-on-open all
  rewrite it whenever the page changes, so nothing the user authored can live here. The editable
  counterpart is the `document` row, whose only writer is the editor — see
  [`documents.md`](documents.md). The two are deliberately separate objects, and page text seeds a
  document exactly once.
- **Encryption for free.** The row lives inside the `.soil`; on an encrypted notebook it is
  SQLCipher-encrypted at rest exactly like `recognizedText` today. No plaintext leak, no new crypto code.
- **Portable for free.** It travels inside the `.soil` on export / import — no `NotebookPackager` changes.
- **RTR flag** (per-notebook, must travel with the file) goes in `NotebookMetadata` (`data/NotebookMetadata.kt`,
  the notebook row's `data` JSON, alongside `last_opened_page`): add `rtrEnabled: Boolean = false`
  (and optionally `rtrEngine: String`).

---

## Path 1 — RTR (real-time background recognition)

Maintains a fresh `page_text` for every page as the user writes, so the text view and any export are
instant.

- **Granularity: idle-debounced + on page-seal — *not* per-stroke.** Hook the completion of the
  existing `saveStrokes(db)` (`NotebookActivity` ~line 4413) and the erase paths, then enqueue a
  debounced (~2 s pen-inactivity) per-page job; also run once at page-seal (the natural boundary that
  already flushes strokes + snapshot). This aligns with the seal/snapshot lifecycle, keeps the surface
  calm, and respects weak e-ink CPUs. Per-stroke recognition would be jittery and wasteful.
- **Off the UI thread, conflated per page.** Schedule on `NotesproutApplication.appScope` (IO). Use a
  per-page conflated channel + a `saveMutex`-style lock (same pattern as `saveStrokes`) so only the
  latest job per page runs and two jobs never race. ML Kit's `recognize` is already async; segmentation
  is cheap geometry.
- **Job body:** load the page's strokes → `StrokeSegmenter` → per-line `recognizeSegment` (context
  chained) → assemble → upsert the `page_text` row with `sourceMaxUpdatedAt = getMaxContentUpdatedAt(layerId)`.
- **Crash safety:** because staleness is stored, a job that never ran (app killed) is detected and
  re-run on next open / next edit.

### Viewer (secondary screen)

A **read-only** text surface for the current page (and optionally the whole notebook, concatenated),
modeled on the existing `DayDetailActivity` "day window" multi-view pattern. Shows a "stale / updating…"
indicator when `sourceMaxUpdatedAt` is behind the layer's current max. Read-only in v1 — editing
recognized text and reconciling it back onto ink is a separate, hard problem (see § Deferred).

---

## Path 2 — export-only recognition (RTR off)

For a notebook where RTR was never on, recognition happens **at export time**, using the identical core.

- **Foreground with progress + cancel.** This is pages × lines × ML Kit and can be long; show a
  determinate progress dialog (unlike the small single-shot conversions today).
- **Flow:** iterate pages in `order` → `PageTextRecognizer.recognizePage` per page → stream into the
  export artifact (plain text / markdown). Optionally upsert `page_text` as it goes so a second export
  is instant and the notebook is effectively seeded for RTR.
- **Cache reuse unifies the two paths:** if a page already has a **fresh** `page_text` (RTR wrote it,
  not stale), skip recognition and use it. A partially-RTR notebook exports fast for done pages and
  only computes the missing / stale ones.

Wiring rides alongside `NotebookExporter` (which already does per-page `renderPage()` for PDF/PNG).

---

## Other paths (share the same core)

1. **On-demand single-page / "copy page as text."** Foreground, one page. Serves the RTR-off user who
   wants just one page without a full export. Also the basis for "copy page as text" to the clipboard.
2. **Backfill-on-enable.** Turning RTR on for an existing (or imported) notebook must recognize all
   existing pages once — this **is** Path 2 run in the background with progress, seeding `page_text`
   for every page.
3. **Full-text search (future consumer, not built here).** `page_text` is exactly what a notebook /
   global search wants. Store structured per-page text now so search can consume it later; the
   `engine` field additionally lets us re-run pages through a future Onyx HWR bridge and know which
   engine produced each stored result. (An opt-in, encrypted-at-rest index is already noted as future
   work in the Encryption Phase-3 backlog.)

---

## RTR lifecycle — convertible toggle, with a creation default

**Decision: a per-notebook toggle changeable at any time, with a creation-time default value.**

Rationale:
- Creation-time-only is wrong as the *sole* mechanism — users don't know at creation whether they'll
  want it, notebooks are long-lived, and **imported notebooks predate the choice** (they'd be locked
  out permanently). Convertibility is therefore mandatory.
- Once the toggle exists, "choose at creation" is simply *its initial value*, so the toggle subsumes
  both and is strictly more flexible.

Behavior:
- **Enable** = set `rtrEnabled = true` in `NotebookMetadata` **+ kick the backfill batch** (Other Path 2).
- **Disable** = stop scheduling background jobs; **keep** existing `page_text` (still valid as an
  export / search cache — it just goes stale). No destructive action either way.
- **Default: OFF** for new notebooks — honors the calm / meditative philosophy and weak-CPU Tier-1
  e-ink reality; RTR is opt-in for people who live in the text view. Optionally expose a global "new
  notebooks use real-time text" preference so power users get it by default.

---

## Threading, e-ink & correctness rules

- **Never on the UI thread.** All recognition + DB work runs on `Dispatchers.IO` via
  `NotesproutApplication.appScope`; no `runBlocking` on the UI thread (large stroke arrays → ANR).
- **Conflate + cancel** superseded per-page jobs so a fast writer never queues a backlog.
- **Debounce** to idle so recognition never competes with active inking on e-ink hardware.
- **Reuse the cache** (freshness via `sourceMaxUpdatedAt`) everywhere — RTR, export, on-demand, and
  backfill all read/write the same `page_text` rows.
- **`Slog.d` only** for any tracing (never `Log.d`); **never log recognized text or passphrases.**

---

## Phasing

1. **Core + export-only (lowest risk, validates segmentation quality).**
   `StrokeSegmenter` + `recognizeSegment(preContext)` + `PageTextRecognizer` + `PageText` / `page_text`
   storage, wired to a foreground "Export as text/markdown" with progress. No background scheduling.
2. **RTR.** Idle/seal-debounced per-page scheduler + `rtrEnabled` toggle + backfill-on-enable +
   the read-only viewer.
3. **Later.** Multi-column layout, editable text view, search index, Onyx HWR engine swap, additional
   language models.

---

## Deferred / open questions

- **Editable recognized text** — the viewer stays read-only, and reconciling text edits back onto ink
  remains out of scope. Editing *did* arrive, but as a separate object rather than a writable cache: a
  page's recognized text seeds a **document** once, which the user then owns
  (see [`documents.md`](documents.md)). Nothing flows back to the ink.
- **Multi-column / tables** — single-column assembly only; horizontal-gap data is captured but unused.
- **List recognition (numbered / bulleted / checkbox)** — v1 takes ML Kit's line text verbatim, so a
  list only becomes Markdown when ML Kit happens to return a clean `N. ` / `- ` prefix. Two failure
  modes make this unreliable: (1) **marker mangling** — handwritten `1.` often comes back as `1`,
  `l.`, `I.`, or `1)`, and a hand-drawn bullet/checkbox isn't recognized as `•`/`☐` at all; (2) a list
  is a **spatial pattern** (marker in the margin, gap, hanging text), not a text one. A real fix is a
  structure-detection pass: detect the hanging-indent geometry from stroke positions and normalize the
  marker ourselves — comparable in effort to the deliberately-deferred size-based heading inference.
  Must avoid false positives on dates/times (`6:30`) and measurements. Deferred as its own focused task.
- **Hard vs. soft line breaks** — v1 emits a newline per detected line (faithful for notes); detecting
  wrapped vs. intentional breaks is a refinement.
- **Baseline skew** — median-band grouping tolerates slight drift; per-line least-squares baselines
  could improve heavily slanted hands later.
- **Language** — only `en-US` today; multi-language notebooks need model selection + download UX.
- **Onyx HWR engine** — the `engine` field and interface leave the door open; the AIDL bridge itself
  is a separate effort (see `SUPERNOTE_SUPPORT_PLAN.md` for the analogous vendor-ink pattern).

---

## TrOCR engine — the personalizable second engine (`hwr-trocr`)

A second `HandwritingRecognizer` implementation that Notesprout fully controls and that will
**learn the user's handwriting** over time, fully offline. Base model:
`microsoft/trocr-small-handwritten` (MIT) — an **image** (line-crop) recognizer, chosen because no
production-quality open-source *stroke*-based pretrained model exists. Runtime: **ONNX Runtime
Mobile** (`com.microsoft.onnxruntime:onnxruntime-android`, MIT) — a deliberate, discussed
dependency addition.

### Routing & rollout

- **Settings toggle** (MainActivity overflow → Handwriting Recognition, `HwrSettingsActivity`):
  Standard (ML Kit, default) vs Personal (TrOCR, experimental). Stored in `HwrSettings`
  (`hwr_settings` prefs).
- **`HandwritingRecognizerProvider` is the only router**: `instance` returns TrOCR when the toggle
  says so AND a model bundle is installed; ML Kit otherwise. Deleting the model can never strand
  the app without recognition. No call-site changes anywhere.
- **Per-line fallback:** `PageTextRecognizer(hwr, fallback = Provider.mlKitFallback)` retries a
  line through ML Kit when the active engine returns `FALLBACK_TEXT`.
- **Engine-aware cache freshness:** `PageTextRepository.isFresh(cached, max, expectedEngine)` —
  flipping the toggle invalidates the other engine's cached `page_text`, so viewer/export
  re-recognize with the newly-selected engine (RTR stays watermark-only on purpose: no
  whole-notebook churn on toggle; it picks the new engine up on next notebook open).
  `HandwritingRecognizer.engineName` ("mlkit"/"trocr") is stamped into `PageText.engine`.

### Pipeline (`recognition/trocr/`)

`StrokeSegmenter.Segment` → `LineRasterizer` (strokes only, always black, no template; uniform
stage-1 render at 128 px height then **non-aspect-preserving** 384×384 stretch — deliberately
matching TrOCR's training distribution, do not "fix") → `TrOcrSession` (3 ORT sessions: encoder +
unmerged decoder pair; step 1 = `decoder_model.onnx` computing cross-attention KV, steps 2+ =
`decoder_with_past_model.onnx`) → `TrOcrDecoder` (pure-JVM greedy loop, 4-gram repetition ban,
`LogitProcessor` hook for future lexicon biasing, cooperative cancellation) →
`SentencePieceTokenizer.decode` (unigram `tokenizer.json`; vocab index == token id; must tolerate
out-of-range ids — 64044 logits vs 64002 pieces). Sessions load lazily (~1 s), never at startup,
and are dropped on `onTrimMemory(UI_HIDDEN)` when idle (`releaseIfIdle`).

### Model bundles

Produced by **`tools/hwr/`** on a Mac (`export_model.py` → ONNX + int8 dynamic quant incl. Gather
so the 65 MB embedding shrinks — the *merged* decoder is rejected because its `If` subgraph defeats
`quantize_dynamic`; `make_bundle.py` → zip with manifest). Imported via SAF in `HwrSettingsActivity`
into `filesDir/hwr/models/<versionId>/` (internal storage deliberately — personalized weights are
biometric-adjacent). Install verifies manifest schema, per-file SHA-256, **and a smoke decode**
before atomically activating; all runtime config (token ids, image normalization, decode cap)
travels in the manifest — nothing hardcoded on either side. `TrOcrModelStore` keeps versions
side-by-side (activate/switch/delete in settings).

### Measured on G102 (Phase 0, int8, greedy, real handwriting)

median **509 ms/line** (p95 510), session load 976 ms, ~291 MB native heap while loaded, base CER
10.9 % vs ML Kit 5.5 % on the same 6 lines — TrOCR's misses were uppercase tech terms (`JSON`,
`SQLite`), the exact target of personalization. Whole-notebook cold recognition (357 lines) ≈ 3 min
— acceptable for the experimental toggle; a progress indicator in the viewer is future polish.

### Interactive latency (heading / text conversion)

A single-shot conversion pays the encoder+greedy-decode cost (~500 ms warm) **plus** a ~1 s cold
ORT session load on the first recognition of a session and after every `onTrimMemory(UI_HIDDEN)`
release. Fixes applied 2026-07-18:

- **Warm-up on selection.** `HandwritingRecognizerProvider.warmUpActive()` →
  `TrOcrHandwritingRecognizer.warmUp()` loads the sessions + personalization off the interactive
  path, fired from `updateFloatingSelectionToolbar` when the selection is **pure strokes** (the
  only selection convertible to a heading / text object). The cold load then overlaps the user's
  "Heading → H1" taps instead of landing after them. Idempotent, `tryLock`-guarded (a recognition
  in flight is already loading), and a no-op when ML Kit is the active engine.
- **Lexicon bias was silently dead.** `TrOcrSession.generate` accepted `processors` and never
  passed them to `TrOcrDecoder.greedy` — the `UserLexicon` bias built per recognition was computed
  and discarded. Now forwarded.
- **Correction-memory rebuild no longer loads the whole store.** `correctionPairs` went through
  `confirmedPairs()`, deserializing every row's `strokesJson` (up to 2000 rows) just to read two
  text columns. Replaced with a `confirmedCorrections()` projection (`CorrectionRow`) that filters
  in SQL.

### Debug lab

`HwrLabActivity` (debug source set only, `adb shell am start -n
com.notesprout.android.dev/com.notesprout.android.HwrLabActivity`, or the settings screen's
debug-only button): per-line ink image + both engines' transcription + latency, tap-a-line to set
its true text, corpus CER. Recognized text is shown on screen only — **never logged** (privacy rule
applies to this engine identically).

### Phase 2 — personalization (BUILT)

- **Training-pair store**: Room DB `filesDir/hwr/training.db`, SQLCipher-encrypted under the
  global key (see the encryption bullet below)
  (`data/hwr/TrainingPairEntity|Dao|HwrTrainingDatabase`, cap 2000, unconfirmed evicted first) —
  a pair = strokes JSON + human label + the engine's `originalText`. Every capture is gated by
  `TrainingPairRepository.captureAllowed` — **the personalization toggle alone**. Deliberately
  NOT in `notesprout.db`.
- **Encrypted notebooks contribute (changed 2026-07-18).** The original rule was "plaintext
  notebooks only", written when the store itself was plaintext. **Phase 1b-ii (`3d52e5b`) made
  `filesDir/hwr/training.db` SQLCipher-encrypted under the global key** — with migration-in-place
  and rotation `rekey()` — which removed the reason for the rule; under encrypt-by-default it
  then disabled the feature outright, so it is gone. Pairs are protected at rest at the same
  level as the `.soil` they came from, and nothing leaves the device unless the user explicitly
  exports a training bundle (that bundle is plaintext, by necessity — it feeds `finetune.py`).
  Viewer **Correct** mode is likewise no longer hidden on encrypted notebooks — `loadLineStrokes`
  now applies `SoilCrypto.roomFactory` when `encrypted` (opening an encrypted `.soil` as
  plaintext Room is the link-picker data-loss class of bug; the key is re-resolved via
  `KeyResolver`, never held in a field).
- **Capture sources**: heading conversion (unconfirmed, keyed by heading id) + heading edit
  (upgrades to confirmed) in `NotebookActivity`; viewer line correction; HwrLab references;
  enrollment. Other heading hosts (Scratchpad/DayDetail/StickyNote) are BACKLOG.
- **Only the initial edit teaches** (2026-07-18). `confirmByObjectId` ignores an edit when the
  pair is **already confirmed** (corrected once) or when it lands **more than 5 minutes**
  (`CORRECTION_WINDOW_MS`) after capture. Rationale: editing a heading days later means the user
  changed what it should *say*, not that the engine misread the ink — confirming it would store a
  label that no longer describes the stored strokes and teach `CorrectionMemory` a bogus
  "wrong → right" substitution. Viewer line correction is unaffected (it is explicitly a
  fix-the-recognition mode and writes a confirmed pair directly via `addPair`).
- **Viewer Correct mode** (`PageTextViewerActivity`): `PageText` **schema 2** adds
  `lines: List<RecognizedLine>(text, strokeIds, top, height)` provenance for handwriting-derived
  lines (populated by `PageTextRecognizer`; schema-1 rows decode with `lines = null` and offer no
  correction until re-recognized). Correct button (This-Page, plaintext, personalization on) →
  tappable line list → edit dialog → confirmed pair + in-memory patch. The `page_text` row is NOT
  written (viewer stays read-only); the fix becomes durable via correction memory on the next pass.
- **Enrollment** (`HwrEnrollmentActivity`, settings → "Teach it your handwriting…"): 16 prescribed
  sentences (`EnrollmentScript` — letter/digit/punctuation coverage), in-memory ink capture
  (`EnrollmentInkView`, plain View), single-band enforcement via `StrokeSegmenter`, each save = a
  confirmed pair.
- **Decoder-level personalization**, applied inside `TrOcrHandwritingRecognizer` and rebuilt
  automatically when the confirmed-pair count changes: `UserLexicon` (confirmed labels → word →
  token-id prefix trie; ≥3 chars, non-numeric) drives a bounded `LogitProcessor` bias (+2.0) toward
  lexicon-word continuations; `CorrectionMemory` (exact normalized-line map + word substitutions at
  ≥2 identical confirmations) post-passes the decoded text. Master toggle: settings →
  "Learn from my corrections" (`HwrSettings.personalizationEnabled`); "Clear my data" wipes the store.

### Phase 3 — Mac fine-tune loop (BUILT; awaiting a real training run)

- **Export** (settings → "Export training data…", visible when samples > 0):
  `TrainingBundleExporter` writes a SAF zip — `pairs/<id>.png` rendered by the **same
  `LineRasterizer`** used at inference (train/infer match), `strokes/<id>.json` (raw ink for
  augmentation), `labels.jsonl` (label + source + the engine's `originalText`), `meta.json`
  (bundle schema + `RASTERIZER_VERSION`, which `finetune.py` validates before training).
- **`tools/hwr/finetune.py`** — peft LoRA (r=8 on decoder q/v projections), MPS/CPU; training
  images re-rasterized from strokes each epoch by a Python mirror of `LineRasterGeometry`
  (thickness ±30 %, slant ±4°, sub-px jitter); the app-rendered PNGs serve as the held-out eval
  split so before/after CER matches on-device rendering. Refuses to run on < 8 pairs.
  `merge_and_unload()` → merged HF model → `export_model.py` + `make_bundle.py --personalized` →
  import in settings (SHA-256 + smoke decode; versions listed side-by-side, ML Kit fallback if
  anything fails).
- **Payoff gate** (still open): personalized CER < ML Kit CER on held-out lines of the user's
  handwriting. **First real attempt (2026-07-13, 39 pairs): not enough data.** Three configs
  (decoder LoRA, both-sides, encoder-only) all degraded held-out CER vs the base model
  (0.03–0.06 base → 0.10–0.20 tuned; failure mode = memorization + repetition artifacts).
  As-built learnings baked into `finetune.py`: train on the app-rendered PNGs (the Python stroke
  re-rasterizer's PIL rendering differs enough to hurt — kept only for experiments), `--target
  encoder|decoder|both` (default encoder), early stopping on held-out CER every 2 epochs with
  best-checkpoint restore, and **refuse to save any model when nothing beats base**. Revisit at
  roughly 150+ pairs (mostly real-page corrections); meanwhile the on-device lexicon/correction
  memory is the personalization that works at this data scale — by design.
- Round trip verified structurally on G102: enroll → export → bundle parses in `finetune.py`'s
  loader → augmentation raster mirrors the app's geometry.
