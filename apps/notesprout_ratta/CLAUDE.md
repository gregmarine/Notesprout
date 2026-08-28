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

**Subsystem docs (`docs/`) — read the matching one before working in that area:**
`docs/library.md` (library screen, naming schemes) · `docs/notebook.md` (the notebook screen:
tools, selection, **snap to guides**, headings, Contents, **Recents**, gestures, **the page
template picker — the whole library since arc 13**, undo, frame-silence ledger) ·
`docs/links.md` (arc 6: link rows/payload, render, picker + create-in-picker, follow + trail) ·
`docs/templates.md` (arc 13: **the paper library** — the two kinds and no third, the sentinels that
are not rows, the reserved **Default** folder, the one browser its three hosts share, SAF import and
export, the Pinned/Recents/Search shelves, the `.soil` **token** and reuse-before-mint, the failure
table, and the abandoned generator idea) ·
`docs/clipboard.md` (arcs 7–8: the clipboard — one index row, one envelope, two kinds; the page
half's long-press sheet and the object half's Copy/Cut, tap-to-place and lasso popup, both
within and **across notebooks**, where a copied link's own-notebook target is re-pointed at the
notebook it came from) ·
`docs/extensions.md` (the **seam**: the three extension points — the recognizer, arc 11's
screen-owning scratch pad, and arc 15's generic exporter point — the extension store, the tier-2
recipe for an extension-owned screen, and **the boundary audit**) ·
`docs/export.md` (arc 15: notebook export as a feature — the library sheet's Export… row, the
`ExportActivity` screen, the keying trio and its host-side transforms, `SoilOpenFiles`, the
conditional-deletion rule, the failure table) ·
`docs/scratchpad.md` (arc 11: the Scratch Pad as a feature — screen, tools, pages, store layout,
both transfers, failure table) ·
`docs/sn-screen.md` (arc 11 / J1: the shared `:sn-screen` paper-screen library — what may live
there, what may not depend on it, and the `nonTransitiveRClass` flag that holds it together).

## Standing rules

All root `CLAUDE.md` rules apply (Kotlin/17, kotlinx-serialization only, no new Gradle
deps without discussion, no Material Components, no `runBlocking` on main, `Slog.d` not
`Log.d`, e-ink design system, Tabler icons only). Plus, for this app:

- **Six modules, own Gradle root:** `:app` (the host), `:sn-screen` (the shared paper-screen
  library — arc 11 / J1: the design resources and the screen helpers both paper surfaces need,
  depends on g-paper + androidx only and **never** on `:app` or `:extension-api`; **a fix to shared
  screen logic goes there, never in a consumer** — that rule is the whole reason the module exists,
  and breaking it recreates the `RattaNotebookView` sibling-copy trap one file at a time),
  `:extension-api` (the contract library — depends on nothing in `:app`, stdlib only),
  `:ext-mlkit` (the **NSE · ML Kit** extension APK), `:ext-scratchpad` (the **NSE · Scratch Pad**
  extension APK — arc 11 / J3: depends on `:extension-api` **and** `:sn-screen`, never `:app`;
  no `tools:replace`, no libc++ `pickFirsts` — those are Paper's Onyx tax and SN has no Onyx),
  and `:ext-soil` (the **NSE · Soil Export** extension APK — arc 15 / E1: depends on
  `:extension-api` only).
  `gradle.properties` sets
  `android.nonTransitiveRClass=false` so `:app`'s `R` keeps seeing the moved resources —
  the move needed no import sweep, and undoing that flag breaks every one of them.
- **SN has THREE extension points** (the arc-15 amendment, on the user's explicit 2026-08-27
  decision — which is exactly the "new user decision" that rule demanded; arc 11 made the same
  amendment for the second):
  `ACTION_HANDWRITING_RECOGNIZER` / `IHandwritingRecognizer`, so other HWR engines can slot in
  later (headings and the markdown engine are core), `ACTION_SCRATCH_PAD` / `IScratchPad` —
  the first **screen-owning** point, served by `:ext-scratchpad` (J2 shipped the store and the
  contract half; **J3 shipped the point**: the AIDL, `WireStroke` / `InkBundle` / `InkChunks`,
  `ExtensionBinder.hold` + `HeldBinding` — SN's **only** bind held across more than one call,
  because the operation is the showing of a screen — `ScratchPadClient`, `TransferCaps`, and the
  APK; **J4 the real screen, both entry buttons and the EPD handoff; J5 the two ink transfers; J6
  the review, the boundary audit and the docs** — the arc is **complete and frozen**, 2026-08-25).
  Its screen is exported under
  `ACTION_SCRATCH_PAD_SCREEN` with `<category DEFAULT>` and refuses any caller that is not a
  `startActivityForResult` from the host (`HostCallerCheck.enforceActivity`), so the host **must**
  launch it with an `ActivityResultLauncher`. And `ACTION_NOTEBOOK_EXPORTER` /
  `INotebookExporter` (arc 15 / E1) — the **generic exporter point**: any number of trusted
  exporter extensions may register (`ExtensionRegistry.exporters()` is plural), each `describe()`s
  the one format it offers via a bounded declarative descriptor the host renders with its own
  widgets, and the host's Export screen lists whatever is installed. **The host keys, the
  extension delivers via fds**: everything that touches a key (checkpoint, keying transform, SAF
  destination) runs host-side; the extension receives two `ParcelFileDescriptor`s + an
  `ExportSpec` (id → value map + display name — no id, no path, no secret) and writes only
  through the granted write fd. A typed passphrase **never** crosses — the reserved
  `ExporterContract.OPTION_KEYING` (and any passphrase-kind option) is executed by the host, and
  the spec carries only the chosen value id. Served by `:ext-soil` (**NSE · Soil Export**).
  **No FOURTH capability point may be added** without another user decision. Extensions get one host service, the
  **extension store** (`IExtensionStore`, `data/extstore/`, `docs/extensions.md` § "The extension
  store"): per-package, encrypted under the global key at `Garden/<pkg>.db`, minted per bind,
  uid-bound, revoked with the unbind — because **an extension writes nothing to disk itself,
  ever**. The action strings are SN-namespaced (`…notesproutsn.extension.*`) so
  Paper's extensions on the same device are never discovered; trust is same-signature both
  ways (`ExtensionRegistry` at discovery + bind-time re-check, `HostCallerCheck` first thing
  in every stub method), the ML Kit dependency lives in `:ext-mlkit` only, **only `prepare()`
  may start a model download** (host consent dialog first — and never at notebook open, which
  only warms an already-present model), and recognized text is never logged on either side
  (counts + durations only).
- **The Scratch Pad is not ours to change from here** (arc 11, `docs/scratchpad.md`). It is the
  `:ext-scratchpad` APK: its own process, its own g-paper surface, its own undo stack, and it
  **writes nothing to disk itself** — its pages live in the host store, lent for the showing and
  revoked with the unbind. It opens **no `.soil`**, and the notebook behind it is **not sealed** —
  what the notebook gives up is the EPD pipeline, not its data. Both transfers are **copies** that
  cross only through the held service (never the Intent, never a file), carry **no ids**, and keep
  coordinates 1:1. The pad's tools are the notebook's, fixed: a pad that lassoed differently one tap
  from the notebook would read as a bug, so a change to the notebook's ink feel is a change to both.
  Touching either paper surface's handoff means re-reading the ordering rule in
  `docs/extensions.md` § the tier-2 recipe first; a failure there is fixed in **g-paper**.

- **Paper is identified by a TOKEN, not a kind** (arc 13, `docs/templates.md`). A `.soil` `template`
  row's `text` is `""` (blank — no row at all), `LINED`/`DOTTED`/`GRID` byte-for-byte as every build
  in this family has written them, or `IMG#<8 hex>` for an imported picture — whose digest covers the
  **fit mode** as well as the bytes. Reuse is `token + page size`, **reuse before mint**, and nothing
  ever soft-deletes a template row. The library's **sentinels are not rows** (Blank, the reserved
  **Default** folder, the three built-in papers): hardcoded ids, nothing seeded, nothing repairable
  — so any prune against "alive rows" must exempt them by name. The browser **never opens a `.soil`**
  and never returns pixels; it returns a `TemplatePick` and the caller does the read and the write.
  Paper that will not **draw** is not paper that is **absent** and neither is blank: a failed render
  leaves the page exactly as it was. **No adjustable generators** — built, shown, abandoned
  (arc 13 / G2); import is how a user gets different paper, and re-raising it needs a fresh user
  decision.
- **Data model is Paper's, byte-for-byte format-compatible** — `notesprout.db` `objects`
  table (user_version 1) + `Garden/<uuid>.soil` universal `notebook` table v1 +
  `notebook_meta`, StrokeCodec format B, encrypt-by-default global key, SQLCipher stock
  defaults. Any schema/codec/crypto change must keep a Paper-created file openable and
  vice versa. References: `apps/notesprout_paper/docs/data.md` + `docs/crypto.md`.
- **`data/SoilFile.kt` is the only path constructor** — `extensionStoreFile` included
  (arc-11 / J2 amendment: the function exists now, and it is the only way to derive an
  extension store's `Garden/<pkg>.db`).
- **Every SQLCipher open routes through `crypto/SoilCrypto`.** Passphrases never logged,
  never in Intent extras, never in the index. Never delete a DB on corruption.
- **`IndexGuard.ready(this)` first thing in every index-touching `onCreate`**;
  `BootstrapActivity` is the only index opener and is `noHistory`.
- **g-paper 0.1.23, `gpaper-core` + `gpaper-ratta` only** (mavenLocal). No `gpaper-onyx`,
  no BOOX repo, no jetifier, no jniLibs pickFirsts, no `tools:replace` label. Engine gaps
  are fixed in `~/git/g-paper` (bump version, `publishToMavenLocal`, re-pin) — never
  worked around in the host.
- **Host does only the documented host responsibilities**
  (`~/git/g-paper/docs/host-responsibilities.md`): page swap = `clearForContentSwap` →
  `setPageSize`/`setTemplate` → `loadStrokes`; undo/redo via `addStrokes`/`removeStrokes`;
  chrome via `setExclusionRects`; lifecycle `resumeDrawing`/`releaseForHandoff`/`release`.
- **Frame-silence rule:** never present an app frame while `paper.isPenActive` — route
  chrome text/updates through a pen-idle gate. Seven recorded exceptions (listed with their
  justifications in `docs/notebook.md` § frame-silence): the delete-page sheet at long-press,
  the selection toolbar's show at lasso completion, the "Opening…" overlay's hide when the
  page lands, the "Recognizing…" overlay around a heading convert, the selection
  toolbar's own-tap re-shows (H toggle / level pick / post-edit re-anchor), the Contents
  dialog's show/hide (C1 — **the arc-10 Recents panel rides this same exception**, it is the same
  act mirrored), and the object paste's frame at a tap's pen-up plus the lasso popup's
  show/hide (O1) — all one chrome frame at a deliberate act or a boundary, never
  under live ink (R3's tool-panel-close exception retired with the panels in P1). B1's
  paste-placement sub-sheet rides the long-press sheet's exception rather than adding one (it is
  raised from a row of a dialog already up), **and so does arc 12's page-template sub-sheet** —
  whose one blob-free read between the tap and the sheet is deliberately *not* re-gated on the pen,
  because `isPenActive` counts hover. Any new
  exception needs the same written justification. **Arc 11 / J4 added none**: the pad's own screen
  (in `:ext-scratchpad`) carries the same rule and its four frames are the notebook's exceptions in
  scratch-pad form, and the host's "Opening…" box at the pad button rides C1 — the same act as the
  Contents and Recents buttons.
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
