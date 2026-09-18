# Notesprout — Claude Code Project Intelligence

A handwriting-first, meditative notes app. Think paper, but smarter underneath. Built for e-ink
devices first (BOOX), expanding to iPad, Android tablets, phones, and web.

- **Slogan:** "Where thought has a place to grow 🌱"
- **License:** MIT · **Monorepo root:** `~/git/Notesprout`
- `apps/notesprout_android` — Native Android app for BOOX and generic Android (the original app)
- `apps/notesprout_sn` — **Notesprout SN**, the official Supernote version: a from-scratch host +
  extension-APK build over the g-paper Ratta firmware-ink engine (complete, on `main` since
  2026-09-11; its own `CLAUDE.md`, `docs/`, Gradle root, and module set — read that `CLAUDE.md`
  before any work there)
- `extensions/bible` — **NSE · Bible**, the Bible-reader extension for Notesprout SN (the ninth SN
  extension point, arc 37, 2026-09-13; built on branch `bible`, **merged to `main` 2026-09-13**, grown by arc 38 "Reference" the same day
  with notebook-linked scripture reference objects, by arc 40 "Verses" the same day (the verses
  themselves on the page as a linked text object), and by arc 41 "Cross references" 2026-09-14 on
  branch `crossref` (tappable parallel-passage references and footnote popups; the builder's
  Song-of-Solomon target bug fixed), and by arc 42 "Notes" 2026-09-14 on the same branch — **`crossref`
  merged to `main` 2026-09-15 (`--no-ff`) and deleted** (a
  personal-commentary side panel in the reader listing every notebook page or document that
  references the chapter being read, fed by a live host push plus a Rebuild door)): lives at the
  monorepo root and is included
  into `apps/notesprout_sn`'s Gradle root by `projectDir` — the pattern for every future extension;
  its own `BIBLE_PLAN.md` + `REFERENCE_PLAN.md` + `LOOKUP_PLAN.md` + `VERSES_PLAN.md` +
  `CROSSREF_PLAN.md` + `NOTES_PLAN.md` + `docs/bible.md`; read the SN `CLAUDE.md` before any work
  there
- `extensions/sketch` — **NSE · Sketch**, the raster-sketch extension for Notesprout SN (the tenth
  SN extension point, arc 43, granted 2026-09-15; built on branch `sketch`, arc 43 COMPLETE +
  FROZEN 2026-09-16, **merged to `main` 2026-09-16 (`--no-ff`) and deleted**): a per-page raster pencil
  sketch beside a notebook page's ink — chosen at notebook creation (a third Handwritten / Text /
  Sketch radio), toggled per page, stored inside the `.soil`, carried by page copy/cut/paste,
  exported as its own bundle page after the ink, and covered like everything else; the seam's held
  bind runs backwards (`ISketch.begin(host)` takes no store — the host mints and lends
  `ISketchHost` instead), grown by K5b the same day with page insert/delete and their own undo/redo
  from the sketch face itself, mirrored onto the notebook's own undo stack. The g-paper engine work
  (the Ratta pencil bake, the rubbing eraser, per-segment dirty rects) landed in `~/git/g-paper`
  first, on Fable's brief; `RattaTuning` removed at the freeze once its four measured values became
  plain engine constants. Lives at the monorepo root and is included into `apps/notesprout_sn`'s
  Gradle root by `projectDir` — the Bible pattern repeated; its own `SKETCH_PLAN.md` +
  `docs/sketch.md`; read the SN `CLAUDE.md` before any work there; grown by **arc 44 "Pencils"**
  2026-09-17 on branch `pencils` (six greyscale pencil shades, twelve lead sizes to 96 px, a 5 px
  black gel pen, all remembered on the device via API 19 — g-paper 0.1.36 + 0.1.37; then g-paper
  0.1.38 "Graphite on paper" 2026-09-17, the wide lead's grain fixed on the Manta; its own
  `PENCILS_PLAN.md`), and by **arc 45 "Ink"** 2026-09-17/18 on branch `ink` (a sketch is now two
  rasters — graphite for the pencil, ink for the gel pen and "Bring in ink" — flattened with
  `DARKEN`, the rubber touching graphite only because ink never erases; two `.soil` rows per page,
  `sketch_graphite` + `sketch_ink`, each a lossless WebP with alpha; no legacy; the seam moves to
  `API_VERSION` 20 with the action floor `MIN_API_VERSION_FOR_SKETCH` 17 → 20; g-paper 0.1.39; its
  own `INK_PLAN.md`; COMPLETE on the user's Nomad hand walk 2026-09-18, merge pending the user's
  word)

