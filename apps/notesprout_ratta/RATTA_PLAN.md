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
**Status:** ⬜ Not started

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

**Outcome:** —

### R2 — Library
**Status:** ⬜ Not started

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

**Outcome:** —

### R3 — Notebook core (write on it)
**Status:** ⬜ Not started

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

**Outcome:** —

### R4 — Multi-page + gestures + undo/redo
**Status:** ⬜ Not started

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

**Questions to resolve at phase start:** adopt Paper's exact gesture thresholds
(PAPER_PLAN.md architecture section) or retune for Nomad; page-flip visual (instant swap
vs. any indicator).

**Outcome:** —

### R5 — Lasso + polish
**Status:** ⬜ Not started

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

**Outcome:** —

### R6 — Hardening, compat proof, review, freeze
**Status:** ⬜ Not started

Code-review pass (findings fixed or explicitly accepted, recorded here); **format-compat
proof on the Nomad**: same passphrase in both apps, adb-copy a Paper-created `.soil` into
SN's Garden (+ index row) and open it — and the reverse into Paper dev; full regression
(Haiku device agents + the arc's short user checklist); docs freeze (`docs/` under the
app), memory + root CLAUDE.md updates; version stamp; commit + push.
**Gate:** everything in Verification below.

**Questions to resolve at phase start:** review depth (/code-review level), whether the
compat proof should also cover an encrypted notebook created before a passphrase rotation.

**Outcome:** —

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
