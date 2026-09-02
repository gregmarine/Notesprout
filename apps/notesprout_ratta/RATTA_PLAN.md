# RATTA_PLAN.md — Notesprout SN ("ratta paper")

**Branch:** `ratta` · **Location:** `apps/notesprout_ratta/` · **Package:** `com.symmetricalpalmtree.notesproutsn`
**Label:** Notesprout SN (debug: "Notesprout SN Dev") · **Version:** `0.1.0-ratta`
**This file is the cross-session memory for the effort. Read it first, whole, at every phase start.**

A from-scratch, Supernote-only rebuild of Notesprout in the spirit of the Paper experiment.
Original Notesprout (`apps/notesprout_android`) and Notesprout Paper (`apps/notesprout_paper`)
are **reading references — no app code is copied**.

**Arcs 1–22 are complete and frozen.** Their entries below are compact ledgers: status, what
still binds, and the reference doc. **The full phase-by-phase records (outcomes, findings,
walk logs) live in git history — arcs 1–18 at `git show 90a9198:apps/notesprout_ratta/RATTA_PLAN.md`,
arcs 19–22's full phase records at the end of this file until the next compaction** — and each
feature's authoritative reference is its `docs/` file. **Arc 22 "Tables" is complete and frozen
(2026-09-01) — X1–X5 all ✅: the extension store is real SQLite tables behind gated parameterized
SQL. Arc 23 "Calendar" is PLANNED (wizard locked 2026-09-01, § "Phases — Arc 23"
below) — Y1 ✅ (6a16017a) · Y2 ✅ (eaf8d8ce) · Y3 🧪 (awaiting the user checklist), next work = Y4. The SEVENTH point is granted and live; no EIGHTH without another decision.**

---

## Working protocol

1. **One phase per session.** At phase start: read this file, root `CLAUDE.md`, and
   `apps/notesprout_ratta/CLAUDE.md`. Confirm the next `⬜` phase with the user, flip it to
   `🔄`, then ask that phase's **Questions to resolve at phase start** wizard-style
   (one at a time) before writing any code.
2. **Model recipe (applies to every arc):**
   - **Fable** plans, orchestrates, reviews, and writes the genuinely complex code
     (crypto/key lifecycle, schema contracts, engine seams, tricky EPD behavior).
   - **Opus** for substantial feature implementation; **Sonnet** for scaffolding, layouts,
     resources, docs; **Haiku** for on-device adb test runs.
   - Background agents only for Opus/Sonnet/Haiku, **≤ 5 concurrent**.
3. **Testing gate:** JVM unit tests for all pure logic. Haiku device agents verify everything
   adb can see on the **Nomad** (SNN `SN078D10012852`). The user gets a **short numbered
   checklist** only for what needs a human eye/hand (live EPD ink, pen feel, lasso trails —
   the EPD overlay is invisible to screencap). Failures are fixed with the right model for
   the job, then re-tested.
4. **Devices:** Nomad only, unless the user explicitly asks for the Manta
   (SNM `SN100C10023972`). Never install anywhere else. The Manta identifies as a Nomad in
   every `ro.product.*` — serial is the only discriminator.
5. **Commit + push only when all tests pass or the user gives the all-clear** — and only
   after docs / memory / CLAUDE.md updates are in. Then the user runs `/clear`.
6. **Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification`
   · `✅ Complete (commit <hash>)`. Every phase records an **Outcome** note when it closes.
7. **g-paper gaps are fixed in g-paper** (`~/git/g-paper`): bump `GPAPER_VERSION`,
   `./gradlew publishToMavenLocal`, re-pin in **`sn-screen/build.gradle.kts`** (both
   artifacts — `:app` inherits via `api`). **The engine commit lands with the host commit**
   — a pin pointing at an uncommitted engine is a tree a fresh clone cannot resolve.
   Every review froze at level **high**; every arc has left the version at `0.1.0-ratta`
   (both stay phase-start questions).

## Foundational locks (arc-1 wizard — permanent)

| Decision | Answer |
|---|---|
| Data compatibility | **Format-compatible only.** Identical `.soil` / `notesprout.db` formats in SN's own `getExternalFilesDir`. Byte-for-byte with Paper: a Paper file passes SN's Room validation and vice versa (identity hashes pinned in R1). Additive row types are the only sanctioned growth (`heading`, `link`, index `naming`/`clipboard`/`template`/`template_folder`/`backup`). |
| Package / applicationId | `com.symmetricalpalmtree.notesproutsn`, debug suffix `.dev`, debug label "Notesprout SN Dev" |
| Rebuild depth | **Fresh code.** Paper v0 (`git show 87277da:apps/notesprout_paper/...`) and og are reading references; no file copying (build boilerplate exempt). |
| App icon | Tabler seedling **mirrored** (outermost group `scaleX="-1"`, pivot 54), black outline on white adaptive icon; all icons Tabler outline. |
| Crypto UX | Identical to Paper v0: `NSPT-` recovery key = the immutable global passphrase, attempt-limiter thresholds (1–2 free · 3–4 → 30 s · 5–9 → 5 min · ≥10 → 1 h), confusable-folding unlock, 450 ms "Preparing…". Unlock never hides the IME while the key field has focus (Ratta rule). |

## Architecture (current — after arc 22)

- **Own Gradle root** at `apps/notesprout_ratta/`. Gradle 8.14, AGP 8.11.1, Kotlin 2.2.20,
  KSP, compileSdk/targetSdk 35, minSdk 29, Java 17 via `org.gradle.java.home` (Temurin-17).
  Repos: `mavenLocal()`, `google()`, `mavenCentral()`. `android.nonTransitiveRClass=false`
  (load-bearing since J1 — undoing it breaks every moved resource reference).
- **Ten modules**: `:app` (host) · `:sn-screen`
  (shared paper-screen library — g-paper `api`, design resources, screen helpers; **a fix to
  shared screen logic goes there, never in a consumer**) · `:markdown` (arc 19 — the shared
  markdown engine, stdlib only, never depends on `:app`/`:sn-screen`/`:extension-api`; host +
  `:ext-document` both consume it, one engine, no drift) ·
  `:extension-api` (contract library, stdlib only) · `:ext-mlkit` · `:ext-scratchpad` ·
  `:ext-soil` (exporter + importer services, one APK) · `:ext-pdf` (module-local
  pdfbox-android 2.0.27.0) · `:ext-document` (**NSE · Document** — editor point + document
  exporter + text importer, one APK, three registrations; module-local SymSpellKt 3.4.0 + the
  bundled proofread dictionary) · `:ext-tags` (**NSE · Tags**, arc 21 — one service + a screen;
  the first tier-2 screen with **no paper**, so no g-paper call and no EPD handoff).
  Full table: app `CLAUDE.md` + `docs/extensions.md`.
- **g-paper pin: 0.1.23** in `sn-screen/build.gradle.kts` — `gpaper-core` + `gpaper-ratta`
  only. No Onyx, no jetifier, no pickFirsts, no `tools:replace`.
- **SIX extension points** (each was its own user decision — the SEVENTH, `ACTION_CALENDAR`, was
  granted 2026-09-01 for arc 23 and lands at Y1; no EIGHTH without another;
  arc 21's `ACTION_TAG_MANAGER` was the sixth's, granted 2026-08-31):
  `HANDWRITING_RECOGNIZER` · `SCRATCH_PAD` (+`_SCREEN`, tier-2 screen-owning) ·
  `NOTEBOOK_EXPORTER` (plural; soil + pdf + document) · `NOTEBOOK_IMPORTER` (soil + text) ·
  `DOCUMENT_EDITOR` (+`_SCREEN`, the second tier-2 — the first host-side callback stub,
  `IDocumentHost`) · `TAG_MANAGER` (+`_SCREEN`, the third tier-2 and the first with **no paper**
  on its screen; one interface serving both a held-bind showing and two bind-per-call methods).
  `ExtensionContract.API_VERSION` = **6**; the host accepts `minApiVersion(action)..6` — floor
  **6** for the three store-taking points (scratch pad, document editor, tag manager; arc 22 /
  X1, because `IExtensionStore` was *replaced*), floor 1 for the stateless ones; meta-data is
  **per service** — an extension declares what each service *requires* of the host.
- **The extension store is real SQLite tables (arc 22):** `IExtensionStore` v6 =
  `schemaVersion` / `applySchema` / `exec` / `query` / `next` / `close`; the extension declares
  versioned DDL once (`StoreSchema`), sends gated parameterized SQL (`StoreSql` validates: one
  statement, head keyword decides the kind, denylist, reserved `host_*`/`sqlite_*`/`room_*`/
  `android_*` names refused, positional binds only), and reads `StoreCodec` rows back in ≤ 4 MiB
  chunks. Host keeps the file (`Garden/<pkg>.db`), the key, the one connection (WAL, foreign keys
  ON), every cap, and backup; format rides `PRAGMA user_version` (`StoreFormat` 2; an arc-11 kv
  store is **wiped on open**, above 2 refused). No Room in the store. Schemas: `ScratchSchema.V1`
  (`page`/`stroke`/`state`) · `TagSchema.V1` (`tag`/`assignment`) · `EditorSchema.V1`
  (`prefs`/`word`/`caret` — `prefs` is the ONE extension table the host reads). Full seam:
  `docs/extensions.md` § the extension store.
  **A new point needs BOTH its actions in the host's `<queries>` block** or `queryIntentServices`
  answers `0 provider(s) of 0 candidate(s)` for a service that is installed, exported, signed and
  versioned correctly — it reads as a signature or version mismatch and is neither (arc 21 / W1).
- **Data model:** index `objects` table (user_version 1) + `Garden/<uuid>.soil` universal
  `notebook` table v1 + `notebook_meta`. Row types SN writes: notebook/page/template/stroke +
  additive heading/link/document (arc 19 — `flags` carries the document's source watermark;
  index flag bit 2 = text document). StrokeCodec format B; encrypt-by-default global key; SQLCipher stock
  defaults; `SoilFile.kt` the only path constructor (`extensionStoreFile` included);
  `SoilOpenFiles` = the one-file-one-connection rule; `SoilCrypto` the one crypto door;
  never-delete-on-corruption everywhere. References: `apps/notesprout_paper/docs/data.md`,
  `docs/crypto.md`.
- **Screens:** Bootstrap (only index opener, noHistory) → RecoveryKey / Unlock → Library
  (+ NewNotebook with inline template browser, FolderPicker, Templates, Backup, Export,
  Import flow) → Notebook (full-bleed paper, selection toolbar, Contents, Recents, links,
  clipboard, snap). Chrome rules, frame-silence ledger, gestures: `docs/notebook.md`.
- **Standing rules** live in `apps/notesprout_ratta/CLAUDE.md` (read it every phase start).

## Standing traps (learned 2026-08-20 → 2026-08-30 — assume they all still apply)

Device / adb:

- **Supernote swallows `adb shell input text` AND `input keyevent` letters** (PinyinIME).
  Typing = tapping the on-screen keyboard from screencap-measured coordinates (dialogs shift
  ~350 px when the IME shows; the IME window is invisible to uiautomator — measure from
  `screencap`). **adb cannot inject stylus ink, lasso, drags, or multi-finger gestures**;
  finger `input tap`/`input swipe` work. EPD live ink is invisible to `screencap` — verify
  committed strokes only.
- **`am broadcast --es text 'two words'` through `adb shell` delivers only the first word** (arc 22 /
  X4 — the outer shell's quotes are gone by the time the device's shell splits the line). The
  document editor's automation receiver reads `--es file /data/local/tmp/x.md` for exactly this;
  push the text and pass the path. The walk agent's "UNCLEAR: no spelling flag" was a one-word
  buffer, not a missing flag.
- **A SAF pick cannot be driven by adb** (G4 — DocumentsUI file items are inert to every
  injectable input). Every path that begins with choosing a file/folder is a user checklist
  item; agents verify up to the picker and inspect results via shell afterwards.
- **`adb push` into `Android/data/<pkg>/files/` fails with `remote fchown failed` AND deletes
  the target** — push to `/data/local/tmp`, then `shell cp` (`rm` target first). Pull is safe.
- Launch with `am start -n <pkg>/<FQCN>` and **verify `mResumedActivity`** before any
  screencap conclusion (`monkey` foregrounds unreliably — a whole walk once passed against
  Paper). `am start` onto an already-RESUMED activity fires **no onResume** — HOME first.
  Back at the library root exits the app.
- **Walk-agent false failures are the single most-fired trap** (~10 occurrences): tap aim,
  invented stories ("stale install", "broken row"), long-pressing a folder card (no Export
  row), walking the stale **release** install (give agents the `.dev` package id — the tell
  is `lastUpdateTime` predating the install). Re-drive any FAIL by hand before believing it;
  re-drive any UNCLEAR proof (a missing purge log) by hand too.
- A page sheet that is up has `releaseRender()`'d — a screencap can be **missing committed
  ink** that is plainly there once it closes.
- zipflinger inflates incremental debug APKs — clean build before chasing size. Ratta's Apps
  grid caches label/icon rows after `install -r` — cosmetic; Settings → Apps is fresh.
  Backing out of a live notebook *through the app* before installing keeps the EPD app-scope
  pin from leaking.

Code / correctness:

- **File tools can land a raw NUL byte** (fired 4×) — byte-scan changed files for `\x00`
  before calling any phase done.
- **ActivityResult callbacks run BEFORE `onResume`** — release round-trip latches at the
  **top** of the result callback (S2 regression; recurred in K3's shape).
- **Two handlers reading one piece of state: order the write after the read** (O2 popup
  toggle; J5 tool restore).
- **`java.util.Base64`, never `android.util.Base64`** (JVM-testable); no test against
  android.text classes under `returnDefaultValues` (they lie).
- **Writing order is load-bearing** (`LinkedHashMap` iteration, never a Set's): recognition
  reads ink as a sequence; restores `revive` in place, never tail-append. `"order"` is per
  parent **and type**; pasted sets rebase after max, relative order preserved.
- **Drain the shared `SoilWriter` before any capture/gather/raster** of the current notebook.
- **A 1 dp hairline at the Nomad's 1.875 density is a coin flip** — draw `round(density)` px
  rects on integer edges. `Stroke.bounds` is **point-tight**: the ink extent is bounds grown
  by width/2 (composite pad, placement clamp, underline band all learned this separately).
- **The 8 MiB `CursorWindow` caps any index blob read back** — clipboard cap 6 MB, template
  import cap 6 MiB, both pinned by tests that fail if raised past the window.
- **An elvis after `?.use { }` answers for the lambda's result** — a null-returning-by-contract
  call inside one (e.g. `decodeStream` with `inJustDecodeBounds`) silently inverts the check.
- **A chrome dimen names a part; a measured view names the whole** (snap margin = `topBar.height`,
  not the dimen). **A bar centred by `layout_weight` is centred on leftover space** — which
  changes with build type (DebugMenu) and caller (`btnSend`); centred = FrameLayout child.
- **A text-button style sets no `layout_width`** (arc 23 / Y2): `Widget.Notesprout.TextButton` and
  `LatchButton` carry padding and text only — unlike `ToolbarButton`, which sets both sizes — so an
  `AppCompatButton` on one of them needs its own `layout_width`. `processDebugResources` passes,
  `assembleDebug` passes, and the screen dies at inflate (`You must supply a layout_width
  attribute`). A resource-only agent cannot see this; the walk is what catches it.
- **`isPenActive` counts hover** — never idle-gate a dismissal/show that must answer a
  deliberate act; the frame-silence exceptions are ledgered in `docs/notebook.md`.
- **`showSoftInput` from `onResume` is dropped** (arc 21 / W2): a resumed Activity does not yet have
  window focus. The field ends up served and caret-ready at `mInputShown=false`, which reads as a
  broken keyboard. Raise it from `onWindowFocusChanged`, behind a once-per-showing latch — and still
  with the arc-20 **explicit flag 0**, never `SHOW_IMPLICIT`.
- **GONE, never disabled; not-built controls do not exist** (J4). Toast confirms / dialog
  explains; **a query is not a name** (don't reuse NameDialog strings for non-names).
- **A screen that explains itself and then leaves must leave on the dialog's DISMISS, never beside
  it** (arc 21 / W6). `Dialogs.problem` has **no dismiss callback**, so `problem(…)` followed by
  `finish()` tears the window down before the dialog is drawn: the screen flashes and vanishes with
  nothing said, which is the exact failure the explanation was added to prevent.
  `Dialogs.confirm(…) { finish() }` is the shape.
- **Size a timeout by the work, not by the call's chattiness** (arc 21 / W6, sharpening J5/D3). An
  `assign` that decodes, edits, re-encodes and writes megabytes cannot ride the same budget as a
  two-word call. And because **a Binder call cannot be cancelled**, a timeout does not undo
  anything — the orphaned call finishes and the write lands *after* the host has told the user
  nothing changed, so an honest budget is what keeps that sentence true.
- **A derived `get()` that a sort orders by is recomputed per comparison** (arc 21 / W6). On an
  immutable class make it a `val`; there is nothing for it to fall behind.
- **Dead code with a confident doc comment is worse than no code** (arc 21 / W6, six members
  removed): the comment asserts a role the function does not have, and the next reader believes it.
  `filterAlive`'s KDoc called itself "the query-time filter" when there is no query-time filter —
  staleness is answered structurally, by reading through the live listings.
- **Reuse before mint; render at the page's own size; `applyTemplate` never decodes; unknown
  kind stays unknown** (arc 12/13 template rules). Nothing ever soft-deletes a template row.
- **Bitmap hygiene:** cards RGB_565 (opaque, erased to white) — **`LinkComposite` must stay
  ARGB_8888** (drawn over live paper); thumbnail cache byte-bounded to hold **one whole grid
  page at the widest card** (re-check when a card grows); `toWebp` is lossy q100 — **measure
  encoders on-device via `WebpProbe`, never on the host** (Skia-specific behavior; grid is a
  known lossless-wins exception; q90 deliberately not taken).
- **`updatedAt` is sacred** — compaction, backup stamps, exclude toggles never bump it.
  Never delete a non-empty `-wal`; the WAL-alongside rule covers the index copy too.
- **A plausible mechanism is not a cause** (J6): two well-argued hypotheses both disproved
  on-device; measure the matched pair (same input device, same stroke count) before believing
  a story.

Extension seam:

- Only `SecurityException` / `IllegalArgumentException` / `IllegalStateException` cross a
  Binder stub — anything else kills the transaction **silently** and the caller reads an
  empty reply as success. Caller check runs **inside** the try whose `finally` closes the
  fds (E1). Constructor `requireValid` = unmarshal is the validation, both directions.
- **A Binder call cannot be cancelled** — size every timeout by on-device measurement, never
  guess (J5 `PLACE_TIMEOUT_MS`; re-measure when the operation's shape changes — D3).
- **Pre-open the extension store on IO before any bind** (cold KDF ≈ 3 s vs 114 ms warm).
- **Only `prepare()` may start a model download**; `status()` never blocks on the engine.
  Recognition area = the **selection bounds**, never the page (N2 "Heading"→"o").
- EPD handoff: the caller `releaseForHandoff()`s immediately before launch, the screen
  releases before **every** `finish()`, and the caller's reclaim must land **before** the
  departing window's close. A failure goes to g-paper, never a host workaround.
- **The host must not repaint from `onScribbleErased`** (arc 14 — the engine re-records
  itself; a host repaint costs two EPD frames showing a half-erased state).
- Keying transforms are **export-and-key, never `PRAGMA rekey`** (og on-device finding);
  `sqlcipher_export` drops `user_version` — copy by hand + re-verify; restamp
  `notebook_meta`; accept nothing without probing as its claimed kind + `integrity_check`.
- The verbatim `bytesWritten == streamBytes` equality is **soil's alone** — verification is
  per source kind (`ExportVerification`); `UNCONFIRMED` never deletes a destination.
- **The Keep `cmp` proof needs Keep to be the LAST export** (every prepare re-stamps
  `exportedAt`, and SQLCipher re-encrypts rewritten pages).
- Recognized/document text is never logged on either side — counts, lengths, durations only.
- Check og's `drawable/` before drawing a "fresh" icon — the vocabulary largely exists.

---

## Frozen arcs — ledger (full records: `git show 90a9198:apps/notesprout_ratta/RATTA_PLAN.md`)

### Arc 1 "Ratta Paper" ✅ frozen 2026-08-22 (R0 cf890a3 · R1 6820112 · R2 ca8347d · R3 d805f1f · R4 72afc92 · R5 4445744 · R6 7570770)
Full Paper-v0 parity: bootstrap/recovery/unlock, library (grid, folders, sort, pinned/recents,
rename/move/delete), notebook (g-paper ink, eraser, lasso move+delete, pages, gestures,
undo/redo bounded 100, covers). Byte-compat **proven both directions** on the Nomad (R6:
CLI-rekeyed files open in the other app). Still binding: Paper's gesture thresholds verbatim
(flip 0.30/0.50 × width + fling; vertical twins rotated 90°); default name `YYYYMMDD_HHmmss`
+ `NameRules` charset; no nesting cap (ancestry cycle-guarded at 50); R6's 7 accepted
findings live in monorepo `BACKLOG.md`. Refs: `docs/library.md`, `docs/notebook.md`.

### Arc 2 "Polish" ✅ P1 ee7337d (2026-08-22)
**Fixed tools, panels removed**: pen hardwired PEN · black · 3 px, eraser 15 px, smart lasso +
scribble erase ON, `ToolPrefs` deleted; second tap on an armed tool = no-op. Selection
context toolbar replaced the tap-in-box sheet; `core/OpeningOverlay` (pre-draw + post — the
view-VISIBLE-then-coroutine trap) wraps the library's one `openNotebook` door. Do not
reintroduce tool panels without a user decision.

### Arc 3 "Headings" ✅ frozen 2026-08-22 (N0 19775ed · N1 02e39d6 · N2 afbe89a · N3 d84273e)
FIRST extension point (`HANDWRITING_RECOGNIZER`, `NSE · ML Kit`) + the core markdown engine
(`core/markdown/` — og parser/renderer subset) + heading rows (`TYPE_HEADING`: text =
hash-prefixed markdown, `flags` = level 1–6 authoritative). Still binding: **en-US hardcoded**;
a heading ALWAYS has recognized text (no og null-text fallback); headings render below ink,
boxes free-growth, remeasured per device at load; **doodle-always-converts accepted as
designed** (ML Kit is forced-choice — do not re-raise a rejection heuristic); no un-heading
command (og parity); parser og-identical including its any-`N.` quirk (fix in og first).
Refs: `docs/extensions.md`, `docs/notebook.md` § Headings.

### Arc 4 "Contents" ✅ frozen 2026-08-22 (C1 063b7e3 · C2 f4d2d8d)
ToC over heading rows, core (no extension layer). Still binding: hidden when empty (button
GONE, swipe silent); row tap navigates **by page id** resolved at tap time (display numbers
stay the modal snapshot's); tree = Paper's (orphans attach, never dropped; 2000-entry cap);
one-finger swipe-down entry; the Contents dialog pushes **BLOCK_ALL** before its first frame
(the Ratta ink daemon draws beneath any window — small transient dialogs deliberately don't);
degrade-not-throw on gather failures. F1 later made the outline + availability reach through
links (two hops). Ref: `docs/notebook.md` § Contents.

### Arc 5 "Naming" ✅ frozen 2026-08-23 (S1 f475b66 · S2 2fc5635)
Name schemes as core (`TYPE_NAMING` additive index row, one per folder, null parent = root).
Still binding: scheme language v2 (Paper v1 + date-part/name tokens; **time parts declined**);
nearest-ancestor inheritance; `{n}` counts siblings in the creation folder; month/weekday
names from hand lists via Calendar indices, **never a formatter** (CLDR drift stalls
counters); naming never blocks creation (degrade to timestamp). Ref: `docs/library.md` § Name schemes.

### Arc 6 "Links" ✅ frozen 2026-08-23 (K1 ada8f09 · K2 8a55461 · K3 f116d38 · K4 aff9390 · K5 05c5d5e)
Link objects core: re-parent wrap model, Paper's v1 payload grammar byte-exact in `text`,
per-link chrome UNDERLINE/none, picker trio with page previews + heading page labels +
create-in-picker (scheme-prenamed), finger-only follow, persisted trail (cap 50, cleared on
fresh non-via-link open, swipe-up + both Backs walk it, every follow pushes), dead-target
dialog with Edit-link retarget. Still binding: **no nesting**; a link erases/copies whole;
`SoilDatabase.readOnce` is the one-shot open ritual — never hand-roll it; picker search
deferred (BACKLOG); underline = `round(density)` px rect, band from **box** bottoms
(+`withUnderlineBand` self-heal that only grows). Scribble immunity later REVERSED (arc 14).
Ref: `docs/links.md`.

### Arc 7 "Pages" ✅ frozen 2026-08-23 (B1 a4e3a10 · B2 · B3)
Whole-page copy/cut/paste on the **global clipboard**: additive index row `clipboard` at the
sentinel id, single sticky slot, `ClipEnvelope` JSON (Base64 stroke blobs, decode never
throws, **6 MB cap** pinned against the CursorWindow), cut = delete-now-undoable, paste
before/after sub-sheet, cross-notebook with template **content-dedupe** (kind + size +
byte-identical blob) and the `KIND_PAGE` → `KIND_NOTEBOOK_PAGE` link rewrite (self-page →
the new copy). Fresh UUIDs through one map; sizes verbatim (never resample). A failed paste
clears the row; a throwing write does not. Ref: `docs/clipboard.md`.

### Arc 8 "Objects" ✅ frozen 2026-08-23 (O1 bae18da · O2 7f008ea; g-paper 0.1.5)
Object copy/cut/paste on the same slot (`kind = "objects"`, one slot, kind wins). g-paper
0.1.5: `onPaperTapped` (stylus-only bare-paper tap while lasso armed = paste centred on tap).
Still binding: re-arm `Tool.LASSO` after Copy/Cut (a dismissed selection restores PEN — the
paste tap would ink); stroke rows have **no bounds columns** — translate via decode →
`translated` → re-encode; lasso popup (Paste/Clear) opens only when objects are held; order
**rebased not verbatim**; objects rewrite has **no self-page case** (no page travels);
pasted content lands selected. Ref: `docs/clipboard.md`.

### Arc 9 "Snap" ✅ frozen 2026-08-24 (A1 844b136; g-paper 0.1.6 b224a55)
Snap-to-guide lives in **g-paper** (`SnapEngine` — the engine owns every drag sample); host =
toggle + `SnapPrefs`. Still binding: drags only (a paste never moves itself); off by default,
remembered; margin = the **measured** `topBar.height` fed via `pushExclusions()`; guides =
og's twelve page + ten per content object (headings/links only — never strokes); 20 dp
threshold; `lassoDragFinish` reports the **snapped** delta. Refs: `docs/notebook.md` § Snap,
`~/git/g-paper/docs/api.md`.

### Arc 10 "Recents" ✅ frozen 2026-08-24 (T1 627b635)
In-notebook Recents switcher, mirrored right: clock button flush at the top bar's right, panel
= the ToC mirrored at **50 %** width (breakpoint shared, width forked), two-finger swipe-down
second entry, row tap = seal → open (the link-follow seal-then-launch). Still binding: the hop
**clears the trail** (a switch is not a follow — the wizard's "not cleared" was the error, the
code was right); close bump (`RecentsPrefs.touch()` once per screen); panel edges 2 dp; names
resolved from the index at gather, never stored in prefs; blob-free batch reads
(`aliveNotebooks`), never per-row `alive()`. Ref: `docs/notebook.md` § Recents.

### Arc 11 "Scratch Pad" ✅ frozen 2026-08-25 (J1 12fe218 · J2 cd8a918 · J3 c7c83b5 · J4 1187f29 · J5 cc0ba79 · J6 d31cb62+98a836e)
SECOND point (`SCRATCH_PAD`, first screen-owning / tier-2), `NSE · Scratch Pad`
(`:ext-scratchpad`), plus two structural moves: **`:sn-screen`** extracted (pure git mv +
`nonTransitiveRClass=false`) and the **extension store** (`IExtensionStore`, encrypted
per-package KV + ashmem large values — an extension writes nothing to disk, ever). The EPD
handoff between two paper surfaces in two processes worked first try, no engine change.
Still binding: the held bind is SN's **only** bind held across more than one call (the
operation is the showing); transfers are copies through the held service only — no ids, no
Intent payload beyond two booleans, coordinates 1:1; `TRANSFER_MAX_CHUNKS` is **computed**
(74 — the one deviation from Paper's shipped values: the inherited 34 was never a bound);
`ScratchStore.receive` writes ink before the page list (a refusal leaves nothing behind);
the pad opens no `.soil` and the notebook is **not sealed** behind it (it gives up the EPD
pipeline, not its data). Open non-blocker in `BACKLOG.md`: a transferred selection drags
worse than a hand-lassoed one — two hypotheses built and **both disproved**; read it before
re-theorising. Refs: `docs/scratchpad.md` (feature), `docs/extensions.md` (seam + store +
tier-2 recipe + boundary rows 1–5), `docs/sn-screen.md`.

### Arc 12 "Paper" ✅ frozen 2026-08-25 (P2 7067a10)
A page can change its template (long-press sheet → sub-sheet; this page only; undoable via
`Action.TemplateChanged`). Minted the template rules the traps section carries: reuse before
mint (`PageTemplate`, current id wins → re-pick is a true no-op), page's own size, apply
never decodes, unknown stays unknown. Index `templateKind` stays the birth record. Superseded
UI-wise by arc 13's full-screen browser; the rules stand. Ref: `docs/notebook.md`.

### Arc 13 "Stationery" ✅ frozen 2026-08-26 (G1 2ee39f3 · G2 ❌ · G3 bc9afec · G4 8485ee8 · G5 faec9e7 · G6 1d77cce)
Templates become a library: folders, true-miniature previews, SAF import/export, three flat
shelves (Pinned/Recents/Search), one `TemplateBrowser` serving Templates / New Notebook /
the page-template picker. Still binding: **two kinds and no third** (built-in = drawn paper,
static = imported pixels); **sentinels are not rows** (Blank, Default, the three papers —
hardcoded ids; any prune must exempt them by name); "Default" reserved at the root — checked
on create, rename, import AND move; paper identified by **token** (`LINED`/`DOTTED`/`GRID`
byte-exact, `IMG#<8 hex>` — digest covers the **fit mode**); imports PNG/JPEG/WEBP only
(no PDF — a dependency decision), downscaled to **1×** the page's long edge, **6 MiB** hard
cap, refusal a live path by design; built-in export dropped (sentinels inert) but built-ins
long-press to exactly **Pin/Unpin**; shelves never persist; deleting a library template never
touches a notebook (pixels were copied at apply). **G2 adjustable generators: built, shown,
ABANDONED — do not rebuild, do not re-raise without a fresh user decision** (its three
findings are recorded in the ledger's git history). Recorded non-fix: an imported-template
notebook's cover is blank until first close. Ref: `docs/templates.md`.

### Arc 14 "Scribble" ✅ frozen 2026-08-26 (S1 802ddc9; g-paper 0.1.23 fe4e71b)
A scribble erases headings **and links** (pin 0.1.6 → 0.1.23; `gpaper-ratta` byte-identical
across the 16 pencil versions between). Still binding: **link scribble-immunity is REVERSED**
(user call — a link erases whole, wrapped children and all); content decided by
**penetration** (≥ 14 dp of path inside bounds — `scribbleContentIds`, deliberately not the
eraser's touch rule); universal, no escape hatch (any `HitTarget` is erasable — future kinds
included free); one gesture = one undo (`Action.ScribbleErased`, forced the new
`onScribbleErased` listener whose default keeps old hosts working); the host never repaints
from that callback. Refs: `docs/notebook.md`, `docs/links.md`.

### Post-arc fixes F1–F5 ✅ (2026-08-26 → 08-27, outside any arc)
- **F1** — a wrapped heading is listed in Contents and names its page again: K1's "belongs to
  the link" reversed for the two *read* paths only (outline + page labels resolve through
  links in two hops; the availability gate reaches exactly as far as the gather). Editing
  ownership untouched.
