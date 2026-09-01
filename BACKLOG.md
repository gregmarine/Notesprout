# Notesprout — Backlog

> Consolidated backlog of deferred / future items harvested from the per-feature implementation
> plans as they were completed and retired. Each shipped plan was removed once done; the items below
> are the things those plans explicitly punted. **The full retired plans (with detailed design notes,
> file paths, and session breakdowns) remain recoverable from git history** if you need the context
> behind any item.
>
> Standing design docs that are **not** folded in here (read them directly):
> - `NOTEBOOK_SIZE_RESEARCH.md` — `.soil` size-reduction + backup-compaction research (no plan yet).
> - `docs/handwriting-recognition.md` — page-text / whole-notebook recognition (segmentation layer,
>   `page_text` cache, RTR mode, export path), not started. Decision/deferred items summarized below.
>
> Nothing here is scheduled. Pull an item into its own plan before building it.

---

## TEMP — legacy-`ts` + PNG→WEBP compaction (remove after all my devices are compacted)

> Transitional single-user migration, **not** a permanent feature. New writes already omit the dead
> per-point `ts` (see `StrokePoint.timestamp` / `LiveStroke.toStrokeData`) and store images as WEBP
> q100 (see `core/ImageCodec.kt`); this backfills existing `.soil` files **and** the `notesprout.db`
> index. The image pass re-encodes both legacy PNG **and** the earlier mistaken lossless-WEBP blobs
> (detected via `NotebookCompactor.needsWebpReencode`) to q100, skipping already-lossy rows. Delete
> the whole compaction path once every device I use has been swept.

- **Manual sweep** (the transitional UI to delete first): `btnCompact` in `activity_main.xml`
  (all three variants), `ic_compact.xml`, and `showCompactNotebooksDialog()` /
  `runCompactNotebooksSweep()` in `MainActivity`.
- **Auto-at-close hook**: the `NotebookCompactor.compact(db)` call in `NotebookActivity.sealNotebook`.
  Cheap and self-limiting (`LIKE '%"ts":%'` for ts; a 60-byte header check per image row once
  notebooks are clean) — safe to leave a while, but it's part of the same transitional path.
- **Core + DAO**: `data/NotebookCompactor.kt` (incl. `needsWebpReencode`); `NotebookDao`
  `strokeRowsWithLegacyTimestamp` / `imageRowHeads` / `imageDataForId` /
  `rewriteObjectDataKeepingTimestamp` (+ `StrokeRowData` / `ImageRowHead`); `ObjectDao`
  `imageRowHeads` / `imageDataForId` / `rewriteObjectData` (+ `IndexImageHead`).
- **Keep** `StrokePoint.timestamp` nullable and **keep** `core/ImageCodec.kt` — those (the schema
  change and the WEBP encoder) are permanent and correct; only the backfill/compaction goes away.
- Only `type='stroke'` rows are `ts`-stripped; images re-encoded are PNG + lossless-WEBP (lossy-WEBP
  and JPEG covers left alone). `ts` embedded in headings/text/links/sticky-notes is a small untouched
  tail; fold it in only if a future pass makes it worthwhile.

---

## Notebook close is noticeably slower than jumping to Calendar/Today (look into soon)

Closing a notebook via the toolbar close button has a visible delay; tapping Calendar or Today from
the same notebook is near-instant. Diagnosed 2026-08-10 (research only, no code changes).

**Why the difference:** the close button runs real teardown **synchronously on the main thread
before `finish()`** (`NotebookActivity.closeNotebook`, called from the `btnClose` handler), then
reveals a library screen that rebuilds itself. Calendar/Today are just `startActivity()` — the
notebook stays alive underneath, and its only bookkeeping (the `onStop` undo/redo persist) runs
*after* the new screen has already drawn, so the same cost is paid invisibly.

Pre-`finish()` main-thread work on close:
1. **Undo/redo persist** (encrypted notebooks — i.e. everything): `undoRedoManager.toJson()` of the
   full history + a synchronous `execSQL` into the SQLCipher `.soil` (JSON build + encryption + disk
   I/O on main).
2. **Cover snapshot** — `drawingView.captureSnapshot()`: full-page ARGB_8888 bitmap (~10–20 MB),
   redraw of template + every object + every stroke, then **WEBP q100 compress + Base64 encode on
   main** (`ImageCodec.encodeBase64`) — almost certainly the biggest single chunk. On Ratta it also
   releases the firmware ink overlay first.
3. Stroke-list deep copy for the seal. (The seal itself is already off-main on `appScope`.)

Then `MainActivity.onResume` → `scanAndRender()` re-queries the index, re-renders the cover grid,
and the e-ink repaints the whole library.

**Candidate fixes** (same family as the tap-time "Opening…" overlay, commit 513d168):
- Capture the raw bitmap on main (cheap-ish) but move the WEBP encode + Base64 into the seal on IO.
- Give the close tap instant visual feedback (a "Closing…" ack via the `OpeningOverlay`
  pre-draw+post pattern) — the library re-scan + e-ink repaint can only be masked, not removed.
- Note: `sealForConversion` and the `onDestroy` blocking path share `sealNotebook` — keep their
  semantics (blocking, no snapshot) intact when moving work around.

---

## Supernote (Ratta) — deferred items

> From the retired `SUPERNOTE_SUPPORT_PLAN.md` (all 10 phases shipped on the `supernote` branch,
> 2026-08-08/09, validated on both the Nomad and the Manta). The as-built engine is documented in
> `docs/drawing-engine.md` → "Ratta (Supernote) Firmware Ink Engine"; the full plan with its
> per-phase findings is in git history.

- **Collapse `GenericNotebookView` + `RattaNotebookView` into a shared `CanvasNotebookView` base.**
  The explicit follow-up to the locked sibling-copy decision (copy chosen for zero risk to the ten
  shipping Generic devices). Until then every shared-logic fix (lasso, erase, gestures, rendering)
  must be applied to both files by hand — the standing tax this item removes.
