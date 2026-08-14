# Notesprout — Claude Code Project Intelligence

A handwriting-first, meditative notes app. Think paper, but smarter underneath. Built for e-ink
devices first (BOOX), expanding to iPad, Android tablets, phones, and web.

- **Slogan:** "Where thought has a place to grow 🌱"
- **License:** MIT · **Monorepo root:** `~/git/Notesprout`
- `apps/notesprout_android` — Native Android app (primary active codebase)

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
| Drawing engines (Onyx / **Ratta-Supernote firmware ink** / Generic), EPD rules, **pen-activity gate (palm vs. finger gestures)**, perf, committed-content RenderNode render model + neighbor prefetch cache + cover snapshots, templates, undo/redo. ⚠️ `RattaNotebookView` is a **sibling copy** of `GenericNotebookView` — a fix to shared logic (lasso, erase, gestures) must be applied to **both** files | [`docs/drawing-engine.md`](docs/drawing-engine.md) |
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

- `main` — stable release branch **and the current working branch** (v1.3 "Supernote" merged 2026-08-14
  in merge commit 0c4902f; v1.2 "Sapling" merged 2026-08-07 in merge commit 55f2905; v1.1 "Sprout" merged
  2026-07-25 in merge commit c118a7e; v1.0 "Seed" merged 2026-06-24). Until the next feature branch is
  cut, work lands here.
- `supernote` — archived (v1.3, everything merged to `main`; branch deleted local + remote, history
  preserved under the merge commit)
- `sapling` — archived (v1.2, everything merged to `main`; branch deleted local + remote, history preserved
  under the merge commit)
- `sprout` — archived (v1.1, everything merged to `main`; branch deleted local + remote, history preserved
  under the merge commit)
- `germination` — previous post-MVP feature branch (reference, not active)
- `seed` — archived (v1.0, all major features shipped in final commit cc2c7ca)

---

## Community Nomenclature

Release notes → Growth Logs · Bug fixes → Pruning · New features → New Branches ·
Contributors → Gardeners · README → The Soil · CLAUDE.md → The Soil for Claude Code