- **F2** — user-directed chrome rearrangement: action buttons on **top** bars everywhere
  (library, templates, scratch pad), bottom bars pager-only, selection toolbar leads
  `Snap · Copy · Cut` and ends **Delete** (supersedes O1), templates shelf exit = head arrow +
  one ✕ that always leaves (supersedes G5's two-✕), `ic_snap`/`ic_notebook_plus` redrawn.
  See the FrameLayout-centring trap.
- **F3** — one-finger horizontal swipe flips every paginated list: flip rule extracted to pure
  `core/SwipeMath`; `core/ListSwipe` applies it to a **region** — observer-only, armed inside
  the grid/body only, **finger only**, bounds are no-ops via the host's own `goToPage`. The
  Contents/Recents **Dialogs feed the detector from the dialog's own `dispatchTouchEvent`**
  (an Activity never sees another window's touches).
- **F4** — Manta 3 cards/row: `values-sw960dp/dimens.xml` (`library_card_min_width` 320 dp) —
  the Manta is 1024 dp wide in the same sw720dp bucket the Nomad's 200 dp was measured for.
  One dimen feeds all three grids; the dimen only picks the column count; the sw960 file
  defines **only** its two dimens (per-name fallback keeps toolbar sizing in sw720dp).
- **F5** — RGB_565 card art + `toWebp` lossy q100, both measured on-device (`WebpProbe`);
  see the bitmap-hygiene trap. `LinkComposite` stays ARGB_8888 with a comment saying why.

### Arc 15 "Export" ✅ frozen 2026-08-27 (E1 c5fb23b · E2 · E3 e9101fb)
THIRD point (`ACTION_NOTEBOOK_EXPORTER`, generic + plural), `NSE · Soil Export` (`:ext-soil`),
`ExportActivity` (chooser collapses at one exporter; descriptor-rendered options). **The host
keys, the extension delivers via two fds** — everything touching a key runs host-side; the
spec carries no id, no path, no secret; the reserved `OPTION_KEYING` is host-*executed*
(Keep / New passphrase… / Remove encryption; typed secret in XML-static `saveEnabled=false`
fields, honest password-lost refusal on a rebuilt screen). `SoilOpenFiles` written down —
export refuses a held file; release is **handle-scoped** (the double-seal race). Conditional
destination deletion (the picker's overwrite confirmation hands back a pre-existing doc's
URI — delete only after the truncating open, report honestly). Destination-size answers are
**corroboration, not authority**. Entry: library long-press sheet only. E3 ledger + accepted
items: monorepo `BACKLOG.md`. A fresh-UUID index row cannot open an export (pages parented
to the original id) — the finding arc 16's remap answered. Refs: `docs/export.md`,
`docs/extensions.md` boundary rows 6–8.

### Arc 16 "Import" ✅ frozen 2026-08-28 (I1 ecf0443 · I2 20b6306)
FOURTH point (`ACTION_NOTEBOOK_IMPORTER`, the exporter's mirror; same `:ext-soil` APK, label
stays `NSE · Soil Export` — user declined a rename). Pipeline: library Import button (GONE
without an importer) → SAF (MIME union + `*/*`; extension-matched) → stream to
`cacheDir/import/` → probe → device-key-first unlock (`AttemptLimiter` bucket `"IMPORT"`) →
**always re-keyed to the device global key, no chooser** (pass-through still pays a whole-file
`integrity_check`) → untrusted manifest (`SafeImportId`) → three questions (id collision
Replace = in-place row rewrite / Keep-both; **one Keep-both answer is one answer** — a
following name clash takes the first free `… Copy N` silently; placement
create-only, planned-then-committed — folders created at COMMIT; soft-deleted ancestry
BLOCKS, never revives) → in-file **remap** (`NotebookRemap`) → **staged-rename Garden write**
(one atomic `rename(2)` over the live target, sidecars after, never a fallback copy) → index
row last. Shared cores: `ExportKeying.exportAndKeyToPrimary`, `SoilStreams.streamCopy`.
Accepted → `BACKLOG.md`: imported names vs `NameRules` charset (a naming-scheme user
decision). No open-with/share-to (single-entry lock). Refs: `docs/import.md`,
`docs/extensions.md` boundary rows 9–11.

### Arc 17 "Backup" ✅ frozen 2026-08-30 (K1 73d6490 · K2 7fb0aa2 · K3 c12d5d0)
Compaction + local backup, **pure core** (no extension — Drive backup is a future arc behind
its own capability-point decision; nothing scaffolds for it). **Purge at every close** (the
user's explicit call): `SoilCompactor` hard-deletes soft-deleted rows at seal (after
`writer.close`, before `db.seal`; cascade only from rows the purge deletes; templates exempt
both passes; `updatedAt` untouched; VACUUM iff deleted; never throws), `IndexCompactor` at
bootstrap (sentinels exempt by name; 256 KiB freelist floor; backup passes 0). Backup screen
(library bottom bar far left, Import right after — `[Backup] [Import*] … pager … [Templates] [⋯]`),
manual only, og-D8 stamps (stamp = the copied `updatedAt`; failed copies never stamp; a
**different** folder clears the stamp map), UUID filenames, ciphertext copy, index **last**
(purge → checkpoint → snapshot → probe → stream; WAL-alongside for both file kinds),
`SafBackupWriter` hand-rolled over `DocumentsContract` (`.part` → `.old` → rename; a lone
`.old` is the last good copy — renamed back, never swept), exclude = flags bit 1, debug →
`dev/` subfolder. Reopen **waits on the `SoilOpenFiles` claim** (`awaitClosed`, 15 s);
sidecar sweep before claim release, gated `openCount == 1 && claimWasOurs`. Replace import
clears the stamp and preserves the exclude bit. `ic_backup` = Tabler `archive` (K3 user
call). Ref: `docs/backup.md`.

### Arc 18 "PDF" ✅ frozen 2026-08-30 (D1 1844446 · post-D1 1a18036 · D2 ff71644 · post-D2 57e8413 · D3 08877b0)
Second exporter on the SAME point (`NSE · PDF Export`, `:ext-pdf`, module-local
pdfbox-android 2.0.27.0 — no new capability point). **The host renders, the extension
assembles**: `ExporterInfo.sourceKind` compatible tail (absent = `SOURCE_SOIL`;
`SOURCE_PAGES` = host-baked full-fidelity `PageBundle`, one page in memory at a time both
sides, caps refused before allocation; the tail holds only while the parcelable is the
TRAILING payload — `ExportSpec` must stay `export()`'s last arg); verification per source
kind; `ExportSpec.exportSecret` = the **one deliberate secret that crosses any seam**
(user-typed, export-scoped, opens no Notesprout data). Options: `OPTION_PAGE_TEMPLATE`
(host-executed) + `OPTION_PROTECT` (host-collected dual fields, extension-executed,
`setPreferAES(true)` — it was RC4 until D3, qpdf-verified after); passwordless PDF =
**silence** (user's call — no honesty line). `PdfAssembly` is pdfbox on BOTH paths (framework
`finishPage` held every raster — the OOM finding); pages = JPEG q100 streams, ~92 KB/page
(~6× D2 — deliberate, user accepted; JPEG quality is the knob if size matters); fsync through
a close-shield on the still-open fd, `fstat`-gated, **failing on a regular file's sync error**
(`SoilStreams` matched). Export chooser defaults to the **last-used** exporter (`ExportPrefs`,
written only at the OK verdict); checked-radio re-tap is a no-op; password-lost fails
**closed** (`armedAtTap`); `ExportActivity` has `keyboard|keyboardHidden` configChanges;
`API_VERSION` 2 with the host accepting `1..N` (`:ext-pdf` declares 2; others stay 1).
Export progress is a modal dialog (post-D2). Timings: ~200 ms/page assembly; `EXPORT_TIMEOUT_MS`
120 s covers ~400 pages. Refs: `docs/export.md`, `docs/extensions.md` §§ source-kind tail /
export secret + boundary rows 12–13.

### Arc 19 "Document" ✅ frozen 2026-08-31 (M1 45943f9 · M2 e828886 · M3 ced73b2 · M4 7822553 · M5 766a3a7 · M6 681d99e · M7 7ef2926 · M8 70e0218+1051cba · M9 62964e6 · M10 2fcc980 · M11 56f8975)
og's Documents as the FIFTH point (`ACTION_DOCUMENT_EDITOR` + `_SCREEN`, `NSE · Document`,
`:ext-document` — one APK, THREE registrations: editor + `SOURCE_DOCUMENT` exporter + text
importer) plus `:markdown`, the shared engine. The page is the draft, the document the result:
page documents (seed-once, Bring in, staleness), the full editor (two-process autosave — its
four-failure table re-derived across the boundary and hardened at M11), the notebook document
(scope toggle, auto-merge, `nb:<id>` key), text documents (create radio, straight-to-editor,
TextCover, rename, `.md`/`.txt` import), export (.md/.txt host-assembled + PDF-of-preview via
`SOURCE_PAGES`, `:ext-pdf` untouched), Proofread (extension-local og port, user dictionary in
the store). Still binding: **the host owns every `.soil` read and write**; `IDocumentHost` is
the only host-side stub (uid-gated per showing, revoke clears the session); text crosses only
chunked (`TextChunks`, `MAX_DOCUMENT_CHARS` 10 M) and every save names its `pageKey` (the
mode-routing guard, structural); nothing rides the screen's Intent; the store holds small state
only (`size`/`carets`/`proofread`/`dict`), never a draft; **flush-before-seal is enforced** —
every seal path joins the entry's `finish()` Job, the hooks' gate is `documentWritesClosed`
(never `closing`/`opened`), the teardown flush asks `current()` first and rides the saver's
push lock, failures park; the watermark park is consumed only after the commit hook returns;
blank-means-absent; staleness counts soft-deleted rows (the arc-17 purge wrinkle stands — do
not "fix"); export never recognizes; `API_VERSION` 3 is per-service; **two deliberate og
divergences** (reflow keeps a joined hard break; block toggles skip blank lines — og carries
both bugs, see monorepo `BACKLOG.md`). M11 review: high, 21/21 fixed (15 correctness + 6
cleanup; 1 refuted), version stays `0.1.0-ratta`. 1503 JVM tests/variant. Refs:
`docs/document.md` (the feature), `docs/extensions.md` §§ fifth point + boundary rows 14–18,
`docs/export.md`, `docs/import.md`, `docs/library.md`, `docs/notebook.md`.

### Arc 20 "Search" ✅ frozen 2026-08-31 (Q1 eae3673 · dialog revision 5b61116 · shelf bottom bar dcfc108)
The library's first search: folders and notebooks **by name**, from anywhere in the tree, fuzzy.
Core only — no extension, no schema change, no new dependency. Still binding:
**fuzzy is subsequence + ranking, NOT typo tolerance** (`core/FuzzyRank`; edit distance was offered
and **declined** — adding it needs a fresh user decision; the honest edge is that a *dropped* letter
still matches and a *swapped* one does not); ranking is EXACT > PREFIX > word-start > substring >
subsequence, then word-start hits, span, name length, name — **a total, stable order**, and its
subsequence pass walks **backwards for each character's latest feasible position, then forwards
preferring word starts** (a plain greedy is not a ranking); **the whole library always**, never
scoped to the current folder, so the DAO stopped filtering names entirely (`allAliveOfType`
replaced `searchOfType`'s `LIKE` **and** `allAliveNotebooks`); **folders first, then relevance**
(`SearchAssembly`) with **Sort GONE** in search; **a dialog asks and the shelf's title is the query,
quoted** (`NameDialog`, the template-browser shape — an inline top-bar field was built, walked,
reviewed and replaced on the user's call), the Search button **re-opens the dialog rather than
toggling the mode off**; the shelf is entered **only** by an accepted query, so there is one empty
state and a blank query is refused by the dialog in its own words; a folder tap **goes there and
closes the search**; every card carries its **parent folder's name** (folders included — names are
unique only per parent); **`BrowseMode.SEARCH` is never persisted in either direction** and the
query lives in memory only (prefs hold ids and enum names — a query is a name); **in every shelf,
Pinned and Recents included, the bottom bar's Backup / Import / Templates stand down** with the
create buttons (group-hidden — `ImportFlow` owns `btnImport`; pager stays). The template browser's
Search shelf runs on the **same matcher**, ranks sentinels and rows in one list, and hides Sort;
`data/template/TemplateSearch` is deleted. Review: high, 5/5 fixed (the two mediums were a
`clearFocus()` that does not drop focus — caret `Blink` as a 500 ms EPD repaint loop — and a
skippable `SHOW_IMPLICIT`; both retired with the field). Version stays `0.1.0-ratta`.
1511 JVM tests/variant. Refs: `docs/library.md` § Search, `docs/templates.md`.

### Arc 21 "Tags" ✅ frozen 2026-09-01 (W1 374ddc8 · W2 b748566 · W3 a4de84e · W4 a1a5031 · W5 bf30697 · W6)
Tags on **notebooks and pages** — the SIXTH point (`ACTION_TAG_MANAGER` + `_SCREEN`), `NSE · Tags`
(`:ext-tags`, the TENTH module), and the **third tier-2 screen — the first carrying no paper**, so no
g-paper call and **no EPD handoff** anywhere (arc 19 / M3's measured answer covers it). Still binding:
**one interface, two call shapes** — a showing is a HELD bind (`begin` → `configureShowing` → launch →
result → `end`) with the store lent once, while `snapshot`/`assign` are bind-per-call with the store
riding the call; **the extension owns the tag index** (one store key `index`, the whole `TagCodec`
blob — never a key per tag) and the host owns every entry point, the recognizer and the search merge;
**tag text and target labels cross on the bind, never in the screen's Intent**, and are never logged.
**Every assignment names its notebook**, a page tag also its page (`Assignment(tagId, notebookId,
pageId?)`) — a page tag could not otherwise be traced to a notebook, because the index holds folders
and notebooks only and a first-ever `.soil` open costs a KDF; a present `pageId` **is** what makes it
a page tag, so no kind is stored. **Every cap the wizard set was kept and the record shrank instead**,
twice (W1 dropped the stored `identityKey`, W4 the encoded kind; ids are compact base64url via
`CompactId`) — `WORST_CASE_BYTES` 3 650 007 against 4 MiB, pinned by test. `TagWrites` is the ONE
read-modify-write, reading fresh inside its lock and answering a typed `Reason`. **`API_VERSION` 5,
the first bump that is NOT a compatible tail** (W4 reshaped `TagShowing`) — it fails loudly and only
the tag service's declaration moved. Identity = trim + collapse + case-fold, display is the
first-entered casing, 64 chars, tabs/newlines **dropped not escaped**; a tag **persists until
explicitly deleted**, and deleting an assigned one confirms naming the blast radius. **Aliveness is
structural** — the merge reads tags through the library's own live listings; there is no filter and
W6 removed the one that had no caller. Four doors, all GONE without the extension: the library sheet's
Tags… row (notebooks only), the notebook bar's three icon-only buttons (gated on `canvasShown`; MANAGE
is an overview you **drill into**), the lasso's Tag (**exactly one heading → silent + toast, or ink
with no content objects → recognize**; a mixed selection and a lone link get **no button**; the
recognizer is not gated on), and search (one query over names AND tags, page hits as their own cards).
Backup grew to **every `Garden/<pkg>.db`**, unconditionally every pass, the index's snapshot-and-probe
treatment, after the notebooks and before the index — **no restore** (manual copy-back documented).
`Garden/` is enumerated in `SoilFile.extensionStoreFiles` and **only** there. No undo for tag
operations. W6 review: high, 6/6 fixed; version stays `0.1.0-ratta`; 1623 JVM tests/variant. Refs:
`docs/tags.md` (the feature), `docs/extensions.md` §§ sixth point + boundary rows 19–23,
`docs/library.md`, `docs/notebook.md`, `docs/backup.md`.

### Arc 22 "Tables" ✅ frozen 2026-09-01 (X1 df720360 · X2 7ac960f7 · X3 26b8d609 · X4 2fa9c4aa · X5)
The extension store stopped being a key/value seam and became **real SQLite tables behind gated
parameterized SQL** — not a seventh point, a change to the service every store-taking point already
receives. Still binding: **`IExtensionStore` v6 was REPLACED whole** (`schemaVersion` / `applySchema`
/ `exec` / `query` / `next` / `close`); the extension declares versioned DDL once (`StoreSchema` —
construction IS the DDL validator run, version 1..256; the host applies the missing steps each in
its own transaction with the version bump, keeps the applied version in its own `host_schema`
table, and **refuses a downgrade** with `STORE_SCHEMA_NEWER`); `exec`/`query` before `applySchema`
on a binder = `STORE_SCHEMA_UNAPPLIED` (structural — bind-per-call callers apply per call). **Every
statement is validated (`StoreSql`)**: one statement (ONE trailing `;` tolerated), the head keyword
decides the kind (`SELECT`/`WITH` for `query`; `INSERT`/`REPLACE`/`UPDATE`/`DELETE`/`WITH` for
`exec` — and a query cannot smuggle a write under `WITH`), the denylist anywhere in the token
stream (`ATTACH DETACH PRAGMA VACUUM CREATE DROP ALTER BEGIN COMMIT ROLLBACK SAVEPOINT RELEASE
REINDEX ANALYZE load_extension`), **every word or quoted identifier in a reserved space `host_*` /
`sqlite_*` / `room_*` / `android_*` refused**, positional `?`/`?NNN` binds only; DDL allows
tables, indexes, `ALTER … ADD COLUMN | RENAME`, `DROP`, `REFERENCES … ON DELETE`, and **no views,
triggers, virtual or TEMP tables**; object names are bare `StoreNames`, column names are the
extension's business. **Writes are batch-atomic only** (one transaction per `exec`, ≤ 10 000
statements, never held open across Binder calls; `exec` is `@Synchronized` per store); **reads run
to completion and cross in ≤ 4 MiB `StoreCodec` chunks** (inline ≤ 512 KiB, ashmem above — the
arc-11 `LargeValue` stays only as the chunk carrier; a row is never split; ≤ 4 open results per
binder; every parked chunk dropped on revoke; `StoreChunker` is codec arithmetic and lives in
`:extension-api`; `StoreReads.all` is the one loop). Caps: `STORE_MAX_INLINE_BYTES` 512 KiB ·
`STORE_MAX_VALUE_BYTES` 4 MiB · `STORE_MAX_RESULT_BYTES` 32 MiB · `STORE_MAX_ROW_BYTES` = the
chunk cap · `STORE_MAX_BATCH_STATEMENTS` 10 000 · `STORE_MAX_SQL_CHARS` 8 192 · `STORE_MAX_ARGS`
999 · `STORE_MAX_TABLES` 64 · `STORE_MAX_SCHEMA_STEPS` 256 / `STORE_MAX_STEP_STATEMENTS` 64 ·
`STORE_MAX_OPEN_RESULTS` 4. Every SQLite failure — a constraint violation included — crosses as
`IllegalStateException` (the three-exception rule stands). **Room left the store file**: a thin
`SupportSQLiteOpenHelper` over the same `SoilCrypto`/`KeyOpener` factories, still
`NonDestructiveOpenHelperFactory`-wrapped, WAL + `wal_autocheckpoint` + `busy_timeout`, cached for
the process and never closed; **foreign keys ON as a POOL setting** (`setForeignKeyConstraintsEnabled`
in `onConfigure` — WAL readers are separate connections, a PRAGMA in `onOpen` reaches one), so a
declared `ON DELETE CASCADE` cascades — and therefore **`INSERT OR REPLACE` into a table with
children deletes the children** (X2's `page` rule; restated wherever REPLACE appears). **Format
rides `PRAGMA user_version`** (`StoreFormat` 2 = tables): 0 → fresh create, 1 or a `kv` /
`room_master_table` → **wipe on open, no migration** (the user's call — `0.1.0-ratta` is unreleased;
logged as a kv row count, never a name; the file keeps its size — no VACUUM), above 2 → refuse, file
left as found (never-delete-on-corruption). **`API_VERSION` 5 → 6, the second break and the first
with a FLOOR**: `MIN_API_VERSION_FOR_STORE` 6 for `SCRATCH_PAD` / `DOCUMENT_EDITOR` / `TAG_MANAGER`
(`ExtensionContract.minApiVersion` / `accepts`), floor 1 for the stateless points — a replaced
interface breaks the old-extension/new-host direction too (a v5 pad on transaction code 1 lands on
a different method), and between X1 and each extension's phase that extension's doors were GONE on
the Nomad, deliberately. **The editor's `prefs` table is the ONE extension table the host reads**
(`DocumentContract` pins `prefs` / `key` / `value` / `size`; `DocumentPdfRender.editorTextSizeSp`
reads it through the host executor, no binder, only if file and table exist). **Deleted outright:**
the KV API (`get`/`put`/`delete`/`keys`/`putLarge`/`getLarge`), the `kv` table, `KvDao`/`KvEntity`,
`STORE_MAX_KEY_CHARS`/`STORE_MAX_KEYS`/`STORE_VALUE_LARGE`; `ScratchPageCodec`, `PageFullException`,
`SCRATCH_PAGE_FULL` and the pad's 4 MiB page ceiling; `TagCodec`, `CompactId`, `WORST_CASE_BYTES`,
`MAX_TAG_ID_CHARS`, `TagWrites`, `snapshot`, `IndexUnreadable`; `CaretMemory`, `UserWords`. **Per
extension:** the pad (X2) = `ScratchSchema.V1` `page`/`stroke`/`state`, an idempotent op log
(`Put`/`Drop` per stroke id, orders a high-water mark) split into ≤ 4 MiB / ≤ 10 000-statement
batches, reads planned by `LENGTH(blob)` into `BETWEEN` ranges — **no page ceiling**, `receive`
compensates a multi-batch failure, never `INSERT OR REPLACE INTO page`; tags (X3) = `TagSchema.V1`
`tag` (UUID ids, `identityKey` stored UNIQUE) / `assignment` (PK `(tagId, notebookId, pageId)`,
`''` = notebook tag, cascade), `assign` = two reads + ONE two-statement transaction with the caps
INSIDE the inserts and the id resolved by identity inside the statement, **the transaction is the
lock**, `ITagManager` v6's `snapshot` → paged `tags(store, offset)` (500) + `assignmentsOf(store,
ids, offset)` (1 000, ≤ 500 ids/call) with `TagPages.collect` the one loop, `TagIndex` moved into
`:ext-tags` as the screen's query-only model, `TagRules.isId` case-insensitive on purpose,
`SEARCH_TIMEOUT_MS` 10 s (Nomad 52–78 ms on a 2-tag index) / `ASSIGN_TIMEOUT_MS` 4 s; the editor
(X4) = `EditorSchema.V1` `prefs`/`word`/`caret`, `EditorStore` the one place SQL runs (schema
applied on every call — the binder is fetched per call), `EditorPrefs` the facade where every
exception is the default, `rememberCaret` one two-statement batch with `CARET_LIMIT` 100 bound,
`insertWord` `OR IGNORE`, no read-modify-write and no lock; the text importer and document exporter
services stay at 3. Extension-side tests are statement-shaped (SQL builders pinned for exact text
+ args through the real validator; fakes are statement recorders that APPLY their writes); the debug
"Extension store self-test" is the only on-device proof (Nomad: cold create ≈ 2.0 s, 5 000 1-KiB
rows ≈ 2.4 s, read back 2 chunks ≈ 0.9 s, legacy wipe ≈ 2.0 s). Backup/restore untouched (same
file, same key; a legacy-shaped store from an old backup wipes on open). **No code-review phase —
the user's call.** Version stays `0.1.0-ratta`; **1738 JVM tests/variant**. Refs:
`docs/extensions.md` § the extension store + the boundary audit, `docs/scratchpad.md`,
`docs/tags.md`, `docs/document.md`, `docs/backup.md`, `BACKLOG.md` (the closed W4 entry, the
rewritten pruning entry, the X2 file-size and X3 double-query notes).

---

## Verification (end of arc)

1. All JVM unit tests green (`./gradlew test` in `apps/notesprout_ratta`).
2. Debug + release builds compile; release signs with the debug keystore.
3. Haiku device agents on the Nomad: install, walk everything adb can reach, `logcat -b crash`
   empty; re-drive FAILs and UNCLEARs by hand.
4. Format compat holds (a Paper-created file still opens; identity hashes unmoved).
5. User checklist (short, eye/hand only).
6. Commit + push to `ratta` only after 1–5 pass or explicit user all-clear.

## Appendix — Build & install (Nomad)

```bash
cd ~/git/Notesprout/apps/notesprout_ratta
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # → app-release-unsigned.apk
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
adb -s SN078D10012852 install -r <apk>      # Nomad (SNN) — the only default target
# Manta SN100C10023972 — ONLY when the user explicitly asks
```

JVM tests: `./gradlew test`. g-paper changes: bump `GPAPER_VERSION` in
`~/git/g-paper/gradle.properties`, `publishToMavenLocal`, re-pin in
**`sn-screen/build.gradle.kts`** (both artifacts); the engine commit lands with the host
commit.

## Appendix — Reference map (read, don't copy)

| Topic | Reference |
|---|---|
| Paper v0 tree (arc-1 parity target) | `git show 87277da:apps/notesprout_paper/<path>` |
| Current Paper data/crypto docs | `apps/notesprout_paper/docs/data.md`, `docs/crypto.md` |
| og feature references | monorepo `docs/` (documents.md, proofread.md, full-notebook-export.md, backup.md, scratchpad.md, clipboard-and-page-transfer.md, links.md) |
| g-paper API + host duties | `~/git/g-paper/docs/{api,architecture,host-responsibilities,integration-guide}.md` |
| Frozen arc phase records | `git show 90a9198:apps/notesprout_ratta/RATTA_PLAN.md` |

---

## Phases — Arc 23 "Calendar" (planned 2026-09-01, wizard complete)

**Status: Y1 ✅ · Y2 ✅ · Y3 🧪 · Y4 ⬜** (wizard locked 2026-09-01; Fable planned it and writes Y1).

A basic writable calendar, the way a physical organizer is one: **Month, Week and Day pages**,
each a full writing surface whose strokes are recorded in the extension's own store — month ink
under a month row, week ink under a week row, day ink under a day row that owns **two** pages (AM
and PM, twelve-hour ledgers in 30-minute slots). og's calendar (`docs/calendar.md` at the monorepo
root, `CalendarActivity` / `CalendarTemplateRenderer`) is the reading reference for the three
layouts and the navigation; nothing is copied. **Not in this arc, on the user's call:** events,
tasks, reminders, the day window, history, day notes, calendar export, the Today dashboard. Each
may become its own extension later — a fresh user decision each time.

What it is in the seam's terms: the **SEVENTH point** (`ACTION_CALENDAR` + `ACTION_CALENDAR_SCREEN`,
granted by the user 2026-09-01 — **no EIGHTH without another**), the **fourth tier-2 screen-owning
point** and the **second with paper** (after the pad), and **two new modules**: `:ext-ink` (a
library — the pad's ink/store helpers, shared so the calendar is not a sibling copy of the pad) and
`:ext-calendar` (**NSE · Calendar**, the APK). The pad's rules bind it whole: an extension writes
nothing to disk itself; both transfers are copies through the held bind, never the Intent, never a
file; tools are the notebook's, fixed; the EPD handoff order is g-paper's to keep.

**Fable writes Y1 (the seam, the module split, the Month page).** Y2–Y3 are Opus-implemented on a
Fable brief with a Fable review, Sonnet-scaffolded, Haiku-walked. **Y4 is docs only — no
code-review phase in this arc, on the user's call.** Anything a phase meets outside this spec
(crypto/key lifecycle, an engine gap, a schema question) stops and asks the user.

### Locked decisions (arc-23 wizard 2026-09-01 — do not re-ask)

| Decision | Answer |
|---|---|
| Seventh point | **Granted explicitly** (the user's 2026-09-01 message). **Scratch-Pad-shaped tier-2**: the extension owns the calendar screen, its g-paper surface and every stroke in its own extension store; the host owns the entry doors and the held bind. No EIGHTH without another decision. |
| Identity | `:ext-calendar` · label **NSE · Calendar** · pkg `…notesproutsn.ext.calendar` · `ACTION_CALENDAR` + `ACTION_CALENDAR_SCREEN` · app icon Tabler `calendar` outline (check og's `drawable/` first). Depends on `:extension-api` + `:sn-screen` + `:ext-ink`, never `:app`. |
| Entry doors | **Both of the pad's**: the library top bar (after `btnScratchPad`) and the notebook top bar's right cluster (after `btnScratchPad`). One entry class serves both; the notebook door hands the EPD pipeline over first. Buttons **GONE** without a trusted calendar installed. |
| Store rows | **`period → page → stroke` + `state`**. `period(id, kind, date)` with `UNIQUE(kind, date)` — **no key prefix** (the kind column already says it); `date` is a plain ISO day for all three kinds (month = its first day, week = its Sunday, day = the day). `page(id, periodId, half, width, height, …)` — month and week own one page (`half` 0), a day owns two (0 = AM, 1 = PM). `stroke` = the pad's row (StrokeCodec format B). **Rows are minted on the first stroke, never on open** — browsing empty months writes nothing. |
| Week start | **Sunday.** Sun–Sat columns; week pages dated by their Sunday. Never the device locale. |
| Layouts | **og's three verbatim**: Month = Sun–Sat header + 6×7 **square** cells + a Notes band below · Week = 2×4 cells (7 days + 1 spare) + the same Notes band · Day = 24 half-hour rows per half, time labels in a left gutter. **One change:** Day rows take a **fixed row height with a slack band** at the bottom — og's height-proportional rows are a ledgered bug (`BACKLOG.md` "Calendar Day view — height-dependent geometry"). |
| Today mark | **Ring today's number only** (Month + Week). No selected-day border — nothing selects. The Day page's title names the date. |
| Gestures | Finger **swipe** steps the period (the notebook's swipe guards, `SwipeMath`) · **double-tap a day cell** on Month/Week opens that day's Day page (AM); double-tap does nothing on Day · 2-finger stationary double-tap = undo, 3-finger = redo · **no long-press**. All finger gestures pen-activity-gated as everywhere. |
| Tools + lasso | The notebook's tools, **fixed** (pen black at the notebook's width, eraser, smart lasso + scribble erase) — the pad's rule. Lasso bar: **Move (drag) · Send to Notebook (with a notebook behind) · Delete** (Delete last). **Both transfers ship**: calendar → notebook (top-bar Send = the whole page; selection Send = the lasso) and notebook → calendar (selection-bar button). |
| Send target | Notebook → calendar asks **first**, host-side: **Today, morning · Today, afternoon · This week · This month** (from the real today). The calendar opens on that page with the ink placed and lasso-selected, one undo step. |
| Open at | **Where you left it** — view + date + half from the store's `state` rows (the bookmarked organizer). First run: today's Month. |
| Shared code | **New library module `:ext-ink`** (`:extension-api` + `:sn-screen`, never `:app`): the pad's `ScratchInk` / `StrokeRows` / `ScratchBatches` / `ScratchReadPlan` / `ScratchDocument` / the stroke-level half of `ScratchUndo` move there under neutral names; the pad is repointed in Y1 with its tests. One copy, no drift. |
| Phases | **Four**: Y1 seam + modules + Month · Y2 Week + Day + navigation · Y3 notebook door + both transfers · Y4 docs/ledger/freeze. Per-phase Fable review of Opus code, per-phase gates, Haiku walks and user checklists all stay; **Y4 has no code review**. |

### Seam spec (planner-fixed — Y1 implements as written; Y2/Y3 deviations need a user decision)

**Contract additions (`:extension-api`):**

- `ExtensionContract`: `ACTION_CALENDAR` / `ACTION_CALENDAR_SCREEN`
  (`com.symmetricalpalmtree.notesproutsn.extension.CALENDAR[_SCREEN]`, the existing namespacing
  exactly) and **`API_VERSION` 6 → 7** — the calendar point is the version-7 event; record it in
  the constant's ledger comment. **The floor becomes per action:** `minApiVersion` answers **7 for
  `ACTION_CALENDAR`** (a point born at 7 has no older shape to accept), 6 for the three arc-22
  store-taking points, 1 for the stateless ones — a small map replaces the single set, pure and
  JVM-tested. **Every existing extension keeps declaring what it declares today and no door
  vanishes** — unlike X1, nothing about any existing interface changes. `:ext-calendar`'s service
  declares 7.
- **`ICalendar`** (AIDL) — `IScratchPad`'s four methods with the placement made a real type:

  ```
  void       begin(IExtensionStore store);
  void       receiveInk(in InkBundle chunk, in CalendarTarget target, boolean last);   // target on every chunk, as the pad's placement is
  InkBundle  takeOutgoing(int chunkIndex);
  void       end();
  ```

  Stub methods: `HostCallerCheck.enforce` first; only the three marshalable exceptions leave;
  `IllegalStateException("store unavailable")` is the one store failure, the pad's text.
- **`CalendarTarget`** (Parcelable, `requireValid` in the constructor = unmarshal validation):
  `kind` (`KIND_MONTH` 0 / `KIND_WEEK` 1 / `KIND_DAY` 2) · `date` (ISO `yyyy-MM-dd`, **already
  normalized** — a month's first day, a week's Sunday, the day itself; anything else is rejected)
  · `half` (0 / 1; **0 unless `KIND_DAY`**).
- **`CalendarDates`** (pure `object`, `:extension-api`, `java.time` — minSdk 29): `monthStart`,
  `weekStart` (Sunday), `periodDate(kind, date)`, `isNormalized(kind, date)`, `step(kind, date,
  half, forward)` (Month ±1 month · Week ±7 days · Day AM → PM → next day AM, and back), the
  Sun–Sat / month-name **hand lists** for titles (arc 5's rule: names from hand lists by index,
  **never a formatter** — CLDR drift). Shared by both sides and the host's target sheet, so the
  host never guesses the week rule. Dates cross and are stored only as `LocalDate.toString()`
  (ISO, `Locale.ROOT`-safe — og's Eastern-Arabic-digit lesson).
- Extras / results / caps: `EXTRA_CALENDAR_SEND_ENABLED` · `EXTRA_CALENDAR_OPEN_RECEIVED` ·
  `RESULT_CALENDAR_SEND` (the pad's three, mirrored). **Transfer caps and chunking are reused
  unchanged** (`MAX_TRANSFER_*`, `TRANSFER_CHUNK_*`, `TRANSFER_MAX_CHUNKS`, `InkChunks`).

**`:ext-ink` (library module, Y1):** `:extension-api` + `:sn-screen`, never `:app`; no manifest
components. Moves, with their tests and no behaviour change: `ScratchInk` → `InkWire` (wire ⇄
paper, the extension-side twin of the host's `TransferCaps` — the twin stays deliberate) ·
`StrokeRows` (row → stroke decode, dropped-not-lost) · `ScratchBatches` → `StoreBatches` ·
`ScratchReadPlan` → `StrokeReadPlan` · `ScratchDocument` → `InkDocument` (the `TreeMap` + op log +
`flushUntilClean` + `highWater`, taking its two stroke statements through a small `StrokeSql`
interface so each consumer's SQL stays its own and pinned) · the stroke-level actions of
`ScratchUndo` → `InkUndo` (`Draw` / `Erase` / `Move` / `Pasted`; the pad's page-level `Page`
action **stays in the pad**). `ScratchSql` and `ScratchSqlTest` are untouched — the pad's SQL
strings do not move. `settings.gradle.kts` + `docs/sn-screen.md`'s "what may live where" table
grow the module; the pad's `build.gradle.kts` gains the dependency.

**Extension side (`:ext-calendar`):** `CalendarApplication` (registers the Ratta engine — its own
process) · `CalendarService` (the `ICalendar` stub; parks the showing's store, target and inbound
ink under one monitor) · `CalendarSession` · `CalendarSchema.V1`:

```sql
CREATE TABLE period (id TEXT PRIMARY KEY, kind INTEGER NOT NULL, date TEXT NOT NULL, UNIQUE(kind, date));
CREATE TABLE page   (id TEXT PRIMARY KEY, periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE,
                     half INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL,
                     createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, UNIQUE(periodId, half));
CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                     "order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL,
                     style TEXT NOT NULL, blob BLOB NOT NULL);