---

## Detailed Documentation (`docs/`)

CLAUDE.md holds the always-relevant guardrails. Subsystem detail lives in `docs/` — **read the
matching doc before working in that area:**

| Area | Doc |
|---|---|
| **Complete `.soil` format spec** — portable, self-contained; container invariants, full object catalog, binary encodings, encryption, export/import, backup, durability hardening. Written to hand to another project (Paintsprout) building a compatible container | [`docs/soil-file-format.md`](docs/soil-file-format.md) |
| **Complete global-index (`notesprout.db`) spec** — the companion to the `.soil` spec: `objects` table + all app tables, sentinel ids, v1→v11 migrations, index encryption & key lifecycle, backup/restore ordering, stores outside both DBs. Also written to hand to Paintsprout | [`docs/global-index-format.md`](docs/global-index-format.md) |
| Global index (`notesprout.db`) + `.soil` file rules, Room/WAL, template library — Notesprout-internal quick reference | [`docs/data-architecture.md`](docs/data-architecture.md) |
| Full e-ink design system, AlertDialog / IME patterns | [`docs/design-system.md`](docs/design-system.md) |
| Toolbar: base, overflow, full customization layer | [`docs/toolbar.md`](docs/toolbar.md) |
| Drawing engines (Onyx / **Ratta-Supernote firmware ink** / Generic), EPD rules, **pen-activity gate (palm vs. finger gestures)**, perf, committed-content RenderNode render model + neighbor prefetch cache + cover snapshots, templates, undo/redo, **hardware lasso trails** (BOOX `DASH`/`CHARCOAL` raw-driven + Ratta `LASSO_DASH`/`LASSO_X`), **EPD refresh handoff** — the device owns refresh except while a drawing surface is front-most; the app-scope handwriting pin is NAME-keyed EPD-service state that survives process death (leak = system-wide ghosting). ⚠️ `RattaNotebookView` is a **sibling copy** of `GenericNotebookView` — a fix to shared logic (lasso, erase, gestures) must be applied to **both** files | [`docs/drawing-engine.md`](docs/drawing-engine.md) |
| **Onyx pen-tool capability survey (research only — nothing planned)**: what the BOOX SDK offers beyond the one hardcoded pen — the two render paths (firmware overlay `setStrokeStyle` vs. the `NeoPen` software family already on our classpath), all 8 overlay stroke styles + 10 `NeoPenConfig` pen types with real defaults, pressure/tilt already arriving unread on every `TouchPoint`, how the pencil grain texture is actually built, and the BOOX-Notes-UI → SDK-constant mapping | [`docs/onyx-pen-tools.md`](docs/onyx-pen-tools.md) |
| Heading / Text (+ markdown) / Line objects | [`docs/content-objects.md`](docs/content-objects.md) |
| **Documents** — the page's authored Markdown (`DocumentEditorActivity`): the page is the draft, the document is the result. `document` row (`.soil` v5, `srcUpdatedAt`), seeded **once** from the page's recognized text and never overwritten by recognition again; "bring in page text" (Replace / Append) is the only path back in. **Notebook document** — one merged final draft per notebook (a `document` row parented to the notebook root, no schema change): header scope toggle in the editor (auto-merge seed with cancellable popup; blank-line page joins; strip Merge = Replace/Append), Page Index selection merge via the Export button's sheet, notebook-wide staleness (page ink **and** page-document edits), and an all-pages text export **Source** row that prefers it. **Text documents** — a notebook flagged (index `flags` bit 2 + `notebook_meta.textDocument`, no migration) to open straight into the editor: created from the new-notebook screen's type radio, text-preview cover (`TextCover`), editor gains close-to-library (✓ = show pages) + tap-title rename; `.md`/`.txt` import (Import sheet + open-with/share-to) creates one. Editor also has find & replace (`Ctrl+F`, selection-as-highlight, pure `TextSearch`) and word count for all documents. **Reflow** joins recognition's per-line breaks into paragraphs, keeping blank-line breaks. Lists continue on Enter and end on a second one; ordered runs renumber to match how they render (shared with `TextEditDialog`). **Page flips** in the editor; the notebook follows on return (never while it is stopped). Opens where the caret was left (per page, device-local; top when unknown). Image references are a source-level placeholder (`![alt](url)`, no picker; Preview shows the alt text). Saved text-size preference; the soft keyboard shrinks the layout — suppressed entirely while a physical keyboard is attached, **except on Ratta, where hardware keys type only while the IME is shown**, so a physical keyboard never hides it there (Supernote keeps the panel off-screen itself). The editor never opens the `.soil` — the notebook host reads and writes for it via `DocumentTransfer`. Export prefers the document over recognized text | [`docs/documents.md`](docs/documents.md) |
| **Proofread** — spelling + grammar in the document editor (only there): SymSpellKt + bundled gzipped dictionary asset (**never name an asset `.gz` — AAPT gunzips it and strips the extension**; VarCon-patched so **both spellings of every standard US/UK pair are accepted** — regen via `tools/proofread/patch_dictionary.py`), pure-Kotlin `core/proofread/` (engine, Markdown-aware tokenizer, line-bounded incremental check, five conservative grammar rules — silence over noise), thin `ProofreadController`, dashed (spelling) / dotted (grammar) underlines drawn in `onDraw` (spans are position-only markers), span diffing so an unchanged screen never repaints, tap popup (suggestions / Fix / Ignore / Add to dictionary), durable user dictionary in the global index (v11 `user_dictionary`; the editor is behind `IndexGuard` for it), global on/off default on — dictionary never loaded while off | [`docs/proofread.md`](docs/proofread.md) |
| Link objects: data model, chrome, follow, back-stack, lasso/undo | [`docs/links.md`](docs/links.md) |
| Scribble-erase, smart lasso, snap-to-guide, align & distribute | [`docs/lasso-and-gestures.md`](docs/lasso-and-gestures.md) |
| MainActivity features (browse/search/sort/export/ML Kit) + recents + launch restore (surface stack: a cold launch reopens the whole chain of screens the user had open) + **library chrome zones & bottom-bar width buckets** (`layout/`, `-sw360dp`, `-sw480dp`) | [`docs/mainactivity-and-recents.md`](docs/mainactivity-and-recents.md) |
| Encryption: SQLCipher model, scopes, key lifecycle, leak hygiene, migration, **data-loss defense** (never-delete-on-corruption, no-plaintext-open-of-encrypted, self-heal stale raw key, passphrase recovery) | [`docs/encryption.md`](docs/encryption.md) |
| **Export screen** (`ExportActivity` + `export/` — the single screen behind every export entry point: page scope, format, options, inline encryption, destination — incl. the **Google Drive destination**: direct upload to an app-owned "Notesprout Exports" tree via the backup Drive client, no share sheet, so it works on Supernote; folder picker creates nothing while browsing) + full-notebook export/import: `.soil` format, `notebook_meta`, copy engine, import pipeline (probe/unlock/placement/keying) | [`docs/full-notebook-export.md`](docs/full-notebook-export.md) |
| Global clipboard (persist across restart, encrypted-source warning) + cross-notebook page copy/move (template remap, smart encryption gate, source-side undo, nav prompt) | [`docs/clipboard-and-page-transfer.md`](docs/clipboard-and-page-transfer.md) |
| Backup: local (SAF) + Google Drive (REST API v3 + WebView OAuth PKCE), per-device subfolder, incremental-by-timestamp, index-last, pre-copy compaction + WAL-sidecar rule — **plus in-app restore** (staging-first, aside-swap, replace-all; restart into unlock) | [`docs/backup.md`](docs/backup.md) |
| Scratch Pad: data model, host window, canvas reuse, multi-page, lasso, both transfer directions, encryption note | [`docs/scratchpad.md`](docs/scratchpad.md) |
| Sticky Notes: data model, two coordinate spaces, editor transfer-singleton, on-page icon, tap-to-open, lasso/undo parity, create-flow, scratch pad parity, PDF footnote/endnote export, encryption note | [`docs/sticky-notes.md`](docs/sticky-notes.md) |
| Shape objects: data model, oriented box vs AABB, recognizer pipeline + as-built constants, dwell trigger (**currently disabled**) + gate order, transform mode, aspect/circle-oval toggle, lasso/clipboard/erase/export parity, host coverage, undo actions | [`docs/shape-objects.md`](docs/shape-objects.md) |
| Handwriting recognition: ML Kit single-shot + page-text pipeline (segmenter, `page_text` cache keyed on a page watermark **and** a pipeline `schema` — bump it when a pass starts reading something new; reads inside composites, not just layer children, RTR, viewer, text export) + **TrOCR personal engine** (settings toggle, ONNX Runtime Mobile, model bundles, engine-aware cache freshness, `tools/hwr/` Mac tooling, debug HwrLab) | [`docs/handwriting-recognition.md`](docs/handwriting-recognition.md) |
| **Today — the dashboard** (`TodayActivity` + `TodaySection`): a full-screen focus view of today and the jump point for the rest of the app. **No drawing surface, and the only edit is the task state box.** Owns no data — three existing stores asked three questions; no schema change, no migration. Two shapes from an **identical id set** (`sw600dp`: single screen · below: tabs). Per-section **prev/next-only pagination** that measures real rows against the real band (arrows never disable — silent on e-ink; the pager stays `INVISIBLE` so its space is never given back). Tasks = overdue + due today + **resolved today, kept in place** (state is not a sort key), routine **steps** shown standalone with their routine's name — the one place outside `TaskDao.MAIN_LIST`. Events = today only, **no look-ahead**, wording shared with the day window via `EventRowFormat`. Notebooks = today's activity then up to 10 recents, deduped, lock icons via one batched blob-free `locksFor` (never per-row `coverFor`); the three sections load concurrently. The `+` for a notebook leaves for the library's folder picker and is brought back by `EXTRA_RETURN_TO_TODAY`. **A two-finger swipe down jumps here** from the notebook, all three calendar views, all four views of the day window, and both task screens (not the scratch pad or sticky editor — focus surfaces) — `core/TwoFingerSwipeDown.kt`, one shared detector rather than a per-screen port, gated on pen-activity like every finger gesture and vertical-dominant so it can never collide with the notebook's horizontal two-finger page insert. **No host consumes the sequence**: an Activity-level swallow throws away Android's per-pointer split dispatch along with the swipe. Separately, **on the G102 a resting contact suppresses second-finger taps app-wide** (hardware reports both contacts; something above the driver drops them) — device behaviour, not ours, and a standing trap when diagnosing a missing tap there | [`docs/today-dashboard.md`](docs/today-dashboard.md) |
| **Tasks & Routines:** the to-do surface (`TasksActivity` + `RoutineActivity`) — independent of calendar and notebook. Fully columnar `tasks` table (v10, **no `data` JSON**) holding three row kinds (standalone task · routine · routine step) behind the `TaskDao.MAIN_LIST` predicate — the main list shows routines, **never their steps**. Materialized recurrence (one open row at a time; resolving generates the successor), next-due = first occurrence after `max(due, action day)`, **look-ahead reminder gates *Upcoming*** (a dated task with no reminder is not shown until it is due — the **All** view is the ungated escape hatch that keeps it reachable), Today/All/Done views (Events-style chrome; no date control — always relative to the real today, kept fresh by a date-change receiver; **Done is windowed to 30 days** + "Show N earlier"), Done/Skip/un-complete, three entry points. **Routines** are always-recurring collections whose due date is *derived* from the period (weekly → Saturday, monthly → last day, yearly → Dec 31), live for the whole period rather than reminder-gated, auto-complete when every step is answered and roll forward copying steps with their position in the period; **a finished routine is final** | [`docs/tasks.md`](docs/tasks.md) |
| Calendar: handwriting-first Month/Week/Day canvas, `calendar` table (v3) + keyed pages, template renderer (grid/timeline + hit-test), page-load contract, finger gestures (swipe/double-tap-to-open/multi-finger undo-redo; single-tap no longer selects), toolbar overflow, shared DayPickerDialog, last-position persistence, lasso/clipboard parity, Send to Notebook, full-view export (grid→page template + optional writing, toolbar top-margin), day-detail four-view "day window" (`DayDetailActivity`, default **Events**, back = exit to calendar: **Events** attached + recurring events, add/edit/delete + **Reminders** paper-like look-ahead (`events` table v5/`MIGRATION_4_5`, no notifications) · **Note** editable canvas `cal-daynote-` + copy-into-table templates · **Notebooks** Opened/Edited/Created card grid · **History** past-year picker + read-only day-note bitmap; `notebook_activity` log v4/`MIGRATION_3_4`, `DayHistoryRepository`) | [`docs/calendar.md`](docs/calendar.md) |
| **NSE · Bible** — the Bible reader for Notesprout SN (arc 37, 2026-09-13): the slim Berean Standard Bible SQLite build, the paginated print-look reader (paragraphs, poetry indents, section headings, superscript verse numbers), single-finger swipe with chapter/book flow, the **Contents** side panel (book rows → chapter grid, swipe down opens it), the **Recents** side panel on the right (the chapters picked by name — clock button, two-finger swipe down), the **Search** side panel (B8: one field — a reference goes there, words become a BM25-ranked hit list over the slim build's **FTS4** index; search button left of the clock), the stored last-read position + `recent` table, the `extensions/` monorepo-root module pattern; grown by **arc 38 "Reference"** (2026-09-13) — a lassoed or typed scripture reference becomes a linked passage object in the notebook, opening this reader on just those verses via two method-floored `IBible` tails (`resolve`/`beginAt`); **B9 "Send to notebook"** (2026-09-13) — with a notebook behind the reader, a far-right Send button lands the current chapter or passage on the page as a selected reference object (`takeOutgoingReference`, API 13); **arc 39 "Lookup"** (2026-09-13) — select a reference in the document editor (Write or Preview), tap **Bible** in the text-selection toolbar, the passage view opens over the editor and Back returns to it (`IDocumentHost.openReference`, API 14, the host's `BibleLookupActivity` trampoline — the stopped notebook can never launch the reader itself); **arc 40 "Verses"** (2026-09-13) — the verses themselves land on the page as a linked text object, not just a citation of them: the reader's Send, the reference dialog's "Insert the verses" pill, and the lasso bar's Verses on a placed reference all read a small passage's words (`IBible.passageText`, API 15, ten verses at most, whole chapters refused) and wrap them in a `KIND_BIBLE_TEXT` link — the same follow, an ordinary text-dialog Edit; **arc 41 "Cross references"** (2026-09-14, branch `crossref`) — the `r` parallel-passage lines' references are underlined and a finger tap opens the cited passage one screen further (Back returns to the chapter), a tap on a footnote `*` opens the note in a bordered popup with its own references tappable; the builder resolves a code-less USFM target from the display text's book (the Biblesprout 1 Peter 3 → 1 Peter 1 bug) and fails the build on any mis-booked target; **arc 42 "Notes"** (2026-09-14, branch `crossref`) — a personal-commentary **Notes** side panel in the reader lists every notebook page or document referencing the chapter in view, grouped per place written, kept live by a host push at every act plus a **Rebuild** door that walks the whole library (`IBible`'s seven notes tails, API 16, locked notebooks indexed but never prompted until followed) | [`extensions/bible/docs/bible.md`](extensions/bible/docs/bible.md) |
| **NSE · Sketch** — the raster-sketch extension for Notesprout SN (arc 43, granted 2026-09-15, branch `sketch`): a per-page raster pencil sketch beside a notebook page's ink on g-paper Ratta, opt-in per notebook (a third Handwritten / Text / Sketch radio at creation), toggled per page, stored inside the `.soil` (one PNG row `sketch` at arc 43 — two lossless-WebP rows `sketch_graphite` + `sketch_ink` since arc 45, the old name excluded from every read), carried by page copy/cut/paste, exported as its own bundle page immediately after the ink page, and covered by the last-shown page's sketch over its ink bake. The seam's held bind runs backwards — `ISketch.begin(host)` takes no store, and the host mints and lends an `ISketchHost` binder instead, the family's third host-side stub after `IDocumentHost`. Grown the same day by K5b: page insert/delete from the sketch face itself, and the face's own undo/redo of one such edit, mirrored onto the notebook's own undo stack so the two histories stay one. Fourteen locked decisions, compressed: the Paintsprout pencil as-is but baked at a **constant pressure 0.5 on Ratta** under a `DARK_GRAY` preview (the firmware paints one tone per pen — no live preview can track a soft touch); the rubbing eraser; `btnSketch` on the notebook's bottom strip, left of Bible, a further granted exception to "bottom bars are pager-only"; Erase page is ink-only, never the sketch; `MAX_BYTES` = 6 MiB is the one written-down hard refusal in the whole family (the SQLCipher cursor window). The g-paper engine work (the Ratta pencil bake, the rubbing eraser, per-segment dirty rects) landed in `~/git/g-paper` first, on Fable's brief, Opus writing it and Fable reviewing every diff before publish — `RattaTuning` removed at the K8 freeze once its four Nomad-measured values became plain engine constants (g-paper 0.1.34). Arc 43 COMPLETE + FROZEN 2026-09-16 on the user's own Nomad hand walks; **merged to `main` 2026-09-16 (`--no-ff`), branch deleted**; grown by **arc 44 "Pencils"** (2026-09-17, branch `pencils`) — the one pencil becomes six ladder shades (1·3·5·7·9·11, default 5) × twelve leads (1.2–96 px) beside a fixed 5 px black gel pen; Pencil · Pen · Eraser on the top bar, re-tap the armed Pencil for the `PencilBar`, the Pencil glyph filled with the armed shade; remembered on the device through two `ISketchHost` tails (API 19) in host prefs, never in the `.soil`; the Ratta preview maps each shade to the nearest usable firmware tone (g-paper 0.1.36) and the EMR ceiling lifted to 96 px (0.1.37); amends arc 43 decision 3; grown by **arc 45 "Ink"** (2026-09-17/18, branch `ink`) — a sketch page becomes two rasters, one picture: graphite and ink, flattened wherever the page is seen with `PorterDuff.Mode.DARKEN` (order-independent, so there is no top and bottom to explain), tools routed by style rather than colour, the rubber rubbing graphite only and never reading ink; stored as `sketch_graphite` + `sketch_ink`, each a page-sized lossless WebP with alpha, no legacy (existing PNG sketches are lost by the user's word); `readSketchChunk`/`saveSketchChunk` take a layer, `API_VERSION` 19 → 20, the seam's first single-point action-floor move (`MIN_API_VERSION_FOR_SKETCH` 17 → 20); g-paper Phase 26 → 0.1.39 on branch `two-rasters`; amends nothing, adds decision 1 "ink never erases"; COMPLETE on the user's Nomad hand walk 2026-09-18, merge pending the user's word | [`extensions/sketch/docs/sketch.md`](extensions/sketch/docs/sketch.md) |

