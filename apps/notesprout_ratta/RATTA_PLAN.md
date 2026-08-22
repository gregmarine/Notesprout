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
**Status:** ✅ Complete (Nomad-verified + user all-clear 2026-08-22)

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