CREATE INDEX stroke_page_order ON stroke(pageId, "order");
CREATE TABLE state  (key TEXT PRIMARY KEY, value TEXT NOT NULL);   -- lastView · lastDate · lastHalf
```

`CalendarSql` holds every string, pinned by `CalendarSqlTest`. Opening a page is one join
(`period.kind = ? AND period.date = ? AND page.half = ?`); no row = a blank page and **nothing is
written** until the first stroke lands, whose flush batch leads with `INSERT OR IGNORE INTO
period` + `INSERT OR IGNORE INTO page` (ids minted extension-side, UUIDs) and then the stroke
upserts — one transaction. **Never `INSERT OR REPLACE` into `period` or `page`** (REPLACE deletes
first and the declared cascades take the ink — X2's trap). Strokes: `INSERT OR REPLACE` /
`DELETE … WHERE id = ?`, idempotent, batched by `StoreBatches`, read by `StrokeReadPlan` ranges.
`page.width/height` = the page's minted size (this device's screen); the template is always
rendered **at the page's own size** and `setTemplate` stretches it into the page rect with the
ink, so a store carried to a different screen keeps grid and ink registered (the pad's 1:1 rule).

`CalendarGeometry` (pure, JVM-tested): page size + density + the two bar thicknesses → every
rect. **Rule: every dimension is width- or dp-derived; height slack goes to a band; nothing is a
proportional slice of the height.** Month: DOW header band, cell width = content width / 7, cells
square, 6 rows, Notes band = the remainder. Week: 2×4 cells, the Notes band the same height as
Month's. Day: gutter 80 dp, **row height a fixed dp constant** (24 rows), the slack band below.
`hitTest(x, y) → LocalDate?` for Month/Week cells only. `CalendarTemplate` paints a transparent
ARGB_8888 bitmap from the geometry (day numbers grey when out-of-month, today's number ringed,
1 px hairlines at `round(density)` on integer edges — the Nomad's 1.875 trap). Re-baked on every
navigation and on `onResume` (a date rolling over while the screen sits open is caught at the next
navigation — no receiver; a planner call the user may revisit).

`CalendarActivity` — the pad's shape from `:sn-screen` (full-bleed paper, `PageGestures`,
`PaperChrome` exclusion rects, `UndoRedoStack`, `SelectionAnchor`); caller check first statement
of `onCreate`; **top bar** `[Back] [Pen] [Eraser] [Lasso] … [Today] [Month] [Week] [Day] [Send]`
(Send only with a notebook behind; Month/Week/Day text toggles, the armed one shown as the notebook
shows an armed tool); **bottom bar = the pager and nothing else** (F2 + 2026-08-28): `[‹] [title]
[›]` centred on the screen — tap the title → the day picker. Titles from hand lists: "September
2026" · "Aug 30 – Sep 5, 2026" · "Tue, Sep 1, 2026 · AM". `Today` = today in the current view
(Day: AM/PM by the clock). Undo is **calendar-level, in memory, per showing** (the pad's rule) —
an action names its page and replaying one on another page navigates there first. Every page
leave, `onPause`, and every exit flushes the op log **before** the handoff (the pad's "Back awaits
the flush"). `state` rows are written on every navigation. Chrome: SN design system, TopGuard 0,
portrait, frame-silence rule with no new exception.

**Host side:** `CalendarClient` (`ScratchPadClient` shape — `open` / `send` / `drainOutgoing` /
`finish`) · `CalendarEntry` (`ScratchPadEntry` shape: both doors, busy guard, `OpeningOverlay`,
**pre-open the store on IO before any bind**, the notebook door's `releaseForHandoff()` immediately
before launch) · `ExtensionRegistry.calendar` · **both actions in the host's `<queries>` block**
(the W1 trap) · `ic_calendar` in `:sn-screen` if og has no usable one. Send-to-Calendar: the
selection toolbar's 8th button (ink-only selection, after the pad's), the target sheet
(`ActionSheetDialog`, four rows), `TransferCaps.withinLimits` **before any bind**, chunks with the
`CalendarTarget` on every one, the last with the placement budget (the pad's 10 s to start;
**re-measured on the Nomad in Y3** — a Binder call cannot be cancelled). Calendar → notebook: drain
on the still-held bind, sanitize (colour forced black), fresh ids, one `ObjectsPasted` step landing
selected, the "Pasted" toast. Backup: `Garden/<pkg>.db` is already in the set (W5) — nothing to do.

**Failure table** mirrors the pad's row for row (button GONE · "Calendar unavailable" · "Too much
to send" · compensated multi-batch placement · "Not everything came back" · "Nothing came back" ·
"Nothing to send" · dropped stroke never a lost page · newer store format left as found).

**Planner calls the wizard didn't cover** (implementer follows; user can override at phase start):
double-tap detection lives in `:sn-screen`'s `PageGestures` if it is not there already (shared
gesture logic never lives in a consumer) · the Send-to-Calendar received placement lands on the
target page with **coordinates 1:1** from the notebook page (no cell-fitting — the user drags it
into a cell; the selection is what makes that one gesture) · the Week page's spare 8th cell is
blank paper, unlabeled · out-of-month cells on Month are writable like any other · nothing is ever
deleted from `period` in this arc (a later question — `BACKLOG.md`).

### Y1 — The seam, `:ext-ink`, `:ext-calendar`, the Month page ✅ (Fable; commit 6a16017a; user checklist PASSED 2026-09-02)
Contract additions above (`API_VERSION` 7, per-action floor, `ICalendar`, `CalendarTarget`,
`CalendarDates`, extras/results). `:ext-ink` extracted from the pad and the pad repointed — **the
pad's JVM tests pass unchanged and the pad's Nomad walk is re-run** (open, ink, flip, send both
ways). `:ext-calendar` module (manifest, icon, Application, service, session, schema, SQL, store,
geometry + template for **Month only**, screen with fixed tools, lasso move/delete, undo, flush,
`state` rows). Host: registry, client, entry, the **library door only**, `<queries>`. **Walk:**
open from the library · `am start` refused (`refused caller (none)`) · ink on September, Back,
reopen → committed ink still there (screencap) · a second month is blank and **wrote no rows**
(`sqlite3` is not available against SQLCipher — prove via the debug store self-test's row counts
or a `Slog` count) · extension `pm disable-user` → button GONE, back after `pm enable` · store file
is ciphertext at `Garden/…ext.calendar.dev.db` · `logcat -b crash` empty · cold/warm open timings.
**JVM:** `CalendarDates` (Sunday weeks across year ends, month starts, stepping incl. Feb 29 and
Dec → Jan, normalization) · `CalendarTarget.requireValid` · `CalendarSqlTest` (exact strings +
args) · `CalendarGeometry` Month rects + `hitTest` · the first-stroke batch shape (period + page +
stroke, `OR IGNORE`, one batch) · `minApiVersion` map + `accepts` · moved tests green under their
new names. **User checklist:** pen feel on the grid, lasso move within the page, undo/redo by
finger, the cell hairlines visible on the Nomad.
**Questions at phase start:** version stamp (`0.1.0-ratta` stays?) only — **stays** (user, 2026-09-01).

**Y1 Outcome (2026-09-02, Fable; 1798 JVM tests/variant, +60; twelve modules debug + release,
three release APKs sign; walk by hand on the Nomad, all green):**
Shipped as the seam spec reads. `:extension-api` v7 (`ACTION_CALENDAR`/`_SCREEN`, `API_VERSION`
7, **`minApiVersion` is a map** — calendar 7 via `MIN_API_VERSION_FOR_CALENDAR`, the three arc-22
store points 6, everything else 1; `ICalendar`, `CalendarTarget` with `requireValid` + `of(kind,
day, half)`, `CalendarDates` with the hand lists + `weekTitle`/`dayTitle`/`monthTitle`, the three
mirrored extras/result). **`:ext-ink`** (`…notesproutsn.ink`, `api` on both `:extension-api` and
`:sn-screen`): `InkWire` · `StrokeRows` + `StrokeBlob` (the pad's `geometry` encoder, moved) ·
`StoreBatches` · `StrokeReadPlan` · `InkDocument` (TreeMap + op log + `flushUntilClean(extraDirty,
exec)` — the consumer prepends its own lead inside `exec` and restores its own flag on failure) ·
`InkAction` (Drew/Erased/Moved/Pasted, each naming its `pageId`). **Implementer calls (recorded,
not re-litigated):** (1) **`StoreUnavailable` + `PageInk` moved too, and the pad's `run` /
`compensated` / `guard` / planned-stroke-read became an abstract `InkStore` base** the pad's and the
calendar's stores extend — the spec named the six files; these were the rest of the sibling copy.
(2) The pad's `ScratchAction` is now `sealed { Ink(InkAction) · Page }` — the pad's tests changed by
exactly the constructor names (`ScratchAction.Drew` → `InkAction.Drew`). (3) **`FloatingSelectionBar`
went to `:sn-screen`** (the pad's bar minus its button list); `ScratchSelectionToolbar` and
`CalendarSelectionToolbar` are thin on it. (4) `CalendarStore.receive` mints a missing page at
**`0 × 0`** and the screen's first showing writes the surface size (`sizePage`, the pad's rule) —
the sender's page size is the sender's; Y3 may revisit. (5) The mint lead rides only a flush that
carries a `Put`: a stroke drawn and undone before the debounce is one `DELETE` and **mints nothing**
(test-pinned). `page.periodId` is resolved by subselect from `(kind, date)` inside the INSERT, so
the day's other half joins an existing period whatever id the caller minted. `touchPage` follows
every stroke write. (6) The bookmark (`state`) is written on **every** `show`, the first included —
"never on open" is the period/page/stroke rule, and the walk proved it (0/0/0 after browsing three
months). (7) Prev/next **and the finger swipe** step the month already (two lines on
`PageGestures`; Y2 owns the rest of navigation); the template re-bakes on `onResume` only when the
date changed. (8) A compensated multi-batch placement drops its strokes by id and **leaves a minted
empty period/page behind** (nothing deletes a period in this arc; an empty page is not a placement).
`CalendarActivity` opens after `awaitLaidOut()` so the grid sits under the bars' **measured**
heights, never a dimen. **Nomad numbers:** cold open 2 726 ms (store creation), warm 56 ms,
`begin` 161 ms cold / 34 ms warm; the pad's warm `begin` 23 ms. **Walk:** library button after the
pad · Month page as specified (today ringed, out-of-month grey, Notes band, pager) · `>` and a
swipe step months · Back → reopen lands on the left month (bookmark) with `rows: 0 period(s), 0
page(s), 0 stroke(s)` · `am start` → `refused caller (none)` · `pm disable-user` → button GONE
(`0 provider(s) of 0 candidate(s)`), `pm enable` → back · store file ciphertext header · crash
buffer empty · **pad re-walk:** open (3 pages, 6 strokes read back through `:ext-ink`), flip,
Back, reopen on page 2, crash buffer empty. Ink and both pad transfers are pen input — user
checklist. `docs/sn-screen.md` grew the `:ext-ink` paragraph, the `FloatingSelectionBar` row and
`ic_calendar`; every other doc is Y4's. **No new trap.** Nomad test data: none added (the calendar
store is empty; the pad's three pages untouched).
**User checklist (pen):** (1) pen feel on the Month grid; ink on September, Back, reopen → still
there; (2) lasso a stroke, drag it into another cell, Delete another; (3) 2-finger double-tap
undo / 3-finger redo; (4) the cell hairlines visible on the Nomad; (5) the pad: ink a page and
send it to a notebook, then lasso in a notebook and send to the pad (both transfers on the
repointed pad). **Commit 6a16017a (pushed); user checklist PASSED "The tests pass" 2026-09-02. Y1 CLOSED; Y2 next.**

### Y2 — Week + Day + navigation ✅ (Opus code on a Fable brief · Fable review · Sonnet layouts/strings · Haiku walk; commit eaf8d8ce; user checklist PASSED 2026-09-02)
`CalendarGeometry` + `CalendarTemplate` for Week and Day (fixed row height, slack band, gutter,
time labels 12-hour with AM/PM from the half) · view toggles · the bottom pager with prev/next and
the title · `DayPickerDialog` rebuilt fresh in `:ext-calendar` (og's shape: e-ink day grid, tap
the header to flip to a 3×4 month grid with year stepping, today ringed, picked day filled) ·
Today · finger swipe (`SwipeMath` guards) · double-tap a cell → that day's Day AM · AM → PM → next
day stepping · `state` rows carry view + date + half · today ring re-bake on navigation/resume.
**JVM:** Week/Day rects (24 rows, gutter, slack ≥ 0 at the Nomad's size **and** at a shorter
page), `hitTest` for Week, stepping across boundaries, the picker's month-grid model, title
strings from the hand lists. **Walk (adb-drivable — finger taps and swipes work on the Nomad):**
toggles, prev/next, Today, picker to a far month, swipe both ways on all three views, double-tap
a cell lands on Day AM, close and reopen lands where left. **User checklist:** ink on all three
views survives a reopen; day rows and ink line up after a close/reopen.
**Questions at phase start:** version stamp only — **stays `0.1.0-ratta`** (user, 2026-09-02).

**Y2 Outcome (2026-09-02, Opus code on a Fable brief + Sonnet XML + Fable review + Haiku walk with
the picker re-driven by hand; 1826 JVM tests/variant, +28; twelve modules debug; Nomad walk all
green):** Shipped as the brief reads. `CalendarGeometry` grew `Week` (2×4 cells over the Month
page's grid area, so the Notes band is Month's ± 1 px; the spare 8th cell hit-tests to null) and
`Day` (`DAY_ROW_DP` 34 flat, `DAY_GUTTER_DP` 80, 24 rows, the closing hairline at `rowsBottom`, the
slack band below; rows shrink only on a page too short for them, never grow) + the pure
`dayRowLabel(half, slot)` ("12:00 AM" … "11:30 PM", AM/PM from `HALF_NAMES`); `CalendarTemplate`
paints all three through one `dayCell` (the ring arithmetic exists once) and labels a Day slack
band only at ≥ `SLACK_LABEL_MIN_DP` 24. **The anchor rule is a pure class, `CalendarNavigation`**
(`Move(target, anchor, anchorHalf)` — `opening` / `stepped` / `todayMove` / `picked` / `dayAt` /
`toggled`; the screen shows the move and then calls `shown`, so a show that throws leaves the
organizer where it was). `DayPickerModel` (pure rows/month grid, no trailing empty week) +
`DayPickerDialog` (og's shape, built in code, `Dialogs.style`). `PageGestures.Listener.
onFingerDoubleTap` in `:sn-screen` — a second, independent history over the same qualifying bare
taps; `onFingerTap` byte-identical, the notebook untouched. `CalendarToolbar` takes Today + the
three word latches (`setView` from `showPage`, never from the tap) and the tappable title.
**Implementer calls (recorded, not re-litigated):** (1) a toggle keeps the anchor **and its half**
— `toggled(kind)` takes no clock; `anchorHalf` is recomputed only when the anchor *day* moves (the
clock's half when that day is today, else AM), so Day Sep 3 PM → Week → Day is Sep 3 PM again;
(2) **a double-tap always opens AM**, today's cell included (the wizard's "Day AM"); (3) Week's
`cellsBottom` is Month's `gridBottom` to within 1 px (halving an odd area), pinned as a range;
(4) the label builder lives on `CalendarGeometry`, no `DayLabels` file. **Fable review fixes (two,
both XML):** the picker's root was `wrap_content` over weight-1 zero-width cells (they would have
measured to nothing — og's root is `match_parent`); the four new `AppCompatButton`s carried no
`layout_width` — **`Widget.Notesprout.TextButton` and `LatchButton` set none** (only
`ToolbarButton` does), `processDebugResources` passes, and the screen dies at inflate. The walk's
first run found that one (`InflateException … You must supply a layout_width attribute`) — a NEW
TRAP in the standing list. **Nomad numbers:** the bars are the **sw720dp tier here — 71 dp = 133 px
each**, not the tests' 107-px fixture, so the Day page's slack is 22 px (11 dp) and takes no label
— blank paper by rule; `begin` warm 19–21 ms. **Walk (Haiku, 11/11):** Month/Week/Day toggles with
the latch following the paper · prev/next on all three (Day: AM → PM → next AM, and back) · six
swipes · Today on Month and on Day (09:06 → AM) · double-tap Sep 10 on Month and Tue Sep 8 on Week
land on their Day AM, a double-tap on Day changes nothing · Back + reopen at `at=1/2026-09-13/0`
then `at=2/2026-09-13/1` with the same titles · rows `1/1/5` at open and at the last reopen · crash
buffer empty. **Re-driven by hand (Fable):** the agent reached March 2028 by 18 × Next instead of
through the picker — the picker's flip to the 3×4 month grid, two year steps, Mar, 15 →
"March 2028" → Day toggle "Wed, Mar 15, 2028 · AM" (the picked day is the anchor) all proved by
`uiautomator` title reads. **Walk notes:** adb's two sequential `input tap`s exceed the 300 ms
double-tap window — `adb shell "input tap X Y & input tap X Y; wait"` lands it; the picker dialog
is content-sized and **narrows in month mode**, so a tap at the day grid's arrow position falls
outside it and cancels the dialog (a procedure slip in the walk; the narrowing itself is cosmetic
and left as is). **Not changed, noted for Y3:** `CalendarDocument.show` sizes a page only when its
stored size is `0 × 0` — a page minted at one screen size and shown at another keeps the old size
(the 1:1 template rule as documented; Y3's transfer touches exactly this). Docs → Y4. Nomad test
data unchanged (the Y1 checklist's five strokes on September 2026). **User checklist (pen):** (1)
ink on a Week cell and on a Day AM row, Back, reopen → both still there and the Day ink sits on its
row; (2) ink on a Day PM page, swipe to AM and back → the ink stays with its half; (3) pen feel
on the Day rows and the Week cells; (4) the Week/Day hairlines visible on the Nomad; (5) lasso a
stroke on Week, drag it into another cell. **Commit eaf8d8ce (pushed); user checklist PASSED "all tests pass" 2026-09-02. Y2 CLOSED; Y3 next.**

### Y3 — The notebook door + both transfers 🧪 (Opus code on a Fable brief · Fable review · Fable walk by hand; awaiting the user checklist)
Notebook top-bar `btnCalendar` after `btnScratchPad` with `releaseForHandoff()` before launch ·
`CalendarEntry`'s second door · `EXTRA_CALENDAR_SEND_ENABLED` → the calendar's top-bar Send (whole
page) + selection Send (lasso) · `RESULT_CALENDAR_SEND` → the host's drain + paste · selection
toolbar's Send to Calendar + the target sheet → `receiveInk` with `CalendarTarget` → placement on
the target page (minting rows if absent) → the screen opens on it, lasso-selected, one `Pasted`
step · compensation on a multi-batch failure · timeouts re-measured on the Nomad · both doors GONE
when the extension is disabled. **JVM:** the target sheet's four `CalendarTarget`s from a fixed
"today" · placement statement shape · drain/caps twins. **Walk:** handoff notebook → calendar →
notebook on Back and on a Send exit (committed ink verified after each — live EPD ink is invisible
to screencap) · the busy guard · `logcat -b crash` empty. **User checklist (adb cannot lasso):**
lasso → Send to Calendar → "This week" → lands selected, drag into a cell · calendar lasso → Send
to Notebook → lands selected · EPD feel on both handoffs.
**Questions at phase start:** version stamp only — **stays `0.1.0-ratta`** (user, 2026-09-02).

**Y3 Outcome (2026-09-02, Opus code on a Fable brief + Fable review + Fable walk BY HAND on the
Nomad; 1833 JVM tests/variant, +7; twelve modules debug; all green):** Shipped as the brief reads —
and Y1 had already built the whole seam, so Y3 was the two doors and the two consumers. Host:
`btnCalendar` after `btnScratchPad` in the notebook's top bar; `CalendarEntry`'s second door with
`sendEnabled = true`, `beforeLaunch = paper.releaseForHandoff()`, refresh on resume, close on
destroy; `SelectionToolbar`'s 8th button **Calendar** between Pad and Tag (ink-only, extension-gated
— the pad's exact rule); new pure `notebook/CalendarTargets` (`Choice` × `Row`, every target via
`CalendarTarget.of` so the host never computes a period) + `CalendarTargetsTest` (7);
`sendSelectionToCalendar` (caps before any bind → `ActionSheetDialog` with the four rows, icons
null — `LinkPickerActivity`'s precedent) → `openCalendarWith` → `onCalendarSent`. **The paste back
is ONE body for both extensions** — `pasteTransferred(wire, truncated, TransferWording, source)`;
`pasteFromPad` / `pasteFromCalendar` are one-liners over it with `PAD_WORDING` / `CALENDAR_WORDING`;
`toolBeforePadPaste` → `toolBeforeTransferPaste` (one field, one restore — only one transfer can
have just landed). Extension: `openDocument` opens on `CalendarSession.received.target` ahead of
the bookmark when `openReceived` (`nav.opening` passes the target through unchanged);
`consumeReceived()` after the overlay hide — consumed once, dropped unless its target is the showing
page, one `InkAction.Pasted`, lasso armed **before** `setSelection`, `toolBeforeReceive` +
`restoreToolAfterReceive` + `whenPenIdle` as the pad has them. **Implementer calls (recorded):**
Calendar is "the second extension-gated button" and Tag "the third" in the bar's comments (the
brief's numbering was off by one); `source` also names the sender in the paste-failure `Log.w`.
**Nomad walk (by hand — the notebook's own pasted selection stood in for a lasso, so BOTH
directions were adb-driven):** notebook (Tags, Page 1) → Calendar door → Week page opened
`send=true`, `begin` 818 ms cold-in-process / 28 ms warm · top-bar Send → `result=1` →
`drainOutgoing: 1 chunks, 19 strokes in 106 ms` → `pasted 19 strokes from the calendar` landed
SELECTED with the bar up, Send to Calendar visible on it · Send to Calendar → the four-row sheet →
"This month" → `receiveInk: 19 strokes placed on 0/2026-09-01/0 in 119 ms` → the calendar opened
on **September** (not the Week bookmark) with the 24 strokes and the 19 placed ones selected under
the bar (screencap) · Back → notebook, `end` → unbind · busy guard: two rapid taps = one `hold`, one
showing · `pm disable-user` → **both** doors GONE (library + notebook), `pm enable` → both back ·
reopen from the library: `rows: 3 period(s), 4 page(s), 63 stroke(s)` (44 + 19) · `logcat -b crash`
empty. **`PLACE_TIMEOUT_MS` stays 10 s** — 119 ms for 19 strokes; the pad's number is generous by
two orders. **No new trap.** Nomad test data: "Tags" Page 1 now carries the Week page's 19 strokes;
September 2026 carries "This is a test" (19 strokes, cells 22–29); the Week page (Aug 30) untouched.
Docs → Y4. **User checklist (pen):** (1) lasso ink in a notebook → Send to Calendar → "This week"
→ the calendar opens on the week, the ink selected; drag it into a cell; 2-finger double-tap
undoes the placement, 3-finger redoes it; (2) in the calendar, lasso a stroke → Send selection to
notebook → lands selected in the notebook; drag it; (3) EPD feel on both handoffs — ink on the
calendar right after the notebook door, and on the notebook right after Back — no ghosting, no
missed first stroke.

### Y4 — Docs, ledger, freeze ⬜ (Sonnet docs in parallel · Fable read-back · **no code review, the user's call**)
New `docs/calendar.md` (the feature: the three pages, the store, navigation, both transfers, the
failure table, where the code is) · `docs/extensions.md` § the calendar point + `:ext-ink` in the
module table + `API_VERSION` ledger (7 = the calendar point, per-action floor) + boundary audit
re-walked · `docs/scratchpad.md` "where the code is" (moved helpers) · `docs/sn-screen.md` (the
new module in the dependency table) · `docs/notebook.md` + `docs/library.md` entry points ·
both CLAUDE.mds (twelve modules, SEVEN points, no EIGHTH) · root `CLAUDE.md` pointer ·
`RATTA_PLAN.md` header, Architecture, the Arc 23 ledger entry, this section marked complete ·
`BACKLOG.md` (deleting a period · a date-change receiver for the today ring · calendar export ·
events / tasks / day window as later extensions) · memory. Byte-scan, commit, push.
**Questions at phase start:** version stamp only.

---

## Phases — Arc 22 "Tables" ✅ COMPLETE + FROZEN 2026-09-01

**Status: X1 ✅ · X2 ✅ · X3 ✅ · X4 ✅ · X5 ✅** (planned 2026-09-01, wizard complete; every phase
shipped the same day). Full phase records below, kept until the next compaction; the ledger entry
under "Frozen arcs" is what binds.

The extension store stops being a key/value seam and becomes **real SQLite tables** — the
extension declares its schema, sends parameterized SQL, and gets rows back — while the host keeps
owning **everything it owns today**: the file (`Garden/<pkg>.db`, `SoilFile` the only path
constructor), the key (global passphrase, `SoilCrypto`, `KeyOpener`), the one connection and its
thread, every cap, validation of every byte that crosses, and backup. **Not a seventh point** — a
change to the service every store-taking point already receives. Raised at arc 21 / W4, declined
then, decided now (the `BACKLOG.md` entry "the extension store is key/value" is this arc's brief).

Why: the scratch pad's **4 MiB page ceiling**, `PageFullException` and the whole-page re-encode
on every save; the tag index's **one 4 MiB value** with `TagCodec`, `WORST_CASE_BYTES`,
`CompactId`, base-36 ids and a whole-index decode per search; the editor's line-codec blobs — all
exist only because arc 11 hid SQLite behind six methods. The file underneath was always SQLite.

**Fable writes X1 (the seam).** X2–X4 are Opus-implemented, Sonnet-scaffolded, Haiku-walked. X5
is docs (Sonnet) — **no code-review phase in this arc, on the user's call**. Anything a phase meets
outside this spec (crypto/key lifecycle, an engine gap, a schema question) stops and asks the user.

### Locked decisions (arc-22 wizard 2026-09-01 — do not re-ask)

| Decision | Answer |
|---|---|
| Seam shape | **Gated parameterized SQL.** The extension declares versioned DDL once; at runtime it sends `SELECT`/`INSERT`/`UPDATE`/`DELETE` strings with bound args. The host validates every statement (one statement, declared-name space only, no `PRAGMA`/`ATTACH`/DDL/transaction control at runtime), runs it, and answers with encoded rows. Full SQLite expressiveness (joins, aggregates, indexes, CTEs). |
| Schema ownership | **Extension declares, host applies.** A `StoreSchema` = ordered DDL steps (v1 create, v2 alter…); the host keeps the applied version per store in its own table, validates each step, runs the missing steps, and **refuses a downgrade** (an extension never sees a store at a schema newer than it knows). |
| The KV API | **Removed in this arc.** `get`/`put`/`delete`/`keys`/`putLarge`/`getLarge` go; the `kv` table goes; every store user (scratch pad, tags, document editor) moves to tables. |
| Existing data | **Wipe, no migration.** `0.1.0-ratta` is unreleased and the Nomad's data is test data. A store still carrying the arc-11 shape is reset on open (legacy tables dropped, schema version 0). The old codecs are deleted outright. Old backups restored later are reset the same way. |
| Tag search merge | **Two queries:** `tags()` (every tag, id + display — small) so the host's `FuzzyRank` runs as today, then `assignmentsOf(matchedTagIds)` for only the rows the ranking needs. `TagCodec`, `WORST_CASE_BYTES`, `CompactId` are deleted. |
| Test harness | **No new dependency.** Pure gate logic (validator, codec, chunking, DDL checks, name guard) is JVM-tested behind an injectable executor; real execution is proven on the Nomad by the debug "Extension store self-test" (grown to tables). |
| Seam author | **Fable writes X1.** The spec below is still complete enough for Opus to take it if a Fable session is unavailable. |

**Planner calls, accepted 2026-09-01 (locks):** (1) `API_VERSION` 5 → **6**, the second non-tail
break; every store-taking service (scratch pad, editor, tag manager) redeclares 6, ML Kit / Soil / PDF
stay. (2) Writes are **batch-atomic only** — a list of statements runs in one host transaction per
call; **no transaction is ever held open across Binder calls**. (3) Reads: the host runs the query
to completion, encodes the rows, and hands **≤ 4 MiB chunks** through `LargeValue` behind a
per-call handle (cleared on revoke); a result above a fixed in-memory cap is a typed refusal and
the extension pages with `LIMIT`. (4) A scratch-pad page becomes **unbounded like a notebook
page**: `PageFullException`, the byte accounting and the "page full" dialog are deleted. (5) **One
binder**: `IExtensionStore` is *replaced* with the table shape (a break may redefine — the
compatible-append recipe is for tails, and this is not one). (6) Tag ids become **UUIDs**;
`identityKey` is a stored, uniquely indexed column. (7) Arc 22 "Tables", phases X1–X5. (8) The
debug self-test grows to prove tables on-device. (9) Backup/restore untouched — same file, same key.

**Sharpened during planning (implementer follows; user may override at phase start):**

- **A version FLOOR joins the range rule.** Arc 18's rule (host accepts `1..API_VERSION`) protects
  new-extension/old-host only; a *replaced* interface breaks the other direction, and a v5 scratch
  pad calling transaction code 1 on a v6 host would land on a different method with a mismatched
  parcel — not reliably loud. So `ExtensionContract.MIN_API_VERSION_FOR_STORE = 6`, and the host
  accepts a **store-taking** point's service (`SCRATCH_PAD`, `DOCUMENT_EDITOR`, `TAG_MANAGER`) only
  in `6..API_VERSION`; the stateless points keep floor 1. **Consequence: after X1 and until each of
  X2–X4 lands, that extension's doors are GONE** (the existing not-installed path — pad button,
  editor entries, tag doors, search names-only). Deliberate, honest, and X1's walk verifies it.
- **Room leaves the store file.** Extension tables are unknown at compile time, so Room's entity
  machinery buys nothing; `ExtensionStoreDatabase`/`KvDao`/`KvEntity` are deleted and the store is
  a thin wrapper over `SupportSQLiteOpenHelper` built from the **same** `SoilCrypto` /
  `KeyOpener` factories (still `NonDestructiveOpenHelperFactory`-wrapped, still the create/open
  two doors, still process-lifetime cached and never closed, still WAL + `wal_autocheckpoint =
  100` + `busy_timeout = 5000`). The `.soil` and the index keep Room; only the store moves.
- **Store format version rides `PRAGMA user_version`:** `2` = table store. On open: `user_version
  1` (Room-era kv) or a `kv`/`room_master_table` in `sqlite_master` → **legacy: drop those two
  tables, create `host_schema`, set 2** (the wipe, logged as a count, never a name); `0` on an empty
  file → fresh create; **`> 2` → refuse to open** (a newer host wrote it — never-delete-on-corruption
  applies, the store is left exactly as found and the extension is "unavailable").
- **Foreign keys are ON** for the store connection (`PRAGMA foreign_keys = ON` in `onOpen`), so a
  declared `ON DELETE CASCADE` actually cascades. Documented as a promise of the seam.
- **The editor's `prefs` table is the ONE extension table the host reads** (Document-PDF export's
  text size, arc 19 / M9 — `DocumentPdfRender.editorTextSizeSp`). Its shape is pinned in
  `DocumentContract` (`PREFS_TABLE = "prefs"`, columns `key`/`value`, `PREF_TEXT_SIZE = "size"`),
  read through the host's own executor (no binder) **only if the file exists and the table exists**
  — the same never-mint rule as today. No other host code reads an extension's tables.
- **Arc-21 caps stay as policy:** `MAX_TAGS` 5 000 / `MAX_TAG_ASSIGNMENTS` 50 000 / `MAX_TAG_CHARS`
  64 were the user's; they become `COUNT(*)` checks before an insert (`TAG_INDEX_FULL` keeps its
  meaning). Their *size* arithmetic (`WORST_CASE_BYTES`, `MAX_TAG_ID_CHARS`, `CompactId`) goes.
- **Extension-side tests are statement-shaped.** With no JVM SQLite, each extension's fake store
  becomes a **statement recorder + canned-row responder**: SQL builders are pure and tested for
  exact text + args; row decoders are tested from hand-built rows; the executor call is thin. The
  Nomad walk is where real SQL runs.

### Superseded locks (this arc rewrites them — do not cite the old form)

Arc 11 "encrypted per-package KV + ashmem large values" (→ tables; ashmem stays as the chunk
carrier) · arc 11's 4 MiB page ceiling, `PageFullException`, `SCRATCH_PAGE_FULL`, the `pages`
line list and `page/<id>` blobs · arc 19 / M5 "store key layout PINNED (`size` + `carets` line
codec)" and M10 "user dictionary as a `UserWords` line blob under `dict`" · arc 21 "one store key
`index` holding the whole `TagCodec` blob — never a key per tag" (boundary row 22), `NSTAG2`,
`WORST_CASE_BYTES` 3 650 007 pinned by test, `CompactId`, base-36 ids, "identityKey is derived,
never stored", `snapshot` as the search merge's read · "`API_VERSION` 5, the FIRST bump that is not
a compatible tail" (6 is the second, and the first with a floor). Everything else in the arc
ledgers stands — in particular the pre-open rule, the three-exception rule, the uid gate, the
held-bind bracket, "an extension writes nothing to disk itself, ever", and W5's backup treatment.

### Seam spec (planner-fixed — X1 implements as written)

**Contract (`:extension-api`, stdlib only, everything pure and JVM-tested):**

- `ExtensionContract.API_VERSION` = **6**; new `MIN_API_VERSION_FOR_STORE` = 6 (KDoc records the
  event and the floor rule). Store caps replace the KV ones: `STORE_MAX_INLINE_BYTES` 512 KiB
  (payload rides inline `byte[]` at or under it) · `STORE_MAX_VALUE_BYTES` 4 MiB (one payload /
  one result chunk, ashmem above inline — `LargeValue.requireValid` unchanged) ·
  `STORE_MAX_RESULT_BYTES` 32 MiB (the whole materialized result) · `STORE_MAX_ROW_BYTES` = the
  chunk cap (a row is never split) · `STORE_MAX_BATCH_STATEMENTS` 10 000 · `STORE_MAX_SQL_CHARS`
  8 192 · `STORE_MAX_ARGS` 999 per statement (SQLite's default bind limit) · `STORE_MAX_TABLES`
  64 · `STORE_MAX_SCHEMA_STEPS` 256, `STORE_MAX_STEP_STATEMENTS` 64 · `STORE_MAX_OPEN_RESULTS` 4
  per binder. Typed messages (compared verbatim): `STORE_RESULT_LARGE` · `STORE_ROW_LARGE` ·
  `STORE_SCHEMA_NEWER` (the downgrade refusal) · `STORE_SCHEMA_UNAPPLIED` · `STORE_RESULTS_OPEN`.
  Deleted: `STORE_MAX_KEY_CHARS`, `STORE_MAX_KEYS`, `STORE_VALUE_LARGE`.
- **`IExtensionStore` v6** (replaced whole; every method `HostCallerCheck`-free — it is the host's
  own stub, uid-gated as today; only the three marshalable exceptions leave):

  ```
  int          schemaVersion();                    // applied version for this store; 0 = fresh
  void         applySchema(in StoreSchema schema); // idempotent: runs steps applied+1..schema.version,
                                                   //   each step its own txn + version bump (crash-resumable);
                                                   //   ISE(STORE_SCHEMA_NEWER) on a downgrade; IAE on bad DDL
  long[]       exec(in StorePayload batch);        // N statements, ONE transaction, all-or-nothing;
                                                   //   returns changes() per statement
  StoreResult  query(in StorePayload statement);   // first chunk (+ handle when more follow)
  StoreResult  next(int handle);                   // the following chunk; ISE when unknown/finished
  void         close(int handle);                  // drop an unfinished result early
  ```

  `exec`/`query` before `applySchema` on this binder → `ISE(STORE_SCHEMA_UNAPPLIED)` (structural:
  a query cannot precede the declaration of what it queries; bind-per-call methods apply per call —
  a no-op when versions match is one `SELECT`).
- **Parcelables** (`requireValid` in `init` = unmarshal validation, both directions):
  `StorePayload(inline: ByteArray?, region: LargeValue?)` — exactly one non-null, inline ≤
  `STORE_MAX_INLINE_BYTES`; `describeContents` = `CONTENTS_FILE_DESCRIPTOR` when a region rides.
  `StoreResult(payload: StorePayload, handle: Int, more: Boolean)` — `handle` −1 when complete.
  `StoreSchema(version: Int, steps: List<List<String>>)` — `steps[i]` is version `i+1`'s DDL;
  `version == steps.size`, caps enforced, every statement pre-validated by the shared validator so a
  bad schema fails at construction on the extension's side, not at bind.
- **`StoreCodec`** (pure, shared both sides, JVM-tested, big-endian `DataOutputStream`, the
  `ScratchPageCodec` idiom): **statements** — magic `NSST` · u8 version 1 · u16 count · per
  statement u32 sqlLen + UTF-8 sql · u16 argc · args as cells; **rows** — magic `NSRW` · u8
  version 1 · u16 columnCount · column names · u32 rowCount · per row per column a **cell** = u8
  tag (`0 NULL · 1 INTEGER i64 · 2 REAL f64 · 3 TEXT u32+UTF-8 · 4 BLOB u32+bytes`). Unknown
  magic/version → `IllegalArgumentException` (unreadable ≠ empty). Extension-facing helpers in the
  same module: `Statement(sql, args: List<Cell>)`, `Cell` sealed, `Row` (typed accessors by index
  and by column name), `StoreRows` (the decoded chunk), and **`StoreReads.all(store, statement)`**
  — the loop over `query`/`next` that closes on any failure, so no extension re-writes it.
- **`StoreSql`** (pure validator, shared so an extension can pre-check): a tiny tokenizer honest
  about `'…'`, `"…"`, `` `…` ``, `[…]`, `--` and `/* */`; **one statement** (no `;` outside
  literals); first keyword ∈ {`SELECT`, `WITH`} for `query`, ∈ {`INSERT`, `REPLACE`, `UPDATE`,
  `DELETE`, `WITH`} for `exec`; top-level denylist anywhere in the token stream: `ATTACH DETACH
  PRAGMA VACUUM CREATE DROP ALTER BEGIN COMMIT ROLLBACK SAVEPOINT RELEASE REINDEX ANALYZE
  load_extension`; **every identifier token that names a host-reserved space** (`host_*`,
  `sqlite_*`, `room_*`, `android_*`) is refused — the file is per-package, so the only things
  to protect are the host's tables, the connection and the file, and this is what protects them.
  DDL validator (schema steps): `CREATE TABLE`, `CREATE [UNIQUE] INDEX`, `ALTER TABLE … ADD
  COLUMN | RENAME …`, `DROP TABLE|INDEX`, all `IF [NOT] EXISTS` forms, `WITHOUT ROWID`, and
  `REFERENCES … ON DELETE …` clauses; **no views, no triggers, no virtual tables** in v6 (each is
  an additive later tail if ever wanted). `StoreNames.isValid`: `^[a-z][a-z0-9_]{0,62}$` and not
  in a reserved space. `?` and `?NNN` positional binds only (named binds refused — a name is one
  more parser).

**Host (`:app` `data/extstore/`):**

- `ExtensionStores` keeps its name, cache, doors and pre-open rule; `open` returns the new
  `ExtensionStoreDatabase` (a wrapper: `SupportSQLiteOpenHelper` + `writable()`), runs the
  user_version ladder above, creates `host_schema (id INTEGER PRIMARY KEY CHECK (id = 0), version
  INTEGER NOT NULL)`; `checkpointIfOpen` and `closeAll` adapt; **backup is untouched** (same file,
  same WAL rule, same `copyDatabase`).
- `StoreExecutor` (interface, Android-free): `transaction { }`, `exec(sql, args): Long`,
  `query(sql, args, sink)`. `SupportStoreExecutor` implements it over `SupportSQLiteDatabase`
  (device-only); tests inject a fake.
- `ExtensionStoreGate(executor, extUid, callingUid)` keeps its shape and its `io {}` mapping (every
  SQLite failure → `IllegalStateException`; `SQLiteConstraintException` too — the extension reads
  the message, the host never parses it), grows `schemaVersion`/`applySchema`/`exec`/`query`/
  `next`/`close`, parks results as `List<ByteArray>` chunks per handle (**bytes, not regions** — a
  region is minted at `next()` time and parked in the binder's `ThreadLocal` exactly as
  `getLarge` does today), refuses a fifth open result, and drops every parked chunk on `revoke()`.
  `exec` is `@Synchronized` (one writer per store at a time; reads run under WAL concurrently).
- `ExtensionStoreBinder` keeps the `onTransact`/`finally` region-close discipline and the
  `region {}` `ErrnoException` mapping; `StorePayload.region` follows the `putLarge` handshake
  (host copies in and closes its handle at once).
- `ExtensionRegistry.discover` applies the per-point floor.
- The debug menu's **"Extension store self-test"** proves on the Nomad, in-process through a real
  binder: fresh create → `applySchema` v1 → `exec` batch of 5 000 stroke-shaped rows (blobs) →
  `query` streaming them back in > 1 chunk with byte-exact equality → a failing batch (constraint
  violation mid-list) leaves **zero** rows → `applySchema` v2 (`ADD COLUMN`) then v1 again refused
  with `STORE_SCHEMA_NEWER` → denylisted statement refused as `IllegalArgumentException` →
  `host_schema` unreachable from SQL → wrong uid / revoked → a legacy-shaped file (built by the
  probe itself with a `kv` table) opens as a wipe to version 2. Timings in the summary
  (open, 5 000-row batch, read-back).

### X1 — The seam ✅ (Fable)
`:extension-api` v6 (contract, parcelables, `StoreCodec`, `StoreSql`, `StoreNames`,
`StoreReads`; delete `LargeValue`-era KV KDoc; keep `LargeValue`/`SharedBytes`); host store rewrite
(`ExtensionStores` doors + user_version ladder + wipe, `StoreExecutor` + `SupportStoreExecutor`,
gate, binder, registry floor); delete `KvDao`/`KvEntity`/Room from the store; adapt
`ScratchPadClient`/`DocumentEditorClient`/`TagClient` **only as far as they compile** (they mint the
binder — unchanged shape); `DocumentPdfRender.editorTextSizeSp` moves to the pinned `prefs` read
via the host executor (returns the default until X4 creates the table); debug self-test.
**The three store-taking extensions keep declaring 5 in X1 and are therefore GONE from the host
until their phase.** X1 touches `:ext-scratchpad`, `:ext-document` and `:ext-tags` only as far as
the release gate needs them to build: each module's store adapter (`ScratchStore`, `TagStore`,
`EditorPrefs`) is reduced to a compiling **"unavailable" stub** against v6 (reads answer their
default, writes refuse) with a `TODO(X2|X3|X4)` at the seam, and their now-dead codec tests are
deleted with the codecs' callers left for their phase. No feature work in those modules in X1.
JVM: codec round-trips (every cell kind, empty result, 0-column guard), validator (each denylist
word, quoted-literal false positives, `;` inside a string, reserved-name refusal, one-statement
rule), chunker (row never split, `STORE_ROW_LARGE`, `STORE_RESULT_LARGE`, exact chunk counts),
gate (uid/revoked on every method, unapplied-schema refusal, batch rollback through the fake
executor, handle lifecycle incl. the fifth-result refusal and revoke-drops-all), `StoreSchema`
construction validation, the floor rule in the registry's pure part, user_version ladder as a pure
decision table. Walk (Haiku): self-test passes; recognizer/heading, export (soil + pdf), import
still work; pad button, Document entries and every tag door GONE; search runs names-only; backup
still copies every `Garden/<pkg>.db`.
**Questions at phase start:** none — wizard + planner calls cover it. Confirm the flip only.

**Outcome (2026-09-01, Fable; 1631 JVM tests/variant, +8 net — 42 new store tests against 34
KV-era tests deleted; ten modules debug + release, four release APKs signed, NUL-scan clean):**
implemented as the seam spec reads, with these implementer calls, all inside the spec's letter:

- **The format ladder rides the open helper's own lifecycle.** The `SupportSQLiteOpenHelper`
  callback's version IS `StoreFormat.VERSION` (2), so a `0` file gets `onCreate` (fresh), a `1`
  file — arc 11's Room kv — gets `onUpgrade` (**the wipe**, inside the helper's own version
  transaction, logged as a kv row count), and a file above 2 gets `onDowngrade`, which throws and
  leaves the file as found. `StoreFormat.decide` is the pure table (JVM-tested); the callbacks only
  act on it. `NonDestructiveOpenHelperFactory` still wraps every open. Foreign keys are enabled as a
  **pool** setting (`setForeignKeyConstraintsEnabled` in `onConfigure`), not a per-connection PRAGMA
  — WAL readers are separate connections, and a PRAGMA in `onOpen` would only have reached one.
- **Chunker in `:extension-api`, not the host** (`StoreChunker`, beside `StoreCodec`): it is codec
  arithmetic (`rowsHeaderBytes` + `rowBytes` are the exact bytes the writers produce, pinned by
  test), and the caps are raised *at the row that crosses*, so the host's `RowSink` stops reading
  there rather than materializing the rest. A result that ends exactly on a chunk boundary gets no
  empty trailing chunk; an empty result gets its one.
- **`StoreSql` tolerates ONE trailing `;`** (any other `;` outside a literal is the one-statement
  refusal) — the DDL in the X2–X4 schemas is written `;`-terminated and refusing that would have
  been a paper cut with no safety behind it. **A query cannot smuggle a write under `WITH`**:
  `INSERT`/`UPDATE`/`DELETE` anywhere in a query, and `REPLACE` followed by `INTO`, are refused
  (`replace(x, y, z)` the function still passes) — `rawQuery` of a `WITH … DELETE` would run it.
  DDL refuses a second head word anywhere (`… DROP` inside a `CREATE`), plus
  `VIEW`/`TRIGGER`/`VIRTUAL`/`TEMP`/`TEMPORARY`. Object names (table/index created, altered,
  dropped, and an index's `ON` table) must be **bare** `StoreNames`; column names are the
  extension's business (`"order"`, `pageId` pass). The reserved-space check applies to every WORD
  and quoted identifier in every kind, so `sqlite_version()` is refused too — a harmless loss.
- **`StoreSchema` construction is the DDL validator run** (every statement `checkDdl`'d, the table
  cap counted statically over `CREATE TABLE`s across all steps); version `1..256` (a schema of zero
  steps is a bug, not a declaration). `Cell.of` + `Statement(sql, vararg args)` +
  `StoreReads.all(store, sql, vararg)` / `StoreReads.exec(...)` are the extension-facing sugar;
  `Row` typed accessors throw `IllegalArgumentException` on a wrong storage class (the "bad row =
  dropped" rule an extension can catch), except that an INTEGER reads as a REAL (SQLite's own
  affinity).
- **The gate's `exec`/`query` are `declared`-gated per binder** (a boolean flipped by the first
  successful `applySchema` on that binder — `schemaVersion` itself needs no declaration). A schema
  step that fails rolls back *that step* and keeps the version; the binder stays declared if an
  earlier `applySchema` succeeded.
- **`ExtensionContract.minApiVersion(action)` / `accepts(action, version)`** hold the floor rule
  (pure, in the contract — the registry calls `accepts`). `DocumentContract` grew the pinned
  `PREFS_TABLE`/`PREFS_KEY_COLUMN`/`PREFS_VALUE_COLUMN`/`PREF_TEXT_SIZE`;
  `DocumentPdfRender.editorTextSizeSp` reads through `ExtensionStoreDatabase.hasTable` + the
  executor (no binder) and answers the default until X4 creates the table.
- **The three stubs**: `ScratchStore` (reads null / `load` + writes throw `StoreUnavailable`),
  `TagStore` (`readBlob` null, `read` = `TagIndex.EMPTY`, `write` throws), `EditorPrefs` (defaults
  and no-ops; the size ladder + `DEFAULT_TEXT_SIZE`/`PREVIEW_BUMP` stay — the screen lays itself
  out with them). Their KV-era tests (`ScratchDocumentTest`, `ScratchReceivedUndoTest`,
  `ScratchStoreReceiveTest`, `TagStoreTest`, `TagWritesTest`, `EditorPrefsLayoutTest`, both
  `FakeExtensionStore`s) are deleted; the pure codec tests (`ScratchPageCodecTest`, `TagCodecTest`,
  `CompactIdTest`, `CaretMemoryTest`, `UserWordsTest`) stay until their codecs go in X2/X3/X4.
  `TagWrites`, `TagClient.snapshot`, `ScratchPageCodec` and `LargeValue`-returning `snapshot` all
  still compile untouched — X3's business.
- **The debug self-test recreates its own files each run** (`probe.test.db` + sidecars deleted,
  then `ExtensionStores.closeAll()` first — a cached store is never closed otherwise) so the create
  door is proved every time, and builds `probe.legacy.db` itself (`SoilCrypto.createRaw` → `kv` +
  `room_master_table` + two rows + `user_version 1`) for the wipe proof. The Nomad's arc-11
  `probe.test.db` (4.3 MB kv) is gone with the first run — the probe's own file.

**Walk (Haiku, the SAF/backup items re-driven by hand): all green.** Self-test on the Nomad:
`Extension store: OK (open 2010ms · 5 000 rows in 2398ms · read back 2 chunks in 894ms · legacy
wipe 1983ms · probe.test.db)` with `fresh store for probe.test` and `wiped legacy store for
probe.legacy (format 1, 2 kv row(s) dropped)` in the log — the create door, two ashmem batches,
a two-chunk read-back byte-exact, the constraint rollback, v2 then v1 refused, three
`IllegalArgumentException` refusals, `STORE_SCHEMA_UNAPPLIED`, wrong uid, revoked, and the wipe.
**The floor, live:** `ExtensionRegistry` skips the tag service (`api version 5 outside 6..6`),
the pad (`1 outside 6..6`) and the editor (`2 outside 6..6`); the recognizer answers `1
provider(s) of 1`, exporters `3 of 3` (soil + pdf + the Document APK's document exporter — a
stateless point, still accepted at 3), importers `2 of 2` (soil + text). The library sheet has no
Tags… row; the notebook's top bar is Back · Contents · Pen · Eraser · Lasso · Recents (no pad,
Document or tag button); the search dialog's hint is "Folder or notebook name". Export reached the
picker (Tags.pdf; the format chooser lists PDF + `.soil` only — the document row is gated on a
document existing, as before); Import reached the picker; a backup run to the remembered folder
logged `1 copied, 40 up to date, 1 excluded … stores 5 copied / 0 failed` — the three extension
stores plus both probe stores. `logcat -b crash` empty. **Two timings worth keeping:** a cold
create is ≈ 2.0 s and a legacy open-and-wipe ≈ 2.0 s (both one native KDF); 5 000 1-KiB rows
land in ≈ 2.4 s and read back in ≈ 0.9 s.
**Left for X5:** every doc (`docs/extensions.md` § store and the audit rows, `scratchpad.md`,
`tags.md`, `document.md`, `backup.md`'s one sentence, `BACKLOG.md`'s two entries). Both CLAUDE.mds
carry the X1 truth already.

### X2 — Scratch pad on rows ✅ (Opus code · Sonnet scaffold/tests · Haiku walk)
`:ext-scratchpad` declares 6 and schema v1:
```sql
CREATE TABLE page   (id TEXT PRIMARY KEY, position INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL,
                     createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL);
