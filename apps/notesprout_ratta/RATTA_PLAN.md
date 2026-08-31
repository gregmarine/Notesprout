# RATTA_PLAN.md — Notesprout SN ("ratta paper")

**Branch:** `ratta` · **Location:** `apps/notesprout_ratta/` · **Package:** `com.symmetricalpalmtree.notesproutsn`
**Label:** Notesprout SN (debug: "Notesprout SN Dev") · **Version:** `0.1.0-ratta`
**This file is the cross-session memory for the effort. Read it first, whole, at every phase start.**

A from-scratch, Supernote-only rebuild of Notesprout in the spirit of the Paper experiment.
Original Notesprout (`apps/notesprout_android`) and Notesprout Paper (`apps/notesprout_paper`)
are **reading references — no app code is copied**.

**Arcs 1–18 are complete and frozen.** Their entries below are compact ledgers: status, what
still binds, and the reference doc. **The full phase-by-phase records (outcomes, findings,
walk logs) live in git history — `git show 90a9198:apps/notesprout_ratta/RATTA_PLAN.md`** —
and each feature's authoritative reference is its `docs/` file. The active arc (19) keeps its
full plan at the end of this file.

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

## Architecture (current — after arc 18)

- **Own Gradle root** at `apps/notesprout_ratta/`. Gradle 8.14, AGP 8.11.1, Kotlin 2.2.20,
  KSP, compileSdk/targetSdk 35, minSdk 29, Java 17 via `org.gradle.java.home` (Temurin-17).
  Repos: `mavenLocal()`, `google()`, `mavenCentral()`. `android.nonTransitiveRClass=false`
  (load-bearing since J1 — undoing it breaks every moved resource reference).
- **Eight modules** (nine once arc 19's `:ext-document` lands): `:app` (host) · `:sn-screen`
  (shared paper-screen library — g-paper `api`, design resources, screen helpers; **a fix to
  shared screen logic goes there, never in a consumer**) · `:markdown` (arc 19 / M1 — the
  shared markdown engine, stdlib only, never depends on `:app`/`:sn-screen`/`:extension-api`;
  `:app` still carries its arc-3 `core/markdown` twin until consumers repoint) ·
  `:extension-api` (contract library, stdlib only) · `:ext-mlkit` · `:ext-scratchpad` ·
  `:ext-soil` (exporter + importer services, one APK) · `:ext-pdf` (module-local
  pdfbox-android 2.0.27.0). Full table: app `CLAUDE.md` + `docs/extensions.md`.
- **g-paper pin: 0.1.23** in `sn-screen/build.gradle.kts` — `gpaper-core` + `gpaper-ratta`
  only. No Onyx, no jetifier, no pickFirsts, no `tools:replace`.
- **FOUR extension points** (each was its own user decision — no FIFTH without another;
  arc 19's `ACTION_DOCUMENT_EDITOR` is that decision, granted 2026-08-30):
  `HANDWRITING_RECOGNIZER` · `SCRATCH_PAD` (+`_SCREEN`, tier-2 screen-owning) ·
  `NOTEBOOK_EXPORTER` (plural; soil + pdf) · `NOTEBOOK_IMPORTER`. `ExtensionContract.API_VERSION`
  = 2; the host accepts `1..N`; an extension declares what it *requires* of the host.
- **Data model:** index `objects` table (user_version 1) + `Garden/<uuid>.soil` universal
  `notebook` table v1 + `notebook_meta`. Row types SN writes: notebook/page/template/stroke +
  additive heading/link. StrokeCodec format B; encrypt-by-default global key; SQLCipher stock
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
- **`isPenActive` counts hover** — never idle-gate a dismissal/show that must answer a
  deliberate act; the frame-silence exceptions are ledgered in `docs/notebook.md`.
- **GONE, never disabled; not-built controls do not exist** (J4). Toast confirms / dialog
  explains; **a query is not a name** (don't reuse NameDialog strings for non-names).
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
**Status:** 🧪 Awaiting device verification (code + walk ✅ 2026-08-30 — user checklist below)

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
**Status:** ⬜ Not started

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
**Status:** ⬜ Not started

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

**Questions to resolve at phase start:** create-screen radio wording; whether a text document
shows in the library with a distinct badge beyond the cover glyph; **and the show-pages exit's
control** — M6's review removed the header's ✓ Done (the back arrow is the one leave door for
ordinary notebooks), so the og shape "✓ = show pages / Close = library" needs a fresh answer
for text documents (a text-document-only button? a Close sub-choice? a library route only?).

### M9 — Export: `SOURCE_DOCUMENT` + PDF-of-preview
**Status:** ⬜ Not started

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

### M10 — Proofread
**Status:** ⬜ Not started

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
**Status:** ⬜ Not started

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