Backlog at monorepo root: `BACKLOG.md` — consolidated deferred/future items harvested from the
completed-and-retired feature plans (toolbar Session-8 polish, multi-page Phase 2, encryption Phase 3,
link Phase 2, import/backup futures, the legacy-template ADB migration task, Supernote/Ratta deferred
items). Detailed retired plans live in git history (`SUPERNOTE_SUPPORT_PLAN.md` retired 2026-08-09 —
its substance lives in `docs/drawing-engine.md`). Standing design doc kept as-is:
`NOTEBOOK_SIZE_RESEARCH.md` (`.soil` size reduction + backup compaction research —
nothing decided/scheduled).

---

## Core Philosophy — Never Violate These

- Human-first: fixed screen-size pages, never infinite scroll
- Meditative, paper-like writing experience
- A coexistence of human and machine — intelligent underneath, calm on the surface
- Everything is an object (universal BaseObject model — relational, compositional)
- Pages feel like physical pages. The app should never feel like a web app.

---

## Standard Constraints

These apply everywhere — do not repeat them in feature sections.

- **Language:** Kotlin (Java 17 target — use Temurin-17 JDK; `org.gradle.java.home` in `gradle.properties` pins Temurin-17)
- **JSON serialization:** `kotlinx.serialization` only — zero reflection, code-generated. Never use `org.json`. Use `toJson()` / `fromJson()`.
- **No new Gradle dependencies** without explicit discussion.
- **No Material Components** — `com.google.android.material` is not a dependency; do not add it.
- **Never `runBlocking` on the UI thread** — ANR risk, especially on large stroke/snapshot data.
- **Every Activity that touches the global index calls `IndexGuard.ready(this)` first thing in `onCreate`** (`core/IndexGuard.kt`), and returns if it is false. `BootstrapActivity` is the only thing that opens the index and it isn't on the back stack, so a task Android rebuilds after a background process kill reads a closed database and throws. If the screen overrides `onDestroy`, open it with `if (IndexGuard.bounced(this)) { super.onDestroy(); return }` — that callback still runs on a guard bounce, and a `lateinit` teardown will crash on the way out.
- **No `Log.d` directly** — use `Slog.d(tag) { "msg" }` (`core/Slog.kt`, `inline fun` gated on `BuildConfig.DEBUG`). Release builds pay zero cost (lambda never evaluated). `Log.e` / `Log.w` survive into release.
- **Encryption:** every `.soil` open routes through `SoilCrypto`; passphrases are **never** logged, never put in Intent extras, never written to the global index. See [`docs/encryption.md`](docs/encryption.md). Global passphrase management and rotation live in `EncryptionSettingsActivity` (reachable from MainActivity's overflow). The one Phase 2 Gradle dependency is `com.tom-roush:pdfbox-android:2.0.27.0` (Apache-2.0) for password-protected PDF export — do not add further dependencies without explicit discussion.

---

## Architecture — Foundational Decisions

- Notebook = a `.soil` file (SQLite DB) at `getExternalFilesDir(null)/Garden/<uuid>.soil` — flat dir, UUID filenames, no permissions
- Folder/notebook structure lives **exclusively** in the global index (`notesprout.db`) — never derived from the filesystem
- **`soilFile(context, notebookId)` (`data/SoilFile.kt`)** is the single canonical way to derive a `.soil` path. No other code constructs one.
- Hierarchy: Notebook → Pages → Layers → Content Objects. Layers: base (template, locked) + content layers.
- Every object carries: id, parentId, boundingBox, order, createdAt, updatedAt, deletedAt, data
- Soft deletes only (set `deletedAt`); stable UUIDs everywhere
- Activities receive notebook identity as `EXTRA_NOTEBOOK_ID` + `EXTRA_NOTEBOOK_NAME` — never a `File` object
- Every `.soil` is **self-describing** via a single-row `notebook_meta` table (schema v3): id, name, folder ancestry, encrypted flag, and cover snapshot travel inside the file for portable import

Full schema, Room setup, and WAL/sidecar rules: [`docs/data-architecture.md`](docs/data-architecture.md).

---

## Design System — E-Ink First (Never Violate These)

**Palette (UI chrome only — no color, ever):** `inkBlack` `#000000` · `paperWhite` `#FFFFFF` ·
`inkLight` `#888888` (text meant **not** to be read — hints, disabled controls; anything carrying
information takes inkBlack and is made *smaller* to read as secondary) · `borderGray` `#CCCCCC`
(**invisible on e-ink** — use inkBlack for any visible border/divider).

- **Icons: Tabler outline only**, 24dp, `inkBlack` stroke 2, round caps — one visual vocabulary. Look
  before you download (~100 exist). Every icon button needs a long-press hint naming it (also its
  content description). Words read better than glyphs on e-ink, but **measure the row against the
  narrowest device first — P2P is `sw439dp`**; a control that falls off the edge is worse than one that
  must be learned. Details: [`docs/design-system.md`](docs/design-system.md).

- **Tap targets are dimen-driven — never hardcode a button size.** Icon buttons take
  `Widget.Notesprout.ToolbarButton`; anything hand-sized references `@dimen/toolbar_button_size` /
  `toolbar_button_padding`, and every chrome bar hosting buttons takes `@dimen/toolbar_bar_thickness`
  (`res/values/dimens.xml` 44/10/56 below sw720dp · `values-sw720dp` **62/14/70 on tablets** — matched
  to the Supernote native toolbar, ~9.8mm at 300ppi). A fixed-height row that holds a `ToolbarButton`
  clips it on the tablet tier; rows wrap or take the bar dimen. Full rationale (incl. why the tier
  boundary is 720, not 480): [`docs/toolbar.md`](docs/toolbar.md).

- **The one colour exception — ink, and only ink.** Colour appears in chrome *only where the colour
  itself is the thing being chosen or reported*: the pen-colour panel's swatches, and the pen button's
  icon tinted with the armed ink. Nothing else. Every render site routes through
  `core/InkColor.paintColor()`, which is what keeps that auditable. Adding colour anywhere else — a
  status, a highlight, an accent — is still forbidden. **Ink is never device-gated**: the panel offers
  a 16-level greyscale palette (the ladder e-paper actually renders) alongside the 16 colours, and
  greyscale is the default. See [`docs/design-system.md`](docs/design-system.md).

- No shadows, elevation, gradients, blur. No Material ripple (`rippleColor=transparent`, `stateListAnimator=null`).
- Animations none/minimal, never decorative (`android:windowAnimationStyle="@null"` in `Theme.Notesprout`).
- Borders 1dp solid inkBlack; corner radius 4dp. Typography: high-contrast black on white.
- Theme is `Theme.AppCompat.Light.NoActionBar`; buttons are `AppCompatButton` with explicit drawable backgrounds.
- **Source of truth — never hardcode:** colors `res/values/colors.xml`, styles `styles.xml`, theme `themes.xml`.

- **Top guard band:** no tappable chrome may sit against the top screen edge (on BOOX it pulls the
  status bar down instead of tapping). Always via `core/TopGuard.kt` — `applyInsetPadding()` where the
  system bars are visible, `applyRootPadding()` on immersive screens (their inset is 0, so the inset
  listener alone does nothing). Applies to tap targets only — canvases stay full-bleed, and chrome
  pushed off the edge needs its own 1dp inkBlack top border. **On Ratta (Supernote) the guard is 0**
  (`heightPx()` gates on `isRattaDevice()` — no status-bar hazard, chrome sits flush at the top edge).

Top guard details, AlertDialog styling + BOOX IME-dismissal patterns: [`docs/design-system.md`](docs/design-system.md).

---

Build variants, build/sign/install commands, and per-device serials/tiers: see the `device-build-install`
skill (`.claude/skills/device-build-install/SKILL.md`) — invoked automatically for build/install/device work.

---

## Branch Strategy

- `main` — stable release branch (v1.0 "Seed" through v1.3 "Supernote" shipped; the release merge
  commits are in git history).
- `ratta` — **merged to `main` 2026-09-11 (`--no-ff`, tag `notesprout-sn-0.1.0`) and deleted** —
  "on ratta" means `main`. It carried Notesprout SN, the official Supernote version, under
  `apps/notesprout_sn/` (renamed from `apps/notesprout_ratta` at the merge). Arcs 1–36 are all
  COMPLETE + FROZEN and `apps/notesprout_sn/PARITY_BACKLOG.md` is closed. That app's `CLAUDE.md`
  holds the maintenance protocol and the still-binding rules; `RATTA_PLAN.md` + the per-arc
  `<ARC>_PLAN.md` files there are history (plan + ledger — read for *why*, never resumed). **No
  NINTH extension point and no new arc without a user decision; no re-raising waived review
  findings.**
- `bible` — **merged to `main` 2026-09-13 (`--no-ff`) and deleted** (local + remote) — "on
  bible" means `main`. It carried `extensions/bible` (**NSE · Bible**), SN's ninth extension
  point: arc 37 (B0–B9), arc 38 "Reference", arc 39 "Lookup", arc 40 "Verses", all COMPLETE +
  FROZEN on the user's Nomad walks, plus the full-branch code review of 2026-09-13 (fixes in
  `extensions/bible/docs/bible.md` § Traps). The per-arc `*_PLAN.md` files there are history.