CREATE INDEX page_position ON page(position);            -- non-unique: renumbering shifts in place
CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE,
                     "order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL,
                     blob BLOB NOT NULL);                 -- blob = StrokeCodec format B, unchanged
CREATE INDEX stroke_page_order ON stroke(pageId, "order");
CREATE TABLE state  (key TEXT PRIMARY KEY, value TEXT NOT NULL);   -- 'current' → page id
```
`ScratchStore` becomes SQL builders + thin calls; `ScratchDocument` keeps its in-memory model and
undo stack but its dirty/flush path becomes an **op log** (insert / delete / update-blob per
stroke, page insert/delete/renumber) flushed as one `exec` batch — the whole-page re-encode is
gone. **Delete:** `ScratchPageCodec`, `PageFullException`, `encodedBytes`/`sizes`/`strokeBytes`,
`SCRATCH_PAGE_FULL`, the "page full" dialog + string, `ScratchPages`' list-as-storage role (the
list math may stay as pure position arithmetic). `receive` writes page + strokes in **one batch**
(the hand-rolled ink-first/compensating-delete choreography goes — the transaction is the
guarantee); transfer caps (`MAX_TRANSFER_*`, Binder budget) stay. Page load = keyset paging by
`"order"` in `STORE_MAX_VALUE_BYTES`-sized reads via `StoreReads` so no page can ever hit
`STORE_RESULT_LARGE`. Never Main (unchanged). JVM: builders (exact SQL + args), op-log coalescing
(add-then-erase same stroke = nothing), receive batch shape, row→Stroke decode (bad blob = that
stroke dropped, page still loads — the `StrokeRows` rule), undo replay ops. Walk: draw, flip,
insert/delete pages, kill + reopen keeps everything, both transfers, **user checklist:** send the
"Document" notebook's ink (5 073 strokes across 20 pages) to ONE pad page repeatedly until well past
the old 4 MiB mark and confirm it loads and draws — the ceiling is gone.
**Questions at phase start:** none expected. Confirm the flip.

**Outcome (2026-09-01, Opus code + Fable review; 1690 JVM tests/variant, +59 — 58 new pad tests
against the codec's 8 deleted, +1 codec-arithmetic pin; ten modules debug + release, both changed
release APKs signed, NUL-scan clean; no docs — X5):** implemented as the phase reads, on a Fable
brief that fixed twelve calls beyond it. What binds from here:

- **Two write ops, both idempotent.** Stroke rows are `INSERT OR REPLACE` / `DELETE … WHERE id`;
  page rows are `INSERT OR IGNORE` + `UPDATE`; positions are renumbered per id (page counts are
  tens); `state('current')` is `INSERT OR REPLACE`. **Never `INSERT OR REPLACE INTO page`** —
  REPLACE deletes the conflicting row first and, with `foreign_keys` ON, that delete CASCADES the
  page's strokes. Every SQL string lives in `ScratchSql` (pinned by `ScratchSqlTest`).
- **A write is split into ≤ 4 MiB / ≤ 10 000-statement `exec` batches** (`ScratchBatches`, over
  the new `StoreCodec.statementBytes` + `STATEMENTS_HEADER_BYTES` — codec arithmetic, pinned
  against `encodeStatements(...).size`). One batch is one transaction and therefore atomic — every
  ordinary flush, page op and placement. Past it the write is several transactions, and the caller's
  retry converges *because* every statement is idempotent. `receive` **compensates** a multi-batch
  failure (new page: `DELETE FROM page` cascade + positions back; current page: one `dropStroke`
  per minted id — never an `IN (…)` list, the 999-arg cap) before throwing `StoreUnavailable`.
- **Reads are planned, never refused.** `readPage` = size row, then `SELECT "order", LENGTH(blob)`
  (small), then `ScratchReadPlan.ranges` packs consecutive strokes under the 4 MiB budget
  (`ROW_OVERHEAD` 128, an over-estimate) and each range is one `BETWEEN` query through
  `StoreReads.all` — a page of any size comes back without ever meeting `STORE_RESULT_LARGE`.
  `StrokeRows.decode`: a bad row is a **dropped stroke, never a lost page** (counted, `Log.w`);
  arc 11's "unreadable page" state, its strings and dialogs are gone with the blob.
- **`ScratchDocument` is a `TreeMap<order, Stroke>` + an op log** (`Put(stroke, order)` / `Drop`
  per stroke id in a `LinkedHashMap` — coalescing is the map; an erase is always a `Drop`, because a
  `Put` may be a move of a row already stored). Flush snapshots + clears the log before the IO hop
  and on failure merges the snapshot back **under** newer entries. **Orders are a high-water
  mark, not the map's last key** (Fable review finding): erasing the tail stroke lowers the last
  key, so `lastKey + 1` would hand an erased stroke's order to the next one drawn and the restore
  would collide — `highWater` never lowers while the page is loaded, and two stored rows at one
  order (never written by this code) both survive a load (the second is re-put past the end).
  `Erased.Entry` carries the **order**; `Pasted` carries strokes **and orders**; `Page` carries
  `ink`/`afterInk` (`PageInk`) instead of blobs; `replayPages` emits `sizePage` only with an ink so
  a lone-page delete's redo can never write `0 × 0` over a known size; `deleteCurrent` takes the
  undo ink from memory after the flush (a page can be bigger than one read).
- **Deleted:** `ScratchPageCodec` (+ test), `PageFullException`, `SCRATCH_PAGE_FULL`,
  `ScratchPageFullException`, the pad's "Page is full" / "Page unreadable" and the host's "Scratch
  page is full" strings, `Add` (every committed stroke is taken), `refuse()`. `PLACE_TIMEOUT_MS`
  stays 10 s (a placement is now INSERTs, cheaper than the re-encode it was sized for).
- The pad's manifest declares **6**; the registry lists it again. `:ext-tags` / `:ext-document`
  untouched (X3 / X4).

**Walk (Haiku for the open + wipe + crash buffer; steps 3–7 re-driven by hand after the agent
declared swipes "unreliable" — the standing false-failure trap, they were fine): all green.**
First pad open on the Nomad's real arc-11 store: `wiped legacy store for
com.symmetricalpalmtree.notesproutsn.ext.scratchpad.dev (format 1, 10 kv row(s) dropped)` →
`begin: pages=1 in 153 ms` → `page … loaded: 0 strokes, 1 pages`, indicator `1 / 1`. Swipe past
the end inserts (`2 / 2`, `3 / 3`), swipe back flips (`2 / 3`); long-press → "Delete this page?" →
Delete lands on `1 / 2`; Back → `end` / `finish: end ok`; `am force-stop` of BOTH processes then
relaunch + reopen → `begin: pages=2 in 137 ms`, opens on `1 / 2` (the pages and the current page
survived the kill). A notebook's top bar is Back · Pen · Eraser · Lasso · … · Recents · **Scratch
pad** again; its pad shows **Send page to notebook**; Back returns to the notebook. `logcat -b
crash` empty; no `dropped` / `compensating` / `failed` line from `ScratchStore` /
`ScratchDocument`. The store file stays 4.3 MB after the wipe (freed pages, no VACUUM in the
ladder — cosmetic; a future compaction question, not this arc's). **User checklist (7ac960f7): items 1–3 passed (ink + erase + undo/redo + lasso-move
survive a force-stop; both placements arrive selected with undo/redo; both Send directions paste
selected); item 4 — the >4 MiB single-page stress — SKIPPED by the user. The ceiling's removal is
proven structurally (no cap anywhere in the write path, keyset reads) and by the JVM split/plan
tests, not by an on-device page past 4 MiB.**
**Left for X5:** every doc (`scratchpad.md` data model + failure table, `extensions.md` module
table/audit rows, `BACKLOG.md`).

### X3 — Tags on rows + the search merge ✅ (Opus code · Fable brief + review · Fable walk by hand)
`:ext-tags` declares 6 and schema v1:
```sql
CREATE TABLE tag        (id TEXT PRIMARY KEY, display TEXT NOT NULL, identityKey TEXT NOT NULL UNIQUE,
                         createdAt INTEGER NOT NULL);
CREATE TABLE assignment (tagId TEXT NOT NULL REFERENCES tag(id) ON DELETE CASCADE, notebookId TEXT NOT NULL,
                         pageId TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL,
                         PRIMARY KEY (tagId, notebookId, pageId));   -- '' = notebook tag (NULL ≠ NULL in a PK)
