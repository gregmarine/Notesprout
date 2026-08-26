# RATTA_PLAN.md — Notesprout SN ("ratta paper")

**Branch:** `ratta` · **Location:** `apps/notesprout_ratta/` · **Package:** `com.symmetricalpalmtree.notesproutsn`
**Label:** Notesprout SN (debug: "Notesprout SN Dev") · **Version:** `0.1.0-ratta`
**This file is the cross-session memory for the effort. Read it first, whole, at every phase start.**

A from-scratch, Supernote-only rebuild of Notesprout in the spirit of the Paper experiment.
Original Notesprout (`apps/notesprout_android`) and Notesprout Paper (`apps/notesprout_paper`)
are **reading references — no app code is copied**. Arc 1 target: full Paper-v0 parity (the
tree at commit `87277da`) with zero extension machinery.

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
   `./gradlew publishToMavenLocal`, pin the new version here. Never work around an engine
   bug in the host.

## Locked decisions (from the arc-planning wizard — do not re-ask)

| Decision | Answer |
|---|---|
| Data compatibility | **Format-compatible only.** Identical `.soil` / `notesprout.db` formats in SN's own `getExternalFilesDir`. No shared on-device storage with Paper, no import path in arc 1. |
| Package / applicationId | `com.symmetricalpalmtree.notesproutsn`, debug suffix `.dev` |
| Rebuild depth | **Fresh code.** Paper v0 (`git show 87277da:apps/notesprout_paper/...`) is the reading reference; no file copying (build boilerplate like the Gradle wrapper is exempt). |
| Arc-1 scope | **Full Paper v0 parity**: bootstrap/recovery-key/unlock, encrypt-by-default, built-in templates (Blank/Lined/Dotted/Grid), covers, pinned + recents, sort, rename/move/delete, page gestures incl. undo/redo + page delete, eraser + lasso. |
| App name / icon | **Notesprout SN**; Tabler seedling **mirrored** (group `scaleX="-1"`, pivot 54, over Paper's vector recipe), black outline on white adaptive icon; all icons Tabler outline. |
| Engine | g-paper **0.1.4** from mavenLocal: `gpaper-core` + `gpaper-ratta` **only** — no `gpaper-onyx`, no BOOX maven repo, no jetifier, no jniLibs pickFirsts, no `tools:replace` label hack. `RattaEngine.register()` only; `GPaper.create(this)` (generic fall-through = desk testing off-device). |
| Extensions | **None.** No extension-api, no extension stores, no `extensionStoreFile`. Formats stay family-compatible so future arcs can add them. |

## Non-goals for arc 1 (do not build, do not scaffold "for later")

- No extension system of any kind (no AIDL, no `<queries>`, no proxy/binder surface).
- No import/export UI, no backup, no Drive, no recognition, no documents, no scratch pad,
  no sticky notes, no links, no content objects — plain ink notebooks only.
- No Onyx/Generic *device* support (the generic engine remains only as g-paper's built-in
  desk-testing fall-through).
- No landscape, no tablets other than Nomad/Manta, no per-notebook keys (global key only).

## Architecture

- **Own Gradle root** at `apps/notesprout_ratta/` (no monorepo root build). Gradle 8.14,
  AGP 8.11.1, Kotlin 2.2.20, KSP 2.2.20-2.0.4, compileSdk/targetSdk 35, minSdk 29, Java 17
  via `org.gradle.java.home` (Temurin-17). Repos: `mavenLocal()`, `google()`, `mavenCentral()`.
- **Single `:app` module.** Namespace `com.symmetricalpalmtree.notesproutsn`. Dependencies
  mirror Paper's `:app` (appcompat, core-ktx, Room 2.7.0 + KSP, coroutines, lifecycle,
  kotlinx-serialization-json, SQLCipher 4.6.1 with `arm64-v8a` abiFilter,
  androidx.security-crypto, junit) + `com.symmetricalpalmtree.gpaper:gpaper-{core,ratta}:0.1.4`.
- **Data model = Paper's, byte-for-byte compatible.** Authoritative references:
  `apps/notesprout_paper/docs/data.md`, `docs/crypto.md`, and the schema sources
  (`data/soil/SoilSchema.kt`, `data/index/*`, `paper-screen/.../core/StrokeCodec.kt`).
  In short: index `objects` table (user_version 1, folders index-only, flat `Garden/`),
  `.soil` universal `notebook` table v1 + `notebook_meta` (same field set; row types
  notebook/page/template/stroke only — never object/link in SN), StrokeCodec format B,
  InkColorCodec, encrypt-by-default global key (`NSPT-` Crockford recovery key,
  PBKDF2-HMAC-SHA512 ×256 000, salt = file bytes 0..15, SQLCipher stock defaults), one
  `SoilCrypto` factory point, never-delete-on-corruption open helper. `SoilFile.kt` is the
  only path constructor. Remember the `"order"` column quoting rule (double-quoted in SQL,
  backticked in Room).
- **Screens** (fresh code, Paper v0 shapes): `BootstrapActivity` (only index opener,
  noHistory) → `RecoveryKeyActivity` / `UnlockActivity` → `LibraryActivity` (breadcrumbs,
  paginated non-scrolling card grid, pinned/recents overlays, sort, long-press action sheet)
  + `NewNotebookActivity` (template radios) + `FolderPickerActivity` → `NotebookActivity`
  (full-bleed paper, chrome overlaid via `setExclusionRects`, toolbar pen/eraser/lasso,
  `PageGestures` observer, `NotebookSession`, single serial `SoilWriter`, `UndoRedoStack`
  bounded 100, `CoverSnapshot` on close).
- **Standing rules** (detail in `apps/notesprout_ratta/CLAUDE.md`): portrait-locked; no
  colour in chrome; one layout per screen; `IndexGuard` first thing in every index-touching
  `onCreate`; frame-silence (never present an app frame while `paper.isPenActive`);
  toast-confirms / dialog-explains; TopGuard = 0 on Ratta (chrome flush at top edge);
  no file over ~800 lines without a written reason.
- **Host/engine split:** the four Supernote overlay laws live inside g-paper 0.1.4. The host
  does only the documented host responsibilities (`~/git/g-paper/docs/host-responsibilities.md`):
  page swap = `clearForContentSwap` → `setPageSize`/`setTemplate` → `loadStrokes`
  (+ `notifyContentChanged`), undo/redo via `addStrokes`/`removeStrokes`, exclusion rects
  for chrome, `releaseForHandoff`/`release` lifecycle.

## Standing traps (learned in prior efforts — assume they still apply)

- **Supernote swallows `adb shell input text`** (PinyinIME eats injected keys). Device
  agents must type via on-screen-keyboard tap coordinates or avoid text-entry paths;
  dialogs shift ~350 px when the IME shows.
- EPD live-ink overlay is invisible to `screencap` — committed (baked) strokes are visible.
  Screenshot-verify only committed content; live ink is the user's eye.
- Ratta hardware keyboard types only while the IME is shown (matters for Unlock later).
- Gradle zipflinger holes inflate incremental debug APKs — clean build if APK size looks wrong.
- **`input keyevent` letters are swallowed too** (R2 finding) — not just `input text`. Typing on
  the Supernote works only by tapping the on-screen keyboard keys, and the tap coordinates must be
  measured from a screencap taken **after** the IME is up (the dialog shifts up; the keyboard keys
  themselves are stable: a≈(145,1567) b≈(840,1683) c≈(562,1683) on the Nomad, dialog field ≈(700,935)
  pre-IME, dialog buttons ≈y 687 post-IME).
- **Back at the library root exits the app** (by design). A device agent must never use
  `input keyevent 4` to dismiss the IME while at the root — it drops to whatever app is underneath.
- **adb cannot inject stylus ink on the Supernote** (R3 finding): `input stylus swipe` and
  `input swipe` deliver nothing to the ink path. Committed-ink verification needs the user's pen;
  agents can still verify chrome, panels, and persistence of strokes the user already wrote.
  Finger `input tap` works normally.
- **`adb push` into `/sdcard/Android/data/<pkg>/files/` fails with `remote fchown failed` — and the
  failed push DELETES the existing target file** (R6 finding: it removed SN dev's live
  `notesprout.db`; recovered from the local pulled copy). The working method: `adb push` to
  `/data/local/tmp/`, then `adb shell cp` into place (`rm` the target first if it pre-exists —
  group `ext_data_rw` can create/delete in the dir but not overwrite an app-owned `-rw-r-----`
  file), then `rm` the temp. `adb pull` from those dirs works fine.
- **`monkey -p <pkg> 1` does not reliably bring the target app to the foreground** (R4 finding):
  with Notesprout Paper dev's task frontmost, a monkey launch of SN dev left Paper in front, and an
  entire Haiku device walk silently "passed" against **Paper's** notebook (same features, near-same
  UI — only the dialog wording gave it away). Device agents must launch with
  `am start -n <pkg>/<fully.qualified.Activity>` and **verify `dumpsys activity activities |
  grep mResumedActivity` shows the target package before every screencap-based conclusion.**
  Note: finger `input swipe` (page-flip gestures) works fine — the R3 ink limitation is the stylus
  path only.

---

## Phases — Arc 1 "Ratta Paper"

### R0 — Scaffold & identity
**Status:** ✅ Complete (commit cf890a3, Nomad-verified 2026-08-20)

Branch `ratta` (done). Gradle root (wrapper copied from Paper — boilerplate exemption) +
single `:app`; `gradle.properties` (Temurin-17 home, AndroidX, **no jetifier**);
`settings.gradle.kts` (mavenLocal/google/mavenCentral, FAIL_ON_PROJECT_REPOS); e-ink design
resources fresh-written to the design system (colors/themes/styles/dimens + `values-sw720dp`
tier); mirrored-seedling adaptive icon + 5 density alias folders; debug variant (`.dev`
suffix, `-dev` versionName suffix, "Notesprout SN Dev" label via debug manifest) / release
(unsigned, signed by hand with the debug keystore); placeholder launcher screen (temporary
`MainActivity` shell to be replaced in R1); JVM test harness with one smoke test.
**Gate:** `assembleDebug` + `assembleRelease` green; `test` green; installs and launches on
the Nomad (Haiku device check: launch + screencap + crash buffer).
*Sonnet scaffolds; Fable reviews.*

**Questions to resolve at phase start:** none — all identity decisions locked above.

**Outcome:** Sonnet scaffold, green on first pass (assembleDebug/assembleRelease/test); Fable
review clean. Deliberate deviations: the icon's mirror group is the *outermost* group (pivot 54
is viewport-space; an inner-group pivot would fly the glyph off-canvas); `styles.xml`/`themes.xml`
carry only what R0 renders (no dialog/toolbar-button widget styles yet — they land with the real
screens so no dangling drawable refs). Haiku device check on SNN all-pass: debug + release
side-by-side (`…notesproutsn.dev` 0.1.0-ratta-dev / `…notesproutsn` 0.1.0-ratta), placeholder
renders, crash buffer empty. Both apps left installed.

### R1 — Crypto + data core
**Status:** ✅ Complete (commit 6820112, Nomad-verified 2026-08-20)

`crypto/` stack (GlobalKey, SecurePrefs, PassphraseStore, AttemptLimiter, DerivedKeyStore,
RawKeyDerivation, KeyMaterial, KeySession, KeyOpener, SoilCrypto), `data/SoilFile.kt`,
index Room DB + DAO + repository + `IndexGuard`, soil Room DB + `SoilSchema` + meta store,
`StrokeCodec` + `InkColorCodec`, Bootstrap → RecoveryKey → Unlock flow (replaces the R0
placeholder). **JVM tests:** StrokeCodec round-trip **plus fixture bytes generated by
Paper's codec** (byte-compat proof), KDF vectors, InkColorCodec, NotebookMeta
serialization, index/list-id constants.
**Gate:** tests green; on-device Haiku walk: first-run mints recovery key → set passphrase
→ relaunch → unlock → empty library shell; attempt limiter behaves.
*Fable writes schema + crypto contracts; Opus implements around them.*

**Questions to resolve at phase start:** confirm identical crypto UX to Paper v0
(recovery-key screen wording, attempt-limiter thresholds) or Ratta-specific adjustments.
**Answered 2026-08-20: identical to Paper v0** — same wording, thresholds (1–2 free ·
3–4 → 30 s · 5–9 → 5 min · ≥10 → 1 h), confusable-folding unlock, 450 ms "Preparing…".
The only delta is the standing Ratta device rule: the Unlock screen never hides the IME
while the key field has focus (hardware keys only deliver while the IME is shown).

**Outcome:** Fable wrote the contract layer (crypto stack, SoilFile, NonDestructiveOpenHelperFactory,
index + soil schemas/DAOs/databases, `SnIndex` open state machine, IndexGuard, StrokeCodec,
InkColorCodec, NotebookMeta) + the JVM suite; Opus built the Bootstrap → RecoveryKey → Unlock
screens, library shell (debug ⋯: Show recovery key / Forget cached key), IndexRepository, res +
manifest. **Byte-compat proofs:** (1) Room identity hashes of SN's generated `SoilDatabase_Impl` /
`IndexDatabase_Impl` match Paper's exactly (`7c05940f…` / `cd6b2701…` + both legacy hashes) — a
Paper file passes SN's Room validation and vice versa; (2) test fixtures generated by *running
Paper's own codecs* (stroke blobs — decode-exact + decompressed-payload byte equality, NotebookMeta
JSON — exact string equality, GlobalKey format vectors, full-256k-iteration KDF vector confirmed
independently with Python hashlib). 32 JVM tests green; debug + release build. **Nomad walk
all-pass** (Haiku agent + Fable hand-verification after the agent mis-aimed the checkbox — the
widget was fine at [53,653][267,769]): first-run mint → recovery screen → click-guard → library
shell; index header encrypted; forget-key kills process → Unlock; limiter locks out exactly on the
3rd failure (30 s countdown, entry row GONE, returns after expiry); paste-unlock succeeds
(clipboard survives the kill — long-press Paste works on Ratta); relaunch + cold start land on the
library via the cached-raw-key fast path; crash buffer empty. One on-device fix: AppCompat dialogs
read the **un-prefixed** `buttonBar*ButtonStyle` theme attrs — with only the `android:`-prefixed
pair the buttons render framework-default ALL-CAPS (fixed in `themes.xml`, re-verified on device).
Deviations: unlock never hides the IME (recorded in the class KDoc); debug chooser is a styled
`AlertDialog.setItems` (no ActionSheetDialog until a later phase needs one); prefs files are
`sn_secure` / `sn_dkeys` (device-local, not format). Both variants left installed on SNN, unlocked.

### R2 — Library
**Status:** ✅ Complete (commit ca8347d, Nomad-verified 2026-08-20)

`LibraryActivity` (grid math, breadcrumbs, pagination — non-scrolling, measured against
the real band), `NewNotebookActivity` (name rules + timestamp default, built-in template
radios rendered by an in-app `BuiltInTemplates` renderer, `SoilDatabase.create` → notebook
row → template row → page 1 sized to full portrait screen px → `notebook_meta` → `seal()`
→ index row), `FolderPickerActivity` (Move), rename/move/delete action sheet,
sort/pinned/recents prefs, cover rendering in cards. **JVM tests:** grid/pagination math,
name validation, sort orders.
**Gate:** tests green; Haiku device walk on the Nomad: create folder + notebook (avoiding
`input text` — use default name), rename via keyboard taps, move, delete, pin, sort,
breadcrumb navigation, relaunch restores browse position.
*Opus implements; Fable reviews.*

