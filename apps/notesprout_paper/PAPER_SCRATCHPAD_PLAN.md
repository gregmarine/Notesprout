# Paper — Extensions arc 6: the Scratch Pad (`NSE · Scratch Pad`)

> **This file is the project's memory across sessions for arc 6.** Context is cleared between
> phases. Everything a fresh session needs — decisions, non-goals, the contract, per-phase tasks,
> tests, status — is here or in the files this document points at. If it isn't written down here
> (or in the repo / project memory), it doesn't exist. **Read this file top to bottom at the start
> of every session**, after `PAPER_PLAN.md` (v0), `PAPER_EXTENSIONS_PLAN.md` (arc 1),
> `PAPER_NAMING_PLAN.md` (arc 2 — the extension store this arc leans on), `PAPER_RECOGNITION_PLAN.md`
> (arc 3), `PAPER_OBJECTS_PLAN.md` (arc 4 — the selection toolbar this arc adds a core action to),
> `PAPER_CONTENTS_PLAN.md` (arc 5) and both `CLAUDE.md` files. `docs/extensions.md` is the
> subsystem reference all arcs write into; `docs/notebook.md` and `docs/library.md` gain sections
> in this arc; a new `docs/scratchpad.md` is the extension's own reference.
>
> **Status: S0 ✅ 9a96c7a · S1 ✅ 98f58f6 (both user-verified SNN / NA5C / MIP11 2026-08-19) · S2 ✅ 374f17f · S3 ⬜.
> Next: S3 (fresh session, phase-start wizard first — freeze as built? remove both debug probes?).**

## Why

The original Notesprout has a **scratch pad** (`apps/notesprout_android`, `ScratchpadActivity` +
`ScratchpadRepository`, `docs/scratchpad.md`): one global, multi-page jotter reachable from the
library and from any notebook, persisted across restarts, with two-way ink transfer — a lasso
selection can be *sent to the scratch pad* (placed on a new or the current scratch page, the pad
opens with it selected) and scratch ink can be *sent to the notebook* (a whole page from the top
bar or a lasso selection from the floating toolbar; the notebook pastes it selected). Arc 6 gives
Paper the same behaviour **as an extension** — and in doing so exercises, for the first time, the
second tier of the UI rule recorded in arc 4: **an extension-owned screen *off* the paper that the
core launches for a result and returns from.** The core grows no second drawing surface: `NSE ·
Scratch Pad` owns its Activity, its g-paper canvas, its tools and its pages; the core adds one
extension point, two entry buttons that exist only while the extension is installed, one core-owned
selection-toolbar action, and the ink transfers.

Two structural things happen alongside, both decided in the planning wizard:

- **A shared `:paper-screen` module.** A second paper surface needs what the notebook screen
  already has — g-paper, the finger-gesture detector, undo history, page math, the stroke codec,
  the top guard, the chrome/exclusion helpers and the e-ink design-system resources. Rather than a
  sibling copy (the original's `RattaNotebookView` trap) those move to a module both `:app` and
  `:ext-scratchpad` depend on. `:extension-api` stays as it is (depends on nothing); `:paper-screen`
  depends on g-paper + androidx only, never on `:app` or `:extension-api`.
- **The store cap rises, and the store learns large values.** Scratch pages persist in the host-owned
  extension store (arc 2); a page's ink is one value, so `STORE_MAX_VALUE_BYTES` goes from 256 KiB to
  **4 MiB** — and since a value that size cannot cross a Binder as a `byte[]`, `IExtensionStore` gains
  **two appended methods** (`putLarge` / `getLarge` over a `LargeValue` = `SharedMemory` + length) — the second exercised
  compatible AIDL change after arc 5's `describeOutline` (`API_VERSION` stays 1; the four existing
  methods keep their transaction codes; an old extension never calls the new ones). Every extension
  gets the larger cap; the 50 000-key cap is unchanged.

That is one new module, one new extension APK, one new point (`SCRATCH_PAD`), two new parcelables,
one constant change + two appended store methods, a small `SelectionActions.merge` change (core actions filter by `appliesTo`),
a new undo action (`Pasted`), and no `.soil` change.

---

## Working protocol

Identical to `PAPER_CONTENTS_PLAN.md` §"Working protocol" — each phase in a **fresh session**:

1. **Phase start (no-assumption QA):** read this file (all of it), the six earlier plans' Locked
   decisions + Architecture + Appendices, `docs/extensions.md`, `docs/notebook.md`, `docs/library.md`,
   `docs/data.md`, `docs/crypto.md` (the store's create entry point), the root `CLAUDE.md`, and
   `apps/notesprout_paper/CLAUDE.md`. Confirm the next `⬜` phase with the user, flip it to `🔄`,
   then **ask the phase's "Questions to resolve at phase start"** one at a time in the wizard
   (option-select) format before writing code — recommended default first, plus "Other". Do not
   assume answers. If a new ambiguity surfaces mid-phase that would materially change the work,
   stop and ask. **Wizard trap:** when the user asks a clarifying question inside the wizard, answer
   it in plain text first (the dialog hides the answer), then re-ask.
2. **Code** — auto mode; inline; **frugal with agents** (Model note); no Gradle dependency beyond
   Appendix B; deliverables **exactly as written** — no added scope, no "improving" adjacent code,
   no scaffolding for later phases.
3. **Test** — `./gradlew testDebugUnitTest` (all modules), then build + install the debug APKs on
   the requested **test devices** and hand the user the phase's numbered on-device checklist (copy
   it, don't invent). EPD overlays are invisible to screencap — the user verifies by eye and
   reports; the automated per-device agent run covers what adb + uiautomator can reach.
4. **Fix → test again** until every test passes (JVM + device checklist).
5. **Docs / memory / CLAUDE.md** — `apps/notesprout_paper/CLAUDE.md` (standing rules + build facts
   only), `docs/extensions.md` (+ `docs/scratchpad.md`, `docs/notebook.md`, `docs/library.md` where
   named), this file's status marker + **Outcome** note, and the project memory
   (`~/.claude/projects/-Users-gregmarine-git-Notesprout/memory/project_paper_scratchpad.md` + its
   `MEMORY.md` index line).
6. **Commit & push** on `paper`. Then the user runs `/clear`.

**Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
`✅ Complete (commit <hash>)`. Update the marker **the moment the state changes**.

**Test devices** (user verifies by eye; always `-s <serial>`; never install on a device the user
didn't ask for; offline → say so and wait). All six extensions installed after S0; the ML Kit model
present.

| Nickname | Device | Serial | Engine | Portrait width |
|---|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` | 748 dp (1404 px @ 300) |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` | 992 dp (1860 px) |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` | 823 dp (1440 px) |

**Device traps carried forward:** NA5C — **BOOX "Freeze new apps"** (Settings → Apps → App Freeze) was
the agent that disabled a freshly installed package: system `ApplicationFreezeHelper` force-stops +
disables it **on a delay (~8 min after install, not seconds)**, so a `pm enable` right after install is
racing it; its list shows launcher apps only, so an extension can never be unfrozen by hand (`pm
enable` over adb is the only way) — **switched OFF on the NA5C 2026-08-19** (turn it back on if wanted);
still confirm `pm list packages -d` after any install; lasso / ink unreachable by adb (Onyx raw callback), finger gestures
reachable; **an extension Activity of a disabled package cannot launch** — the entry point
disappears with discovery, which is the intended behaviour. SNN — `input stylus` reads as a finger
(Android 11), `input text` swallowed, IME invisible to uiautomator; Ratta's Apps grid caches labels
(uninstall + install to refresh). MIP11 — `log.tag` resets to `I` by itself: per-tag `setprop
log.tag.<TAG> DEBUG` before every log read; adb stylus lassos need a dense 25-point loop in one
shell command. **adb cannot do multi-finger gestures on any device** (undo / redo / two-finger insert
are always user items). **New in this arc:** two paper-hosting screens in **two processes** — the
EPD pen pipeline is process-global (g-paper `docs/api.md` §Lifecycle: `releaseForHandoff()` before
launching another paper-hosting screen, `resumeDrawing()` on return); the first S1 device item is
exactly this handoff on NA5C and SNN, and it is the arc's biggest technical risk (Appendix D).

**Model note (user decision, unchanged from arc 5): Fable 5 runs every phase.** Within a phase the
work is inline; agents only for (a) read-only exploration when a survey spans many files (`Explore`,
Sonnet) and (b) the automated per-device verification run — **one Sonnet agent per device, at most
two at a time**, given the phase's checklist verbatim plus the traps above. Never an agent for code
that changes a verified path. **Written for Opus too:** every phase names its files, signatures,
constants and failure texts; anything genuinely undecided is a phase-start question, not an inference.

---

## Locked decisions (planning Q&A, 2026-08-19)

| Area | Decision |
|---|---|
| Where the surface lives (Q1) | **A — extension-owned screen.** `NSE · Scratch Pad` owns `ScratchPadActivity` (its own g-paper canvas, tools, pages) and keeps its pages in the host's extension store; the core adds one point (`SCRATCH_PAD` — "an off-paper screen the core launches for a result"), the entry buttons and the two ink transfers. First exercise of the arc-4 UI rule's tier 2. **B** (a core-hosted hidden `.soil` + core screen, thin extension) was declined — the core would grow a feature back. |
| Storage (Q2) | **Extension store, one key = one page, value cap raised to 4 MiB** (`STORE_MAX_VALUE_BYTES = 4 * 1024 * 1024`) — **and, because a 4 MiB `byte[]` cannot cross the store's Binder (~1 MB transaction cap; surfaced during planning, re-asked and decided 2026-08-19), `IExtensionStore` gains two appended, compatible methods `putLarge(key, LargeValue)` / `getLarge(key) → LargeValue`** (the `RenderedTemplate` ashmem handshake) for values over `STORE_MAX_INLINE_BYTES` (512 KiB); the `byte[]` `put` / `get` path keeps working up to that inline cap (`get` of a stored value above it → `IllegalStateException(STORE_VALUE_LARGE)`, "use getLarge"). Chunking pages across keys and a Binder-safe 768 KiB cap were declined. Session-only was declined (the pad persists across restarts, like the original). A page whose encoded ink would exceed 4 MiB is **full**: the extension refuses the stroke that would cross it, removes it from the paper, and says so once per page visit (`scratch_page_full`). |
| Store lifetime (Q3) | **The service bind brackets the screen.** The core pre-opens the store on IO, binds `IScratchPad`, calls `begin(store)`, launches the Activity for a result, and on the result (or cancel / host stop) calls `end()` and unbinds + revokes in `finally`. Same uid-bound `ExtensionStoreBinder`, lifetime = the screen's; nothing new in the trust model. `Bundle.putBinder` on the launch Intent was declined (a new pattern for the audit). |
| Content (Q4) | **Strokes only.** The scratch pad is paper with ink. *Send to Scratch Pad* is a core-owned **INK** action (stroke-only selections; the toolbar already shows Delete only for mixed); *Send to Notebook* returns strokes the core pastes as ink. **Objects never cross** (a mixed selection has no Send action; a scratch page cannot hold an object). |
| Entry points (Q5) | **Notebook + library**, each **present only while `NSE · Scratch Pad` is installed** (the Naming / Contents rule): a top-bar button in the notebook (Tabler `notes`, hint "Scratch Pad", after Contents — see S1 Q2 for the exact slot) with Send-to-Notebook available in the pad; a bottom-bar button in the library (Tabler `notes`, next to Recents) with no send target (the pad's Send buttons are absent). |
| Presentation (Q6) | **Full screen, always.** An immersive full-bleed paper with the notebook's overlay-chrome shape (top bar: Back · Pen · Eraser · Lasso · [Send] · [debug ⋯]; bottom strip: `<` `n / N` `>` — **re-ordered after S1 at the user's call: title top, tools bottom; see the S1 Outcome**); one layout, portrait-locked, no translucent window over the paused notebook (the Onyx raw pen path + EPD handoff stay simple). The original's 75 % floating window was declined. |
| Pages & tools (Q7) | **Notebook parity.** Multi-page: one-finger swipe flips, swipe past the last page **inserts** (arc-1 phase-4 rule), bottom-strip arrows flip, `n / N`, finger long-press → sheet → confirm delete (min 1 page), two-finger horizontal swipe inserts before / after; tools = the notebook's fixed pen / eraser / lasso (`PEN_WIDTH_PX 3f`, `ERASER_RADIUS_PX 15f`, black); multi-finger double-tap undo (2) / redo (3), pad-level history cleared on close. Content persists across opens; the last page is remembered. |
| Notebook → pad (Q8) | **Core-owned INK action + placement ask.** The core adds its own toolbar action `scratch` (label "Pad", icon `notes`, hint "Send to Scratch Pad", `appliesTo = INK`) after Delete while the extension is installed — **no fake object provider**. Tap → the original's dialog **"New page / Current page / Cancel"** → strokes handed to the extension through the bound service → toast "Sent to scratch pad" → the scratch pad opens **on that page with the strokes selected**. **Send = copy** — the notebook keeps its ink; nothing is recorded in the notebook's undo stack. Always-new-page and an extension-contributed action were declined. |
| Pad → notebook (Q9) | **Both entry points, paste selected.** Top-bar **Send** = every stroke on the current scratch page; the pad's own selection toolbar **Send** = the lasso selection only. Both finish the screen with the ink as the result; the notebook pastes it **translated so its bounds' top-left lands at the page origin (0, 0)** with fresh ids, records **one** undoable step (`Action.Pasted`), and leaves it **selected** (`paper.setSelection`). Send = copy — the pad keeps its ink. Both Send buttons are absent when the pad was opened from the library. |
| Sharing code (Q10) | **New shared `:paper-screen` module** — the pure / screen pieces both surfaces need move there (list under Architecture); g-paper (the three artifacts) becomes that module's `api` dependency; `:app` and `:ext-scratchpad` depend on it. A copy into the extension was declined (two copies of gesture / undo / codec logic). The move is **pure** — same packages, no behaviour change — and verified by the existing JVM tests + a v0 regression subset. |
| Phases (Q11) | **Four phases S0 · S1 · S2 · S3** (below), each a fresh session, verified on SNN + NA5C + MIP11. |
| Names + models (Q12) | Point **`SCRATCH_PAD`** (`ACTION_SCRATCH_PAD`, `IScratchPad`, screen action `ACTION_SCRATCH_PAD_SCREEN`); module **`:ext-scratchpad`**, package `com.symmetricalpalmtree.notesprout.ext.scratchpad` (debug `.dev`), label **`NSE · Scratch Pad`** (+ ` Dev`); plan file `PAPER_SCRATCHPAD_PLAN.md` (arc 6); UI word **"Scratch Pad"**; icon Tabler **`notes`** (`IconNames.NOTES` joins the catalog); shared module **`:paper-screen`**; store cap **4 MiB**; parcelables `PaperStroke` + `InkBundle`; **Fable 5 every phase, agents ≤ 2** (Sonnet exploration / device runs). |
| Trust / artifacts / version | Unchanged: same-signature only (both directions — the Activity checks its caller too), debug-only APKs, no app version bump, `ExtensionContract.API_VERSION` stays **1** (a new point + a raised cap + two methods **appended** to `IExtensionStore` are compatible additions — the arc-5 recipe; nothing is reordered), no `.soil` change (`SOIL_VERSION` 1), g-paper stays **0.1.1** unless S1 finds a gap (recorded there). |

## Deferred (recorded 2026-08-19, not built in this arc)

- **Objects on the scratch pad** (send / hold / render objects, dashed placeholders) — Q4 strokes only.
- **A floating-window presentation** on wide screens — Q6 full screen; revisit only if the user
  misses seeing the notebook under the pad.
- **A page-fit / crop step on Send to Scratch Pad** — the original asks "Crop to fit" when the
  selection is larger than the scratch page; Paper's pad page = the same device's screen, so a
  selection always fits; cross-device (a notebook page authored on a bigger screen) is translated
  to the origin and clipped by the page like any other ink. Recorded, not built.
- **Send to *another* notebook / a specific page** — the pad returns to the notebook it was opened
  from; the original's "Send to Notebook" had the same rule.
- **Launch restore of the pad** (a cold launch reopening the pad over its caller) — Paper v0 has no
  surface stack.
- **Extension-store housekeeping** (delete a scratch page's key on page delete — done; compaction /
  vacuum of the store — none in Paper).
- **A "Clear page" action** — the original had none either; delete + insert covers it.
- **Sharing `SelectionToolbar` / `ActionSheetDialog` beyond what `:paper-screen` takes in S0** — the
  pad's selection toolbar is a small own class (two buttons); extract a shared one when a third
  consumer appears.
- **The Extensions-UI-arc items** (`HOST_PACKAGE` meta-data on the `<service>` so a release
  extension is invisible to the dev host; consent; per-extension store deletion) — unchanged.

## Non-goals for this arc (do not build, do not scaffold "for later")

No `.soil` change · no objects on the pad · no colour · no template on scratch pages (white) ·
no cover / thumbnail for the pad · no export · no launch restore · no persisted undo · no
in-process extension code over the notebook's paper (tier 3 of the UI rule stays forbidden — the
pad is *its own screen*, never a view inside the notebook) · no release signing · no version bump ·
no `kotlin-parcelize` · no `kotlinx.serialization` in the extension (its store blobs are a hand
binary format, JVM-tested) · no new Gradle dependency beyond Appendix B.

---

## Architecture

### Module layout (delta)

```
apps/notesprout_paper/
├── PAPER_SCRATCHPAD_PLAN.md        this file
├── settings.gradle.kts             + ":paper-screen", ":ext-scratchpad"
├── docs/extensions.md              gains: ScratchPad contract (+ PaperStroke / InkBundle parcelables, IScratchPad),
│                                   §"The Scratch Pad extension", host behaviour, §"Extension-owned screens (tier 2)"
│                                   recipe, audit rows 28–32, rules 25–27, store-cap note, "Writing an extension" item
├── docs/scratchpad.md              (new) the extension's own reference: screen, tools, pages, store layout, transfers, failures
├── docs/notebook.md                gains: §"Scratch Pad (arc 6)" — the button, the core action, placement, paste-selected, undo `Pasted`
├── docs/library.md                 gains: the library entry point paragraph
├── paper-screen/                   (new, Android library; namespace com.symmetricalpalmtree.notesprout.paperscreen)
│   ├── build.gradle.kts            api(gpaper-core/onyx/ratta 0.1.1), appcompat, core-ktx; buildConfig = true (Slog)
│   └── src/main/
│       ├── kotlin/com/symmetricalpalmtree/notesprout/
│       │   ├── core/   Slog · Device (isBooxDevice/isRattaDevice) · TopGuard · Dialogs · ActionSheetDialog ·
│       │   │           StrokeCodec · InkColorCodec · Bitmaps           (moved verbatim, packages unchanged)
│       │   └── notebook/ PageGestures · PageMath · UndoRedoStack<A> (generic) · PaperToolbar (was NotebookToolbar) ·
│       │               PaperChrome (was NotebookChrome, rects supplier) · ToolbarAnchor  (moved; two renames)
│       └── res/  values/{colors,dimens,styles,themes}.xml (+ values-sw720dp/dimens.xml) · the strings the moved
│                 code references · drawable/{bg_toolbar_button,btn_elevated_background,shape_bordered,
│                 shape_dialog_bordered,toolbar_background_bottom,radio_*,ic_*} (every Tabler icon incl. ic_notes)
├── extension-api/src/main/
│   ├── aidl/…/extension/IScratchPad.aidl · PaperStroke.aidl · InkBundle.aidl · LargeValue.aidl (new) · IExtensionStore.aidl (+ putLarge / getLarge appended)
│   └── kotlin/…/extension/PaperStroke.kt · InkBundle.kt · LargeValue.kt · SharedBytes.kt (new) · ExtensionContract (+ ACTION_SCRATCH_PAD,
│                          ACTION_SCRATCH_PAD_SCREEN, EXTRA_*, RESULT_*, MAX_TRANSFER_*, STORE_MAX_VALUE_BYTES 4 MiB) ·
│                          IconNames (+ NOTES)
├── ext-scratchpad/                 (new, Android application; applicationId com.symmetricalpalmtree.notesprout.ext.scratchpad)
│   ├── build.gradle.kts            implementation(project(":extension-api")), implementation(project(":paper-screen")); HOST_PACKAGE
│   └── src/main/
│       ├── AndroidManifest.xml     application .ScratchPadApplication (registers Onyx + Ratta engines) ·
│       │                           <service .ScratchPadService exported> action SCRATCH_PAD + meta API 1 ·
│       │                           <activity .ScratchPadActivity exported, portrait, no launcher> action SCRATCH_PAD_SCREEN
│       ├── kotlin/…/ext/scratchpad/
│       │   ├── ScratchPadApplication.kt   OnyxEngine.register(this); RattaEngine.register()
│       │   ├── ScratchPadService.kt       IScratchPad.Stub — begin / receiveInk / takeOutgoing / end (HostCallerCheck first)
│       │   ├── ScratchSession.kt          process-wide: the held store binder + pending inbound / outbound ink (S0/S2)
│       │   ├── ScratchStore.kt            key layout + page blob read/write over IExtensionStore (IO), the 4 MiB full rule
│       │   ├── ScratchPageCodec.kt        pure: page blob ⇄ (pageWidth, pageHeight, strokes) — JVM-tested
│       │   ├── ScratchPages.kt            pure: the page list + current index math (insert / delete / clamp) — JVM-tested
│       │   ├── ScratchPadActivity.kt      the screen (≤ 800 lines; collaborators below)
│       │   ├── ScratchToolbar.kt          top bar wiring over PaperToolbar; Send visibility
│       │   ├── ScratchSelectionToolbar.kt the two-button floating bar (Delete · Send) — anchored via ToolbarAnchor
│       │   ├── ScratchUndo.kt             the pad's Action set for UndoRedoStack<Action> (Drew / Erased / Moved / Page / Pasted)
│       │   └── ScratchDebugMenu.kt        (src/debug) ⋯ → "Store size" (counts + bytes; removed in S3)
│       └── res/layout/activity_scratch_pad.xml · res/values/strings.xml · mipmap (puzzle icon, as every extension)
└── app/…/notesprout/
    ├── build.gradle.kts             + implementation(project(":paper-screen")); the g-paper lines move to :paper-screen
    ├── extension/ExtensionRegistry.kt   + scratchPad(context): ProviderRef?
    ├── extension/ExtensionBinder.kt     + hold(...) : HeldBinding  (bind that outlives one call; close() = unbind)
    ├── extension/ScratchPadClient.kt    (new) pre-open store · hold · begin · launch · receiveInk chunks · takeOutgoing · end
    ├── extension/TransferCaps.kt        (new, pure) outward / inward stroke caps + chunking
    ├── core/IconCatalog.kt              + NOTES → ic_notes
    ├── notebook/ScratchPadFlow.kt       (new) notebook-side: button, core action, placement dialog, send, result → paste
    ├── notebook/SelectionActions.kt     core actions filtered by appliesTo (Delete = ALL, scratch = INK)
    ├── notebook/UndoRedoStack → NotebookUndo.kt   the app's Action sealed interface (+ Pasted) over the generic stack
    ├── notebook/StrokeStore.kt          + insert(pageId, strokes)  (fresh rows; restore stays for un-delete)
    ├── notebook/NotebookActivity.kt     wiring only (≤ ~15 lines; extraction rule below)
    ├── library/ScratchPadLaunch.kt      (new, small) the library's button + launch (no ink)
    └── res/layout/activity_notebook.xml (+ btnScratchPad) · activity_library.xml (+ btnScratchPad) · drawable/ic_notes.xml (moved to :paper-screen)
```

Dependency direction: `:app → :paper-screen, :extension-api` · `:ext-scratchpad → :paper-screen,
:extension-api` · `:paper-screen → g-paper only` · `:extension-api → nothing`. The five existing
extension modules are untouched (still `:extension-api` only).

### `:paper-screen` — what moves, and the rules

- **Pure move, packages unchanged** (`com.symmetricalpalmtree.notesprout.core` /
  `…notesprout.notebook` continue to exist in two modules — legal; no class is duplicated). Two
  renames because the names were notebook-specific: `NotebookToolbar → PaperToolbar`,
  `NotebookChrome → PaperChrome`. `PaperChrome` loses its `SelectionToolbar` parameter and takes
  `extraRects: () -> List<Rect>` + `extraContains: (Int, Int) -> Boolean` (the notebook passes its
  selection toolbar's; the pad passes its own).
- **`UndoRedoStack<A : Any>`** becomes generic (LIFO, `MAX 100`, `record / canUndo / canRedo /
  popUndo / popRedo / clear` unchanged); the app's `sealed interface Action` (Drew / Erased / Moved /
  Page / ObjectCreated / ObjectsDeleted / ObjectEdited **+ Pasted** in S2) moves to a new
  `notebook/NotebookUndo.kt` in `:app` (it references `PageObject` / `NotebookSession.Structural`).
  `UndoRedoStackTest` moves with the stack (typed on a test-local action).
- **`Slog`** moves with the module's own `BuildConfig.DEBUG` (`buildFeatures.buildConfig = true`);
  the app's debug build consumes the library's debug variant, so gating is unchanged. The extension
  modules' recorded `if (BuildConfig.DEBUG) Log.d` rule stays for the five existing ones;
  `:ext-scratchpad` may use `Slog` (it depends on `:paper-screen`).
- **Resources:** colors, dimens (both tiers), styles, themes, the toolbar/dialog drawables and every
  `ic_*` icon move; **layouts stay in `:app`**; strings move only if a moved class references them.
  XML references (`@style/…`, `@drawable/…`, `@dimen/…`) resolve across modules as-is; **Kotlin `R`
  references** are the S0 phase-start question (recommended: `android.nonTransitiveRClass=false` in
  `gradle.properties` so the app's `R` keeps seeing the moved resources and the move stays pure).
- **g-paper** is `api(...)` in `:paper-screen` (both consumers write against `PaperView` /
  `Stroke`); the BOOX maven repo is already project-wide in `settings.gradle.kts`.
- **What does not move:** anything that touches the index, a `.soil`, crypto, the extension
  clients, `IconCatalog` (needs `IconNames` from `:extension-api` — the pad has two fixed icons and
  needs no catalog), `SelectionToolbar` (typed on `ToolbarItem`), `Dialogs` **does** move (it is
  pure AppCompat + a drawable).

### Contract additions (`:extension-api`) — exact

`ExtensionContract` gains:

| Constant | Value | Meaning |
|---|---|---|
| `ACTION_SCRATCH_PAD` | `"com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD"` | the `<service>` intent action (discovery, `<queries>`) |
| `ACTION_SCRATCH_PAD_SCREEN` | `"com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD_SCREEN"` | the `<activity>` intent action; the core resolves it with `setPackage(ref.packageName)` |
| `EXTRA_SCRATCH_SEND_ENABLED` | `"sendEnabled"` | boolean launch extra — true when opened from a notebook (the pad shows its Send buttons) |
| `EXTRA_SCRATCH_OPEN_RECEIVED` | `"openReceived"` | boolean launch extra — true right after `receiveInk` (the pad opens on the received page with the strokes selected) |
| `RESULT_SCRATCH_SEND` | `Activity.RESULT_FIRST_USER` (= 1) | result code: the pad has outbound ink for `takeOutgoing` |
| `PLACEMENT_NEW_PAGE` / `PLACEMENT_CURRENT_PAGE` | `0` / `1` | `receiveInk` placement |
| `MAX_TRANSFER_STROKES` | `5_000` | most strokes in one transfer (send to pad / send to notebook), host-enforced outward, re-checked inward on both sides |
| `MAX_TRANSFER_POINTS` | `200_000` | most points, summed, in one transfer |
| `TRANSFER_CHUNK_STROKES` / `TRANSFER_CHUNK_POINTS` | `300` / `20_000` | most strokes / points per Binder call (≈ 320 KB of floats — under the 1 MB transaction budget with headroom); the host chunks, the extension re-checks |
| `STORE_MAX_VALUE_BYTES` | **`4 * 1024 * 1024`** (was `256 * 1024`) | the extension-store value cap — raised for one-key-per-page; values above `STORE_MAX_INLINE_BYTES` travel only through `putLarge` / `getLarge` |
| `STORE_MAX_INLINE_BYTES` | `512 * 1024` | largest value the `byte[]` `put` / `get` path carries (Binder budget); above it → `putLarge` / `getLarge` |
| `STORE_VALUE_LARGE` | `"value is large — use getLarge"` | the `IllegalStateException` message `get` throws for a stored value above the inline cap |

`IconNames` gains `NOTES = "notes"` (added to `ALL`; the core's two Scratch Pad buttons and the
core `scratch` action use the same drawable — listed so an extension may reuse the glyph).

AIDL — **`IExtensionStore` gains two methods appended at the very end** (never reordered; the four
arc-2 methods keep their transaction codes — the arc-5 recipe):

```aidl
// IExtensionStore.aidl — arc 6 / S0, appended after keys():
    /** Insert or replace a value up to STORE_MAX_VALUE_BYTES carried in an ashmem region the caller
     *  created (RenderedTemplate's handshake: create, write, setProtect(PROT_READ), hand over; the
     *  host copies in and closes ITS handle in onTransact's finally; the caller closes its own). */
    void putLarge(String key, in LargeValue value);
    /** The value for [key] of any size (null if absent) as a read-only region the host created;
     *  the caller maps, copies out, and closes it. */
    LargeValue getLarge(String key);
```

`ExtensionStoreGate` stays pure (`byte[]` in / out, JVM-tested — the caps: `put` ≤
`STORE_MAX_INLINE_BYTES`, `putLarge` ≤ `STORE_MAX_VALUE_BYTES`, `get` throws `STORE_VALUE_LARGE` above
the inline cap, `getLarge` any size); `ExtensionStoreBinder` does the ashmem copy-in / copy-out
around it. `:extension-api` gains the parcelable **`LargeValue(memory: SharedMemory, byteCount: Int)`** (hand-written
like `RenderedTemplate`, `describeContents = CONTENTS_FILE_DESCRIPTOR`, `requireValid`: `byteCount in
1..STORE_MAX_VALUE_BYTES` and `≤ memory.size`) and a tiny helper `SharedBytes` (`write(bytes): LargeValue`
— create + map + write + `setProtect(PROT_READ)`; `read(v): ByteArray` — map + copy exactly `byteCount` +
unmap; both sides use it, so the handshake is written once; the receiver closes the region in `finally`).

AIDL (new files):

```aidl
// PaperStroke.aidl · InkBundle.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable PaperStroke;
parcelable InkBundle;

// IScratchPad.aidl — the SCRATCH_PAD point (arc 6 / S0). Every method: HostCallerCheck first.
package com.symmetricalpalmtree.notesprout.extension;
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore;
import com.symmetricalpalmtree.notesprout.extension.InkBundle;
interface IScratchPad {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). ≤ 2 s. */
    void begin(IExtensionStore store);
    /** Notebook → pad (S2): one chunk of the inbound ink; [placement] + [last] on every chunk. The
     *  extension appends chunks until last == true, then places them (a new page after the current
     *  one, or the current page) and marks them "open selected" for the next screen launch. ≤ 2 s per chunk. */
    void receiveInk(in InkBundle chunk, int placement, boolean last);
    /** Pad → notebook (S2): after RESULT_SCRATCH_SEND the host drains the outbound ink chunk by
     *  chunk; an empty bundle (0 strokes) means done. ≤ 2 s per chunk. */
    InkBundle takeOutgoing(int chunkIndex);
    /** The screen is over (result / cancel / host stop): drop the store, clear pending ink. ≤ 2 s. */
    void end();
}
```

Parcelables (hand-written, `@JvmField CREATOR`, write order fixed forever, tails may be appended):

- **`PaperStroke(x: FloatArray, y: FloatArray, pressure: FloatArray, tilt: FloatArray, width: Float,
  colorArgb: Int, style: String)`** — a whole g-paper stroke minus its id and time (`x/y/pressure/
  tilt` same non-zero length; `style` = the `StrokeStyle` name, unknown → PEN on the reader's side).
  Wire: `int n · float[] x · float[] y · float[] pressure · float[] tilt · float width · int color ·
  String style`. `requireValid()` at unmarshal (lengths, `n ≥ 1`, `width > 0`) — a malformed stroke
  rejects the whole bundle (row 21's rule).
- **`InkBundle(strokes: List<PaperStroke>, pageWidth: Float, pageHeight: Float)`** — the page px
  geometry the strokes were authored in (`0f × 0f` = unknown → the reader uses its own page). Wire:
  `float pageWidth · float pageHeight · typedList strokes`. `requireValid()`: `strokes.size ≤
  TRANSFER_CHUNK_STROKES`, points summed `≤ TRANSFER_CHUNK_POINTS`, sizes `≥ 0`.

**Semantics the host enforces (`TransferCaps`, pure, JVM-tested):** outward — a selection / page
above `MAX_TRANSFER_STROKES` / `MAX_TRANSFER_POINTS` is refused **before** any bind with an honest
dialog (`scratch_too_large`); chunking greedy at `TRANSFER_CHUNK_STROKES` / `TRANSFER_CHUNK_POINTS`
(a single stroke over the point chunk cap is its own chunk — allowed, `InkBundle.requireValid`
counts points per chunk, a lone stroke ≤ `MAX_INK_POINTS`-class sizes always fits). Inward
(`takeOutgoing`) — the drain stops at `MAX_TRANSFER_STROKES` / `MAX_TRANSFER_POINTS` summed or at
`TRANSFER_MAX_CHUNKS` (`ceil(MAX_TRANSFER_STROKES / TRANSFER_CHUNK_STROKES)` = 17) chunks — whichever
first — and the paste says so (`scratch_truncated`); every chunk through `requireValid` +
`TransferCaps.sanitize` (unknown style → PEN, `width` clamped `0.5..50` px, `colorArgb` forced opaque
black in v0). Nothing else is trusted; strokes carry **no ids** across (fresh ids on both sides).

### The point's shape — "an extension-owned screen" (tier 2, first exercise)

The recipe later screen-owning extensions follow (`docs/extensions.md` §"Extension-owned screens",
written in S3):

1. Discovery + trust as every point (`ExtensionRegistry.scratchPad` — **the first** trusted service;
   a second installed pad is ignored, like the namer).
2. The core **pre-opens** any store on IO, then **holds a bind** for the screen's life
   (`ExtensionBinder.hold`), calls the point's `begin(...)`, and only then launches the screen with
   `startActivityForResult`-style launcher (`ActivityResultLauncher<Intent>`), after
   `paper.releaseForHandoff()` on any paper-hosting caller.
3. The screen is an **exported Activity with a custom action and no launcher filter**; it verifies
   its caller **first thing in `onCreate`** — `callingPackage == BuildConfig.HOST_PACKAGE` **and**
   `checkSignatures(callingPackage, packageName) == SIGNATURE_MATCH` — else `finish()` before
   `setContentView` (`HostCallerCheck.enforceActivity(activity, hostPackage)` — a sibling of
   `enforce`, added to `:extension-api` in S0). It reads only the recorded `EXTRA_*` and returns only
   the recorded `RESULT_*`; **data never travels in the Intent** — everything goes through the held
   service (`receiveInk` / `takeOutgoing`).
4. On the result (any code), on the launcher's cancel, and in the caller's `onStop` while the screen
   is up (`isFinishing` or a process death of the caller): `end()` → unbind → revoke store, in
   `finally`. The extension treats a dead binder / a `SecurityException` from the store as
   "unavailable" → an honest dialog and `finish()`.
5. The core decides what the user sees on every failure (rule 3): the extension's screen shows its
   own dialogs **only about its own state** (page full, store unavailable); the core owns the
   dialogs around the transfers.

### Extension side — `:ext-scratchpad` (`NSE · Scratch Pad`)

- **`ScratchPadApplication`**: `OnyxEngine.register(this)` + `RattaEngine.register()` (g-paper's
  rule — Onyx must run in `Application.onCreate`).
- **`ScratchPadService`** (`IScratchPad.Stub`): every method `HostCallerCheck.enforce(this,
  BuildConfig.HOST_PACKAGE)` first; `begin` → `ScratchSession.store = store` (a second `begin` while
  one is held replaces it — the host restarted); `receiveInk` → `requireValid` + re-check the caps →
  append to `ScratchSession.inbound` (chunks; on `last` build the page: `PLACEMENT_NEW_PAGE` →
  insert after the remembered current page, `CURRENT_PAGE` → append to it; write through
  `ScratchStore` on the Binder thread — never Main; remember `openReceived = ids`) — a full page →
  `IllegalStateException(SCRATCH_PAGE_FULL)` (the host maps it to `scratch_page_full`); `takeOutgoing`
  → the chunk `chunkIndex` of `ScratchSession.outbound` (empty bundle past the end); `end` → clear
  everything, drop the store. Debug log: counts + durations only.
- **`ScratchSession`** (object): `store: IExtensionStore?`, `inbound`, `outbound: List<PaperStroke>
  + page size`, `openReceived: List<String>?` — process-wide because the Service and the Activity
  share the process; **nothing is written to disk by the extension itself, ever** (its data lives
  in the host store — the arc-2 rule; the ML Kit model exception does not apply).
- **`ScratchStore`** (IO): key layout — `pages` = UTF-8, one page id per line, in order (a page id =
  a random UUID minted by the extension); `current` = the current page id; `page/<id>` = the page
  blob (`ScratchPageCodec`). `load()`, `savePage(id, blob)` (`put` up to `STORE_MAX_INLINE_BYTES`, `putLarge` above it; `get` /
  `getLarge` symmetrically — via `SharedBytes`; **the full rule:** `blob.size >
  STORE_MAX_VALUE_BYTES` → `PageFullException` — the caller removes the offending stroke from the
  paper and shows `scratch_page_full` once per page visit), `insertPage(afterId)`, `deletePage(id)`
  (deletes `page/<id>`; never below one page — the last page is emptied instead), `setCurrent(id)`.
  Every store call on `Dispatchers.IO`; any exception → `StoreUnavailable` → the Activity shows
  `scratch_store_unavailable` and finishes. A missing `pages` key = first run → one blank page.
- **`ScratchPageCodec`** (pure, JVM-tested): `u8 version(1) · f32 pageWidth · f32 pageHeight · u32
  count · per stroke { u16 idLen + UTF-8 id · f32 width · i32 color · u8 styleOrdinalNameLen + name ·
  u32 blobLen + StrokeCodec format-B blob }` — `StrokeCodec` is shared from `:paper-screen`, so a
  page blob is the `.soil` stroke encoding with a header, and the reader tolerates a truncated tail
  (drops the partial stroke). `encode(pageWidth, pageHeight, strokes): ByteArray`,
  `decode(blob): Page(pageWidth, pageHeight, strokes)`.
- **`ScratchPages`** (pure, JVM-tested): `insertAfter(ids, currentId, newId)`, `delete(ids,
  currentId) → (ids, landingId)` (`PageMath.indexAfterDelete`), `clampCurrent`.
- **`ScratchPadActivity`** (`Theme.Notesprout` from `:paper-screen`, portrait, immersive like the
  notebook — `goImmersive` copied as-is; `TopGuard.applyRootPadding`): caller check first;
  `GPaper.create(this)`; pen / eraser / lasso via `PaperToolbar`; `PageGestures` with a listener that
  flips / inserts / undoes / redoes / asks to delete (`ActionSheetDialog` → confirm dialog); the
  bottom strip `<` `n / N` `>` (arrows are no-ops at a bound — never disabled); **whole-paper
  exclusion until the first page is loaded** (the notebook's "Opening…" rule — a small
  `scratch_opening` overlay, hidden when loaded); `setPageSize(pageWidth, pageHeight)` from the blob
  (`0×0` on a new page = the surface size at first layout, written into the blob on the first save);
  `onStrokeCommitted` → the in-memory page + a **debounced save** (`SAVE_DEBOUNCE_MS 800`) + save on
  page leave / `onPause` / finish; `onStrokesErased` / `onSelectionMoved` → same; **undo/redo** =
  `UndoRedoStack<ScratchUndo.Action>` (Drew / Erased / Moved / PageInserted / PageDeleted(pageId,
  blob, index) / Pasted) replayed via `loadStrokes` / `addStrokes` / `removeStrokes`, cleared on
  finish; **selection toolbar** = `ScratchSelectionToolbar` (Delete · Send-when-enabled), anchored
  by `ToolbarAnchor`, its rects in `PaperChrome`; **Send (top bar)** = every stroke on the current
  page → `ScratchSession.outbound` → `setResult(RESULT_SCRATCH_SEND)` → `finish()`; **Send
  (selection)** = the selected strokes, same; **Back** = `setResult(RESULT_CANCELED)` → `finish()`;
  `EXTRA_SCRATCH_OPEN_RECEIVED` → open on the received page, `paper.setSelection(ids)` after load;
  `EXTRA_SCRATCH_SEND_ENABLED = false` → both Send buttons `GONE`. `resumeDrawing()` in `onResume`,
  `release()` in `onDestroy`. Debug ⋯ (`ScratchDebugMenu`, S1, removed in S3): "Store size" → keys +
  bytes toast.
- Strings (extension `res/values/strings.xml`, Appendix A): `scratch_title`, `cd_scratch_back`,
  `cd_scratch_pen` / `_eraser` / `_lasso`, `cd_scratch_send` "Send to notebook", `cd_scratch_prev` /
  `_next`, `scratch_delete_page` / `scratch_delete_confirm`, `scratch_page_full`,
  `scratch_store_unavailable`, `scratch_opening`, `cd_scratch_delete_selection`.

### Host side (`:app`)

**`extension/`**

- **`ExtensionRegistry.scratchPad(context): ProviderRef?`** — the first trusted `ACTION_SCRATCH_PAD`
  service (same filter as every point). Manifest `<queries>` gains the two actions.
- **`ExtensionBinder.hold(appContext, ref, action, tag, asInterface, bindTimeoutMs): HeldBinding<I>`**
  — the bind half of `call` without the unbind: `HeldBinding.iface`, `suspend fun <T> call(timeoutMs,
  block: (I) -> T): T` (the same timeout / exception mapping as `call`), `close()` (unbind, idempotent;
  `onBindingDied` / `onServiceDisconnected` mark it dead so the next `call` throws
  `ExtensionCallException`). Signature re-checked at bind like `call`.
- **`ScratchPadClient`** (one instance per calling screen): `suspend open(sendEnabled: Boolean,
  openReceived: Boolean): Boolean` — `ExtensionStores.open(ctx, ref.packageName)` on IO (pre-open) →
  mint `ExtensionStoreBinder(db, uid)` → `hold` → `call(2 s) { begin(store) }` → build the screen
  Intent (`ACTION_SCRATCH_PAD_SCREEN`, `setPackage(ref.packageName)`, the two extras) → the caller
  launches it (`launcher.launch(intent)`) — the client returns false with the reason logged when
  the extension is absent / the bind or `begin` fails (the caller shows
  `objects_provider_failed`-style `scratch_failed(label)`); `suspend send(bundleChunks, placement)`
  (S2 — `receiveInk` per chunk under one held bind, 2 s each; an `IllegalStateException` whose message
  is `SCRATCH_PAGE_FULL` → typed `ScratchPageFullException`); `suspend drainOutgoing(): List<
  PaperStroke> + page size + truncated` (S2 — `takeOutgoing(i)` until an empty bundle / the caps);
  `suspend finish()` — `call(2 s) { end() }` in a `try`, then `close()` + `revoke()` in `finally`
  (idempotent; called from the result callback **and** the caller's `onDestroy` if still open).
  Log tag `ScratchPadClient` — counts + durations, never a stroke.
- **`TransferCaps`** (pure, JVM-tested): `chunk(strokes: List<PaperStroke>): List<List<PaperStroke>>`,
  `withinLimits(strokeCount, pointCount): Boolean`, `sanitize(bundle): InkBundle` (as above),
  `toPaperStrokes(strokes: List<Stroke>)` / `toStrokes(bundle, freshIds = true)` — the two mappings
  (id dropped outward, minted inward; `timeMillis 0`).

**`notebook/`**

- **`ScratchPadFlow`** (`activity`, `paper`, `session`, `strokeStore`, `undo`, `client`): owns
  `btnScratchPad` visibility (`GONE` until `ExtensionRegistry.scratchPad != null`, re-evaluated with
  `loadProviders` — same resume rule as the Contents button), the top-bar tap (`open(sendEnabled =
  true)` after `paper.releaseForHandoff()`; the flow's `busy` guard drops a second tap), the core
  toolbar action (S2: `ToolbarAction(CORE_SCRATCH_ID "scratch", "Pad", ic_notes, "Send to Scratch
  Pad", appliesTo = INK, requires = 0)` appended to the core list only while installed → tap →
  the placement `AlertDialog` (title `scratch_send_title` "Send to Scratch Pad", items
  `scratch_new_page` / `scratch_current_page` / cancel — `Dialogs.style`) → `TransferCaps.withinLimits`
  else `scratch_too_large` dialog → `client.open(sendEnabled = true, openReceived = true)`… **order:**
  open the store + hold + `begin` first, **then** `send(chunks, placement)`, **then** launch the screen
  (so the pad opens on the received page); a `ScratchPageFullException` → `scratch_page_full_host`
  dialog, nothing launched, `finish()`; toast `scratch_sent` "Sent to scratch pad" right before the
  launch), and the **result callback** (`RESULT_SCRATCH_SEND` → `drainOutgoing()` → translate to the
  origin → `strokeStore.insert(pageId, strokes)` + `paper.addStrokes` + `undo.record(Pasted(pageId,
  strokes))` + `paper.setSelection(ids)` (host-initiated → sets the selection state itself, the H4
  rule) + `scratch_truncated` dialog if truncated; any code → `client.finish()`).
- **`SelectionActions.merge`**: core actions are now filtered by `appliesTo` like contributed ones
  (`Delete.appliesTo = INK | OBJECT | …` = every bit → unchanged behaviour; `scratch` = INK →
  absent for one-object / mixed). `SelectionActionsTest` gains the cases.
- **`NotebookUndo.Action.Pasted(pageId, strokes)`**: revert = `strokeStore.remove(ids)` +
  `paper.removeStrokes(ids)`; reapply = `strokeStore.restore(pageId, strokes)` + `paper.addStrokes`.
- **`StrokeStore.insert(pageId, strokes)`**: fresh rows via `StrokeRows.toRow` at `maxOrder + i`
  (enqueued on the writer like `commit`).
- **`NotebookActivity`**: `btnScratchPad` (`AppCompatImageButton`, ToolbarButton style, `ic_notes`,
  `cd_scratch_pad` "Scratch Pad") in `topBarRow` (slot = S1 Q2), the launcher registration, the
  flow's construction, `onDestroy` → `flow.close()`. **Line cap:** the file is at 797; the wiring is
  ≤ ~15 lines, so S1 **first** moves `revert` / `reapply` / `doUndo` / `doRedo` (≈ 60 lines) into
  `notebook/NotebookUndo.kt` next to the Action set (a pure move) — written as an S1 deliverable, not
  a reason.

**`library/`**

- **`ScratchPadLaunch`** (small): `btnScratchPad` in `activity_library.xml`'s `bottomBar` right after
  `btnRecents` (S1 Q3 confirms), `GONE` until `ExtensionRegistry.scratchPad != null` (checked in
  `onResume` beside the namer discovery), tap → `client.open(sendEnabled = false, openReceived =
  false)`; result of any code → `client.finish()`. `LibraryActivity` is at 769 lines — the launch is
  its own file, the Activity gains ≤ 10 lines.

### Rules followed and added

Rules 1–5 (point), the store rules (arc 2), 18–23 (object point) apply. Added to
`docs/extensions.md` in S3, under a new §"Adding a screen-owning point (arc 6 pattern)":

25. **A screen is the extension's, its data is the host's, the transfer is the point's.** An
    extension-owned screen holds nothing but what the host lent it for that showing (the store
    binder, the inbound ink) and hands back only through the point's methods — never through the
    Intent, never through a file, never through a shared process.
26. **A held bind is still bind-per-operation** — the operation is the showing. It is opened before
    the screen and closed (unbind + revoke) in one `finally` after it, on every path including the
    caller's death.
27. **Two paper surfaces never share a process, a view, or the EPD pipeline at once** —
    `releaseForHandoff()` before the launch, `resumeDrawing()` on return, and the extension registers
    its own engines in its own `Application`.

---

## Phases

### Phase S0 — `:paper-screen` extraction · contract · extension skeleton (discovered, held, no screen)
**Status:** ✅ Complete (commit 9a96c7a; user-verified SNN / NA5C / MIP11 2026-08-19 — all five checklist items pass)

**Goal:** the shared module exists and the core is a pure-move consumer of it (every JVM test
green, the v0 regression subset by eye unchanged on all three devices); the contract has the point,
the two parcelables and the raised cap; `NSE · Scratch Pad` installs, is discovered, and the host
can hold a bind and round-trip `begin` / `end` (a debug ⋯ item proves it: store handed, page count
read); nothing user-visible in release beyond the two buttons **not yet appearing** (they arrive in
S1).

**Questions to resolve at phase start** (one at a time; recommended default first):
1. Kotlin `R` references to moved resources: `android.nonTransitiveRClass=false` in
   `gradle.properties` (rec. — the move stays pure, one line) / sweep the app's `R.` imports to the
   library's `R`?
2. `Slog` moves with the module's own `BuildConfig` (rec.) / stays in `:app` and the extension uses
   `if (BuildConfig.DEBUG) Log.d` like the others?
3. `STORE_MAX_VALUE_BYTES` = 4 MiB + `STORE_MAX_INLINE_BYTES` = 512 KiB + `putLarge` / `getLarge`
   appended to `IExtensionStore` (rec., Q2 revisited) — confirm; and the arc-2 debug "Extension store
   self-test" gains a 4 MiB `putLarge` / `getLarge` round-trip case (rec.) — confirm.
4. The S0 debug probe: notebook ⋯ → "Probe scratch pad" (hold → `begin` → the extension logs its
   page count → `end`; removed in S3) — rec. yes; confirm.

**Answered 2026-08-19 (all the recommended defaults):** Q1 `android.nonTransitiveRClass=false` ·
Q2 `Slog` moves with the module's own `BuildConfig` · Q3 4 MiB + 512 KiB inline + `putLarge` /
`getLarge` appended + the self-test's 4 MiB case · Q4 the "Probe scratch pad" debug item, yes.

**Deliverables**
1. `:paper-screen` module (`settings.gradle.kts`, `build.gradle.kts` — Appendix B): the moves listed
   under Architecture (Kotlin + resources + the tests that travel: `StrokeCodecTest`,
   `InkColorCodecTest`, `PageMathTest`, `UndoRedoStackTest` (generic), `PageGesturesTest` if one
   exists); `PaperToolbar` / `PaperChrome` renames; `NotebookUndo.kt` in `:app` holding the Action
   set; `:app` `build.gradle.kts` depends on it; every existing test green in its new home;
   `assembleDebug` + `:app:assembleRelease` compile.
2. `:extension-api`: `PaperStroke` + `InkBundle` (`.aidl` + `.kt`, `requireValid`, JVM tests
   `PaperStrokeTest` / `InkBundleTest`: round trip, mismatched lengths rejected, chunk caps rejected,
   unknown style kept as text), `IScratchPad.aidl`, the constants (Appendix A), `IconNames.NOTES`,
   `HostCallerCheck.enforceActivity`, `STORE_MAX_VALUE_BYTES` 4 MiB + `STORE_MAX_INLINE_BYTES` +
   `STORE_VALUE_LARGE`, `IExtensionStore.putLarge` / `getLarge` appended, `SharedBytes` helper; `:app`
   `ExtensionStoreGate` (+ `ExtensionStoreGateTest`: inline cap, large cap, `get` above inline throws,
   `getLarge` any size) + `ExtensionStoreBinder` copy-in / copy-out (close in `finally`); the debug
   store self-test's 4 MiB round-trip case.
3. `:ext-scratchpad` skeleton: manifest (application, service, **the Activity declared but showing
   only a caller-checked "Scratch Pad" title + Back in S0** — the real screen is S1), puzzle icon +
   label, `ScratchPadApplication`, `ScratchPadService` (`begin` / `end` real; `receiveInk` /
   `takeOutgoing` → `UnsupportedOperationException` until S2 — never reached, S1 wires nothing to
   them), `ScratchSession`, `ScratchStore` (`load` / `savePage` / `insertPage` / `deletePage` /
   `setCurrent` + the full rule), `ScratchPageCodec` (+ `ScratchPageCodecTest`), `ScratchPages` (+
   test).
4. `:app`: `ExtensionRegistry.scratchPad` + `<queries>`; `ExtensionBinder.hold` (+ a JVM test of the
   `HeldBinding` state machine if it can be typed without Android — else the device probe covers it);
   `ScratchPadClient.open` / `finish` (no `send` / `drain` yet); `IconCatalog.NOTES`; `TransferCaps`
   (+ `TransferCapsTest`); debug ⋯ "Probe scratch pad".
5. Docs: `docs/extensions.md` contract table / AIDL / parcelables / the store-cap note / the
   `:paper-screen` module paragraph in the module list; `CLAUDE.md` module list (nine modules) +
   build/install lines for `ext-scratchpad`; `README.md` module table.

**Tests**
- JVM green (nine modules); builds; the moved tests run in `:paper-screen`.
- Claude-side per device (app + all six extensions; NA5C enable dance): the v0 + arc-4/5 regression
  subset (open / write / flip / undo / lasso → H → heading / Contents) unchanged after the move;
  ⋯ Probe scratch pad → `logcat -s ScratchPadClient ScratchPadService` shows `hold`, `begin`, the
  extension's `pages=1` (first run creates one), `end`, `unbind`, no `SecurityException`, store file
  `Garden/com.symmetricalpalmtree.notesprout.ext.scratchpad.dev.db` created encrypted (header check
  as the arc-2 self-test does); library ⋯ → "Extension store self-test" → OK **including the new 4 MiB
  `putLarge` / `getLarge` case** on every device (risk register 3).
- **User device checklist:** (1) Settings → Apps shows "NSE · Scratch Pad Dev"; (2) 60-second v0
  regression: create / open / write / flip / undo / lasso-delete / long-press delete page / Back /
  library rename; (3) the arc-4 heading story + the Contents unchanged; (4) Templates / Naming /
  Recognize page unchanged; (5) no visual change anywhere (the move is pure).

**Close-out:** status ✅ + Outcome (**S3 review base = the commit before S0's first commit** — write
its hash here); docs; memory; commit + push.

**Outcome (2026-08-19) — S3 review base = `8b05f7e`** (the arc-6 planning commit; review range
`8b05f7e...HEAD` in S3).
- **`:paper-screen`** extracted as a pure move (14 Kotlin files + 4 value files + `values-sw720dp` +
  38 drawables + the `ok` string; `git mv` throughout). The two renames (`PaperToolbar`, `PaperChrome`
  with `extraRects` / `extraContains`), the generic `UndoRedoStack<A>`, `NotebookUndo.kt` in `:app`
  (the Action set — replay still in the Activity until S1), `Slog` on the library's own `BuildConfig`,
  `Dialogs` / `ActionSheetDialog` on the library's `R`. Moved tests: `StrokeCodecTest`,
  `InkColorCodecTest`, `PageMathTest`, `ToolbarAnchorTest` (its subject moved — not in the S0 list but
  the obvious home), `UndoRedoStackTest` (rewritten on a test-local action set; the object-action
  shapes it pinned moved to a new `NotebookUndoTest` in `:app`). `ic_notes` (Tabler `notes`) added to
  the library. `android.nonTransitiveRClass=false` (Q1). The five earlier extensions untouched.
- **Contract:** everything in Appendix A + `LargeValue` + `SharedBytes` + `HostCallerCheck.enforceActivity`
  (returns `Boolean`, finishes the Activity itself); `IExtensionStore.putLarge` / `getLarge` appended
  (the `.aidl` needed an explicit `import ...LargeValue;` for the parcelable). Gate / binder split: the
  gate stays `byte[]`-pure (`put` ≤ inline, `putLarge` ≤ cap, `get` throws `STORE_VALUE_LARGE` above
  inline, `getLarge` any size; key-count cap on both puts); the binder copies in (`readAndClose`) /
  out (`SharedBytes.write` parked per Binder thread, closed in `onTransact`'s `finally`). JVM tests:
  `PaperStrokeTest`, `InkBundleTest` (+ `LargeValue.requireValid`), `ExtensionStoreBinderTest` (+3),
  `TransferCapsTest`, `ScratchPageCodecTest`, `ScratchPagesTest`, `NotebookUndoTest`.
- **`ScratchPadClient.open` returns the screen `Intent?`** (null = failure, reason logged) rather
  than `Boolean` + a separate accessor — one call, the caller launches what it gets. `finish` is
  idempotent and releases in `finally` on every path incl. cancellation.
- **`ExtensionBinder.hold`** releases an attempted bind itself on any failure and returns a
  `HeldBinding` (`call(timeoutMs, block)` with `call`'s exception mapping; `isDead` after
  `onBindingDied` / `onServiceDisconnected` / `close`; `close` idempotent). No JVM test — it is all
  Android types; the device probe covers the state machine (hold → begin → end → unbind, binds =
  unbinds, `dumpsys activity services` shows 0 lingering connections on all three devices).
- **`:ext-scratchpad`:** the manifest needed `tools:replace="android:label,android:allowBackup"` and
  the `libc++_shared.so` `pickFirsts` — both because the Onyx SDK arrives through `:paper-screen`'s
  `api` g-paper; the screen `<activity>` carries `<category DEFAULT>` (implicit-intent resolution
  requires it). APK ≈ 25 MB (g-paper + the Onyx SDK). `ScratchStore.readPage` tries `get` first and
  falls to `getLarge` on `STORE_VALUE_LARGE`.
- **Risk register 3 answered on all three devices:** the in-process self-test's 4 MiB round trip
  (MIP11 117 ms · NA5C 119 ms · SNN 389 ms) **and** a cross-process one (`putLarge` → `getLarge` →
  `get` refused with `STORE_VALUE_LARGE` → delete; SharedMemory both ways over a real Binder) run
  once per extension process from `begin` in debug builds: MIP11 503 ms · NA5C 905 ms · SNN 917 ms —
  inside the 2 s `begin` budget even on the BOOX. **Removed again after verification** (it would
  otherwise sit inside every first pad open in S1 and muddy its timings; the host's "Probe scratch
  pad" stays until S3 as planned). `begin` itself (page list read, first run creates one page): MIP11
  12–30 ms · NA5C 24 ms · SNN 47 ms; warm `open` (store cached, process alive): MIP11 ≈ 0.8 s cold /
  NA5C 13–31 ms / SNN 45–60 ms warm; cold `open` end to end (cold store open + process start + the
  probe): NA5C 2.0 s · SNN 4.0 s (the Nomad's cold KDF is ≈ 2 s — the self-test's `open 1983ms`).
- **Claude-side per device (Sonnet agents, one per device):** packages present + enabled; the v0
  regression subset (library chrome, open, flip by finger swipe, Back, New-folder dialog, Sort sheet)
  unchanged; the probe sequence exact; the store `.db` created **encrypted** (header not
  `SQLite format 3`); self-test OK; `am start` of the screen **refused** (`refused caller (none)`,
  nothing shown); no `FATAL`, no `SecurityException`, no lingering bind. The NA5C store file is 4.3 MB
  after the probe (a deleted 4 MiB row leaves free pages — no vacuum in Paper; recorded under
  Deferred already).
- Trap for S1: a wrong blind tap on the **library** debug sheet lands on "Forget cached key" — always
  dump before tapping (the notebook's and the library's sheets sit at different y).

---

### Phase S1 — The scratch-pad screen + the two entry buttons (no transfers)
**Status:** ✅ Complete (commits a7a9923 + 98f58f6; user-verified SNN / NA5C / MIP11 2026-08-19 — all ten checklist items pass)

**Goal:** the user story minus transfers works on all three devices: the notebook's top-bar and the
library's bottom-bar **notes** buttons exist only with the extension installed; tap → the pad opens
full screen with pen / eraser / lasso, writes persist across close / reopen / process kill, pages
flip / insert / delete with the notebook's gestures and the strip's arrows, undo / redo work, the
last page is remembered; Back returns to the caller with its ink intact and its pen working (the
handoff); Send buttons show (from the notebook) but are **not yet wired** (S2) — S1 Q4 decides
whether they are hidden until S2 or shown as no-ops.

**Questions to resolve at phase start:**
1. The pad's whole-paper exclusion until loaded + a small "Opening…"-style overlay (rec., the
   notebook's rule) — confirm.
2. Notebook button slot: right after `btnContents` (Back · [Contents] · **Scratch Pad** · Pen · Eraser
   · Lasso), rec. — or at the far end before the debug ⋯?
3. Library button slot: right after `btnRecents` (rec.) — confirm; and its hint "Scratch Pad".
4. Send buttons in S1: `GONE` until S2 wires them (rec. — nothing dead on screen) / visible no-ops?
5. Save cadence: debounced 800 ms + on page leave / pause / finish (rec.) — confirm; and the "page
   full" rule = refuse the crossing stroke + dialog once per visit (rec.) — confirm.

**Answered 2026-08-19:** Q1 exclusion + "Opening…" overlay (rec.) · **Q2 far end — Back · [Contents]
· Pen · Eraser · Lasso · Scratch Pad · [⋯]** (not after Contents) · Q3 after Recents, hint "Scratch
Pad" (rec.) · **Q4 visible no-ops** — both Send buttons show when opened from a notebook, a tap does
nothing until S2 (not GONE) · Q5 800 ms debounce + leave / pause / finish, page-full = refuse + remove
+ dialog once per visit (rec.).

**Deliverables**
1. `:ext-scratchpad`: `ScratchPadActivity` + `activity_scratch_pad.xml` + `ScratchToolbar` +
   `ScratchSelectionToolbar` (Delete only in S1; Send per Q4) + `ScratchUndo` + `ScratchDebugMenu`
   (⋯ "Store size"); strings; the whole screen as specified under Architecture.
2. `:app`: `NotebookUndo.kt` gains the moved `revert` / `reapply` / `doUndo` / `doRedo`; `ScratchPadFlow`
   (button, open, result → `finish()`), `btnScratchPad` in both layouts, `ScratchPadLaunch` in the
   library, `NotebookActivity` wiring (≤ ~15 lines net), `LibraryActivity` wiring (≤ 10).
3. Docs: `docs/scratchpad.md` (new: screen, tools, pages, store layout, failure table),
   `docs/notebook.md` §"Scratch Pad (arc 6)" (button, handoff), `docs/library.md` paragraph.

**Tests**
- JVM green; builds; g-paper unchanged (or the bump recorded).
- Claude-side per device: button present in the top-bar dump with the extension installed and gone
  after `pm disable-user` + resume; tap → uiautomator dump shows the pad's chrome; finger swipe
  flips (`n / N` changes); Back returns; `logcat` — `hold` … `begin` … `end` … `unbind`, binds =
  unbinds, no `SecurityException`, no `FATAL`; the extension's `savePage` lines show sizes; MIP11
  stylus ink by adb persists across reopen (uiautomator can't see ink — the store size line proves
  it); library button same; a launch of the pad's Activity by `am start` from adb **is refused**
  (caller check → finishes, log line).
- **User device checklist** (app + all six extensions):
  1. **Handoff first (NA5C, SNN):** open a notebook, write, tap the notes button → the pad opens
     full screen; write on the pad — ink lands (raw layer / ink daemon in the *extension's* process);
     Back → the notebook's pen works at once (write again), no ghosting, no lost strokes.
  2. Pen / eraser / lasso on the pad behave as in the notebook (eraser radius, lasso select-drag,
     finger tap outside dismisses).
  3. Write, Back, reopen → the ink is there; kill the app (Forget cached key → unlock) → reopen →
     still there; the pad opens on the page you left.
  4. Swipe left past the last page inserts; swipe right / left flips; the strip's arrows flip; at a
     bound an arrow does nothing (never greyed); two-finger swipe inserts before / after; `n / N`
     correct throughout.
  5. Finger long-press → sheet → delete page → confirm; the last page cannot be deleted (it is
     emptied); undo (2-finger double-tap) restores the deleted page with its ink; redo removes it.
  6. Undo / redo of a stroke, an erase, a lasso move.
  7. Lasso → Delete on the pad's floating bar; undo brings it back.
  8. From the **library**: the notes button beside Recents opens the same pad (same pages); no Send
     buttons; Back returns to the library.
  9. `pm disable-user` NSE · Scratch Pad → both buttons gone (notebook after resume, library after
     resume); re-enable → back.
  10. Regression: notebook flip / insert / undo / delete / Contents / heading story / Templates /
      Naming / Recognize page unchanged.

**Close-out:** status ✅ + Outcome (per-device open timings tap → pad drawn; page save sizes; the
handoff behaviour observed on NA5C / SNN — anything the engines needed); docs; memory; commit + push.

**Outcome (2026-08-19 — a7a9923 + 98f58f6; Claude-verified, then user checklist items 1–10 all pass on SNN / NA5C / MIP11, incl. item 1 the EPD handoff by eye):**
- **`:ext-scratchpad`:** `ScratchPadActivity` (the notebook's shape from `:paper-screen` — `PageGestures`,
  `PaperChrome`, `UndoRedoStack<ScratchUndo.Action>`, `ToolbarAnchor`, `TopGuard`, `Dialogs`,
  `ActionSheetDialog`; caller check first; whole-paper exclusion under "Opening…"; `resumeDrawing` /
  `release`), `ScratchToolbar`, `ScratchSelectionToolbar` (Delete · [Send] — a two-button layout bar, not
  the core's description-drawn one), `ScratchUndo` (Drew / Erased / Moved / **`Page(before,
  beforeCurrent, after, afterCurrent, changedId, blob)`** — one shape for insert and delete, the lone
  page's delete = `before == after` + the ink toggled / Pasted for S2), **`ScratchDocument`** (new
  collaborator, not in the S0 list: the pages in memory + every store round trip on IO — `load` / `goTo`
  / `insert` / `deleteCurrent` / `flush` / `add` / `remove` / `translate` / `revert` / `reapply`;
  JVM-tested over a fake `IExtensionStore` — `ScratchDocumentTest`, 8 cases incl. the full rule and
  structural undo / redo restoring a deleted page's ink), `ScratchDebugMenu` (debug ⋯ "Store size" +
  release no-op twin), `ScratchStore.setPages` / `removePageBlob` (the undo path), `ScratchPageCodec.
  HEADER_BYTES` / `strokeBytes`. Tabler **`send`** (`ic_send`) added to `:paper-screen` for the two Send
  buttons (the plan named no glyph). `testOptions.unitTests.isReturnDefaultValues = true` in the
  extension's `build.gradle.kts` (so `Slog` → `Log.d` doesn't throw on the JVM).
- **The full rule is a running total:** `pageBytes = HEADER_BYTES + Σ strokeBytes`, exact because each
  stroke is encoded on its own — but the geometry is **zlib-compressed per stroke**, so a move
  re-measures the moved strokes (`translate` adjusts the total; the first cut assumed "floats re-encode
  to the same size" and the test caught it). `add` refuses a crossing stroke at commit time (no page
  encode); the screen removes it from the paper and says `scratch_page_full` once per page visit.
- **Saves:** 800 ms debounce + flush on page leave / `onPause` / **Back awaits the flush before
  `finish()`** (the host's `end()` revokes the store right after the result — a save left in flight would
  hit a revoked binder); a page turn re-flushes until clean (`flushUntilClean` — a stroke committed during
  a flush's IO hop lands on the page being left and is not dropped); `flush` restores `dirty` on failure.
- **`:app`:** `NotebookUndo.undo` / `redo` (+ the private `revert` / `reapply`) — the replay moved out of
  `NotebookActivity` (800 → 755 lines; the wiring added back is 6 lines); `ScratchPadFlow` (button +
  `refresh()` on open and **every resume** — its own discovery, not tied to `ObjectProviders.signature`;
  `open` = busy guard → `ScratchPadClient.open` → **`releaseForHandoff()` immediately before the
  launch**, so the notebook's pen stays live through an open that can take seconds cold → launcher; result
  / `close()` → `finish` on a process-wide scope); `ScratchPadLaunch` (library, no send target, its own
  `onDestroy` → `close()` — `LibraryActivity` gained an `onDestroy` behind `IndexGuard.bounced`);
  `btnScratchPad` in both layouts (notebook: after Lasso, before the debug ⋯ — S1 Q2; library: after
  Recents — S1 Q3); strings `cd_scratch_pad`, `scratch_failed`.
- **JVM green (nine modules), debug + release compile.** Per-device (app + ext reinstalled; Sonnet agents
  on NA5C / SNN, MIP11 by hand incl. stylus ink + a `motionevent` lasso → the Delete · Send bar anchored
  under the selection → Delete → saved 0 strokes): both buttons present, gone after `pm disable-user` +
  resume, back after enable; tap → the pad's chrome; finger swipe inserts / flips, arrows flip and no-op
  at a bound; long-press → sheet → confirm → one page fewer; Back → `result 0` · `end` · `finish: end ok`
  · `unbind (held)`, `dumpsys activity services` shows the service gone; `am start` of the screen
  **refused** (`refused caller (none)`); no FATAL, no `SecurityException`. **Timings** (tap → `begin ok` /
  pad `opened`): MIP11 342 ms cold / 21 ms warm · 131 / 36–55 ms (generic) · NA5C 445–470 ms · 109–122 ms
  (onyx) · SNN 1.1–1.2 s cold · 313–317 ms (ratta). Page saves: 3 strokes = 644 B in 32 ms (MIP11).
  Engines: the pad's process reports `engine=onyx` on NA5C and `engine=ratta` on SNN — the pipelines arm
  in the extension's process without a g-paper change (0.1.1 unchanged); **the handoff by eye (S1 user
  item 1 — ink lands on the pad, the notebook's pen re-arms on return, no ghosting) is the user's call.**
- Agent note (NA5C): a second tap at the notebook Back's coordinates after returning to the library
  "exited the app" — not reproduced; at those coordinates the library shows **Go up one folder**, and a
  re-run went notebook → library (folder) → root with the app alive. Not a Scratch Pad path.
- **Post-✅ follow-up (user's call, 2026-08-19):** (1) the Scratch Pad glyph is Tabler **`sketching`**
  (`ic_sketching`, `IconNames.SKETCHING` — it resembles the original Notesprout's scratch-pad icon;
  `notes` / `ic_notes` / `IconNames.NOTES` removed — nothing else used them; the S2 core action uses
  `SKETCHING`); (2) the pad's chrome re-ordered: **top bar = Back · "Scratch Pad" · [Send] · [⋯]; bottom
  bar = Pen · Eraser · Lasso … `<` n / N `>`** (the exclusion rects are the same two bars).
- **Post-commit (98f58f6):** BOOX re-disabled the sideloaded extension after a reinstall → `bindService`
  false → the `scratch_failed` dialog with the button still up; a failed open now re-runs discovery so the
  button hides at once. First check on any "didn't respond": `pm list packages -d`.

---

### Phase S2 — The two transfers
**Status:** ✅ Complete (commits 16866d9 + 2657481 + 374f17f; user-verified SNN / NA5C / MIP11 2026-08-19 — all nine checklist items pass, incl. the return handoff by eye)

**Goal:** *Send to Scratch Pad* (core toolbar action, placement dialog, ink handed through the held
bind, the pad opens on the page with the strokes selected) and *Send to Notebook* (top-bar whole
page, selection-bar selection; the notebook pastes at the origin, one undoable step, selected) work
on all three devices; both are copies; caps and failures are honest dialogs.

**Questions to resolve at phase start:**
1. The paste lands with its bounds' top-left at the page origin (0, 0) (rec., the original) — or at
   the top-left with an inset (e.g. 16 dp)? confirm.
2. The core `scratch` action's label "Pad" (≤ 6 chars) + icon `notes`, after Delete (rec.) —
   confirm.
3. `MAX_TRANSFER_STROKES 5 000` / `MAX_TRANSFER_POINTS 200 000`, chunk 300 / 20 000 (rec.) —
   confirm; over the cap = refuse with `scratch_too_large` before any bind (rec.) — confirm.
4. After a Send to Scratch Pad the pad **opens** (rec., the original) — or only the toast, the pad
   opened by the user later?
5. `Action.Pasted` is undoable in the notebook; on the pad a *received* placement is **also**
   recorded as `Pasted` in the pad's history (rec. — undo on the pad removes what just arrived) —
   confirm.

**Answered 2026-08-19:** **Q1 keep the pad's coordinates** — the paste is 1:1 (the pad page and the
notebook page are both the device's screen; no translation to (0, 0), no inset — overrides the Q9
"origin" rule; a cross-device page is clipped by the page like any other ink) · Q2 "Pad" · `sketching`
(the `notes` glyph was replaced in the S1 follow-up) · after Delete (rec.) · **Q3 higher caps —
`MAX_TRANSFER_STROKES 10 000` / `MAX_TRANSFER_POINTS 400 000`**, chunks 300 / 20 000 unchanged
(`TRANSFER_MAX_CHUNKS` = 34), over the cap = refuse before any bind (rec.) · Q4 the pad opens on the
page with the strokes selected (rec.) · Q5 received ink is `Pasted` on the pad's stack (rec.).

**Deliverables**
1. `:ext-scratchpad`: `ScratchPadService.receiveInk` / `takeOutgoing` real; `ScratchSession` inbound /
   outbound; `ScratchPadActivity` Send (top bar + selection bar), `EXTRA_SCRATCH_OPEN_RECEIVED`
   handling (open on the page + `setSelection`), `Pasted` on the pad's stack.
2. `:app`: `SelectionActions.merge` core-action filter + `CORE_SCRATCH_ID`; `ScratchPadFlow` action /
   placement dialog / send / result paste; `ScratchPadClient.send` / `drainOutgoing` +
   `ScratchPageFullException`; `StrokeStore.insert`; `NotebookUndo.Action.Pasted` + revert / reapply;
   `TransferCaps` fully exercised (tests for chunking, limits, sanitize, id minting).
3. Docs: `docs/scratchpad.md` transfers section + failure table; `docs/notebook.md` §Scratch Pad
   (action, placement, paste-selected, `Pasted`); `docs/extensions.md` host-behaviour paragraph.

**Tests**
- JVM green; builds.
- Claude-side per device (MIP11 can lasso by adb; NA5C / SNN by the user): lasso strokes → the bar
  shows Delete · **Pad** (a heading selected → no Pad; mixed → Delete only); Pad → dialog → New page →
  `logcat`: `receiveInk chunks=1 strokes=n`, the pad launches, `setSelection` line; Back; on the pad
  lasso → Send → `RESULT_SCRATCH_SEND` → `takeOutgoing 0..k` → `insert n` → selection shown; undo
  removes the paste, redo restores; a 6 000-stroke page (scripted) → `scratch_too_large` dialog, no
  bind; binds = unbinds; no stroke text / geometry in any log line (counts + durations only).
- **User device checklist:**
  1. Lasso a few strokes in a notebook → the floating bar shows Delete and **Pad**; tap Pad → "Send
     to Scratch Pad — New page / Current page / Cancel"; New page → toast "Sent to scratch pad" → the
     pad opens on a **new** page after the current one with the strokes **selected**; the notebook
     still has its ink (Back to check).
  2. Same with **Current page** → lands on the pad's current page, selected.
  3. Lasso a heading (or a heading + ink) → **no Pad button** (Delete only for mixed; H actions for
     the heading).
  4. On the pad (opened from a notebook): write, tap the top-bar **Send** → the pad closes, the
     notebook shows the whole page's ink pasted at the top-left, **selected**; drag it; undo (2-finger
     double-tap) removes the paste; redo brings it back; the pad still has its ink (reopen to check).
  5. On the pad: lasso part of the ink → floating **Send** → only that part arrives, selected.
  6. Cancel in the placement dialog → nothing happens; Back on the pad → nothing pasted.
  7. From the **library** the pad has no Send buttons.
  8. Undo on the pad right after a Send-to-Scratch-Pad removes the received strokes; redo restores.
  9. Regression: S1 items 1–10; the arc-4/5 stories; Templates / Naming / Recognize page.

**Close-out:** status ✅ + Outcome (per-device transfer timings for ~200 strokes each way); docs;
memory; commit + push.

**Outcome (2026-08-19 — 16866d9 + 2657481 + 374f17f; Claude-verified MIP11 by hand, then the user's checklist items 1–9 all pass on SNN / NA5C / MIP11 incl. the two-finger undo items and the return handoff by eye):**
- **Contract:** `MAX_TRANSFER_STROKES` / `MAX_TRANSFER_POINTS` raised to **10 000 / 400 000** (Q3),
  `TRANSFER_MAX_CHUNKS` 34; new **`InkChunks`** in `:extension-api` — the contract's per-call chunking
  rule written once for both sides (`TransferCaps.chunk` delegates; the extension's Send uses it
  directly). Not in the S0 list: the alternative was a second copy of the chunker in the extension.
- **`:app`:** `TransferCaps.Drain` (the inward accumulator — empty bundle / summed caps / chunk budget
  + **one probe past the budget** so "truncated" is said only when something was really left;
  sanitizes on the way in); `ScratchPadClient.send` (one `receiveInk` per chunk ≤ 2 s; `SCRATCH_PAGE_FULL`
  typed as `ScratchPageFullException`) + `drainOutgoing` → `Drained(strokes, pageWidth, pageHeight,
  truncated)`; `SelectionActions.merge` filters **core** actions by `appliesTo` (Delete = ALL everywhere;
  `scratch` = INK → ink only; `CORE_SCRATCH_ID`); `NotebookUndo.Action.Pasted` (undo = `remove`, redo =
  `restore` in place); `StrokeStore.insert` (fresh rows after `maxOrder`); `ScratchPadFlow` gained
  `toolbarAction()`, `sendSelection` → the placement **`ActionSheetDialog`** (title "Send to Scratch
  Pad", `ic_plus` New page · `ic_sketching` Current page; tap outside cancels — the app's sheet idiom
  rather than an `AlertDialog` item list), `startSend` (caps → `scratch_too_large` before any bind),
  one `launchPad(r, send?)` for both the plain open and the send-then-open (open → `send` → clear the
  notebook's selection → toast → `releaseForHandoff` → launch), and the `RESULT_SCRATCH_SEND` branch of
  `onResult` (drain on the still-held bind → `finish` in `finally` → `toStrokes` → the host's `onPaste`
  → `scratch_truncated`); `NotebookActivity` (755 → 780 lines): `coreActions` is a getter (Delete +
  `scratchPadFlow.toolbarAction()`), `onAction(providerKey == null)` routes `CORE_SCRATCH_ID` to
  `sendSelection(strokes)` for ink-only selections, `pasteStrokes` (insert + `liveStrokes` + `addStrokes`
  + `Pasted` + host-initiated `setSelection` over the union bounds + the toolbar), the flow's two new
  lambdas (`pageSize`, `onPaste`). Strings per Appendix A (+ the sheet reuses `scratch_send_title`).
- **`:ext-scratchpad`:** `ScratchInk` (the extension's own wire ⇄ paper mapping — fresh ids, unknown
  style → PEN, width 0.5..50; `:paper-screen` can't host a shared one); `ScratchStore.receive(strokes,
  pageWidth, pageHeight, newPage)` → `Received(pageId, strokeIds)` (new page inserted after the current
  one with the bundle's size · current page appended keeping its own size; the target made `current`;
  the full rule refuses the whole placement — nothing inserted); `ScratchSession` (inbound + running
  points + page size, `outbound` pre-chunked, one-shot `received`); `ScratchPadService.receiveInk`
  (caps re-checked on the running totals → `IllegalArgumentException`; placed on the Binder thread under
  the session lock; `PageFullException` → `IllegalStateException(SCRATCH_PAGE_FULL)`, `StoreUnavailable`
  → `IllegalStateException("store unavailable")`) + `takeOutgoing` (chunk *i*, empty past the end);
  `ScratchPadActivity`: `EXTRA_SCRATCH_OPEN_RECEIVED` → `selectReceived` after `opened` (the record
  consumed once; `Pasted` recorded — Q5; host-initiated `setSelection` + the Delete · Send bar), `send`
  (page / selection; **an empty pick → the new `scratch_nothing_to_send` dialog** — the toast-vs-dialog
  rule; flush under `pageOps` → `InkChunks.chunk(ScratchInk.toPaperStrokes)` on Default → park →
  `RESULT_SCRATCH_SEND` → finish).
- **Paste = keep the pad's coordinates** (Q1 — the plan's "origin" rule overridden; no translation).
- **JVM green (nine modules), debug + release compile.** New tests: `SelectionActionsTest.coreActionsFilteredByAppliesTo`,
  `NotebookUndoTest.pastedIsOneStepForTheWholePaste`, `TransferCapsTest.drain_…`, `ScratchInkTest`,
  `ScratchStoreReceiveTest` (new page / current page / the full rule leaves nothing behind);
  `PaperStrokeTest.contractConstants` re-pinned to the new caps.
- **Claude-verified MIP11 by hand** (3 stylus strokes by `input stylus swipe`; a 28-point
  `input stylus motionevent` lasso): the bar reads **Delete · Pad · H**; Pad → the sheet "Send to
  Scratch Pad / New page / Current page"; New page → `begin ok 314 ms` (cold) → `receiveInk: placed 3
  strokes (new page) in 33 ms` → `send … in 40 ms` → the pad `opened: page 2/2, 3 strokes … 93 ms` →
  `received 3 strokes selected` (the Delete · Send bar anchored under them); the selection bar's Send →
  `result 1` → `takeOutgoing 0: 3 strokes`, `takeOutgoing 1: 0` → `drainOutgoing … 27 ms` → `paste 3
  strokes` → `pasted 3 strokes on <page>` → `end` → `unbind (held)` → `insert 3` — the notebook shows the
  selection bar (Delete · Pad · H) over the paste; the button open + the top-bar Send pasted the whole
  page the same way (`begin ok 24 ms` warm); Current page → `placed 9 strokes (current page) in 11 ms`,
  `opened: page 2/2, 12 strokes`, `received 9 strokes selected`; Back → `result 0 (cancelled)` → `end`,
  nothing pasted; an inserted blank page + Send → "There's no ink here to send."; `dumpsys activity
  services` shows no scratchpad service after; no FATAL, no `SecurityException`; the library's pad has 0
  Send buttons; log lines carry counts + durations only. Multi-finger undo / redo (items 4 + 8), the
  heading / mixed case on device (item 3) and NA5C / SNN are the user's (adb can't lasso there).
- **User feedback round 1 (2026-08-19, fixed + reinstalled SNN / NA5C / MIP11):** (1) a pasted /
  received selection under the **pen** could not be dragged or dismissed → both screens now switch to
  the **lasso before `setSelection`** (a host tool change dismisses, and host-initiated changes don't
  echo `onToolChanged`, so the toolbar is synced by hand) and restore the prior tool **pen-idle** when
  that selection is dismissed, only if still on the lasso (stays lasso if it was lasso; a tool the user
  picked meanwhile wins) — `toolBeforePaste` / `restoreToolAfterPaste` in the notebook,
  `toolBeforeReceived` / `restoreToolAfterReceived` on the pad; MIP11-verified by uiautomator
  (`btnLasso selected` while the paste shows, `btnPen` after tap-away; lasso → lasso stays). (2) undo
  on the pad after a **New page** placement removed only the strokes → it is now recorded as a
  structural `Action.Page` (`Received` carries `newPage` + `pagesBefore` + `currentBefore`), so undo
  removes the page and redo restores it with its ink; Current page stays `Pasted`. `NotebookActivity`
  796 lines (the cap is 800 — S3 notes it).
- **User feedback round 2 (2026-08-19, NA5C — the EPD handoff on the way BACK; fixed + reinstalled all
  three):** after returning from the pad the notebook's ink / lasso trails were invisible while the pen
  was down until a tool flip. Logs: the notebook reclaimed the pipeline in `onResume` (`openRawDrawing:
  pipeline claimed` 10.574) and the pad's own close (`onWindowVisibilityChanged`, the *other* process)
  landed after it (10.759) — tearing the notebook's fresh session down. Fix: the pad calls
  **`paper.releaseForHandoff()` before every `finish()`** (`finishWithHandoff` — Back, Send, the store
  dialog), the symmetric of rule 27's release-before-launch; the order is now pad release 45.311 →
  notebook reclaim 45.359. Recorded in g-paper's lifecycle tables (`api.md` / `host-responsibilities.md`
  / `integration-guide.md`, docs-only commit 289b407, no version bump) and in `docs/scratchpad.md`.
  Live overlay is invisible to screencap — the user confirmed by eye on NA5C ("That fix it!"), then all S2 items on all three devices.

---

### Phase S3 — Review, boundary audit, docs freeze
**Status:** ⬜ Not started

**Goal:** the shared module, the new point, its held bind, the store cap, the transfers and the
screen are trustworthy and recorded as the pattern a second screen-owning extension follows.

**Questions to resolve at phase start:**
1. Anything observed in S1 / S2 the user wants changed before freezing (wording, sizes, gates,
   the button slots)? (rec.: no — freeze as built)
2. Confirm scope freeze: fixes only; remove the debug "Probe scratch pad" (host) and "Store size"
   (extension) items (rec.: remove both).

**Deliverables**
1. `/code-review high <S0 base>...HEAD` (**the range** — passing a bare hash reviews that commit;
   the base is in S0's Outcome); fix confirmed findings.
2. **Boundary audit** rows added to `docs/extensions.md` and walked:
   - **28 — Outward on `begin` is the uid-bound store binder only** (no key, path, name, id; the
     same binder as rows 10–13, now held for a showing and revoked in the same `finally` as the
     unbind — every path: result, cancel, caller `onDestroy`).
   - **29 — Outward ink (`receiveInk`) is bare stroke geometry + style + the page px size** — no
     stroke ids, no page ids / numbers, no notebook name / id, no positions beyond the strokes'
     own coordinates; capped and chunked before the bind.
   - **30 — Inward ink (`takeOutgoing`) is validated** (`requireValid` at unmarshal, `TransferCaps.
     sanitize`, the drain caps, fresh ids minted by the core; the paste is one undoable step and
     nothing else on the page changes).
   - **31 — The screen is the extension's, launched only by the core (caller-checked both ways —
     `enforceActivity` + `checkSignatures`), carrying only the recorded extras / result codes;
     data never rides the Intent.**
   - **32 — The raised store cap changes no rule** — value ≤ 4 MiB (inline ≤ 512 KiB, else an ashmem
     region the receiver copies out of and closes in `finally`), keys ≤ 50 000, uid-bound, revoked;
     a page over the cap is refused by the extension, never split, never written elsewhere; the two
     appended store methods follow the arc-5 compatible-change recipe.
   - Re-walk rows 1, 6, 7 for `hold` / `HeldBinding.call` and the four `IScratchPad` calls.
3. `docs/extensions.md` final: §"Extension-owned screens (tier 2)" recipe (the five steps under
   "The point's shape"), rules 25–27 under §"Adding a screen-owning point (arc 6 pattern)", the
   `:paper-screen` module paragraph + its dependency rule, §"The Scratch Pad extension", "Writing an
   extension" item for a screen-owning extension; `docs/scratchpad.md` frozen; `README.md`;
   `CLAUDE.md` standing rules (a `:paper-screen` bullet — what lives there, "a fix to shared screen
   logic goes there, never in a consumer"; a Scratch Pad bullet — the held bind, the caller check,
   the handoff, the transfers, the two buttons' visibility rule).
4. Remove the debug scaffolding (per Q2). This file frozen; memory updated (arc complete).

**Tests:** full S1 + S2 checklists on all three devices + the C1 checklist items 1–5, 7–13 + the H4
checklist items 1–5 + v0 regression subset (create / open / write / flip, library create / rename /
move / delete, cold-launch reopen).

**Close-out:** status ✅ + Outcome; commit + push `paper`.

---

## Appendix A — Constants + strings (this arc)

| Name | Value |
|---|---|
| `ACTION_SCRATCH_PAD` / `ACTION_SCRATCH_PAD_SCREEN` | `…extension.SCRATCH_PAD` / `…extension.SCRATCH_PAD_SCREEN` |
| `EXTRA_SCRATCH_SEND_ENABLED` / `EXTRA_SCRATCH_OPEN_RECEIVED` | `"sendEnabled"` / `"openReceived"` |
| `RESULT_SCRATCH_SEND` | `Activity.RESULT_FIRST_USER` (1) |
| `PLACEMENT_NEW_PAGE` / `PLACEMENT_CURRENT_PAGE` | 0 / 1 |
| `MAX_TRANSFER_STROKES` / `MAX_TRANSFER_POINTS` | 5 000 / 200 000 |
| `TRANSFER_CHUNK_STROKES` / `TRANSFER_CHUNK_POINTS` / `TRANSFER_MAX_CHUNKS` | 300 / 20 000 / 17 |
| `STORE_MAX_VALUE_BYTES` / `STORE_MAX_INLINE_BYTES` | **4 MiB** (was 256 KiB) / 512 KiB — `putLarge` / `getLarge` above the inline cap; `STORE_VALUE_LARGE` message |
| `SCRATCH_PAGE_FULL` (exception message) | `"scratch page full"` |
| `IconNames.NOTES` | `"notes"` → `ic_notes` (Tabler `notes`, 24 dp, stroke 2, round caps) |
| `SelectionActions.CORE_SCRATCH_ID` | `"scratch"` — label "Pad", hint "Send to Scratch Pad", `appliesTo = INK` |
| Timeouts | bind 3 s (`hold`) · `begin` / `end` / each `receiveInk` / each `takeOutgoing` 2 s |
| Pad | `PEN_WIDTH_PX 3f` · `ERASER_RADIUS_PX 15f` · `SAVE_DEBOUNCE_MS 800` · undo `MAX 100` · gestures = the notebook's constants (shared `PageGestures`) |
| Strings (`:app`) | `cd_scratch_pad` "Scratch Pad" · `scratch_send_title` "Send to Scratch Pad" · `scratch_new_page` "New page" · `scratch_current_page` "Current page" · `scratch_sent` "Sent to scratch pad" · `scratch_too_large` "That's too much ink to send at once (%1$d strokes)." · `scratch_page_full_host` "The scratch pad's page is full — send to a new page." · `scratch_truncated` "Only the first %1$d strokes came back." · `scratch_failed` "The %1$s extension didn't respond — try again." (title `cd_scratch_pad`) · action label `scratch_action_label` "Pad" · hint `scratch_action_hint` "Send to Scratch Pad" |
| Strings (`:ext-scratchpad`) | `scratch_title` "Scratch Pad" · `cd_scratch_back` "Back" · `cd_scratch_pen` "Pen" · `cd_scratch_eraser` "Eraser" · `cd_scratch_lasso` "Lasso" · `cd_scratch_send` "Send to notebook" · `cd_scratch_prev` "Previous page" · `cd_scratch_next` "Next page" · `cd_scratch_delete_selection` "Delete" · `scratch_delete_page` "Delete this page" · `scratch_delete_confirm` "Delete this page and its ink?" · `scratch_page_full` "This page is full — start a new one." · `scratch_store_unavailable` "The scratch pad can't reach its storage — close and try again." · `scratch_opening` "Opening…" |
| `API_VERSION` / `SOIL_VERSION` / g-paper | 1 / 1 / 0.1.1 — unchanged (g-paper bump only if S1 finds a gap; recorded there) |

## Appendix B — Allowed dependencies

- `:paper-screen` — `api("com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.1")`,
  `androidx.appcompat:appcompat:1.7.0`, `androidx.core:core-ktx:1.13.1`, `org.jetbrains.kotlinx:
  kotlinx-coroutines-android:1.8.1` (only if a moved class needs it — `PageGestures` does not);
  test junit. **Never** `:app`, `:extension-api`, Room, SQLCipher, serialization.
- `:ext-scratchpad` — `project(":extension-api")`, `project(":paper-screen")`, `androidx.appcompat`
  + `core-ktx` + `lifecycle-runtime-ktx:2.8.7` + `kotlinx-coroutines-android:1.8.1` (the screen's IO
  hops); test junit. **No** `kotlinx.serialization`, no Room, no SQLCipher (its data is the host's).
- `:app` — `+ project(":paper-screen")`; the three g-paper `implementation` lines are removed (they
  arrive transitively via `api`). Everything else unchanged.
- `:extension-api` and the five existing extension modules — unchanged.

## Appendix C — Build & install (this arc)

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug && ./gradlew testDebugUnitTest      # nine modules after S0
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-scratchpad/build/outputs/apk/debug/ext-scratchpad-debug.apk
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.scratchpad.dev   # BOOX: re-run after a few s
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.scratchpad.dev
adb -s <serial> shell am start -a com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD_SCREEN \
    -p com.symmetricalpalmtree.notesprout.ext.scratchpad.dev     # S1: must be REFUSED (caller check)
adb -s <serial> shell ls -l /sdcard/Android/data/com.symmetricalpalmtree.notesprout.dev/files/Garden/   # the ext store .db
adb -s <serial> logcat -s NotebookActivity ScratchPadFlow ScratchPadClient ScratchPadService ScratchPadActivity ScratchStore ExtensionRegistry
adb -s 5HL21V5007384 shell setprop log.tag.ScratchPadClient DEBUG   # MIP11: per tag, before every read
```

## Appendix D — Reference map + the risk register

| Concern | Where |
|---|---|
| Original scratch pad (behaviour to mirror) | `apps/notesprout_android/app/src/main/kotlin/com/notesprout/android/{ScratchpadActivity,ScratchpadTransfer}.kt`, `data/ScratchpadRepository.kt`, `res/layout/activity_scratchpad.xml`; root `docs/scratchpad.md` (data model, host window, canvas reuse, multi-page, lasso, both transfer directions) |
| Arc-2 store (what the pad writes into) | `PAPER_NAMING_PLAN.md` §Architecture; `app/…/data/extstore/{ExtensionStores,ExtensionStoreBinder,ExtensionStoreGate}.kt`; `docs/extensions.md` §"The extension store"; `docs/crypto.md` audit item 2 |
| Arc-4 toolbar (the core action's home) | `notebook/{SelectionActions,SelectionToolbar,ToolbarAnchor}.kt`; `docs/notebook.md` §"Selection toolbar"; `docs/extensions.md` §"Selection-toolbar contributions" (the tiered UI rule — tier 2 is this arc) |
| Host client shapes to copy | `extension/ExtensionBinder.kt` (`call` — `hold` is its bind half), `extension/NamerClient.kt` (store pre-open + per-bind binder + revoke in `finally`), `extension/ObjectProviderClient.kt` (`renderAll` = one bind, N calls) |
| Notebook screen pieces that move | `notebook/{PageGestures,PageMath,UndoRedoStack,NotebookToolbar,NotebookChrome,ToolbarAnchor}.kt`, `core/{Slog,Device,TopGuard,Dialogs,ActionSheetDialog,StrokeCodec,InkColorCodec,Bitmaps}.kt`, `res/values/*`, `res/drawable/*` |
| Notebook screen behaviour to mirror on the pad | `notebook/NotebookActivity.kt` (`goImmersive`, `pushExclusions`, `whenPenIdle`, `dispatchTouchEvent`, `onResume` → `resumeDrawing`, the "Opening…" overlay, `showDeleteSheet` / `confirmDeletePage`, `doInsert` / `doDelete` / `doUndo` / `doRedo`), `res/layout/activity_notebook.xml`, `docs/notebook.md` |
| g-paper (the pad's canvas + the handoff) | `~/git/g-paper/docs/api.md` (§Data in / data out, §Tools, §Chrome cooperation, **§Lifecycle contract**, §Engine selection — `OnyxEngine.register(application)` in `Application.onCreate`), `host-responsibilities.md`, `integration-guide.md` |
| Design system for the pad | root `docs/design-system.md`, `docs/toolbar.md`; after S0 the resources live in `:paper-screen` |
| Debug menus (probe homes) | `app/src/debug/…/notebook/NotebookDebugMenu.kt`; `ext-scratchpad/src/debug/…/ScratchDebugMenu.kt` (S1) |

**Risk register (read before S1):**

1. **Two paper surfaces in two processes on EPD.** g-paper's Onyx and Ratta engines guard a
   process-global pipeline; the notebook must `releaseForHandoff()` before the launch and the pad's
   process registers its own engines. If the pad's raw layer / ink daemon does not arm, or the
   notebook's does not re-arm on return, S1 stops and the fix goes to g-paper (bump 0.1.x, republish,
   record here) — never a workaround in either host. S1 device item 1 is this, on NA5C and SNN,
   before anything else.
2. **`checkSignatures` from the extension's Activity** — `callingPackage` is set only for
   `startActivityForResult`-style launches; a plain `startActivity` from the host would leave it
   null and the check would refuse the host. The core uses the `ActivityResultLauncher` path only
   (recorded in `docs/extensions.md`).
3. **The 4 MiB cap and Binder** — settled in planning: a `put` of a 4 MiB `byte[]` would exceed the
   ~1 MB Binder transaction budget, so large values travel as `SharedMemory` (`putLarge` / `getLarge`).
   S0's device test round-trips a 4 MiB value through a real `ExtensionStoreBinder` on all three
   devices before anything is built on it; a failure stops S0 and asks (chunk across keys was the
   declined fallback).
4. **Non-transitive `R`** — S0 Q1; the wrong choice is a large mechanical diff, not a bug.
5. **`NotebookActivity` at 797 lines** — S1 moves the undo replay out first (deliverable, not a
   reason); `LibraryActivity` at 769 takes ≤ 10 lines.