CREATE INDEX assignment_target ON assignment(notebookId, pageId);
```
`ITagManager` v6: `begin`/`configureShowing`/`end` unchanged; **`snapshot` replaced** by
`List<TagRecord> tags(IExtensionStore store, int offset)` (pages of `TAGS_PAGE` 500 — 5 000 × ~250
parcel bytes would not fit one Binder transaction) and `List<AssignmentRecord>
assignmentsOf(IExtensionStore store, in List<String> tagIds, int offset)` (pages of
`ASSIGNMENTS_PAGE` 1 000); `assign` keeps its signature, now `INSERT OR IGNORE tag` +
`INSERT OR IGNORE assignment` in one batch under the policy caps. Records are Parcelables with
`requireValid`; page id `''` on the wire means notebook tag (the no-kind rule stands). **Delete:**
`TagCodec`, `CompactId`, `WORST_CASE_BYTES`, `MAX_TAG_ID_CHARS`, `TagStore.KEY_INDEX`,
`TagWrites`' process-local lock (the transaction is the lock, across both writers and both
processes), the `IndexUnreadable` failure (an unreadable store is "unavailable" now — there is no
blob to be half-read). `TagIndex` shrinks to the **screen's in-memory tag list** (loaded once per
showing, live-filtered per keystroke — the arc-21 "never a store call per keystroke" lock stands);
assignments for the showing's target(s) are queried; `usageOf` = one `GROUP BY pageId = ''`;
delete-with-blast-radius = one `DELETE FROM tag` (cascade). Host: `TagClient.tags`/`assignmentsOf`
with **timeouts re-measured on the Nomad** (size by the work — the W6 rule); `SearchAssembly.rank`
ranks over `tags()` then asks `assignmentsOf(matched)` and groups exactly as today (own-tag =
`pageId == ''`); `LibrarySearch` keeps one fetch per query run; `TagManagerEntry`/`TagTargets`
unchanged. JVM: builders, record validation, rank over the two-query shape (same fixtures as W4's
tests, re-expressed), assign cap checks, cascade expectations as SQL text. Walk: every arc-21 door
(library sheet, three notebook buttons, lasso Tag on a heading and on ink, search with page cards)
re-driven; tag / untag / delete-with-cascade; `logcat -b crash` empty.
**Questions at phase start:** none expected. Confirm the flip.

**Outcome (2026-09-01, Opus code on a Fable brief + Fable review; 1717 JVM tests/variant, +27 —
27 new tag tests against `TagCodecTest`, `CompactIdTest` and the old `TagIndexTest`'s edit/cap/`of`
tests deleted; ten modules debug + release, both changed release APKs signed, NUL-scan clean (one
raw NUL landed in a new test during the phase and was caught by the scan — the trap's 8th firing);
no docs — X5):** implemented as the phase reads. What binds from here:

- **Schema + SQL exactly as planned.** `TagSchema.V1` = `tag` (`identityKey` stored `UNIQUE`) /
  `assignment` (PK `(tagId, notebookId, pageId)`, `pageId` `''` = notebook tag, `ON DELETE CASCADE`)
  + `assignment_target`; every statement in `TagSql` (pinned by `TagSqlTest`, every one through the
  real validator in both the test and the fake — **no validator refusal was met**: `EXISTS(…)`,
  `SUM(pageId = '')`, `COUNT(*)`, `INSERT … SELECT … WHERE` and the `IN (?, …)` list all pass).
- **`assign` = two small reads + ONE two-statement transaction, and the caps ride INSIDE the
  inserts.** `selectTagByIdentity` answers "exists + already attached" in one read (attached →
  `changed = false`, nothing written); the batch is `insertTag` (`INSERT OR IGNORE … SELECT ?,?,?,?
  WHERE (SELECT COUNT(*) FROM tag) < ? AND (SELECT COUNT(*) FROM assignment) < ?`) only when the
  identity was absent, then `insertAssignment`, which **resolves the tag id BY IDENTITY inside the
  statement** (`SELECT id, ?, ?, ? FROM tag WHERE identityKey = ? AND (SELECT COUNT(*) FROM
  assignment) < ?`) so a concurrent creator of the same tag cannot leave a dangling id; the
  post-write re-read turns `OR IGNORE`'s silence back into `TAG_INDEX_FULL` and answers the
  **stored** display (first-entered casing even when the other writer won). **Fable review finding,
  fixed before the walk:** the tag insert is gated on the *assignment* cap too — a new tag created
  for an attachment the assignment cap then refused would have been an orphan row behind a "nothing
  was written" sentence. `TagWrites` and its process-local monitor are deleted: **the transaction is
  the lock**, across both writers and both processes.
- **`ITagManager` v6:** `snapshot` → `tags(store, offset)` (pages of `TAGS_PAGE` 500, order
  `identityKey, display`) + `assignmentsOf(store, tagIds, offset)` (pages of `ASSIGNMENTS_PAGE`
  1 000, ≤ `ASSIGNMENT_QUERY_TAGS` 500 ids per call — one `IN (…)` under the 999-bind cap; the host
  chunks). `TagRecord(id, display)` / `AssignmentRecord(tagId, notebookId, pageId)` are the
  `requireValid` parcelables; `TagPages.collect` is the ONE paging loop (short page ends it, runaway
  guard = cap/page + 1), in `:extension-api` because both sides run it. No ashmem anywhere on the tag
  path — `onTransact`/`pending` are gone from the service.
- **`TagRules.isId` replaces `CompactId.isId`** and is deliberately **case-insensitive** on the hex
  (Opus call, accepted): `CompactId` already was, and arc 16's `SafeImportId` admits upper-case ids
  out of a stranger's `.soil` — tightening it would make an imported notebook's pages untaggable
  (`TagShowing`'s `require` would refuse the showing).
- **`TagIndex` moved into `:ext-tags`** as the screen's in-memory model (queries only, built from
  the two reads `tags()` + `assignmentsOfNotebook(showing.notebookId)` in every mode; BROWSE/ADD on
  a page filter the notebook's few rows in memory). Every edit is still **written before it is
  shown** — the screen re-reads both after each write, which is also how another writer's edit
  arrives. `usageOf` is a read now (`SUM(pageId = '')` / `SUM(pageId <> '')`), run on IO behind the
  busy latch before the delete confirm is built. `RESULT_OK` only when something changed.
- **Host:** `TagClient.search(ctx, ref) { tags -> ids }` = one pre-open + ONE bind: page tags →
  the host's `SearchAssembly.matchTags` runs inside the block → page `assignmentsOf` per 500-id
  chunk (an empty selection asks nothing). `SearchAssembly.rank(folders, notebooks, query,
  TagMatches?, assignments)`; `LibrarySearch` captures the matches out of the lambda.
  `SEARCH_TIMEOUT_MS` **10 s** (first cut — the Nomad measured **52–78 ms** end-to-end on a 2-tag
  index: `tags` 15–19 ms, `assignmentsOf` 11–18 ms; the worst case, 10 tag pages + 50 assignment
  pages, was not built as test data, so the budget stays generous and documented as such);
  `ASSIGN_TIMEOUT_MS` 8 s → **4 s** (the work is two indexed reads + one transaction now — W6's
  size-by-the-work rule, not taste). Deleted host-side: `TagIndexUnreadableException`,
  `INDEX_UNREADABLE`, `tags_unreadable_body`, `SNAPSHOT_TIMEOUT_MS`.
- **Test doubles:** `FakeTagStore` (statement recorder + canned rows) **applies** the four writes
  literally — `OR IGNORE`, the identity resolution, both `COUNT` caps — because `assign`'s post-write
  re-read must see the write; it has a `beforeExec` hook so the concurrent-create race is a test
  (`theTagCapRefusesAndNothingIsWritten`, `theAssignmentCapRefusesANewTagWithoutCreatingIt`,
  the second-writer-attaches-by-identity case). Caps are `TagStore` constructor parameters so the
  cap tests exercise the real statement with small numbers (X2's `maxPayloadBytes` precedent).
- Manifest declares **6**; the registry lists it again (`TAG_MANAGER: 1 provider(s) of 1`). Only
  the Document editor is still gone (X4).

**Walk (Fable, by hand on the Nomad — the walk agent was not used this phase): all green.** First
tag-store open on the real arc-21 file: `wiped legacy store for …ext.tags.dev (format 1, 1 kv row(s)
dropped)`; MANAGE overview from the two reads (`loaded: 0 tags, 0 assignments`); Page 1 → typed
`rows` on the on-screen keyboard → landed (the real `assign` SQL), toggled off and on from the
list, long-press → "Delete “rows”? Remove it from 1 page?" (the `usageOf` read) → cancelled;
overview shows `Page 1 · rows`; Tag-notebook quick door opened with the keyboard raised (MODE_ADD)
→ `shelf`; library search `rows` → ONE page card `20260827_200914 · Page 1 / Notebooks · rows`
(`tags: 2 from 0 in 19 ms` · `assignmentsOf: 1 rows for 1 tag(s) in 18 ms` · `search: 2 tags, 1
assignments in 78 ms` · `PageNumbers: read 1 page ids`); search `shelf` → the notebook card with
subtitle `Notebooks · shelf` (58 ms); library sheet **Tags…** → BROWSE (`open: ready in 40 ms`,
`loaded: 2 tags, 2 assignments`); long-press `shelf` → "Remove it from 1 notebook?" → **Delete** →
the tag and its assignment gone (cascade), `rows` still listed; back to the shelf → `search: 1
tags, 0 assignments` and no `assignmentsOf` call at all (empty selection). `logcat -b crash` empty.
Observed, pre-existing (arc 21 / W4 shape, not X3's): the search shelf re-runs its query **twice**
on return from a tag screen (`onChanged` + the resume re-list, 10 ms apart) — cheap, noted for X5's
BACKLOG line. **User checklist:** the lasso's Tag on a heading and on ink (pen input — adb cannot
drive it), which exercises the host's `TagClient.assign` path and the 4 s budget — **PASSED by the user 2026-09-01 ("All tests pass"; commit 26b8d609)**. Nomad test data
left behind: tag `rows` on Page 1 of 20260827_200914; every arc-21 tag was wiped by design.
**Left for X5:** `docs/tags.md` (schema, two queries, the deleted caps arithmetic, `isId`),
`extensions.md` (tag-point section + `API_VERSION` ledger + audit rows), `library.md` § search.

### X4 — Document editor on rows ✅ (Opus code on a Fable brief + Fable review · Haiku walk, two items re-driven by hand)
`:ext-document`'s editor service declares 6 (the text importer keeps 3 — per-service meta-data);
schema v1:
```sql
CREATE TABLE prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL);                 -- 'size', 'proofread' (absent = on)
CREATE TABLE word  (word TEXT PRIMARY KEY, addedAt INTEGER NOT NULL);           -- the user dictionary
CREATE TABLE caret (pageKey TEXT PRIMARY KEY, offset INTEGER NOT NULL, updatedAt INTEGER NOT NULL);
```
`EditorPrefs` keeps its blocking, fetch-the-binder-per-call, every-exception-is-the-default shape;
`rememberCaret` = `INSERT OR REPLACE` + the LRU trim `DELETE FROM caret WHERE pageKey NOT IN
(SELECT pageKey FROM caret ORDER BY updatedAt DESC LIMIT 100)` in one batch; the dictionary's
add/remove are single statements (no read-modify-write, no `wordsLock`); the proofread engine's
words-before-engine publish order stands. **Delete:** `CaretMemory`'s codec, `UserWords`' line
codec (the normalization rule stays as a pure function), `EditorPrefsLayoutTest`. Host:
`DocumentPdfRender` now finds the pinned `prefs` table (X1 wired the read). JVM: builders, trim
statement, decode of each pref, dictionary normalization. Walk: text size survives kill; proofread
toggle; add-to-dictionary survives `am force-stop`; caret restore per page + `nb:<id>`;
Document-PDF export at a non-default size.
**Questions at phase start:** none expected. Confirm the flip.

**Outcome (2026-09-01, Opus code on a Fable brief + Fable review; 1738 JVM tests/variant, +21 —
41 new editor-store tests against the deleted `CaretMemoryTest` + `UserWordsTest`; ten modules
debug + release, both changed release APKs signed, NUL-scan clean; no docs — X5):** implemented
as the phase reads. What binds from here:

- **Schema + SQL exactly as planned.** `EditorSchema.V1` = `prefs` (`key`/`value`, the DDL built
  from `DocumentContract`'s pinned names — the one table the host reads) / `word` (the word IS the
  primary key) / `caret` (`pageKey`, `offset`, `updatedAt`); every statement in `EditorSql`
  (pinned by `EditorSqlTest`, each through the real validator — `key`, `value` and `offset` are
  SQLite fallback keywords and pass unquoted, on the JVM and on the Nomad). `INSERT OR REPLACE` is
  used for a pref and a caret and the KDoc says why it is safe here (no table has children — X2's
  `REPLACE INTO page` rule needs restating wherever REPLACE appears). `insertWord` is `OR IGNORE`
  (a re-add keeps its `addedAt` — the manage list's order); `selectWords` orders `addedAt, word`
  (a total order). `rememberCaret` = **ONE `exec` of two statements** — the upsert, then
  `DELETE … NOT IN (SELECT pageKey … ORDER BY updatedAt DESC LIMIT ?)` with `CARET_LIMIT` 100
  **bound**, not written into the text.
- **`EditorStore` is the one place SQL runs** (X3's `TagStore` shape: blocking, applies the schema
  on every public call — the binder is fetched per call and a restarted host lends an undeclared
  one — and lets every exception through). `EditorPrefs` is the thin facade the callers already
  used: same names and signatures, `EditorSession.store` per call, **every exception is the
  default**, the `limitedParallelism(1)` lane for `rememberCaretAsync` kept for ORDER (the leave
  path's newer caret), `caretLock` / `wordsLock` deleted — no read-modify-write remains.
  **Deleted:** `CaretMemory`, `UserWords` and both tests; no new `UserWords` object was minted —
  `SpellEngine.normalizeWord` already is the normalization rule and every caller applies it.
  `EditorPrefsLayoutTest` had already gone in X1.
- **Implementer calls (Opus, accepted):** `caret()` narrows the INTEGER with `coerceIn(0,
  Int.MAX_VALUE)` (a `Long` needs the upper bound too); no `load()` door on `EditorStore` (no caller
  needs one — the per-call apply covers it); `EditorPrefsTest` reaches the binder through the real
  `EditorSession.begin(fake, FakeHost)` with a 12-line refusing `IDocumentHost` fake rather than a
  test seam on the object; the two sibling-service manifest comments that said "the editor above
  keeps its 2" were corrected (the values stay 3). **Fable review, two fixes before the walk:** the
  schema KDoc credited the host with the scratch pad's `state` table (it is `:ext-scratchpad`'s —
  the true host precedent is `DocumentPdfRender`'s own unquoted `key`/`value` read), and
  `rememberCaret` clamps a negative offset on the way in as arc 19's encoder did.
- **`FakeEditorStore`** applies its five writes (upsert pref / caret, `OR IGNORE` word, two
  deletes, the LRU trim by `updatedAt DESC LIMIT n`) so read-after-write is real; `EditorStoreTest`
  proves the trim evicts the oldest past the limit and that every failure propagates (the store's
  half), `EditorPrefsTest` that a null or failing store answers every default and makes no call
  (the facade's half). Manifest: the editor service declares **6**; the text importer and the
  document exporter keep **3** (per-service meta-data; neither takes a store).

**Walk (Haiku, W4 + W7 re-driven by hand — both were quoting artifacts, see the new standing trap):
all green.** `ExtensionRegistry`: `DOCUMENT_EDITOR: 1 provider(s) of 1 candidate(s)` — the Document
button is back in the notebook's top bar; first editor open: `wiped legacy store for
…ext.document.dev (format 1, 4 kv row(s) dropped)` (the arc-19 `size` / `carets` / `proofread` /
`dict` keys); text size 16 → 21 survived `am force-stop` of both processes (then restored to 16);
caret per page — page 1 at 40 and page 2 at 6 each restored on flip and after a kill (the editor
reopens on the page it left); the notebook document's `nb:` caret restored across two scope
toggles; proofread off survived a kill (then turned back on); a nonsense word added from the popup
took the flag count 1 → 0 and after a kill the dictionary loaded `83627 words + 1 user words` with
the flag still absent; the store file re-stamped today; export reached the SAF picker;
`logcat -b crash` empty. **User checklist:** a Document-PDF export at a non-default text size (the
size read is the host's own `prefs` query, and a SAF pick cannot be driven) — **PASSED by the user
2026-09-01 ("The test passed"; commit 2fa9c4aa)**. Nomad test data left
behind: one nonsense user-dictionary word, page-document text on pages 1–2 of 20260827_200914 plus
a seeded notebook document; text size back at 16, proofread on. **Left for X5:** `docs/document.md`
(the three tables, the deleted codecs, `EditorStore`), `extensions.md` (module table, audit rows,
the `prefs` read), and `document.md` § Proofread (the dictionary as rows).

### X5 — Docs, ledger, freeze ✅ (Sonnet docs · Fable read them back)
**No code review in this arc (user's call).** `docs/extensions.md` § store rewritten (contract,
caps, validator rules, codec, chunk protocol, schema lifecycle, the floor, the user_version ladder,
the self-test), the `API_VERSION` ledger (6 = arc 22 — the second break, the first with a floor),
module table, and **the boundary audit re-walked** (rows 1, 5, 14, 16, 19–22 change; add rows for
the SQL gate, reserved names, the `prefs` read); `docs/scratchpad.md` data model (rows, no
ceiling); `docs/tags.md` (schema, two queries, deleted caps arithmetic); `docs/document.md` (three
tables); `docs/backup.md` (a legacy-shaped store in an old backup resets on open — one sentence);
app `CLAUDE.md` store paragraph + module lines; root `CLAUDE.md` ratta pointer; `BACKLOG.md` — the
W4 "key/value" entry closes (→ arc 22), the W6 "pruning dead tag assignments" entry is rewritten
(a prune is now `DELETE … WHERE notebookId NOT IN (…)`, still wanting a trigger decision), the W5
restore entry unchanged; `RATTA_PLAN.md` ledger entry for arc 22 + compaction of this section into
it; memory. **Version stamp stays `0.1.0-ratta`** unless the user says otherwise at phase start.
**Questions at phase start:** the version stamp only.

**Phase-start decision (user, 2026-09-01):** version stays **`0.1.0-ratta`** (every arc's answer).

**Outcome (2026-09-01, Fable orchestrating four Sonnet doc agents in parallel + Fable read-back
of every rewritten section; no code, no code review — the user's call; gates re-run on the
unchanged tree: 1738 JVM tests/variant, 0 failures, ten modules debug + release, host release
signs, NUL-scan clean on all ten changed files):**

- **`docs/extensions.md` 1404 → 1725.** § "The extension store (arc 11 / J2 — rebuilt on tables,
  arc 22)" rewritten whole as sixteen subsections (contract · caps table · validator · codec ·
  chunk protocol · schema lifecycle · the floor · the `user_version` ladder · Room left the store ·
  executor split · gate · three exceptions restated · the one host-read table · self-test with the
  Nomad timings · backup · verification by test class); `API_VERSION` ledger gains 6 (the second
  break, the first with a floor — and why 5 needed none); module + identity tables at 6 for every
  store-taking service, 3 for the two stateless Document services; scratch-pad, document-editor and
  tag-manager sections moved to rows (the tag section gained "the two-query search merge", "the
  transaction is the lock", "the caps — policy now", "`TagIndex` moved", "`isId` is
  case-insensitive"); **boundary audit re-walked** — rows 1, 5, 14, 16, 19–23 rewritten (23 also
  named the deleted `TagClient.snapshot`), rows **24–26 new** (the SQL gate · reserved name spaces
  · the `prefs` read). Fable read-back fixed three slips: the ladder's transaction credited to
  Room (it is the open helper's), and "Room, SQLCipher, ashmem and a Binder cannot run on the JVM"
  twice (Room is not in the store any more).
- **`docs/scratchpad.md` 221 → 304.** § "Pages and the store" rewritten (DDL verbatim, two
  idempotent ops + the REPLACE-cascade rule, the op log and high-water mark, `ScratchBatches`,
  planned reads, the ceiling's deletion — the skipped >4 MiB stress stated honestly, the legacy
  wipe and its unchanged file size); transfers step 5, the undo table (`afterInk`), failure table
  (two 4 MiB rows + "unreadable" out; bad row / multi-batch compensation / legacy wipe / refused
  format in), "Where the code is". **`docs/backup.md` +3**: the one sentence.
- **`docs/tags.md` 493 → 628.** § "The data model" rewritten with a new "`assign` — two reads, one
  transaction" subsection quoting both inserts; `TagCodec` subsection and the v1→v2 migration
  deleted; seam (two paged queries, 4 → 5 → 6), screen (`TagIndex` query-only, `usageOf` before
  the confirm), search door (`TagClient.search`, the two timeouts with the Nomad numbers), backup,
  failure table (unreadable rows out; refused format / legacy wipe / concurrent create in), traps
  retired and added. **`docs/library.md` 846 → 858**: the tag-fetch paragraphs of § search only.
- **`docs/document.md` 538 → 650.** A new § "The editor's store" (the doc had no store section to
  rewrite — its "Data model" is the `document` row); Export's `prefs` read; Proofread's `word`
  table; failure table + Related. Fable read-back fixed one sentence: the wipe creates
  `host_schema`, not the extension's tables (those arrive on the next `applySchema`).
- **This file:** header (arcs 1–22 frozen, no next arc planned), Architecture (after arc 22:
  `API_VERSION` 6 + floor, the store paragraph), the Arc 22 ledger entry under "Frozen arcs", this
  section marked complete. **`BACKLOG.md`:** the W4 key/value entry CLOSED (what shipped vs. what
  it sketched — a replacement, not an append); the W6 pruning entry rewritten (a prune is a
  `DELETE` now, two shapes under the 999-bind cap, trigger still a user decision); two new notes
  (X3: the search shelf queries twice on return from a tag screen · X2: a wiped store keeps its
  file size, `VACUUM` is host-side if ever). **Both CLAUDE.mds** (`:ext-document`'s "editor keeps
  2" corrected to 6; doc-list lines name the tables; root pointer = arcs 1–22 frozen, no next arc
  without a user decision) and memory.
- **Every doc agent reported code = plan** on every number, SQL string and signature it checked;
  the doc pass found no code defect this time. Agents were told not to cite boundary-audit row
  numbers from other docs (the audit was being renumbered concurrently) — cite it by topic.

**Status:** ✅ Complete. **Arc 22 "Tables" frozen.** No next arc is planned.

### Per-phase gates (every phase, before its commit)
`./gradlew test` both variants green (record the count) · every module debug + release builds ·
both release APKs sign · NUL byte-scan of changed files (python, not grep) · the walk's FAILs
re-driven by hand before belief · docs/memory/CLAUDE.md updated · **commit + push** · `/clear`.

---

## Phases — Arc 21 "Tags" ✅ COMPLETE + FROZEN 2026-09-01
(planned 2026-08-31; W1 374ddc8 · W2 b748566 · W3 a4de84e · W4 a1a5031 · W5 bf30697 · W6 below)

Tags on **notebooks and pages** — the SIXTH capability point (`ACTION_TAG_MANAGER` + `_SCREEN`,
granted by the user 2026-08-31), the **third tier-2 screen-owning point**, and the **TENTH module**
(`:ext-tags`, **NSE · Tags**). Scratch-Pad-shaped: the extension owns the tag screen and its index
(in its own encrypted extension store, `Garden/<pkg>.db` — an extension still writes nothing to
disk itself); the host owns every entry point (library long-press row, notebook toolbar button +
secondary toolbar, lasso-toolbar button), the recognizer call, and the library-search merge.

**⚠️ Fable planned this arc and will NOT be available to execute it.** Opus implements every
feature phase *including the seam code* — the seam spec below is deliberately complete so no phase
waits on Fable. Sonnet scaffolds modules/layouts/resources/docs; Haiku walks the Nomad. If a phase
hits something genuinely outside this spec (crypto/key lifecycle, an engine gap, a schema
question), **stop and ask the user** rather than improvising.

### Locked decisions (arc-21 wizard 2026-08-31 — do not re-ask)

| Decision | Answer |
|---|---|
| Sixth point | **Granted explicitly** (the user's 2026-08-31 message is the decision the standing rule requires). No SEVENTH without another. |
| Seam shape | **Scratch-Pad-shaped tier-2**: extension owns the tag screen + the index in its extension store; host owns entries, recognition, search merge. |
| Identity | `:ext-tags` (TENTH module) · label **NSE · Tags** · pkg `…notesproutsn.ext.tags` · `ACTION_TAG_MANAGER` + `ACTION_TAG_MANAGER_SCREEN` · app icon Tabler `tag` outline. |
| Tag identity | **Trim ends + collapse internal whitespace runs + case-fold** = one tag; display form is the first-entered casing; cap **64 chars**; no other charset restriction. Multi-word tags are the point. |
| Lifecycle | A tag **persists until explicitly deleted** — removing its last assignment leaves it in the suggestion list. Delete lives behind **long-press on a tag in the list**. |
| Deleting an assigned tag | **Confirm naming the blast radius** ("Remove from N notebooks and M pages?"), then the tag and every assignment go. |
| Search | **One query runs names AND tags** through the same `core/FuzzyRank`. **Page hits appear as their own cards** (open the notebook AT that page). Order: folders → notebooks (name- or tag-matched, deduped, best rank) → page hits. |
| Lasso → tag target | **The current page**, always. Non-destructive — the ink/heading stays untouched. |
| Snapshot semantics | A tag is a **text snapshot** at creation: editing the source heading later never renames a tag; converting again creates/attaches another tag ("modifying creates a new tag"). |
| Heading → tag | **Silent** — one tap attaches the heading's text as a page tag, toast-confirmed. |
| Handwriting → tag | Recognize the selection → **prefilled input dialog for correction** → create/attach. |
| Visibility | **Only where you go looking**: the tag screen and search-result cards. No tag lines on library cards, nothing on pages. |
| Notebook toolbar | `ic_tag` in the top bar's **right cluster next to the Document button, before Recents** → secondary toolbar `[Tag notebook] [Tag page] [Manage]`. The first two open the tag screen for that target with the **add-input already focused**; Manage opens the notebook in **browse state — its tags AND every page's tags** for add/remove. |
| Library entry | Long-press action sheet gains a **"Tags…"** row (notebooks only — folders are not taggable) → tag screen for that notebook. |
| Backup | **Extension stores enter the arc-17 backup set** (all of `Garden/<pkg>.db` — tags, scratch pad pages, proofread dictionary all become durable). |

### Seam spec (planner-fixed — implement as written; deviations need a user decision)

**Contract additions (`:extension-api`):**

- `ExtensionContract`: `ACTION_TAG_MANAGER` / `ACTION_TAG_MANAGER_SCREEN`
  (`com.symmetricalpalmtree.notesproutsn.extension.TAG_MANAGER[_SCREEN]` — match the existing
  namespacing exactly), and **`API_VERSION` 3 → 4** (the tag point is the version-4 event;
  `:ext-tags` services declare 4, every other extension's declarations stay put; host accepts
  `1..N` as always — record the event in the constant's comment, D3/M8 recipe).
- Caps, each pinned by test: `MAX_TAG_CHARS` 64 · `MAX_TAGS` 5_000 · `MAX_TAG_ASSIGNMENTS`
  50_000 · the serialized index must fit `STORE_MAX_VALUE_BYTES` by construction (assert in the
  codec test with worst-case sizes).
- **`ITagManager`** (AIDL), one interface serving both call patterns — the store rides the calls
  that need it, per the store's own rule:

  ```
  void       begin(IExtensionStore store);                 // held-bind bracket for a showing
  void       configureShowing(in TagShowing showing);      // after begin, before launch — nothing rides the Intent
  void       end();                                        // drop the store + parked showing
  LargeValue snapshot(IExtensionStore store);              // call-shaped: whole index as a TagCodec blob (ashmem)
  String     assign(IExtensionStore store, String text, int targetKind, String targetId);
                                                           // call-shaped: normalize → create-if-absent → attach;
                                                           // returns the canonical display text (for the toast)
  ```

  Showings use `ExtensionBinder.hold` (scratch-pad bracket: pre-open store on IO → mint
  `ExtensionStoreBinder` → hold → `begin` → `configureShowing` → launch via
  `ActivityResultLauncher` → result → `end()` → close/revoke in one `finally`, `onDestroy`
  backstop). `snapshot`/`assign` use `ExtensionBinder.call` (bind-per-call, recognizer shape,
  pre-open rule still applies). Stub methods: `HostCallerCheck.enforce` first, only the three
  marshalable exceptions leave.
- **`TagShowing`** (Parcelable, `requireValid` in the constructor = unmarshal validation):
  `targetKind` (`TARGET_NOTEBOOK` 0 / `TARGET_PAGE` 1) · `targetId` (opaque UUID string — the
  M3 pageKey precedent) · `targetLabel` (display name the host resolved) · `mode` (`MODE_BROWSE`
  0 / `MODE_ADD` 1 / `MODE_MANAGE` 2) · `prefill` (nullable — the recognized text) ·
  `pageIds`/`pageLabels` (parallel string arrays, MANAGE only, else empty — page display numbers
  are the host's to name). Tag text and labels are user content: they cross the bind, **never**
  the Intent, and are never logged on either side (counts/lengths/durations only).
- **`TagCodec`** (pure, `:extension-api`, stdlib-only line codec — the `UserWords`/store-blob
  precedent, no serialization dep): storage form **is** the wire form. One blob: version line,
  then tag records (`id · identityKey · display`), then assignment records
  (`tagId · targetKind · targetId`). Unknown version throws ("unreadable, not empty" — the
  ScratchPageCodec rule); a truncated tail keeps what decoded whole; escapes tabs/newlines.
- **`TagRules`** (pure, `:extension-api`, shared by both sides + tests): `identityKey(text)`
  (trim, collapse `\s+` runs to one space, locale-neutral case fold), `isValid(text)`
  (non-blank after normalize, ≤ 64), display-form rule (first-entered casing wins).

**Extension side (`:ext-tags`):** no Application class, **no g-paper / no drawing surface** —
the first non-drawing tier-2 screen, so **no EPD handoff anywhere** (M3's measured answer:
stop-behind is enough for a non-drawing child screen, cross-process included — do not add
`releaseForHandoff`). `TagManagerService` parks the showing under one monitor (`begin`/`end`
take the same one); the screen Activity runs `HostCallerCheck.enforceActivity` first thing in
`onCreate`, reads the parked showing from the service (same process), and owns: the target's
current tags (tap a chip/row to remove), the add input with **live-filtered suggestions from the
in-memory snapshot** (never a store call per keystroke — the list region repaint is the accepted
EPD cost), the paginated all-tags list when the input is empty (prev/next pager, rows measured
against the real band — the Today/library idiom), long-press-delete with the blast-radius
confirm, and MANAGE's notebook + per-page sections. Store layout: key `index` = the `TagCodec`
blob (`put`/`get` at or under `STORE_MAX_INLINE_BYTES`, `putLarge`/`getLarge` above — fall to
large only on the exact `STORE_VALUE_LARGE` message; blocking, IO or Binder thread, never Main).
Chrome: SN design system, action buttons on the **top bar after Cancel** (F2 + the 2026-08-28
amendment), TopGuard 0, portrait, Ratta IME rules (never hide while the field has focus;
explicit show flag 0 — the arc-20 hard-keyboard finding). RESULT_OK when anything changed.

**Host side:** `TagClient` (scratch-pad `ScratchPadClient` shape); discovery via
`ExtensionRegistry` for the new action; **every entry point GONE when no tag extension is
installed** (menu row, toolbar button, lasso button — the Import-button precedent; search runs
names-only). Stale assignments (deleted notebooks/pages, arc-17 purge): the host **filters
snapshot targets against alive index rows at query time**; dead entries are tolerated in the
blob — pruning is a `BACKLOG.md` note, not this arc.

**Planner calls the wizard didn't cover** (implementer follows; user can override at phase start):
lasso Tag button shows when the selection is **exactly one heading and nothing else** (→ silent
flow) **or** a recognizer is READY-able (→ recognize flow; recognition area = the **selection
bounds**, never the page); any other/mixed selection recognizes the selection's strokes. No undo
for tag operations (not page content; confirm dialogs guard the destructive ones). Open-at-page
from search: reuse the notebook's existing open-at-page mechanism if one exists (link-follow /
Contents navigate by page id — check `docs/notebook.md`), else add an optional page-id extra
handled at load (ids in extras are the norm; names/queries are not).

### W1 — Point, module, tag screen core ✅
`:extension-api` additions above; `:ext-tags` module (manifest, icon, service, screen, store
layout, codec); host `TagClient` + discovery; **one real entry** so the walk is honest: the
library long-press sheet's "Tags…" row (notebooks only). Screen ships BROWSE + ADD complete
(current tags, add input + live suggestions, paginated all-tags list, long-press delete +
blast-radius confirm); MANAGE refuses politely until W2 (`UnsupportedOperationException` crosses
intact — the J3 precedent — but prefer simply not offering the mode yet). JVM: TagRules,
TagCodec (incl. unreadable-not-empty + truncated-tail), caps, gate/validation, snapshot/assign
logic over a fake store. Walk: tag a notebook, suggestion reuse across notebooks, remove, delete
w/ confirm, extension-disabled → row GONE.
*Opus: contract + service + screen logic + client. Sonnet: module scaffold, manifest, icon,
layouts, strings. Haiku: walk.*
**Questions at phase start:** none — wizard covered it. Confirm the phase flip only.

**Outcome (code + Nomad walk 9/9).** The SIXTH point is live. `:extension-api` grew
`ACTION_TAG_MANAGER` / `_SCREEN`, `API_VERSION` 3 → 4, the tag caps, `ITagManager.aidl`,
`TagShowing`, and three pure files (`TagRules` · `TagIndex` · `TagCodec`). `:ext-tags` is the TENTH
module (**NSE · Tags**, Tabler `tag` icon): `TagManagerService` · `TagStore` · `TagWrites` ·
`TagPaging` · `TagRowView` · `TagsActivity`. Host: `ExtensionRegistry.tagManager`, `TagClient`,
`TagManagerEntry`, the library sheet's **Tags…** row, `ic_tag` in `:sn-screen`.
**1563 JVM tests/variant** (+52). All ten modules build debug + release; release signs.

**Three implementer calls** (the seam spec is otherwise as written):
- **The codec's arithmetic did not close as specified.** `id · identityKey · display` with UUID ids,
  under the wizard's caps (5 000 tags / 50 000 assignments / 64 chars), is ~6.0 MB against a 4 MiB
  `STORE_MAX_VALUE_BYTES` — so the spec's own "must fit by construction" could not hold. **Every cap
  was kept** and the record shrank instead: `id · display` (the identity key is a pure function of
  the display form, so storing it was a second copy of the answer that could disagree with the
  question), **compact base-36 ids** (`TagCodec.MAX_TAG_ID_CHARS` 4 — the assignment table pays for
  an id 50 000 times) and `MAX_TARGET_ID_CHARS` 48. `TagCodec.WORST_CASE_BYTES` is now 3 900 007,
  and `TagCodecTest` fails if any cap moves past the budget.
- **Tabs and newlines are dropped, not escaped** — the `UserWords` rule the spec itself names as the
  precedent. `TagRules.display` collapses every whitespace run, so an escape layer would be
  unreachable code posing as a guarantee.
- **MODE_MANAGE is validated in the contract but not offered** (the plan's stated preference over a
  polite refusal): `TagShowing` accepts all three modes with MANAGE's page-array rules pinned, and
  W2 is what builds the screen half.

**New trap, cost an hour and reads as something else entirely:** a new capability point needs its
two actions in the **host's `<queries>` block** or `queryIntentServices` answers with **zero
candidates** for a service that is installed, exported, correctly signed and correctly versioned —
`0 provider(s) of 0 candidate(s)`, which looks exactly like a signature or API-version mismatch and
is neither. Both actions added (the service's *and* the screen's, the scratch-pad/document
precedent).

**Shape worth keeping:** `TagWrites` is the **one** read-modify-write of the index, taken by both
writers in the process (the screen on IO, the service's call-shaped `assign` on a Binder thread) —
the index is a single store value, and two writers each applying their change to the version they
happened to hold is how one silently erases the other. It reads **fresh inside the lock**, never the
index the caller is showing, and answers with a typed `Reason` rather than an exception because the
two callers say failures differently (a marshalable message the host compares verbatim vs. a
sentence in a dialog). `TagIndex` is immutable and shared by both sides of the seam, so the host's
W4 search merge and the extension's edits can never disagree about what a tag is.

Locks the phase set: the store key layout is **one key, `index`**, holding the whole `TagCodec` blob
(never a key per tag — one write, no fan-out without a transaction around it); **unreadable is not
empty** and nothing may be written over it (`IndexUnreadable` / `INDEX_UNREADABLE`, distinct from an
absent value, which is a first run); `snapshot` answers over ashmem with the region parked per
Binder thread and closed in `onTransact`'s `finally`, after the reply is marshalled; the screen's
three gestures are **tap the target row = detach · tap a list row = toggle · long-press a list row =
delete everywhere** behind the blast-radius confirm; the pager is `INVISIBLE`, never `GONE`, and its
arrows never disable.

**Walk (by hand on the Nomad, 9/9).** Tags… row present on notebooks between Export… and Exclude
from backup · **absent on folders** · screen opens (title = the notebook's name, both empty states)
· typed `reading list` on the on-screen keyboard, live filter flipped the list to "Matching tags",
Enter added it (target section + All tags with ✓, field cleared, keyboard kept) · a **second
notebook** offered the same tag with ⊕ and attached on a tap · tapping the target row removed it
while the tag **stayed in All tags** (the lifecycle rule, seen live) · long-press → `Delete "reading
list"?` / "Remove it from 1 notebook? The tag itself is deleted too." — the count naming the *other*
notebook — and Delete cleared it everywhere · extension `pm disable-user` → the row is **GONE**, and
back after `pm enable` · `logcat -b crash` empty. Store file is SQLCipher ciphertext at
`Garden/…ext.tags.dev.db`. Timings: cold open 2 630 ms (the KDF, behind `OpeningOverlay`), warm 53 ms.

**Walk-agent false failure, ~15th firing:** the agent reported tests 4–7 BLOCKED on "the input field
does not accept text — PinyinIME binds and immediately finishes". Re-driven by hand: tapping the
field gives `mInputShown=true mShowExplicitlyRequested=true` with `mServedView=…app:id/input`, the
keyboard draws, and every key lands. It was tap aim, which the same agent had already admitted
elsewhere in its own report.

### W2 — Notebook entries ✅
`ic_tag` top-bar button (right cluster, next to Document, before Recents) → secondary toolbar
`[Tag notebook] [Tag page] [Manage]` (the selection-toolbar pattern; GONE without the
extension). Quick-add ×2 = tag screen in MODE_ADD, input focused (IME rules); Manage =
MODE_MANAGE with the page id/label arrays (display numbers resolved host-side at launch).
Walk: all three doors, page tags land on the right page, IME opens on quick-add.
*Opus: flows + toolbar wiring. Sonnet: layout/strings/icon. Haiku: walk.*
**Questions at phase start:** exact secondary-toolbar button labels/wording (a placement/wording
call the user traditionally makes on sight).

**Outcome (code + Nomad walk by hand, all green).** The notebook's three tag doors are live.
Host: `btnTags` in the top bar's right cluster (`ic_tag`, between Document and Recents, GONE
without the extension via a new `TagManagerEntry.refresh()`), `notebook/TagsPopup` hung under it,
`notebook/TagTargets` (pure), and `TagManagerEntry` grew an optional `button`. Extension:
`TagsActivity` gained MODE_MANAGE, `TagManage` (pure), `TagRowView.buildTarget`, and the layout's
new `targetSection` wrapper. **1575 JVM tests/variant** (+12). All ten modules build debug +
release; both release APKs sign.

**Two user calls at phase start:**
- **The three buttons are icon-only with long-press hints** — the house style every floating bar in
  the notebook already follows. `ic_notebook` (og's Tabler notebook) and a new `ic_page` (Tabler
  file — deliberately *not* `ic_file_text`, which is the Document button two taps away in the same
  bar) joined `ic_list` in `:sn-screen`.
- **MANAGE is an overview list you drill into**, not many targets edited on one screen: it opens on
  the notebook plus every page, each row over the tags it carries, and tapping a row makes it the
  target so the screen becomes *exactly* the W1 screen. The back arrow returns to the overview and
  only from there leaves. Adding and removing therefore have one implementation and one set of
  gestures whatever door was taken — editing many targets at once would have needed a second
  grammar for "which of these am I acting on".

**Two things the walk found, both fixed and re-proven:**
- **`showSoftInput` from `onResume` is dropped** — a resumed Activity does not yet have window
  focus, so MODE_ADD came up with the field served and caret-ready at `mInputShown=false`, which
  reads as a broken keyboard rather than one asked for too early. It is raised from
  `onWindowFocusChanged` now, behind a once-per-showing latch so returning from a dialog does not
  re-raise a keyboard the user just put away. (The arc-20 explicit-flag-0 rule is unchanged and
  still necessary.)
- **The overview must remember its page.** Drilling into Page 20 and coming back landed on 1/3.
  `overviewPage` is kept apart from the tag list's `page`, and the text watcher is **silent in the
  overview** — the state changes clear the field, and that clear was sending the target list back
  to its first page.

**Shape worth keeping:** the arc-8 `LassoPopup` and this one are the same bar, so the placement
call, the measure-before-place rule, the rects and the button recipe moved into
`notebook/AnchoredBar` and both are now thin. A second copy would have been the sibling-copy trap
in miniature. The tag bar's outside-tap dismissal deliberately does **not** write
`tapDismissedPopup`: that latch exists so a contact spent dismissing the clipboard popup is not
also spent pasting, and this bar has no second meaning.

Locks the phase set: two of the three doors are about the page on the paper, so the bar is gated on
`canvasShown` (a text document that has never shown its pages has no page to tag — absent, not
greyed); page **numbers** are the host's to resolve, at the tap, against the live page list, and a
displayed page that is not in the list falls back to the notebook's name rather than "Page 0"; the
MANAGE arrays stop at `TagShowing.MAX_PAGES` because the parcel refuses rather than allocates above
it; the overview is never empty (the notebook is always a row) so it has no empty state.

**Walk (by hand on the Nomad).** Button in the right cluster between Document and Recents · bar
opens under it with the three glyphs · **Tag notebook** → the notebook's screen, keyboard up ·
**Tag page** → title "Page 1", "Tags on this page", typed `wip` on the on-screen keys, Enter landed
it (target section + All tags ✓, field cleared, keyboard kept) · **Manage** → "Notebook and pages",
`Notebook / No tags`, `Page 1 / wip` · drill into Page 1 → "Tags on Page 1", attached Test, back
arrow → the overview now reading `Test, wip` · on the 20-page **Document** notebook the overview is
21 rows over 3 pages, numbered Page 1…Page 20 correctly, pager 1/3 → 3/3 · tagged Page 20 from the
drill-in and it landed on **Page 20** with the overview returning to 3/3 where it was left ·
outside tap takes the bar down · `pm disable-user` → the button is **GONE** (verified pixel-wise,
875 → 1178 → 875 dark px as the row re-flows and comes back) · `logcat -b crash` empty. Test data
left on the Nomad: tags `wip` (Page 1 of 20260827_200914) and `Blah` (Page 20 of "Document").

### W3 — Lasso → tag ✅
Selection toolbar gains **Tag** (visibility per the planner call above). Heading flow: silent
`assign` (call-shaped), toast with the canonical display text. Handwriting flow:
`RecognizerClient` over the selection bounds (en-US; `RECOGNIZER_NOT_READY` = the exact-message
"still downloading" dialog the heading flow already has) → tag screen MODE_ADD with `prefill` →
user corrects → lands on the current page. Non-destructive both ways.
Walk: heading→tag silent; ink→tag with correction; recognizer-absent gating.
*Opus. Haiku: walk (Supernote keyboard is tappable from screencap coords — the arc-20 note).*
**Questions at phase start:** confirm the mixed-selection rule reads right in practice.

**Outcome (code + user checklist, all items passed).** The lasso's Tag is live: `tagButton` in the
selection toolbar between Pad and Delete, new pure `notebook/TagSelection` (the flow table), the
silent door `TagManagerEntry.assign`, and the notebook's `tagSelection` / `tagFromHeading` /
`tagFromInk` / `openTagAdd`. **1590 JVM tests/variant** (+15). All ten modules build debug +
release. **The extension side needed no change at all** — W1 built `TagShowing.prefill` and the
`assign` call, and W2 built the keyboard-at-window-focus latch, so W3 is entirely host-side.

**Two calls at phase start, both the user's:**
- **A mixed selection is not offered the button.** The planner's sketch recognized whatever was in
  the selection; the user narrowed it to *exactly one heading (silent) or a selection with no
  content objects at all (recognize)*, and **no button** for anything in between. The reason is
  that a mixed selection has two answers — a heading already carries the words a tag would be made
  of, while the ink beside it carries different words, and re-recognizing the heading's own strokes
  can come back with something other than what is on the glass. A button that quietly picks one of
  those is worse than a button that is not there. The offered set is therefore exactly
  `SelectionMode.HEADING` and `SelectionMode.STROKES` — a lone link is content with a payload, not
  ink, so it is out too.
- **The recognizer is NOT gated on.** Tag stands or falls with the *tag* extension alone; a missing
  recognizer is explained by the same problem dialog the H button beside it already gives. H and Tag
  sit in the same bar and both go out through the recognizer, so one vanishing while the other
  stayed would read as a bug rather than as a rule — and it keeps a package query off every `show()`.

**Two implementer calls:**
- **A heading that is not a tag lands in the correction screen, not in a refusal.** A title over
  `MAX_TAG_CHARS` (or blank after the prefix is stripped) cannot be assigned, and `TagShowing`'s
  constructor *refuses* an over-cap prefill rather than truncating — so the silent flow falls
  through to MODE_ADD prefilled with as much as fits, and the act finishes in one more gesture
  instead of none. The cut backs off a character rather than splitting a surrogate pair (the
  `TextChunks` rule, one char wide).
- **`RecognizingOverlay` grew a message parameter** instead of a third overlay object. The silent
  assign's wait is the same shape and length as a heading convert's — the first tag operation of a
  host process pays SQLCipher's KDF (seconds on a Nomad) — but it is not recognizing anything, so
  the box says "Tagging…". Default argument, one new `@+id/message`, both KDocs say why.

**Shape worth keeping:** ink→tag takes `HeadingConvert` **whole** rather than growing a near-copy.
"Read this one writing area and give me back a single line" is the same question the heading convert
asks — same extension, same selection-bounds area (a page-sized area under one line collapses
recognition to fragments), same problem dialogs — and the only difference is what the caller does
with the answer. The name stays `HeadingConvert` because that is where the flow came from; nothing
in it knows a heading is what follows. Likewise `TagManagerEntry` took the silent door rather than
the Activity, because availability, the busy latch and the wording of a failure are the same
questions for a door with a screen and a door without one.

Locks the phase set: the lasso always tags **the page on the paper**, never the notebook (wizard),
and the page id is captured **at the tap** so a flip mid-recognition still lands the tag on the page
the ink was on; the flow is re-read from the live selection at the tap rather than trusted from the
bar that offered it (a selection can move, change kind or die between the bar going up and a button
landing); nothing is consumed — ink, heading and selection are all exactly as they were, and the
toast fires when the write lands, never at the tap.

**Walk.** The lasso cannot be driven by adb (pen input), so this phase's walk was the user's
checklist rather than an agent's: ink→tag with correction · heading→tag silent + toast, heading and
selection untouched · a mixed selection showing **no** tag icon · a lone link the same · the
over-long heading routing to the prefilled screen · and the extension `pm disable-user`'d → the
button GONE, back after `pm enable`. All passed. Host-side smoke by adb: notebook opens with the new
bar constructed (which is what proves `ic_tag` resolves — `button()` sets the drawable in `init`),
`logcat -b crash` empty.

