# Notesprout SN — Claude Code instructions (apps/notesprout_ratta)

**Branch `ratta` · Package `com.symmetricalpalmtree.notesproutsn` · Label "Notesprout SN"
("Notesprout SN Dev" in debug) · Plan/status: `RATTA_PLAN.md` — read it whole at every
phase start; it holds the working protocol, model recipe, locked decisions, and phase
statuses.**

A from-scratch, **Supernote-only** rebuild of Notesprout (the "ratta paper" experiment).
Paper v0 (`git show 87277da:apps/notesprout_paper/...`) and the original app are reading
references — **no app code is copied from either**. Devices: **Nomad only by default**
(SNN `SN078D10012852`); Manta (SNM `SN100C10023972`) only when the user explicitly asks.
The Manta identifies as a Nomad — target by serial.

## Standing rules

All root `CLAUDE.md` rules apply (Kotlin/17, kotlinx-serialization only, no new Gradle
deps without discussion, no Material Components, no `runBlocking` on main, `Slog.d` not
`Log.d`, e-ink design system, Tabler icons only). Plus, for this app:

- **Three modules, own Gradle root:** `:app` (the host), `:extension-api` (the contract
  library — depends on nothing in `:app`, stdlib only), `:ext-mlkit` (the **NSE · ML Kit**
  extension APK). **The recognizer point is SN's ONE extension surface** (arc-3 amendment to
  the arc-1 no-extensions rule): `ACTION_HANDWRITING_RECOGNIZER` / `IHandwritingRecognizer`
  exists solely so other HWR engines can slot in later — headings and the markdown engine are
  core, and **no other capability point may be added** without a new user decision. No
  extension stores. The action strings are SN-namespaced (`…notesproutsn.extension.*`) so
  Paper's extensions on the same device are never discovered; trust is same-signature both
  ways (`ExtensionRegistry` at discovery + bind-time re-check, `HostCallerCheck` first thing
  in every stub method), the ML Kit dependency lives in `:ext-mlkit` only, **only `prepare()`
  may start a model download** (host consent dialog first — and never at notebook open, which
  only warms an already-present model), and recognized text is never logged on either side
  (counts + durations only).
- **Data model is Paper's, byte-for-byte format-compatible** — `notesprout.db` `objects`
  table (user_version 1) + `Garden/<uuid>.soil` universal `notebook` table v1 +
  `notebook_meta`, StrokeCodec format B, encrypt-by-default global key, SQLCipher stock
  defaults. Any schema/codec/crypto change must keep a Paper-created file openable and
  vice versa. References: `apps/notesprout_paper/docs/data.md` + `docs/crypto.md`.
- **`data/SoilFile.kt` is the only path constructor.** No `extensionStoreFile` here.
- **Every SQLCipher open routes through `crypto/SoilCrypto`.** Passphrases never logged,
  never in Intent extras, never in the index. Never delete a DB on corruption.
- **`IndexGuard.ready(this)` first thing in every index-touching `onCreate`**;
  `BootstrapActivity` is the only index opener and is `noHistory`.
- **g-paper 0.1.4, `gpaper-core` + `gpaper-ratta` only** (mavenLocal). No `gpaper-onyx`,
  no BOOX repo, no jetifier, no jniLibs pickFirsts, no `tools:replace` label. Engine gaps
  are fixed in `~/git/g-paper` (bump version, `publishToMavenLocal`, re-pin) — never
  worked around in the host.
- **Host does only the documented host responsibilities**
  (`~/git/g-paper/docs/host-responsibilities.md`): page swap = `clearForContentSwap` →
  `setPageSize`/`setTemplate` → `loadStrokes`; undo/redo via `addStrokes`/`removeStrokes`;
  chrome via `setExclusionRects`; lifecycle `resumeDrawing`/`releaseForHandoff`/`release`.
- **Frame-silence rule:** never present an app frame while `paper.isPenActive` — route
  chrome text/updates through a pen-idle gate. Five recorded exceptions (listed with their
  justifications in `docs/notebook.md` § frame-silence): the delete-page sheet at long-press,
  the selection toolbar's show at lasso completion, the "Opening…" overlay's hide when the
  page lands, the "Recognizing…" overlay around a heading convert, and the selection
  toolbar's own-tap re-shows (H toggle / level pick / post-edit re-anchor) — all one chrome
  frame at a deliberate act or a boundary, never under live ink (R3's tool-panel-close
  exception retired with the panels in P1). Any new exception needs the same written
  justification.
- **Toast vs. dialog:** a toast only confirms something that already happened; anything
  explaining why a tap *didn't* work is a problem dialog. On e-ink a missed toast reads
  as "broken".
- Portrait-locked everywhere · one layout per screen · no colour in chrome (ink is fixed
  black — P1 removed the tool panels) · TopGuard is 0 on Ratta — chrome sits flush at the top
  edge · notebook writes go through the session's single serial `SoilWriter` · undo/redo
  replays through the store then reloads the page (DB is the source of truth) · no file
  over ~800 lines without a written reason.
- **Supernote swallows `adb shell input text`** — scripted device tests tap the on-screen
  keyboard or avoid text entry. EPD live ink is invisible to screencap; only committed
  strokes screenshot-verify.

## Build & install

See `RATTA_PLAN.md` appendix. Debug: `./gradlew assembleDebug` → `adb -s SN078D10012852
install -r`. Release is unsigned + hand-signed with the debug keystore. JVM tests:
`./gradlew test`. Java 17 comes from `org.gradle.java.home` (Temurin-17).
