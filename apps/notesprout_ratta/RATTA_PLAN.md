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
**Status:** ⬜ Not started

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

**Questions to resolve at phase start:** whether the Manta leg is in scope (needs the second
device — Nomad-only is the standing rule) or the size-mismatch case is proven by a
hand-built page; whether a cross-notebook paste should toast the source notebook's name.

### B3 — Review + hardening + docs + freeze
**Status:** ⬜ Not started

Arc-range `/code-review` (level asked at phase start; every arc so far froze at **high**),
findings fixed or explicitly accepted → monorepo `BACKLOG.md`; docs (`docs/clipboard.md`
**new** under the app; `docs/notebook.md` long-press sheet + undo rows; `docs/library.md` if
the index row type is described there; frame-silence ledger if any new exception); app
`CLAUDE.md` touch-ups; memory + this file's outcomes; version-stamp decision; full regression
(Haiku walk + the short user checklist); commit + push; arc freeze.
**Gate:** everything green or explicitly accepted; user all-clear.

**Questions to resolve at phase start:** review level; version stamp (0.1.0-ratta through six
arcs — the user's call).

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