### W4 — Search merge ✅
`LibrarySearch` fetches `snapshot()` at query time (call-shaped, pre-open rule; absent extension
= names-only, silently). Tag texts rank through `core/FuzzyRank` with the same total order;
`SearchAssembly` grows the third group: folders → notebooks (name/tag deduped, best rank) →
**page-hit cards** ("<notebook> · Page N", parent-folder second line, matched tag shown) that
open the notebook at that page. Alive-filtering against the index; the query **re-runs after any
action** (arc-20 rule) — tag edits included. Sort stays GONE; `BrowseMode.SEARCH` persistence
rules untouched.
Walk: tag-only match surfaces notebook; page hit opens at the page; names-only when disabled.
*Opus (touches arc-20 code — read `docs/library.md` § Search first; FuzzyRank itself must not
change ranking for names). Haiku: walk.*
**Questions at phase start:** page-hit card wording.

#### W4 phase-start decisions (user, 2026-09-01 — do not re-litigate)

**The structural surprise that opened them.** The plan's page-hit card assumed the host could name
the notebook a tagged page lives in. It cannot: W1 stored a page assignment as
`(tagId, TARGET_PAGE, pageUuid)` and **nothing anywhere records the owning notebook** — the global
index holds folders and notebooks only, pages exist solely inside each `.soil`. Scanning for the
owner is not available either: `KeyMaterial`'s raw-key cache is **per file** (the salt is per file),
so a first-ever open of a `.soil` costs a full KDF. The user's call reshaped the record instead.