- **User-facing stylus calibration screen.** The Ratta registration offsets (+2 px Nomad / +3 px
  Manta input x-shift) are believed model-level but were measured on **one unit per model**; a unit
  with different factory calibration would show a 1–3 px live-vs-baked shift. A calibration surface
  (the probe's REG-lab pattern: draw, nudge to null, store per-device) would replace the hardcoded
  constants — and also cover whole-pipeline tip offsets on Generic-engine devices (the Paper 7 is
  the known suspect; on Generic, live and baked agree with each other but can both miss the pen).
  BOOX can never need the Supernote half: SDK overlay and bake share one input pipeline.
- **Firmware pen types as a pen-tool offering.** The 0…31 sweep found more than the four codes the
  Supernote UI exposes: 0/5/8 solid steady, 1/2/16 pressure-sensitive, 14/15 calligraphy, plus the
  lasso vocabulary (3 = x-stream, 4 = dashes). Candidate material for a future pen picker alongside
  the Onyx styles in `docs/onyx-pen-tools.md`. **Code 12 is broken** (random giant laggy blob) —
  exclude it from any offering.
- **Enumerate `end_button_behavior` values.** The OS side-button preference (`Settings.System`,
  app-readable; `=2` delivers `BUTTON_STYLUS_PRIMARY` from hover) was only tested at its default.
  If some value makes the OS swallow the button, barrel-erase goes inert for that user (no
  misbehaviour beyond the firmware possibly painting its native trace unsuppressed); the probe's
  hover-Δ barrel lab is the test rig, and the value could drive a runtime hint if it ever warrants
  one.
- **HWR enrollment on Supernote** was descoped by the user (staying on ML Kit; the custom TrOCR
  engine may be removed entirely) — not a test gap; revisit only if the enrollment feature survives.

---

## TrOCR personal engine — deferred items (branch `hwr-trocr`)

Phases 0–2 built (see `docs/handwriting-recognition.md` § "TrOCR engine"). Deferred:

- **Viewer progress indicator** — a cold whole-notebook recognition pass with TrOCR (~0.5 s/line)
  can take minutes with only a static "Recognizing…" label; show per-page progress + cancel.
- **Capture hooks in other heading hosts** — heading-conversion/correction capture currently lives
  in `NotebookActivity` only; Scratchpad / DayDetail / StickyNoteEditor heading edits could mirror
  the same ~8-line hook (their conversions would need capture first for corrections to matter).
- **Beam search + KV-state cloning** — decoder is greedy; beam-3 needs per-beam ORT tensor state
  cloning in `TrOcrSession`. Revisit if lexicon biasing under greedy proves too weak.
- **RTR debounce bump when TrOCR active** — consider raising `RtrScheduler.DEBOUNCE_MS` 2 s → 4 s
  while the Personal engine is selected (a 19-line page ≈ 10 s per RTR job).
- **Enrollment ink view EPD acceleration** — `EnrollmentInkView` is a plain View (laggy-but-usable
  ink on e-ink); could route through the raw-drawing fast path like real pages.

## Handwriting → Text: page-text, RTR & export recognition

> **Phases 1 & 2 SHIPPED on `sprout` (2026-07-03), device-verified.** Full detail + as-built notes in
> `docs/handwriting-recognition.md`. `StrokeSegmenter` (rewritten to a vertical projection profile),
> `recognizeSegment(preContext)`, `PageTextRecognizer`, the `page_text` cache, Markdown/text export
> (whole-notebook + selected/single page), RTR (`RtrScheduler` + `rtrEnabled` toggle + backfill), and
> the read-only `PageTextViewerActivity` are all done. The Phase 1/2 bullets below are retained for
> reference; **only the "Deferred / open questions" items remain.**

**Phase 1 — core + export-only (lowest risk, do first):**
- **`StrokeSegmenter`** (`recognition/StrokeSegmenter.kt`) — pure geometry, unit-testable: median-height
  line grouping → intra-line ordering + word gaps → paragraph breaks. Feeds only `stroke` rows; merges
  headings / text-objects (which already carry `recognizedText`) by vertical position.
- **Interface change** — add `suspend fun recognizeSegment(strokes, bounds, preContext)` to
  `HandwritingRecognizer`; `MlKitHandwritingRecognizer` currently hardcodes `setPreContext("")`, losing
  the free per-line context-chaining accuracy win. Keep the callback `recognize` for the single-shot path.
- **`PageTextRecognizer`** (`recognition/PageTextRecognizer.kt`) — segments a page, recognizes each line
  with chained `preContext`, assembles reading-order text.
- **`page_text` object** — `NotebookObject(type = "page_text", parentId = pageId)`, `data = PageText`
  JSON (`text`, `engine`, `recognizedAt`, `sourceMaxUpdatedAt`, `schema`). **No schema migration** (type
  is a string discriminator). Staleness reuses `NotebookDao.getMaxContentUpdatedAt(layerId)`; encrypted
  at rest and portable on export/import for free.
- **Export-only path** — foreground "Export as text/markdown" with determinate progress + cancel; reuses
  fresh `page_text` rows when present. Rides alongside `NotebookExporter`.

**Phase 2 — RTR (real-time background):**
- **Scheduler** — hook `saveStrokes(db)` + erase completion in `NotebookActivity`; idle-debounced (~2 s)
  + on page-seal, **not** per-stroke. Runs on `NotesproutApplication.appScope` (IO), conflated per page
  with a `saveMutex`-style lock; cancel superseded jobs.
- **`rtrEnabled` toggle** — per-notebook, in `NotebookMetadata` (travels on export). **Decision:**
  convertible any time (not creation-only) with a creation default of **OFF**; enable kicks a backfill
  batch over existing pages, disable stops scheduling and keeps the cache. Optional global "new notebooks
  use real-time text" default.
- **Read-only text viewer** — secondary screen modeled on `DayDetailActivity`'s day-window pattern;
  "stale/updating" badge from `sourceMaxUpdatedAt`.

**Other paths (share the core):**
- **On-demand single-page / "copy page as text."**
- **Backfill-on-enable** = the export batch run in background with progress.

**Deferred / open questions:**
- **List recognition (numbered / bulleted / checkbox)** — v1 takes ML Kit's line text verbatim, so a
  list only becomes Markdown when ML Kit returns a clean `N. ` / `- ` prefix. Unreliable for two reasons:
  (1) **marker mangling** — handwritten `1.` comes back as `1`/`l.`/`I.`/`1)`, and a drawn bullet/checkbox
  isn't recognized as `•`/`☐`; (2) a list is a **spatial** pattern (marker in margin → gap → hanging text),
  not a text one. A real fix is a structure-detection pass that reads the hanging-indent geometry from
  stroke positions and normalizes the marker — comparable effort to the deferred size-based heading
  inference, and must not false-positive on dates/times (`6:30`) or measurements. Own focused task.
- **Editable recognized text** (reconciling edits back onto ink) — read-only in v1.
- **Multi-column / tables** — single-column assembly; horizontal-gap data captured but unused.
- **Full-text search** over `page_text` — a natural future consumer (ties into the Encryption Phase-3
  "opt-in encrypted search index" item); not built here.
- **Hard vs. soft line breaks**, **baseline skew** (per-line least-squares), **multi-language models**
  (only `en-US` today), and the **Onyx HWR engine** swap (the `engine` field leaves the door open).

---

## Sticky Notes — deferred items

> From the retired `STICKY_NOTE_PLAN.md` (all 7 sessions shipped). Items below were explicitly
> deferred during build or noted as non-goals for v1.

- **Cross-size content scaling.** When the editor window size differs from the authored
  `contentWidth/Height` (rotation, cross-device paste), proportionally rescale embedded content
  instead of rendering as-authored + clipping.
- ~~In-editor autosave / process-death durability.~~ **DONE (2026-07-21).** The editor now persists
  its canvas to the sticky's own encrypted DB in real-time (debounced pen-lift + `onStop` flush) —
  see `docs/sticky-notes.md` → "Real-time persistence". An OS kill mid-edit no longer loses ink.
- **Multi-page sticky notes.** D1 chose single page; multi-page would need in-window page nav/add/delete.
- **Native text/line insertion inside the editor.** D2 chose pen / eraser / lasso + paste only.
  Adding the text and line insertion buttons inside the window is a follow-on decision.
- **Content affordance on the icon.** Currently one static sticker icon regardless of whether the
  note has content. A "has content" indicator or mini-preview would improve discoverability.
- **Sticky-note content in search / TOC.** Content is hidden and intentionally excluded from ML Kit
  search and page-name/TOC rules for v1. An explicit opt-in search over embedded content is future work.
- **Live undo inside the editor.** D3: only one before/after undo action per window session. Live
  in-window undo (stroke-level) is a follow-on feature.
- **Endnote pagination / fit.** S6 renders each note's content onto a single endnote page sized to
  the content; content larger than one page is not split across multiple endnote pages (acceptable for v1).

---

## Scratch Pad — phase 2 / deferred

> From the completed `SCRATCHPAD_PLAN.md` (all 8 sessions shipped). Items below were explicitly
> deferred during build or noted as non-goals for phase 1.

- **True geometric crop (Send to Scratch Pad).** Current "Crop to fit" translates the selection to
  origin `(0,0)` and lets the canvas clip the overflow. No geometric point-cutting of strokes that
  straddle the boundary. A proper crop would bisect strokes at the page edge.
- **Per-pad pen style picker.** Phase 1 reuses the global `ToolPreferencesManager` pen settings.
  A scratch-pad-local pen style (separate from the notebook pen) would need its own prefs key and
  toolbar picker.
- **Move-vs-copy on "Send to Notebook".** Current behaviour: Send is a copy — content stays in the
  scratch pad after sending. A "move" variant (auto-clear the sent page after transfer) may be useful
  and was deferred pending UX decision.
- **Scratch-pad-only undo persistence.** There is no undo/redo on the scratch pad. If undo is added,
  persisting the undo stack across restarts (like notebooks do via `undo_redo_state`) would be needed.
- **Scratch pad snapshot in recents / cover.** No thumbnail is generated for the scratch pad in the
  recents bar or anywhere else in the UI.
- **Encryption path for global DB.** The scratch pad is permanently plaintext because `notesprout.db`
  is never encrypted. If per-user DB encryption is ever added, the scratch pad inherits it
  automatically; the existing `awaitEncryptionClipboardConfirm` warning would need revisiting.

---

## Toolbar Customization — Session 8 (UI/UX polish)

> From the retired `TOOLBAR_CUSTOMIZATION_PLAN.md`. Core Sessions 1–7 shipped (feature is live and
> documented in `CLAUDE.md` / `docs/toolbar.md`). Session 8 was a living idea-backlog for *feel*,
> revisited once the feature was usable in hand. (One Session-8 idea — **pin the gear button so it's
> always shown** — already shipped.)

- **Split-panel "Customize Toolbar" dialog (grid, not list).** Redesign into two stacked grid panels:
  a top **"Showing"** panel (visible buttons, arrangeable in order) and a bottom **"Hidden / Available"**
  panel. Drag a button between panels to show/hide; drag within the top panel to reorder — making order
  + visibility a single direct manipulation instead of separate reorder + toggle steps. Considerations
  to work through: 2-D grid drag-reorder + reflow (more involved than the current list); still
  hand-rolled (no RecyclerView); how Close/gear pinning reads in a grid (locked cells?); how the
  mini-set picker composes with this layout; cross-panel drag must preserve the move-not-clone +
  key-stability contracts.
- **Remove the toolbar dividers.** Re-evaluate whether the auto-managed group dividers earn their keep;
  consider dropping them for a cleaner, calmer bar. Not yet decided.

---

## Multi-Page Selection — Phase 2

> From the retired `MULTI_PAGE_SELECTION_PLAN.md` (Phase 1 + its in-plan P2.1/P2.2 all shipped). These
> were flagged "do NOT build without discussion."

- **Range selection** — tap first, shift/long-press last to select a span, for faster large selections.
- **Selection count badge** on each grid page, or a footer summary while paginating.
- **Drag-to-reorder** multiple pages directly (vs. the before/after destination tap).
- **Export ordering choice** — page order vs. selection order for PDF/PNG export.
- **Persist selection** across leaving/returning to the index (currently cleared on exit).
- **"Invert selection"** control alongside Select All.

---

## Notebook Encryption — Phase 3 (deferred / found-along-the-way)

> From the retired `NOTEBOOK_ENCRYPTION_PHASE2_PLAN.md`. Phase 1 + Phase 2 shipped in full. Some gates
> below were written before the cross-notebook page-transfer and multi-page features landed — re-check
> each gate before assuming it's still blocked.

- **Cross-notebook page-copy plaintext-leak confirm** — warn when copying a page from an encrypted
  notebook into a plaintext one. *Originally gated on cross-notebook page copy/move existing.* That
  feature has since shipped (the retired Clipboard + Page-Transfer plan) **with a "smart" encryption
  warning already built in** (warn only when protection actually drops). **Re-verify whether this is
  already satisfied** before treating it as open work.
- **Bulk encrypt / decrypt (multi-select + whole-folder)** *(blocked)* — encrypt/decrypt many notebooks
  at once, and encrypt a whole folder. Gated on **multi-select for notebooks/folders** existing (today
  only multi-*page* selection exists, not multi-notebook/folder). Sub-decision when unblocked:
  per-notebook distinct passphrases vs. one shared passphrase for a NOTEBOOK-scope batch.
- **Biometric gate** — optionally require fingerprint/face to release the cached global passphrase.
  Needs `androidx.biometric` (new Gradle dependency → requires discussion).
- **Change passphrase from the open toolbar** — Phase 2 put re-key / scope-change in the context menu
  only; add an in-notebook toolbar entry if wanted.
- **Export password = notebook passphrase option** — let the user opt to reuse the notebook passphrase
  as the PDF password instead of entering a separate one.
- **Encrypted PNG / ZIP export** — password-protected archive for PNG exports (PDF is already covered).
- **Rotation as a foreground service / WorkManager job** — move global-passphrase rotation off the
  activity for very large libraries, with a persistent notification so it survives navigation.
- **Recents thumbnail for encrypted notebooks** — currently a lock icon; consider a user-set cover.
- **Search over decrypted content (opt-in)** — if full-text search of page content is ever added,
  design an explicitly opt-in, encrypted-at-rest index (none exists today, by design).
- **Cross-session undo/redo for plaintext notebooks** — encrypted notebooks already persist undo/redo
  inside the `.soil` (`undo_redo_state` table); extend the same store-on-close / read-on-open behaviour
  to plaintext notebooks (today plaintext only survives background→foreground via the sidecar, and
  loses history on explicit close). Same pattern, no crypto needed.

---

## Columnarize the `events` `data` payload

> Noticed while building the task manager (2026-07-25). The `tasks` table shipped **fully columnar** —
> no `data` column at all — which leaves `events` as the only app-content table still carrying a JSON
> payload in a schema that is otherwise JSON-free apart from the two deliberate singletons
> (`clipboard` / `backup_config`).

`docs/global-index-format.md` currently documents the `events` payload as a considered choice, and it
was: the recurrence rule carries `exceptionDates`, a genuinely open-ended list, and every field added
to it since v5 has landed with an empty default and **no migration**. That is real value.

Whether to columnarize is therefore a judgement call, not an obvious cleanup:

- **For** — one consistent story across the index; queryable recurrence (e.g. "every yearly event")
  becomes possible; the `tasks` schema proves the shape works.
- **Against** — `exceptionDates` still needs somewhere to live (a child-row table, mirroring the
  `list_item` pattern), which is a second table for one field; and every future event field then costs
  a migration where today it costs nothing.

If it is done, it must touch live on-device event data, so it needs the same care as any
`.soil`/index migration: additive DDL, format-agnostic reads, convert-on-write, sweep in the
background. Not scheduled.

---

## Link Objects — Phase 2

> From the retired `LINK_OBJECTS_PLAN.md` (Phase 1 — page + notebook links — shipped in full).

- **File / website link targets** — explicitly out of scope in Phase 1; the next link kinds to add.
- **Snapshot-invalidation refinement** — a noted "future pass" to tighten how a link edit invalidates
  the page snapshot (Phase 1 reloads the page and clears selection; revisit for a lighter touch).

---

## Columnar schema drift — crash-on-launch with no user-facing recovery

> Hit again on G6 (dev) 2026-07-20 while testing an unrelated UI change; first recorded during the
> global-encryption work, on G6 and MAX but never G102. Not urgent for *my* devices (dev data is
> disposable) — it matters only if drifted notebooks exist on a real install.

Old notebooks written before the columnar migration carry a `notebook` column set that no longer
matches `NotebookObject`. Room finds no `room_master_table`, takes the **`onCreate`** path, then fails
strict schema validation on the open:

```
java.lang.IllegalStateException: Pre-packaged database has an invalid schema:
notebook(com.notesprout.android.data.NotebookObject)
  Expected: blob, x, y, width, height, level, shapeType, linkTarget, …   (columnar)
  Found:    id, parentId, type, updatedAt                                (legacy)
```

("Pre-packaged" is a red herring — there is no asset DB. That's just Room's message for a failed
`onCreate` validation.)

`NonDestructiveOpenHelperFactory` behaves **correctly** here: it converts what the default corruption
handler would have made a silent delete-and-recreate into a loud crash, and the `.soil` files and index
are left byte-intact. A reinstall or clean build does **not** help — the fault is entirely in the
on-device data.

**The crash-loop half is FIXED (2026-07-20).** The open is now caught broadly in `NotebookActivity` and
reported via `state/NotebookOpenFailure`: the notebook steps back to the library, which explains what
happened and offers the raw exception chain behind a "Details" button. Returning to the library is also
what breaks the loop — `MainActivity.onResume` calls `SurfaceStack.reset`, so the bad notebook is no
longer in the stack the next cold launch rebuilds. Note the catch has to sit in **two** places: `build()`
is lazy, so schema validation actually fires on the first query inside `loadStrokes()`'s own coroutine.
Verified on G6 against a deliberately corrupted `.soil`, via both the surface-restore path and a direct
tap. The dialog copy is **intentionally verbose for dogfooding** — trim before a public release.

**Still open: the underlying migration gap.** A drifted notebook is still unopenable — it now fails
gracefully instead of fatally. Options, none decided: a real migration for drifted column sets; a
pre-open schema probe that flags the notebook in the library so its card shows it can't be opened; or
accepting graceful failure as the permanent answer if drift can only originate from test devices.

## Serialization hardening — pin `@SerialName` on `LinkTarget` (latent, not urgent)

> Found 2026-07-14 while evaluating (and rejecting) an app package rename. Nothing is broken today —
> this is a tripwire that only fires if `LinkTarget` ever changes package or class name.

`data/LinkTarget.kt` is a `@Serializable sealed class` whose three subclasses carry **no
`@SerialName`**. kotlinx.serialization therefore falls back to the fully-qualified class name as the
polymorphic discriminator, and that FQCN string is **persisted on disk** — it's written into the
`linkTarget TEXT` column of every link row in every `.soil` (`ObjectColumns.kt:209`, `:326`):

```json
{"type":"com.notesprout.android.data.LinkTarget.OtherNotebookPage","notebookId":"…","pageId":"…"}
```

So the Kotlin package path is, accidentally, part of the on-disk file format. Move or rename the
class and old rows no longer decode. The read path (`ObjectColumns.kt:287`) is
`runCatching { … }.getOrNull()`, so it **fails silently to `null`** rather than throwing: every
existing link would quietly lose its target, and a re-save would persist the `null`. Silent data
loss, discovered late.

**Fix (cheap, do before any refactor that touches this class):**
1. Add stable `@SerialName("current_page" / "other_notebook" / "other_notebook_page")` to the three
   subclasses — decouples the wire format from the class path permanently.
2. Ship a read-side migration (or a `NotebookCompactor` pass) that rewrites existing rows whose
   discriminator is the old FQCN, since step 1 alone would orphan them the same way.
3. While in there: consider making the decode failure loud (or at least `Slog`-warned) instead of a
   silent `getOrNull()`.

Audit note: `LinkTarget` is the only *persisted* sealed hierarchy. `DriveAuth.TokenResult` and
`history/UndoRedoAction` are sealed too but are in-memory only — no on-disk exposure.

---

## Full Notebook Import — out of scope / future

> From the retired `FULL_NOTEBOOK_IMPORT_PLAN.md` (single-file import shipped in full).

- **Bulk / folder import** — import many `.soil` files or a whole exported folder set at once. Shares
  the same multi-select gate as bulk export / encryption Phase 3.
- **Encrypt-on-import for plaintext** — deliberately not offered (the user can lock after import).
  Revisit only if requested.
- **Cross-notebook link auto-repair beyond folders** — import recreates folders with the same IDs to
  help link resolution; full link rewrite/repair across an imported set is a separate effort.
- **Conflict-aware merge** — Replace overwrites; there is no page-level merge of an imported copy into
  an existing notebook.
- **Determinate progress for very large copies** — replace the indeterminate "Importing…" modal with a
  progress bar. Not needed for typical notebook sizes.

---

## Backup — future (restore is a separate effort)

> From the retired `BACKUP_PLAN.md` (Phase 1 backup — LOCAL/SAF + Google Drive REST — shipped in full).

- **Restore** — the inverse of backup (pull `.soil` files + index back onto a device). Explicitly out
  of scope for Phase 1; the backup format was designed to make it possible (display name + folder
  ancestry travel inside each `.soil` via `notebook_meta`).
- **Backup garbage collection** — deleting a notebook does not remove its backup file; a GC/prune pass
  for orphaned backups is future work.
- **Resumable / chunked Drive upload** — `Content-Range` + `308 Resume Incomplete` for large files
  (current path is a single upload). A documented future enhancement.

---

## Shape Objects — deferred items

> Shape objects are complete through S7 (all three hosts: notebook, scratch pad, sticky note editor).
> Items below were identified during build and are not yet scheduled.

- **"Convert to Shape" in lasso context menu.** Add a lasso context-menu action so users can manually
  trigger stroke→shape conversion for strokes that didn't auto-trigger (drawn too slowly, missed
  confidence threshold, or the user changed their mind after drawing). Decide icon and label ("Convert
  to Shape" or "Recognize Shape"); handle the same `ShapeCreated` undo/redo path as the dwell trigger.
  Show only when the selection is a single stroke.
- **1:1 aspect ratio snap for shape objects.** Add a "Square it" / "Make uniform" toggle in the lasso
  context menu for selected shape objects. Not a hard lock — a one-shot snap that resizes the shape to
  a 1:1 bounding box around its current center, then deselects (or re-selects with the new size).
  Especially useful for circles, squares, stars, and diamonds where slight drawing asymmetry produces
  obviously uneven shapes. The `regularized()` function in `ShapeRecognizer` already handles
  aspect-ratio normalization internally; this just exposes a manual user-triggered version of it.
- **Multi-stroke shape assembly.** The recognizer currently requires a single stroke. Composite shapes
  (e.g. two lines forming a cross, or a rectangle with a diagonal inside) cannot be recognized.
  Future: accumulate dwell-held strokes, merge their point sets, then feed to the recognizer.
- **Toolbar "insert shape" button.** An explicit palette button (overflow toolbar, or a dedicated
  "insert" entry) that lets the user tap to place a pre-set shape without drawing — useful on
  non-stylus devices and for precise shapes.
- **Fill option.** `ShapeObject` and `ShapeGeometry.pathFor` support only stroke rendering. Add an
  optional `fillAlpha` field (0=no fill, 1=solid) and a fill-style toggle in the transform toolbar.
  E-ink restriction: only `paperWhite` or very light grays look correct; no dark fills.
- **Snap-target support.** Shape objects are lasso-moveable but do not yet act as snap targets
  (the snap-to-guide system only snaps *to* margin guides, not *to* other objects). A future
  alignment pass could add object-edge snap targets alongside the existing margin guides.
- **Per-shape stroke-width picker.** Currently the shape inherits the stroke width from the drawn
  stroke. A post-transform style picker (like the pen-tool width) could let users adjust it.

---

## Calendar Day view — height-dependent geometry (misaligns on any canvas resize)

> Surfaced 2026-07-18 by the top-guard work (toolbar pushed below the status bar reveal zone, which
> shortened the calendar canvas by the guard height). **Not caused by it — exposed by it.**

`CalendarTemplateRenderer.drawDay()` derives its geometry purely from the canvas height:

```kotlin
val rowH = h / 24f
val top  = rowH * slot
```

Every row is a proportional slice of `h`, so *any* change in canvas height re-spaces the whole grid
and existing day-view handwriting drifts against it — a drift that grows toward the bottom of the
page (~1px at row 1, ~29px at row 23 for a ~30px height loss).

Month and Week are structurally immune: `monthGeometry()` builds **square, width-derived** cells
(`cellW = (w - 6) / 7f; cellH = cellW`) and lets the bottom Notes band absorb all height slack
(`notesH = (h - notesTop)`). Week borrows month's `notesH`, so its cell area reduces to the same
width-derived constant. Day has no such slack band.

This bites on any canvas-height change: a different device, a backup restored onto different
hardware, or a future toolbar-height tweak. The 2026-08-10 Ratta full-screen change (top guard → 0
on Supernote) was exactly such a one-time height change on the Nomad/Manta — accepted knowingly;
pre-change Supernote Day-view ink re-spaced once. The restructure below remains the real fix.

**Options considered** (Day view left misaligned for now, by decision):

- **A — full-bleed canvas.** Restructure `activity_calendar.xml` into a FrameLayout with the toolbar
  overlaying a full-height `calendarContent`, mirroring `NotebookActivity`. Restores the canvas to
  its exact prior height so existing ink realigns; touches no stored data. `CalendarActivity` already
  has `setToolbarExclusion` plumbing. Fixes today's symptom, not the underlying fragility.
- **B — give Day a slack band.** Derive `rowH` from a fixed (dp- or width-based) reference and let a
  bottom band absorb the remainder, exactly as Month/Week do. Makes Day permanently
  height-independent. Costs a one-time reflow of existing day-view ink.
- **C — rescale stored day strokes** by `newH/oldH`. Rejected: needs the old height recorded and only
  patches a single instance.

Recommended path if picked up: **A**, then **B** as a deliberate hardening pass.

---

## Operational task — migrate legacy on-device PNG templates into the index

> From the retired `TEMPLATE_MIGRATION_RUNBOOK.md`. The template system moved from flat PNG files
> (`getExternalFilesDir("Templates")`) into the global index (`notesprout.db`) as `type="template"`
> objects. The app ships **fresh — there is no in-app migration.** This is a **one-time operational
> task run from the dev machine over ADB** (not app code) to bring a user's *existing* on-device PNG
> templates into the new library. Only needed for devices that held templates under the old PNG scheme.

**Procedure outline** (the full runbook — exact commands + the `migrate_templates.py` script — is in
git history if needed):

1. **Pick the build** — debug (`com.notesprout.android.dev`) or stable (`com.notesprout.android`); each
   has its own `notesprout.db` and `Templates/` dir. Run once per build if migrating both.
2. **Quiesce** — `adb am force-stop <pkg>` so nothing holds the WAL-mode DB open.
3. **Pull** `notesprout.db` (+ `-wal`/`-shm` sidecars) and `files/Templates/*.png` to a working dir.
4. **Insert** one `type="template"` row per PNG into the `objects` table, under an
   `"Imported templates"` `type="template_folder"` at root (or root directly). For each PNG: name =
   filename stem (stored in the `name` **column**, not the JSON), `data` =
   `{"width":W,"height":H,"image":"<base64 NO_WRAP>"}`, timestamps in epoch **ms**, fresh `uuid4` id.
   Idempotent — skips names already present. Run `PRAGMA wal_checkpoint(TRUNCATE)` so the main DB is
   self-contained.
5. **Push back** the rebuilt main DB; delete the device's stale `-wal`/`-shm` so it starts clean from
   the main file. (Do not push local sidecars.)
6. **Verify** in-app: Templates browser shows the imported cards; an imported template applies to a page.
7. Legacy PNGs are left untouched (copy, not move) — delete only if the user asks.

`objects` columns: `id, type, name, parentId, createdAt, updatedAt, deletedAt, data`.

---

## Documents — deferred items

Punted from the document-storage work (2026-07-29/30, `docs/documents.md`). The feature ships
per-page and notebook-only; each item below is a deliberate omission, not an oversight.

- **Notebook-level / multi-page documents.** Per-page storage composes into one later (text export
  already concatenates pages) with no storage change.
- **Documents on Scratch Pad / Calendar day pages.** Those pages live in the global index's
  `scratchpad` / `calendar` tables, which would each need the `srcUpdatedAt` column added.
- **Sticky-note text in the seed draft.** `PageTextRepository.loadPageContent` does not read sticky
  contents; changing that belongs to recognition, not to documents.
- **Editing a document from outside an open notebook** (Page Index, MainActivity). The editor writes
  only through `NotebookActivity`'s connection, so the notebook is the only safe host today. A second
  writing connection to a live `.soil` is the shape of this project's worst data-loss bugs.
- **PDF / PNG export of a document.** Text formats only (MD/TXT prefer the document over recognized
  text; the raster paths still render the page's ink).
- **Lettered / roman-numeral ordered lists** (`a.`, `i.`). Decided against 2026-07-29: they exist only
  in Pandoc's `fancy_lists` extension, not CommonMark or GFM, where they are paragraphs — and
  consecutive lines get *joined*. Rendering them as lists in-app would send documents out as
  run-together paragraphs everywhere else, which is the opposite of what a pre-export surface is for.
  Revisit only if the export target becomes Pandoc; the change would be localized to
  `MarkdownFormatter.listEnter` / `renumberOrderedLists` and the parser's ordered-item regex.
- **A durable undo for "bring in page text".** In-session Ctrl+Z only (the refresh is applied through
  the buffer, like a format-bar edit); beyond the session, the confirmation dialog is the guard.

## Proofread — deferred items

Punted from the proofread work (2026-08-11/12, `docs/proofread.md`). Each is a deliberate scope
cut, not an oversight.

- **Other languages.** The engine is language-agnostic; a language means a frequency-dictionary
  asset (gzipped `term frequency` lines — remember the `.dict`-not-`.gz` AAPT trap) plus a way to
  choose it. The grammar rules are English-only and would simply not run for other languages.
- **Review-stepper mode.** A "walk the flags" pass — jump flag to flag with the popup open,
  fix/ignore/next — for proofreading a finished document in one sweep instead of hunting
  underlines by eye.
- **Reuse in `TextEditDialog` / sticky notes.** `core/proofread` is pure Kotlin and already
  host-agnostic; the cost is per-surface integration (underline drawing, tap plumbing, debounce
  wiring — the `ProofreadController` pattern). Decided out of scope for v1: the document editor
  is where finished prose happens.
- **Session-durable ignores.** "Ignore for now" is session-only *by design* (a durable ignore is
  what Add to dictionary is for); revisit only if real use shows re-ignoring the same finding is
  a genuine irritation.

## Routines — an accidental last tick is irreversible

> Found 2026-08-04 while testing the Today dashboard. **Working as designed** (see
> `docs/tasks.md` → *Finished is final*); recorded because the dashboard makes it easier to trigger
> without meaning to, not because the rule is wrong.

Resolving a routine's **last open step** auto-completes the occurrence and rolls it forward. From that
instant the occurrence is immutable: `TasksRepository.reopen` returns `ReopenOutcome.LOCKED` for the
routine row and for every step inside it. So a mis-tap on the final step cannot be undone at all —
there is no path back, from any screen.

The rule itself is sound. Un-checking a step would leave a live step inside an occurrence that has
already spawned its successor, which is a worse state than the one it fixes.

**What the dashboard changes is the context around the tap.** `RoutineActivity` shows the routine, its
deadline, and the other steps, so "this is the last one" is visible before you touch it. The dashboard
deliberately shows steps *without* their routine row (no "4 of 5 done" progress line — see
`docs/today-dashboard.md`), so the final step looks exactly like any other row, and the only signal
that something irreversible happened is a toast **after** the fact.

Options, none decided:

- **Confirm on the last step only** — "This is the last step of *X*. Completing it finishes the
  routine." Cheap and targeted, but it puts a dialog on the one screen whose whole point is calm, and
  the confirm would fire on the legitimate path too (finishing a routine on purpose is the normal
  case, not the exception).
- **Make the rollover reversible** — the most principled, and there is precedent: `reopen` already
  withdraws a *task's* machine-generated successor by hard-deleting it, guarded on `maxSeriesIndex`
  so it refuses once the user has acted on later rows. The routine equivalent has to withdraw the
  successor routine **and** the steps `rollForward` copied into it, and only while none of them has
  been touched. Bigger, but it removes the sharp edge rather than papering over it.
- **Warn in the row's meta line** — e.g. "Weekly reset · last step". Zero interaction cost, no dialog,
  and it restores the context the dashboard removed. Weakest guarantee: it informs, it doesn't
  protect.
- **Accept as-is.** The behaviour is documented and consistent; a toast already names what happened.

Whichever is chosen, `ReopenOutcome.LOCKED` stays as the backstop for the paths that genuinely cannot
be reopened (a surface restore into an occurrence that completed while the user was away).

## A long event title truncates where a long task title wraps

> Found 2026-08-05 while testing the Today dashboard's Events section. **Pre-existing** — the same
> truncation is in the day window today; the dashboard only puts the two row styles side by side,
> where the inconsistency is obvious.

`item_event.xml`'s `tvEventTitle` is `maxLines="1"`, so *"Call the bank about the mortgage renewal"*
renders as *"Call the bank about the mortgage re…"*. `item_task.xml`'s `tvTaskTitle` is
`maxLines="2"` and wraps. On the dashboard both sit in adjacent bands, so a truncated event reads as
a different *class* of thing rather than a longer one.

Events are the odd one out, not tasks: the event row spends 72dp on a leading time badge plus a
divider before the title even starts, so it loses more width than any other row in the app — the one
place a second line is most needed is the one place it is refused.

- **Raise `tvEventTitle` to `maxLines="2"`.** One attribute, and it fixes the day window at the same
  time — the layout is shared, which is the point. Safe for pagination: `TodaySection` measures each
  cell rather than assuming a fixed row height, so a taller row simply takes more of the band and one
  fewer row lands on the page.
- **Give the dashboard its own event row.** Rejected on sight; `EventRowFormat` exists specifically so
  these two surfaces cannot describe an event differently, and forking the layout re-opens that door
  from the other side.

Check the meta line at the same time. It is already `maxLines="2"`, but a multi-day recurring event
(`Vacation · 4 Aug – 7 Aug · Every 2 weeks on Mon, Wed`) is the longest string the formatter can
produce and has not been measured on P2P, the narrowest Tier-1 device.

## Today dashboard — deferred by design

> Decided during planning (2026-08-04) and carried out of the retired `TODAY_DASHBOARD_PLAN.md`. These
> are choices, not omissions — see [`docs/today-dashboard.md`](docs/today-dashboard.md) → *What this
> screen is not*.

- **"Open on launch: Library / Today" preference** — the separable change that would make the
  dashboard the home screen. Kept out of the feature deliberately: as a sibling surface reached from
  the library, it leaves `BootstrapActivity`'s forwarding and the library's "implicit bottom of the
  stack" invariant untouched, and `MainActivity.reset` still means what it always meant. A launch
  preference has to answer what Back does from a dashboard that nothing launched, which is a question
  worth its own thinking rather than a flag.
- **No day-note or scratch-pad content preview.** Those stay jump buttons. Rendering a preview means
  decoding a page bitmap on a focus view, and the dashboard already declined that for notebook covers.
- **No weather, greeting, counts or streaks.** It is a focus view, not a metrics screen.

## Today dashboard — every row is inflated, not just the visible page

> Found 2026-08-05 in the Today dashboard's phase-6 review. Not observed biting; recorded because the
> planning note claimed "pagination is what bounds the cost", and that is not quite true.

`TodaySection.buildCells` inflates and measures a View for the **whole** result set before packing it
into pages. Pagination bounds what is *attached*, not what is *built*. A user with 200 overdue tasks
inflates 200 `item_task` rows on every refresh to display six of them, and `TaskDao.openDueBy` has no
`LIMIT`.

"Today" is small by construction for anyone keeping up, which is why this has not been felt — but a
long-ignored overdue tail is exactly the state a dashboard is supposed to help with, so the cost
arrives precisely when the screen is most needed.

Options, cheapest first:

- **`LIMIT` on `openDueBy`** with a "+N more" affordance into the Tasks screen. Honest and small, but
  it needs a design decision about what the overflow row says.
- **Build lazily per page.** `TodaySection` would keep the *data*, not the Views, and call `makeRow`
  only for the page being shown. That breaks the current packer, which needs every cell measured up
  front to know where pages break — so it means measuring a representative row and accepting
  estimated breaks, losing the exact fit that took two bugs to get right.
- **Cache the built rows across refreshes**, keyed on row identity. Helps the check-off path (already
  narrowed to `refreshTasks()`), does nothing for the first paint.

## Landscape — three findings, parked

> Found 2026-08-05 during the Today dashboard's Tier-1 device pass (MAX, P2P, G6). **Portrait is the
> target orientation on every device**, so none of this was fixed. Recorded with measurements so that
> whenever landscape does matter, the diagnosis is already done.

### 1. Horizontal window insets are dropped app-wide — controls hide under the nav bar

The one with real consequences, and **not** a dashboard bug. In landscape the navigation bar moves to
the *side* of the screen. Nothing applies the horizontal inset, so right-aligned chrome renders
underneath it and cannot be tapped.

Measured on P2P, dashboard in landscape:

```
navigationBarBackground   [1558,0][1648,824]
"Scratch Pad" button      [1560,84][1639,163]   ← entirely beneath the nav bar
Notebooks tab             ends x=1622           ← partly beneath it
```

`TopGuard.applyInsetPadding` passes the view's existing left/right padding straight through and only
ever sets top and bottom from the insets:

```kotlin
v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bottom)
```

**14 screens** call it, and `MainActivity` doesn't call it at all — it hand-rolls
`setPadding(0, bars.top, 0, bars.bottom)`, with the same omission. So the library's own bottom bar
(`btnMore` at the far right) is exposed too.

The fix is two lines — `bars.left` / `bars.right` in both places — and it repairs all fifteen at once.
Note that `applyInsetPadding` documents itself as *"preserves any horizontal padding already set"*, so
check no caller is relying on that before overwriting it.

### 2. The tabbed variant has almost no room in landscape

P2P, landscape, Tasks: **one row per page, `1/19`**. The packer is doing the right thing — there is
simply no room:

```
band 276px · "Overdue" header 40px · row 128px
header + 1 row = 168 ✓      header + 2 rows = 296 ✗ (misses by 20px)
```

The chrome is what eats it: toolbar, tab row, section header, pager. The section header row
(`Tasks  +`) is the only part that is pure duplication — on a tabbed device the tab row already names
the section — so hiding it there and moving its `+` into the top toolbar would buy back exactly the
row that is missing. That is also a small portrait win on the same devices.

### 3. The single-screen split is tuned for portrait

MAX in landscape gives roughly 40% of the height to the Notebooks band whether or not it has anything
in it, leaving Tasks and Events 3 and 4 rows. A fix means content-dependent band weights or a
`layout-land/` variant of the wide layout (which must carry an identical id set — see
`docs/today-dashboard.md`). Portrait on MAX and both orientations on G102 are unaffected.

## g-paper — MARKER live ink differs from its baked appearance on Ratta (deferred from Notesprout SN R3)

Reported in the Notesprout SN R3 eye check (2026-08-21): a MARKER stroke visibly changes when it
bakes. Documented engine behaviour, not a host bug — `StrokeStyle`'s mapping table gives Ratta no
semi-transparent live style, so live MARKER draws as `NEEDLE` (plain uniform line) and the baked
stroke is core's engine-independent semi-transparent flat-cap rendering. Live ink is defined as a
best-effort preview; the bake is the truth. A better live approximation (if the 0…31 Ratta pen-code
sweep offers one, e.g. a grey pen code) would be a g-paper change (`~/git/g-paper`, bump + republish)
— explicitly deferred out of the initial ratta arc by the user.

## Paper + Notesprout SN — R6 review findings accepted (not fixed) in the SN freeze (2026-08-22)

The Notesprout SN R6 `/code-review high` pass fixed its 10 top correctness findings in SN
(`apps/notesprout_ratta`, see RATTA_PLAN.md R6 Outcome). The following were **explicitly accepted**
— most are byte-identical in Paper, so a real fix is a family-wide change:

- **Paper twin of the damaged-index fix (the one worth doing):** `PaperIndex`'s probe-`Invalid`
  branch (`apps/notesprout_paper/.../PaperIndex.kt` ≈ line 68) still treats an existing-but-damaged
  `notesprout.db` (e.g. a truncated restore remnant) as a fresh install and creates a new encrypted
  index over it — the data-loss case SN now refuses with `PrepareOutcome.DAMAGED_FILE`. Port SN's
  guard when Paper is next unfrozen.
- StrokeCodec forward-compat/truncation gaps — codec bytes are the frozen family format; any change
  must land in Paper and SN together with fixture regeneration.
- `PRAGMA auto_vacuum = INCREMENTAL` in `SoilDatabase.onCreate` is a no-op (Room's onCreate runs
  after tables exist, so the pragma silently does nothing). Same in Paper; new files simply have
  auto_vacuum off, matching every existing file. Fix family-wide or drop the pragma.
- Case-sensitive sibling-name collision check ("Notes" and "notes" can coexist) — Paper parity,
  cosmetic; UUID filenames mean no filesystem conflict.
- Library perf niggles (SN): cover WEBP decode on Main in card bind, occasional double refresh on
  resume, `pinnedNotebookIds`' per-id `alive()` reads pull the full row (cover blob included), and
  `StrokeStore.commit`'s per-stroke `MAX("order")` query. All small at SN's data sizes.
- FolderPicker/Library breadcrumb logic duplication; dead API surface (`SoilCrypto.createRaw`
  unused by SN's create path, unused `SoilDao` methods) — cleanup, not correctness.

## Notesprout SN — N3 (arc 3) review findings accepted (not fixed) in the headings freeze (2026-08-22)

The arc-3 `/code-review high` pass (N0–N2 range) fixed 8 of its 10 confirmed correctness findings
in SN (see RATTA_PLAN.md N3 Outcome). Two were **explicitly accepted**:

- **`StrokeSegmenter` fragment-merge guard can fold a genuine short line into an adjacent full
  line** (`apps/notesprout_ratta/ext-mlkit/.../StrokeSegmenter.kt` ≈ line 129): the
  `minOf(sizes) <= 3` guard has no x-range/gap check, so a 1–3-stroke cursive line whose box
  overlaps a descender-inflated neighbour >40 % merges and interleaves both lines' ink. Affects
  `recognizePage` only, which has **no consumer in the shipped app** since N3 removed the debug
  "Recognize page" row (the heading flow uses `recognizeInk`). Tuning it blind risks regressing the
  real fragment cases it exists for — revisit with device data when `recognizePage` gains a
  consumer (page-text pipeline, documents).
- **`MarkdownParser` lets any `N. text` line interrupt a paragraph** (CommonMark restricts
  paragraph interruption to `1.`): `"…came out in\n1986. It sold well."` becomes a numbered list
  item. **og's parser behaves identically** (`isBlockStart` uses the same unrestricted
  `orderedItemRegex`), and og's two test suites are SN's locked behaviour reference — fixing SN
  alone would render the same document differently across the family. Fix in og first, then port.

Below-cap cleanup notes from the same review, recorded so they aren't re-found: host
`RecognizerClient.recognizePage` is now dead surface (kept — the AIDL contract retains the call for
future engines, same acceptance shape as R6's `createRaw`); `NotebookActivity` exceeds the
~800-line rule (written reason added to its class KDoc in N3); `ext-mlkit` logs via
`if (BuildConfig.DEBUG) Log.d` (module has no Slog; the gate satisfies the rule's zero-release-cost
intent); `Dialogs.problem` duplication in `RecognizerReadiness.showDownloadFailed`, dead
`problemTitleRes` default, `SelectionToolbar`/`NameDialog`-family duplication, per-draw markdown
parse in `HeadingRenderer`, mirrored `SELECTION_BOX_INFLATE_PX` constant (deliberate, commented).

## Notesprout SN — S2 (arc 5) review findings accepted (not fixed) in the naming freeze (2026-08-22)

The arc-5 `/code-review high` pass (S1 range) fixed 9 of its 10 findings in SN (see RATTA_PLAN.md
S2 Outcome). One was **explicitly accepted**:

- **`resolveScheme` is N+1 on top of `ancestry`'s per-hop walk** (`IndexRepository.kt`): for a
  folder D deep, D `summaryById` reads plus up to D+1 `namingRowAny` reads run sequentially in the
  + tap's pre-launch gap (bounded ~101 round-trips by the 50-hop cap; realistic depths are
  single-digit ms). Same family as R6's accepted library perf niggles. If it ever shows on device:
  one query — `WHERE type='naming' AND deletedAt IS NULL AND (parentId IN (:ancestorIds) OR
  parentId IS NULL)` — plus an in-memory nearest-first pick over the ancestry list.

Refuted-but-noted from the same review, recorded so it isn't re-found: the naming-row table has no
UNIQUE(parentId) constraint and `namingRowAny` is an unordered `LIMIT 1`, so two concurrent
`setScheme` writers *could* create twin rows — provably unreachable through today's click-guarded,
modal UI, and adding an index would touch the Room-validated schema (the format contract with
Paper). Revisit only alongside a family-wide schema change. Also refuted: Cancel-during-save window
(app-wide established pattern, sub-human-reaction window, no harm).

## Notesprout SN — K5 (arc 6) review findings accepted (not fixed) in the links freeze (2026-08-23)

The arc-6 `/code-review high` pass (K1–K4 range) fixed 9 of its 10 findings in SN (see
RATTA_PLAN.md K5 Outcome). One was **partially fixed, remainder accepted**:

- **Breadcrumb builders are hand-rolled in three screens** (`LinkPickerActivity`,
  `FolderPickerActivity`, `LibraryActivity` — `label()`/`crumb()`/`separator()` near-verbatim
  copies). The behavioural drift the review caught (the picker showed the *start* of a deep path
  instead of the current folder) was fixed in K5 by adding the library's
  `post { fullScroll(FOCUS_RIGHT) }`; the 14sp-vs-16sp size difference and the extraction of one
  shared `Breadcrumbs` builder (long-press as an optional parameter — the `NewFolderFlow` move)
  remain deferred. Worth doing the next time any crumb styling or tap-target rule changes, so the
  change lands once instead of three times.

- **No search in the link picker** — carried from K2 exactly as Paper deferred it (recorded at the
  arc's picker-modes decision; repeated here so the arc has one findings ledger).

## Notesprout SN — arc 11 (Scratch Pad) J6 review ledger (2026-08-25)

The J1–J5 arc-range `/code-review high` raised six items. Five were fixed in J6 (see RATTA_PLAN.md
J6 Outcome) and one was refuted. Two things are carried, neither of them SN bugs:

- **Paper carries the same `TRANSFER_MAX_CHUNKS` derivation bug SN just fixed.** SN inherited `34`
  = `ceil(MAX_TRANSFER_STROKES / TRANSFER_CHUNK_STROKES)` from Paper's shipped arc-6 values, and it
  is not an upper bound on what the chunker produces: a chunk also closes when the *next* stroke
  would cross the point cap, so a transfer inside both whole-transfer caps can chunk into more than
  34 and the drain reports a legal transfer as truncated, leaving ink on the pad. SN's constant is
  now computed from the other four (= 74, both close reasons counted) with three shape tests.
  `apps/notesprout_paper/` has the same constant, the same one-sided derivation, and the same test
  pinning it. Not fixed here — Paper is on `main` and out of this arc's range. Worth a small
  targeted fix the next time Paper's `:extension-api` is opened.

- **The pad/notebook colour clamp is asymmetric, and deliberately so.** The host forces inbound ink
  to opaque black; the extension does not clamp the colour of ink the host sends it. Recorded in
  `apps/notesprout_ratta/docs/extensions.md` § Boundary audit rather than "fixed": SN's ink is fixed
  black so the host has no other colour to send, the sender is signature-matched, and the untrusted
  direction is the one that clamps. Revisit only if SN ever gains colour ink — at which point the
  pad's fixed-tool rule changes too, and both belong in the same change.

**Refuted, recorded so it is not re-raised:** "the store binder's `pending` ThreadLocal leaks a
4 MiB `SharedMemory` because `DebugMenu.runStoreProbe` calls `getLarge` in-process, where
`onTransact` never runs." That probe was deleted in J3 — there is no in-process caller — and
`onTransact`'s `finally` already calls `pending.remove()`.

## Notesprout SN — arc 15 (Export) E3 review ledger (2026-08-27)

The E1–E2 arc-range `/code-review high` surfaced 28 unique verified candidates; the ten surviving
correctness findings were **all fixed in E3** (see RATTA_PLAN.md E3 Outcome). Carried here: the
items the review confirmed but cut under its output cap, accepted rather than fixed at the freeze.

- **`openDestination`'s plain-`"w"` fallback never truncates.** Providers differ on which write
  modes they accept, so `"rwt"` → `"wt"` → `"w"` is tried in order — but a provider that rejects
  the truncating modes, opens `"w"` in place over a *longer* pre-existing overwrite target, and
  answers **neither** `OpenableColumns.SIZE` nor `statSize` would leave the old file's trailing
  bytes after the new content, passing both size checks (the on-disk check is skipped when the
  provider won't answer at all). Three provider quirks have to coincide, and the local DocumentsUI
  path always accepts `"rwt"` — accepted. If picked up: a best-effort
  `FileOutputStream(pfd.fileDescriptor).channel.truncate(0)` after a plain-`"w"` open closes most
  of it.
- **`NotebookSession.refreshMeta` does not carry `exportedAt`/`appVersionCode` forward** — the next
  notebook open after an export rewrites `notebook_meta` without them, so the *Garden* file's
  export stamp is transient (every export re-stamps its own artifact, which is the copy that
  travels, so nothing user-visible is wrong). Worth aligning the two writers the next time
  `NotebookMeta` changes.
- **Cleanups cut under the cap, none behavioural:** `describe()` binds run sequentially at
  discovery (one exporter installed today); `Ready.bytes` duplicates `Ready.file.length()`;
  `ExportArtifact`'s cache copy uses `copyTo`'s default 8 KiB buffer where `:ext-soil` streams at
  64 KiB; `prepare()` hand-rolls a variant of the `readOnce` open→work→seal ritual (it needs the
  meta write, which `readOnce`'s read-only contract refuses); `versionCode()` is a third copy of
  the same helper; `ExportKeying`'s `plan`/`apply` split forces a nullable passphrase parameter.

## g-paper / Notesprout SN — a transferred selection drags worse than a hand-lassoed one (open, 2026-08-25)

**Symptom (user, Nomad, arc 11 / J6):** after a scratch-pad transfer in either direction, dragging
the resulting selection feels sluggish; an ordinary hand-lassoed selection drags smoothly. Not a
showstopper — the drag lands where it should, nothing is lost.

**Still open.** Two hypotheses were tested on the device and **both were disproved** — recorded here
so nobody spends the time again:

1. **The firmware dash trail painting under the app-drawn ghost.** `RattaPaperView` never overrides
   `onSelectionDragVisual`, so the `firmwareInkSuppressed` flip at drag start is never pushed to the
   firmware — suppression rests on `updateLassoDragHoverSuppress` winning the race from the hover
   stream (overlay law 3), with a down-time backstop whose own comment says it is "too late for this
   contact's first dashes". A `0.1.7` adding `override fun onSelectionDragVisual(active) { if (active)
   fullScreenDisable() else applyToolToFirmware() }` was built, published, pinned and installed:
   **user reported no change in behaviour.** The change was reverted (unproven engine changes do not
   ride into an arc freeze) — but **the gap it names is real** and is worth closing on its own merits
   the next time gpaper-ratta is opened: the base documents the hook, `OnyxPaperView` implements it,
   Ratta ignores it, and the `false` edge would also cover drag-cancel and dismiss-mid-drag, which
   never reach the existing lift-time `applyToolToFirmware`.

2. **Per-frame drag cost scaling with selection size.** `CanvasPaperView.onDraw` rebuilds every
   dragged stroke from raw points each frame (`StrokeRenderer.draw` per stroke in `dragStrokes`,
   main thread), so a rasterize-once drag layer looked like the fix. The measurement that suggested
   it was **confounded**: the fast drags were pen and the slow ones finger. Rasterizing the drag
   layer once at drag start is still a defensible optimization, but it is **not** established as
   this bug's cause.

**What the instrumented run actually measured** (temporary `DBG` logging in `lassoDragMove` /
`lassoDragFinish` / the Ratta suppress points; sample counts + throttled invalidate counts):

| drag | input | selected | sample rate | frame rate |
|---|---|---|---|---|
| transferred | pen | 1 | 432 Hz | 16 Hz |
| hand-lassoed | pen | 1 | 431 Hz | 16 Hz |
| transferred | finger | 10 | 47 Hz | 12 Hz |
| hand-lassoed | finger | 10 | 56 Hz | 13 Hz |

Reading: the repaint rate is flat everywhere (the 60 ms `LASSO_REFRESH_INTERVAL_MS` throttle caps it
at ~16 Hz), so the felt sluggishness is **pen/finger input sampling**, not frames. The pen samples at
~430 Hz and the finger at ~50 Hz — that gap is the EMR digitizer vs the touch panel and explains most
of the table. **The residual worth chasing is the matched finger pair: 47 Hz transferred vs 56 Hz
hand-lassoed, one sample each**, with the user confirming the transferred one still felt worse at
equal stroke count. Everything else is confound.

**Next step if picked up:** one controlled run — same ink, same stroke count, one drag each of
{pen, finger} × {transferred, hand-lassoed} — with the same instrumentation, to see whether the
finger-pair residual survives. A stylus contact logs `down inside box` and a finger does not, which
is the cheap way to tell the two apart in a trace. Note `dragStrokes` is emptied before
`onSelectionDragVisual(false)`, so a drag-summary log must read `selection?.strokeIds?.size`, not
`dragStrokes.size`.

## Notesprout SN — arc 16 "Import" I2 (2026-08-28): one review finding accepted + deferred items

The I2 `/code-review high` pass (arc range `e9101fb..HEAD`, 10 findings) fixed nine in SN —
including the two Replace-import data-loss paths (`placeInGarden` now swaps by one atomic
`rename(2)` over the live target with no fallback copy, and the same-device keying pass-through
now pays a whole-file `integrity_check`) — and refactored the two duplication findings into
shared code (`ExportKeying.exportAndKeyToPrimary`, `SoilStreams.streamCopy`). One was
**explicitly accepted, not fixed**:

- **Imported names can't be edited under `NameRules`' charset**
  (`apps/notesprout_ratta/.../importing/ImportNames.kt` + `library/NameRules.kt`): `ImportNames.clean`
  deliberately admits characters (parentheses, unicode) the typed-name charset
  (`^[a-zA-Z0-9_\-. ]*$`) forbids — mangling `Field notes (2)` at import would rename the user's
  notebook for no benefit, and that decision stands. The cost: any later *edit* of such a name in
  the rename dialog fails validation until the whole name is retyped in the restricted alphabet
  (confirming unchanged is a no-op and still works). Fixing it means deciding what the library's
  naming rules *are* for non-typed names (relax the charset? accept chars already present in the
  current name?) — a user-facing naming-scheme decision, not a patch. Raise it with the user
  before touching either side.

Deferred by the arc-16 wizard (not findings):
- **No open-with / share-to intent filters** for `.soil` on SN — the library Import button is the
  only entry this arc; a future arc may add the receive-intent path (og has one).

## Notesprout SN — arc 19 "Document" M11 (2026-08-31): review ledger + og upstream bugs

The M11 `/code-review high` pass (arc range `17b0b9f..HEAD`, ~20.5k insertions) confirmed 15
correctness findings + 6 cleanup items; the user chose **fix everything** and all 21 were fixed
(one candidate was refuted — the proofread double-sheet, blocked by the modal dialog). Nothing
was accepted-instead-of-fixed this arc. Ledger items that outlive the arc:

- **og carries two markdown-engine bugs SN now deliberately diverges from** (found at M11,
  verified byte-identical in og's `core/markdown/`):
  1. `MarkdownReflow`'s **join branch drops a hard break's two trailing spaces** — a wrapped
     line ending in an explicit Markdown line break loses it on reflow, and reflow stops being
     idempotent (`reflow(reflow(x)) != reflow(x)`). og's own class doc says trimming it "would
     silently delete the very thing this rule exists to protect".
  2. `MarkdownFormatter.toggleBlock` (og: the format-bar block toggles) **stamps the block
     marker onto blank separator lines** inside a multi-line selection — "alpha\n\nbeta" +
     numbered list → "1. alpha\n2. \n3. beta", an empty item the user never asked for.
  Both are fixed in SN's `:markdown` (pinned by test). Fixing og means porting the same two
  changes into `apps/notesprout_android/.../core/markdown/` — small, test-covered, worth doing
  next time og's markdown engine is touched.
- **`SoilDao.hasLiveDocument` blankness — accepted residual mismatch** (recorded in the query
  KDoc): the SQL TRIM set covers ASCII whitespace (space/tab/LF/VT/FF/CR) but not U+001C–U+001F
  or Unicode spaces Kotlin's `isBlank()` accepts; a foreign-written document row whose text is
  only those exotic characters would list an exporter that then refuses honestly. Nothing in the
  family writes such rows.
- **Deferred by the arc-19 wizard** (not findings): Page-Index-style selection-merge for the
  notebook document (SN has no Page Index; auto-merge + the Merge sheet cover it — revisit on
  demand); open-with/share-to for `.md`/`.txt` (the arc-16 single-entry lock stands); images
  beyond og's source-level placeholder.

## Notesprout SN — arc 21 "Tags" W4 (2026-09-01): the extension store is key/value

**The extension store should offer rows and columns, not just keys and values.** Raised by the
user at W4's phase start, examined, and **declined for arc 21** — W4 shipped on the blob. It is
recorded here because it is not a preference; it is the cause of several unrelated-looking things.

`IExtensionStore` (arc 11 / J2) is `get` / `put` / `delete` / `keys` over byte arrays, plus the
`putLarge` / `getLarge` ashmem pair. The file underneath is **already SQLite** — host-owned,
encrypted under the global key at `Garden/<pkg>.db` — so only the seam hides the fact. Every
extension since has therefore serialized its structure into values, and pays for it:

- **Tags** (`TagCodec`) hold what is plainly a relational model — tags, and assignments joining a
  tag to a notebook and optionally a page — in **one 4 MiB store value**. That is where
  `WORST_CASE_BYTES` comes from, why `MAX_TAGS` / `MAX_TAG_ASSIGNMENTS` / `MAX_TAG_CHARS` exist as
  numbers rather than as anything a user would recognise, and why W4 had to write ids in base64url
  (`CompactId`) to keep the caps the wizard set. A search merge decodes the **whole** index per
  query rather than asking a question of it.
- **The scratch pad** stores `pages` (one page id per line) and `page/<id>` blobs of encoded ink.
  Its user-visible **4 MiB page ceiling** — `ScratchDocument` tracking the exact encoded size and
  removing the stroke that would cross the line, behind a "page full" dialog — exists for exactly
  this reason and for no other.

The change would be an **appended** table facility on `IExtensionStore` (the compatible-append
recipe the interface's own KDoc already used for `putLarge`), the host implementing it over the
store's own SQLite file, and each extension migrating at its own pace. Nothing else in the family
needs to move. It would delete `TagCodec` and its arithmetic outright and lift the pad's ceiling.

Needs a fresh user decision and an arc of its own — it is a seam change every extension inherits.

**→ Decided 2026-09-01: this is Arc 22 "Tables"** (`apps/notesprout_ratta/RATTA_PLAN.md` § "Phases —
Arc 22"): gated parameterized SQL over extension-declared schemas, the KV API removed, no migration
of existing stores (wiped on open — `0.1.0-ratta` is unreleased). This entry closes at X5.

## Notesprout SN — arc 21 "Tags" W5 (2026-09-01): a restore screen

**W5 put every extension store into the backup set; it did not add a way to put one back.** The
user's phase-start call: W5 ships backup only, the manual copy-back is documented
(`apps/notesprout_ratta/docs/backup.md` § Extension stores), and a restore screen is deferred here.

Arc 17 shipped the same shape for the library itself — backup, no restore — because a single
notebook already comes back through arc 16's Import, every backup file being a self-describing
`.soil`. **A store has no such door**: it is not a notebook, no importer claims it, and recovering
one means a shell copy of `Garden/<pkg>.db` (plus its `-wal` if the backup carries one, both or
neither) with the app closed.

A restore arc would cover the whole backup folder in one screen — index, notebooks and stores —
and its hard parts are the ones arc 17 named and left: the aside-swap ordering, what "replace all"
means against a library that has moved on since the backup, and the fact that a store's ciphertext
is keyed to the device that wrote it (a restore across devices needs the source device's recovery
key, exactly as an encrypted import does). Needs a user decision on scope before it is planned.

## Notesprout SN — arc 21 "Tags" W6 (2026-09-01): pruning dead tag assignments

**Deleting a notebook or a page does not remove the tag assignments naming it.** Deliberate for the
arc, and correct as far as the user can see — nothing dead ever surfaces — but the blob grows and
nothing shrinks it.

Aliveness is answered at **query time** and never in the store. `SearchAssembly.rank` reads tags
*through* the index's own live notebook listing, so an assignment naming a deleted notebook is
simply never looked at; a page's aliveness is a different question with a different source (the
notebook's live page rows, which only the host can read) and `PageNumbers` answers it the same way.
The extension is not the side that knows: it holds ids, and the index that says which ids are alive
is the host's. That is the shape the seam wants — the extension owns tags, the host owns the
library — so a pruning pass cannot be a background job inside `:ext-tags`.

The cost is bounded and small: an assignment is 53 bytes, `MAX_TAG_ASSIGNMENTS` is 50 000, and the
budget is checked against the store's 4 MiB value. A library would have to delete tagged notebooks
for a very long time to reach it — and if it did, the refusal is a cap message about a number the
user has no way to see, which is the honest complaint against leaving this.

A pruning pass would be host-driven: hand the extension the set of live notebook ids (and, for the
notebooks it asks about, live page ids) and let `TagWrites` drop the rest under its own lock. The
open questions are when it runs (a backup pass? the arc-17 close purge? a Tags-screen visit?) and
whether removing a tag's last assignment may ever delete the tag — it may **not**, by the arc's
lifecycle rule, so a prune leaves tags behind on purpose. Wants a user decision on the trigger.
Note that the store-seam entry above would dissolve most of this: with real rows, a delete is a
`DELETE`.
