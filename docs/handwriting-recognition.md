# Handwriting Recognition & Page-Text

Handwriting-to-text recognition in Notesprout. This doc covers **what ships today** (a single-shot
ML Kit recognizer used for heading / text-box conversion) and the **proposed design** for extending
it to whole-page and whole-notebook text: a segmentation layer, a persisted per-page text cache, a
real-time background recognition (RTR) mode, and an export-time recognition path.

> **Status.** The single-shot recognizer (§ Current State) is **shipped**. Everything from
> § The Gap onward is **proposed design — not yet built.** Pull a phase into its own plan before
> building it (see § Phasing and the matching `BACKLOG.md` section).

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

- **Editable recognized text** — reconciling text edits back onto ink is out of scope; the viewer is
  read-only in v1.
- **Multi-column / tables** — single-column assembly only; horizontal-gap data is captured but unused.
- **Hard vs. soft line breaks** — v1 emits a newline per detected line (faithful for notes); detecting
  wrapped vs. intentional breaks is a refinement.
- **Baseline skew** — median-band grouping tolerates slight drift; per-line least-squares baselines
  could improve heavily slanted hands later.
- **Language** — only `en-US` today; multi-language notebooks need model selection + download UX.
- **Onyx HWR engine** — the `engine` field and interface leave the door open; the AIDL bridge itself
  is a separate effort (see `SUPERNOTE_SUPPORT_PLAN.md` for the analogous vendor-ink pattern).