| Decision | Answer |
|---|---|
| The assignment record | **Every assignment names a notebook**; a page tag *also* names a page. `notebookId` present alone = a notebook tag, `notebookId` + `pageId` = a page tag. The user's model, and it makes the page→notebook relationship storable instead of inferable. |
| Where the pair lives | **Two explicit fields in the contract**, not packed into the opaque target id: `TagIndex.Assignment` becomes `(tagId, notebookId, pageId?)` and `TagCodec` bumps its version. The extension now *knows* a page belongs to a notebook, which it did not. |
| The encoded `kind` field | **Dropped** — a present `pageId` is what makes an assignment a page tag, so a stored kind is a second copy of the answer that can disagree with the question (W1's own reason for dropping `identityKey`). `TagShowing.TARGET_NOTEBOOK` / `TARGET_PAGE` stay in the API surface: they read well at call sites and MODE_MANAGE is defined against them. |
| Id form | **Compact base64url** — a UUID's 128 bits as 22 chars, not 36. W1's precedent applied again: keep every cap the wizard set, shrink the record. Assignment = 53 bytes; `WORST_CASE_BYTES` 50 000 × 53 + 1 000 007 = **3 650 007** against 4 MiB (was 3 900 007), so all three caps (5 000 tags · 50 000 assignments · 64 chars) are **unchanged**. One pure transform in `:extension-api`, JVM-tested both directions. Plain UUIDs were offered and declined — they would have forced the assignment cap down to ~35 000. |
| Old blobs | Tolerant migrate, not a wipe: a v1 `A <tagId> <kind> <target>` record with `kind == TARGET_NOTEBOOK` becomes `(tagId, target, null)` — a notebook assignment already *was* its notebook id. `kind == TARGET_PAGE` records are **dropped**: they name a page with no recoverable owner. (On the Nomad that is W2's two test tags.) |
| Page-hit card wording | Name line **`<Notebook> · Page N`**, subtitle **`<folder> · <tag>`**. The name line is `singleLine` — the notebook name is ellipsized so `· Page N` always survives, rather than letting a long name eat the page number. |
| Page-hit card cover | **The notebook's own cover snapshot** — already in hand from the listing, no extra read, no `.soil` raster. A page hit and its notebook look alike above the fold; the two text lines tell them apart. |
| Notebook card subtitle | The matched tag joins the folder **only when the name did not match** — `folder · tag` answers "why is this here", and a name match already answers it. A notebook matching both keeps the plain folder line. |
| Page numbers | Resolved by reading the owning notebook's `.soil` page list, on IO, **cached per process**, and only for notebooks that actually have page hits. Viable because raw keys persist in `DerivedKeyStore`, so a notebook that has been opened before reopens without a KDF — and a notebook with a tagged page has necessarily been opened. A notebook that will not open contributes **no page cards**; its notebook card is unaffected. The walk measures this. |
| Long-press on a page card | **Nothing** this arc. The ordinary action sheet acts on a notebook, and firing it from a page card would rename or delete something the card does not name. |

**Declined, and why it is in `BACKLOG.md`.** The user asked the right question — why is a table being
serialized at all? Because `IExtensionStore` (arc 11) is **key/value**: `get`/`put`/`delete`/`keys`
over byte arrays. The file underneath is already SQLite and host-owned; only the seam hides it. So
every extension serializes its structure into values — the scratch pad's pages are `pages` (one id
per line) plus `page/<id>` blobs, which is why a scratch page has a hard 4 MiB ceiling and a "page
full" dialog. Widening `IExtensionStore` to offer rows and columns was offered and **declined for
this arc**: W4 ships on the blob. The seam question and the pad's page ceiling go to `BACKLOG.md`
together — they have one cause.

**Outcome (code + Nomad walk by hand).** One query now answers over names **and** tags, and a
tagged page is its own card that opens the notebook at it. **1620 JVM tests/variant** (+30). All ten
modules build debug + release; both release APKs sign.

New: `CompactId` (`:extension-api`, pure) · `library/PageNumbers` (host) · `CardItem.Page` +
`LibraryGrid.pageCard`/`paintCover` · `SoilDao.livePageIds` · `SearchAssembly.Shelf`/`NotebookHit`/
`PageHit`. Reshaped: `TagIndex.Assignment` (tagId · notebookId · pageId?), `TagCodec` (`NSTAG2`,
compact ids, no kind field, v1 migration), `TagShowing` (the pair), `ITagManager.assign`,
`TagClient.assign`, `TagManagerEntry.assign`, and the five host call sites.

**What the arc did not expect.** W4's real work turned out to be upstream of search: a W1 page
assignment named a page and nothing else, so the library could not say which notebook a tagged page
was in — see the decision table above. Reshaping the record was the phase; the merge itself is small.

**Locks the phase set.** `API_VERSION` **5**, and it is the first bump that is **not** a compatible
tail — a W1-shaped tag extension against a W4 host would unmarshal a `TagShowing` wrongly. It fails
loudly (the constructor `require`s reject it, crossing as `IllegalArgumentException`), and the
declaration keeps it from being reached; only the tag service moves. `ExtensionContract.MAX_TARGET_ID_CHARS`
is **gone** — an id is a canonical UUID or it is not a target, checked by `CompactId.isId` at every
door, which is also what keeps a path character and a NUL out of one (the UUID alphabet has neither),
so W1's hand-rolled character checks were deleted as the weaker spelling of the same guarantee.
In memory an id is **always** a UUID; the compact form lives between `TagCodec`'s two functions and
nowhere else. Ranking and the blob decode both run **off Main** (`Dispatchers.Default`) — the decode
can be megabytes and the rank walks every assignment, and the caller is the listing coroutine.
A page card carries **no long-press**: the action sheet acts on a notebook, and firing it from a card
that names a page would act on something the card does not name.

**Two finds from the walk, both fixed and re-proven on the Nomad:**
- **Page cards got no cover.** `bindCurrentPage` fetched covers for `CardItem.Notebook` only, so
  every page card fell through to the paper placeholder. It asks for any non-folder card now, and
  `distinct()` keeps two hits from one notebook to a single fetch.
- **The search dialog's hint was a lie.** "Folder or notebook name" survived a change that made tags
  searchable. There are two hints now and the dialog picks by whether a tag manager is installed —
  the house rule about not claiming a control that cannot work, applied to a hint. `LibraryActivity`
  refreshes `TagManagerEntry` from `onResume` for it (it has no button, so nothing did).

**Walk (by hand on the Nomad).** The v1 → v2 migration ran live on the real store: all five existing
tags survived and the two W2 page assignments were dropped, exactly as the codec test asserts ·
tagged Page 1 of "Tags" with `packing` from the notebook's Tag-page door · searched `packing` from
the library and got **one page card**, `Tags · Page 1` over `Test · packing`, the notebook's own cover
above it · tapping it opened the notebook · `logcat -b crash` empty. Test data left on the Nomad:
tag `packing` on Page 1 of the "Tags" notebook (folder Test).

**Left for W6:** `docs/tags.md`, `docs/library.md` § Search, `docs/extensions.md` (the reshaped
contract + `API_VERSION` 5), both CLAUDE.mds. The `BACKLOG.md` entry for the declined store-seam
widening is already written.

### W5 — Backup of extension stores ✅
The arc-17 engine grows the store set: enumerate via a new `SoilFile.extensionStoreFiles(ctx)`
(the one path authority grows the one listing function; `isValidExtensionPackage` filters),
**copy every pass unconditionally** (small files — no stamp bookkeeping; `updatedAt` semantics
untouched), ciphertext stream + WAL-alongside, ordered **before the index** (index-last rule
unchanged). Backup-done dialog counts them.
*Opus (read `docs/backup.md` whole first). Haiku: walk (backup to SAF, verify `<pkg>.db` +
sidecars present, encrypted header).*
**Questions at phase start:** the restore path — arc 17 shipped backup + per-notebook Replace
import; confirm with the user what "restoring" a store means this arc (likely: document the
manual copy-back + BACKLOG a restore screen).

#### W5 phase-start decisions (user, 2026-09-01 — do not re-litigate)

| Decision | Answer |
|---|---|
| Restore | **Document + BACKLOG.** W5 ships backup only. `docs/backup.md` gains the manual copy-back (`<pkg>.db` plus its `-wal` **if the backup has one — both or neither**, `-shm` never, app closed, ciphertext keyed to the device that wrote it), and a whole-library restore screen goes to the monorepo `BACKLOG.md` as its own future arc. The same answer arc 17 gave for the library itself. |
| The done dialog | **Stores get their own sentence** — "N extension stores copied.", on its own line under the notebook counts. Notebook counts stay exactly as they are: folding stores into "N copied" would make a number the user can check against the library stop matching it. Nothing at all when there is no store (a line reading "0 extension stores copied" is a sentence about something the reader has never heard of). |

**Outcome (code + Nomad walk).** Every `Garden/<pkg>.db` is in the backup set. **1626 JVM
tests/variant** (+6). All ten modules build debug + release; both release APKs sign.

New: `SoilFile.extensionStoreFiles` + the pure `extensionStorePackage` (the one path authority grew
the one listing function) · `ExtensionStores.checkpointIfOpen` · `BackupEngine.copyStore` ·
`Result.storesCopied`/`storesFailed` · `BackupActivity.storesLine`. Reshaped: `copyIndex`'s body
became **`copyDatabase`**, taken by the index and every store.

**Locks the phase set.** A store takes the **index's** treatment, never a notebook's: `ExtensionStores`
caches every store it opens for the life of the process and closes none, so the notebook rule
(never copy under a live writer — skip and count it) would skip *every store worth copying*.
Snapshot-into-cache → probe → copy → WAL alongside is what makes the live copy safe, and that body
is now shared rather than a sibling copy. **Every pass, unconditionally, no stamps**: a store has no
`updatedAt` to compare against — its edits are an extension's, not the library's — and inventing a
clock for one would be a second answer that can disagree with the file. Ordered **after the
notebooks, before the index** (a store is content; the index is last because it is the manifest of
what the run already wrote). `succeeded` counts a store copy, so a run that copied only stores still
moves `lastRunAt`. A **zero-length** store is skipped (a create that never finished must not replace
a good destination copy with an empty one), and an **uninstalled** extension's store is still copied
— arc 11's rule that removing an extension's data is a deliberate act. A store this process has
**not** opened is deliberately not opened to checkpoint it: that costs a cold KDF to buy what the
WAL-alongside rule already buys.

**`Garden/` is enumerated here, and this is the one place it is.** The library's structure stays
index-only; a store has no index row to be listed from, because the host mints the file the first
time an extension is lent its store and that file is the only record it exists. The pure
`extensionStorePackage(fileName)` is the whole rule — only a store ends in `.db`, and its stem must
still pass `isValidExtensionPackage`, which names out notebooks, an import in flight and every
sidecar.

**Walk (by hand on the Nomad, both sidecar branches proven live).** Four stores on the device
(`ext.document.dev`, `ext.scratchpad.dev`, `ext.tags.dev`, and `probe.test` — the arc-11 debug
probe's leftover, correctly treated as a store). First run: "9 copied, 33 skipped. / 4 extension
stores copied." and all four `.db`s landed at the destination byte-for-byte with their **non-empty
`-wal`s alongside** and **no `-shm`** · every copied header is ciphertext (no `SQLite format 3`) ·
then the scratch pad was opened and closed, which left its WAL absorbed and the store **open in the
host**, so the second run checkpointed it and **verifiably deleted the now-stale destination
`-wal`** while the other three kept theirs · second run "0 copied, 42 skipped. / 4 extension stores
copied." — unconditional, exactly as specified · `logcat -b crash` empty.

**Status:** ✅ Complete.

### W6 — Review, docs, freeze ✅
`/code-review` on the arc range (**run + fixed by Opus this arc — Fable unavailable**; level
asked at phase start), fix/accept per user call. Docs: **`docs/tags.md`** NEW (the feature),
`docs/extensions.md` (sixth point + module table to TEN + `API_VERSION` 4 + boundary-audit
rows: TagShowing, the snapshot/assign calls, the store-index layout, what each side may know),
`docs/library.md` (search merge + long-press row), `docs/notebook.md` (toolbar + lasso entries),
`docs/backup.md` (store set), both CLAUDE.mds, root CLAUDE.md arc record, `BACKLOG.md`
(assignment pruning; restore screen if W5 lands there), memory. NUL byte-scan every changed
file. Full gates: JVM both variants, all TEN modules debug+release, release signs, final Nomad
walk, user checklist.
*Opus review + fixes; Sonnet docs; Haiku final walk.*
**Questions at phase start:** review level; version stamp (stay `0.1.0-ratta`?).

#### W6 phase-start decisions (user, 2026-09-01)

Review level **high** (every arc's answer). Version stays **`0.1.0-ratta`** (every arc's answer).
Findings: **fix all six** — the arc-19 / M11 precedent.

**Outcome (review + fixes + docs).** `/code-review high 645f144..HEAD` returned **six findings — two
medium, four low — and all six were verified against the source before triage** (none refuted). All
six fixed. **1623 JVM tests/variant** (1626 − 4 dead-code tests + 1 new sanitizer test). All ten
modules build debug + release; both release APKs sign.

The two mediums were both **a screen or a sentence telling the user something untrue**:

- **`TagsActivity` explained a revoked bind with a dialog that never drew.** `Dialogs.problem` has no
  dismiss callback, so `problem(…)` followed by `finish()` on the next line tears the window down
  before the dialog is on screen — the flash-and-vanish that the comment directly above it says the
  branch exists to prevent. It takes `failAndClose` now, which is the same file's own correct
  spelling (`Dialogs.confirm(…) { finish() }` — leave on the *dismiss*, never beside it).
- **`assign` had a smaller budget than `snapshot` while doing strictly more work.** `snapshot` was
  given 5 s "because the index can be megabytes"; `assign` decodes that same index, edits it,
  re-encodes it and writes up to `WORST_CASE_BYTES` back through the large-value path into
  SQLCipher — on 2 s. On a large index the lasso's silent heading→tag times out, and because **a
  Binder call cannot be cancelled** the orphaned call finishes anyway: the tag lands *after* the host
  has already said "Nothing has been changed." New `ASSIGN_TIMEOUT_MS` = 8 s, with the reasoning in
  its KDoc — the honest budget is the cheap way to keep that sentence true.

The four lows, and what each was really about:

- **`TagIndex.of` spent an id on a tag it then dropped.** `ids.add(t.id)` ran *before* the identity
  de-dup, so a tag folded away as a duplicate identity kept its id reserved — and every assignment
  naming it passed the `tagId !in ids` gate and landed pointing at a tag not in `keptTags`. Such an
  orphan is invisible to every reader, unreachable by `deleteTag`, and counts against
  `MAX_TAG_ASSIGNMENTS` for good. Reachable only from a foreign or corrupt blob, which is exactly
  what `of` exists to make trustworthy. Identity is checked first now and an id is spent only on a
  tag that is kept; the new test asserts every surviving assignment names a tag that is there.
- **`Tag.identityKey` was a `get()` that `sortedTags()` sorted by** — re-running `TagRules.display` +
  `lowercase` on every comparison, and `tagsOf` calls `sortedTags` once per MANAGE overview row, on
  Main, on every repaint. It is a `val` computed once now; a `Tag` is immutable, so there is nothing
  for it to fall behind.
- **Six dead members carrying doc comments that asserted roles they did not have** —
  `TagIndex.filterAlive` / `targetsOf` / `assignmentsIn`, `Assignment.targetKind` / `targetId`,
  `TagPaging.pageOf`, `PageNumbers.clear()`. All removed with their tests. **`filterAlive` is the one
  worth remembering**: its KDoc called it "the query-time filter", and there is no query-time filter
  — staleness is answered **structurally**, by `SearchAssembly.rank` reading tags *through* the
  index's own live notebook listing and `PageNumbers` answering a page against the notebook's live
  page rows. A comment in its place now says so, so the next reader does not re-add it.
  (`TagShowing`'s own `targetKind`/`targetId` are live and stay.)
- **The `:ext-tags` manifest comment said "API version 4" two lines above `android:value="5"`.** The
  declared number is the host-compat gate, so a stale comment beside it is the one place a future
  edit is most likely to "fix" the wrong line. It now records both numbers and why they differ.

**Docs.** `docs/tags.md` **NEW** (489 lines — the feature bible: identity and lifecycle, the record
where every assignment names its notebook, the codec budget, the seam summary, the screen's three
modes, all four doors, the failure table, the traps). `docs/extensions.md` 1141 → 1404 (six points
throughout, module table to TEN, `API_VERSION` 5 with the tail-vs-break ledger, the tag-manager point
section, boundary-audit **rows 19–23**, the store's backup subsection). `docs/library.md` 777 → 846
(the tag half of search, the page-hit cards, the Tags… sheet row). `docs/notebook.md` 1303 → 1430
(`btnTags`/`TagsPopup`, the `AnchoredBar` extraction, the lasso's Tag, the `RecognizingOverlay`
message). `docs/backup.md` was already done in W5. Both CLAUDE.mds, root `CLAUDE.md`'s arc record
(arcs 1–21 frozen), and `BACKLOG.md` (the assignment-pruning entry — W4's store-seam and W5's restore
entries were already written).

**Two things the doc pass found that the review did not, and both were real:** `filterAlive`'s
dead-ness (found independently by two agents before the review's own row), and the stale manifest
comment. A doc pass that has to state what the code does is a second reader of it.

NUL byte-scan: every changed file clean (the trap's ninth would-be firing — none fired).

**Walk (by hand on the Nomad, after the agent's ~16th false failure).** The Haiku walk reported
steps 4 and 10 as UNVERIFIED — "the Tags… row does not navigate", "the tag button does not respond"
— with steps 5–9 and 11 abandoned on top of them. **Both were tap aim, again.** The tag button sits
at x≈1108 and the agent tapped x=755; the Tags… row sits at y≈1084 and the agent tapped y=734, which
is the Unpin/Rename band. Re-driven by hand, every abandoned step passes:
notebook top bar → the bar opens under the button with its three glyphs (notebook · page · list) ·
**Manage** → `TagsActivity` resumed, "Notebook and pages", `Notebook / Blah, Test` over
`Page 1 / No tags` · drill into Page 1 → exactly the W1 screen ("Tags on Page 1", both empty states,
the add input, All tags with eight rows and ⊕) · tapping `packing` flipped it to ✓ and put it in the
target section (**the `assign` write landing under the new 8 s budget**) · long-press → `Delete
"Blah"?` / "Remove it from 1 notebook? The tag itself is deleted too." · Cancel, then the target
row's ✕ detached — and the screen came back **byte-identical** to the pre-attach capture, with
`packing` still in All tags (the lifecycle rule seen live, and a clean reversal of the test edit) ·
the library sheet's **Tags…** row opens the screen · `pm disable-user` → the row is **GONE** and the
sheet re-flows; `pm enable` → the sheet is **byte-identical** to before the disable ·
`logcat -b crash` and the main buffer both empty of anything naming the app.

**The walk-agent trap has now fired ~16 times and its shape has never changed:** a control the agent
can see in its own screenshot, coordinates it never measured, and a confident story about the app
being broken laid over the miss. Re-drive every FAIL and every UNVERIFIED by hand.

**User checklist: PASSED** ("The checklist passes", 2026-09-01) — the four pen-only items adb cannot
drive: heading→tag silent + toast with heading and selection untouched · ink→tag recognized into the
prefilled screen and landing on the page · **no Tag button** for a mixed selection or a lone link ·
pen feel unchanged.

**Status:** ✅ Complete. **Arc 21 "Tags" frozen.**

---

## Phases — Arc 20 "Search" (planned 2026-08-31, wizard complete)

Find a folder or a notebook by **name**, from anywhere in the library, with a **fuzzy** match —
the library's first search. One phase, **Q1**. Content (ink, recognized text, documents) is
explicitly **not** searched: names only, this arc.

### Locked decisions (arc-20 wizard 2026-08-31 — do not re-ask)

| Decision | Answer |
|---|---|
| Arc shape | **One phase, Q1** — the standing gates (JVM tests, Nomad walk, `/code-review` **high**, docs, freeze). Version stays **`0.1.0-ratta`**. |
| The button | `ic_search` (already in `:sn-screen`), top bar, **between `+Folder` and `Recents`** — the user's placement call. |
| What "fuzzy" means | **Subsequence + ranking**, not typo tolerance (the user's explicit call — `mtg` finds "Meeting Notes"; `meting` does **not**). Pure `core/FuzzyRank`: tiers EXACT > PREFIX > WORD-START substring > substring > subsequence, tie-broken by word-start hits, then span, then name length, then name (case-insensitive). Edit distance was offered and declined — **do not add it without a fresh decision**. |
| Scope | **The whole library, always** — every alive folder and notebook anywhere in the tree, whatever folder you are standing in (arc 13's template-search call, made again). |
| Order | **Folders first, then relevance** inside each group; the library's folders-before-notebooks rule outranks the score. **Sort is GONE in search mode** — relevance *is* the order, and a live Sort control would fight it. |
| Trigger | **A dialog asks for the query** (`NameDialog`, the template browser's shape — the user's 2026-08-31 revision of this row; an inline top-bar field was built first, walked, reviewed, and replaced). The Search button always opens it, and opens it again with the last query in it while the shelf is up — it **never toggles the mode off**. **Not** live-as-you-type: every debounce is a whole-page EPD repaint with cover reads. |
| Folder tap | **Go there, search closes** — the shelf's job was to find the place. Back then peels normally. |
| Long-press | **The same action sheet as anywhere** (Pinned/Recents parity); the query re-runs after any action that changes a name or removes a row. |
| Chrome in search | `[←] "query" [Search•] [Recents] [Pinned] [Scratch pad]` — `+Notebook`, `+Folder` and `Sort` hide; the Scratch pad button **stays** (the user's explicit amendment). **The shelf's title is the query, quoted** (`mode_title_search`), in `modeTitle` where Pinned and Recents put their names. |
| The Search button does not toggle | Unlike Pinned/Recents, tapping Search **re-opens the dialog with the last query in it** rather than leaving; `←` and Back are the way out. A deliberate divergence — written down in `docs/library.md`. |
| Persistence | `BrowseMode` gains **`SEARCH`**, and it is the one mode that is **never persisted**: a query is a moment, not a view. A relaunch lands in NORMAL at the remembered folder; the reader maps a stored `SEARCH` back to NORMAL. The query itself never touches prefs (**a name never reaches device-local plaintext** — the standing prefs rule). |
| Cards | The Pinned/Recents card shape. A notebook's second line is its **parent folder name** (the Recents call: on a flat shelf "where is it" beats "when"), memoised per refresh; folder cards keep icon + name. |
| Empty states | **One**: a run that matched nothing → "No folders or notebooks match that". The shelf is only ever entered by an accepted query, so a query-less search shelf does not exist; a blank query is refused **by the dialog**, in its own words ("Nothing to search for"), never the naming dialog's. |
| Templates | **The template Search shelf adopts the same fuzzy rule** (the user's call): `TemplateSearch` is reimplemented on `FuzzyRank`, `IndexRepository.searchTemplates` ranks in Kotlin instead of SQL `LIKE` (the `LIKE` DAO query goes), sentinels and rows rank **together** in one list, and **`btnSort` is GONE on that shelf only**. Its dialog-vs-inline shape stays as arc 13 built it. |
| IME | **The dialog owns it** — nothing in the top bar ever holds focus, so no caret blinks on the panel between searches and the never-hide-the-IME rule is never in play. `LibraryActivity` keeps `adjustNothing` (the grid measures itself once against a real band — G3's reason; the dialog's own window pans regardless) and `keyboard\|keyboardHidden` configChanges (the M4 lesson: a BT-keyboard attach must not recreate it). |

### Q1 — Search
**Status:** ✅ Complete (eae3673 · 5b61116 · dcfc108) — **arc 20 frozen 2026-08-31**, user checklist
passed ("It all looks and works good"), with the two amendments recorded below.

**Outcome (code + Nomad walk).** Search is live: `core/FuzzyRank` (the matcher + the total order),
`library/SearchAssembly` (folders-first, then relevance) and `library/LibrarySearch` (the field, the
query, the cards) — `LibraryActivity` grew a mode branch, the chrome and `enterFolder`, nothing more.
`BrowseMode.SEARCH` is refused by `BrowseState` **in both directions**. The index stopped filtering
names: `ObjectDao.searchOfType`'s `LIKE` is gone, replaced by `allAliveOfType` (which also absorbed
`allAliveNotebooks`), and `IndexRepository` grew `allFolders` / `allTemplates`. Templates followed
the user's call — `TemplateSearch` is now a door onto `FuzzyRank`, `TemplateShelves.searchCards`
ranks sentinels and rows **in one list**, and `btnSort` is GONE on that shelf. 1511 JVM
tests/variant (+25 new, −9 with the deleted `TemplateSearchTest`; +8 net after the review). Manifest: `LibraryActivity` gained `adjustNothing` + `keyboard|keyboardHidden`.

**Two findings worth keeping:**
- **A `GONE` field cannot take focus.** `search.open()` ran before `refresh`'s `renderChrome`, so the
  keyboard never opened (`mInputShown=false`, proven on the Nomad). Fixed by rendering the chrome
  synchronously on the way *into* the mode — the other modes keep their single render.
- **A left-to-right greedy subsequence is not a ranking.** It scored "Meeting Team Group" no better
  than "Amount Given" for `mtg`. The matcher walks **backwards for each character's latest feasible
  position, then forwards preferring word starts** under that ceiling.

Also written down: fuzzy-by-subsequence forgives a **dropped** letter (`meting` still finds
"Meeting Notes") but not a swapped or wrong one — the honest edge of the no-edit-distance decision.

**Review (level high, `/code-review` over the working tree): 5 findings, 5 fixed.** Two mediums were
both real and both on the seam between the field and the EPD panel: (1) **`clearFocus()` does not
drop focus** when the field is the only touch-mode-focusable view in the bar — focus bounced
straight back and `TextView`'s caret `Blink` invalidated every 500 ms, a permanent partial-refresh
loop on a screen whose rule is that nothing repaints unless something happened (`dropFocus` now
toggles `isFocusableInTouchMode` around the clear; proven on the Nomad — two captures 1.2 s apart
are pixel-identical, and tapping the field still brings focus and the keyboard back);
(2) **`SHOW_IMPLICIT` is skippable with a hard keyboard attached**, which on Ratta (keys delivered
only while the IME is shown) would have stranded the mode with no way to type into it — explicit
flag 0 now, `mShowExplicitlyRequested=true` verified. Three lows, all fixed: a blank submit with no
query is **inert** (Search collects the taps Pinned/Recents would have toggled with — it must not
answer them by taking an expensive keyboard away); **folder cards carry the parent line too**
(`card_folder.folderParent` — folder names are unique only per parent, so two "Notes" were one card
twice); and `data/template/TemplateSearch` — down to a single wrapper with no production caller and
a KDoc describing behaviour the rewrite removed — was **deleted**, `TemplateShelfView` calling
`FuzzyRank.isRunnable` directly. Final: **1511 JVM tests/variant**.

**Post-walk revision (user, 2026-08-31, after the first cut shipped as `eae3673`): the inline
top-bar field is gone — a dialog asks for the query and the shelf's title is the answer.** The
template browser's shape, so the app's two searches are one interaction. `searchField` was removed
from `activity_library.xml`, `LibrarySearch` lost the field, the editor action, the focus dance and
both IME calls (about 40 lines) and gained `openDialog()` + `title()`; `modeTitle` now carries the
quoted query; `setMode(SEARCH)` is fired from the dialog's accept callback, so the shelf is only
ever entered by a runnable query — which retires the "Type to search" empty state, the blank-submit
rule and both keyboard findings the review had raised against the field (they were fixed on the
field before it was replaced; the dialog makes them unreachable rather than fixed). Strings:
`mode_title_search` / `library_search_title` / `_hint` / `_confirm` / `_empty_title` / `_empty_body`
replace `library_search_prompt`. Re-walked on the Nomad: dialog opens with the last query, blank is
refused with "Nothing to search for" over a dialog that stays up, an accepted query titles the shelf
`"jour"`, Cancel leaves the standing shelf untouched, Back leaves to the tree. `logcat -b crash`
empty.

**Second user amendment (2026-08-31, from testing): the bottom bar's actions stand down in every
shelf.** Backup, Import and Templates act on the library — the folder tree you are standing in — and
Pinned / Recents / Search are not standing anywhere; they now hide with the create buttons.
`bottomLeft` is hidden as a **group** (ImportFlow owns `btnImport`'s own visibility and two owners of
one flag is a race), `btnTemplates` on its own so the debug ⋯ keeps its slot, and the **pager stays**
— a shelf paginates like any other listing. Verified on the Nomad in all three shelves and back.

**Written reason for the size rule** (`LibraryActivity` 823 → 886 lines, the `ExportActivity`
precedent): the whole search *feature* is already out of it — the matcher, the ordering rule and the
field/query/cards controller are three files of their own, and what stayed is a `when` branch, the
chrome and `enterFolder`. What is left in the Activity is one screen's wiring — chrome, modes,
listing, cards, sheets, delete, move, sort — and every candidate for extraction is a handful of
lines that would then need the Activity, the repo, the prefs and a refresh callback passed to it.
Splitting it would move lines, not responsibilities.

**Walk (by hand on the Nomad, 12/12).** Button placement · search chrome (+Notebook/+Folder/Sort
gone, Scratch pad kept, Search selected) · keyboard opens on entry · typed via the on-screen keys
(`input text` is swallowed, as ever — **but the Supernote keyboard IS visible to `screencap`**,
which is what made the walk drivable) · run on Enter and on the Search button · `jour` and the fuzzy
`jrn` both find the folder + its two notebooks with parent-folder subtitles · folder tap enters and
closes the search · no-match and type-to-search states · the last query returns select-all'd ·
long-press → Pin re-runs the query and the badge lands · Back leaves to the folder, keyboard down ·
the template shelf finds Grid from `grd` with Sort gone. `logcat -b crash` empty.

New pure code: `core/FuzzyRank` (the matcher + comparator) and `library/SearchAssembly`
(folders-first + relevance over `ObjectSummary`), both JVM-tested. New `library/LibrarySearch`
holds the field, the query and the card assembly, so `LibraryActivity` (already at 823 lines)
grows by a mode branch and chrome, not a feature. Index: one blob-free `allAliveOfType` listing
per run — the whole library's folder + notebook rows, ranked in Kotlin (fuzzy cannot be a `LIKE`).

**Gate:** `./gradlew test` both variants green; debug + release build, release signs; Nomad walk
(search from the library, a folder hit, a notebook hit, no-match, blank field, long-press
actions re-running the query, Back and `←` out, the template shelf still finding paper);
`/code-review high`; NUL-scan; docs (`docs/library.md`, `docs/templates.md`) + both CLAUDE.mds
+ this ledger; then the user checklist.

---

## Phases — Arc 19 "Document" (planned 2026-08-30, wizard complete)

**og's Documents feature, as an extension — the biggest arc yet.** The page is the draft; the
document is the result. A handwritten page's ink flows into a Markdown document once (seeded via
recognition), and from then on the document is the user's own writing. One document per page, one
more per notebook (the merged final draft), and **text documents** — notebooks whose primary
surface *is* the editor. Plus the export half: a Markdown/Text export provider, and PDF export of
the **rendered preview** through the existing `:ext-pdf`, untouched.

Reading references (read, don't copy): og `docs/documents.md` (the feature bible — four
invariants, autosave table, flip no-save zone, notebook document, text documents), og
`docs/proofread.md`, og `DocumentEditorActivity.kt` (1,850 lines) + `core/markdown/*` (~1,330
lines pure) + `core/proofread/*`. Paper never built documents — og is the only reference.

**This is the FIFTH capability point** — the user's explicit 2026-08-30 decision, satisfying the
no-new-point rule (amend both CLAUDE.mds at M3: **no SIXTH without another user decision**).

### Locked decisions (arc-19 wizard 2026-08-30 — do not re-ask)

| Decision | Answer |
|---|---|
| Fifth capability point | **`ACTION_DOCUMENT_EDITOR`** — screen-owning (tier-2, the scratch-pad recipe): the extension owns the full-screen Markdown editor Activity; **the host owns every `.soil` read and write** (og invariant 3, now enforced by a process boundary). The seam's new piece: a **host-callback binder** (`IDocumentHost`, passed at `begin`) so autosave pushes text to the host live — og's flush-before-seal invariant must hold across the boundary. |
| Module shape | **`:ext-document`** (EIGHTH module), label **`NSE · Document`** (user's singular call, `… Dev` in debug), package `….ext.document` (`.dev` debug), family puzzle icon, versionName lockstep. One APK, **three registrations**: the editor point + a document exporter on the existing `ACTION_NOTEBOOK_EXPORTER` + a text importer on the existing `ACTION_NOTEBOOK_IMPORTER` (the `:ext-soil` one-APK-many-services precedent). Depends on `:extension-api` + `:sn-screen` + `:markdown`. |
| Markdown engine | **`:markdown`** (NINTH module) — a shared library holding the whole pure engine (parser, renderer, formatter, reflow, list logic, search, draft/append, pagination). `:app` and `:ext-document` both depend on it: the host renders text-document covers and the PDF preview pages (the arc-18 rule — *the host renders whatever a notebook is*); the extension renders the editor's Preview. One engine, no drift. Never depends on `:app`, `:sn-screen`, or `:extension-api`. |
| Scope | **Full og parity including Proofread.** Page documents (seed-once, Bring in Replace/Append, staleness line), the full editor (Write/Preview, format bar + overflow, Ctrl shortcuts, list continuation + renumbering, Reflow, find & replace, word count, caret memory, text-size preference, in-editor page flips), the notebook document (scope toggle, auto-merge, notebook-wide staleness), text documents (create radio, straight-to-editor, text cover, rename from title, `.md`/`.txt` import). **Excluded:** open-with/share-to intents (arc-16 single-entry lock stands), images beyond og's source-level placeholder, og's digits-only list rule stands (lettered/roman REJECTED in og — do not re-raise). |
| Proofread | **In** (user's explicit call). SymSpellKt dependency **approved 2026-08-30, module-local to `:ext-document`** (the pdfbox precedent). Dictionary asset bundled in the extension APK (**never name an asset `.gz`** — AAPT gunzips and strips the extension, the og trap). **User dictionary lives in the extension store** — the extension writes nothing to disk, and the store is exactly this. |
| Recognition | **In-editor seed flows only** (seed-once on an undocumented page, Bring in, the notebook document's first-toggle auto-merge — cancellable, og behavior). Runs **host-side** via the existing `RecognizerClient`/ML Kit point — ink never crosses the document seam, only text does. `recognizePage` finally gets its consumer. **Export never recognizes**: a text export is documents only — notebook document if present, else per-page documents, undocumented pages skipped. |
| Export | Document exporter in `:ext-document` on a **new source kind `SOURCE_DOCUMENT`** (`API_VERSION` → **3**; `:ext-document` declares 3, everything else keeps its number — the D3 skew-guard recipe): the host assembles the document text and streams it over the read fd; the extension writes it — one format option **Markdown (.md) / Plain text (.txt)** (strip via the shared engine). Listed in the chooser **only when the notebook has a document**. **PDF-of-preview needs NO `:ext-pdf` change**: the host paginates + renders the markdown preview into the existing `SOURCE_PAGES` page bundle; the Export screen grows a **host-side Source row** (Notebook pages / Document) shown only when a document exists and a `SOURCE_PAGES` exporter is selected. |
| Text import | Text importer service in `:ext-document`: `ImporterInfo` grows a compatible **result-kind tail** (absent = soil notebook, `RESULT_TEXT_DOCUMENT` = text bytes — the sourceKind recipe mirrored). Extension streams bytes to the host cache exactly as today; the host forks **after** delivery: UTF-8 validation + **10 MB cap** → create a text document in the current folder (name deduped, encrypted like any create) → open into the editor. Entry stays the library's one Import button — arc-16's picker already unions MIME filters and matches by extension, so **no og-style import sheet is needed**. |
| Data model | **`TYPE_DOCUMENT = "document"`** — third additive row type on the family shape (the heading/link precedent: no version bump, no migration, Paper ignores the rows). `parentId` = page id (page document) or the notebook root row's id (notebook document — og's shape). `text` = the markdown. **The `srcUpdatedAt` watermark rides the free `flags` INTEGER** (SQLite INTEGER is 64-bit; og needed a new column only because its table had no spare 64-bit slot — ours does; NULL = authored by hand, never drafted). **Blank means absent** (no row ⇒ undrafted ⇒ seed offer works with no flag). Index: `NotebookFlags.TEXT_DOCUMENT = 4` (bit 2). `notebook_meta` json gains additive `textDocument` (codec defaults — absent = false). Document rows **excluded** from cover/content-staleness whitelists (a document is a product of the page, not content on it) but **included** in the notebook document's own staleness sweep (og's rule: page ink AND page-document edits both mean "pages have changed"). |
| Arc shape | **Eleven phases M1–M11** (letter M — D is taken). Each ends green (build + `./gradlew test` + Nomad walk) so the user can `/clear` between phases. Haiku automates everything adb can see; typing-heavy editor flows get a **debug-only automation hook** (M4 phase-start question) so walks aren't blocked by the Supernote IME trap; user checklists only for pen/EPD/SAF/keyboard-feel items. |
| Staffing | The standing recipe. **Fable the seams**: the AIDL + host-callback contract, the text-chunking rule, the result-kind/source-kind tails, the data-model contract, the pagination contract. Opus the editor + host flows; Sonnet scaffold/layouts/strings; Haiku walks; ≤ 5 background. |

### Arc-19 standing traps (assume they apply)

- **The ~1 MB Binder transaction budget vs. a 10 MB document.** Text crosses the seam **chunked**
  (the `InkChunks` recipe applied to text — one shared chunking rule, both sides, pinned by test).
  A whole-document cap (`MAX_DOCUMENT_CHARS`, sized at M3) guards both directions; the host-side
  cap runs before any bind, the extension re-checks running totals on receipt.
- **The extension store's 4 MiB value cap is NOT the document's home.** Autosave pushes text to
  the host via the callback binder; the store holds only small per-device state (caret LRU,
  text-size, proofread toggle, user dictionary). A draft never lives in the store.
- **Supernote swallows `adb shell input text` AND `input keyevent` letters** — editor walks
  cannot type. The debug-only automation hook (M4) exists precisely for this; anything it can't
  reach is a user checklist item.
- **The Ratta hardware-keyboard rule carries into the editor from day one** (og's 2026-08-11
  finding): hardware keys type **only while the IME is shown** — an attached keyboard is never a
  reason to hide the soft keyboard on Ratta; Supernote keeps the panel off-screen itself. Ctrl
  chords pass through and work. The og BOOX refuse-the-connection defense is irrelevant here.
- **The editor is SN's first non-paper full-screen Activity launched over a live
  `NotebookActivity`.** Whether the notebook must `releaseForHandoff()` around the editor (the
  scratch-pad ordering) or can simply stop behind it (og's shape — the editor draws no ink) is
  **answered on the Nomad at M3, not assumed**. If the EPD pipeline needs releasing, the
  scratch-pad ordering rule applies verbatim; a failure there is fixed in g-paper.
- **The host is stopped behind the editor** (og): no dialog is ever shown on the stopped host's
  window; the notebook catches up on page flips only when the editor closes
  (`navigateToPage(endedOn)` on the result). The flip gap is a **no-save zone** guarded on both
  sides (og's `flipInFlight` + `documentPageLoading` pair — now with a process boundary between
  them).
- **The autosave/teardown table is the feature's soul** (og's four-failure table): flush before
  seal, process death both sides, config-change recreation, editor recreation. Every path must be
  re-derived for the two-process shape and pinned. `NotebookActivity` already carries
  `keyboard|keyboardHidden` awareness lessons from D3 — the notebook must not be destroyed behind
  the editor by a BT-keyboard attach (og declares `keyboard` in configChanges for exactly this).
- **`SoilObjectEntity.flags` must hold epoch millis** — if the Room entity types it `Int?`, the
  watermark overflows; retype to `Long?` (column affinity unchanged — verify the Room identity
  hash does not move; family compatibility is the whole game). Checked first thing in M2.
- **Marshalable exceptions only, caller check inside the fd/bind try** (the E1 trap) — and the
  host-callback binder's stub methods gate on the extension's uid + signature the same way
  (`HostCallerCheck` runs on BOTH sides of this seam for the first time).
- **Recognized/document text is never logged on either side** — counts, lengths, durations only
  (the N-arc privacy rule, now covering the callback direction too).
- **View-VISIBLE-then-coroutine never draws first** (og overlay trap) — sequence any
  "Reading this page…" feedback via pre-draw + post before heavy work.
- **File tools can land a raw NUL byte** (fired 4×) — byte-scan changed files before calling any
  phase done.
- **GONE, never disabled; not-built controls do not exist** (J4): descriptors and buttons appear
  in the phase that builds their behavior, never before.

### M1 — `:markdown`, the shared engine
**Status:** ✅ Complete (2026-08-30)

**Outcome:** The NINTH module exists and is green standalone: `markdown/` (Android library,
namespace + package `…notesproutsn.markdown`, stdlib + junit only, **no `returnDefaultValues`**
on purpose — a test straying into android.text fails loudly). Parser / renderer /
`HeadingTypography` / `MarkdownDraw` + their six test files are the arc-3 `core/markdown`
files package-renamed (SN code — copyable); `:app`'s copies stay untouched this phase and die
when consumers repoint (migration rides the phase that first wires `:app` to `:markdown`).
`MarkdownFormatter` / `TextBuffer` (+`EditableBuffer`) / `MarkdownReflow` / `TextSearch` /
`DocumentDraft` are fresh og-semantics ports, reviewed line-against-og; og's test surface is
the floor, with idempotency/neutrality/caret-edge additions above it. `MarkdownPaginator` is
the new pure piece: caller hands measured `Line(top, bottom)` boxes, pages slice on line
boundaries, every page starts flush at its first line's top, an oversized line gets a page to
itself (clipped at draw — progress guaranteed), no lines ⇒ no pages. 160 new JVM tests
(1126/variant total), NUL-scan clean. **Grammar correction to this plan's own M1 sentence:**
og's parser has NO fenced-code or table blocks (closed subset; fences matter only to
reflow/renumber, `|` rows only to reflow) — og's semantics govern, the module keeps og's
grammar. One naming hazard written down in code: `MarkdownFormatter.Block` (enum) deliberately
shadows the file-level `Block` sealed class inside the object — from outside, always
`MarkdownFormatter.Block`.

The NINTH module: pure engine, no app changes, no new deps. `MarkdownParser` (block + inline,
og's grammar: headings, lists incl. tasks, blockquotes, fenced code, rules, tables, links, the
image placeholder rendered as italic alt text), `MarkdownRenderer` (spans from a handed paint —
sizes baked, the og re-render-on-size-change contract), `MarkdownFormatter` (format-bar ops +
`listEnter` + `renumberOrderedLists` — og's exact semantics: state-of-the-line not key timing,
runs per indent width, digits only), `MarkdownReflow` (og's conservative table: certain-wrap
joins only, fences untouched, hard breaks honoured, idempotent), `TextSearch` (case-insensitive,
non-overlapping, wrap navigation, replace-all caret math), `DocumentDraft` (isUndrafted /
isStale / append-under-`---`), and **`MarkdownPaginator`** — the arc's one og-less piece: split
rendered output into page-height slices on line boundaries for M9's PDF preview (design it now,
test it now, consume it at M9).
**Gate:** the big JVM suite (og's test surface as the floor: reflow idempotency, renumber
neutrality, listEnter shapes, search caret math, draft append, paginator line-boundary rule);
`:markdown` builds standalone; NUL-scan.
*Fable the module contract + paginator; Sonnet/Opus the engine port-by-inspiration; Haiku runs
the suites.*

**Questions to resolve at phase start:** none expected — og's semantics are the spec; anything
ambiguous in og's behavior gets asked as it surfaces.

### M2 — Host data layer
**Status:** ✅ Complete (2026-08-30)

**Outcome:** The document data layer is in, green (1161 JVM tests/variant, +35), and a pre-phase
file opens on the Nomad (the identity-hash proof — crash buffer clean). `TYPE_DOCUMENT` KDoc'd in
`SoilSchema` with the flags-as-watermark contract; `SoilObjectEntity.flags` **and `ClipRow.flags`**
retyped `Int?`→`Long?` (same INTEGER affinity — hash unmoved; ClipRow rides along so a page copy
carries the watermark verbatim). `DocumentDao` (documentFor / two staleness sweeps / the
setDocumentText·setDocumentDrafted pair — **exactly one of them moves `flags`**, structural) +
`DocumentRepository` (blank-means-absent enforced on read AND as soft-delete on write — stricter
than og's write-the-blank, deliberate; drop-unchanged-write; `save` cannot touch the watermark,
only `saveDrafted` can — og's single-method+caller-discipline made structural). **Staleness sweeps
count soft-deleted rows (og's rule — an erase IS a page change)**; the SN-only wrinkle is KDoc'd
on the query: the arc-17 close purge hard-deletes those rows, so erase-raised staleness lasts
until the next close, then the max honestly describes what remains — accepted, do not "fix".
`liveDescendantIds` gained `'document'` at the page level only (delete/undo/copy carry the page
document; a link never wraps one). `NotebookFlags.TEXT_DOCUMENT = 4`; all THREE meta-refresh
sites source `textDocument` from the index bit, never the previous meta (the wipe trap);
import reads the probed manifest's flag once and feeds both writes (a text document imported
from another device stays one). Purge parity pinned: a document dies only via cascade from its
purged page. Create paths untouched (the type radio is M8). `:app` still does not depend on
`:markdown`; the isStale comparison stays with the consumer phases.

`TYPE_DOCUMENT` in `SoilSchema` (KDoc'd like heading/link), the `flags`-as-watermark contract
written at the constant, `SoilObjectEntity.flags` retyped `Long?` if needed (identity-hash
verified against a pre-arc file), `DocumentDao`/`DocumentRepository` (get/save with
blank-means-absent + drop-unchanged-write, page + notebook-root parents, watermark stamp only at
seed/refresh), staleness queries (`maxContentUpdatedAt(pageId)` over strokes/headings/links +
link children; notebook-wide sweep including page-document rows, excluding the notebook document
itself), `NotebookFlags.TEXT_DOCUMENT`, `notebook_meta.textDocument` (additive codec field,
index-sourced on rebuild — the og meta-refresh-wipe trap), and the parity sweeps: page
copy/cut/paste carries the document (SN's page children include it — verify, pin), page delete +
undo carries it (soft-delete by parent), `SoilCompactor` purges it only via cascade from a
purged page, export/backup untouched (rows ride the file).
**Gate:** JVM tests (repository rules, staleness both scopes, clipboard/purge/undo parity —
FakeObjectDao pins); a Paper-created and pre-arc SN file still open (compat pin); builds; NUL.
*Fable the contract; Opus the queries + parity sweeps.*

**Questions to resolve at phase start:** none expected.

### M3 — The FIFTH point: AIDL, `:ext-document`, entry button
**Status:** ✅ Complete (2026-08-30)

**Outcome (code + walk, 2026-08-30):** The FIFTH point is live end to end on the Nomad. Seam
(`:extension-api`): `DocumentContract` (actions, `MAX_DOCUMENT_CHARS` 10 M, `TEXT_CHUNK_CHARS`
100 k, `TEXT_MAX_CHUNKS` **computed** 101, `MAX_PAGE_KEY_CHARS` 64, scope/source/direction/close
constants), `TextChunks` (greedy, **surrogate-pair backoff** — a chunk may run one char short,
which is the bound's `+ 1`; **empty text is ONE empty chunk**, so a cleared save and an absent
document ride the same shape), `DocumentPageState` (constructor-validated, compatible-tail wire
doc), `IDocumentEditor` (`begin(store, host)` / `end()` — the held-bind bracket, **zero Intent
extras**) and `IDocumentHost` — the first host-side stub on any SN seam, full M3–M8 surface
declared now (M6+ methods answer `UnsupportedOperationException`, which marshals — the J3
precedent; their semantics belong to their phases and may reshape before freeze). **The read
direction is a pull** (every state-answering call parks its text in the host's read window
atomically with the state; `readChunk` serves it), the write a push (`saveChunk(pageKey, index,
chunk, last, drafted)` — ordered from 0, cap re-checked on receipt, any refusal resets whole).
`pageKey` = the page row's id used as an **opaque stable token** (the extension's per-page store
keys need stability across showings; it opens nothing — no path, no key crosses).
Host: `DocumentHostSession` (pure, JVM-tested: window + accumulator + **parked-watermark** rule —
a drafted save consumes a watermark parked at serve-time, an ordinary edit can never invent one;
wrong-key save refused = og's mode-routing guard made structural), `DocumentHostBinder`
(uid-gated + revoked like the store binder; hooks run on Binder threads, `runBlocking` allowed
there; unexpected Throwables funnel to `IllegalStateException(className)` — never a message),
`DocumentEditorClient`/`DocumentEditorEntry` (the ScratchPad pair minus transfers),
`DocumentHostHooks` (staleness = M2's sweep vs. the row's watermark; `displayedPageId`, never
`currentIndex` — the torn-read rule), `NotebookSession.documents` + `writeDocument` (**enqueue
then `drain()`** — a fire-and-forget enqueue would let the editor's blocking save return before
the write landed; the drain is flush-before-seal across the process boundary). `:ext-document`
EIGHTH module (`NSE · Document`, API_VERSION 2, no Application class); stub screen =
`enforceActivity` first + a read-only proof of the whole seam (title + `n / m` + text pulled
through the callback binder). Notebook button before Recents (`ic_file_text`), GONE-not-disabled.
Both CLAUDE.mds amended: **FIVE points, no SIXTH without a user decision**; nine modules.
**+36 JVM tests → 1197/variant**; release builds green; NUL-scan clean (the trap fired a 5th
time — a NUL char literal landed as a raw NUL; byte-scan caught it).
**Walk (Nomad):** discovery/button ✓ · tap → extension-process screen with live title + `12 / 13`
via `current()`+`readChunk` (25 ms) ✓ · Done → `end`/unbind, services dump empty ✓ · shell
`am start` → `refused caller (none)` ✓ · `pm disable`/`enable` hides/restores the button ✓ ·
crash buffer clean ✓. The walk agent's one FAIL (button tap "did nothing") was the standing
tap-aim trap — refuted by hand at (1100, 65).
**The EPD question — ANSWERED (user pen check, 2026-08-30): stop-behind is enough.** The
notebook needs **no `releaseForHandoff()`** around a non-drawing child screen, cross-process
included: with the Document screen open, pen scribble drew nothing (no daemon ink, no trails),
and on return ink flowed normally with no ghosting and committed. The arc-13 template-picker
precedent extends across the process boundary; the editor keeps launching with no handoff, and
M4–M8 inherit this answer.

The seam (Fable): `IDocumentEditor` (held bind — the operation is the showing:
`begin(store, host)` / `end()`, session calls for text/state chunks) + **`IDocumentHost`** — the
callback binder the host passes at `begin`, SN's first host-side stub on an extension seam
(`saveDocument(pageKey, chunked text)`, `requestPage`, `requestSeed`/`requestMerge` answers,
rename, close-notebook) — every stub method gated by `HostCallerCheck` against the extension's
uid. Text chunking rule (`TextChunks`, both sides, pinned), `MAX_DOCUMENT_CHARS`,
`DocumentContract` caps/timeouts. `:ext-document` EIGHTH module (`:extension-api` + `:sn-screen`
+ `:markdown`), manifest + queries entry, `HostCallerCheck.enforceActivity` first thing in the
stub editor screen, `DocumentEditorClient` host-side (pre-open store on IO → mint store binder →
hold → begin → launch via `ActivityResultLauncher`; finish from result AND `onDestroy`).
Notebook top-bar **Document** button. Both CLAUDE.mds amended: FIVE points, no SIXTH without a
user decision. **The EPD question answered on-device**: does the notebook need
`releaseForHandoff()` around a non-drawing child screen? (Measure, don't assume; record the
answer here.)
**Gate:** JVM tests (chunking, caps, contract pins); walk — bind/begin/launch/refuse-shell
(`am start` = refused caller), binds = unbinds, disable/enable discovery, crash buffer; stub
screen opens and returns.
*Fable seams; Opus client + service skeleton; Sonnet module scaffold/manifest/icon/strings.*

**Questions to resolve at phase start:** ✅ answered 2026-08-30 — icon = **Tabler `file-text`**
(og's own `ic_file_text` vocabulary, redrawn into `:app`); position = **right cluster, before
Recents** (`… [space] Document · Recents · Scratch Pad` — page-bound, so leftmost of the
leave-this-page cluster); **`MAX_DOCUMENT_CHARS` = 10,000,000** (aligned with the M8 import
cap's 10 MB — UTF-8 chars ≤ bytes, so anything the importer accepts is guaranteed editable).

### M4 — The real editor: Write/Preview, format bar, autosave
**Status:** ✅ Complete (2026-08-30 — user checklist passed: typing feel, IME resize, hardware
keyboard + chords, Preview fidelity all confirmed on the Nomad)

**Outcome (code + walk, 2026-08-30):** The real editor is live on the Nomad; 1227 JVM
tests/variant (+30), debug+release green, NUL-clean. Wizard answers: **automation hook APPROVED**
(extension-side `src/debug/` receiver — `AutomationReceiver`, action `….ext.document.AUTOMATION`,
set/append/get text via `/data/local/tmp` files, get_state/set_caret/mode/save/done/close; absent
from release, `EditorAutomation.peer` never assigned there); **format bar = FULL og parity**
(13 tools, og's four groups + dividers, og hint strings with chords) plus og's chord-only four
(Ctrl+P mode toggle — the one chord live in Preview, Ctrl+0 paragraph, Ctrl+4–6). Editor:
header Close · centred title+`n / m` · Write/Preview/Done (armed mode = `isSelected` box),
`writingChrome` hidden as one piece in Preview, `FormatBarOverflow` (og semantics: moves REAL
views, in-flow bordered panel, outside tap closes but is NOT consumed, dots never auto-dismisses,
never leaves a divider last), watcher-based list continuation (`newlineAt` read-and-clear = the
re-entrancy guard) + back-to-front renumber with caret delta, Back = Close path (both leave paths
save — no cancel exists).
**The two-process autosave/teardown table (the phase's soul — Fable design, pinned):**
pure `AutosaveGovernor` (savedText/dirty/one-push-in-flight/newest-wins queue) + `ChunkPush`
(fun-interface sink) + `PendingPark` (failed saves parked keyed by target; **key mismatch =
deliberate drop** — page UUIDs globally unique, wrong-key write is corruption) + `DocumentSaver`
(snapshot on Main, push on IO under a Mutex, 2 s debounce + 2 s retry, scope never cancelled so
the onPause save lands). Four failures closed: (1) flush-before-seal = `end()` backstop
(service flushes live buffer via latch-hopped `flushHook`, then park; host's `end()` gets its own
**END_TIMEOUT_MS 15 s**); (2) host death behind the editor = host-driven **reconnect**
(`KEY_DOCUMENT_SHOWING` saved state → `DocumentEditorEntry.reconnect()` opens WITHOUT launching;
`onResult` **joins, never cancels** the in-flight reconnect; ext side: `begin` while held = "host
restarted" → live screen re-`current()`s + flushes on key match, no screen → daemon-thread pending
push, 10×500 ms behind `current()`; `DocumentHostHooks.openSession` now **waits bounded 8 s** for
the recreated host's async DB open — og's pendingDocumentFlush staging, two-process form, pure
`BoundedWait` in `core/`); (3) config change = `keyboard|keyboardHidden` on **both** NotebookActivity
and the editor (+ `stateUnchanged|adjustResize`, and NO IME-hide call anywhere — the Ratta rule);
(4) editor recreation = explicit buffer in saved state, **capped 256 k chars** (a Bundle is a
Binder transaction), restored buffer treated as unsaved.
**Walk (Nomad):** open/state/type/save/preview/persistence/shell-refusal/cleanup all ✓; the
**kill-host recovery ✓** (`am kill` under the live editor → DeadObjectException parked → Done →
"begin (host restarted)" → reconnect begin ok 118 ms → "teardown flush pushed 63 chars" → text in
the `.soil`). The walk's two FAILs both **refuted by hand** (the standing trap's ~11th firing):
list continuation works (walk's newline file was bad; one real find — the hook's append clobbered
a watcher-moved caret, fixed: append no longer calls setSelection); overflow works (agent tapped
the bar's empty right edge — the dots sit right after the last fitting tool). Blank-save cleanup
verified live (chars=0 → row soft-deleted). User checklist passed 2026-08-30.

The editor screen in `:ext-document`, og's chrome shape on SN's design system: header (title ·
`‹ n / m ›` · text-size · Write/Preview/Done as icons — og's P2P lesson pre-applied), source
strip (words), format bar + **overflow** (og reuses a toolbar overflow manager — SN builds its
own small one or reuses `:sn-screen`'s if fit), the text surface. `MarkdownFormatter` wired to
bar + Ctrl shortcuts; list continuation via the text watcher (og: buffer, not key events);
autosave (2 s idle debounce, mode switch, `onPause`, Done) **through the callback binder,
chunked**; og's four-failure teardown table re-derived for two processes and pinned (flush
before seal via `end()`-drain backstop; host process death → editor's buffer survives, flushed
on reconnect; editor recreation → explicit buffer save). IME: soft keyboard shrinks the layout;
the Ratta hardware-keyboard rule from day one. The debug automation hook (if approved) lands
here.
**Gate:** JVM tests (formatter wiring is M1-tested; here: autosave state machine as pure logic,
chunk reassembly); walk — open/type-via-hook/mode-switch/Done, autosave lands in the `.soil`
(adb pulls + opens the file via a debug probe), kill-host-behind-editor recovery, crash buffer;
**user checklist**: typing feel, soft-keyboard resize, hardware-keyboard typing + Ctrl chords,
Preview fidelity eye-check.
*Opus the screen; Fable the autosave/teardown review; Haiku walks.*

**Questions to resolve at phase start:** ✅ answered 2026-08-30 — the **debug-only automation
hook is APPROVED** (extension-side: a `src/debug/`-only receiver in `:ext-document` that
sets/reads the editor buffer + caret/mode; never compiled into release); format bar = **full og
parity** (all 13 formatting tools, og's four groups + Ctrl chords; Find/Word-count/Proofread
land in M5/M10 per the not-built-controls rule).

### M5 — Editor tools: Reflow, find & replace, word count, text size, caret memory
**Status:** ✅ Complete (2026-08-30)

**Outcome (code + walk, 2026-08-30):** All five tools live on the Nomad; 1241 JVM tests/variant
(+14), debug+release green, NUL-clean, walk 10/10 (find wrap-around, replace-all-then-ONE-undo,
reflow paragraph joins, size + caret both surviving a close/reopen, crash buffer empty, service
unbound after Done). All og semantics; what is SN-shaped:
- **The store key layout is a persistence format, pinned by test:** `size` (the sp float's
  toString, UTF-8) + `carets` (`CaretMemory`'s line blob `<key>\t<offset>\n`, oldest first,
  LRU 100 least-recently-written). Deliberately **no JSON** — the module carries no serialization
  dependency; the hand codec degrades every bad decode to an empty map (losing a caret costs a
  scroll, refusing to open costs the document).
- **`EditorPrefs` fetches `EditorSession.store` per call, never caches** (a host restart replaces
  the binder); every method is blocking-IO-only and treats every exception as "store unavailable";
  caret RMW is lock-serialized with a skip-unchanged write; `rememberCaretAsync` runs on its own
  non-lifecycle scope so the leave-path handover lands after `finish()` starts.
- **Caret handover on EVERY save trigger** (og's even-when-unchanged rule): `DocumentSaver` gained
  `caretSnapshot`+`caretSink`, fired at the top of `saveNow()` and in `flushAndThen` before its
  clean-buffer early return. Restore order: bundle (`NO_CARET = -1` distinguishes absence — 0 is a
  real caret) → store → top.
- **Reflow has NO visible control this phase** — its og home is M6's source strip; `Ctrl+Shift+F`
  + the debug hook are the entries until then (not-built-controls rule). `renumberLists` now
  returns Boolean (reflow's "nothing" check needs it).
- Format bar grew og's fifth group (Search · Word count) — SEARCH/WORD_COUNT are FormatTool
  entries routed past the formatter. Find bar = og's two rows in `writingChrome` (Preview hides it
  as one piece), selection-as-highlight, per-action recompute, replaces through the `Editable`
  (one Ctrl+Z), Enter-in-field navigation; buttons all focusable=false (the selection IS the
  match). Text size = header button LEFT of Write (live in Preview), `ActionSheetDialog`, og's
  ladder 14/16/18/21/25 default 16, preview +2, load applies with `persist=false` (no write-back
  of the just-read value). `keepCaretVisible` ported incl. the editor height-change hook. Activity
  stayed under the size rule by extracting `FindReplaceBar` (192) + `EditorTools` (157);
  automation hook grew 10 commands (find/replace/reflow/word_count/undo/size — `get_size` reports
  the sp preference, not the view's px).
- **Handoff notes for M6 (the flip phase):** `caretSink` fires with `saver.pageKey` — a flip must
  fire its save trigger BEFORE reassigning `pageKey` or the outgoing caret files under the
  incoming page; the load-time caret lookup runs only in `load()`, so the flip path needs its own
  `EditorPrefs.caret()` call; the find count goes stale across a buffer swap (re-count or close
  the bar on flip); the find query deliberately survives Preview and would survive a flip too.

Reflow (selection-grows-to-lines or whole document, `Ctrl+Shift+F`, "Nothing to reflow"), find &
replace (`Ctrl+F`, two-row bar, selection-as-highlight — e-ink honest, no spans), word count
(toast — the recorded toast-confirms shape), text-size preference (og's five steps +
`PREVIEW_BUMP`; stored in the extension store), caret memory (per-page LRU 100 in the extension
store, device-local by nature — og's deliberate not-in-the-soil call; top when unknown; handed
over on every save).
**Gate:** JVM tests (all pure logic already in M1 — here the store key layout + LRU eviction);
walk — via the hook: search navigation, replace-all one-undo, size persistence across reopen,
caret restore; **user checklist**: none expected.
*Opus; Haiku walks.*

**Questions to resolve at phase start:** none expected.

### M6 — Seeding: recognition in, Bring in, staleness, page flips
**Status:** ✅ Complete (commit 681d99e, 2026-08-30 — user checklist passed; three chrome calls
applied same-day, see the checklist-outcome bullet)

**Outcome (code + walk, 2026-08-30):** Seeding, the source strip, Bring in and in-editor page
flips are live on the Nomad; 1283 JVM tests/variant (+42), debug+release green, NUL-clean.
og semantics throughout; what is SN-shaped, and the locks:
- **Seam:** `DocumentPageState` grew the compatible-tail **`seeded`** flag ("the read window
  holds a fresh draft the host has NOT stored" — the editor treats it as unsaved and pushes
  `drafted = true` until one commits); two **typed refusal messages** on `DocumentContract`,
  matched `==` (the RecognizerClient recipe): `SEED_UNAVAILABLE` (Bring in with no recognizer
  ready) and `NO_DRAFT_PENDING` (a drafted commit whose parked watermark died — the editor
  **downgrades**: claim cleared, same words re-sent as an ordinary save; only provenance lost).
  `DocumentHostSession.setWindow` now clears the parked watermark on a **different-key** load
  and keeps it on a same-key one (M3's comment said this; the code didn't — fixed, pinned).
- **Host:** the hooks own the **target** (`targetPageId` — a flip moves it, nothing else does;
  saved state `KEY_DOCUMENT_TARGET`, restored before `reconnect()`; reset + notebook catch-up
  `refreshToPage(endedOn)` in the entry's new `onClosed`, which runs at the TOP of `onResult`,
  before the detached finish). Decision tables live in pure `DocumentTargetRules`
  (resolveTarget / source / flipIndex / openDecision / flipDecision — 18 tests); the open-time
  seed + the editor's silent recognitions live in `DocumentSeedFlow` (tap: drain → stored-doc
  check → `RecognizerReadiness` consent → "Reading this page…" → recognize → `stageSeed`,
  consume-once at `current()`; flip/Bring-in: READY-or-nothing, no dialog, never a download).
  Watermark read **before** recognition on every path. `requestPage` = null on ANY failure with
  target/window guaranteed untouched (mutations are the last statements); `requestSeed` refuses
  typed. A failed/blank seed never blocks: the flip lands empty, the page stays seedable.
- **Editor:** `SourceStrip` (og's line + words Reflow · Bring in, Reflow LEFT; sheet-first
  Replace/Append; applied through the `Editable` — one Ctrl+Z; `DocumentDraft.append` join),
  `PageFlipController` (the no-save zone: `prepareFlip` on Main fires the outgoing caret under
  the outgoing key, **abandons the governor queue** — a queued older snapshot relaunched by an
  in-flight push's completion would land stale text over the flip's own push — then blocking
  outgoing push under the shared lock (NonCancellable bookkeeping: a Done mid-flip must not
  skip `pending.clear`, or the teardown flush replays a stale park over what just landed),
  abort-on-failure = editor stays, `requestPage`, own caret lookup (`EditorPrefs.caret`, top
  for a seed), `setText` adopt — never on the undo stack), `ReadingPopup` (350 ms delay on
  flips, immediate on Bring in; `close()` from `onDestroy` — the coroutine hide rides a
  cancelled scope), pure `FlipRules` + `DraftAnchor` (arm/anchor/downgrade, 16 tests),
  `DocumentSaver.adoptWindow` (**an empty seed is not a draft** — arming it would stamp
  "drafted" over hand-typed words), `STATE_DRAFT_PENDING` in saved state. Arrows flank the
  page label (header restructured to weighted flanks — a screen-centred group could not hold
  them at 62 dp buttons); `Ctrl+PgUp/PgDn` live in Write AND Preview; edge taps toast
  "First page." / "Last page." (og wording). M5 spillover extractions: `FormatActions`,
  `TextSizeControl`, `EditorTools.continueListAt`, `FormatBarOverflow.dismissIfOutside`
  (Activity at 799 lines).
- **The walk's one real find (fixed + device-proven):** a Bring in whose recognition equals the
  buffer never pushed — `saveNow` drops unchanged text, so og's rule ("both choices re-anchor
  `srcUpdatedAt` even when the draft came out identical") never landed and the parked watermark
  sat unconsumed. Fix: `AutosaveGovernor.requestDraft` + `DocumentSaver.saveDraftNow` — a Bring
  in's save bypasses the unchanged-drop. Proven: re-anchor survives close/reopen.
- **Walk (Nomad, re-driven by hand after the agent's tap-aim FAIL — the trap's ~12th firing):**
  open-time seed with real ink (`seed: 5 strokes → 4 chars in 173 ms` → served seeded →
  `committed (drafted)` at the debounce) ✓ · seed-once (reopen instant, same text) ✓ · flips
  11↔12↔13 incl. flip-seeding ✓ · edges stay put (1/1 and 13/13) ✓ · Bring in
  Replace/Append + forced re-anchor across reopen ✓ · flip persistence (typed marker survives
  flip-away/back) ✓ · hand-typed doc honestly "Not drafted from this page" ✓ · close catch-up
  (notebook followed to 13/13 on Done, on-glass) ✓ · shell `am start` refused ✓ · binds = 0
  after close, crash buffer clean ✓. **Kill-host edge recorded:** host killed AND editor closed
  before it returned → the IndexGuard bounce (Bootstrap relaunch) eats the host's saved state,
  so no reconnect runs; the parked final save stays in the live extension process and is
  **dropped on key mismatch** at the next showing (`pending dropped — target changed` — the
  M4 corruption-safety rule doing its job). Exposure: keystrokes typed between host death and
  closing the editor; corruption-proof by design; accepted (og's single-process shape cannot
  express it; M4's "no screen alive" ladder is the standing answer).
- **Not walk-testable (adb cannot ink):** staleness flipping after NEW ink → user checklist.
- **User checklist outcome (2026-08-30): function passed ("works really well"); three chrome
  calls, all applied same-day:** (1) title DOCKED after the back arrow (the library's own
  `← Notebooks / Test` idiom — the floated-centre group read as misaligned); (2) the source
  strip's label + Reflow/Bring in are HAND-SIZED (`minHeight = @dimen/toolbar_button_size`,
  14 sp — they are tapped mid-writing, not find-bar-sized); (3) **NO ✓ Done in the editor
  header** (user call — every way out saves and nothing discards, so the back arrow is the ONE
  leave door; og keeps its ✓, SN diverges deliberately; the debug hook's `done` still leaves
  with RESULT_OK, which the host treats identically to Close). **M8 consequence:** its
  "✓ Done = show pages / Close = to library" split for text documents can no longer ride a
  button that exists — the control is an M8 phase-start decision.

**User checklist (M6):** 1. seed quality eye-check on a really handwritten page (open Document
on an inked, undocumented page — does the recognized draft read right?) · 2. flip-under-slow-seed
feel (flip to a heavily inked undocumented page — the 350 ms "Reading this page…" popup, no
dialog flash on drafted pages) · 3. write NEW ink on a drafted page, reopen the editor — the
strip must say "Page has changed since this draft" · 4. source strip + arrows legibility.

The host's `openDocumentEditor` seeds **before** launch (og's order): flush ink → document row

The host's `openDocumentEditor` seeds **before** launch (og's order): flush ink → document row
non-blank ⇒ hand over instantly → else `RecognizerClient.recognizePage` behind "Reading this
page…" (model-consent via the existing `RecognizerReadiness` flow; no recognizer ⇒ open empty,
page stays seedable). The seed becomes real only when the editor stores it. Source strip states
(Drafted from this page / Page has changed / Not drafted), `Bring in` → Replace/Append sheet
**before** recognition runs (no cancel-after-waiting), watermark re-anchored only at seed +
refresh. **In-editor page flips**: `‹ ›` + `Ctrl+PgUp/PgDn` via `IDocumentHost.requestPage` —
text stored first, host switches target + clears live state, the no-save zone guarded both
sides, `requestPage` always answers from a `finally` (null ⇒ revert), editor's own 350 ms-delayed
"Reading this page…" popup, arrows say First/Last page (never disabled). The notebook catches up
on close (`navigateToPage(endedOn)`).
**Gate:** JVM tests (seed decision table, flip state machine, staleness lines); walk — seed a
hand-inked page (user pre-inks; walk drives open/verify), flip through a notebook via the hook,
staleness flips after new ink; **user checklist**: seed quality eye-check on real handwriting,
flip-under-slow-seed feel.
*Opus features; Fable the flip/no-save-zone review; Haiku walks.*

**Questions to resolve at phase start:** none expected (consent flow reuses `RecognizerReadiness`
as-is).

### M7 — The notebook document
**Status:** ✅ Complete (commit 7ef2926, 2026-08-30 — user checklist passed: auto-merge over real handwriting,
cancel feel, ink-staleness and notebook-scope header all confirmed on the Nomad, "All good".
Checklist prep note: the "Document" test notebook was reset OFF-DEVICE — sqlcipher CLI +
the debug menu's recovery key: 16 page copies added → 20 pages / 5073 strokes, all document
rows deleted; the recipe (pull → edit → checkpoint TRUNCATE → push via /data/local/tmp + cp)
worked cleanly and is reusable for test-data prep.)

**Outcome (code + walk, 2026-08-30):** The notebook document is live on the Nomad; 1315 JVM
tests/variant (+32), debug+release green, NUL-clean (the zsh/BSD-grep `$'\x00'` scan false-flags
EVERY file — byte-scan with python). Wizard answers: **auto-merge + Merge only** (no Contents
selection path — revisit on demand); **merge-without-recognizer FIXED deliberately** (og quirk:
no READY recognizer voided the whole merge, documents included — SN merges page documents always,
recognition contributes per page only when READY, the M6 silent rule); **notebook-scope
never-merged strip line SILENT** (og code parity — page scope keeps its M6 label; og's docs
wording was a doc/code discrepancy, both og branches are silent in code).
og semantics throughout; what is SN-shaped, and the locks:
- **Seam:** `DocumentContract.MERGE_CANCELLED` typed message (== match). `requestScope` =
  requestPage's null-safety-net recipe (null covers failure, a cancelled auto-merge AND
  same-scope — nothing moved, editor stays silently); `requestMerge` copies `requestSeed`'s
  throw-not-null asymmetry DELIBERATELY (typed cancel; **never** SEED_UNAVAILABLE — recognition
  cannot block a merge; a merge with nothing to give answers an honest EMPTY un-seeded window,
  no park); `cancelRequest` = volatile flag, checked **between pages**, harmless no-op idle.
- **The notebook document's pageKey is `nb:<notebookId>`** (`DocumentTargetRules.notebookKey`) —
  og's mode-routing flag made structural: no page row can own the key, the accumulator's key
  guard refuses cross-scope saves by construction, and the editor's caret memory lands on og's
  own `nb:` key for free. `parentFor` resolves by EQUALITY against the one minted token — never
  a parse; the key stays opaque on the wire.
- **Host:** hooks grew `targetScope` (+`cancelled`); the page target is RETAINED through a
  notebook visit (toggle-back serves it — seeding it exactly like a flip, og; close catch-up
  still names it). `loadCurrent`'s notebook branch serves the STORED doc only — a reconnect
  never re-merges, never recognizes (same-key setWindow keeps a parked merge watermark, so a
  recreated editor's drafted save still anchors). Auto-merge belongs to `requestScope` alone;
  notebook watermark read BEFORE the loop; merge loop = pure `mergePagePart` (doc wins →
  recognition when READY → else dropped whole) + `mergeText` (`"\n\n"` join + whole-trim,
  og-verbatim: parts NOT trimmed; the `---` rule belongs to Append via `DocumentDraft.append`,
  never to the join). Saved state: `KEY_DOCUMENT_SCOPE` (og's STATE_DOCUMENT_NOTEBOOK).
- **Editor:** `ScopeToggle` (header, after the flip cluster — icon names where the tap GOES:
  `ic_notebook` on a page / `ic_file_text` on the notebook doc, og; always visible; arrows +
  `n / m` GONE in notebook scope; chords stay consumed — FlipRules' −1 BLOCKED already covered
  them), `PageFlipController.switchScope` (flip + switch share ONE private `move()` path —
  same prepareFlip → blocking push → request → adopt, same no-save zone), `SourceStrip` scope-
  aware (Bring in ↔ **Merge**, sheet title "Merge pages", same Replace/Append rows; notebook
  lines "Merged from this notebook's pages" / "Pages have changed since this merge" / silence;
  **a blank merge is a silent no-op** — `ScopeRules.mergeLands`, the Replace-over-blank-pages
  protection, og's null-draft), `ReadingPopup` grew message-per-show + a real Cancel button
  (only the two every-page walks carry it: entering notebook scope, Merge; `HostCancel` fires
  `cancelRequest` on IO, never Main), `RestoredState` = **the mode-routing guard's editor
  half** (bundle carries its pageKey; buffer+caret+draft-claim dropped TOGETHER on mismatch,
  before the caret lookup so the store's caret still applies; previewing survives), pure
  `ScopeRules` (17 tests) + `EditorShortcuts` extraction (chord table verbatim; Activity 776).
- **Walk (Nomad, driven by hand after the agent's false FAIL — the trap's ~13th firing: it
  claimed "empty library / NewNotebook broken"; the real cause was a wrong Bootstrap FQCN in
  the walk prompt (`bootstrap.BootstrapActivity`) and the library was full):** blank-notebook
  toggle round-trip (empty merge lands, strip silent, `1 / 1` ↔ `-`) ✓ · hand-typed nb doc
  stays silent + **blank Replace merge = silent no-op** (`merge came back empty — nothing
  applied`, buffer intact) ✓ · seed-once (stored doc back in 14 ms, no re-merge) ✓ ·
  kill-host in notebook scope = M6's recorded bounce edge, now with the M7 proof: **both guard
  halves fired live** (`pending dropped — target changed` + `restored buffer dropped — target
  changed`), page row stayed EMPTY, nb doc held exactly the pre-kill save — notebook text
  cannot land on a page row, demonstrated ✓ · auto-merge on a 4-page documented notebook
  (`merge: 4 pages (0 recognized) → 903 chars`, 66 ms, seeded, "Merged from this notebook's
  pages") ✓ · flips no-op in nb scope ✓ · Merge Append = 1813 chars = 903×2+7 (the
  `\n\n---\n\n` join, arithmetic proof — `am broadcast` replies are single-line) ✓ · staleness
  flips to "Pages have changed since this merge" after a page-document edit ✓ · binds 0 after
  Done, crash buffer clean ✓. **Cancel mid-merge NOT walkable** (documented pages merge in
  <100 ms — no popup window; needs a heavily-inked undocumented notebook) → user checklist.
- **Traps for M8:** the walk hook's `get_text` reply is single-line — multi-line buffers need
  length arithmetic, not grep. `am kill` does NOT recreate the host behind a live editor; the
  recreate happens at result delivery, and the IndexGuard bounce path is the one that runs —
  M6's accepted edge, unchanged.

**User checklist (M7):** 1. auto-merge over real handwriting (a notebook with inked,
undocumented pages — first toggle into the notebook document: does the merged draft read right,
pages joined by blank lines, in order?) · 2. cancel feel (toggle on a heavily inked notebook —
"Reading the pages…" with Cancel; tap it: editor stays on the page document, silently) ·
3. write NEW ink on any page of a merged notebook, reopen, toggle — the strip must say "Pages
have changed since this merge" · 4. header feel in notebook scope (arrows/count gone, toggle
icon legible at 62 dp).

An ordinary document row parented to the notebook root (M2 built the storage). Header scope
toggle (page ↔ notebook — a page flip in every way that matters: same guards, same no-save
zone), notebook-wide staleness ("Pages have changed since this merge"), **first toggle
auto-merges** (per page: document if non-blank, else recognize — og's loop; blank-line joins;
empty pages dropped; the ONE reading popup with Cancel — `IDocumentHost.cancelRequest`), Merge =
Replace/Append (sheet first, before recognition), `Ctrl+PgUp/PgDn` no-ops in notebook mode,
caret key `nb:<notebookId>`, process-death routing carries the mode flag (og's
`STATE_DOCUMENT_NOTEBOOK` shape) so notebook text can never land on a page row or vice versa.
**Gate:** JVM tests (merge assembly, staleness sweep, mode routing); walk — toggle/auto-merge
via the hook on a documented notebook, cancel mid-merge, mode survives process death
(`am kill`); **user checklist**: auto-merge over real handwriting.
*Opus; Fable the mode-routing review; Haiku walks.*

**Questions to resolve at phase start:** og also merges a page *selection* from its Page Index —
SN has no Page Index; should the Contents dialog grow a selection-merge path, or is
auto-merge + Merge enough for SN? (Recommend: enough — revisit on demand.)

### M8 — Text documents + `.md`/`.txt` import
**Status:** ✅ Complete (commits 70e0218 + 1051cba, 2026-08-31 — user checklist passed, "All
tests pass"; the one checklist find was the show-pages scope, fixed in 1051cba)

**Outcome (code + walk, 2026-08-31):** Text documents are live end to end on the Nomad; 1321 JVM
tests/variant (+6 net of the twin deletion below), all nine modules build debug + release,
whole-diff NUL-scan clean, walk 10/10. og semantics throughout; what is SN-shaped, and the locks:
- **Seam:** `ImporterInfo.resultKind` **compatible tail** (the sourceKind recipe verbatim:
  written unconditionally last, absent = `RESULT_NOTEBOOK`, unknown refused at unmarshal; holds
  only while the descriptor stays `describe()`'s trailing payload). **`API_VERSION` = 3 now**
  (M9's `SOURCE_DOCUMENT` shares it): the text importer's `<service>` declares 3 — a version-2
  host reading its tail as absent would run text bytes through the `.soil` probe — while every
  other service keeps its number; the D3 version-pin test moved 2→3 with the contract-event note.
  `IDocumentHost.renameNotebook`/`closeNotebook` are live: rename refuses with
  `IllegalArgumentException` whose MESSAGE is the user-readable reason (the library's own rename
  strings, verbatim); **closeNotebook is advisory state** recorded host-side, consumed ONCE via
  `takeCloseMode()` (taken BEFORE `resetTarget()`, which clears it), and **null advisory =
  TO-LIBRARY** — the fail-safe: a wrongly-sealed notebook reopens, a wrongly-loaded canvas cannot
  un-load. `DocumentPageState.textDocument` is real (the index bit via the hooks).
- **Routing** (pure `TextDocRouting`, 13 tests): `openDecision` →
  CANVAS / SEAL_AND_LEAVE / EDITOR_LAUNCH / EDITOR_RECONNECT; `closeDecision` →
  CATCH_UP / LOAD_CANVAS / SEAL_TO_LIBRARY; `parkClose(opened)` names the S2 rule
  (`ParkedClose` is a box, not a bare Int? — "no result yet" ≠ "result with no advisory";
  openSession re-decides a parked close after the open and it OUTRANKS the route). The flag is
  read once at `NotebookSession.open()` (`isTextDocument`, blob-free summary read; `refreshMeta`
  shares the hoisted bit-helper). `loadCanvas(pageId)` is the extracted open-second-half — the
  ONE place `canvasShown` is set (one-way latch, saved as `KEY_CANVAS_SHOWN`; once the pages have
  shown, the notebook is ordinary for the incarnation). `openIntoEditor`: lightweight setup only
  (no stroke deserialization), scope set to notebook **only on a fresh open** (a restored target
  or live reconnect is the editor's own memory), **no seed flow** (nb scope serves the STORED doc
  — the M7 lock; an empty text document opens instantly), overlay HANDED OVER to the entry's own
  box in one Main message (no stack, no gap), no-editor-installed fallback = `loadCanvas`, plus a
  10 s watchdog for an editor that never appears. `pushExclusions` blocks the whole surface while
  `!canvasShown` (opened-with-no-page must not take ink). Cover fork `captureCover`: a text
  document renders `TextCover` (DB-truthful text) at BOTH seal sites — `CoverSnapshot.capture`
  there would snapshot a surface that never loaded.
- **Editor:** `btnShowPages` far trailing end, visible in **either scope** of a text document
  (checklist fix 1051cba — the user's call, and true og parity: og's ✓ was show-pages in BOTH
  scopes, only og's extra Close was nb-mode-only, a slot SN's back arrow already fills; the
  page-scope header holds the extra 62 dp on the Nomad); icon `ic_pages` = og's Tabler
  "files". Tap = `closeNotebook(CLOSE_SHOW_PAGES)` on IO (failure Slog'd, leave proceeds) then
  `leave(RESULT_OK)`; **the back arrow calls nothing** — silence is the library door. Rename =
  tap the title (clickable only for a text document, bar-height target): the family NameDialog
  recipe, `MAX_TITLE_CHARS` filter, NO IME API call (the Ratta rule), refusal dialog shows the
  host's message verbatim and the rename dialog STAYS UP for correction. Pure
  `TextDocumentRules` (10 tests); `PreviewRender` extraction holds the Activity at exactly 800
  lines; hook +3 commands (`show_pages`, `rename`, `get_title`).
- **Import:** `TextImporterService` in `:ext-document` (third registration, `SoilImporterService`
  shape line for line, own `TextStreams`; streams VERBATIM — the bytes are as untrusted as a
  `.soil`'s and validation is the host's). `ImportFlow` forks right after `deliver()` on pure
  `ImportRouting`; the text branch: first-hand byte cap → `KeySession` → **`TextImport.decode`**
  (strict UTF-8 `CodingErrorAction.REPORT` — never the stdlib's lossy decode; NUL chars = binary
  wearing a text extension; leading-BOM strip; CRLF/CR→LF; byte cap 10 MB and
  `MAX_DOCUMENT_CHARS` both enforced, deliberately aligned) → silent name dedupe
  (`ImportNames.freeName`, one sibling read) → `TextDocumentCreate` (the 8-step create contract,
  blank-template case, document row `srcUpdatedAt` NULL = authored elsewhere; blank text writes
  NO row) → cover → `openImported` = straight into the editor. **No questions on the text path**
  (always creates new — og's rule). Empty-delivery check is kind-aware: an empty `.txt` is a
  legal import landing as a genuinely empty text document; a zero-byte `.soil` still refuses.
  New problems `NOT_TEXT` / `TEXT_TOO_LONG`.
- **Create + library:** "Handwritten / Text" radio (wizard wording; Export-screen radio idiom +
  a `Type` caption; second 1 dp rule below so the row reads as chrome), template browser stays
  live for both types, `createNotebook` writes meta `textDocument` + index bit
  (`IndexRepository.createNotebook` grew the param) + renders the empty cover at create. Card
  fallback: `ic_file_text` centered when a text document has no cover (flags already rode
  `ObjectSummary` — zero extra reads); ordinary cards byte-identical.
- **`:app` → `:markdown` repoint done** (the M1 lock's rider): 4 twins + 6 test twins deleted
  (byte-identical verified before deletion), 3 consumers repointed. **`HeadingPrefix.kt`
  deliberately stays host-side** — it is the TYPE_HEADING storage contract, not the engine, and
  `:ext-document` has no use for it (KDoc'd).
- **Walk (Nomad, agent, 10/10 first try):** create-radio → straight into the editor (verified by
  `mResumedActivity`), notebook scope + no arrows · set/save/get by length arithmetic ·
  close = library, card shows the text cover · reopen = stored doc, no re-merge · `show_pages` →
  canvas → Document button → editor → close = CANVAS (the latch) · rename lands + duplicate
  refused with title unchanged · ordinary "Document" notebook opens canvas-first (no hijack) ·
  `pm disable`/`enable` clean · binds 0, crash buffer empty.
- **Traps (new this phase):** a gradle gate piped through `| tail` reports the PIPE's exit code —
  a failing version-pin test rode a "green" gate for two runs; run gates unpiped and read
  `$?` (or check the XML), always. The NUL trap fired a **7th** time — a `'\u0000'` char literal
  and a `"\uFEFF"` string landed as RAW bytes; python-rewrite with escapes, then byte-scan.

**User checklist (M8):** 1. **SAF import of a real `.md`** (the picker is not adb-drivable):
library → Import → pick a Markdown file — it should land straight in the editor with the
content, no questions asked; back arrow → the card shows the file's opening lines ·
2. **cover eye-check** — a text document's card (opening lines legible? the empty-document card
with the centered file glyph read right?) · 3. **create-screen feel** — the Type row
(Handwritten default, Text) under the name bar · 4. **rename from the title** with the real
keyboard — typing feel, and a duplicate name's refusal wording · 5. **Show pages** — button
legibility at 62 dp in notebook scope; flip to a page document, then Show pages: the canvas
should land on the page the editor was on.

The create screen gains a type radio (Notebook / Text document); a flagged notebook
(`NotebookFlags.TEXT_DOCUMENT` + meta mirror) routes straight into the editor from every entry
point (lightweight page-id setup, canvas load deferred until ✓ Done asks for it — og's shape:
✓ Done = show pages, Close = seal to library without touching the canvas,
`pendingCloseAfterOpen` for the recreated-host race). **Text cover**: the host renders the
opening lines via `:markdown` onto the family cover canvas at seal-after-flush + at import;
card center glyph `ic_file_text`. **Rename from the title** (text documents only) via
`IDocumentHost.renameNotebook` (host validates against siblings, index + meta + title). **Text
import**: `ImporterInfo` result-kind tail (Fable), text importer service (stream copy — the
`:ext-soil` idiom), host fork after delivery (UTF-8 + 10 MB cap → create text document, name
deduped, `srcUpdatedAt` NULL — authored elsewhere → cover → open into the editor). The soil
importer's pipeline is untouched (tail absent = today's meaning, pinned on real wire like D1).
**Gate:** JVM tests (routing decision table, tail compat both directions, import fork, name
dedupe); walk — create/reopen/rename/import via picker where adb allows, `pm disable` hides the
text importer only; **user checklist**: SAF import of a real `.md` (picker not adb-drivable),
cover eye-check.
*Fable the tail; Opus routing + import fork + cover; Sonnet create-screen radio; Haiku walks.*

**Questions to resolve at phase start:** ✅ answered 2026-08-30 — radio wording =
**Handwritten / Text**; library badge = **none** beyond the text-preview cover + its
`ic_file_text` center glyph; show-pages exit = **a text-document-only header button**
("Show pages", its own icon — NOT `ic_notebook`, the scope toggle already owns it): the back
arrow stays the ONE leave door (→ library, M6's rule holds for text documents too), the
button opens the canvas.

### M9 — Export: `SOURCE_DOCUMENT` + PDF-of-preview
**Status:** ✅ Complete (commit 62964e6 — checklist passed 2026-08-31, "All tests pass")

**Outcome (code + walk):** All three shapes live and Nomad-proven end-to-end: a real `.md`
export, a real `.txt` export, and a real Document-source PDF (3 pages of the "Blog 20251008"
text document — white ground, Preview metrics, no line cut by a page edge; rendered and
eyeballed off-device). 1364 JVM tests/variant (+43), both builds compile, NUL-scan clean.
**One plan-sentence contradiction resolved at the seam:** the M9 blurb's "extension strips via
`:markdown`" could not coexist with the pinned "text stream is verbatim" verification — resolved
in the verification's favour: **the HOST assembles the FINAL bytes** (`.txt` strip runs
host-side through `MarkdownText`, og's `toPlainText` ported into `:markdown`), and
`DocumentExporterService` is a pure verbatim streamer (`TextStreams.streamCopy` reused), so
`ExportVerification` holds `SOURCE_DOCUMENT` to the soil equality. **Locks:**
`SOURCE_DOCUMENT = 2` (`ExporterInfo` accepts it; the service's manifest declares API version 3
per-service — a version-2 host fails the unmarshal, 3 moves the skip to discovery) · reserved
`OPTION_TEXT_FORMAT` ("textFormat", choices `md`/`txt`) is **host-executed twice over** —
assembly AND destination naming (extension + picker MIME follow the choice via
`ExportDocumentRules.fileExtension`/`mimeType`); `isRenderable` gates it to `SOURCE_DOCUMENT`
only, known choice ids only, and keying can never ride the document kind ·
`ExportText.markdownOf` is **the one read both document exports share** (notebook document
wins whole if non-blank, else page documents in page order, M7's merge join verbatim —
`ExportDocumentRules.assemble`; a text document's row is notebook-parented, covered free) ·
`hasDocument` = `SoilDatabase.readOnce` + `SoilDao.hasLiveDocument()` (blank-means-absent in
SQL) at the top of `loadCandidates()` — feeds both `listed()` (chooser gate) and
`sourceRowVisible()` (Source row = hasDocument AND SOURCE_PAGES); `documentSource` is forced
false whenever the row is not on screen · Document mode: template toggle row HIDDEN (GONE) and
the render is white-always (phase answer); `DocumentPdfRender` = Preview metrics
(`DocumentPdfMetrics` mirrors `EditorPrefs` — KDoc names `EditorPrefsLayoutTest` as the pinned
key-layout source), saved editor text size read from the editor extension's store **only if the
store file already exists** (an export never mints a store), page size = first live page row's
own, one `StaticLayout` sliced by `MarkdownPaginator`, RGB_565/WEBP one page at a time into a
standard `PageBundle` — `:ext-pdf` untouched and unaware · problem→string tables lifted to
`ExportMessages` (one table read four ways). **`ExportActivity` is 924 lines — written reason:**
the screen now hosts four prepare paths + the host-owned Source row, all needing its state
(`values`, `busy`, `stage`, the binding); the liftable halves already left (ExportMessages,
ExportDocumentRules, the two preparers); next candidate if it grows again = the ~50-line SAF
destination unit (arc-15-reviewed code, deliberately not churned). Walk extras: with ALL
exporters disabled the library sheet's Export… row itself is GONE (arc-15 gating, one level
above the no-exporter dialog); re-enable restores all three rows and the last-used default.
**Cosmetic flag for the user:** the chooser caption and the document exporter's option are both
labelled "Format" — two "Format" captions stack when the document exporter is selected.

The seam (Fable): `SOURCE_DOCUMENT` on `ExporterContract`, `API_VERSION` → **3**
(`:ext-document` declares 3; a pre-arc-19 host skips it — the D3 recipe, pinned), per-kind
verification grown (a text stream is verbatim: the soil equality applies; pinned).
`DocumentExporterService` in `:ext-document`: descriptor (format option Markdown/.md ·
Plain text/.txt, MIME `text/markdown`/`text/plain` — the host renames the extension per
choice), `export()` = read the host-assembled text fd, write verbatim or strip via `:markdown`,
fsync, honest count. Host: assembles the source (notebook document if non-blank, else per-page
documents joined by blank lines, undocumented pages skipped; honest EMPTY refusal when no
document exists — but the chooser already gates), lists the exporter only when a document
exists. **PDF-of-preview**: the host-side **Source row** (Notebook pages / Document — GONE
unless a document exists AND the selected exporter is `SOURCE_PAGES`), Document ⇒
`MarkdownPaginator` + `:markdown` render into the standard `PageBundle` at the notebook's page
size — `:ext-pdf` receives it none the wiser. Template toggle in Document mode = white ground
(paper under prose is a phase-start question).
**Gate:** JVM tests (assembly rules, strip, tail/skew pins, Source-row visibility table,
paginator-to-bundle); walk — chooser gating (documented vs. not), option swap, `pm disable`
each exporter, binds = unbinds; **user checklist**: SAF-saved `.md`/`.txt`/preview-PDF opened
on the Mac, pagination eye-check.
*Fable seams; Opus host assembly + Source row + exporter service; Haiku walks.*

**Questions to resolve at phase start:** does the Document PDF render on the page template or
plain white (og never built this — no precedent)? Page size/margins for the preview render;
`.txt` strip shape sanity (og's `toPlainText`).
**Phase-start answers (2026-08-31):** Document PDF ground = **plain white, always** — the
template toggle is hidden/inert in Document mode. Metrics = **editor Preview's** (its padding
and the user's saved text-size preference; the PDF matches what Preview shows), at the
notebook's own page size. `.txt` strip = **og's `MarkdownText.toPlainText` verbatim**, ported
into `:markdown` and pinned by og's tests (incl. the never-collapse-blocks repro rule).

### M10 — Proofread
**Status:** ✅ Complete (2026-08-31, commit 2fcc980 — checklist passed, all 4)

**Outcome (code + walk):** og's proofread, extension-local, all og semantics. Phase-start answers:
**dictionary asset reused verbatim** (sha256-identical copy of og's VarCon-patched
`proofread/en_82765.dict` + NOTICE.txt into `:ext-document` assets — data, not code); **toggle in
the editor** (format bar tail "Proofread" → sheet: Check document / User dictionary / Turn off —
GONE-not-greyed when off). Pure port `…ext.document.proofread/` (SpellEngine / ProofreadTokenizer
/ ProofreadCheck / GrammarRules): **normalized diff against og = zero lines in all four**; og's 55
test methods + 16 hole-fillers; the asset rides the test classpath via a `sourceSets` test-resource
mount of `src/main/assets`. Two og subtleties pinned: `misspelled`'s intersection means a
**zero-width region still judges the word it sits inside** (grammar early-returns on empty — og's
asymmetry, preserved); og's `articleAgreement` KDoc claimed a capitalized-after-capitalized guard
the code never had (KDoc corrected, code untouched). **Locks:** user dictionary lives in the
extension store as `UserWords`' line blob under `EditorPrefs.KEY_USER_WORDS` = `"dict"`
(normalized form is the storage form; hard drop on remove; insertion order = og's addedAt with the
clock removed); toggle under `KEY_PROOFREAD` = `"proofread"`, **absent = on**, read async in
`start()` (the store is Binder I/O — no sync constructor read; dictionary never loaded while off);
suggestion-index build on a **process-level scope** (no Application class in the module — a
lifecycle scope would restart the ~40 s build every open); og's words-before-engine publish order
kept across the store seam; og's three informational toasts → `Dialogs.problem` per the SN
toast-vs-dialog rule ("Removed “word”" stays a toast); `ProofreadPeer` = a SECOND automation
interface implemented by the controller (kept the activity at exactly 800 lines, with
`watchHeight`/`watchWidth` extracted to EditorTools/FormatBarOverflow to make room);
`proofread_fix` never blocks on the index — walks poll `proofread_status` for `suggestions=true`;
og's `suppressImeSession` deliberately NOT ported (Ratta IME rule). Controller 710 + Sheets 155 +
EditText subclass 141 + Spans 26; no word/text logged anywhere. **1482 JVM tests/variant** (+118);
debug + release build green; NUL-scans clean. Walk 10/10 on the Nomad via the hook (fix applies
spelling + grammar, ignore session-only, **add-to-dictionary survived `am force-stop`** — the
store-persistence proof; popup titles "suggestions are loading" honestly; crash buffer clean).
Walk-expectation correction worth keeping: "the cat sat. the dog barked." raises NO
sentence-capital flag — **"sat" is in ABBREVIATIONS (Saturday)**, precision over recall, og
behavior confirmed live; a walk that wants that rule to fire must not end the prior sentence in a
weekday/month word. User checklist passed 2026-08-31 (all 4: underline legibility/texture on
e-ink, popup + Ctrl+Z, no-flicker debounce, toggle round-trip).

og's subsystem, extension-local: SymSpellKt (module-local dep, approved) + the bundled gzipped
dictionary asset (og's VarCon-patched dictionary — both US/UK spellings; **asset reuse from og
is data, not code** — confirm at phase start), pure engine port (Markdown-aware tokenizer,
line-bounded incremental check, og's five conservative grammar rules — silence over noise),
dashed/dotted underlines in `onDraw` (spans are position-only markers), span diffing (an
unchanged screen never repaints — the e-ink rule), tap popup (suggestions / Fix / Ignore / Add
to dictionary), **user dictionary in the extension store**, global on/off (default on, dictionary
never loaded while off).
**Gate:** JVM tests (engine, tokenizer, rules, incremental bounds — og's test surface as the
floor); walk — via the hook: misspelling underlined, fix applies, ignore persists, add-to-dict
survives restart; **user checklist**: underline legibility on e-ink.
*Opus the port; Sonnet the asset pipeline; Haiku walks.*

**Questions to resolve at phase start:** reuse og's patched dictionary asset verbatim (data, not
code — recommend yes)? Toggle location (editor overflow row vs. library debug-adjacent
settings)?

### M11 — Review, boundary audit, docs, freeze
**Status:** ✅ Complete (commit 56f8975 — user checklist passed all 4, 2026-08-31). **Arc 19 is frozen.**

**Outcome (running record):** Phase-start answers: review level **high**, version stays
`0.1.0-ratta`. `/code-review high 17b0b9f..HEAD` → 15 CONFIRMED correctness + 6 cleanup (1
refuted: proofread double-sheet, blocked by the modal dialog); the user chose **fix everything**
— all 21 fixed, all pinned by test where a pure seam exists (1503 JVM tests/variant, +21).
The dominant family: save durability on the host-restart reconnect seam —
(1) hooks' `alive` gate made the M4 BoundedWait unreachable → new `documentWritesClosed` flag
(flipped only at the seal; NOT `closing`/`opened`) + separate `sessionOpen` lambda;
(2) `onHostBegan` single-shot probe → 10×500 ms ladder; (3) `flushBeforeRevoke` → `current()`
first, live buffer through the saver's push lock (`FlushHook.pushBlocking`), parks resolve by
key, failures re-park; (4) `writeDocument` swallowed write failures (SoilWriter drain-loop
catch) → per-job `CompletableDeferred`, `enqueue` reports acceptance; (5) governor's stale-queue
drop was dead code → while pushing, the newest snapshot ALWAYS queues (an undo back to saved
text supersedes); (6) `isShowing` latched before launch → latched AT the launch + the reconnect
route gets the watchdog (RESUMED-at-deadline = no editor on top); (7) onDestroy seal raced the
detached `end()` flush → every seal path joins the entry's `finish()` Job (LAZY-started at
onResult, `close()` returns its Job). Below-the-line six: watermark park consumed only after
the commit hook returns (`consumeParkedWatermark`, value-matched); `ParkedClose` carries
`endedOn` (the replay lands on the page the editor ended on); `TextSearch.replaceAll` caret
carries the running delta; `SoilDao.hasLiveDocument` TRIM set = ASCII whitespace (Kotlin
`isBlank` parity; residual Unicode mismatch KDoc'd + BACKLOG); caret writes serialized
(`limitedParallelism(1)`); `renumberLists` clamps a caret inside a rewritten marker. Engine:
reflow keeps a joined line's hard break + block toggles skip blank lines — **og carries both
bugs; SN deliberately diverges** (BACKLOG has the upstream note); `ExportText` refuses a
document that strips to nothing (no more 0-byte `.txt`). Cleanups: `ExportOpen.readOnly` +
`freshDir` (the quadruplicated guard preamble — order preserved, per-caller Problem mapping);
`servePageTarget` (the requestPage/requestScope sibling copy); merge acquires the recognizer
lazily ONCE (`recognizeBatch` / `DocumentSeedFlow.recognizerReady`+`recognizeWith`);
`DocumentDao.pageDocumentsIn` batch read for `ExportText.markdownOf`; `hasDocument` cached per
Export screen (extension re-discovery kept per-resume); `runPush` extraction + the
saveNow/saveDraftNow fold; ImportFlow + ExportActivity got their over-800 written reasons.
Docs: `docs/document.md` NEW (the feature bible); `docs/extensions.md` fifth-point seam section
+ module table to NINE + API-3 rows + identity block + **boundary-audit rows 14–18**;
export/import/library/notebook docs updated; both CLAUDE.mds; BACKLOG arc-19 ledger. Gates:
full `./gradlew test` green both variants, all nine modules assembleDebug+assembleRelease,
release signed, NUL-sweep clean. **Nomad walk PASSED** (agent walk: kill-host reconnect live,
crash buffer empty; its "automation hook blocked" FAIL was the walk-agent trap's ~15th firing —
the broadcast needs `-p <package>`; re-driven by hand: hook set_text/get_text round trip, host
killed under a live editor with unsaved words → DeadObjectException park + 2-s retry beats
observed → editor closed → Bootstrap bounce relaunch → reopened editor served
"…AFTER-KILL" — **the words typed while the host was dead landed in the `.soil`**, the exact
family the fixes closed; scope toggle round trip 0→1→0; crash buffer empty). The walk-agent
prompt must say `append_text` (not `append`) and include `-p` in every broadcast. User
checklist pending.

`/code-review` on the arc range (level asked at phase start), fix/accept per user call.
Boundary audit: new rows for the fifth point (the callback binder — the first host-side stub on
an extension seam — text chunking, the store's small-state layout), the result-kind tail, the
`SOURCE_DOCUMENT` seam. Docs: **`docs/document.md`** NEW (the feature),
`docs/extensions.md` (fifth point + seam sections + module table to NINE + identity block +
API-version 3 rows), `docs/export.md` (Source row + document exporter), `docs/import.md`
(result-kind tail), `docs/library.md` (text documents), `docs/notebook.md` (Document button),
`docs/sn-screen.md` if touched, both CLAUDE.mds, root CLAUDE.md arc record, BACKLOG ledger,
memory. Version stamp question at phase start. Freeze.
**Gate:** full JVM suite both variants, all nine modules build debug + release, NUL-scan, final
Nomad walk, user checklist re-run of anything a fix touched.
*Fable review + audit; Sonnet docs pass; Haiku the final walk.*

**Questions to resolve at phase start:** review level; version stamp (stay `0.1.0-ratta`?).