**Questions to resolve at phase start:** default notebook-name format; folder nesting
depth cap (Paper's rule); whether Recents/Pinned are both in arc 1's library chrome from
day one or land with covers polish in R5.
**Answered 2026-08-20: all three = Paper v0's rules.** Default name `YYYYMMDD_HHmmss`
(editable, whitelist `[a-zA-Z0-9_\-. ]`, reject `.`/`..`, non-empty, unique in target
folder); no nesting depth cap (ancestry walk cycle-guarded at 50 hops); Pinned/Recents
bottom-bar buttons land now as stubs (toast "Later") + ids-only prefs stores, the modes
wire up in R5.

**Outcome:** Opus implemented the whole phase (one background agent); Fable review found **one
parity fix** — `DOT_RADIUS_MDPI` 1.5→2 (Paper v0's on-device legibility finding; test + doc
updated) — everything else clean. New: `library/{GridMath,NameRules,SortRules,LibraryGrid,
NameDialog,NewNotebookActivity,FolderPickerActivity}`, `notebook/NotebookActivity` (stub, entry
contract fixed: `EXTRA_NOTEBOOK_ID`/`_NAME`), `core/{ActionSheetDialog,Bitmaps}`,
`data/prefs/{SortPrefs,BrowseState,RecentsPrefs}`, `data/template/{TemplateGeometry,
BuiltInTemplates}`, `docs/library.md`; `LibraryActivity` rewritten. Creation sequence verified
field-for-field against Paper v0's (notebook `refId`=first page, template `text`=kind +
page-size WEBP blob, page `refId`=templateId|"" order 0, meta → seal → index row last).
**74 JVM tests green** (42 new: grid/pagination, names, sort, template geometry); debug + release
build. Deliberate deviations: problem **dialogs** (not Paper's toasts) for duplicate/invalid names
and move collisions (SN's toast-confirms/dialog-explains rule); pager goes **INVISIBLE** not GONE
(controls never shift); `library_card_min_width` is a tier dimen (140dp base / **200dp sw720dp** —
literal 140 would give five ~2 cm columns on the Nomad; 200 → 3×2, Paper's density);
`WEBP_LOSSLESS` API-guarded (minSdk 29); template feature sizes density-scaled (Paper v0
parity, not the plan's literal px). **Nomad walk: all 15 gate steps pass** — Haiku agent covered
launch/create/templates/files/stubs/pagination/crash-buffer; Fable hand-drove folder create,
duplicate dialog, rename, move, breadcrumbs, sort, delete (+file+sidecar purge, count-verified)
and relaunch-restore-into-folder after the agent could not type (new standing traps recorded
above; the agent's delete "failure" was a mis-tap — delete verified working). Test data left on
device (folder `abc` + 6 notebooks); both variants reinstalled current.

### R3 — Notebook core (write on it)
**Status:** ✅ Complete (commit d805f1f, Nomad-verified + user all-clear 2026-08-21)

**Eye-check #1 round 1 (2026-08-21) findings & responses:**
- *Panels should dismiss on a finger tap on the page* → **fixed**: activity-level dismiss on
  finger `ACTION_DOWN` not over chrome (panel itself counts as chrome — composing never
  dismisses); adb-verified on the Nomad. Round-2 addition: **the stylus dismisses too** — at
  **pen-up**, not pen-idle (round-3 finding: `isPenActive` counts hover, so the idle gate held
  the panel while the pen floated near the glass). One deliberate frame-silence exception: a
  single chrome frame at the stroke boundary; a contact racing the posted close falls back to
  the idle gate.
- *MARKER changes when baking* → **documented g-paper behaviour** (Ratta has no semi-transparent
  live style; live = `NEEDLE`, bake = core's true rendering — live is a preview, the bake is the
  truth). **Deferred out of the ratta arc** per user; recorded in monorepo `BACKLOG.md`.
- *One stroke lost, once, unreproduced* (showed live, gone on close/reopen) → analysis: commits
  fire synchronously at pen-up and the seal drains the writer, so a committed stroke can't be
  lost host-side; the ink was overlay-only (never entered the engine model). Suspects: stale
  exclusion-rect window around a panel toggle, or a raw-delivery drop (4th-overlay-law family).
  **Hardening applied**: every toolbar `releaseRender()` is now pen-gated (`releaseRenderIfIdle`
  — the g-paper API contract; an ungated release in the pen-active window can cost a live
  stroke). Watch through R4–R6; if it recurs, instrument and fix in g-paper.

`NotebookActivity` + g-paper: `RattaEngine.register()` in the Application class,
`GPaper.create(this)`, full-bleed `PaperView` with chrome exclusion rects, toolbar
(pen width/style/16-level greyscale panel, eraser with radius, lasso arm), stroke persist
via the session's single serial `SoilWriter` (`onStrokeCommitted` → store → soil),
template render into the stored page rect, open/close lifecycle (drain → seal →
`CoverSnapshot`), frame-silence rule wired (`whenPenIdle` for chrome text).
**Gate:** JVM tests (StrokeRows mapping, writer ordering); Haiku device walk: write →
close → reopen → committed strokes visible in screencap; relaunch persistence; crash
buffer clean. **User eye check #1:** live ink latency, no ghost overlay after tool
changes, eraser feel.
*Fable does the engine seam + writer; Opus toolbar/chrome; Sonnet layouts.*

**Questions to resolve at phase start:** toolbar layout (Paper v0's shape vs. anything
Supernote-specific the user wants); default pen width/style; eraser radius options.
**Answered 2026-08-20:** toolbar keeps Paper v0's bar shape `[←] [pen] [eraser] [lasso]`
but with **rich panels** — pen button opens a width + style + 16-level greyscale ink panel
(widths **1·2·3·5·8 px**, all five g-paper styles PEN/FOUNTAIN/MARKER/PENCIL/BRUSH); eraser
button opens a radius panel **8·15·30·60 px** (default 15). First-ever pen default =
**PEN · black · 3 px** (Paper v0 parity); panel choices persist per app thereafter.

**Outcome:** Split per the recipe — Fable wrote the engine seam + data layer (`NotebookSession`,
`StrokeStore` single-serial writer, `StrokeRows` format mapper, `CoverSnapshot`,
`NotebookActivity`, the library's cold-launch `reopenLastNotebookIfNeeded` — the R2-reserved
`lastOpenNotebookId` consumer) and the JVM suites; Opus built `NotebookToolbar` + `ToolPrefs`
(panels fully programmatic into layout-contract containers); Sonnet the layout/icons/strings.
All three ran in parallel against a fixed id/behavior contract — integrated green on the first
build. **90 JVM tests** (16 new), debug + release; `unitTests.isReturnDefaultValues = true` added
for the store tests. **Nomad walk all 13 steps pass** (Haiku agent; Fable eyeballed the panel
screencaps), crash buffer clean; cold-restore lands back inside the open notebook; the user's
eye-check strokes survived kill + reinstall + restore. **Eye check #1: three findings over three
rounds, all resolved** (block above — page-tap panel dismiss, then stylus dismiss, then pen-up
instead of pen-idle because `isPenActive` counts hover); user all-clear 2026-08-21. Deliberate
deviations: rich tool panels + `ToolPrefs` (phase decisions), problem-dialog on failed open,
pen-gated `releaseRenderIfIdle` everywhere in the toolbar, the **one frame-silence exception**
(panel close at stylus-up — single chrome frame at a stroke boundary; a contact racing the
posted close falls back to the idle gate), `CoverSnapshot` WEBP API-guard for minSdk 29.
Deferred: MARKER live≠baked (g-paper, monorepo `BACKLOG.md`). Watch item: the one-time
unreproduced lost stroke (overlay-only ink; suspects recorded in `docs/notebook.md` § Known
issues). Docs: `docs/notebook.md` new, `docs/library.md` row updated. Both variants left
installed on SNN.

### R4 — Multi-page + gestures + undo/redo
**Status:** ✅ Complete (commit 72afc92, Nomad-verified + user all-clear 2026-08-22)

`NotebookSession` paging (`goTo`, `insertBlank`, `deleteCurrent`, reconcile),
`PageGestures` observer fed from `dispatchTouchEvent` (1-finger horizontal swipe = flip,
past-the-last-page inserts; 2-finger horizontal = insert before/after; multi-finger
stationary double-tap = undo (2) / redo (3); 1-finger long-press = delete-page sheet +
confirm), `PageMath`, `UndoRedoStack` + `NotebookUndo` action replay (Drew/Erased/Page…)
— notebook-level, bounded 100, DB stays source of truth (undo → store → drain → page
reload). Page swap follows the host-responsibilities sequence exactly.
**Gate:** JVM tests (PageMath, UndoRedoStack, action replay, gesture classifier if pure);
Haiku device walk: flip/insert/delete/undo/redo via adb multi-touch where injectable,
persistence across relaunch. **User eye check #2:** gesture feel, flip cleanliness (no
stale overlay ink crossing pages).
*Opus implements; Fable reviews the gesture/EPD interplay.*

**Outcome:** Opus implemented the whole phase in one background agent (`PageMath`, `UndoRedoStack`,
`PageGestures`, session `insertBlank`/`deleteCurrent`/`reconcile` each in one transaction with
dense 0..N-1 renumber + index mirrors, store `remove`/`restore`, full activity wiring); Fable
review clean — the SN deltas are all deliberate: gesture **stand-down widened to
`selectionActive || toolbar.panelOpen`** and the detector is fed *before* the panel-dismiss block
(so the closing tap can't half-arm a gesture), the BOOX 3-finger `ACTION_CANCEL` case dropped, the
delete-sheet `releaseRender()` documented as safe-because-gate-checked. **113 JVM tests** (23 new:
PageMath, UndoRedoStack), debug + release build. **Device walk: the first Haiku run was invalid —
it silently tested Notesprout Paper dev** (the monkey-launch trap recorded above; only the dialog
wording exposed it, chased through the APK's arsc/dex to `mResumedActivity`). Fable re-drove the
full walk by hand against the verified-foreground SN build: all steps pass (insert-past-last
1/1→3/3, flips both ways, first-page hold, sub-threshold swipe ignored, sheet → confirm → delete
1/2, cold restart restores into the notebook at 1/2, crash buffer empty). Cleanup note: the
misfired agent left a junk 2-page notebook **"Test 08" in Paper dev's library** (agent-created;
user to remove at leisure). **Eye check #2 all-pass 2026-08-22, one wording fix:** the delete
confirm's body "Its ink cannot be recovered." was **false** (soft delete + `reconcile` means undo
restores the page *and* its ink) — dialog reduced to the bare "Delete this page?"
(`delete_page_body` removed; decision recorded in `docs/notebook.md`). Test data left on device:
SN notebook `20260821_004817` (2 blank pages). Both variants reinstalled current on SNN.

**Questions to resolve at phase start:** adopt Paper's exact gesture thresholds
(PAPER_PLAN.md architecture section) or retune for Nomad; page-flip visual (instant swap
vs. any indicator).
**Answered 2026-08-21:** Paper v0's **exact thresholds, verbatim** (flip: horizontal-dominant,
`|dx| ≥ 0.30 × screenWidth`, fling velocity **or** `|dx| ≥ 0.50 × screenWidth`; insert: same
gates on the 2-finger centroid; undo/redo: 2/3-finger stationary double-tap on
touchSlop/longPressTimeout/doubleTapTimeout gates; delete: 1-finger long-press → sheet →
confirm) — retune only if eye-check #2 flags a gesture; the BOOX 3-finger `ACTION_CANCEL`
special-case is dropped unless the Nomad shows the same behavior. Flip visual = Paper's:
**instant swap + persistent "n / N" indicator** in chrome, updates pen-idle-gated. (Already
locked by the phase text, unchanged: swipe-next past the last page inserts; undo covers page
insert/delete → notebook-level stack bounded 100, cleared on close only.)

### R5 — Lasso + polish
**Status:** ✅ Complete (commit 4445744, Nomad-verified + user all-clear 2026-08-22)

Lasso select/move (firmware dash trail comes free from g-paper's Ratta engine), selection
box behavior + drag commit (Moved undo action), smart-lasso/scribble-erase toggles as in
Paper v0, covers/pinned/recents polish, empty states, dialog pass (toast-confirms /
dialog-explains audit), library ↔ notebook chrome consistency pass.
**Gate:** JVM tests for lasso hit math only if host-side math exists (engine owns hit
tests); Haiku device walk for committed results of lasso moves. **User eye check #3:**
trail rendering, drag feel, selection dismiss.
*Opus implements; Fable reviews.*

**Questions to resolve at phase start:** lasso action set for arc 1 (move only, or
move + delete); smart-lasso default on/off.
**Answered 2026-08-22:** lasso = **move + delete** (Moved + Deleted undo actions);
**smart-lasso on by default**; follow-up: **scribble-erase also on by default** — both
toggleable in a new lasso panel, persisted in `ToolPrefs`.

**Outcome:** Opus implemented the whole phase in one background agent against Fable's contract;
Fable review clean. Lasso **delete** = tap inside the selection box → `onSelectionTapped` → one-row
sheet "Delete strokes" (no confirm — undoable, same reasoning as R4's bare page-delete confirm);
host keeps `currentSelection`, captures geometry from `liveStrokes` *before* `removeStrokes`
(which itself dismisses the selection), then `store.erase` + `Action.Deleted` (replays like
`Erased`, kept distinct for a future undo label). **Lasso panel**: second tap on the armed lasso
button → "Pen gestures" panel with Smart lasso / Scribble erase latches, both **default ON**,
persisted in `ToolPrefs` (`smartLasso`/`scribbleErase`), written straight to the engine flags.
**Pinned/Recents modes** on the Paper v0 model (mode title + `ic_x` close, create buttons stand
down, per-mode empty states, pin = index list edge, pin badge chip `bg_pin_badge`, recents subtitle
= parent folder, `pruneDeleted` on shelf build) with SN deltas: mode buttons toggle themselves +
carry `state_selected`; pinned shelf follows the **on-screen sort** (edge `sortOrder` recorded,
unused). `RecentsAssembly` pure helper (stored order wins). Dialog pass: recovery-tick toast →
problem dialog; `library_later` + dead `notebook_stub_body` removed; tooltip audit. The selection
sheet is the **third recorded frame-silence exception** (docs/notebook.md § frame-silence — all
three: one chrome frame at a stroke boundary from a deliberate act). **122 JVM tests** (9 new:
RecentsAssembly ×6, ToolPrefs defaults ×2, Deleted-on-stack ×1), debug + release build. **Device
walk:** the Haiku agent reported pin + lasso-panel failures — **both were its tap aim** (the R2
delete-"failure" pattern); Fable re-drove by hand with screencap-measured coordinates: pin badge /
Pinned shelf / Unpin label, **mode persists across force-stop** (cold launch lands back in the
Pinned shelf), lasso panel + toggle persistence across restart, page-tap panel dismissal, crash
buffer clean — all pass. **Eye check #3 all-pass 2026-08-22** (trail, drag, delete sheet,
undo/redo, smart lasso, scribble erase, dismissal) — no findings. Test data: `20260820_231010`
left pinned on SNN; both variants reinstalled current.

### R6 — Hardening, compat proof, review, freeze
**Status:** ✅ Complete (commit 7570770, Nomad-verified + user all-clear 2026-08-22)

Code-review pass (findings fixed or explicitly accepted, recorded here); **format-compat
proof on the Nomad**: same passphrase in both apps, adb-copy a Paper-created `.soil` into
SN's Garden (+ index row) and open it — and the reverse into Paper dev; full regression
(Haiku device agents + the arc's short user checklist); docs freeze (`docs/` under the
app), memory + root CLAUDE.md updates; version stamp; commit + push.
**Gate:** everything in Verification below.

**Questions to resolve at phase start:** review depth (/code-review level), whether the
compat proof should also cover an encrypted notebook created before a passphrase rotation.
**Answered 2026-08-22:** review at **high**. Rotation case **skipped as vacuous** — neither
app has a rotation (or set-passphrase) flow; in the v0 family the minted `NSPT-` recovery
key *is* the immutable global passphrase ("future rotation" is only a code comment). The
user declined the adopted-key substitute (wipe SN dev + push Paper's index — no device data
wipes). Basic proof mechanics, since "same passphrase in both apps" also has no in-app path:
copied `.soil`s are re-keyed on the Mac with the stock sqlcipher CLI (`PRAGMA rekey`
source-app key → destination-app key, keys read from each debug ⋯ "Show recovery key"),
and the destination index row is inserted the same CLI way (force-stop first; WAL sidecars
pulled together). Stock-CLI rekey doubles as a stock-SQLCipher-defaults proof.

**Outcome:** **Review (`/code-review high` over the whole arc):** 36 candidates → 10 confirmed
correctness findings survived verification — **all ten fixed by Fable** (124 JVM tests + both
builds green): ① library double-tap opened two concurrent writers on one `.soil` → `openNotebook`
latch, the one door in, reset in `onResume`; ② Back during the ~1 s KDF open window leaked the
opened `SoilDatabase` + WAL forever → both layers seal an abandoned open (`NotebookSession.open`
catch + `sealAbandonedOpen` on appScope, `NonCancellable`); ③ g-paper callbacks read
`session.currentPage` racing IO page mutations (wrong-page ink, torn-read crash) → Main-only
`displayedPageId` stamped at the two `loadStrokes` sites; ④ ink before `opened` was silently
dropped → block-all exclusion rect until the page is loaded; ⑤ `SnIndex` probe-`Invalid` built a
fresh index over an existing damaged file → `PrepareOutcome.DAMAGED_FILE` + honest Retry/Close
dialog (never create over, never delete); ⑥ `close()` sealed under a possibly in-flight page
transaction → every seal/persist path now holds `pageOps`; ⑦ mid-flip `template.recycle()` while
the engine still paints it → reference-drop only (minSdk 29); ⑧ a failed/interleaved undo replay
corrupted history → push-back on failure + `generation` counter guarding the redo push (2 new JVM
tests); ⑨ `deleteFolderRecursive` non-transactional (kill mid-cascade strands alive subtrees,
never purged) → one Room transaction; ⑩ Bootstrap's catch swallowed `CancellationException` and
showed a dialog on a dead window → rethrow + `isFinishing`/`isDestroyed` guard; a mid-open crash
now lands in the failed-open dialog, not an uncaught-scope crash. **Seven findings explicitly
accepted** (reasons + the Paper twin of ⑤ recorded in monorepo `BACKLOG.md`): StrokeCodec
forward-compat gaps (frozen family format), `auto_vacuum` no-op, case-sensitive name collisions,
library perf niggles (Main-thread cover decode, blob-laden `alive()`, per-stroke `MAX(order)`),
breadcrumb duplication, dead `createRaw`/`SoilDao` surface — all byte-identical-or-parity with
Paper. **Format-compat proof (both directions, Nomad):** Paper's "Test 04" (1 page, 34 strokes,
3 object rows, extension-rendered lined template) re-keyed on the Mac with the stock sqlcipher CLI
4.17.0 (`PRAGMA rekey`, Paper key → SN key — the CLI opening both indexes doubles as the
stock-SQLCipher-defaults proof) + index row inserted the same CLI way → **opens in SN**: strokes +
template render, object rows correctly ignored, and SN minted its own cover on close. SN's
`20260820_231010` (3 pages, 26 strokes, no template row) → **opens in Paper dev at its remembered
page 3/3** (the notebook row's `refId` honored cross-app). Both imports left on device. Rotation
leg skipped as vacuous (block above). **Regression:** Haiku walk 10/13 pass; its two "failures"
(Create button, delete-sheet row) were **tap aim again — the third R2/R5-pattern occurrence** —
Fable re-drove both by hand: create → opens at 1/1, flip-insert 2/2, flip back, delete sheet →
bare confirm → 1/1, cold force-stop restore lands back in the notebook, test notebook deleted
(sheet title verified first), Pinned/Recents modes pass, double-tap guard proves exactly one
`NotebookActivity`, crash buffer empty. Agent side-effect cleaned: its stray inserted page in
`20260820_231018` removed (back to 1/1). **New standing trap recorded** (list above): a failed
`adb push` into `Android/data` *deletes the target*; recovered from the local copy, two-step
`/data/local/tmp` + `cp` is the method. Docs updated (`notebook.md`, `library.md` — all R6
behaviours), BACKLOG.md updated, version stamp stays **0.1.0-ratta** for the arc freeze. Both
variants reinstalled current on SNN. **User eye-check all-pass 2026-08-22** (write-immediately
after open, ink/eraser feel, lasso trail + drag + delete sheet, flips + two-finger insert +
undo/redo, the Test 04 import, no findings) → all-clear given; arc 1 frozen at this commit.

---

## Phases — Arc 2 "Polish" (user-directed 2026-08-22)

### P1 — Fixed tools, selection context toolbar, "Opening…" overlay
**Status:** ✅ Complete (commit ee7337d, Nomad-verified + user all-clear 2026-08-22)

Three user-requested polish items, converging SN on og-Notesprout/Paper behaviour:

1. **Fixed tools — all panels removed.** Pen hardwired **PEN · black · 3 px** (og's stored
   default = Paper's `PEN_WIDTH_PX`); eraser hardwired **15 px radius**; tool buttons are
   plain arm-only taps (second tap = no-op, Paper style — og's eraser-toggles-back-to-pen
   declined); smart lasso + scribble erase hardwired **ON** (lasso panel removed too);
   `ToolPrefs` deleted (stale `sn_tool` prefs file cleaned up once). Existing strokes keep
   their stored width/style/grey — render-as-authored, no migration.
2. **Selection context toolbar** replaces the tap-in-box sheet: Delete-only bordered bar
   anchored to the selection box (centred, 8 dp below, flip above, clamped to the chrome
   band — Paper's anchor rules; bounds inflated by g-paper's
   `CanvasPaperView.SELECTION_BOX_INFLATE_PX`), shown at `onSelectionCreated` (not
   pen-idle-gated — lasso ends hovering; the engine already presented the selection frame),
   hidden on drag start, re-anchored after a move, in the exclusion rects + `overChrome`.
   `onSelectionTapped` becomes a no-op; the selection-sheet frame-silence exception is
   replaced by the toolbar-show exception.
3. **"Opening…" overlay, og tap-time pattern**: fresh-coded `core/OpeningOverlay.showThen`
   (pre-draw + post — the Dispatchers.Main async-barrier trap) wrapping the library's single
   `openNotebook` door, plus a destination overlay in `NotebookActivity` visible from the
   first frame until `loadStrokes`/`failOpen` (hide not pen-idle-gated — hover trap).

**Wizard answers (2026-08-22):** pen width **3 px** (not og's on-device 2.5); eraser
**stays armed** on second tap; lasso panel **removed, both toggles fixed ON**; selection
toolbar = **Delete only**; overlay = **tap-time og style** (not destination-only).
**Gate:** JVM tests (new `SelectionAnchor` math; ToolPrefs tests removed) green; debug +
release build; Haiku device walk (no panels on second taps, overlay visible during open,
crash buffer); **user eye check** (selection toolbar anchor/feel, delete, drag hide/reshow,
tap-time overlay).
*Opus implements against Fable's contract; Fable reviews.*

**Outcome:** Opus implemented the whole phase in one background agent; Fable review clean.
`NotebookToolbar` 453→106 lines (panels, prefs, dismiss machinery gone); new
`SelectionAnchor` (pure, 9 JVM tests) + `SelectionToolbar` (bordered floating bar, one
Delete button, placed by margins after an explicit measure; rect in exclusions +
`overChrome`); new `core/OpeningOverlay` + `overlay_opening.xml` (pre-draw + post, per-
activity WeakHashMap cache, resume-after-pause auto-hide) wrapping the library's single
`openNotebook` door, destination overlay VISIBLE from first frame, hidden after
`opened = true` and at the top of `failOpen` (before the dialog — the shield would eat its
OK). One deliberate deviation: g-paper's `CanvasPaperView.SELECTION_BOX_INFLATE_PX` is in a
**private** companion, so the 12f is mirrored in `SelectionToolbar` with a keep-in-step
comment (Paper's own precedent) rather than bumping the engine mid-phase. Fable follow-ups:
app `CLAUDE.md` frame-silence list + no-colour line refreshed (docs/notebook.md § frame-
silence now: delete-page sheet · selection-toolbar show at lasso completion ·
opening-overlay hide at page land; R3's panel-close exception retired). `SnApplication`
deletes the orphaned `sn_tool` prefs file once, off-main. **131 JVM tests** (124 − 2
ToolPrefs + 9 SelectionAnchor), debug + release build. **Haiku walk 7/7 pass on the first
run** (no tap-aim false failures this time): cold restore into the notebook, ink renders,
all three tools double-tapped with no panel, library return, crash buffer clean, cold
restart. The "Opening…" box is not screencap-catchable on cached-key fast opens (it only
flashes) — it earns its keep on the ~1 s cold-derive path; user saw it live. **User
eye-check all-pass 2026-08-22** (fixed-tool feel, selection bar anchor/delete/undo, drag
hide + re-show at drop, dismissal, overlay) → all-clear. Version stays **0.1.0-ratta**
(P1 is a phase, not an arc freeze). Both variants reinstalled current on SNN.

---

## Phases — Arc 3 "Headings" (planned 2026-08-22, wizard complete)

Heading objects baked into the core, og-Notesprout style, using Paper's lessons — plus the
one sanctioned extension: **ML Kit handwriting recognition**. The extension mechanism exists
*solely* so other HWR engines can slot in later; headings and the markdown engine are core.
This amends the arc-1 "no extensions" rule — N0 updates `apps/notesprout_ratta/CLAUDE.md`
accordingly (the recognizer point is the only extension surface; no other capability points).

### Locked decisions (arc-3 wizard 2026-08-22 — do not re-ask)

| Decision | Answer |
|---|---|
| Extension strategy | **Fresh SN extension.** New minimal `:extension-api` + `:ext-mlkit` modules in SN's own Gradle root; fresh-written AIDL scoped to the recognizer point only. Paper's arcs 1/3 (`PAPER_EXTENSIONS_PLAN.md`, `PAPER_RECOGNITION_PLAN.md`, `:extension-api`, `:ext-mlkit`) are reading references — no code copied. Separate model download from Paper's ext accepted. |
| Extension identity | Label **"NSE · ML Kit"** (+" Dev" in debug) — Paper's shared prefix, side-by-side ambiguity with Paper's ext on the Nomad accepted. Package `com.symmetricalpalmtree.notesproutsn.ext.mlkit` (+`.dev`), Tabler puzzle icon, **no launcher activity** (Supernote shows it anyway — accepted), same-signature trust both directions. |
| New Gradle dependency | `com.google.mlkit:digital-ink-recognition:19.0.0` in `:ext-mlkit` **only** (approved in this wizard — the root-CLAUDE.md discussion requirement is satisfied). |
| Heading storage | **Additive row type `TYPE_HEADING = "heading"`** in the universal table (og model on the family shape): `parentId` = page id · `text` = **hash-prefixed markdown** (`"## Title"`), **always non-null** · `flags` = level 1–6 (**authoritative** — the prefix is only ever written from it) · `x/y/width/height` = bounds in page px · `"order"` = z-order among the page's headings · everything else null. No version bump, no migration; Paper ignores the rows (proven-safe additive pattern, same as SN ignoring Paper's `object` rows in R6). |
| Recognition-failure path | **Paper's way**: problem dialog, lassoed ink untouched, retry or give up. A heading ALWAYS has recognized text — the og null-text stroke-fallback state and heading stroke-children **never exist in SN**. |
| Markdown engine | **Full og parser/renderer subset fresh-coded into core** (`core/markdown/`, pure Kotlin): h1–h6, bold/italic/strike, links + `![alt]` as italic caption, un/ordered lists (start-number honoured, **no lettered/roman**), task checkboxes, blockquotes, horizontal rules; the WYSIWYG regex-safety rules (`[^*\n]` classes, no DOT_MATCHES_ALL). og's two parser test suites are the reference. Only the heading path is exercised on-page this arc. |
| Heading typography | og/Paper's: 24 sp bold, per-level scale ×2.0 / 1.75 / 1.5 / 1.25 / 1.1 / 1.0, single line ellipsized, 8 dp padding. |
| Level-pick UI | Selection toolbar gains an **H button** → the bar swaps to an **H1–H6 sub-row** (Tabler `h-1`…`h-6`). CONVERT mode: pure-stroke selection (`contentIds.isEmpty()`) → pick level → recognize → create. CHANGE mode: single-heading selection → sub-row opens with the current level highlighted (1 dp inkBlack border). |
| Ops in scope | Create + render + **tap-to-edit** (hash-free dialog: prefill `stripHeadingPrefix`, Save re-applies `applyLevel` + re-measures, **empty Save = delete**) + **change level** + first-class lasso move/delete with undo. No un-heading/revert command (og parity). |
| HWR flow | **Paper's M-arc flow verbatim, fresh-coded**: only `prepare()` downloads (consent dialog precedes), `Connectivity.isOnline` pre-check (ML Kit's download hangs silently offline), model + present-flag in the extension's sandbox, `warmUp()` from the flag only, warm-up at notebook open once the model exists, "Recognizing…" as an Opening-style overlay, exact `RECOGNIZER_NOT_READY` contract message, extension whole-call budgets just under host timeouts, **never log recognized text** (counts/durations only). |
| Debug trigger | Debug-only **⋯ button at the notebook toolbar's end** → styled chooser with "Recognize page" (whole-page ink in writing order → recognized text in a styled dialog). The recognize-page row is **removed in N3** (user chose not to keep it); the ⋯ button stays only if other debug items remain. |

### Arc-3 standing traps (inherited from Paper's M/H arcs — assume they apply)

- **ML Kit needs writing order**: strokes must be handed over in commit order (LinkedHashMap
  `liveStrokes` order), never a `Selection.strokeIds` Set's hash order — Paper's H4
  "Meeting Notes" bug. Any restore path must also preserve `"order"`.
- Only Binder-marshalable exceptions leave an AIDL stub (`SecurityException`,
  `IllegalArgumentException`, `IllegalStateException`) — anything else kills the
  transaction silently.
- ML Kit's cold `isModelDownloaded` is network-bound and SLOW (75 s worst) — never block a
  `status()` call on it; the present-flag is the fast source. The download survives the
  host's unbind (extension process stays cached). Cold first inference loads the model
  (~1.9 s) — hence warm-up at notebook open.
- Ratta's Apps grid caches label/icon rows — after `adb install -r` it can show stale
  identity; Settings → Apps → My Apps is fresh. Cosmetic only.
- Typing on the Supernote is the user's job (IME swallow trap) — device agents verify the
  edit dialog via uiautomator dumps and keyboard-tap coordinates only where unavoidable.

### N0 — Recognizer extension point + NSE · ML Kit + debug recognize
**Status:** ✅ Complete (commit 19775ed, Nomad-verified + user all-clear 2026-08-22)

`:extension-api` (fresh, minimal — depends on nothing in `:app`): the recognizer AIDL
(`status` / `prepare` / `recognize`), hand-written parcelables (strokes in, per-line text
out — Binder 1 MB cap in mind), capability + contract constants (timeouts, budgets, exact
`RECOGNIZER_NOT_READY` message), same-signature trust check used by both sides.
`:ext-mlkit`: bound service, ML Kit dep, `prepare()`-only download, model-present flag,
whole-call budgets, marshalable-exceptions-only stubs. Host: manifest `<queries>`,
discovery + `RecognizerClient` (bind on app context, supervisor-scope async, unbind in
finally), `RecognizerReadiness` (consent dialog → online pre-check → progress dialog →
ready), warm-up at notebook open, debug ⋯ chooser + "Recognize page" → whole-page strokes
in writing order → styled result dialog. `apps/notesprout_ratta/CLAUDE.md` extension rule
amended.
**Gate:** JVM tests (parcelable round-trips, contract constants, trust-check logic where
pure); debug + release build of all three modules; Haiku device walk on the Nomad
(install host + ext, discovery, consent → download → ready on user-written ink, result
dialog via uiautomator dump, crash buffer); **user eye check** (consent flow feel,
recognize a handwritten page, offline behaviour).
*Fable writes the AIDL contract + trust seam; Opus the extension + readiness flow; Sonnet
module scaffolds/resources.*

**Outcome:** Split per the recipe. Fable wrote the contract +
trust seam (`:extension-api`: SN-namespaced AIDL `IHandwritingRecognizer` + `InkStroke.aidl`,
hand-written `InkStroke` parcelable, `RecognizerStatus`, recognizer-scoped `ExtensionContract`,
`HostCallerCheck`; host: `ExtensionRegistry`/`ExtensionBinder`/`ExtensionCallException`/`InkCaps`/
`RecognizerClient`, `core/Connectivity`, manifest `<queries>` + `ACCESS_NETWORK_STATE`; both module
build files incl. `:ext-mlkit`'s per-variant `HOST_PACKAGE`) and amended the app `CLAUDE.md`
extension rule. Opus wrote `:ext-mlkit` (service with 9.5 s/28 s budgets + marshalable-only
exceptions, `ModelManager` with prepare-only downloads + model-present flag + prime,
`MlKitEngine`/`PageText`, projection-profile `StrokeSegmenter`, `Dots` incl. the shaky-period rule,
`Box`) + host `RecognizerReadiness`, open-time warm-up (one fire-and-forget `status()` bind after
the page lands — never a dialog), `InkPayload`/`RecognizeContext`, and the debug ⋯ (`NotebookDebugMenu`
debug + release-no-op twins, weight-spacer at the row's end). Sonnet built the NSE · ML Kit
resources (Tabler puzzle at the family's ×3.1 ext-icon scale, `NSE · ML Kit`/`… Dev` labels).
**Key deltas, all deliberate:** SN-namespaced action strings so Paper's extensions on the same
Nomad are never discovered (pinned by test); `recognizeContext()` resolves the page via
`displayedPageId`, not `session.currentPage` (mid-flip safety); discovery at the tap (a one-item
chooser can't hide its only row); result dialog = text + timing line (wizard). **179 JVM tests**
(app 142 · api 6 · ext 29 + 2 contract-pin), debug + release of all three modules. **Device walk
(Nomad):** the Haiku agent produced the **fourth** tap-aim false failure ("dialogs don't render" —
it had dumped the Debug-tools AlertDialog itself one step earlier); Fable re-drove by hand: consent
dialog exact wording → Download → elapsed-counter progress (~25 s on Wi-Fi) → auto-continue →
**"No headings" recognized verbatim** (11 strokes → 11 chars · 0.4 s); warm re-run 0.3 s with no
consent; force-stop both processes → cold restore lands in the notebook, warm-up log
`model remembered as present` → `status=0`, engine primed 4.1 s (Paper's Nomad number), post-restart
recognize 0.4 s; crash buffer clean. Model + present-flag live in the ext sandbox; ext APK ~40 MB
(the ML Kit dep — expected). Both dev APKs left installed on SNN, Test 04 unmodified.
**User eye check #4 all-pass 2026-08-22, no findings** (recognition quality on real writing +
timing line, trailing-period rule, blank-page dialog, and — after a `pm clear` of the ext to
re-arm the one-time consent — the offline notice with Wi-Fi off, then consent → download →
auto-continue live). Model re-downloaded during the check; the ext ends the phase READY on SNN.

**Questions to resolve at phase start:** recognition language/model (en-US only vs. a
setting); debug result-dialog contents (text only vs. text + timing); extension versionName
scheme; where the consent dialog first appears (debug recognize vs. also pre-armed at
notebook open).
**Answered 2026-08-22:** **en-US only** (hardcoded model; a setting can come later with no
format impact); debug result dialog = **text + timing** (recognized lines + a small
duration line — bind/recognize ms); extension versionName = **host lockstep**
(`0.1.0-ratta`, `-dev` suffixed in debug, bumped with the host at arc freezes); consent
dialog appears at **first recognize use only** (debug "Recognize page" now, heading-convert
in N2) — notebook open only ever warms up an already-present model, never shows a dialog.

### N1 — Markdown engine (core, pure)
**Status:** ✅ Complete (commit 02e39d6, JVM-only phase — no device gate; 2026-08-22)

`core/markdown/` fresh-coded to the og subset (see locked decisions): `MarkdownParser`
(blocks + inlines), `MarkdownRenderer` (spans), and a measure/draw utility
(StaticLayout-based) sized for heading rendering and reusable by future text surfaces.
No UI change, no on-device behaviour change — this phase is JVM-only.
**Gate:** JVM suite ported from og's two parser test suites (as behaviour reference —
fresh test code) + heading-typography measure tests; debug + release build.
*Opus implements; Fable reviews against og's parser semantics (list start numbers, regex
safety, seven-`#`s-is-not-a-heading).*

**Questions to resolve at phase start:** none expected — scope fully locked above; ask
only if og-vs-SN semantic conflicts surface mid-port. **None surfaced.**

**Outcome:** Opus implemented the whole phase in one background agent; Fable review clean
(every og semantic verified line-against-line: honoured ordered-list start numbers +
per-depth counters, seven-`#`s-falls-to-paragraph, `-*-`-is-not-a-rule, task-before-bullet,
image-before-link with empty-alt-renders-nothing, unclosed-markers-stay-literal, literal
coalescing, blockquote space-join, trailing-`\n` trim before StaticLayout). New
`core/markdown/`: `MarkdownParser` (pure Kotlin, `^`-anchored per-line regexes + index-scan
inlines — no DOT_MATCHES_ALL anywhere), `MarkdownRenderer` (og span set incl.
`HorizontalRuleSpan`, `blockGapPx` param), `MarkdownDraw` (StaticLayout measure/draw, og's
`TextObjectRenderer` minus the TextRender dependency; `singleLine` END-ellipsize flag for
headings), `HeadingTypography` (pure: `BASE_SP` 24 bold, `PADDING_DP` 8, ×2.0/1.75/1.5/
1.25/1.1/1.0). **Two deliberate deviations:** ① the renderer's heading scale delegates to
`HeadingTypography.scaleFor` (one table; observable delta only for out-of-1..6 levels —
unreachable from `parse()`, and clamping is what N2's stored `flags` wants); ② the shared
test `flatten` resolves Code/Strikethrough to visible text (og's returned `""`; no ported
assertion touches either). **42 new JVM tests** (both og parser suites ported case-for-case
+ block/inline/typography coverage; none against the android.text classes —
returnDefaultValues would lie about StaticLayout; renderer/draw are exercised on-device in
N2), suite now app 184 · api 6 · ext 29; debug + release build green (Fable re-ran the
gate). No UI/resource/manifest/dependency change; on-device behaviour identical — devices
untouched.

### N2 — Heading objects end to end
**Status:** ✅ Complete (commit afbe89a, Nomad-verified + user all-clear 2026-08-22)

**Eye-check #5 round 1 (2026-08-22) findings & responses — both fixed, build reinstalled:**
- *H1–H6 must be a sub-toolbar popping up off the bar (og/Paper shape), not a second row growing
  the bar* → **fixed**: `selectionSubToolbar` is its own floating bar hung off the main bar by
  `SelectionAnchor.placeSub` (Paper's `ToolbarAnchor` math ported verbatim — below the bar, above
  when the bar flipped, band-clamped; the main bar never moves when it opens); both bars join the
  exclusion rects and `overChrome`; 6 new JVM tests.
- *Convert recognition garbage ("Heading" → "Go"/"o") while debug Recognize page is excellent* →
  **root cause: writing area.** The page pipeline recognizes per line with the LINE box, and
  Paper's H action passes the **selection bounds** — SN's convert passed the whole page, which
  ML Kit reads as the writing's scale. Fix: `recognizeInk` now gets `sel.bounds.width/height`
  (`HeadingConvert` params renamed `areaWidth/areaHeight` with the why in the KDoc).

**Eye-check #5 round 3 (2026-08-22) finding & decision — no code change:**
- *A doodle always converts into something ("o", junk)* → **accepted as designed** (user's call,
  wizard-asked): ML Kit is a forced-choice recognizer — it essentially never returns blank, and
  candidate scores are only comparable within one result, so a confidence gate would false-reject
  real writing. og/Paper behave identically (Paper rejects only a truly blank result). Recovery is
  already one gesture: the junk heading lands selected → Delete or undo; the problem dialog stays
  for the rare truly-blank result. Do not re-raise a doodle-rejection heuristic.

**Eye-check #5 round 2 (2026-08-22) finding & response — fixed, build reinstalled:**
- *Smart-lasso convert: the pen tool re-arms while the new heading is still selected, and the
  selection can't be dragged/tapped; PEN must not return until the selection is dismissed* →
  **root cause + fix:** the conversion's `removeStrokes` dismisses the smart-lasso selection, and
  the engine's `maybeEndSmartLassoSession` restored PEN before the host re-selected the heading.
  But `clearSelection` fires `onSelectionDismissed` *before* that check, and the check skips the
  restore when a successor selection exists — so the heading selection is now injected **inside
  the dismissal callback** (`pendingSelection` handoff in `NotebookActivity`): the smart-lasso
  session survives the conversion, LASSO stays armed over the selected heading (drag + tap-to-edit
  work), and the engine itself restores PEN + fires `onToolChanged` when the heading's selection
  is eventually dismissed. No host-side tool bookkeeping.

`SoilSchema.TYPE_HEADING` + heading store (`HeadingRows`/`HeadingStore` on the session's
single serial `SoilWriter`, z-order `MAX("order")+1`, soft delete); prefix helpers
(`headingPrefix` / `stripHeadingPrefix` / `applyLevel` — **never hardcode `"# "`**; level
is authoritative, no derive-from-text); g-paper `ContentRenderer` (canvas text via the N1
engine, `hitTargets()`, the live-drag pair `draw(canvas, excluded)` + `drawObject` so
drags don't ghost, `notifyContentChanged` per batch); selection toolbar H button +
H1–H6 sub-row (CONVERT + CHANGE modes per locked decisions); create flow = "Recognizing…"
overlay → recognize (writing order) → success: heading row + stroke soft-delete in **one
undo step**, `setSelection` on the new heading / failure: problem dialog, ink untouched;
tap-to-edit via `onSelectionTapped` hit-test (hash-free dialog, Ratta IME rule — never
hide the IME while the field has focus; empty Save = delete); change level (re-prefix +
re-measure, keep top-left); undo actions (HeadingCreated / HeadingMoved / HeadingDeleted /
HeadingTextEdited / HeadingLevelChanged) replayed through the store then page reload;
page delete/undo carries heading rows; `CoverSnapshot` + page reconcile include headings.
**Gate:** JVM tests (prefix helpers, level↔flags mapping, measure math, store ordering,
undo actions); Haiku device walk (headings persist across close/reopen + cold restart,
move/delete via finger where injectable, crash buffer); **user eye check** (convert a
handwritten title per level, render fidelity, drag feel, edit dialog, level change,
undo/redo of everything).
*Fable writes the store + renderer seam + undo contracts; Opus the toolbar/dialog/flows;
Sonnet layouts/icons/strings.*

**Questions to resolve at phase start:** heading z-order vs. ink (og renders headings
below strokes — adopt?); sub-row anchor behaviour (swap the bar in place vs. second row);
edit-dialog box growth rule (clamp to page width?); whether CHANGE mode's sub-row also
offers Delete or stays level-only.
**Answered 2026-08-22:** **headings below ink** (og parity — strokes draw on top);
H1–H6 sub-row = **second row below the main bar** (Delete/H bar stays visible above it) —
**superseded by eye-check round 1: the og/Paper floating sub-toolbar hung off the bar**;
heading box = **free growth** (measured text width even past the page edge — no clamp,
overhang simply not visible); CHANGE-mode sub-row = **level-only** (Delete stays on the
main bar).

**Outcome:** Split per the recipe — Fable wrote the data/undo/renderer layer + all
`NotebookActivity` wiring (`TYPE_HEADING`, `HeadingPrefix`, `Heading`/`HeadingRows`,
**`SoilWriter` extracted from `StrokeStore`** so stroke + heading writes share the one serial
queue, `HeadingStore` — in-place restores, `StrokeStore.revive` for the writing-order trap —
`HeadingRenderer` (BELOW_STROKES, live-drag pair, free-growth measure via the N1 engine, covers
free through the shared render path), undo actions `HeadingCreated`/`HeadingDeleted`/
`HeadingTextEdited`/`HeadingLevelChanged` + `Moved`/`Deleted` carrying heading ids, page
delete/reconcile carrying heading rows via `liveContentIds` and `Structural.objectIds`); Opus the
SelectionToolbar/H flows/`HeadingConvert`/`HeadingEditDialog`/`RecognizingOverlay`; Sonnet
icons (og `ic_heading`, Paper's `ic_h_1..6`) + strings. **Deliberate deviations:** `HeadingMoved`
folded into `Moved` (one gesture = one undo step); eraser sweep = `onContentErased` →
`HeadingDeleted`; unconditional `notifyContentChanged` after conversion/delete `removeStrokes`
(it skips its re-record on stale ids). **Eye-check #5 ran three rounds** (blocks above): ① sub-row
→ og/Paper floating sub-toolbar (`SelectionAnchor.placeSub`, Paper's `ToolbarAnchor` math; main
bar never moves) + **recognition writing-area root cause** (selection bounds, not page —
"Heading"→"o" fixed); ② smart-lasso PEN restored mid-selection → successor selection injected
**inside `onSelectionDismissed`** (`pendingSelection` handoff — the engine keeps the session,
restores PEN at the heading selection's own dismissal); ③ doodle-always-converts **accepted as
designed** (decision block above — do not re-raise). **247 JVM tests** (28 new: HeadingPrefix 8,
HeadingRows 6, HeadingStore 7, revive 1, placeSub/flipped 6; shared `FakeSoilDao` extracted),
debug + release build. **Device:** Haiku smoke walk 6/7 + the fifth tap-aim false failure (⋯ —
hand-verified working: Recognize page 28 strokes · 0.5 s); crash buffer clean throughout.
**User eye check #5 all-pass 2026-08-22** (convert per level incl. smart-lasso session survival,
render fidelity, floating level popup, drag-as-real-text, mixed lasso, eraser, edit dialog +
empty-save delete, undo/redo of everything, persistence, ink-over-heading) → all-clear. Docs:
`docs/notebook.md` frame-silence ledger → five exceptions (+ app CLAUDE.md). Version stays
**0.1.0-ratta** (N3 decides the stamp). Both variants reinstalled current on SNN; Test 04 carries
the eye-check headings.

### N3 — Hardening, review, docs, freeze
**Status:** ✅ Complete (commit d84273e, Nomad-verified + user all-clear 2026-08-22 — arc 3 frozen at this commit)

**Outcome:** Debug surface removed per the wizard: both
`NotebookDebugMenu` twins, the ⋯ button, `recognizeContext()`/`RecognizeContext`, and 10 orphaned
strings deleted; two wording fixes fell out (`recognize_problem_title` "Recognize page" → 
"Recognition" — it titles the heading-convert problem dialogs and named a removed action; 
"Page too dense…" → "Too much ink to recognize at once." — it fires on selections). **Review
(`/code-review high` over `1b9362b..HEAD`):** 10 confirmed correctness findings — **8 fixed by
Fable**: ① `RecognizingOverlay`'s `WeakHashMap<Activity, View>` cache leaked every Activity for
the process lifetime (the cached View strongly refs its Activity via `View.context` — value→key
defeats the weak key) → replaced with a view-tag lookup in the activity's own tree; **the same
defect existed in P1's `OpeningOverlay`** (out of range) — fixed identically; ② undo of
`Erased`/`Deleted` (and redo of `Drew`) used the tail-append `restore`, scrambling the persisted
writing order N2 declared load-bearing → all three now `revive` (in place); `StrokeStore.restore`
deleted as dead; ③ `doDelete` never drained the writer, so a queued commit could land after the
page delete's snapshot/transaction as a permanently live orphan row → drains first; ④ the ext's
`recognizeInk` scaled `Dots`' tiny-stroke threshold from the whole selection height (a two-line
lasso doubled it, eating real punctuation) → the stub derives a line height from the ink via the
segmenter (`dotLineHeight` param; page path unchanged); ⑤ the download poll's offline branch
skipped the purely-local `status()` bind, falsely failing a model that finished as connectivity
dropped → status polled every iteration, offline included; ⑥ heading boxes measured with the
writing device's `scaledDensity` but stored in page px ellipsized every heading on a font-scale or
density change (portable `.soil`, Nomad ↔ Manta) → `remeasureForDevice` at both page-load sites
(in-memory; position authored, size derived); ⑨ a zero-area (single-dot) selection threw the
absurd "Too much ink" dialog host-side → `RecognizerClient` floors zero to 1 px (negative/NaN
still rejected); ⑩ a pen-up landing in `navigateTo`'s suspending-load window was persisted but
wiped off the glass until the next flip → `loadingCommits` buffer merged into the rebuild.
**2 accepted** (reasons + below-cap cleanup notes in monorepo `BACKLOG.md`): ⑦ the segmenter's
fragment-merge guard (affects only `recognizePage`, which has no consumer in the shipped app);
⑧ any-`N.` paragraph interruption in `MarkdownParser` (**og's parser is identical** — og parity is
the locked reference; fix in og first). `NotebookActivity`'s >800-line written reason added to its
KDoc. **Docs:** `docs/extensions.md` new (the recognizer point end to end);
`docs/notebook.md` rewritten for N2/N3 (headings section, stale P1 claims corrected, review-fix
behaviours). **247 JVM tests** (app 212 · api 6 · ext 29), debug + release of all three modules;
release hand-signed; app debug + signed release + ext-mlkit dev reinstalled on SNN. **Haiku
regression walk 10/10 pass on the first run** (no tap-aim false failures): cold restore into
Test 04, toolbar has **no ⋯**, heading + strokes render, warm-up `status=0`, flips 1/2⇄2/2,
delete sheet up/dismissed, back to library, crash buffer clean, cold-restore to library.
Version stays **0.1.0-ratta** (wizard). **User eye check all-pass 2026-08-22, no findings**
(heading render fidelity, punctuation surviving a convert, single-dot convert without the
"Too much ink" dialog, erase-undo restoring strokes in place, general feel pass) → all-clear;
arc 3 frozen.

`/code-review` over the arc range (level asked at phase start); remove the debug
"Recognize page" row (+ the ⋯ button if empty); docs (`docs/extensions.md` new,
`docs/notebook.md` headings + recognition sections, `docs/library.md` if touched);
root + app CLAUDE.md and memory updates; version stamp decision; full regression
(Haiku walk + short user checklist); commit + push; arc freeze.
**Gate:** everything above green or explicitly accepted; user all-clear.

**Questions to resolve at phase start:** review level; version stamp (stay 0.1.0-ratta
vs. 0.2.0-ratta for a feature arc); keep-or-remove the ⋯ debug button.
**Answered 2026-08-22:** review at **high** (R6 precedent); version **stays 0.1.0-ratta**;
the ⋯ button is **removed entirely** (Recognize page was its only row — the
`NotebookDebugMenu` twins, button, and strings all go; a future debug need re-adds the
known pattern).

---

## Phases — Arc 4 "Contents" (planned 2026-08-22, wizard complete)

A table of contents over the notebook's core `heading` rows — the og/Paper Contents feature,
**baked into `:app` like og** (SN headings are core rows, so Paper's whole extension layer —
`describeOutline` AIDL, capability probe, `OutlineCaps` sanitize, provider-failure dialog —
does not exist here), **designed like Paper's arc 5** (the improved og). Read-only over
existing rows: no schema change, no `user_version` bump, format compat untouched, the
recognizer extension surface untouched.

### Locked decisions (arc-4 wizard 2026-08-22 — do not re-ask)

| Decision | Answer |
|---|---|
| Entry points | **Both, Paper style**: a top-bar Contents button (Tabler `list`, between Back and the pen) **and** a one-finger swipe-down on the canvas. `PageGestures.Listener` gains `onSwipeDown()` (the flip's constants reused: vertical-dominant, ≥0.30×height + fling or ≥0.50×height, `dy > 0` only, same DOWN-time/mid-sequence/fire-time gates, no escrow). |
| Availability | **Hidden when empty (Paper)**: button GONE and swipe silent (no toast) unless ≥1 live heading sits on a live page. Recomputed after open, `navigateTo` (covers flips/inserts/deletes/undo-redo — all replays end there), heading convert, selection delete, eraser sweep (`onContentErased`), and the edit dialog's empty-Save delete. |
| Row tap | **Navigate only (og/Paper parity)**: dismiss → jump to the page (no-op if current); nothing selected on arrival. Navigate + select stays deferred (Paper's backlog note stands). |
| Tree design | Paper's: H1–H6, document order `(pageIndex, y, x)`, parents persist across pages, **orphans attach to the nearest shallower heading or become roots — never dropped**, opens collapsed-to-roots with the active entry's ancestors pre-expanded, highlight = last entry with `pageIndex ≤ current` (nearest **visible** ancestor when collapsed away), in-memory-only expansion, rebuild on every open (no cache, modal snapshot), 2000-entry cap kept as a memory/UI bound on imported files ("Showing the first N headings" footer). |
| Screen | Paper's one-layout-two-forms `Dialog`: ≥480 dp → 60 % left sidebar over a transparent scrim (both real devices: Nomad 749 dp / Manta 1024 dp at density 1.875); <480 dp → full-screen with a back arrow (JVM-tested; provable via `wm size 800x1600`). Row `[+/− toggle | page # 52 dp 20 sp bold | 1 dp divider | label 20 sp ellipsized]`, indent `(level−1)×16 dp`, 68 dp rows, highlight = 5 dp inkBlack bar at the row's right edge, library-shape pager footer (`INVISIBLE` at one page, bound taps no-ops). |
| Core deltas from Paper | Label = `HeadingPrefix.stripHeadingPrefix(row.text)` at gather; level = `flags` via `HeadingRows.toHeading` (tested clamp); availability is exact ("≥1 live heading on a live page"), not a provider approximation; page index from a `session.pages` snapshot; current page derives from `displayedPageId` (R6 rule), never `session.currentIndex`; no `Failed` result — a gather over own rows cannot "not answer". |
| Arc shape | Two phases: **C1** core + dialog + entry points, device-walked · **C2** hardening (`/code-review`), docs, arc freeze. |

### Arc-4 standing traps

- **The Ratta ink daemon draws firmware ink beneath any Android window** — while the
  Contents shows, `pushExclusions()` must push `BLOCK_ALL` (the `!opened` shield's trick),
  and it must be up **before** the dialog's first frame. Exclusion rects fence only the ink
  path, not touch dispatch — dialog taps still work. The small transient dialogs
  (`HeadingEditDialog`, ActionSheet, delete confirm) deliberately do **not** block-all;
  record the asymmetry in docs so a review doesn't "fix" them.
- Dialog show/hide = **frame-silence exception #6** (deliberate act through a pen gate;
  pen-gated `releaseRender` precedes the show). Do not idle-gate the show — a hovering pen
  would hold the dialog hostage (the R3/P1 hover lesson).
- Immersive flags before **and** after `Dialog.show()` or the system bars flash (Paper's
  recorded note).
- One `writer.drain()` covers strokes and headings (the shared `SoilWriter`) — a heading
  created a moment ago must appear in the gather.

### C1 — Core + dialog + entry points
**Status:** ✅ Complete (commit 063b7e3, Nomad-verified + user all-clear 2026-08-22 — "it all
feels, looks, and works good", no findings)

**Outcome:** Split per the recipe — Fable wrote the pure core (`OutlineTree` with the local
`MAX_LEVEL = 6`, `ContentsLayout`, `ContentsSource` incl. the pure `items()` pass that drops
dead-page/malformed/blank-label rows), `SoilDao.liveHeadingsAll()` + the `FakeSoilDao` twin,
`PageGestures`' vertical rule (the flip's three constants against the screen height, judged at the
same `ACTION_UP`, `dy > 0` only), `ContentsFlow` (busy/available/showing, generation-counted
`refresh()`, pen-gated `releaseRender`, empty-gather self-refresh, `dismissIfShowing()`), and all
`NotebookActivity` wiring (construction off `displayedPageId`, the `pushExclusions` showing-branch
BLOCK_ALL, six refresh sites — `navigateTo`'s one line covers every flip/insert/delete/undo-redo —
and the `close()` dismiss). Opus wrote `ContentsDialog` + the three test suites (36 new tests);
Sonnet the resources (`ic_list`, `ic_minus`, both Contents layouts, active-entry/sidebar drawables,
strings — `ic_plus` reused for the toggle). Docs already updated: `docs/notebook.md` §Contents +
collaborators + gestures + **frame-silence exception #6**, app `CLAUDE.md` at six. **283 JVM tests**
(app 248 · api 6 · ext 29), debug + release build, debug installed on SNN. **Haiku walk 11/11 on
the first run, no tap-aim failures:** button gated by headings (present in Test 04, absent in
"abc"), swipe-down + button both open the 842 px sidebar, scrim dismiss, row-tap navigation
(4/4 → 1/4), toggle expand/collapse, flips unaffected, no-headings swipe silent, crash buffer
clean, cold restore clean. Gather 12–20 ms for 6 headings.

Pure core (`OutlineTree`, `ContentsLayout`, `ContentsSource.items`) + one cross-page DAO
query (`liveHeadingsAll()`, blob-free in effect — heading rows never set `blob`) +
`ContentsFlow` (busy/available/showing, generation-counted `refresh()`, pen-gated
`releaseRender`, empty-gather self-refresh race guard, `dismissIfShowing()` for `close()`)
+ `ContentsDialog` (the screen per the locked decisions) + `PageGestures.onSwipeDown` +
`btnContents` in `topBarRow` (`ContentsFlow` owns it — `NotebookToolbar`'s arming-only
charter untouched) + ~35 lines of `NotebookActivity` wiring (construction, gesture hook,
`pushExclusions` showing-branch, the refresh call sites, close hygiene).
**Gate:** JVM tests (~280 total: OutlineTree build/visible/highlight/ancestors/paging,
ContentsLayout width/items-per-page/indent, ContentsSource mapping/order/cap); debug +
release build; Haiku device walk on the Nomad (button gated by headings, swipe opens,
sidebar form, toggle expand/collapse, row-tap navigation, scrim dismiss, no-headings
silence, flips still work, crash buffer, cold restore); **user eye check** (swipe feel vs.
flip misfires, pen over the open Contents does not ink beneath while finger taps still
land, EPD render quality/ghosting, button appears/disappears with the last heading,
arrival page writes immediately, collapsed-open feel).
*Fable writes the pure core + DAO + gesture + wiring; Opus `ContentsFlow`/`ContentsDialog`
+ the JVM tests; Sonnet layouts/drawables/strings.*

**Questions to resolve at phase start:** all answered in the 2026-08-22 wizard (the locked
decisions above).

### C2 — Hardening, review, docs, freeze
**Status:** ✅ Complete (commit f4d2d8d, Nomad-verified + user all-clear 2026-08-22 — arc 4 frozen at this commit)

**Outcome:** **Review (`/code-review high` over `d84273e..HEAD`):**
8 finder angles → 24 candidates → 12 verified → 9 CONFIRMED + 1 PLAUSIBLE (2 refuted as unreachable:
the `showing`-mirror stuck-BLOCK_ALL and the `.also{}` stale-dialog-field candidates) — **all ten
fixed by Fable, none accepted**: ① a fired long-press never stood the touch sequence down, so the
continued drag's UP could fire a flip/swipe-down *under the delete sheet* and the pending confirm
would delete the wrong page → `cancelAll()` + `ignoreSequence` before `onDeleteRequested`;
② `ContentsFlow.refresh()`/`open()` rethrew any live-screen `Exception` into `lifecycleScope`
(no handler anywhere) — a transient SQLite fault on a routine flip became a process crash →
degrade with `Log.w` (refresh keeps the last answer; open shows nothing, next tap retries);
③ the gather runs outside `pageOps` and a mid-gather page op (escrowed undo, queued insert)
reindexes `session.pages`, so the row tap's snapshot *index* could land one page away → navigate
**by page id**, resolved at tap time under the lock via `refreshToPage` (`OutlineTree.Item`/`Node`
carry `pageId`; displayed numbers stay the modal snapshot's — display-only skew, documented);
④ the `onDestroy` fallback never dismissed the Contents dialog (config-change recreate, "don't
keep activities" → `WindowLeaked`) → `dismissIfShowing()` added — the pre-check gap, found
independently by Fable and two review angles; ⑤ `ACTION_POINTER_DOWN` committed an
already-qualifying flip but silently discarded an already-qualifying swipe-down (a trailing palm
is likeliest on exactly the downward drag) → symmetric vertical commit (`verticalQualifies`);
⑥ [PLAUSIBLE] `session.pages` had no happens-before edge to the mutex-bypassing IO readers
(unsafe publication under the JMM, not just staleness) → `@Volatile`; ⑦ `available()` was a
full-table `SELECT *` + full materialization on every flip → id-only `EXISTS` query
(`SoilDao.anyLiveHeadingOnLivePage`), exactness unchanged; ⑧ the dialog's third hand-rolled pager
→ `R.string.page_indicator` + `GridMath.pageCount`/`clampPage` (`OutlineTree.pageCount` delegates;
`GridMath` deliberately stays in `library/` — in-module import, not worth a package move);
⑨ the opening-highlight computation was degenerate (all-ids Set → vacuous `isVisible`, `find()`
re-scan, identical recompute at layout) → one `lastOrNull` + ancestor pre-expansion
(`OutlineTree.find` deleted with its only caller); ⑩ the dialog's post-show immersive block was a
line-for-line copy of `goImmersive()` → both windows through new `core/Immersive.apply`. Also
fixed in passing: the stale "heading extension" strings.xml comment (SN headings are core rows).
**283 JVM tests** (the `find` test replaced by a carried-page-id test; `FakeSoilDao` grew the
EXISTS twin), debug + release build, release hand-signed. **Docs:** `docs/notebook.md` §Contents
updated (EXISTS availability, id navigation + snapshot-skew note, onDestroy hygiene,
BLOCK_ALL-covers-in-dialog-repaints, a C2-hardening block); app `CLAUDE.md` unchanged (still six
frame-silence exceptions). **Regression (Nomad):** Haiku walk 14/16 pass; its two "failures" were
both false — the row-tap "failure" was **tap aim, the seventh occurrence of the R2 pattern**
(Fable re-drove by hand: Contents → "Working" row → lands 3/4 with the heading rendered), and the
long-press-guard step was an **invalid test by design** (`input swipe` moves from the first
millisecond, so the long-press correctly cancels on touch-slop — the hold-then-drag scenario is
uninjectable via adb and goes to the user eye check). Crash buffer clean throughout; cold restart
clean; both variants reinstalled current on SNN (0.1.0-ratta / 0.1.0-ratta-dev). Version stays
**0.1.0-ratta** (wizard). **User eye check all-pass 2026-08-22** (hold-then-drag guard, Contents
end-to-end, palm-graze swipe-down, write-after-jump general pass — "All is well", no findings) →
all-clear; arc 4 frozen.

`/code-review` over the arc range (level asked at phase start; N3 precedent high).
Pre-check list: the `refresh()` generation race; `showing` → `pushExclusions` ordering
(BLOCK_ALL before the first dialog frame); dialog leak on activity destroy; `itemsPerPage`
measured before the first render; a gather racing `close()`; a stale `session.pages`
snapshot vs. a mid-gather page op. Fix or explicitly accept each finding (accepted →
monorepo `BACKLOG.md`). Docs: `docs/notebook.md` §Contents (+ collaborators table, gestures
table, frame-silence ledger → six) with the deliberate-differences and the block-all
asymmetry note; app `CLAUDE.md` frame-silence count; this file's outcomes. Version stamp
decision; full regression (Haiku walk + short user checklist); commit + push; arc freeze.
**Gate:** everything green or explicitly accepted; user all-clear.

**Questions to resolve at phase start:** review level; version stamp.
**Answered 2026-08-22:** review at **high** (R6/N3 precedent); version **stays 0.1.0-ratta**.

---

## Phases — Arc 5 "Naming" (planned 2026-08-22, wizard complete)

Custom notebook name schemes as a **first-class core feature** — Paper's arc-2 Naming extension
(`PAPER_NAMING_PLAN.md`, `docs/extensions.md` §"The Naming extension" + §"NotebookNamer — host
behaviour") baked into `:app`, the arc-4 precedent: the extension's whole provider layer (AIDL,
store, NamerClient, discovery) vanishes; the scheme language and the library UX carry over.
Reading references only — no code copied. Two phases (user-directed): S1 implementation, S2 review.

### Locked decisions (arc-5 wizard 2026-08-22 — do not re-ask)

| Decision | Answer |
|---|---|
| Storage | **Additive index row type `TYPE_NAMING = "naming"`** in the `objects` table — no schema change, no Room-hash change (the proven additive-row pattern; Paper filters listings by type so the rows are invisible to it). One row per folder: `parentId` = folder id (**null = the library root**), `name` = the scheme text, everything else defaulted. Set = upsert in place (same row id, `deletedAt` cleared); clear (blank save) = soft delete. `deleteFolderRecursive` soft-deletes each folder's naming row in the same transaction. |
| Root scheme | **Yes** — the root can hold a scheme (the `parentId = null` naming row). |
| Scheme language | **v2 = Paper's v1 + date-part/name tokens.** v1 verbatim: literal text (core name charset), `{date}` `yyyyMMdd`, `{time}` `HHmmss`, `{n}` / `{n:K}` (K 1–9, at most once), 100-char cap incl. worst-case expansion, `{n}` = 1 + highest sibling match against the scheme's anchored skeleton regex (date/time positions are wildcards — the counter runs across days; zero-padded to K, never truncated). **New tokens:** `{year}` (`yyyy`) · `{month}` (`MM`) · `{day}` (`dd`) · `{monthname}` (August) · `{weekday}` (Saturday) · `{mon}` (Aug) · `{wd}` (Sat) — English/`Locale.ROOT`, charset-safe; skeleton wildcards = fixed digit widths / alternations of the 12 or 7 names. Time parts (`{hour}` etc.) **declined**. |
| Entry points | All three of Paper's, plus one: ① the New-folder dialog gains the scheme field (validated **before** the folder exists; folder created → then scheme saved); ② folder long-press sheet gains "Default notebook name…"; ③ +Notebook opens pre-named via `EXTRA_DEFAULT_NAME` (still editable, timestamp default when no scheme resolves); ④ **breadcrumb long-press** (any crumb **including the root**) opens the same scheme dialog for that crumb's folder — the root's only entry point. |
| Inheritance | **Nearest-ancestor fallback**: creation folder → … → root, first folder with a scheme wins; none → core timestamp default. `{n}` always counts siblings (alive notebook names) in the **creation folder**, never the scheme-holding ancestor's. |
| Failure rule | Paper's: naming never blocks what the user chose. An unresolvable or unparseable stored scheme → core default silently (`Log.w`, C2's degrade-not-throw rule); scheme validation/save failures → problem dialogs that keep the user's text (dialog-explains rule). |

### S1 — Scheme engine + storage + library UX (end to end)
**Status:** ✅ Complete (commit f475b66, Nomad-verified + user all-clear 2026-08-22)

`library/SchemeEngine.kt` (pure Kotlin, fresh-coded — Paper's `SchemeEngine` + its 18 tests are
the reference, extended with the v2 tokens), `ObjectType.NAMING` + DAO queries + `IndexRepository`
scheme methods (`schemeFor` / `resolveScheme` ancestor walk on the existing cycle-guarded
`ancestry` / `setScheme` upsert-in-place / `clearScheme` soft delete + the `deleteFolderRecursive`
sweep), `SchemeDialog` (one text field, prefill, token help line, blank save = clear),
`LibraryActivity` wiring (New-folder dialog field, folder sheet row, breadcrumb long-press incl.
root, +Notebook resolve-then-launch), `NewNotebookActivity.EXTRA_DEFAULT_NAME`.
**Gate:** JVM tests green (SchemeEngine ported case-for-case + v2-token cases); debug + release
build; Haiku device walk (dialogs/fields present via uiautomator, no-scheme prefill = timestamp,
crash buffer — typed-scheme flows are the user's, IME swallow trap); **user eye check** (set a
scheme, create pre-named notebooks, {n} counting, inheritance, root scheme, clear).
*Opus implements against Fable's contract; Fable reviews.*

**Questions to resolve at phase start:** none — wizard above complete.

**Outcome:** Opus implemented the whole phase in one background agent against Fable's contract;
Fable review clean. New: `library/SchemeEngine.kt` (v2 language, 11 `Part` kinds, expansion-counted
100 cap, anchored-skeleton `{n}` with non-capturing name alternations so the counter stays group 1),
`library/SchemeDialog.kt` (`buildField` shared with the New-folder dialog; `open` reads then shows —
a read failure explains itself and opens nothing; blank save = clear; positive button wired after
`show()`), `ic_cursor_text`, 29 JVM tests. Changed: `ObjectType.NAMING` + `namingRowAny` (includes
soft-deleted — set revives the same row, never a second) + repository `scheme`/`setScheme`/
`clearScheme`/`resolveScheme` (nearest-ancestor over reversed `ancestry`, then the root) +
`deleteFolderRecursive` naming-row sweep in the same transaction; `LibraryActivity` (sheet row,
breadcrumb long-press incl. root, latched `launchNewNotebook` with a stale-folder drop,
name→scheme→duplicate→create→save-scheme order in the New-folder accept); `NameDialog` optional
second field; `NewNotebookActivity.EXTRA_DEFAULT_NAME`. **Three deliberate deviations:**
① **`Locale.US`, not `Locale.ROOT`, for formatting** — CLDR's root locale renders `MMMM`/`EEEE` as
the abbreviated forms, so `{monthname}` would equal `{mon}` (the tests caught it; reason KDoc'd on
`FORMAT_LOCALE`); ② three failure strings, not one (folder-created-but-scheme-not,
standalone save, standalone read — one wording would read wrongly in two of the three places);
③ `SchemeDialog.open` does its own current-scheme read so all three entry points share one call
site. **312 JVM tests** (283 + 29), debug + release build, Room identity hash confirmed unchanged
(`cd6b2701…`). **Haiku walk 8/8 on the first run, zero tap-aim false failures** (sheet row, both
scheme dialogs incl. the root crumb's, timestamp prefill with no scheme, New-folder scheme section,
crash buffer clean). **User eye check all-pass 2026-08-22** (set/reopen scheme, `{n}` counting,
v2 tokens, inheritance, root scheme, create-with-scheme, bad-scheme dialog keeps text, clear) →
all-clear. Version stays 0.1.0-ratta. Both variants reinstalled current on SNN.

### S2 — Review + hardening + docs
**Status:** ✅ Complete (commit 2fc5635, Nomad-verified + user all-clear 2026-08-23)

Arc-range `/code-review` (level asked at phase start), findings fixed or explicitly accepted →
`BACKLOG.md`; docs (`docs/library.md` §Naming section); app `CLAUDE.md` touch-ups if any; memory +
this file's outcomes; version-stamp decision; regression (Haiku walk + short user checklist);
commit + push; arc freeze.

**Questions to resolve at phase start:** review level; version stamp.
**Answered 2026-08-22:** review at **high** (the level every prior arc freeze used);
version stays **0.1.0-ratta** (the version is the experiment's, not per-arc).

**Outcome:** Arc-range `/code-review high` (acf04fd..HEAD): 23 raw candidates → **10 findings — 9
fixed by Fable, 1 accepted** → monorepo `BACKLOG.md`. Correctness: ① the New-folder OK had no
re-entry guard (an e-ink double-tap runs two creates whose duplicate checks both read before
either insert — two identically named folders, each saving its own naming row) → closure-scoped
`accepting` guard armed across the coroutine, rename given the same guard; ② a counter that
outgrows its declared width expands past the 100-char cap and the `NameRules.isValid` backstop is
length-blind → the backstop now also checks the cap; over-cap degrades to the timestamp default
(never truncated). Cleanups: **one shared `launching` latch** for both library doors (two per-door
flags let a card tap + a + tap stack two screens in the feedback gap); `SchemeEngine` validates
against `NameRules.CHARSET`/`NameRules.validate` (the charset is written once); the month/weekday
names are emitted **from the hand lists via Calendar indices, never a formatter** — CLDR drift
(the en_GB "Sep"→"Sept" family) can no longer stall counters, pinned by a new 12-month + 7-weekday
JVM test; `deleteFolderRecursive` calls `clearScheme` instead of an inline copy;
`NameDialog`'s three sentinel res-int params → an optional caller-built `extraField`
(callback back to `(name, dismiss)`); one shared `NameDialog.input` bordered-field recipe;
siblings fetched only when the parsed scheme actually holds a counter (`hasCounter` +
`expand(parts, …)` overload). Accepted: `resolveScheme`'s N+1 ancestry walk (single-digit ms at
realistic depths, R6-perf-niggle family; the one-query fix is recorded in BACKLOG.md).
Refuted-but-noted there too: naming-row UNIQUE hardening (race unreachable via the modal
click-guarded UI; an index would touch the Room-validated schema — the Paper format contract).
**One S2 regression, user-caught in the eye check:** the merged latch silently dropped the open
of a just-created notebook — the ActivityResult callback runs *before* `onResume`'s reset, so
`openNotebook` saw the latch still armed from the + tap. Fixed by releasing the latch at the top
of the callback (the round-trip it guards is over); adb-verified end to end: create → auto-open,
double-tap latch still one instance, cross-door latch still one screen. **313 JVM tests** (312 +
the single-authority pin), debug + release build. **Haiku walk 11/11 on the first run, zero
tap-aim false failures** (both scheme dialogs incl. the root crumb's, New-folder scheme field,
timestamp prefill, create→open, both latches, delete, crash buffer, cold restart). Docs:
`docs/library.md` gains § "Name schemes (arc 5)" + the `SchemeEngineTest` tests-table row;
BACKLOG.md arc-5 section; app `CLAUDE.md` unchanged (no new rules, no new frame-silence
exceptions). **User eye check all-pass 2026-08-23** (v2 tokens, `{n}` counting, double-tap OK
guard, clear; the create-then-open fix confirmed live). Version stays **0.1.0-ratta**; arc 5
frozen. Both variants reinstalled current on SNN.

---

## Phases — Arc 6 "Links" (planned 2026-08-23, wizard complete)

Link objects **baked into the core, og style** — wrap a lasso selection into a tappable
navigation object pointing at a page in this notebook, another notebook, or another
notebook's page. og (`docs/links.md` at the monorepo root) and Paper's arc 7
(`apps/notesprout_paper/PAPER_LINKS_PLAN.md`, `apps/notesprout_paper/docs/links.md`) are
reading references — no code copied; Paper's whole extension layer (NSE · Links AIDL,
opaque payload ownership, LinkCatalogBinder, ext store trail) vanishes, the arc-4/5
precedent. Two og features Paper skipped come back: **page previews** in the picker and the
**heading-as-page-name** rule. Arc-5 naming plugs in: picker-created notebooks are
scheme-prenamed, picker-created folders take name + scheme like the library. g-paper stays
at **0.1.4** — its links-era API (public `StrokeRasterizer`, eraser content hits,
`ContentRenderer` hit targets + live-drag pair) was built for exactly this; gaps, if found,
are fixed in g-paper per the standing rule.

### Locked decisions (arc-6 wizard 2026-08-23 — do not re-ask)

| Decision | Answer |
|---|---|
| Wrap model | **Re-parent** (Paper L1): the selected strokes/headings get `parentId = link.id` and stay live rows — no id churn, no embedded copies. Unlink = re-parent back to the page (undoable). Page delete/reconcile cascades **grandchildren** (`deepChildIds`). |
| Storage | Additive row type `TYPE_LINK = "link"` in the universal `notebook` table — no version bump, no Room-hash change. **Payload = Paper's exact v1 grammar in `text`** (`L1\|chrome\|kind\|nb\|pg` — K1 verifies the byte-exact spec against `PAPER_LINKS_PLAN.md` before coding) so link rows stay family-compatible: SN reads a Paper-created link's chrome/target (cross-app ids simply resolve dead → the dead-target rule). `flags` stays null (no chrome cache — parse at load, session map); `style` written null, read leniently (Paper put its provider id there). |
| Chrome | **Per-link, UNDERLINE default**, underline/none only (og's dotted-chevron excluded). Style toggle in the picker, editable via Edit. 1 dp inkBlack line under the union bbox, drawn **live** by the link renderer, never baked. |
| Picker modes | og/Paper trio: **This notebook · Notebook · Notebook page**. Current page excluded from the This-notebook grid; current notebook hidden in both Notebook modes. Paged card grid (library `GridMath`), no search (deferred, as in Paper → BACKLOG). |
| Page previews | **Both page grids** (og feature, Paper skipped): stroke + heading thumbnails rendered async per grid page (placeholder card while rendering), current notebook from the live session (drain first), foreign notebooks via a **read-only** `SoilCrypto` open (global key) + `StrokeRasterizer` + the markdown draw for headings. Notebook cards keep cover snapshots. |
| Page names | **Heading-as-page-name**: a page's card label = "n · <topmost heading>" (topmost by `(y, x)`, `stripHeadingPrefix`), plain "Page n" when headingless. Picker grids only — SN has no other page index. |
| Create-in-picker | **Full og/Paper-L3 parity + naming schemes.** New page in both page grids (selected anchor → Insert before/after sheet, else append; template inherited; auto-selected as target; **no undo** for picker creations — og rule). New notebook = **the real `NewNotebookActivity`** launched from the picker, `EXTRA_DEFAULT_NAME` prefilled via `resolveScheme` on the destination folder; on return auto-selected (Notebook mode) or drilled into (Page mode). New folder = the library's New-folder dialog shape (**name + scheme fields**, S2's `NameDialog.extraField`), scheme saved after create, navigate in. |
| Follow | **Finger-only tap, never stylus** (og/Paper). Whole-link tappable via bbox; tap on a *selected* link = no-op. Same-notebook → direct navigate; cross-notebook → seal → relaunch with `EXTRA_VIA_LINK` (+`EXTRA_INITIAL_PAGE_ID`), **"Opening…" overlay at tap time** (og pattern, `OpeningOverlay.showThen`). Whole-notebook target opens at its remembered page (`refId`). |
| Trail | **Persisted** (SN prefs — no ext store), cap 50, cleared on any fresh non-via-link open, dead entries skipped **silently** on the way back. **Swipe-up = walk back** (the user-specified gesture); in a via-link notebook **both Backs walk the trail too** (Paper L4). |
| Dead target at tap | **Problem dialog + an "Edit link…" button** that opens the picker prefilled to retarget on the spot (notebook-gone and page-gone get their own wording). Link row untouched otherwise. |
| Ops | **Full Paper set**: selection toolbar grows **Link** (pure stroke/heading selection — hidden when the selection contains a link), **Edit + Unlink** (single-link selection). Eraser erases **whole links** (scribble-erase immune — Paper L1 user call). Lasso move/delete first-class. Undo: `LinkCreated` / `LinkUnlinked` / `LinkEdited` + `Moved`/`Deleted` carrying `linkIds`. |
| Arc shape | **Five phases**: K1 core rows/render/ops · K2 picker (existing targets) · K3 create-in-picker · K4 follow + trail · K5 review/freeze. |

### Arc-6 standing traps (inherited from og + Paper's L arcs — assume they apply)

- **The hover repaint trap (Paper L4's one field finding):** a freshly created link's chrome
  stayed invisible while the pen hovered because the composite repaint was `whenPenIdle`-gated
  and EMR hover held it back. Build/refresh link render content **before** the page-load
  frame, never behind an idle gate.
- **Seal/reopen race** on fast A→B→swipe-up: `close()` must await the seal before the relaunch
  (`close(andThen)` starts the next Activity strictly after the seal — Paper L4's shape).
- A recreated via-link notebook **re-applies `EXTRA_INITIAL_PAGE_ID`** from the redelivered
  Intent (Paper accepted this; K4 decides handle-or-record).
- **ActivityResult callback runs BEFORE `onResume`** (the S2 latch regression) — every latch
  around the picker/new-notebook round-trips must release at the top of the result callback.
- A picker page-create in the **current** notebook invalidates old `Structural` undo snapshots
  → clear the undo stack on that return (Paper L3's recorded consequence).
- adb cannot inject stylus ink or lasso — link creation and lasso ops on device are the
  user's; finger `input tap` / `input swipe` DO work, so K4's follow + swipe-up walks are
  agent-injectable once links exist.
- One `writer.drain()` before any gather/raster of the current notebook (shared `SoilWriter`).
- zipflinger inflates incremental debug APKs — clean build before chasing size.

### K1 — Core link rows + render + ops
**Status:** ✅ Complete (commit ada8f09, Nomad-verified + user all-clear 2026-08-23)

**Outcome:** Split per the recipe. Fable wrote the data/render/undo layer: `LinkPayload` in core
(Paper's v1 grammar **byte-verified** against `PAPER_LINKS_PLAN.md` + its extension's codec — the
JVM fixtures pin the exact strings; decode-never-throws, cap 2000, `chromeOf` degrades unusable →
NONE), `PageLink`/`LinkRows` (locked deltas: `style` written null + read leniently — a
Paper-provider identity decodes fine; chrome parsed from the payload at load, held on the object,
`flags` stays null), `LinkStore` (re-parent wrap/unlink/relink/remove/restore/move on the shared
serial `SoilWriter`, each multi-row op in one Room transaction via an **injected `transact`
lambda** so the store JVM-tests against `FakeSoilDao`; stroke children re-encode on move, heading
children + link row shift via the new `SoilDao.moveBy`), DAO additions
(`linksOf`/`reparent`/`liveDescendantIds` — Paper's grandchild query with `'heading'` for
`'object'` — `moveBy`), `TYPE_LINK` schema doc, session `links` store +
`deleteCurrent`/reconcile carrying **deep** descendants, undo actions
`LinkCreated`/`LinkUnlinked`/`LinkEdited` (+`Deleted.links` snapshots / `Moved.linkIds`) with all
replay arms, and `LinkComposite`/`LinkRenderer` (composite bitmap per link — strokes via g-paper's
`StrokeRasterizer`, headings via the shared `HeadingRenderer.drawHeading` recipe; cache reused
when the drawable size is unchanged so a **move never rebuilds**; 1 dp underline drawn live, never
baked; `update()` runs **before `loadStrokes`** at both page-load sites — the hover-repaint trap).
Opus wired the screen: `SelectionMode` → five modes (LINK / MIXED_WITH_LINK enforce no-nesting),
bar order Delete · H · Link · Edit · Unlink · flask (Link/Edit inert until K2, visibility set
before the measure), `createLinkFromSelection` (capture discipline from the heading convert,
no-nesting re-check at use time, one frame, `pendingSelection` **generalized from `Heading?` to a
select-successor lambda** so the smart-lasso session survives a wrap exactly as it does a
conversion), `selectAsLink`, `unlinkSelection` (store → record → drain → `refreshToPage` — the
reload is the sync), eraser `onContentErased` splitting headings/links (**one
`Action.Deleted` covering both when a link is involved**; heading-only keeps `HeadingDeleted`),
`deleteSelection`/`onSelectionMoved` extended, and `debugCreateTestLink` (insert-without-navigate
when on the last page: `insertBlank` + `Action.Page` + `goTo(here)`, never `navigateTo`). Sonnet:
`ic_link`/`ic_link_off`/`ic_flask` + 4 strings. **Deliberate deviations:** no `syncLinkRenderer`
helper (every K1 link mutation shares its frame with other content or replays through a reload —
recorded in the code; K2's payload edit adds it); the debug flask lives **on the selection
toolbar** (it acts on a selection — the N3-retired ⋯ pattern would have to re-arm one) and is not
built at all in release; wrapped headings are **not** `remeasureForDevice`d (baked in the
composite at authored size — KDoc'd) and leave the Contents while wrapped (their parent is the
link). **344 JVM tests** (app 309 · api 6 · ext 29 — 31 new: LinkPayload 13 incl. Paper-grammar
fixtures, LinkRows/unionBounds 9, LinkStore 9), debug + release build. **Haiku walk 8/8 on the
first run** (launch/restore/flip/Contents-gating/cold-restore/crash-buffer; its "diamond/comment"
toolbar description was the usual icon misread — Fable re-verified the chrome by hand screencap:
back · pen · eraser · lasso, correct). **User eye check #6 all-pass 2026-08-23, no findings**
(wrap ink + heading + mixed, pixel-identical re-render + underline, no-nesting bar, live drag,
unlink + undo re-wrap, whole-link erase with scribble-immunity, delete + undo, persistence across
close/reopen + cold restore, ink-over-link on top). Both variants reinstalled current on SNN.

`SoilSchema.TYPE_LINK` + payload codec (Paper's v1 grammar, byte-verified against
`PAPER_LINKS_PLAN.md`), `PageLink`/`LinkRows`, `LinkStore` on the session's shared serial
`SoilWriter` (wrap = link row + child re-parent in one transaction; unlink = re-parent back;
`deepChildIds` cascade for page delete/reconcile), link renderer as a g-paper
`ContentRenderer` (children composite: `StrokeRasterizer` for stroke children + the N1
markdown draw for heading children, 1 dp underline chrome from the session chrome map,
hit targets, live-drag pair — below top-level strokes, og order), selection-toolbar Link /
Edit / Unlink buttons (Link + Edit **inert until K2**, Unlink live), eraser whole-link via
`onContentErased`, lasso move/delete with `linkIds` on `Moved`/`Deleted`, undo actions
`LinkCreated`/`LinkUnlinked` (+`LinkEdited` contract, exercised in K2), **temporary
debug-only "Create test link" button** (removed in K5) to exercise everything pre-picker.
**Gate:** JVM tests (payload codec round-trip incl. Paper-grammar fixtures, LinkRows,
wrap/unwrap transactions, undo replay, deepChildIds); debug + release build; Haiku device
walk (test-link render in screencap, persistence across close/reopen + cold restart, crash
buffer); **user eye check** (wrap a real selection via debug create, underline render, drag,
whole-link erase, unlink, undo/redo of everything).
*Fable writes the payload codec + store + renderer seam + undo contracts; Opus the flows and
NotebookActivity wiring; Sonnet icons/strings.*

**Questions to resolve at phase start:** link z-order confirmation (below strokes, og order —
or above?); whether a link may wrap another link (Paper: selection containing a link hides
the Link action — recommend the same); debug create-test-link's target (next page vs. fixed).
**Answered 2026-08-23:** links composite **below top-level strokes** (og order, headings'
slot); **no nesting** — a selection containing any link hides the Link action (Paper's
rule); debug create-test-link targets the **next page** in this notebook (inserting one when
the notebook has a single page — a real, followable page-kind target for K4).

### K2 — Picker (existing targets) + previews + heading page names
**Status:** ✅ Complete (commit 8a55461, Nomad-verified + user all-clear 2026-08-23)

**Outcome:** Split per the recipe, with Sonnet's share folded into Opus (one screen, resources
inseparable — recorded deviation). Fable wrote the support seam + host wiring: `PageLabels`
(topmost-by-`(y,x)` bare title; **loose headings only** — a wrapped heading names nothing, the K1
Contents rule applied to labels), `PreviewMath` (page aspect clamped 0.5–3 against untrusted
foreign dims, 1024 px render cap), `PageReads`/`PickerPage`/`PageContent` (one read-only gather
over any `SoilDao`, wrapped content inside its `PageLink`), `PagePreview` (StrokeRasterizer + the
shared `drawHeading` recipe, off-Main-safe), `PickerPageSource` + `LinkPickerRelay` (the
transfer-singleton hand-off — the live session serves the current notebook, **its `.soil` is never
opened twice**; nothing but the edit-prefill ids rides the Intent), `ForeignPageSource` (lazy
read-only `SoilDatabase.open` under the global key, `sealAsync` on a process-scoped NonCancellable
IO job — a destroy-time seal always completes), `LinkPickFlow` (launcher + one-door latch released
at the **top** of the result callback — the S2 trap; capture-at-launch for create and edit;
unchanged-payload edit = no-op; honest `link_result_lost` dialog when a host rebuild lost the
capture), and the `NotebookActivity` wiring (`beginLinkPick`/`beginLinkEdit`, `applyLinkEdit` =
store + working copy + the K1-promised `syncLinkRenderer` + `Action.LinkEdited` + re-select).
Opus built the screen: `LinkPickerActivity` + pure `LinkPickerModel` (mode↔kind, `chromeFor`,
**exclusion-beside-numbering** `pageCards`, `gridPageOf` pager jump, `composeOk` incl. the
self-target refusal) + `PageCardGrid` (LibraryGrid's geometry for page cards) + layout/strings.
**Deliberate deviations, all reviewed clean:** `bg_selectable_card` selector (1 dp → 3 dp border on
`state_selected`; unselected byte-identical to `shape_bordered`, so library/move-picker cards are
unchanged) + `LibraryGrid.bind` optional `selectedId`; `Widget.Notesprout.LatchButton` for the mode
trio + Style pair; the per-showing preview cache holds `(bitmap, title)` and is **bounded** (~3
grid pages' worth, dropped whole — not an LRU) and cleared on drill-exit; browse cards keep pin
badges; a style-only Edit of a link whose target page has died correctly **keeps the dead target**
(the honest dialog is K4's follow, per the locked dead-target rule); picker `exported="false"`, so
an external `am start` is refused by Android before the relay-null guard. **384 JVM tests** (app
349 · api 6 · ext 29 — 40 new: LinkPickerModel 24, PageLabels 5, PreviewMath 5, PageReads 3,
LinkComposite 3), debug + release build. **Haiku walk 7/7 on the first run** (verified-foreground
launch, flips 1/2↔2/2, picker `am start` refused + no crash, cold restore, back-to-library, crash
buffer clean). **Drivable-half correction (Paper L2's wall, now recorded):** the picker opens only
from a lasso selection, which adb cannot inject — the picker walk itself is the user's eye check;
agents cover install/regression/the refusal probe. **Eye check #7: two findings, both fixed +
screencap-diff-verified on device:** ① the preview band's bordered background was overpainted by
the fit-centred page bitmap (read as top/bottom "clipping") → the 1 px border is now drawn **on the
preview bitmap** and the band has no background; ② the K1 composite sheared wrapped strokes at the
link bounds — g-paper's `Stroke.bounds` is **point-tight** (no stroke width), so rendered ink
overhangs by width/2 + cap → `LinkComposite.padOf/sizeOf` render a stroke-width margin and
`LinkRenderer` draws at `(x − pad, y − pad)`; existing rows heal with no bounds/hit-target/
underline change (pixel diff: 59 px of ink restored, 0 removed, across every stroke edge). User
all-clear 2026-08-23. Test data: links live on "August Sun 1" (folder `Journal`). Both variants
reinstalled current on SNN.

`LinkPickerActivity` (in-app, `IndexGuard`, portrait, e-ink chrome): mode trio, Style toggle
(underline/none), paged card grids on `GridMath`, current-page/current-notebook exclusions,
**page previews in both grids** (async raster + placeholder; foreign `.soil` read-only open;
per-showing cache), **"n · heading" page labels** (topmost by `(y, x)`), edit prefill
(`EXTRA_INITIAL_*`), OK → create (`LinkCreated`) or edit (`LinkEdited`) in the host; Link +
Edit buttons go live. Reload-preserving-current on any picker return.
**Gate:** JVM tests (label composition, preview sizing math, payload build from picker
results, exclusion rules); debug + release build; Haiku device walk (modes, exclusions,
previews visible in screencap, labels, style toggle, cancel/OK returns); **user eye check**
(create + edit real links end to end, preview fidelity, picker feel).
*Fable writes the preview raster seam + host create/edit wiring; Opus the picker screen;
Sonnet layouts/icons/strings.*

**Questions to resolve at phase start:** preview card size/aspect (page aspect at grid-cell
width?); preview cache lifetime (per-showing vs. per-session); whether the This-notebook
grid also labels the (excluded) current page's neighbours by heading — trivially yes, ask
only if a conflict surfaces.
**Answered 2026-08-23:** preview cards keep the **real page aspect at grid-cell width**
(undistorted, library-card footprint, `GridMath` unchanged); preview cache is
**per-showing** (lives while the picker is open, dropped on finish — always fresh, no
staleness machinery); heading labels apply to every page card (no conflict surfaced).

### K3 — Create-in-picker (+ naming schemes)
**Status:** ✅ Complete (commit f116d38, Nomad-verified + user all-clear 2026-08-23)

**Outcome:** Opus implemented against Fable's written contract (the recipe's K3 split); Fable
reviewed the diff — no blocking findings, all three of the agent's flagged concerns resolved
(the >800-line reason already lives in `NotebookActivity`'s KDoc per N3; index-mirror-after-
transaction and the foreign WAL-on-process-death both match `insertBlank`/seal-contract shapes).
No phase-start questions: `NewNotebookActivity`'s existing result contract fit unchanged.
Shape: **`SchemePrefill`** (new, pure — the scheme→prefill rules extracted from the library's
`resolveAndExpand`, lazy sibling fetch only when the scheme holds a counter, null for anything
the library would refuse; shared verbatim by +Notebook and the picker) and **`NewFolderFlow`**
(the whole New-folder dialog extracted — name + scheme fields, identical validation order,
same `accepting` guard; library delegates with `{ refresh() }`, picker with navigate-in).
`LinkPickerModel` gains the pure placement trio (`insertIndexFor` — vanished anchor appends,
never redirects to its old slot; `inheritIndexFor` — anchor else last; `createButtons` — page
grid ⇄ browse, never both). `NotebookSession.insertAt` inserts **without navigating**
(`currentIndex` re-anchored **by id**, no template load, no undo entry); the relay's `Showing`
carries a `createPage` lambda armed by `LinkPickFlow`, which runs the host's
`pickerCreatePage` under the page-op lock and flags `pagesChanged` — the result callback fires
`onPagesChanged` (host: `undo.clear()` + indicator + Contents refresh) **before the RESULT_OK
check and before `applyCreate`**, so a cancel still clears the stale `Structural` snapshots
and the new link's `LinkCreated` survives the clear. `ForeignPageSource.createPage` is that
open's **one sanctioned write** (inside the `withDb` seal lock, one transaction, index
mirrored; KDoc amended to "near-read-only"). Picker screen: three `TextButton`s at the left of
the style row, visibility from `createButtons` (GONE, never disabled); anchor →
"Insert before / Insert after" `ActionSheetDialog`, else silent append; created page
auto-selected + grid jump; New notebook = the real screen, prefill via `SchemePrefill` with
the library's stale-folder drop, latch released at the TOP of the result callback (S2), result
auto-selects (Notebook mode) or drills in (Page mode); New folder navigates in. **403 JVM
tests** (app 368 · api 6 · ext 29 — 19 new: LinkPickerModel 11, SchemePrefill 8), debug +
release build, both variants reinstalled on SNN. **Haiku walk 10/10 first run** (launch,
Journal folder, +Notebook prefilled **"August Sun 3"** — the scheme resolving live through
`SchemePrefill`, New-folder two-field dialog with token help, notebook open, flips, picker
`am start` refusal, cold restore, crash buffer clean; zero tap-aim failures). **User eye
check #8 all-pass 2026-08-23, no findings** (anchored + appended page creates in both grids,
undo-stack clear with the link still undoable, scheme-prenamed notebook in both browse modes,
drill-in on Page-mode create, folder create + navigate-in + scheme pickup, cancel keeps the
created page, foreign create verified in the target notebook).

New page in both page grids (anchor → Insert before/after `ActionSheetDialog`, else append;
template inherited from anchor/last page; auto-selected; current-notebook insert without
navigating + undo-stack clear on return); New notebook = real `NewNotebookActivity` from the
picker (`EXTRA_DEFAULT_NAME` via `resolveScheme` on the browse folder, result → auto-select
or drill); New folder = name + scheme dialog (S2 `NameDialog.extraField` recipe, library
validation + duplicate check, `createFolder` + `setScheme`, navigate in). No undo for
picker creations. All round-trip latches release in the result callback (S2 trap).
**Gate:** JVM tests (anchor/append placement, template inheritance, scheme resolution for
the picker path); debug + release build; Haiku device walk (new-page sheet + placement,
new-notebook screen opens scheme-prenamed, new-folder dialog shape — typed text is the
user's, IME trap); **user eye check** (create page/notebook/folder as targets, scheme
prenaming correctness incl. `{n}`, link to each created target).
*Opus implements against Fable's contract; Fable reviews.*

**Questions to resolve at phase start:** none expected — og/Paper-L3 rules + arc-5 wiring
locked above; ask only if `NewNotebookActivity`'s result contract needs widening.

### K4 — Follow + trail
**Status:** ✅ Complete (commit aff9390, Nomad-verified + user all-clear 2026-08-23)

**Outcome:** Split per the recipe — Fable wrote `PageGestures` tap (inverse
recogniser: sub-slop, under the long-press timeout, single-finger, pen-tail escrow, reports the
down point) + swipe-up (one sign-routed vertical evaluation — exclusive with the Contents
swipe-down by construction), pure `LinkNav`, and the `NotebookActivity` surgery (`EXTRA_VIA_LINK`
/ `EXTRA_INITIAL_PAGE_ID` with the **consumed-once** recreate fix — the initial page is read only
when `savedInstanceState == null`; both Backs funnel through `backPressed()`; `close(andThen)`
launches strictly after the seal; fresh non-via-link open clears the trail) plus
`BrowseState.lastOpenViaLink` so a cold restore reopens a via-link notebook *as* via-link (the
trail survives a mid-chain force-stop — without it the restore would read as a fresh open and
clear it). Opus (one background agent) wrote `LinkTrail`/`TrailCodec` (prefs `sn_trail`, ids only,
cap 50 enforced on read too — untrusted input), `LinkFollowFlow` (topmost-last hit-test; validate
before navigating incl. a one-shot read-only `foreignPageAlive` pre-check sealed in `finally`;
every follow pushes the origin; walk-back skips dead entries silently, loop bounded by the cap;
busy door kept set forever on a leave-the-screen hop; dead-target dialog = two buttons, "Edit
link" opens the picker prefilled to retarget, link row untouched) + 4 strings + 18 JVM tests.
Agent deviations, all accepted: reused the existing `link_edit_action` "Edit link" (3 call sites);
`notebookSummary` additionally requires `type == NOTEBOOK` (a folder id in an untrusted payload
must not launch a notebook screen). No new frame-silence exception: both entry points are finger
gestures behind `PageGestures`' pen gate. **421 JVM tests** (app 386 — LinkNav 10 + TrailCodec 8
new), debug + release built + installed on SNN. **Device walk: the Haiku agent stalled on the
recorded tap-aim pattern** (reported the Journal folder missing; it was on the root's first grid
page) — **Fable re-drove the full walk by hand, all pass**: cross-notebook follow off "August Sun
1" p3 → "Links 1" p2/2 (seal → relaunch), swipe-up back to the exact origin, toolbar-Back walks
the trail too, follow → `am force-stop` → relaunch restores into Links 1 at its remembered page
(no Intent re-application) **and** swipe-up still walks back (trail + via-link flag survived),
empty-trail Back closes to the library, fresh open clears (swipe-up silent), flips + Contents
swipe-down regression clean, crash buffer empty. **User eye check #9 all-pass 2026-08-23, no
findings** (pen-over-link never follows + finger tap does, same-notebook follow + return, the
A→B→C chain with swipe-up twice + tap-time "Opening…", no accidental follows while writing,
dead-target dialog + Edit-link retarget on the spot, dead trail entry skipped silently).

`PageGestures.onFingerTap` (escrowed inverse recogniser, finger-only) + `onSwipeUp` (the
flip's constants against screen height, `dy < 0`, judged at the same `ACTION_UP` — mutually
exclusive with the Contents swipe-down); pure `LinkNav` planner (`planFollow`/`planBack`);
`LinkTrail` prefs store (cap 50, cleared on fresh non-via-link open, dead entries skipped
silently); follow = same-notebook `navigateTo` vs. cross-notebook seal → `close(andThen)` →
`EXTRA_VIA_LINK` + `EXTRA_INITIAL_PAGE_ID` + tap-time "Opening…" overlay; whole-notebook
target honours `refId`; **both Backs walk the trail in via-link notebooks**; dead-target
dialog with **Edit link…** button (picker prefilled); every follow pushes the origin.
**Gate:** JVM tests (`LinkNav`, trail cap/clear/skip); debug + release build; Haiku device
walk — **follows and swipe-up are finger-injectable**: real follow chains A→B→C, swipe-up
walk-back, Back parity, trail survives force-stop, fresh open clears, dead-target dialog
after deleting a target page, crash buffer; **user eye check** (tap feel vs. accidental
follows while writing, swipe-up feel vs. flip misfires, Opening overlay, dead-target
dialog wording).
*Fable writes gestures + LinkNav + navigation surgery (close/seal ordering); Opus trail
store + dialogs + wiring; Sonnet strings.*

**Questions to resolve at phase start:** trail-push granularity on repeated follows of the
same link (Paper: every follow pushes — recommend same); whether `EXTRA_INITIAL_PAGE_ID`
Intent-redelivery on recreate is handled or recorded (Paper accepted it).
**Answered 2026-08-23:** **every follow pushes** (Paper's rule — the trail is a
pop-on-walk-back stack, so back-and-repeat can never stack duplicates; loop chains like
A→B→A→B are real history and belong on the trail); Intent-redelivery is **handled in K4**
(consume `EXTRA_INITIAL_PAGE_ID` once — a rebuilt via-link notebook lands on the remembered
page, not the link target; Paper's accepted quirk is fixed here, not inherited).

### K5 — Review + hardening + docs + freeze
**Status:** ✅ Complete (commit 05c5d5e, Nomad-verified + user all-clear 2026-08-23 —
**ARC 6 "Links" COMPLETE + FROZEN**, version stays 0.1.0-ratta)

**Outcome:** Phase-start answers: review level **high** (every arc's level), version stamp
**stays 0.1.0-ratta** (user's call). K1 debug scaffold removed whole (flask button + string +
`ic_flask` + `debugCreateTestLink` + the `SelectionToolbar` hook). Arc-range `/code-review high`
(`5c383b0..HEAD`): **10 findings — 7 confirmed correctness + 3 cleanups; 9 fixed, 1 partially
fixed + accepted → `BACKLOG.md`**:
① `ForeignPageSource` cross-instance seal race (leave-drill → re-drill could hold two connections
to one `.soil`) → `sealed` flips synchronously + companion `lastSeal` job joined by the next open;
② Back silently dead for a via-link notebook's whole opening window (`walkBack`'s alive/busy door
swallowed it) → `backPressed()` walks only while `opened && !closing`, else falls through to
`close()`; ③ `LinkPickFlow.begin` re-armed the relay after its drain suspension even if the host
had closed → re-checks finishing/destroyed/`isOpen` and bails; ④ `LinkStore.move` was the one
multi-row op outside `transact` → wrapped; ⑤ the host's `order = 0` snapshot rewrote the
store-assigned z-order on relink/restore (overlap taps then hit the wrong link) →
`reviveOrInsert`: revive **in place** by id, upsert only a row that never existed (+2 JVM tests);
⑥ the fresh-open trail clear ran on recreate/task-rebuild too, stranding a mid-story walk-back →
gated on `savedInstanceState == null` like the initial-page consume; ⑦ a self-targeting page
payload pushed self-entries onto the trail → silent no-op like the notebook-self case;
⑧ `foreignPageAlive` hand-rolled the one-shot open→read→seal ritual → extracted
**`SoilDatabase.readOnce`** (the single owner; never hand-roll that shape again); ⑨ breadcrumb
triplication: the real drift fixed (picker now `fullScroll(FOCUS_RIGHT)`s to the current folder),
shared-builder extraction **accepted → BACKLOG**; ⑩ link composites rasterized on Main in every
flip → `LinkRenderer.prebuild` on `Dispatchers.Default` inside both suspend load blocks (in
`navigateTo`, inside the buffered-commit window), `update(links, prebuilt)` installs. Docs:
**`docs/links.md` new** (the arc's subsystem doc, K5 fixes folded in), `docs/notebook.md`
(5 selection modes, link gestures + undo rows, open/close/`backPressed`, K3 undo-clear,
frame-silence "arc 6 added no exception", test pointer), `docs/library.md` (`NewFolderFlow` /
`SchemePrefill` extractions, card `selectedId`, `LinkTrail`/`lastOpenViaLink` prefs rows), app
`CLAUDE.md` (subsystem-doc map). **423 JVM tests** (app 388 · api 6 · ext 29), debug + release
built, signed, both installed on SNN. **Regression: Haiku walk hit the recorded tap-aim pattern
again** (called the p3 link follow "broken" after tapping ~120 px off it; every other step
passed) — **Fable re-drove by hand: follows, walk-backs, fresh-open trail clear, Contents
swipe-down, flips all pass, crash buffer clean**. Note: the on-device test data has changed since
K4 — every current link is in-notebook, so the **cross-notebook chain (seal → relaunch,
`readOnce` pre-check, force-stop restore) is the user's eye check to re-prove**, along with the
K5 fixes only a pen can reach. **User eye check (#10) all-pass 2026-08-23, no findings** —
cross-notebook create + follow + swipe-up return, Back cancels a slow open, overlap tap resolves
to the newer link after undo/redo, picker re-drill + create clean, deep-folder crumbs
end-scrolled, writing-over-links and flip feel unchanged. Arc frozen.

Remove the K1 debug create-test-link (+ its strings); arc-range `/code-review` (level asked
at phase start), findings fixed or explicitly accepted → `BACKLOG.md`; docs
(`docs/links.md` **new** under the app, `docs/notebook.md` links + gestures sections,
`docs/library.md` if touched, frame-silence ledger if any new exception); app `CLAUDE.md`
touch-ups; memory + this file's outcomes; version-stamp decision; full regression (Haiku
walk + short user checklist); commit + push; arc freeze.
**Gate:** everything green or explicitly accepted; user all-clear.

**Questions to resolve at phase start:** review level (every arc froze at **high**);
version stamp (0.1.0-ratta so far — links may warrant 0.2.0-ratta, user's call).

---

## Phases — Arc 7 "Pages" (planned 2026-08-23, wizard complete)

Whole-page **copy / cut / paste**, within a notebook and across notebooks, on a **global
clipboard that lives in the index** (`notesprout.db`) so a copy survives a force-stop and
travels between notebooks. og's `docs/clipboard-and-page-transfer.md` (monorepo root) is the
reading reference — no code copied, and its picker-driven "copy to other notebook" flow is
**not** what SN builds: the persisted clipboard *is* the cross-notebook mechanism (copy in A,
open B, long-press → Paste). Entry point is the existing page long-press sheet, which today
holds only Delete page. Phase letter **B** = clipboard.

Two constraints are settled by the standing rules, not by the wizard:

- **No new index table.** `notesprout.db` is Room-validated and format-compatible with Paper;
  a new `@Entity` changes the identity hash and a Paper index would fail validation (and vice
  versa). The clipboard is an **additive row type** at a sentinel id — the arc-5 `naming`
  precedent, the proven-safe pattern.
- **No encryption gate.** og's "protection drops" warning is vacuous in SN: one global key,
  every `.soil` under it, and the index itself encrypted at rest. Recorded, not built — the
  R6 rotation-leg reasoning.

### Locked decisions (arc-7 wizard 2026-08-23 — do not re-ask)

| Decision | Answer |
|---|---|
| Clipboard storage | **Additive index row type** `ObjectType.CLIPBOARD = "clipboard"` at the sentinel id `00000000-0000-0000-0000-636c69706264` ("clipbd" in hex — og's id). Single slot: every copy/cut is an `upsert` over it. `name` = kind label (`"page"`), `refId` = source notebook id, `flags` = envelope version, `createdAt`/`updatedAt` = copiedAt, **`blob` = the envelope JSON, UTF-8**. Invisible to the library (every listing query is type-filtered), never soft-deleted. |
| Payload shape | **Neutral `.soil` row set, kotlinx-serialization JSON** — `ClipEnvelope(version, kind, rows, sourceNotebookId, copiedAt)` with `ClipRow` mirroring the universal row columns and stroke bytes as **Base64** (`java.util.Base64`, JVM-testable — never `android.util.Base64`). `kind = "page"` now; the same envelope carries `"objects"` (strokes / headings / links) in a later arc with **no format change**. Decode never throws (the `LinkPayload` discipline): unusable → the clipboard reads as empty. Byte cap enforced on write **and** read; over-cap copy = problem dialog, never a truncated payload. |
| Op scope | **Single page** — always the page on the paper. SN has no page-index surface to multi-select on; `rows` is already a set, so multi-page is additive later. |
| Cut | **Deletes now, undoable.** Cut = capture → `deleteCurrent()` → `undo.record(Action.Page(snap))` — the existing delete path, so undo restores the page *and* its ink; cutting the only page leaves the fresh blank behind. Cross-notebook "move" falls out of cut + paste with no move engine at all. |
| Sheet | Four rows: **Copy page · Cut page · Paste page · Delete page**. Paste is present only when the clipboard holds a page (**GONE, never disabled** — the e-ink rule). Paste opens a second `ActionSheetDialog`: **Paste before this page / Paste after this page**. Delete keeps its bare confirm; copy/cut/paste confirm with a toast (toast-confirms / dialog-explains). |
| Clipboard lifetime | **Sticky, single slot.** A paste leaves it loaded (paste the same page into several notebooks); replaced only by the next Copy/Cut; survives force-stop. **No Clear UI** this arc. Consequence worth keeping: the sticky payload is the safety net for a cut whose source notebook has since been closed — paste again. |
| Availability read | In-memory header only (`SnClipboard`: kind · sourceNotebookId · copiedAt), rehydrated **off-main at app start** (`SnApplication`, og's pattern) and refreshed on every copy/cut, so the sheet decides synchronously. The **envelope blob is read from the index only at paste time** — a header projection never drags MBs out of the index. |
| Paste undo | **Undoable**, symmetric with insert/delete: paste records a structural snapshot and replays through the one `reconcile` primitive. Undo soft-deletes the pasted page **and its pasted content**, redo restores both — a distinct action arm (`Action.PagePasted`-shaped) because `Structural.objectIds` runs the opposite direction from a delete's. A template row the paste inserted is **left in place** (harmless; the next paste's dedupe reuses it). |
| Template + page size | Source template row **travels in the payload**. On paste it is **deduped** against the destination's template rows (same kind text + size + byte-identical blob → reuse, else insert fresh) so repeated pastes don't stack identical WEBPs. Page `width`/`height` are kept **verbatim** — ink is never resampled (a Manta-authored page stays its size inside a Nomad notebook). og's rule. |
| Links on a cross-notebook paste | **Rewrite to an explicit target.** `KIND_PAGE` (own-notebook, carries no notebook id) → `KIND_NOTEBOOK_PAGE` with the **source** notebook id, so the link keeps working. Exception: a link whose target is the page being pasted re-points at the **new copy**. `KIND_NOTEBOOK` / `KIND_NOTEBOOK_PAGE` payloads travel unchanged. Same-notebook paste is verbatim. |
| Ids | **Every pasted row gets a fresh UUID**, wired through one old→new map so link children re-parent onto the copied link and the page's `refId` points at the resolved template. `"order"` values are **preserved** (writing order is load-bearing — the M-arc/N3 lesson). |
| Snapshot semantics | The clipboard is a **snapshot taken at copy time**: editing or deleting the source page (or its whole notebook) afterwards changes nothing about what pastes. |
| Arc shape | **Three phases**: B1 core + same-notebook copy/cut/paste · B2 cross-notebook · B3 review/freeze. |

### Arc-7 standing traps (assume they apply)

- **Drain before capture.** A stroke commit still queued on the shared `SoilWriter` would land
  after the copy's row read — `session.store.drain()` first, exactly as `doDelete` does.
- **`java.util.Base64`, never `android.util.Base64`** — the android class is a stub under
  `returnDefaultValues` and would make every JVM codec test lie (the N1 `StaticLayout` lesson).
- Preserve `"order"` on every pasted row; never re-sequence content (writing order feeds
  recognition and the composite raster).
- `ObjectEntity.name` is **non-null** — the kind label fills it; don't reach for a nullable
  column that doesn't exist in the lean SN index.
- A page's own content is **two levels deep** since arc 6 (links wrap children) — capture and
  paste must walk `liveDescendantIds`' shape, not `liveContentIds`.
- The undo stack is **per-notebook and cleared on close**: a cut in A pasted into B is two
  independent stacks. Recorded, not fixed — the sticky clipboard is the recovery.
- adb can't lasso or ink, but the long-press sheet, its rows and the sub-sheet **are**
  finger-injectable — device agents can drive copy/cut/paste end to end (measure before
  tapping; the tap-aim pattern has cost nine false failures).
- One `.soil` never has two connections: paste writes through the **open session**, never a
  second open of the destination file.

### B1 — Clipboard core + copy / cut / paste in one notebook
**Status:** ✅ Complete (commit a4e3a10)

`data/index/ObjectType.CLIPBOARD` + sentinel id + a header projection and blob read on
`ObjectDao`; `data/clip/ClipEnvelope.kt` (serializable envelope + `ClipRow`, Base64 blobs,
caps, never-throws decode) + `data/clip/ClipStore.kt` (write / readHeader / readEnvelope over
`ObjectDao`) + `core/SnClipboard.kt` (in-memory header, rehydrated in `SnApplication`);
`notebook/PageClip.kt` (pure capture → envelope, and the apply plan: id remap, parent
rewiring, order preservation, template resolution) wired into `NotebookSession` as
`pasteAt(envelope, before): Structural` on the `insertBlank` shape (one transaction, dense
renumber, index mirror); the long-press sheet grows Copy / Cut / Paste with the before/after
sub-sheet; `Action.PagePasted` replay arm; toasts.
**Gate:** JVM tests (envelope round-trip incl. a Base64 stroke blob and a decode-refuses
fixture set, capture/apply id remap + order preservation against `FakeSoilDao`, placement
math, cap enforcement); debug + release build; Haiku device walk (copy → paste before/after,
cut → page gone + undo restores, paste survives force-stop, page count + indicator honest,
crash buffer); **user eye check** (copied ink renders identically on the pasted page,
headings and links come with it, undo/redo of paste and cut, sheet feel).
*Fable writes the envelope/store/capture-apply contracts + session seam; Opus the sheet flows
and NotebookActivity wiring; Sonnet icons (Tabler `copy` / `scissors` / `clipboard`) and
strings.*

**Phase-start answers (2026-08-23):** paste toast **names the target** — "Pasted after
page 3" / "Pasted before page 3"; Copy and Cut stay bare ("Page copied" / "Page cut").
Copying a page with **no content is allowed** (no special case — capture yields a page row
+ template and zero content rows; a blank page is a legitimate thing to duplicate, and it
stamps a template into another notebook in B2).

**Outcome (2026-08-23, user all-clear):** built as planned. 455 JVM tests green
(+31: `ClipEnvelopeTest`, `ClipStoreTest` + `FakeObjectDao`, `PageClipTest`); debug +
release build; Nomad walk all-pass (sheet without Paste → Copy → "Page copied" → sheet
with Paste → sub-sheet → paste after = 7/8 with the heading on the copy → cut = back to
6/7 → force-stop → **Paste still there** → paste before = "Pasted before page 6" → delete
to restore). `logcat -b crash` empty; notebook left exactly as found.

Shape as built:
- `ObjectType.CLIPBOARD` + `ListIds.CLIPBOARD_ID` + `ObjectDao.clipHeader` (a **blob-free
  projection** — `name/refId/updatedAt/flags` aliased onto `ClipHeader`) / `clipBlob`.
- `data/clip/` = `ClipEnvelope` (+ `ClipRow`, `ClipHeader`) and `ClipStore`. `MAX_BYTES`
  = 12 MB, enforced on encode **and** decode; decode refuses garbage / truncation / an
  empty row set / a **newer** version, and `ignoreUnknownKeys` keeps it forward-tolerant.
- **Deviation from the plan, recorded:** the header is rehydrated at **notebook open**
  (`SnClipboard.ensureLoaded()` in `openSession`), not in `SnApplication`. SN's index is
  encrypted and only `BootstrapActivity` opens it — at `Application.onCreate` there is
  nothing to read. The notebook screen is the only consumer and always runs after
  Bootstrap, so this one call covers every route in, **including the unlock route**, which
  never passes through a warm Bootstrap.
- `PageClip` is pure and **row-level, not object-level** — it understands only the page row
  (where the template reference lives), so anything a later arc adds to the family table
  copies without it learning a content type. `plan()` takes an injected `newId` for
  testability and **drops** a content row whose parent didn't travel (untrusted payload —
  a visible absence beats a silent corruption).
- Template resolution landed as `Reuse` / `Insert` / `None` in `NotebookSession`, with
  `Insert` bringing the row in **under its source id** (free in the destination). Dedupe
  therefore already falls out for a repeat paste of the same source page; B2 only adds the
  content-match rule for the same paper under a different id.
- `PageGestures.Listener.onDeleteRequested` renamed **`onPageSheetRequested`** — the
  long-press no longer means "delete".
- Docs: `docs/clipboard.md` **NEW** (written now rather than in B3 — `docs/notebook.md`
  links it, and a dangling link is worse than a doc that B2 extends); `docs/notebook.md`
  gesture table / page-ops table / undo table / long-press section / frame-silence
  exception 1 all updated; app `CLAUDE.md` doc map + frame-silence line.
- **A cross-notebook paste already works** (template travels, ids remap, size verbatim);
  what B2 adds is the `KIND_PAGE` link rewrite. Left working rather than blocked — the arc
  freezes at B3, and blocking would be code written only to be deleted.

**Not verifiable by adb (eye check):** undo/redo of a paste and of a cut are multi-finger
stationary double-taps, which `input` cannot inject.

### B2 — Cross-notebook paste
**Status:** ✅ Complete

Template dedupe against the destination's template rows (content match, else fresh insert);
the `KIND_PAGE` → `KIND_NOTEBOOK_PAGE` link rewrite with the self-link exception; page-size
mismatch kept verbatim and proven on a Manta-authored page; a source notebook deleted or
renamed between copy and paste (payload is self-contained — only the rewritten link target
resolves dead, into K4's dialog); envelope-version and cap handling for a payload written by
an older build.
**Gate:** JVM tests (template match rule, payload rewrite table incl. every kind and the
self-link case, foreign-envelope rejection); debug + release build; Haiku device walk (copy in
A → open B → paste, page count in both, force-stop between copy and paste, `.soil` size sanity
after repeated pastes = dedupe working); **user eye check** (a real page with ink + heading +
link copied A→B, the rewritten link followed back into A, cut A → paste B as a move).

**Phase-start answers (2026-08-23):** **Nomad only** — the size-mismatch case is proven by JVM
tests over a hand-built payload, not by a Manta leg. The paste toast is **unchanged across
notebooks** ("Pasted after page 3"): the source notebook is something you already know, and its
name would put an unbounded string in an e-ink toast.

**Outcome (2026-08-23, user all-clear):** built as planned. **466 JVM tests** green (app 431 ·
api 6 · ext 29, +11); debug + release built and signed; debug installed on the Nomad.

Shape as built:
- **Template dedupe by content.** `resolveTemplate` now tries three things: the row is already here
  under that id (same-notebook, or a repeat paste — `Insert` brings it in under its *source* id) →
  the same paper under a **different** id → insert. "The same paper" is kind label + size +
  byte-identical pixels (`PageClip.matchTemplate`, pure). The read is blob-free first — new
  `SoilDao.templateDigests` projects `id/text/width/height/length(blob)` and only rows that could
  match are loaded whole (`ClipHeader` discipline, one level down). Two notebooks made from the same
  built-in template hold the same WEBP under different UUIDs, so without this every notebook pair
  stacks its own copy.
- **The `KIND_PAGE` link rewrite** (`PageClip.rewriteLink`, pure): own-notebook → `KIND_NOTEBOOK_PAGE`
  naming the source notebook · a link to **the page being pasted** → the new copy, still
  `KIND_PAGE` · `KIND_NOTEBOOK`/`KIND_NOTEBOOK_PAGE` and anything that does not decode → verbatim ·
  same-notebook paste and a blank `sourceNotebookId` → verbatim. Rewriting an unreadable payload
  would be inventing a target.
- **Foreign-envelope rejection moved into `doPaste`**: no envelope / wrong kind / a payload
  *claiming* a page it does not carry all clear the header and raise the problem dialog. Left to
  `pasteAt`'s `error()`, `runPageOp`'s `runCatching` would have made it a silent no-op — the one
  thing the sheet must never do. Older-build payloads were already covered (`decode` accepts
  `1..VERSION`, `ignoreUnknownKeys`).
- Source notebook deleted/renamed between copy and paste needed **no code**: the payload is
  self-contained and the source `.soil` is never reopened; only a rewritten link target resolves
  dead, into K4's dialog.
- Docs: `docs/clipboard.md` (status, three-try template table + the same-paper rule, a new
  cross-notebook link table, the foreign-payload guard, four new traps), `docs/notebook.md`
  (`pasteAt` row).

**Nomad walk, all-pass** (driven by hand over adb, no device agent): cross-notebook copy A → paste
after in B (3/3, ink identical) · two fresh **lined** notebooks → copy a blank page C → D: log shows
`paste reuses matching template`, `.soil` **stays 163840 bytes across three pastes** (1→4 pages) =
dedupe working · force-stop between copy and paste, clipboard survives · **cut in D → paste in C** =
a cross-notebook move (template content-matched to C's own row, a different id again) · scratch
notebooks deleted, the user's notebook restored to 2 pages. `logcat -b crash` empty.

**Not verifiable by adb (eye check):** every link case — links are made by lasso, which `input`
cannot drive. The rewrite table is JVM-tested instead; the user's eye check was a real page with
ink + heading + link copied A → B and the rewritten link followed back into A — **all-pass
2026-08-23, no findings.**

*New trap recorded:* a page sheet that is up has `releaseRender()`'d the surface, so a screencap
taken while it shows can be **missing committed ink that is plainly there once it closes** — cost
one false data-loss scare mid-walk.

### B3 — Review + hardening + docs + freeze
**Status:** ✅ Complete — **Arc 7 "Pages" frozen 2026-08-23**

Arc-range `/code-review` (level asked at phase start; every arc so far froze at **high**),
findings fixed or explicitly accepted → monorepo `BACKLOG.md`; docs (`docs/clipboard.md`
**new** under the app; `docs/notebook.md` long-press sheet + undo rows; `docs/library.md` if
the index row type is described there; frame-silence ledger if any new exception); app
`CLAUDE.md` touch-ups; memory + this file's outcomes; version-stamp decision; full regression
(Haiku walk + the short user checklist); commit + push; arc freeze.
**Gate:** everything green or explicitly accepted; user all-clear.

**Questions to resolve at phase start:** review level; version stamp (0.1.0-ratta through six
arcs — the user's call).

**Phase-start answers (2026-08-23):** review level **high** (the standing precedent), range
`a4e3a10^..HEAD` (B1 + B2). Version stamp **stays `0.1.0-ratta`** — no bump at this freeze; the
version waits for a real milestone rather than tracking arcs.

**Outcome (2026-08-23):** review returned **5 findings, all verified real and all fixed** — none
critical, none in the id-remap / order / undo core (the reviewer cleared those explicitly). **473 JVM
tests** green (app 438 +7 · api 6 · ext 29); debug + release built, release signed; Nomad walk
all-pass; `logcat -b crash` empty.

| # | Finding | Fix |
|---|---|---|
| 1 (med) | `MAX_BYTES` 12 MB sat **above the 8 MiB cursor window** the blob is read back through (`SQLiteCursor.DEFAULT_CURSOR_WINDOW_SIZE`, confirmed in the sqlcipher-android 4.6.1 AAR). An 8–12 MB copy would write, replace a good clipboard, toast success — then throw on every paste, swallowed by `runPageOp` into a dead tap | cap → **6 MB**, `CURSOR_WINDOW_BYTES` + a guard test that fails if the cap is ever raised past the window; `readEnvelope` also guards the read |
| 2 | `SnClipboard.set(null)` cleared only the in-memory mirror — the row's still-valid header came back at the next notebook open and failed again, **forever** | `ObjectDao.clipClear` / `ClipStore.clear()` (soft-delete **and** null the blob) on the paste-failed path |
| 3 | The paste toast named the anchor's **pre**-paste number, so "Pasted before page 3" pointed at the page it had just created | `PageMath.anchorNumberAfterPaste` (pure, JVM-tested): before → anchor+1, after unchanged |
| 4 | `doCopy` handled two failures and swallowed every other throw — a disk/IO error made Copy a dead tap while a **stale** clipboard stood ready to paste the wrong page | capture + write wrapped, distinct message for over-cap vs. failed write (`clip_write_failed`) |
| 5 | `ensureLoaded` latched `loaded = true` on a **failed** read — one transient index error hid Paste for the whole process | only a successful read latches |

Docs: `docs/clipboard.md` (status → arc complete, the cursor-window rationale, the clear path, the
toast rule, two new traps). `docs/notebook.md` needed nothing — it delegates the clipboard to
`docs/clipboard.md`. **`docs/library.md` deliberately unchanged**: it has no index-row-type section
(the `naming` row is documented under *Name schemes*, its own feature), so the clipboard row stays
documented where it lives.

**Nomad walk, all-pass** (by hand over adb, no device agent — two scratch lined notebooks in
`Test`, both deleted afterwards): copy → "Page copied" · paste after on 1/1 → 2/2 · **paste before
on 2/2 → indicator 2/3 with the toast reading "Pasted before page 3"** (fix 3 proven on glass;
pre-fix it said "page 2") · paste after on 2/3 → "Pasted after page 2", 3/4 · force-stop → **Paste
row still there** · cut → "Page cut", 2/3 · cross-notebook paste into a second lined notebook →
"Pasted after page 1" with `paste reuses matching template` in the log (B2's content dedupe still
live after the changes). `logcat -b crash` empty; the user's notebooks untouched.

**Observed once, NOT arc 7 — the "Opening…" overlay can stick and kill the library.** After
`install -r` over a live app on a **sleeping, locked** device, the launch-restore chain ran behind
the lock screen; on returning from the notebook the library carried a **full-screen clickable**
`OpeningOverlay` node (uiautomator confirmed it, not a cover artefact) that swallowed every tap —
the library was completely dead until a force-stop. Not reproducible in three later attempts (normal
open→back, restore→back, restore-with-screen-off, which re-locks the device and blocks adb). Nothing
in arc 7 touches `OpeningOverlay` or the restore path.

**Chased and fixed at the user's call (2026-08-23), outside the arc range.** The hole was
`armAutoHide`'s rule: *hide on the first `ON_RESUME` after an `ON_PAUSE`* assumes every show is
followed by a pause, which the tap path guarantees and nothing else does — an activity that shows the
box while it is not resumed (recreated in the background, or opening from `onCreate` via
`reopenLastNotebookIfNeeded`) resumes with no pause on record and hides nothing, ever. Now: **hide on
any `ON_RESUME` with no launch pending**, with `launchPending` keeping the restore path honest (the
resume that arrives mid-launch is the one that must leave the box alone) and a `WATCHDOG_MS` = 4 s
backstop for a launch that never draws. Verified on the Nomad: tap → notebook → back leaves a live
library, and restore-into-notebook → back leaves a live library, no overlay node in either dump.
Documented in `docs/notebook.md` § "Opening…" overlay. Committed separately from the arc-7 freeze.

**Link underlines pruned to the og look (2026-08-23), outside the arc range.** The user's eye-check
on glass: the underline read grey, worst under stroke-only links, and its gap was too tight. Two
causes, both arc-6 code:

1. **A 1 dp `drawLine` is not one weight.** 1 dp is **1.875 px** on the Nomad, and the line's centre
   landed wherever the bounds' float bottom put it, so Skia's non-antialiased ">50 % of the pixel"
   rule kept two rows for some links and a single hairline row for others. Now a `drawRect` of
   `round(density)` px (≥ 2) on integer edges — same weight every time, every pixel fully black.
   **Standing trap: a hairline measured in dp on a 300 ppi non-integer density is a coin flip.**
2. **The band was measured from point-tight bounds.** `Stroke.bounds` carries no stroke width (the
   trap `LinkComposite.padOf` already pads for), so half the stroke ate the 2 dp clearance and the
   line sat against the writing — invisible on heading links, whose boxes carry 8 dp of their own.
   The user's own model: *give ink the box a heading already has.* `PageLink.bandBottom` now puts the
   line `UNDERLINE_CLEARANCE_DP` (4 dp) below the lowest wrapped **box** bottom, where a stroke's box
   is its ink extent (`bounds.bottom + width / 2`) plus `HeadingTypography.PADDING_DP` (8 dp). Ink and
   headings arrive at the line looking alike; heading-only links are unchanged.

Links written under the old band self-heal at page load — `PageLink.withUnderlineBand`, applied by
`NotebookActivity.withUnderlineBand` next to the heading remeasure and before `prebuild`, re-applies
the wrap-time formula and **only ever grows** (a foreign link may wrap children this build cannot
decode; shrinking to the union of what we can read would cut it down). In memory only; the row is
corrected whenever the link is next written. Four new `LinkRowsTest` cases; `docs/links.md` §
underline chrome rewritten. Verified on the Nomad by the user's eye.

---

## Phases — Arc 8 "Objects" (planned 2026-08-23, wizard complete)

Copy / cut / paste of **what's on a page** — strokes, headings, links — within a notebook and
across notebooks, on the same clipboard arc 7 built. Arc 7 reserved this: `ClipEnvelope`'s
`kind` discriminator was written so a later arc could put objects on the clipboard "with **no
format change and no migration**". That promise is what makes this a light arc; the real work is
the placement path (a tap has to mean *paste here*) and the lasso popup that og has and SN never
built.

### Locked decisions (arc-8 wizard 2026-08-23 — do not re-ask)

| Decision | Answer |
|---|---|
| Copy / Cut entry | The **selection toolbar** gains Copy and Cut (Tabler `copy` / `scissors`), offered in **every** selection mode — ink, a lone heading, a lone link, mixed, mixed-with-link. A link copies **whole**, wrapped children included (the K1 model — nothing ever reaches inside one). |
| Paste trigger | **og's tap-to-place, stylus only.** Lasso armed + no selection + a sub-threshold **pen** tap on bare paper → the clipboard pastes **centred on that tap**. Finger stays reserved for link-follow, gestures and dismissal, and a palm can never paste. Needs a g-paper addition (below). |
| Engine change | **g-paper 0.1.5**: a new `PaperListener` callback for a sub-threshold **stylus** tap on bare paper while `tool == LASSO` and no selection exists — palm-gated and escrowed exactly like `onSelectionTapped`, never fired for finger, never for a tap inside a selection box. Fixed in g-paper, pinned here (the standing rule: never work an engine gap around in the host). |
| After Copy / Cut | **og's**: the clipboard is written, the selection is dismissed, the bar goes away — and the host **explicitly re-arms `Tool.LASSO`** so the very next tap pastes. Cut deletes first, then the same. |
| Smart-lasso trap | Dismissing a selection **ends the smart-lasso session and restores `Tool.PEN`** (g-paper's documented behaviour). Without the explicit re-arm above, the paste tap after a Copy would land under a PEN and **ink the page** instead. A host-initiated tool change ends the session cleanly. |
| Lasso popup | og's, restored: a second tap on the **already-armed** lasso button opens a small bordered bar anchored under it, holding **Paste** and **Clear** (silent no-op when the clipboard holds no objects — P1's "second tap = no-op" survives for the empty case). Dismissed on tool switch, page swap, gesture start, paste, clear, and an outside tap. Its rect joins the exclusion rects and `overChrome`. |
| Popup Paste placement | **Source coordinates** (clamped onto the page), landing selected — pasting into the same or a same-size page reproduces the original layout exactly. Tap-paste centres on the tap; the popup's Paste has no tap to aim at. |
| Clipboard-loaded signal | The lasso toolbar button's icon swaps to a **clipboard-marked lasso** while the clipboard holds objects (og's `ic_lasso_clipboard`, fresh-drawn Tabler-style) — the only hint that a tap will paste. Reverts on Clear, and on a page copy taking the slot. |
| Clear | og's: clears the in-memory header **and retires the index row** (`ClipStore.clear()` — soft-delete + null blob, the B3 lesson that clearing the mirror alone is not clearing the clipboard), toast-confirms, hides the popup, reverts the icon. |
| Clipboard slot | **One slot, kind wins.** The same sentinel row, `kind = "page"` or `"objects"`; a copy of either replaces the other. So "Paste page" leaves the long-press sheet while objects are held, and the popup's Paste is absent while a page is held (GONE, never disabled). |
| Off-page landing | **Clamp.** The pasted set is shifted so its bounding box sits inside the page when it fits; content larger than the page pastes from the top-left. Silent — no toast for a placement the user will see land. |
| Order | **Rebased, not verbatim** — the one deliberate divergence from `PageClip`. A page paste owns a whole self-contained row set; an object paste lands *among* existing rows, so pasted content is appended after the destination page's current max `"order"` **with its relative order preserved** (writing order is load-bearing — the M-arc / N3 lesson). |
| Geometry | `ObjectClip` is **object-level, not row-level** (again unlike `PageClip`): a `stroke` row carries **no** `x/y/width/height` — its geometry is entirely inside the format-B blob — so translating one means decode → `Stroke.translated` → re-encode (`StrokeCodec`/`StrokeRows`, both pure). Headings and links translate by their `x/y` columns; a link's wrapped children are page-absolute and translate with it (`PageLink.translated`'s rule). |
| Ids / parents | Every pasted row gets a fresh UUID through one old→new map; top-level content parents to the destination **page**, a link's children to the **copied link**. A child whose parent didn't travel is dropped (`PageClip`'s untrusted-payload rule). |
| Cross-notebook links | B2's rewrite, minus the self-page case (no page travels): `KIND_PAGE` → `KIND_NOTEBOOK_PAGE` naming the **source** notebook · `KIND_NOTEBOOK` / `KIND_NOTEBOOK_PAGE` / anything that doesn't decode → verbatim · same-notebook paste → verbatim. |
| After a paste | The pasted content lands **selected**, bar up (`paper.setSelection`, host-initiated — the `selectAsHeading` precedent), so the pen can drag it straight into place. |
| Undo | One entry per act. Paste = a new `Action.ObjectsPasted` arm (`Action.Deleted` run in reverse: undo soft-deletes the pasted strokes/headings/links, redo restores them). Cut = the existing `deleteSelection` path's single `Action.Deleted`, so undo puts the ink back exactly as the bar's Delete would. Copy and Clear record nothing. |
| Toasts / dialogs | Toast-confirms: "Copied" · "Cut" · "Pasted" · "Clipboard cleared". Dialog-explains, on the B3 pattern: an over-cap payload, a failed clipboard write (copy **and** cut — a cut whose write failed must not delete), an unreadable/foreign payload at paste (which also retires the row). Never a silent no-op. |
| Encryption | Nothing new — one global key, index encrypted at rest (arc 7's finding). Recorded, not built. |
| Arc shape | **Two phases**: O1 engine bump + core copy/cut/paste + popup/Clear/icon, same notebook · O2 cross-notebook link rewrite + review + docs + freeze. |

### Arc-8 standing traps

- **A dismissed selection restores PEN** (smart lasso). Re-arm `Tool.LASSO` after Copy/Cut, or the
  paste tap inks the page.
- **A `stroke` row has no bounds columns** — never translate one by touching `x/y`; decode,
  translate, re-encode. (A `heading` / `link` row is the opposite: bounds *are* columns.)
- Arc 7's whole trap list still applies: drain before capture · `java.util.Base64` ·
  `ObjectEntity.name` is non-null · content is two levels deep (`liveDescendantIds`) · the 6 MB cap
  is pinned by the 8 MiB cursor window · clearing the in-memory mirror is not clearing the clipboard
  · one `.soil`, one connection.
- **`"order"` rebase is not `"order"` rewrite** — preserve the relative sequence of the pasted set.
- **adb can neither lasso nor ink**, so Copy/Cut/paste-tap are **eye-check only**; device agents can
  reach the popup only after the user has put something on the clipboard by hand. The pure halves
  (`ObjectClip`, placement math, the rewrite table) are JVM-tested instead.
- A new engine callback means a **g-paper version bump**: `~/git/g-paper` → `GPAPER_VERSION=0.1.5`,
  `publishToMavenLocal`, re-pin in `app/build.gradle.kts`, and record the API in
  `docs/host-responsibilities.md` there.

### O1 — Engine tap callback + object copy / cut / paste in one notebook
**Status:** ✅ Complete (commit `bae18da`, g-paper 0.1.5 = `bbcdc37`) — 2026-08-23

g-paper **0.1.5**: the bare-paper stylus-tap callback (contract above) + its doc; re-pin.
Host: `notebook/ObjectClip.kt` (pure — capture selection → `kind = "objects"` envelope via the
existing `StrokeRows` / `HeadingRows` / `LinkRows` mappers; plan → fresh ids, parent rewiring,
order rebase, geometry translation) and `notebook/ObjectPlacement.kt` (pure — payload bbox +
tap point (or source origin) + page size → the clamped `dx/dy`); `NotebookSession.pasteObjects`
(one transaction, rows through the existing stores, index/session mirrors honest);
`SelectionToolbar` grows Copy / Cut; `NotebookActivity` gets `doObjectCopy` / `doObjectCut` /
`doObjectPaste` (re-arm LASSO, live-map + renderer updates, `paper.addStrokes`, `setSelection`,
`contentsFlow.refresh()` when a heading landed, `Action.ObjectsPasted`); the lasso popup
(Paste + Clear) with its dismiss rules, exclusion rect and icon swap; strings + Tabler icons.
**Gate:** JVM tests (`ObjectClip` capture/plan: id remap, parent rewiring, order rebase,
stroke re-encode round-trip through `StrokeCodec`, dropped orphan; `ObjectPlacement` clamp
cases incl. content bigger than the page; envelope `kind` round-trip and the page/objects slot
swap); debug + release build; Haiku device walk for what adb can see (popup open/dismiss, Clear,
icon swap, force-stop survival, crash buffer); **user eye check** (copy ink+heading+link → tap
paste → drag, cut → undo, paste onto another page, popup Paste at source coordinates, no tap
ever inking).
*Fable does the g-paper callback + `ObjectClip`/placement contracts and the session seam; Opus
the activity flows and the popup; Sonnet icons/strings.*

**Built (2026-08-23) — what landed, and the three decisions the code had to make.**

*Engine.* g-paper **0.1.5**: `PaperListener.onPaperTapped(x, y)` — the companion to
`onSelectionTapped`, fired from `CanvasPaperView.completeLassoOutline` when the outline classified
as a **tap** and nothing was selected at pen-down. Two facts made it cheap: the finger path
(`handleFingerSelection`) only ever drags or dismisses an *active* selection, so a contact reaching
`completeLassoOutline` is a stylus by construction and **needs no escrow**; and a new field
`outlineDismissedSelection`, latched in `lassoOutlineStart`, keeps the tap that **dismissed** a
selection from also pasting — a contact spent on a dismissal is spent. A few-sample gesture with a
real extent is still neither an outline nor a tap and reports nothing. `api.md`,
`host-responsibilities.md` (with the two host traps: re-arm the lasso yourself; the affordance is
yours to draw), root `CLAUDE.md`, demo hook, every 0.1.3/0.1.4 version pin swept to 0.1.5,
`publishToMavenLocal`, re-pinned here and in app `CLAUDE.md`.

*Host.* `notebook/ObjectClip.kt` + `notebook/ObjectPlacement.kt` (both pure, 26 new JVM tests) ·
`NotebookSession.captureObjects` / `pasteObjects` (one transaction; the `"order"` bases are read
**inside** it, since `ObjectClip.plan` is synchronous and cannot suspend) · `SelectionToolbar` grew
Copy + Cut after Delete · `NotebookToolbar` grew the armed-lasso re-tap hook and
`showClipboardLoaded` · `LassoPopup` + `SelectionAnchor.placeUnder` (3 tests) ·
`Action.ObjectsPasted` (`Deleted` run in reverse) · `ic_lasso_clipboard` · strings.

**The icon is og's, not a fresh drawing** (user's eye-check call). A clipboard badge scaled into the
corner was tried first and read as a blob at 24 dp on the panel; og's own `ic_lasso_clipboard` puts a
plus crosshair in the middle of the loop instead — space the lasso already encloses, so the mark
costs the lasso nothing and stays legible on e-ink. Copied geometry for geometry. **Standing note:
check og's `drawable/` before drawing a "fresh Tabler-style" icon — the vocabulary is largely
already there.**

Three decisions the plan left open, made here:

1. **The popup opens only while the clipboard holds objects.** The locked table says both "silent
   no-op when the clipboard holds no objects" and "Paste is absent while a page is held", which
   together would leave a popup holding nothing but a Clear for someone else's payload. Absent
   beats half-empty, and it agrees with the phase-start answer that a page-kind clipboard says
   nothing at all on this surface.
2. **The source page id is inferred, not carried** (`ObjectClip.sourcePageOf`). `PageClip` tells a
   top-level row from a link's orphan by the page row it carries; an objects payload has none, and
   arc 7's promise was *no format change*. One selection lives on one page, so the **most common**
   parent that is not itself in the payload is the source page — and anything parented to some
   *other* absent id is an orphan, dropped rather than re-parented onto the page.
3. **A paste that threw does not retire the clipboard.** A payload that decoded but carries nothing
   placeable can only ever fail again, so its row goes; a write that *threw* (a full disk, an IO
   error) is this attempt failing, and clearing the clipboard over it would turn a retry into a loss.

*New standing traps.* The popup's own dismissal is a spent contact too — `tapDismissedPopup` is
rewritten at **every** `ACTION_DOWN` in `dispatchTouchEvent` (a latch set once goes stale the moment
the contact becomes a stroke instead of a tap), and the lasso button itself is excluded from the
outside-tap dismissal or its re-tap would close the popup there and reopen it in the toolbar. ·
`Stroke.bounds` is point-tight, so the box handed to `ObjectPlacement` is the **ink extent**
(`bounds` grown by width/2) — otherwise a clamped paste shears half a nib off the page edge (the K2
trap, applied to placement). · `"order"` is per parent **and type** in this family, so the rebase
reads three maxes, not one.

**Verified:** `./gradlew test` green (466 JVM tests, 26 of them new) · debug + release build ·
installed on the Nomad, `logcat -b crash` empty, and the one thing adb can reach — a second tap on
the armed lasso with an empty clipboard — is a silent no-op that leaves the lasso armed and puts no
popup node in the dump. **adb can neither lasso nor ink**, so copy, cut, the placement tap, the
popup and the icon were the user's eye: **eye check #11 all-pass**, one finding (the icon, fixed
above). Docs done here rather than deferred to O2 — `docs/clipboard.md` now covers both kinds and
`docs/notebook.md` has the selection-bar, undo and frame-silence entries. Committed `bae18da`,
g-paper `bbcdc37`, both pushed.

**Questions resolved at phase start (2026-08-23):**
- **Popup buttons: icon-only**, at `toolbar_button_size`, each with a long-press name hint (its
  content description too) — Tabler `clipboard` for Paste, Tabler `trash` for Clear. Consistent
  with every other chrome button in SN.
- **Copy / Cut sit *after* Delete** on the selection toolbar — Delete keeps the leftmost slot it
  has held since P1; the clipboard verbs append to the right.
- **A pen tap on bare paper while the clipboard holds a *page* is silent.** Neither the icon swap
  nor the popup's Paste row is present in that state, so nothing offered a paste for the tap to
  fail at; "never a silent no-op" covers affordances that *were* offered.

### O2 — Cross-notebook + review + docs + freeze
**Status:** ✅ Complete (commit `7f008ea`) — **Arc 8 "Objects" frozen 2026-08-23**
(Nomad-verified + user all-clear: "All good")

The `KIND_PAGE` link rewrite for an objects payload (B2's `rewriteLink`, minus the self-page
case) and its test table; a copy whose source notebook is gone at paste time; arc-range
`/code-review` (level asked at phase start — every arc so far froze at **high**), findings fixed
or explicitly accepted → monorepo `BACKLOG.md`; memory + this file's outcomes; version stamp; full
regression; commit + push; arc freeze.

**Docs are already done** (O1 wrote them rather than deferring): `docs/clipboard.md` covers both
kinds end to end, `docs/notebook.md` has the selection-bar / undo / JVM-test entries and the
seventh frame-silence exception, and both `CLAUDE.md`s are current. O2 only extends them with the
cross-notebook rewrite.
**Gate:** JVM tests green; debug + release build; Nomad walk; **user eye check** (a real
selection copied A → B, the rewritten link followed back into A, cut A → paste B as a move);
user all-clear.

**Questions resolved at phase start (2026-08-23):** review level **high** (what every arc has frozen
at); version stamp **stays `0.1.0-ratta`** — nothing ships from this branch yet and no user reads the
number, so bumping it would only cost a decision about what the scheme means. Revisit when SN first
goes to a device the user does not control.

**Built (2026-08-23) — the rewrite, and what the review found.**

*The rewrite.* `ObjectClip.plan` gains the **destination** `notebookId` and `ObjectClip.rewriteLink`
— B2's rewrite minus the one case that cannot arise here. `KIND_PAGE` carries no notebook id, so a
copied link left alone would mean *a page of the destination*, almost always no page at all; it is
re-pointed at the source notebook as `KIND_NOTEBOOK_PAGE`. Everything that already names a notebook,
anything that does not decode, a blank source, and every same-notebook paste travel **verbatim**.

**There is no self-page exception, and that is the whole diff between the two rewrites.** `PageClip`
has one — a link whose target *is* the page being pasted re-points at the new copy — but **no page
travels in an objects payload**, so there is nothing for such a link to re-point at. A link to its
own source page crosses like any other, back to that page in the source notebook, which is exactly
where the page it named still is. Two functions rather than one shared with a nullable `newPageId`.

The fire site is the top-level link rows only (a link inside a link is refused outright, so there is
no second level), and `NotebookSession.pasteObjects` threads the id. **`NotebookActivity` needed no
change at all** — `doObjectPaste` was already notebook-agnostic, which is the arc-7 clipboard
paying off a second time.

*A source notebook gone at paste time needed no code.* The payload is self-contained and the source
`.soil` is never reopened; the rewritten target simply resolves dead, into the same K4 dialog a link
to a deleted notebook has always landed in. B2 had already settled this for pages. Recorded in
`docs/clipboard.md`, not built.

*Review (`/code-review high`, `8616d22..HEAD`) — three findings, all real, all fixed.* Two of them
were **arc-8 O1 bugs the eye check could not have caught**, which is the argument for running the
review over the arc rather than the phase:

1. **The lasso re-tap could never close the popup.** `onToolTap` fired `onToolTapped()` — the hook
   that takes the popup down when another tool is armed — *before* the already-armed check, so the
   toggle hid the popup and then `onLassoReTap` asked "is it showing?", got `false`, and reopened it.
   The documented toggle never closed; only an outside tap did. Fixed by firing `onToolTapped()`
   only on an actual tool change. **Standing trap: two handlers reading one piece of state — order
   the write after the read.**
2. **The dismissal latch went stale for a second pointer.** `tapDismissedPopup` was rewritten at
   `ACTION_DOWN`, which is the *first* contact of a gesture. With a hand resting on the glass — the
   normal writing posture — the palm lands first and the pen arrives as `ACTION_POINTER_DOWN`,
   inheriting the palm's answer. If the palm had dismissed the popup, **every** pen tap until the
   hand lifted silently declined to paste. Now every pointer-down rewrites it, reading
   `ev.actionIndex`. The O1 note said this latch "can never go stale"; it could.
3. **`sourcePageOf`'s majority vote could invert the rule it exists to enforce.** Rows
   `[stroke → page, childA → lnk-1, childB → lnk-1]` with the link row missing put the *orphans* in
   the majority, so they would have been written loose onto the page — the untrusted-payload rule
   exactly backwards — while the one genuine top-level row was dropped as the orphan. Replaced with
   two signals the format actually guarantees: a `link` row's parent (a link is top-level by
   definition), else the first row parented outside the payload (`capture` writes `top + children`).
   **Standing trap: when a rule exists to reject the malformed case, never let the malformed case
   outvote it.** Only reachable from a corrupt or foreign blob — but the file treats the blob as
   untrusted input by design, and the row survives force-stops.

The review also cleared, by reading rather than assuming: the id remap and per-type `"order"` rebase,
`ObjectPlacement`'s clamp order and guards, `Action.ObjectsPasted` against `LinkStore.remove/restore`
and `SoilDao.byId`'s revive branch, the popup's first-open exclusion rect (the layout listener covers
the zero-width first pass), `OpeningOverlay`'s `launchPending` against `LifecycleRegistry`'s
immediate ON_RESUME replay, and `withUnderlineBand`'s monotonicity.

**Verified:** `./gradlew test` green (**475** JVM tests, 9 new — the rewrite table, the two
inference cases), debug + release build. Version left at `0.1.0-ratta` per the phase-start answer.
Installed on the Nomad, `logcat -b crash` empty; adb re-checked the two things it can reach after the
toolbar reorder — the empty-clipboard lasso re-tap is still a silent no-op with no popup node in the
dump, and tool arming still tracks (lasso → pen → eraser). Everything else is lasso-driven and was
the user's eye: a selection copied A → B, the rewritten link followed back into A, a self-page link
likewise, cut A → paste B as a move, the popup's re-tap **closing** (it never could in O1), and a
same-notebook paste unchanged. **All-clear: "All good."**

**Arc 8 "Objects" is frozen at this commit.** Nothing is carried forward. `docs/clipboard.md` is the
reference for both halves of the clipboard; the next arc is **not planned — ask first**.

---

## Phases — Arc 9 "Snap" (planned 2026-08-24, wizard complete)

og's **snap-to-guide** for a lasso selection being dragged, fitted to SN: while snap is armed a
dragged selection pulls to page edges, page margins, page centres, and to the edges/centres/one-
margin-out proximities of the other content objects on the page, with a dashed guide drawn where
it caught. Dragging past the threshold releases it — there is no clamping, and with snap off the
drag is exactly what it is today.

**The drag is g-paper's, not the host's.** `CanvasPaperView.lassoTryBeginDrag` / `lassoDragMove` /
`lassoDragFinish` own the per-sample delta and the drag layer's `onDraw`; the host never sees a
sample. So the engine gets the feature and the host gets a toggle — the standing rule (never work
an engine gap around in the host) decides this, not preference.

### Locked decisions (arc-9 wizard 2026-08-24 — do not re-ask)

| Decision | Answer |
|---|---|
| Where it lives | **g-paper 0.1.6.** `SnapEngine` + `SnapGuide`/`SnapResult`, all three in `core/geometry/SnapEngine.kt`, wired into the existing drag; new `PaperView.snapToGuides` / `snapMarginPx`. Host wires a toggle and a pref, nothing more. |
| Margin value | **One toolbar** — `@dimen/toolbar_bar_thickness` (70dp on the Nomad/Manta tier, 56dp below) **plus the bar's 1dp bottom border**, which is why the host passes `topBar.height` measured after layout rather than the dimen: the dimen sizes the button row only, and an object snapped to it would sit two pixels behind the black rule. og's `SNAP_MARGIN_DP = 44f` was the *small*-tier button size; "the same margin as the toolbar" means the bar. g-paper holds no dimens, so the value crosses as px. |
| Page guides | og's twelve: x = 0 · margin · w/2 · w−margin · w; y = 0 · margin · h/2 · h−margin · h. Measured against the **page rect** (`setPageSize`) when known, else the view — the same rule `templateDestRect()` already uses, so the guides agree with the template. |
| Object guides | **Content objects only — headings and links.** Strokes are never snap targets (og's rule): a handwriting page is ink everywhere, and a guide per stroke bbox is a thicket that fights the pen. Targets are derived inside the engine from `ContentRenderer.hitTargets()` minus the selected ids — no host plumbing, and always current. |
| Object guide set | og's ten, per target: `left−margin` · `left` · `centerX` · `right` · `right+margin`; same five on Y. The ±margin proximities are what make equal spacing fall out of a drag. |
| Proximity gap | **The same value as the margin** (70dp / 56dp), og's single constant. Two stacked headings settle a bar-thickness apart. |
| Anchors / threshold | og's: three anchors per axis (left/centerX/right, top/centerY/bottom); nearest (anchor, guide) pair within **20dp** wins, X and Y independent, offset adjusted by `guide − anchor`. Anchors are the **tight** `Selection.bounds`, not the 12px-inflated box the overlay draws — the user is aligning content, so a heading snapped to the top margin must start at the bar's edge, not 12px inside it. `HitTarget.bounds` are tight too, so both sides of an object snap agree. |
| Guide line | 2px black dash **24 on / 12 off**, edge to edge, drawn only for an engaged guide. Same weight as the selection box (a 1dp hairline at the Nomad's 1.875 density is a non-integer coin flip — the link-underline lesson) but a visibly longer stride, so the ruler never reads as another box. |
| Toggle | Last button on the **selection toolbar**, after Cut, always visible in every selection mode. **One icon** (Tabler `template`) plus `isSelected` — `bg_toolbar_button`'s 1.5dp border is already how Pen/Eraser/Lasso show what is armed, so snap reads in the same vocabulary. No icon swap (og's pair is action-labelled and would read backwards next to those borders). |
| Default + memory | **Off by default, remembered across app restarts** — a `SnapPrefs` flag beside `BrowseState` / `SortPrefs` / `RecentsPrefs`. On once is on for every later selection, page, notebook and relaunch. |
| Scope | **Drags only.** Arc 8's tap-to-place still lands exactly where the pen tapped; the paste already arrives selected with the bar up, so the very next drag snaps it. A paste that moved itself would read as the app relocating your content. |
| Arc shape | **One phase.** ~95 lines of pure engine, one API pair, one button, one pref — splitting it would put a device round-trip in the middle of something that cannot be eye-checked until it is whole. |

### Arc-9 standing traps

- **`lassoDragFinish` recomputes `dx/dy` from the pen position** — it must report the *snapped*
  delta (`dragDx`/`dragDy`, which equal the raw delta when snap is off), or the drop would undo the
  snap the user just watched happen.
- Every drag exit (`clearSelection`, `release`, cancel, tool change, page swap) must clear the
  active guides alongside `dragDx`/`dragDy`, or a stale dashed rule survives onto the next frame.
- **adb can neither lasso nor drag**, so the snap itself is **eye-check only**; `SnapEngine` is pure
  and JVM-tested instead, and a device agent can only confirm the button exists and toggles.
- The bar grows by one button. Its widest modes are **six** (STROKES / HEADING: Delete · H · Link ·
  Copy · Cut · Snap; LINK: Delete · Edit · Unlink · Copy · Cut · Snap) — `show()` never puts more
  up. `SelectionAnchor` re-measures anyway, and 6 × 62dp = 372dp fits the Nomad's 749dp easily.
- **The margin is the bar's measured height, not the dimen** — the top bar is the button row plus a
  1dp border, and snapping to the dimen alone parks content behind that rule. `pushExclusions()`
  re-reads it, so it can never drift from the chrome it names.
- A g-paper change means the full ritual: `GPAPER_VERSION=0.1.6`, `publishToMavenLocal`, re-pin in
  `app/build.gradle.kts`, and record the API in that repo's `docs/api.md` +
  `docs/host-responsibilities.md`.

### A1 — Snap to guide (engine + toggle + freeze)
**Status:** ✅ Complete (commit `844b136`) — **Arc 9 "Snap" frozen 2026-08-24** (Nomad-verified + user all-clear:
"All tests pass"). g-paper 0.1.6 committed alongside it (`~/git/g-paper` `b224a55`).

g-paper **0.1.6**: `SnapEngine`/`SnapGuide` + the drag wiring + `snapToGuides`/`snapMarginPx` +
guide paint + docs; re-pin. Host: `ic_snap.xml`, strings, `data/prefs/SnapPrefs.kt`,
`SelectionToolbar` gains the toggle (state via `isSelected`), `NotebookActivity` loads the pref and
feeds `paper.snapToGuides` + `snapMarginPx` from `@dimen/toolbar_bar_thickness`.
**Gate:** JVM tests (page guides each axis, object edge/centre/proximity, nearest-wins, threshold
release, independent axes, no-target and off cases); `./gradlew test` green; debug + release build;
Nomad install + `logcat -b crash` empty; Nomad walk for the button's presence and toggle
persistence across a relaunch; **user eye check** (guide appears at each page guide, objects snap to
each other, past-threshold releases, snap off = free drag, preference survives a relaunch);
`/code-review` over the arc; docs (`docs/notebook.md`, both `CLAUDE.md`s) + memory; commit + push;
arc freeze.

**Built (2026-08-24).** The arc turned out to be an *engine* arc with a host garnish, and finding
that out was most of the value. `CanvasPaperView` owns `lassoTryBeginDrag` / `lassoDragMove` /
`lassoDragFinish` and the drag layer's `onDraw`; the host is handed one `SelectionMove` at the end
and never sees a sample. There was no host-side version of this feature to write. So **g-paper
0.1.6** gained `core/geometry/SnapEngine.kt` (the engine, `SnapGuide` and `SnapResult`, all pure)
and two properties, and Notesprout SN gained `SnapPrefs`, one icon, one button, and a five-line
`toggleSnap()`. Onyx inherits the whole thing untouched — it drives the same protected drag entries.

*The one thing that would have quietly ruined it.* `lassoDragFinish` computed its delta fresh from
the lift position, which is right for a raw drag and **discards a snap entirely** — the user watches
an object catch a guide and then watches it drop somewhere else. Both the samples and the lift now
go through one `applyDragDelta`, so the drop uses the freshest pen position *and* the same snap
pass. (Naively fixing this by reporting the last move sample instead trades one bug for a smaller
one: a fast drag travels ~30 px between the final sample and the lift.)

*Review (`/code-review high`, pre-commit working tree) — three findings, all real, all fixed.* None
were correctness bugs in the host; the useful one was geometric:

1. **The margin was one dimen, but the toolbar is not.** `snapMarginPx` came from
   `@dimen/toolbar_bar_thickness`, which sizes the button **row**; `topBar` is that row *plus* a 1 dp
   `inkBlack` border. An object snapped to the top margin would sit two pixels behind the black
   rule — breaking the exact invariant the value was chosen for. Now read from `topBar.height` in
   `pushExclusions()`, which re-runs on every chrome layout change, so it cannot drift.
   **Standing trap: a chrome dimen names a part, a measured view names the whole thing.**
2. **The engine change was uncommitted.** The pin moved to 0.1.6 while `~/git/g-paper` HEAD was
   still `bbcdc37` (0.1.5) — a fresh clone plus `publishToMavenLocal` would have failed to resolve.
   The engine commit lands with this one, and that ordering is now a rule for any g-paper bump.
3. **Two wrong facts in this file** — `SnapGuide`/`SnapResult` placed in `core/model` (they are in
   `core/geometry/SnapEngine.kt`) and a bar-width note claiming 8 buttons (`show()` never puts up
   more than 6). Both corrected above; this file is cross-session memory, so a wrong fact here
   outlives the session that wrote it.

Two more came out of reading the diff before the review: `hitTargets()` was being asked twice per
renderer at drag start (once for the travelling objects, once for the guides) — now one pass split
two ways, since a host's `hitTargets()` is arbitrary work; and every drag exit clears
`activeSnapGuides` / `snapTargets` alongside the deltas, or a stale rule survives onto the next
frame. The review separately cleared, by reading: `bg_toolbar_button` really does carry a
`state_selected` item, `SelectionAnchor.place` clamps the now-wider bar, and a dragged link cannot
snap to its own wrapped children (`HeadingStore.loadPage` returns page-parented rows only).

**Verified:** g-paper `:gpaper-core:test` green (23 new `SnapEngineTest` cases), Notesprout
`./gradlew test` green, debug + release build, installed on the Nomad, notebook opens on engine
`ratta`, crash buffer empty. Version stays `0.1.0-ratta`. **adb can neither lasso nor drag**, so the
snap itself was the user's eye — guides at each page guide, object-to-object catches, release past
the threshold, snap off = free drag, and the preference surviving a relaunch. All-clear: "All tests
pass."

**Arc 9 "Snap" is frozen at this commit.** Nothing is carried forward. `docs/notebook.md` §
"Snap to guides" is the reference on this side, `~/git/g-paper/docs/api.md` on the engine side; the
next arc is **not planned — ask first**.

---

## Phases — Arc 10 "Recents" (planned 2026-08-24, wizard complete)

og's **in-notebook recents** — the "Recent Notebooks" switcher on the notebook toolbar — fitted to
SN and **mirrored to the right**: the button sits flush at the top bar's right edge, the panel slides
in as a right sidebar (og's and the ToC's are both on the left), and a **two-finger swipe down**
opens it the way a one-finger swipe down opens the Contents. Tapping a row seals this notebook and
opens that one. While it is built, the ToC panel's edge rule thickens to 2 dp and the new panel takes
the same rule on its left edge.

### Locked decisions (arc-10 wizard 2026-08-24 — do not re-ask)

| Decision | Answer |
|---|---|
| What it lists | **Recently opened notebooks** (og parity) — not recent pages. The **current notebook is excluded**; tap = switch to that notebook. |
| Store | SN's existing `data/prefs/RecentsPrefs` (`sn_recents`, id + timestamp, MAX 20) — the same store the library's Recents shelf reads. **No new store, no schema change, nothing in the index or any `.soil`.** |
| Timestamp | **og's close bump**: `RecentsPrefs.touch()` re-stamps the notebook when it closes, so a row reads "when I last put it down". Ordering is unaffected (the open already moved it to the front). Written exactly once per screen — `close()` and the `onDestroy` fallback are mutually exclusive on `closing`. |
| Row | **og parity — three lines**: notebook name · `<medium date>, <time>` (device format) · the full breadcrumb (`Notebooks › A › B`). Names and paths are resolved **from the index at gather time** — the prefs store must never learn a name. |
| Panel shape | **The ToC mirrored.** One layout, two forms on `ContentsLayout.fullScreen` (< 480 dp full screen, else a sidebar) — the breakpoint is shared, the **width is not: 50 %** (`RecentRows.SIDEBAR_WIDTH_FRACTION`, user's call at T1 — the ToC's 60 % is more than a name/time/path row needs; the ToC keeps 60 %) — and anchored **right**, with the header mirrored too: title first, back/close arrow at the panel's right edge. Same 68 dp rows, same first/prev/next/last pager, same paginate-never-scroll rule, same scrim-tap dismissal. |
| Availability | **Always visible** (og). No availability gate, no `refresh()` machinery: an empty list shows "No recent notebooks", and the two-finger swipe always opens the panel. |
| Toolbar placement | **Flush against the bar's right edge** — a weighted spacer after Lasso. Icon `ic_clock` (Tabler, already in the app, og's icon for this button). |
| Gesture | **Two-finger swipe down** — the two-finger centroid judged by the flip rule rotated 90°, exactly as the one-finger vertical swipe already is. **Two-finger up is unassigned** (silently nothing). Two-finger horizontal keeps inserting a page; dominance keeps them exclusive. |
| The hop | Row tap → dismiss → "Opening…" → `close { startActivity(...) }`, the link-follow path's seal-then-launch. The target opens at its own remembered page. **Nothing is pushed onto the link trail** — a switch is not a follow, so Back in the target exits to the library. It *is* a fresh non-via-link open, so `onCreate`'s standing rule **clears** the trail on arrival, which is the wanted behaviour: a trail surviving a switch would let a link followed later in the new notebook walk back into the one you switched away from. (Corrected at T1 review — the wizard's "not cleared" was wrong.) |
| Panel edge | **2 dp** inkBlack (was 1 dp): ToC on its right edge, Recents on its left. |
| Arc shape | **One phase** (T1) — panel + button + gesture + borders, then review/docs/freeze in the same phase. Nothing here eye-checks until it is whole. |
| Staffing | **No background agents this arc** (user's call) — implemented in-session; device work is adb from here plus the user's eye check. |

### Arc-10 standing traps

- **The panel must raise `BLOCK_ALL`** while it is up, like the Contents dialog: the Ratta ink daemon
  draws firmware ink beneath any Android window, so a full-height panel over the paper needs the whole
  surface excluded, and the chrome rects back on dismiss.
- **A dialog outliving a finishing Activity is a window leak** — the recents dialog joins
  `contentsFlow.dismissIfShowing()` in *both* `close()` and `onDestroy`.
- The two-finger detector is judged at `ACTION_POINTER_UP` (back to one finger) and at 3+ fingers.
  A vertical evaluation must be added at **every** place the horizontal one is, or the swipe fires
  only on some lifts.
- A two-finger swipe that starts as one finger travelling far before the second lands is committed by
  the existing **late-arrival rule** as a *one*-finger swipe → the Contents opens. That is the rule
  arc 4 chose; land the second finger with the first.
- `IndexRepository.alive()` reads the **whole row, cover blob included** — never use it for twenty
  ids. The panel uses one blob-free batch query (`ObjectDao.aliveNotebooks`).
- adb cannot lasso or drag, but it **can** tap and `input swipe` (finger paths work — R4). A device
  agent can drive the button, the pager, a row tap and the swipe; only the EPD look is the user's eye.

### T1 — Recents panel + toolbar button + two-finger swipe + panel edges
**Status:** ✅ Complete (commit `627b635`) — **Arc 10 "Recents" frozen 2026-08-24** (Nomad-verified as far
as adb reaches, then user eye-checked: the two-finger swipe-down and the 2 dp panel edges, neither of which is
adb-injectable, both confirmed on the device).

New: `data/index/ObjectDao.aliveNotebooks` (blob-free batch) + `IndexRepository.aliveNotebooks`;
`RecentsPrefs.touch()`; `notebook/RecentRows` (pure: stored order, drop dead/current/dupes → display
rows; JVM-tested); `notebook/RecentsSource` (the IO gather + breadcrumbs + prune);
`notebook/RecentsFlow` (busy guard, pen-gated `releaseRender`, gather → dialog, `showing` →
exclusions, `dismissIfShowing`); `notebook/RecentsDialog` (the mirrored panel);
`layout/dialog_recents.xml`, `layout/item_recent_entry.xml`, `drawable/shape_recents_sidebar.xml`,
strings. Changed: `shape_contents_sidebar` 1 dp → 2 dp; `activity_notebook.xml` (spacer + `btnRecents`);
`PageGestures` (two-finger vertical → `onTwoFingerSwipeDown`); `NotebookActivity` (wiring, the hop,
`BLOCK_ALL`, close hygiene, the close bump).
**Gate:** JVM tests (`RecentRows`, the gesture's pure rules where they exist); `./gradlew test` green;
debug + release build; Nomad install + `logcat -b crash` empty; adb walk (button opens the panel,
rows render, pager, a row tap switches notebooks, the swipe opens it); **user eye check** (panel edge
weight both sides, right-anchored feel, the two-finger swipe, the switch); `/code-review`; docs
(`docs/notebook.md`, both `CLAUDE.md`s) + memory; commit + push; arc freeze.

**Built (2026-08-24), in-session, no agents (the user's staffing call).** The panel is the Contents
dialog seen from the other side, and building it that way — same layout skeleton, same pagination
contract, same BLOCK_ALL / dismiss-hygiene wiring — is why it cost four small files instead of a
subsystem. New: `RecentRows` (pure: stored-order selection with the current notebook dropped, the
breadcrumb join, the sidebar fraction, `itemsPerPage`), `RecentsSource` (the IO gather),
`RecentsFlow`, `RecentsDialog`, `dialog_recents.xml` / `item_recent_entry.xml` /
`shape_recents_sidebar.xml`, plus `ObjectDao.aliveNotebooks` (blob-free batch),
`RecentsPrefs.touch()`, the gesture's vertical twin, and `NotebookActivity.switchToNotebook`.
`shape_contents_sidebar` went 1 dp → 2 dp with its hidden-edge insets widened past the stroke.

**The width changed after the first device look:** the user cut the panel from the ToC's 60 % to
**50 %** — a row is a name, a time and a path, and the ToC's width was empty space. Only the width
forked; the 480 dp full-screen breakpoint stays shared with `ContentsLayout`, so "a sidebar doesn't
fit here" is still decided in one place.

**Review (`/code-review high`, pre-commit working tree) — two findings, both real, both fixed.**
Neither was in the new subsystem's logic; both were about telling the truth:

1. **The hop clears the link trail — the code was right and three pieces of prose were wrong.** The
   switch launches without `viaLink`, so `onCreate`'s standing "a fresh open starts a new story" rule
   clears the trail; the KDoc, `docs/notebook.md` and this file's locked-decision row all said "not
   cleared" (the wizard's answer, taken too literally). Clearing is what you want: a trail left
   standing across a switch would let a link followed later in the *new* notebook walk back into the
   notebook you switched away from. All three corrected rather than the code.
   **Standing lesson: when a review finds code and comment disagreeing, decide which one is right
   before reaching for the editor** — here the comment was the defect.
2. **An index-read failure was reported to the user as "It was deleted".** `switchToNotebook` folded
   every exception into `null`, and `null`'s only message is the deleted-row dialog — so a transient
   read fault (the class `ContentsFlow` already degrades on) would have told someone their notebook
   was gone, repeatably, and they had no way to check. The read is a `runCatching` now: failure logs
   and says "The library couldn't be read just now"; `recents_gone_body` is reserved for a genuinely
   absent row.

The reviewer separately cleared by reading: the vertical evaluation is present at **both** places the
horizontal one is and dominance keeps the paired calls mutually exclusive; `touch()` writes exactly
once per screen and never reorders; the BLOCK_ALL / `dismissIfShowing` / busy-reset paths match the
Contents precedent; the check-then-raise-overlay ordering avoids B3's stranded-overlay hazard; and
the 2 dp strokes render at full weight given the −4 dp insets (`GradientDrawable` insets a stroked
rect by half its width).

**Verified:** 489 JVM tests green (+14: `RecentRowsTest`), debug + release build, installed on the
Nomad. adb walk: the button opens the panel (17 rows over 2 pages, the open notebook absent), the
pager pages, a row tap switched into "Test 04" at 1/4, Back landed in the library (not a trail walk),
reopening showed Test 04 at the top stamped with its **close** time — the bump working end to end —
the one-finger swipe-down still opens the Contents, the scrim dismisses, `panel=702px` of 1404 after
the width change, crash buffer empty throughout. **adb cannot inject multi-touch**, so the two-finger
swipe is the user's eye check, as is the 2 dp rule's weight on e-ink. Version stays `0.1.0-ratta`.
**The user's eye check came back clear**, and **arc 10 "Recents" is frozen at this commit.** Nothing is
carried forward; `docs/notebook.md` § "Recents" is the reference.

---

## Phases — Arc 11 "Scratch Pad" (planned 2026-08-24, wizard complete)

og's **scratch pad** — one global, multi-page jotter reachable from the notebook and the library,
persisted across restarts, with two-way ink transfer to and from the notebook it was opened from —
fitted to SN **as an extension**, on the user's explicit call. That call is the fresh user decision
the standing extension rule demands before a second capability point may exist: SN gains
**`SCRATCH_PAD`** as its second point and its first **screen-owning** one (Paper's UI-rule tier 2 —
an extension-owned screen *off* the paper that the core launches for a result and returns from; the
core grows no second drawing surface). og (`docs/scratchpad.md` at the monorepo root) and Paper's
arc 6 (`apps/notesprout_paper/PAPER_SCRATCHPAD_PLAN.md`, `docs/scratchpad.md`, `docs/extensions.md`
§"ScratchPad (contract)" / §"The extension store" / rules 25–27 / the tier-2 recipe) are reading
references — **no code copied**, the standing rule; Paper's shipped constants and its S3 review
findings are inherited as traps below, because every one of them was found the hard way.

Two structural moves ride with the feature, both wizard-locked: a shared **`:sn-screen`** library
(the second paper surface needs what the notebook screen already has — the sibling-copy trap og's
`RattaNotebookView` still teaches), and the **full extension-store port** (the pad's pages persist
in a host-owned encrypted store; the extension itself writes nothing to disk, ever). J2 amends the
two `apps/notesprout_ratta/CLAUDE.md` standing rules those moves collide with, by name.

**The arc's headline risk is the EPD handoff**: two paper surfaces in two processes for the first
time on SN. The traps section carries the whole discipline; a failure there goes to g-paper, never
a host workaround.

### Locked decisions (arc-11 wizard 2026-08-24 — do not re-ask)

| Decision | Answer |
|---|---|
| It stays an extension | **Yes — the user was explicit.** This is the fresh user decision `CLAUDE.md` requires; `SCRATCH_PAD` becomes SN's second extension point and the first screen-owning one (tier 2). `NSE · Scratch Pad` owns `ScratchPadActivity`, its own g-paper canvas, tools and pages; the core adds the point, two entry buttons, one selection action and the transfers. |
| Shared code | **New `:sn-screen` Android library**, Paper's S0 move on SN's files: a pure `git mv` out of `:app` — `core/{StrokeCodec, InkColorCodec, Slog, Dialogs, TopGuard, ActionSheetDialog}`, `notebook/{PageMath, SelectionAnchor, PageGestures, UndoRedoStack}` (genericised to `UndoRedoStack<A : Any>`; its 14-case `Action` sealed interface stays in `:app` as `notebook/NotebookUndo.kt`), plus the design resources (`values/{colors,dimens,styles,themes}`, `values-sw720dp/dimens`, the **37 chrome** `ic_*.xml`, `bg_toolbar_button`, `shape_dialog_bordered`, a module `strings.xml` holding only `ok`). **`ic_launcher_foreground.xml` and every `mipmap-*` stay in `:app`** — the launcher glyph is the host's identity, not shared chrome, and the extension draws its own (the Tabler puzzle, ext-mlkit's recipe). New in the module: `PaperChrome` (no SN equivalent exists — exclusions are inlined in `NotebookActivity`; Paper's is 46 lines), a **binding-free** `PaperToolbar` (Paper's is 61; SN's `NotebookToolbar` is hard-bound to `ActivityNotebookBinding` and is not reusable — it stays in `:app`), and `ic_pencil_down.xml` + `ic_sketching.xml` (SN has neither; both exist in `apps/notesprout_paper/paper-screen/src/main/res/drawable/` as the Tabler reference). g-paper becomes the module's `api(...)` so it reaches the extension transitively; `:app` and `:ext-scratchpad` both depend on it; it must **NEVER** see `:extension-api` — that seam is why `TransferCaps` on the host and `ScratchInk` in the extension are deliberate twin mappings, not one shared class. |
| Extension store | **Full port.** `IExtensionStore` with all six methods (`get`/`put`/`delete`/`keys` + the appended `putLarge`/`getLarge`), `LargeValue` + `SharedBytes` (ashmem), host-side `data/extstore/` (`KvEntity`, `KvDao`, `ExtensionStoreDatabase`, `ExtensionStores`, `ExtensionStoreGate`, `ExtensionStoreBinder`): a per-package SQLCipher Room KV at `Garden/<pkg>.db` under the **global** key, uid-bound per bind, revoked in the same `finally` as the unbind. Caps: key 512 chars · inline 512 KiB · value 4 MiB · 50 000 keys. `data/SoilFile.kt` gains `extensionStoreFile(ctx, pkg)` and stays the only path constructor. Nothing lands in the index or any `.soil`; `Garden/` gains `.db` files beside the `.soil`s — invisible to the library, whose structure is index-only. |
| Pad feel | **Paper parity plus two SN engine flags.** Fixed tools (`PEN_WIDTH_PX 3f`, black, `ERASER_RADIUS_PX 15f`) — but **`smartLassoEnabled = true` and `scribbleEraseEnabled = true`**: SN's notebook has both on, and a pad one tap away that lassos differently reads as a bug. Everything else spartan: no headings, no links, no clipboard, no snap, no Contents, no Recents, no templates, no colour, no debug menu. |
| Entry points | Notebook: right cluster, **immediately LEFT of `btnRecents`** — after `topBarRow`'s weight-1 spacer, before `btnRecents` (`activity_notebook.xml`); the right edge becomes "things that leave this page". Library: **immediately AFTER `btnRecents`**, before the weighted `pager` LinearLayout (`activity_library.xml`'s `bottomBar`). Icon for both: Tabler `sketching` (`ic_sketching`) — Paper's choice. Both GONE unless a trusted extension is installed, re-discovered on every resume. |
| Selection action | The notebook's **"Pad" action is ink-only** — hidden the moment the selection holds a heading or a link, because `WireStroke` is the only thing the contract carries. `SelectionToolbar` goes to **7 buttons in STROKES mode** (Delete · H · Link · Copy · Cut · Snap · Pad = 434 dp of the Nomad's 749 dp). |
| Naming | The wire parcelable is **`WireStroke`**, not Paper's `PaperStroke` — "Paper" is the other app's name and this one is on the wire forever. Modules `:sn-screen`, `:ext-scratchpad`. Package `com.symmetricalpalmtree.notesproutsn.ext.scratchpad` (`.dev` in debug). Label `NSE · Scratch Pad` / `NSE · Scratch Pad Dev` — a build-type string override, not a suffix (the ext-mlkit recipe, verified in its `src/{main,debug}/res`). versionName host lockstep `0.1.0-ratta` (`-dev` in debug). Action strings SN-namespaced: `…notesproutsn.extension.SCRATCH_PAD` and `…SCRATCH_PAD_SCREEN`. |
| Arc shape | **Six phases J1–J6** (below). Each ends green — build + `./gradlew test` + Nomad — so the user can `/clear` between them. |
| Staffing | **The full model recipe returns**: Fable plans, reviews, and writes the contract, store and crypto seams; Opus the substantial feature code; Sonnet scaffolding, layouts, resources, docs; Haiku the Nomad adb walks. Background agents ≤ 5 concurrent. |

### Arc-11 standing traps

Device / process:

- **adb cannot inject stylus ink or multi-finger gestures** on the Supernote; `input text` and
  `input keyevent` letters are swallowed; EPD live ink is invisible to `screencap` (committed
  strokes are visible). Finger `input tap` / `input swipe` work — so buttons, sheets and flips are
  agent-drivable, but every lasso and every stroke is the user's.
- **`am start -n <pkg>/<FQCN>` and verify `dumpsys activity activities | grep mResumedActivity`**
  before trusting any screencap — `monkey` does not reliably foreground the target, and an entire
  device walk once "passed" against the wrong app (R4).
- **`adb push` into `/sdcard/Android/data/<pkg>/files/` fails AND deletes the target** — push to
  `/data/local/tmp`, then `shell cp` (R6).
- **`callingPackage` is non-null only for a `startActivityForResult`-style launch** — the host MUST
  use an `ActivityResultLauncher`, and a plain `am start` from a shell is always refused. A design
  constraint, not a bug (Paper's risk register 2).

EPD / handoff — the arc's headline risk:

- **Two paper surfaces, two processes.** The notebook calls `paper.releaseForHandoff()` immediately
  before launching the pad, and **the pad calls it before EVERY `finish()`** — Back, Send, the
  store-failure dialog — Paper's `finishWithHandoff`. The returning caller reclaims the pipeline in
  its `onResume`, which runs *before* the departing window's visibility close; a close landing after
  that reclaim tears the caller's live session down. **SN's host has never called
  `releaseForHandoff` before** (verified — zero call sites today; it has only ever had one paper
  surface), so J4 is its first use. The API is in g-paper 0.1.6 and the Ratta ownership-token fix
  landed in 0.1.2, so no engine bump is expected — but if the pad's ink daemon does not arm, or the
  notebook's does not re-arm on return, **the fix goes to g-paper, never a host workaround**, and an
  engine commit lands with or before the host commit that pins it (the A1 rule).
- **g-paper's ownership guards are process-local statics** — which is why the departing side's
  `releaseForHandoff()` must be its full teardown (Paper S3's root cause: the pad's late `release()`
  from the other process re-sent teardown to the device-global daemon ~200 ms after the notebook's
  reclaim).

Correctness — Paper's arc, found the hard way:

- Stroke geometry is **zlib-compressed per stroke**, so a move must re-measure the moved strokes
  against the page's running size total — "floats re-encode to the same size" is false and a test
  caught it.
- A page turn must **re-flush until clean** (Paper's `flushUntilClean`): a stroke committed during a
  flush's or a page-swap's IO hop otherwise lands in the departing page's map and is discarded.
- **Back must await the flush before `finish()`** — the host's result callback runs `end()` →
  unbind → revoke immediately, and a save left in flight would hit a revoked binder.
- An undecodable page blob is an honest **"unreadable"** failure — never a blank page saved over it.
- `onResult` must not clear its busy/client state before the async drain completes — a second
  launch mid-drain would `begin()` a new showing and wipe the parked chunks (silent partial paste).
- The **last** `receiveInk` chunk carries the whole placement (read + decode + re-encode + write of
  up to 4 MiB on an e-ink CPU) and **a Binder call cannot be cancelled** — it takes its own ~10 s
  timeout (Paper's `PLACE_TIMEOUT_MS`), not the 2 s of the other calls, or a timeout reports failure
  for ink that lands anyway.
- Only `SecurityException` / `IllegalArgumentException` / `IllegalStateException` survive Binder
  marshalling. Anything else — an `ErrnoException` from ashmem is checked and outside the set —
  kills the transaction **silently** and the caller reads an empty reply as success; in Paper that
  made a page read as blank and then get saved over. Every ashmem step runs inside the gate's
  exception mapping.
- ashmem refuses a zero-size region: an empty value rides a **1-byte region with `byteCount = 0`**.
- A `put` of a 4 MiB `byte[]` exceeds the ~1 MB Binder transaction budget — that is why
  `putLarge`/`getLarge` exist; the inline `put`/`get` path stops at 512 KiB.
- A received selection under the **pen** can neither be dragged nor dismissed — switch to the lasso
  **before** `setSelection`, and restore the prior tool pen-idle when that selection is dismissed
  (Paper S2 round 1; SN's own N2 `pendingSelection` lesson rhymes with it).
- **The extension writes nothing to disk itself, ever** — its data is the host's store (the model
  sandbox is ML Kit's recorded exception, and it does not transfer).
- **The pad opens no `.soil` and the notebook is not sealed behind it.** This is the one way the
  hop differs from arc 10's notebook switch, and the difference is easy to get backwards: a switch
  seals because two live `SoilDatabase`s on one file is a family-wide hard invariant, whereas the
  pad touches no notebook file at all — the notebook stays open, keeps its session, its undo stack
  and its unsaved page, and is still there when the result comes back. What the notebook *does*
  give up before the launch is the **EPD pipeline** (`releaseForHandoff()`), not its data. A pad
  launch that sealed the notebook would throw away the very undo stack J5's paste has to land on.
- `API_VERSION` stays **1**: `putLarge`/`getLarge` are *appended* to `IExtensionStore` — base four
  first, the large pair last, never reordered — the family's compatible-append recipe, kept even
  though SN ships all six methods at once.
- A `.aidl` that takes a parcelable needs an explicit `import` line for it (Paper S0).
- `testOptions.unitTests.isReturnDefaultValues = true` in any module whose tested code calls
  `Slog`/`Log` — `:sn-screen` and `:ext-scratchpad` both need it; `:app` already has it.
- The screen's intent-filter **must carry `<category android:name="android.intent.category.DEFAULT" />`**
  or implicit resolution will not match it (Paper S0).
- A failed open **re-runs discovery** so the entry button hides at once — a package can be disabled
  under you (Paper's post-S1 fix; SN has no BOOX freezer, but `pm disable` exists everywhere).
- The pad's long-press-to-delete rides the shared `PageGestures.Listener`'s existing long-press
  callback — which SN renamed **`onPageSheetRequested`** in B1 (the notebook's sheet outgrew
  delete). The pad implements that, rather than gaining a Paper-named `onDeleteRequested` twin; and
  it leaves SN's other callbacks (`onSwipeDown` Contents, `onTwoFingerSwipeDown` Recents,
  `onSwipeUp` trail walk, `onFingerTap` link follow) as the all-default no-ops they already are.
- `:ext-scratchpad` needs **no** `tools:replace` and **no** libc++ `pickFirsts` — both exist in
  Paper only because the Onyx SDK arrives through `:paper-screen`. SN has no Onyx, so the APK will
  be a fraction of Paper's ~21 MB release (verified against Paper's built release APK).
- The debug extension trusts the **debug** host package (`…notesproutsn.dev`) via a per-build-type
  `HOST_PACKAGE` `buildConfigField` — the ext-mlkit recipe, verified in its build file;
  same-signature trust runs in both directions.
- **Paper's plan appendix holds the pre-S2 transfer caps** (5 000 / 200 000 / 17 chunks); the
  shipped contract is **10 000 / 400 000 / 34** (`apps/notesprout_paper/docs/scratchpad.md`, frozen).
  Copy constants from the frozen doc and the S2 outcome, never the appendix table.

### J1 — `:sn-screen` extraction
**Status:** ✅ Complete (commit 12fe218, Nomad-verified 2026-08-24)

Paper's S0 move on SN's files. `settings.gradle.kts` gains the module; the **pure `git mv`** listed
in the locked decision moves out of `:app` with packages unchanged; `UndoRedoStack` is genericised
to `<A : Any>` and its 14-case `Action` sealed interface stays behind in `:app` as
`notebook/NotebookUndo.kt`; `Slog` gates on the module's own `BuildConfig.DEBUG`
(`buildFeatures.buildConfig = true`); tests travel with their subjects (`UndoRedoStack`'s re-typed
on a test-local action set — Paper's recipe — with the replay shapes staying pinned in `:app`).
Fresh-written in the module, to Paper's shape but SN's code: `PaperChrome` (`extraRects` /
`extraContains` suppliers; `NotebookActivity` keeps its own inline `pushExclusions` untouched this
phase — adopting the helper in the notebook is not this arc's business) and the binding-free
`PaperToolbar`, plus the two drawables `ic_pencil_down.xml` / `ic_sketching.xml` drawn from the
Tabler originals. g-paper moves to `:sn-screen` as `api(...)` — the two `implementation` lines
leave `app/build.gradle.kts`, the pin stays **0.1.6** — and `:app` depends on the module.
**`gradle.properties` gains `android.nonTransitiveRClass=false`** — checked: SN does *not* set it
today (the file has four lines, none R-related), and AGP 8.11's default is non-transitive, so
without the line every moved resource falls out of `:app`'s `R` (Paper's S0 Q1, same answer).
**Gate:** all **489** JVM tests green and unmoved in behaviour (moved suites run in their new
home); debug + release build; Nomad install + a Haiku smoke walk proving the notebook and library
are visually and behaviourally identical (the move is pure); `logcat -b crash` empty.
*Sonnet drives the mechanical move and the module scaffold; Fable writes `PaperChrome` /
`PaperToolbar` and reviews the seam; Haiku the Nomad walk.*

**Questions to resolve at phase start:** none. (`core/Immersive.kt` was missing from the wizard's
move list and **joins the move in J1** — 23 pure lines the pad needs in J4, and splitting a trivial
move across two phases buys nothing. `core/OpeningOverlay.kt` does **not** move: it is the host's
tap-feedback-then-launch helper, which the notebook and library need when launching the pad and the
pad itself never uses — the pad's own "Opening…" box lives in its own layout, as Paper's does.)

**Outcome:** the move landed pure — of the 81 changed files, **every moved source and resource is
byte-identical**; only three `R`/`BuildConfig` import lines changed, plus the deliberate splits and
the four fresh files. `:sn-screen` namespace `…notesproutsn.screen` (a library sharing the app's
namespace would collide on `R`/`BuildConfig`), Kotlin packages unchanged — which is why no import
sweep was needed anywhere in `:app`. `assembleDebug`, `assembleRelease` and `test` green on the
first pass; **490** JVM tests (`:app` 432 + `:sn-screen` 58), one more than the 489 baseline because
the undo split gained a test rather than losing one: the generic suite kept a two-kinds-side-by-side
case of its own while `:app`'s new `NotebookUndoTest` took the three notebook-shaped ones. Resource
identity checked against the built APK with `aapt2 dump resources` — all 37 moved icons, the theme,
the widget styles and both tiers' `dimens` present, plus the two new drawables.

Deliberate deviations from the phase text, all forced by the dependency closure:
- **Six more drawables moved than the wizard listed** — `btn_elevated_background`, `shape_bordered`,
  `bg_selectable_card`, `radio_selector`, `radio_checked`, `radio_unchecked`. A library's own
  `styles.xml` cannot reference a resource that exists only in the consuming app, and the moved
  `Widget.Notesprout.*` styles reference all six. (Paper's `:paper-screen` holds the same set — the
  closure is the same shape there.) Everything they in turn reference is `colors.xml`, which moved.
- **The module `strings.xml` holds `cancel` as well as `ok`** — SN's `ActionSheetDialog` sets the
  close-X's content description from `R.string.cancel`, where Paper's does not. Both were removed
  from `:app`'s `strings.xml` rather than left duplicated.
- **`UndoRedoStack.generation` travelled with the generic stack** (Paper's has no such counter): the
  mid-replay protocol is SN's, and it is stack mechanics, not action shape.

Verified on the Nomad by hand (the walk is finger-drivable end to end; no ink was needed, because
everything under test is chrome): library grid and both bars render identically · notebook opens
with committed ink, heading and page indicator · a tool arm shows the bordered `state_selected`
look · Contents panel · Recents panel · the long-press page sheet (`ActionSheetDialog` +
`shape_dialog_bordered` + the moved icons) · a swipe past the last page inserts one (`PageGestures`
from the module) · Delete-page's `AlertDialog` keeps the bordered window and the mixed-case
non-accent buttons that `themes.xml`'s un-prefixed `buttonBar*` attrs buy · the library's own action
sheet. `logcat -b crash` empty throughout; `mResumedActivity` checked against `…notesproutsn.dev`
before every conclusion. Version stays `0.1.0-ratta`. New reference doc: `docs/sn-screen.md`;
`CLAUDE.md`'s module rule now reads "Four modules" and names `nonTransitiveRClass=false` as
load-bearing.

### J2 — Contract + extension store (host only)
**Status:** ✅ Complete (commit cd8a918, Nomad-verified 2026-08-24)

`:extension-api` gains `IExtensionStore.aidl` — all six methods, the base four
(`get`/`put`/`delete`/`keys`) first and `putLarge`/`getLarge` appended last (the trap above) —
plus `LargeValue` (SharedMemory + `byteCount`, `describeContents = CONTENTS_FILE_DESCRIPTOR`,
`requireValid` accepting `0..STORE_MAX_VALUE_BYTES`), `SharedBytes` (the ashmem handshake written
once for both sides), and the `STORE_*` constants. `:app` gains `data/extstore/`: `KvEntity` /
`KvDao` / `ExtensionStoreDatabase` (its own Room DB — one `kv(key, value, updatedAt)` table, no
index-hash impact), `ExtensionStores` (open-or-create on IO, process-lifetime cache — a named
create entry point beside `SoilDatabase.create`, under the global key through `SoilCrypto`),
`ExtensionStoreGate` (pure, `byte[]` in/out, **no Android types precisely so it is JVM-testable**),
and `ExtensionStoreBinder` (uid-bound per bind; every method checks calling uid + revoked; the
ashmem copy in/out runs inside the gate's exception mapping; revoked in the same `finally` as the
unbind). `data/SoilFile.kt` gains `extensionStoreFile(ctx, pkg)` — still the only path constructor.
No pad, no new extension, no `<queries>` change yet. Verified by a **debug-only library ⋯
"Extension store self-test"** (Paper's probe): open-or-create a `probe.test` store, round-trip
through a real `ExtensionStoreBinder`, verify the file header is encrypted, prove the wrong-uid and
revoked refusals, a 4 MiB `putLarge`/`getLarge` round trip, the inline cap, and `get`'s
`STORE_VALUE_LARGE` refusal.

**This phase amends two `apps/notesprout_ratta/CLAUDE.md` standing rules, by name:** ① the arc-3
extension rule — "the recognizer point is SN's ONE extension surface … no other capability point
may be added without a new user decision. **No extension stores.**" The arc-11 wizard's first
answer *is* that user decision: the rule now names two points, and the no-stores sentence falls.
② the SoilFile rule — "`data/SoilFile.kt` is the only path constructor. **No `extensionStoreFile`
here.**" The function exists now; the rule becomes "still the only path constructor,
`extensionStoreFile` included."
**Gate:** JVM tests for `ExtensionStoreGate` (inline cap, large cap, `get`-above-inline throws,
key-count cap on both puts) + whatever crypto-seam pins Fable deems load-bearing; debug + release
build; Haiku Nomad walk: self-test all-OK, the probe store's `.db` header encrypted, crash buffer
empty.
*Fable writes the store, gate, binder and crypto seam plus the self-test; Sonnet the CLAUDE.md and
doc touch-ups; Haiku the walk.*

**Questions to resolve at phase start:** none — the store's rules are Paper's, and everything
wire-visible is locked above.

**Outcome:** landed as written — eleven new files, no deviations from the phase text. `:extension-api`
gained `IExtensionStore.aidl` (base four first, `putLarge`/`getLarge` appended last), `LargeValue.aidl`,
`LargeValue.kt`, `SharedBytes.kt` and the six `STORE_*` constants; `:app` gained `data/extstore/`
(`KvEntity`, `KvDao`, `ExtensionStoreDatabase`, `ExtensionStores`, `ExtensionStoreGate`,
`ExtensionStoreBinder`) and `data/SoilFile.kt`'s `extensionStoreFile` + `isValidExtensionPackage`.
`API_VERSION` stays 1. Both `CLAUDE.md` rules amended by name, and `docs/extensions.md` gained a
full § "The extension store" (caps table, the ashmem handshake, the three-marshalable-exceptions
rule, the host-side table, the pre-open rule, verification).

**Tests: 544 JVM** (`:app` 445 · `:sn-screen` 58 · `:extension-api` 12 · `:ext-mlkit` 29) — up 19
from J1's baseline on the same two modules (`:app` 432 → 445, `:extension-api` 6 → 12).
`ExtensionStoreGateTest` (11) drives every check and cap over a fake `KvDao` with an injectable
calling uid — uid and revoke on **all six** methods, both value caps, `get`-above-inline throwing the
exact `STORE_VALUE_LARGE`, the key-count cap rejecting a new key while still allowing a replace, the
literal case-sensitive prefix, and a DAO failure becoming `IllegalStateException`. `LargeValueTest`
(5) pins the unmarshal validation including the 1-byte-region / `byteCount 0` rule; `SoilFileTest`
(2) pins the package-name guard against `../` and `/`; `ExtensionContractTest` gained a
`storeConstants` case. `assembleDebug` + `assembleRelease` green.

**Nomad walk (by hand — the whole path is finger-drivable):** ⋯ → "Extension store self-test" →
`Extension store: OK`. Both open paths exercised, in the order that proves the pre-open rule matters:
**cold create 2004 ms** (`created store for probe.test` — the native KDF) then, after a `force-stop`
and relaunch, **open 171 ms** via the cached raw key (`KeyOpener: raw-key open: ext:probe.test`) —
a 12× difference, which is exactly why a caller opens the store on IO *before* it binds. 4 MiB
round trip 373–379 ms through real ashmem both ways. File encryption confirmed **independently of
the app's own probe**: `head -c 16 …/Garden/probe.test.db | xxd` reads `15ed 7b8b 9865 4aec …`, not
`SQLite format 3`. `logcat -b crash` empty; no E/W from our code.

Two notes for J3+:
- The probe store (`Garden/probe.test.db`) is **left in place** on the dev device, as Paper leaves
  its own. That is the store's lifecycle rule showing itself: a store outlives the extension it
  belongs to, because removing an extension's data is a deliberate act, not a side effect.
- `ExtensionStoreBinder.putLarge` copies the caller's region in and closes the host's handle
  **before** the gate's uid / cap check. Deliberate (and Paper's shape): the handle must be closed
  whatever the gate then says, and the bytes are the caller's own, so a refused call wastes a copy
  but leaks nothing.

### J3 — Held bind + client + extension skeleton
**Status:** ✅ Complete (commit c7c83b5, Nomad-verified + user-verified 2026-08-24)

Host: `ExtensionBinder.hold` + `HeldBinding<I>` — the bind half of `call` without the unbind (same
timeout and exception mapping, `isDead` after `onBindingDied`/`onServiceDisconnected`, idempotent
`close()`; SN's binder today is strictly bind-per-call — verified, no `hold` exists);
`ExtensionRegistry.scratchPad` (the first trusted `ACTION_SCRATCH_PAD` service, a second installed
pad ignored); `ScratchPadClient` with its full surface — `open` (store pre-open on IO → hold →
`begin(store)` ≤ 2 s → the screen Intent) / `send` / `drainOutgoing` / `finish` (`end` → unbind →
revoke in one `finally`, idempotent, called from the result callback *and* the caller's
`onDestroy`) — of which only `open`/`finish` are exercised until J5; pure `TransferCaps` (limits,
chunk delegation, `sanitize` — unknown style → PEN, width clamped 0.5..50 px — and the wire ⇄
g-paper mappings, ids dropped outward and minted inward). Contract: `IScratchPad.aidl`
(`begin` / `receiveInk` / `takeOutgoing` / `end`), **`WireStroke`** + `InkBundle` (hand-written
parcelables, `requireValid` at unmarshal — a malformed stroke rejects the whole bundle),
`InkChunks` (the per-call chunking rule written once for both sides), `HostCallerCheck.enforceActivity`
(the sibling of `enforce`), and the transfer constants at **Paper's shipped values** —
`MAX_TRANSFER_STROKES` 10 000 · `MAX_TRANSFER_POINTS` 400 000 · chunks 300 / 20 000 ·
`TRANSFER_MAX_CHUNKS` 34 · `PLACEMENT_NEW_PAGE`/`PLACEMENT_CURRENT_PAGE` · `RESULT_SCRATCH_SEND` ·
`EXTRA_SCRATCH_SEND_ENABLED` / `EXTRA_SCRATCH_OPEN_RECEIVED` · `SCRATCH_PAGE_FULL` — SN-namespaced.
The `:ext-scratchpad` APK: `ScratchPadApplication` (**`RattaEngine.register()` only** — no Onyx in
SN), `ScratchPadService` (`HostCallerCheck.enforce` first in every method; `begin`/`end` real, the
transfer pair throwing `UnsupportedOperationException` until J5), `ScratchSession`, `ScratchStore`
(key layout `pages` / `current` / `page/<id>`; `readPage` tries `get` and falls to `getLarge` on
`STORE_VALUE_LARGE`; the 4 MiB full rule), `ScratchPageCodec` (pure — header + `StrokeCodec`
format-B strokes; a truncated tail drops the partial stroke, an unknown version is unreadable),
`ScratchPages` (pure id-list math over the shared `PageMath`) — with the Activity a **stub**
(caller-checked title + Back) that proves `enforceActivity` and returns; the real screen is J4.
Identity per the locked naming, Tabler puzzle icon at the family scale, no launcher activity,
per-build-type `HOST_PACKAGE`. Host `<queries>` gains both new actions. The stub is reached through
a **debug-only library ⋯ row** driving the real `ScratchPadClient.open` path (Paper's "Probe
scratch pad" precedent — removed in J4 when the real buttons land), and a one-time debug
**cross-process 4 MiB store round trip** runs from `begin` (Paper's S0 measured 917 ms on the
Nomad, inside the 2 s budget) — verified, then removed, exactly as Paper did.
**Gate:** JVM tests (parcelable round trips + `requireValid` rejections, `InkChunks`,
`TransferCaps`, `ScratchPageCodec`, `ScratchPages`, contract-constant pins); all five modules build
debug + release; Haiku Nomad walk: discovery finds the extension, the debug row opens the stub and
returns (`begin` → `pages=1` on first run → `end` → unbind; binds = unbinds, no lingering service
in `dumpsys activity services`), the store `.db` created encrypted, the cross-process 4 MiB probe
passes, and `am start` of the screen from a shell is **refused** (`refused caller (none)`); crash
buffer empty.
*Fable writes the AIDL contract, `hold` and the trust seam; Opus the extension skeleton and the
client; Sonnet the module scaffold, manifest, icon, strings.*

**Questions to resolve at phase start:** none expected — the contract is Paper's shipped shape
under SN names; ask only if a wire detail surfaces that the frozen Paper docs disagree on.
*(None arose — nothing in the frozen Paper docs disagreed with itself.)*

**Outcome:** landed as written; one deviation, noted below. **`:extension-api`** gained
`IScratchPad.aidl` (with the explicit `import` lines both parcelables need), `WireStroke` +
`WireStroke.aidl`, `InkBundle` + `InkBundle.aidl`, `InkChunks`, `HostCallerCheck.enforceActivity`,
and the twelve scratch constants at Paper's shipped values. `API_VERSION` stays **1**.
**`:app`** gained `ExtensionBinder.hold` + `HeldBinding` (SN's first held bind — it had none;
verified), `ExtensionRegistry.scratchPad`, `TransferCaps`, `ScratchPadClient`, both new `<queries>`
intents, and a debug library ⋯ row **"Probe scratch pad"** that drives the real client path end to
end (store pre-open → hold → `begin` → **launch for a result** → `end` → unbind → revoke).
**`:ext-scratchpad`** is new: `ScratchPadApplication` (`RattaEngine.register()` only),
`ScratchPadService`, `ScratchSession`, `ScratchStore`, `ScratchPageCodec`, `ScratchPages`,
`ScratchInk`, and the stub `ScratchPadActivity` (caller-checked title + Back). Release APK **6.7 MB**
— against Paper's ≈ 25 MB, exactly the predicted Onyx tax it does not pay; no `tools:replace`, no
`pickFirsts`.

**The one deviation:** `TransferCaps.sanitize` has **no NaN-width branch** (Paper's does). It is
unreachable — `WireStroke.requireValid` runs in the constructor, which is also where unmarshalling
lands, and it already rejects a non-finite or non-positive width. A test pins that
(`assertThrows` on constructing one) rather than leaving a branch nothing can reach.

**Tests: 590 JVM** (`:app` 455 · `:sn-screen` 58 · `:extension-api` 29 · `:ext-mlkit` 29 ·
`:ext-scratchpad` 19) — up 46 from J2. `WireStrokeTest` (4) and `InkBundleTest` (6) pin the
`requireValid` rules including the one that matters most — a single stroke over the point chunk cap
is a legal chunk of one, because the chunker never splits a stroke; `InkChunksTest` (6) drives the
chunker and pins that `TRANSFER_MAX_CHUNKS` really is `ceil(MAX_STROKES / CHUNK_STROKES)` (a
too-small budget would silently truncate a legal maximum transfer); `TransferCapsTest` (10) covers
both mappings and every `Drain` exit — empty chunk, stroke cap, and the probe chunk past the budget
being refused **whole**; `ScratchPageCodecTest` (7) pins the exact `strokeBytes` running total the
4 MiB full rule depends on, plus the truncated-tail and unknown-version rules;
`ScratchPagesTest` (8) and `ScratchInkTest` (4) the rest. `assembleDebug` + `assembleRelease` green
across all five modules.

**Nomad walk (agent-free, by hand — the whole path is finger-drivable):**
- Discovery: the "Probe scratch pad" row appears only with the extension installed —
  `pm disable-user` makes it **vanish**, `pm enable` brings it **back**, because discovery re-runs
  on every sheet open.
- The sequence, exact: `hold` → `ScratchPadService begin: pages=1 in 47–57 ms` (first run creates
  the page) → screen `opened (J3 stub)` → Back → `ScratchPadService end` → `finish: end ok` →
  `unbind … (held)`. `dumpsys activity services <pkg>` = **(nothing)**; binds = unbinds.
- **The pre-open rule, measured:** cold `open` **3 123 ms** (SQLCipher's KDF creating
  `Garden/…ext.scratchpad.dev.db`) vs. warm **114 ms** — 27×. That gap is the whole reason a caller
  opens the store on IO *before* it binds.
- Store file created **encrypted**, checked independently of the app's own probe:
  `head -c 16 …/Garden/com.symmetricalpalmtree.notesproutsn.ext.scratchpad.dev.db | xxd` reads
  `7fd5 ad06 662d 8925 …`, not `SQLite format 3`.
- `am start` of the screen from a shell is **refused**: `D ScratchPadActivity: refused caller (none)`,
  nothing shown, the Supernote's own app comes straight back.
- Labels verified in both APKs (`aapt2 dump strings`): `NSE · Scratch Pad Dev` / `NSE · Scratch Pad`.
  versionName `0.1.0-ratta-dev`.
- J2 regression: the store self-test still reports `Extension store: OK (open 176ms, 4 MiB round
  trip 396ms, probe.test.db)`.
- `logcat -b crash` **empty** throughout; no E/W from our code (every warning in the buffer is
  SurfaceFlinger / WindowManager / NotificationService).

**The J3 question, answered and then removed:** a once-per-process debug `StoreProbe` ran the
cross-process 4 MiB `putLarge` / `getLarge` round trip from `begin` — ashmem over a **real** Binder,
which the host's in-process self-test never exercises. **916 ms on the Nomad** (Paper measured 917 ms
on the same device), comfortably inside `begin`'s 2 s budget. Removed in this same phase, as Paper
removed its own: left in, it would sit inside the first pad open of every session and muddy J4's
timings. Its numbers live here and in `docs/extensions.md`.

**Carried into J4:**
- The debug "Probe scratch pad" row is **removed** when the two real entry buttons land, exactly as
  the phase text says.
- SN has still **never** called `releaseForHandoff` — J4 remains its first use, and the arc's
  headline risk is untouched by J3 (the stub screen hosts no paper surface).
- `receiveInk` / `takeOutgoing` throw `UnsupportedOperationException` until J5. That **is** Binder-
  marshalable (`EX_UNSUPPORTED_OPERATION`), despite the arc's trap list naming only three
  exceptions — the trap's list is the conservative set this contract uses, not Binder's full one.
  `docs/extensions.md` now records the distinction so the next reader does not "fix" a working throw.

**User checklist — all three pass (2026-08-24):** 1. Settings → Apps shows **"NSE · Scratch Pad Dev"**
with the puzzle icon, next to "NSE · ML Kit Dev". 2. Library ⋯ → "Probe scratch pad" opens a
white screen with a Back arrow flush at the top-left and the title "Scratch Pad", and Back returns
to the library. 3. Nothing else in the library or the notebook looks different.

### J4 — The pad screen + both entry buttons
**Status:** ✅ Complete (commit 1187f29, Nomad-verified + user-verified 2026-08-24)

The extension's screen, the notebook's shape from `:sn-screen`: `ScratchPadActivity` (caller check
first thing in `onCreate`, before anything is inflated; full-bleed `GPaper.create` in the
extension's own process; immersive, chrome flush at the top edge — TopGuard is 0 on Ratta; top bar
**Back · "Scratch Pad" · [Send]**, bottom bar **Pen · Eraser · Lasso … `<` · `n / N` · `>`** with
arrows that no-op at a bound, never disabled; chrome geometry through `PaperChrome`, the whole
paper one exclusion rect under an "Opening…" overlay until the first page is on it; strip text
pen-idle-gated — the frame-silence rule is SN-wide), `ScratchToolbar`, `ScratchSelectionToolbar`
(the floating Delete bar over a lasso selection, anchored by the shared `SelectionAnchor`),
`ScratchDocument` (the pages in memory + persistence over `ScratchStore`: `load` / `goTo` /
`insert` / `deleteCurrent` / `flush`, the mutations, the undo replay, the running **exact** encoded
size for the 4 MiB full rule, the re-flush-until-clean page turn), `ScratchUndo` (Drew · Erased ·
Moved · Page — `Pasted` arrives in J5), `activity_scratch_pad.xml`, strings. Tools per the locked
decision — PEN · black · 3 px, eraser 15 px, **smart lasso and scribble erase ON**, nothing else.
Pages: one-finger swipe flips and past-the-last inserts, two-finger horizontal inserts before /
after, multi-finger double-tap undo / redo, long-press → delete sheet (the last page is emptied,
never removed); the current page id persists so the pad reopens where it was left. Saves: 800 ms
debounce + flush on page leave / `onPause` / **Back awaited before `finish()`**. Host:
`notebook/ScratchPadFlow` (button visibility — GONE unless a trusted extension is installed,
re-discovered on every resume and after a failed open; busy guard; **`paper.releaseForHandoff()`
immediately before the launch — SN's first use of that API, ever**; any result → `client.finish()`)
and `library/ScratchPadLaunch` (no send target, `close()` from an `IndexGuard.bounced`-guarded
`onDestroy`); the two buttons land at the locked slots, both `ic_sketching`. The pad's every exit
goes through `finishWithHandoff` (`releaseForHandoff()` then `finish()` — Back now; Send and the
store-failure dialog when they exist). **No transfers: the Send buttons do not exist in this
phase** (not Paper's S1 visible no-ops — SN's GONE-never-disabled rule extends to not-built). The
J3 debug row is removed.
**Gate:** JVM tests (`ScratchDocument` over a fake store — the full rule, gap ink kept on a page
turn, the unreadable-blob case; `ScratchUndo` replay); debug + release build; Haiku Nomad walk
(buttons present, gone after `pm disable-user` + resume; the pad's chrome in a dump; finger flips
and inserts; the delete sheet; Back; binds = unbinds; `am start` still refused); **user eye
check** — write, flip, insert, delete a page, undo/redo, the full rule, persistence across an app
restart **and a process death**, and above all **the handoff both ways**: ink lands on the pad, and
the notebook's pen is live the instant it returns — live EPD ink is invisible to screencap, so this
is the user's eye and the arc's headline risk. A handoff failure stops the phase and goes to
g-paper.
*Opus builds the screen and host flows against Fable's written contract; Sonnet layouts and
strings; Fable reviews the handoff seam and the flush ordering.*

**Questions to resolve at phase start:** the delete sheet's confirm wording — Paper asks "Delete
this page and its ink?", SN's notebook uses the bare "Delete this page?" because undo restores the
ink (the R4 lesson) and the pad's structural undo restores ink too; recommend the bare SN wording —
confirm.
*(Answered: the **bare SN wording**, "Delete this page?" — the two surfaces read identically and the
pad's structural undo puts the page **and its ink** back.)*

**Outcome:** landed as written, with three deviations noted below. **`:ext-scratchpad`** gained the
real screen: `ScratchPadActivity` (caller check first statement, full-bleed `GPaper.create` in the
extension's own process, immersive, `PaperChrome` exclusions with the whole paper blocked under the
"Opening…" box until the page lands, `PageGestures` for the finger vocabulary, `UndoRedoStack<ScratchAction>`,
the 800 ms debounce, `exit()` → flush-under-the-lock → `finishWithHandoff`), `ScratchToolbar` (the
fixed tools + `:sn-screen`'s `PaperToolbar` + the page arrows + the pen-idle-gated indicator),
`ScratchSelectionToolbar` (Delete alone, `SelectionAnchor`-placed), `ScratchDocument` (pages in
memory over `ScratchStore`: load / goTo / insert / deleteCurrent / flushUntilClean, the three
mutations, the replay, the running **exact** encoded size), `ScratchUndo` (Drew · Erased · Moved ·
Page), `activity_scratch_pad.xml` and the strings. **`:app`** gained `extension/ScratchPadEntry`
(visibility · busy guard · the "Opening…" wait · `beforeLaunch` · the bind's life), the two buttons
at the locked slots (both `ic_sketching`, both GONE by default) and their strings; the J3 debug
"Probe scratch pad" row is **removed**.

**The headline risk is answered — the handoff works, both ways, first try.** SN's first-ever
`releaseForHandoff` call, and the log reads exactly as the trap says it must:

```
ScratchPadClient: open: begin ok in 35 ms      ← host
GPaperRatta: enableFullUiAuto(false) ok        ← host (15251)
GPaperRatta: firmware ink released for handoff ← host, immediately before the launch
GPaperRatta: firmware ink session claimed      ← pad  (15382)
…Back…
GPaperRatta: firmware ink released for handoff ← pad,  .036
ScratchPadActivity: finishing (handoff released)
GPaperRatta: firmware ink session claimed      ← host, .120 — the caller reclaims
ViewRootImpl[ScratchPadActivity]: Change to Gone ← the pad's window closes, .209, AFTER the reclaim
```
The departing window's visibility close lands **after** the caller's reclaim, which is the exact
ordering Paper's S3 root cause turns on. No g-paper change was needed; the pin stays **0.1.6**.

**Three deviations from the phase text:**
- **One entry class, not two.** The phase named `notebook/ScratchPadFlow` and `library/ScratchPadLaunch`;
  they came out ~90 % identical, so this is one `extension/ScratchPadEntry` with a `beforeLaunch`
  lambda — the notebook passes `paper.releaseForHandoff()`, the library passes nothing. Two
  near-identical files is the sibling-copy trap `:sn-screen` exists to keep out of this app.
- **The long-press goes straight to the confirm**, with no one-row sheet in between. The notebook's
  sheet has three or four rows and earns itself; the pad has exactly one page action, and a sheet
  whose only row leads to a confirm is two taps for one decision. It rides the same recorded
  frame-silence exception ("the delete-page sheet at long-press").
- **The host's "Opening…" box rides the C1 exception rather than adding one.** It is the same act as
  the Contents and Recents buttons — a deliberate chrome tap that raises a full-screen thing, after
  `dispatchTouchEvent` has already released the render — and it is genuinely needed: a **cold** open
  is 3 123 ms on the Nomad against 114 ms warm.

**Tests: 605 JVM** (`:app` 455 · `:sn-screen` 58 · `:extension-api` 29 · `:ext-mlkit` 29 ·
`:ext-scratchpad` 34) — up 15 from J3. `ScratchDocumentTest` (15) drives a `FakeExtensionStore` (a
plain map behind the real `IExtensionStore` — implementable on the JVM because it is an interface)
and pins the arc's three correctness rules directly: **re-flush until clean** (the fake's `onPut`
hook drops a stroke into the very window a flush's IO hop opens, and the second pass writes it),
**the full rule** (incompressible strokes until `PAGE_FULL`, then the held page is re-encoded and
proved to sit inside the 4 MiB cap — the running total is the encoder's own answer, never assumed),
and **an unreadable page is never written over** (a damaged blob is byte-identical after a flush).
Plus both directions of every action, including the two that are easy to get wrong: an erase comes
back **in place** (indices, not an append), and a `Moved` revert leaves the store holding exactly what
`ScratchPageCodec.encode` produces for the reverted geometry — the zlib re-measure, proven rather
than asserted. `assembleDebug` + `assembleRelease` green across all five modules on the first pass;
the pad's release APK is **6.5 MB**.

**Nomad walk (agent-free, by hand — chrome, pages and the handoff are all finger-drivable):**
- **Library**: "Scratch pad" sits at `[232,1748]`, immediately after Recents and before the pager.
  **Notebook**: at `[1164,7]`, immediately left of Recents. Both GONE without the extension:
  `pm disable-user` makes them vanish on the next resume, `pm enable` brings them back on the one
  after — discovery re-runs every time.
- **The pad's chrome, dumped**: Back · "Scratch Pad" on top; Pen (selected) · Eraser · Lasso on the
  left of the bottom bar, `<` · `1 / 1` · `>` on the right.
- **Pages**: both arrows **no-op at a bound** (1 / 1 stayed 1 / 1 on either arrow, and 2 / 2 stayed
  2 / 2 on Next) and never disable; a one-finger swipe past the last page inserted one (2 / 2); the
  arrows walk 1 / 2 ⇄ 2 / 2; long-press raises **"Delete this page?"** with mixed-case
  Cancel / Delete, and the delete lands on the previous page.
- **The bind**: `hold` → `begin: pages=1 in 9–50 ms` → screen → Back → `finishing (handoff released)`
  → result → `end` → `finish: end ok` → `unbind … (held)`.
  `dumpsys activity services <pkg>` = **(nothing)**; binds = unbinds.
- **The double tap in the e-ink gap produces one showing** — one `hold`, one `begin`. The guard is
  latched at the tap, not when the client lands (see the fix note below).
- **Persistence**: three pages, left on page 2, `am force-stop` of **both** processes, relaunch →
  the pad reopens on **2 / 3**.
- `am start` of the screen from a shell is still **refused** (`refused caller (none)`), and the store
  file is still encrypted (`head -c 16 | xxd` → `7fd5 ad06 662d 8925 …`).
- `logcat -b crash` **empty** throughout; no E/W from our code (every line in the buffer is
  SurfaceFlinger / WindowManager).

**One bug found and fixed in self-review, before the device walk's second pass:** the entry's busy
guard watched the client slot, which is filled *asynchronously* — a pre-draw hop, then the store and
the bind. A second tap inside that window (which on e-ink is the normal thing to do) would have
started a second showing. The guard is now latched synchronously at the tap and released with the
result or the failure; the double-tap probe above is what pins it.

**Carried into J5:** `receiveInk` / `takeOutgoing` still throw `UnsupportedOperationException`; the
pad's Send buttons and the notebook's 7th selection button do not exist yet (GONE-never-disabled
extends to not-built); `ScratchAction` has no `Pasted`; `ScratchPadEntry.onResult` and
`ScratchPadClient.send` / `drainOutgoing` are wired but unexercised.

### J5 — The two transfers
**Status:** ✅ Complete (commit cc0ba79, Nomad-verified + user-verified 2026-08-25)

Both directions are **copies**, cross **only through the held service** — never the Intent, never a
file — carry **no ids** (fresh ids minted on the receiving side), and keep **coordinates 1:1**
(the pad page and the notebook page are both this device's screen; a cross-size page is clipped
like any other ink). *Notebook → pad (`receiveInk`):* the selection toolbar's ink-only **Pad**
action (the 7th STROKES button, per the locked decision) → the **New page / Current page**
placement sheet (`ActionSheetDialog`) → caps checked **before any bind** → `open` → the chunks
(`placement` + `last` on each) → toast → the screen launched with `EXTRA_SCRATCH_OPEN_RECEIVED`.
The service re-checks the running totals, mints ids (`ScratchInk` — the extension's own mapping),
and places on the Binder thread through `ScratchStore.receive`: New page = inserted after the
current one at the bundle's size, Current page = appended keeping its own size; the target becomes
`current` so the screen opens on it; the full rule refuses the **whole** placement
(`SCRATCH_PAGE_FULL` → the host's page-full dialog, the pad not opened). The received record is
consumed once: the screen switches to the **lasso before `setSelection`**, selects the strokes,
restores the prior tool pen-idle at dismissal, and records **one step** on the pad's stack — a New
page placement as `Page` (undo removes the page with its cargo), a Current-page one as `Pasted`
(undo removes exactly what arrived). *Pad → notebook (`takeOutgoing`):* the top-bar **Send** = the
whole current page, the selection bar's **Send** = the lasso's strokes — both `ic_pencil_down`,
both existing only when opened from a notebook (`EXTRA_SCRATCH_SEND_ENABLED`); an empty pick → the
nothing-to-send dialog, never silence. The page is flushed first (the pad keeps its ink), the
chunks parked, `RESULT_SCRATCH_SEND`; the host drains on the still-held bind (empty bundle, summed
caps, or the chunk budget — a cut drain says so), sanitizes, mints ids and pastes into the notebook
as **one undo step**, appended after the destination page's current max `"order"` with relative
order preserved (the arc-8 rebase rule — writing order is load-bearing), landing **selected** with
the lasso armed; only then `finish`. Failures on the B3 pattern: over-cap, a failed write, page
full, store gone are dialogs; toasts only confirm. No new frame-silence exception is expected —
the placement sheet rises from a selection-toolbar tap, the O1/C1 pattern; a surprise needs the
standing written justification.
**Gate:** JVM tests (`TransferCaps` chunk/limits/sanitize both ways, `ScratchInk`,
`ScratchStore.receive` incl. the full-rule-leaves-nothing-behind case, the drain's truncation, the
undo arms both sides); debug + release build; Haiku's reach is short — **adb cannot lasso or
ink** — so the agent covers regression + the refusal probes; **user eye check** — both directions
with real ink, coordinates 1:1, undo on the pad removes what arrived (both placements), undo in the
notebook removes what was pasted, the truncation and page-full paths, and the handoff still clean
across a Send exit.
*Fable writes the transfer contracts, `ScratchStore.receive` and the undo arms; Opus the flows on
both sides; Sonnet strings and icons.*

**Questions to resolve at phase start:** the notebook-side undo arm — reuse arc-8's
`Action.ObjectsPasted` (a scratch paste is a strokes-only object paste) or a strokes-only twin
(Paper's `Pasted`); and whether the paste-back confirms with a toast (arc-8's "Pasted" precedent)
or the landing selection is confirmation enough.
*(Answered: **reuse `Action.ObjectsPasted`** with empty heading and link lists — a scratch paste is
a strokes-only object paste, the direction and the rows are the same, and both existing replay arms
already no-op on the empty lists; no 15th kind, no new arm. And **the toast, in arc-8's words**
(`objects_pasted_toast` = "Pasted") — a paste is a paste and confirms the same way whichever source
it came from; a cut drain overrides it with its dialog.)*

**Outcome:** landed as written, and **both directions worked on the Nomad first try** — the whole
round trip is finger-drivable, because a paste from the pad lands *selected*, which is the very
selection the notebook's Pad button needs.

**`:extension-api`** unchanged — J3's contract was complete, which is the point of having written it
first. **`:ext-scratchpad`**: `ScratchPadService.receiveInk` / `takeOutgoing` are real (the two J3
`UnsupportedOperationException` throws are gone) — chunks accumulate under `ScratchSession`'s one
monitor with the **running totals re-checked** as they go, and the last chunk mints ids and places
through `ScratchStore.receive` on the Binder thread; `ScratchAction` gained **`Pasted`** and its
`Page` gained a second blob (`afterBlob`) so one shape now covers three acts — insert (blank both
sides), delete (ink on the `before` side) and **a received new page** (ink on the `after` side),
which the J4 shape could not express because its redo always dropped the blob; `ScratchDocument`
gained `addStrokes` (all-or-nothing under the full rule), `encodeCurrentPage` and the two `Pasted`
arms; `ScratchPadActivity` gained `sendEnabled`, both Send buttons (top bar = the page, selection bar
= the lasso, both `ic_pencil_down`, both **absent** without a notebook behind them),
`send()` (flush under the lock → park the chunks → `RESULT_SCRATCH_SEND` through `finishWithHandoff`,
which now takes the result code) and **`consumeReceived()`** — the one-shot handover, cleared before
anything can fail. **`:app`**: `ScratchPadEntry` grew the outbound `Send` payload (chunked off Main,
handed over on the held bind **before** the launch, a `SCRATCH_PAGE_FULL` stopping the whole thing
because nothing was placed) and the inbound drain (on the bind that is **still held**, `opening`
released only after it); `SelectionToolbar` is **7 buttons in STROKES mode** with the ink-only Pad
last; `NotebookSession.pasteStrokes` writes the rows in one transaction with `"order"` rebased inside
it; `NotebookActivity` gained the placement sheet, the caps gate **before any bind**, and the paste.

**Both directions, from the device log, in one sitting:**

```
ScratchPadActivity: send: 9 strokes in 1 chunks          ← pad → notebook
GPaperRatta: firmware ink released for handoff             pad
ScratchPadActivity: finishing (handoff released, result=1)
ScratchPadEntry: scratch pad returned: resultCode=1
GPaperRatta: firmware ink session claimed                  notebook reclaims, .097
ViewRootImpl[ScratchPadActivity]: Change to Gone           the pad's window closes, .198 — AFTER
ScratchPadClient: drainOutgoing: 1 chunks, 9 strokes in 99 ms
ScratchPadService: end · finish: end ok · unbind … (held)
NotebookSession: pasted 9 strokes from the scratch pad onto 5c69e5aa…

ScratchPadClient: send: 1 chunks, 9 strokes, placement=0 in 77 ms   ← notebook → pad
GPaperRatta: firmware ink released for handoff             notebook
ScratchPadActivity: page fba0923d… loaded: 9 strokes, 2 pages
ScratchPadActivity: received 9 strokes (newPage=true)
```

The handoff ordering held on the Send exit exactly as it does on Back — the caller's reclaim lands
**before** the departing window's close. No g-paper change; the pin stays **0.1.6**.

**Landing state, dumped:** the notebook's paste lands with **Lasso armed** and the seven-button bar
(Delete · H · Link · Copy · Cut · Snap · **Send to Scratch Pad** — 812 px of the Nomad's 1404); the
pad's received placement opens on **2 / 2** (the inserted page) with the strokes selected, the lasso
armed and **Delete · Send selection to notebook** floating over them. The placement sheet reads
"Send to Scratch Pad — New page / Current page". A Send from a blank page raises **"Nothing to
send"** and the pad stays up. `am start` of the screen is still **refused** (`refused caller (none)`);
`dumpsys activity services <pkg>` = **(nothing)** after every showing; binds = unbinds; `logcat -b
crash` **empty** and no E/W from our code throughout.

**Tests: 620 JVM** (`:app` 456 · `:sn-screen` 58 · `:extension-api` 29 · `:ext-mlkit` 29 ·
`:ext-scratchpad` 48) — up 15 from J4. New: `ScratchStoreReceiveTest` (10) pins where a placement
lands, which page becomes current, what the `Received` record carries so the screen can build **one**
undo step from it, and above all that a placement over the cap **leaves nothing behind** — no ink, no
inserted page, no moved current (the half that is easy to get wrong is the insert, and it is asserted
directly); `ScratchReceivedUndoTest` (5) pins the two undo shapes, including the one the J4 shape
could not do — a redo of a received new page brings the page back **with its ink**, and an ordinary
insert still redoes blank; plus a `TransferCaps` round-trip proving **coordinates 1:1** through the
full outward → drain → mint path, id excepted. `assembleDebug`, `assembleRelease` and `test` green
across all five modules; the pad's release APK is **6.8 MB**.

**Three deviations from the phase text:**
- **`ScratchAction.Page` grew a second blob rather than the arc gaining a fifth kind.** The phase
  said a New-page placement records as `Page`; J4's `Page` could put the page back but would redo it
  **blank**, because its redo always dropped the blob. `afterBlob` (default null) makes the one shape
  say what the page holds on *each* side, and J4's two acts are unchanged — pinned by a test.
- **`ScratchPadEntry` gained an `onSent` callback** rather than the notebook toasting at the tap.
  The send is asynchronous inside the entry, so a toast fired at the tap would confirm something that
  had not happened — and could be followed by a failure dialog. It fires after the last `receiveInk`
  returns, which is also where the selection is cleared.
- **The library's entry passes `sendEnabled = false`, and it is a constructor flag, not a `Send`.**
  Whether the pad shows its Send buttons is a property of the *caller*, not of whether ink was handed
  over: the notebook's plain top-bar tap must still come back with ink.

**One bug found in self-review, before the second device pass:** the tool restore wrote its "put this
back" field **before** arming the lasso. Arming the lasso dismisses whatever selection was still up,
and that dismissal is exactly what runs the restore — so it would have consumed its own field and put
the pen back under the selection it was about to make. The write now lands after the tool change on
**both** sides (notebook and pad). It is the O2 lesson verbatim: two handlers reading one piece of
state must order the write after the read.

**Carried into J6:** the docs (`docs/scratchpad.md` new, `docs/extensions.md` grown, both
`CLAUDE.md`s), the arc-range `/code-review high`, the boundary audit and the freeze.

### J6 — Review, hardening, docs, freeze
**Status:** ✅ Complete (commits d31cb62 + 98a836e, Nomad-verified + user all-clear 2026-08-25 —
"These tests pass"). **ARC 11 FROZEN.** Review level **high**, version stamp stays **`0.1.0-ratta`**, g-paper pin stays **0.1.6**.

`/code-review high` **over the whole arc range, never the last phase** — the O2 lesson: reviewing
the arc caught two O1 bugs no eye check could have. A **boundary audit** on Paper's rows-28–32
shape, walked against SN's code: outward on `begin` is the uid-bound store binder only; outward ink
is bare geometry + width + colour + style name + the page px size — no stroke, page or notebook id
or name ever crosses; inward ink is validated, capped and fresh-id'd; the screen is caller-checked
both ways and data never rides the Intent; the store caps change no trust rule. Docs:
**`apps/notesprout_ratta/docs/scratchpad.md` new** (the pad's own reference — screen, tools, pages,
store layout, transfers, failure table); **`docs/extensions.md` grown** from a one-point doc to
cover a second point, the first screen-owning point (the tier-2 recipe), the extension store, and
the held bind; both `CLAUDE.md`s (the five-module layout, the `:sn-screen` "a fix to shared screen
logic goes there, never in a consumer" rule, the Scratch Pad bullet); memory; version-stamp
decision; full regression (Haiku walk + the arc's short user checklist); commit + push; **arc
freeze**.
**Gate:** everything green or explicitly accepted → monorepo `BACKLOG.md`; user all-clear.
*Fable reviews and hardens; Sonnet the doc pass; Haiku the regression walk.*

**Questions to resolve at phase start:** review level (every arc has frozen at **high**); version
stamp (0.1.0-ratta through ten arcs — the user's call).
*(Answered: **high** — where every arc has frozen, and the arc range `832fba7..HEAD` is what the O2
lesson says to review, never the last phase. And **leave `0.1.0-ratta`** — the version has never
tracked arcs and nothing ships from this branch yet.)*

**Outcome:** the arc-range review found **six** things; **five were real and are fixed**, and the
sixth was **refuted** — the review is itself reviewed, which is the whole point of doing it over the
arc rather than the phase.

**The one that mattered — a bound that was never a bound.** `TRANSFER_MAX_CHUNKS` was inherited from
Paper as `34`, documented as `ceil(MAX_TRANSFER_STROKES / TRANSFER_CHUNK_STROKES)`. But a chunk does
not only close when it is full of strokes: it also closes when the **next** stroke would cross the
point cap. So 39 strokes of 10 001 points — comfortably inside *both* whole-transfer caps — chunks
into 39, and the host's drain, which stops at the budget, would have reported a **legal transfer as
truncated** and left ink behind on the pad. Worse, a JVM test pinned the wrong derivation, so the
bug had a test defending it. The constant is now **computed from the other four** and counts both
reasons a chunk closes (stroke-driven ≤ `MAX_STROKES / CHUNK_STROKES`; point-driven <
`2 * MAX_POINTS / CHUNK_POINTS`, because summing those pairs counts each point at most twice; plus
the last chunk) = **74**, deliberately loose — it is a runaway guard, not a target. Three tests
replace the one, a chunking shape each. **This is the arc's one deviation from Paper's shipped
values**, and it is recorded as such in `docs/extensions.md`.

The other four:
- **A failure that said nothing.** A failed or empty `drainOutgoing` was swallowed into a `Slog.d`:
  the user taps Send, watches the pad disappear, and lands back in the notebook with nothing pasted
  and **no dialog** — the one path in J5 that broke the standing rule (a tap that did nothing reads
  as broken on e-ink). Now the "Nothing came back" problem dialog, saying the ink is still on the pad.
- **`finish()` ran before `onDrained`**, contradicting both its own KDoc and the parameter's. Benign
  today, but the comment is what a future change would trust. The finish moved after the callback,
  in a `finally` so it cannot leak — and the parameter doc now says exactly what is true: the
  callback is *invoked* with the bind held, and work it **defers** is past it.
- **A stray page could outlive a refusal.** `ScratchStore.receive(newPage)` inserted the page into
  the list *before* writing its ink, so a store failure between the two left a blank page behind
  while the host was telling the user nothing was sent. The ink is now written first and the list
  published last, with the orphan blob taken back out if the list write fails — the promise the
  KDoc already made, now kept. A test asserts the store is byte-clean afterwards.
- **A documented extra nobody read.** `EXTRA_SCRATCH_OPEN_RECEIVED` was set by the host, documented
  in the contract, and never read — dead surface a later change would have trusted. The pad reads it
  and gates the placement consume on it: belt and braces of the kind the rest of this seam is made of.

**The refuted one** claimed the store binder's `pending` ThreadLocal leaks a 4 MiB region because
`DebugMenu.runStoreProbe` calls `getLarge` in-process, where `onTransact` never runs. Both premises
are false: that probe was **deleted in J3** (there is no in-process caller), and `onTransact`'s
`finally` already calls `pending.remove()`. No change made — recorded here so it is not re-raised.

**Boundary audit:** walked on Paper's rows-28–32 shape against SN's code and written into
`docs/extensions.md` as five rows — the uid-bound store binder as `begin`'s only outward argument;
outward ink as bare geometry with **no parameter for an id to travel in**; inward ink validated,
capped, fresh-id'd and pasted as one undoable step; the screen caller-checked both ways with data
never on the Intent; and the store caps changing no trust rule. One **asymmetry recorded rather than
changed**: the host clamps inbound colour to black, the extension does not clamp the host's — SN's
ink is fixed black, so there is no other colour to send, and the untrusted direction is the one that
clamps.

**Docs:** `docs/scratchpad.md` is **new** — the pad as a feature (screen, tools, pages, store layout,
both transfers, failure table, what the pad is *not*). `docs/extensions.md` grew from a
one-screen-owning-point doc into the **seam** doc: the tier-2 recipe for an extension-owned screen
written as a numbered order (exported + `<category DEFAULT>`, caller check as the first statement,
launch only after `begin`, drain before finish, `onDestroy` as the backstop), the transfers as a
who-trusts-what table, and the boundary audit. Both `CLAUDE.md`s: the five-module layout, the
**`:sn-screen` rule** in the words that make it enforceable ("a fix to shared screen logic goes
there, never in a consumer — breaking it recreates the `RattaNotebookView` sibling-copy trap one
file at a time"), and a Scratch Pad bullet.

**Tests: 623 JVM** (`:app` 456 · `:sn-screen` 58 · `:extension-api` 31 · `:ext-mlkit` 29 ·
`:ext-scratchpad` 49) — up 3 from J5: the three chunking-shape tests replacing the one that pinned
the wrong bound, and the page-list rollback test. `assembleDebug`, `assembleRelease` and `test`
green across all five modules.

**Nomad, verified after the fixes:** the pad opens from the library **without** a Send button and
from the notebook **with** one (the caller-property rule, proven side by side); the page arrows
walk 4/4 → 2/4; a Send of the whole page runs the round trip in one sitting — release (`.605`) →
notebook reclaims (`.696`) → the pad's window closes (`.795`) → drain 1 chunk / 9 strokes (`.808`)
→ `end ok` → unbind → `pasted 9 strokes` — with the **reclaim before the close**, the ordering the
whole handoff turns on. The paste lands **selected**, lasso armed, under the seven-button bar.
A shell `am start` of the screen is still `refused caller (none)`; `dumpsys activity services` is
`(nothing)` after every showing; `logcat -b crash` empty and **no E or W from our tags** throughout.
The device agent's three skips were the standing tap-aim trap, not app failures — re-walked by hand
from measured screencaps, all three pass.

**Deviation from the phase text:** the phase said Fable reviews and Sonnet writes the docs. Both were
done in-session — the review by the `/code-review` agent at `high` and the fixes, audit and docs
by the session model — and the Haiku device walk ran as written.

**One thing the user found at the freeze check, and where it landed.** After the transfers, dragging
the *resulting* selection feels sluggish where an ordinary hand-lassoed drag is smooth. Not a
showstopper; chased at the user's request and **left open, banked in `BACKLOG.md`** with the
evidence. Two hypotheses were built, shipped to the device and **both disproved** — worth recording
as method, not just outcome:

1. **The firmware dash trail under the app-drawn ghost.** `RattaPaperView` never overrides
   `onSelectionDragVisual`, so the `firmwareInkSuppressed` flip at drag start is never pushed to the
   firmware; suppression rests on the hover stream winning the race (overlay law 3). A g-paper
   **0.1.7** adding the override was built, published, pinned and installed — **the user reported no
   change**, so it was **reverted** and the pin stays 0.1.6. An unproven engine change does not ride
   into an arc freeze. The gap it names is real and is in `BACKLOG.md` on its own merits.
2. **Per-frame drag cost scaling with selection size** (`onDraw` rebuilds every dragged stroke from
   raw points each frame). The measurement that suggested it was **confounded**: the fast drags were
   pen and the slow ones finger.

What instrumentation actually established: the repaint rate is flat at ~12–16 Hz everywhere (the
60 ms throttle caps it), so the felt sluggishness is **input sampling**, not frames — pen ~430 Hz vs
finger ~50 Hz, which is the digitizer vs the touch panel and explains most of the spread. The
residual worth chasing is the **matched finger pair at equal stroke count: 47 Hz transferred vs
56 Hz hand-lassoed**, one sample each, with the user confirming the transferred one still felt
worse. The full table, the ruled-out causes and the next controlled run are in `BACKLOG.md`.

**The lesson, and it cost two device round trips:** *a plausible mechanism is not a cause.* Both
hypotheses were well-argued from the code and both were wrong, and the second was wrong because the
data behind it mixed two input devices. Measure the matched pair before believing the story.

---

## Phases — Arc 12 "Paper" (planned 2026-08-25, wizard complete)

One capability, one phase: **change a page's template from the long-press page sheet.** The
notebook has been able to *start* as one of four papers since R2 and never been able to change its
mind. No new dependency, no schema change, no migration, no extension surface — the `template` row
and the page's `refId` already say everything this needs; nothing here was missing from the format,
only from the UI.

### Locked decisions (arc-12 wizard 2026-08-25 — do not re-ask)

| Decision | Answer |
|---|---|
| Scope of one change | **This page only.** The same scope every other row of the page sheet has (Copy, Cut and Delete are all the page you long-pressed). A mixed-paper notebook was already reachable via cross-notebook page paste, so this makes no new shape possible. |
| Undoable | **Yes** — a new `NotebookUndo.Action.TemplateChanged(pageId, from, to)`, replayed both ways through `NotebookSession.applyTemplate`. Consistent with every other page-level edit. |
| Picker UI | **Nested action sheet with a check mark.** Page sheet gains a **Page template** row → a second `ActionSheetDialog` titled "Page template" listing Blank / Lined / Dotted / Grid, the current kind carrying `ic_check` — the library sort sheet's exact pattern. No new widget, no radios, no previews. |
| Index `templateKind` | **Left alone.** It is the notebook's birth record; a real cover snapshot supersedes it on every close, and with per-page paper there is no longer one true answer for a whole notebook. |
| Kinds offered | The four built-ins only (`TemplateKind.entries`). No custom templates, no import — out of scope and unasked. |
| Scratch pad | **Not included.** Pad pages are blank by construction and hold no template rows; its long-press goes straight to the delete confirm. Nothing about this arc reaches the extension. |

### P2 — Change a page's template
**Status:** ✅ Complete (commit 7067a10, Nomad-verified + user all-clear 2026-08-25) — **ARC 12 COMPLETE + FROZEN**

`PageTemplate` (pure: `reusableId` + `kindOf`), `NotebookSession.changeTemplate` /
`applyTemplate` / `currentTemplateKind`, `Action.TemplateChanged` + both replay arms, the page
sheet's new row and the sub-sheet, `ic_template` (Tabler "template"), one string.
**Gate:** JVM tests green; debug + release build; Nomad walk (sheet row, tick tracks the page,
per-page independence, persistence across a cold restart, template-row reuse, crash buffer);
**user eye check** (the four papers on the glass, undo/redo — the one thing adb cannot drive,
since undo is a multi-finger double-tap).

**Outcome:** Implemented in one pass. New `notebook/PageTemplate.kt` — the whole decision that
costs pixels, pure and JVM-testable: **reuse before mint** (a `template` row is *shared paper*, so
a change looks for a row this file already holds of the wanted kind **at the page's own size**
before rendering another megabyte of the same WEBP), with the page's **current** id winning among
equal matches so picking the already-ticked kind is a true no-op. Nothing ever soft-deletes a
template, so Lined → Grid → Lined is free — and the old row is deliberately left standing, the same
reasoning the paste path uses. `kindOf` keeps three states as **unknown** (vanished row, a `text`
this build cannot name, a failed read) rather than folding them into Blank — the T1 lesson; an
empty `refId` *is* Blank, which is a real answer, not a missing one. Session: `changeTemplate`
renders at the **page's own** width/height (a page pasted from a larger device keeps its authored
size; ruling it to this screen would stop short of its edge), `applyTemplate` writes the `refId`
and **does not decode** — `loadTemplateFor`'s `templateIdLoaded` compare means the following
`navigateTo` is what reloads the bitmap, so one decode lands on the swap that paints it, in one EPD
refresh. The sub-sheet is the one sheet in the app that opens **asynchronously** (the tick needs a
blob-free `templateDigests` read); it rides the page sheet's existing frame-silence exception and
is deliberately **not** re-gated on the pen — `isPenActive` counts hover, so a gate would hold the
sheet while the pen floats (R3's lesson). A failed read still shows the sheet, unticked.
**637 JVM tests** (14 new: `PageTemplateTest` ×13, one `TemplateChanged` case in
`NotebookUndoTest`), debug + release build green. **Nomad walk, all pass** (driven by hand — the
sheet is finger-tappable, so no agent needed): the row appears between Cut and Delete; the sub-sheet
renders titled with the tick on **Blank**; Grid applied with the ink untouched and the tick moved to
**Grid** on reopen; page 2 inserted and set to **Lined** while page 1 stayed Grid (per-page
independence); force-stop → cold restore lands back on a Grid page 1; setting page 2 to Grid logged
`re-paper reuses template 4d6c76a4…` — page 1's row, no second blob minted; crash buffer empty. Test
data restored (page 2 deleted, page 1 back to Blank). Version stays **0.1.0-ratta**; g-paper pin
stays 0.1.6. **Undo/redo was the user's to check** — the multi-finger double-tap is the one gesture
adb cannot inject. **User all-clear 2026-08-25** ("All tests pass…this one is good to go"), no
findings; the arc is frozen at this commit.

**Questions to resolve at phase start:** all answered in the wizard above.

---

## Phases — Arc 13 "Stationery" (planned 2026-08-25, wizard complete)

Templates stop being four radio buttons and become **a library**: folders, previews, import,
export. Two things are joined here that the app has always kept apart — og's reusable template
library (index-owned, foldered, importable) and SN's built-in paper (rendered from arithmetic,
never stored twice) — and the join is the whole design: a **built-in** is paper the app draws, a
**static template** is pixels the library keeps, and there is no third kind.

**The generator-options idea was tried and abandoned** (2026-08-26, the user's call after seeing it
on the glass — see G2 below). The three built-ins are not adjustable and are not meant to be: they
are the **Default** set, always present, and the way to get different paper is to import it.

The one screen behind every paper decision is shared: **New Notebook**, the **page's paper** row on
the notebook's long-press sheet, and a new **Templates** button in the library all host the same
browser, with the same folders, the same long-press behaviour and the same import.

### Locked decisions (arc-13 wizard 2026-08-25 — do not re-ask)

| Decision | Answer |
|---|---|
| Two card kinds, no third | **Built-in** (Lined / Dotted / Grid, in the reserved **Default** folder) — the app's own paper, **not adjustable and not editable**: it does not long-press at all. **Static template** (imported images) — long-press opens **management** (rename / move / duplicate / export / delete, plus **Fit…**). |
| The three built-ins are **not rows** | They are hardcoded, with sentinel ids in the `ListIds` style, and so is the **Default** folder card and the **Blank** card. Nothing is seeded at bootstrap, nothing can be deleted, renamed or moved, nothing needs repairing if an index is restored from a backup, and there is no migration. **"Default" is a reserved name** at the templates root — a user folder cannot take it, no template of the user's may be called it, and no import may land in it. |
| The **Default** folder | Always present, always the same three papers, in one order, forever. It is the floor of the library: however empty the rest of it is, every notebook can still reach Lined, Dotted and Grid. (Renamed from "Generated" on 2026-08-26 when the generator idea was dropped — nothing about it is generated *by the user* any more.) |
| Library storage | **Additive index row types** `template` / `template_folder` in `notesprout.db` — the arc-5 `naming` / arc-7 `clipboard` pattern. No schema change, no migration, no Room identity-hash break with Paper. Folders are `parentId`, exactly like notebook folders. Nothing about templates touches the filesystem. |
| The static row's shape | `type=template` · `name` · `parentId` (null = root) · `templateKind` = the base kind or `IMAGE` · `flags` = the fit mode · `blob` = the image bytes. `refId` / `sortOrder` / `pageCount` / `keyScope` unused. No new columns. |
| Imported images | **PNG / JPEG / WEBP** only, through SAF. **No PDF** — that is a new Gradle dependency and its own decision. The fit is **asked at import** (Fit, centred on white · Stretch · Fill/crop) and **kept as a setting**: the row stores the *original* image and the page-sized render happens **on use**, so the same template lands correctly on a Nomad page and a Manta page and **Fit…** can be changed later. |
| Export | **PNG rendered at the page size**, one rule for both kinds: a built-in renders its pattern, a static template renders with its fit applied. Written through SAF `CreateDocument`. **No share sheet** — the Supernote suppresses it. |
| New Notebook | Hosts the **whole browser inline** — breadcrumbs, folders, import, new folder, both long-press sheets — under a header carrying the name field and **Create**. The radios are gone. |
| The page's paper | The page sheet's **Page template** row opens the same browser full-screen (the `LinkPickerActivity` shape: an `ActivityResultLauncher`, chrome only, **no `releaseForHandoff`** — it is not a paper surface). Scope stays **this page only**, one undo step, exactly as arc 12 locked it. |
| Browse chrome | **Sort · Search · Pinned · Recents**, all four. Sort reuses the library's sheet. Pinned is a sentinel `LIST` row (templates only, never folders); Recents is device-local prefs and records a use **only when a template is actually applied**. Pinned / Recents / Search are mutually exclusive flat views, as og has them. |
| Thumbnails | **True miniature** — the card is the page, scaled honestly. Density is what tells two papers apart, so the card must show it; a dense grid reading as a grey wash is what a dense grid looks like. Rendered on IO, cached in memory keyed `id:updatedAt`. |
| `.soil` identity | The `.soil` `template` row's `text` is the **token**, and **reuse is `text` + page size** — arc 12's rule, generalised. The built-ins keep exactly `LINED` / `DOTTED` / `GRID` (every existing file and Paper still read them, and nothing about them changes); an imported template is `IMG#<8 hex of the image bytes>`. Nothing ever soft-deletes a template row, so there-and-back stays free. |
| Deleting a library template | **Never touches a notebook that used it** — the pixels were copied into the `.soil` at apply time. og's rule. |
| Scratch pad | **Not included.** Pad pages are blank by construction; nothing here reaches the extension. |
| Non-goals | **No adjustable built-ins** (tried in G2, abandoned — do not re-raise without a fresh user decision), no PDF import, no bulk/folder export, no template sync or backup, no per-notebook "default paper for new pages" (a new page still inherits the page it was inserted after), no landscape, no third extension point. |

### Arc-13 standing traps (assume they apply)

- **An index blob you can write is not one you can read** (B3): SQLCipher's 8 MiB `CursorWindow`
  caps any row the app reads back. Imports downscale (longest edge ≤ 2× the page's long edge) and
  refuse, with a **problem dialog**, anything whose encoded bytes exceed the cap set in G4.
- **Reuse before mint** (P2): a `.soil` `template` row is *shared paper*. Every apply path looks for
  a row this file already holds with the same token at the page's size before rendering another
  megabyte of WEBP, and the page's **current** row wins among equal matches so re-picking the
  ticked card is a true no-op.
- **Render at the page's own size, never the screen's** (P2) — a page pasted from a larger device
  keeps its authored size.
- **`applyTemplate` must not decode** (P2) — the id change is what makes the following `navigateTo`
  reload the bitmap; decoding here costs a read the swap throws away.
- **`kindOf` keeps unknown as unknown** (T1, P2) — a vanished row, a token this build cannot parse,
  or a failed read shows **no tick**; only an empty `refId` is Blank.
- **The Default folder and its three papers are the app's, not the user's** — no rename, no move,
  no delete, no long-press, and no import may land inside it.
- **Read kinds through blob-free digests** (`templateDigests`), never `byId` — the pixels are the
  one thing the decision never needs.
- **SAF is new to SN.** Nothing in the app has ever opened a system file picker, and the Supernote
  is known to suppress the share sheet. **G4's first step is a probe**: confirm
  `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT` resolve and return a usable URI on the Nomad,
  *before* any of the import flow is built. If DocumentsUI is absent or crippled, the fallback is a
  fixed on-device folder (the contingency is decided then, not now).
- **Supernote swallows `adb shell input text` and `input keyevent` letters** — naming a template or
  a folder in a device walk means tapping the on-screen keyboard, or the agent avoids the path.
- **A SAF pick cannot be driven by adb** (found in G4, 2026-08-26, and it is a hard boundary — the
  lasso's rule in a new place). DocumentsUI's *chrome* answers injected taps perfectly — the roots
  drawer, the list/grid toggle, the breadcrumb, Cancel — but its **file items are inert to every
  injectable input**: `input tap`, `input touchscreen tap`, a 60/150/900 ms `swipe` in place, and
  `input stylus` / `mouse` / `trackball` all land on nothing, and `KEYCODE_TAB` focus stops at the
  first folder card and `DPAD_DOWN` will not enter the file grid. Its items come from
  `recyclerview-selection`, which reads the event in ways an injected one does not satisfy. So
  **every path that begins with choosing a file is a user checklist item**, and a `CREATE_DOCUMENT`
  save is too (its SAVE button is reachable, but only after a pick has produced something to save).
- **A query is not a name** (found in G5, 2026-08-26). `NameDialog` is the family's one "type a
  short string" dialog, and reusing it drags `name_problem_title` / `name_empty` along by habit —
  so an empty *search* first refused with *"That name won't work / Name cannot be empty"*, which
  answers a question the user did not ask. A dialog reused for something that is not a name needs
  its own words.
- **A disabled button is invisible on e-ink** — click-guard plus a dialog, never `isEnabled = false`.
- **Toast confirms, dialog explains** — a refused import (too large, unreadable, reserved folder) is
  a dialog.
- **Frame silence** — the notebook's launch of the browser rides the page sheet's existing
  exception (arc 12); no new exception may be added without a written justification.
- **The browser never opens a `.soil`** (og's rule, and K2's): the notebook holds the only
  connection; the browser returns a pick and the session does the write.
- **`IndexGuard.ready(this)` first thing** in every new index-touching `onCreate`, and
  `IndexGuard.bounced(this)` at the top of any `onDestroy` override.
- **`"order"` is quoted in SQL and backticked in Room**; index writes are soft deletes only.

### G1 — The template store + the Templates screen (browse, folders, management)
**Status:** ✅ Complete (commit `2ee39f3`)

The index side and the screen, with nothing to pick yet. Additive `template` / `template_folder`
row types + an `IndexRepository` template region (create / rename / move / duplicate / soft-delete /
list, recursive folder delete, per-folder duplicate-name check). `TemplatesActivity`: breadcrumbs,
the library's paginated non-scrolling card grid, the sort sheet, **New folder**, and the two
long-press sheet for static cards (the sentinels do not long-press at all). The root's synthetic
cards (**Blank**, **Default**) and the built-in paper cards inside `Default`, all from hardcoded
sentinel ids. True-miniature thumbnails rendered on IO with an
in-memory cache. Entry point: a **Templates** button in the library's bottom bar (`ic_template`).
**Gate:** JVM tests for the pure rules (root composition, reserved name, sentinel ids, duplicate
naming, recursive delete); debug + release build; Nomad walk (browse, create/rename/move/delete a
folder, pagination, sort, crash buffer).
*Opus builds the store + screen; Sonnet does the layout, drawables and strings.*

**Questions to resolve at phase start:** none — the wizard above covers G1.

**Outcome (2026-08-25):** built and walked on the Nomad; **658 JVM tests** (was 637), debug +
release both build, release signs, crash buffer empty, test data restored. Version stays
`0.1.0-ratta`; g-paper pin stays 0.1.6. No new dependency, no schema change, no migration.

*What landed.* Two additive index row types (`template` / `template_folder`) and an
`IndexRepository` template region (list · create folder · create template · image read · duplicate ·
soft-delete · **transactional** recursive folder delete). Five hardcoded sentinel ids in the
`ListIds` hex-ASCII style — `_blank`, `genrtd`, `lined_`, `dotted`, `_grid_`. `TemplateLibrary`
(pure, JVM-tested) owns root composition, the reserved name and duplicate-name suffixing;
`TemplateCard` is the card model; `TemplateCardGrid` is the library's grid shape over
`GridMath` (the `PageCardGrid` precedent — the shared part is already the arithmetic);
`TemplateThumbnails` is the true-miniature renderer + `LruCache`. `TemplatesActivity` is the screen.
Entry point: a **Templates** button in the library's bottom bar (`ic_template`, already in
`:sn-screen`).

*Three things generalised rather than copied.* `IndexRepository.ancestry` and `folders` take a row
type (a row of the wrong type ends the walk, so the two trees can never be spliced by a corrupt
`parentId`); `SortRules.foldersFirst` takes a `folderType`; and **`FolderPickerActivity` now walks
either hierarchy** (`browseFolderType` + `rootLabel` extras) — one Move picker, two trees, rather
than a sibling copy that would drift. `SortPrefs` gained a `templates(context)` file: two shelves of
two different things, and re-sorting one must not silently re-sort the other.

*Two calls worth knowing.* **(1) The built-in's long-press sheet is not in G1.** Its only row would
have been *Template options…*, and what that opened was G2 — a row that opens nothing is worse than
no row, and the phase gate never named it. **G2 was then abandoned, so that row never landed and
never will**: Blank, Default and the three built-in papers do not long-press at all, by design.
**(2) The reserved root name is reserved for templates as well as folders.** The locked decision's headline sentence is about *the name at the root*; a second card
reading "Generated" beside the real one would be a confusion the user cannot resolve by looking,
whichever kind it is. Say so if you meant folders only.

*(The folder this phase called **"Generated"** was renamed **"Default"** on 2026-08-26 when the
generator idea was dropped; its sentinel id spells `deflt_`. Everything else below still stands.)*

*Traps confirmed on the glass.* The bottom bar now carries **nine** controls on a 1404 px Nomad —
7 buttons × 116 px + the pager's 569 px = 1381 px, **23 px of headroom**; measured before it was
written, and the pager stays `INVISIBLE` so the row never reflows. Inside **Generated** both Sort
and New folder are GONE (never `isEnabled = false`). The miniature's 1 px page edge is drawn **on
the bitmap**, not as an ImageView background (the page-card lesson). The reserved-name rejection was
walked end-to-end with the on-screen keyboard — the dialog stays open with the typing intact.

*Not verifiable on device yet, by construction.* Static-template management (rename/move/**duplicate**
/delete of a `template` row) had no way to make one — G2's discarded save flow proved it end to end
on the glass before it was reverted (sheet, Duplicate, Delete, all correct), so the only untested
path left is the one G4's import will open. Same for the `IMAGE` fit path in
`TemplateThumbnails`, which draws Fit only until G4 brings the other two modes.

### G2 — Adjustable generators ❌ ABANDONED
**Status:** ❌ Abandoned 2026-08-26 — built (commit `6706340`), shown to the user, **reverted**.
Its one surviving product is the rename of "Generated" to **Default**.

**Do not rebuild this. Do not re-raise it without a fresh user decision.**

*What it was.* `TemplateSpec` (a serializable recipe: kind, per-axis density in spacing **or**
count, four insets in mm, margin rule, rule thickness, dot size, ruling shade) with a canonical form
and an 8-hex digest for a `LINED#<hex>` `.soil` token; `TemplateGeometry` grown into a
`TemplatePlan` renderer; and a full-screen options Activity with a live page preview and
**Cancel · Use once · Save as template…**, saving a variant as a static card carrying its spec.

*Why it went.* The user's call, on the glass: *"I'm not happy with the generated template options.
I want to abandon that idea completely."* Two rounds of layout work went in first — the margins
rearranged spatially around a centred preview, then their steppers replaced with typed input boxes
— and the verdict after both was that the whole idea, not the arrangement, was wrong. A generator
with nine knobs is a settings screen wearing a page's clothes, and this app is meant to feel like
paper.

*What replaced it.* Nothing. The three built-ins are fixed paper in a fixed folder, and **import
(G4) is how a user gets different paper.** That is one idea instead of two, and it is the one the
rest of the arc was already built around.

*What the revert removed*, so a future session does not go looking for it: `TemplateSpec`,
`TemplatePlan` and the spec half of `TemplateGeometry` / `BuiltInTemplates` (both back to their G1
shape, kind-based), `TemplateOptionsActivity`, `OptionStepper`, `OptionField`, `StepMath`,
`TemplateNaming`, the two option layouts, `FolderPickerActivity`'s pick mode, the options strings
and the three test files. The tree is the G1 tree plus the rename.

*Three things worth keeping in mind if this ever comes back* — they were real findings, and all
three are cheap to lose and expensive to rediscover:

1. **Stock output has to stay bit-identical.** The rule thickness and dot radius are authored in
   *mdpi pixels*, not millimetres (`px × dpi / 160`). Expressing them in mm reproduces the old
   numbers exactly only because `25.4 / 160` and `4 × 25.4 / 160` round-trip in float32 at every
   density in the family — checked before a line was written. Any future arithmetic change needs
   that check.
2. **A count is not a spacing.** 10.0 mm → 14 lines → 9.9 mm, because a count spreads evenly over
   the page. And the exact derivation `extent / (count + leading)` puts the phantom next feature
   *precisely* on the far edge, which float accumulation then includes about half the time (12 rows
   asked for, 13 drawn) — it needs a hair of a nudge.
3. **`adjustResize` is wrong for a screen with a page on it.** It squashed the preview to a sliver
   and clipped the field boxes. `adjustPan` moved nothing at all, because the fields already sat
   above where the panel lands.

### G3 — Picking: New Notebook, and a page's paper
**Status:** ✅ Complete (commit `bc9afec`)

The browser becomes shared. The grid + breadcrumbs + long-press behaviour move into one component
hosted three ways: `TemplatesActivity` (no pick), `NewNotebookActivity` (name + **Create** header,
radios deleted), and a full-screen launch from the notebook's **Page template** row
(`ActivityResultLauncher`, the `LinkPickerActivity` shape). The result contract is one small
`TemplatePick` — Blank, a built-in kind, or a static template id — and the apply path generalises
arc 12: token + page-size reuse before mint, `NotebookSession.changeTemplate` taking a pick,
`Action.TemplateChanged` unchanged, and the **tick** on whichever card the page is currently using.
New-notebook creation seeds page 1 from the pick with the same renderer.

Note that until G4 lands there is nothing to pick *but* the built-ins and Blank — which is exactly
what the notebook can already do through arc 12's sub-sheet. G3 is therefore the seam, not the
feature: it is what makes one browser serve all three hosts, and what G4's imports arrive into.
**Gate:** JVM tests (token derivation, reuse matching incl. the static/`IMG#` case, tick resolution,
pick round-trip); debug + release build; Nomad walk (create a notebook from each source; re-paper a
page; per-page independence; cold restart; no duplicate template rows in the `.soil`); **user eye
check** — undo/redo of a template change (adb cannot inject the multi-finger double-tap).
*Opus — this is the risky seam.*

**Questions to resolve at phase start:** none expected.

**Outcome (2026-08-26):** built and walked on the Nomad; **683 JVM tests** across the five modules
(was 658 — `:app` alone went 491 → 516), debug + release both build, release signs, crash buffer
empty, test data restored. Version stays `0.1.0-ratta`; g-paper pin stays 0.1.6. No new dependency,
no schema change, no migration.

*What landed.* **`TemplateBrowser`** — the grid, breadcrumbs, sort sheet, New folder, both
long-press sheets and the Move picker, lifted whole out of `TemplatesActivity` and hosted three
ways over one shared layout (`view_template_browser.xml`, `<include>`d by both screens). Hosts
supply only the two things that genuinely differ: **what a tap means** (`onPick`) and **what is in
force** (`selection`). `TemplatesActivity` is now 97 lines with two modes — browse (a tap on paper
means nothing; it is a library) and **pick**, launched from the notebook with an
`ActivityResultLauncher`. `NewNotebookActivity` hosts the browser under a one-row header carrying
the name field and **Create**; the four radios are gone.

*The one structural decision, and it is the arc's hinge:* **paper is identified by a token, not by a
kind.** `TemplateToken` widens arc 12's `TemplateKind` match into one vocabulary that also says
`IMG#<8 hex>`, and the built-ins keep exactly `LINED` / `DOTTED` / `GRID` — every file this family
has written, and Paper on the same device, still read them unchanged. `PageTemplate.reusableId`
takes a token, `kindOf` became `tokenOf` (a vanished row still answers null, and a token this build
cannot parse comes back verbatim and simply matches no card — unknown stays unknown, by a shorter
road). `PaperSource` + `PagePaper` are the one place a pick becomes pixels, so notebook creation and
re-papering render through the same function.

*Three calls worth knowing.*
**(1) The image token digests the fit mode as well as the bytes.** The locked wording said "8 hex of
the image bytes", and that is wrong by exactly one input: fit is what turns stored bytes into page
pixels, so the same picture Fitted and Stretched are two papers. Digesting the bytes alone would let
a page that asked for the stretched one be silently re-pointed at the fitted row already in the
file. The shape is unchanged (`IMG#` + 8 hex); the fit byte goes in first so it can never be read as
image data. JVM-tested both ways.
**(2) `TemplateFit` landed here rather than in G4.** The apply path has to render a static template
*somehow*, and a stub would have been a second thing to replace. It is ~40 lines of pure arithmetic
for all three modes (Fit moves the destination, Fill moves the source, Stretch moves neither), with
Fit tested now; G4 adds the choice UI and the other two modes' cases. `TemplateThumbnails` was
re-pointed at it in the same breath — a card that showed a picture fitted while the page stretched
it is the one thing a *true miniature* must never do.
**(3) `NewNotebookActivity` is `adjustNothing` now, not `adjustResize`.** G2's third finding, applied
before it could bite: this screen has a page on it, and resizing for the keyboard would squash the
grid it measured itself against. The name field moved into the top row instead, where the IME cannot
reach it — which is also why the header is one row and not a title plus a field.

*Traps confirmed on the glass.* Reuse-before-mint was verified through the log, not by eye: Dotted →
Grid → Dotted re-papered twice and logged **`re-paper reuses template <id>`** both times, never a
mint — so a there-and-back stacks no second megabyte of WEBP. Re-picking the **ticked** card logged
the reuse and then **no `re-papered` line at all** — `changeTemplate` returned null, so it is a true
no-op with no undo step, which is what the `prefer` argument exists for. The tick resolves from the
page's own `.soil` token with the library never having heard of the notebook: Grid ticked inside
**Default** while the page was Grid, and nothing ticked at the root. Inside Default both Sort and
New folder are still GONE. The Move picker launches from the browser's own
`registerForActivityResult`, which is the one lifecycle rule the component imposes on its hosts
(construct it in `onCreate`).

*Two things deliberately not done.* A pick that will not decode is treated as **a cancel, not
Blank** — the two are indistinguishable from the caller's side and only one of them is safe. And a
static row that vanished between the tap and the apply raises a **problem dialog** and leaves the
page exactly as it was, in both hosts; a template that disappeared must never become blank paper by
default.

*Left for the user's eye:* undo/redo of a template change (adb cannot inject the multi-finger
double-tap). Docs (`docs/templates.md`, `docs/library.md`, `docs/notebook.md`) stay in G6 as planned
— `docs/notebook.md` still describes arc 12's four-row sub-sheet, which is now a full-screen browser.

### G4 — Import and export
**Status:** ✅ Complete (commit `8485ee8`)

**First step is the SAF probe on the Nomad** (see the traps). Then: `ACTION_OPEN_DOCUMENT` limited
to `image/png`, `image/jpeg`, `image/webp`; a fit choice (Fit · Stretch · Fill) and a name + folder
in one sheet; decode with bounds-first sampling, downscale to the cap, re-encode lossless WEBP,
store with `flags` = fit. The apply path renders original → fit → page rect. A **Fit…** row appears
on an imported card's management sheet. Export: `ACTION_CREATE_DOCUMENT`, PNG at page size, for
built-ins and static cards alike. Reserved-folder guard on every landing spot.
**Gate:** JVM tests (fit arithmetic for all three modes at several aspects, cap/downscale decision,
name collision suffixing); debug + release build; Nomad walk (import a PNG of each of three aspects,
check the card and the applied page, export both kinds and re-import the result, refuse an
oversized file with a dialog, refuse a landing in Default).
*Opus for the codec + fit path; Sonnet for the sheets.*

**Questions to resolve at phase start — answered 2026-08-26, do not re-ask:**

| Question | Answer |
|---|---|
| **Is SAF usable on the Supernote?** | **Yes — the probe passed, no fallback needed.** Both `ACTION_OPEN_DOCUMENT` and `ACTION_CREATE_DOCUMENT` resolve to `com.android.documentsui/.picker.PickActivity` (`enabled=true exported=true isDefault=true`, `/system/priv-app/DocumentsUI`) and both render on the Nomad: Open shows a browsable Recent-images grid with roots behind the hamburger, Create shows a folder with a name field, create-folder and **SAVE**. |
| **Stored encoding + downscale bound** | **Lossless WEBP, longest edge ≤ 1× the page's long edge** (1872 px on a Nomad), never upscaled. Measured at 2× (2106×2808): a clean vector-ish template is 0.004 MB lossless, a mild greyscale scan **3.56 MB**, a grainy scan **12.51 MB**, a photo **12.86 MB** — so lossless at 2× writes blobs the 8 MiB `CursorWindow` cannot read back (the B3 trap, arrived by way of the spec's own encoding choice). At 1× a mild scan is 1.45 MB and the worst cases land ~4–5 MB. The user chose exact pixels over headroom: **the refusal dialog is a live path, by design.** |
| **The blob cap** | **6 MiB** (`6 × 1024 × 1024`), on the *encoded* bytes after the downscale. Two thirds of the read ceiling, above every measured case. |
| **Built-in export** | **Dropped.** G4's "for built-ins and static cards alike" collided with G1's frozen "Blank, Default and the three built-in papers do not long-press at all"; **G1 wins** — the sentinels stay completely inert and only an imported static template exports. |

**Outcome (2026-08-26):** built and walked on the Nomad; **704 JVM tests** across the five modules
(was 683 — `:app` alone went 516 → 537), debug + release both build, release signs, crash buffer
empty. Version stays `0.1.0-ratta`; g-paper pin stays 0.1.6. No new dependency, no schema change,
no migration.

*What landed.* `TemplateImport` (pure, JVM-tested) — the sample/scale/cap/name arithmetic;
`TemplateTransfer` — both `ActivityResultLauncher`s, the decoder, the three sheets, and the export
render, beside `TemplateBrowser` rather than inside it (the browser is about what is *in* the
library; this is about what crosses its edge). `IndexRepository.setTemplateFit`. An **Import**
button (`ic_photo_plus`) in the browser's bottom bar — so all three hosts have it — plus **Fit…**
(`ic_aspect_ratio`) and **Export…** (`ic_download`) on an imported card's management sheet. Three
Tabler drawables in `:sn-screen`; og's `ic_import` was deliberately *not* reused — it is a notebook
with an arrow, and this is a picture.

*The measurement that changed a locked line.* G4's text said "re-encode lossless WEBP" and the cap
was to be set "once the probe says what a real scanned page costs". Measured at 2× the page
(2106×2808), lossless comes to **3.56 MB** for a mild greyscale scan, **12.51 MB** for a grainy one
and **12.86 MB** for a photo — so the spec's own encoding choice walks straight into the B3 trap and
writes blobs the 8 MiB `CursorWindow` cannot read back. Put to the user with the numbers: **lossless
kept, bound dropped to 1× the page's long edge, cap 6 MiB**, and the refusal dialog is a live path
by design rather than a formality.

*Three calls worth knowing.*
**(1) Built-in export was dropped.** G4's "for built-ins and static cards alike" collided head-on
with G1's frozen "Blank, Default and the three built-in papers do not long-press at all". The user's
call: **G1 wins** — the sentinels stay completely inert, and only an imported static template
exports. Import stands down inside **Default** for the same reason (a button that could only refuse
itself is not a button), so that folder's bottom bar is now empty of all three controls.
**(2) The fit is asked before the name.** The order is the point: the decode, the resize, the encode
and the cap check all happen on IO *before* a word is asked, so a file that is going to be refused
is refused before the user has spent their two decisions on it.
**(3) `pendingExportId` is saved and restored.** A `CREATE_DOCUMENT` is the one operation whose state
outlives the call, and DocumentsUI is another process on a memory-tight device — a host killed
behind it would come back holding a `Uri` with nothing to write into it. `TemplateBrowser.saveState`
/ `restoreState` pass it through, and both hosting Activities wire them.

*The bug the tests could not reach, and it is the phase's lesson.* The first import on the glass
failed with "Couldn't read that image" for **every** file. `BitmapFactory.decodeStream` with
`inJustDecodeBounds = true` returns null **by contract** — it only fills `outWidth`/`outHeight` —
and `open(uri)?.use { decodeStream(...) } ?: return Failed` binds the elvis to the whole expression,
not to the stream. So the bounds pass "failed" on every valid image, before the file was ever really
read. `TemplateImport`'s arithmetic was correct and green throughout; the Android half never got as
far as calling it. **An elvis after a `?.use { }` answers for the lambda's result, not the receiver's
nullability** — and a null-returning-by-contract call inside one silently inverts the check.

*Confirmed on the glass, through the log.* `2000x1200 → 1872x1123` (downscale to the page's long
edge) · `1500x1500 → 1500x1500` and `1200x1600 → 1200x1600` (**never upscales** — the `scaledSize`
null path) · `6000x8000 → 1404x1872 sample=4` (the sampled decode: 8000/4 = 2000, still clear of
1872, then the exact resize) · the 7.9 MB noise refused at **7,885,510 bytes** against the cap · and
an exported card re-imported to **33,784 bytes**, byte-for-byte the size of the original blob, so
the export→re-import round trip is stable. All three fit modes checked by eye against a test image
carrying a border, two diagonals, a circle and four corner blocks. Crash buffer empty.

*The new standing trap* (recorded above): **a SAF pick cannot be driven by adb.** DocumentsUI's
chrome answers injected taps perfectly, but its file items are inert to every injectable input —
`input tap`, `touchscreen tap`, 60/150/900 ms presses, `stylus`/`mouse`/`trackball`, and TAB focus
stops at the first folder with DPAD refusing to enter the grid. Every path that begins with choosing
a file is a user checklist item. That is also what caught the elvis bug: the checklist's item 1 was
the only thing that could.

### G5 — Pinned, Recents, Search
**Status:** ✅ Complete (commit `faec9e7`)

Three flat, paginated, mutually exclusive views on the same grid, reached from the Templates
screen's top bar: **Pinned** (a sentinel `LIST` row + `list_item` edges, templates only, Pin/Unpin on
the management sheet, scrubbed on delete), **Recents** (device-local prefs, newest first, recorded
**only** when a template is actually applied — creating, importing and saving are not uses), and
**Search** (name match across all template folders, flattened). The built-in papers are pinnable
and recordable through their sentinel ids.
**Gate:** JVM tests (pin toggle + scrub, recents ordering + self-healing prune of dead ids, search
matching); debug + release build; Nomad walk (pin/unpin, the three views' exclusivity, a recents
entry appearing only after a real apply, a deleted template vanishing from both lists).
*Sonnet, with Opus on the recents/pin store.*

**Questions to resolve at phase start — answered 2026-08-26, do not re-ask:**

| Question | Answer |
|---|---|
| **Where the three controls live** | **The bottom bar, on the left** — the library's shape, not G5's "top bar" sentence. Pinned · Recents · Search at the bottom-left, and the *top* bar swaps its breadcrumbs for a shelf title + a close ✕ while a shelf is up, exactly as `LibraryActivity` does. Measured first: 6 fixed buttons × 116 px + the pager's 569 px = **1265 px of 1404** on the Nomad, 139 px of headroom, no reflow. |
| **Which hosts** | **All three.** It is one component, so they come free — and Pinned/Recents earn the most while *picking*: the paper used constantly is one tap from the notebook instead of three folders deep. |
| **How a query is typed** | **A dialog, then a flat shelf.** Search opens the family's one-field `NameDialog`; confirming shows the matches as a shelf whose title is the query, closed like the other two, and re-tapping Search re-opens the dialog with the last query in it. An inline field was rejected: `NewNotebookActivity` is `adjustNothing` (G3), so a field in the browser's own chrome would sit under the IME with no way to reach it — and live filtering is a repaint per keystroke on e-ink. |
| **What Search finds** | **Paper only, sentinels included.** Template names anywhere in the tree, plus **Blank** and the three built-ins by their labels — typing "grid" and not finding Grid would read as a bug. **Folders never appear**: a place is not paper, and a flat shelf you tap to pick must not have taps that mean two things. |
| **What can be pinned** | **Static templates and the three built-ins**; never a folder, never Blank (it is already card #1 at the root, forever). |
| **Do the built-ins long-press?** | **Yes — to exactly one row, Pin / Unpin.** This reverses G1's "the built-in papers do not long-press at all" *for this row only*, on the user's call, and the reason is the reason G1 gave: its rule was written because the sentinel's only candidate row was *Template options…*, which opened G2, and **a row that opens nothing is worse than no row**. Pin is a row that does something. **Blank and Default still do not long-press at all.** (Note this is the *opposite* call to G4's dropped built-in export — the difference is that Export on a sentinel had a live alternative and Pin has none.) |
| **Does a shelf persist?** | **No.** The browser opens at the templates root, in the tree, every time, in all three hosts. A shelf is a glance, not a place to live — and persisting a Search would mean writing a **name** into device-local plaintext prefs, which the family's prefs rule forbids. |
| **When Recents records** | On an **apply that resolved**: the New Notebook screen creating from a pick, and the notebook re-papering a page. A pick whose row vanished (the problem dialog) records nothing; **Blank records nothing**. A re-pick of the ticked card *does* record — the prefs write is not a page change and raises no undo step. |

**Outcome (2026-08-26):** built and walked on the Nomad; **753 JVM tests** across the five modules
(was 704 — `:app` alone went 537 → 586), debug + release both build, release signs, crash buffer
empty. Version stays `0.1.0-ratta`; g-paper pin stays 0.1.6. No new dependency, no schema change,
no migration.

*What landed.* A second `LIST` sentinel (`TEMPLATE_PINNED_LIST_ID`, "tpinnd") and an
`IndexRepository` shelf region (pin/unpin/`pinnedTemplateIds`/`aliveTemplates`/`searchTemplates`);
`ObjectDao.searchOfType` (a `LIKE … ESCAPE '\'` with **no `parentId` at all**) and `aliveOfType`
(one blob-free read per shelf). Two pure, JVM-tested files —
**`data/template/TemplateSearch`** (the `LIKE` pattern, the label matcher, the runnable check) and
**`templates/TemplateShelves`** (what is pinnable, the pinned composition, the recents ordering and
its prune set, the search card composition). **`templates/TemplateShelfView`** is the shelves
themselves, beside `TemplateBrowser` the way `TemplateTransfer` is: this class is about *where you
are*, and a shelf has no where. `templates/TemplateRecents` is the one place that decides what
counts as a use. Chrome: three buttons at the bottom-left, a `shelfTitle` + its own ✕ in the top
bar, and a pin badge on `card_template` — the notebook card's badge in the notebook card's chip.

*One implementation, two stores.* `RecentsPrefs` was generalised rather than copied: same
"move to front, cap, prune" arithmetic, a `templates(context)` factory beside `SortPrefs`'s, and
`RecentEntry.notebookId` renamed to `id` **keeping `@SerialName("notebookId")`** — blobs written
before the rename are on every device this app has ever run on, and a field name is not worth a
silently emptied recents shelf.

*Four calls worth knowing.*
**(1) The built-ins long-press now — to exactly one row.** The wizard said they are pinnable and G1
said they do not long-press at all; put to the user, **the pin wins**, and for G1's own reason: G1's
rule was written because the sentinel's only candidate row *then* was *Template options…*, which
opened the abandoned G2, and **a row that opens nothing is worse than no row**. Pin is a row that
does something. Blank and Default still do not long-press. Note this is the *opposite* call to G4's
dropped built-in export — the difference is that Export on a sentinel had a live alternative and Pin
has none.
**(2) The browser owns the host's ✕ now.** In a shelf the Templates screen would have shown two
identical ✕ side by side — one leaving the shelf, one leaving the screen — which is a choice nobody
can make by looking. `showCloseButton` moved the button under the browser so a shelf can hide it,
and back peels the same way: shelf first, then folders, then out.
**(3) A shelf is a glance, not a place.** Nothing persists — not the mode, not the query. The
browser opens in the tree, at the root, in all three hosts. Persisting a search would also mean
writing a **name** into device-local plaintext prefs, which the family's prefs rule forbids; the
query lives in the `TemplateShelfView` instance and dies with the screen.
**(4) Duplicate stands down on a shelf**, with New folder and Import. It lands a copy in the
*original's* folder, which a shelf is neither standing in nor showing — in the tree the new card
appears under your finger, on a shelf it would read as a row that did nothing.

*Confirmed on the glass.* The bottom bar measured **exactly** as written: 3 shelf buttons at
0–348 px, Sort/New folder/Import at 1056–1404, the pager invisible between them, no reflow. Pinned →
Recents switched rather than stacked (one field, so exclusivity is structural). A built-in pinned
from inside **Default** showed on the Pinned shelf opened from inside **test** — a shelf cuts across
the tree. The two orderings are visibly different and correct: Pinned drew *Grid, tpl-square* (the
built-in first, then the rows in the screen's sort) while Recents drew *tpl-square, Grid* (stored
order, newest first). A card wore **both** badges honestly — the tick top-left, the pin top-right.
Deleting a pinned, recently-used template from the Pinned shelf removed it from **both** shelves and
left the built-in alone. Search from the root found all four `tpl` rows two folders down, including
`x-tpl-huge` (substring anywhere), and found the **Grid** built-in by its label. Recents stayed
empty through browsing, pinning and searching and filled only at the apply — the log reads
`template use recorded` then `re-paper minted template`. Re-picking the ticked card logged
`template use recorded` then **`re-paper reuses template` with no `re-papered` line**: G3's true
no-op survives, and the prefs write raised no undo step. Back peeled shelf → tree → out; a cold
restart landed in the tree. Crash buffer empty.

*Two traps this phase paid for.*
**The refusal borrowed the wrong words.** An empty query first raised *"That name won't work / Name
cannot be empty"* — `name_problem_title` reused out of habit. **A query is not a name**, and that
dialog answers a question the user did not ask; it now has its own two strings. Worth watching for
wherever `NameDialog` gets reused for something that is not a name.
**`TemplateBrowser` hit 831 lines** — over this app's ~800 rule. Rather than write a justification,
the shelves came out into `TemplateShelfView` (708 / 212), which is the better shape anyway and is
the same seam G4 used for `TemplateTransfer`.

*The `input text` trap re-confirmed, and the way through it.* `adb shell input text "grid"` left the
field on its hint — PinyinIME eats injected keys, exactly as recorded. The query **was** typed by
tapping the on-screen keyboard: the IME window is invisible to `uiautomator dump`, so its key
centres came off a `screencap` read as an image (the capture is 1:1 with device pixels), and the
dialog's own 350 px shift under the IME is the tell that the keyboard is actually up.

**User checklist — restoration only, nothing about G5 is unverified:**
1. **Re-import `tpl-square`.** The delete-scrub walk needed a pinned, recently-used static template
   and `tpl-square` was the one to hand, so it is gone. Its source is staged back on the device at
   `/sdcard/Download/tpl-square.png` — Templates → **test** → Import → Downloads → tpl-square.png →
   Fit → name it `tpl-square`. (A SAF pick cannot be driven by adb; that is the standing G4 trap.)
2. **The page `abc / abc 20260822 001 / page 1` was re-papered** three times during the walk and is
   now on the `tpl-square` image (whose library row was then deleted — the page keeps its pixels,
   which is the rule). Undo it back if you want it as it was: the multi-finger double-tap, which adb
   cannot inject.

### G6 — Review, hardening, docs, freeze
**Status:** ⬜ Not started

`/code-review` over the **whole arc range** (G1→G5 — note G2 is a revert, so the range's net
content is G1 + G3–G5, `high`) — the arc, not the phase; every finding
treated as a hypothesis whose premises get checked before it is fixed. Boundary audit (nothing new
crosses into `:extension-api` or `:ext-scratchpad`; `:sn-screen` holds anything both surfaces use).
Full JVM suite; debug + release build; a Haiku device walk of everything adb can see; a short user
checklist for what only an eye and a pen can judge. Docs: **`apps/notesprout_ratta/docs/templates.md`**
(the library as a feature — the two kinds, the Default folder, the three hosts, the `.soil` token,
the failure table, and a line on the abandoned generator options so the question stays closed), plus edits to `docs/library.md`, `docs/notebook.md` (the page-paper row now opens a screen),
`apps/notesprout_ratta/CLAUDE.md`, root `CLAUDE.md`, and memory. Then commit + freeze.
*Fable is out for this arc — review runs as `/code-review`, fixes go to Opus.*

---

## Verification (end of arc)

1. All JVM unit tests green (`./gradlew test` in `apps/notesprout_ratta`).
2. Debug + release builds compile; release signs with the debug keystore.
3. Haiku device agents on the Nomad: install, bootstrap → recovery → unlock, library CRUD
   walk, notebook create/open/write-persist (committed strokes in screencap), page
   insert/flip/delete, undo/redo, relaunch restores, `logcat -b crash` empty.
4. Format-compat proof (R6, both directions, Paper dev ↔ SN dev on the Nomad).
5. User checklist (short, eye/hand only): live ink latency + no ghost overlay, eraser
   feel, lasso trail + drag, page-flip feel, unlock flow.
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

JVM tests: `./gradlew test`. If g-paper needs a change: `cd ~/git/g-paper && ./gradlew
publishToMavenLocal`, bump the pinned version in `app/build.gradle.kts`.

## Appendix — Reference map (read, don't copy)

| Topic | Reference |
|---|---|
| Paper v0 tree (the parity target) | `git show 87277da:apps/notesprout_paper/<path>` (single-module `:app`, 54 files) |
| Paper v0 plan (constants, thresholds, DDL) | `git show 87277da:apps/notesprout_paper/PAPER_PLAN.md` |
| Current Paper data/crypto docs | `apps/notesprout_paper/docs/data.md`, `docs/crypto.md`, `docs/library.md`, `docs/notebook.md` (ignore arc 1–7 extension sections) |
| g-paper API + host duties | `~/git/g-paper/docs/{api,architecture,host-responsibilities,integration-guide}.md` |
| Ratta engine internals | `~/git/g-paper/gpaper-ratta/src/main/java/.../{RattaEngine,RattaPaperView,RattaInkMap,SupernoteInk}.kt` |
| E-ink design system | root `CLAUDE.md` + `apps/notesprout_paper/paper-screen/src/main/res/values/` |
| Icon vector recipe | `apps/notesprout_paper/app/src/main/res/drawable/ic_launcher_foreground.xml` (mirror it) |