- `crossref` — **merged to `main` 2026-09-15 (`--no-ff`) and deleted** (local + remote) — "on
  crossref" means `main`. It carried arc 41 "Cross references" (tappable BSB cross references +
  footnote popups; the builder's mis-booked-target bug fixed) and arc 42 "Notes" (the personal
  commentary: a Notes side panel in the reader, the `note_ref` index in the Bible's store fed by a
  live host push + a Rebuild door, `API_VERSION` 16), both COMPLETE + FROZEN on the user's Nomad
  hand walks, no code review. `CROSSREF_PLAN.md` + `NOTES_PLAN.md` there are history.
- `sketch` — **merged to `main` 2026-09-16 (`--no-ff`) and deleted** (local + remote) — "on
  sketch" means `main`. Arc 43 "Sketch" COMPLETE + FROZEN 2026-09-16 on the user's final Nomad hand
  walk. It carried `extensions/sketch` (**NSE · Sketch**), SN's
  tenth extension point: a per-page raster pencil sketch beside a notebook page's ink, with the
  engine work (the Ratta pencil bake, the rubbing eraser, per-segment dirty rects) done in
  `~/git/g-paper` first (0.1.32 → 0.1.34) and `RattaTuning` removed at the freeze. `SKETCH_PLAN.md`
  there is history (plan + ledger — read for *why*, never resumed).
- `pencils` — arc 44 "Pencils" COMPLETE + FROZEN 2026-09-17 on the user's Nomad hand walks;
  **merged to `main` 2026-09-17 (`--no-ff`) and deleted** (local + remote) — "on pencils" means `main`. It carried the growth of
  `extensions/sketch` (**NSE · Sketch**) into shades, sizes and a gel pen, with the engine work in
  `~/git/g-paper` on branch `pencil-tones` (0.1.36 → 0.1.37). `PENCILS_PLAN.md` there is history
  (plan + ledger — read for *why*, never resumed).
- `ink` — arc 45 "Ink" COMPLETE + FROZEN 2026-09-18 on the user's Nomad hand walks. It carries the
  growth of `extensions/sketch` (**NSE · Sketch**) into two rasters — graphite and ink, ink never
  erased — with the engine work in `~/git/g-paper` on branch `two-rasters` (Phase 26 → 0.1.39).
  `INK_PLAN.md` there is history (plan + ledger — read for *why*, never resumed). Merge to `main`
  in either repo pending the user's word. **No ELEVENTH extension point and no new arc without a
  user decision.**
- `germination` — previous post-MVP feature branch (reference, not active)
- The former feature branches (`seed`, `sprout`, `sapling`, `supernote`, `paper`) are merged and
  **deleted** (local + remote) — "on sprout/sapling/supernote/paper" means `main`; history lives under
  the merge commits (`paper` = v0 "Paper" rebuild under `apps/notesprout_paper/`, merged 944d990; see
  `apps/notesprout_paper/PAPER_PLAN.md` and `apps/notesprout_paper/CLAUDE.md`).

---

## Community Nomenclature

Release notes → Growth Logs · Bug fixes → Pruning · New features → New Branches ·
Contributors → Gardeners · README → The Soil · CLAUDE.md → The Soil for Claude Code
